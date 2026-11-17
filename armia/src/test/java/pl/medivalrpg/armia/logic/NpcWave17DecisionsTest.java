// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.api.NpcStance;

/** Decisions of NPC countries for the wave 13–17 features: landings, fleet, bridgeheads, unions, embassies, railway, economy. */
class NpcWave17DecisionsTest {

    private static long k(final int x, final int z) {
        return ChunkPlanner.key(x, z);
    }

    private static final Personality CALM = new Personality(30, 50, 70, 50, 40, 20, true);
    private static final Personality WARLIKE = new Personality(80, 50, 30, 50, 40, 20, true);

    // ------------------------------------------------------------------ bridgeheads

    @Test
    void playerBridgeheadOnOurCoastCanBeRetakenLikeKrajeAllows() {
        // Player land: 3×1 mainland with the capital at (0,0) plus a one-chunk bridgehead far away at (20,0).
        final Set<Long> land = new HashSet<>(Set.of(k(0, 0), k(1, 0), k(2, 0), k(20, 0)));
        assertTrue(ChunkPlanner.wouldDisconnect(land, k(2, 0)), "old rule refused every mainland chunk of a country with a bridgehead");
        assertTrue(ChunkPlanner.siegeKeepsConnectivity(land, k(20, 0), k(0, 0)), "the bridgehead itself may be retaken");
        assertTrue(ChunkPlanner.siegeKeepsConnectivity(land, k(2, 0), k(0, 0)), "edge of the mainland may be sieged");
        assertFalse(ChunkPlanner.siegeKeepsConnectivity(land, k(1, 0), k(0, 0)), "cutting the mainland in two is refused");
        assertEquals(2, ChunkPlanner.components(land));
    }

    @Test
    void connectedTerritoryKeepsTheCapitalRule() {
        final Set<Long> line = Set.of(k(0, 0), k(1, 0), k(2, 0));
        assertFalse(ChunkPlanner.siegeKeepsConnectivity(line, k(1, 0), k(0, 0)));
        assertTrue(ChunkPlanner.siegeKeepsConnectivity(line, k(2, 0), k(0, 0)));
        assertFalse(ChunkPlanner.siegeKeepsConnectivity(line, k(0, 0), k(0, 0)), "removing the anchor (capital) is never allowed");
        assertTrue(ChunkPlanner.siegeKeepsConnectivity(line, k(9, 9), k(0, 0)), "not ours: nothing to split");
    }

    // ------------------------------------------------------------------ limits

    @Test
    void adminAndScenarioAdditionsNeverPassTheGlobalCapOf1000() {
        assertEquals(0, ArmyLimits.adminAllowed(50, 1000, 1000));
        assertEquals(10, ArmyLimits.adminAllowed(50, 990, 1000));
        assertEquals(50, ArmyLimits.adminAllowed(50, 10, 1000));
        assertEquals(0, ArmyLimits.adminAllowed(5, 1200, 1000), "already above (old data): nothing more");
    }

    // ------------------------------------------------------------------ unions, embassies, railway

    @Test
    void unionAndEmbassyChangeTheOpinionUsedForWarAndAlliances() {
        assertEquals(10, RelationBonds.effectiveOpinion(0, false, true));
        assertEquals(40, RelationBonds.effectiveOpinion(0, true, true), "union counts once, not union + embassy");
        assertEquals(100, RelationBonds.effectiveOpinion(90, true, false));
        assertEquals(-100, RelationBonds.effectiveOpinion(-100, false, false));
    }

    @Test
    void npcAcceptsAUnionOnlyFromAFriendlyStrongerAlly() {
        assertTrue(RelationBonds.acceptUnion(CALM, 60, false, true, 1.5, NpcStance.AUTO));
        assertFalse(RelationBonds.acceptUnion(CALM, 0, false, false, 1.0, NpcStance.AUTO), "stranger");
        assertFalse(RelationBonds.acceptUnion(CALM, 100, false, false, 3.0, NpcStance.FRIENDLY), "no alliance yet: never a free union");
        assertTrue(RelationBonds.requestCooldownOver(null, 1000));
        assertFalse(RelationBonds.requestCooldownOver(0L, 30 * 60_000L), "same proposer within an hour");
        assertTrue(RelationBonds.requestCooldownOver(0L, 60 * 60_000L));
        assertTrue(RelationBonds.leaveUnion(-40, CALM), "deep grudge: leave");
        assertFalse(RelationBonds.leaveUnion(-20, CALM), "diplomatic partner tolerates small grudges");
        assertFalse(RelationBonds.acceptUnion(WARLIKE, 60, false, true, 1.5, NpcStance.AUTO), "proud, undiplomatic ruler");
        assertFalse(RelationBonds.acceptUnion(CALM, 100, true, true, 3.0, NpcStance.AUTO), "never during a war");
        assertFalse(RelationBonds.acceptUnion(CALM, 100, false, true, 3.0, NpcStance.HOSTILE), "hostile stance");
    }

    @Test
    void embassyAnswersFollowStanceAndOpinion() {
        assertTrue(RelationBonds.acceptEmbassy(CALM, 0, false, false, NpcStance.AUTO), "diplomatic host, neutral opinion");
        assertFalse(RelationBonds.acceptEmbassy(WARLIKE, 0, false, false, NpcStance.AUTO), "undiplomatic host needs good opinion");
        assertTrue(RelationBonds.acceptEmbassy(WARLIKE, 0, false, true, NpcStance.AUTO), "allies always");
        assertFalse(RelationBonds.acceptEmbassy(CALM, 100, true, true, NpcStance.FRIENDLY), "never in war");
        assertFalse(RelationBonds.acceptEmbassy(CALM, 100, false, false, NpcStance.PREFERRED_TARGET));
    }

    @Test
    void railConsentIsAnsweredAtOnceFreeForFriendsPaidForNeutralsNeverInWar() {
        final RelationBonds.RailDecision ally = RelationBonds.railConsent(CALM, 0, false, true, false, false, NpcStance.AUTO, 500);
        assertEquals(RelationBonds.RailAnswer.ACCEPT, ally.answer());
        final RelationBonds.RailDecision neutral = RelationBonds.railConsent(CALM, 0, false, false, false, false, NpcStance.AUTO, 500);
        assertEquals(RelationBonds.RailAnswer.ACCEPT_FEE, neutral.answer());
        assertEquals(1000, neutral.fee(), "500 × distrust 2.0 × greed 1.0");
        final Personality greedy = new Personality(30, 50, 70, 100, 40, 20, true);
        assertTrue(RelationBonds.railConsent(greedy, 0, false, false, false, false, NpcStance.AUTO, 500).fee() > neutral.fee());
        assertEquals(RelationBonds.RailAnswer.ACCEPT,
            RelationBonds.railConsent(CALM, 25, false, false, false, true, NpcStance.AUTO, 500).answer(), "embassy +10 → good relations");
        assertEquals(RelationBonds.RailAnswer.REFUSE, RelationBonds.railConsent(CALM, 90, true, false, false, false, NpcStance.AUTO, 500).answer());
        assertEquals(RelationBonds.RailAnswer.REFUSE, RelationBonds.railConsent(CALM, -80, false, false, false, false, NpcStance.AUTO, 500).answer());
        assertEquals(RelationBonds.RailAnswer.ACCEPT, RelationBonds.railConsent(CALM, 0, false, false, false, false, NpcStance.AUTO, 0).answer(),
            "no fee configured → free");
    }

    // ------------------------------------------------------------------ closed money pool

    @Test
    void npcIncomeNeverExceedsTheOperatingBudgetSurplus() {
        assertTrue(ClosedEconomy.closedPool("auto", 1, 0, 0));
        assertFalse(ClosedEconomy.closedPool("auto", 0, 0, 0), "open economy (old bank)");
        assertTrue(ClosedEconomy.closedPool("true", 0, 0, 0));
        assertFalse(ClosedEconomy.closedPool("false", 10, 10, 10));
        assertEquals(300, ClosedEconomy.income(300, false, 0, 100_000, 0.002), "open pool: unchanged formula");
        assertEquals(0, ClosedEconomy.income(300, true, 90_000, 100_000, 0.002), "below the floor: nothing");
        assertEquals(200, ClosedEconomy.income(300, true, 200_000, 100_000, 0.002), "0.2% of 100 000 surplus");
        assertEquals(300, ClosedEconomy.income(300, true, 10_000_000, 100_000, 0.002), "never more than the formula");
    }

    @Test
    void armyIsSizedToWhatCanBePaid() {
        assertEquals(Integer.MAX_VALUE, ClosedEconomy.sustainableArmy(0, 0, 0, 14));
        assertEquals(10, ClosedEconomy.sustainableArmy(100, 1400, 20, 14), "(100 + 1400/14) / 20");
        assertEquals(0, ClosedEconomy.sustainableArmy(0, 0, 20, 14));
        assertEquals(12, ClosedEconomy.cappedDesired(30, 12, 5), "never forces disbanding");
        assertEquals(8, ClosedEconomy.cappedDesired(30, 2, 8));
        assertEquals(6, ClosedEconomy.cappedDesired(6, 2, 50));
    }

    // ------------------------------------------------------------------ naval landings

    private static NavalPlanner.Input landing(final boolean landFront, final boolean reclaim, final NpcPosture posture, final boolean crisis,
                                              final int aggression, final double ratio, final double distance, final int idle,
                                              final boolean shipFree, final long spendable) {
        return new NavalPlanner.Input(true, false, 0, 1000, landFront, reclaim, posture, crisis, aggression, ratio, true, true, distance,
            idle, shipFree, true, 3000, 1500, spendable);
    }

    @Test
    void coastalNpcAtWarWithAnOverseasEnemyDecidesToLand() {
        final NavalPlanner.Settings s = NavalPlanner.Settings.defaults();
        assertEquals(NavalPlanner.Reason.OK, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, false, 60, 1.2, 120, 8, false, 6000)));
        assertEquals(NavalPlanner.Reason.LAND_FRONT, NavalPlanner.decide(s, landing(true, false, NpcPosture.BALANCED, false, 60, 1.2, 120, 8, false, 6000)),
            "reachable by land → ordinary siege");
        assertEquals(NavalPlanner.Reason.NO_MONEY, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, false, 60, 1.2, 120, 8, false, 4000)),
            "ship 3000 + landing 1500 > 4000");
        assertEquals(NavalPlanner.Reason.OK, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, false, 60, 1.2, 120, 8, true, 2000)),
            "idle ship in harbour: only the landing must be paid");
        assertEquals(NavalPlanner.Reason.TOO_FAR, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, false, 60, 1.2, 300, 8, false, 9000)));
        assertEquals(NavalPlanner.Reason.TOO_CLOSE, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, false, 60, 1.2, 10, 8, false, 9000)),
            "Kraje's proof needs 24 sailed blocks");
        assertEquals(NavalPlanner.Reason.FEW_SOLDIERS, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, false, 60, 1.2, 120, 3, false, 9000)));
        assertEquals(NavalPlanner.Reason.WEAK, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, false, 60, 0.5, 120, 8, false, 9000)));
        assertEquals(NavalPlanner.Reason.PASSIVE, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, false, 20, 1.2, 120, 8, false, 9000)));
    }

    @Test
    void defensiveOrBesiegedCountriesOnlySailToRetakeTheirOwnLand() {
        final NavalPlanner.Settings s = NavalPlanner.Settings.defaults();
        assertEquals(NavalPlanner.Reason.DEFENSIVE, NavalPlanner.decide(s, landing(false, false, NpcPosture.DEFENSIVE, false, 60, 1.2, 120, 8, false, 9000)));
        assertEquals(NavalPlanner.Reason.CRISIS, NavalPlanner.decide(s, landing(false, false, NpcPosture.BALANCED, true, 60, 1.2, 120, 8, false, 9000)));
        assertEquals(NavalPlanner.Reason.OK, NavalPlanner.decide(s, landing(false, true, NpcPosture.DEFENSIVE, true, 10, 0.3, 120, 8, false, 9000)),
            "reclaiming a lost bridgehead ignores posture, crisis, aggression and advantage");
    }

    @Test
    void cooldownsAndTheGlobalCapPreventLandingSpam() {
        final NavalPlanner.Settings s = NavalPlanner.Settings.defaults();
        final NavalPlanner.Input cooling = new NavalPlanner.Input(true, false, 0, 30, false, false, NpcPosture.OFFENSIVE, false, 90, 2, true, true,
            120, 10, true, true, 3000, 1500, 99_999);
        assertEquals(NavalPlanner.Reason.COOLDOWN, NavalPlanner.decide(s, cooling));
        final NavalPlanner.Input full = new NavalPlanner.Input(true, false, 3, 1000, false, false, NpcPosture.OFFENSIVE, false, 90, 2, true, true,
            120, 10, true, true, 3000, 1500, 99_999);
        assertEquals(NavalPlanner.Reason.GLOBAL_LIMIT, NavalPlanner.decide(s, full));
        final NavalPlanner.Input busy = new NavalPlanner.Input(true, true, 0, 1000, false, false, NpcPosture.OFFENSIVE, false, 90, 2, true, true,
            120, 10, true, true, 3000, 1500, 99_999);
        assertEquals(NavalPlanner.Reason.BUSY, NavalPlanner.decide(s, busy));
        final NavalPlanner.Input peace = new NavalPlanner.Input(false, false, 0, 1000, false, false, NpcPosture.OFFENSIVE, false, 90, 2, true, true,
            120, 10, true, true, 3000, 1500, 99_999);
        assertEquals(NavalPlanner.Reason.NOT_AT_WAR, NavalPlanner.decide(s, peace), "never starts a war by itself");
    }

    @Test
    void fleetFollowsTheEraLimit() {
        assertEquals("transportowiec", NavalPlanner.shipType(1, 1_000_000, 6000, 1500), "medieval: transports only");
        assertEquals("karawela", NavalPlanner.shipType(2, 20_000, 6000, 1500));
        assertEquals("transportowiec", NavalPlanner.shipType(2, 10_000, 6000, 1500), "renaissance but poor");
        assertEquals(12, NavalPlanner.squad(NavalPlanner.Settings.defaults(), 40, 16));
        assertEquals(5, NavalPlanner.squad(NavalPlanner.Settings.defaults(), 5, 16));
        assertEquals(3, NavalPlanner.squad(NavalPlanner.Settings.defaults(), 9, 3));
    }

    @Test
    void autopilotSteersLikeTheHelm() {
        assertEquals(0f, NavalPlanner.headingTo(0, 0, 0, 10), 0.001f, "south = yaw 0");
        assertEquals(90f, NavalPlanner.headingTo(0, 0, -10, 0), 0.001f, "west = yaw 90");
        assertEquals(-90f, NavalPlanner.headingTo(0, 0, 10, 0), 0.001f, "east = yaw -90");
        assertEquals(20f, NavalPlanner.turn(170f, -170f), 0.001f, "shortest turn across ±180");
        boolean[] keys = NavalPlanner.helm(0f, 0, 0, -10, 10, 0f); // target south-west: yaw 45 → turn right
        assertTrue(keys[1]);
        assertFalse(keys[0]);
        assertTrue(keys[2], "45° off: keep sailing while turning");
        keys = NavalPlanner.helm(0f, 0, 0, 0, -10, 0f); // straight behind
        assertFalse(keys[2], "turn in place first");
        keys = NavalPlanner.helm(0f, 0, 0, 0, 10, 0f);
        assertFalse(keys[0] || keys[1]);
        assertTrue(NavalPlanner.arrived(0, 0, 3, 4, 6));
        assertFalse(NavalPlanner.arrived(0, 0, 30, 40, 6));
        assertEquals(0f, NavalPlanner.detour(0));
        assertEquals(50f, NavalPlanner.detour(1));
        assertEquals(-90f, NavalPlanner.detour(4));
        assertEquals(50f, NavalPlanner.detour(5));
    }
}
