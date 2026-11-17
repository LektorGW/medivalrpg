// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a soldier may attack, as pure rules. Enemies are only players and soldiers of countries <b>at war</b> with
 * the soldier's country; own, allied and neutral countries are never targets. Monsters are fought on own land (or
 * when they attacked the soldier, or when the commander ordered it).
 */
public final class TargetSelector {

    /** Relation of the soldier's country to another country. */
    public enum Stance { OWN, ALLY, NEUTRAL, ENEMY }

    public enum Kind { PLAYER, SOLDIER, MONSTER, OTHER }

    /** Relation lookup between the soldier's country and another (never called with a null country). */
    @FunctionalInterface
    public interface Relations {
        Stance stance(UUID own, UUID other);
    }

    /**
     * @param attackCountryless    attack players without a country (never by default)
     * @param monstersOnOwnLand    attack monsters standing on own territory
     * @param monstersAnywhere     attack monsters anywhere within range
     */
    public record Rules(boolean attackCountryless, boolean monstersOnOwnLand, boolean monstersAnywhere) {
        public static Rules defaults() {
            return new Rules(false, true, false);
        }
    }

    /**
     * One entity near a soldier.
     *
     * @param handle     caller's object (the entity), returned by {@link #choose}
     * @param kind       what it is
     * @param country    its country (players/soldiers; null = none)
     * @param distanceSq squared distance to the soldier
     * @param onOwnLand  stands on the soldier's country land
     * @param attackedUs it hurt this soldier recently
     * @param exempt     never a target (creative/spectator player, admin bypass, dead)
     */
    public record Candidate(Object handle, Kind kind, UUID country, double distanceSq, boolean onOwnLand, boolean attackedUs, boolean exempt) {
    }

    private TargetSelector() {
    }

    /** True if a soldier of {@code own} may attack {@code c} on its own initiative. */
    public static boolean isHostile(final UUID own, final Candidate c, final Relations relations, final Rules rules) {
        if (c.exempt()) {
            return false;
        }
        return switch (c.kind()) {
            case PLAYER -> c.country() == null
                ? rules.attackCountryless()
                : relations.stance(own, c.country()) == Stance.ENEMY;
            case SOLDIER -> c.country() != null && !c.country().equals(own) && relations.stance(own, c.country()) == Stance.ENEMY;
            case MONSTER -> c.attackedUs() || rules.monstersAnywhere() || (rules.monstersOnOwnLand() && c.onOwnLand());
            case OTHER -> false;
        };
    }

    /**
     * True if a commander may order an attack on {@code c}: enemies at war and any monster. Own, allied and
     * neutral players/soldiers stay protected even by direct order.
     */
    public static boolean isOrderable(final UUID own, final Candidate c, final Relations relations, final Rules rules) {
        if (c.exempt()) {
            return false;
        }
        if (c.kind() == Kind.MONSTER) {
            return true;
        }
        return isHostile(own, c, relations, rules);
    }

    /**
     * Best target within {@code maxDistanceSq}: whoever attacked us first, then soldiers and players before monsters,
     * then the closest.
     */
    public static Optional<Candidate> choose(final UUID own, final List<Candidate> candidates, final Relations relations,
                                             final Rules rules, final double maxDistanceSq) {
        return candidates.stream()
            .filter(c -> c.distanceSq() <= maxDistanceSq)
            .filter(c -> isHostile(own, c, relations, rules))
            .min(Comparator
                .comparing((Candidate c) -> !c.attackedUs())
                .thenComparing(c -> c.kind() == Kind.MONSTER)
                .thenComparingDouble(Candidate::distanceSq));
    }
}
