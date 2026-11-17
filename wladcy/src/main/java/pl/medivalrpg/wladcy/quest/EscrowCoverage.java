// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.quest;

import java.util.UUID;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Every reward of an NPC country's quest is covered by real money of that country (closed world pool, 2026-10-29).
 *
 * <p>The binding quest of a player is created at acceptance: {@link SupplyAcceptance} first persists a stable UID and
 * then moves the reward from {@code country:<npc>} into the quest's own escrow account {@code wladcy:zlecenie-<uid>};
 * without funds the quest is not created. This class settles what happens afterwards, with the escrow balance itself as
 * the idempotency key (the account is unique per UID):</p>
 * <ul>
 *   <li>{@link #payout}: DELIVERED/turned-in quests are paid <b>only from their escrow</b> — never minted, never a second
 *       time from the treasury (a crash between payout and the saved completion leaves an empty escrow, so a repeated
 *       turn-in pays nothing);</li>
 *   <li>{@link #refund}: expiry, abandonment and cancellation return what is held to the country;</li>
 *   <li>{@link #reconcile}: at start, legacy quests without escrow (old {@code reward.create-money}) are covered from the
 *       country treasury when it can pay, otherwise marked {@code uncovered}; escrow quests whose deposit is missing are
 *       marked for audit — they are never re-funded, because the money may already have been paid out.</li>
 * </ul>
 * Only {@code balance} and {@code transfer} are used; {@code deposit}/{@code withdraw} (money in/out of the pool) never.
 */
public final class EscrowCoverage {

    /** Coverage of one active quest. */
    public enum State { SECURED, PENDING_ACCEPTANCE, SUPPLY, UNCOVERED, AUDIT }

    public record Summary(int secured, int funded, int uncovered, int audit) {
        public boolean clean() {
            return this.uncovered == 0 && this.audit == 0;
        }
    }

    private EscrowCoverage() {
    }

    public static String countryAccount(final QuestData.Active a) {
        return SkarbiecApi.countryAccount(UUID.fromString(a.country));
    }

    /** Coverage now (read only). */
    public static State state(final QuestData.Active a, final SkarbiecApi eco) {
        if (SupplyAcceptance.pending(a)) {
            return a.acceptancePhase == SupplyAcceptance.Phase.AUDIT ? State.AUDIT : State.PENDING_ACCEPTANCE;
        }
        if (a.supplyDelivery) {
            return State.SUPPLY; // SupplySettlement owns the escrow of warehouse deliveries
        }
        if (a.reward <= 0) {
            return State.SECURED;
        }
        final long held = eco.balance(QuestService.escrowAccount(a.uid));
        if (a.escrow && held == a.reward) {
            return State.SECURED;
        }
        if (a.escrow) {
            return State.AUDIT;
        }
        return State.UNCOVERED;
    }

    /** Players see whether their reward is locked away. */
    public static boolean secured(final QuestData.Active a, final SkarbiecApi eco) {
        final State s = state(a, eco);
        return s == State.SECURED || s == State.SUPPLY;
    }

    /**
     * Start-up check of every accepted, non-warehouse quest. Legacy quests are funded from the country's treasury
     * (idempotent: a full escrow left by an interrupted earlier run only sets the flag). Changes {@code a.escrow} and
     * {@code a.uncovered}; the caller persists the book.
     */
    public static Summary reconcile(final Iterable<QuestData.Active> quests, final SkarbiecApi eco) {
        int secured = 0;
        int funded = 0;
        int uncovered = 0;
        int audit = 0;
        for (final QuestData.Active a : quests) {
            if (SupplyAcceptance.pending(a) || a.supplyDelivery || a.reward <= 0) {
                continue;
            }
            final String escrow = QuestService.escrowAccount(a.uid);
            final long held = eco.balance(escrow);
            if (a.escrow) {
                if (held == a.reward) {
                    a.uncovered = false;
                    secured++;
                } else {
                    a.uncovered = true; // never re-funded: it may already have been paid or refunded
                    audit++;
                }
                continue;
            }
            if (held == a.reward) {
                a.escrow = true; // an earlier reconcile moved the money but the flag was not saved
                a.uncovered = false;
                funded++;
            } else if (held == 0 && eco.transfer(countryAccount(a), escrow, a.reward,
                "Wladcy: dociągnięcie depozytu zlecenia #" + a.offerId + " / " + a.uid).ok()) {
                a.escrow = true;
                a.uncovered = false;
                funded++;
            } else {
                a.uncovered = true;
                if (held == 0) {
                    uncovered++;
                } else {
                    audit++;
                }
            }
        }
        return new Summary(secured, funded, uncovered, audit);
    }

    /**
     * Pays a turned-in quest. Escrow quests: what the escrow holds (at most the reward), else nothing. Legacy uncovered
     * quests: only from the country's treasury, only when it can pay the whole reward. Returns what was paid.
     */
    public static long payout(final QuestData.Active a, final SkarbiecApi eco, final String to, final String reason) {
        if (a.reward <= 0) {
            return 0;
        }
        if (a.escrow) {
            final String escrow = QuestService.escrowAccount(a.uid);
            final long amount = Math.min(a.reward, eco.balance(escrow));
            return amount > 0 && eco.transfer(escrow, to, amount, reason).ok() ? amount : 0;
        }
        return eco.transfer(countryAccount(a), to, a.reward, reason + " (ze skarbca, bez depozytu)").ok() ? a.reward : 0;
    }

    /** Returns what the escrow holds (at most the reward) to the country. Idempotent. Returns the refunded amount. */
    public static long refund(final QuestData.Active a, final SkarbiecApi eco) {
        if (!a.escrow) {
            return 0;
        }
        final String escrow = QuestService.escrowAccount(a.uid);
        final long amount = Math.min(eco.balance(escrow), Math.max(0, a.reward));
        return amount > 0 && eco.transfer(escrow, countryAccount(a), amount, "Zwrot depozytu zlecenia #" + a.offerId).ok() ? amount : 0;
    }
}
