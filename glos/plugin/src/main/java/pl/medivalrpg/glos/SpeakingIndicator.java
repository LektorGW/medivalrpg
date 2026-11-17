// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Wskaźnik „kto mówi”: nietrwały {@link TextDisplay} nad głową mówiącego, niewidoczny dla niego samego.
 * Tylko główny wątek.
 */
public final class SpeakingIndicator {

    private record Active(TextDisplay display, VoiceMode mode) {
    }

    private final Plugin plugin;
    private final NamespacedKey key;
    private final Map<UUID, Active> active = new HashMap<>();
    private final PlayerPrefs prefs;
    private GlosConfig config;

    public SpeakingIndicator(Plugin plugin, GlosConfig config, PlayerPrefs prefs) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "speaking_indicator");
        this.config = config;
        this.prefs = prefs;
    }

    /**
     * Widz włącza/wyłącza widzenie wskaźników innych graczy ({@code /glos wskaznik}).
     * Wołane też przy wejściu gracza (ukrycie istniejących wskaźników, gdy ma je wyłączone).
     */
    public void applyViewer(Player viewer, boolean show) {
        for (Map.Entry<UUID, Active> e : active.entrySet()) {
            if (e.getKey().equals(viewer.getUniqueId())) {
                continue; // własny wskaźnik zawsze ukryty
            }
            TextDisplay d = e.getValue().display;
            if (show) {
                viewer.showEntity(plugin, d);
            } else {
                viewer.hideEntity(plugin, d);
            }
        }
    }

    public void setConfig(GlosConfig config) {
        this.config = config;
        hideAll(); // nowe teksty/ustawienia – wskaźniki odtworzą się w następnym sync()
    }

    public boolean isIndicator(Entity e) {
        return e instanceof TextDisplay && e.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * Uzgadnia wskaźnik z oczekiwanym stanem: {@code mode == null} oznacza „bez wskaźnika”.
     * Wołane co tick dla graczy, którzy mają lub powinni mieć wskaźnik.
     */
    public void sync(Player player, VoiceMode mode) {
        UUID id = player.getUniqueId();
        Active cur = active.get(id);
        if (mode == null || !config.indicatorEnabled()) {
            if (cur != null) {
                hide(id);
            }
            return;
        }
        if (cur != null && !cur.display.isValid()) {
            active.remove(id);
            cur = null;
        }
        if (cur != null && !cur.display.getWorld().equals(player.getWorld())) {
            hide(id);
            cur = null;
        }
        if (cur == null) {
            spawn(player, mode);
            return;
        }
        if (cur.mode != mode) {
            cur.display.text(Messages.parse(config.indicatorText(mode)));
            active.put(id, new Active(cur.display, mode));
        }
        TextDisplay d = cur.display;
        if (config.indicatorAttach() == GlosConfig.IndicatorAttach.PASSENGER) {
            if (!player.getPassengers().contains(d)) {
                // Zsiadł (śmierć, teleport, inny plugin) – dosiadamy ponownie; gdy się nie da, podążamy teleportem.
                d.teleport(anchor(player));
                player.addPassenger(d);
            }
        } else {
            d.teleport(anchor(player));
        }
    }

    public boolean has(UUID id) {
        return active.containsKey(id);
    }

    public Iterable<UUID> activeIds() {
        return Map.copyOf(active).keySet();
    }

    public void hide(UUID id) {
        Active a = active.remove(id);
        if (a != null && a.display.isValid()) {
            a.display.remove();
        }
    }

    public void hideAll() {
        for (Iterator<Active> it = active.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            it.remove();
            if (a.display.isValid()) {
                a.display.remove();
            }
        }
    }

    /** Usuwa osierocone wskaźniki (z tagiem PDC pluginu) ze wszystkich załadowanych światów. */
    public int cleanupOrphans() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay d : world.getEntitiesByClass(TextDisplay.class)) {
                if (isIndicator(d) && !isTracked(d)) {
                    d.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public boolean isTracked(Entity e) {
        for (Active a : active.values()) {
            if (a.display.equals(e)) {
                return true;
            }
        }
        return false;
    }

    private Location anchor(Player player) {
        Location l = player.getLocation();
        l.setY(l.getY() + player.getHeight());
        l.setYaw(0);
        l.setPitch(0);
        return l;
    }

    private void spawn(Player player, VoiceMode mode) {
        float viewRange = Math.max(0.2f, (config.maxRange() + 8) / 64f);
        TextDisplay d = player.getWorld().spawn(anchor(player), TextDisplay.class, td -> {
            td.setPersistent(false);
            td.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            td.setBillboard(Display.Billboard.CENTER);
            td.text(Messages.parse(config.indicatorText(mode)));
            td.setDefaultBackground(false);
            td.setBackgroundColor(config.indicatorBackground());
            td.setShadowed(true);
            td.setSeeThrough(false);
            td.setBrightness(new Display.Brightness(15, 15));
            td.setViewRange(viewRange);
            td.setTeleportDuration(2);
            td.setTransformation(new Transformation(
                    new Vector3f(0f, config.indicatorHeight(), 0f),
                    new Quaternionf(),
                    new Vector3f(1f, 1f, 1f),
                    new Quaternionf()));
        });
        player.hideEntity(plugin, d);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != player && !prefs.get(viewer.getUniqueId(), PlayerPrefs.INDICATORS)) {
                viewer.hideEntity(plugin, d);
            }
        }
        if (config.indicatorAttach() == GlosConfig.IndicatorAttach.PASSENGER) {
            player.addPassenger(d); // gdy się nie uda, sync() będzie go przesuwał teleportem
        }
        active.put(player.getUniqueId(), new Active(d, mode));
    }
}
