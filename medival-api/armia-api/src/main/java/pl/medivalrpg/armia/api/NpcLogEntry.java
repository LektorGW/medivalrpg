// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * One decision of the NPC AI (see {@link ArmiaApi#npcLog}).
 *
 * @param time epoch millis
 * @param text Polish description
 */
public record NpcLogEntry(long time, String text) {
}
