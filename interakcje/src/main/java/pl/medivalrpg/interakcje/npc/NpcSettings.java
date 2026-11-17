// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.npc;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** {@code npc-animations:} in config.yml. */
public record NpcSettings(
        boolean enabled,
        double range,
        double hysteresis,
        int maxActive,
        double budgetMs,
        int scanTicks,
        int maxStartsPerScan,
        boolean persist,
        boolean animateWithoutSkin,
        Set<String> entityTypes) {

    public static NpcSettings from(ConfigurationSection s) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        Set<String> types = new LinkedHashSet<>();
        List<String> raw = s.isList("entity-types") ? s.getStringList("entity-types") : List.of("MANNEQUIN");
        for (String t : raw) {
            types.add(t.trim().toUpperCase(Locale.ROOT));
        }
        return new NpcSettings(
                s.getBoolean("enabled", true),
                clamp(s.getDouble("range", 32.0), 4.0, 96.0),
                clamp(s.getDouble("hysteresis", 4.0), 0.0, 16.0),
                (int) clamp(s.getInt("max-active", 40), 0, 400),
                clamp(s.getDouble("budget-ms", 2.0), 0.1, 20.0),
                (int) clamp(s.getInt("scan-interval-ticks", 10), 2, 200),
                (int) clamp(s.getInt("max-starts-per-scan", 1), 1, 200),
                s.getBoolean("persist", true),
                s.getBoolean("animate-without-skin", false),
                Set.copyOf(types));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
