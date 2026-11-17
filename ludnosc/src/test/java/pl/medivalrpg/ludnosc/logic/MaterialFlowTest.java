// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Raw material needs of blocks, hauler trip sizes and the pacing of construction sites. */
class MaterialFlowTest {

    @Test
    void woodenBlocksNeedWoodAndLogsCostFourPlanks() {
        for (final String m : List.of("OAK_PLANKS", "spruce_stairs", "minecraft:dark_oak_slab[type=top]", "BIRCH_FENCE",
            "OAK_DOOR", "SPRUCE_TRAPDOOR", "LADDER", "CHEST", "BARREL", "STRIPPED_OAK_LOG", "CHERRY_FENCE_GATE")) {
            assertEquals(MaterialNeeds.WOOD, MaterialNeeds.group(m), m);
        }
        assertEquals(4, MaterialNeeds.units("OAK_LOG"));
        assertEquals(4, MaterialNeeds.units("minecraft:spruce_wood[axis=x]"));
        assertEquals(1, MaterialNeeds.units("OAK_PLANKS"));
        assertEquals(2, MaterialNeeds.units("OAK_DOOR"));
    }

    @Test
    void stoneBlocksNeedStoneButNotSandstoneOrCraftedRedstone() {
        for (final String m : List.of("COBBLESTONE", "STONE_BRICKS", "STONE_BRICK_STAIRS", "COBBLESTONE_WALL", "SMOOTH_STONE_SLAB",
            "POLISHED_ANDESITE", "MOSSY_STONE_BRICKS", "COBBLED_DEEPSLATE", "DEEPSLATE_TILES", "STONE")) {
            assertEquals(MaterialNeeds.STONE, MaterialNeeds.group(m), m);
        }
        for (final String m : List.of("GLOWSTONE", "REDSTONE_LAMP", "STONECUTTER", "STONE_BUTTON", "DEEPSLATE_COAL_ORE", "BELL",
            "QUARTZ_BLOCK", "SOUL_TORCH", "MOSS_CARPET", "TINTED_GLASS", "AIR")) {
            assertNull(MaterialNeeds.group(m), m);
            assertEquals(0, MaterialNeeds.units(m), m);
        }
    }

    @Test
    void storedGoodsSupplyUnitsOnlyAsRawMaterial() {
        assertEquals(4, MaterialNeeds.supply("OAK_LOG", MaterialNeeds.WOOD));
        assertEquals(1, MaterialNeeds.supply("SPRUCE_PLANKS", MaterialNeeds.WOOD));
        assertEquals(0, MaterialNeeds.supply("OAK_STAIRS", MaterialNeeds.WOOD));
        assertEquals(0, MaterialNeeds.supply("OAK_LOG", MaterialNeeds.STONE));
        assertEquals(1, MaterialNeeds.supply("COBBLESTONE", MaterialNeeds.STONE));
        assertEquals(1, MaterialNeeds.supply("COBBLED_DEEPSLATE", MaterialNeeds.STONE));
        assertEquals(0, MaterialNeeds.supply("RAW_IRON", MaterialNeeds.STONE));
        assertEquals(0, MaterialNeeds.supply("WHEAT", MaterialNeeds.WOOD));
    }

    @Test
    void totalCountsUnitsPerGroup() {
        final Map<String, Long> t = MaterialNeeds.total(List.of("OAK_LOG", "OAK_PLANKS", "COBBLESTONE", "GLASS", "STONE_BRICKS"));
        assertEquals(5L, t.get(MaterialNeeds.WOOD));
        assertEquals(2L, t.get(MaterialNeeds.STONE));
        assertEquals(8L, t.get(MaterialNeeds.GLASS));
        assertEquals(3, t.size());
        assertEquals("kamień", MaterialNeeds.displayName(MaterialNeeds.STONE));
    }

    @Test
    void aVillageWithoutProductionOrStoresNeverStalls() {
        assertFalse(MaterialNeeds.mustWait(false, true, 0, 3_600_000));   // no sawmill / quarry: bought with coins
        assertFalse(MaterialNeeds.mustWait(true, false, 0, 3_600_000));   // no registered store at all
        assertTrue(MaterialNeeds.mustWait(true, true, 0, 3_600_000));     // produces it: waits for the haulers
        assertTrue(MaterialNeeds.mustWait(true, true, 3_599_999, 3_600_000));
        assertFalse(MaterialNeeds.mustWait(true, true, 3_600_000, 3_600_000)); // after the limit: bought, never blocked
        assertTrue(MaterialNeeds.mustWait(true, true, Long.MAX_VALUE / 2, 0)); // 0 = wait for real material forever
    }

    @Test
    void haulTripCoversTheNeedWithinLoadAndStock() {
        assertEquals(3, HaulPlan.amount(10, 4, 50, 32));   // 10 units of wood = 3 logs
        assertEquals(32, HaulPlan.amount(500, 1, 64, 32)); // one load
        assertEquals(5, HaulPlan.amount(500, 1, 5, 32));   // what the store has
        assertEquals(0, HaulPlan.amount(0, 1, 5, 32));
        assertEquals(0, HaulPlan.amount(10, 0, 5, 32));
        assertTrue(HaulPlan.shouldUnload(21, 27, 0.75));
        assertFalse(HaulPlan.shouldUnload(20, 27, 0.75));
        assertFalse(HaulPlan.shouldUnload(0, 0, 0.75));
        assertEquals(20_000L + 50_000L, HaulPlan.deadlineMillis(50, 2.5));
    }

    @Test
    void loadedSiteCreditNeverBurstsWhileBuildersWalk() {
        double credit = 0;
        for (int i = 0; i < 100; i++) { // the builder walks for 50 s, nothing is placed
            credit = SitePacing.accrue(credit, 2, true, true, true, false, 4096);
        }
        assertEquals(6.0, credit);
        assertFalse(SitePacing.catchUp(credit, 2));
    }

    @Test
    void offlineCreditCatchesUpAndNightOrWaitingEarnsNothing() {
        double credit = 0;
        for (int i = 0; i < 100; i++) {
            credit = SitePacing.accrue(credit, 2, true, false, true, false, 4096);
        }
        assertEquals(200.0, credit);
        assertTrue(SitePacing.catchUp(credit, 2));
        assertEquals(200.0, SitePacing.accrue(credit, 2, true, true, true, false, 4096)); // loaded: keeps the backlog
        assertEquals(5.0, SitePacing.accrue(5, 2, false, true, true, false, 4096));
        assertEquals(5.0, SitePacing.accrue(5, 2, true, true, true, true, 4096));
        assertEquals(5.0, SitePacing.accrue(5, 2, true, false, false, false, 4096));
        assertEquals(12.0, SitePacing.accrue(11, 2, true, false, true, false, 10)); // remaining + one period
    }

    @Test
    void templateChangeNeverSendsAStartedBuildingBackToEarthworks() {
        assertEquals(SitePacing.FOUNDATION, SitePacing.phaseAfterTemplateChange("build", SitePacing.CELLS));
        assertEquals(SitePacing.FOUNDATION, SitePacing.phaseAfterTemplateChange(null, SitePacing.ROAD));
        assertEquals(SitePacing.TERRAIN, SitePacing.phaseAfterTemplateChange("build", SitePacing.TERRAIN));
        assertEquals(SitePacing.CELLS, SitePacing.phaseAfterTemplateChange("repair", SitePacing.TERRAIN));
        assertEquals(SitePacing.CELLS, SitePacing.phaseAfterTemplateChange("upgrade", SitePacing.CELLS));
        assertEquals(SitePacing.ROAD, SitePacing.phaseAfterTemplateChange("road", SitePacing.TERRAIN));
    }
}
