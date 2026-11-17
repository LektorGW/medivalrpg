// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable result of the last mood calculation of a country (read by {@code ZycieApi} from any thread).
 *
 * @param mood       0..100
 * @param factors    factor → points (ordered)
 * @param concerns   Polish concerns, most important first
 * @param multiplier effect multiplier for Ludnosc (0.6..1.25)
 * @param unrest     protest or exodus
 * @param protest    protest on the square
 * @param exodus     mood so low that citizens leave
 * @param festival   festival running
 * @param era        1..4
 * @param war        at war
 * @param enemy      name of one enemy (for texts), empty when none
 * @param siege      a siege of its land is running
 * @param hungry     hunger
 * @param highTaxes  taxes are a concern
 * @param homeless   too few beds
 * @param events     active event modifier keys (texts: {@code zdarzenia})
 * @param population NPC citizens
 * @param housing    beds
 * @param lastBuilding name of the newest building (texts: {@code <budynek>}), empty when unknown
 */
public record MoodSnapshot(int mood, Map<String, Integer> factors, List<String> concerns, double multiplier,
                           boolean unrest, boolean protest, boolean exodus, boolean festival, int era, boolean war,
                           String enemy, boolean siege, boolean hungry, boolean highTaxes, boolean homeless,
                           Set<String> events, int population, int housing, String lastBuilding) {

    public static final MoodSnapshot NEUTRAL = new MoodSnapshot(50, Map.of(), List.of(), 1.0, false, false, false, false,
        1, false, "", false, false, false, false, Set.of(), 0, 0, "");
}
