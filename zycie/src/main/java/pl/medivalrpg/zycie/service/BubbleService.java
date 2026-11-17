// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.config.ZycieConfig;

/**
 * Speech bubbles: a {@link TextDisplay} riding on the villager (follows it smoothly, no per-tick teleports), visible
 * within {@code dymki.zasieg} blocks (view range), slowly floating up (transformation interpolation), removed after a
 * few seconds. Bubbles are never saved ({@code setPersistent(false)}) and carry the PDC tag {@code zycie:bubble}, so
 * leftovers (crash) are removed at start-up and when chunks load. Main thread only.
 */
public final class BubbleService {

    private final ZyciePlugin plugin;
    private final NamespacedKey tag;
    private final Map<UUID, TextDisplay> active = new HashMap<>();
    private ZycieConfig cfg;
    private long shown;

    public BubbleService(final ZyciePlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "bubble");
    }

    public void configure(final ZycieConfig config) {
        this.cfg = config;
    }

    public NamespacedKey tag() {
        return this.tag;
    }

    public int activeCount() {
        return this.active.size();
    }

    public long shownTotal() {
        return this.shown;
    }

    public boolean hasBubble(final Entity speaker) {
        final TextDisplay d = this.active.get(speaker.getUniqueId());
        return d != null && d.isValid();
    }

    /** Shows {@code text} above {@code speaker} for {@code ticks}; false when the global limit is reached. */
    public boolean show(final Entity speaker, final Component text, final int ticks) {
        if (speaker == null || !speaker.isValid()) {
            return false;
        }
        this.remove(speaker.getUniqueId());
        if (this.active.size() >= this.cfg.bubbleMax) {
            return false;
        }
        final World world = speaker.getWorld();
        final Location at = speaker.getLocation().add(0, speaker.getHeight(), 0);
        final float base = this.cfg.bubbleHeight;
        final TextDisplay display = world.spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(this.tag, PersistentDataType.BYTE, (byte) 1);
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setViewRange((float) Math.max(0.05, this.cfg.bubbleRange / 64.0));
            d.setBackgroundColor(Color.fromARGB(this.cfg.bubbleBackground));
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setLineWidth(this.cfg.bubbleLineWidth);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setTransformation(translation(base));
        });
        if (!speaker.addPassenger(display)) {
            // cannot ride (e.g. the villager sits in a boat): stays where it was spawned
            display.setTeleportDuration(3);
        }
        this.active.put(speaker.getUniqueId(), display);
        this.shown++;
        final UUID id = speaker.getUniqueId();
        // float up a little, smoothly (client-side interpolation)
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (display.isValid()) {
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(Math.max(1, ticks - 2));
                display.setTransformation(translation(base + this.cfg.bubbleRise));
            }
        }, 2L);
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (this.active.get(id) == display) {
                this.active.remove(id);
            }
            display.remove();
        }, Math.max(10, ticks));
        return true;
    }

    private static Transformation translation(final float y) {
        return new Transformation(new Vector3f(0f, y, 0f), new AxisAngle4f(), new Vector3f(1f, 1f, 1f), new AxisAngle4f());
    }

    public void remove(final UUID speaker) {
        final TextDisplay d = this.active.remove(speaker);
        if (d != null) {
            d.remove();
        }
    }

    /** Removes every bubble (disable / reload). */
    public void clearAll() {
        for (final TextDisplay d : new ArrayList<>(this.active.values())) {
            d.remove();
        }
        this.active.clear();
    }

    /** Bubble or firework of this plugin (PDC)? */
    public boolean isOurs(final Entity e) {
        return e.getPersistentDataContainer().has(this.tag, PersistentDataType.BYTE);
    }

    /** Start-up: removes leftover bubbles of all loaded entities (one pass over loaded TextDisplays). */
    public int purgeLoaded() {
        final List<Entity> doomed = new ArrayList<>();
        for (final World w : Bukkit.getWorlds()) {
            for (final TextDisplay d : w.getEntitiesByClass(TextDisplay.class)) {
                if (this.isOurs(d)) {
                    doomed.add(d);
                }
            }
        }
        doomed.forEach(Entity::remove);
        return doomed.size();
    }
}
