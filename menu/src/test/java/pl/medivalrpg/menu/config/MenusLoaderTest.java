// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.menu.model.BookDef;
import pl.medivalrpg.menu.model.ButtonDef;
import pl.medivalrpg.menu.model.Condition;
import pl.medivalrpg.menu.model.MenuAction;
import pl.medivalrpg.menu.model.MenuConfig;
import pl.medivalrpg.menu.model.MenuDef;
import pl.medivalrpg.menu.model.MenuType;

class MenusLoaderTest {

    private static MenuConfig bundled() {
        return MenusLoader.load(YamlConfiguration.loadConfiguration(new InputStreamReader(
            MenusLoaderTest.class.getResourceAsStream("/menus.yml"), StandardCharsets.UTF_8)));
    }

    private static MenuConfig parse(final String yaml) {
        final YamlConfiguration c = new YamlConfiguration();
        try {
            c.loadFromString(yaml);
        } catch (final InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return MenusLoader.load(c);
    }

    private static ButtonDef button(final MenuDef menu, final String id) {
        return menu.buttons().stream().filter(b -> b.id().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("no button " + id + " in " + menu.id()));
    }

    // ------------------------------------------------------------------ bundled menus.yml

    private static final List<String> ALL_MENUS = List.of("main", "postac", "interakcje", "ruchy", "kraj",
        "kraj-dyplomacja", "osada", "armia", "flota", "skarbiec", "glos", "teleporty", "swiat", "pomoc", "ustawienia",
        "admin", "panstwa", "postep", "wladcy", "unia", "ambasada", "praca-sezony", "ludnosc-admin",
        "kolej", "kolej-admin", "kolej-infrastruktura", "kolej-linie", "kolej-firma", "turnieje", "turnieje-admin",
        "szlaki", "szlaki-projekt", "szlaki-admin", "kolej-transport", "pomoc-moduly", "osada-kopie",
        "osada-obywatele", "kraj-zarzad", "skarbswiata-admin", "osada-budowa",
        // 2026-10-29 (popołudnie): firmy, chorągiew, zakłady, uruchomienie kolei, podmenu admina, Straż
        "firmy", "firmy-zarzad", "firmy-kontrakty", "firmy-kredyty", "firmy-manufaktura", "kraj-flaga", "osada-zaklady",
        "kolej-uruchomienie", "admin-npc", "admin-gospodarka", "straz-admin",
        // 2026-11-03: Discord admina, zrzutka Firm, własne budynki Ludności
        "admin-discord", "firmy-zrzutka", "osada-wlasne",
        // 2026-11-11: samouczek nowego gracza
        "samouczek");

    /** Section of the guide by id, searched in every chapter book ({@code poradnik-*}). */
    static BookDef.SectionDef guideSection(final MenuConfig cfg, final String id) {
        return cfg.books().values().stream().filter(b -> b.id().startsWith("poradnik"))
            .flatMap(b -> b.sections().stream()).filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("no guide section " + id));
    }

    @Test
    void bundledMenusLoadWithoutWarnings() {
        final MenuConfig cfg = bundled();
        assertEquals(List.of(), cfg.warnings());
        for (final String id : ALL_MENUS) {
            assertNotNull(cfg.menu(id), id);
        }
        assertEquals(ALL_MENUS.size(), cfg.menus().size(), "no unreachable leftovers: " + cfg.menus().keySet());
        assertNotNull(cfg.book("poradnik"));
        for (final String section : List.of("start", "teleporty", "osada", "armia", "flota", "wladcy", "panel", "emotki", "epoki")) {
            assertNotNull(guideSection(cfg, section), section);
        }
    }

    @Test
    void mainMenuShowsCategories() {
        final MenuDef main = bundled().menu(MenuConfig.MAIN);
        assertEquals(6, main.rows());
        assertEquals("BLACK_STAINED_GLASS_PANE", main.border());
        assertEquals(List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43), main.autoSlots());

        final ButtonDef header = button(main, "header");
        assertEquals(4, header.slot());
        assertTrue(header.viewerHead());
        assertEquals("PLAYER_HEAD", header.material());
        assertInstanceOf(MenuAction.None.class, header.action());
        assertEquals(49, button(main, "zamknij").slot());
        assertInstanceOf(MenuAction.Close.class, button(main, "zamknij").action());

        final List<String> auto = main.buttons().stream().filter(ButtonDef::autoSlot).map(ButtonDef::id).toList();
        assertEquals(List.of("samouczek", "discord", "postac", "kraj", "osada", "armia", "postep", "pieniadze", "glos", "teleporty", "swiat",
            "pomoc", "ustawienia", "admin", "powrot", "kolej", "turnieje", "szlaki", "firmy"), auto);
        assertEquals(List.of("Postep"), button(main, "postep").requiresPlugins());
        assertEquals(new MenuAction.OpenMenu("postep"), button(main, "postep").action());
        assertEquals(new MenuAction.OpenMenu("postac"), button(main, "postac").action());
        assertEquals(List.of("Interakcje"), button(main, "postac").requiresPlugins());
        assertEquals(List.of("Ludnosc"), button(main, "osada").requiresPlugins());
        assertEquals(List.of("Armia"), button(main, "armia").requiresPlugins());
        assertEquals("armia.use", button(main, "armia").permission());
        assertEquals(new MenuAction.OpenMenu("skarbiec"), button(main, "pieniadze").action());
        assertEquals(List.of("Skarbiec"), button(main, "pieniadze").requiresPlugins());
        assertEquals("menu.admin", button(main, "admin").permission());
        assertEquals(Condition.CAN_RETURN, button(main, "powrot").condition());
        assertEquals(new MenuAction.Command("powrot"), button(main, "powrot").action());
    }

    @Test
    void everySubmenuIsReachableAndHasAWayBack() {
        final MenuConfig cfg = bundled();
        final java.util.Set<String> linked = new java.util.HashSet<>();
        for (final MenuDef m : cfg.menus().values()) {
            for (final ButtonDef b : m.buttons()) {
                if (b.action() instanceof MenuAction.OpenMenu(final String id)) {
                    linked.add(id);
                }
            }
        }
        for (final String id : ALL_MENUS) {
            if (!id.equals(MenuConfig.MAIN)) {
                assertTrue(linked.contains(id), id + " is opened from some button");
                assertTrue(cfg.menu(id).buttons().stream().anyMatch(b -> b.action() instanceof MenuAction.OpenMenu),
                    id + " has a back button");
            }
        }
    }

    /** Every server feature from the plugins' real command trees is one click away in /menu. */
    @Test
    void everyFeatureIsInTheMenu() {
        final java.util.Set<String> commands = new java.util.HashSet<>();
        for (final MenuDef m : bundled().menus().values()) {
            for (final ButtonDef b : m.buttons()) {
                switch (b.action()) {
                    case MenuAction.Command(final String c) -> commands.add(c);
                    case MenuAction.Suggest(final String t) -> commands.add(t.strip().replaceFirst("^/", ""));
                    case MenuAction.PickPlayer(final double r, final String c) -> commands.add(c);
                    default -> { }
                }
            }
        }
        for (final String c : List.of(
            // teleports
            "spawn", "dom", "domy", "ustawdom", "usundom", "powrot", "kraj spawn",
            // interakcje
            "usiadz", "poloz", "pusc", "taniec stop", "emotka stop", "interakcje wlacz", "interakcje wylacz",
            // glos
            "glos", "glos szept", "glos normalnie", "glos krzyk", "glos rozlacz", "glos pasek", "glos wskaznik",
            // kraj
            "kraj zaloz", "kraj dolacz", "kraj info", "kraj zajmij", "kraj porzuc", "kraj mapa", "kraj zapros",
            "kraj czat", "kraj skarbiec", "kraj wplac", "kraj wyplac", "kraj podatek", "kraj oblezenie",
            "kraj oblezenia", "kraj wasale", "kraj kapitulacja", "kraj wykup", "kraj sojusz", "kraj wojna",
            "kraj pokoj", "kraj zerwij", "kraj lista", "kraj pomoc",
            // ludnosc
            "ludnosc info", "ludnosc budynki", "ludnosc typy", "ludnosc buduj", "ludnosc kolejka",
            "ludnosc plan", "ludnosc spichlerz", "ludnosc kopalnia", "ludnosc modernizuj", "ludnosc napraw",
            "ludnosc plan krolestwo", "ludnosc magazyn",
            "ludnosc przebuduj", "ludnosc podziemia", "zycie", "zycie kraj",
            // armia
            "armia rekrutuj", "armia lista", "armia info", "armia zamna", "armia stoj", "armia wroc", "armia straz",
            "armia patrol dodaj", "armia patrol wyczysc", "armia atakuj", "armia bulawa", "armia rozpusc potwierdz", "armia patrol start",
            "armia najmij", "armia najemnicy", "armia przedluz wszystkich", "armia zwolnij-najemnikow",
            "armia przezbroj", "armia trybut przyjmij", "armia trybut odrzuc",
            // postep
            "postep", "postep drzewko", "postep odkrycia", "postep inwestuj", "postep ranking", "postep epoki",
            // skarbiec
            "saldo", "monety", "zaplac", "handel", "skarbiec sklepy",
            // world, help, admin
            "panel", "nowosci", "wladca rozmowa", "przewodnik daj", "nk lista", "nk scenariusz lista", "wladca koszt",
            "nk tp", "nk scenariusz pokaz europa1350", "nk wioska", "postep admin tick",
            "ludnosc admin przebudowa", "ludnosc admin drogi", "ludnosc admin modernizuj", "zycie admin swieto",
            "zycie admin nastroj", "kraj admin zajmij", "kraj admin przylacz", "kraj admin porzuc", "kraj admin tp",
            "skarbiec npc lista", "menu reload")) {
            assertTrue(commands.contains(c), "missing in /menu: /" + c);
        }
    }

    @Test
    void motionsMenuIsGenerated() {
        final MenuDef m = bundled().menu("ruchy");
        assertEquals(MenuType.MOTIONS, m.type());
        assertEquals(28, m.contentSlots().size());
        assertEquals(MenuType.MOTIONS, MenuType.parse("emotki"));
        assertEquals(new MenuAction.OpenMenu("postac"), button(bundled().menu("interakcje"), "wstecz").action());
    }

    @Test
    void countryMenuConditions() {
        final MenuDef kraj = bundled().menu("kraj");
        assertEquals(new MenuAction.Suggest("/kraj wplac "), button(kraj, "wplac").action());
        assertEquals(new MenuAction.OpenMenu("kraj-dyplomacja"), button(kraj, "dyplomacja").action());
        final ButtonDef found = button(kraj, "zaloz");
        assertEquals(Condition.NO_COUNTRY, found.condition());
        assertEquals(new MenuAction.Suggest("/kraj zaloz "), found.action());
        assertEquals(Condition.HAS_COUNTRY, button(kraj, "zajmij").condition());
        assertEquals(new MenuAction.Command("kraj zajmij"), button(kraj, "zajmij").action());
        assertEquals(List.of("Skarbiec"), button(kraj, "skarbiec-kraju").requiresPlugins());
        // both headers share slot 4 but are mutually exclusive
        assertEquals(4, button(kraj, "naglowek").slot());
        assertEquals(4, button(kraj, "naglowek-brak").slot());
    }

    @Test
    void interactionsMenuIsGenerated() {
        final MenuDef m = bundled().menu("interakcje");
        assertEquals(MenuType.INTERACTIONS, m.type());
        assertEquals(21, m.contentSlots().size());
        assertEquals(10, m.contentSlots().getFirst());
        assertEquals(34, m.contentSlots().getLast());
    }

    @Test
    void countriesMenuIsGeneratedAndAdminOnly() {
        final MenuConfig cfg = bundled();
        final MenuDef m = cfg.menu("panstwa");
        assertEquals(MenuType.COUNTRIES, m.type());
        assertEquals("menu.admin", m.permission());
        assertEquals(List.of("Kraje"), m.requiresPlugins());
        assertEquals(28, m.contentSlots().size());
        assertEquals(new MenuAction.Page(-1), button(m, "poprzednia").action());
        assertEquals(new MenuAction.Page(1), button(m, "nastepna").action());
        assertEquals(new MenuAction.OpenMenu("main"), button(m, "wstecz").action());

        final ButtonDef entry = button(cfg.menu(MenuConfig.MAIN), "panstwa");
        assertEquals("FILLED_MAP", entry.material());
        assertEquals("menu.admin", entry.permission());
        assertEquals(new MenuAction.OpenMenu("panstwa"), entry.action());
        assertEquals(51, entry.slot(), "fixed slot in the bottom frame: the players' layout stays the same");
    }

    @Test
    void countriesTypeAndPageActions() {
        assertEquals(MenuType.COUNTRIES, MenuType.parse("countries"));
        assertEquals(MenuType.COUNTRIES, MenuType.parse("Panstwa"));
        assertEquals(new MenuAction.Page(-1), MenuAction.parse("page:prev"));
        assertEquals(new MenuAction.Page(1), MenuAction.parse("strona: nastepna"));
        assertThrows(IllegalArgumentException.class, () -> MenuAction.parse("page:3"));

        final MenuConfig cfg = parse("""
            menus:
              main:
                items:
                  dalej:
                    slot: 0
                    action: "page:next"
              lista:
                type: countries
                items: {}
            """);
        assertEquals(2, cfg.warnings().size(), cfg.warnings().toString());
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("menus.lista") && w.contains("content-slots")));
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("menus.main.items.dalej") && w.contains("page")));
    }

    @Test
    void tradeUsesPlayerPicker() {
        assertEquals(new MenuAction.PickPlayer(10, "handel"), button(bundled().menu("skarbiec"), "handel").action());
    }

    @Test
    void bookSectionsDependOnPlugins() {
        final MenuConfig cfg = bundled();
        final BookDef index = cfg.book("poradnik");
        assertEquals(List.of("samouczek", "start", "kraj", "ekonomia", "firmy", "ludnosc", "wlasne-budynki", "zaklady", "armia", "dyplomacja", "postep",
            "kolej", "handel", "turnieje", "postac", "admin"), index.chapters());
        final BookDef start = cfg.book("poradnik-start");
        assertEquals("start", start.sections().getFirst().id());
        assertTrue(start.sections().getFirst().requiresPlugins().isEmpty());
        assertTrue(start.requiresPlugins().isEmpty(), "the first chapter is always readable");
        final BookDef army = cfg.book("poradnik-armia");
        final BookDef.SectionDef armia = guideSection(cfg, "armia");
        assertTrue(army.requiresPlugins().contains("Armia") || armia.requiresPlugins().contains("Armia"));
        assertFalse(armia.paragraphs().isEmpty());
        assertEquals("menu.admin", cfg.book("poradnik-admin").permission());
    }

    // ------------------------------------------------------------------ validation

    @Test
    void requiresCommandBecomesACommandRequirementOfButtonsMenusBooksAndSections() {
        final MenuConfig cfg = parse("""
            menus:
              main:
                rows: 3
                requires-command: "menu"
                items:
                  a:
                    slot: 0
                    requires-plugin: Firmy
                    requires-command: ["firma kredyt", "/Firma  Kontrakt"]
                    action: "command:firma kredyt"
                  b:
                    slot: 1
                    requires-command: "firma <kwota>"
                    action: "command:firma"
            books:
              poradnik:
                chapters: [firmy, brak]
              poradnik-firmy:
                requires-command: "firma"
                name: "<gold>Firmy"
                description: "Konto i udziały"
                sections:
                  s:
                    requires-command: "firma kredyt"
                    text: "x"
            """);
        assertEquals(List.of("/menu"), cfg.menu("main").requiresPlugins());
        assertEquals(List.of("Firmy", "/firma kredyt", "/firma kontrakt"), button(cfg.menu("main"), "a").requiresPlugins());
        assertEquals(List.of(), button(cfg.menu("main"), "b").requiresPlugins(), "a path with arguments is rejected");
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("firma <kwota>")), cfg.warnings().toString());
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("poradnik-brak")), "missing chapter book is reported");
        final BookDef chapter = cfg.book("poradnik-firmy");
        assertEquals(List.of("/firma"), chapter.requiresPlugins());
        assertEquals("<gold>Firmy", chapter.name());
        assertEquals(List.of("/firma kredyt"), chapter.sections().getFirst().requiresPlugins());
        assertEquals("/menu poradnik firmy", cfg.book("poradnik").chapterCommand("firmy"));
        assertTrue(cfg.warnings().stream().noneMatch(w -> w.contains("poradnik:")), "an index needs no own sections");
    }

    @Test
    void slotsAndRanges() {
        final MenuConfig cfg = parse("""
            menus:
              main:
                rows: 3
                auto-slots: [10-12, 14, "16"]
                content-slots: "0-2, 26"
                items:
                  a: { slot: 13, material: stone, action: "command:/spawn" }
                  b: { material: dirt }
                  c: { slot: auto, action: "menu:main" }
            """);
        assertEquals(List.of(), cfg.warnings());
        final MenuDef main = cfg.menu("main");
        assertEquals(List.of(10, 11, 12, 14, 16), main.autoSlots());
        assertEquals(List.of(0, 1, 2, 26), main.contentSlots());
        assertEquals("STONE", button(main, "a").material());
        assertEquals(new MenuAction.Command("spawn"), button(main, "a").action());
        assertTrue(button(main, "b").autoSlot());
        assertInstanceOf(MenuAction.None.class, button(main, "b").action());
        assertTrue(button(main, "c").autoSlot());
    }

    @Test
    void brokenEntriesAreSkippedWithWarnings() {
        final MenuConfig cfg = parse("""
            menus:
              main:
                rows: 9
                auto-slots: [50-60, 1, 1]
                items:
                  outside: { slot: 54 }
                  badaction: { slot: 1, action: "teleport:x" }
                  badpick: { slot: 2, action: "player:abc:handel" }
                  nocommand: { slot: 3, action: "command:" }
                  badcondition: { slot: 4, condition: "is-king" }
                  first: { slot: 5 }
                  second: { slot: 5 }
                  link: { slot: 6, action: "menu:nope" }
                  book: { slot: 7, action: "book:nope" }
              "Zła nazwa":
                items: {}
            """);
        final MenuDef main = cfg.menu("main");
        assertEquals(6, main.rows(), "rows out of range → 6");
        assertEquals(List.of(50, 51, 52, 53, 1), main.autoSlots());
        assertEquals(List.of("first", "second", "link", "book"), main.buttons().stream().map(ButtonDef::id).toList());
        final String all = String.join("\n", cfg.warnings());
        assertTrue(all.contains("rows"), all);
        assertTrue(all.contains("slot 54"), all);
        assertTrue(all.contains("powtórzony"), all);
        assertTrue(all.contains("outside.slot"), all);
        assertTrue(all.contains("badaction.action"), all);
        assertTrue(all.contains("badpick.action"), all);
        assertTrue(all.contains("nocommand.action"), all);
        assertTrue(all.contains("badcondition.condition"), all);
        assertTrue(all.contains("second: slot 5"), all);
        assertTrue(all.contains("menu:nope"), all);
        assertTrue(all.contains("book:nope"), all);
        assertTrue(all.contains("Zła nazwa"), all);
        assertEquals(1, cfg.menus().size());
    }

    @Test
    void missingMainMenuIsReported() {
        final MenuConfig cfg = parse("""
            menus:
              other:
                rows: 1
            """);
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("'main'")));
    }

    @Test
    void tooManyAutoButtons() {
        final MenuConfig cfg = parse("""
            menus:
              main:
                rows: 1
                auto-slots: [0]
                items:
                  a: {}
                  b: {}
            """);
        assertTrue(cfg.warnings().stream().anyMatch(w -> w.contains("auto-slots ma tylko 1")));
    }

    // ------------------------------------------------------------------ actions and conditions

    @Test
    void actionParsing() {
        assertEquals(MenuAction.NONE, MenuAction.parse(null));
        assertEquals(MenuAction.NONE, MenuAction.parse("  "));
        assertEquals(MenuAction.CLOSE, MenuAction.parse("CLOSE"));
        assertEquals(new MenuAction.Command("kraj info"), MenuAction.parse("command://kraj info"));
        assertEquals(new MenuAction.Command("glos szept"), MenuAction.parse("cmd: glos szept"));
        assertEquals(new MenuAction.OpenMenu("kraj"), MenuAction.parse("menu:Kraj"));
        assertEquals(new MenuAction.OpenBook("poradnik"), MenuAction.parse("book:poradnik"));
        assertEquals(new MenuAction.Url("http://example.com:8080/a"), MenuAction.parse("url:http://example.com:8080/a"));
        assertEquals(new MenuAction.Suggest("/kraj zaloz "), MenuAction.parse("suggest:/kraj zaloz "));
        assertEquals(new MenuAction.PickPlayer(3.5, "pocaluj"), MenuAction.parse("player:3.5:/pocaluj"));
        assertThrows(IllegalArgumentException.class, () -> MenuAction.parse("player:0:x"));
        assertThrows(IllegalArgumentException.class, () -> MenuAction.parse("player:5"));
        assertThrows(IllegalArgumentException.class, () -> MenuAction.parse("url:"));
        assertThrows(IllegalArgumentException.class, () -> MenuAction.parse("command:///"));
        assertThrows(IllegalArgumentException.class, () -> MenuAction.parse("fly:away"));
    }

    @Test
    void conditionParsing() {
        assertEquals(Condition.NONE, Condition.parse(null));
        assertEquals(Condition.HAS_COUNTRY, Condition.parse("has_country"));
        assertEquals(Condition.NO_COUNTRY, Condition.parse("bez-kraju"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("rich"));
    }
}
