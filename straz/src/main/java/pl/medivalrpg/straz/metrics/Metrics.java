// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.metrics;

import java.util.Map;
import java.util.TreeMap;

/**
 * Hourly performance and activity counters (pure; serialized in the state): TPS samples, lag spikes, peak players,
 * citizens killed. Keys are hour starts (epoch ms).
 */
public final class Metrics {

    private static final long HOUR = 3_600_000L;

    /** One hour. */
    public static final class Hour {
        public double tpsSum;
        public int tpsSamples;
        public double tpsMin = 20.0;
        public int lagSpikes;
        public double worstTickMs;
        public int peakPlayers;
        public long peakAt;
        public int citizensKilled;
        public int lowTpsMinutes;
    }

    /** Aggregate of a window. */
    public record Window(double tpsAvg, double tpsMin, int samples, int lagSpikes, double worstTickMs, int peakPlayers,
                         long peakAt, int citizensKilled, int lowTpsMinutes) {
    }

    public TreeMap<Long, Hour> hours = new TreeMap<>();

    private Hour at(final long time) {
        return this.hours.computeIfAbsent(time - Math.floorMod(time, HOUR), k -> new Hour());
    }

    /** One TPS sample (e.g. the 1-minute average, taken every minute). */
    public void tps(final long time, final double tps, final boolean low) {
        final Hour h = this.at(time);
        final double v = Math.min(20.0, Math.max(0.0, tps));
        h.tpsSum += v;
        h.tpsSamples++;
        h.tpsMin = Math.min(h.tpsMin, v);
        if (low) {
            h.lowTpsMinutes++;
        }
    }

    public void lagSpike(final long time, final double tickMs) {
        final Hour h = this.at(time);
        h.lagSpikes++;
        h.worstTickMs = Math.max(h.worstTickMs, tickMs);
    }

    public void players(final long time, final int online) {
        final Hour h = this.at(time);
        if (online > h.peakPlayers) {
            h.peakPlayers = online;
            h.peakAt = time;
        }
    }

    public void citizenKilled(final long time) {
        this.at(time).citizensKilled++;
    }

    /** Aggregate of [now − windowMs, now] at hour precision (the oldest partial hour is included whole). */
    public Window window(final long now, final long windowMs) {
        final long from = now - windowMs;
        double sum = 0;
        int n = 0;
        double min = Double.NaN;
        int spikes = 0;
        double worst = 0;
        int peak = 0;
        long peakAt = 0;
        int killed = 0;
        int low = 0;
        for (final Map.Entry<Long, Hour> e : this.hours.tailMap(from - Math.floorMod(from, HOUR)).entrySet()) {
            if (e.getKey() > now) {
                continue;
            }
            final Hour h = e.getValue();
            sum += h.tpsSum;
            n += h.tpsSamples;
            if (h.tpsSamples > 0) {
                min = Double.isNaN(min) ? h.tpsMin : Math.min(min, h.tpsMin);
            }
            spikes += h.lagSpikes;
            worst = Math.max(worst, h.worstTickMs);
            if (h.peakPlayers > peak) {
                peak = h.peakPlayers;
                peakAt = h.peakAt;
            }
            killed += h.citizensKilled;
            low += h.lowTpsMinutes;
        }
        return new Window(n == 0 ? Double.NaN : sum / n, min, n, spikes, worst, peak, peakAt, killed, low);
    }

    public void prune(final long now, final long keepMs) {
        this.hours.headMap(now - keepMs).clear();
    }
}
