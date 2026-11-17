// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Production chains of the villages ({@code workshops.*}; immutable, pure): the glassworks digs sand and melts glass,
 * the brickworks digs clay and fires bricks / terracotta, the sheepfold keeps real sheep and presses hay, the smithy
 * smelts the mines' raw iron and makes torches. Every key is optional; missing keys use these defaults.
 */
public final class WorkshopSettings {

    /** Master switch (false = the workshops stand idle; sites keep buying those materials with coins). */
    public final boolean enabled;
    /** A workshop works only with a player within this many blocks (physical work only near players). */
    public final int playerRange;
    /** false only for isolated trials: work also without a player nearby (still only in loaded chunks). */
    public final boolean requirePlayer;
    /** Time budget of one service run (ms) and workshops handled per run. */
    public final double budgetMs;
    public final int perTick;
    /** Seconds between two work steps of one workshop. */
    public final int actionSeconds;
    /** A workshop that could not make a material for this long counts as not producing it (sites buy it). */
    public final int stallGraceMinutes;
    /** No new step while this many production commits wait (mines, quarries and fields keep their turn). */
    public final int maxPending;
    /** A workshop without its own chest (working from the warehouse) steps at most this often (seconds). */
    public final int sharedStoreSeconds;

    /** Sand / clay: search radius around the workshop, blocks dug per workshop and day, columns scanned per run. */
    public final int digRadius;
    public final int sandPerDay;
    public final int clayPerDay;
    public final int scanColumns;
    public final int rescanSeconds;
    /** No digging closer than this to the world spawn (blocks) and to buildings / roads. */
    public final int spawnRadius;
    public final int buildingMargin;

    /** Kilns: items smelted per step; smelts per coal / charcoal (vanilla 8). */
    public final int batch;
    public final int smeltsPerFuel;
    /** Brickworks: stock targets (bricks before terracotta when nothing is demanded). */
    public final int brickStock;
    public final int terracottaStock;

    /** Smithy: smelts raw iron; torches up to this stock (1 log + 4 coal → 16 torches). */
    public final boolean smithy;
    public final int torchStock;

    /** Sheepfold: target flock, founders at completion, wool regrowth and breeding (2 wheat per lamb). */
    public final int flock;
    public final int founders;
    public final int regrowMinutes;
    public final int breedMinutes;
    public final int refoundDays;
    /** Hay bales kept in stock without demand (9 wheat each). */
    public final int hayStock;

    public WorkshopSettings(final FileConfiguration c) {
        this(c.getConfigurationSection("workshops"));
    }

    public WorkshopSettings(final ConfigurationSection s) {
        this.enabled = bool(s, "enabled", true);
        this.playerRange = clamp(integer(s, "player-range", 96), 16, 256);
        this.requirePlayer = bool(s, "require-player", true);
        this.budgetMs = Math.max(0.2, Math.min(20.0, s == null ? 2.0 : s.getDouble("budget-ms", 2.0)));
        this.perTick = clamp(integer(s, "per-tick", 6), 1, 64);
        this.actionSeconds = clamp(integer(s, "action-seconds", 10), 2, 120);
        this.stallGraceMinutes = clamp(integer(s, "stall-grace-minutes", 20), 1, 1440);
        this.maxPending = clamp(integer(s, "max-pending", 2), 1, 16);
        this.sharedStoreSeconds = clamp(integer(s, "shared-store-seconds", 30), 2, 600);
        this.digRadius = clamp(integer(s, "dig.radius", 24), 6, 48);
        this.sandPerDay = clamp(integer(s, "dig.sand-per-day", 48), 0, 1024);
        this.clayPerDay = clamp(integer(s, "dig.clay-per-day", 24), 0, 1024);
        this.scanColumns = clamp(integer(s, "dig.scan-columns", 192), 16, 4096);
        this.rescanSeconds = clamp(integer(s, "dig.rescan-seconds", 120), 10, 3600);
        this.spawnRadius = clamp(integer(s, "dig.spawn-radius", 160), 0, 4096);
        this.buildingMargin = clamp(integer(s, "dig.building-margin", 3), 0, 16);
        this.batch = clamp(integer(s, "kiln.batch", 8), 1, 64);
        this.smeltsPerFuel = clamp(integer(s, "kiln.smelts-per-fuel", 8), 1, 64);
        this.brickStock = clamp(integer(s, "kiln.brick-stock", 64), 0, 4096);
        this.terracottaStock = clamp(integer(s, "kiln.terracotta-stock", 16), 0, 4096);
        this.smithy = bool(s, "smithy.enabled", true);
        this.torchStock = clamp(integer(s, "smithy.torch-stock", 32), 0, 1024);
        this.flock = clamp(integer(s, "sheep.flock", 4), 0, 16);
        this.founders = clamp(integer(s, "sheep.founders", 2), 0, 16);
        this.regrowMinutes = clamp(integer(s, "sheep.regrow-minutes", 10), 1, 1440);
        this.breedMinutes = clamp(integer(s, "sheep.breed-minutes", 15), 1, 1440);
        this.refoundDays = clamp(integer(s, "sheep.refound-days", 3), 0, 365);
        this.hayStock = clamp(integer(s, "sheep.hay-stock", 4), 0, 256);
    }

    private static boolean bool(final ConfigurationSection s, final String key, final boolean def) {
        return s == null ? def : s.getBoolean(key, def);
    }

    private static int integer(final ConfigurationSection s, final String key, final int def) {
        return s == null ? def : s.getInt(key, def);
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
