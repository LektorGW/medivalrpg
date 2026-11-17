// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily wages (żołd) and desertion. Soldiers with the lowest morale are paid first (they are the closest to leaving);
 * a paid soldier gains morale, an unpaid one loses it and deserts when it falls below the threshold. Pure.
 */
public final class Payroll {

    /**
     * @param maxMorale   morale cap (100)
     * @param gainPaid    morale gained when paid
     * @param lossUnpaid  morale lost when not paid
     * @param desertBelow morale under which a soldier deserts
     */
    public record Rules(int maxMorale, int gainPaid, int lossUnpaid, int desertBelow) {
        public static Rules defaults() {
            return new Rules(100, 10, 34, 1);
        }
    }

    public record Soldier(UUID id, long wage, int morale) {
    }

    /**
     * @param paid      total amount to withdraw from the treasury
     * @param paidIds   soldiers that received their wage
     * @param morale    new morale of every soldier
     * @param deserters soldiers that desert now
     */
    public record Result(long paid, List<UUID> paidIds, Map<UUID, Integer> morale, List<UUID> deserters) {
        public int unpaid() {
            return this.morale.size() - this.paidIds.size();
        }
    }

    private Payroll() {
    }

    public static Result pay(final List<Soldier> soldiers, final long balance, final Rules rules) {
        long left = Math.max(0, balance);
        long paid = 0;
        final List<UUID> paidIds = new ArrayList<>();
        final Map<UUID, Integer> morale = new HashMap<>();
        final List<UUID> deserters = new ArrayList<>();
        final List<Soldier> order = new ArrayList<>(soldiers);
        order.sort(Comparator.comparingInt(Soldier::morale).thenComparingLong(Soldier::wage));
        for (final Soldier s : order) {
            final long wage = Math.max(0, s.wage());
            if (wage <= left) {
                left -= wage;
                paid += wage;
                paidIds.add(s.id());
                morale.put(s.id(), Math.min(rules.maxMorale(), s.morale() + rules.gainPaid()));
            } else {
                final int m = Math.max(0, s.morale() - rules.lossUnpaid());
                morale.put(s.id(), m);
                if (m < rules.desertBelow()) {
                    deserters.add(s.id());
                }
            }
        }
        return new Result(paid, List.copyOf(paidIds), Map.copyOf(morale), List.copyOf(deserters));
    }

    /** Sum of wages per day. */
    public static long dailyTotal(final List<Soldier> soldiers) {
        long sum = 0;
        for (final Soldier s : soldiers) {
            sum += Math.max(0, s.wage());
        }
        return sum;
    }
}
