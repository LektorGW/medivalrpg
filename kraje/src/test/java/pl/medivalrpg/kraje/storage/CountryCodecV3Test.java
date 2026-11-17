// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.model.StoredInvite;
import pl.medivalrpg.kraje.model.Vassalage;

/** Format 3: vassalage and persistent invitations; files of format 2 (on the server now) still load. */
class CountryCodecV3Test {

    /** What Kraje wrote before this change (format 2, economy fields, no vassals, no invitations). */
    private static final String VERSION_2 = """
        {
          "version": 2,
          "economy": {"lastDailyTick": 1792960000000},
          "countries": [
            {
              "id": "2b1f0a57-7f5c-4d3e-9b8e-0c6f1d2a3b4c",
              "name": "Aldoria", "color": "#1E88E5", "description": "",
              "ruler": "11111111-1111-3111-8111-111111111111", "foundedAt": 1792960000000,
              "members": [
                {"uuid": "11111111-1111-3111-8111-111111111111", "name": "Maciej", "rank": "RULER", "joinedAt": 1792960000000,
                 "taxDebt": 5, "debtDays": 1}
              ],
              "claims": {"world": [[0, 0]]},
              "capital": {"world": "world", "x": 0, "z": 0},
              "spawn": {"world": "world", "x": 8.5, "y": 70.0, "z": 8.5, "yaw": 0.0, "pitch": 0.0},
              "relations": {},
              "npc": false, "taxPerCitizen": 10, "unpaidUpkeepDays": 0,
              "warSince": {}, "warEndedAt": {},
              "treasuryLog": [{"time": 1, "type": "deposit", "amount": 100, "actor": "Maciej"}]
            }
          ]
        }
        """;

    @Test
    void readsVersion2WithoutVassalsAndInvitations() {
        final JsonObject root = JsonParser.parseString(VERSION_2).getAsJsonObject();
        final List<Country> list = CountryCodec.decodeAll(root);
        assertEquals(1, list.size());
        final Country c = list.get(0);
        assertNull(c.vassalage());
        assertNull(c.overlord());
        assertEquals(10, c.taxPerCitizen());
        assertEquals(1, c.treasuryLog().size());
        assertTrue(CountryCodec.decodeInvites(root).isEmpty());
        assertEquals(1792960000000L, CountryCodec.decodeLastDailyTick(root));
        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(list, CountryCodec.decodeLastDailyTick(root), CountryCodec.decodeInvites(root));
        assertNull(m.vassalInfo(c.id()));
        assertTrue(m.storedInvites().isEmpty());
    }

    @Test
    void version3RoundTrip() {
        final Country overlord = country("Aldoria", 0);
        final Country vassal = country("Borsk", 20);
        final Vassalage v = new Vassalage(overlord.id(), 1000L, 1000L + 7 * 86_400_000L, 0.25, 20);
        v.debt(70);
        v.debtDays(2);
        v.tributePaid(300);
        vassal.vassalage(v);
        final UUID player = UUID.randomUUID();
        final StoredInvite invite = new StoredInvite(player, "Uciekinier", overlord.id(), 5L, Long.MAX_VALUE, StoredInvite.CONQUEST);

        final JsonObject root = JsonParser.parseString(
            CountryCodec.encodeAll(List.of(overlord, vassal), 77L, List.of(invite)).toString()).getAsJsonObject();
        assertEquals(3, root.get("version").getAsInt());
        final List<Country> decoded = CountryCodec.decodeAll(root);
        assertNull(decoded.get(0).vassalage());
        final Vassalage d = decoded.get(1).vassalage();
        assertEquals(overlord.id(), d.overlord());
        assertEquals(1000L, d.since());
        assertEquals(v.until(), d.until());
        assertEquals(0.25, d.tributeShare());
        assertEquals(20, d.tributeMin());
        assertEquals(70, d.debt());
        assertEquals(2, d.debtDays());
        assertEquals(300, d.tributePaid());
        assertEquals(List.of(invite), CountryCodec.decodeInvites(root));

        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(decoded, 77L, CountryCodec.decodeInvites(root));
        assertEquals(overlord.id(), m.overlordOf(vassal.id()));
        assertTrue(m.hasStoredInvite(player, overlord.id()));
    }

    @Test
    void brokenInvitationIsSkipped() {
        final JsonObject root = JsonParser.parseString("""
            {"version": 3, "countries": [], "invitations": [
              {"player": "not-a-uuid", "country": "2b1f0a57-7f5c-4d3e-9b8e-0c6f1d2a3b4c", "expiresAt": 5},
              {"player": "11111111-1111-3111-8111-111111111111", "country": "2b1f0a57-7f5c-4d3e-9b8e-0c6f1d2a3b4c", "expiresAt": 5}
            ]}
            """).getAsJsonObject();
        final List<StoredInvite> invites = CountryCodec.decodeInvites(root);
        assertEquals(1, invites.size());
        assertEquals("", invites.get(0).reason());
    }

    private static Country country(final String name, final int x) {
        final UUID ruler = UUID.randomUUID();
        final ChunkPos cap = new ChunkPos("world", x, 0);
        final Country c = new Country(UUID.randomUUID(), name, 0x123456, "", ruler, 1L, cap,
            new SpawnPoint("world", cap.centerBlockX(), 64, cap.centerBlockZ(), 0f, 0f));
        c.membersMap().put(ruler, new Member(ruler, "R" + name, Rank.RULER, 1L));
        c.mutableClaimsIn("world").add(cap.key());
        return c;
    }
}
