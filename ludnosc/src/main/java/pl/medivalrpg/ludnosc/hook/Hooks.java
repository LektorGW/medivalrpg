// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.hook;

import org.bukkit.Bukkit;

/**
 * Presence checks for soft dependencies. This class references no WorldEdit/Armia types, so it is always safe to
 * load; {@link WorldEditHook} and {@link ArmiaHook} are touched only after a positive check.
 */
public final class Hooks {

    private Hooks() {
    }

    public static boolean worldEdit() {
        return Bukkit.getPluginManager().isPluginEnabled("WorldEdit");
    }

    public static boolean armia() {
        return Bukkit.getPluginManager().isPluginEnabled("Armia");
    }
}
