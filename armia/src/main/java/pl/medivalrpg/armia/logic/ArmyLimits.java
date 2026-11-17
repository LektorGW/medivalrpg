// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/** Soldier caps: per country (base + per chunk, hard max) and global. Pure. */
public final class ArmyLimits {

    private ArmyLimits() {
    }

    /** Max soldiers of a country with {@code chunks} chunks. */
    public static int countryCap(final int base, final double perChunk, final int chunks, final int hardMax) {
        final long cap = (long) base + (long) Math.floor(Math.max(0, perChunk) * Math.max(0, chunks));
        return (int) Math.max(0, Math.min(hardMax, cap));
    }

    /** How many of {@code requested} soldiers may be added now (0 if none). */
    public static int allowed(final int requested, final int countryCount, final int countryCap, final int globalCount, final int globalCap) {
        final int byCountry = countryCap - countryCount;
        final int byGlobal = globalCap - globalCount;
        return Math.max(0, Math.min(requested, Math.min(byCountry, byGlobal)));
    }

    /**
     * Admin/scenario/panel additions ignore the country cap but NEVER the global cap (decision: 1000 for the whole
     * server). Older code allowed up to twice the global cap here.
     */
    public static int adminAllowed(final int requested, final int globalCount, final int globalCap) {
        return Math.max(0, Math.min(requested, globalCap - globalCount));
    }
}
