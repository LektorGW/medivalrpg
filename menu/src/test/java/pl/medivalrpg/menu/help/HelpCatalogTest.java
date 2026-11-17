// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.help;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.config.MenusLoader;
import pl.medivalrpg.menu.model.MenuAction;

class HelpCatalogTest {

    private static final Predicate<String> ALL = p -> true;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static YamlConfiguration yaml(final String resource) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            HelpCatalogTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8));
    }

    private static HelpCatalog catalog() {
        return HelpCatalog.load(yaml("/pomoc.yml"));
    }

    private static Messages messages() {
        return new Messages(yaml("/config.yml").getConfigurationSection("messages"));
    }

    private static HelpCatalog parse(final String text) {
        final YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(new StringReader(text));
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
        return HelpCatalog.load(y);
    }

    @Test void shippedCatalogLoadsWithoutWarningsAndHasTheRequestedCategories() {
        final HelpCatalog c = catalog();
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        for (final String id : List.of("start", "kraj", "dyplomacja", "ekonomia", "ludnosc", "armia", "epoki", "kolej",
            "handel", "turnieje", "postac", "nowosci")) {
            assertNotNull(c.category(id), id);
            assertFalse(c.category(id).entries().isEmpty(), id);
            assertFalse(c.category(id).guide().isEmpty(), id + " needs a 'how to start' guide");
        }
        assertTrue(c.categories().size() <= 14, "hub has two rows of seven category slots");
    }

    @Test void onlyPlayerCommandsNoAdministrationOrForeignTools() {
        for (final HelpCatalog.Category cat : catalog().categories().values()) {
            final Set<String> seen = new HashSet<>();
            for (final HelpCatalog.Entry e : cat.entries()) {
                final String cmd = e.command().toLowerCase(java.util.Locale.ROOT);
                assertTrue(seen.add(cmd), "duplicate " + cmd);
                if ("discordbot.admin".equals(e.permission())) {
                    assertTrue(cmd.startsWith("/discord admin "));
                    assertFalse(cat.visibleEntries(ALL, p -> false).contains(e), "admin help visible to a player");
                    continue;
                }
                for (final String banned : List.of(" admin", "/nk", "/npckraj", "reload", "przeladuj", "/ustawspawn",
                    "//", ":", "/rg", "/region", "/worldedit", "/we ", "/paper", "/skarbswiata", "/op", "/gamemode",
                    " zatwierdz", " audyt", " siec", " linia", "/kolej baza", "/kolej wagon", "/kolej ladunek")) {
                    assertFalse(cmd.contains(banned), cat.id() + ": " + cmd + " contains " + banned);
                }
                if (e.permission() != null) {
                    assertTrue(e.permission().startsWith("kolej.maszynista"), "only role entries may carry a permission: " + cmd);
                }
            }
        }
    }

    @Test void everyCategoryHasAHubButtonAndEveryHelpActionHasACategory() {
        final HelpCatalog help = catalog();
        final var menus = MenusLoader.load(yaml("/menus.yml"));
        assertTrue(menus.warnings().isEmpty(), menus.warnings().toString());
        final Set<String> targets = new HashSet<>();
        menus.menus().values().forEach(m -> m.buttons().forEach(b -> {
            if (b.action() instanceof MenuAction.Help(final String id)) {
                assertNotNull(help.category(id), m.id() + "/" + b.id());
                targets.add(id);
            }
        }));
        assertEquals(help.categories().keySet(), targets);
        assertTrue(menus.menu(HelpService.HUB_MENU).buttons().stream()
            .allMatch(b -> !(b.action() instanceof MenuAction.Help) || b.autoSlot()));
    }

    @Test void guideCommandsAreListedInTheCatalog() {
        final HelpCatalog help = catalog();
        final Set<String> prefixes = new HashSet<>();
        help.categories().values().forEach(c -> c.entries().forEach(e -> prefixes.add(HelpCatalog.suggestion(e.command()).strip())));
        for (final HelpCatalog.Category c : help.categories().values()) {
            for (final String line : c.guide()) {
                for (final HelpSearch.Segment s : HelpSearch.segments(line)) {
                    if (s.command() && s.text().startsWith("/")) {
                        final String literal = HelpCatalog.suggestion(s.text()).strip();
                        assertTrue(prefixes.stream().anyMatch(p -> literal.equals(p) || literal.startsWith(p + " ")),
                            c.id() + ": guide mentions " + s.text() + " which is not in the catalog");
                    }
                }
            }
        }
    }

    @Test void suggestionStopsAtTheFirstArgumentAndRunIsOnlyForArgumentlessCommands() {
        assertEquals("/kraj zaloz ", HelpCatalog.suggestion("/kraj zaloz <nazwa>"));
        assertEquals("/kraj info ", HelpCatalog.suggestion("/kraj info [kraj]"));
        assertEquals("/glos ", HelpCatalog.suggestion("/glos szept|normalnie|krzyk"));
        assertEquals("/saldo", HelpCatalog.suggestion("/saldo"));
        final HelpCatalog c = parse("""
            categories:
              a:
                name: A
                commands:
                  - {command: "/x <y>", description: "d", click: run}
                  - {command: "/z", description: "d", click: run}
                  - {command: "bez ukośnika", description: "d"}
                  - {command: "/bez-opisu"}
            """);
        assertEquals(3, c.warnings().size(), c.warnings().toString());
        final var entries = c.category("a").entries();
        assertEquals(2, entries.size());
        assertFalse(entries.get(0).run());
        assertEquals("/x ", entries.get(0).insert());
        assertTrue(entries.get(1).run());
        for (final HelpCatalog.Category cat : catalog().categories().values()) {
            for (final HelpCatalog.Entry e : cat.entries()) {
                if (e.run()) {
                    assertTrue(HelpCatalog.argumentless(e.command()), e.command());
                }
            }
        }
    }

    @Test void searchIgnoresCaseAndDiacriticsAndRanksCommandMatchesFirst() {
        final HelpCatalog c = catalog();
        final var tax = HelpSearch.search(c, "PODATEK", 12, ALL, ALL);
        assertFalse(tax.isEmpty());
        assertEquals("/kraj podatek [kwota]", tax.getFirst().entry().command());
        assertEquals("ekonomia", tax.getFirst().category().id());
        assertEquals(HelpSearch.search(c, "pociąg", 12, ALL, ALL), HelpSearch.search(c, "pociag", 12, ALL, ALL));
        assertTrue(HelpSearch.search(c, "pociag", 12, ALL, ALL).stream().allMatch(h -> h.category().id().equals("kolej")));
        assertEquals("/dom [nazwa]", HelpSearch.search(c, "/dom", 12, ALL, ALL).getFirst().entry().command());
        assertTrue(HelpSearch.search(c, "zzzz nic", 12, ALL, ALL).isEmpty());
        assertTrue(HelpSearch.search(c, "  ", 12, ALL, ALL).isEmpty());
        assertTrue(HelpSearch.search(c, "kraj", 5, ALL, ALL).size() <= 5);
        assertEquals("ludnosc swiat", HelpSearch.normalize("  Ludność /Świat "));
    }

    @Test void missingPluginsAndPermissionsHideCategoriesEntriesAndSearchHits() {
        final HelpCatalog c = catalog();
        final Predicate<String> noKraje = p -> !p.equals("Kraje");
        final var visible = c.visibleCategories(noKraje, ALL).stream().map(HelpCatalog.Category::id).toList();
        assertFalse(visible.contains("kraj"));
        assertFalse(visible.contains("dyplomacja"));
        assertTrue(visible.contains("ekonomia"));
        assertTrue(c.category("ekonomia").visibleEntries(noKraje, ALL).stream().noneMatch(e -> e.command().startsWith("/kraj")));
        assertTrue(HelpSearch.search(c, "kraj", 50, noKraje, ALL).stream().noneMatch(h -> h.entry().command().startsWith("/kraj")));
        final Predicate<String> noRole = p -> !p.equals("kolej.maszynista");
        assertTrue(c.category("kolej").visibleEntries(ALL, noRole).stream().noneMatch(e -> e.command().contains("prowadz")));
        assertTrue(c.category("kolej").visibleEntries(ALL, ALL).stream().anyMatch(e -> e.command().contains("prowadz")));
        assertTrue(c.visibleCategories(p -> false, ALL).stream().allMatch(cat -> cat.requiresPlugins().isEmpty()));
    }

    @Test void chatPagesAreClickableAndNeverParseCatalogTextAsMarkup() {
        final HelpCatalog c = parse("""
            categories:
              test:
                name: "<red>Test"
                description: "<bold>opis"
                guide: ["Wpisz `/kraj zaloz <nazwa>` i gotowe"]
                commands:
                  - {command: "/kraj zaloz <nazwa>", description: "<click:run_command:'/op x'>zly", rank: "Władca"}
                  - {command: "/saldo", description: "Stan konta", click: run}
            """);
        assertTrue(c.warnings().isEmpty(), c.warnings().toString());
        final HelpChat chat = new HelpChat(messages(), ALL, ALL);
        final List<Component> lines = chat.category(c, c.category("test"));
        final String text = lines.stream().map(PLAIN::serialize).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(text.contains("<red>Test"), text);
        assertTrue(text.contains("<bold>opis"), text);
        assertTrue(text.contains("/kraj zaloz <nazwa>"), text);
        assertTrue(text.contains("<click:run_command:'/op x'>zly"), text);
        assertTrue(text.contains("Jak zacząć"), text);
        final List<ClickEvent> clicks = new ArrayList<>();
        lines.forEach(l -> collect(l, clicks));
        assertTrue(clicks.contains(ClickEvent.suggestCommand("/kraj zaloz ")), clicks.toString());
        assertTrue(clicks.contains(ClickEvent.runCommand("/saldo")), clicks.toString());
        assertTrue(clicks.stream().noneMatch(e -> e.toString().contains("/op")), clicks.toString());
        assertTrue(clicks.stream().anyMatch(e -> e.equals(ClickEvent.suggestCommand("/pomoc szukaj "))));
    }

    @Test void searchAndOverviewPagesRenderWithMessagesFromTheJar() {
        final HelpCatalog c = catalog();
        final HelpChat chat = new HelpChat(messages(), ALL, ALL);
        final List<Component> overview = chat.overview(c);
        assertEquals(c.categories().size() + 3, overview.size());
        final List<ClickEvent> clicks = new ArrayList<>();
        overview.forEach(l -> collect(l, clicks));
        assertTrue(clicks.contains(ClickEvent.runCommand("/pomoc kolej")));
        final String none = PLAIN.serialize(chat.search(c, "qqqq").getFirst());
        assertTrue(none.contains("qqqq"), none);
        final List<Component> hits = chat.search(c, "sklep");
        assertTrue(hits.stream().map(PLAIN::serialize).anyMatch(h -> h.contains("/skarbiec sklepy")), hits.toString());
        for (final String key : List.of("help-header", "help-line", "help-gui-title", "help-gui-entry-name",
            "help-gui-guide-step", "help-search-header", "help-tab-hover", "help-gui-border", "help-gui-entry-icon")) {
            assertNotEquals(key, messages().raw(key), "missing default message " + key);
        }
    }

    @Test void requiresCommandHidesEntriesOfPreparedModulesInGame() {
        final List<String> warnings = new ArrayList<>();
        final HelpCatalog.Entry e = HelpCatalog.parseEntry("t", java.util.Map.of("command", "/firma kredyt",
            "description", "Kredyty", "requires-command", "firma kredyt", "discord", false), warnings);
        assertEquals(List.of("/firma kredyt"), e.requiresPlugins());
        final HelpCatalog.Category c = new HelpCatalog.Category("firmy", "Firmy", "BOOK", "gold", "", List.of("x"),
            List.of(), null, List.of(e));
        assertTrue(c.visibleEntries(t -> !t.startsWith("/"), p -> true).isEmpty(), "hidden before activation");
        assertEquals(List.of(e), c.visibleEntries(t -> true, p -> true));
        HelpCatalog.parseEntry("t", java.util.Map.of("command", "/x", "description", "d", "requires-command", "x <a>"), warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("x <a>")), warnings.toString());
        // Every prepared entry of the shipped catalog is gated in-game.
        for (final HelpCatalog.Category cat : catalog().categories().values()) for (final HelpCatalog.Entry entry : cat.entries())
            if (entry.command().matches("/(firma (kontrakt|hurt|kredyt|warsztat|robotnik)|kraj flaga|uscisk|toast|koronuj)\\b.*"))
                assertTrue(entry.requiresPlugins().stream().anyMatch(t -> t.startsWith("/")), entry.command());
    }

    @Test void guideSegmentsAndLoreWrapping() {
        assertEquals(List.of(new HelpSearch.Segment("Wpisz ", false), new HelpSearch.Segment("/menu", true),
            new HelpSearch.Segment(" teraz", false)), HelpSearch.segments("Wpisz `/menu` teraz"));
        assertEquals(List.of(new HelpSearch.Segment("bez `zamkniecia", false)), HelpSearch.segments("bez `zamkniecia"));
        assertEquals("Wpisz /menu teraz", HelpService.plain("Wpisz `/menu` teraz"));
        assertEquals(List.of("aaa bbb", "ccc"), HelpService.wrap("aaa bbb ccc", 7));
        assertEquals(List.of("bardzodlugieslowo"), HelpService.wrap("bardzodlugieslowo", 5));
        assertEquals(List.of(), HelpService.wrap("  ", 10));
        assertEquals(1, HelpService.pageCount(0, 28));
        assertEquals(1, HelpService.pageCount(28, 28));
        assertEquals(2, HelpService.pageCount(29, 28));
        assertEquals(28, HelpService.CONTENT.size());
    }

    private static void collect(final Component c, final List<ClickEvent> out) {
        if (c.clickEvent() != null) {
            out.add(c.clickEvent());
        }
        c.children().forEach(child -> collect(child, out));
    }
}
