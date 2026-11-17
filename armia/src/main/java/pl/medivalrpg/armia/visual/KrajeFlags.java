// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Reflective bridge to the country flags of Kraje ({@code KrajePlugin#flagShow/flagHide/flagHidePrefix}, see
 * kraje/README.md "Most dla innych pluginów"): an approved uploaded flag (or the dyed default banner) on the stern staff.
 * Older Kraje without the methods: every call is a no-op returning false, the model keeps its own dyed ensign.
 */
final class KrajeFlags {
    static final String PREFIX = "armia:statek:";
    private static Plugin plugin;
    private static Method show, hide, hidePrefix;
    private static boolean resolved;

    private KrajeFlags() { }

    private static boolean resolve() {
        final Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
        if (kraje == null || !kraje.isEnabled()) return false;
        if (resolved && plugin == kraje) return show != null;
        resolved = true;
        plugin = kraje;
        try {
            show = kraje.getClass().getMethod("flagShow", String.class, String.class, Location.class, float.class, boolean.class, float.class);
            hide = kraje.getClass().getMethod("flagHide", String.class);
            hidePrefix = kraje.getClass().getMethod("flagHidePrefix", String.class);
        } catch (final NoSuchMethodException e) {
            show = hide = hidePrefix = null;
        }
        return show != null;
    }

    /**
     * Shows/moves the banner. {@code hoistTop} = top of the ensign staff; the Kraje cloth streams towards its model
     * +X, which BetterModel turns to the stern for {@code yaw = shipYaw + 90}.
     */
    static boolean show(final UUID ship, final UUID country, final Location hoistTop, final float shipYaw, final float scale) {
        if (ship == null || country == null || !resolve()) return false;
        try {
            return (Boolean) show.invoke(plugin, PREFIX + ship, country.toString(), hoistTop, shipYaw + 90f, false, scale);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    static void hide(final UUID ship) {
        if (ship == null || !resolve()) return;
        try { hide.invoke(plugin, PREFIX + ship); } catch (final ReflectiveOperationException | RuntimeException ignored) { }
    }

    static void hideAll() {
        if (!resolve()) return;
        try { hidePrefix.invoke(plugin, PREFIX); } catch (final ReflectiveOperationException | RuntimeException ignored) { }
    }
}
