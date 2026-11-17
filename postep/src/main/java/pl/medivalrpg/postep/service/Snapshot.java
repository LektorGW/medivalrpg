// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.postep.logic.EraRules;
import pl.medivalrpg.postep.logic.ResearchMath;

/**
 * Immutable view of one country's progress, rebuilt on the main thread after every change and every periodic
 * evaluation. Read by the API (any thread), commands, GUI, tab list, map and the JSON export.
 *
 * @param population     NPC citizens (-1 = unknown, Ludnosc absent)
 * @param effectivePop   population used for the era threshold (citizens + players × N), -1 = unknown
 * @param tier           settlement tier level, -1 = unknown
 * @param discoveries    owned ids in discovery order
 * @param effects        effect key → sum in percent
 */
public record Snapshot(UUID id, String name, String color, boolean npc, int players, int era, long eraSince,
                       double points, double totalPoints, List<String> discoveries, Set<String> discoverySet,
                       String target, EraRules.Check check, ResearchMath.Breakdown daily, double investedToday,
                       double investPointsToday, boolean pendingAdvance, int npcInvestShare, Map<String, Double> effects,
                       int population, int effectivePop, int tier) {
}
