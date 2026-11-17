// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The generated BetterModel banner: structure, texture and animation. */
class FlagModelTest {

    private static int[] flag() {
        final int[] px = new int[FlagImage.WIDTH * FlagImage.HEIGHT];
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xFF000000 | (i % FlagImage.WIDTH) * 4 << 16 | (i / FlagImage.WIDTH) * 6;
        }
        return px;
    }

    @Test
    void modelHasPoleFinialAndChainedCloth() throws Exception {
        final String json = FlagModel.bbmodel("kraje_flaga_abc", flag(), "0123456789abcdef");
        assertEquals(json, FlagModel.bbmodel("kraje_flaga_abc", flag(), "0123456789abcdef"), "deterministic");
        final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("5.0", root.getAsJsonObject("meta").get("format_version").getAsString());
        assertEquals("kraje_flaga_abc", root.get("name").getAsString());
        assertEquals("0123456789abcdef", root.get("model_identifier").getAsString());
        final Set<String> names = new HashSet<>();
        for (final JsonElement g : root.getAsJsonArray("groups")) {
            names.add(g.getAsJsonObject().get("name").getAsString());
        }
        assertTrue(names.containsAll(Set.of("maszt", "maszt_gora", "glowica")));
        for (int i = 0; i < FlagModel.STRIPS; i++) {
            assertTrue(names.contains("plotno_" + i));
        }
        // cloth chain: plotno_0 at top level, every next strip nested in the previous one
        JsonObject node = null;
        for (final JsonElement e : root.getAsJsonArray("outliner")) {
            final String uuid = e.getAsJsonObject().get("uuid").getAsString();
            if (uuid.equals(FlagModel.uuid("kraje_flaga_abc", "g:plotno_0"))) {
                node = e.getAsJsonObject();
            }
        }
        int depth = 1;
        while (node != null) {
            JsonObject next = null;
            for (final JsonElement c : node.getAsJsonArray("children")) {
                if (c.isJsonObject()) {
                    next = c.getAsJsonObject();
                }
            }
            node = next;
            if (node != null) {
                depth++;
            }
        }
        assertEquals(FlagModel.STRIPS, depth);
        // every element fits the item model range (48 units per bone)
        for (final JsonElement e : root.getAsJsonArray("elements")) {
            final JsonArray from = e.getAsJsonObject().getAsJsonArray("from");
            final JsonArray to = e.getAsJsonObject().getAsJsonArray("to");
            for (int a = 0; a < 3; a++) {
                assertTrue(to.get(a).getAsDouble() - from.get(a).getAsDouble() <= 32.0);
            }
        }
    }

    @Test
    void textureAtlasCarriesTheFlag() throws Exception {
        final int[] flag = flag();
        final JsonObject root = JsonParser.parseString(FlagModel.bbmodel("m", flag, "")).getAsJsonObject();
        final JsonObject tex = root.getAsJsonArray("textures").get(0).getAsJsonObject();
        final String src = tex.get("source").getAsString();
        assertTrue(src.startsWith("data:image/png;base64,"));
        final BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(src.substring(22))));
        assertEquals(FlagModel.ATLAS, img.getWidth());
        assertEquals(FlagModel.ATLAS, img.getHeight());
        for (int y = 0; y < FlagImage.HEIGHT; y++) {
            for (int x = 0; x < FlagImage.WIDTH; x++) {
                assertEquals(flag[y * FlagImage.WIDTH + x], img.getRGB(x, y));
            }
        }
    }

    @Test
    void waveAnimationLoopsOverAllStrips() {
        final JsonObject root = JsonParser.parseString(FlagModel.bbmodel("m", flag(), "")).getAsJsonObject();
        final JsonObject anim = root.getAsJsonArray("animations").get(0).getAsJsonObject();
        assertEquals(FlagModel.ANIMATION, anim.get("name").getAsString());
        assertEquals("loop", anim.get("loop").getAsString());
        final JsonObject animators = anim.getAsJsonObject("animators");
        assertEquals(FlagModel.STRIPS, animators.size());
        for (final String key : animators.keySet()) {
            final JsonArray kfs = animators.getAsJsonObject(key).getAsJsonArray("keyframes");
            final JsonObject first = kfs.get(0).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject();
            final JsonObject last = kfs.get(kfs.size() - 1).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject();
            assertEquals(first.get("y").getAsDouble(), last.get("y").getAsDouble(), 1e-3, "seamless loop");
        }
    }
}
