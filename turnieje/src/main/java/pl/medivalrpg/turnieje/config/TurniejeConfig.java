// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import pl.medivalrpg.turnieje.arena.GearMode;
import pl.medivalrpg.turnieje.arena.KitSpec;
import pl.medivalrpg.turnieje.game.Texts;
import pl.medivalrpg.turnieje.game.WeeklySchedule;

/** Settings read from config.yml (immutable snapshot; {@code /turniej reload} builds a new one). */
public final class TurniejeConfig {

    /** Where the money of a weekly/admin tournament prize comes from. */
    public enum PrizeSource {
        /** Server pool account ({@code weekly.prize.server-account}). */
        SERVER,
        /** Treasury of the organizing country ({@code weekly.prize.country}). */
        COUNTRY,
        /** Created from nothing at payout — only with {@code weekly.prize.allow-mint: true}. */
        MINT,
        NONE
    }

    // general
    public final GearMode defaultGear;
    public final boolean forcePvp;
    public final int minParticipants;
    public final int maxParticipants;
    public final Set<String> allowedCommands;
    public final boolean protectAlways;
    public final boolean spectateEnabled;
    // match
    public final int countdownSeconds;
    public final int timeLimitSeconds;
    public final int meleeTimeLimitSeconds;
    public final int intermissionSeconds;
    public final int celebrationSeconds;
    // joust
    public final String lance;
    public final String fallbackLance;
    public final double horseSpeed;
    // kit
    public final String helmet;
    public final String chestplate;
    public final String leggings;
    public final String boots;
    public final String offhand;
    public final List<KitSpec> kitItems;
    // sign-ups
    public final List<Integer> announceSeconds;
    public final List<Integer> titleSeconds;
    public final boolean bossbarForAll;
    // weekly
    public final boolean weeklyEnabled;
    public final WeeklySchedule weekly;
    public final String weeklyArena;
    public final int weeklySignupMinutes;
    public final String weeklyName;
    public final long weeklyEntryFee;
    public final PrizeSource prizeSource;
    public final long prizeAmount;
    public final String prizeCountry;
    public final String serverAccount;
    public final boolean allowMint;
    // prize split
    public final List<Integer> split;
    public final boolean unclaimedToChampion;
    // organize
    public final boolean organizeEnabled;
    public final int organizeMinRank;
    public final long organizeMinPool;
    public final long organizeMaxPool;
    public final long organizeMaxFee;
    public final int organizeMinMinutes;
    public final int organizeMaxMinutes;
    public final int organizeDefaultMinutes;
    public final int organizeCooldownMinutes;
    public final boolean organizePublicArenas;
    // duel
    public final boolean duelEnabled;
    public final int duelRequestSeconds;
    public final boolean duelStats;
    // trophy & fireworks
    public final boolean trophyEnabled;
    public final String trophyMaterial;
    public final String trophyName;
    public final List<String> trophyLore;
    public final int fireworks;

    public TurniejeConfig(final FileConfiguration c, final Logger log) {
        this.defaultGear = GearMode.parse(c.getString("general.default-gear", "zestaw")).orElse(GearMode.KIT);
        this.forcePvp = c.getBoolean("general.force-pvp", true);
        this.minParticipants = Math.max(2, c.getInt("general.min-participants", 2));
        this.maxParticipants = Math.max(this.minParticipants, Math.min(128, c.getInt("general.max-participants", 32)));
        final Set<String> allowed = new LinkedHashSet<>();
        for (final String s : c.getStringList("general.allowed-commands")) {
            allowed.add(s.toLowerCase(Locale.ROOT).replace("/", "").trim());
        }
        allowed.add("turniej");
        allowed.add("arena");
        this.allowedCommands = Set.copyOf(allowed);
        this.protectAlways = c.getBoolean("general.protect-arena-always", false);
        this.spectateEnabled = c.getBoolean("general.spectate", true);

        this.countdownSeconds = clamp(c.getInt("match.countdown-seconds", 5), 1, 30);
        this.timeLimitSeconds = clamp(c.getInt("match.time-limit-seconds", 180), 20, 3600);
        this.meleeTimeLimitSeconds = clamp(c.getInt("match.melee-time-limit-seconds", 300), 20, 3600);
        this.intermissionSeconds = clamp(c.getInt("match.intermission-seconds", 6), 1, 120);
        this.celebrationSeconds = clamp(c.getInt("match.celebration-seconds", 10), 1, 120);

        this.lance = c.getString("joust.lance", "IRON_SPEAR");
        this.fallbackLance = c.getString("joust.fallback-lance", "IRON_SWORD");
        this.horseSpeed = Math.max(0.05, Math.min(0.5, c.getDouble("joust.horse-speed", 0.3)));

        this.helmet = c.getString("kit.helmet", "");
        this.chestplate = c.getString("kit.chestplate", "");
        this.leggings = c.getString("kit.leggings", "");
        this.boots = c.getString("kit.boots", "");
        this.offhand = c.getString("kit.offhand", "");
        final List<KitSpec> items = new ArrayList<>();
        for (final String s : c.getStringList("kit.items")) {
            KitSpec.parse(s).ifPresentOrElse(items::add, () -> log.warning("kit.items: niepoprawny wpis '" + s + "'"));
        }
        this.kitItems = List.copyOf(items);

        this.announceSeconds = minutesToSeconds(c.getIntegerList("signups.announce-minutes"));
        this.titleSeconds = minutesToSeconds(c.getIntegerList("signups.title-minutes"));
        this.bossbarForAll = c.getBoolean("signups.bossbar-for-all", true);

        this.weeklyEnabled = c.getBoolean("weekly.enabled", true);
        final String dayRaw = c.getString("weekly.day", "sobota");
        final DayOfWeek day = WeeklySchedule.parseDay(dayRaw).orElseGet(() -> {
            log.warning("weekly.day: nieznany dzień '" + dayRaw + "' — używam soboty");
            return DayOfWeek.SATURDAY;
        });
        final String timeRaw = c.getString("weekly.time", "20:00");
        final LocalTime time = WeeklySchedule.parseTime(timeRaw).orElseGet(() -> {
            log.warning("weekly.time: niepoprawna godzina '" + timeRaw + "' — używam 20:00");
            return LocalTime.of(20, 0);
        });
        this.weekly = new WeeklySchedule(day, time, WeeklySchedule.parseZone(c.getString("weekly.timezone", "")));
        this.weeklyArena = c.getString("weekly.arena", "").toLowerCase(Locale.ROOT).trim();
        this.weeklySignupMinutes = clamp(c.getInt("weekly.signup-minutes", 30), 1, 24 * 60);
        this.weeklyName = c.getString("weekly.name", "Wielki Turniej Rycerski");
        this.weeklyEntryFee = Math.max(0, c.getLong("weekly.entry-fee", 0));
        this.allowMint = c.getBoolean("weekly.prize.allow-mint", false);
        PrizeSource src = switch (Texts.normalize(c.getString("weekly.prize.source", "serwer"))) {
            case "serwer", "server", "pula" -> PrizeSource.SERVER;
            case "kraj", "country" -> PrizeSource.COUNTRY;
            case "mint", "druk", "nowe" -> PrizeSource.MINT;
            default -> PrizeSource.NONE;
        };
        if (src == PrizeSource.MINT && !this.allowMint) {
            log.warning("weekly.prize.source: mint wymaga weekly.prize.allow-mint: true — nagroda pieniężna wyłączona");
            src = PrizeSource.NONE;
        }
        this.prizeSource = src;
        this.prizeAmount = Math.max(0, c.getLong("weekly.prize.amount", 1000));
        this.prizeCountry = c.getString("weekly.prize.country", "");
        this.serverAccount = c.getString("weekly.prize.server-account", "serwer:turnieje");

        final List<Integer> sp = new ArrayList<>();
        int sum = 0;
        for (final int p : c.getIntegerList("prize.split")) {
            if (p >= 0 && sum + p <= 100) {
                sp.add(p);
                sum += p;
            } else {
                log.warning("prize.split: suma przekracza 100% — pomijam " + p);
            }
        }
        this.split = sp.isEmpty() ? List.of(100) : List.copyOf(sp);
        this.unclaimedToChampion = Texts.normalize(c.getString("prize.unclaimed", "zwrot")).startsWith("zwyciez");

        this.organizeEnabled = c.getBoolean("organize.enabled", true);
        this.organizeMinRank = switch (Texts.normalize(c.getString("organize.min-rank", "wladca"))) {
            case "obywatel", "citizen" -> 1;
            case "doradca", "advisor" -> 2;
            default -> 3;
        };
        this.organizeMinPool = Math.max(0, c.getLong("organize.min-pool", 100));
        this.organizeMaxPool = Math.max(this.organizeMinPool, c.getLong("organize.max-pool", 100_000));
        this.organizeMaxFee = Math.max(0, c.getLong("organize.max-entry-fee", 5_000));
        this.organizeMinMinutes = clamp(c.getInt("organize.min-minutes", 2), 1, 24 * 60);
        this.organizeMaxMinutes = clamp(c.getInt("organize.max-minutes", 60), this.organizeMinMinutes, 24 * 60);
        this.organizeDefaultMinutes = clamp(c.getInt("organize.default-minutes", 10), this.organizeMinMinutes, this.organizeMaxMinutes);
        this.organizeCooldownMinutes = Math.max(0, c.getInt("organize.cooldown-minutes", 120));
        this.organizePublicArenas = c.getBoolean("organize.allow-public-arenas", true);

        this.duelEnabled = c.getBoolean("duel.enabled", true);
        this.duelRequestSeconds = clamp(c.getInt("duel.request-seconds", 60), 10, 600);
        this.duelStats = c.getBoolean("duel.count-in-stats", true);

        this.trophyEnabled = c.getBoolean("trophy.enabled", true);
        this.trophyMaterial = c.getString("trophy.material", "GOLDEN_SWORD");
        this.trophyName = c.getString("trophy.name", "<gold><bold>Złoty Miecz Turniejowy</bold></gold>");
        this.trophyLore = List.copyOf(c.getStringList("trophy.lore"));
        this.fireworks = clamp(c.getInt("fireworks.count", 6), 0, 30);
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static List<Integer> minutesToSeconds(final List<Integer> minutes) {
        final List<Integer> out = new ArrayList<>();
        for (final int m : minutes) {
            if (m > 0) {
                out.add(m * 60);
            }
        }
        return List.copyOf(out);
    }
}
