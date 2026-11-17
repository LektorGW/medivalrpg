// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import pl.medivalrpg.pory.logic.ChunkPlans;
import pl.medivalrpg.pory.nms.Nms;

/**
 * After a season change, re-sends only the biomes (small {@code ChunksBiomes} packets, no blocks/light) of every chunk
 * a player has, nearest first, within per-player and global per-tick limits. The packet handler then rewrites them to
 * the player's season; the client recolours those chunks. Main thread only.
 */
public final class BiomeRefresher {

    private final Map<UUID, ArrayDeque<Long>> queues = new LinkedHashMap<>();
    public long sentChunks;
    public long sentPackets;

    /** Queues all chunks the player currently has (replaces an older queue). Returns their number. */
    public int enqueue(final Player player) {
        final int cx = player.getLocation().getBlockX() >> 4;
        final int cz = player.getLocation().getBlockZ() >> 4;
        final long[] keys = ChunkPlans.nearestFirst(player.getSentChunkKeys(), cx, cz);
        final ArrayDeque<Long> q = new ArrayDeque<>(keys.length);
        for (final long k : keys) {
            q.add(k);
        }
        this.queues.put(player.getUniqueId(), q);
        return keys.length;
    }

    public void remove(final UUID player) {
        this.queues.remove(player);
    }

    public int pending() {
        int n = 0;
        for (final ArrayDeque<Long> q : this.queues.values()) {
            n += q.size();
        }
        return n;
    }

    public void tick(final int perPlayer, final int perTick, final int perPacket) {
        if (this.queues.isEmpty()) {
            return;
        }
        int budget = perTick;
        final Iterator<Map.Entry<UUID, ArrayDeque<Long>>> it = this.queues.entrySet().iterator();
        while (it.hasNext() && budget > 0) {
            final Map.Entry<UUID, ArrayDeque<Long>> e = it.next();
            final Player player = Bukkit.getPlayer(e.getKey());
            final ArrayDeque<Long> q = e.getValue();
            if (player == null || !player.isOnline() || q.isEmpty()) {
                it.remove();
                continue;
            }
            final World world = player.getWorld();
            final List<LevelChunk> batch = new ArrayList<>(perPacket);
            int taken = 0;
            while (!q.isEmpty() && taken < perPlayer && budget > 0) {
                final long key = q.poll();
                if (!player.isChunkSent(key)) {
                    continue;
                }
                final LevelChunk chunk = Nms.loadedChunk(world, ChunkPlans.chunkX(key), ChunkPlans.chunkZ(key));
                if (chunk == null) {
                    continue;
                }
                batch.add(chunk);
                taken++;
                budget--;
                if (batch.size() >= perPacket) {
                    this.send(player, batch);
                }
            }
            if (!batch.isEmpty()) {
                this.send(player, batch);
            }
            if (q.isEmpty()) {
                it.remove();
            }
        }
        // round robin: players served this tick go to the back
        if (!this.queues.isEmpty()) {
            final List<Map.Entry<UUID, ArrayDeque<Long>>> served = new ArrayList<>(this.queues.entrySet());
            this.queues.clear();
            final int rotate = Math.min(served.size(), Math.max(1, perTick / Math.max(1, perPlayer)));
            for (int i = rotate; i < served.size(); i++) {
                this.queues.put(served.get(i).getKey(), served.get(i).getValue());
            }
            for (int i = 0; i < rotate; i++) {
                this.queues.put(served.get(i).getKey(), served.get(i).getValue());
            }
        }
    }

    private void send(final Player player, final List<LevelChunk> batch) {
        Nms.send(player, ClientboundChunksBiomesPacket.forChunks(new ArrayList<>(batch)));
        this.sentChunks += batch.size();
        this.sentPackets++;
        batch.clear();
    }
}
