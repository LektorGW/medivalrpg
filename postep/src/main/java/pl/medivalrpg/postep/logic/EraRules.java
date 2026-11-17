// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import pl.medivalrpg.postep.model.Discovery;

/**
 * Conditions for entering the next era and the progress bar. Pure function.
 *
 * <p>A country may enter era {@code e + 1} when it owns every discovery of era {@code e} marked as required, has
 * spent at least the minimum time in era {@code e}, and meets the population / settlement-tier thresholds of era
 * {@code e + 1}. A threshold with an unknown current value (Ludnosc absent) is treated as met when
 * {@link Input#skipUnknown()} is set.</p>
 *
 * <p>Progress = weighted average of the discovery part (owned required cost + spendable points, over the total
 * required cost), the time part and the threshold part; it stays below 1 until everything is met.</p>
 */
public final class EraRules {

    private EraRules() {
    }

    /** Polish templates of the "missing" lines: {name}, {left}, {have}, {need}. */
    public record Texts(String discovery, String time, String population, String tier) {

        public static final Texts DEFAULT = new Texts("odkrycie: {name}", "czas w epoce: jeszcze {left}",
            "ludność ≥ {need} (jest {have})", "poziom osady ≥ {need} (jest {have})");
    }

    /** Weights of the progress parts (normalised, need not sum to 1). */
    public record Weights(double discoveries, double time, double thresholds) {

        public static final Weights DEFAULT = new Weights(0.6, 0.25, 0.15);
    }

    /**
     * @param era            current era
     * @param maxEra         last era
     * @param eraSince       epoch millis of entering the current era
     * @param now            epoch millis
     * @param minDays        minimum days in the current era
     * @param owned          owned discoveries
     * @param required       discoveries of the current era required to leave it
     * @param points         spendable research points
     * @param population     effective population, -1 = unknown
     * @param minPopulation  population needed for the next era (0 = none)
     * @param tier           settlement tier level, -1 = unknown
     * @param tierName       name of the current tier (for the text)
     * @param minTier        tier level needed for the next era (0 = none)
     * @param minTierName    name of the needed tier
     * @param skipUnknown    unknown thresholds count as met
     */
    public record Input(int era, int maxEra, long eraSince, long now, double minDays, Set<String> owned,
                        List<Discovery> required, double points, int population, int minPopulation, int tier,
                        String tierName, int minTier, String minTierName, boolean skipUnknown) {
    }

    /**
     * @param atMaxEra          the country is in the last era
     * @param ready             every condition is met
     * @param progress          0..1
     * @param missing           Polish lines of what is missing
     * @param missingDiscoveries ids of required discoveries not owned yet
     * @param timeLeftMillis    time still to spend in the era (0 = done)
     */
    public record Check(boolean atMaxEra, boolean ready, double progress, List<String> missing,
                        List<String> missingDiscoveries, double discoveryPart, double timePart, double thresholdPart,
                        long timeLeftMillis) {

        public static final Check MAX = new Check(true, false, 1.0, List.of(), List.of(), 1, 1, 1, 0);
    }

    public static final long DAY = 24L * 60 * 60 * 1000;

    public static Check evaluate(final Input in, final Weights w, final Texts texts) {
        if (in.era() >= in.maxEra()) {
            return Check.MAX;
        }
        final List<String> missing = new ArrayList<>();
        final List<String> missingIds = new ArrayList<>();

        // discoveries
        double totalCost = 0;
        double ownedCost = 0;
        for (final Discovery d : in.required()) {
            totalCost += d.cost();
            if (in.owned().contains(d.id())) {
                ownedCost += d.cost();
            } else {
                missingIds.add(d.id());
                missing.add(texts.discovery().replace("{name}", d.name()));
            }
        }
        final double discoveryPart = totalCost <= 0 ? 1.0
            : Math.min(1.0, (ownedCost + Math.min(Math.max(0, in.points()), totalCost - ownedCost)) / totalCost);
        final boolean discoveriesOk = missingIds.isEmpty();

        // time
        final long needMillis = (long) (Math.max(0, in.minDays()) * DAY);
        final long spent = Math.max(0, in.now() - in.eraSince());
        final long left = Math.max(0, needMillis - spent);
        final double timePart = needMillis <= 0 ? 1.0 : Math.min(1.0, (double) spent / needMillis);
        if (left > 0) {
            missing.add(texts.time().replace("{left}", duration(left)));
        }

        // thresholds
        double thresholdSum = 0;
        int thresholdCount = 0;
        boolean thresholdsOk = true;
        if (in.minPopulation() > 0) {
            thresholdCount++;
            if (in.population() < 0) {
                thresholdSum += in.skipUnknown() ? 1 : 0;
                thresholdsOk &= in.skipUnknown();
                if (!in.skipUnknown()) {
                    missing.add(texts.population().replace("{need}", Integer.toString(in.minPopulation())).replace("{have}", "?"));
                }
            } else {
                thresholdSum += Math.min(1.0, (double) in.population() / in.minPopulation());
                if (in.population() < in.minPopulation()) {
                    thresholdsOk = false;
                    missing.add(texts.population().replace("{need}", Integer.toString(in.minPopulation()))
                        .replace("{have}", Integer.toString(in.population())));
                }
            }
        }
        if (in.minTier() > 0) {
            thresholdCount++;
            if (in.tier() < 0) {
                thresholdSum += in.skipUnknown() ? 1 : 0;
                thresholdsOk &= in.skipUnknown();
                if (!in.skipUnknown()) {
                    missing.add(texts.tier().replace("{need}", in.minTierName()).replace("{have}", "?"));
                }
            } else {
                thresholdSum += Math.min(1.0, (double) in.tier() / in.minTier());
                if (in.tier() < in.minTier()) {
                    thresholdsOk = false;
                    missing.add(texts.tier().replace("{need}", in.minTierName()).replace("{have}", in.tierName()));
                }
            }
        }
        final double thresholdPart = thresholdCount == 0 ? 1.0 : thresholdSum / thresholdCount;

        final boolean ready = discoveriesOk && left == 0 && thresholdsOk;
        final double wSum = Math.max(1e-9, w.discoveries() + w.time() + w.thresholds());
        double progress = (w.discoveries() * discoveryPart + w.time() * timePart + w.thresholds() * thresholdPart) / wSum;
        progress = ready ? 1.0 : Math.min(0.99, Math.max(0.0, progress));
        return new Check(false, ready, progress, List.copyOf(missing), List.copyOf(missingIds), discoveryPart, timePart,
            thresholdPart, left);
    }

    /** Polish duration: "3 dni 4 h", "5 h 12 min", "12 min". */
    public static String duration(final long millis) {
        final long minutes = Math.max(0, (millis + 59_999) / 60_000);
        final long days = minutes / (24 * 60);
        final long hours = minutes % (24 * 60) / 60;
        final long mins = minutes % 60;
        if (days > 0) {
            return days + " " + (days == 1 ? "dzień" : "dni") + (hours > 0 ? " " + hours + " h" : "");
        }
        if (hours > 0) {
            return hours + " h" + (mins > 0 ? " " + mins + " min" : "");
        }
        return mins + " min";
    }
}
