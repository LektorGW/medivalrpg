// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.UUID;

/**
 * Registry entry of one NPC citizen (a real {@code Villager} entity). Immutable; replaced on every change so it can be
 * read from any thread. The location is the last known one (updated when the entity's chunk unloads and periodically
 * while loaded), so population counts work without loading chunks.
 *
 * @param entityId   villager entity UUID
 * @param countryId  country the citizen belongs to
 * @param name       Polish first name shown as custom name
 * @param profession profession key, e.g. {@code minecraft:farmer}
 * @param adult      false for baby villagers
 * @param missed     how many times the chunk of the last known location loaded without the entity in it
 * @param pending    the villager does not exist yet (its chunk was not loaded when it arrived / was born / came
 *                   back from the army); {@code entityId} is a placeholder and the entity appears when the chunk loads
 */
public record CitizenRecord(UUID entityId, UUID countryId, String name, String profession, boolean adult,
                            String world, int x, int y, int z, long since, int missed, boolean pending) {

    /** Record of an existing entity. */
    public CitizenRecord(final UUID entityId, final UUID countryId, final String name, final String profession,
                         final boolean adult, final String world, final int x, final int y, final int z, final long since,
                         final int missed) {
        this(entityId, countryId, name, profession, adult, world, x, y, z, since, missed, false);
    }

    public CitizenRecord withCountry(final UUID country) {
        return new CitizenRecord(this.entityId, country, this.name, this.profession, this.adult, this.world, this.x, this.y,
            this.z, this.since, 0, this.pending);
    }

    public CitizenRecord withState(final String world, final int x, final int y, final int z, final String profession,
                                   final boolean adult) {
        return new CitizenRecord(this.entityId, this.countryId, this.name, profession, adult, world, x, y, z, this.since, 0,
            this.pending);
    }

    public CitizenRecord withMissed(final int missed) {
        return new CitizenRecord(this.entityId, this.countryId, this.name, this.profession, this.adult, this.world, this.x,
            this.y, this.z, this.since, missed, this.pending);
    }

    public int chunkX() {
        return this.x >> 4;
    }

    public int chunkZ() {
        return this.z >> 4;
    }

    public ChunkRef chunk() {
        return new ChunkRef(this.world, this.chunkX(), this.chunkZ());
    }
}
