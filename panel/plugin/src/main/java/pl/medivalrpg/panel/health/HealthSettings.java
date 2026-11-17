// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.health;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code health.*} of config.yml (server health: TPS, MSPT, NPC load). Immutable.
 *
 * @param enabled          sampling on/off
 * @param intervalSeconds  probe interval (5..60)
 * @param fineMinutes      how long probes are kept at full resolution (10..360)
 * @param coarseHours      how long one-minute aggregates are kept (1..168)
 * @param npcScan          count plugin NPCs (walks the loaded entities)
 * @param npcScanEvery     scan NPCs every N probes (between scans the last result is reused)
 * @param saveMinutes      periodic save of {@code plugins/Panel/health.json} (0 = only on shutdown)
 * @param maxPoints        most points in one {@code admin.health} answer (more are merged)
 * @param countryLimit     most countries in the per-country NPC breakdown
 * @param thresholds       warning thresholds
 * @param alerts           in-game alerts
 */
public record HealthSettings(boolean enabled, int intervalSeconds, int fineMinutes, int coarseHours, boolean npcScan, int npcScanEvery,
                             int saveMinutes, int maxPoints, int countryLimit, HealthWarnings.Thresholds thresholds, Alerts alerts) {

    /**
     * @param enabled     send alerts in game
     * @param permission  Bukkit permission of recipients (default op)
     * @param panelAdmins also online panel admins ({@code admins})
     * @param cooldownMs  per warning code and level
     * @param recovery    message when a warning ends (only after an alert was sent)
     * @param console     log warnings to the console
     */
    public record Alerts(boolean enabled, String permission, boolean panelAdmins, long cooldownMs, boolean recovery, boolean console) {
    }

    public long intervalMs() {
        return this.intervalSeconds * 1000L;
    }

    public static HealthSettings from(final ConfigurationSection c) {
        if (c == null) {
            return from(new org.bukkit.configuration.MemoryConfiguration());
        }
        final ConfigurationSection t = c.getConfigurationSection("thresholds");
        final Map<String, Integer> perPlugin = new LinkedHashMap<>();
        final ConfigurationSection pp = t == null ? null : t.getConfigurationSection("npc-plugin");
        if (pp != null) {
            for (final String key : pp.getKeys(false)) {
                final String id = key.toLowerCase(Locale.ROOT);
                final int v = pp.getInt(key, 0);
                if (NpcCensus.PLUGINS.contains(id) && v > 0) {
                    perPlugin.put(id, v);
                }
            }
        }
        final HealthWarnings.Thresholds th = new HealthWarnings.Thresholds(
            t == null ? 18.0 : t.getDouble("tps", 18.0),
            t == null ? 15.0 : t.getDouble("tps-critical", 15.0),
            t == null ? 45.0 : t.getDouble("mspt", 45.0),
            t == null ? 50.0 : t.getDouble("mspt-critical", 50.0),
            t == null ? 2000 : t.getInt("npc", 2000),
            t == null ? 3000 : t.getInt("npc-critical", 3000),
            t == null ? 8000 : t.getInt("entities", 8000),
            t == null ? 12000 : t.getInt("entities-critical", 12000),
            perPlugin,
            Math.max(1, Math.min(30, t == null ? 2 : t.getInt("sustain-samples", 2))));
        final ConfigurationSection a = c.getConfigurationSection("alerts");
        final String perm = a == null ? "panel.health.alerts" : a.getString("permission", "panel.health.alerts");
        final Alerts alerts = new Alerts(
            a == null || a.getBoolean("enabled", true),
            perm == null || perm.isBlank() ? "panel.health.alerts" : perm.trim(),
            a == null || a.getBoolean("panel-admins", true),
            Math.max(1, Math.min(1440, a == null ? 15 : a.getInt("cooldown-minutes", 15))) * 60_000L,
            a == null || a.getBoolean("recovery", true),
            a == null || a.getBoolean("console", true));
        return new HealthSettings(
            c.getBoolean("enabled", true),
            Math.max(5, Math.min(60, c.getInt("interval-seconds", 10))),
            Math.max(10, Math.min(360, c.getInt("fine-minutes", 60))),
            Math.max(1, Math.min(168, c.getInt("coarse-hours", 24))),
            c.getBoolean("npc-scan", true),
            Math.max(1, Math.min(60, c.getInt("npc-scan-every", 1))),
            Math.max(0, Math.min(1440, c.getInt("save-minutes", 10))),
            Math.max(60, Math.min(2000, c.getInt("max-points", 720))),
            Math.max(0, Math.min(500, c.getInt("country-limit", 50))),
            th,
            alerts);
    }
}
