// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.embassy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.api.RelationQuality;
import pl.medivalrpg.kraje.embassy.EmbassyRules.Verdict;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Relation;

/** Embassy rules: where/when one may be opened, NPC answers, war, relation quality, joint projects, trade bonus. */
class EmbassyRulesTest {

    private static final UUID GUEST = UUID.randomUUID();
    private static final UUID HOST = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final ChunkPos LAND = new ChunkPos("world", 5, 5);
    private static final ChunkPos CAPITAL = new ChunkPos("world", 0, 0);
    private static final ChunkPos WILD = new ChunkPos("world", 99, 99);

    private static EmbassyRules.Settings settings(final int maxPerCountry, final int maxHosted, final boolean capital,
                                                  final RelationQuality jointMin, final boolean ownEmbassy) {
        return new EmbassyRules.Settings(true, Set.of(Relation.NEUTRAL, Relation.ALLIANCE), maxPerCountry, maxHosted, capital,
            24 * 3_600_000L, Set.of(Relation.ALLIANCE), 0.05, 0.10, jointMin, ownEmbassy);
    }

    private static final EmbassyRules.Settings DEFAULTS = settings(3, 5, false, RelationQuality.NEUTRAL, false);

    private static final class World implements EmbassyRules.State {
        final Map<ChunkPos, UUID> owners = new HashMap<>(Map.of(LAND, HOST, CAPITAL, HOST));
        final Map<Set<UUID>, Relation> relations = new HashMap<>();
        final Set<String> embassies = new HashSet<>();
        final Set<ChunkPos> embassyChunks = new HashSet<>();
        final Set<ChunkPos> sieged = new HashSet<>();
        final Set<ChunkPos> protectedChunks = new HashSet<>();
        int guestCount;
        int hostCount;

        @Override
        public UUID ownerAt(final ChunkPos pos) {
            return this.owners.get(pos);
        }

        @Override
        public Relation relation(final UUID a, final UUID b) {
            return this.relations.getOrDefault(Set.of(a, b), Relation.NEUTRAL);
        }

        @Override
        public boolean hasEmbassy(final UUID guest, final UUID host) {
            return this.embassies.contains(guest + ">" + host);
        }

        @Override
        public int embassiesOf(final UUID guest) {
            return this.guestCount;
        }

        @Override
        public int embassiesIn(final UUID host) {
            return this.hostCount;
        }

        @Override
        public boolean embassyAt(final ChunkPos pos) {
            return this.embassyChunks.contains(pos);
        }

        @Override
        public boolean isCapital(final UUID country, final ChunkPos pos) {
            return HOST.equals(country) && CAPITAL.equals(pos);
        }

        @Override
        public boolean sieged(final ChunkPos pos) {
            return this.sieged.contains(pos);
        }

        @Override
        public boolean protectedArea(final ChunkPos pos) {
            return this.protectedChunks.contains(pos);
        }
    }

    @Test
    void openingMatrix() {
        final World w = new World();
        assertEquals(Verdict.ALLOWED, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));
        assertEquals(Verdict.NOT_HOST_LAND, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, WILD));
        assertEquals(Verdict.NOT_HOST_LAND, EmbassyRules.open(DEFAULTS, w, GUEST, OTHER, LAND), "standing on someone else's land");
        assertEquals(Verdict.SAME_COUNTRY, EmbassyRules.open(DEFAULTS, w, HOST, HOST, LAND));
        assertEquals(Verdict.CAPITAL, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, CAPITAL));
        assertEquals(Verdict.ALLOWED, EmbassyRules.open(settings(3, 5, true, RelationQuality.NEUTRAL, false), w, GUEST, HOST, CAPITAL));

        w.relations.put(Set.of(GUEST, HOST), Relation.WAR);
        assertEquals(Verdict.BAD_RELATION, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));
        w.relations.put(Set.of(GUEST, HOST), Relation.ALLIANCE);
        assertEquals(Verdict.ALLOWED, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));
        final EmbassyRules.Settings alliesOnly = new EmbassyRules.Settings(true, Set.of(Relation.ALLIANCE), 3, 5, false,
            0, Set.of(Relation.ALLIANCE), 0, 0, RelationQuality.NEUTRAL, false);
        w.relations.remove(Set.of(GUEST, HOST));
        assertEquals(Verdict.BAD_RELATION, EmbassyRules.open(alliesOnly, w, GUEST, HOST, LAND));

        w.embassies.add(GUEST + ">" + HOST);
        assertEquals(Verdict.ALREADY_OPEN, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));
        w.embassies.clear();
        w.guestCount = 3;
        assertEquals(Verdict.LIMIT_GUEST, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));
        assertEquals(Verdict.ALLOWED, EmbassyRules.open(settings(0, 5, false, RelationQuality.NEUTRAL, false), w, GUEST, HOST, LAND));
        w.guestCount = 0;
        w.hostCount = 5;
        assertEquals(Verdict.LIMIT_HOST, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));
        w.hostCount = 0;
        w.embassyChunks.add(LAND);
        assertEquals(Verdict.CHUNK_TAKEN, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));
        w.embassyChunks.clear();
        w.sieged.add(LAND);
        assertEquals(Verdict.SIEGED, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));
        w.sieged.clear();
        w.protectedChunks.add(LAND);
        assertEquals(Verdict.PROTECTED, EmbassyRules.open(DEFAULTS, w, GUEST, HOST, LAND));

        final EmbassyRules.Settings off = new EmbassyRules.Settings(false, Set.of(Relation.NEUTRAL), 3, 5, false, 0,
            Set.of(), 0, 0, RelationQuality.NEUTRAL, false);
        assertEquals(Verdict.DISABLED, EmbassyRules.open(off, new World(), GUEST, HOST, LAND));
    }

    @Test
    void warClosesNpcAnswersAndNotice() {
        assertTrue(EmbassyRules.closesOn(Relation.WAR));
        assertFalse(EmbassyRules.closesOn(Relation.NEUTRAL));
        assertFalse(EmbassyRules.closesOn(Relation.ALLIANCE));
        assertTrue(EmbassyRules.npcAccepts(DEFAULTS, Relation.ALLIANCE));
        assertFalse(EmbassyRules.npcAccepts(DEFAULTS, Relation.NEUTRAL));
        assertFalse(EmbassyRules.npcAccepts(DEFAULTS, Relation.WAR));
        assertEquals(1000L + 24 * 3_600_000L, EmbassyRules.expelDeadline(DEFAULTS, 1000L));
        final EmbassyRules.Settings instant = new EmbassyRules.Settings(true, Set.of(Relation.NEUTRAL), 3, 5, false, 0,
            Set.of(Relation.ALLIANCE), 0, 0, RelationQuality.NEUTRAL, false);
        assertEquals(1000L, EmbassyRules.expelDeadline(instant, 1000L));
    }

    @Test
    void relationQuality() {
        assertEquals(RelationQuality.SAME, EmbassyRules.quality(true, false, Relation.NEUTRAL, false));
        assertEquals(RelationQuality.UNION, EmbassyRules.quality(false, true, Relation.ALLIANCE, false));
        assertEquals(RelationQuality.WAR, EmbassyRules.quality(false, false, Relation.WAR, true));
        assertEquals(RelationQuality.ALLIANCE, EmbassyRules.quality(false, false, Relation.ALLIANCE, true));
        assertEquals(RelationQuality.VASSALAGE, EmbassyRules.quality(false, false, Relation.NEUTRAL, true));
        assertEquals(RelationQuality.NEUTRAL, EmbassyRules.quality(false, false, Relation.NEUTRAL, false));
        assertTrue(RelationQuality.ALLIANCE.atLeast(RelationQuality.NEUTRAL));
        assertFalse(RelationQuality.WAR.peaceful());
    }

    @Test
    void jointProjectsNeedGoodRelationsAndAnEmbassy() {
        assertTrue(EmbassyRules.jointProject(DEFAULTS, RelationQuality.UNION, false, false), "union partners always");
        assertTrue(EmbassyRules.jointProject(DEFAULTS, RelationQuality.SAME, false, false));
        assertFalse(EmbassyRules.jointProject(DEFAULTS, RelationQuality.NEUTRAL, false, false), "no embassy");
        assertTrue(EmbassyRules.jointProject(DEFAULTS, RelationQuality.NEUTRAL, true, false));
        assertTrue(EmbassyRules.jointProject(DEFAULTS, RelationQuality.NEUTRAL, false, true), "either direction by default");
        assertFalse(EmbassyRules.jointProject(DEFAULTS, RelationQuality.WAR, true, true));
        final EmbassyRules.Settings strict = settings(3, 5, false, RelationQuality.ALLIANCE, true);
        assertFalse(EmbassyRules.jointProject(strict, RelationQuality.NEUTRAL, true, true), "alliance required");
        assertFalse(EmbassyRules.jointProject(strict, RelationQuality.ALLIANCE, false, true), "own embassy required");
        assertTrue(EmbassyRules.jointProject(strict, RelationQuality.ALLIANCE, true, false));
    }

    @Test
    void tradeBonusHook() {
        assertEquals(0.05, EmbassyRules.tradeBonus(DEFAULTS, RelationQuality.NEUTRAL, true));
        assertEquals(0.0, EmbassyRules.tradeBonus(DEFAULTS, RelationQuality.NEUTRAL, false));
        assertEquals(0.10, EmbassyRules.tradeBonus(DEFAULTS, RelationQuality.UNION, false));
        assertEquals(0.10, EmbassyRules.tradeBonus(DEFAULTS, RelationQuality.UNION, true));
        assertEquals(0.0, EmbassyRules.tradeBonus(DEFAULTS, RelationQuality.WAR, true));
        assertEquals(0.0, EmbassyRules.tradeBonus(DEFAULTS, RelationQuality.SAME, true));
    }
}
