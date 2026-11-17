// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.logic.TargetSelector.Candidate;
import pl.medivalrpg.armia.logic.TargetSelector.Kind;
import pl.medivalrpg.armia.logic.TargetSelector.Stance;

class TargetSelectorTest {

    private static final UUID OWN = UUID.randomUUID();
    private static final UUID ALLY = UUID.randomUUID();
    private static final UUID NEUTRAL = UUID.randomUUID();
    private static final UUID ENEMY = UUID.randomUUID();
    private static final Map<UUID, Stance> STANCES = Map.of(OWN, Stance.OWN, ALLY, Stance.ALLY, NEUTRAL, Stance.NEUTRAL, ENEMY, Stance.ENEMY);
    private static final TargetSelector.Relations REL = (own, other) -> STANCES.getOrDefault(other, Stance.NEUTRAL);
    private static final TargetSelector.Rules RULES = TargetSelector.Rules.defaults();

    private static Candidate c(final String name, final Kind kind, final UUID country, final double d2, final boolean ownLand, final boolean attacked) {
        return new Candidate(name, kind, country, d2, ownLand, attacked, false);
    }

    @Test
    void neverOwnAllyOrNeutral() {
        for (final UUID country : List.of(OWN, ALLY, NEUTRAL)) {
            assertFalse(TargetSelector.isHostile(OWN, c("p", Kind.PLAYER, country, 1, true, false), REL, RULES));
            assertFalse(TargetSelector.isHostile(OWN, c("s", Kind.SOLDIER, country, 1, true, false), REL, RULES));
            // not even when they hit us or by order
            assertFalse(TargetSelector.isHostile(OWN, c("p", Kind.PLAYER, country, 1, true, true), REL, RULES));
            assertFalse(TargetSelector.isOrderable(OWN, c("p", Kind.PLAYER, country, 1, true, true), REL, RULES));
        }
    }

    @Test
    void enemiesAtWarAreTargets() {
        assertTrue(TargetSelector.isHostile(OWN, c("p", Kind.PLAYER, ENEMY, 1, false, false), REL, RULES));
        assertTrue(TargetSelector.isHostile(OWN, c("s", Kind.SOLDIER, ENEMY, 1, false, false), REL, RULES));
    }

    @Test
    void countrylessPlayersOnlyWhenConfigured() {
        final Candidate wanderer = c("p", Kind.PLAYER, null, 1, true, false);
        assertFalse(TargetSelector.isHostile(OWN, wanderer, REL, RULES));
        assertTrue(TargetSelector.isHostile(OWN, wanderer, REL, new TargetSelector.Rules(true, true, false)));
    }

    @Test
    void monstersOnOwnLandOrWhenAttacking() {
        assertTrue(TargetSelector.isHostile(OWN, c("zombie", Kind.MONSTER, null, 1, true, false), REL, RULES));
        assertFalse(TargetSelector.isHostile(OWN, c("zombie", Kind.MONSTER, null, 1, false, false), REL, RULES));
        assertTrue(TargetSelector.isHostile(OWN, c("zombie", Kind.MONSTER, null, 1, false, true), REL, RULES));
        assertTrue(TargetSelector.isOrderable(OWN, c("zombie", Kind.MONSTER, null, 1, false, false), REL, RULES));
    }

    @Test
    void exemptAndOtherNeverTargets() {
        assertFalse(TargetSelector.isHostile(OWN, new Candidate("creative", Kind.PLAYER, ENEMY, 1, false, true, true), REL, RULES));
        assertFalse(TargetSelector.isHostile(OWN, c("cow", Kind.OTHER, null, 1, true, true), REL, RULES));
    }

    @Test
    void prefersAttackerThenPeopleThenClosest() {
        final List<Candidate> list = List.of(
            c("zombie-blisko", Kind.MONSTER, null, 1, true, false),
            c("wrog-daleko", Kind.PLAYER, ENEMY, 100, false, false),
            c("sojusznik", Kind.PLAYER, ALLY, 0.5, true, false));
        assertEquals("wrog-daleko", TargetSelector.choose(OWN, list, REL, RULES, 400).orElseThrow().handle());

        final List<Candidate> attacked = List.of(
            c("wrog", Kind.SOLDIER, ENEMY, 4, false, false),
            c("zombie-atakuje", Kind.MONSTER, null, 50, false, true));
        assertEquals("zombie-atakuje", TargetSelector.choose(OWN, attacked, REL, RULES, 400).orElseThrow().handle());

        final List<Candidate> two = List.of(c("daleki", Kind.SOLDIER, ENEMY, 90, false, false), c("bliski", Kind.SOLDIER, ENEMY, 9, false, false));
        assertEquals("bliski", TargetSelector.choose(OWN, two, REL, RULES, 400).orElseThrow().handle());
    }

    @Test
    void respectsRange() {
        assertTrue(TargetSelector.choose(OWN, List.of(c("wrog", Kind.PLAYER, ENEMY, 500, false, false)), REL, RULES, 400).isEmpty());
    }
}
