// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.panel.PanelPlugin;

/**
 * Every hour (checked each minute) takes one sample of every NPC country (and of player countries with
 * {@code history.player-countries}) on the main thread and writes {@code plugins/Panel/history/<id>.json} in the
 * background (tmp + atomic move). Samples older than {@code history.keep-days} (30) are dropped.
 */
public final class HistoryService {

    private final PanelPlugin plugin;
    private final Path dir;
    private final Map<UUID, HistoryLog> logs = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Panel-History");
        t.setDaemon(true);
        return t;
    });
    private BukkitTask task;
    private long lastHour;

    public HistoryService(final PanelPlugin plugin, final Path dataFolder) {
        this.plugin = plugin;
        this.dir = dataFolder.resolve("history");
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::check, 20L * 30, 20L * 60);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
        }
        this.io.shutdown();
        try {
            this.io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void check() {
        if (!this.plugin.cfg().historyEnabled) {
            return;
        }
        final long hour = HistoryLog.hourOf(System.currentTimeMillis());
        if (hour <= this.lastHour) {
            return;
        }
        this.lastHour = hour;
        try {
            this.sampleAll();
        } catch (final RuntimeException | LinkageError e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie udało się zapisać próbki historii krajów", e);
        }
    }

    /** Main thread: one sample of every tracked country. */
    public void sampleAll() {
        final long now = System.currentTimeMillis();
        final long keep = this.plugin.cfg().historyKeepDays * HistoryLog.DAY;
        for (final KrajInfo k : this.plugin.kraje().getCountries()) {
            if (!k.npc() && !this.plugin.cfg().historyPlayerCountries) {
                continue;
            }
            final HistoryLog log = this.log(k.id());
            log.add(this.sample(k, now), now, keep, k.name());
            final String json = log.toJson();
            final Path file = this.dir.resolve(k.id() + ".json");
            try {
                this.io.execute(() -> this.write(file, json));
            } catch (final RejectedExecutionException e) {
                return; // shutting down
            }
        }
    }

    /** Current values of a country (null = measure unavailable). */
    public HistoryLog.Point sample(final KrajInfo k, final long now) {
        final ArmiaApi armia = this.plugin.armia();
        final LudnoscApi lud = this.plugin.ludnosc();
        Integer soldiers = null;
        if (armia != null) {
            try {
                soldiers = armia.soldierCount(k.id());
            } catch (final RuntimeException | LinkageError e) {
                soldiers = null;
            }
        }
        Integer population = null;
        Long food = null;
        if (lud != null) {
            try {
                population = lud.population(k.id());
                final Optional<LudnoscInfo> info = lud.info(k.id());
                food = info.map(LudnoscInfo::food).orElse(null);
            } catch (final RuntimeException | LinkageError e) {
                food = null;
            }
        }
        return new HistoryLog.Point(now, this.plugin.skarbiec().balance(k.treasuryAccount()), population, soldiers, k.chunkCount(), food);
    }

    /** Samples since {@code from} (oldest first), at most {@code max}. */
    public List<HistoryLog.Point> points(final UUID country, final long from, final int max) {
        return this.log(country).since(from, max);
    }

    private HistoryLog log(final UUID country) {
        return this.logs.computeIfAbsent(country, id -> {
            final Path file = this.dir.resolve(id + ".json");
            if (Files.exists(file)) {
                try {
                    return HistoryLog.fromJson(id, Files.readString(file, StandardCharsets.UTF_8));
                } catch (final IOException e) {
                    this.plugin.getLogger().log(Level.WARNING, "Nie można wczytać " + file, e);
                }
            }
            return new HistoryLog(id, null);
        });
    }

    private void write(final Path file, final String json) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie można zapisać " + file, e);
        }
    }
}
