// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import pl.medivalrpg.ludnosc.LudnoscPlugin;

/** Keeps the citizen registry in sync with villager entities — driven only by chunk/entity events. */
public final class CitizenListener implements Listener {

    private final LudnoscPlugin plugin;

    public CitizenListener(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        for (final Entity e : event.getEntities()) {
            if (this.plugin.construction().isHelper(e)) {
                this.plugin.construction().checkLoadedHelper(e);
            }
        }
        this.plugin.citizens().onEntitiesLoad(event.getChunk(), event.getEntities());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(final org.bukkit.event.world.ChunkLoadEvent event) {
        this.plugin.terrain().onChunkLoad(event.getChunk());
        this.plugin.repairs().onChunkLoad(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(final EntitiesUnloadEvent event) {
        this.plugin.citizens().onEntitiesUnload(event.getEntities());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(final EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Villager)) {
            return;
        }
        switch (event.getCause()) {
            case UNLOAD, PLAYER_QUIT -> {
                // still exists on disk
            }
            default -> this.plugin.citizens().onGone(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING && event.getEntity() instanceof Villager v) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (v.isValid() && this.plugin.data().citizen(v.getUniqueId()) == null) {
                    this.plugin.citizens().onBorn(v);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCareer(final VillagerCareerChangeEvent event) {
        this.plugin.citizens().onCareerChange(event.getEntity(), event.getProfession());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.plugin.plans().forget(event.getPlayer().getUniqueId());
    }
}
