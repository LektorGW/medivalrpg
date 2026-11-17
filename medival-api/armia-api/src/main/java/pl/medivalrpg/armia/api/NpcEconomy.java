// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

import java.util.List;
import java.util.Map;

/**
 * Money flows of an NPC country in the last 24 hours (see {@link ArmiaApi#economyBreakdown}), all in copper.
 *
 * <p>Income keys: {@code npcTax} (estimate: Ludnosc's daily tax × tax multiplier, prorated over the AI ticks of the
 * window), {@code aiIncome}, {@code tribute}, {@code other}. Expense keys: {@code wages}, {@code land} (claims and
 * daily upkeep), {@code buildings}, {@code recruit}, {@code tribute}, {@code war} (declarations, sieges),
 * {@code other}. Amounts are never negative; keys with 0 may be missing.</p>
 *
 * @param from     start of the window (epoch millis)
 * @param to       end of the window (now)
 * @param balance  treasury now
 * @param income   income per key
 * @param expenses expenses per key
 * @param log      the recorded flows of the window, newest first
 */
public record NpcEconomy(long from, long to, long balance, Map<String, Long> income, Map<String, Long> expenses, List<Flow> log) {

    /**
     * One recorded flow.
     *
     * @param time   epoch millis
     * @param key    income/expense key (see above)
     * @param amount signed: positive = income, negative = expense
     * @param actor  other country / player (may be empty)
     * @param note   Polish description (may be empty)
     */
    public record Flow(long time, String key, long amount, String actor, String note) {
    }

    public long incomeTotal() {
        return this.income.values().stream().mapToLong(Long::longValue).sum();
    }

    public long expensesTotal() {
        return this.expenses.values().stream().mapToLong(Long::longValue).sum();
    }
}
