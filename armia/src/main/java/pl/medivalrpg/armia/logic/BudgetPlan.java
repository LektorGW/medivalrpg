// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import pl.medivalrpg.armia.api.NpcBudget;
import pl.medivalrpg.armia.api.NpcPosture;

/**
 * The budget split of an NPC country (army / buildings / reserve, percent) and what it means for one AI tick. Pure.
 *
 * <ul>
 *   <li>Reserve: {@code reserve%} of the treasury, never less than the AI's minimum reserve (wages, min-reserve).</li>
 *   <li>The rest ("spendable") is split army : buildings in the ratio of their shares.</li>
 *   <li>War moves {@code warArmyShare} of the buildings share to the army, the crisis mode {@code crisisArmyShare} and
 *       half of the reserve share; rebuilding after peace moves {@code rebuildBuildingsShare} of the army share to
 *       buildings.</li>
 * </ul>
 */
public final class BudgetPlan {

    /** Effective fractions of one tick (sum 1). */
    public record Shares(double army, double buildings, double reserve) {
    }

    private BudgetPlan() {
    }

    /** Split derived from the personality when an admin never set one (close to the AI v1 proportions). */
    public static NpcBudget derived(final Personality p, final BuildBudget.Rules rules) {
        final int buildings = (int) Math.round(BuildBudget.share(rules, p, false) * 100);
        final int reserve = Math.max(0, Math.min(100 - buildings, 10 + p.wealth() / 5));
        return new NpcBudget(100 - buildings - reserve, buildings, reserve, false);
    }

    /** Validates an admin's split; null when fine, else a Polish error. */
    public static String validate(final int army, final int buildings, final int reserve) {
        if (army < 0 || buildings < 0 || reserve < 0 || army > 100 || buildings > 100 || reserve > 100) {
            return "Każdy udział musi być liczbą 0–100.";
        }
        if (army + buildings + reserve != 100) {
            return "Udziały muszą sumować się do 100 (jest " + (army + buildings + reserve) + ").";
        }
        return null;
    }

    /** Validates a split with research (all four sum to 100); null when fine. */
    public static String validate(final int army, final int buildings, final int research, final int reserve) {
        if (research < 0 || research > 100) {
            return "Udział badań musi być liczbą 0–100.";
        }
        if (army < 0 || buildings < 0 || reserve < 0 || army > 100 || buildings > 100 || reserve > 100) {
            return "Każdy udział musi być liczbą 0–100.";
        }
        final int sum = army + buildings + research + reserve;
        return sum == 100 ? null : "Udziały muszą sumować się do 100 (jest " + sum + ").";
    }

    public static Shares effective(final AiRules r, final NpcBudget b, final boolean atWar, final boolean crisis, final boolean rebuilding) {
        double army = Math.max(0, b.army()) / 100.0;
        double buildings = Math.max(0, b.buildings()) / 100.0;
        double reserve = Math.max(0, b.reserve()) / 100.0;
        if (crisis) {
            final double move = buildings * clamp01(r.crisisArmyShare());
            buildings -= move;
            army += move + reserve * 0.5;
            reserve *= 0.5;
        } else if (atWar) {
            final double move = buildings * clamp01(r.warArmyShare());
            buildings -= move;
            army += move;
        } else if (rebuilding) {
            final double move = army * clamp01(r.rebuildBuildingsShare());
            army -= move;
            buildings += move;
        }
        final double sum = army + buildings + reserve;
        if (sum <= 0) {
            return new Shares(0, 0, 1);
        }
        return new Shares(army / sum, buildings / sum, reserve / sum);
    }

    /** Money kept untouched this tick. */
    public static long reserveAmount(final Shares s, final long balance, final long minReserve) {
        return Math.max(Math.max(0, minReserve), Math.round(Math.max(0, balance) * s.reserve()));
    }

    private static long spendable(final Shares s, final long balance, final long minReserve) {
        return Math.max(0, balance - reserveAmount(s, balance, minReserve));
    }

    /** Budget for {@code developSettlement} this tick; 0 below {@code minBudget}. */
    public static long buildingBudget(final Shares s, final long balance, final long minReserve, final long minBudget) {
        final double parts = s.army() + s.buildings();
        if (parts <= 0) {
            return 0;
        }
        final long b = Math.round(spendable(s, balance, minReserve) * s.buildings() / parts);
        return b < minBudget ? 0 : b;
    }

    /** Money recruitment may spend this tick. */
    public static long armyMoney(final Shares s, final long balance, final long minReserve) {
        final double parts = s.army() + s.buildings();
        if (parts <= 0) {
            return 0;
        }
        return Math.round(spendable(s, balance, minReserve) * s.army() / parts);
    }

    /** Share of guards placed on the border for the posture (0..0.95); at night (no crisis) 0.2 less, so more guards stay in the settlement. */
    public static double guardFraction(final AiRules r, final double base, final NpcPosture posture, final boolean crisis, final boolean night) {
        double f = base;
        if (posture == NpcPosture.DEFENSIVE) {
            f += r.defensiveGuardBonus();
        } else if (posture == NpcPosture.OFFENSIVE) {
            f -= r.offensiveGuardCut();
        }
        if (crisis) {
            f += 0.1;
        } else if (night && posture != NpcPosture.OFFENSIVE) {
            f -= 0.2;
        }
        return Math.max(0, Math.min(0.95, f));
    }

    /** Siege cooldown in minutes for the posture. */
    public static long siegeCooldown(final AiRules r, final long baseMinutes, final NpcPosture posture) {
        final double m = posture == NpcPosture.OFFENSIVE ? r.offensiveSiegeCooldown() : posture == NpcPosture.DEFENSIVE ? r.defensiveSiegeCooldown() : 1.0;
        return Math.max(0, Math.round(baseMinutes * m));
    }

    /** Desired army in the crisis mode (still capped by the country limit). */
    public static int desiredInCrisis(final AiRules r, final int desired, final int cap) {
        return Math.min(cap, (int) Math.ceil(desired * Math.max(1.0, r.crisisSoldiersMultiplier())));
    }

    /** Recruits per tick: more in the crisis mode. */
    public static int recruitPerTick(final AiRules r, final int base, final boolean crisis) {
        return crisis ? (int) Math.ceil(base * Math.max(1.0, r.crisisRecruitMultiplier())) : base;
    }

    /** Soldiers to recruit now: the missing ones, at most {@code perTick}, paid from the army money only. */
    public static int recruitBatch(final int desired, final int current, final int perTick, final long armyMoney, final long unitCost) {
        final int missing = desired - current;
        if (missing <= 0 || perTick <= 0) {
            return 0;
        }
        final long affordable = unitCost <= 0 ? Integer.MAX_VALUE : Math.max(0, armyMoney) / unitCost;
        return (int) Math.max(0, Math.min(Math.min(missing, perTick), affordable));
    }

    private static double clamp01(final double v) {
        return Math.max(0, Math.min(1, v));
    }
}
