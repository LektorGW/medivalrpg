// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.ledger.WorldMoney;
import pl.medivalrpg.skarbiec.money.Denomination;
import pl.medivalrpg.skarbiec.shop.Shop;

/**
 * On-demand census of physical coins. Phase 1 (main thread, a few ms per tick): online players and every loaded
 * chunk (containers, items on the ground, frames, carts, animals). Phase 2 (background thread, throttled): region,
 * entity and player files for everything that was NOT loaded, read-only; unchanged files are taken from a cache.
 * The result is a hot census — coins can move while it runs — so the difference against the ledger's cash backing is
 * shown, never corrected automatically.
 */
public final class CoinCensus {

    private static final Set<Material> COINS = Set.of(Material.COPPER_NUGGET, Material.IRON_NUGGET, Material.GOLD_NUGGET);
    private static final int MAX_LOCATIONS = 2000;

    private final SkarbiecPlugin plugin;
    private final ExecutorService worker;
    private final Path dir;
    private final Path resultFile;
    private final Path cacheFile;

    private volatile String status = "NONE";
    private volatile double progress;
    private volatile String phase = "";
    private volatile long startedAt;
    private volatile Map<String, Object> last;
    private BukkitTask task;
    private Map<String, DiskCoinScanner.FileResult> cache;

    public CoinCensus(final SkarbiecPlugin plugin, final ExecutorService worker) {
        this.plugin = plugin;
        this.worker = worker;
        this.dir = plugin.getDataFolder().toPath().resolve("stats");
        this.resultFile = this.dir.resolve("census.json");
        this.cacheFile = this.dir.resolve("census-cache.json");
        this.loadLast();
    }

    private void loadLast() {
        if (!Files.exists(this.resultFile)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(this.resultFile, StandardCharsets.UTF_8)) {
            final Map<String, Object> m = JsonFilesBridge.toMap(JsonParser.parseReader(r));
            if (m != null) {
                this.last = m;
                this.status = String.valueOf(m.getOrDefault("status", "DONE"));
            }
        } catch (final IOException | RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie udało się wczytać ostatniego spisu monet (stats/census.json)", e);
        }
    }

    public boolean running() {
        return "RUNNING".equals(this.status);
    }

    /** Current state for {@code world.stats.coins} (main thread). */
    public Map<String, Object> snapshot() {
        final Map<String, Object> out = new LinkedHashMap<>();
        final Map<String, Object> l = this.last;
        if (l != null) {
            out.putAll(l);
        } else {
            out.put("status", "NONE");
            out.put("startedAt", null);
            out.put("finishedAt", null);
            out.put("total", 0L);
            out.put("stacks", 0L);
            out.put("byDenomination", FlowQuery.map("copper", 0, "silver", 0, "gold", 0));
            out.put("byKind", List.of());
            out.put("locations", List.of());
            out.put("truncated", false);
            out.put("errors", List.of());
            out.put("notes", List.of());
        }
        final long cash = this.plugin.ledger().balance(WorldMoney.CASH);
        out.put("cashAccount", cash);
        if (l != null && l.get("total") instanceof Number t) {
            out.put("difference", t.longValue() - cash);
            out.put("differenceAtCensus", l.get("differenceAtCensus"));
        } else {
            out.put("difference", null);
        }
        if (this.running()) {
            out.put("status", "RUNNING");
            out.put("progress", this.progress);
            out.put("phase", this.phase);
            out.put("runningSince", this.startedAt);
        }
        return out;
    }

    // ================================================================ start

    /** Starts a census (main thread). Returns false when one is already running. */
    public boolean start() {
        if (this.running()) {
            return false;
        }
        this.status = "RUNNING";
        this.progress = 0;
        this.phase = "Gracze online";
        this.startedAt = System.currentTimeMillis();
        final Run run = new Run();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            run.online.add(p.getUniqueId());
            final String id = p.getUniqueId().toString();
            final var loc = p.getLocation();
            final DiskCoinScanner.Ctx inv = new DiskCoinScanner.Ctx("PLAYER_ONLINE", "Ekwipunek gracza", id, loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            this.items(p.getInventory().getContents(), inv, run.tally, 0);
            this.item(p.getItemOnCursor(), inv, run.tally, 0);
            if (p.getOpenInventory().getTopInventory().getHolder() == p) {
                this.items(p.getOpenInventory().getTopInventory().getContents(), inv, run.tally, 0); // 2x2 crafting grid
            }
            this.items(p.getEnderChest().getContents(), new DiskCoinScanner.Ctx("ENDER_ONLINE", "Ender chest", id, loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), run.tally, 0);
        }
        for (final World w : Bukkit.getWorlds()) {
            for (final Chunk c : w.getLoadedChunks()) {
                run.chunks.add(new ChunkRef(w.getName(), c.getX(), c.getZ()));
            }
        }
        run.liveTotal = run.chunks.size();
        this.phase = "Załadowane chunki (0/" + run.liveTotal + ")";
        final double budgetMs = Math.max(0.5, this.plugin.getConfig().getDouble("stats.census.live-ms-per-tick", 2.0));
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> this.liveStep(run, budgetMs), 1L, 1L);
        return true;
    }

    private record ChunkRef(String world, int x, int z) {
        String key() {
            return key(this.world, this.x, this.z);
        }

        static String key(final String world, final int x, final int z) {
            return world + '|' + x + '|' + z;
        }
    }

    private static final class Run {
        final CoinTally tally = new CoinTally();
        final Set<UUID> online = new HashSet<>();
        final Deque<ChunkRef> chunks = new ArrayDeque<>();
        final Set<String> blockSkip = new HashSet<>();
        final Set<String> entitySkip = new HashSet<>();
        final List<Map<String, Object>> errors = new ArrayList<>();
        int liveTotal;
        int liveDone;
        int filesScanned;
        int filesTotal;
        int filesCached;
        int playersOffline;
    }

    private void liveStep(final Run run, final double budgetMs) {
        final long deadline = System.nanoTime() + (long) (budgetMs * 1_000_000);
        try {
            while (!run.chunks.isEmpty() && System.nanoTime() < deadline) {
                final ChunkRef ref = run.chunks.poll();
                run.liveDone++;
                final World w = Bukkit.getWorld(ref.world());
                if (w == null || !w.isChunkLoaded(ref.x(), ref.z())) {
                    continue; // unloaded meanwhile: the saved file is read in phase 2
                }
                final Chunk chunk = w.getChunkAt(ref.x(), ref.z());
                for (final BlockState state : chunk.getTileEntities(false)) {
                    this.blockState(state, w.getName(), run.tally);
                }
                run.blockSkip.add(ref.key());
                if (chunk.isEntitiesLoaded()) {
                    for (final Entity e : chunk.getEntities()) {
                        this.entity(e, w.getName(), run.tally);
                    }
                    run.entitySkip.add(ref.key());
                }
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Spis monet: błąd skanowania chunka", e);
            run.errors.add(FlowQuery.map("file", "(chunk w pamięci)", "error", String.valueOf(e)));
        }
        this.progress = run.liveTotal == 0 ? 0.2 : 0.2 * run.liveDone / run.liveTotal;
        this.phase = "Załadowane chunki (" + run.liveDone + "/" + run.liveTotal + ")";
        if (run.chunks.isEmpty()) {
            this.task.cancel();
            this.task = null;
            final Map<String, String> worldsByDimension = new HashMap<>();
            final List<Map.Entry<String, Path>> worlds = new ArrayList<>();
            for (final World w : Bukkit.getWorlds()) {
                worldsByDimension.put(w.getKey().toString(), w.getName());
                worlds.add(Map.entry(w.getName(), dimensionDir(w)));
            }
            final Path players = playersDir();
            final int dutyPercent = Math.max(5, Math.min(100, this.plugin.getConfig().getInt("stats.census.disk-duty-percent", 40)));
            this.phase = "Pliki świata";
            this.worker.execute(() -> this.diskPhase(run, worlds, worldsByDimension, players, dutyPercent));
        }
    }

    private static Path dimensionDir(final World w) {
        final Path level = Bukkit.getServer().getLevelDirectory();
        final Path byKey = level.resolve("dimensions").resolve(w.getKey().getNamespace()).resolve(w.getKey().getKey());
        if (Files.isDirectory(byKey)) {
            return byKey;
        }
        return w.getWorldPath();
    }

    private static Path playersDir() {
        final Path level = Bukkit.getServer().getLevelDirectory();
        final Path modern = level.resolve("players").resolve("data");
        return Files.isDirectory(modern) ? modern : level.resolve("playerdata");
    }

    // ================================================================ live scanning (main thread)

    private void blockState(final BlockState state, final String world, final CoinTally tally) {
        final org.bukkit.inventory.Inventory inv;
        if (state instanceof Chest chest) {
            inv = chest.getBlockInventory();
        } else if (state instanceof InventoryHolder holder) {
            inv = holder.getInventory();
        } else {
            return;
        }
        final ItemStack[] contents = inv.getContents();
        if (!this.anyCoinOrContainer(contents)) {
            return;
        }
        String label = CoinTally.holderLabel(state.getType().getKey().toString());
        String owner = null;
        final Shop shop = this.plugin.shops() == null ? null : this.plugin.shops().atContainer(state.getBlock());
        if (shop != null) {
            label = "Sklep gracza — " + label;
            owner = shop.owner().toString();
        }
        this.items(contents, new DiskCoinScanner.Ctx("CONTAINER", label, owner, world, state.getX(), state.getY(), state.getZ()), tally, 0);
    }

    private void entity(final Entity e, final String world, final CoinTally tally) {
        if (e instanceof Player) {
            return;
        }
        final var l = e.getLocation();
        final String label = CoinTally.holderLabel(e.getType().getKey().toString());
        final DiskCoinScanner.Ctx ctx = new DiskCoinScanner.Ctx(e instanceof Item ? "GROUND" : "ENTITY", label, null, world,
            l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (e instanceof Item item) {
            this.item(item.getItemStack(), ctx, tally, 0);
            return;
        }
        if (e instanceof ItemFrame frame) {
            this.item(frame.getItem(), ctx, tally, 0);
        }
        if (e instanceof ItemDisplay display) {
            this.item(display.getItemStack(), ctx, tally, 0);
        }
        if (e instanceof InventoryHolder holder) {
            this.items(holder.getInventory().getContents(), ctx, tally, 0);
        }
        if (e instanceof LivingEntity living) {
            final EntityEquipment eq = living.getEquipment();
            if (eq != null) {
                this.items(eq.getArmorContents(), ctx, tally, 0);
                this.item(eq.getItemInMainHand(), ctx, tally, 0);
                this.item(eq.getItemInOffHand(), ctx, tally, 0);
            }
        }
    }

    private boolean anyCoinOrContainer(final ItemStack[] items) {
        for (final ItemStack i : items) {
            if (i != null && (COINS.contains(i.getType()) || nested(i.getType()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean nested(final Material m) {
        final String n = m.name();
        return n.endsWith("SHULKER_BOX") || n.endsWith("BUNDLE");
    }

    private void items(final ItemStack[] items, final DiskCoinScanner.Ctx ctx, final CoinTally tally, final int depth) {
        if (items == null) {
            return;
        }
        for (final ItemStack i : items) {
            this.item(i, ctx, tally, depth);
        }
    }

    private void item(final ItemStack item, final DiskCoinScanner.Ctx ctx, final CoinTally tally, final int depth) {
        if (item == null || item.isEmpty() || depth > 8) {
            return;
        }
        final Material type = item.getType();
        if (COINS.contains(type)) {
            final Denomination d = this.plugin.coins().denomination(item);
            if (d != null) {
                tally.add(new CoinTally.Found(ctx.kind(), ctx.label(), ctx.ownerId(), ctx.world(), ctx.x(), ctx.y(), ctx.z(), "LIVE",
                    d.ordinal(), item.getAmount()));
            }
            return;
        }
        if (!nested(type) || !item.hasItemMeta()) {
            return;
        }
        final var meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof InventoryHolder holder) {
            this.items(holder.getInventory().getContents(), ctx, tally, depth + 1);
        } else if (meta instanceof BundleMeta bundle) {
            for (final ItemStack nested : bundle.getItems()) {
                this.item(nested, ctx, tally, depth + 1);
            }
        }
    }

    // ================================================================ disk phase (worker thread)

    private void diskPhase(final Run run, final List<Map.Entry<String, Path>> worlds, final Map<String, String> worldsByDimension,
                           final Path players, final int dutyPercent) {
        try {
            if (this.cache == null) {
                this.cache = this.loadCache();
            }
            final List<Object[]> files = new ArrayList<>(); // world, path, entities?
            for (final Map.Entry<String, Path> w : worlds) {
                for (final String sub : List.of("region", "entities")) {
                    final Path d = w.getValue().resolve(sub);
                    if (!Files.isDirectory(d)) {
                        continue;
                    }
                    try (Stream<Path> s = Files.list(d)) {
                        s.filter(p -> DiskCoinScanner.regionOf(p) != null).sorted().forEach(p -> files.add(new Object[] {w.getKey(), p, sub.equals("entities")}));
                    }
                }
            }
            final List<Path> playerFiles = new ArrayList<>();
            if (Files.isDirectory(players)) {
                try (Stream<Path> s = Files.list(players)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".dat")).sorted().forEach(playerFiles::add);
                }
            }
            run.filesTotal = files.size() + playerFiles.size();
            final Map<String, DiskCoinScanner.FileResult> fresh = new HashMap<>();
            int done = 0;
            for (final Object[] f : files) {
                final String world = (String) f[0];
                final Path path = (Path) f[1];
                final boolean entities = (Boolean) f[2];
                final long t0 = System.nanoTime();
                final String key = world + "|" + (entities ? "entities" : "region") + "|" + path.getFileName();
                try {
                    DiskCoinScanner.FileResult r = this.cache.get(key);
                    final long size = Files.size(path);
                    final long mtime = Files.getLastModifiedTime(path).toMillis();
                    if (r == null || r.size() != size || r.mtime() != mtime) {
                        r = DiskCoinScanner.scanRegion(path, world);
                        if (Files.size(path) != r.size() || Files.getLastModifiedTime(path).toMillis() != r.mtime()) {
                            Thread.sleep(200); // written while read: try once more
                            r = DiskCoinScanner.scanRegion(path, world);
                        }
                        run.filesScanned++;
                    } else {
                        run.filesCached++;
                    }
                    fresh.put(key, r);
                    for (final DiskCoinScanner.ChunkCoins c : r.chunks()) {
                        final String ck = ChunkRef.key(world, c.cx(), c.cz());
                        if (!(entities ? run.entitySkip : run.blockSkip).contains(ck)) {
                            run.tally.addAll(c.found());
                        }
                    }
                    for (final String err : r.errors()) {
                        if (run.errors.size() < 200) {
                            run.errors.add(FlowQuery.map("file", rel(path), "error", err));
                        }
                    }
                } catch (final IOException | RuntimeException e) {
                    if (run.errors.size() < 200) {
                        run.errors.add(FlowQuery.map("file", rel(path), "error", String.valueOf(e.getMessage())));
                    }
                }
                done++;
                this.progress = 0.2 + 0.78 * done / Math.max(1, run.filesTotal);
                this.phase = "Pliki świata (" + done + "/" + run.filesTotal + ")";
                throttle(t0, dutyPercent);
            }
            for (final Path p : playerFiles) {
                final long t0 = System.nanoTime();
                final String name = p.getFileName().toString();
                try {
                    final UUID id = UUID.fromString(name.substring(0, name.length() - 4));
                    if (!run.online.contains(id)) {
                        run.playersOffline++;
                        run.tally.addAll(DiskCoinScanner.scanPlayer(p, dim -> worldsByDimension.getOrDefault(dim, dim)));
                        run.filesScanned++;
                    }
                } catch (final IOException | RuntimeException e) {
                    if (run.errors.size() < 200) {
                        run.errors.add(FlowQuery.map("file", rel(p), "error", String.valueOf(e.getMessage())));
                    }
                }
                done++;
                this.progress = 0.2 + 0.78 * done / Math.max(1, run.filesTotal);
                this.phase = "Gracze offline (" + done + "/" + run.filesTotal + ")";
                throttle(t0, dutyPercent);
            }
            this.cache = fresh;
            this.saveCache(fresh);
            Bukkit.getScheduler().runTask(this.plugin, () -> this.finish(run, null));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.runOnMain(() -> this.finish(run, "Spis przerwany (wyłączanie serwera)."));
        } catch (final IOException | RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Spis monet nie powiódł się", e);
            this.runOnMain(() -> this.finish(run, "Spis nie powiódł się: " + e.getMessage()));
        }
    }

    private void runOnMain(final Runnable r) {
        try {
            Bukkit.getScheduler().runTask(this.plugin, r);
        } catch (final RuntimeException e) {
            this.status = "FAILED";
        }
    }

    private static void throttle(final long t0, final int dutyPercent) throws InterruptedException {
        if (dutyPercent >= 100) {
            return;
        }
        final long worked = System.nanoTime() - t0;
        final long sleepNs = worked * (100 - dutyPercent) / dutyPercent;
        if (sleepNs > 0) {
            Thread.sleep(Math.min(5_000, sleepNs / 1_000_000), (int) (sleepNs % 1_000_000));
        }
    }

    private static String rel(final Path p) {
        final Path level = Bukkit.getServer().getLevelDirectory();
        try {
            return level.getParent() == null ? p.toString() : level.getParent().relativize(p).toString().replace('\\', '/');
        } catch (final IllegalArgumentException e) {
            return p.toString();
        }
    }

    // ================================================================ finish (main thread)

    private void finish(final Run run, final String failure) {
        final long now = System.currentTimeMillis();
        final Map<String, String> names = new HashMap<>();
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", failure == null ? "DONE" : "FAILED");
        result.put("startedAt", this.startedAt);
        result.put("finishedAt", now);
        result.put("durationMs", now - this.startedAt);
        result.put("progress", 1.0);
        result.put("phase", failure == null ? "Gotowe" : failure);
        result.putAll(run.tally.toMap(MAX_LOCATIONS, id -> names.computeIfAbsent(id, k -> {
            try {
                return SkarbiecPlugin.nameOf(UUID.fromString(k));
            } catch (final IllegalArgumentException e) {
                return k;
            }
        })));
        final long cash = this.plugin.ledger().balance(WorldMoney.CASH);
        result.put("cashAtCensus", cash);
        result.put("differenceAtCensus", run.tally.total() - cash);
        result.put("filesScanned", run.filesScanned);
        result.put("filesCached", run.filesCached);
        result.put("filesTotal", run.filesTotal);
        result.put("chunksLive", run.liveTotal);
        result.put("playersOnline", run.online.size());
        result.put("playersOffline", run.playersOffline);
        result.put("errors", run.errors);
        final List<String> notes = new ArrayList<>();
        notes.add("Spis na gorąco: serwer działał, monety mogły się przemieszczać w trakcie. Różnica względem pokrycia nie jest korygowana automatycznie.");
        notes.add("Załadowane chunki i gracze online liczeni z pamięci serwera (LIVE), reszta z plików świata i graczy (DISK, ostatni zapis).");
        notes.add("Pliki niezmienione od poprzedniego spisu brane z pamięci podręcznej spisu (" + run.filesCached + ").");
        notes.add("Nie są liczone przedmioty przechowywane wyłącznie w danych innych pluginów (np. magazyny w ich własnych plikach).");
        result.put("notes", notes);
        this.last = result;
        this.status = (String) result.get("status");
        this.progress = 1;
        this.phase = (String) result.get("phase");
        try {
            this.plugin.files().writeAsync(this.resultFile, JsonFilesBridge.toJson(result));
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Nie udało się zapisać wyniku spisu monet", e);
        }
        this.plugin.getLogger().info("Spis monet: " + run.tally.total() + " md w " + run.tally.places() + " miejscach (pokrycie " + cash
            + " md, różnica " + (run.tally.total() - cash) + " md), " + (now - this.startedAt) / 1000 + " s.");
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
            this.status = this.last == null ? "NONE" : String.valueOf(this.last.getOrDefault("status", "DONE"));
        }
    }

    // ================================================================ cache

    private Map<String, DiskCoinScanner.FileResult> loadCache() {
        final Map<String, DiskCoinScanner.FileResult> out = new HashMap<>();
        if (!Files.exists(this.cacheFile)) {
            return out;
        }
        try (Reader r = Files.newBufferedReader(this.cacheFile, StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.get("version") == null || root.get("version").getAsInt() != 1) {
                return out;
            }
            for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("files").entrySet()) {
                final JsonObject f = e.getValue().getAsJsonObject();
                final List<DiskCoinScanner.ChunkCoins> chunks = new ArrayList<>();
                for (final JsonElement c : f.getAsJsonArray("chunks")) {
                    final JsonObject co = c.getAsJsonObject();
                    final List<CoinTally.Found> found = JsonFilesBridge.GSON.fromJson(co.get("found"), new TypeToken<List<CoinTally.Found>>() { }.getType());
                    chunks.add(new DiskCoinScanner.ChunkCoins(co.get("cx").getAsInt(), co.get("cz").getAsInt(), found));
                }
                // Files with errors are never cached as clean results: they are rescanned next time.
                out.put(e.getKey(), new DiskCoinScanner.FileResult(f.get("size").getAsLong(), f.get("mtime").getAsLong(), chunks, List.of()));
            }
        } catch (final IOException | RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Pamięć podręczna spisu monet jest nieczytelna — pełny spis.", e);
            out.clear();
        }
        return out;
    }

    private void saveCache(final Map<String, DiskCoinScanner.FileResult> files) {
        final JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        final JsonObject fo = new JsonObject();
        files.forEach((key, r) -> {
            if (!r.errors().isEmpty()) {
                return;
            }
            final JsonObject f = new JsonObject();
            f.addProperty("size", r.size());
            f.addProperty("mtime", r.mtime());
            final JsonArray chunks = new JsonArray();
            for (final DiskCoinScanner.ChunkCoins c : r.chunks()) {
                final JsonObject co = new JsonObject();
                co.addProperty("cx", c.cx());
                co.addProperty("cz", c.cz());
                co.add("found", JsonFilesBridge.GSON.toJsonTree(c.found()));
                chunks.add(co);
            }
            f.add("chunks", chunks);
            fo.add(key, f);
        });
        root.add("files", fo);
        this.plugin.files().writeAsync(this.cacheFile, root);
    }

    static String lower(final String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
