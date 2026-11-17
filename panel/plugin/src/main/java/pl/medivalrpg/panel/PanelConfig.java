// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable view of config.yml; a new instance on every {@code /panel reload}. */
public final class PanelConfig {

    public final String workerUrl;
    public final String pluginSecret;
    /** Lower-case nicks. */
    public final Set<String> admins;
    public final boolean opsAreAdmins;
    /** Lower-case nicks. */
    public final Set<String> npcManagers;
    public final int loginAttempts;
    public final long loginWindowMillis;
    public final int linkMinutes;
    public final long linkCooldownMillis;
    public final int mutationsPerMinute;
    public final long rpcTimeoutMillis;
    public final int descriptionMaxLength;
    public final int maxArmyCount;
    public final int treasuryLog;
    public final int npcLog;
    public final int auditMemory;
    public final boolean notifyInGame;
    public final boolean debug;
    /** history.*: hourly samples of NPC countries (plugins/Panel/history). */
    public final boolean historyEnabled;
    public final boolean historyPlayerCountries;
    public final int historyKeepDays;
    /** map-render.*: top-down map of an NPC country for the plan editor. */
    public final int mapMargin;
    public final pl.medivalrpg.panel.map.MapRenderService.Settings mapRender;
    /** plan.margin: blocks outside the country's land where plan points are still accepted. */
    public final int planMargin;
    /** territory.*: claim cost used when Kraje's own config cannot be read. */
    public final long territoryCostBase;
    public final long territoryCostPerChunk;
    public final boolean territoryReadKrajeConfig;
    public final int territoryChangesPerTick;
    /** health.*: server health probe (TPS, MSPT, NPC load) and its warnings. */
    public final pl.medivalrpg.panel.health.HealthSettings health;

    public PanelConfig(final FileConfiguration c) {
        String url = c.getString("worker-url", "").trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.workerUrl = url;
        this.pluginSecret = c.getString("plugin-secret", "").trim();
        this.admins = lower(c.getStringList("admins"));
        this.opsAreAdmins = c.getBoolean("ops-are-admins", false);
        this.npcManagers = lower(c.getStringList("npc-managers"));
        this.loginAttempts = Math.max(1, c.getInt("limits.login-attempts", 5));
        this.loginWindowMillis = Math.max(1, c.getInt("limits.login-window-minutes", 15)) * 60_000L;
        this.linkMinutes = Math.max(1, c.getInt("limits.link-minutes", 10));
        this.linkCooldownMillis = Math.max(0, c.getInt("limits.link-cooldown-seconds", 10)) * 1000L;
        this.mutationsPerMinute = Math.max(1, c.getInt("limits.mutations-per-minute", 30));
        this.rpcTimeoutMillis = Math.max(1000, Math.min(9500, c.getLong("limits.rpc-timeout-ms", 8000)));
        this.descriptionMaxLength = Math.max(1, Math.min(1000, c.getInt("limits.description-max-length", 200)));
        this.maxArmyCount = Math.max(1, Math.min(500, c.getInt("limits.max-army-count", 64)));
        this.treasuryLog = Math.max(0, Math.min(200, c.getInt("limits.treasury-log", 20)));
        this.npcLog = Math.max(0, Math.min(200, c.getInt("limits.npc-log", 30)));
        this.auditMemory = Math.max(10, Math.min(100_000, c.getInt("limits.audit-memory", 1000)));
        this.notifyInGame = c.getBoolean("notify-in-game", true);
        this.debug = c.getBoolean("debug", false);
        this.historyEnabled = c.getBoolean("history.enabled", true);
        this.historyPlayerCountries = c.getBoolean("history.player-countries", false);
        this.historyKeepDays = Math.max(7, Math.min(365, c.getInt("history.keep-days", 30)));
        this.mapMargin = Math.max(0, Math.min(128, c.getInt("map-render.margin", 32)));
        this.mapRender = new pl.medivalrpg.panel.map.MapRenderService.Settings(
            Math.max(128, Math.min(2048, c.getInt("map-render.max-size", 768))),
            Math.max(16, Math.min(16_384, c.getInt("map-render.max-chunks", 4096))),
            Math.max(0, Math.min(16_384, c.getInt("map-render.max-async-loads", 1500))),
            Math.max(1, Math.min(64, c.getInt("map-render.parallel-loads", 8))),
            Math.max(1, Math.min(256, c.getInt("map-render.snapshots-per-tick", 24))),
            Math.max(500, Math.min(9000, c.getLong("map-render.wait-ms", 5500))),
            Math.max(0, Math.min(3600, c.getLong("map-render.cache-seconds", 60))) * 1000L,
            Math.max(5, Math.min(600, c.getLong("map-render.job-timeout-seconds", 60))) * 1000L,
            Math.max(64, Math.min(1800, c.getInt("map-render.max-png-kb", 1400))) * 1024);
        this.planMargin = Math.max(0, Math.min(128, c.getInt("plan.margin", 32)));
        this.territoryCostBase = Math.max(0, c.getLong("territory.cost-base", 50));
        this.territoryCostPerChunk = Math.max(0, c.getLong("territory.cost-per-chunk", 5));
        this.territoryReadKrajeConfig = c.getBoolean("territory.read-kraje-config", true);
        this.territoryChangesPerTick = Math.max(1, Math.min(512, c.getInt("territory.changes-per-tick", 32)));
        this.health = pl.medivalrpg.panel.health.HealthSettings.from(c.getConfigurationSection("health"));
    }

    static Set<String> lower(final List<String> names) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String n : names) {
            if (n != null && !n.isBlank()) {
                out.add(n.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }
}
