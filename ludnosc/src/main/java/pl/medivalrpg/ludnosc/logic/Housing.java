// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Permanent home and bed of every citizen (pure, unit tested). A bed is a slot {@code (house, index)} with
 * {@code index < beds}; no bed is ever given twice. Rules, in order:
 * <ol>
 *   <li>valid assignments stay (the house still exists, the index is still a bed, nobody else took it);</li>
 *   <li>a household (couple + children) lives together: when its members are split or homeless, the house where most of
 *       them already live — or the nearest house with room for all — takes the whole household;</li>
 *   <li>when no house has room for the whole household, the members keep their beds and the rest get any free bed,
 *       nearest to the household first;</li>
 *   <li>everybody else is homeless (visible in the statistics).</li>
 * </ol>
 * The result is deterministic for the same input (ordering by ids), so repeated runs do not move anybody.
 */
public final class Housing {

    private Housing() {
    }

    /** A housing building. */
    public record Home(UUID id, int beds, int x, int z) {
    }

    /**
     * A citizen.
     *
     * @param household household key (see {@link Family#households}); null = alone
     * @param home      currently assigned house (null = none)
     * @param bed       currently assigned bed index (-1 = none)
     */
    public record Resident(UUID id, UUID household, UUID home, int bed, int x, int z) {
    }

    /** One bed. */
    public record Slot(UUID home, int bed) {
    }

    /** Assignments and the homeless (in id order). */
    public record Result(Map<UUID, Slot> slots, List<UUID> homeless) {

        public int housed() {
            return this.slots.size();
        }

        /** Residents of a house. */
        public int residentsOf(final UUID home) {
            int n = 0;
            for (final Slot s : this.slots.values()) {
                if (s.home().equals(home)) {
                    n++;
                }
            }
            return n;
        }
    }

    public static Result assign(final List<Resident> residents, final List<Home> homes) {
        final Map<UUID, Home> byId = new LinkedHashMap<>();
        final List<Home> sortedHomes = new ArrayList<>(homes);
        sortedHomes.sort(Comparator.comparing(h -> h.id().toString()));
        for (final Home h : sortedHomes) {
            if (h != null && h.id() != null && h.beds() > 0) {
                byId.put(h.id(), h);
            }
        }
        final Map<UUID, UUID[]> beds = new HashMap<>();
        for (final Home h : byId.values()) {
            beds.put(h.id(), new UUID[h.beds()]);
        }
        final List<Resident> people = new ArrayList<>(residents);
        people.sort(Comparator.comparing(r -> r.id().toString()));
        final Map<UUID, Slot> slots = new LinkedHashMap<>();
        // 1. keep valid beds
        for (final Resident r : people) {
            if (r.home() == null || r.bed() < 0) {
                continue;
            }
            final UUID[] b = beds.get(r.home());
            if (b != null && r.bed() < b.length && b[r.bed()] == null) {
                b[r.bed()] = r.id();
                slots.put(r.id(), new Slot(r.home(), r.bed()));
            }
        }
        // 2. households
        final Map<UUID, List<Resident>> households = new LinkedHashMap<>();
        for (final Resident r : people) {
            households.computeIfAbsent(r.household() == null ? r.id() : r.household(), k -> new ArrayList<>()).add(r);
        }
        final List<List<Resident>> order = new ArrayList<>(households.values());
        // households with somebody already housed first (they keep their house), then bigger ones, then by id
        order.sort(Comparator.<List<Resident>>comparingInt(h -> anyHoused(h, slots) ? 0 : 1)
            .thenComparing(h -> -h.size())
            .thenComparing(h -> h.get(0).id().toString()));
        for (final List<Resident> hh : order) {
            placeHousehold(hh, byId, beds, slots);
        }
        // a household that moved together may have freed beds after an earlier one stayed partly homeless
        for (final List<Resident> hh : order) {
            if (hh.stream().anyMatch(r -> !slots.containsKey(r.id()))) {
                placeHousehold(hh, byId, beds, slots);
            }
        }
        // nobody stays homeless while any bed is free (no further moves: just fill the gaps)
        for (final Resident r : people) {
            if (slots.containsKey(r.id())) {
                continue;
            }
            for (final Home h : byId.values()) {
                final UUID[] b = beds.get(h.id());
                final int bed = firstFree(b);
                if (bed >= 0) {
                    b[bed] = r.id();
                    slots.put(r.id(), new Slot(h.id(), bed));
                    break;
                }
            }
        }
        final List<UUID> homeless = new ArrayList<>();
        for (final Resident r : people) {
            if (!slots.containsKey(r.id())) {
                homeless.add(r.id());
            }
        }
        return new Result(java.util.Collections.unmodifiableMap(slots), List.copyOf(homeless));
    }

    private static boolean anyHoused(final List<Resident> hh, final Map<UUID, Slot> slots) {
        for (final Resident r : hh) {
            if (slots.containsKey(r.id())) {
                return true;
            }
        }
        return false;
    }

    private static void placeHousehold(final List<Resident> hh, final Map<UUID, Home> homes, final Map<UUID, UUID[]> beds,
                                       final Map<UUID, Slot> slots) {
        // already together?
        UUID common = null;
        boolean together = true;
        for (final Resident r : hh) {
            final Slot s = slots.get(r.id());
            if (s == null) {
                together = false;
                break;
            }
            if (common == null) {
                common = s.home();
            } else if (!common.equals(s.home())) {
                together = false;
                break;
            }
        }
        if (together) {
            return;
        }
        final Map<UUID, Integer> inHome = new HashMap<>();
        for (final Resident r : hh) {
            final Slot s = slots.get(r.id());
            if (s != null) {
                inHome.merge(s.home(), 1, Integer::sum);
            }
        }
        final double cx = hh.stream().mapToInt(Resident::x).average().orElse(0);
        final double cz = hh.stream().mapToInt(Resident::z).average().orElse(0);
        Home best = null;
        int bestMembers = -1;
        double bestDist = Double.MAX_VALUE;
        for (final Home h : homes.values()) {
            final int members = inHome.getOrDefault(h.id(), 0);
            final int room = free(beds.get(h.id())) + members;
            if (room < hh.size()) {
                continue;
            }
            final double d = (h.x() - cx) * (h.x() - cx) + (h.z() - cz) * (h.z() - cz);
            if (members > bestMembers || members == bestMembers && d < bestDist) {
                best = h;
                bestMembers = members;
                bestDist = d;
            }
        }
        if (best != null) {
            final UUID[] target = beds.get(best.id());
            for (final Resident r : hh) {
                final Slot s = slots.get(r.id());
                if (s != null && s.home().equals(best.id())) {
                    continue;
                }
                if (s != null) {
                    beds.get(s.home())[s.bed()] = null;
                }
                final int bed = firstFree(target);
                target[bed] = r.id();
                slots.put(r.id(), new Slot(best.id(), bed));
            }
            return;
        }
        // nobody can take them all: keep beds, the others next to the family if possible, else nearest free bed
        final List<Home> byPreference = new ArrayList<>(homes.values());
        byPreference.sort(Comparator.<Home>comparingInt(h -> -inHome.getOrDefault(h.id(), 0))
            .thenComparingDouble(h -> (h.x() - cx) * (h.x() - cx) + (h.z() - cz) * (h.z() - cz))
            .thenComparing(h -> h.id().toString()));
        for (final Resident r : hh) {
            if (slots.containsKey(r.id())) {
                continue;
            }
            for (final Home h : byPreference) {
                final UUID[] b = beds.get(h.id());
                final int bed = firstFree(b);
                if (bed >= 0) {
                    b[bed] = r.id();
                    slots.put(r.id(), new Slot(h.id(), bed));
                    break;
                }
            }
        }
    }

    private static int free(final UUID[] beds) {
        int n = 0;
        for (final UUID u : beds) {
            if (u == null) {
                n++;
            }
        }
        return n;
    }

    private static int firstFree(final UUID[] beds) {
        for (int i = 0; i < beds.length; i++) {
            if (beds[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /** True when every assigned bed is a real bed and is used once (for tests and sanity checks). */
    public static boolean consistent(final Result r, final List<Home> homes) {
        final Map<UUID, Integer> bedCount = new HashMap<>();
        for (final Home h : homes) {
            bedCount.put(h.id(), h.beds());
        }
        final java.util.Set<Slot> used = new java.util.HashSet<>();
        for (final Slot s : r.slots().values()) {
            final Integer n = bedCount.get(s.home());
            if (n == null || s.bed() < 0 || s.bed() >= n || !used.add(s)) {
                return false;
            }
        }
        return Objects.equals(r.slots().size(), used.size());
    }
}
