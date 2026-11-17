// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.cost;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player limits: message length, cooldown between messages and messages per day (server time zone).
 * Main thread only (no locking). {@link #check} does not consume; {@link #consume} is called when a request is
 * actually sent.
 */
public final class RateLimiter {

    public enum Verdict { OK, TOO_LONG, EMPTY, COOLDOWN, DAILY_LIMIT }

    /** Outcome of {@link #check}: {@code waitSeconds} only for {@link Verdict#COOLDOWN}. */
    public record Check(Verdict verdict, long waitSeconds, int usedToday) {
        public boolean ok() {
            return this.verdict == Verdict.OK;
        }
    }

    private final Clock clock;
    private int perDay;
    private int cooldownSeconds;
    private int maxChars;
    private final Map<UUID, Long> last = new HashMap<>();
    private final Map<UUID, Integer> today = new HashMap<>();
    private LocalDate day;

    public RateLimiter(final Clock clock, final int perDay, final int cooldownSeconds, final int maxChars) {
        this.clock = clock;
        this.configure(perDay, cooldownSeconds, maxChars);
    }

    public void configure(final int perDay, final int cooldownSeconds, final int maxChars) {
        this.perDay = perDay;
        this.cooldownSeconds = cooldownSeconds;
        this.maxChars = maxChars;
    }

    private void roll() {
        final LocalDate d = LocalDate.now(this.clock);
        if (!d.equals(this.day)) {
            this.day = d;
            this.today.clear();
        }
    }

    public Check check(final UUID player, final String text) {
        this.roll();
        final int used = this.today.getOrDefault(player, 0);
        if (text == null || text.isBlank()) {
            return new Check(Verdict.EMPTY, 0, used);
        }
        if (text.length() > this.maxChars) {
            return new Check(Verdict.TOO_LONG, 0, used);
        }
        final Long l = this.last.get(player);
        final long now = this.clock.millis();
        if (l != null && now - l < this.cooldownSeconds * 1000L) {
            return new Check(Verdict.COOLDOWN, Math.max(1, (this.cooldownSeconds * 1000L - (now - l) + 999) / 1000), used);
        }
        if (used >= this.perDay) {
            return new Check(Verdict.DAILY_LIMIT, 0, used);
        }
        return new Check(Verdict.OK, 0, used);
    }

    public void consume(final UUID player) {
        this.roll();
        this.last.put(player, this.clock.millis());
        this.today.merge(player, 1, Integer::sum);
    }

    public int usedToday(final UUID player) {
        this.roll();
        return this.today.getOrDefault(player, 0);
    }

    public int maxChars() {
        return this.maxChars;
    }
}
