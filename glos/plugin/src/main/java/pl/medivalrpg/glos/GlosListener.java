// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/** Zdarzenia graczy i ochrona przed osieroconymi wskaźnikami. */
public final class GlosListener implements Listener {
    private final GlosPlugin plugin;

    public GlosListener(GlosPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.voice().playerJoined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.voice().playerQuit(event.getPlayer());
    }

    /** Wskaźniki są nietrwałe, ale gdyby jakiś trafił na dysk (np. awaria), usuwamy go przy wczytaniu chunka. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        SpeakingIndicator indicator = plugin.indicator();
        for (Entity e : event.getEntities()) {
            if (indicator.isIndicator(e) && !indicator.isTracked(e)) {
                e.remove();
            }
        }
    }
}
