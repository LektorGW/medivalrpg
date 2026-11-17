// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.panel.Messages;
import pl.medivalrpg.panel.PanelPlugin;

/**
 * Server health probe (PANEL.md „Wydajność serwera”): every {@code health.interval-seconds} on the main thread reads
 * TPS ({@code Bukkit.getTPS()}), MSPT ({@code getAverageTickTime()}), players, loaded chunks and entities per world, and
 * (every {@code npc-scan-every} probes) counts the NPCs of Ludnosc / Armia / Skarbiec / Władcy / Życie by their PDC
 * tags ({@link NpcCensus}). Probes go to {@link HealthHistory} (1 h at full resolution + 24 h of minute aggregates),
 * thresholds to {@link HealthWarnings}; new warnings are sent in game to holders of {@code health.alerts.permission}
 * (and online panel admins), rate-limited per code. State is saved to {@code plugins/Panel/health.json} periodically
 * (in the background) and on shutdown.
 */
public final class HealthService {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** File format of health.json. */
    static final class FileData {
        int version = 1;
        long savedAt;
        List<HealthPoint> fine;
        List<HealthPoint> coarse;
        List<Integer> coarseWeight;
        List<HealthWarnings.Episode> episodes;
    }

    private final PanelPlugin plugin;
    private final Path file;
    private final HealthHistory history;
    private final HealthWarnings warnings = new HealthWarnings();
    private final Map<String, NamespacedKey> keys = new HashMap<>();
    /** Last alert per "code:level" (rate limit). */
    private final Map<String, Long> lastAlert = new HashMap<>();
    /** Codes whose start was announced in game (only those get a recovery message). */
    private final Set<String> announced = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Panel-Health");
        t.setDaemon(true);
        return t;
    });
    private BukkitTask probeTask;
    private BukkitTask saveTask;
    private int probeIntervalSeconds;
    private int saveMinutes = -1;
    private long probes;
    private volatile HealthJson.Current current;
    private NpcCensus lastCensus;
    private double lastScanMs;
    private long lastScanAt;

    public HealthService(final PanelPlugin plugin, final Path dataFolder) {
        this.plugin = plugin;
        this.file = dataFolder.resolve("health.json");
        final HealthSettings s = this.settings();
        this.history = new HealthHistory(s.fineMinutes() * 60_000L, s.coarseHours() * 3_600_000L);
    }

    private HealthSettings settings() {
        return this.plugin.cfg().health;
    }

    public HealthHistory history() {
        return this.history;
    }

    public HealthWarnings warnings() {
        return this.warnings;
    }

    public HealthJson.Current current() {
        return this.current;
    }

    // ------------------------------------------------------------------ lifecycle (main thread)

    public void start() {
        this.load();
        this.schedule();
    }

    /** {@code /panel reload}: new interval / retention / save period. */
    public void reload() {
        final HealthSettings s = this.settings();
        this.history.setRetention(s.fineMinutes() * 60_000L, s.coarseHours() * 3_600_000L);
        this.schedule();
    }

    private void schedule() {
        final HealthSettings s = this.settings();
        if (this.probeTask == null || this.probeIntervalSeconds != s.intervalSeconds()) {
            if (this.probeTask != null) {
                this.probeTask.cancel();
            }
            this.probeIntervalSeconds = s.intervalSeconds();
            final long ticks = 20L * s.intervalSeconds();
            this.probeTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tick, Math.min(ticks, 20L * 5), ticks);
        }
        if (this.saveMinutes != s.saveMinutes()) {
            if (this.saveTask != null) {
                this.saveTask.cancel();
                this.saveTask = null;
            }
            this.saveMinutes = s.saveMinutes();
            if (s.saveMinutes() > 0) {
                final long ticks = 20L * 60 * s.saveMinutes();
                this.saveTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> this.saveAsync(), ticks, ticks);
            }
        }
    }

    public void stop() {
        if (this.probeTask != null) {
            this.probeTask.cancel();
        }
        if (this.saveTask != null) {
            this.saveTask.cancel();
        }
        // Queue the final snapshot behind earlier writes: never overwrite it with an older queued save.
        final FileData finalSnapshot = this.snapshot(true);
        this.io.execute(() -> this.write(GSON.toJson(finalSnapshot)));
        this.io.shutdown();
        try {
            this.io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void tick() {
        if (!this.settings().enabled()) {
            return;
        }
        try {
            this.probe();
        } catch (final RuntimeException | LinkageError e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie udało się zmierzyć wydajności serwera", e);
        }
    }

    // ------------------------------------------------------------------ probe (main thread)

    /** One probe now. */
    public void probe() {
        final HealthSettings s = this.settings();
        final long now = System.currentTimeMillis();
        final double[] tps = Bukkit.getTPS();
        final double tps1 = tps.length > 0 ? Math.min(20.0, tps[0]) : 20.0;
        final double tps5 = tps.length > 1 ? Math.min(20.0, tps[1]) : tps1;
        final double tps15 = tps.length > 2 ? Math.min(20.0, tps[2]) : tps1;
        final double mspt = Bukkit.getAverageTickTime();
        final int players = Bukkit.getOnlinePlayers().size();

        final boolean scan = s.npcScan() && (this.lastCensus == null || this.probes % s.npcScanEvery() == 0);
        this.probes++;
        if (scan) {
            final long t0 = System.nanoTime();
            final NpcCensus census = new NpcCensus();
            for (final World w : Bukkit.getWorlds()) {
                for (final Entity e : w.getEntities()) {
                    if (e instanceof Player) {
                        continue;
                    }
                    final PersistentDataContainer pdc = e.getPersistentDataContainer();
                    if (pdc.isEmpty()) {
                        continue;
                    }
                    census.add(NpcCensus.classify(this.tags(pdc, e.getType().name())), w.getName());
                }
            }
            this.lastCensus = census;
            this.lastScanMs = (System.nanoTime() - t0) / 1_000_000.0;
            this.lastScanAt = now;
        } else if (!s.npcScan()) {
            this.lastCensus = null;
        }
        final NpcCensus census = this.lastCensus == null ? new NpcCensus() : this.lastCensus;

        int chunks = 0;
        int entities = 0;
        final List<HealthJson.WorldStat> worlds = new ArrayList<>();
        for (final World w : Bukkit.getWorlds()) {
            final int c = w.getChunkCount();
            final int en = w.getEntityCount();
            chunks += c;
            entities += en;
            worlds.add(new HealthJson.WorldStat(w.getName(), w.getEnvironment().name(), c, en, w.getPlayers().size(),
                census.byWorld().getOrDefault(w.getName(), 0)));
        }
        final Map<String, Integer> npc = s.npcScan() ? census.byPlugin() : Map.of();
        HealthPoint p = HealthPoint.probe(now, tps1, mspt, players, chunks, entities, npc, s.npcScan() ? census.helpers() : 0);
        final HealthWarnings.Update u = this.warnings.update(p, s.thresholds());
        p = p.withWarn(u.codes());
        this.history.add(p);

        final List<HealthJson.CountryNpc> countries = new ArrayList<>();
        for (final Map.Entry<UUID, NpcCensus.CountryCount> e : census.byCountry().entrySet()) {
            countries.add(new HealthJson.CountryNpc(e.getKey(), e.getValue().citizens, e.getValue().soldiers, e.getValue().rulers));
        }
        this.current = new HealthJson.Current(now, tps1, tps5, tps15, mspt, players, Bukkit.getMaxPlayers(), chunks, entities,
            p.npcTotal(), npc, s.npcScan() ? census.byKind() : Map.of(), p.helpers(), worlds, countries, this.lastScanMs,
            this.lastScanAt, s.npcScan());
        this.alert(u, now, s.alerts());
    }

    private NpcCensus.Tags tags(final PersistentDataContainer pdc, final String entityType) {
        return new NpcCensus.Tags() {
            private Set<String> ns;

            @Override
            public String entityType() { return entityType; }

            @Override
            public Set<String> namespaces() {
                if (this.ns == null) {
                    final Set<String> out = new HashSet<>(4);
                    for (final NamespacedKey k : pdc.getKeys()) {
                        out.add(k.getNamespace());
                    }
                    this.ns = out;
                }
                return this.ns;
            }

            @Override
            public boolean has(final String namespace, final String key) {
                return pdc.has(HealthService.this.key(namespace, key));
            }

            @Override
            public String string(final String namespace, final String key) {
                final NamespacedKey k = HealthService.this.key(namespace, key);
                try {
                    return pdc.has(k, PersistentDataType.STRING) ? pdc.get(k, PersistentDataType.STRING) : null;
                } catch (final IllegalArgumentException e) {
                    return null;
                }
            }
        };
    }

    private NamespacedKey key(final String namespace, final String key) {
        return this.keys.computeIfAbsent(namespace + ':' + key, x -> new NamespacedKey(namespace, key));
    }

    // ------------------------------------------------------------------ alerts (main thread)

    private void alert(final HealthWarnings.Update u, final long now, final HealthSettings.Alerts a) {
        final Messages m = this.plugin.messages();
        for (final HealthWarnings.Warning w : u.raised()) {
            final String key = w.code() + ':' + w.level().name();
            final Long last = this.lastAlert.get(key);
            if (last != null && now - last < a.cooldownMs()) {
                continue;
            }
            this.lastAlert.put(key, now);
            if (a.console()) {
                this.plugin.getLogger().warning("Wydajność: " + w.text());
            }
            if (a.enabled()) {
                final String msg = w.level() == HealthWarnings.Level.CRITICAL ? "health-alert-critical" : "health-alert";
                for (final Player p : this.recipients(a)) {
                    m.send(p, msg, Messages.text("value", w.text()), Messages.text("target", w.code()));
                }
                this.announced.add(w.code());
            }
        }
        for (final HealthWarnings.Warning w : u.ended()) {
            if (a.console()) {
                this.plugin.getLogger().info("Wydajność: koniec ostrzeżenia " + w.code());
            }
            final boolean wasAnnounced = this.announced.remove(w.code());
            if (a.enabled() && a.recovery() && wasAnnounced) {
                for (final Player p : this.recipients(a)) {
                    m.send(p, "health-recovered", Messages.text("value", label(w.code())), Messages.text("target", w.code()));
                }
            }
        }
        this.lastAlert.values().removeIf(t -> now - t > 24 * 3_600_000L);
    }

    private List<Player> recipients(final HealthSettings.Alerts a) {
        final List<Player> out = new ArrayList<>();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(a.permission()) || (a.panelAdmins() && this.plugin.roles().isAdmin(p.getUniqueId(), p.getName()))) {
                out.add(p);
            }
        }
        return out;
    }

    /** Polish label of a warning code. */
    public static String label(final String code) {
        return switch (code) {
            case "TPS" -> "TPS";
            case "MSPT" -> "czas ticka (MSPT)";
            case "NPC" -> "liczba NPC";
            case "ENTITIES" -> "liczba encji";
            default -> code.startsWith("NPC_")
                ? "NPC (" + NpcCensus.NAMES.getOrDefault(code.substring(4).toLowerCase(java.util.Locale.ROOT), code.substring(4)) + ")"
                : code;
        };
    }

    // ------------------------------------------------------------------ persistence

    private FileData snapshot(final boolean stopping) {
        final FileData d = new FileData();
        d.savedAt = System.currentTimeMillis();
        final HealthHistory.Saved s = this.history.save();
        d.fine = s.fine();
        d.coarse = s.coarse();
        d.coarseWeight = s.coarseWeight();
        d.episodes = this.warnings.save(d.savedAt, stopping);
        return d;
    }

    /** Main thread: snapshot now, write in the background. */
    public void saveAsync() {
        final FileData snapshot = this.snapshot(false);
        try {
            this.io.execute(() -> this.write(GSON.toJson(snapshot)));
        } catch (final RejectedExecutionException e) {
            // shutting down: stop() writes synchronously
        }
    }

    private void load() {
        if (!Files.exists(this.file)) {
            return;
        }
        try {
            final FileData d = GSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8), FileData.class);
            if (d == null) {
                return;
            }
            final long now = System.currentTimeMillis();
            this.history.restore(new HealthHistory.Saved(d.fine, d.coarse, d.coarseWeight), now);
            this.warnings.restore(d.episodes, now);
        } catch (final IOException | JsonParseException | IllegalStateException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie można wczytać " + this.file + " — historia wydajności zaczyna się od nowa", e);
        }
    }

    private synchronized void write(final String json) {
        try {
            Files.createDirectories(this.file.getParent());
            final Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.exists(this.file)) {
                Files.copy(this.file, this.file.resolveSibling(this.file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie można zapisać " + this.file, e);
        }
    }
}
