// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.model;

/**
 * One era from {@code config.yml} ({@code epoki.<id>}).
 *
 * @param id               1..n
 * @param name             Polish name, e.g. "Renesans"
 * @param accusative       name after "wkracza w", e.g. "epokę przemysłową"
 * @param years            label, e.g. "XV–XVII w."
 * @param color            hex colour {@code #RRGGBB}
 * @param icon             one unicode symbol, e.g. "⚜"
 * @param description      short Polish description
 * @param guiIcon          material name for the tree GUI
 * @param minDays          minimum days spent IN this era before the next one
 * @param minPopulation    population needed to ENTER this era (0 = none)
 * @param minTier          settlement tier needed to ENTER this era (0 = none)
 * @param pointMultiplier  multiplier of daily research points while in this era
 * @param investPerGold    research points per 1 gold (100 copper) for the first coin invested in a day
 * @param investDailyCap   maximum research points from investments per day (asymptote)
 */
public record EraDef(int id, String name, String accusative, String years, String color, String icon, String description, String guiIcon,
                     double minDays, int minPopulation, int minTier, double pointMultiplier, double investPerGold,
                     double investDailyCap) {
}
