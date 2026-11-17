// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.naval.DeckRules;
import pl.medivalrpg.armia.naval.NavalRules;

/** The bundled .bbmodel files against the code and the physical deck (no BetterModel needed). */
class ModelAssetsTest {
    private static final Set<String> TAG_PREFIXES = Set.of("h", "hi", "b", "ob", "p", "sp", "tag", "mtag", "ptag", "li", "ri", "pli", "pri", "ph");

    record Cube(String name, double[] from, double[] to, double[] rotation, String bone) { }

    static final class Model {
        final JsonObject json;
        final Map<String, JsonObject> groups = new HashMap<>();
        final Map<String, String> groupByUuid = new HashMap<>();
        final List<Cube> cubes = new ArrayList<>();
        final Map<String, JsonObject> animations = new HashMap<>();

        Model(final JsonObject json) {
            this.json = json;
            for (final var g : json.getAsJsonArray("groups")) {
                final var o = g.getAsJsonObject();
                assertNull(this.groups.put(o.get("name").getAsString(), o), "duplicate bone " + o.get("name"));
                this.groupByUuid.put(o.get("uuid").getAsString(), o.get("name").getAsString());
            }
            final Map<String, JsonObject> elements = new HashMap<>();
            for (final var e : json.getAsJsonArray("elements")) elements.put(e.getAsJsonObject().get("uuid").getAsString(), e.getAsJsonObject());
            walk(json.getAsJsonArray("outliner"), null, elements);
            assertEquals(elements.size(), this.cubes.size(), "every cube is in the outliner exactly once");
            for (final var a : json.getAsJsonArray("animations")) this.animations.put(a.getAsJsonObject().get("name").getAsString(), a.getAsJsonObject());
        }

        private void walk(final JsonArray nodes, final String bone, final Map<String, JsonObject> elements) {
            for (final var n : nodes) {
                if (n.isJsonPrimitive()) {
                    final JsonObject e = elements.get(n.getAsString());
                    assertNotNull(e);
                    this.cubes.add(new Cube(e.get("name").getAsString(), arr(e, "from"), arr(e, "to"), arr(e, "rotation"), bone));
                } else {
                    final var o = n.getAsJsonObject();
                    walk(o.getAsJsonArray("children"), o.get("name").getAsString(), elements);
                }
            }
        }

        List<double[]> keys(final String animation, final String bone, final String channel) {
            final JsonObject a = this.animations.get(animation);
            assertNotNull(a, animation);
            final List<double[]> out = new ArrayList<>();
            for (final var an : a.getAsJsonObject("animators").entrySet()) {
                if (!bone.equals(this.groupByUuid.get(an.getKey()))) continue;
                for (final var k : an.getValue().getAsJsonObject().getAsJsonArray("keyframes")) {
                    final var o = k.getAsJsonObject();
                    if (!o.get("channel").getAsString().equals(channel)) continue;
                    final var d = o.getAsJsonArray("data_points").get(0).getAsJsonObject();
                    out.add(new double[] {o.get("time").getAsDouble(), d.get("x").getAsDouble(), d.get("y").getAsDouble(), d.get("z").getAsDouble()});
                }
            }
            out.sort((x, y) -> Double.compare(x[0], y[0]));
            return out;
        }
    }

    private static double[] arr(final JsonObject o, final String key) {
        final var a = o.getAsJsonArray(key);
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    static Model load(final String name) throws Exception {
        try (var in = ModelAssetsTest.class.getResourceAsStream("/models/" + name + ".bbmodel")) {
            assertNotNull(in, "bundled " + name);
            return new Model(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    @Test void everyModelIsAValidBlockbenchFreeModel() throws Exception {
        for (final String name : ModelCatalog.ALL) {
            final Model m = load(name);
            assertEquals(name, m.json.get("name").getAsString());
            assertEquals("free", m.json.getAsJsonObject("meta").get("model_format").getAsString());
            assertEquals(128, m.json.getAsJsonObject("resolution").get("width").getAsInt());
            final var tex = m.json.getAsJsonArray("textures").get(0).getAsJsonObject();
            final byte[] png = Base64.getDecoder().decode(tex.get("source").getAsString().split(",", 2)[1]);
            final var image = ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(128, image.getWidth());
            assertEquals(128, image.getHeight());
            assertTrue(m.cubes.size() >= 40 && m.cubes.size() <= 260, name + " cubes " + m.cubes.size());
            for (final Cube c : m.cubes) {
                for (int i = 0; i < 3; i++) assertTrue(c.to[i] > c.from[i], name + "/" + c.name + " has positive size");
                int axes = 0;
                for (final double r : c.rotation) if (r != 0) {
                    axes++;
                    assertTrue(Set.of(-45.0, -22.5, 22.5, 45.0).contains(r), name + "/" + c.name + " rotation " + r);
                }
                assertTrue(axes <= 1, "single-axis cube rotation (vanilla item model): " + c.name);
                assertNotNull(c.bone, "cube inside a bone: " + c.name);
            }
            for (final String bone : m.groups.keySet()) {
                final int cut = bone.indexOf('_');
                if (cut > 0) assertFalse(TAG_PREFIXES.contains(bone.substring(0, cut)),
                    "bone " + bone + " would be read as a BetterModel tag (hitbox/seat/head...)");
                assertFalse(bone.startsWith("glow_") && !bone.equals("glow_latarnia") && !bone.equals("glow_plomien"), bone);
            }
            for (final JsonObject a : m.animations.values()) {
                assertTrue(a.get("override").getAsBoolean(), "override: clips never reset bones they don't animate");
                final double length = a.get("length").getAsDouble();
                for (final var an : a.getAsJsonObject("animators").entrySet()) {
                    assertNotNull(m.groupByUuid.get(an.getKey()), "animator bound to an existing bone");
                    for (final var k : an.getValue().getAsJsonObject().getAsJsonArray("keyframes")) {
                        final double t = k.getAsJsonObject().get("time").getAsDouble();
                        assertTrue(t >= 0 && t <= length + 1e-6);
                    }
                }
            }
        }
    }

    @Test void shipModelsCarryEveryClipAndBoneTheCodeUses() throws Exception {
        for (final String name : List.of(ModelCatalog.KOGA, ModelCatalog.KARAWELA)) {
            final Model m = load(name);
            for (final String clip : List.of(ModelCatalog.WAVES, ModelCatalog.SAILS_SET, ModelCatalog.SAILS_FURL, ModelCatalog.SAILS_FURLED,
                ModelCatalog.FLUTTER, ModelCatalog.FLAGS, ModelCatalog.ANCHOR_DROP, ModelCatalog.ANCHOR_RAISE, ModelCatalog.ANCHOR_DOWN))
                assertTrue(m.animations.containsKey(clip), name + " " + clip);
            assertEquals("loop", m.animations.get(ModelCatalog.WAVES).get("loop").getAsString());
            assertEquals("hold", m.animations.get(ModelCatalog.SAILS_SET).get("loop").getAsString());
            for (final String bone : List.of("statek", ModelCatalog.RUDDER, ModelCatalog.RUDDER_TIP, ModelCatalog.ANCHOR, ModelCatalog.ENSIGN))
                assertTrue(m.groups.containsKey(bone), name + " bone " + bone);
            assertTrue(m.groups.keySet().stream().filter(ModelCatalog::tinted).count() >= 4, "flags, pennant, shields, emblem dyed");
            // tinted bones hold only the white cloth, so the dye shows the true country colour
            for (final Cube c : m.cubes) if (ModelCatalog.tinted(c.bone)) assertTrue(isWhite(m, c), name + "/" + c.name + " in tinted bone");
            // the Kraje banner is hoisted exactly where the model ensign hangs
            final var ensign = m.groups.get(ModelCatalog.ENSIGN).getAsJsonArray("origin");
            final double[] hoist = ModelCatalog.ensignHoist(name);
            for (int i = 0; i < 3; i++) assertEquals(hoist[i] * 16, ensign.get(i).getAsDouble(), 1e-6, name + " ensign hoist " + i);
            // rudder rotates about the sternpost
            final var rudder = m.groups.get(ModelCatalog.RUDDER).getAsJsonArray("origin");
            assertEquals(0, rudder.get(0).getAsDouble(), 1e-9);
            // sails: furled clip ends small, set clip ends full size
            final var set = m.keys(ModelCatalog.SAILS_SET, "zagiel_gl", "scale");
            assertEquals(1, set.getLast()[2], 1e-9);
            assertTrue(m.keys(ModelCatalog.SAILS_FURL, "zagiel_gl", "scale").getLast()[2] < 0.2);
            assertTrue(m.keys(ModelCatalog.ANCHOR_DROP, ModelCatalog.ANCHOR, "position").getLast()[2] < -20, "anchor reaches the water");
        }
    }

    private static boolean isWhite(final Model m, final Cube c) {
        for (final var e : m.json.getAsJsonArray("elements")) {
            final var o = e.getAsJsonObject();
            if (!o.get("name").getAsString().equals(c.name)) continue;
            final var uv = o.getAsJsonObject("faces").getAsJsonObject("north").getAsJsonArray("uv");
            final int tile = (int) (uv.get(1).getAsDouble() / 32) * 4 + (int) (uv.get(0).getAsDouble() / 32);
            return tile == 4; // "barwa": near-white dye base
        }
        return false;
    }

    /**
     * Walking freely: on the walkable deck nothing visible stands between step height (0.6) and head height (1.8)
     * except thin masts (and their bands), the shrouds and the edge posts of the overhead castles.
     */
    @Test void walkwayHasNoVisibleWallsTheCollidersDoNotHave() throws Exception {
        for (final String[] pair : new String[][] {{"transportowiec", ModelCatalog.KOGA}, {"karawela", ModelCatalog.KARAWELA}}) {
            final var type = NavalRules.type(pair[0]);
            final Model m = load(pair[1]);
            final double deck = DeckRules.deckHeight(type) * 16, walk = DeckRules.walkableHalfLength(type) * 16;
            final double side = (type.halfWidth() - .12) * 16;
            final Set<String> blockers = new HashSet<>();
            for (final Cube c : m.cubes) {
                if (c.rotation[0] != 0 || c.rotation[1] != 0 || c.rotation[2] != 0) {
                    if (!c.name.equals("want") && !c.name.equals("miecz") && !c.name.equals("bukszpryt")) blockers.add(c.name + "(rotated)");
                    continue;
                }
                final boolean overDeck = c.to[0] > -side + 2.5 && c.from[0] < side - 2.5 && c.to[2] > -walk && c.from[2] < walk;
                final boolean bodyHeight = c.to[1] > deck + 9.6 && c.from[1] < deck + 28.8;
                if (!overDeck || !bodyHeight) continue;
                final double width = Math.min(c.to[0] - c.from[0], c.to[2] - c.from[2]);
                if ((c.name.startsWith("maszt") || c.name.startsWith("opaska")) && width <= 6) continue;
                // the helm station: the tiller bar the seated captain holds (FleetVisuals.helmPoint)
                if (c.name.startsWith("rumpel") && c.bone.equals(ModelCatalog.RUDDER) && width <= 3) continue;
                if (c.name.startsWith("zagiel") || c.name.startsWith("lik") || c.name.startsWith("herb")) {
                    assertTrue(c.from[1] >= deck + 30, "sails hang above head height: " + c.name);
                    continue;
                }
                blockers.add(c.name);
            }
            assertTrue(blockers.isEmpty(), pair[1] + " has walkway obstacles: " + blockers);
            // low deck furniture stays below one step
            for (final Cube c : m.cubes)
                if (List.of("luk", "kratownica", "zwoj_liny", "knaga").contains(c.name)) assertTrue(c.to[1] - deck <= 9.6, c.name);
            // overhead castles leave headroom
            for (final Cube c : m.cubes) if (c.name.startsWith("kasztel")) assertTrue(c.from[1] - deck >= 1.9 * 16, c.name);
            // bulwark rails can be stepped over: walking off the side remains a way off the ship
            for (final Cube c : m.cubes) if (c.name.equals("reling")) assertTrue(c.to[1] - deck <= 9.6, "rail below step height");
        }
    }

    @Test void engineModelsMatchCatalogTimingAndWheels() throws Exception {
        for (final String name : List.of(ModelCatalog.TREBUSZ, ModelCatalog.KATAPULTA, ModelCatalog.TARAN, ModelCatalog.ARMATA)) {
            final Model m = load(name);
            final var engine = ModelCatalog.engine(name);
            final JsonObject shot = m.animations.get(engine.shotAnimation());
            assertNotNull(shot, name + " shot clip");
            assertEquals(engine.shotLength(), shot.get("length").getAsDouble(), 1e-9);
            assertEquals("hold", shot.get("loop").getAsString());
            assertEquals(engine.reloadLength(), m.animations.get(ModelCatalog.RELOAD).get("length").getAsDouble(), 1e-9);
            assertEquals("loop", m.animations.get(ModelCatalog.ROLL).get("loop").getAsString());
            int wheels = 0;
            for (final var e : m.groups.entrySet()) if (e.getKey().startsWith("kolo_")) {
                wheels++;
                assertEquals(engine.wheelRadius() * 16, e.getValue().getAsJsonArray("origin").get(1).getAsDouble(), 1e-6,
                    name + " wheel axle at its radius (rolls on the ground)");
                assertFalse(m.keys(ModelCatalog.ROLL, e.getKey(), "rotation").isEmpty());
            }
            assertTrue(wheels >= 2, name + " wheels");
            if (m.groups.containsKey("kamien")) {
                // the stone leaves the sling/bucket at the release moment the logic projectile starts from
                final var stone = m.keys(engine.shotAnimation(), "kamien", "scale");
                assertTrue(stone.stream().anyMatch(k -> k[0] <= engine.release() + 1e-6 && k[1] < 0.05), name + " releases by " + engine.release());
                assertTrue(stone.stream().filter(k -> k[0] < engine.release() - 0.011).allMatch(k -> k[1] > 0.99));
                assertTrue(m.keys(ModelCatalog.RELOAD, "kamien", "scale").getLast()[1] > 0.99, "reloaded with a new stone");
            }
            // nothing in a rest pose below the ground
            for (final Cube c : m.cubes) assertTrue(c.from[1] >= -0.5 || c.bone.equals("wycior"), name + "/" + c.name + " under ground");
        }
        // cannon: barrel recoil goes back (-Z, towards the crew), smoke forward and up
        final Model gun = load(ModelCatalog.ARMATA);
        assertTrue(gun.keys(ModelCatalog.SHOT, "lufa", "position").stream().anyMatch(k -> k[3] < -4));
        assertTrue(gun.keys(ModelCatalog.SHOT, "dym", "position").stream().anyMatch(k -> k[3] > 20 && k[2] > 5));
        assertEquals(1, gun.keys(ModelCatalog.SHOT, "dym", "scale").getLast()[1], 1e-9, "smoke back inside the bore when the clip ends");
        // ram: impact (forward swing) at the catalog moment
        final Model ram = load(ModelCatalog.TARAN);
        final var swing = ram.keys(ModelCatalog.STRIKE, "liny", "rotation");
        assertTrue(swing.stream().anyMatch(k -> Math.abs(k[0] - ModelCatalog.engine(ModelCatalog.TARAN).release()) < 1e-6 && k[1] < -5));
    }
}
