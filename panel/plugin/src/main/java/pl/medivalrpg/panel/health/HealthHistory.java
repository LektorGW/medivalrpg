// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Two ring buffers of {@link HealthPoint}s: <b>fine</b> (every probe, kept {@code fineKeepMs}, default 1 h at 10 s)
 * and <b>coarse</b> (one aggregate per minute, kept {@code coarseKeepMs}, default 24 h). Probes of the current
 * minute are collected in an open bucket that becomes a coarse point when the next minute starts; reads include
 * the open bucket as the newest coarse point. Thread-safe (synchronized), pure (no Bukkit).
 */
public final class HealthHistory {

    public static final long MINUTE = 60_000L;

    private final Deque<HealthPoint> fine = new ArrayDeque<>();
    private final Deque<HealthPoint> coarse = new ArrayDeque<>();
    /** Number of probes each coarse point stands for (same order as {@link #coarse}), for weighted re-merging. */
    private final Deque<Integer> coarseWeight = new ArrayDeque<>();
    private final List<HealthPoint> bucket = new ArrayList<>();
    private long bucketStart = Long.MIN_VALUE;
    private long fineKeepMs;
    private long coarseKeepMs;

    public HealthHistory(final long fineKeepMs, final long coarseKeepMs) {
        this.setRetention(fineKeepMs, coarseKeepMs);
    }

    public synchronized void setRetention(final long fineKeepMs, final long coarseKeepMs) {
        this.fineKeepMs = Math.max(MINUTE, fineKeepMs);
        this.coarseKeepMs = Math.max(MINUTE, coarseKeepMs);
    }

    public static long minuteOf(final long t) {
        return Math.floorDiv(t, MINUTE) * MINUTE;
    }

    /** Adds a probe (time must not go backwards; older probes are ignored). */
    public synchronized void add(final HealthPoint p) {
        if (!this.fine.isEmpty() && p.t() < this.fine.peekLast().t()) {
            return;
        }
        this.fine.addLast(p);
        final long minute = minuteOf(p.t());
        if (minute != this.bucketStart) {
            this.flushBucket();
            this.bucketStart = minute;
        }
        this.bucket.add(p);
        this.trim(p.t());
    }

    private void flushBucket() {
        if (this.bucket.isEmpty()) {
            return;
        }
        this.pushCoarse(HealthPoint.merge(this.bucket, null, this.bucketStart), this.bucket.size());
        this.bucket.clear();
    }

    /** Appends a coarse point; a point of the same minute as the last one is merged into it (e.g. after a restart). */
    private void pushCoarse(final HealthPoint p, final int weight) {
        final HealthPoint last = this.coarse.peekLast();
        if (last != null && last.t() == p.t()) {
            this.coarse.pollLast();
            final int lw = this.coarseWeight.pollLast();
            this.coarse.addLast(HealthPoint.merge(List.of(last, p), new int[] {lw, weight}, p.t()));
            this.coarseWeight.addLast(lw + weight);
            return;
        }
        if (last != null && p.t() < last.t()) {
            return;
        }
        this.coarse.addLast(p);
        this.coarseWeight.addLast(Math.max(1, weight));
    }

    private void trim(final long now) {
        while (!this.fine.isEmpty() && now - this.fine.peekFirst().t() > this.fineKeepMs) {
            this.fine.pollFirst();
        }
        while (!this.coarse.isEmpty() && now - this.coarse.peekFirst().t() > this.coarseKeepMs) {
            this.coarse.pollFirst();
            this.coarseWeight.pollFirst();
        }
    }

    /** Probes with {@code t >= from}, oldest first. */
    public synchronized List<HealthPoint> fine(final long from) {
        final List<HealthPoint> out = new ArrayList<>();
        for (final HealthPoint p : this.fine) {
            if (p.t() >= from) {
                out.add(p);
            }
        }
        return out;
    }

    /** Minute aggregates with {@code t >= from} (bucket start), oldest first, including the open minute. */
    public synchronized List<HealthPoint> coarse(final long from) {
        final List<HealthPoint> out = new ArrayList<>();
        for (final HealthPoint p : this.withOpenBucket().points()) {
            if (p.t() >= from) {
                out.add(p);
            }
        }
        return out;
    }

    /** Newest probe, or null. */
    public synchronized HealthPoint last() {
        return this.fine.peekLast();
    }

    public synchronized int fineSize() {
        return this.fine.size();
    }

    public synchronized int coarseSize() {
        return this.coarse.size() + (this.bucket.isEmpty() ? 0 : 1);
    }

    /**
     * At most {@code max} points: consecutive runs of {@code ceil(n / max)} points are merged (stamped with the first
     * point's time). Returns the input when it is already small enough.
     */
    public static List<HealthPoint> downsample(final List<HealthPoint> points, final int max) {
        if (max <= 0 || points.size() <= max) {
            return points;
        }
        final int step = (points.size() + max - 1) / max;
        final List<HealthPoint> out = new ArrayList<>(max);
        for (int i = 0; i < points.size(); i += step) {
            final List<HealthPoint> group = points.subList(i, Math.min(points.size(), i + step));
            out.add(HealthPoint.merge(group, null, group.get(0).t()));
        }
        return out;
    }

    // ------------------------------------------------------------------ persistence

    /** Saved state: probes and minute aggregates (the open minute is saved as an aggregate). */
    public record Saved(List<HealthPoint> fine, List<HealthPoint> coarse, List<Integer> coarseWeight) {
    }

    public synchronized Saved save() {
        final Weighted c = this.withOpenBucket();
        return new Saved(new ArrayList<>(this.fine), c.points(), c.weights());
    }

    private record Weighted(List<HealthPoint> points, List<Integer> weights) {
    }

    /** Coarse points plus the open minute (merged into the last point when it is the same minute, e.g. after a restart). */
    private Weighted withOpenBucket() {
        final List<HealthPoint> c = new ArrayList<>(this.coarse);
        final List<Integer> w = new ArrayList<>(this.coarseWeight);
        if (!this.bucket.isEmpty()) {
            final HealthPoint open = HealthPoint.merge(this.bucket, null, this.bucketStart);
            final int last = c.size() - 1;
            if (last >= 0 && c.get(last).t() == open.t()) {
                c.set(last, HealthPoint.merge(List.of(c.get(last), open), new int[] {w.get(last), this.bucket.size()}, open.t()));
                w.set(last, w.get(last) + this.bucket.size());
            } else {
                c.add(open);
                w.add(this.bucket.size());
            }
        }
        return new Weighted(c, w);
    }

    /** Restores saved points, dropping expired and out-of-order ones (relative to {@code now}). */
    public synchronized void restore(final Saved s, final long now) {
        this.fine.clear();
        this.coarse.clear();
        this.coarseWeight.clear();
        this.bucket.clear();
        this.bucketStart = Long.MIN_VALUE;
        if (s == null) {
            return;
        }
        if (s.fine() != null) {
            for (final HealthPoint p : s.fine()) {
                if (p != null && now - p.t() <= this.fineKeepMs && p.t() <= now
                    && (this.fine.isEmpty() || p.t() >= this.fine.peekLast().t())) {
                    this.fine.addLast(new HealthPoint(p.t(), p.tps(), p.tpsMin(), p.mspt(), p.msptMax(), p.players(), p.chunks(),
                        p.entities(), p.npcTotal(), p.npc(), p.helpers(), p.warn()));
                }
            }
        }
        if (s.coarse() != null) {
            for (int i = 0; i < s.coarse().size(); i++) {
                final HealthPoint p = s.coarse().get(i);
                if (p == null || now - p.t() > this.coarseKeepMs || p.t() > now) {
                    continue;
                }
                final int w = s.coarseWeight() != null && i < s.coarseWeight().size() && s.coarseWeight().get(i) != null
                    ? s.coarseWeight().get(i) : 1;
                this.pushCoarse(new HealthPoint(minuteOf(p.t()), p.tps(), p.tpsMin(), p.mspt(), p.msptMax(), p.players(), p.chunks(),
                    p.entities(), p.npcTotal(), p.npc(), p.helpers(), p.warn()), w);
            }
        }
    }
}
