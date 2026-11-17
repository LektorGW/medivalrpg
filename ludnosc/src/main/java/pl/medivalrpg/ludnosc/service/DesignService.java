// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult;
import pl.medivalrpg.ludnosc.logic.DesignOrderRunner;
import pl.medivalrpg.ludnosc.logic.DesignOrderRunner.Attempt;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.planner.DesignPlan;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.TerrainView;
import pl.medivalrpg.ludnosc.planner.WallRoute;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * The design plan drawn in the web panel ({@link DesignPlan}) — it has priority over the automatic town planner:
 * its buildings are queued in priority order exactly where drawn (forced like an explicit {@code orderBuilding},
 * terrain is levelled), its roads and walls follow the drawn lines (gates at the given points, or along the border /
 * around the town), its zones restrict the automatic planner (only matching types; forest: forestry only;
 * {@code nie_ruszac}: no works at all; {@code zabytki}: no demolition), {@code autoFill} lets the planner fill the
 * zones, {@code allowDemolish} / {@code keepExisting} decide about demolitions. Stored per country. Main thread.
 */
public final class DesignService {

    private static final int SECTION = 48;
    private static final long ROAD_COST_PER_BLOCK = 2;

    private final LudnoscPlugin plugin;
    private final Map<UUID, DesignPlan> cache = new ConcurrentHashMap<>();
    private int playerCursor;

    public DesignService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    /** Execute one explicitly saved player plan per turn; NPC countries are driven by their existing AI budget. */
    public void tickPlayerPlans() {
        final List<KrajInfo> countries=this.plugin.kraje().getCountries().stream()
            .filter(k -> !k.npc() && this.plan(k.id())!=null).toList();
        if (countries.isEmpty()) return;
        final KrajInfo country=countries.get(Math.floorMod(this.playerCursor++,countries.size()));
        this.step(country.id(),this.plugin.settlement().treasury(country.id()));
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** The plan of a country, or null. */
    public DesignPlan plan(final UUID country) {
        if (country == null) {
            return null;
        }
        final DesignPlan cached = this.cache.get(country);
        if (cached != null) {
            return cached;
        }
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null || st.designJson == null) {
            return null;
        }
        try {
            final DesignPlan p = DesignPlan.parse(st.designJson);
            this.cache.put(country, p);
            return p;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public Optional<String> json(final UUID country) {
        final CountryState st = country == null ? null : this.data().stateIfPresent(country);
        return Optional.ofNullable(st == null ? null : st.designJson);
    }

    /** Validates and stores a plan; returns the warnings (empty = OK). A broken plan is not stored. */
    public List<String> set(final UUID country, final String json) {
        final List<String> warnings = new ArrayList<>();
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null) {
            warnings.add("nieznany kraj");
            return warnings;
        }
        final DesignPlan p;
        try {
            p = DesignPlan.parse(json);
        } catch (final IllegalArgumentException e) {
            warnings.add(e.getMessage());
            return warnings;
        }
        warnings.addAll(p.check(new HashSet<>(this.plugin.templates().ids())));
        warnings.addAll(this.worldChecks(k, p));
        if (!k.npc()) {
            warnings.addAll(playerPlanNotes(this.plugin.settings().playerPlanWalls, !p.walls.isEmpty(),
                !p.buildings.isEmpty() || !p.roads.isEmpty()));
        }
        final CountryState st = this.data().state(country);
        st.designJson = p.toJson();
        st.designExecutionPaused=false;
        st.designDone = new ArrayList<>();
        st.designFailed = new ArrayList<>();
        st.designFailures = new java.util.LinkedHashMap<>();
        st.designRoadsLeft = null;
        st.designWallsLeft = null;
        st.designWallsDone = null;
        this.cache.put(country, p);
        this.data().markDirty();
        return warnings;
    }

    public boolean clear(final UUID country) {
        final CountryState st = this.data().stateIfPresent(country);
        this.cache.remove(country);
        if (st == null || st.designJson == null) {
            return false;
        }
        st.designJson = null;
        st.designExecutionPaused=false;
        st.designFailures = null;
        st.designRoadsLeft = null;
        st.designWallsLeft = null;
        this.data().markDirty();
        return true;
    }

    private boolean own(final KrajInfo k, final String world, final int x, final int z) {
        return this.plugin.kraje().getCountryAt(world, x >> 4, z >> 4).map(KrajInfo::id).map(k.id()::equals).orElse(false);
    }

    /** Footprint of a planned building (rotation in degrees), or null for an unknown type. */
    BlockBox footprint(final String world, final DesignPlan.Building b) {
        final BuildingTemplate t = b.type == null ? null : this.plugin.templates().get(b.type);
        if (t == null) {
            return null;
        }
        final int q = Math.floorMod(b.rotation / 90, 4);
        final int w = q % 2 == 0 ? t.width() : t.depth();
        final int d = q % 2 == 0 ? t.depth() : t.width();
        final int minX = b.x - w / 2;
        final int minZ = b.z - d / 2;
        return new BlockBox(world, minX, -64, minZ, minX + w - 1, 320, minZ + d - 1);
    }

    private List<String> worldChecks(final KrajInfo k, final DesignPlan p) {
        final List<String> out = new ArrayList<>();
        final String world = this.plugin.settlement().center(k).world();
        final List<BlockBox> planned = new ArrayList<>();
        final var input = this.plugin.settlement().input(k.id(), Long.MAX_VALUE);
        for (int i = 0; i < p.buildings.size(); i++) {
            final DesignPlan.Building b = p.buildings.get(i);
            final BlockBox box = this.footprint(world, b);
            if (box == null) {
                continue; // reported by check()
            }
            final String name = "budynek #" + (i + 1) + " (" + b.type + ")";
            if (!this.own(k, world, box.minX(), box.minZ()) || !this.own(k, world, box.maxX(), box.maxZ())
                || !this.own(k, world, box.minX(), box.maxZ()) || !this.own(k, world, box.maxX(), box.minZ())) {
                out.add(name + ": obrys wychodzi poza ziemię kraju");
            }
            if (this.plugin.buildings().overlapsAny(box)) {
                out.add(name + ": koliduje z istniejącym budynkiem");
            }
            for (final BlockBox other : planned) {
                if (other.intersects(box)) {
                    out.add(name + ": koliduje z innym budynkiem planu");
                    break;
                }
            }
            planned.add(box);
            final List<String> missing = this.plugin.settlement().requirementsMissing(this.plugin.templates().get(b.type).meta(), input);
            missing.removeIf(m -> m.startsWith("koszt"));
            if (!missing.isEmpty()) {
                out.add(name + ": wymagania niespełnione (" + String.join(", ", missing) + ") — poczeka");
            }
            if (p.untouchable(b.x, b.z)) {
                out.add(name + ": stoi w strefie nie_ruszac — zostanie pominięty");
            }
        }
        for (int i = 0; i < p.roads.size(); i++) {
            int foreign = 0;
            for (final int[] c : DesignPlan.cells(p.roads.get(i).points, 1, false)) {
                if (!this.own(k, world, c[0], c[1])) {
                    foreign++;
                }
            }
            if (foreign > 0) {
                out.add("droga #" + (i + 1) + ": " + foreign + " bl. na obcej ziemi — pominięte");
            }
        }
        for (int i = 0; i < p.walls.size(); i++) {
            final DesignPlan.Wall w = p.walls.get(i);
            if (w.mode != null) {
                continue;
            }
            int foreign = 0;
            for (final int[] c : DesignPlan.cells(w.points, 1, w.closed)) {
                if (!this.own(k, world, c[0], c[1])) {
                    foreign++;
                }
            }
            if (foreign > 0) {
                out.add("mur #" + (i + 1) + ": " + foreign + " bl. na obcej ziemi — pominięte");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ execution

    /**
     * One step of the plan (called by the planner before anything automatic): the next building by priority, then
     * roads, then walls. True when something was ordered.
     */
    public boolean step(final UUID country, final long budget) {
        // player countries: no automatic building; with player-countries.plan-walls the ruler's drawn walls are built
        final boolean automatic = this.plugin.automaticBuildingAllowed(country);
        if (!automatic && !this.plugin.settings().playerPlanWalls) return false;
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(country))return false;
        final CountryState paused=this.data().stateIfPresent(country);
        if(paused!=null&&paused.designExecutionPaused)return false;
        final DesignPlan p = this.plan(country);
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (p == null || k == null) {
            return false;
        }
        final CountryState st = this.data().state(country);
        final BlockPos c = this.plugin.settlement().center(k);
        final World w = Bukkit.getWorld(c.world());
        if (w == null) {
            return false;
        }
        final TerrainView view = this.plugin.terrain().view(c.world(), country);
        if (automatic) {
        // Unavailable epochs, unscanned chunks and temporary obstructions cannot freeze later available buildings.
        final var run=DesignOrderRunner.run(st,p.buildingsByPriority(),b->b.type+"@"+b.x+","+b.z,b->{
            if(!pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(country,b.type))return Attempt.rejected("RESERVED_TEMPLATE","Ten pałac jest zastrzeżony wyłącznie dla KRÓLESTWA.");
            if(p.untouchable(b.x,b.z)) return Attempt.rejected("PROTECTED_ZONE","Budynek leży w strefie nie_ruszac — plan chroni to miejsce.");
            final BuildingTemplate t=this.plugin.templates().get(b.type);
            if(t==null) return Attempt.rejected("INVALID_TEMPLATE","Nie ma szablonu budynku: "+b.type+".");
            // Era/discovery must precede the money gate: an unreachable costly modern tower must not reserve all funds.
            final Attempt gate=DesignOrderRunner.gate(t.meta().era(),this.plugin.postep().era(country),
                t.meta().discovery().isEmpty() || this.plugin.postep().hasDiscovery(country,t.meta().discovery()),t.meta().cost(),budget,
                "Wymaga epoki: "+pl.medivalrpg.ludnosc.hook.PostepHook.eraName(t.meta().era())+". Pozostałe dostępne budynki mogą powstać wcześniej.",
                "Wymaga odkrycia: "+pl.medivalrpg.ludnosc.hook.PostepHook.discoveryName(t.meta().discovery())+".",
                "Brak środków: potrzeba "+this.plugin.formatMoney(t.meta().cost())+", dostępne "+this.plugin.formatMoney(budget)+".");
            if(gate!=null) return gate;
            final int height=view.height(b.x,b.z);
            if(height==TerrainView.UNKNOWN) {
                this.plugin.terrain().requestAround(country,c.world(),b.x>>4,b.z>>4,2);
                return Attempt.retry("TERRAIN_SCANNING","Teren przy "+b.x+" "+b.z+" jest jeszcze skanowany.");
            }
            // Keep the existing player protection: explicit drawing never forces demolition of player structures.
            final var outcome=this.plugin.construction().orderDetailed(country,b.type,w,b.x,height,b.z,
                Math.floorMod(b.rotation/90,4),false,"plan",null,k.npc());
            if(outcome.result()==BuildOrderResult.OK) return Attempt.ordered();
            if(outcome.result()==BuildOrderResult.INSUFFICIENT_FUNDS) return Attempt.waiting("INSUFFICIENT_FUNDS","Brak środków w skarbcu na budowę.");
            if(outcome.result()==BuildOrderResult.TOO_MANY_ORDERS) return Attempt.waiting("TOO_MANY_ORDERS","Kolejka budowy jest pełna. Pozycja poczeka na wolne miejsce.");
            final String why=this.plugin.construction().lastCheckReason(country);
            final String reason=why==null || why.isBlank()?"Budowa czeka: "+outcome.result().name():why;
            this.plugin.settlement().setFailure(country,"plan: "+b.type+" w "+b.x+" "+b.z+": "+reason);
            return Attempt.retry(outcome.result().name(),reason);
        },System.currentTimeMillis());
        if(run.changed()) this.data().markDirty();
        if(run.ordered()) return true;
        if(run.blocked()) return false;
        // 2. roads along the drawn lines
        if (st.designRoadsLeft == null) {
            st.designRoadsLeft = new ArrayList<>();
            for (final long key : p.roadCells()) {
                if (!st.roads().contains(key) && this.own(k, c.world(), RoadPlanner.keyX(key), RoadPlanner.keyZ(key))
                    && !p.untouchable(RoadPlanner.keyX(key), RoadPlanner.keyZ(key))) {
                    st.designRoadsLeft.add(key);
                }
            }
        }
        if (!st.designRoadsLeft.isEmpty()) {
            final List<Long> section = new ArrayList<>(st.designRoadsLeft.subList(0, Math.min(SECTION, st.designRoadsLeft.size())));
            final List<BlockPos> cells = new ArrayList<>();
            for (final long key : section) {
                final int hh = view.height(RoadPlanner.keyX(key), RoadPlanner.keyZ(key));
                if (hh==TerrainView.UNKNOWN) {
                    this.plugin.terrain().request(new pl.medivalrpg.ludnosc.model.ChunkRef(c.world(),RoadPlanner.keyX(key)>>4,RoadPlanner.keyZ(key)>>4));
                    return false;
                }
                cells.add(new BlockPos(c.world(), RoadPlanner.keyX(key), hh == TerrainView.UNKNOWN ? c.y() : hh, RoadPlanner.keyZ(key)));
            }
            final long cost = cells.size() * ROAD_COST_PER_BLOCK;
            if (cost > budget) {
                return false;
            }
            if (this.plugin.construction().orderRoad(country, c.world(), cells, cost, "plan").result() == BuildOrderResult.OK) {
                st.designRoadsLeft.subList(0, section.size()).clear();
                this.data().markDirty();
                return true;
            }
            return false;
        }
        }
        // 3. walls along the drawn lines / the border / around the town; the follow-* lines are recomputed twice a day
        final boolean follows = p.walls.stream().anyMatch(wl -> wl.mode != null);
        if (st.designWallsLeft != null && st.designWallsLeft.isEmpty() && follows
            && System.currentTimeMillis() - st.designWallsAt > 12 * 3_600_000L) {
            st.designWallsLeft = null;
        }
        if (st.designWallsDone == null) {
            st.designWallsDone = new HashSet<>();
        }
        if (st.designWallsLeft == null) {
            st.designWallsAt = System.currentTimeMillis();
            st.designWallsLeft = new ArrayList<>();
            st.designGates = new ArrayList<>();
            for (final DesignPlan.Wall wall : p.walls) {
                for (final int[] cell : this.wallCells(k, p, wall, view)) {
                    final long key = RoadPlanner.key(cell[0], cell[1]);
                    if (this.own(k, c.world(), cell[0], cell[1]) && !p.untouchable(cell[0], cell[1]) && !st.designWallsDone.contains(key)) {
                        st.designWallsLeft.add(key);
                    }
                }
                for (final int[] g : wall.gates) {
                    st.designGates.add(RoadPlanner.key(g[0], g[1]));
                }
            }
        }
        if (!st.designWallsLeft.isEmpty()) {
            if (this.plugin.construction().wallWorkPending(country)) {
                return false; // one stretch at a time: a queued stretch is paid already
            }
            // one continuous stretch per site (a border line has many separate pieces; cells already built are skipped)
            final List<Long> section = new ArrayList<>(st.designWallsLeft.subList(0, WallRoute.headRun(st.designWallsLeft, SECTION)));
            final List<BlockPos> cells = new ArrayList<>();
            for (final long key : section) {
                cells.add(new BlockPos(c.world(), RoadPlanner.keyX(key), c.y(), RoadPlanner.keyZ(key)));
            }
            final long cost = cells.size() * this.plugin.settings().wallsCostPerCell;
            if (cost > budget) {
                return false;
            }
            if (this.plugin.construction().orderWall(country, c.world(), cells, cost).result() == BuildOrderResult.OK) {
                st.designWallsDone.addAll(section);
                st.designWallsLeft.subList(0, section.size()).clear();
                this.data().markDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * What a player country's plan does (player countries build themselves; the plan is not an automatic builder):
     * shown to the ruler when the plan is saved, so a drawn wall is never silently ignored.
     */
    static List<String> playerPlanNotes(final boolean planWalls, final boolean walls, final boolean other) {
        final List<String> out = new ArrayList<>();
        if (walls) {
            out.add(planWalls
                ? "mury planu: robotnicy kraju budują je odcinkami, płatne ze skarbca kraju"
                : "mury planu: w kraju graczy plan nie jest budowany automatycznie — mury tylko jako szkic");
        }
        if (other) {
            out.add("budynki i drogi planu: w kraju graczy tylko szkic — zamawiaj je komendą /ludnosc buduj");
        }
        return out;
    }

    /** Store a completed reviewed layout for the panel; only a later explicit editor save resumes it. */
    public void storeCompletedRoyalPlan(final UUID country,final String json) {
        final DesignPlan p=DesignPlan.parse(json);p.options.autoFill=false;p.options.allowDemolish=false;p.options.keepExisting=true;
        this.set(country,p.toJson());final CountryState st=this.data().state(country);
        st.designExecutionPaused=true;st.designDone=p.buildingsByPriority().stream().map(b->b.type+"@"+b.x+","+b.z).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        st.designRoadsLeft=new ArrayList<>();st.designWallsLeft=new ArrayList<>();st.planActive=false;st.planForced=false;st.planDue=false;
        this.data().markDirty();
    }

    /** Cells of a wall of the plan: drawn points, the country's border, or around the town. */
    List<int[]> wallCells(final KrajInfo k, final DesignPlan p, final DesignPlan.Wall wall, final TerrainView view) {
        final String world = this.plugin.settlement().center(k).world();
        if ("follow-border".equals(wall.mode)) {
            return WallRoute.border(this.plugin.kraje().getClaims(k.id(), world), wall.inset);
        }
        if ("follow-town".equals(wall.mode)) {
            final List<BlockBox> town = new ArrayList<>();
            for (final Building b : this.data().buildingsOf(k.id())) {
                if (world.equals(b.box().world())) town.add(b.box());
            }
            return WallRoute.rings(town, this.plugin.kraje().getClaims(k.id(), world), Math.max(2, wall.inset + 4), view, (x, z) -> this.plugin.data().buildingAt(world, x,
                view.height(x, z) + 1, z) != null);
        }
        return DesignPlan.cells(wall.points, 1, wall.closed);
    }

    /** Gate cells of the plan's walls (merged with the roads when the walls are planned). */
    public Set<Long> gates(final UUID country) {
        final CountryState st = this.data().stateIfPresent(country);
        return st == null || st.designGates == null ? Set.of() : new HashSet<>(st.designGates);
    }

    /** The automatic planner may run (no plan, or the plan allows filling). */
    public boolean autoFill(final UUID country) {
        final DesignPlan p = this.plan(country);
        return p == null || p.options.autoFill;
    }

    /** No demolition at a column (heritage / untouchable zone, demolitions off). */
    public boolean noDemolition(final UUID country, final int x, final int z) {
        final DesignPlan p = this.plan(country);
        return p != null && p.noDemolition(x, z);
    }

    public boolean untouchable(final UUID country, final int x, final int z) {
        final DesignPlan p = this.plan(country);
        return p != null && p.untouchable(x, z);
    }

    /** May the automatic planner place a building of this template here (zones of the plan)? */
    public boolean allows(final UUID country, final int x, final int z, final BuildingTemplate.Meta m) {
        final DesignPlan p = this.plan(country);
        return p == null || p.allows(x, z, m.category(), m.zone(), m.id());
    }

    /** JSON summary for the panel: orders, demolitions, road and wall metres, cost, warnings. */
    public Optional<String> preview(final UUID country) {
        final DesignPlan p = this.plan(country);
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (p == null || k == null) {
            return Optional.empty();
        }
        final CountryState st = this.data().state(country);
        final String world = this.plugin.settlement().center(k).world();
        final JsonObject o = new JsonObject();
        final JsonArray orders = new JsonArray();
        long total = 0;
        for (final DesignPlan.Building b : p.buildingsByPriority()) {
            final BuildingTemplate t = this.plugin.templates().get(b.type == null ? "" : b.type);
            final JsonObject e = new JsonObject();
            e.addProperty("type", b.type);
            e.addProperty("x", b.x);
            e.addProperty("z", b.z);
            e.addProperty("rotation", b.rotation);
            e.addProperty("priority", b.priority);
            e.addProperty("cost", t == null ? 0 : t.meta().cost());
            final String key = b.type + "@" + b.x + "," + b.z;
            final var failure=st.designFailures==null?null:st.designFailures.get(key);
            final boolean ordered=st.designDone!=null && st.designDone.contains(key);
            e.addProperty("status",ordered?"zlecony":failure!=null && failure.terminal?"odrzucony":"czeka");
            if(!ordered && failure!=null) {
                e.addProperty("reason",failure.reason);
                if(!failure.terminal && failure.retryAt>0) e.addProperty("retryAt",failure.retryAt); else e.add("retryAt",com.google.gson.JsonNull.INSTANCE);
            } else {
                e.add("reason",com.google.gson.JsonNull.INSTANCE);e.add("retryAt",com.google.gson.JsonNull.INSTANCE);
            }
            orders.add(e);
            total += t == null ? 0 : t.meta().cost();
        }
        o.add("orders", orders);
        final JsonArray demolish = new JsonArray();
        final var redesign = this.plugin.redesign().compute(country);
        if (redesign != null) {
            for (final UUID id : redesign.demolish()) {
                final Building b = this.data().building(id);
                if (b != null) {
                    final JsonObject e = new JsonObject();
                    e.addProperty("id", id.toString());
                    e.addProperty("type", b.type());
                    demolish.add(e);
                }
            }
        }
        o.add("demolitions", demolish);
        final int roadMetres = p.roadCells().size();
        int wallMetres = 0;
        final TerrainView view = this.plugin.terrain().view(world, country);
        for (final DesignPlan.Wall wall : p.walls) {
            wallMetres += this.wallCells(k, p, wall, view).size();
        }
        o.addProperty("roadMetres", roadMetres);
        o.addProperty("wallMetres", wallMetres);
        total += roadMetres * ROAD_COST_PER_BLOCK + (long) wallMetres * this.plugin.settings().wallsCostPerCell;
        o.addProperty("totalCost", total);
        final JsonArray warn = new JsonArray();
        for (final String s : p.check(new HashSet<>(this.plugin.templates().ids()))) {
            warn.add(s);
        }
        for (final String s : this.worldChecks(k, p)) {
            warn.add(s);
        }
        o.add("warnings", warn);
        return Optional.of(o.toString());
    }
}
