// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import pl.medivalrpg.armia.api.NpcLogCategory;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.logic.AiDecisions;
import pl.medivalrpg.armia.logic.ChunkPlanner;
import pl.medivalrpg.armia.logic.NavalPlanner;
import pl.medivalrpg.armia.logic.SiegeRules;
import pl.medivalrpg.armia.model.NpcState;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.model.SoldierMode;
import pl.medivalrpg.armia.naval.FleetService;
import pl.medivalrpg.armia.naval.NavalRules;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.armia.soldier.Soldier;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.SiegeInfo;

/**
 * Field operations of NPC countries that need more than one decision tick (wave 13/16):
 * <ul>
 *   <li><b>naval landings</b> — plan (inside the AI tick), embark, sail by autopilot, unload and start the landing siege
 *       through {@code KrajeApi.startSiege} (Kraje turns it into a landing when the chunk is not adjacent and
 *       {@code LandingSupport} confirms the transport);</li>
 *   <li><b>coastal defence</b> — an enemy ship near our coast pulls nearby guards to the shore for a few minutes;</li>
 *   <li><b>artillery</b> — catapults/cannons sent to an NPC siege bombard the besieged chunk like {@code /armia ostrzal}.</li>
 * </ul>
 * Everything runs on the main thread every second, only in loaded chunks, with a time budget per run.
 */
public final class NavalAiService {

    private static final long RUN_BUDGET_NANOS = 2_000_000L;
    /** Per scan (target, then launch); planning runs at most once per AI tick of a country (5 min). */
    private static final long PLAN_BUDGET_NANOS = 6_000_000L;
    /** Campaign prefix of soldiers at sea on a long-range expedition ({@code SoldierRecord.campaign}). */
    public static final String CAMPAIGN_PREFIX = "desant:";

    private final Plugin plugin;
    private final Services services;
    private final ArmyService army;
    private final NpcAiService ai;
    private BukkitTask task;
    private long runs;
    /** Last ram target per soldier: the next AI pick avoids it (an unreachable gate is not retried forever). */
    private final java.util.Map<UUID, Location> ramFailed = new java.util.HashMap<>();
    private boolean budgetHit;

    public NavalAiService(final Plugin plugin, final Services services, final ArmyService army, final NpcAiService ai) {
        this.plugin = plugin;
        this.services = services;
        this.army = army;
        this.ai = ai;
    }

    private ArmiaConfig cfg() {
        return this.ai.cfg();
    }

    private FleetService fleet() {
        return this.army.fleet();
    }

    public void start() {
        final long now = System.currentTimeMillis();
        for (final NpcState st : this.ai.states().values()) {
            final NpcState.NavalOp op = st.naval;
            if (op == null) {
                continue;
            }
            if (op.longRange) {
                // A voyage in the background survives a restart. A physical landing is retried (the ship and its cargo are
                // persisted by the fleet); a siege is gone after a restart (Kraje does not persist sieges) → SIEGE ends it.
                if ("LAND".equals(op.phase)) {
                    op.landedAt = 0;
                    op.phaseAt = now;
                }
                continue;
            }
            // Autopilot courses are not persisted: short-range operations that were sailing or landing are wound down.
            if (!"EMBARK".equals(op.phase)) {
                op.phase = "SIEGE".equals(op.phase) ? "DONE" : "RETURN";
                op.phaseAt = now;
            }
        }
        this.reconcile();
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::run, 220L, 20L);
    }

    /** After a restart soldiers at sea and expeditions must agree (soldiers.json and npc.json are saved separately). */
    private void reconcile() {
        final java.util.Map<String, NpcState.NavalOp> ops = new java.util.HashMap<>();
        for (final NpcState st : this.ai.states().values()) {
            if (st.naval != null && st.naval.longRange && st.naval.id != null) {
                ops.put(st.naval.campaign(), st.naval);
            }
        }
        int back = 0;
        for (final Soldier s : List.copyOf(this.army.registry().all())) {
            final String c = s.record().campaign;
            if (c == null || !c.startsWith(CAMPAIGN_PREFIX)) {
                continue;
            }
            final NpcState.NavalOp op = ops.get(c);
            if (op == null || !op.soldiers.contains(s.id()) || !this.ai.state(s.country()).map(x -> x.naval == op).orElse(false)) {
                this.army.controller().returnFromMarch(s);
                s.record().mode = SoldierMode.GUARD;
                s.record().hold = null;
                back++;
            }
        }
        for (final NpcState.NavalOp op : ops.values()) {
            op.soldiers.removeIf(id -> this.army.registry().get(id) == null);
            op.landed.removeIf(id -> this.army.registry().get(id) == null);
        }
        if (back > 0) {
            this.army.registry().markDirty();
            this.plugin.getLogger().info("Wyprawy morskie: " + back + " żołnierzy bez wyprawy wraca do domu");
        }
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
        }
    }

    public int running() {
        int n = 0;
        for (final NpcState st : this.ai.states().values()) {
            if (st.naval != null) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ driver

    private void run() {
        if (!this.cfg().aiEnabled || this.ai.globalPaused() || this.fleet() == null) {
            return;
        }
        this.runs++;
        final long start = System.nanoTime();
        for (final NpcState st : List.copyOf(this.ai.states().values())) {
            if (System.nanoTime() - start > RUN_BUDGET_NANOS) {
                break; // the rest next second
            }
            try {
                if (st.naval != null) {
                    this.advance(st);
                }
                if (this.runs % 5 == 0) {
                    this.coast(st);
                    this.artillery(st);
                }
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd operacji AI kraju " + st.country, e);
            }
        }
    }

    // ------------------------------------------------------------------ planning (inside the AI tick)

    /**
     * Decides and starts a naval landing for one NPC country; logs the reasons only when they change. A physical landing
     * (own coast and enemy coast loaded near players, within {@code max-range}) is preferred; otherwise a long-range
     * expedition computed in the background ({@code ai.naval.long-range}).
     */
    void plan(final KrajInfo c, final NpcState st) {
        final ArmiaConfig cfg = this.cfg();
        final FleetService fleet = this.fleet();
        final NavalPlanner.Settings s = cfg.naval;
        if (!s.enabled() || !cfg.aiSieges || fleet == null || !fleet.aiAvailable() || st.naval != null || st.siegeId != null
            || c.enemies().isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long sinceLast = (now - st.lastNavalAt) / 60_000L;
        if (sinceLast < s.cooldownMinutes() || this.running() >= s.maxOperations()) {
            return;
        }
        final World w = Bukkit.getWorld(c.capitalWorld());
        if (w == null) {
            return;
        }
        final long started = System.nanoTime();
        final Set<Long> own = this.ai.kraje().getClaims(c.id(), w.getName());
        final double ours = this.ai.strength(c.id());
        final long spendable = this.spendable(c, st);
        final long landingCost = this.landingCost();
        final List<Soldier> idle = this.idle(c.id(), w);
        final List<String> reasons = new ArrayList<>();

        for (final UUID enemy : this.ai.siegeOrder(c, st)) {
            if (System.nanoTime() - started > PLAN_BUDGET_NANOS) {
                break;
            }
            final boolean landFront = !ChunkPlanner.enemyFront(own, k -> this.ai.kraje().getCountryAt(w.getName(), ChunkPlanner.cx(k), ChunkPlanner.cz(k))
                .map(o -> o.id().equals(enemy)).orElse(false)).isEmpty();
            final Optional<KrajInfo> oe = this.ai.kraje().getCountry(enemy);
            if (oe.isEmpty()) {
                continue;
            }
            final Set<Long> lost = this.lostTo(st, enemy, w.getName());
            final double ratio = ours / Math.max(0.5, this.ai.strength(enemy));
            final NavalPlanner.Reason physical = this.physical(c, st, oe.get(), w, own, lost, landFront, ratio, sinceLast, spendable,
                landingCost, idle, started);
            if (physical == null || physical == NavalPlanner.Reason.OK) {
                return; // started, or the block scan ran out of time (next decision tick)
            }
            final NavalPlanner.Reason far = cfg.navalLong.enabled()
                ? this.longPlan(c, st, oe.get(), landFront, lost, sinceLast, ratio, spendable, landingCost, false).reason()
                : physical;
            if (far == NavalPlanner.Reason.OK) {
                return;
            }
            if (far != NavalPlanner.Reason.NOT_AT_WAR && far != NavalPlanner.Reason.LAND_FRONT && far != NavalPlanner.Reason.COOLDOWN
                && far != NavalPlanner.Reason.BUSY) {
                reasons.add(oe.get().name() + " — " + NavalPlanner.polish(far));
            }
        }
        if (!reasons.isEmpty()) {
            this.logOnce(st, "Desant nie teraz: " + String.join("; ", reasons));
        }
    }

    /** Short-range physical landing (wave 13/16): OK when started, null when the block scan hit the time budget. */
    private NavalPlanner.Reason physical(final KrajInfo c, final NpcState st, final KrajInfo enemy, final World w, final Set<Long> own,
                                         final Set<Long> lost, final boolean landFront, final double ratio, final long sinceLast,
                                         final long spendable, final long landingCost, final List<Soldier> idle, final long started) {
        final NavalPlanner.Settings s = this.cfg().naval;
        final FleetService fleet = this.fleet();
        // Cheap gate before any block scanning (optimistic coast/target/distance).
        final NavalPlanner.Input gate = new NavalPlanner.Input(true, false, this.running(), sinceLast, landFront, !lost.isEmpty(),
            st.posture(), st.crisis, st.aggression, ratio, true, true, 100, idle.size(), true, true, 0, landingCost, spendable);
        NavalPlanner.Reason last = NavalPlanner.decide(s, gate);
        if (last != NavalPlanner.Reason.OK) {
            return last;
        }
        final Target target = this.target(c, enemy, w, lost, landFront, started);
        if (target == null) {
            return NavalPlanner.Reason.NO_TARGET;
        }
        final Launch launch = this.launch(c, w, own, target, System.nanoTime());
        if (launch == null && this.budgetHit) {
            this.budgetHit = false;
            return null; // scanning took too long this time: try again at the next decision tick
        }
        final String type = NavalPlanner.shipType(this.army.era(c.id()), spendable, NavalRules.type("karawela").cost(), landingCost);
        final boolean canBuild = fleet.aiCanBuild(c.id(), type) || fleet.aiCanBuild(c.id(), "transportowiec");
        final NavalPlanner.Input in = new NavalPlanner.Input(true, false, this.running(), sinceLast, landFront && !target.reclaim(),
            target.reclaim(), st.posture(), st.crisis, st.aggression, ratio, launch != null, true,
            launch == null ? Double.NaN : Math.hypot(launch.water().getX() - target.wx(), launch.water().getZ() - target.wz()),
            idle.size(), launch != null && launch.ship() != null, canBuild, NavalRules.type(type).cost(), landingCost, spendable);
        last = NavalPlanner.decide(s, in);
        if (last != NavalPlanner.Reason.OK) {
            return last;
        }
        this.begin(c, st, enemy.id(), target, launch, fleet.aiCanBuild(c.id(), type) ? type : "transportowiec", idle);
        return st.naval != null ? NavalPlanner.Reason.OK : NavalPlanner.Reason.NO_SHIP;
    }

    private Set<Long> lostTo(final NpcState st, final UUID enemy, final String world) {
        final Set<Long> lost = new HashSet<>();
        for (final NpcState.LostChunk l : st.lostChunks) {
            if (enemy.equals(l.taker) && l.world.equals(world)) {
                lost.add(ChunkPlanner.key(l.cx, l.cz));
            }
        }
        return lost;
    }

    private long spendable(final KrajInfo c, final NpcState st) {
        final long wages = this.army.dailyWages(c.id());
        return Math.max(0, this.services.skarbiec().balance(this.services.treasury(c.id()))
            - AiDecisions.reserve(this.cfg().tuning, st.personality(), wages));
    }

    /** Kraje's price of a landing siege: {@code siege.cost} + {@code siege.landing.extra-cost}. */
    private long landingCost() {
        return this.krajeLong("siege.cost", 500) + this.krajeLong("siege.landing.extra-cost", 1000);
    }

    private final java.util.Map<UUID, String> lastPlanLog = new java.util.HashMap<>();

    private void logOnce(final NpcState st, final String text) {
        if (!text.equals(this.lastPlanLog.put(st.country, text))) {
            this.ai.log(st.country, NpcLogCategory.WAR, text);
        }
    }

    private long krajeLong(final String key, final long def) {
        final Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
        return kraje == null ? def : Math.max(0, kraje.getConfig().getLong(key, def));
    }

    private List<Soldier> idle(final UUID country, final World w) {
        final List<Soldier> out = new ArrayList<>();
        for (final Soldier s : this.army.registry().ofCountry(country)) {
            if (s.attached() && s.record().commander == null && !s.record().siege && s.record().transportShip == null
                && s.record().mode == SoldierMode.GUARD && s.brain().getWorld() == w && !s.record().suspended()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Enemy coastal chunk to land on: reclaim first, loaded, siegeable, players near, with a water waypoint. */
    private record Target(int cx, int cz, double wx, double wz, boolean reclaim) {
    }

    private Target target(final KrajInfo c, final KrajInfo enemy, final World w, final Set<Long> lost, final boolean landFront,
                          final long started) {
        final List<Long> candidates = new ArrayList<>();
        for (final long k : this.ai.kraje().getClaims(enemy.id(), w.getName())) {
            if (w.isChunkLoaded(ChunkPlanner.cx(k), ChunkPlanner.cz(k)) && (!landFront || lost.contains(k))) {
                candidates.add(k);
            }
        }
        candidates.sort(Comparator.comparingInt((Long k) -> lost.contains(k) ? 0 : 1)
            .thenComparingLong(k -> distSq(k, c.capitalX(), c.capitalZ())));
        int checked = 0;
        for (final long k : candidates) {
            if (checked++ >= 24 || System.nanoTime() - started > PLAN_BUDGET_NANOS) {
                return null;
            }
            final int cx = ChunkPlanner.cx(k);
            final int cz = ChunkPlanner.cz(k);
            if (!this.ai.siegeable(enemy, w.getName(), k) || this.protectedAt(w.getName(), cx, cz)) {
                continue;
            }
            final Location centre = new Location(w, (cx << 4) + 8.5, 64, (cz << 4) + 8.5);
            centre.setY(w.getHighestBlockYAt(centre.getBlockX(), centre.getBlockZ()) + 1.0);
            if ((this.cfg().aiNavalRequirePlayers && !this.ai.playersNear(centre, this.cfg().aiSiegePlayerRadius)) || this.ai.kraje().getSiege(w.getName(), cx, cz).isPresent()) {
                continue;
            }
            final Location water = this.waterNear(w, cx, cz);
            if (water != null && this.shoreIn(w, cx, cz, water) != null) {
                return new Target(cx, cz, water.getX(), water.getZ(), lost.contains(k));
            }
        }
        return null;
    }

    /** Water column touching dry shore of chunk (cx,cz), within 12 blocks of its edge, loaded only; null when none. */
    private Location waterNear(final World w, final int cx, final int cz) {
        Location best = null;
        double bestD = Double.MAX_VALUE;
        final double mx = (cx << 4) + 8;
        final double mz = (cz << 4) + 8;
        for (int x = (cx << 4) - 12; x < (cx << 4) + 28; x += 2) {
            for (int z = (cz << 4) - 12; z < (cz << 4) + 28; z += 2) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                final Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (top.getType() != Material.WATER || top.getRelative(0, -2, 0).getType() != Material.WATER) {
                    continue; // at least 3 blocks deep under the hull
                }
                final double d = (x - mx) * (x - mx) + (z - mz) * (z - mz);
                if (d < bestD && d > 8 * 8) {
                    bestD = d;
                    best = top.getLocation().add(0.5, 0.85, 0.5);
                }
            }
        }
        return best;
    }

    /** Where the operation starts: an idle own ship near our coast, or water where a new hull fits. */
    private record Launch(UUID ship, Location water) {
    }

    private Launch launch(final KrajInfo c, final World w, final Set<Long> own, final Target t, final long started) {
        final FleetService fleet = this.fleet();
        final List<Long> coast = new ArrayList<>();
        for (final long k : own) {
            if (w.isChunkLoaded(ChunkPlanner.cx(k), ChunkPlanner.cz(k))) {
                coast.add(k);
            }
        }
        coast.sort(Comparator.comparingDouble(k -> sq((ChunkPlanner.cx(k) << 4) + 8 - t.wx()) + sq((ChunkPlanner.cz(k) << 4) + 8 - t.wz())));
        int checked = 0;
        for (final long k : coast) {
            if (checked++ >= 12) {
                return null;
            }
            if (System.nanoTime() - started > PLAN_BUDGET_NANOS) {
                this.budgetHit = true;
                return null;
            }
            final Location centre = new Location(w, (ChunkPlanner.cx(k) << 4) + 8, 64, (ChunkPlanner.cz(k) << 4) + 8);
            final UUID existing = fleet.aiIdleShip(c.id(), centre, 48);
            if (existing != null) {
                final Location at = fleet.aiShipLocation(existing);
                if (at != null) {
                    return new Launch(existing, at);
                }
            }
            for (int x = (ChunkPlanner.cx(k) << 4) - 8; x < (ChunkPlanner.cx(k) << 4) + 24; x += 3) {
                for (int z = (ChunkPlanner.cz(k) << 4) - 8; z < (ChunkPlanner.cz(k) << 4) + 24; z += 3) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    final Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    if (top.getType() != Material.WATER) {
                        continue;
                    }
                    final Location at = top.getLocation().add(0.5, 0.85, 0.5);
                    final UUID owner = this.ai.kraje().getCountryAt(at).map(KrajInfo::id).orElse(null);
                    if (owner != null && !owner.equals(c.id())) {
                        continue;
                    }
                    at.setYaw(NavalPlanner.headingTo(at.getX(), at.getZ(), t.wx(), t.wz()));
                    if (fleet.aiHullFits(c.id(), "karawela", at) || fleet.aiHullFits(c.id(), "transportowiec", at)) {
                        return new Launch(null, at);
                    }
                }
            }
        }
        return null;
    }

    private void begin(final KrajInfo c, final NpcState st, final UUID enemy, final Target t, final Launch l, final String type,
                       final List<Soldier> idle) {
        final FleetService fleet = this.fleet();
        UUID ship = l.ship();
        boolean built = false;
        if (ship == null) {
            final String fit = fleet.aiHullFits(c.id(), type, l.water()) ? type : "transportowiec";
            ship = fleet.aiBuild(c.id(), fit, l.water(), l.water().getYaw());
            if (ship == null) {
                this.logOnce(st, "Desant nie teraz: nie udało się zwodować statku");
                return;
            }
            built = true;
            this.ai.flow(c.id(), "recruit", -NavalRules.type(fit).cost(), "AI", "Statek " + fit + " do desantu");
        }
        final Location gather = fleet.aiShoreNear(ship, 12);
        if (gather == null) {
            this.logOnce(st, "Desant nie teraz: brak brzegu do zaokrętowania przy statku");
            return; // a new hull stays in the fleet and can be reused next time
        }
        final NpcState.NavalOp op = new NpcState.NavalOp();
        op.phase = "EMBARK";
        op.ship = ship;
        op.enemy = enemy;
        op.world = gather.getWorld().getName();
        op.cx = t.cx();
        op.cz = t.cz();
        op.wx = t.wx();
        op.wz = t.wz();
        op.gx = gather.getX();
        op.gy = gather.getY();
        op.gz = gather.getZ();
        op.reclaim = t.reclaim();
        op.startedAt = System.currentTimeMillis();
        op.phaseAt = op.startedAt;
        final int squad = NavalPlanner.squad(this.cfg().naval, idle.size(), fleet.aiCapacity(ship));
        final List<Soldier> sorted = new ArrayList<>(idle);
        sorted.sort(Comparator.comparingDouble(s -> s.brain().getLocation().distanceSquared(gather)));
        for (final Soldier s : sorted.subList(0, Math.min(squad, sorted.size()))) {
            s.record().siege = true; // reserved: guards/sieges leave it alone
            s.record().mode = SoldierMode.HOLD;
            s.record().hold = Pos.of(gather);
            s.record().commander = null;
            op.soldiers.add(s.id());
        }
        this.army.registry().markDirty();
        st.naval = op;
        st.lastNavalAt = op.startedAt;
        this.ai.log(c.id(), NpcLogCategory.WAR, "DESANT: " + (built ? "zwodowano statek, " : "statek gotowy, ") + "zaokrętowanie " + op.soldiers.size()
            + " żołnierzy na wyprawę na " + this.ai.name(enemy) + " (" + t.cx() + "," + t.cz() + (t.reclaim() ? ", odbicie utraconej ziemi" : "") + ")");
    }

    // ------------------------------------------------------------------ execution

    private void advance(final NpcState st) {
        final NpcState.NavalOp op = st.naval;
        if (op.longRange) {
            this.advanceLong(st, op);
            return;
        }
        final FleetService fleet = this.fleet();
        final long now = System.currentTimeMillis();
        final Optional<KrajInfo> oc = this.ai.kraje().getCountry(st.country);
        if (oc.isEmpty() || op.ship == null || !fleet.aiExists(op.ship)) {
            this.finish(st, "statek utracony");
            return;
        }
        final boolean war = oc.get().enemies().contains(op.enemy);
        final long timeout = this.cfg().naval.timeoutMinutes() * 60_000L;
        if (!war && ("EMBARK".equals(op.phase) || "SAIL".equals(op.phase) || "LAND".equals(op.phase))) {
            this.phase(st, "RETURN", "wojna się skończyła — powrót");
        } else if (now - op.phaseAt > timeout && !"SIEGE".equals(op.phase)) {
            if ("RETURN".equals(op.phase)) {
                // Never abandon soldiers aboard: keep trying whenever the ship is simulated again (operation blocks new ones).
                op.phaseAt = now;
                this.ai.log(st.country, NpcLogCategory.WAR, "Desant: " + fleet.aiCargo(op.ship) + " żołnierzy czeka na pokładzie na wyładunek");
                return;
            }
            this.phase(st, "RETURN", "brak postępu przez " + this.cfg().naval.timeoutMinutes() + " min");
        }
        switch (op.phase) {
            case "EMBARK" -> this.embark(st, op, now);
            case "SAIL" -> this.sail(st, op);
            case "LAND" -> this.land(st, op, oc.get());
            case "SIEGE" -> {
                final boolean running = st.siegeId != null && this.ai.kraje().getSieges().stream().anyMatch(s -> s.id().equals(st.siegeId));
                if (!running) {
                    if (fleet.aiCargo(op.ship) > 0) {
                        this.phase(st, "RETURN", "koniec desantu — reszta wojska wraca");
                    } else {
                        this.finish(st, "desant zakończony");
                    }
                }
            }
            case "RETURN" -> this.unload(st, op);
            default -> this.finish(st, "");
        }
    }

    private void phase(final NpcState st, final String phase, final String why) {
        st.naval.phase = phase;
        st.naval.phaseAt = System.currentTimeMillis();
        if (!why.isEmpty()) {
            this.ai.log(st.country, NpcLogCategory.WAR, "Desant: " + why);
        }
    }

    private void embark(final NpcState st, final NpcState.NavalOp op, final long now) {
        final FleetService fleet = this.fleet();
        if (fleet.aiShipLocation(op.ship) == null) {
            return; // the model is not simulated (no players around): wait, the timeout winds it down
        }
        int aboard = 0;
        for (final UUID id : List.copyOf(op.soldiers)) {
            final Soldier s = this.army.registry().get(id);
            if (s == null) {
                op.soldiers.remove(id);
                continue;
            }
            if (op.ship.equals(s.record().transportShip)) {
                aboard++;
            } else if (s.attached() && fleet.aiEmbark(op.ship, s)) {
                aboard++;
            }
        }
        final int min = this.cfg().naval.minSoldiers();
        final boolean all = aboard >= op.soldiers.size() || fleet.aiCapacity(op.ship) == 0;
        if ((all && aboard >= min) || (aboard >= min && now - op.phaseAt > 90_000L)) {
            this.releaseAshore(op, fleet);
            fleet.aiSail(op.ship, op.wx, op.wz);
            this.phase(st, "SAIL", "wypłynięcie z " + aboard + " żołnierzami w kierunku " + (int) op.wx + ", " + (int) op.wz);
        } else if (now - op.phaseAt > 180_000L) {
            this.phase(st, "RETURN", "za mało żołnierzy dotarło do statku (" + aboard + ")");
        }
    }

    private void sail(final NpcState st, final NpcState.NavalOp op) {
        final FleetService fleet = this.fleet();
        switch (fleet.aiCourse(op.ship)) {
            case ARRIVED -> {
                fleet.aiStop(op.ship);
                this.phase(st, "LAND", "statek u brzegu wroga");
            }
            case STUCK -> {
                fleet.aiStop(op.ship);
                final Location at = fleet.aiShipLocation(op.ship);
                if (at != null && NavalRules.distanceToChunkSq(at.getX(), at.getZ(), op.cx, op.cz) <= 40 * 40) {
                    this.phase(st, "LAND", "statek utknął blisko celu — próba wyładunku");
                } else {
                    this.phase(st, "RETURN", "statek nie może dopłynąć do celu");
                }
            }
            case NONE -> fleet.aiSail(op.ship, op.wx, op.wz); // e.g. after a reload of the fleet
            default -> { }
        }
    }

    private void land(final NpcState st, final NpcState.NavalOp op, final KrajInfo c) {
        final FleetService fleet = this.fleet();
        final Location ship = fleet.aiShipLocation(op.ship);
        final World w = Bukkit.getWorld(op.world);
        if (ship == null || w == null || !w.isChunkLoaded(op.cx, op.cz)) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (op.landedAt == 0) {
            Location shore = this.shoreIn(w, op.cx, op.cz, ship);
            if (shore == null && !this.retarget(op, w, ship)) {
                if (op.longRange) {
                    this.homeward(st, op, "brzeg w chunku " + op.cx + "," + op.cz + " jest umocniony (brak plaży) — odwrót", op.voyageMinutes, Set.of());
                    return;
                }
                this.phase(st, "RETURN", "brzeg w chunku " + op.cx + "," + op.cz + " jest umocniony (brak plaży) — odwrót");
                return;
            }
            if (shore == null) {
                shore = this.shoreIn(w, op.cx, op.cz, ship);
                this.ai.log(c.id(), NpcLogCategory.WAR, "Desant: brzeg celu umocniony — lądowanie w sąsiednim chunku " + op.cx + "," + op.cz);
            }
            final List<UUID> delivered = fleet.aiLand(op.ship, shore, 6, op.longRange);
            if (delivered.isEmpty()) {
                return; // retry next second, the timeout winds it down
            }
            op.landed.addAll(delivered);
            op.landedAt = now;
            this.ai.log(c.id(), NpcLogCategory.WAR, "Desant: wyładowano " + delivered.size() + " żołnierzy na brzegu " + shore.getBlockX() + ", " + shore.getBlockZ());
            return; // the landed soldiers spawn on the next ticks; Kraje's proof needs them present
        }
        if (now - op.landedAt < 1_500L) {
            return;
        }
        final List<UUID> landed = new ArrayList<>();
        for (final UUID id : op.soldiers) {
            final Soldier s = this.army.registry().get(id);
            if (s != null && s.record().transportShip == null) {
                landed.add(id);
            }
        }
        final KrajeResult r = this.ai.kraje().startSiege(c.id(), op.world, op.cx, op.cz);
        if (!r.ok()) {
            if (now - op.landedAt < 15_000L && r.status() == KrajeResult.Status.DENIED) {
                return; // soldiers may still be spawning: the transport proof is retried for a few seconds
            }
            this.ai.log(c.id(), NpcLogCategory.WAR, "Desant: Kraje odmówiło oblężenia: " + r.status() + " " + r.message());
            if (op.longRange) {
                this.homeward(st, op, "lądowanie nieudane — flota zabiera wojsko do domu", op.voyageMinutes, Set.of());
                return;
            }
            for (final UUID id : landed) {
                op.soldiers.remove(id); // landed soldiers guard the beach (disembark = GUARD at the landing spot)
                final Soldier s = this.army.registry().get(id);
                if (s != null) {
                    s.record().siege = false;
                }
            }
            this.army.registry().markDirty();
            if (fleet.aiCargo(op.ship) > 0) {
                this.phase(st, "RETURN", "");
            } else {
                this.finish(st, "wojsko zostaje na brzegu jako straż");
            }
            return;
        }
        st.siegeId = this.ai.kraje().getSiege(op.world, op.cx, op.cz).map(SiegeInfo::id).orElse(null);
        st.lastSiegeAt = now;
        final Location centre = new Location(w, (op.cx << 4) + 8.5, 64, (op.cz << 4) + 8.5);
        centre.setY(w.getHighestBlockYAt(centre.getBlockX(), centre.getBlockZ()) + 1.0);
        for (final UUID id : landed) {
            final Soldier s = this.army.registry().get(id);
            if (s != null) {
                s.record().mode = SoldierMode.HOLD;
                s.record().hold = Pos.of(centre);
                s.record().siege = true;
                st.siegeSoldiers.add(id);
            }
            op.soldiers.remove(id);
        }
        this.army.registry().markDirty();
        this.ai.syncKrajeLedger(c.id());
        this.phase(st, "SIEGE", "");
        this.ai.log(c.id(), NpcLogCategory.WAR, "DESANT na " + this.ai.name(op.enemy) + " (" + op.cx + "," + op.cz + "): " + landed.size()
            + " żołnierzy walczy o przyczółek" + (op.reclaim ? " (odbicie utraconej ziemi)" : ""));
    }

    /**
     * The enemy fortified the planned beach (e.g. Ludnosc border walls): look for another enemy coastal chunk within
     * 2 chunks that may be besieged and still has a beach within reach of the ship. Updates the operation's target.
     */
    private boolean retarget(final NpcState.NavalOp op, final World w, final Location ship) {
        final Optional<KrajInfo> enemy = this.ai.kraje().getCountry(op.enemy);
        if (enemy.isEmpty()) {
            return false;
        }
        int bestX = 0;
        int bestZ = 0;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                final int cx = op.cx + dx;
                final int cz = op.cz + dz;
                if ((dx == 0 && dz == 0) || !w.isChunkLoaded(cx, cz)
                    || !this.ai.kraje().getCountryAt(w.getName(), cx, cz).map(k -> k.id().equals(op.enemy)).orElse(false)
                    || !this.ai.siegeable(enemy.get(), w.getName(), ChunkPlanner.key(cx, cz))
                    || this.ai.kraje().getSiege(w.getName(), cx, cz).isPresent()) {
                    continue;
                }
                final Location beach = this.shoreIn(w, cx, cz, ship);
                if (beach == null) {
                    continue;
                }
                final double d = beach.distanceSquared(ship);
                if (d < bestD && d <= 24 * 24) {
                    bestD = d;
                    bestX = cx;
                    bestZ = cz;
                }
            }
        }
        if (bestD == Double.MAX_VALUE) {
            return false;
        }
        op.cx = bestX;
        op.cz = bestZ;
        return true;
    }

    /** Dry block of chunk (cx,cz) next to water, nearest to {@code near}. Bounded: 256 columns. */
    private Location shoreIn(final World w, final int cx, final int cz, final Location near) {
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = cx << 4; x < (cx << 4) + 16; x++) {
            for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                final Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (!top.getType().isSolid()) {
                    continue;
                }
                boolean coast = false;
                for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (!w.isChunkLoaded((x + d[0]) >> 4, (z + d[1]) >> 4)) {
                        continue;
                    }
                    final Block n = w.getHighestBlockAt(x + d[0], z + d[1], HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    if (n.getType() == Material.WATER && Math.abs(n.getY() - top.getY()) <= 3) {
                        coast = true;
                        break;
                    }
                }
                if (!coast) {
                    continue;
                }
                final double d = sq(x + 0.5 - near.getX()) + sq(z + 0.5 - near.getZ());
                if (d < bestD) {
                    bestD = d;
                    best = top.getLocation().add(0.5, 1, 0.5);
                }
            }
        }
        return best;
    }

    /** RETURN: unload the remaining cargo on a safe shore near the ship, else sail back to the gathering shore. */
    private void unload(final NpcState st, final NpcState.NavalOp op) {
        final FleetService fleet = this.fleet();
        final Location ship = fleet.aiShipLocation(op.ship);
        if (fleet.aiCargo(op.ship) == 0) {
            this.finish(st, "wojsko na lądzie");
            return;
        }
        if (ship == null) {
            return;
        }
        final World w = ship.getWorld();
        final Location home = new Location(w, op.gx, op.gy, op.gz);
        final boolean nearHome = ship.distanceSquared(home) <= 20 * 20;
        // Our own land or wilderness only (never stay on enemy land when retreating).
        final Location spot = nearHome ? home : fleet.aiShoreNear(op.ship, 12);
        final boolean ownOrWild = spot != null && this.ai.kraje().getCountryAt(spot).map(k -> k.id().equals(st.country)).orElse(true);
        if (spot != null && ownOrWild) {
            final List<UUID> back = fleet.aiLand(op.ship, spot, 6);
            if (!back.isEmpty()) {
                this.ai.log(st.country, NpcLogCategory.WAR, "Desant: " + back.size() + " żołnierzy zeszło na brzeg");
            }
            return;
        }
        if (fleet.aiCourse(op.ship) == FleetService.CourseState.NONE || fleet.aiCourse(op.ship) == FleetService.CourseState.STUCK) {
            fleet.aiSail(op.ship, op.gx, op.gz);
        }
    }

    /** Ends the operation: soldiers not aboard and not besieging go back to normal guard duty. */
    private void finish(final NpcState st, final String why) {
        final NpcState.NavalOp op = st.naval;
        if (op != null) {
            if (op.ship != null && this.fleet() != null) {
                this.fleet().aiStop(op.ship);
            }
            this.releaseAshore(op, this.fleet());
            for (final UUID id : op.soldiers) {
                final Soldier s = this.army.registry().get(id);
                if (s != null && s.record().transportShip == null && !st.siegeSoldiers.contains(id)) {
                    s.record().siege = false;
                    s.record().mode = SoldierMode.GUARD;
                    s.record().hold = null;
                }
            }
            this.army.registry().markDirty();
        }
        st.naval = null;
        if (!why.isEmpty()) {
            this.ai.log(st.country, NpcLogCategory.WAR, "Desant: " + why);
        }
    }

    /** Reserved soldiers that did not board stay ashore as guards. */
    private void releaseAshore(final NpcState.NavalOp op, final FleetService fleet) {
        for (final UUID id : List.copyOf(op.soldiers)) {
            final Soldier s = this.army.registry().get(id);
            if (s != null && s.record().transportShip == null && s.record().siege && "EMBARK".equals(op.phase)) {
                s.record().siege = false;
                s.record().mode = SoldierMode.GUARD;
                s.record().hold = null;
                op.soldiers.remove(id);
            }
        }
    }

    // ------------------------------------------------------------------ long-range expeditions (2026-10-31)
    //
    // Beyond real sailing range (unloaded ocean, thousands of blocks) the voyage is computed in the background:
    //   VOYAGE   — soldiers left the home coast (campaign "desant:<id>": no entity, still counted in limits and wages);
    //              the ship was paid at departure; defenders are warned with an ETA, again shortly before arrival.
    //   OFFSHORE — off the enemy coast: waits until players are near an enemy coastal chunk (same rule as land sieges),
    //              then the ship appears on loaded water (FleetService.aiMaterialize) and the soldiers go aboard.
    //   LAND     — the normal physical landing (land()): unload on open ground, Kraje landing siege with LandingSupport proof.
    //   SIEGE    — the normal siege (rams, artillery, capture rules of Kraje).
    //   HOMEWARD — survivors sail home in the background and reappear at their home posts; the ship is laid up.
    // Everything is persisted in npc.json (+ soldiers.json/fleet.json) and survives restarts.

    /** Result of a long-range decision (reason + a short Polish detail for the admin). */
    public record LongResult(NavalPlanner.Reason reason, String detail) {
    }

    private static final Set<String> LONG_PHASES = Set.of("VOYAGE", "OFFSHORE", "LAND", "SIEGE", "HOMEWARD");

    /** Soldier free for an expedition: at home on guard duty, not commanded, not on another task. */
    private static boolean freeForExpedition(final Soldier s) {
        final var r = s.record();
        return r.mode == SoldierMode.GUARD && r.commander == null && !r.siege && !r.suspended() && r.royalPost == null;
    }

    private int pendingShips() {
        int n = 0;
        for (final NpcState st : this.ai.states().values()) {
            if (st.naval != null && st.naval.longRange && st.naval.ship == null && !"HOMEWARD".equals(st.naval.phase)) {
                n++;
            }
        }
        return n;
    }

    /** Heading of the voyage: a siegeable enemy chunk (our lost land first, then nearest to our capital). Null = none. */
    private Long headingTarget(final KrajInfo c, final KrajInfo enemy, final String world, final Set<Long> lost) {
        final List<Long> land = new ArrayList<>(this.ai.kraje().getClaims(enemy.id(), world));
        land.sort(Comparator.comparingInt((Long k) -> lost.contains(k) ? 0 : 1).thenComparingLong(k -> distSq(k, c.capitalX(), c.capitalZ())));
        int checked = 0;
        for (final long k : land) {
            if (checked++ >= 32) {
                break;
            }
            if (this.ai.siegeable(enemy, world, k) && !this.protectedAt(world, ChunkPlanner.cx(k), ChunkPlanner.cz(k))) {
                return k;
            }
        }
        return null;
    }

    /** Strongest free soldiers, one ram first (it breaks the gate), at most one more siege engine. */
    private List<Soldier> pickForce(final UUID country, final int size) {
        final List<Soldier> free = new ArrayList<>();
        for (final Soldier s : this.army.registry().ofCountry(country)) {
            if (freeForExpedition(s)) {
                free.add(s);
            }
        }
        // Soldiers in sight of players are taken last (they would vanish in front of them).
        free.sort(Comparator.comparingInt((Soldier s) -> s.attached() && this.ai.playersNear(s.brain().getLocation(), 64) ? 1 : 0)
            .thenComparingDouble(s -> -this.strengthOf(s)).thenComparing(Soldier::id));
        final List<Soldier> out = new ArrayList<>();
        if (size <= 0) {
            return out;
        }
        for (final Soldier s : free) {
            if (SiegeRules.ram(s.record().type)) {
                out.add(s);
                break;
            }
        }
        int engines = out.size();
        for (final Soldier s : free) {
            if (out.size() >= size) {
                break;
            }
            if (out.contains(s) || SiegeRules.ram(s.record().type)) {
                continue;
            }
            if (SiegeRules.breaksFortifications(s.record().type)) {
                if (engines >= 2) {
                    continue;
                }
                engines++;
            }
            out.add(s);
        }
        return out;
    }

    private double strengthOf(final Soldier s) {
        final UnitType u = this.cfg().soldierUnit(s.record().type);
        return u == null ? 1.0 : u.siegeStrength();
    }

    /**
     * Long-range decision for one enemy; starts the expedition on OK. {@code admin}: ordered by an administrator (no
     * personality/posture/cooldown gates; all rules, limits and costs still apply).
     */
    public LongResult longPlan(final KrajInfo c, final NpcState st, final KrajInfo enemy, final boolean landFront, final Set<Long> lost,
                               final long sinceLast, final double ratio, final long spendable, final long landingCost, final boolean admin) {
        final ArmiaConfig cfg = this.cfg();
        final FleetService fleet = this.fleet();
        final String world = c.capitalWorld();
        if (fleet == null || !fleet.aiAvailable() || world == null || Bukkit.getWorld(world) == null) {
            return new LongResult(NavalPlanner.Reason.DISABLED, "flota niedostępna");
        }
        final Long heading = this.headingTarget(c, enemy, world, lost);
        final String type = NavalPlanner.shipType(this.army.era(c.id()), spendable, NavalRules.type("karawela").cost(), landingCost);
        final NavalRules.Type spec = NavalRules.type(type);
        int free = 0;
        for (final Soldier s : this.army.registry().ofCountry(c.id())) {
            if (freeForExpedition(s)) {
                free++;
            }
        }
        final int pool = NavalPlanner.expeditionPool(cfg.navalLong, this.army.registry().count(c.id()), free);
        final boolean slot = fleet.aiFreeSlots(c.id()) > this.pendingShips();
        final boolean running = this.ai.kraje().getSieges().stream().anyMatch(x -> x.attacker().equals(c.id()));
        final NavalPlanner.LongInput in = new NavalPlanner.LongInput(admin, c.enemies().contains(enemy.id()), st.naval != null,
            st.siegeId != null || running, this.running(), sinceLast, landFront, !lost.isEmpty(), st.posture(), st.crisis,
            st.aggression, ratio, enemy.npc(), heading != null, pool, slot, spec.cost(), landingCost, spendable);
        final NavalPlanner.Reason r = NavalPlanner.decideLong(cfg.naval, cfg.navalLong, in);
        if (r != NavalPlanner.Reason.OK) {
            return new LongResult(r, NavalPlanner.polish(r) + " (wolnych żołnierzy " + pool + ", do wydania " + this.services.money(spendable)
                + ", potrzeba " + this.services.money(spec.cost() + landingCost) + ")");
        }
        final int squad = NavalPlanner.longSquad(cfg.navalLong, pool, spec.soldiers());
        final List<Soldier> force = this.pickForce(c.id(), squad);
        if (force.size() < cfg.naval.minSoldiers()) {
            return new LongResult(NavalPlanner.Reason.FEW_SOLDIERS, NavalPlanner.polish(NavalPlanner.Reason.FEW_SOLDIERS));
        }
        // The ship is paid now (closed pool: treasury → world operating budget); Kraje charges the landing at the coast.
        if (!this.services.skarbiec().withdraw(this.services.treasury(c.id()), spec.cost(), "Armia NPC: statek wyprawy desantowej (" + type + ")").ok()) {
            return new LongResult(NavalPlanner.Reason.NO_MONEY, NavalPlanner.polish(NavalPlanner.Reason.NO_MONEY));
        }
        this.ai.flow(c.id(), "recruit", -spec.cost(), admin ? "admin" : "AI", "Statek wyprawy desantowej " + type);
        final int hx = ChunkPlanner.cx(heading);
        final int hz = ChunkPlanner.cz(heading);
        final double distance = Math.hypot(((long) hx - c.capitalX()) * 16.0, ((long) hz - c.capitalZ()) * 16.0);
        final long now = System.currentTimeMillis();
        final NpcState.NavalOp op = new NpcState.NavalOp();
        op.longRange = true;
        op.id = UUID.randomUUID().toString();
        op.admin = admin;
        op.phase = "VOYAGE";
        op.enemy = enemy.id();
        op.world = world;
        op.cx = hx;
        op.cz = hz;
        op.reclaim = lost.contains(heading);
        op.shipType = type;
        op.distance = distance;
        op.voyageMinutes = NavalPlanner.voyageMinutes(cfg.navalLong, distance);
        op.startedAt = now;
        op.phaseAt = now;
        op.arriveAt = now + op.voyageMinutes * 60_000L;
        for (final Soldier s : force) {
            final Pos home = s.record().post != null ? s.record().post : s.record().spawnPos();
            if (home != null) {
                op.homes.put(s.id(), home);
            }
            s.record().siege = true; // reserved: guards, sieges and background offensives leave it alone
            s.record().commander = null;
            s.record().hold = null;
            this.army.controller().march(s, op.campaign());
            op.soldiers.add(s.id());
        }
        this.army.registry().markDirty();
        st.naval = op;
        st.lastNavalAt = now;
        this.lastPlanLog.remove(c.id());
        this.ai.log(c.id(), NpcLogCategory.WAR, "WYPRAWA MORSKA" + (admin ? " (rozkaz administratora)" : "") + " na " + enemy.name() + ": "
            + op.soldiers.size() + " żołnierzy, " + type + " (" + this.services.money(spec.cost()) + "), rejs ok. " + op.voyageMinutes
            + " min (" + Math.round(distance) + " bloków) ku " + hx + "," + hz + (op.reclaim ? ", odbicie utraconej ziemi" : ""));
        this.tellDefenders(c, enemy, "naval-expedition-departed", op);
        return new LongResult(NavalPlanner.Reason.OK, op.soldiers.size() + " żołnierzy, rejs " + op.voyageMinutes + " min");
    }

    private void advanceLong(final NpcState st, final NpcState.NavalOp op) {
        final long now = System.currentTimeMillis();
        final Optional<KrajInfo> oc = this.ai.kraje().getCountry(st.country);
        if (oc.isEmpty()) {
            this.finishLong(st, op, "kraj zniknął");
            return;
        }
        final KrajInfo c = oc.get();
        final Optional<KrajInfo> enemy = this.ai.kraje().getCountry(op.enemy);
        final boolean war = enemy.isPresent() && c.enemies().contains(op.enemy);
        op.soldiers.removeIf(id -> this.army.registry().get(id) == null);
        op.landed.removeIf(id -> this.army.registry().get(id) == null);
        if (!LONG_PHASES.contains(op.phase)) {
            op.phase = "HOMEWARD";
        }
        switch (op.phase) {
            case "VOYAGE" -> {
                if (!war) {
                    this.homeward(st, op, enemy.isEmpty() ? "wróg już nie istnieje — flota zawraca" : "wojna się skończyła — flota zawraca",
                        elapsedMinutes(op, now), Set.of());
                    return;
                }
                if (op.soldiers.isEmpty()) {
                    this.finishLong(st, op, "wyprawa bez żołnierzy");
                    return;
                }
                final long left = op.arriveAt - now;
                if (!op.approachWarned && left <= this.cfg().navalLong.approachMinutes() * 60_000L && left > 0) {
                    op.approachWarned = true;
                    this.tellDefenders(c, enemy.get(), "naval-expedition-approach", op);
                }
                if (now >= op.arriveAt) {
                    op.phase = "OFFSHORE";
                    op.phaseAt = now;
                    op.waitUntil = now + this.cfg().navalLong.waitMinutes() * 60_000L;
                    op.offshoreAnnounced = true;
                    this.ai.log(c.id(), NpcLogCategory.WAR, "Wyprawa: flota u wybrzeży " + enemy.get().name()
                        + " — czeka na dogodną chwilę do lądowania (najdłużej " + this.cfg().navalLong.waitMinutes() + " min)");
                    this.tellDefenders(c, enemy.get(), "naval-expedition-offshore", op);
                }
            }
            case "OFFSHORE" -> {
                if (!war) {
                    this.homeward(st, op, "wojna się skończyła — flota zawraca", op.voyageMinutes, Set.of());
                } else if (op.soldiers.isEmpty()) {
                    this.finishLong(st, op, "wyprawa bez żołnierzy");
                } else if (now > op.waitUntil) {
                    this.homeward(st, op, "brak okazji do lądowania (nikogo przy brzegu) — flota zawraca", op.voyageMinutes, Set.of());
                } else if (this.runs % 5 == 0) {
                    this.tryArrive(st, op, c, enemy.get());
                }
            }
            case "LAND" -> {
                final FleetService fleet = this.fleet();
                if (!war) {
                    this.homeward(st, op, "wojna się skończyła — odwrót z wybrzeża", op.voyageMinutes, Set.of());
                } else if (op.ship == null || !fleet.aiExists(op.ship)) {
                    this.homeward(st, op, "statek zatopiony — ocalali wracają", op.voyageMinutes, Set.of());
                } else if (now - op.phaseAt > Math.max(10, this.cfg().naval.timeoutMinutes()) * 60_000L) {
                    this.homeward(st, op, "lądowanie się nie udało — odwrót", op.voyageMinutes, Set.of());
                } else {
                    this.land(st, op, c);
                }
            }
            case "SIEGE" -> {
                final boolean running = st.siegeId != null && this.ai.kraje().getSieges().stream().anyMatch(x -> x.id().equals(st.siegeId));
                if (running) {
                    return;
                }
                final boolean conquered = this.ai.kraje().getCountryAt(op.world, op.cx, op.cz).map(k -> k.id().equals(c.id())).orElse(false);
                if (st.siegeId != null) {
                    this.ai.releaseSiege(st); // Kraje ended it (also after a restart): soldiers ashore are free again
                }
                if (conquered) {
                    // The bridgehead is ours: soldiers ashore hold it (they guard their landing posts); cargo goes home.
                    final Set<UUID> garrison = new HashSet<>(op.landed);
                    this.ai.log(c.id(), NpcLogCategory.WAR, "Wyprawa: PRZYCZÓŁEK ZDOBYTY (" + op.cx + "," + op.cz + ") — "
                        + garrison.size() + " żołnierzy zostaje jako załoga");
                    this.homeward(st, op, "przyczółek utrzymany, statek wraca", op.voyageMinutes, garrison);
                } else {
                    this.homeward(st, op, "desant odparty — ocalali wracają", op.voyageMinutes, Set.of());
                }
            }
            case "HOMEWARD" -> {
                if (now >= op.returnAt) {
                    this.arriveHome(st, op);
                }
            }
            default -> { }
        }
    }

    private static long elapsedMinutes(final NpcState.NavalOp op, final long now) {
        return Math.max(1, (long) Math.ceil((now - op.startedAt) / 60_000.0));
    }

    /** OFFSHORE: players near an enemy coastal chunk → the ship appears on loaded water and the soldiers board it. */
    private void tryArrive(final NpcState st, final NpcState.NavalOp op, final KrajInfo c, final KrajInfo enemy) {
        final World w = Bukkit.getWorld(op.world);
        final FleetService fleet = this.fleet();
        if (w == null || fleet == null || !fleet.aiAvailable()) {
            return;
        }
        final long started = System.nanoTime();
        final Target t = this.target(c, enemy, w, this.lostTo(st, enemy.id(), w.getName()), false, started);
        if (t == null) {
            // Nobody near a reachable enemy coast yet. When players do stand on enemy land, say why nothing happens.
            final boolean onEnemyLand = w.getPlayers().stream().anyMatch(p -> this.ai.kraje().getCountryAt(p.getLocation())
                .map(k -> k.id().equals(enemy.id())).orElse(false));
            if (onEnemyLand) {
                this.logOnce(st, "Wyprawa: gracze są na ziemi " + enemy.name() + ", ale przy nich brak nadbrzeżnego chunka do oblężenia "
                    + "(plaża + woda min. 3 bloki głęboka, bez stolicy i bez dzielenia kraju) — flota czeka");
            }
            return;
        }
        String type = op.shipType == null ? "transportowiec" : op.shipType;
        Location at = this.anchorage(c.id(), type, w, t, started);
        if (at == null && !"transportowiec".equals(type) && NavalRules.type("transportowiec").soldiers() >= op.soldiers.size()) {
            type = "transportowiec"; // a smaller hull fits narrower waters (the caravel was paid; no refund, no new cost)
            at = this.anchorage(c.id(), type, w, t, System.nanoTime());
        }
        if (at == null || NavalRules.type(type).soldiers() < op.soldiers.size()) {
            this.logOnce(st, "Wyprawa: przy brzegu " + enemy.name() + " (" + t.cx() + "," + t.cz() + ") brak wolnej wody dla kadłuba — szukam dalej");
            return; // try again in a few seconds (maybe another stretch of coast); never leave anybody at sea
        }
        final UUID ship = fleet.aiMaterialize(c.id(), type, at, at.getYaw(), Math.max(24, op.distance));
        if (ship == null) {
            return;
        }
        op.ship = ship;
        op.cx = t.cx();
        op.cz = t.cz();
        op.wx = at.getX();
        op.wz = at.getZ();
        op.reclaim = t.reclaim();
        int aboard = 0;
        for (final UUID id : List.copyOf(op.soldiers)) {
            final Soldier s = this.army.registry().get(id);
            if (s != null && op.campaign().equals(s.record().campaign)) {
                this.army.controller().stow(s, ship);
                if (ship.equals(s.record().transportShip)) {
                    aboard++;
                }
            }
        }
        this.army.registry().markDirty();
        op.phase = "LAND";
        op.phaseAt = System.currentTimeMillis();
        op.landedAt = 0;
        this.ai.log(c.id(), NpcLogCategory.WAR, "Wyprawa: " + type + " z " + aboard + " żołnierzami podchodzi do brzegu " + enemy.name()
            + " (" + t.cx() + "," + t.cz() + ") — lądowanie");
    }

    /** Loaded water within reach of the target's beach where the expedition hull fits (wilderness/own/enemy at war). */
    private Location anchorage(final UUID country, final String type, final World w, final Target t, final long started) {
        final FleetService fleet = this.fleet();
        final Location near = new Location(w, t.wx(), 64, t.wz());
        final Location beach = this.shoreIn(w, t.cx(), t.cz(), near);
        if (beach == null) {
            return null;
        }
        final List<Location> water = new ArrayList<>();
        for (int x = (t.cx() << 4) - 20; x < (t.cx() << 4) + 36; x += 2) {
            for (int z = (t.cz() << 4) - 20; z < (t.cz() << 4) + 36; z += 2) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                final double d = sq(x + 0.5 - beach.getX()) + sq(z + 0.5 - beach.getZ());
                if (d > 20 * 20 || d < 3 * 3) {
                    continue; // unloading spots must lie within 24 blocks of the ship
                }
                final Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (top.getType() != Material.WATER || top.getRelative(0, -2, 0).getType() != Material.WATER) {
                    continue;
                }
                water.add(top.getLocation().add(0.5, 0.85, 0.5));
            }
        }
        water.sort(Comparator.comparingDouble(l -> l.distanceSquared(beach)));
        int tried = 0;
        for (final Location at : water) {
            if (tried++ >= 16 || System.nanoTime() - started > PLAN_BUDGET_NANOS) {
                return null;
            }
            if (this.protectedAt(w.getName(), at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                continue;
            }
            final float toShore = NavalPlanner.headingTo(at.getX(), at.getZ(), beach.getX(), beach.getZ());
            for (final float yaw : new float[] {toShore + 90f, toShore, toShore - 90f}) {
                at.setYaw(yaw);
                if (fleet.aiAnchorage(country, type, at)) {
                    return at;
                }
            }
        }
        return null;
    }

    /**
     * The fleet sails home in the background ({@code minutes}); cargo and soldiers ashore (except {@code garrison}, who
     * hold a conquered bridgehead) leave with it. The empty ship is laid up.
     */
    private void homeward(final NpcState st, final NpcState.NavalOp op, final String why, final long minutes, final Set<UUID> garrison) {
        final FleetService fleet = this.fleet();
        final String campaign = op.campaign();
        final Set<UUID> all = new HashSet<>(op.soldiers);
        all.addAll(op.landed);
        all.removeAll(garrison);
        int back = 0;
        for (final UUID id : all) {
            final Soldier s = this.army.registry().get(id);
            if (s == null) {
                op.soldiers.remove(id);
                continue;
            }
            st.siegeSoldiers.remove(id);
            if (!campaign.equals(s.record().campaign)) {
                s.record().siege = true;
                this.army.controller().unstow(s, campaign);
            }
            if (campaign.equals(s.record().campaign)) {
                op.soldiers.add(id);
                back++;
            } else {
                op.soldiers.remove(id);
            }
        }
        for (final UUID id : garrison) {
            op.soldiers.remove(id);
            final Soldier s = this.army.registry().get(id);
            if (s != null && s.record().transportShip == null && s.record().campaign == null) {
                s.record().siege = false;
                s.record().mode = SoldierMode.GUARD;
                s.record().hold = null;
            }
        }
        op.landed.clear();
        if (op.ship != null && fleet != null) {
            if (fleet.aiExists(op.ship) && !fleet.aiDismiss(op.ship)) {
                this.plugin.getLogger().warning("Wyprawa: statek " + op.ship + " nadal ma ładunek — pozostaje we flocie");
            }
            op.ship = null;
        }
        this.army.registry().markDirty();
        if (back == 0) {
            this.finishLong(st, op, why + " — nikt nie wraca");
            return;
        }
        final long now = System.currentTimeMillis();
        op.phase = "HOMEWARD";
        op.phaseAt = now;
        op.returnAt = now + Math.max(1, minutes) * 60_000L;
        this.ai.log(st.country, NpcLogCategory.WAR, "Wyprawa: " + why + " — " + back + " żołnierzy w drodze do domu (ok. "
            + Math.max(1, minutes) + " min)");
        this.ai.kraje().getCountry(st.country).ifPresent(c -> this.ai.kraje().getCountry(op.enemy)
            .ifPresent(e -> this.tellDefenders(c, e, "naval-expedition-retreat", op)));
    }

    /** HOMEWARD ended: soldiers reappear at their home posts (when those chunks are loaded) and are on duty again. */
    private void arriveHome(final NpcState st, final NpcState.NavalOp op) {
        final String campaign = op.campaign();
        final Optional<KrajInfo> c = this.ai.kraje().getCountry(st.country);
        int back = 0;
        for (final UUID id : op.soldiers) {
            final Soldier s = this.army.registry().get(id);
            if (s == null || !campaign.equals(s.record().campaign)) {
                continue;
            }
            this.comeHome(s, op, c.orElse(null));
            back++;
        }
        this.army.registry().markDirty();
        op.soldiers.clear();
        this.finishLong(st, op, "flota wróciła — " + back + " żołnierzy znów w domu");
    }

    private void comeHome(final Soldier s, final NpcState.NavalOp op, final KrajInfo c) {
        this.army.controller().returnFromMarch(s);
        Pos home = op.homes.get(s.id());
        if (home == null && c != null && c.capitalWorld() != null) {
            home = Pos.chunkCenter(c.capitalWorld(), c.capitalX(), c.capitalZ());
        }
        if (home != null) {
            s.record().post = home;
            s.record().last = home;
        }
        s.record().mode = SoldierMode.GUARD;
        s.record().hold = null;
        s.record().commander = null;
    }

    private void finishLong(final NpcState st, final NpcState.NavalOp op, final String why) {
        // Nobody may stay at sea: whoever still carries this expedition's campaign comes home now.
        final String campaign = op.campaign();
        final KrajInfo c = this.ai.kraje().getCountry(st.country).orElse(null);
        for (final Soldier s : List.copyOf(this.army.registry().ofCountry(st.country))) {
            if (campaign.equals(s.record().campaign)) {
                this.comeHome(s, op, c);
            }
        }
        if (op.ship != null && this.fleet() != null && this.fleet().aiExists(op.ship) && this.fleet().aiCargo(op.ship) == 0) {
            this.fleet().aiDismiss(op.ship);
        }
        this.army.registry().markDirty();
        st.naval = null;
        st.lastNavalAt = System.currentTimeMillis();
        this.ai.log(st.country, NpcLogCategory.WAR, "Wyprawa zakończona: " + why);
    }

    /** Medieval-toned news for the citizens of the attacked country (online members only). */
    private void tellDefenders(final KrajInfo attacker, final KrajInfo defender, final String key, final NpcState.NavalOp op) {
        final long eta = Math.max(0, (long) Math.ceil((op.arriveAt - System.currentTimeMillis()) / 60_000.0));
        final String from = NavalPlanner.direction(((long) attacker.capitalX() - defender.capitalX()) * 16.0,
            ((long) attacker.capitalZ() - defender.capitalZ()) * 16.0);
        this.army.notifyCountry(defender.id(), key,
            Placeholder.component("attacker", Component.text(attacker.name(), attacker.color())),
            Placeholder.component("defender", Component.text(defender.name(), defender.color())),
            Placeholder.unparsed("eta", Long.toString(eta)),
            Placeholder.unparsed("from", from),
            Placeholder.unparsed("soldiers", Integer.toString(op.soldiers.size() + op.landed.size())));
    }

    // ------------------------------------------------------------------ admin (/nk desant)

    /** {@code /nk desant <kraj> [cel]}: an immediate long-range expedition against {@code target}. */
    public LongResult order(final KrajInfo c, final KrajInfo target) {
        final NpcState st = this.ai.ensure(c.id());
        if (!this.cfg().naval.enabled() || !this.cfg().navalLong.enabled()) {
            return new LongResult(NavalPlanner.Reason.DISABLED, NavalPlanner.polish(NavalPlanner.Reason.DISABLED)
                + " (ai.naval.enabled / ai.naval.long-range.enabled)");
        }
        if (st.naval != null) {
            return new LongResult(NavalPlanner.Reason.BUSY, "kraj prowadzi już operację morską (/nk desant " + c.name() + " status)");
        }
        final String world = c.capitalWorld();
        if (world == null || Bukkit.getWorld(world) == null) {
            return new LongResult(NavalPlanner.Reason.NO_COAST, "brak świata stolicy");
        }
        final Set<Long> own = this.ai.kraje().getClaims(c.id(), world);
        final boolean landFront = !ChunkPlanner.enemyFront(own, k -> this.ai.kraje().getCountryAt(world, ChunkPlanner.cx(k), ChunkPlanner.cz(k))
            .map(o -> o.id().equals(target.id())).orElse(false)).isEmpty();
        final double ratio = this.ai.strength(c.id()) / Math.max(0.5, this.ai.strength(target.id()));
        final LongResult r = this.longPlan(c, st, target, landFront, this.lostTo(st, target.id(), world),
            Long.MAX_VALUE / 120_000L, ratio, this.spendable(c, st), this.landingCost(), true);
        if (r.reason() == NavalPlanner.Reason.OK && landFront) {
            this.ai.log(c.id(), NpcLogCategory.WAR, "Wyprawa: " + target.name() + " graniczy z nami lądem — desant mimo to (rozkaz)");
        }
        return r;
    }

    /** {@code /nk desant <kraj> anuluj}: the fleet turns back (soldiers come home after the way back). */
    public String cancel(final UUID country) {
        final NpcState st = this.ai.state(country).orElse(null);
        final NpcState.NavalOp op = st == null ? null : st.naval;
        if (op == null) {
            return "brak operacji morskiej";
        }
        if (!op.longRange) {
            if (!"RETURN".equals(op.phase) && !"SIEGE".equals(op.phase)) {
                this.phase(st, "RETURN", "odwołano (administrator)");
            }
            return "desant fizyczny — statek wraca na brzeg (faza " + op.phase + ")";
        }
        return switch (op.phase) {
            case "VOYAGE" -> {
                final long back = elapsedMinutes(op, System.currentTimeMillis());
                this.homeward(st, op, "odwołano (administrator)", back, Set.of());
                yield "flota zawraca, powrót za ok. " + back + " min";
            }
            case "OFFSHORE", "LAND" -> {
                this.homeward(st, op, "odwołano (administrator)", op.voyageMinutes, Set.of());
                yield "flota zawraca, powrót za ok. " + op.voyageMinutes + " min";
            }
            case "SIEGE" -> "trwa oblężenie — wyprawa skończy się razem z nim";
            default -> "flota już wraca";
        };
    }

    /** {@code /nk desant <kraj> teraz}: skips the rest of the voyage (there or back). */
    public String hurry(final UUID country) {
        final NpcState st = this.ai.state(country).orElse(null);
        final NpcState.NavalOp op = st == null ? null : st.naval;
        if (op == null || !op.longRange) {
            return "brak wyprawy dalekiej";
        }
        final long now = System.currentTimeMillis();
        switch (op.phase) {
            case "VOYAGE" -> {
                op.arriveAt = now;
                return "flota dopływa teraz do wybrzeży wroga";
            }
            case "HOMEWARD" -> {
                op.returnAt = now;
                return "flota wraca teraz do domu";
            }
            default -> {
                return "faza " + op.phase + " — nic do przyspieszenia";
            }
        }
    }

    /** Status lines of the country's naval operation (admin). */
    public List<String> status(final UUID country) {
        final List<String> out = new ArrayList<>();
        final NpcState st = this.ai.state(country).orElse(null);
        final NpcState.NavalOp op = st == null ? null : st.naval;
        if (op == null) {
            out.add("brak operacji morskiej (ostatnia: " + (st == null || st.lastNavalAt == 0 ? "nigdy"
                : ((System.currentTimeMillis() - st.lastNavalAt) / 60_000L) + " min temu") + ")");
            return out;
        }
        final long now = System.currentTimeMillis();
        out.add((op.longRange ? "wyprawa daleka" : "desant fizyczny") + (op.admin ? " (rozkaz)" : "") + " na " + this.ai.name(op.enemy)
            + ", faza " + op.phase + ", cel " + op.cx + "," + op.cz);
        out.add("żołnierzy: " + op.soldiers.size() + " w drodze/na statku, " + op.landed.size() + " na lądzie, oblężenie: "
            + (st.siegeId != null ? "trwa" : "nie"));
        if (op.longRange) {
            switch (op.phase) {
                case "VOYAGE" -> out.add("przybycie za " + Math.max(0, (op.arriveAt - now) / 60_000L) + " min (rejs " + op.voyageMinutes + " min, "
                    + Math.round(op.distance) + " bloków)");
                case "OFFSHORE" -> out.add("u wybrzeży — czeka na graczy przy brzegu jeszcze " + Math.max(0, (op.waitUntil - now) / 60_000L) + " min");
                case "HOMEWARD" -> out.add("powrót za " + Math.max(0, (op.returnAt - now) / 60_000L) + " min");
                default -> out.add("statek: " + (op.ship == null ? "brak" : op.ship.toString().substring(0, 8)));
            }
        }
        return out;
    }

    /** Kraje's protected areas (server spawn): never land, anchor or besiege there. Read through the plugin; false when unknown. */
    private boolean protectedAt(final String world, final int cx, final int cz) {
        try {
            final Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
            if (kraje == null) {
                return false;
            }
            final Object areas = kraje.getClass().getMethod("protectedAreas").invoke(kraje);
            final Object at = areas.getClass().getMethod("at", String.class, int.class, int.class).invoke(areas, world, cx, cz);
            return at instanceof Optional<?> o && o.isPresent();
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ coastal defence

    private void coast(final NpcState st) {
        final FleetService fleet = this.fleet();
        final long now = System.currentTimeMillis();
        if (!this.ai.isActive(st.country)) {
            return;
        }
        final Optional<KrajInfo> oc = this.ai.kraje().getCountry(st.country);
        if (oc.isEmpty()) {
            return;
        }
        final KrajInfo c = oc.get();
        FleetService.LiveShip threat = null;
        if (!c.enemies().isEmpty()) {
            for (final FleetService.LiveShip ship : fleet.aiLiveShips()) {
                if (!c.enemies().contains(ship.country()) || ship.aboard() <= 0) {
                    continue;
                }
                final World w = ship.at().getWorld();
                final int scx = ship.at().getBlockX() >> 4;
                final int scz = ship.at().getBlockZ() >> 4;
                for (int dx = -3; dx <= 3 && threat == null; dx++) {
                    for (int dz = -3; dz <= 3 && threat == null; dz++) {
                        if (this.ai.kraje().getCountryAt(w.getName(), scx + dx, scz + dz).map(k -> k.id().equals(c.id())).orElse(false)) {
                            threat = ship;
                        }
                    }
                }
                if (threat != null) {
                    break;
                }
            }
        }
        if (threat == null) {
            if (!st.coastGuard.isEmpty() && now > st.coastAlarmUntil) {
                for (final UUID id : st.coastGuard) {
                    final Soldier s = this.army.registry().get(id);
                    if (s != null && s.record().siege && !st.siegeSoldiers.contains(id)) {
                        s.record().siege = false;
                        s.record().mode = SoldierMode.GUARD;
                        s.record().hold = null;
                    }
                }
                st.coastGuard.clear();
                this.army.registry().markDirty();
                this.ai.log(c.id(), NpcLogCategory.WAR, "Koniec alarmu na wybrzeżu");
            }
            return;
        }
        st.coastAlarmUntil = now + 180_000L;
        if (!st.coastGuard.isEmpty()) {
            return;
        }
        final Location post = this.nearestOwnShore(c, threat.at());
        if (post == null) {
            return;
        }
        final List<Soldier> near = new ArrayList<>();
        for (final Soldier s : this.army.registry().ofCountry(c.id())) {
            if (s.attached() && s.record().commander == null && !s.record().siege && s.record().transportShip == null
                && s.record().mode == SoldierMode.GUARD && s.brain().getWorld() == post.getWorld()
                && s.brain().getLocation().distanceSquared(post) < 128 * 128) {
                near.add(s);
            }
        }
        near.sort(Comparator.comparingDouble(s -> s.brain().getLocation().distanceSquared(post)));
        for (final Soldier s : near.subList(0, Math.min(6, near.size()))) {
            s.record().siege = true;
            s.record().mode = SoldierMode.HOLD;
            s.record().hold = Pos.of(post);
            st.coastGuard.add(s.id());
        }
        this.army.registry().markDirty();
        this.ai.log(c.id(), NpcLogCategory.WAR, "ALARM: wrogi statek kraju " + this.ai.name(threat.country()) + " przy wybrzeżu ("
            + threat.at().getBlockX() + ", " + threat.at().getBlockZ() + ") — " + st.coastGuard.size() + " żołnierzy na brzeg");
    }

    /** Dry own block nearest to {@code at} in the 7×7 loaded chunks around it. */
    private Location nearestOwnShore(final KrajInfo c, final Location at) {
        final World w = at.getWorld();
        Location best = null;
        double bestD = Double.MAX_VALUE;
        final int scx = at.getBlockX() >> 4;
        final int scz = at.getBlockZ() >> 4;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                final int cx = scx + dx;
                final int cz = scz + dz;
                if (!w.isChunkLoaded(cx, cz) || !this.ai.kraje().getCountryAt(w.getName(), cx, cz).map(k -> k.id().equals(c.id())).orElse(false)) {
                    continue;
                }
                final int x = Math.max(cx << 4, Math.min((cx << 4) + 15, at.getBlockX()));
                final int z = Math.max(cz << 4, Math.min((cz << 4) + 15, at.getBlockZ()));
                final Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (!top.getType().isSolid()) {
                    continue;
                }
                final double d = sq(x - at.getX()) + sq(z - at.getZ());
                if (d < bestD) {
                    bestD = d;
                    best = top.getLocation().add(0.5, 1, 0.5);
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ artillery

    private void artillery(final NpcState st) {
        if ((!this.cfg().aiArtillery && !this.cfg().aiRams) || st.siegeId == null || st.siegeSoldiers.isEmpty() || !this.ai.isActive(st.country)) {
            return;
        }
        final Optional<SiegeInfo> siege = this.ai.kraje().getSieges().stream().filter(s -> s.id().equals(st.siegeId)).findFirst();
        if (siege.isEmpty()) {
            return;
        }
        final World w = Bukkit.getWorld(siege.get().world());
        final int cx = siege.get().cx();
        final int cz = siege.get().cz();
        if (w == null || !w.isChunkLoaded(cx, cz)) {
            return;
        }
        int shots = 0;
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (final UUID id : st.siegeSoldiers) {
            final Soldier s = this.army.registry().get(id);
            if (s != null && s.attached() && SiegeRules.ram(s.record().type)) {
                this.ram(s, w, cx, cz); // rams strike on their own cadence, outside the salvo budget
                continue;
            }
            if (shots >= this.cfg().aiArtilleryShotsPerRun) {
                continue;
            }
            if (!this.cfg().aiArtillery || s == null || !s.attached() || SiegeRules.engine(s.record().type) == null || s.target() != null) {
                continue; // engines with a living target already fire at it
            }
            final UnitType u = this.cfg().soldierUnit(s.record().type);
            if (u == null) {
                continue;
            }
            final int x = (cx << 4) + 2 + rnd.nextInt(12);
            final int z = (cz << 4) + 2 + rnd.nextInt(12);
            final Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (top.getType().isAir() || top.isLiquid()) {
                continue;
            }
            if (this.army.controller().siegeEngines().fire(s, u, top.getLocation().add(0.5, 0.5, 0.5), null, u.damage())) {
                shots++;
            }
        }
    }

    /** NPC ram in a physical siege: drive to a gate or wall of the besieged chunk and strike it (same rules as players). */
    private void ram(final Soldier s, final World w, final int cx, final int cz) {
        final var engines = this.army.controller().siegeEngines();
        if (!this.cfg().aiRams || engines.hasRamOrder(s.id())) {
            return;
        }
        final Location failed = this.ramFailed.get(s.id());
        final Block target = engines.pickRamTarget(s, w, cx, cz, failed);
        if (target == null) {
            return;
        }
        if (engines.orderRam(s, target) == pl.medivalrpg.armia.soldier.SiegeEngineService.RamOrderResult.OK) {
            this.ramFailed.put(s.id(), target.getLocation()); // next pick avoids it if this order ends without a breach
        }
    }

    // ------------------------------------------------------------------ helpers

    private static double sq(final double v) {
        return v * v;
    }

    private static long distSq(final long key, final int x, final int z) {
        final long dx = ChunkPlanner.cx(key) - (long) x;
        final long dz = ChunkPlanner.cz(key) - (long) z;
        return dx * dx + dz * dz;
    }

}
