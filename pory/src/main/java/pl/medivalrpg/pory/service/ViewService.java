// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.service;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.medivalrpg.pory.config.PoryConfig;
import pl.medivalrpg.pory.logic.BiomeMapping;
import pl.medivalrpg.pory.logic.VisualSeason;
import pl.medivalrpg.pory.nms.Nms;
import pl.medivalrpg.pory.nms.PacketContext;
import pl.medivalrpg.pory.nms.SeasonPacketHandler;

/**
 * What each player sees: the server season (or an admin preview), and the snow coverage of today. On every change it
 * queues the needed refreshes — biome colours and/or snow — for the affected players only. Main thread only.
 */
public final class ViewService implements Listener {

    private record View(VisualSeason season, double coverage) {
    }

    private final PacketContext context;
    private final SeasonService seasons;
    private final BiomeRefresher biomes;
    private final SnowService snow;
    private final Map<UUID, View> views = new HashMap<>();
    private final java.util.function.Consumer<String> warn;
    private PoryConfig config;

    public ViewService(final PacketContext context, final SeasonService seasons, final BiomeRefresher biomes,
                       final SnowService snow, final PoryConfig config, final java.util.function.Consumer<String> warn) {
        this.context = context;
        this.seasons = seasons;
        this.biomes = biomes;
        this.snow = snow;
        this.config = config;
        this.warn = warn;
    }

    public void config(final PoryConfig config) {
        this.config = config;
    }

    public boolean worldAllowed(final World world) {
        if (world == null) {
            return false;
        }
        final Set<String> worlds = this.config.worlds();
        return worlds.isEmpty() ? world.getEnvironment() == World.Environment.NORMAL : worlds.contains(world.getName());
    }

    public VisualSeason season(final Player player) {
        final SeasonPacketHandler h = Nms.handler(player);
        final VisualSeason o = h == null ? null : h.override();
        return o != null ? o : this.seasons.state().season();
    }

    public boolean previewing(final Player player) {
        final SeasonPacketHandler h = Nms.handler(player);
        return h != null && h.override() != null;
    }

    /** Today's snow cover for this player (0 when snow is off or the world has no seasons). */
    public double coverage(final Player player) {
        if (!this.config.snow() || !this.worldAllowed(player.getWorld())) {
            return 0;
        }
        final SeasonPacketHandler h = Nms.handler(player);
        if (h != null && h.override() != null) {
            return this.config.coverage().coverage(h.override(), this.config.coverage().peakDay(h.override()));
        }
        final SeasonService.State s = this.seasons.state();
        return this.config.coverage().coverage(s.season(), s.day());
    }

    /** The server season or day changed (or config reloaded): update everyone. */
    public void refreshAll(final boolean force) {
        this.context.globalSeason(this.seasons.state().season());
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.apply(p, force);
        }
    }

    /** Admin preview for one player; null ends it. */
    public void preview(final Player player, final VisualSeason season) {
        final SeasonPacketHandler h = Nms.handler(player);
        if (h == null) {
            return;
        }
        h.override(season);
        this.apply(player, false);
    }

    /** Re-sends what changes between the shown and the wanted look; returns the number of queued chunks. */
    public int apply(final Player player, final boolean force) {
        final SeasonPacketHandler h = Nms.handler(player);
        if (h == null) {
            return 0;
        }
        final VisualSeason season = this.season(player);
        final double cov = this.coverage(player);
        final View old = this.views.put(player.getUniqueId(), new View(season, cov));
        int queued = 0;
        final BiomeMapping mapping = this.context.mapping();
        final boolean biomesDiffer = old != null && mapping != null && this.context.biomesEnabled()
            && mapping.table(old.season()) != mapping.table(season);
        if (force || biomesDiffer) {
            queued = this.biomes.enqueue(player);
        }
        if (force || old == null || old.coverage() != cov) {
            final int n = this.snow.enqueueReconcile(player);
            queued = Math.max(queued, n);
        }
        return queued;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        if (Nms.handler(p) == null) {
            Nms.ensure(p, this.context, this.warn);
        }
        this.views.put(p.getUniqueId(), new View(this.seasons.state().season(), this.coverage(p)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.views.remove(event.getPlayer().getUniqueId());
        this.biomes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkSent(final PlayerChunkLoadEvent event) {
        final Player p = event.getPlayer();
        if (this.coverage(p) > 0) {
            this.snow.enqueueFresh(p, event.getChunk().getX(), event.getChunk().getZ());
        }
    }
}
