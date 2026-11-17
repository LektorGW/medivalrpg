// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

/**
 * When to (re)issue a pathfinder move for a citizen (pure). The villager brain stays on: we only set a path in short
 * windows; once the citizen arrived, vanilla takes over inside {@link #leashRadius} (work, stroll, gossip).
 *
 * @param arriveRadius distance counted as "arrived"
 * @param leashRadius  an arrived citizen is steered back only after wandering farther than this
 * @param repathTicks  minimum ticks between two moves of one citizen (vanilla may override the path meanwhile)
 * @param maxStep      farther targets are approached in steps of this length (vanilla path range ≈ 48 blocks)
 * @param failLimit    failed path searches before the target is given up for {@link #failCooldown} ticks
 * @param failCooldown ticks
 */
public record SteeringRules(double arriveRadius, double leashRadius, int repathTicks, int maxStep, int failLimit,
                            int failCooldown) {

    public enum Decision {
        /** Nothing to do now. */
        IDLE,
        /** Close enough: mark arrived, let vanilla act. */
        ARRIVED,
        /** Issue a pathfinder move. */
        MOVE
    }

    public static SteeringRules defaults() {
        return new SteeringRules(6.0, 20.0, 60, 32, 3, 1200);
    }

    public Decision decide(final double distance, final boolean arrived, final long ticksSinceMove) {
        if (distance <= this.arriveRadius) {
            return Decision.ARRIVED;
        }
        if (arrived && distance <= this.leashRadius) {
            return Decision.IDLE;
        }
        if (ticksSinceMove < this.repathTicks) {
            return Decision.IDLE;
        }
        return Decision.MOVE;
    }

    /** Fraction of the way to walk now (1 = whole way): long distances are split into {@link #maxStep} steps. */
    public double stepFraction(final double distance) {
        if (distance <= this.maxStep || distance <= 0) {
            return 1.0;
        }
        return this.maxStep / distance;
    }
}
