// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.zycie.logic.DailyPlan;
import pl.medivalrpg.zycie.logic.DaySchedule;
import pl.medivalrpg.zycie.logic.FestivalRules;
import pl.medivalrpg.zycie.logic.JobCatalog;
import pl.medivalrpg.zycie.logic.MoodSettings;
import pl.medivalrpg.zycie.logic.Places;
import pl.medivalrpg.zycie.logic.SteeringRules;
import pl.medivalrpg.zycie.logic.TextPool;
import pl.medivalrpg.zycie.logic.TextRuleParser;
import pl.medivalrpg.zycie.logic.Texts;
import pl.medivalrpg.zycie.logic.UnrestRules;

/**
 * Typed view of config.yml (immutable after construction; read on the main thread, safe to share). Problems found while
 * reading are appended to the given list (Polish) and replaced by defaults. Uses only {@link ConfigurationSection},
 * so it can be built in unit tests.
 */
public final class ZycieConfig {

    /** A temporary mood modifier after an event: points and duration in ticks. */
    public record EventDef(int points, long duration) {
    }

    public final Set<String> worlds;
    public final boolean broadcasts;

    // performance
    public final int citizensPerTick;
    public final int movesPerTick;
    public final int refreshSeconds;
    public final int playerRange;

    // plan & movement
    public final DaySchedule schedule;
    public final DailyPlan plan;
    public final SteeringRules steering;
    public final double speed;
    public final double fleeSpeed;
    public final double maxTravel;
    public final int spread;
    public final Places places;

    // bubbles
    public final int bubbleTicks;
    public final double bubbleRange;
    public final double bubbleChance;
    public final long bubbleCooldownMs;
    public final int bubbleMax;
    public final int bubbleBackground;
    public final float bubbleHeight;
    public final float bubbleRise;
    public final int bubbleLineWidth;

    // dialogues
    public final int talkPeriod;
    public final double talkChance;
    public final double talkDistance;
    public final long talkCooldownMs;
    public final int talkGap;

    // reactions
    public final double reactRadius;
    public final int reactPeriod;
    public final long reactPairCooldownMs;
    public final long reactVillagerCooldownMs;
    public final double alarmRadius;
    public final int alarmMax;
    public final boolean callGuards;
    public final double guardRadius;
    public final int guardMax;
    public final long fleeMs;

    // mood
    public final int moodRecalcSeconds;
    public final MoodSettings mood;
    public final UnrestRules unrest;
    public final FestivalRules festival;
    public final List<String> greatBuildings;
    public final double danceRadius;
    public final int fireworksFrom;
    public final int fireworksTo;
    public final int fireworksPeriod;
    public final double fireworksChance;
    public final int musicPeriod;
    public final float musicVolume;
    public final List<String> musicSounds;
    public final List<Float> musicPitches;
    public final Map<String, EventDef> events;

    // siege
    public final double siegeRadius;
    public final int siegePeriod;
    public final boolean nightBell;
    public final int bellPeriod;
    public final boolean levy;
    public final double levyShare;
    public final int levyMax;
    public final List<String> levyWeapons;

    // names & texts
    public final boolean jobNames;
    public final JobCatalog jobs;
    public final Map<String, String> concernTemplates;
    /** Mood factor of wages and savings (citizens' life in Ludnosc). */
    public final pl.medivalrpg.zycie.logic.WageMood wageMood;
    public final Map<String, String> factorNames;
    public final TextPool ambient;
    public final TextPool reactions;
    public final TextPool alarm;
    public final TextPool protest;
    public final TextPool festivalTexts;
    public final TextPool siegeTexts;
    public final TextPool levyTexts;
    public final TextPool dialogues;

    public ZycieConfig(final ConfigurationSection c, final List<String> problems) {
        final Set<String> w = new LinkedHashSet<>();
        for (final String s : c.getStringList("swiaty")) {
            w.add(s.trim());
        }
        this.worlds = Set.copyOf(w);
        this.broadcasts = c.getBoolean("ogloszenia", true);

        this.citizensPerTick = clampInt(c, "wydajnosc.obywatele-na-tick", 10, 1, 200, problems);
        this.movesPerTick = clampInt(c, "wydajnosc.ruchy-na-tick", 4, 0, 50, problems);
        this.refreshSeconds = clampInt(c, "wydajnosc.odswiez-liste-sekund", 30, 5, 600, problems);
        this.playerRange = clampInt(c, "wydajnosc.zasieg-graczy", 64, 0, 512, problems);

        final DaySchedule d = DaySchedule.defaults();
        DaySchedule sched = new DaySchedule(
            c.getInt("plan-dnia.praca-od", d.workStart()), c.getInt("plan-dnia.poludnie-od", d.noonStart()),
            c.getInt("plan-dnia.poludnie-do", d.noonEnd()), c.getInt("plan-dnia.spotkanie-od", d.meetStart()),
            c.getInt("plan-dnia.wieczor-od", d.eveningStart()), c.getInt("plan-dnia.noc-od", d.nightStart()),
            c.getInt("plan-dnia.msza-do", d.massEnd()), c.getInt("plan-dnia.dni-tygodnia", d.weekLength()),
            c.getInt("plan-dnia.niedziela", d.sundayIndex()));
        final List<String> sp = sched.validate();
        if (!sp.isEmpty()) {
            problems.addAll(sp);
            sched = d;
        }
        this.schedule = sched;
        final DailyPlan dp = DailyPlan.defaults();
        this.plan = new DailyPlan(share(c, "plan-dnia.udzial-targ", dp.noonMarketShare()),
            share(c, "plan-dnia.udzial-karczma", dp.tavernShare()), share(c, "plan-dnia.udzial-msza", dp.massShare()),
            share(c, "plan-dnia.udzial-protest", dp.protestShare()), share(c, "plan-dnia.udzial-swieto", dp.festivalShare()),
            c.getBoolean("plan-dnia.wieczor-do-domu", dp.eveningHome()));

        final SteeringRules sr = SteeringRules.defaults();
        this.steering = new SteeringRules(c.getDouble("ruch.promien-dotarcia", sr.arriveRadius()),
            c.getDouble("ruch.smycz", sr.leashRadius()), clampInt(c, "ruch.co-tickow", sr.repathTicks(), 10, 1200, problems),
            clampInt(c, "ruch.krok", sr.maxStep(), 8, 48, problems), clampInt(c, "ruch.limit-porazek", sr.failLimit(), 1, 20, problems),
            clampInt(c, "ruch.przerwa-po-porazkach", sr.failCooldown(), 20, 24000, problems));
        this.speed = c.getDouble("ruch.predkosc", 0.6);
        this.maxTravel = c.getDouble("ruch.max-dystans", 128.0);
        this.fleeSpeed = c.getDouble("ruch.predkosc-ucieczki", 0.9);
        this.spread = clampInt(c, "ruch.rozrzut", 3, 0, 8, problems);
        final Places pd = Places.defaults();
        this.places = new Places(tokens(c, "miejsca.targ", pd.market()), tokens(c, "miejsca.karczma", pd.tavern()),
            tokens(c, "miejsca.kosciol", pd.church()), tokens(c, "miejsca.rynek", pd.square()), tokens(c, "miejsca.brama", pd.gate()));

        this.bubbleTicks = clampInt(c, "dymki.czas-tickow", 70, 20, 400, problems);
        this.bubbleRange = c.getDouble("dymki.zasieg", 12.0);
        this.bubbleChance = share(c, "dymki.szansa", 0.12);
        this.bubbleCooldownMs = 1000L * c.getLong("dymki.przerwa-sekund", 25);
        this.bubbleMax = clampInt(c, "dymki.max-naraz", 30, 1, 500, problems);
        this.bubbleBackground = argb(c.getString("dymki.tlo", "#50000000"), problems);
        this.bubbleHeight = (float) c.getDouble("dymki.wysokosc", 0.3);
        this.bubbleRise = (float) c.getDouble("dymki.unoszenie", 0.25);
        this.bubbleLineWidth = clampInt(c, "dymki.szerokosc-linii", 150, 40, 400, problems);

        this.talkPeriod = clampInt(c, "rozmowy.co-tickow", 100, 20, 12000, problems);
        this.talkChance = share(c, "rozmowy.szansa", 0.5);
        this.talkDistance = c.getDouble("rozmowy.odleglosc", 3.5);
        this.talkCooldownMs = 1000L * c.getLong("rozmowy.przerwa-sekund", 90);
        this.talkGap = clampInt(c, "rozmowy.odstep-tickow", 55, 20, 400, problems);

        this.reactRadius = c.getDouble("reakcje.promien", 5.0);
        this.reactPeriod = clampInt(c, "reakcje.co-tickow", 10, 2, 200, problems);
        this.reactPairCooldownMs = 1000L * c.getLong("reakcje.przerwa-gracz-sekund", 90);
        this.reactVillagerCooldownMs = 1000L * c.getLong("reakcje.przerwa-wiesniak-sekund", 15);
        this.alarmRadius = c.getDouble("reakcje.alarm-promien", 12.0);
        this.alarmMax = clampInt(c, "reakcje.alarm-max", 6, 0, 50, problems);
        this.callGuards = c.getBoolean("reakcje.wezwij-straz", true);
        this.guardRadius = c.getDouble("reakcje.straz-promien", 24.0);
        this.guardMax = clampInt(c, "reakcje.straz-max", 4, 0, 30, problems);
        this.fleeMs = 1000L * c.getLong("reakcje.ucieczka-sekund", 40);

        this.moodRecalcSeconds = clampInt(c, "nastroj.przelicz-sekund", 180, 10, 3600, problems);
        this.mood = moodSettings(c, problems);
        final UnrestRules ud = UnrestRules.defaults();
        this.unrest = new UnrestRules(c.getInt("protest.ponizej", ud.protestBelow()), c.getLong("protest.po-tickach", ud.protestAfter()),
            c.getInt("protest.koniec-od", ud.protestEndAt()), c.getInt("protest.odejscia-ponizej", ud.exodusBelow()));
        if (this.unrest.protestEndAt() < this.unrest.protestBelow()) {
            problems.add("protest.koniec-od musi być ≥ protest.ponizej");
        }
        final FestivalRules fd = FestivalRules.defaults();
        this.festival = new FestivalRules(Math.max(1200, c.getLong("swieto.czas-tickow", fd.duration())),
            Math.max(0, c.getLong("swieto.przerwa-tickow", fd.cooldown())), c.getInt("swieto.premia", fd.moodBonus()));
        this.greatBuildings = tokens(c, "swieto.wielkie-budynki", List.of("palac", "katedra", "zamek"));
        this.danceRadius = c.getDouble("swieto.taniec-promien", 16.0);
        this.fireworksFrom = c.getInt("swieto.fajerwerki.od", 11500);
        this.fireworksTo = c.getInt("swieto.fajerwerki.do", 14500);
        this.fireworksPeriod = clampInt(c, "swieto.fajerwerki.co-tickow", 40, 5, 2400, problems);
        this.fireworksChance = share(c, "swieto.fajerwerki.szansa", 0.7);
        this.musicPeriod = clampInt(c, "swieto.muzyka.co-tickow", 8, 2, 200, problems);
        this.musicVolume = (float) c.getDouble("swieto.muzyka.glosnosc", 1.2);
        this.musicSounds = List.copyOf(c.getStringList("swieto.muzyka.dzwieki"));
        final List<Float> pitches = new ArrayList<>();
        for (final Double p : c.getDoubleList("swieto.muzyka.tony")) {
            pitches.add((float) Math.max(0.5, Math.min(2.0, p)));
        }
        this.musicPitches = pitches.isEmpty() ? List.of(1.0f) : List.copyOf(pitches);

        final Map<String, EventDef> ev = new HashMap<>();
        final ConfigurationSection es = c.getConfigurationSection("zdarzenia");
        if (es != null) {
            for (final String key : es.getKeys(false)) {
                ev.put(Texts.normalize(key), new EventDef(es.getInt(key + ".punkty", 0), Math.max(20, es.getLong(key + ".czas", 24000))));
            }
        }
        this.events = Map.copyOf(ev);

        this.siegeRadius = c.getDouble("oblezenie.promien", 48.0);
        this.siegePeriod = clampInt(c, "oblezenie.co-tickow", 40, 10, 1200, problems);
        this.nightBell = c.getBoolean("oblezenie.dzwon-noca", true);
        this.bellPeriod = clampInt(c, "oblezenie.dzwon-co-tickow", 200, 20, 6000, problems);
        this.levy = c.getBoolean("oblezenie.pospolite-ruszenie", true);
        this.levyShare = share(c, "oblezenie.ruszenie-udzial", 0.3);
        this.levyMax = clampInt(c, "oblezenie.ruszenie-max", 6, 0, 50, problems);
        final List<String> weapons = new ArrayList<>();
        for (final String s : c.getStringList("oblezenie.bronie")) {
            weapons.add(s.trim().toUpperCase(Locale.ROOT));
        }
        this.levyWeapons = weapons.isEmpty() ? List.of("IRON_HOE", "IRON_AXE") : List.copyOf(weapons);

        this.jobNames = c.getBoolean("zawody-nazwy", true);
        this.jobs = JobCatalog.parse(c.getMapList("zawody"), problems);
        this.concernTemplates = new java.util.HashMap<>(strings(c.getConfigurationSection("zmartwienia")));
        this.concernTemplates.putIfAbsent("brak_wyplat", "Skarbiec nie płaci pełnych pensji (<procent>% pracowników)");
        final pl.medivalrpg.zycie.logic.WageMood wm = pl.medivalrpg.zycie.logic.WageMood.defaults();
        this.wageMood = new pl.medivalrpg.zycie.logic.WageMood(Math.max(0, c.getInt("nastroj.zarobki.kara-max", wm.maxPenalty())),
            Math.max(0, c.getInt("nastroj.zarobki.premia-zamoznosc", wm.wealthBonus())),
            Math.max(0, c.getLong("nastroj.zarobki.zamoznosc-od", wm.wealthFrom())));
        this.factorNames = strings(c.getConfigurationSection("czynniki"));
        this.ambient = TextRuleParser.parse(c.getMapList("teksty.dymki"), "teksty.dymki", problems);
        this.reactions = TextRuleParser.parse(c.getMapList("teksty.reakcje"), "teksty.reakcje", problems);
        this.alarm = TextRuleParser.parse(c.getMapList("teksty.alarm"), "teksty.alarm", problems);
        this.protest = TextRuleParser.parse(c.getMapList("teksty.protest"), "teksty.protest", problems);
        this.festivalTexts = TextRuleParser.parse(c.getMapList("teksty.swieto"), "teksty.swieto", problems);
        this.siegeTexts = TextRuleParser.parse(c.getMapList("teksty.oblezenie"), "teksty.oblezenie", problems);
        this.levyTexts = TextRuleParser.parse(c.getMapList("teksty.ruszenie"), "teksty.ruszenie", problems);
        this.dialogues = TextRuleParser.parse(c.getMapList("rozmowy"), "rozmowy", problems);
        if (this.ambient.size() == 0) {
            problems.add("teksty.dymki jest puste — wieśniacy nie będą nic mówić");
        }
    }

    public EventDef event(final String key) {
        return this.events.getOrDefault(key, new EventDef(0, 24000));
    }

    public boolean worldEnabled(final String world) {
        return this.worlds.isEmpty() || this.worlds.contains(world);
    }

    // ------------------------------------------------------------------ helpers

    private static MoodSettings moodSettings(final ConfigurationSection c, final List<String> problems) {
        final MoodSettings d = MoodSettings.defaults();
        final String n = "nastroj.";
        final List<MoodSettings.ComfortItem> comfort = new ArrayList<>();
        final List<Map<?, ?>> raw = c.getMapList(n + "komfort.budynki");
        int i = 0;
        for (final Map<?, ?> m : raw) {
            i++;
            final List<String> toks = new ArrayList<>();
            final Object b = m.get("budynki");
            if (b instanceof List<?> l) {
                for (final Object o : l) {
                    toks.add(Texts.normalize(String.valueOf(o)));
                }
            }
            if (toks.isEmpty()) {
                problems.add("nastroj.komfort.budynki[" + i + "]: brak listy budynki");
                continue;
            }
            comfort.add(new MoodSettings.ComfortItem(List.copyOf(toks), String.valueOf(m.get("nazwa") == null ? toks.getFirst() : m.get("nazwa")),
                num(m.get("punkty"), 0), num(m.get("od-epoki"), 1), num(m.get("oczekiwane-od"), 0)));
        }
        return new MoodSettings(
            c.getInt(n + "bazowy", d.base()), c.getInt(n + "max-zmiana", d.maxStep()),
            c.getInt(n + "jedzenie.syci", d.fedBonus()), c.getInt(n + "jedzenie.malo-zapasow", d.lowStockPenalty()),
            c.getInt(n + "jedzenie.glod-bazowo", d.hungerBase()), c.getInt(n + "jedzenie.glod-za-dzien", d.hungerPerDay()),
            c.getInt(n + "jedzenie.glod-max", d.hungerCap()),
            c.getInt(n + "mieszkania.przestronnie", d.roomyBonus()), c.getDouble(n + "mieszkania.przestronnie-od", d.roomyRatio()),
            c.getInt(n + "mieszkania.bezdomnosc-bazowo", d.homelessBase()), c.getInt(n + "mieszkania.bezdomnosc-skala", d.homelessScale()),
            c.getInt(n + "mieszkania.bezdomnosc-za-dzien", d.homelessPerDay()), c.getInt(n + "mieszkania.bezdomnosc-max", d.homelessCap()),
            c.getLong(n + "podatki.gracz-max", d.playerTaxMax()),
            Math.max(0.0, Math.min(0.99, c.getDouble(n + "podatki.gracz-bez-narzekan", d.playerTaxFree()))),
            c.getInt(n + "podatki.gracz-kara", d.playerTaxPenalty()), c.getInt(n + "podatki.bez-podatku", d.noTaxBonus()),
            c.getDouble(n + "podatki.npc-normalny", d.npcTaxNormal()), c.getInt(n + "podatki.npc-kara", d.npcTaxPenalty()),
            c.getInt(n + "podatki.niskie", d.lowTaxBonus()), c.getInt(n + "podatki.max", d.taxCap()),
            c.getInt(n + "podatki.zmartwienie-od", d.taxConcernAt()),
            c.getInt(n + "wojna.za-wojne", d.warPenalty()), c.getInt(n + "wojna.max", d.warCap()),
            c.getInt(n + "wojna.za-oblezenie", d.siegePenalty()), c.getInt(n + "wojna.oblezenia-max", d.siegeCap()),
            c.getInt(n + "wojna.pokoj", d.peaceBonus()), c.getInt(n + "wojna.wasal", d.vassalPenalty()),
            raw.isEmpty() ? d.comfort() : List.copyOf(comfort),
            c.getInt(n + "komfort.max", d.comfortCap()), c.getInt(n + "komfort.oczekiwania-za-epoke", d.eraExpectation()),
            c.getDouble(n + "mnoznik.min", d.multiplierMin()), c.getDouble(n + "mnoznik.max", d.multiplierMax()),
            c.getDouble(n + "mnoznik.protest", d.protestPenalty()));
    }

    private static int num(final Object o, final int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    private static int clampInt(final ConfigurationSection c, final String path, final int def, final int min, final int max,
                                final List<String> problems) {
        final int v = c.getInt(path, def);
        if (v < min || v > max) {
            problems.add(path + " = " + v + " poza zakresem " + min + "–" + max + " (użyto " + Math.max(min, Math.min(max, v)) + ")");
            return Math.max(min, Math.min(max, v));
        }
        return v;
    }

    private static double share(final ConfigurationSection c, final String path, final double def) {
        return Math.max(0.0, Math.min(1.0, c.getDouble(path, def)));
    }

    private static List<String> tokens(final ConfigurationSection c, final String path, final List<String> def) {
        if (!c.isList(path)) {
            return def;
        }
        final List<String> out = new ArrayList<>();
        for (final String s : c.getStringList(path)) {
            out.add(Texts.normalize(s));
        }
        return List.copyOf(out);
    }

    private static Map<String, String> strings(final ConfigurationSection s) {
        final Map<String, String> out = new HashMap<>();
        if (s != null) {
            for (final String k : s.getKeys(false)) {
                if (s.isString(k)) {
                    out.put(k, s.getString(k));
                }
            }
        }
        return Map.copyOf(out);
    }

    /** {@code #AARRGGBB} or {@code #RRGGBB} → ARGB int. */
    static int argb(final String hex, final List<String> problems) {
        final String h = hex == null ? "" : hex.trim().replace("#", "");
        try {
            if (h.length() == 6) {
                return 0xFF000000 | Integer.parseInt(h, 16);
            }
            if (h.length() == 8) {
                return (int) Long.parseLong(h, 16);
            }
        } catch (final NumberFormatException ignored) {
            // fall through
        }
        problems.add("dymki.tlo: zły kolor " + hex + " (oczekiwano #AARRGGBB)");
        return 0x50000000;
    }
}
