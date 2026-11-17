// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

/**
 * Siege progress rule (pure). Every second the attackers' strength in the chunk (players + NPC soldiers from
 * {@code SiegeContributor}s) is compared with the defenders':
 * <ul>
 *   <li>attackers stronger → progress grows,</li>
 *   <li>defenders stronger → progress shrinks (never below 0),</li>
 *   <li>equal strength, including nobody present ("no fight") → progress stays.</li>
 * </ul>
 * Speed is proportional to the advantage: one unopposed fighter (advantage 1.0) needs {@code captureSeconds}
 * for 0 → 100 %; the multiplier is capped at {@code maxMultiplier}.
 */
public final class SiegeMath {

    private static final double EPSILON = 1e-6;

    private SiegeMath() {
    }

    public static double step(final double progress, final double attackers, final double defenders, final double seconds,
                              final double captureSeconds, final double maxMultiplier) {
        final double diff = Math.max(0, attackers) - Math.max(0, defenders);
        if (Math.abs(diff) < EPSILON || captureSeconds <= 0) {
            return clamp(progress);
        }
        final double multiplier = Math.min(Math.max(1.0, maxMultiplier), Math.abs(diff));
        final double delta = Math.signum(diff) * multiplier * seconds / captureSeconds;
        return clamp(progress + delta);
    }

    /** -1 falling, 0 standing, 1 rising (for the boss bar colour / text). */
    public static int trend(final double attackers, final double defenders) {
        final double diff = attackers - defenders;
        return Math.abs(diff) < EPSILON ? 0 : diff > 0 ? 1 : -1;
    }

    private static double clamp(final double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
