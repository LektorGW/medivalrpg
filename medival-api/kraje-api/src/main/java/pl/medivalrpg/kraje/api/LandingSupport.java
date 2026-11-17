// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.UUID;

/**
 * Optional naval transport proof, registered by a fleet provider (Armia) in ServicesManager.
 * Queried on the main server thread when starting a remote amphibious siege. Implementations must
 * validate current country ownership, world and nearby transport/delivered forces without loading
 * chunks, charging money, starting sieges or modifying territory. Missing providers deny landings.
 */
public interface LandingSupport {
    /** True when this country's actual naval transport supports a landing on the target chunk. */
    boolean canLand(UUID countryId, String world, int chunkX, int chunkZ);
}
