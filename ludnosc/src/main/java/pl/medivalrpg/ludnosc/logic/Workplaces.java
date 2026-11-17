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
 * Permanent workplace of every adult citizen (pure, unit tested). The production jobs (field, mine, quarry, sawmill,
 * harbour) come from the existing {@link JobBoard}; the other adults work, by profession, in a workshop of their
 * trade (smithy, bakery, library, church...), then as helpers at a construction site, then at the granary or the
 * warehouse. A citizen keeps a valid workplace; nobody exceeds the slots of a place.
 */
public final class Workplaces {

    private Workplaces() {
    }

    /** Kind of workplace (config key / Polish title). */
    public enum Kind {
        POLE("pole", "rolnik w polu"),
        KOPALNIA("kopalnia", "górnik w kopalni"),
        KAMIENIOLOM("kamieniolom", "kamieniarz w kamieniołomie"),
        TARTAK("tartak", "drwal z tartaku"),
        PRZYSTAN("przystan", "rybak na przystani"),
        WARSZTAT("warsztat", "rzemieślnik w warsztacie"),
        BUDOWA("budowa", "pomocnik na placu budowy"),
        SPICHLERZ("spichlerz", "pomocnik w spichlerzu"),
        MAGAZYN("magazyn", "pomocnik w magazynie");

        private final String key;
        private final String title;

        Kind(final String key, final String title) {
            this.key = key;
            this.title = title;
        }

        public String key() {
            return this.key;
        }

        public String title() {
            return this.title;
        }

        public static Kind of(final JobBoard.Job job) {
            return switch (job) {
                case FARMER -> POLE;
                case MINER -> KOPALNIA;
                case QUARRYMAN -> KAMIENIOLOM;
                case WOODCUTTER -> TARTAK;
                case FISHER -> PRZYSTAN;
            };
        }

        public static Kind byKey(final String key) {
            for (final Kind k : values()) {
                if (k.key.equals(key)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** Workshop building types per profession (template ids, base types). */
    public static final Map<String, List<String>> TRADES = Map.ofEntries(
        Map.entry("armorer", List.of("kuznia")),
        Map.entry("weaponsmith", List.of("kuznia")),
        Map.entry("toolsmith", List.of("kuznia")),
        Map.entry("butcher", List.of("targ", "rynek", "piekarnia")),
        Map.entry("cartographer", List.of("biblioteka", "ratusz", "drukarnia", "uniwersytet")),
        Map.entry("cleric", List.of("kosciol", "katedra", "zielarnia")),
        Map.entry("leatherworker", List.of("targ", "stajnia")),
        Map.entry("librarian", List.of("biblioteka", "szkola", "uniwersytet", "akademia")),
        Map.entry("shepherd", List.of("stajnia")),
        Map.entry("fletcher", List.of("tartak", "kuznia")),
        Map.entry("mason", List.of("kamieniolom", "kuznia")),
        Map.entry("farmer", List.of("mlyn", "piekarnia", "browar")),
        Map.entry("fisherman", List.of("przystan", "port")));

    /**
     * A place with slots.
     *
     * @param types template base type of a workshop (for {@link Kind#WARSZTAT}), else empty
     */
    public record Place(UUID id, Kind kind, String type, int slots, String world, int x, int z) {
    }

    /** An adult citizen. */
    public record Worker(UUID id, String profession, String world, int x, int z, UUID current) {
    }

    /** Assigned place per citizen (absent = unemployed). */
    public record Result(Map<UUID, Place> placeOf) {

        public int employed() {
            return this.placeOf.size();
        }
    }

    /**
     * @param board  production jobs from {@link JobBoard} (citizen → its place), always kept
     * @param places workshops, construction sites, granaries and warehouses
     */
    public static Result assign(final List<Worker> workers, final Map<UUID, Place> board, final List<Place> places) {
        final Map<UUID, Place> out = new LinkedHashMap<>();
        final Map<UUID, Integer> used = new HashMap<>();
        final Map<UUID, Place> byId = new LinkedHashMap<>();
        final List<Place> sorted = new ArrayList<>(places);
        sorted.sort(Comparator.comparing((Place p) -> p.kind().ordinal()).thenComparing(p -> p.id().toString()));
        for (final Place p : sorted) {
            byId.put(p.id(), p);
        }
        final List<Worker> ws = new ArrayList<>(workers);
        ws.sort(Comparator.comparing(w -> w.id().toString()));
        final List<Worker> rest = new ArrayList<>();
        for (final Worker w : ws) {
            final Place b = board.get(w.id());
            if (b != null) {
                out.put(w.id(), b);
            } else {
                rest.add(w);
            }
        }
        // keep valid current places
        final List<Worker> open = new ArrayList<>();
        for (final Worker w : rest) {
            final Place p = w.current() == null ? null : byId.get(w.current());
            if (p != null && fits(w, p) && used.getOrDefault(p.id(), 0) < p.slots()) {
                used.merge(p.id(), 1, Integer::sum);
                out.put(w.id(), p);
            } else {
                open.add(w);
            }
        }
        // trade workshops first, then construction sites, granaries, warehouses — nearest place with a free slot
        for (final Kind kind : List.of(Kind.WARSZTAT, Kind.BUDOWA, Kind.SPICHLERZ, Kind.MAGAZYN)) {
            for (final Worker w : open) {
                if (out.containsKey(w.id())) {
                    continue;
                }
                Place best = null;
                double bestD = Double.MAX_VALUE;
                for (final Place p : sorted) {
                    if (p.kind() != kind || !fits(w, p) || used.getOrDefault(p.id(), 0) >= p.slots()
                        || !Objects.equals(p.world(), w.world())) {
                        continue;
                    }
                    final double d = Math.pow(p.x() - w.x(), 2) + Math.pow(p.z() - w.z(), 2);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
                if (best != null) {
                    used.merge(best.id(), 1, Integer::sum);
                    out.put(w.id(), best);
                }
            }
        }
        return new Result(java.util.Collections.unmodifiableMap(out));
    }

    /**
     * Field work is closed in winter when {@code winterIndoors} (the farmers then take a workshop of their trade —
     * mill, bakery, brewery — or help at the granary / warehouse / sites). Rain never closes the fields.
     */
    public static boolean fieldsOpen(final Season season, final boolean winterIndoors) {
        return !(winterIndoors && season == Season.ZIMA);
    }

    /** Workshops only take their trade; nitwits only help; everybody may help at sites, granaries and warehouses. */
    static boolean fits(final Worker w, final Place p) {
        final String prof = Economy.professionKey(w.profession());
        if (p.kind() == Kind.WARSZTAT) {
            final List<String> types = TRADES.get(prof);
            return types != null && types.contains(p.type());
        }
        return p.kind() == Kind.BUDOWA || p.kind() == Kind.SPICHLERZ || p.kind() == Kind.MAGAZYN || prof.equals("none");
    }
}
