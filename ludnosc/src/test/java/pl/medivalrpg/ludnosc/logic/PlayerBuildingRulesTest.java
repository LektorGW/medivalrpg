// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.logic.PlayerBuildingRules.Def;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.OwnBuilding;
import pl.medivalrpg.ludnosc.service.LudnoscData;
import pl.medivalrpg.ludnosc.storage.DataCodec;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

class PlayerBuildingRulesTest {

    private static Def def(final String key) {
        return PlayerBuildingRules.DEFAULTS.stream().filter(d -> d.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void typeIdsAreUnknownTemplatesWithAPrefix() {
        assertEquals("wlasny_kuznia", PlayerBuildingRules.typeId("kuznia"));
        assertEquals("kuznia", PlayerBuildingRules.keyOf("wlasny_kuznia"));
        assertTrue(PlayerBuildingRules.isPlayerType("wlasny_mieszkanie"));
        assertFalse(PlayerBuildingRules.isPlayerType("kuznia"));
        assertFalse(PlayerBuildingRules.isPlayerType("wlasny_"));
        assertFalse(PlayerBuildingRules.isPlayerType("zgloszony"));
        assertNull(PlayerBuildingRules.keyOf("dom"));
        // never mistaken for a copy of a player building (kopia_*) or another known type
        assertFalse(BlueprintRules.isBlueprintId("wlasny_kuznia"));
    }

    @Test
    void defaultsOnlyOfferFunctionsThatWorkWithoutATemplateAndEpochAtMostTwo() {
        assertEquals(List.of("mieszkanie", "spichlerz", "magazyn", "koszary", "kuznia", "studnia", "karczma", "kosciol", "ratusz"),
            PlayerBuildingRules.DEFAULTS.stream().map(Def::key).toList());
        for (final Def d : PlayerBuildingRules.DEFAULTS) {
            assertFalse(PlayerBuildingRules.EXCLUDED.contains(d.function()), d.key());
            assertFalse(d.needs().isEmpty(), d.key());
            for (final String m : d.materials()) {
                assertTrue(PlayerBuildingRules.TOKENS.contains(m) || Material.matchMaterial(m) != null, d.key() + ": " + m);
            }
            final var in = PlayerBuildingRulesTest.class.getResourceAsStream("/budynki/" + d.template() + ".yml");
            assertNotNull(in, "szablon " + d.template());
            final YamlConfiguration t = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(t.getInt("requires.era", 1) <= 2, d.template() + " epoka");
            assertEquals(d.function(), BuildingFunction.parse(t.getString("function")), d.template() + " funkcja szablonu");
        }
        for (final BuildingFunction f : List.of(BuildingFunction.MINE, BuildingFunction.FARM, BuildingFunction.SAWMILL,
            BuildingFunction.QUARRY, BuildingFunction.HARBOR, BuildingFunction.GLASSWORKS, BuildingFunction.BRICKWORKS,
            BuildingFunction.SHEEPFOLD, BuildingFunction.WALL, BuildingFunction.GATE, BuildingFunction.BRIDGE)) {
            assertTrue(PlayerBuildingRules.EXCLUDED.contains(f), f.name());
        }
        assertTrue(def("mieszkanie").function().housing());
        assertFalse(def("koszary").function().housing(), "łóżka koszar nie są mieszkaniami");
    }

    @Test
    void sizeLimits() {
        final PlayerBuildingRules.Limits l = new PlayerBuildingRules.Limits(3, 3, 1000);
        assertNull(PlayerBuildingRules.sizeProblem(5, 4, 5, 100, l, 27));
        assertTrue(PlayerBuildingRules.sizeProblem(2, 4, 5, 40, l, 27).contains("2×5×4"));
        assertTrue(PlayerBuildingRules.sizeProblem(5, 2, 5, 50, l, 27).contains("za małe"));
        assertTrue(PlayerBuildingRules.sizeProblem(20, 10, 20, 4000, l, 27).contains("najwyżej 1000"));
        assertTrue(PlayerBuildingRules.sizeProblem(4, 4, 4, 64, l, 150).contains("co najmniej 150"));
    }

    @Test
    void keyBlocksAreCountedPerNeed() {
        final Def smithy = def("kuznia");
        final List<PlayerBuildingRules.Shortage> none = PlayerBuildingRules.shortages(smithy, Map.of());
        assertEquals(2, none.size());
        assertEquals("kowadło (0/1), piec, piec hutniczy lub stół kowalski (0/1)", PlayerBuildingRules.describe(none));
        assertEquals(1, PlayerBuildingRules.shortages(smithy, Map.of(PlayerBuildingRules.ANVIL, 1)).size());
        assertTrue(PlayerBuildingRules.shortages(smithy, Map.of(PlayerBuildingRules.ANVIL, 1, "SMITHING_TABLE", 1)).isEmpty());
        assertTrue(PlayerBuildingRules.shortages(smithy, Map.of(PlayerBuildingRules.ANVIL, 2, "BLAST_FURNACE", 1)).isEmpty());
        // two whole beds for barracks; one is not enough
        assertEquals("całe łóżka (1/2)", PlayerBuildingRules.describe(
            PlayerBuildingRules.shortages(def("koszary"), Map.of(PlayerBuildingRules.BED, 1))));
        assertTrue(PlayerBuildingRules.shortages(def("koszary"), Map.of(PlayerBuildingRules.BED, 2)).isEmpty());
        // a church: lectern and a candle OR a bell
        assertTrue(PlayerBuildingRules.shortages(def("kosciol"), Map.of("LECTERN", 1, "BELL", 1)).isEmpty());
        assertTrue(PlayerBuildingRules.shortages(def("kosciol"), Map.of("LECTERN", 1, PlayerBuildingRules.CANDLE, 3)).isEmpty());
        assertEquals(1, PlayerBuildingRules.shortages(def("kosciol"), Map.of("BELL", 1)).size());
        assertEquals(1, PlayerBuildingRules.shortages(def("studnia"), Map.of()).size());
        assertTrue(PlayerBuildingRules.shortages(def("studnia"), Map.of(PlayerBuildingRules.WATER, 1)).isEmpty());
        assertEquals(1, PlayerBuildingRules.shortages(def("magazyn"), Map.of(PlayerBuildingRules.CONTAINER, 1)).size());
    }

    @Test
    void limitsCountEverythingOfTheKind() {
        final Def smithy = def("kuznia");
        assertNull(PlayerBuildingRules.limitProblem(smithy, 2, 1, 1, 40, 5));
        assertTrue(PlayerBuildingRules.limitProblem(smithy, 2, 2, 0, 40, 0).contains("najwyżej 2"));
        final Def granary = def("spichlerz");
        assertEquals(3, granary.maxRegistered());
        assertNull(PlayerBuildingRules.limitProblem(granary, 0, 10, 2, 40, 2));
        assertTrue(PlayerBuildingRules.limitProblem(granary, 0, 10, 3, 40, 3).contains("zgłoszonych"));
        assertTrue(PlayerBuildingRules.limitProblem(def("mieszkanie"), 0, 50, 40, 40, 40).contains("40 zgłoszonych"));
        assertNull(PlayerBuildingRules.limitProblem(def("mieszkanie"), 0, 50, 40, 0, 40), "0 = bez limitu");
    }

    @Test
    void bedFootIsBehindTheHead() {
        assertArrayEquals(new int[] {0, 64, 1}, PlayerBuildingRules.bedFoot(0, 64, 0, "NORTH"));
        assertArrayEquals(new int[] {0, 64, -1}, PlayerBuildingRules.bedFoot(0, 64, 0, "SOUTH"));
        assertArrayEquals(new int[] {-1, 64, 0}, PlayerBuildingRules.bedFoot(0, 64, 0, "EAST"));
        assertArrayEquals(new int[] {1, 64, 0}, PlayerBuildingRules.bedFoot(0, 64, 0, "WEST"));
    }

    @Test
    void namesAreShortPlainText() {
        assertNull(PlayerBuildingRules.nameProblem(null));
        assertNull(PlayerBuildingRules.nameProblem("Karczma Pod Dębem"));
        assertNull(PlayerBuildingRules.nameProblem("Kuźnia-2"));
        assertNotNull(PlayerBuildingRules.nameProblem("<red>x</red>"));
        assertNotNull(PlayerBuildingRules.nameProblem("a"));
        assertNotNull(PlayerBuildingRules.nameProblem("x".repeat(25)));
    }

    @Test
    void configOverridesDisablesAddsAndRefuses() {
        final Map<String, Map<String, Object>> section = new LinkedHashMap<>();
        section.put("spichlerz", Map.of("max", 5));
        section.put("ratusz", Map.of("enabled", false));
        section.put("wieza", Map.of("function", "tower", "template", "wieza", "name", "Wieża",
            "blocks", List.of(Map.of("name", "dzwon", "count", 1, "materials", List.of("bell")))));
        section.put("kopalnia", Map.of("function", "mine", "template", "kopalnia",
            "blocks", List.of(Map.of("materials", List.of("TORCH")))));
        section.put("Zły Klucz", Map.of("max", 1));
        section.put("nowy", Map.of("max", 1));
        final List<String> warnings = new ArrayList<>();
        final Map<String, Def> out = PlayerBuildingRules.configure(section, warnings);
        assertEquals(5, out.get("spichlerz").maxRegistered());
        assertEquals(def("spichlerz").needs(), out.get("spichlerz").needs(), "bloki z domyślnych");
        assertFalse(out.containsKey("ratusz"));
        assertEquals(BuildingFunction.TOWER, out.get("wieza").function());
        assertEquals(List.of("BELL"), List.copyOf(out.get("wieza").needs().getFirst().materials()));
        assertFalse(out.containsKey("kopalnia"), "kopalnia ma własny mechanizm");
        assertFalse(out.containsKey("nowy"));
        assertEquals(3, warnings.size(), String.join("\n", warnings));
        assertEquals(PlayerBuildingRules.DEFAULTS.size() - 1 + 1, out.size());
    }

    @Test
    void registeredBuildingsSurviveTheDataFileAndOldFilesStillLoad() {
        final UUID country = UUID.randomUUID();
        final LudnoscData data = new LudnoscData();
        final Building b = new Building(UUID.randomUUID(), country, "wlasny_kuznia", BuildingFunction.SMITHY,
            new BlockBox("world", 0, 64, 0, 6, 70, 6), 0, null, 0, 5L, true);
        data.putBuilding(b);
        final CountryState st = data.state(country);
        st.origins().put(b.id().toString(), "Wladca");
        st.ownBuildings().put(b.id().toString(), new OwnBuilding("kuznia", "Kuźnia Pod Lipą", "Wladca", UUID.randomUUID(), 5L,
            null, 6L));
        final String json = DataCodec.encode(data);
        final LudnoscData back = new LudnoscData();
        DataCodec.apply(DataCodec.decode(json), back);
        final Building b2 = back.building(b.id());
        assertEquals(b, b2);
        assertEquals("Kuźnia Pod Lipą", back.state(country).ownBuildings().get(b.id().toString()).name());
        // a file written before this change has no "ownBuildings": the field stays null
        final String old = json.replaceAll("(?s)\"ownBuildings\": \\{.*?\\}\\s*\\},", "");
        assertFalse(old.contains("ownBuildings"), old);
        final LudnoscData legacy = new LudnoscData();
        DataCodec.apply(DataCodec.decode(old), legacy);
        assertNull(legacy.state(country).ownBuildings);
        assertTrue(legacy.state(country).ownBuildings().isEmpty());
        assertEquals(BuildingFunction.SMITHY, legacy.building(b.id()).function());
    }

    @Test
    void aWorkingRegisteredBuildingCountsAsItsKindForHasBuilding() {
        final UUID country = UUID.randomUUID();
        final LudnoscData data = new LudnoscData();
        final Building barracks = new Building(UUID.randomUUID(), country, "wlasny_koszary", BuildingFunction.BARRACKS,
            new BlockBox("world", 0, 64, 0, 6, 70, 6), 2, null, 0, 5L, true);
        data.putBuilding(barracks);
        assertFalse(data.hasBuilding(country, "koszary"), "bez aliasu tylko dokładny typ");
        data.setAlias(b -> b.function() == BuildingFunction.BARRACKS ? "koszary" : null);
        assertTrue(data.hasBuilding(country, "koszary"));
        assertTrue(data.hasBuilding(country, "wlasny_koszary"));
        data.putBuilding(new Building(barracks.id(), country, "wlasny_koszary", BuildingFunction.OTHER, barracks.box(), 0, null,
            0, 5L, true));
        assertFalse(data.hasBuilding(country, "koszary"), "nieczynne koszary nie liczą się dla Armii");
        assertEquals(0, data.housing(country));
    }
}
