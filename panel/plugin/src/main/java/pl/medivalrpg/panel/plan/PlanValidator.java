// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation and normalisation of an NPC-country design plan (PANEL.md "Edytor planu państwa NPC", format version 1)
 * before it is handed to Ludnosc. Only known fields survive, numbers become integers and defaults are filled in, so
 * Ludnosc always gets a canonical document. Errors reject the plan; warnings are returned to the editor. Every issue
 * names its element as {@code zones[3]}, {@code roads[0]}, {@code walls[1]} or {@code buildings[12]} (0-based), which
 * the web editor uses to mark the element. Pure (Gson only), no Bukkit.
 */
public final class PlanValidator {

    public static final int VERSION = 1;
    public static final int MAX_ZONES = 200;
    public static final int MAX_ROADS = 200;
    public static final int MAX_WALLS = 50;
    public static final int MAX_BUILDINGS = 500;
    public static final int MAX_POINTS_PER_ELEMENT = 256;
    public static final int MAX_POINTS_TOTAL = 20_000;
    public static final int MAX_GATES = 32;
    public static final int MAX_ROAD_WIDTH = 9;
    /** Ludnosc clamps the wall inset to 0..7 (WallRoute.border). */
    public static final int MAX_INSET = 7;
    public static final int MAX_PRIORITY = 100;
    public static final int DEFAULT_PRIORITY = 50;
    /** A gate counts as "on the wall" up to this many blocks from the wall line. */
    public static final double GATE_TOLERANCE = 2.5;
    public static final int DEFAULT_MARGIN = 32;
    private static final int COORD_MAX = 29_999_000;
    /** Errors listed in the RPC error message; the rest is summarised. */
    private static final int MESSAGE_ERRORS = 8;

    public static final List<String> ZONE_TYPES = List.of("mieszkalna", "centrum", "rzemieslnicza", "rolnicza", "las", "kopalnia",
        "port", "obronna", "park", "zabytki", "nie_ruszac");
    public static final List<String> ROAD_KINDS = List.of("glowna", "boczna", "trakt");
    public static final List<String> WALL_MODES = List.of("points", "follow-border", "follow-town");
    public static final List<Integer> ROTATIONS = List.of(0, 90, 180, 270);
    static final Map<String, Integer> ROAD_WIDTH = Map.of("glowna", 5, "boczna", 3, "trakt", 2);
    static final Map<String, Integer> INSET = Map.of("follow-border", 2, "follow-town", 2);
    static final List<String> KINDS = List.of("zones", "roads", "walls", "buildings");
    /** Ludnosc's own warnings: {@code budynek #3 (dom): …}, {@code droga #1: …} (numbered from 1). */
    private static final Pattern REF_PL = Pattern.compile("^\\s*(strefa|droga|mur|budynek)\\s*#(\\d{1,4})\\s*(\\([^)]*\\))?\\s*[:\\-–—]?\\s*(.*)$", Pattern.DOTALL);
    private static final Map<String, String> KIND_PL = Map.of("strefa", "zones", "droga", "roads", "mur", "walls", "budynek", "buildings");
    private static final Pattern REF = Pattern.compile("^\\s*(zones|roads|walls|buildings)\\[(\\d{1,4})]\\s*[:\\-–—]?\\s*(.*)$", Pattern.DOTALL);

    /** A building type as Ludnosc reports it ({@code buildingRequirements}). */
    public record TypeInfo(String type, String name, boolean available, List<String> missing, int built, int max) {
    }

    /**
     * Problem with one element ({@code kind} null = the plan as a whole).
     *
     * @param kind  zones / roads / walls / buildings, or null
     * @param index 0-based index in that list (-1 with a null kind)
     */
    public record Issue(String kind, int index, String text) {

        public String ref() {
            return this.kind == null ? "" : this.kind + "[" + this.index + "]";
        }

        @Override
        public String toString() {
            return this.kind == null ? this.text : this.ref() + ": " + this.text;
        }

        /** JSON for the editor: {@code {ref:{kind,index}|null, text}}. */
        public Map<String, Object> json() {
            final Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ref", this.kind == null ? null : Map.of("kind", this.kind, "index", this.index));
            m.put("text", this.text);
            return m;
        }

        /** Parses a Ludnosc warning that may start with {@code buildings[3]: …}. */
        public static Issue parse(final String raw) {
            final String s = raw == null ? "" : raw.trim();
            final Matcher m = REF.matcher(s);
            if (m.matches()) {
                return new Issue(m.group(1), Integer.parseInt(m.group(2)), m.group(3).isBlank() ? s : m.group(3).trim());
            }
            final Matcher pl = REF_PL.matcher(s);
            if (pl.matches() && Integer.parseInt(pl.group(2)) >= 1) {
                return new Issue(KIND_PL.get(pl.group(1)), Integer.parseInt(pl.group(2)) - 1, pl.group(4).isBlank() ? s : pl.group(4).trim());
            }
            return new Issue(null, -1, s);
        }
    }

    /** Outcome: the canonical plan (null when there are errors), errors and warnings. */
    public record Result(JsonObject plan, List<Issue> errors, List<Issue> warnings) {

        public boolean ok() {
            return this.errors.isEmpty();
        }

        /** Polish RPC error naming the first errors. */
        public String message() {
            final StringBuilder b = new StringBuilder("Plan ma błędy (").append(this.errors.size()).append("): ");
            for (int i = 0; i < Math.min(MESSAGE_ERRORS, this.errors.size()); i++) {
                if (i > 0) {
                    b.append("; ");
                }
                b.append(this.errors.get(i));
            }
            if (this.errors.size() > MESSAGE_ERRORS) {
                b.append("; … i ").append(this.errors.size() - MESSAGE_ERRORS).append(" więcej");
            }
            return b.toString();
        }
    }

    private final Set<Long> land;
    private final int margin;
    private final Map<String, TypeInfo> types;
    private final List<Issue> errors = new ArrayList<>();
    private final List<Issue> warnings = new ArrayList<>();
    private int totalPoints;

    /**
     * @param land   chunks of the country (Kraje keys) in its world
     * @param margin blocks outside the land where points are still accepted (with a warning)
     * @param types  building types by lower-case id; empty = every {@code [a-z0-9_-]} id is accepted
     */
    public PlanValidator(final Set<Long> land, final int margin, final Map<String, TypeInfo> types) {
        this.land = land;
        this.margin = Math.max(0, margin);
        this.types = types == null ? Map.of() : types;
    }

    public static Result validate(final JsonElement plan, final Set<Long> land, final int margin, final Map<String, TypeInfo> types) {
        return new PlanValidator(land, margin, types).run(plan);
    }

    Result run(final JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            this.errors.add(new Issue(null, -1, "plan musi być obiektem JSON"));
            return new Result(null, this.errors, this.warnings);
        }
        final JsonObject in = raw.getAsJsonObject();
        final JsonElement v = in.get("version");
        if (v != null && !v.isJsonNull() && !(isInt(v) && v.getAsInt() == VERSION)) {
            this.errors.add(new Issue(null, -1, "nieobsługiwana wersja planu (dozwolona: " + VERSION + ")"));
        }
        final JsonObject out = new JsonObject();
        out.addProperty("version", VERSION);
        final JsonArray zones = this.list(in, "zones", MAX_ZONES);
        final JsonArray roads = this.list(in, "roads", MAX_ROADS);
        final JsonArray walls = this.list(in, "walls", MAX_WALLS);
        final JsonArray buildings = this.list(in, "buildings", MAX_BUILDINGS);
        final JsonArray zOut = new JsonArray();
        final List<int[][]> zonePolys = new ArrayList<>();
        final List<String> zoneTypes = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            final JsonObject z = this.zone(zones.get(i), i);
            if (z != null) {
                zOut.add(z);
                zonePolys.add(points(z.getAsJsonArray("polygon")));
                zoneTypes.add(z.get("type").getAsString());
            }
        }
        final JsonArray rOut = new JsonArray();
        for (int i = 0; i < roads.size(); i++) {
            final JsonObject r = this.road(roads.get(i), i);
            if (r != null) {
                rOut.add(r);
            }
        }
        final JsonArray wOut = new JsonArray();
        final Map<String, Integer> autoWalls = new HashMap<>();
        for (int i = 0; i < walls.size(); i++) {
            final JsonObject w = this.wall(walls.get(i), i, autoWalls);
            if (w != null) {
                wOut.add(w);
            }
        }
        final JsonArray bOut = new JsonArray();
        final List<int[]> footprints = new ArrayList<>();
        final Map<String, Integer> perType = new HashMap<>();
        for (int i = 0; i < buildings.size(); i++) {
            final JsonObject b = this.building(buildings.get(i), i, zonePolys, zoneTypes, footprints, perType);
            if (b != null) {
                bOut.add(b);
            }
        }
        if (this.totalPoints > MAX_POINTS_TOTAL) {
            this.errors.add(new Issue(null, -1, "za dużo punktów w planie (" + this.totalPoints + ", najwyżej " + MAX_POINTS_TOTAL + ")"));
        }
        out.add("zones", zOut);
        out.add("roads", rOut);
        out.add("walls", wOut);
        out.add("buildings", bOut);
        out.add("options", this.options(in.get("options")));
        return new Result(this.errors.isEmpty() ? out : null, this.errors, this.warnings);
    }

    // ------------------------------------------------------------------ lists and scalars

    private JsonArray list(final JsonObject in, final String name, final int max) {
        final JsonElement e = in.get(name);
        if (e == null || e.isJsonNull()) {
            return new JsonArray();
        }
        if (!e.isJsonArray()) {
            this.errors.add(new Issue(null, -1, "„" + name + "” musi być listą"));
            return new JsonArray();
        }
        final JsonArray a = e.getAsJsonArray();
        if (a.size() > max) {
            this.errors.add(new Issue(null, -1, "za dużo elementów „" + name + "”: " + a.size() + " (najwyżej " + max + ")"));
            final JsonArray cut = new JsonArray();
            for (int i = 0; i < max; i++) {
                cut.add(a.get(i));
            }
            return cut;
        }
        return a;
    }

    static boolean isInt(final JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        final double d = e.getAsDouble();
        return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE;
    }

    private String string(final JsonObject o, final String name) {
        final JsonElement e = o.get(name);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString().trim().toLowerCase(Locale.ROOT) : null;
    }

    /** Integer field in [min, max]; {@code def} when missing (null def = required). Null on error (reported). */
    private Integer integer(final JsonObject o, final String name, final int min, final int max, final Integer def, final String kind, final int index, final String label) {
        final JsonElement e = o.get(name);
        if (e == null || e.isJsonNull()) {
            if (def == null) {
                this.errors.add(new Issue(kind, index, "brak pola „" + name + "”"));
            }
            return def;
        }
        if (!isInt(e) || e.getAsInt() < min || e.getAsInt() > max) {
            this.errors.add(new Issue(kind, index, label + ": liczba całkowita " + min + "–" + max));
            return null;
        }
        return e.getAsInt();
    }

    private Boolean bool(final JsonObject o, final String name, final boolean def, final String kind, final int index) {
        final JsonElement e = o.get(name);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            this.errors.add(new Issue(kind, index, "„" + name + "” musi być true albo false"));
            return null;
        }
        return e.getAsBoolean();
    }

    /**
     * Point list of {@code o.name}: [[x,z],...], consecutive duplicates removed. Null on error (reported).
     *
     * @param minPoints minimum after de-duplication (0 = may be empty)
     */
    private int[][] pointList(final JsonObject o, final String name, final int minPoints, final int maxPoints, final String kind, final int index, final String what) {
        final JsonElement e = o.get(name);
        if (e == null || e.isJsonNull()) {
            if (minPoints > 0) {
                this.errors.add(new Issue(kind, index, "brak pola „" + name + "”"));
                return null;
            }
            return new int[0][];
        }
        if (!e.isJsonArray()) {
            this.errors.add(new Issue(kind, index, "„" + name + "” musi być listą punktów [[x,z],…]"));
            return null;
        }
        final JsonArray a = e.getAsJsonArray();
        if (a.size() > maxPoints) {
            this.errors.add(new Issue(kind, index, "za dużo punktów (" + a.size() + ", najwyżej " + maxPoints + ")"));
            return null;
        }
        final List<int[]> pts = new ArrayList<>();
        for (final JsonElement p : a) {
            if (!p.isJsonArray() || p.getAsJsonArray().size() != 2 || !isInt(p.getAsJsonArray().get(0)) || !isInt(p.getAsJsonArray().get(1))) {
                this.errors.add(new Issue(kind, index, "punkt musi mieć postać [x, z] z liczb całkowitych"));
                return null;
            }
            final int x = p.getAsJsonArray().get(0).getAsInt();
            final int z = p.getAsJsonArray().get(1).getAsInt();
            if (Math.abs(x) > COORD_MAX || Math.abs(z) > COORD_MAX) {
                this.errors.add(new Issue(kind, index, "współrzędne poza światem"));
                return null;
            }
            if (!pts.isEmpty() && pts.get(pts.size() - 1)[0] == x && pts.get(pts.size() - 1)[1] == z) {
                continue;
            }
            pts.add(new int[] {x, z});
        }
        if (pts.size() < minPoints) {
            this.errors.add(new Issue(kind, index, what + " potrzebuje co najmniej " + minPoints + " różnych punktów"));
            return null;
        }
        this.totalPoints += pts.size();
        return pts.toArray(new int[0][]);
    }

    /** Checks the points against the land: beyond the margin = error, beyond the land = one warning. */
    private boolean onLand(final int[][] pts, final String kind, final int index, final String what) {
        boolean outside = false;
        for (final int[] p : pts) {
            if (!ChunkGeometry.nearChunks(this.land, p[0], p[1], this.margin)) {
                this.errors.add(new Issue(kind, index, "punkt (" + p[0] + ", " + p[1] + ") leży poza ziemią kraju i marginesem " + this.margin + " bl."));
                return false;
            }
            if (!this.land.contains(ChunkGeometry.key(p[0] >> 4, p[1] >> 4))) {
                outside = true;
            }
        }
        if (outside) {
            this.warnings.add(new Issue(kind, index, what + " wychodzi poza ziemię kraju — ta część zostanie pominięta, dopóki kraj jej nie zajmie"));
        }
        return true;
    }

    private static JsonArray toJson(final int[][] pts) {
        final JsonArray a = new JsonArray();
        for (final int[] p : pts) {
            final JsonArray q = new JsonArray();
            q.add(new JsonPrimitive(p[0]));
            q.add(new JsonPrimitive(p[1]));
            a.add(q);
        }
        return a;
    }

    static int[][] points(final JsonArray a) {
        final int[][] out = new int[a.size()][];
        for (int i = 0; i < a.size(); i++) {
            out[i] = new int[] {a.get(i).getAsJsonArray().get(0).getAsInt(), a.get(i).getAsJsonArray().get(1).getAsInt()};
        }
        return out;
    }

    // ------------------------------------------------------------------ elements

    private JsonObject object(final JsonElement e, final String kind, final int index) {
        if (e == null || !e.isJsonObject()) {
            this.errors.add(new Issue(kind, index, "element musi być obiektem JSON"));
            return null;
        }
        return e.getAsJsonObject();
    }

    private JsonObject zone(final JsonElement e, final int i) {
        final JsonObject o = this.object(e, "zones", i);
        if (o == null) {
            return null;
        }
        final String type = this.string(o, "type");
        if (type == null || !ZONE_TYPES.contains(type)) {
            this.errors.add(new Issue("zones", i, "nieznany typ strefy" + (type == null ? "" : " „" + type + "”") + " (dozwolone: " + String.join(", ", ZONE_TYPES) + ")"));
            return null;
        }
        int[][] pts = this.pointList(o, "polygon", 3, MAX_POINTS_PER_ELEMENT, "zones", i, "strefa");
        if (pts == null) {
            return null;
        }
        if (pts.length > 3 && pts[0][0] == pts[pts.length - 1][0] && pts[0][1] == pts[pts.length - 1][1]) {
            pts = java.util.Arrays.copyOf(pts, pts.length - 1); // closing point repeated
        }
        if (Math.abs(Geometry.area(pts)) < 1) {
            this.errors.add(new Issue("zones", i, "strefa ma zerową powierzchnię"));
            return null;
        }
        if (!this.onLand(pts, "zones", i, "strefa")) {
            return null;
        }
        if (Geometry.selfIntersects(pts)) {
            this.warnings.add(new Issue("zones", i, "brzeg strefy przecina sam siebie — planer może ją odczytać inaczej, niż wygląda"));
        }
        final JsonObject z = new JsonObject();
        z.addProperty("type", type);
        z.add("polygon", toJson(pts));
        return z;
    }

    private JsonObject road(final JsonElement e, final int i) {
        final JsonObject o = this.object(e, "roads", i);
        if (o == null) {
            return null;
        }
        final String kind = this.string(o, "kind");
        if (kind == null || !ROAD_KINDS.contains(kind)) {
            this.errors.add(new Issue("roads", i, "nieznany rodzaj drogi (dozwolone: " + String.join(", ", ROAD_KINDS) + ")"));
            return null;
        }
        final Integer width = this.integer(o, "width", 1, MAX_ROAD_WIDTH, ROAD_WIDTH.get(kind), "roads", i, "szerokość");
        final int[][] pts = this.pointList(o, "points", 2, MAX_POINTS_PER_ELEMENT, "roads", i, "droga");
        if (width == null || pts == null || !this.onLand(pts, "roads", i, "droga")) {
            return null;
        }
        final JsonObject r = new JsonObject();
        r.addProperty("kind", kind);
        r.add("points", toJson(pts));
        r.addProperty("width", width);
        return r;
    }

    private JsonObject wall(final JsonElement e, final int i, final Map<String, Integer> autoWalls) {
        final JsonObject o = this.object(e, "walls", i);
        if (o == null) {
            return null;
        }
        final String raw = this.string(o, "mode");
        final String mode = raw == null ? "points" : raw;
        if (!WALL_MODES.contains(mode)) {
            this.errors.add(new Issue("walls", i, "nieznany tryb muru „" + mode + "” (dozwolone: " + String.join(", ", WALL_MODES) + ")"));
            return null;
        }
        final JsonObject w = new JsonObject();
        w.addProperty("mode", mode);
        final int[][] gates = this.pointList(o, "gates", 0, MAX_GATES, "walls", i, "mur");
        if (gates == null) {
            return null;
        }
        if ("points".equals(mode)) {
            final Boolean closed = this.bool(o, "closed", false, "walls", i);
            if (closed == null) {
                return null;
            }
            final int[][] pts = this.pointList(o, "points", closed ? 3 : 2, MAX_POINTS_PER_ELEMENT, "walls", i, closed ? "zamknięty mur" : "mur");
            if (pts == null || !this.onLand(pts, "walls", i, "mur")) {
                return null;
            }
            for (final int[] g : gates) {
                if (Geometry.distanceToPolyline(pts, closed, g[0], g[1]) > GATE_TOLERANCE) {
                    this.errors.add(new Issue("walls", i, "brama (" + g[0] + ", " + g[1] + ") nie leży na murze"));
                    return null;
                }
            }
            w.add("points", toJson(pts));
            w.addProperty("closed", closed);
        } else {
            final Integer prev = autoWalls.putIfAbsent(mode, i);
            if (prev != null) {
                this.errors.add(new Issue("walls", i, "plan może mieć tylko jeden mur „" + ("follow-border".equals(mode) ? "wzdłuż granicy" : "wokół miasta") + "” (jest już walls[" + prev + "])"));
                return null;
            }
            final JsonElement pe = o.get("points");
            if (pe != null && pe.isJsonArray() && !pe.getAsJsonArray().isEmpty()) {
                this.warnings.add(new Issue("walls", i, "mur automatyczny nie używa punktów — pominięto je"));
            }
            final Integer inset = this.integer(o, "inset", 0, MAX_INSET, INSET.get(mode), "walls", i, "odstęp (inset)");
            if (inset == null) {
                return null;
            }
            for (final int[] g : gates) {
                if (!ChunkGeometry.nearChunks(this.land, g[0], g[1], this.margin)) {
                    this.errors.add(new Issue("walls", i, "brama (" + g[0] + ", " + g[1] + ") leży poza ziemią kraju i marginesem " + this.margin + " bl."));
                    return null;
                }
            }
            w.add("points", new JsonArray());
            w.addProperty("closed", true);
            w.addProperty("inset", inset);
        }
        w.add("gates", toJson(gates));
        return w;
    }

    private JsonObject building(final JsonElement e, final int i, final List<int[][]> zones, final List<String> zoneTypes,
                                final List<int[]> footprints, final Map<String, Integer> perType) {
        final JsonObject o = this.object(e, "buildings", i);
        if (o == null) {
            footprints.add(null);
            return null;
        }
        final String type = this.string(o, "type");
        final TypeInfo info = type == null ? null : this.types.get(type);
        if (type == null || !type.matches("[a-z0-9_-]{1,32}") || (!this.types.isEmpty() && info == null)) {
            this.errors.add(new Issue("buildings", i, "nieznany typ budynku" + (type == null ? "" : " „" + type + "”")));
            footprints.add(null);
            return null;
        }
        final Integer x = this.integer(o, "x", -COORD_MAX, COORD_MAX, null, "buildings", i, "x");
        final Integer z = this.integer(o, "z", -COORD_MAX, COORD_MAX, null, "buildings", i, "z");
        final Integer rotation = this.integer(o, "rotation", 0, 270, 0, "buildings", i, "obrót");
        final Integer priority = this.integer(o, "priority", 1, MAX_PRIORITY, DEFAULT_PRIORITY, "buildings", i, "priorytet");
        if (x == null || z == null || rotation == null || priority == null) {
            footprints.add(null);
            return null;
        }
        if (!ROTATIONS.contains(rotation)) {
            this.errors.add(new Issue("buildings", i, "obrót musi wynosić 0, 90, 180 albo 270"));
            footprints.add(null);
            return null;
        }
        final String name = info == null ? type : info.name();
        if (!ChunkGeometry.nearChunks(this.land, x, z, this.margin)) {
            this.errors.add(new Issue("buildings", i, name + " (" + x + ", " + z + ") leży poza ziemią kraju i marginesem " + this.margin + " bl."));
            footprints.add(null);
            return null;
        }
        this.totalPoints++;
        final int[] fp = BuildingCatalog.footprint(type, x, z, rotation);
        if (!this.footprintOnLand(fp)) {
            this.warnings.add(new Issue("buildings", i, name + " wychodzi poza ziemię kraju — powstanie, gdy kraj zajmie ten teren"));
        }
        if (info != null && !info.available()) {
            this.warnings.add(new Issue("buildings", i, name + " — teraz niedostępny" + (info.missing() == null || info.missing().isEmpty() ? "" : " (brakuje: " + String.join(", ", info.missing()) + ")")
                + "; czeka w planie na spełnienie wymagań"));
        }
        final int n = perType.merge(type, 1, Integer::sum);
        if (info != null && info.max() > 0 && info.built() + n > info.max()) {
            this.warnings.add(new Issue("buildings", i, name + " — przekroczony limit " + info.max() + " na kraj (zbudowane: " + info.built() + ")"));
        }
        for (int j = 0; j < footprints.size(); j++) {
            final int[] other = footprints.get(j);
            if (other != null && other[0] <= fp[2] && fp[0] <= other[2] && other[1] <= fp[3] && fp[1] <= other[3]) {
                this.warnings.add(new Issue("buildings", i, name + " nachodzi na budynek buildings[" + j + "]"));
                break;
            }
        }
        footprints.add(fp);
        for (int j = zones.size() - 1; j >= 0; j--) {
            if (Geometry.contains(zones.get(j), x + 0.5, z + 0.5)) {
                final String zt = zoneTypes.get(j);
                if ("nie_ruszac".equals(zt) || "las".equals(zt) || "zabytki".equals(zt)) {
                    this.warnings.add(new Issue("buildings", i, name + " stoi w strefie „" + zt.replace('_', ' ') + "” (zones[" + j + "]) — planer może go nie postawić"));
                }
                break;
            }
        }
        final JsonObject b = new JsonObject();
        b.addProperty("type", type);
        b.addProperty("x", x);
        b.addProperty("z", z);
        b.addProperty("rotation", rotation);
        b.addProperty("priority", priority);
        return b;
    }

    private boolean footprintOnLand(final int[] fp) {
        for (int cx = fp[0] >> 4; cx <= fp[2] >> 4; cx++) {
            for (int cz = fp[1] >> 4; cz <= fp[3] >> 4; cz++) {
                if (!this.land.contains(ChunkGeometry.key(cx, cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private JsonObject options(final JsonElement e) {
        final JsonObject in = e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        if (e != null && !e.isJsonNull() && !e.isJsonObject()) {
            this.errors.add(new Issue(null, -1, "„options” musi być obiektem"));
        }
        final JsonObject out = new JsonObject();
        final Boolean demolish = this.bool(in, "allowDemolish", false, null, -1);
        final Boolean keep = this.bool(in, "keepExisting", true, null, -1);
        final Boolean fill = this.bool(in, "autoFill", true, null, -1);
        out.addProperty("allowDemolish", demolish != null && demolish);
        out.addProperty("keepExisting", keep == null || keep);
        out.addProperty("autoFill", fill == null || fill);
        return out;
    }

    /** Ludnosc warnings ({@code setDesignPlan}) as issues. */
    public static List<Issue> parseWarnings(final List<String> raw) {
        final List<Issue> out = new ArrayList<>();
        if (raw != null) {
            for (final String s : raw) {
                if (s != null && !s.isBlank()) {
                    out.add(Issue.parse(s));
                }
            }
        }
        return out;
    }
}
