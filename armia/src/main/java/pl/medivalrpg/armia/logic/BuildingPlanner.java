// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What the AI orders next from Ludnosc: housing when nearly full, otherwise one of each utility building. Pure. */
public final class BuildingPlanner {

    private BuildingPlanner() {
    }

    /**
     * @param population   current NPC population
     * @param housing      housing capacity
     * @param ordered      how many of each type we already ordered
     * @param available    building types Ludnosc knows
     * @param housingTypes preferred housing types, in order
     * @param utilities    utility buildings to have once each, in order
     * @param maxHousing   cap of housing orders
     */
    public static Optional<String> next(final int population, final int housing, final Map<String, Integer> ordered,
                                        final Collection<String> available, final List<String> housingTypes,
                                        final List<String> utilities, final int maxHousing) {
        final int housingOrdered = housingTypes.stream().mapToInt(t -> ordered.getOrDefault(t, 0)).sum();
        if (housing - population <= 1 && housingOrdered < maxHousing) {
            final Optional<String> house = housingTypes.stream().filter(available::contains).findFirst();
            if (house.isPresent()) {
                return house;
            }
        }
        return utilities.stream()
            .filter(available::contains)
            .filter(t -> ordered.getOrDefault(t, 0) == 0)
            .findFirst();
    }
}
