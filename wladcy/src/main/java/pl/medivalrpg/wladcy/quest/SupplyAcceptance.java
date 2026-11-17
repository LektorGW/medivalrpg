// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/** Durable acceptance/funding intent for every new escrow-backed quest, including delivery and sealed transport. */
public final class SupplyAcceptance {
    /** ACCEPTED is the default for older files, which did not have a pending acceptance stage. */
    public enum Phase { INTENT, FUNDING, CONFIRMING, ACCEPTED, AUDIT }
    public enum Status { PENDING, NO_FUNDS, UNAVAILABLE, ACCEPTED, AUDIT }
    public record Attempt(Status status, String reason) { }
    private record Write(String token, CompletableFuture<Void> future) { }

    private final SkarbiecApi economy;
    private final BankCheckpointBridge bank;
    private final SupplySettlement.Checkpoint checkpoint;
    private final Clock clock;
    private final Map<String, Write> writes = new HashMap<>();
    private final Map<String, String> persisted = new HashMap<>();
    private final Map<String, CompletableFuture<Void>> bankWrites = new HashMap<>();

    public SupplyAcceptance(final SkarbiecApi economy, final BankCheckpointBridge bank,
                            final SupplySettlement.Checkpoint checkpoint, final Clock clock) {
        this.economy = economy; this.bank = bank; this.checkpoint = checkpoint; this.clock = clock;
    }

    public static boolean pending(final QuestData.Active quest) {
        return quest.acceptancePhase != Phase.ACCEPTED || quest.acceptanceNoticePending;
    }

    private boolean saved(final QuestData.Active quest) {
        final String token = quest.acceptancePhase + "|" + quest.acceptanceBinding + "|" + quest.acceptedAt + "|" + quest.deadline;
        final Write write = this.writes.get(quest.uid);
        if (write != null) {
            if (!write.future().isDone()) return false;
            this.writes.remove(quest.uid);
            write.future().join();
            this.persisted.put(quest.uid, write.token());
        }
        if (token.equals(this.persisted.get(quest.uid))) return true;
        final CompletableFuture<Void> future = this.checkpoint.save();
        this.writes.put(quest.uid, new Write(token, future));
        return future.isDone() && this.saved(quest);
    }

    private boolean bankSaved(final QuestData.Active quest) {
        CompletableFuture<Void> future = this.bankWrites.get(quest.uid);
        if (future == null || future.isCompletedExceptionally() || future.isCancelled()) {
            future = this.bank.checkpoint(); this.bankWrites.put(quest.uid, future);
        }
        if (!future.isDone()) return false;
        future.join();
        return true;
    }

    public Attempt advance(final QuestData.Active quest) {
        try {
            if (!quest.escrow || quest.reward <= 0 || quest.acceptancePhase == null || quest.acceptanceBinding == null
                || quest.acceptanceMinutes <= 0) return audit(quest);
            final String binding = SupplySettlement.binding(quest);
            if (quest.acceptancePhase == Phase.INTENT && quest.acceptanceBinding.isEmpty()) quest.acceptanceBinding = binding;
            if (!binding.equals(quest.acceptanceBinding)) return audit(quest);
            for (int step = 0; step < 6; step++) {
                if (!this.saved(quest)) return new Attempt(Status.PENDING, "Trwa zapis zamiaru przyjęcia i finansowania zlecenia.");
                switch (quest.acceptancePhase) {
                    case INTENT -> quest.acceptancePhase = Phase.FUNDING;
                    case FUNDING -> {
                        if (!this.bank.available()) return new Attempt(Status.UNAVAILABLE, "Bank nie potwierdza trwałych zapisów.");
                        final String escrow = QuestService.escrowAccount(quest.uid);
                        final long held = this.economy.balance(escrow);
                        if (held == 0) {
                            if (this.bankWrites.containsKey(quest.uid)) return audit(quest);
                            // The immutable UID and the funding intent are already durable at this point.
                            if (!this.economy.transfer(SkarbiecApi.countryAccount(UUID.fromString(quest.country)), escrow,
                                quest.reward, "Depozyt zlecenia #" + quest.offerId + " / " + quest.uid).ok()) {
                                return new Attempt(Status.NO_FUNDS, "Skarbiec nie zarezerwował jeszcze nagrody. Ponów przyjęcie tego samego zlecenia.");
                            }
                        } else if (held != quest.reward) {
                            return audit(quest);
                        }
                        // A recovered full escrow means the old transfer already happened: never fund it twice.
                        if (!this.bankSaved(quest)) return new Attempt(Status.PENDING, "Bank zapisuje depozyt nagrody. Przyjęcie jeszcze nie jest potwierdzone.");
                        quest.acceptedAt = this.clock.millis();
                        quest.deadline = QuestRules.deadline(quest.acceptedAt, quest.acceptanceMinutes);
                        quest.acceptancePhase = Phase.CONFIRMING;
                    }
                    case CONFIRMING -> {
                        if (this.economy.balance(QuestService.escrowAccount(quest.uid)) != quest.reward) return audit(quest);
                        if (!this.bankSaved(quest)) return new Attempt(Status.PENDING, "Trwa potwierdzanie depozytu nagrody.");
                        quest.acceptancePhase = Phase.ACCEPTED;
                    }
                    case ACCEPTED -> { return new Attempt(Status.ACCEPTED, ""); }
                    case AUDIT -> { return new Attempt(Status.AUDIT, "Finansowanie przyjęcia wymaga audytu administratora."); }
                }
            }
            return new Attempt(Status.PENDING, "Ponów przyjęcie tego samego zlecenia.");
        } catch (final RuntimeException | LinkageError unavailable) {
            return new Attempt(Status.UNAVAILABLE, "Nie potwierdzono zapisu zamiaru lub depozytu. Identyfikator zlecenia pozostaje zarezerwowany.");
        }
    }

    private static Attempt audit(final QuestData.Active quest) {
        quest.acceptancePhase = Phase.AUDIT;
        return new Attempt(Status.AUDIT, "Niezgodny zamiar przyjęcia lub depozyt — wymagany audyt administratora.");
    }

    public void forget(final String uid) {
        this.writes.remove(uid); this.persisted.remove(uid); this.bankWrites.remove(uid);
    }
}
