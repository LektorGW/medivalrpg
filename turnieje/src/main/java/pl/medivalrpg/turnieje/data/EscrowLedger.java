// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Money held for one tournament on its escrow account ({@code turnieje:<session>}). Persisted in {@code state.json}
 * after every change, so that a crash or restart mid-tournament can refund everybody at the next start. Pure
 * bookkeeping: the actual transfers are done by the caller through Skarbiec. Plain fields for Gson.
 *
 * <ul>
 *   <li>{@code funded} — moved from {@code sourceAccount} (country treasury or server pool) into escrow;</li>
 *   <li>{@code minted} — promised pool created from nothing at payout (only when the config allows minting);
 *       never refunded, because it never existed;</li>
 *   <li>{@code fees} — entry fees paid by players into escrow.</li>
 * </ul>
 */
public final class EscrowLedger {

    public String sessionId;
    public String title;
    public String escrowAccount;
    /** Where the funded pool came from and where the remainder goes; null = no source (nothing funded). */
    public String sourceAccount;
    public boolean settling;
    public Map<String, Long> payments = new LinkedHashMap<>();
    public long funded;
    public long minted;
    public Map<UUID, Long> fees = new LinkedHashMap<>();
    /** Weekly occurrence key while still in sign-ups (a restart then reopens that week's sign-ups); null otherwise. */
    public String reopenWeeklyKey;

    public EscrowLedger() {
    }

    public EscrowLedger(final String sessionId, final String title, final String escrowAccount) {
        this.sessionId = sessionId;
        this.title = title;
        this.escrowAccount = escrowAccount;
    }

    public void addFee(final UUID player, final long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("fee < 0");
        }
        if (amount > 0) {
            this.fees.merge(player, amount, Math::addExact);
        }
    }

    /** Removes and returns the fee of a player (0 when none). */
    public long removeFee(final UUID player) {
        final Long v = this.fees.remove(player);
        return v == null ? 0L : v;
    }

    public long feeOf(final UUID player) {
        return this.fees.getOrDefault(player, 0L);
    }

    public long totalFees() {
        return this.fees.values().stream().reduce(0L, Math::addExact);
    }

    /** Money really sitting in escrow. */
    public long held() {
        return Math.addExact(this.funded, this.totalFees());
    }

    /** Pool announced and paid out: held money plus the minted part. */
    public long prizePool() {
        return Math.addExact(this.held(), this.minted);
    }

    /** What a cancellation returns: each fee to its payer and {@code funded} to the source. */
    public record RefundPlan(Map<UUID, Long> fees, String sourceAccount, long toSource) {
        public long total() {
            return this.toSource + this.fees.values().stream().reduce(0L, Math::addExact);
        }
    }

    public RefundPlan refundPlan() {
        return new RefundPlan(Map.copyOf(this.fees), this.sourceAccount, this.sourceAccount == null ? 0 : this.funded);
    }

    /** After a refund or payout nothing is held any more. */
    public void clear() {
        this.fees.clear();
        this.funded = 0;
        this.minted = 0;
    }
}
