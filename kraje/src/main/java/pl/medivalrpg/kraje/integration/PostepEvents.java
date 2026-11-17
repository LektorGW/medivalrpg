// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.integration;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.KrajePlugin;

/**
 * Listens to Postep's {@code EraAdvanceEvent} without linking against it. Postep depends on Kraje, so Kraje cannot list it
 * in (soft)depend (load-order cycle) and must not load the event class from its own classloader. The class is taken from
 * Postep's classloader and registered through {@code PluginManager#registerEvent} once Postep is enabled (at Kraje's
 * start when Postep is already up, e.g. after a reload, otherwise on {@link PluginEnableEvent}).
 */
public final class PostepEvents implements Listener {

    static final String PLUGIN = "Postep";
    static final String EVENT_CLASS = "pl.medivalrpg.postep.api.event.EraAdvanceEvent";

    private final KrajePlugin plugin;
    /** Event class already registered (a re-enabled Postep brings a new classloader → registered again). */
    private Class<?> registered;

    public PostepEvents(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(final PluginEnableEvent event) {
        if (PLUGIN.equals(event.getPlugin().getName())) {
            this.tryRegister();
        }
    }

    /** Registers the listener when Postep is enabled; quietly does nothing otherwise. */
    public void tryRegister() {
        final Plugin postep = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (postep == null || !postep.isEnabled()) {
            return;
        }
        try {
            final Class<? extends Event> type = Class.forName(EVENT_CLASS, true, postep.getClass().getClassLoader())
                .asSubclass(Event.class);
            if (type == this.registered) {
                return;
            }
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                if (type.isInstance(event)) {
                    this.plugin.onEraAdvance();
                }
            }, this.plugin, true);
            this.registered = type;
            this.plugin.getLogger().info("Postep: nasłuch zmian epok (EraAdvanceEvent) włączony.");
        } catch (final ClassNotFoundException | ClassCastException | LinkageError e) {
            this.plugin.getLogger().warning("Postep jest włączony, ale nie ma " + EVENT_CLASS + " (inna wersja?): " + e);
        }
    }
}
