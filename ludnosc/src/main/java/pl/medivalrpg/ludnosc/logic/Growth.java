// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

/** Population growth rule (pure): a new settler arrives only with a free bed and enough food in the granary. */
public final class Growth {

    private Growth() {
    }

    /**
     * Number of settlers that may arrive in this growth tick.
     *
     * @param population      current citizens
     * @param pending         settlers already on their way (spawn in progress)
     * @param housing         beds in housing buildings
     * @param food            food in the granary
     * @param foodPerSettler  food consumed by one new settler
     * @param maxPerTick      cap per tick
     */
    public static int newSettlers(final int population, final int pending, final int housing, final long food,
                                  final long foodPerSettler, final int maxPerTick) {
        final int freeBeds = housing - population - pending;
        if (freeBeds <= 0 || maxPerTick <= 0) {
            return 0;
        }
        final long affordable = foodPerSettler <= 0 ? Long.MAX_VALUE : Math.max(0, food) / foodPerSettler;
        return (int) Math.min(Math.min(freeBeds, maxPerTick), affordable);
    }
}
