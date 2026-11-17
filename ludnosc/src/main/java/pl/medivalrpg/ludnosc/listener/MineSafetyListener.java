// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Mine shafts are open pits with a ladder: a player who falls into one ({@code mines.fall-protection}) lands on the
 * miners' safety rope — no fall damage inside the mine building (the shaft and its bottom). Event driven, one
 * registry lookup per fall.
 */
public final class MineSafetyListener implements Listener {

    private final LudnoscPlugin plugin;
    private final Map<UUID, Long> told = new ConcurrentHashMap<>();

    public MineSafetyListener(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(final EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)
            || !this.plugin.settings().work.mineFallProtection) {
            return;
        }
        final Location l = p.getLocation();
        Building b = this.plugin.data().buildingAt(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (b == null) {
            b = this.plugin.data().buildingAt(l.getWorld().getName(), l.getBlockX(), l.getBlockY() - 1, l.getBlockZ());
        }
        if (b == null || b.function() != BuildingFunction.MINE) {
            return;
        }
        e.setCancelled(true);
        p.setFallDistance(0);
        l.getWorld().playSound(l, Sound.BLOCK_WOOL_FALL, 0.8f, 0.8f);
        l.getWorld().spawnParticle(Particle.CLOUD, l.clone().add(0, 0.2, 0), 6, 0.3, 0.1, 0.3, 0.01);
        final long now = System.currentTimeMillis();
        final Long last = this.told.get(p.getUniqueId());
        if (last == null || now - last > 60_000L) {
            this.told.put(p.getUniqueId(), now);
            this.plugin.messages().send(p, "mine-fall-saved");
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        this.told.remove(e.getPlayer().getUniqueId());
    }
}
