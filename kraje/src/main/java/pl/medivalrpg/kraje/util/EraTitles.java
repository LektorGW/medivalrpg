// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Era-dependent titles ({@code era-titles} in config.yml): the first word(s) of a country name are replaced <b>only for
 * display</b> depending on the country's era from the Postep plugin, e.g. in era 4 "Królestwo_Mazowsza" is shown as
 * "Rzeczpospolita_Mazowsza". The real name (commands, API, files) never changes.
 *
 * <p>Replacements are cumulative: for a country in era N the lists of eras N, N−1, …, 1 are tried in that order and
 * the first matching prefix wins (longer prefixes first within one era). A prefix matches case-insensitively, with
 * {@code _} and spaces treated alike, when it is followed by the end of the name, a space or {@code _}. Pure, immutable, thread-safe.</p>
 */
public final class EraTitles {

    public static final EraTitles NONE = new EraTitles(false, true, new TreeMap<>());

    private final boolean enabled;
    private final boolean npcOnly;
    /** era → (prefix, replacement) pairs, longest prefix first. */
    private final TreeMap<Integer, List<Map.Entry<String, String>>> byEra;

    public EraTitles(final boolean enabled, final boolean npcOnly, final Map<Integer, Map<String, String>> replacements) {
        this.enabled = enabled;
        this.npcOnly = npcOnly;
        this.byEra = new TreeMap<>();
        for (final Map.Entry<Integer, Map<String, String>> e : replacements.entrySet()) {
            final List<Map.Entry<String, String>> pairs = new ArrayList<>();
            for (final Map.Entry<String, String> r : e.getValue().entrySet()) {
                if (r.getKey() != null && !r.getKey().isBlank() && r.getValue() != null) {
                    pairs.add(Map.entry(r.getKey().strip(), r.getValue().strip()));
                }
            }
            pairs.sort(Comparator.comparingInt((Map.Entry<String, String> p) -> p.getKey().length()).reversed());
            this.byEra.put(e.getKey(), List.copyOf(pairs));
        }
    }

    /**
     * {@code era-titles} section: {@code enabled}, {@code npc-only}, {@code replacements.<era>.<prefix>: <title>}.
     * A missing section (old server config) falls back to {@code defaults} (the jar's config.yml), then to "off".
     */
    public static EraTitles parse(final ConfigurationSection section, final ConfigurationSection defaults) {
        final ConfigurationSection s = section != null ? section : defaults;
        if (s == null) {
            return NONE;
        }
        final Map<Integer, Map<String, String>> replacements = new TreeMap<>();
        final ConfigurationSection rs = s.getConfigurationSection("replacements");
        if (rs != null) {
            for (final String key : rs.getKeys(false)) {
                final int era;
                try {
                    era = Integer.parseInt(key.strip());
                } catch (final NumberFormatException e) {
                    continue;
                }
                final ConfigurationSection list = rs.getConfigurationSection(key);
                if (list == null) {
                    continue;
                }
                final Map<String, String> pairs = new java.util.LinkedHashMap<>();
                for (final String prefix : list.getKeys(false)) {
                    pairs.put(prefix, list.getString(prefix, ""));
                }
                replacements.put(era, pairs);
            }
        }
        return new EraTitles(s.getBoolean("enabled", true), s.getBoolean("npc-only", true), replacements);
    }

    public boolean enabled() {
        return this.enabled && !this.byEra.isEmpty();
    }

    /** Does this country get titles at all (enabled, NPC rule)? Cheap: callers skip the era lookup otherwise. */
    public boolean appliesTo(final boolean npc) {
        return this.enabled() && (npc || !this.npcOnly);
    }

    /** Name to display for a country in {@code era}. */
    public String apply(final String name, final boolean npc, final int era) {
        if (name == null || !this.appliesTo(npc)) {
            return name;
        }
        for (final List<Map.Entry<String, String>> pairs : this.byEra.headMap(era, true).descendingMap().values()) {
            for (final Map.Entry<String, String> p : pairs) {
                final String prefix = p.getKey();
                if (name.length() >= prefix.length()
                    && normalize(name.substring(0, prefix.length())).equals(normalize(prefix))
                    && (name.length() == prefix.length() || name.charAt(prefix.length()) == ' ' || name.charAt(prefix.length()) == '_')) {
                    final String rest = name.substring(prefix.length());
                    // keep the name's separator style: "Królestwo_X" → "Wielka_Republika_X" when the title has spaces
                    final String title = rest.startsWith("_") ? p.getValue().replace(' ', '_') : p.getValue();
                    return title + rest;
                }
            }
        }
        return name;
    }

    private static String normalize(final String s) {
        return s.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
