// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.map;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.plan.ChunkGeometry;
import pl.medivalrpg.panel.rpc.RpcException;

/**
 * {@code admin.npc.mapRender}: top-down PNG of an NPC country's land plus a margin. Chunk snapshots are taken on the
 * main thread in small batches (loaded chunks first; unloaded but generated chunks are loaded asynchronously with
 * a concurrency and total limit, never generated), reduced to colour/height columns on a background thread right
 * away (so snapshots do not pile up), and the image is composed and PNG-encoded off the main thread. When the job
 * takes longer than the RPC allows, a partial image is returned ({@code complete:false}) while the job finishes in the
 * background; the finished result is cached per country for {@code cache-seconds}.
 */
public final class MapRenderService {

    private static final Set<Material> WATERS = EnumSet.of(Material.WATER, Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT,
        Material.SEAGRASS, Material.TALL_SEAGRASS);
    private static final int MAX_JOBS = 3;
    private static final long FRESH_MIN_AGE_MS = 5_000;

    /** Settings from config.yml {@code map-render.*}. */
    public record Settings(int maxSize, int maxChunks, int maxAsyncLoads, int parallelLoads, int snapshotsPerTick, long waitMs,
                           long cacheMs, long jobTimeoutMs, int maxPngBase64) {
    }

    /** Main-thread data of a request: what to render and the overlay data (buildings, neighbours, …) sent with it. */
    public record Request(UUID country, String world, int margin, Set<Long> render, Map<String, Object> extra) {
    }

    private final PanelPlugin plugin;
    private final ExecutorService reducer;
    private final ExecutorService encoder;
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    /** Colour cache, reducer thread only. */
    private final Map<BlockData, Integer> colors = new HashMap<>();

    private record Cached(Map<String, Object> result, int margin, long at) {
    }

    public MapRenderService(final PanelPlugin plugin) {
        this.plugin = plugin;
        this.reducer = Executors.newSingleThreadExecutor(daemon("Panel-MapReduce"));
        this.encoder = Executors.newSingleThreadExecutor(daemon("Panel-MapEncode"));
    }

    private static java.util.concurrent.ThreadFactory daemon(final String name) {
        return r -> {
            final Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
    }

    public void shutdown() {
        for (final Job j : new ArrayList<>(this.jobs.values())) {
            j.stop();
        }
        this.jobs.clear();
        this.reducer.shutdownNow();
        this.encoder.shutdownNow();
    }

    /** Forgets the cached image of a country (after its land changed). */
    public void invalidate(final UUID country) {
        this.cache.remove(country);
    }

    /**
     * Main thread. Answers from the cache, joins a running job, or starts one. The future completes off the main
     * thread with the RPC data.
     */
    public CompletableFuture<Map<String, Object>> render(final Request req, final boolean fresh, final Settings s) {
        final long now = System.currentTimeMillis();
        final Cached c = this.cache.get(req.country());
        if (c != null && c.margin() == req.margin() && now - c.at() < s.cacheMs() && (!fresh || now - c.at() < FRESH_MIN_AGE_MS)) {
            final Map<String, Object> out = new HashMap<>(c.result());
            out.putAll(req.extra()); // overlay data is cheap and always current
            out.put("cached", true);
            return CompletableFuture.completedFuture(out);
        }
        Job job = this.jobs.get(req.country());
        if (job != null && job.req.margin() != req.margin()) {
            job.stop();
            this.jobs.remove(req.country());
            job = null;
        }
        if (job == null) {
            if (this.jobs.size() >= MAX_JOBS) {
                return CompletableFuture.failedFuture(new RpcException("Trwa renderowanie innych map — spróbuj za kilka sekund."));
            }
            final World world = Bukkit.getWorld(req.world());
            if (world == null) {
                return CompletableFuture.failedFuture(new RpcException("Świat kraju (" + req.world() + ") nie jest wczytany."));
            }
            job = new Job(req, world, s);
            this.jobs.put(req.country(), job);
            job.start();
        }
        final Job j = job;
        final CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        j.finished.whenComplete((v, e) -> {
            if (!answer.isDone()) {
                this.encode(j, req.extra(), answer);
            }
        });
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (!answer.isDone()) {
                this.encode(j, req.extra(), answer);
            }
        }, Math.max(1, s.waitMs() / 50));
        return answer;
    }

    private void encode(final Job j, final Map<String, Object> extra, final CompletableFuture<Map<String, Object>> answer) {
        try {
            this.encoder.execute(() -> {
                try {
                    final Map<String, Object> out = j.image();
                    out.putAll(extra);
                    answer.complete(out);
                } catch (final RuntimeException | OutOfMemoryError e) {
                    this.plugin.getLogger().log(Level.WARNING, "Panel: błąd renderowania mapy", e);
                    answer.completeExceptionally(new RpcException("Nie udało się wyrenderować mapy."));
                }
            });
        } catch (final RejectedExecutionException e) {
            answer.completeExceptionally(new RpcException("Serwer gry się wyłącza."));
        }
    }

    // ------------------------------------------------------------------ job

    private final class Job {
        final Request req;
        final World world;
        final Settings s;
        final int minY;
        final int maxY;
        final int minCX;
        final int minCZ;
        final int maxCX;
        final int maxCZ;
        final Map<Long, MapImage.Column> columns = new ConcurrentHashMap<>();
        final AtomicInteger processed = new AtomicInteger();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        final ArrayDeque<Long> pending = new ArrayDeque<>();
        final ArrayDeque<Long> unloaded = new ArrayDeque<>();
        final long started = System.currentTimeMillis();
        int inFlight;
        int asyncStarted;
        BukkitTask task;
        volatile boolean stopped;

        Job(final Request req, final World world, final Settings s) {
            this.req = req;
            this.world = world;
            this.s = s;
            this.minY = world.getMinHeight();
            this.maxY = world.getMaxHeight();
            int a = Integer.MAX_VALUE;
            int b = Integer.MAX_VALUE;
            int c = Integer.MIN_VALUE;
            int d = Integer.MIN_VALUE;
            for (final long k : req.render()) {
                a = Math.min(a, ChunkGeometry.cx(k));
                b = Math.min(b, ChunkGeometry.cz(k));
                c = Math.max(c, ChunkGeometry.cx(k));
                d = Math.max(d, ChunkGeometry.cz(k));
            }
            this.minCX = a;
            this.minCZ = b;
            this.maxCX = c;
            this.maxCZ = d;
            // nearest to the centre first, so a partial image shows the heart of the country
            final double mx = (a + c) / 2.0;
            final double mz = (b + d) / 2.0;
            final List<Long> order = new ArrayList<>(req.render());
            order.sort((p, q) -> Double.compare(Math.hypot(ChunkGeometry.cx(p) - mx, ChunkGeometry.cz(p) - mz),
                Math.hypot(ChunkGeometry.cx(q) - mx, ChunkGeometry.cz(q) - mz)));
            this.pending.addAll(order);
        }

        void start() {
            this.task = Bukkit.getScheduler().runTaskTimer(MapRenderService.this.plugin, this::tick, 1L, 1L);
        }

        void stop() {
            this.stopped = true;
            if (this.task != null) {
                this.task.cancel();
            }
            this.finished.complete(null);
        }

        int total() {
            return this.req.render().size();
        }

        private void done() {
            if (this.processed.incrementAndGet() >= this.total()) {
                this.finish();
            }
        }

        private void finish() {
            if (this.task != null) {
                this.task.cancel();
            }
            this.finished.complete(null);
        }

        /** Main thread, every tick. */
        void tick() {
            if (this.stopped) {
                return;
            }
            if (System.currentTimeMillis() - this.started > this.s.jobTimeoutMs()) {
                // give up on the rest: they stay transparent
                final int left = this.pending.size() + this.unloaded.size();
                this.pending.clear();
                this.unloaded.clear();
                for (int i = 0; i < left + this.inFlight; i++) {
                    this.done();
                }
                this.inFlight = 0;
                this.finish();
                return;
            }
            int budget = this.s.snapshotsPerTick();
            while (budget > 0 && !this.pending.isEmpty()) {
                final long k = this.pending.poll();
                final int cx = ChunkGeometry.cx(k);
                final int cz = ChunkGeometry.cz(k);
                if (this.world.isChunkLoaded(cx, cz)) {
                    this.snapshot(k, this.world.getChunkAt(cx, cz).getChunkSnapshot(true, false, false));
                    budget--;
                } else {
                    this.unloaded.add(k);
                }
            }
            while (this.inFlight < this.s.parallelLoads() && !this.unloaded.isEmpty()) {
                final long k = this.unloaded.poll();
                final int cx = ChunkGeometry.cx(k);
                final int cz = ChunkGeometry.cz(k);
                if (this.asyncStarted >= this.s.maxAsyncLoads() || !this.world.isChunkGenerated(cx, cz)) {
                    this.done();
                    continue;
                }
                this.asyncStarted++;
                this.inFlight++;
                this.world.getChunkAtAsync(cx, cz, false).whenComplete((chunk, err) -> Bukkit.getScheduler().runTask(MapRenderService.this.plugin, () -> {
                    this.inFlight--;
                    if (this.stopped) {
                        return;
                    }
                    if (chunk == null || err != null) {
                        this.done();
                    } else {
                        this.snapshot(k, chunk.getChunkSnapshot(true, false, false));
                    }
                }));
            }
            if (this.pending.isEmpty() && this.unloaded.isEmpty() && this.inFlight == 0 && this.task != null) {
                this.task.cancel(); // the reducer completes the job when the last column is in
            }
        }

        private void snapshot(final long key, final ChunkSnapshot snap) {
            try {
                MapRenderService.this.reducer.execute(() -> {
                    try {
                        if (!this.stopped) {
                            this.columns.put(key, MapImage.reduce(new SnapshotSource(snap), this.minY, this.maxY));
                        }
                    } catch (final RuntimeException e) {
                        MapRenderService.this.plugin.getLogger().log(Level.FINE, "Panel: chunk mapy pominięty", e);
                    } finally {
                        this.done();
                    }
                });
            } catch (final RejectedExecutionException e) {
                this.done();
            }
        }

        /** Encoder thread: composes, scales and encodes what is there now. */
        Map<String, Object> image() {
            final int originX = this.minCX << 4;
            final int originZ = this.minCZ << 4;
            final int w = (this.maxCX - this.minCX + 1) << 4;
            final int h = (this.maxCZ - this.minCZ + 1) << 4;
            final Map<Long, MapImage.Column> snap = new HashMap<>(this.columns);
            MapImage.Raster raster = MapImage.composeBounded(snap, originX, originZ, w, h, this.s.maxSize(), 1);
            String b64;
            for (;;) {
                b64 = Base64.getEncoder().encodeToString(MapImage.png(raster.pixels(), raster.width(), raster.height()));
                if (b64.length() <= this.s.maxPngBase64() || raster.width() == 1 && raster.height() == 1) {
                    break;
                }
                raster = MapImage.composeBounded(snap, originX, originZ, w, h, this.s.maxSize(), Math.multiplyExact(raster.scale(), 2));
            }
            final boolean complete = this.finished.isDone() && snap.size() >= this.columns.size() && this.processed.get() >= this.total();
            final Map<String, Object> out = new HashMap<>();
            out.put("id", this.req.country().toString());
            out.put("world", this.req.world());
            out.put("png", b64);
            out.put("originX", originX);
            out.put("originZ", originZ);
            out.put("width", raster.width());
            out.put("height", raster.height());
            out.put("scale", raster.scale());
            out.put("margin", this.req.margin());
            out.put("complete", complete);
            out.put("loadedChunks", snap.size());
            out.put("totalChunks", this.total());
            out.put("renderedAt", System.currentTimeMillis());
            out.put("cached", false);
            if (complete) {
                MapRenderService.this.cache.put(this.req.country(), new Cached(Collections.unmodifiableMap(new HashMap<>(out)), this.req.margin(), System.currentTimeMillis()));
                Bukkit.getScheduler().runTask(MapRenderService.this.plugin, () -> {
                    if (MapRenderService.this.jobs.get(this.req.country()) == this) {
                        MapRenderService.this.jobs.remove(this.req.country());
                    }
                });
            } else if (this.finished.isDone()) {
                // finished with gaps (timeout / limits): cache anyway, the gaps would not fill in on a retry soon
                MapRenderService.this.cache.put(this.req.country(), new Cached(Collections.unmodifiableMap(new HashMap<>(out)), this.req.margin(), System.currentTimeMillis()));
                Bukkit.getScheduler().runTask(MapRenderService.this.plugin, () -> MapRenderService.this.jobs.remove(this.req.country(), this));
            } else {
                // partial answer: the job keeps going and caches the full image when done
                this.finished.whenComplete((v, e) -> {
                    try {
                        MapRenderService.this.encoder.execute(this::image);
                    } catch (final RejectedExecutionException ignored) {
                        // shutting down
                    }
                });
            }
            return out;
        }
    }

    /** {@link MapImage.ColumnSource} over a chunk snapshot (reducer thread). */
    private final class SnapshotSource implements MapImage.ColumnSource {
        private final ChunkSnapshot snap;

        SnapshotSource(final ChunkSnapshot snap) {
            this.snap = snap;
        }

        @Override
        public int top(final int x, final int z) {
            return this.snap.getHighestBlockYAt(x, z);
        }

        @Override
        public int color(final int x, final int y, final int z) {
            final Material m = this.snap.getBlockType(x, y, z);
            if (m.isAir()) {
                return -1;
            }
            final BlockData bd = this.snap.getBlockData(x, y, z);
            Integer c = MapRenderService.this.colors.get(bd);
            if (c == null) {
                int rgb;
                try {
                    rgb = bd.getMapColor().asRGB();
                } catch (final RuntimeException e) {
                    rgb = 0;
                }
                c = rgb == 0 ? -1 : rgb; // MapColor.NONE (glass, barriers, …) is transparent on maps
                if (MapRenderService.this.colors.size() < 20_000) {
                    MapRenderService.this.colors.put(bd, c);
                }
            }
            return c;
        }

        @Override
        public boolean water(final int x, final int y, final int z) {
            return WATERS.contains(this.snap.getBlockType(x, y, z));
        }
    }

    /** Chunk set to render: the land dilated by the margin, shrunk (smaller margin, then nearest to the capital) to the limit. */
    public static Set<Long> renderSet(final Set<Long> land, final long capital, final int margin, final int maxChunks, final int[] usedMargin) {
        int m = Math.max(0, margin);
        for (;;) {
            final Set<Long> set = ChunkGeometry.dilate(land, (m + 15) >> 4);
            if (set.size() <= maxChunks || m == 0) {
                if (set.size() <= maxChunks) {
                    usedMargin[0] = m;
                    return set;
                }
                break;
            }
            m = m >= 16 ? m / 2 : 0;
        }
        usedMargin[0] = 0;
        final List<Long> l = new ArrayList<>(land);
        final int cx = ChunkGeometry.cx(capital);
        final int cz = ChunkGeometry.cz(capital);
        l.sort((p, q) -> Long.compare(dist2(p, cx, cz), dist2(q, cx, cz)));
        return new HashSet<>(l.subList(0, Math.min(maxChunks, l.size())));
    }

    private static long dist2(final long k, final int cx, final int cz) {
        final long dx = ChunkGeometry.cx(k) - cx;
        final long dz = ChunkGeometry.cz(k) - cz;
        return dx * dx + dz * dz;
    }
}
