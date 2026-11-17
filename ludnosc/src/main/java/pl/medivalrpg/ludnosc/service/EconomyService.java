// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.logic.DailySchedule;
import pl.medivalrpg.ludnosc.logic.Economy;
import pl.medivalrpg.ludnosc.logic.Growth;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Daily economy (food production, meals, NPC taxes into the treasury, departures) and periodic population growth.
 * Everything is computed from the registry; only spawning a settler touches the world (async chunk load).
 */
public final class EconomyService {

    private final LudnoscPlugin plugin;
    private final Random random = new Random();

    public EconomyService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** Ids of all existing countries (Kraje). */
    private List<KrajInfo> countries() {
        return new ArrayList<>(this.plugin.kraje().getCountries());
    }

    /** Current state of a country as input of the daily computation (thread-safe reads). */
    public Economy.DayInput input(final UUID country, final boolean npc) {
        final Map<String, Integer> adults = new HashMap<>();
        int babies = 0;
        for (final CitizenRecord r : this.data().citizensOf(country)) {
            if (r.adult()) {
                adults.merge(Economy.professionKey(r.profession()), 1, Integer::sum);
            } else {
                babies++;
            }
        }
        final CountryState s = this.data().stateIfPresent(country);
        int farms = 0;
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.function() == pl.medivalrpg.ludnosc.template.BuildingFunction.FARM) {
                farms++;
            }
        }
        if (s != null) {
            farms += s.villageFarmland / this.plugin.settings().villageFarmlandPerField;
        }
        final Economy.DayInput base = new Economy.DayInput(adults, babies, this.data().housing(country), s == null ? 0 : s.food,
            this.plugin.granary().capacity(country), npc, s == null ? 0 : s.hungryDays, s == null ? 0 : s.homelessDays, farms,
            this.bonus(country).food());
        // No second, virtual harvest. Real food is removed from registered physical stores at feeding time.
        final long virtual=Economy.production(base,this.plugin.settings().rules);
        return new Economy.DayInput(adults,babies,base.housing(),base.food(),base.capacity(),npc,base.hungryDays(),base.homelessDays(),farms,
            base.bonusFood()-virtual);
    }

    public long expectedTax(final UUID country) {
        final boolean npc = this.plugin.kraje().getCountry(country).map(KrajInfo::npc).orElse(false);
        return Math.round(Economy.expectedTax(this.input(country, npc), this.plugin.settings().rules)
            * (1 + this.bonus(country).tax()) * this.plugin.zycie().moodMultiplier(country));
    }

    /** Sum of the bonuses of the country's finished buildings (config {@code buildings.<id>.bonus}). */
    public pl.medivalrpg.ludnosc.config.LudnoscConfig.Bonus bonus(final UUID country) {
        long food = 0;
        double tax = 0;
        double growth = 0;
        long granary = 0;
        for (final Building b : this.data().buildingsOf(country)) {
            final String own = this.plugin.playerBuildings() == null ? null : this.plugin.playerBuildings().activeKind(b);
            var bonus = this.plugin.settings().bonuses.get(own != null ? own : b.type());
            if (bonus == null) { // look variants (variant-of) get the bonus of their base type
                bonus = this.plugin.settings().bonuses.get(this.plugin.templates().baseType(b.type()));
            }
            if (bonus != null) {
                food += bonus.food();
                tax += bonus.tax();
                growth += bonus.growth();
                granary += bonus.granary();
            }
        }
        return new pl.medivalrpg.ludnosc.config.LudnoscConfig.Bonus(food, tax, growth, granary);
    }

    /** Daily income of the country's mines (gallery blocks × income per block). */
    public long mineIncome(final UUID country) {
        return this.plugin.mines().income(country);
    }

    /** Expected daily food balance (production − consumption). */
    public long expectedFoodBalance(final UUID country) {
        final boolean npc = this.plugin.kraje().getCountry(country).map(KrajInfo::npc).orElse(false);
        final Economy.DayInput in = this.input(country, npc);
        return Economy.production(in, this.plugin.settings().rules)
            - (long) in.population() * this.plugin.settings().rules.foodPerCitizen();
    }

    // ------------------------------------------------------------------ daily

    /** Runs due daily ticks (called every minute and on enable). */
    public void checkDaily() {
        final ZonedDateTime now = ZonedDateTime.now(this.plugin.settings().dailyZone);
        final LocalDate last = this.data().lastDaily();
        if (last == null) {
            this.data().setLastDaily(DailySchedule.lastPassed(now, this.plugin.settings().dailyTime));
            return;
        }
        final List<LocalDate> due = DailySchedule.due(last, now, this.plugin.settings().dailyTime,
            this.plugin.settings().maxCatchUpDays);
        // at most once: the day is marked and saved before any transfer (the bank ledger is durable on its own)
        pl.medivalrpg.ludnosc.logic.DailyRun.settle(due, this.data()::setLastDaily, this.plugin::saveRoyalRegistry, this::runDay);
    }

    /** One daily tick for every country; a failure of one country never stops (nor repeats) the others. */
    public void runDay() {
        final SkarbiecApi eco = this.plugin.skarbiec();
        pl.medivalrpg.ludnosc.logic.DailyRun.eachIsolated(this.countries(), k -> this.runDay(k, eco),
            (k, e) -> this.plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Rozliczenie dnia kraju " + k.name() + " przerwane (pozostałe kraje rozliczone dalej)", e));
        this.plugin.saveRoyalRegistry(); // the settled day (food, tax statistics, departures) is on disk at once
    }

    private void runDay(final KrajInfo k, final SkarbiecApi eco) {
        final UUID id = k.id();
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(id))return;
        if (this.data().population(id) == 0 && this.data().stateIfPresent(id) == null && !k.npc()) {
            return;
        }
        this.plugin.seasons().daily(k);
        this.plugin.production().feed(id);
        final Economy.DayInput in = this.input(id, k.npc());
        final Economy.DayResult raw = Economy.day(in, this.plugin.settings().rules);
        // starvation is gradual: at most seasons/departures.max-fraction-per-day of the population leaves a day
        final int leave = this.plugin.settings().work.seasons.capDepartures(raw.departures(), in.population());
        final Economy.DayResult r = new Economy.DayResult(raw.food(), raw.produced(), raw.eaten(), raw.fedRatio(), raw.tax(), leave,
            raw.hungryDays(), raw.homelessDays());
        final CountryState s = this.data().state(id);
        s.food = r.food();
        s.hungryDays = r.hungryDays();
        s.homelessDays = r.homelessDays();
        final long citizensTax = Math.round(r.tax() * (1 + this.bonus(id).tax()) * this.plugin.zycie().moodMultiplier(id));
        final long mines = this.mineIncome(id);
        this.plugin.resources().daily(id);
        s.lastProduced = r.produced();
        s.lastEaten = r.eaten();
        s.lastDepartures = r.departures();
        this.data().markDirty();
        // Decision 2026-10-29 (variant A): citizens earn their income from the households pool and pay the NPC tax
        // from their own savings to the treasury (LifeService, transfers only). Without it: the old direct deposit.
        final LifeService life = this.plugin.life();
        long collected = 0;
        boolean viaCitizens = false;
        if (life != null && eco != null && life.collectsTax()) {
            viaCitizens = true;
            try {
                collected = life.daily(k, r.fedRatio(), citizensTax);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Życie obywateli: rozliczenie dnia " + k.name(), e);
            }
        } else if (life != null) {
            try {
                life.daily(k, r.fedRatio(), 0);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Życie obywateli: rozliczenie dnia " + k.name(), e);
            }
        }
        final long direct = viaCitizens ? mines : citizensTax + mines;
        if (direct > 0 && eco != null) {
            final var tx = eco.deposit(SkarbiecApi.countryAccount(id), direct, "Ludnosc: podatek od obywateli NPC i kopalnie");
            if (!tx.ok()) {
                this.plugin.getLogger().warning("Nie udało się wpłacić podatku NPC krajowi " + k.name() + ": " + tx);
            } else {
                collected += direct;
            }
        }
        s.lastTax = viaCitizens ? collected : citizensTax + mines;
        if (r.departures() > 0) {
            final List<CitizenRecord> recs = new ArrayList<>(this.data().citizensOf(id));
            Collections.shuffle(recs, this.random);
            for (int i = 0; i < r.departures() && i < recs.size(); i++) {
                this.plugin.citizens().depart(recs.get(i));
            }
            this.plugin.notifyCountry(id, "notify-departures", Placeholder.unparsed("n", Integer.toString(r.departures())));
        }
    }

    // ------------------------------------------------------------------ growth

    /** One growth tick: settlers for countries with free beds and food. Returns settlers ordered. */
    public int growthTick() {
        int total = 0;
        for (final KrajInfo k : this.countries()) {
            final UUID id = k.id();
            if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(id))continue;
            final CountryState s = this.data().stateIfPresent(id);
            final long food = s == null ? 0 : s.food;
            // Zycie: unrest stops the growth; the mood scales it (a bad mood skips growth ticks, a good one adds settlers)
            if (this.plugin.zycie().unrest(id)) {
                continue;
            }
            final double mood = this.plugin.zycie().moodMultiplier(id);
            if (this.plugin.life() != null && this.plugin.life().growthEnabled()) {
                // citizens' life: the mood scales the chances inside LifeGrowth (no extra skipped ticks)
                total += this.lifeGrowth(id, food, Math.min(1.25, mood),
                    this.bonus(id).growth() + this.plugin.postep().effect(id, "ludnosc") / 100.0);
                continue;
            }
            if (mood < 1 && this.random.nextDouble() > mood) {
                continue;
            }
            final double growth = this.bonus(id).growth() + this.plugin.postep().effect(id, "ludnosc") / 100.0 + Math.max(0, mood - 1);
            final int extra = (int) Math.floor(growth) + (this.random.nextDouble() < growth - Math.floor(growth) ? 1 : 0);
            final int n = Growth.newSettlers(this.data().population(id), 0,
                this.data().housing(id), food, this.plugin.settings().foodPerSettler, this.plugin.settings().growthMaxPerTick + extra);
            for (int i = 0; i < n; i++) {
                final Location loc = this.homeSpawn(id);
                if (loc == null) {
                    break;
                }
                final CountryState st = this.data().state(id);
                final long cost = this.plugin.settings().foodPerSettler;
                st.food = Math.max(0, st.food - cost);
                this.data().markDirty();
                final CitizenRecord rec = this.plugin.citizens().spawnOrQueue(id, loc, null);
                if (rec == null) {
                    st.food += cost; // refund
                } else {
                    this.plugin.notifyCountry(id, "notify-settler", Placeholder.unparsed("name", rec.name()));
                }
                total++;
            }
        }
        return total;
    }

    /**
     * Growth by the citizens' life rules ({@link pl.medivalrpg.ludnosc.logic.LifeGrowth}): children of couples and
     * settlers, only with free beds and food; more likely with a full granary and wealthy citizens. Each newcomer eats
     * {@code growth.food-per-settler} from the granary (refunded when it cannot be placed).
     */
    private int lifeGrowth(final UUID id, final long food, final double mood, final double bonus) {
        final LifeService life = this.plugin.life();
        final List<UUID[]> couples = life.eligibleCouples(id);
        final pl.medivalrpg.ludnosc.logic.LifeGrowth.Outcome o = pl.medivalrpg.ludnosc.logic.LifeGrowth.tick(
            life.growthInput(id, food, mood, bonus, couples.size()), life.settings().growthSettings(), this.random);
        int done = 0;
        final long cost = this.plugin.settings().foodPerSettler;
        for (int i = 0; i < o.births(); i++) {
            final UUID[] c = couples.get(i);
            Location loc = life.birthPlace(c[0]);
            if (loc == null) {
                loc = life.birthPlace(c[1]);
            }
            if (loc == null) {
                loc = this.homeSpawn(id);
            }
            if (loc == null) {
                break;
            }
            final CountryState st = this.data().state(id);
            st.food = Math.max(0, st.food - cost);
            this.data().markDirty();
            final CitizenRecord child = this.plugin.citizens().spawnChild(id, loc);
            if (child == null) {
                st.food += cost;
                continue;
            }
            life.born(child, c[0], c[1]);
            final CitizenRecord mother = this.data().citizen(c[0]);
            final CitizenRecord father = this.data().citizen(c[1]);
            life.tell(id, "born", Placeholder.unparsed("name", child.name()),
                Placeholder.unparsed("mother", mother == null ? "?" : mother.name()),
                Placeholder.unparsed("father", father == null ? "?" : father.name()));
            done++;
        }
        for (int i = 0; i < o.settlers(); i++) {
            final Location loc = this.homeSpawn(id);
            if (loc == null) {
                break;
            }
            final CountryState st = this.data().state(id);
            st.food = Math.max(0, st.food - cost);
            this.data().markDirty();
            final CitizenRecord rec = this.plugin.citizens().spawnOrQueue(id, loc, null);
            if (rec == null) {
                st.food += cost;
            } else {
                this.plugin.notifyCountry(id, "notify-settler", Placeholder.unparsed("name", rec.name()));
                done++;
            }
        }
        return done;
    }

    /** Web panel: adds (or removes) granary food, clamped to 0..capacity. */
    public boolean adjustFood(final UUID country, final long delta) {
        if (this.plugin.kraje().getCountry(country).isEmpty()) {
            return false;
        }
        final CountryState st = this.data().state(country);
        st.food = Math.max(0, Math.min(this.plugin.granary().capacity(country), st.food + delta));
        this.data().markDirty();
        return true;
    }

    /**
     * Web panel: adds settlers (adults, in houses, else at the capital; pending when the chunk is not loaded) or
     * removes them (loaded ones first). Returns the change actually made.
     */
    public int adjustSettlers(final UUID country, final int delta) {
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null || delta == 0) {
            return 0;
        }
        if (delta < 0) {
            return -this.plugin.citizens().removeSome(country, -delta);
        }
        int added = 0;
        for (int i = 0; i < Math.min(delta, 200); i++) {
            Location loc = this.homeSpawn(country);
            if (loc == null) {
                final World w = Bukkit.getWorld(k.capitalWorld());
                if (w == null) {
                    break;
                }
                final pl.medivalrpg.ludnosc.model.BlockPos c = this.plugin.settlement().center(k);
                loc = new Location(w, c.x() + 0.5, c.y() + 1, c.z() + 0.5);
            }
            if (this.plugin.citizens().spawnOrQueue(country, loc, null) != null) {
                added++;
            }
        }
        return added;
    }

    /** Spawn point inside a random housing building of the country (weighted by beds). */
    private Location homeSpawn(final UUID country) {
        final List<Building> homes = new ArrayList<>();
        int weight = 0;
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.housingBeds() > 0 && b.spawn() != null && Bukkit.getWorld(b.spawn().world()) != null) {
                homes.add(b);
                weight += b.housingBeds();
            }
        }
        if (homes.isEmpty()) {
            return null;
        }
        int roll = this.random.nextInt(weight);
        for (final Building b : homes) {
            roll -= b.housingBeds();
            if (roll < 0) {
                final BlockPos p = b.spawn();
                final World w = Bukkit.getWorld(p.world());
                return new Location(w, p.x() + 0.5, p.y(), p.z() + 0.5);
            }
        }
        return null;
    }

    /** Countries that the daily tick knows about (for info). */
    public Set<UUID> known() {
        return new LinkedHashSet<>(this.data().knownCountries());
    }
}
