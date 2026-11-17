// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.Event;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.EmbassyInfo;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.UnionInfo;
import pl.medivalrpg.kraje.api.VassalInfo;
import pl.medivalrpg.kraje.api.event.ChunkOwnerChangeEvent;
import pl.medivalrpg.kraje.api.event.CountryCreateEvent;
import pl.medivalrpg.kraje.api.event.CountryDisbandEvent;
import pl.medivalrpg.kraje.api.event.RelationChangeEvent;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Embassy;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.PersonalUnion;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.model.StoredInvite;
import pl.medivalrpg.kraje.model.TreasuryEntry;
import pl.medivalrpg.kraje.model.Vassalage;
import pl.medivalrpg.kraje.union.UnionRules;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Owns all countries and keeps the lookup indexes in sync.
 *
 * <p>All mutating methods must be called on the main thread. Lookup indexes ({@link #ownerAt},
 * {@link #countryIdOf}) and {@link #info} snapshots use concurrent / immutable structures so they can
 * also be read from async threads (chat, API consumers).</p>
 *
 * <p>Territory, founding, disbanding and relation changes fire the public events of
 * {@code pl.medivalrpg.kraje.api.event} through the {@link EventSink}, always after the change has been
 * applied and the snapshots rebuilt (so listeners already see the new state through the API).</p>
 */
public final class CountryManager {

    /** Notified (main thread) after every change. */
    public interface ChangeListener {
        void onCountriesChanged();
    }

    /** Fires Bukkit events (no-op in tests). */
    public interface EventSink {
        void fire(Event event);
    }

    private final Logger logger;
    private final Map<UUID, Country> countries = new LinkedHashMap<>();
    private final Map<String, UUID> byName = new HashMap<>();
    private final Map<UUID, UUID> memberIndex = new ConcurrentHashMap<>();
    /** world name → chunk key → country id. */
    private final Map<String, Map<Long, UUID>> claimIndex = new ConcurrentHashMap<>();
    private volatile Map<UUID, KrajInfo> snapshots = Map.of();
    private ChangeListener listener = () -> { };
    private EventSink events = e -> { };
    private long lastDailyTick;
    /** Persistent invitations (conquered citizens), saved with the countries. */
    private final PendingInvites storedInvites = new PendingInvites();
    /** vassal id → snapshot of its vassalage (thread-safe reads). */
    private volatile Map<UUID, VassalInfo> vassalSnapshots = Map.of();
    /** overlord id → vassal ids (thread-safe reads). */
    private volatile Map<UUID, Set<UUID>> vassalIndex = Map.of();
    /** Expected daily tribute of a vassal (set by the plugin; needs economy and Ludnosc). Main thread. */
    private ToLongFunction<Country> tributeEstimator = c -> 0L;
    /** home and partner id → snapshot of their personal union (thread-safe reads). */
    private volatile Map<UUID, UnionInfo> unionSnapshots = Map.of();
    /** world → chunk key → embassy standing there (thread-safe reads). */
    private volatile Map<String, Map<Long, EmbassyInfo>> embassyIndex = Map.of();
    private volatile List<EmbassyInfo> embassySnapshots = List.of();
    /** Minimum union duration (config), only for {@link UnionInfo#breakableAt()}. */
    private long unionMinDurationMillis;
    /** Called after the ruler of a country changed ({@link #transferRule}); the plugin ends broken unions. */
    private java.util.function.Consumer<Country> rulerListener = c -> { };

    public CountryManager(final Logger logger) {
        this.logger = logger;
    }

    public void setListener(final ChangeListener listener) {
        this.listener = listener;
    }

    public void setEventSink(final EventSink events) {
        this.events = events;
    }

    /** Loads countries from storage, repairing inconsistent data (duplicate claims / members). */
    public void loadAll(final Collection<Country> loaded) {
        this.loadAll(loaded, 0L);
    }

    public void loadAll(final Collection<Country> loaded, final long lastDailyTick) {
        this.loadAll(loaded, lastDailyTick, List.of());
    }

    /** Loads countries and persistent invitations (expired ones and those to unknown countries are dropped). */
    public void loadAll(final Collection<Country> loaded, final long lastDailyTick, final Collection<StoredInvite> invites) {
        this.countries.clear();
        this.byName.clear();
        this.memberIndex.clear();
        this.claimIndex.clear();
        this.lastDailyTick = lastDailyTick;
        for (final Country c : loaded) {
            if (this.byName.containsKey(lower(c.name()))) {
                this.logger.warning("Pomijam kraj o zduplikowanej nazwie: " + c.name());
                continue;
            }
            if (c.npc()) {
                // NPC countries have a synthetic ruler that is never a member.
                c.ruler(Country.npcRulerId(c.id()));
                c.membersMap().values().removeIf(m -> m.rank() == Rank.RULER);
            } else if (!c.isMember(c.ruler())) {
                this.logger.warning("Kraj " + c.name() + " nie ma władcy wśród członków — pomijam.");
                continue;
            }
            this.countries.put(c.id(), c);
            this.byName.put(lower(c.name()), c.id());
            for (final Member m : new ArrayList<>(c.members())) {
                if (c.isTitular(m.uuid())) {
                    continue; // the union monarch is indexed in his home country (validated below)
                }
                if (this.memberIndex.putIfAbsent(m.uuid(), c.id()) != null) {
                    this.logger.warning("Gracz " + m.name() + " należy do dwóch krajów — usuwam z " + c.name());
                    c.membersMap().remove(m.uuid());
                }
            }
            for (final Map.Entry<String, Set<Long>> entry : c.claimsMap().entrySet()) {
                final Map<Long, UUID> worldIndex = this.worldIndex(entry.getKey());
                for (final Long key : new ArrayList<>(entry.getValue())) {
                    if (worldIndex.putIfAbsent(key, c.id()) != null) {
                        this.logger.warning("Chunk zajęty przez dwa kraje — usuwam z " + c.name());
                        entry.getValue().remove(key);
                    }
                }
            }
        }
        // Drop relations (and war timestamps) of countries that no longer exist.
        for (final Country c : this.countries.values()) {
            c.relationsMap().keySet().removeIf(id -> !this.countries.containsKey(id));
            c.warSinceMap().keySet().removeIf(id -> !this.countries.containsKey(id) || c.relationTo(id) != Relation.WAR);
            c.warEndedAtMap().keySet().removeIf(id -> !this.countries.containsKey(id));
            // Vassalage to a country that no longer exists (or to itself) is dropped.
            final UUID overlord = c.overlord();
            if (overlord != null && (overlord.equals(c.id()) || !this.countries.containsKey(overlord))) {
                this.logger.warning("Kraj " + c.name() + " był wasalem nieistniejącego kraju — wasalstwo usunięte.");
                c.vassalage(null);
            }
        }
        // A vassal of a vassal of ... itself would be a cycle: break it.
        for (final Country c : this.countries.values()) {
            if (this.inVassalCycle(c)) {
                this.logger.warning("Cykl wasalstwa przy " + c.name() + " — wasalstwo usunięte.");
                c.vassalage(null);
            }
        }
        this.validateUnions();
        this.validateEmbassies();
        final long now = System.currentTimeMillis();
        this.storedInvites.load(invites.stream()
            .filter(i -> this.countries.containsKey(i.country()) && !this.memberIndex.containsKey(i.player())).toList(), now);
        this.rebuildSnapshots();
    }

    private boolean inVassalCycle(final Country start) {
        final Set<UUID> seen = new HashSet<>();
        Country c = start;
        while (c != null && c.overlord() != null) {
            if (!seen.add(c.id())) {
                return true;
            }
            c = this.countries.get(c.overlord());
        }
        return false;
    }

    // ------------------------------------------------------------------ lookups

    public Collection<Country> countries() {
        return Collections.unmodifiableCollection(this.countries.values());
    }

    public List<Country> countriesSnapshot() {
        return List.copyOf(this.countries.values());
    }

    public Country get(final UUID id) {
        return id == null ? null : this.countries.get(id);
    }

    public Country byName(final String name) {
        return name == null ? null : this.get(this.byName.get(lower(name)));
    }

    public boolean isNameTaken(final String name) {
        return this.byName.containsKey(lower(name));
    }

    public UUID countryIdOf(final UUID player) {
        return this.memberIndex.get(player);
    }

    public Country countryOf(final UUID player) {
        return this.get(this.memberIndex.get(player));
    }

    /** Thread-safe. */
    public UUID ownerAt(final String world, final long chunkKey) {
        final Map<Long, UUID> worldIndex = this.claimIndex.get(world);
        return worldIndex == null ? null : worldIndex.get(chunkKey);
    }

    /** Thread-safe. */
    public UUID ownerAt(final World world, final int blockX, final int blockZ) {
        return this.ownerAt(world.getName(), ChunkKeys.key(blockX >> 4, blockZ >> 4));
    }

    /** Thread-safe. */
    public UUID ownerAt(final Location location) {
        final World world = location.getWorld();
        return world == null ? null : this.ownerAt(world, location.getBlockX(), location.getBlockZ());
    }

    public Country countryAt(final Location location) {
        return this.get(this.ownerAt(location));
    }

    public Country countryAt(final ChunkPos pos) {
        return this.get(this.ownerAt(pos.world(), pos.key()));
    }

    /** Whether any chunk owned by a country other than {@code except} lies within {@code radius} chunks. */
    public boolean hasForeignClaimWithin(final String world, final int cx, final int cz, final int radius, final UUID except) {
        final Map<Long, UUID> worldIndex = this.claimIndex.get(world);
        if (worldIndex == null || worldIndex.isEmpty()) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final UUID owner = worldIndex.get(ChunkKeys.key(cx + dx, cz + dz));
                if (owner != null && !owner.equals(except)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Thread-safe: internal chunk keys of a country in one world (copied from the concurrent index). */
    public Set<Long> claimKeysOf(final UUID countryId, final String world) {
        final Map<Long, UUID> worldIndex = this.claimIndex.get(world);
        if (worldIndex == null) {
            return Set.of();
        }
        final Set<Long> keys = new HashSet<>();
        worldIndex.forEach((key, owner) -> {
            if (owner.equals(countryId)) {
                keys.add(key);
            }
        });
        return keys;
    }

    /** Thread-safe immutable snapshot. */
    public KrajInfo info(final UUID countryId) {
        return countryId == null ? null : this.snapshots.get(countryId);
    }

    /** Thread-safe immutable snapshot. */
    public KrajInfo infoOf(final UUID player) {
        return this.info(this.memberIndex.get(player));
    }

    public Collection<KrajInfo> infos() {
        return this.snapshots.values();
    }

    public Relation relation(final UUID a, final UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return Relation.NEUTRAL;
        }
        final Country ca = this.countries.get(a);
        return ca == null ? Relation.NEUTRAL : ca.relationTo(b);
    }

    /** Epoch millis of the last processed daily economy tick (0 = never). */
    public long lastDailyTick() {
        return this.lastDailyTick;
    }

    // ------------------------------------------------------------------ mutations (main thread)

    public Country create(final UUID founder, final String founderName, final String name, final int color,
                          final ChunkPos capital, final SpawnPoint spawn) {
        return this.create(founder, founderName, name, color, capital, spawn, ClaimCause.PLAYER);
    }

    public Country create(final UUID founder, final String founderName, final String name, final int color,
                          final ChunkPos capital, final SpawnPoint spawn, final ClaimCause cause) {
        final Country c = new Country(UUID.randomUUID(), name, color, "", founder, System.currentTimeMillis(), capital, spawn);
        c.membersMap().put(founder, new Member(founder, founderName, Rank.RULER, System.currentTimeMillis()));
        this.register(c);
        this.memberIndex.put(founder, c.id());
        this.storedInvites.removePlayer(founder);
        this.addClaim(c, capital.world(), capital.key());
        this.changed();
        this.events.fire(new CountryCreateEvent(c.id(), false));
        this.fireChunk(null, c.id(), capital.world(), capital.key(), cause);
        return c;
    }

    /** Creates a country run by NPCs (no player members, synthetic ruler). */
    public Country createNpc(final String name, final int color, final ChunkPos capital, final SpawnPoint spawn,
                             final String rulerName, final ClaimCause cause) {
        final UUID id = UUID.randomUUID();
        final Country c = new Country(id, name, color, "", Country.npcRulerId(id), System.currentTimeMillis(), capital, spawn);
        c.npc(true);
        c.npcRulerName(rulerName);
        this.register(c);
        this.addClaim(c, capital.world(), capital.key());
        this.changed();
        this.events.fire(new CountryCreateEvent(c.id(), true));
        this.fireChunk(null, c.id(), capital.world(), capital.key(), cause);
        return c;
    }

    private void register(final Country c) {
        this.countries.put(c.id(), c);
        this.byName.put(lower(c.name()), c.id());
    }

    public void disband(final Country c) {
        this.disband(c, ClaimCause.PLAYER);
    }

    /** Removes the country; every chunk goes back to the wilderness ({@code cause} in the chunk events). */
    public void disband(final Country c, final ClaimCause cause) {
        if (this.countries.remove(c.id()) == null) {
            return;
        }
        this.byName.remove(lower(c.name()));
        for (final UUID member : c.membersMap().keySet()) {
            this.memberIndex.remove(member, c.id());
        }
        final List<ChunkPos> released = new ArrayList<>();
        for (final Map.Entry<String, Set<Long>> entry : c.claimsMap().entrySet()) {
            final Map<Long, UUID> worldIndex = this.claimIndex.get(entry.getKey());
            for (final Long key : entry.getValue()) {
                if (worldIndex != null) {
                    worldIndex.remove(key, c.id());
                }
                released.add(new ChunkPos(entry.getKey(), ChunkKeys.x(key), ChunkKeys.z(key)));
            }
        }
        for (final Country other : this.countries.values()) {
            other.relationsMap().remove(c.id());
            other.warSinceMap().remove(c.id());
            other.warEndedAtMap().remove(c.id());
            if (c.id().equals(other.overlord())) {
                other.vassalage(null); // normally ended (with events) by KrajeActions#disband already
            }
            if (other.union() != null && c.id().equals(other.union().home())) {
                this.endUnionInternal(other); // normally ended (with events) by KrajeActions#disband already
            }
            other.embassiesMap().remove(c.id());
        }
        this.storedInvites.removeCountry(c.id());
        this.changed();
        for (final ChunkPos pos : released) {
            this.fireChunk(c.id(), null, pos.world(), pos.key(), cause);
        }
        this.events.fire(new CountryDisbandEvent(c.id(), c.name(), c.npc()));
    }

    public void claim(final Country c, final String world, final long key) {
        this.claim(c, world, key, ClaimCause.PLAYER);
    }

    public void claim(final Country c, final String world, final long key, final ClaimCause cause) {
        this.addClaim(c, world, key);
        pl.medivalrpg.kraje.geometry.Bridgeheads.normalize(c);
        this.changed();
        this.fireChunk(null, c.id(), world, key, cause);
    }

    /**
     * Several free chunks of one world to {@code c} with a single save/refresh (admin area tools). Chunks that are
     * already claimed (by anyone) are left out; returns the chunks actually claimed. Events fire per chunk.
     */
    public List<Long> claimAll(final Country c, final String world, final Collection<Long> keys, final ClaimCause cause) {
        final List<Long> claimed = new ArrayList<>();
        final Map<Long, UUID> index = this.worldIndex(world);
        for (final long key : keys) {
            if (!index.containsKey(key)) {
                this.addClaim(c, world, key);
                claimed.add(key);
            }
        }
        if (claimed.isEmpty()) {
            return claimed;
        }
        pl.medivalrpg.kraje.geometry.Bridgeheads.normalize(c);
        this.changed();
        for (final long key : claimed) {
            this.fireChunk(null, c.id(), world, key, cause);
        }
        return claimed;
    }

    private void addClaim(final Country c, final String world, final long key) {
        final UUID previous = this.worldIndex(world).putIfAbsent(key, c.id());
        if (previous != null && !previous.equals(c.id())) {
            throw new IllegalStateException("Chunk already claimed");
        }
        c.mutableClaimsIn(world).add(key);
    }

    private void removeClaim(final Country c, final String world, final long key) {
        pl.medivalrpg.kraje.geometry.Bridgeheads.removing(c,new ChunkPos(world,pl.medivalrpg.kraje.util.ChunkKeys.x(key),pl.medivalrpg.kraje.util.ChunkKeys.z(key)));
        final Set<Long> set = c.claimsMap().get(world);
        if (set != null) {
            set.remove(key);
            if (set.isEmpty()) {
                c.claimsMap().remove(world);
            }
        }
        final Map<Long, UUID> worldIndex = this.claimIndex.get(world);
        if (worldIndex != null) {
            worldIndex.remove(key, c.id());
        }
        // Removal cannot merge components. removing() already relocates or drops a lost seed;
        // do not rescan the whole mainland per chunk during a large admin unclaim operation.
    }

    public void unclaim(final Country c, final String world, final long key) {
        this.unclaim(c, world, key, ClaimCause.PLAYER);
    }

    public void unclaim(final Country c, final String world, final long key, final ClaimCause cause) {
        this.removeClaim(c, world, key);
        this.changed();
        this.fireChunk(c.id(), null, world, key, cause);
    }

    /** Several chunks of one country back to the wilderness with a single save/refresh (bankruptcy). */
    public void unclaimAll(final Country c, final List<ChunkPos> chunks, final ClaimCause cause) {
        if (chunks.isEmpty()) {
            return;
        }
        for (final ChunkPos pos : chunks) {
            this.removeClaim(c, pos.world(), pos.key());
        }
        this.changed();
        for (final ChunkPos pos : chunks) {
            this.fireChunk(c.id(), null, pos.world(), pos.key(), cause);
        }
    }

    /** Moves a chunk from {@code from} to {@code to} ({@code null} = wilderness) as one change (conquest). */
    public void transferChunk(final Country from, final Country to, final String world, final long key, final ClaimCause cause) {
        this.transferChunk(from,to,world,key,cause,false);
    }

    /** Landing ownership and its persistent seed become visible in the same save and event. */
    public void transferChunk(final Country from, final Country to, final String world, final long key,
                              final ClaimCause cause, final boolean landing) {
        this.removeClaim(from, world, key);
        if (to != null) {
            this.addClaim(to, world, key);
            if (landing) {
                to.mutableBridgeheads().add(new ChunkPos(world,pl.medivalrpg.kraje.util.ChunkKeys.x(key),pl.medivalrpg.kraje.util.ChunkKeys.z(key)));
            }
            pl.medivalrpg.kraje.geometry.Bridgeheads.normalize(to);
        }
        this.changed();
        this.fireChunk(from.id(), to == null ? null : to.id(), world, key, cause);
    }

    public void addMember(final Country c, final UUID player, final String name, final Rank rank) {
        if (this.memberIndex.containsKey(player)) {
            throw new IllegalStateException("Player already in a country");
        }
        c.membersMap().put(player, new Member(player, name, rank, System.currentTimeMillis()));
        this.memberIndex.put(player, c.id());
        this.storedInvites.removePlayer(player); // a player in a country has no use for invitations
        this.changed();
    }

    public void removeMember(final Country c, final UUID player) {
        if (player.equals(c.ruler())) {
            throw new IllegalStateException("Cannot remove the ruler");
        }
        c.membersMap().remove(player);
        this.memberIndex.remove(player, c.id());
        this.changed();
    }

    public void setRank(final Country c, final UUID player, final Rank rank) {
        final Member m = c.member(player);
        if (m == null || rank == Rank.RULER || m.rank() == Rank.RULER) {
            throw new IllegalStateException("Invalid rank change");
        }
        m.rank(rank);
        this.changed();
    }

    /**
     * Makes {@code newRuler} (must be a member) the ruler; the old ruler becomes an advisor. For an NPC country
     * the country stops being an NPC country (a player took it over).
     */
    public void transferRule(final Country c, final UUID newRuler) {
        final Member next = c.member(newRuler);
        if (next == null) {
            throw new IllegalStateException("Not a member");
        }
        final Member old = c.rulerMember();
        if (old != null && !old.uuid().equals(newRuler)) {
            old.rank(Rank.ADVISOR);
        }
        next.rank(Rank.RULER);
        c.ruler(newRuler);
        c.npc(false);
        this.changed();
        this.rulerListener.accept(c);
    }

    public void setColor(final Country c, final int color) {
        c.color(color);
        this.changed();
    }

    public void setDescription(final Country c, final String description) {
        c.description(description);
        this.changed();
    }

    public void setCapital(final Country c, final ChunkPos capital, final SpawnPoint spawn) {
        c.capital(capital);
        c.spawn(spawn);
        pl.medivalrpg.kraje.geometry.Bridgeheads.normalize(c);
        this.changed();
    }

    public void setNpcRulerName(final Country c, final String name) {
        c.npcRulerName(name);
        this.changed();
    }

    public void setTax(final Country c, final long tax) {
        c.taxPerCitizen(tax);
        this.changed();
    }

    /** Sets the relation (symmetric), keeps war timestamps and fires {@link RelationChangeEvent} if it changed. */
    public void setRelation(final Country a, final Country b, final Relation relation) {
        final Relation old = a.relationTo(b.id());
        final long now = System.currentTimeMillis();
        if (relation == Relation.NEUTRAL) {
            a.relationsMap().remove(b.id());
            b.relationsMap().remove(a.id());
        } else {
            a.relationsMap().put(b.id(), relation);
            b.relationsMap().put(a.id(), relation);
        }
        if (old != Relation.WAR && relation == Relation.WAR) {
            a.warSinceMap().put(b.id(), now);
            b.warSinceMap().put(a.id(), now);
        } else if (old == Relation.WAR && relation != Relation.WAR) {
            a.warSinceMap().remove(b.id());
            b.warSinceMap().remove(a.id());
            a.warEndedAtMap().put(b.id(), now);
            b.warEndedAtMap().put(a.id(), now);
        }
        this.changed();
        if (old != relation) {
            this.events.fire(new RelationChangeEvent(a.id(), b.id(), old, relation));
        }
    }

    /** Adds a treasury history line (newest first), keeping at most {@code max} lines. */
    public void addTreasuryEntry(final Country c, final TreasuryEntry entry, final int max) {
        c.treasuryLog().addFirst(entry);
        while (c.treasuryLog().size() > Math.max(1, max)) {
            c.treasuryLog().removeLast();
        }
        this.changed();
    }

    public void setLastDailyTick(final long millis) {
        this.lastDailyTick = millis;
        this.changed();
    }

    /** Rebuilds the thread-safe snapshots without saving (e.g. after the tribute estimator was set). */
    public void refreshSnapshots() {
        this.rebuildSnapshots();
    }

    /** For batched field changes done by the economy tick (debts, unpaid days): rebuild + save once. */
    public void markChanged() {
        this.changed();
    }

    /** Updates the cached member name (on join); returns whether something changed. */
    public boolean updateMemberName(final UUID player, final String name) {
        final Country c = this.countryOf(player);
        if (c == null) {
            return false;
        }
        final Member m = c.member(player);
        if (m == null || name.equals(m.name())) {
            return false;
        }
        m.name(name);
        final Country partner = this.unionPartnerOf(c);
        if (partner != null && partner.isTitular(player) && partner.member(player) != null) {
            partner.member(player).name(name);
        }
        this.changed();
        return true;
    }

    // ------------------------------------------------------------------ vassals

    public void setTributeEstimator(final ToLongFunction<Country> estimator) {
        this.tributeEstimator = estimator == null ? c -> 0L : estimator;
    }

    /** Makes {@code vassal} the vassal described by {@code vassalage} (null = independent again). */
    public void setVassalage(final Country vassal, final Vassalage vassalage) {
        if (vassalage != null && (vassalage.overlord().equals(vassal.id()) || !this.countries.containsKey(vassalage.overlord()))) {
            throw new IllegalStateException("Invalid overlord");
        }
        vassal.vassalage(vassalage);
        this.changed();
    }

    /** Main thread: vassals of a country, in load order. */
    public List<Country> vassalsOf(final UUID overlord) {
        final List<Country> out = new ArrayList<>();
        for (final Country c : this.countries.values()) {
            if (overlord != null && overlord.equals(c.overlord())) {
                out.add(c);
            }
        }
        return out;
    }

    /** Main thread: all current vassals. */
    public List<Country> allVassals() {
        return this.countries.values().stream().filter(c -> c.vassalage() != null).toList();
    }

    /** Thread-safe. */
    public VassalInfo vassalInfo(final UUID vassal) {
        return vassal == null ? null : this.vassalSnapshots.get(vassal);
    }

    /** Thread-safe. */
    public UUID overlordOf(final UUID country) {
        final VassalInfo info = this.vassalInfo(country);
        return info == null ? null : info.overlord();
    }

    /** Thread-safe. */
    public Set<UUID> vassalIdsOf(final UUID overlord) {
        return overlord == null ? Set.of() : this.vassalIndex.getOrDefault(overlord, Set.of());
    }

    // ------------------------------------------------------------------ personal unions

    public void setRulerListener(final java.util.function.Consumer<Country> listener) {
        this.rulerListener = listener == null ? c -> { } : listener;
    }

    /** Config value used for {@link UnionInfo#breakableAt()} in the snapshots. */
    public void setUnionMinDuration(final long millis) {
        this.unionMinDurationMillis = Math.max(0, millis);
    }

    /**
     * Forms a personal union: the monarch (ruler of {@code home}) takes {@code partner}'s throne as a titular member with
     * the ruler rank, the partner's former ruler becomes an advisor (the regent). An NPC partner keeps its AI ruler.
     */
    public void formUnion(final Country home, final Country partner, final PersonalUnion union) {
        if (home == partner || !home.id().equals(union.home()) || !union.monarch().equals(home.ruler())
            || partner.union() != null || this.unionPartnerOf(home) != null || home.union() != null
            || this.unionPartnerOf(partner) != null || home.npc()
            || home.overlord() != null || partner.overlord() != null
            || home.relationTo(partner.id()) == Relation.WAR || partner.relationTo(home.id()) == Relation.WAR) {
            throw new IllegalStateException("Invalid union");
        }
        if (!partner.npc()) {
            final Member old = partner.rulerMember();
            if (old != null) {
                old.rank(Rank.ADVISOR);
            }
            partner.membersMap().put(union.monarch(), new Member(union.monarch(), union.monarchName(), Rank.RULER,
                System.currentTimeMillis()));
            partner.ruler(union.monarch());
        }
        partner.union(union);
        this.changed();
    }

    /**
     * Ends the union of {@code partner} (no-op without one): the titular monarch leaves, and if he held the throne the
     * successor ({@link UnionRules#successor}) becomes ruler; with nobody left the country falls back to NPC rule.
     * Returns the new ruler member (null when unchanged or when it became an NPC country).
     */
    public Member endUnion(final Country partner) {
        if (partner.union() == null) {
            return null;
        }
        final Member next = this.endUnionInternal(partner);
        this.changed();
        return next;
    }

    private Member endUnionInternal(final Country partner) {
        final PersonalUnion u = partner.union();
        partner.union(null);
        if (u == null || partner.npc()) {
            return null;
        }
        final UUID monarch = u.monarch();
        if (partner.id().equals(this.memberIndex.get(monarch))) {
            return null; // should not happen: a regular member is not titular
        }
        final boolean held = monarch.equals(partner.ruler());
        partner.membersMap().remove(monarch);
        if (!held && partner.isMember(partner.ruler())) {
            return null;
        }
        final Member next = UnionRules.successor(partner, monarch, u.regent());
        if (next != null) {
            next.rank(Rank.RULER);
            partner.ruler(next.uuid());
            return next;
        }
        this.logger.warning("Kraj " + partner.name() + " po końcu unii nie ma członków — przechodzi pod zarząd NPC.");
        partner.membersMap().values().removeIf(m -> m.rank() == Rank.RULER);
        partner.npc(true);
        partner.ruler(Country.npcRulerId(partner.id()));
        partner.npcRulerName(u.regentName().isEmpty() ? "Namiestnik" : u.regentName());
        return null;
    }

    /** Main thread: the other country of {@code c}'s union (null when none). */
    public Country unionPartnerOf(final Country c) {
        if (c == null) {
            return null;
        }
        if (c.union() != null) {
            return this.get(c.union().home());
        }
        for (final Country other : this.countries.values()) {
            if (other.union() != null && c.id().equals(other.union().home())) {
                return other;
            }
        }
        return null;
    }

    /** Main thread: the partner side (holder of the {@link PersonalUnion}) of {@code c}'s union, null when none. */
    public Country unionPartnerSide(final Country c) {
        if (c == null) {
            return null;
        }
        return c.union() != null ? c : this.unionPartnerOf(c);
    }

    /** Thread-safe. */
    public UnionInfo unionInfo(final UUID country) {
        return country == null ? null : this.unionSnapshots.get(country);
    }

    /** Thread-safe: id of the other union country, null when none. */
    public UUID unionPartnerId(final UUID country) {
        final UnionInfo info = this.unionInfo(country);
        return info == null ? null : info.other(country);
    }

    /** Thread-safe: whether {@code player} is the shared ruler of a union {@code country} belongs to. */
    public boolean isUnionMonarch(final UUID player, final UUID country) {
        final UnionInfo info = this.unionInfo(country);
        return info != null && player != null && player.equals(info.monarch());
    }

    /** Thread-safe: every union once. */
    public List<UnionInfo> unionInfos() {
        return this.unionSnapshots.values().stream().distinct().toList();
    }

    private void validateUnions() {
        final Set<UUID> homes = new HashSet<>();
        for (final Country c : this.countries.values()) {
            final PersonalUnion u = c.union();
            if (u == null) {
                continue;
            }
            final Country home = this.countries.get(u.home());
            String problem = null;
            if (home == null || home == c) {
                problem = "kraj macierzysty nie istnieje";
            } else if (home.npc() || home.union() != null) {
                problem = "kraj macierzysty jest NPC albo sam jest partnerem unii";
            } else if (home.overlord() != null || c.overlord() != null
                || home.relationTo(c.id()) == Relation.WAR || c.relationTo(home.id()) == Relation.WAR) {
                problem = "unia koliduje z wojną lub wasalstwem";
            } else if (!u.monarch().equals(home.ruler()) || !home.id().equals(this.memberIndex.get(u.monarch()))) {
                problem = "wspólny władca nie rządzi już krajem macierzystym";
            } else if (!homes.add(home.id())) {
                problem = "kraj macierzysty ma już inną unię";
            } else if (!c.npc() && (!u.monarch().equals(c.ruler()) || c.member(u.monarch()) == null)) {
                problem = "wspólny władca nie jest władcą partnera";
            }
            if (problem != null) {
                this.logger.warning("Unia " + c.name() + ": " + problem + " — unia rozwiązana.");
                this.endUnionInternal(c);
            }
        }
    }

    // ------------------------------------------------------------------ embassies

    /** Opens (or replaces) the embassy of {@code guest} on {@code embassy.chunk()} of {@code embassy.host()}. */
    public void addEmbassy(final Country guest, final Embassy embassy) {
        if (this.get(guest.id()) != guest || embassy.host().equals(guest.id()) || !this.countries.containsKey(embassy.host())
            || !embassy.host().equals(this.ownerAt(embassy.chunk().world(), embassy.chunk().key()))
            || guest.relationTo(embassy.host()) == Relation.WAR
            || !validEmbassySpot(embassy)) {
            throw new IllegalStateException("Invalid embassy host");
        }
        final EmbassyInfo occupied = this.embassyAt(embassy.chunk().world(), embassy.chunk().key());
        if (occupied != null && !occupied.guest().equals(guest.id())) {
            throw new IllegalStateException("Embassy chunk occupied");
        }
        guest.embassiesMap().put(embassy.host(), embassy);
        this.changed();
    }

    /** Removes the embassy of {@code guest} in {@code host}; returns it (null when there was none). */
    public Embassy removeEmbassy(final Country guest, final UUID host) {
        final Embassy removed = guest.embassiesMap().remove(host);
        if (removed != null) {
            this.changed();
        }
        return removed;
    }

    /** Sets the closing time of an expelled embassy (0 = not expelled). */
    public void setEmbassyClosesAt(final Country guest, final UUID host, final long at) {
        final Embassy e = guest.embassiesMap().get(host);
        if (e != null) {
            guest.embassiesMap().put(host, e.withClosesAt(at));
            this.changed();
        }
    }

    /** Thread-safe: the embassy standing on a chunk, null when none. */
    public EmbassyInfo embassyAt(final String world, final long chunkKey) {
        final Map<Long, EmbassyInfo> index = this.embassyIndex.get(world);
        return index == null ? null : index.get(chunkKey);
    }

    /** Thread-safe: all embassies. */
    public List<EmbassyInfo> embassyInfos() {
        return this.embassySnapshots;
    }

    /** Main thread: guests with an embassy in {@code host}. */
    public List<Country> embassyGuestsIn(final UUID host) {
        final List<Country> out = new ArrayList<>();
        for (final Country c : this.countries.values()) {
            if (host != null && c.embassiesMap().containsKey(host)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean validEmbassySpot(final Embassy e) {
        final SpawnPoint s = e.spot();
        return s != null && e.chunk().world().equals(s.world())
            && Double.isFinite(s.x()) && Double.isFinite(s.y()) && Double.isFinite(s.z())
            && Float.isFinite(s.yaw()) && Float.isFinite(s.pitch())
            && ((int) Math.floor(s.x()) >> 4) == e.chunk().x()
            && ((int) Math.floor(s.z()) >> 4) == e.chunk().z();
    }

    private void validateEmbassies() {
        final Set<ChunkPos> used = new HashSet<>();
        for (final Country c : this.countries.values()) {
            c.embassiesMap().entrySet().removeIf(entry -> {
                final Embassy e = entry.getValue();
                final boolean valid = entry.getKey().equals(e.host()) && !e.host().equals(c.id())
                    && this.countries.containsKey(e.host())
                    && e.host().equals(this.ownerAt(e.chunk().world(), e.chunk().key()))
                    && c.relationTo(e.host()) != Relation.WAR
                    && this.get(e.host()).relationTo(c.id()) != Relation.WAR
                    && (!e.expelled() || e.closesAt() > System.currentTimeMillis())
                    && validEmbassySpot(e)
                    && used.add(e.chunk());
                if (!valid) {
                    this.logger.warning("Ambasada " + c.name() + " na " + e.chunk() + " jest nieważna — usunięta.");
                }
                return !valid;
            });
        }
    }

    // ------------------------------------------------------------------ persistent invitations

    public void addStoredInvite(final StoredInvite invite) {
        this.storedInvites.add(invite);
        this.changed();
    }

    public boolean hasStoredInvite(final UUID player, final UUID country) {
        return this.storedInvites.has(player, country, System.currentTimeMillis());
    }

    public List<StoredInvite> storedInvitesOf(final UUID player) {
        return this.storedInvites.of(player, System.currentTimeMillis());
    }

    public void removeStoredInvite(final UUID player, final UUID country) {
        if (this.storedInvites.remove(player, country)) {
            this.changed();
        }
    }

    public void removeStoredInvitesOf(final UUID player) {
        if (this.storedInvites.removePlayer(player)) {
            this.changed();
        }
    }

    /** Drops expired invitations (called periodically); saves only when something changed. */
    public void pruneStoredInvites() {
        if (this.storedInvites.prune(System.currentTimeMillis())) {
            this.changed();
        }
    }

    /** Snapshot for saving. */
    public List<StoredInvite> storedInvites() {
        return this.storedInvites.all();
    }

    // ------------------------------------------------------------------ internals

    private void fireChunk(final UUID oldOwner, final UUID newOwner, final String world, final long key, final ClaimCause cause) {
        this.events.fire(new ChunkOwnerChangeEvent(oldOwner, newOwner, world, ChunkKeys.x(key), ChunkKeys.z(key), cause));
    }

    private Map<Long, UUID> worldIndex(final String world) {
        return this.claimIndex.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
    }

    private void changed() {
        this.rebuildSnapshots();
        this.listener.onCountriesChanged();
    }

    private void rebuildSnapshots() {
        final Map<UUID, KrajInfo> map = new HashMap<>();
        for (final Country c : this.countries.values()) {
            final Map<UUID, Rank> members = new HashMap<>();
            for (final Member m : c.members()) {
                members.put(m.uuid(), m.rank());
            }
            final Set<UUID> allies = new HashSet<>();
            final Set<UUID> enemies = new HashSet<>();
            c.relationsMap().forEach((id, rel) -> {
                if (rel == Relation.ALLIANCE) {
                    allies.add(id);
                } else if (rel == Relation.WAR) {
                    enemies.add(id);
                }
            });
            final ChunkPos capital = c.capital();
            map.put(c.id(), new KrajInfo(c.id(), c.name(), c.textColor(), c.ruler(), c.rulerName(),
                Map.copyOf(members), c.claimCount(), Set.copyOf(allies), Set.copyOf(enemies),
                c.npc(), capital.world(), capital.x(), capital.z(), c.taxPerCitizen(), c.treasuryAccount()));
        }
        this.snapshots = Map.copyOf(map);

        final Map<UUID, VassalInfo> vassals = new HashMap<>();
        final Map<UUID, Set<UUID>> index = new HashMap<>();
        for (final Country c : this.countries.values()) {
            final Vassalage v = c.vassalage();
            if (v == null) {
                continue;
            }
            long estimate;
            try {
                estimate = this.tributeEstimator.applyAsLong(c);
            } catch (final RuntimeException e) {
                estimate = 0L;
            }
            vassals.put(c.id(), new VassalInfo(c.id(), v.overlord(), v.since(), v.until(), v.tributeShare(), v.tributeMin(),
                estimate, v.debt(), v.debtDays(), v.tributePaid()));
            index.computeIfAbsent(v.overlord(), k -> new HashSet<>()).add(c.id());
        }
        final Map<UUID, Set<UUID>> frozen = new HashMap<>();
        index.forEach((k, set) -> frozen.put(k, Set.copyOf(set)));
        this.vassalSnapshots = Map.copyOf(vassals);
        this.vassalIndex = Map.copyOf(frozen);

        final Map<UUID, UnionInfo> unions = new HashMap<>();
        final Map<String, Map<Long, EmbassyInfo>> embassies = new HashMap<>();
        final List<EmbassyInfo> embassyList = new ArrayList<>();
        for (final Country c : this.countries.values()) {
            final PersonalUnion u = c.union();
            if (u != null) {
                final Member regent = u.regent() == null ? null : c.member(u.regent());
                final UnionInfo info = new UnionInfo(u.home(), c.id(), u.monarch(), u.monarchName(), u.since(), u.regent(),
                    regent != null ? regent.name() : u.regentName(), u.since() + this.unionMinDurationMillis);
                unions.put(u.home(), info);
                unions.put(c.id(), info);
            }
            for (final Embassy e : c.embassiesMap().values()) {
                final EmbassyInfo info = new EmbassyInfo(c.id(), e.host(), e.chunk().world(), e.chunk().x(), e.chunk().z(),
                    e.spot().x(), e.spot().y(), e.spot().z(), e.openedAt(), e.closesAt());
                embassies.computeIfAbsent(e.chunk().world(), w -> new HashMap<>()).put(e.chunk().key(), info);
                embassyList.add(info);
            }
        }
        final Map<String, Map<Long, EmbassyInfo>> frozenEmbassies = new HashMap<>();
        embassies.forEach((w, m) -> frozenEmbassies.put(w, Map.copyOf(m)));
        this.unionSnapshots = Map.copyOf(unions);
        this.embassyIndex = Map.copyOf(frozenEmbassies);
        this.embassySnapshots = List.copyOf(embassyList);
    }

    private static String lower(final String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
