// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * Mercenaries of one type hired by a country, grouped by contract end (see {@link ArmiaApi#mercenaries}).
 *
 * @param type          mercenary type id (e.g. {@code kusznik-genuenski})
 * @param name          display name, plain text
 * @param count         soldiers
 * @param contractUntil epoch millis when their contract ends (they leave unless it is extended)
 * @param wage          daily wage of one, copper
 */
public record NpcMercenary(String type, String name, int count, long contractUntil, long wage) {
}
