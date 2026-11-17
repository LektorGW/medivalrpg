// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the mood of one country depends on, gathered from Kraje, Ludnosc, Armia and Postep (pure data).
 *
 * @param populationData  false when Ludnosc reported nothing (food and housing are then neutral)
 * @param population      NPC citizens
 * @param housing         beds
 * @param food            granary food points
 * @param foodBalance     expected daily food change
 * @param hungryDays      consecutive days of hunger
 * @param homelessDays    consecutive days with too few beds
 * @param playerTax       daily tax per player citizen (Kraje), copper
 * @param taxpayers       player citizens paying it (0 = no player tax effect)
 * @param npcTaxPerCitizen daily NPC tax per citizen (Ludnosc), copper
 * @param npcTaxPercent   NPC tax multiplier of an NPC country (Armia), 100 = normal
 * @param enemies         names of countries at war with this one
 * @param siegers         names of countries besieging its chunks (one entry per siege)
 * @param overlord        name of the overlord when the country is a vassal, else null
 * @param buildings       normalised building types and functions the country has
 * @param era             1..4 (Postep)
 * @param modifiers       temporary event modifiers (victory, festival, admin…), key → points
 */
public record MoodInputs(boolean populationData, int population, int housing, long food, long foodBalance,
                         int hungryDays, int homelessDays, long playerTax, int taxpayers, double npcTaxPerCitizen,
                         int npcTaxPercent, List<String> enemies, List<String> siegers, String overlord,
                         Set<String> buildings, int era, Map<String, Integer> modifiers) {

    /** A neutral country: nothing known, no war. */
    public static MoodInputs empty() {
        return new MoodInputs(false, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 100, List.of(), List.of(), null, Set.of(), 1, Map.of());
    }
}
