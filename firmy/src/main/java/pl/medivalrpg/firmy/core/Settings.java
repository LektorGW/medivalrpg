// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.firmy.model.FirmType;

/** Rules of companies from {@code config.yml} (amounts in copper). Defaults = decisions of 2026-10-29. */
public final class Settings {

    public ZoneId zone = ZoneId.of("Europe/Warsaw");

    // founding
    public long fee = 2_500;
    public long minCapital = 2_500;
    public int applicationHours = 72;
    public int nameMin = 3;
    public int nameMax = 24;
    public int nameReservedDays = 30;
    public List<String> bannedWords = List.of("admin", "serwer", "moderator", "skarb swiata", "worldsteward", "kolej krolewska",
        "krolewski przewoznik");
    /** Minimum reputation with an NPC seat country (Wladcy, "Obcy" starts at −19). */
    public int npcMinReputation = -19;
    /** Without Wladcy the reputation cannot be checked: allow (true) or refuse (false). */
    public boolean npcAllowWithoutReputation = true;
    /** Co-founding (zrzutka): several players pay the fee + capital together; shares follow the contributions. */
    public boolean gatheringEnabled = true;
    /** Smallest contribution to a gathering (copper). */
    public long gatheringMinContribution = 500;
    /** Most people paying into one gathering (the founder included). */
    public int gatheringMaxPeople = 6;

    // people
    public int maxMasterOf = 1;
    public int maxMemberships = 3;
    public long minPlayMinutes = 300;
    public int minAccountDays = 3;
    public Map<FirmType, Integer> maxPeople = new EnumMap<>(Map.of(FirmType.CECH, 12, FirmType.KOMPANIA, 16,
        FirmType.MANUFAKTURA, 16, FirmType.DOM_HANDLOWY, 30));
    public int inviteMinutes = 30;

    // tax
    public int defaultTaxBp = 500;
    public int maxTaxBp = 2_000;
    public int npcTaxBp = 500;

    // salaries and payouts
    public long maxSalary = 5_000;
    public int dailyHour = 12;
    public int payoutDelayHours = 24;
    public int payoutBalanceBp = 2_500;
    public long payoutDailyLimit = 20_000;

    // insolvency, dormancy
    public int insolventDays = 3;
    public int liquidationDays = 7;
    public int dormantDays = 30;
    public int dormantLiquidationDays = 60;
    public int exMasterDays = 7;

    // shops, warehouse, promotion
    public Map<FirmType, Integer> maxShops = new EnumMap<>(Map.of(FirmType.CECH, 6, FirmType.KOMPANIA, 10,
        FirmType.MANUFAKTURA, 8, FirmType.DOM_HANDLOWY, 20));
    public Map<FirmType, Integer> maxChests = new EnumMap<>(Map.of(FirmType.CECH, 6, FirmType.KOMPANIA, 8,
        FirmType.MANUFAKTURA, 8, FirmType.DOM_HANDLOWY, 16));
    public int chestLogSize = 300;
    public int journalSize = 400;
    /** 30-day gross income needed to become a trading house (Dom handlowy). */
    public long promotionIncome = 500_000;
    public int manufactoryEra = 2;

    public static Settings defaults() {
        return new Settings();
    }

    public static Settings from(final ConfigurationSection c) {
        final Settings s = new Settings();
        if (c == null) {
            return s;
        }
        try {
            s.zone = ZoneId.of(c.getString("strefa-czasowa", "Europe/Warsaw"));
        } catch (final RuntimeException e) {
            s.zone = ZoneId.of("Europe/Warsaw");
        }
        s.fee = Math.max(0, c.getLong("zalozenie.oplata", s.fee));
        s.minCapital = Math.max(1, c.getLong("zalozenie.kapital-min", s.minCapital));
        s.applicationHours = clamp(c.getInt("zalozenie.wniosek-godziny", s.applicationHours), 1, 24 * 14);
        s.nameMin = clamp(c.getInt("zalozenie.nazwa-min", s.nameMin), 1, 24);
        s.nameMax = clamp(c.getInt("zalozenie.nazwa-max", s.nameMax), s.nameMin, 32);
        s.nameReservedDays = clamp(c.getInt("zalozenie.nazwa-zastrzezona-dni", s.nameReservedDays), 0, 365);
        if (c.isList("zalozenie.zakazane-slowa")) {
            s.bannedWords = c.getStringList("zalozenie.zakazane-slowa").stream().map(w -> Names.normalize(w)).toList();
        }
        s.npcMinReputation = clamp(c.getInt("zalozenie.npc-min-reputacja", s.npcMinReputation), -100, 100);
        s.npcAllowWithoutReputation = c.getBoolean("zalozenie.npc-bez-wladcow-zgoda", s.npcAllowWithoutReputation);
        s.gatheringEnabled = c.getBoolean("zalozenie.zrzutka.wlaczona", s.gatheringEnabled);
        s.gatheringMinContribution = Math.max(1, c.getLong("zalozenie.zrzutka.min-wklad", s.gatheringMinContribution));
        s.gatheringMaxPeople = clamp(c.getInt("zalozenie.zrzutka.max-osob", s.gatheringMaxPeople), 2, 30);
        s.maxMasterOf = clamp(c.getInt("ludzie.max-firm-jako-mistrz", s.maxMasterOf), 1, 10);
        s.maxMemberships = clamp(c.getInt("ludzie.max-czlonkostw", s.maxMemberships), 1, 20);
        s.minPlayMinutes = Math.max(0, c.getLong("ludzie.min-czas-gry-minuty", s.minPlayMinutes));
        s.minAccountDays = clamp(c.getInt("ludzie.min-wiek-konta-dni", s.minAccountDays), 0, 365);
        s.inviteMinutes = clamp(c.getInt("ludzie.zaproszenie-minuty", s.inviteMinutes), 1, 24 * 60);
        readTypes(c.getConfigurationSection("ludzie.limit-osob"), s.maxPeople, 1, 200);
        s.defaultTaxBp = clamp(percentBp(c, "podatek.domyslny-procent", s.defaultTaxBp), 0, 10_000);
        s.maxTaxBp = clamp(percentBp(c, "podatek.max-procent", s.maxTaxBp), 0, 10_000);
        s.defaultTaxBp = Math.min(s.defaultTaxBp, s.maxTaxBp);
        s.npcTaxBp = clamp(percentBp(c, "podatek.npc-procent", s.npcTaxBp), 0, s.maxTaxBp);
        s.maxSalary = Math.max(0, c.getLong("pensje.max-dziennie", s.maxSalary));
        s.dailyHour = clamp(c.getInt("pensje.godzina", s.dailyHour), 0, 23);
        s.payoutDelayHours = clamp(c.getInt("wyplaty.zwloka-godziny", s.payoutDelayHours), 0, 24 * 7);
        s.payoutBalanceBp = clamp(percentBp(c, "wyplaty.prog-procent-salda", s.payoutBalanceBp), 0, 10_000);
        s.payoutDailyLimit = Math.max(0, c.getLong("wyplaty.prog-dzienny", s.payoutDailyLimit));
        s.insolventDays = clamp(c.getInt("niewyplacalnosc.dni-do-statusu", s.insolventDays), 1, 60);
        s.liquidationDays = clamp(c.getInt("niewyplacalnosc.dni-do-likwidacji", s.liquidationDays), s.insolventDays, 120);
        s.dormantDays = clamp(c.getInt("uspienie.dni", s.dormantDays), 1, 365);
        s.dormantLiquidationDays = clamp(c.getInt("uspienie.likwidacja-dni", s.dormantLiquidationDays), s.dormantDays, 730);
        s.exMasterDays = clamp(c.getInt("likwidacja.dostep-bylego-mistrza-dni", s.exMasterDays), 0, 60);
        readTypes(c.getConfigurationSection("sklepy.limit"), s.maxShops, 0, 200);
        readTypes(c.getConfigurationSection("magazyn.limit-skrzyn"), s.maxChests, 0, 200);
        s.chestLogSize = clamp(c.getInt("magazyn.dziennik-wpisow", s.chestLogSize), 20, 5_000);
        s.journalSize = clamp(c.getInt("ksiega.wpisow", s.journalSize), 50, 10_000);
        s.promotionIncome = Math.max(1, c.getLong("dom-handlowy.obrot-30-dni", s.promotionIncome));
        s.manufactoryEra = clamp(c.getInt("manufaktura.epoka", s.manufactoryEra), 1, 4);
        return s;
    }

    private static int percentBp(final ConfigurationSection c, final String path, final int defBp) {
        if (!c.contains(path)) {
            return defBp;
        }
        return (int) Math.round(c.getDouble(path) * 100.0);
    }

    private static void readTypes(final ConfigurationSection sec, final Map<FirmType, Integer> into, final int min, final int max) {
        if (sec == null) {
            return;
        }
        for (final String k : sec.getKeys(false)) {
            FirmType.parse(k.toLowerCase(Locale.ROOT)).ifPresent(t -> into.put(t, clamp(sec.getInt(k), min, max)));
        }
    }

    static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
