// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.BuiltinTemplates;
import pl.medivalrpg.ludnosc.template.FoundationMode;

/** The planner picks look variants deterministically, cycles through all of them and shares limits per type. */
class VariantChoiceTest {

    static List<BuildingTemplate.Meta> catalog;

    @BeforeAll
    static void load() throws IOException {
        catalog = new ArrayList<>();
        for (final BuildingTemplate t : BuiltinTemplates.loadAll()) {
            catalog.add(t.meta());
        }
    }

    static BuildingChooser.Input input(final int level, final int population, final int housing, final long treasury,
                                       final Map<String, Integer> counts, final long seed, final int era) {
        return new BuildingChooser.Input(level, population, housing, 100, 300, 10, 0, 0, false, treasury, treasury, counts,
            catalog, 0.2, 0.9, era, d -> true).withVariantSeed(seed);
    }

    static Set<String> family(final String base) {
        final Set<String> out = new HashSet<>();
        for (final BuildingTemplate.Meta m : catalog) {
            if (m.baseType().equals(base)) {
                out.add(m.id());
            }
        }
        return out;
    }

    @Test
    void familiesContainTheBaseAndItsVariants() {
        assertEquals(Set.of("dom", "dom_szachulec", "dom_kamienny"), family("dom"));
        assertEquals(Set.of("chata", "chata_szachulec", "chata_zrebowa"), family("chata"));
        assertEquals(Set.of("kamienica", "kamienica_szczytowa", "kamienica_podcieniowa"), family("kamienica"));
        assertEquals(Set.of("kamienica_renesansowa", "kamienica_renesansowa_loggia"), family("kamienica_renesansowa"));
        assertEquals(Set.of("studnia"), family("studnia"), "types without variants stay alone");
    }

    @Test
    void choiceIsDeterministic() {
        for (long seed = -3; seed < 20; seed++) {
            final Map<String, Integer> counts = Map.of("studnia", 1, "dom", 2);
            final String a = BuildingChooser.choose(input(1, 10, 10, 50_000, counts, seed, 1)).type();
            final String b = BuildingChooser.choose(input(1, 10, 10, 50_000, new HashMap<>(counts), seed, 1)).type();
            assertEquals(a, b);
            assertEquals(a, BuildingChooser.variantFor(input(1, 10, 10, 50_000, counts, seed, 1), "dom"));
            assertEquals(a, BuildingChooser.variantFor(input(1, 10, 10, 50_000, counts, seed, 1), "dom_kamienny"),
                "any id of the family asks for the same type");
            assertTrue(family("dom").contains(a), a);
        }
    }

    @Test
    void consecutiveBuildingsOfATypeCycleThroughEveryVariant() {
        for (final String base : List.of("dom", "chata", "kamienica")) {
            final int level = base.equals("kamienica") ? 2 : 1;
            for (long seed = 0; seed < 8; seed++) {
                final Set<String> seen = new HashSet<>();
                final Map<String, Integer> counts = new HashMap<>(Map.of("studnia", 1));
                final int n = family(base).size();
                for (int i = 0; i < n; i++) {
                    final String id = BuildingChooser.variantFor(input(level, 30, 30, 100_000, counts, seed, 1), base);
                    seen.add(id);
                    counts.merge(id, 1, Integer::sum);
                }
                assertEquals(family(base), seen, base + " seed " + seed);
            }
        }
    }

    @Test
    void seedsSpreadTheFirstVariant() {
        final Set<String> first = new HashSet<>();
        for (long seed = 0; seed < 64; seed++) {
            first.add(BuildingChooser.variantFor(input(1, 10, 10, 50_000, Map.of("studnia", 1), seed * 0x9E3779B97F4A7C15L, 1), "dom"));
        }
        assertEquals(family("dom"), first, "different countries start their towns with different houses");
    }

    @Test
    void limitsAreSharedByTheWholeType() {
        // kuźnia: 2 per country — one base + one variant use the limit up
        final Map<String, Integer> counts = Map.of("studnia", 1, "kuznia", 1, "kuznia_kamienna", 1);
        final BuildingChooser.Input in = input(1, 20, 30, 100_000, counts, 7, 1);
        assertEquals(2, in.count("kuznia"));
        assertEquals(2, in.count("kuznia_kamienna"));
        assertEquals(1, in.rawCount("kuznia"));
        assertEquals("kuznia", in.baseOf("kuznia_kamienna"));
        for (final BuildingTemplate.Meta m : catalog) {
            if (m.baseType().equals("kuznia")) {
                assertFalse(BuildingChooser.allowed(in, m), m.id());
            }
        }
        // a church of either look counts as the one church of the country
        final BuildingChooser.Input church = input(2, 40, 60, 500_000, Map.of("studnia", 1, "kosciol_drewniany", 1), 0, 1);
        for (final BuildingTemplate.Meta m : catalog) {
            if (m.baseType().equals("kosciol")) {
                assertFalse(BuildingChooser.allowed(church, m), m.id());
            }
        }
        // categories are not counted twice
        assertEquals(2, in.countCategory("economy"));
    }

    @Test
    void growthBuildsOneOfEachTypeNotOneOfEachLook() {
        final Map<String, Integer> counts = new HashMap<>(Map.of("studnia", 1));
        final Set<String> types = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            final BuildingChooser.Choice c = BuildingChooser.choose(input(1, 9, 40, 200_000, counts, 3, 1));
            if (c.reason() != BuildingChooser.Reason.GROWTH) {
                break;
            }
            final String base = new BuildingChooser.Input(1, 10, 40, 0, 0, 0, 0, 0, false, 0, 0, Map.of(), catalog, 0.2, 0.9)
                .baseOf(c.type());
            assertTrue(types.add(base), "type " + base + " chosen twice for growth (" + c.type() + ")");
            counts.merge(c.type(), 1, Integer::sum);
        }
        assertFalse(types.isEmpty());
    }

    @Test
    void renaissanceVariantNeedsTheRenaissance() {
        final Map<String, Integer> counts = Map.of("studnia", 1);
        for (long seed = 0; seed < 16; seed++) {
            final String id = BuildingChooser.variantFor(input(1, 30, 30, 100_000, counts, seed, 1), "kamienica_renesansowa");
            assertEquals("kamienica_renesansowa", id, "nothing allowed in the Middle Ages: the requested id stays");
        }
        final Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 16; seed++) {
            seen.add(BuildingChooser.variantFor(input(1, 30, 30, 100_000, counts, seed, 2), "kamienica_renesansowa"));
        }
        assertEquals(family("kamienica_renesansowa"), seen);
    }

    @Test
    void copiesOfPlayerBuildingsAreNeverPlanned() {
        final List<BuildingTemplate.Meta> withCopy = new ArrayList<>(catalog);
        withCopy.add(new BuildingTemplate.Meta("kopia_dom_gracza", "Kopia", "", BuildingFunction.HOUSING, 40, 10,
            FoundationMode.FILL, "cobblestone", 0, "test", 0, "housing", "inner", 0, 0, 0, 1, "", "dom"));
        for (long seed = 0; seed < 32; seed++) {
            final BuildingChooser.Input in = new BuildingChooser.Input(0, 4, 0, 100, 300, 10, 0, 0, false, 50_000, 50_000,
                Map.of("studnia", 1), withCopy, 0.2, 0.9, 1, d -> true, seed);
            final String type = BuildingChooser.choose(in).type();
            assertFalse(type.startsWith("kopia_"), type);
            for (final BuildingTemplate.Meta m : BuildingChooser.housing(in)) {
                assertFalse(m.id().startsWith("kopia_"), m.id());
            }
        }
    }

    @Test
    void housingListHasOneEntryPerType() {
        final List<BuildingTemplate.Meta> homes = BuildingChooser.housing(input(2, 30, 30, 100_000, Map.of("studnia", 1), 5, 2));
        final Set<String> types = new HashSet<>();
        for (final BuildingTemplate.Meta m : homes) {
            assertTrue(types.add(m.baseType()), "type listed twice: " + m.baseType());
        }
        assertTrue(types.containsAll(Set.of("chata", "dom", "kamienica", "kamienica_renesansowa")), types.toString());
    }

    @Test
    void unknownIdsAreTheirOwnType() {
        final BuildingChooser.Input in = input(0, 0, 0, 0, Map.of("dom_wiejski", 3), 0, 1);
        assertEquals("dom_wiejski", in.baseOf("dom_wiejski"));
        assertEquals(3, in.count("dom_wiejski"));
        assertEquals("nieznany", BuildingChooser.variantFor(in, "nieznany"));
        assertNull(BuildingChooser.choose(input(0, 0, 0, 0, Map.of("studnia", 1), 0, 1).without(new HashSet<>(
            catalog.stream().map(BuildingTemplate.Meta::id).toList()))).type());
    }
}
