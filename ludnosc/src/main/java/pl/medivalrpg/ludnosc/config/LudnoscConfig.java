// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.config;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.ludnosc.logic.Economy;
import pl.medivalrpg.ludnosc.logic.EconomyRules;

/** Typed view of config.yml (immutable after construction, safe from any thread). Pure: no server needed. */
public final class LudnoscConfig {

    public enum DisbandMode { RELEASE, REMOVE }

    public enum ConquestMode { SWITCH, RELEASE, LEAVE }

    public enum UnclaimMode { RELEASE, KEEP, LEAVE }

    public enum BuilderType { MANNEQUIN, VILLAGER }

    // citizens
    public final boolean adoptVillagers;
    public final boolean adoptRequiresHousing;
    public final String nameFormat;
    public final boolean nameAlwaysVisible;
    public final boolean lockProfession;
    public final Map<String, Integer> professionWeights;
    public final int forgetAfterMissedLoads;
    public final DisbandMode onDisband;
    public final ConquestMode onConquest;
    public final UnclaimMode onUnclaim;
    public final boolean conscriptUnloaded;
    public final List<String> names;

    // growth / daily
    public final int growthIntervalMinutes;
    public final int growthMaxPerTick;
    public final long foodPerSettler;
    public final LocalTime dailyTime;
    public final ZoneId dailyZone;
    public final int maxCatchUpDays;

    // food
    public final long baseCapacity;
    public final long capacityPerGranary;
    public final int maxMarkedChests;
    public final Map<Material, Integer> foodItems;

    public final EconomyRules rules;

    // protection
    public final boolean protectionEnabled;
    public final boolean warAllowsDamage;
    public final boolean ownCountryCanDamage;
    public final boolean blockNameTags;
    public final boolean blockLeash;

    // npc countries
    public final int npcInitialSettlers;
    public final long npcInitialFood;
    public final List<String> npcStartingBuildings;
    public final boolean npcStartingFree;

    // building
    public final Rank buildRank;
    public final int maxSitesPerCountry;
    /** Extra site slots per settlement level above Osada and per epoch above the Middle Ages; hard cap. */
    public final int sitesPerTier;
    public final int sitesPerEra;
    public final int maxSitesCap;
    /** Blocks placed per construction tick (every 10 game ticks): per country and for the whole server. */
    public final int blocksPerTickCountry;
    public final int blocksPerTickServer;
    /** Orders waiting when all site slots are busy ({@code construction.queue-size}). */
    public final int queueSize;
    public final boolean keepLoaded;
    public final int maxLoadedChunks;
    public final boolean workHours;
    public final int workStart;
    public final int workEnd;
    public final boolean nightCampfire;
    public final boolean requireMaterials;
    /** Minutes a site waits for wood/stone the country produces before it buys them with the order's coins (0 = never). */
    public final int materialWaitMinutes;
    /** Blocks a builder carries from the material pile in one load (0 = no fetching walks). */
    public final int carryLoad;
    /** Material haulers between stores, granary and construction sites. */
    public final Logistics logistics;

    /**
     * Haulers ("tragarze"): real item transfers between registered containers, carried by a visible NPC.
     *
     * @param load        items per trip
     * @param maxDistance longest trip in blocks (both ends loaded, same world)
     * @param perCountry  haulers walking at once per country
     * @param total       haulers walking at once on the server
     * @param siteStock   units of each raw material kept ready in a site chest
     * @param unloadFill  share of occupied slots from which a workplace store is emptied into the warehouse
     * @param walkSpeed   blocks per second
     */
    public record Logistics(boolean enabled, int load, int maxDistance, int perCountry, int total, int siteStock,
                            double unloadFill, double walkSpeed) {
    }
    public final int blocksPerExtraBuilder;
    public final int buildersMax;
    public final double walkSpeed;
    public final double reach;
    public final boolean scaffolding;
    public final int scaffoldingMinHeight;
    public final int scaffoldingMaxBlocks;
    public final boolean entranceStairs;
    public final int maxEntranceSteps;
    // repair
    public final boolean repairEnabled;
    public final double repairBudgetMs;
    public final int repairIntervalMinutes;
    public final double repairThreshold;
    public final double repairCostMultiplier;
    public final boolean repairAutoForPlayers;
    public final double ruinThreshold;
    public final boolean ruinRebuild;
    // planner
    public final int plannerRadius;
    public final int plannerSpacing;
    public final int plannerMaxSlope;
    public final double housingFreeRatio;
    public final double granaryFullRatio;
    public final boolean roads;
    public final int pavedRoadLevel;
    public final pl.medivalrpg.ludnosc.planner.SettlementLevels levels;
    /** Materials per settlement level (config {@code materials}). */
    public final pl.medivalrpg.ludnosc.template.MaterialPalette materials;
    /** Balance overrides per template id. */
    public final java.util.Map<String, BuildingBalance> buildings;

    /**
     * Effect of one finished building of a type ({@code buildings.<id>.bonus}): food per day, tax share (+0.05 =
     * +5 %), growth chance per growth tick, extra granary capacity.
     */
    public record Bonus(long food, double tax, double growth, long granary) {

        public static final Bonus NONE = new Bonus(0, 0, 0, 0);

        /** Defaults used when config.yml (e.g. an older copy on the server) has no {@code bonus} for the type. */
        public static final Map<String, Bonus> DEFAULTS = Map.ofEntries(
            Map.entry("piekarnia", new Bonus(12, 0, 0, 0)),
            Map.entry("browar", new Bonus(0, 0.04, 0.1, 0)),
            Map.entry("tartak", new Bonus(0, 0.05, 0, 0)),
            Map.entry("biblioteka", new Bonus(0, 0.02, 0.15, 0)),
            Map.entry("zielarnia", new Bonus(0, 0, 0.25, 0)),
            Map.entry("przystan", new Bonus(10, 0.03, 0, 0)),
            Map.entry("cmentarz", new Bonus(0, 0, 0.05, 0)),
            Map.entry("pomnik", new Bonus(0, 0.03, 0, 0)),
            Map.entry("magazyn", new Bonus(0, 0, 0, 300)),
            Map.entry("karczma", new Bonus(0, 0.05, 0.1, 0)),
            Map.entry("mlyn", new Bonus(8, 0, 0, 0)),
            Map.entry("targ", new Bonus(0, 0.1, 0, 0)),
            Map.entry("ratusz", new Bonus(0, 0.08, 0, 0)),
            Map.entry("kosciol", new Bonus(0, 0, 0.2, 0)),
            Map.entry("zamek", new Bonus(0, 0.1, 0, 0)),
            Map.entry("palac", new Bonus(0, 0.15, 0, 0)),
            Map.entry("laznia", new Bonus(0, 0, 0.15, 0)),
            Map.entry("latarnia", new Bonus(4, 0.03, 0, 0)),
            Map.entry("rynek", new Bonus(0, 0.06, 0.05, 0)),
            Map.entry("ogrody", new Bonus(0, 0.03, 0.1, 0)),
            Map.entry("skarbiec", new Bonus(0, 0.08, 0, 0)),
            Map.entry("katedra", new Bonus(0, 0.02, 0.3, 0)),
            Map.entry("arena", new Bonus(0, 0.04, 0.05, 0)),
            Map.entry("most", new Bonus(0, 0.02, 0, 0)),
            Map.entry("kamieniolom", new Bonus(0, 0.04, 0, 0)),
            Map.entry("fabryka", new Bonus(0, 0.10, 0, 0)),
            Map.entry("dworzec", new Bonus(0, 0.06, 0.05, 0)),
            Map.entry("wiezowiec_biurowy", new Bonus(0, 0.15, 0, 0)),
            Map.entry("drukarnia", new Bonus(0, 0.03, 0.05, 0)),
            Map.entry("port", new Bonus(8, 0.08, 0, 0)),
            Map.entry("bank", new Bonus(0, 0.10, 0, 0)),
            Map.entry("ratusz_renesansowy", new Bonus(0, 0.10, 0, 0)),
            Map.entry("palac_renesansowy", new Bonus(0, 0.12, 0.05, 0)),
            Map.entry("szkola", new Bonus(0, 0.02, 0.1, 0)),
            Map.entry("szpital", new Bonus(0, 0, 0.3, 0)),
            Map.entry("elektrownia", new Bonus(0, 0.12, 0, 0)),
            Map.entry("park", new Bonus(0, 0, 0.15, 0)),
            Map.entry("galeria", new Bonus(0, 0.18, 0, 0)),
            Map.entry("szpital_nowoczesny", new Bonus(0, 0, 0.45, 0)),
            Map.entry("szkola_nowoczesna", new Bonus(0, 0.03, 0.15, 0)),
            Map.entry("stadion", new Bonus(0, 0.06, 0.1, 0)),
            Map.entry("parlament", new Bonus(0, 0.12, 0.05, 0)),
            Map.entry("dworzec_nowoczesny", new Bonus(0, 0.10, 0.05, 0)),
            Map.entry("parking", new Bonus(0, 0.03, 0, 0)),
            Map.entry("lotnisko", new Bonus(0, 0.15, 0.05, 0)));

        public boolean any() {
            return this.food != 0 || this.tax != 0 || this.growth != 0 || this.granary != 0;
        }
    }

    /** Bonus per template id. */
    public final Map<String, Bonus> bonuses;
    /** Daily income per gallery block of mines (copper). */
    public final long mineIncomePerBlock;
    /** Gallery blocks dug per mine extension order and its cost (copper). */
    public final int mineExtension;
    public final long mineExtensionCost;
    public final int mineMaxLength;
    public final int mineDepth;
    /** Lumberjacks and raw materials. */
    public final boolean lumberjacks;
    public final int lumberjackBlocksPerStep;
    public final int lumberjackRestSeconds;
    public final int lumberjackRadius;
    public final int lumberjackMaxLogs;
    public final int lumberjackKeepTrees;
    public final int quarryStonePerDay;
    public final int mineOrePerDay;
    public final int harbourFishPerDay;
    public final Map<String, Long> resourcePrices;
    /** Farmers, stonemasons and fishermen walking on their buildings; food per harvested crop. */
    public final boolean fieldWorkers;
    /** Citizens' work, seasons and mine output ({@code work}, {@code seasons}, {@code mines}). */
    public final WorkSettings work;
    /** Production chains: glassworks, brickworks, sheepfold, smithy ({@code workshops}). */
    public final WorkshopSettings workshops;
    public final int foodPerHarvest;
    public final long resourceSellAbove;
    /** City walls: from this settlement level, margin around the town, tower every N cells, cells per module, cost. */
    public final int wallsLevel;
    public final int wallsMargin;
    public final int wallsTowerEvery;
    public final int wallsModule;
    public final long wallsCostPerCell;
    /** When the town outgrows its walls: keep the old ring as an inner wall (true) or take it down step by step. */
    public final boolean wallsKeepOld;
    /**
     * Player countries: the walls the ruler draws in the panel plan are built (stretch by stretch, paid by the
     * treasury). Off by default: player countries have no automatic building, the plan stays a sketch.
     */
    public final boolean playerPlanWalls;
    /** Kingdom: new settlements from this level / treasury / land, distance between settlements, at most. */
    public final int kingdomMinLevel;
    public final long kingdomMinTreasury;
    public final int kingdomMinChunks;
    public final int kingdomMinDistance;
    public final int kingdomMaxSettlements;
    public final int kingdomBorderSegments;
    /** Town redesign: planned area, replanning interval, hours between demolitions, levelling limits and price. */
    public final int redesignRadius;
    public final int redesignIntervalHours;
    public final int redesignDemolishHours;
    public final int redesignLevelBlocksPerDay;
    public final long redesignLevelCostPerColumn;
    /** Admin orders (redesign, roads, modernisation) are paid from the country's treasury unless "darmowo". */
    public final boolean redesignAdminPays;
    /** Never demolished (heritage) building types. */
    public final java.util.Set<String> heritage;
    /** Planner may claim adjacent chunks for buildings: player countries too (NPC always), max chunks per attempt. */
    public final boolean autoClaimForPlayers;
    public final int autoClaimMax;
    /** Epoch rebuilding: hours between two demolitions of a country, old town radius kept around the square. */
    public final int rebuildDemolishHours;
    public final int rebuildOldTownRadius;
    /** Village takeover: farmland blocks that make a chunk part of a village, house search reach, farmland per field. */
    public final int villageMinFarmland;
    public final int villageHouseReach;
    public final int villageFarmlandPerField;
    /** Underground parts: on/off, feature per building type, blocks per step, price per kind, cellar granary bonus. */
    public final boolean undergroundEnabled;
    public final Map<String, String> undergroundFeatures;
    public final int undergroundOpsPerStep;
    public final Map<String, Long> undergroundCost;
    public final long cellarCapacity;
    /** Modernisation cost = building cost × share of material blocks × this. */
    public final double upgradeCostMultiplier;

    /** Balance override of one building ({@code buildings.<id>} in config.yml); null fields = keep the template value. */
    public record BuildingBalance(Long cost, Integer tier, Integer maxPerCountry, Integer population, Long treasury) {
    }
    public final double blocksPerSecond;
    public final int buildersPerSite;
    public final int maxBlocksPerTick;
    public final boolean offlineProgress;
    public final int maxTerrainCut;
    public final int maxFoundationDepth;
    public final double refundOnCancel;
    public final int previewSeconds;
    public final Set<Material> terrainBlocks;
    public final BuilderType builderType;
    public final List<String> builderSkins;
    public final List<Material> builderTools;
    public final int builderMoveTicks;
    public final boolean hologram;
    public final boolean effects;

    // misc
    public final long registerMaxVolume;
    public final boolean exportBuiltins;
    public final long schematicDefaultCost;
    public final boolean verifyRotation;
    public final int autosaveMinutes;

    public LudnoscConfig(final FileConfiguration c, final Logger logger) {
        this.adoptVillagers = c.getBoolean("citizens.adopt-villagers", true);
        this.adoptRequiresHousing = c.getBoolean("citizens.adopt-requires-free-housing", false);
        this.nameFormat = c.getString("citizens.name-format", "<kolor><imie></kolor>");
        this.nameAlwaysVisible = c.getBoolean("citizens.name-always-visible", false);
        this.lockProfession = c.getBoolean("citizens.lock-profession", true);
        this.professionWeights = intMap(c.getConfigurationSection("citizens.professions"));
        this.forgetAfterMissedLoads = Math.max(1, c.getInt("citizens.forget-after-missed-loads", 3));
        this.onDisband = parseEnum(DisbandMode.class, c.getString("citizens.on-disband"), DisbandMode.RELEASE, logger);
        this.onConquest = parseEnum(ConquestMode.class, c.getString("citizens.on-conquest"), ConquestMode.SWITCH, logger);
        this.onUnclaim = parseEnum(UnclaimMode.class, c.getString("citizens.on-unclaim"), UnclaimMode.RELEASE, logger);
        this.conscriptUnloaded = c.getBoolean("citizens.conscript-unloaded", true);
        this.names = List.copyOf(c.getStringList("citizens.names"));

        this.growthIntervalMinutes = Math.max(1, c.getInt("growth.interval-minutes", 20));
        this.growthMaxPerTick = Math.max(0, c.getInt("growth.max-per-tick", 1));
        this.foodPerSettler = Math.max(0, c.getLong("growth.food-per-settler", 20));
        this.dailyTime = parseTime(c.getString("daily.time", "06:00"), logger);
        this.dailyZone = parseZone(c.getString("daily.timezone", "Europe/Warsaw"), logger);
        this.maxCatchUpDays = Math.max(1, c.getInt("daily.max-catch-up-days", 3));

        this.baseCapacity = Math.max(0, c.getLong("food.base-capacity", 300));
        this.capacityPerGranary = Math.max(0, c.getLong("food.capacity-per-granary", 2000));
        this.maxMarkedChests = Math.max(0, c.getInt("food.max-marked-chests", 6));
        final Map<Material, Integer> food = new EnumMap<>(Material.class);
        final ConfigurationSection items = c.getConfigurationSection("food.items");
        if (items != null) {
            for (final String key : items.getKeys(false)) {
                final Material m = Material.matchMaterial(key);
                if (m == null) {
                    if (logger != null) {
                        logger.warning("Nieznany przedmiot w food.items: " + key);
                    }
                    continue;
                }
                final int points = items.getInt(key);
                if (points > 0) {
                    food.put(m, points);
                }
            }
        }
        this.foodItems = Map.copyOf(food);

        final Map<String, Double> mult = new LinkedHashMap<>();
        final ConfigurationSection prof = c.getConfigurationSection("taxes.professions");
        if (prof != null) {
            for (final String key : prof.getKeys(false)) {
                mult.put(Economy.professionKey(key), Math.max(0, prof.getDouble(key)));
            }
        }
        this.rules = new EconomyRules(
            Math.max(0, c.getLong("food.per-citizen", 4)),
            Math.max(0, c.getLong("food.farmer-production", 10)),
            Math.max(0, c.getLong("npc-countries.virtual-food-per-day", 10)),
            Math.max(0, c.getLong("npc-countries.virtual-food-per-citizen", 5)),
            c.getBoolean("npc-countries.ignore-capacity", false),
            Math.max(0, c.getLong("taxes.per-citizen", 3)),
            mult,
            clamp01(c.getDouble("taxes.baby-multiplier", 0.0)),
            clamp01(c.getDouble("taxes.hungry-multiplier", 0.3)),
            clamp01(c.getDouble("taxes.homeless-multiplier", 0.5)),
            Math.max(0, c.getInt("departures.hunger-grace-days", 2)),
            clamp01(c.getDouble("departures.hunger-fraction", 0.2)),
            Math.max(0, c.getInt("departures.homeless-grace-days", 3)),
            clamp01(c.getDouble("departures.homeless-fraction", 0.25)),
            Math.max(0, c.getInt("departures.max-per-day", 5)),
            Math.max(0, c.getLong("food.farm-production", 15)));

        this.protectionEnabled = c.getBoolean("protection.enabled", true);
        this.warAllowsDamage = c.getBoolean("protection.war-allows-damage", true);
        this.ownCountryCanDamage = c.getBoolean("protection.own-country-can-damage", true);
        this.blockNameTags = c.getBoolean("protection.block-name-tags", true);
        this.blockLeash = c.getBoolean("protection.block-leash-by-foreigners", true);

        this.npcInitialSettlers = Math.max(0, c.getInt("npc-countries.initial-settlers", 4));
        this.npcInitialFood = Math.max(0, c.getLong("npc-countries.initial-food", 200));
        this.npcStartingBuildings = lower(c.getStringList("npc-countries.starting-buildings"));
        this.npcStartingFree = c.getBoolean("npc-countries.starting-buildings-free", true);

        this.buildRank = parseEnum(Rank.class, c.getString("building.rank"), Rank.ADVISOR, logger);
        this.maxSitesPerCountry = Math.max(1, c.getInt("building.max-sites-per-country", 5));
        this.sitesPerTier = Math.max(0, c.getInt("building.sites-per-tier", 1));
        this.sitesPerEra = Math.max(0, c.getInt("building.sites-per-era", 1));
        this.maxSitesCap = Math.max(this.maxSitesPerCountry, c.getInt("building.max-sites-cap", 12));
        this.blocksPerTickCountry = Math.max(1, c.getInt("construction.blocks-per-tick-country", 80));
        this.blocksPerTickServer = Math.max(1, c.getInt("construction.blocks-per-tick-server", 400));
        this.queueSize = Math.max(0, c.getInt("construction.queue-size", 10));
        this.keepLoaded = c.getBoolean("construction.keep-loaded", true);
        this.maxLoadedChunks = Math.max(0, c.getInt("construction.max-loaded-chunks-total", 64));
        this.workHours = c.getBoolean("construction.work-hours.enabled", true);
        this.workStart = Math.floorMod(c.getInt("construction.work-hours.start", 0), 24000);
        this.workEnd = Math.floorMod(c.getInt("construction.work-hours.end", 12000), 24000);
        this.nightCampfire = c.getBoolean("construction.night-campfire", true);
        this.requireMaterials = c.getBoolean("construction.require-materials", false);
        this.carryLoad = Math.max(0, Math.min(64, c.getInt("construction.carry-load", 12)));
        this.materialWaitMinutes = Math.max(0, Math.min(10080, c.getInt("construction.material-wait-minutes", 60)));
        this.logistics = new Logistics(c.getBoolean("logistics.enabled", true),
            Math.max(1, Math.min(64, c.getInt("logistics.load", 32))),
            Math.max(8, Math.min(512, c.getInt("logistics.max-distance", 160))),
            Math.max(0, Math.min(16, c.getInt("logistics.per-country", 3))),
            Math.max(0, Math.min(64, c.getInt("logistics.total", 16))),
            Math.max(8, Math.min(1024, c.getInt("logistics.site-stock", 96))),
            Math.max(0.1, Math.min(1.0, c.getDouble("logistics.unload-fill", 0.75))),
            Math.max(0.5, Math.min(8, c.getDouble("logistics.walk-speed", 2.5))));
        this.blocksPerExtraBuilder = Math.max(50, c.getInt("construction.blocks-per-extra-builder", 800));
        this.buildersMax = Math.max(1, Math.min(12, c.getInt("construction.builders-max", 6)));
        this.walkSpeed = Math.max(0.5, Math.min(10, c.getDouble("construction.walk-speed", 3.5)));
        this.reach = Math.max(2, Math.min(8, c.getDouble("construction.reach", 4.5)));
        this.scaffolding = c.getBoolean("construction.scaffolding", true);
        this.scaffoldingMinHeight = Math.max(1, c.getInt("construction.scaffolding-min-height", 3));
        this.scaffoldingMaxBlocks = Math.max(0, c.getInt("construction.scaffolding-max-blocks", 400));
        this.entranceStairs = c.getBoolean("construction.entrance-stairs", true);
        this.maxEntranceSteps = Math.max(1, c.getInt("construction.max-entrance-steps", 8));
        this.repairEnabled = c.getBoolean("repair.enabled", true);
        this.repairBudgetMs = Math.max(0.1, Math.min(20, c.getDouble("repair.budget-ms", 1.0)));
        this.repairIntervalMinutes = Math.max(1, c.getInt("repair.interval-minutes", 10));
        this.repairThreshold = clamp01(c.getDouble("repair.threshold", 0.02));
        this.repairCostMultiplier = Math.max(0, c.getDouble("repair.cost-multiplier", 0.5));
        this.repairAutoForPlayers = c.getBoolean("repair.auto-for-players", false);
        this.ruinThreshold = clamp01(c.getDouble("repair.ruin-threshold", 0.7));
        this.ruinRebuild = !"remove".equalsIgnoreCase(c.getString("repair.ruin-mode", "rebuild"));
        this.plannerRadius = Math.max(16, Math.min(160, c.getInt("planner.search-radius", 64)));
        this.plannerSpacing = Math.max(0, c.getInt("planner.spacing", 2));
        this.plannerMaxSlope = Math.max(1, c.getInt("planner.max-slope", 4));
        this.housingFreeRatio = clamp01(c.getDouble("planner.housing-free-ratio", 0.2));
        this.granaryFullRatio = clamp01(c.getDouble("planner.granary-full-ratio", 0.85));
        this.roads = c.getBoolean("planner.roads", true);
        this.pavedRoadLevel = Math.max(0, c.getInt("planner.paved-road-level", 3));
        final List<pl.medivalrpg.ludnosc.planner.SettlementLevels.Level> lv = new ArrayList<>();
        for (final Map<?, ?> level : c.getMapList("levels")) {
            final Object name = level.get("name");
            if (name == null) {
                continue;
            }
            lv.add(new pl.medivalrpg.ludnosc.planner.SettlementLevels.Level(name.toString(),
                number(level.get("population")).intValue(), number(level.get("treasury")).longValue()));
        }
        // older config files have no Metropolia: append the default levels above the configured ones
        if (!lv.isEmpty()) {
            final List<pl.medivalrpg.ludnosc.planner.SettlementLevels.Level> def = pl.medivalrpg.ludnosc.planner.SettlementLevels.DEFAULT;
            for (int i = lv.size(); i < def.size(); i++) {
                final var last = lv.get(lv.size() - 1);
                final var d = def.get(i);
                lv.add(new pl.medivalrpg.ludnosc.planner.SettlementLevels.Level(d.name(), Math.max(d.population(), last.population() + 1),
                    Math.max(d.treasury(), last.treasury() + 1)));
            }
        }
        this.levels = new pl.medivalrpg.ludnosc.planner.SettlementLevels(lv);
        final Map<String, BuildingBalance> bal = new java.util.HashMap<>();
        final ConfigurationSection bs = c.getConfigurationSection("buildings");
        if (bs != null) {
            for (final String id : bs.getKeys(false)) {
                final ConfigurationSection b = bs.getConfigurationSection(id);
                if (b == null) {
                    continue;
                }
                bal.put(id.toLowerCase(Locale.ROOT), new BuildingBalance(
                    b.contains("cost") ? b.getLong("cost") : null,
                    b.contains("tier") ? b.getInt("tier") : null,
                    b.contains("max-per-country") ? b.getInt("max-per-country") : null,
                    b.contains("population") ? b.getInt("population") : null,
                    b.contains("treasury") ? b.getLong("treasury") : null));
            }
        }
        this.buildings = Map.copyOf(bal);
        final Map<String, Bonus> bonus = new java.util.HashMap<>(Bonus.DEFAULTS);
        if (bs != null) {
            for (final String id : bs.getKeys(false)) {
                final ConfigurationSection b = bs.getConfigurationSection(id + ".bonus");
                if (b != null) {
                    bonus.put(id.toLowerCase(Locale.ROOT), new Bonus(b.getLong("food", 0), b.getDouble("tax", 0),
                        b.getDouble("growth", 0), b.getLong("granary", 0)));
                }
            }
        }
        this.bonuses = Map.copyOf(bonus);
        this.mineIncomePerBlock = Math.max(0, c.getLong("mines.income-per-block", 2));
        this.mineExtension = Math.max(4, c.getInt("mines.extension-blocks", 16));
        this.mineExtensionCost = Math.max(0, c.getLong("mines.extension-cost", 800));
        this.mineMaxLength = Math.max(16, c.getInt("mines.max-length", 400));
        this.mineDepth = Math.max(4, c.getInt("mines.depth", 12));
        this.upgradeCostMultiplier = Math.max(0, c.getDouble("construction.upgrade-cost-multiplier", 0.4));
        this.undergroundEnabled = c.getBoolean("underground.enabled", true);
        final Map<String, String> feats = new java.util.HashMap<>(Map.of("chata", "cellar", "dom", "cellar", "kamienica", "cellar",
            "karczma", "cellar", "ratusz", "dungeon", "zamek", "tunnel", "katedra", "catacombs"));
        final ConfigurationSection fs = c.getConfigurationSection("underground.features");
        if (fs != null) {
            for (final String k : fs.getKeys(false)) {
                final String v = fs.getString(k, "");
                if (v.isBlank() || v.equalsIgnoreCase("none")) {
                    feats.remove(k.toLowerCase(Locale.ROOT));
                } else {
                    feats.put(k.toLowerCase(Locale.ROOT), v.toLowerCase(Locale.ROOT));
                }
            }
        }
        this.undergroundFeatures = Map.copyOf(feats);
        this.undergroundOpsPerStep = Math.max(1, c.getInt("underground.blocks-per-step", 6));
        final Map<String, Long> ucost = new java.util.HashMap<>(Map.of("cellar", 300L, "dungeon", 3000L, "tunnel", 5000L,
            "catacombs", 4000L));
        final ConfigurationSection cs = c.getConfigurationSection("underground.cost");
        if (cs != null) {
            for (final String k : cs.getKeys(false)) {
                ucost.put(k.toLowerCase(Locale.ROOT), Math.max(0, cs.getLong(k)));
            }
        }
        this.undergroundCost = Map.copyOf(ucost);
        this.cellarCapacity = Math.max(0, c.getLong("underground.cellar-capacity", 50));
        this.lumberjacks = c.getBoolean("resources.lumberjacks", true);
        this.lumberjackBlocksPerStep = Math.max(1, c.getInt("resources.lumberjack-blocks-per-step", 3));
        this.lumberjackRestSeconds = Math.max(0, c.getInt("resources.lumberjack-rest-seconds", 20));
        this.lumberjackRadius = Math.max(8, Math.min(64, c.getInt("resources.lumberjack-radius", 28)));
        this.lumberjackMaxLogs = Math.max(8, c.getInt("resources.lumberjack-max-logs", 120));
        this.lumberjackKeepTrees = Math.max(0, c.getInt("resources.keep-trees-around", 3));
        this.quarryStonePerDay = Math.max(0, c.getInt("resources.quarry-stone-per-day", 32));
        this.mineOrePerDay = Math.max(0, c.getInt("resources.mine-ore-per-day", 8));
        this.harbourFishPerDay = Math.max(0, c.getInt("resources.harbour-fish-per-day", 16));
        final Map<String, Long> prices = new java.util.HashMap<>(Map.of("drewno", 2L, "kamien", 1L, "rudy", 6L, "ryby", 2L));
        final ConfigurationSection ps = c.getConfigurationSection("resources.prices");
        if (ps != null) {
            for (final String k : ps.getKeys(false)) {
                prices.put(k.toLowerCase(Locale.ROOT), Math.max(0, ps.getLong(k)));
            }
        }
        this.resourcePrices = Map.copyOf(prices);
        this.resourceSellAbove = Math.max(0, c.getLong("resources.sell-above", 512));
        this.fieldWorkers = c.getBoolean("resources.field-workers", true);
        this.foodPerHarvest = Math.max(0, c.getInt("resources.food-per-harvest", 2));
        this.work = new WorkSettings(c);
        this.workshops = new WorkshopSettings(c);
        this.wallsLevel = Math.max(0, c.getInt("walls.from-level", 2));
        this.wallsMargin = Math.max(2, c.getInt("walls.margin", 6));
        this.wallsTowerEvery = Math.max(4, c.getInt("walls.tower-every", 16));
        this.wallsModule = Math.max(8, c.getInt("walls.module-cells", 32));
        this.wallsCostPerCell = Math.max(0, c.getLong("walls.cost-per-cell", 40));
        this.wallsKeepOld = "keep".equalsIgnoreCase(c.getString("walls.old-ring", "demolish"));
        this.playerPlanWalls = c.getBoolean("player-countries.plan-walls", false);
        this.kingdomMinLevel = Math.max(0, c.getInt("kingdom.min-level", 2));
        this.kingdomMinTreasury = Math.max(0, c.getLong("kingdom.min-treasury", 60000));
        this.kingdomMinChunks = Math.max(4, c.getInt("kingdom.min-chunks", 24));
        this.kingdomMinDistance = Math.max(48, c.getInt("kingdom.min-distance", 96));
        this.kingdomMaxSettlements = Math.max(0, c.getInt("kingdom.max-settlements", 3));
        this.kingdomBorderSegments = Math.max(0, c.getInt("kingdom.border-segments", 6));
        this.redesignRadius = Math.max(32, Math.min(160, c.getInt("redesign.radius", 96)));
        this.redesignIntervalHours = Math.max(1, c.getInt("redesign.interval-hours", 24));
        this.redesignDemolishHours = Math.max(0, c.getInt("redesign.demolish-hours", 2));
        this.redesignLevelBlocksPerDay = Math.max(0, c.getInt("redesign.level-blocks-per-day", 4000));
        this.redesignLevelCostPerColumn = Math.max(0, c.getLong("redesign.level-cost-per-column", 1));
        this.redesignAdminPays = c.getBoolean("redesign.admin-pays", true);
        this.heritage = java.util.Set.copyOf(c.getStringList("rebuild.heritage").isEmpty()
            ? List.of("kosciol", "katedra", "zamek", "palac", "pomnik", "ratusz", "cmentarz")
            : c.getStringList("rebuild.heritage"));
        this.autoClaimForPlayers = c.getBoolean("planner.auto-claim-for-players", false);
        this.autoClaimMax = Math.max(0, c.getInt("planner.auto-claim-max", 3));
        this.rebuildDemolishHours = Math.max(0, c.getInt("rebuild.demolish-hours", 6));
        this.rebuildOldTownRadius = Math.max(0, c.getInt("rebuild.old-town-radius", 22));
        this.villageMinFarmland = Math.max(1, c.getInt("villages.min-farmland-per-chunk", 4));
        this.villageHouseReach = Math.max(3, Math.min(16, c.getInt("villages.house-reach", 7)));
        this.villageFarmlandPerField = Math.max(1, c.getInt("villages.farmland-per-field", 40));
        final List<Map<String, String>> mats = new ArrayList<>();
        final ConfigurationSection ms = c.getConfigurationSection("materials");
        for (int i = 0; i < pl.medivalrpg.ludnosc.template.MaterialPalette.DEFAULTS.size(); i++) {
            final Map<String, String> m = new java.util.HashMap<>();
            final ConfigurationSection lvl = ms == null ? null : ms.getConfigurationSection(Integer.toString(i));
            if (lvl != null) {
                for (final String k : lvl.getKeys(false)) {
                    m.put(k, lvl.getString(k));
                }
            }
            mats.add(m);
        }
        this.materials = new pl.medivalrpg.ludnosc.template.MaterialPalette(mats);
        this.blocksPerSecond = Math.max(0.05, c.getDouble("building.blocks-per-second", 2.0));
        this.buildersPerSite = Math.max(1, Math.min(8, c.getInt("building.builders-per-site", 2)));
        this.maxBlocksPerTick = Math.max(1, c.getInt("building.max-blocks-per-tick", 40));
        this.offlineProgress = c.getBoolean("building.offline-progress", true);
        this.maxTerrainCut = Math.max(0, c.getInt("building.max-terrain-cut", 2));
        this.maxFoundationDepth = Math.max(0, c.getInt("building.max-foundation-depth", 12));
        this.refundOnCancel = clamp01(c.getDouble("building.refund-on-cancel", 0.5));
        this.previewSeconds = Math.max(10, c.getInt("building.preview-seconds", 90));
        final Set<Material> terrain = EnumSet.noneOf(Material.class);
        for (final String s : c.getStringList("building.terrain-blocks")) {
            final Material m = Material.matchMaterial(s);
            if (m != null) {
                terrain.add(m);
            } else if (logger != null) {
                logger.warning("Nieznany blok w building.terrain-blocks: " + s);
            }
        }
        this.terrainBlocks = Set.copyOf(terrain);
        this.builderType = parseEnum(BuilderType.class, c.getString("building.builder.type"), BuilderType.MANNEQUIN, logger);
        this.builderSkins = List.copyOf(c.getStringList("building.builder.skins"));
        final List<Material> tools = new ArrayList<>();
        for (final String s : c.getStringList("building.builder.tools")) {
            final Material m = Material.matchMaterial(s);
            if (m != null) {
                tools.add(m);
            }
        }
        if (tools.isEmpty()) {
            tools.add(Material.IRON_AXE);
        }
        this.builderTools = List.copyOf(tools);
        this.builderMoveTicks = Math.max(5, c.getInt("building.builder.move-interval-ticks", 30));
        this.hologram = c.getBoolean("building.hologram", true);
        this.effects = c.getBoolean("building.effects", true);

        this.registerMaxVolume = Math.max(1, c.getLong("register.max-volume", 40000));
        this.exportBuiltins = c.getBoolean("templates.export-builtins", true);
        this.schematicDefaultCost = Math.max(0, c.getLong("templates.schematic-default-cost", 3000));
        this.verifyRotation = c.getBoolean("templates.verify-rotation", true);
        this.autosaveMinutes = Math.max(1, c.getInt("storage.autosave-minutes", 5));
    }

    private static Number number(final Object o) {
        if (o instanceof Number n) {
            return n;
        }
        try {
            return o == null ? 0 : Long.parseLong(o.toString().trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** MC time of day inside the work hours (always true when work hours are disabled). */
    public boolean workTime(final long worldTime) {
        if (!this.workHours) {
            return true;
        }
        final int t = (int) Math.floorMod(worldTime, 24000L);
        return this.workStart <= this.workEnd ? t >= this.workStart && t < this.workEnd : t >= this.workStart || t < this.workEnd;
    }

    /** Food points of one item of this material (0 = not food). */
    public int foodValue(final Material material) {
        return this.foodItems.getOrDefault(material, 0);
    }

    private static Map<String, Integer> intMap(final ConfigurationSection s) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        if (s != null) {
            for (final String key : s.getKeys(false)) {
                final int v = s.getInt(key);
                if (v > 0) {
                    out.put(Economy.professionKey(key), v);
                }
            }
        }
        if (out.isEmpty()) {
            out.put("none", 1);
        }
        return Map.copyOf(out);
    }

    private static List<String> lower(final List<String> in) {
        final List<String> out = new ArrayList<>();
        for (final String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }

    private static double clamp01(final double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static <E extends Enum<E>> E parseEnum(final Class<E> type, final String value, final E def, final Logger logger) {
        if (value == null || value.isBlank()) {
            return def;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            if (logger != null) {
                logger.warning("Zła wartość '" + value + "' (" + type.getSimpleName() + "), używam " + def);
            }
            return def;
        }
    }

    private static LocalTime parseTime(final String value, final Logger logger) {
        try {
            return LocalTime.parse(value.trim().length() == 4 ? "0" + value.trim() : value.trim());
        } catch (final RuntimeException e) {
            if (logger != null) {
                logger.warning("Zła godzina daily.time: " + value + ", używam 06:00");
            }
            return LocalTime.of(6, 0);
        }
    }

    private static ZoneId parseZone(final String value, final Logger logger) {
        try {
            return ZoneId.of(value.trim());
        } catch (final RuntimeException e) {
            if (logger != null) {
                logger.warning("Zła strefa czasowa daily.timezone: " + value + ", używam strefy serwera");
            }
            return ZoneId.systemDefault();
        }
    }
}
