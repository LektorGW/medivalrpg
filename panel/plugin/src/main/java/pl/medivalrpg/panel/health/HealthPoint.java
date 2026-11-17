// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One server-health sample (fine: one probe every {@code health.interval-seconds}) or one aggregated bucket (coarse:
 * one minute, or several minutes after downsampling). In a single probe {@code tpsMin == tps} and
 * {@code msptMax == mspt}; an aggregate keeps the average in {@code tps}/{@code mspt} and the worst value in
 * {@code tpsMin}/{@code msptMax}, and the maximum of every count (load peaks are what matters). Pure, immutable.
 *
 * @param t        time (ms epoch); bucket start for aggregates
 * @param tps      TPS (Paper 1-minute average, ≤ 20)
 * @param tpsMin   lowest TPS in the bucket
 * @param mspt     milliseconds per tick (Paper average of the last 100 ticks)
 * @param msptMax  highest MSPT in the bucket
 * @param players  players online
 * @param chunks   loaded chunks (all worlds)
 * @param entities loaded entities (all worlds)
 * @param npcTotal NPCs of the MEDIVALRPG plugins (sum of {@code npc})
 * @param npc      NPCs per plugin id ({@link NpcCensus#PLUGINS})
 * @param helpers  helper entities of those plugins (bodies, hitboxes, holograms…), not NPCs
 * @param warn     warning codes active at this sample (union for aggregates)
 */
public record HealthPoint(long t, double tps, double tpsMin, double mspt, double msptMax, int players, int chunks, int entities,
                          int npcTotal, Map<String, Integer> npc, int helpers, List<String> warn) {

    public HealthPoint {
        npc = npc == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(npc));
        warn = warn == null ? List.of() : List.copyOf(warn);
    }

    /** A single probe. */
    public static HealthPoint probe(final long t, final double tps, final double mspt, final int players, final int chunks,
                                   final int entities, final Map<String, Integer> npc, final int helpers) {
        int total = 0;
        if (npc != null) {
            for (final Integer v : npc.values()) {
                total += v == null ? 0 : v;
            }
        }
        return new HealthPoint(t, tps, tps, mspt, mspt, players, chunks, entities, total, npc, helpers, List.of());
    }

    public HealthPoint withWarn(final List<String> codes) {
        return new HealthPoint(this.t, this.tps, this.tpsMin, this.mspt, this.msptMax, this.players, this.chunks, this.entities,
            this.npcTotal, this.npc, this.helpers, codes);
    }

    public HealthPoint withTime(final long time) {
        return new HealthPoint(time, this.tps, this.tpsMin, this.mspt, this.msptMax, this.players, this.chunks, this.entities,
            this.npcTotal, this.npc, this.helpers, this.warn);
    }

    /**
     * Aggregate of {@code group} (not empty) stamped with {@code t}: averages of TPS/MSPT weighted by the number of
     * probes each point stands for ({@code weights}, null = 1 each), worst TPS/MSPT, maxima of the counts, union of warnings.
     */
    public static HealthPoint merge(final List<HealthPoint> group, final int[] weights, final long t) {
        if (group.isEmpty()) {
            throw new IllegalArgumentException("empty group");
        }
        double tpsSum = 0;
        double msptSum = 0;
        long w = 0;
        double tpsMin = Double.MAX_VALUE;
        double msptMax = 0;
        int players = 0;
        int chunks = 0;
        int entities = 0;
        int npcTotal = 0;
        int helpers = 0;
        final Map<String, Integer> npc = new LinkedHashMap<>();
        final Set<String> warn = new LinkedHashSet<>();
        for (int i = 0; i < group.size(); i++) {
            final HealthPoint p = group.get(i);
            final int weight = weights == null ? 1 : Math.max(1, weights[i]);
            tpsSum += p.tps * weight;
            msptSum += p.mspt * weight;
            w += weight;
            tpsMin = Math.min(tpsMin, p.tpsMin);
            msptMax = Math.max(msptMax, p.msptMax);
            players = Math.max(players, p.players);
            chunks = Math.max(chunks, p.chunks);
            entities = Math.max(entities, p.entities);
            npcTotal = Math.max(npcTotal, p.npcTotal);
            helpers = Math.max(helpers, p.helpers);
            for (final Map.Entry<String, Integer> e : p.npc.entrySet()) {
                npc.merge(e.getKey(), e.getValue() == null ? 0 : e.getValue(), Math::max);
            }
            warn.addAll(p.warn);
        }
        return new HealthPoint(t, tpsSum / w, tpsMin, msptSum / w, msptMax, players, chunks, entities, npcTotal, npc, helpers,
            List.copyOf(warn));
    }

    /** Rounds to {@code digits} decimal places (JSON output). */
    public static double round(final double v, final int digits) {
        if (!Double.isFinite(v)) {
            return 0;
        }
        final double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
