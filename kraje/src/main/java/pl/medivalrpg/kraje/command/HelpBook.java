// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.util.Colors;

/**
 * Interactive {@code /kraj pomoc [strona]}: one page per category, one line per command. Hover shows the full
 * description, syntax and required rank; click puts the command into the chat box ({@code suggest_command}).
 * Texts live in {@code messages.help-*} (config.yml); only the structure (syntax, command to insert) is here.
 * Pure function of {@link Messages}, no Bukkit calls.
 */
final class HelpBook {

    enum Category {
        TEREN, LUDZIE, WYGLAD, DYPLOMACJA, SKARBIEC, INFORMACJE, CZAT, ADMIN;

        String key() {
            return this.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * @param id       key in {@code messages.help-entries.<id>} ({@code short}, {@code description}, {@code rank})
     * @param syntax   plain text, shown as-is (never parsed as MiniMessage)
     * @param command  text inserted into the chat box on click
     */
    record Entry(String id, Category category, String syntax, String command) {
    }

    static final List<Entry> ENTRIES = List.of(
        new Entry("zaloz", Category.TEREN, "/kraj zaloz <nazwa>", "/kraj zaloz "),
        new Entry("zajmij", Category.TEREN, "/kraj zajmij", "/kraj zajmij"),
        new Entry("porzuc", Category.TEREN, "/kraj porzuc", "/kraj porzuc"),
        new Entry("stolica", Category.TEREN, "/kraj stolica", "/kraj stolica"),
        new Entry("spawn", Category.TEREN, "/kraj spawn", "/kraj spawn"),

        new Entry("zapros", Category.LUDZIE, "/kraj zapros <gracz>", "/kraj zapros "),
        new Entry("dolacz", Category.LUDZIE, "/kraj dolacz <kraj>", "/kraj dolacz "),
        new Entry("odrzuc", Category.LUDZIE, "/kraj odrzuc <kraj>", "/kraj odrzuc "),
        new Entry("opusc", Category.LUDZIE, "/kraj opusc", "/kraj opusc"),
        new Entry("wyrzuc", Category.LUDZIE, "/kraj wyrzuc <gracz>", "/kraj wyrzuc "),
        new Entry("awansuj", Category.LUDZIE, "/kraj awansuj <gracz>", "/kraj awansuj "),
        new Entry("degraduj", Category.LUDZIE, "/kraj degraduj <gracz>", "/kraj degraduj "),
        new Entry("przekaz", Category.LUDZIE, "/kraj przekaz <gracz>", "/kraj przekaz "),
        new Entry("rozwiaz", Category.LUDZIE, "/kraj rozwiaz", "/kraj rozwiaz"),

        new Entry("kolor", Category.WYGLAD, "/kraj kolor <kolor>", "/kraj kolor "),
        new Entry("opis", Category.WYGLAD, "/kraj opis <tekst>", "/kraj opis "),
        new Entry("flaga", Category.WYGLAD, "/kraj flaga", "/kraj flaga"),
        new Entry("flaga-postaw", Category.WYGLAD, "/kraj flaga postaw", "/kraj flaga postaw"),
        new Entry("flaga-domyslna", Category.WYGLAD, "/kraj flaga domyslna", "/kraj flaga domyslna"),
        new Entry("flaga-sztandar", Category.WYGLAD, "/kraj flaga sztandar", "/kraj flaga sztandar"),
        new Entry("flaga-przedmiot", Category.WYGLAD, "/kraj flaga przedmiot", "/kraj flaga przedmiot"),
        new Entry("flaga-historia", Category.WYGLAD, "/kraj flaga historia", "/kraj flaga historia"),

        new Entry("sojusz", Category.DYPLOMACJA, "/kraj sojusz <kraj>", "/kraj sojusz "),
        new Entry("wojna", Category.DYPLOMACJA, "/kraj wojna <kraj>", "/kraj wojna "),
        new Entry("pokoj", Category.DYPLOMACJA, "/kraj pokoj <kraj>", "/kraj pokoj "),
        new Entry("zerwij", Category.DYPLOMACJA, "/kraj zerwij <kraj>", "/kraj zerwij "),
        new Entry("kapitulacja", Category.DYPLOMACJA, "/kraj kapitulacja <wróg> [dni]", "/kraj kapitulacja "),
        new Entry("kapitulacja-przyjmij", Category.DYPLOMACJA, "/kraj kapitulacja przyjmij <kraj>", "/kraj kapitulacja przyjmij "),
        new Entry("wasale", Category.DYPLOMACJA, "/kraj wasale [kraj]", "/kraj wasale"),
        new Entry("wykup", Category.DYPLOMACJA, "/kraj wykup", "/kraj wykup"),
        new Entry("zwolnij", Category.DYPLOMACJA, "/kraj zwolnij <wasal>", "/kraj zwolnij "),
        new Entry("unia-zaproponuj", Category.DYPLOMACJA, "/kraj unia zaproponuj <kraj>", "/kraj unia zaproponuj "),
        new Entry("unia-przyjmij", Category.DYPLOMACJA, "/kraj unia przyjmij <kraj>", "/kraj unia przyjmij "),
        new Entry("unia-info", Category.DYPLOMACJA, "/kraj unia info [kraj]", "/kraj unia info"),
        new Entry("ambasada-otworz", Category.DYPLOMACJA, "/kraj ambasada otworz <kraj>", "/kraj ambasada otworz "),
        new Entry("ambasada-lista", Category.DYPLOMACJA, "/kraj ambasada lista [kraj]", "/kraj ambasada lista"),
        new Entry("ambasada-tp", Category.DYPLOMACJA, "/kraj ambasada tp <kraj>", "/kraj ambasada tp "),
        new Entry("oblezenie", Category.DYPLOMACJA, "/kraj oblezenie", "/kraj oblezenie"),
        new Entry("desant", Category.DYPLOMACJA, "/kraj desant", "/kraj desant"),
        new Entry("oblezenia", Category.DYPLOMACJA, "/kraj oblezenia", "/kraj oblezenia"),

        new Entry("skarbiec", Category.SKARBIEC, "/kraj skarbiec", "/kraj skarbiec"),
        new Entry("wplac", Category.SKARBIEC, "/kraj wplac <kwota> [konto|monety]", "/kraj wplac "),
        new Entry("wyplac", Category.SKARBIEC, "/kraj wyplac <kwota>", "/kraj wyplac "),
        new Entry("podatek", Category.SKARBIEC, "/kraj podatek [kwota]", "/kraj podatek "),

        new Entry("info", Category.INFORMACJE, "/kraj info [kraj]", "/kraj info "),
        new Entry("lista", Category.INFORMACJE, "/kraj lista [strona]", "/kraj lista"),
        new Entry("mapa", Category.INFORMACJE, "/kraj mapa", "/kraj mapa"),
        new Entry("mapa-www", Category.INFORMACJE, "/mapa", "/mapa"),
        new Entry("pomoc", Category.INFORMACJE, "/kraj pomoc [strona]", "/kraj pomoc "),

        new Entry("czat", Category.CZAT, "/kraj czat", "/kraj czat"),
        new Entry("kc", Category.CZAT, "/kc <wiadomość>", "/kc "),
        new Entry("ks", Category.CZAT, "/ks <wiadomość>", "/ks "),

        new Entry("admin-usun", Category.ADMIN, "/kraj admin usun <kraj>", "/kraj admin usun "),
        new Entry("admin-usun-przywroc", Category.ADMIN, "/kraj admin usun <kraj> przywroc", "/kraj admin usun "),
        new Entry("admin-wladca", Category.ADMIN, "/kraj admin wladca <kraj> <gracz>", "/kraj admin wladca "),
        new Entry("admin-zajmij", Category.ADMIN, "/kraj admin zajmij <kraj>", "/kraj admin zajmij "),
        new Entry("admin-zajmij-promien", Category.ADMIN, "/kraj admin zajmij <kraj> promien <n>", "/kraj admin zajmij "),
        new Entry("admin-zajmij-zaznaczenie", Category.ADMIN, "/kraj admin zajmij <kraj> zaznaczenie", "/kraj admin zajmij "),
        new Entry("admin-przylacz", Category.ADMIN, "/kraj admin przylacz <kraj> [pierścienie]", "/kraj admin przylacz "),
        new Entry("admin-porzuc-obszar", Category.ADMIN, "/kraj admin porzuc <kraj> [promien <n>|zaznaczenie]", "/kraj admin porzuc "),
        new Entry("admin-przywroc", Category.ADMIN, "/kraj admin przywroc <kraj> [promien <n>|zaznaczenie]", "/kraj admin przywroc "),
        new Entry("admin-przywroc-teren", Category.ADMIN, "/kraj admin przywroc teren <obszar>", "/kraj admin przywroc teren "),
        new Entry("admin-przywroc-stop", Category.ADMIN, "/kraj admin przywroc stop", "/kraj admin przywroc stop"),
        new Entry("admin-tp", Category.ADMIN, "/kraj admin tp <kraj>", "/kraj admin tp "),
        new Entry("admin-porzuc", Category.ADMIN, "/kraj admin porzuc", "/kraj admin porzuc"),
        new Entry("admin-bypass", Category.ADMIN, "/kraj admin bypass", "/kraj admin bypass"),
        new Entry("admin-spy", Category.ADMIN, "/kraj admin spy", "/kraj admin spy"),
        new Entry("admin-reload", Category.ADMIN, "/kraj admin reload", "/kraj admin reload"),
        new Entry("admin-tick", Category.ADMIN, "/kraj admin tick", "/kraj admin tick"),
        new Entry("admin-oblezenie", Category.ADMIN, "/kraj admin oblezenie", "/kraj admin oblezenie"),
        new Entry("admin-flaga", Category.ADMIN, "/kraj flaga admin kolejka", "/kraj flaga admin "));

    private HelpBook() {
    }

    /** Categories (= pages) visible to the sender; the admin page only with {@code kraje.admin}. */
    static List<Category> pages(final boolean admin) {
        final List<Category> pages = new ArrayList<>(List.of(Category.values()));
        if (!admin) {
            pages.remove(Category.ADMIN);
        }
        return pages;
    }

    static List<Entry> entries(final Category category) {
        return ENTRIES.stream().filter(e -> e.category() == category).toList();
    }

    static String categoryName(final Messages messages, final Category category) {
        return messages.raw("help-categories." + category.key());
    }

    /**
     * Page number (1-based) for {@code /kraj pomoc <arg>}: a number (clamped) or a category name
     * (case and Polish diacritics ignored). Anything else → page 1.
     */
    static int resolvePage(final Messages messages, final List<Category> pages, final String arg) {
        if (arg == null || arg.isBlank()) {
            return 1;
        }
        final String input = arg.trim();
        final int number = KrajCommand.parseInt(input, -1);
        if (number != -1) {
            return Math.max(1, Math.min(number, pages.size()));
        }
        final String normalized = Colors.normalize(input);
        for (int i = 0; i < pages.size(); i++) {
            final Category c = pages.get(i);
            if (c.key().equals(normalized) || Colors.normalize(plain(messages, categoryName(messages, c))).equals(normalized)) {
                return i + 1;
            }
        }
        return 1;
    }

    private static String plain(final Messages messages, final String miniMessage) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(messages.parse(miniMessage));
    }

    /** All chat lines of one help page. */
    static List<Component> render(final Messages m, final boolean admin, final String pageArg) {
        final List<Category> pages = pages(admin);
        final int page = resolvePage(m, pages, pageArg);
        final Category category = pages.get(page - 1);
        final List<Component> lines = new ArrayList<>();

        lines.add(m.get("help-header",
            Placeholder.parsed("category", categoryName(m, category)),
            Placeholder.unparsed("page", Integer.toString(page)),
            Placeholder.unparsed("pages", Integer.toString(pages.size()))));

        for (final Entry e : entries(category)) {
            final String base = "help-entries." + e.id() + ".";
            final Component hover = m.get("help-hover",
                Placeholder.unparsed("syntax", e.syntax()),
                Placeholder.component("description", m.get(base + "description")),
                Placeholder.component("rank", m.get(base + "rank")));
            lines.add(m.get("help-line",
                    Placeholder.unparsed("syntax", e.syntax()),
                    Placeholder.component("short", m.get(base + "short")))
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand(e.command())));
        }

        lines.add(m.get("help-nav",
            Placeholder.component("prev", navButton(m, pages, page - 1, "help-prev")),
            Placeholder.component("next", navButton(m, pages, page + 1, "help-next"))));

        final List<Component> tabs = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            final String name = categoryName(m, pages.get(i));
            final boolean current = i + 1 == page;
            Component tab = m.get(current ? "help-tab-current" : "help-tab", Placeholder.parsed("category", name));
            if (!current) {
                tab = tab.hoverEvent(HoverEvent.showText(m.get("help-page-hover",
                        Placeholder.parsed("category", name), Placeholder.unparsed("page", Integer.toString(i + 1)))))
                    .clickEvent(ClickEvent.runCommand("/kraj pomoc " + (i + 1)));
            }
            tabs.add(tab);
        }
        lines.add(m.get("help-tabs", Placeholder.component("tabs",
            Component.join(JoinConfiguration.separator(m.get("help-tab-separator")), tabs))));
        return lines;
    }

    private static Component navButton(final Messages m, final List<Category> pages, final int target, final String key) {
        if (target < 1 || target > pages.size()) {
            return m.get(key + "-disabled");
        }
        final String name = categoryName(m, pages.get(target - 1));
        return m.get(key)
            .hoverEvent(HoverEvent.showText(m.get("help-page-hover",
                Placeholder.parsed("category", name), Placeholder.unparsed("page", Integer.toString(target)))))
            .clickEvent(ClickEvent.runCommand("/kraj pomoc " + target));
    }
}
