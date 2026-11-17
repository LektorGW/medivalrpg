// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory history of each country's material flow, kept by the panel between answers (lost on restart, never written):
 * <ul>
 *   <li><b>production estimate</b> — Ludnosc does not count daily output yet, so every sample compares the goods of the stores
 *       seen in BOTH the previous and the current sample (a store that unloads or reloads is ignored) and keeps the positive net
 *       growth per material. Haulers moving goods between two known stores cancel out; what NPCs consume in between is not
 *       seen, so this is a lower bound. Scaled to 24 h only over observed intervals (gaps longer than {@code maxGapMs} are skipped);</li>
 *   <li><b>points</b> for the charts — food, population, goods and slots over the last {@code keepMs};</li>
 *   <li><b>waiting sites</b> — when the panel first saw a site waiting for a material (Ludnosc keeps its own clock private).</li>
 * </ul>
 * Pure (time is passed in); synchronized because the RPC thread and the sampler both run on the main thread, but tests may not.
 */
public final class EconomyTracker {

    public static final long MINUTE = 60_000L;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;

    /** One chart point. Nullable measures are unknown. */
    public record Point(long t, Long food, Long population, long goods, int usedSlots, int slots, int haulers, int waitingSites) {
    }

    /**
     * Production estimate over the last {@code windowMs}: {@code gained} = positive net growth seen, {@code perDay} = scaled to
     * 24 h (empty while {@code coveredMs} is shorter than {@code minCoverMs}), {@code since} = oldest sample of the country.
     */
    public record Estimate(long windowMs, long coveredMs, long since, Map<String, Long> gained, Map<String, Long> perDay, int intervals) {
    }

    private record Interval(long t0, long t1, Map<String, Long> gained) {
    }

    private record Wait(String key, long since) {
    }

    private static final class Track {
        long since;
        long lastSample;
        Map<String, Map<String, Long>> lastStores;
        final Deque<Interval> intervals = new ArrayDeque<>();
        final Deque<Point> points = new ArrayDeque<>();
        final Map<String, Wait> waits = new HashMap<>();
    }

    private final Map<UUID, Track> tracks = new HashMap<>();
    private final long keepMs;
    private final long minIntervalMs;
    private final long maxGapMs;
    private final long minCoverMs;

    public EconomyTracker() {
        this(48 * HOUR, MINUTE, 30 * MINUTE, 30 * MINUTE);
    }

    public EconomyTracker(final long keepMs, final long minIntervalMs, final long maxGapMs, final long minCoverMs) {
        this.keepMs = keepMs;
        this.minIntervalMs = minIntervalMs;
        this.maxGapMs = maxGapMs;
        this.minCoverMs = minCoverMs;
    }

    private Track track(final UUID country) {
        return this.tracks.computeIfAbsent(country, k -> new Track());
    }

    /** Time of the last accepted sample of a country (0 = none). */
    public synchronized long lastSample(final UUID country) {
        final Track t = this.tracks.get(country);
        return t == null ? 0 : t.lastSample;
    }

    /**
     * Records one sample: {@code stores} = store key → material → count (only the stores that are loaded now).
     * Ignored when the previous sample is younger than {@code minIntervalMs}. Returns whether it was accepted.
     */
    public synchronized boolean sample(final UUID country, final long now, final Map<String, Map<String, Long>> stores, final Point point) {
        final Track t = this.track(country);
        if (t.lastSample != 0 && now - t.lastSample < this.minIntervalMs) {
            return false;
        }
        if (t.since == 0) {
            t.since = now;
        }
        if (t.lastStores != null && now > t.lastSample && now - t.lastSample <= this.maxGapMs) {
            final Map<String, Long> net = new HashMap<>();
            for (final Map.Entry<String, Map<String, Long>> e : stores.entrySet()) {
                final Map<String, Long> before = t.lastStores.get(e.getKey());
                if (before == null) {
                    continue;
                }
                final Set<String> keys = new java.util.HashSet<>(before.keySet());
                keys.addAll(e.getValue().keySet());
                for (final String k : keys) {
                    net.merge(k, e.getValue().getOrDefault(k, 0L) - before.getOrDefault(k, 0L), Long::sum);
                }
            }
            final Map<String, Long> gained = new LinkedHashMap<>();
            net.forEach((k, v) -> {
                if (v > 0) {
                    gained.put(k, v);
                }
            });
            t.intervals.addLast(new Interval(t.lastSample, now, gained));
        }
        t.lastSample = now;
        t.lastStores = copy(stores);
        if (point != null) {
            t.points.addLast(point);
        }
        while (!t.intervals.isEmpty() && now - t.intervals.peekFirst().t1() > this.keepMs) {
            t.intervals.removeFirst();
        }
        while (!t.points.isEmpty() && (now - t.points.peekFirst().t() > this.keepMs || t.points.size() > 2000)) {
            t.points.removeFirst();
        }
        return true;
    }

    private static Map<String, Map<String, Long>> copy(final Map<String, Map<String, Long>> stores) {
        final Map<String, Map<String, Long>> out = new HashMap<>();
        stores.forEach((k, v) -> out.put(k, Map.copyOf(v)));
        return out;
    }

    /** Production estimate over the last {@code windowMs} (see the class comment). */
    public synchronized Estimate estimate(final UUID country, final long now, final long windowMs) {
        final Track t = this.tracks.get(country);
        if (t == null) {
            return new Estimate(windowMs, 0, 0, Map.of(), Map.of(), 0);
        }
        long covered = 0;
        int n = 0;
        final Map<String, Long> gained = new HashMap<>();
        for (final Interval i : t.intervals) {
            if (now - i.t1() > windowMs) {
                continue;
            }
            covered += i.t1() - Math.max(i.t0(), now - windowMs);
            n++;
            i.gained().forEach((k, v) -> gained.merge(k, v, Long::sum));
        }
        final Map<String, Long> perDay = new HashMap<>();
        if (covered >= this.minCoverMs && covered > 0) {
            final long c = covered;
            gained.forEach((k, v) -> perDay.put(k, Math.round(v * (double) DAY / c)));
        }
        return new Estimate(windowMs, covered, t.since, sorted(gained), sorted(perDay), n);
    }

    private static Map<String, Long> sorted(final Map<String, Long> m) {
        final List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        l.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        final Map<String, Long> out = new LinkedHashMap<>();
        l.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Chart points since {@code from}, oldest first. */
    public synchronized List<Point> points(final UUID country, final long from) {
        final Track t = this.tracks.get(country);
        if (t == null) {
            return List.of();
        }
        return t.points.stream().filter(p -> p.t() >= from).toList();
    }

    /**
     * When the site started waiting for {@code key} (first seen by the panel). A different key restarts the clock.
     * {@code reported} &gt; 0 (a time Ludnosc reports itself) wins.
     */
    public synchronized long waitingSince(final UUID country, final String site, final String key, final long now, final long reported) {
        final Track t = this.track(country);
        final Wait w = t.waits.get(site);
        if (reported > 0) {
            t.waits.put(site, new Wait(key, reported));
            return reported;
        }
        if (w != null && w.key().equals(key)) {
            return w.since();
        }
        t.waits.put(site, new Wait(key, now));
        return now;
    }

    /** Forgets the waiting clock of every site of the country that is not waiting now. */
    public synchronized void keepWaiting(final UUID country, final Set<String> waitingSites) {
        final Track t = this.tracks.get(country);
        if (t != null) {
            t.waits.keySet().retainAll(waitingSites);
        }
    }

    /** Drops countries that no longer exist. */
    public synchronized void retain(final Set<UUID> countries) {
        this.tracks.keySet().retainAll(countries);
    }
}
