// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import pl.medivalrpg.postep.model.Discovery;

/**
 * The discovery tree: lookup, validation (unknown requirements, requirements from a later era, cycles) and the status
 * of each discovery for a country. Immutable, pure Java.
 */
public final class TechTree {

    /** Status of a discovery for one country. */
    public enum Status {
        /** Already discovered. */
        OWNED,
        /** Can be discovered now (era reached, all requirements owned). */
        AVAILABLE,
        /** Era reached, but some requirements are missing. */
        LOCKED_REQUIREMENTS,
        /** Belongs to a later era. */
        LOCKED_ERA
    }

    private final Map<String, Discovery> byId;

    private TechTree(final Map<String, Discovery> byId) {
        this.byId = byId;
    }

    /** Keeps the given order; for a duplicated id the first one wins. */
    public static TechTree of(final Collection<Discovery> discoveries) {
        final Map<String, Discovery> map = new LinkedHashMap<>();
        for (final Discovery d : discoveries) {
            map.putIfAbsent(d.id(), d);
        }
        return new TechTree(Collections.unmodifiableMap(map));
    }

    public Collection<Discovery> all() {
        return this.byId.values();
    }

    public Optional<Discovery> get(final String id) {
        return Optional.ofNullable(id == null ? null : this.byId.get(id));
    }

    public boolean contains(final String id) {
        return this.byId.containsKey(id);
    }

    public int size() {
        return this.byId.size();
    }

    public List<Discovery> inEra(final int era) {
        return this.byId.values().stream().filter(d -> d.era() == era).toList();
    }

    /** Discoveries of {@code era} needed to enter the next era. */
    public List<Discovery> requiredToLeave(final int era) {
        return this.byId.values().stream().filter(d -> d.era() == era && d.requiredForEra()).toList();
    }

    public Status status(final Discovery d, final Set<String> owned, final int era) {
        if (owned.contains(d.id())) {
            return Status.OWNED;
        }
        if (d.era() > era) {
            return Status.LOCKED_ERA;
        }
        return owned.containsAll(d.requires()) ? Status.AVAILABLE : Status.LOCKED_REQUIREMENTS;
    }

    /** Available discoveries in tree order. */
    public List<Discovery> available(final Set<String> owned, final int era) {
        return this.byId.values().stream().filter(d -> this.status(d, owned, era) == Status.AVAILABLE).toList();
    }

    /** Direct requirements not owned yet. */
    public List<String> missingRequirements(final Discovery d, final Set<String> owned) {
        return d.requires().stream().filter(r -> !owned.contains(r)).toList();
    }

    /**
     * Every not-owned discovery needed (transitively) to get {@code target}, prerequisites first, ending with the
     * target itself. Unknown ids are skipped.
     */
    public List<Discovery> pathTo(final String target, final Set<String> owned) {
        final LinkedHashSet<String> order = new LinkedHashSet<>();
        this.collect(target, owned, order, new HashSet<>());
        final List<Discovery> out = new ArrayList<>();
        for (final String id : order) {
            out.add(this.byId.get(id));
        }
        return out;
    }

    private void collect(final String id, final Set<String> owned, final LinkedHashSet<String> order, final Set<String> visiting) {
        final Discovery d = this.byId.get(id);
        if (d == null || owned.contains(id) || order.contains(id) || !visiting.add(id)) {
            return;
        }
        for (final String r : d.requires()) {
            this.collect(r, owned, order, visiting);
        }
        order.add(id);
    }

    /** Discoveries that require {@code id} directly or transitively (removed together with it by an admin). */
    public Set<String> dependents(final String id) {
        final Set<String> out = new LinkedHashSet<>();
        final Deque<String> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty()) {
            final String current = queue.poll();
            for (final Discovery d : this.byId.values()) {
                if (d.requires().contains(current) && out.add(d.id())) {
                    queue.add(d.id());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ validation

    /** A cycle in the requirements as a list of ids ({@code a → b → a}), empty when the tree is acyclic. */
    public Optional<List<String>> findCycle() {
        final Map<String, Integer> state = new HashMap<>(); // 1 = on the stack, 2 = done
        for (final String id : this.byId.keySet()) {
            final Optional<List<String>> cycle = this.dfs(id, state, new ArrayList<>());
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private Optional<List<String>> dfs(final String id, final Map<String, Integer> state, final List<String> stack) {
        final Integer s = state.get(id);
        if (s != null && s == 2) {
            return Optional.empty();
        }
        if (s != null && s == 1) {
            final List<String> cycle = new ArrayList<>(stack.subList(stack.indexOf(id), stack.size()));
            cycle.add(id);
            return Optional.of(cycle);
        }
        final Discovery d = this.byId.get(id);
        if (d == null) {
            return Optional.empty();
        }
        state.put(id, 1);
        stack.add(id);
        for (final String r : d.requires()) {
            final Optional<List<String>> c = this.dfs(r, state, stack);
            if (c.isPresent()) {
                return c;
            }
        }
        stack.remove(stack.size() - 1);
        state.put(id, 2);
        return Optional.empty();
    }

    /** Polish descriptions of every problem; empty when the tree is valid for eras {@code 1..maxEra}. */
    public List<String> validate(final int maxEra) {
        final List<String> problems = new ArrayList<>();
        for (final Discovery d : this.byId.values()) {
            if (d.era() < 1 || d.era() > maxEra) {
                problems.add("odkrycie " + d.id() + ": epoka " + d.era() + " poza zakresem 1–" + maxEra);
            }
            if (d.cost() <= 0) {
                problems.add("odkrycie " + d.id() + ": koszt musi być dodatni");
            }
            for (final String r : d.requires()) {
                final Discovery req = this.byId.get(r);
                if (r.equals(d.id())) {
                    problems.add("odkrycie " + d.id() + " wymaga samego siebie");
                } else if (req == null) {
                    problems.add("odkrycie " + d.id() + ": nieznane wymaganie " + r);
                } else if (req.era() > d.era()) {
                    problems.add("odkrycie " + d.id() + " (epoka " + d.era() + ") wymaga " + r + " z późniejszej epoki " + req.era());
                }
            }
        }
        this.findCycle().ifPresent(c -> problems.add("cykl wymagań: " + String.join(" → ", c)));
        return problems;
    }

    /**
     * A usable tree: drops discoveries with an era out of range, a non-positive cost, unknown or later-era
     * requirements, members of cycles, and (repeatedly) everything that depended on a dropped discovery.
     */
    public TechTree sanitized(final int maxEra) {
        final Map<String, Discovery> keep = new LinkedHashMap<>(this.byId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final Discovery d : List.copyOf(keep.values())) {
                boolean bad = d.era() < 1 || d.era() > maxEra || d.cost() <= 0;
                for (final String r : d.requires()) {
                    final Discovery req = keep.get(r);
                    if (req == null || req.era() > d.era() || r.equals(d.id())) {
                        bad = true;
                    }
                }
                if (bad) {
                    keep.remove(d.id());
                    changed = true;
                }
            }
            final Optional<List<String>> cycle = new TechTree(keep).findCycle();
            if (cycle.isPresent()) {
                cycle.get().forEach(keep::remove);
                changed = true;
            }
        }
        return new TechTree(Collections.unmodifiableMap(keep));
    }
}
