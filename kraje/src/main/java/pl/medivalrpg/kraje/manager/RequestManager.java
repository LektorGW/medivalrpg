// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Short-lived pending requests: invitations, alliance / peace proposals, capitulation offers and confirmations.
 * Expired entries are dropped lazily on access, so no timers are needed. Main thread only.
 * (Long-lived invitations that survive restarts are {@link PendingInvites}, kept by {@code CountryManager}.)
 */
public final class RequestManager {

    public enum ProposalType { ALLIANCE, PEACE }

    /** A pending confirmation of a dangerous action ("rozwiaz", "przekaz", "wykup"). */
    public record Confirmation(String action, UUID target, long expiresAt) {
    }

    /** {@code from} offers to become the vassal of {@code to} for {@code days} days on these terms. */
    public record CapitulationOffer(UUID from, UUID to, int days, double tributeShare, long tributeMin, long expiresAt) {
    }

    private record ProposalKey(ProposalType type, UUID from, UUID to) {
    }

    private record PairKey(UUID from, UUID to) {
    }

    /** player → (country → expiry). */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();
    private final Map<ProposalKey, Long> proposals = new HashMap<>();
    private final Map<PairKey, CapitulationOffer> capitulations = new HashMap<>();
    private final Map<UUID, Confirmation> confirmations = new HashMap<>();

    // --- invites

    public void invite(final UUID player, final UUID country, final long ttlMillis) {
        this.invites.computeIfAbsent(player, p -> new HashMap<>()).put(country, System.currentTimeMillis() + ttlMillis);
    }

    public boolean hasInvite(final UUID player, final UUID country) {
        final Map<UUID, Long> map = this.invites.get(player);
        if (map == null) {
            return false;
        }
        final Long expiry = map.get(country);
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            map.remove(country);
            return false;
        }
        return true;
    }

    public List<UUID> invitesOf(final UUID player) {
        final Map<UUID, Long> map = this.invites.get(player);
        if (map == null) {
            return List.of();
        }
        final long now = System.currentTimeMillis();
        map.values().removeIf(expiry -> expiry < now);
        return List.copyOf(map.keySet());
    }

    /** Removes one short-lived invitation; true when it existed. */
    public boolean removeInvite(final UUID player, final UUID country) {
        final Map<UUID, Long> map = this.invites.get(player);
        return map != null && map.remove(country) != null;
    }

    public void clearInvites(final UUID player) {
        this.invites.remove(player);
    }

    // --- proposals

    public void propose(final ProposalType type, final UUID from, final UUID to, final long ttlMillis) {
        this.proposals.put(new ProposalKey(type, from, to), System.currentTimeMillis() + ttlMillis);
    }

    /** Whether {@code from} has a valid pending proposal to {@code to}. Consumes it if {@code consume}. */
    public boolean hasProposal(final ProposalType type, final UUID from, final UUID to, final boolean consume) {
        final ProposalKey key = new ProposalKey(type, from, to);
        final Long expiry = this.proposals.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            this.proposals.remove(key);
            return false;
        }
        if (consume) {
            this.proposals.remove(key);
        }
        return true;
    }

    /** Also voids capitulation offers between the two countries. */
    public void clearProposalsBetween(final UUID a, final UUID b) {
        this.proposals.keySet().removeIf(k -> (k.from().equals(a) && k.to().equals(b)) || (k.from().equals(b) && k.to().equals(a)));
        this.capitulations.keySet().removeIf(k -> (k.from().equals(a) && k.to().equals(b)) || (k.from().equals(b) && k.to().equals(a)));
    }

    // --- capitulation offers

    /** Stores (or replaces) the offer of {@code offer.from()} to {@code offer.to()}. */
    public void offerCapitulation(final CapitulationOffer offer) {
        this.capitulations.put(new PairKey(offer.from(), offer.to()), offer);
    }

    /** Valid offer from {@code from} to {@code to}, null when none; consumed if {@code consume}. */
    public CapitulationOffer capitulation(final UUID from, final UUID to, final boolean consume) {
        final PairKey key = new PairKey(from, to);
        final CapitulationOffer offer = this.capitulations.get(key);
        if (offer == null) {
            return null;
        }
        if (offer.expiresAt() < System.currentTimeMillis()) {
            this.capitulations.remove(key);
            return null;
        }
        if (consume) {
            this.capitulations.remove(key);
        }
        return offer;
    }

    /** Valid offers made to {@code to} (for suggestions). */
    public List<CapitulationOffer> capitulationsTo(final UUID to) {
        final long now = System.currentTimeMillis();
        this.capitulations.values().removeIf(o -> o.expiresAt() < now);
        return this.capitulations.values().stream().filter(o -> o.to().equals(to)).toList();
    }

    // --- confirmations

    public void requestConfirmation(final UUID player, final String action, final UUID target, final long ttlMillis) {
        this.confirmations.put(player, new Confirmation(action, target, System.currentTimeMillis() + ttlMillis));
    }

    /** Consumes a matching, non-expired confirmation. */
    public boolean confirm(final UUID player, final String action, final UUID target) {
        final Confirmation c = this.confirmations.get(player);
        if (c == null) {
            return false;
        }
        this.confirmations.remove(player);
        return c.expiresAt() >= System.currentTimeMillis() && c.action().equals(action) && Objects.equals(c.target(), target);
    }

    /** Removes everything related to a disbanded country. */
    public void forgetCountry(final UUID country) {
        this.invites.values().forEach(m -> m.remove(country));
        this.proposals.keySet().removeIf(k -> k.from().equals(country) || k.to().equals(country));
        this.capitulations.keySet().removeIf(k -> k.from().equals(country) || k.to().equals(country));
    }

    public void clear() {
        this.invites.clear();
        this.proposals.clear();
        this.capitulations.clear();
        this.confirmations.clear();
    }
}
