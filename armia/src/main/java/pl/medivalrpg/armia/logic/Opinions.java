// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/**
 * Opinions of NPC countries (−100..100). Positive opinions fade by 1 point per AI tick; negative ones ("revenge")
 * recover only 1 point every {@code negativeDecayTicks} ticks, so an aggressor is remembered much longer. Pure.
 */
public final class Opinions {

    private Opinions() {
    }

    public static int clamp(final long v) {
        return (int) Math.max(-100, Math.min(100, v));
    }

    public static int add(final int opinion, final int delta) {
        return clamp((long) opinion + delta);
    }

    /** Opinion after one AI tick number {@code tick}. */
    public static int decay(final int opinion, final long tick, final int negativeDecayTicks) {
        if (opinion > 0) {
            return opinion - 1;
        }
        if (opinion < 0) {
            final boolean due = negativeDecayTicks <= 1 || Math.floorMod(tick, negativeDecayTicks) == 0;
            return due ? opinion + 1 : opinion;
        }
        return 0;
    }
}
