// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.interakcje.anim.AnimationCatalog;
import pl.medivalrpg.interakcje.npc.NpcSettings;

import java.io.InputStream;
import java.io.InputStreamReader;
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

/** The new interactions (handshake ... coronation): consent, ruler requirement, catalogue links, defaults merge. */
class CeremonyConfigTest {

    private static YamlConfiguration bundled() throws Exception {
        try (InputStream in = CeremonyConfigTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static AnimationCatalog catalog() throws Exception {
        try (InputStream in = CeremonyConfigTest.class.getClassLoader().getResourceAsStream("bettermodel/animations.json")) {
            List<String> warnings = new ArrayList<>();
            AnimationCatalog c = AnimationCatalog.parse(new InputStreamReader(in, StandardCharsets.UTF_8), warnings::add);
            assertEquals(List.of(), warnings);
            return c;
        }
    }

    private static Map<String, InteractionDef> defs() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<String, InteractionDef> defs = InteractionLoader.load(bundled().getConfigurationSection("interactions"),
                Set.of("interakcje", "int", "pusc", "usiadz", "poloz", "taniec", "emotka"), warnings::add);
        assertEquals(List.of(), warnings);
        return defs;
    }

    @Test
    void everyTwoPersonCeremonyAsksForConsent() throws Exception {
        Map<String, InteractionDef> defs = defs();
        for (String id : List.of("handshake", "toast", "courtbow", "knight", "fealty", "coronation")) {
            InteractionDef d = defs.get(id);
            assertNotNull(d, id);
            assertTrue(d.consent(), id + " needs the partner's consent");
            assertNotNull(d.animation(), id + " has an animation");
        }
        assertEquals("uscisk", defs.get("handshake").command());
        assertEquals("pasuj", defs.get("knight").command());
        assertEquals("lenno", defs.get("fealty").command());
        assertEquals("koronuj", defs.get("coronation").command());
    }

    @Test
    void coronationIsForRulersAndCanBeDoneAlone() throws Exception {
        InteractionDef crown = defs().get("coronation");
        assertEquals(InteractionDef.Requirement.RULER, crown.requires());
        assertTrue(crown.targetOptional());
        assertTrue(crown.audienceBow());
        assertEquals("crown", crown.animationFor(true));
        assertEquals("crown_self", crown.animationFor(false));
        InteractionDef knight = defs().get("knight");
        assertTrue(knight.grantTitle());
        assertEquals(InteractionDef.Requirement.NONE, knight.requires(), "anyone may knight; only rulers give a title");
        assertFalse(defs().get("fealty").grantTitle(), "the oath is only an animation, no relation changes");
    }

    @Test
    void everyInteractionAnimationIsInTheCatalogue() throws Exception {
        AnimationCatalog cat = catalog();
        for (InteractionDef d : defs().values()) {
            for (boolean t : new boolean[]{true, false}) {
                String a = d.animationFor(t);
                if (a != null) {
                    assertNotNull(cat.animation(a), d.id() + " -> " + a);
                }
            }
        }
        assertTrue(cat.animation("knight").pair());
        assertFalse(cat.animation("crown_self").pair());
    }

    @Test
    void theCrownHasATrackFromTheHandsToTheHead() throws Exception {
        AnimationCatalog cat = catalog();
        for (String clip : List.of("crown_a", "crown_self")) {
            AnimationCatalog.Prop crown = cat.clip(clip).prop("crown");
            assertNotNull(crown, clip);
            assertTrue(crown.firstTick() > 0 && crown.firstTick() < crown.setTick(), clip + " is taken before it is set");
            assertTrue(crown.setTick() < cat.clip(clip).lengthTicks());
            AnimationCatalog.PropPoint high = crown.at(crown.setTick() - 25);
            AnimationCatalog.PropPoint on = crown.at(crown.setTick() + 2);
            assertTrue(high.y() > on.y(), clip + ": lowered onto the head");
            assertTrue(on.y() > 20, clip + ": on a head, not on the ground (" + on.y() + ")");
        }
        assertNull(cat.clip("crown_b").prop("crown"), "only the officiant's clip carries the crown");
        // interpolation between two points
        AnimationCatalog.Prop p = new AnimationCatalog.Prop(10, List.of(new AnimationCatalog.PropPoint(0, 0, 0, 0),
                new AnimationCatalog.PropPoint(10, 10, 20, -10)));
        assertEquals(5.0, p.at(5).x(), 1e-9);
        assertEquals(10.0, p.at(5).y(), 1e-9);
        assertEquals(-10.0, p.at(99).z(), 1e-9);
    }

    @Test
    void workAnimationsAreLoopsOfThePlayerModel() throws Exception {
        AnimationCatalog cat = catalog();
        assertEquals(Set.of("budowa", "noszenie_plecy", "noszenie_rece", "kilof", "motyka", "siekiera", "kowadlo",
                "zaproszenie", "salut", "straz", "ziewanie", "odpoczynek"), cat.work().keySet());
        for (Map.Entry<String, String> e : cat.work().entrySet()) {
            AnimationCatalog.Clip c = cat.workClip(e.getKey());
            assertNotNull(c, e.getKey());
            assertEquals("interakcje", c.model(), e.getKey() + ": NPCs use the model without camera head tracking");
            boolean gesture = Set.of("salut", "ziewanie").contains(e.getKey());
            assertEquals(!gesture, c.loop(), e.getKey() + (gesture ? " is a one-off gesture" : " loops"));
        }
        assertNotNull(cat.workClip("KILOF"), "names are case-insensitive");
        assertNotNull(cat.workClip("npc_axe"), "clip names work too");
        assertNull(cat.workClip("taniec_brzucha"));
        assertFalse(cat.clip("npc_hammer").impactTicks().isEmpty(), "hammer blows have impact moments");
    }

    @Test
    void newDefaultInteractionsReachAnOldConfigWithoutOverridingIt() throws Exception {
        YamlConfiguration old = new YamlConfiguration();
        old.set("interactions.kiss.command", "calusek");
        old.set("interactions.toast.enabled", false);
        old.set("emotes.list.klaskanie.name", "Brawo");
        old.setDefaults(bundled());
        List<String> added = DefaultEntries.addMissing(old, true);
        assertTrue(added.contains("interactions.coronation"));
        assertTrue(added.contains("interactions.hug"));
        assertFalse(added.contains("interactions.toast"), "disabled by the admin stays disabled");
        assertFalse(added.contains("interactions.kiss"));
        assertEquals("calusek", old.getString("interactions.kiss.command"));
        assertEquals("koronuj", old.getString("interactions.coronation.command"));
        assertEquals("ruler", old.getString("interactions.coronation.requires"));
        assertEquals("block.bell.use",
                ((Map<?, ?>) old.getList("interactions.coronation.effects.sounds").getFirst()).get("sound"));
        assertTrue(added.contains("emotes.list.salut_miecz"));
        assertEquals(List.of(), DefaultEntries.addMissing(old, true), "idempotent");
        YamlConfiguration off = new YamlConfiguration();
        off.set("interactions.kiss.command", "x");
        off.setDefaults(bundled());
        assertEquals(List.of(), DefaultEntries.addMissing(off, false));
    }

    @Test
    void npcSettingsAreClampedAndDefaultToMannequins() {
        NpcSettings d = NpcSettings.from(null);
        assertEquals(Set.of("MANNEQUIN"), d.entityTypes());
        assertEquals(40, d.maxActive());
        MemoryConfiguration m = new MemoryConfiguration();
        m.set("range", 1000);
        m.set("max-active", -5);
        m.set("budget-ms", 0);
        NpcSettings s = NpcSettings.from(m);
        assertEquals(96.0, s.range());
        assertEquals(0, s.maxActive());
        assertEquals(0.1, s.budgetMs());
    }
}
