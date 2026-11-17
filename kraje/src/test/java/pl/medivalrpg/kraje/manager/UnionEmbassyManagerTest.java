// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.EmbassyInfo;
import pl.medivalrpg.kraje.api.UnionInfo;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Embassy;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.PersonalUnion;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.storage.CountryCodec;
import pl.medivalrpg.kraje.util.ChunkKeys;

/** Personal union (titular ruler, successor, snapshots) and embassies in the manager, plus their persistence. */
class UnionEmbassyManagerTest {

    private static final UUID KING = UUID.randomUUID();
    private static final UUID DUKE = UUID.randomUUID();
    private static final UUID PEASANT = UUID.randomUUID();

    private static SpawnPoint spawn(final ChunkPos p) {
        return new SpawnPoint(p.world(), p.centerBlockX(), 64, p.centerBlockZ(), 0f, 0f);
    }

    private record World(CountryManager m, Country home, Country partner) {
    }

    private static World world() {
        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(List.of(), 0L);
        m.setUnionMinDuration(72 * 3_600_000L);
        final ChunkPos capA = new ChunkPos("world", 0, 0);
        final ChunkPos capB = new ChunkPos("world", 20, 0);
        final Country home = m.create(KING, "Krol", "Aldoria", 0xFF0000, capA, spawn(capA), ClaimCause.PLAYER);
        final Country partner = m.create(DUKE, "Ksiaze", "Borsk", 0x00FF00, capB, spawn(capB), ClaimCause.PLAYER);
        m.addMember(partner, PEASANT, "Chlop", Rank.CITIZEN);
        m.claim(partner, "world", ChunkKeys.key(21, 0), ClaimCause.PLAYER);
        return new World(m, home, partner);
    }

    private static PersonalUnion union(final World w) {
        return new PersonalUnion(w.home().id(), KING, "Krol", 1000L, DUKE, "Ksiaze");
    }

    @Test
    void formingGivesTheMonarchBothThronesWithoutDoubleMembership() {
        final World w = world();
        w.m().formUnion(w.home(), w.partner(), union(w));
        assertEquals(KING, w.partner().ruler());
        assertEquals(Rank.RULER, w.partner().member(KING).rank());
        assertEquals(Rank.ADVISOR, w.partner().member(DUKE).rank(), "the former ruler becomes the regent (advisor)");
        assertTrue(w.partner().isTitular(KING));
        assertEquals(3, w.partner().memberCount());
        assertEquals(2, w.partner().realMemberCount(), "the titular monarch does not count");
        assertEquals(java.util.Set.of(DUKE, PEASANT), w.partner().realMembers().stream()
            .map(Member::uuid).collect(java.util.stream.Collectors.toSet()), "tax and treasury recipients exclude titular ruler");
        assertSame(w.home(), w.m().countryOf(KING), "the monarch still belongs to his home country only");
        assertEquals("Krol", w.m().info(w.partner().id()).rulerName());

        final UnionInfo info = w.m().unionInfo(w.home().id());
        assertNotNull(info);
        assertSame(info, w.m().unionInfo(w.partner().id()));
        assertEquals(w.partner().id(), w.m().unionPartnerId(w.home().id()));
        assertEquals(w.home().id(), w.m().unionPartnerId(w.partner().id()));
        assertEquals(1000L + 72 * 3_600_000L, info.breakableAt());
        assertEquals("Ksiaze", info.regentName());
        assertTrue(w.m().isUnionMonarch(KING, w.partner().id()));
        assertFalse(w.m().isUnionMonarch(DUKE, w.partner().id()));
        assertSame(w.partner(), w.m().unionPartnerOf(w.home()));
        assertSame(w.home(), w.m().unionPartnerOf(w.partner()));
        assertSame(w.partner(), w.m().unionPartnerSide(w.home()));
        assertEquals(1, w.m().unionInfos().size());

        // a second union for either country is refused
        assertThrows(IllegalStateException.class, () -> w.m().formUnion(w.home(), w.partner(), union(w)));
    }

    @Test
    void endingHandsTheThroneBackToTheRegent() {
        final World w = world();
        w.m().formUnion(w.home(), w.partner(), union(w));
        final Member next = w.m().endUnion(w.partner());
        assertEquals(DUKE, next.uuid());
        assertEquals(DUKE, w.partner().ruler());
        assertEquals(Rank.RULER, w.partner().member(DUKE).rank());
        assertNull(w.partner().member(KING));
        assertNull(w.partner().union());
        assertNull(w.m().unionInfo(w.home().id()));
        assertSame(w.home(), w.m().countryOf(KING));
        assertEquals(KING, w.home().ruler());
        assertEquals(2, w.partner().realMembers().size());
    }

    @Test
    void withoutTheRegentTheHighestRankedMemberRulesAndAnEmptyCountryFallsToNpc() {
        final World w = world();
        w.m().formUnion(w.home(), w.partner(), union(w));
        w.m().removeMember(w.partner(), DUKE);
        assertEquals(PEASANT, w.m().endUnion(w.partner()).uuid());
        assertEquals(PEASANT, w.partner().ruler());

        final World w2 = world();
        w2.m().formUnion(w2.home(), w2.partner(), union(w2));
        w2.m().removeMember(w2.partner(), DUKE);
        w2.m().removeMember(w2.partner(), PEASANT);
        assertNull(w2.m().endUnion(w2.partner()));
        assertTrue(w2.partner().npc(), "nobody left: the country is run by the AI");
        assertEquals(Country.npcRulerId(w2.partner().id()), w2.partner().ruler());
        assertEquals("Ksiaze", w2.partner().npcRulerName());
        assertEquals(0, w2.partner().memberCount());
    }

    @Test
    void transferOfPowerNotifiesTheRulerListener() {
        final World w = world();
        final List<Country> changed = new ArrayList<>();
        w.m().setRulerListener(changed::add);
        w.m().formUnion(w.home(), w.partner(), union(w));
        w.m().transferRule(w.partner(), DUKE);
        assertEquals(List.of(w.partner()), changed);
        // the service would now end the union: the titular monarch (now advisor) leaves, the duke keeps the throne
        assertNull(w.m().endUnion(w.partner()), "ruler unchanged");
        assertEquals(DUKE, w.partner().ruler());
        assertNull(w.partner().member(KING));
    }

    @Test
    void embassiesAreIndexedAndCleanedUp() {
        final World w = world();
        final ChunkPos chunk = new ChunkPos("world", 21, 0);
        w.m().addEmbassy(w.home(), new Embassy(w.partner().id(), chunk, spawn(chunk), 5L, 0L));
        final EmbassyInfo at = w.m().embassyAt("world", chunk.key());
        assertNotNull(at);
        assertEquals(w.home().id(), at.guest());
        assertEquals(w.partner().id(), at.host());
        assertFalse(at.expelled());
        assertEquals(List.of(w.home()), w.m().embassyGuestsIn(w.partner().id()));
        assertEquals(1, w.m().embassyInfos().size());

        w.m().setEmbassyClosesAt(w.home(), w.partner().id(), 999L);
        assertTrue(w.m().embassyAt("world", chunk.key()).expelled());
        assertThrows(IllegalStateException.class,
            () -> w.m().addEmbassy(w.home(), new Embassy(w.home().id(), chunk, spawn(chunk), 5L, 0L)));

        w.m().disband(w.partner(), ClaimCause.ADMIN);
        assertTrue(w.home().embassiesMap().isEmpty(), "the host disappeared");
        assertNull(w.m().embassyAt("world", chunk.key()));
    }

    @Test
    void disbandingTheHomeCountryEndsTheUnion() {
        final World w = world();
        w.m().formUnion(w.home(), w.partner(), union(w));
        w.m().disband(w.home(), ClaimCause.ADMIN);
        assertNull(w.partner().union());
        assertEquals(DUKE, w.partner().ruler());
        assertNull(w.m().unionInfo(w.partner().id()));
    }

    @Test
    void unionAndEmbassySurviveSaveAndLoad() {
        final World w = world();
        w.m().formUnion(w.home(), w.partner(), union(w));
        final ChunkPos chunk = new ChunkPos("world", 21, 0);
        final long deadline = System.currentTimeMillis() + 86_400_000L;
        w.m().addEmbassy(w.home(), new Embassy(w.partner().id(), chunk, spawn(chunk), 5L, deadline));

        final JsonObject root = JsonParser.parseString(
            CountryCodec.encodeAll(w.m().countriesSnapshot(), 0L, List.of()).toString()).getAsJsonObject();
        assertEquals(3, root.get("version").getAsInt(), "optional fields, same format version");
        // load in reverse order: the partner (holding the titular monarch) comes first
        final List<Country> decoded = new ArrayList<>(CountryCodec.decodeAll(root));
        java.util.Collections.reverse(decoded);
        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(decoded, 0L, List.of());

        final Country home = m.byName("Aldoria");
        final Country partner = m.byName("Borsk");
        assertSame(home, m.countryOf(KING), "titular membership is never indexed");
        assertSame(partner, m.countryOf(DUKE));
        assertEquals(KING, partner.ruler());
        assertEquals(KING, partner.union().monarch());
        assertEquals(DUKE, partner.union().regent());
        assertEquals(1000L, partner.union().since());
        assertEquals(partner.id(), m.unionPartnerId(home.id()));
        final Embassy e = home.embassyIn(partner.id());
        assertEquals(chunk, e.chunk());
        assertEquals(deadline, e.closesAt());
        assertEquals(5L, e.openedAt());
        assertNotNull(m.embassyAt("world", chunk.key()));
    }

    @Test
    void brokenUnionAndEmbassyDataIsRepairedOnLoad() {
        final World w = world();
        w.m().formUnion(w.home(), w.partner(), union(w));
        // embassy on a chunk the host does not own
        final ChunkPos wild = new ChunkPos("world", 50, 50);
        w.home().embassiesMap().put(w.partner().id(), new Embassy(w.partner().id(), wild, spawn(wild), 1L, 0L));
        final List<Country> decoded = CountryCodec.decodeAll(JsonParser.parseString(
            CountryCodec.encodeAll(w.m().countriesSnapshot(), 0L, List.of()).toString()).getAsJsonObject());
        // the monarch lost his home throne while the file was edited by hand
        final Country home = decoded.stream().filter(c -> c.name().equals("Aldoria")).findFirst().orElseThrow();
        final UUID heir = UUID.randomUUID();
        home.membersMap().put(heir, new Member(heir, "Nastepca", Rank.RULER, 2L));
        home.membersMap().get(KING).rank(Rank.ADVISOR);
        home.ruler(heir);

        final CountryManager m = new CountryManager(Logger.getAnonymousLogger());
        m.loadAll(decoded, 0L, List.of());
        final Country partner = m.byName("Borsk");
        assertNull(partner.union(), "invalid union dropped");
        assertEquals(DUKE, partner.ruler(), "the regent rules again");
        assertNull(partner.member(KING));
        assertTrue(m.byName("Aldoria").embassiesMap().isEmpty(), "embassy outside host land dropped");
        assertSame(m.byName("Aldoria"), m.countryOf(KING));
    }

    @Test
    void loadDropsUnionsConflictingWithWarOrVassalage() {
        for (final boolean war : List.of(true, false)) {
            final World w = world();
            w.m().formUnion(w.home(), w.partner(), union(w));
            if (war) {
                w.home().relationsMap().put(w.partner().id(), pl.medivalrpg.kraje.model.Relation.WAR);
            } else {
                w.partner().vassalage(new pl.medivalrpg.kraje.model.Vassalage(w.home().id(), 1L, Long.MAX_VALUE, 0.1, 0L));
            }
            final CountryManager loaded = new CountryManager(Logger.getAnonymousLogger());
            loaded.loadAll(CountryCodec.decodeAll(CountryCodec.encodeAll(w.m().countriesSnapshot(), 0L, List.of())), 0L);
            assertNull(loaded.unionInfo(w.home().id()));
            assertEquals(DUKE, loaded.get(w.partner().id()).ruler());
            assertSame(loaded.get(w.home().id()), loaded.countryOf(KING));
        }
    }

    @Test
    void embassyRejectsForeignLandAndDuplicateOccupantsAndRepairsExpiredData() {
        final World w = world();
        final ChunkPos p = new ChunkPos("world", 21, 0);
        final ChunkPos wild = new ChunkPos("world", 90, 0);
        assertThrows(IllegalStateException.class, () -> w.m().addEmbassy(w.home(),
            new Embassy(w.partner().id(), wild, spawn(wild), 1L, 0L)));
        assertThrows(IllegalStateException.class, () -> w.m().addEmbassy(w.home(),
            new Embassy(w.partner().id(), p, spawn(wild), 1L, 0L)));
        w.m().addEmbassy(w.home(), new Embassy(w.partner().id(), p, spawn(p), 1L, 0L));
        final ChunkPos cap = new ChunkPos("world", 40, 0);
        final Country third = w.m().create(UUID.randomUUID(), "Trzeci", "C", 1, cap, spawn(cap), ClaimCause.PLAYER);
        assertThrows(IllegalStateException.class, () -> w.m().addEmbassy(third,
            new Embassy(w.partner().id(), p, spawn(p), 1L, 0L)));
        w.m().setEmbassyClosesAt(w.home(), w.partner().id(), 2L);
        final CountryManager loaded = new CountryManager(Logger.getAnonymousLogger());
        loaded.loadAll(CountryCodec.decodeAll(CountryCodec.encodeAll(w.m().countriesSnapshot(), 0L, List.of())), 0L);
        assertNull(loaded.embassyAt(p.world(), p.key()));
    }

    @Test
    void deletingPartnerKeepsMonarchHomeMembershipAndRemovesSnapshots() {
        final World w = world();
        w.m().formUnion(w.home(), w.partner(), union(w));
        w.m().disband(w.partner(), ClaimCause.ADMIN);
        assertSame(w.home(), w.m().countryOf(KING));
        assertNull(w.m().countryOf(DUKE));
        assertNull(w.m().unionInfo(w.home().id()));
        assertTrue(w.m().embassyInfos().isEmpty());
    }

    @Test
    void embassyInWarIsDroppedOnLoadAndDeletingGuestReleasesItsChunk() {
        final World w = world();
        final ChunkPos p = new ChunkPos("world", 21, 0);
        w.m().addEmbassy(w.home(), new Embassy(w.partner().id(), p, spawn(p), 1L, 0L));
        w.partner().relationsMap().put(w.home().id(), pl.medivalrpg.kraje.model.Relation.WAR);
        final CountryManager loaded = new CountryManager(Logger.getAnonymousLogger());
        loaded.loadAll(CountryCodec.decodeAll(CountryCodec.encodeAll(w.m().countriesSnapshot(), 0L, List.of())), 0L);
        assertNull(loaded.embassyAt(p.world(), p.key()));
        w.m().disband(w.home(), ClaimCause.CONQUEST);
        assertNull(w.m().embassyAt(p.world(), p.key()));
        assertEquals(w.partner().id(), w.m().ownerAt(p.world(), p.key()));
    }
}
