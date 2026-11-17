// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

/**
 * Mood factor {@code zarobki} from the citizens' wages and savings (Ludnosc, pure): workers without their full pay
 * lower the mood (more unpaid = worse, up to {@code maxPenalty}); comfortable average savings raise it a little.
 *
 * @param maxPenalty  strongest penalty (positive number of points)
 * @param wealthBonus bonus when the average savings reach {@code wealthFrom}
 * @param wealthFrom  average savings (md) for the bonus; 0 = no bonus
 */
public record WageMood(int maxPenalty, int wealthBonus, long wealthFrom) {

    public static WageMood defaults() {
        return new WageMood(12, 3, 30);
    }

    /** Points of the factor: negative with unpaid workers, else the wealth bonus (or 0). */
    public int points(final int unpaidPercent, final long averageSavings) {
        if (unpaidPercent > 0) {
            return -Math.min(this.maxPenalty, 2 + Math.max(0, Math.min(100, unpaidPercent)) / 10);
        }
        return this.wealthFrom > 0 && averageSavings >= this.wealthFrom ? this.wealthBonus : 0;
    }
}
