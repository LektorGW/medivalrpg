// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.postep.config.Messages;
import pl.medivalrpg.postep.logic.ResearchMath;

/**
 * Interactive {@code /postep pomoc [strona]}: one page per category, one line per command; hover = description, syntax
 * and required rank, click = the command in the chat box. Texts in {@code messages.help-*}.
 */
final class HelpBook {

    enum Category {
        INFORMACJE, BADANIA, ADMIN;

        String key() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    record Entry(String id, Category category, String syntax, String command) {
    }

    static final List<Entry> ENTRIES = List.of(
        new Entry("status", Category.INFORMACJE, "/postep", "/postep"),
        new Entry("drzewko", Category.INFORMACJE, "/postep drzewko", "/postep drzewko"),
        new Entry("odkrycia", Category.INFORMACJE, "/postep odkrycia", "/postep odkrycia"),
        new Entry("epoki", Category.INFORMACJE, "/postep epoki", "/postep epoki"),
        new Entry("ranking", Category.INFORMACJE, "/postep ranking [strona]", "/postep ranking"),
        new Entry("kraj", Category.INFORMACJE, "/postep kraj <kraj>", "/postep kraj "),
        new Entry("pomoc", Category.INFORMACJE, "/postep pomoc [strona]", "/postep pomoc"),

        new Entry("odkryj", Category.BADANIA, "/postep odkryj <odkrycie>", "/postep odkryj "),
        new Entry("cel", Category.BADANIA, "/postep cel [wyczysc]", "/postep cel"),
        new Entry("inwestuj", Category.BADANIA, "/postep inwestuj <kwota>", "/postep inwestuj "),
        new Entry("awansuj", Category.BADANIA, "/postep awansuj", "/postep awansuj"),

        new Entry("admin-ustaw", Category.ADMIN, "/postep admin ustaw <kraj> <epoka>", "/postep admin ustaw "),
        new Entry("admin-punkty", Category.ADMIN, "/postep admin punkty <kraj> <±liczba>", "/postep admin punkty "),
        new Entry("admin-odkryj", Category.ADMIN, "/postep admin odkryj <kraj> <odkrycie>", "/postep admin odkryj "),
        new Entry("admin-zabierz", Category.ADMIN, "/postep admin zabierz <kraj> <odkrycie>", "/postep admin zabierz "),
        new Entry("admin-tick", Category.ADMIN, "/postep admin tick", "/postep admin tick"),
        new Entry("admin-info", Category.ADMIN, "/postep admin info", "/postep admin info"),
        new Entry("admin-przeladuj", Category.ADMIN, "/postep admin przeladuj", "/postep admin przeladuj"));

    private HelpBook() {
    }

    static List<Category> pages(final boolean admin) {
        final List<Category> pages = new ArrayList<>(List.of(Category.values()));
        if (!admin) {
            pages.remove(Category.ADMIN);
        }
        return pages;
    }

    static String categoryName(final Messages m, final Category c) {
        return m.raw("help-categories." + c.key());
    }

    static int resolvePage(final Messages m, final List<Category> pages, final String arg) {
        if (arg == null || arg.isBlank()) {
            return 1;
        }
        final String input = arg.trim();
        try {
            return Math.max(1, Math.min(Integer.parseInt(input), pages.size()));
        } catch (final NumberFormatException ignored) {
            // a category name
        }
        final String normalized = ResearchMath.normalize(input);
        for (int i = 0; i < pages.size(); i++) {
            final Category c = pages.get(i);
            final String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(m.parse(categoryName(m, c)));
            if (c.key().equals(normalized) || ResearchMath.normalize(plain).equals(normalized)) {
                return i + 1;
            }
        }
        return 1;
    }

    static List<Component> render(final Messages m, final boolean admin, final String pageArg) {
        final List<Category> pages = pages(admin);
        final int page = resolvePage(m, pages, pageArg);
        final Category category = pages.get(page - 1);
        final List<Component> lines = new ArrayList<>();
        lines.add(m.get("help-header", Placeholder.parsed("category", categoryName(m, category)),
            Placeholder.unparsed("page", Integer.toString(page)), Placeholder.unparsed("pages", Integer.toString(pages.size()))));
        for (final Entry e : ENTRIES) {
            if (e.category() != category) {
                continue;
            }
            final String base = "help-entries." + e.id() + ".";
            final Component hover = m.get("help-hover", Placeholder.unparsed("syntax", e.syntax()),
                Placeholder.component("description", m.get(base + "description")),
                Placeholder.component("rank", m.get(base + "rank")));
            lines.add(m.get("help-line", Placeholder.unparsed("syntax", e.syntax()), Placeholder.component("short", m.get(base + "short")))
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand(e.command())));
        }
        lines.add(m.get("help-nav", Placeholder.component("prev", nav(m, pages, page - 1, "help-prev")),
            Placeholder.component("next", nav(m, pages, page + 1, "help-next"))));
        final List<Component> tabs = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            final String name = categoryName(m, pages.get(i));
            final boolean current = i + 1 == page;
            Component tab = m.get(current ? "help-tab-current" : "help-tab", Placeholder.parsed("category", name));
            if (!current) {
                tab = tab.hoverEvent(HoverEvent.showText(m.get("help-page-hover", Placeholder.parsed("category", name),
                        Placeholder.unparsed("page", Integer.toString(i + 1)))))
                    .clickEvent(ClickEvent.runCommand("/postep pomoc " + (i + 1)));
            }
            tabs.add(tab);
        }
        lines.add(m.get("help-tabs", Placeholder.component("tabs",
            Component.join(JoinConfiguration.separator(m.get("help-tab-separator")), tabs))));
        return lines;
    }

    private static Component nav(final Messages m, final List<Category> pages, final int target, final String key) {
        if (target < 1 || target > pages.size()) {
            return m.get(key + "-disabled");
        }
        final String name = categoryName(m, pages.get(target - 1));
        return m.get(key)
            .hoverEvent(HoverEvent.showText(m.get("help-page-hover", Placeholder.parsed("category", name),
                Placeholder.unparsed("page", Integer.toString(target)))))
            .clickEvent(ClickEvent.runCommand("/postep pomoc " + target));
    }
}
