// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.logic.QueueRules;

/**
 * In-memory registry of everything Ludnosc persists: citizens, buildings, construction sites, per-country state.
 * Written on the main thread; concurrent maps and immutable records make the read API safe from other threads.
 * Every count (population, housing...) is computed from here — the world is never scanned.
 */
public final class LudnoscData {

    private final Map<UUID, CitizenRecord> citizens = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> citizensByCountry = new ConcurrentHashMap<>();
    private final Map<ChunkRef, Set<UUID>> citizensByChunk = new ConcurrentHashMap<>();
    private final Map<UUID, Building> buildings = new ConcurrentHashMap<>();
    private final Map<ChunkRef, Set<UUID>> buildingsByChunk = new ConcurrentHashMap<>();
    private final Map<UUID, ConstructionSite> sites = new ConcurrentHashMap<>();
    /** Construction queue per country, in order (copy-on-write: safe to read from any thread). */
    private final Map<UUID, List<QueuedOrder>> queues = new ConcurrentHashMap<>();
    private final Map<UUID, CountryState> countries = new ConcurrentHashMap<>();
    /** Entities to delete when their chunk loads (citizens that left / were conscripted while unloaded) → expiry millis. */
    private final Map<UUID, Long> tombstones = new ConcurrentHashMap<>();
    private volatile LocalDate lastDaily;
    /** Season cycle: anchor (epoch millis, 0 = not set yet) and the last season serial announced. */
    private volatile long seasonAnchor;
    private volatile long seasonSerial = Long.MIN_VALUE;
    private volatile boolean dirty;

    // ------------------------------------------------------------------ dirty flag

    public void markDirty() {
        this.dirty = true;
    }

    public boolean takeDirty() {
        final boolean d = this.dirty;
        this.dirty = false;
        return d;
    }

    // ------------------------------------------------------------------ citizens

    public CitizenRecord citizen(final UUID entity) {
        return this.citizens.get(entity);
    }

    public Collection<CitizenRecord> allCitizens() {
        return this.citizens.values();
    }

    public void putCitizen(final CitizenRecord record) {
        final CitizenRecord old = this.citizens.put(record.entityId(), record);
        if (old != null && !old.countryId().equals(record.countryId())) {
            final Set<UUID> set = this.citizensByCountry.get(old.countryId());
            if (set != null) {
                set.remove(old.entityId());
            }
        }
        if (old != null && !old.chunk().equals(record.chunk())) {
            this.unindexChunk(old);
        }
        this.citizensByCountry.computeIfAbsent(record.countryId(), k -> ConcurrentHashMap.newKeySet()).add(record.entityId());
        this.citizensByChunk.computeIfAbsent(record.chunk(), k -> ConcurrentHashMap.newKeySet()).add(record.entityId());
        this.markDirty();
    }

    private void unindexChunk(final CitizenRecord old) {
        final Set<UUID> set = this.citizensByChunk.get(old.chunk());
        if (set != null) {
            set.remove(old.entityId());
            if (set.isEmpty()) {
                this.citizensByChunk.remove(old.chunk());
            }
        }
    }

    public CitizenRecord removeCitizen(final UUID entity) {
        final CitizenRecord old = this.citizens.remove(entity);
        if (old != null) {
            final Set<UUID> set = this.citizensByCountry.get(old.countryId());
            if (set != null) {
                set.remove(entity);
            }
            this.unindexChunk(old);
            this.markDirty();
        }
        return old;
    }

    /** Citizens whose last known position is in the chunk. */
    public List<CitizenRecord> citizensIn(final ChunkRef chunk) {
        final Set<UUID> set = this.citizensByChunk.get(chunk);
        if (set == null) {
            return List.of();
        }
        final List<CitizenRecord> out = new ArrayList<>();
        for (final UUID id : set) {
            final CitizenRecord r = this.citizens.get(id);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    public int population(final UUID country) {
        final Set<UUID> set = this.citizensByCountry.get(country);
        return set == null ? 0 : set.size();
    }

    public List<UUID> citizenIds(final UUID country) {
        final Set<UUID> set = this.citizensByCountry.get(country);
        return set == null ? List.of() : List.copyOf(set);
    }

    public List<CitizenRecord> citizensOf(final UUID country) {
        final List<CitizenRecord> out = new ArrayList<>();
        for (final UUID id : this.citizenIds(country)) {
            final CitizenRecord r = this.citizens.get(id);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** Countries that have Ludnosc data (citizens, buildings or state). */
    public Set<UUID> knownCountries() {
        final Set<UUID> out = ConcurrentHashMap.newKeySet();
        this.citizensByCountry.forEach((k, v) -> {
            if (!v.isEmpty()) {
                out.add(k);
            }
        });
        out.addAll(this.countries.keySet());
        for (final Building b : this.buildings.values()) {
            if (b.countryId() != null) {
                out.add(b.countryId());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ buildings

    public Building building(final UUID id) {
        return this.buildings.get(id);
    }

    public Collection<Building> allBuildings() {
        return this.buildings.values();
    }

    public void putBuilding(final Building b) {
        final Building old = this.buildings.put(b.id(), b);
        if (old != null) {
            for (final ChunkRef c : old.box().chunks()) {
                final Set<UUID> set = this.buildingsByChunk.get(c);
                if (set != null) {
                    set.remove(old.id());
                }
            }
        }
        for (final ChunkRef c : b.box().chunks()) {
            this.buildingsByChunk.computeIfAbsent(c, k -> ConcurrentHashMap.newKeySet()).add(b.id());
        }
        this.markDirty();
    }

    public Building removeBuilding(final UUID id) {
        final Building old = this.buildings.remove(id);
        if (old != null) {
            for (final ChunkRef c : old.box().chunks()) {
                final Set<UUID> set = this.buildingsByChunk.get(c);
                if (set != null) {
                    set.remove(id);
                    if (set.isEmpty()) {
                        this.buildingsByChunk.remove(c);
                    }
                }
            }
            this.markDirty();
        }
        return old;
    }

    /** Buildings overlapping a chunk. */
    public List<Building> buildingsIn(final ChunkRef chunk) {
        final Set<UUID> set = this.buildingsByChunk.get(chunk);
        if (set == null) {
            return List.of();
        }
        final List<Building> out = new ArrayList<>();
        for (final UUID id : set) {
            final Building b = this.buildings.get(id);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    /** Building containing a block, if any. */
    public Building buildingAt(final String world, final int x, final int y, final int z) {
        for (final Building b : this.buildingsIn(ChunkRef.ofBlock(world, x, z))) {
            if (b.box().contains(world, x, y, z)) {
                return b;
            }
        }
        return null;
    }

    public List<Building> buildingsOf(final UUID country) {
        final List<Building> out = new ArrayList<>();
        for (final Building b : this.buildings.values()) {
            if (country.equals(b.countryId())) {
                out.add(b);
            }
        }
        return out;
    }

    /** Template id a working building registered by the players stands for (null = none), see PlayerBuildingService. */
    private volatile java.util.function.Function<Building, String> alias = b -> null;

    public void setAlias(final java.util.function.Function<Building, String> alias) {
        this.alias = alias == null ? b -> null : alias;
    }

    /**
     * At least one finished building of this type (template id, case-insensitive) owned by the country; a working
     * building registered by the players counts as the template of its kind ({@code wlasny_koszary} → {@code koszary}).
     */
    public boolean hasBuilding(final UUID country, final String type) {
        if (country == null || type == null) {
            return false;
        }
        for (final Building b : this.buildings.values()) {
            if (country.equals(b.countryId()) && (type.equalsIgnoreCase(b.type()) || type.equalsIgnoreCase(this.alias.apply(b)))) {
                return true;
            }
        }
        return false;
    }

    /** Beds of housing buildings owned by the country. */
    public int housing(final UUID country) {
        int beds = 0;
        for (final Building b : this.buildings.values()) {
            if (country.equals(b.countryId())) {
                beds += b.housingBeds();
            }
        }
        return beds;
    }

    // ------------------------------------------------------------------ sites

    public ConstructionSite site(final UUID id) {
        return this.sites.get(id);
    }

    public Collection<ConstructionSite> allSites() {
        return this.sites.values();
    }

    public void putSite(final ConstructionSite site) {
        this.sites.put(site.id, site);
        this.markDirty();
    }

    public ConstructionSite removeSite(final UUID id) {
        final ConstructionSite s = this.sites.remove(id);
        if (s != null) {
            this.markDirty();
        }
        return s;
    }

    public List<ConstructionSite> sitesOf(final UUID country) {
        final List<ConstructionSite> out = new ArrayList<>();
        for (final ConstructionSite s : this.sites.values()) {
            if (country.equals(s.countryId)) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ construction queue

    private List<QueuedOrder> queueList(final UUID country) {
        return this.queues.computeIfAbsent(country, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    /** Snapshot of the country's queue, first = next to start. */
    public List<QueuedOrder> queue(final UUID country) {
        final List<QueuedOrder> q = country == null ? null : this.queues.get(country);
        return q == null ? List.of() : List.copyOf(q);
    }

    public int queueSize(final UUID country) {
        final List<QueuedOrder> q = country == null ? null : this.queues.get(country);
        return q == null ? 0 : q.size();
    }

    /** Every queued order of every country. */
    public List<QueuedOrder> allQueued() {
        final List<QueuedOrder> out = new ArrayList<>();
        this.queues.values().forEach(out::addAll);
        return out;
    }

    /** Countries with a non-empty queue. */
    public Set<UUID> queuedCountries() {
        final Set<UUID> out = new java.util.HashSet<>();
        this.queues.forEach((k, v) -> {
            if (!v.isEmpty()) {
                out.add(k);
            }
        });
        return out;
    }

    public void enqueue(final QueuedOrder order) {
        this.queueList(order.countryId).add(order);
        this.markDirty();
    }

    /** Puts an order right behind another order of the same country (at the end when that one is gone). */
    public void enqueueAfter(final QueuedOrder anchor, final QueuedOrder order) {
        final List<QueuedOrder> q = this.queueList(order.countryId);
        final int i = q.indexOf(anchor);
        if (i < 0) {
            q.add(order);
        } else {
            q.add(i + 1, order);
        }
        this.markDirty();
    }

    /** Puts an order at the front of the queue (repairs). */
    public void enqueueFront(final QueuedOrder order) {
        this.queueList(order.countryId).add(0, order);
        this.markDirty();
    }

    /** Removes the order at a 0-based position; null when out of range. */
    public QueuedOrder removeQueuedAt(final UUID country, final int index) {
        final List<QueuedOrder> q = this.queues.get(country);
        if (q == null || index < 0 || index >= q.size()) {
            return null;
        }
        final QueuedOrder o = q.remove(index);
        this.markDirty();
        return o;
    }

    public boolean removeQueued(final QueuedOrder order) {
        final List<QueuedOrder> q = this.queues.get(order.countryId);
        final boolean removed = q != null && q.removeIf(o -> o.id.equals(order.id));
        if (removed) {
            this.markDirty();
        }
        return removed;
    }

    /** Moves the order at {@code index} by {@code delta} places (−1 = up / earlier). */
    public boolean moveQueued(final UUID country, final int index, final int delta) {
        final List<QueuedOrder> q = this.queues.get(country);
        if (q == null) {
            return false;
        }
        final List<QueuedOrder> copy = new ArrayList<>(q);
        if (!QueueRules.move(copy, index, delta)) {
            return false;
        }
        q.clear();
        q.addAll(copy);
        this.markDirty();
        return true;
    }

    /** Removes and returns the whole queue of a country. */
    public List<QueuedOrder> clearQueue(final UUID country) {
        final List<QueuedOrder> q = this.queues.remove(country);
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        this.markDirty();
        return List.copyOf(q);
    }

    // ------------------------------------------------------------------ countries

    public CountryState state(final UUID country) {
        return this.countries.computeIfAbsent(country, k -> {
            this.markDirty();
            return new CountryState();
        });
    }

    public CountryState stateIfPresent(final UUID country) {
        return this.countries.get(country);
    }

    public Map<UUID, CountryState> states() {
        return this.countries;
    }

    public void removeState(final UUID country) {
        if (this.countries.remove(country) != null) {
            this.markDirty();
        }
    }

    // ------------------------------------------------------------------ tombstones

    public void tombstone(final UUID entity, final long expiresAt) {
        this.tombstones.put(entity, expiresAt);
        this.markDirty();
    }

    /** True (and forgets it) when the entity was marked for removal. */
    public boolean takeTombstone(final UUID entity) {
        final boolean had = this.tombstones.remove(entity) != null;
        if (had) {
            this.markDirty();
        }
        return had;
    }

    public Map<UUID, Long> tombstones() {
        return this.tombstones;
    }

    public void purgeTombstones(final long now) {
        if (this.tombstones.values().removeIf(t -> t < now)) {
            this.markDirty();
        }
    }

    // ------------------------------------------------------------------ daily

    public LocalDate lastDaily() {
        return this.lastDaily;
    }

    public void setLastDaily(final LocalDate day) {
        this.lastDaily = day;
        this.markDirty();
    }

    // ------------------------------------------------------------------ seasons

    public long seasonAnchor() {
        return this.seasonAnchor;
    }

    public void setSeasonAnchor(final long anchor) {
        this.seasonAnchor = anchor;
        this.markDirty();
    }

    /** Serial of the last season announced ({@link Long#MIN_VALUE} = none yet). */
    public long seasonSerial() {
        return this.seasonSerial;
    }

    public void setSeasonSerial(final long serial) {
        this.seasonSerial = serial;
        this.markDirty();
    }

    /** Removes everything (used before loading). */
    public void clear() {
        this.citizens.clear();
        this.citizensByCountry.clear();
        this.citizensByChunk.clear();
        this.buildings.clear();
        this.buildingsByChunk.clear();
        this.sites.clear();
        this.queues.clear();
        this.countries.clear();
        this.tombstones.clear();
        this.lastDaily = null;
        this.seasonAnchor = 0;
        this.seasonSerial = Long.MIN_VALUE;
    }
}
