// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.api.SiegeInfo;
import pl.medivalrpg.ludnosc.api.LudnoscInfo;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.config.Messages;
import pl.medivalrpg.zycie.config.ZycieConfig;
import pl.medivalrpg.zycie.logic.Concern;
import pl.medivalrpg.zycie.logic.DaySchedule;
import pl.medivalrpg.zycie.logic.FestivalRules;
import pl.medivalrpg.zycie.logic.MoodCalculator;
import pl.medivalrpg.zycie.logic.MoodInputs;
import pl.medivalrpg.zycie.logic.UnrestRules;
import pl.medivalrpg.zycie.storage.CountryState;
import pl.medivalrpg.zycie.storage.DataStore;

/**
 * Mood of every country: gathers the inputs (Kraje, Ludnosc, Armia, Postep), runs {@link MoodCalculator}, keeps the
 * persistent state (modifiers, protest, festival) and publishes thread-safe {@link MoodSnapshot}s. Main thread,
 * except {@link #snapshot}.
 */
public final class MoodService {

    private static final long MAX_MODIFIER_FUTURE = 30L * DaySchedule.DAY_TICKS;

    private final ZyciePlugin plugin;
    private final KrajeApi kraje;
    private final CityCache cities;
    private final Integrations integrations;
    private final DataStore store;
    private DataStore.Data data = new DataStore.Data();
    private final Map<UUID, MoodSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastBuilding = new HashMap<>();
    private ZycieConfig cfg;
    private Messages messages;
    private MoodCalculator calculator;
    private boolean dirty;

    public MoodService(final ZyciePlugin plugin, final KrajeApi kraje, final CityCache cities, final Integrations integrations,
                       final DataStore store) {
        this.plugin = plugin;
        this.kraje = kraje;
        this.cities = cities;
        this.integrations = integrations;
        this.store = store;
        cities.onNewBuildings(this::onNewBuildings);
    }

    public void configure(final ZycieConfig config, final Messages msgs) {
        this.cfg = config;
        this.messages = msgs;
        this.calculator = new MoodCalculator(config.mood);
    }

    public void load() {
        this.data = this.store.load();
    }

    public void saveIfDirty() {
        if (this.dirty) {
            this.dirty = false;
            this.store.saveAsync(this.data);
        }
    }

    public void saveNow() {
        this.store.saveNow(this.data);
    }

    // ------------------------------------------------------------------ clock & reads

    /** The "Minecraft clock" of moods, protests and festivals: full time of the main world (ticks). */
    public static long now() {
        final List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? 0L : worlds.getFirst().getFullTime();
    }

    /** Thread-safe. */
    public MoodSnapshot snapshot(final UUID country) {
        return country == null ? null : this.snapshots.get(country);
    }

    public MoodSnapshot snapshotOrNeutral(final UUID country) {
        final MoodSnapshot s = this.snapshot(country);
        return s == null ? MoodSnapshot.NEUTRAL : s;
    }

    public Map<UUID, MoodSnapshot> snapshots() {
        return this.snapshots;
    }

    private CountryState state(final UUID country) {
        return this.data.countries.computeIfAbsent(country, k -> new CountryState());
    }

    public boolean festivalActive(final UUID country) {
        final CountryState st = this.data.countries.get(country);
        return st != null && st.festival().active(now());
    }

    /** Countries with a running festival. */
    public List<UUID> festivalCountries() {
        final long now = now();
        final List<UUID> out = new ArrayList<>();
        for (final Map.Entry<UUID, CountryState> e : this.data.countries.entrySet()) {
            if (e.getValue().festival().active(now)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Festival / protest spot: square next to the capital (or the capital centre); null when not loaded. */
    public Location squareSpot(final UUID country) {
        return this.kraje.getCountry(country).map(k -> this.cities.squareSpot(k, this.cfg.places.square())).orElse(null);
    }

    // ------------------------------------------------------------------ calculation

    public void recalcAll() {
        for (final KrajInfo k : this.kraje.getCountries()) {
            try {
                this.recalc(k);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Błąd liczenia nastroju kraju " + k.name(), e);
            }
        }
        this.snapshots.keySet().removeIf(id -> this.kraje.getCountry(id).isEmpty());
    }

    public void recalc(final UUID country) {
        this.kraje.getCountry(country).ifPresent(this::recalc);
    }

    public void recalc(final KrajInfo k) {
        this.recalc(k, true);
    }

    /** @param smooth false = keep the current mood as is this time (admin change) */
    private void recalc(final KrajInfo k, final boolean smooth) {
        final long now = now();
        final CountryState st = this.state(k.id());
        final CityCache.City city = this.cities.get(k.id());
        final LudnoscInfo info = city.info();

        // era advance noticed without Postep's event (e.g. Postep loaded later). Only a real answer of Postep counts:
        // without it the era is unknown, not 1 (a fallback 1 used to lower st.era and fire a false "new era" festival
        // for every era-2 country once Postep came back)
        final java.util.OptionalInt known = this.integrations.eraIfKnown(k.id());
        final int era = known.isPresent() ? known.getAsInt() : Math.max(1, st.era); // unknown → the last known era
        if (known.isPresent()) {
            if (st.era >= 1 && era > st.era) {
                this.onEraAdvance(k.id(), st.era, era);
            }
            if (st.era != era) {
                st.era = era;
                this.dirty = true;
            }
        }

        // great buildings → festival
        final int great = city.count(this.cfg.greatBuildings);
        if (info != null && st.greatBuildings != great) {
            final boolean finished = FestivalRules.greatBuildingFinished(st.greatBuildings, great);
            st.greatBuildings = great;
            this.dirty = true;
            if (finished) {
                final String name = this.lastBuilding.getOrDefault(k.id(), "");
                this.startFestival(k.id(), this.messages.raw("reason-building").replace("<building>", name.isBlank() ? "wielki budynek" : name), false);
            }
        }

        final Map<String, Integer> modifiers = new HashMap<>(st.activeModifiers(now, MAX_MODIFIER_FUTURE));
        final FestivalRules.State fest = st.festival();
        if (fest.until() - now > MAX_MODIFIER_FUTURE) {
            st.festival(FestivalRules.State.NONE); // /time set backwards
        }
        final boolean festival = st.festival().active(now);
        if (festival) {
            modifiers.merge("swieto", this.cfg.festival.moodBonus(), Integer::sum);
        }
        // citizens' life (Ludnosc): unpaid wages hurt, comfortable savings help
        final int[] life = this.integrations.ludnoscLife(k.id());
        final int wagePoints = life == null ? 0 : this.cfg.wageMood.points(life[0], life[1]);
        if (wagePoints != 0) {
            modifiers.merge("zarobki", wagePoints, Integer::sum);
        }

        final List<String> enemies = new ArrayList<>();
        for (final UUID e : k.enemies()) {
            this.kraje.getCountry(e).ifPresent(x -> enemies.add(x.name()));
        }
        final List<String> siegers = new ArrayList<>();
        for (final SiegeInfo s : this.kraje.getSieges()) {
            if (k.id().equals(s.defender())) {
                siegers.add(this.kraje.getCountry(s.attacker()).map(KrajInfo::name).orElse("wróg"));
            }
        }
        String overlord = null;
        try {
            overlord = this.kraje.getOverlord(k.id()).flatMap(this.kraje::getCountry).map(KrajInfo::name).orElse(null);
        } catch (final RuntimeException ignored) {
            // older Kraje
        }
        final int population = info == null ? 0 : info.population();
        final int taxpayers = Math.max(0, k.members().size() - 1);
        final int npcPercent = k.npc() ? this.integrations.npcTaxMultiplier(k.id()).orElse(100) : 100;
        final MoodInputs in = new MoodInputs(info != null, population, info == null ? 0 : info.housing(),
            info == null ? 0 : info.food(), info == null ? 0 : info.foodBalance(), info == null ? 0 : info.hungryDays(),
            info == null ? 0 : info.homelessDays(), k.taxPerCitizen(), taxpayers,
            info == null || population <= 0 ? 0.0 : info.dailyTax() / (double) population, npcPercent, enemies, siegers,
            overlord, city.tokens(), era, modifiers);
        final MoodCalculator.Result r = this.calculator.calculate(in);

        final int previous = st.mood;
        st.mood = smooth || st.mood < 0 ? MoodCalculator.step(st.mood, r.target(), this.cfg.mood.maxStep()) : st.mood;
        final UnrestRules.State before = st.unrest();
        final UnrestRules.State after = this.cfg.unrest.update(before, st.mood, now);
        st.unrest(after);
        if (!before.protest() && after.protest()) {
            this.broadcast("broadcast-protest", k);
        } else if (before.protest() && !after.protest()) {
            this.broadcast("broadcast-protest-end", k);
        }
        if (previous != st.mood || !before.equals(after)) {
            this.dirty = true;
        }

        final boolean exodus = this.cfg.unrest.exodus(st.mood);
        final List<String> concerns = new ArrayList<>();
        if (after.protest()) {
            concerns.add(this.concernText(Concern.of("protest", 100)));
        }
        if (exodus) {
            concerns.add(this.concernText(Concern.of("odejscia", 99)));
        }
        if (life != null && life[0] > 0) {
            final String text = this.concernText(Concern.of("brak_wyplat", -wagePoints, "procent", Integer.toString(life[0])));
            if (text != null) {
                concerns.add(text);
            }
        }
        final Set<String> keys = new LinkedHashSet<>();
        boolean highTaxes = false;
        boolean hungry = false;
        boolean homeless = false;
        for (final Concern c : r.concerns()) {
            highTaxes |= c.key().equals("podatki");
            hungry |= c.key().equals("glod") || c.key().equals("malo_jedzenia");
            homeless |= c.key().equals("brak_lozek");
            final String text = this.concernText(c);
            if (text != null && keys.add(text)) {
                concerns.add(text);
            }
        }
        final boolean unrest = this.cfg.unrest.unrest(after, st.mood);
        this.snapshots.put(k.id(), new MoodSnapshot(st.mood, r.factors(), List.copyOf(concerns),
            MoodCalculator.multiplier(st.mood, after.protest(), this.cfg.mood), unrest, after.protest(), exodus, festival,
            era, !enemies.isEmpty(), enemies.isEmpty() ? "" : enemies.getFirst(), !siegers.isEmpty(), hungry, highTaxes,
            homeless, Set.copyOf(modifiers.keySet()), population, info == null ? 0 : info.housing(),
            this.lastBuilding.getOrDefault(k.id(), "")));
    }

    private String concernText(final Concern c) {
        final String template = this.cfg.concernTemplates.get(c.key());
        if (template == null || template.isBlank()) {
            return null;
        }
        String t = template;
        for (final Map.Entry<String, String> a : c.args().entrySet()) {
            t = t.replace("<" + a.getKey() + ">", a.getValue());
        }
        return t;
    }

    // ------------------------------------------------------------------ events

    /** Adds the configured modifier of an event ({@code zdarzenia.<key>}). */
    public void addEvent(final UUID country, final String key) {
        final ZycieConfig.EventDef def = this.cfg.event(key);
        if (def.points() == 0 && !"admin".equals(key)) {
            return;
        }
        this.state(country).addModifier(key, def.points(), now() + def.duration());
        this.dirty = true;
    }

    /** {@code /zycie admin nastroj}: shifts the mood now and keeps the shift as a temporary "admin" modifier. */
    public int adminAdjust(final UUID country, final int delta) {
        final long now = now();
        final CountryState st = this.state(country);
        final int current = st.activeModifiers(now, MAX_MODIFIER_FUTURE).getOrDefault("admin", 0);
        st.addModifier("admin", current + delta, now + this.cfg.event("admin").duration());
        st.mood = MoodCalculator.clamp((st.mood < 0 ? this.cfg.mood.base() : st.mood) + delta);
        this.dirty = true;
        this.kraje.getCountry(country).ifPresent(k -> this.recalc(k, false));
        return st.mood;
    }

    /**
     * Starts (or prolongs) a festival. {@code reason} is plain Polish text for the announcement.
     *
     * @param forced admin: ignores the cooldown
     * @return true when a festival started
     */
    public boolean startFestival(final UUID country, final String reason, final boolean forced) {
        final long now = now();
        final CountryState st = this.state(country);
        final FestivalRules.State before = st.festival();
        final FestivalRules.State after = this.cfg.festival.start(before, now, forced);
        if (after.equals(before)) {
            return false;
        }
        final boolean wasActive = before.active(now);
        st.festival(after);
        st.festivalReason = reason == null ? "" : reason;
        this.dirty = true;
        if (!wasActive) {
            this.kraje.getCountry(country).ifPresent(k -> this.broadcast("broadcast-festival", k,
                Placeholder.unparsed("reason", st.festivalReason)));
        }
        this.recalc(country);
        return true;
    }

    public void onEraAdvance(final UUID country, final int oldEra, final int newEra) {
        final CountryState st = this.state(country);
        if (st.era >= newEra) {
            return; // already handled (event + periodic check)
        }
        st.era = newEra;
        this.addEvent(country, "awans_epoki");
        this.startFestival(country, this.messages.raw("reason-era"), false);
    }

    private void onNewBuildings(final UUID country, final List<CityCache.Spot> spots) {
        final String name = spots.getLast().name();
        this.lastBuilding.put(country, name == null ? "" : name);
        this.addEvent(country, "nowy_budynek");
    }

    public void remove(final UUID country) {
        this.data.countries.remove(country);
        this.snapshots.remove(country);
        this.lastBuilding.remove(country);
        this.cities.forget(country);
        this.dirty = true;
    }

    public Optional<CountryState> rawState(final UUID country) {
        return Optional.ofNullable(this.data.countries.get(country));
    }

    private void broadcast(final String key, final KrajInfo k, final net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        if (!this.cfg.broadcasts) {
            return;
        }
        final List<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver> all = new ArrayList<>(List.of(extra));
        all.add(Placeholder.unparsed("country", k.name()));
        all.add(Placeholder.styling("color", TextColor.color(k.color())));
        final Component msg = this.messages.get(key, all.toArray(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new));
        Bukkit.broadcast(msg);
    }
}
