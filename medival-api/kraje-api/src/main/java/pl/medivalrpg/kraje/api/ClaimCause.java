// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

/** Why a chunk changed owner. */
public enum ClaimCause {
    /** Player command {@code /kraj zajmij} / {@code porzuc}. */
    PLAYER,
    /** NPC country AI (obeys the same rules and costs as players). */
    NPC,
    /** Admin command or API with {@code force}: no rules, no cost. */
    ADMIN,
    /** Won siege: chunk moved from defender to attacker. */
    CONQUEST,
    /** Lost because upkeep could not be paid. */
    BANKRUPTCY
}
