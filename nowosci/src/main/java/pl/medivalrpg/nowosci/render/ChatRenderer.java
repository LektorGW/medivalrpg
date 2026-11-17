// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.nowosci.config.Messages;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Changelog;
import pl.medivalrpg.nowosci.model.Entry;
import pl.medivalrpg.nowosci.model.UnreadSelector.Selection;
import pl.medivalrpg.nowosci.render.EntryFormat.Medium;

/**
 * Chat views: the block after login, the welcome line for new players, the announcement, the paged
 * {@code /nowosci czat} view and {@code /nowosci pomoc}. Pure functions returning chat lines.
 */
public final class ChatRenderer {

    public static final String CMD_BOOK = "/nowosci";
    public static final String CMD_READ = "/nowosci przeczytane";

    /** {@code /nowosci pomoc} lines: help key, syntax, text put into the chat box, admin only. */
    public record HelpEntry(String id, String syntax, String suggest, boolean admin) {
    }

    public static final List<HelpEntry> HELP = List.of(
        new HelpEntry("ksiazka", "/nowosci", "/nowosci", false),
        new HelpEntry("czat", "/nowosci czat [strona]", "/nowosci czat ", false),
        new HelpEntry("przeczytane", "/nowosci przeczytane", "/nowosci przeczytane", false),
        new HelpEntry("pomoc", "/nowosci pomoc", "/nowosci pomoc", false),
        new HelpEntry("dodaj", "/nowosci dodaj <kategoria> <tytuł>", "/nowosci dodaj ", true),
        new HelpEntry("punkt", "/nowosci punkt <tekst>", "/nowosci punkt ", true),
        new HelpEntry("usun", "/nowosci usun <id>", "/nowosci usun ", true),
        new HelpEntry("oglos", "/nowosci oglos", "/nowosci oglos", true),
        new HelpEntry("reload", "/nowosci reload", "/nowosci reload", true));

    private final EntryFormat format;
    private final Messages m;
    private final NowosciConfig config;

    public ChatRenderer(final EntryFormat format) {
        this.format = format;
        this.m = format.messages();
        this.config = format.config();
    }

    // ------------------------------------------------------------------ login

    public List<Component> loginBlock(final Selection selection) {
        final List<Component> lines = new ArrayList<>();
        lines.add(this.m.get("login-header"));
        lines.add(this.m.get("login-count",
            Placeholder.unparsed("count", Integer.toString(selection.totalUnread())),
            Placeholder.unparsed("word", NowosciConfig.plural(this.config.pluralNewEntries, selection.totalUnread()))));
        for (final Entry e : selection.shown()) {
            final Component hover = this.entryHover(e).append(Component.newline()).append(this.m.get("login-entry-hover-click"));
            lines.add(this.m.get("login-entry", this.format.tags(e, Medium.CHAT))
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.runCommand(CMD_BOOK)));
            final int points = Math.min(this.config.pointsPerEntry, e.points().size());
            for (int i = 0; i < points; i++) {
                lines.add(this.m.get("login-entry-point",
                    Placeholder.unparsed("point", this.format.shortPoint(e.points().get(i), this.config.pointMaxChars)))
                    .hoverEvent(HoverEvent.showText(hover))
                    .clickEvent(ClickEvent.runCommand(CMD_BOOK)));
            }
        }
        final int hidden = selection.hiddenCount();
        if (hidden > 0) {
            lines.add(this.m.get("login-more",
                    Placeholder.unparsed("count", Integer.toString(hidden)),
                    Placeholder.unparsed("word", NowosciConfig.plural(this.config.pluralEntries, hidden)))
                .clickEvent(ClickEvent.runCommand(CMD_BOOK)));
        }
        lines.add(this.m.get("login-buttons",
            Placeholder.component("show_all", this.showAllButton()),
            Placeholder.component("dismiss", this.dismissButton())));
        if (!this.m.isEmpty("login-footer")) {
            lines.add(this.m.get("login-footer"));
        }
        return lines;
    }

    public Component welcome() {
        return this.m.get("welcome", Placeholder.component("show_all", this.showAllButton()));
    }

    public List<Component> announcement(final Entry e) {
        final List<Component> lines = new ArrayList<>();
        lines.add(this.m.get("announce-header"));
        lines.add(this.m.get("announce-title", this.format.tags(e, Medium.CHAT)));
        for (final String point : e.points()) {
            lines.add(this.m.get("announce-point", Placeholder.component("point", this.format.point(point, Medium.CHAT))));
        }
        lines.add(this.m.get("announce-buttons",
            Placeholder.component("show_all", this.showAllButton()),
            Placeholder.component("dismiss", this.dismissButton())));
        return lines;
    }

    /** Periodic reminder while {@code unread} entries wait for the player. */
    public Component reminder(final int unread) {
        return this.m.get("reminder-chat",
            Placeholder.unparsed("count", Integer.toString(unread)),
            Placeholder.unparsed("word", NowosciConfig.plural(this.config.pluralNewEntries, unread)),
            Placeholder.component("show_all", this.showAllButton()),
            Placeholder.component("dismiss", this.dismissButton()));
    }

    /** Boss bar text while {@code unread} entries wait for the player. */
    public Component reminderBar(final int unread) {
        return this.m.get("reminder-bossbar",
            Placeholder.unparsed("count", Integer.toString(unread)),
            Placeholder.unparsed("word", NowosciConfig.plural(this.config.pluralNewEntries, unread)));
    }

    private Component showAllButton() {
        return this.m.get("button-show-all")
            .hoverEvent(HoverEvent.showText(this.m.get("button-show-all-hover")))
            .clickEvent(ClickEvent.runCommand(CMD_BOOK));
    }

    private Component dismissButton() {
        return this.m.get("button-dismiss")
            .hoverEvent(HoverEvent.showText(this.m.get("button-dismiss-hover")))
            .clickEvent(ClickEvent.runCommand(CMD_READ));
    }

    /** Hover with the category, date, title and every point of {@code e}. */
    public Component entryHover(final Entry e) {
        final List<Component> points = new ArrayList<>();
        for (final String p : e.points()) {
            points.add(this.m.get("entry-hover-point", Placeholder.component("point", this.format.point(p, Medium.CHAT))));
        }
        return this.m.get("entry-hover", this.format.tags(e, Medium.CHAT),
            Placeholder.component("points", Component.join(JoinConfiguration.newlines(), points)));
    }

    // ------------------------------------------------------------------ /nowosci czat [strona]

    public int chatPages(final Changelog changelog) {
        final int per = this.config.chatEntriesPerPage;
        return Math.max(1, (changelog.size() + per - 1) / per);
    }

    public List<Component> chatPage(final Changelog changelog, final int requestedPage) {
        if (changelog.isEmpty()) {
            return List.of(this.m.get("chat-empty"));
        }
        final int pages = this.chatPages(changelog);
        final int page = Math.max(1, Math.min(requestedPage, pages));
        final int per = this.config.chatEntriesPerPage;
        final List<Entry> entries = changelog.entries();
        final List<Component> lines = new ArrayList<>();
        lines.add(this.m.get("chat-header", pageTags(page, pages)));
        final int from = (page - 1) * per;
        final int to = Math.min(entries.size(), from + per);
        for (int i = from; i < to; i++) {
            final Entry e = entries.get(i);
            if (i > from && !this.m.isEmpty("chat-entry-separator")) {
                lines.add(this.m.get("chat-entry-separator"));
            }
            lines.add(this.m.get("chat-entry-title", this.format.tags(e, Medium.CHAT)));
            for (final String p : e.points()) {
                lines.add(this.m.get("chat-entry-point", Placeholder.component("point", this.format.point(p, Medium.CHAT))));
            }
        }
        lines.add(this.m.get("chat-nav",
            pageTags(page, pages),
            Placeholder.component("prev", this.nav(page - 1, pages, "chat-prev")),
            Placeholder.component("next", this.nav(page + 1, pages, "chat-next")),
            Placeholder.component("book", this.m.get("chat-book")
                .hoverEvent(HoverEvent.showText(this.m.get("button-show-all-hover")))
                .clickEvent(ClickEvent.runCommand("/nowosci ksiazka")))));
        return lines;
    }

    private Component nav(final int target, final int pages, final String key) {
        if (target < 1 || target > pages) {
            return this.m.get(key + "-disabled");
        }
        return this.m.get(key)
            .hoverEvent(HoverEvent.showText(this.m.get("chat-page-hover", Placeholder.unparsed("page", Integer.toString(target)))))
            .clickEvent(ClickEvent.runCommand("/nowosci czat " + target));
    }

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver pageTags(final int page, final int pages) {
        return net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
            Placeholder.unparsed("page", Integer.toString(page)),
            Placeholder.unparsed("pages", Integer.toString(pages)));
    }

    // ------------------------------------------------------------------ /nowosci pomoc

    public List<Component> help(final boolean admin) {
        final List<Component> lines = new ArrayList<>();
        lines.add(this.m.get("help-header"));
        for (final HelpEntry h : HELP) {
            if (h.admin() && !admin) {
                continue;
            }
            final Component description = this.m.get("help-entries." + h.id());
            lines.add(this.m.get("help-line",
                    Placeholder.unparsed("syntax", h.syntax()),
                    Placeholder.component("description", description))
                .hoverEvent(HoverEvent.showText(this.m.get("help-hover",
                    Placeholder.unparsed("syntax", h.syntax()),
                    Placeholder.component("description", description))))
                .clickEvent(ClickEvent.suggestCommand(h.suggest())));
        }
        return lines;
    }
}
