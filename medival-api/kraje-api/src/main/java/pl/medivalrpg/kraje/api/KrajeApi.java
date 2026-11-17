// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;

/**
 * Public API of the Kraje plugin, registered in Bukkit's ServicesManager:
 *
 * <pre>{@code
 * KrajeApi api = Bukkit.getServicesManager().load(KrajeApi.class);
 * api.getCountryOf(player.getUniqueId()).ifPresent(k -> ...);
 * }</pre>
 *
 * <p>Read methods return immutable snapshots and may be called from any thread.
 * <b>Mutating methods must be called on the main server thread</b>; they persist, refresh the map/tab list
 * and fire the events from {@code pl.medivalrpg.kraje.api.event} exactly like the equivalent player commands.</p>
 */
public interface KrajeApi {

    // ---- read ----------------------------------------------------------------------------------

    /** Country owning the chunk at the given location. */
    Optional<KrajInfo> getCountryAt(Location location);

    /** Country owning chunk ({@code cx}, {@code cz}) in {@code world}. */
    Optional<KrajInfo> getCountryAt(String world, int cx, int cz);

    /** Country the player belongs to. */
    Optional<KrajInfo> getCountryOf(UUID player);

    Optional<KrajInfo> getCountry(UUID countryId);

    Optional<KrajInfo> getCountryByName(String name);

    Collection<KrajInfo> getCountries();

    /** Claimed chunk keys of a country in one world; key = {@code ((long) cx << 32) | (cz & 0xFFFFFFFFL)}. */
    Set<Long> getClaims(UUID countryId, String world);

    /** Rank of the player in their country. */
    Optional<Rank> getRank(UUID player);

    /** Relation between two countries ({@link Relation#NEUTRAL} if unknown or equal). */
    Relation getRelation(UUID countryA, UUID countryB);

    default boolean isAtWar(final UUID countryA, final UUID countryB) {
        return this.getRelation(countryA, countryB) == Relation.WAR;
    }

    /** Running sieges. */
    Collection<SiegeInfo> getSieges();

    Optional<SiegeInfo> getSiege(String world, int cx, int cz);

    default Optional<String> getCountryName(final UUID player) {
        return this.getCountryOf(player).map(KrajInfo::name);
    }

    default Optional<TextColor> getCountryColor(final UUID player) {
        return this.getCountryOf(player).map(KrajInfo::color);
    }

    // ---- NPC countries & automation (main thread) ------------------------------------------------

    /**
     * Creates a country run by NPCs. The chunk at {@code capital} becomes its capital and spawn.
     * Same name/colour/distance rules as {@code /kraj zaloz} unless {@code force} (admin) is true.
     * {@link KrajeResult#countryId()} holds the new id on success.
     */
    KrajeResult createNpcCountry(String name, int rgb, Location capital, String rulerName, boolean force);

    /** Deletes a country (admin / NPC AI). Fires {@link pl.medivalrpg.kraje.api.event.CountryDisbandEvent}. */
    KrajeResult deleteCountry(UUID countryId);

    /**
     * Claims a chunk for a country. {@code cause} {@link ClaimCause#ADMIN} ignores adjacency, limits and cost;
     * {@link ClaimCause#NPC} obeys adjacency and pays the claim cost from the treasury like a player claim.
     */
    KrajeResult claimChunk(UUID countryId, String world, int cx, int cz, ClaimCause cause);

    KrajeResult unclaimChunk(UUID countryId, String world, int cx, int cz, ClaimCause cause);

    /**
     * Claim for the town planner of Ludnosc ({@link ClaimCause#NPC}: adjacency and cost as usual). For NPC countries
     * Kraje may allow more chunks than the normal claim limit ({@code claims.npc-planner-bonus}); player countries
     * keep the normal rules. Default (older Kraje) = {@link #claimChunk} with {@link ClaimCause#NPC}. Since API 1.4.
     */
    default KrajeResult claimChunkForPlanner(final UUID countryId, final String world, final int cx, final int cz) {
        return this.claimChunk(countryId, world, cx, cz, ClaimCause.NPC);
    }

    /** Directly sets a relation (admin or NPC AI decision). War/peace/alliance announcements are broadcast as usual. */
    KrajeResult setRelation(UUID countryA, UUID countryB, Relation relation);

    /**
     * Answers a pending proposal (alliance or peace) sent to {@code target} by {@code from}.
     * Used by the NPC AI to accept or refuse proposals made by player countries
     * (see {@link pl.medivalrpg.kraje.api.event.RelationProposalEvent}).
     */
    KrajeResult respondToProposal(UUID target, UUID from, Relation proposed, boolean accept);

    /**
     * Sends a proposal from {@code from} to {@code to} (e.g. an NPC country offering peace). For a player
     * country the ruler gets the usual clickable prompt; for an NPC country a {@link pl.medivalrpg.kraje.api.event.RelationProposalEvent} fires.
     */
    KrajeResult propose(UUID from, UUID to, Relation proposed);

    /** Starts a siege of an enemy chunk (both countries must be at war; the chunk must border attacker land or be the attacker's target per config). */
    KrajeResult startSiege(UUID attacker, String world, int cx, int cz);

    /** Renames NPC ruler / sets the NPC ruler's display name. */
    KrajeResult setNpcRulerName(UUID countryId, String rulerName);

    // ---- extensions for the web panel (since API 1.1; default = unsupported) --------------------
    //
    // Mutations run the same rules, costs, announcements and events as the equivalent /kraj commands and must be
    // called on the main thread. They do NOT check who is allowed to call them (rank of the web user, admin):
    // the caller (e.g. the Panel plugin) is responsible for that. Where a rule depends on the acting player
    // (e.g. "cannot kick a member of equal or higher rank") the actor is a parameter.

    /** Description of the country ({@code /kraj opis}); empty when unknown. Thread-safe. */
    default Optional<String> getDescription(final UUID countryId) {
        return Optional.empty();
    }

    /** Player members with cached names, ranks and tax debts; empty when unknown/unsupported. */
    default java.util.List<MemberInfo> members(final UUID countryId) {
        return java.util.List.of();
    }

    /** {@code /kraj kolor}: colour {@code 0xRRGGBB}. */
    default KrajeResult setColor(final UUID countryId, final int rgb) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj opis}: control characters are stripped; empty text clears it; max length from Kraje's config. */
    default KrajeResult setDescription(final UUID countryId, final String text) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj podatek}: daily tax per player citizen in copper (0 = none, max from config). Needs Skarbiec. */
    default KrajeResult setTax(final UUID countryId, final long amount) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj awansuj|degraduj}: {@code rank} must be {@link Rank#ADVISOR} or {@link Rank#CITIZEN}; never the ruler. */
    default KrajeResult setRank(final UUID countryId, final UUID player, final Rank rank) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * {@code /kraj wyrzuc}: removes a member. Never the ruler; when {@code byPlayer} is a member of the country,
     * the target must have a lower rank than {@code byPlayer} (null = admin, only the ruler rule applies).
     */
    default KrajeResult kickMember(final UUID countryId, final UUID player, final UUID byPlayer) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj przekaz}: {@code newRuler} must be a member; the old ruler becomes an advisor. */
    default KrajeResult transferRuler(final UUID countryId, final UUID newRuler) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * {@code /kraj zapros}: invites a player by name (same expiry as in game). The target must not be in a country.
     * An implementation may accept offline players (the invitation is shown when they join) or refuse them.
     */
    default KrajeResult invite(final UUID countryId, final UUID inviterUuid, final String targetName) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj wojna}: cooldown and war cost from the treasury exactly like the command. */
    default KrajeResult declareWar(final UUID from, final UUID to) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj zerwij}: ends an alliance. */
    default KrajeResult breakAlliance(final UUID from, final UUID to) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj wplac ... konto}: the member pays {@code amount} copper from their bank account into the treasury. */
    default KrajeResult treasuryDeposit(final UUID countryId, final UUID playerUuid, final long amount) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj wyplac}: the ruler ({@code playerUuid}) takes {@code amount} copper to their bank account. */
    default KrajeResult treasuryWithdraw(final UUID countryId, final UUID playerUuid, final long amount) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Last treasury operations, newest first (at most {@code limit}). Thread-safe snapshot. */
    default java.util.List<TreasuryLogEntry> treasuryLog(final UUID countryId, final int limit) {
        return java.util.List.of();
    }

    /** Unpaid taxes of a member in copper (0 when none / not a member). */
    default long taxDebt(final UUID countryId, final UUID player) {
        return 0L;
    }

    /**
     * {@code /kraj admin wladca}: makes the named player (online, member, or known to the server) the ruler; a player
     * without a country is added first. An NPC country taken over this way becomes a player country.
     */
    default KrajeResult setRuler(final UUID countryId, final String playerName) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    // ---- capitulation & vassals (since API 1.2; default = unsupported / none) -------------------
    //
    // A country at war may capitulate: on acceptance the war ends (NEUTRAL, no minimum war time), the loser becomes
    // the VASSAL of the winner (its OVERLORD) for some days and pays a daily tribute at the daily settlement.
    // Reads are thread-safe snapshots; mutations run on the main thread with the same rules and announcements as
    // /kraj kapitulacja, /kraj zwolnij and /kraj wykup, and do not check who calls them.

    /** Overlord of {@code countryId} when it is a vassal. Thread-safe. */
    default Optional<UUID> getOverlord(final UUID countryId) {
        return Optional.empty();
    }

    /** Vassals of {@code countryId} (empty when none). Thread-safe. */
    default Set<UUID> getVassals(final UUID countryId) {
        return Set.of();
    }

    /** Terms and state of the vassalage of {@code vassal} (empty when it is not a vassal). Thread-safe. */
    default Optional<VassalInfo> getVassalage(final UUID vassal) {
        return Optional.empty();
    }

    /**
     * {@code /kraj kapitulacja <wróg> [dni]}: {@code loser} offers to capitulate to {@code winner} (both at war; the loser
     * must not be a vassal already). {@code days <= 0} = default length from Kraje's config; otherwise it must lie in the
     * configured range ({@link KrajeResult.Status#INVALID}). An NPC winner gets a
     * {@link pl.medivalrpg.kraje.api.event.CapitulationOfferEvent}; a player winner's ruler gets a clickable prompt.
     */
    default KrajeResult offerCapitulation(final UUID loser, final UUID winner, final int days) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * {@code /kraj kapitulacja przyjmij|odrzuc <kraj>}: {@code winner} answers the pending offer of {@code loser}.
     * {@link KrajeResult.Status#NO_PROPOSAL} without a valid offer, {@link KrajeResult.Status#NOT_AT_WAR} when the war
     * already ended. On acceptance fires {@link pl.medivalrpg.kraje.api.event.VassalageStartEvent}.
     */
    default KrajeResult respondCapitulation(final UUID winner, final UUID loser, final boolean accept) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj zwolnij <wasal>}: the overlord frees its vassal (debt is forgiven). */
    default KrajeResult releaseVassal(final UUID overlord, final UUID vassal) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * {@code /kraj wykup}: the vassal buys its freedom from its treasury: buyout multiplier × remaining tribute
     * (expected daily tribute × remaining days) + unpaid tribute, paid to the overlord's treasury. Needs Skarbiec;
     * {@link KrajeResult.Status#INSUFFICIENT_FUNDS} when the treasury cannot pay.
     */
    default KrajeResult buyout(final UUID vassal) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Current price of {@link #buyout} in copper; -1 when {@code vassal} is not a vassal or buyout is unsupported. */
    default long buyoutCost(final UUID vassal) {
        return -1L;
    }

    /** Main thread. Safe artillery block policy: enemy in war, active siege, siege build exclusions and protected areas. */
    default boolean canSiegeBreak(final UUID attacker, final String world, final int x, final int y, final int z) {
        return false;
    }

    // ---- protected areas (since API 1.3; default = unsupported) ---------------------------------
    //
    // Areas (e.g. the server spawn) where nobody can found a country or claim land. Main thread only; persisted by
    // Kraje. They block /kraj zaloz, /kraj zajmij, claimChunk (any cause except ADMIN) and createNpcCountry
    // (unless force). Already claimed chunks inside an area are not removed.

    /**
     * Adds or replaces (same {@code id}) a protected area. Coordinates are BLOCK coordinates, inclusive; min/max are
     * swapped when given in the wrong order. Every chunk touching the rectangle, extended by {@code bufferChunks}
     * chunks on each side, is protected.
     *
     * @param id           area id, {@code [a-z0-9_-]{1,32}} (shown to players, e.g. {@code spawn})
     * @param world        world name (non-blank)
     * @param bufferChunks extra protected chunks around the rectangle, 0..16
     * @return {@link KrajeResult.Status#OK} or {@link KrajeResult.Status#INVALID} for bad arguments
     */
    default KrajeResult addProtectedArea(final String id, final String world, final int minX, final int minZ,
                                         final int maxX, final int maxZ, final int bufferChunks) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Removes the protected area {@code id}; {@link KrajeResult.Status#NOT_FOUND} when there is none. */
    default KrajeResult removeProtectedArea(final String id) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    // ---- personal unions, embassies, relation quality (since API 1.5; default = unsupported / none) -----------
    //
    // Personal union: two sovereign countries share one ruler (the "monarch", ruler of the HOME country, who also holds
    // the ruler title of the PARTNER). Each keeps its territory, treasury, citizens and laws; the two are allied and
    // cannot go to war with each other. Embassy: a guest country owns one chunk of a host's land (guest members may
    // build there) — other plugins can require "good relations + embassy" before joint projects (roads, railways).
    // Reads are thread-safe snapshots; mutations run on the main thread with the same rules, costs, announcements and
    // events as /kraj unia and /kraj ambasada, and do not check who calls them.

    /** Union {@code countryId} is part of (as home or partner). Thread-safe. */
    default Optional<UnionInfo> getUnion(final UUID countryId) {
        return Optional.empty();
    }

    /** The other country of the union of {@code countryId}. Thread-safe. */
    default Optional<UUID> getUnionPartner(final UUID countryId) {
        return this.getUnion(countryId).map(u -> u.other(countryId));
    }

    /**
     * {@code /kraj unia zaproponuj}: {@code home} (its ruler becomes the shared ruler) proposes a union to {@code partner}.
     * Fires {@link pl.medivalrpg.kraje.api.event.UnionProposalEvent}.
     */
    default KrajeResult proposeUnion(final UUID home, final UUID partner) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /**
     * {@code /kraj unia przyjmij|odrzuc}: {@code partner} answers the pending proposal of {@code home}
     * ({@link KrajeResult.Status#NO_PROPOSAL} without one). On acceptance fires
     * {@link pl.medivalrpg.kraje.api.event.UnionFormedEvent}.
     */
    default KrajeResult respondUnion(final UUID partner, final UUID home, final boolean accept) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Dissolves the union of {@code countryId} at once (admin/AI: no minimum duration, no cost). */
    default KrajeResult dissolveUnion(final UUID countryId) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** Whether {@code guest} has an open (possibly expelled, still running) embassy in {@code host}. Thread-safe. */
    default boolean hasEmbassy(final UUID guest, final UUID host) {
        return false;
    }

    /** Whether either country has an embassy in the other. Thread-safe. */
    default boolean hasEmbassyBetween(final UUID a, final UUID b) {
        return this.hasEmbassy(a, b) || this.hasEmbassy(b, a);
    }

    /** Embassies of {@code countryId} abroad and embassies hosted by it. Thread-safe. */
    default java.util.List<EmbassyInfo> getEmbassies(final UUID countryId) {
        return java.util.List.of();
    }

    /** Embassy standing on chunk ({@code cx}, {@code cz}) of {@code world}. Thread-safe. */
    default Optional<EmbassyInfo> getEmbassyAt(final String world, final int cx, final int cz) {
        return Optional.empty();
    }

    /** How good the relation of two countries is (war … same country). Thread-safe. */
    default RelationQuality getRelationQuality(final UUID countryA, final UUID countryB) {
        if (countryA == null || countryB == null) {
            return RelationQuality.NEUTRAL;
        }
        if (countryA.equals(countryB)) {
            return RelationQuality.SAME;
        }
        return switch (this.getRelation(countryA, countryB)) {
            case WAR -> RelationQuality.WAR;
            case ALLIANCE -> RelationQuality.ALLIANCE;
            case NEUTRAL -> RelationQuality.NEUTRAL;
        };
    }

    /**
     * May {@code from} start a joint project (e.g. build a road or railway) reaching into {@code into}? Same country and
     * union partners: yes; otherwise the relation must be good enough and an embassy must exist (Kraje's config decides
     * the exact rule). Thread-safe.
     */
    default boolean canStartJointProject(final UUID from, final UUID into) {
        if (from == null || into == null) {
            return false;
        }
        final RelationQuality q = this.getRelationQuality(from, into);
        return q.atLeast(RelationQuality.UNION) || (q.peaceful() && this.hasEmbassyBetween(from, into));
    }

    /**
     * Trade bonus between two countries as a fraction (e.g. 0.05 = +5 %) granted by embassies and unions (config of Kraje).
     * A hook only: Kraje applies it nowhere itself; shops/markets may use it. 0 when none. Thread-safe.
     */
    default double getTradeBonus(final UUID countryA, final UUID countryB) {
        return 0.0;
    }

    /**
     * {@code /kraj ambasada otworz}: {@code guest} asks the country owning the chunk at {@code spot} for an embassy there
     * ({@code spot} becomes the diplomat spawn point). Fires {@link pl.medivalrpg.kraje.api.event.EmbassyRequestEvent}.
     */
    default KrajeResult requestEmbassy(final UUID guest, final Location spot) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj ambasada przyjmij|odrzuc}: {@code host} answers the pending request of {@code guest}. */
    default KrajeResult respondEmbassy(final UUID host, final UUID guest, final boolean accept) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj ambasada zamknij}: the guest closes its embassy in {@code host} at once. */
    default KrajeResult closeEmbassy(final UUID guest, final UUID host) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }

    /** {@code /kraj ambasada wydal}: the host expels the embassy of {@code guest} (closes after the notice period). */
    default KrajeResult expelEmbassy(final UUID host, final UUID guest) {
        return KrajeResult.fail(KrajeResult.Status.DENIED, "nieobsługiwane");
    }
}
