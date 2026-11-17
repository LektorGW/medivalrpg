// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** PDC keys of the plugin (namespace {@code turnieje}). */
public final class Keys {

    /** Kit item lent for a fight; must never exist outside a tournament. */
    public final NamespacedKey kit;
    public final NamespacedKey restored;
    /** Joust horse spawned by the plugin. */
    public final NamespacedKey horse;
    /** Trophy item; value = session id. */
    public final NamespacedKey trophy;
    /** Celebration firework (harmless). */
    public final NamespacedKey firework;

    public Keys(final Plugin plugin) {
        this.kit = new NamespacedKey(plugin, "kit");
        this.restored = new NamespacedKey(plugin, "restored");
        this.horse = new NamespacedKey(plugin, "horse");
        this.trophy = new NamespacedKey(plugin, "trophy");
        this.firework = new NamespacedKey(plugin, "firework");
    }
}
