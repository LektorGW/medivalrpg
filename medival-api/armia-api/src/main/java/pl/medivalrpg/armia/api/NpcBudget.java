// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * How an NPC country splits its money, in percent (sum 100): {@code army} = recruitment, {@code buildings} =
 * settlement development ({@code LudnoscApi.developSettlement}), {@code reserve} = share of the treasury kept untouched.
 * In war and in the crisis mode the AI temporarily moves part of the buildings share to the army; after peace it
 * rebuilds first (see the Armia README).
 *
 * @param army      percent for the army
 * @param buildings percent for buildings
 * @param reserve   percent kept in the treasury
 * @param custom    false = derived from the personality (never set by an admin)
 */
public record NpcBudget(int army, int buildings, int reserve, boolean custom) {
}
