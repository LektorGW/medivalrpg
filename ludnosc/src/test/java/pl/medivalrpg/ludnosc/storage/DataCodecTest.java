// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.service.LudnoscData;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

class DataCodecTest {

    private static final UUID COUNTRY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static LudnoscData sample() {
        final LudnoscData data = new LudnoscData();
        data.putCitizen(new CitizenRecord(UUID.randomUUID(), COUNTRY, "Bolesław", "minecraft:farmer", true, "world", 10, 64, -20, 5L, 0));
        data.putCitizen(new CitizenRecord(UUID.randomUUID(), COUNTRY, "Jadwiga", "minecraft:none", false, "world", 33, 70, 40, 6L, 1));
        data.putCitizen(new CitizenRecord(UUID.randomUUID(), OTHER, "Mieszko", "minecraft:librarian", true, "world_nether", 0, 50, 0, 7L, 0));
        data.putBuilding(new Building(UUID.randomUUID(), COUNTRY, "dom", BuildingFunction.HOUSING,
            new BlockBox("world", 0, 60, 0, 10, 73, 8), 4, new BlockPos("world", 3, 65, 3), 1, 9L, false));
        data.putBuilding(new Building(UUID.randomUUID(), null, "spichlerz", BuildingFunction.GRANARY,
            new BlockBox("world", 100, 60, 100, 109, 70, 110), 0, null, 0, 9L, false));
        final ConstructionSite s = new ConstructionSite();
        s.id = UUID.randomUUID();
        s.countryId = COUNTRY;
        s.type = "chata";
        s.world = "world";
        s.originX = 50;
        s.originY = 64;
        s.originZ = 50;
        s.rotation = 3;
        s.cursor = 120;
        s.credit = 3.5;
        s.cost = 1500;
        s.templateHash = 123456789L;
        s.validated = true;
        s.felledTrees = 5;               // citizens' hut: trees already felled (must survive a restart)
        s.materials().put("drewno", 3);  // wood cut on the site
        data.putSite(s);
        final CountryState st = data.state(COUNTRY);
        st.food = 321;
        st.hungryDays = 2;
        st.chests().add(new BlockPos("world", 1, 2, 3));
        st.origins().put("b-1", "WorldSteward");
        data.tombstone(UUID.randomUUID(), 99L);
        data.setLastDaily(LocalDate.of(2026, 10, 25));
        return data;
    }

    @Test
    void roundTripKeepsEverything() {
        final LudnoscData original = sample();
        final String json = DataCodec.encode(original);
        final LudnoscData loaded = new LudnoscData();
        DataCodec.apply(DataCodec.decode(json), loaded);

        assertEquals(2, loaded.population(COUNTRY));
        assertEquals(1, loaded.population(OTHER));
        assertEquals(4, loaded.housing(COUNTRY), "granary beds and ownerless buildings do not count");
        assertEquals(2, loaded.allBuildings().size());
        assertEquals(1, loaded.citizensIn(new ChunkRef("world", 0, -2)).size(), "chunk index rebuilt");
        assertNotNull(loaded.buildingAt("world", 5, 65, 5));
        assertNull(loaded.buildingAt("world", 50, 65, 50));
        final ConstructionSite site = loaded.sitesOf(COUNTRY).get(0);
        assertEquals("chata", site.type);
        assertEquals(3, site.rotation);
        assertEquals(120, site.cursor);
        assertEquals(3.5, site.credit);
        assertEquals(123456789L, site.templateHash);
        assertTrue(site.validated);
        assertEquals(5, site.felledTrees, "the felled-tree limit of a hut survives a restart");
        assertEquals(3, site.materials().get("drewno"));
        assertEquals("WorldSteward", loaded.state(COUNTRY).origins().get("b-1"), "who ordered a building survives a restart");
        assertEquals(321, loaded.state(COUNTRY).food);
        assertEquals(2, loaded.state(COUNTRY).hungryDays);
        assertTrue(loaded.state(COUNTRY).chests().contains(new BlockPos("world", 1, 2, 3)));
        assertEquals(1, loaded.tombstones().size());
        assertEquals(LocalDate.of(2026, 10, 25), loaded.lastDaily());
        assertFalse(loaded.takeDirty(), "loading does not mark dirty");
        // stable: encode(decode(x)) == x
        assertEquals(DataCodec.decode(json).citizens().size(), DataCodec.decode(DataCodec.encode(loaded)).citizens().size());
    }

    @Test
    void registryIndexesFollowChanges() {
        final LudnoscData data = sample();
        final CitizenRecord r = data.citizensOf(COUNTRY).get(0);
        data.putCitizen(r.withCountry(OTHER));
        assertEquals(1, data.population(COUNTRY));
        assertEquals(2, data.population(OTHER));
        data.putCitizen(data.citizen(r.entityId()).withState("world", 500, 64, 500, "minecraft:mason", true));
        assertEquals(1, data.citizensIn(new ChunkRef("world", 31, 31)).size());
        assertTrue(data.citizensIn(r.chunk()).stream().noneMatch(c -> c.entityId().equals(r.entityId())));
        data.removeCitizen(r.entityId());
        assertEquals(1, data.population(OTHER));
        assertTrue(data.takeTombstone(data.tombstones().keySet().iterator().next()));
    }

    @Test
    void minesAndBuildingLevelsSurviveARestart() {
        final LudnoscData data = sample();
        data.state(COUNTRY).mines().put("0b6f6c8e-0000-0000-0000-000000000001", new int[] {10, 52, -4, 1, 37, 5, 50, 0});
        final LudnoscData loaded = new LudnoscData();
        DataCodec.apply(DataCodec.decode(DataCodec.encode(data)), loaded);
        final int[] m = loaded.state(COUNTRY).mines().get("0b6f6c8e-0000-0000-0000-000000000001");
        assertNotNull(m);
        assertEquals(37, m[4], "gallery length");
        assertEquals(5, m[5], "remaining steps");
        assertTrue(loaded.state(OTHER).mines().isEmpty(), "older data without mines");
    }

    @Test
    void hasBuildingCountsOnlyOwnedFinishedBuildings() {
        final LudnoscData data = sample();
        assertTrue(data.hasBuilding(COUNTRY, "dom"));
        assertTrue(data.hasBuilding(COUNTRY, "DOM"));
        assertFalse(data.hasBuilding(COUNTRY, "koszary"));
        assertFalse(data.hasBuilding(COUNTRY, "chata"), "a construction site is not a finished building");
        assertFalse(data.hasBuilding(COUNTRY, "spichlerz"), "ownerless building");
        assertFalse(data.hasBuilding(OTHER, "dom"));
        assertFalse(data.hasBuilding(null, "dom"));
        assertFalse(data.hasBuilding(COUNTRY, null));
    }

    @Test
    void queueRoundTripKeepsOrderPerCountry() {
        final LudnoscData data = sample();
        final QueuedOrder a = QueuedOrder.of(COUNTRY, "dom", "world", 10, 64, 10, 1, 4000, "Bolek");
        final QueuedOrder b = QueuedOrder.of(OTHER, "chata", "world", 90, 64, 90, 0, 1500, "API");
        final QueuedOrder c = QueuedOrder.of(COUNTRY, "kuznia", "world", 40, 64, 40, 3, 3500, "Lolek");
        data.enqueue(a);
        data.enqueue(b);
        data.enqueue(c);
        data.moveQueued(COUNTRY, 1, -1); // c before a
        final LudnoscData loaded = new LudnoscData();
        DataCodec.apply(DataCodec.decode(DataCodec.encode(data)), loaded);
        final List<QueuedOrder> q = loaded.queue(COUNTRY);
        assertEquals(List.of(c.id, a.id), q.stream().map(o -> o.id).toList());
        assertEquals("kuznia", q.get(0).type);
        assertEquals(3, q.get(0).rotation);
        assertEquals(3500, q.get(0).cost);
        assertEquals("Lolek", q.get(0).orderedBy);
        assertEquals(1, loaded.queueSize(OTHER));
    }

    @Test
    void oldDataWithoutQueueStillLoads() {
        // data.json written by the first deployed version (version 1, no "queue" field)
        final String v1 = """
            {
              "version": 1,
              "lastDaily": "2026-10-24",
              "citizens": [
                {"entityId": "00000000-0000-0000-0000-0000000000aa", "countryId": "00000000-0000-0000-0000-000000000001",
                 "name": "Bolesław", "profession": "minecraft:farmer", "adult": true, "world": "world",
                 "x": 1, "y": 64, "z": 2, "since": 5, "missed": 0}
              ],
              "buildings": [],
              "sites": [
                {"id": "00000000-0000-0000-0000-0000000000bb", "countryId": "00000000-0000-0000-0000-000000000001",
                 "type": "chata", "world": "world", "originX": 5, "originY": 64, "originZ": 5, "rotation": 0,
                 "cursor": 10, "credit": 0.0, "createdAt": 1, "cost": 1500, "templateHash": 7, "validated": true,
                 "orderedBy": "Gracz"}
              ],
              "countries": {"00000000-0000-0000-0000-000000000001": {"food": 50, "hungryDays": 0, "homelessDays": 0,
                 "lastTax": 0, "lastProduced": 0, "lastEaten": 0, "lastDepartures": 0, "granaryChests": []}},
              "tombstones": {}
            }
            """;
        final LudnoscData loaded = new LudnoscData();
        DataCodec.apply(DataCodec.decode(v1), loaded);
        assertEquals(1, loaded.population(COUNTRY));
        assertEquals(1, loaded.sitesOf(COUNTRY).size());
        assertEquals(50, loaded.state(COUNTRY).food);
        assertEquals(0, loaded.queueSize(COUNTRY));
        assertTrue(loaded.allQueued().isEmpty());
        assertEquals(LocalDate.of(2026, 10, 24), loaded.lastDaily());
        // and is written back in the new format
        assertTrue(DataCodec.encode(loaded).contains("\"queue\""));
    }

    @Test
    void brokenJsonFails() {
        assertThrows(RuntimeException.class, () -> DataCodec.decode("{not json"));
        assertThrows(RuntimeException.class, () -> DataCodec.decode(""));
    }

    @Test
    void boxGeometry() {
        final BlockBox b = BlockBox.of("world", 20, 70, 5, 10, 60, -5);
        assertEquals(10, b.minX());
        assertEquals(11 * 11 * 11, b.volume());
        assertEquals(new ChunkRef("world", 0, 0), b.anchorChunk());
        assertEquals(4, b.chunks().size());
        assertTrue(b.intersects(new BlockBox("world", 20, 70, 5, 30, 80, 9)));
        assertFalse(b.intersects(new BlockBox("world", 21, 60, 5, 30, 80, 9)));
        assertFalse(b.intersects(new BlockBox("other", 10, 60, -5, 20, 70, 5)));
    }
}
