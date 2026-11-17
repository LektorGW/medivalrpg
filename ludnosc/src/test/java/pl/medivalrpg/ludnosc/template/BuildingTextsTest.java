// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.ludnosc.config.LudnoscConfig;
import pl.medivalrpg.ludnosc.logic.PlayerBuildingRules;
import pl.medivalrpg.ludnosc.logic.PlayerCountryRules;

/**
 * The descriptions say what a building DOES, in one format, with numbers that are those of the code and of the jar
 * config (decision of 2026-11-11); variants say the same as their base; an old exported copy gets the new text.
 */
class BuildingTextsTest {

    private static TemplateTexts texts;
    private static List<BuildingTemplate> templates;
    private static YamlConfiguration config;

    @BeforeAll
    static void load() throws Exception {
        texts = TemplateTexts.load();
        templates = BuiltinTemplates.loadAll();
        try (var in = BuildingTextsTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static String functional(final TemplateTexts.Entry e) {
        assertTrue(e.description().endsWith(e.look()), e.description());
        return e.description().substring(0, e.description().length() - e.look().length()).trim();
    }

    @Test
    void everyBuiltinTemplateHasTextsInOneFormat() {
        assertEquals(BuiltinTemplates.IDS.size(), texts.entries().size());
        for (final BuildingTemplate t : templates) {
            final TemplateTexts.Entry e = texts.entry(t.id());
            assertNotNull(e, t.id());
            assertEquals(e.description(), t.meta().description(), t.id() + ": opis w YAML = opisy.yml");
            assertEquals(t.meta().baseType(), e.base(), t.id());
            assertFalse(e.effect().isBlank(), t.id());
            assertFalse(e.look().isBlank(), t.id());
            assertTrue(e.effect().length() <= 60, t.id() + ": krótki skutek " + e.effect().length());
            assertTrue(e.description().length() <= 300, t.id() + ": opis ma " + e.description().length() + " znaków");
            // what it does comes first, the look is the last sentence only
            assertFalse(functional(e).isBlank(), t.id());
            assertEquals(e.description(), texts.description(t));
            assertEquals(e.effect(), texts.effect(t));
        }
    }

    @Test
    void variantsSayTheSameAboutTheGameplayAsTheirBase() {
        int variants = 0;
        for (final BuildingTemplate t : templates) {
            if (!t.meta().isVariant()) {
                continue;
            }
            variants++;
            final TemplateTexts.Entry own = texts.entry(t.id());
            final TemplateTexts.Entry base = texts.entry(t.meta().baseType());
            assertEquals(functional(base), functional(own), t.id());
            assertEquals(base.effect(), own.effect(), t.id());
        }
        assertEquals(BuiltinTemplates.VARIANT_IDS.size() + 3, variants);
    }

    @Test
    void bonusNumbersAreThoseOfTheCode() {
        for (final BuildingTemplate t : templates) {
            final String d = t.meta().description();
            final LudnoscConfig.Bonus b = LudnoscConfig.Bonus.DEFAULTS.get(t.meta().baseType());
            final boolean tax = Pattern.compile("[Pp]odatki( od obywateli)? \\+\\d+%").matcher(d).find();
            final boolean growth = d.contains("osadnika +");
            assertEquals(b != null && b.tax() != 0, tax, t.id() + ": podatki");
            assertEquals(b != null && b.growth() != 0, growth, t.id() + ": wzrost");
            if (b != null && b.tax() != 0) {
                assertTrue(Pattern.compile("[Pp]odatki( od obywateli)? \\+" + Math.round(b.tax() * 100) + "%").matcher(d).find(),
                    t.id() + ": " + d);
            }
            if (b != null && b.growth() != 0) {
                assertTrue(d.toLowerCase(java.util.Locale.ROOT).contains("szansa na nowego osadnika +" + Math.round(b.growth() * 100) + "%"),
                    t.id() + ": " + d);
            }
            if (b != null && b.granary() != 0) {
                assertTrue(d.contains("pojemność spichlerza +" + b.granary()), t.id() + ": " + d);
            }
            // the food bonus and the mine income do nothing since the physical economy: never promised
            assertFalse(d.contains("jedzenie +") || d.contains("Więcej jedzenia") || d.contains("dochód z kamienia")
                || d.contains("rozbudowa daje dochód"), t.id() + ": " + d);
        }
        // the same numbers in the jar config (a changed default must change the text)
        for (final String id : LudnoscConfig.Bonus.DEFAULTS.keySet()) {
            final LudnoscConfig.Bonus b = LudnoscConfig.Bonus.DEFAULTS.get(id);
            if (config.isSet("buildings." + id + ".bonus")) {
                assertEquals(b.tax(), config.getDouble("buildings." + id + ".bonus.tax", 0), 1e-9, id);
                assertEquals(b.growth(), config.getDouble("buildings." + id + ".bonus.growth", 0), 1e-9, id);
                assertEquals(b.granary(), config.getLong("buildings." + id + ".bonus.granary", 0), id);
            }
        }
    }

    @Test
    void numbersOfTheWorkplacesAreThoseOfTheConfig() {
        final Map<String, String> d = new java.util.HashMap<>();
        for (final BuildingTemplate t : templates) {
            d.put(t.id(), t.meta().description());
        }
        assertTrue(d.get("spichlerz").contains("o " + config.getLong("food.capacity-per-granary") + " pkt jedzenia"));
        assertTrue(d.get("spichlerz").contains("zjada " + config.getInt("food.per-citizen") + " pkt dziennie"));
        assertTrue(d.get("kamieniolom").contains("do " + config.getInt("resources.quarry-stone-per-day") + " bloków kamienia dziennie"));
        assertTrue(d.get("tartak").contains("w promieniu " + config.getInt("resources.lumberjack-radius") + " bloków"));
        assertTrue(d.get("pole").contains("(" + config.getInt("work.slots.farm") + " miejsca pracy)"));
        assertTrue(d.get("przystan").contains("(" + config.getInt("work.slots.harbor") + " miejsca pracy)"));
        assertTrue(d.get("przystan").contains("do " + config.getInt("work.fish-cap-per-fisher") + " ryb dziennie na rybaka"));
        assertTrue(d.get("przystan").contains("w promieniu " + config.getInt("work.player-range") + " bloków"));
        final String range = "gracza w promieniu " + config.getInt("workshops.player-range") + " bloków";
        final String dig = "w promieniu " + config.getInt("workshops.dig.radius") + " bloków";
        assertTrue(d.get("huta_szkla").contains(range) && d.get("huta_szkla").contains(dig)
            && d.get("huta_szkla").contains("do " + config.getInt("workshops.dig.sand-per-day") + " dziennie"));
        assertTrue(d.get("cegielnia").contains(range) && d.get("cegielnia").contains(dig)
            && d.get("cegielnia").contains("do " + config.getInt("workshops.dig.clay-per-day") + " dziennie"));
        assertTrue(d.get("owczarnia").contains(range) && d.get("owczarnia").contains("do " + config.getInt("workshops.sheep.flock") + " owiec"));
        assertTrue(d.get("kuznia").contains(range));
        assertTrue(d.get("magazyn").contains("każda skrzynia i beczka"));
    }

    @Test
    void bedsAreStatedTheSameWayEverywhere() {
        for (final BuildingTemplate t : templates) {
            final String d = t.meta().description();
            final int beds = t.bedCount();
            switch (t.meta().function()) {
                case HOUSING -> assertTrue(d.startsWith("Mieszkanie dla " + beds + " obywateli (" + beds + " łóż"), t.id() + ": " + d);
                case BARRACKS -> assertTrue(d.contains("(" + beds + " prycz = +" + beds + ")") && d.contains("nie liczą się jako mieszkania"),
                    t.id() + ": " + d);
                default -> {
                    if (beds > 0) {
                        assertTrue(d.contains("(" + beds + ") nie liczą się jako mieszkania"), t.id() + ": " + d);
                    } else {
                        assertFalse(d.contains("łóż"), t.id() + ": " + d);
                    }
                }
            }
        }
    }

    @Test
    void theSeventeenPlayerBuildingsAndTheOwnTypesAreDescribed() {
        final List<String> allowed = List.of("chata", "kopalnia", "kamieniolom", "tartak", "pole", "przystan", "huta_szkla", "cegielnia",
            "owczarnia", "kuznia", "spichlerz", "magazyn", "studnia", "koszary", "ratusz", "karczma", "kosciol");
        for (final String id : allowed) {
            assertTrue(PlayerCountryRules.allowed(false, false, id, id, allowed), id);
            assertNotNull(texts.entry(id), id);
        }
        assertEquals(9, PlayerBuildingRules.DEFAULTS.size());
        for (final PlayerBuildingRules.Def def : PlayerBuildingRules.DEFAULTS) {
            assertFalse(def.effect().contains("premia") || def.effect().contains("dla Armii"), def.key() + ": " + def.effect());
        }
        final Map<String, String> effects = new java.util.HashMap<>();
        PlayerBuildingRules.DEFAULTS.forEach(def -> effects.put(def.key(), def.effect()));
        assertTrue(effects.get("ratusz").contains("+8%") && effects.get("karczma").contains("+5%") && effects.get("karczma").contains("+10%"));
        assertTrue(effects.get("kosciol").contains("+20%") && effects.get("spichlerz").contains("+2000") && effects.get("magazyn").contains("+300"));
        assertTrue(effects.get("koszary").contains("limit żołnierzy") && effects.get("koszary").contains("nie liczą się jako mieszkania"));
    }

    @Test
    void anOldExportedCopyGetsTheNewTextButAnEditedOneKeepsItsOwn() {
        final BuildingTemplate chata = templates.stream().filter(t -> t.id().equals("chata")).findFirst().orElseThrow();
        final TemplateTexts.Entry e = texts.entry("chata");
        assertTrue(e.previous().contains("Mała drewniana chata na kamiennej podmurówce. 2 łóżka."));
        // the copy exported by an older jar into plugins/Ludnosc/budynki (it overrides the built-in)
        final BuildingTemplate old = withDescription(chata, "Mała drewniana chata na kamiennej podmurówce. 2 łóżka.");
        assertEquals(e.description(), texts.description(old));
        assertEquals(e.effect(), texts.effect(old));
        assertTrue(texts.entry("studnia").previous().contains("Kamienna studnia z daszkiem."), "kopia z 25.10 na produkcji");
        final BuildingTemplate edited = withDescription(chata, "Chata sołtysa — opis administratora.");
        assertEquals("Chata sołtysa — opis administratora.", texts.description(edited));
        assertEquals("", texts.effect(edited));
        final BuildingTemplate blank = withDescription(chata, "");
        assertEquals(e.description(), texts.description(blank));
        // not a built-in: its own text, no short effect
        final BuildingTemplate custom = new BuildingTemplate(new BuildingTemplate.Meta("palac_krola", "Pałac", "Ze schematu.",
            BuildingFunction.PALACE, 0, 1, FoundationMode.FILL, "cobblestone", 0, "plik"), 1, 1, 1, new String[] {"stone"});
        assertEquals("Ze schematu.", texts.description(custom));
        assertEquals("", texts.effect(custom));
        // every previous text really differs from the current one
        texts.entries().forEach((id, x) -> assertFalse(x.previous().contains(x.description()), id));
    }

    private static BuildingTemplate withDescription(final BuildingTemplate t, final String description) {
        final BuildingTemplate.Meta m = t.meta();
        return t.withMeta(new BuildingTemplate.Meta(m.id(), m.displayName(), description, m.function(), m.declaredBeds(), m.cost(),
            m.foundation(), m.foundationBlock(), m.groundLayer(), m.source(), m.tier(), m.category(), m.zone(), m.maxPerCountry(),
            m.minPopulation(), m.minTreasury(), m.era(), m.discovery(), m.variantOf()));
    }
}
