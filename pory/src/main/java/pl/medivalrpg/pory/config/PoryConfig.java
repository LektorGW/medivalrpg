// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.pory.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.pory.logic.SnowCoverage;
import pl.medivalrpg.pory.logic.VisualSeason;

/** Parsed config.yml with safe bounds (unit tested). */
public record PoryConfig(
    boolean enabled,
    VisualSeason fallbackSeason,
    int pollSeconds,
    Set<String> worlds,
    boolean biomes,
    int refreshPerPlayer,
    int refreshPerTick,
    int chunksPerPacket,
    boolean snow,
    double snowBudgetMs,
    int snowMaxQueue,
    SnowCoverage coverage,
    boolean snowyGround,
    Set<String> denyBlocks,
    boolean leaves,
    int leavesInterval,
    int leavesSamples,
    int leavesRadius
) {

    public static PoryConfig from(final ConfigurationSection c) {
        final VisualSeason fallback = VisualSeason.parse(c.getString("fallback-season", "lato"));
        final Set<String> worlds = new LinkedHashSet<>();
        for (final String w : c.getStringList("worlds")) {
            if (w != null && !w.isBlank()) {
                worlds.add(w.trim());
            }
        }
        final Map<VisualSeason, List<Double>> perDay = new EnumMap<>(VisualSeason.class);
        final ConfigurationSection cov = c.getConfigurationSection("snow.coverage");
        if (cov != null) {
            for (final String key : cov.getKeys(false)) {
                final VisualSeason s = VisualSeason.parse(key);
                if (s != null) {
                    perDay.put(s, new ArrayList<>(cov.getDoubleList(key)));
                }
            }
        }
        final Set<String> deny = new LinkedHashSet<>();
        for (final String b : c.getStringList("snow.deny-blocks")) {
            if (b != null && !b.isBlank()) {
                final String id = b.trim().toLowerCase(Locale.ROOT);
                deny.add(id.contains(":") ? id : "minecraft:" + id);
            }
        }
        return new PoryConfig(
            c.getBoolean("enabled", true),
            fallback == null ? VisualSeason.LATO : fallback,
            clamp(c.getInt("poll-seconds", 5), 1, 300),
            Set.copyOf(worlds),
            c.getBoolean("biomes.enabled", true),
            clamp(c.getInt("refresh.chunks-per-tick-per-player", 6), 1, 200),
            clamp(c.getInt("refresh.max-chunks-per-tick", 48), 1, 2000),
            clamp(c.getInt("refresh.chunks-per-packet", 6), 1, 64),
            c.getBoolean("snow.enabled", true),
            Math.max(0.1, Math.min(20.0, c.getDouble("snow.budget-ms-per-tick", 2.0))),
            clamp(c.getInt("snow.max-queue", 20000), 100, 1_000_000),
            new SnowCoverage(perDay),
            c.getBoolean("snow.snowy-ground", true),
            Set.copyOf(deny),
            c.getBoolean("leaves.enabled", true),
            clamp(c.getInt("leaves.interval-ticks", 10), 2, 200),
            clamp(c.getInt("leaves.samples-per-player", 10), 0, 100),
            clamp(c.getInt("leaves.radius", 14), 2, 48));
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
