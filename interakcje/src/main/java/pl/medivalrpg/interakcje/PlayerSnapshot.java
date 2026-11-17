// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable list of online players' positions, rebuilt on the main thread a few times per second.
 * Command suggestions may run off the main thread, so they read this instead of the Bukkit API.
 */
public final class PlayerSnapshot {

    public record Entry(UUID id, String name, UUID world, double x, double y, double z, boolean hidden) {
        public double distanceSquared(Entry o) {
            double dx = x - o.x;
            double dy = y - o.y;
            double dz = z - o.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final InterakcjePlugin plugin;
    private volatile List<Entry> entries = List.of();

    public PlayerSnapshot(InterakcjePlugin plugin) {
        this.plugin = plugin;
    }

    /** Main thread only. */
    public void refresh() {
        List<Entry> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            out.add(new Entry(p.getUniqueId(), p.getName(), l.getWorld().getUID(), l.getX(), l.getY(), l.getZ(),
                    plugin.service().isHidden(p)));
        }
        entries = List.copyOf(out);
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry find(UUID id) {
        for (Entry e : entries) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }
}
