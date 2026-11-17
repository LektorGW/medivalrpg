// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.util.ChunkKeys;

class CountryCodecTest {

    @Test
    void roundTrip() {
        final UUID ruler = UUID.randomUUID();
        final UUID advisor = UUID.randomUUID();
        final UUID ally = UUID.randomUUID();
        final ChunkPos capital = new ChunkPos("world", -3, 7);
        final Country c = new Country(UUID.randomUUID(), "Aldoria", 0x1E88E5, "Królestwo <b>północy</b>", ruler,
            1_700_000_000_000L, capital, new SpawnPoint("world", -40.5, 70, 120.25, 90f, 10f));
        c.membersMap().put(ruler, new Member(ruler, "Maciej", Rank.RULER, 1L));
        c.membersMap().put(advisor, new Member(advisor, "Doradca1", Rank.ADVISOR, 2L));
        c.mutableClaimsIn("world").addAll(Set.of(capital.key(), ChunkKeys.key(-2, 7), ChunkKeys.key(-3, 8)));
        c.relationsMap().put(ally, Relation.ALLIANCE);

        final String json = CountryCodec.encodeAll(List.of(c)).toString();
        final List<Country> decoded = CountryCodec.decodeAll(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(1, decoded.size());
        final Country d = decoded.get(0);
        assertEquals(c.id(), d.id());
        assertEquals("Aldoria", d.name());
        assertEquals("#1E88E5", d.hexColor());
        assertEquals(c.description(), d.description());
        assertEquals(ruler, d.ruler());
        assertEquals(capital, d.capital());
        assertEquals(c.spawn(), d.spawn());
        assertEquals(c.claimsIn("world"), d.claimsIn("world"));
        assertEquals(Rank.ADVISOR, d.member(advisor).rank());
        assertEquals("Doradca1", d.member(advisor).name());
        assertEquals(Relation.ALLIANCE, d.relationTo(ally));
        assertEquals(1_700_000_000_000L, d.foundedAt());
    }

    @Test
    void emptyFileStructure() {
        final JsonObject root = new JsonObject();
        assertEquals(0, CountryCodec.decodeAll(root).size());
    }
}
