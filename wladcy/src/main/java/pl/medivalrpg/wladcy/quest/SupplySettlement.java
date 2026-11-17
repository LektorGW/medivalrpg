// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/** Main-thread orchestration; only checkpoints run asynchronously. Never touches an item or creates money. */
public final class SupplySettlement {
    public enum Phase { NEW, FUNDING, REQUESTED, BLOCKED, DELIVERED, PAYING, PAID, AUDIT }
    public enum Status { PENDING, BLOCKED, UNAVAILABLE, PAYMENT, PAID, AUDIT }
    public record Attempt(Status status, String reason) { }

    @FunctionalInterface
    public interface Checkpoint {
        CompletableFuture<Void> save();
    }

    private record Write(String token, CompletableFuture<Void> saved) { }
    private final SupplyDeliveryBridge bridge;
    private final SkarbiecApi economy;
    private final BankCheckpointBridge bank;
    private final Checkpoint checkpoint;
    private final Map<String, Write> writes = new HashMap<>();
    private final Map<String, String> persisted = new HashMap<>();
    private final Map<String, CompletableFuture<Void>> fundingAcknowledgements = new HashMap<>();
    /** Presence means this process already moved the money. Failed bank acknowledgements must not repeat it. */
    private final Map<String, CompletableFuture<Void>> paymentAcknowledgements = new HashMap<>();

    public SupplySettlement(final SupplyDeliveryBridge bridge, final SkarbiecApi economy, final BankCheckpointBridge bank, final Checkpoint checkpoint) {
        this.bridge = bridge;
        this.economy = economy;
        this.bank = bank;
        this.checkpoint = checkpoint;
    }

    private boolean bankAcknowledged(final Map<String, CompletableFuture<Void>> acknowledgements, final String uid) {
        CompletableFuture<Void> future = acknowledgements.get(uid);
        if (future == null || future.isCompletedExceptionally() || future.isCancelled()) {
            future = this.bank.checkpoint();
            acknowledgements.put(uid, future);
        }
        if (!future.isDone()) return false;
        future.join();
        return true;
    }

    public static String contractId(final QuestData.Active quest) {
        return "wladcy:" + quest.uid;
    }

    /** Pending/ambiguous delivery must never expire or refund money while the provider might own the items. */
    public static boolean protectedFromCancellation(final QuestData.Active quest) {
        return SupplyAcceptance.pending(quest)
            || quest.supplyDelivery && quest.supplyPhase != Phase.NEW && quest.supplyPhase != Phase.BLOCKED;
    }

    static String binding(final QuestData.Active quest) {
        final String value = String.join("|", quest.uid, quest.player, quest.country, quest.type, quest.material,
            Integer.toString(quest.amount), Long.toString(quest.reward), Boolean.toString(quest.escrow));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private boolean saved(final QuestData.Active quest) {
        final String token = quest.supplyPhase + "|" + quest.supplyBinding + "|" + quest.supplyReceipt;
        final Write pending = this.writes.get(quest.uid);
        if (pending != null) {
            if (!pending.saved().isDone()) {
                return false;
            }
            this.writes.remove(quest.uid);
            pending.saved().join(); // Failed writes propagate: no subsequent item or money operation is allowed.
            this.persisted.put(quest.uid, pending.token());
        }
        if (token.equals(this.persisted.get(quest.uid))) {
            return true;
        }
        final CompletableFuture<Void> future = this.checkpoint.save();
        this.writes.put(quest.uid, new Write(token, future));
        if (future.isDone()) {
            return this.saved(quest);
        }
        return false;
    }

    public Attempt advance(final QuestData.Active quest) {
        try {
            if (SupplyAcceptance.pending(quest)) return new Attempt(Status.PENDING, "Trwa finansowanie przyjęcia zlecenia.");
            if (!quest.supplyDelivery || quest.type() != QuestType.SUROWCE || !quest.escrow || quest.reward <= 0
                || quest.supplyPhase == null || quest.supplyReceipt == null || quest.supplyBinding == null) {
                return audit(quest);
            }
            final String binding = binding(quest);
            if (quest.supplyPhase == Phase.NEW && quest.supplyBinding.isEmpty()) {
                quest.supplyBinding = binding;
            }
            if (!binding.equals(quest.supplyBinding)) {
                return audit(quest);
            }
            if ((quest.supplyPhase == Phase.DELIVERED || quest.supplyPhase == Phase.PAYING || quest.supplyPhase == Phase.PAID)
                && quest.supplyReceipt.isBlank()) {
                return audit(quest);
            }
            boolean freshDelivery = false;
            // Never carry provider authorization across a disk/bank wait, retry, or process restart.
            for (int step = 0; step < 8; step++) {
                if (!this.saved(quest)) {
                    return new Attempt(Status.PENDING, "Trwa bezpieczny zapis rozliczenia. Ponów oddanie.");
                }
                switch (quest.supplyPhase) {
                    case NEW -> quest.supplyPhase = Phase.FUNDING;
                    case FUNDING -> {
                        if (this.economy.balance(QuestService.escrowAccount(quest.uid)) != quest.reward) return audit(quest);
                        if (!this.bankAcknowledged(this.fundingAcknowledgements, quest.uid)) {
                            return new Attempt(Status.PENDING, "Bank zapisuje rezerwację nagrody. Ponów oddanie.");
                        }
                        quest.supplyPhase = Phase.REQUESTED;
                    }
                    case BLOCKED -> quest.supplyPhase = Phase.REQUESTED;
                    case REQUESTED -> {
                        if (this.economy.balance(QuestService.escrowAccount(quest.uid)) != quest.reward) {
                            return audit(quest);
                        }
                        final SupplyDeliveryBridge.Result result = this.bridge.deliver(UUID.fromString(quest.player),
                            UUID.fromString(quest.country), contractId(quest), quest.material, quest.amount);
                        if (!result.receipt().isBlank()) {
                            if (!quest.supplyReceipt.isBlank() && !quest.supplyReceipt.equals(result.receipt())) return audit(quest);
                            quest.supplyReceipt = result.receipt();
                        }
                        switch (result.status()) {
                            case PENDING -> { return new Attempt(Status.PENDING, result.reason()); }
                            case UNAVAILABLE -> { return new Attempt(Status.UNAVAILABLE, result.reason()); }
                            case BLOCKED -> {
                                // A registered/ambiguous provider receipt may still own items. Never refund it blindly.
                                if (!quest.supplyReceipt.isBlank()) return audit(quest);
                                quest.supplyPhase = Phase.BLOCKED;
                                this.saved(quest);
                                return new Attempt(Status.BLOCKED, result.reason());
                            }
                            case DELIVERED -> {
                                quest.supplyReceipt = result.receipt();
                                quest.supplyPhase = Phase.DELIVERED;
                                freshDelivery = true;
                            }
                        }
                    }
                    case DELIVERED -> {
                        if (quest.supplyReceipt.isBlank()) {
                            return audit(quest);
                        }
                        if (!freshDelivery) {
                            final Attempt denied = this.revalidateDelivery(quest);
                            if (denied != null) return denied;
                            freshDelivery = true;
                        }
                        quest.supplyPhase = Phase.PAYING;
                    }
                    case PAYING -> {
                        if (!this.paymentAcknowledgements.containsKey(quest.uid)) {
                            if (!freshDelivery) {
                                final Attempt denied = this.revalidateDelivery(quest);
                                if (denied != null) return denied;
                                freshDelivery = true;
                            }
                            if (!this.bank.available()) return new Attempt(Status.PAYMENT, "Bank nie potwierdza trwałych zapisów. Wypłata czeka.");
                            // Empty/partial escrow after a crash is ambiguous, not permission to debit the treasury again.
                            if (this.economy.balance(QuestService.escrowAccount(quest.uid)) != quest.reward) return audit(quest);
                            if (!this.economy.transfer(QuestService.escrowAccount(quest.uid),
                                SkarbiecApi.playerAccount(UUID.fromString(quest.player)), quest.reward,
                                "Nagroda za dostawę do magazynu #" + quest.offerId).ok()) {
                                return new Attempt(Status.PAYMENT, "Dostawa potwierdzona; depozyt czeka na wypłatę. Ponów oddanie.");
                            }
                            this.paymentAcknowledgements.put(quest.uid, this.bank.checkpoint());
                        }
                        if (!this.bankAcknowledged(this.paymentAcknowledgements, quest.uid)) {
                            return new Attempt(Status.PAYMENT, "Bank zapisuje wypłatę. Ponów oddanie tego samego zlecenia.");
                        }
                        quest.supplyPhase = Phase.PAID;
                    }
                    case PAID -> { return new Attempt(Status.PAID, ""); }
                    case AUDIT -> { return new Attempt(Status.AUDIT, "Rozliczenie wymaga audytu administratora."); }
                }
            }
            return new Attempt(Status.PENDING, "Ponów oddanie tego samego zlecenia.");
        } catch (final RuntimeException | LinkageError unavailable) {
            return new Attempt(Status.UNAVAILABLE, "Nie potwierdzono zapisu lub rozliczenia. Ponów oddanie tego samego zlecenia.");
        }
    }

    private Attempt revalidateDelivery(final QuestData.Active quest) {
        final SupplyDeliveryBridge.Result result = this.bridge.deliver(UUID.fromString(quest.player), UUID.fromString(quest.country),
            contractId(quest), quest.material, quest.amount);
        if (!result.receipt().isBlank() && !result.receipt().equals(quest.supplyReceipt)) return audit(quest);
        if (result.status() != SupplyDeliveryBridge.Status.DELIVERED) {
            return new Attempt(result.status() == SupplyDeliveryBridge.Status.UNAVAILABLE ? Status.UNAVAILABLE : Status.PENDING,
                "Magazyn musi ponownie potwierdzić ten sam odbiór przed wypłatą. " + result.reason());
        }
        return null;
    }

    private static Attempt audit(final QuestData.Active quest) {
        quest.supplyPhase = Phase.AUDIT;
        return new Attempt(Status.AUDIT, "Niezgodne dane kontraktu lub depozytu — wymagany audyt administratora.");
    }

    public void forget(final String uid) {
        this.writes.remove(uid);
        this.persisted.remove(uid);
        this.fundingAcknowledgements.remove(uid);
        this.paymentAcknowledgements.remove(uid);
    }
}
