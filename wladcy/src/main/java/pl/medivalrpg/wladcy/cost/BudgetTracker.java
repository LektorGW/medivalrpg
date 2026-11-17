// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.cost;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Spending today and this month (server time zone), persisted as {@link State}. When the daily (or optional monthly)
 * budget is used up, rulers refuse audiences until the next day. Thread-safe (synchronized; called from the API
 * threads and the main thread).
 */
public final class BudgetTracker {

    /** Persistent counters ({@code usage.json}). Field names are the JSON keys. */
    public static final class State {
        public String day = "";
        public double dayUsd;
        public long dayRequests;
        public long dayInput;
        public long dayOutput;
        public long dayCacheRead;
        public long dayCacheWrite;
        public String month = "";
        public double monthUsd;
        public long monthRequests;
        public double totalUsd;
    }

    /** Snapshot for {@code /wladca koszt}. */
    public record Report(String day, double dayUsd, long dayRequests, long dayInput, long dayOutput, long dayCacheRead,
                         long dayCacheWrite, String month, double monthUsd, long monthRequests, double totalUsd,
                         double dailyBudget, double monthlyBudget) {
    }

    private final Clock clock;
    private final CostCalculator calculator;
    private final State state;
    private volatile double dailyBudget;
    private volatile double monthlyBudget;
    private volatile boolean dirty;

    public BudgetTracker(final Clock clock, final CostCalculator calculator, final State state, final double dailyBudget, final double monthlyBudget) {
        this.clock = clock;
        this.calculator = calculator;
        this.state = state == null ? new State() : state;
        this.dailyBudget = dailyBudget;
        this.monthlyBudget = monthlyBudget;
    }

    public void setBudgets(final double daily, final double monthly) {
        this.dailyBudget = daily;
        this.monthlyBudget = monthly;
    }

    private void roll() {
        final LocalDate today = LocalDate.now(this.clock);
        final String d = today.toString();
        if (!d.equals(this.state.day)) {
            this.state.day = d;
            this.state.dayUsd = 0;
            this.state.dayRequests = 0;
            this.state.dayInput = 0;
            this.state.dayOutput = 0;
            this.state.dayCacheRead = 0;
            this.state.dayCacheWrite = 0;
            this.dirty = true;
        }
        final String m = YearMonth.from(today).toString();
        if (!m.equals(this.state.month)) {
            this.state.month = m;
            this.state.monthUsd = 0;
            this.state.monthRequests = 0;
            this.dirty = true;
        }
    }

    /** Records one API request (all its attempts) and returns its cost in USD. */
    public synchronized double record(final List<UsageSample> attempts) {
        this.roll();
        final double usd = this.calculator.cost(attempts);
        this.state.dayUsd += usd;
        this.state.monthUsd += usd;
        this.state.totalUsd += usd;
        this.state.dayRequests++;
        this.state.monthRequests++;
        for (final UsageSample u : attempts) {
            this.state.dayInput += u.input();
            this.state.dayOutput += u.output();
            this.state.dayCacheRead += u.cacheRead();
            this.state.dayCacheWrite += u.cacheWrite();
        }
        this.dirty = true;
        return usd;
    }

    /** True when no more requests may be sent today (daily budget reached, or the monthly one when set). */
    public synchronized boolean exceeded() {
        this.roll();
        if (this.dailyBudget > 0 && this.state.dayUsd >= this.dailyBudget) {
            return true;
        }
        return this.monthlyBudget > 0 && this.state.monthUsd >= this.monthlyBudget;
    }

    public synchronized Report report() {
        this.roll();
        final State s = this.state;
        return new Report(s.day, s.dayUsd, s.dayRequests, s.dayInput, s.dayOutput, s.dayCacheRead, s.dayCacheWrite,
            s.month, s.monthUsd, s.monthRequests, s.totalUsd, this.dailyBudget, this.monthlyBudget);
    }

    /** Copy of the state for saving; clears the dirty flag. */
    public synchronized State snapshot() {
        this.dirty = false;
        final State c = new State();
        c.day = this.state.day;
        c.dayUsd = this.state.dayUsd;
        c.dayRequests = this.state.dayRequests;
        c.dayInput = this.state.dayInput;
        c.dayOutput = this.state.dayOutput;
        c.dayCacheRead = this.state.dayCacheRead;
        c.dayCacheWrite = this.state.dayCacheWrite;
        c.month = this.state.month;
        c.monthUsd = this.state.monthUsd;
        c.monthRequests = this.state.monthRequests;
        c.totalUsd = this.state.totalUsd;
        return c;
    }

    public boolean dirty() {
        return this.dirty;
    }
}
