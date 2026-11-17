// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.planner.Underground;

/** Look variants ({@code variant-of}) of the medieval/renaissance buildings and the untouched base templates. */
class TemplateVariantsTest {

    private static final MaterialPalette PALETTE = MaterialPalette.defaults();
    private static Map<String, BuildingTemplate> all;

    @BeforeAll
    static void load() throws IOException {
        all = new HashMap<>();
        for (final BuildingTemplate t : BuiltinTemplates.loadAll()) {
            all.put(t.id(), t);
        }
    }

    private static List<BuildingTemplate> variants() {
        return BuiltinTemplates.VARIANT_IDS.stream().map(all::get).toList();
    }

    @Test
    void everyVariantParsesAndPointsToAnExistingBase() {
        assertEquals(BuiltinTemplates.BASE_IDS.size() + BuiltinTemplates.VARIANT_IDS.size() + BuiltinTemplates.WORKSHOP_IDS.size(),
            BuiltinTemplates.IDS.size());
        assertEquals(BuiltinTemplates.IDS.size(), new HashSet<>(BuiltinTemplates.IDS).size(), "ids are unique");
        assertTrue(BuiltinTemplates.VARIANT_IDS.size() >= 10);
        for (final BuildingTemplate v : variants()) {
            assertNotNull(v, "variant loads");
            assertTrue(v.meta().isVariant(), v.id());
            assertTrue(BuiltinTemplates.BASE_IDS.contains(v.meta().baseType()), v.id() + " → " + v.meta().baseType());
            assertEquals(List.of(), TemplateParser.check(v), v.id());
            assertTrue(v.solidCount() > 100, v.id());
        }
        for (final String id : BuiltinTemplates.BASE_IDS) {
            assertFalse(all.get(id).meta().isVariant(), id);
            assertEquals(id, all.get(id).meta().baseType());
        }
    }

    @Test
    void variantsKeepTheFunctionBedsAndRequirementsOfTheirBase() {
        final Set<String> bases = new HashSet<>();
        for (final BuildingTemplate v : variants()) {
            final BuildingTemplate.Meta m = v.meta();
            final BuildingTemplate.Meta b = all.get(m.baseType()).meta();
            bases.add(b.id());
            assertEquals(b.function(), m.function(), v.id());
            assertEquals(b.declaredBeds(), m.declaredBeds(), v.id());
            assertEquals(b.declaredBeds(), v.bedCount(), v.id());
            assertEquals(b.tier(), m.tier(), v.id());
            assertEquals(b.category(), m.category(), v.id());
            assertEquals(b.zone(), m.zone(), v.id());
            assertEquals(b.maxPerCountry(), m.maxPerCountry(), v.id());
            assertEquals(b.minPopulation(), m.minPopulation(), v.id());
            assertEquals(b.minTreasury(), m.minTreasury(), v.id());
            assertEquals(b.era(), m.era(), v.id());
            assertEquals(b.discovery(), m.discovery(), v.id());
            assertEquals(b.cost(), m.cost(), v.id());
            assertEquals(b.groundLayer(), m.groundLayer(), v.id());
            assertTrue(m.era() <= 2, "medieval / renaissance only: " + v.id());
        }
        // the important types of epochs 1-2 got variants
        assertTrue(bases.containsAll(Set.of("chata", "dom", "kamienica", "kamienica_renesansowa", "karczma", "kuznia",
            "piekarnia", "tartak", "spichlerz", "stajnia", "kosciol", "wieza")), bases.toString());
    }

    @Test
    void variantsFitThePlotsOfTheirBase() {
        for (final BuildingTemplate v : variants()) {
            final BuildingTemplate b = all.get(v.meta().baseType());
            assertTrue(v.width() <= b.width() + 2 && v.depth() <= b.depth() + 2,
                v.id() + " " + v.width() + "x" + v.depth() + " vs " + b.id() + " " + b.width() + "x" + b.depth());
            assertTrue(v.height() <= b.height() + 4, v.id() + " height " + v.height());
            assertTrue(Math.max(v.width(), v.depth()) <= 20 && v.height() <= 24, v.id());
        }
    }

    @Test
    void variantsUseTheMaterialSlotsAndResolveAtEveryLevel() {
        for (final BuildingTemplate v : variants()) {
            assertTrue(v.usesMaterials(), v.id());
            assertTrue(v.slotCount() > v.solidCount() / 3, v.id() + " walls/roof follow the palette");
            for (int level = 0; level < PALETTE.levels(); level++) {
                final BuildingTemplate t = v.atLevel(PALETTE, level);
                for (final String s : t.distinctStates()) {
                    assertFalse(MaterialPalette.isSlot(s), v.id() + " level " + level + ": " + s);
                }
                assertEquals(v.bedCount(), t.bedCount(), v.id());
                assertEquals(List.of(), t.validate(), v.id() + " level " + level);
            }
        }
    }

    @Test
    void rotationsOfVariantsAreConsistent() {
        for (final BuildingTemplate raw : variants()) {
            final BuildingTemplate v = raw.atLevel(PALETTE, 1);
            final BuildingTemplate base = all.get(raw.meta().baseType()).atLevel(PALETTE, 1);
            assertTrue(base.exteriorDoors().isEmpty() || !v.exteriorDoors().isEmpty(), v.id() + " has an entrance like its base");
            for (final int[] d : v.exteriorDoors()) {
                assertEquals(v.meta().groundLayer() + 1, d[1], v.id() + ": exterior doors only on the ground floor");
            }
            for (int q = 0; q < 4; q++) {
                final BuildingTemplate r = v.rotated(q);
                assertEquals(List.of(), r.validate(), v.id() + " rotation " + q);
                assertEquals(v.bedCount(), r.bedCount(), v.id());
                assertEquals(v.solidCount(), r.solidCount(), v.id());
                assertEquals(v.exteriorDoors().size(), r.exteriorDoors().size(), v.id() + " rotation " + q);
                assertEquals(q % 2 == 0 ? v.width() : v.depth(), r.width(), v.id());
                assertEquals(BlockStates.rotateDirection("south", q), r.front(), v.id());
            }
            final BuildingTemplate full = v.rotated(1).rotated(1).rotated(1).rotated(1);
            for (int i = 0; i < v.volume(); i++) {
                final String a = v.state(i);
                final String b = full.state(i);
                if (a == null) {
                    assertEquals(null, b);
                } else {
                    assertEquals(BlockStates.parse(a).id(), BlockStates.parse(b).id(), v.id() + " @" + i);
                    assertEquals(BlockStates.parse(a).properties(), BlockStates.parse(b).properties(), v.id() + " @" + i);
                }
            }
        }
    }

    @Test
    void housesHaveRoomForSettlersAndACellarHatch() {
        for (final BuildingTemplate raw : variants()) {
            if (raw.meta().declaredBeds() == 0) {
                continue;
            }
            final BuildingTemplate t = raw.atLevel(PALETTE, 1);
            assertNotNull(t.spawnCell(), t.id() + " spawn cell");
            if (Set.of("chata", "dom", "kamienica", "karczma").contains(raw.meta().baseType())) {
                final int g = t.meta().groundLayer();
                assertNotNull(Underground.hatch((x, y, z) -> t.inside(x, y, z) ? t.state(x, y, z) : null, t.width(), t.depth(), g),
                    t.id() + " has room for the cellar hatch of its base type");
            }
        }
    }

    @Test
    void workplacesHaveContainersForTheirProduce() {
        for (final BuildingTemplate v : variants()) {
            assertFalse(v.containers().isEmpty(), v.id());
        }
        assertTrue(all.get("spichlerz_kamienny").containers().size() >= 4, "granary chests");
    }

    @Test
    void baseTemplatesAreByteIdenticalToTheDeployedOnes() throws IOException, NoSuchAlgorithmException {
        final Map<String, String> expected = new HashMap<>();
        try (InputStream in = TemplateVariantsTest.class.getClassLoader().getResourceAsStream("budynki-base-sha256.txt")) {
            assertNotNull(in, "snapshot resource");
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                final String[] p = line.trim().split("\\s+");
                expected.put(p[0], p[1]);
            }
        }
        assertEquals(new HashSet<>(BuiltinTemplates.BASE_IDS), expected.keySet(), "every base template is in the snapshot");
        for (final String id : BuiltinTemplates.BASE_IDS) {
            try (InputStream in = BuiltinTemplates.class.getClassLoader().getResourceAsStream(BuiltinTemplates.resourcePath(id))) {
                assertNotNull(in, id);
                // the description line is text for the players (2026-11-11: what the building does); everything else —
                // grid, palette, meta — must stay byte-identical to the deployed template
                final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                final StringBuilder kept = new StringBuilder();
                for (final String line : text.split("(?<=\\n)")) {
                    if (!line.startsWith("description:")) {
                        kept.append(line);
                    }
                }
                final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(kept.toString().getBytes(StandardCharsets.UTF_8)));
                assertEquals(expected.get(id), sha, "budynki/" + id + ".yml changed — standing buildings would look damaged");
            }
        }
    }

    @Test
    void baseContentHashDoesNotDependOnTheNewMetaField() throws IOException {
        // the content hash covers the grid only: a variant-of line in the YAML never changes it
        final BuildingTemplate dom = all.get("dom");
        final String text;
        try (InputStream in = BuiltinTemplates.class.getClassLoader().getResourceAsStream("budynki/dom.yml")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader(
            text.replace("id: dom\n", "id: dom_test\nvariant-of: dom\n")));
        final BuildingTemplate copy = TemplateParser.parse(y, "dom_test", "test");
        assertEquals("dom", copy.meta().baseType());
        assertTrue(copy.meta().isVariant());
        assertEquals(dom.contentHash(), copy.contentHash());
        for (int q = 0; q < 4; q++) {
            assertEquals(dom.rotated(q).contentHash(), copy.rotated(q).contentHash());
        }
        assertEquals(dom.atLevel(PALETTE, 1).contentHash(), copy.atLevel(PALETTE, 1).contentHash());
    }

    @Test
    void parserReadsVariantOf() {
        final String grid = "palette:\n  'C': cobblestone\nlayers:\n  - |\n    CC\n    CC\n";
        final BuildingTemplate plain = TemplateParser.parse(YamlConfiguration.loadConfiguration(new StringReader("id: a\n" + grid)), "a", "t");
        assertFalse(plain.meta().isVariant());
        assertEquals("a", plain.meta().baseType());
        final BuildingTemplate self = TemplateParser.parse(
            YamlConfiguration.loadConfiguration(new StringReader("id: a\nvariant-of: A\n" + grid)), "a", "t");
        assertFalse(self.meta().isVariant(), "variant of itself = base");
        final BuildingTemplate v = TemplateParser.parse(
            YamlConfiguration.loadConfiguration(new StringReader("id: b\nvariant-of: ' Dom '\n" + grid)), "b", "t");
        assertEquals("dom", v.meta().baseType());
        assertEquals("dom", v.meta().withBalance(1, 0, 0, 0, 0).baseType(), "balance override keeps variant-of");
        assertThrows(IllegalArgumentException.class, () -> TemplateParser.parse(
            YamlConfiguration.loadConfiguration(new StringReader("id: b\nvariant-of: 'zły typ'\n" + grid)), "b", "t"));
    }
}
