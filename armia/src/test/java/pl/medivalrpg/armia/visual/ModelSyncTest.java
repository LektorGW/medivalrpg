// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.naval.DeckRules;
import pl.medivalrpg.armia.naval.NavalRules;

/**
 * Position/rotation sync between the model and the physical layer. BetterModel turns a model by
 * Ry(180 - modelYaw) with its -Z side forward; ShipVisual passes modelYaw = yaw + 180, which must be exactly the
 * rotation FleetService uses for the deck colliders (DeckRules.toWorld). The deck the players see must lie on the
 * invisible shulker tiles at every heading.
 */
class ModelSyncTest {
    /** The BetterModel transform of a model point (pixels) for a tracker at (x,y,z) with rotation modelYaw. */
    static double[] betterModel(final double x, final double y, final double z, final float modelYaw, final double[] px) {
        final double a = Math.toRadians(180 - modelYaw), c = Math.cos(a), s = Math.sin(a);
        final double lx = px[0] / 16, ly = px[1] / 16, lz = px[2] / 16;
        return new double[] {x + c * lx + s * lz, y + ly, z - s * lx + c * lz};
    }

    @Test void modelRotationMatchesDeckFrameAtEveryHeading() {
        for (int yaw = -180; yaw < 180; yaw += 3) {
            final DeckRules.Frame frame = new DeckRules.Frame(100.25, 62.9, -40.5, yaw);
            for (final double[] px : new double[][] {{0, 0, 16}, {16, 0, 0}, {-24, 40, 61}, {30, 48, -74}, {0, 70, 0}}) {
                final double[] bm = betterModel(frame.x(), frame.y(), frame.z(), yaw + 180, px);
                final DeckRules.Point dr = DeckRules.toWorld(frame, new DeckRules.Point(px[0] / 16, px[1] / 16, px[2] / 16));
                assertEquals(dr.x(), bm[0], 1e-9, "x at yaw " + yaw);
                assertEquals(dr.y(), bm[1], 1e-9);
                assertEquals(dr.z(), bm[2], 1e-9, "z at yaw " + yaw);
            }
            // bow (+Z of the model) points where the boat sails: HelmRules forward = (-sin, cos)
            final double[] bow = betterModel(0, 0, 0, yaw + 180, new double[] {0, 0, 16});
            final double[] f = VisualRules.forward(yaw);
            assertEquals(f[0], bow[0], 1e-9);
            assertEquals(f[1], bow[2], 1e-9);
        }
    }

    private static JsonObject model(final String name) throws Exception {
        try (var in = ModelSyncTest.class.getResourceAsStream("/models/" + name + ".bbmodel")) {
            assertNotNull(in, name);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static double[] deckCube(final JsonObject model) {
        for (final var e : model.getAsJsonArray("elements")) {
            final var o = e.getAsJsonObject();
            if (o.get("name").getAsString().equals("poklad")) {
                final var f = o.getAsJsonArray("from");
                final var t = o.getAsJsonArray("to");
                return new double[] {f.get(0).getAsDouble(), f.get(1).getAsDouble(), f.get(2).getAsDouble(),
                    t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble()};
            }
        }
        throw new AssertionError("no deck cube");
    }

    @Test void visibleDeckLiesOnTheShulkerTilesWhileTheShipTurnsAndSails() throws Exception {
        for (final String[] pair : new String[][] {{"transportowiec", ModelCatalog.KOGA}, {"karawela", ModelCatalog.KARAWELA}}) {
            final var type = NavalRules.type(pair[0]);
            final double[] deck = deckCube(model(pair[1]));
            assertEquals(DeckRules.deckHeight(type) * 16, deck[4], 1e-9, "model deck top = collider top");
            final double walk = DeckRules.walkableHalfLength(type);
            assertTrue(deck[5] / 16 >= walk && -deck[2] / 16 >= walk, "planks reach the walkable ends");
            final double hw = type.halfWidth() - .13;
            int samples = 0;
            for (int yaw = -180; yaw < 180; yaw += 5) {
                // a boat sailing: frame moves as the fleet moves it
                final DeckRules.Frame frame = new DeckRules.Frame(10 + yaw * .24, 62.9, 5 - yaw * .1, yaw);
                for (double lx = -hw; lx <= hw + 1e-9; lx += hw / 4) for (double lz = -walk + .01; lz <= walk; lz += (walk - .01) / 8) {
                    final double[] world = betterModel(frame.x(), frame.y(), frame.z(), yaw + 180, new double[] {lx * 16, deck[4], lz * 16});
                    final DeckRules.Point p = new DeckRules.Point(world[0], world[1], world[2]);
                    assertTrue(DeckRules.onDeck(type, frame, p), "visible deck point outside the walkable deck at yaw " + yaw);
                    assertTrue(supported(type, frame, world), "visible deck point without a collider under it at yaw " + yaw
                        + " local " + lx + "," + lz);
                    samples++;
                }
            }
            assertTrue(samples > 2000);
        }
    }

    /** A 0.6 wide player standing at this deck point rests on at least one tile AABB at deck height. */
    private static boolean supported(final NavalRules.Type type, final DeckRules.Frame frame, final double[] p) {
        for (final DeckRules.Tile tile : DeckRules.tiles(type)) {
            final DeckRules.Point c = DeckRules.toWorld(frame, new DeckRules.Point(tile.x(), 0, tile.z()));
            final double h = tile.scale() / 2;
            if (Math.abs(frame.y() + DeckRules.deckHeight(type) - p[1]) > 1e-9) return false;
            if (p[0] + .3 > c.x() - h && p[0] - .3 < c.x() + h && p[2] + .3 > c.z() - h && p[2] - .3 < c.z() + h) return true;
        }
        return false;
    }

    @Test void swellKeepsTheVisibleDeckNearTheColliders() throws Exception {
        for (final String name : List.of(ModelCatalog.KOGA, ModelCatalog.KARAWELA)) {
            final JsonObject m = model(name);
            final double[] deck = deckCube(m);
            double maxRot = 0, maxHeave = 0;
            for (final var a : m.getAsJsonArray("animations")) {
                if (!a.getAsJsonObject().get("name").getAsString().equals(ModelCatalog.WAVES)) continue;
                for (final var an : a.getAsJsonObject().getAsJsonObject("animators").entrySet())
                    for (final var k : an.getValue().getAsJsonObject().getAsJsonArray("keyframes")) {
                        final var o = k.getAsJsonObject();
                        final var d = o.getAsJsonArray("data_points").get(0).getAsJsonObject();
                        if (o.get("channel").getAsString().equals("rotation"))
                            maxRot = Math.max(maxRot, Math.max(Math.abs(d.get("x").getAsDouble()), Math.abs(d.get("z").getAsDouble())));
                        else maxHeave = Math.max(maxHeave, Math.abs(d.get("y").getAsDouble()));
                    }
            }
            final double reach = Math.max(Math.abs(deck[2]), Math.abs(deck[5])) / 16;
            final double offset = reach * Math.sin(Math.toRadians(maxRot)) + maxHeave / 16;
            assertTrue(offset <= 0.13, name + ": swell moves the deck ends by " + offset);
            assertTrue(maxRot > 0.3, "the ship visibly rocks");
        }
    }

    @Test void visibilityHasHysteresisAndBudget() {
        assertTrue(VisualRules.visible(false, 95 * 95, 96, 112));
        assertFalse(VisualRules.visible(false, 100 * 100, 96, 112));
        assertTrue(VisualRules.visible(true, 100 * 100, 96, 112), "shown model is not dropped at the edge");
        assertFalse(VisualRules.visible(true, 113 * 113, 96, 112));
        assertFalse(VisualRules.visible(false, Double.NaN, 96, 112));
        assertTrue(VisualRules.admit(23, 24));
        assertFalse(VisualRules.admit(24, 24));
        assertFalse(VisualRules.admit(0, 0));
    }

    @Test void engineColliderNeverTouchesItsCrewAndLeavesRoomAround() {
        for (final String model : List.of(ModelCatalog.TREBUSZ, ModelCatalog.KATAPULTA, ModelCatalog.TARAN, ModelCatalog.ARMATA)) {
            final var engine = ModelCatalog.engine(model);
            for (int yaw = -180; yaw < 180; yaw += 7) {
                final double[] box = VisualRules.colliderBox(3.5, 70, -8.25, yaw, engine);
                final double[] crew = {3.5 - .3, 70, -8.25 - .3, 3.5 + .3, 71.95, -8.25 + .3};
                assertFalse(VisualRules.overlaps(box, crew, 0.25), model + " collider reaches its crew at yaw " + yaw);
                assertEquals(engine.colliderSize(), box[3] - box[0], 1e-9);
                assertTrue(box[4] - box[1] < 1.9, "never a wall taller than a player");
            }
            assertTrue(engine.colliderSize() <= 1.5, "small enough to walk round");
        }
        final double[] a = {0, 0, 0, 1, 1, 1};
        assertTrue(VisualRules.overlaps(a, new double[] {0.9, 0, 0.9, 2, 1, 2}, 0));
        assertFalse(VisualRules.overlaps(a, new double[] {1.2, 0, 0, 2, 1, 1}, 0.1));
        assertTrue(VisualRules.overlaps(a, new double[] {1.2, 0, 0, 2, 1, 1}, 0.3));
        assertTrue(VisualRules.colliderWanted(true, true, 40, 40, false));
        assertFalse(VisualRules.colliderWanted(true, true, 39, 40, false));
        assertFalse(VisualRules.colliderWanted(true, true, 400, 40, true), "never spawned into somebody");
        assertFalse(VisualRules.colliderWanted(false, true, 400, 40, false));
        assertTrue(VisualRules.still(0.005, 0.2f));
        assertFalse(VisualRules.still(0.02, 0));
        assertFalse(VisualRules.still(0, 2));
        assertTrue(VisualRules.still(0, 359.9f));
    }
}
