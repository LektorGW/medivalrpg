// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.menu.book.BookBuilder;
import pl.medivalrpg.menu.book.BookPaginator;
import pl.medivalrpg.menu.gui.AutoLayout;
import pl.medivalrpg.menu.model.*;

class IntegrationMenusTest {
    @Test void chronicleAndRailPauseAreAdminOnlyAndGatedUntilCommandsExist() {
        // 2026-11-03: Discord buttons moved to their own admin submenu (admin had 28/28 auto slots).
        final var chronicle = button("admin-discord", "discord-kronika");
        assertEquals("discordbot.admin", chronicle.permission());
        assertEquals(new MenuAction.Command("discord admin kronika"), chronicle.action());
        assertTrue(chronicle.requiresPlugins().contains("/discord admin kronika"));
        final var help = yaml("/pomoc.yml").getMapList("categories.start.commands").stream()
            .filter(e -> "/discord admin kronika".equals(e.get("command"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, help.get("discord"));
        assertEquals("discordbot.admin", help.get("permission"));
        assertEquals("discord admin kronika", help.get("requires-command"));
        for (String key : List.of("postep", "wstrzymaj", "wznow")) {
            final var pause = button("kolej-infrastruktura", "siec-" + key);
            assertEquals("kolej.admin", pause.permission());
            assertTrue(pause.requiresPlugins().contains("/kolej siec " + key));
        }
    }
    private static YamlConfiguration yaml(String resource) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
            IntegrationMenusTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8));
    }
    private static MenuConfig config() { return MenusLoader.load(yaml("/menus.yml")); }
    /** Guide section by id in any chapter book, with the chapter's own requirements first. */
    private static BookDef.SectionDef guide(String id) {
        for (var book : config().books().values()) for (var s : book.sections()) if (s.id().equals(id)) {
            final List<String> reqs = new ArrayList<>(book.requiresPlugins());
            reqs.addAll(s.requiresPlugins());
            return new BookDef.SectionDef(s.id(), s.title(), reqs,
                s.permission() != null ? s.permission() : book.permission(), s.paragraphs());
        }
        throw new AssertionError("no guide section " + id);
    }
    private static ButtonDef button(String menu, String id) {
        return config().menu(menu).buttons().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }
    private static String command(ButtonDef b) {
        return switch (b.action()) {
            case MenuAction.Command c -> c.command();
            case MenuAction.Suggest s -> s.text().strip().replaceFirst("^/", "");
            case MenuAction.PickPlayer p -> p.command().strip();
            default -> "";
        };
    }
    private static Set<String> commands(MenuConfig cfg) {
        final Set<String> result = new HashSet<>();
        cfg.menus().values().forEach(m -> m.buttons().forEach(b -> result.add(command(b))));
        return result;
    }

    @Test void allMenusAndBooksHaveExistingLinksAndAreReachableFromMain() {
        final var cfg = config();
        assertTrue(cfg.warnings().isEmpty(), cfg.warnings().toString());
        final Set<String> visited = new HashSet<>();
        final Set<String> books = new HashSet<>();
        final var pending = new ArrayDeque<>(List.of("main"));
        while (!pending.isEmpty()) {
            final String id = pending.removeFirst();
            if (!visited.add(id)) continue;
            final var menu = cfg.menu(id);
            assertNotNull(menu, id);
            for (var b : menu.buttons()) {
                if (b.action() instanceof MenuAction.OpenMenu target) pending.add(target.menuId());
                if (b.action() instanceof MenuAction.OpenBook target) {
                    assertNotNull(cfg.book(target.bookId()), menu.id() + "/" + b.id());
                    books.add(target.bookId());
                }
            }
        }
        // Chapter books are reached from their index (/menu poradnik <rozdział>).
        for (final String id : List.copyOf(books)) for (final String chapter : cfg.book(id).chapters()) {
            assertNotNull(cfg.book(cfg.book(id).chapterBookId(chapter)), id + " → " + chapter);
            books.add(cfg.book(id).chapterBookId(chapter));
        }
        assertEquals(cfg.menus().keySet(), visited, "orphaned or cyclic-only menus");
        assertEquals(cfg.books().keySet(), books);
    }

    @Test void everyPluginAndCountryCombinationFitsWithoutSlotCollisions() {
        final var cfg = config();
        final Set<String> plugins = new HashSet<>();
        cfg.menus().values().forEach(m -> {
            plugins.addAll(m.requiresPlugins());
            m.buttons().forEach(b -> plugins.addAll(b.requiresPlugins()));
        });
        final List<Predicate<String>> scenarios = new ArrayList<>();
        scenarios.add(p -> true);
        scenarios.add(p -> false);
        for (final String missing : plugins) scenarios.add(p -> !p.equals(missing));
        for (var enabled : scenarios) for (boolean country : List.of(false, true))
            for (boolean returning : List.of(false, true)) for (var menu : cfg.menus().values()) {
                final var visible = menu.buttons().stream()
                    .filter(b -> AccessRules.visible(b, enabled, p -> true, country, returning)).toList();
                final var automatic = visible.stream().filter(ButtonDef::autoSlot).toList();
                final var slots = AutoLayout.assign(menu.autoSlots(), automatic.size());
                assertEquals(automatic.size(), slots.size(), "overflow in " + menu.id());
                final Set<Integer> used = new HashSet<>(menu.contentSlots());
                for (var b : visible) if (!b.autoSlot()) {
                    assertTrue(used.add(b.slot()), "fixed/content collision " + menu.id() + "/" + b.id());
                }
                for (int slot : slots) {
                    assertTrue(slot >= 0 && slot < menu.size());
                    assertTrue(used.add(slot), "auto collision in " + menu.id());
                }
            }
    }

    @Test void integrationCatalogCommandsAreRepresentedAsCommandsOrSafeSuggestions() throws Exception {
        final var actual = commands(config());
        // These are live contracts owned by the other modules; this test only reads them.
        for (String module : List.of("kraje", "ludnosc")) {
            final var catalog = YamlConfiguration.loadConfiguration(Path.of("..", module, "MENU-ADDITIONS.yml").toFile());
            for (Object value : catalog.getValues(true).values()) if (value instanceof String s && s.startsWith("/")) {
                final String prefix = s.split(" \\{")[0].substring(1);
                assertTrue(actual.contains(prefix), module + " missing: " + s);
            }
        }
        final var wl = YamlConfiguration.loadConfiguration(Path.of("..", "wladcy", "MENU-ADDITIONS.yml").toFile());
        for (Object value : wl.getValues(true).values()) if (value instanceof String s
            && (s.startsWith("command:") || s.startsWith("suggest:"))) {
            final var action = MenuAction.parse(s);
            final String text = action instanceof MenuAction.Command c ? c.command()
                : ((MenuAction.Suggest) action).text().strip().substring(1);
            assertTrue(actual.contains(text), s);
        }
        final var tournaments = YamlConfiguration.loadConfiguration(Path.of("..", "turnieje", "MENU-ADDITIONS.yml").toFile());
        for (Map<?, ?> entry : tournaments.getMapList("turnieje.entries"))
            assertTrue(actual.contains(entry.get("command")), entry.toString());
        assertTrue(actual.contains("panel wydajnosc"));
        final var routes = YamlConfiguration.loadConfiguration(Path.of("..", "szlaki", "MENU-ADDITIONS.yml").toFile());
        for (Object value : routes.getValues(true).values()) if (value instanceof String s
            && (s.startsWith("command:") || s.startsWith("suggest:"))) {
            final var action = MenuAction.parse(s);
            final String text = action instanceof MenuAction.Command c ? c.command()
                : ((MenuAction.Suggest) action).text().strip().replaceFirst("^/", "");
            assertTrue(actual.contains(text), s);
        }
    }

    /** Every module's MENU-ADDITIONS.yml (whatever its layout) keeps a button: nothing may get lost. */
    @Test void everyMenuAdditionsCatalogOfEveryModuleIsRepresented() throws Exception {
        final var actual = commands(config());
        final List<Path> catalogs = new ArrayList<>();
        try (var dirs = java.nio.file.Files.list(Path.of(".."))) {
            // Deployment snapshots are historical proposals, not the integration contract of a module.
            dirs.filter(d -> java.nio.file.Files.isRegularFile(d.resolve("build.gradle.kts")))
                .map(d -> d.getFileName().toString().equals("discordbot")
                    ? Path.of("..", "deploy-discordbot-20261029", "integration", "MENU-ADDITIONS.yml")
                    : d.resolve("MENU-ADDITIONS.yml"))
                .filter(java.nio.file.Files::isRegularFile).forEach(catalogs::add);
        }
        assertTrue(catalogs.size() >= 6, catalogs.toString());
        int checked = 0;
        for (final Path file : catalogs) {
            final var yaml = YamlConfiguration.loadConfiguration(file.toFile());
            final List<String> wanted = new ArrayList<>();
            for (final var e : yaml.getValues(true).entrySet()) {
                if (e.getValue() instanceof String s) {
                    if (s.startsWith("/")) wanted.add(s.split(" \\{")[0].substring(1));
                    else if (e.getKey().endsWith("action") || s.startsWith("command:") || s.startsWith("suggest:")) {
                        try {
                            final var action = MenuAction.parse(s);
                            if (action instanceof MenuAction.Command c) wanted.add(c.command());
                            if (action instanceof MenuAction.Suggest sg) wanted.add(sg.text().strip().replaceFirst("^/", ""));
                        } catch (final IllegalArgumentException ignored) {
                            // free text that only looks like an action
                        }
                    }
                } else if (e.getValue() instanceof List<?> list) {
                    // Help-style entries ("command: /pory admin podglad <pora>"): the literal path before arguments.
                    for (Object o : list) if (o instanceof Map<?, ?> m && m.get("command") instanceof String c)
                        wanted.add(c.replaceFirst("^/", "").split(" [<\\[{]")[0].strip());
                }
            }
            for (final String command : wanted) {
                assertTrue(actual.contains(command), file + " missing in menus.yml: " + command);
                checked++;
            }
        }
        assertTrue(checked > 60, "too few catalog commands checked: " + checked);
    }

    @Test void allRailwayNetworkAndTimetableActionsRequireCompanyManager() {
        final var cfg = config();
        for (String id : List.of("kolej-admin", "kolej-infrastruktura", "kolej-linie", "kolej-firma", "kolej-transport")) {
            final var menu = cfg.menu(id);
            assertEquals(List.of("Kolej"), menu.requiresPlugins());
            assertEquals("kolej.admin", menu.permission());
            assertFalse(AccessRules.allows(menu.requiresPlugins(), menu.permission(), p -> true, Set.of("menu.admin", "kolej.use")::contains));
            for (var b : menu.buttons()) if (!(b.action() instanceof MenuAction.OpenMenu)) {
                assertEquals("kolej.admin", b.permission(), id + "/" + b.id());
                assertTrue(b.requiresPlugins().contains("Kolej"));
            }
        }
        assertEquals("kolej.admin", button("kolej", "linie").permission(), "the current command restricts even listing lines");
        assertEquals("kolej.admin", button("admin", "kolej").permission());
    }

    @Test void locallyAssignedDriverCanReachTheRuntimeAuthorizationCheck() {
        final var driver = button("kolej", "prowadz");
        assertEquals(new MenuAction.Suggest("/kolej prowadz "), driver.action());
        assertTrue(AccessRules.visible(driver, p -> p.equals("Kolej"), Set.of("kolej.use")::contains, false, false));
        assertFalse(AccessRules.visible(driver, p -> false, p -> true, false, false));
        assertTrue(driver.lore().stream().anyMatch(s -> s.contains("lokalnego przydziału")));
        for (String id : List.of("wejdz", "wplac")) assertInstanceOf(MenuAction.Suggest.class, button("kolej", id).action());
        assertEquals(new MenuAction.Command("kolej zejdz"), button("kolej", "zejdz").action());
    }

    @Test void disablingPluginOrRevokingPermissionsInvalidatesAnAlreadyVisibleAction() {
        final var performance = button("admin", "wydajnosc");
        assertEquals(List.of("Panel"), performance.requiresPlugins());
        assertEquals("panel.health.alerts", performance.permission());
        assertEquals(new MenuAction.Command("panel wydajnosc"), performance.action());
        assertFalse(performance.keepOpen());
        final Set<String> enabled = new HashSet<>(Set.of("Panel"));
        final Set<String> permissions = new HashSet<>(Set.of("panel.health.alerts"));
        assertTrue(AccessRules.visible(performance, enabled::contains, permissions::contains, false, false));
        permissions.clear();
        assertFalse(AccessRules.visible(performance, enabled::contains, permissions::contains, false, false));
        permissions.add("panel.health.alerts");
        enabled.clear();
        assertFalse(AccessRules.visible(performance, enabled::contains, permissions::contains, false, false));
    }

    @Test void newMenusAndGuideSectionsDisappearWithMissingPluginOrPermission() {
        final Map<String, String> integrations = Map.of("kolej", "Kolej", "turnieje", "Turnieje", "wladcy", "Wladcy",
            "praca-sezony", "Ludnosc", "unia", "Kraje", "ambasada", "Kraje");
        for (var e : integrations.entrySet()) {
            final var m = config().menu(e.getKey());
            assertFalse(AccessRules.allows(m.requiresPlugins(), m.permission(), p -> !p.equals(e.getValue()), p -> true));
            assertFalse(AccessRules.allows(m.requiresPlugins(), m.permission(), p -> true, p -> false));
            assertTrue(AccessRules.allows(m.requiresPlugins(), m.permission(), p -> true, p -> true));
        }
        for (String id : List.of("kolej", "turnieje", "zlecenia", "praca-sezony", "unia-ambasady", "szlaki", "szlaki-projekt")) {
            final var section = guide(id);
            assertFalse(section.requiresPlugins().isEmpty(), id);
            assertFalse(AccessRules.allows(section.requiresPlugins(), section.permission(), p -> false, p -> true));
        }
    }

    @Test void everyBookPageAndMenuClickTargetsAnExistingDestination() {
        final var cfg = config();
        for (var book : cfg.books().values()) {
            final var result = BookBuilder.build(book, s -> true, s -> MiniMessage.miniMessage().deserialize(s),
                new BookPaginator.Layout(112, 14));
            assertFalse(result.truncated(), book.id() + " is over 100 pages");
            final List<ClickEvent> clicks = new ArrayList<>();
            result.pages().forEach(page -> collect(page, clicks));
            for (var click : clicks) {
                if (click.action() == ClickEvent.Action.CHANGE_PAGE) {
                    final int page = ((ClickEvent.Payload.Int) click.payload()).integer();
                    assertTrue(page >= 1 && page <= result.pages().size(), click.toString());
                }
                if (click.action() == ClickEvent.Action.RUN_COMMAND && click.payload() instanceof ClickEvent.Payload.Text text
                    && text.value().startsWith("/menu ")) {
                    final String target = text.value().substring(6);
                    final String chapterBook = target.replace(' ', '-');
                    assertTrue(cfg.menus().containsKey(target) || cfg.books().containsKey(target)
                        || (target.startsWith("poradnik ") && cfg.books().containsKey(chapterBook)), click.toString());
                }
            }
        }
    }
    private static void collect(Component c, List<ClickEvent> out) {
        if (c.clickEvent() != null) out.add(c.clickEvent());
        c.children().forEach(child -> collect(child, out));
    }

    @Test void fleetAndExistingPlayerActionsRemainAvailable() {
        final var fleet = config().menu("flota");
        final Set<String> actual = new HashSet<>();
        fleet.buttons().forEach(b -> actual.add(command(b)));
        assertTrue(actual.containsAll(List.of("armia flota lista", "armia flota wejdz", "armia flota steruj",
            "armia flota zejdz", "armia flota zaladuj", "armia flota desant", "kraj desant", "armia flota salwa",
            "armia flota zbuduj transportowiec", "armia flota zbuduj karawela", "armia ostrzal")));
        assertEquals(new MenuAction.OpenMenu("flota"), button("armia", "flota").action());
        assertTrue(commands(config()).containsAll(List.of("spawn", "powrot", "dom", "saldo", "glos")));
    }

    @Test void guideAndEpochHeaderStopAtRenaissanceAndKeepRailwayException() {
        final var header = String.join(" ", button("postep", "naglowek").lore());
        assertFalse(header.contains("Nowoczesność"));
        assertTrue(header.contains("Maksimum: Renesans"));
        final var section = guide("epoki");
        final String text = String.join(" ", section.paragraphs());
        assertTrue(text.contains("maksymalna epoka 2"));
        assertTrue(text.contains("dostępna od startu"));
        assertFalse(text.contains("4 epoki"));
        assertFalse(text.contains("internet"));
        assertEquals(List.of("Szlaki"), config().menu("szlaki").requiresPlugins());
    }

    @Test void publicRoutesExposeRealTransportActionsWithoutAnInventedUsePermission() {
        final var menu = config().menu("szlaki");
        assertNull(menu.permission(), "Szlaki has no szlaki.use permission");
        assertTrue(AccessRules.allows(menu.requiresPlugins(), menu.permission(), p -> true, p -> false));
        assertFalse(AccessRules.allows(menu.requiresPlugins(), menu.permission(), p -> false, p -> true));
        for (String id : List.of("lista", "pomoc", "trasa", "info", "napad", "przejecie", "naprawa")) {
            final var b = button("szlaki", id);
            assertTrue(AccessRules.visible(b, p -> p.equals("Szlaki"), p -> false, false, false), id);
            assertFalse(AccessRules.visible(b, p -> false, p -> true, true, true), id);
        }
        for (String id : List.of("trasa", "info", "napad", "przejecie", "naprawa")) {
            final var action = assertInstanceOf(MenuAction.Suggest.class, button("szlaki", id).action());
            assertTrue(action.text().startsWith("/szlaki "));
            assertTrue(action.text().endsWith(" "), "must leave room for route ID or UUID");
        }
        assertEquals(new MenuAction.Command("szlaki"), button("szlaki", "pomoc").action());
        assertEquals(new MenuAction.OpenMenu("szlaki"), button("main", "szlaki").action());
    }

    @Test void routeDesignAndApprovalRemainSeparateCapabilities() {
        final var project = config().menu("szlaki-projekt");
        final var admin = config().menu("szlaki-admin");
        final var declaration = YamlConfiguration.loadConfiguration(Path.of("..", "szlaki", "src", "main", "resources", "plugin.yml").toFile());
        assertTrue(declaration.contains("permissions." + project.permission()));
        assertTrue(declaration.contains("permissions." + admin.permission()));
        assertEquals("szlaki.zarzadzanie", project.permission());
        assertEquals("szlaki.admin", admin.permission());
        assertFalse(AccessRules.allows(admin.requiresPlugins(), admin.permission(), p -> true, Set.of("szlaki.zarzadzanie", "menu.admin")::contains));
        assertFalse(AccessRules.allows(project.requiresPlugins(), project.permission(), p -> true, Set.of("szlaki.admin", "menu.admin")::contains));
        for (var menu : List.of(project, admin)) for (var b : menu.buttons()) {
            if (!(b.action() instanceof MenuAction.OpenMenu)) {
                assertEquals(menu.permission(), b.permission(), b.id());
                assertInstanceOf(MenuAction.Suggest.class, b.action(), "IDs are required: " + b.id());
            }
        }
        assertEquals("szlaki.admin", button("admin", "szlaki").permission());
        assertTrue(button("szlaki-projekt", "nowa-drogowa").lore().stream().anyMatch(s -> s.contains("drogowa UUID_KRAJU")));
        assertTrue(button("szlaki-projekt", "nowa-morska").lore().stream().anyMatch(s -> s.contains("morska UUID_KRAJU")));
        assertEquals(new MenuAction.Suggest("/szlaki zatwierdz "), button("szlaki-admin", "zatwierdz").action());
        assertEquals(new MenuAction.Suggest("/szlaki audyt "), button("szlaki-admin", "audyt").action());
    }

    @Test void railwayYardReserveAndFreightCommandsKeepArgumentsAndManagerGate() {
        final Map<String, String> required = Map.of("wprowadz", "baza wprowadz", "wyprowadz", "baza wyprowadz",
            "odczep", "wagon odczep", "dolacz", "wagon dolacz", "magazyn", "ladunek magazyn",
            "zlec", "ladunek zlec", "regularnosc", "ladunek regularnosc");
        for (var e : required.entrySet()) {
            final var b = button("kolej-transport", e.getKey());
            assertEquals(new MenuAction.Suggest("/kolej " + e.getValue() + " "), b.action());
            assertFalse(AccessRules.visible(b, p -> true, Set.of("kolej.use", "kolej.maszynista", "menu.admin")::contains, true, false));
            assertTrue(AccessRules.visible(b, p -> true, Set.of("kolej.admin")::contains, false, false));
        }
        assertEquals(new MenuAction.Command("kolej ladunek status"), button("kolej-transport", "status").action());
        assertEquals(new MenuAction.OpenMenu("kolej-transport"), button("kolej-admin", "transport").action());
        assertTrue(button("kolej-transport", "wprowadz").lore().stream().anyMatch(s -> s.contains("STANOWISKO_1_DO_6")));
        assertTrue(button("kolej-transport", "regularnosc").lore().stream().anyMatch(s -> s.contains("MINUTY_LUB_0")));
    }

    @Test void caravanGuideDescribesPhysicalCargoWithoutAdvertisingPlayerCompanyCreation() {
        final var text = config().books().values().stream().flatMap(b -> b.sections().stream()).filter(s -> s.id().startsWith("szlaki"))
            .flatMap(s -> s.paragraphs().stream()).reduce("", (a, b) -> a + " " + b).toLowerCase(java.util.Locale.ROOT);
        assertTrue(text.contains("osłem") && text.contains("łodzią"));
        assertTrue(text.contains("skrzyni"));
        assertTrue(text.contains("30 s") && text.contains("10 s"));
        assertFalse(text.contains("załóż firmę"));
        assertFalse(text.contains("firmy graczy"));
        assertFalse(text.contains("własną firmę"));
    }

    @Test void railwayIntermediatePointsAreImmediateManagerActionsBeforeSurveying() {
        for (String id : List.of("tor-punkt", "tor-wyczysc")) {
            final var b = button("kolej-infrastruktura", id);
            assertEquals("kolej.admin", b.permission());
            assertEquals(new MenuAction.Command("kolej tor " + (id.equals("tor-punkt") ? "punkt" : "wyczysc")), b.action());
            assertFalse(AccessRules.visible(b, p -> true, Set.of("menu.admin", "kolej.use", "kolej.maszynista")::contains, true, true));
            assertTrue(AccessRules.visible(b, p -> p.equals("Kolej"), Set.of("kolej.admin")::contains, false, false));
        }
        final var point = button("kolej-infrastruktura", "tor-punkt");
        assertTrue(point.lore().stream().anyMatch(s -> s.contains("12 punktów")));
        assertTrue(point.lore().stream().anyMatch(s -> s.contains("tor planuj od do")));
    }

    @Test void railwayConsentSeparatesNpcManagerRequestsFromPlayerRulerDecisions() {
        final var npc = button("kolej-infrastruktura", "zgoda-npc");
        assertEquals("kolej.admin", npc.permission());
        assertEquals(new MenuAction.Suggest("/kolej zgoda "), npc.action());
        // rail-realistic3: the NPC ruler's AI decides (consent, consent for a fee, refusal); no alliance required.
        assertTrue(npc.lore().stream().anyMatch(s -> s.contains("[firma|budzet]")));
        assertTrue(npc.lore().stream().anyMatch(s -> s.contains("Sojusz nie jest wymagany")));
        assertFalse(AccessRules.visible(npc, p -> true, Set.of("kolej.use")::contains, true, false));
        assertFalse(AccessRules.visible(npc, p -> !p.equals("Armia"), p -> true, true, false));
        for (String answer : List.of("tak", "nie")) {
            final var b = button("kolej", "zgoda-" + answer);
            assertEquals("kolej.use", b.permission());
            assertEquals(Condition.HAS_COUNTRY, b.condition());
            assertEquals(new MenuAction.Command("kolej zgoda " + answer), b.action());
            assertTrue(AccessRules.visible(b, p -> p.equals("Kolej"), Set.of("kolej.use")::contains, true, false));
            assertFalse(AccessRules.visible(b, p -> true, p -> true, false, false));
            assertTrue(b.lore().stream().anyMatch(s -> s.contains("władc")), "Kolej validates the ruler at execution");
        }
    }
    @Test void physicalWorkplaceBindingsUseRealCommandsAndKeepAdministrativeAuditSeparate() {
        assertEquals(new MenuAction.Suggest("/ludnosc magazyn powiaz "),button("praca-sezony","powiaz-magazyn").action());
        assertEquals(new MenuAction.Command("ludnosc magazyn domyslny"),button("praca-sezony","domyslny-magazyn").action());
        assertEquals(new MenuAction.Command("ludnosc magazyn status"),button("praca-sezony","status-magazyn").action());
        for(String id:List.of("powiaz-magazyn","domyslny-magazyn","status-magazyn","pochodnie")) {
            final var b=button("praca-sezony",id);
            assertEquals(List.of("Ludnosc"),b.requiresPlugins()); assertEquals("ludnosc.use",b.permission());
            assertFalse(AccessRules.visible(b,p -> false,p -> true,true,false));
        }
        assertTrue(button("praca-sezony","powiaz-magazyn").lore().stream().anyMatch(s -> s.contains("Doradca")));
        assertInstanceOf(MenuAction.None.class,button("praca-sezony","pochodnie").action());
        assertEquals("ludnosc.admin",button("ludnosc-admin","magazyn-audyt").permission());
        assertEquals(new MenuAction.Suggest("/ludnosc magazyn audyt "),button("ludnosc-admin","magazyn-audyt").action());
    }

    // ------------------------------------------------------------------ 2026-10-29: release-20261029

    /** Literal command path of a help entry: words before the first argument, "a|b" alternatives expanded. */
    private static List<String> literalPaths(final String syntax) {
        List<String> paths = List.of("");
        for (final String word : syntax.strip().replaceFirst("^/", "").split("\\s+")) {
            if (word.startsWith("<") || word.startsWith("[") || word.startsWith("{")) break;
            final List<String> next = new ArrayList<>();
            for (final String prefix : paths) for (final String alt : word.split("\\|"))
                next.add(prefix.isEmpty() ? alt : prefix + " " + alt);
            paths = next;
        }
        return paths;
    }

    /** User requirement: every player function has a /menu entry. Every /pomoc command has a button
     *  (exact path or the same path with a fixed argument, e.g. "armia flota zbuduj karawela"). */
    @Test void everyPlayerHelpCommandHasAMenuButton() {
        final var actual = commands(config());
        final var help = YamlConfiguration.loadConfiguration(new InputStreamReader(
            IntegrationMenusTest.class.getResourceAsStream("/pomoc.yml"), StandardCharsets.UTF_8));
        // The menu itself, its book and the account commands; interactions, dances and emotes are generated
        // at runtime from the Interakcje config (menu types interactions / motions).
        final Set<String> outsideButtons = Set.of("register", "login", "menu", "pomoc", "menu poradnik",
            "discord odlacz", "discord pomoc",
            "przytul", "pocaluj", "trzymaj", "poglaszcz", "piatka", "lisc", "pomachaj", "uklon", "taniec", "emotka",
            "uscisk", "toast", "reweranse", "pasuj", "lenno", "koronuj");
        assertEquals(List.of("ludnosc kopia info", "ludnosc kopia buduj", "ludnosc kopia usun"),
            literalPaths("/ludnosc kopia info|buduj|usun <nazwa>"));
        int checked = 0;
        for (final String category : help.getConfigurationSection("categories").getKeys(false)) {
            for (final Map<?, ?> entry : help.getMapList("categories." + category + ".commands")) {
                if (entry.get("permission") != null) continue; // This test covers player help only.
                for (final String path : literalPaths(String.valueOf(entry.get("command")))) {
                    if (outsideButtons.contains(path)) continue;
                    assertTrue(actual.stream().anyMatch(c -> c.equals(path) || c.startsWith(path + " ")),
                        category + ": no /menu button for /" + path);
                    checked++;
                }
            }
        }
        assertTrue(checked > 200, "checked " + checked);
    }

    @Test void preparedFeaturesAreGatedAndKeptOffDiscordUntilActivation() {
        // Skarb Świata: the command exists only for WorldSteward's UUID; the permission merely hides the buttons.
        final var treasury = config().menu("skarbswiata-admin");
        assertEquals(List.of("Skarbiec"), treasury.requiresPlugins());
        assertEquals("skarbiec.admin", treasury.permission());
        assertEquals(new MenuAction.OpenMenu("skarbswiata-admin"), button("admin", "skarb-swiata").action());
        for (var b : treasury.buttons()) if (!(b.action() instanceof MenuAction.OpenMenu))
            assertEquals("skarbiec.admin", b.permission(), b.id());
        assertEquals(new MenuAction.Command("skarbswiata potwierdz"), button("skarbswiata-admin", "potwierdz").action());
        // Citizens' huts change the own country's settings: hidden without a country, rank checked by Ludnosc.
        for (String id : List.of("chatki-wl", "chatki-wyl", "chatki-limit")) {
            final var b = button("osada-obywatele", id);
            assertEquals(Condition.HAS_COUNTRY, b.condition(), id);
            assertFalse(AccessRules.visible(b, p -> true, p -> true, false, false), id);
            assertTrue(AccessRules.visible(b, p -> p.equals("Ludnosc"), Set.of("ludnosc.use")::contains, true, false), id);
        }
        assertEquals(new MenuAction.Suggest("/ludnosc obywatele chatki limit "), button("osada-obywatele", "chatki-limit").action());
        // Irreversible actions are only typed into chat, never executed with a single click.
        for (var b : List.of(button("kraj-zarzad", "rozwiaz"), button("kraj-zarzad", "opusc"), button("armia", "rozpusc"),
            button("kraj-dyplomacja", "kapitulacja"), button("skarbswiata-admin", "wdrozenie-wykonaj")))
            assertInstanceOf(MenuAction.Suggest.class, b.action(), b.id());
        // A host country's consent for the railway never requires an alliance (user decision, Kolej code).
        assertTrue(button("kolej", "zgoda-tak").lore().stream().anyMatch(s -> s.contains("sojusz nie jest wymagany")));
        // Entries gated with requires-command stay hidden in-game until the command exists. Batch 2 (active since
        // 2026-10-29 18:39) activated Firmy 2–3, chorągwie (/kraj flaga), taran and the new Interakcje: those entries
        // keep requires-command (harmless, the command exists) and are published on Discord (catalog 5d52b5c4).
        // Still prepared (deploy-flags-banners-20261029, Kraje 463b65a5 in plugins/update/): /kraj flaga sztandar|przedmiot
        // — gated AND off Discord until activation.
        final var help = YamlConfiguration.loadConfiguration(new InputStreamReader(
            IntegrationMenusTest.class.getResourceAsStream("/pomoc.yml"), StandardCharsets.UTF_8));
        // 2026-11-03: Firmy 7be75bc3 (start, tablica zleceń, zrzutka) is active since the 03.11 restart.
        final String activeGated = "/firma (kontrakt|hurt|kredyt|warsztat|robotnik|start|zlecenia|zrzutka)\\b.*"
            + "|/kraj flaga( (postaw|auto|historia|wycofaj|sztandar|przedmiot)\\b.*)?|/armia taranuj"
            + "|/(uscisk|toast|reweranse|pasuj|lenno|koronuj)\\b.*|/interakcje tytul\\b.*"
            + "|/discord (polacz|odlacz|status|pomoc)"
            // 2026-11-11: Ludnosc 4ead5867 (własne budynki) is active since the 04.11 restart.
            + "|/ludnosc budynek\\b.*";
        final String stillPrepared = "/discord admin uklad\\b.*";
        int prepared = 0;
        int gated = 0;
        for (final String category : help.getConfigurationSection("categories").getKeys(false)) {
            for (final Map<?, ?> entry : help.getMapList("categories." + category + ".commands")) {
                final String command = String.valueOf(entry.get("command"));
                if (command.matches(activeGated) || command.matches(stillPrepared)) {
                    assertNotNull(entry.get("requires-command"), "gated command without requires-command: " + command);
                }
                if (command.matches(stillPrepared)) {
                    assertEquals(Boolean.FALSE, entry.get("discord"), "prepared entry published on Discord: " + command);
                    prepared++;
                } else if (entry.get("requires-command") != null) {
                    // An unknown gated entry is treated as prepared: it must not reach Discord before activation.
                    if (!command.matches(activeGated))
                        assertEquals(Boolean.FALSE, entry.get("discord"), "prepared entry published on Discord: " + command);
                    else
                        assertNull(entry.get("discord"), "active entry hidden from Discord: " + command);
                    gated++;
                }
            }
        }
        assertEquals(3, prepared, "prepared Discord layout entries");
        assertTrue(gated >= 15, "active gated entries: " + gated);
        // Active since release-20261029 / night1: published.
        final var start = help.getMapList("categories.start.commands").stream()
            .filter(e -> "/pomoc".equals(e.get("command"))).findFirst().orElseThrow();
        assertNull(start.get("discord"), "/pomoc is active");
        assertNull(help.get("categories.firmy.discord"), "Firmy stage 1 is active since night1");
        // Prepared buttons carry requires-command: they stay hidden until the command exists on the server.
        for (var b : List.of(button("firmy", "kontrakty"), button("firmy", "hurt"), button("firmy", "kredyty"),
            button("firmy", "manufaktura"), button("kraj-zarzad", "flaga"), button("postac", "tytul"),
            button("admin-npc", "nk-wojny"), button("admin-npc", "npc-animacje"))) {
            assertTrue(b.requiresPlugins().stream().anyMatch(t -> t.startsWith("/")), b.id());
            assertFalse(AccessRules.visible(b, t -> !t.startsWith("/"), p -> true, true, false), b.id());
            assertTrue(AccessRules.visible(b, t -> true, p -> true, true, false), b.id());
        }
    }

    // ------------------------------------------------------------------ 2026-10-29: command registry of the isolated Paper

    /** Command registry exported by CommandCatalogProbe on the isolated Paper (verify-menu-20261029). */
    record Registry(Map<String, List<List<String>>> usages, Set<String> plugins) {
        static Registry load(final Path file) throws Exception {
            final var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            final Map<String, List<List<String>>> usages = new java.util.HashMap<>();
            for (var el : json.getAsJsonArray("roots")) {
                final var root = el.getAsJsonObject();
                final List<List<String>> paths = new ArrayList<>();
                for (var u : root.getAsJsonArray("executableUsagePaths"))
                    paths.add(List.of(u.getAsString().strip().replaceFirst("^/", "").split("\\s+")));
                usages.computeIfAbsent(root.get("name").getAsString().toLowerCase(java.util.Locale.ROOT),
                    k -> new ArrayList<>()).addAll(paths);
            }
            final Set<String> plugins = new HashSet<>();
            for (var el : json.getAsJsonObject("metadata").getAsJsonArray("plugins")) {
                final var p = el.getAsJsonObject();
                if (p.get("enabled").getAsBoolean()) plugins.add(p.get("name").getAsString());
            }
            return new Registry(usages, plugins);
        }

        private static boolean argument(final String token) {
            return token.startsWith("<") || token.startsWith("[") || token.startsWith("(");
        }

        /** A command with argument values ({@code ludnosc buduj huta_szkla}): argument tokens match any word. */
        boolean accepts(final String command) {
            final List<String> words = List.of(command.strip().toLowerCase(java.util.Locale.ROOT).split("\\s+"));
            for (final List<String> usage : this.usages.getOrDefault(words.getFirst(), List.of())) {
                boolean ok = true;
                for (int i = 1; i < words.size() && ok; i++) {
                    if (i >= usage.size()) { ok = argument(usage.getLast()) && usage.size() > 1; break; }
                    final String t = usage.get(i);
                    ok = argument(t) || java.util.Arrays.asList(t.split("\\|")).contains(words.get(i));
                }
                if (ok) return true;
            }
            return false;
        }

        /** A literal path ({@code requires-command}): literal words, or a root with a single greedy argument. */
        boolean hasPath(final String path) {
            final List<String> words = List.of(path.strip().toLowerCase(java.util.Locale.ROOT).split("\\s+"));
            final List<List<String>> all = this.usages.getOrDefault(words.getFirst(), List.of());
            if (all.isEmpty()) return false;
            if (all.stream().allMatch(u -> u.size() == 1 || (u.size() == 2 && argument(u.get(1))))) return true;
            return all.stream().anyMatch(u -> u.size() >= words.size()
                && java.util.stream.IntStream.range(1, words.size()).allMatch(i -> u.get(i).equals(words.get(i))));
        }

        boolean met(final String token) {
            return token.startsWith("/") ? this.hasPath(token.substring(1)) : this.plugins.contains(token);
        }
    }

    private static final Path REGISTRY_ACTIVE = Path.of("..", "verify-menu-20261029", "catalog-active-raw.json");
    private static final Path REGISTRY_PREPARED = Path.of("..", "verify-menu-20261029", "catalog-prepared-raw.json");
    /** Registry of the batch-2 joint test (production jars after batch 2, verify-batch2-20261029/run.py, phase B1). */
    private static final Path REGISTRY_BATCH2 = Path.of("..", "verify-batch2-20261029", "catalog-batch2-raw.json");

    /** Kraje with country standards (deploy-flags-banners-20261029): /kraj tree of the prepared jar. */
    private static final Path REGISTRY_BANNERS = Path.of("..", "verify-flags-banners-20261029", "catalog-banners-raw.json");

    /** Prepared registry = verify-menu prepared jars plus, when present, the batch-2 jars and the standards jar of Kraje. */
    private static Registry preparedRegistry() throws Exception {
        final List<Registry> all = new ArrayList<>(List.of(Registry.load(REGISTRY_PREPARED)));
        for (Path extra : List.of(REGISTRY_BATCH2, REGISTRY_BANNERS, Path.of("src", "test", "resources", "catalog-discordbot-kronika-20261030.json"),
                                  REGISTRY_PROD_20261103, REGISTRY_BUDYNKI_20261103, REGISTRY_SAMOUCZEK_20261111))
            if (java.nio.file.Files.isRegularFile(extra)) all.add(Registry.load(extra));
        final Map<String, List<List<String>>> usages = new java.util.HashMap<>();
        final Set<String> plugins = new HashSet<>();
        for (Registry r : all) {
            r.usages().forEach((k, v) -> usages.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
            plugins.addAll(r.plugins());
        }
        // DiscordBot 4e10d027: public Brigadier literals verified in the deployed JAR.
        usages.putIfAbsent("discord", List.of(List.of("discord"), List.of("discord", "pomoc"),
            List.of("discord", "polacz"), List.of("discord", "odlacz"), List.of("discord", "status")));
        plugins.add("DiscordBot");
        // Menu's own /samouczek (TutorialCommand, this module): literals of the Brigadier tree.
        usages.putIfAbsent("samouczek", List.of(List.of("samouczek"), List.of("samouczek", "ukryj"),
            List.of("samouczek", "przypominaj")));
        return new Registry(usages, plugins);
    }
    /** Runtime-generated or placeholder commands that the registry cannot list as such. */
    private static final Set<String> NOT_IN_REGISTRY = Set.of();

    private static List<String> buttonCommands(final MenuConfig cfg, final java.util.function.BiPredicate<MenuDef, ButtonDef> filter) {
        final List<String> out = new ArrayList<>();
        for (var menu : cfg.menus().values()) for (var b : menu.buttons()) {
            final String c = command(b).replace("<player>", "Gracz").replace("<country>", "Kraj").strip();
            if (!c.isEmpty() && filter.test(menu, b)) out.add(menu.id() + "/" + b.id() + " → " + c);
        }
        return out;
    }

    /** User requirement: every button points to an existing command (after activation of the prepared jars). */
    @Test void everyButtonCommandExistsInTheRegistryOfActiveAndPreparedJars() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(REGISTRY_PREPARED), "no registry");
        final Registry prepared = preparedRegistry();
        final List<String> missing = buttonCommands(config(), (m, b) -> true).stream()
            .filter(line -> !prepared.accepts(line.substring(line.indexOf("→ ") + 2)))
            .filter(line -> NOT_IN_REGISTRY.stream().noneMatch(line::endsWith)).toList();
        assertEquals(List.of(), missing);
    }

    /** One menus.yml before and after activation: a button whose command does not exist on production is hidden. */
    @Test void buttonsAndHelpEntriesOfCommandsMissingOnProductionAreHidden() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(REGISTRY_ACTIVE), "no registry");
        final Registry active = Registry.load(REGISTRY_ACTIVE);
        final MenuConfig cfg = config();
        final List<String> shown = buttonCommands(cfg, (m, b) ->
            AccessRules.allows(m.requiresPlugins(), m.permission(), active::met, p -> true)
                && AccessRules.visible(b, active::met, p -> true, true, true)).stream()
            .filter(line -> !active.accepts(line.substring(line.indexOf("→ ") + 2))).toList();
        assertEquals(List.of(), shown, "visible on production but the command does not exist there");
        final var help = pl.medivalrpg.menu.help.HelpCatalog.load(yaml("/pomoc.yml"));
        final List<String> helpShown = new ArrayList<>();
        for (var c : help.visibleCategories(active::met, p -> true)) for (var e : c.visibleEntries(active::met, p -> true)) {
            final String path = String.join(" ", literalPaths(e.command()).getFirst().split(" "));
            if (!active.accepts(path) && !Set.of("register", "login").contains(path)) helpShown.add(c.id() + ": " + e.command());
        }
        assertEquals(List.of(), helpShown, "/pomoc shows a command that does not exist on production");
        // And the gate opens with the prepared jars (otherwise the button would never appear).
        if (java.nio.file.Files.isRegularFile(REGISTRY_PREPARED)) {
            final Registry prepared = preparedRegistry();
            final List<String> never = new ArrayList<>();
            for (var menu : cfg.menus().values()) for (var b : menu.buttons())
                for (String t : b.requiresPlugins()) if (t.startsWith("/") && !prepared.met(t)) never.add(menu.id() + "/" + b.id() + " " + t);
            assertEquals(List.of(), never, "requires-command never satisfied by the prepared jars");
        }
    }

    /** Batch 2 (one restart): with the batch-2 jars every VISIBLE button and /pomoc entry points to an existing command. */
    @Test void batch2NoDeadButtonsOrHelpEntries() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(REGISTRY_BATCH2), "no batch-2 registry");
        final Registry b2 = Registry.load(REGISTRY_BATCH2);
        final MenuConfig cfg = config();
        final List<String> dead = buttonCommands(cfg, (m, b) ->
            AccessRules.allows(m.requiresPlugins(), m.permission(), b2::met, p -> true)
                && AccessRules.visible(b, b2::met, p -> true, true, true)).stream()
            .filter(line -> !b2.accepts(line.substring(line.indexOf("→ ") + 2))).toList();
        assertEquals(List.of(), dead, "visible after batch 2 but the command does not exist");
        final var help = pl.medivalrpg.menu.help.HelpCatalog.load(yaml("/pomoc.yml"));
        final List<String> helpDead = new ArrayList<>();
        int shown = 0;
        for (var c : help.visibleCategories(b2::met, p -> true)) for (var e : c.visibleEntries(b2::met, p -> true)) {
            shown++;
            final String path = String.join(" ", literalPaths(e.command()).getFirst().split(" "));
            if (!b2.accepts(path)) helpDead.add(c.id() + ": " + e.command());
        }
        assertEquals(List.of(), helpDead, "/pomoc shows a command that does not exist after batch 2");
        assertTrue(shown > 150, "too few help entries checked: " + shown);
        // Batch 2 activates Strażnik, chorągwie, taran, Firmy 2–3 and the new Interakcje: their gates must open.
        for (String gate : List.of("/zmienhaslo", "/kraj flaga", "/armia taranuj", "/firma kontrakt", "/firma kredyt",
                                   "/firma warsztat", "/firma robotnik", "/uscisk", "/koronuj", "/interakcje tytul", "/nk wojny"))
            assertTrue(b2.met(gate), "batch-2 gate closed: " + gate);
    }

    /** Country standards (deploy-flags-banners-20261029): one menus.yml for Kraje 9240f7b2 (active, /kraj tree as in
     *  batch 2) and Kraje 463b65a5 (plugins/update/). The 3 new buttons are hidden with the old Kraje, shown and
     *  pointing to existing commands with the new one; the rest of kraj-flaga is unchanged in both. */
    @Test void flagStandardButtonsAppearOnlyWithTheNewKraje() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(REGISTRY_BATCH2)
            && java.nio.file.Files.isRegularFile(REGISTRY_BANNERS), "no registries");
        final Registry before = Registry.load(REGISTRY_BATCH2);
        final Registry banners = Registry.load(REGISTRY_BANNERS);
        // The banners registry lists only /kraj of the new jar: everything else as on production.
        final Registry after = new Registry(new java.util.HashMap<>(before.usages()), before.plugins());
        after.usages().put("kraj", banners.usages().get("kraj"));
        final MenuDef menu = config().menu("kraj-flaga");
        assertTrue(AccessRules.allows(menu.requiresPlugins(), menu.permission(), before::met, p -> true));
        final Set<String> fresh = Set.of("sztandar", "sztandar-podglad", "przedmiot");
        for (var b : menu.buttons()) {
            final String c = command(b).strip();
            final boolean shownBefore = AccessRules.visible(b, before::met, p -> true, true, true);
            final boolean shownAfter = AccessRules.visible(b, after::met, p -> true, true, true);
            if (fresh.contains(b.id())) {
                assertFalse(shownBefore, "new button visible with Kraje 9240f7b2: " + b.id());
                assertTrue(shownAfter, "new button hidden with Kraje 463b65a5: " + b.id());
                // (before.accepts() is not checked: the registry's catch-all "/kraj <komenda>" accepts any words.)
                assertFalse(before.hasPath(b.requiresPlugins().stream().filter(t -> t.startsWith("/"))
                    .findFirst().orElseThrow().substring(1)), b.id());
            } else {
                assertEquals(shownBefore, shownAfter, b.id());
                if (shownBefore && !c.isEmpty()) assertTrue(before.accepts(c), "dead button with Kraje 9240f7b2: " + b.id());
            }
            if (shownAfter && !c.isEmpty()) assertTrue(after.accepts(c), "dead button with Kraje 463b65a5: " + b.id());
        }
        assertEquals(fresh, menu.buttons().stream().map(ButtonDef::id).filter(fresh::contains)
            .collect(java.util.stream.Collectors.toSet()));
        // Layout: every auto button of the full (new) set fits in auto-slots, « Wróć stays outside them.
        assertEquals(4, menu.rows());
    }

    // ------------------------------------------------------------------ 2026-11-03: zaległe MENU-ADDITIONS (deploy-menu-20261103)

    /** Registry of the isolated Paper with the production jar set of 03.11 (Ludnosc 019cec1e, Menu ce56d9db),
     *  exported by verify-menu-20261103 (phase A). */
    private static final Path REGISTRY_PROD_20261103 = Path.of("src", "test", "resources", "catalog-production-20261103.json");
    /** The same set with Ludnosc 4ead5867 from plugins/update (własne budynki), phase B. */
    private static final Path REGISTRY_BUDYNKI_20261103 = Path.of("src", "test", "resources", "catalog-budynki-20261103.json");

    /** Pory, Firmy (fix + warsztat), desant NPC, DiscordBot server-info and Ludnosc własne budynki: every pending
     *  catalog entry has a button, irreversible actions are only suggested, prepared ones are gated. */
    @Test void pendingAdditions20261103AreMergedAndGated() {
        assertEquals(new MenuAction.Command("pory"), button("swiat", "pory-roku").action());
        assertTrue(button("swiat", "pory-roku").requiresPlugins().contains("Pory"));
        // Admin was full (28/28): Discord moved to its own submenu.
        assertEquals(new MenuAction.OpenMenu("admin-discord"), button("admin", "discord").action());
        for (String gone : List.of("discord-status", "discord-layout", "discord-kronika"))
            assertTrue(config().menu("admin").buttons().stream().noneMatch(b -> b.id().equals(gone)), gone);
        final var content = button("admin-discord", "discord-tresci");
        assertEquals(new MenuAction.Command("discord admin tresci odswiez"), content.action());
        assertEquals("discordbot.admin", content.permission());
        assertTrue(content.requiresPlugins().contains("/discord admin tresci odswiez"));
        assertInstanceOf(MenuAction.Suggest.class, button("admin-discord", "discord-zastosuj").action());
        for (var b : config().menu("admin-discord").buttons()) if (!(b.action() instanceof MenuAction.OpenMenu))
            assertEquals("discordbot.admin", b.permission(), b.id());
        assertEquals(new MenuAction.Suggest("/pory admin podglad "), button("ludnosc-admin", "pory-podglad").action());
        assertEquals(new MenuAction.Command("pory admin status"), button("ludnosc-admin", "pory-status").action());
        assertEquals("pory.admin", button("ludnosc-admin", "pory-status").permission());
        final var landing = button("admin-npc", "nk-desant");
        assertEquals(new MenuAction.Suggest("/nk desant "), landing.action());
        assertEquals("armia.admin", landing.permission());
        assertTrue(landing.requiresPlugins().contains("/nk desant"));
        assertEquals(new MenuAction.Command("firma admin zrzutki"), button("admin-gospodarka", "firmy-zrzutki").action());
        assertEquals("firmy.admin", button("admin-gospodarka", "firmy-zrzutki").permission());
        // Firmy: /firma zaloz without arguments is now the founding guide (players sent the bare suggestion before).
        assertEquals(new MenuAction.Command("firma zaloz"), button("firmy", "zaloz").action());
        assertEquals(new MenuAction.Command("firma start"), button("firmy", "start").action());
        assertEquals(new MenuAction.Command("firma zlecenia"), button("firmy", "zlecenia").action());
        assertEquals(new MenuAction.OpenMenu("firmy-zrzutka"), button("firmy", "zrzutka").action());
        assertTrue(config().menu("firmy-zrzutka").requiresPlugins().contains("/firma zrzutka"));
        assertInstanceOf(MenuAction.Suggest.class, button("firmy-zrzutka", "wycofaj").action());
        assertTrue(button("firmy", "manufaktura").lore().stream().anyMatch(s -> s.contains("Cech: 1 warsztat")));
        // Ludnosc własne budynki: hidden until Ludnosc 4ead5867 registers /ludnosc budynek.
        assertEquals(new MenuAction.OpenMenu("osada-wlasne"), button("osada", "wlasne-budynki").action());
        assertTrue(button("osada", "wlasne-budynki").requiresPlugins().contains("/ludnosc budynek"));
        assertTrue(config().menu("osada-wlasne").requiresPlugins().contains("/ludnosc budynek"));
        assertEquals(new MenuAction.Suggest("/ludnosc budynek zglos "), button("osada-wlasne", "budynek-zglos").action());
        assertEquals(new MenuAction.Suggest("/ludnosc budynek usun"), button("osada-wlasne", "budynek-usun").action());
        assertEquals(new MenuAction.Command("ludnosc budynek typy"), button("osada-wlasne", "budynek-typy").action());
        assertEquals(new MenuAction.Command("ludnosc budynek info"), button("osada-wlasne", "budynek-info").action());
        assertEquals(new MenuAction.Command("ludnosc kopia pos1"), button("osada-wlasne", "pos1").action());
        assertEquals(new MenuAction.Command("ludnosc kopia pos2"), button("osada-wlasne", "pos2").action());
        assertEquals(new MenuAction.OpenBook("poradnik-wlasne-budynki"), button("osada-wlasne", "poradnik").action());
        // /ludnosc buduj anuluj (Ludnosc 4ead5867) only drops the preview; a site needs an id or "potwierdz".
        final var cancel = button("osada-budowa", "anuluj");
        assertEquals(new MenuAction.Command("ludnosc buduj anuluj"), cancel.action());
        assertFalse(cancel.name().contains("budowę"), cancel.name());
        assertEquals(new MenuAction.Suggest("/ludnosc buduj anuluj "), button("osada-budowa", "anuluj-plac").action());
        // Poradnik: new chapter right after Ludność, gated like the menu.
        final var guide = config().book("poradnik");
        assertEquals(guide.chapters().indexOf("ludnosc") + 1, guide.chapters().indexOf("wlasne-budynki"));
        final var chapter = config().book(guide.chapterBookId("wlasne-budynki"));
        assertNotNull(chapter);
        assertTrue(chapter.requiresPlugins().containsAll(List.of("Ludnosc", "/ludnosc budynek")), chapter.requiresPlugins().toString());
        assertEquals(List.of("zgloszenie", "typy", "zasady"), chapter.sections().stream().map(BookDef.SectionDef::id).toList());
        final var building = guide("budowa").paragraphs();
        assertTrue(building.stream().noneMatch(s -> s.contains("obroc|potwierdz|anuluj")), building.toString());
        assertTrue(building.stream().anyMatch(s -> s.contains("porzuca tylko podgląd")), building.toString());
        // /pomoc entries
        final var help = yaml("/pomoc.yml");
        final Map<String, Map<?, ?>> entries = new java.util.HashMap<>();
        for (String category : help.getConfigurationSection("categories").getKeys(false))
            for (Map<?, ?> e : help.getMapList("categories." + category + ".commands")) entries.put(String.valueOf(e.get("command")), e);
        for (String c : List.of("/ludnosc budynek zglos <typ> [nazwa]", "/ludnosc budynek typy", "/ludnosc budynek info", "/ludnosc budynek usun")) {
            assertEquals("ludnosc budynek", entries.get(c).get("requires-command"), c);
            // Active on production since 2026-11-04 (Ludnosc 4ead5867): published on Discord from 2026-11-11.
            assertNull(entries.get(c).get("discord"), "active entry still kept off Discord: " + c);
        }
        assertEquals("Pory", entries.get("/pory").get("requires-plugin"));
        for (String c : List.of("/discord admin tresci odswiez", "/discord admin synchronizuj")) {
            assertEquals("discordbot.admin", entries.get(c).get("permission"), c);
            assertEquals(Boolean.FALSE, entries.get(c).get("discord"), c);
        }
        // The help catalog is for players: admin-only commands (/nk desant, /pory admin, /firma admin) live only in /menu admin.
        for (String c : entries.keySet()) assertFalse(c.startsWith("/nk ") || c.startsWith("/pory admin") || c.startsWith("/firma admin"), c);
        assertTrue(entries.containsKey("/ludnosc buduj anuluj [id|potwierdz]"));
        assertFalse(entries.containsKey("/ludnosc buduj anuluj [id]"));
    }

    /** Paper 26.3 renamed CHAIN to IRON_CHAIN: an unknown material is shown as BARRIER with a warning (found on the
     *  isolated Paper 2026-11-03 — production showed BARRIER for /menu Discord and Wasale). */
    @Test void everyMaterialExistsInPaper263() {
        final List<String> unknown = new ArrayList<>();
        for (var menu : config().menus().values()) for (var b : menu.buttons())
            if (b.material() != null && org.bukkit.Material.getMaterial(b.material()) == null) unknown.add(menu.id() + "/" + b.id() + " " + b.material());
        assertEquals(List.of(), unknown);
    }

    /** Isolated Paper registries of 03.11: no dead visible button or /pomoc entry with the production set (Ludnosc 019cec1e)
     *  nor after the restart with Ludnosc 4ead5867; "Własne budynki" appear only with the new Ludnosc. */
    @Test void registries20261103NoDeadButtonsAndOwnBuildingsOnlyWithNewLudnosc() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(REGISTRY_PROD_20261103)
            && java.nio.file.Files.isRegularFile(REGISTRY_BUDYNKI_20261103), "no 2026-11-03 registries");
        final MenuConfig cfg = config();
        final var help = pl.medivalrpg.menu.help.HelpCatalog.load(yaml("/pomoc.yml"));
        for (final boolean newLudnosc : List.of(false, true)) {
            final Registry r = Registry.load(newLudnosc ? REGISTRY_BUDYNKI_20261103 : REGISTRY_PROD_20261103);
            final String label = newLudnosc ? "Ludnosc 4ead5867" : "Ludnosc 019cec1e";
            final List<String> dead = buttonCommands(cfg, (m, b) ->
                AccessRules.allows(m.requiresPlugins(), m.permission(), r::met, p -> true)
                    && AccessRules.visible(b, r::met, p -> true, true, true)).stream()
                .filter(line -> !r.accepts(line.substring(line.indexOf("→ ") + 2))).toList();
            assertEquals(List.of(), dead, "visible but the command does not exist (" + label + ")");
            final List<String> helpDead = new ArrayList<>();
            for (var c : help.visibleCategories(r::met, p -> true)) for (var e : c.visibleEntries(r::met, p -> true)) {
                final String path = String.join(" ", literalPaths(e.command()).getFirst().split(" "));
                if (!r.accepts(path) && !Set.of("register", "login").contains(path)) helpDead.add(c.id() + ": " + e.command());
            }
            assertEquals(List.of(), helpDead, "/pomoc shows a missing command (" + label + ")");
            assertEquals(newLudnosc, r.met("/ludnosc budynek"), label);
            assertEquals(newLudnosc, AccessRules.visible(button("osada", "wlasne-budynki"), r::met, p -> true, true, true), label);
            final var own = cfg.menu("osada-wlasne");
            assertEquals(newLudnosc, AccessRules.allows(own.requiresPlugins(), own.permission(), r::met, p -> true), label);
            final var chapter = cfg.book("poradnik-wlasne-budynki");
            assertEquals(newLudnosc, AccessRules.allows(chapter.requiresPlugins(), chapter.permission(), r::met, p -> true), label);
            for (String gate : List.of("/nk desant", "/firma start", "/firma zlecenia", "/firma zrzutka", "/firma admin zrzutki",
                                       "/discord admin tresci odswiez", "/discord admin synchronizuj", "Pory"))
                assertTrue(r.met(gate), label + " gate closed: " + gate);
        }
    }

    // ------------------------------------------------------------------ samouczek (2026-11-11)

    /** Registry of the isolated Paper of 2026-11-11: production jars + the Menu with /samouczek. */
    private static final Path REGISTRY_SAMOUCZEK_20261111 = Path.of("src", "test", "resources", "catalog-samouczek-20261111.json");

    private static final List<String> TUTORIAL_STEPS = List.of("pieniadze", "kraj", "ziemia", "mieszkania", "jedzenie",
        "budowa", "produkcja", "wlasne", "armia", "pomoc");

    /** Owner's decision 2026-11-11: a step-by-step tutorial, prominent in /menu, with the Poradnik chapter and /pomoc. */
    @Test void tutorialIsFirstInMainMenuAndHasTenOrderedStepsWithRealActions() {
        final MenuConfig cfg = config();
        final MenuDef main = cfg.menu("main");
        // First auto button of the main menu = top-left slot; always visible (no plugin, permission or command gate).
        final ButtonDef entry = main.buttons().stream().filter(ButtonDef::autoSlot).findFirst().orElseThrow();
        assertEquals("samouczek", entry.id());
        assertEquals(new MenuAction.OpenMenu("samouczek"), entry.action());
        assertTrue(entry.glow());
        assertTrue(entry.requiresPlugins().isEmpty() && entry.permission() == null && entry.condition() == Condition.NONE);

        final MenuDef menu = cfg.menu("samouczek");
        assertNotNull(menu);
        assertTrue(menu.requiresPlugins().isEmpty() && menu.permission() == null, "the tutorial menu must open for everyone");
        final List<ButtonDef> steps = menu.buttons().stream()
            .filter(b -> b.progress() != null && !ButtonDef.PROGRESS_SUMMARY.equals(b.progress())).toList();
        assertEquals(TUTORIAL_STEPS, steps.stream().map(ButtonDef::progress).toList());
        assertEquals(steps.size(), menu.autoSlots().size(), "every step has its own slot");
        for (int i = 0; i < steps.size(); i++) {
            final ButtonDef b = steps.get(i);
            assertTrue(b.autoSlot(), b.id());
            assertTrue(b.name().contains((i + 1) + ". "), "step number in the name: " + b.name());
            final long text = b.lore().stream().filter(l -> l.startsWith("<gray>") || l.startsWith("<white>") || l.startsWith("<green>")).count();
            assertTrue(text >= 2 && b.lore().size() <= 10, b.id() + " lore " + b.lore().size());
            assertFalse(b.action() instanceof MenuAction.None, b.id() + " must do something on click");
            assertTrue(b.lore().getLast().contains("Kliknij"), b.id());
            for (String line : b.lore())   // keeps the tooltip narrow
                assertTrue(MiniMessage.miniMessage().stripTags(line.replaceAll("<cfg:[^>]*>", "500")).length() <= 48, b.id() + ": " + line);
        }
        assertEquals(ButtonDef.PROGRESS_SUMMARY, button("samouczek", "naglowek").progress());
        // The click of each step: the right command, a safe suggestion (nothing is bought by a click) or the submenu.
        assertEquals(new MenuAction.Command("skarbiec kupcy"), button("samouczek", "krok-pieniadze").action());
        assertEquals(new MenuAction.OpenMenu("kraj"), button("samouczek", "krok-kraj").action());
        assertEquals(new MenuAction.Suggest("/kraj zajmij"), button("samouczek", "krok-ziemia").action());
        assertEquals(new MenuAction.Suggest("/ludnosc buduj chata"), button("samouczek", "krok-mieszkania").action());
        assertEquals(new MenuAction.Suggest("/ludnosc buduj pole"), button("samouczek", "krok-jedzenie").action());
        assertEquals(new MenuAction.OpenMenu("osada-budowa"), button("samouczek", "krok-budowa").action());
        assertEquals(new MenuAction.Command("ludnosc typy"), button("samouczek", "krok-produkcja").action());
        assertEquals(new MenuAction.OpenMenu("osada-wlasne"), button("samouczek", "krok-wlasne").action());
        assertEquals(new MenuAction.OpenMenu("armia"), button("samouczek", "krok-armia").action());
        assertEquals(new MenuAction.OpenMenu("pomoc"), button("samouczek", "krok-pomoc").action());
        // A step is never shown when its target cannot open: at least the plugin, permission and command gates of the target menu.
        for (ButtonDef b : steps) if (b.action() instanceof MenuAction.OpenMenu(String target)) {
            final MenuDef t = cfg.menu(target);
            assertTrue(b.requiresPlugins().containsAll(t.requiresPlugins()), b.id() + " " + t.requiresPlugins());
            assertTrue(t.permission() == null || t.permission().equals(b.permission()), b.id() + " " + t.permission());
        }
        // Reminders off / on: only with the Menu that has /samouczek (the file also runs with the older jar).
        assertEquals(new MenuAction.Command("samouczek ukryj"), button("samouczek", "ukryj").action());
        assertEquals(List.of("/samouczek ukryj"), button("samouczek", "ukryj").requiresPlugins());
        assertEquals(new MenuAction.Command("samouczek przypominaj"), button("samouczek", "przypominaj").action());
        assertEquals(List.of("/samouczek przypominaj"), button("samouczek", "przypominaj").requiresPlugins());
        assertEquals(new MenuAction.OpenMenu("main"), button("samouczek", "wstecz").action());
        assertEquals(new MenuAction.OpenBook("poradnik-samouczek"), button("samouczek", "poradnik").action());
        // Building descriptions are being rewritten in Ludnosc: the tutorial links to /ludnosc typy and copies no prices.
        for (ButtonDef b : steps) for (String line : b.lore())
            assertFalse(line.matches(".*\\d+ ?(zł|sr)\\b.*") && !line.contains("1 zł = 10 sr"), b.id() + " copies a price: " + line);
        assertTrue(button("samouczek", "krok-produkcja").lore().stream().anyMatch(l -> l.contains("/ludnosc typy")));
    }

    @Test void tutorialChapterOpensThePoradnikAndHelpListsTheCommand() {
        final MenuConfig cfg = config();
        final BookDef guide = cfg.book("poradnik");
        assertEquals("samouczek", guide.chapters().getFirst(), "the tutorial is the first chapter");
        final BookDef chapter = cfg.book(guide.chapterBookId("samouczek"));
        assertNotNull(chapter);
        assertTrue(chapter.requiresPlugins().isEmpty() && chapter.permission() == null);
        // Section ids carry the prefix "krok-": the plain ids (budowa, armia, kraj…) belong to the older chapters.
        assertEquals(TUTORIAL_STEPS.stream().map(s -> "krok-" + s).toList(), chapter.sections().stream().map(BookDef.SectionDef::id).toList());
        final Map<String, List<String>> gates = Map.of("pieniadze", List.of("Skarbiec"), "kraj", List.of("Kraje"),
            "ziemia", List.of("Kraje"), "wlasne", List.of("Ludnosc", "/ludnosc budynek"), "armia", List.of("Armia"),
            "pomoc", List.of());
        for (BookDef.SectionDef sec : chapter.sections())
            assertEquals(gates.getOrDefault(sec.id().substring("krok-".length()), List.of("Ludnosc")), sec.requiresPlugins(), sec.id());
        // /pomoc: the entry and the first-steps guide line (4th: Discord publishes only the first three).
        final var help = yaml("/pomoc.yml");
        final Map<String, Map<?, ?>> entries = new java.util.HashMap<>();
        for (Map<?, ?> e : help.getMapList("categories.start.commands")) entries.put(String.valueOf(e.get("command")), e);
        assertEquals("samouczek", entries.get("/samouczek").get("requires-command"));
        assertEquals("run", entries.get("/samouczek").get("click"));
        // Prepared, not active yet: off Discord until the restart (rule of preparedFeaturesAreGatedAndKeptOffDiscordUntilActivation).
        for (String sub : List.of("/samouczek", "/samouczek ukryj", "/samouczek przypominaj")) {
            assertEquals(sub.substring(1), entries.get(sub).get("requires-command"));
            assertEquals(Boolean.FALSE, entries.get(sub).get("discord"), sub);
        }
        assertTrue(help.getStringList("categories.start.guide").get(3).contains("`/samouczek`"));
    }

    /** One menus.yml for the active Menu 0e1f5ac9 (no /samouczek: file swapped before the restart) and the new one. */
    @Test void tutorialWorksWithTheActiveJarAndReminderButtonsAppearOnlyWithTheNewOne() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(REGISTRY_BUDYNKI_20261103), "no 2026-11-03 registry");
        final MenuConfig cfg = config();
        final var help = pl.medivalrpg.menu.help.HelpCatalog.load(yaml("/pomoc.yml"));
        final Registry active = Registry.load(REGISTRY_BUDYNKI_20261103);   // = production since 2026-11-04
        assertFalse(active.met("/samouczek"));
        final List<String> shownActive = cfg.menu("samouczek").buttons().stream()
            .filter(b -> AccessRules.visible(b, active::met, p -> true, true, true)).map(ButtonDef::id).toList();
        assertEquals(List.of("naglowek", "krok-pieniadze", "krok-kraj", "krok-ziemia", "krok-mieszkania", "krok-jedzenie",
            "krok-budowa", "krok-produkcja", "krok-wlasne", "krok-armia", "krok-pomoc", "poradnik", "wstecz"), shownActive);
        assertTrue(help.category("start").visibleEntries(active::met, p -> true).stream()
            .noneMatch(e -> e.command().startsWith("/samouczek")));

        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(REGISTRY_SAMOUCZEK_20261111), "no 2026-11-11 registry");
        final Registry r = Registry.load(REGISTRY_SAMOUCZEK_20261111);
        for (String gate : List.of("/samouczek", "/samouczek ukryj", "/samouczek przypominaj", "/ludnosc budynek"))
            assertTrue(r.met(gate), "gate closed: " + gate);
        final List<String> dead = buttonCommands(cfg, (m, b) ->
            AccessRules.allows(m.requiresPlugins(), m.permission(), r::met, p -> true)
                && AccessRules.visible(b, r::met, p -> true, true, true)).stream()
            .filter(line -> !r.accepts(line.substring(line.indexOf("→ ") + 2))).toList();
        assertEquals(List.of(), dead, "visible but the command does not exist (Menu with /samouczek)");
        final List<String> helpDead = new ArrayList<>();
        for (var c : help.visibleCategories(r::met, p -> true)) for (var e : c.visibleEntries(r::met, p -> true)) {
            final String path = String.join(" ", literalPaths(e.command()).getFirst().split(" "));
            if (!r.accepts(path) && !Set.of("register", "login").contains(path)) helpDead.add(c.id() + ": " + e.command());
        }
        assertEquals(List.of(), helpDead, "/pomoc shows a missing command (Menu with /samouczek)");
        assertEquals(15, cfg.menu("samouczek").buttons().stream()
            .filter(b -> AccessRules.visible(b, r::met, p -> true, true, true)).count());
        assertEquals(3, help.category("start").visibleEntries(r::met, p -> true).stream()
            .filter(e -> e.command().startsWith("/samouczek")).count());
    }
}
