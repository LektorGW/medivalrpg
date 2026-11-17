// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Straż MEDIVALRPG: daily owner report, immediate alerts and the nightly plugin-data backup. Read-only towards the
 * other plugins (ServicesManager APIs and their data files); no game mechanics are changed.
 */
public final class StrazPlugin extends JavaPlugin implements Listener {

    private StrazService service;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        final StrazConfig cfg = StrazConfig.from(this.getConfig(), this.getLogger());
        this.service = new StrazService(this, cfg);
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(new StrazCommand(this).build(), "Straż MEDIVALRPG — raport, alerty i kopie (admin)", List.of()));
        this.getServer().getScheduler().runTaskTimer(this, this.service::minuteTick, 20L * 60, 20L * 60);
        this.service.start();
        this.getLogger().info("Straz by Maciej Kaczmarczyk — lektoro.pl");
    }

    @Override
    public void onDisable() {
        if (this.service != null) {
            this.service.stop();
        }
    }

    public StrazService service() {
        return this.service;
    }

    /** /straz przeladuj: config only (webhook, hours, thresholds); state and counters stay. */
    public void reloadStraz() {
        this.reloadConfig();
        this.service.apply(StrazConfig.from(this.getConfig(), this.getLogger()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(final ServerTickEndEvent event) {
        this.service.tickEnded(event.getTickDuration());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.service.playerJoined();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager && this.service.bridges().isCitizen(event.getEntity())) {
            this.service.citizenKilled();
        }
    }
}
