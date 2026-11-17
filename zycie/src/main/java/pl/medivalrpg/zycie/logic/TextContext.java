// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.Set;

/**
 * The situation a text (bubble, reaction, dialogue) is chosen for.
 *
 * @param phase     part of the day
 * @param era       1..4
 * @param mood      0..100
 * @param war       the country is at war
 * @param siege     a siege of the country's land is running
 * @param hungry    the granary is empty / hunger
 * @param highTaxes taxes are a concern
 * @param homeless  too few beds
 * @param festival  festival running
 * @param protest   protest running
 * @param events    recent events: {@code nowy_budynek, awans_epoki, zwyciestwo, porazka, pokoj_zawarty, wojna_wypowiedziana}
 * @param jobs      vanilla profession key (e.g. {@code farmer}) and the city job id (e.g. {@code piekarz})
 * @param standing  for reactions: {@code wladca, rodak, zwierzchnik, sojusznik, obcy, wrog}; null otherwise
 */
public record TextContext(DayPhase phase, int era, int mood, boolean war, boolean siege, boolean hungry,
                          boolean highTaxes, boolean homeless, boolean festival, boolean protest, Set<String> events,
                          Set<String> jobs, String standing) {

    public TextContext withStanding(final String newStanding) {
        return new TextContext(this.phase, this.era, this.mood, this.war, this.siege, this.hungry, this.highTaxes,
            this.homeless, this.festival, this.protest, this.events, this.jobs, newStanding);
    }
}
