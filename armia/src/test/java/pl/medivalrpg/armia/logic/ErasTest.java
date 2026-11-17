// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Era units (Postep): availability, rearm chain, strength, camouflage, research budget; the config's unit tree. */
class ErasTest {

    private static final Map<String, Eras.UnitEra> UNITS = new LinkedHashMap<>();

    static {
        UNITS.put("piechota", Eras.UnitEra.MEDIEVAL);
        UNITS.put("kusznik", Eras.UnitEra.MEDIEVAL);
        UNITS.put("pikinier", new Eras.UnitEra(2, "", List.of("piechota"), false, 0));
        UNITS.put("muszkieter", new Eras.UnitEra(2, "proch", List.of("kusznik"), false, 0));
        UNITS.put("piechota-liniowa", new Eras.UnitEra(3, "karabin_gwintowany", List.of("pikinier", "muszkieter"), false, 0));
        UNITS.put("zolnierz", new Eras.UnitEra(4, "", List.of("piechota-liniowa"), true, 0));
        UNITS.put("medyk", new Eras.UnitEra(4, "antybiotyki", List.of(), true, 1.0));
    }

    private static Predicate<String> has(final String... ids) {
        final Set<String> s = Set.of(ids);
        return s::contains;
    }

    @Test
    void availabilityNeedsEraAndDiscovery() {
        assertTrue(Eras.available(UNITS.get("piechota"), 1, has()));
        assertFalse(Eras.available(UNITS.get("pikinier"), 1, has()));
        assertTrue(Eras.available(UNITS.get("pikinier"), 2, has()));
        assertFalse(Eras.available(UNITS.get("muszkieter"), 2, has()), "needs proch");
        assertTrue(Eras.available(UNITS.get("muszkieter"), 2, has("proch")));
        assertTrue(Eras.available(UNITS.get("muszkieter"), 4, has("proch")));
    }

    @Test
    void rearmFollowsTheChainAsFarAsTheCountryMayGo() {
        assertEquals(Optional.empty(), Eras.upgrade("piechota", UNITS, 1, has()));
        assertEquals(Optional.of("pikinier"), Eras.upgrade("piechota", UNITS, 2, has()));
        assertEquals(Optional.of("pikinier"), Eras.upgrade("piechota", UNITS, 3, has()), "no karabin_gwintowany yet");
        assertEquals(Optional.of("piechota-liniowa"), Eras.upgrade("piechota", UNITS, 3, has("karabin_gwintowany")));
        assertEquals(Optional.of("zolnierz"), Eras.upgrade("piechota", UNITS, 4, has("karabin_gwintowany")));
        assertEquals(Optional.of("zolnierz"), Eras.upgrade("muszkieter", UNITS, 4, has("karabin_gwintowany")));
        assertEquals(Optional.empty(), Eras.upgrade("zolnierz", UNITS, 4, has("karabin_gwintowany")));
    }

    @Test
    void aiRecruitsOnlyCurrentUnits() {
        assertEquals(List.of("piechota", "kusznik"), Eras.current(UNITS, 1, has()));
        assertEquals(List.of("kusznik", "pikinier"), Eras.current(UNITS, 2, has()));
        assertEquals(List.of("zolnierz", "medyk"), Eras.current(UNITS, 4, has("proch", "antybiotyki")),
            "the modern soldier replaces the whole chain even without karabin_gwintowany");
        assertEquals(Optional.of("zolnierz"), Eras.upgrade("pikinier", UNITS, 4, has()), "through an unavailable middle link");
    }

    @Test
    void strengthAndRearmCost() {
        assertEquals(1.0, Eras.power(0.1, 1, 0), 1e-9);
        assertEquals(1.3, Eras.power(0.1, 4, 0), 1e-9);
        assertEquals(1.45, Eras.power(0.1, 4, 15), 1e-9, "+15 % sila-armii");
        assertEquals(0.1, Eras.power(0.1, 1, -500), 1e-9);
        assertEquals(350, Eras.rearmCost(Eras.Rules.defaults(), 700));
    }

    @Test
    void camouflageIsDarkGreenishButKeepsTheCountryHint() {
        final int red = Eras.camo(0xFF0000);
        final int blue = Eras.camo(0x0000FF);
        assertTrue(((red >> 8) & 255) > 40, "olive green part");
        assertTrue(((red >> 16) & 255) > ((blue >> 16) & 255), "red country stays redder");
        assertTrue((blue & 255) > (red & 255), "blue country stays bluer");
    }

    @Test
    void researchBudgetSumsToHundred() {
        assertNull(BudgetPlan.validate(40, 30, 10, 20));
        assertTrue(BudgetPlan.validate(40, 30, 20, 20).contains("100"));
        assertTrue(BudgetPlan.validate(40, 30, -1, 31) != null);
    }

    @Test
    void configUnitTreeIsConsistent() throws Exception {
        try (var in = ErasTest.class.getResourceAsStream("/config.yml")) {
            final ConfigurationSection units = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getConfigurationSection("units");
            final Map<String, Eras.UnitEra> tree = new LinkedHashMap<>();
            for (final String id : units.getKeys(false)) {
                final ConfigurationSection u = units.getConfigurationSection(id);
                final List<String> rep = u.isList("replaces") ? u.getStringList("replaces")
                    : u.getString("replaces", "").isBlank() ? List.of() : List.of(u.getString("replaces"));
                for (final String r : rep) {
                    assertTrue(units.contains(r), id + " replaces unknown " + r);
                }
                tree.put(id, new Eras.UnitEra(u.getInt("era", 1), u.getString("discovery", ""), rep, u.getBoolean("camo"), u.getDouble("heal-per-second")));
            }
            for (final String id : List.of("pikinier", "muszkieter", "arkebuzer", "armata", "piechota-liniowa", "kawaleria", "artyleria",
                "zolnierz", "snajper", "pojazd-opancerzony", "medyk")) {
                assertTrue(tree.containsKey(id), id);
                assertTrue(tree.get(id).era() >= 2, id);
            }
            final Predicate<String> all = d -> true;
            assertEquals(Optional.of("zolnierz"), Eras.upgrade("piechota", tree, 4, all));
            assertEquals(Optional.of("pojazd-opancerzony"), Eras.upgrade("rycerz", tree, 4, all));
            assertEquals(Optional.of("artyleria"), Eras.upgrade("armata", tree, 4, all));
            assertTrue(tree.get("medyk").healPerSecond() > 0);
            assertTrue(tree.get("zolnierz").camo());
            assertTrue(units.getDouble("armata.siege-strength") >= 4);
            assertTrue(units.getDouble("snajper.range") > units.getDouble("zolnierz.range"));
        }
    }
}
