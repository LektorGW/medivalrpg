// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

/**
 * Context of an alliance proposal received by an NPC country.
 *
 * @param ourStrength   our strength
 * @param theirStrength proposer's strength
 * @param opinion       our opinion of the proposer
 * @param atWar         we are at war with the proposer
 * @param sharedEnemy   the proposer fights one of our enemies
 * @param ourAllies     number of alliances we already have
 */
public record AllianceView(double ourStrength, double theirStrength, int opinion, boolean atWar, boolean sharedEnemy, int ourAllies) {
}
