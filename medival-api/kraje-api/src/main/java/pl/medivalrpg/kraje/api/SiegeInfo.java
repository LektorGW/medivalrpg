// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.UUID;

/**
 * Snapshot of a running siege of one chunk.
 *
 * @param id        siege id
 * @param attacker  attacking country
 * @param defender  defending country (current chunk owner)
 * @param world     world name
 * @param cx        chunk x
 * @param cz        chunk z
 * @param progress  capture progress 0..1 (1 = chunk conquered)
 * @param startedAt epoch millis
 * @param endsAt    epoch millis when the siege fails if not finished
 */
public record SiegeInfo(UUID id, UUID attacker, UUID defender, String world, int cx, int cz,
                        double progress, long startedAt, long endsAt) {
}
