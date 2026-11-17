// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult;
import pl.medivalrpg.ludnosc.hook.ArmiaHook;
import pl.medivalrpg.ludnosc.hook.Hooks;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.planner.BuildingChooser;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.SettlementLevels;
import pl.medivalrpg.ludnosc.planner.SiteFinder;
import pl.medivalrpg.ludnosc.planner.TerrainView;
import pl.medivalrpg.ludnosc.planner.TownPlanner;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Settlement planning in game: settlement level, the town centre and street network kept in the country's data, and
 * the {@link TownPlanner} fed with registry numbers and terrain snapshots ({@link TerrainCache}). Used by
 * {@code LudnoscApi#developSettlement}, {@code #orderBuildingAuto} and {@code /ludnosc buduj auto}.
 */
public final class SettlementService {

    /** Copper per road block of a road-works order. */
    private static final long ROAD_COST_PER_BLOCK = 2;

    private final LudnoscPlugin plugin;

    public SettlementService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    public long treasury(final UUID country) {
        final SkarbiecApi eco = this.plugin.skarbiec();
        return eco == null ? 0 : eco.balance(SkarbiecApi.countryAccount(country));
    }

    /** "Renesans, Miasto". */
    public String styleName(final int style) {
        return pl.medivalrpg.ludnosc.hook.PostepHook.eraName(pl.medivalrpg.ludnosc.template.MaterialPalette.eraOf(style)) + ", "
            + this.plugin.settings().levels.level(pl.medivalrpg.ludnosc.template.MaterialPalette.levelOf(style)).name();
    }

    // ------------------------------------------------------------------ epochs: rebuilding the town

    /** {@code EraAdvanceEvent}: the town starts rebuilding (roads, modernisation, bigger houses), step by step. */
    public void eraAdvanced(final UUID country, final int from, final int to) {
        if (this.plugin.kraje().getCountry(country).isEmpty()) {
            return;
        }
        final CountryState st = this.data().state(country);
        st.rebuildSince = to > from ? System.currentTimeMillis() : 0;
        this.data().markDirty();
        if (to > from && this.plugin.kraje().getCountry(country).map(KrajInfo::npc).orElse(false)) {
            this.plugin.redesign().due(country);
        }
        if (to > from) {
            this.plugin.notifyCountry(country, "era-rebuild",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("era", pl.medivalrpg.ludnosc.hook.PostepHook.eraName(to)));
        }
    }

    /** Resurfaces the country's roads in the style of its epoch. */
    public BuildOrderResult resurfaceRoads(final UUID country, final long budget, final String by) {
        return this.resurfaceRoads(country, budget, by, false);
    }

    /** {@code free}: an admin order the treasury does not pay; also when the roads already have this epoch's style. */
    public BuildOrderResult resurfaceRoads(final UUID country, final long budget, final String by, final boolean free) {
        final CountryState st = this.data().state(country);
        final int era = this.plugin.postep().era(country);
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null || st.roads().isEmpty() || st.roadsEra >= era && !free) {
            return BuildOrderResult.AREA_OBSTRUCTED;
        }
        final String world = st.center != null ? st.center.world() : k.capitalWorld();
        final int y = st.center != null ? st.center.y() : 64;
        final List<BlockPos> cells = new ArrayList<>();
        for (final int[] c : pl.medivalrpg.ludnosc.planner.RoadDecor.chains(st.roads())) {
            cells.add(new BlockPos(world, c[0], y, c[1]));
        }
        final long cost = free ? 0 : cells.size() * ROAD_COST_PER_BLOCK * era;
        if (cost > budget) {
            return BuildOrderResult.INSUFFICIENT_FUNDS;
        }
        final BuildOrderResult r = this.plugin.construction().orderRoad(country, world, cells, cost, by).result();
        if (r == BuildOrderResult.OK) {
            st.roadsEra = era;
            this.data().markDirty();
        }
        return r;
    }

    /** Housing that replaces {@code old} in the current epoch (more beds, allowed now), or null. */
    String replacementFor(final UUID country, final Building old, final BuildingChooser.Input in) {
        final BuildingTemplate oldT = this.plugin.templates().get(old.type());
        String best = null;
        int bestBeds = Math.max(old.beds(), oldT == null ? 0 : oldT.bedCount()) * 2 - 1;
        for (final BuildingTemplate.Meta m : this.catalogList()) {
            if (m.function() != BuildingFunction.HOUSING || m.declaredBeds() <= bestBeds || m.era() < 2
                || !this.requirementsMissing(m, in).isEmpty()) {
                continue;
            }
            best = m.id();
            bestBeds = m.declaredBeds();
        }
        return best;
    }

    /**
     * One step of rebuilding the town after an epoch advance (planner of NPC countries): roads in the new style, then
     * modernisation of old materials, then — at most one at a time, {@code rebuild.demolish-hours} apart — a small house
     * outside the old town is demolished and a bigger one built in its place. Churches, castles, palaces and the old
     * town around the square stay. True when something was ordered.
     */
    public boolean rebuildStep(final UUID country, final long budget) {
        if (!this.plugin.automaticBuildingAllowed(country)) return false;
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null || st.rebuildSince == 0) {
            return false;
        }
        final int era = this.plugin.postep().era(country);
        if (st.roadsEra < era && !st.roads().isEmpty() && this.resurfaceRoads(country, budget, "AI") == BuildOrderResult.OK) {
            return true;
        }
        if (this.plugin.repairs().autoUpgrade(country, budget)) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (now - st.lastDemolition < this.plugin.settings().rebuildDemolishHours * 3_600_000L) {
            return false;
        }
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (s.is(ConstructionSite.DEMOLISH)) {
                return false;
            }
        }
        final BuildingChooser.Input in = this.input(country, budget);
        final int freeBeds = this.data().housing(country) - this.data().population(country);
        Building victim = null;
        String then = null;
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.function() != BuildingFunction.HOUSING || b.registered() || this.plugin.templates().get(b.type()) == null
                || b.beds() > freeBeds || this.plugin.construction().repairing(b.id())) {
                continue;
            }
            if (st.center != null && Math.hypot(b.box().centerX() - st.center.x(), b.box().centerZ() - st.center.z())
                < this.plugin.settings().rebuildOldTownRadius) {
                continue; // the old town stays
            }
            if (this.plugin.design().noDemolition(country, b.box().centerX(), b.box().centerZ())) {
                continue; // the design plan keeps it
            }
            final String rep = this.replacementFor(country, b, in);
            if (rep == null || this.plugin.templates().get(rep).meta().cost() > budget) {
                continue;
            }
            if (victim == null || b.beds() < victim.beds()) {
                victim = b;
                then = rep;
            }
        }
        if (victim == null) {
            return false;
        }
        if (this.plugin.construction().orderDemolition(victim, then, "AI")) {
            st.lastDemolition = now;
            this.data().markDirty();
            return true;
        }
        return false;
    }

    /**
     * City walls of NPC countries from {@code walls.from-level} (Middle Ages and Renaissance; later epochs use posts
     * instead): the rectangle around the town is walled in modules of {@code walls.module-cells}; when the town grows
     * beyond the walls a new, wider ring is ordered (the old one stays as an inner wall). True when ordered.
     */
    public boolean orderWalls(final UUID country, final long budget) {
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null || this.levelIndex(country) < this.plugin.settings().wallsLevel || this.plugin.postep().era(country) > 2) {
            return false;
        }
        final pl.medivalrpg.ludnosc.planner.DesignPlan design = this.plugin.design().plan(country);
        if (design != null && !design.walls.isEmpty()) {
            return false; // the walls of the design plan are built by the design service
        }
        final CountryState st = this.data().state(country);
        if (this.plugin.construction().wallWorkPending(country)) {
            return false; // one stretch at a time - a queued stretch counts too (it is paid already)
        }
        final BlockPos centre = this.center(k);
        final List<BlockBox> town = new ArrayList<>();
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.box().world().equals(centre.world())) {
                town.add(b.box());
            }
        }
        if (town.size() < 6) {
            return false;
        }
        final TerrainView view = this.plugin.terrain().view(centre.world(), country);
        final java.util.function.BiPredicate<Integer, Integer> blocked = (x, z) -> {
            for (final BlockBox b : town) {
                if (x >= b.minX() - 1 && x <= b.maxX() + 1 && z >= b.minZ() - 1 && z <= b.maxZ() + 1) {
                    return true;
                }
            }
            return this.plugin.design().untouchable(country, x, z);
        };
        if (this.wallsOutdated(st, town, view)) {
            final List<int[]> ring = pl.medivalrpg.ludnosc.planner.WallRoute.rings(town,
                this.plugin.kraje().getClaims(country, centre.world()), this.plugin.settings().wallsMargin, view, blocked);
            if (ring.isEmpty()) {
                return false;
            }
            final List<Long> keys = new ArrayList<>();
            for (final int[] c : ring) {
                keys.add(RoadPlanner.key(c[0], c[1]));
            }
            if (st.wallCells != null && !this.plugin.settings().wallsKeepOld) {
                st.wallRemove = new ArrayList<>(pl.medivalrpg.ludnosc.planner.WallRoute.obsolete(st.wallCells, ring));
            }
            int[] enemy = null;
            for (final UUID e : k.enemies()) {
                final KrajInfo ek = this.plugin.kraje().getCountry(e).orElse(null);
                if (ek != null) {
                    enemy = new int[] {ek.capitalX() * 16 + 8, ek.capitalZ() * 16 + 8};
                    break;
                }
            }
            st.wallCells = keys;
            st.wallRingVersion = WALL_RING_VERSION;
            st.wallModules = new ArrayList<>(pl.medivalrpg.ludnosc.planner.WallRoute.modules(ring, this.plugin.settings().wallsModule,
                (x, z) -> !view.own(x, z), 24, enemy));
            this.data().markDirty();
        }
        if (st.wallRingVersion < WALL_RING_VERSION) {
            this.closeRingGaps(country, st, view, blocked);
        }
        // first take down the old stretches the new ring replaced, then build the modules by threat
        if (st.wallRemove != null && !st.wallRemove.isEmpty()) {
            // one continuous stretch: the obsolete cells lie in pieces all around the old ring
            final List<Long> section = new ArrayList<>(st.wallRemove.subList(0,
                pl.medivalrpg.ludnosc.planner.WallRoute.headRun(st.wallRemove, 48)));
            final List<BlockPos> cells = new ArrayList<>();
            for (final long key : section) {
                cells.add(new BlockPos(centre.world(), RoadPlanner.keyX(key), centre.y(), RoadPlanner.keyZ(key)));
            }
            if (this.plugin.construction().orderWallRemoval(country, centre.world(), cells).result() == BuildOrderResult.OK) {
                st.wallRemove.subList(0, section.size()).clear();
                this.data().markDirty();
                return true;
            }
            return false;
        }
        if (st.wallModules == null || st.wallModules.isEmpty()) {
            return false;
        }
        final int[] m = st.wallModules.get(0);
        final int from = Math.min(m[0], st.wallCells.size());
        final int to = Math.min(m[1], st.wallCells.size());
        if (to <= from) {
            st.wallModules.remove(0);
            this.data().markDirty();
            return false;
        }
        // modules of older rings may span a break (river, island): only the continuous head is one site
        final int end = from + pl.medivalrpg.ludnosc.planner.WallRoute.headRun(st.wallCells.subList(from, to), to - from);
        final long cost = (long) (end - from) * this.plugin.settings().wallsCostPerCell;
        if (cost > budget) {
            return false; // in peace and with little money the wall waits (incomplete)
        }
        final List<BlockPos> cells = new ArrayList<>();
        for (int i = from; i < end; i++) {
            final long key = st.wallCells.get(i);
            cells.add(new BlockPos(centre.world(), RoadPlanner.keyX(key), centre.y(), RoadPlanner.keyZ(key)));
        }
        if (this.plugin.construction().orderWall(country, centre.world(), cells, cost).result() == BuildOrderResult.OK) {
            if (end >= to) {
                st.wallModules.remove(0);
            } else {
                st.wallModules.set(0, new int[] {end, to});
            }
            this.data().markDirty();
            return true;
        }
        return false;
    }

    /** Ring planning version stored with {@link CountryState#wallRingVersion}. */
    static final int WALL_RING_VERSION = 1;

    /**
     * A ring planned by an older version: its small holes (one-block gaps on diagonal stretches) are closed in place.
     * The joining cells go into the ring; inside a module still to build they are built with it, next to stretches
     * already built they become small modules of their own (first in line: they are holes in a standing wall).
     */
    private void closeRingGaps(final UUID country, final CountryState st, final TerrainView view,
                               final java.util.function.BiPredicate<Integer, Integer> blocked) {
        st.wallRingVersion = WALL_RING_VERSION;
        this.data().markDirty();
        if (st.wallCells == null || st.wallCells.size() < 2) {
            return;
        }
        final List<Long> old = st.wallCells;
        final int n = old.size();
        final boolean[] pending = new boolean[n];
        if (st.wallModules != null) {
            for (final int[] m : st.wallModules) {
                for (int i = Math.max(0, m[0]); i < Math.min(n, m[1]); i++) {
                    pending[i] = true;
                }
            }
        }
        final pl.medivalrpg.ludnosc.planner.WallRoute.Spliced spliced =
            pl.medivalrpg.ludnosc.planner.WallRoute.spliceGaps(old, pending, view, blocked);
        if (spliced.cells().size() == n) {
            return;
        }
        st.wallCells = spliced.cells();
        st.wallModules = pl.medivalrpg.ludnosc.planner.WallRoute.remapModules(st.wallModules, spliced, n);
        this.plugin.getLogger().info("Mury " + this.plugin.countryName(country) + ": domknięto " + (spliced.cells().size() - n)
            + " bl. dziur w pierścieniu (" + spliced.holes().size() + " małych odcinków przy gotowym murze).");
    }

    /** A new ring is needed: none yet, the town grew beyond it, or part of it lies on land the country lost. */
    private boolean wallsOutdated(final CountryState st, final List<BlockBox> town, final TerrainView view) {
        if (st.wallCells == null || st.wallCells.isEmpty()) {
            return true;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int lost = 0;
        for (final long key : st.wallCells) {
            final int x = RoadPlanner.keyX(key);
            final int z = RoadPlanner.keyZ(key);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
            if (!view.own(x, z)) {
                lost++;
            }
        }
        if (lost > 0) {
            return true;
        }
        for (final BlockBox b : town) {
            if (b.minX() < minX || b.maxX() > maxX || b.minZ() < minZ || b.maxZ() > maxZ) {
                return true; // a new district outside the walls
            }
        }
        return false;
    }

    /** After a demolition: the new building at the same place, or where the planner finds room. */
    public void buildInPlace(final UUID country, final String type, final World w, final int x, final int y, final int z, final int q) {
        final var out = this.plugin.construction().orderDetailed(country, type, w, x, y, z, q, false, "AI", null);
        if (out.result() != BuildOrderResult.OK) {
            this.orderAuto(country, type, "AI");
        }
    }

    /** Material style of a country: epoch (Postep) × settlement level ({@link MaterialPalette#style}). */
    public int style(final UUID country) {
        return pl.medivalrpg.ludnosc.template.MaterialPalette.style(this.plugin.postep().era(country), this.levelIndex(country));
    }

    public int levelIndex(final UUID country) {
        return this.plugin.settings().levels.index(this.data().population(country), this.treasury(country));
    }

    public SettlementLevels.Level level(final UUID country) {
        return this.plugin.settings().levels.level(this.levelIndex(country));
    }

    /** Town centre: the planner's square, else the centre of the capital chunk. */
    public BlockPos center(final KrajInfo k) {
        final BlockPos override = this.centerOverride.get(k.id());
        if (override != null) {
            return override;
        }
        final CountryState st = this.data().stateIfPresent(k.id());
        if (st != null && st.center != null) {
            return st.center;
        }
        final int x = k.capitalX() * 16 + 8;
        final int z = k.capitalZ() * 16 + 8;
        final World w = Bukkit.getWorld(k.capitalWorld());
        final int y = w != null && w.isChunkLoaded(k.capitalX(), k.capitalZ()) ? w.getHighestBlockYAt(x, z) : 64;
        return new BlockPos(k.capitalWorld(), x, y, z);
    }

    /** Finished + under construction + queued, per template id. */
    public Map<String, Integer> counts(final UUID country) {
        final Map<String, Integer> out = new HashMap<>();
        for (final Building b : this.data().buildingsOf(country)) {
            // a building registered by the players counts as its kind (template limits), working or not
            out.merge(this.plugin.playerBuildings() == null ? b.type() : this.plugin.playerBuildings().kind(b), 1, Integer::sum);
        }
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (s.is(ConstructionSite.BUILD) && s.repairOf == null) {
                out.merge(s.type, 1, Integer::sum);
            }
        }
        for (final QueuedOrder o : this.data().queue(country)) {
            if (ConstructionSite.BUILD.equals(o.kind()) && o.repairOf == null) {
                out.merge(o.type, 1, Integer::sum);
            }
        }
        return out;
    }

    public List<BuildingTemplate.Meta> catalogList() {
        final List<BuildingTemplate.Meta> out = new ArrayList<>();
        for (final BuildingTemplate t : this.plugin.templates().all()) {
            out.add(t.meta());
        }
        return out;
    }

    private TownPlanner.Catalog catalog(final UUID country) {
        final Map<String, BuildingTemplate.Meta> metas = new HashMap<>();
        final Map<String, int[]> sizes = new HashMap<>();
        for (final BuildingTemplate t : this.plugin.templates().all()) {
            if(!pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(country,t.id()))continue;
            metas.put(t.id(), t.meta());
            sizes.put(t.id(), new int[] {t.width(), t.depth()});
        }
        return new TownPlanner.Catalog(metas, sizes);
    }

    public BuildingChooser.Input input(final UUID country, final long budget) {
        final boolean npc = this.plugin.kraje().getCountry(country).map(KrajInfo::npc).orElse(false);
        final boolean war = this.plugin.kraje().getCountry(country).map(k -> !k.enemies().isEmpty()).orElse(false);
        int barracksBeds = 0;
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.function() == BuildingFunction.BARRACKS) {
                barracksBeds += b.beds();
            }
        }
        int soldiers = 0;
        if (Hooks.armia()) {
            soldiers = ArmiaHook.soldierCount(country);
        }
        final CountryState st = this.data().stateIfPresent(country);
        return new BuildingChooser.Input(this.levelIndex(country), this.data().population(country), this.data().housing(country),
            st == null ? 0 : st.food, this.plugin.granary().capacity(country), this.plugin.economy().expectedFoodBalance(country),
            soldiers, barracksBeds, war, this.treasury(country), budget, this.counts(country), this.catalogList(),
            this.plugin.settings().housingFreeRatio, this.plugin.settings().granaryFullRatio, this.plugin.postep().era(country),
            d -> this.plugin.postep().hasDiscovery(country, d), country.getMostSignificantBits() ^ country.getLeastSignificantBits());
    }

    /** Footprints of everything already planned in a world (buildings, sites, queue). */
    List<BlockBox> taken(final String world) {
        final List<BlockBox> out = new ArrayList<>();
        for (final Building b : this.data().allBuildings()) {
            if (b.box().world().equals(world)) {
                out.add(b.box());
            }
        }
        out.addAll(this.plugin.construction().takenNear(world, 0, 0, Integer.MAX_VALUE / 4, Integer.MAX_VALUE));
        return out;
    }

    SiteFinder.Settings settings() {
        return new SiteFinder.Settings(this.plugin.settings().plannerRadius, 3, Math.max(this.plugin.settings().plannerSpacing, this.plugin.getConfig().getInt("courtesy.building-spacing", 8)),
            this.plugin.settings().plannerMaxSlope);
    }

    /** Plans the next building (or a given type) without ordering anything. */
    /** Planning turn of another settlement of the kingdom: its centre replaces the capital's for that turn. */
    private final Map<UUID, BlockPos> centerOverride = new java.util.concurrent.ConcurrentHashMap<>();

    /** The terrain as the planner may use it for a type: own land only where the design plan's zones allow it. */
    TerrainView zoned(final TerrainView base, final UUID country, final BuildingTemplate.Meta m) {
        if (this.plugin.design().plan(country) == null) {
            return base;
        }
        return new TerrainView() {
            @Override
            public int height(final int x, final int z) {
                return base.height(x, z);
            }

            @Override
            public boolean liquid(final int x, final int z) {
                return base.liquid(x, z);
            }

            @Override
            public boolean obstacle(final int x, final int z) {
                return base.obstacle(x, z);
            }

            @Override
            public boolean own(final int x, final int z) {
                return base.own(x, z) && SettlementService.this.plugin.design().allows(country, x, z, m);
            }

            @Override
            public boolean stone(final int x, final int z) {
                return base.stone(x, z);
            }

            @Override
            public boolean tree(final int x, final int z) {
                return base.tree(x, z);
            }
        };
    }

    /** Last "no place" reason per country (for players, the panel and the log). */
    private final Map<UUID, String> failures = new java.util.concurrent.ConcurrentHashMap<>();

    /** Why the last planning / order of the country found no place (Polish), or null. */
    public String lastFailure(final UUID country) {
        return country == null ? null : this.failures.get(country);
    }

    public void setFailure(final UUID country, final String reason) {
        if (country == null) {
            return;
        }
        if (reason == null) {
            this.failures.remove(country);
            return;
        }
        final String old = this.failures.put(country, reason);
        if (!reason.equals(old)) {
            this.plugin.getLogger().info("Brak miejsca dla " + this.plugin.kraje().getCountry(country).map(KrajInfo::name).orElse("?")
                + ": " + reason);
        }
    }

    /**
     * Plans the next building (or a given type); when nothing fits and the country may grow (NPC countries,
     * players with {@code planner.auto-claim-for-players}), claims adjacent chunks with good terrain first and plans
     * again. Records the reason of a failure ({@link #lastFailure}).
     */
    public TownPlanner.Result plan(final UUID country, final long budget, final String forcedType) {
        // types that found no place recently are skipped for a while (the planner moves on to other buildings)
        final Set<String> skip = forcedType == null ? new java.util.HashSet<>(this.backedOff(country)) : Set.of();
        TownPlanner.Result r = this.planOnce(country, budget, forcedType, skip);
        String claimed = null;
        for (int attempt = 0; attempt < 3 && r.proposal() == null && r.noSpot(); attempt++) {
            if (r.failure() != null && r.failure().contains("nie jest jeszcze zbadany")) {
                break; // the terrain is still being scanned: no back-off, no claims
            }
            if (attempt == 0 && !this.waterType(r.type())) {
                claimed = this.claimForBuilding(country, r);
                if (claimed != null && claimed.isEmpty()) {
                    r = this.planOnce(country, budget, forcedType, skip);
                    if (r.proposal() != null || !r.noSpot()) {
                        break;
                    }
                }
            }
            if (forcedType != null || r.type() == null) {
                break;
            }
            this.backOff(country, r.type(), r.failure());
            skip.add(r.type());
            final TownPlanner.Result next = this.planOnce(country, budget, null, skip);
            if (next.proposal() == null && !next.noSpot()) {
                break; // nothing else is needed now: keep the reason of the failed type
            }
            r = next;
        }
        if (r.proposal() == null && r.noSpot()) {
            final String reason = (r.failure() == null ? "brak miejsca" : r.failure())
                + (claimed == null || claimed.isEmpty() ? "" : " (" + claimed + ")");
            this.setFailure(country, reason);
            return new TownPlanner.Result(null, r.reason(), true, reason, r.type());
        }
        // a proposal is not a success yet: the back-off and the reason are cleared only by an accepted order
        return r;
    }

    // ------------------------------------------------------------------ plots refused before (persistent)

    /** How long a refused / obstructed plot stays out of the planner's proposals. */
    static final long AVOID_MILLIS = 3L * 86_400_000L;
    static final int AVOID_MAX = 64;

    /** The planner will not propose this plot (any type overlapping it) for {@link #AVOID_MILLIS}; kept in data.json. */
    public void avoid(final UUID country, final String type, final BlockBox box, final String reason) {
        if (country == null || box == null) {
            return;
        }
        final CountryState st = this.data().state(country);
        final long now = System.currentTimeMillis();
        st.plannerAvoid = new ArrayList<>(pl.medivalrpg.ludnosc.logic.BuildFixes.remember(st.plannerAvoid,
            new pl.medivalrpg.ludnosc.model.AvoidSpot(box.world(), type, box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                now + AVOID_MILLIS, reason), now, AVOID_MAX));
        this.data().markDirty();
        this.plugin.getLogger().info("Planista " + this.plugin.kraje().getCountry(country).map(KrajInfo::name).orElse("?") + ": działka "
            + type + " " + box + " pominięta na 3 dni (" + (reason == null ? "odmowa" : reason) + ")");
    }

    /** Refused plots of the country in a world, as footprints the planner treats as taken. */
    List<BlockBox> avoided(final UUID country, final String world) {
        final CountryState st = this.data().stateIfPresent(country);
        final List<BlockBox> out = new ArrayList<>();
        if (st != null) {
            for (final var a : pl.medivalrpg.ludnosc.logic.BuildFixes.active(st.plannerAvoid, world, System.currentTimeMillis())) {
                out.add(new BlockBox(a.world(), a.minX(), Integer.MIN_VALUE / 2, a.minZ(), a.maxX(), Integer.MAX_VALUE / 2, a.maxZ()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ back-off of types without a place

    /** Country -> template id -> {until (epoch millis), failures}. In memory: after a restart every type is tried again. */
    private final Map<UUID, Map<String, long[]>> backoff = new java.util.concurrent.ConcurrentHashMap<>();

    /** Template ids the planner skips now. */
    public Set<String> backedOff(final UUID country) {
        final Map<String, long[]> m = country == null ? null : this.backoff.get(country);
        if (m == null || m.isEmpty()) {
            return Set.of();
        }
        final long now = System.currentTimeMillis();
        final Set<String> out = new java.util.HashSet<>();
        for (final Map.Entry<String, long[]> e : m.entrySet()) {
            if (e.getValue()[0] > now) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** A type found no place: skipped for a while, longer with every further failure ({@link pl.medivalrpg.ludnosc.logic.Backoff}). */
    public void backOff(final UUID country, final String type, final String reason) {
        if (country == null || type == null) {
            return;
        }
        final long[] v = this.backoff.computeIfAbsent(country, k -> new java.util.concurrent.ConcurrentHashMap<>())
            .computeIfAbsent(type, k -> new long[2]);
        v[1]++;
        final long wait = pl.medivalrpg.ludnosc.logic.Backoff.delay((int) v[1]);
        v[0] = System.currentTimeMillis() + wait;
        if (v[1] == 1 || v[1] % 5 == 0) {
            this.plugin.getLogger().info("Planista " + this.plugin.kraje().getCountry(country).map(KrajInfo::name).orElse("?") + ": "
                + type + " odłożony na " + wait / 60_000 + " min (" + (reason == null ? "brak miejsca" : reason) + ") — buduje inne budynki.");
        }
    }

    public void clearBackoff(final UUID country, final String type) {
        final Map<String, long[]> m = country == null ? null : this.backoff.get(country);
        if (m != null && type != null) {
            m.remove(type);
        }
    }

    /** Harbours and bridges: only where the country has water (claiming dry land would not help). */
    private boolean waterType(final String type) {
        final BuildingTemplate t = type == null ? null : this.plugin.templates().get(type);
        return t != null && "water".equals(t.meta().zone());
    }

    /** The country has open water on its land near the centre (a bridge / harbour has something to stand on). */
    private boolean waterOnLand(final TerrainView view, final int cx, final int cz, final int radius) {
        for (int dx = -radius; dx <= radius; dx += 3) {
            for (int dz = -radius; dz <= radius; dz += 3) {
                if (view.own(cx + dx, cz + dz) && view.liquid(cx + dx, cz + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Claims up to {@code planner.auto-claim-max} chunks next to the country's land, closest to the centre and with the
     * flattest, driest terrain. Returns "" when something was claimed, a Polish reason when not allowed / failed, or
     * null when claiming is off.
     */
    String claimForBuilding(final UUID country, final TownPlanner.Result r) {
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null || !(k.npc() || this.plugin.settings().autoClaimForPlayers) || this.plugin.settings().autoClaimMax <= 0) {
            return null;
        }
        if (r.failure() != null && r.failure().contains("nie jest jeszcze zbadany")) {
            return null; // the terrain is still being scanned: more land would not help yet
        }
        final BlockPos c = this.center(k);
        final TerrainView view = this.plugin.terrain().view(c.world(), country);
        final Set<Long> own = new java.util.HashSet<>(this.plugin.kraje().getClaims(country, c.world()));
        final int radius = this.plugin.settings().plannerRadius / 16 + 2;
        // scan the free chunks along the border (for a terrain-aware choice next time)
        int asked = 0;
        for (final long key : own) {
            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int nx = (int) (key >> 32) + d[0];
                final int nz = (int) key + d[1];
                if (asked < 24 && !own.contains(pl.medivalrpg.ludnosc.planner.LandPicker.key(nx, nz))) {
                    this.plugin.terrain().request(new pl.medivalrpg.ludnosc.model.ChunkRef(c.world(), nx, nz));
                    asked++;
                }
            }
        }
        String failure = null;
        int claimed = 0;
        for (int i = 0; i < this.plugin.settings().autoClaimMax; i++) {
            final long best = pl.medivalrpg.ludnosc.planner.LandPicker.pick(own, c.x() >> 4, c.z() >> 4, radius,
                (cx, cz) -> this.plugin.kraje().getCountryAt(c.world(), cx, cz).isEmpty(), view);
            if (best == Long.MIN_VALUE) {
                failure = "brak wolnych chunków przy granicy kraju";
                break;
            }
            final int cx = (int) (best >> 32);
            final int cz = (int) best;
            final var res = this.claimChunk(k, c.world(), cx, cz);
            if (!res.ok()) {
                failure = switch (res.status()) {
                    case LIMIT_REACHED -> "limit terenu Kraje osiągnięty — kraj nie może zająć więcej chunków";
                    case INSUFFICIENT_FUNDS -> "brak pieniędzy w skarbcu na zajęcie chunka";
                    default -> "nie udało się zająć chunka (" + res.status().name().toLowerCase(java.util.Locale.ROOT) + ")";
                };
                break;
            }
            own.add(best);
            claimed++;
            this.plugin.terrain().request(new pl.medivalrpg.ludnosc.model.ChunkRef(c.world(), cx, cz));
            this.plugin.getLogger().info("Planista: " + k.name() + " zajmuje chunk " + cx + " " + cz + " pod budowę.");
        }
        if (claimed > 0) {
            return "";
        }
        return failure;
    }

    /**
     * Claim of the planner: NPC countries through {@code KrajeApi#claimChunkForPlanner} (Kraje may allow more land
     * than the normal limit for building), player countries and older Kraje through the normal NPC claim.
     */
    private pl.medivalrpg.kraje.api.KrajeResult claimChunk(final KrajInfo k, final String world, final int cx, final int cz) {
        if (k.npc()) {
            try {
                return this.plugin.kraje().claimChunkForPlanner(k.id(), world, cx, cz);
            } catch (final NoSuchMethodError | AbstractMethodError e) {
                // Kraje older than API 1.4
            }
        }
        return this.plugin.kraje().claimChunk(k.id(), world, cx, cz, pl.medivalrpg.kraje.api.ClaimCause.NPC);
    }

    private TownPlanner.Result planOnce(final UUID country, final long budget, final String forcedType, final Set<String> skip) {
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null) {
            return new TownPlanner.Result(null, BuildingChooser.Reason.NOTHING_NEEDED, false);
        }
        final BlockPos c = this.center(k);
        this.plugin.terrain().requestAround(country, c.world(), c.x() >> 4, c.z() >> 4, this.plugin.settings().plannerRadius / 16 + 1);
        final TerrainView view = this.plugin.terrain().view(c.world(), country);
        final TerrainView land = this.planStreetsFree(view, country); // building land; the roads may use the planned streets
        final Set<Long> roads = new LinkedHashSet<>(this.data().state(country).roads());
        final List<BlockBox> taken = new ArrayList<>(this.taken(c.world()));
        taken.addAll(this.avoided(country, c.world()));
        if (forcedType == null) {
            return TownPlanner.plan(this.input(country, budget).without(skip), this.catalog(country), view, c.world(), c.x(), c.z(), roads,
                taken, this.settings(), 6000, m -> this.zoned(land, country, m));
        }
        final BuildingTemplate t = this.plugin.templates().get(forcedType);
        if (t == null||!pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(country,forcedType)) {
            return new TownPlanner.Result(null, BuildingChooser.Reason.NOTHING_NEEDED, false);
        }
        if ("water".equals(t.meta().zone()) && !this.waterOnLand(view, c.x(), c.z(), this.plugin.settings().plannerRadius)) {
            return new TownPlanner.Result(null, BuildingChooser.Reason.GROWTH, true, t.meta().displayName().replaceAll("<[^>]*>", "")
                + ": brak wody na ziemi kraju (most i port stoją tylko nad wodą)", t.id());
        }
        final SiteFinder.Search search = SiteFinder.search(this.zoned(land, country, t.meta()), new int[] {t.width(), t.depth()}, c.x(), c.z(),
            t.meta().zone(), taken, roads, this.settings(), t.meta().groundLayer(), "highrise".equals(t.meta().category()));
        final SiteFinder.Spot spot = search.spot();
        if (spot == null) {
            return new TownPlanner.Result(null, BuildingChooser.Reason.GROWTH, true, t.meta().displayName().replaceAll("<[^>]*>", "")
                + ": " + search.stats().reason(t.width(), t.depth()), t.id());
        }
        final BlockBox box = TownPlanner.footprint(c.world(), new int[] {t.width(), t.depth()}, spot);
        final List<BlockBox> obstacles = new ArrayList<>(taken);
        obstacles.add(box);
        final List<int[]> road = RoadPlanner.path(view, spot.doorX(), spot.doorZ(), roads, c.x(), c.z(), obstacles, 6000);
        return new TownPlanner.Result(new TownPlanner.Proposal(t.id(), BuildingChooser.Reason.GROWTH, spot, road, box),
            BuildingChooser.Reason.GROWTH, false);
    }

    /**
     * While a town redesign is active its future streets are not building land (a house there would be demolished
     * again by the same redesign).
     */
    private TerrainView planStreetsFree(final TerrainView base, final UUID country) {
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null || !st.planActive || st.planRoads == null || st.planRoads.isEmpty()) {
            return base;
        }
        final Set<Long> streets = new java.util.HashSet<>(st.planRoads);
        return new TerrainView() {
            @Override
            public int height(final int x, final int z) {
                return base.height(x, z);
            }

            @Override
            public boolean liquid(final int x, final int z) {
                return base.liquid(x, z);
            }

            @Override
            public boolean obstacle(final int x, final int z) {
                return base.obstacle(x, z);
            }

            @Override
            public boolean own(final int x, final int z) {
                return base.own(x, z) && !streets.contains(RoadPlanner.key(x, z));
            }

            @Override
            public boolean stone(final int x, final int z) {
                return base.stone(x, z);
            }

            @Override
            public boolean tree(final int x, final int z) {
                return base.tree(x, z);
            }
        };
    }

    /** Road cells with surface heights (from the terrain cache when known). */
    List<BlockPos> roadCells(final String world, final UUID country, final List<int[]> path, final int fallbackY) {
        final TerrainView view = this.plugin.terrain().view(world, country);
        final List<BlockPos> out = new ArrayList<>();
        for (final int[] p : path) {
            final int h = view.height(p[0], p[1]);
            out.add(new BlockPos(world, p[0], h == TerrainView.UNKNOWN ? fallbackY : h, p[1]));
        }
        return out;
    }

    /** {@code LudnoscApi#developSettlement}: the planner decides what and where, within {@code budget}. */
    public BuildOrderResult develop(final UUID country, final long budget) {
        if (!this.plugin.automaticBuildingAllowed(country)) return BuildOrderResult.AREA_OBSTRUCTED;
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(country))return BuildOrderResult.AREA_OBSTRUCTED;
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (k == null) {
            return BuildOrderResult.NOT_ON_OWN_LAND;
        }
        final World w = Bukkit.getWorld(k.capitalWorld());
        if (w == null) {
            return BuildOrderResult.AREA_OBSTRUCTED;
        }
        // main streets from the square once the well stands
        final CountryState st = this.data().state(country);
        if (st.roads().isEmpty() && st.center != null && this.counts(country).getOrDefault("studnia", 0) > 0 && !this.roadWorksPending(country)) {
            final TerrainView view = this.plugin.terrain().view(k.capitalWorld(), country);
            final List<BlockPos> cells = new ArrayList<>();
            for (final List<int[]> axis : TownPlanner.mainAxes(view, st.center.x(), st.center.z(), 24, this.taken(k.capitalWorld()), 6000)) {
                cells.addAll(this.roadCells(k.capitalWorld(), country, axis, st.center.y()));
            }
            if (!cells.isEmpty() && cells.size() * ROAD_COST_PER_BLOCK <= budget) {
                return this.plugin.construction().orderRoad(country, k.capitalWorld(), cells, cells.size() * ROAD_COST_PER_BLOCK, "AI").result();
            }
        }
        // the town plan v2: recomputed when due, worked off step by step (every third planning turn)
        this.plugin.redesign().maybeReplan(country);
        final CountryState rs = this.data().state(country);
        if (rs.planActive && rs.rebuildTurn++ % 3 == 1 && this.plugin.redesign().step(country, budget)) {
            return BuildOrderResult.OK;
        }
        // a new epoch: every other planning turn goes to rebuilding the town
        if (rs.rebuildSince > 0 && (rs.rebuildTurn++ & 1) == 0 && this.rebuildStep(country, budget)) {
            return BuildOrderResult.OK;
        }
        // the design plan drawn in the panel has priority; without autoFill nothing else is planned
        if (this.plugin.design().step(country, budget)) {
            return BuildOrderResult.OK;
        }
        if (!this.plugin.design().autoFill(country)) {
            this.setFailure(country, "plan wytyczny: wszystko z planu zlecone albo czeka (autoFill wyłączony)");
            return BuildOrderResult.AREA_OBSTRUCTED;
        }
        // war: fortify the threatened border first; the kingdom: high roads, new settlements, other settlements' turns
        if (this.plugin.kingdom().fortify(country, budget)) {
            return BuildOrderResult.OK;
        }
        if ((rs.rebuildTurn & 3) == 2 && this.plugin.kingdom().step(country, budget)) {
            return BuildOrderResult.OK;
        }
        final BlockPos turnCentre = this.plugin.kingdom().planningTurn(country);
        TownPlanner.Result r;
        if (turnCentre != null) {
            this.centerOverride.put(country, turnCentre);
            try {
                r = this.plan(country, budget, null);
                if (r.proposal() != null) {
                    return this.orderProposal(country, w, r, "AI");
                }
            } finally {
                this.centerOverride.remove(country);
            }
        }
        r = this.plan(country, budget, null);
        if (r.proposal() == null) {
            if (this.plugin.kingdom().maybeFound(country, budget)) {
                return BuildOrderResult.OK;
            }
            if (this.rebuildStep(country, budget)) {
                return BuildOrderResult.OK;
            }
            // nothing new to build: modernise old buildings, then extend the mines
            if (this.plugin.repairs().autoUpgrade(country, budget)) {
                return BuildOrderResult.OK;
            }
            if (this.orderWalls(country, budget)) {
                return BuildOrderResult.OK;
            }
            if (this.plugin.underground().autoOrder(country, budget)) {
                return BuildOrderResult.OK;
            }
            if (this.plugin.settings().mineExtensionCost <= budget && this.plugin.mines().extendable(country)
                && this.plugin.mines().extend(country, null, true) == MineService.Extension.ORDERED) {
                return BuildOrderResult.OK;
            }
        }
        return this.orderProposal(country, w, r, "AI");
    }

    private boolean roadWorksPending(final UUID country) {
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (s.is(ConstructionSite.ROAD)) {
                return true;
            }
        }
        for (final QueuedOrder o : this.data().queue(country)) {
            if (ConstructionSite.ROAD.equals(o.kind())) {
                return true;
            }
        }
        return false;
    }

    /** {@code LudnoscApi#orderBuildingAuto}: a given type, the planner picks the spot. */
    public BuildOrderResult orderAuto(final UUID country, final String type, final String orderedBy) {
        if (!this.plugin.automaticBuildingAllowed(country)) return BuildOrderResult.AREA_OBSTRUCTED;
        final BuildingTemplate t = this.plugin.templates().get(type);
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        if (t == null || k == null) {
            return BuildOrderResult.UNKNOWN_BUILDING;
        }
        final World w = Bukkit.getWorld(k.capitalWorld());
        if (w == null) {
            return BuildOrderResult.AREA_OBSTRUCTED;
        }
        final BuildingChooser.Input in = this.input(country, Long.MAX_VALUE);
        if (!this.requirementsMissing(t.meta(), in).isEmpty()) {
            return t.meta().minTreasury() > in.treasury() || t.meta().cost() > in.treasury()
                ? BuildOrderResult.INSUFFICIENT_FUNDS : BuildOrderResult.UNKNOWN_BUILDING;
        }
        return this.orderProposal(country, w, this.plan(country, Long.MAX_VALUE, t.id()), orderedBy);
    }

    BuildOrderResult orderProposal(final UUID country, final World w, final TownPlanner.Result r, final String orderedBy) {
        if (r.proposal() == null) {
            if (r.noSpot()) {
                return BuildOrderResult.AREA_OBSTRUCTED;
            }
            return BuildOrderResult.INSUFFICIENT_FUNDS;
        }
        final TownPlanner.Proposal p = r.proposal();
        final List<BlockPos> road = this.roadCells(w.getName(), country, p.road(), p.spot().y());
        final ConstructionService.OrderOutcome out = this.plugin.construction().orderDetailed(country, p.type(), w, p.spot().x(),
            p.spot().y(), p.spot().z(), p.spot().rotation(), false, orderedBy, road);
        if (out.result() != BuildOrderResult.OK) {
            final String why = this.plugin.construction().lastCheckReason(country);
            this.setFailure(country, why);
            if (out.result() == BuildOrderResult.AREA_OBSTRUCTED || out.result() == BuildOrderResult.NOT_ON_OWN_LAND) {
                // the same plot is not proposed again and again: remembered (also over restarts) and the type backs off
                this.avoid(country, p.type(), this.plugin.construction().footprint(p.type(), w.getName(), p.spot().x(), p.spot().y(),
                    p.spot().z(), p.spot().rotation()), why);
                this.backOff(country, p.type(), why);
            }
        } else {
            this.setFailure(country, null);
            this.clearBackoff(country, p.type());
        }
        if (out.result() == BuildOrderResult.OK && "infrastructure".equals(this.plugin.templates().get(p.type()).meta().category())) {
            final CountryState st = this.data().state(country);
            if (st.center == null) {
                st.center = new BlockPos(w.getName(), p.spot().x(), p.spot().y(), p.spot().z());
                this.data().markDirty();
            }
        }
        return out.result();
    }

    /** Unmet requirements of a template for a country (Polish), empty = available. */
    public List<String> requirementsMissing(final BuildingTemplate.Meta m, final BuildingChooser.Input in) {
        return this.requirementsMissing(m, in, true);
    }

    /** {@code costAndLimit = false}: without the price and the limit per country (registered player buildings). */
    public List<String> requirementsMissing(final BuildingTemplate.Meta m, final BuildingChooser.Input in, final boolean costAndLimit) {
        final List<String> out = new ArrayList<>();
        final SettlementLevels levels = this.plugin.settings().levels;
        if (m.era() > in.era()) {
            out.add("epoka: " + pl.medivalrpg.ludnosc.hook.PostepHook.eraName(m.era()));
        }
        if (!m.discovery().isEmpty() && !in.discovered().test(m.discovery())) {
            out.add("odkrycie: " + pl.medivalrpg.ludnosc.hook.PostepHook.discoveryName(m.discovery()));
        }
        if (m.tier() > in.level()) {
            out.add("poziom: " + levels.level(m.tier()).name());
        }
        if (in.population() < m.minPopulation()) {
            out.add("ludność ≥ " + m.minPopulation());
        }
        if (in.treasury() < m.minTreasury()) {
            out.add("skarbiec ≥ " + this.plugin.formatMoney(m.minTreasury()));
        }
        if (costAndLimit && m.maxPerCountry() > 0 && in.count(m.id()) >= m.maxPerCountry()) {
            out.add("limit " + m.maxPerCountry() + " na kraj");
        }
        if (costAndLimit && in.treasury() < m.cost()) {
            out.add("koszt " + this.plugin.formatMoney(m.cost()));
        }
        return out;
    }

    /** Settlement level 1..5 and name. */
    public Optional<int[]> tier(final UUID country) {
        return this.plugin.kraje().getCountry(country).map(k -> new int[] {this.levelIndex(country) + 1});
    }
}
