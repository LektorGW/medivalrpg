// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;

/** Reactions, protest and festival thresholds, cooldowns. */
class RulesTest {

    private static final UUID HOME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void reactionsDependOnRelation() {
        assertEquals(ReactionRules.Standing.RULER, ReactionRules.classify(HOME, HOME, Rank.RULER, Relation.NEUTRAL, null));
        assertEquals(ReactionRules.Standing.COMPATRIOT, ReactionRules.classify(HOME, HOME, Rank.CITIZEN, Relation.NEUTRAL, null));
        assertEquals(ReactionRules.Standing.COMPATRIOT, ReactionRules.classify(HOME, HOME, Rank.ADVISOR, Relation.WAR, null),
            "own country is never an enemy");
        assertEquals(ReactionRules.Standing.STRANGER, ReactionRules.classify(HOME, null, null, Relation.NEUTRAL, null));
        assertEquals(ReactionRules.Standing.STRANGER, ReactionRules.classify(HOME, OTHER, Rank.RULER, Relation.NEUTRAL, null));
        assertEquals(ReactionRules.Standing.ALLY, ReactionRules.classify(HOME, OTHER, Rank.CITIZEN, Relation.ALLIANCE, null));
        assertEquals(ReactionRules.Standing.ENEMY, ReactionRules.classify(HOME, OTHER, Rank.CITIZEN, Relation.WAR, null));
        assertEquals(ReactionRules.Standing.OVERLORD, ReactionRules.classify(HOME, OTHER, Rank.CITIZEN, Relation.NEUTRAL, OTHER));
        assertEquals(ReactionRules.Action.FLEE, ReactionRules.Standing.ENEMY.action());
        assertEquals(ReactionRules.Action.SUSPICIOUS, ReactionRules.Standing.STRANGER.action());
        assertEquals(ReactionRules.Action.GREET, ReactionRules.Standing.RULER.action());
        assertEquals("wrog", ReactionRules.Standing.ENEMY.key());
    }

    @Test
    void protestNeedsLowMoodForMoreThanADay() {
        final UnrestRules r = UnrestRules.defaults();
        UnrestRules.State st = UnrestRules.State.CALM;
        st = r.update(st, 15, 1000);
        assertEquals(1000, st.lowSince());
        assertFalse(st.protest());
        st = r.update(st, 15, 1000 + DaySchedule.DAY_TICKS);
        assertFalse(st.protest(), "exactly one day is not more than a day");
        st = r.update(st, 12, 1001 + DaySchedule.DAY_TICKS);
        assertTrue(st.protest());
        assertTrue(r.unrest(st, 12));
        // hysteresis: 22 keeps the protest, 25 ends it
        st = r.update(st, 22, 2 * DaySchedule.DAY_TICKS);
        assertTrue(st.protest());
        st = r.update(st, 25, 2 * DaySchedule.DAY_TICKS + 10);
        assertEquals(UnrestRules.State.CALM, st);
        assertFalse(r.unrest(st, 25));
    }

    @Test
    void shortDipsDoNotStartProtests() {
        final UnrestRules r = UnrestRules.defaults();
        UnrestRules.State st = r.update(UnrestRules.State.CALM, 15, 0);
        st = r.update(st, 21, 10_000);
        assertEquals(UnrestRules.State.CALM, st, "back above the threshold: discontent forgotten");
        st = r.update(st, 15, 20_000);
        st = r.update(st, 15, 30_000);
        assertFalse(st.protest());
    }

    @Test
    void exodusBelowTen() {
        final UnrestRules r = UnrestRules.defaults();
        assertTrue(r.exodus(9));
        assertFalse(r.exodus(10));
        assertTrue(r.unrest(UnrestRules.State.CALM, 5), "very low mood is unrest even without a protest");
    }

    @Test
    void timeGoingBackwardsRestartsTheCount() {
        final UnrestRules r = UnrestRules.defaults();
        final UnrestRules.State st = r.update(new UnrestRules.State(100_000, false), 10, 50_000);
        assertEquals(50_000, st.lowSince());
    }

    @Test
    void festivalsLastADayAndRespectTheCooldown() {
        final FestivalRules f = FestivalRules.defaults();
        FestivalRules.State st = f.start(FestivalRules.State.NONE, 1000, false);
        assertTrue(st.active(1000));
        assertTrue(st.active(1000 + DaySchedule.DAY_TICKS - 1));
        assertFalse(st.active(1000 + DaySchedule.DAY_TICKS));
        // another automatic festival within the cooldown is refused
        final FestivalRules.State again = f.start(st, 1000 + DaySchedule.DAY_TICKS + 5, false);
        assertEquals(st, again);
        assertFalse(f.canStart(st, 1000 + DaySchedule.DAY_TICKS + 5, false));
        // the admin may always
        assertTrue(f.canStart(st, 1000 + DaySchedule.DAY_TICKS + 5, true));
        // after the cooldown
        final FestivalRules.State later = f.start(st, 1000 + 2L * DaySchedule.DAY_TICKS, false);
        assertTrue(later.active(1000 + 2L * DaySchedule.DAY_TICKS + 100));
        // a forced festival during a running one prolongs it
        final FestivalRules.State longer = f.start(st, 2000, true);
        assertEquals(2000 + DaySchedule.DAY_TICKS, longer.until());
    }

    @Test
    void greatBuildingDetection() {
        assertFalse(FestivalRules.greatBuildingFinished(-1, 2), "first check only remembers");
        assertFalse(FestivalRules.greatBuildingFinished(1, 1));
        assertFalse(FestivalRules.greatBuildingFinished(2, 1), "demolished");
        assertTrue(FestivalRules.greatBuildingFinished(1, 2));
    }

    @Test
    void cooldowns() {
        final Cooldowns<String> c = new Cooldowns<>();
        assertTrue(c.tryUse("a", 0, 100));
        assertFalse(c.tryUse("a", 50, 100));
        assertTrue(c.tryUse("a", 100, 100));
        assertTrue(c.ready("b", 0));
        c.prune(1000);
        assertEquals(0, c.size());
    }

    @Test
    void hashingIsStableAndSpread() {
        final UUID id = UUID.randomUUID();
        assertEquals(Hashing.unit(id, "plan", 3), Hashing.unit(id, "plan", 3));
        int below = 0;
        for (int i = 0; i < 2000; i++) {
            final double u = Hashing.unit(UUID.randomUUID(), "plan", i);
            assertTrue(u >= 0 && u < 1);
            if (u < 0.3) {
                below++;
            }
        }
        assertTrue(below > 450 && below < 750, "about 30 %: " + below);
        for (int i = 0; i < 100; i++) {
            final int o = Hashing.offset(UUID.randomUUID(), "dx", 3);
            assertTrue(o >= -3 && o <= 3);
        }
    }
}
