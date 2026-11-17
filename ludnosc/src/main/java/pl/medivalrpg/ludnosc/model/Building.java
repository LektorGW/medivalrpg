// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.UUID;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * A finished (or admin-registered) building. Immutable; replaced on change.
 *
 * @param countryId  owner (country of the anchor chunk); null when the land is wilderness
 * @param type       template id, or {@code zgloszony} for houses registered with a WorldEdit selection
 * @param beds       beds currently inside (recounted when beds are broken/placed/exploded)
 * @param spawn      where new settlers appear (may be null)
 * @param registered true for {@code /ludnosc dom zglos} houses
 * @param dye        colour of {@code {kolor}} blocks used when it was built (for repairs)
 * @param level      settlement level whose materials were used (material slots), for repairs and modernisation
 */
public record Building(UUID id, UUID countryId, String type, BuildingFunction function, BlockBox box, int beds,
                       BlockPos spawn, int rotation, long createdAt, boolean registered, String dye, int level) {

    public Building(final UUID id, final UUID countryId, final String type, final BuildingFunction function, final BlockBox box,
                    final int beds, final BlockPos spawn, final int rotation, final long createdAt, final boolean registered) {
        this(id, countryId, type, function, box, beds, spawn, rotation, createdAt, registered, null, 0);
    }

    public Building withLevel(final int newLevel) {
        return new Building(this.id, this.countryId, this.type, this.function, this.box, this.beds, this.spawn, this.rotation,
            this.createdAt, this.registered, this.dye, newLevel);
    }

    public Building withCountry(final UUID country) {
        return new Building(this.id, country, this.type, this.function, this.box, this.beds, this.spawn, this.rotation,
            this.createdAt, this.registered, this.dye, this.level);
    }

    public Building withBeds(final int beds) {
        return new Building(this.id, this.countryId, this.type, this.function, this.box, beds, this.spawn, this.rotation,
            this.createdAt, this.registered, this.dye, this.level);
    }

    public Building withSpawn(final BlockPos spawn) {
        return new Building(this.id, this.countryId, this.type, this.function, this.box, this.beds, spawn, this.rotation,
            this.createdAt, this.registered, this.dye, this.level);
    }

    /** Beds that count as citizen housing. */
    public int housingBeds() {
        return this.function != null && this.function.housing() ? Math.max(0, this.beds) : 0;
    }
}
