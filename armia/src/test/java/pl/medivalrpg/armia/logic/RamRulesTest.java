// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.logic.conquest.BattleMath;
import pl.medivalrpg.armia.logic.conquest.ConquestSettings;
import pl.medivalrpg.armia.visual.ModelCatalog;
import pl.medivalrpg.armia.visual.SiegeAnimator;

/** Taran (ram): a siege engine without projectiles that wears down the blocks in front of it. */
class RamRulesTest {
    private final SiegeRules.RamSettings s = SiegeRules.RamSettings.defaults();

    @Test void ramIsAnEngineButNeverShootsNorTakesAmmunition() {
        final var engine = SiegeRules.engine("taran");
        assertNotNull(engine);
        assertTrue(engine.ram());
        assertEquals(SiegeRules.Mode.RAM, engine.mode());
        assertEquals(0, engine.shotCost());
        assertTrue(engine.reloadTicks() >= 60);
        assertTrue(engine.blockLimit() <= 4);
        assertTrue(SiegeRules.ram("taran"));
        assertFalse(SiegeRules.shoots("taran"));
        assertTrue(SiegeRules.shoots("katapulta"));
        assertTrue(SiegeRules.shoots("armata"));
        assertFalse(SiegeRules.ram("katapulta"));
        assertFalse(SiegeRules.ram(null));
        assertNull(SiegeRules.ammunition("taran", null));
        assertNull(SiegeRules.ammunition("taran", "kamien"));
        for (final var ammo : SiegeRules.Ammo.values()) assertFalse(SiegeRules.accepts("taran", ammo));
        // existing engines unchanged
        assertEquals(SiegeRules.Mode.PROJECTILE, SiegeRules.engine("katapulta").mode());
        assertEquals(SiegeRules.Ammo.STONE, SiegeRules.ammunition("katapulta", null));
    }

    @Test void ramRaisesStrengthAgainstFortificationsInAbstractBattlesLikeOtherEngines() {
        assertTrue(SiegeRules.breaksFortifications("taran"));
        assertTrue(SiegeRules.breaksFortifications("katapulta"));
        assertFalse(SiegeRules.breaksFortifications("piechota"));
        final ConquestSettings cs = ConquestSettings.defaults();
        final double fort = 0.6;
        assertTrue(BattleMath.effectiveFort(fort, 1, cs) < BattleMath.effectiveFort(fort, 0, cs));
    }

    @Test void materialClassesWoodStoneFortification() {
        final Set<String> list = s.fortificationBlocks();
        assertEquals(SiegeRules.RamClass.WOOD, SiegeRules.ramClass("OAK_DOOR", true, 3.0f, list));
        assertEquals(SiegeRules.RamClass.WOOD, SiegeRules.ramClass("SPRUCE_FENCE_GATE", true, 2.0f, list));
        assertEquals(SiegeRules.RamClass.WOOD, SiegeRules.ramClass("DIRT", false, 0.5f, list));
        assertEquals(SiegeRules.RamClass.STONE, SiegeRules.ramClass("STONE_BRICKS", false, 1.5f, list));
        assertEquals(SiegeRules.RamClass.STONE, SiegeRules.ramClass("COBBLESTONE", false, 2.0f, list));
        assertEquals(SiegeRules.RamClass.FORTIFICATION, SiegeRules.ramClass("DEEPSLATE_BRICKS", false, 3.5f, list));
        assertEquals(SiegeRules.RamClass.FORTIFICATION, SiegeRules.ramClass("IRON_DOOR", false, 5f, list));
        assertEquals(SiegeRules.RamClass.FORTIFICATION, SiegeRules.ramClass("IRON_BARS", false, 5f, list));
        assertEquals(SiegeRules.RamClass.FORTIFICATION, SiegeRules.ramClass("OBSIDIAN", false, 50f, list));
        assertNull(SiegeRules.ramClass("BEDROCK", false, -1f, list));
        assertNull(SiegeRules.ramClass("BARRIER", false, -1f, list));
        assertNull(SiegeRules.ramClass(null, false, 1f, list));
        assertNull(SiegeRules.ramClass("X", false, Float.NaN, list));
    }

    @Test void strikesNeededFollowTheConfiguredStrength() {
        assertEquals(2, s.strikes(SiegeRules.RamClass.WOOD));
        assertEquals(4, s.strikes(SiegeRules.RamClass.STONE));
        assertEquals(10, s.strikes(SiegeRules.RamClass.FORTIFICATION));
        double wear = 0;
        int strikes = 0;
        while (!SiegeRules.ramBreaks(wear) && strikes < 100) {
            wear = SiegeRules.ramDamage(wear, SiegeRules.RamClass.FORTIFICATION, s);
            strikes++;
        }
        assertEquals(10, strikes);
        assertEquals(0.5, SiegeRules.ramDamage(0, SiegeRules.RamClass.WOOD, s), 1e-9);
        assertEquals(0.3, SiegeRules.ramDamage(0.3, null, s), 1e-9);   // unbreakable: no wear
        assertEquals(0.25, SiegeRules.ramDamage(-5, SiegeRules.RamClass.STONE, s), 1e-9);
        final var zero = new SiegeRules.RamSettings(0.25, 2.5, 7, 4, 0, 0, 0, Set.of(), 3600);
        assertEquals(Integer.MAX_VALUE, zero.strikes(SiegeRules.RamClass.WOOD));
    }

    @Test void sturdyAgainstArrowsWeakAgainstFire() {
        assertEquals(2.5, SiegeRules.ramIncoming(10, true, false, s), 1e-9);
        assertEquals(25, SiegeRules.ramIncoming(10, false, true, s), 1e-9);
        assertEquals(25, SiegeRules.ramIncoming(10, true, true, s), 1e-9);  // flaming arrow
        assertEquals(10, SiegeRules.ramIncoming(10, false, false, s), 1e-9); // swords, siege shots
        assertEquals(0, SiegeRules.ramIncoming(0, true, false, s), 1e-9);
        // effective HP of the default crew (health 60): 240 against arrows, 24 against fire
        assertEquals(240, 60 / s.arrowMultiplier(), 1e-9);
        assertEquals(24, 60 / s.fireMultiplier(), 1e-9);
    }

    @Test void facingIsCardinalAndPositionNeedsTheHeadOnTheBlock() {
        assertArrayEquals(new int[] {1, 0}, SiegeRules.ramFacing(7, 0.4));
        assertArrayEquals(new int[] {-1, 0}, SiegeRules.ramFacing(-7, 2));
        assertArrayEquals(new int[] {0, 1}, SiegeRules.ramFacing(0.5, 7));
        assertArrayEquals(new int[] {0, -1}, SiegeRules.ramFacing(1, -7));
        final int[] east = {1, 0};
        assertEquals(SiegeRules.RamPlace.IN_POSITION, SiegeRules.ramPlace(7.0, 0.2, 1, east, 7.0));
        assertEquals(SiegeRules.RamPlace.IN_POSITION, SiegeRules.ramPlace(6.2, -0.9, 0, east, 7.0));
        assertEquals(SiegeRules.RamPlace.APPROACH, SiegeRules.ramPlace(12, 0, 1, east, 7.0));   // too far
        assertEquals(SiegeRules.RamPlace.APPROACH, SiegeRules.ramPlace(3, 0, 1, east, 7.0));    // too close: back off
        assertEquals(SiegeRules.RamPlace.APPROACH, SiegeRules.ramPlace(7, 2.5, 1, east, 7.0));  // beside the gate
        assertEquals(SiegeRules.RamPlace.UNREACHABLE, SiegeRules.ramPlace(7, 0, 4, east, 7.0)); // top of the wall
        assertEquals(SiegeRules.RamPlace.UNREACHABLE, SiegeRules.ramPlace(7, 0, -1, east, 7.0));
        assertEquals(SiegeRules.RamPlace.UNREACHABLE, SiegeRules.ramPlace(Double.NaN, 0, 1, east, 7.0));
    }

    @Test void faceIsThreeByThreeFromTheGroundHeadFirst() {
        final List<int[]> face = SiegeRules.ramFace();
        assertEquals(9, face.size());
        assertArrayEquals(new int[] {0, 1}, face.getFirst());
        final Set<String> seen = new HashSet<>();
        for (final int[] o : face) {
            assertTrue(o[0] >= -1 && o[0] <= 1);
            assertTrue(o[1] >= 0 && o[1] <= 2);
            assertTrue(seen.add(o[0] + ":" + o[1]));
        }
    }

    @Test void impactFrameMatchesTheStrikeClip() {
        final var taran = ModelCatalog.engine(ModelCatalog.TARAN);
        assertEquals(ModelCatalog.STRIKE, taran.shotAnimation());
        assertEquals(List.of(ModelCatalog.TARAN), ModelCatalog.defaultEngineModels("taran"));
        final int delay = SiegeRules.ramImpactDelay(taran.release());
        assertEquals(2, delay); // 0.12 s
        assertTrue(delay < SiegeAnimator.shotTicks(taran)); // the impact lies inside the strike clip
        assertEquals(0, SiegeRules.ramImpactDelay(Double.NaN));
        assertEquals(20, SiegeRules.ramImpactDelay(5));
        // the reload clip ends when the next strike is allowed (3 s)
        final float speed = SiegeAnimator.reloadSpeed(taran, 60);
        assertEquals(taran.reloadLength() * 20 / (60 - SiegeAnimator.shotTicks(taran)), speed, 1e-4);
    }
}
