// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.bracket;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Melee / free-for-all: everybody fights at once, the last one standing wins. Places follow the elimination order
 * (the last eliminated is second). Pure logic.
 */
public final class FreeForAll<T> {

    private final Set<T> alive = new LinkedHashSet<>();
    private final List<T> eliminated = new ArrayList<>();
    private final int entrantCount;

    public FreeForAll(final List<T> entrants) {
        if (entrants.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("entrants must be non-null");
        }
        this.alive.addAll(entrants);
        if (this.alive.size() != entrants.size()) {
            throw new IllegalArgumentException("entrants must be unique");
        }
        this.entrantCount = entrants.size();
    }

    public int entrantCount() {
        return this.entrantCount;
    }

    public List<T> alive() {
        return List.copyOf(this.alive);
    }

    public boolean isAlive(final T entrant) {
        return this.alive.contains(entrant);
    }

    /** Removes an entrant (killed, disconnected). False when already out. */
    public boolean eliminate(final T entrant) {
        if (this.alive.remove(entrant)) {
            this.eliminated.add(entrant);
            return true;
        }
        return false;
    }

    /**
     * Ends the fight at timeout: {@code ranking} lists the still-alive entrants best first (see TimeoutJudge); all but
     * the first are eliminated, worst first. Alive entrants missing from the ranking count as worst.
     */
    public void resolveTimeout(final List<T> ranking) {
        final List<T> order = new ArrayList<>();
        for (final T t : ranking) {
            if (this.alive.contains(t) && !order.contains(t)) {
                order.add(t);
            }
        }
        for (final T t : this.alive) {
            if (!order.contains(t)) {
                order.add(t);
            }
        }
        for (int i = order.size() - 1; i >= 1; i--) {
            this.eliminate(order.get(i));
        }
    }

    public boolean isFinished() {
        return this.alive.size() <= 1;
    }

    public Optional<T> champion() {
        return this.isFinished() && this.alive.size() == 1 ? Optional.of(this.alive.iterator().next()) : Optional.empty();
    }

    /** {@code [[champion], [second], [third]]}; places without anybody are empty lists. */
    public List<List<T>> placements() {
        final List<List<T>> out = new ArrayList<>();
        if (!this.isFinished()) {
            return out;
        }
        final List<T> standing = new ArrayList<>();
        this.champion().ifPresent(standing::add);
        for (int i = this.eliminated.size() - 1; i >= 0; i--) {
            standing.add(this.eliminated.get(i));
        }
        if (this.alive.isEmpty()) {
            // nobody survived (e.g. everyone left): no champion, the last eliminated are not rewarded either
            out.add(List.of());
            out.add(List.of());
            out.add(List.of());
            return out;
        }
        for (int place = 0; place < 3; place++) {
            out.add(place < standing.size() ? List.of(standing.get(place)) : List.of());
        }
        return out;
    }
}
