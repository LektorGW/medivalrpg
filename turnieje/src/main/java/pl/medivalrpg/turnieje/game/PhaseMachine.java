// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.game;

/** Holds the current {@link Phase} and refuses illegal transitions. Pure logic. */
public final class PhaseMachine {

    private Phase phase;

    public PhaseMachine(final Phase initial) {
        this.phase = initial;
    }

    public Phase phase() {
        return this.phase;
    }

    public boolean is(final Phase p) {
        return this.phase == p;
    }

    /** @throws IllegalStateException for a transition not allowed by {@link Phase#next()} */
    public void moveTo(final Phase target) {
        if (!this.phase.canMoveTo(target)) {
            throw new IllegalStateException("illegal transition " + this.phase + " -> " + target);
        }
        this.phase = target;
    }

    /** Moves when allowed; returns false (and stays) otherwise. */
    public boolean tryMoveTo(final Phase target) {
        if (!this.phase.canMoveTo(target)) {
            return false;
        }
        this.phase = target;
        return true;
    }
}
