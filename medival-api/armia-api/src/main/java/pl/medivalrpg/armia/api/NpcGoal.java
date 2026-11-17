// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

import java.util.UUID;

/**
 * Current goal of the NPC AI (see {@link ArmiaApi#aiGoalDetails}).
 *
 * @param kind     {@code SAVING | WAR | EXPANSION | BUILDING | RECRUITING | DIPLOMACY | IDLE}
 * @param text     short Polish description, e.g. "Prowadzi wojnę z: Królestwo Polan"
 * @param target   country the goal is about (enemy, overlord...), may be null
 * @param progress 0..1, or null when it makes no sense
 */
public record NpcGoal(String kind, String text, UUID target, Double progress) {
}
