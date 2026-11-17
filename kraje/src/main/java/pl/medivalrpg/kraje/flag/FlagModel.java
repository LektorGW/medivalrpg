// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Generator of the BetterModel banner (Blockbench 5 "free" model, pure Java, deterministic): a two-piece wooden pole
 * with a gilded finial and a cloth of {@link #STRIPS} vertical strips chained one into another. The looping animation
 * {@value #ANIMATION} turns every strip a little around Y with a phase shift, so the chain forms a travelling wave.
 *
 * <p>Texture atlas 64×64: rows 0–39 are the flag ({@link FlagImage#WIDTH}×{@link FlagImage#HEIGHT}), rows 40–63 the
 * pole wood and the finial. Units: 16 = one block; the pole stands on the model origin, the cloth hangs from its top
 * towards +X (top edge {@value #CLOTH_TOP}/16 block, bottom {@value #CLOTH_BOTTOM}/16).
 */
public final class FlagModel {

    public static final String ANIMATION = "powiewanie";
    public static final int STRIPS = 8;
    public static final int ATLAS = 64;
    /** Bone names; the cloth bones are {@code plotno_0..7} (tinted for the default flag), pole bones are never tinted. */
    public static final String POLE_LOW = "maszt";
    public static final String POLE_HIGH = "maszt_gora";
    public static final String FINIAL = "glowica";
    public static final String CLOTH_PREFIX = "plotno_";
    public static final double CLOTH_TOP = 62;
    public static final double CLOTH_BOTTOM = 42;
    public static final double CLOTH_WIDTH = 32;
    public static final double POLE_HEIGHT = 64;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private FlagModel() {
    }

    /** 64×64 atlas: the flag on top, wood and gold below. */
    public static int[] atlas(final int[] flag) {
        if (flag.length != FlagImage.WIDTH * FlagImage.HEIGHT) {
            throw new IllegalArgumentException("flag size");
        }
        final int[] out = new int[ATLAS * ATLAS];
        for (int y = 0; y < FlagImage.HEIGHT; y++) {
            System.arraycopy(flag, y * FlagImage.WIDTH, out, y * ATLAS, FlagImage.WIDTH);
        }
        for (int y = FlagImage.HEIGHT; y < ATLAS; y++) {
            for (int x = 0; x < ATLAS; x++) {
                final int rgb;
                if (x < 16) {
                    // oak pole with darker grain lines
                    rgb = (x % 4 == 0 || (x + y) % 7 == 0) ? 0x5A3A1E : 0x7A5230;
                } else if (x < 24) {
                    rgb = (x + y) % 3 == 0 ? 0xB8892A : 0xE0B33C; // gilded finial
                } else {
                    rgb = 0x3B2A1A;
                }
                out[y * ATLAS + x] = 0xFF000000 | rgb;
            }
        }
        return out;
    }

    /**
     * The {@code .bbmodel} JSON for {@code modelName} with the given flag pixels. {@code identifier} (the image id) is
     * stored in {@code model_identifier}, so the plugin knows after a restart which image every model file holds.
     */
    public static String bbmodel(final String modelName, final int[] flag, final String identifier) {
        final byte[] png = FlagImage.encodePng(atlas(flag), ATLAS, ATLAS);
        final JsonObject root = new JsonObject();
        final JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "5.0");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);
        root.add("meta", meta);
        root.addProperty("name", modelName);
        root.addProperty("model_identifier", identifier == null ? "" : identifier);
        final JsonObject resolution = new JsonObject();
        resolution.addProperty("width", ATLAS);
        resolution.addProperty("height", ATLAS);
        root.add("resolution", resolution);

        final JsonArray elements = new JsonArray();
        final JsonArray groups = new JsonArray();
        final JsonArray outliner = new JsonArray();

        // pole: two bones of 32 units (item models are limited to 48 units per bone), then the finial
        final String poleLow = uuid(modelName, "g:" + POLE_LOW);
        groups.add(group(poleLow, POLE_LOW, 0, 0, 0));
        final String poleLowCube = uuid(modelName, "e:" + POLE_LOW);
        elements.add(cube(poleLowCube, POLE_LOW, new double[] {-1, 0, -1}, new double[] {1, 32, 1}, new double[] {0, 0, 0}, poleFaces(0)));
        outliner.add(node(poleLow, poleLowCube));

        final String poleHigh = uuid(modelName, "g:" + POLE_HIGH);
        groups.add(group(poleHigh, POLE_HIGH, 0, 32, 0));
        final String poleHighCube = uuid(modelName, "e:" + POLE_HIGH);
        elements.add(cube(poleHighCube, POLE_HIGH, new double[] {-1, 32, -1}, new double[] {1, 64, 1}, new double[] {0, 32, 0}, poleFaces(8)));
        outliner.add(node(poleHigh, poleHighCube));

        final String finial = uuid(modelName, "g:" + FINIAL);
        groups.add(group(finial, FINIAL, 0, 64, 0));
        final String finialCube = uuid(modelName, "e:" + FINIAL);
        elements.add(cube(finialCube, FINIAL, new double[] {-1.5, 64, -1.5}, new double[] {1.5, 67, 1.5}, new double[] {0, 64, 0}, finialFaces()));
        outliner.add(node(finial, finialCube));

        // cloth: strip i is a child of strip i-1, pivot on its hoist edge
        JsonObject parentNode = null;
        final double stripWidth = CLOTH_WIDTH / STRIPS;
        final double pivotY = (CLOTH_TOP + CLOTH_BOTTOM) / 2;
        final String[] stripGroups = new String[STRIPS];
        for (int i = 0; i < STRIPS; i++) {
            final String name = CLOTH_PREFIX + i;
            final double x0 = 1 + i * stripWidth;
            final String g = uuid(modelName, "g:" + name);
            stripGroups[i] = g;
            groups.add(group(g, name, x0, pivotY, 0));
            final String e = uuid(modelName, "e:" + name);
            elements.add(cube(e, name, new double[] {x0, CLOTH_BOTTOM, -0.25}, new double[] {x0 + stripWidth, CLOTH_TOP, 0.25},
                new double[] {x0, pivotY, 0}, clothFaces(i)));
            final JsonObject n = node(g, e);
            if (parentNode == null) {
                outliner.add(n);
            } else {
                parentNode.getAsJsonArray("children").add(n);
            }
            parentNode = n;
        }

        root.add("elements", elements);
        root.add("groups", groups);
        root.add("outliner", outliner);

        final JsonArray textures = new JsonArray();
        final JsonObject tex = new JsonObject();
        tex.addProperty("name", modelName + ".png");
        tex.addProperty("id", "0");
        tex.addProperty("uuid", uuid(modelName, "texture"));
        tex.addProperty("width", ATLAS);
        tex.addProperty("height", ATLAS);
        tex.addProperty("uv_width", ATLAS);
        tex.addProperty("uv_height", ATLAS);
        tex.addProperty("source", FlagImage.dataUrl(png));
        tex.addProperty("internal", true);
        tex.addProperty("mode", "bitmap");
        tex.addProperty("visible", true);
        tex.addProperty("render_mode", "default");
        textures.add(tex);
        root.add("textures", textures);

        final JsonArray animations = new JsonArray();
        animations.add(waveAnimation(modelName, stripGroups));
        root.add("animations", animations);
        return GSON.toJson(root);
    }

    /** Loop of 2 s: strip i turns by A_i·sin(2πt/T − 0.7·i) degrees around Y (chained, so the free end swings most). */
    static JsonObject waveAnimation(final String modelName, final String[] stripGroups) {
        final double length = 2.0;
        final int steps = 16;
        final JsonObject anim = new JsonObject();
        anim.addProperty("uuid", uuid(modelName, "anim:" + ANIMATION));
        anim.addProperty("name", ANIMATION);
        anim.addProperty("loop", "loop");
        anim.addProperty("override", false);
        anim.addProperty("length", length);
        anim.addProperty("snapping", 20);
        final JsonObject animators = new JsonObject();
        for (int i = 0; i < stripGroups.length; i++) {
            final JsonObject animator = new JsonObject();
            animator.addProperty("name", CLOTH_PREFIX + i);
            animator.addProperty("type", "bone");
            animator.addProperty("rotation_global", false);
            final JsonArray keyframes = new JsonArray();
            final double amplitude = 5.0 + i * 0.75;
            for (int k = 0; k <= steps; k++) {
                final double t = length * k / steps;
                final double y = amplitude * Math.sin(2 * Math.PI * t / length - 0.7 * i);
                final double z = i == 0 ? 0 : 0.8 * Math.sin(2 * Math.PI * t / length - 0.7 * i + 1.1);
                final JsonObject kf = new JsonObject();
                kf.addProperty("uuid", uuid(modelName, "kf:" + i + ":" + k));
                kf.addProperty("channel", "rotation");
                kf.addProperty("time", round(t));
                kf.addProperty("interpolation", "linear");
                final JsonArray points = new JsonArray();
                final JsonObject p = new JsonObject();
                p.addProperty("x", "0");
                p.addProperty("y", String.format(Locale.ROOT, "%.3f", y));
                p.addProperty("z", String.format(Locale.ROOT, "%.3f", z));
                points.add(p);
                kf.add("data_points", points);
                keyframes.add(kf);
            }
            animator.add("keyframes", keyframes);
            animators.add(stripGroups[i], animator);
        }
        anim.add("animators", animators);
        return anim;
    }

    // ------------------------------------------------------------------ building blocks

    private static double round(final double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    static String uuid(final String modelName, final String part) {
        return UUID.nameUUIDFromBytes(("kraje-flag:" + modelName + ":" + part).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JsonObject group(final String uuid, final String name, final double x, final double y, final double z) {
        final JsonObject g = new JsonObject();
        g.addProperty("uuid", uuid);
        g.addProperty("export", true);
        g.addProperty("locked", false);
        g.add("origin", vec(x, y, z));
        g.add("rotation", vec(0, 0, 0));
        g.addProperty("color", 0);
        g.addProperty("name", name);
        g.add("children", new JsonArray());
        g.addProperty("reset", false);
        g.addProperty("shade", true);
        g.addProperty("mirror_uv", false);
        g.addProperty("selected", false);
        g.addProperty("visibility", true);
        g.addProperty("autouv", 0);
        g.addProperty("isOpen", true);
        return g;
    }

    private static JsonObject node(final String group, final String element) {
        final JsonObject n = new JsonObject();
        n.addProperty("uuid", group);
        n.addProperty("isOpen", true);
        final JsonArray children = new JsonArray();
        children.add(element);
        n.add("children", children);
        return n;
    }

    private static JsonObject cube(final String uuid, final String name, final double[] from, final double[] to,
                                   final double[] origin, final JsonObject faces) {
        final JsonObject c = new JsonObject();
        c.addProperty("name", name);
        c.addProperty("type", "cube");
        c.addProperty("uuid", uuid);
        c.add("from", vec(from[0], from[1], from[2]));
        c.add("to", vec(to[0], to[1], to[2]));
        c.add("origin", vec(origin[0], origin[1], origin[2]));
        c.add("rotation", vec(0, 0, 0));
        c.addProperty("box_uv", false);
        c.addProperty("autouv", 0);
        c.addProperty("rescale", false);
        c.addProperty("export", true);
        c.addProperty("visibility", true);
        c.add("faces", faces);
        return c;
    }

    private static JsonArray vec(final double x, final double y, final double z) {
        final JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    private static JsonObject face(final double u1, final double v1, final double u2, final double v2) {
        final JsonObject f = new JsonObject();
        final JsonArray uv = new JsonArray();
        uv.add(u1);
        uv.add(v1);
        uv.add(u2);
        uv.add(v2);
        f.add("uv", uv);
        f.addProperty("texture", 0);
        return f;
    }

    private static JsonObject poleFaces(final int u) {
        final JsonObject faces = new JsonObject();
        for (final String side : new String[] {"north", "south", "east", "west"}) {
            faces.add(side, face(u, 40, u + 2, 64));
        }
        faces.add("up", face(u, 40, u + 2, 42));
        faces.add("down", face(u, 40, u + 2, 42));
        return faces;
    }

    private static JsonObject finialFaces() {
        final JsonObject faces = new JsonObject();
        for (final String side : new String[] {"north", "south", "east", "west", "up", "down"}) {
            faces.add(side, face(16, 40, 19, 43));
        }
        return faces;
    }

    /** Strip i uses flag columns 8i..8i+8; the back (north) face is mirrored like the back of a real flag. */
    private static JsonObject clothFaces(final int i) {
        final int px = FlagImage.WIDTH / STRIPS;
        final int u0 = i * px;
        final int u1 = u0 + px;
        final JsonObject faces = new JsonObject();
        faces.add("south", face(u0, 0, u1, FlagImage.HEIGHT));
        faces.add("north", face(u1, 0, u0, FlagImage.HEIGHT));
        faces.add("east", face(u1 - 1, 0, u1, FlagImage.HEIGHT));
        faces.add("west", face(u0, 0, u0 + 1, FlagImage.HEIGHT));
        faces.add("up", face(u0, 0, u1, 1));
        faces.add("down", face(u0, FlagImage.HEIGHT - 1, u1, FlagImage.HEIGHT));
        return faces;
    }
}
