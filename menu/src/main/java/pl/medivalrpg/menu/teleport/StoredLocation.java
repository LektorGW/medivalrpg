// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A location kept in a config or data file (world by name, so it survives restarts and unloaded worlds).
 *
 * <p>In YAML either a section {@code {world, x, y, z, yaw, pitch}} or one string
 * {@code "world, x, y, z[, yaw, pitch]"}. Empty / missing / broken → {@link Optional#empty()}.</p>
 */
public record StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {

    public static StoredLocation of(final Location loc) {
        return new StoredLocation(loc.getWorld() == null ? "" : loc.getWorld().getName(), loc.getX(), loc.getY(),
            loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /** Parses a value read from YAML ({@link ConfigurationSection}, {@link Map} or string). */
    public static Optional<StoredLocation> parse(final Object raw) {
        if (raw instanceof ConfigurationSection s) {
            return fromValues(s.getString("world"), s.get("x"), s.get("y"), s.get("z"), s.get("yaw"), s.get("pitch"));
        }
        if (raw instanceof Map<?, ?> m) {
            return fromValues(m.get("world") == null ? null : String.valueOf(m.get("world")),
                m.get("x"), m.get("y"), m.get("z"), m.get("yaw"), m.get("pitch"));
        }
        if (raw instanceof String str) {
            final String[] parts = str.split(",");
            if (parts.length != 4 && parts.length != 6) {
                return Optional.empty();
            }
            return fromValues(parts[0], parts[1], parts[2], parts[3],
                parts.length == 6 ? parts[4] : null, parts.length == 6 ? parts[5] : null);
        }
        return Optional.empty();
    }

    private static Optional<StoredLocation> fromValues(final String world, final Object x, final Object y,
                                                       final Object z, final Object yaw, final Object pitch) {
        if (world == null || world.isBlank()) {
            return Optional.empty();
        }
        final Double px = number(x);
        final Double py = number(y);
        final Double pz = number(z);
        if (px == null || py == null || pz == null) {
            return Optional.empty();
        }
        final Double pyaw = yaw == null ? Double.valueOf(0) : number(yaw);
        final Double ppitch = pitch == null ? Double.valueOf(0) : number(pitch);
        if (pyaw == null || ppitch == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredLocation(world.strip(), px, py, pz, pyaw.floatValue(),
            (float) Math.clamp(ppitch, -90.0, 90.0)));
    }

    private static Double number(final Object o) {
        if (o instanceof Number n) {
            final double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (o instanceof String s) {
            try {
                final double d = Double.parseDouble(s.strip());
                return Double.isFinite(d) ? d : null;
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Values for {@code ConfigurationSection#set} (rounded to keep the YAML readable). */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", this.world);
        map.put("x", round(this.x, 3));
        map.put("y", round(this.y, 3));
        map.put("z", round(this.z, 3));
        map.put("yaw", round(this.yaw, 1));
        map.put("pitch", round(this.pitch, 1));
        return map;
    }

    private static double round(final double v, final int places) {
        final double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    public int chunkX() {
        return (int) Math.floor(this.x) >> 4;
    }

    public int chunkZ() {
        return (int) Math.floor(this.z) >> 4;
    }

    /** Bukkit location, empty when the world is not loaded. Main thread. */
    public Optional<Location> toLocation() {
        final World w = Bukkit.getWorld(this.world);
        return w == null ? Optional.empty() : Optional.of(new Location(w, this.x, this.y, this.z, this.yaw, this.pitch));
    }

    /** "world 120, 64, -30" for chat. */
    public String describe() {
        return String.format(Locale.ROOT, "%s %d, %d, %d", this.world, (int) Math.floor(this.x),
            (int) Math.floor(this.y), (int) Math.floor(this.z));
    }
}
