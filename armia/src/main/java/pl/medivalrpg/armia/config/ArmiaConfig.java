// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EquipmentSlot;
import pl.medivalrpg.armia.logic.AiRules;
import pl.medivalrpg.armia.logic.AiTuning;
import pl.medivalrpg.armia.logic.Payroll;
import pl.medivalrpg.armia.logic.Personality;
import pl.medivalrpg.armia.logic.TargetSelector;
import pl.medivalrpg.kraje.model.Rank;

/** Parsed config.yml. Immutable; rebuilt on reload. */
public final class ArmiaConfig {

    public enum Visual { MANNEQUIN, MOB }

    public enum UpkeepTrigger { INTERVAL, TREASURY_TICK }

    /**
     * Mobs that may carry a soldier. All use the classic goal selector (Paper {@code MobGoals} works on them);
     * brain-AI mobs (villager, piglin, axolotl...) are not allowed because their AI ignores goals.
     */
    public static final Set<EntityType> ALLOWED_MOBS = Set.of(
        EntityType.WANDERING_TRADER, EntityType.ZOMBIE, EntityType.HUSK, EntityType.ZOMBIE_VILLAGER,
        EntityType.SKELETON, EntityType.STRAY, EntityType.VINDICATOR, EntityType.PILLAGER,
        EntityType.EVOKER, EntityType.ILLUSIONER, EntityType.WITCH);

    public final Visual visual;
    public final EntityType brainType;
    public final boolean noCollision;
    public final boolean showNames;

    public final int globalMax;
    public final int countryBase;
    public final double countryPerChunk;
    public final int countryMax;

    public final double thinkBudgetMs;
    public final int thinkIntervalTicks;
    public final int maxSpawnsPerTick;
    public final int repairSeconds;

    public final Rank recruitRank;
    public final Rank commandRank;
    public final boolean requireCitizen;
    public final int maxPerCommand;
    public final double citizenMaxDistance;
    public final boolean barracksRequired;
    public final Material barracksBlock;
    public final int barracksRadius;
    public final String barracksBuilding;
    /** What barracks give: limit per bunk, muster point of recruits, garrison healing ({@code barracks.*}). */
    public final pl.medivalrpg.armia.logic.BarracksRules.Settings barracks;
    public final pl.medivalrpg.armia.logic.BuildBudget.Rules buildBudget;
    public final long aiWarDeclareCost;

    public final UpkeepTrigger upkeepTrigger;
    public final int upkeepIntervalMinutes;
    public final int upkeepMaxCatchUp;
    public final Payroll.Rules payroll;
    public final boolean deserterToPopulation;

    public final double detectRadius;
    public final double leashExtra;
    public final TargetSelector.Rules targetRules;
    public final boolean protectFromNonEnemies;
    public final boolean protectAllowCountryless;
    public final double followDistance;
    public final double followTeleportDistance;
    public final double chaseSpeed;
    public final double regenPerMinute;
    public final double guardRadiusDefault;
    public final double guardRadiusMax;
    public final double squadGatherRadius;
    public final int patrolMaxPoints;
    public final Material maceMaterial;
    public final NamespacedKey maceModel;
    public final int maceRange;

    public final boolean aiEnabled;
    public final int aiTickMinutes;
    public final Personality aiDefaults;
    public final AiTuning tuning;
    /** AI v2 knobs ({@code ai.v2}). */
    public final AiRules rules;
    public final long aiProposalExpiryMinutes;
    public final long aiLedgerKeepHours;
    public final boolean aiRequireCitizen;
    public final double aiGuardFraction;
    public final double aiGuardRadius;
    public final long aiWarCooldownMinutes;
    public final long aiProposalCooldownMinutes;
    public final boolean aiBuildings;
    public final List<String> aiHousingTypes;
    public final List<String> aiUtilityTypes;
    public final int aiMaxHousing;
    public final boolean aiSieges;
    public final double aiSiegePlayerRadius;
    public final int aiSiegeSoldiers;
    public final long aiSiegeCooldownMinutes;
    public final long aiClaimCostEstimate;
    public final int aiLogSize;
    public final boolean aiLogFile;
    /** Closed world money pool (Skarbiec 2026-10-28): auto = detected from the world accounts, else forced on/off. */
    public final String aiEconomyClosedPool;
    /** Income of NPC countries comes from the world operating budget; this much is always left for other systems. */
    public final long aiEconomyPaymentsFloor;
    /** Max share of the operating budget above the floor one NPC country may take per AI tick. */
    public final double aiEconomyPaymentsShare;
    /** Days of income + savings an army must be payable from before the AI recruits more (closed pool only). */
    public final double aiEconomySustainDays;
    public final pl.medivalrpg.armia.logic.NavalPlanner.Settings naval;
    /** Long-range landings computed in the background ({@code ai.naval.long-range}, 2026-10-31). */
    public final pl.medivalrpg.armia.logic.NavalPlanner.LongRange navalLong;
    /** Background wars of NPC countries far from players ({@code ai.conquest}). */
    public final pl.medivalrpg.armia.logic.conquest.ConquestSettings conquest;
    public final boolean aiArtillery;
    /** NPC rams sent to a physical siege drive to a gate or wall of the besieged chunk and strike it. */
    public final boolean aiRams;
    /** Ram parameters ({@code units.taran.ram}); defaults when the unit or section is missing. */
    public final pl.medivalrpg.armia.logic.SiegeRules.RamSettings ram;
    /** Landings only where players are near (like land sieges). false only for isolated tests. */
    public final boolean aiNavalRequirePlayers;
    public final int aiArtilleryShotsPerRun;
    public final long aiRailBaseFee;
    public final List<Long> tributeAmounts;
    public final long tributeCopperPerOpinion;
    public final Rank diplomacyRank;
    public final List<String> rulerNames;
    public final String rulerDefaultSkin;
    /** ruler.admin-kill: a sneaking player with armia.admin may hurt a ruler. */
    public final boolean rulerAdminKill;
    /** scenario.*: land search when a scenario target lies in water. */
    public final int scenarioMaxSearchRadius;
    public final int scenarioSearchStep;
    public final int scenarioStepTimeoutSeconds;
    /** villages.*: taking over villages (command and automat). */
    public final pl.medivalrpg.armia.logic.VillageRules.Rules villageRules;
    public final int villageFindRadius;
    public final int villageScanIntervalSeconds;
    public final List<String> villageNames;
    public final List<String> villageRulers;
    public final boolean npcCreateForce;

    public final Map<String, UnitType> units;
    /** Era data of the regular units (Postep): era, discovery, replaces, camo, healing. */
    public final Map<String, pl.medivalrpg.armia.logic.Eras.UnitEra> unitEras;
    public final pl.medivalrpg.armia.logic.Eras.Rules eraRules;
    /** Mercenary types (mercenaries.types), built from a base unit with higher cost and wage, without country colours. */
    public final Map<String, UnitType> mercenaries;
    public final boolean mercenariesEnabled;
    public final boolean mercenariesAi;
    public final pl.medivalrpg.armia.logic.Mercenaries.Rules mercRules;

    /** {@code ai.naval.long-range} with defaults and clamps; {@code null} section = defaults. */
    public static pl.medivalrpg.armia.logic.NavalPlanner.LongRange longRange(final org.bukkit.configuration.ConfigurationSection c) {
        final var lr = pl.medivalrpg.armia.logic.NavalPlanner.LongRange.defaults();
        if (c == null) {
            return lr;
        }
        final int min = Math.max(1, Math.min(240, c.getInt("min-minutes", lr.minMinutes())));
        return new pl.medivalrpg.armia.logic.NavalPlanner.LongRange(
            c.getBoolean("enabled", lr.enabled()),
            Math.max(0, Math.min(120, c.getDouble("minutes-per-1000-blocks", lr.minutesPer1000()))),
            min,
            Math.max(min, Math.min(1440, c.getInt("max-minutes", lr.maxMinutes()))),
            Math.max(5, Math.min(10080, c.getInt("wait-minutes", lr.waitMinutes()))),
            Math.max(2, Math.min(32, c.getInt("max-soldiers", lr.maxSoldiers()))),
            Math.max(0, Math.min(0.9, c.getDouble("home-guard-share", lr.homeGuardShare()))),
            c.getBoolean("vs-npc", lr.vsNpc()),
            Math.max(0, Math.min(60, c.getInt("approach-warning-minutes", lr.approachMinutes()))));
    }

    /** {@code barracks.*}; a missing section or key (older production config) = defaults from the code. */
    public static pl.medivalrpg.armia.logic.BarracksRules.Settings barracks(final org.bukkit.configuration.ConfigurationSection c) {
        final pl.medivalrpg.armia.logic.BarracksRules.Settings d = pl.medivalrpg.armia.logic.BarracksRules.Settings.defaults();
        if (c == null) {
            return d;
        }
        return new pl.medivalrpg.armia.logic.BarracksRules.Settings(
            c.getInt("limit-per-bed", d.limitPerBed()), c.getInt("limit-bonus-max", d.limitBonusMax()),
            c.getInt("max-beds-per-building", d.maxBedsPerBuilding()), c.getInt("max-damage-percent", d.maxDamagePercent()),
            c.getBoolean("recruit-spawn", d.recruitSpawn()), c.getDouble("recruit-follow-distance", d.recruitFollowDistance()),
            c.getDouble("heal-radius", d.healRadius()), c.getDouble("heal-per-minute", d.healPerMinute()),
            c.getInt("heal-interval-seconds", d.healIntervalSeconds()), c.getBoolean("heal-particles", d.healParticles()));
    }

    public ArmiaConfig(final FileConfiguration c, final Logger log) {
        this.visual = enumOf(Visual.class, c.getString("visual", "mannequin"), Visual.MANNEQUIN, log, "visual");
        this.brainType = mobType(c.getString("brain-entity", "WANDERING_TRADER"), EntityType.WANDERING_TRADER, log, "brain-entity");
        this.noCollision = c.getBoolean("no-collision", true);
        this.showNames = c.getBoolean("show-names", false);

        this.globalMax = Math.max(0, c.getInt("limits.global-max", 1000));
        this.countryBase = Math.max(0, c.getInt("limits.country-base", 5));
        this.countryPerChunk = Math.max(0, c.getDouble("limits.country-per-chunk", 0.5));
        this.countryMax = Math.max(0, c.getInt("limits.country-max", 60));

        this.thinkBudgetMs = Math.max(0.1, c.getDouble("cpu.think-budget-ms", 2.0));
        this.thinkIntervalTicks = Math.max(2, c.getInt("cpu.think-interval-ticks", 10));
        this.maxSpawnsPerTick = Math.max(1, c.getInt("cpu.max-spawns-per-tick", 4));
        this.repairSeconds = Math.max(5, c.getInt("cpu.repair-seconds", 30));

        this.recruitRank = rank(c.getString("recruit.rank", "ADVISOR"), Rank.ADVISOR, log);
        this.commandRank = rank(c.getString("command-rank", "ADVISOR"), Rank.ADVISOR, log);
        this.requireCitizen = c.getBoolean("recruit.require-citizen", true);
        this.maxPerCommand = Math.max(1, c.getInt("recruit.max-per-command", 10));
        this.citizenMaxDistance = Math.max(4, c.getDouble("recruit.citizen-max-distance", 48));
        this.barracksRequired = c.getBoolean("recruit.barracks.required", false);
        this.barracksBlock = material(c.getString("recruit.barracks.block", "SMITHING_TABLE"), Material.SMITHING_TABLE, log, "recruit.barracks.block");
        this.barracksRadius = Math.max(1, c.getInt("recruit.barracks.radius", 12));
        this.barracksBuilding = c.getString("recruit.barracks.building", "koszary");
        this.barracks = barracks(c.getConfigurationSection("barracks"));
        final pl.medivalrpg.armia.logic.BuildBudget.Rules bd = pl.medivalrpg.armia.logic.BuildBudget.Rules.defaults();
        this.buildBudget = new pl.medivalrpg.armia.logic.BuildBudget.Rules(
            c.getDouble("ai.tuning.build-base-share", bd.baseShare()),
            c.getDouble("ai.tuning.build-wealth-share", bd.wealthShare()),
            c.getDouble("ai.tuning.build-expansion-share", bd.expansionShare()),
            c.getDouble("ai.tuning.build-war-cut", bd.warCut()),
            c.getDouble("ai.tuning.build-max-share", bd.maxShare()),
            c.getLong("ai.tuning.build-min-budget", bd.minBudget()),
            c.getInt("ai.tuning.rich-wealth", bd.richWealth()),
            c.getLong("ai.tuning.rich-treasury", bd.richTreasury()),
            c.getDouble("ai.tuning.build-savings-share", bd.savingsShare()));
        this.aiWarDeclareCost = Math.max(-1, c.getLong("ai.war-declare-cost", -1));

        this.upkeepTrigger = enumOf(UpkeepTrigger.class, c.getString("upkeep.trigger", "interval").replace('-', '_'), UpkeepTrigger.INTERVAL, log, "upkeep.trigger");
        this.upkeepIntervalMinutes = Math.max(1, c.getInt("upkeep.interval-minutes", 1440));
        this.upkeepMaxCatchUp = Math.max(1, c.getInt("upkeep.max-catch-up", 3));
        this.payroll = new Payroll.Rules(100, c.getInt("upkeep.morale-gain-paid", 10), c.getInt("upkeep.morale-loss-unpaid", 34), c.getInt("upkeep.desert-below", 1));
        this.deserterToPopulation = c.getBoolean("upkeep.deserter-to-population", true);

        this.detectRadius = Math.max(4, c.getDouble("combat.detect-radius", 14));
        this.leashExtra = Math.max(0, c.getDouble("combat.leash-extra", 12));
        this.targetRules = new TargetSelector.Rules(
            c.getBoolean("combat.attack-countryless-players", false),
            c.getBoolean("combat.monsters-on-own-land", true),
            c.getBoolean("combat.monsters-anywhere", false));
        this.protectFromNonEnemies = c.getBoolean("combat.only-enemies-can-hurt", true);
        this.protectAllowCountryless = c.getBoolean("combat.countryless-can-hurt", false);
        this.followDistance = Math.max(1.5, c.getDouble("combat.follow-distance", 3.0));
        this.followTeleportDistance = Math.max(8, c.getDouble("combat.follow-teleport-distance", 40));
        this.chaseSpeed = Math.max(0.5, c.getDouble("combat.chase-speed", 1.25));
        this.regenPerMinute = Math.max(0, c.getDouble("combat.regen-per-minute", 1.0));
        this.guardRadiusDefault = Math.max(1, c.getDouble("combat.guard-radius", 8));
        this.guardRadiusMax = Math.max(this.guardRadiusDefault, c.getDouble("combat.guard-radius-max", 32));
        this.squadGatherRadius = Math.max(4, c.getDouble("combat.squad-gather-radius", 32));
        this.patrolMaxPoints = Math.max(2, c.getInt("combat.patrol-max-points", 10));
        this.maceMaterial = material(c.getString("mace.material", "STICK"), Material.STICK, log, "mace.material");
        this.maceModel = NamespacedKey.fromString(c.getString("mace.item-model", "minecraft:mace"));
        this.maceRange = Math.max(8, c.getInt("mace.range", 64));

        this.aiEnabled = c.getBoolean("ai.enabled", true);
        this.aiTickMinutes = Math.max(1, c.getInt("ai.tick-minutes", 5));
        this.aiDefaults = new Personality(
            c.getInt("ai.defaults.agresja", 50), c.getInt("ai.defaults.ekspansja", 50),
            c.getInt("ai.defaults.dyplomacja", 50), c.getInt("ai.defaults.bogactwo", 50),
            c.getInt("ai.defaults.max-chunki", 40), c.getInt("ai.defaults.max-zolnierze", 20), true);
        final AiTuning d = AiTuning.defaults();
        final String t = "ai.tuning.";
        this.tuning = new AiTuning(
            c.getLong(t + "base-income-per-day", d.baseIncomePerDay()),
            c.getLong(t + "income-per-chunk-per-day", d.incomePerChunkPerDay()),
            c.getDouble(t + "expansion-chance", d.expansionChance()),
            c.getDouble(t + "reserve-days-of-wages", d.reserveDaysOfWages()),
            c.getLong(t + "min-reserve", d.minReserve()),
            c.getDouble(t + "soldiers-per-chunk", d.soldiersPerChunk()),
            c.getInt(t + "max-recruit-per-tick", d.maxRecruitPerTick()),
            c.getInt(t + "min-aggression-for-war", d.minAggressionForWar()),
            c.getDouble(t + "war-advantage", d.warAdvantage()),
            c.getDouble(t + "war-advantage-aggression", d.warAdvantageAggression()),
            c.getDouble(t + "war-chance", d.warChance()),
            c.getInt(t + "max-concurrent-wars", d.maxConcurrentWars()),
            c.getInt(t + "war-opinion-block", d.warOpinionBlock()),
            c.getDouble(t + "min-war-hours", d.minWarHours()),
            c.getDouble(t + "fatigue-hours", d.fatigueHours()),
            c.getInt(t + "loss-tolerance", d.lossTolerance()),
            c.getDouble(t + "losing-ratio", d.losingRatio()),
            c.getDouble(t + "peace-threshold", d.peaceThreshold()),
            c.getDouble(t + "alliance-threshold", d.allianceThreshold()),
            c.getInt(t + "min-diplomacy-for-alliance", d.minDiplomacyForAlliance()),
            c.getInt(t + "alliance-propose-diplomacy", d.allianceProposeDiplomacy()),
            c.getDouble(t + "alliance-propose-chance", d.allianceProposeChance()),
            c.getDouble(t + "player-weight", d.playerWeight()),
            c.getDouble(t + "offline-player-weight", d.offlinePlayerWeight()));
        this.rules = rules(c);
        this.aiProposalExpiryMinutes = Math.max(1, c.getLong("ai.proposal-expiry-minutes", 5));
        this.aiLedgerKeepHours = Math.max(24, c.getLong("ai.v2.ledger-keep-hours", 48));
        this.aiRequireCitizen = c.getBoolean("ai.require-citizen", false);
        this.aiGuardFraction = clamp01(c.getDouble("ai.border-guard-fraction", 0.6));
        this.aiGuardRadius = Math.max(2, c.getDouble("ai.border-guard-radius", 7));
        this.aiWarCooldownMinutes = Math.max(0, c.getLong("ai.war-cooldown-minutes", 180));
        this.aiProposalCooldownMinutes = Math.max(1, c.getLong("ai.proposal-cooldown-minutes", 60));
        this.aiBuildings = c.getBoolean("ai.buildings.enabled", true);
        this.aiHousingTypes = lower(c.getStringList("ai.buildings.housing"), List.of("dom", "chata"));
        this.aiUtilityTypes = lower(c.getStringList("ai.buildings.utilities"), List.of("spichlerz", "koszary", "studnia", "kuznia", "wieza"));
        this.aiMaxHousing = Math.max(0, c.getInt("ai.buildings.max-housing", 12));
        this.aiSieges = c.getBoolean("ai.sieges.enabled", true);
        this.aiSiegePlayerRadius = Math.max(16, c.getDouble("ai.sieges.player-radius", 96));
        this.aiSiegeSoldiers = Math.max(1, c.getInt("ai.sieges.soldiers", 8));
        this.aiSiegeCooldownMinutes = Math.max(0, c.getLong("ai.sieges.cooldown-minutes", 30));
        this.aiEconomyClosedPool = c.getString("ai.economy.closed-pool", "auto");
        this.aiEconomyPaymentsFloor = Math.max(0, c.getLong("ai.economy.payments-floor", 100_000));
        this.aiEconomyPaymentsShare = Math.max(0, Math.min(0.05, c.getDouble("ai.economy.payments-share-per-tick", 0.002)));
        this.conquest = pl.medivalrpg.armia.logic.conquest.ConquestSettings.from(c.getConfigurationSection("ai.conquest"));
        this.aiEconomySustainDays = Math.max(1, Math.min(60, c.getDouble("ai.economy.sustain-days", 14)));
        this.naval = new pl.medivalrpg.armia.logic.NavalPlanner.Settings(
            c.getBoolean("ai.naval.enabled", true),
            Math.max(2, c.getInt("ai.naval.min-soldiers", 4)),
            Math.max(2, Math.min(32, c.getInt("ai.naval.max-soldiers", 12))),
            Math.max(32, Math.min(512, c.getInt("ai.naval.max-range", 256))),
            Math.max(5, c.getLong("ai.naval.cooldown-minutes", 90)),
            Math.max(2, Math.min(60, c.getLong("ai.naval.timeout-minutes", 15))),
            Math.max(0, Math.min(100, c.getInt("ai.naval.min-aggression", 40))),
            Math.max(0.1, c.getDouble("ai.naval.min-advantage", 0.9)),
            Math.max(1, Math.min(24, c.getInt("ai.naval.max-operations", 3))));
        this.aiArtillery = c.getBoolean("ai.sieges.artillery", true);
        this.aiRams = c.getBoolean("ai.sieges.rams", true);
        this.ram = ram(c.getConfigurationSection("units.taran.ram"));
        this.aiNavalRequirePlayers = c.getBoolean("ai.naval.require-players-near", true);
        this.navalLong = longRange(c.getConfigurationSection("ai.naval.long-range"));
        this.aiArtilleryShotsPerRun = Math.max(0, Math.min(16, c.getInt("ai.sieges.artillery-shots-per-run", 4)));
        this.aiRailBaseFee = Math.max(0, c.getLong("ai.rail.base-fee", 500));
        this.aiClaimCostEstimate = Math.max(0, c.getLong("ai.claim-cost-estimate", 100));
        this.aiLogSize = Math.max(10, c.getInt("ai.log.size", 200));
        this.aiLogFile = c.getBoolean("ai.log.file", true);
        final List<Long> amounts = new ArrayList<>();
        for (final Object o : c.getList("diplomacy.tribute-amounts", List.of(100, 1000, 5000))) {
            if (o instanceof Number n && n.longValue() > 0) {
                amounts.add(n.longValue());
            }
        }
        this.tributeAmounts = amounts.isEmpty() ? List.of(100L, 1000L, 5000L) : List.copyOf(amounts);
        this.tributeCopperPerOpinion = Math.max(1, c.getLong("diplomacy.copper-per-opinion", 50));
        this.diplomacyRank = rank(c.getString("diplomacy.rank", "RULER"), Rank.RULER, log);
        this.rulerNames = c.getStringList("ruler.names").isEmpty() ? List.of("Bolesław", "Mieszko", "Kazimierz", "Władysław", "Przemysł", "Leszek", "Jadwiga", "Dobrawa") : List.copyOf(c.getStringList("ruler.names"));
        this.rulerDefaultSkin = c.getString("ruler.default-skin", "");
        this.rulerAdminKill = c.getBoolean("ruler.admin-kill", false);
        this.scenarioMaxSearchRadius = Math.max(32, Math.min(4000, c.getInt("scenario.max-search-radius", 800)));
        this.scenarioSearchStep = Math.max(16, Math.min(256, c.getInt("scenario.search-step", 48)));
        this.scenarioStepTimeoutSeconds = Math.max(2, Math.min(120, c.getInt("scenario.step-timeout-seconds", 15)));
        final pl.medivalrpg.armia.logic.VillageRules.Rules vd = pl.medivalrpg.armia.logic.VillageRules.Rules.defaults();
        this.villageRules = new pl.medivalrpg.armia.logic.VillageRules.Rules(c.getBoolean("villages.auto.enabled", vd.enabled()),
            clamp01(c.getDouble("villages.auto.chance", vd.chance())), Math.max(0, c.getInt("villages.auto.min-spawn-distance", vd.minSpawnDistance())),
            Math.max(0, c.getInt("villages.auto.min-country-distance", vd.minCountryDistance())), Math.max(0, c.getInt("villages.auto.max-countries", vd.maxCountries())),
            Math.max(0, c.getInt("villages.auto.max-auto-countries", vd.maxAutoCountries())), Math.max(0, c.getInt("villages.auto.scan-radius", vd.scanRadius())),
            Math.max(64, c.getInt("villages.auto.scan-step", vd.scanStep())));
        this.villageFindRadius = Math.max(16, Math.min(256, c.getInt("villages.find-radius", 96)));
        this.villageScanIntervalSeconds = Math.max(1, c.getInt("villages.auto.interval-seconds", 5));
        this.villageNames = c.getStringList("villages.names").isEmpty() ? List.of("Księstwo_Wiślan", "Ziemia_Sądecka", "Hrabstwo_Pomorskie")
            : c.getStringList("villages.names").stream().map(s -> s.trim().replace(' ', '_')).filter(s -> !s.isEmpty()).toList();
        this.villageRulers = c.getStringList("villages.rulers").isEmpty() ? List.of("Bolesław", "Mieszko", "Kazimierz") : List.copyOf(c.getStringList("villages.rulers"));
        this.npcCreateForce = c.getBoolean("ai.create-force", false);

        this.units = Collections.unmodifiableMap(units(c.getConfigurationSection("units"), log));
        this.unitEras = Collections.unmodifiableMap(unitEras(c.getConfigurationSection("units"), this.units));
        final pl.medivalrpg.armia.logic.Eras.Rules er = pl.medivalrpg.armia.logic.Eras.Rules.defaults();
        this.eraRules = new pl.medivalrpg.armia.logic.Eras.Rules(Math.max(0, c.getDouble("eras.hp-bonus-per-era", er.hpBonusPerEra())),
            Math.max(0, c.getDouble("eras.damage-bonus-per-era", er.damageBonusPerEra())), Math.max(0, c.getDouble("eras.rearm-cost-share", er.rearmCostShare())),
            Math.max(0, c.getInt("eras.ai-rearm-per-tick", er.aiRearmPerTick())));
        final pl.medivalrpg.armia.logic.Mercenaries.Rules md = pl.medivalrpg.armia.logic.Mercenaries.Rules.defaults();
        this.mercenariesEnabled = c.getBoolean("mercenaries.enabled", true);
        this.mercenariesAi = c.getBoolean("mercenaries.ai.enabled", true);
        this.mercRules = new pl.medivalrpg.armia.logic.Mercenaries.Rules(
            Math.max(0, c.getDouble("mercenaries.cost-multiplier", md.costMultiplier())),
            Math.max(0, c.getDouble("mercenaries.wage-multiplier", md.wageMultiplier())),
            Math.max(1, c.getLong("mercenaries.contract-hours", md.contractHours())),
            Math.max(0, c.getDouble("mercenaries.extend-cost-share", md.extendCostShare())),
            clamp01(c.getDouble("mercenaries.max-share", md.maxShare())),
            c.getBoolean("mercenaries.defect-to-enemy", md.defect()),
            Math.max(0, c.getLong("mercenaries.defect-treasury-below", md.defectBelow())),
            Math.max(0, c.getInt("mercenaries.ai.max-population", md.aiMaxPopulation())),
            Math.max(0, c.getLong("mercenaries.ai.min-treasury", md.aiMinTreasury())),
            Math.max(0, c.getInt("mercenaries.ai.per-tick", md.aiPerTick())));
        this.mercenaries = Collections.unmodifiableMap(mercenaries(c.getConfigurationSection("mercenaries.types"), this.units, this.mercRules, log));
    }

    /** A recruitable (regular) unit type. */
    public UnitType unit(final String id) {
        return id == null ? null : this.units.get(id.toLowerCase(Locale.ROOT));
    }

    /** Era data of a unit (era 1 without requirements for mercenaries and unknown ids). */
    public pl.medivalrpg.armia.logic.Eras.UnitEra unitEra(final String id) {
        final pl.medivalrpg.armia.logic.Eras.UnitEra e = id == null ? null : this.unitEras.get(id.toLowerCase(Locale.ROOT));
        return e == null ? pl.medivalrpg.armia.logic.Eras.UnitEra.MEDIEVAL : e;
    }

    private static Map<String, pl.medivalrpg.armia.logic.Eras.UnitEra> unitEras(final ConfigurationSection section, final Map<String, UnitType> units) {
        final Map<String, pl.medivalrpg.armia.logic.Eras.UnitEra> map = new LinkedHashMap<>();
        if (section == null) {
            return map;
        }
        for (final String id : units.keySet()) {
            final ConfigurationSection u = section.getConfigurationSection(id);
            if (u == null) {
                continue;
            }
            final List<String> replaces = u.isList("replaces") ? lower(u.getStringList("replaces"), List.of())
                : u.getString("replaces", "").isBlank() ? List.of() : List.of(u.getString("replaces").trim().toLowerCase(Locale.ROOT));
            map.put(id, new pl.medivalrpg.armia.logic.Eras.UnitEra(u.getInt("era", 1), u.getString("discovery", ""), replaces,
                u.getBoolean("camo", false), u.getDouble("heal-per-second", 0)));
        }
        return map;
    }

    /** A mercenary type. */
    public UnitType mercenary(final String id) {
        return id == null ? null : this.mercenaries.get(id.toLowerCase(Locale.ROOT));
    }

    /** Type of an existing soldier: regular or mercenary. */
    public UnitType soldierUnit(final String id) {
        final UnitType u = this.unit(id);
        return u != null ? u : this.mercenary(id);
    }

    // ------------------------------------------------------------------ units

    /**
     * Mercenary types: a copy of {@code base} with its own name, skin and equipment, cost × cost-multiplier and wage ×
     * wage-multiplier (unless {@code cost}/{@code wage} are given), never in country colours.
     */
    private static Map<String, UnitType> mercenaries(final ConfigurationSection section, final Map<String, UnitType> units,
                                                     final pl.medivalrpg.armia.logic.Mercenaries.Rules rules, final Logger log) {
        final Map<String, UnitType> map = new LinkedHashMap<>();
        if (section == null) {
            return map;
        }
        for (final String id : section.getKeys(false)) {
            final ConfigurationSection m = section.getConfigurationSection(id);
            if (m == null || !m.getBoolean("enabled", true)) {
                continue;
            }
            final String key = id.toLowerCase(Locale.ROOT);
            final UnitType base = units.get(m.getString("base", "piechota").toLowerCase(Locale.ROOT));
            if (base == null) {
                log.warning("mercenaries.types." + id + ": nieznana jednostka bazowa " + m.getString("base"));
                continue;
            }
            if (units.containsKey(key)) {
                log.warning("mercenaries.types." + id + ": nazwa zajęta przez zwykłą jednostkę");
                continue;
            }
            final Map<EquipmentSlot, Material> eq = new EnumMap<>(EquipmentSlot.class);
            final ConfigurationSection es = m.getConfigurationSection("equipment");
            if (es != null) {
                for (final String slot : es.getKeys(false)) {
                    final EquipmentSlot s = slot(slot);
                    final Material mat = material(es.getString(slot), null, log, "mercenaries.types." + id + ".equipment." + slot);
                    if (s != null && mat != null && mat != Material.AIR) {
                        eq.put(s, mat);
                    }
                }
            } else {
                eq.putAll(base.equipment());
            }
            final String company = m.getString("company", "");
            final String description = m.getString("description", base.description()) + (company.isBlank() ? "" : " <dark_gray>(" + company + ")");
            final String armor = m.getString("horse-armor", "");
            map.put(key, new UnitType(key, m.getString("name", "<dark_gray>Najemnik"), description,
                m.contains("cost") ? Math.max(0, m.getLong("cost")) : pl.medivalrpg.armia.logic.Mercenaries.cost(rules, base.cost()),
                m.contains("wage") ? Math.max(0, m.getLong("wage")) : pl.medivalrpg.armia.logic.Mercenaries.wage(rules, base.wage()),
                base.health(), base.damage(), base.range(), base.cooldownTicks(), base.ranged(), base.projectileSpeed(), base.spread(), base.speed(),
                base.siegeStrength(), m.isList("skin") ? String.join(",", m.getStringList("skin")) : m.getString("skin", "losowy"),
                base.mobType(), base.mounted(), base.horseHealth(), base.horseSpeed(),
                armor == null || armor.isBlank() ? base.horseArmor() : material(armor, base.horseArmor(), log, "mercenaries.types." + id + ".horse-armor"),
                Map.copyOf(eq), false, false));
        }
        return map;
    }

    private static Map<String, UnitType> units(final ConfigurationSection section, final Logger log) {
        final Map<String, UnitType> map = new LinkedHashMap<>();
        if (section == null) {
            log.warning("Brak sekcji units w config.yml — nie ma żadnych jednostek");
            return map;
        }
        for (final String id : section.getKeys(false)) {
            final ConfigurationSection u = section.getConfigurationSection(id);
            if (u == null || !u.getBoolean("enabled", true)) {
                continue;
            }
            final String key = id.toLowerCase(Locale.ROOT);
            final Map<EquipmentSlot, Material> eq = new EnumMap<>(EquipmentSlot.class);
            final ConfigurationSection es = u.getConfigurationSection("equipment");
            if (es != null) {
                for (final String slot : es.getKeys(false)) {
                    final EquipmentSlot s = slot(slot);
                    final Material m = material(es.getString(slot), null, log, "units." + id + ".equipment." + slot);
                    if (s != null && m != null && m != Material.AIR) {
                        eq.put(s, m);
                    } else if (s == null) {
                        log.warning("Nieznany slot units." + id + ".equipment." + slot + " (head, chest, legs, feet, hand, offhand)");
                    }
                }
            }
            final String armor = u.getString("horse-armor", "");
            map.put(key, new UnitType(
                key,
                u.getString("name", key),
                u.getString("description", ""),
                Math.max(0, u.getLong("cost", 300)),
                Math.max(0, u.getLong("wage", 20)),
                Math.max(1, u.getDouble("health", 24)),
                Math.max(0, u.getDouble("damage", 4)),
                Math.max(1, u.getDouble("range", u.getBoolean("ranged", false) ? 16 : 2.5)),
                Math.max(4, u.getInt("cooldown-ticks", 20)),
                u.getBoolean("ranged", false),
                Math.max(0.5, u.getDouble("projectile-speed", 1.6)),
                Math.max(0, u.getDouble("spread", 0.04)),
                Math.max(0.05, u.getDouble("speed", 0.3)),
                Math.max(0, u.getDouble("siege-strength", 1.0)),
                u.isList("skin") ? String.join(",", u.getStringList("skin")) : u.getString("skin", "losowy"),
                mobType(u.getString("mob-type", u.getBoolean("ranged", false) ? "SKELETON" : "ZOMBIE"), EntityType.ZOMBIE, log, "units." + id + ".mob-type"),
                u.getBoolean("mounted", false),
                Math.max(1, u.getDouble("horse-health", 40)),
                Math.max(0.05, u.getDouble("horse-speed", 0.3)),
                armor == null || armor.isBlank() ? null : material(armor, null, log, "units." + id + ".horse-armor"),
                Map.copyOf(eq),
                u.getBoolean("dye-leather", true),
                u.getBoolean("color-shield", true)));
        }
        return map;
    }

    /** {@code units.taran.ram}: missing keys keep the defaults of {@link pl.medivalrpg.armia.logic.SiegeRules.RamSettings}. */
    static pl.medivalrpg.armia.logic.SiegeRules.RamSettings ram(final ConfigurationSection r) {
        final pl.medivalrpg.armia.logic.SiegeRules.RamSettings d = pl.medivalrpg.armia.logic.SiegeRules.RamSettings.defaults();
        if (r == null) {
            return d;
        }
        final java.util.Set<String> fort = new java.util.HashSet<>();
        if (r.isList("fortification-blocks")) {
            for (final String m : r.getStringList("fortification-blocks")) {
                fort.add(m.trim().toUpperCase(Locale.ROOT));
            }
        } else {
            fort.addAll(d.fortificationBlocks());
        }
        return new pl.medivalrpg.armia.logic.SiegeRules.RamSettings(
            Math.max(0, Math.min(10, r.getDouble("arrow-multiplier", d.arrowMultiplier()))),
            Math.max(0, Math.min(20, r.getDouble("fire-multiplier", d.fireMultiplier()))),
            Math.max(2, Math.min(10, r.getDouble("head-reach", d.headReach()))),
            Math.max(1, Math.min(4, r.getInt("blocks-per-strike", d.blocksPerStrike()))),
            Math.max(0, Math.min(1, r.getDouble("strength.wood", d.wood()))),
            Math.max(0, Math.min(1, r.getDouble("strength.stone", d.stone()))),
            Math.max(0, Math.min(1, r.getDouble("strength.fortification", d.fortification()))),
            java.util.Set.copyOf(fort),
            Math.max(200, Math.min(72000, r.getInt("order-seconds", d.orderTicks() / 20) * 20)));
    }

    private static EquipmentSlot slot(final String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "head", "helmet", "glowa" -> EquipmentSlot.HEAD;
            case "chest", "chestplate", "tors" -> EquipmentSlot.CHEST;
            case "legs", "leggings", "nogi" -> EquipmentSlot.LEGS;
            case "feet", "boots", "stopy" -> EquipmentSlot.FEET;
            case "hand", "mainhand", "main-hand", "reka" -> EquipmentSlot.HAND;
            case "offhand", "off-hand", "lewa-reka" -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    private static AiRules rules(final FileConfiguration c) {
        final AiRules d = AiRules.defaults();
        final String v = "ai.v2.";
        return new AiRules(
            c.getInt(v + "friendly-opinion", d.friendlyOpinion()),
            c.getInt(v + "hostile-opinion", d.hostileOpinion()),
            c.getDouble(v + "preferred-weight", d.preferredWeight()),
            c.getDouble(v + "preferred-advantage", d.preferredAdvantage()),
            c.getDouble(v + "offensive-war-chance", d.offensiveWarChance()),
            c.getDouble(v + "offensive-advantage", d.offensiveAdvantage()),
            c.getDouble(v + "offensive-siege-cooldown", d.offensiveSiegeCooldown()),
            c.getDouble(v + "defensive-siege-cooldown", d.defensiveSiegeCooldown()),
            c.getDouble(v + "defensive-guard-bonus", d.defensiveGuardBonus()),
            c.getDouble(v + "offensive-guard-cut", d.offensiveGuardCut()),
            c.getDouble(v + "reclaim-weight", d.reclaimWeight()),
            Math.max(0, c.getLong(v + "reclaim-cooldown-minutes", d.reclaimCooldownMinutes())),
            Math.max(0, c.getInt(v + "revenge-war-declared", d.revengeWarDeclared())),
            Math.max(0, c.getInt(v + "revenge-chunk-lost", d.revengeChunkLost())),
            Math.max(0, c.getInt(v + "revenge-siege", d.revengeSiege())),
            Math.max(1, c.getInt(v + "negative-decay-ticks", d.negativeDecayTicks())),
            Math.max(0, c.getDouble(v + "crisis-hours", d.crisisHours())),
            clamp01(c.getDouble(v + "crisis-army-share", d.crisisArmyShare())),
            Math.max(1, c.getDouble(v + "crisis-recruit-multiplier", d.crisisRecruitMultiplier())),
            Math.max(1, c.getDouble(v + "crisis-soldiers-multiplier", d.crisisSoldiersMultiplier())),
            clamp01(c.getDouble(v + "war-army-share", d.warArmyShare())),
            Math.max(0, c.getDouble(v + "rebuild-hours", d.rebuildHours())),
            clamp01(c.getDouble(v + "rebuild-buildings-share", d.rebuildBuildingsShare())),
            Math.max(1, c.getLong(v + "help-cooldown-minutes", d.helpCooldownMinutes())),
            c.getDouble(v + "ally-join-threshold", d.allyJoinThreshold()),
            Math.max(0, c.getDouble(v + "capitulate-ratio", d.capitulateRatio())),
            Math.max(1, c.getInt(v + "capitulate-lost-chunks", d.capitulateLostChunks())),
            clamp01(c.getDouble(v + "capitulate-treasury-drop", d.capitulateTreasuryDrop())),
            Math.max(1, c.getLong(v + "capitulate-cooldown-minutes", d.capitulateCooldownMinutes())),
            c.getDouble(v + "capitulation-threshold", d.capitulationThreshold()),
            Math.max(0, c.getLong(v + "greedy-tribute-per-strength", d.greedyTributePerStrength())),
            Math.max(0, Math.min(100, c.getInt(v + "release-opinion", d.releaseOpinion()))),
            Math.max(0, c.getDouble(v + "buyout-reserve-factor", d.buyoutReserveFactor())),
            Math.max(1, c.getInt(v + "tribute-days", d.tributeDays())),
            Math.max(1, c.getLong(v + "tribute-expiry-minutes", d.tributeExpiryMinutes())),
            Math.max(1, c.getLong(v + "tribute-max", d.tributeMax())),
            Math.max(0.1, c.getDouble(v + "tribute-accept-ratio", d.tributeAcceptRatio())),
            Math.max(0, c.getInt(v + "tribute-refused-opinion", d.tributeRefusedOpinion())));
    }

    // ------------------------------------------------------------------ helpers

    private static double clamp01(final double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static List<String> lower(final List<String> list, final List<String> def) {
        if (list == null || list.isEmpty()) {
            return def;
        }
        return list.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    static Material material(final String name, final Material def, final Logger log, final String path) {
        if (name == null || name.isBlank()) {
            return def;
        }
        final Material m = Material.matchMaterial(name.trim());
        if (m == null) {
            log.warning("Nieznany materiał w " + path + ": " + name);
            return def;
        }
        return m;
    }

    static EntityType mobType(final String name, final EntityType def, final Logger log, final String path) {
        try {
            final EntityType type = EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
            if (!ALLOWED_MOBS.contains(type) || type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass())) {
                log.warning(path + ": " + name + " nie może nieść żołnierza (dozwolone: " + ALLOWED_MOBS + "), używam " + def);
                return def;
            }
            return type;
        } catch (final IllegalArgumentException | NullPointerException e) {
            log.warning("Nieznany typ moba w " + path + ": " + name + ", używam " + def);
            return def;
        }
    }

    static Rank rank(final String name, final Rank def, final Logger log) {
        if (name == null) {
            return def;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "citizen", "obywatel" -> Rank.CITIZEN;
            case "advisor", "doradca" -> Rank.ADVISOR;
            case "ruler", "wladca", "władca" -> Rank.RULER;
            default -> {
                log.warning("Nieznana ranga: " + name + " (CITIZEN, ADVISOR, RULER), używam " + def);
                yield def;
            }
        };
    }

    private static <E extends Enum<E>> E enumOf(final Class<E> type, final String name, final E def, final Logger log, final String path) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException | NullPointerException e) {
            log.warning("Nieznana wartość " + path + ": " + name + ", używam " + def.name().toLowerCase(Locale.ROOT));
            return def;
        }
    }
}
