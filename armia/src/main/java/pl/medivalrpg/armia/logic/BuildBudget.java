// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/**
 * How much of the treasury an NPC country gives the Ludnosc settlement planner per AI tick, and how much a rich
 * country keeps away from the army while it saves for a representative building. Pure; money in copper.
 */
public final class BuildBudget {

    /**
     * @param baseShare      share of the spendable treasury at bogactwo = ekspansja = 0
     * @param wealthShare    extra share at bogactwo = 100
     * @param expansionShare extra share at ekspansja = 100
     * @param warCut         fraction of the share (and of the savings) moved to the army while at war
     * @param maxShare       cap of the share
     * @param minBudget      smaller budgets are not worth a call (0 is returned)
     * @param richWealth     bogactwo from which a country counts as rich...
     * @param richTreasury   ...when its treasury holds at least this much
     * @param savingsShare   share of the treasury a saving rich country keeps out of recruitment
     */
    public record Rules(double baseShare, double wealthShare, double expansionShare, double warCut, double maxShare,
                        long minBudget, int richWealth, long richTreasury, double savingsShare) {

        public static Rules defaults() {
            return new Rules(0.15, 0.25, 0.15, 0.6, 0.6, 100, 70, 5000, 0.5);
        }
    }

    private BuildBudget() {
    }

    /** Share (0..maxShare) of the spendable treasury offered for development. */
    public static double share(final Rules r, final Personality p, final boolean atWar) {
        double share = r.baseShare() + p.wealth() / 100.0 * r.wealthShare() + p.expansion() / 100.0 * r.expansionShare();
        if (atWar) {
            share *= 1.0 - clamp01(r.warCut());
        }
        return Math.max(0, Math.min(r.maxShare(), share));
    }

    /** Budget for {@code developSettlement}: share of what is above the reserve; 0 when too small to matter. */
    public static long budget(final Rules r, final Personality p, final long balance, final long reserve, final boolean atWar) {
        final long spendable = balance - Math.max(0, reserve);
        if (spendable <= 0) {
            return 0;
        }
        final long b = Math.round(spendable * share(r, p, atWar));
        return b < r.minBudget() ? 0 : b;
    }

    public static boolean isRich(final Rules r, final Personality p, final long balance) {
        return p.wealth() >= r.richWealth() && balance >= r.richTreasury();
    }

    /**
     * Money recruitment must leave untouched: a rich country whose last development failed for lack of funds keeps
     * {@code savingsShare} of the treasury "na rozbudowę" (less while at war).
     */
    public static long savingsReserve(final Rules r, final Personality p, final long balance, final boolean saving, final boolean atWar) {
        if (!saving || !isRich(r, p, balance)) {
            return 0;
        }
        double share = clamp01(r.savingsShare());
        if (atWar) {
            share *= 1.0 - clamp01(r.warCut());
        }
        return Math.round(Math.max(0, balance) * share);
    }

    /** After a development attempt: keep saving only when a rich country could not afford anything sensible. */
    public static boolean keepSaving(final Rules r, final Personality p, final long balance, final String result) {
        return "INSUFFICIENT_FUNDS".equals(result) && isRich(r, p, balance);
    }

    private static double clamp01(final double v) {
        return Math.max(0, Math.min(1, v));
    }
}
