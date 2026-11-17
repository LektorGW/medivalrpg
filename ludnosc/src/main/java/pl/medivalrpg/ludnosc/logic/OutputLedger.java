// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Real output of a workplace in the last 24 hours (pure, unit tested): hourly buckets {@code epoch hour → material →
 * items}, kept in the country data (survives restarts); buckets older than 24 h are dropped. Only committed
 * production is recorded (what really entered the store), never haulers' moves.
 */
public final class OutputLedger {

    /** Hours kept (a rolling day). */
    public static final int HOURS = 24;

    private OutputLedger() {
    }

    public static long hour(final long epochMillis) {
        return Math.floorDiv(epochMillis, 3_600_000L);
    }

    /** Adds items of a material made now; prunes buckets older than a day. */
    public static void record(final Map<String, Map<String, Integer>> buckets, final long now, final String material, final int amount) {
        if (amount <= 0 || material == null) {
            return;
        }
        buckets.computeIfAbsent(Long.toString(hour(now)), k -> new LinkedHashMap<>()).merge(material, amount, Integer::sum);
        prune(buckets, now);
    }

    /** Drops buckets older than {@link #HOURS} hours (and unreadable keys). */
    public static void prune(final Map<String, Map<String, Integer>> buckets, final long now) {
        final long oldest = hour(now) - HOURS + 1;
        for (final Iterator<String> it = buckets.keySet().iterator(); it.hasNext();) {
            final String k = it.next();
            try {
                if (Long.parseLong(k) < oldest) {
                    it.remove();
                }
            } catch (final NumberFormatException e) {
                it.remove();
            }
        }
    }

    /** Items per material made in the last 24 hours (sorted by material). */
    public static Map<String, Integer> lastDay(final Map<String, Map<String, Integer>> buckets, final long now) {
        final Map<String, Integer> out = new TreeMap<>();
        if (buckets == null) {
            return out;
        }
        final long oldest = hour(now) - HOURS + 1;
        for (final Map.Entry<String, Map<String, Integer>> e : buckets.entrySet()) {
            final long h;
            try {
                h = Long.parseLong(e.getKey());
            } catch (final NumberFormatException ex) {
                continue;
            }
            if (h >= oldest && h <= hour(now) && e.getValue() != null) {
                e.getValue().forEach((m, n) -> out.merge(m, n, Integer::sum));
            }
        }
        return out;
    }
}
