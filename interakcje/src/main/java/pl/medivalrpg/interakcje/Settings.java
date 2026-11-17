// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** General settings ({@code settings.*}, {@code menu.*}). */
public record Settings(
        double broadcastRadius,
        long requestTimeoutMs,
        long globalCooldownMs,
        boolean useDisplayNames,
        List<String> vanishMetadata,
        String releaseCommand,
        List<String> releaseAliases,
        boolean menuShiftRightClick,
        boolean menuRequireEmptyHand,
        String menuFiller) {

    public static Settings from(FileConfiguration cfg) {
        ConfigurationSection s = cfg.getConfigurationSection("settings");
        if (s == null) {
            s = cfg.createSection("settings");
        }
        ConfigurationSection m = cfg.getConfigurationSection("menu");
        if (m == null) {
            m = cfg.createSection("menu");
        }
        String release = InteractionLoader.normalizeLabel(s.getString("release-command", "pusc"));
        if (release == null) {
            release = "pusc";
        }
        List<String> releaseAliases = new ArrayList<>();
        for (String a : s.getStringList("release-aliases")) {
            String n = InteractionLoader.normalizeLabel(a);
            if (n != null && !n.equals(release) && !releaseAliases.contains(n)) {
                releaseAliases.add(n);
            }
        }
        return new Settings(
                Math.max(1, s.getDouble("broadcast-radius", 16)),
                Math.max(5, s.getInt("request-timeout", 30)) * 1000L,
                Math.max(0, s.getLong("global-cooldown-ms", 1500)),
                s.getBoolean("use-display-names", false),
                s.isList("vanish-metadata") ? List.copyOf(s.getStringList("vanish-metadata")) : List.of("vanished"),
                release,
                List.copyOf(releaseAliases),
                m.getBoolean("shift-right-click", true),
                m.getBoolean("require-empty-hand", true),
                m.getString("filler", ""));
    }
}
