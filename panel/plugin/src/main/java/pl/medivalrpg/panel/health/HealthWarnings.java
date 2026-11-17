// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Health warnings from configured thresholds. A threshold must be crossed in {@code sustain} consecutive probes before
 * the warning becomes active (a single lag spike does not alarm); it ends at the first probe within limits. Every
 * active period is an {@link Episode} (kept 24 h, at most 200). Codes: {@code TPS}, {@code MSPT}, {@code NPC},
 * {@code ENTITIES} and {@code NPC_<PLUGIN>} (e.g. {@code NPC_ARMIA}). Thresholds ≤ 0 are disabled. Pure; synchronized.
 */
public final class HealthWarnings {

    public static final long EPISODE_KEEP_MS = 24 * 3_600_000L;
    public static final int EPISODE_MAX = 200;
    private static final Locale PL = Locale.forLanguageTag("pl-PL");

    /**
     * Thresholds (≤ 0 = off). TPS warns <i>below</i>, the rest <i>above</i>.
     *
     * @param npcPlugin per-plugin NPC limit (plugin id → count)
     * @param sustain   consecutive probes needed to activate
     */
    public record Thresholds(double tps, double tpsCritical, double mspt, double msptCritical, int npc, int npcCritical,
                             int entities, int entitiesCritical, Map<String, Integer> npcPlugin, int sustain) {
        public Thresholds {
            npcPlugin = npcPlugin == null ? Map.of() : Map.copyOf(npcPlugin);
            sustain = Math.max(1, sustain);
        }
    }

    public enum Level { WARN, CRITICAL }

    /** A violated threshold in one probe. */
    public record Violation(String code, Level level, double value, double threshold, String text) {
    }

    /** A warning active now. */
    public record Warning(String code, Level level, double value, double threshold, String text, long since) {
    }

    /** One active period of a warning ({@code end} null = still active). */
    public static final class Episode {
        public String code;
        public String level;
        public long start;
        public Long end;
        public double worst;
        public String text;

        public Episode() {
        }

        Episode(final Violation v, final long start) {
            this.code = v.code();
            this.level = v.level().name();
            this.start = start;
            this.worst = v.value();
            this.text = v.text();
        }
    }

    /** Outcome of one probe: warnings active now, newly started or escalated ones, and codes that just ended. */
    public record Update(List<Warning> active, List<Warning> raised, List<Warning> ended) {
        public List<String> codes() {
            return this.active.stream().map(Warning::code).toList();
        }
    }

    private final Map<String, Integer> streak = new HashMap<>();
    private final Map<String, Warning> active = new LinkedHashMap<>();
    private final Map<String, Episode> open = new HashMap<>();
    private final Deque<Episode> episodes = new ArrayDeque<>();

    /** Thresholds crossed by {@code p} (no state). */
    public static List<Violation> check(final HealthPoint p, final Thresholds th) {
        final List<Violation> out = new ArrayList<>();
        if ((th.tps() > 0 && p.tps() < th.tps()) || (th.tpsCritical() > 0 && p.tps() < th.tpsCritical())) {
            final boolean crit = th.tpsCritical() > 0 && p.tps() < th.tpsCritical();
            final double limit = crit ? th.tpsCritical() : th.tps();
            out.add(new Violation("TPS", crit ? Level.CRITICAL : Level.WARN, p.tps(), limit,
                "Niski TPS: " + dec(p.tps(), 1) + " (próg " + dec(limit, 1) + ")"));
        }
        if ((th.mspt() > 0 && p.mspt() > th.mspt()) || (th.msptCritical() > 0 && p.mspt() > th.msptCritical())) {
            final boolean crit = th.msptCritical() > 0 && p.mspt() > th.msptCritical();
            final double limit = crit ? th.msptCritical() : th.mspt();
            out.add(new Violation("MSPT", crit ? Level.CRITICAL : Level.WARN, p.mspt(), limit,
                "Długi tick: " + dec(p.mspt(), 1) + " ms (próg " + dec(limit, 1) + " ms)"));
        }
        count(out, "NPC", "Dużo NPC", p.npcTotal(), th.npc(), th.npcCritical());
        count(out, "ENTITIES", "Dużo encji", p.entities(), th.entities(), th.entitiesCritical());
        for (final Map.Entry<String, Integer> e : th.npcPlugin().entrySet()) {
            final Integer v = p.npc().get(e.getKey());
            if (v != null) {
                count(out, "NPC_" + e.getKey().toUpperCase(Locale.ROOT), "Dużo NPC (" + NpcCensus.NAMES.getOrDefault(e.getKey(), e.getKey()) + ")",
                    v, e.getValue() == null ? 0 : e.getValue(), 0);
            }
        }
        return out;
    }

    private static void count(final List<Violation> out, final String code, final String label, final int value, final int warn, final int crit) {
        if ((warn > 0 && value > warn) || (crit > 0 && value > crit)) {
            final boolean c = crit > 0 && value > crit;
            final int limit = c ? crit : warn;
            out.add(new Violation(code, c ? Level.CRITICAL : Level.WARN, value, limit, label + ": " + value + " (próg " + limit + ")"));
        }
    }

    static String dec(final double v, final int digits) {
        return String.format(PL, "%." + digits + "f", v);
    }

    /** Applies one probe. */
    public synchronized Update update(final HealthPoint p, final Thresholds th) {
        final Map<String, Violation> now = new LinkedHashMap<>();
        for (final Violation v : check(p, th)) {
            now.put(v.code(), v);
        }
        final List<Warning> raised = new ArrayList<>();
        final List<Warning> ended = new ArrayList<>();
        for (final String code : List.copyOf(this.active.keySet())) {
            if (!now.containsKey(code)) {
                ended.add(this.active.remove(code));
                final Episode ep = this.open.remove(code);
                if (ep != null) {
                    ep.end = p.t();
                }
            }
        }
        this.streak.keySet().retainAll(now.keySet());
        for (final Violation v : now.values()) {
            final int n = this.streak.merge(v.code(), 1, Integer::sum);
            final Warning prev = this.active.get(v.code());
            if (prev == null && n < th.sustain()) {
                continue;
            }
            final long since = prev == null ? p.t() : prev.since();
            final Warning w = new Warning(v.code(), v.level(), v.value(), v.threshold(), v.text(), since);
            this.active.put(v.code(), w);
            Episode ep = this.open.get(v.code());
            if (ep == null) {
                ep = new Episode(v, p.t());
                this.open.put(v.code(), ep);
                this.episodes.addLast(ep);
                raised.add(w);
            } else {
                if (v.level() == Level.CRITICAL && !Level.CRITICAL.name().equals(ep.level)) {
                    ep.level = Level.CRITICAL.name();
                    raised.add(w);
                }
                final boolean worse = "TPS".equals(v.code()) ? v.value() < ep.worst : v.value() > ep.worst;
                if (worse) {
                    ep.worst = v.value();
                    ep.text = v.text();
                }
            }
        }
        this.trim(p.t());
        return new Update(List.copyOf(this.active.values()), raised, ended);
    }

    private void trim(final long now) {
        while (this.episodes.size() > EPISODE_MAX
            || (!this.episodes.isEmpty() && this.episodes.peekFirst().end != null && now - this.episodes.peekFirst().end > EPISODE_KEEP_MS)) {
            final Episode e = this.episodes.pollFirst();
            this.open.values().remove(e);
        }
    }

    public synchronized List<Warning> active() {
        return List.copyOf(this.active.values());
    }

    /** Episodes, newest first. */
    public synchronized List<Episode> episodes() {
        final List<Episode> out = new ArrayList<>(this.episodes);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Saved episodes (oldest first); open ones are closed at {@code now} (the server is stopping). */
    public synchronized List<Episode> save(final long now, final boolean closeOpen) {
        final List<Episode> saved = new ArrayList<>();
        for (final Episode e : this.episodes) {
            final Episode copy = new Episode();
            copy.code = e.code;
            copy.level = e.level;
            copy.start = e.start;
            copy.end = closeOpen && e.end == null ? Long.valueOf(now) : e.end;
            copy.worst = e.worst;
            copy.text = e.text;
            saved.add(copy);
        }
        return saved;
    }

    /** Restores closed episodes (open ones from an unclean stop get {@code end = start}). */
    public synchronized void restore(final List<Episode> saved, final long now) {
        this.episodes.clear();
        this.open.clear();
        this.active.clear();
        this.streak.clear();
        if (saved == null) {
            return;
        }
        for (final Episode e : saved) {
            if (e == null || e.code == null) {
                continue;
            }
            if (e.end == null) {
                e.end = e.start;
            }
            if (now - e.end <= EPISODE_KEEP_MS) {
                this.episodes.addLast(e);
            }
        }
        while (this.episodes.size() > EPISODE_MAX) {
            this.episodes.pollFirst();
        }
    }
}
