// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.skarbiec.config.Messages;

/**
 * Interactive {@code /skarbiec pomoc [strona]}: one page per category, one line per command. Hover shows the
 * description, click puts the command into the chat box. Texts live in {@code messages.help-*}; pure function
 * of {@link Messages} (no Bukkit).
 */
final class HelpBook {

    enum Category {
        BANK, HANDEL, SKLEPY, ADMIN;

        String key() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    record Entry(String id, Category category, String syntax, String command) {
    }

    static final List<Entry> ENTRIES = List.of(
        new Entry("saldo", Category.BANK, "/saldo", "/saldo"),
        new Entry("zaplac", Category.BANK, "/zaplac <gracz> <kwota>", "/zaplac "),
        new Entry("monety", Category.BANK, "/monety", "/monety"),
        new Entry("bankier", Category.BANK, "PPM na bankiera", "/saldo"),
        new Entry("kwoty", Category.BANK, "1 zł = 10 sr = 100 md", "/monety"),

        new Entry("handel", Category.HANDEL, "/handel <gracz>", "/handel "),
        new Entry("kupiec", Category.HANDEL, "PPM na kupca", "/skarbiec kupcy"),
        new Entry("kupcy", Category.HANDEL, "/skarbiec kupcy", "/skarbiec kupcy"),

        new Entry("sklep", Category.SKLEPY, "[Sklep] / Sprzedam|Kupię / ilość / cena", "/skarbiec pomoc 3"),
        new Entry("sklepy", Category.SKLEPY, "/skarbiec sklepy", "/skarbiec sklepy"),

        new Entry("admin-konto", Category.ADMIN, "/skarbiec admin daj|zabierz|ustaw <gracz|konto> <kwota>", "/skarbiec admin daj "),
        new Entry("admin-monety", Category.ADMIN, "/skarbiec admin monety <gracz> <kwota>", "/skarbiec admin monety "),
        new Entry("admin-npc", Category.ADMIN, "/skarbiec npc stworz <kupiec|bankier> [\"nazwa\"] [skin] [typ]", "/skarbiec npc stworz "),
        new Entry("admin-npc-typ", Category.ADMIN, "/skarbiec npc typ <id|najblizszy> <typ>", "/skarbiec npc typ najblizszy "),
        new Entry("admin-kram", Category.ADMIN, "/skarbiec kram postaw|przesun|obroc|usun [id|najblizszy]", "/skarbiec kram"),
        new Entry("admin-npc-usun", Category.ADMIN, "/skarbiec npc usun [id]", "/skarbiec npc usun"),
        new Entry("admin-npc-skin", Category.ADMIN, "/skarbiec npc skin <id|najblizszy> <nick|losowy>", "/skarbiec npc skin "),
        new Entry("admin-npc-lista", Category.ADMIN, "/skarbiec npc lista", "/skarbiec npc lista"),
        new Entry("admin-log", Category.ADMIN, "/skarbiec log <gracz|konto> [strona]", "/skarbiec log "),
        new Entry("admin-info", Category.ADMIN, "/skarbiec info", "/skarbiec info"),
        new Entry("admin-reload", Category.ADMIN, "/skarbiec reload", "/skarbiec reload"));

    private HelpBook() {
    }

    static List<Category> pages(final boolean admin) {
        final List<Category> pages = new ArrayList<>(List.of(Category.values()));
        if (!admin) {
            pages.remove(Category.ADMIN);
        }
        return pages;
    }

    static int resolvePage(final List<Category> pages, final String arg) {
        if (arg == null || arg.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(arg.trim()), pages.size()));
        } catch (final NumberFormatException e) {
            final String a = arg.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i).key().equals(a)) {
                    return i + 1;
                }
            }
            return 1;
        }
    }

    static List<Component> render(final Messages m, final boolean admin, final String pageArg) {
        final List<Category> pages = pages(admin);
        final int page = resolvePage(pages, pageArg);
        final Category category = pages.get(page - 1);
        final List<Component> lines = new ArrayList<>();
        lines.add(m.get("help-header", Placeholder.parsed("category", m.raw("help-categories." + category.key())),
            Placeholder.unparsed("page", Integer.toString(page)), Placeholder.unparsed("pages", Integer.toString(pages.size()))));
        for (final Entry e : ENTRIES) {
            if (e.category() != category) {
                continue;
            }
            final String base = "help-entries." + e.id() + ".";
            final Component hover = m.get("help-hover", Placeholder.unparsed("syntax", e.syntax()),
                Placeholder.component("description", m.get(base + "description")));
            lines.add(m.get("help-line", Placeholder.unparsed("syntax", e.syntax()), Placeholder.component("short", m.get(base + "short")))
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand(e.command())));
        }
        final List<Component> tabs = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            final String name = m.raw("help-categories." + pages.get(i).key());
            if (i + 1 == page) {
                tabs.add(m.get("help-tab-current", Placeholder.parsed("category", name)));
            } else {
                tabs.add(m.get("help-tab", Placeholder.parsed("category", name))
                    .hoverEvent(HoverEvent.showText(m.get("help-page-hover", Placeholder.parsed("category", name))))
                    .clickEvent(ClickEvent.runCommand("/skarbiec pomoc " + (i + 1))));
            }
        }
        lines.add(m.get("help-tabs", Placeholder.component("tabs", Component.join(JoinConfiguration.separator(m.get("help-tab-separator")), tabs))));
        return lines;
    }
}
