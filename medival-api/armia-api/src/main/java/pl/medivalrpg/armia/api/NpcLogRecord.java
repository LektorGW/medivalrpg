// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * One AI decision with its category (see {@link ArmiaApi#npcLog(java.util.UUID, String, String, int)}).
 *
 * @param time     epoch millis
 * @param category category
 * @param text     Polish description
 */
public record NpcLogRecord(long time, NpcLogCategory category, String text) {
}
