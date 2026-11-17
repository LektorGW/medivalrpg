// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ai;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.armia.api.NpcBudget;
import pl.medivalrpg.armia.api.NpcGoal;
import pl.medivalrpg.armia.api.NpcLogCategory;
import pl.medivalrpg.armia.api.NpcPosture;
import pl.medivalrpg.armia.api.NpcReply;
import pl.medivalrpg.armia.api.NpcStance;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.logic.AiDecisions;
import pl.medivalrpg.armia.logic.AiRules;
import pl.medivalrpg.armia.logic.AiTuning;
import pl.medivalrpg.armia.logic.AllianceView;
import pl.medivalrpg.armia.logic.BudgetPlan;
import pl.medivalrpg.armia.logic.BuildBudget;
import pl.medivalrpg.armia.logic.BuildingPlanner;
import pl.medivalrpg.armia.logic.Capitulation;
import pl.medivalrpg.armia.logic.ChunkPlanner;
import pl.medivalrpg.armia.logic.Crisis;
import pl.medivalrpg.armia.logic.DecisionLog;
import pl.medivalrpg.armia.logic.GoalPlanner;
import pl.medivalrpg.armia.logic.Ledger;
import pl.medivalrpg.armia.logic.LogText;
import pl.medivalrpg.armia.logic.NeighborView;
import pl.medivalrpg.armia.logic.Opinions;
import pl.medivalrpg.armia.logic.Personality;
import pl.medivalrpg.armia.logic.Policy;
import pl.medivalrpg.armia.logic.WarView;
import pl.medivalrpg.armia.model.NpcState;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.SoldierMode;
import pl.medivalrpg.armia.ruler.RulerService;
import pl.medivalrpg.armia.service.Population;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.armia.soldier.Soldier;
import pl.medivalrpg.armia.storage.DataStore;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.SiegeInfo;
import pl.medivalrpg.kraje.api.TreasuryLogEntry;
import pl.medivalrpg.kraje.api.VassalInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * "Władcy NPC": the AI of NPC countries. Every {@code ai.tick-minutes} each active NPC country runs one decision
 * tick (at most one country per second, so the work is spread). The decisions themselves are pure functions
 * ({@link AiDecisions}, {@link Policy}, {@link Capitulation}, {@link BudgetPlan}, {@link Crisis}); this class gathers
 * the situation, applies the results through the APIs, records money flows and logs every decision with a category.
 */
public final class NpcAiService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long HOUR = 3_600_000L;

    private final Plugin plugin;
    private final Services services;
    private final ArmyService army;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final DataStore store;
    private final Map<UUID, NpcState> states;
    private final Map<UUID, DecisionLog> logs = new HashMap<>();
    private final DataStore.Meta meta;
    /** "from→to" proposals answered synchronously (the delayed answer from the listener is skipped). */
    private final Set<String> answeredDirectly = new HashSet<>();
    private final Map<UUID, Long> buyoutTriedAt = new HashMap<>();
    /** "union:from>to" / "embassy:from>to" → when the AI last answered (anti-spam cooldown, memory only). */
    private final Map<String, Long> bondAnsweredAt = new HashMap<>();
    private RulerService rulers;
    private NavalAiService naval;
    private ConquestService conquest;
    private BukkitTask task;
    private long lastScan;

    public NpcAiService(final Plugin plugin, final Services services, final ArmyService army, final Supplier<ArmiaConfig> config,
                        final Supplier<Messages> messages, final DataStore store, final Map<UUID, NpcState> states, final DataStore.Meta meta) {
        this.plugin = plugin;
        this.services = services;
        this.army = army;
        this.config = config;
        this.messages = messages;
        this.store = store;
        this.states = states;
        this.meta = meta;
        for (final NpcState s : states.values()) {
            s.normalize();
        }
    }

    /** Rulers are respawned at every AI tick when missing. */
    public void setRulers(final RulerService rulers) {
        this.rulers = rulers;
    }

    /** Multi-tick field operations (naval landings, coastal defence, artillery). */
    public NavalAiService naval() {
        return this.naval;
    }

    public void setNaval(final NavalAiService naval) {
        this.naval = naval;
    }

    /** Background wars far from players ({@code ai.conquest}). */
    public void setConquest(final ConquestService conquest) {
        this.conquest = conquest;
    }

    public ConquestService conquest() {
        return this.conquest;
    }

    public void start() {
        // Kraje does not keep sieges across restarts: free soldiers still assigned to one.
        for (final NpcState s : this.states.values()) {
            if (s.siegeId != null || !s.siegeSoldiers.isEmpty()) {
                this.releaseSiege(s);
            }
        }
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::schedule, 200L, 20L);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
        }
    }

    ArmiaConfig cfg() {
        return this.config.get();
    }

    private AiRules rules() {
        return this.cfg().rules;
    }

    KrajeApi kraje() {
        return this.services.kraje();
    }

    public Map<UUID, NpcState> states() {
        return this.states;
    }

    public boolean globalPaused() {
        return this.meta.aiPaused;
    }

    public void setGlobalPaused(final boolean paused) {
        this.meta.aiPaused = paused;
    }

    // ------------------------------------------------------------------ state

    public Optional<NpcState> state(final UUID country) {
        return Optional.ofNullable(this.states.get(country));
    }

    /** State of an NPC country, created with the default personality if missing. */
    public NpcState ensure(final UUID country) {
        return this.states.computeIfAbsent(country, id -> {
            final NpcState s = new NpcState(id, this.cfg().aiDefaults);
            s.nextTickAt = System.currentTimeMillis() + 60_000L;
            return s;
        });
    }

    public void forget(final UUID country) {
        if (this.conquest != null) {
            this.conquest.onDisband(country);
        }
        this.states.remove(country);
        this.logs.remove(country);
        for (final NpcState s : this.states.values()) {
            s.opinion.remove(country);
            s.warStartedAt.remove(country);
            s.warLosses.remove(country);
            s.stance.remove(country);
            s.pending.remove(country);
            s.tributeDemands.remove(country);
            s.tributes.removeIf(d -> country.equals(d.other));
            s.lostChunks.removeIf(l -> country.equals(l.taker));
            if (country.equals(s.siegeTarget)) {
                s.siegeTarget = null;
            }
        }
    }

    /** ArmiaApi#isAiActive. */
    public boolean isActive(final UUID country) {
        final NpcState s = this.states.get(country);
        return s != null && s.active && !s.paused && this.cfg().aiEnabled && !this.meta.aiPaused
            && this.kraje().getCountry(country).map(KrajInfo::npc).orElse(false);
    }

    /** Live decision log of a country (seeded from the persisted copy in the state). */
    public DecisionLog log(final UUID country) {
        return this.logs.computeIfAbsent(country, k -> {
            final DecisionLog l = new DecisionLog(this.cfg().aiLogSize);
            final NpcState st = this.states.get(k);
            if (st != null && st.log != null) {
                for (final NpcState.LogLine x : st.log) {
                    l.add(x.time, NpcLogCategory.parse(x.category).orElse(LogText.classify(x.text)), x.text);
                }
            }
            return l;
        });
    }

    /** Logs a decision, guessing the category from the text. */
    public void log(final UUID country, final String text) {
        this.log(country, LogText.classify(text), text);
    }

    public void log(final UUID country, final NpcLogCategory category, final String text) {
        final long now = System.currentTimeMillis();
        this.log(country).add(now, category, text);
        if (this.cfg().aiLogFile) {
            final String name = this.kraje().getCountry(country).map(KrajInfo::name).orElse(country.toString());
            this.store.appendLog("ai-decisions.log", LocalDateTime.now().format(TIME) + " [" + name + "] [" + category.name() + "] " + text);
        }
    }

    /** Main thread, before saving: copies the live logs into the states (persisted in npc.json). */
    public void prepareSave() {
        for (final NpcState st : this.states.values()) {
            final DecisionLog l = this.logs.get(st.country);
            if (l == null) {
                continue;
            }
            final List<NpcState.LogLine> lines = new ArrayList<>();
            for (final DecisionLog.Entry e : l.last(this.cfg().aiLogSize)) {
                lines.add(new NpcState.LogLine(e.time(), e.category().name(), e.text()));
            }
            st.log = lines;
        }
    }

    // ------------------------------------------------------------------ money flows (24 h economy)

    /** Records a money flow of an NPC country (signed: + income, − expense). */
    public void flow(final UUID country, final String key, final long amount, final String actor, final String note) {
        final NpcState st = this.states.get(country);
        if (st == null || amount == 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        Ledger.add(st.ledger, new Ledger.Entry(now, key, amount, actor == null ? "" : actor, note == null ? "" : note),
            now, this.cfg().aiLedgerKeepHours * HOUR, 3000);
    }

    /** Copies new lines of Kraje's treasury history (claims, upkeep, wars, sieges, vassal tribute) into the ledger. */
    public void syncKrajeLedger(final UUID country) {
        final NpcState st = this.states.get(country);
        if (st == null) {
            return;
        }
        final List<TreasuryLogEntry> list;
        try {
            list = this.kraje().treasuryLog(country, 100);
        } catch (final LinkageError | RuntimeException e) {
            return;
        }
        final long now = System.currentTimeMillis();
        long max = st.krajeLogSeen;
        for (int i = list.size() - 1; i >= 0; i--) {
            final TreasuryLogEntry e = list.get(i);
            if (e == null || e.time() <= st.krajeLogSeen || e.amount() == 0) {
                continue;
            }
            Ledger.add(st.ledger, new Ledger.Entry(e.time(), Ledger.krajeKey(e.type()), e.amount(), e.actor() == null ? "" : e.actor(),
                Ledger.krajeNote(e.type())), now, this.cfg().aiLedgerKeepHours * HOUR, 3000);
            max = Math.max(max, e.time());
        }
        st.krajeLogSeen = max;
    }

    static final String WORLD_PAYMENTS = "world:payments";

    /** Closed world money pool (Skarbiec 2026-10-28) — config or detected from the world accounts. */
    public boolean closedPool() {
        final var eco = this.services.skarbiec();
        return pl.medivalrpg.armia.logic.ClosedEconomy.closedPool(this.cfg().aiEconomyClosedPool,
            eco.balance("world:reserve"), eco.balance("world:cash"), eco.balance(WORLD_PAYMENTS));
    }

    private long balance(final UUID country) {
        return this.services.skarbiec().balance(this.services.treasury(country));
    }

    // ------------------------------------------------------------------ scheduling

    private void schedule() {
        final long now = System.currentTimeMillis();
        if (now - this.lastScan > 60_000L) {
            this.lastScan = now;
            for (final KrajInfo c : this.kraje().getCountries()) {
                if (c.npc()) {
                    this.ensure(c.id());
                }
            }
            this.states.keySet().removeIf(id -> this.kraje().getCountry(id).isEmpty());
        }
        if (!this.cfg().aiEnabled || this.meta.aiPaused) {
            return;
        }
        final Optional<NpcState> due = this.states.values().stream()
            .filter(s -> s.active && !s.paused && now >= s.nextTickAt)
            .min(Comparator.comparingLong(s -> s.nextTickAt));
        if (due.isPresent()) {
            final NpcState s = due.get();
            s.nextTickAt = now + this.cfg().aiTickMinutes * 60_000L + ThreadLocalRandom.current().nextLong(15_000L);
            try {
                this.tick(s.country, false);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd AI kraju " + s.country, e);
            }
        }
    }

    // ------------------------------------------------------------------ one decision tick

    /** Budget split of a country: the admin's, or derived from the personality. */
    public NpcBudget budget(final NpcState st) {
        if (st.budget != null) {
            return new NpcBudget(st.budget.army, st.budget.buildings, st.budget.reserve, true);
        }
        return BudgetPlan.derived(st.personality(), this.cfg().buildBudget);
    }

    private boolean besieged(final UUID country) {
        return this.kraje().getSieges().stream().anyMatch(s -> s.defender().equals(country));
    }

    private boolean rebuilding(final KrajInfo c, final NpcState st) {
        return Crisis.rebuilding(this.rules(), !c.enemies().isEmpty(), st.peaceSince, System.currentTimeMillis());
    }

    /** Runs one decision tick now; returns false when the country is not an NPC country. */
    public boolean tick(final UUID id, final boolean manual) {
        final Optional<KrajInfo> oc = this.kraje().getCountry(id);
        if (oc.isEmpty() || !oc.get().npc()) {
            return false;
        }
        final KrajInfo c = oc.get();
        final NpcState st = this.ensure(id);
        final Personality p = st.personality();
        final AiTuning t = this.cfg().tuning;
        final String account = this.services.treasury(id);
        st.tickCount++;
        if (manual) {
            this.log(id, NpcLogCategory.OTHER, "Ręczny tick (admin)");
        }
        if (this.rulers != null) {
            this.rulers.ensure(id);
        }
        this.syncKrajeLedger(id);
        this.updateWarState(c, st);

        // 1. Income: virtual AI income, NPC taxes (estimate) and the tax multiplier correction.
        final double dayFraction = this.cfg().aiTickMinutes / 1440.0;
        final long formula = AiDecisions.income(t, p, c.chunkCount(), dayFraction);
        final boolean closed = this.closedPool();
        final long income = pl.medivalrpg.armia.logic.ClosedEconomy.income(formula, closed,
            closed ? this.services.skarbiec().balance(WORLD_PAYMENTS) : 0, this.cfg().aiEconomyPaymentsFloor, this.cfg().aiEconomyPaymentsShare);
        // Closed pool: Skarbiec moves a deposit from world:payments (nothing is created); the reason says so.
        if (income > 0 && this.services.skarbiec().deposit(account, income,
            closed ? "Armia NPC: dochód z obrotu (budżet operacyjny świata)" : "Dochód kraju NPC").ok()) {
            this.flow(id, "aiIncome", income, "AI", closed ? "Dochód z obrotu — budżet operacyjny świata (bogactwo " + p.wealth() + ")"
                : "Dochód AI (bogactwo " + p.wealth() + ")");
        } else if (closed && formula > 0 && st.tickCount % 12 == 1) {
            this.log(id, NpcLogCategory.ECONOMY, "Brak dochodu z obrotu: budżet operacyjny świata poniżej progu — oszczędzanie");
        }
        final Population pop = this.services.population();
        this.taxes(c, st, pop, dayFraction);
        final long wages = this.army.dailyWages(id);

        // 2. Vassalage: buy out / release.
        this.vassalage(c, st, wages);

        // 3. A losing country offers capitulation instead of falling.
        this.maybeCapitulate(c, st);

        // 4. Tribute agreements and demands.
        this.tributes(c, st);

        // 5. Expansion (not while fighting for survival).
        long balance = this.services.skarbiec().balance(account);
        if (!st.crisis && AiDecisions.shouldExpand(t, p, c.chunkCount(), balance, this.cfg().aiClaimCostEstimate, wages, ThreadLocalRandom.current().nextDouble())) {
            this.expand(c);
        }

        // 6. Buildings: priority walls/towers (crisis) or houses/granary (rebuilding), else Ludnosc's settlement planner.
        if (this.cfg().aiBuildings && pop.present()) {
            this.develop(this.kraje().getCountry(id).orElse(c), st, pop, wages);
        }

        // 7. Recruitment.
        this.recruit(this.kraje().getCountry(id).orElse(c), st, account, wages, pop);
        this.hireMercenaries(this.kraje().getCountry(id).orElse(c), st, wages, pop);
        this.rearm(this.kraje().getCountry(id).orElse(c), st, wages);
        this.applyResearchShare(id);

        // 8. Border guards.
        this.assignGuards(this.kraje().getCountry(id).orElse(c), st);

        // 9. Diplomacy.
        this.diplomacy(this.kraje().getCountry(id).orElse(c), st);

        // 10. Sieges; without a land front a naval landing (wave 13/16).
        this.siege(this.kraje().getCountry(id).orElse(c), st);
        if (this.naval != null) {
            this.naval.plan(this.kraje().getCountry(id).orElse(c), st);
        }

        // Opinions return to neutral: positive ones fast, grudges slowly.
        final int slow = this.rules().negativeDecayTicks();
        st.opinion.replaceAll((k, v) -> Opinions.decay(v, st.tickCount, slow));
        st.opinion.values().removeIf(v -> v == 0);
        this.expirePending(st);
        this.syncKrajeLedger(id);
        st.goal = this.computeGoal(this.kraje().getCountry(id).orElse(c), st);
        return true;
    }

    /** Crisis mode on/off, rebuilding; logged when it changes. */
    private void updateWarState(final KrajInfo c, final NpcState st) {
        final long now = System.currentTimeMillis();
        final boolean atWar = !c.enemies().isEmpty();
        if (!atWar && !st.warStartedAt.isEmpty()) {
            st.warStartedAt.clear(); // missed a RelationChangeEvent (restart)
            if (st.peaceSince == 0) {
                st.peaceSince = now;
            }
        }
        if (atWar) {
            st.peaceSince = 0;
        }
        final boolean crisis = Crisis.active(this.rules(), atWar, now, st.lastChunkLostAt, this.besieged(c.id()), st.lastSiegeOnUsAt);
        if (crisis && st.crisisSince == 0) {
            st.crisisSince = now;
            this.log(c.id(), NpcLogCategory.WAR, "TRYB KRYZYSOWY: kraj traci ziemie albo jest oblegany — szybszy pobór, budżet na wojsko, mury i wieże");
        } else if (!crisis && st.crisisSince != 0) {
            st.crisisSince = 0;
            this.log(c.id(), NpcLogCategory.WAR, "Koniec trybu kryzysowego");
        }
        st.crisis = crisis;
    }

    /** NPC taxes: ledger estimate from Ludnosc, plus the tax multiplier correction paid by Armia. */
    private void taxes(final KrajInfo c, final NpcState st, final Population pop, final double dayFraction) {
        if (!pop.present()) {
            return;
        }
        final long daily = pop.dailyTax(c.id());
        if (daily <= 0) {
            return;
        }
        final long estimate = Math.round(daily * st.taxMultiplier / 100.0 * dayFraction);
        this.flow(c.id(), "npcTax", estimate, "", "Podatki od ludności (szacunek" + (st.taxMultiplier != 100 ? ", mnożnik " + st.taxMultiplier + "%" : "") + ")");
        final long correction = Math.round(daily * (st.taxMultiplier - 100) / 100.0 * dayFraction);
        final String account = this.services.treasury(c.id());
        if (correction > 0) {
            // Extra citizens' tax: in the closed pool Skarbiec takes it from the NPC citizens' budget (world:households).
            this.services.skarbiec().deposit(account, correction, "Ludnosc: podatek od obywateli NPC — mnożnik Armii");
        } else if (correction < 0) {
            final long take = Math.min(-correction, this.services.skarbiec().balance(account));
            if (take > 0) {
                this.services.skarbiec().withdraw(account, take, "Mnożnik podatku NPC");
            }
        }
    }

    private void expand(final KrajInfo c) {
        final String world = c.capitalWorld();
        if (world == null || Bukkit.getWorld(world) == null) {
            return;
        }
        final Set<Long> own = this.kraje().getClaims(c.id(), world);
        final List<Long> candidates = ChunkPlanner.expansionCandidates(own,
            k -> this.kraje().getCountryAt(world, ChunkPlanner.cx(k), ChunkPlanner.cz(k)).isEmpty(),
            c.capitalX(), c.capitalZ(), 6);
        String lastError = null;
        for (final long k : candidates) {
            final KrajeResult r = this.kraje().claimChunk(c.id(), world, ChunkPlanner.cx(k), ChunkPlanner.cz(k), ClaimCause.NPC);
            if (r.ok()) {
                this.log(c.id(), NpcLogCategory.ECONOMY, "Rozrost: zajęto chunk " + ChunkPlanner.cx(k) + "," + ChunkPlanner.cz(k));
                return;
            }
            lastError = r.status() + " " + r.message();
            if (r.status() == KrajeResult.Status.INSUFFICIENT_FUNDS || r.status() == KrajeResult.Status.LIMIT_REACHED) {
                break;
            }
        }
        if (lastError != null) {
            this.log(c.id(), NpcLogCategory.ECONOMY, "Rozrost nieudany: " + lastError);
        }
    }

    private void develop(final KrajInfo c, final NpcState st, final Population pop, final long wages) {
        final AiTuning t = this.cfg().tuning;
        final Personality p = st.personality();
        final boolean atWar = !c.enemies().isEmpty();
        final boolean rebuilding = this.rebuilding(c, st);
        final String account = this.services.treasury(c.id());
        final long balance = this.services.skarbiec().balance(account);
        final long minReserve = AiDecisions.reserve(t, p, wages);
        final BudgetPlan.Shares shares = BudgetPlan.effective(this.rules(), this.budget(st), atWar, st.crisis, rebuilding);
        final long budget = BudgetPlan.buildingBudget(shares, balance, minReserve, this.cfg().buildBudget.minBudget());

        // Priority buildings through orderBuildingAuto.
        if (st.crisis || rebuilding) {
            final List<Population.Requirement> reqs = pop.requirements(c.id());
            final String type = Crisis.priorityBuilding(st.crisis, rebuilding,
                available(reqs, "mur"), built(reqs, "mur"), available(reqs, "wieza"), built(reqs, "wieza"),
                pop.population(c.id()), pop.housing(c.id()), available(reqs, "dom"), available(reqs, "spichlerz"), built(reqs, "spichlerz"));
            final long cost = reqs.stream().filter(r -> r.type().equalsIgnoreCase(type == null ? "" : type)).mapToLong(Population.Requirement::cost).findFirst().orElse(0);
            final long allowed = st.crisis ? balance - minReserve : budget;
            if (type != null && cost <= allowed) {
                final String result = pop.orderBuildingAuto(c.id(), type);
                if ("OK".equals(result)) {
                    final long spent = Math.max(0, balance - this.services.skarbiec().balance(account));
                    this.flow(c.id(), "buildings", -spent, "AI", (st.crisis ? "Obrona: " : "Odbudowa: ") + type);
                    st.lastBuildAt = System.currentTimeMillis();
                    st.buildingsOrdered.merge(type, 1, Integer::sum);
                    this.log(c.id(), NpcLogCategory.BUILD, (st.crisis ? "Tryb kryzysowy: zamówiono " : "Odbudowa po wojnie: zamówiono ") + type
                        + (spent > 0 ? " (" + this.services.money(spent) + ")" : ""));
                    return;
                }
                if (!result.equals(st.lastBuildResult)) {
                    this.log(c.id(), NpcLogCategory.BUILD, "Budowa priorytetowa " + type + " nieudana: " + result);
                }
                st.lastBuildResult = result;
            }
        }
        if (budget <= 0) {
            return;
        }
        final String result = pop.developSettlement(c.id(), budget);
        if ("UNKNOWN_BUILDING".equals(result)) {
            if (balance > minReserve) {
                this.build(c, st, pop); // older Ludnosc without the planner
            }
            return;
        }
        final long spent = Math.max(0, balance - this.services.skarbiec().balance(account));
        if (spent > 0) {
            this.flow(c.id(), "buildings", -spent, "AI", "Rozbudowa osady (planer)");
        }
        final boolean wasSaving = st.savingForBuilding;
        st.savingForBuilding = BuildBudget.keepSaving(this.cfg().buildBudget, p, balance, result);
        final String money = this.services.money(budget);
        final String text = switch (result) {
            case "OK" -> "Rozbudowa osady: zamówiono (budżet " + money + (spent > 0 ? ", wydano " + this.services.money(spent) : "") + ")";
            case "INSUFFICIENT_FUNDS" -> "Rozbudowa osady: brak środków na sensowny budynek (budżet " + money + ")"
                + (st.savingForBuilding ? " — odkładam na rozbudowę" : "");
            case "AREA_OBSTRUCTED" -> "Rozbudowa osady: brak miejsca";
            case "TOO_MANY_ORDERS" -> "Rozbudowa osady: kolejka budów pełna";
            case "NOT_ON_OWN_LAND" -> "Rozbudowa osady: brak terenu pod budowę";
            default -> "Rozbudowa osady: nieudana (" + result + ")";
        };
        if ("OK".equals(result)) {
            st.lastBuildAt = System.currentTimeMillis();
        }
        // Successes always; repeated failures only once, so the log is not flooded every tick.
        if ("OK".equals(result) || !result.equals(st.lastBuildResult) || wasSaving != st.savingForBuilding) {
            this.log(c.id(), NpcLogCategory.BUILD, text);
        }
        st.lastBuildResult = result;
    }

    private static boolean available(final List<Population.Requirement> reqs, final String type) {
        return reqs.stream().anyMatch(r -> r.type().equalsIgnoreCase(type) && r.available());
    }

    private static int built(final List<Population.Requirement> reqs, final String type) {
        return reqs.stream().filter(r -> r.type().equalsIgnoreCase(type)).mapToInt(Population.Requirement::built).findFirst().orElse(0);
    }

    private void build(final KrajInfo c, final NpcState st, final Population pop) {
        final Collection<String> available = pop.buildingTypes();
        final Optional<String> next = BuildingPlanner.next(pop.population(c.id()), pop.housing(c.id()), st.buildingsOrdered, available,
            this.cfg().aiHousingTypes, this.cfg().aiUtilityTypes, this.cfg().aiMaxHousing);
        if (next.isEmpty()) {
            return;
        }
        final World w = Bukkit.getWorld(c.capitalWorld());
        if (w == null) {
            return;
        }
        final List<Long> loaded = new ArrayList<>();
        for (final long k : this.kraje().getClaims(c.id(), w.getName())) {
            if (w.isChunkLoaded(ChunkPlanner.cx(k), ChunkPlanner.cz(k))) {
                loaded.add(k);
            }
        }
        if (loaded.isEmpty()) {
            return; // the surface is unknown in unloaded chunks — try again later
        }
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        final String account = this.services.treasury(c.id());
        String result = "";
        for (int attempt = 0; attempt < 4; attempt++) {
            final long k = loaded.get(rnd.nextInt(loaded.size()));
            final int x = (ChunkPlanner.cx(k) << 4) + 2 + rnd.nextInt(4);
            final int z = (ChunkPlanner.cz(k) << 4) + 2 + rnd.nextInt(4);
            final Location origin = new Location(w, x, w.getHighestBlockYAt(x, z) + 1, z);
            final long before = this.services.skarbiec().balance(account);
            result = pop.orderBuilding(c.id(), next.get(), origin, 90 * rnd.nextInt(4));
            if ("OK".equals(result)) {
                st.buildingsOrdered.merge(next.get(), 1, Integer::sum);
                st.lastBuildAt = System.currentTimeMillis();
                this.flow(c.id(), "buildings", -Math.max(0, before - this.services.skarbiec().balance(account)), "AI", "Budowa: " + next.get());
                this.log(c.id(), NpcLogCategory.BUILD, "Budowa: zamówiono " + next.get() + " przy " + x + " " + origin.getBlockY() + " " + z);
                return;
            }
            if ("INSUFFICIENT_FUNDS".equals(result) || "TOO_MANY_ORDERS".equals(result) || "UNKNOWN_BUILDING".equals(result)) {
                break;
            }
        }
        this.log(c.id(), NpcLogCategory.BUILD, "Budowa " + next.get() + " nieudana: " + result);
    }

    /** Desired army of a country now (crisis mode raises it). */
    public int desiredSoldiers(final KrajInfo c, final NpcState st) {
        final ArmiaConfig cfg = this.cfg();
        final int current = this.army.registry().ofCountry(c.id()).size();
        final int cap = Math.min(this.army.countryCap(c), Math.max(0, cfg.globalMax - this.army.registry().size() + current));
        final int desired = AiDecisions.desiredSoldiers(cfg.tuning, st.personality(), c.chunkCount(), cap);
        final int wanted = st.crisis ? BudgetPlan.desiredInCrisis(this.rules(), desired, Math.min(cap, Math.max(desired, st.maxSoldiers))) : desired;
        if (!this.closedPool()) {
            return wanted;
        }
        // Closed pool: do not recruit more than income + savings can pay (no desertion spiral, no bankruptcy).
        final long wages = this.army.dailyWages(c.id());
        final long perSoldier = current > 0 ? Math.max(1, wages / current) : cfg.units.values().stream().mapToLong(UnitType::wage).min().orElse(0);
        final long dailyIncome = Math.round(AiDecisions.income(cfg.tuning, st.personality(), c.chunkCount(), 1.0)
            * (this.services.skarbiec().balance(WORLD_PAYMENTS) > cfg.aiEconomyPaymentsFloor ? 1.0 : 0.0))
            + (this.services.population().present() ? this.services.population().dailyTax(c.id()) : 0);
        final int sustainable = pl.medivalrpg.armia.logic.ClosedEconomy.sustainableArmy(dailyIncome, this.balance(c.id()), perSoldier, cfg.aiEconomySustainDays);
        return pl.medivalrpg.armia.logic.ClosedEconomy.cappedDesired(wanted, current, sustainable);
    }

    private void recruit(final KrajInfo c, final NpcState st, final String account, final long wages, final Population pop) {
        final ArmiaConfig cfg = this.cfg();
        if (cfg.units.isEmpty()) {
            return;
        }
        final int current = this.army.registry().ofCountry(c.id()).size();
        final int desired = this.desiredSoldiers(c, st);
        final UnitType unit = this.pickUnit(c.id());
        final long balance = this.services.skarbiec().balance(account);
        final BudgetPlan.Shares shares = BudgetPlan.effective(this.rules(), this.budget(st), !c.enemies().isEmpty(), st.crisis, this.rebuilding(c, st));
        final long armyMoney = BudgetPlan.armyMoney(shares, balance, AiDecisions.reserve(cfg.tuning, st.personality(), wages));
        final int perTick = BudgetPlan.recruitPerTick(this.rules(), cfg.tuning.maxRecruitPerTick(), st.crisis);
        final int batch = BudgetPlan.recruitBatch(desired, current, perTick, armyMoney, unit.cost());
        if (batch <= 0) {
            return;
        }
        final Pos capital = Pos.chunkCenter(c.capitalWorld(), c.capitalX(), c.capitalZ());
        int done = 0;
        long spent = 0;
        for (int i = 0; i < batch; i++) {
            final UnitType u = i == 0 ? unit : this.pickUnit(c.id());
            if (this.army.allowed(c, 1) <= 0 || spent + u.cost() > armyMoney) {
                break;
            }
            if (u.cost() > 0) {
                final TxResult tx = this.services.skarbiec().withdraw(account, u.cost(), "Armia NPC: rekrutacja " + u.id());
                if (!tx.ok()) {
                    break;
                }
            }
            String profession = "minecraft:none";
            if (cfg.aiRequireCitizen && pop.present()) {
                final Location near = capital.isChunkLoaded() ? capital.toLocation() : null;
                final Optional<Population.Conscripted> cs = pop.conscript(c.id(), near);
                if (cs.isEmpty()) {
                    if (u.cost() > 0) {
                        this.services.skarbiec().deposit(account, u.cost(), "Armia NPC: zwrot (brak obywatela)");
                    }
                    this.log(c.id(), NpcLogCategory.ARMY, "Rekrutacja wstrzymana: brak obywateli do poboru");
                    break;
                }
                profession = cs.get().profession();
            }
            spent += u.cost();
            final Soldier s = this.army.create(c.id(), u, capital, 10, SoldierMode.GUARD, null, profession);
            if (capital.isChunkLoaded()) {
                final Location l = capital.toLocation();
                if (l != null) {
                    this.army.controller().materialize(s, l);
                }
            }
            done++;
        }
        if (done > 0) {
            this.flow(c.id(), "recruit", -spent, "AI", done + " żołnierzy");
            this.log(c.id(), NpcLogCategory.ARMY, (st.crisis ? "Pobór kryzysowy: +" : "Rekrutacja: +") + done + " (" + (current + done) + "/" + desired
                + ", koszt " + this.services.money(spent) + ")");
        }
    }

    // ------------------------------------------------------------------ eras (Postep)

    private final Map<UUID, Integer> researchApplied = new HashMap<>();

    /**
     * Research share of the budget → Postep's NPC investment share ({@code setNpcInvestShare}): the admin's percent of
     * the daily income, or -1 (Postep decides) when the research share is automatic. Re-applied when Postep appears.
     */
    public void applyResearchShare(final UUID country) {
        final pl.medivalrpg.armia.service.Progress p = this.services.progress();
        final NpcState st = this.states.get(country);
        if (p == null || st == null || !p.present()) {
            return;
        }
        final int share = st.budget == null || st.budget.research == null ? -1 : st.budget.research;
        if (!Integer.valueOf(share).equals(this.researchApplied.get(country)) && p.setNpcInvestShare(country, share)) {
            this.researchApplied.put(country, share);
        }
    }

    /** Old units in a new era: the AI rearms a few per tick (paid like a player rearm). */
    private void rearm(final KrajInfo c, final NpcState st, final long wages) {
        final pl.medivalrpg.armia.service.Progress p = this.services.progress();
        if (p == null || !p.present() || this.cfg().eraRules.aiRearmPerTick() <= 0) {
            return;
        }
        final long spendable = Math.max(0, this.balance(c.id()) - AiDecisions.reserve(this.cfg().tuning, st.personality(), wages));
        final long[] r = this.army.rearm(c.id(), null, this.cfg().eraRules.aiRearmPerTick(), spendable / 2);
        if (r[0] > 0) {
            this.flow(c.id(), "recruit", -r[1], "AI", "Przezbrojenie " + r[0] + " żołnierzy");
            this.log(c.id(), NpcLogCategory.ARMY, "Przezbrojenie: " + r[0] + " żołnierzy dostaje broń epoki (" + this.services.money(r[1]) + ")");
        }
    }

    /** Postep: a country entered a new era. NPC countries react at the next tick; players get the list of new units. */
    public void onEraAdvance(final UUID country, final int oldEra, final int newEra) {
        final List<String> unlocked = new ArrayList<>();
        for (final UnitType u : this.cfg().units.values()) {
            if (this.cfg().unitEra(u.id()).era() == newEra && this.army.available(country, u)) {
                unlocked.add(pl.medivalrpg.armia.service.ArmiaApiImpl.plainText(u.name()));
            }
        }
        final pl.medivalrpg.armia.service.Progress p = this.services.progress();
        final String era = p == null ? Integer.toString(newEra) : p.eraName(newEra);
        final NpcState st = this.states.get(country);
        if (st != null) {
            st.nextTickAt = Math.min(st.nextTickAt, System.currentTimeMillis() + 10_000L);
            this.log(country, NpcLogCategory.ARMY, "Nowa epoka: " + era + (unlocked.isEmpty() ? "" : " — nowe jednostki: " + String.join(", ", unlocked)));
        } else if (newEra > oldEra) {
            this.army.notifyCountry(country, "era-units", Placeholder.unparsed("era", era),
                Placeholder.unparsed("units", unlocked.isEmpty() ? "brak" : String.join(", ", unlocked)));
        }
    }

    /** Crisis mode with few citizens and a rich treasury: the AI hires mercenaries (no conscription needed). */
    private void hireMercenaries(final KrajInfo c, final NpcState st, final long wages, final Population pop) {
        final ArmiaConfig cfg = this.cfg();
        if (!cfg.mercenariesEnabled || !cfg.mercenariesAi || cfg.mercenaries.isEmpty() || !st.crisis) {
            return;
        }
        final Map<String, Integer> have = this.army.registry().countByType(c.id());
        final UnitType u = cfg.mercenaries.values().stream()
            .min(Comparator.comparingInt((UnitType x) -> have.getOrDefault(x.id(), 0)).thenComparingLong(UnitType::cost)).orElseThrow();
        final int current = this.army.registry().ofCountry(c.id()).size();
        final long balance = this.balance(c.id());
        final int n = pl.medivalrpg.armia.logic.Mercenaries.aiHire(cfg.mercRules, true, pop.present() ? pop.population(c.id()) : 0, balance,
            AiDecisions.reserve(cfg.tuning, st.personality(), wages), u.cost(), this.desiredSoldiers(c, st) - current,
            this.army.mercenaries(c.id()).size(), this.army.mercenaryCap(c));
        final World w = Bukkit.getWorld(c.capitalWorld());
        if (n <= 0 || w == null) {
            return;
        }
        final Location spawn = new Location(w, (c.capitalX() << 4) + 8.5, 64, (c.capitalZ() << 4) + 8.5);
        if (spawn.isChunkLoaded()) {
            spawn.setY(w.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ()) + 1.0);
        }
        final ArmyService.RemoteRecruit r = this.army.hireAt(c, u, n, spawn, SoldierMode.GUARD, null, "AI");
        if (r.done() > 0) {
            this.flow(c.id(), "recruit", -r.spent(), "AI", "Najemnicy: " + r.done() + " × " + u.id());
            this.log(c.id(), NpcLogCategory.ARMY, "Tryb kryzysowy: wynajęto " + r.done() + " × " + u.id() + " (koszt " + this.services.money(r.spent())
                + ", kontrakt " + cfg.mercRules.contractHours() + " h)");
        }
    }

    /** Unit mix: ai.unit-mix weights, keeping the army's composition close to them. */
    private UnitType pickUnit(final UUID country) {
        final ArmiaConfig cfg = this.cfg();
        final Map<String, Integer> have = this.army.registry().countByType(country);
        final List<String> current = this.army.currentUnits(country); // era units available now, without replaced ones
        final Map<String, Double> weights = new LinkedHashMap<>();
        for (final UnitType u : cfg.units.values()) {
            if (!current.contains(u.id())) {
                continue;
            }
            final double w = this.plugin.getConfig().getDouble("ai.unit-mix." + u.id(), 1.0);
            if (w > 0) {
                weights.put(u.id(), w);
            }
        }
        if (weights.isEmpty()) {
            return cfg.units.values().iterator().next();
        }
        final double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        final int count = have.values().stream().mapToInt(Integer::intValue).sum() + 1;
        String best = null;
        double bestDeficit = -Double.MAX_VALUE;
        for (final Map.Entry<String, Double> e : weights.entrySet()) {
            final double deficit = e.getValue() / total * count - have.getOrDefault(e.getKey(), 0);
            if (deficit > bestDeficit) {
                bestDeficit = deficit;
                best = e.getKey();
            }
        }
        return cfg.unit(best);
    }

    private static boolean night(final World w) {
        final long time = w.getTime() % 24000L;
        return time >= 13000L && time <= 23000L;
    }

    private void assignGuards(final KrajInfo c, final NpcState st) {
        final String world = c.capitalWorld();
        if (world == null) {
            return;
        }
        final List<Soldier> guards = new ArrayList<>();
        for (final Soldier s : this.army.registry().ofCountry(c.id())) {
            if (s.record().mode == SoldierMode.GUARD && !s.record().siege && s.record().commander == null) {
                guards.add(s);
            }
        }
        if (guards.isEmpty()) {
            return;
        }
        guards.sort(Comparator.comparing(Soldier::id));
        final Set<Long> own = this.kraje().getClaims(c.id(), world);
        final List<Long> border = ChunkPlanner.borderChunks(own,
            k -> this.kraje().getCountryAt(world, ChunkPlanner.cx(k), ChunkPlanner.cz(k)).map(o -> c.enemies().contains(o.id())).orElse(false),
            k -> this.kraje().getCountryAt(world, ChunkPlanner.cx(k), ChunkPlanner.cz(k)).isPresent());
        final World w = Bukkit.getWorld(world);
        final double fraction = BudgetPlan.guardFraction(this.rules(), this.cfg().aiGuardFraction, st.posture(), st.crisis, w != null && night(w));
        final int n = (int) Math.round(guards.size() * fraction);
        final List<Long> posts = ChunkPlanner.guardPosts(border, n);
        int moved = 0;
        for (int i = 0; i < guards.size(); i++) {
            final Soldier s = guards.get(i);
            final int cx;
            final int cz;
            final double radius;
            if (i < posts.size()) {
                cx = ChunkPlanner.cx(posts.get(i));
                cz = ChunkPlanner.cz(posts.get(i));
                radius = this.cfg().aiGuardRadius;
            } else {
                cx = c.capitalX();
                cz = c.capitalZ();
                radius = 10;
            }
            final Pos post = s.record().post;
            if (post == null || !post.world().equals(world) || post.chunkX() != cx || post.chunkZ() != cz) {
                s.record().post = Pos.chunkCenter(world, cx, cz);
                s.record().radius = radius;
                moved++;
            }
        }
        if (moved > 0) {
            this.army.registry().markDirty();
            this.log(c.id(), NpcLogCategory.ARMY, "Straż: przestawiono " + moved + " żołnierzy (" + Math.min(n, posts.size()) + " na granicy, postawa "
                + st.posture().polish() + ")");
        }
    }

    /** Guard posts: guards grouped by post chunk. */
    public List<pl.medivalrpg.armia.api.NpcOutpost> outposts(final KrajInfo c) {
        final Map<String, int[]> groups = new LinkedHashMap<>();
        final Map<String, Pos> where = new HashMap<>();
        for (final Soldier s : this.army.registry().ofCountry(c.id())) {
            final Pos post = s.record().post;
            if (s.record().mode != SoldierMode.GUARD || post == null || s.record().siege) {
                continue;
            }
            final String key = post.world() + ":" + post.chunkX() + ":" + post.chunkZ();
            groups.computeIfAbsent(key, k -> new int[1])[0]++;
            where.putIfAbsent(key, post);
        }
        final List<pl.medivalrpg.armia.api.NpcOutpost> out = new ArrayList<>();
        for (final Map.Entry<String, int[]> e : groups.entrySet()) {
            final Pos p = where.get(e.getKey());
            final boolean capital = p.world().equals(c.capitalWorld()) && p.chunkX() == c.capitalX() && p.chunkZ() == c.capitalZ();
            out.add(new pl.medivalrpg.armia.api.NpcOutpost(capital ? "Stolica" : "Posterunek " + p.chunkX() + ", " + p.chunkZ(), p.world(),
                (p.chunkX() << 4) + 8, (p.chunkZ() << 4) + 8, e.getValue()[0]));
        }
        out.sort(Comparator.comparingInt(pl.medivalrpg.armia.api.NpcOutpost::soldiers).reversed());
        return out;
    }

    // ------------------------------------------------------------------ diplomacy

    /** War declaration price of Kraje ({@code war.declare-cost}); {@code ai.war-declare-cost} ≥ 0 only overrides the display. */
    public long warDeclareCost() {
        final long own = this.cfg().aiWarDeclareCost;
        if (own >= 0) {
            return own;
        }
        final org.bukkit.plugin.Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
        return kraje == null ? 0 : Math.max(0, kraje.getConfig().getLong("war.declare-cost", 0));
    }

    public double strength(final UUID country) {
        final Optional<KrajInfo> c = this.kraje().getCountry(country);
        int online = 0;
        int offline = 0;
        if (c.isPresent()) {
            for (final UUID m : c.get().members().keySet()) {
                if (Bukkit.getPlayer(m) != null) {
                    online++;
                } else {
                    offline++;
                }
            }
        }
        return AiDecisions.strength(this.cfg().tuning, this.army.soldierPower(country), online, offline);
    }

    private Optional<UUID> overlordOf(final UUID country) {
        try {
            return this.kraje().getOverlord(country);
        } catch (final LinkageError e) {
            return Optional.empty();
        }
    }

    private Set<UUID> vassalsOf(final UUID country) {
        try {
            return this.kraje().getVassals(country);
        } catch (final LinkageError e) {
            return Set.of();
        }
    }

    /** Partner of our personal union (Kraje API 1.5), empty on older Kraje. */
    private Optional<UUID> unionPartner(final UUID country) {
        try {
            return this.kraje().getUnionPartner(country);
        } catch (final LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    private boolean embassyBetween(final UUID a, final UUID b) {
        try {
            return this.kraje().hasEmbassyBetween(a, b);
        } catch (final LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Our opinion of {@code other} including the union/embassy bonus (used for war and alliance choices). */
    public int bondOpinion(final NpcState st, final UUID other) {
        return pl.medivalrpg.armia.logic.RelationBonds.effectiveOpinion(st.opinionOf(other),
            this.unionPartner(st.country).filter(other::equals).isPresent(), this.embassyBetween(st.country, other));
    }

    /** Kraje's vassal and union rules: no war on our overlord, its allies, our vassals or our union partner. */
    boolean protectedFromUs(final UUID self, final UUID other) {
        if (this.unionPartner(self).filter(other::equals).isPresent()) {
            return true;
        }
        final Optional<UUID> overlord = this.overlordOf(self);
        if (overlord.isPresent() && (overlord.get().equals(other) || this.kraje().getRelation(overlord.get(), other) == Relation.ALLIANCE)) {
            return true;
        }
        return this.vassalsOf(self).contains(other);
    }

    private List<NeighborView> neighbors(final KrajInfo c, final NpcState st) {
        final Set<UUID> ids = new HashSet<>(c.enemies());
        ids.addAll(c.allies());
        final String world = c.capitalWorld();
        if (world != null) {
            for (final long k : this.kraje().getClaims(c.id(), world)) {
                for (final long n : ChunkPlanner.neighbors4(k)) {
                    this.kraje().getCountryAt(world, ChunkPlanner.cx(n), ChunkPlanner.cz(n)).ifPresent(o -> {
                        if (!o.id().equals(c.id())) {
                            ids.add(o.id());
                        }
                    });
                }
            }
        }
        // Not only geographic neighbours: admin targets and whoever holds our land.
        for (final Map.Entry<UUID, String> e : st.stance.entrySet()) {
            if (!"AUTO".equals(e.getValue())) {
                ids.add(e.getKey());
            }
        }
        for (final NpcState.LostChunk l : st.lostChunks) {
            ids.add(l.taker);
        }
        // Background wars: NPC countries within ai.conquest.war-range-chunks are reachable by an expedition.
        if (this.conquest != null) {
            ids.addAll(this.conquest.reachable(c));
        }
        ids.remove(c.id());
        final List<NeighborView> out = new ArrayList<>();
        for (final UUID id : ids) {
            this.kraje().getCountry(id).ifPresent(o -> {
                final boolean shared = o.enemies().stream().anyMatch(c.enemies()::contains);
                out.add(new NeighborView(o.id(), o.name(), this.strength(o.id()), this.bondOpinion(st, o.id()),
                    c.allies().contains(o.id()), c.enemies().contains(o.id()), o.npc(), shared, st.stanceOf(o.id()), st.lostTo(o.id()),
                    this.protectedFromUs(c.id(), o.id())));
            });
        }
        return out;
    }

    public WarView warView(final KrajInfo c, final NpcState st, final UUID enemy) {
        final long now = System.currentTimeMillis();
        final long started = st.warStartedAt.computeIfAbsent(enemy, k -> now);
        return new WarView(this.strength(c.id()), this.strength(enemy), (now - started) / 3_600_000.0,
            st.warLosses.getOrDefault(enemy, 0), st.opinionOf(enemy));
    }

    private boolean cooldownOver(final Map<UUID, Long> map, final UUID other, final long minutes) {
        final Long last = map.get(other);
        return last == null || System.currentTimeMillis() - last >= minutes * 60_000L;
    }

    private boolean cooldownOver(final Map<UUID, Long> map, final UUID other) {
        return this.cooldownOver(map, other, this.cfg().aiProposalCooldownMinutes);
    }

    private void diplomacy(final KrajInfo c, final NpcState st) {
        final AiTuning t = this.cfg().tuning;
        final AiRules r = this.rules();
        final Personality p = st.personality();
        final long now = System.currentTimeMillis();

        // Peace offers.
        for (final UUID enemy : c.enemies()) {
            final WarView w = this.warView(c, st, enemy);
            final NpcStance stance = st.stanceOf(enemy);
            if (Policy.shouldOfferPeace(t, p, w, stance) && this.cooldownOver(st.lastPeaceProposal, enemy)) {
                st.lastPeaceProposal.put(enemy, now);
                final KrajeResult res = this.kraje().propose(c.id(), enemy, Relation.NEUTRAL);
                if (res.ok()) {
                    this.markPending(c.id(), enemy, Relation.NEUTRAL);
                }
                this.log(c.id(), NpcLogCategory.DIPLOMACY, "Propozycja pokoju dla " + this.name(enemy) + " (siła " + fmt(w.ourStrength()) + " vs "
                    + fmt(w.theirStrength()) + ", " + fmt(w.hours()) + " h, straty " + w.losses()
                    + (stance != NpcStance.AUTO ? ", nastawienie " + stance.polish() : "") + "): " + (res.ok() ? "wysłana" : res.status() + " " + res.message()));
            }
        }

        final List<NeighborView> neighbors = this.neighbors(c, st);
        final double ours = this.strength(c.id());

        // War (never in the crisis mode; DEFENSIVE posture never starts one).
        if (!st.crisis) {
            final boolean cooldown = now - st.lastWarDeclaredAt >= this.cfg().aiWarCooldownMinutes * 60_000L;
            final boolean reclaimReady = Crisis.reclaimReady(r, !c.enemies().isEmpty(), st.peaceSince, now);
            final Optional<NeighborView> war = Policy.chooseWarTarget(t, r, p, st.posture(), ours,
                neighbors.stream().filter(n -> !n.npc() || this.plugin.getConfig().getBoolean("ai.war-on-npc", true)).toList(),
                c.enemies().size(), cooldown, reclaimReady, ThreadLocalRandom.current().nextDouble());
            if (war.isPresent()) {
                final NeighborView target = war.get();
                final String why = target.lostChunks() > 0 && reclaimReady ? ", odbicie " + target.lostChunks() + " utraconych chunków"
                    : target.stance() == NpcStance.PREFERRED_TARGET ? ", preferowany cel" : "";
                this.declareWar(c, target.id(), "siła " + fmt(ours) + " vs " + fmt(target.strength()) + why);
            }
        }

        // Alliance.
        final Optional<NeighborView> ally = Policy.chooseAllianceTarget(t, r, p, neighbors, ThreadLocalRandom.current().nextDouble());
        if (ally.isPresent() && this.cooldownOver(st.lastAllianceProposal, ally.get().id())) {
            st.lastAllianceProposal.put(ally.get().id(), now);
            final KrajeResult res = this.kraje().propose(c.id(), ally.get().id(), Relation.ALLIANCE);
            if (res.ok()) {
                this.markPending(c.id(), ally.get().id(), Relation.ALLIANCE);
            }
            this.log(c.id(), NpcLogCategory.DIPLOMACY, "Propozycja sojuszu dla " + ally.get().name() + ": " + (res.ok() ? "wysłana" : res.status() + " " + res.message()));
        }

        // Personal union: the NPC partner leaves a monarch whose country it came to resent (Kraje: no cost, no minimum).
        this.unionPartner(c.id()).ifPresent(home -> {
            if (pl.medivalrpg.armia.logic.RelationBonds.leaveUnion(st.opinionOf(home), p)) {
                KrajeResult res;
                try {
                    res = this.kraje().dissolveUnion(c.id());
                } catch (final LinkageError e) {
                    res = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje bez unii");
                }
                this.log(c.id(), NpcLogCategory.DIPLOMACY, "Zerwanie unii personalnej z " + this.name(home) + " (opinia " + st.opinionOf(home) + "): "
                    + (res.ok() ? "unia rozwiązana" : res.status() + " " + res.message()));
            }
        });

        // Call for help.
        if (st.crisis && now - st.lastHelpRequestAt >= r.helpCooldownMinutes() * 60_000L) {
            st.lastHelpRequestAt = now;
            this.requestHelp(c, st);
        }
    }

    /**
     * Declares war through Kraje (cost, cooldown and vassal rules like the command). NEVER_WAR blocks it. Used by the
     * AI and by the panel ({@code admin.npc.declareWar}).
     */
    public KrajeResult declareWar(final KrajInfo c, final UUID target, final String reason) {
        final NpcState st = this.ensure(c.id());
        if (st.stanceOf(target) == NpcStance.NEVER_WAR) {
            return KrajeResult.fail(KrajeResult.Status.DENIED, "Nastawienie „nigdy wojny” blokuje wojnę z tym krajem.");
        }
        final String targetName = this.name(target);
        final long now = System.currentTimeMillis();
        st.declaredByUs.add(target);
        KrajeResult res;
        try {
            res = this.kraje().declareWar(c.id(), target);
        } catch (final LinkageError e) {
            res = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje nie obsługuje declareWar — zaktualizuj Kraje.");
        }
        if (!res.ok()) {
            st.declaredByUs.remove(target);
            this.log(c.id(), NpcLogCategory.WAR, "Wojna z " + targetName + " niemożliwa: " + res.status() + " " + res.message());
            return res;
        }
        st.lastWarDeclaredAt = now;
        st.warStartedAt.put(target, now);
        st.warLosses.remove(target);
        st.opinion.merge(target, -30, (a, b) -> Opinions.add(a, b));
        this.syncKrajeLedger(c.id());
        this.log(c.id(), NpcLogCategory.WAR, "WOJNA z " + targetName + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
        if (!this.messages.get().raw("ai-war-declared").isEmpty()) {
            Bukkit.broadcast(this.messages.get().get("ai-war-declared",
                Placeholder.unparsed("ruler", c.rulerName() == null ? "Władca" : c.rulerName()),
                Placeholder.component("country", Component.text(c.name(), c.color())),
                Placeholder.unparsed("target", targetName)));
        }
        return res;
    }

    /** Crisis: allies are asked for help. NPC allies decide at once (they may join the war), player allies get a message. */
    private void requestHelp(final KrajInfo c, final NpcState st) {
        if (c.allies().isEmpty() || c.enemies().isEmpty()) {
            return;
        }
        UUID enemy = null;
        double strongest = -1;
        for (final UUID e : c.enemies()) {
            final double s = this.strength(e);
            if (s > strongest) {
                strongest = s;
                enemy = e;
            }
        }
        final UUID mainEnemy = enemy;
        final String enemyName = this.name(mainEnemy);
        final List<String> joined = new ArrayList<>();
        for (final UUID allyId : c.allies()) {
            final Optional<KrajInfo> oa = this.kraje().getCountry(allyId);
            if (oa.isEmpty() || oa.get().enemies().contains(mainEnemy)) {
                continue;
            }
            final KrajInfo ally = oa.get();
            if (ally.npc()) {
                if (!this.isActive(allyId)) {
                    continue;
                }
                final NpcState as = this.ensure(allyId);
                final boolean join = Policy.joinWar(this.rules(), as.personality(), as.posture(), as.opinionOf(c.id()), as.opinionOf(mainEnemy),
                    as.stanceOf(mainEnemy), this.protectedFromUs(allyId, mainEnemy), this.strength(allyId), strongest);
                if (join) {
                    final KrajeResult r = this.declareWar(ally, mainEnemy, "na prośbę sojusznika " + c.name());
                    if (r.ok()) {
                        joined.add(ally.name());
                        as.opinion.merge(c.id(), 5, (a, b) -> Opinions.add(a, b));
                        continue;
                    }
                }
                this.log(allyId, NpcLogCategory.DIPLOMACY, "Prośba o pomoc od " + c.name() + " w wojnie z " + enemyName + ": odmowa");
            } else {
                this.army.notifyCountry(allyId, "ai-help-request",
                    Placeholder.unparsed("ruler", c.rulerName() == null ? "Władca" : c.rulerName()),
                    Placeholder.component("country", Component.text(c.name(), c.color())),
                    Placeholder.unparsed("enemy", enemyName));
            }
        }
        this.log(c.id(), NpcLogCategory.DIPLOMACY, "Prośba o pomoc do sojuszników w wojnie z " + enemyName
            + (joined.isEmpty() ? "" : " — dołączyli: " + String.join(", ", joined)));
    }

    private void markPending(final UUID from, final UUID to, final Relation relation) {
        final long now = System.currentTimeMillis();
        final NpcState a = this.states.get(from);
        if (a != null) {
            a.pending.put(to, new NpcState.Pending(relation.name(), true, now));
        }
        final NpcState b = this.states.get(to);
        if (b != null) {
            b.pending.put(from, new NpcState.Pending(relation.name(), false, now));
        }
    }

    private void clearPending(final UUID a, final UUID b) {
        final NpcState sa = this.states.get(a);
        if (sa != null) {
            sa.pending.remove(b);
        }
        final NpcState sb = this.states.get(b);
        if (sb != null) {
            sb.pending.remove(a);
        }
    }

    private void expirePending(final NpcState st) {
        final long limit = System.currentTimeMillis() - this.cfg().aiProposalExpiryMinutes * 60_000L;
        st.pending.values().removeIf(p -> p == null || p.at < limit);
        if (st.capitulatingTo != null && st.capitulatingAt < System.currentTimeMillis() - 15 * 60_000L) {
            st.capitulatingTo = null;
        }
    }

    /** RelationProposalEvent: false when the proposal was already answered synchronously (panel) — skip the delayed answer. */
    public boolean consumeDirectAnswer(final UUID from, final UUID to) {
        return this.answeredDirectly.remove(from + ">" + to);
    }

    /** A proposal arrived for an NPC country whose AI is paused: remember it as pending. */
    public void rememberProposal(final UUID from, final UUID to, final Relation proposed) {
        this.markPending(from, to, proposed);
    }

    /**
     * Answer to a proposal made to an NPC country (called one tick after {@code RelationProposalEvent}, or at once by
     * {@link #proposeFromNpc}). Null when the AI did not answer (inactive).
     */
    public Boolean answerProposal(final UUID to, final UUID from, final Relation proposed) {
        final Optional<KrajInfo> oc = this.kraje().getCountry(to);
        final Optional<KrajInfo> of = this.kraje().getCountry(from);
        if (oc.isEmpty() || of.isEmpty() || !this.isActive(to)) {
            return null;
        }
        final KrajInfo c = oc.get();
        final NpcState st = this.ensure(to);
        final AiTuning t = this.cfg().tuning;
        final NpcStance stance = st.stanceOf(from);
        final boolean atWar = c.enemies().contains(from);
        final boolean accept;
        final String why;
        if (proposed == Relation.ALLIANCE) {
            final boolean shared = of.get().enemies().stream().anyMatch(c.enemies()::contains);
            final AllianceView a = new AllianceView(this.strength(to), this.strength(from), st.opinionOf(from), atWar, shared, c.allies().size());
            accept = Policy.acceptAlliance(t, this.rules(), st.personality(), a, stance);
            why = "wynik " + fmt(Policy.allianceScore(this.rules(), st.personality(), a, stance)) + "/" + fmt(t.allianceThreshold())
                + (stance != NpcStance.AUTO ? ", nastawienie " + stance.polish() : "");
        } else if (proposed == Relation.NEUTRAL && atWar) {
            final WarView w = this.warView(c, st, from);
            final boolean policy = Policy.acceptPeace(t, st.personality(), w, stance);
            final boolean exhausted = !policy && this.conquest != null && this.conquest.acceptsPeace(to);
            accept = policy || exhausted;
            why = exhausted ? "zmęczenie wojną " + Math.round(this.conquest.exhaustion(to)) + "/100"
                : w.hours() < t.minWarHours() ? "wojna trwa za krótko"
                : "wynik " + fmt(Policy.peaceScore(t, st.personality(), w, stance)) + "/" + fmt(t.peaceThreshold());
        } else {
            accept = true;
            why = "brak powodu do odmowy";
        }
        final KrajeResult r = this.kraje().respondToProposal(to, from, proposed, accept);
        this.clearPending(to, from);
        this.log(to, NpcLogCategory.DIPLOMACY, "Odpowiedź na " + (proposed == Relation.ALLIANCE ? "sojusz" : "pokój") + " od " + of.get().name() + ": "
            + (accept ? "PRZYJĘTO" : "odrzucono") + " (" + why + ")" + (r.ok() ? "" : " — Kraje: " + r.status() + " " + r.message()));
        final NpcState fromState = this.states.get(from);
        if (fromState != null && fromState != st) {
            this.log(from, NpcLogCategory.DIPLOMACY, c.name() + (accept ? " przyjmuje " : " odrzuca ") + (proposed == Relation.ALLIANCE ? "sojusz" : "pokój"));
        }
        this.army.notifyCountry(from, accept ? "ai-reply-accept" : "ai-reply-refuse",
            Placeholder.unparsed("ruler", c.rulerName() == null ? "Władca" : c.rulerName()),
            Placeholder.component("country", Component.text(c.name(), c.color())),
            Placeholder.unparsed("proposal", proposed == Relation.ALLIANCE ? "sojusz" : "pokój"));
        return accept && r.ok();
    }

    /** Panel: the NPC country proposes through Kraje; another active NPC country answers at once. */
    public NpcReply proposeFromNpc(final KrajInfo c, final UUID target, final Relation relation) {
        return this.proposeFromNpc(c, target, relation, "Panel");
    }

    /** Same as the panel path; {@code origin} labels the log line (e.g. "Zmęczenie wojną" for background wars). */
    public NpcReply proposeFromNpc(final KrajInfo c, final UUID target, final Relation relation, final String origin) {
        final Optional<KrajInfo> ot = this.kraje().getCountry(target);
        if (ot.isEmpty() || target.equals(c.id())) {
            return NpcReply.fail("Nieprawidłowy kraj docelowy.");
        }
        if (relation != Relation.ALLIANCE && relation != Relation.NEUTRAL) {
            return NpcReply.fail("Można proponować tylko sojusz albo pokój.");
        }
        final KrajInfo other = ot.get();
        final boolean direct = other.npc() && this.isActive(target);
        final String key = c.id() + ">" + target;
        if (direct) {
            this.answeredDirectly.add(key);
        }
        final KrajeResult r = this.kraje().propose(c.id(), target, relation);
        if (!r.ok()) {
            this.answeredDirectly.remove(key);
            return NpcReply.fail(r.message().isBlank() ? "Kraje odrzuciło propozycję (" + r.status() + ")." : r.message());
        }
        final String what = relation == Relation.ALLIANCE ? "sojuszu" : "pokoju";
        this.log(c.id(), NpcLogCategory.DIPLOMACY, origin + ": propozycja " + what + " dla " + other.name());
        if (!direct) {
            this.answeredDirectly.remove(key);
            this.markPending(c.id(), target, relation);
            return new NpcReply(true, "Wysłano propozycję " + what + " do: " + other.name() + (other.npc() ? " (AI wstrzymana — czeka)" : " (czeka na władcę)"), null);
        }
        final Boolean accepted = this.answerProposal(target, c.id(), relation);
        this.answeredDirectly.remove(key);
        if (accepted == null) {
            this.markPending(c.id(), target, relation);
            return new NpcReply(true, "Wysłano propozycję " + what + " do: " + other.name(), null);
        }
        return new NpcReply(true, other.name() + (accepted ? " przyjmuje" : " odrzuca") + " propozycję " + what + ".", accepted);
    }

    // ------------------------------------------------------------------ wave 17: unions, embassies, railway consent

    /**
     * UnionProposalEvent (only fired when Kraje's {@code union.npc-auto-refuse: false}): the NPC country answers by
     * personality, opinion, alliance and relative strength. Called one tick after the event.
     */
    public void answerUnion(final UUID partner, final UUID home) {
        final Optional<KrajInfo> op = this.kraje().getCountry(partner);
        final Optional<KrajInfo> oh = this.kraje().getCountry(home);
        if (op.isEmpty() || oh.isEmpty() || !this.isActive(partner)) {
            return;
        }
        final NpcState st = this.ensure(partner);
        final KrajInfo c = op.get();
        final double ratio = this.strength(home) / Math.max(0.5, this.strength(partner));
        final int opinion = this.bondOpinion(st, home);
        final long now = System.currentTimeMillis();
        final boolean fresh = pl.medivalrpg.armia.logic.RelationBonds.requestCooldownOver(this.bondAnsweredAt.get("union:" + home + ">" + partner), now);
        this.bondAnsweredAt.put("union:" + home + ">" + partner, now);
        final boolean accept = fresh && pl.medivalrpg.armia.logic.RelationBonds.acceptUnion(st.personality(), opinion, c.enemies().contains(home),
            c.allies().contains(home), ratio, st.stanceOf(home));
        KrajeResult r;
        try {
            r = this.kraje().respondUnion(partner, home, accept);
        } catch (final LinkageError e) {
            r = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje bez unii personalnych");
        }
        if (accept && r.ok()) {
            st.opinion.merge(home, 10, (a, b) -> Opinions.add(a, b));
        }
        this.log(partner, NpcLogCategory.DIPLOMACY, "Unia personalna proponowana przez " + oh.get().name() + ": " + (accept ? "PRZYJĘTO" : "odrzucono")
            + (fresh ? " (opinia " + opinion + ", siła " + fmt(ratio) + "×" + (c.allies().contains(home) ? "" : ", brak sojuszu") + ")"
                : " (ponowna prośba przed upływem " + pl.medivalrpg.armia.logic.RelationBonds.REQUEST_COOLDOWN_MINUTES + " min)")
            + (r.ok() ? "" : " — Kraje: " + r.status() + " " + r.message()));
        this.army.notifyCountry(home, accept && r.ok() ? "ai-reply-accept" : "ai-reply-refuse",
            Placeholder.unparsed("ruler", c.rulerName() == null ? "Władca" : c.rulerName()),
            Placeholder.component("country", Component.text(c.name(), c.color())),
            Placeholder.unparsed("proposal", "unię personalną"));
    }

    /** EmbassyRequestEvent (only fired when Kraje's {@code embassy.npc-auto-decide: false}). */
    public void answerEmbassy(final UUID host, final UUID guest) {
        final Optional<KrajInfo> oh = this.kraje().getCountry(host);
        final Optional<KrajInfo> og = this.kraje().getCountry(guest);
        if (oh.isEmpty() || og.isEmpty() || !this.isActive(host)) {
            return;
        }
        final NpcState st = this.ensure(host);
        final int opinion = this.bondOpinion(st, guest);
        final long now = System.currentTimeMillis();
        final boolean fresh = pl.medivalrpg.armia.logic.RelationBonds.requestCooldownOver(this.bondAnsweredAt.get("embassy:" + guest + ">" + host), now)
            || oh.get().allies().contains(guest);
        this.bondAnsweredAt.put("embassy:" + guest + ">" + host, now);
        final boolean accept = fresh && pl.medivalrpg.armia.logic.RelationBonds.acceptEmbassy(st.personality(), opinion,
            oh.get().enemies().contains(guest), oh.get().allies().contains(guest), st.stanceOf(guest));
        KrajeResult r;
        try {
            r = this.kraje().respondEmbassy(host, guest, accept);
        } catch (final LinkageError e) {
            r = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje bez ambasad");
        }
        if (accept && r.ok()) {
            st.opinion.merge(guest, 5, (a, b) -> Opinions.add(a, b));
        }
        this.log(host, NpcLogCategory.DIPLOMACY, "Ambasada kraju " + og.get().name() + ": " + (accept ? "PRZYJĘTO" : "odrzucono")
            + (fresh ? " (opinia " + opinion + ")" : " (ponowna prośba przed upływem godziny)") + (r.ok() ? "" : " — Kraje: " + r.status() + " " + r.message()));
        this.army.notifyCountry(guest, accept && r.ok() ? "ai-reply-accept" : "ai-reply-refuse",
            Placeholder.unparsed("ruler", oh.get().rulerName() == null ? "Władca" : oh.get().rulerName()),
            Placeholder.component("country", Component.text(oh.get().name(), oh.get().color())),
            Placeholder.unparsed("proposal", "ambasadę"));
    }

    /**
     * Railway consent of an NPC host (narrow bridge for Kolej, {@code ArmiaPlugin#npcRailConsent}). {@code applicant} is
     * the applicant's country or null (the royal carrier is managed by a player personally — treated as neutral).
     * Logged; never blocks forever.
     */
    public pl.medivalrpg.armia.logic.RelationBonds.RailDecision railConsent(final UUID host, final UUID applicant, final long baseFee) {
        final Optional<KrajInfo> oh = this.kraje().getCountry(host);
        if (oh.isEmpty() || !oh.get().npc()) {
            return new pl.medivalrpg.armia.logic.RelationBonds.RailDecision(pl.medivalrpg.armia.logic.RelationBonds.RailAnswer.REFUSE, 0, "to nie jest kraj NPC");
        }
        final NpcState st = this.ensure(host);
        final KrajInfo h = oh.get();
        final boolean known = applicant != null && !applicant.equals(host);
        final var d = pl.medivalrpg.armia.logic.RelationBonds.railConsent(st.personality(), known ? st.opinionOf(applicant) : 0,
            known && h.enemies().contains(applicant), known && h.allies().contains(applicant),
            known && this.unionPartner(host).filter(applicant::equals).isPresent(), known && this.embassyBetween(host, applicant),
            known ? st.stanceOf(applicant) : NpcStance.AUTO, baseFee);
        this.log(host, NpcLogCategory.DIPLOMACY, "Kolej: zgoda dla " + (known ? this.name(applicant) : "przewoźnika królewskiego") + " — "
            + switch (d.answer()) {
                case ACCEPT -> "TAK";
                case ACCEPT_FEE -> "TAK za opłatą " + this.services.money(d.fee());
                case REFUSE -> "NIE";
            } + " (" + d.reason() + ")");
        return d;
    }

    // ------------------------------------------------------------------ capitulation & vassals

    /** Kraje: somebody offered to capitulate to an NPC country (answered one tick later). */
    public void answerCapitulation(final UUID winner, final UUID loser, final int days, final long estimatedTribute, final long tributeMin) {
        final Optional<KrajInfo> ow = this.kraje().getCountry(winner);
        final Optional<KrajInfo> ol = this.kraje().getCountry(loser);
        if (ow.isEmpty() || ol.isEmpty()) {
            return;
        }
        final NpcState st = this.ensure(winner);
        if (!this.isActive(winner)) {
            this.log(winner, NpcLogCategory.DIPLOMACY, "Oferta kapitulacji od " + ol.get().name() + " czeka (AI wstrzymana)");
            return;
        }
        final WarView w = this.warView(ow.get(), st, loser);
        final Capitulation.OfferView v = new Capitulation.OfferView(w.ourStrength(), w.theirStrength(), st.opinionOf(loser),
            estimatedTribute, tributeMin, w.hours(), w.losses(), st.stanceOf(loser));
        final Personality p = st.personality();
        final boolean greedyRefuses = Capitulation.greedyRefuses(this.rules(), p, v);
        final boolean byRules = Capitulation.accept(this.cfg().tuning, this.rules(), p, v);
        // Background wars: a winner exhausted by the war takes a vassal with tribute instead of fighting on.
        final boolean exhausted = !byRules && !greedyRefuses && this.conquest != null && this.conquest.acceptsCapitulation(winner);
        final boolean accept = byRules || exhausted;
        KrajeResult r;
        try {
            r = this.kraje().respondCapitulation(winner, loser, accept);
        } catch (final LinkageError e) {
            r = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje bez kapitulacji");
        }
        final String why = exhausted ? "zmęczenie wojną " + Math.round(this.conquest.exhaustion(winner)) + "/100 — wasal i trybut zamiast dalszej wojny"
            : greedyRefuses
            ? "chciwy władca: trybut " + this.services.money(Math.max(estimatedTribute, tributeMin)) + " za niski, oczekuje " + this.services.money(Capitulation.fairTribute(this.rules(), v))
            : "wynik " + fmt(Capitulation.acceptScore(this.cfg().tuning, p, v)) + "/" + fmt(this.rules().capitulationThreshold())
                + ", siła " + fmt(v.ourStrength()) + " vs " + fmt(v.theirStrength());
        this.log(winner, NpcLogCategory.DIPLOMACY, "Kapitulacja " + ol.get().name() + " (" + days + " dni, trybut ok. "
            + this.services.money(estimatedTribute) + "/dzień): " + (accept ? "PRZYJĘTO" : "odrzucono") + " (" + why + ")"
            + (r.ok() ? "" : " — Kraje: " + r.status() + " " + r.message()));
        final KrajInfo c = ow.get();
        this.army.notifyCountry(loser, accept ? "ai-capitulation-accept" : "ai-capitulation-refuse",
            Placeholder.unparsed("ruler", c.rulerName() == null ? "Władca" : c.rulerName()),
            Placeholder.component("country", Component.text(c.name(), c.color())),
            Placeholder.unparsed("reason", greedyRefuses ? " — żąda wyższego trybutu" : ""));
    }

    private void maybeCapitulate(final KrajInfo c, final NpcState st) {
        if (c.enemies().isEmpty() || st.capitulatingTo != null) {
            return;
        }
        final boolean vassal = this.overlordOf(c.id()).isPresent();
        final double ours = this.strength(c.id());
        UUID enemy = null;
        double strongest = -1;
        for (final UUID e : c.enemies()) {
            final double s = this.strength(e);
            if (s > strongest) {
                strongest = s;
                enemy = e;
            }
        }
        if (enemy == null) {
            return;
        }
        final WarView w = this.warView(c, st, enemy);
        final Capitulation.LosingView v = new Capitulation.LosingView(ours, strongest, w.hours(), st.chunksLostInWar.getOrDefault(enemy, 0),
            c.chunkCount(), st.treasuryAtWarStart.getOrDefault(enemy, 0L), this.balance(c.id()), vassal,
            this.cooldownOver(st.lastCapitulationOffer, enemy, this.rules().capitulateCooldownMinutes()));
        if (!Capitulation.shouldCapitulate(this.cfg().tuning, this.rules(), st.personality(), v)) {
            return;
        }
        st.lastCapitulationOffer.put(enemy, System.currentTimeMillis());
        KrajeResult r;
        try {
            r = this.kraje().offerCapitulation(c.id(), enemy, 0);
        } catch (final LinkageError e) {
            r = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje bez kapitulacji");
        }
        if (r.ok()) {
            st.capitulatingTo = enemy;
            st.capitulatingAt = System.currentTimeMillis();
        }
        this.log(c.id(), NpcLogCategory.DIPLOMACY, "Propozycja KAPITULACJI dla " + this.name(enemy) + " (siła " + fmt(ours) + " vs " + fmt(strongest)
            + ", utracone chunki " + v.chunksLostInWar() + ", skarbiec −" + Math.round(v.treasuryDrop() * 100) + "%): "
            + (r.ok() ? "wysłana" : r.status() + " " + r.message()));
    }

    private void vassalage(final KrajInfo c, final NpcState st, final long wages) {
        final long now = System.currentTimeMillis();
        final Optional<UUID> overlord = this.overlordOf(c.id());
        if (overlord.isPresent() && now - this.buyoutTriedAt.getOrDefault(c.id(), 0L) >= HOUR) {
            long cost;
            try {
                cost = this.kraje().buyoutCost(c.id());
            } catch (final LinkageError e) {
                cost = -1;
            }
            final long balance = this.balance(c.id());
            final long reserve = AiDecisions.reserve(this.cfg().tuning, st.personality(), wages);
            if (Capitulation.shouldBuyout(this.rules(), cost, balance, reserve)) {
                this.buyoutTriedAt.put(c.id(), now);
                KrajeResult r;
                try {
                    r = this.kraje().buyout(c.id());
                } catch (final LinkageError e) {
                    r = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje bez wasalstwa");
                }
                this.syncKrajeLedger(c.id());
                this.log(c.id(), NpcLogCategory.DIPLOMACY, "Wykup z wasalstwa u " + this.name(overlord.get()) + " za " + this.services.money(cost) + ": "
                    + (r.ok() ? "WOLNOŚĆ" : r.status() + " " + r.message()));
            }
        }
        for (final UUID vassal : this.vassalsOf(c.id())) {
            if (Capitulation.shouldRelease(this.rules(), st.personality(), st.opinionOf(vassal))) {
                KrajeResult r;
                try {
                    r = this.kraje().releaseVassal(c.id(), vassal);
                } catch (final LinkageError e) {
                    r = KrajeResult.fail(KrajeResult.Status.DENIED, "Kraje bez wasalstwa");
                }
                this.log(c.id(), NpcLogCategory.DIPLOMACY, "Zwolnienie wasala " + this.name(vassal) + " (opinia " + st.opinionOf(vassal) + "): "
                    + (r.ok() ? "zwolniony" : r.status() + " " + r.message()));
            }
        }
    }

    public void onVassalageStart(final UUID vassal, final UUID overlord, final long until) {
        final NpcState v = this.states.get(vassal);
        if (v != null) {
            v.capitulatingTo = null;
            v.opinion.merge(overlord, -10, (a, b) -> Opinions.add(a, b));
            this.log(vassal, NpcLogCategory.DIPLOMACY, "Zostajemy wasalem " + this.name(overlord) + " (do " + LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(until), java.time.ZoneId.systemDefault()).format(TIME) + ")");
        }
        final NpcState o = this.states.get(overlord);
        if (o != null) {
            o.opinion.merge(vassal, 10, (a, b) -> Opinions.add(a, b));
            this.log(overlord, NpcLogCategory.DIPLOMACY, this.name(vassal) + " zostaje naszym wasalem");
        }
    }

    public void onVassalageEnd(final UUID vassal, final UUID overlord, final String reason) {
        final String why = switch (reason) {
            case "EXPIRED" -> "minął czas";
            case "BOUGHT_OUT" -> "wykup";
            case "RELEASED" -> "zwolnienie";
            case "FALL" -> "upadek";
            case "WAR" -> "wojna";
            default -> reason.toLowerCase(Locale.ROOT);
        };
        if (this.states.containsKey(vassal)) {
            this.log(vassal, NpcLogCategory.DIPLOMACY, "Koniec wasalstwa u " + this.name(overlord) + " (" + why + ")");
        }
        if (this.states.containsKey(overlord)) {
            this.log(overlord, NpcLogCategory.DIPLOMACY, "Koniec wasalstwa " + this.name(vassal) + " (" + why + ")");
        }
    }

    // ------------------------------------------------------------------ tributes

    /** Daily payments of tribute agreements; expired demands. */
    private void tributes(final KrajInfo c, final NpcState st) {
        final long now = System.currentTimeMillis();
        for (final NpcState.TributeDeal d : new ArrayList<>(st.tributes)) {
            if (now >= d.until) {
                st.tributes.remove(d);
                this.log(c.id(), NpcLogCategory.DIPLOMACY, "Koniec trybutu " + (d.pays ? "dla " : "od ") + this.name(d.other));
                continue;
            }
            final boolean otherIsNpc = this.kraje().getCountry(d.other).map(KrajInfo::npc).orElse(false);
            // The payer collects: an NPC pays its own deals; a player country's deals are collected by the NPC receiver.
            if ((d.pays || !otherIsNpc) && now - d.lastPaidAt >= Ledger.DAY) {
                this.payTribute(c.id(), st, d);
            }
        }
        final long expiry = this.rules().tributeExpiryMinutes() * 60_000L;
        st.tributeDemands.entrySet().removeIf(e -> {
            if (now - e.getValue().at < expiry) {
                return false;
            }
            st.opinion.merge(e.getKey(), -this.rules().tributeRefusedOpinion(), (a, b) -> Opinions.add(a, b));
            this.log(c.id(), NpcLogCategory.DIPLOMACY, this.name(e.getKey()) + " zignorował żądanie trybutu — opinia spada");
            return true;
        });
    }

    /** One payment of a deal of {@code self}: from the payer's treasury to the receiver's. */
    private void payTribute(final UUID self, final NpcState st, final NpcState.TributeDeal d) {
        final UUID payer = d.pays ? self : d.other;
        final UUID receiver = d.pays ? d.other : self;
        final String payerName = this.name(payer);
        final String receiverName = this.name(receiver);
        final TxResult r = this.services.skarbiec().transfer(this.services.treasury(payer), this.services.treasury(receiver), d.amount,
            "Trybut " + payerName + " → " + receiverName);
        d.lastPaidAt = System.currentTimeMillis();
        if (r.ok()) {
            d.missed = 0;
            this.flow(payer, "tribute", -d.amount, receiverName, "Trybut dla: " + receiverName);
            this.flow(receiver, "tribute", d.amount, payerName, "Trybut od: " + payerName);
            this.army.notifyCountry(payer, "ai-tribute-collected", Placeholder.unparsed("amount", this.services.money(d.amount)),
                Placeholder.unparsed("country", receiverName));
            return;
        }
        d.missed++;
        this.army.notifyCountry(payer, "ai-tribute-unpaid", Placeholder.unparsed("amount", this.services.money(d.amount)),
            Placeholder.unparsed("country", receiverName));
        if (d.missed >= 2) {
            st.tributes.remove(d);
            final NpcState other = this.states.get(d.other);
            if (other != null) {
                other.tributes.removeIf(x -> self.equals(x.other));
            }
            final NpcState receiverState = this.states.get(receiver);
            if (receiverState != null) {
                receiverState.opinion.merge(payer, -2 * this.rules().tributeRefusedOpinion(), (a, b) -> Opinions.add(a, b));
                this.log(receiver, NpcLogCategory.DIPLOMACY, payerName + " nie płaci trybutu — umowa zerwana (powód do wojny)");
            }
            if (this.states.containsKey(payer)) {
                this.log(payer, NpcLogCategory.DIPLOMACY, "Nie stać nas na trybut dla " + receiverName + " — umowa zerwana");
            }
        }
    }

    /** Expected daily income of a country: NPC taxes + AI income. */
    long dailyIncome(final KrajInfo c, final NpcState st) {
        return this.services.population().dailyTax(c.id()) + AiDecisions.income(this.cfg().tuning, st.personality(), c.chunkCount(), 1.0);
    }

    /** Panel: the NPC country demands a daily tribute from {@code target}. */
    public NpcReply demandTribute(final KrajInfo c, final UUID target, final long amount) {
        final Optional<KrajInfo> ot = this.kraje().getCountry(target);
        if (ot.isEmpty() || target.equals(c.id())) {
            return NpcReply.fail("Nieprawidłowy kraj docelowy.");
        }
        final AiRules r = this.rules();
        if (amount < 1 || amount > r.tributeMax()) {
            return NpcReply.fail("Trybut musi wynosić od 1 md do " + this.services.money(r.tributeMax()) + " dziennie.");
        }
        final NpcState st = this.ensure(c.id());
        final KrajInfo other = ot.get();
        if (st.tributeWith(target) != null) {
            return NpcReply.fail("Z krajem " + other.name() + " obowiązuje już umowa o trybut.");
        }
        final long now = System.currentTimeMillis();
        final String money = this.services.money(amount);
        if (!other.npc()) {
            if (st.tributeDemands.containsKey(target)) {
                return NpcReply.fail(other.name() + " jeszcze nie odpowiedział na poprzednie żądanie.");
            }
            st.tributeDemands.put(target, new NpcState.Demand(amount, now));
            final String quoted = com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(c.name());
            final TagResolver[] tags = {
                Placeholder.unparsed("ruler", c.rulerName() == null ? "Władca" : c.rulerName()),
                Placeholder.component("country", Component.text(c.name(), c.color())),
                Placeholder.unparsed("amount", money),
                Placeholder.unparsed("days", Integer.toString(r.tributeDays())),
                Placeholder.unparsed("minutes", Long.toString(r.tributeExpiryMinutes())),
                Placeholder.styling("accept", ClickEvent.runCommand("/armia trybut przyjmij " + quoted)),
                Placeholder.styling("refuse", ClickEvent.runCommand("/armia trybut odrzuc " + quoted))};
            this.army.notifyCountry(target, "ai-tribute-demand", tags);
            this.log(c.id(), NpcLogCategory.DIPLOMACY, "Żądanie trybutu " + money + "/dzień od " + other.name() + " (czeka na władcę)");
            return new NpcReply(true, "Wysłano żądanie trybutu do: " + other.name() + " (czeka na władcę).", null);
        }
        final NpcState ts = this.ensure(target);
        final boolean accept = Policy.acceptTributeDemand(r, ts.personality(), this.strength(c.id()), this.strength(target), ts.opinionOf(c.id()),
            ts.stanceOf(c.id()), amount, this.dailyIncome(other, ts), this.balance(target), r.tributeDays());
        if (!accept) {
            st.opinion.merge(target, -r.tributeRefusedOpinion(), (a, b) -> Opinions.add(a, b));
            ts.opinion.merge(c.id(), -r.tributeRefusedOpinion() / 2, (a, b) -> Opinions.add(a, b));
            this.log(c.id(), NpcLogCategory.DIPLOMACY, "Żądanie trybutu " + money + "/dzień od " + other.name() + ": ODMOWA");
            this.log(target, NpcLogCategory.DIPLOMACY, c.name() + " żąda trybutu " + money + "/dzień: odmowa");
            return new NpcReply(true, other.name() + " odrzuca żądanie trybutu.", false);
        }
        this.startTribute(c.id(), target, amount);
        this.log(c.id(), NpcLogCategory.DIPLOMACY, "Żądanie trybutu " + money + "/dzień od " + other.name() + ": PRZYJĘTE na " + r.tributeDays() + " dni");
        this.log(target, NpcLogCategory.DIPLOMACY, "Ulegamy " + c.name() + ": trybut " + money + "/dzień przez " + r.tributeDays() + " dni");
        return new NpcReply(true, other.name() + " będzie płacić trybut " + money + " dziennie przez " + r.tributeDays() + " dni.", true);
    }

    /** Creates the agreement in both states and collects the first payment at once. */
    void startTribute(final UUID receiver, final UUID payer, final long amount) {
        final long now = System.currentTimeMillis();
        final long until = now + this.rules().tributeDays() * Ledger.DAY;
        final NpcState rs = this.ensure(receiver);
        final NpcState.TributeDeal receive = new NpcState.TributeDeal(payer, amount, false, until, 0);
        rs.tributes.add(receive);
        final NpcState ps = this.states.get(payer);
        if (ps != null) {
            final NpcState.TributeDeal pay = new NpcState.TributeDeal(receiver, amount, true, until, 0);
            ps.tributes.add(pay);
            this.payTribute(payer, ps, pay);
            receive.lastPaidAt = pay.lastPaidAt;
        } else {
            this.payTribute(receiver, rs, receive);
        }
    }

    /** {@code /armia trybut przyjmij|odrzuc <kraj>}: the ruler of a player country answers a tribute demand. */
    public void answerTributeDemand(final Player player, final KrajInfo npc, final boolean accept) {
        final Messages m = this.messages.get();
        final Optional<KrajInfo> own = this.kraje().getCountryOf(player.getUniqueId());
        if (own.isEmpty()) {
            m.send(player, "no-country");
            return;
        }
        final Rank rank = this.kraje().getRank(player.getUniqueId()).orElse(Rank.CITIZEN);
        if (!rank.atLeast(this.cfg().diplomacyRank)) {
            m.send(player, "no-rank", Placeholder.unparsed("rank", this.cfg().diplomacyRank.displayName()));
            return;
        }
        final NpcState st = this.states.get(npc.id());
        final NpcState.Demand d = st == null ? null : st.tributeDemands.get(own.get().id());
        if (d == null) {
            m.send(player, "ai-tribute-no-demand", Placeholder.unparsed("country", npc.name()));
            return;
        }
        st.tributeDemands.remove(own.get().id());
        final String money = this.services.money(d.amount);
        if (!accept) {
            st.opinion.merge(own.get().id(), -this.rules().tributeRefusedOpinion(), (a, b) -> Opinions.add(a, b));
            this.log(npc.id(), NpcLogCategory.DIPLOMACY, own.get().name() + " odrzuca żądanie trybutu " + money + " — opinia spada");
            m.send(player, "ai-tribute-refused", Placeholder.unparsed("country", npc.name()));
            return;
        }
        this.startTribute(npc.id(), own.get().id(), d.amount);
        st.opinion.merge(own.get().id(), 10, (a, b) -> Opinions.add(a, b));
        this.log(npc.id(), NpcLogCategory.DIPLOMACY, own.get().name() + " przyjmuje trybut " + money + "/dzień na " + this.rules().tributeDays() + " dni");
        m.send(player, "ai-tribute-accepted", Placeholder.unparsed("country", npc.name()), Placeholder.unparsed("amount", money),
            Placeholder.unparsed("days", Integer.toString(this.rules().tributeDays())));
    }

    // ------------------------------------------------------------------ sieges

    /** Enemies in siege order (admin target, reclaim, preferred, weakest). */
    public List<UUID> siegeOrder(final KrajInfo c, final NpcState st) {
        final double ours = this.strength(c.id());
        final List<Policy.EnemyView> enemies = new ArrayList<>();
        for (final UUID e : c.enemies()) {
            enemies.add(new Policy.EnemyView(e, ours / Math.max(0.5, this.strength(e)), st.lostTo(e), st.stanceOf(e)));
        }
        return Policy.siegeOrder(st.siegeTarget, enemies);
    }

    /** "autoTarget": the enemy besieged first, or at peace the best war candidate. */
    public Optional<UUID> autoTarget(final KrajInfo c, final NpcState st) {
        if (!c.enemies().isEmpty()) {
            final double ours = this.strength(c.id());
            final List<Policy.EnemyView> enemies = new ArrayList<>();
            for (final UUID e : c.enemies()) {
                enemies.add(new Policy.EnemyView(e, ours / Math.max(0.5, this.strength(e)), st.lostTo(e), st.stanceOf(e)));
            }
            final List<UUID> order = Policy.siegeOrder(null, enemies);
            return order.isEmpty() ? Optional.empty() : Optional.of(order.get(0));
        }
        final long now = System.currentTimeMillis();
        return Policy.bestWarTarget(this.cfg().tuning, this.rules(), st.personality(), st.posture(), this.strength(c.id()),
            this.neighbors(c, st), Crisis.reclaimReady(this.rules(), false, st.peaceSince, now)).map(NeighborView::id);
    }

    private void siege(final KrajInfo c, final NpcState st) {
        final ArmiaConfig cfg = this.cfg();
        if (!cfg.aiSieges) {
            return;
        }
        if (st.siegeId != null) {
            final boolean running = this.kraje().getSieges().stream().anyMatch(s -> s.id().equals(st.siegeId));
            if (running) {
                return;
            }
            this.releaseSiege(st);
        }
        if (this.conquest != null && this.conquest.hasActiveOffensive(c.id())) {
            return; // one offensive per country: the background one is being fought
        }
        final NpcPosture posture = st.posture();
        final long cooldown = BudgetPlan.siegeCooldown(this.rules(), cfg.aiSiegeCooldownMinutes, posture);
        if (c.enemies().isEmpty() || System.currentTimeMillis() - st.lastSiegeAt < cooldown * 60_000L) {
            return;
        }
        final World w = Bukkit.getWorld(c.capitalWorld());
        if (w == null) {
            return;
        }
        final List<Soldier> ready = new ArrayList<>();
        for (final Soldier s : this.army.registry().ofCountry(c.id())) {
            if (s.attached() && s.record().commander == null && !s.record().siege && s.brain().getWorld() == w) {
                ready.add(s);
            }
        }
        if (ready.size() < (posture == NpcPosture.OFFENSIVE ? 2 : 3)) {
            return;
        }
        final Set<Long> own = this.kraje().getClaims(c.id(), w.getName());
        final List<Long> front = ChunkPlanner.enemyFront(own,
            k -> this.kraje().getCountryAt(w.getName(), ChunkPlanner.cx(k), ChunkPlanner.cz(k)).map(o -> c.enemies().contains(o.id())).orElse(false));
        // Order: our lost chunks first (by enemy priority), then the rest of the front by enemy priority.
        final List<UUID> order = this.siegeOrder(c, st);
        final List<Long> candidates = new ArrayList<>();
        final Set<Long> lost = new HashSet<>();
        for (final NpcState.LostChunk l : st.lostChunks) {
            if (l.world.equals(w.getName())) {
                lost.add(ChunkPlanner.key(l.cx, l.cz));
            }
        }
        final Map<Long, UUID> ownerOf = new HashMap<>();
        for (final long k : front) {
            this.kraje().getCountryAt(w.getName(), ChunkPlanner.cx(k), ChunkPlanner.cz(k)).ifPresent(o -> ownerOf.put(k, o.id()));
        }
        final Comparator<Long> byEnemy = Comparator.comparingInt(k -> {
            final int i = order.indexOf(ownerOf.get(k));
            return i < 0 ? Integer.MAX_VALUE : i;
        });
        front.stream().filter(lost::contains).sorted(byEnemy).forEach(candidates::add);
        front.stream().filter(k -> !lost.contains(k)).sorted(byEnemy).forEach(candidates::add);
        if (posture == NpcPosture.DEFENSIVE && !st.crisis) {
            candidates.retainAll(lost); // defensive: only take back what was ours
        }
        for (final long k : candidates) {
            final int cx = ChunkPlanner.cx(k);
            final int cz = ChunkPlanner.cz(k);
            if (!w.isChunkLoaded(cx, cz)) {
                continue; // only where the world is actually simulated
            }
            final Optional<KrajInfo> defender = this.kraje().getCountryAt(w.getName(), cx, cz);
            if (defender.isEmpty() || !this.siegeable(defender.get(), w.getName(), k)) {
                continue;
            }
            final Location centre = new Location(w, (cx << 4) + 8.5, 0, (cz << 4) + 8.5);
            centre.setY(w.getHighestBlockYAt(centre.getBlockX(), centre.getBlockZ()) + 1.0);
            if (!this.playersNear(centre, cfg.aiSiegePlayerRadius)) {
                continue;
            }
            final KrajeResult r = this.kraje().startSiege(c.id(), w.getName(), cx, cz);
            if (!r.ok()) {
                this.log(c.id(), NpcLogCategory.WAR, "Oblężenie " + cx + "," + cz + " niemożliwe: " + r.status() + " " + r.message());
                continue;
            }
            st.siegeId = this.kraje().getSiege(w.getName(), cx, cz).map(SiegeInfo::id).orElse(null);
            st.lastSiegeAt = System.currentTimeMillis();
            final int sent = this.sendTo(ready, centre, cfg.aiSiegeSoldiers, st.siegeSoldiers, true);
            this.syncKrajeLedger(c.id());
            this.log(c.id(), NpcLogCategory.WAR, (lost.contains(k) ? "ODBICIE: oblężenie utraconego chunka " : "OBLĘŻENIE chunka ") + cx + "," + cz + " ("
                + defender.get().name() + "), wysłano " + sent + " żołnierzy");
            return;
        }
    }

    /** Kraje's rule: the chunk's loss must not cut the defender's land in two; the capital only when it is the last chunk. */
    boolean siegeable(final KrajInfo defender, final String world, final long key) {
        final Set<Long> land = this.kraje().getClaims(defender.id(), world);
        final boolean capital = world.equals(defender.capitalWorld()) && ChunkPlanner.cx(key) == defender.capitalX() && ChunkPlanner.cz(key) == defender.capitalZ();
        if (capital && defender.chunkCount() > 1) {
            return false;
        }
        final Long capitalKey = world.equals(defender.capitalWorld()) ? ChunkPlanner.key(defender.capitalX(), defender.capitalZ()) : null;
        return ChunkPlanner.siegeKeepsConnectivity(land, key, capitalKey);
    }

    boolean playersNear(final Location at, final double radius) {
        for (final Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    /** Sends the {@code max} nearest soldiers to hold {@code centre}; returns how many. */
    private int sendTo(final List<Soldier> soldiers, final Location centre, final int max, final Set<UUID> remember, final boolean attack) {
        final List<Soldier> sorted = new ArrayList<>(soldiers);
        sorted.sort(Comparator.comparingDouble(s -> s.brain().getLocation().distanceSquared(centre)));
        // attack: the nearest ram (taran) always goes along to break the gate (NavalAiService.ram);
        // defence: rams stay home (the crew does not fight living enemies)
        if (!attack) {
            sorted.removeIf(s -> pl.medivalrpg.armia.logic.SiegeRules.ram(s.record().type));
        }
        for (int i = 0; attack && i < sorted.size(); i++) {
            if (pl.medivalrpg.armia.logic.SiegeRules.ram(sorted.get(i).record().type)) {
                sorted.addFirst(sorted.remove(i));
                break;
            }
        }
        int n = 0;
        for (final Soldier s : sorted) {
            if (n >= max) {
                break;
            }
            s.record().mode = SoldierMode.HOLD;
            s.record().hold = Pos.of(centre);
            s.record().siege = true;
            s.record().commander = null;
            remember.add(s.id());
            n++;
        }
        this.army.registry().markDirty();
        return n;
    }

    void releaseSiege(final NpcState st) {
        for (final UUID id : st.siegeSoldiers) {
            final Soldier s = this.army.registry().get(id);
            if (s != null && s.record().siege) {
                s.record().siege = false;
                s.record().mode = SoldierMode.GUARD;
                s.record().hold = null;
            }
        }
        st.siegeSoldiers.clear();
        st.siegeId = null;
        this.army.registry().markDirty();
    }

    /** Kraje: a siege started. NPC defenders send nearby soldiers to the chunk; grudge against the attacker. */
    public void onSiegeStart(final UUID siegeId, final UUID attacker, final UUID defender, final String world, final int cx, final int cz) {
        final NpcState st = this.states.get(defender);
        if (st == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        st.lastSiegeOnUsAt = now;
        st.opinion.merge(attacker, -this.rules().revengeSiege(), (a, b) -> Opinions.add(a, b));
        st.nextTickAt = Math.min(st.nextTickAt, now + 10_000L); // react soon (crisis mode)
        if (!this.isActive(defender)) {
            return;
        }
        final World w = Bukkit.getWorld(world);
        if (w == null || !w.isChunkLoaded(cx, cz)) {
            this.log(defender, NpcLogCategory.WAR, "Oblężenie przez " + this.name(attacker) + " (" + cx + "," + cz + ")");
            return;
        }
        final Location centre = new Location(w, (cx << 4) + 8.5, 0, (cz << 4) + 8.5);
        centre.setY(w.getHighestBlockYAt(centre.getBlockX(), centre.getBlockZ()) + 1.0);
        // A landing (no attacker land next to the chunk) is answered with a wider and bigger response: the bridgehead
        // must not take root on our coast.
        final Set<Long> attackerLand = this.kraje().getClaims(attacker, world);
        boolean landing = true;
        for (final long n : ChunkPlanner.neighbors4(ChunkPlanner.key(cx, cz))) {
            if (attackerLand.contains(n)) {
                landing = false;
                break;
            }
        }
        final double radius = landing ? 160 : 96;
        final List<Soldier> near = new ArrayList<>();
        for (final Soldier s : this.army.registry().ofCountry(defender)) {
            // Coast guards called by a ship alarm are reserved (siege flag) but exactly the ones that should fight now.
            final boolean free = !s.record().siege || st.coastGuard.contains(s.id());
            if (s.attached() && s.record().commander == null && free && s.brain().getWorld() == w
                && s.brain().getLocation().distanceSquared(centre) < radius * radius) {
                near.add(s);
            }
        }
        near.forEach(s -> st.coastGuard.remove(s.id()));
        final int max = landing ? this.cfg().aiSiegeSoldiers * 3 / 2 : this.cfg().aiSiegeSoldiers;
        final int sent = this.sendTo(near, centre, max, st.siegeSoldiers, false);
        this.log(defender, NpcLogCategory.WAR, (landing ? "Obrona wybrzeża przed DESANTEM " : "Obrona przed oblężeniem przez ") + this.name(attacker)
            + " (" + cx + "," + cz + "): wysłano " + sent + " żołnierzy");
    }

    public void onSiegeEnd(final UUID siegeId, final UUID attacker, final UUID defender, final boolean conquered) {
        final NpcState a = this.states.get(attacker);
        if (a != null && (siegeId.equals(a.siegeId) || !a.siegeSoldiers.isEmpty())) {
            this.log(attacker, NpcLogCategory.WAR, "Koniec oblężenia: " + (conquered ? "ZDOBYTO chunk" : "nieudane"));
            this.releaseSiege(a);
        }
        final NpcState d = this.states.get(defender);
        if (d != null && !d.siegeSoldiers.isEmpty() && d.siegeId == null) {
            this.log(defender, NpcLogCategory.WAR, "Koniec obrony: " + (conquered ? "chunk STRACONY" : "obroniono"));
            this.releaseSiege(d);
        }
    }

    /** Kraje: a chunk changed owner. Conquests from NPC countries are remembered (reclaim, revenge, crisis). */
    public void onChunkChange(final UUID oldOwner, final UUID newOwner, final String world, final int cx, final int cz, final ClaimCause cause) {
        if (this.conquest != null) {
            this.conquest.onChunkChange(oldOwner, newOwner, cause);
        }
        final long now = System.currentTimeMillis();
        final NpcState loser = oldOwner == null ? null : this.states.get(oldOwner);
        if (loser != null && cause == ClaimCause.CONQUEST && newOwner != null) {
            loser.lostChunks.removeIf(l -> l.is(world, cx, cz));
            loser.lostChunks.add(new NpcState.LostChunk(world, cx, cz, newOwner, now));
            while (loser.lostChunks.size() > 64) {
                loser.lostChunks.remove(0);
            }
            loser.lastChunkLostAt = now;
            loser.chunksLostInWar.merge(newOwner, 1, Integer::sum);
            loser.opinion.merge(newOwner, -this.rules().revengeChunkLost(), (a, b) -> Opinions.add(a, b));
            loser.nextTickAt = Math.min(loser.nextTickAt, now + 10_000L);
            this.log(oldOwner, NpcLogCategory.WAR, "Utracono chunk " + cx + "," + cz + " na rzecz " + this.name(newOwner) + " — do odbicia");
        }
        for (final NpcState st : this.states.values()) {
            final boolean ours = st.country.equals(newOwner);
            final boolean removed = st.lostChunks.removeIf(l -> l.is(world, cx, cz) && (ours || newOwner == null));
            if (removed && ours) {
                this.log(st.country, NpcLogCategory.WAR, "Odbito utracony chunk " + cx + "," + cz);
            }
            if (!ours && newOwner != null && st != loser) {
                for (final NpcState.LostChunk l : st.lostChunks) {
                    if (l.is(world, cx, cz)) {
                        l.taker = newOwner; // our old land changed hands
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ events from Kraje / Armia

    public void onRelationChange(final UUID a, final UUID b, final Relation oldRel, final Relation newRel) {
        if (this.conquest != null) {
            this.conquest.onRelationChange(a, b, oldRel, newRel);
        }
        this.track(a, b, oldRel, newRel);
        this.track(b, a, oldRel, newRel);
        if (newRel == Relation.ALLIANCE || oldRel == Relation.WAR) {
            this.clearPending(a, b);
        }
    }

    private void track(final UUID self, final UUID other, final Relation oldRel, final Relation newRel) {
        final NpcState st = this.states.get(self);
        if (st == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (newRel == Relation.WAR && oldRel != Relation.WAR) {
            st.warStartedAt.put(other, now);
            st.warLosses.remove(other);
            st.treasuryAtWarStart.put(other, this.balance(self));
            st.chunksLostInWar.put(other, 0);
            st.peaceSince = 0;
            if (st.declaredByUs.contains(other)) {
                st.opinion.merge(other, -20, (x, y) -> Opinions.add(x, y));
                this.log(self, NpcLogCategory.WAR, "Stan wojny z " + this.name(other));
            } else {
                st.opinion.merge(other, -this.rules().revengeWarDeclared(), (x, y) -> Opinions.add(x, y));
                this.log(self, NpcLogCategory.WAR, "Stan wojny z " + this.name(other) + " — agresor, opinia " + st.opinionOf(other));
            }
        } else if (oldRel == Relation.WAR && newRel != Relation.WAR) {
            st.warStartedAt.remove(other);
            st.warLosses.remove(other);
            st.declaredByUs.remove(other);
            st.treasuryAtWarStart.remove(other);
            st.chunksLostInWar.remove(other);
            if (other.equals(st.capitulatingTo)) {
                st.capitulatingTo = null;
            }
            final boolean peaceNow = this.kraje().getCountry(self).map(k -> k.enemies().isEmpty()).orElse(true);
            if (peaceNow) {
                st.peaceSince = now;
                st.crisisSince = 0;
                st.crisis = false;
            }
            this.log(self, NpcLogCategory.DIPLOMACY, "Pokój z " + this.name(other) + (peaceNow ? " — budżet wraca na odbudowę" : ""));
        } else if (newRel == Relation.ALLIANCE) {
            st.opinion.merge(other, 20, (x, y) -> Opinions.add(x, y));
            this.log(self, NpcLogCategory.DIPLOMACY, "Sojusz z " + this.name(other));
        }
    }

    public void onSoldierDied(final Soldier s, final UUID killerCountry) {
        final NpcState st = this.states.get(s.country());
        if (st != null && killerCountry != null && !killerCountry.equals(s.country())) {
            st.warLosses.merge(killerCountry, 1, Integer::sum);
            st.opinion.merge(killerCountry, -2, (x, y) -> Opinions.add(x, y));
        }
    }

    /** Tribute paid by a player country through the ruler GUI. */
    public boolean tribute(final Player payer, final UUID npc, final long amount) {
        final Optional<KrajInfo> own = this.kraje().getCountryOf(payer.getUniqueId());
        final Messages m = this.messages.get();
        if (own.isEmpty()) {
            m.send(payer, "no-country");
            return false;
        }
        final Rank rank = this.kraje().getRank(payer.getUniqueId()).orElse(Rank.CITIZEN);
        if (!rank.atLeast(this.cfg().diplomacyRank)) {
            m.send(payer, "no-rank", Placeholder.unparsed("rank", this.cfg().diplomacyRank.displayName()));
            return false;
        }
        final TxResult r = this.services.skarbiec().transfer(this.services.treasury(own.get().id()), this.services.treasury(npc), amount,
            "Trybut od " + own.get().name());
        if (!r.ok()) {
            m.send(payer, "tribute-no-money", Placeholder.unparsed("amount", this.services.money(amount)));
            return false;
        }
        final NpcState st = this.ensure(npc);
        final int before = st.opinionOf(own.get().id());
        st.opinion.put(own.get().id(), AiDecisions.opinionAfterTribute(before, amount, this.cfg().tributeCopperPerOpinion));
        this.flow(npc, "tribute", amount, own.get().name(), "Trybut od: " + own.get().name());
        this.log(npc, NpcLogCategory.DIPLOMACY, "Trybut " + this.services.money(amount) + " od " + own.get().name() + " (opinia " + before + " → "
            + st.opinionOf(own.get().id()) + ")");
        m.send(payer, "tribute-paid", Placeholder.unparsed("amount", this.services.money(amount)), Placeholder.unparsed("country", this.name(npc)));
        return true;
    }

    // ------------------------------------------------------------------ goal

    private GoalPlanner.Ref ref(final UUID id) {
        return id == null ? null : this.kraje().getCountry(id).map(k -> new GoalPlanner.Ref(k.id(), k.name())).orElse(null);
    }

    /** Current goal of an NPC country (also cached in the state after every tick). */
    public NpcGoal computeGoal(final KrajInfo c, final NpcState st) {
        GoalPlanner.Ref siegeOf = null;
        Double siegeProgress = null;
        if (st.siegeId != null) {
            for (final SiegeInfo s : this.kraje().getSieges()) {
                if (s.id().equals(st.siegeId)) {
                    siegeOf = this.ref(s.defender());
                    siegeProgress = s.progress();
                }
            }
        }
        final List<UUID> order = c.enemies().isEmpty() ? List.of() : this.siegeOrder(c, st);
        final GoalPlanner.Ref enemy = order.isEmpty() ? null : this.ref(order.get(0));
        UUID reclaim = null;
        int lostMax = 0;
        final Map<UUID, Integer> lostBy = new HashMap<>();
        for (final NpcState.LostChunk l : st.lostChunks) {
            final int n = lostBy.merge(l.taker, 1, Integer::sum);
            if (n > lostMax && this.kraje().getCountry(l.taker).isPresent()) {
                lostMax = n;
                reclaim = l.taker;
            }
        }
        final Optional<UUID> overlord = this.overlordOf(c.id());
        long buyout = -1;
        if (overlord.isPresent()) {
            try {
                buyout = this.kraje().buyoutCost(c.id());
            } catch (final LinkageError e) {
                buyout = -1;
            }
        }
        final long now = System.currentTimeMillis();
        return GoalPlanner.goal(new GoalPlanner.Inputs(st.crisis, enemy, siegeOf, siegeProgress, this.ref(reclaim), lostMax,
            overlord.map(this::ref).orElse(null), buyout, this.balance(c.id()), this.ref(st.capitulatingTo), this.rebuilding(c, st),
            st.savingForBuilding, this.army.registry().count(c.id()), this.desiredSoldiers(c, st), c.chunkCount(), st.maxChunks,
            st.expansion, now - st.lastBuildAt < 3L * this.cfg().aiTickMinutes * 60_000L));
    }

    /** Tribute agreement or vassalage between an NPC country and another one. */
    public Optional<pl.medivalrpg.armia.api.NpcTribute> tributeInfo(final UUID npc, final UUID other) {
        final NpcState st = this.states.get(npc);
        final NpcState.TributeDeal d = st == null ? null : st.tributeWith(other);
        if (d != null) {
            return Optional.of(new pl.medivalrpg.armia.api.NpcTribute(d.amount, d.pays, d.until));
        }
        try {
            final Optional<VassalInfo> mine = this.kraje().getVassalage(npc);
            if (mine.isPresent() && mine.get().overlord().equals(other)) {
                return Optional.of(new pl.medivalrpg.armia.api.NpcTribute(mine.get().dailyTribute(), true, mine.get().until()));
            }
            final Optional<VassalInfo> theirs = this.kraje().getVassalage(other);
            if (theirs.isPresent() && theirs.get().overlord().equals(npc)) {
                return Optional.of(new pl.medivalrpg.armia.api.NpcTribute(theirs.get().dailyTribute(), false, theirs.get().until()));
            }
        } catch (final LinkageError e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    String name(final UUID country) {
        return this.kraje().getCountry(country).map(KrajInfo::name).orElse("?");
    }

    static String fmt(final double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
