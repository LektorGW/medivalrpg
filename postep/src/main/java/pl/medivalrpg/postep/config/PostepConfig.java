// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.config;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.postep.logic.DailySchedule;
import pl.medivalrpg.postep.logic.EraRules;
import pl.medivalrpg.postep.logic.NpcStrategy;
import pl.medivalrpg.postep.logic.ResearchMath;
import pl.medivalrpg.postep.model.EraDef;

/** Settings from {@code config.yml} (immutable snapshot; a reload builds a new one). */
public final class PostepConfig {

    public final LocalTime tickTime;
    public final ZoneId zone;
    public final int maxCatchUp;
    public final int refreshSeconds;

    public final boolean rulerConfirmation;
    public final boolean announce;
    public final boolean title;
    public final boolean titleForAll;
    public final String sound;
    public final float soundVolume;
    public final float soundPitch;

    public final boolean skipUnknownThresholds;
    public final int playerCountsAsCitizens;
    public final List<String> tierNames;
    public final EraRules.Weights weights;
    public final EraRules.Texts missingTexts;

    public final List<EraDef> eras;
    public final ResearchMath.Settings research;

    public final Rank investRank;
    public final long minInvest;
    public final Rank discoverRank;
    public final boolean playersAutoTarget;
    /** Who hears about a new discovery: {@code kraj} (members), {@code wszyscy}, {@code brak}. */
    public final String discoveryAnnounce;

    public final boolean npcAutoResearch;
    public final boolean npcAutoInvest;
    public final NpcStrategy.InvestSettings npcInvest;

    public final boolean tablistAppend;
    public final String tablistFormat;
    public final int tablistSeconds;

    public final boolean mapLayer;
    public final String mapLayerName;
    public final boolean mapLayerHidden;
    public final int mapLayerPriority;
    public final int mapLayerZIndex;
    public final int mapIconSize;
    public final int mapOffsetX;
    public final int mapOffsetZ;
    public final boolean exportJson;
    public final String exportPath;
    public final int mapSeconds;

    public PostepConfig(final ConfigurationSection c, final List<String> problems) {
        this.tickTime = DailySchedule.parseTime(c.getString("tick.godzina", "06:00"));
        final String zoneText = c.getString("tick.strefa", "");
        ZoneId z = ZoneId.systemDefault();
        if (zoneText != null && !zoneText.isBlank()) {
            try {
                z = ZoneId.of(zoneText.trim());
            } catch (final RuntimeException e) {
                problems.add("tick.strefa: nieznana strefa " + zoneText);
            }
        }
        this.zone = z;
        this.maxCatchUp = Math.max(0, c.getInt("tick.max-nadrabianie", 7));
        this.refreshSeconds = Math.max(10, c.getInt("tick.odswiezanie-sekundy", 60));

        this.rulerConfirmation = c.getBoolean("awans.potwierdzenie-wladcy", false);
        this.announce = c.getBoolean("awans.ogloszenie", true);
        this.title = c.getBoolean("awans.title", true);
        this.titleForAll = !"kraj".equalsIgnoreCase(c.getString("awans.title-dla", "wszyscy"));
        this.sound = c.getString("awans.dzwiek", "ui.toast.challenge_complete");
        this.soundVolume = (float) c.getDouble("awans.dzwiek-glosnosc", 1.0);
        this.soundPitch = (float) c.getDouble("awans.dzwiek-ton", 1.0);

        this.skipUnknownThresholds = c.getBoolean("progi.pomijaj-nieznane", true);
        this.playerCountsAsCitizens = Math.max(0, c.getInt("progi.gracz-jak-mieszkancy", 5));
        final List<String> tiers = c.getStringList("progi.nazwy-poziomow");
        this.tierNames = tiers.isEmpty() ? List.of("Osada", "Wieś", "Miasteczko", "Miasto", "Stolica", "Metropolia") : List.copyOf(tiers);
        this.weights = new EraRules.Weights(c.getDouble("progi.wagi.odkrycia", 0.6), c.getDouble("progi.wagi.czas", 0.25),
            c.getDouble("progi.wagi.progi", 0.15));
        this.missingTexts = new EraRules.Texts(
            c.getString("progi.teksty.odkrycie", EraRules.Texts.DEFAULT.discovery()),
            c.getString("progi.teksty.czas", EraRules.Texts.DEFAULT.time()),
            c.getString("progi.teksty.ludnosc", EraRules.Texts.DEFAULT.population()),
            c.getString("progi.teksty.poziom", EraRules.Texts.DEFAULT.tier()));

        final List<EraDef> definedEras = parseEras(c.getConfigurationSection("epoki"), problems);
        final int eraLimit = Math.clamp(c.getInt("awans.maksymalna-epoka", 2), 1, definedEras.size());
        this.eras = List.copyOf(definedEras.subList(0, eraLimit));

        final Map<String, Double> buildings = new LinkedHashMap<>();
        final ConfigurationSection b = c.getConfigurationSection("punkty.budynki");
        if (b != null) {
            for (final String key : b.getKeys(false)) {
                buildings.put(key, b.getDouble(key));
            }
        }
        this.research = new ResearchMath.Settings(
            c.getDouble("punkty.baza", 3),
            c.getDouble("punkty.na-mieszkanca", 0.15),
            c.getInt("punkty.max-mieszkancow", 250),
            c.getDouble("punkty.na-gracza", 2),
            c.getInt("punkty.max-graczy", 10),
            buildings,
            c.getInt("punkty.max-budynkow-typu", 3),
            c.getDouble("punkty.pokoj", 10),
            c.getDouble("punkty.na-sojusz", 5),
            c.getDouble("punkty.max-sojusze", 15),
            c.getDouble("punkty.na-wojne", 15),
            c.getDouble("punkty.max-wojny", 45),
            c.getDouble("punkty.wasal", 10),
            c.getDouble("punkty.min-mnoznik", 0.25),
            c.getDouble("punkty.mnoznik-npc", 1.0));

        this.investRank = rank(c.getString("inwestycje.ranga", "ADVISOR"), Rank.ADVISOR, problems);
        this.minInvest = Math.max(1, c.getLong("inwestycje.min-kwota", 10));
        this.discoverRank = rank(c.getString("odkrywanie.ranga", "ADVISOR"), Rank.ADVISOR, problems);
        this.playersAutoTarget = c.getBoolean("odkrywanie.gracze-auto-cel", false);
        this.discoveryAnnounce = c.getString("odkrywanie.ogloszenie", "kraj").toLowerCase(Locale.ROOT);

        this.npcAutoResearch = c.getBoolean("npc.auto-wybor", true);
        this.npcAutoInvest = c.getBoolean("npc.auto-inwestycje", true);
        this.npcInvest = new NpcStrategy.InvestSettings(
            c.getDouble("npc.udzial-bazowy", 12),
            c.getDouble("npc.udzial-max", 35),
            c.getDouble("npc.mnoznik-wojna", 0.4),
            Math.max(0, c.getLong("npc.rezerwa", 500)),
            c.getDouble("npc.max-ze-skarbca", 0.2),
            c.getDouble("npc.do-limitu", 0.85));

        this.tablistAppend = c.getBoolean("tablista.dopisz-ikone", true);
        this.tablistFormat = c.getString("tablista.format", "<name> <badge_icon>");
        this.tablistSeconds = Math.max(1, c.getInt("tablista.co-ile-sekund", 5));

        this.mapLayer = c.getBoolean("mapa.warstwa", true);
        this.mapLayerName = c.getString("mapa.nazwa-warstwy", "Epoki");
        this.mapLayerHidden = c.getBoolean("mapa.ukryta-domyslnie", false);
        this.mapLayerPriority = c.getInt("mapa.priorytet", 12);
        this.mapLayerZIndex = c.getInt("mapa.z-index", 260);
        this.mapIconSize = Math.max(8, c.getInt("mapa.rozmiar-ikony", 18));
        this.mapOffsetX = c.getInt("mapa.przesuniecie-x", 0);
        this.mapOffsetZ = c.getInt("mapa.przesuniecie-z", -12);
        this.exportJson = c.getBoolean("mapa.eksport-json", true);
        this.exportPath = c.getString("mapa.sciezka-json", "");
        this.mapSeconds = Math.max(10, c.getInt("mapa.co-ile-sekund", 60));
    }

    static List<EraDef> parseEras(final ConfigurationSection section, final List<String> problems) {
        final List<EraDef> out = new ArrayList<>();
        if (section == null) {
            problems.add("brak sekcji 'epoki' — używam domyślnych");
            return defaultEras();
        }
        int expected = 1;
        for (final String key : section.getKeys(false)) {
            final ConfigurationSection s = section.getConfigurationSection(key);
            final int id;
            try {
                id = Integer.parseInt(key.trim());
            } catch (final NumberFormatException e) {
                problems.add("epoki." + key + ": klucz musi być numerem 1, 2, 3...");
                continue;
            }
            if (s == null || id != expected) {
                problems.add("epoki." + key + ": oczekiwano epoki " + expected + " (kolejne numery od 1)");
                continue;
            }
            expected++;
            out.add(new EraDef(id,
                s.getString("nazwa", "Epoka " + id),
                s.getString("biernik", s.getString("nazwa", "Epoka " + id)),
                s.getString("lata", ""),
                s.getString("kolor", "#FFFFFF"),
                s.getString("ikona", "✦"),
                s.getString("opis", ""),
                s.getString("ikona-gui", "BOOK"),
                s.getDouble("min-dni", 21),
                s.getInt("min-ludnosc", 0),
                s.getInt("min-poziom", 0),
                s.getDouble("mnoznik-punktow", 1.0),
                s.getDouble("inwestycja-pkt-za-zlota", 4),
                s.getDouble("inwestycja-max-dziennie", 20)));
        }
        if (out.isEmpty()) {
            problems.add("brak poprawnych epok — używam domyślnych");
            return defaultEras();
        }
        return out;
    }

    static List<EraDef> defaultEras() {
        return List.of(
            new EraDef(1, "Średniowiecze", "Średniowiecze", "V–XV w.", "#B08D57", "⚔", "", "SHIELD", 21, 0, 0, 1.0, 4, 20),
            new EraDef(2, "Renesans", "Renesans", "XV–XVII w.", "#C9A227", "⚜", "", "SPYGLASS", 21, 25, 2, 1.25, 5, 35),
            new EraDef(3, "Epoka przemysłowa", "epokę przemysłową", "XIX w.", "#8E9AAF", "⚙", "", "FURNACE", 21, 60, 3, 1.5, 6, 55),
            new EraDef(4, "Nowoczesność", "Nowoczesność", "2026", "#3FA7D6", "⚡", "", "REDSTONE_LAMP", 0, 100, 4, 1.75, 7, 80));
    }

    private static Rank rank(final String text, final Rank fallback, final List<String> problems) {
        if (text == null) {
            return fallback;
        }
        final String t = text.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "CITIZEN", "OBYWATEL" -> Rank.CITIZEN;
            case "ADVISOR", "DORADCA" -> Rank.ADVISOR;
            case "RULER", "WLADCA", "WŁADCA" -> Rank.RULER;
            default -> {
                problems.add("nieznana ranga " + text + " — używam " + fallback.name());
                yield fallback;
            }
        };
    }

    public int maxEra() {
        return this.eras.size();
    }

    public EraDef era(final int id) {
        return this.eras.get(Math.max(1, Math.min(id, this.eras.size())) - 1);
    }

    /** Name of a settlement tier level (1-based). */
    public String tierName(final int level) {
        if (level >= 1 && level <= this.tierNames.size()) {
            return this.tierNames.get(level - 1);
        }
        return "poziom " + level;
    }
}
