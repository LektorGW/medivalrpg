// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.interakcje.anim.AnimationCatalog;
import pl.medivalrpg.interakcje.anim.AnimationSettings;
import pl.medivalrpg.interakcje.pack.ResourcePackService;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled config.yml refers only to animations that exist; new sections parse without warnings. */
class AnimationConfigTest {

    private static YamlConfiguration config() throws Exception {
        try (InputStream in = AnimationConfigTest.class.getResourceAsStream("/config.yml");
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration y = new YamlConfiguration();
            y.load(r);
            return y;
        }
    }

    private static AnimationCatalog catalog() throws Exception {
        try (InputStream in = AnimationConfigTest.class.getResourceAsStream("/bettermodel/animations.json");
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return AnimationCatalog.parse(r, w -> { });
        }
    }

    @Test
    void interactionsUseExistingAnimations() throws Exception {
        YamlConfiguration cfg = config();
        AnimationCatalog catalog = catalog();
        Map<String, InteractionDef> defs = InteractionLoader.load(cfg.getConfigurationSection("interactions"),
                Set.of("interakcje", "int", "pusc"), w -> { });
        for (InteractionDef d : defs.values()) {
            assertNotNull(d.animation(), d.id() + " has an animation");
            assertNotNull(catalog.animation(d.animation()), d.id() + " -> " + d.animation());
        }
        assertEquals("hug", defs.get("hug").animation());
        assertTrue(catalog.animation(defs.get("hold").animation()).pair());
        assertNull(InteractionLoader.animationId(" none "));
        assertNull(InteractionLoader.animationId(""));
        assertEquals("hug", InteractionLoader.animationId(" HUG "));
    }

    @Test
    void dancesAndEmotesParse() throws Exception {
        YamlConfiguration cfg = config();
        AnimationCatalog catalog = catalog();
        List<String> warnings = new ArrayList<>();
        MotionGroup dances = MotionGroup.parse("dances", cfg.getConfigurationSection("dances"), "taniec",
                List.of(), "interakcje.dance", "numer", warnings::add);
        MotionGroup emotes = MotionGroup.parse("emotes", cfg.getConfigurationSection("emotes"), "emotka",
                List.of(), "interakcje.emote", "nazwa", warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals("taniec", dances.command());
        assertEquals(List.of("1", "2", "3"), List.copyOf(dances.entries().keySet()));
        assertEquals("emotka", emotes.command());
        assertEquals(List.of("klaskanie", "salut", "nie_wiem", "facepalm", "modlitwa", "kleczenie", "poklon",
                "salut_miecz", "kufel"), List.copyOf(emotes.entries().keySet()));
        assertTrue(emotes.get("poklon").target(), "/emotka poklon <gracz>");
        assertTrue(!emotes.get("kufel").target());
        assertEquals("POTION", emotes.get("kufel").icon());
        assertEquals("entity.generic.drink", emotes.get("kufel").effects().sounds().getFirst().sound());
        assertTrue(!emotes.clickTrigger().enabled(), "drink click is off by default");
        assertEquals("kufel", emotes.clickTrigger().motion());
        assertTrue(emotes.clickTrigger().items().contains("POTION"));
        assertTrue(!dances.clickTrigger().enabled());
        for (MotionGroup.Motion m : emotes.entries().values()) {
            assertTrue(!m.description().isEmpty(), m.id() + " has a description for the tab-complete tooltip");
            assertTrue(!m.icon().equals("PAPER"), m.id() + " has a menu icon");
        }
        for (MotionGroup g : List.of(dances, emotes)) {
            for (MotionGroup.Motion m : g.entries().values()) {
                assertNotNull(catalog.animation(m.animation()), g.key() + "." + m.id() + " -> " + m.animation());
                assertEquals(3000, m.cooldownMs());
            }
        }
        assertEquals("dances:2", dances.cooldownKey(dances.get("2")));
        assertTrue(catalog.animation(dances.get("1").animation()) != null);
    }

    @Test
    void posesAnimationsAndPackSettings() throws Exception {
        YamlConfiguration cfg = config();
        List<String> warnings = new ArrayList<>();
        PoseDef sit = PoseDef.parse(PoseDef.SIT, cfg.getConfigurationSection("sit"), warnings::add);
        PoseDef lay = PoseDef.parse(PoseDef.LAY, cfg.getConfigurationSection("lay"), warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals("AUTO", sit.variant());
        assertEquals("NONE", lay.animatedPose());

        AnimationSettings a = AnimationSettings.from(cfg.getConfigurationSection("animations"));
        assertTrue(a.enabled());
        assertTrue(a.alignPairs());
        assertEquals(1.25, a.alignMaxShift());
        assertTrue(a.onlyForPackUsers());

        ResourcePackService.Settings p = ResourcePackService.Settings.from(cfg.getConfigurationSection("resource-pack"));
        assertTrue(p.enabled());
        assertTrue(p.required());
        assertEquals("http://mc.example.org:8100", p.urlBase());
        assertEquals("pack/medivalrpg.zip", p.path());
        assertEquals("plugins/squaremap/web", p.webRoot());
        assertEquals("auto", p.source());
        assertTrue(p.verifyDownload());
        assertEquals(new pl.medivalrpg.interakcje.pack.PackVerifier.Policy(6, 15_000, 5_000, 60_000), p.verifyPolicy());
        assertEquals("auto", p.verifyLocalBase());
    }
}
