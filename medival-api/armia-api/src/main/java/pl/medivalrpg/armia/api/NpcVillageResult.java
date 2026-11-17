// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

import java.util.UUID;

/**
 * Result of taking over a village as a new NPC country (see {@link ArmiaApi#createNpcFromVillage}).
 *
 * @param ok        country created
 * @param countryId new country (null on failure)
 * @param name      country name
 * @param message   Polish summary or error
 * @param chunks    chunks claimed
 * @param beds      beds found in the village
 * @param villagers villagers found
 * @param adopted   villagers adopted by Ludnosc (negative = Ludnosc could not adopt the village)
 */
public record NpcVillageResult(boolean ok, UUID countryId, String name, String message, int chunks, int beds, int villagers, int adopted) {

    public static NpcVillageResult fail(final String message) {
        return new NpcVillageResult(false, null, "", message, 0, 0, 0, -1);
    }
}
