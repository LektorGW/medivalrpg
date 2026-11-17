// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.UUID;

/**
 * An embassy of {@code guest} on one chunk of {@code host}'s land (see {@link KrajeApi#getEmbassies}). The chunk stays
 * host territory on the map, but only guest members may build there. Immutable snapshot. Since API 1.5.
 *
 * @param guest    country that owns the embassy
 * @param host     country whose land the embassy stands on
 * @param world    world name
 * @param chunkX   chunk x
 * @param chunkZ   chunk z
 * @param x        diplomat spawn point (block coordinates, {@code /kraj ambasada tp})
 * @param y        spawn y
 * @param z        spawn z
 * @param openedAt epoch millis of the opening
 * @param closesAt epoch millis when an expelled embassy closes (0 = not expelled)
 */
public record EmbassyInfo(UUID guest, UUID host, String world, int chunkX, int chunkZ, double x, double y, double z,
                          long openedAt, long closesAt) {

    /** True while the host has expelled the embassy and the notice period is running. */
    public boolean expelled() {
        return this.closesAt > 0;
    }
}
