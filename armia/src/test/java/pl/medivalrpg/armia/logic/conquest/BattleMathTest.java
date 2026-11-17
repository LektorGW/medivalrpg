// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic.conquest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

class BattleMathTest {

    private static final ConquestSettings S = ConquestSettings.defaults();

    private static DoubleSupplier fixed(final double v) {
        return () -> v;
    }

    /** Runs a siege to its verdict; returns {rounds, attackerLosses, defenderLosses, captured?1:0}. */
    private static int[] siege(final double perSoldierA, final int a, final double perSoldierD, final int d, final double mA,
                               final double mD, final long seed) {
        final Random r = new Random(seed);
        double progress = 0;
        int rounds = 0;
        int lostA = 0;
        int lostD = 0;
        int na = a;
        int nd = d;
        while (true) {
            final double attack = perSoldierA * na * mA;
            final double defence = (perSoldierD * nd + S.militia()) * mD;
            final BattleMath.Round round = BattleMath.round(attack, defence, na, nd, progress, S, r::nextDouble);
            progress = round.progress();
            na -= round.attackerLosses();
            nd -= round.defenderLosses();
            lostA += round.attackerLosses();
            lostD += round.defenderLosses();
            rounds++;
            final BattleMath.Verdict v = BattleMath.verdict(progress, rounds, na, S);
            if (v != BattleMath.Verdict.CONTINUE) {
                return new int[] {rounds, lostA, lostD, v == BattleMath.Verdict.CAPTURED ? 1 : 0};
            }
        }
    }

    @Test
    void strongerArmyCapturesInAFewRoundsAndLosesLess() {
        int captured = 0;
        int lossA = 0;
        int lossD = 0;
        for (long seed = 0; seed < 200; seed++) {
            final int[] r = siege(1.0, 12, 1.0, 4, 1.0, 1.0, seed);
            captured += r[3];
            lossA += r[1];
            lossD += r[2];
            assertTrue(r[0] <= S.maxRounds());
        }
        assertTrue(captured >= 190, "captured " + captured);
        assertTrue(lossA < lossD * 2, "attacker " + lossA + " defender " + lossD);
    }

    @Test
    void weakAttackerIsRepulsedOrBroken() {
        int captured = 0;
        for (long seed = 0; seed < 200; seed++) {
            captured += siege(1.0, 5, 1.0, 12, 1.0, 1.5, seed)[3];
        }
        assertTrue(captured <= 4, "weak attacker captured " + captured + " of 200");
    }

    @Test
    void evenFightWithLuckIsUncertain() {
        int captured = 0;
        for (long seed = 0; seed < 400; seed++) {
            captured += siege(1.0, 8, 1.0, 7, 1.0, 1.0, seed)[3];
        }
        assertTrue(captured > 20 && captured < 380, "even fight " + captured);
    }

    @Test
    void roundIsDeterministicAndBounded() {
        final BattleMath.Round r = BattleMath.round(30, 10, 10, 10, 0.5, S, fixed(0.5));
        assertEquals(0.75, r.ratio(), 1e-9);
        assertEquals(0.5 + 0.5 * S.progressPerRound(), r.progress(), 1e-9);
        assertTrue(r.attackerLosses() <= 10 && r.defenderLosses() <= 10);
        assertTrue(r.defenderLosses() >= r.attackerLosses());
        final BattleMath.Round none = BattleMath.round(100, 0, 3, 0, 0.9, S, fixed(0.99));
        assertEquals(1.0, none.progress(), 1e-9);
        assertEquals(0, none.defenderLosses());
        final BattleMath.Round lost = BattleMath.round(0, 10, 3, 3, 0.1, S, fixed(0.0));
        assertEquals(0.0, lost.progress(), 1e-9);
    }

    @Test
    void stochasticRoundingKeepsTheExpectedValue() {
        final Random r = new Random(7);
        long sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += BattleMath.stochastic(0.3, r::nextDouble);
        }
        assertEquals(30_000, sum, 1_000);
        assertEquals(2, BattleMath.stochastic(2.0, fixed(0.99)));
        assertEquals(0, BattleMath.stochastic(-1, fixed(0)));
    }

    @Test
    void soldierStrengthUsesUnitEraAndMorale() {
        assertEquals(1.0, BattleMath.soldier(1.0, 1.0, 100), 1e-9);
        assertEquals(0.5, BattleMath.soldier(1.0, 1.0, 0), 1e-9);
        assertEquals(4.4, BattleMath.soldier(4.0, 1.1, 100), 1e-9); // cannon in the renaissance
        assertEquals(0.2, BattleMath.soldier(0, 1.0, 100), 1e-9);
    }

    @Test
    void fortificationsHelpDefendersAndEnginesBreakThem() {
        final double fort = BattleMath.fortification(4, 2, 1, S);
        assertEquals(Math.min(S.fortMax(), 4 * S.fortWall() + 2 * S.fortTower() + S.fortCastle()), fort, 1e-9);
        assertEquals(S.fortMax(), BattleMath.fortification(100, 100, 100, S), 1e-9);
        assertTrue(BattleMath.effectiveFort(fort, 2, S) < fort);
        assertEquals(0, BattleMath.effectiveFort(fort, 100, S), 1e-9);
        final double open = BattleMath.defender(20, 0, 0, S);
        final double walled = BattleMath.defender(20, fort, 0, S);
        assertTrue(walled > open);
    }

    @Test
    void capitalAndDistanceMatter() {
        assertEquals(1.0 + S.capitalBonus(), BattleMath.defender(0, 0, 0, S), 1e-9);
        assertEquals(1.0, BattleMath.defender(S.capitalBonusRange(), 0, 0, S), 1e-9);
        assertEquals(1.0, BattleMath.supply(0, S), 1e-9);
        assertEquals(1.0 - S.distancePenalty(), BattleMath.supply(10_000, S), 1e-9);
        final double near = BattleMath.attacker(2, false, false, 0, 50, S);
        final double expedition = BattleMath.attacker(2, true, false, 0, 50, S);
        final double withShip = BattleMath.attacker(2, true, true, 0, 50, S);
        assertTrue(expedition < near && withShip > expedition && withShip <= near);
    }

    @Test
    void exhaustionAndOverextensionWeakenTheArmy() {
        assertEquals(1.0, BattleMath.exhaustion(0, S), 1e-9);
        assertEquals(1.0 - S.exhaustion().strengthPenalty(), BattleMath.exhaustion(100, S), 1e-9);
        assertEquals(1.0, BattleMath.overextension(S.overextension().freeChunks(), S), 1e-9);
        assertEquals(S.overextension().strengthFloor(), BattleMath.overextension(100_000, S), 1e-9);
        assertTrue(BattleMath.overextension(150, S) < 1.0);
    }

    @Test
    void verdicts() {
        assertEquals(BattleMath.Verdict.CAPTURED, BattleMath.verdict(1.0, 3, 5, S));
        assertEquals(BattleMath.Verdict.BROKEN, BattleMath.verdict(0.5, 3, 1, S));
        assertEquals(BattleMath.Verdict.REPULSED, BattleMath.verdict(0.0, 3, 8, S));
        assertEquals(BattleMath.Verdict.CONTINUE, BattleMath.verdict(0.0, 2, 8, S));
        assertEquals(BattleMath.Verdict.STALLED, BattleMath.verdict(0.4, S.maxRounds(), 8, S));
    }
}
