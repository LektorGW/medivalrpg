// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the generated BetterModel files in {@code src/main/resources/bettermodel}: valid JSON in the
 * Blockbench 5 format, the 12-part player rig with BetterModel's bone tags, every clip finite, inside
 * its length, starting / ending in the rest pose (one-shots) or closing the loop, and the catalogue
 * consistent with the models.
 */
class AnimationAssetsTest {
    private static final List<String> REQUIRED_BONES = List.of(
            "player_root", "phip_hip", "pw_waist", "pc_chest", "pra_right_arm", "prfa_right_forearm",
            "pla_left_arm", "plfa_left_forearm", "prl_right_leg", "prfl_right_foreleg", "pll_left_leg",
            "plfl_left_foreleg", "pri_right_item", "pli_left_item", "cape_cape");
    private static final List<String> REQUIRED_ANIMATIONS = List.of(
            "hug", "kiss", "hold", "highfive", "pat", "slap", "wave", "bow", "sit_floor", "sit_chair", "lay",
            "dance_1", "dance_2", "dance_3", "klaskanie", "salut", "nie_wiem", "facepalm",
            "modlitwa", "kleczenie", "poklon", "salut_miecz", "kufel");

    private static AnimationCatalog catalog;
    private static final Map<String, JsonObject> models = new HashMap<>();

    @BeforeAll
    static void load() throws Exception {
        List<String> warnings = new ArrayList<>();
        try (Reader r = resource("/bettermodel/animations.json")) {
            catalog = AnimationCatalog.parse(r, warnings::add);
        }
        assertEquals(List.of(), warnings);
        for (String m : catalog.models()) {
            try (Reader r = resource("/bettermodel/players/" + m + ".bbmodel")) {
                models.put(m, JsonParser.parseReader(r).getAsJsonObject());
            }
        }
    }

    private static Reader resource(String path) {
        InputStream in = AnimationAssetsTest.class.getResourceAsStream(path);
        assertNotNull(in, path + " on the classpath (run animations-src/generate.py)");
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    @Test
    void catalogueHasEveryRequiredAnimation() {
        for (String id : REQUIRED_ANIMATIONS) {
            assertNotNull(catalog.animation(id), "animation " + id);
        }
        for (String pair : List.of("hug", "kiss", "hold", "highfive", "pat", "slap")) {
            AnimationCatalog.Animation a = catalog.animation(pair);
            assertTrue(a.pair(), pair + " is a two-person animation");
            assertNotNull(a.role(AnimationCatalog.Role.TARGET), pair + " target role");
            assertTrue(a.distance() >= 0.5 && a.distance() <= 1.0, pair + " distance " + a.distance());
        }
        assertEquals(AnimationCatalog.Arrangement.FACE, catalog.animation("hug").arrangement());
        assertEquals(0.6, catalog.animation("hug").distance(), 1e-9);
        assertEquals(AnimationCatalog.Arrangement.SIDE, catalog.animation("hold").arrangement());
        assertEquals(2, catalog.animation("hold").mirror().size(), "holding hands works with the partner on either side");
        assertFalse(catalog.animation("slap").role(AnimationCatalog.Role.TARGET).lock(), "the knockback must not end the reaction");
    }

    @Test
    void durationsAreRealistic() {
        assertClipLength("hug_a", 2.8, 3.4);
        assertClipLength("hug_b", 2.8, 3.4);
        assertClipLength("kiss_a", 2.2, 2.8);
        assertClipLength("kiss_b", 2.2, 2.8);
        assertClipLength("highfive_a", 1.2, 2.0);
        assertClipLength("wave", 1.8, 3.0);
        assertClipLength("bow", 2.4, 4.0);
        // the clap of a high five happens at the same tick for both
        assertEquals(clip("highfive_a").impactTicks(), clip("highfive_b").impactTicks());
        assertFalse(clip("highfive_a").impactTicks().isEmpty());
        assertEquals(6, clip("emote_klaskanie").impactTicks().size(), "six claps, six sounds");
        assertEquals(3, clip("emote_kufel").impactTicks().size(), "three gulps, three drinking sounds");
        assertClipLength("emote_poklon", 2.4, 3.2);
        assertTrue(clip("emote_poklon").lengthTicks() > clip("bow").lengthTicks() - 20, "a deep bow is not shorter than a plain one");
        assertClipLength("emote_kufel", 2.7, 3.5);
        assertClipLength("emote_salut_miecz", 2.0, 3.2);
        for (String kneel : List.of("emote_modlitwa", "emote_kleczenie")) {
            assertTrue(clip(kneel).blendTicks() >= 6, kneel + " blends in slowly from standing");
        }
        assertEquals(-1, clip("hug_a").blendTicks(), "default blend-in");
        for (String loop : List.of("hold_right", "hold_left", "sit_floor", "sit_chair", "lay_back", "dance_1", "dance_2", "dance_3",
                "emote_modlitwa", "emote_kleczenie")) {
            assertTrue(clip(loop).loop(), loop + " loops");
        }
        for (String once : List.of("hug_a", "kiss_a", "highfive_a", "pat_a", "slap_a", "slap_b", "wave", "bow",
                "emote_klaskanie", "emote_salut", "emote_nie_wiem", "emote_facepalm", "emote_poklon", "emote_salut_miecz",
                "emote_kufel")) {
            assertFalse(clip(once).loop(), once + " plays once");
        }
    }

    private static AnimationCatalog.Clip clip(String name) {
        AnimationCatalog.Clip c = catalog.clip(name);
        assertNotNull(c, name);
        return c;
    }

    private static void assertClipLength(String name, double min, double max) {
        double s = clip(name).lengthTicks() / 20.0;
        assertTrue(s >= min && s <= max, name + " lasts " + s + " s");
    }

    @Test
    void modelsHaveThePlayerRigInBlockbench5Format() {
        assertEquals(Set.of("interakcje", "interakcje_look"), models.keySet());
        for (Map.Entry<String, JsonObject> e : models.entrySet()) {
            JsonObject m = e.getValue();
            assertEquals("5.0", m.getAsJsonObject("meta").get("format_version").getAsString(),
                    "BetterModel reads axis signs from the format version");
            Set<String> groups = new HashSet<>();
            for (JsonElement g : m.getAsJsonArray("groups")) {
                groups.add(g.getAsJsonObject().get("name").getAsString());
            }
            for (String bone : REQUIRED_BONES) {
                assertTrue(groups.contains(bone), e.getKey() + " has bone " + bone);
            }
            boolean look = e.getKey().equals("interakcje_look");
            assertTrue(groups.contains(look ? "h_ph_head" : "ph_head"),
                    e.getKey() + ": the head follows the camera only in the _look model");
            assertFalse(m.getAsJsonArray("elements").isEmpty());
            assertTrue(m.getAsJsonArray("textures").get(0).getAsJsonObject().get("name").getAsString().startsWith("-"),
                    "the skin template texture is ignored by BetterModel (player skin instead)");
        }
    }

    @Test
    void everyClipIsWellFormed() {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, JsonObject> e : models.entrySet()) {
            Map<String, String> groupByUuid = new HashMap<>();
            for (JsonElement g : e.getValue().getAsJsonArray("groups")) {
                groupByUuid.put(g.getAsJsonObject().get("uuid").getAsString(), g.getAsJsonObject().get("name").getAsString());
            }
            for (JsonElement ae : e.getValue().getAsJsonArray("animations")) {
                JsonObject a = ae.getAsJsonObject();
                String name = a.get("name").getAsString();
                seen.add(name);
                AnimationCatalog.Clip c = clip(name);
                assertEquals(e.getKey(), c.model(), name + " is in the model the catalogue says");
                double len = a.get("length").getAsDouble();
                assertEquals(c.lengthTicks(), Math.round(len * 20), name + " length");
                String loop = a.get("loop").getAsString();
                assertEquals(c.loop() ? "loop" : "once", loop, name + " loop mode");
                JsonObject animators = a.getAsJsonObject("animators");
                assertFalse(animators.isEmpty(), name + " animates something");
                for (Map.Entry<String, JsonElement> an : animators.entrySet()) {
                    String bone = groupByUuid.get(an.getKey());
                    assertNotNull(bone, name + ": animator for an existing bone");
                    checkKeyframes(name + "/" + bone, an.getValue().getAsJsonObject().getAsJsonArray("keyframes"),
                            len, c.loop());
                }
            }
        }
        assertEquals(catalog.clips().keySet(), seen, "every catalogue clip exists in a model and vice versa");
    }

    private static void checkKeyframes(String where, JsonArray keyframes, double length, boolean loop) {
        Map<String, List<double[]>> byChannel = new HashMap<>();
        for (JsonElement ke : keyframes) {
            JsonObject k = ke.getAsJsonObject();
            String channel = k.get("channel").getAsString();
            assertTrue(channel.equals("rotation") || channel.equals("position"), where + " channel " + channel);
            double t = k.get("time").getAsDouble();
            assertTrue(t >= 0 && t <= length + 1e-6, where + " keyframe time " + t);
            JsonObject p = k.getAsJsonArray("data_points").get(0).getAsJsonObject();
            double[] v = {t, Double.parseDouble(p.get("x").getAsString()), Double.parseDouble(p.get("y").getAsString()),
                    Double.parseDouble(p.get("z").getAsString())};
            for (int i = 1; i < 4; i++) {
                assertTrue(Double.isFinite(v[i]), where + " finite value at " + t);
                assertTrue(Math.abs(v[i]) < 400, where + " sane value " + v[i]);
            }
            byChannel.computeIfAbsent(channel, x -> new ArrayList<>()).add(v);
        }
        for (Map.Entry<String, List<double[]>> c : byChannel.entrySet()) {
            List<double[]> list = c.getValue();
            list.sort((a, b) -> Double.compare(a[0], b[0]));
            double[] first = list.getFirst();
            double[] last = list.getLast();
            assertEquals(0.0, first[0], 1e-9, where + " " + c.getKey() + " starts at 0");
            assertEquals(length, last[0], 1e-6, where + " " + c.getKey() + " ends at the clip length");
            double tol = c.getKey().equals("rotation") ? 0.6 : 0.06;
            for (int i = 1; i < 4; i++) {
                if (loop) {
                    assertEquals(first[i], last[i], tol, where + " " + c.getKey() + " loop closes");
                } else {
                    assertEquals(0.0, first[i], tol, where + " " + c.getKey() + " starts in the rest pose");
                    assertEquals(0.0, last[i], tol, where + " " + c.getKey() + " ends in the rest pose");
                }
            }
        }
    }

    @Test
    void generatorOutputIsUpToDate() throws Exception {
        File script = new File("animations-src/generate.py");
        Assumptions.assumeTrue(script.isFile(), "generator sources present");
        Process p;
        try {
            p = new ProcessBuilder("python", script.getPath(), "--check").redirectErrorStream(true).start();
        } catch (java.io.IOException e) {
            Assumptions.abort("python not available: " + e.getMessage());
            return;
        }
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            fail("generator timed out");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assumptions.assumeFalse(out.contains("Python was not found"), "python not available");
        assertEquals(0, p.exitValue(), "run python animations-src/generate.py: " + out);
    }
}
