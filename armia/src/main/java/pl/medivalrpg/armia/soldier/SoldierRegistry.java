// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.soldier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.armia.model.SoldierRecord;

/**
 * All soldiers, indexed by id, by brain entity and by country. Main thread only, except {@link #count} and
 * {@link #countByType} which read a snapshot rebuilt on every change (safe from any thread, as ArmiaApi promises).
 */
public final class SoldierRegistry {

    private final Map<UUID, Soldier> byId = new LinkedHashMap<>();
    private final Map<UUID, Soldier> byBrain = new HashMap<>();
    private final Map<UUID, Set<Soldier>> byCountry = new HashMap<>();
    private volatile Map<UUID, Map<String, Integer>> snapshot = Map.of();
    private boolean dirty;

    public Soldier add(final SoldierRecord rec) {
        rec.normalize();
        final Soldier s = new Soldier(rec);
        this.byId.put(rec.id, s);
        this.byCountry.computeIfAbsent(rec.country, k -> new LinkedHashSet<>()).add(s);
        if (rec.brain != null) {
            this.byBrain.put(rec.brain, s);
        }
        this.changed();
        return s;
    }

    public void remove(final Soldier s) {
        this.byId.remove(s.id());
        if (s.rec.brain != null) {
            this.byBrain.remove(s.rec.brain);
        }
        final Set<Soldier> set = this.byCountry.get(s.country());
        if (set != null) {
            set.remove(s);
            if (set.isEmpty()) {
                this.byCountry.remove(s.country());
            }
        }
        this.changed();
    }

    /** Re-index after the brain entity changed. */
    public void setBrain(final Soldier s, final UUID brain) {
        if (s.rec.brain != null) {
            this.byBrain.remove(s.rec.brain);
        }
        s.rec.brain = brain;
        if (brain != null) {
            this.byBrain.put(brain, s);
        }
        this.dirty = true;
    }

    public Soldier get(final UUID id) {
        return id == null ? null : this.byId.get(id);
    }

    public Soldier byBrain(final UUID entity) {
        return entity == null ? null : this.byBrain.get(entity);
    }

    public Collection<Soldier> all() {
        return Collections.unmodifiableCollection(this.byId.values());
    }

    public List<Soldier> ofCountry(final UUID country) {
        final Set<Soldier> set = this.byCountry.get(country);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    public int size() {
        return this.byId.size();
    }

    /** Thread-safe. */
    public int count(final UUID country) {
        final Map<String, Integer> m = this.snapshot.get(country);
        if (m == null) {
            return 0;
        }
        int sum = 0;
        for (final int v : m.values()) {
            sum += v;
        }
        return sum;
    }

    /** Thread-safe. */
    public Map<String, Integer> countByType(final UUID country) {
        return this.snapshot.getOrDefault(country, Map.of());
    }

    public List<SoldierRecord> records() {
        final List<SoldierRecord> list = new ArrayList<>(this.byId.size());
        for (final Soldier s : this.byId.values()) {
            list.add(s.rec);
        }
        return list;
    }

    /** A soldier changed its type (rearm): refresh the per-type counts. */
    public void retyped() {
        this.changed();
    }

    public void markDirty() {
        this.dirty = true;
    }

    /** True once after any change (for the autosave). */
    public boolean consumeDirty() {
        final boolean d = this.dirty;
        this.dirty = false;
        return d;
    }

    private void changed() {
        this.dirty = true;
        final Map<UUID, Map<String, Integer>> snap = new HashMap<>();
        for (final Map.Entry<UUID, Set<Soldier>> e : this.byCountry.entrySet()) {
            final Map<String, Integer> types = new LinkedHashMap<>();
            for (final Soldier s : e.getValue()) {
                types.merge(s.rec.type, 1, Integer::sum);
            }
            snap.put(e.getKey(), Map.copyOf(types));
        }
        this.snapshot = Map.copyOf(snap);
    }
}
