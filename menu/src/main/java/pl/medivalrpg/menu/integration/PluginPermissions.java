// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.integration;

import java.util.Collection;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Mirrors Szlaki's configured manager alternative without granting administrative rights. Main thread only. */
public final class PluginPermissions {
    private PluginPermissions() { }

    public static boolean has(final Player player, final String permission) {
        if (player.hasPermission(permission)) return true;
        if (!"szlaki.zarzadzanie".equals(permission)) return false;
        final Plugin routes = PluginHooks.enabled("Szlaki");
        return configuredManager(permission, player.getUniqueId(), routes == null ? java.util.List.of()
            : routes.getConfig().getStringList("managers"));
    }

    /** The same exact UUID string match used by Szlaki's manager(Player). */
    public static boolean configuredManager(final String permission, final UUID player, final Collection<String> managers) {
        return "szlaki.zarzadzanie".equals(permission) && managers.contains(player.toString());
    }
}
