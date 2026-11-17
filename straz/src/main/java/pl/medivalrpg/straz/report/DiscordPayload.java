// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.straz.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;
import pl.medivalrpg.straz.alert.Alert;

/**
 * Discord webhook bodies (pure). Embeds respect Discord limits (title 256, description 4096, field value 1024,
 * 25 fields, 6000 characters in total). Mentions never ping: {@code allowed_mentions.parse = []} and the text
 * {@code @everyone}/{@code @here} is neutralized with a full-width at sign.
 */
public final class DiscordPayload {

    static final int GREEN = 0x2E7D32;
    static final int AMBER = 0xF9A825;
    static final int RED = 0xC62828;
    static final int BLUE = 0x1565C0;
    static final int TOTAL_LIMIT = 6000;
    static final String FOOTER = "Straż MEDIVALRPG · Maciej Kaczmarczyk — lektoro.pl";

    private static final Pattern MASS = Pattern.compile("@(everyone|here)", Pattern.CASE_INSENSITIVE);

    private DiscordPayload() {
    }

    /** {@code @everyone} → {@code ＠everyone} (same length, never a mention). */
    public static String neutralize(final String s) {
        return s == null ? "" : MASS.matcher(s).replaceAll("＠$1");
    }

    public static JsonObject report(final ReportFormatter.Report r, final String username) {
        final JsonObject embed = new JsonObject();
        embed.addProperty("title", clip(neutralize(r.title()), 256));
        embed.addProperty("description", clip(neutralize(r.statusLine()), 4096));
        embed.addProperty("color", r.ok() ? GREEN : r.issues().stream().anyMatch(DiscordPayload::severe) ? RED : AMBER);
        embed.addProperty("timestamp", Instant.ofEpochMilli(r.epochMs()).toString());
        final JsonObject footer = new JsonObject();
        footer.addProperty("text", FOOTER);
        embed.add("footer", footer);
        int budget = TOTAL_LIMIT - embed.get("title").getAsString().length() - embed.get("description").getAsString().length()
            - FOOTER.length();
        final JsonArray fields = new JsonArray();
        final List<ReportFormatter.Section> sections = r.sections();
        for (int i = 0; i < sections.size() && i < 25; i++) {
            final ReportFormatter.Section s = sections.get(i);
            final int reserve = 60 * (sections.size() - i - 1); // leave room for the remaining sections
            final int room = Math.min(1024, budget - s.title().length() - reserve);
            if (room < 20) {
                break;
            }
            final String value = clip(neutralize(s.body()), room);
            final JsonObject f = new JsonObject();
            f.addProperty("name", clip(neutralize(s.title()), 256));
            f.addProperty("value", value.isBlank() ? "—" : value);
            f.addProperty("inline", false);
            fields.add(f);
            budget -= s.title().length() + value.length();
        }
        embed.add("fields", fields);
        return wrap(embed, username);
    }

    public static JsonObject alert(final Alert a, final ZoneId zone, final String username, final int suppressedBefore) {
        final JsonObject embed = new JsonObject();
        embed.addProperty("title", clip(neutralize(a.icon() + " ALERT — " + a.title()), 256));
        final StringBuilder d = new StringBuilder();
        for (final String l : a.lines()) {
            d.append(l).append('\n');
        }
        d.append("\n").append(ReportFormatter.HM.format(Instant.ofEpochMilli(a.time()).atZone(zone)))
            .append(" · /straz status w grze");
        if (suppressedBefore > 0) {
            d.append("\n(pominięte wcześniej przez limit alertów: ").append(suppressedBefore).append(")");
        }
        embed.addProperty("description", clip(neutralize(d.toString().strip()), 4000));
        embed.addProperty("color", a.problem() ? RED : GREEN);
        embed.addProperty("timestamp", Instant.ofEpochMilli(a.time()).toString());
        final JsonObject footer = new JsonObject();
        footer.addProperty("text", FOOTER);
        embed.add("footer", footer);
        return wrap(embed, username);
    }

    public static JsonObject text(final String content, final String username) {
        final JsonObject root = base(username);
        root.addProperty("content", clip(neutralize(content), 2000));
        return root;
    }

    private static JsonObject wrap(final JsonObject embed, final String username) {
        final JsonObject root = base(username);
        final JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);
        return root;
    }

    private static JsonObject base(final String username) {
        final JsonObject root = new JsonObject();
        if (username != null && !username.isBlank()) {
            root.addProperty("username", clip(neutralize(username), 80));
        }
        final JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        root.add("allowed_mentions", mentions);
        return root;
    }

    private static boolean severe(final String issue) {
        return issue.startsWith("rozbieżność") || issue.startsWith("nieudana kopia") || issue.startsWith("budowa kolei")
            || issue.startsWith("wojsko ponad") || issue.startsWith("kopia:");
    }

    static String clip(final String s, final int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
