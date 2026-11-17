// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.tablist;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drops header / footer lines whose data is not available (no Ludnosc → no season, no Skarbiec → no balance...).
 *
 * <ul>
 *   <li>A part of a line written as {@code [[ ... ]]} is optional: it disappears when it uses a missing placeholder.</li>
 *   <li>A line that uses a missing placeholder outside of {@code [[ ]]} disappears completely.</li>
 *   <li>A line that had optional parts and shows nothing after removing them (no text, no placeholder — only
 *       formatting tags) disappears too. A line written empty on purpose ({@code ""}) stays (spacing).</li>
 * </ul>
 * Pure logic, no Bukkit.
 */
public final class TabLines {

    private static final Pattern OPTIONAL = Pattern.compile("\\[\\[(.*?)]]", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^<>]*>");

    /** Every placeholder of the extended header / footer; they count as visible content of a line. */
    public static final Set<String> PLACEHOLDERS = Set.of("player", "online", "max", "afk_count", "ping", "tps", "date",
        "game_time", "game_day", "season", "season_icon", "season_day", "season_days", "country", "rank", "rank_icon",
        "era", "era_icon", "balance", "news", "tip", "map_link", "map_url", "panel_link", "panel_url");

    private TabLines() {
    }

    /** Lines to show, in order. */
    public static List<String> filter(final List<String> lines, final Set<String> missing) {
        final List<String> out = new ArrayList<>(lines.size());
        for (final String line : lines) {
            final String kept = line(line, missing);
            if (kept != null) {
                out.add(kept);
            }
        }
        return out;
    }

    /** One line after removing unavailable parts, {@code null} when the whole line is to be dropped. */
    public static String line(final String line, final Set<String> missing) {
        if (line == null) {
            return null;
        }
        final Matcher m = OPTIONAL.matcher(line);
        final StringBuilder sb = new StringBuilder(line.length());
        boolean hadOptional = false;
        int last = 0;
        while (m.find()) {
            hadOptional = true;
            final String outside = line.substring(last, m.start());
            if (usesAny(outside, missing)) {
                return null;
            }
            sb.append(outside);
            final String part = m.group(1);
            if (!usesAny(part, missing)) {
                sb.append(part);
            }
            last = m.end();
        }
        final String tail = line.substring(last);
        if (usesAny(tail, missing)) {
            return null;
        }
        sb.append(tail);
        final String result = sb.toString();
        if (hadOptional && !visible(result)) {
            return null;
        }
        return result;
    }

    /** Does the MiniMessage text use {@code <name>} or {@code <name:...>} for one of the names? */
    public static boolean usesAny(final String text, final Set<String> names) {
        if (text.isEmpty() || names.isEmpty()) {
            return false;
        }
        for (final String name : names) {
            if (uses(text, name)) {
                return true;
            }
        }
        return false;
    }

    /** Does the MiniMessage text use the placeholder {@code <name>} (or {@code <name:arg>})? */
    public static boolean uses(final String text, final String name) {
        int from = 0;
        final String open = "<" + name;
        while (true) {
            final int i = text.indexOf(open, from);
            if (i < 0) {
                return false;
            }
            final int after = i + open.length();
            if (after < text.length()) {
                final char c = text.charAt(after);
                if (c == '>' || c == ':') {
                    return true;
                }
            }
            from = after;
        }
    }

    /** Is there anything besides formatting tags and whitespace (text or a placeholder)? */
    static boolean visible(final String miniMessage) {
        return !TAG.matcher(miniMessage).replaceAll("").isBlank() || usesAny(miniMessage, PLACEHOLDERS);
    }
}
