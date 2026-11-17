// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import pl.medivalrpg.panel.util.Json;

/** JSON shape of {@code admin.health} (PANEL.md „Wydajność serwera”). Pure. */
public final class HealthJson {

    /** Allowed {@code range} values → length in ms. */
    public static final Map<String, Long> RANGES;

    static {
        final Map<String, Long> r = new LinkedHashMap<>();
        r.put("1h", 3_600_000L);
        r.put("6h", 6 * 3_600_000L);
        r.put("24h", 24 * 3_600_000L);
        RANGES = java.util.Collections.unmodifiableMap(r);
    }

    private HealthJson() {
    }

    /** Loaded world at the last probe. */
    public record WorldStat(String name, String environment, int chunks, int entities, int players, int npc) {
    }

    /** NPCs of one country at the last scan. */
    public record CountryNpc(UUID id, int citizens, int soldiers, int rulers) {
        public int total() {
            return this.citizens + this.soldiers + this.rulers;
        }
    }

    /** State at the last probe (the stat tiles). */
    public record Current(long t, double tps1, double tps5, double tps15, double mspt, int players, int maxPlayers, int chunks,
                          int entities, int npcTotal, Map<String, Integer> npc, Map<String, Integer> kinds, int helpers,
                          List<WorldStat> worlds, List<CountryNpc> countries, double scanMs, long scannedAt, boolean npcScan) {
    }

    public static Map<String, Object> point(final HealthPoint p) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", p.t());
        m.put("tps", HealthPoint.round(p.tps(), 2));
        m.put("tpsMin", HealthPoint.round(p.tpsMin(), 2));
        m.put("mspt", HealthPoint.round(p.mspt(), 2));
        m.put("msptMax", HealthPoint.round(p.msptMax(), 2));
        m.put("players", p.players());
        m.put("chunks", p.chunks());
        m.put("entities", p.entities());
        m.put("npcTotal", p.npcTotal());
        m.put("npc", p.npc());
        m.put("helpers", p.helpers());
        m.put("warn", p.warn());
        return m;
    }

    /** Points of {@code range} from the history: probes for 1 h, minute aggregates (downsampled to {@code maxPoints}) otherwise. */
    public static Map<String, Object> series(final HealthHistory h, final String range, final long now, final long intervalMs, final int maxPoints) {
        final long len = RANGES.getOrDefault(range, RANGES.get("1h"));
        final long from = now - len;
        List<HealthPoint> pts;
        long resolution;
        if (len <= 3_600_000L) {
            pts = h.fine(from);
            resolution = intervalMs;
        } else {
            pts = h.coarse(HealthHistory.minuteOf(from));
            resolution = HealthHistory.MINUTE;
        }
        if (pts.size() > maxPoints) {
            final int step = (pts.size() + maxPoints - 1) / maxPoints;
            pts = HealthHistory.downsample(pts, maxPoints);
            resolution *= step;
        }
        final List<Map<String, Object>> out = new ArrayList<>(pts.size());
        for (final HealthPoint p : pts) {
            out.add(point(p));
        }
        return Json.obj("range", RANGES.containsKey(range) ? range : "1h", "resolutionMs", resolution, "from", from, "to", now, "points", out);
    }

    public static Map<String, Object> current(final Current c, final Function<UUID, Map<String, Object>> countryRef, final int countryLimit) {
        if (c == null) {
            return null;
        }
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", c.t());
        m.put("tps", HealthPoint.round(c.tps1(), 2));
        m.put("tps5", HealthPoint.round(c.tps5(), 2));
        m.put("tps15", HealthPoint.round(c.tps15(), 2));
        m.put("mspt", HealthPoint.round(c.mspt(), 2));
        m.put("players", c.players());
        m.put("maxPlayers", c.maxPlayers());
        m.put("chunks", c.chunks());
        m.put("entities", c.entities());
        m.put("npcScan", c.npcScan());
        m.put("npcTotal", c.npcTotal());
        m.put("npc", c.npc());
        m.put("kinds", c.kinds());
        m.put("helpers", c.helpers());
        final List<Map<String, Object>> worlds = new ArrayList<>();
        for (final WorldStat w : c.worlds()) {
            worlds.add(Json.obj("name", w.name(), "environment", w.environment(), "chunks", w.chunks(), "entities", w.entities(),
                "players", w.players(), "npc", w.npc()));
        }
        m.put("worlds", worlds);
        final List<CountryNpc> sorted = new ArrayList<>(c.countries());
        sorted.sort(Comparator.comparingInt(CountryNpc::total).reversed().thenComparing(x -> x.id().toString()));
        final List<Map<String, Object>> countries = new ArrayList<>();
        for (final CountryNpc x : sorted.subList(0, Math.min(sorted.size(), Math.max(0, countryLimit)))) {
            final Map<String, Object> ref = countryRef == null ? null : countryRef.apply(x.id());
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", x.id().toString());
            row.put("name", ref == null ? null : ref.get("name"));
            row.put("color", ref == null ? "#808080" : ref.get("color"));
            row.put("npc", ref != null && Boolean.TRUE.equals(ref.get("npc")));
            row.put("exists", ref != null);
            row.put("total", x.total());
            row.put("citizens", x.citizens());
            row.put("soldiers", x.soldiers());
            row.put("rulers", x.rulers());
            countries.add(row);
        }
        m.put("countries", countries);
        m.put("countriesTotal", sorted.size());
        m.put("scanMs", HealthPoint.round(c.scanMs(), 2));
        m.put("scannedAt", c.scannedAt());
        return m;
    }

    public static Map<String, Object> thresholds(final HealthWarnings.Thresholds th) {
        return Json.obj("tps", th.tps(), "tpsCritical", th.tpsCritical(), "mspt", th.mspt(), "msptCritical", th.msptCritical(),
            "npc", th.npc(), "npcCritical", th.npcCritical(), "entities", th.entities(), "entitiesCritical", th.entitiesCritical(),
            "npcPlugin", th.npcPlugin(), "sustain", th.sustain());
    }

    public static List<Map<String, Object>> warnings(final List<HealthWarnings.Warning> active) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final HealthWarnings.Warning w : active) {
            out.add(Json.obj("code", w.code(), "level", w.level().name(), "text", w.text(), "value", HealthPoint.round(w.value(), 2),
                "threshold", HealthPoint.round(w.threshold(), 2), "since", w.since()));
        }
        return out;
    }

    public static List<Map<String, Object>> episodes(final List<HealthWarnings.Episode> eps, final int limit) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final HealthWarnings.Episode e : eps) {
            if (out.size() >= limit) {
                break;
            }
            out.add(Json.obj("code", e.code, "level", e.level, "text", e.text, "start", e.start, "end", e.end,
                "worst", HealthPoint.round(e.worst, 2)));
        }
        return out;
    }

    public static List<Map<String, Object>> plugins() {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final String p : NpcCensus.PLUGINS) {
            out.add(Json.obj("id", p, "name", NpcCensus.NAMES.get(p)));
        }
        return out;
    }
}
