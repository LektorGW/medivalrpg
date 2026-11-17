// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.panel.util.Json;

/** {@code country.economy} answer from a flow-release5-shaped {@code materialFlow} map (and newer / broken variants). */
@SuppressWarnings("unchecked")
class EconomyReportTest {

    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final long NOW = 1_800_000_000_000L;

    /** Exactly the shape LogisticsService.flowData builds (flow-release5). */
    static Map<String, Object> flow() {
        final Map<String, Object> f = new LinkedHashMap<>();
        f.put("workplaces", List.of(
            Map.of("id", "11111111-aaaa-bbbb-cccc-000000000001", "function", "kopalnia", "status", "Magazyn pełny — praca wstrzymana", "x", 100, "z", 200),
            Map.of("id", "11111111-aaaa-bbbb-cccc-000000000002", "function", "kamieniołom", "status", "Magazyn gotowy", "x", 120, "z", 210),
            Map.of("id", "11111111-aaaa-bbbb-cccc-000000000003", "function", "pole", "status", "Magazyn gotowy", "x", 90, "z", 180),
            Map.of("id", "11111111-aaaa-bbbb-cccc-000000000004", "function", "pole", "status",
                "Praca wstrzymana — magazyn niezaładowany, zniszczony lub zmienił właściciela", "x", 300, "z", 400),
            Map.of("id", "22222222-aaaa-bbbb-cccc-000000000005", "function", "kopalnia", "status",
                "Brak oświetlenia — brak pochodni w magazynie, praca wstrzymana", "x", 140, "z", 230)));
        f.put("stores", List.of(
            Map.of("label", "kopalnia (100 60 200)", "used", 27, "slots", 27, "goods",
                Map.of("minecraft:cobblestone", 1600, "minecraft:raw_iron", 64, "minecraft:coal", 40), "world", "world", "x", 100, "y", 60, "z", 200),
            Map.of("label", "pole (90 64 180)", "used", 3, "slots", 27, "goods", Map.of("minecraft:wheat", 120, "minecraft:wheat_seeds", 30),
                "world", "world", "x", 90, "y", 64, "z", 180),
            Map.of("label", "kamieniołom (120 62 210)", "used", 25, "slots", 27, "goods", Map.of("minecraft:cobblestone", 1500),
                "world", "world", "x", 120, "y", 62, "z", 210)));
        f.put("granary", Map.of("food", 90, "capacity", 500, "meal", 40, "population", 20, "housing", 20));
        f.put("sites", List.of(
            Map.of("id", "33333333-0000-0000-0000-000000000001", "name", "Dom", "stage", "konstrukcja: słupy i belki", "percent", 42,
                "builders", 3, "status", "czeka na drewno — brak w dostępnych magazynach kraju; włóż drewno do skrzyni budowy albo magazynu",
                "needs", Map.of("drewno", 120, "kamien", 30), "x", 10, "y", 64, "z", 20),
            Map.of("id", "33333333-0000-0000-0000-000000000002", "name", "Kuźnia", "stage", "fundament", "percent", 12, "builders", 2,
                "status", "czeka na kamień — tragarz przyniesie z: kamieniołom (120 62 210)", "needs", Map.of("kamien", 80), "x", 30, "y", 64, "z", 20),
            Map.of("id", "33333333-0000-0000-0000-000000000003", "name", "Studnia", "stage", "wykończenie", "percent", 95, "builders", 1,
                "status", "", "needs", Map.of(), "x", 50, "y", 64, "z", 20)));
        f.put("haulers", List.of("12× bruk: kamieniołom (120 62 210) → Kuźnia (30 64 20)", "dziwny wpis bez strzałki"));
        f.put("recent", List.of("12:30:05 Spichlerz przyjął 40 pkt jedzenia", "bez godziny"));
        f.put("requireMaterials", true);
        return f;
    }

    static LudnoscBridge.Sources sources(final Map<String, Object> flow) {
        final Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("population", 20L);
        stats.put("adults", 16L);
        stats.put("children", 4L);
        stats.put("housed", 18L);
        stats.put("homeless", 2L);
        stats.put("employed", 11L);
        stats.put("unemployed", 5L);
        stats.put("couples", 6L);
        stats.put("savings", 12_345L);
        stats.put("average", 617L);
        stats.put("unpaid", 1L);
        stats.put("time", NOW - 5000);
        final Map<String, Object> day = Map.of("lastTaxPaid", 900L, "lastTaxDue", 950L, "lastWagesPaid", 400L, "lastWagesDue", 420L,
            "lastIncome", 1500L, "lastMeals", 300L, "lastMarket", 50L, "lastWorkers", 11L, "lastDay", 20_000L, "hutsOrdered", 2L);
        final Map<String, Object> work = Map.of("workers", Map.of("FARMER", 4L, "MINER", 5L), "slots", Map.of("FARMER", 6L, "MINER", 5L),
            "places", Map.of("FARMER", 2L, "MINER", 2L), "adults", 16L, "assigned", 9L, "foodToday", 30L, "foodCap", 120L, "fishToday", 0L, "fishCap", 40L);
        return new LudnoscBridge.Sources(flow, stats, day, work, Map.of("materialWaitMinutes", 60L, "foodPerSettler", 20L), Map.of());
    }

    static EconomyReport.Info info() {
        return new EconomyReport.Info(20, 20, 90, 500, -10, 700, 0, 0, List.of("mieszkanie", "kopalnia", "pole", "kamieniołom"), "Gród");
    }

    static Map<String, Object> build(final LudnoscBridge.Sources s, final EconomyTracker t) {
        return EconomyReport.build(new EconomyReport.Input(ID, "Królestwo Polan", "#B02E26", false, "RULER", NOW, s, info(), t, null, true));
    }

    @SuppressWarnings("unchecked")
    static <T> T at(final Object o, final String path) {
        Object cur = o;
        for (final String p : path.split("\\.")) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(p);
            } else if (cur instanceof List<?> l) {
                cur = l.get(Integer.parseInt(p));
            } else {
                return null;
            }
        }
        return (T) cur;
    }

    static List<Map<String, Object>> warnings(final Map<String, Object> r) {
        return at(r, "warnings");
    }

    static Map<String, Object> warning(final Map<String, Object> r, final String code) {
        return warnings(r).stream().filter(w -> code.equals(w.get("code"))).findFirst().orElse(null);
    }

    @Test
    void workplacesAreClassifiedAndBlockedOnesWarnWithAdvice() {
        final Map<String, Object> r = build(sources(flow()), new EconomyTracker());
        final List<Map<String, Object>> w = at(r, "production.workplaces");
        assertEquals(List.of("FULL", "OK", "OK", "UNLOADED", "TORCHES"), w.stream().map(x -> x.get("state")).toList());
        assertEquals("11111111", w.get(0).get("short"));
        assertEquals("pełna skrzynia — stoi", w.get(0).get("stateLabel"));
        assertEquals(5, (int) at(r, "summary.workplaces"));
        assertEquals(2, (int) at(r, "summary.working"));
        assertEquals(2, (int) at(r, "summary.blocked"));
        assertEquals(1, (int) at(r, "summary.unloaded"));
        final Map<String, Object> full = warning(r, "FULL_STORE");
        assertNotNull(full);
        assertEquals("error", full.get("level"));
        assertTrue(((String) full.get("title")).startsWith("Kopalnia stoi"), (String) full.get("title"));
        assertTrue(((String) full.get("hint")).contains("zbuduj magazyn"));
        assertTrue(((String) warning(r, "TORCHES").get("hint")).contains("pochodnie"));
        assertEquals("info", warning(r, "UNLOADED").get("level"));
        final List<Map<String, Object>> byFn = at(r, "production.byFunction");
        assertEquals(List.of("kamieniołom", "kopalnia", "pole"), byFn.stream().map(x -> x.get("function")).toList());
        assertEquals(1, byFn.get(1).get("full"));
    }

    @Test
    void storesHaveFillTotalsAndFullFlag() {
        final Map<String, Object> r = build(sources(flow()), new EconomyTracker());
        final List<Map<String, Object>> stores = at(r, "stores.list");
        assertEquals("kopalnia (100 60 200)", stores.get(0).get("label"), "najpełniejsza pierwsza");
        assertEquals(true, stores.get(0).get("full"));
        assertEquals(1.0, stores.get(0).get("fill"));
        assertEquals("kopalnia", stores.get(0).get("kind"));
        assertEquals("Bruk", at(stores.get(0), "goods.0.label"));
        assertEquals(1, (int) at(r, "stores.fullCount"));
        final List<Map<String, Object>> totals = at(r, "stores.totals");
        assertEquals("minecraft:cobblestone", totals.get(0).get("key"));
        assertEquals(3100L, totals.get(0).get("amount"));
        assertEquals("stone", totals.get(0).get("kind"));
        assertNotNull(warning(r, "STORE_ALMOST_FULL"), "kamieniołom 25/27");
        assertNotNull(warning(r, "NO_WAREHOUSE"), "pełna skrzynia i brak magazynu kraju");
    }

    @Test
    void granaryDaysGrowthAndCitizens() {
        final Map<String, Object> r = build(sources(flow()), new EconomyTracker());
        assertEquals(2.3, (double) at(r, "granary.days"), 1e-9);
        assertEquals(false, at(r, "granary.growth.possible"));
        assertTrue(((String) at(r, "granary.growth.text")).contains("brak wolnych łóżek"));
        assertEquals(120L + 0L, (long) at(r, "granary.storedFood"), "pszenica w skrzyniach");
        assertEquals("warn", warning(r, "FOOD_LOW").get("level"));
        assertNotNull(warning(r, "NO_GRANARY"));
        assertEquals(true, at(r, "citizens.available"));
        assertEquals(2L, (long) at(r, "citizens.homeless"));
        assertEquals(6L, (long) at(r, "citizens.couples"));
        assertEquals(900L, (long) at(r, "citizens.taxDaily"));
        assertEquals("settled", at(r, "citizens.taxSource"));
        assertEquals(400L, (long) at(r, "citizens.wagesDaily"));
        assertNotNull(warning(r, "HOMELESS"));
        assertNotNull(warning(r, "UNEMPLOYED"));
        assertNotNull(warning(r, "UNPAID"));
        assertEquals(30L, (long) at(r, "production.today.food"));
        final List<Map<String, Object>> workers = at(r, "production.workers");
        assertEquals("rolnik", workers.stream().filter(w -> "FARMER".equals(w.get("job"))).findFirst().orElseThrow().get("label"));
        assertEquals(4L, workers.stream().filter(w -> "FARMER".equals(w.get("job"))).findFirst().orElseThrow().get("workers"));
    }

    @Test
    void sitesShowWaitSourceAndMissingMaterialWithAdvice() {
        final EconomyTracker t = new EconomyTracker();
        final Map<String, Object> r = build(sources(flow()), t);
        final List<Map<String, Object>> sites = at(r, "sites");
        assertEquals("WAITING", sites.get(0).get("state"));
        final Map<String, Object> forge = sites.stream().filter(s -> "Kuźnia".equals(s.get("name"))).findFirst().orElseThrow();
        assertEquals("kamień", forge.get("waitingFor"));
        assertEquals("kamieniołom (120 62 210)", forge.get("source"));
        assertEquals(0L, forge.get("waitedMs"));
        assertEquals(60 * EconomyTracker.MINUTE, forge.get("buyInMs"));
        assertEquals(true, forge.get("waitedApprox"));
        final Map<String, Object> well = sites.stream().filter(s -> "Studnia".equals(s.get("name"))).findFirst().orElseThrow();
        assertEquals("WORKING", well.get("state"));
        // wood: needed 120 units, none in stores and no sawmill → error with "zbuduj tartak … kup u kupca"
        final List<Map<String, Object>> mats = at(r, "materials");
        final Map<String, Object> wood = mats.stream().filter(m -> "drewno".equals(m.get("key"))).findFirst().orElseThrow();
        assertEquals(120L, wood.get("needed"));
        assertEquals(0L, wood.get("have"));
        assertEquals(false, wood.get("produced"));
        final Map<String, Object> woodWarn = warnings(r).stream().filter(w -> "MATERIAL".equals(w.get("code"))
            && ((String) w.get("title")).contains("drewna")).findFirst().orElseThrow();
        assertEquals("error", woodWarn.get("level"));
        assertTrue(((String) woodWarn.get("hint")).contains("Zbuduj tartak albo kup drewno u kupca"), (String) woodWarn.get("hint"));
        // stone: 110 needed, 3100 cobblestone in stores → no shortage
        final Map<String, Object> stone = mats.stream().filter(m -> "kamien".equals(m.get("key"))).findFirst().orElseThrow();
        assertEquals(3100L, stone.get("have"));
        assertEquals(0L, stone.get("missing"));
        assertEquals(true, stone.get("produced"));
        // a later answer: the waiting clock keeps running
        final Map<String, Object> later = EconomyReport.build(new EconomyReport.Input(ID, "Królestwo Polan", "#B02E26", false, "RULER",
            NOW + 20 * EconomyTracker.MINUTE, sources(flow()), info(), t, null, true));
        final Map<String, Object> forge2 = ((List<Map<String, Object>>) at(later, "sites")).stream()
            .filter(s -> "Kuźnia".equals(s.get("name"))).findFirst().orElseThrow();
        assertEquals(20 * EconomyTracker.MINUTE, forge2.get("waitedMs"));
        assertEquals(40 * EconomyTracker.MINUTE, forge2.get("buyInMs"));
    }

    @Test
    void glassFromANewProductionChainGetsGlassworksAdvice() {
        final Map<String, Object> f = flow();
        f.put("sites", List.of(Map.of("id", "s9", "name", "Kamienica", "stage", "okna", "percent", 70, "builders", 2,
            "status", "czeka na szkło — brak w dostępnych magazynach kraju", "needs", Map.of("szklo", 12))));
        final Map<String, Object> r = build(sources(f), new EconomyTracker());
        final Map<String, Object> glass = warnings(r).stream().filter(w -> "MATERIAL".equals(w.get("code"))
            && ((String) w.get("title")).contains("szkła")).findFirst().orElseThrow();
        assertTrue(((String) glass.get("hint")).contains("Zbuduj hutę szkła albo kup szkło u kupca"), (String) glass.get("hint"));
        // once a glassworks exists and works, the advice says the haulers will bring it
        final List<Object> wps = new java.util.ArrayList<>((List<?>) f.get("workplaces"));
        wps.add(Map.of("id", "44444444-0000-0000-0000-000000000001", "function", "huta szkła", "status", "Magazyn gotowy"));
        f.put("workplaces", wps);
        final Map<String, Object> r2 = build(sources(f), new EconomyTracker());
        final Map<String, Object> glass2 = warnings(r2).stream().filter(w -> "MATERIAL".equals(w.get("code"))
            && ((String) w.get("title")).contains("szkła")).findFirst().orElseThrow();
        assertEquals("warn", glass2.get("level"));
        assertTrue(((String) glass2.get("hint")).startsWith("Tragarze przyniosą szkło (wytwarza: huta szkła)"), (String) glass2.get("hint"));
    }

    @Test
    void haulersAndRecentAreParsed() {
        final Map<String, Object> r = build(sources(flow()), new EconomyTracker());
        assertEquals(2, (int) at(r, "haulers.count"));
        assertEquals(12L, (long) at(r, "haulers.walking.0.amount"));
        assertEquals("bruk", at(r, "haulers.walking.0.item"));
        assertEquals("kamieniołom (120 62 210)", at(r, "haulers.walking.0.from"));
        assertEquals("Kuźnia (30 64 20)", at(r, "haulers.walking.0.to"));
        assertNull(at(r, "haulers.walking.1.amount"));
        assertEquals("dziwny wpis bez strzałki", at(r, "haulers.walking.1.text"));
        assertEquals("12:30:05", at(r, "haulers.recent.0.time"));
        assertNull(at(r, "haulers.recent.1.time"));
    }

    @Test
    void unknownFieldsOfANewerLudnoscArePassedOnAndDailyOutputIsUsed() {
        final Map<String, Object> f = flow();
        f.put("version", 2);
        f.put("chains", List.of(Map.of("name", "szkło", "steps", List.of("piasek", "węgiel", "szkło"))));
        final List<Object> wps = new java.util.ArrayList<>();
        wps.add(Map.of("id", "55555555-0000-0000-0000-000000000001", "function", "cegielnia", "status", "Magazyn gotowy",
            "daily", Map.of("minecraft:brick", 96), "workers", 3));
        wps.add(Map.of("id", "55555555-0000-0000-0000-000000000002", "function", "tartak", "status", "Magazyn gotowy", "perDay", 40));
        f.put("workplaces", wps);
        final Map<String, Object> r = build(sources(f), new EconomyTracker());
        assertEquals(2L, (long) at(r, "sources.flowVersion"));
        assertEquals(List.of("chains"), at(r, "sources.unknown"));
        assertNotNull(at(r, "extra.chains"));
        assertEquals("ludnosc", at(r, "production.daily.source"));
        final List<Map<String, Object>> items = at(r, "production.daily.items");
        assertEquals("minecraft:brick", items.get(0).get("key"));
        assertEquals(96L, items.get(0).get("amount"));
        assertEquals("Cegła", items.get(0).get("label"));
        assertEquals(3L, (long) at(r, "production.workplaces.0.extra.workers"), "nowe pole miejsca pracy");
        assertEquals(40L, (long) at(r, "production.workplaces.1.daily.0.amount"));
    }

    @Test
    void productionIsEstimatedFromTrackerWhenLudnoscDoesNotCountIt() {
        final EconomyTracker t = new EconomyTracker();
        final Map<String, Object> f1 = flow();
        EconomyReport.build(new EconomyReport.Input(ID, "P", "#000000", false, "RULER", NOW, sources(f1), info(), t, null, true));
        final Map<String, Object> f2 = flow();
        f2.put("stores", List.of(
            Map.of("label", "kopalnia (100 60 200)", "used", 27, "slots", 27, "goods",
                Map.of("minecraft:cobblestone", 1700, "minecraft:raw_iron", 70, "minecraft:coal", 40), "world", "world", "x", 100, "y", 60, "z", 200),
            Map.of("label", "pole (90 64 180)", "used", 3, "slots", 27, "goods", Map.of("minecraft:wheat", 100, "minecraft:wheat_seeds", 30),
                "world", "world", "x", 90, "y", 64, "z", 180),
            Map.of("label", "kamieniołom (120 62 210)", "used", 25, "slots", 27, "goods", Map.of("minecraft:cobblestone", 1500),
                "world", "world", "x", 120, "y", 62, "z", 210)));
        final Map<String, Object> r = EconomyReport.build(new EconomyReport.Input(ID, "P", "#000000", false, "RULER",
            NOW + 30 * EconomyTracker.MINUTE, sources(f2), info(), t, null, true));
        assertEquals("estimate", at(r, "production.daily.source"));
        final Map<String, Long> perDay = ((List<Map<String, Object>>) at(r, "production.daily.items")).stream()
            .collect(Collectors.toMap(m -> (String) m.get("key"), m -> (Long) m.get("amount")));
        assertEquals(100L * 48, perDay.get("minecraft:cobblestone"));
        assertEquals(6L * 48, perDay.get("minecraft:raw_iron"));
        assertFalse(perDay.containsKey("minecraft:wheat"), "zjedzona pszenica to nie produkcja");
        assertEquals(2, ((List<?>) at(r, "history.points")).size());
    }

    @Test
    void missingOrBrokenFlowStillAnswers() {
        // Ludnosc present but without materialFlow (older jar)
        final LudnoscBridge.Sources none = new LudnoscBridge.Sources(null, null, null, null, Map.of(),
            Map.of("flow", "Zainstalowana Ludnosc nie ma metody materialFlow — zaktualizuj plugin."));
        final Map<String, Object> r = build(none, new EconomyTracker());
        assertEquals(false, r.get("available"));
        assertTrue(((String) warning(r, "NO_FLOW").get("text")).contains("materialFlow"));
        assertEquals(20L, (long) at(r, "granary.population"), "z LudnoscApi.info");
        assertEquals(700L, (long) at(r, "citizens.taxDaily"), "przewidywany podatek z info");
        assertEquals("expected", at(r, "citizens.taxSource"));
        // wrong types everywhere: no exception, empty parts
        final Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("workplaces", "nie lista");
        bad.put("stores", List.of("tekst", Map.of("label", 5, "used", "x", "slots", -3, "goods", List.of(1, 2))));
        bad.put("granary", List.of());
        bad.put("sites", List.of(Map.of("needs", "brak", "percent", "150")));
        bad.put("haulers", Map.of());
        final Map<String, Object> rb = EconomyReport.build(new EconomyReport.Input(ID, "P", "#000000", false, "ADMIN", NOW,
            new LudnoscBridge.Sources(bad, Map.of("population", "x"), null, null, null, null), null, null, null, true));
        assertEquals(0, (int) at(rb, "summary.workplaces"));
        assertEquals(100L, (long) at(rb, "sites.0.percent"), "procent przycięty do 100");
        assertEquals(false, at(rb, "citizens.available"));
        assertNotNull(Json.GSON.toJson(rb), "serializuje się");
    }

    @Test
    void ludnoscMissingGivesWarning() {
        final Map<String, Object> r = EconomyReport.build(new EconomyReport.Input(ID, "P", "#000000", true, "OWNER", NOW,
            null, null, new EconomyTracker(), null, false));
        assertEquals(false, r.get("available"));
        assertNotNull(warning(r, "NO_LUDNOSC"));
        assertEquals("OWNER", r.get("access"));
    }

    @Test
    void warningsAreOrderedBySeverity() {
        final Map<String, Object> r = build(sources(flow()), new EconomyTracker());
        final List<String> levels = warnings(r).stream().map(w -> (String) w.get("level")).toList();
        int last = 0;
        for (final String l : levels) {
            final int o = "error".equals(l) ? 0 : "warn".equals(l) ? 1 : 2;
            assertTrue(o >= last, "kolejność: " + levels);
            last = o;
        }
        assertEquals((int) at(r, "summary.errors"), (int) levels.stream().filter("error"::equals).count());
    }

    @Test
    void polishPlurals() {
        assertEquals("bezdomny", EconomyReport.plural(1, "bezdomny", "bezdomnych", "bezdomnych"));
        assertEquals("wolne łóżka", EconomyReport.plural(3, "wolne łóżko", "wolne łóżka", "wolnych łóżek"));
        assertEquals("wolnych łóżek", EconomyReport.plural(12, "wolne łóżko", "wolne łóżka", "wolnych łóżek"));
        assertEquals("wolne łóżka", EconomyReport.plural(22, "wolne łóżko", "wolne łóżka", "wolnych łóżek"));
    }

    @Test
    void goodsNamesAndUnits() {
        assertEquals("Kłoda dębowa", Goods.label("minecraft:oak_log"));
        assertEquals("Wełna (red)", Goods.label("minecraft:red_wool"));
        assertEquals("Raw tin", Goods.label("minecraft:raw_tin"));
        assertEquals(4, Goods.units("drewno", "minecraft:spruce_log"));
        assertEquals(1, Goods.units("drewno", "minecraft:oak_planks"));
        assertEquals(1, Goods.units("kamien", "minecraft:cobbled_deepslate"));
        assertEquals(0, Goods.units("kamien", "minecraft:stone_slab"));
        assertEquals(0, Goods.units("kamien", "minecraft:coal_ore"));
        assertEquals(1, Goods.units("szklo", "minecraft:glass_pane"));
        assertEquals("kamien", Goods.group("kamień").key());
        assertTrue(Goods.produces(Goods.group("kamien"), "Kopalnia"));
        assertEquals("food", Goods.kind("minecraft:bread"));
        assertEquals("fish", Goods.kind("minecraft:cod"));
    }
}
