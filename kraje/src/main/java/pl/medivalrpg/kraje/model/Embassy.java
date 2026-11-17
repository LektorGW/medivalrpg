// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import java.util.UUID;

/**
 * Embassy of the country that stores it (the guest) on one chunk of {@link #host()}'s land. The chunk stays claimed by
 * the host; protection treats the guest as its owner. {@link #spot()} is the diplomat spawn point.
 *
 * @param host     host country
 * @param chunk    the embassy chunk (host land)
 * @param spot     diplomat spawn point inside the chunk
 * @param openedAt epoch millis of the opening
 * @param closesAt epoch millis when an expelled embassy closes (0 = not expelled)
 */
public record Embassy(UUID host, ChunkPos chunk, SpawnPoint spot, long openedAt, long closesAt) {

    public boolean expelled() {
        return this.closesAt > 0;
    }

    public Embassy withClosesAt(final long at) {
        return new Embassy(this.host, this.chunk, this.spot, this.openedAt, Math.max(0, at));
    }
}
