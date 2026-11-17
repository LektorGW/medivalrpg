// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import pl.medivalrpg.kraje.model.StoredInvite;

/**
 * Persistent invitations ({@link StoredInvite}): at most one per (player, country), expired ones are dropped on
 * access and by {@link #prune}. Pure data structure, main thread only; {@code CountryManager} persists it.
 */
public final class PendingInvites {

    private final List<StoredInvite> invites = new ArrayList<>();

    public void load(final Collection<StoredInvite> loaded, final long now) {
        this.invites.clear();
        for (final StoredInvite i : loaded) {
            if (i.valid(now)) {
                this.add(i);
            }
        }
    }

    /** Adds or replaces the invitation of the same player to the same country. */
    public void add(final StoredInvite invite) {
        this.invites.removeIf(i -> i.player().equals(invite.player()) && i.country().equals(invite.country()));
        this.invites.add(invite);
    }

    public boolean has(final UUID player, final UUID country, final long now) {
        return this.invites.stream().anyMatch(i -> i.player().equals(player) && i.country().equals(country) && i.valid(now));
    }

    /** Valid invitations of a player, oldest first. */
    public List<StoredInvite> of(final UUID player, final long now) {
        return this.invites.stream().filter(i -> i.player().equals(player) && i.valid(now)).toList();
    }

    /** Removes one invitation; true when it existed (valid or not). */
    public boolean remove(final UUID player, final UUID country) {
        return this.invites.removeIf(i -> i.player().equals(player) && i.country().equals(country));
    }

    /** Removes every invitation of a player (joined or founded a country); true when something was removed. */
    public boolean removePlayer(final UUID player) {
        return this.invites.removeIf(i -> i.player().equals(player));
    }

    /** Removes every invitation to a country (disbanded); true when something was removed. */
    public boolean removeCountry(final UUID country) {
        return this.invites.removeIf(i -> i.country().equals(country));
    }

    /** Drops expired invitations; true when something was removed. */
    public boolean prune(final long now) {
        return this.invites.removeIf(i -> !i.valid(now));
    }

    public List<StoredInvite> all() {
        return List.copyOf(this.invites);
    }

    public int size() {
        return this.invites.size();
    }
}
