// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import pl.medivalrpg.kraje.KrajePlugin;

/**
 * Keeps cached member names fresh, reminds about unpaid taxes, forgets siege boss bar viewers on quit and
 * redraws the map when a world is loaded.
 */
public final class PlayerListener implements Listener {

    private final KrajePlugin plugin;

    public PlayerListener(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.plugin.manager().updateMemberName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline()) {
                this.plugin.treasury().remindDebt(player);
                // Persistent invitations (e.g. from the conqueror of the player's fallen country).
                this.plugin.actions().showStoredInvites(player);
            }
        }, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.plugin.sieges().forgetViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(final WorldLoadEvent event) {
        // squaremap initialises its map world on WorldLoadEvent (LOW); the debounced refresh runs later.
        this.plugin.mapHook().scheduleRefresh();
    }
}
