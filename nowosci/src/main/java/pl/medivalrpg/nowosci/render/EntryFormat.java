// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import pl.medivalrpg.nowosci.config.Messages;
import pl.medivalrpg.nowosci.config.NowosciConfig;
import pl.medivalrpg.nowosci.model.Category;
import pl.medivalrpg.nowosci.model.Entry;

/**
 * Shared pieces of an entry for every view: placeholders ({@code <title>}, {@code <icon>}, {@code <cat_color>},
 * {@code <category>}, {@code <date>}, {@code <id>}) and points parsed from MiniMessage with the extra
 * {@code <cmd>} / {@code <hl>} tags, coloured for the chat or for the book page.
 */
public final class EntryFormat {

    public enum Medium { CHAT, BOOK }

    private final Messages messages;
    private final NowosciConfig config;

    public EntryFormat(final Messages messages, final NowosciConfig config) {
        this.messages = messages;
        this.config = config;
    }

    public Messages messages() {
        return this.messages;
    }

    public NowosciConfig config() {
        return this.config;
    }

    public String date(final Entry e) {
        return e.date() == null ? "—" : this.config.dateFormat.format(e.date());
    }

    /** Placeholders describing {@code e}; the category colour depends on the medium. */
    public TagResolver tags(final Entry e, final Medium medium) {
        final Category c = this.config.category(e.category());
        final TextColor color = medium == Medium.BOOK ? c.bookColor() : c.chatColor();
        return TagResolver.resolver(
            Placeholder.unparsed("title", e.title()),
            Placeholder.unparsed("icon", c.icon()),
            Placeholder.unparsed("category", c.name()),
            Placeholder.unparsed("date", this.date(e)),
            Placeholder.unparsed("id", Integer.toString(e.id())),
            Placeholder.styling("cat_color", color));
    }

    /** Tags usable inside changelog points. */
    public TagResolver pointTags(final Medium medium) {
        final boolean book = medium == Medium.BOOK;
        return TagResolver.resolver(
            TagResolver.resolver("cmd", Tag.styling(book ? this.config.bookCmd : this.config.chatCmd)),
            TagResolver.resolver("hl", Tag.styling(book ? this.config.bookHl : this.config.chatHl)));
    }

    /** One point (admin MiniMessage). */
    public Component point(final String miniMessage, final Medium medium) {
        return Messages.mini().deserialize(miniMessage, this.pointTags(medium));
    }

    /** Plain text of one point, shortened at a word boundary to {@code maxChars} (with "…"). */
    public String shortPoint(final String miniMessage, final int maxChars) {
        return shorten(plain(this.point(miniMessage, Medium.CHAT)), maxChars);
    }

    public static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    static String shorten(final String text, final int maxChars) {
        final String t = text.strip();
        if (t.length() <= maxChars) {
            return t;
        }
        final int cut = t.lastIndexOf(' ', maxChars - 1);
        final String head = (cut > maxChars / 2 ? t.substring(0, cut) : t.substring(0, maxChars - 1)).stripTrailing();
        return head.replaceAll("[,;:.—–-]+$", "") + "…";
    }
}
