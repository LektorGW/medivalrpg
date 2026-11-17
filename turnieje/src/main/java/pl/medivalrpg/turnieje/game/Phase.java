// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import java.util.EnumSet;
import java.util.Set;

/**
 * Phases of a tournament session and the allowed transitions (state machine). Pure logic.
 *
 * <pre>
 * SIGNUPS ─▶ PREPARING ─▶ INTERMISSION ⇄ COUNTDOWN ─▶ FIGHTING ─▶ INTERMISSION … ─▶ CELEBRATION ─▶ FINISHED
 *    └──────────┴──────────────┴──────────────┴────────────┴──▶ CANCELLED (refund)
 * </pre>
 */
public enum Phase {
    /** Sign-ups open, countdown to the start. */
    SIGNUPS,
    /** Snapshots, teleports, bracket creation. */
    PREPARING,
    /** Between matches. */
    INTERMISSION,
    /** Fighters in place, damage blocked, 3…2…1. */
    COUNTDOWN,
    /** Fight in progress. */
    FIGHTING,
    /** Winner known, prizes paid, fireworks; participants are restored at the end. */
    CELEBRATION,
    FINISHED,
    CANCELLED;

    public Set<Phase> next() {
        return switch (this) {
            case SIGNUPS -> EnumSet.of(PREPARING, CANCELLED);
            case PREPARING -> EnumSet.of(INTERMISSION, CELEBRATION, CANCELLED);
            case INTERMISSION -> EnumSet.of(COUNTDOWN, CELEBRATION, CANCELLED);
            case COUNTDOWN -> EnumSet.of(FIGHTING, INTERMISSION, CELEBRATION, CANCELLED);
            case FIGHTING -> EnumSet.of(INTERMISSION, CELEBRATION, CANCELLED);
            case CELEBRATION -> EnumSet.of(FINISHED);
            case FINISHED, CANCELLED -> EnumSet.noneOf(Phase.class);
        };
    }

    public boolean canMoveTo(final Phase target) {
        return this.next().contains(target);
    }

    public boolean terminal() {
        return this == FINISHED || this == CANCELLED;
    }

    /** Participants are inside the event (snapshot taken, rules for commands/teleports apply). */
    public boolean running() {
        return this == PREPARING || this == INTERMISSION || this == COUNTDOWN || this == FIGHTING || this == CELEBRATION;
    }

    /** A cancellation in this phase refunds fees and the pool (prizes are paid when CELEBRATION starts). */
    public boolean refundable() {
        return this == SIGNUPS || this == PREPARING || this == INTERMISSION || this == COUNTDOWN || this == FIGHTING;
    }

    /** Fighters are on the field (countdown or fight). */
    public boolean matchLive() {
        return this == COUNTDOWN || this == FIGHTING;
    }
}
