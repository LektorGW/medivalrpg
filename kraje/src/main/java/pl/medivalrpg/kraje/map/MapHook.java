// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.map;

import java.util.logging.Level;
import org.bukkit.Bukkit;
import pl.medivalrpg.kraje.KrajePlugin;

/**
 * Web map integration. Implementations must only be used on the main thread.
 */
public interface MapHook {

    MapHook NOOP = new MapHook() {
        @Override
        public void scheduleRefresh() {
        }

        @Override
        public void shutdown() {
        }
    };

    /** Requests a (debounced) redraw of all country markers. */
    void scheduleRefresh();

    /** Redraws the siege layer (cheap; called when sieges start / end and periodically while they run). */
    default void refreshSieges() {
    }

    /** Removes layers / icons and cancels pending work. */
    void shutdown();

    /**
     * Creates the squaremap hook if squaremap is installed and the map is enabled. The implementation is
     * loaded reflectively so that this plugin never touches squaremap classes when squaremap is absent.
     */
    static MapHook create(final KrajePlugin plugin) {
        if (!plugin.cfg().mapEnabled || !Bukkit.getPluginManager().isPluginEnabled("squaremap")) {
            return NOOP;
        }
        try {
            final Class<?> type = Class.forName("pl.medivalrpg.kraje.map.SquaremapHook");
            final MapHook hook = (MapHook) type.getConstructor(KrajePlugin.class).newInstance(plugin);
            plugin.getLogger().info("Połączono ze squaremap — kraje będą widoczne na mapie.");
            return hook;
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Nie udało się połączyć ze squaremap — mapa wyłączona.", e);
            return NOOP;
        }
    }
}
