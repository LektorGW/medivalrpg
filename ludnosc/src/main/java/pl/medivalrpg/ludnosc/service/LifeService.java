// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.config.LifeSettings;
import pl.medivalrpg.ludnosc.logic.DailyRoutine;
import pl.medivalrpg.ludnosc.logic.Family;
import pl.medivalrpg.ludnosc.logic.Housing;
import pl.medivalrpg.ludnosc.logic.HutLumber;
import pl.medivalrpg.ludnosc.logic.HutSiting;
import pl.medivalrpg.ludnosc.logic.JobBoard;
import pl.medivalrpg.ludnosc.logic.LifeGrowth;
import pl.medivalrpg.ludnosc.logic.Wages;
import pl.medivalrpg.ludnosc.logic.WorkerHire;
import pl.medivalrpg.ludnosc.logic.Workplaces;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CitizenLife;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.SiteFinder;
import pl.medivalrpg.ludnosc.planner.TerrainView;
import pl.medivalrpg.ludnosc.storage.LifeStore;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Everyday life of the citizens: a permanent home/bed and workplace for every citizen (also in unloaded chunks — pure
 * registry logic), wages and meals paid with real money between existing Skarbiec accounts, families (couples, children
 * who grow up and take over a parent's trade), growth that depends on food, beds and wealth, and huts the citizens
 * order themselves from their savings.
 *
 * <p>Physical behaviour only for loaded citizens near players, a few per tick under a time budget: the bed becomes the
 * villager's vanilla {@code HOME} memory, home and workplace are written into its PDC ({@code ludnosc:home},
 * {@code ludnosc:workplace} — read by the Zycie plugin for its day plan), and without Zycie the citizens walk home in
 * the evening. Main thread only, except the {@link #stats} map.</p>
 *
 * <p><b>Money</b> (closed economy, nothing is created): the savings of a country's citizens are the Skarbiec account
 * {@code ludnosc:obywatele_<kraj>} with a per-citizen share book; wages are a transfer from the country's treasury
 * (never more than it has), meals a transfer back to the treasury (never more than the payer's share), huts a payment
 * from the savings exactly like a country pays for a building. The book is reconciled with the real balance daily.</p>
 */
public final class LifeService implements Listener {

    /** Ticks between two runs. */
    public static final long PERIOD = 10L;
    /** {@code orderedBy} of huts the citizens order themselves. */
    public static final String HUT_ORDERER = "obywatele";

    /** Statistics of a country (for {@code /ludnosc obywatele}, the panel and Zycie). */
    public record Stats(int population, int adults, int children, int housed, int homeless, int employed, int unemployed,
                        int couples, long savings, long average, int unpaid, long time) {

        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private record BedCache(int beds, long at, List<Location> heads) {
    }

    private final LudnoscPlugin plugin;
    private final LifeStore store;
    private final LifeStore.Snapshot snap;
    private final NamespacedKey homeKey;
    private final NamespacedKey workKey;
    private final Random random = new Random();
    private final ArrayDeque<UUID> rotation = new ArrayDeque<>();
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();
    private final Map<UUID, String> hutReason = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hutChecked = new HashMap<>();
    private final Map<UUID, BedCache> beds = new HashMap<>();
    private final Map<UUID, Long> nextMove = new HashMap<>();
    private final Map<UUID, UUID[]> bredBy = new HashMap<>();
    private final Map<World, List<Location>> players = new HashMap<>();
    private FileConfiguration source;
    private LifeSettings cfg = LifeSettings.defaults();
    private long ticks;
    private long refilledAt;
    private int countryCursor;
    private int hutCursor;
    private boolean dirty;
    private int scansThisTick;
    private int movesThisTick;

    public LifeService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        this.store = new LifeStore(plugin.getDataFolder().toPath(), plugin.getLogger());
        this.snap = this.store.load();
        this.homeKey = new NamespacedKey(plugin, "home");
        this.workKey = new NamespacedKey(plugin, "workplace");
        plugin.getLogger().info("Życie obywateli: wczytano " + this.snap.citizens.size() + " zapisów domów/pracy/rodzin.");
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** Settings, re-read after {@code /ludnosc admin reload} (new config object). */
    public LifeSettings settings() {
        final FileConfiguration c = this.plugin.getConfig();
        if (c != this.source) {
            this.source = c;
            this.cfg = LifeSettings.from(c);
        }
        return this.cfg;
    }

    public boolean enabled() {
        return this.settings().enabled();
    }

    public boolean growthEnabled() {
        return this.settings().enabled() && this.settings().growth();
    }

    // ------------------------------------------------------------------ records

    /** Life of a citizen (null = not assigned yet). */
    public CitizenLife life(final UUID citizen) {
        return citizen == null ? null : this.snap.citizens.get(citizen);
    }

    private CitizenLife lifeOf(final CitizenRecord r) {
        CitizenLife l = this.snap.citizens.get(r.entityId());
        if (l == null) {
            l = CitizenLife.of(r.countryId());
            final UUID[] parents = this.bredBy.remove(r.entityId());
            if (parents != null) {
                l.mother = parents[0];
                l.father = parents[1];
                l.born = System.currentTimeMillis();
            }
            this.snap.citizens.put(r.entityId(), l);
            this.dirty = true;
        }
        return l;
    }

    /** A pending citizen became a real villager with a new entity id. */
    public void rekey(final UUID from, final UUID to) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        final CitizenLife l = this.snap.citizens.remove(from);
        if (l != null) {
            this.snap.citizens.put(to, l);
        }
        for (final CitizenLife o : this.snap.citizens.values()) {
            o.rekey(from, to);
        }
        this.dirty = true;
    }

    public LifeStore.CountryLife countryLife(final UUID country) {
        return this.snap.countries.computeIfAbsent(country, k -> {
            this.dirty = true;
            return new LifeStore.CountryLife();
        });
    }

    public Stats stats(final UUID country) {
        return this.stats.getOrDefault(country, Stats.EMPTY);
    }

    /** Why the citizens of a country do (not) build a hut now (Polish), null = not checked yet. */
    public String hutReason(final UUID country) {
        return this.hutReason.get(country);
    }

    /** For Zycie (reflection, plain Java types): {unpaid %, average savings, homeless, unemployed %}. */
    public int[] moodInputs(final UUID country) {
        final Stats s = this.stats(country);
        final int adults = Math.max(1, s.adults());
        return new int[] {s.unpaid() * 100 / adults, (int) Math.min(Integer.MAX_VALUE, s.average()), s.homeless(),
            s.unemployed() * 100 / adults};
    }

    // ------------------------------------------------------------------ tick

    /** Every {@link #PERIOD} ticks. */
    public void tick() {
        this.ticks++;
        final LifeSettings s = this.settings();
        if (!s.enabled()) {
            return;
        }
        final long now = System.currentTimeMillis();
        this.scansThisTick = 0;
        this.movesThisTick = 0;
        this.players.clear();
        if (this.ticks % Math.max(1, s.assignEverySeconds() * 2L) == 0) {
            this.assignNext();
        }
        if (this.ticks % 120 == 0) { // every minute
            this.prune();
            if (s.family()) {
                this.growUp(now);
            }
        }
        if (this.ticks % 60 == 5 && s.huts()) {
            this.hutsNext(now, s);
        }
        if (this.ticks % 20 == 7 && s.huts()) {
            this.fellNext(now, s);
        }
        if (this.ticks % 600 == 0) { // every 5 minutes
            this.saveIfDirty();
        }
        this.physical(now, s);
    }

    public void saveIfDirty() {
        if (this.dirty) {
            this.dirty = false;
            this.store.saveAsync(this.snap);
        }
    }

    public void shutdown() {
        this.store.saveAndShutdown(this.snap);
    }

    // ------------------------------------------------------------------ assignment (registry only)

    private void assignNext() {
        final List<KrajInfo> countries = new ArrayList<>(this.plugin.kraje().getCountries());
        if (countries.isEmpty()) {
            return;
        }
        countries.sort(Comparator.comparing(k -> k.id().toString()));
        this.countryCursor = Math.floorMod(this.countryCursor + 1, countries.size());
        final KrajInfo k = countries.get(this.countryCursor);
        if (this.plugin.royalRebuild() != null && this.plugin.royalRebuild().blocks(k.id())) {
            return;
        }
        this.assign(k.id());
    }

    /**
     * Citizens who moved to another country (conquest, reassignment) take their savings share with them: a transfer
     * between the two citizens' accounts, never more than the old account holds.
     */
    private void followSavings(final UUID country, final List<CitizenRecord> recs) {
        final SkarbiecApi eco = this.plugin.skarbiec();
        for (final CitizenRecord r : recs) {
            final CitizenLife l = this.lifeOf(r);
            if (l.country == null || l.country.equals(country)) {
                l.country = country;
                continue;
            }
            if (eco == null) {
                continue; // retried when Skarbiec is back
            }
            if (l.savings > 0) {
                final String from = Wages.account(l.country);
                final long amount = Math.min(l.savings, eco.balance(from));
                final boolean ok = amount > 0 && eco.transfer(from, Wages.account(country), amount,
                    "Ludnosc: oszczędności obywatela przechodzą z nim do kraju " + this.plugin.countryName(country)).ok();
                l.savings = ok ? amount : 0;
            }
            l.country = country;
            this.dirty = true;
        }
    }

    /** Homes, workplaces and statistics of one country (registry only; works for unloaded citizens). */
    public void assign(final UUID country) {
        final LifeSettings s = this.settings();
        final List<CitizenRecord> recs = this.data().citizensOf(country);
        this.followSavings(country, recs);
        final long nowAge = System.currentTimeMillis();
        for (final CitizenRecord r : recs) {
            this.ensureAge(this.lifeOf(r), s, nowAge); // once per citizen: lifespan + a safe age for old saves
        }
        final List<Family.Member> members = new ArrayList<>();
        for (final CitizenRecord r : recs) {
            final CitizenLife l = this.lifeOf(r);
            members.add(new Family.Member(r.entityId(), r.name(), r.adult(), l.partner, l.mother, l.father, r.world(), r.x(), r.z()));
        }
        final Map<UUID, UUID> households = Family.households(members);
        // homes
        final List<Housing.Home> homes = new ArrayList<>();
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.housingBeds() > 0) {
                homes.add(new Housing.Home(b.id(), b.housingBeds(), b.box().centerX(), b.box().centerZ()));
            }
        }
        final List<Housing.Resident> residents = new ArrayList<>();
        for (final CitizenRecord r : recs) {
            final CitizenLife l = this.lifeOf(r);
            residents.add(new Housing.Resident(r.entityId(), households.get(r.entityId()), l.home, l.bed, r.x(), r.z()));
        }
        final Housing.Result housing = Housing.assign(residents, homes);
        for (final CitizenRecord r : recs) {
            final CitizenLife l = this.lifeOf(r);
            final Housing.Slot slot = housing.slots().get(r.entityId());
            final UUID home = slot == null ? null : slot.home();
            final int bed = slot == null ? -1 : slot.bed();
            if (!java.util.Objects.equals(home, l.home) || bed != l.bed) {
                l.home = home;
                l.bed = bed;
                this.dirty = true;
            }
        }
        // work
        final JobBoard.Assignment board = this.plugin.work() == null ? JobBoard.Assignment.EMPTY : this.plugin.work().board(country);
        final Map<UUID, Workplaces.Place> boardPlaces = new HashMap<>();
        board.workplaceOf().forEach((citizen, place) -> {
            final Building b = this.data().building(place);
            final JobBoard.Job job = board.jobOf().get(citizen);
            if (b != null && job != null) {
                boardPlaces.put(citizen, new Workplaces.Place(b.id(), Workplaces.Kind.of(job), "", 0, b.box().world(),
                    b.box().centerX(), b.box().centerZ()));
            }
        });
        final List<Workplaces.Place> places = new ArrayList<>();
        final Set<String> trades = new HashSet<>();
        Workplaces.TRADES.values().forEach(trades::addAll);
        for (final Building b : this.data().buildingsOf(country)) {
            final String own = this.plugin.playerBuildings() == null ? null : this.plugin.playerBuildings().activeKind(b);
            final String base = own != null ? own : this.plugin.templates().baseType(b.type());
            if (base != null && trades.contains(base) && s.craftSlots() > 0) {
                places.add(new Workplaces.Place(b.id(), Workplaces.Kind.WARSZTAT, base, s.craftSlots(), b.box().world(),
                    b.box().centerX(), b.box().centerZ()));
            } else if (b.function() == BuildingFunction.GRANARY && s.storeHelpers() > 0) {
                places.add(new Workplaces.Place(b.id(), Workplaces.Kind.SPICHLERZ, base, s.storeHelpers(), b.box().world(),
                    b.box().centerX(), b.box().centerZ()));
            } else if (b.function() == BuildingFunction.WAREHOUSE && s.storeHelpers() > 0) {
                places.add(new Workplaces.Place(b.id(), Workplaces.Kind.MAGAZYN, base, s.storeHelpers(), b.box().world(),
                    b.box().centerX(), b.box().centerZ()));
            }
        }
        if (s.siteHelpers() > 0) {
            for (final ConstructionSite site : this.data().allSites()) {
                if (country.equals(site.countryId) && site.id != null) {
                    places.add(new Workplaces.Place(site.id, Workplaces.Kind.BUDOWA, site.type, s.siteHelpers(), site.world,
                        site.originX, site.originZ));
                }
            }
        }
        final List<Workplaces.Worker> workers = new ArrayList<>();
        for (final CitizenRecord r : recs) {
            if (r.adult()) {
                workers.add(new Workplaces.Worker(r.entityId(), r.profession(), r.world(), r.x(), r.z(), this.lifeOf(r).workplace));
            }
        }
        final Workplaces.Result work = Workplaces.assign(workers, boardPlaces, places);
        for (final CitizenRecord r : recs) {
            final CitizenLife l = this.lifeOf(r);
            final Workplaces.Place p = work.placeOf().get(r.entityId());
            final UUID id = p == null ? null : p.id();
            final String kind = p == null ? null : p.kind().key();
            if (!java.util.Objects.equals(id, l.workplace) || !java.util.Objects.equals(kind, l.work)) {
                l.workplace = id;
                l.work = kind;
                this.dirty = true;
            }
        }
        // statistics
        int adults = 0;
        int unpaid = 0;
        long book = 0;
        for (final CitizenRecord r : recs) {
            final CitizenLife l = this.lifeOf(r);
            if (r.adult()) {
                adults++;
                if (l.work != null && l.unpaidDays > 0) {
                    unpaid++;
                }
            }
            book += l.savings;
        }
        final SkarbiecApi eco = this.plugin.skarbiec();
        final long savings = eco == null ? book : eco.balance(Wages.account(country));
        this.stats.put(country, new Stats(recs.size(), adults, recs.size() - adults, housing.housed(), housing.homeless().size(),
            work.employed(), adults - work.employed(), Family.couples(members).size(), savings,
            Wages.average(savings, recs.size()), unpaid, System.currentTimeMillis()));
    }

    /**
     * Savings of a citizen who is gone (died, left, was conscripted): the partner inherits, else the children of the
     * same country (shares of the book), else the money goes back to the country's treasury by a transfer from the
     * citizens' account. Nothing is created or lost. The partner becomes a widow(er) and may marry again.
     */
    private void bequeath(final UUID id, final CitizenLife dead) {
        final Map<UUID, CitizenLife> all = this.snap.citizens;
        final List<CitizenLife> heirs = new ArrayList<>();
        final CitizenLife partner = dead.partner == null ? null : all.get(dead.partner);
        if (partner != null && java.util.Objects.equals(partner.country, dead.country)) {
            heirs.add(partner);
        } else {
            for (final CitizenLife o : all.values()) {
                if ((id.equals(o.mother) || id.equals(o.father)) && java.util.Objects.equals(o.country, dead.country)) {
                    heirs.add(o);
                }
            }
        }
        if (dead.savings > 0) {
            if (!heirs.isEmpty()) {
                final long each = dead.savings / heirs.size();
                long rest = dead.savings % heirs.size();
                for (final CitizenLife h : heirs) {
                    h.savings += each + (rest-- > 0 ? 1 : 0);
                }
            } else if (dead.country != null && this.plugin.kraje().getCountry(dead.country).isPresent()) {
                final SkarbiecApi eco = this.plugin.skarbiec();
                final String acc = Wages.account(dead.country);
                final long amount = eco == null ? 0 : Math.min(dead.savings, eco.balance(acc));
                if (amount > 0) {
                    eco.transfer(acc, SkarbiecApi.countryAccount(dead.country), amount,
                        "Ludnosc: oszczędności obywatela bez spadkobierców wracają do skarbca kraju");
                }
            }
            dead.savings = 0;
        }
        for (final CitizenLife o : all.values()) {
            if (id.equals(o.partner)) {
                o.partner = null;
            }
        }
        this.dirty = true;
    }

    // ------------------------------------------------------------------ hired by a company (Firmy, manufactories)

    /**
     * Adult citizens of a country without any workplace, computed fresh now (the homes/work assignment of that country
     * is refreshed first, registry only). Empty when the citizens' life is off — the caller then uses the job board.
     */
    public Set<UUID> unemployedAdults(final UUID country) {
        final Set<UUID> out = new HashSet<>();
        if (country == null || !this.enabled()) {
            return out;
        }
        this.assign(country);
        for (final CitizenRecord r : this.data().citizensOf(country)) {
            final CitizenLife l = this.snap.citizens.get(r.entityId());
            if (r.adult() && l != null && l.workplace == null && l.work == null) {
                out.add(r.entityId());
            }
        }
        return out;
    }

    /**
     * A citizen (already removed from the registry) left to work in a company's manufactory: the bed and the life entry
     * are freed at once (the country's homes are assigned again, so another citizen can take the bed), the partner is
     * free to marry again, and the savings share stays in the citizens' account — the partner or the children take it,
     * otherwise the daily reconciliation spreads it over everybody ({@link WorkerHire#shareOut}; no transfer, nothing is
     * created or lost). The worker comes back later through {@code returnCitizen} as a new citizen.
     */
    public void leftForWork(final UUID id, final UUID country) {
        final CitizenLife gone = id == null ? null : this.snap.citizens.remove(id);
        if (gone != null) {
            final Map<UUID, CitizenLife> all = this.snap.citizens;
            final List<UUID> heirs = new ArrayList<>();
            final CitizenLife partner = gone.partner == null ? null : all.get(gone.partner);
            if (partner != null && java.util.Objects.equals(partner.country, gone.country)) {
                heirs.add(gone.partner);
            } else {
                final List<UUID> children = new ArrayList<>();
                all.forEach((cid, o) -> {
                    if ((id.equals(o.mother) || id.equals(o.father)) && java.util.Objects.equals(o.country, gone.country)) {
                        children.add(cid);
                    }
                });
                children.sort(Comparator.comparing(UUID::toString));
                heirs.addAll(children);
            }
            WorkerHire.shareOut(gone.savings, heirs).forEach((heir, v) -> all.get(heir).savings += v);
            gone.savings = 0;
            for (final CitizenLife o : all.values()) {
                if (id.equals(o.partner)) {
                    o.partner = null;
                }
            }
            this.nextMove.remove(id);
            this.rotation.remove(id);
            this.dirty = true;
        }
        if (country != null && this.enabled()) {
            this.assign(country); // free bed and statistics right away
        }
    }

    /** Lives whose citizen is gone: {@link #bequeath}; orphaned accounts of disbanded countries go back to the world. */
    private void prune() {
        final Map<UUID, CitizenLife> all = this.snap.citizens;
        final List<UUID> gone = new ArrayList<>();
        for (final UUID id : all.keySet()) {
            if (this.data().citizen(id) == null) {
                gone.add(id);
            }
        }
        for (final UUID id : gone) {
            final CitizenLife dead = all.remove(id);
            if (dead != null) {
                this.bequeath(id, dead);
            }
        }
        if (this.bredBy.size() > 256) {
            this.bredBy.clear();
        }
        // a disbanded country: its citizens' savings go back to the world pool (Skarbiec: withdraw = transfer to
        // world:payments in the closed economy), never left in an orphaned account
        final SkarbiecApi eco = this.plugin.skarbiec();
        if (eco != null) {
            for (final UUID country : List.copyOf(this.snap.countries.keySet())) {
                if (this.plugin.kraje().getCountry(country).isPresent()) {
                    continue;
                }
                final long left = eco.balance(Wages.account(country));
                if (left <= 0 || eco.withdraw(Wages.account(country), left,
                    "Ludnosc: oszczędności obywateli rozwiązanego kraju wracają do Skarbu Świata").ok()) {
                    this.snap.countries.remove(country);
                    this.dirty = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------ daily money

    /** The world's households pool (Skarbiec {@code WorldMoney.HOUSEHOLDS}), source of the NPC tax since the migration. */
    public static final String HOUSEHOLDS = "world:households";

    /** The citizens' life pays the NPC tax (decision 2026-10-29): Ludnosc then must not deposit it itself. */
    public boolean collectsTax() {
        final LifeSettings s = this.settings();
        return s.enabled() && s.taxFromCitizens() && this.plugin.skarbiec() != null;
    }

    /**
     * Daily settlement of a country, right after the meals of {@link EconomyService#runDay} (only transfers between
     * existing accounts, each with a Polish reason):
     * <ol>
     *   <li>the share book is reconciled with the citizens' account;</li>
     *   <li>income from work: {@code world:households} (the pool the NPC tax always came from) → citizens' account,
     *       each citizen's tax share × {@code income.employed-factor} (unemployed: {@code unemployed-factor}) + meals;</li>
     *   <li>meals from the granary: citizens' account → treasury;</li>
     *   <li>NPC tax: citizens' account → treasury, each citizen at most its savings;</li>
     *   <li>wages: treasury → citizens' account, never more than the treasury has;</li>
     *   <li>marriages and old age.</li>
     * </ol>
     *
     * @param tax the country's NPC tax of the day ({@code Economy.day} × building bonus × mood)
     * @return tax actually collected (0 when the feature does not collect taxes)
     */
    public long daily(final KrajInfo k, final double fedRatio, final long tax) {
        final LifeSettings s = this.settings();
        final SkarbiecApi eco = this.plugin.skarbiec();
        if (!s.enabled() || eco == null) {
            return 0;
        }
        final UUID country = k.id();
        final String acc = Wages.account(country);
        final String treasury = SkarbiecApi.countryAccount(country);
        final List<CitizenRecord> recs = this.data().citizensOf(country);
        final LifeStore.CountryLife cl = this.countryLife(country);
        this.followSavings(country, recs);
        if (recs.isEmpty()) {
            final long left = eco.balance(acc);
            if (left > 0) {
                eco.transfer(acc, treasury, left, "Ludnosc: oszczędności obywateli bez spadkobierców wracają do skarbca kraju");
            }
            this.dirty = true;
            return 0;
        }
        // 1. reconcile the book with the real account
        final Map<UUID, Long> book0 = new LinkedHashMap<>();
        final List<UUID> ids = new ArrayList<>();
        for (final CitizenRecord r : recs) {
            ids.add(r.entityId());
            book0.put(r.entityId(), this.lifeOf(r).savings);
        }
        Map<UUID, Long> book = Wages.reconcile(book0, ids, eco.balance(acc));
        // who pays the meals of whom
        final Map<UUID, UUID> payerOf = new LinkedHashMap<>();
        final Map<UUID, Integer> dependents = new HashMap<>();
        final Set<UUID> present = new HashSet<>(ids);
        for (final CitizenRecord r : recs) {
            final CitizenLife l = this.lifeOf(r);
            UUID payer = r.entityId();
            if (!r.adult()) {
                payer = l.mother != null && present.contains(l.mother) ? l.mother
                    : l.father != null && present.contains(l.father) ? l.father : r.entityId();
            }
            payerOf.put(r.entityId(), payer);
            if (!payer.equals(r.entityId())) {
                dependents.merge(payer, 1, Integer::sum);
            }
        }
        // tax shares of the citizens (profession multipliers; children by the baby multiplier, 0 by default)
        final boolean collect = s.taxFromCitizens();
        final var rules = this.plugin.settings().rules;
        final Map<UUID, Long> weights = new LinkedHashMap<>();
        for (final CitizenRecord r : recs) {
            final double w = r.adult() ? rules.taxPerCitizen() * rules.multiplier(r.profession()) * 1000
                : rules.taxPerCitizen() * rules.babyMultiplier() * 1000;
            weights.put(r.entityId(), Math.max(0, Math.round(w)));
        }
        final Map<UUID, Long> shares = collect ? Wages.split(Math.max(0, tax), weights) : Map.of();
        // 2. income from work (households pool)
        long incomeTotal = 0;
        if (collect) {
            final Map<UUID, Long> income = new LinkedHashMap<>();
            final long meal = Math.round(s.mealPrice() * Math.max(0, Math.min(1, fedRatio)));
            for (final CitizenRecord r : recs) {
                if (!r.adult() && !payerOf.get(r.entityId()).equals(r.entityId())) {
                    continue; // a child's meals are part of its parent's income
                }
                final CitizenLife l = this.lifeOf(r);
                final double factor = l.work != null ? s.employedIncome() : s.unemployedIncome();
                final long v = Wages.income(shares.getOrDefault(r.entityId(), 0L), factor, meal,
                    dependents.getOrDefault(r.entityId(), 0));
                if (v > 0) {
                    income.put(r.entityId(), v);
                }
            }
            final Map<UUID, Long> got = Wages.pay(income, eco.balance(HOUSEHOLDS));
            incomeTotal = Wages.sum(got);
            if (incomeTotal > 0 && eco.transfer(HOUSEHOLDS, acc, incomeTotal,
                "Ludnosc: dochód obywateli NPC z pracy (" + got.size() + " osób)").ok()) {
                book = Wages.credit(book, got);
                got.forEach((id, v) -> this.snap.citizens.get(id).earned += v);
            } else {
                incomeTotal = 0;
            }
        }
        // 3. meals from the granary
        long mealsTotal = 0;
        if (s.mealPrice() > 0 && fedRatio > 0) {
            final Map<UUID, Long> meals = Wages.meals(book, payerOf, s.mealPrice(), fedRatio);
            mealsTotal = Wages.sum(meals);
            // decision 2026-10-29: the meals close the money circle — they go back to the households pool
            final boolean mealsHome = this.plugin.getConfig().getBoolean("life.meals.to-households", true);
            if (mealsTotal > 0 && eco.transfer(acc, mealsHome ? HOUSEHOLDS : treasury, mealsTotal,
                mealsHome ? "Ludnosc: obywatele NPC kupują żywność (posiłki) — zwrot do budżetu gospodarstw"
                    : "Ludnosc: obywatele NPC płacą za posiłki ze spichlerza").ok()) {
                book = Wages.minus(book, meals);
                meals.forEach((id, v) -> this.snap.citizens.get(id).spent += v);
            } else {
                mealsTotal = 0;
            }
        }
        // 4. NPC tax from the citizens' savings
        long taxPaid = 0;
        if (collect && tax > 0) {
            final Map<UUID, Long> paid = Wages.collect(book, shares);
            taxPaid = Wages.sum(paid);
            if (taxPaid > 0 && eco.transfer(acc, treasury, taxPaid,
                "Ludnosc: podatek obywateli NPC z ich dochodów (" + paid.size() + " podatników)").ok()) {
                book = Wages.minus(book, paid);
                paid.forEach((id, v) -> this.snap.citizens.get(id).spent += v);
            } else {
                taxPaid = 0;
            }
        }
        // 5. wages from the treasury
        long due = 0;
        long paidTotal = 0;
        int workers = 0;
        int unpaid = 0;
        if (s.wages()) {
            final Map<UUID, Long> owed = new LinkedHashMap<>();
            for (final CitizenRecord r : recs) {
                final CitizenLife l = this.lifeOf(r);
                final long w = r.adult() ? s.wageOf(Workplaces.Kind.byKey(l.work)) : 0;
                if (w > 0) {
                    owed.put(r.entityId(), w);
                }
            }
            due = Wages.sum(owed);
            workers = owed.size();
            Map<UUID, Long> paid = Wages.pay(owed, Math.max(0, eco.balance(treasury) - s.minTreasury()));
            paidTotal = Wages.sum(paid);
            if (paidTotal > 0 && !eco.transfer(treasury, acc, paidTotal,
                "Ludnosc: wynagrodzenie obywateli NPC za pracę (" + workers + " pracowników)").ok()) {
                paid = Map.of();
                paidTotal = 0;
            }
            book = Wages.credit(book, paid);
            for (final CitizenRecord r : recs) {
                final CitizenLife l = this.lifeOf(r);
                final long o = owed.getOrDefault(r.entityId(), 0L);
                final long p = paid.getOrDefault(r.entityId(), 0L);
                l.lastDue = o;
                l.lastWage = p;
                l.earned += p;
                if (o > 0 && p < o) {
                    l.unpaidDays++;
                    unpaid++;
                } else {
                    l.unpaidDays = 0;
                }
            }
        }
        // 6. market purchases: savings above a buffer are partly spent and return to the households pool (closed circle)
        long market = 0;
        final long buffer = Math.max(0, this.plugin.getConfig().getLong("life.market.buffer", 30));
        final double spend = Math.max(0, Math.min(1, this.plugin.getConfig().getDouble("life.market.share", 0.5)));
        if (this.plugin.getConfig().getBoolean("life.market.enabled", true) && spend > 0) {
            final Map<UUID, Long> buys = Wages.market(book, buffer, spend);
            market = Wages.sum(buys);
            if (market > 0 && eco.transfer(acc, HOUSEHOLDS, market,
                "Ludnosc: obywatele NPC robią zakupy na targu z oszczędności (" + buys.size() + " osób)").ok()) {
                book = Wages.minus(book, buys);
                buys.forEach((id, v) -> this.snap.citizens.get(id).spent += v);
            } else {
                market = 0;
            }
        }
        cl.lastMarket = market;
        for (final CitizenRecord r : recs) {
            this.lifeOf(r).savings = book.getOrDefault(r.entityId(), 0L);
        }
        cl.lastWagesDue = due;
        cl.lastWagesPaid = paidTotal;
        cl.lastMeals = mealsTotal;
        cl.lastIncome = incomeTotal;
        cl.lastTaxDue = collect ? tax : 0;
        cl.lastTaxPaid = taxPaid;
        cl.lastWorkers = workers;
        cl.lastUnpaid = unpaid;
        cl.lastDay = System.currentTimeMillis();
        if (unpaid > 0 && paidTotal < due) {
            this.tell(country, "unpaid", Placeholder.unparsed("n", Integer.toString(unpaid)));
        }
        // 6. family
        if (s.family()) {
            this.marriages(recs, s);
            if (s.oldAge()) {
                this.oldAge(recs, s);
            }
        }
        this.dirty = true;
        this.saveIfDirty();
        this.assign(country);
        return taxPaid;
    }

    private void marriages(final List<CitizenRecord> recs, final LifeSettings s) {
        final List<Family.Member> members = new ArrayList<>();
        for (final CitizenRecord r : recs) {
            final CitizenLife l = this.lifeOf(r);
            members.add(new Family.Member(r.entityId(), r.name(), r.adult(), l.partner, l.mother, l.father, r.world(), r.x(), r.z()));
        }
        for (final UUID[] pair : Family.marriages(members, s.marriageChance(), this.random)) {
            final CitizenLife a = this.snap.citizens.get(pair[0]);
            final CitizenLife b = this.snap.citizens.get(pair[1]);
            if (a != null && b != null) {
                a.partner = pair[1];
                b.partner = pair[0];
            }
        }
    }

    /** Draws the lifespan and, for citizens without a known birth, a safe random age (once; kept afterwards). */
    private void ensureAge(final CitizenLife l, final LifeSettings s, final long now) {
        if (l.lifespan <= 0) {
            l.lifespan = Family.lifespanDays(s.lifespanMinDays(), s.lifespanMaxDays(), this.random);
            this.dirty = true;
        }
        if (l.lifeStart <= 0) {
            l.lifeStart = l.born > 0 ? l.born : Family.initialLifeStart(now, l.lifespan, s.initialAgeMaxShare(), this.random);
            this.dirty = true;
        }
    }

    /** Age in real days. */
    public static long ageDays(final CitizenLife l, final long now) {
        return l == null || l.lifeStart <= 0 ? 0 : Math.max(0, (now - l.lifeStart) / 86_400_000L);
    }

    /**
     * Old age: past its lifespan a citizen dies with {@code chance-per-day}, at most {@code max-deaths-per-day} per
     * country (no waves of deaths). The bed is freed by the next assignment, an adult child takes over the workplace,
     * the savings go to the family or back to the treasury ({@link #bequeath}).
     */
    private void oldAge(final List<CitizenRecord> recs, final LifeSettings s) {
        final long now = System.currentTimeMillis();
        int deaths = 0;
        final List<CitizenRecord> order = new ArrayList<>(recs);
        order.sort(Comparator.comparingLong(r -> {
            final CitizenLife l = this.snap.citizens.get(r.entityId());
            return l == null || l.lifeStart <= 0 ? Long.MAX_VALUE : l.lifeStart;
        }));
        for (final CitizenRecord r : order) {
            if (deaths >= s.maxDeathsPerDay()) {
                break;
            }
            final CitizenLife l = this.lifeOf(r);
            this.ensureAge(l, s, now);
            if (!r.adult() || !Family.diesOfOldAge(ageDays(l, now), l.lifespan, s.oldAgeChance(), this.random)) {
                continue;
            }
            deaths++;
            // an adult child (a helper or unemployed) takes over the workplace
            String heirName = null;
            if (l.workplace != null) {
                for (final CitizenRecord c : recs) {
                    final CitizenLife cl = this.snap.citizens.get(c.entityId());
                    if (c.adult() && cl != null && (r.entityId().equals(cl.mother) || r.entityId().equals(cl.father))
                        && (cl.work == null || "budowa".equals(cl.work) || "spichlerz".equals(cl.work) || "magazyn".equals(cl.work))) {
                        cl.workplace = l.workplace;
                        cl.work = l.work;
                        heirName = c.name();
                        break;
                    }
                }
            }
            this.snap.citizens.remove(r.entityId());
            this.bequeath(r.entityId(), l);
            this.plugin.citizens().depart(r);
            this.tell(r.countryId(), "old-age", Placeholder.unparsed("name", r.name()),
                Placeholder.unparsed("age", Long.toString(ageDays(l, now))),
                Placeholder.unparsed("heir", heirName == null ? "" : " Pracę przejmuje " + heirName + "."));
        }
    }

    // ------------------------------------------------------------------ growth & children

    /** Couples of a country that may have a child now: [mother, father]. */
    public List<UUID[]> eligibleCouples(final UUID country) {
        final LifeSettings s = this.settings();
        final long now = System.currentTimeMillis();
        final List<Family.Member> members = new ArrayList<>();
        for (final CitizenRecord r : this.data().citizensOf(country)) {
            final CitizenLife l = this.lifeOf(r);
            members.add(new Family.Member(r.entityId(), r.name(), r.adult(), l.partner, l.mother, l.father, r.world(), r.x(), r.z()));
        }
        final List<UUID[]> out = new ArrayList<>();
        for (final UUID[] c : Family.couples(members)) {
            final CitizenLife a = this.snap.citizens.get(c[0]);
            final CitizenLife b = this.snap.citizens.get(c[1]);
            if (a == null || b == null || a.children >= s.maxChildren()) {
                continue;
            }
            final long last = Math.max(a.lastChild, b.lastChild);
            if (now - last >= s.birthCooldownHours() * 3_600_000L) {
                out.add(c);
            }
        }
        return out;
    }

    /** Growth input of a country for {@link LifeGrowth}. */
    public LifeGrowth.Input growthInput(final UUID country, final long food, final double mood, final double bonus,
                                        final int couples) {
        final int pop = this.data().population(country);
        final SkarbiecApi eco = this.plugin.skarbiec();
        final long savings = eco == null ? 0 : eco.balance(Wages.account(country));
        return new LifeGrowth.Input(pop, this.plugin.citizens().pending(country), this.data().housing(country), food,
            this.plugin.settings().foodPerSettler, this.plugin.settings().rules.foodPerCitizen(), Wages.average(savings, pop), couples,
            mood, bonus);
    }

    /** A child was born to a couple: links and counters. */
    public void born(final CitizenRecord child, final UUID mother, final UUID father) {
        final CitizenLife l = this.lifeOf(child);
        l.mother = mother;
        l.father = father;
        l.born = System.currentTimeMillis();
        l.lifeStart = l.born;
        for (final UUID p : new UUID[] {mother, father}) {
            final CitizenLife pl = p == null ? null : this.snap.citizens.get(p);
            if (pl != null) {
                pl.children++;
                pl.lastChild = l.born;
            }
        }
        this.dirty = true;
    }

    /** Where a child of this mother is born: her home's spawn (null = anywhere in a house). */
    public Location birthPlace(final UUID mother) {
        final CitizenLife l = this.life(mother);
        final Building b = l == null || l.home == null ? null : this.data().building(l.home);
        if (b == null || b.spawn() == null) {
            return null;
        }
        final World w = Bukkit.getWorld(b.spawn().world());
        return w == null ? null : new Location(w, b.spawn().x() + 0.5, b.spawn().y(), b.spawn().z() + 0.5);
    }

    /** Children old enough grow up and take over a parent's profession (also in unloaded chunks). */
    private void growUp(final long now) {
        final long childhood = this.settings().childhoodHours() * 3_600_000L;
        for (final Map.Entry<UUID, CitizenLife> e : List.copyOf(this.snap.citizens.entrySet())) {
            final CitizenLife l = e.getValue();
            if (l.born <= 0 || !Family.grownUp(l.born, now, childhood)) {
                continue;
            }
            final CitizenRecord r = this.data().citizen(e.getKey());
            if (r == null || r.adult()) {
                continue;
            }
            final CitizenRecord father = l.father == null ? null : this.data().citizen(l.father);
            final CitizenRecord mother = l.mother == null ? null : this.data().citizen(l.mother);
            final String profession = Family.inheritedProfession(father == null ? null : father.profession(),
                mother == null ? null : mother.profession());
            final CitizenRecord grown = r.withState(r.world(), r.x(), r.y(), r.z(), profession, true);
            this.data().putCitizen(grown);
            final Entity ent = Bukkit.getEntity(r.entityId());
            if (ent instanceof Villager v && v.isValid()) {
                this.plugin.citizens().growUp(v, profession);
            }
            this.tell(r.countryId(), "grown-up", Placeholder.unparsed("name", r.name()));
            this.dirty = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(final EntityBreedEvent e) {
        if (!(e.getEntity() instanceof Villager child)) {
            return;
        }
        final UUID mother = e.getMother() == null ? null : e.getMother().getUniqueId();
        final UUID father = e.getFather() == null ? null : e.getFather().getUniqueId();
        if (mother != null && this.data().citizen(mother) != null || father != null && this.data().citizen(father) != null) {
            this.bredBy.put(child.getUniqueId(), new UUID[] {mother, father});
        }
    }

    // ------------------------------------------------------------------ huts

    private void hutsNext(final long now, final LifeSettings s) {
        final List<KrajInfo> countries = new ArrayList<>(this.plugin.kraje().getCountries());
        if (countries.isEmpty()) {
            return;
        }
        countries.sort(Comparator.comparing(k -> k.id().toString()));
        for (int i = 0; i < countries.size(); i++) {
            this.hutCursor = Math.floorMod(this.hutCursor + 1, countries.size());
            final KrajInfo k = countries.get(this.hutCursor);
            if (now - this.hutChecked.getOrDefault(k.id(), 0L) >= s.hutCheckMinutes() * 60_000L) {
                this.hutChecked.put(k.id(), now);
                if (this.plugin.royalRebuild() == null || !this.plugin.royalRebuild().blocks(k.id())) {
                    try {
                        this.checkHuts(k, s);
                    } catch (final RuntimeException ex) {
                        this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Chata obywateli: " + k.name(), ex);
                    }
                }
                return;
            }
        }
    }

    /** Self-built huts enabled for the country (config + country setting). */
    public boolean hutsEnabled(final UUID country) {
        final LifeStore.CountryLife cl = this.snap.countries.get(country);
        return this.plugin.automaticBuildingAllowed(country) && this.settings().huts() && (cl == null || cl.huts == null || cl.huts);
    }

    public int hutLimit(final UUID country) {
        final LifeStore.CountryLife cl = this.snap.countries.get(country);
        return cl == null || cl.hutLimit == null ? this.settings().hutLimit() : cl.hutLimit;
    }

    public void setHuts(final UUID country, final Boolean enabled, final Integer limit) {
        final LifeStore.CountryLife cl = this.countryLife(country);
        if (enabled != null) {
            cl.huts = enabled;
        }
        if (limit != null) {
            cl.hutLimit = Math.max(0, Math.min(20, limit));
        }
        this.hutChecked.remove(country);
        this.dirty = true;
        this.saveIfDirty();
    }

    /** Huts the citizens ordered that are still queued or being built. */
    public int hutsInProgress(final UUID country) {
        int n = 0;
        for (final ConstructionSite site : this.data().allSites()) {
            if (country.equals(site.countryId) && HUT_ORDERER.equals(site.orderedBy)) {
                n++;
            }
        }
        for (final QueuedOrder o : this.data().allQueued()) {
            if (country.equals(o.countryId) && HUT_ORDERER.equals(o.orderedBy)) {
                n++;
            }
        }
        return n;
    }

    /** Hut cost paid from the savings (template cost × share). */
    public long hutCost() {
        final BuildingTemplate t = this.plugin.templates().get(this.settings().hutType());
        return t == null ? -1 : Math.round(t.meta().cost() * this.settings().hutCostShare());
    }

    private void checkHuts(final KrajInfo k, final LifeSettings s) {
        final UUID country = k.id();
        if (!this.plugin.automaticBuildingAllowed(country)) { this.hutReason.put(country, "W kraju graczy chaty zleca władca lub doradca."); return; }
        if (!pl.medivalrpg.ludnosc.logic.PlayerCountryRules.hutDue(System.currentTimeMillis(), this.countryLife(country).lastHut,
            this.plugin.getConfig().getInt("courtesy.huts-per-day", 1))) { this.hutReason.put(country, "Dzienny limit nowych chat — obywatele czekają."); return; }
        final SkarbiecApi eco = this.plugin.skarbiec();
        final BuildingTemplate t = this.plugin.templates().get(s.hutType());
        if (eco == null || t == null) {
            this.hutReason.put(country, t == null ? "brak szablonu " + s.hutType() : "brak Skarbca");
            return;
        }
        final int inProgress = this.hutsInProgress(country);
        final int bedsPerHut = Math.max(1, t.meta().declaredBeds());
        final int homeless = this.data().population(country) - this.data().housing(country) - inProgress * bedsPerHut;
        final long cost = Math.round(t.meta().cost() * s.hutCostShare());
        final String acc = Wages.account(country);
        final boolean queueFree = this.plugin.construction().admission(country) != pl.medivalrpg.ludnosc.logic.QueueRules.Admission.FULL;
        final HutSiting.Decision d = HutSiting.decide(this.hutsEnabled(country), homeless, inProgress, this.hutLimit(country),
            eco.balance(acc), cost, queueFree);
        if (d != HutSiting.Decision.ORDER) {
            this.hutReason.put(country, d.title());
            return;
        }
        final World w = Bukkit.getWorld(k.capitalWorld());
        if (w == null) {
            this.hutReason.put(country, "brak świata stolicy");
            return;
        }
        final BlockPos c = this.plugin.settlement().center(k);
        this.plugin.terrain().requestAround(country, c.world(), c.x() >> 4, c.z() >> 4, this.plugin.settings().plannerRadius / 16 + 1);
        final TerrainView view = this.plugin.settlement().zoned(this.plugin.terrain().view(c.world(), country), country, t.meta());
        final CountryState st = this.data().state(country);
        final Set<Long> streets = new LinkedHashSet<>();
        if (st.planActive && st.planRoads != null) {
            streets.addAll(st.planRoads);
        }
        if (st.designRoadsLeft != null) {
            streets.addAll(st.designRoadsLeft);
        }
        final Set<Long> roads = new LinkedHashSet<>(st.roads());
        final List<BlockBox> taken = this.plugin.settlement().taken(c.world());
        final Set<Long> protectedRoads = new LinkedHashSet<>(roads);
        protectedRoads.addAll(streets);
        if (st.planSquare != null) {
            final int[] sq = st.planSquare;
            for (int x=sq[0]; x<=sq[2]; x++) for (int z=sq[1]; z<=sq[3]; z++) protectedRoads.add(RoadPlanner.key(x,z));
        }
        final TerrainView safeView = HutSiting.courteous(view, protectedRoads,
            Math.max(2, Math.min(16, this.plugin.getConfig().getInt("courtesy.player-building-gap", 6))));
        // Prefer the inner part of the existing town before searching the full radius.
        final List<int[]> planAreas = new ArrayList<>();
        if (st.planLevels != null) planAreas.addAll(st.planLevels);
        if (st.planFill != null) planAreas.addAll(st.planFill);
        final SiteFinder.Search planned = this.plugin.getConfig().getBoolean("courtesy.prefer-town-plan", true) && !planAreas.isEmpty()
            ? HutSiting.find(HutSiting.inPlan(safeView,planAreas), new int[] {t.width(),t.depth()}, c.x(),c.z(),t.meta().zone(),
                taken,roads,streets,new SiteFinder.Settings(this.plugin.getConfig().getInt("courtesy.hut-radius",64),3,
                    this.plugin.getConfig().getInt("courtesy.building-spacing",8),this.plugin.getConfig().getInt("courtesy.hut-max-slope",2)),t.meta().groundLayer()) : null;
        final SiteFinder.Search preferred = planned != null && planned.spot() != null ? planned : HutSiting.find(safeView, new int[] {t.width(), t.depth()}, c.x(), c.z(), t.meta().zone(),
            taken, roads, streets, new SiteFinder.Settings(Math.min(32, this.plugin.getConfig().getInt("courtesy.hut-radius",64)), 3,
                this.plugin.getConfig().getInt("courtesy.building-spacing",8), this.plugin.getConfig().getInt("courtesy.hut-max-slope",2)), t.meta().groundLayer());
        final SiteFinder.Search search = preferred.spot() != null ? preferred : HutSiting.find(safeView, new int[] {t.width(), t.depth()}, c.x(), c.z(), t.meta().zone(),
            taken, roads, streets, new SiteFinder.Settings(this.plugin.getConfig().getInt("courtesy.hut-radius", 64), 3,
                this.plugin.getConfig().getInt("courtesy.building-spacing", 8), this.plugin.getConfig().getInt("courtesy.hut-max-slope", 2)), t.meta().groundLayer());
        final SiteFinder.Spot spot = search.spot();
        if (spot == null) {
            this.hutReason.put(country, "brak wolnego miejsca: " + search.stats().reason(t.width(), t.depth()));
            return;
        }
        final String hutType = this.plugin.construction().variantFor(country, t.id(), w, spot.x(), spot.y(), spot.z(), spot.rotation());
        final var check = this.plugin.construction().check(country, hutType, w, spot.x(), spot.y(), spot.z(), spot.rotation(), null);
        if (check != pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult.OK) {
            final String why = this.plugin.construction().lastCheckReason(country);
            this.hutReason.put(country, "miejsce odrzucone: " + (why == null ? check.name() : why));
            return;
        }
        final List<BlockBox> obstacles = new ArrayList<>(taken);
        obstacles.add(pl.medivalrpg.ludnosc.planner.TownPlanner.footprint(c.world(), new int[] {t.width(), t.depth()}, spot));
        final List<int[]> path = RoadPlanner.path(view, spot.doorX(), spot.doorZ(), roads, c.x(), c.z(), obstacles, 6000);
        final List<BlockPos> road = this.plugin.settlement().roadCells(w.getName(), country, path, spot.y());
        if (cost > 0 && !eco.withdraw(acc, cost, "Ludnosc: obywatele opłacają budowę chaty z własnych oszczędności").ok()) {
            this.hutReason.put(country, HutSiting.Decision.NO_MONEY.title());
            return;
        }
        ConstructionService.OrderOutcome out;
        try {
            out = this.plugin.construction().orderDetailed(country, hutType, w, spot.x(), spot.y(),
                spot.z(), spot.rotation(), true, HUT_ORDERER, road);
        } catch (final RuntimeException e) {
            // the savings were already taken: an exception must not keep them without a hut
            this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Chata obywateli: zamówienie przerwane wyjątkiem", e);
            out = null;
        }
        if (out == null || out.result() != pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult.OK) {
            if (cost > 0 && !eco.deposit(acc, cost, "Ludnosc: zwrot oszczędności obywateli — chata nie powstała").ok()) {
                this.plugin.getLogger().severe("Chata obywateli: NIE zwrócono " + cost + " md na " + acc + " (sprawdź world:payments)");
            }
            this.hutReason.put(country, "zamówienie odrzucone: " + (out == null ? "błąd" : out.result().name()));
            return;
        }
        // the cost is shared by all savers proportionally
        final List<CitizenRecord> recs = this.data().citizensOf(country);
        final Map<UUID, Long> book = new LinkedHashMap<>();
        final List<UUID> ids = new ArrayList<>();
        for (final CitizenRecord r : recs) {
            ids.add(r.entityId());
            book.put(r.entityId(), this.lifeOf(r).savings);
        }
        final Map<UUID, Long> reconciled = Wages.reconcile(book, ids, eco.balance(acc) + cost);
        final Map<UUID, Long> after = Wages.debit(reconciled, cost);
        if (after != null) {
            after.forEach((id, v) -> this.snap.citizens.get(id).savings = v);
        }
        final LifeStore.CountryLife cl = this.countryLife(country);
        cl.hutsOrdered++;
        cl.lastHut = System.currentTimeMillis();
        this.dirty = true;
        this.hutReason.put(country, "zlecono chatę" + (out.queued() ? " (kolejka, poz. " + out.position() + ")" : ""));
        this.tell(country, "hut", Placeholder.unparsed("cost", this.plugin.formatMoney(cost)),
            Placeholder.unparsed("x", Integer.toString(spot.x())), Placeholder.unparsed("z", Integer.toString(spot.z())));
        this.saveIfDirty();
    }

    // ------------------------------------------------------------------ wood for the citizens' huts

    /** Country → earliest next felling (epoch millis); trees felled per site are kept in {@code ConstructionSite.felledTrees}. */
    private final Map<UUID, Long> fellAt = new HashMap<>();
    private final Map<UUID, String> fellReason = new ConcurrentHashMap<>();
    private java.lang.reflect.Method protectedAreas;
    private java.lang.reflect.Method isProtected;

    /** Last result of felling for the country's huts (Polish), null = none yet. */
    public String fellReason(final UUID country) {
        return this.fellReason.get(country);
    }

    /**
     * One tree at most per call (and per country every {@code huts.felling.interval-seconds}): a citizens' hut site
     * that still wants wood gets the real drops of one natural tree nearby, put into the site chest or the country's
     * registered store. Refused (tree stays) when the chest is full.
     */
    private void fellNext(final long now, final LifeSettings s) {
        if (!s.felling() || this.plugin.construction() == null) {
            return;
        }
        for (final ConstructionService.MaterialDemand d : this.plugin.construction().materialDemands()) {
            if (!pl.medivalrpg.ludnosc.logic.MaterialNeeds.WOOD.equals(d.group()) || now < this.fellAt.getOrDefault(d.country(), 0L)) {
                continue;
            }
            final ConstructionSite site = this.data().site(d.site());
            if (site == null || !this.plugin.automaticBuildingAllowed(site.countryId) || !HUT_ORDERER.equals(site.orderedBy) || site.felledTrees >= s.treesPerHut()) {
                continue;
            }
            this.fellAt.put(d.country(), now + s.fellingSeconds() * 1000L);
            final String refused = this.fellFor(site, s);
            this.fellReason.put(d.country(), refused == null ? "ścięto drzewo na chatę" : refused);
            if (refused == null) {
                site.felledTrees++;
                this.data().markDirty();
            }
            return;
        }
    }

    /** Chunk protected by Kraje (spawn and admin areas); unknown = protected (never fell when unsure). */
    private boolean protectedChunk(final String world, final int cx, final int cz) {
        try {
            final org.bukkit.plugin.Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
            if (kraje == null) {
                return true;
            }
            if (this.protectedAreas == null) {
                this.protectedAreas = kraje.getClass().getMethod("protectedAreas");
            }
            final Object areas = this.protectedAreas.invoke(kraje);
            if (this.isProtected == null) {
                this.isProtected = areas.getClass().getMethod("isProtected", String.class, int.class, int.class);
            }
            return Boolean.TRUE.equals(this.isProtected.invoke(areas, world, cx, cz));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return true;
        }
    }

    private static boolean inside(final List<BlockBox> boxes, final int x, final int z, final int margin) {
        for (final BlockBox b : boxes) {
            if (x >= b.minX() - margin && x <= b.maxX() + margin && z >= b.minZ() - margin && z <= b.maxZ() + margin) {
                return true;
            }
        }
        return false;
    }

    /** Fells one tree for the site; null on success, else the Polish reason. */
    private String fellFor(final ConstructionSite site, final LifeSettings s) {
        final World w = Bukkit.getWorld(site.world);
        if (w == null || !w.isChunkLoaded(site.originX >> 4, site.originZ >> 4)) {
            return "plac chaty nie jest wczytany";
        }
        final UUID country = site.countryId;
        org.bukkit.inventory.Inventory target = this.plugin.construction().siteChest(site);
        if (target == null && this.plugin.production() != null) {
            final var views = this.plugin.production().views(country);
            target = views.isEmpty() ? null : views.get(0).inventory();
        }
        if (target == null) {
            return "brak skrzyni placu i magazynu kraju";
        }
        final int r = s.fellingRadius();
        final TerrainView view = this.plugin.terrain().view(w.getName(), country);
        this.plugin.terrain().requestAround(country, w.getName(), site.originX >> 4, site.originZ >> 4, r / 16 + 1);
        final CountryState st = this.data().state(country);
        final List<BlockBox> near = this.plugin.construction().takenNear(w.getName(), site.originX, site.originZ, r + 24, 1024);
        final Location spawn = w.getSpawnLocation();
        final List<int[]> candidates = new ArrayList<>();
        for (int x = site.originX - r; x <= site.originX + r; x += 2) {
            for (int z = site.originZ - r; z <= site.originZ + r; z += 2) {
                if (view.tree(x, z) && w.isChunkLoaded(x >> 4, z >> 4)) {
                    candidates.add(new int[] {x, z});
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(c -> Math.pow(c[0] - site.originX, 2) + Math.pow(c[1] - site.originZ, 2)));
        String last = "brak naturalnego drzewa w promieniu " + r + " bloków";
        int tries = 0;
        for (final int[] c : candidates) {
            if (++tries > 16) {
                break;
            }
            boolean road = false;
            for (int dx = -3; dx <= 3 && !road; dx++) {
                for (int dz = -3; dz <= 3 && !road; dz++) {
                    road = st.roads().contains(RoadPlanner.key(c[0] + dx, c[1] + dz));
                }
            }
            final boolean own = view.own(c[0], c[1]) && this.plugin.kraje().getCountryAt(w.getName(), c[0] >> 4, c[1] >> 4)
                .map(k -> k.id().equals(country)).orElse(false);
            final String refuse = HutLumber.refuse(own, this.plugin.design().untouchable(country, c[0], c[1]),
                this.protectedChunk(w.getName(), c[0] >> 4, c[1] >> 4),
                spawn.getWorld() == w ? Math.hypot(c[0] - spawn.getX(), c[1] - spawn.getZ()) : Double.MAX_VALUE, s.spawnRadius(),
                road || inside(near, c[0], c[1], 4));
            if (refuse != null) {
                last = refuse;
                continue;
            }
            int y = w.getHighestBlockYAt(c[0], c[1]);
            for (int k = 0; k < 24 && y > w.getMinHeight() && !Tag.LOGS.isTagged(w.getBlockAt(c[0], y, c[1]).getType()); k++) {
                y--;
            }
            if (!Tag.LOGS.isTagged(w.getBlockAt(c[0], y, c[1]).getType())) {
                continue;
            }
            final pl.medivalrpg.ludnosc.planner.TreeFeller.Plan plan = pl.medivalrpg.ludnosc.planner.TreeFeller.plan((x, yy, z) -> {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    return pl.medivalrpg.ludnosc.planner.TreeFeller.OTHER;
                }
                final Block blk = w.getBlockAt(x, yy, z);
                if (Tag.LOGS.isTagged(blk.getType()) && !blk.getType().name().startsWith("STRIPPED_")) {
                    return pl.medivalrpg.ludnosc.planner.TreeFeller.LOG;
                }
                if (Tag.LEAVES.isTagged(blk.getType()) && blk.getBlockData() instanceof org.bukkit.block.data.type.Leaves l && !l.isPersistent()) {
                    return pl.medivalrpg.ludnosc.planner.TreeFeller.LEAF;
                }
                return pl.medivalrpg.ludnosc.planner.TreeFeller.OTHER;
            }, (x, z) -> view.own(x, z) && !inside(near, x, z, 0) && !this.plugin.design().untouchable(country, x, z),
                c[0], y, c[1], s.maxLogs());
            if (plan == null || plan.logs() == 0) {
                continue;
            }
            final int protectionGap = Math.max(2, Math.min(16, this.plugin.getConfig().getInt("courtesy.player-building-gap", 6)));
            final Set<Long> protectedStreets = new LinkedHashSet<>(st.roads());
            if (st.planRoads != null) protectedStreets.addAll(st.planRoads);
            final var design = this.plugin.design().plan(country);
            if (design != null) protectedStreets.addAll(design.roadCells());
            boolean nearStreet = false;
            for (final int[] block : plan.remove()) {
                for (int dx=-protectionGap; dx<=protectionGap; dx++) for (int dz=-protectionGap; dz<=protectionGap; dz++) {
                    final int xx=block[0]+dx, zz=block[2]+dz;
                    if (protectedStreets.contains(RoadPlanner.key(xx,zz)) || this.plugin.design().untouchable(country,xx,zz)
                        || st.planSquare != null && xx>=st.planSquare[0] && zz>=st.planSquare[1] && xx<=st.planSquare[2] && zz<=st.planSquare[3]) nearStreet=true;
                }
            }
            if (nearStreet) { last="drzewo przy drodze, placu lub strefie chronionej"; continue; }
            if (this.builtNear(w, plan)) {
                last = "przy drzewie są bloki budowli";
                continue;
            }
            // real drops of every block of the tree
            final List<org.bukkit.inventory.ItemStack> drops = new ArrayList<>();
            final org.bukkit.inventory.ItemStack axe = new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_AXE);
            for (final int[] p : plan.remove()) {
                for (final org.bukkit.inventory.ItemStack it : w.getBlockAt(p[0], p[1], p[2]).getDrops(axe)) {
                    if (it != null && !it.getType().isAir() && it.getAmount() > 0) {
                        drops.add(it.clone());
                    }
                }
            }
            final int[] base = plan.sapling();
            final String logId = w.getBlockAt(base[0], base[1], base[2]).getType().getKey().toString();
            final org.bukkit.Material sapling = org.bukkit.Material.matchMaterial(
                pl.medivalrpg.ludnosc.planner.TreeFeller.sapling(logId));
            boolean replant = false;
            for (final org.bukkit.inventory.ItemStack it : drops) {
                if (sapling != null && it.getType() == sapling && it.getAmount() > 0) {
                    it.setAmount(it.getAmount() - 1); // the sapling planted back comes from the tree's own drops
                    replant = true;
                    break;
                }
            }
            drops.removeIf(it -> it.getAmount() <= 0);
            final List<HutLumber.Slot> slots = new ArrayList<>();
            int i = 0;
            for (final org.bukkit.inventory.ItemStack it : target.getStorageContents()) {
                i++;
                slots.add(it == null || it.getType().isAir() ? new HutLumber.Slot(null, 0, 0)
                    : new HutLumber.Slot(it.hasItemMeta() ? "#" + i : it.getType().name(), it.getAmount(), it.getMaxStackSize()));
            }
            final List<HutLumber.Item> items = new ArrayList<>();
            for (final org.bukkit.inventory.ItemStack it : drops) {
                items.add(new HutLumber.Item(it.getType().name(), it.getAmount(), it.getMaxStackSize()));
            }
            if (!HutLumber.fits(slots, items)) {
                return "skrzynia pełna — drzewo zostaje";
            }
            final var left = target.addItem(drops.toArray(new org.bukkit.inventory.ItemStack[0]));
            if (!left.isEmpty()) {
                this.plugin.getLogger().warning("Chata obywateli: nie zmieściło się " + left.size() + " stosów mimo sprawdzenia.");
            }
            for (final int[] p : plan.remove()) {
                w.getBlockAt(p[0], p[1], p[2]).setType(org.bukkit.Material.AIR, false);
            }
            final Block at = w.getBlockAt(base[0], base[1], base[2]);
            // like the sawmill's lumberjacks the citizens always plant a sapling back (one dropped sapling is used for it
            // when the tree gave one); the forest regrows instead of turning into a clearing
            final org.bukkit.Material ground = at.getRelative(0, -1, 0).getType();
            if (sapling != null && at.getType().isAir() && (Tag.DIRT.isTagged(ground) || ground == org.bukkit.Material.GRASS_BLOCK)) {
                at.setType(sapling, false);
            } else {
                this.plugin.getLogger().info("Chata obywateli: sadzonka nieposadzona przy " + at.getX() + " " + at.getY() + " " + at.getZ()
                    + " (sadzonka " + sapling + ", miejsce " + at.getType() + ", pod spodem " + at.getRelative(0, -1, 0).getType() + ")");
            }
            if (this.plugin.settings().effects) {
                w.playSound(at.getLocation(), org.bukkit.Sound.BLOCK_WOOD_BREAK, 0.8f, 0.9f);
                w.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, at.getLocation().add(0.5, 1, 0.5), 6, 0.4, 0.6, 0.4, 0);
            }
            this.showWoodcutter(country, at.getLocation());
            return null;
        }
        return last;
    }

    /** No player / builder block within 2 blocks of the tree (a log house, a garden tree, a decoration). */
    private boolean builtNear(final World w, final pl.medivalrpg.ludnosc.planner.TreeFeller.Plan plan) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final int[] p : plan.remove()) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
            maxZ = Math.max(maxZ, p[2]);
        }
        final int gap = Math.max(2, Math.min(16, this.plugin.getConfig().getInt("courtesy.player-building-gap", 6)));
        for (int x = minX - gap; x <= maxX + gap; x++) {
            for (int z = minZ - gap; z <= maxZ + gap; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    return true;
                }
                for (int y = minY - 1; y <= maxY + 1; y++) {
                    if (HutLumber.built(w.getBlockAt(x, y, z).getType().name())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The nearest loaded adult citizen walks to the tree and swings (looks like the citizens cut it). */
    private void showWoodcutter(final UUID country, final Location tree) {
        Villager best = null;
        double bestD = 48 * 48;
        for (final CitizenRecord r : this.data().citizensOf(country)) {
            if (!r.adult() || !r.world().equals(tree.getWorld().getName())) {
                continue;
            }
            final Entity e = Bukkit.getEntity(r.entityId());
            if (e instanceof Villager v && v.isValid() && !v.isSleeping() && !v.isTrading()) {
                final double d = v.getLocation().distanceSquared(tree);
                if (d < bestD) {
                    bestD = d;
                    best = v;
                }
            }
        }
        if (best != null) {
            best.getPathfinder().moveTo(tree, 0.6);
            best.swingMainHand();
        }
    }

    // ------------------------------------------------------------------ physical (loaded, near players)

    private void physical(final long now, final LifeSettings s) {
        if (now - this.refilledAt > 5000L || this.rotation.isEmpty() && now - this.refilledAt > 1000L) {
            this.refilledAt = now;
            this.rotation.clear();
            for (final CitizenRecord r : this.data().allCitizens()) {
                if (!r.pending() && Bukkit.getEntity(r.entityId()) != null) {
                    this.rotation.addLast(r.entityId());
                }
            }
            this.nextMove.keySet().retainAll(new HashSet<>(this.rotation));
        }
        final long started = System.nanoTime();
        final long budget = (long) (s.budgetMs() * 1_000_000L);
        int processed = 0;
        int lookups = 0;
        while (processed < s.perTick() && lookups < s.perTick() * 4 && !this.rotation.isEmpty()) {
            if (System.nanoTime() - started > budget) {
                break;
            }
            final UUID id = this.rotation.pollFirst();
            lookups++;
            final Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Villager v) || !v.isValid()) {
                continue;
            }
            this.rotation.addLast(id);
            processed++;
            try {
                this.process(v, s);
            } catch (final RuntimeException ex) {
                this.plugin.getLogger().log(java.util.logging.Level.FINE, "Życie obywatela", ex);
            }
        }
    }

    private void process(final Villager v, final LifeSettings s) {
        final CitizenRecord rec = this.data().citizen(v.getUniqueId());
        final CitizenLife l = rec == null ? null : this.snap.citizens.get(rec.entityId());
        if (l == null || this.plugin.royalRebuild() != null && this.plugin.royalRebuild().blocks(rec.countryId())) {
            return;
        }
        final World w = v.getWorld();
        final Location bed = this.bedOf(l, w);
        final Location home = bed != null ? bed : this.buildingSpot(l.home, w);
        final Location work = this.workSpot(l, w);
        this.writePos(v, this.homeKey, home);
        this.writePos(v, this.workKey, work);
        if (!v.isAdult() && l.born > 0 && !v.getAgeLock()) {
            v.setAgeLock(true); // our children grow up by the family rules, not in 20 minutes
        }
        if (rec.adult() && !v.isAdult()) {
            this.plugin.citizens().growUp(v, rec.profession());
        }
        if (!this.playerNear(v.getLocation(), s.playerRange())) {
            return;
        }
        if (s.vanillaHome() && bed != null) {
            final Location cur = v.getMemory(MemoryKey.HOME);
            if (cur == null || cur.getWorld() != w || cur.getBlockX() != bed.getBlockX() || cur.getBlockY() != bed.getBlockY()
                || cur.getBlockZ() != bed.getBlockZ()) {
                v.setMemory(MemoryKey.HOME, bed);
            }
        }
        if (!s.steerHome() || home == null || this.plugin.zycie().available()) {
            return; // Zycie walks the citizens (it reads ludnosc:home / ludnosc:workplace)
        }
        final DailyRoutine.Phase phase = s.routine().phase(w.getFullTime());
        if (phase != DailyRoutine.Phase.EVENING || v.isSleeping() || v.isTrading() || v.isLeashed() || v.isInsideVehicle()) {
            return;
        }
        if (v.getLocation().distanceSquared(home) > 36 && this.movesThisTick < 2
            && this.ticks >= this.nextMove.getOrDefault(v.getUniqueId(), 0L)) {
            this.movesThisTick++;
            this.nextMove.put(v.getUniqueId(), this.ticks + 6);
            v.getPathfinder().moveTo(home, 0.55);
        }
    }

    private void writePos(final Villager v, final NamespacedKey key, final Location at) {
        final var pdc = v.getPersistentDataContainer();
        if (at == null || at.getWorld() != v.getWorld()) {
            if (pdc.has(key)) {
                pdc.remove(key);
            }
            return;
        }
        final int[] want = {at.getBlockX(), at.getBlockY(), at.getBlockZ()};
        final int[] cur = pdc.get(key, PersistentDataType.INTEGER_ARRAY);
        if (!java.util.Arrays.equals(cur, want)) {
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, want);
        }
    }

    private boolean playerNear(final Location at, final int range) {
        final double r2 = (double) range * range;
        for (final Location p : this.players.computeIfAbsent(at.getWorld(), w -> {
            final List<Location> out = new ArrayList<>();
            for (final Player pl : w.getPlayers()) {
                if (pl.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                    out.add(pl.getLocation());
                }
            }
            return out;
        })) {
            if (p.distanceSquared(at) <= r2) {
                return true;
            }
        }
        return false;
    }

    /** Head block of the assigned bed (scanned in the loaded house, cached), null when unknown. */
    public Location bedOf(final CitizenLife l, final World w) {
        if (l == null || l.home == null || l.bed < 0) {
            return null;
        }
        final Building b = this.data().building(l.home);
        if (b == null || w == null || !b.box().world().equals(w.getName())) {
            return null;
        }
        BedCache c = this.beds.get(b.id());
        final long now = System.currentTimeMillis();
        if ((c == null || c.beds() != b.beds() || now - c.at() > 600_000L) && this.scansThisTick < 1
            && ConstructionService.chunksLoaded(w, b.box())) {
            this.scansThisTick++;
            c = new BedCache(b.beds(), now, scanBeds(w, b.box()));
            this.beds.put(b.id(), c);
            if (this.beds.size() > 4096) {
                this.beds.clear();
            }
        }
        return c == null || l.bed >= c.heads().size() ? null : c.heads().get(l.bed);
    }

    private static List<Location> scanBeds(final World w, final BlockBox box) {
        final List<Location> out = new ArrayList<>();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    final Block b = w.getBlockAt(x, y, z);
                    if (Tag.BEDS.isTagged(b.getType()) && b.getBlockData() instanceof Bed bed && bed.getPart() == Bed.Part.HEAD) {
                        out.add(b.getLocation());
                    }
                }
            }
        }
        return out;
    }

    private Location buildingSpot(final UUID id, final World w) {
        final Building b = id == null ? null : this.data().building(id);
        if (b == null || w == null || !b.box().world().equals(w.getName())) {
            return null;
        }
        if (b.spawn() != null) {
            return new Location(w, b.spawn().x() + 0.5, b.spawn().y(), b.spawn().z() + 0.5);
        }
        return new Location(w, b.box().centerX() + 0.5, b.box().minY() + 1, b.box().centerZ() + 0.5);
    }

    private Location workSpot(final CitizenLife l, final World w) {
        if (l.workplace == null) {
            return null;
        }
        final Location b = this.buildingSpot(l.workplace, w);
        if (b != null) {
            return b;
        }
        final ConstructionSite site = this.data().site(l.workplace);
        if (site != null && w.getName().equals(site.world)) {
            return new Location(w, site.originX + 0.5, site.originY, site.originZ + 0.5);
        }
        return null;
    }

    // ------------------------------------------------------------------ views

    /** Message to the online players of a country ({@link LifeTexts}; empty override = off). */
    public void tell(final UUID country, final String key, final net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        if (country == null || pl.medivalrpg.ludnosc.config.LifeTexts.raw(this.plugin.messages(), key).isEmpty()) {
            return;
        }
        final net.kyori.adventure.text.Component msg = pl.medivalrpg.ludnosc.config.LifeTexts.text(this.plugin.messages(), key, resolvers);
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.kraje().getCountryOf(p.getUniqueId()).map(k -> k.id().equals(country)).orElse(false)) {
                p.sendMessage(msg);
            }
        }
    }

    /** Where the citizen is expected now (abstract day plan, also for unloaded citizens). */
    public DailyRoutine.Place routinePlace(final CitizenRecord r) {
        final World w = Bukkit.getWorld(r.world());
        final CitizenLife l = this.life(r.entityId());
        final long time = w == null ? 6000 : w.getFullTime();
        return this.settings().routine().place(time, r.adult(), l != null && l.workplace != null, l != null && l.home != null);
    }

    /** Description of a home / workplace building for views. */
    public String describePlace(final UUID id) {
        if (id == null) {
            return null;
        }
        final Building b = this.data().building(id);
        if (b != null) {
            final BuildingTemplate t = this.plugin.templates().get(b.type());
            final String name = t == null ? b.type() : t.meta().displayName().replaceAll("<[^>]*>", "");
            final BlockPos p = b.spawn();
            return name + " #" + b.id().toString().substring(0, 4) + " (" + (p == null ? b.box().centerX() : p.x()) + " "
                + (p == null ? b.box().minY() : p.y()) + " " + (p == null ? b.box().centerZ() : p.z()) + ")";
        }
        final ConstructionSite site = this.data().site(id);
        if (site != null) {
            return "plac budowy: " + this.plugin.construction().displayName(site.type).replaceAll("<[^>]*>", "") + " ("
                + site.originX + " " + site.originY + " " + site.originZ + ")";
        }
        return "?";
    }
}
