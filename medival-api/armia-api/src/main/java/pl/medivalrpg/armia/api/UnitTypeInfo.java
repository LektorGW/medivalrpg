// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * A recruitable unit type (see {@link ArmiaApi#unitTypes()}).
 *
 * @param id            config id used by {@link ArmiaApi#recruit} (e.g. {@code piechota})
 * @param name          display name, plain text
 * @param description   one line description, plain text (may be empty)
 * @param cost          recruitment cost in copper
 * @param wage          daily wage in copper
 * @param siegeStrength contribution to sieges (1.0 = one player)
 */
public record UnitTypeInfo(String id, String name, String description, long cost, long wage, double siegeStrength) {
}
