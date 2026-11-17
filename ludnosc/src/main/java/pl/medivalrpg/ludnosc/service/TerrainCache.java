// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.planner.TerrainView;

/**
 * Surface heights of country land for the settlement planner. Chunks are snapshotted on the main thread within a
 * small per-tick budget ({@code ChunkSnapshot}, no block access later) and analysed asynchronously; unloaded chunks
 * of planning countries are loaded asynchronously, a few at a time. Nothing scans the world every tick.
 */
public final class TerrainCache {

    private static final long MAX_AGE = 15 * 60_000L;
    private static final int MAX_IN_FLIGHT = 2;
    private static final long TICK_BUDGET_NANOS = 2_000_000L;

    /** Surface of one chunk: top ground y per column and flags (1 = liquid, 2 = obstacle, 4 = rock, 8 = tree). */
    record Surface(int[] top, byte[] flags, long time) {
    }

    private final LudnoscPlugin plugin;
    private final Map<ChunkRef, Surface> surfaces = new ConcurrentHashMap<>();
    private final Map<ChunkRef, Long> generations = new HashMap<>();
    private final ArrayDeque<ChunkRef> wanted = new ArrayDeque<>();
    private final Set<ChunkRef> queued = new HashSet<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    public TerrainCache(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean fresh(final ChunkRef c) {
        final Surface s = this.surfaces.get(c);
        return s != null && s.time() > 0 && System.currentTimeMillis() - s.time() < MAX_AGE;
    }

    /** Asks for a chunk to be scanned (main thread). */
    public void request(final ChunkRef c) {
        if (!this.fresh(c) && this.queued.add(c)) {
            this.wanted.addLast(c);
        }
    }

    /** The terrain of a box changed (levelling, demolition): its chunks are scanned again. Main thread. */
    public void refresh(final String world, final pl.medivalrpg.ludnosc.model.BlockBox box) {
        if (box == null) {
            return;
        }
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                final ChunkRef c = new ChunkRef(world, cx, cz);
                this.generations.merge(c, 1L, Long::sum);
                this.surfaces.computeIfPresent(c, (k, v) -> new Surface(v.top(), v.flags(), 0)); // invalid until rescanned
                this.request(c);
            }
        }
    }

    /** Requests every claimed chunk of a country within {@code radius} chunks of (cx, cz). */
    public void requestAround(final UUID country, final String world, final int cx, final int cz, final int radius) {
        for (final long key : this.plugin.kraje().getClaims(country, world)) {
            final int x = (int) (key >> 32);
            final int z = (int) key;
            if (Math.abs(x - cx) <= radius && Math.abs(z - cz) <= radius) {
                this.request(new ChunkRef(world, x, z));
            }
        }
    }

    /** A chunk loaded: refresh it if it is country land and the data is old (cheap). */
    public void onChunkLoad(final Chunk chunk) {
        final ChunkRef c = new ChunkRef(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (!this.fresh(c) && !this.queued.contains(c)
            && this.plugin.kraje().getCountryAt(c.world(), c.cx(), c.cz()).isPresent()) {
            this.request(c);
        }
    }

    /** Main thread, every tick: snapshot wanted chunks within the time budget. */
    public void tick() {
        final long start = System.nanoTime();
        int n = 0;
        while (!this.wanted.isEmpty() && System.nanoTime() - start < TICK_BUDGET_NANOS && n < 4) {
            final ChunkRef c = this.wanted.peekFirst();
            final World w = Bukkit.getWorld(c.world());
            if (w == null) {
                this.wanted.pollFirst();
                this.queued.remove(c);
                continue;
            }
            if (w.isChunkLoaded(c.cx(), c.cz())) {
                this.wanted.pollFirst();
                this.snapshot(c, w.getChunkAt(c.cx(), c.cz()));
                n++;
                continue;
            }
            if (this.inFlight.get() >= MAX_IN_FLIGHT) {
                break;
            }
            this.wanted.pollFirst();
            this.inFlight.incrementAndGet();
            w.getChunkAtAsync(c.cx(), c.cz()).whenComplete((chunk, err) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.inFlight.decrementAndGet();
                if (err == null && chunk != null) {
                    this.snapshot(c, chunk);
                } else {
                    this.queued.remove(c);
                }
            }));
        }
    }

    private void snapshot(final ChunkRef c, final Chunk chunk) {
        final ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
        final int minY = chunk.getWorld().getMinHeight();
        final long generation = this.generations.getOrDefault(c, 0L);
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            final Surface analysed = analyse(snap, minY);
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.queued.remove(c);
                if (generation == this.generations.getOrDefault(c, 0L)) {
                    this.surfaces.put(c, analysed);
                } else {
                    // A build changed this chunk after the snapshot was taken. Never publish the old hill
                    // as fresh ground; queue another snapshot, and keep planning on hold until it arrives.
                    this.request(c);
                }
            });
        });
    }

    /** Async: top ground block per column (skipping plants, leaves and tree trunks). */
    static Surface analyse(final ChunkSnapshot snap, final int minY) {
        final int[] top = new int[256];
        final byte[] flags = new byte[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = snap.getHighestBlockYAt(x, z);
                byte f = 0;
                int found = TerrainView.UNKNOWN;
                for (int k = 0; k < 48 && y >= minY; k++, y--) {
                    final Material m = snap.getBlockType(x, y, z);
                    if (m.isAir() || Tag.LEAVES.isTagged(m) || Tag.REPLACEABLE.isTagged(m) || Tag.FLOWERS.isTagged(m)
                        || m == Material.SNOW) {
                        continue;
                    }
                    if (m == Material.WATER || m == Material.LAVA) {
                        f |= 1;
                        found = y;
                        break;
                    }
                    if (Tag.LOGS.isTagged(m)) {
                        f |= 2 | 8;
                        continue;
                    }
                    if (!natural(m)) {
                        f |= 2;
                    }
                    if (Tag.BASE_STONE_OVERWORLD.isTagged(m)) {
                        f |= 4;
                    }
                    found = y;
                    break;
                }
                top[z * 16 + x] = found;
                flags[z * 16 + x] = f;
            }
        }
        return new Surface(top, flags, System.currentTimeMillis());
    }

    static boolean natural(final Material m) {
        return Tag.DIRT.isTagged(m) || pl.medivalrpg.ludnosc.logic.BuildFixes.soil(m.name()) || Tag.BASE_STONE_OVERWORLD.isTagged(m)
            || Tag.SAND.isTagged(m)
            || Tag.TERRACOTTA.isTagged(m) || Tag.SNOW.isTagged(m) || Tag.ICE.isTagged(m) || m == Material.GRAVEL
            || m == Material.CLAY || m == Material.DIRT_PATH || m == Material.SANDSTONE || m == Material.RED_SANDSTONE
            || m == Material.MOSS_BLOCK || m == Material.MUD || m.name().endsWith("_ORE");
    }

    /** True when the cache knows the chunk. */
    public boolean known(final ChunkRef c) {
        final Surface s = this.surfaces.get(c);
        return s != null && s.top() != null && s.time() > 0;
    }

    public int pending() {
        return this.wanted.size() + this.inFlight.get();
    }

    /**
     * View for the planner of one country in one world (safe off the main thread: snapshots + Kraje read API).
     * Ownership and surfaces are memoised per chunk inside the view.
     */
    public TerrainView view(final String world, final UUID country) {
        final Map<Long, Boolean> own = new HashMap<>();
        final Map<Long, Surface> local = new HashMap<>();
        return new TerrainView() {
            private Surface surface(final int x, final int z) {
                final long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
                return local.computeIfAbsent(key, k -> {
                    final Surface s = TerrainCache.this.surfaces.get(new ChunkRef(world, x >> 4, z >> 4));
                    return s == null || s.time() == 0 ? new Surface(null, null, 0) : s;
                });
            }

            @Override
            public int height(final int x, final int z) {
                final Surface s = this.surface(x, z);
                return s.top() == null ? UNKNOWN : s.top()[(z & 15) * 16 + (x & 15)];
            }

            @Override
            public boolean liquid(final int x, final int z) {
                final Surface s = this.surface(x, z);
                return s.flags() != null && (s.flags()[(z & 15) * 16 + (x & 15)] & 1) != 0;
            }

            @Override
            public boolean obstacle(final int x, final int z) {
                final Surface s = this.surface(x, z);
                return s.flags() != null && (s.flags()[(z & 15) * 16 + (x & 15)] & 2) != 0;
            }

            @Override
            public boolean stone(final int x, final int z) {
                final Surface s = this.surface(x, z);
                return s.flags() != null && (s.flags()[(z & 15) * 16 + (x & 15)] & 4) != 0;
            }

            @Override
            public boolean tree(final int x, final int z) {
                final Surface s = this.surface(x, z);
                return s.flags() != null && (s.flags()[(z & 15) * 16 + (x & 15)] & 8) != 0;
            }

            @Override
            public boolean own(final int x, final int z) {
                final long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
                return own.computeIfAbsent(key, k -> TerrainCache.this.plugin.kraje().getCountryAt(world, x >> 4, z >> 4)
                    .map(KrajInfo::id).map(country::equals).orElse(false));
            }
        };
    }
}
