// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bracket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Single-elimination bracket of 1v1 matches. Pure logic, no Bukkit calls.
 *
 * <p>The field is padded to the next power of two; the missing places are byes, spread evenly over the first round so
 * that a bye never meets another bye. A bye (or an opponent who withdrew) gives an automatic walkover. When both sides
 * of a match are empty or withdrawn, the match produces no winner and the next round sees an empty slot.</p>
 *
 * @param <T> entrant identity (UUID in game, String in tests); must have proper equals/hashCode
 */
public final class Bracket<T> {

    /** One pairing. {@code a}/{@code b} are {@code null} for a bye/empty slot once known. */
    public static final class Match<T> {
        private final int round;
        private final int index;
        private T a;
        private T b;
        private boolean aKnown;
        private boolean bKnown;
        private boolean done;
        private boolean walkover;
        private T winner;

        Match(final int round, final int index) {
            this.round = round;
            this.index = index;
        }

        /** 0-based round number. */
        public int round() {
            return this.round;
        }

        public int index() {
            return this.index;
        }

        public T a() {
            return this.a;
        }

        public T b() {
            return this.b;
        }

        public boolean done() {
            return this.done;
        }

        /** True when decided without a fight (bye or withdrawal). */
        public boolean walkover() {
            return this.walkover;
        }

        public T winner() {
            return this.winner;
        }

        /** The other entrant of a decided match, or null. */
        public T loser() {
            if (!this.done || this.winner == null) {
                return null;
            }
            return Objects.equals(this.winner, this.a) ? this.b : this.a;
        }

        public boolean involves(final T entrant) {
            return entrant != null && (entrant.equals(this.a) || entrant.equals(this.b));
        }

        @Override
        public String toString() {
            return "R" + this.round + "#" + this.index + "[" + this.a + " vs " + this.b + (this.done ? " -> " + this.winner : "") + "]";
        }
    }

    private final List<List<Match<T>>> rounds = new ArrayList<>();
    private final Set<T> withdrawn = new HashSet<>();
    private final int entrantCount;

    private Bracket(final int entrantCount) {
        this.entrantCount = entrantCount;
    }

    /** Smallest power of two {@code >= n} (at least 2). */
    public static int bracketSize(final int n) {
        int size = 2;
        while (size < n) {
            size <<= 1;
        }
        return size;
    }

    /**
     * Creates a bracket with the entrants shuffled by {@code random} (pass a seeded Random in tests).
     *
     * @throws IllegalArgumentException on duplicates or null entrants
     */
    public static <T> Bracket<T> create(final List<T> entrants, final Random random) {
        final List<T> field = new ArrayList<>(entrants);
        if (field.contains(null) || new HashSet<>(field).size() != field.size()) {
            throw new IllegalArgumentException("entrants must be unique and non-null");
        }
        Collections.shuffle(field, random);
        final Bracket<T> bracket = new Bracket<>(field.size());
        if (field.isEmpty()) {
            return bracket;
        }
        final int size = bracketSize(field.size());
        final int firstRound = size / 2;
        final int byes = size - field.size();
        final Set<Integer> byeMatches = byePositions(firstRound, byes);
        int roundSize = firstRound;
        int round = 0;
        while (roundSize >= 1) {
            final List<Match<T>> matches = new ArrayList<>(roundSize);
            for (int i = 0; i < roundSize; i++) {
                matches.add(new Match<>(round, i));
            }
            bracket.rounds.add(matches);
            roundSize /= 2;
            round++;
        }
        int next = 0;
        for (final Match<T> m : bracket.rounds.getFirst()) {
            m.a = field.get(next++);
            m.aKnown = true;
            m.b = byeMatches.contains(m.index) ? null : field.get(next++);
            m.bKnown = true;
        }
        bracket.settle();
        return bracket;
    }

    /** Indices of first-round matches that get a bye, spread evenly ({@code byes < matches} always holds). */
    static Set<Integer> byePositions(final int matches, final int byes) {
        final Set<Integer> out = new HashSet<>();
        for (int k = 0; k < byes; k++) {
            out.add((int) ((long) k * matches / byes));
        }
        return out;
    }

    public int entrantCount() {
        return this.entrantCount;
    }

    public int totalRounds() {
        return this.rounds.size();
    }

    public List<Match<T>> round(final int round) {
        return Collections.unmodifiableList(this.rounds.get(round));
    }

    /** 1-based number of the lowest round that still has an undecided match; {@link #totalRounds()} when finished. */
    public int currentRound() {
        for (int r = 0; r < this.rounds.size(); r++) {
            for (final Match<T> m : this.rounds.get(r)) {
                if (!m.done) {
                    return r + 1;
                }
            }
        }
        return this.rounds.size();
    }

    public boolean isFinished() {
        return this.rounds.isEmpty() || this.rounds.getLast().getFirst().done;
    }

    /** Winner of the final; empty while running or when nobody is left. */
    public Optional<T> champion() {
        if (!this.isFinished() || this.rounds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.rounds.getLast().getFirst().winner);
    }

    /** Matches ready to be fought (both sides present, nobody withdrawn), lowest round first. */
    public List<Match<T>> playable() {
        final List<Match<T>> out = new ArrayList<>();
        for (final List<Match<T>> round : this.rounds) {
            for (final Match<T> m : round) {
                if (!m.done && m.aKnown && m.bKnown && m.a != null && m.b != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    public Optional<Match<T>> nextPlayable() {
        final List<Match<T>> p = this.playable();
        return p.isEmpty() ? Optional.empty() : Optional.of(p.getFirst());
    }

    /** Pending (not decided) match containing the entrant. */
    public Optional<Match<T>> pendingMatchOf(final T entrant) {
        for (final List<Match<T>> round : this.rounds) {
            for (final Match<T> m : round) {
                if (!m.done && m.involves(entrant)) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    public boolean isWithdrawn(final T entrant) {
        return this.withdrawn.contains(entrant);
    }

    /** True while the entrant is in the bracket, has not lost and has not withdrawn. */
    public boolean isAlive(final T entrant) {
        if (entrant == null || this.withdrawn.contains(entrant)) {
            return false;
        }
        boolean seen = false;
        for (final List<Match<T>> round : this.rounds) {
            for (final Match<T> m : round) {
                if (m.involves(entrant)) {
                    seen = true;
                    if (m.done && !entrant.equals(m.winner)) {
                        return false;
                    }
                }
            }
        }
        return seen;
    }

    /**
     * Records the result of a fought match.
     *
     * @throws IllegalStateException    when the match is not playable
     * @throws IllegalArgumentException when {@code winner} is not one of its entrants
     */
    public void recordWinner(final Match<T> match, final T winner) {
        if (match.done || match.a == null || match.b == null) {
            throw new IllegalStateException("match not playable: " + match);
        }
        if (!match.involves(winner)) {
            throw new IllegalArgumentException(winner + " is not in " + match);
        }
        this.decide(match, winner, false);
        this.settle();
    }

    /** Withdraws an entrant (disconnect, leave). Their pending match becomes a walkover for the opponent. */
    public void withdraw(final T entrant) {
        if (entrant != null && this.withdrawn.add(entrant)) {
            this.settle();
        }
    }

    private void decide(final Match<T> match, final T winner, final boolean walkover) {
        match.done = true;
        match.winner = winner;
        match.walkover = walkover;
        final int nextRound = match.round + 1;
        if (nextRound < this.rounds.size()) {
            final Match<T> next = this.rounds.get(nextRound).get(match.index / 2);
            if (match.index % 2 == 0) {
                next.a = winner;
                next.aKnown = true;
            } else {
                next.b = winner;
                next.bKnown = true;
            }
        }
    }

    /** Resolves byes, empty slots and withdrawals until nothing changes. */
    private void settle() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final List<Match<T>> round : this.rounds) {
                for (final Match<T> m : round) {
                    if (m.done && m.walkover && m.winner != null && this.withdrawn.contains(m.winner)) {
                        m.done = false;
                    }
                    if (m.done || !m.aKnown || !m.bKnown) {
                        continue;
                    }
                    final boolean hasA = m.a != null && !this.withdrawn.contains(m.a);
                    final boolean hasB = m.b != null && !this.withdrawn.contains(m.b);
                    if (hasA && hasB) {
                        continue;
                    }
                    this.decide(m, hasA ? m.a : hasB ? m.b : null, true);
                    changed = true;
                }
            }
        }
    }

    /**
     * Final standings once finished: {@code [[champion], [runner-up], [semi-final losers...]]}. Places without anybody
     * are empty lists. Entrants who withdrew keep the place they reached.
     */
    public List<List<T>> placements() {
        final List<List<T>> out = new ArrayList<>();
        if (!this.isFinished() || this.rounds.isEmpty()) {
            return out;
        }
        final Match<T> fin = this.rounds.getLast().getFirst();
        out.add(fin.winner == null ? List.of() : List.of(fin.winner));
        final T runnerUp = fin.loser();
        out.add(runnerUp == null ? List.of() : List.of(runnerUp));
        final List<T> third = new ArrayList<>();
        if (this.rounds.size() >= 2) {
            for (final Match<T> m : this.rounds.get(this.rounds.size() - 2)) {
                final T l = m.loser();
                if (l != null) {
                    third.add(l);
                }
            }
        }
        out.add(List.copyOf(third));
        return out;
    }
}
