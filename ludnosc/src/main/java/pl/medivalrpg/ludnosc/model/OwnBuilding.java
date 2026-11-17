// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.UUID;

/**
 * Details of a building registered by the players ({@code /ludnosc budynek zglos}), kept in
 * {@link CountryState#ownBuildings} under the building id. Optional data: an older Ludnosc ignores it (the building
 * itself, its type, function and author in {@code origins} stay readable by every version).
 *
 * @param key      type key ({@code kuznia}, {@code mieszkanie}...)
 * @param name     optional name given by the players (null = none)
 * @param problem  missing key blocks while the function is off (null = working)
 * @param checkedAt last check of the key blocks (epoch millis)
 */
public record OwnBuilding(String key, String name, String author, UUID authorId, long createdAt, String problem, long checkedAt) {

    public OwnBuilding withProblem(final String newProblem, final long at) {
        return new OwnBuilding(this.key, this.name, this.author, this.authorId, this.createdAt, newProblem, at);
    }
}
