// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.kraje.model.Rank;

/** Who may do what with flags (pure, unit-tested). */
public final class FlagAccess {

    private FlagAccess() {
    }

    /**
     * Moderator: panel admin, a UUID from {@code flags.moderators} (WorldSteward by default) or a player with
     * {@code kraje.flagi.moderuj}. Moderators approve and reject uploads.
     */
    public static boolean moderator(final UUID actor, final boolean panelAdmin, final boolean permission, final Set<UUID> moderators) {
        return panelAdmin || permission || (actor != null && moderators.contains(actor));
    }

    /**
     * Upload, withdraw, reset, restore and place the capital banner: a member of a player country with at least
     * {@code needed}; moderators for any country (including NPC countries, which have no members).
     *
     * @param rank the actor's rank in that country (null = not a member)
     */
    public static boolean manage(final Rank rank, final boolean npcCountry, final boolean moderator, final Rank needed) {
        if (moderator) {
            return true;
        }
        return !npcCountry && rank != null && rank.atLeast(needed);
    }

    /** Pending upload, rejection reason and history are visible to those who manage the flag and to moderators. */
    public static boolean seePrivate(final Rank rank, final boolean npcCountry, final boolean moderator, final Rank needed) {
        return manage(rank, npcCountry, moderator, needed);
    }
}
