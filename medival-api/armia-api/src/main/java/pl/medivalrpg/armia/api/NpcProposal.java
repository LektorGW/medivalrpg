// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.api;

/**
 * A pending alliance / peace proposal between an NPC country and another country.
 *
 * @param relation {@code ALLIANCE} or {@code NEUTRAL} (peace)
 * @param outgoing true = sent by the NPC country, false = received by it
 * @param at       epoch millis when it was made
 */
public record NpcProposal(String relation, boolean outgoing, long at) {
}
