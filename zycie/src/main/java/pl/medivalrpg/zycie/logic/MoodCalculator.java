// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mood of a country's citizens from its situation (pure, thread-safe). Factor keys (Polish, used in the API and UI):
 * {@code jedzenie, mieszkania, podatki, wojna, pokoj, oblezenie, zaleznosc, komfort, epoka} plus every temporary
 * modifier key ({@code zwyciestwo, porazka, swieto, awans_epoki, nowy_budynek, pokoj_zawarty, admin…}).
 */
public final class MoodCalculator {

    /** Result of one calculation: the target mood (before smoothing), factors and concerns (most important first). */
    public record Result(int target, Map<String, Integer> factors, List<Concern> concerns) {
    }

    private final MoodSettings s;

    public MoodCalculator(final MoodSettings settings) {
        this.s = settings;
    }

    public MoodSettings settings() {
        return this.s;
    }

    public Result calculate(final MoodInputs in) {
        final Map<String, Integer> f = new LinkedHashMap<>();
        final List<Concern> concerns = new ArrayList<>();

        // ---- food
        if (in.populationData() && in.population() > 0) {
            if (in.hungryDays() > 0) {
                final int v = -Math.min(this.s.hungerCap(), this.s.hungerBase() + this.s.hungerPerDay() * in.hungryDays());
                put(f, "jedzenie", v);
                concerns.add(Concern.of("glod", -v, "dni", Integer.toString(in.hungryDays())));
            } else if (in.foodBalance() < 0 && in.food() < -in.foodBalance() * 2) {
                put(f, "jedzenie", -this.s.lowStockPenalty());
                concerns.add(Concern.of("malo_jedzenia", this.s.lowStockPenalty()));
            } else if (in.food() > 0) {
                put(f, "jedzenie", this.s.fedBonus());
            }
        }

        // ---- housing
        if (in.populationData() && in.population() > 0) {
            if (in.population() > in.housing()) {
                final int missing = in.population() - in.housing();
                final double ratio = missing / (double) in.population();
                final int v = -Math.min(this.s.homelessCap(),
                    (int) Math.round(this.s.homelessBase() + ratio * this.s.homelessScale() + this.s.homelessPerDay() * in.homelessDays()));
                put(f, "mieszkania", v);
                concerns.add(Concern.of("brak_lozek", -v, "brak", Integer.toString(missing)));
            } else if (in.housing() >= in.population() * this.s.roomyRatio()) {
                put(f, "mieszkania", this.s.roomyBonus());
            }
        }

        // ---- taxes
        put(f, "podatki", this.taxes(in));
        final int taxes = f.getOrDefault("podatki", 0);
        if (taxes <= this.s.taxConcernAt()) {
            concerns.add(Concern.of("podatki", -taxes));
        }

        // ---- war, sieges, vassalage
        final Set<String> enemies = new LinkedHashSet<>(in.enemies());
        if (!enemies.isEmpty()) {
            put(f, "wojna", -Math.min(this.s.warCap(), this.s.warPenalty() * enemies.size()));
            for (final String e : enemies) {
                concerns.add(Concern.of("wojna", this.s.warPenalty(), "wrog", e));
            }
        } else {
            put(f, "pokoj", this.s.peaceBonus());
        }
        if (!in.siegers().isEmpty()) {
            put(f, "oblezenie", -Math.min(this.s.siegeCap(), this.s.siegePenalty() * in.siegers().size()));
            for (final String e : new LinkedHashSet<>(in.siegers())) {
                concerns.add(Concern.of("oblezenie", this.s.siegePenalty() + 10, "wrog", e));
            }
        }
        if (in.overlord() != null && !in.overlord().isBlank()) {
            put(f, "zaleznosc", -this.s.vassalPenalty());
            concerns.add(Concern.of("wasal", this.s.vassalPenalty(), "kraj", in.overlord()));
        }

        // ---- comfort & era expectations
        int comfort = 0;
        final List<String> missing = new ArrayList<>();
        for (final MoodSettings.ComfortItem item : this.s.comfort()) {
            final boolean has = item.tokens().stream().anyMatch(in.buildings()::contains);
            if (has && in.era() >= item.minEra()) {
                comfort += item.points();
            } else if (!has && item.expectedFromEra() > 0 && in.era() >= item.expectedFromEra()) {
                missing.add(item.name());
            }
        }
        put(f, "komfort", Math.min(this.s.comfortCap(), comfort));
        if (!missing.isEmpty()) {
            concerns.add(Concern.of("brak_wygod", 3 + missing.size(), "lista", String.join(", ", missing)));
        }
        if (in.era() > 1) {
            put(f, "epoka", -(in.era() - 1) * this.s.eraExpectation());
        }

        // ---- temporary modifiers (events, festival, admin)
        for (final Map.Entry<String, Integer> m : in.modifiers().entrySet()) {
            if (m.getValue() == null || m.getValue() == 0) {
                continue;
            }
            f.merge(m.getKey(), m.getValue(), Integer::sum);
            if (m.getValue() < 0) {
                concerns.add(Concern.of("zdarzenie_" + m.getKey(), -m.getValue()));
            }
        }

        int sum = this.s.base();
        for (final int v : f.values()) {
            sum += v;
        }
        concerns.sort(Comparator.comparingInt(Concern::weight).reversed());
        return new Result(clamp(sum), java.util.Collections.unmodifiableMap(f), List.copyOf(concerns));
    }

    private int taxes(final MoodInputs in) {
        int total = 0;
        if (in.taxpayers() > 0 && this.s.playerTaxMax() > 0) {
            if (in.playerTax() <= 0) {
                total += this.s.noTaxBonus();
            } else {
                final double r = Math.min(1.0, in.playerTax() / (double) this.s.playerTaxMax());
                if (r > this.s.playerTaxFree()) {
                    total -= (int) Math.round((r - this.s.playerTaxFree()) / (1.0 - this.s.playerTaxFree()) * this.s.playerTaxPenalty());
                }
            }
        }
        if (in.populationData() && in.population() > 0 && this.s.npcTaxNormal() > 0) {
            final double burden = in.npcTaxPerCitizen() / this.s.npcTaxNormal() * (Math.max(0, in.npcTaxPercent()) / 100.0);
            if (burden > 1.0) {
                total -= (int) Math.round((burden - 1.0) * this.s.npcTaxPenalty());
            } else if (burden < 0.6) {
                total += this.s.lowTaxBonus();
            }
        }
        return Math.max(-this.s.taxCap(), Math.min(this.s.noTaxBonus() + this.s.lowTaxBonus(), total));
    }

    private static void put(final Map<String, Integer> f, final String key, final int value) {
        if (value != 0) {
            f.put(key, value);
        }
    }

    public static int clamp(final int mood) {
        return Math.max(0, Math.min(100, mood));
    }

    /** Moves {@code current} towards {@code target} by at most {@code maxStep}; a negative current = first value. */
    public static int step(final int current, final int target, final int maxStep) {
        if (current < 0 || maxStep <= 0) {
            return clamp(target);
        }
        if (target > current) {
            return clamp(Math.min(target, current + maxStep));
        }
        return clamp(Math.max(target, current - maxStep));
    }

    /**
     * Effect multiplier for Ludnosc: 0 → {@code min} (0.6), 50 → 1.0, 100 → {@code max} (1.25), linear in between;
     * a protest multiplies it by {@code protestPenalty} (never below {@code min}).
     */
    public static double multiplier(final int mood, final boolean protest, final MoodSettings s) {
        final int m = clamp(mood);
        double v = m <= 50 ? s.multiplierMin() + (1.0 - s.multiplierMin()) * m / 50.0
            : 1.0 + (s.multiplierMax() - 1.0) * (m - 50) / 50.0;
        if (protest) {
            v *= s.protestPenalty();
        }
        v = Math.max(s.multiplierMin(), Math.min(s.multiplierMax(), v));
        return Math.round(v * 1000.0) / 1000.0;
    }
}
