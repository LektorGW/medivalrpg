// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.history.HistoryLog;

/**
 * Economy of a country for the web panel ({@code country.economy}). Everything runs on the main thread: Ludnosc's
 * {@code materialFlow} reads loaded containers and refuses other threads.
 * <ul>
 *   <li><b>Answer</b>: one country per call, built from fresh Ludnosc snapshots. A finished answer is reused for
 *       {@code economy.cache-seconds} (15 s) per country so that many open tabs do not multiply the work; {@code fresh}
 *       rebuilds it, at most every {@code economy.fresh-min-seconds} (5 s). The permission check always happens first, in
 *       the RPC method, and the cache never leaves the server (the Worker and the browser do not store this method).</li>
 *   <li><b>Sampler</b>: once per second at most ONE country whose last sample is older than {@code economy.sample-seconds}
 *       (300 s) is read, to keep the production estimate and the charts going while nobody watches. Skipped when the server
 *       is slow (average tick above {@code economy.skip-above-mspt}); a read slower than {@code economy.budget-ms} pauses the
 *       sampler proportionally.</li>
 * </ul>
 */
public final class EconomyService {

    private record Cached(long at, Map<String, Object> data) {
    }

    private final PanelPlugin plugin;
    private final LudnoscBridge bridge = new LudnoscBridge();
    private final EconomyTracker tracker = new EconomyTracker();
    private final Map<UUID, Cached> cache = new HashMap<>();
    /** Last sampler attempt per country (also failed ones, so a broken country is not retried every second). */
    private final Map<UUID, Long> attempted = new HashMap<>();
    /** Last measured read cost per country (ms) — a costly country is sampled less often. */
    private final Map<UUID, Double> cost = new HashMap<>();
    private BukkitTask task;
    private int cursor;
    private long pausedUntil;
    private long ticks;
    private double lastSampleMs;

    public EconomyService(final PanelPlugin plugin) {
        this.plugin = plugin;
    }

    public EconomyTracker tracker() {
        return this.tracker;
    }

    // ------------------------------------------------------------------ settings (re-read: /panel reload)

    private FileConfiguration cfg() {
        return this.plugin.getConfig();
    }

    private boolean enabled() {
        return this.cfg().getBoolean("economy.enabled", true);
    }

    private long cacheMs() {
        return Math.max(0, Math.min(300, this.cfg().getInt("economy.cache-seconds", 15))) * 1000L;
    }

    private long freshMinMs() {
        return Math.max(1, Math.min(60, this.cfg().getInt("economy.fresh-min-seconds", 5))) * 1000L;
    }

    private long sampleMs() {
        return Math.max(60, Math.min(3600, this.cfg().getInt("economy.sample-seconds", 300))) * 1000L;
    }

    private double budgetMs() {
        return Math.max(1, Math.min(50, this.cfg().getDouble("economy.budget-ms", 8)));
    }

    private boolean sampleNpc() {
        return this.cfg().getBoolean("economy.sample-npc-countries", false);
    }

    /**
     * Interval of one country: {@code sample-seconds}, stretched by its last read cost — a read of k × {@code budget-ms}
     * waits k × the interval (e.g. 20 ms at budget 8 → every 12.5 min instead of 5).
     */
    static long intervalFor(final long baseMs, final double costMs, final double budgetMs) {
        return costMs > budgetMs ? (long) (baseMs * (costMs / budgetMs)) : baseMs;
    }

    private double skipAboveMspt() {
        return this.cfg().getDouble("economy.skip-above-mspt", 45);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the sampler (main thread, every second). Bukkit cancels it when the plugin is disabled. */
    public void start() {
        if (this.task == null) {
            this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, 20L * 45, 20L);
        }
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private Plugin ludnoscPlugin() {
        final Plugin p = Bukkit.getPluginManager().getPlugin("Ludnosc");
        return p != null && p.isEnabled() ? p : null;
    }

    // ------------------------------------------------------------------ answer

    /** Main thread. {@code access} is the viewer's role, already checked by the caller. */
    public Map<String, Object> report(final KrajInfo k, final String access, final boolean fresh) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("country.economy requires the main thread");
        }
        if (!this.enabled()) {
            throw new IllegalStateException("disabled");
        }
        final long now = System.currentTimeMillis();
        final Cached c = this.cache.get(k.id());
        final long age = c == null ? Long.MAX_VALUE : now - c.at();
        if (c != null && (age < (fresh ? this.freshMinMs() : this.cacheMs()))) {
            return this.answer(c.data(), access, true, age);
        }
        final long t0 = System.nanoTime();
        final Map<String, Object> data = this.build(k, now);
        final double ms = (System.nanoTime() - t0) / 1e6;
        data.put("buildMs", Math.round(ms * 100) / 100.0);
        this.cost.put(k.id(), ms);
        this.cache.put(k.id(), new Cached(now, data));
        return this.answer(data, access, false, 0);
    }

    private Map<String, Object> answer(final Map<String, Object> data, final String access, final boolean cached, final long ageMs) {
        final Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("access", access);
        out.put("cached", cached);
        out.put("ageMs", ageMs);
        out.put("refreshSeconds", Math.max(15, this.cacheMs() / 1000));
        return out;
    }

    /** Builds a fresh answer (also feeds the tracker). */
    Map<String, Object> build(final KrajInfo k, final long now) {
        final Plugin lud = this.ludnoscPlugin();
        final LudnoscBridge.Sources src = this.bridge.read(lud, k.id());
        final EconomyReport.Info info = this.info(k.id());
        final EconomyReport.Growth base = this.growthBase(k.id(), now);
        return EconomyReport.build(new EconomyReport.Input(k.id(), k.name(), k.hexColor(), k.npc(), null, now, src, info, this.tracker, base,
            lud != null));
    }

    private EconomyReport.Info info(final UUID country) {
        final LudnoscApi api = this.plugin.ludnosc();
        if (api == null) {
            return null;
        }
        try {
            final Optional<LudnoscInfo> o = api.info(country);
            if (o.isEmpty()) {
                return null;
            }
            final LudnoscInfo i = o.get();
            final List<String> functions = new ArrayList<>();
            if (i.buildings() != null) {
                for (final LudnoscInfo.Building b : i.buildings()) {
                    if (b != null && b.function() != null) {
                        functions.add(b.function());
                    }
                }
            }
            String settlement = null;
            try {
                settlement = i.settlementName();
            } catch (final LinkageError e) {
                settlement = null;
            }
            return new EconomyReport.Info(i.population(), i.housing(), i.food(), i.foodCapacity(), i.foodBalance(), i.dailyTax(), i.hungryDays(),
                i.homelessDays(), functions, settlement == null || settlement.isBlank() ? null : settlement);
        } catch (final RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Population about 24 h ago: Panel's hourly history (NPC countries, or all with history.player-countries), else the tracker. */
    private EconomyReport.Growth growthBase(final UUID country, final long now) {
        try {
            if (this.plugin.history() != null) {
                final List<HistoryLog.Point> pts = this.plugin.history().points(country, now - 26 * HistoryLog.HOUR, 40);
                HistoryLog.Point best = null;
                for (final HistoryLog.Point p : pts) {
                    if (p.population() != null && p.t() <= now - 20 * HistoryLog.HOUR) {
                        best = p;
                    }
                }
                if (best != null) {
                    return new EconomyReport.Growth(best.population(), best.t(), "history");
                }
            }
        } catch (final RuntimeException e) {
            // fall back to the tracker
        }
        for (final EconomyTracker.Point p : this.tracker.points(country, now - EconomyTracker.DAY)) {
            if (p.population() != null && now - p.t() >= EconomyTracker.HOUR) {
                return new EconomyReport.Growth(p.population(), p.t(), "panel");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ sampler

    private void tick() {
        this.ticks++;
        if (!this.enabled() || this.ludnoscPlugin() == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now < this.pausedUntil) {
            return;
        }
        try {
            if (Bukkit.getServer().getAverageTickTime() > this.skipAboveMspt()) {
                return;
            }
        } catch (final RuntimeException | LinkageError e) {
            // not available: sample anyway
        }
        final List<KrajInfo> all = new ArrayList<>(this.plugin.kraje().getCountries());
        if (all.isEmpty()) {
            return;
        }
        if (this.ticks % 600 == 0) {
            final Set<UUID> ids = new HashSet<>();
            all.forEach(k -> ids.add(k.id()));
            this.tracker.retain(ids);
            this.cache.keySet().retainAll(ids);
            this.attempted.keySet().retainAll(ids);
            this.cost.keySet().retainAll(ids);
        }
        final long base = this.sampleMs();
        final boolean npc = this.sampleNpc();
        for (int i = 0; i < all.size(); i++) {
            final KrajInfo k = all.get(Math.floorMod(this.cursor++, all.size()));
            if (k.npc() && !npc) {
                continue; // NPC countries: on demand only (admin, owner, manager open the page)
            }
            final long interval = intervalFor(base, this.cost.getOrDefault(k.id(), 0.0), this.budgetMs());
            if (now - Math.max(this.tracker.lastSample(k.id()), this.attempted.getOrDefault(k.id(), 0L)) < interval) {
                continue;
            }
            this.attempted.put(k.id(), now);
            final long t0 = System.nanoTime();
            try {
                this.cache.put(k.id(), new Cached(now, this.withMs(this.build(k, now), t0)));
            } catch (final RuntimeException | LinkageError e) {
                this.plugin.getLogger().log(Level.FINE, "Próbka gospodarki kraju " + k.name() + " nieudana", e);
            }
            this.lastSampleMs = (System.nanoTime() - t0) / 1e6;
            this.cost.put(k.id(), this.lastSampleMs);
            if (this.lastSampleMs > this.budgetMs()) {
                // a slow read: wait proportionally before the next one (e.g. 20 ms at budget 8 → 2.5 s)
                this.pausedUntil = now + (long) (1000 * this.lastSampleMs / this.budgetMs());
            }
            return; // at most one country per run
        }
    }

    private Map<String, Object> withMs(final Map<String, Object> data, final long t0) {
        data.put("buildMs", Math.round((System.nanoTime() - t0) / 1e4) / 100.0);
        return data;
    }

    /** Last sampler read in ms (diagnostics). */
    public double lastSampleMs() {
        return this.lastSampleMs;
    }
}
