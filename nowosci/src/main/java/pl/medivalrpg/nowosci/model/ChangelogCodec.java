// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.nowosci.model;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads and writes {@code changelog.yml}. Parsing is lenient: a broken entry is skipped (or repaired) and
 * described in {@link Result#warnings()}, so one typo never hides the whole changelog. No Bukkit server calls.
 *
 * <pre>
 * next-id: 6
 * entries:
 *   - id: 1
 *     date: "2026-10-25"
 *     title: "Kraje"
 *     category: nowe
 *     points: ["...", "..."]
 * </pre>
 */
public final class ChangelogCodec {

    public static final String KEY_ENTRIES = "entries";
    public static final String KEY_NEXT_ID = "next-id";

    public static final List<String> HEADER = List.of(
        "Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl",
        "Changelog serwera MEDIVALRPG — to widzą gracze po zalogowaniu i pod /nowosci.",
        "",
        "Każdy wpis: id (rośnie), date (\"RRRR-MM-DD\"), title (zwykły tekst), category (nowe | zmiana | poprawka),",
        "points (lista punktów w MiniMessage; dodatkowe tagi <cmd>/komenda</cmd> i <hl>wyróżnienie</hl>).",
        "next-id — następny wolny numer (nie zmniejszaj go). Po ręcznej edycji: /nowosci reload.");

    /**
     * @param ok {@code false} when the file is not valid YAML at all; the changelog is then empty and must not be
     *           saved over the broken file
     */
    public record Result(Changelog changelog, List<String> warnings, boolean ok) {
    }

    private ChangelogCodec() {
    }

    public static Result parse(final String yaml) {
        final YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (final org.bukkit.configuration.InvalidConfigurationException e) {
            return new Result(Changelog.empty(), List.of("Niepoprawny YAML: " + e.getMessage()), false);
        }
        return parse(config);
    }

    public static Result parse(final YamlConfiguration config) {
        if (config.contains(KEY_ENTRIES) && !config.isList(KEY_ENTRIES)) {
            return new Result(Changelog.empty(), List.of("'" + KEY_ENTRIES + "' musi być listą wpisów (- id: ...)"), false);
        }
        final List<String> warnings = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        final Set<Integer> ids = new HashSet<>();
        final List<Map<?, ?>> raw = config.getMapList(KEY_ENTRIES);
        for (int i = 0; i < raw.size(); i++) {
            final Map<?, ?> map = raw.get(i);
            final String where = "wpis nr " + (i + 1) + " na liście";
            final Integer id = toInt(map.get("id"));
            if (id == null || id <= 0) {
                warnings.add(where + ": brak poprawnego 'id' (liczba > 0) — pominięty");
                continue;
            }
            if (!ids.add(id)) {
                warnings.add(where + ": powtórzone id " + id + " — pominięty");
                continue;
            }
            final Object titleRaw = map.get("title");
            final String title = titleRaw == null ? "" : titleRaw.toString().trim();
            if (title.isEmpty()) {
                warnings.add("wpis #" + id + ": brak 'title' — pominięty");
                continue;
            }
            final Object categoryRaw = map.get("category");
            String category = categoryRaw == null ? "" : categoryRaw.toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (category.isEmpty()) {
                warnings.add("wpis #" + id + ": brak 'category' — użyto 'nowe'");
                category = "nowe";
            }
            final LocalDate date = toDate(map.get("date"));
            if (date == null) {
                warnings.add("wpis #" + id + ": brak albo zła 'date' (oczekiwano \"RRRR-MM-DD\")");
            }
            entries.add(new Entry(id, date, title, category, toPoints(map.get("points"))));
        }
        final Integer storedNext = toInt(config.get(KEY_NEXT_ID));
        return new Result(new Changelog(entries, storedNext == null ? 1 : storedNext), List.copyOf(warnings), true);
    }

    /** Serialized changelog.yml (oldest entry first, so new entries are appended at the bottom). */
    public static String write(final Changelog changelog) {
        final YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(HEADER);
        config.set(KEY_NEXT_ID, changelog.nextId());
        final List<Map<String, Object>> list = new ArrayList<>();
        for (final Entry e : changelog.entries().reversed()) {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", e.id());
            if (e.date() != null) {
                map.put("date", e.date().toString());
            }
            map.put("title", e.title());
            map.put("category", e.category());
            map.put("points", new ArrayList<>(e.points()));
            list.add(map);
        }
        config.set(KEY_ENTRIES, list);
        return config.saveToString();
    }

    static Integer toInt(final Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static LocalDate toDate(final Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof Date d) { // unquoted 2026-10-25 → SnakeYAML timestamp at UTC midnight
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value != null) {
            try {
                return LocalDate.parse(value.toString().trim());
            } catch (final DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static List<String> toPoints(final Object value) {
        final List<String> points = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (final Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    points.add(o.toString());
                }
            }
        } else if (value != null && !value.toString().isBlank()) {
            points.add(value.toString());
        }
        return points;
    }
}
