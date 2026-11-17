// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.menu.teleport.StoredLocation;

class HomeRulesTest {

    private static final UUID MINE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // ------------------------------------------------------------------ names

    @Test
    void validNames() {
        assertEquals(Optional.of("dom"), HomeRules.normalizeName(null));
        assertEquals(Optional.of("dom"), HomeRules.normalizeName("  "));
        assertEquals(Optional.of("kopalnia"), HomeRules.normalizeName("Kopalnia"));
        assertEquals(Optional.of("dom_2"), HomeRules.normalizeName(" dom_2 "));
        assertEquals(Optional.of("a-b"), HomeRules.normalizeName("a-b"));
        assertEquals(Optional.of("x".repeat(16)), HomeRules.normalizeName("x".repeat(16)));
    }

    @Test
    void invalidNames() {
        assertTrue(HomeRules.normalizeName("x".repeat(17)).isEmpty(), "too long");
        assertTrue(HomeRules.normalizeName("zamek króla").isEmpty(), "space");
        assertTrue(HomeRules.normalizeName("łąka").isEmpty(), "Polish letters (not a Brigadier word)");
        assertTrue(HomeRules.normalizeName("dom.1").isEmpty());
        assertTrue(HomeRules.normalizeName("<red>dom").isEmpty(), "no MiniMessage");
    }

    // ------------------------------------------------------------------ limits

    @Test
    void limitFromConfig() {
        assertEquals(1, HomeRules.limit(1, perm -> false));
        assertEquals(3, HomeRules.limit(3, perm -> false));
        assertEquals(0, HomeRules.limit(-5, perm -> false));
    }

    @Test
    void permissionRaisesButNeverLowersTheLimit() {
        assertEquals(5, HomeRules.limit(1, Set.of("menu.homes.5", "menu.homes.2")::contains));
        assertEquals(3, HomeRules.limit(3, Set.of("menu.homes.2")::contains));
        assertEquals(100, HomeRules.limit(1, Set.of("menu.homes.100")::contains));
        assertEquals(1, HomeRules.limit(1, Set.of("menu.homes.101", "menu.homes.abc")::contains));
    }

    @Test
    void unlimitedForOp() {
        assertEquals(HomeRules.UNLIMITED, HomeRules.limit(1, Set.of(HomeRules.PERM_UNLIMITED)::contains));
        // op: every undeclared permission is true
        assertEquals(HomeRules.UNLIMITED, HomeRules.limit(1, perm -> true));
    }

    @Test
    void limitBlocksNewHomesButNotOverwriting() {
        assertTrue(HomeRules.canSet(0, false, 1));
        assertFalse(HomeRules.canSet(1, false, 1));
        assertTrue(HomeRules.canSet(1, true, 1), "moving an existing home is always fine");
        assertFalse(HomeRules.canSet(0, false, 0), "homes.max: 0 disables new homes");
        assertTrue(HomeRules.canSet(500, false, HomeRules.UNLIMITED));
    }

    // ------------------------------------------------------------------ land rule

    @Test
    void landClassification() {
        assertEquals(HomeRules.Land.WILD, HomeRules.land(null, MINE));
        assertEquals(HomeRules.Land.WILD, HomeRules.land(null, null));
        assertEquals(HomeRules.Land.OWN, HomeRules.land(MINE, MINE));
        assertEquals(HomeRules.Land.FOREIGN, HomeRules.land(OTHER, MINE));
        assertEquals(HomeRules.Land.FOREIGN, HomeRules.land(OTHER, null), "no country → every country is foreign");
    }

    @Test
    void onlyOwnOrWild() {
        assertTrue(HomeRules.allowed(HomeRules.Land.WILD, true));
        assertTrue(HomeRules.allowed(HomeRules.Land.OWN, true));
        assertFalse(HomeRules.allowed(HomeRules.Land.FOREIGN, true));
        assertTrue(HomeRules.allowed(HomeRules.Land.FOREIGN, false), "rule disabled in config");
    }

    @Test
    void conquestBlocksTheHome() {
        // home set in own land, then the chunk is conquered by another country
        assertTrue(HomeRules.allowed(HomeRules.land(MINE, MINE), true));
        assertFalse(HomeRules.allowed(HomeRules.land(OTHER, MINE), true));
    }

    // ------------------------------------------------------------------ storage format

    @Test
    void jsonRoundTrip() {
        final Map<UUID, Map<String, StoredLocation>> data = Map.of(MINE, Map.of(
            "dom", new StoredLocation("world", 1.5, 64, -2.5, 90f, 0f),
            "kopalnia", new StoredLocation("world_nether", 10, 30, 10, 0f, 45f)));
        final Map<UUID, Map<String, StoredLocation>> back = HomeStore.fromJson(HomeStore.toJson(data));
        assertEquals(data, back);
    }

    @Test
    void brokenJsonEntriesAreSkipped() {
        final Map<UUID, Map<String, StoredLocation>> back = HomeStore.fromJson("""
            {
              "not-a-uuid": {"dom": {"world": "w", "x": 1, "y": 2, "z": 3}},
              "00000000-0000-0000-0000-000000000001": {
                "dom": {"world": "w", "x": 1, "y": 2, "z": 3},
                "Zła nazwa": {"world": "w", "x": 1, "y": 2, "z": 3},
                "bez_y": {"world": "w", "x": 1, "z": 3}
              },
              "00000000-0000-0000-0000-000000000002": "x"
            }
            """);
        assertEquals(Map.of(MINE, Map.of("dom", new StoredLocation("w", 1, 2, 3, 0f, 0f))), back);
    }
}
