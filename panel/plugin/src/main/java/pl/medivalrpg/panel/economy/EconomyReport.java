// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pl.medivalrpg.panel.util.Json;

/**
 * Builds the {@code country.economy} answer (PANEL.md „Gospodarka kraju”) from what Ludnosc reported: production and the state
 * of each workplace, stores with goods and fill, the granary, haulers, construction sites with what they lack and where it
 * comes from, citizens, and the warnings with advice. Tolerant: every field of {@code materialFlow} may be missing, carry an
 * unexpected type or be new — known fields are normalised, unknown ones are passed on under {@code extra} (bounded, plain).
 * Pure: time, the tracker and all inputs are passed in, so the whole answer is unit-tested without a server.
 */
public final class EconomyReport {

    /** Version of this answer (the SPA shows a hint when it is newer than it knows). */
    public static final int VERSION = 1;

    /** Top-level fields of {@code materialFlow} this panel understands (flow-release5). Others go to {@code extra}. */
    static final Set<String> KNOWN_FLOW = Set.of("version", "workplaces", "stores", "granary", "sites", "haulers", "recent",
        "requireMaterials", "production", "daily");
    static final Set<String> KNOWN_WORKPLACE = Set.of("id", "function", "status", "x", "y", "z", "world", "name", "type");
    static final Set<String> KNOWN_STORE = Set.of("label", "used", "slots", "goods", "world", "x", "y", "z");
    static final Set<String> KNOWN_SITE = Set.of("id", "name", "stage", "percent", "builders", "status", "needs", "x", "y", "z",
        "waitingSince", "world", "type", "carrying");
    /** Daily output fields a newer Ludnosc may add to a workplace (material → amount, or one number). */
    static final List<String> DAILY_KEYS = List.of("daily", "perDay", "dailyYield", "yield24h", "produced24h", "producedToday", "output");

    /** From the frozen {@code LudnoscApi.info} (null = not available). */
    public record Info(int population, int housing, long food, long foodCapacity, long foodBalance, long dailyTax, int hungryDays,
                       int homelessDays, List<String> buildingFunctions, String settlementName) {
    }

    /** Population about 24 h ago (from the Panel's hourly history or the tracker), null = unknown. */
    public record Growth(long population, long at, String source) {
    }

    /** Everything one answer is built from. */
    public record Input(UUID id, String name, String color, boolean npc, String access, long now, LudnoscBridge.Sources src,
                        Info info, EconomyTracker tracker, Growth growthBase, boolean ludnoscPresent) {
    }

    private static final Pattern HAUL = Pattern.compile("^(\\d+)\\s*×\\s*(.+?):\\s*(.+?)\\s*→\\s*(.+)$");
    private static final Pattern STAMP = Pattern.compile("^(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s+(.*)$");
    private static final Pattern WAIT = Pattern.compile("^czeka na (.+?)(?:\\s+—\\s+(.*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile("tragarz przyniesie z:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private EconomyReport() {
    }

    // ------------------------------------------------------------------ workplace states

    /** State of a workplace from Ludnosc's Polish status line. */
    public static String state(final String status) {
        final String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return "UNKNOWN";
        }
        if (s.contains("pełny") || s.contains("pelny")) {
            return "FULL";
        }
        if (s.contains("pochodni")) {
            return "TORCHES";
        }
        if (s.contains("audyt") || s.contains("nieprawidłowy stos") || s.contains("błąd zapisu")) {
            return "AUDIT";
        }
        if (s.contains("przypisz magazyn") || s.contains("brak magazynu")) {
            return "NO_STORE";
        }
        if (s.contains("niezaładowany") || s.contains("niewczytan")) {
            return "UNLOADED";
        }
        if (s.contains("zapis produkcji")) {
            return "WORKING";
        }
        if (s.contains("gotowy") || s.contains("pracuj")) {
            return "OK";
        }
        if (s.contains("wstrzyman") || s.contains("brak")) {
            return "BLOCKED";
        }
        return "OTHER";
    }

    static String stateLabel(final String state) {
        return switch (state) {
            case "OK" -> "pracuje";
            case "WORKING" -> "zapisuje urobek";
            case "FULL" -> "pełna skrzynia — stoi";
            case "TORCHES" -> "brak pochodni — stoi";
            case "NO_STORE" -> "brak magazynu — stoi";
            case "UNLOADED" -> "teren niewczytany";
            case "AUDIT" -> "wymaga audytu";
            case "BLOCKED" -> "wstrzymane";
            default -> "nieznany stan";
        };
    }

    static boolean blocked(final String state) {
        return switch (state) {
            case "FULL", "TORCHES", "NO_STORE", "AUDIT", "BLOCKED" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ build

    public static Map<String, Object> build(final Input in) {
        final long now = in.now();
        final LudnoscBridge.Sources src = in.src() == null
            ? new LudnoscBridge.Sources(null, null, null, null, Map.of(), Map.of()) : in.src();
        final Map<String, Object> flow = src.flow() == null ? Map.of() : src.flow();
        final List<Map<String, Object>> warnings = new ArrayList<>();

        // ---- workplaces
        final List<Map<String, Object>> workplaces = new ArrayList<>();
        final Map<String, int[]> byFunction = new TreeMap<>();
        final Map<String, Long> reportedDaily = new LinkedHashMap<>();
        boolean dailyFromLudnosc = false;
        final Set<String> functions = new HashSet<>();
        for (final Object o : Plain.list(flow.get("workplaces"))) {
            final Map<String, Object> w = Plain.map(o);
            final String id = Plain.str(w.get("id"), "");
            final String function = Plain.str(Plain.first(w, "function", "type", "name"), "miejsce pracy");
            final String status = Plain.str(w.get("status"), "");
            final String state = state(status);
            functions.add(function.toLowerCase(Locale.ROOT));
            final int[] f = byFunction.computeIfAbsent(function, k -> new int[5]);
            f[0]++;
            if (state.equals("OK") || state.equals("WORKING")) {
                f[1]++;
            }
            if (blocked(state)) {
                f[2]++;
            }
            if (state.equals("FULL")) {
                f[3]++;
            }
            if (state.equals("UNLOADED")) {
                f[4]++;
            }
            final List<Map<String, Object>> daily = dailyOf(w);
            if (daily != null) {
                dailyFromLudnosc = true;
                for (final Map<String, Object> d : daily) {
                    reportedDaily.merge((String) d.get("key"), (Long) d.get("amount"), Long::sum);
                }
            }
            final String shortId = id.length() >= 8 ? id.substring(0, 8) : id;
            workplaces.add(Json.obj("id", id, "short", shortId, "function", function, "status", status, "state", state,
                "stateLabel", stateLabel(state), "x", Plain.num(w.get("x")), "y", Plain.num(w.get("y")), "z", Plain.num(w.get("z")),
                "daily", daily, "extra", extra(w, KNOWN_WORKPLACE, DAILY_KEYS)));
            final String where = coords(w);
            switch (state) {
                case "FULL" -> warnings.add(warn("error", "FULL_STORE", cap(function) + " stoi — pełna skrzynia",
                    "Skrzynia " + function + where + " jest pełna, więc praca stoi przed następnym urobkiem.",
                    "Opróżnij skrzynię, sprzedaj nadwyżkę kupcowi albo zbuduj magazyn — tragarze odwiozą towar z pełnych skrzyń."));
                case "TORCHES" -> warnings.add(warn("warn", "TORCHES", cap(function) + " bez pochodni",
                    status + where + ".", "Włóż pochodnie do skrzyni kopalni — górnicy stawiają jedną co ok. 7 bloków."));
                case "NO_STORE" -> warnings.add(warn("warn", "NO_STORE", cap(function) + " bez magazynu",
                    status + where + ".", "Doradca: postaw skrzynię w budynku albo /ludnosc magazyn powiaz " + shortId + "."));
                case "AUDIT" -> warnings.add(warn("error", "AUDIT", cap(function) + " wymaga audytu",
                    status + where + ".", "Administrator sprawdza skrzynię i zapis, potem /ludnosc magazyn audyt " + shortId + " potwierdz."));
                case "BLOCKED" -> warnings.add(warn("warn", "BLOCKED", cap(function) + " wstrzymane", status + where + ".", null));
                default -> {
                }
            }
        }
        final List<Map<String, Object>> functionRows = new ArrayList<>();
        int working = 0;
        int blockedCount = 0;
        int unloaded = 0;
        for (final Map.Entry<String, int[]> e : byFunction.entrySet()) {
            final int[] f = e.getValue();
            working += f[1];
            blockedCount += f[2];
            unloaded += f[4];
            functionRows.add(Json.obj("function", e.getKey(), "count", f[0], "working", f[1], "blocked", f[2], "full", f[3], "unloaded", f[4]));
        }
        if (unloaded > 0) {
            warnings.add(warn("info", "UNLOADED", unloaded + " " + plural(unloaded, "miejsce pracy", "miejsca pracy", "miejsc pracy")
                + " w niewczytanym terenie",
                "Skrzynie tych miejsc są w chunkach, których serwer teraz nie trzyma w pamięci; praca i odczyt wracają, gdy w pobliżu jest gracz.",
                null));
        }
        // top-level daily production of a newer Ludnosc
        if (!dailyFromLudnosc) {
            final List<Map<String, Object>> top = items(Plain.first(flow, "production", "daily"));
            if (top != null && !top.isEmpty()) {
                dailyFromLudnosc = true;
                top.forEach(d -> reportedDaily.merge((String) d.get("key"), (Long) d.get("amount"), Long::sum));
            }
        }

        // ---- stores
        final List<Map<String, Object>> stores = new ArrayList<>();
        final Map<String, Long> totals = new HashMap<>();
        final Map<String, Map<String, Long>> storeGoods = new LinkedHashMap<>();
        long usedSlots = 0;
        long allSlots = 0;
        int fullStores = 0;
        boolean anyWarehouse = false;
        for (final Object o : Plain.list(flow.get("stores"))) {
            final Map<String, Object> s = Plain.map(o);
            final String label = Plain.str(s.get("label"), "skrzynia");
            final String kind = storeKind(label);
            if (kind.equals("magazyn") || kind.equals("magazyn domyślny") || kind.equals("spichlerz")) {
                anyWarehouse = true;
            }
            final long used = Math.max(0, Plain.num(s.get("used"), 0));
            final long slots = Math.max(0, Plain.num(s.get("slots"), 0));
            final Map<String, Long> goods = new LinkedHashMap<>();
            Plain.map(s.get("goods")).forEach((k, v) -> {
                final Long n = Plain.num(v);
                if (n != null && n > 0) {
                    goods.merge(k, n, Long::sum);
                }
            });
            goods.forEach((k, v) -> totals.merge(k, v, Long::sum));
            storeGoods.put(label + "|" + Plain.str(s.get("world"), "") + "|" + Plain.num(s.get("x")) + "|" + Plain.num(s.get("y")) + "|"
                + Plain.num(s.get("z")), goods);
            usedSlots += used;
            allSlots += slots;
            final boolean full = slots > 0 && used >= slots;
            final double fill = slots > 0 ? Math.min(1.0, used / (double) slots) : 0.0;
            if (full) {
                fullStores++;
            }
            stores.add(Json.obj("label", label, "kind", kind, "used", used, "slots", slots, "fill", round3(fill), "full", full,
                "goods", goodsList(goods, 0), "goodsCount", goods.values().stream().mapToLong(Long::longValue).sum(),
                "world", Plain.str(s.get("world")), "x", Plain.num(s.get("x")), "y", Plain.num(s.get("y")), "z", Plain.num(s.get("z")),
                "extra", extra(s, KNOWN_STORE, List.of())));
            if (!full && slots > 0 && fill >= 0.9) {
                warnings.add(warn("warn", "STORE_ALMOST_FULL", "Skrzynia prawie pełna: " + label,
                    "Zajęte " + used + " z " + slots + " miejsc.", "Opróżnij ją albo zbuduj magazyn, zanim praca stanie."));
            }
        }
        stores.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("fill")).doubleValue())
            .thenComparing(m -> String.valueOf(m.get("label"))));

        // ---- granary
        final Map<String, Object> g = Plain.map(flow.get("granary"));
        final Info info = in.info();
        final Long food = firstNum(Plain.num(g.get("food")), info == null ? null : info.food());
        final Long capacity = firstNum(Plain.num(g.get("capacity")), info == null ? null : info.foodCapacity());
        final Long meal = Plain.num(g.get("meal"));
        final Long population = firstNum(Plain.num(g.get("population")), info == null ? null : (long) info.population());
        final Long housing = firstNum(Plain.num(g.get("housing")), info == null ? null : (long) info.housing());
        final Map<String, Object> settings = src.settings() == null ? Map.of() : src.settings();
        final long foodPerSettler = Plain.num(settings.get("foodPerSettler"), 20);
        final Double days = food != null && meal != null && meal > 0 ? round1(food / (double) meal) : null;
        final long freeBeds = population != null && housing != null ? Math.max(0, housing - population) : 0;
        final String growthText;
        final boolean growthPossible;
        if (population == null || housing == null) {
            growthText = "brak danych";
            growthPossible = false;
        } else if (housing <= population) {
            growthText = "wstrzymany — brak wolnych łóżek (" + population + "/" + housing + ")";
            growthPossible = false;
        } else if (food != null && food < foodPerSettler) {
            growthText = "wstrzymany — za mało jedzenia w spichlerzu (osadnik: " + foodPerSettler + " pkt)";
            growthPossible = false;
        } else {
            growthText = "możliwy (" + freeBeds + " " + plural((int) freeBeds, "wolne łóżko", "wolne łóżka", "wolnych łóżek") + ")";
            growthPossible = true;
        }
        final boolean hasGranary = (info != null && info.buildingFunctions().stream().anyMatch(f -> f.toLowerCase(Locale.ROOT).contains("spichlerz")))
            || stores.stream().anyMatch(s -> "spichlerz".equals(s.get("kind")));
        Map<String, Object> growth24 = null;
        if (in.growthBase() != null && population != null) {
            growth24 = Json.obj("delta", population - in.growthBase().population(), "from", in.growthBase().population(),
                "at", in.growthBase().at(), "source", in.growthBase().source());
        }
        final Map<String, Object> granary = Json.obj("food", food, "capacity", capacity, "meal", meal, "days", days,
            "fill", capacity != null && capacity > 0 && food != null ? round3(Math.min(1.0, food / (double) capacity)) : null,
            "population", population, "housing", housing, "freeBeds", freeBeds,
            "foodBalance", info == null ? null : info.foodBalance(), "hungryDays", info == null ? null : info.hungryDays(),
            "homelessDays", info == null ? null : info.homelessDays(), "foodPerSettler", foodPerSettler,
            "growth", Json.obj("possible", growthPossible, "text", growthText), "growth24h", growth24, "hasGranary", hasGranary,
            "storedFood", totals.entrySet().stream().filter(e -> Goods.kind(e.getKey()).equals("food") || Goods.kind(e.getKey()).equals("fish"))
                .mapToLong(Map.Entry::getValue).sum(),
            "extra", extra(g, Set.of("food", "capacity", "meal", "population", "housing"), List.of()));
        if (meal != null && meal > 0 && food != null) {
            if (food < meal) {
                warnings.add(warn("error", "FOOD", "Jedzenia nie starczy na jutrzejszy posiłek",
                    "W spichlerzu jest " + food + " pkt, a dzienny posiłek to " + meal + " pkt (brakuje " + (meal - food) + ").",
                    "Zbuduj pole albo przystań, przenieś plony do spichlerza, kup jedzenie u kupca albo włóż je do spichlerza."));
            } else if (days != null && days < 3) {
                warnings.add(warn("warn", "FOOD_LOW", "Zapas jedzenia na mniej niż 3 dni",
                    "Spichlerz wystarczy na około " + fmt1(days) + " " + (days < 2 ? "dzień" : "dni") + ".",
                    "Więcej pól lub przystań i spichlerz blisko pól — tragarze zanoszą plony do spichlerza."));
            }
        }
        if (info != null && info.hungryDays() > 0) {
            warnings.add(warn("error", "HUNGER", "Głód od " + info.hungryDays() + " " + plural(info.hungryDays(), "dnia", "dni", "dni"),
                "Obywatele nie dostają pełnych posiłków — spada podatek i ludzie odchodzą.", "Uzupełnij spichlerz (pola, przystań, kupiec)."));
        }
        if (!hasGranary && population != null && population > 0) {
            warnings.add(warn("warn", "NO_GRANARY", "Brak spichlerza",
                "Plony zostają w skrzyniach pól i idą tylko na dzienny posiłek — zapas nie rośnie, a ludność nie przybywa.",
                "Zbuduj spichlerz."));
        }
        if (!growthPossible && population != null && housing != null && housing <= population) {
            warnings.add(warn("info", "NO_BEDS", "Ludność nie rośnie — brak wolnych łóżek", growthText,
                "Zbuduj domy — obywatele z oszczędnościami stawiają też własne chaty."));
        }

        // ---- haulers
        final List<Map<String, Object>> walking = new ArrayList<>();
        for (final Object o : Plain.list(flow.get("haulers"))) {
            walking.add(haul(o));
        }
        final List<Map<String, Object>> recent = new ArrayList<>();
        for (final Object o : Plain.list(flow.get("recent"))) {
            final String line = Plain.str(o, "");
            final Matcher m = STAMP.matcher(line);
            recent.add(m.matches() ? Json.obj("time", m.group(1), "text", m.group(2)) : Json.obj("time", null, "text", line));
        }

        // ---- sites and materials
        final List<Map<String, Object>> sites = new ArrayList<>();
        final Map<String, Long> needed = new LinkedHashMap<>();
        final Set<String> waitingIds = new HashSet<>();
        final long waitMinutes = Plain.num(settings.get("materialWaitMinutes"), 60);
        final boolean requireMaterials = Boolean.TRUE.equals(Plain.bool(Plain.first(flow, "requireMaterials")))
            || flow.get("requireMaterials") == null && Boolean.TRUE.equals(Plain.bool(settings.get("requireMaterials")));
        int waitingSites = 0;
        for (final Object o : Plain.list(flow.get("sites"))) {
            final Map<String, Object> s = Plain.map(o);
            final String id = Plain.str(s.get("id"), "");
            final String status = Plain.str(s.get("status"), "");
            final Matcher wm = WAIT.matcher(status.trim());
            final boolean waiting = wm.matches();
            final boolean bought = status.toLowerCase(Locale.ROOT).startsWith("kupiono");
            String waitingFor = null;
            String source = null;
            Long waitedMs = null;
            Long buyInMs = null;
            if (waiting) {
                waitingSites++;
                waitingIds.add(id);
                waitingFor = wm.group(1).trim();
                final String why = wm.group(2) == null ? "" : wm.group(2);
                final Matcher fm = FROM.matcher(why);
                source = fm.find() ? fm.group(1).trim() : null; // otherwise the status line already says why
                if (in.tracker() != null) {
                    final long since = in.tracker().waitingSince(in.id(), id, waitingFor, now, Plain.num(s.get("waitingSince"), 0));
                    waitedMs = Math.max(0, now - since);
                    buyInMs = Math.max(0, waitMinutes * EconomyTracker.MINUTE - waitedMs);
                }
            }
            final List<Map<String, Object>> needs = new ArrayList<>();
            Plain.map(s.get("needs")).forEach((k, v) -> {
                final long units = Plain.num(v, 0);
                if (units > 0) {
                    needed.merge(k, units, Long::sum);
                    final long have = stockUnits(k, totals);
                    needs.add(Json.obj("key", k, "label", Goods.groupLabel(k), "units", units, "have", have, "missing", Math.max(0, units - have)));
                }
            });
            final String state = waiting ? "WAITING" : bought ? "BOUGHT" : status.isBlank() || status.toLowerCase(Locale.ROOT).contains("pracuj") ? "WORKING" : "OTHER";
            sites.add(Json.obj("id", id, "name", Plain.str(Plain.first(s, "name", "type"), "budowa"), "stage", Plain.str(s.get("stage"), ""),
                "percent", Math.max(0, Math.min(100, Plain.num(s.get("percent"), 0))), "builders", Plain.num(s.get("builders"), 0),
                "status", status, "state", state, "waitingFor", waitingFor, "source", source, "waitedMs", waitedMs, "buyInMs", buyInMs,
                "waitedApprox", waiting && Plain.num(s.get("waitingSince")) == null,
                "needs", needs, "x", Plain.num(s.get("x")), "y", Plain.num(s.get("y")), "z", Plain.num(s.get("z")),
                "extra", extra(s, KNOWN_SITE, List.of())));
        }
        if (in.tracker() != null) {
            in.tracker().keepWaiting(in.id(), waitingIds);
        }
        sites.sort(Comparator.comparing((Map<String, Object> m) -> "WAITING".equals(m.get("state")) ? 0 : 1)
            .thenComparing(m -> -((Number) m.get("percent")).longValue()));

        // aggregate materials: what the sites still need vs what lies in the loaded stores, and who makes it
        final List<Map<String, Object>> materials = new ArrayList<>();
        final Set<String> missingGroups = new HashSet<>();
        for (final Map.Entry<String, Long> e : needed.entrySet()) {
            final String key = e.getKey();
            final Goods.Group grp = Goods.group(key);
            final long have = stockUnits(key, totals);
            final List<String> producers = new ArrayList<>();
            boolean producerBlocked = false;
            for (final Map<String, Object> w : workplaces) {
                if (Goods.produces(grp, (String) w.get("function"))) {
                    producers.add((String) w.get("function"));
                    producerBlocked |= blocked((String) w.get("state"));
                }
            }
            final boolean produced = !producers.isEmpty();
            final long missing = Math.max(0, e.getValue() - have);
            materials.add(Json.obj("key", key, "label", Goods.groupLabel(key), "needed", e.getValue(), "have", have, "missing", missing,
                "producers", producers.stream().distinct().toList(), "produced", produced,
                "build", grp == null ? null : grp.build()));
            if (missing > 0 && requireMaterials) {
                missingGroups.add(key);
                final String gen = grp == null ? Goods.groupLabel(key) : grp.genitive();
                final String label = Goods.groupLabel(key);
                final String text = "Budowy potrzebują jeszcze " + e.getValue() + " jedn. " + gen + ", we wczytanych skrzyniach jest " + have + ".";
                final String hint;
                if (!produced) {
                    hint = "Zbuduj " + (grp == null ? "warsztat, który wytwarza " + label : grp.build()) + " albo kup " + label
                        + " u kupca — do tego czasu budowa kupuje " + label + " za monety z zamówienia.";
                } else if (producerBlocked) {
                    hint = "Producent stoi (" + String.join(", ", producers.stream().distinct().toList())
                        + ") — usuń przyczynę postoju; budowa po " + waitMinutes + " min kupi " + label + " za monety z zamówienia.";
                } else {
                    hint = "Tragarze przyniosą " + label + " (wytwarza: " + String.join(", ", producers.stream().distinct().toList())
                        + "), gdy pojawi się w skrzyniach; budowa czeka najwyżej " + waitMinutes + " min, potem kupuje za monety z zamówienia.";
                }
                warnings.add(warn(produced ? "warn" : "error", "MATERIAL", "Brakuje " + gen, text, hint));
            }
        }
        // sites waiting for a material nobody counted in needs (e.g. new groups)
        for (final Map<String, Object> s : sites) {
            final String wf = (String) s.get("waitingFor");
            if (wf == null) {
                continue;
            }
            final Goods.Group grp = Goods.group(wf);
            final String key = grp == null ? wf : grp.key();
            if (missingGroups.contains(key) || needed.containsKey(key)) {
                continue;
            }
            missingGroups.add(key);
            final boolean produced = workplaces.stream().anyMatch(w -> Goods.produces(grp, (String) w.get("function")));
            warnings.add(warn("warn", "MATERIAL", "Budowa czeka na: " + wf, s.get("name") + ": " + s.get("status"),
                produced ? "Tragarze przyniosą go z miejsca pracy, gdy będzie w skrzyni."
                    : "Zbuduj " + (grp == null ? "warsztat, który go wytwarza" : grp.build()) + " albo kup " + wf + " u kupca."));
        }
        if (!requireMaterials && !sites.isEmpty()) {
            warnings.add(warn("info", "COINS_ONLY", "Budowy opłacane monetami",
                "Serwer nie wymaga fizycznego materiału (construction.require-materials: false).", null));
        }
        if (fullStores > 0 && !anyWarehouse) {
            warnings.add(warn("warn", "NO_WAREHOUSE", "Brak magazynu kraju",
                "Pełne skrzynie miejsc pracy nie mają dokąd odwieźć towaru.", "Zbuduj magazyn albo wskaż magazyn domyślny (/ludnosc magazyn domyslny)."));
        }

        // ---- tracker sample (also feeds the production estimate) before the estimate, so it counts this answer too
        if (in.tracker() != null && src.flow() != null) {
            in.tracker().sample(in.id(), now, storeGoods, new EconomyTracker.Point(now, food, population,
                totals.values().stream().mapToLong(Long::longValue).sum(), (int) usedSlots, (int) allSlots, walking.size(), waitingSites));
        }

        // ---- production per day
        final EconomyTracker.Estimate est = in.tracker() == null ? null : in.tracker().estimate(in.id(), now, EconomyTracker.DAY);
        final Map<String, Object> daily;
        if (dailyFromLudnosc) {
            daily = Json.obj("source", "ludnosc", "items", goodsList(reportedDaily, 0), "coveredMs", null, "since", null,
                "windowMs", EconomyTracker.DAY);
        } else if (est != null && !est.perDay().isEmpty()) {
            daily = Json.obj("source", "estimate", "items", goodsList(est.perDay(), 0), "gained", goodsList(est.gained(), 0),
                "coveredMs", est.coveredMs(), "since", est.since(), "windowMs", est.windowMs());
        } else {
            daily = Json.obj("source", "none", "items", List.of(), "coveredMs", est == null ? 0 : est.coveredMs(),
                "since", est == null || est.since() == 0 ? null : est.since(), "windowMs", EconomyTracker.DAY);
        }
        final Map<String, Object> work = src.work();
        Map<String, Object> today = null;
        List<Map<String, Object>> workers = null;
        if (work != null) {
            today = Json.obj("food", Plain.num(work.get("foodToday")), "foodCap", Plain.num(work.get("foodCap")),
                "fish", Plain.num(work.get("fishToday")), "fishCap", Plain.num(work.get("fishCap")),
                "adults", Plain.num(work.get("adults")), "assigned", Plain.num(work.get("assigned")));
            workers = new ArrayList<>();
            final Map<String, Object> ws = Plain.map(work.get("workers"));
            final Map<String, Object> sl = Plain.map(work.get("slots"));
            final Map<String, Object> pl = Plain.map(work.get("places"));
            final Set<String> jobs = new java.util.LinkedHashSet<>(ws.keySet());
            jobs.addAll(sl.keySet());
            for (final String job : jobs) {
                workers.add(Json.obj("job", job, "label", jobLabel(job), "workers", Plain.num(ws.get(job), 0), "slots", Plain.num(sl.get(job), 0),
                    "places", Plain.num(pl.get(job), 0)));
            }
        }

        // ---- citizens
        final Map<String, Object> citizens = citizens(src, info);
        final long homeless = Plain.num(citizens.get("homeless"), 0);
        final long unemployed = Plain.num(citizens.get("unemployed"), 0);
        final long unpaid = Plain.num(citizens.get("unpaid"), 0);
        if (Boolean.TRUE.equals(citizens.get("available"))) {
            if (homeless > 0) {
                warnings.add(warn("warn", "HOMELESS", homeless + " " + plural((int) homeless, "bezdomny", "bezdomnych", "bezdomnych"),
                    "Nie wszyscy obywatele mają łóżko.", "Zbuduj domy — obywatele z oszczędnościami stawiają też własne chaty."));
            }
            if (unemployed > 0) {
                warnings.add(warn("info", "UNEMPLOYED", unemployed + " " + plural((int) unemployed, "bezrobotny", "bezrobotnych", "bezrobotnych"),
                    "Dorośli bez stałej pracy płacą mniejszy podatek.",
                    "Zbuduj miejsca pracy: pole, tartak, kamieniołom, kopalnię, przystań albo warsztat."));
            }
            if (unpaid > 0) {
                warnings.add(warn("warn", "UNPAID", unpaid + " " + plural((int) unpaid, "pracownik", "pracowników", "pracowników") + " bez pensji",
                    "Skarbiec kraju nie wystarczył na pensje.", "Wpłać pieniądze do skarbca kraju — pensje wypłaca skarbiec."));
            }
        }

        // ---- source problems
        final Map<String, String> errors = src.errors() == null ? Map.of() : src.errors();
        if (!in.ludnoscPresent()) {
            warnings.add(warn("warn", "NO_LUDNOSC", "Plugin Ludnosc nie działa", "Bez niego panel nie zna produkcji, magazynów ani obywateli.", null));
        } else if (src.flow() == null) {
            warnings.add(warn("warn", "NO_FLOW", "Brak danych przepływu materiałów",
                errors.getOrDefault("flow", "Zainstalowana Ludnosc nie udostępnia przepływu."), "Zaktualizuj plugin Ludnosc (flow-release5 lub nowszy)."));
        }
        if (workplaces.isEmpty() && src.flow() != null) {
            warnings.add(warn("info", "NO_WORKPLACES", "Kraj nic nie wytwarza",
                "Nie ma pól, tartaków, kamieniołomów, kopalń ani przystani.", "Zbuduj pierwsze miejsca pracy — produkcja trafia do skrzyń."));
        }

        warnings.sort(Comparator.comparingInt(w -> levelOrder((String) w.get("level"))));
        int errorsCount = 0;
        int warnCount = 0;
        for (final Map<String, Object> w : warnings) {
            if ("error".equals(w.get("level"))) {
                errorsCount++;
            } else if ("warn".equals(w.get("level"))) {
                warnCount++;
            }
        }

        // ---- unknown top-level fields of a newer Ludnosc
        final Map<String, Object> extraFlow = extra(flow, KNOWN_FLOW, List.of());
        final Long flowVersion = Plain.num(flow.get("version"));

        final List<Map<String, Object>> points = new ArrayList<>();
        if (in.tracker() != null) {
            for (final EconomyTracker.Point p : in.tracker().points(in.id(), now - EconomyTracker.DAY)) {
                points.add(Json.obj("t", p.t(), "food", p.food(), "population", p.population(), "goods", p.goods(), "usedSlots", p.usedSlots(),
                    "slots", p.slots(), "haulers", p.haulers(), "waitingSites", p.waitingSites()));
            }
        }

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("id", in.id().toString());
        out.put("name", in.name());
        out.put("color", in.color());
        out.put("npc", in.npc());
        out.put("access", in.access());
        out.put("generatedAt", now);
        out.put("available", in.ludnoscPresent() && src.flow() != null);
        out.put("settlement", info == null ? null : info.settlementName());
        out.put("sources", Json.obj("flow", src.flow() != null, "life", src.lifeStats() != null, "work", src.work() != null,
            "flowVersion", flowVersion, "unknown", extraFlow == null ? List.of() : List.copyOf(extraFlow.keySet()), "errors", errors));
        out.put("summary", Json.obj("workplaces", workplaces.size(), "working", working, "blocked", blockedCount, "unloaded", unloaded,
            "stores", stores.size(), "fullStores", fullStores, "usedSlots", usedSlots, "slots", allSlots,
            "goods", totals.values().stream().mapToLong(Long::longValue).sum(), "foodDays", days, "haulers", walking.size(),
            "sites", sites.size(), "waitingSites", waitingSites, "errors", errorsCount, "warnings", warnCount,
            "population", population, "housing", housing));
        out.put("production", Json.obj("workplaces", workplaces, "byFunction", functionRows, "daily", daily, "today", today, "workers", workers));
        out.put("stores", Json.obj("list", stores, "totals", goodsList(totals, 0), "usedSlots", usedSlots, "slots", allSlots,
            "fill", allSlots > 0 ? round3(usedSlots / (double) allSlots) : null, "fullCount", fullStores));
        out.put("granary", granary);
        out.put("haulers", Json.obj("count", walking.size(), "walking", walking, "recent", recent));
        out.put("sites", sites);
        out.put("materials", materials);
        out.put("warnings", warnings);
        out.put("citizens", citizens);
        out.put("history", Json.obj("points", points, "from", now - EconomyTracker.DAY, "to", now));
        out.put("settings", Json.obj("materialWaitMinutes", waitMinutes, "requireMaterials", requireMaterials, "foodPerSettler", foodPerSettler));
        out.put("extra", extraFlow);
        return out;
    }

    // ------------------------------------------------------------------ parts

    static Map<String, Object> citizens(final LudnoscBridge.Sources src, final Info info) {
        final Map<String, Object> s = src.lifeStats();
        final Map<String, Object> d = src.lifeDay() == null ? Map.of() : src.lifeDay();
        final boolean available = s != null && Plain.num(s.get("time"), 0) > 0;
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available);
        out.put("population", available ? Plain.num(s.get("population")) : info == null ? null : (long) info.population());
        out.put("adults", available ? Plain.num(s.get("adults")) : null);
        out.put("children", available ? Plain.num(s.get("children")) : null);
        out.put("housed", available ? Plain.num(s.get("housed")) : null);
        out.put("homeless", available ? Plain.num(s.get("homeless")) : null);
        out.put("employed", available ? Plain.num(s.get("employed")) : null);
        out.put("unemployed", available ? Plain.num(s.get("unemployed")) : null);
        out.put("couples", available ? Plain.num(s.get("couples")) : null);
        out.put("savings", available ? Plain.num(s.get("savings")) : null);
        out.put("average", available ? Plain.num(s.get("average")) : null);
        out.put("unpaid", available ? Plain.num(s.get("unpaid")) : null);
        out.put("statsAt", available ? Plain.num(s.get("time")) : null);
        // last daily settlement (LifeStore.CountryLife): tax collected from the citizens, wages paid by the treasury
        final Long taxPaid = Plain.num(d.get("lastTaxPaid"));
        out.put("taxDaily", taxPaid != null && taxPaid > 0 ? taxPaid : info == null ? taxPaid : Long.valueOf(info.dailyTax()));
        out.put("taxSource", taxPaid != null && taxPaid > 0 ? "settled" : info == null ? null : "expected");
        out.put("taxDue", Plain.num(d.get("lastTaxDue")));
        out.put("wagesDaily", Plain.num(d.get("lastWagesPaid")));
        out.put("wagesDue", Plain.num(d.get("lastWagesDue")));
        out.put("income", Plain.num(d.get("lastIncome")));
        out.put("meals", Plain.num(d.get("lastMeals")));
        out.put("market", Plain.num(d.get("lastMarket")));
        out.put("workers", Plain.num(d.get("lastWorkers")));
        out.put("lastDay", Plain.num(d.get("lastDay")));
        out.put("huts", Plain.num(d.get("hutsOrdered")));
        return out;
    }

    private static Map<String, Object> haul(final Object o) {
        if (o instanceof Map<?, ?>) {
            final Map<String, Object> m = Plain.map(o);
            final String item = Plain.str(Plain.first(m, "item", "material"), "");
            return Json.obj("text", Plain.str(m.get("text"), Plain.num(m.get("amount"), 0) + "× " + item), "amount", Plain.num(m.get("amount")),
                "item", item, "from", Plain.str(m.get("from")), "to", Plain.str(m.get("to")));
        }
        final String line = Plain.str(o, "");
        final Matcher m = HAUL.matcher(line.trim());
        if (m.matches()) {
            return Json.obj("text", line, "amount", Long.parseLong(m.group(1)), "item", m.group(2).trim(), "from", m.group(3).trim(), "to", m.group(4).trim());
        }
        return Json.obj("text", line, "amount", null, "item", null, "from", null, "to", null);
    }

    /** Daily output a newer Ludnosc may report on a workplace (null = none). */
    static List<Map<String, Object>> dailyOf(final Map<String, Object> w) {
        for (final String k : DAILY_KEYS) {
            final Object v = w.get(k);
            if (v == null) {
                continue;
            }
            if (v instanceof Number n) {
                return List.of(Json.obj("key", "jedn.", "label", "jednostki", "kind", "other", "amount", Math.max(0, Math.round(n.doubleValue()))));
            }
            final List<Map<String, Object>> l = items(v);
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    /** {@code {material: n}} or {@code [{key|material, amount|count}]} → goods list; null when the shape is unknown. */
    static List<Map<String, Object>> items(final Object v) {
        if (v instanceof Map<?, ?>) {
            final Map<String, Long> m = new LinkedHashMap<>();
            Plain.map(v).forEach((k, x) -> {
                final Long n = Plain.num(x);
                if (n != null && n > 0) {
                    m.merge(k, n, Long::sum);
                }
            });
            return goodsList(m, 0);
        }
        if (v instanceof List<?>) {
            final Map<String, Long> m = new LinkedHashMap<>();
            for (final Object o : Plain.list(v)) {
                final Map<String, Object> e = Plain.map(o);
                final String k = Plain.str(Plain.first(e, "key", "material", "item"));
                final Long n = Plain.num(Plain.first(e, "amount", "count", "perDay", "daily"));
                if (k != null && n != null && n > 0) {
                    m.merge(k, n, Long::sum);
                }
            }
            return goodsList(m, 0);
        }
        return null;
    }

    static List<Map<String, Object>> goodsList(final Map<String, Long> goods, final int limit) {
        final List<Map.Entry<String, Long>> l = new ArrayList<>(goods.entrySet());
        l.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final Map.Entry<String, Long> e : l) {
            if (limit > 0 && out.size() >= limit) {
                break;
            }
            out.add(Json.obj("key", e.getKey(), "label", Goods.label(e.getKey()), "kind", Goods.kind(e.getKey()), "amount", e.getValue()));
        }
        return out;
    }

    /** Construction units of a group in the loaded stores (logs ×4, planks/stone ×1 — as Ludnosc counts them). */
    static long stockUnits(final String group, final Map<String, Long> totals) {
        long sum = 0;
        for (final Map.Entry<String, Long> e : totals.entrySet()) {
            sum += e.getValue() * Goods.units(group, e.getKey());
        }
        return sum;
    }

    /** Unknown fields of a Ludnosc record, plain and bounded; null when there are none. */
    static Map<String, Object> extra(final Map<String, Object> m, final Set<String> known, final List<String> alsoKnown) {
        Map<String, Object> out = null;
        for (final Map.Entry<String, Object> e : m.entrySet()) {
            if (known.contains(e.getKey()) || alsoKnown.contains(e.getKey())) {
                continue;
            }
            if (out == null) {
                out = new LinkedHashMap<>();
            }
            if (out.size() >= 40) {
                break;
            }
            out.put(e.getKey(), Plain.of(e.getValue()));
        }
        return out;
    }

    static String storeKind(final String label) {
        final int p = label.indexOf(" (");
        return (p > 0 ? label.substring(0, p) : label).trim().toLowerCase(Locale.ROOT);
    }

    static String jobLabel(final String job) {
        return switch (job == null ? "" : job.toUpperCase(Locale.ROOT)) {
            case "FARMER" -> "rolnik";
            case "MINER" -> "górnik";
            case "QUARRYMAN" -> "kamieniarz";
            case "WOODCUTTER" -> "drwal";
            case "FISHER" -> "rybak";
            case "SHEPHERD" -> "owczarz";
            case "GLASSBLOWER", "GLASSMAKER" -> "hutnik szkła";
            case "BRICKMAKER" -> "ceglarz";
            default -> job == null ? "?" : job.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static Map<String, Object> warn(final String level, final String code, final String title, final String text, final String hint) {
        return Json.obj("level", level, "code", code, "title", title, "text", text, "hint", hint);
    }

    private static int levelOrder(final String level) {
        return "error".equals(level) ? 0 : "warn".equals(level) ? 1 : 2;
    }

    private static String coords(final Map<String, Object> w) {
        final Long x = Plain.num(w.get("x"));
        final Long z = Plain.num(w.get("z"));
        return x == null || z == null ? "" : " (x " + x + ", z " + z + ")";
    }

    private static String cap(final String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Polish plural: 1 → one, 2–4 (not 12–14) → few, else many. */
    static String plural(final int n, final String one, final String few, final String many) {
        final int a = Math.abs(n);
        if (a == 1) {
            return one;
        }
        final int d = a % 10;
        final int t = a % 100;
        return d >= 2 && d <= 4 && (t < 12 || t > 14) ? few : many;
    }

    private static Long firstNum(final Long a, final Long b) {
        return a != null ? a : b;
    }

    private static double round3(final double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static double round1(final double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String fmt1(final double v) {
        return String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
    }
}
