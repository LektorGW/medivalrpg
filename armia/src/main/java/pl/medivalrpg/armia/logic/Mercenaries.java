// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mercenaries: soldiers for hire without conscription (no citizen, no barracks) — more expensive, on a contract, gone
 * at once when unpaid. They count towards the army limit and have their own sub-limit (share of the army cap). Pure.
 */
public final class Mercenaries {

    /**
     * @param costMultiplier     hire cost = base unit cost × this
     * @param wageMultiplier     daily wage = base unit wage × this
     * @param contractHours      contract length (real hours)
     * @param extendCostShare    extending a contract costs this share of the hire cost
     * @param maxShare           at most this share of the country's army cap may be mercenaries
     * @param defect             unpaid mercenaries of a nearly bankrupt country may go to a paying enemy
     * @param defectBelow        "nearly bankrupt" = treasury below this (copper)
     * @param aiMaxPopulation    the NPC AI hires in the crisis mode only with at most this many citizens...
     * @param aiMinTreasury      ...and at least this much in the treasury
     * @param aiPerTick          mercenaries the AI hires per tick at most
     */
    public record Rules(double costMultiplier, double wageMultiplier, long contractHours, double extendCostShare, double maxShare,
                        boolean defect, long defectBelow, int aiMaxPopulation, long aiMinTreasury, int aiPerTick) {

        public static Rules defaults() {
            return new Rules(2.5, 2.0, 72, 0.5, 0.3, false, 100, 10, 5000, 2);
        }
    }

    private Mercenaries() {
    }

    public static long cost(final Rules r, final long baseCost) {
        return Math.max(0, Math.round(Math.max(0, baseCost) * r.costMultiplier()));
    }

    public static long wage(final Rules r, final long baseWage) {
        return Math.max(0, Math.round(Math.max(0, baseWage) * r.wageMultiplier()));
    }

    public static long extendCost(final Rules r, final long hireCost) {
        return Math.max(0, Math.round(Math.max(0, hireCost) * r.extendCostShare()));
    }

    /** Mercenary sub-limit for a country with this army cap. */
    public static int maxMercenaries(final Rules r, final int countryCap) {
        return Math.max(0, (int) Math.floor(Math.max(0, countryCap) * Math.max(0, Math.min(1, r.maxShare()))));
    }

    /** How many of {@code requested} may be hired now: army limit, global cap and the mercenary sub-limit. */
    public static int allowedHire(final Rules r, final int requested, final int armyNow, final int mercsNow, final int countryCap,
                                  final int globalNow, final int globalCap) {
        final int byArmy = ArmyLimits.allowed(requested, armyNow, countryCap, globalNow, globalCap);
        return Math.max(0, Math.min(byArmy, maxMercenaries(r, countryCap) - mercsNow));
    }

    public static long contractEnd(final Rules r, final long now) {
        return now + Math.max(1, r.contractHours()) * 3_600_000L;
    }

    /** New end after extending: from the current end (or now when it already passed). */
    public static long extended(final Rules r, final long until, final long now) {
        return Math.max(until, now) + Math.max(1, r.contractHours()) * 3_600_000L;
    }

    public static boolean expired(final long until, final long now) {
        return until > 0 && now >= until;
    }

    /** Mercenaries that were not paid in this payroll (they leave at once, no morale step). */
    public static List<UUID> unpaid(final Payroll.Result result, final Set<UUID> mercenaries) {
        final Set<UUID> paid = new HashSet<>(result.paidIds());
        final List<UUID> out = new ArrayList<>();
        for (final UUID id : result.morale().keySet()) {
            if (mercenaries.contains(id) && !paid.contains(id)) {
                out.add(id);
            }
        }
        out.sort(UUID::compareTo);
        return out;
    }

    /** Unpaid mercenaries may go over to a paying enemy when the option is on and the treasury is nearly empty. */
    public static boolean mayDefect(final Rules r, final long treasury) {
        return r.defect() && treasury < r.defectBelow();
    }

    /**
     * Mercenaries the NPC AI hires this tick: only in the crisis mode, with few citizens and a rich treasury; never
     * more than missing soldiers, the sub-limit, {@code aiPerTick} or what the spendable money pays.
     */
    public static int aiHire(final Rules r, final boolean crisis, final int population, final long balance, final long reserve,
                             final long hireCost, final int missing, final int mercsNow, final int maxMercs) {
        if (!crisis || population > r.aiMaxPopulation() || balance < r.aiMinTreasury() || missing <= 0) {
            return 0;
        }
        final long spendable = balance - Math.max(0, reserve);
        final long affordable = hireCost <= 0 ? Integer.MAX_VALUE : Math.max(0, spendable) / hireCost;
        return (int) Math.max(0, Math.min(Math.min(missing, r.aiPerTick()), Math.min(affordable, maxMercs - mercsNow)));
    }
}
