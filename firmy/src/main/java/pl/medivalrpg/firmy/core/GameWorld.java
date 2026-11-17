// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.Optional;
import java.util.UUID;

/** What the company rules need to know about the game (Kraje, statistics, Wladcy, Postep). Faked in tests. */
public interface GameWorld {

    /** A country snapshot. {@code rank} of a player: 3 ruler, 2 advisor, 1 citizen, 0 none. */
    record Country(UUID id, String name, boolean npc, String treasuryAccount) {
    }

    Optional<Country> country(UUID id);

    Optional<Country> countryOf(UUID player);

    /** 3 = ruler, 2 = advisor, 1 = citizen, 0 = not a member. */
    int rank(UUID country, UUID player);

    boolean atWar(UUID a, UUID b);

    /** Minutes played (vanilla statistic), -1 when unknown. */
    long playMinutes(UUID player);

    /** First join, epoch millis, 0 when unknown. */
    long firstPlayed(UUID player);

    /** Reputation of the player with an NPC country (−100…100), empty when unknown (Wladcy missing). */
    Optional<Integer> reputation(UUID npcCountry, UUID player);

    /** Era of a country (1..4), 1 when unknown. */
    int era(UUID country);

    String playerName(UUID player);
}
