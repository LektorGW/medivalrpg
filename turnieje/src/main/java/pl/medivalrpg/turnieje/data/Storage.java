// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import com.google.gson.reflect.TypeToken;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import pl.medivalrpg.turnieje.arena.Arena;

/**
 * All persistent data of the plugin (main thread only):
 * <ul>
 *   <li>{@code arenas.json} — arenas;</li>
 *   <li>{@code stats.json} — wins, losses, titles (saved periodically when changed);</li>
 *   <li>{@code snapshots.json} — inventories/positions of players inside a tournament (saved at once);</li>
 *   <li>{@code state.json} — escrow ledgers, weekly marker, country cooldowns, undelivered trophies (saved at once).</li>
 * </ul>
 */
public final class Storage {

    /** {@code state.json}. Plain fields for Gson. */
    public static final class State {
        public String weeklyKey;
        public Map<UUID, Long> countryCooldowns = new LinkedHashMap<>();
        public Map<String, EscrowLedger> escrows = new LinkedHashMap<>();
        public Map<UUID, List<TrophyRecord>> pendingTrophies = new LinkedHashMap<>();
    }

    /** A trophy not yet handed over (winner offline at the end). */
    public record TrophyRecord(String title, String date, String arena, String session) {
    }

    private final Path dir;
    private final JsonFiles files;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();
    private final Map<UUID, Snapshot> snapshots = new LinkedHashMap<>();
    private State state = new State();
    private boolean statsDirty;
    private boolean loaded;
    private boolean moneyBlocked;
    private final List<String> moneyOperations = new ArrayList<>();

    public Storage(final Path dir, final Logger logger) {
        this.dir = dir;
        this.files = new JsonFiles(logger);
    }

    public void load() {
        this.moneyBlocked = java.nio.file.Files.exists(this.dir.resolve("money-journal.json"));
        final List<Arena> a = this.files.read(this.dir.resolve("arenas.json"), new TypeToken<List<Arena>>() { }.getType());
        this.arenas.clear();
        if (a != null) {
            for (final Arena arena : a) {
                if (arena != null && arena.id != null && arena.box != null && arena.type != null) {
                    if (arena.spawns == null) {
                        arena.spawns = new ArrayList<>();
                    }
                    this.arenas.put(arena.id, arena);
                }
            }
        }
        final Map<UUID, PlayerStats> s = this.files.read(this.dir.resolve("stats.json"), new TypeToken<LinkedHashMap<UUID, PlayerStats>>() { }.getType());
        this.stats.clear();
        if (s != null) {
            s.forEach((k, v) -> {
                if (k != null && v != null) {
                    v.uuid = k;
                    this.stats.put(k, v);
                }
            });
        }
        final Map<UUID, Snapshot> sn = this.files.read(this.dir.resolve("snapshots.json"), new TypeToken<LinkedHashMap<UUID, Snapshot>>() { }.getType());
        this.snapshots.clear();
        if (sn != null) {
            sn.forEach((k, v) -> {
                if (k != null && v != null) {
                    this.snapshots.put(k, v);
                }
            });
        }
        final State st = this.files.read(this.dir.resolve("state.json"), State.class);
        this.state = st == null ? new State() : st;
        if (this.state.countryCooldowns == null) {
            this.state.countryCooldowns = new LinkedHashMap<>();
        }
        if (this.state.escrows == null) {
            this.state.escrows = new LinkedHashMap<>();
        }
        if (this.state.pendingTrophies == null) {
            this.state.pendingTrophies = new LinkedHashMap<>();
        }
        this.loaded = true;
    }

    // ---- arenas ---------------------------------------------------------------------------------

    public Map<String, Arena> arenas() {
        return this.arenas;
    }

    public void saveArenas() {
        this.files.writeAsync(this.dir.resolve("arenas.json"), new ArrayList<>(this.arenas.values()));
    }

    /** Bootstrap uses the same ordered I/O worker and authoritative arenas map. */
    public void saveArenasNow() {
        this.files.writeNow(this.dir.resolve("arenas.json"), new ArrayList<>(this.arenas.values()));
    }

    public pl.medivalrpg.turnieje.bootstrap.BootstrapPlan.Progress loadArenaBootstrap() {
        return this.files.read(this.dir.resolve("bootstrap-arena-state.json"), pl.medivalrpg.turnieje.bootstrap.BootstrapPlan.Progress.class);
    }

    public void saveArenaBootstrap(final pl.medivalrpg.turnieje.bootstrap.BootstrapPlan.Progress progress) {
        this.files.writeNow(this.dir.resolve("bootstrap-arena-state.json"), progress);
    }

    // ---- stats ----------------------------------------------------------------------------------

    public Map<UUID, PlayerStats> stats() {
        return this.stats;
    }

    public PlayerStats stats(final UUID uuid, final String name) {
        final PlayerStats s = this.stats.computeIfAbsent(uuid, u -> new PlayerStats(u, name));
        if (name != null) {
            s.name = name;
        }
        this.statsDirty = true;
        return s;
    }

    public void saveStatsIfDirty() {
        if (this.statsDirty) {
            this.statsDirty = false;
            this.files.writeAsync(this.dir.resolve("stats.json"), new LinkedHashMap<>(this.stats));
        }
    }

    // ---- snapshots ------------------------------------------------------------------------------

    public Snapshot snapshot(final UUID uuid) {
        return this.snapshots.get(uuid);
    }

    public boolean hasSnapshot(final UUID uuid) {
        return this.snapshots.containsKey(uuid);
    }

    public void putSnapshot(final UUID uuid, final Snapshot snapshot) {
        this.snapshots.put(uuid, snapshot);
        this.saveSnapshots();
    }

    public void removeSnapshot(final UUID uuid) {
        if (this.snapshots.remove(uuid) != null) {
            this.saveSnapshots();
        }
    }

    private void saveSnapshots() {
        this.files.writeNow(this.dir.resolve("snapshots.json"), new LinkedHashMap<>(this.snapshots));
    }

    // ---- state ----------------------------------------------------------------------------------

    public State state() {
        return this.state;
    }

    public void saveState() {
        this.files.writeNow(this.dir.resolve("state.json"), this.state);
        if (!this.moneyBlocked && !this.moneyOperations.isEmpty()) {
            try { java.nio.file.Files.deleteIfExists(this.dir.resolve("money-journal.json")); }
            catch (java.io.IOException e) { this.moneyBlocked = true; throw new java.io.UncheckedIOException(e); }
            this.moneyOperations.clear();
        }
    }

    public void blockMoney() { this.moneyBlocked = true; }

    public boolean moneyBlocked() { return this.moneyBlocked; }

    /** Durable intent before touching an external economy without idempotency keys. */
    public void beginMoney(final String operation) {
        if (this.moneyBlocked) throw new IllegalStateException("Rozliczenia zablokowane: money-journal.json");
        this.moneyOperations.add(operation);
        this.files.writeNow(this.dir.resolve("money-journal.json"), this.moneyOperations);
    }

    /** Flushes pending writes and writes everything synchronously (plugin disable). */
    public void shutdown() {
        if (!this.loaded) { this.files.shutdown(); return; }
        this.files.writeNow(this.dir.resolve("arenas.json"), new ArrayList<>(this.arenas.values()));
        this.files.writeNow(this.dir.resolve("stats.json"), new LinkedHashMap<>(this.stats));
        this.files.writeNow(this.dir.resolve("snapshots.json"), new LinkedHashMap<>(this.snapshots));
        this.files.writeNow(this.dir.resolve("state.json"), this.state);
        this.files.shutdown();
    }
}
