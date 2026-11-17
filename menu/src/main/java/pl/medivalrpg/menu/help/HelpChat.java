// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.help;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.menu.config.Messages;

/**
 * Chat pages of {@code /pomoc}: overview, one category (guide + commands) and search results. Every command is
 * clickable (hover = description and rank, click = put into the chat box or run). Texts from {@code pomoc.yml} are
 * always inserted as plain text, never parsed as MiniMessage. Pure function of {@link Messages}: no Bukkit calls.
 */
public final class HelpChat {

    public static final String COMMAND = "/pomoc";
    public static final int SEARCH_LIMIT = 12;

    private final Messages m;
    private final Predicate<String> pluginEnabled;
    private final Predicate<String> permission;

    public HelpChat(final Messages messages, final Predicate<String> pluginEnabled, final Predicate<String> permission) {
        this.m = messages;
        this.pluginEnabled = pluginEnabled;
        this.permission = permission;
    }

    /** {@code /pomoc lista}: one clickable line per visible category. */
    public List<Component> overview(final HelpCatalog catalog) {
        final List<Component> lines = new ArrayList<>();
        lines.add(this.m.get("help-overview-header"));
        for (final String intro : catalog.intro()) {
            lines.add(this.guideLine(intro, "help-overview-intro"));
        }
        for (final HelpCatalog.Category c : catalog.visibleCategories(this.pluginEnabled, this.permission)) {
            lines.add(this.m.get("help-overview-line",
                    Placeholder.component("name", this.name(c)),
                    Placeholder.unparsed("description", c.description()),
                    Placeholder.unparsed("count", Integer.toString(c.visibleEntries(this.pluginEnabled, this.permission).size())))
                .hoverEvent(HoverEvent.showText(this.m.get("help-tab-hover",
                    Placeholder.component("name", this.name(c)), Placeholder.unparsed("id", c.id()))))
                .clickEvent(ClickEvent.runCommand(COMMAND + " " + c.id())));
        }
        lines.add(this.footer());
        return lines;
    }

    /** {@code /pomoc <kategoria>}: header, description, "how to start", commands and the category tabs. */
    public List<Component> category(final HelpCatalog catalog, final HelpCatalog.Category c) {
        final List<Component> lines = new ArrayList<>();
        lines.add(this.m.get("help-header", Placeholder.component("category", this.name(c))));
        if (!c.description().isBlank()) {
            lines.add(this.m.get("help-description", Placeholder.unparsed("description", c.description())));
        }
        if (!c.guide().isEmpty()) {
            lines.add(this.m.get("help-guide-title"));
            int n = 1;
            for (final String step : c.guide()) {
                lines.add(this.m.get("help-guide-line", Placeholder.unparsed("number", Integer.toString(n++)),
                    Placeholder.component("line", this.guideText(step))));
            }
        }
        final List<HelpCatalog.Entry> entries = c.visibleEntries(this.pluginEnabled, this.permission);
        if (!entries.isEmpty()) {
            lines.add(this.m.get("help-commands-title"));
            for (final HelpCatalog.Entry e : entries) {
                lines.add(this.entryLine("help-line", e, null));
            }
        }
        lines.add(this.tabs(catalog, c));
        lines.add(this.footer());
        return lines;
    }

    /** {@code /pomoc szukaj <fraza>}. */
    public List<Component> search(final HelpCatalog catalog, final String query) {
        final List<HelpSearch.Hit> hits = HelpSearch.search(catalog, query, SEARCH_LIMIT, this.pluginEnabled, this.permission);
        if (hits.isEmpty()) {
            return List.of(this.m.get("help-search-none", Placeholder.unparsed("query", query.strip())), this.footer());
        }
        final List<Component> lines = new ArrayList<>();
        lines.add(this.m.get("help-search-header", Placeholder.unparsed("query", query.strip()),
            Placeholder.unparsed("count", Integer.toString(hits.size()))));
        for (final HelpSearch.Hit hit : hits) {
            lines.add(this.entryLine("help-search-line", hit.entry(), hit.category()));
        }
        lines.add(this.footer());
        return lines;
    }

    /** One clickable command line ({@code help-line} or {@code help-search-line}). */
    public Component entryLine(final String key, final HelpCatalog.Entry e, final HelpCatalog.Category category) {
        final Component rank = e.rank().isBlank() ? Component.empty()
            : this.m.get("help-line-rank", Placeholder.unparsed("rank", e.rank()));
        Component line = this.m.get(key,
            Placeholder.unparsed("command", e.command()),
            Placeholder.unparsed("description", e.description()),
            Placeholder.component("rank", rank),
            Placeholder.component("category", category == null ? Component.empty() : this.name(category)));
        return line.hoverEvent(HoverEvent.showText(this.hover(e))).clickEvent(this.click(e));
    }

    public Component hover(final HelpCatalog.Entry e) {
        final Component rank = e.rank().isBlank() ? Component.empty()
            : this.m.get("help-hover-rank", Placeholder.unparsed("rank", e.rank()));
        return this.m.get(e.run() ? "help-hover-run" : "help-hover-suggest",
            Placeholder.unparsed("command", e.command()),
            Placeholder.unparsed("description", e.description()),
            Placeholder.component("rank", rank));
    }

    public ClickEvent click(final HelpCatalog.Entry e) {
        return e.run() ? ClickEvent.runCommand(e.insert()) : ClickEvent.suggestCommand(e.insert());
    }

    /** A guide line: plain text with clickable {@code `komenda`} parts (put into the chat box). */
    public Component guideText(final String line) {
        final List<Component> parts = new ArrayList<>();
        for (final HelpSearch.Segment s : HelpSearch.segments(line)) {
            if (s.command() && s.text().startsWith("/")) {
                parts.add(this.m.get("help-guide-command", Placeholder.unparsed("command", s.text()))
                    .hoverEvent(HoverEvent.showText(this.m.get("suggest-hover")))
                    .clickEvent(ClickEvent.suggestCommand(HelpCatalog.suggestion(s.text()))));
            } else {
                parts.add(this.m.get(s.command() ? "help-guide-code" : "help-guide-text",
                    Placeholder.unparsed("text", s.text())));
            }
        }
        return Component.join(JoinConfiguration.noSeparators(), parts);
    }

    private Component guideLine(final String line, final String key) {
        return this.m.get(key, Placeholder.component("line", this.guideText(line)));
    }

    /** Clickable category tabs; the current one is highlighted. */
    public Component tabs(final HelpCatalog catalog, final HelpCatalog.Category current) {
        final List<Component> tabs = new ArrayList<>();
        for (final HelpCatalog.Category c : catalog.visibleCategories(this.pluginEnabled, this.permission)) {
            final boolean same = current != null && c.id().equals(current.id());
            Component tab = this.m.get(same ? "help-tab-current" : "help-tab", Placeholder.component("name", this.name(c)));
            if (!same) {
                tab = tab.hoverEvent(HoverEvent.showText(this.m.get("help-tab-hover",
                        Placeholder.component("name", this.name(c)), Placeholder.unparsed("id", c.id()))))
                    .clickEvent(ClickEvent.runCommand(COMMAND + " " + c.id()));
            }
            tabs.add(tab);
        }
        return this.m.get("help-tabs", Placeholder.component("tabs",
            Component.join(JoinConfiguration.separator(this.m.get("help-tab-separator")), tabs)));
    }

    private Component footer() {
        final Component search = this.m.get("help-search-button")
            .hoverEvent(HoverEvent.showText(this.m.get("help-search-hover")))
            .clickEvent(ClickEvent.suggestCommand(COMMAND + " szukaj "));
        final Component menu = this.m.get("help-menu-button")
            .hoverEvent(HoverEvent.showText(this.m.get("help-menu-hover")))
            .clickEvent(ClickEvent.runCommand(COMMAND));
        final Component list = this.m.get("help-list-button")
            .hoverEvent(HoverEvent.showText(this.m.get("help-list-hover")))
            .clickEvent(ClickEvent.runCommand(COMMAND + " lista"));
        return this.m.get("help-footer", Placeholder.component("search", search), Placeholder.component("menu", menu),
            Placeholder.component("list", list));
    }

    /** Category name in its colour ({@code color}: a named colour or {@code #RRGGBB}). */
    public Component name(final HelpCatalog.Category c) {
        return Component.text(c.name(), color(c.color()));
    }

    public static TextColor color(final String raw) {
        if (raw != null) {
            final String v = raw.strip();
            final TextColor hex = v.startsWith("#") ? TextColor.fromHexString(v) : null;
            if (hex != null) {
                return hex;
            }
            final NamedTextColor named = NamedTextColor.NAMES.value(v.toLowerCase(java.util.Locale.ROOT));
            if (named != null) {
                return named;
            }
        }
        return NamedTextColor.GOLD;
    }
}
