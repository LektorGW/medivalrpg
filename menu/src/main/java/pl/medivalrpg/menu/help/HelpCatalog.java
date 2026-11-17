// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.help;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Player help catalog from {@code pomoc.yml}: categories ("Start", "Kraj", …) with a short "how to start" guide and
 * one entry per player command. The same file is the source of the in-game help ({@code /pomoc}, help GUI) and of
 * the Discord command channel ({@code tools/build_player_help_catalog.py}), so they never drift apart.
 *
 * <p>Pure (no server access): parsing never aborts, problems become Polish warnings.</p>
 */
public record HelpCatalog(String title, List<String> intro, Map<String, Category> categories, List<String> warnings) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+");
    /** Commands only: a leading slash and no MiniMessage/markup characters other than argument brackets. */
    private static final Pattern COMMAND = Pattern.compile("/[a-z0-9_:-]+( [^`\\n]*)?");

    public HelpCatalog {
        intro = List.copyOf(intro);
        categories = Collections.unmodifiableMap(new LinkedHashMap<>(categories));
        warnings = List.copyOf(warnings);
    }

    /**
     * One command.
     *
     * @param command     syntax shown to players, e.g. {@code /kraj zaloz <nazwa>} (never parsed as markup)
     * @param description short Polish description
     * @param rank        required rank/condition text, e.g. {@code Doradca+}; empty when none
     * @param insert      text put into the chat box (suggest) or run (run)
     * @param run         {@code true}: a click runs {@link #insert} directly (safe, argument-less commands)
     * @param requiresPlugins requirement tokens: plugin names ({@code requires-plugin}) and {@code /command} paths
     *                    ({@code requires-command}); the entry is hidden in-game while one is not met
     */
    public record Entry(String command, String description, String rank, String insert, boolean run,
                        List<String> requiresPlugins, String permission, List<String> keywords) {
        public Entry {
            requiresPlugins = List.copyOf(requiresPlugins);
            keywords = List.copyOf(keywords);
        }
    }

    /** One category (one GUI page set, one chat page, one or more Discord pages). */
    public record Category(String id, String name, String icon, String color, String description,
                           List<String> guide, List<String> requiresPlugins, String permission, List<Entry> entries) {
        public Category {
            guide = List.copyOf(guide);
            requiresPlugins = List.copyOf(requiresPlugins);
            entries = List.copyOf(entries);
        }

        /** Entries the viewer may use (plugin enabled and permission granted). */
        public List<Entry> visibleEntries(final Predicate<String> pluginEnabled, final Predicate<String> permission) {
            return this.entries.stream()
                .filter(e -> e.requiresPlugins().stream().allMatch(pluginEnabled))
                .filter(e -> e.permission() == null || permission.test(e.permission()))
                .toList();
        }

        public boolean visible(final Predicate<String> pluginEnabled, final Predicate<String> permission) {
            return this.requiresPlugins.stream().allMatch(pluginEnabled)
                && (this.permission == null || permission.test(this.permission))
                && (!this.visibleEntries(pluginEnabled, permission).isEmpty() || !this.guide.isEmpty());
        }
    }

    public Category category(final String id) {
        return id == null ? null : this.categories.get(id.toLowerCase(Locale.ROOT));
    }

    /** Categories the viewer may open, in file order. */
    public List<Category> visibleCategories(final Predicate<String> pluginEnabled, final Predicate<String> permission) {
        return this.categories.values().stream().filter(c -> c.visible(pluginEnabled, permission)).toList();
    }

    /**
     * Finds a category by id or by its name, ignoring case and Polish diacritics ({@code ludnosc} =
     * {@code Ludność i budowa} only by id; {@code "ekonomia"} by id or name).
     */
    public Category find(final String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        final String q = HelpSearch.normalize(query);
        for (final Category c : this.categories.values()) {
            if (c.id().equals(q) || HelpSearch.normalize(c.name()).equals(q)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ loading

    public static HelpCatalog load(final ConfigurationSection root) {
        final List<String> warnings = new ArrayList<>();
        final Map<String, Category> categories = new LinkedHashMap<>();
        if (root == null) {
            warnings.add("Pusty plik pomoc.yml.");
            return new HelpCatalog("Pomoc", List.of(), categories, warnings);
        }
        final ConfigurationSection cats = root.getConfigurationSection("categories");
        if (cats == null) {
            warnings.add("Brak sekcji 'categories' w pomoc.yml.");
        } else {
            for (final String key : cats.getKeys(false)) {
                final String id = key.toLowerCase(Locale.ROOT);
                final ConfigurationSection s = cats.getConfigurationSection(key);
                if (s == null || !ID.matcher(id).matches()) {
                    warnings.add("categories." + key + ": niepoprawna kategoria – pomijam.");
                    continue;
                }
                categories.put(id, parseCategory(id, s, warnings));
            }
        }
        return new HelpCatalog(root.getString("title", "Pomoc"), strings(root, "intro"), categories, warnings);
    }

    static Category parseCategory(final String id, final ConfigurationSection s, final List<String> warnings) {
        final String where = "categories." + id;
        final List<String> plugins = names(s, "requires-plugin");
        final List<Entry> entries = new ArrayList<>();
        final List<Map<?, ?>> raw = s.getMapList("commands");
        for (int i = 0; i < raw.size(); i++) {
            final Entry e = parseEntry(where + ".commands[" + i + "]", raw.get(i), warnings);
            if (e != null) {
                entries.add(e);
            }
        }
        if (entries.isEmpty() && strings(s, "guide").isEmpty()) {
            warnings.add(where + ": kategoria nie ma komend ani poradnika.");
        }
        return new Category(id,
            s.getString("name", id),
            upper(s.getString("icon", "BOOK")),
            s.getString("color", "gold"),
            s.getString("description", ""),
            strings(s, "guide"),
            plugins,
            blankToNull(s.getString("permission")),
            entries);
    }

    static Entry parseEntry(final String where, final Map<?, ?> map, final List<String> warnings) {
        final String command = text(map.get("command")).strip();
        final String description = text(map.get("description")).strip();
        if (!COMMAND.matcher(command).matches()) {
            warnings.add(where + ": niepoprawna komenda '" + command + "' – pomijam.");
            return null;
        }
        if (description.isEmpty()) {
            warnings.add(where + ": komenda " + command + " nie ma opisu – pomijam.");
            return null;
        }
        final String click = text(map.get("click")).strip().toLowerCase(Locale.ROOT);
        final boolean run = "run".equals(click) || "wykonaj".equals(click);
        if (run && !argumentless(command)) {
            warnings.add(where + ": click: run tylko dla komend bez argumentów (" + command + ") – używam wpisania.");
        }
        final boolean safeRun = run && argumentless(command);
        String insert = text(map.get("insert"));
        if (insert.isBlank()) {
            insert = safeRun ? command : suggestion(command);
        }
        // requires-command: the entry of a prepared module stays hidden in-game until its command exists
        // (token "/path", checked like requires-plugin by the runtime predicate, see Requirements).
        final List<String> requirements = new ArrayList<>(list(map.get("requires-plugin")));
        for (final String path : list(map.get("requires-command"))) {
            if (pl.medivalrpg.menu.integration.CommandPaths.literal(path)) {
                requirements.add(pl.medivalrpg.menu.integration.Requirements.commandToken(path));
            } else {
                warnings.add(where + ": requires-command '" + path + "' – podaj same słowa komendy – pomijam.");
            }
        }
        return new Entry(command, description, text(map.get("rank")).strip(), insert, safeRun,
            requirements, blankToNull(text(map.get("permission"))), list(map.get("keywords")));
    }

    /**
     * Text put into the chat box: the syntax up to the first argument ({@code <…>}, {@code […]} or a choice
     * {@code a|b}), with a trailing space when an argument follows ({@code /kraj zaloz <nazwa>} →
     * {@code "/kraj zaloz "}, {@code /glos szept|krzyk} → {@code "/glos "}).
     */
    public static String suggestion(final String command) {
        final String[] words = command.strip().split(" +");
        final StringBuilder out = new StringBuilder();
        for (final String w : words) {
            if (w.contains("<") || w.contains("[") || w.contains("|")) {
                return out.append(' ').toString();
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(w);
        }
        return out.toString();
    }

    /** {@code true} when the syntax has no arguments at all (only then a click may run it directly). */
    static boolean argumentless(final String command) {
        return suggestion(command).equals(command.strip());
    }

    // ------------------------------------------------------------------ helpers

    private static String text(final Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static List<String> list(final Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).map(String::strip).filter(x -> !x.isEmpty()).toList();
        }
        final String s = String.valueOf(o).strip();
        return s.isEmpty() ? List.of() : List.of(s);
    }

    static List<String> strings(final ConfigurationSection s, final String key) {
        if (s.isList(key)) {
            final List<String> out = new ArrayList<>();
            for (final Object o : s.getList(key, List.of())) {
                out.add(o == null ? "" : String.valueOf(o));
            }
            return out;
        }
        final String single = s.getString(key);
        return single == null ? List.of() : List.of(single);
    }

    private static List<String> names(final ConfigurationSection s, final String key) {
        return strings(s, key).stream().map(String::strip).filter(n -> !n.isEmpty()).toList();
    }

    private static String upper(final String s) {
        return s == null ? "BOOK" : s.strip().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(final String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
