// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.service;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import pl.medivalrpg.pory.logic.BiomeMapping;
import pl.medivalrpg.pory.logic.ChunkPlans;
import pl.medivalrpg.pory.logic.SnowCoverage;
import pl.medivalrpg.pory.logic.SnowTracker;
import pl.medivalrpg.pory.nms.Nms;
import pl.medivalrpg.pory.nms.SeasonPacketHandler;
import pl.medivalrpg.pory.nms.SnowRules;
import pl.medivalrpg.pory.nms.SnowSectionPacket;

/**
 * Client-only snow cover. For every chunk a player receives (and for all of a player's chunks when the coverage
 * changes) it computes which top surfaces carry snow and sends them as multi-block updates to that player only.
 * The server world is never modified. Work is queued and done within a per-tick time budget. Main thread only.
 */
public final class SnowService {

    /** fresh = the client just got the whole chunk (shows no snow yet). */
    private record Job(UUID player, UUID world, int cx, int cz, boolean fresh) {
    }

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final ToDoubleFunction<Player> coverage;
    private volatile SnowRules rules;
    private volatile BiomeMapping mapping;
    private boolean snowyGround = true;
    private int maxQueue = 20000;

    public long chunksProcessed;
    public long columnsAdded;
    public long columnsRemoved;
    public long dropped;
    public long nanos;

    public SnowService(final ToDoubleFunction<Player> coverage) {
        this.coverage = coverage;
    }

    public void configure(final SnowRules rules, final BiomeMapping mapping, final boolean snowyGround,
                          final int maxQueue) {
        this.rules = rules;
        this.mapping = mapping;
        this.snowyGround = snowyGround;
        this.maxQueue = maxQueue;
    }

    public int pending() {
        return this.queue.size();
    }

    public void clear() {
        this.queue.clear();
    }

    private void offer(final Job job) {
        if (this.queue.size() >= this.maxQueue) {
            this.queue.poll();
            this.dropped++;
        }
        this.queue.add(job);
    }

    /** The player just received this chunk. */
    public void enqueueFresh(final Player player, final int cx, final int cz) {
        this.offer(new Job(player.getUniqueId(), player.getWorld().getUID(), cx, cz, true));
    }

    /** Bring all chunks of the player to the current coverage (adds and removes), nearest first. */
    public int enqueueReconcile(final Player player) {
        final int cx = player.getLocation().getBlockX() >> 4;
        final int cz = player.getLocation().getBlockZ() >> 4;
        final long[] keys = ChunkPlans.nearestFirst(player.getSentChunkKeys(), cx, cz);
        final UUID world = player.getWorld().getUID();
        for (final long k : keys) {
            this.offer(new Job(player.getUniqueId(), world, ChunkPlans.chunkX(k), ChunkPlans.chunkZ(k), false));
        }
        return keys.length;
    }

    public void tick(final double budgetMs) {
        if (this.queue.isEmpty()) {
            return;
        }
        final long start = System.nanoTime();
        final long deadline = start + (long) (budgetMs * 1_000_000L);
        final Registry<Biome> registry = Nms.biomes();
        final Map<UUID, Player> players = new HashMap<>();
        do {
            final Job job = this.queue.poll();
            Player p = players.get(job.player());
            if (p == null) {
                p = Bukkit.getPlayer(job.player());
                if (p == null) {
                    continue;
                }
                players.put(job.player(), p);
            }
            this.process(p, job, registry);
        } while (!this.queue.isEmpty() && System.nanoTime() < deadline);
        this.nanos += System.nanoTime() - start;
    }

    private void process(final Player player, final Job job, final Registry<Biome> registry) {
        final World world = player.getWorld();
        if (!world.getUID().equals(job.world()) || !player.isChunkSent(chunkKey(job.cx(), job.cz()))) {
            return;
        }
        final SeasonPacketHandler handler = Nms.handler(player);
        if (handler == null) {
            return;
        }
        final LevelChunk chunk = Nms.loadedChunk(world, job.cx(), job.cz());
        if (chunk == null) {
            return;
        }
        this.chunksProcessed++;
        final double cov = this.coverage.applyAsDouble(player);
        final short[] desired = cov > 0 ? this.desired(chunk, job.cx(), job.cz(), cov, registry) : null;
        final short[] current = job.fresh() ? null : handler.snow().snapshot(job.cx(), job.cz());
        if (desired == null && current == null) {
            return;
        }
        final List<ChunkPlans.ColumnChange> changes = ChunkPlans.diff(current, desired);
        if (!changes.isEmpty()) {
            this.send(player, chunk, job.cx(), job.cz(), changes);
        }
    }

    private static long chunkKey(final int cx, final int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    /** Snow heights per column ({@code z << 4 | x}) or null when no column gets snow. */
    public short[] desired(final LevelChunk chunk, final int cx, final int cz, final double cov,
                    final Registry<Biome> registry) {
        final SnowRules r = this.rules;
        final BiomeMapping m = this.mapping;
        if (r == null) {
            return null;
        }
        final int minY = chunk.getMinY();
        final int maxY = chunk.getMaxY();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        short[] out = null;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                final int x = (cx << 4) + lx;
                final int z = (cz << 4) + lz;
                if (!SnowCoverage.covered(x, z, cov)) {
                    continue;
                }
                final int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                if (top < minY || top + 1 > maxY) {
                    continue;
                }
                if (!r.supports(chunk.getBlockState(pos.set(x, top, z)))) {
                    continue;
                }
                if (!chunk.getBlockState(pos.set(x, top + 1, z)).isAir()) {
                    continue;
                }
                if (m != null && !m.snowEligible(Nms.biomeId(registry, chunk, x, top + 1, z))) {
                    continue;
                }
                if (out == null) {
                    out = new short[256];
                    Arrays.fill(out, SnowTracker.NONE);
                }
                out[lz << 4 | lx] = (short) (top + 1);
            }
        }
        return out;
    }

    private static final class SectionChanges {
        final Short2ObjectOpenHashMap<BlockState> states = new Short2ObjectOpenHashMap<>();
        final LongArrayList added = new LongArrayList();
        final LongArrayList removed = new LongArrayList();
    }

    private void send(final Player player, final LevelChunk chunk, final int cx, final int cz,
                      final List<ChunkPlans.ColumnChange> changes) {
        final Map<Integer, SectionChanges> sections = new HashMap<>();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockState snow = Blocks.SNOW.defaultBlockState();
        // removals first so that a new snow position in the same place wins
        for (final ChunkPlans.ColumnChange c : changes) {
            if (c.oldY() == SnowTracker.NONE) {
                continue;
            }
            final int x = (cx << 4) + (c.index() & 15);
            final int z = (cz << 4) + (c.index() >> 4);
            put(sections, pos.set(x, c.oldY(), z), chunk.getBlockState(pos));
            put(sections, pos.set(x, c.oldY() - 1, z), chunk.getBlockState(pos));
            sections.get(SectionPos.blockToSectionCoord(c.oldY())).removed.add(BlockPos.asLong(x, c.oldY(), z));
            this.columnsRemoved++;
        }
        for (final ChunkPlans.ColumnChange c : changes) {
            if (c.newY() == SnowTracker.NONE) {
                continue;
            }
            final int x = (cx << 4) + (c.index() & 15);
            final int z = (cz << 4) + (c.index() >> 4);
            put(sections, pos.set(x, c.newY(), z), snow);
            sections.get(SectionPos.blockToSectionCoord(c.newY())).added.add(BlockPos.asLong(x, c.newY(), z));
            if (this.snowyGround) {
                final BlockState ground = chunk.getBlockState(pos.set(x, c.newY() - 1, z));
                if (ground.hasProperty(BlockStateProperties.SNOWY) && !ground.getValue(BlockStateProperties.SNOWY)) {
                    put(sections, pos, ground.setValue(BlockStateProperties.SNOWY, true));
                }
            }
            this.columnsAdded++;
        }
        for (final Map.Entry<Integer, SectionChanges> e : sections.entrySet()) {
            final SectionChanges s = e.getValue();
            Nms.send(player, new SnowSectionPacket(SectionPos.of(cx, e.getKey(), cz), s.states,
                s.added.toLongArray(), s.removed.toLongArray()));
        }
    }

    private static void put(final Map<Integer, SectionChanges> sections, final BlockPos pos, final BlockState state) {
        sections.computeIfAbsent(SectionPos.blockToSectionCoord(pos.getY()), k -> new SectionChanges())
            .states.put(SectionPos.sectionRelativePos(pos), state);
    }
}
