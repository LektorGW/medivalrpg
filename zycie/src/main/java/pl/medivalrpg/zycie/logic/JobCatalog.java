// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * City jobs (section {@code zawody}): maps vanilla professions to Polish job titles per era and to the buildings the
 * citizen works in. Several jobs may share a profession (a farmer may be a baker when the town has a bakery); the
 * first job, in config order, whose conditions hold and whose share includes the villager wins.
 */
public final class JobCatalog {

    /**
     * @param id               job id (also a {@code zawody} condition in text pools), e.g. {@code piekarz}
     * @param names            era → Polish title (an era without an entry uses the closest lower one)
     * @param professions      vanilla professions without namespace ({@code farmer}, {@code none}…)
     * @param buildings        workplace building types / functions
     * @param requiresBuilding the job exists only when the country has one of {@code buildings}
     * @param share            part of the villagers of these professions taking this job (0..1)
     * @param minEra           first era of the job
     */
    public record Job(String id, NavigableMap<Integer, String> names, Set<String> professions, List<String> buildings,
                      boolean requiresBuilding, double share, int minEra) {

        public String title(final int era) {
            final Map.Entry<Integer, String> e = this.names.floorEntry(era);
            if (e != null) {
                return e.getValue();
            }
            return this.names.isEmpty() ? this.id : this.names.firstEntry().getValue();
        }
    }

    public static final Job NOBODY = new Job("mieszczanin", new TreeMap<>(Map.of(1, "mieszczanin")), Set.of(), List.of(),
        false, 1.0, 1);

    private final List<Job> jobs;

    public JobCatalog(final List<Job> jobs) {
        this.jobs = List.copyOf(jobs);
    }

    public List<Job> jobs() {
        return this.jobs;
    }

    /**
     * @param profession  vanilla profession key ({@code minecraft:farmer} or {@code farmer})
     * @param villager    villager id (stable share)
     * @param era         country era
     * @param hasBuilding whether the country has a building of a type/function
     */
    public Job resolve(final String profession, final UUID villager, final int era, final Predicate<String> hasBuilding) {
        final String prof = Texts.stripNamespace(profession);
        for (final Job j : this.jobs) {
            if (!j.professions().contains(prof) || era < j.minEra()) {
                continue;
            }
            if (j.requiresBuilding() && j.buildings().stream().noneMatch(hasBuilding)) {
                continue;
            }
            if (j.share() >= 1.0 || Hashing.unit(villager, "job:" + j.id()) < j.share()) {
                return j;
            }
        }
        return NOBODY;
    }

    /** Parses the {@code zawody} list (maps from YAML). */
    public static JobCatalog parse(final List<Map<?, ?>> raw, final List<String> problems) {
        final List<Job> out = new ArrayList<>();
        if (raw == null) {
            return new JobCatalog(out);
        }
        int index = 0;
        for (final Map<?, ?> m : raw) {
            index++;
            final String where = "zawody[" + index + "]";
            final Object idRaw = m.get("id");
            if (idRaw == null) {
                problems.add(where + ": brak id");
                continue;
            }
            final String id = Texts.normalize(String.valueOf(idRaw));
            final TreeMap<Integer, String> names = new TreeMap<>();
            final Object n = m.get("nazwa");
            if (n instanceof Map<?, ?> byEra) {
                for (final Map.Entry<?, ?> e : byEra.entrySet()) {
                    try {
                        names.put(Integer.parseInt(String.valueOf(e.getKey()).trim()), String.valueOf(e.getValue()));
                    } catch (final NumberFormatException ex) {
                        problems.add(where + ": klucz nazwy musi być numerem epoki: " + e.getKey());
                    }
                }
            } else if (n != null) {
                names.put(1, String.valueOf(n));
            } else {
                names.put(1, String.valueOf(idRaw));
            }
            final Set<String> profs = new LinkedHashSet<>();
            for (final String p : TextRuleParser.strings(m.get("profesje"))) {
                profs.add(Texts.stripNamespace(p));
            }
            if (profs.isEmpty()) {
                problems.add(where + " (" + id + "): brak profesji");
                continue;
            }
            final List<String> buildings = new ArrayList<>();
            for (final String b : TextRuleParser.strings(m.get("budynki"))) {
                buildings.add(Texts.normalize(b));
            }
            final String req = Texts.normalize(String.valueOf(m.get("wymaga-budynku")));
            final boolean requires = req.equals("true") || req.equals("tak");
            final double share = m.get("udzial") instanceof Number num ? Math.max(0.0, Math.min(1.0, num.doubleValue())) : 1.0;
            final int minEra = m.get("od-epoki") instanceof Number num ? Math.max(1, num.intValue()) : 1;
            out.add(new Job(id, names, Set.copyOf(profs), List.copyOf(buildings), requires, share, minEra));
        }
        return new JobCatalog(out);
    }
}
