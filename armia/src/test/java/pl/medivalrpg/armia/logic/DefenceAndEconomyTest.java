// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.api.NpcBudget;
import pl.medivalrpg.armia.api.NpcGoal;
import pl.medivalrpg.armia.api.NpcLogCategory;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.api.NpcStance;

/** Capitulation, budget split, crisis/rebuilding, opinions, goals, ledger and log search. */
class DefenceAndEconomyTest {

    private static final AiTuning T = AiTuning.defaults();
    private static final AiRules R = AiRules.defaults();
    private static final long H = 3_600_000L;

    private static Personality p(final int aggression, final int diplomacy) {
        return new Personality(aggression, 50, diplomacy, 50, 40, 20, true);
    }

    @Nested
    class Capitulations {
        private Capitulation.OfferView offer(final double ours, final double theirs, final int opinion, final long tribute) {
            return new Capitulation.OfferView(ours, theirs, opinion, tribute, 20, 3, 0, NpcStance.AUTO);
        }

        @Test
        void diplomaticWinnerAccepts() {
            assertTrue(Capitulation.accept(T, R, p(30, 80), this.offer(20, 5, 0, 200)));
        }

        @Test
        void aggressiveWinnerWithBigAdvantageRefuses() {
            assertFalse(Capitulation.accept(T, R, p(95, 50), this.offer(40, 5, -40, 200)));
        }

        @Test
        void greedyRulerRefusesLowTributeButAcceptsAFairOne() {
            final Personality greedy = p(80, 20);
            assertTrue(Capitulation.greedy(greedy));
            final Capitulation.OfferView low = this.offer(10, 10, 60, 30);
            assertTrue(Capitulation.greedyRefuses(R, greedy, low), "30 < 15 × 10");
            assertFalse(Capitulation.accept(T, R, greedy, low));
            assertFalse(Capitulation.greedyRefuses(R, greedy, this.offer(10, 10, 60, 200)));
        }

        @Test
        void stanceShiftsTheAnswer() {
            final Capitulation.OfferView base = this.offer(20, 10, -20, 200);
            final double auto = Capitulation.acceptScore(T, p(60, 40), base);
            final double never = Capitulation.acceptScore(T, p(60, 40),
                new Capitulation.OfferView(20, 10, -20, 200, 20, 3, 0, NpcStance.NEVER_WAR));
            final double target = Capitulation.acceptScore(T, p(60, 40),
                new Capitulation.OfferView(20, 10, -20, 200, 20, 3, 0, NpcStance.PREFERRED_TARGET));
            assertTrue(never > auto && auto > target);
        }

        private Capitulation.LosingView losing(final double ours, final double theirs, final double hours, final int lost, final int chunks,
                                               final long start, final long now) {
            return new Capitulation.LosingView(ours, theirs, hours, lost, chunks, start, now, false, true);
        }

        @Test
        void losingCountryOffersCapitulation() {
            assertTrue(Capitulation.shouldCapitulate(T, R, p(50, 50), this.losing(3, 20, 3, 2, 20, 0, 0)), "lost 2 chunks");
            assertTrue(Capitulation.shouldCapitulate(T, R, p(50, 50), this.losing(3, 20, 3, 0, 20, 10_000, 5_000)), "treasury −50%");
            assertTrue(Capitulation.shouldCapitulate(T, R, p(50, 50), this.losing(3, 20, 3, 0, 2, 0, 0)), "near the fall");
        }

        @Test
        void noCapitulationWhenStrongEnoughTooEarlyOrAlreadyVassal() {
            assertFalse(Capitulation.shouldCapitulate(T, R, p(50, 50), this.losing(15, 20, 3, 5, 20, 0, 0)), "ratio 0.75");
            assertFalse(Capitulation.shouldCapitulate(T, R, p(50, 50), this.losing(3, 20, 1, 5, 20, 0, 0)), "min war time");
            assertFalse(Capitulation.shouldCapitulate(T, R, p(50, 50), new Capitulation.LosingView(3, 20, 3, 5, 20, 0, 0, true, true)));
            assertFalse(Capitulation.shouldCapitulate(T, R, p(50, 50), new Capitulation.LosingView(3, 20, 3, 5, 20, 0, 0, false, false)));
        }

        @Test
        void proudRulerOnlyBeforeTheFall() {
            assertFalse(Capitulation.shouldCapitulate(T, R, p(90, 50), this.losing(3, 20, 3, 5, 20, 0, 0)));
            assertTrue(Capitulation.shouldCapitulate(T, R, p(90, 50), this.losing(3, 20, 3, 5, 1, 0, 0)));
        }

        @Test
        void buyoutWhenAffordableWithReserve() {
            assertTrue(Capitulation.shouldBuyout(R, 1000, 2500, 1000));
            assertFalse(Capitulation.shouldBuyout(R, 1000, 2400, 1000));
            assertFalse(Capitulation.shouldBuyout(R, -1, 100_000, 0), "not a vassal");
        }

        @Test
        void releaseVassalAtVeryHighOpinion() {
            assertTrue(Capitulation.shouldRelease(R, p(50, 50), 85));
            assertFalse(Capitulation.shouldRelease(R, p(50, 50), 80));
            assertFalse(Capitulation.shouldRelease(R, p(100, 50), 90), "aggressive needs 95");
            assertTrue(Capitulation.shouldRelease(R, p(0, 50), 75));
        }
    }

    @Nested
    class Budget {
        @Test
        void derivedSplitSumsTo100AndMatchesThePanelExample() {
            final NpcBudget b = BudgetPlan.derived(p(50, 50), BuildBudget.Rules.defaults());
            assertEquals(100, b.army() + b.buildings() + b.reserve());
            assertEquals(45, b.army());
            assertEquals(35, b.buildings());
            assertEquals(20, b.reserve());
            assertFalse(b.custom());
        }

        @Test
        void validation() {
            assertNull(BudgetPlan.validate(45, 35, 20));
            assertNotNull(BudgetPlan.validate(50, 35, 20));
            assertNotNull(BudgetPlan.validate(-5, 85, 20));
        }

        @Test
        void splitDecidesArmyAndBuildingMoney() {
            final BudgetPlan.Shares s = BudgetPlan.effective(R, new NpcBudget(50, 30, 20, true), false, false, false);
            assertEquals(2000, BudgetPlan.reserveAmount(s, 10_000, 200));
            assertEquals(3000, BudgetPlan.buildingBudget(s, 10_000, 200, 100));
            assertEquals(5000, BudgetPlan.armyMoney(s, 10_000, 200));
            assertEquals(0, BudgetPlan.buildingBudget(s, 300, 200, 100), "below the minimum budget");
        }

        @Test
        void warAndCrisisMoveMoneyToTheArmyRebuildingBack() {
            final NpcBudget b = new NpcBudget(40, 40, 20, true);
            final BudgetPlan.Shares peace = BudgetPlan.effective(R, b, false, false, false);
            final BudgetPlan.Shares war = BudgetPlan.effective(R, b, true, false, false);
            final BudgetPlan.Shares crisis = BudgetPlan.effective(R, b, true, true, false);
            final BudgetPlan.Shares rebuild = BudgetPlan.effective(R, b, false, false, true);
            assertTrue(war.army() > peace.army() && crisis.army() > war.army());
            assertTrue(crisis.reserve() < peace.reserve());
            assertTrue(rebuild.buildings() > peace.buildings() && rebuild.army() < peace.army());
            for (final BudgetPlan.Shares s : List.of(peace, war, crisis, rebuild)) {
                assertEquals(1.0, s.army() + s.buildings() + s.reserve(), 1e-9);
            }
        }

        @Test
        void recruitBatchUsesArmyMoneyOnly() {
            assertEquals(3, BudgetPlan.recruitBatch(10, 2, 3, 10_000, 250));
            assertEquals(1, BudgetPlan.recruitBatch(10, 2, 3, 300, 250));
            assertEquals(0, BudgetPlan.recruitBatch(10, 10, 3, 10_000, 250));
            assertEquals(6, BudgetPlan.recruitPerTick(R, 3, true));
            assertEquals(3, BudgetPlan.recruitPerTick(R, 3, false));
            assertEquals(13, BudgetPlan.desiredInCrisis(R, 10, 40));
            assertEquals(11, BudgetPlan.desiredInCrisis(R, 10, 11));
        }

        @Test
        void postureChangesGuardsAndSieges() {
            assertTrue(BudgetPlan.guardFraction(R, 0.6, NpcPosture.DEFENSIVE, false, false) > BudgetPlan.guardFraction(R, 0.6, NpcPosture.BALANCED, false, false));
            assertTrue(BudgetPlan.guardFraction(R, 0.6, NpcPosture.OFFENSIVE, false, false) < BudgetPlan.guardFraction(R, 0.6, NpcPosture.BALANCED, false, false));
            assertTrue(BudgetPlan.guardFraction(R, 0.6, NpcPosture.BALANCED, false, true) < BudgetPlan.guardFraction(R, 0.6, NpcPosture.BALANCED, false, false),
                "at night more guards stay in the settlement");
            assertEquals(15, BudgetPlan.siegeCooldown(R, 30, NpcPosture.OFFENSIVE));
            assertEquals(45, BudgetPlan.siegeCooldown(R, 30, NpcPosture.DEFENSIVE));
        }
    }

    @Nested
    class CrisisMode {
        @Test
        void crisisOnlyInWarAfterRecentLoss() {
            final long now = 100 * H;
            assertTrue(Crisis.active(R, true, now, now - H, false, 0));
            assertFalse(Crisis.active(R, true, now, now - 3 * H, false, 0));
            assertTrue(Crisis.active(R, true, now, 0, true, 0), "besieged now");
            assertFalse(Crisis.active(R, false, now, now - H, true, now), "peace");
        }

        @Test
        void rebuildingAndReclaimWindows() {
            final long now = 100 * H;
            assertTrue(Crisis.rebuilding(R, false, now - H, now));
            assertFalse(Crisis.rebuilding(R, false, now - 7 * H, now));
            assertFalse(Crisis.rebuilding(R, true, now - H, now));
            assertFalse(Crisis.reclaimReady(R, false, now - H, now));
            assertTrue(Crisis.reclaimReady(R, false, now - 7 * H, now));
        }

        @Test
        void priorityBuildings() {
            assertEquals("mur", Crisis.priorityBuilding(true, false, true, 0, true, 0, 10, 10, true, true, 1));
            assertEquals("wieza", Crisis.priorityBuilding(true, false, true, 2, true, 1, 10, 10, true, true, 1));
            assertEquals("wieza", Crisis.priorityBuilding(true, false, false, 0, true, 0, 10, 10, true, true, 1));
            assertNull(Crisis.priorityBuilding(true, false, false, 0, false, 0, 10, 10, true, true, 1));
            assertEquals("dom", Crisis.priorityBuilding(false, true, false, 0, false, 0, 10, 11, true, true, 0));
            assertEquals("spichlerz", Crisis.priorityBuilding(false, true, false, 0, false, 0, 4, 12, true, true, 0));
            assertNull(Crisis.priorityBuilding(false, false, true, 0, true, 0, 10, 10, true, true, 0));
        }
    }

    @Test
    void grudgesFadeSlowerThanFriendship() {
        int friend = 10;
        int enemy = -10;
        for (long tick = 1; tick <= 8; tick++) {
            friend = Opinions.decay(friend, tick, 4);
            enemy = Opinions.decay(enemy, tick, 4);
        }
        assertEquals(2, friend);
        assertEquals(-8, enemy);
        assertEquals(100, Opinions.add(90, 50));
        assertEquals(-100, Opinions.add(-90, -50));
    }

    @Nested
    class Goals {
        private GoalPlanner.Inputs in(final boolean crisis, final GoalPlanner.Ref enemy, final GoalPlanner.Ref overlord, final boolean rebuilding,
                                      final int soldiers, final int desired) {
            return new GoalPlanner.Inputs(crisis, enemy, null, null, null, 0, overlord, -1, 0, null, rebuilding, false, soldiers, desired,
                10, 40, 50, false);
        }

        @Test
        void priorities() {
            final GoalPlanner.Ref polanie = new GoalPlanner.Ref(UUID.randomUUID(), "Polanie");
            assertEquals("WAR", GoalPlanner.goal(this.in(true, polanie, null, false, 5, 10)).kind());
            assertTrue(GoalPlanner.goal(this.in(true, polanie, null, false, 5, 10)).text().contains("kryzys"));
            assertEquals("Prowadzi wojnę z: Polanie", GoalPlanner.goal(this.in(false, polanie, null, false, 5, 10)).text());
            assertEquals("DIPLOMACY", GoalPlanner.goal(this.in(false, null, polanie, false, 5, 10)).kind());
            assertEquals("BUILDING", GoalPlanner.goal(this.in(false, null, null, true, 5, 10)).kind());
            final NpcGoal recruiting = GoalPlanner.goal(this.in(false, null, null, false, 5, 10));
            assertEquals("RECRUITING", recruiting.kind());
            assertEquals(0.5, recruiting.progress(), 1e-9);
            assertEquals("EXPANSION", GoalPlanner.goal(this.in(false, null, null, false, 10, 10)).kind());
        }

        @Test
        void vassalSavesForBuyout() {
            final GoalPlanner.Ref lord = new GoalPlanner.Ref(UUID.randomUUID(), "Suweren");
            final NpcGoal g = GoalPlanner.goal(new GoalPlanner.Inputs(false, null, null, null, null, 0, lord, 4000, 1000, null, false, false,
                10, 10, 10, 40, 50, false));
            assertEquals("SAVING", g.kind());
            assertEquals(0.25, g.progress(), 1e-9);
            assertEquals(lord.id(), g.target());
        }

        @Test
        void reclaimAtPeace() {
            final GoalPlanner.Ref thief = new GoalPlanner.Ref(UUID.randomUUID(), "Złodziej");
            final NpcGoal g = GoalPlanner.goal(new GoalPlanner.Inputs(false, null, null, null, thief, 3, null, -1, 0, null, false, false,
                10, 10, 10, 40, 50, false));
            assertEquals("WAR", g.kind());
            assertTrue(g.text().contains("odbicia"));
        }
    }

    @Nested
    class Ledgers {
        @Test
        void breakdownOfTheLast24Hours() {
            final long now = 100 * H;
            final List<Ledger.Entry> l = new ArrayList<>();
            Ledger.add(l, new Ledger.Entry(now - 30 * H, "aiIncome", 999, "", ""), now, 48 * H, 100);
            Ledger.add(l, new Ledger.Entry(now - 2 * H, "aiIncome", 100, "", ""), now, 48 * H, 100);
            Ledger.add(l, new Ledger.Entry(now - H, "aiIncome", 50, "", ""), now, 48 * H, 100);
            Ledger.add(l, new Ledger.Entry(now - H, "wages", -40, "", ""), now, 48 * H, 100);
            Ledger.add(l, new Ledger.Entry(now - H, "tribute", -10, "", ""), now, 48 * H, 100);
            Ledger.add(l, new Ledger.Entry(now - H, "tribute", 25, "", ""), now, 48 * H, 100);
            Ledger.add(l, new Ledger.Entry(now - H, "dziwne", -5, "", ""), now, 48 * H, 100);
            Ledger.add(l, new Ledger.Entry(now, "land", 0, "", ""), now, 48 * H, 100);
            final Ledger.Breakdown b = Ledger.breakdown(l, now, 24 * H);
            assertEquals(150L, b.income().get("aiIncome"));
            assertEquals(25L, b.income().get("tribute"));
            assertEquals(40L, b.expenses().get("wages"));
            assertEquals(10L, b.expenses().get("tribute"));
            assertEquals(5L, b.expenses().get("other"));
            assertEquals(175, b.incomeTotal());
            assertEquals(55, b.expensesTotal());
            assertEquals(6, b.newestFirst().size());
            assertTrue(b.newestFirst().get(0).time() >= b.newestFirst().get(5).time());
        }

        @Test
        void oldAndExcessEntriesAreDropped() {
            final long now = 100 * H;
            final List<Ledger.Entry> l = new ArrayList<>();
            Ledger.add(l, new Ledger.Entry(now - 50 * H, "wages", -1, "", ""), now, 48 * H, 3);
            assertTrue(l.isEmpty());
            for (int i = 0; i < 5; i++) {
                Ledger.add(l, new Ledger.Entry(now, "wages", -1, "", ""), now, 48 * H, 3);
            }
            assertEquals(3, l.size());
        }

        @Test
        void krajeHistoryKeys() {
            assertEquals("land", Ledger.krajeKey("claim"));
            assertEquals("land", Ledger.krajeKey("upkeep"));
            assertEquals("war", Ledger.krajeKey("war"));
            assertEquals("war", Ledger.krajeKey("siege"));
            assertEquals("tribute", Ledger.krajeKey("tribute"));
            assertEquals("tribute", Ledger.krajeKey("buyout"));
            assertEquals("other", Ledger.krajeKey("loot"));
        }
    }

    @Nested
    class Logs {
        @Test
        void searchIgnoresCaseAndPolishLetters() {
            assertTrue(LogText.matches("Oblężenie chunka 12,-4 (Królestwo Polan)", "KROLESTWO polan"));
            assertTrue(LogText.matches("Żółć", "ZOLC"));
            assertTrue(LogText.matches("cokolwiek", " "));
            assertFalse(LogText.matches("Pokój z Rusią", "wojna"));
        }

        @Test
        void categoriesOfOldEntries() {
            assertEquals(NpcLogCategory.WAR, LogText.classify("WOJNA z Polanie (siła 3 vs 1)"));
            assertEquals(NpcLogCategory.DIPLOMACY, LogText.classify("Propozycja pokoju dla Polanie"));
            assertEquals(NpcLogCategory.BUILD, LogText.classify("Rozbudowa osady: zamówiono"));
            assertEquals(NpcLogCategory.ARMY, LogText.classify("Rekrutacja: +3 (5/10)"));
            assertEquals(NpcLogCategory.ECONOMY, LogText.classify("Rozrost: zajęto chunk 1,2"));
            assertEquals(NpcLogCategory.OTHER, LogText.classify("Ręczny tick (admin)"));
        }

        @Test
        void decisionLogFiltersNewestFirst() {
            final DecisionLog log = new DecisionLog(3);
            log.add(1, NpcLogCategory.WAR, "Wojna z A");
            log.add(2, NpcLogCategory.BUILD, "Budowa domu");
            log.add(3, NpcLogCategory.WAR, "Oblężenie B");
            log.add(4, "Rekrutacja: +1");
            assertEquals(3, log.size());
            assertEquals(List.of(3L), log.find(NpcLogCategory.WAR, null, 10).stream().map(DecisionLog.Entry::time).toList());
            assertEquals(List.of(4L, 3L, 2L), log.find(null, "", 10).stream().map(DecisionLog.Entry::time).toList());
            assertEquals(1, log.find(null, "budowa", 10).size());
            assertEquals(NpcLogCategory.ARMY, log.find(null, "rekrut", 10).get(0).category());
        }
    }
}
