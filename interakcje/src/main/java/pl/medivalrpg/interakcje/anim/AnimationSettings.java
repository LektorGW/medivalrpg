// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/** {@code animations:} in config.yml. */
public record AnimationSettings(
        boolean enabled,
        boolean installModels,
        boolean reloadBetterModel,
        boolean alignPairs,
        double alignMaxShift,
        double maxHeightDifference,
        int blendInTicks,
        double moveTolerance,
        boolean stopOnDamage,
        boolean fixBodyYaw,
        boolean onlyForPackUsers,
        boolean showToSelf,
        boolean resumeHold,
        String customCatalog) {

    public static AnimationSettings from(ConfigurationSection s) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        return new AnimationSettings(
                s.getBoolean("enabled", true),
                s.getBoolean("install-models", true),
                s.getBoolean("reload-bettermodel", true),
                s.getBoolean("align-pairs", true),
                clamp(s.getDouble("align-max-shift", 1.25), 0.0, 3.0),
                clamp(s.getDouble("max-height-difference", 0.5), 0.0, 2.0),
                (int) clamp(s.getInt("blend-in-ticks", 3), 0, 20),
                clamp(s.getDouble("move-tolerance", 0.3), 0.05, 2.0),
                s.getBoolean("stop-on-damage", true),
                s.getBoolean("fix-body-yaw", true),
                s.getBoolean("only-for-pack-users", true),
                s.getBoolean("show-to-self", true),
                s.getBoolean("resume-hold", true),
                s.getString("custom-catalog", "animations-custom.json"));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
