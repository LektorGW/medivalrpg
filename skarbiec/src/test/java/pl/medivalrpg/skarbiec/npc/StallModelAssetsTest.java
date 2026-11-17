// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every merchant type has a shipped stall model with the bones and animations StallModels uses. */
class StallModelAssetsTest {

    private static JsonObject model(final String type) throws Exception {
        try (InputStream in = StallModelAssetsTest.class.getResourceAsStream("/models/" + StallService.PREFIX + type + ".bbmodel")) {
            assertNotNull(in, type);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void names(final JsonElement node, final Set<String> out) {
        if (node.isJsonObject()) {
            final JsonObject o = node.getAsJsonObject();
            out.add(o.get("name").getAsString());
            for (final JsonElement c : o.getAsJsonArray("children")) {
                names(c, out);
            }
        }
    }

    @Test
    void everyShippedTypeHasAStallWithWindAndDelivery() throws Exception {
        for (final String type : StallService.SHIPPED) {
            final JsonObject m = model(type);
            final Set<String> bones = new HashSet<>();
            for (final JsonElement e : m.getAsJsonArray("outliner")) {
                names(e, bones);
            }
            assertTrue(bones.containsAll(Set.of("kram", "lada", "slupki", "daszek", "szyld", "towar")), type + " " + bones);
            final Set<String> animations = new HashSet<>();
            final JsonArray anims = m.getAsJsonArray("animations");
            for (final JsonElement a : anims) {
                final JsonObject o = a.getAsJsonObject();
                animations.add(o.get("name").getAsString() + ":" + o.get("loop").getAsString());
            }
            assertTrue(animations.contains(StallModels.WIND + ":loop"), type + " " + animations);
            assertTrue(animations.contains(StallModels.DELIVERY + ":once"), type + " " + animations);
            assertEquals(1, m.getAsJsonArray("textures").size(), type);
        }
    }
}
