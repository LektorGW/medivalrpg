// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.logic.BuildFixes.Readiness;
import pl.medivalrpg.ludnosc.logic.BuildFixes.TicketCandidate;
import pl.medivalrpg.ludnosc.model.AvoidSpot;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.planner.TicketAllocator;

class BuildFixesTest {

    /** Production 03.11: five NPC levellings past the daily limit held all 64 tickets; 51 of 56 sites stood. */
    @Test
    void idleSitesGetNoTicketsAndPlayersComeFirst() {
        final List<TicketCandidate> c = new ArrayList<>();
        final List<TicketAllocator.Demand> demands = new ArrayList<>();
        final List<UUID> levels = new ArrayList<>();
        for (int i = 0; i < 5; i++) { // the oldest: NPC levelling of 9–16 chunks after the daily limit
            final UUID id = UUID.randomUUID();
            levels.add(id);
            c.add(new TicketCandidate(id, i, false, Readiness.IDLE));
        }
        final UUID npcHouse = UUID.randomUUID();
        final UUID playerMine = UUID.randomUUID();
        final UUID waiting = UUID.randomUUID();
        c.add(new TicketCandidate(waiting, 6, true, Readiness.WAITING));
        c.add(new TicketCandidate(npcHouse, 10, false, Readiness.WORKING));
        c.add(new TicketCandidate(playerMine, 20, true, Readiness.WORKING));
        final List<UUID> order = BuildFixes.ticketOrder(c);
        assertEquals(List.of(playerMine, npcHouse, waiting), order);
        for (final UUID id : order) {
            demands.add(new TicketAllocator.Demand(id, Set.of(new ChunkRef("world", order.indexOf(id), 0))));
        }
        assertEquals(3, TicketAllocator.allocate(demands, 64).sites().size());
        assertTrue(BuildFixes.levelLimitReached(10, 10, 4000, 4000, false));
        assertFalse(BuildFixes.levelLimitReached(11, 10, 4000, 4000, false), "nowy dzień");
        assertFalse(BuildFixes.levelLimitReached(10, 10, 9000, 4000, true), "wymuszona przebudowa bez limitu");
    }

    /** Production: the road of England 74e72caf wanted its ~56-chunk rectangle (> 64 with the rest) and stood 113 h. */
    @Test
    void longDiagonalRoadsLoadOnlyTheChunksOfTheirCells() {
        final List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            cells.add(new int[] {i, i}); // 120 blocks diagonally
        }
        final Set<ChunkRef> chunks = BuildFixes.cellChunks("world", cells, 3);
        final BlockBox rect = new BlockBox("world", 0, 60, 0, 119, 70, 119);
        assertEquals(64, rect.chunks().size());
        assertTrue(chunks.size() <= 30, "komórki: " + chunks.size());
        for (final int[] p : cells) {
            assertTrue(chunks.contains(new ChunkRef("world", p[0] >> 4, p[1] >> 4)));
            assertTrue(chunks.contains(new ChunkRef("world", (p[0] + 3) >> 4, (p[1] - 3) >> 4)), "margines");
        }
    }

    /** Production: "buduj chata_zrebowa" paid and built "chata"; "tartak_kryty" → "tartak". */
    @Test
    void anExplicitVariantIsBuiltAsTyped() {
        final List<String> family = List.of("chata", "chata_szachulec", "chata_zrebowa");
        for (int x = -50; x < 50; x += 7) {
            assertEquals("chata_zrebowa", BuildFixes.chooseVariant("chata_zrebowa", "chata", family, "world", x, 3));
            assertTrue(family.contains(BuildFixes.chooseVariant("chata", "chata", family, "world", x, 3)));
        }
        assertEquals("tartak_kryty", BuildFixes.chooseVariant("tartak_kryty", "tartak", List.of("tartak", "tartak_kryty"), "w", 1, 1));
        assertEquals("studnia", BuildFixes.chooseVariant("studnia", "studnia", List.of("studnia"), "w", 1, 1));
        // the base type still varies between plots
        final Set<String> seen = new java.util.HashSet<>();
        for (int x = 0; x < 400; x += 4) {
            seen.add(BuildFixes.chooseVariant("chata", "chata", family, "world", x, x * 3));
        }
        assertEquals(3, seen.size());
    }

    /** Minecraft 26.x: #minecraft:dirt no longer holds grass_block; grass is still natural ground. */
    @Test
    void grassIsSoil() {
        for (final String m : List.of("GRASS_BLOCK", "DIRT", "PODZOL", "MYCELIUM", "COARSE_DIRT", "ROOTED_DIRT", "MUD", "dirt_path")) {
            assertTrue(BuildFixes.soil(m), m);
        }
        for (final String m : List.of("FARMLAND", "COBBLESTONE", "OAK_PLANKS", "STONE")) {
            assertFalse(BuildFixes.soil(m), m);
        }
    }

    @Test
    void leavesAndSnowAreNoObstacle() {
        for (final String m : List.of("OAK_LEAVES", "SPRUCE_LEAVES", "AZALEA_LEAVES", "CHERRY_LEAVES", "SNOW", "SNOW_BLOCK", "POWDER_SNOW", "VINE", "SCAFFOLDING",
            "OAK_SAPLING")) {
            assertTrue(BuildFixes.clearable(m), m);
        }
        for (final String m : List.of("OAK_LOG", "COBBLESTONE", "CHEST", "OAK_PLANKS", "GLASS", "STONE")) {
            assertFalse(BuildFixes.clearable(m), m);
        }
    }

    /** Production: Rome ordered the same obstructed plot 150× in 3 days (paid 5000, refunded after the chunk loaded). */
    @Test
    void refusedPlotsAreRememberedOnceAndExpire() {
        final AvoidSpot a = new AvoidSpot("world", "dom", 0, 0, 8, 8, 1000, "Blok X");
        final AvoidSpot b = new AvoidSpot("world", "dom", 20, 0, 28, 8, 1000, "Blok Y");
        List<AvoidSpot> list = BuildFixes.remember(null, a, 0, 64);
        list = BuildFixes.remember(list, b, 0, 64);
        list = BuildFixes.remember(list, new AvoidSpot("world", "dom", 0, 0, 8, 8, 2000, "znowu"), 0, 64);
        assertEquals(2, list.size(), "to samo miejsce raz");
        assertEquals("znowu", list.getLast().reason());
        assertEquals(2, BuildFixes.active(list, "world", 500).size());
        assertEquals(1, BuildFixes.active(list, "world", 1500).size(), "wygasło");
        assertEquals(0, BuildFixes.active(list, "world_nether", 0).size());
        List<AvoidSpot> many = null;
        for (int i = 0; i < 100; i++) {
            many = BuildFixes.remember(many, new AvoidSpot("world", "dom", i * 20, 0, i * 20 + 8, 8, 5000, ""), 0, 64);
        }
        assertEquals(64, many.size());
        assertEquals(99 * 20, many.getLast().minX());
    }

    /** Production: an expired preview + "buduj anuluj" removed the site under the player with a fixed 50 %. */
    @Test
    void anUntouchedSiteIsRefundedInFull() {
        assertTrue(BuildFixes.untouched(0, 0, 0));
        assertFalse(BuildFixes.untouched(0, 5, 0));
        assertFalse(BuildFixes.untouched(2, 0, 0));
        assertEquals(1.0, BuildFixes.cancelRefund(true, 0.5));
        assertEquals(0.5, BuildFixes.cancelRefund(false, 0.5));
        assertEquals(1.0, BuildFixes.cancelRefund(false, 7));
    }

    @Test
    void repairsSayTheRealReason() {
        assertNull(BuildFixes.repairRefusal(false, false, false, false, false, false, "1 zł"));
        assertTrue(BuildFixes.repairRefusal(false, false, false, false, false, true, "12 zł").contains("12 zł"));
        assertTrue(BuildFixes.repairRefusal(false, false, false, true, false, true, "1 zł").contains("już w naprawie"));
        assertTrue(BuildFixes.repairRefusal(false, false, false, false, true, false, "1 zł").contains("szablonu"));
        assertTrue(BuildFixes.repairRefusal(true, false, false, false, false, false, "1 zł").contains("KRÓLESTWA"));
        assertTrue(BuildFixes.repairRefusal(false, true, false, false, false, false, "1 zł").contains("królewska"));
    }

    /** Production: Watykan fb4ab7c4 (395 44 -370) and Grünberg a308352b stopped on their own cobblestone / posts. */
    @Test
    void minesDigTheirOwnGalleryBlocksUnderground() {
        assertTrue(BuildFixes.mineMayDig("COBBLESTONE", 44, 70, false));
        assertTrue(BuildFixes.mineMayDig("OAK_LOG", 44, 70, false));
        assertTrue(BuildFixes.mineMayDig("RAIL", 39, 70, false));
        assertTrue(BuildFixes.mineMayDig("SANDSTONE", 70, 72, false), "naturalny piaskowiec");
        assertFalse(BuildFixes.mineMayDig("COBBLESTONE", 69, 70, false), "przy powierzchni: może być budowla");
        assertFalse(BuildFixes.mineMayDig("COBBLESTONE", 40, 70, true), "w innym zarejestrowanym budynku");
        assertFalse(BuildFixes.mineMayDig("CHEST", 40, 70, false));
        assertFalse(BuildFixes.mineMayDig("STONE_BRICKS", 40, 70, false));
    }
}
