// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import pl.medivalrpg.ludnosc.logic.Economy;
import pl.medivalrpg.ludnosc.logic.JobBoard;
import pl.medivalrpg.ludnosc.logic.MineYield;
import pl.medivalrpg.ludnosc.logic.Season;
import pl.medivalrpg.ludnosc.logic.SeasonRules;

/**
 * Typed view of the citizens' work, the seasons and the mine output ({@code work}, {@code seasons}, the new keys of
 * {@code mines}; immutable, pure: no server needed).
 */
public final class WorkSettings {

    public final SeasonRules seasons;
    public final boolean announceSeasons;
    public final boolean announceOnJoin;

    public final MineYield mineYield;
    public final boolean mineAutoDig;
    public final int mineMaxAutoTickets;
    public final int mineCrewMax;
    public final boolean mineFallProtection;
    public final long preciousPrice;

    public final boolean enabled;
    public final int playerRange;
    public final int citizensPerTick;
    public final double budgetMs;
    public final int workStart;
    public final int workEnd;
    public final int deliveryStart;
    public final int deliveryEnd;
    public final Map<JobBoard.Job, List<String>> professions;
    public final Map<JobBoard.Job, Integer> slots;
    public final int basket;
    public final int harvestCapPerFarmer;
    public final int fishCapPerFisher;
    public final int granaryRange;
    public final int deliveryTimeoutSeconds;
    public final boolean standInWorkers;
    public final boolean openFieldGates;

    public WorkSettings(final FileConfiguration c) {
        final Map<Season, Double> food = seasonMap(c.getConfigurationSection("seasons.food"));
        final Map<Season, Double> growth = seasonMap(c.getConfigurationSection("seasons.crop-growth"));
        final Map<Season, Double> chance = seasonMap(c.getConfigurationSection("seasons.poor-harvest.chance"));
        final SeasonRules d = SeasonRules.defaults();
        final Season first = Season.parse(c.getString("seasons.first", "jesien"));
        this.seasons = new SeasonRules(c.getBoolean("seasons.enabled", true), c.getDouble("seasons.length-days", 7),
            first == null ? Season.JESIEN : first, food.isEmpty() ? d.food() : merge(d.food(), food),
            growth.isEmpty() ? d.cropGrowth() : merge(d.cropGrowth(), growth), c.getBoolean("seasons.crop-growth.enabled", true),
            c.getBoolean("seasons.affects-npc-countries", true), chance.isEmpty() ? d.blightChance() : merge(d.blightChance(), chance),
            c.getDouble("seasons.poor-harvest.multiplier", 0.4), c.getInt("seasons.poor-harvest.min-days", 2),
            c.getInt("seasons.poor-harvest.max-days", 4), c.getInt("seasons.poor-harvest.cooldown-days", 10),
            c.getDouble("departures.max-fraction-per-day", 0.1));
        this.announceSeasons = c.getBoolean("seasons.announce", true);
        this.announceOnJoin = c.getBoolean("seasons.announce-on-join", true);

        final MineYield m = MineYield.defaults();
        this.mineYield = new MineYield(c.getDouble("mines.ore-per-miner", m.orePerMiner()),
            c.getDouble("mines.stone-per-miner", m.stonePerMiner()), c.getDouble("mines.precious-per-miner", m.preciousPerMiner()),
            c.getDouble("mines.level-bonus", m.levelBonus()), c.getInt("mines.slots-base", m.slotsBase()),
            c.getInt("mines.slots-per-level", m.slotsPerLevel()), c.getDouble("mines.smithy-tool-bonus", m.smithyBonus()),
            c.getDouble("mines.era-tool-bonus", m.eraBonus()), c.getDouble("mines.steps-per-miner-hour", m.stepsPerMinerHour()),
            c.getDouble("mines.sell-share", m.sellShare()));
        this.mineAutoDig = c.getBoolean("mines.auto-dig", true);
        this.mineMaxAutoTickets = Math.max(0, c.getInt("mines.max-auto-tickets", 8));
        this.mineCrewMax = Math.max(0, Math.min(6, c.getInt("mines.crew-max", 3)));
        this.mineFallProtection = c.getBoolean("mines.fall-protection", true);
        this.preciousPrice = Math.max(0, c.getLong("mines.precious-price", 25));

        this.enabled = c.getBoolean("work.enabled", true);
        this.playerRange = Math.max(8, c.getInt("work.player-range", 64));
        this.citizensPerTick = Math.max(1, c.getInt("work.citizens-per-tick", 12));
        this.budgetMs = Math.max(0.1, c.getDouble("work.budget-ms", 1.0));
        this.workStart = Math.floorMod(c.getInt("work.hours.start", 2000), 24000);
        this.workEnd = Math.floorMod(c.getInt("work.hours.end", 9000), 24000);
        this.deliveryStart = Math.floorMod(c.getInt("work.delivery-hours.start", 9000), 24000);
        this.deliveryEnd = Math.floorMod(c.getInt("work.delivery-hours.end", 10500), 24000);
        final Map<JobBoard.Job, List<String>> profs = new EnumMap<>(JobBoard.Job.class);
        final Map<JobBoard.Job, List<String>> defaults = JobBoard.defaultProfessions();
        for (final JobBoard.Job j : JobBoard.Job.values()) {
            final String path = "work.jobs." + j.key();
            final List<String> list = new ArrayList<>();
            if (c.isList(path)) {
                for (final String s : c.getStringList(path)) {
                    if (s != null && !s.isBlank() && !list.contains(Economy.professionKey(s))) {
                        list.add(Economy.professionKey(s));
                    }
                }
            }
            profs.put(j, List.copyOf(c.isList(path) ? list : defaults.getOrDefault(j, List.of())));
        }
        this.professions = Map.copyOf(profs);
        final Map<JobBoard.Job, Integer> sl = new EnumMap<>(JobBoard.Job.class);
        sl.put(JobBoard.Job.FARMER, Math.max(0, c.getInt("work.slots.farm", 2)));
        sl.put(JobBoard.Job.QUARRYMAN, Math.max(0, c.getInt("work.slots.quarry", 2)));
        sl.put(JobBoard.Job.WOODCUTTER, Math.max(0, c.getInt("work.slots.sawmill", 2)));
        sl.put(JobBoard.Job.FISHER, Math.max(0, c.getInt("work.slots.harbor", 2)));
        this.slots = Map.copyOf(sl);
        this.basket = Math.max(1, c.getInt("work.basket", 6));
        this.harvestCapPerFarmer = Math.max(0, c.getInt("work.harvest-cap-per-farmer", 8));
        this.fishCapPerFisher = Math.max(0, c.getInt("work.fish-cap-per-fisher", 6));
        this.granaryRange = Math.max(8, c.getInt("work.granary-range", 64));
        this.deliveryTimeoutSeconds = Math.max(5, c.getInt("work.delivery-timeout-seconds", 45));
        this.standInWorkers = c.getBoolean("work.stand-in-workers", true);
        this.openFieldGates = c.getBoolean("work.open-field-gates", true);
    }

    /** MC time of day 0..23999 within the visible work hours. */
    public boolean workTime(final long worldTime) {
        return within((int) Math.floorMod(worldTime, 24000L), this.workStart, this.workEnd);
    }

    /** MC time of day within the delivery window (farmers carry the day's harvest to the granary). */
    public boolean deliveryTime(final long worldTime) {
        return within((int) Math.floorMod(worldTime, 24000L), this.deliveryStart, this.deliveryEnd);
    }

    static boolean within(final int t, final int start, final int end) {
        return start <= end ? t >= start && t < end : t >= start || t < end;
    }

    private static Map<Season, Double> seasonMap(final ConfigurationSection s) {
        final Map<Season, Double> out = new EnumMap<>(Season.class);
        if (s == null) {
            return out;
        }
        for (final String key : s.getKeys(false)) {
            final Season season = Season.parse(key);
            if (season != null && s.isDouble(key) || season != null && s.isInt(key)) {
                out.put(season, s.getDouble(key));
            }
        }
        return out;
    }

    private static Map<Season, Double> merge(final Map<Season, Double> base, final Map<Season, Double> over) {
        final Map<Season, Double> out = new EnumMap<>(Season.class);
        out.putAll(base);
        out.putAll(over);
        return out;
    }
}
