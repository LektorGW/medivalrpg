// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

/**
 * Cost formulas (pure functions, all amounts in copper).
 */
public final class Costs {

    private Costs() {
    }

    /**
     * Price of the next chunk: {@code base + perChunk × chunks the country already has}.
     * The capital, claimed at founding, is never charged here (it is covered by the founding cost).
     */
    public static long claimCost(final long base, final long perChunk, final int currentChunks) {
        return Math.max(0, base) + Math.max(0, perChunk) * Math.max(0, currentChunks);
    }

    /** Daily upkeep: {@code perChunk × max(0, chunks − freeChunks)}. */
    public static long upkeep(final long perChunk, final int freeChunks, final int chunks) {
        return Math.max(0, perChunk) * Math.max(0, chunks - Math.max(0, freeChunks));
    }

    /** What a taxpayer pays this tick: today's tax plus older debt, limited by what they have. */
    public static long taxPayment(final long tax, final long debt, final long balance) {
        final long due = Math.max(0, tax) + Math.max(0, debt);
        return Math.max(0, Math.min(due, balance));
    }
}
