// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import org.bukkit.block.data.Rail;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettlementTransportTest {
    @Test void renaissanceLimitKeepsMineCartsButBlocksIndustrialProps() {
        for (int era = 1; era <= 2; era++) {
            assertTrue(SettlementTransportService.cartAllowed(false, era));
            assertFalse(SettlementTransportService.cartAllowed(true, era));
            assertFalse(SettlementTransportService.craneAllowed("wiezowiec", era));
        }
    }

    @Test void transportUnlocksWithTheAppropriateEra() {
        assertTrue(SettlementTransportService.cartAllowed(true, 3));
        assertFalse(SettlementTransportService.craneAllowed("wiezowiec", 3));
        assertTrue(SettlementTransportService.craneAllowed("biurowiec", 4));
        assertFalse(SettlementTransportService.craneAllowed("chata", 4));
        assertFalse(SettlementTransportService.craneAllowed(null, 4));
    }
    @Test void propulsionFollowsStraightRailsOnly() {
        assertEquals(new Vector(0, 0, 1), SettlementTransportService.straightDirection(Rail.Shape.NORTH_SOUTH));
        assertEquals(new Vector(1, 0, 0), SettlementTransportService.straightDirection(Rail.Shape.EAST_WEST));
        // Curves and ramps are left to the native minecart physics, never pushed sideways off a track.
        assertNull(SettlementTransportService.straightDirection(Rail.Shape.NORTH_EAST));
        assertNull(SettlementTransportService.straightDirection(Rail.Shape.ASCENDING_EAST));
    }
}
