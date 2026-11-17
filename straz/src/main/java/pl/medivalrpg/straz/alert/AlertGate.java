// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.alert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides whether an alert goes out (pure; state serialized). The same key is sent once per {@code cooldown}
 * (e.g. a pool difference of the same amount), and at most {@code maxPerHour} alerts of any key per rolling hour;
 * suppressed ones are counted and reported with the next alert that passes.
 */
public final class AlertGate {

    /** Decision. {@code suppressedBefore} = alerts dropped by the hourly limit since the last sent one. */
    public record Decision(boolean send, int suppressedBefore, String why) {
    }

    public Map<String, Long> lastSent = new LinkedHashMap<>();
    public ArrayList<Long> sentTimes = new ArrayList<>();
    public int suppressed;

    public Decision offer(final String key, final long now, final long cooldownMs, final int maxPerHour) {
        final Long last = this.lastSent.get(key);
        if (last != null && now - last < cooldownMs) {
            return new Decision(false, 0, "duplikat");
        }
        this.sentTimes.removeIf(t -> now - t >= 3_600_000L);
        if (this.sentTimes.size() >= Math.max(1, maxPerHour)) {
            this.suppressed++;
            this.lastSent.put(key, now); // do not retry the same alert every minute
            return new Decision(false, 0, "limit na godzinę");
        }
        final int dropped = this.suppressed;
        this.suppressed = 0;
        this.sentTimes.add(now);
        this.lastSent.put(key, now);
        return new Decision(true, dropped, "");
    }

    /** Forgets a key (the condition cleared), so its next occurrence alerts again at once. */
    public void clear(final String key) {
        this.lastSent.remove(key);
    }

    public void prune(final long now, final long keepMs) {
        this.lastSent.values().removeIf(t -> now - t > keepMs);
    }
}
