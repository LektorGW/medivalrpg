// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Sorting of the tab list through {@code Player#setPlayerListOrder} (higher values are listed first): countries in
 * alphabetical order, inside a country by rank, players without a country last. Pure logic, no Bukkit.
 */
public final class TablistOrder {

    /** Gap between two countries; ranks weigh 1..3, so they never overlap the next country. */
    public static final int STEP = 10;

    private TablistOrder() {
    }

    /** Base value of each country: the alphabetically first country gets the highest base. */
    public static <T> Map<UUID, Integer> groupBases(final Collection<T> countries, final Function<T, UUID> id,
                                                    final Function<T, String> name) {
        final List<T> sorted = countries.stream()
            .sorted(Comparator.comparing((T c) -> name.apply(c).toLowerCase(Locale.ROOT)))
            .toList();
        final Map<UUID, Integer> order = new HashMap<>();
        int base = (sorted.size() + 1) * STEP;
        for (final T c : sorted) {
            order.put(id.apply(c), base);
            base -= STEP;
        }
        return order;
    }

    /** Order value of a player: country base + rank weight; 0 without a country. */
    public static int order(final Map<UUID, Integer> bases, final UUID countryId, final int rankWeight) {
        if (countryId == null) {
            return 0;
        }
        return bases.getOrDefault(countryId, STEP) + rankWeight;
    }
}
