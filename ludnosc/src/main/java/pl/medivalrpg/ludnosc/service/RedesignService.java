// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.planner.BuildingChooser;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.SiteFinder;
import pl.medivalrpg.ludnosc.planner.TerrainView;
import pl.medivalrpg.ludnosc.planner.TownGrade;
import pl.medivalrpg.ludnosc.planner.TownPlanner;
import pl.medivalrpg.ludnosc.planner.TownRedesign;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * Redesign of whole towns ("plan v2", {@link TownRedesign}): NPC countries recompute their target plan after an
 * epoch or settlement level advance and every {@code redesign.interval-hours}, players on request
 * ({@code /ludnosc przebudowa} shows the plan, {@code potwierdz} — the ruler — starts it), admins and the panel at once
 * ({@code /ludnosc admin przebudowa <kraj> teraz}). The plan is worked off in this order:
 * <ol>
 *   <li>demolitions (only Ludnosc buildings that may be demolished; the plot is filled back to the ground level),</li>
 *   <li>grading ({@link TownGrade}): one level per quarter and for the square, gentle slopes in the streets between them,
 *       old street furniture and remains cleared from the graded columns,</li>
 *   <li>streets — after the grading, on the final ground (old street cells the grading changed are laid again),</li>
 *   <li>buildings in the quarters: the planner chooses what the town needs (housing otherwise) and where in the
 *       quarter; the queue is fed as orders finish, until no quarter has room.</li>
 * </ol>
 * Repeated admin orders do not stack: while a redesign is in progress {@link #force} only reports it. Main thread.
 */
public final class RedesignService {

    private static final int ROAD_SECTION = 48;
    private static final long ROAD_COST_PER_BLOCK = 2;
    /** A quarter is graded together with this many columns around it (half of the streets). */
    private static final int GRADE_MARGIN = 2;
    /** Towards the edge of the town (no graded neighbour) the slopes reach this far beyond the quarter. */
    private static final int EDGE_RAMP = 6;
    /** Kept buildings, sites and plots keep their ground within this distance. */
    private static final int KEEP_MARGIN = 2;
    /** Planning turns that wait for the terrain scan before the levels are computed anyway. */
    private static final int LEVEL_WAIT_TURNS = 20;
    private static final int TERRAIN_PLAN_VERSION = 1;

    private final LudnoscPlugin plugin;
    private final java.util.Map<UUID, Integer> levelWaits = new java.util.HashMap<>();
    /** Quarter → fill attempts in a row that found no room. */
    private final java.util.Map<String, Integer> fullMisses = new java.util.HashMap<>();

    public RedesignService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** The target plan of a country as it stands now (the design plan from the panel when there is one). */
    public TownRedesign.Plan compute(final UUID country) {
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null) {
            return null;
        }
        final pl.medivalrpg.ludnosc.planner.DesignPlan design = this.plugin.design().plan(country);
        if (design != null) {
            return this.fromDesign(country, k, design);
        }
        final BlockPos c = this.plugin.settlement().center(k);
        final List<TownRedesign.Existing> existing = new ArrayList<>();
        int radius = 32;
        for (final Building b : this.data().buildingsOf(country)) {
            if (!b.box().world().equals(c.world())) {
                continue;
            }
            // the well stays: it is the heart of the square (the square goes around it)
            existing.add(new TownRedesign.Existing(b.id(), b.box(), this.demolishable(b) && b.function() != BuildingFunction.WELL));
            radius = Math.max(radius, (int) Math.hypot(b.box().centerX() - c.x(), b.box().centerZ() - c.z()) + 16);
        }
        radius = Math.min(radius, this.plugin.settings().redesignRadius);
        final int era = this.plugin.postep().era(country);
        final Set<Long> roads = new java.util.HashSet<>(this.data().state(country).roads());
        roads.addAll(this.pendingRoadCells(country)); // ordered already: not ordered again
        return TownRedesign.plan(c.x(), c.z(), radius, era, existing, roads,
            (x, z) -> this.plugin.kraje().getCountryAt(c.world(), x >> 4, z >> 4).map(KrajInfo::id).map(country::equals).orElse(false));
    }

    /** Street cells of road works under way or queued (they are not the network yet). */
    private Set<Long> pendingRoadCells(final UUID country) {
        final Set<Long> out = new HashSet<>();
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (s.is(ConstructionSite.ROAD)) {
                for (final BlockPos p : s.road()) {
                    out.add(RoadPlanner.key(p.x(), p.z()));
                }
            }
        }
        for (final QueuedOrder o : this.data().queue(country)) {
            if (ConstructionSite.ROAD.equals(o.kind()) && o.road != null) {
                for (final BlockPos p : o.road) {
                    out.add(RoadPlanner.key(p.x(), p.z()));
                }
            }
        }
        return out;
    }

    /** Plan v2 from the design plan: its roads are the target; buildings in their way go only when the plan allows. */
    private TownRedesign.Plan fromDesign(final UUID country, final KrajInfo k, final pl.medivalrpg.ludnosc.planner.DesignPlan d) {
        final BlockPos c = this.plugin.settlement().center(k);
        final java.util.Set<Long> target = d.roadCells();
        final Set<Long> pending = this.pendingRoadCells(country);
        final List<Long> newRoads = new ArrayList<>();
        for (final long key : target) {
            if (!this.data().state(country).roads().contains(key) && !pending.contains(key)
                && this.plugin.kraje().getCountryAt(c.world(), RoadPlanner.keyX(key) >> 4,
                RoadPlanner.keyZ(key) >> 4).map(KrajInfo::id).map(country::equals).orElse(false)) {
                newRoads.add(key);
            }
        }
        final List<UUID> demolish = new ArrayList<>();
        if (d.options.allowDemolish && !d.options.keepExisting) {
            final List<pl.medivalrpg.ludnosc.model.BlockBox> planned = new ArrayList<>();
            for (final var pb : d.buildings) {
                final var box = this.plugin.design().footprint(c.world(), pb);
                if (box != null) {
                    planned.add(box);
                }
            }
            for (final Building b : this.data().buildingsOf(country)) {
                if (!this.demolishable(b) || d.noDemolition(b.box().centerX(), b.box().centerZ())) {
                    continue;
                }
                boolean hit = planned.stream().anyMatch(box -> box.intersects(b.box()));
                for (int x = b.box().minX(); x <= b.box().maxX() && !hit; x++) {
                    for (int z = b.box().minZ(); z <= b.box().maxZ() && !hit; z++) {
                        hit = target.contains(RoadPlanner.key(x, z));
                    }
                }
                if (hit) {
                    demolish.add(b.id());
                }
            }
        }
        return new TownRedesign.Plan(target, newRoads, demolish, List.of(), 0);
    }

    /** Only Ludnosc buildings with a template, never heritage. */
    public boolean demolishable(final Building b) {
        final var st = this.data().stateIfPresent(b.countryId());
        final boolean npc = this.plugin.kraje().getCountry(b.countryId()).map(KrajInfo::npc).orElse(false);
        return pl.medivalrpg.ludnosc.logic.DemolitionRules.demolishable(this.plugin.templates().get(b.type()) != null,
            this.plugin.settings().heritage.contains(b.type()) || this.plugin.settings().heritage.contains(this.plugin.templates().baseType(b.type())),
            b.registered(), b.type(), npc, st == null ? null : st.origins().get(b.id().toString()));
    }

    /** Starts the plan (stored in the country's data), worked off by the planner turns. */
    public void activate(final UUID country, final TownRedesign.Plan p) {
        this.activate(country, p, false, false);
    }

    /**
     * Starts the plan. {@code forced}: an admin / panel order, worked off every few seconds without the daily levelling
     * limit; {@code free}: the treasury does not pay.
     */
    public void activate(final UUID country, final TownRedesign.Plan p, final boolean forced, final boolean free) {
        if (!this.plugin.automaticBuildingAllowed(country)) return;
        final CountryState st = this.data().state(country);
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        final BlockPos c = k == null ? null : this.plugin.settlement().center(k);
        st.planRoads = new ArrayList<>(p.newRoads());
        st.planDemolish = new ArrayList<>();
        for (final UUID id : p.demolish()) {
            st.planDemolish.add(id.toString());
        }
        st.planQuarters = new ArrayList<>();
        st.planFill = new ArrayList<>();
        for (final TownRedesign.Quarter q : p.quarters()) {
            if (q.green()) {
                continue; // green quarters keep their trees
            }
            st.planFill.add(new int[] {q.minX(), q.minZ(), q.maxX(), q.maxZ()});
            // Occupied quarters still need their open yards and access lanes graded. Kept buildings are
            // protected separately and become floor-height anchors instead of vetoing the whole quarter.
            st.planQuarters.add(new int[] {q.minX(), q.minZ(), q.maxX(), q.maxZ()});
        }
        if (c != null) {
            final Comparator<int[]> byDistance = Comparator.comparingDouble(q -> Math.hypot((q[0] + q[2]) / 2.0 - c.x(), (q[1] + q[3]) / 2.0 - c.z()));
            st.planQuarters.sort(byDistance);
            st.planFill.sort(byDistance);
            if (!p.quarters().isEmpty()) {
                final int half = TownRedesign.squareHalf(this.plugin.postep().era(country));
                st.planSquare = new int[] {c.x() - half, c.z() - half, c.x() + half, c.z() + half};
                st.planQuarters.add(0, st.planSquare.clone());
            } else {
                st.planSquare = null;
            }
        }
        st.planQuartersTotal = st.planQuarters.size();
        st.planFillTotal = st.planFill.size();
        st.planLevels = null;
        st.planLevelsReady = false;
        st.planTerrainVersion = TERRAIN_PLAN_VERSION;
        st.planSquareLevel = Integer.MIN_VALUE;
        st.planForced = forced;
        st.planFree = free;
        st.planCreated = System.currentTimeMillis();
        st.planActive = true;
        this.levelWaits.remove(country);
        this.data().markDirty();
    }

    /** A building is on the plan's demolition list. */
    public boolean toDemolish(final Building b) {
        final CountryState st = b.countryId() == null ? null : this.data().stateIfPresent(b.countryId());
        return st != null && st.planActive && st.planDemolish != null && st.planDemolish.contains(b.id().toString());
    }

    /** NPC countries: a new plan when due (after advances: {@link #due}). */
    public void maybeReplan(final UUID country) {
        final CountryState st = this.data().state(country);
        final long interval = this.plugin.settings().redesignIntervalHours * 3_600_000L;
        if (!st.planActive && (st.planCreated == 0 || System.currentTimeMillis() - st.planCreated > interval || st.planDue)) {
            final TownRedesign.Plan p = this.compute(country);
            if (p != null && (!p.newRoads().isEmpty() || !p.demolish().isEmpty() || !p.quarters().isEmpty())) {
                this.activate(country, p);
            }
            st.planDue = false;
        }
    }

    /** Epoch / level advance: the next planning turn recomputes the plan (an admin redesign under way finishes first). */
    public void due(final UUID country) {
        final CountryState st = this.data().state(country);
        st.planDue = true;
        if (!(st.planActive && st.planForced)) {
            st.planActive = false;
        }
        this.data().markDirty();
    }

    /**
     * One step of the active plan within the budget. True when something was ordered. Called by the planner of NPC
     * countries and every few minutes for players with a confirmed plan.
     */
    public boolean step(final UUID country, final long budget) {
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(country))return false;
        return this.work(country, budget, 1) > 0;
    }

    // ------------------------------------------------------------------ the work

    private static boolean empty(final List<?> l) {
        return l == null || l.isEmpty();
    }

    /** Upgrade only the remaining terrain plan, preserving paid orders, demolition progress and forced/free flags. */
    static boolean migrateTerrainPlan(final CountryState st) {
        if (st.planTerrainVersion >= TERRAIN_PLAN_VERSION) {
            return false;
        }
        final List<int[]> remaining = new ArrayList<>();
        if (st.planQuarters != null) {
            remaining.addAll(st.planQuarters);
        }
        if (st.planFill != null) {
            for (final int[] q : st.planFill) {
                if (remaining.stream().noneMatch(r -> same(r, q))) {
                    remaining.add(q.clone());
                }
            }
        }
        st.planQuarters = remaining;
        st.planQuartersTotal = remaining.size();
        st.planLevels = null;
        st.planLevelsReady = false;
        st.planTerrainVersion = TERRAIN_PLAN_VERSION;
        return true;
    }

    /** Works the plan off: at most {@code maxOrders} orders. Returns the orders placed. */
    int work(final UUID country, final long budget, final int maxOrders) {
        if (!this.plugin.automaticBuildingAllowed(country)) return 0;
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null || !st.planActive) {
            return 0;
        }
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null) {
            return 0;
        }
        final BlockPos c = this.plugin.settlement().center(k);
        final World w = Bukkit.getWorld(c.world());
        if (w == null) {
            return 0;
        }
        if (st.planFill == null) {
            // a plan from an older version: its quarters are filled as well
            st.planFill = st.planQuarters == null ? new ArrayList<>() : new ArrayList<>(st.planQuarters);
            st.planFillTotal = st.planFill.size();
            st.planQuartersTotal = st.planQuarters == null ? 0 : st.planQuarters.size();
        }
        if (migrateTerrainPlan(st)) {
            this.data().markDirty();
        }
        long left = st.planFree ? Long.MAX_VALUE / 4 : budget;
        int orders = 0;
        // 1. demolitions: all at once for admin orders, otherwise one every demolish-hours
        final long now = System.currentTimeMillis();
        while (!empty(st.planDemolish)
            && (st.planForced || now - st.lastDemolition >= this.plugin.settings().redesignDemolishHours * 3_600_000L)) {
            final String id = st.planDemolish.remove(0);
            final Building b = this.data().building(UUID.fromString(id));
            if (b != null && this.demolishable(b) && this.plugin.construction().orderDemolition(b, null, ConstructionService.REDESIGN)) {
                st.lastDemolition = now;
                orders++;
                if (!st.planForced) {
                    break;
                }
            }
            this.data().markDirty();
        }
        if (!empty(st.planDemolish)) {
            return orders; // not all demolitions ordered yet (not forced: one every demolish-hours)
        }
        // quarters next to a demolition under way wait for it (it changes their ground); the others go on
        final List<BlockBox> demolishing = this.demolitionBoxes(country);
        final TerrainView view = this.plugin.terrain().view(c.world(), country);
        // 2. levels of the quarters and the square (once, after the demolitions)
        if (!st.planLevelsReady && !empty(st.planQuarters) && !this.computeLevels(country, st, c, view)) {
            return orders;
        }
        // 3. grading, quarter by quarter from the centre
        int grading = this.gradingSites(country);
        final int maxGrading = st.planForced ? 2 : 1;
        while (!empty(st.planQuarters) && grading < maxGrading && orders < maxOrders) {
            int[] q = null;
            for (final int[] c2 : st.planQuarters) {
                if (!near(demolishing, c2, EDGE_RAMP + KEEP_MARGIN)) {
                    q = c2;
                    break;
                }
            }
            if (q == null) {
                break;
            }
            final BuildOrderResult r = this.orderGrading(country, st, w, view, q);
            if (r == BuildOrderResult.OK) {
                st.planQuarters.remove(q);
                grading++;
                orders++;
                this.data().markDirty();
            } else if (r == BuildOrderResult.AREA_OBSTRUCTED) {
                st.planQuarters.remove(q); // nothing to change there (flat already, water, foreign land)
                this.data().markDirty();
            } else {
                break; // no free slot, no money or terrain not scanned yet: later
            }
        }
        // 4. streets on the final ground, after the grading and the demolitions
        if (empty(st.planQuarters) && grading == 0 && demolishing.isEmpty()) {
            while (!empty(st.planRoads) && orders < maxOrders) {
                final List<Long> section = new ArrayList<>(st.planRoads.subList(0, Math.min(ROAD_SECTION, st.planRoads.size())));
                final List<int[]> path = new ArrayList<>();
                for (final long key : section) {
                    path.add(new int[] {RoadPlanner.keyX(key), RoadPlanner.keyZ(key)});
                }
                final List<BlockPos> cells = this.plugin.settlement().roadCells(c.world(), country, path, c.y());
                final long cost = st.planFree ? 0 : cells.size() * ROAD_COST_PER_BLOCK;
                if (cost > left) {
                    break;
                }
                if (this.plugin.construction().orderRoad(country, c.world(), cells, cost, ConstructionService.REDESIGN).result()
                    != BuildOrderResult.OK) {
                    break; // queue full or no money: the rest follows
                }
                st.planRoads.subList(0, section.size()).clear();
                left -= cost;
                orders++;
                this.data().markDirty();
            }
        }
        // 5. buildings in the graded quarters (the queue is fed as orders finish); while quarters wait for their grading
        //    one site slot stays free for it and nothing is queued (buildings would take every slot first)
        boolean gradePending = false;
        if (st.planQuarters != null) {
            for (final int[] q : st.planQuarters) {
                gradePending |= !near(demolishing, q, EDGE_RAMP + KEEP_MARGIN);
            }
        }
        int attempts = 0;
        final Set<int[]> tried = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (!empty(st.planFill) && orders < maxOrders && attempts++ < 8
            && this.plugin.construction().admission(country) != pl.medivalrpg.ludnosc.logic.QueueRules.Admission.FULL) {
            if (gradePending && (this.data().queueSize(country) > 0
                || this.plugin.construction().sitesInUse(country) >= this.plugin.construction().maxSites(country) - 1)) {
                break;
            }
            final int[] q = this.nextReady(country, st, tried);
            if (q == null) {
                break;
            }
            final String key = country + ":" + q[0] + ":" + q[1];
            final Fill f = this.fillOne(country, k, c, w, view, q, left, st.planFree);
            if (f == Fill.ORDERED) {
                orders++;
                this.fullMisses.remove(key);
                left = st.planFree ? left : Math.min(left, this.plugin.settlement().treasury(country));
            } else if (f == Fill.FULL) {
                tried.add(q);
                // twice in a row without room (the terrain scan may lag behind the grading): the quarter is full
                if (this.fullMisses.merge(key, 1, Integer::sum) >= 2) {
                    this.fullMisses.remove(key);
                    st.planFill.remove(q);
                    this.data().markDirty();
                }
            } else {
                break;
            }
        }
        // 6. done
        if (empty(st.planDemolish) && empty(st.planQuarters) && empty(st.planRoads) && empty(st.planFill)
            && this.gradingSites(country) == 0) {
            st.planActive = false;
            st.planForced = false;
            this.data().markDirty();
            this.plugin.notifyCountry(country, "redesign-done");
            this.plugin.getLogger().info("Przebudowa " + k.name() + " zakończona.");
        }
        return orders;
    }

    /** Boxes of the buildings the country is demolishing (under way or queued). */
    private List<BlockBox> demolitionBoxes(final UUID country) {
        final List<BlockBox> out = new ArrayList<>();
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            final Building b = s.is(ConstructionSite.DEMOLISH) && s.repairOf != null ? this.data().building(s.repairOf) : null;
            if (b != null) {
                out.add(b.box());
            }
        }
        for (final QueuedOrder o : this.data().queue(country)) {
            final Building b = ConstructionSite.DEMOLISH.equals(o.kind()) && o.repairOf != null ? this.data().building(o.repairOf) : null;
            if (b != null) {
                out.add(b.box());
            }
        }
        return out;
    }

    private static boolean near(final List<BlockBox> boxes, final int[] q, final int margin) {
        for (final BlockBox b : boxes) {
            if (b.maxX() >= q[0] - margin && b.minX() <= q[2] + margin && b.maxZ() >= q[1] - margin && b.minZ() <= q[3] + margin) {
                return true;
            }
        }
        return false;
    }

    private int gradingSites(final UUID country) {
        int n = 0;
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (s.is(ConstructionSite.LEVEL)) {
                n++;
            }
        }
        return n;
    }

    /** The first quarter to fill whose grading is done (not waiting, no grading site on it). */
    private int[] nextReady(final UUID country, final CountryState st, final Set<int[]> tried) {
        final List<BlockBox> grading = new ArrayList<>();
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (s.is(ConstructionSite.LEVEL) && s.road().size() >= 2) {
                final BlockPos a = s.road().get(0);
                final BlockPos b = s.road().get(1);
                grading.add(new BlockBox(s.world, a.x(), 0, a.z(), b.x(), 0, b.z()));
            }
        }
        for (final int[] q : st.planFill) {
            boolean ready = !tried.contains(q);
            if (st.planQuarters != null) {
                for (final int[] g : st.planQuarters) {
                    if (g[0] == q[0] && g[1] == q[1] && g[2] == q[2] && g[3] == q[3]) {
                        ready = false;
                        break;
                    }
                }
            }
            for (final BlockBox g : grading) {
                if (ready && g.maxX() >= q[0] && g.minX() <= q[2] && g.maxZ() >= q[1] && g.minZ() <= q[3]) {
                    ready = false;
                }
            }
            if (ready) {
                return q;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ grading

    /** Exact footprints and real ground-floor levels, never roof heights from the surface scanner. */
    private List<TownGrade.Area> floorAnchors(final String world) {
        final List<TownGrade.Area> out = new ArrayList<>();
        for (final Building b : this.data().allBuildings()) {
            if (!world.equals(b.box().world())) {
                continue;
            }
            final BuildingTemplate template = this.plugin.templates().get(b.type());
            if (template != null) {
                out.add(new TownGrade.Area(b.box().minX(), b.box().minZ(), b.box().maxX(), b.box().maxZ(),
                    b.box().minY() + template.meta().groundLayer()));
            }
        }
        return out;
    }

    private static boolean anchored(final BlockBox b, final List<TownGrade.Area> floors) {
        return floors.stream().anyMatch(a -> a.minX() == b.minX() && a.minZ() == b.minZ()
            && a.maxX() == b.maxX() && a.maxZ() == b.maxZ());
    }

    /** Boxes of everything that keeps its ground: buildings, sites, queued plots (+ margin by the callers). */
    private List<BlockBox> kept(final String world) {
        return this.plugin.settlement().taken(world);
    }

    private static boolean near(final List<BlockBox> boxes, final int x, final int z, final int margin) {
        for (final BlockBox b : boxes) {
            if (x >= b.minX() - margin && x <= b.maxX() + margin && z >= b.minZ() - margin && z <= b.maxZ() + margin) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(final int[] a, final int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
    }

    /** Levels of the quarters to grade and of the square; false while the terrain is not scanned yet. */
    private boolean computeLevels(final UUID country, final CountryState st, final BlockPos c, final TerrainView view) {
        final List<BlockBox> kept = this.kept(c.world());
        final List<TownGrade.Area> floors = this.floorAnchors(c.world());
        int unknown = 0;
        for (final int[] q : st.planQuarters) {
            boolean pending = false;
            // A known centre does not mean the whole quarter is scanned: the edges often fall into
            // neighbouring chunks. Wait for them instead of permanently discarding a usable quarter.
            for (int x = q[0]; x <= q[2] && !pending; x++) {
                for (int z = q[1]; z <= q[3]; z++) {
                    if (view.own(x, z) && !near(kept, x, z, 0) && view.height(x, z) == TerrainView.UNKNOWN) {
                        pending = true;
                        break;
                    }
                }
            }
            if (pending) {
                unknown++;
            }
        }
        if (unknown > 0 && this.levelWaits.merge(country, 1, Integer::sum) < LEVEL_WAIT_TURNS) {
            this.plugin.terrain().requestAround(country, c.world(), c.x() >> 4, c.z() >> 4, this.plugin.settings().redesignRadius / 16 + 1);
            return false;
        }
        final java.util.function.IntBinaryOperator height = (x, z) -> {
            if (!view.own(x, z) || view.liquid(x, z)) {
                return TerrainView.UNKNOWN;
            }
            for (final TownGrade.Area a : floors) {
                if (a.contains(x, z)) {
                    return a.level();
                }
            }
            return near(kept, x, z, 0) ? TerrainView.UNKNOWN : view.height(x, z);
        };
        int square = -1;
        for (int i = 0; i < st.planQuarters.size(); i++) {
            if (same(st.planQuarters.get(i), st.planSquare)) {
                square = i;
            }
        }
        final int[] levels = TownGrade.levels(st.planQuarters, square, height);
        final List<int[]> kept2 = new ArrayList<>();
        st.planLevels = new ArrayList<>();
        for (int i = 0; i < st.planQuarters.size(); i++) {
            final int[] q = st.planQuarters.get(i);
            if (levels[i] == TerrainView.UNKNOWN && TownGrade.occupied(q, floors)) {
                // Its open ground can still be joined to preserved floors even when the whole quarter is
                // too rough for a single plateau. No plateau is generated for an occupied quarter.
                final int[] anchoredLevels = floors.stream().filter(a -> TownGrade.occupied(q, List.of(a)))
                    .mapToInt(TownGrade.Area::level).sorted().toArray();
                levels[i] = anchoredLevels[anchoredLevels.length / 2];
            }
            if (levels[i] == TerrainView.UNKNOWN) {
                continue; // too rough or unknown: stays natural (its plots are levelled by their builders)
            }
            st.planLevels.add(new int[] {q[0], q[1], q[2], q[3], levels[i]});
            kept2.add(q);
            if (i == square) {
                st.planSquareLevel = levels[i];
            }
        }
        st.planQuarters = kept2;
        st.planQuartersTotal = kept2.size();
        st.planLevelsReady = true;
        this.levelWaits.remove(country);
        this.data().markDirty();
        return true;
    }

    /** Orders the grading of one quarter (or the square). */
    private BuildOrderResult orderGrading(final UUID country, final CountryState st, final World w, final TerrainView view, final int[] q) {
        if (st.planLevels == null) {
            return BuildOrderResult.AREA_OBSTRUCTED;
        }
        int level = TerrainView.UNKNOWN;
        final List<TownGrade.Area> floors = this.floorAnchors(w.getName());
        final List<TownGrade.Area> flats = TownGrade.flats(st.planLevels, st.planSquare, floors);
        for (final int[] l : st.planLevels) {
            if (same(l, q)) {
                level = l[4];
            }
        }
        if (level == TerrainView.UNKNOWN) {
            return BuildOrderResult.AREA_OBSTRUCTED;
        }
        final List<BlockBox> kept = this.kept(w.getName());
        final List<BlockBox> buffered = kept.stream().filter(b -> !anchored(b, floors)).toList();
        final List<int[]> domain = st.planLevels;
        final java.util.function.BiPredicate<Integer, Integer> graded = (x, z) -> {
            boolean in = false;
            for (final int[] l : domain) {
                if (x >= l[0] - EDGE_RAMP && x <= l[2] + EDGE_RAMP && z >= l[1] - EDGE_RAMP && z <= l[3] + EDGE_RAMP) {
                    in = true;
                    break;
                }
            }
            return in && view.own(x, z) && !view.liquid(x, z) && !near(kept, x, z, 0)
                && !near(buffered, x, z, KEEP_MARGIN) && !this.plugin.design().untouchable(country, x, z);
        };
        final java.util.function.IntBinaryOperator natural = (x, z) -> near(kept, x, z, 0) ? TerrainView.UNKNOWN : view.height(x, z);
        // half of the street towards a graded neighbour, a longer ramp towards the edge of the town
        final int minX = q[0] - this.margin(domain, q, -1, 0);
        final int minZ = q[1] - this.margin(domain, q, 0, -1);
        final int maxX = q[2] + this.margin(domain, q, 1, 0);
        final int maxZ = q[3] + this.margin(domain, q, 0, 1);
        // the ground around kept buildings is met by the slopes
        final java.util.function.BiPredicate<Integer, Integer> anchor = (x, z) -> near(kept, x, z, KEEP_MARGIN + 1) && !near(kept, x, z, 0);
        final java.util.function.BiPredicate<Integer, Integer> core = (x, z) -> {
            for (final int[] l : domain) {
                if (x >= l[0] - GRADE_MARGIN && x <= l[2] + GRADE_MARGIN && z >= l[1] - GRADE_MARGIN && z <= l[3] + GRADE_MARGIN) {
                    return true;
                }
            }
            return false;
        };
        final Map<Long, Integer> targets = TownGrade.targets(minX, minZ, maxX, maxZ, flats, graded, core, anchor, natural);
        final int[] interior = same(q, st.planSquare) ? q : TownGrade.interior(q[0], q[1], q[2], q[3]);
        final List<int[]> cols = new ArrayList<>();
        int unknown = 0;
        for (final Map.Entry<Long, Integer> e : targets.entrySet()) {
            final int x = RoadPlanner.keyX(e.getKey());
            final int z = RoadPlanner.keyZ(e.getKey());
            if (!graded.test(x, z)) {
                continue;
            }
            final int old = view.height(x, z);
            if (old == TerrainView.UNKNOWN) {
                unknown++;
                continue;
            }
            final boolean plot = !TownGrade.occupied(q, floors)
                && x >= interior[0] && x <= interior[2] && z >= interior[1] && z <= interior[3];
            // over the quarter every column (remains are cleared); elsewhere changed ones and those with street furniture or
            // leftovers on top (a slab or lamp left hanging after older works)
            if (plot || old != e.getValue() || view.obstacle(x, z)) {
                cols.add(new int[] {x, z, old, e.getValue(), Integer.MAX_VALUE, Integer.MIN_VALUE, plot ? 1 : 0});
            }
        }
        if (cols.isEmpty()) {
            return unknown > 0 ? BuildOrderResult.TOO_MANY_ORDERS : BuildOrderResult.AREA_OBSTRUCTED;
        }
        // rows from north to south: the builders walk along them
        cols.sort(Comparator.<int[]>comparingInt(a -> a[1]).thenComparingInt(a -> a[0]));
        final BuildOrderResult r = this.plugin.construction().orderGrading(country, w, minX, minZ, maxX, maxZ, level, cols, st.planFree);
        return r == BuildOrderResult.INSUFFICIENT_FUNDS ? BuildOrderResult.TOO_MANY_ORDERS : r;
    }

    /** Grading margin of a quarter on one side: {@link #GRADE_MARGIN} next to another graded area, else {@link #EDGE_RAMP}. */
    private int margin(final List<int[]> graded, final int[] q, final int dx, final int dz) {
        for (final int[] l : graded) {
            if (same(l, q)) {
                continue;
            }
            final boolean overlapX = l[2] >= q[0] && l[0] <= q[2];
            final boolean overlapZ = l[3] >= q[1] && l[1] <= q[3];
            if (dx > 0 && overlapZ && l[0] > q[2] && l[0] - q[2] <= GRADE_MARGIN + 2
                || dx < 0 && overlapZ && l[2] < q[0] && q[0] - l[2] <= GRADE_MARGIN + 2
                || dz > 0 && overlapX && l[1] > q[3] && l[1] - q[3] <= GRADE_MARGIN + 2
                || dz < 0 && overlapX && l[3] < q[1] && q[1] - l[3] <= GRADE_MARGIN + 2) {
                return GRADE_MARGIN;
            }
        }
        return EDGE_RAMP;
    }

    // ------------------------------------------------------------------ filling the quarters

    private enum Fill { ORDERED, FULL, WAIT }

    /** Templates that never go into a town quarter (bridges, harbours, mines, walls, fields, stations...). */
    private Set<String> unsuitable(final int[] q) {
        final Set<String> out = new HashSet<>();
        final int qw = q[2] - q[0] + 1;
        final int qd = q[3] - q[1] + 1;
        for (final BuildingTemplate t : this.plugin.templates().all()) {
            final BuildingTemplate.Meta m = t.meta();
            final boolean fits = t.width() <= qw && t.depth() <= qd || t.depth() <= qw && t.width() <= qd;
            final boolean zone = switch (m.zone()) {
                case "water", "mountain", "forest", "border", "shore", "farm" -> false;
                default -> true;
            };
            final boolean category = switch (m.category()) {
                case "defense", "farm" -> false;
                case "infrastructure" -> m.function() == BuildingFunction.WELL;
                default -> true;
            };
            if (!fits || !zone || !category || m.function() == BuildingFunction.MINE || m.function() == BuildingFunction.BRIDGE) {
                out.add(t.id());
            }
        }
        return out;
    }

    /** One building into a quarter: what the town needs (housing otherwise), where the quarter has room. */
    private Fill fillOne(final UUID country, final KrajInfo k, final BlockPos c, final World w, final TerrainView view, final int[] q,
                         final long budget, final boolean free) {
        final TerrainView inQuarter = new TerrainView() {
            @Override
            public int height(final int x, final int z) {
                return view.height(x, z);
            }

            @Override
            public boolean liquid(final int x, final int z) {
                return view.liquid(x, z);
            }

            @Override
            public boolean obstacle(final int x, final int z) {
                return view.obstacle(x, z);
            }

            @Override
            public boolean own(final int x, final int z) {
                return x >= q[0] && x <= q[2] && z >= q[1] && z <= q[3] && view.own(x, z)
                    && !RedesignService.this.plugin.design().untouchable(country, x, z);
            }
        };
        BuildingChooser.Input in = this.plugin.settlement().input(country, free ? Long.MAX_VALUE / 4 : budget);
        if (free) {
            in = in.withBudget(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4);
        }
        final Set<String> skip = new HashSet<>(this.unsuitable(q));
        skip.addAll(this.plugin.settlement().backedOff(country));
        in = in.without(skip);
        final List<String> candidates = new ArrayList<>();
        final BuildingChooser.Choice choice = BuildingChooser.choose(in);
        // a need already answered by an order under way (a granary in the queue does not hold food yet) is not ordered twice
        final Set<String> pending = new HashSet<>();
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (s.is(ConstructionSite.BUILD) && s.repairOf == null) {
                pending.add(s.type);
            }
        }
        for (final QueuedOrder o : this.data().queue(country)) {
            if (ConstructionSite.BUILD.equals(o.kind()) && o.repairOf == null) {
                pending.add(o.type);
            }
        }
        if (choice.found() && (!pending.contains(choice.type()) || choice.reason() == BuildingChooser.Reason.HOUSING
            || choice.reason() == BuildingChooser.Reason.MORE_HOUSING)) {
            candidates.add(choice.type());
        }
        for (final BuildingTemplate.Meta m : BuildingChooser.housing(in)) {
            if (candidates.size() >= 4) {
                break;
            }
            if (!candidates.contains(m.id()) && m.cost() <= in.budget() && m.cost() <= in.treasury()) {
                candidates.add(m.id());
            }
        }
        if (candidates.isEmpty()) {
            return Fill.WAIT; // nothing affordable / allowed now
        }
        final List<BlockBox> taken = this.plugin.settlement().taken(w.getName());
        final Set<Long> roads = new java.util.LinkedHashSet<>(this.data().state(country).roads());
        final int qx = (q[0] + q[2]) / 2;
        final int qz = (q[1] + q[3]) / 2;
        final int reach = (int) Math.ceil(Math.hypot(q[2] - q[0], q[3] - q[1]) / 2) + 2;
        final SiteFinder.Settings base = this.plugin.settlement().settings();
        final SiteFinder.Settings s = new SiteFinder.Settings(reach, 2, base.spacing(), base.maxSlope());
        boolean waited = false;
        for (final String type : candidates) {
            final BuildingTemplate t = this.plugin.templates().get(type);
            if (t == null) {
                continue;
            }
            final int[] size = {t.width(), t.depth()};
            final SiteFinder.Search search = SiteFinder.search(inQuarter, size, qx, qz, t.meta().zone(), taken, roads, s,
                t.meta().groundLayer(), "highrise".equals(t.meta().category()));
            final SiteFinder.Spot spot = search.spot();
            if (spot == null) {
                // Explicitly invalidated post-grading snapshots are not evidence that a quarter is full.
                // Keep it for the next planner turn, rather than dropping it after two failed scans.
                waited |= search.stats().unknown > 0;
                continue;
            }
            final BlockBox box = TownPlanner.footprint(w.getName(), size, spot);
            final List<BlockBox> obstacles = new ArrayList<>(taken);
            obstacles.add(box);
            final List<int[]> path = RoadPlanner.path(view, spot.doorX(), spot.doorZ(), roads, c.x(), c.z(), obstacles, 6000);
            final List<BlockPos> road = this.plugin.settlement().roadCells(w.getName(), country, path, spot.y());
            final String variant = this.plugin.construction().variantFor(country, type, w, spot.x(), spot.y(), spot.z(), spot.rotation());
            final ConstructionService.OrderOutcome out = this.plugin.construction().orderDetailed(country, variant, w, spot.x(), spot.y(),
                spot.z(), spot.rotation(), free, ConstructionService.REDESIGN, road);
            if (out.result() == BuildOrderResult.OK) {
                this.plugin.getLogger().info("Przebudowa " + k.name() + ": " + type + " w kwartale " + q[0] + " " + q[1] + " → " + q[2] + " "
                    + q[3] + (out.queued() ? " (kolejka " + out.position() + ")" : ""));
                return Fill.ORDERED;
            }
            if (out.result() == BuildOrderResult.TOO_MANY_ORDERS || out.result() == BuildOrderResult.INSUFFICIENT_FUNDS) {
                waited = true;
                break;
            }
        }
        return waited ? Fill.WAIT : Fill.FULL;
    }

    // ------------------------------------------------------------------ admin: at once, idempotent

    /** What {@link #force} did. */
    public enum ForceKind { STARTED, SPED_UP, BUSY, FAILED }

    public record ForceOutcome(ForceKind kind, int orders) {
    }

    /**
     * Progress of a redesign.
     *
     * @param levelled quarters graded (incl. the square) of {@code quarters}
     * @param filled   quarters without room left of {@code fill}
     * @param queued   orders of the redesign waiting in the queue
     * @param sites    construction sites of the redesign (demolitions, grading, streets, buildings)
     */
    public record Progress(boolean active, boolean forced, int levelled, int quarters, int filled, int fill, int demolish, int roads,
                           int queued, int sites) {
    }

    public Progress progress(final UUID country) {
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null) {
            return new Progress(false, false, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int queued = 0;
        for (final QueuedOrder o : this.data().queue(country)) {
            if (ConstructionService.REDESIGN.equals(o.orderedBy) || ConstructionSite.DEMOLISH.equals(o.kind())) {
                queued++;
            }
        }
        int sites = 0;
        int grading = 0;
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (s.is(ConstructionSite.LEVEL)) {
                grading++;
            }
            if (ConstructionService.REDESIGN.equals(s.orderedBy) || s.is(ConstructionSite.DEMOLISH) || s.is(ConstructionSite.LEVEL)) {
                sites++;
            }
        }
        final int toLevel = st.planQuarters == null ? 0 : st.planQuarters.size();
        final int toFill = st.planFill == null ? 0 : st.planFill.size();
        return new Progress(st.planActive, st.planForced, Math.max(0, st.planQuartersTotal - toLevel - grading), st.planQuartersTotal,
            Math.max(0, st.planFillTotal - toFill), st.planFillTotal, st.planDemolish == null ? 0 : st.planDemolish.size(),
            st.planRoads == null ? 0 : st.planRoads.size(), queued, sites);
    }

    /** A redesign is active and has work left (lists or orders under way). */
    public boolean inProgress(final UUID country) {
        final Progress p = this.progress(country);
        final CountryState st = this.data().stateIfPresent(country);
        return p.active() && (p.demolish() > 0 || p.roads() > 0 || !empty(st.planQuarters) || !empty(st.planFill) || p.sites() > 0
            || p.queued() > 0);
    }

    /**
     * Admin / panel: the whole plan at once — demolitions first, then grading, streets and buildings as fast as the
     * queue allows (fed every few seconds). Never stacks: while a redesign of this country is in progress nothing new is
     * planned ({@link ForceKind#BUSY}); a redesign the planner started on its own is just sped up.
     */
    public ForceOutcome force(final UUID country, final boolean free) {
        if (!this.plugin.automaticBuildingAllowed(country)) return new ForceOutcome(ForceKind.FAILED, 0);
        final CountryState st = this.data().state(country);
        if (this.plugin.kraje().getCountry(country).isEmpty()) {
            return new ForceOutcome(ForceKind.FAILED, -1);
        }
        if (this.inProgress(country)) {
            if (st.planForced) {
                return new ForceOutcome(ForceKind.BUSY, 0);
            }
            st.planForced = true;
            st.planFree = free || !this.plugin.settings().redesignAdminPays;
            this.data().markDirty();
            return new ForceOutcome(ForceKind.SPED_UP, this.pump(country));
        }
        final TownRedesign.Plan plan = this.compute(country);
        if (plan == null) {
            return new ForceOutcome(ForceKind.FAILED, -1);
        }
        this.activate(country, plan, true, free || !this.plugin.settings().redesignAdminPays);
        return new ForceOutcome(ForceKind.STARTED, this.pump(country));
    }

    /** {@code LudnoscApi#forceRedesign}: the number of orders placed now (0 when a redesign is already in progress). */
    public int forceOrders(final UUID country, final boolean free) {
        final ForceOutcome o = this.force(country, free);
        return o.kind() == ForceKind.FAILED ? -1 : o.orders();
    }

    /** A forced plan: as much as the queue takes now. */
    private int pump(final UUID country) {
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null) {
            return 0;
        }
        return this.work(country, st.planFree ? Long.MAX_VALUE / 4 : this.plugin.settlement().treasury(country), 12);
    }

    /**
     * Cancels the redesign: nothing more is planned, its queued orders are removed with a refund and the grading sites
     * stop. Demolitions and buildings already under construction go on. Returns {cancelled orders, refund}.
     */
    public long[] cancel(final UUID country) {
        final CountryState st = this.data().state(country);
        st.planActive = false;
        st.planForced = false;
        st.planRoads = new ArrayList<>();
        st.planDemolish = new ArrayList<>();
        st.planQuarters = new ArrayList<>();
        st.planFill = new ArrayList<>();
        st.planLevels = null;
        st.planLevelsReady = false;
        st.planCreated = System.currentTimeMillis(); // the NPC planner does not start a new plan right away
        long refund = 0;
        int n = 0;
        for (final QueuedOrder o : new ArrayList<>(this.data().queue(country))) {
            if (ConstructionService.REDESIGN.equals(o.orderedBy) && !ConstructionSite.DEMOLISH.equals(o.kind())) {
                refund += this.plugin.construction().cancelQueued(o, null);
                n++;
            }
        }
        for (final ConstructionSite s : new ArrayList<>(this.data().sitesOf(country))) {
            if (s.is(ConstructionSite.LEVEL)) {
                refund += this.plugin.construction().cancel(s, 1.0, null);
                n++;
            }
        }
        this.data().markDirty();
        return new long[] {n, refund};
    }

    /** Every 30 s: admin / panel redesigns feed the queue as orders finish. */
    public void tickForced() {
        for (final var e : new ArrayList<>(this.data().states().entrySet())) {
            final CountryState st = e.getValue();
            if (st.planActive && st.planForced) {
                try {
                    this.pump(e.getKey());
                } catch (final RuntimeException ex) {
                    this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd przebudowy " + e.getKey(), ex);
                }
            }
        }
    }

    // ------------------------------------------------------------------ players: preview and confirm

    /** Shows the plan for 20 s: new streets green, demolitions red, quarters to level yellow. Returns the plan. */
    public TownRedesign.Plan preview(final Player p, final UUID country) {
        final TownRedesign.Plan plan = this.compute(country);
        if (plan == null) {
            return null;
        }
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        final World w = k == null ? null : Bukkit.getWorld(this.plugin.settlement().center(k).world());
        if (w == null) {
            return plan;
        }
        final TerrainView view = this.plugin.terrain().view(w.getName(), country);
        final List<Location> green = new ArrayList<>();
        final List<Location> red = new ArrayList<>();
        final List<Location> yellow = new ArrayList<>();
        int i = 0;
        for (final long key : plan.newRoads()) {
            if (i++ % 2 == 0) {
                green.add(this.at(w, view, RoadPlanner.keyX(key), RoadPlanner.keyZ(key)));
            }
        }
        for (final UUID id : plan.demolish()) {
            final Building b = this.data().building(id);
            if (b != null) {
                for (int x = b.box().minX(); x <= b.box().maxX(); x += 2) {
                    red.add(new Location(w, x + 0.5, b.box().maxY() + 1.5, b.box().minZ() + 0.5));
                    red.add(new Location(w, x + 0.5, b.box().maxY() + 1.5, b.box().maxZ() + 0.5));
                }
            }
        }
        for (final TownRedesign.Quarter q : plan.quarters()) {
            if (q.free() && !q.green()) {
                for (int x = q.minX(); x <= q.maxX(); x += 3) {
                    yellow.add(this.at(w, view, x, q.minZ()));
                    yellow.add(this.at(w, view, x, q.maxZ()));
                }
            }
        }
        final BukkitTask[] task = new BukkitTask[1];
        final int[] runs = {0};
        task[0] = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (!p.isOnline() || ++runs[0] > 20) {
                task[0].cancel();
                return;
            }
            this.show(p, green, Color.LIME);
            this.show(p, red, Color.RED);
            this.show(p, yellow, Color.YELLOW);
        }, 0L, 20L);
        return plan;
    }

    private Location at(final World w, final TerrainView view, final int x, final int z) {
        final int h = view.height(x, z);
        return new Location(w, x + 0.5, (h == TerrainView.UNKNOWN ? w.getHighestBlockYAt(x, z) : h) + 1.2, z + 0.5);
    }

    private void show(final Player p, final List<Location> points, final Color color) {
        final Particle.DustOptions dust = new Particle.DustOptions(color, 1.4f);
        int n = 0;
        for (final Location l : points) {
            if (n++ > 600) {
                break;
            }
            if (l.getWorld().equals(p.getWorld()) && l.distanceSquared(p.getLocation()) < 160 * 160) {
                p.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    /** Price and time estimate of a plan: {copper, minutes}. */
    public long[] estimate(final TownRedesign.Plan plan) {
        long blocks = plan.newRoads().size();
        long cost = plan.newRoads().size() * ROAD_COST_PER_BLOCK;
        for (final TownRedesign.Quarter q : plan.quarters()) {
            if (q.free() && !q.green()) {
                final long area = (long) (q.maxX() - q.minX() + 1) * (q.maxZ() - q.minZ() + 1);
                blocks += area * 2;
                cost += area * this.plugin.settings().redesignLevelCostPerColumn;
            }
        }
        final long perDay = Math.max(1, this.plugin.settings().redesignLevelBlocksPerDay);
        final long minutes = Math.max(10, blocks * 60 / Math.max(1, (long) (this.plugin.settings().blocksPerSecond * 60 * 2)))
            + plan.demolish().size() * this.plugin.settings().redesignDemolishHours * 60L + blocks / perDay * 24 * 60 / 4;
        return new long[] {cost, minutes};
    }

    /** Players with a confirmed plan: one step every few minutes (NPC countries step in the planner). */
    public void tickPlayers() {
        for (final var e : new ArrayList<>(this.data().states().entrySet())) {
            final CountryState st = e.getValue();
            if (!st.planActive || st.planForced) {
                continue;
            }
            final boolean npc = this.plugin.kraje().getCountry(e.getKey()).map(KrajInfo::npc).orElse(true);
            if (!npc) {
                this.step(e.getKey(), this.plugin.settlement().treasury(e.getKey()));
            }
        }
    }
}
