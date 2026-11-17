// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resource-pack files of the item "Sztandar kraju" (option B) and which flags get into the pack. */
class StandardAssetsTest {

    static final int[] POLAND = BannerFitTest.flag((x, y) -> y < 20 ? 0xFFFFFFFF : 0xFFDC143C);

    @Test
    void filesAndJson() throws Exception {
        final Map<String, byte[]> files = StandardAssets.files("0123456789ab", POLAND, false);
        assertEquals(List.of("assets/kraje/items/sztandar/0123456789ab.json", "assets/kraje/models/item/sztandar/0123456789ab.json",
            "assets/kraje/textures/item/sztandar/0123456789ab.png"), List.copyOf(files.keySet()));
        final JsonObject item = JsonParser.parseString(new String(files.get("assets/kraje/items/sztandar/0123456789ab.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertEquals("kraje:item/sztandar/0123456789ab", item.getAsJsonObject("model").get("model").getAsString());
        assertFalse(item.getAsJsonObject("model").has("tints"), "own flag is not tinted");
        assertEquals("kraje:sztandar/0123456789ab", StandardAssets.itemModel("0123456789ab"));

        final JsonObject model = JsonParser.parseString(new String(files.get("assets/kraje/models/item/sztandar/0123456789ab.json"),
            StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("kraje:item/sztandar/0123456789ab", model.getAsJsonObject("textures").get("flag").getAsString());
        for (final JsonElement e : model.getAsJsonArray("elements")) {
            for (final String k : new String[] {"from", "to"}) {
                for (final JsonElement v : e.getAsJsonObject().getAsJsonArray(k)) {
                    assertTrue(v.getAsDouble() >= -16 && v.getAsDouble() <= 32, "vanilla element bounds: " + v);
                }
            }
        }
        for (final Map.Entry<String, JsonElement> d : model.getAsJsonObject("display").entrySet()) {
            for (final JsonElement v : d.getValue().getAsJsonObject().getAsJsonArray("translation")) {
                assertTrue(Math.abs(v.getAsDouble()) <= 80, d.getKey());
            }
            for (final JsonElement v : d.getValue().getAsJsonObject().getAsJsonArray("scale")) {
                assertTrue(v.getAsDouble() > 0 && v.getAsDouble() <= 4, d.getKey());
            }
        }
        assertTrue(model.getAsJsonObject("display").has("thirdperson_righthand") && model.getAsJsonObject("display").has("gui"));
        assertFalse(new String(files.get("assets/kraje/models/item/sztandar/0123456789ab.json"), StandardCharsets.UTF_8).contains("tintindex"));

        // texture: the exact flag in rows 0..39 of a 64x64 atlas
        final BufferedImage tex = ImageIO.read(new ByteArrayInputStream(files.get("assets/kraje/textures/item/sztandar/0123456789ab.png")));
        assertEquals(64, tex.getWidth());
        assertEquals(64, tex.getHeight());
        assertEquals(0xFFFFFFFF, tex.getRGB(10, 5));
        assertEquals(0xFFDC143C, tex.getRGB(10, 30));
    }

    @Test
    void defaultIsTinted() {
        final Map<String, byte[]> files = StandardAssets.defaultFiles();
        final String item = new String(files.get("assets/kraje/items/sztandar/domyslna.json"), StandardCharsets.UTF_8);
        assertTrue(item.contains("\"minecraft:custom_model_data\""), item);
        final String model = new String(files.get("assets/kraje/models/item/sztandar/domyslna.json"), StandardCharsets.UTF_8);
        assertEquals(6, model.split("tintindex", -1).length - 1, "only the cloth faces are tinted");
    }

    @Test
    void idsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> StandardAssets.files("../../x", POLAND, false));
        assertThrows(IllegalArgumentException.class, () -> StandardAssets.files("0123456789AB", POLAND, false));
    }

    /** Only banner model files of approved flags (written by the rebuild) bring their image into the pack. */
    @Test
    void packFilesFollowTheBannerModels(@TempDir final Path dir) throws Exception {
        final String approved = "aaaaaaaaaaaaaaaa";
        final byte[] png = FlagImage.encodePng(POLAND, FlagImage.WIDTH, FlagImage.HEIGHT);
        Files.writeString(dir.resolve("kraje_flaga_0123456789ab.bbmodel"), FlagModel.bbmodel("kraje_flaga_0123456789ab", POLAND, approved));
        Files.writeString(dir.resolve("kraje_flaga_domyslna.bbmodel"), FlagModel.bbmodel("kraje_flaga_domyslna", FlagImage.defaultTexture(), "x"));
        Files.writeString(dir.resolve("kraje_flaga_ffffffffffff.bbmodel"), "{zepsuty");
        Files.writeString(dir.resolve("kraje_flaga_eeeeeeeeeeee.bbmodel"), FlagModel.bbmodel("kraje_flaga_eeeeeeeeeeee", POLAND, "bbbbbbbbbbbbbbbb"));
        Files.writeString(dir.resolve("inny_model.bbmodel"), "{}");
        final Map<String, byte[]> files = FlagService.standardPackFiles(dir, "kraje_flaga_",
            id -> id.equals(approved) ? png : null, Logger.getLogger("test"));
        assertTrue(files.containsKey("assets/kraje/items/sztandar/domyslna.json"));
        assertTrue(files.containsKey("assets/kraje/textures/item/sztandar/0123456789ab.png"));
        assertFalse(files.keySet().stream().anyMatch(k -> k.contains("eeeeeeeeeeee")), "no image file -> nothing");
        assertFalse(files.keySet().stream().anyMatch(k -> k.contains("ffffffffffff")), "broken model -> nothing");
        assertEquals(6, files.size());
        assertEquals(3, FlagService.standardPackFiles(dir.resolve("brak"), "kraje_flaga_", id -> null, Logger.getLogger("test")).size());
    }
}
