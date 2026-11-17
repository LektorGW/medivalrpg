// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigParsingTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = ConfigParsingTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the test classpath");
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration y = new YamlConfiguration();
                y.load(r);
                return y;
            }
        }
    }

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void bundledConfigLoadsAllDefaultInteractionsWithoutWarnings() throws Exception {
        YamlConfiguration cfg = bundled();
        List<String> warnings = new ArrayList<>();
        Map<String, InteractionDef> defs = InteractionLoader.load(cfg.getConfigurationSection("interactions"),
                Set.of("interakcje", "int", "pusc", "puść", "usiadz", "sit", "siad", "poloz", "lay", "lez"), warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(List.of("kiss", "hug", "hold", "pat", "highfive", "slap", "wave", "bow", "handshake", "toast",
                "courtbow", "knight", "fealty", "coronation"), List.copyOf(defs.keySet()));

        InteractionDef kiss = defs.get("kiss");
        assertEquals("pocaluj", kiss.command());
        assertEquals(List.of("calus", "kiss"), kiss.aliases());
        assertTrue(kiss.consent());
        assertFalse(kiss.targetOptional());
        assertEquals(3.0, kiss.range());
        assertEquals(5000, kiss.cooldownMs());
        assertEquals("POPPY", kiss.icon());
        assertEquals("HEART", kiss.effects().particles().getFirst().type());
        assertEquals(InteractionDef.Anchor.BETWEEN, kiss.effects().particles().getFirst().at());
        assertEquals("entity.chicken.egg", kiss.effects().sounds().getFirst().sound());
        assertEquals("interakcje.kiss", kiss.permission());

        InteractionDef hug = defs.get("hug");
        assertTrue(hug.effects().faceEachOther());
        assertEquals(InteractionDef.Swing.BOTH, hug.effects().swing());

        InteractionDef hold = defs.get("hold");
        assertNotNull(hold.hold());
        assertEquals(30, hold.hold().durationSec());
        assertEquals(4.0, hold.hold().maxDistance());
        assertEquals(10, hold.hold().intervalTicks());

        InteractionDef slap = defs.get("slap");
        assertEquals("liść", slap.command());
        assertEquals(List.of("lisc", "slap"), slap.aliases());
        assertEquals(10_000, slap.cooldownMs());
        assertTrue(slap.effects().knockback() > 0);
        assertTrue(slap.isExempt("creative"));
        assertTrue(slap.isExempt("SPECTATOR"));
        assertFalse(slap.isExempt("SURVIVAL"));

        InteractionDef wave = defs.get("wave");
        assertTrue(wave.targetOptional());
        assertEquals(10.0, wave.range());
        assertTrue(defs.get("bow").effects().nod());
        assertNull(defs.get("pat").hold());
    }

    @Test
    void bundledPosesAndSettings() throws Exception {
        YamlConfiguration cfg = bundled();
        List<String> warnings = new ArrayList<>();
        PoseDef sit = PoseDef.parse(PoseDef.SIT, cfg.getConfigurationSection("sit"), warnings::add);
        PoseDef lay = PoseDef.parse(PoseDef.LAY, cfg.getConfigurationSection("lay"), warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals("usiadz", sit.command());
        assertEquals(List.of("sit", "siad"), sit.aliases());
        assertTrue(sit.rightClickStairs());
        assertEquals("interakcje.sit", sit.permission());
        assertEquals("poloz", lay.command());
        assertEquals(List.of("lay", "lez"), lay.aliases());
        assertEquals("SLEEPING", lay.pose());
        assertEquals(3000, lay.cooldownMs());

        Settings s = Settings.from(cfg);
        assertEquals(16.0, s.broadcastRadius());
        assertEquals(30_000, s.requestTimeoutMs());
        assertEquals(1500, s.globalCooldownMs());
        assertEquals("pusc", s.releaseCommand());
        assertEquals(List.of("puść", "release"), s.releaseAliases());
        assertTrue(s.menuShiftRightClick());
        assertTrue(s.menuRequireEmptyHand());
    }

    @Test
    void missingPoseSectionFallsBackToDefaults() {
        PoseDef lay = PoseDef.parse(PoseDef.LAY, null, w -> { });
        assertEquals("poloz", lay.command());
        assertEquals("SLEEPING", lay.pose());
        assertTrue(lay.enabled());
    }

    @Test
    void invalidLayPoseIsCorrected() throws Exception {
        List<String> warnings = new ArrayList<>();
        PoseDef lay = PoseDef.parse(PoseDef.LAY, yaml("pose: DANCING").getRoot(), warnings::add);
        assertEquals("SLEEPING", lay.pose());
        assertEquals(1, warnings.size());
    }

    @Test
    void minimalInteractionGetsDefaults() throws Exception {
        YamlConfiguration y = yaml("""
                interactions:
                  poke:
                    command: /Szturchnij
                """);
        List<String> warnings = new ArrayList<>();
        InteractionDef d = InteractionLoader.load(y.getConfigurationSection("interactions"), Set.of(), warnings::add)
                .get("poke");
        assertNotNull(d);
        assertEquals("szturchnij", d.command(), "lower-cased, slash stripped");
        assertEquals(3.0, d.range());
        assertEquals(5000, d.cooldownMs());
        assertFalse(d.consent());
        assertTrue(d.enabled());
        assertEquals(InteractionDef.Effects.NONE, d.effects());
        assertEquals(-1, d.slot());
        assertEquals(List.of(), warnings);
    }

    @Test
    void duplicateAndReservedCommandsAreSkipped() throws Exception {
        YamlConfiguration y = yaml("""
                interactions:
                  a:
                    command: przytul
                  b:
                    command: inna
                    aliases: [przytul]
                  c:
                    command: interakcje
                  "Bad Id":
                    command: zla
                """);
        List<String> warnings = new ArrayList<>();
        Map<String, InteractionDef> defs = InteractionLoader.load(y.getConfigurationSection("interactions"),
                Set.of("interakcje"), warnings::add);
        assertEquals(Set.of("a"), defs.keySet());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void badValuesAreCorrectedWithWarnings() throws Exception {
        YamlConfiguration y = yaml("""
                interactions:
                  x:
                    command: x
                    range: -2
                    target: maybe
                    slot: 99
                    effects:
                      swing: legs
                      particles:
                        - { at: target }
                        - { type: HEART, at: nowhere, count: "4" }
                      sounds:
                        - { sound: entity.chicken.egg, pitch: 9 }
                    hold:
                      duration: 0
                      interval-ticks: 1
                """);
        List<String> warnings = new ArrayList<>();
        InteractionDef d = InteractionLoader.load(y.getConfigurationSection("interactions"), Set.of(), warnings::add)
                .get("x");
        assertNotNull(d);
        assertEquals(3.0, d.range());
        assertFalse(d.targetOptional());
        assertEquals(-1, d.slot());
        assertEquals(InteractionDef.Swing.NONE, d.effects().swing());
        assertEquals(1, d.effects().particles().size(), "particle without type is skipped");
        assertEquals(InteractionDef.Anchor.BETWEEN, d.effects().particles().getFirst().at());
        assertEquals(4, d.effects().particles().getFirst().count());
        assertEquals(2.0f, d.effects().sounds().getFirst().pitch(), "pitch clamped to 0.5–2.0");
        assertEquals(30, d.hold().durationSec());
        assertEquals(2, d.hold().intervalTicks());
        assertEquals(7, warnings.size(), warnings.toString());
    }

    @Test
    void labelNormalisation() {
        assertEquals("liść", InteractionLoader.normalizeLabel(" /LIŚĆ "));
        assertNull(InteractionLoader.normalizeLabel("two words"));
        assertNull(InteractionLoader.normalizeLabel("ns:cmd"));
        assertNull(InteractionLoader.normalizeLabel("/"));
        assertNull(InteractionLoader.normalizeLabel(null));
    }
}
