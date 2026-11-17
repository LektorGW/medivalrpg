// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage3;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A vanilla-ratio processing recipe of a manufactory workshop: real inputs from the input chest → real output into
 * the output chest. Smelting recipes ({@code fuel}) burn real coal/charcoal: one piece per 8 batches, like a furnace.
 *
 * @param inputs material → pieces per batch
 */
public record Recipe(String id, String name, String station, Map<String, Integer> inputs, String output, int outputCount,
                     int seconds, boolean fuel, String profession) {

    public static final int SMELTS_PER_FUEL = 8;

    public static Map<String, Recipe> defaults() {
        final Map<String, Recipe> m = new LinkedHashMap<>();
        add(m, new Recipe("chleb", "Chleb", "CRAFTING_TABLE", Map.of("WHEAT", 3), "BREAD", 1, 30, false, "FARMER"));
        add(m, new Recipe("deski_debowe", "Deski dębowe", "CRAFTING_TABLE", Map.of("OAK_LOG", 1), "OAK_PLANKS", 4, 20, false, "MASON"));
        add(m, new Recipe("deski_swierkowe", "Deski świerkowe", "CRAFTING_TABLE", Map.of("SPRUCE_LOG", 1), "SPRUCE_PLANKS", 4, 20, false, "MASON"));
        add(m, new Recipe("deski_brzozowe", "Deski brzozowe", "CRAFTING_TABLE", Map.of("BIRCH_LOG", 1), "BIRCH_PLANKS", 4, 20, false, "MASON"));
        add(m, new Recipe("papier", "Papier", "CRAFTING_TABLE", Map.of("SUGAR_CANE", 3), "PAPER", 3, 30, false, "LIBRARIAN"));
        add(m, new Recipe("welna", "Wełna z nici", "LOOM", Map.of("STRING", 4), "WHITE_WOOL", 1, 30, false, "SHEPHERD"));
        add(m, new Recipe("sztabki_zelaza", "Sztabki żelaza", "FURNACE", Map.of("RAW_IRON", 1), "IRON_INGOT", 1, 20, true, "ARMORER"));
        add(m, new Recipe("sztabki_zlota", "Sztabki złota", "FURNACE", Map.of("RAW_GOLD", 1), "GOLD_INGOT", 1, 20, true, "ARMORER"));
        add(m, new Recipe("wegiel_drzewny", "Węgiel drzewny", "FURNACE", Map.of("OAK_LOG", 1), "CHARCOAL", 1, 20, true, "MASON"));
        add(m, new Recipe("cegly", "Cegły", "FURNACE", Map.of("CLAY_BALL", 1), "BRICK", 1, 20, true, "MASON"));
        add(m, new Recipe("szklo", "Szkło", "FURNACE", Map.of("SAND", 1), "GLASS", 1, 20, true, "MASON"));
        add(m, new Recipe("wedzona_wolowina", "Wędzona wołowina", "SMOKER", Map.of("BEEF", 1), "COOKED_BEEF", 1, 15, true, "BUTCHER"));
        add(m, new Recipe("wedzona_wieprzowina", "Wędzona wieprzowina", "SMOKER", Map.of("PORKCHOP", 1), "COOKED_PORKCHOP", 1, 15, true, "BUTCHER"));
        return m;
    }

    private static void add(final Map<String, Recipe> m, final Recipe r) {
        m.put(r.id(), r);
    }

    /** Recipes from {@code manufaktura.przepisy} (each fully replaces the default with the same id). */
    public static Map<String, Recipe> from(final ConfigurationSection sec) {
        final Map<String, Recipe> m = defaults();
        if (sec == null) {
            return m;
        }
        for (final String id : sec.getKeys(false)) {
            final ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null) {
                continue;
            }
            final Map<String, Integer> in = new LinkedHashMap<>();
            final ConfigurationSection w = r.getConfigurationSection("wejscie");
            if (w != null) {
                for (final String k : w.getKeys(false)) {
                    in.put(k.toUpperCase(Locale.ROOT), Math.max(1, w.getInt(k)));
                }
            }
            if (in.isEmpty() || !r.isString("wyjscie")) {
                continue;
            }
            m.put(id, new Recipe(id, r.getString("nazwa", id), r.getString("stanowisko", "CRAFTING_TABLE").toUpperCase(Locale.ROOT), Map.copyOf(in),
                r.getString("wyjscie").toUpperCase(Locale.ROOT), Math.max(1, r.getInt("ilosc", 1)), Math.max(5, r.getInt("sekundy", 30)),
                r.getBoolean("paliwo", false), r.getString("zawod", "NONE").toUpperCase(Locale.ROOT)));
        }
        return m;
    }
}
