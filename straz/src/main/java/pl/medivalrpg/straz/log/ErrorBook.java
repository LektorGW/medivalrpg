// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deduplicated ERROR/SEVERE signatures with hourly counters (pure; serialized by Gson as part of the state).
 * A signature is {@code level|plugin|normalized message}; "new" means not seen during the configured window.
 */
public final class ErrorBook {

    private static final long HOUR = 3_600_000L;

    /** One signature. Hour buckets: start of the hour (epoch ms) → count. */
    public static final class Sig {
        public String plugin;
        public String level;
        public String sample;
        public String detail;
        public long first;
        public long last;
        public long total;
        public TreeMap<Long, Integer> hours = new TreeMap<>();
    }

    /** One line of the report: a signature and its count in the window. */
    public record Line(String plugin, String sample, String detail, long count, long firstInWindow, long last, boolean newInWindow) {
    }

    /** Per-plugin group of the report. */
    public record Group(String plugin, long count, List<Line> lines) {
    }

    public Map<String, Sig> sigs = new LinkedHashMap<>();

    public static String key(final LogParser.Event e) {
        return e.level() + "|" + e.plugin() + "|" + LogParser.normalize(e.message());
    }

    /** Records one event; returns true when its signature is new (unseen for {@code newWindowMs}). */
    public boolean record(final LogParser.Event e, final long newWindowMs) {
        final String key = key(e);
        Sig s = this.sigs.get(key);
        final boolean isNew = s == null || e.time() - s.last > newWindowMs;
        if (s == null) {
            s = new Sig();
            s.plugin = e.plugin();
            s.level = e.level();
            s.first = e.time();
            this.sigs.put(key, s);
        }
        s.sample = e.message();
        if (e.detail() != null) {
            s.detail = e.detail();
        }
        s.last = Math.max(s.last, e.time());
        s.total++;
        s.hours.merge(e.time() - Math.floorMod(e.time(), HOUR), 1, Integer::sum);
        return isNew;
    }

    /** Errors of the last {@code windowMs} grouped by plugin (largest first), signatures sorted by count. */
    public List<Group> summary(final long now, final long windowMs, final long newWindowMs) {
        final long from = now - windowMs;
        final Map<String, List<Line>> byPlugin = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (final Sig s : this.sigs.values()) {
            long count = 0;
            long firstIn = Long.MAX_VALUE;
            for (final Map.Entry<Long, Integer> h : s.hours.tailMap(from - Math.floorMod(from, HOUR)).entrySet()) {
                // the bucket of the window's first hour is counted whole (hour precision is enough for a daily report)
                count += h.getValue();
                firstIn = Math.min(firstIn, Math.max(h.getKey(), from));
            }
            if (count == 0 || s.last < from) {
                continue;
            }
            // "new" = first seen inside the window and never during the preceding new-error window
            final boolean fresh = s.first >= from;
            byPlugin.computeIfAbsent(s.plugin, k -> new ArrayList<>())
                .add(new Line(s.plugin, s.sample, s.detail, count, firstIn, s.last, fresh));
        }
        final List<Group> out = new ArrayList<>();
        for (final Map.Entry<String, List<Line>> e : byPlugin.entrySet()) {
            final List<Line> lines = e.getValue();
            lines.sort(Comparator.comparingLong(Line::count).reversed().thenComparing(Line::sample));
            out.add(new Group(e.getKey(), lines.stream().mapToLong(Line::count).sum(), List.copyOf(lines)));
        }
        out.sort(Comparator.comparingLong(Group::count).reversed().thenComparing(Group::plugin));
        return out;
    }

    /** Drops hour buckets older than {@code keepHoursMs} and signatures unseen for {@code keepSigMs}. */
    public void prune(final long now, final long keepHoursMs, final long keepSigMs) {
        this.sigs.values().removeIf(s -> now - s.last > keepSigMs);
        for (final Sig s : this.sigs.values()) {
            s.hours.headMap(now - keepHoursMs).clear();
        }
        // hard cap on memory: keep the 500 most recently seen signatures
        if (this.sigs.size() > 500) {
            final List<Map.Entry<String, Sig>> all = new ArrayList<>(this.sigs.entrySet());
            all.sort(Comparator.comparingLong(en -> -en.getValue().last));
            final Map<String, Sig> kept = new LinkedHashMap<>();
            for (int i = 0; i < 500; i++) {
                kept.put(all.get(i).getKey(), all.get(i).getValue());
            }
            this.sigs = kept;
        }
    }
}
