// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bracket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BracketTest {

    private static List<String> players(final int n) {
        final List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add("p" + i);
        }
        return out;
    }

    /** Plays the bracket to the end: the alphabetically smaller name always wins. */
    private static void playOut(final Bracket<String> b) {
        int guard = 0;
        while (!b.isFinished()) {
            final Bracket.Match<String> m = b.nextPlayable().orElseThrow();
            b.recordWinner(m, m.a().compareTo(m.b()) < 0 ? m.a() : m.b());
            if (++guard > 1000) {
                throw new AssertionError("no progress");
            }
        }
    }

    @Test
    void bracketSizeIsNextPowerOfTwo() {
        assertEquals(2, Bracket.bracketSize(1));
        assertEquals(2, Bracket.bracketSize(2));
        assertEquals(4, Bracket.bracketSize(3));
        assertEquals(8, Bracket.bracketSize(5));
        assertEquals(16, Bracket.bracketSize(16));
        assertEquals(32, Bracket.bracketSize(17));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 16, 17, 31, 32})
    void everyEntrantPlacedOnceAndByesNeverMeet(final int n) {
        final Bracket<String> b = Bracket.create(players(n), new Random(n));
        final int size = Bracket.bracketSize(n);
        assertEquals(Integer.numberOfTrailingZeros(size), b.totalRounds());
        final List<Bracket.Match<String>> first = b.round(0);
        assertEquals(size / 2, first.size());
        final Set<String> seen = new HashSet<>();
        int byes = 0;
        for (final Bracket.Match<String> m : first) {
            assertTrue(m.a() != null, "slot a is never a bye");
            assertTrue(seen.add(m.a()));
            if (m.b() == null) {
                byes++;
                assertTrue(m.done() && m.walkover(), "bye = automatic walkover");
                assertEquals(m.a(), m.winner());
            } else {
                assertTrue(seen.add(m.b()));
            }
        }
        assertEquals(size - n, byes);
        assertEquals(n, seen.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5, 6, 7, 9, 13, 17})
    void numberOfFoughtMatchesIsEntrantsMinusOne(final int n) {
        final Bracket<String> b = Bracket.create(players(n), new Random(42));
        int fights = 0;
        while (!b.isFinished()) {
            final Bracket.Match<String> m = b.nextPlayable().orElseThrow();
            b.recordWinner(m, m.a());
            fights++;
        }
        assertEquals(n - 1, fights);
        assertTrue(b.champion().isPresent());
    }

    @Test
    void championIsTheBestAndPlacementsFollowTheBracket() {
        final Bracket<String> b = Bracket.create(players(8), new Random(7));
        playOut(b);
        assertEquals(Optional.of("p1"), b.champion());
        final List<List<String>> places = b.placements();
        assertEquals(3, places.size());
        assertEquals(List.of("p1"), places.get(0));
        assertEquals(1, places.get(1).size());
        assertEquals(2, places.get(2).size(), "two semi-final losers share 3rd place");
        final Set<String> podium = new HashSet<>();
        places.forEach(podium::addAll);
        assertEquals(4, podium.size());
    }

    @Test
    void threePlayersOneByeAndSemiFinalLoser() {
        final Bracket<String> b = Bracket.create(players(3), new Random(1));
        assertEquals(2, b.totalRounds());
        assertEquals(1, b.playable().size(), "only one real first-round match");
        playOut(b);
        final List<List<String>> places = b.placements();
        assertEquals(List.of("p1"), places.get(0));
        assertEquals(1, places.get(1).size());
        assertEquals(1, places.get(2).size(), "only one semi-final was fought");
    }

    @Test
    void twoPlayersAreAFinal() {
        final Bracket<String> b = Bracket.create(List.of("a", "b"), new Random(3));
        assertEquals(1, b.totalRounds());
        final Bracket.Match<String> m = b.nextPlayable().orElseThrow();
        b.recordWinner(m, "b");
        assertEquals(Optional.of("b"), b.champion());
        assertEquals(List.of(List.of("b"), List.of("a"), List.of()), b.placements());
    }

    @Test
    void singleEntrantWinsByDefault() {
        final Bracket<String> b = Bracket.create(List.of("solo"), new Random(1));
        assertTrue(b.isFinished());
        assertEquals(Optional.of("solo"), b.champion());
        assertTrue(b.playable().isEmpty());
    }

    @Test
    void emptyBracketIsFinishedWithoutChampion() {
        final Bracket<String> b = Bracket.create(List.of(), new Random(1));
        assertTrue(b.isFinished());
        assertTrue(b.champion().isEmpty());
    }

    @Test
    void withdrawalGivesWalkoverToOpponent() {
        final Bracket<String> b = Bracket.create(players(4), new Random(5));
        final Bracket.Match<String> m = b.nextPlayable().orElseThrow();
        final String quitter = m.a();
        final String opponent = m.b();
        b.withdraw(quitter);
        assertTrue(m.done());
        assertTrue(m.walkover());
        assertEquals(opponent, m.winner());
        assertFalse(b.isAlive(quitter));
        assertTrue(b.isAlive(opponent));
    }

    @Test
    void withdrawalOfFutureOpponentResolvesWhenMatchForms() {
        final Bracket<String> b = Bracket.create(players(4), new Random(9));
        final List<Bracket.Match<String>> first = b.round(0);
        final Bracket.Match<String> m0 = first.get(0);
        final Bracket.Match<String> m1 = first.get(1);
        b.recordWinner(m0, m0.a());
        b.withdraw(m0.a()); // the finalist leaves before the final
        b.recordWinner(m1, m1.b());
        assertTrue(b.isFinished());
        assertEquals(Optional.of(m1.b()), b.champion());
    }

    @Test
    void bothSidesGoneLeavesEmptySlot() {
        final Bracket<String> b = Bracket.create(players(4), new Random(11));
        final Bracket.Match<String> m0 = b.round(0).get(0);
        b.withdraw(m0.a());
        b.withdraw(m0.b());
        assertTrue(m0.done());
        assertEquals(null, m0.winner());
        final Bracket.Match<String> m1 = b.round(0).get(1);
        b.recordWinner(m1, m1.a());
        assertTrue(b.isFinished());
        assertEquals(Optional.of(m1.a()), b.champion());
        assertEquals(List.of(), b.placements().get(1), "nobody reached the final on the other side");
    }

    @Test
    void everybodyLeavesMeansNoChampion() {
        final Bracket<String> b = Bracket.create(players(3), new Random(2));
        for (final String p : players(3)) {
            b.withdraw(p);
        }
        assertTrue(b.isFinished());
        assertTrue(b.champion().isEmpty());
    }

    @Test
    void recordingInvalidResultsIsRejected() {
        final Bracket<String> b = Bracket.create(players(4), new Random(4));
        final Bracket.Match<String> m = b.nextPlayable().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> b.recordWinner(m, "nobody"));
        b.recordWinner(m, m.a());
        assertThrows(IllegalStateException.class, () -> b.recordWinner(m, m.a()));
        final Bracket.Match<String> fin = b.round(1).get(0);
        assertThrows(IllegalStateException.class, () -> b.recordWinner(fin, m.a()), "final not ready yet");
    }

    @Test
    void duplicatesAndNullsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Bracket.create(List.of("a", "a"), new Random(1)));
        final List<String> withNull = new ArrayList<>();
        withNull.add("a");
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> Bracket.create(withNull, new Random(1)));
    }

    @Test
    void currentRoundAdvances() {
        final Bracket<String> b = Bracket.create(players(4), new Random(8));
        assertEquals(1, b.currentRound());
        for (final Bracket.Match<String> m : b.round(0)) {
            b.recordWinner(m, m.a());
        }
        assertEquals(2, b.currentRound());
        assertEquals(1, b.playable().size());
        assertTrue(b.pendingMatchOf(b.round(1).get(0).a()).isPresent());
    }

    @Test
    void byePositionsAreDistinctAndSpread() {
        for (int matches = 2; matches <= 16; matches *= 2) {
            for (int byes = 0; byes < matches; byes++) {
                assertEquals(byes, Bracket.byePositions(matches, byes).size());
            }
        }
        assertEquals(Set.of(0, 2, 4, 6), Bracket.byePositions(8, 4));
    }
}
