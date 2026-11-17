// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who works where (pure, unit tested). Workplaces (fields, mines, quarries, sawmills, harbours) have slots; adult
 * citizens fill them by profession — first the primary profession of the job (farmer → field, mason → mine...), then
 * the fallbacks in config order (e.g. the unemployed may become miners). Within a pass the workplaces take turns,
 * each taking the nearest free candidate, so workers spread over all workplaces instead of filling the first one.
 */
public final class JobBoard {

    /** Jobs with a workplace. */
    public enum Job {
        FARMER("rolnik", "farmer"),
        MINER("górnik", "miner"),
        QUARRYMAN("kamieniarz", "quarryman"),
        WOODCUTTER("drwal", "woodcutter"),
        FISHER("rybak", "fisher");

        private final String title;
        private final String key;

        Job(final String title, final String key) {
            this.title = title;
            this.key = key;
        }

        /** Polish title. */
        public String title() {
            return this.title;
        }

        /** Config key ({@code work.jobs.<key>}). */
        public String key() {
            return this.key;
        }
    }

    /** A citizen (adult or not; children never work). */
    public record Citizen(UUID id, String profession, boolean adult, int x, int z, String world) {
        public Citizen(UUID id, String profession, boolean adult, int x, int z) {
            this(id, profession, adult, x, z, null);
        }
    }

    /** A workplace with free slots. */
    public record Workplace(UUID id, Job job, int slots, int x, int z, String world) {
        public Workplace(UUID id, Job job, int slots, int x, int z) {
            this(id, job, slots, x, z, null);
        }
    }

    /**
     * Result.
     *
     * @param workplaceOf citizen → workplace
     * @param jobOf       citizen → job
     * @param staff       workplace → its workers (nearest first)
     */
    public record Assignment(Map<UUID, UUID> workplaceOf, Map<UUID, Job> jobOf, Map<UUID, List<UUID>> staff) {

        public static final Assignment EMPTY = new Assignment(Map.of(), Map.of(), Map.of());

        public int workersAt(final UUID workplace) {
            final List<UUID> s = this.staff.get(workplace);
            return s == null ? 0 : s.size();
        }

        public int workers(final Job job) {
            int n = 0;
            for (final Job j : this.jobOf.values()) {
                if (j == job) {
                    n++;
                }
            }
            return n;
        }
    }

    private JobBoard() {
    }

    /**
     * @param professions job → professions in priority order (keys without namespace: {@code farmer}, {@code none}...)
     */
    public static Assignment assign(final List<Citizen> citizens, final List<Workplace> places,
                                    final Map<Job, List<String>> professions) {
        if (citizens.isEmpty() || places.isEmpty()) {
            return Assignment.EMPTY;
        }
        final List<Workplace> order = new ArrayList<>(places);
        order.sort(Comparator.comparing((Workplace w) -> w.job().ordinal()).thenComparing(w -> w.id().toString()));
        final Map<UUID, UUID> workplaceOf = new HashMap<>();
        final Map<UUID, Job> jobOf = new HashMap<>();
        final Map<UUID, List<UUID>> staff = new LinkedHashMap<>();
        final Map<UUID, Workplace> byId = new HashMap<>();
        for (final Workplace w : order) {
            staff.put(w.id(), new ArrayList<>());
            byId.put(w.id(), w);
        }
        final List<Citizen> adults = new ArrayList<>();
        for (final Citizen c : citizens) {
            if (c.adult()) {
                adults.add(c);
            }
        }
        adults.sort(Comparator.comparing(c -> c.id().toString()));
        int passes = 0;
        for (final List<String> list : professions.values()) {
            passes = Math.max(passes, list.size());
        }
        for (int pass = 0; pass < passes; pass++) {
            boolean progress = true;
            while (progress) {
                progress = false;
                for (final Workplace w : order) {
                    final List<UUID> s = staff.get(w.id());
                    if (s.size() >= w.slots()) {
                        continue;
                    }
                    final List<String> wanted = professions.getOrDefault(w.job(), List.of());
                    if (pass >= wanted.size()) {
                        continue;
                    }
                    Citizen best = null;
                    double bestD = Double.POSITIVE_INFINITY;
                    for (final Citizen c : adults) {
                        if (workplaceOf.containsKey(c.id()) || !java.util.Objects.equals(c.world(), w.world())) {
                            continue;
                        }
                        // the citizen's own profession ranks at most this pass in the job's list
                        final int rank = wanted.indexOf(Economy.professionKey(c.profession()));
                        if (rank < 0 || rank > pass) {
                            continue;
                        }
                        final long dx = (long) c.x() - w.x();
                        final long dz = (long) c.z() - w.z();
                        final double d = (double) dx * dx + (double) dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = c;
                        }
                    }
                    if (best != null) {
                        s.add(best.id());
                        workplaceOf.put(best.id(), w.id());
                        jobOf.put(best.id(), w.job());
                        progress = true;
                    }
                }
            }
        }
        final Map<UUID, List<UUID>> frozen = new LinkedHashMap<>();
        staff.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new Assignment(Map.copyOf(workplaceOf), Map.copyOf(jobOf), java.util.Collections.unmodifiableMap(frozen));
    }

    /** Default professions per job (config {@code work.jobs}). */
    public static Map<Job, List<String>> defaultProfessions() {
        final Map<Job, List<String>> m = new EnumMap<>(Job.class);
        m.put(Job.FARMER, List.of("farmer"));
        m.put(Job.MINER, List.of("mason", "toolsmith", "armorer", "weaponsmith", "none"));
        m.put(Job.QUARRYMAN, List.of("mason", "none"));
        m.put(Job.WOODCUTTER, List.of("fletcher", "none"));
        m.put(Job.FISHER, List.of("fisherman"));
        return m;
    }
}
