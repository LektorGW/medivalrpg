// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.help;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Search in the help catalog ({@code /pomoc szukaj <fraza>}): case and Polish diacritics are ignored. Pure. */
public final class HelpSearch {

    /** One search result with its category (for the tag shown before the line). */
    public record Hit(HelpCatalog.Category category, HelpCatalog.Entry entry, int score) {
    }

    /** One piece of a guide line: plain text, or a command written in backticks (clickable in game). */
    public record Segment(String text, boolean command) {
    }

    private HelpSearch() {
    }

    /** Lower case, no diacritics ({@code ł} → {@code l}), no leading slash, single spaces. */
    public static String normalize(final String text) {
        if (text == null) {
            return "";
        }
        final String lower = text.toLowerCase(Locale.ROOT).replace('ł', 'l');
        final String stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return stripped.replaceAll("[\\s/]+", " ").strip();
    }

    /**
     * All entries visible to the viewer that contain every word of {@code query}. Matches in the command itself
     * rank first, then matches in the description, then keywords/category; ties keep the catalog order.
     */
    public static List<Hit> search(final HelpCatalog catalog, final String query, final int limit,
                                   final Predicate<String> pluginEnabled, final Predicate<String> permission) {
        final String[] words = normalize(query).split(" ");
        if (words.length == 0 || words[0].isEmpty()) {
            return List.of();
        }
        final List<Hit> hits = new ArrayList<>();
        for (final HelpCatalog.Category c : catalog.visibleCategories(pluginEnabled, permission)) {
            final String categoryText = normalize(c.name() + " " + c.id());
            for (final HelpCatalog.Entry e : c.visibleEntries(pluginEnabled, permission)) {
                final String command = normalize(e.command());
                final String description = normalize(e.description() + " " + e.rank());
                final String extra = normalize(String.join(" ", e.keywords())) + " " + categoryText;
                final List<String> commandWords = List.of(command.split(" "));
                int score = 0;
                boolean all = true;
                for (final String w : words) {
                    if (commandWords.contains(w)) {
                        score += 5; // whole word: "dom" ranks /dom above /ustawdom
                    } else if (command.contains(w)) {
                        score += 3;
                    } else if (description.contains(w)) {
                        score += 2;
                    } else if (extra.contains(w)) {
                        score += 1;
                    } else {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    hits.add(new Hit(c, e, score));
                }
            }
        }
        // Stable sort: equal scores keep the catalog order.
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    /** Splits a guide line into plain text and {@code `komenda`} segments (unbalanced backtick = plain text). */
    public static List<Segment> segments(final String line) {
        final List<Segment> out = new ArrayList<>();
        int pos = 0;
        while (pos < line.length()) {
            final int open = line.indexOf('`', pos);
            final int close = open < 0 ? -1 : line.indexOf('`', open + 1);
            if (open < 0 || close < 0) {
                out.add(new Segment(line.substring(pos), false));
                break;
            }
            if (open > pos) {
                out.add(new Segment(line.substring(pos, open), false));
            }
            if (close > open + 1) {
                out.add(new Segment(line.substring(open + 1, close), true));
            }
            pos = close + 1;
        }
        return out;
    }
}
