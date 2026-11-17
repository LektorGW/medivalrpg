// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The design plan of a country drawn in the web panel (JSON v1, PANEL.md "Edytor planu państwa NPC"), pure and unit
 * tested: parsing, validation rules that do not need the world, zones (which building may stand where, what the
 * planner may not touch), road and wall lines rasterised into cells, buildings in priority order.
 */
public final class DesignPlan {

    /** A zone: type and polygon corners [[x, z], ...]. */
    public static final class Zone {
        public String type;
        public List<int[]> polygon = new ArrayList<>();
    }

    /** A drawn road. */
    public static final class Road {
        public String kind = "boczna";
        public List<int[]> points = new ArrayList<>();
        public int width = 1;
    }

    /**
     * A wall: manual {@code points} (+ {@code closed}, {@code gates}), or {@code mode} "follow-border" (along the
     * country's border, {@code inset} blocks inside) / "follow-town" (around the built-up area).
     */
    public static final class Wall {
        public List<int[]> points = new ArrayList<>();
        public boolean closed;
        public List<int[]> gates = new ArrayList<>();
        public String mode;
        public int inset = 2;
    }

    /** A building at a fixed place. */
    public static final class Building {
        public String type;
        public int x;
        public int z;
        public int rotation;
        public int priority = 100;
    }

    /** Options. */
    public static final class Options {
        public boolean allowDemolish = true;
        public boolean keepExisting = true;
        public boolean autoFill = true;
    }

    public int version = 1;
    public List<Zone> zones = new ArrayList<>();
    public List<Road> roads = new ArrayList<>();
    public List<Wall> walls = new ArrayList<>();
    public List<Building> buildings = new ArrayList<>();
    public Options options = new Options();

    public static final Set<String> ZONE_TYPES = Set.of("mieszkalna", "centrum", "rzemieslnicza", "rolnicza", "las", "kopalnia", "port",
        "obronna", "park", "zabytki", "nie_ruszac");

    private static final Gson GSON = new GsonBuilder().create();

    /** Parses a plan; throws {@link IllegalArgumentException} with a Polish message on broken JSON. */
    public static DesignPlan parse(final String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("pusty plan");
        }
        final DesignPlan p;
        try {
            p = GSON.fromJson(json, DesignPlan.class);
        } catch (final JsonParseException | IllegalStateException | NumberFormatException e) {
            throw new IllegalArgumentException("błędny JSON planu: " + e.getMessage());
        }
        if (p == null) {
            throw new IllegalArgumentException("pusty plan");
        }
        p.zones = p.zones == null ? new ArrayList<>() : p.zones;
        p.roads = p.roads == null ? new ArrayList<>() : p.roads;
        p.walls = p.walls == null ? new ArrayList<>() : p.walls;
        p.buildings = p.buildings == null ? new ArrayList<>() : p.buildings;
        p.options = p.options == null ? new Options() : p.options;
        return p;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Buildings in the order they are queued (priority ascending, then as drawn). */
    public List<Building> buildingsByPriority() {
        final List<Building> out = new ArrayList<>(this.buildings);
        out.sort(Comparator.comparingInt(b -> b.priority));
        return out;
    }

    /** Point in polygon (ray casting; corners as drawn). */
    public static boolean inside(final List<int[]> poly, final double x, final double z) {
        boolean in = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            final int[] a = poly.get(i);
            final int[] b = poly.get(j);
            if ((a[1] > z) != (b[1] > z) && x < (double) (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    /** The zone at a column (the last drawn wins), or null. */
    public String zoneAt(final int x, final int z) {
        String found = null;
        for (final Zone zone : this.zones) {
            if (zone.polygon != null && zone.polygon.size() >= 3 && inside(zone.polygon, x + 0.5, z + 0.5)) {
                found = zone.type == null ? null : zone.type.toLowerCase(Locale.ROOT);
            }
        }
        return found;
    }

    /** The planner may not work at all here (no building, cutting, levelling, demolition). */
    public boolean untouchable(final int x, final int z) {
        return "nie_ruszac".equals(this.zoneAt(x, z));
    }

    /** No demolition here (heritage zone, untouchable zone, or demolitions switched off). */
    public boolean noDemolition(final int x, final int z) {
        final String zone = this.zoneAt(x, z);
        return !this.options.allowDemolish || "zabytki".equals(zone) || "nie_ruszac".equals(zone);
    }

    /**
     * May the automatic planner put a building of this category / function zone here? Outside every zone anything
     * goes (when {@code autoFill}); in a zone only matching types.
     *
     * @param category template category (housing, civic, economy, food, farm, military, defense, representative...)
     * @param zone     template zone (inner, center, outer, farm, border, mountain, water, forest, shore)
     */
    public boolean allows(final int x, final int z, final String category, final String zone, final String type) {
        final String here = this.zoneAt(x, z);
        if (here == null) {
            return this.options.autoFill;
        }
        return switch (here) {
            case "mieszkalna" -> "housing".equals(category);
            case "centrum" -> "civic".equals(category) || "representative".equals(category) || "infrastructure".equals(category)
                || "religious".equals(category);
            case "rzemieslnicza" -> "economy".equals(category) || "food".equals(category);
            case "rolnicza" -> "farm".equals(category) || "food".equals(category);
            case "las" -> "tartak".equals(type) || "forest".equals(zone); // also look variants of the sawmill (tartak_kryty)
            case "kopalnia" -> "mountain".equals(zone);
            case "port" -> "water".equals(zone);
            case "obronna" -> "defense".equals(category) || "military".equals(category);
            case "park" -> "park".equals(type) || "ogrody".equals(type);
            case "zabytki" -> false;
            default -> false; // nie_ruszac and anything unknown
        };
    }

    /** Cells of a polyline (4-connected), widened to {@code width}. */
    public static List<int[]> cells(final List<int[]> points, final int width, final boolean closed) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        if (points.size() == 1) {
            return List.of(points.get(0));
        }
        final List<int[]> line = WallRoute.rasterise(points, closed);
        if (width <= 1) {
            return line;
        }
        final Set<Long> seen = new LinkedHashSet<>();
        final List<int[]> out = new ArrayList<>();
        for (final int[] c : line) {
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < width; dz++) {
                    if (seen.add(RoadPlanner.key(c[0] + dx, c[1] + dz))) {
                        out.add(new int[] {c[0] + dx, c[1] + dz});
                    }
                }
            }
        }
        return out;
    }

    /** All road cells of the plan. */
    public Set<Long> roadCells() {
        final Set<Long> out = new LinkedHashSet<>();
        for (final Road r : this.roads) {
            for (final int[] c : cells(r.points, Math.max(1, Math.min(5, r.width)), false)) {
                out.add(RoadPlanner.key(c[0], c[1]));
            }
        }
        return out;
    }

    /** Structural warnings that need no world (Polish, one per element). */
    public List<String> check(final Set<String> knownTypes) {
        final List<String> out = new ArrayList<>();
        if (this.version != 1) {
            out.add("nieznana wersja planu " + this.version + " (obsługiwana: 1)");
        }
        for (int i = 0; i < this.zones.size(); i++) {
            final Zone z = this.zones.get(i);
            if (z.type == null || !ZONE_TYPES.contains(z.type.toLowerCase(Locale.ROOT))) {
                out.add("strefa #" + (i + 1) + ": nieznany typ " + z.type);
            }
            if (z.polygon == null || z.polygon.size() < 3) {
                out.add("strefa #" + (i + 1) + ": wielokąt musi mieć co najmniej 3 punkty");
            }
        }
        for (int i = 0; i < this.roads.size(); i++) {
            if (this.roads.get(i).points == null || this.roads.get(i).points.size() < 2) {
                out.add("droga #" + (i + 1) + ": potrzeba co najmniej 2 punktów");
            }
        }
        for (int i = 0; i < this.walls.size(); i++) {
            final Wall w = this.walls.get(i);
            final boolean auto = "follow-border".equals(w.mode) || "follow-town".equals(w.mode);
            if (w.mode != null && !auto) {
                out.add("mur #" + (i + 1) + ": nieznany tryb " + w.mode + " (follow-border, follow-town)");
            }
            if (!auto && (w.points == null || w.points.size() < 2)) {
                out.add("mur #" + (i + 1) + ": potrzeba co najmniej 2 punktów (albo mode)");
            }
        }
        for (int i = 0; i < this.buildings.size(); i++) {
            final Building b = this.buildings.get(i);
            if (b.type == null || !knownTypes.contains(b.type.toLowerCase(Locale.ROOT))) {
                out.add("budynek #" + (i + 1) + ": nieznany typ " + b.type);
            }
            if (b.rotation % 90 != 0) {
                out.add("budynek #" + (i + 1) + " (" + b.type + "): obrót musi być wielokrotnością 90");
            }
        }
        return out;
    }
}
