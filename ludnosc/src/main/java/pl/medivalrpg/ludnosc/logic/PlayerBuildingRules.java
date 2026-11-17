// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Buildings built by hand by the players and registered with {@code /ludnosc budynek zglos <typ>} (pure, unit tested).
 * A registered building has no template: it is stored as an ordinary {@code Building} with type {@code wlasny_<key>},
 * {@code registered = true} (never demolished, rebuilt, modernised or repaired by the plugin, exactly like houses
 * registered by an admin) and the function of its type while its key blocks are inside; without them the function is
 * {@link BuildingFunction#OTHER} until the players repair it. Limits and requirements come from the template of the
 * same kind ({@link Def#template()}), so registering never bypasses the limits per country or the epoch.
 */
public final class PlayerBuildingRules {

    /** Type prefix of registered player buildings (unknown to the template library). */
    public static final String PREFIX = "wlasny_";

    /** Whole beds: head and foot both inside the outline. */
    public static final String BED = "#BED";
    /** Water source blocks. */
    public static final String WATER = "#WATER";
    /** Chests and barrels (granary / store containers). */
    public static final String CONTAINER = "#CONTAINER";
    /** Any anvil (also chipped / damaged). */
    public static final String ANVIL = "#ANVIL";
    /** Any candle (all colours). */
    public static final String CANDLE = "#CANDLE";

    public static final Set<String> TOKENS = Set.of(BED, WATER, CONTAINER, ANVIL, CANDLE);

    /**
     * Functions whose effect needs a mechanism of their own (galleries, fields, felling, quarry cuts, harbour waters,
     * sand / clay pits, flocks, the wall ring, bridges) or that belong to later epochs: never registrable.
     */
    public static final Set<BuildingFunction> EXCLUDED = EnumSet.of(BuildingFunction.MINE, BuildingFunction.FARM,
        BuildingFunction.SAWMILL, BuildingFunction.QUARRY, BuildingFunction.HARBOR, BuildingFunction.GLASSWORKS,
        BuildingFunction.BRICKWORKS, BuildingFunction.SHEEPFOLD, BuildingFunction.WALL, BuildingFunction.GATE,
        BuildingFunction.BRIDGE, BuildingFunction.FACTORY, BuildingFunction.STATION, BuildingFunction.OFFICE,
        BuildingFunction.POWERPLANT, BuildingFunction.PARKING, BuildingFunction.AIRPORT, BuildingFunction.MALL,
        BuildingFunction.STADIUM, BuildingFunction.LAB, BuildingFunction.OTHER);

    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{2,24}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _'\\-]{2,24}");

    private PlayerBuildingRules() {
    }

    /**
     * A key block requirement: at least {@code count} blocks of any of {@code materials} (Bukkit material names or one
     * of the {@link #TOKENS}) inside the outline.
     */
    public record Need(String label, Set<String> materials, int count) {
        public Need {
            materials = Set.copyOf(materials);
            count = Math.max(1, count);
        }
    }

    /**
     * A registrable type.
     *
     * @param template      template id whose limits and requirements apply (max per country, epoch, level...)
     * @param minVolume     smallest outline volume
     * @param maxRegistered most registered buildings of this type per country (0 = only the template limit)
     * @param effect        player-facing effect (Polish)
     */
    public record Def(String key, String name, BuildingFunction function, String template, List<Need> needs, int minVolume,
                      int maxRegistered, String effect) {
        public Def {
            needs = List.copyOf(needs);
        }

        /** Every material name / token any need of this type looks for. */
        public Set<String> materials() {
            final Set<String> out = new LinkedHashSet<>();
            for (final Need n : this.needs) {
                out.addAll(n.materials());
            }
            return out;
        }
    }

    /** Size limits of an outline. */
    public record Limits(int minSide, int minHeight, long maxVolume) {
    }

    /** A need that is not met: {@code have} of {@link Need#count()}. */
    public record Shortage(Need need, int have) {
    }

    public static final List<Def> DEFAULTS = List.of(
        new Def("mieszkanie", "Mieszkanie", BuildingFunction.HOUSING, "chata",
            List.of(new Need("całe łóżko", Set.of(BED), 1)), 27, 0,
            "mieszkanie: każde całe łóżko to miejsce dla 1 obywatela (bez wolnych łóżek nie przybywa osadników)"),
        new Def("spichlerz", "Spichlerz", BuildingFunction.GRANARY, "spichlerz",
            List.of(new Need("skrzynia lub beczka", Set.of(CONTAINER), 1)), 27, 3,
            "pojemność spichlerza kraju +2000 pkt jedzenia; jedzenie włożone do skrzyń i beczek w środku karmi ludność"),
        new Def("magazyn", "Magazyn", BuildingFunction.WAREHOUSE, "magazyn",
            List.of(new Need("skrzynie lub beczki", Set.of(CONTAINER), 2)), 27, 0,
            "skład kraju: każda skrzynia i beczka w środku przyjmuje urobek od tragarzy i wydaje materiały na budowy; "
                + "pojemność spichlerza +300 (stan: /ludnosc magazyn)"),
        new Def("koszary", "Koszary", BuildingFunction.BARRACKS, "koszary",
            List.of(new Need("całe łóżka", Set.of(BED), 2)), 27, 0,
            "każda cała prycza podnosi limit żołnierzy kraju o 1; rekruci stawiają się przy koszarach, żołnierze w pobliżu "
                + "powoli się leczą; prycze nie liczą się jako mieszkania"),
        new Def("kuznia", "Kuźnia", BuildingFunction.SMITHY, "kuznia",
            List.of(new Need("kowadło", Set.of(ANVIL), 1),
                new Need("piec, piec hutniczy lub stół kowalski", Set.of("FURNACE", "BLAST_FURNACE", "SMITHING_TABLE"), 1)),
            27, 0, "miejsce pracy kowali (zbrojmistrzów, płatnerzy, narzędziarzy); zgłoszona kuźnia sama nie wytapia żelaza "
                + "ani nie robi pochodni — to robi tylko kuźnia zamówiona przez /ludnosc buduj"),
        new Def("studnia", "Studnia", BuildingFunction.WELL, "studnia",
            List.of(new Need("źródło wody", Set.of(WATER), 1)), 27, 0,
            "lepszy nastrój mieszkańców, miejsce wieczornych spotkań i woda dla ekip gaszących pożary"),
        new Def("karczma", "Karczma", BuildingFunction.TAVERN, "karczma",
            List.of(new Need("beczka (szynk)", Set.of("BARREL"), 1),
                new Need("palenisko: ognisko, wędzarnia lub piec", Set.of("CAMPFIRE", "SMOKER", "FURNACE"), 1)),
            64, 0, "podatki +5%, szansa na nowego osadnika +10% w każdym cyklu wzrostu, lepszy nastrój"),
        new Def("kosciol", "Kościół", BuildingFunction.CHURCH, "kosciol",
            List.of(new Need("pulpit (ołtarz)", Set.of("LECTERN"), 1),
                new Need("świeca lub dzwon", Set.of(CANDLE, "BELL"), 1)),
            150, 0, "szansa na nowego osadnika +20% w każdym cyklu wzrostu, lepszy nastrój (niedzielna msza), praca dla kapłanów"),
        new Def("ratusz", "Ratusz", BuildingFunction.TOWNHALL, "ratusz",
            List.of(new Need("stół kartograficzny", Set.of("CARTOGRAPHY_TABLE"), 1),
                new Need("pulpit (księga rachunkowa)", Set.of("LECTERN"), 1)),
            125, 0, "podatki od obywateli +8%, praca dla kartografów"));

    // ------------------------------------------------------------------ type ids

    public static boolean isPlayerType(final String type) {
        return type != null && type.startsWith(PREFIX) && type.length() > PREFIX.length();
    }

    public static String typeId(final String key) {
        return PREFIX + key;
    }

    /** Key of a registered type ({@code wlasny_kuznia} → {@code kuznia}), null for other types. */
    public static String keyOf(final String type) {
        return isPlayerType(type) ? type.substring(PREFIX.length()) : null;
    }

    public static boolean validKey(final String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** Error (Polish) of an optional player-given name, null = valid. */
    public static String nameProblem(final String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return NAME.matcher(name).matches() ? null : "nazwa: 2–24 znaki (litery, cyfry, spacja, _ - ')";
    }

    // ------------------------------------------------------------------ checks

    /** Size problem (Polish, with the sizes), null = fits. */
    public static String sizeProblem(final int sx, final int sy, final int sz, final long volume, final Limits l, final int minVolume) {
        if (sx < l.minSide() || sz < l.minSide() || sy < l.minHeight()) {
            return "zaznaczenie " + sx + "×" + sz + "×" + sy + " (szer.×gł.×wys.) jest za małe — najmniej " + l.minSide() + "×"
                + l.minSide() + "×" + l.minHeight();
        }
        if (volume > l.maxVolume()) {
            return "zaznaczenie ma " + volume + " pól — najwyżej " + l.maxVolume() + "; zaznacz sam budynek";
        }
        if (volume < minVolume) {
            return "zaznaczenie ma " + volume + " pól — ten typ budynku musi mieć co najmniej " + minVolume;
        }
        return null;
    }

    /** Needs that the counted blocks do not meet (counts per material name / token). */
    public static List<Shortage> shortages(final Def d, final Map<String, Integer> counts) {
        final List<Shortage> out = new ArrayList<>();
        for (final Need n : d.needs()) {
            int have = 0;
            for (final String m : n.materials()) {
                have += counts.getOrDefault(m, 0);
            }
            if (have < n.count()) {
                out.add(new Shortage(n, have));
            }
        }
        return out;
    }

    /** One line per shortage: "kowadło (0/1)". */
    public static String describe(final List<Shortage> shortages) {
        final List<String> parts = new ArrayList<>();
        for (final Shortage s : shortages) {
            parts.add(s.need().label() + " (" + s.have() + "/" + s.need().count() + ")");
        }
        return String.join(", ", parts);
    }

    /**
     * Limit problem: the template limit counts every building of the kind (ordered, under construction, queued and
     * registered), {@code maxRegistered} only the registered ones, {@code maxTotal} all registered buildings of the
     * country. Null = within the limits.
     */
    public static String limitProblem(final Def d, final int templateMax, final int ofKind, final int registeredOfType,
                                      final int maxTotal, final int registeredTotal) {
        if (templateMax > 0 && ofKind >= templateMax) {
            return "limit: najwyżej " + templateMax + " × " + d.name().toLowerCase(Locale.ROOT) + " na kraj (macie " + ofKind
                + " razem z zamówionymi i budowanymi)";
        }
        if (d.maxRegistered() > 0 && registeredOfType >= d.maxRegistered()) {
            return "limit zgłoszonych: najwyżej " + d.maxRegistered() + " × " + d.name().toLowerCase(Locale.ROOT)
                + " zgłoszonych przez graczy na kraj";
        }
        if (maxTotal > 0 && registeredTotal >= maxTotal) {
            return "limit: najwyżej " + maxTotal + " zgłoszonych budynków na kraj";
        }
        return null;
    }

    /** Head → foot of a bed: the foot lies one block opposite to the facing of the head. */
    public static int[] bedFoot(final int x, final int y, final int z, final String facing) {
        return switch (facing == null ? "" : facing.toUpperCase(Locale.ROOT)) {
            case "NORTH" -> new int[] {x, y, z + 1};
            case "SOUTH" -> new int[] {x, y, z - 1};
            case "EAST" -> new int[] {x - 1, y, z};
            case "WEST" -> new int[] {x + 1, y, z};
            default -> new int[] {x, y, z};
        };
    }

    // ------------------------------------------------------------------ config

    /**
     * Defaults overridden by the {@code player-buildings.types} section (as plain maps): {@code enabled}, {@code name},
     * {@code max}, {@code min-volume}, {@code template}, {@code function}, {@code effect} and {@code blocks}
     * ({@code [{name, count, materials: [...]}]}). New keys need {@code function} and {@code template}. Functions of
     * {@link #EXCLUDED} are refused. Problems are added to {@code warnings}.
     */
    public static Map<String, Def> configure(final Map<String, Map<String, Object>> section, final List<String> warnings) {
        final Map<String, Def> out = new LinkedHashMap<>();
        for (final Def d : DEFAULTS) {
            out.put(d.key(), d);
        }
        if (section == null) {
            return out;
        }
        for (final Map.Entry<String, Map<String, Object>> e : section.entrySet()) {
            final String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            final Map<String, Object> v = e.getValue() == null ? Map.of() : e.getValue();
            if (!validKey(key)) {
                warnings.add("player-buildings.types." + key + ": nieprawidłowy klucz (a-z, 0-9, _)");
                continue;
            }
            if (Boolean.FALSE.equals(v.get("enabled"))) {
                out.remove(key);
                continue;
            }
            final Def base = out.get(key);
            BuildingFunction function = base == null ? null : base.function();
            if (v.get("function") != null) {
                try {
                    function = BuildingFunction.parse(String.valueOf(v.get("function")));
                } catch (final IllegalArgumentException ex) {
                    warnings.add("player-buildings.types." + key + ": " + ex.getMessage());
                    continue;
                }
            }
            final String template = v.get("template") != null ? String.valueOf(v.get("template")).toLowerCase(Locale.ROOT)
                : base == null ? null : base.template();
            if (function == null || template == null) {
                warnings.add("player-buildings.types." + key + ": nowy typ wymaga function i template");
                continue;
            }
            if (EXCLUDED.contains(function)) {
                warnings.add("player-buildings.types." + key + ": funkcja " + function + " wymaga własnego mechanizmu — pominięto");
                out.remove(key);
                continue;
            }
            List<Need> needs = base == null ? List.of() : base.needs();
            if (v.get("blocks") instanceof List<?> list) {
                needs = new ArrayList<>();
                for (final Object o : list) {
                    if (!(o instanceof Map<?, ?> entry) || !(entry.get("materials") instanceof List<?> mats) || mats.isEmpty()) {
                        warnings.add("player-buildings.types." + key + ".blocks: każdy wpis potrzebuje materials");
                        continue;
                    }
                    final Set<String> names = new LinkedHashSet<>();
                    for (final Object mat : mats) {
                        final String s = String.valueOf(mat).trim();
                        names.add(s.startsWith("#") ? s.toUpperCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT));
                    }
                    final Object label = entry.get("name");
                    needs.add(new Need(label == null ? String.join("/", names) : String.valueOf(label), names, intOf(entry.get("count"), 1)));
                }
            }
            if (needs.isEmpty()) {
                warnings.add("player-buildings.types." + key + ": brak wymaganych bloków — pominięto");
                out.remove(key);
                continue;
            }
            out.put(key, new Def(key, v.get("name") != null ? String.valueOf(v.get("name")) : base == null ? key : base.name(),
                function, template, needs,
                intOf(v.get("min-volume"), base == null ? 27 : base.minVolume()),
                intOf(v.get("max"), base == null ? 0 : base.maxRegistered()),
                v.get("effect") != null ? String.valueOf(v.get("effect")) : base == null ? "" : base.effect()));
        }
        return out;
    }

    private static int intOf(final Object o, final int def) {
        if (o instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (o != null) {
            try {
                return Math.max(0, Integer.parseInt(String.valueOf(o).trim()));
            } catch (final NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
