// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import java.util.UUID;
import pl.medivalrpg.armia.api.NpcStance;

/**
 * What the AI knows about a neighbouring country.
 *
 * @param id          country id
 * @param name        display name (for logs)
 * @param strength    estimated military strength (soldiers + weighted players)
 * @param opinion     our opinion of them, −100..100 (tribute raises it, wars lower it)
 * @param ally        currently allied with us
 * @param atWar       currently at war with us
 * @param npc         run by the NPC AI
 * @param sharedEnemy they fight a country we fight too
 * @param stance      attitude set by an admin (web panel)
 * @param lostChunks  chunks they conquered from us that we did not take back (reason to reclaim)
 * @param protectedBy war on them is forbidden by vassalage (our overlord / its ally); never a war target
 */
public record NeighborView(UUID id, String name, double strength, int opinion, boolean ally, boolean atWar, boolean npc,
                           boolean sharedEnemy, NpcStance stance, int lostChunks, boolean protectedBy) {

    public NeighborView {
        stance = stance == null ? NpcStance.AUTO : stance;
        lostChunks = Math.max(0, lostChunks);
    }

    /** Neighbour without stance, lost land or vassal protection (AI v1). */
    public NeighborView(final UUID id, final String name, final double strength, final int opinion, final boolean ally,
                        final boolean atWar, final boolean npc, final boolean sharedEnemy) {
        this(id, name, strength, opinion, ally, atWar, npc, sharedEnemy, NpcStance.AUTO, 0, false);
    }
}
