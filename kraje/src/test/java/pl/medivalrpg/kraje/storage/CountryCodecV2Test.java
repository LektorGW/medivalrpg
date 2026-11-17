// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.model.TreasuryEntry;
import pl.medivalrpg.kraje.util.ChunkKeys;

/** Data compatibility: files written by the first Kraje version (format 1) and the new fields of format 2. */
class CountryCodecV2Test {

    /** Exactly what Kraje 1.0 (format 1) wrote to countries.json on the server. */
    private static final String VERSION_1 = """
        {
          "version": 1,
          "countries": [
            {
              "id": "2b1f0a57-7f5c-4d3e-9b8e-0c6f1d2a3b4c",
              "name": "Aldoria",
              "color": "#1E88E5",
              "description": "Królestwo północy",
              "ruler": "11111111-1111-3111-8111-111111111111",
              "foundedAt": 1792960000000,
              "members": [
                {"uuid": "11111111-1111-3111-8111-111111111111", "name": "Maciej", "rank": "RULER", "joinedAt": 1792960000000},
                {"uuid": "22222222-2222-3222-8222-222222222222", "name": "Doradca1", "rank": "ADVISOR", "joinedAt": 1792960001000}
              ],
              "claims": {"world": [[-3, 7], [-2, 7], [-3, 8]]},
              "capital": {"world": "world", "x": -3, "z": 7},
              "spawn": {"world": "world", "x": -40.5, "y": 70.0, "z": 120.25, "yaw": 90.0, "pitch": 10.0},
              "relations": {"3c2d1e0f-1111-4222-8333-444455556666": "WAR"}
            }
          ]
        }
        """;

    @Test
    void readsVersion1WithDefaults() {
        final JsonObject root = JsonParser.parseString(VERSION_1).getAsJsonObject();
        final List<Country> list = CountryCodec.decodeAll(root);
        assertEquals(0L, CountryCodec.decodeLastDailyTick(root));
        assertEquals(1, list.size());
        final Country c = list.get(0);
        assertEquals("Aldoria", c.name());
        assertEquals(3, c.claimCount());
        assertEquals(Relation.WAR, c.relationTo(UUID.fromString("3c2d1e0f-1111-4222-8333-444455556666")));
        assertEquals("Maciej", c.rulerName());
        // new fields → defaults
        assertFalse(c.npc());
        assertEquals("", c.npcRulerName());
        assertEquals(0L, c.taxPerCitizen());
        assertEquals(0, c.unpaidUpkeepDays());
        assertTrue(c.warSinceMap().isEmpty());
        assertTrue(c.warEndedAtMap().isEmpty());
        assertTrue(c.treasuryLog().isEmpty());
        for (final Member m : c.members()) {
            assertEquals(0L, m.taxDebt());
            assertEquals(0, m.debtDays());
        }
        // and it loads into the manager (country at war with a country that no longer exists is cleaned)
        final CountryManager manager = new CountryManager(Logger.getAnonymousLogger());
        manager.loadAll(list, 0L);
        final KrajInfo info = manager.info(c.id());
        assertEquals(2, info.memberCount());
        assertFalse(info.npc());
        assertEquals("country:" + c.id(), info.treasuryAccount());
        assertEquals("world", info.capitalWorld());
        assertEquals(-3, info.capitalX());
        assertEquals(7, info.capitalZ());
        assertTrue(info.enemies().isEmpty());
    }

    @Test
    void version2RoundTrip() {
        final UUID ruler = UUID.randomUUID();
        final UUID citizen = UUID.randomUUID();
        final UUID enemy = UUID.randomUUID();
        final ChunkPos capital = new ChunkPos("world", 0, 0);
        final Country c = new Country(UUID.randomUUID(), "Borsk", 0xE53935, "", ruler, 5L, capital,
            new SpawnPoint("world", 8.5, 64, 8.5, 0f, 0f));
        c.membersMap().put(ruler, new Member(ruler, "Wladca", Rank.RULER, 1L));
        final Member debtor = new Member(citizen, "Dluznik", Rank.CITIZEN, 2L);
        debtor.taxDebt(45);
        debtor.debtDays(2);
        c.membersMap().put(citizen, debtor);
        c.mutableClaimsIn("world").add(capital.key());
        c.taxPerCitizen(15);
        c.unpaidUpkeepDays(1);
        c.relationsMap().put(enemy, Relation.WAR);
        c.warSinceMap().put(enemy, 1000L);
        c.warEndedAtMap().put(UUID.randomUUID(), 2000L);
        c.treasuryLog().addFirst(new TreasuryEntry(10L, "deposit", 500, "Wladca"));
        c.treasuryLog().addFirst(new TreasuryEntry(20L, "upkeep", -2, ""));

        final JsonObject root = JsonParser.parseString(CountryCodec.encodeAll(List.of(c), 123_456L).toString()).getAsJsonObject();
        assertEquals(CountryCodec.FORMAT_VERSION, root.get("version").getAsInt());
        assertEquals(123_456L, CountryCodec.decodeLastDailyTick(root));
        final Country d = CountryCodec.decodeAll(root).get(0);
        assertEquals(15L, d.taxPerCitizen());
        assertEquals(1, d.unpaidUpkeepDays());
        assertEquals(1000L, d.warSinceMap().get(enemy));
        assertEquals(1, d.warEndedAtMap().size());
        assertEquals(45L, d.member(citizen).taxDebt());
        assertEquals(2, d.member(citizen).debtDays());
        assertEquals(List.copyOf(c.treasuryLog()), List.copyOf(d.treasuryLog()));
    }

    @Test
    void npcCountryRoundTripAndLoad() {
        final UUID id = UUID.randomUUID();
        final ChunkPos capital = new ChunkPos("world", 10, 10);
        final Country c = new Country(id, "Norvik", 0x43A047, "", Country.npcRulerId(id), 1L, capital,
            new SpawnPoint("world", 168, 70, 168, 0f, 0f));
        c.npc(true);
        c.npcRulerName("Król Bolesław");
        c.mutableClaimsIn("world").add(capital.key());
        c.mutableClaimsIn("world").add(ChunkKeys.key(11, 10));

        final Country d = CountryCodec.decodeAll(JsonParser.parseString(CountryCodec.encodeAll(List.of(c)).toString())
            .getAsJsonObject()).get(0);
        assertTrue(d.npc());
        assertEquals("Król Bolesław", d.rulerName());
        assertEquals(0, d.memberCount());

        final CountryManager manager = new CountryManager(Logger.getAnonymousLogger());
        manager.loadAll(List.of(d), 0L);
        final KrajInfo info = manager.info(id);
        assertTrue(info.npc(), "NPC country without player members must load");
        assertEquals("Król Bolesław", info.rulerName());
        assertEquals(Country.npcRulerId(id), info.ruler());
        assertTrue(info.members().isEmpty());
        assertEquals(2, info.chunkCount());
    }
}
