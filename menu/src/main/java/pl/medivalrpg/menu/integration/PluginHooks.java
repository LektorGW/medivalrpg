// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Presence checks for other plugins ({@code requires-plugin}). Main thread only. */
public final class PluginHooks {

    private PluginHooks() {
    }

    /** Installed and enabled plugin by name (exact name first, then case-insensitive), or {@code null}. */
    public static Plugin enabled(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        if (plugin == null) {
            for (final Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p.getName().equalsIgnoreCase(name)) {
                    plugin = p;
                    break;
                }
            }
        }
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    public static boolean isEnabled(final String name) {
        return enabled(name) != null;
    }

    /** {@code true} when every listed plugin is enabled (an empty list is always satisfied). */
    public static boolean allEnabled(final Collection<String> names) {
        for (final String name : names) {
            if (!isEnabled(name)) {
                return false;
            }
        }
        return true;
    }
}
