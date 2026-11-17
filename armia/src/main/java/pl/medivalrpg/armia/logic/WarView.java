// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/**
 * One running war from the AI country's point of view.
 *
 * @param ourStrength   our strength
 * @param theirStrength enemy strength
 * @param hours         war duration in hours
 * @param losses        soldiers we lost in this war
 * @param opinion       our opinion of the enemy (tribute during the war raises it)
 */
public record WarView(double ourStrength, double theirStrength, double hours, int losses, int opinion) {

    /** We / they (they never below 0.5, so an empty enemy does not divide by zero). */
    public double ratio() {
        return this.ourStrength / Math.max(0.5, this.theirStrength);
    }
}
