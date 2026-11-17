// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Units of the eras of the Postep plugin (1 Średniowiecze … 4 Nowoczesność): which unit a country may recruit, which
 * newer unit replaces an old one ("przezbrojenie"), the strength bonus of the era and the camouflage colour. Pure.
 */
public final class Eras {

    /**
     * Era data of one unit type (config {@code units.<id>.era|discovery|replaces|camo|heal-per-second}).
     *
     * @param era            first era the unit is available in (1 = always)
     * @param discovery      discovery the country also needs (empty = none)
     * @param replaces       older unit ids this unit replaces (target of a rearm)
     * @param camo           leather in a camouflage version of the country colour
     * @param healPerSecond  medic: hit points per second healed to nearby own soldiers (0 = no medic)
     */
    public record UnitEra(int era, String discovery, List<String> replaces, boolean camo, double healPerSecond) {

        public static final UnitEra MEDIEVAL = new UnitEra(1, "", List.of(), false, 0);

        public UnitEra {
            era = Math.max(1, Math.min(4, era));
            discovery = discovery == null ? "" : discovery.trim();
            replaces = replaces == null ? List.of() : List.copyOf(replaces);
            healPerSecond = Math.max(0, healPerSecond);
        }
    }

    /**
     * @param hpBonusPerEra     extra hit points per era above the first (0.1 = +10 %)
     * @param damageBonusPerEra extra damage per era above the first
     * @param rearmCostShare    rearming costs this share of the new unit's recruitment cost
     * @param aiRearmPerTick    soldiers the NPC AI rearms per tick at most
     */
    public record Rules(double hpBonusPerEra, double damageBonusPerEra, double rearmCostShare, int aiRearmPerTick) {

        public static Rules defaults() {
            return new Rules(0.1, 0.1, 0.5, 3);
        }
    }

    private Eras() {
    }

    public static boolean available(final UnitEra e, final int countryEra, final Predicate<String> hasDiscovery) {
        final UnitEra u = e == null ? UnitEra.MEDIEVAL : e;
        return countryEra >= u.era() && (u.discovery().isEmpty() || hasDiscovery.test(u.discovery()));
    }

    /**
     * The best available replacement of {@code type}, following the chain (piechota → pikinier → piechota liniowa →
     * żołnierz) as far as the country may go; empty when there is none.
     */
    public static Optional<String> upgrade(final String type, final Map<String, UnitEra> units, final int countryEra,
                                           final Predicate<String> hasDiscovery) {
        // Walk the "replaced by" graph from the type (also through units the country cannot have yet, so a modern
        // soldier still replaces a pikeman when the rifle was never discovered) and take the newest available one.
        final java.util.Deque<String> todo = new java.util.ArrayDeque<>(List.of(type));
        final java.util.Set<String> seen = new java.util.HashSet<>(List.of(type));
        String best = null;
        int bestEra = -1;
        while (!todo.isEmpty()) {
            final String from = todo.poll();
            for (final Map.Entry<String, UnitEra> e : units.entrySet()) {
                if (!e.getValue().replaces().contains(from) || !seen.add(e.getKey())) {
                    continue;
                }
                todo.add(e.getKey());
                if (available(e.getValue(), countryEra, hasDiscovery) && e.getValue().era() > bestEra) {
                    best = e.getKey();
                    bestEra = e.getValue().era();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Units the country may recruit now and that are not replaced by another available unit (what the AI picks). */
    public static List<String> current(final Map<String, UnitEra> units, final int countryEra, final Predicate<String> hasDiscovery) {
        final List<String> out = new ArrayList<>();
        for (final Map.Entry<String, UnitEra> e : units.entrySet()) {
            if (available(e.getValue(), countryEra, hasDiscovery) && upgrade(e.getKey(), units, countryEra, hasDiscovery).isEmpty()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Multiplier of hit points / damage: era bonus plus the Postep effect {@code sila-armii} (percent). Never below 0.1. */
    public static double power(final double bonusPerEra, final int era, final double effectPercent) {
        return Math.max(0.1, 1.0 + Math.max(0, era - 1) * bonusPerEra + effectPercent / 100.0);
    }

    public static long rearmCost(final Rules r, final long newUnitCost) {
        return Math.max(0, Math.round(Math.max(0, newUnitCost) * r.rearmCostShare()));
    }

    /** Camouflage of a country colour: mixed 60 % with olive drab (0x556B2F) and darkened. */
    public static int camo(final int rgb) {
        final int[] olive = {0x55, 0x6B, 0x2F};
        final int[] c = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        int out = 0;
        for (int i = 0; i < 3; i++) {
            final int v = (int) Math.round((c[i] * 0.4 + olive[i] * 0.6) * 0.85);
            out = (out << 8) | Math.max(0, Math.min(255, v));
        }
        return out;
    }
}
