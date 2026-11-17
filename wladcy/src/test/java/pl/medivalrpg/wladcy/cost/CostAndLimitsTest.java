// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.wladcy.config.WladcyConfig;
import pl.medivalrpg.wladcy.testutil.TestSupport;

class CostAndLimitsTest {

    private final WladcyConfig cfg = TestSupport.config();
    private final CostCalculator calc = new CostCalculator(this.cfg::price);

    @Test
    void opusCostFromUsage() {
        // 1000 uncached in × $4 + 200 out × $20 + 3000 cache read × $0.20 + 500 cache write × $5, per MTok.
        final double usd = this.calc.cost(new UsageSample("claude-opus-5-5", 1000, 200, 3000, 500));
        assertEquals((1000 * 4.0 + 200 * 20.0 + 3000 * 0.20 + 500 * 5.0) / 1e6, usd, 1e-12);
        assertEquals(0.0111, usd, 1e-9);
    }

    @Test
    void haikuIsMuchCheaper() {
        final UsageSample opus = new UsageSample("claude-opus-5-5", 1000, 200, 3000, 500);
        final UsageSample haiku = new UsageSample("claude-haiku-5-5", 1000, 200, 3000, 500);
        assertTrue(this.calc.cost(opus) / this.calc.cost(haiku) > 30);
    }

    @Test
    void fallbackAttemptsPricedPerModel() {
        final List<UsageSample> attempts = List.of(
            new UsageSample("claude-opus-5-5", 800, 0, 0, 0),     // declined before output
            new UsageSample("claude-opus-5", 800, 100, 0, 0));    // fallback model served
        assertEquals((800 * 4.0) / 1e6 + (800 * 5.0 + 100 * 25.0) / 1e6, this.calc.cost(attempts), 1e-12);
    }

    @Test
    void budgetPerDayAndMonth() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        final BudgetTracker b = new BudgetTracker(clock, this.calc, null, 0.02, 0.03);
        assertFalse(b.exceeded());
        b.record(List.of(new UsageSample("claude-opus-5-5", 0, 1000, 0, 0))); // $0.02
        assertTrue(b.exceeded(), "daily budget reached");
        assertEquals(1, b.report().dayRequests());

        clock.set(Instant.parse("2026-10-27T00:00:01Z"));
        assertFalse(b.exceeded(), "new day");
        assertEquals(0.0, b.report().dayUsd(), 1e-12);
        assertEquals(0.02, b.report().monthUsd(), 1e-12);
        b.record(List.of(new UsageSample("claude-opus-5-5", 0, 500, 0, 0)));  // month $0.03
        assertTrue(b.exceeded(), "monthly budget reached");

        clock.set(Instant.parse("2026-11-01T08:00:00Z"));
        assertFalse(b.exceeded());
        assertEquals("2026-11", b.report().month());
        assertEquals(0.03, b.report().totalUsd(), 1e-12);
    }

    @Test
    void budgetStateSurvivesSnapshot() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        final BudgetTracker a = new BudgetTracker(clock, this.calc, null, 3, 0);
        a.record(List.of(new UsageSample("claude-opus-5-5", 100, 100, 100, 100)));
        assertTrue(a.dirty());
        final BudgetTracker.State s = a.snapshot();
        assertFalse(a.dirty());
        final BudgetTracker b = new BudgetTracker(clock, this.calc, s, 3, 0);
        assertEquals(a.report(), b.report());
    }

    @Test
    void rateLimits() {
        final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-10-26T10:00:00Z"));
        final RateLimiter r = new RateLimiter(clock, 2, 4, 10);
        final UUID p = UUID.randomUUID();
        assertEquals(RateLimiter.Verdict.EMPTY, r.check(p, " ").verdict());
        assertEquals(RateLimiter.Verdict.TOO_LONG, r.check(p, "12345678901").verdict());
        assertTrue(r.check(p, "witaj").ok());
        r.consume(p);
        final RateLimiter.Check cd = r.check(p, "witaj");
        assertEquals(RateLimiter.Verdict.COOLDOWN, cd.verdict());
        assertEquals(4, cd.waitSeconds());
        clock.advanceMillis(4000);
        assertTrue(r.check(p, "witaj").ok());
        r.consume(p);
        clock.advanceMillis(5000);
        assertEquals(RateLimiter.Verdict.DAILY_LIMIT, r.check(p, "witaj").verdict());
        assertTrue(r.check(UUID.randomUUID(), "witaj").ok(), "limits are per player");
        clock.set(Instant.parse("2026-10-27T00:00:00Z"));
        assertTrue(r.check(p, "witaj").ok(), "new day resets the count");
    }
}
