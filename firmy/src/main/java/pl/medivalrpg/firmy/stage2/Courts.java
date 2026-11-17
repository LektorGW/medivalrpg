// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage2;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * NPC courts (wholesale orders) and the World Treasury (loans approved by the owner). Implemented with Kraje, Wladcy
 * (reflection, optional) and Skarbiec (reflection); faked in tests.
 */
public interface Courts {

    record Court(UUID id, String name, String treasuryAccount, String world, int capitalX, int capitalZ) {
    }

    /** Budget of an NPC treasury for one wholesale order now: per order and what is left of today's shared limit. */
    record Budget(long perOrder, long perDayLeft) {
        public long max() {
            return Math.max(0, Math.min(this.perOrder, this.perDayLeft));
        }
    }

    List<Court> npcCountries();

    /** Shared budget with the quests of Wladcy (same treasury percentages); empty = Wladcy has no such bridge. */
    Optional<Budget> wladcyBudget(UUID npc);

    /** Books a funded wholesale order into Wladcy's daily budget of the country (shared limit). */
    void booked(UUID npc, long amount);

    /** Reputation for the player who delivered a wholesale order (Wladcy, daily-capped there). */
    void reputation(UUID npc, UUID player, String name, int delta);

    /** Owner-approved allocation from the World Treasury reserve to a loan escrow; empty = done, else the refusal. */
    Optional<String> allocateLoan(UUID owner, String escrow, long amount, String firmName);

    /** Owner returns repaid World Treasury loan money from the escrow to the reserve; empty = done. */
    Optional<String> returnLoan(UUID owner, String escrow, long amount);
}
