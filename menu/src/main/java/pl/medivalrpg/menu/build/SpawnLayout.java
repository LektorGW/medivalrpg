// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code markers.json} of the spawn schematic (see {@code spawn/README.md}): size, WorldEdit offset and marker
 * positions relative to the paste point. The paste point P is the block the admin stands in; the lodestone of the
 * compass rose is at P − (0, 1, 0), the schematic spans P + offset … P + offset + size − 1. Pure.
 */
public record SpawnLayout(int sizeX, int sizeY, int sizeZ, int offsetX, int offsetY, int offsetZ,
                          Map<String, int[]> markers) {

    /** Integer block position. */
    public record Pos(int x, int y, int z) {
        public Pos plus(final int dx, final int dy, final int dz) {
            return new Pos(this.x + dx, this.y + dy, this.z + dz);
        }
    }

    public static SpawnLayout parse(final String json) {
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        final int[] size = ints(root.getAsJsonArray("size"));
        final int[] offset = ints(root.getAsJsonArray("offset"));
        final Map<String, int[]> markers = new LinkedHashMap<>();
        final JsonElement m = root.get("markers");
        if (m != null && m.isJsonObject()) {
            for (final Map.Entry<String, JsonElement> e : m.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("relative_to_paste_point")) {
                    markers.put(e.getKey(), ints(e.getValue().getAsJsonObject().getAsJsonArray("relative_to_paste_point")));
                }
            }
        }
        if (size[0] <= 0 || size[1] <= 0 || size[2] <= 0) {
            throw new IllegalArgumentException("size <= 0");
        }
        return new SpawnLayout(size[0], size[1], size[2], offset[0], offset[1], offset[2], Map.copyOf(markers));
    }

    private static int[] ints(final JsonArray a) {
        if (a == null || a.size() != 3) {
            throw new IllegalArgumentException("oczekiwano [x, y, z]");
        }
        return new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    /** Blocks the paste overwrites (whole schematic incl. foundation and air). */
    public Box box(final Pos paste) {
        final int minX = paste.x() + this.offsetX;
        final int minY = paste.y() + this.offsetY;
        final int minZ = paste.z() + this.offsetZ;
        return new Box(minX, minY, minZ, minX + this.sizeX - 1, minY + this.sizeY - 1, minZ + this.sizeZ - 1);
    }

    /** WorldGuard region: schematic footprint + {@code margin}, whole world height. */
    public Box region(final Pos paste, final int margin, final int worldMinY, final int worldMaxY) {
        return this.box(paste).expandHorizontally(Math.max(0, margin), worldMinY, worldMaxY);
    }

    /** Ground (grass / paving) level of the schematic edge: one block below the paste point. */
    public int groundY(final Pos paste) {
        return paste.y() - 1;
    }

    /** World position of a marker (the carpet for NPCs, the block above the lodestone for "spawn"). */
    public Optional<Pos> marker(final String name, final Pos paste) {
        final int[] rel = this.markers.get(name);
        return rel == null ? Optional.empty() : Optional.of(paste.plus(rel[0], rel[1], rel[2]));
    }
}
