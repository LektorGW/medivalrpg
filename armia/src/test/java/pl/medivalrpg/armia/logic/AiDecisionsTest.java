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

class AiDecisionsTest {

    private static final AiTuning T = AiTuning.defaults();

    private static Personality p(final int aggression, final int expansion, final int diplomacy, final int wealth) {
        return new Personality(aggression, expansion, diplomacy, wealth, 40, 20, true);
    }

    private static NeighborView n(final String name, final double strength, final int opinion, final boolean ally, final boolean war) {
        return new NeighborView(UUID.nameUUIDFromBytes(name.getBytes()), name, strength, opinion, ally, war, false, false);
    }

    @Nested
    class War {
        @Test
        void peacefulRulerNeverAttacks() {
            final Optional<NeighborView> t = AiDecisions.chooseWarTarget(T, p(30, 50, 50, 50), 100, List.of(n("slaby", 1, 0, false, false)), 0, true, 0.0);
            assertTrue(t.isEmpty());
        }

        @Test
        void aggressiveRulerAttacksWeakNeighbour() {
            final Optional<NeighborView> t = AiDecisions.chooseWarTarget(T, p(90, 50, 50, 50), 20, List.of(n("slaby", 5, 0, false, false)), 0, true, 0.0);
            assertEquals("slaby", t.orElseThrow().name());
        }

        @Test
        void needsStrengthAdvantage() {
            // 20 vs 18: ratio 1.11 < required 1.28 at agresja 90
            assertTrue(AiDecisions.chooseWarTarget(T, p(90, 50, 50, 50), 20, List.of(n("rowny", 18, 0, false, false)), 0, true, 0.0).isEmpty());
        }

        @Test
        void higherAggressionNeedsLessAdvantage() {
            assertTrue(AiDecisions.requiredAdvantage(T, p(100, 0, 0, 0)) < AiDecisions.requiredAdvantage(T, p(60, 0, 0, 0)));
            assertTrue(AiDecisions.requiredAdvantage(T, p(100, 0, 0, 0)) >= 1.0);
        }

        @Test
        void neverAttacksAllyOrCurrentEnemyOrFriend() {
            final List<NeighborView> list = List.of(n("sojusznik", 1, 0, true, false), n("wrog", 1, 0, false, true), n("przyjaciel", 1, 80, false, false));
            assertTrue(AiDecisions.chooseWarTarget(T, p(100, 50, 50, 50), 50, list, 0, true, 0.0).isEmpty());
        }

        @Test
        void respectsCooldownAndWarLimit() {
            final List<NeighborView> list = List.of(n("slaby", 1, 0, false, false));
            assertTrue(AiDecisions.chooseWarTarget(T, p(100, 50, 50, 50), 50, list, 0, false, 0.0).isEmpty());
            assertTrue(AiDecisions.chooseWarTarget(T, p(100, 50, 50, 50), 50, list, 1, true, 0.0).isEmpty());
        }

        @Test
        void rollDecides() {
            final List<NeighborView> list = List.of(n("slaby", 1, 0, false, false));
            assertTrue(AiDecisions.chooseWarTarget(T, p(100, 50, 50, 50), 50, list, 0, true, 0.99).isEmpty());
        }

        @Test
        void picksRelativelyWeakest() {
            final List<NeighborView> list = List.of(n("a", 10, 0, false, false), n("b", 2, 0, false, false));
            assertEquals("b", AiDecisions.chooseWarTarget(T, p(100, 50, 50, 50), 40, list, 0, true, 0.0).orElseThrow().name());
        }
    }

    @Nested
    class Peace {
        @Test
        void neverBeforeMinimumWarTime() {
            final WarView w = new WarView(1, 100, 1.0, 50, 0);
            assertFalse(AiDecisions.shouldOfferPeace(T, p(0, 0, 100, 0), w));
            assertFalse(AiDecisions.acceptPeace(T, p(0, 0, 100, 0), w));
        }

        @Test
        void losingCountryOffersPeace() {
            assertTrue(AiDecisions.shouldOfferPeace(T, p(50, 50, 50, 50), new WarView(5, 20, 3, 0, 0)));
        }

        @Test
        void winningAggressiveCountryKeepsFighting() {
            final WarView w = new WarView(40, 5, 3, 0, 0);
            assertFalse(AiDecisions.shouldOfferPeace(T, p(90, 50, 30, 50), w));
            assertFalse(AiDecisions.acceptPeace(T, p(90, 50, 30, 50), w));
        }

        @Test
        void exhaustionLeadsToPeace() {
            final WarView w = new WarView(20, 20, 20, 15, 0);
            assertTrue(AiDecisions.fatigue(T, p(50, 50, 50, 50), w) >= 0.75);
            assertTrue(AiDecisions.shouldOfferPeace(T, p(50, 50, 50, 50), w));
            assertTrue(AiDecisions.acceptPeace(T, p(50, 50, 50, 50), w));
        }

        @Test
        void weakerSideAcceptsPeace() {
            assertTrue(AiDecisions.acceptPeace(T, p(50, 50, 50, 50), new WarView(5, 10, 2.5, 0, 0)));
        }

        @Test
        void tributeHelpsPeace() {
            final Personality pers = p(50, 50, 40, 50);
            final WarView without = new WarView(10, 10, 2.5, 1, 0);
            final WarView with = new WarView(10, 10, 2.5, 1, 100);
            assertTrue(AiDecisions.peaceScore(T, pers, with) > AiDecisions.peaceScore(T, pers, without));
            assertFalse(AiDecisions.acceptPeace(T, pers, without));
            assertTrue(AiDecisions.acceptPeace(T, pers, with));
        }
    }

    @Nested
    class Alliance {
        @Test
        void neverWithEnemyOrLowDiplomacy() {
            assertFalse(AiDecisions.acceptAlliance(T, p(0, 0, 100, 0), new AllianceView(10, 10, 100, true, true, 0)));
            assertFalse(AiDecisions.acceptAlliance(T, p(0, 0, 20, 0), new AllianceView(10, 10, 100, false, true, 0)));
        }

        @Test
        void diplomatAcceptsFriend() {
            assertTrue(AiDecisions.acceptAlliance(T, p(20, 50, 80, 50), new AllianceView(10, 10, 30, false, false, 0)));
        }

        @Test
        void averageRulerRefusesStranger() {
            assertFalse(AiDecisions.acceptAlliance(T, p(50, 50, 50, 50), new AllianceView(10, 10, 0, false, false, 0)));
        }

        @Test
        void sharedEnemyConvinces() {
            final Personality pers = p(40, 50, 60, 50);
            assertFalse(AiDecisions.acceptAlliance(T, pers, new AllianceView(10, 10, 0, false, false, 0)));
            assertTrue(AiDecisions.acceptAlliance(T, pers, new AllianceView(10, 10, 0, false, true, 0)));
        }

        @Test
        void manyAlliancesMakeItHarder() {
            final Personality pers = p(20, 50, 80, 50);
            assertTrue(AiDecisions.allianceScore(pers, new AllianceView(10, 10, 30, false, false, 3))
                < AiDecisions.allianceScore(pers, new AllianceView(10, 10, 30, false, false, 0)));
        }

        @Test
        void proposesOnlyWhenDiplomatic() {
            final List<NeighborView> list = List.of(n("przyjaciel", 5, 30, false, false), n("obcy", 5, 0, false, false));
            assertTrue(AiDecisions.chooseAllianceTarget(T, p(50, 50, 50, 50), list, 0.0).isEmpty());
            assertEquals("przyjaciel", AiDecisions.chooseAllianceTarget(T, p(50, 50, 90, 50), list, 0.0).orElseThrow().name());
        }
    }

    @Nested
    class Expansion {
        @Test
        void expandsWhenRichAndEager() {
            assertTrue(AiDecisions.shouldExpand(T, p(50, 100, 50, 50), 5, 10_000, 100, 100, 0.1));
        }

        @Test
        void neverAboveCap() {
            final Personality pers = new Personality(50, 100, 50, 50, 10, 20, true);
            assertFalse(AiDecisions.shouldExpand(T, pers, 10, 1_000_000, 100, 0, 0.0));
        }

        @Test
        void neverIntoReserve() {
            // reserve = max(200, wages 500 × 2 days) = 1000; balance 1050 − cost 100 < 1000
            assertFalse(AiDecisions.shouldExpand(T, p(50, 100, 50, 50), 5, 1050, 100, 500, 0.0));
            assertTrue(AiDecisions.shouldExpand(T, p(50, 100, 50, 50), 5, 1200, 100, 500, 0.0));
        }

        @Test
        void zeroExpansionNeverExpands() {
            assertFalse(AiDecisions.shouldExpand(T, p(50, 0, 50, 50), 1, 1_000_000, 0, 0, 0.0));
        }

        @Test
        void chanceFallsNearCap() {
            final Personality pers = new Personality(50, 100, 50, 50, 10, 20, true);
            // roll 0.4: below the base chance 0.6 with 1 chunk, above 0.6 × (1 − 0.45) with 9 chunks
            assertTrue(AiDecisions.shouldExpand(T, pers, 1, 100_000, 100, 0, 0.4));
            assertFalse(AiDecisions.shouldExpand(T, pers, 9, 100_000, 100, 0, 0.4));
        }
    }

    @Nested
    class Economy {
        @Test
        void incomeScalesWithWealthAndLand() {
            final long poor = AiDecisions.income(T, p(0, 0, 0, 0), 10, 1.0);
            final long rich = AiDecisions.income(T, p(0, 0, 0, 100), 10, 1.0);
            assertEquals(275, poor); // (300 + 25×10) × 0.5
            assertEquals(825, rich);
            assertTrue(AiDecisions.income(T, p(0, 0, 0, 50), 20, 1.0) > AiDecisions.income(T, p(0, 0, 0, 50), 10, 1.0));
            assertEquals(0, AiDecisions.income(T, p(0, 0, 0, 50), 10, 0));
        }

        @Test
        void recruitsTowardsTargetWithinBudget() {
            final Personality pers = p(50, 50, 50, 50);
            assertEquals(3, AiDecisions.recruitBatch(T, pers, 10, 2, 100_000, 300, 0));
            assertEquals(1, AiDecisions.recruitBatch(T, pers, 10, 9, 100_000, 300, 0));
            assertEquals(0, AiDecisions.recruitBatch(T, pers, 10, 10, 100_000, 300, 0));
            assertEquals(1, AiDecisions.recruitBatch(T, pers, 10, 2, 500, 300, 0)); // 500 − reserve 200 = 300
            assertEquals(0, AiDecisions.recruitBatch(T, pers, 10, 2, 150, 300, 0));
        }

        @Test
        void desiredArmyCapped() {
            assertEquals(5, AiDecisions.desiredSoldiers(T, p(50, 50, 50, 50), 10, 100)); // 10 × 0.5 × 1.0
            assertEquals(8, AiDecisions.desiredSoldiers(T, p(100, 50, 50, 50), 10, 100)); // ceil(10 × 0.75)
            assertEquals(3, AiDecisions.desiredSoldiers(T, p(100, 50, 50, 50), 10, 3));
            assertEquals(2, AiDecisions.desiredSoldiers(T, new Personality(100, 50, 50, 50, 40, 2, true), 10, 100));
        }

        @Test
        void tributeRaisesOpinionClamped() {
            assertEquals(20, AiDecisions.opinionAfterTribute(0, 1000, 50));
            assertEquals(100, AiDecisions.opinionAfterTribute(90, 1_000_000, 50));
            assertEquals(-50, AiDecisions.opinionAfterTribute(-50, 10, 50));
        }
    }
}
