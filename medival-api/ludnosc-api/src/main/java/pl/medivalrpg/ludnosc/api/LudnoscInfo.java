// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.api;

import java.util.List;

/**
 * Population summary of one country (see {@link LudnoscApi#info}), like {@code /ludnosc info} + {@code /ludnosc budynki}.
 *
 * @param population   living NPC citizens
 * @param housing      housing capacity (beds)
 * @param food         food points in the granary
 * @param foodCapacity granary capacity in food points
 * @param foodBalance  expected daily food change (production − consumption)
 * @param dailyTax     expected daily tax from citizens, copper
 * @param hungryDays   consecutive days without enough food (0 = fed)
 * @param homelessDays consecutive days with more citizens than beds
 * @param buildings    finished / registered buildings
 * @param sites        construction sites
 */
public record LudnoscInfo(int population, int housing, long food, long foodCapacity, long foodBalance, long dailyTax,
                          int hungryDays, int homelessDays, List<Building> buildings, List<Site> sites,
                          int settlementLevel, String settlementName) {

    /** Compatible constructor for consumers built against the original summary. Zero means unknown. */
    public LudnoscInfo(int population, int housing, long food, long foodCapacity, long foodBalance, long dailyTax,
                      int hungryDays, int homelessDays, List<Building> buildings, List<Site> sites) {
        this(population, housing, food, foodCapacity, foodBalance, dailyTax, hungryDays, homelessDays, buildings, sites, 0, "");
    }

    /**
     * A building.
     *
     * @param type     template id ({@code zgloszony} for admin-registered houses)
     * @param name     display name, plain text
     * @param function function name in Polish (e.g. mieszkanie, spichlerz, koszary)
     * @param beds     beds inside
     */
    public record Building(String type, String name, String function, int beds, String world, int x, int y, int z) {
    }

    /**
     * A construction site.
     *
     * @param percent progress 0–100
     */
    public record Site(String type, String name, int percent, String world, int x, int y, int z) {
    }
}
