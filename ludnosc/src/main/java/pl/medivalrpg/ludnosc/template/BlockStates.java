// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure helpers for vanilla block-state strings such as {@code minecraft:oak_stairs[facing=north,half=bottom]}.
 * No Bukkit calls, so rotation can be unit tested; at runtime the rotated string is turned into {@code BlockData}
 * once per template and cross-checked against {@code BlockData#rotate} (see {@code TemplateLibrary}).
 *
 * <p>Rotation is clockwise seen from above, in quarter turns (north → east → south → west). It rotates every
 * direction-valued property used by {@code Directional}/{@code Rotatable}/{@code Orientable}/{@code MultipleFacing}
 * blocks: {@code facing}, {@code rotation} (0–15), {@code axis}, the side properties {@code north/east/south/west}
 * (fences, panes, walls, vines, redstone) and rail {@code shape}. Relative properties ({@code hinge}, stair
 * {@code shape}, chest {@code type}, {@code half}, {@code part}) stay unchanged — a rotation keeps handedness.</p>
 */
public final class BlockStates {

    public static final String AIR = "minecraft:air";
    /** Placeholder for the country colour in block ids, e.g. {@code {kolor}_wool} → {@code blue_wool}. */
    public static final String COLOR = "{kolor}";
    /** Dye used for {@link #COLOR} when nothing else is known. */
    public static final String DEFAULT_DYE = "red";

    /** Horizontal directions in clockwise order. */
    static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    /** Rails whose missing {@code shape} means the vanilla default {@code north_south}. */
    static final java.util.Set<String> RAILS = java.util.Set.of("minecraft:rail", "minecraft:powered_rail",
        "minecraft:detector_rail", "minecraft:activator_rail");

    private BlockStates() {
    }

    /** Parsed block state: namespaced id and properties in their original order. */
    public record Parsed(String id, Map<String, String> properties) {

        public String property(final String key) {
            return this.properties.get(key);
        }

        /** Block id without the namespace, e.g. {@code oak_stairs}. */
        public String path() {
            final int colon = this.id.indexOf(':');
            return colon < 0 ? this.id : this.id.substring(colon + 1);
        }

        public String format() {
            if (this.properties.isEmpty()) {
                return this.id;
            }
            final StringBuilder sb = new StringBuilder(this.id).append('[');
            boolean first = true;
            for (final Map.Entry<String, String> e : this.properties.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            return sb.append(']').toString();
        }
    }

    /** Parses {@code [namespace:]id[key=value,...]}; adds the {@code minecraft:} namespace and lower-cases everything. */
    public static Parsed parse(final String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("Pusty stan bloku");
        }
        String s = state.trim().toLowerCase(Locale.ROOT);
        final Map<String, String> props = new LinkedHashMap<>();
        final int bracket = s.indexOf('[');
        String id = s;
        if (bracket >= 0) {
            if (!s.endsWith("]")) {
                throw new IllegalArgumentException("Brak ']' w stanie bloku: " + state);
            }
            id = s.substring(0, bracket);
            final String inner = s.substring(bracket + 1, s.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (final String pair : inner.split(",")) {
                    final int eq = pair.indexOf('=');
                    if (eq <= 0 || eq == pair.length() - 1) {
                        throw new IllegalArgumentException("Zła właściwość '" + pair + "' w stanie bloku: " + state);
                    }
                    props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        id = id.trim();
        if (id.isEmpty() || !id.matches("[a-z0-9_.\\-{}@]+(:[a-z0-9_./\\-{}@]+)?")) {
            throw new IllegalArgumentException("Złe id bloku: " + state);
        }
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        return new Parsed(id, props);
    }

    /** Normalised string form (namespace added, lower case). */
    public static String normalize(final String state) {
        return parse(state).format();
    }

    /** Replaces the country colour placeholder with a dye name. */
    public static String colorize(final String state, final String dye) {
        return state == null ? null : state.replace(COLOR, dye == null || dye.isBlank() ? DEFAULT_DYE : dye);
    }

    public static boolean isAir(final String state) {
        return state != null && (state.equals(AIR) || state.startsWith("minecraft:air[")
            || state.equals("minecraft:cave_air") || state.equals("minecraft:void_air"));
    }

    // ------------------------------------------------------------------ directions

    /** Rotates a horizontal direction name clockwise by {@code quarterTurns}; other values (up/down) unchanged. */
    public static String rotateDirection(final String dir, final int quarterTurns) {
        final int i = HORIZONTAL.indexOf(dir);
        if (i < 0) {
            return dir;
        }
        return HORIZONTAL.get(Math.floorMod(i + quarterTurns, 4));
    }

    /** Unit vector (dx, dz) of a horizontal direction; north = -z. */
    public static int[] vector(final String dir) {
        return switch (dir) {
            case "north" -> new int[] {0, -1};
            case "south" -> new int[] {0, 1};
            case "east" -> new int[] {1, 0};
            case "west" -> new int[] {-1, 0};
            default -> new int[] {0, 0};
        };
    }

    /** Normalises any angle to 0..3 clockwise quarter turns (rounded to the nearest quarter). */
    public static int quarterTurns(final int degrees) {
        return Math.floorMod(Math.round(degrees / 90f), 4);
    }

    // ------------------------------------------------------------------ rotation

    /** Rotates a full state string clockwise by {@code quarterTurns}. */
    public static String rotate(final String state, final int quarterTurns) {
        final int q = Math.floorMod(quarterTurns, 4);
        final Parsed parsed = parse(state);
        final boolean implicitRail = RAILS.contains(parsed.id()) && !parsed.properties().containsKey("shape");
        if (q == 0 || parsed.properties().isEmpty() && !implicitRail || implicitRail && q == 2) {
            // a bare rail is north_south (vanilla default): a half turn keeps it, so the state string stays unchanged
            return parsed.format();
        }
        final Map<String, String> in = new LinkedHashMap<>(parsed.properties());
        if (implicitRail) {
            in.put("shape", "north_south"); // the server's default, rotated like every explicit shape
        }
        final Map<String, String> out = new LinkedHashMap<>();
        // Side properties are permuted: the value that was on side D moves to side rot(D).
        final Map<String, String> sides = new LinkedHashMap<>();
        for (final String dir : HORIZONTAL) {
            if (in.containsKey(dir)) {
                sides.put(rotateDirection(dir, q), in.get(dir));
            }
        }
        for (final Map.Entry<String, String> e : in.entrySet()) {
            final String key = e.getKey();
            final String value = e.getValue();
            if (HORIZONTAL.contains(key)) {
                continue; // added below, permuted
            }
            out.put(key, switch (key) {
                case "facing", "horizontal_facing" -> rotateDirection(value, q);
                case "rotation" -> rotateSixteenth(value, q);
                case "axis" -> rotateAxis(value, q);
                case "shape" -> rotateRailShape(value, q);
                default -> value;
            });
        }
        for (final String dir : HORIZONTAL) {
            if (sides.containsKey(dir)) {
                out.put(dir, sides.get(dir));
            }
        }
        if (RAILS.contains(parsed.id()) && "north_south".equals(out.get("shape"))) {
            out.remove("shape"); // the vanilla default: four quarter turns of a bare rail give the same template string
        }
        return new Parsed(parsed.id(), out).format();
    }

    private static String rotateSixteenth(final String value, final int q) {
        try {
            return Integer.toString(Math.floorMod(Integer.parseInt(value) + 4 * q, 16));
        } catch (final NumberFormatException e) {
            return value;
        }
    }

    private static String rotateAxis(final String value, final int q) {
        if (q % 2 == 0) {
            return value;
        }
        return switch (value) {
            case "x" -> "z";
            case "z" -> "x";
            default -> value;
        };
    }

    /**
     * Rail shapes ({@code north_south}, {@code ascending_east}, {@code south_west}...). Stair shapes
     * ({@code straight}, {@code inner_left}...) are relative and are returned unchanged.
     */
    static String rotateRailShape(final String value, final int q) {
        if (value.startsWith("ascending_")) {
            return "ascending_" + rotateDirection(value.substring("ascending_".length()), q);
        }
        final String[] parts = value.split("_");
        if (parts.length == 2 && HORIZONTAL.contains(parts[0]) && HORIZONTAL.contains(parts[1])) {
            final String a = rotateDirection(parts[0], q);
            final String b = rotateDirection(parts[1], q);
            // vanilla names: north_south, east_west, south_east, south_west, north_west, north_east
            if (isAxisPair(a, b)) {
                return (a.equals("north") || a.equals("south")) ? "north_south" : "east_west";
            }
            final String ns = a.equals("north") || a.equals("south") ? a : b;
            final String ew = ns.equals(a) ? b : a;
            return ns + "_" + ew;
        }
        return value;
    }

    private static boolean isAxisPair(final String a, final String b) {
        return HORIZONTAL.indexOf(a) % 2 == HORIZONTAL.indexOf(b) % 2;
    }
}
