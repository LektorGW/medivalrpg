// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BiomeMappingTest {

    private static final List<BiomeMapping.Entry> REGISTRY = List.of(
        new BiomeMapping.Entry(0, "minecraft:plains", true, 0.8f),
        new BiomeMapping.Entry(1, "minecraft:desert", false, 2.0f),
        new BiomeMapping.Entry(2, "minecraft:snowy_plains", true, 0.0f),
        new BiomeMapping.Entry(3, "minecraft:nether_wastes", false, 2.0f),
        new BiomeMapping.Entry(4, "pory:jesien/plains", true, 0.8f),
        new BiomeMapping.Entry(5, "pory:zima/plains", true, -0.25f),
        new BiomeMapping.Entry(6, "pory:zima/desert", false, 2.0f),
        new BiomeMapping.Entry(7, "pory:wiosna/plains", true, 0.8f),
        new BiomeMapping.Entry(8, "terralith:alpine", true, 0.5f),
        new BiomeMapping.Entry(9, "pory:zima/terralith/alpine", true, -0.3f));

    @Test
    void variantKeys() {
        assertEquals("pory:jesien/plains", BiomeMapping.variantKey(VisualSeason.JESIEN, "minecraft:plains"));
        assertEquals("pory:zima/terralith/alpine", BiomeMapping.variantKey(VisualSeason.ZIMA, "terralith:alpine"));
        assertEquals("minecraft:plains", BiomeMapping.baseKeyOf("pory:zima/plains"));
        assertEquals("terralith:alpine", BiomeMapping.baseKeyOf("pory:zima/terralith/alpine"));
        assertNull(BiomeMapping.baseKeyOf("minecraft:plains"));
        assertNull(BiomeMapping.baseKeyOf("pory:nieznana/plains"));
        assertEquals(VisualSeason.ZIMA, BiomeMapping.seasonOf("pory:zima/plains"));
        assertNull(BiomeMapping.seasonOf("minecraft:plains"));
    }

    @Test
    void tablesPerSeason() {
        final BiomeMapping m = BiomeMapping.build(REGISTRY, 0.15f);
        assertEquals(5, m.baseCount());
        assertNull(m.table(VisualSeason.LATO), "summer is vanilla");
        final int[] autumn = m.table(VisualSeason.JESIEN);
        assertNotNull(autumn);
        assertEquals(4, autumn[0]);
        assertEquals(1, autumn[1], "no autumn desert variant: unchanged");
        assertEquals(2, autumn[2]);
        assertEquals(3, autumn[3]);
        final int[] winter = m.table(VisualSeason.ZIMA);
        assertEquals(5, winter[0]);
        assertEquals(6, winter[1]);
        assertEquals(9, winter[8], "other namespaces too");
        assertEquals(1, m.variantCount(VisualSeason.JESIEN));
        assertEquals(3, m.variantCount(VisualSeason.ZIMA));
    }

    @Test
    void variantsMapAcrossSeasonsIdempotently() {
        final BiomeMapping m = BiomeMapping.build(REGISTRY, 0.15f);
        final int[] winter = m.table(VisualSeason.ZIMA);
        assertEquals(5, winter[4], "autumn variant → winter variant");
        assertEquals(5, winter[5]);
        assertEquals(5, winter[7]);
        final int[] summer = m.table(VisualSeason.LATO);
        assertNull(summer);
        final int[] spring = m.table(VisualSeason.WIOSNA);
        assertEquals(7, spring[5]);
        assertEquals(1, spring[6], "winter desert → base desert in spring");
        for (final VisualSeason s : VisualSeason.values()) {
            final int[] t = m.table(s);
            if (t != null) {
                for (int i = 0; i < t.length; i++) {
                    assertEquals(t[i], t[t[i]], "idempotent " + s + " " + i);
                }
            }
        }
    }

    @Test
    void snowEligibilityUsesWinterLook() {
        final BiomeMapping m = BiomeMapping.build(REGISTRY, 0.15f);
        assertTrue(m.snowEligible(0), "plains: winter variant is cold with precipitation");
        assertFalse(m.snowEligible(1), "desert: no precipitation");
        assertTrue(m.snowEligible(2), "snowy plains");
        assertFalse(m.snowEligible(3), "nether");
        assertTrue(m.snowEligible(8));
        assertFalse(m.snowEligible(99));
        assertFalse(m.snowEligible(-1));
    }

    @Test
    void registryWithoutDatapackIsIdentity() {
        final BiomeMapping m = BiomeMapping.build(REGISTRY.subList(0, 4), 0.15f);
        for (final VisualSeason s : VisualSeason.values()) {
            assertNull(m.table(s));
            assertEquals(0, m.variantCount(s));
        }
        assertFalse(m.snowEligible(0), "without winter variant plains stays warm");
        assertTrue(m.snowEligible(2));
    }
}
