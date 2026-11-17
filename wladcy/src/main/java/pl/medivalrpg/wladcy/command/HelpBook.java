// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.wladcy.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import pl.medivalrpg.wladcy.config.Messages;

/**
 * Interactive help ({@code /wladca pomoc [strona]}): one page per category, one line per
 * command; hover = full description, syntax and who may use it; click = command put into the chat box. Texts live in
 * {@code messages.<book>.*}; this class only knows the structure. No Bukkit calls.
 */
final class HelpBook {

    /**
     * @param id       key in {@code messages.<book>.entries.<id>} ({@code short}, {@code description}, {@code rank})
     * @param category key in {@code messages.<book>.categories}
     * @param syntax   shown as-is
     * @param command  inserted into the chat box on click
     * @param admin    only on the admin page / for admins
     */
    record Entry(String id, String category, String syntax, String command, boolean admin) {
    }

    private final String book;
    private final String root;
    private final List<String> categories;
    private final List<Entry> entries;

    HelpBook(final String book, final String root, final List<String> categories, final List<Entry> entries) {
        this.book = book;
        this.root = root;
        this.categories = List.copyOf(categories);
        this.entries = List.copyOf(entries);
    }

    List<Entry> entries() {
        return this.entries;
    }

    List<String> pages(final boolean admin) {
        final List<String> out = new ArrayList<>();
        for (final String c : this.categories) {
            if (this.entries.stream().anyMatch(e -> e.category().equals(c) && (admin || !e.admin()))) {
                out.add(c);
            }
        }
        return out;
    }

    String categoryName(final Messages m, final String category) {
        return m.raw(this.book + ".categories." + category);
    }

    int resolvePage(final Messages m, final List<String> pages, final String arg) {
        if (arg == null || arg.isBlank()) {
            return 1;
        }
        final String input = arg.trim();
        try {
            final int n = Integer.parseInt(input);
            return Math.max(1, Math.min(n, pages.size()));
        } catch (final NumberFormatException ignored) {
            // a category name
        }
        final String wanted = normalize(input);
        for (int i = 0; i < pages.size(); i++) {
            final String c = pages.get(i);
            final String shown = PlainTextComponentSerializer.plainText().serialize(m.parse(this.categoryName(m, c)));
            if (normalize(c).equals(wanted) || normalize(shown).equals(wanted)) {
                return i + 1;
            }
        }
        return 1;
    }

    static String normalize(final String s) {
        final StringBuilder b = new StringBuilder();
        for (final char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            b.append(switch (ch) {
                case 'ą' -> 'a';
                case 'ć' -> 'c';
                case 'ę' -> 'e';
                case 'ł' -> 'l';
                case 'ń' -> 'n';
                case 'ó' -> 'o';
                case 'ś' -> 's';
                case 'ź', 'ż' -> 'z';
                default -> ch;
            });
        }
        return b.toString().trim();
    }

    List<Component> render(final Messages m, final boolean admin, final String pageArg) {
        final List<String> pages = this.pages(admin);
        final List<Component> lines = new ArrayList<>();
        if (pages.isEmpty()) {
            return lines;
        }
        final int page = this.resolvePage(m, pages, pageArg);
        final String category = pages.get(page - 1);
        lines.add(m.get("help-header",
            Placeholder.parsed("title", m.raw(this.book + ".title")),
            Placeholder.parsed("category", this.categoryName(m, category)),
            Placeholder.unparsed("page", Integer.toString(page)),
            Placeholder.unparsed("pages", Integer.toString(pages.size()))));
        for (final Entry e : this.entries) {
            if (!e.category().equals(category) || (e.admin() && !admin)) {
                continue;
            }
            final String base = this.book + ".entries." + e.id() + ".";
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
            Placeholder.component("prev", this.nav(m, pages, page - 1, "help-prev")),
            Placeholder.component("next", this.nav(m, pages, page + 1, "help-next"))));
        final List<Component> tabs = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            final String name = this.categoryName(m, pages.get(i));
            final boolean current = i + 1 == page;
            Component tab = m.get(current ? "help-tab-current" : "help-tab", Placeholder.parsed("category", name));
            if (!current) {
                tab = tab.hoverEvent(HoverEvent.showText(m.get("help-page-hover", Placeholder.parsed("category", name),
                        Placeholder.unparsed("page", Integer.toString(i + 1)))))
                    .clickEvent(ClickEvent.runCommand(this.root + " pomoc " + (i + 1)));
            }
            tabs.add(tab);
        }
        lines.add(m.get("help-tabs", Placeholder.component("tabs", Component.join(JoinConfiguration.separator(m.get("help-tab-separator")), tabs))));
        return lines;
    }

    private Component nav(final Messages m, final List<String> pages, final int target, final String key) {
        if (target < 1 || target > pages.size()) {
            return m.get(key + "-disabled");
        }
        final String name = this.categoryName(m, pages.get(target - 1));
        return m.get(key)
            .hoverEvent(HoverEvent.showText(m.get("help-page-hover", Placeholder.parsed("category", name), Placeholder.unparsed("page", Integer.toString(target)))))
            .clickEvent(ClickEvent.runCommand(this.root + " pomoc " + target));
    }
}
