// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.List;

/**
 * Weights of the mood calculation (section {@code nastroj} of config.yml). Every factor is a whole number of mood points
 * added to {@link #base}; the result is clamped to 0..100.
 */
public record MoodSettings(
    int base,
    int maxStep,
    // food
    int fedBonus,
    int lowStockPenalty,
    int hungerBase,
    int hungerPerDay,
    int hungerCap,
    // housing
    int roomyBonus,
    double roomyRatio,
    int homelessBase,
    int homelessScale,
    int homelessPerDay,
    int homelessCap,
    // taxes
    long playerTaxMax,
    double playerTaxFree,
    int playerTaxPenalty,
    int noTaxBonus,
    double npcTaxNormal,
    int npcTaxPenalty,
    int lowTaxBonus,
    int taxCap,
    int taxConcernAt,
    // war
    int warPenalty,
    int warCap,
    int siegePenalty,
    int siegeCap,
    int peaceBonus,
    int vassalPenalty,
    // comfort & era
    List<ComfortItem> comfort,
    int comfortCap,
    int eraExpectation,
    // effects
    double multiplierMin,
    double multiplierMax,
    double protestPenalty
) {

    /**
     * A comfort building group.
     *
     * @param tokens          building types / functions that count (normalised, e.g. {@code karczma}, {@code browar})
     * @param name            Polish name for concerns, e.g. "karczma"
     * @param points          mood points while the country has one
     * @param minEra          gives points from this era on
     * @param expectedFromEra from this era on its absence is a concern ("Brakuje: łaźnia"); 0 = never
     */
    public record ComfortItem(List<String> tokens, String name, int points, int minEra, int expectedFromEra) {
    }

    public static MoodSettings defaults() {
        return new MoodSettings(50, 6,
            8, 6, 10, 5, 30,
            6, 1.15, 5, 30, 2, 25,
            100L, 0.25, 15, 3, 5.0, 15, 4, 25, -6,
            6, 18, 12, 30, 4, 5,
            List.of(
                new ComfortItem(List.of("karczma", "browar"), "karczma", 4, 1, 1),
                new ComfortItem(List.of("kosciol", "katedra"), "kościół", 4, 1, 1),
                new ComfortItem(List.of("rynek", "targ"), "rynek", 4, 1, 2),
                new ComfortItem(List.of("laznia"), "łaźnia", 4, 1, 2),
                new ComfortItem(List.of("zielarnia", "szpital"), "szpital", 3, 1, 3),
                new ComfortItem(List.of("ogrody", "park"), "park", 3, 2, 4),
                new ComfortItem(List.of("studnia"), "studnia", 2, 1, 0),
                new ComfortItem(List.of("arena", "stadion"), "arena", 3, 2, 0)),
            22, 3,
            0.6, 1.25, 0.85);
    }
}
