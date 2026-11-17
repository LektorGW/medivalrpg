// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * A daily tribute between an NPC country and another country: an accepted tribute demand
 * ({@link ArmiaApi#demandTribute}) or a vassalage from Kraje.
 *
 * @param amount daily amount in copper (for a vassalage: the expected tribute)
 * @param pays   true = the NPC country pays, false = it receives
 * @param until  epoch millis when it ends, or null
 */
public record NpcTribute(long amount, boolean pays, Long until) {
}
