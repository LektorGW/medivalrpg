// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import pl.medivalrpg.nowosci.model.Category;
import pl.medivalrpg.nowosci.model.Entry;

/**
 * Turns a changelog entry into a Discord webhook payload (one embed). Points are MiniMessage: {@code <cmd>} becomes
 * inline code, {@code <hl>} bold, every other tag is dropped and Discord markdown typed by players is escaped.
 * Pure function, no I/O.
 */
public final class DiscordFormat {

    /** Discord limits: embed title 256, description 4096, content 2000. */
    static final int MAX_TITLE = 256;
    static final int MAX_DESCRIPTION = 4096;

    private static final Pattern CMD = Pattern.compile("<cmd>(.*?)</cmd>", Pattern.DOTALL);
    private static final Pattern HL = Pattern.compile("<hl>(.*?)</hl>", Pattern.DOTALL);
    /** Inline markdown; {@code >} only quotes at a line start, and every point starts with "• ". */
    private static final Pattern MARKDOWN = Pattern.compile("([\\\\*_~`|])");
    private static final String CODE_OPEN = "\u0000C";
    private static final String CODE_CLOSE = "\u0000c";
    private static final String BOLD = "\u0000B";

    private DiscordFormat() {
    }

    /**
     * @param username  webhook display name ({@code ""} = the webhook's own)
     * @param mention   text before the embed, e.g. {@code @everyone} or {@code <@&roleId>} ({@code ""} = none)
     */
    public static JsonObject payload(final Entry entry, final Category category, final DateTimeFormatter dates,
                                     final String username, final String avatarUrl, final String mention) {
        final JsonObject root = new JsonObject();
        if (!username.isBlank()) {
            root.addProperty("username", username);
        }
        if (!avatarUrl.isBlank()) {
            root.addProperty("avatar_url", avatarUrl);
        }
        if (!mention.isBlank()) {
            root.addProperty("content", mention);
            final JsonObject allowed = new JsonObject();
            final JsonArray parse = new JsonArray();
            if (mention.contains("@everyone") || mention.contains("@here")) {
                parse.add("everyone");
            }
            if (mention.contains("<@&")) {
                parse.add("roles");
            }
            allowed.add("parse", parse);
            root.add("allowed_mentions", allowed);
        }

        final JsonObject embed = new JsonObject();
        embed.addProperty("title", cut(category.icon() + " " + entry.title(), MAX_TITLE));
        final StringBuilder description = new StringBuilder();
        for (final String point : entry.points()) {
            description.append("• ").append(point(point)).append('\n');
        }
        embed.addProperty("description", cut(description.toString().strip(), MAX_DESCRIPTION));
        embed.addProperty("color", category.chatColor().value());
        final JsonObject footer = new JsonObject();
        footer.addProperty("text", category.name() + (entry.date() == null ? "" : " · " + dates.format(entry.date()))
            + " · wpis #" + entry.id());
        embed.add("footer", footer);
        final JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);
        return root;
    }

    /** One changelog point as Discord markdown. */
    static String point(final String miniMessage) {
        String s = CMD.matcher(miniMessage).replaceAll(m -> CODE_OPEN + m.group(1) + CODE_CLOSE);
        s = HL.matcher(s).replaceAll(m -> BOLD + m.group(1) + BOLD);
        s = MiniMessage.miniMessage().stripTags(s).replace("\\<", "<");
        // Markdown is escaped outside code spans only; inside them backslashes would show literally.
        final StringBuilder out = new StringBuilder();
        int from = 0;
        while (from < s.length()) {
            final int open = s.indexOf(CODE_OPEN, from);
            if (open < 0) {
                out.append(escape(s.substring(from)));
                break;
            }
            final int close = s.indexOf(CODE_CLOSE, open);
            final int end = close < 0 ? s.length() : close;
            out.append(escape(s.substring(from, open)))
                .append('`').append(s.substring(open + CODE_OPEN.length(), end).replace("`", "'")).append('`');
            from = close < 0 ? s.length() : close + CODE_CLOSE.length();
        }
        return out.toString();
    }

    private static String escape(final String text) {
        return MARKDOWN.matcher(text).replaceAll("\\\\$1").replace(BOLD, "**");
    }

    private static String cut(final String text, final int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
