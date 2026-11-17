// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic.conquest;

import java.util.function.DoubleSupplier;

/**
 * Abstract siege of one chunk, fought in rounds far from players (pure).
 *
 * <p>Strength of a side = Σ soldiers (unit siege strength × era multiplier × morale) × multipliers. The attacker suffers
 * from distance to its capital, expeditions without a common border, war exhaustion and overextension; the defender gains
 * near its capital and from walls, towers and castles of Ludnosc (siege engines of the attacker cancel part of it) and
 * always has some militia. Each round moves the siege progress by the strength ratio plus luck and kills soldiers on both
 * sides (the stronger side loses fewer). The siege ends at 100 % (conquest), when the attacker has too few soldiers left,
 * is pushed back to 0 % or runs out of rounds.</p>
 */
public final class BattleMath {

    private BattleMath() {
    }

    /** Strength of one soldier: unit siege strength (≥ 0.2) × era/HP multiplier × morale (50 % at 0 morale). */
    public static double soldier(final double siegeStrength, final double eraMultiplier, final int morale) {
        final double m = Math.max(0, Math.min(100, morale)) / 100.0;
        return Math.max(0.2, siegeStrength) * Math.max(0.1, eraMultiplier) * (0.5 + 0.5 * m);
    }

    /** 1 at no exhaustion, {@code 1 − penalty} at 100. */
    public static double exhaustion(final double exhaustion, final ConquestSettings s) {
        return 1.0 - s.exhaustion().strengthPenalty() * Math.max(0, Math.min(100, exhaustion)) / 100.0;
    }

    /** Large countries spread their army thin: free/chunks, never below the floor; 1 up to {@code freeChunks}. */
    public static double overextension(final int chunks, final ConquestSettings s) {
        final int free = s.overextension().freeChunks();
        if (chunks <= free) {
            return 1.0;
        }
        return Math.max(s.overextension().strengthFloor(), (double) free / chunks);
    }

    /** Supply lines: 1 at the capital, {@code 1 − distancePenalty} at the expedition reach and beyond. */
    public static double supply(final double distanceChunks, final ConquestSettings s) {
        final double f = Math.min(1.0, Math.max(0, distanceChunks) / Math.max(1, s.expeditionMaxDistance()));
        return 1.0 - s.distancePenalty() * f;
    }

    /** Attacker multiplier for a target {@code distance} chunks from its capital. */
    public static double attacker(final double distance, final boolean expedition, final boolean ship, final double exhaustion,
                                  final int chunks, final ConquestSettings s) {
        double m = supply(distance, s) * exhaustion(exhaustion, s) * overextension(chunks, s);
        if (expedition) {
            m *= Math.min(1.0, s.expeditionPenalty() + (ship ? s.navalBonus() : 0));
        }
        return m;
    }

    /** Fortification bonus around the target (walls/gates, towers, castles/palaces), capped. */
    public static double fortification(final int walls, final int towers, final int castles, final ConquestSettings s) {
        final double raw = Math.max(0, walls) * s.fortWall() + Math.max(0, towers) * s.fortTower() + Math.max(0, castles) * s.fortCastle();
        return Math.min(s.fortMax(), raw);
    }

    /** Siege engines (catapults, cannons) of the attacker break part of the fortification bonus. */
    public static double effectiveFort(final double fort, final int engines, final ConquestSettings s) {
        return fort * Math.max(0, 1.0 - Math.max(0, engines) * s.engineVsFort());
    }

    /** Defender multiplier: capital bonus fading with distance × (1 + fortification) × exhaustion. */
    public static double defender(final double distanceToCapital, final double effectiveFort, final double exhaustion,
                                  final ConquestSettings s) {
        final double cap = s.capitalBonus() * Math.max(0, 1.0 - Math.max(0, distanceToCapital) / s.capitalBonusRange());
        return (1.0 + cap) * (1.0 + Math.max(0, effectiveFort)) * exhaustion(exhaustion, s);
    }

    /** Result of one round. */
    public record Round(double progress, double delta, double ratio, int attackerLosses, int defenderLosses) {
    }

    /**
     * One round. {@code attack}/{@code defence} are final strengths (with multipliers, allies and militia);
     * {@code attackers}/{@code defenders} the numbers of soldiers that can die. {@code random} gives values in [0, 1).
     */
    public static Round round(final double attack, final double defence, final int attackers, final int defenders,
                              final double progress, final ConquestSettings s, final DoubleSupplier random) {
        final double a = Math.max(0, attack);
        final double d = Math.max(0.01, defence);
        final double ratio = a / (a + d);
        final double swing = (random.getAsDouble() * 2 - 1) * s.luck();
        final double delta = ((ratio - 0.5) * 2 + swing) * s.progressPerRound();
        final double next = Math.max(0, Math.min(1, progress + delta));
        final double attackerExpected = s.casualtyRate() * Math.max(0, attackers) * (1 - ratio) * 2 * (0.75 + 0.5 * random.getAsDouble());
        final double defenderExpected = s.casualtyRate() * Math.max(0, defenders) * ratio * 2 * (0.75 + 0.5 * random.getAsDouble());
        return new Round(next, next - progress, ratio,
            Math.min(Math.max(0, attackers), stochastic(attackerExpected, random)),
            Math.min(Math.max(0, defenders), stochastic(defenderExpected, random)));
    }

    /** Rounds {@code x} up with probability equal to its fraction (expected value preserved). */
    static int stochastic(final double x, final DoubleSupplier random) {
        if (x <= 0) {
            return 0;
        }
        final int whole = (int) Math.floor(x);
        return whole + (random.getAsDouble() < x - whole ? 1 : 0);
    }

    /** How an offensive stands after a round. */
    public enum Verdict { CONTINUE, CAPTURED, BROKEN, REPULSED, STALLED }

    public static Verdict verdict(final double progress, final int rounds, final int attackersLeft, final ConquestSettings s) {
        if (progress >= 1.0 - 1e-9) {
            return Verdict.CAPTURED;
        }
        if (attackersLeft < Math.max(1, (s.minSoldiers() + 1) / 2)) {
            return Verdict.BROKEN;
        }
        if (progress <= 1e-9 && rounds >= 3) {
            return Verdict.REPULSED;
        }
        if (rounds >= s.maxRounds()) {
            return Verdict.STALLED;
        }
        return Verdict.CONTINUE;
    }
}
