// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.ludnosc.config.Messages;

/**
 * Interactive {@code /ludnosc pomoc [strona]}: one page per category, hover = description, syntax and rank, click puts
 * the command into the chat box. Texts in {@code messages.help-*}. Pure function of {@link Messages}.
 */
final class HelpBook {

    enum Category {
        INFORMACJE, BUDOWA, SPICHLERZ, ADMIN;

        String key() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    record Entry(String id, Category category, String syntax, String command) {
    }

    static final List<Entry> ENTRIES = List.of(
        new Entry("pomoc", Category.INFORMACJE, "/ludnosc pomoc [strona]", "/ludnosc pomoc "),
        new Entry("info", Category.INFORMACJE, "/ludnosc info [kraj]", "/ludnosc info "),
        new Entry("budynki", Category.INFORMACJE, "/ludnosc budynki [kraj]", "/ludnosc budynki"),
        new Entry("typy", Category.INFORMACJE, "/ludnosc typy", "/ludnosc typy"),
        new Entry("plan", Category.INFORMACJE, "/ludnosc plan", "/ludnosc plan"),
        new Entry("plan-krolestwo", Category.INFORMACJE, "/ludnosc plan krolestwo", "/ludnosc plan krolestwo"),

        new Entry("buduj-gracze", Category.BUDOWA, "/ludnosc buduj <typ>", "/ludnosc buduj "),
        new Entry("buduj-obroc", Category.BUDOWA, "/ludnosc buduj obroc", "/ludnosc buduj obroc"),
        new Entry("buduj-wariant", Category.BUDOWA, "/ludnosc buduj wariant", "/ludnosc buduj wariant"),
        new Entry("buduj-potwierdz", Category.BUDOWA, "/ludnosc buduj potwierdz", "/ludnosc buduj potwierdz"),
        new Entry("buduj-anuluj-plac", Category.BUDOWA, "/ludnosc buduj anuluj [id]", "/ludnosc buduj anuluj"),
        new Entry("kolejka", Category.BUDOWA, "/ludnosc kolejka", "/ludnosc kolejka"),
        new Entry("napraw", Category.BUDOWA, "/ludnosc napraw <id|wszystko>", "/ludnosc napraw "),
        new Entry("modernizuj", Category.BUDOWA, "/ludnosc modernizuj <id|wszystko>", "/ludnosc modernizuj "),
        new Entry("kopalnia", Category.BUDOWA, "/ludnosc kopalnia", "/ludnosc kopalnia"),
        new Entry("kopia", Category.BUDOWA, "/ludnosc kopia", "/ludnosc kopia "),
        new Entry("budynek-zglos", Category.BUDOWA, "/ludnosc budynek zglos <typ> [nazwa]", "/ludnosc budynek zglos "),
        new Entry("budynek-typy", Category.BUDOWA, "/ludnosc budynek typy", "/ludnosc budynek typy"),
        new Entry("budynek-info", Category.BUDOWA, "/ludnosc budynek info", "/ludnosc budynek info"),
        new Entry("budynek-usun", Category.BUDOWA, "/ludnosc budynek usun", "/ludnosc budynek usun"),
        new Entry("podziemia", Category.BUDOWA, "/ludnosc podziemia", "/ludnosc podziemia"),
        new Entry("przebuduj", Category.BUDOWA, "/ludnosc przebuduj", "/ludnosc przebuduj"),
        new Entry("magazyn", Category.INFORMACJE, "/ludnosc magazyn", "/ludnosc magazyn"),
        new Entry("budowy", Category.INFORMACJE, "/ludnosc budowy", "/ludnosc budowy"),
        new Entry("gospodarka", Category.INFORMACJE, "/ludnosc gospodarka [kraj]", "/ludnosc gospodarka"),
        new Entry("zaklady", Category.INFORMACJE, "/ludnosc zaklady [kraj]", "/ludnosc zaklady"),
        new Entry("obywatel", Category.INFORMACJE, "/ludnosc obywatel [imię]", "/ludnosc obywatel "),
        new Entry("obywatele", Category.INFORMACJE, "/ludnosc obywatele [kraj]", "/ludnosc obywatele"),
        new Entry("obywatele-chatki", Category.BUDOWA, "/ludnosc obywatele chatki wl|wyl|limit <n>", "/ludnosc obywatele chatki "),
        new Entry("pora", Category.INFORMACJE, "/ludnosc pora", "/ludnosc pora"),
        new Entry("praca", Category.INFORMACJE, "/ludnosc praca [kraj]", "/ludnosc praca"),
        new Entry("podziemia-kop", Category.BUDOWA, "/ludnosc podziemia kop [id]", "/ludnosc podziemia kop "),
        new Entry("kopalnia-rozbuduj", Category.BUDOWA, "/ludnosc kopalnia rozbuduj [id]", "/ludnosc kopalnia rozbuduj"),
        new Entry("kolejka-anuluj", Category.BUDOWA, "/ludnosc kolejka anuluj <nr>", "/ludnosc kolejka anuluj "),
        new Entry("kolejka-wyzej", Category.BUDOWA, "/ludnosc kolejka wyzej <nr>", "/ludnosc kolejka wyzej "),
        new Entry("kolejka-nizej", Category.BUDOWA, "/ludnosc kolejka nizej <nr>", "/ludnosc kolejka nizej "),
        new Entry("kolejka-pokaz", Category.BUDOWA, "/ludnosc kolejka pokaz <nr>", "/ludnosc kolejka pokaz "),

        new Entry("spichlerz", Category.SPICHLERZ, "/ludnosc spichlerz", "/ludnosc spichlerz"),
        new Entry("spichlerz-oznacz", Category.SPICHLERZ, "/ludnosc spichlerz oznacz", "/ludnosc spichlerz oznacz"),
        new Entry("spichlerz-usun", Category.SPICHLERZ, "/ludnosc spichlerz usun", "/ludnosc spichlerz usun"),

        new Entry("dom-zglos", Category.ADMIN, "/ludnosc dom zglos", "/ludnosc dom zglos"),
        new Entry("dom-usun", Category.ADMIN, "/ludnosc dom usun", "/ludnosc dom usun"),
        new Entry("admin-dodaj", Category.ADMIN, "/ludnosc admin dodaj <kraj> <n>", "/ludnosc admin dodaj "),
        new Entry("admin-krolestwo",Category.ADMIN,"/ludnosc admin krolestwo preview|start|status|resume","/ludnosc admin krolestwo status"),
        new Entry("admin-usun", Category.ADMIN, "/ludnosc admin usun <kraj> <n>", "/ludnosc admin usun "),
        new Entry("admin-budynek-anuluj", Category.ADMIN, "/ludnosc admin budynek anuluj [id]", "/ludnosc admin budynek anuluj"),
        new Entry("admin-kolejka", Category.ADMIN, "/ludnosc admin kolejka <kraj> [wyczysc]", "/ludnosc admin kolejka "),
        new Entry("admin-przebudowa", Category.ADMIN, "/ludnosc admin przebudowa <kraj> [podglad|teraz|anuluj|reset] [darmowo]",
            "/ludnosc admin przebudowa "),
        new Entry("admin-drogi", Category.ADMIN, "/ludnosc admin drogi <kraj> [darmowo]", "/ludnosc admin drogi "),
        new Entry("admin-modernizuj", Category.ADMIN, "/ludnosc admin modernizuj <kraj> [wszystko] [darmowo]", "/ludnosc admin modernizuj "),
        new Entry("admin-jedzenie", Category.ADMIN, "/ludnosc admin jedzenie <kraj> <ilość>", "/ludnosc admin jedzenie "),
        new Entry("admin-pora", Category.ADMIN, "/ludnosc admin pora info|ustaw <pora> [dzień]", "/ludnosc admin pora "),
        new Entry("admin-nieurodzaj", Category.ADMIN, "/ludnosc admin nieurodzaj <kraj> [dni|koniec]", "/ludnosc admin nieurodzaj "),
        new Entry("admin-praca", Category.ADMIN, "/ludnosc admin praca", "/ludnosc admin praca"),
        new Entry("admin-dzien", Category.ADMIN, "/ludnosc admin dzien", "/ludnosc admin dzien"),
        new Entry("admin-wzrost", Category.ADMIN, "/ludnosc admin wzrost", "/ludnosc admin wzrost"),
        new Entry("admin-reload", Category.ADMIN, "/ludnosc admin reload", "/ludnosc admin reload"));

    private HelpBook() {
    }

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

    static int resolvePage(final Messages messages, final List<Category> pages, final String arg) {
        if (arg == null || arg.isBlank()) {
            return 1;
        }
        final String input = arg.trim().toLowerCase(Locale.ROOT);
        try {
            return Math.max(1, Math.min(Integer.parseInt(input), pages.size()));
        } catch (final NumberFormatException ignored) {
            // category name
        }
        for (int i = 0; i < pages.size(); i++) {
            final Category c = pages.get(i);
            final String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(messages.parse(categoryName(messages, c))).toLowerCase(Locale.ROOT);
            if (c.key().equals(input) || plain.equals(input)) {
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
            lines.add(m.get("help-line", Placeholder.unparsed("syntax", e.syntax()), Placeholder.component("short", m.get(base + "short")))
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand(e.command())));
        }
        lines.add(m.get("help-nav",
            Placeholder.component("prev", nav(m, pages, page - 1, "help-prev")),
            Placeholder.component("next", nav(m, pages, page + 1, "help-next"))));
        final List<Component> tabs = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            final String name = categoryName(m, pages.get(i));
            final boolean current = i + 1 == page;
            Component tab = m.get(current ? "help-tab-current" : "help-tab", Placeholder.parsed("category", name));
            if (!current) {
                tab = tab.hoverEvent(HoverEvent.showText(m.get("help-page-hover", Placeholder.parsed("category", name),
                        Placeholder.unparsed("page", Integer.toString(i + 1)))))
                    .clickEvent(ClickEvent.runCommand("/ludnosc pomoc " + (i + 1)));
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
            .clickEvent(ClickEvent.runCommand("/ludnosc pomoc " + target));
    }
}
