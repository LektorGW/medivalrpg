// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import pl.medivalrpg.kraje.api.event.CountryCreateEvent;
import pl.medivalrpg.kraje.api.event.CountryDisbandEvent;
import pl.medivalrpg.kraje.api.event.RelationChangeEvent;
import pl.medivalrpg.kraje.api.event.VassalageEndEvent;
import pl.medivalrpg.kraje.api.event.VassalageStartEvent;
import pl.medivalrpg.postep.PostepPlugin;

/** Kraje events (new / removed countries, diplomacy), soft dependencies coming and going, join reminders. */
public final class PostepListener implements Listener {

    private final PostepPlugin plugin;

    public PostepListener(final PostepPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreate(final CountryCreateEvent event) {
        // Kraje fires the event before every detail is in place; evaluate on the next tick.
        Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.service().ensureCountry(event.countryId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(final CountryDisbandEvent event) {
        this.plugin.service().removeCountry(event.countryId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRelation(final RelationChangeEvent event) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.plugin.service().refresh(event.countryA());
            this.plugin.service().refresh(event.countryB());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVassalStart(final VassalageStartEvent event) {
        Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.service().refresh(event.vassal()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVassalEnd(final VassalageEndEvent event) {
        Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.service().refresh(event.vassal()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (event.getPlayer().isOnline()) {
                this.plugin.service().remindPending(event.getPlayer());
            }
        }, 60L);
    }

    @EventHandler
    public void onPluginEnable(final PluginEnableEvent event) {
        this.softChanged(event.getPlugin().getName());
    }

    @EventHandler
    public void onPluginDisable(final PluginDisableEvent event) {
        this.softChanged(event.getPlugin().getName());
    }

    private void softChanged(final String name) {
        if ("Ludnosc".equals(name) || "Armia".equals(name)) {
            this.plugin.integrations().reset();
        }
    }
}
