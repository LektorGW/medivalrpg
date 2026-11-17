// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.KrajeResult.Status;
import pl.medivalrpg.kraje.api.event.RelationProposalEvent;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.geometry.ChunkGraph;
import pl.medivalrpg.kraje.manager.RequestManager.ProposalType;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.ProtectedArea;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.model.StoredInvite;
import pl.medivalrpg.kraje.model.Vassalage;
import pl.medivalrpg.kraje.util.Format;

/**
 * Game rules shared by player commands and the public API: founding, claims, disbanding and diplomacy with
 * their costs, limits and announcements. Rule violations throw {@link KrajeException} (message key +
 * {@link Status} for the API). Main thread only.
 */
public final class KrajeActions {

    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N}_-]+$");

    /** Result of a proposal: sent, or the other side had already proposed and the relation was set. */
    public enum Outcome { PROPOSED, FORMED }

    private final KrajePlugin plugin;

    public KrajeActions(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private CountryManager manager() {
        return this.plugin.manager();
    }

    private KrajeConfig cfg() {
        return this.plugin.cfg();
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    // ------------------------------------------------------------------ names & colours

    public void validateName(final String name) {
        final KrajeConfig cfg = this.cfg();
        if (name == null || name.length() < cfg.nameMinLength || name.length() > cfg.nameMaxLength) {
            throw new KrajeException(Status.INVALID, "name-length",
                Notify.unparsed("min", cfg.nameMinLength), Notify.unparsed("max", cfg.nameMaxLength));
        }
        if (!NAME.matcher(name).matches()) {
            throw new KrajeException(Status.INVALID, "name-invalid");
        }
        if (this.manager().isNameTaken(name)) {
            throw new KrajeException(Status.NAME_TAKEN, "name-taken", Notify.unparsed("name", name));
        }
    }

    public int randomColor() {
        final List<Integer> palette = this.cfg().defaultColors;
        if (!palette.isEmpty()) {
            final Set<Integer> used = new HashSet<>();
            this.manager().countries().forEach(c -> used.add(c.color()));
            final List<Integer> free = palette.stream().filter(c -> !used.contains(c)).toList();
            final List<Integer> pool = free.isEmpty() ? palette : free;
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
        return java.awt.Color.HSBtoRGB(ThreadLocalRandom.current().nextFloat(), 0.65f, 0.9f) & 0xFFFFFF;
    }

    // ------------------------------------------------------------------ founding

    /** {@code /kraj zaloz}: all checks, founding fee from the player (account, then coins), creation. */
    public Country found(final Player player, final String name) {
        if (this.manager().countryOf(player.getUniqueId()) != null) {
            throw new KrajeException(Status.INVALID, "already-in-country");
        }
        this.validateName(name);
        final ChunkPos pos = ChunkPos.of(player.getLocation());
        this.checkFoundingSpot(pos, false);
        this.plugin.treasury().chargePlayer(player, this.cfg().foundCost, "założenie kraju " + name);
        final Country c = this.manager().create(player.getUniqueId(), player.getName(), name, this.randomColor(),
            pos, SpawnPoint.of(player.getLocation()), ClaimCause.PLAYER);
        this.plugin.requests().clearInvites(player.getUniqueId());
        return c;
    }

    private void checkFoundingSpot(final ChunkPos pos, final boolean force) {
        if (!force && this.cfg().isWorldBlocked(pos.world())) {
            throw new KrajeException(Status.INVALID, "world-blocked");
        }
        final Country owner = this.manager().countryAt(pos);
        if (owner != null) {
            throw new KrajeException(Status.ALREADY_CLAIMED, "chunk-claimed", Notify.country(owner));
        }
        if (!force) {
            this.requireNotProtected(pos);
        }
        final int distance = this.cfg().foundMinDistanceChunks;
        if (!force && distance > 0 && this.manager().hasForeignClaimWithin(pos.world(), pos.x(), pos.z(), distance, null)) {
            throw new KrajeException(Status.TOO_CLOSE, "too-close", Notify.unparsed("distance", distance));
        }
    }

    /**
     * Protected areas (e.g. the server spawn, plus their chunk buffer) are neutral land: no founding, no claims
     * except by an admin. The founding check uses the capital chunk only; the buffer is the safety margin.
     */
    public void requireNotProtected(final ChunkPos pos) {
        final ProtectedArea area = this.plugin.protectedAreas().at(pos.world(), pos.x(), pos.z()).orElse(null);
        if (area != null) {
            throw new KrajeException(Status.DENIED, "protected-area", Notify.unparsed("area", area.id()));
        }
    }

    /** {@code KrajeApi#createNpcCountry}: same rules as founding unless {@code force}; no cost. */
    public Country createNpc(final String name, final int rgb, final Location capital, final String rulerName, final boolean force) {
        if (capital == null || capital.getWorld() == null) {
            throw new KrajeException(Status.INVALID, "api-invalid-location");
        }
        if (force) {
            if (name == null || name.isBlank() || name.contains(" ")) {
                throw new KrajeException(Status.INVALID, "name-invalid");
            }
            if (this.manager().isNameTaken(name)) {
                throw new KrajeException(Status.NAME_TAKEN, "name-taken", Notify.unparsed("name", name));
            }
        } else {
            this.validateName(name);
        }
        final ChunkPos pos = ChunkPos.of(capital);
        this.checkFoundingSpot(pos, force);
        final String ruler = rulerName == null || rulerName.isBlank() ? "Władca" : rulerName.replaceAll("[\\p{Cntrl}§]", "").trim();
        final Country c = this.manager().createNpc(name, rgb & 0xFFFFFF, pos, SpawnPoint.of(capital), ruler,
            force ? ClaimCause.ADMIN : ClaimCause.NPC);
        Notify.broadcast(this.messages(), "npc-founded-broadcast", Notify.country(c), Notify.unparsed("ruler", ruler));
        return c;
    }

    // ------------------------------------------------------------------ territory

    public KrajeConfig.ClaimLimit limitOf(final Country c) {
        // The titular monarch of a personal union does not count twice.
        return this.cfg().claimLimitFor(c.realMemberCount(), this.plugin.integrations().population(c.id()).orElse(0));
    }

    /** Placeholders explaining a claim limit: {@code <limit> <count> <base> <per_member> <members> <population> <cap>}. */
    public TagResolver limitResolvers(final Country c) {
        final KrajeConfig.ClaimLimit limit = this.limitOf(c);
        return TagResolver.resolver(
            Notify.unparsed("limit", limit.total()),
            Notify.unparsed("count", c.claimCount()),
            Notify.unparsed("base", limit.base()),
            Notify.unparsed("per_member", limit.perMember()),
            Notify.unparsed("members", limit.members()),
            Notify.unparsed("population", limit.fromPopulation()),
            Placeholder.parsed("cap", limit.max() > 0
                ? this.messages().raw(limit.capped() ? "claim-limit-cap-hit" : "claim-limit-cap").replace("<max>", Integer.toString(limit.max()))
                : ""));
    }

    /**
     * Claims a chunk. {@link ClaimCause#ADMIN} only requires the chunk to be free; PLAYER/NPC obey blocked worlds,
     * the claim limit, adjacency and pay the claim cost from the treasury. Returns the cost paid.
     */
    public long claim(final Country c, final ChunkPos pos, final ClaimCause cause, final String actor) {
        return this.claim(c, pos, cause, actor, false);
    }

    /**
     * {@code planner}: a claim of the Ludnosc town planner — an NPC country may then exceed its claim limit by
     * {@code claims.npc-planner-bonus} ({@link KrajeConfig#npcPlannerLimit}); player countries keep the normal limit.
     */
    public long claim(final Country c, final ChunkPos pos, final ClaimCause cause, final String actor, final boolean planner) {
        final Country owner = this.manager().countryAt(pos);
        if (owner == c) {
            throw new KrajeException(Status.ALREADY_CLAIMED, "chunk-already-own");
        }
        if (owner != null) {
            throw new KrajeException(Status.ALREADY_CLAIMED, "chunk-claimed", Notify.country(owner));
        }
        long cost = 0;
        if (cause != ClaimCause.ADMIN) {
            if (this.cfg().isWorldBlocked(pos.world())) {
                throw new KrajeException(Status.INVALID, "world-blocked");
            }
            this.requireNotProtected(pos);
            final KrajeConfig.ClaimLimit limit = this.limitOf(c);
            final int max = planner && c.npc() ? this.cfg().npcPlannerLimit(limit) : limit.total();
            if (c.claimCount() >= max) {
                throw new KrajeException(Status.LIMIT_REACHED, "claim-limit-full", this.limitResolvers(c));
            }
            if (!ChunkGraph.isAdjacent(c.claimsIn(pos.world()), pos.key())) {
                throw new KrajeException(Status.NOT_ADJACENT, "claim-not-adjacent");
            }
            cost = this.plugin.treasury().claimCost(c);
            this.plugin.treasury().charge(c, cost, "claim", actor);
        }
        this.manager().claim(c, pos.world(), pos.key(), cause);
        return cost;
    }

    /** Unclaims a chunk of {@code c}. Never the capital; PLAYER/NPC additionally keep the territory connected. */
    public void unclaim(final Country c, final ChunkPos pos, final ClaimCause cause) {
        if (this.manager().countryAt(pos) != c) {
            throw new KrajeException(Status.NOT_FOUND, "chunk-not-own");
        }
        if (pos.equals(c.capital())) {
            throw new KrajeException(Status.DENIED, "cannot-unclaim-capital");
        }
        if (cause != ClaimCause.ADMIN) {
            if (!this.staysConnected(c, pos)) {
                throw new KrajeException(Status.DENIED, "unclaim-disconnects");
            }
            if (this.plugin.sieges().siegeAt(pos) != null) {
                throw new KrajeException(Status.DENIED, "unclaim-sieged");
            }
            if (this.manager().embassyAt(pos.world(), pos.key()) != null) {
                throw new KrajeException(Status.DENIED, "unclaim-embassy");
            }
        }
        this.manager().unclaim(c, pos.world(), pos.key(), cause);
    }

    public boolean staysConnected(final Country c, final ChunkPos removed) {
        final Long capitalKey = c.capital().world().equals(removed.world()) ? c.capital().key() : null;
        return ChunkGraph.staysConnected(c.claimsIn(removed.world()), removed.key(), capitalKey);
    }

    // ------------------------------------------------------------------ disband

    /**
     * Removes a country with everything attached (treasury, vassalages, sieges, requests, chat mode). Without a war
     * the treasury is settled per {@code disband.treasury}; a conquered country's treasury was already looted and
     * settled by the siege ({@link ClaimCause#CONQUEST}).
     */
    public void disband(final Country c, final ClaimCause cause) {
        if (this.manager().get(c.id()) == null) {
            return;
        }
        if (cause != ClaimCause.CONQUEST) {
            this.plugin.treasury().settleDisband(c);
        }
        this.plugin.vassals().onDisband(c, cause);
        this.plugin.unions().onDisband(c, cause);
        this.plugin.embassies().onDisband(c);
        for (final Member m : c.members()) {
            if (c.isTitular(m.uuid())) {
                continue; // the union monarch keeps his home country's chat mode
            }
            this.plugin.chat().disableCountryChat(m.uuid());
        }
        this.plugin.sieges().endAllFor(c.id());
        this.plugin.requests().forgetCountry(c.id());
        this.manager().disband(c, cause);
    }

    // ------------------------------------------------------------------ admin

    /**
     * {@code /kraj admin wladca} and {@code KrajeApi#setRuler}: makes the named player the ruler (online player,
     * member, or a player known to the server). A player without a country is added first. Announces the change to
     * the members. Returns the resolved name.
     */
    public String setRuler(final Country c, final String name) {
        if (name == null || name.isBlank()) {
            throw new KrajeException(Status.INVALID, "player-not-found", Notify.unparsed("player", String.valueOf(name)));
        }
        UUID uuid = null;
        String resolvedName = name;
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            uuid = online.getUniqueId();
            resolvedName = online.getName();
        } else {
            for (final Member m : c.members()) {
                if (m.name().equalsIgnoreCase(name)) {
                    uuid = m.uuid();
                    resolvedName = m.name();
                }
            }
            if (uuid == null) {
                final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
                if (cached != null) {
                    uuid = cached.getUniqueId();
                    resolvedName = cached.getName() == null ? name : cached.getName();
                }
            }
        }
        if (uuid == null) {
            throw new KrajeException(Status.NOT_FOUND, "player-not-found", Notify.unparsed("player", name));
        }
        final Country current = this.manager().countryOf(uuid);
        if (current != null && current != c) {
            throw new KrajeException(Status.DENIED, "admin-target-in-other-country", Notify.unparsed("player", resolvedName),
                Notify.country(current));
        }
        if (current == null) {
            this.manager().addMember(c, uuid, resolvedName, Rank.CITIZEN);
            this.plugin.requests().clearInvites(uuid);
        }
        this.manager().transferRule(c, uuid);
        Notify.members(this.messages(), c, "ruler-changed", Notify.country(c), Notify.unparsed("player", resolvedName));
        return resolvedName;
    }

    // ------------------------------------------------------------------ diplomacy

    public Outcome proposeAlliance(final Country from, final Country to) {
        final Relation rel = from.relationTo(to.id());
        if (rel == Relation.ALLIANCE) {
            throw new KrajeException(Status.INVALID, "already-allied", Notify.country(to));
        }
        if (rel == Relation.WAR) {
            throw new KrajeException(Status.DENIED, "alliance-at-war", Notify.country(to));
        }
        this.plugin.vassals().requireAllianceAllowed(from, to);
        if (this.plugin.requests().hasProposal(ProposalType.ALLIANCE, to.id(), from.id(), true)) {
            this.applyRelation(from, to, Relation.ALLIANCE);
            return Outcome.FORMED;
        }
        this.plugin.requests().propose(ProposalType.ALLIANCE, from.id(), to.id(), this.cfg().proposalExpiryMillis);
        Notify.leaders(this.messages(), to, "alliance-proposal-received", Notify.country(from),
            Placeholder.styling("accept", ClickEvent.runCommand("/kraj sojusz " + from.name())));
        Bukkit.getPluginManager().callEvent(new RelationProposalEvent(from.id(), to.id(), Relation.ALLIANCE));
        return Outcome.PROPOSED;
    }

    public Outcome proposePeace(final Country from, final Country to) {
        if (from.relationTo(to.id()) != Relation.WAR) {
            throw new KrajeException(Status.NOT_AT_WAR, "not-at-war", Notify.country(to));
        }
        final long remaining = this.peaceAvailableIn(from, to);
        if (remaining > 0) {
            throw new KrajeException(Status.DENIED, "peace-too-early", Notify.country(to),
                Notify.unparsed("time", Format.duration(remaining)));
        }
        if (this.plugin.requests().hasProposal(ProposalType.PEACE, to.id(), from.id(), true)) {
            this.applyRelation(from, to, Relation.NEUTRAL);
            return Outcome.FORMED;
        }
        this.plugin.requests().propose(ProposalType.PEACE, from.id(), to.id(), this.cfg().proposalExpiryMillis);
        Notify.leaders(this.messages(), to, "peace-proposal-received", Notify.country(from),
            Placeholder.styling("accept", ClickEvent.runCommand("/kraj pokoj " + from.name())));
        Bukkit.getPluginManager().callEvent(new RelationProposalEvent(from.id(), to.id(), Relation.NEUTRAL));
        return Outcome.PROPOSED;
    }

    /** Millis until peace may be made (minimum war duration), 0 if already possible. */
    public long peaceAvailableIn(final Country a, final Country b) {
        final Long since = a.warSinceMap().get(b.id());
        if (since == null) {
            return 0;
        }
        return Math.max(0, since + this.cfg().warMinDurationMillis - System.currentTimeMillis());
    }

    /** Millis until {@code a} may declare war on {@code b} again, 0 if possible. */
    public long warAvailableIn(final Country a, final Country b) {
        final Long ended = a.warEndedAtMap().get(b.id());
        if (ended == null) {
            return 0;
        }
        return Math.max(0, ended + this.cfg().warRedeclareCooldownMillis - System.currentTimeMillis());
    }

    /**
     * {@code /kraj wojna}: vassal rules, cooldown, cost from the treasury, then war. An overlord punishing a vassal
     * that owes tribute for {@code vassal.debt-war-days} pays nothing and ignores the cooldown. Returns the cost paid.
     */
    public long declareWar(final Country from, final Country to, final String actor) {
        if (from.relationTo(to.id()) == Relation.WAR) {
            throw new KrajeException(Status.INVALID, "already-at-war", Notify.country(to));
        }
        this.plugin.unions().requireWarAllowed(from, to);
        if (this.plugin.vassals().requireWarAllowed(from, to) == pl.medivalrpg.kraje.vassal.VassalRules.Verdict.ALLOWED_FREE) {
            final Vassalage v = to.vassalage();
            Notify.broadcast(this.messages(), "debt-war-declared", Notify.pair(from, to),
                Notify.unparsed("debt", this.plugin.treasury().format(v == null ? 0 : v.debt())),
                Notify.unparsed("days", v == null ? 0 : v.debtDays()));
            this.applyRelation(from, to, Relation.WAR);
            return 0;
        }
        final long cooldown = this.warAvailableIn(from, to);
        if (cooldown > 0) {
            throw new KrajeException(Status.DENIED, "war-cooldown", Notify.country(to),
                Notify.unparsed("time", Format.duration(cooldown)));
        }
        final long cost = this.plugin.treasury().active() ? this.cfg().warDeclareCost : 0;
        this.plugin.treasury().charge(from, cost, "war", actor);
        this.applyRelation(from, to, Relation.WAR);
        return cost;
    }

    public void breakAlliance(final Country from, final Country to) {
        if (from.relationTo(to.id()) != Relation.ALLIANCE) {
            throw new KrajeException(Status.INVALID, "not-allied", Notify.country(to));
        }
        this.plugin.unions().requireAllianceBreakAllowed(from, to);
        this.applyRelation(from, to, Relation.NEUTRAL);
    }

    /**
     * Sets the relation directly (no costs, no minimum war time) with the usual announcements; used by the
     * accepted proposals above, the admin and the API ({@code setRelation}). Ending a war stops its sieges.
     */
    public void applyRelation(final Country a, final Country b, final Relation relation) {
        this.applyRelation(a, b, relation, true);
    }

    /** Relation change without the usual broadcast (a personal union forms / ends with its own announcement). */
    public void applyRelationQuiet(final Country a, final Country b, final Relation relation) {
        this.applyRelation(a, b, relation, false);
    }

    /** Capitulation accepted: the war ends without the usual "peace" announcement (the capitulation is announced). */
    public void endWarSilently(final Country a, final Country b) {
        this.applyRelation(a, b, Relation.NEUTRAL, false);
    }

    private void applyRelation(final Country a, final Country b, final Relation relation, final boolean announce) {
        final Relation old = a.relationTo(b.id());
        if (old == relation) {
            return;
        }
        this.manager().setRelation(a, b, relation);
        this.plugin.requests().clearProposalsBetween(a.id(), b.id());
        if (old == Relation.WAR) {
            this.plugin.sieges().endBetween(a.id(), b.id());
        }
        final Messages m = this.messages();
        if (announce) {
            if (relation == Relation.WAR) {
                Notify.broadcast(m, "war-declared", Notify.pair(a, b));
            } else if (relation == Relation.ALLIANCE) {
                Notify.broadcast(m, "alliance-formed", Notify.pair(a, b));
            } else if (old == Relation.WAR) {
                Notify.broadcast(m, "peace-made", Notify.pair(a, b));
            } else {
                Notify.members(m, a, "alliance-broken", Notify.country(b));
                Notify.members(m, b, "alliance-broken", Notify.country(a));
            }
        }
        if (relation == Relation.WAR) {
            // A war between overlord and vassal ends the vassalage; vassals of both sides are told (or join).
            this.plugin.vassals().onWar(a, b);
            // Embassies between the two close; a war between union partners ends the union, otherwise partners join.
            this.plugin.embassies().onWar(a, b);
            if (this.manager().get(a.id()) != null && this.manager().get(b.id()) != null) {
                this.plugin.unions().onWar(a, b);
            }
        }
    }

    /** {@code KrajeApi#respondToProposal}: {@code target} answers a pending proposal from {@code from}. */
    public KrajeResult respond(final Country target, final Country from, final Relation proposed, final boolean accept) {
        final ProposalType type = proposed == Relation.ALLIANCE ? ProposalType.ALLIANCE
            : proposed == Relation.NEUTRAL ? ProposalType.PEACE : null;
        if (type == null) {
            return KrajeResult.fail(Status.INVALID, "Można odpowiadać tylko na sojusz (ALLIANCE) albo pokój (NEUTRAL).");
        }
        if (!this.plugin.requests().hasProposal(type, from.id(), target.id(), true)) {
            return KrajeResult.fail(Status.NO_PROPOSAL, "Brak ważnej propozycji od " + from.name() + ".");
        }
        if (!accept) {
            Notify.leaders(this.messages(), from, type == ProposalType.ALLIANCE ? "alliance-refused" : "peace-refused",
                Notify.country(target));
            return KrajeResult.ok(target.id());
        }
        final Relation current = target.relationTo(from.id());
        if (type == ProposalType.ALLIANCE && current != Relation.NEUTRAL) {
            return KrajeResult.fail(current == Relation.WAR ? Status.DENIED : Status.INVALID, "Sojusz niemożliwy przy relacji " + current.displayName() + ".");
        }
        if (type == ProposalType.PEACE && current != Relation.WAR) {
            return KrajeResult.fail(Status.NOT_AT_WAR, "Te kraje nie są w stanie wojny.");
        }
        if (type == ProposalType.ALLIANCE) {
            this.plugin.vassals().requireAllianceAllowed(target, from);
        }
        this.applyRelation(target, from, proposed);
        return KrajeResult.ok(target.id());
    }

    // ------------------------------------------------------------------ citizens of a fallen country

    /**
     * Country {@code fallen} was conquered by {@code winner}: each former player member gets a persistent invitation
     * to the winner ({@code conquest.citizen-invite-hours}, works offline, shown on join). Not for NPC winners that
     * players may not join. Returns how many players were invited.
     */
    public int inviteFormerCitizens(final Country winner, final Country fallen, final List<Member> former) {
        final KrajeConfig cfg = this.cfg();
        if (!cfg.conquestInvites || former.isEmpty() || (winner.npc() && !cfg.npcPlayersCanJoin)) {
            return 0;
        }
        final long now = System.currentTimeMillis();
        final long until = now + cfg.conquestInviteMillis;
        int count = 0;
        for (final Member m : former) {
            if (this.manager().countryOf(m.uuid()) != null) {
                continue;
            }
            this.manager().addStoredInvite(new StoredInvite(m.uuid(), m.name(), winner.id(), now, until, StoredInvite.CONQUEST));
            count++;
            final Player online = Bukkit.getPlayer(m.uuid());
            if (online != null) {
                this.showStoredInvite(online, winner, until);
            }
        }
        if (count > 0) {
            final Member ruler = winner.rulerMember();
            final Player rulerOnline = ruler == null ? null : Bukkit.getPlayer(ruler.uuid());
            if (rulerOnline != null) {
                this.messages().send(rulerOnline, "conquest-invites-sent", Notify.pair(winner, fallen),
                    Notify.unparsed("count", count), Notify.unparsed("hours", cfg.conquestInviteMillis / 3_600_000L));
            }
            this.plugin.getLogger().info("Upadek " + fallen.name() + ": zaproszono " + count + " graczy do " + winner.name());
        }
        return count;
    }

    /** Clickable "[Dołącz do X] [Odrzuć]" of a persistent invitation. */
    public void showStoredInvite(final Player player, final Country country, final long until) {
        this.messages().send(player, "conquest-invite", Notify.country(country),
            Notify.unparsed("until", Format.dateTime(until)),
            Placeholder.styling("join", ClickEvent.runCommand("/kraj dolacz " + country.name())),
            Placeholder.styling("refuse", ClickEvent.runCommand("/kraj odrzuc " + country.name())));
    }

    /** On join: every valid persistent invitation of the player. */
    public void showStoredInvites(final Player player) {
        if (this.manager().countryOf(player.getUniqueId()) != null) {
            return;
        }
        for (final StoredInvite invite : this.manager().storedInvitesOf(player.getUniqueId())) {
            final Country c = this.manager().get(invite.country());
            if (c != null) {
                this.showStoredInvite(player, c, invite.expiresAt());
            }
        }
    }
}
