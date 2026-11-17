// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.config;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.medivalrpg.kraje.economy.DailySchedule;
import pl.medivalrpg.kraje.geometry.RegenPlanner;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.protection.ProtectionService;
import pl.medivalrpg.kraje.protection.PvpRules;
import pl.medivalrpg.kraje.util.Colors;
import pl.medivalrpg.kraje.vassal.VassalRules;

/**
 * Immutable view of config.yml (re-created on {@code /kraj admin reload}). Safe to read from any thread.
 */
public final class KrajeConfig {

    public enum BorderMode { TITLE, ACTIONBAR }

    // country
    public final int nameMinLength;
    public final int nameMaxLength;
    public final int descriptionMaxLength;
    public final int foundMinDistanceChunks;
    public final Set<String> blockedWorlds;
    public final long inviteExpiryMillis;
    public final long proposalExpiryMillis;
    public final long confirmExpiryMillis;
    public final List<Integer> defaultColors;

    // claims
    public final int claimsBase;
    public final int claimsPerMember;

    // spawn
    public final boolean spawnEnabled;
    public final int spawnWarmupSeconds;

    // protection
    public final Protection protection;

    // border
    public final boolean borderEnabled;
    public final BorderMode borderMode;
    public final int titleFadeIn;
    public final int titleStay;
    public final int titleFadeOut;

    // map
    public final boolean mapEnabled;
    public final String mapLayerName;
    public final boolean mapLayerDefaultHidden;
    public final int mapLayerPriority;
    public final int mapLayerZIndex;
    public final double mapFillOpacity;
    public final int mapStrokeWeight;
    public final double mapStrokeOpacity;
    public final boolean mapShowCapitals;
    public final long mapRefreshDebounceTicks;
    public final String mapHoverTemplate;
    public final String mapPopupTemplate;
    public final String mapUrl;

    // tablist
    public final boolean tablistEnabled;
    public final String tablistFormat;
    public final String tablistNoCountryFormat;
    public final String tablistHeader;
    public final String tablistFooter;
    public final String tablistFooterMapLine;
    public final boolean tablistSort;
    public final long tablistRefreshTicks;
    /** Extended tab list ({@code tablist.style}, {@code tablist.extended.*}), jar defaults as fallback. */
    public final pl.medivalrpg.kraje.tablist.TablistSettings tablist;

    // chat
    public final boolean chatEnabled;
    public final String chatFormat;
    public final String chatCountryTag;
    public final String chatNoCountryTag;
    public final String chatTagHover;
    public final String chatTagClickCommand;
    public final String chatCountryFormat;
    public final String chatAllyFormat;
    public final String chatSpyPrefix;
    public final boolean chatLogToConsole;

    // ranks
    private final Map<Rank, String> rankIcons;

    // claims (v2)
    public final double claimsPerNpcCitizen;
    public final int claimsMax;
    /** Extra chunks the Ludnosc planner may claim for an NPC country above its limit (-1 = no limit). */
    public final int claimsNpcPlannerBonus;

    // border (v2)
    public final String borderSoundEnter;
    public final float borderSoundEnterVolume;
    public final float borderSoundEnterPitch;
    public final String borderSoundEnemy;
    public final float borderSoundEnemyVolume;
    public final float borderSoundEnemyPitch;
    public final long borderSoundCooldownMillis;

    // economy (only with Skarbiec)
    public final boolean economyEnabled;
    public final long foundCost;
    public final long claimCostBase;
    public final long claimCostPerChunk;
    public final long upkeepPerChunk;
    public final int upkeepFreeChunks;
    public final int graceDays;
    public final int bankruptcyChunksPerDay;
    public final LocalTime dailyTickTime;
    public final int maxCatchUpDays;
    public final int treasuryLogSize;
    public final long taxMax;
    public final boolean taxExemptRuler;
    public final int taxKickAfterDays;

    // war
    public final long warDeclareCost;
    public final long warMinDurationMillis;
    public final long warRedeclareCooldownMillis;

    // pvp
    public final PvpRules.Settings pvp;

    // sieges
    public final boolean siegeEnabled;
    public final long siegeCost;
    public final boolean landingEnabled;
    public final long landingExtraCost;
    public final int landingMaxBridgeheads;
    public final Rank siegeMinRank;
    public final long siegeMaxDurationMillis;
    public final double siegeCaptureSeconds;
    public final double siegeMaxSpeedMultiplier;
    public final boolean siegeRequireAdjacent;
    public final boolean siegeCapitalAnytime;
    public final boolean siegeAlliesHelp;
    public final int siegeBossbarRadiusChunks;
    public final long siegeMapRefreshTicks;
    public final Set<ProtectionService.Flag> siegeAllowedActions;
    public final List<String> siegeExcludedBlocks;
    public final boolean siegeExcludeContainers;
    /** Abstract NPC↔NPC sieges far from players (Armia, {@code siege.abstract.*}). */
    public final boolean abstractEnabled;
    public final boolean abstractExpeditions;
    public final long abstractExpeditionExtraCost;
    public final boolean abstractAllowPlayerCountries;
    public final int abstractPlayerBlockChunks;

    // npc countries
    public final boolean npcPlayersCanJoin;

    // terrain restore (/kraj admin przywroc, WorldEdit)
    public final int restoreChunksPerBatch;
    public final int restorePauseTicks;
    public final double restoreEstimatedMsPerChunk;
    public final int restoreDeleteDelayTicks;
    public final int restoreProgressStepPercent;

    // map (v2)
    public final String mapSiegeLayerName;
    public final String mapNpcSuffix;
    public final String mapSiegeTooltip;
    public final String mapPopupNpc;
    public final String mapPopupPopulation;
    public final String mapPopupArmy;
    public final String mapPopupSiege;
    /** Popup line with the era (Postep), added to {extra} when Postep is present. */
    public final String mapPopupEra;
    /** Era-dependent display titles of country names (Postep). */
    public final pl.medivalrpg.kraje.util.EraTitles eraTitles;
    public final boolean mapExportJson;

    // conquest & disband (v3; money parts only with Skarbiec)
    public enum FallRemainder { DESTROY, MEMBERS }

    public enum DisbandTreasury { RULER, MEMBERS, DESTROY }

    public final double lootPerChunk;
    public final double lootOnFall;
    public final FallRemainder fallRemainder;
    public final boolean conquestInvites;
    public final long conquestInviteMillis;
    public final DisbandTreasury disbandTreasury;

    // vassals (v3)
    public final boolean vassalEnabled;
    public final double tributeShare;
    public final long tributeMin;
    public final int vassalDefaultDays;
    public final int vassalMinDays;
    public final int vassalMaxDays;
    public final long capitulationExpiryMillis;
    public final double buyoutMultiplier;
    public final VassalRules.Settings vassalRules;
    public final boolean vassalNotifyWars;
    public final boolean vassalAutoJoinWars;
    public final String mapPopupVassal;
    public final String mapPopupVassals;

    // personal unions
    public final pl.medivalrpg.kraje.union.UnionRules.Settings unionRules;
    /** Kraje refuses union proposals to NPC countries at once (false = wait for the AI, UnionProposalEvent). */
    public final boolean unionNpcAutoRefuse;
    public final long unionProposalExpiryMillis;
    public final long unionFormCost;
    public final long unionBreakCost;
    public final boolean unionKeepAlliance;
    public final boolean unionRegentCanSecede;
    public final String mapPopupUnion;

    // embassies
    public final pl.medivalrpg.kraje.embassy.EmbassyRules.Settings embassyRules;
    public final long embassyOpenCost;
    public final long embassyRequestExpiryMillis;
    public final Rank embassyMinRank;
    public final Rank embassyTpMinRank;
    /** Kraje answers embassy requests to NPC countries itself (false = wait for the AI, EmbassyRequestEvent). */
    public final boolean embassyNpcAutoDecide;
    public final String mapPopupEmbassies;

    public KrajeConfig(final FileConfiguration c) {
        this.nameMinLength = Math.max(1, c.getInt("country.name-min-length", 3));
        this.nameMaxLength = Math.max(this.nameMinLength, c.getInt("country.name-max-length", 24));
        this.descriptionMaxLength = c.getInt("country.description-max-length", 120);
        this.foundMinDistanceChunks = Math.max(0, c.getInt("country.found-min-distance-chunks", 3));
        final Set<String> blocked = new HashSet<>();
        for (final String w : c.getStringList("country.blocked-worlds")) {
            blocked.add(w.toLowerCase(Locale.ROOT));
        }
        this.blockedWorlds = Set.copyOf(blocked);
        this.inviteExpiryMillis = c.getLong("country.invite-expiry-seconds", 300) * 1000L;
        this.proposalExpiryMillis = c.getLong("country.proposal-expiry-seconds", 300) * 1000L;
        this.confirmExpiryMillis = c.getLong("country.confirm-expiry-seconds", 30) * 1000L;
        this.defaultColors = c.getStringList("country.default-colors").stream()
            .map(Colors::parseHex).filter(java.util.Objects::nonNull).toList();

        this.claimsBase = c.getInt("claims.base", 16);
        this.claimsPerMember = c.getInt("claims.per-member", 8);

        this.spawnEnabled = c.getBoolean("spawn.enabled", true);
        this.spawnWarmupSeconds = Math.max(0, c.getInt("spawn.warmup-seconds", 5));

        this.protection = new Protection(c.getConfigurationSection("protection"));

        this.borderEnabled = c.getBoolean("border.enabled", true);
        BorderMode mode;
        try {
            mode = BorderMode.valueOf(c.getString("border.display", "TITLE").toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            mode = BorderMode.TITLE;
        }
        this.borderMode = mode;
        this.titleFadeIn = c.getInt("border.title-fade-in-ticks", 5);
        this.titleStay = c.getInt("border.title-stay-ticks", 30);
        this.titleFadeOut = c.getInt("border.title-fade-out-ticks", 10);

        this.mapEnabled = c.getBoolean("map.enabled", true);
        this.mapLayerName = c.getString("map.layer-name", "Kraje");
        this.mapLayerDefaultHidden = c.getBoolean("map.layer-default-hidden", false);
        this.mapLayerPriority = c.getInt("map.layer-priority", 5);
        this.mapLayerZIndex = c.getInt("map.layer-z-index", 5);
        this.mapFillOpacity = c.getDouble("map.fill-opacity", 0.35);
        this.mapStrokeWeight = c.getInt("map.stroke-weight", 2);
        this.mapStrokeOpacity = c.getDouble("map.stroke-opacity", 1.0);
        this.mapShowCapitals = c.getBoolean("map.show-capitals", true);
        this.mapRefreshDebounceTicks = Math.max(1, c.getLong("map.refresh-debounce-ticks", 40));
        this.mapHoverTemplate = c.getString("map.hover-tooltip", "{name}");
        this.mapPopupTemplate = c.getString("map.popup", "{name}");
        this.mapUrl = c.getString("map-url", "").trim();

        this.tablistEnabled = c.getBoolean("tablist.enabled", true);
        this.tablistFormat = c.getString("tablist.format", "<rank_icon><era_icon><color>[<country>]</color> <white><player>");
        this.tablistNoCountryFormat = c.getString("tablist.no-country-format", "<gray>[Dzicz]</gray> <white><player>");
        this.tablistHeader = c.getString("tablist.header", "");
        this.tablistFooter = c.getString("tablist.footer", "");
        this.tablistFooterMapLine = c.getString("tablist.footer-map-line", "");
        this.tablistSort = c.getBoolean("tablist.sort", true);
        this.tablistRefreshTicks = Math.max(20, c.getLong("tablist.refresh-ticks", 100));
        this.tablist = new pl.medivalrpg.kraje.tablist.TablistSettings(c);

        this.chatEnabled = c.getBoolean("chat.enabled", true);
        this.chatFormat = c.getString("chat.format", "<rank_icon><country_tag> <player><gray>:</gray> <message>");
        this.chatCountryTag = c.getString("chat.country-tag", "<color>[<country>]</color>");
        this.chatNoCountryTag = c.getString("chat.no-country-tag", "<gray>[Dzicz]</gray>");
        this.chatTagHover = c.getString("chat.tag-hover", "<country>");
        this.chatTagClickCommand = c.getString("chat.tag-click-command", "/kraj info <country>");
        this.chatCountryFormat = c.getString("chat.country-format", "<color>[Kraj]</color> <rank_icon><player>: <message>");
        this.chatAllyFormat = c.getString("chat.ally-format", "<dark_aqua>[Sojusz]</dark_aqua> <color>[<country>]</color> <player>: <message>");
        this.chatSpyPrefix = c.getString("chat.spy-prefix", "<dark_gray>[Podgląd]</dark_gray> ");
        this.chatLogToConsole = c.getBoolean("chat.log-to-console", true);

        final Map<Rank, String> icons = new EnumMap<>(Rank.class);
        final ConfigurationSection iconSection = c.getConfigurationSection("rank-icons");
        for (final Rank rank : Rank.values()) {
            icons.put(rank, iconSection == null ? "" : iconSection.getString(rank.name(), ""));
        }
        this.rankIcons = Map.copyOf(icons);

        // ---- keys added in v2: read with the jar's config.yml as fallback (the server file may not have them)
        final Reader r = new Reader(c);
        this.claimsPerNpcCitizen = Math.max(0, r.dbl("claims.per-npc-citizen", 0.25));
        this.claimsMax = Math.max(0, (int) r.lng("claims.max", 300));
        this.claimsNpcPlannerBonus = Math.max(-1, (int) r.lng("claims.npc-planner-bonus", 64));

        this.borderSoundEnter = r.str("border.sound.enter", "block.note_block.chime");
        this.borderSoundEnterVolume = (float) r.dbl("border.sound.enter-volume", 0.35);
        this.borderSoundEnterPitch = (float) r.dbl("border.sound.enter-pitch", 1.2);
        this.borderSoundEnemy = r.str("border.sound.enemy", "event.raid.horn");
        this.borderSoundEnemyVolume = (float) r.dbl("border.sound.enemy-volume", 0.15);
        this.borderSoundEnemyPitch = (float) r.dbl("border.sound.enemy-pitch", 1.0);
        this.borderSoundCooldownMillis = Math.max(0, r.lng("border.sound.cooldown-seconds", 6)) * 1000L;

        this.economyEnabled = r.bool("economy.enabled", true);
        this.foundCost = Math.max(0, r.lng("economy.found-cost", 500));
        this.claimCostBase = Math.max(0, r.lng("economy.claim-cost-base", 50));
        this.claimCostPerChunk = Math.max(0, r.lng("economy.claim-cost-per-chunk", 5));
        this.upkeepPerChunk = Math.max(0, r.lng("economy.upkeep-per-chunk", 2));
        this.upkeepFreeChunks = Math.max(0, (int) r.lng("economy.upkeep-free-chunks", 1));
        this.graceDays = Math.max(0, (int) r.lng("economy.grace-days", 3));
        this.bankruptcyChunksPerDay = Math.max(1, (int) r.lng("economy.bankruptcy-chunks-per-day", 2));
        this.dailyTickTime = DailySchedule.parseTime(r.str("economy.daily-tick-time", "18:00"));
        this.maxCatchUpDays = Math.max(0, (int) r.lng("economy.max-catch-up-days", 7));
        this.treasuryLogSize = Math.max(1, (int) r.lng("economy.treasury-log-size", 10));
        this.taxMax = Math.max(0, r.lng("economy.tax.max", 100));
        this.taxExemptRuler = r.bool("economy.tax.exempt-ruler", true);
        this.taxKickAfterDays = Math.max(1, (int) r.lng("economy.tax.kick-after-days", 3));

        this.warDeclareCost = Math.max(0, r.lng("war.declare-cost", 1000));
        this.warMinDurationMillis = Math.max(0, r.lng("war.min-duration-minutes", 120)) * 60_000L;
        this.warRedeclareCooldownMillis = Math.max(0, r.lng("war.redeclare-cooldown-minutes", 720)) * 60_000L;

        this.pvp = new PvpRules.Settings(PvpRules.Mode.parse(r.str("pvp.mode", "vanilla")),
            r.bool("pvp.friendly-fire", true), r.bool("pvp.without-country", false), r.bool("pvp.wilderness-free", false));

        this.siegeEnabled = r.bool("siege.enabled", true);
        this.siegeCost = Math.max(0, r.lng("siege.cost", 500));
        this.landingEnabled = r.bool("siege.landing.enabled", true);
        this.landingExtraCost = Math.max(0, Math.min(Long.MAX_VALUE-this.siegeCost,r.lng("siege.landing.extra-cost",1000)));
        this.landingMaxBridgeheads = (int)Math.max(1,Math.min(16,r.lng("siege.landing.max-bridgeheads",3)));
        Rank minRank;
        try {
            minRank = Rank.valueOf(r.str("siege.min-rank", "ADVISOR").toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            minRank = Rank.ADVISOR;
        }
        this.siegeMinRank = minRank;
        this.siegeMaxDurationMillis = Math.max(1, r.lng("siege.max-duration-minutes", 30)) * 60_000L;
        this.siegeCaptureSeconds = Math.max(10, r.dbl("siege.capture-seconds", 300));
        this.siegeMaxSpeedMultiplier = Math.max(1, r.dbl("siege.max-speed-multiplier", 3));
        this.siegeRequireAdjacent = r.bool("siege.require-adjacent", true);
        this.siegeCapitalAnytime = r.bool("siege.capital-anytime", false);
        this.siegeAlliesHelp = r.bool("siege.allies-help", true);
        this.siegeBossbarRadiusChunks = Math.max(0, (int) r.lng("siege.bossbar-radius-chunks", 6));
        this.siegeMapRefreshTicks = Math.max(5, r.lng("siege.map-refresh-seconds", 15)) * 20L;
        final Set<ProtectionService.Flag> actions = EnumSet.noneOf(ProtectionService.Flag.class);
        for (final String s : r.list("siege.build.allowed-actions",
            List.of("BLOCK_BREAK", "BLOCK_PLACE", "DOORS", "FENCE_GATES", "BUTTONS", "PRESSURE_PLATES"))) {
            try {
                actions.add(ProtectionService.Flag.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (final IllegalArgumentException ignored) {
                // unknown action names are ignored
            }
        }
        this.siegeAllowedActions = Set.copyOf(actions);
        this.siegeExcludedBlocks = List.copyOf(r.list("siege.build.excluded-blocks", List.of()));
        this.siegeExcludeContainers = r.bool("siege.build.exclude-containers", true);
        this.abstractEnabled = r.bool("siege.abstract.enabled", true);
        this.abstractExpeditions = r.bool("siege.abstract.expeditions", true);
        this.abstractExpeditionExtraCost = Math.max(0, Math.min(1_000_000L, r.lng("siege.abstract.expedition-extra-cost", 500)));
        this.abstractAllowPlayerCountries = r.bool("siege.abstract.allow-player-countries", false);
        this.abstractPlayerBlockChunks = (int) Math.max(0, Math.min(32, r.lng("siege.abstract.player-block-radius-chunks", 8)));

        this.npcPlayersCanJoin = r.bool("npc.players-can-join", false);

        this.restoreChunksPerBatch = (int) Math.max(1, Math.min(RegenPlanner.MAX_BATCH, r.lng("restore.chunks-per-batch", 4)));
        this.restorePauseTicks = (int) Math.max(1, Math.min(200, r.lng("restore.pause-ticks", 20)));
        this.restoreEstimatedMsPerChunk = Math.max(1.0, r.dbl("restore.estimated-ms-per-chunk", 1000));
        this.restoreDeleteDelayTicks = (int) Math.max(1, Math.min(1200, r.lng("restore.delete-delay-ticks", 60)));
        this.restoreProgressStepPercent = (int) Math.max(1, Math.min(50, r.lng("restore.progress-step-percent", 10)));

        this.mapSiegeLayerName = r.str("map.siege-layer-name", "Oblężenia");
        this.mapNpcSuffix = r.str("map.npc-suffix", " (NPC)");
        this.mapSiegeTooltip = r.str("map.siege-tooltip", "<b>⚔ Oblężenie</b><br>{attacker} oblega {defender}<br>Postęp: {progress}% • zostało {time}");
        this.mapPopupNpc = r.str("map.popup-npc", "<i>Kraj NPC — rządzi nim SI</i><br>");
        this.mapPopupPopulation = r.str("map.popup-population", "<b>Ludność:</b> {population} (mieszkania: {housing})<br>");
        this.mapPopupArmy = r.str("map.popup-army", "<b>Żołnierze:</b> {soldiers}<br>");
        this.mapExportJson = r.bool("map.export-json", true);
        this.mapPopupSiege = r.str("map.popup-siege", "<b style=\"color:#C0392B\">⚔ Oblężenie</b> przy {x}, {z}<br>");
        this.mapPopupEra = r.str("map.popup-era", "<b>Epoka:</b> {era} • postęp: {progress}<br>");
        this.eraTitles = pl.medivalrpg.kraje.util.EraTitles.parse(
            c.get("era-titles", null) instanceof ConfigurationSection own ? own : null,
            c.getDefaults() == null ? null : c.getDefaults().getConfigurationSection("era-titles"));

        // ---- v3: spoils of war, disbanding, vassals
        this.lootPerChunk = clamp01(r.dbl("conquest.loot-per-chunk", 0.05));
        this.lootOnFall = clamp01(r.dbl("conquest.loot-on-fall", 0.75));
        this.fallRemainder = parseEnum(FallRemainder.class, r.str("conquest.fall-remainder", "destroy"), FallRemainder.DESTROY);
        this.conquestInvites = r.bool("conquest.citizen-invites", true);
        this.conquestInviteMillis = Math.max(1, r.lng("conquest.citizen-invite-hours", 24)) * 3_600_000L;
        this.disbandTreasury = parseEnum(DisbandTreasury.class, r.str("disband.treasury", "ruler"), DisbandTreasury.RULER);

        this.vassalEnabled = r.bool("vassal.enabled", true);
        this.tributeShare = clamp01(r.dbl("vassal.tribute-share", 0.25));
        this.tributeMin = Math.max(0, r.lng("vassal.tribute-min", 20));
        this.vassalMinDays = Math.max(1, (int) r.lng("vassal.min-days", 1));
        this.vassalMaxDays = Math.max(this.vassalMinDays, (int) r.lng("vassal.max-days", 30));
        this.vassalDefaultDays = Math.max(this.vassalMinDays, Math.min(this.vassalMaxDays, (int) r.lng("vassal.default-days", 7)));
        this.capitulationExpiryMillis = Math.max(10, r.lng("vassal.offer-expiry-seconds", 300)) * 1000L;
        this.buyoutMultiplier = Math.max(0, r.dbl("vassal.buyout-multiplier", 1.5));
        this.vassalRules = new VassalRules.Settings(
            r.bool("vassal.forbid-war-on-overlord", true),
            r.bool("vassal.forbid-war-on-overlord-allies", true),
            r.bool("vassal.forbid-alliance-with-overlord-enemies", true),
            r.bool("vassal.overlord-war-only-for-debt", true),
            Math.max(1, (int) r.lng("vassal.debt-war-days", 3)));
        this.vassalNotifyWars = r.bool("vassal.notify-overlord-wars", true);
        this.vassalAutoJoinWars = r.bool("vassal.auto-join-overlord-wars", false);
        this.mapPopupVassal = r.str("map.popup-vassal", "<b>Wasal:</b> {overlord} (do {until})<br>");
        this.mapPopupVassals = r.str("map.popup-vassals", "<b>Wasale:</b> {vassals}<br>");

        // ---- personal unions & embassies
        this.unionRules = new pl.medivalrpg.kraje.union.UnionRules.Settings(
            r.bool("union.enabled", true),
            r.bool("union.allow-npc", true),
            Math.max(0, r.lng("union.min-duration-hours", 72)) * 3_600_000L,
            Math.max(0, r.lng("union.reform-cooldown-hours", 48)) * 3_600_000L,
            r.bool("union.defend-partner", true),
            r.bool("union.join-offensive-wars", false));
        this.unionNpcAutoRefuse = r.bool("union.npc-auto-refuse", false);
        this.unionProposalExpiryMillis = Math.max(10, r.lng("union.proposal-expiry-seconds", 300)) * 1000L;
        this.unionFormCost = Math.max(0, r.lng("union.form-cost", 2000));
        this.unionBreakCost = Math.max(0, r.lng("union.break-cost", 1000));
        this.unionKeepAlliance = r.bool("union.keep-alliance-after", true);
        this.unionRegentCanSecede = r.bool("union.regent-can-secede", true);
        this.mapPopupUnion = r.str("map.popup-union", "<b>Unia personalna:</b> z {union} • wspólny władca: {monarch}<br>");

        this.embassyRules = new pl.medivalrpg.kraje.embassy.EmbassyRules.Settings(
            r.bool("embassy.enabled", true),
            relations(r.list("embassy.allowed-relations", List.of("NEUTRAL", "ALLIANCE")), Set.of(Relation.NEUTRAL, Relation.ALLIANCE)),
            Math.max(0, (int) r.lng("embassy.max-per-country", 3)),
            Math.max(0, (int) r.lng("embassy.max-hosted", 5)),
            r.bool("embassy.allow-capital", false),
            Math.max(0, r.lng("embassy.expel-notice-hours", 24)) * 3_600_000L,
            relations(r.list("embassy.npc-accept-relations", List.of("ALLIANCE")), Set.of(Relation.ALLIANCE)),
            Math.max(0, r.dbl("embassy.trade-bonus", 0.05)),
            Math.max(0, r.dbl("union.trade-bonus", 0.10)),
            parseEnum(pl.medivalrpg.kraje.api.RelationQuality.class, r.str("embassy.joint-projects.min-relation", "NEUTRAL"),
                pl.medivalrpg.kraje.api.RelationQuality.NEUTRAL),
            r.bool("embassy.joint-projects.require-own-embassy", false));
        this.embassyOpenCost = Math.max(0, r.lng("embassy.open-cost", 300));
        this.embassyRequestExpiryMillis = Math.max(10, r.lng("embassy.request-expiry-seconds", 300)) * 1000L;
        this.embassyMinRank = parseEnum(Rank.class, r.str("embassy.min-rank", "RULER"), Rank.RULER);
        this.embassyTpMinRank = parseEnum(Rank.class, r.str("embassy.tp-min-rank", "CITIZEN"), Rank.CITIZEN);
        this.embassyNpcAutoDecide = r.bool("embassy.npc-auto-decide", false);
        this.mapPopupEmbassies = r.str("map.popup-embassies", "<b>Ambasady:</b> {embassies}<br>");
    }

    /** Relation names from the config (unknown names and WAR are ignored); {@code def} when nothing valid is left. */
    static Set<Relation> relations(final List<String> names, final Set<Relation> def) {
        final Set<Relation> out = EnumSet.noneOf(Relation.class);
        for (final String n : names) {
            final Relation rel = parseEnum(Relation.class, n, null);
            if (rel != null && rel != Relation.WAR) {
                out.add(rel);
            }
        }
        return out.isEmpty() ? def : Set.copyOf(out);
    }

    private static double clamp01(final double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    private static <E extends Enum<E>> E parseEnum(final Class<E> type, final String value, final E def) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException | NullPointerException e) {
            return def;
        }
    }

    public String rankIcon(final Rank rank) {
        return rank == null ? "" : this.rankIcons.getOrDefault(rank, "");
    }

    public boolean isWorldBlocked(final String world) {
        return this.blockedWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    /** Limit without NPC population (players only). */
    public int claimLimit(final int members) {
        return this.claimLimitFor(members, 0).total();
    }

    /**
     * Claim limit of a country: {@code base + per-member × player members + ⌊per-npc-citizen × NPC population⌋},
     * capped by {@code claims.max} (0 = no cap).
     */
    public ClaimLimit claimLimitFor(final int members, final int population) {
        final int fromMembers = this.claimsPerMember * Math.max(0, members);
        final int fromPopulation = (int) Math.floor(this.claimsPerNpcCitizen * Math.max(0, population));
        final long sum = (long) this.claimsBase + fromMembers + fromPopulation;
        final int total = (int) Math.min(Integer.MAX_VALUE, this.claimsMax > 0 ? Math.min(sum, this.claimsMax) : sum);
        return new ClaimLimit(this.claimsBase, this.claimsPerMember, Math.max(0, members), fromPopulation, this.claimsMax, total);
    }

    /**
     * Limit for claims of the Ludnosc town planner in an NPC country: the normal limit + {@code claims.npc-planner-bonus}
     * (not capped by {@code claims.max}); bonus -1 = no limit.
     */
    public int npcPlannerLimit(final ClaimLimit normal) {
        if (this.claimsNpcPlannerBonus < 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.min(Integer.MAX_VALUE, (long) normal.total() + this.claimsNpcPlannerBonus);
    }

    /**
     * Breakdown of a claim limit (for messages).
     *
     * @param fromPopulation chunks granted by NPC citizens
     * @param max            hard cap (0 = none)
     */
    public record ClaimLimit(int base, int perMember, int members, int fromPopulation, int max, int total) {
        public boolean capped() {
            return this.max > 0 && this.base + this.perMember * this.members + this.fromPopulation > this.max;
        }
    }

    /** Reads a key from the server file, falling back to the jar's default config, then to {@code def}. */
    private static final class Reader {
        private final FileConfiguration c;

        Reader(final FileConfiguration c) {
            this.c = c;
        }

        private Object raw(final String path) {
            Object v = this.c.get(path, null);
            if (v == null && this.c.getDefaults() != null) {
                v = this.c.getDefaults().get(path, null);
            }
            return v;
        }

        long lng(final String path, final long def) {
            final Object v = this.raw(path);
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v instanceof String s) {
                try {
                    return Long.parseLong(s.trim());
                } catch (final NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }

        double dbl(final String path, final double def) {
            final Object v = this.raw(path);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (final NumberFormatException ignored) {
                    return def;
                }
            }
            return def;
        }

        boolean bool(final String path, final boolean def) {
            final Object v = this.raw(path);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof String s) {
                return Boolean.parseBoolean(s.trim());
            }
            return def;
        }

        String str(final String path, final String def) {
            final Object v = this.raw(path);
            return v == null ? def : String.valueOf(v);
        }

        List<String> list(final String path, final List<String> def) {
            final Object v = this.raw(path);
            if (v instanceof List<?> l) {
                return l.stream().map(String::valueOf).toList();
            }
            return def;
        }
    }

    /** Protection flags. {@code true} = action is protected (forbidden for outsiders). */
    public static final class Protection {
        public final boolean enabled;
        public final boolean blockBreak;
        public final boolean blockPlace;
        public final boolean containers;
        public final boolean doors;
        public final boolean fenceGates;
        public final boolean buttons;
        public final boolean pressurePlates;
        public final boolean buckets;
        public final boolean fire;
        public final boolean animals;
        public final boolean decorations;
        public final boolean otherInteractions;
        public final boolean alliesUseDoors;
        /** Whether explosions may destroy blocks inside countries. */
        public final boolean explosions;
        /** Verified country TNT: own land or an actively besieged enemy chunk only. */
        public final boolean playerTnt;
        /** Whether fire may spread into a country from outside. */
        public final boolean fireSpreadFromOutside;
        public final boolean pistonsAcrossBorder;
        public final boolean liquidsAcrossBorder;
        public final boolean hoppersAcrossBorder;
        public final boolean dispensersAcrossBorder;
        public final long denyMessageCooldownMillis;

        Protection(final ConfigurationSection s) {
            this.enabled = get(s, "enabled", true);
            this.blockBreak = get(s, "block-break", true);
            this.blockPlace = get(s, "block-place", true);
            this.containers = get(s, "containers", true);
            this.doors = get(s, "doors", true);
            this.fenceGates = get(s, "fence-gates", true);
            this.buttons = get(s, "buttons", true);
            this.pressurePlates = get(s, "pressure-plates", true);
            this.buckets = get(s, "buckets", true);
            this.fire = get(s, "fire", true);
            this.animals = get(s, "animals", true);
            this.decorations = get(s, "decorations", true);
            this.otherInteractions = get(s, "other-interactions", true);
            this.alliesUseDoors = get(s, "allies-use-doors", true);
            this.explosions = get(s, "explosions", false);
            this.playerTnt = get(s,"player-tnt",true);
            this.fireSpreadFromOutside = get(s, "fire-spread-from-outside", false);
            this.pistonsAcrossBorder = get(s, "pistons-across-border", false);
            this.liquidsAcrossBorder = get(s, "liquids-across-border", false);
            this.hoppersAcrossBorder = get(s, "hoppers-across-border", false);
            this.dispensersAcrossBorder = get(s, "dispensers-across-border", false);
            this.denyMessageCooldownMillis = s == null ? 1500 : s.getLong("deny-message-cooldown-ms", 1500);
        }

        private static boolean get(final ConfigurationSection s, final String key, final boolean def) {
            return s == null ? def : s.getBoolean(key, def);
        }
    }
}
