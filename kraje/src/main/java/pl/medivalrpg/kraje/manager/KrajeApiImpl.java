// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.KrajeResult.Status;
import pl.medivalrpg.kraje.api.MemberInfo;
import pl.medivalrpg.kraje.api.SiegeInfo;
import pl.medivalrpg.kraje.api.TreasuryLogEntry;
import pl.medivalrpg.kraje.api.VassalInfo;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.ProtectedArea;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.TreasuryEntry;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * {@link KrajeApi}: reads use the manager's thread-safe indexes and snapshots; mutations run the same rules,
 * costs, announcements and events as the player commands ({@link KrajeActions}, siege manager) and must be called
 * on the main thread (otherwise they fail with {@link Status#INVALID} without changing anything).
 */
public final class KrajeApiImpl implements KrajeApi {

    private final KrajePlugin plugin;

    public KrajeApiImpl(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private CountryManager manager() {
        return this.plugin.manager();
    }

    @Override
    public boolean canSiegeBreak(final UUID attacker, final String worldName, final int x, final int y, final int z) {
        if (!Bukkit.isPrimaryThread() || attacker==null || worldName==null) return false;
        final var world=Bukkit.getWorld(worldName);
        if (world==null || y<world.getMinHeight() || y>=world.getMaxHeight() || !world.isChunkLoaded(x>>4,z>>4)
            || this.plugin.protectedAreas().isProtected(worldName,x>>4,z>>4)) return false;
        final UUID owner=this.manager().ownerAt(worldName,ChunkKeys.key(x>>4,z>>4));
        if (owner==null || owner.equals(attacker) || this.manager().relation(attacker,owner)!=Relation.WAR) return false;
        final var block=world.getBlockAt(x,y,z);
        if (block.getType().getHardness()<0 || block.getState(false) instanceof org.bukkit.block.Container) return false;
        return this.plugin.sieges().allowsCountryAction(attacker,block,
            pl.medivalrpg.kraje.protection.ProtectionService.Flag.BLOCK_BREAK);
    }

    // ------------------------------------------------------------------ read

    @Override
    public Optional<KrajInfo> getCountryAt(final Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.manager().info(this.manager().ownerAt(location)));
    }

    @Override
    public Optional<KrajInfo> getCountryAt(final String world, final int cx, final int cz) {
        if (world == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.manager().info(this.manager().ownerAt(world, ChunkKeys.key(cx, cz))));
    }

    @Override
    public Optional<KrajInfo> getCountryOf(final UUID player) {
        return Optional.ofNullable(this.manager().infoOf(player));
    }

    @Override
    public Optional<KrajInfo> getCountry(final UUID countryId) {
        return Optional.ofNullable(this.manager().info(countryId));
    }

    @Override
    public Optional<KrajInfo> getCountryByName(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        return this.manager().infos().stream().filter(k -> k.name().equalsIgnoreCase(name)).findFirst();
    }

    @Override
    public Collection<KrajInfo> getCountries() {
        return List.copyOf(this.manager().infos());
    }

    /** Keys in the API layout {@code ((long) cx << 32) | (cz & 0xFFFFFFFFL)} (Kraje's internal layout differs). */
    @Override
    public Set<Long> getClaims(final UUID countryId, final String world) {
        if (countryId == null || world == null) {
            return Set.of();
        }
        return this.manager().claimKeysOf(countryId, world).stream()
            .map(key -> ((long) ChunkKeys.x(key) << 32) | (ChunkKeys.z(key) & 0xFFFFFFFFL))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<Rank> getRank(final UUID player) {
        final KrajInfo info = this.manager().infoOf(player);
        return info == null ? Optional.empty() : Optional.ofNullable(info.rankOf(player));
    }

    @Override
    public Relation getRelation(final UUID countryA, final UUID countryB) {
        final KrajInfo a = this.manager().info(countryA);
        if (a == null || countryB == null || countryA.equals(countryB)) {
            return Relation.NEUTRAL;
        }
        if (a.allies().contains(countryB)) {
            return Relation.ALLIANCE;
        }
        if (a.enemies().contains(countryB)) {
            return Relation.WAR;
        }
        return Relation.NEUTRAL;
    }

    @Override
    public Collection<SiegeInfo> getSieges() {
        return this.plugin.sieges().infos();
    }

    @Override
    public Optional<SiegeInfo> getSiege(final String world, final int cx, final int cz) {
        return this.plugin.sieges().infos().stream()
            .filter(s -> s.world().equals(world) && s.cx() == cx && s.cz() == cz).findFirst();
    }

    // ------------------------------------------------------------------ mutations

    private KrajeResult run(final Supplier<KrajeResult> action) {
        if (!Bukkit.isPrimaryThread()) {
            this.plugin.getLogger().warning("KrajeApi: metoda zmieniająca dane wywołana poza głównym wątkiem — odrzucono.");
            return KrajeResult.fail(Status.INVALID, "Wywołaj na głównym wątku serwera.");
        }
        try {
            return action.get();
        } catch (final KrajeException e) {
            return KrajeResult.fail(e.status(), this.plain(e));
        }
    }

    private String plain(final KrajeException e) {
        final String text = PlainTextComponentSerializer.plainText()
            .serialize(this.plugin.messages().get(e.messageKey(), e.resolvers()));
        final String prefix = PlainTextComponentSerializer.plainText().serialize(this.plugin.messages().get("prefix"));
        return text.startsWith(prefix) ? text.substring(prefix.length()).trim() : text.trim();
    }

    private Country require(final UUID id) {
        final Country c = this.manager().get(id);
        if (c == null) {
            throw new KrajeException(Status.NOT_FOUND, "api-country-not-found");
        }
        return c;
    }

    @Override
    public KrajeResult createNpcCountry(final String name, final int rgb, final Location capital, final String rulerName, final boolean force) {
        return this.run(() -> KrajeResult.ok(this.plugin.actions().createNpc(name, rgb, capital, rulerName, force).id()));
    }

    @Override
    public KrajeResult deleteCountry(final UUID countryId) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            Notify.members(this.plugin.messages(), c, "admin-deleted-members", Notify.country(c));
            this.plugin.actions().disband(c, ClaimCause.ADMIN);
            return KrajeResult.ok(c.id());
        });
    }

    private static ClaimCause claimCause(final ClaimCause cause) {
        if (cause == ClaimCause.CONQUEST || cause == ClaimCause.BANKRUPTCY) {
            throw new KrajeException(Status.INVALID, "api-invalid-cause");
        }
        return cause == null ? ClaimCause.NPC : cause;
    }

    @Override
    public KrajeResult claimChunk(final UUID countryId, final String world, final int cx, final int cz, final ClaimCause cause) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            if (world == null || Bukkit.getWorld(world) == null) {
                throw new KrajeException(Status.INVALID, "api-invalid-location");
            }
            this.plugin.actions().claim(c, new ChunkPos(world, cx, cz), claimCause(cause), c.rulerName());
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult claimChunkForPlanner(final UUID countryId, final String world, final int cx, final int cz) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            if (world == null || Bukkit.getWorld(world) == null) {
                throw new KrajeException(Status.INVALID, "api-invalid-location");
            }
            this.plugin.actions().claim(c, new ChunkPos(world, cx, cz), ClaimCause.NPC, c.rulerName(), true);
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult unclaimChunk(final UUID countryId, final String world, final int cx, final int cz, final ClaimCause cause) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            this.plugin.actions().unclaim(c, new ChunkPos(world, cx, cz), claimCause(cause));
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult setRelation(final UUID countryA, final UUID countryB, final Relation relation) {
        return this.run(() -> {
            final Country a = this.require(countryA);
            final Country b = this.require(countryB);
            if (a == b || relation == null) {
                throw new KrajeException(Status.INVALID, "cannot-target-own-country");
            }
            this.plugin.actions().applyRelation(a, b, relation);
            return KrajeResult.ok(a.id());
        });
    }

    @Override
    public KrajeResult respondToProposal(final UUID target, final UUID from, final Relation proposed, final boolean accept) {
        return this.run(() -> this.plugin.actions().respond(this.require(target), this.require(from), proposed, accept));
    }

    @Override
    public KrajeResult propose(final UUID from, final UUID to, final Relation proposed) {
        return this.run(() -> {
            final Country a = this.require(from);
            final Country b = this.require(to);
            if (a == b) {
                throw new KrajeException(Status.INVALID, "cannot-target-own-country");
            }
            if (proposed == Relation.ALLIANCE) {
                this.plugin.actions().proposeAlliance(a, b);
            } else if (proposed == Relation.NEUTRAL) {
                this.plugin.actions().proposePeace(a, b);
            } else {
                throw new KrajeException(Status.INVALID, "api-invalid-proposal");
            }
            return KrajeResult.ok(a.id());
        });
    }

    @Override
    public KrajeResult startSiege(final UUID attacker, final String world, final int cx, final int cz) {
        return this.run(() -> {
            final Country c = this.require(attacker);
            final ChunkPos pos = new ChunkPos(world, cx, cz);
            // Wave 13 for API callers (NPC AI): a chunk that does not border the attacker's land is a naval landing —
            // Kraje still checks the landing rules, coast, bridgehead limit and the LandingSupport transport proof.
            if (pl.medivalrpg.kraje.siege.SiegeMode.landing(
                    pl.medivalrpg.kraje.geometry.ChunkGraph.isAdjacent(c.claimsIn(world), pos.key()),
                    this.plugin.cfg().landingEnabled, this.plugin.cfg().siegeRequireAdjacent)) {
                this.plugin.sieges().startLanding(c, pos, null);
            } else {
                this.plugin.sieges().start(c, pos, null);
            }
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult setNpcRulerName(final UUID countryId, final String rulerName) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            if (!c.npc()) {
                throw new KrajeException(Status.INVALID, "api-not-npc");
            }
            if (rulerName == null || rulerName.isBlank()) {
                throw new KrajeException(Status.INVALID, "api-invalid-name");
            }
            this.manager().setNpcRulerName(c, rulerName.replaceAll("[\\p{Cntrl}§]", "").trim());
            return KrajeResult.ok(c.id());
        });
    }

    // ------------------------------------------------------------------ web panel extensions

    @Override
    public Optional<String> getDescription(final UUID countryId) {
        final Country c = this.manager().get(countryId);
        return c == null ? Optional.empty() : Optional.ofNullable(c.description());
    }

    @Override
    public List<MemberInfo> members(final UUID countryId) {
        final Country c = this.manager().get(countryId);
        if (c == null) {
            return List.of();
        }
        final List<MemberInfo> out = new ArrayList<>();
        for (final Member m : List.copyOf(c.members())) {
            out.add(new MemberInfo(m.uuid(), m.name(), m.rank(), m.joinedAt(), m.taxDebt(), m.debtDays()));
        }
        return List.copyOf(out);
    }

    @Override
    public KrajeResult setColor(final UUID countryId, final int rgb) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            this.manager().setColor(c, rgb & 0xFFFFFF);
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult setDescription(final UUID countryId, final String text) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            String clean = text == null ? "" : text.replaceAll("[\\p{Cntrl}§]", "").trim();
            if (clean.equals("-")) {
                clean = "";
            }
            if (clean.length() > this.plugin.cfg().descriptionMaxLength) {
                throw new KrajeException(Status.INVALID, "description-too-long", Notify.unparsed("max", this.plugin.cfg().descriptionMaxLength));
            }
            this.manager().setDescription(c, clean);
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult setTax(final UUID countryId, final long amount) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            this.plugin.treasury().requireActive();
            if (amount < 0) {
                throw new KrajeException(Status.INVALID, "api-invalid-tax");
            }
            if (amount > this.plugin.cfg().taxMax) {
                throw new KrajeException(Status.INVALID, "tax-too-high", Notify.unparsed("max", this.plugin.treasury().format(this.plugin.cfg().taxMax)));
            }
            this.manager().setTax(c, amount);
            if (amount == 0) {
                Notify.members(this.plugin.messages(), c, "tax-cleared", Notify.country(c), Notify.unparsed("player", c.rulerName()));
            } else {
                Notify.members(this.plugin.messages(), c, "tax-set", Notify.country(c), Notify.unparsed("player", c.rulerName()),
                    Notify.unparsed("amount", this.plugin.treasury().format(amount)),
                    Notify.unparsed("time", this.plugin.cfg().dailyTickTime.toString()));
            }
            return KrajeResult.ok(c.id());
        });
    }

    private static Member requireMember(final Country c, final UUID player) {
        final Member m = player == null ? null : c.member(player);
        if (m == null) {
            throw new KrajeException(Status.NOT_FOUND, "api-not-member", Notify.unparsed("player", player == null ? "?" : nameOf(player)));
        }
        return m;
    }

    private static String nameOf(final UUID player) {
        final String name = Bukkit.getOfflinePlayer(player).getName();
        return name == null ? player.toString() : name;
    }

    @Override
    public KrajeResult setRank(final UUID countryId, final UUID player, final Rank rank) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            if (rank != Rank.ADVISOR && rank != Rank.CITIZEN) {
                throw new KrajeException(Status.INVALID, "api-invalid-rank");
            }
            final Member target = requireMember(c, player);
            if (target.rank() == Rank.RULER) {
                throw new KrajeException(Status.DENIED, "api-cannot-change-ruler-rank");
            }
            if (target.rank() == rank) {
                throw new KrajeException(Status.INVALID, rank == Rank.ADVISOR ? "already-max-rank" : "already-min-rank",
                    Notify.unparsed("player", target.name()));
            }
            this.manager().setRank(c, target.uuid(), rank);
            Notify.members(this.plugin.messages(), c, rank == Rank.ADVISOR ? "member-promoted" : "member-demoted", Notify.country(c),
                Notify.unparsed("player", target.name()), Notify.unparsed("rank", rank.displayName()));
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult kickMember(final UUID countryId, final UUID player, final UUID byPlayer) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            final Member target = requireMember(c, player);
            if (target.uuid().equals(c.ruler()) || target.rank() == Rank.RULER) {
                throw new KrajeException(Status.DENIED, "api-cannot-kick-ruler");
            }
            if (target.uuid().equals(byPlayer)) {
                throw new KrajeException(Status.INVALID, "cannot-target-self");
            }
            final Member by = byPlayer == null ? null : c.member(byPlayer);
            if (by != null && target.rank().weight() >= by.rank().weight()) {
                throw new KrajeException(Status.DENIED, "cannot-kick-higher", Notify.unparsed("player", target.name()));
            }
            this.plugin.unions().requireCanLoseMember(c, target.uuid());
            final String byName = by != null ? by.name()
                : byPlayer != null ? nameOf(byPlayer) : this.plugin.messages().raw("api-actor-admin");
            final boolean forTaxes = this.plugin.treasury().active() && this.plugin.treasury().canKickForTaxes(target);
            final long debt = target.taxDebt();
            this.manager().removeMember(c, target.uuid());
            this.plugin.chat().disableCountryChat(target.uuid());
            final String money = this.plugin.treasury().format(debt);
            final org.bukkit.entity.Player online = Bukkit.getPlayer(target.uuid());
            if (online != null) {
                this.plugin.messages().send(online, forTaxes ? "kicked-target-taxes" : "kicked-target", Notify.country(c),
                    Notify.unparsed("player", byName), Notify.unparsed("debt", money));
            }
            Notify.members(this.plugin.messages(), c, forTaxes ? "member-kicked-taxes" : "member-kicked", Notify.country(c),
                Notify.unparsed("player", target.name()), Notify.unparsed("by", byName), Notify.unparsed("debt", money),
                Notify.unparsed("days", target.debtDays()));
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult transferRuler(final UUID countryId, final UUID newRuler) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            final Member target = requireMember(c, newRuler);
            if (target.uuid().equals(c.ruler())) {
                throw new KrajeException(Status.INVALID, "api-already-ruler", Notify.unparsed("player", target.name()));
            }
            this.manager().transferRule(c, target.uuid());
            Notify.members(this.plugin.messages(), c, "ruler-changed", Notify.country(c), Notify.unparsed("player", target.name()));
            return KrajeResult.ok(c.id());
        });
    }

    /** Only online players can be invited (invitations live a few minutes in memory, like in game). */
    @Override
    public KrajeResult invite(final UUID countryId, final UUID inviterUuid, final String targetName) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            if (targetName == null || targetName.isBlank()) {
                throw new KrajeException(Status.INVALID, "player-not-found", Notify.unparsed("player", ""));
            }
            final org.bukkit.entity.Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                final org.bukkit.OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(targetName);
                if (known == null) {
                    throw new KrajeException(Status.NOT_FOUND, "player-not-found", Notify.unparsed("player", targetName));
                }
                throw new KrajeException(Status.DENIED, "api-invite-offline",
                    Notify.unparsed("player", known.getName() == null ? targetName : known.getName()));
            }
            if (target.getUniqueId().equals(inviterUuid)) {
                throw new KrajeException(Status.INVALID, "cannot-target-self");
            }
            if (this.manager().countryOf(target.getUniqueId()) != null) {
                throw new KrajeException(Status.DENIED, "target-in-country", Notify.unparsed("player", target.getName()));
            }
            final Member inviter = inviterUuid == null ? null : c.member(inviterUuid);
            final String inviterName = inviter != null ? inviter.name()
                : inviterUuid != null ? nameOf(inviterUuid) : this.plugin.messages().raw("api-actor-admin");
            final long ttl = this.plugin.cfg().inviteExpiryMillis;
            this.plugin.requests().invite(target.getUniqueId(), c.id(), ttl);
            this.plugin.messages().send(target, "invite-received", Notify.country(c), Notify.unparsed("player", inviterName),
                Notify.unparsed("minutes", Math.max(1, ttl / 60_000L)),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.styling("join",
                    net.kyori.adventure.text.event.ClickEvent.runCommand("/kraj dolacz " + c.name())));
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult declareWar(final UUID from, final UUID to) {
        return this.run(() -> {
            final Country a = this.require(from);
            final Country b = this.require(to);
            if (a == b) {
                throw new KrajeException(Status.INVALID, "cannot-target-own-country");
            }
            this.plugin.actions().declareWar(a, b, a.rulerName());
            return KrajeResult.ok(a.id());
        });
    }

    @Override
    public KrajeResult breakAlliance(final UUID from, final UUID to) {
        return this.run(() -> {
            final Country a = this.require(from);
            final Country b = this.require(to);
            if (a == b) {
                throw new KrajeException(Status.INVALID, "cannot-target-own-country");
            }
            this.plugin.actions().breakAlliance(a, b);
            return KrajeResult.ok(a.id());
        });
    }

    @Override
    public KrajeResult treasuryDeposit(final UUID countryId, final UUID playerUuid, final long amount) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            this.plugin.treasury().requireActive();
            final Member m = requireMember(c, playerUuid);
            if (amount <= 0) {
                throw new KrajeException(Status.INVALID, "api-invalid-amount");
            }
            this.plugin.treasury().depositFromAccount(m.uuid(), m.name(), c, amount);
            for (final Member other : c.members()) {
                if (other.rank() == Rank.RULER && !other.uuid().equals(m.uuid())) {
                    final org.bukkit.entity.Player ruler = Bukkit.getPlayer(other.uuid());
                    if (ruler != null) {
                        this.plugin.messages().send(ruler, "deposited-notify", Notify.country(c), Notify.unparsed("player", m.name()),
                            Notify.unparsed("amount", this.plugin.treasury().format(amount)));
                    }
                }
            }
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public KrajeResult treasuryWithdraw(final UUID countryId, final UUID playerUuid, final long amount) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            this.plugin.treasury().requireActive();
            final Member m = requireMember(c, playerUuid);
            if (m.rank() != Rank.RULER) {
                throw new KrajeException(Status.DENIED, "rank-required", Notify.unparsed("rank", Rank.RULER.displayName()));
            }
            if (amount <= 0) {
                throw new KrajeException(Status.INVALID, "api-invalid-amount");
            }
            this.plugin.treasury().withdraw(m.uuid(), m.name(), c, amount);
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public List<TreasuryLogEntry> treasuryLog(final UUID countryId, final int limit) {
        final Country c = this.manager().get(countryId);
        if (c == null || limit <= 0) {
            return List.of();
        }
        final List<TreasuryLogEntry> out = new ArrayList<>();
        for (final TreasuryEntry e : List.copyOf(c.treasuryLog())) {
            if (out.size() >= limit) {
                break;
            }
            out.add(new TreasuryLogEntry(e.time(), e.type(), e.amount(), e.actor() == null ? "" : e.actor()));
        }
        return List.copyOf(out);
    }

    @Override
    public long taxDebt(final UUID countryId, final UUID player) {
        final Country c = this.manager().get(countryId);
        final Member m = c == null || player == null ? null : c.member(player);
        return m == null ? 0L : m.taxDebt();
    }

    // ------------------------------------------------------------------ capitulation & vassals

    @Override
    public Optional<UUID> getOverlord(final UUID countryId) {
        return Optional.ofNullable(this.manager().overlordOf(countryId));
    }

    @Override
    public Set<UUID> getVassals(final UUID countryId) {
        return this.manager().vassalIdsOf(countryId);
    }

    @Override
    public Optional<VassalInfo> getVassalage(final UUID vassal) {
        return Optional.ofNullable(this.manager().vassalInfo(vassal));
    }

    @Override
    public KrajeResult offerCapitulation(final UUID loser, final UUID winner, final int days) {
        return this.run(() -> {
            final Country l = this.require(loser);
            final Country w = this.require(winner);
            this.plugin.vassals().offer(l, w, days);
            return KrajeResult.ok(l.id());
        });
    }

    @Override
    public KrajeResult respondCapitulation(final UUID winner, final UUID loser, final boolean accept) {
        return this.run(() -> this.plugin.vassals().respond(this.require(winner), this.require(loser), accept));
    }

    @Override
    public KrajeResult releaseVassal(final UUID overlord, final UUID vassal) {
        return this.run(() -> {
            final Country o = this.require(overlord);
            final Country v = this.require(vassal);
            this.plugin.vassals().release(o, v);
            return KrajeResult.ok(v.id());
        });
    }

    @Override
    public KrajeResult buyout(final UUID vassal) {
        return this.run(() -> {
            final Country v = this.require(vassal);
            this.plugin.vassals().buyout(v);
            return KrajeResult.ok(v.id());
        });
    }

    @Override
    public long buyoutCost(final UUID vassal) {
        if (!Bukkit.isPrimaryThread()) {
            final VassalInfo info = this.manager().vassalInfo(vassal);
            if (info == null) {
                return -1L;
            }
            return pl.medivalrpg.kraje.economy.TributeMath.buyout(this.plugin.cfg().buyoutMultiplier, info.dailyTribute(),
                pl.medivalrpg.kraje.economy.TributeMath.remainingDays(System.currentTimeMillis(), info.until()), info.debt());
        }
        final Country c = this.manager().get(vassal);
        return c == null ? -1L : this.plugin.vassals().buyoutCost(c);
    }

    @Override
    public KrajeResult setRuler(final UUID countryId, final String playerName) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            this.plugin.actions().setRuler(c, playerName);
            return KrajeResult.ok(c.id());
        });
    }

    // ------------------------------------------------------------------ protected areas

    @Override
    public KrajeResult addProtectedArea(final String id, final String world, final int minX, final int minZ,
                                        final int maxX, final int maxZ, final int bufferChunks) {
        return this.run(() -> {
            final ProtectedArea area;
            try {
                area = ProtectedArea.of(id, world == null ? null : world.trim(), minX, minZ, maxX, maxZ, bufferChunks);
            } catch (final ProtectedArea.InvalidAreaException e) {
                throw new KrajeException(Status.INVALID, switch (e.problem()) {
                    case ID -> "api-invalid-area-id";
                    case WORLD -> "api-invalid-area-world";
                    case BUFFER -> "api-invalid-area-buffer";
                }, Notify.unparsed("max", ProtectedArea.MAX_BUFFER));
            }
            final boolean replaced = this.plugin.protectedAreas().put(area).isPresent();
            this.plugin.onProtectedAreasChanged();
            this.plugin.getLogger().info((replaced ? "Zaktualizowano" : "Dodano") + " obszar chroniony " + area.id() + " ("
                + area.world() + " " + area.minX() + "," + area.minZ() + " — " + area.maxX() + "," + area.maxZ()
                + ", bufor " + area.bufferChunks() + " ch.)");
            return KrajeResult.ok(null);
        });
    }

    // ------------------------------------------------------------------ unions, embassies, relation quality (API 1.5)

    @Override
    public Optional<pl.medivalrpg.kraje.api.UnionInfo> getUnion(final UUID countryId) {
        return Optional.ofNullable(this.manager().unionInfo(countryId));
    }

    @Override
    public KrajeResult proposeUnion(final UUID home, final UUID partner) {
        return this.run(() -> {
            final Country h = this.require(home);
            final Country p = this.require(partner);
            if (h == p) {
                throw new KrajeException(Status.INVALID, "cannot-target-own-country");
            }
            this.plugin.unions().propose(h, p);
            return KrajeResult.ok(h.id());
        });
    }

    @Override
    public KrajeResult respondUnion(final UUID partner, final UUID home, final boolean accept) {
        return this.run(() -> {
            final Country p = this.require(partner);
            this.plugin.unions().respond(p, this.require(home), accept);
            return KrajeResult.ok(p.id());
        });
    }

    @Override
    public KrajeResult dissolveUnion(final UUID countryId) {
        return this.run(() -> {
            final Country c = this.require(countryId);
            if (this.manager().unionPartnerSide(c) == null) {
                throw new KrajeException(Status.NOT_FOUND, "union-none", Notify.country(c));
            }
            this.plugin.unions().dissolve(c, pl.medivalrpg.kraje.api.event.UnionDissolvedEvent.Reason.ADMIN);
            return KrajeResult.ok(c.id());
        });
    }

    @Override
    public boolean hasEmbassy(final UUID guest, final UUID host) {
        if (guest == null || host == null) {
            return false;
        }
        for (final pl.medivalrpg.kraje.api.EmbassyInfo e : this.manager().embassyInfos()) {
            if (e.guest().equals(guest) && e.host().equals(host)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<pl.medivalrpg.kraje.api.EmbassyInfo> getEmbassies(final UUID countryId) {
        if (countryId == null) {
            return List.of();
        }
        return this.manager().embassyInfos().stream()
            .filter(e -> e.guest().equals(countryId) || e.host().equals(countryId)).toList();
    }

    @Override
    public Optional<pl.medivalrpg.kraje.api.EmbassyInfo> getEmbassyAt(final String world, final int cx, final int cz) {
        return world == null ? Optional.empty() : Optional.ofNullable(this.manager().embassyAt(world, ChunkKeys.key(cx, cz)));
    }

    @Override
    public pl.medivalrpg.kraje.api.RelationQuality getRelationQuality(final UUID countryA, final UUID countryB) {
        if (countryA == null || countryB == null) {
            return pl.medivalrpg.kraje.api.RelationQuality.NEUTRAL;
        }
        final boolean vassalage = countryA.equals(this.manager().overlordOf(countryB)) || countryB.equals(this.manager().overlordOf(countryA));
        return pl.medivalrpg.kraje.embassy.EmbassyRules.quality(countryA.equals(countryB),
            countryB.equals(this.manager().unionPartnerId(countryA)), this.getRelation(countryA, countryB), vassalage);
    }

    @Override
    public boolean canStartJointProject(final UUID from, final UUID into) {
        if (from == null || into == null || this.manager().info(from) == null || this.manager().info(into) == null) {
            return false;
        }
        return pl.medivalrpg.kraje.embassy.EmbassyRules.jointProject(this.plugin.cfg().embassyRules,
            this.getRelationQuality(from, into), this.hasEmbassy(from, into), this.hasEmbassy(into, from));
    }

    @Override
    public double getTradeBonus(final UUID countryA, final UUID countryB) {
        if (countryA == null || countryB == null) {
            return 0.0;
        }
        return pl.medivalrpg.kraje.embassy.EmbassyRules.tradeBonus(this.plugin.cfg().embassyRules,
            this.getRelationQuality(countryA, countryB), this.hasEmbassyBetween(countryA, countryB));
    }

    @Override
    public KrajeResult requestEmbassy(final UUID guest, final Location spot) {
        return this.run(() -> {
            final Country g = this.require(guest);
            this.plugin.embassies().request(g, null, spot);
            return KrajeResult.ok(g.id());
        });
    }

    @Override
    public KrajeResult respondEmbassy(final UUID host, final UUID guest, final boolean accept) {
        return this.run(() -> {
            final Country h = this.require(host);
            this.plugin.embassies().respond(h, this.require(guest), accept);
            return KrajeResult.ok(h.id());
        });
    }

    @Override
    public KrajeResult closeEmbassy(final UUID guest, final UUID host) {
        return this.run(() -> {
            final Country g = this.require(guest);
            this.plugin.embassies().closeByGuest(g, this.require(host));
            return KrajeResult.ok(g.id());
        });
    }

    @Override
    public KrajeResult expelEmbassy(final UUID host, final UUID guest) {
        return this.run(() -> {
            final Country h = this.require(host);
            this.plugin.embassies().expel(h, this.require(guest));
            return KrajeResult.ok(h.id());
        });
    }

    @Override
    public KrajeResult removeProtectedArea(final String id) {
        return this.run(() -> {
            if (this.plugin.protectedAreas().remove(id).isEmpty()) {
                throw new KrajeException(Status.NOT_FOUND, "api-area-not-found", Notify.unparsed("area", String.valueOf(id)));
            }
            this.plugin.onProtectedAreasChanged();
            this.plugin.getLogger().info("Usunięto obszar chroniony " + id);
            return KrajeResult.ok(null);
        });
    }
}
