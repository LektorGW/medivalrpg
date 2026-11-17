// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Daily research points of a country. Pure function of {@link Settings} and {@link Input}:
 *
 * <pre>
 * raw        = base + perCitizen × min(population, citizenCap) + perPlayer × min(players, playerCap)
 *            + Σ buildings: points(type) × min(count, maxPerType)
 * modifiers  = peace (no war) + perAlly × allies (≤ allyCap) − perWar × wars (≤ warCap) − vassal + discoveries "badania"
 * total      = raw × max(minMultiplier, 1 + modifiers / 100) × eraMultiplier × (npc ? npcMultiplier : 1)
 * </pre>
 *
 * Building types are matched to the configured keys by the longest key contained in the type id (Polish diacritics
 * ignored), so {@code uniwersytet_renesansowy} counts as {@code uniwersytet} and future types work without changes.
 */
public final class ResearchMath {

    private ResearchMath() {
    }

    /**
     * Configuration ({@code punkty.*} in config.yml).
     *
     * @param buildingPoints type key → points per day for one building
     * @param peaceBonus     percent when the country is at war with nobody
     * @param perAlly        percent per alliance
     * @param allyCap        max percent from alliances
     * @param perWar         percent lost per war
     * @param warCap         max percent lost to wars
     * @param vassalPenalty  percent lost while a vassal
     * @param minMultiplier  lower bound of the modifier multiplier (e.g. 0.25)
     * @param npcMultiplier  extra multiplier for NPC countries
     */
    public record Settings(double base, double perCitizen, int citizenCap, double perPlayer, int playerCap,
                           Map<String, Double> buildingPoints, int maxPerType, double peaceBonus, double perAlly,
                           double allyCap, double perWar, double warCap, double vassalPenalty, double minMultiplier,
                           double npcMultiplier) {

        public Settings {
            final Map<String, Double> normalized = new LinkedHashMap<>();
            buildingPoints.forEach((k, v) -> normalized.put(normalize(k), v));
            buildingPoints = Map.copyOf(normalized);
        }
    }

    /**
     * State of one country.
     *
     * @param population      NPC citizens (0 when Ludnosc is absent)
     * @param players         player members
     * @param buildings       finished building type → count
     * @param allies          number of alliances
     * @param wars            number of wars
     * @param vassal          the country is a vassal
     * @param npc             NPC country
     * @param eraMultiplier   multiplier of the current era
     * @param researchBonus   sum of the "badania" effect of the discoveries, percent
     */
    public record Input(int population, int players, Map<String, Integer> buildings, int allies, int wars, boolean vassal,
                        boolean npc, double eraMultiplier, double researchBonus) {
    }

    /**
     * Result with every component (for the hover breakdown in {@code /postep}).
     *
     * @param buildingDetail matched key → points from buildings of that key
     * @param modifierPercent sum of the percent modifiers (diplomacy + discoveries)
     * @param multiplier     final multiplier applied to the raw points
     */
    public record Breakdown(double base, double population, double players, double buildings, Map<String, Double> buildingDetail,
                            double diplomacyPercent, double researchPercent, double modifierPercent, double multiplier,
                            double raw, double total) {

        public static final Breakdown ZERO = new Breakdown(0, 0, 0, 0, Map.of(), 0, 0, 0, 1, 0, 0);
    }

    public static Breakdown daily(final Settings s, final Input in) {
        final double base = s.base();
        final double population = s.perCitizen() * Math.max(0, Math.min(in.population(), s.citizenCap()));
        final double players = s.perPlayer() * Math.max(0, Math.min(in.players(), s.playerCap()));

        final Map<String, Integer> perKey = new LinkedHashMap<>();
        for (final Map.Entry<String, Integer> e : in.buildings().entrySet()) {
            final String key = matchBuilding(e.getKey(), s.buildingPoints());
            if (key != null && e.getValue() != null && e.getValue() > 0) {
                perKey.merge(key, e.getValue(), Integer::sum);
            }
        }
        final Map<String, Double> detail = new LinkedHashMap<>();
        double buildings = 0;
        for (final Map.Entry<String, Integer> e : perKey.entrySet()) {
            final int counted = s.maxPerType() > 0 ? Math.min(e.getValue(), s.maxPerType()) : e.getValue();
            final double pts = s.buildingPoints().getOrDefault(e.getKey(), 0.0) * counted;
            if (pts != 0) {
                detail.put(e.getKey(), pts);
                buildings += pts;
            }
        }

        double diplomacy = 0;
        if (in.wars() <= 0) {
            diplomacy += s.peaceBonus();
        }
        diplomacy += Math.min(Math.max(0, in.allies()) * s.perAlly(), s.allyCap());
        diplomacy -= Math.min(Math.max(0, in.wars()) * s.perWar(), s.warCap());
        if (in.vassal()) {
            diplomacy -= s.vassalPenalty();
        }
        final double modifier = diplomacy + in.researchBonus();
        final double raw = base + population + players + buildings;
        double multiplier = Math.max(s.minMultiplier(), 1.0 + modifier / 100.0) * Math.max(0, in.eraMultiplier());
        if (in.npc()) {
            multiplier *= s.npcMultiplier();
        }
        final double total = Math.max(0, raw * multiplier);
        return new Breakdown(base, population, players, buildings, Map.copyOf(detail), diplomacy, in.researchBonus(),
            modifier, multiplier, raw, total);
    }

    /** The longest configured key contained in {@code type} (diacritics and case ignored), or null. */
    public static String matchBuilding(final String type, final Map<String, Double> keys) {
        if (type == null) {
            return null;
        }
        final String t = normalize(type);
        String best = null;
        for (final String key : keys.keySet()) {
            if (t.contains(key) && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        return best;
    }

    /** Lower case, Polish letters → ASCII ({@code ł → l}), spaces and dashes → underscores. */
    public static String normalize(final String s) {
        final String lower = s.toLowerCase(Locale.ROOT).replace('ł', 'l').replace(' ', '_').replace('-', '_');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
