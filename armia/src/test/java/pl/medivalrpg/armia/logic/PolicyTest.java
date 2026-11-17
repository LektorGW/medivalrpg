// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.api.NpcStance;

/** AI v2: stance, posture and lost land in war / alliance / peace / tribute decisions. */
class PolicyTest {

    private static final AiTuning T = AiTuning.defaults();
    private static final AiRules R = AiRules.defaults();

    private static Personality p(final int aggression, final int diplomacy) {
        return new Personality(aggression, 50, diplomacy, 50, 40, 20, true);
    }

    private static NeighborView n(final String name, final double strength, final int opinion, final NpcStance stance, final int lost) {
        return new NeighborView(UUID.nameUUIDFromBytes(name.getBytes()), name, strength, opinion, false, false, false, false, stance, lost, false);
    }

    @Nested
    class War {
        @Test
        void neverWarBlocksEvenTheWeakestNeighbour() {
            final Optional<NeighborView> t = Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.OFFENSIVE, 100,
                List.of(n("slaby", 1, -100, NpcStance.NEVER_WAR, 3)), 0, true, true, 0.0);
            assertTrue(t.isEmpty());
        }

        @Test
        void defensivePostureNeverStartsWars() {
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.DEFENSIVE, 100,
                List.of(n("slaby", 1, -100, NpcStance.PREFERRED_TARGET, 3)), 0, true, true, 0.0).isEmpty());
        }

        @Test
        void vassalProtectionBlocksWar() {
            final NeighborView overlord = new NeighborView(UUID.randomUUID(), "suweren", 1, -100, false, false, false, false, NpcStance.AUTO, 0, true);
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.BALANCED, 100, List.of(overlord), 0, true, true, 0.0).isEmpty());
        }

        @Test
        void preferredTargetWinsOverAWeakerNeighbour() {
            final List<NeighborView> list = List.of(n("slabszy", 4, 0, NpcStance.AUTO, 0), n("cel", 6, 0, NpcStance.PREFERRED_TARGET, 0));
            assertEquals("cel", Policy.chooseWarTarget(T, R, p(90, 50), NpcPosture.BALANCED, 20, list, 0, true, true, 0.0).orElseThrow().name());
        }

        @Test
        void preferredTargetIgnoresTheFriendshipBlockAndLowersMinimumAggression() {
            // agresja 45 < min 55, opinion 80 would block — a preferred target still qualifies
            final List<NeighborView> list = List.of(n("cel", 5, 80, NpcStance.PREFERRED_TARGET, 0));
            assertTrue(Policy.chooseWarTarget(T, R, p(45, 50), NpcPosture.BALANCED, 20, list, 0, true, true, 0.0).isPresent());
            assertTrue(Policy.chooseWarTarget(T, R, p(45, 50), NpcPosture.BALANCED, 20, List.of(n("zwykly", 5, 0, NpcStance.AUTO, 0)), 0, true, true, 0.0).isEmpty());
        }

        @Test
        void friendlyStanceMakesWarLessLikely() {
            // opinion 20 + friendly 30 = 50 ≥ block 40
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.BALANCED, 20, List.of(n("przyjaciel", 1, 20, NpcStance.FRIENDLY, 0)), 0, true, true, 0.0).isEmpty());
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.BALANCED, 20, List.of(n("obcy", 1, 20, NpcStance.AUTO, 0)), 0, true, true, 0.0).isPresent());
        }

        @Test
        void lostLandRaisesWeightOnlyAfterReclaimCooldown() {
            final List<NeighborView> list = List.of(n("slabszy", 4, 0, NpcStance.AUTO, 0), n("zlodziej", 6, 0, NpcStance.AUTO, 3));
            assertEquals("zlodziej", Policy.chooseWarTarget(T, R, p(90, 50), NpcPosture.BALANCED, 20, list, 0, true, true, 0.0).orElseThrow().name());
            assertEquals("slabszy", Policy.chooseWarTarget(T, R, p(90, 50), NpcPosture.BALANCED, 20, list, 0, true, false, 0.0).orElseThrow().name());
        }

        @Test
        void offensivePostureNeedsLessAdvantageAndRollsMoreOften() {
            final NeighborView x = n("x", 10, 0, NpcStance.AUTO, 0);
            assertTrue(Policy.requiredAdvantage(T, R, p(70, 50), NpcPosture.OFFENSIVE, x, false)
                < Policy.requiredAdvantage(T, R, p(70, 50), NpcPosture.BALANCED, x, false));
            // roll 0.3: balanced chance at agresja 70 ≈ 0.35 × 16/45 ≈ 0.12 → no; offensive ×1.5 still < 0.3; at agresja 100 balanced 0.35 → yes
            final List<NeighborView> weak = List.of(n("slaby", 1, 0, NpcStance.AUTO, 0));
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.BALANCED, 20, weak, 0, true, true, 0.3).isPresent());
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.BALANCED, 20, weak, 0, true, true, 0.4).isEmpty());
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.OFFENSIVE, 20, weak, 0, true, true, 0.4).isPresent());
        }

        @Test
        void cooldownAndWarLimitStillApply() {
            final List<NeighborView> weak = List.of(n("slaby", 1, 0, NpcStance.PREFERRED_TARGET, 0));
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.OFFENSIVE, 20, weak, 0, false, true, 0.0).isEmpty());
            assertTrue(Policy.chooseWarTarget(T, R, p(100, 50), NpcPosture.OFFENSIVE, 20, weak, 1, true, true, 0.0).isEmpty());
        }
    }

    @Nested
    class Diplomacy {
        private final AllianceView neutral = new AllianceView(10, 10, 0, false, false, 0);

        @Test
        void friendlyStanceMakesAllianceEasier() {
            assertFalse(Policy.acceptAlliance(T, R, p(50, 50), this.neutral, NpcStance.AUTO));
            assertTrue(Policy.acceptAlliance(T, R, p(50, 50), this.neutral, NpcStance.FRIENDLY));
        }

        @Test
        void hostileOrTargetNeverAllies() {
            final AllianceView great = new AllianceView(10, 10, 100, false, true, 0);
            assertFalse(Policy.acceptAlliance(T, R, p(0, 100), great, NpcStance.HOSTILE));
            assertFalse(Policy.acceptAlliance(T, R, p(0, 100), great, NpcStance.PREFERRED_TARGET));
        }

        @Test
        void friendlyNeighbourIsProposedFirstEvenWithLowDiplomacy() {
            final List<NeighborView> list = List.of(n("lubiany", 5, 40, NpcStance.AUTO, 0), n("przyjazny", 5, 0, NpcStance.FRIENDLY, 0));
            // dyplomacja 55 < 65 needed — but a FRIENDLY neighbour lowers it to 50
            assertEquals("przyjazny", Policy.chooseAllianceTarget(T, R, p(30, 55), list, 0.0).orElseThrow().name());
            assertTrue(Policy.chooseAllianceTarget(T, R, p(30, 55), List.of(n("lubiany", 5, 40, NpcStance.AUTO, 0)), 0.0).isEmpty());
        }

        @Test
        void neverWarOffersAndAcceptsPeaceSoon() {
            final WarView winning = new WarView(20, 5, 3, 0, 0);
            assertFalse(Policy.shouldOfferPeace(T, p(80, 20), winning, NpcStance.AUTO));
            assertTrue(Policy.shouldOfferPeace(T, p(80, 20), winning, NpcStance.NEVER_WAR));
            assertTrue(Policy.acceptPeace(T, p(80, 20), winning, NpcStance.NEVER_WAR));
            assertFalse(Policy.shouldOfferPeace(T, p(80, 20), new WarView(20, 5, 1, 0, 0), NpcStance.NEVER_WAR), "minimum war time");
        }

        @Test
        void preferredTargetGetsPeaceOnlyWhenLosing() {
            final WarView tired = new WarView(10, 10, 20, 10, 0);
            assertTrue(Policy.shouldOfferPeace(T, p(50, 50), tired, NpcStance.AUTO));
            assertFalse(Policy.shouldOfferPeace(T, p(50, 50), tired, NpcStance.PREFERRED_TARGET));
            assertTrue(Policy.shouldOfferPeace(T, p(50, 50), new WarView(5, 10, 20, 10, 0), NpcStance.PREFERRED_TARGET));
        }

        @Test
        void allyJoinsWhenItLikesUsAndIsStrong() {
            assertTrue(Policy.joinWar(R, p(60, 70), NpcPosture.BALANCED, 60, -20, NpcStance.AUTO, false, 20, 10));
            assertFalse(Policy.joinWar(R, p(60, 70), NpcPosture.BALANCED, 60, -20, NpcStance.NEVER_WAR, false, 20, 10));
            assertFalse(Policy.joinWar(R, p(60, 70), NpcPosture.BALANCED, 60, -20, NpcStance.AUTO, true, 20, 10), "vassal rules");
            assertFalse(Policy.joinWar(R, p(10, 20), NpcPosture.DEFENSIVE, 0, 0, NpcStance.AUTO, false, 5, 10));
        }
    }

    @Nested
    class Tribute {
        @Test
        void weakCountryPaysAStrongDemander() {
            assertTrue(Policy.acceptTributeDemand(R, p(50, 50), 30, 10, 0, NpcStance.AUTO, 100, 1000, 5000, 7));
        }

        @Test
        void equalCountryRefuses() {
            assertFalse(Policy.acceptTributeDemand(R, p(50, 50), 10, 10, 0, NpcStance.AUTO, 100, 1000, 5000, 7));
        }

        @Test
        void cannotPayRefuses() {
            assertFalse(Policy.acceptTributeDemand(R, p(0, 50), 100, 1, 0, NpcStance.AUTO, 10_000, 100, 0, 7));
        }

        @Test
        void hostileNeedsMuchMoreAdvantage() {
            assertFalse(Policy.acceptTributeDemand(R, p(50, 50), 20, 10, 0, NpcStance.HOSTILE, 100, 1000, 5000, 7));
            assertTrue(Policy.acceptTributeDemand(R, p(50, 50), 40, 10, 0, NpcStance.HOSTILE, 100, 1000, 5000, 7));
        }

        @Test
        void goodwillForAFriendWithSmallTribute() {
            assertTrue(Policy.acceptTributeDemand(R, p(20, 80), 5, 10, 70, NpcStance.AUTO, 100, 1000, 5000, 7));
            assertFalse(Policy.acceptTributeDemand(R, p(20, 80), 5, 10, 70, NpcStance.AUTO, 900, 1000, 50_000, 7));
        }
    }

    @Test
    void siegeOrderManualThenReclaimThenPreferredThenWeakest() {
        final UUID manual = UUID.randomUUID();
        final UUID reclaim = UUID.randomUUID();
        final UUID preferred = UUID.randomUUID();
        final UUID weak = UUID.randomUUID();
        final List<UUID> order = Policy.siegeOrder(manual, List.of(
            new Policy.EnemyView(weak, 5.0, 0, NpcStance.AUTO),
            new Policy.EnemyView(preferred, 1.0, 0, NpcStance.PREFERRED_TARGET),
            new Policy.EnemyView(reclaim, 0.5, 2, NpcStance.AUTO),
            new Policy.EnemyView(manual, 0.1, 0, NpcStance.AUTO)));
        assertEquals(List.of(manual, reclaim, preferred, weak), order);
        assertEquals(reclaim, Policy.siegeOrder(null, List.of(new Policy.EnemyView(weak, 5.0, 0, NpcStance.AUTO),
            new Policy.EnemyView(reclaim, 0.5, 1, NpcStance.AUTO))).get(0));
    }

    @Test
    void effectiveOpinionIsClamped() {
        assertEquals(100, Policy.effectiveOpinion(R, 90, NpcStance.FRIENDLY));
        assertEquals(-100, Policy.effectiveOpinion(R, -90, NpcStance.HOSTILE));
        assertEquals(-30, Policy.effectiveOpinion(R, 0, NpcStance.PREFERRED_TARGET));
        assertEquals(10, Policy.effectiveOpinion(R, 10, NpcStance.NEVER_WAR));
    }
}
