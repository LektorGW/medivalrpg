// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * New interactions / emotes shipped in the jar reach a server whose {@code config.yml} was written by
 * an older version: entries of the jar defaults missing in the file are copied into the loaded
 * configuration (memory only, the file is not rewritten). An admin removes one with {@code enabled: false}
 * (the entry is then present and wins). Whole sections missing in the file ({@code npc-animations},
 * {@code coronation}, {@code knighting}) are read from the defaults by Bukkit anyway.
 */
public final class DefaultEntries {
    /** Sections whose child entries are merged one by one. */
    static final List<String> MERGED = List.of("interactions", "emotes.list");

    private DefaultEntries() {
    }

    /** @return paths of the entries added */
    public static List<String> addMissing(Configuration config, boolean enabled) {
        List<String> added = new ArrayList<>();
        Configuration defaults = config.getDefaults();
        if (!enabled || defaults == null) {
            return added;
        }
        for (String path : MERGED) {
            ConfigurationSection def = defaults.getConfigurationSection(path);
            ConfigurationSection mine = config.isConfigurationSection(path) ? config.getConfigurationSection(path) : null;
            if (def == null || mine == null) {
                continue; // the whole section comes from the defaults already
            }
            for (String key : def.getKeys(false)) {
                if (mine.contains(key, true)) {
                    continue;
                }
                ConfigurationSection src = def.getConfigurationSection(key);
                if (src == null) {
                    continue;
                }
                ConfigurationSection dst = mine.createSection(key);
                for (Map.Entry<String, Object> e : src.getValues(true).entrySet()) {
                    if (!(e.getValue() instanceof ConfigurationSection)) {
                        dst.set(e.getKey(), e.getValue());
                    }
                }
                added.add(path + "." + key);
            }
        }
        return added;
    }
}
