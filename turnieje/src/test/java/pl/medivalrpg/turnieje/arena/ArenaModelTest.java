// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.turnieje.data.JsonFiles;

class ArenaModelTest {

    @Test
    void boxNormalisesCornersAndContains() {
        final Box b = Box.of("world", 10, 70, -5, 0, 60, 5);
        assertEquals(new Box("world", 0, 60, -5, 10, 70, 5), b);
        assertTrue(b.contains("world", 0, 60, -5));
        assertTrue(b.contains("world", 10, 70, 5));
        assertFalse(b.contains("world", 11, 65, 0));
        assertFalse(b.contains("world_nether", 5, 65, 0));
        assertTrue(b.contains("world", 10.99, 70.5, 5.2), "entity coordinates are floored");
        assertFalse(b.contains("world", -0.01, 65.0, 0.0));
        assertEquals(11L * 11 * 11, b.volume());
        assertEquals(5.5, b.centerX());
        assertTrue(b.touchesChunk("world", 0, -1));
        assertFalse(b.touchesChunk("world", 1, 0));
    }

    @Test
    void arenaTypesParse() {
        assertEquals(Optional.of(ArenaType.POJEDYNEK), ArenaType.parse("pojedynek"));
        assertEquals(Optional.of(ArenaType.TURNIEJ), ArenaType.parse("Turniej"));
        assertEquals(Optional.of(ArenaType.TURNIEJ), ArenaType.parse("turniej-rycerski"));
        assertEquals(Optional.of(ArenaType.MELEE), ArenaType.parse("ffa"));
        assertTrue(ArenaType.parse("wyscig").isEmpty());
        assertFalse(ArenaType.POJEDYNEK.hostsTournaments());
        assertTrue(ArenaType.MELEE.hostsTournaments());
    }

    @Test
    void gearModesParse() {
        assertEquals(Optional.of(GearMode.KIT), GearMode.parse("zestaw"));
        assertEquals(Optional.of(GearMode.OWN), GearMode.parse("własny"));
        assertEquals(Optional.of(GearMode.OWN), GearMode.parse("own-gear"));
        assertTrue(GearMode.parse("nic").isEmpty());
    }

    @Test
    void kitSpecs() {
        assertEquals(Optional.of(new KitSpec("IRON_SWORD", 1)), KitSpec.parse("iron_sword"));
        assertEquals(Optional.of(new KitSpec("ARROW", 16)), KitSpec.parse("ARROW:16"));
        assertEquals(Optional.of(new KitSpec("BREAD", 8)), KitSpec.parse("minecraft:bread:8"
            .replace("minecraft:", "")));
        assertTrue(KitSpec.parse("ARROW:0").isEmpty());
        assertTrue(KitSpec.parse("ARROW:65").isEmpty());
        assertTrue(KitSpec.parse("ARROW:x").isEmpty());
        assertTrue(KitSpec.parse("").isEmpty());
        assertTrue(KitSpec.parse("bad item").isEmpty());
    }

    @Test
    void arenaReadinessAndStandFallback() {
        final Arena a = new Arena("a1", ArenaType.TURNIEJ, Box.of("world", 0, 60, 0, 20, 70, 20));
        assertFalse(a.ready());
        a.spawns.add(new Pos("world", 2, 61, 2, 0, 0));
        a.spawns.add(new Pos("world", 18, 61, 18, 180, 0));
        assertTrue(a.ready());
        final Pos stand = a.spectatorPos();
        assertEquals(71.0, stand.y(), "above the box when no stand is set");
        assertEquals(10.5, stand.x());
        assertEquals(GearMode.KIT, a.gearOr(GearMode.KIT));
        a.gear = GearMode.OWN;
        assertEquals(GearMode.OWN, a.gearOr(GearMode.KIT));
    }

    @Test
    void arenaJsonRoundTrip() {
        final Arena a = new Arena("joust", ArenaType.TURNIEJ, Box.of("world", 0, 60, 0, 40, 70, 10));
        a.mounted = true;
        a.spawns.add(new Pos("world", 2.5, 61, 5.5, -90f, 0f));
        a.spectators = new Pos("world", 20, 75, -3, 0f, 10f);
        final Arena back = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(a), Arena.class);
        assertEquals(a.box, back.box);
        assertEquals(a.spawns, back.spawns);
        assertEquals(a.spectators, back.spectators);
        assertEquals(ArenaType.TURNIEJ, back.type);
        assertTrue(back.mounted);
    }
}
