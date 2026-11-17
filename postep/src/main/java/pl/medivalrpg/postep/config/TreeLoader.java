// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.postep.model.Discovery;

/** Reads the {@code odkrycia} section of {@code drzewko.yml}. */
public final class TreeLoader {

    private TreeLoader() {
    }

    /** Discoveries in file order; {@code problems} receives Polish notes about skipped entries. */
    public static List<Discovery> load(final ConfigurationSection root, final List<String> problems) {
        final List<Discovery> out = new ArrayList<>();
        final ConfigurationSection section = root == null ? null : root.getConfigurationSection("odkrycia");
        if (section == null) {
            problems.add("brak sekcji 'odkrycia' w drzewko.yml");
            return out;
        }
        for (final String rawId : section.getKeys(false)) {
            final ConfigurationSection s = section.getConfigurationSection(rawId);
            if (s == null) {
                problems.add("odkrycie " + rawId + ": to nie jest sekcja");
                continue;
            }
            final String id = rawId.toLowerCase(Locale.ROOT);
            final List<String> requires = new ArrayList<>();
            for (final String r : s.getStringList("wymaga")) {
                if (!r.isBlank()) {
                    requires.add(r.trim().toLowerCase(Locale.ROOT));
                }
            }
            final Map<String, Double> effects = new LinkedHashMap<>();
            final ConfigurationSection eff = s.getConfigurationSection("efekty");
            if (eff != null) {
                for (final String key : eff.getKeys(false)) {
                    if (eff.isDouble(key) || eff.isInt(key) || eff.isLong(key)) {
                        effects.put(key.toLowerCase(Locale.ROOT), eff.getDouble(key));
                    } else {
                        problems.add("odkrycie " + id + ": efekt " + key + " nie jest liczbą");
                    }
                }
            }
            out.add(new Discovery(id,
                s.getString("nazwa", id),
                s.getInt("epoka", 1),
                s.getLong("koszt", 100),
                requires,
                s.getBoolean("wymagane-do-epoki", false),
                s.getString("kategoria", "nauka").toLowerCase(Locale.ROOT),
                s.getString("ikona", "PAPER"),
                s.getString("opis", ""),
                s.getString("efekt", ""),
                effects));
        }
        return out;
    }
}
