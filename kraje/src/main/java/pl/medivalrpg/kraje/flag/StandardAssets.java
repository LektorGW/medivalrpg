// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resource-pack files of the item "Sztandar kraju" (option B, pure Java, deterministic): an item model definition
 * ({@code assets/kraje/items/sztandar/<id>.json}, 1.21.4+ format), a block-style model with a standing pole and the flag
 * cloth ({@code assets/kraje/models/item/sztandar/<id>.json}) and the 64×64 texture with the exact approved flag on top
 * (the same atlas as the BetterModel banner, {@link FlagModel#atlas}). They are added to BetterModel's pack at the start
 * of every BetterModel reload, i.e. by the same batched rebuild as the banners — only for flags whose banner model file
 * is written, so only approved images ever reach the pack.
 *
 * <p>Model units: 16 = one block. The pole stands from y −16 to y 30 (with a finial up to 32), the cloth (20 × 12.5, the
 * flag aspect 1.6) hangs from its top towards +X. Display transforms: {@code fixed} is the identity (display entities of
 * placed standards are positioned in code), hands hold the pole upright, the inventory shows the whole standard.
 */
public final class StandardAssets {

    public static final String NAMESPACE = "kraje";
    public static final String DEFAULT_ID = "domyslna";
    /** Country part of the id: the same 12 hex chars as the banner model {@code kraje_flaga_<hex>}. */
    static final Pattern ID = Pattern.compile("[0-9a-f]{12}|" + DEFAULT_ID);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private StandardAssets() {
    }

    /** {@code kraje:sztandar/<id>} — the value of the {@code item_model} component. */
    public static String itemModel(final String id) {
        return NAMESPACE + ":sztandar/" + id;
    }

    /** The three pack files for one flag ({@code tinted} = cloth multiplied by custom_model_data colour 0). */
    public static Map<String, byte[]> files(final String id, final int[] flag64x40, final boolean tinted) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("bad standard id " + id);
        }
        final Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("assets/" + NAMESPACE + "/items/sztandar/" + id + ".json", itemJson(id, tinted).getBytes(StandardCharsets.UTF_8));
        out.put("assets/" + NAMESPACE + "/models/item/sztandar/" + id + ".json", modelJson(id, tinted).getBytes(StandardCharsets.UTF_8));
        out.put("assets/" + NAMESPACE + "/textures/item/sztandar/" + id + ".png",
            FlagImage.encodePng(FlagModel.atlas(flag64x40), FlagModel.ATLAS, FlagModel.ATLAS));
        return out;
    }

    /** Files of the shared grey default standard (tinted with the country colour per item). */
    public static Map<String, byte[]> defaultFiles() {
        return files(DEFAULT_ID, FlagImage.defaultTexture(), true);
    }

    static String itemJson(final String id, final boolean tinted) {
        final JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", NAMESPACE + ":item/sztandar/" + id);
        if (tinted) {
            final JsonArray tints = new JsonArray();
            final JsonObject tint = new JsonObject();
            tint.addProperty("type", "minecraft:custom_model_data");
            tint.addProperty("index", 0);
            tint.addProperty("default", 0xFFFFFF);
            tints.add(tint);
            model.add("tints", tints);
        }
        final JsonObject root = new JsonObject();
        root.add("model", model);
        return GSON.toJson(root);
    }

    static String modelJson(final String id, final boolean tinted) {
        final JsonObject root = new JsonObject();
        final JsonObject textures = new JsonObject();
        textures.addProperty("flag", NAMESPACE + ":item/sztandar/" + id);
        textures.addProperty("particle", NAMESPACE + ":item/sztandar/" + id);
        root.add("textures", textures);
        final JsonArray elements = new JsonArray();
        // pole: wood texels x 0..2 (u 0..0.5), rows 40..63 (v 10..16)
        elements.add(cube(new double[] {7.25, -16, 7.25}, new double[] {8.75, 30, 8.75}, faces(0, 10, 0.5, 16, -1)));
        // gilded finial: x 16..19 (u 4..4.75), rows 40..43
        elements.add(cube(new double[] {6.75, 30, 6.75}, new double[] {9.25, 32, 9.25}, faces(4, 10, 4.75, 10.75, -1)));
        // cloth: the whole flag, rows 0..39 (v 0..10); south shows it, north is mirrored like the back of a real flag
        final JsonObject cloth = new JsonObject();
        final int tint = tinted ? 0 : -1;
        cloth.add("south", face(0, 0, 16, 10, tint));
        cloth.add("north", face(16, 0, 0, 10, tint));
        cloth.add("east", face(15.75, 0, 16, 10, tint));
        cloth.add("west", face(0, 0, 0.25, 10, tint));
        cloth.add("up", face(0, 0, 16, 0.25, tint));
        cloth.add("down", face(0, 9.75, 16, 10, tint));
        elements.add(cube(new double[] {8.75, 19, 7.9}, new double[] {28.75, 31.5, 8.1}, cloth));
        root.add("elements", elements);
        final JsonObject display = new JsonObject();
        display.add("fixed", transform(new double[] {0, 0, 0}, new double[] {0, 0, 0}, 1));
        display.add("thirdperson_righthand", transform(new double[] {0, 90, 0}, new double[] {0, 10, 1}, 0.75));
        display.add("thirdperson_lefthand", transform(new double[] {0, 90, 0}, new double[] {0, 10, 1}, 0.75));
        display.add("firstperson_righthand", transform(new double[] {0, 75, 0}, new double[] {1, 2, 0}, 0.5));
        display.add("firstperson_lefthand", transform(new double[] {0, 75, 0}, new double[] {1, 2, 0}, 0.5));
        display.add("gui", transform(new double[] {0, 0, 0}, new double[] {-3, 0, 0}, 0.31));
        display.add("ground", transform(new double[] {0, 0, 0}, new double[] {0, 2, 0}, 0.25));
        display.add("head", transform(new double[] {0, 0, 0}, new double[] {0, 8, 0}, 0.6));
        root.add("display", display);
        return GSON.toJson(root);
    }

    private static JsonObject cube(final double[] from, final double[] to, final JsonObject faces) {
        final JsonObject c = new JsonObject();
        c.add("from", vec(from));
        c.add("to", vec(to));
        c.add("faces", faces);
        return c;
    }

    private static JsonObject faces(final double u1, final double v1, final double u2, final double v2, final int tint) {
        final JsonObject f = new JsonObject();
        for (final String side : new String[] {"north", "south", "east", "west", "up", "down"}) {
            f.add(side, face(u1, v1, u2, v2, tint));
        }
        return f;
    }

    private static JsonObject face(final double u1, final double v1, final double u2, final double v2, final int tint) {
        final JsonObject f = new JsonObject();
        f.add("uv", vec(new double[] {u1, v1, u2, v2}));
        f.addProperty("texture", "#flag");
        if (tint >= 0) {
            f.addProperty("tintindex", tint);
        }
        return f;
    }

    private static JsonObject transform(final double[] rotation, final double[] translation, final double scale) {
        final JsonObject t = new JsonObject();
        t.add("rotation", vec(rotation));
        t.add("translation", vec(translation));
        t.add("scale", vec(new double[] {scale, scale, scale}));
        return t;
    }

    private static JsonArray vec(final double[] v) {
        final JsonArray a = new JsonArray();
        for (final double d : v) {
            a.add(d);
        }
        return a;
    }
}
