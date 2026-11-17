// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult;
import pl.medivalrpg.ludnosc.config.LudnoscConfig;
import pl.medivalrpg.ludnosc.logic.MaterialNeeds;
import pl.medivalrpg.ludnosc.logic.QueueRules;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.planner.Drainage;
import pl.medivalrpg.ludnosc.planner.Entrance;
import pl.medivalrpg.ludnosc.planner.FluidGuard;
import pl.medivalrpg.ludnosc.planner.RepairRules;
import pl.medivalrpg.ludnosc.planner.RoadDecor;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.Terraform;
import pl.medivalrpg.ludnosc.planner.TerrainFootprint;
import pl.medivalrpg.ludnosc.planner.TerrainView;
import pl.medivalrpg.ludnosc.planner.TicketAllocator;
import pl.medivalrpg.ludnosc.planner.WallPlanner;
import pl.medivalrpg.ludnosc.planner.WallRoute;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildPhases;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.MaterialPalette;
import pl.medivalrpg.ludnosc.template.Placement;
import pl.medivalrpg.ludnosc.template.TemplateLibrary;
import pl.medivalrpg.ludnosc.util.Keys;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Construction sites: orders (land, overlap, terrain, treasury, queue), realistic gradual building by walking builder
 * NPCs, completion into registered buildings, repairs, road works and the clean-up after cancelled builds.
 *
 * <p>Phases of a building site: terrain levelling (plot + slopes / retaining walls) → foundation → template cells in
 * the realistic order of {@link BuildPhases} (with temporary scaffolding at high walls) → entrance stairs → road to
 * the door → finish (scaffolding removed, neighbour connections recomputed, greenery). Builders only work in the MC
 * daytime (config); at night they rest at a campfire. Active sites keep their chunks loaded with plugin chunk tickets
 * within a server-wide limit; other sites progress virtually and catch up when their chunks load.</p>
 */
public final class ConstructionService {

    /** Service tick period in game ticks. */
    public static final int PERIOD = 10;
    /** Version of the cursor semantics; older sites restart their cursor (placed blocks are skipped quickly). */
    static final int ORDER_VERSION = 2;
    private static final int MAX_CHECKS_PER_TICK = 4096;
    private static final long MATERIAL_WARN_MILLIS = 5 * 60_000L;
    private static final long NEEDS_MILLIS = 20_000L;
    /** Service ticks a builder may walk towards one block before he hops there (12 s). */
    static final int STUCK_HOP = 24;

    /** Phases of a site. */
    enum Phase { TERRAIN, FOUNDATION, CELLS, ENTRANCE, ROAD, FINISH, RESTORE_CELLS, RESTORE_TERRAIN }

    private final LudnoscPlugin plugin;
    private final long policyStartedAt = System.currentTimeMillis();
    private final Map<UUID, SiteRuntime> runtimes = new HashMap<>();
    private final Set<ChunkRef> tickets = new HashSet<>();
    /** Chunks requested asynchronously for a site ticket (the ticket is added when the chunk has arrived). */
    private final Set<ChunkRef> ticketLoads = new HashSet<>();
    private Set<ChunkRef> ticketsWanted = Set.of();
    /** Duration of the last / longest ticket pass (ms) and chunks loaded for tickets so far (diagnostics). */
    private double ticketMs;
    private double ticketMaxMs;
    private long ticketLoaded;
    static final int TICKET_LOADS_PER_PASS = 2;
    static final int TICKET_LOADS_IN_FLIGHT = 6;
    private final Map<UUID, Long> materialWarned = new HashMap<>();
    private final Random random = new Random();
    private long ticks;

    /** Runtime (non-persisted) state of one site. */
    private static final class SiteRuntime {
        private final TemplateLibrary.Runtime tpl;
        private final Placement placement;
        private final BuilderCrew crew = new BuilderCrew();
        private List<RoadDecor.Block> entrance;
        private List<RoadDecor.Block> road;
        private BuildPhases.Phase[] phases;
        private String waitingMaterial;
        /** Since when the site waits for physical material (0 = not waiting). */
        private long waitingSince;
        /** Polish explanation of what the site waits for (null = working). */
        private String status;
        /** Raw material still needed from the cursor on (group → units), refreshed every {@link #NEEDS_MILLIS}. */
        private Map<String, Long> needs = Map.of();
        private long needsAt;
        private boolean night;
        private boolean[] slots;
        /** The site of this runtime and the spot in front of it (material pile without a store chest). */
        private ConstructionSite site;
        private Location front;
        /** Service ticks since the last dry check; -1 = never checked (first tick of the runtime). */
        private int dryTicks = -1;

        private SiteRuntime(final TemplateLibrary.Runtime tpl, final Placement placement) {
            this.tpl = tpl;
            this.placement = placement;
        }

        BlockBox box() {
            return this.placement == null ? null : this.placement.box();
        }
    }

    public ConstructionService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    private LudnoscConfig cfg() {
        return this.plugin.settings();
    }

    // ------------------------------------------------------------------ geometry & checks

    public Placement placement(final BuildingTemplate rotated, final String world, final int ox, final int oy, final int oz) {
        return Placement.centered(world, rotated, ox, oy, oz);
    }

    private SiteRuntime runtime(final ConstructionSite site) {
        final SiteRuntime existing = this.runtimes.get(site.id);
        if (existing != null) {
            return existing;
        }
        if (site.is(ConstructionSite.ROAD) || site.is(ConstructionSite.LEVEL)) {
            final SiteRuntime rt = new SiteRuntime(null, null);
            this.runtimes.put(site.id, rt);
            return rt;
        }
        final TemplateLibrary.Runtime tpl = this.plugin.templates().runtime(site.type, site.rotation, site.dye, MaterialPalette.decode(site.level));
        if (tpl == null) {
            return null;
        }
        if (site.orderVersion < ORDER_VERSION || site.templateHash != tpl.template().contentHash()) {
            if (site.templateHash != tpl.template().contentHash() && site.orderVersion >= ORDER_VERSION) {
                this.plugin.getLogger().info("Szablon " + site.type + " zmienił się — budowa " + site.id + " sprawdza bloki od początku.");
            }
            site.templateHash = tpl.template().contentHash();
            site.orderVersion = ORDER_VERSION;
            if (!site.is(ConstructionSite.RESTORE)) {
                // A started building is re-checked from its foundation (placed blocks are skipped), never sent back
                // into the earthworks: re-grading would dig at the walls that already stand.
                site.phase = pl.medivalrpg.ludnosc.logic.SitePacing.phaseAfterTemplateChange(site.kind(), site.phase);
            }
            site.cursor = 0;
            this.data().markDirty();
        }
        final SiteRuntime rt = new SiteRuntime(tpl, this.placement(tpl.template(), site.world, site.originX, site.originY, site.originZ));
        rt.phases = BuildPhases.classify(tpl.template());
        final BuildingTemplate raw = this.plugin.templates().get(site.type).rotated(site.rotation);
        rt.slots = new boolean[raw.volume()];
        for (int i = 0; i < raw.volume(); i++) {
            rt.slots[i] = MaterialPalette.isSlot(raw.state(i));
        }
        this.runtimes.put(site.id, rt);
        return rt;
    }

    public static boolean chunksLoaded(final World w, final BlockBox box) {
        for (final ChunkRef c : box.chunks()) {
            if (!w.isChunkLoaded(c.cx(), c.cz())) {
                return false;
            }
        }
        return true;
    }

    /** Every chunk of the footprint belongs to the country. */
    public boolean onOwnLand(final UUID country, final BlockBox box) {
        for (final ChunkRef c : box.chunks()) {
            final UUID owner = this.plugin.kraje().getCountryAt(c.world(), c.cx(), c.cz()).map(KrajInfo::id).orElse(null);
            if (!country.equals(owner)) {
                return false;
            }
        }
        return true;
    }

    /** Box of a queued order (null when its template no longer exists or it has no footprint). */
    public BlockBox box(final QueuedOrder o) {
        if (ConstructionSite.ROAD.equals(o.kind())) {
            return BRIDGE_TYPE.equals(o.type) && o.road != null ? pl.medivalrpg.ludnosc.logic.BridgePlan.bounds(o.road) : null;
        }
        final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(o.type, o.rotation);
        return rt == null ? null : this.placement(rt.template(), o.world, o.originX, o.originY, o.originZ).box();
    }

    /** Areas reserved by construction sites and queued orders (except {@code ignore}). */
    private List<BlockBox> reservations(final UUID ignore) {
        final List<BlockBox> out = new ArrayList<>();
        for (final ConstructionSite s : this.data().allSites()) {
            if (!s.id.equals(ignore) && s.is(ConstructionSite.ROAD) && BRIDGE_TYPE.equals(s.type)) {
                out.add(pl.medivalrpg.ludnosc.logic.BridgePlan.bounds(s.road()));
            }
            if (!s.id.equals(ignore) && s.is(ConstructionSite.BUILD) && s.repairOf == null) {
                final SiteRuntime rt = this.runtime(s);
                if (rt != null && rt.box() != null) {
                    out.add(rt.box());
                }
            }
        }
        for (final QueuedOrder o : this.data().allQueued()) {
            if (!o.id.equals(ignore) && ConstructionSite.ROAD.equals(o.kind()) && BRIDGE_TYPE.equals(o.type)) {
                out.add(this.box(o));
            }
            if (!o.id.equals(ignore) && ConstructionSite.BUILD.equals(o.kind())) {
                out.add(this.box(o));
            }
        }
        return out;
    }

    /** Overlaps a registered building, a construction site or a queued (reserved) order — except {@code ignore}. */
    /**
     * First registered building, construction site or queued order the box overlaps, as a Polish description with its
     * outline ("budynek Kuźnia (10 64 3) → (16 70 9)"), null = free.
     */
    public String conflict(final BlockBox box) {
        for (final ChunkRef c : box.chunks()) {
            for (final Building b : this.data().buildingsIn(c)) {
                if (b.box().intersects(box)) {
                    final String name = this.plugin.playerBuildings() != null && PlayerBuildingService.isPlayer(b)
                        ? this.plugin.playerBuildings().plainLabel(b) : this.displayPlain(b.type());
                    return "budynek " + name + " " + b.box();
                }
            }
        }
        for (final ConstructionSite s : this.data().allSites()) {
            BlockBox other = null;
            if (s.is(ConstructionSite.ROAD) && BRIDGE_TYPE.equals(s.type)) {
                other = pl.medivalrpg.ludnosc.logic.BridgePlan.bounds(s.road());
            } else if (s.is(ConstructionSite.BUILD) && s.repairOf == null) {
                final SiteRuntime rt = this.runtime(s);
                other = rt == null ? null : rt.box();
            }
            if (other != null && other.intersects(box)) {
                return "plac budowy " + this.displayPlain(s.type) + " " + other;
            }
        }
        for (final QueuedOrder o : this.data().allQueued()) {
            if (ConstructionSite.BUILD.equals(o.kind()) || ConstructionSite.ROAD.equals(o.kind()) && BRIDGE_TYPE.equals(o.type)) {
                final BlockBox other = this.box(o);
                if (other != null && other.intersects(box)) {
                    return "miejsce zamówione w kolejce budów (" + this.displayPlain(o.type) + ") " + other;
                }
            }
        }
        return null;
    }

    public boolean overlaps(final BlockBox box, final UUID ignore) {
        return this.plugin.buildings().overlapsAny(box) || QueueRules.collides(box, this.reservations(ignore));
    }

    /** Taken areas (buildings, sites, queue) near a column — for the build preview and the planner. */
    public List<BlockBox> takenNear(final String world, final int x, final int z, final int radius, final int max) {
        final List<BlockBox> all = new ArrayList<>(this.reservations(null));
        for (final Building b : this.data().allBuildings()) {
            all.add(b.box());
        }
        final List<BlockBox> out = new ArrayList<>();
        for (final BlockBox b : all) {
            if (b != null && b.world().equals(world) && b.maxX() >= x - radius && b.minX() <= x + radius
                && b.maxZ() >= z - radius && b.minZ() <= z + radius) {
                out.add(b);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    private boolean naturalTerrain(final Material m) {
        return WorldOps.ground(m) || Tag.LEAVES.isTagged(m) || Tag.FLOWERS.isTagged(m) || Tag.REPLACEABLE.isTagged(m)
            || Tag.ICE.isTagged(m) || this.cfg().terrainBlocks.contains(m);
    }

    /**
     * Terrain check (loaded chunks only): every cell the template touches must be free or natural ground (it is levelled
     * by the builders). Player structures, logs, containers → obstructed.
     */
    public boolean terrainFree(final UUID country, final World w, final Placement p) {
        return this.terrainObstruction(country, w, p) == null;
    }

    /**
     * The first concrete obstruction (and how many there are), used by the plan editor instead of labelling every block a
     * player building. Natural terrain refuses only automatic NPC orders above the cut limit: the builders excavate every
     * template cell, so a player is never sent to dig away earth the crew would move itself (and may pile up again with
     * the earthworks of a neighbouring site). Leaves, snow and scaffolding are replaced by the builders at any height.
     * See {@link pl.medivalrpg.ludnosc.logic.TerrainObstruction} (dirt fix 2026-11-03) and
     * {@link pl.medivalrpg.ludnosc.logic.BuildFixes#clearable}.
     */
    public String terrainObstruction(final UUID country, final World w, final Placement p) {
        final BuildingTemplate t = p.template();
        final boolean automatic = country == null || this.plugin.automaticBuildingAllowed(country);
        final int cutLimit = pl.medivalrpg.ludnosc.logic.TerrainObstruction.cutLimit(p.groundY(), this.cfg().maxTerrainCut,
            automatic);
        Block first = null;
        pl.medivalrpg.ludnosc.logic.TerrainObstruction.Cell firstKind = null;
        int count = 0;
        for (int i = 0; i < t.volume(); i++) {
            if (t.state(i) == null) {
                continue;
            }
            final int y = p.y(i);
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
                return "Obrys budynku wychodzi poza wysokość świata przy "+p.x(i)+" "+y+" "+p.z(i)+".";
            }
            final Block b = w.getBlockAt(p.x(i), y, p.z(i));
            final pl.medivalrpg.ludnosc.logic.TerrainObstruction.Cell kind = b.isEmpty() || b.isLiquid() || b.isReplaceable()
                || pl.medivalrpg.ludnosc.logic.BuildFixes.clearable(b.getType().name())
                ? pl.medivalrpg.ludnosc.logic.TerrainObstruction.Cell.FREE
                : this.naturalTerrain(b.getType()) ? pl.medivalrpg.ludnosc.logic.TerrainObstruction.Cell.NATURAL
                : pl.medivalrpg.ludnosc.logic.TerrainObstruction.Cell.BUILT;
            if (!pl.medivalrpg.ludnosc.logic.TerrainObstruction.blocks(kind, y, cutLimit)) {
                continue;
            }
            if (first == null) {
                first = b;
                firstKind = kind;
                if (automatic) {
                    break; // the planner only needs a verdict; players get the full count
                }
            }
            if (kind == firstKind) {
                count++;
            }
        }
        return first == null ? null : pl.medivalrpg.ludnosc.logic.TerrainObstruction.reason(first.getType().name(), first.getX(),
            first.getY(), first.getZ(), firstKind, Math.max(1, count));
    }

    /** Sites that take a slot (restore clean-ups do not). */
    private int activeSites(final UUID country) {
        int n = 0;
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (!s.is(ConstructionSite.RESTORE)) {
                n++;
            }
        }
        return n;
    }

    /** Active sites of a country (for {@code /ludnosc info}). */
    public int sitesInUse(final UUID country) {
        return this.activeSites(country);
    }

    /** Site slots of a country: base + per settlement level + per epoch, capped. */
    public int maxSites(final UUID country) {
        final int level = this.plugin.settlement().levelIndex(country);
        final int era = this.plugin.postep().era(country);
        return QueueRules.siteSlots(this.cfg().maxSitesPerCountry, this.cfg().sitesPerTier, level, this.cfg().sitesPerEra, era,
            this.cfg().maxSitesCap);
    }

    /** Would a new order of this country start now, wait in the queue, or be refused? */
    public QueueRules.Admission admission(final UUID country) {
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(country))return QueueRules.Admission.FULL;
        return QueueRules.admit(this.activeSites(country), this.data().queueSize(country), this.maxSites(country),
            this.cfg().queueSize);
    }

    /** Checks an order without paying. Terrain is checked only when the area is loaded (otherwise when it loads). */
    public BuildOrderResult check(final UUID country, final String type, final World w, final int ox, final int oy,
                                  final int oz, final int quarterTurns, final UUID ignoreSite) {
        return this.check(country, type, w, ox, oy, oz, quarterTurns, ignoreSite, false);
    }

    /** Reason of the last refused check per country (Polish). */
    private final Map<UUID, String> checkReasons = new java.util.concurrent.ConcurrentHashMap<>();

    public String lastCheckReason(final UUID country) {
        return country == null ? null : this.checkReasons.get(country);
    }

    private BuildOrderResult refuse(final UUID country, final BuildOrderResult r, final String reason) {
        if (country != null) {
            this.checkReasons.put(country, reason);
        }
        return r;
    }

    /**
     * {@code forced}: an admin / panel order at explicit coordinates — rough terrain and non-natural blocks in the
     * footprint do not refuse it (the builders terraform and clear), only another building or foreign land does.
     */
    public BuildOrderResult check(final UUID country, final String type, final World w, final int ox, final int oy,
                                  final int oz, final int quarterTurns, final UUID ignoreSite, final boolean forced) {
        if (country != null) {
            this.checkReasons.remove(country); // the reason always belongs to THIS check
        }
        if(!pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(country,type))return this.refuse(country,BuildOrderResult.UNKNOWN_BUILDING,"Ten pałac jest zastrzeżony wyłącznie dla KRÓLESTWA.");
        if (this.plugin.blueprints() != null && !this.plugin.blueprints().mayOrder(country, type)) {
            return this.refuse(country, BuildOrderResult.UNKNOWN_BUILDING, "to kopia budowli innego kraju albo usunięta kopia");
        }
        final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(type, quarterTurns);
        if (rt == null) {
            return BuildOrderResult.UNKNOWN_BUILDING;
        }
        if (this.admission(country) == QueueRules.Admission.FULL) {
            return this.refuse(country, BuildOrderResult.TOO_MANY_ORDERS, "wszystkie miejsca budów i kolejka kraju są zajęte ("
                + this.activeSites(country) + "/" + this.maxSites(country) + " placów, kolejka " + this.data().queueSize(country) + "/"
                + this.cfg().queueSize + ")");
        }
        // buildings of a later epoch / discovery (plugin Postep) are not available yet
        final BuildingTemplate.Meta meta = rt.template().meta();
        if (meta.era() > this.plugin.postep().era(country)) {
            return this.refuse(country, BuildOrderResult.UNKNOWN_BUILDING,
                "wymaga epoki: " + pl.medivalrpg.ludnosc.hook.PostepHook.eraName(meta.era()));
        }
        if (!meta.discovery().isEmpty() && !this.plugin.postep().hasDiscovery(country, meta.discovery())) {
            return this.refuse(country, BuildOrderResult.UNKNOWN_BUILDING,
                "wymaga odkrycia: " + pl.medivalrpg.ludnosc.hook.PostepHook.discoveryName(meta.discovery()));
        }
        final Placement p = this.placement(rt.template(), w.getName(), ox, oy, oz);
        final BlockBox box = p.box();
        if (box.maxY() >= w.getMaxHeight() - 1 || box.minY() <= w.getMinHeight()) {
            return this.refuse(country, BuildOrderResult.AREA_OBSTRUCTED, "budynek wychodzi poza limit wysokości świata");
        }
        if (!this.onOwnLand(country, box)) {
            return this.refuse(country, BuildOrderResult.NOT_ON_OWN_LAND, "obrys wychodzi poza ziemię kraju");
        }
        if (this.overlaps(box, ignoreSite)) {
            return this.refuse(country, BuildOrderResult.AREA_OBSTRUCTED, "kolizja z innym budynkiem, placem budowy lub zamówieniem w kolejce");
        }
        if (!forced && chunksLoaded(w, box)) {
            final String obstruction=this.terrainObstruction(country, w, p);
            if(obstruction!=null) return this.refuse(country, BuildOrderResult.AREA_OBSTRUCTED, obstruction);
        }
        if (country != null) {
            this.checkReasons.remove(country);
        }
        return BuildOrderResult.OK;
    }

    /**
     * Outcome of an order.
     *
     * @param queued   true when it waits in the queue (already paid, area reserved)
     * @param position 1-based queue position when queued
     */
    public record OrderOutcome(BuildOrderResult result, boolean queued, int position) {
    }

    /** Places an order (main thread). {@code free} skips the treasury (NPC starting buildings). Queued orders → OK. */
    public BuildOrderResult order(final UUID country, final String type, final World w, final int ox, final int oy,
                                  final int oz, final int quarterTurns, final boolean free, final String orderedBy) {
        return this.orderDetailed(country, type, w, ox, oy, oz, quarterTurns, free, orderedBy).result();
    }

    public OrderOutcome orderDetailed(final UUID country, final String type, final World w, final int ox, final int oy,
                                      final int oz, final int quarterTurns, final boolean free, final String orderedBy) {
        return this.orderDetailed(country, type, w, ox, oy, oz, quarterTurns, free, orderedBy, null);
    }

    /**
     * Places an order: starts a site when a slot is free (and nobody waits), otherwise pays now and appends it to the
     * country's queue with its area reserved; {@code TOO_MANY_ORDERS} only when the queue is full too.
     *
     * @param road street cells from the planner, built after the building (may be null)
     */
    public OrderOutcome orderDetailed(final UUID country, final String type, final World w, final int ox, final int oy,
                                      final int oz, final int quarterTurns, final boolean free, final String orderedBy,
                                      final List<BlockPos> road) {
        return this.orderDetailed(country, type, w, ox, oy, oz, quarterTurns, free, orderedBy, road, false);
    }

    /** {@code forced}: see {@link #check(UUID, String, World, int, int, int, int, UUID, boolean)}. */
    public OrderOutcome orderDetailed(final UUID country, final String type, final World w, final int ox, final int oy,
                                      final int oz, final int quarterTurns, final boolean free, final String orderedBy,
                                      final List<BlockPos> road, final boolean forced) {
        final int q = Math.floorMod(quarterTurns, 4);
        final BuildOrderResult result = this.check(country, type, w, ox, oy, oz, q, null, forced);
        if (result != BuildOrderResult.OK) {
            return new OrderOutcome(result, false, 0);
        }
        if (!this.plugin.buildingAllowed(country, type)) {
            this.checkReasons.put(country, "Ten budynek gracze budują samodzielnie. Wybierz typ z /ludnosc buduj albo użyj /ludnosc kopia.");
            return new OrderOutcome(BuildOrderResult.UNKNOWN_BUILDING, false, 0);
        }
        final QueueRules.Admission admission = this.admission(country);
        final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(type, q);
        final long cost = free ? 0 : rt.template().meta().cost();
        if (cost > 0 && !this.pay(country, cost, "Ludnosc: budowa " + rt.template().id()
            + (admission == QueueRules.Admission.QUEUE ? " (kolejka)" : ""))) {
            return new OrderOutcome(BuildOrderResult.INSUFFICIENT_FUNDS, false, 0);
        }
        final String dye = this.plugin.dyeOf(country);
        if (admission == QueueRules.Admission.QUEUE) {
            final QueuedOrder o = QueuedOrder.of(country, rt.template().id(), w.getName(), ox, oy, oz, q, cost, orderedBy);
            o.dye = dye;
            o.level = MaterialPalette.encode(this.plugin.settlement().style(country));
            o.road = road == null ? null : new ArrayList<>(road);
            this.data().enqueue(o);
            return new OrderOutcome(BuildOrderResult.OK, true, this.data().queueSize(country));
        }
        final ConstructionSite site = this.newSite(country, ConstructionSite.BUILD, rt.template().id(), w.getName(), ox, oy, oz, q,
            cost, orderedBy, dye);
        if (forced) {
            site.validated = true; // explicit admin order: no obstruction check when the chunks load
            final Placement fp = this.placement(rt.template(), w.getName(), ox, oy, oz);
            if (chunksLoaded(w, fp.box()) && !this.terrainFree(country, w, fp)) {
                this.checkReasons.put(country, "uwaga: w obrysie były budowle, zostaną rozebrane przy budowie");
                this.plugin.getLogger().warning("Wymuszona budowa " + type + " w " + ox + " " + oy + " " + oz
                    + ": w obrysie są nienaturalne bloki (zostaną zastąpione).");
            }
        }
        if (road != null) {
            site.road().addAll(road);
        }
        this.data().putSite(site);
        return new OrderOutcome(BuildOrderResult.OK, false, 0);
    }

    /**
     * A road-works order (cheap "terrain building" in the same queue): the given street cells, with half slabs on
     * climbs and lamps. Free when {@code cost == 0}.
     */
    public OrderOutcome orderRoad(final UUID country, final String world, final List<BlockPos> cells, final long cost,
                                  final String orderedBy) {
        if (cells.isEmpty()) {
            return new OrderOutcome(BuildOrderResult.AREA_OBSTRUCTED, false, 0);
        }
        final QueueRules.Admission admission = this.admission(country);
        if (admission == QueueRules.Admission.FULL) {
            return new OrderOutcome(BuildOrderResult.TOO_MANY_ORDERS, false, 0);
        }
        if (cost > 0 && !this.pay(country, cost, "Ludnosc: drogi")) {
            return new OrderOutcome(BuildOrderResult.INSUFFICIENT_FUNDS, false, 0);
        }
        final BlockPos first = cells.get(0);
        if (admission == QueueRules.Admission.QUEUE) {
            final QueuedOrder o = QueuedOrder.of(country, "droga", world, first.x(), first.y(), first.z(), 0, cost, orderedBy);
            o.kind = ConstructionSite.ROAD;
            o.road = new ArrayList<>(cells);
            this.data().enqueue(o);
            return new OrderOutcome(BuildOrderResult.OK, true, this.data().queueSize(country));
        }
        final ConstructionSite site = this.newSite(country, ConstructionSite.ROAD, "droga", world, first.x(), first.y(), first.z(),
            0, cost, orderedBy, null);
        site.road().addAll(cells);
        site.phase = Phase.ROAD.ordinal();
        this.data().putSite(site);
        return new OrderOutcome(BuildOrderResult.OK, false, 0);
    }

    /**
     * A repair of a registered building, already priced; goes to the FRONT of the queue (or starts at once).
     * Returns false when the treasury cannot pay.
     */
    public boolean orderRepair(final Building b, final long cost, final boolean rebuild, final String orderedBy) {
        if(!pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(b.countryId(),b.type()))return false;
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(b.countryId()))return false;
        if (b.countryId() == null || this.repairing(b.id())) {
            return false;
        }
        // Origin first: a building without a template runtime must not be charged for a repair that never starts.
        final int[] origin = this.originOf(b);
        if (origin == null) {
            return false;
        }
        if (cost > 0 && !this.pay(b.countryId(), cost, "Ludnosc: naprawa " + b.type())) {
            return false;
        }
        final String kind = rebuild ? ConstructionSite.BUILD : ConstructionSite.REPAIR;
        return this.enqueueWork(b, kind, cost, orderedBy, MaterialPalette.decode(b.level()), rebuild ? Phase.TERRAIN : Phase.CELLS, origin);
    }

    /**
     * Modernisation: the building's material slots are rebuilt with the materials of the current settlement level
     * (front of the queue like a repair, cheaper than a new building). False when there is nothing to do or no money.
     */
    public boolean orderUpgrade(final Building b, final long cost, final int level, final String orderedBy) {
        if(!pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(b.countryId(),b.type()))return false;
        if (b.countryId() == null || this.repairing(b.id()) || level <= MaterialPalette.decode(b.level())) {
            return false;
        }
        final BuildingTemplate t = this.plugin.templates().get(b.type());
        if (t == null || !t.usesMaterials()) {
            return false;
        }
        final int[] origin = this.originOf(b);
        if (origin == null) {
            return false; // checked BEFORE paying: no charge without a site
        }
        if (cost > 0 && !this.pay(b.countryId(), cost, "Ludnosc: modernizacja " + b.type())) {
            return false;
        }
        return this.enqueueWork(b, ConstructionSite.UPGRADE, cost, orderedBy, level, Phase.CELLS, origin);
    }

    /**
     * Demolition by the builders (towns rebuilt in a new epoch): the building's blocks are taken down from the top, the
     * building leaves the register, then {@code then} (if any) is ordered at the same place. False when busy.
     */
    public boolean orderDemolition(final Building b, final String then, final String orderedBy) {
        if (b.countryId() == null || this.repairing(b.id()) || (this.cfg().heritage.contains(b.type()) || this.cfg().heritage.contains(this.plugin.templates().baseType(b.type())))
            || this.plugin.templates().get(b.type()) == null
            || this.plugin.design().noDemolition(b.countryId(), b.box().centerX(), b.box().centerZ())) {
            return false; // heritage and buildings without a Ludnosc template are never demolished
        }
        final int[] origin = this.originOf(b);
        if (origin == null) {
            return false;
        }
        this.pendingThen = then;
        try {
            return this.enqueueWork(b, ConstructionSite.DEMOLISH, 0, orderedBy, MaterialPalette.decode(b.level()), Phase.CELLS, origin);
        } finally {
            this.pendingThen = null;
        }
    }

    /** {@code then} of the demolition being enqueued. */
    private String pendingThen;

    private boolean enqueueWork(final Building b, final String kind, final long cost, final String orderedBy, final int level,
                                final Phase phase, final int[] origin) {
        if (this.activeSites(b.countryId()) < this.maxSites(b.countryId())) {
            final ConstructionSite site = this.newSite(b.countryId(), kind, b.type(), b.box().world(), origin[0], origin[1], origin[2],
                b.rotation(), cost, orderedBy, b.dye(), level);
            site.repairOf = b.id();
            site.then = this.pendingThen;
            site.phase = phase.ordinal();
            this.data().putSite(site);
        } else {
            final QueuedOrder o = QueuedOrder.of(b.countryId(), b.type(), b.box().world(), origin[0], origin[1], origin[2],
                b.rotation(), cost, orderedBy);
            o.kind = kind;
            o.repairOf = b.id();
            o.then = this.pendingThen;
            o.dye = b.dye();
            o.level = MaterialPalette.encode(level);
            this.data().enqueueFront(o);
        }
        return true;
    }

    /** Origin (footprint centre, floor y) of a template building from its box. */
    int[] originOf(final Building b) {
        final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(b.type(), b.rotation(), b.dye(), MaterialPalette.decode(b.level()));
        if (rt == null) {
            return null;
        }
        final BuildingTemplate t = rt.template();
        return new int[] {b.box().minX() + t.width() / 2, b.box().minY() + t.meta().groundLayer(), b.box().minZ() + t.depth() / 2};
    }

    /** A repair (site or queued) of the building exists. */
    public boolean repairing(final UUID building) {
        for (final ConstructionSite s : this.data().allSites()) {
            if (building.equals(s.repairOf)) {
                return true;
            }
        }
        for (final QueuedOrder o : this.data().allQueued()) {
            if (building.equals(o.repairOf)) {
                return true;
            }
        }
        return false;
    }

    private boolean pay(final UUID country, final long cost, final String reason) {
        final SkarbiecApi eco = this.plugin.skarbiec();
        return eco != null && eco.withdraw(SkarbiecApi.countryAccount(country), cost, reason).ok();
    }

    private ConstructionSite newSite(final UUID country, final String kind, final String type, final String world, final int ox,
                                     final int oy, final int oz, final int q, final long cost, final String orderedBy,
                                     final String dye) {
        return this.newSite(country, kind, type, world, ox, oy, oz, q, cost, orderedBy, dye, this.plugin.settlement().style(country));
    }

    private ConstructionSite newSite(final UUID country, final String kind, final String type, final String world, final int ox,
                                     final int oy, final int oz, final int q, final long cost, final String orderedBy,
                                     final String dye, final int level) {
        final ConstructionSite site = new ConstructionSite();
        site.level = MaterialPalette.encode(level);
        site.id = UUID.randomUUID();
        site.countryId = country;
        site.kind = kind;
        site.type = type;
        site.world = world;
        site.originX = ox;
        site.originY = oy;
        site.originZ = oz;
        site.rotation = Math.floorMod(q, 4);
        site.createdAt = System.currentTimeMillis();
        site.cost = cost;
        site.dye = dye;
        site.orderVersion = ORDER_VERSION;
        site.orderedBy = orderedBy == null ? "" : orderedBy;
        final TemplateLibrary.Runtime rt = ConstructionSite.ROAD.equals(kind) ? null
            : this.plugin.templates().runtime(type, site.rotation, dye, level);
        if (rt != null) {
            site.templateHash = rt.template().contentHash();
            final World w = Bukkit.getWorld(world);
            site.validated = w != null && chunksLoaded(w, this.placement(rt.template(), world, ox, oy, oz).box());
        } else {
            site.validated = true;
        }
        return site;
    }

    // ------------------------------------------------------------------ queue

    private long refund(final UUID country, final long amount, final String reason) {
        final SkarbiecApi eco = this.plugin.skarbiec();
        if (amount <= 0 || eco == null || this.plugin.kraje().getCountry(country).isEmpty()) {
            return 0;
        }
        final var tx = eco.deposit(SkarbiecApi.countryAccount(country), amount, reason);
        if (!tx.ok()) {
            this.plugin.getLogger().warning("Zwrot " + amount + " md krajowi " + country + " nieudany (" + tx + "): " + reason);
            return 0;
        }
        return amount;
    }

    /** A queued order can still start: country, world and template exist, land is own, area free. */
    private boolean queuedStillValid(final QueuedOrder o) {
        if(!pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(o.countryId,o.type))return false;
        final World w = Bukkit.getWorld(o.world);
        if (w == null || this.plugin.kraje().getCountry(o.countryId).isEmpty()) {
            return false;
        }
        if (ConstructionSite.ROAD.equals(o.kind())) {
            return true;
        }
        if (o.repairOf != null) {
            final Building b = this.data().building(o.repairOf);
            return b != null && o.countryId.equals(b.countryId());
        }
        final BlockBox box = this.box(o);
        if (box == null || !this.onOwnLand(o.countryId, box) || this.overlaps(box, o.id)) {
            return false;
        }
        if (chunksLoaded(w, box)) {
            final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(o.type, o.rotation);
            return this.terrainFree(o.countryId, w, this.placement(rt.template(), o.world, o.originX, o.originY, o.originZ));
        }
        return true;
    }

    /** Repairs wait while the building's chunk is besieged. */
    private boolean waitingForSiege(final QueuedOrder o) {
        if (o.repairOf == null) {
            return false;
        }
        final Building b = this.data().building(o.repairOf);
        return b != null && this.besieged(b.box());
    }

    boolean besieged(final BlockBox box) {
        for (final ChunkRef c : box.chunks()) {
            if (this.plugin.kraje().getSiege(c.world(), c.cx(), c.cz()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Starts queued orders of countries with free site slots (called every service tick). */
    private void startQueued() {
        for (final UUID country : this.data().queuedCountries()) {
            if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(country))continue;
            final int can = QueueRules.startable(this.activeSites(country), this.data().queueSize(country),
                this.maxSites(country));
            for (int i = 0; i < can; i++) {
                final List<QueuedOrder> copy = new ArrayList<>(this.data().queue(country));
                final List<QueuedOrder> rejected = new ArrayList<>();
                final QueuedOrder next = QueueRules.pollFirstStartable(copy, this::waitingForSiege, this::queuedStillValid,
                    rejected::add);
                for (final QueuedOrder r : rejected) {
                    this.data().removeQueued(r);
                    final long back = this.refund(r.countryId, QueueRules.refund(r.cost, true, 1), "Ludnosc: zwrot (kolejka) " + r.type);
                    this.plugin.notifyCountry(r.countryId, "queue-skipped", Placeholder.parsed("name", this.displayName(r.type)),
                        Placeholder.unparsed("refund", this.plugin.formatMoney(back)));
                }
                if (next == null) {
                    break;
                }
                this.data().removeQueued(next);
                final ConstructionSite site = this.newSite(next.countryId, next.kind(), next.type, next.world, next.originX,
                    next.originY, next.originZ, next.rotation, next.cost, next.orderedBy, next.dye,
                    ConstructionSite.BUILD.equals(next.kind()) && next.repairOf == null
                        ? Math.max(MaterialPalette.decode(next.level), this.plugin.settlement().style(next.countryId))
                        : MaterialPalette.decode(next.level));
                site.repairOf = next.repairOf;
                site.then = next.then;
                if (next.road != null) {
                    site.road().addAll(next.road);
                }
                site.phase = site.is(ConstructionSite.ROAD) ? Phase.ROAD.ordinal()
                    : site.is(ConstructionSite.REPAIR) || site.is(ConstructionSite.UPGRADE) || site.is(ConstructionSite.DEMOLISH)
                    ? Phase.CELLS.ordinal() : Phase.TERRAIN.ordinal();
                this.data().putSite(site);
                this.plugin.notifyCountry(next.countryId, "queue-started", Placeholder.parsed("name", this.displayName(next.type)),
                    Placeholder.unparsed("pos", next.originX + " " + next.originY + " " + next.originZ));
            }
        }
    }

    /** Cancels a queued order with a full refund. Returns the refund. */
    public long cancelQueued(final QueuedOrder o, final String messageKey) {
        if (!this.data().removeQueued(o)) {
            return 0;
        }
        final long back = this.refund(o.countryId, QueueRules.refund(o.cost, true, 1), "Ludnosc: zwrot (kolejka) " + o.type);
        if (messageKey != null) {
            this.plugin.notifyCountry(o.countryId, messageKey, Placeholder.parsed("name", this.displayName(o.type)),
                Placeholder.unparsed("refund", this.plugin.formatMoney(back)));
        }
        return back;
    }

    // ------------------------------------------------------------------ chunk tickets

    /**
     * Keeps the chunks of sites that can work loaded (server-wide limit): sites of players' countries first, then NPC
     * sites, oldest first; sites waiting for a material only from what is left; sites that cannot work now (levelling
     * after the daily limit, repairs during a siege, outside working hours) get none. Releases the rest.
     */
    private void updateTickets() {
        final Set<ChunkRef> wanted = new LinkedHashSet<>();
        if (this.cfg().keepLoaded) {
            final Map<UUID, ConstructionSite> byId = new HashMap<>();
            final List<pl.medivalrpg.ludnosc.logic.BuildFixes.TicketCandidate> candidates = new ArrayList<>();
            final long today = System.currentTimeMillis() / 86_400_000L;
            for (final ConstructionSite s : this.data().allSites()) {
                final World w = Bukkit.getWorld(s.world);
                if (w == null) {
                    continue;
                }
                byId.put(s.id, s);
                candidates.add(new pl.medivalrpg.ludnosc.logic.BuildFixes.TicketCandidate(s.id, s.createdAt,
                    !this.plugin.kraje().getCountry(s.countryId).map(KrajInfo::npc).orElse(true), this.readiness(s, w, today)));
            }
            final List<TicketAllocator.Demand> demands = new ArrayList<>();
            for (final UUID id : pl.medivalrpg.ludnosc.logic.BuildFixes.ticketOrder(candidates)) {
                final ConstructionSite s = byId.get(id);
                final Set<ChunkRef> chunks = new LinkedHashSet<>();
                final SiteRuntime rt = this.runtime(s);
                if (rt != null) {
                    chunks.addAll(this.loadingChunks(s, rt));
                }
                for (final BlockPos p : s.road()) {
                    chunks.add(p.chunk());
                }
                if (BRIDGE_TYPE.equals(s.type)) {
                    final BlockBox bridge = pl.medivalrpg.ludnosc.logic.BridgePlan.bounds(s.road());
                    if (bridge != null) {
                        chunks.addAll(bridge.chunks());
                    }
                }
                if (!chunks.isEmpty()) {
                    demands.add(new TicketAllocator.Demand(s.id, chunks));
                }
            }
            wanted.addAll(TicketAllocator.allocate(demands, this.cfg().maxLoadedChunks).chunks());
        }
        // never a synchronous chunk load here: loaded chunks get their ticket now, the others are loaded asynchronously,
        // a few per pass (a ticket on an unloaded chunk used to load / generate it on the main thread: 10–15 s at start)
        this.ticketsWanted = wanted;
        final pl.medivalrpg.ludnosc.logic.TicketPlan.Step step = pl.medivalrpg.ludnosc.logic.TicketPlan.step(wanted, this.tickets,
            this.ticketLoads, c -> {
                final World w = Bukkit.getWorld(c.world());
                return w != null && w.isChunkLoaded(c.cx(), c.cz());
            }, TICKET_LOADS_PER_PASS, TICKET_LOADS_IN_FLIGHT);
        for (final ChunkRef c : step.release()) {
            final World w = Bukkit.getWorld(c.world());
            if (w != null) {
                w.removePluginChunkTicket(c.cx(), c.cz(), this.plugin);
            }
            this.tickets.remove(c);
        }
        for (final ChunkRef c : step.addNow()) {
            final World w = Bukkit.getWorld(c.world());
            if (w != null && this.tickets.add(c)) {
                w.addPluginChunkTicket(c.cx(), c.cz(), this.plugin);
            }
        }
        for (final ChunkRef c : step.load()) {
            final World w = Bukkit.getWorld(c.world());
            if (w == null) {
                continue;
            }
            this.ticketLoads.add(c);
            w.getChunkAtAsync(c.cx(), c.cz()).whenComplete((chunk, error) -> {
                final Runnable attach = () -> {
                    this.ticketLoads.remove(c);
                    if (error == null && chunk != null && this.plugin.isEnabled() && this.ticketsWanted.contains(c) && this.tickets.add(c)) {
                        chunk.addPluginChunkTicket(this.plugin); // already loaded: no main-thread load
                        this.ticketLoaded++;
                    }
                };
                if (Bukkit.isPrimaryThread()) {
                    attach.run();
                } else if (this.plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(this.plugin, attach);
                }
            });
        }
    }

    /** Whether a site can use chunk tickets now (see {@link pl.medivalrpg.ludnosc.logic.BuildFixes.Readiness}). */
    private pl.medivalrpg.ludnosc.logic.BuildFixes.Readiness readiness(final ConstructionSite s, final World w, final long today) {
        if (!this.cfg().workTime(w.getTime()) && !s.is(ConstructionSite.RESTORE)) {
            return pl.medivalrpg.ludnosc.logic.BuildFixes.Readiness.IDLE;
        }
        if (s.is(ConstructionSite.LEVEL) && s.phase == Phase.TERRAIN.ordinal()) {
            final CountryState st = this.data().stateIfPresent(s.countryId);
            if (st != null && pl.medivalrpg.ludnosc.logic.BuildFixes.levelLimitReached(today, st.levelDay, st.levelBlocks,
                this.cfg().redesignLevelBlocksPerDay, st.planActive && st.planForced)) {
                return pl.medivalrpg.ludnosc.logic.BuildFixes.Readiness.IDLE;
            }
        }
        final SiteRuntime rt = this.runtimes.get(s.id);
        if ((s.is(ConstructionSite.REPAIR) || s.is(ConstructionSite.UPGRADE)) && rt != null) {
            final BlockBox box = this.workBox(s, rt);
            if (box != null && this.besieged(box)) {
                return pl.medivalrpg.ludnosc.logic.BuildFixes.Readiness.IDLE;
            }
        }
        return rt != null && rt.waitingMaterial != null ? pl.medivalrpg.ludnosc.logic.BuildFixes.Readiness.WAITING
            : pl.medivalrpg.ludnosc.logic.BuildFixes.Readiness.WORKING;
    }

    /** Margin around road / wall cells whose chunks a road or wall site reads (pavement, lamps, towers, ground). */
    static final int CELL_MARGIN = 3;

    /**
     * Chunks a site needs loaded to work: roads and walls only the chunks of their cells (with {@link #CELL_MARGIN}),
     * never the whole rectangle of a long diagonal road; buildings, bridges and levelling their {@link #loadingBox}.
     */
    private Set<ChunkRef> loadingChunks(final ConstructionSite site, final SiteRuntime rt) {
        if (site.is(ConstructionSite.ROAD) && !BRIDGE_TYPE.equals(site.type)) {
            final List<int[]> cells = new ArrayList<>();
            for (final BlockPos p : site.road()) {
                cells.add(new int[] {p.x(), p.z()});
            }
            return pl.medivalrpg.ludnosc.logic.BuildFixes.cellChunks(site.world, cells, CELL_MARGIN);
        }
        final BlockBox box = this.loadingBox(site, rt);
        return box == null ? Set.of() : new LinkedHashSet<>(box.chunks());
    }

    private static boolean chunksLoaded(final World w, final Set<ChunkRef> chunks) {
        for (final ChunkRef c : chunks) {
            if (!w.isChunkLoaded(c.cx(), c.cz())) {
                return false;
            }
        }
        return true;
    }

    /** Footprint of a template at an origin and rotation, null for unknown types. */
    public BlockBox footprint(final String type, final String world, final int ox, final int oy, final int oz, final int rotation) {
        final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(type, Math.floorMod(rotation, 4));
        return rt == null ? null : this.placement(rt.template(), world, ox, oy, oz).box();
    }

    /** Why a repair of the building cannot be ordered now (Polish), null = it can. */
    public String repairRefusal(final Building b, final long cost) {
        final SkarbiecApi eco = this.plugin.skarbiec();
        return pl.medivalrpg.ludnosc.logic.BuildFixes.repairRefusal(
            !pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(b.countryId(), b.type()),
            this.plugin.royalRebuild() != null && this.plugin.royalRebuild().blocks(b.countryId()),
            b.countryId() == null, b.countryId() != null && this.repairing(b.id()), this.originOf(b) == null,
            cost > 0 && b.countryId() != null && (eco == null || !eco.has(SkarbiecApi.countryAccount(b.countryId()), cost)),
            this.plugin.formatMoney(cost));
    }

    /** Since when (epoch ms) the site waits for a material, or null when it does not wait now. Main thread. */
    public Long waitingSince(final UUID site) {
        final SiteRuntime rt = this.runtimes.get(site);
        return rt == null || rt.waitingMaterial == null || rt.waitingSince == 0 ? null : rt.waitingSince;
    }

    /** The material (Polish group name) a site waits for now, or null. Main thread. */
    public String waitingFor(final UUID site) {
        final SiteRuntime rt = this.runtimes.get(site);
        return rt == null ? null : rt.waitingMaterial;
    }

    /** Ticket pass timing: {last ms, longest ms, chunks loaded asynchronously, loads in flight}. */
    public double[] ticketStats() {
        return new double[] {this.ticketMs, this.ticketMaxMs, this.ticketLoaded, this.ticketLoads.size()};
    }

    public int ticketCount() {
        return this.tickets.size();
    }

    private void releaseTickets() {
        for (final World w : Bukkit.getWorlds()) {
            w.removePluginChunkTickets(this.plugin);
        }
        this.tickets.clear();
        this.ticketsWanted = Set.of();
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        this.ticks++;
        if (this.ticks == 1) {
            try {
                final int split = this.splitScatteredWalls();
                if (split > 0) {
                    this.plugin.getLogger().info("Mury: podzielono " + split + " rozproszonych odcinków (sprzed poprawki) na ciągłe odcinki.");
                }
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd podziału odcinków murów", e);
            }
        }
        try {
            this.startQueued();
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd kolejki budów", e);
        }
        if (this.ticks % 10 == 1) {
            final long t0 = System.nanoTime();
            try {
                this.updateTickets();
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd biletów chunków", e);
            }
            this.ticketMs = (System.nanoTime() - t0) / 1_000_000.0;
            if (this.ticketMs > this.ticketMaxMs) {
                this.ticketMaxMs = this.ticketMs;
                if (this.ticketMs > 50) {
                    this.plugin.getLogger().warning(String.format(java.util.Locale.ROOT,
                        "Bilety chunków placów budowy: przebieg %.1f ms (%d biletów, %d w drodze)", this.ticketMs, this.tickets.size(),
                        this.ticketLoads.size()));
                }
            }
        }
        // CPU budget: blocks per tick for the whole server and per country, sites served round-robin
        this.serverLeft = this.cfg().blocksPerTickServer;
        this.countryLeft.clear();
        final List<ConstructionSite> all = new ArrayList<>(this.data().allSites());
        if (!all.isEmpty()) {
            java.util.Collections.rotate(all, -(int) (this.ticks % all.size()));
        }
        for (final ConstructionSite site : all) {
            if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(site.countryId))continue;
            try {
                this.tickSite(site);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Błąd placu budowy " + site.id, e);
            }
        }
        this.rebuildGuard();
    }

    /** Every 2 game ticks: builders walk. */
    public void moveBuilders() {
        final double step = this.cfg().walkSpeed * 0.1;
        for (final SiteRuntime rt : this.runtimes.values()) {
            if (!rt.crew.bodies.isEmpty()) {
                rt.crew.move(step);
            }
        }
        this.plugin.resources().move(step);
        this.plugin.mines().moveCrews(step * 0.6);
        if (this.plugin.logistics() != null) {
            this.plugin.logistics().move(step);
        }
        if (this.plugin.workshops() != null) {
            this.plugin.workshops().move(step);
        }
    }

    private int builderCount(final SiteRuntime rt) {
        if (rt.tpl == null) {
            return Math.max(1, this.cfg().buildersPerSite - 1);
        }
        final int extra = rt.tpl.template().solidCount() / this.cfg().blocksPerExtraBuilder;
        final int cap = this.cfg().buildersMax + (rt.tpl.template().height() > 30 ? 4 : 0); // high-rise: a bigger crew
        return Math.max(1, Math.min(cap, this.cfg().buildersPerSite + extra));
    }

    /** Box of the area a site works on (template box, or the road cells). */
    private BlockBox workBox(final ConstructionSite site, final SiteRuntime rt) {
        if (BRIDGE_TYPE.equals(site.type)) {
            return pl.medivalrpg.ludnosc.logic.BridgePlan.bounds(site.road());
        }
        if (rt.box() != null) {
            return rt.box();
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (final BlockPos p : site.road()) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        if (minX == Integer.MAX_VALUE) {
            return null;
        }
        return new BlockBox(site.world, minX, minY - 2, minZ, maxX, maxY + 4, maxZ);
    }

    /** Load the grading apron without expanding the site that pushes villagers away or places helpers. */
    /**
     * Area whose chunks must be loaded before a site works (and that its tickets keep loaded): every block the site
     * reads or writes must lie inside, or the read would load (even generate) a chunk synchronously on the main thread.
     * Earthworks, entrance steps and the clean-up after a cancelled build reach the whole earthworks band; the other
     * phases reach {@link #NEAR_MARGIN} blocks around the plot (front spot, stand spots, scaffolding, plot sweep).
     */
    private BlockBox loadingBox(final ConstructionSite site, final SiteRuntime rt) {
        final BlockBox box = this.workBox(site, rt);
        if (box == null || rt.box() == null) {
            return box; // roads and bridges: only their own cells
        }
        return wideLoadingBox(site) ? TerrainFootprint.building(box) : TerrainFootprint.expand(box, NEAR_MARGIN);
    }

    /**
     * Blocks around the plot touched outside the earthworks phases: front spot up to +4 (even-sized plots, see
     * {@code frontSpot}), stand spots +2 around scaffolding (+1), plot sweep +3.
     */
    static final int NEAR_MARGIN = 4;

    static boolean wideLoadingBox(final ConstructionSite site) {
        return site.is(ConstructionSite.RESTORE)
            || site.is(ConstructionSite.BUILD) && (site.phase == Phase.TERRAIN.ordinal() || site.phase == Phase.ENTRANCE.ordinal());
    }

    private void tickSite(final ConstructionSite site) {
        final SiteRuntime rt = this.runtime(site);
        if (rt == null) {
            this.plugin.getLogger().warning("Brak szablonu " + site.type + " — anuluję budowę " + site.id + " ze zwrotem.");
            this.cancel(site, 1.0, "site-cancelled");
            return;
        }
        final World w = Bukkit.getWorld(site.world);
        if (w == null) {
            return;
        }
        final BlockBox box = this.workBox(site, rt);
        if (box == null) {
            this.finish(site, rt, w);
            return;
        }
        final boolean loaded = chunksLoaded(w, this.loadingChunks(site, rt));
        final boolean day = this.cfg().workTime(w.getTime()) || site.is(ConstructionSite.RESTORE);
        // discoveries (Postep "tempo-budowy", percent) speed the builders up
        final double speed = Math.max(0.1, 1 + this.plugin.postep().effect(site.countryId, "tempo-budowy") / 100.0);
        final double perTick = this.cfg().blocksPerSecond * this.builderCount(rt) * PERIOD / 20.0 * speed;
        // On a loaded site the credit only paces the builders: it never piles up while they walk, so blocks are never
        // placed "by magic" in a burst after a walk. Only offline progress may exceed a few periods (catch-up).
        site.credit = pl.medivalrpg.ludnosc.logic.SitePacing.accrue(site.credit, perTick, day, loaded, this.cfg().offlineProgress,
            rt.waitingMaterial != null, 4096);
        if (!loaded) {
            rt.status = "chunki placu niewczytane — budowa nadrobi po wczytaniu";
            return;
        }
        if (!site.validated && rt.tpl != null && site.is(ConstructionSite.BUILD)) {
            final String obstruction = this.terrainObstruction(site.countryId, w, rt.placement);
            if (obstruction != null) {
                if (pl.medivalrpg.ludnosc.logic.DemolitionRules.SYSTEM.contains(site.orderedBy) && rt.box() != null) {
                    // the planner must not order the same plot again after the refund (Rzym: 150 orders in 3 days)
                    this.plugin.settlement().avoid(site.countryId, site.type, rt.box(), obstruction);
                }
                this.cancel(site, 1.0, "site-obstructed");
                return;
            }
            site.validated = true;
            this.data().markDirty();
        }
        // keep the site dry: dam the water around it and remove water from it (also repairs flooded sites)
        if (rt.dryTicks < 0 || ++rt.dryTicks >= DRY_EVERY) {
            rt.dryTicks = 0;
            final int n = this.keepDry(site, rt, w);
            if (n > 0) {
                this.plugin.getLogger().info("Plac budowy " + this.displayPlain(site.type) + " (" + site.originX + " " + site.originY
                    + " " + site.originZ + "): osuszono / uszczelniono " + n + " bl.");
                this.data().markDirty();
            }
        }
        if ((site.is(ConstructionSite.REPAIR) || site.is(ConstructionSite.UPGRADE)) && this.besieged(box)) {
            rt.status = "oblężenie — naprawa czeka";
            this.updateHologram(site, rt, w, box, null);
            return; // repairs wait for the end of a siege
        }
        final Location front = this.frontSpot(site, rt, w, box);
        rt.front = front;
        rt.site = site;
        if (!site.is(ConstructionSite.RESTORE) || this.cfg().keepLoaded) {
            rt.crew.ensure(this.plugin, site.id, front, this.builderCount(rt), this.displayPlain(site.type));
        }
        if (!day) {
            rt.status = "noc — budowniczowie odpoczywają przy ognisku";
            this.night(site, rt, w, front);
            this.updateHologram(site, rt, w, box, "night");
            return;
        }
        if (rt.night || site.campfire != null) {
            this.morning(site, rt, w); // also a campfire left from a night before a restart
        }
        if (this.needsMaterials(site, rt) && site.storage == null) {
            this.placeStorage(site, w, front);
        }
        final boolean catchUp = pl.medivalrpg.ludnosc.logic.SitePacing.catchUp(site.credit, perTick);
        if (!catchUp) {
            this.organiseCrew(site, rt, w);
        }
        final int countryBudget = this.countryLeft.getOrDefault(site.countryId, this.cfg().blocksPerTickCountry);
        final int budget = (int) Math.min(Math.min(Math.floor(site.credit), this.cfg().maxBlocksPerTick),
            Math.min(countryBudget, this.serverLeft));
        final int placed = budget <= 0 ? 0 : this.work(site, rt, w, budget, catchUp);
        if (placed > 0 && rt.waitingMaterial == null) {
            rt.status = null;
        }
        this.serverLeft -= placed;
        this.countryLeft.put(site.countryId, countryBudget - placed);
        site.credit = Math.max(0, site.credit - placed);
        if (placed > 0) {
            this.data().markDirty();
        }
        if (site.phase >= Phase.FINISH.ordinal() && !site.is(ConstructionSite.RESTORE)
            || site.is(ConstructionSite.RESTORE) && site.phase > Phase.RESTORE_TERRAIN.ordinal()) {
            this.finish(site, rt, w);
            return;
        }
        if (this.ticks % 4 == 0) {
            this.pushOutLiving(w, box, front);
        }
        this.updateHologram(site, rt, w, box, rt.waitingMaterial != null ? "material" : null);
    }

    // ------------------------------------------------------------------ water

    /** Service ticks (of {@link #PERIOD}) between two dry checks of a loaded site (10 s). */
    static final int DRY_EVERY = 20;
    /** Margin around the footprint kept dry and free of flowing liquids. */
    public static final int DRY_MARGIN = 3;

    private static boolean waterBuilding(final SiteRuntime rt) {
        return rt.tpl != null && "water".equals(rt.tpl.template().meta().zone());
    }

    /** Region kept dry for a site, or null (no template, a harbour/bridge that stands in water, a clean-up). */
    private Drainage.Region dryRegion(final ConstructionSite site, final SiteRuntime rt) {
        if (rt.tpl == null || rt.placement == null || waterBuilding(rt) || site.is(ConstructionSite.RESTORE)
            || site.is(ConstructionSite.DEMOLISH)) {
            return null;
        }
        final BlockBox b = rt.box();
        return Drainage.Region.of(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), DRY_MARGIN, rt.placement.groundY());
    }

    /** Block classification for the drainage plan. */
    static int liquidKind(final Block b) {
        if (b.isLiquid()) {
            return Drainage.LIQUID;
        }
        final Material m = b.getType();
        if (m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.KELP || m == Material.KELP_PLANT
            || m == Material.BUBBLE_COLUMN) {
            return Drainage.LIQUID;
        }
        if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) {
            return Drainage.WATERLOGGED;
        }
        return m.isAir() || b.isPassable() ? Drainage.OPEN : Drainage.SOLID;
    }

    /** Dams the liquid around the site and removes it from the site; returns the number of changed blocks. */
    int keepDry(final ConstructionSite site, final SiteRuntime rt, final World w) {
        final Drainage.Region r = this.dryRegion(site, rt);
        if (r == null) {
            return 0;
        }
        for (int cx = (r.minX() - 1) >> 4; cx <= (r.maxX() + 1) >> 4; cx++) {
            for (int cz = (r.minZ() - 1) >> 4; cz <= (r.maxZ() + 1) >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) {
                    return 0;
                }
            }
        }
        final BuildingTemplate t = rt.tpl.template();
        final Placement p = rt.placement;
        final List<Drainage.Op> ops = Drainage.plan((x, y, z) -> liquidKind(w.getBlockAt(x, y, z)), r, (x, y, z) -> {
            final int lx = x - p.minX();
            final int ly = y - p.minY();
            final int lz = z - p.minZ();
            if (!t.inside(lx, ly, lz) || t.state(lx, ly, lz) == null) {
                return false;
            }
            final String s = t.state(lx, ly, lz);
            final String id = s.replaceAll("\\[.*", "");
            return id.equals("minecraft:water") || id.equals("minecraft:lava") || s.contains("waterlogged=true");
        });
        Block last = null;
        int changes = 0;
        final List<BlockBox> protectedTerrain = this.terrainProtection(site);
        for (final Drainage.Op op : ops) {
            if (!this.mayGrade(site, rt, op.x(), op.z(), protectedTerrain)) continue;
            final Block b = w.getBlockAt(op.x(), op.y(), op.z());
            switch (op.type()) {
                case DAM -> b.setType(liquidKind(b.getRelative(org.bukkit.block.BlockFace.UP)) == Drainage.LIQUID
                    ? Material.STONE : Material.DIRT, false);
                case FILL -> b.setType(Material.DIRT, false);
                case DRY -> {
                    if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) {
                        wl.setWaterlogged(false);
                        b.setBlockData(wl, false);
                    } else {
                        b.setType(Material.AIR, false);
                    }
                }
            }
            last = b;
            changes++;
        }
        if (last != null && this.cfg().effects) {
            w.playSound(last.getLocation(), Sound.ITEM_BUCKET_FILL, 0.8f, 1f);
        }
        return changes;
    }

    /** Blocks still allowed in this construction tick: server-wide and per country. */
    private int serverLeft;
    private final Map<UUID, Integer> countryLeft = new HashMap<>();

    /** Areas liquids must not flow into: active sites (+ margin), mines being dug, underground works. */
    private volatile FluidGuard guard = new FluidGuard();

    private void rebuildGuard() {
        final FluidGuard g = new FluidGuard();
        for (final ConstructionSite site : this.data().allSites()) {
            final SiteRuntime rt = this.runtimes.get(site.id);
            if (rt == null) {
                continue;
            }
            final Drainage.Region r = this.dryRegion(site, rt);
            if (r != null) {
                g.add(new BlockBox(site.world, r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY() + 2, r.maxZ()));
            }
        }
        this.plugin.mines().guardBoxes().forEach(g::add);
        this.plugin.underground().guardBoxes().forEach(g::add);
        this.guard = g;
    }

    /** {@code BlockFromToEvent}: a liquid may not flow into this block. */
    public boolean liquidBlocked(final String world, final int x, final int y, final int z) {
        return this.guard.guarded(world, x, y, z);
    }

    // ------------------------------------------------------------------ the work

    /** One placement without material (earthworks, demolition, clean-up). */
    private boolean reach(final SiteRuntime rt, final World w, final Block target, final boolean catchUp) {
        return this.reach(rt, w, target, catchUp, false);
    }

    /**
     * Who places the next block. Any builder within reach who is not fetching material (and, for a block of
     * material, still carries some) does it — the one who waited longest. Otherwise the nearest free builder walks
     * to a spot next to the block (a builder with empty hands first goes to the material pile); after
     * {@link #STUCK_HOP} service ticks of walking he hops there. Returns false while somebody walks.
     */
    private boolean reach(final SiteRuntime rt, final World w, final Block target, final boolean catchUp, final boolean carries) {
        final Location center = target.getLocation().add(0.5, 0.5, 0.5);
        if (catchUp || rt.crew.bodies.isEmpty()) {
            rt.crew.active = rt.crew.bodies.isEmpty() ? null : rt.crew.nearest(center);
            return true;
        }
        final boolean needsLoad = carries && this.cfg().carryLoad > 0;
        final BuilderCrew.Body ready = rt.crew.ready(center, this.cfg().reach, needsLoad);
        if (ready != null) {
            ready.look = center;
            ready.stuck = 0;
            rt.crew.active = ready;
            return true;
        }
        BuilderCrew.Body walker = null;
        double best = Double.MAX_VALUE;
        for (final BuilderCrew.Body b : rt.crew.bodies) {
            if (b.fetching || !b.pos.getWorld().equals(w)) {
                continue;
            }
            final double d = b.pos.distanceSquared(center);
            if (d < best) {
                best = d;
                walker = b;
            }
        }
        if (walker == null) {
            return false; // everybody is at the material pile right now
        }
        if (needsLoad && walker.carry <= 0) {
            this.sendToPile(rt, w, walker);
            return false;
        }
        if (walker.target == null || walker.target.distanceSquared(center) > this.cfg().reach * this.cfg().reach) {
            walker.target = WorldOps.standSpot(w, target, walker.pos);
        }
        walker.look = center;
        if (++walker.stuck > STUCK_HOP) { // no way there: hop to the work spot rather than stand idle
            walker.pos = walker.target.clone();
            final Entity e = walker.entity();
            if (e != null) {
                e.teleport(walker.pos);
            }
            walker.stuck = 0;
            rt.crew.active = walker;
            return true;
        }
        return false;
    }

    /** The material pile of a site: its store chest, or the spot in front of the plot. */
    private Location pile(final SiteRuntime rt, final World w) {
        final ConstructionSite site = rt.site;
        if (site != null && site.storage != null && site.storage.world().equals(w.getName())) {
            return new Location(w, site.storage.x() + 0.5, site.storage.y() + 0.5, site.storage.z() + 0.5);
        }
        return rt.front;
    }

    private void sendToPile(final SiteRuntime rt, final World w, final BuilderCrew.Body b) {
        final Location pile = this.pile(rt, w);
        if (pile == null) {
            b.carry = this.cfg().carryLoad; // nowhere to fetch from: carry on
            return;
        }
        b.fetching = true;
        b.stuck = 0;
        b.target = WorldOps.standSpot(w, pile.getBlock(), b.pos);
        b.look = pile;
    }

    /**
     * Every service tick: builders back at the pile pick up a new load; idle builders walk ahead to the blocks that
     * come next (spread along the wall), so the whole crew is seen working instead of one walking while others stand.
     */
    private void organiseCrew(final ConstructionSite site, final SiteRuntime rt, final World w) {
        rt.site = site;
        for (final BuilderCrew.Body b : rt.crew.bodies) {
            if (!b.fetching) {
                continue;
            }
            if (b.target == null || b.pos.distanceSquared(b.target) <= 2.5 * 2.5) {
                b.fetching = false;
                b.stuck = 0;
                b.carry = this.cfg().carryLoad;
                rt.crew.swing(b);
                final Location pile = this.pile(rt, w);
                if (this.cfg().effects && pile != null) {
                    w.playSound(pile, site.storage != null ? Sound.BLOCK_CHEST_OPEN : Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.5f, 1f);
                }
                final Material next = this.upcomingMaterial(site, rt);
                if (next != null) {
                    b.heldAt = 0;
                    rt.crew.hold(b, next);
                }
            } else if (++b.stuck > STUCK_HOP * 2) {
                b.pos = b.target.clone();
                final Entity e = b.entity();
                if (e != null) {
                    e.teleport(b.pos);
                }
                b.stuck = 0;
            }
        }
        if (this.ticks % 4 != 0 || rt.crew.bodies.size() < 2) {
            return;
        }
        int k = 1;
        for (final BuilderCrew.Body b : rt.crew.bodies) {
            if (b.fetching || b == rt.crew.active || b.stuck > 0 || this.ticks - b.lastWork < 6) {
                continue;
            }
            final Block ahead = this.upcoming(site, rt, w, k++ * 6);
            if (ahead == null) {
                break;
            }
            final Location c = ahead.getLocation().add(0.5, 0.5, 0.5);
            if (b.target == null || b.target.distanceSquared(c) > 9) {
                b.target = WorldOps.standSpot(w, ahead, b.pos);
                b.look = c;
            }
        }
    }

    /** A block of the current phase {@code ahead} steps after the cursor (null at the end / outside the template). */
    private Block upcoming(final ConstructionSite site, final SiteRuntime rt, final World w, final int ahead) {
        final int i = site.cursor + ahead;
        final Phase phase = Phase.values()[Math.min(site.phase, Phase.values().length - 1)];
        if (phase == Phase.TERRAIN && site.terrain != null && i < site.terrain.size()) {
            final int[] col = site.terrain.get(i);
            return w.isChunkLoaded(col[0] >> 4, col[1] >> 4) ? w.getBlockAt(col[0], Math.max(col[2], col[3]), col[1]) : null;
        }
        if (rt.tpl == null || rt.placement == null) {
            return null;
        }
        if (phase == Phase.FOUNDATION && i < rt.tpl.foundation().size()) {
            final int[] col = rt.tpl.foundation().get(i);
            return w.getBlockAt(rt.placement.minX() + col[0], rt.placement.minY() - 1, rt.placement.minZ() + col[1]);
        }
        if (phase == Phase.CELLS && i < rt.tpl.order().length) {
            final int idx = site.is(ConstructionSite.DEMOLISH) ? rt.tpl.order()[rt.tpl.order().length - 1 - i] : rt.tpl.order()[i];
            final int x = rt.placement.x(idx);
            final int z = rt.placement.z(idx);
            return w.isChunkLoaded(x >> 4, z >> 4) ? w.getBlockAt(x, rt.placement.y(idx), z) : null;
        }
        return null;
    }

    /** Material of the next solid block to place (what a builder carries from the pile). */
    private Material upcomingMaterial(final ConstructionSite site, final SiteRuntime rt) {
        if (rt.tpl == null) {
            return null;
        }
        if (site.phase == Phase.FOUNDATION.ordinal()) {
            return rt.tpl.foundationBlock().getMaterial();
        }
        if (site.phase != Phase.CELLS.ordinal()) {
            return null;
        }
        for (int i = site.cursor; i < Math.min(rt.tpl.order().length, site.cursor + 64); i++) {
            final BlockData d = rt.tpl.data()[rt.tpl.order()[i]];
            if (d != null && !d.getMaterial().isAir() && d.getMaterial().isItem()) {
                return d.getMaterial();
            }
        }
        return null;
    }

    private void placed(final SiteRuntime rt, final World w, final Block b, final BlockData d, final int[] sounds) {
        final BuilderCrew.Body body = rt.crew.active != null && rt.crew.bodies.contains(rt.crew.active) ? rt.crew.active
            : rt.crew.nextBody();
        if (body != null) {
            body.lastWork = this.ticks;
            if (body.carry > 0 && !d.getMaterial().isAir()) {
                body.carry--;
            }
        }
        rt.crew.hold(body, d.getMaterial());
        rt.crew.swing(body);
        if (this.cfg().effects && !d.getMaterial().isAir() && sounds[0] < 2 && this.random.nextInt(2) == 0) {
            sounds[0]++;
            final Location c = b.getLocation().add(0.5, 0.5, 0.5);
            w.playSound(c, d.getSoundGroup().getPlaceSound(), 0.6f, 0.9f + this.random.nextFloat() * 0.2f);
            w.spawnParticle(Particle.BLOCK, c, 6, 0.3, 0.3, 0.3, 0, d);
        }
    }

    /** Places up to {@code budget} blocks of the current phase(s); returns the number placed. */
    private int work(final ConstructionSite site, final SiteRuntime rt, final World w, final int budget, final boolean catchUp) {
        if (TerrainFootprint.refreshPending(site)) this.data().markDirty();
        final List<BlockBox> protectedTerrain = site.phase == Phase.TERRAIN.ordinal()
            ? this.terrainProtection(site) : List.of();
        List<BlockBox> restoreGuard = null; // built lazily: the phase may change inside the loop
        int placed = 0;
        int checks = 0;
        final int[] sounds = {0};
        while (placed < budget && checks < MAX_CHECKS_PER_TICK) {
            checks++;
            final Phase phase = Phase.values()[Math.min(site.phase, Phase.values().length - 1)];
            switch (phase) {
                case TERRAIN -> {
                    if (site.terrain == null) {
                        site.terrain = this.planTerrain(site, rt, w);
                    }
                    if (site.cursor >= site.terrain.size()) {
                        this.nextPhase(site);
                        continue;
                    }
                    final int[] col = site.terrain.get(site.cursor);
                    final Block top = w.getBlockAt(col[0], Math.max(col[2], col[3]), col[1]);
                    final CountryState lst = site.is(ConstructionSite.LEVEL) ? this.data().state(site.countryId) : null;
                    if (!this.mayGrade(site, rt, col[0], col[1], protectedTerrain)) {
                        site.cursor++; // a building, site or queued plot came next to it meanwhile: never dig under it
                        continue;
                    }
                    if (lst != null && !(lst.planActive && lst.planForced)) {
                        // large-scale levelling: a daily limit per country (no razing of the world in an hour)
                        final long day = System.currentTimeMillis() / 86_400_000L;
                        if (lst.levelDay != day) {
                            lst.levelDay = day;
                            lst.levelBlocks = 0;
                        }
                        if (lst.levelBlocks >= this.cfg().redesignLevelBlocksPerDay) {
                            return placed;
                        }
                    }
                    // players' countries: the (rare) face is earth with grass on top; NPC: a low stone wall; no fences
                    final boolean earth = !this.plugin.automaticBuildingAllowed(site.countryId);
                    if (this.terraformColumn(w, col, lst != null, false, earth) == 0) {
                        site.cursor++; // already at the planned level: nobody walks there for nothing
                        continue;
                    }
                    if (!this.reach(rt, w, top, catchUp)) {
                        return placed;
                    }
                    final int n = this.terraformColumn(w, col, lst != null, true, earth);
                    if (lst != null) {
                        lst.levelBlocks += n;
                    }
                    if (n > 0) {
                        this.placed(rt, w, top, top.getBlockData(), sounds);
                    }
                    placed += Math.max(n > 0 ? 1 : 0, n / 3);
                    site.cursor++;
                }
                case FOUNDATION -> {
                    if (rt.tpl == null || site.cursor >= rt.tpl.foundation().size()) {
                        this.nextPhase(site);
                        continue;
                    }
                    final int[] col = rt.tpl.foundation().get(site.cursor);
                    final int wx = rt.placement.minX() + col[0];
                    final int wz = rt.placement.minZ() + col[1];
                    final int topY = rt.placement.minY() - 1;
                    int bottom = topY + 1;
                    for (int y = topY; y > topY - this.cfg().maxFoundationDepth && y >= w.getMinHeight(); y--) {
                        final Block b = w.getBlockAt(wx, y, wz);
                        if (!(b.isEmpty() || b.isLiquid() || b.isReplaceable() || Tag.LEAVES.isTagged(b.getType()))) {
                            break;
                        }
                        bottom = y;
                    }
                    if (bottom <= topY && !this.reach(rt, w, w.getBlockAt(wx, topY, wz), catchUp, true)) {
                        return placed; // a builder walks to the foundation trench first
                    }
                    int y = bottom;
                    for (; y <= topY && placed < budget; y++) {
                        if (!this.consume(site, rt, rt.tpl.foundationBlock())) {
                            return placed;
                        }
                        final Block fb = w.getBlockAt(wx, y, wz);
                        fb.setBlockData(rt.tpl.foundationBlock(), false);
                        placed++;
                        if (y == topY) {
                            this.placed(rt, w, fb, rt.tpl.foundationBlock(), sounds);
                        }
                    }
                    if (y > topY) {
                        site.cursor++;
                    } else {
                        return placed;
                    }
                }
                case CELLS -> {
                    if (rt.tpl == null || site.cursor >= rt.tpl.order().length) {
                        this.nextPhase(site);
                        continue;
                    }
                    if (site.is(ConstructionSite.DEMOLISH)) {
                        // from the roof down: the build order backwards
                        final int idx = rt.tpl.order()[rt.tpl.order().length - 1 - site.cursor];
                        site.cursor++;
                        if (rt.tpl.data()[idx] == null || rt.tpl.data()[idx].getMaterial().isAir()) {
                            continue;
                        }
                        final Block b = w.getBlockAt(rt.placement.x(idx), rt.placement.y(idx), rt.placement.z(idx));
                        if (b.getType().isAir() || b.isLiquid()) {
                            continue;
                        }
                        if (!this.reach(rt, w, b, catchUp)) {
                            site.cursor--;
                            return placed;
                        }
                        final BlockData before = b.getBlockData();
                        if (b.getState() instanceof org.bukkit.block.Container c) {
                            c.getInventory().clear(); // no item spill from chests of the old house
                        }
                        this.plugin.resources().salvage(site.countryId, b.getType());
                        b.setType(Material.AIR, false);
                        this.placed(rt, w, b, before, sounds);
                        placed++;
                        continue;
                    }
                    final int idx = rt.tpl.order()[site.cursor];
                    final BlockData d = rt.tpl.data()[idx];
                    if (d == null) {
                        site.cursor++;
                        continue;
                    }
                    if (site.is(ConstructionSite.UPGRADE) && (rt.slots == null || !rt.slots[idx])) {
                        site.cursor++;
                        continue;
                    }
                    final Block b = w.getBlockAt(rt.placement.x(idx), rt.placement.y(idx), rt.placement.z(idx));
                    final BlockData current = b.getBlockData();
                    if (current.equals(d) || site.is(ConstructionSite.REPAIR)
                        && RepairRules.equivalent(rt.tpl.template().state(idx), current.getAsString())) {
                        site.cursor++;
                        continue;
                    }
                    if (site.is(ConstructionSite.REPAIR) && d.getMaterial().isAir()) {
                        site.cursor++; // repairs never clear things away
                        continue;
                    }
                    if (!this.reach(rt, w, b, catchUp, !d.getMaterial().isAir())) {
                        return placed;
                    }
                    if (!this.consume(site, rt, d)) {
                        return placed;
                    }
                    this.scaffoldFor(site, rt, w, idx);
                    b.setBlockData(d, false);
                    site.scaffolding().removeIf(p -> p.x() == b.getX() && p.y() == b.getY() && p.z() == b.getZ());
                    this.placed(rt, w, b, d, sounds);
                    placed++;
                    site.cursor++;
                }
                case ENTRANCE -> {
                    if (rt.entrance == null) {
                        rt.entrance = this.planEntrance(site, rt, w);
                    }
                    if (site.cursor >= rt.entrance.size()) {
                        this.nextPhase(site);
                        continue;
                    }
                    final RoadDecor.Block e = rt.entrance.get(site.cursor);
                    final Block b = w.getBlockAt(e.x(), e.y(), e.z());
                    final BlockData d = Bukkit.createBlockData(e.state());
                    if (!b.getBlockData().equals(d) && (WorldOps.soft(b) || WorldOps.ground(b.getType()))) {
                        if (!this.reach(rt, w, b, catchUp, true)) {
                            return placed;
                        }
                        if (!this.consume(site, rt, d)) {
                            return placed;
                        }
                        b.setBlockData(d, false);
                        this.placed(rt, w, b, d, sounds);
                        placed++;
                    }
                    site.cursor++;
                }
                case ROAD -> {
                    if (!this.cfg().roads && !site.is(ConstructionSite.ROAD)) {
                        this.nextPhase(site);
                        continue;
                    }
                    if (rt.road == null) {
                        rt.road = this.planRoad(site, w);
                    }
                    if (site.cursor >= rt.road.size()) {
                        this.nextPhase(site);
                        continue;
                    }
                    final RoadDecor.Block r = rt.road.get(site.cursor);
                    if (!w.isChunkLoaded(r.x() >> 4, r.z() >> 4)) {
                        return placed;
                    }
                    final Block b = w.getBlockAt(r.x(), r.y(), r.z());
                    final BlockData d = Bukkit.createBlockData(r.state());
                    // ground / old road surface is replaced (resurfacing in a new epoch), posts, lamps and walls go into air
                    final boolean surface = WorldOps.ground(b.getType()) || WorldOps.roadSurface(b.getType());
                    final boolean allowed = WALL_REMOVE_TYPE.equals(site.type) ? WorldOps.wallBlock(b.getType())
                        : surface || b.isEmpty() || WorldOps.soft(b);
                    final boolean bridgeOwned = !BRIDGE_TYPE.equals(site.type)
                        || this.plugin.kraje().getCountryAt(site.world, r.x() >> 4, r.z() >> 4).map(k -> site.countryId.equals(k.id())).orElse(false)
                            && !this.plugin.design().untouchable(site.countryId, r.x(), r.z());
                    if (allowed && bridgeOwned && !b.getBlockData().equals(d) && this.plugin.data().buildingAt(site.world, r.x(), r.y(), r.z()) == null) {
                        if (!this.reach(rt, w, b, catchUp, true)) {
                            return placed;
                        }
                        b.setBlockData(d, false);
                        if (surface) {
                            final Block above = b.getRelative(0, 1, 0);
                            if (WorldOps.soft(above) && !above.isEmpty()) {
                                above.setType(Material.AIR, false);
                            }
                        }
                        this.placed(rt, w, b, d, sounds);
                        placed++;
                    }
                    site.cursor++;
                }
                case FINISH -> {
                    return placed;
                }
                case RESTORE_CELLS -> {
                    if (rt.tpl == null || site.cursor >= rt.tpl.order().length) {
                        this.nextPhase(site);
                        continue;
                    }
                    // reverse order: decoration first, foundation last
                    final int idx = rt.tpl.order()[rt.tpl.order().length - 1 - site.cursor];
                    final BlockData d = rt.tpl.data()[idx];
                    site.cursor++;
                    if (d == null || d.getMaterial().isAir() || this.naturalTerrain(d.getMaterial())) {
                        continue;
                    }
                    final Block b = w.getBlockAt(rt.placement.x(idx), rt.placement.y(idx), rt.placement.z(idx));
                    if (b.getType() == d.getMaterial()) {
                        if (!this.reach(rt, w, b, catchUp)) {
                            site.cursor--;
                            return placed;
                        }
                        b.setType(Material.AIR, false);
                        this.placed(rt, w, b, Material.AIR.createBlockData(), sounds);
                        placed++;
                    }
                }
                case RESTORE_TERRAIN -> {
                    if (site.terrain == null || site.cursor >= site.terrain.size()) {
                        site.phase = Phase.RESTORE_TERRAIN.ordinal() + 1;
                        return placed;
                    }
                    final int[] col = site.terrain.get(site.terrain.size() - 1 - site.cursor);
                    site.cursor++;
                    if (restoreGuard == null) {
                        restoreGuard = this.terrainProtection(site);
                    }
                    // the earthworks band is not reserved: a neighbour (or foreign land) may be there by now
                    if (!this.mayGrade(site, rt, col[0], col[1], restoreGuard)) {
                        continue;
                    }
                    this.restoreColumn(w, col);
                    placed++;
                }
                default -> {
                    return placed;
                }
            }
        }
        return placed;
    }

    private void nextPhase(final ConstructionSite site) {
        site.phase++;
        site.cursor = 0;
        if ((site.is(ConstructionSite.REPAIR) || site.is(ConstructionSite.UPGRADE) || site.is(ConstructionSite.DEMOLISH))
            && site.phase > Phase.CELLS.ordinal()) {
            site.phase = Phase.FINISH.ordinal();
        }
        if (site.is(ConstructionSite.ROAD) && site.phase > Phase.ROAD.ordinal()) {
            site.phase = Phase.FINISH.ordinal();
        }
        if (site.is(ConstructionSite.LEVEL) && site.phase > Phase.TERRAIN.ordinal()) {
            site.phase = Phase.FINISH.ordinal();
        }
        if (!this.cfg().entranceStairs && site.phase == Phase.ENTRANCE.ordinal()) {
            site.phase++;
        }
        this.data().markDirty();
    }

    // ------------------------------------------------------------------ terrain

    private List<int[]> planTerrain(final ConstructionSite site, final SiteRuntime rt, final World w) {
        final List<int[]> out = new ArrayList<>();
        if (rt.tpl == null || !site.is(ConstructionSite.BUILD)) {
            return out;
        }
        final BlockBox box = rt.box();
        if (!chunksLoaded(w, TerrainFootprint.building(box))) {
            throw new IllegalStateException("Earthworks domain must be loaded before planning");
        }
        final int floorY = rt.placement.groundY();
        final Map<Long, Integer> heights = new HashMap<>();
        final List<BlockBox> protectedTerrain = this.terrainProtection(site);
        // natural embankments (decision 2026-11-03): slopes of at most 1:1 that keep clear of protected / foreign columns;
        // a face only where the slope cannot fit (counted in the log)
        final Terraform.Plan plan = Terraform.plan((x, z) -> {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                return TerrainView.UNKNOWN;
            }
            return heights.computeIfAbsent(RoadPlanner.key(x, z), k -> WorldOps.topSolid(w, x, z, floorY + 24, floorY - 24));
        }, (x, z) -> this.mayGrade(site, rt, x, z, protectedTerrain), box.minX(), box.minZ(), box.maxX(), box.maxZ(), floorY,
            LifeService.HUT_ORDERER.equals(site.orderedBy) && site.createdAt >= this.policyStartedAt
                ? new Terraform.Settings(Math.max(0, Math.min(2, this.plugin.getConfig().getInt("courtesy.hut-terrain-margin", 1))),
                    Math.max(1, Math.min(6, this.plugin.getConfig().getInt("courtesy.hut-terrain-ramp", 2))), 2)
                : new Terraform.Settings(TerrainFootprint.MARGIN, TerrainFootprint.RAMP, 3), true);
        if (plan.wallColumns() > 0) {
            this.plugin.getLogger().info("Niwelacja " + this.displayPlain(site.type) + " (" + site.originX + " " + site.originY + " "
                + site.originZ + "): skarpa nie mieści się w " + plan.wallColumns() + " kolumnach — niski "
                + (this.plugin.automaticBuildingAllowed(site.countryId) ? "mur" : "uskok z ziemi") + " bez płotka");
        }
        for (final Terraform.Column c : plan.columns()) {
            if (!this.mayGrade(site, rt, c.x(), c.z(), protectedTerrain)) continue;
            if (c.changed() || c.plot()) {
                out.add(new int[] {c.x(), c.z(), c.oldTop(), c.newTop(), c.wall() ? c.wallBottom() : Integer.MAX_VALUE,
                    c.wall() ? c.wallTop() : Integer.MIN_VALUE, c.plot() ? 1 : 0});
            }
        }
        this.data().markDirty();
        return out;
    }

    /**
     * Levels one column: clears vegetation/trees above, cuts or fills, builds the retaining wall part. {@code town}: the
     * grading of a redesigned town — old street surface, slabs, lamp posts and leftovers of demolished buildings in the
     * column are cleared as well (they would float or stand in a pit otherwise); over a quarter (plot columns) every
     * non-natural block up to {@value #TOWN_CLEAR} blocks above the new ground goes (containers excepted).
     */
    private int terraformColumn(final World w, final int[] col, final boolean town) {
        return this.terraformColumn(w, col, town, true);
    }

    private int terraformColumn(final World w, final int[] col, final boolean town, final boolean apply) {
        return this.terraformColumn(w, col, town, apply, false);
    }

    /**
     * {@code apply=false}: only tells whether the column needs any change (returns 1 at the first one), nothing is set.
     * {@code earth}: the (last-resort) face is built of the surface material (grass on top, dirt below) — players read
     * stone-brick walls with fences around every building on a slope as walls "spawning" in their town.
     */
    private int terraformColumn(final World w, final int[] col, final boolean town, final boolean apply, final boolean earth) {
        final int x = col[0];
        final int z = col[1];
        final int oldTop = col[2];
        final int newTop = col[3];
        final int wallBottom = col[4];
        final int wallTop = col[5];
        final boolean plot = col[6] == 1;
        int changes = 0;
        // surface family from the current top block (grass/dirt, sand, stone)
        final int curTop = WorldOps.topSolid(w, x, z, Math.max(oldTop, newTop) + 2, Math.min(oldTop, newTop) - 8);
        Material surface = curTop == TerrainView.UNKNOWN ? Material.GRASS_BLOCK : w.getBlockAt(x, curTop, z).getType();
        if (surface.hasGravity() && nearWater(w, x, Math.max(oldTop, newTop), z)) {
            surface = Material.GRASS_BLOCK; // slopes by the water from earth and stone: sand and gravel would slide
        }
        // cut: everything natural above the new top; over the plot also trees and leaves
        final int clearTo = Math.min(w.getMaxHeight()-1,
            Math.max(oldTop, newTop) + (town ? plot ? TOWN_CLEAR : 8 : plot ? 16 : 2));
        for (int y = newTop + 1; y <= clearTo; y++) {
            final Block b = w.getBlockAt(x, y, z);
            if (b.isEmpty()) {
                continue;
            }
            final Material m = b.getType();
            if (WorldOps.soft(b) || WorldOps.ground(m) || plot && (Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || b.isLiquid())
                || town && (WorldOps.townDecor(m) || plot && WorldOps.leftover(b))) {
                if (!apply) {
                    return 1;
                }
                b.setType(Material.AIR, false);
                changes++;
            }
        }
        // fill down from the new top until solid ground; wall blocks in the wall range
        for (int y = newTop; y > newTop - 32 && y >= w.getMinHeight(); y--) {
            final Block b = w.getBlockAt(x, y, z);
            if (y >= wallBottom && y <= wallTop) {
                final Material m = earth ? earthFace(surface, wallTop - y) : this.wallMaterial(x, y, z);
                if (b.getType() != m) {
                    if (!apply) {
                        return 1;
                    }
                    b.setType(m, false);
                    changes++;
                }
                continue;
            }
            final boolean open = b.isEmpty() || b.isLiquid() || WorldOps.soft(b) || town && WorldOps.townDecor(b.getType());
            if (!open) {
                if (y == newTop && Tag.DIRT.isTagged(b.getType()) && b.getType() != Material.GRASS_BLOCK
                    && WorldOps.fill(surface, 0) == Material.GRASS_BLOCK && b.getRelative(0, 1, 0).isEmpty()) {
                    if (!apply) {
                        return 1;
                    }
                    b.setType(Material.GRASS_BLOCK, false); // freshly exposed dirt turns green
                    changes++;
                }
                break;
            }
            if (!apply) {
                return 1;
            }
            b.setType(WorldOps.fill(surface, newTop - y), false);
            changes++;
        }
        // a retaining wall with a face of 3+ blocks gets a fence on top: villagers and players do not fall off
        if (!NATURAL_EMBANKMENTS && wallBottom <= wallTop && wallTop - wallBottom + 1 >= FALL_HEIGHT) {
            final Block top = w.getBlockAt(x, wallTop + 1, z);
            if (top.isEmpty() || WorldOps.soft(top)) {
                if (!apply) {
                    return 1;
                }
                top.setType(Material.SPRUCE_FENCE, false);
                WorldOps.fixConnections(top);
                for (final org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[] {org.bukkit.block.BlockFace.NORTH,
                    org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.WEST}) {
                    final Block n = top.getRelative(f);
                    if (Tag.FENCES.isTagged(n.getType())) {
                        WorldOps.fixConnections(n);
                    }
                }
                changes++;
            }
        }
        return changes;
    }

    /** Liquid within 2 blocks horizontally of a column, from its top down 4 blocks. */
    private static boolean nearWater(final World w, final int x, final int top, final int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) {
                    continue;
                }
                for (int y = top; y >= top - 4; y--) {
                    if (w.getBlockAt(x + dx, y, z + dz).isLiquid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Town grading over a quarter: leftovers are cleared up to this height above the new ground. */
    static final int TOWN_CLEAR = 32;

    /** Finished template floors can be approached; unknown structures and pending works retain a safety margin. */
    private List<BlockBox> terrainProtection(final ConstructionSite site) {
        final List<BlockBox> out = new ArrayList<>();
        for (final Building b : this.data().allBuildings()) {
            if (b.id().equals(site.repairOf)) continue;
            if (!site.world.equals(b.box().world())) continue;
            final int margin = b.registered() || this.plugin.templates().get(b.type()) == null ? 2 : 0;
            out.add(TerrainFootprint.expand(b.box(), margin));
        }
        for (final BlockBox box : this.reservations(site.id)) {
            if (box != null && site.world.equals(box.world())) out.add(TerrainFootprint.expand(box, 2));
        }
        // the clean-up of a cancelled neighbour (it restores that terrain) and a player's live preview are not graded away
        for (final ConstructionSite s : this.data().allSites()) {
            if (!s.id.equals(site.id) && s.is(ConstructionSite.RESTORE) && site.world.equals(s.world)) {
                final BlockBox box = this.footprint(s.type, s.world, s.originX, s.originY, s.originZ, s.rotation);
                if (box != null) out.add(TerrainFootprint.expand(box, 2));
            }
        }
        for (final BlockBox box : this.plugin.plans().previewBoxes()) {
            if (site.world.equals(box.world())) out.add(TerrainFootprint.expand(box, 2));
        }
        return out;
    }

    private boolean mayGrade(final ConstructionSite site, final SiteRuntime rt, final int x, final int z,
                             final List<BlockBox> protectedTerrain) {
        if (this.plugin.design().untouchable(site.countryId, x, z)
            || this.plugin.kraje().getCountryAt(site.world, x >> 4, z >> 4)
                .map(k -> !site.countryId.equals(k.id())).orElse(true)) return false;
        for (final BlockBox box : protectedTerrain) {
            if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) return false;
        }
        if (site.is(ConstructionSite.BUILD)) {
            final BlockBox plot = rt.box();
            final boolean outside = x < plot.minX() || x > plot.maxX() || z < plot.minZ() || z > plot.maxZ();
            final CountryState state = this.data().stateIfPresent(site.countryId);
            if (outside && state != null && state.roads().contains(RoadPlanner.key(x, z))) return false;
        }
        return true;
    }

    /** Drop (blocks) from which a fence was put on the edge of a retaining wall (before 2026-11-03). */
    static final int FALL_HEIGHT = 3;

    /** Natural embankments (owner's decision 2026-11-03): 1:1 slopes, faces only as a last resort, never fences. */
    static final boolean NATURAL_EMBANKMENTS = true;

    /** Earth embankment: the surface family, never a block that falls (sand, gravel → grass / dirt). */
    static Material earthFace(final Material surface, final int depth) {
        final Material m = WorldOps.fill(surface, depth);
        return m.hasGravity() ? (depth == 0 ? Material.GRASS_BLOCK : Material.DIRT) : m;
    }

    private Material wallMaterial(final int x, final int y, final int z) {
        final int h = Math.floorMod(x * 31 + y * 17 + z * 7, 10);
        return h < 6 ? Material.STONE_BRICKS : h < 8 ? Material.MOSSY_STONE_BRICKS : h < 9 ? Material.COBBLESTONE
            : Material.MOSSY_COBBLESTONE;
    }

    /** Undoes a terrain column (after a cancelled build): back to the old top height. */
    private void restoreColumn(final World w, final int[] col) {
        final int x = col[0];
        final int z = col[1];
        final int oldTop = col[2];
        final int newTop = Math.max(col[3], col[5] == Integer.MIN_VALUE ? col[3] : col[5]);
        if (col[5] != Integer.MIN_VALUE && w.getBlockAt(x, col[5] + 1, z).getType() == Material.SPRUCE_FENCE) {
            w.getBlockAt(x, col[5] + 1, z).setType(Material.AIR, false); // safety fence of the wall
        }
        for (int y = newTop; y > oldTop; y--) {
            final Block b = w.getBlockAt(x, y, z);
            if (WorldOps.ground(b.getType()) || b.getType().name().contains("STONE_BRICKS") || b.getType().name().contains("COBBLESTONE")) {
                b.setType(Material.AIR, false);
            }
        }
        for (int y = col[3] + 1; y <= oldTop; y++) {
            final Block b = w.getBlockAt(x, y, z);
            if (b.isEmpty() || WorldOps.soft(b)) {
                b.setType(y == oldTop ? Material.GRASS_BLOCK : Material.DIRT, false);
            }
        }
    }

    // ------------------------------------------------------------------ scaffolding, entrance, road

    /** Scaffolding column outside a high exterior wall cell (every third position), recorded in the site. */
    private void scaffoldFor(final ConstructionSite site, final SiteRuntime rt, final World w, final int idx) {
        if (!this.cfg().scaffolding || rt.phases == null || site.scaffolding().size() >= this.cfg().scaffoldingMaxBlocks) {
            return;
        }
        final BuildPhases.Phase ph = rt.phases[idx];
        if (ph != BuildPhases.Phase.WALL && ph != BuildPhases.Phase.FRAME) {
            return;
        }
        final BuildingTemplate t = rt.tpl.template();
        final int lx = t.xOf(idx);
        final int ly = t.yOf(idx);
        final int lz = t.zOf(idx);
        final int height = ly - t.meta().groundLayer();
        if (height < this.cfg().scaffoldingMinHeight || Math.floorMod(lx + lz, 3) != 0) {
            return;
        }
        for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            final int nx = lx + d[0];
            final int nz = lz + d[1];
            final String floor = t.state(nx, t.meta().groundLayer(), nz);
            final boolean outside = !t.inside(nx, ly, nz) || floor == null || BlockStates.isAir(floor);
            if (!outside) {
                continue;
            }
            final int wx = rt.placement.minX() + nx;
            final int wz = rt.placement.minZ() + nz;
            final int top = rt.placement.minY() + ly - 1;
            for (int y = rt.placement.groundY() + 1; y <= top; y++) {
                final Block b = w.getBlockAt(wx, y, wz);
                if (b.isEmpty()) {
                    b.setType(Material.SCAFFOLDING, false);
                    site.scaffolding().add(new BlockPos(site.world, wx, y, wz));
                }
            }
            return;
        }
    }

    private void removeScaffolding(final ConstructionSite site, final World w) {
        final List<BlockPos> list = new ArrayList<>(site.scaffolding());
        list.sort((a, b) -> Integer.compare(b.y(), a.y()));
        for (final BlockPos p : list) {
            if (w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                final Block b = w.getBlockAt(p.x(), p.y(), p.z());
                if (b.getType() == Material.SCAFFOLDING) {
                    b.setType(Material.AIR, false);
                }
            }
        }
        site.scaffolding().clear();
        // scaffolds knocked down by block updates fall next to the plot and were left behind (no longer in the list)
        if (site.is(ConstructionSite.BUILD) || site.is(ConstructionSite.REPAIR) || site.is(ConstructionSite.UPGRADE)) {
            final BlockBox fp = this.footprint(site.type, site.world, site.originX, site.originY, site.originZ, site.rotation);
            if (fp != null) {
                final BlockBox around = TerrainFootprint.expand(fp, NEAR_MARGIN);
                for (int x = around.minX(); x <= around.maxX(); x++) {
                    for (int z = around.minZ(); z <= around.maxZ(); z++) {
                        if (!w.isChunkLoaded(x >> 4, z >> 4) || this.plugin.data().buildingAt(site.world, x, fp.minY(), z) != null
                            && !fp.contains(site.world, x, fp.minY(), z)) {
                            continue; // never in a neighbouring registered building
                        }
                        for (int y = Math.max(w.getMinHeight(), fp.minY() - 2); y <= Math.min(w.getMaxHeight() - 1, fp.maxY() + 1); y++) {
                            final Block b = w.getBlockAt(x, y, z);
                            if (b.getType() == Material.SCAFFOLDING) {
                                b.setType(Material.AIR, false);
                            }
                        }
                    }
                }
            }
        }
    }

    private String stairsFor(final BlockData foundation) {
        final String id = foundation.getMaterial().name().toLowerCase(java.util.Locale.ROOT);
        String stairs = switch (id) {
            case "stone_bricks" -> "stone_brick_stairs";
            case "smooth_stone", "stone" -> "stone_stairs";
            case "polished_andesite" -> "polished_andesite_stairs";
            default -> id.endsWith("_planks") ? id.replace("_planks", "_stairs") : "cobblestone_stairs";
        };
        if (Material.matchMaterial(stairs) == null) {
            stairs = "cobblestone_stairs";
        }
        return "minecraft:" + stairs;
    }

    /** Steps from every exterior door down to the terrain (world heights now, after the building stands). */
    private List<RoadDecor.Block> planEntrance(final ConstructionSite site, final SiteRuntime rt, final World w) {
        final List<RoadDecor.Block> out = new ArrayList<>();
        if (rt.tpl == null || !site.is(ConstructionSite.BUILD)) {
            return out;
        }
        final BuildingTemplate t = rt.tpl.template();
        final String stairs = this.stairsFor(rt.tpl.foundationBlock());
        final String support = rt.tpl.foundationBlock().getAsString();
        for (final int[] door : t.exteriorDoors()) {
            final int doorY = rt.placement.minY() + door[1];
            final String facing = BlockStates.parse(t.state(door[0], door[1], door[2])).property("facing");
            // skip the building's own floor in front of the door (porches)
            int start = 1;
            while (t.inside(door[0] + start * door[3], door[1] - 1, door[2] + start * door[4])) {
                final String below = t.state(door[0] + start * door[3], door[1] - 1, door[2] + start * door[4]);
                if (below == null || BlockStates.isAir(below)) {
                    break;
                }
                start++;
            }
            final int sx = rt.placement.minX() + door[0] + (start - 1) * door[3];
            final int sz = rt.placement.minZ() + door[2] + (start - 1) * door[4];
            final List<Entrance.Step> plan = Entrance.plan(doorY, d -> {
                final int x = sx + d * door[3];
                final int z = sz + d * door[4];
                final int h = WorldOps.topSolid(w, x, z, doorY + 1, doorY - 16);
                return h == TerrainView.UNKNOWN ? doorY - 16 : h;
            }, this.cfg().maxEntranceSteps);
            for (final Entrance.Step s : plan) {
                final int x = sx + s.dist() * door[3];
                final int z = sz + s.dist() * door[4];
                final String state = switch (s.kind()) {
                    case STAIR -> stairs + "[facing=" + facing + ",half=bottom]";
                    case SUPPORT -> support;
                    case CLEAR -> BlockStates.AIR;
                };
                out.add(new RoadDecor.Block(x, s.y(), z, state));
            }
        }
        return out;
    }

    /** Type of road-kind sites that build a section of the city walls. */
    public static final String WALL_TYPE = "mur_miejski";
    public static final String BRIDGE_TYPE = "most_traktu";

    private List<RoadDecor.Block> planRoad(final ConstructionSite site, final World w) {
        if (site.road().isEmpty()) {
            return List.of();
        }
        if (BRIDGE_TYPE.equals(site.type)) {
            return pl.medivalrpg.ludnosc.logic.BridgePlan.blocks(site.road(), this.plugin.postep().era(site.countryId));
        }
        if (WALL_TYPE.equals(site.type)) {
            return this.planWall(site, w);
        }
        if (WALL_REMOVE_TYPE.equals(site.type)) {
            return this.planWallRemoval(site, w);
        }
        final List<int[]> path = new ArrayList<>();
        for (final BlockPos p : site.road()) {
            path.add(new int[] {p.x(), p.z()});
        }
        final int level = this.plugin.settlement().levelIndex(site.countryId);
        final int era = this.plugin.postep().era(site.countryId);
        final RoadDecor.Style style = RoadDecor.Style.of(era, level, this.cfg().pavedRoadLevel,
            this.plugin.postep().hasDiscovery(site.countryId, "samochod"));
        final Set<Long> onPath = new HashSet<>();
        for (final int[] p : path) {
            onPath.add(RoadPlanner.key(p[0], p[1]));
        }
        return RoadDecor.blocks(path, (x, z) -> {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                return TerrainView.UNKNOWN;
            }
            return WorldOps.topSolid(w, x, z, site.originY + 20, site.originY - 20);
        }, style, 8, (x, z) -> !onPath.contains(RoadPlanner.key(x, z)) && w.isChunkLoaded(x >> 4, z >> 4)
            && this.plugin.data().buildingAt(site.world, x, site.originY, z) == null && this.naturalTop(w, x, z, site.originY));
    }

    /** The top block of a column is natural ground or an old road (pavements and second lanes never cover buildings). */
    private boolean naturalTop(final World w, final int x, final int z, final int aroundY) {
        final int top = WorldOps.topSolid(w, x, z, aroundY + 20, aroundY - 20);
        if (top == TerrainView.UNKNOWN) {
            return false;
        }
        final Material m = w.getBlockAt(x, top, z).getType();
        final Material above = w.getBlockAt(x, top + 1, z).getType();
        return (TerrainCache.natural(m) || m == Material.COBBLESTONE || m == Material.STONE_BRICKS || m == Material.POLISHED_ANDESITE
            || m == Material.SMOOTH_STONE || m == Material.BLACK_CONCRETE) && (above.isAir() || Tag.REPLACEABLE.isTagged(above)
            || Tag.FLOWERS.isTagged(above));
    }

    /**
     * Levelling of a quarter of a redesigned town with the columns of {@link pl.medivalrpg.ludnosc.planner.TownGrade}
     * ({@code {x, z, oldTop, newTop, MAX, MIN, plot}}): starts at once or not at all. {@code free}: an admin order the
     * treasury does not pay.
     */
    public BuildOrderResult orderGrading(final UUID country, final World w, final int minX, final int minZ, final int maxX,
                                         final int maxZ, final int floorY, final List<int[]> cols, final boolean free) {
        return this.orderGrading(country,w,minX,minZ,maxX,maxZ,floorY,cols,free,REDESIGN);
    }

    public BuildOrderResult orderManualGrading(final UUID country, final World w, final int minX, final int minZ, final int maxX,
                                               final int maxZ, final int floorY, final List<int[]> cols, final boolean free) {
        return this.orderGrading(country,w,minX,minZ,maxX,maxZ,floorY,cols,free,"plan-niwelacja");
    }

    private BuildOrderResult orderGrading(final UUID country, final World w, final int minX, final int minZ, final int maxX,
                                          final int maxZ, final int floorY, final List<int[]> cols, final boolean free,
                                          final String orderedBy) {
        if (this.admission(country) != QueueRules.Admission.START) {
            return BuildOrderResult.TOO_MANY_ORDERS;
        }
        if (cols.isEmpty()) {
            return BuildOrderResult.AREA_OBSTRUCTED;
        }
        long changed = 0;
        for (final int[] c : cols) {
            if (c[2] != c[3]) {
                changed++;
            }
        }
        final long cost = free ? 0 : changed * this.cfg().redesignLevelCostPerColumn;
        if (cost > 0 && !this.pay(country, cost, "Ludnosc: wyrównanie terenu (przebudowa)")) {
            return BuildOrderResult.INSUFFICIENT_FUNDS;
        }
        final ConstructionSite site = this.newSite(country, ConstructionSite.LEVEL, "niwelacja", w.getName(), (minX + maxX) / 2, floorY,
            (minZ + maxZ) / 2, 0, cost, orderedBy, null);
        site.terrain = new ArrayList<>(cols);
        site.road().add(new BlockPos(w.getName(), minX - 2, floorY, minZ - 2));
        site.road().add(new BlockPos(w.getName(), maxX + 2, floorY, maxZ + 2));
        site.phase = Phase.TERRAIN.ordinal();
        this.data().putSite(site);
        return BuildOrderResult.OK;
    }

    /** {@code orderedBy} of everything the town redesign orders (progress, cancel). */
    public static final String REDESIGN = "przebudowa";

    /** City wall section: the ring cells of the site (in order) → wall columns, merlons, towers and gates. */
    private List<RoadDecor.Block> planWall(final ConstructionSite site, final World w) {
        final List<int[]> ring = new ArrayList<>();
        for (final BlockPos p : site.road()) {
            ring.add(new int[] {p.x(), p.z()});
        }
        final List<RoadDecor.Block> out = new ArrayList<>();
        // one site = one open stretch (no wrap-around between its ends); the ground ignores the wall already standing
        // there, so planning the stretch again (restart, chunk reload) gives the same blocks and the same cursor
        for (final WallPlanner.Piece piece : WallPlanner.section(ring, (x, z) -> {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                return TerrainView.UNKNOWN;
            }
            return WorldOps.wallGround(w, x, z, WALL_STACK);
        }, this.roadsAndGates(site.countryId), this.wallStyle(site.countryId), this.cfg().wallsTowerEvery,
            (x, z) -> this.plugin.kraje().getCountryAt(site.world, x >> 4, z >> 4).map(KrajInfo::id)
                .map(site.countryId::equals).orElse(false) && this.plugin.data().buildingAt(site.world, x, site.originY, z) == null
                && !this.plugin.design().untouchable(site.countryId, x, z))) {
            out.add(new RoadDecor.Block(piece.x(), piece.y(), piece.z(), piece.state()));
        }
        return out;
    }

    /** Tallest stack of wall blocks a city wall column may already have (tower 5 + 3, merlon, lantern, spare). */
    static final int WALL_STACK = 12;

    private Set<Long> roadsAndGates(final UUID country) {
        final Set<Long> all = new HashSet<>(this.data().state(country).roads());
        all.addAll(this.plugin.design().gates(country));
        return all;
    }

    /** Type of road-kind sites that take down an old stretch of the city walls. */
    public static final String WALL_REMOVE_TYPE = "rozbiorka_muru";

    /** Old wall stretch: every wall-like block above the ground of its columns comes down. */
    private List<RoadDecor.Block> planWallRemoval(final ConstructionSite site, final World w) {
        final List<RoadDecor.Block> out = new ArrayList<>();
        for (final BlockPos p : site.road()) {
            if (!w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                continue;
            }
            final int top = w.getHighestBlockYAt(p.x(), p.z());
            for (int y = top; y >= top - 12; y--) {
                if (WorldOps.wallBlock(w.getBlockAt(p.x(), y, p.z()).getType())) {
                    out.add(new RoadDecor.Block(p.x(), y, p.z(), "minecraft:air"));
                } else if (!w.getBlockAt(p.x(), y, p.z()).isEmpty()) {
                    break;
                }
            }
        }
        return out;
    }

    /** Orders the removal of an old wall stretch (free). */
    public OrderOutcome orderWallRemoval(final UUID country, final String world, final List<BlockPos> cells) {
        final OrderOutcome out = this.orderRoad(country, world, cells, 0, "AI");
        if (out.result() == BuildOrderResult.OK) {
            for (final ConstructionSite s : this.data().sitesOf(country)) {
                if (s.is(ConstructionSite.ROAD) && "droga".equals(s.type) && s.road().equals(cells)) {
                    s.type = WALL_REMOVE_TYPE;
                }
            }
            for (final QueuedOrder o : this.data().queue(country)) {
                if (ConstructionSite.ROAD.equals(o.kind()) && "droga".equals(o.type) && cells.equals(o.road)) {
                    o.type = WALL_REMOVE_TYPE;
                }
            }
            this.data().markDirty();
        }
        return out;
    }

    /** A complete river crossing in the usual paid, persisted builder queue; river water is never replaced. */
    public OrderOutcome orderBridge(final UUID country, final String world, final List<BlockPos> cells, final long cost) {
        final World w = Bukkit.getWorld(world);
        final List<RoadDecor.Block> blocks = pl.medivalrpg.ludnosc.logic.BridgePlan.blocks(cells, this.plugin.postep().era(country));
        if (w == null || blocks.isEmpty()) {
            return new OrderOutcome(BuildOrderResult.AREA_OBSTRUCTED, false, 0);
        }
        final Set<Long> load = new HashSet<>();
        for (final RoadDecor.Block p : blocks) {
            if (!w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                load.add(RoadPlanner.key(p.x() >> 4, p.z() >> 4));
            }
        }
        if (!load.isEmpty()) {
            for (final long chunk : load) {
                w.getChunkAtAsync(RoadPlanner.keyX(chunk), RoadPlanner.keyZ(chunk));
            }
            return new OrderOutcome(BuildOrderResult.AREA_OBSTRUCTED, false, 0);
        }
        for (final RoadDecor.Block p : blocks) {
            if (!w.isChunkLoaded(p.x() >> 4, p.z() >> 4)
                || !this.plugin.kraje().getCountryAt(world, p.x() >> 4, p.z() >> 4).map(k -> country.equals(k.id())).orElse(false)
                || this.plugin.design().untouchable(country, p.x(), p.z())
                || this.data().buildingAt(world, p.x(), p.y(), p.z()) != null || this.reservedAt(world, p.x(), p.z())) {
                return new OrderOutcome(BuildOrderResult.AREA_OBSTRUCTED, false, 0);
            }
            final Block at = w.getBlockAt(p.x(), p.y(), p.z());
            if (at.isLiquid() || !WorldOps.soft(at) && !WorldOps.ground(at.getType()) && !WorldOps.roadSurface(at.getType())
                && at.getType() != Material.SPRUCE_PLANKS && at.getType() != Material.SPRUCE_FENCE && at.getType() != Material.STONE_BRICK_WALL) {
                return new OrderOutcome(BuildOrderResult.AREA_OBSTRUCTED, false, 0);
            }
            if (p.y() == Math.max(cells.getFirst().y(), cells.getLast().y())) {
                for (int dy = 1; dy <= 2; dy++) {
                    final Block headroom = at.getRelative(0, dy, 0);
                    if (!WorldOps.soft(headroom)) {
                        return new OrderOutcome(BuildOrderResult.AREA_OBSTRUCTED, false, 0);
                    }
                }
            }
        }
        // Reject lava crossings; a river span must have water directly underneath at least one centre cell.
        boolean river = false;
        final int deckY = Math.max(cells.getFirst().y(), cells.getLast().y());
        for (final BlockPos c : cells) {
            for (int y = deckY - 1; y >= Math.max(w.getMinHeight(), deckY - 12); y--) {
                final Material m = w.getBlockAt(c.x(), y, c.z()).getType();
                if (m == Material.LAVA) {
                    return new OrderOutcome(BuildOrderResult.AREA_OBSTRUCTED, false, 0);
                }
                if (m == Material.WATER) {
                    river = true;
                    break;
                }
                if (m.isSolid()) {
                    break;
                }
            }
        }
        if (!river) {
            return new OrderOutcome(BuildOrderResult.AREA_OBSTRUCTED, false, 0);
        }
        final OrderOutcome out = this.orderRoad(country, world, cells, cost, "AI");
        if (out.result() == BuildOrderResult.OK) {
            for (final ConstructionSite s : this.data().sitesOf(country)) {
                if (s.is(ConstructionSite.ROAD) && "droga".equals(s.type) && s.road().equals(cells)) {
                    s.type = BRIDGE_TYPE;
                }
            }
            for (final QueuedOrder o : this.data().queue(country)) {
                if (ConstructionSite.ROAD.equals(o.kind()) && "droga".equals(o.type) && cells.equals(o.road)) {
                    o.type = BRIDGE_TYPE;
                }
            }
            this.data().markDirty();
        }
        return out;
    }

    /** Walls of the epoch; a young medieval town (below Miasto) builds a wooden palisade. */
    private WallPlanner.Style wallStyle(final UUID country) {
        final int era = this.plugin.postep().era(country);
        if (era <= 1 && this.plugin.settlement().levelIndex(country) < 3) {
            return WallPlanner.Style.palisade();
        }
        return WallPlanner.Style.of(era);
    }

    /** A wall stretch (city wall / border palisade, or the removal of an old one) is being built or waits in the queue. */
    public boolean wallWorkPending(final UUID country) {
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            if (WALL_TYPE.equals(s.type) || WALL_REMOVE_TYPE.equals(s.type)) {
                return true;
            }
        }
        for (final QueuedOrder o : this.data().queue(country)) {
            if (WALL_TYPE.equals(o.type) || WALL_REMOVE_TYPE.equals(o.type)) {
                return true;
            }
        }
        return false;
    }

    /** Cells split into continuous stretches (cells touching one another), in order. */
    static List<List<BlockPos>> stretches(final List<BlockPos> cells) {
        final List<List<BlockPos>> out = new ArrayList<>();
        List<BlockPos> cur = null;
        for (final BlockPos p : cells) {
            if (cur == null || !WallRoute.touching(new int[] {cur.getLast().x(), cur.getLast().z()}, new int[] {p.x(), p.z()})) {
                cur = new ArrayList<>();
                out.add(cur);
            }
            cur.add(p);
        }
        return out;
    }

    /**
     * Wall stretches ordered by older versions across breaks of the ring (cells scattered around the town: the site
     * needed every chunk of its bounding box at once and waited for days, blocking the next stretches). Stretches not
     * started yet — sites with no block placed and queued orders — are split into continuous ones: the first keeps the
     * site / order, the others go into the queue right behind it with their share of the price (nothing is charged
     * again, cancelling still refunds the whole price). Returns the number of split orders.
     */
    public int splitScatteredWalls() {
        int split = 0;
        for (final ConstructionSite s : new ArrayList<>(this.data().allSites())) {
            if (!s.is(ConstructionSite.ROAD) || !WALL_TYPE.equals(s.type) && !WALL_REMOVE_TYPE.equals(s.type)
                || s.phase != Phase.ROAD.ordinal() || s.cursor != 0) {
                continue;
            }
            final List<List<BlockPos>> parts = stretches(s.road());
            if (parts.size() < 2) {
                continue;
            }
            final int total = s.road().size();
            final long cost = s.cost;
            long given = 0;
            final List<QueuedOrder> tail = new ArrayList<>();
            for (int i = 1; i < parts.size(); i++) {
                final List<BlockPos> part = parts.get(i);
                final long share = cost * part.size() / total;
                given += share;
                tail.add(this.wallOrder(s.countryId, s.type, s.world, part, share, s.orderedBy));
            }
            s.road().clear();
            s.road().addAll(parts.getFirst());
            s.originX = parts.getFirst().getFirst().x();
            s.originZ = parts.getFirst().getFirst().z();
            s.cost = cost - given;
            final SiteRuntime rt = this.runtimes.remove(s.id);
            if (rt != null) {
                rt.crew.remove();
            }
            // the parts start before anything ordered later: front of the queue, in ring order
            for (int i = tail.size() - 1; i >= 0; i--) {
                this.data().enqueueFront(tail.get(i));
            }
            split++;
        }
        for (final QueuedOrder o : this.data().allQueued()) {
            if (!ConstructionSite.ROAD.equals(o.kind()) || o.road == null || !WALL_TYPE.equals(o.type) && !WALL_REMOVE_TYPE.equals(o.type)) {
                continue;
            }
            final List<List<BlockPos>> parts = stretches(o.road);
            if (parts.size() < 2) {
                continue;
            }
            final int total = o.road.size();
            final long cost = o.cost;
            long given = 0;
            QueuedOrder anchor = o;
            for (int i = 1; i < parts.size(); i++) {
                final List<BlockPos> part = parts.get(i);
                final long share = cost * part.size() / total;
                given += share;
                final QueuedOrder next = this.wallOrder(o.countryId, o.type, o.world, part, share, o.orderedBy);
                this.data().enqueueAfter(anchor, next);
                anchor = next;
            }
            o.road = new ArrayList<>(parts.getFirst());
            o.originX = parts.getFirst().getFirst().x();
            o.originZ = parts.getFirst().getFirst().z();
            o.cost = cost - given;
            split++;
        }
        if (split > 0) {
            this.data().markDirty();
        }
        return split;
    }

    private QueuedOrder wallOrder(final UUID country, final String type, final String world, final List<BlockPos> cells, final long cost,
                                  final String orderedBy) {
        final BlockPos first = cells.getFirst();
        final QueuedOrder o = QueuedOrder.of(country, type, world, first.x(), first.y(), first.z(), 0, cost, orderedBy);
        o.kind = ConstructionSite.ROAD;
        o.road = new ArrayList<>(cells);
        return o;
    }

    /** Orders a section of the city walls (ring cells in order). */
    public OrderOutcome orderWall(final UUID country, final String world, final List<BlockPos> cells, final long cost) {
        final OrderOutcome out = this.orderRoad(country, world, cells, cost, "AI");
        if (out.result() == BuildOrderResult.OK) {
            // the order was created as a road: turn it into a wall section
            for (final ConstructionSite s : this.data().sitesOf(country)) {
                if (s.is(ConstructionSite.ROAD) && "droga".equals(s.type) && s.road().equals(cells)) {
                    s.type = WALL_TYPE;
                }
            }
            for (final QueuedOrder o : this.data().queue(country)) {
                if (ConstructionSite.ROAD.equals(o.kind()) && "droga".equals(o.type) && cells.equals(o.road)) {
                    o.type = WALL_TYPE;
                }
            }
            this.data().markDirty();
        }
        return out;
    }

    // ------------------------------------------------------------------ materials

    /** With {@code require-materials} a building / repair / road takes its wood and stone from the site chest. */
    private boolean needsMaterials(final ConstructionSite site, final SiteRuntime rt) {
        return this.cfg().requireMaterials && rt.tpl != null && !site.is(ConstructionSite.RESTORE)
            && !site.is(ConstructionSite.DEMOLISH) && !site.is(ConstructionSite.LEVEL);
    }

    /**
     * With {@code require-materials}: the raw material for one block. A block costs units of one or more groups
     * ({@link MaterialNeeds#costs}: wood, stone, glass, bricks, wool, hay, iron, torches...); the exact block item lying
     * in the site chest pays it whole, otherwise raw goods taken out of the site chest are cut / baled / forged on the
     * site ({@link ConstructionSite#materials}). A group the country cannot make (no workshop for it, or its workshops
     * starve) is paid by the order's coins; a group it makes is waited for (at most {@code material-wait-minutes}).
     * Returns false (and says why) when the site has to wait.
     */
    private boolean consume(final ConstructionSite site, final SiteRuntime rt, final BlockData d) {
        if (!this.needsMaterials(site, rt) || d.getMaterial().isAir()) {
            return true;
        }
        final String s = d.getAsString();
        if (s.contains("half=upper") || s.contains("part=head") || d.getMaterial() == Material.WATER
            || d.getMaterial() == Material.LAVA || d.getMaterial() == Material.SCAFFOLDING) {
            return true;
        }
        final Material need = d.getPlacementMaterial();
        if (need == null || need.isAir() || !need.isItem()) {
            return true;
        }
        final Map<String, Integer> costs = MaterialNeeds.costs(need.name());
        if (costs.isEmpty()) {
            return true; // flowers, bells, quartz...: bought with the coins of the order
        }
        final Map<String, Integer> buffer = site.materials();
        boolean lacking = false;
        for (final Map.Entry<String, Integer> e : costs.entrySet()) {
            lacking |= buffer.getOrDefault(e.getKey(), 0) < e.getValue();
        }
        if (lacking && this.takeExact(site, need)) {
            rt.waitingMaterial = null;
            rt.waitingSince = 0;
            return true;
        }
        for (final Map.Entry<String, Integer> e : costs.entrySet()) {
            if (buffer.getOrDefault(e.getKey(), 0) < e.getValue()) {
                this.openMaterial(site, rt, e.getKey(), need, e.getValue());
            }
        }
        final long waited = rt.waitingSince == 0 ? 0 : System.currentTimeMillis() - rt.waitingSince;
        String group = null;
        final Map<String, Boolean> producer = new HashMap<>();
        for (final Map.Entry<String, Integer> e : costs.entrySet()) {
            if (buffer.getOrDefault(e.getKey(), 0) >= e.getValue()) {
                continue;
            }
            final boolean makes = this.produces(site.countryId, e.getKey());
            producer.put(e.getKey(), makes);
            if (MaterialNeeds.mustWait(makes, this.hasStore(site.countryId), waited, this.cfg().materialWaitMinutes * 60_000L)) {
                group = e.getKey();
                break;
            }
        }
        if (group == null) {
            String boughtMade = null;
            for (final Map.Entry<String, Integer> e : costs.entrySet()) {
                if (buffer.getOrDefault(e.getKey(), 0) >= e.getValue()) {
                    buffer.merge(e.getKey(), -e.getValue(), Integer::sum);
                } else if (producer.getOrDefault(e.getKey(), false) && boughtMade == null) {
                    boughtMade = e.getKey();
                }
            }
            this.data().markDirty();
            rt.waitingMaterial = null;
            if (boughtMade == null) {
                rt.waitingSince = 0; // everything the country makes came from its own stores
            } else if (rt.waitingSince != 0) {
                // keeps buying until real material reaches the site chest again (that use resets the clock)
                rt.status = "kupiono " + MaterialNeeds.displayName(boughtMade) + " na targu (koszt zamówienia) — magazyny nie dostarczyły w "
                    + this.cfg().materialWaitMinutes + " min";
            }
            return true; // the rest was bought with the order's coins (the country cannot make it, or waited too long)
        }
        rt.waitingMaterial = MaterialNeeds.displayName(group);
        if (rt.waitingSince == 0) {
            rt.waitingSince = System.currentTimeMillis();
        }
        final String source = this.plugin.logistics() == null ? null : this.plugin.logistics().sourceHint(site.countryId, group);
        final String why = site.storage == null ? "brak skrzyni budowy — miejsce przed placem jest zajęte"
            : source == null ? "brak w dostępnych magazynach kraju (" + MaterialNeeds.producerName(group) + " wytwarza); włóż "
                + rt.waitingMaterial + " do skrzyni budowy albo magazynu"
            : "tragarz przyniesie z: " + source;
        rt.status = "czeka na " + rt.waitingMaterial + " — " + why;
        final long now = System.currentTimeMillis();
        if (now - this.materialWarned.getOrDefault(site.id, 0L) > MATERIAL_WARN_MILLIS) {
            this.materialWarned.put(site.id, now);
            this.plugin.notifyCountry(site.countryId, "site-waiting-material", Placeholder.parsed("name", this.displayName(site.type)),
                Placeholder.unparsed("material", rt.waitingMaterial + " (" + why + ")"),
                Placeholder.unparsed("pos", site.storage == null ? site.originX + " " + site.originY + " " + site.originZ
                    : site.storage.x() + " " + site.storage.y() + " " + site.storage.z()));
        }
        return false;
    }

    /** The exact block item lying in the site chest (e.g. a bed or a lantern a player put there) pays the block whole. */
    private boolean takeExact(final ConstructionSite site, final Material need) {
        final Inventory inv = this.siteChest(site);
        if (inv == null) {
            return false;
        }
        final ItemStack[] items = inv.getStorageContents();
        for (int i = 0; i < items.length; i++) {
            final ItemStack item = items[i];
            if (item != null && item.getType() == need && !item.hasItemMeta()) {
                item.setAmount(item.getAmount() - 1);
                items[i] = item.getAmount() <= 0 ? null : item;
                inv.setStorageContents(items);
                this.data().markDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * A variant of {@code type} for this plot: the family members the country may build (epoch, discovery, never a
     * player's copy), starting at an index fixed by the plot position (a restart or a retry picks the same one), first
     * one that passes the normal order check there; the type itself when none does.
     */
    public String variantFor(final UUID country, final String type, final World w, final int x, final int y, final int z, final int q) {
        final List<String> family = new ArrayList<>();
        for (final String id : this.plugin.templates().family(type)) {
            final BuildingTemplate t = this.plugin.templates().get(id);
            if (t == null || pl.medivalrpg.ludnosc.logic.BlueprintRules.isBlueprintId(id) || t.meta().era() > this.plugin.postep().era(country)
                || !t.meta().discovery().isEmpty() && !this.plugin.postep().hasDiscovery(country, t.meta().discovery())
                || !pl.medivalrpg.ludnosc.logic.RoyalManifest.templateAllowed(country, id)) {
                continue;
            }
            family.add(id);
        }
        if (family.size() < 2) {
            return type;
        }
        final int start = pl.medivalrpg.ludnosc.logic.VariantPick.start(family.size(), w.getName(), x, z);
        for (int i = 0; i < family.size(); i++) {
            final String id = family.get((start + i) % family.size());
            if (this.check(country, id, w, x, y, z, q, null, false) == BuildOrderResult.OK) {
                return id;
            }
        }
        return type;
    }

    /**
     * The country makes the group: a sawmill for wood, a quarry or mine for stone, and for the chains of the workshops
     * (glass, bricks, wool, hay, iron, torches) a workshop that is not starving (see {@code WorkshopService#capable}).
     */
    private boolean produces(final UUID country, final String group) {
        final java.util.Set<BuildingFunction> have = java.util.EnumSet.noneOf(BuildingFunction.class);
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.function() != null) {
                have.add(b.function());
            }
        }
        if (!MaterialNeeds.producedBy(group, have::contains)) {
            return false;
        }
        if (MaterialNeeds.WOOD.equals(group) || MaterialNeeds.STONE.equals(group)) {
            return true;
        }
        return this.plugin.workshops() != null && this.plugin.workshops().capable(country, group);
    }

    /** Whether the country makes the group itself (for /ludnosc budowy and the panel); main thread. */
    public boolean makes(final UUID country, final String group) {
        return this.produces(country, group);
    }

    /** The country has at least one registered physical store (workplace, warehouse or default container). */
    private boolean hasStore(final UUID country) {
        final CountryState st = this.data().stateIfPresent(country);
        return st != null && !st.productionStores().isEmpty();
    }

    /** Takes raw goods of a group out of the site chest (logs/planks, stone, glass, wool, ingots...) into the buffer. */
    private void openMaterial(final ConstructionSite site, final SiteRuntime rt, final String group, final Material need,
                              final int units) {
        final Map<String, Integer> buffer = site.materials();
        final Inventory inv = this.siteChest(site);
        if (inv != null) {
            final ItemStack[] items = inv.getStorageContents();
            boolean changed = false;
            for (int i = 0; i < items.length && buffer.getOrDefault(group, 0) < units; i++) {
                final ItemStack item = items[i];
                if (item == null || item.getType().isAir() || item.hasItemMeta()) {
                    continue;
                }
                final int gives = MaterialNeeds.supply(item.getType().name(), group);
                if (gives <= 0) {
                    continue;
                }
                while (item.getAmount() > 0 && buffer.getOrDefault(group, 0) < units) {
                    item.setAmount(item.getAmount() - 1);
                    buffer.merge(group, gives, Integer::sum);
                    changed = true;
                }
                items[i] = item.getAmount() <= 0 ? null : item;
            }
            if (changed) {
                inv.setStorageContents(items);
                this.data().markDirty();
            }
        }
        if (buffer.getOrDefault(group, 0) >= units) {
            return;
        }
        // historical virtual stock (salvage of demolitions, old daily production): one unit each
        final CountryState st = this.data().stateIfPresent(site.countryId);
        if (st != null && st.resources().getOrDefault(group, 0L) > 0) {
            final long take = Math.min(st.resources().get(group), units - buffer.getOrDefault(group, 0));
            st.resources().merge(group, -take, Long::sum);
            buffer.merge(group, (int) take, Integer::sum);
            this.data().markDirty();
            if (buffer.getOrDefault(group, 0) >= units) {
                return;
            }
        }
        // haulers disabled: the old direct debit from any store of the country
        if (this.plugin.logistics() == null || !this.cfg().logistics.enabled()) {
            if ((MaterialNeeds.WOOD.equals(group) || MaterialNeeds.STONE.equals(group)) && MaterialNeeds.costs(need.name()).size() == 1) {
                if (this.plugin.resources().take(site.countryId, need)) {
                    buffer.merge(group, units, Integer::sum);
                }
                return;
            }
            while (buffer.getOrDefault(group, 0) < units) {
                final int got = this.plugin.production().takeSupply(site.countryId, group);
                if (got <= 0) {
                    break;
                }
                buffer.merge(group, got, Integer::sum);
                this.data().markDirty();
            }
        }
    }

    /** Inventory of the site chest (loaded, still a chest), or null; a destroyed chest is forgotten and placed again. */
    Inventory siteChest(final ConstructionSite site) {
        if (site.storage == null) {
            return null;
        }
        final World w = Bukkit.getWorld(site.storage.world());
        if (w == null || !w.isChunkLoaded(site.storage.x() >> 4, site.storage.z() >> 4)) {
            return null;
        }
        final Block chestBlock = w.getBlockAt(site.storage.x(), site.storage.y(), site.storage.z());
        if (!(chestBlock.getState(false) instanceof Chest chest)) {
            site.storage = null; // destroyed: a new one is set up on the next tick
            this.data().markDirty();
            return null;
        }
        return chest.getBlockInventory();
    }

    /** A material request of a site for the haulers: units of a group still wanted in its chest. */
    public record MaterialDemand(UUID site, UUID country, String name, BlockPos chest, String group, long units) {
    }

    /**
     * What the working sites still need in their chests: the raw material of the rest of the building (from the cursor
     * on), minus what is cut on site and lying in the chest, capped at {@code logistics.site-stock}. Main thread.
     */
    public List<MaterialDemand> materialDemands() {
        final List<MaterialDemand> out = new ArrayList<>();
        if (!this.cfg().requireMaterials) {
            return out;
        }
        final long now = System.currentTimeMillis();
        for (final ConstructionSite site : this.data().allSites()) {
            final SiteRuntime rt = this.runtimes.get(site.id);
            if (rt == null || site.storage == null || !this.needsMaterials(site, rt) || site.phase > Phase.ROAD.ordinal()) {
                continue;
            }
            if (now - rt.needsAt > NEEDS_MILLIS) {
                rt.needs = this.remainingNeeds(site, rt);
                rt.needsAt = now;
            }
            final Inventory inv = this.siteChest(site);
            if (inv == null) {
                continue;
            }
            for (final Map.Entry<String, Long> e : rt.needs.entrySet()) {
                long have = site.materials().getOrDefault(e.getKey(), 0);
                for (final ItemStack item : inv.getStorageContents()) {
                    if (item != null && !item.hasItemMeta()) {
                        have += (long) item.getAmount() * MaterialNeeds.supply(item.getType().name(), e.getKey());
                    }
                }
                final long target = Math.min(e.getValue(), this.cfg().logistics.siteStock());
                final long want = target - have;
                // a fresh load only when the pile runs low (no stream of tiny top-up trips)
                if (want > 0 && have * 2 < target) {
                    out.add(new MaterialDemand(site.id, site.countryId, this.displayPlain(site.type), site.storage, e.getKey(), want));
                }
            }
        }
        return out;
    }

    /** Raw material of the rest of the site (template order from the cursor), by group. */
    private Map<String, Long> remainingNeeds(final ConstructionSite site, final SiteRuntime rt) {
        final Map<String, Long> out = new HashMap<>();
        if (rt.tpl == null) {
            return out;
        }
        if (site.phase <= Phase.FOUNDATION.ordinal()) {
            final int from = site.phase == Phase.FOUNDATION.ordinal() ? site.cursor : 0;
            final long left = Math.max(0, rt.tpl.foundation().size() - from);
            MaterialNeeds.costs(rt.tpl.foundationBlock().getMaterial().name()).forEach((g, u) -> out.merge(g, left * u, Long::sum));
        }
        if (site.phase <= Phase.CELLS.ordinal()) {
            final int from = site.phase == Phase.CELLS.ordinal() ? site.cursor : 0;
            final int[] order = rt.tpl.order();
            for (int i = Math.max(0, from); i < order.length; i++) {
                final BlockData d = rt.tpl.data()[order[i]];
                if (d == null || d.getMaterial().isAir() || site.is(ConstructionSite.UPGRADE) && (rt.slots == null || !rt.slots[order[i]])) {
                    continue;
                }
                final Material m = d.getPlacementMaterial();
                if (m == null) {
                    continue;
                }
                final String half = d.getAsString();
                if (half.contains("half=upper") || half.contains("part=head")) {
                    continue; // the other half of a door / bed is paid with its first half
                }
                MaterialNeeds.costs(m.name()).forEach((g, u) -> out.merge(g, (long) u, Long::sum));
            }
        }
        return out;
    }

    /** A site chest on free ground next to the front spot (searched in a small ring, never inside the plot). */
    private void placeStorage(final ConstructionSite site, final World w, final Location front) {
        final BlockBox box = this.box(site);
        for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {2, 0}, {-2, 0}, {0, 1}, {1, 1}, {-1, 1}, {0, -1}, {3, 0}, {-3, 0}}) {
            final int x = front.getBlockX() + d[0];
            final int z = front.getBlockZ() + d[1];
            if (!w.isChunkLoaded(x >> 4, z >> 4) || box != null && box.contains(w.getName(), x, box.minY(), z)) {
                continue;
            }
            final int top = WorldOps.topSolid(w, x, z, front.getBlockY() + 3, front.getBlockY() - 4);
            if (top == TerrainView.UNKNOWN) {
                continue;
            }
            final Block b = w.getBlockAt(x, top + 1, z);
            if ((b.isEmpty() || b.isReplaceable()) && !b.isLiquid() && this.data().buildingAt(w.getName(), x, top + 1, z) == null
                && this.plugin.kraje().getCountryAt(w.getName(), x >> 4, z >> 4).map(k -> site.countryId.equals(k.id())).orElse(false)) {
                b.setType(Material.CHEST, false);
                site.storage = new BlockPos(site.world, b.getX(), b.getY(), b.getZ());
                this.data().markDirty();
                return;
            }
        }
    }

    /** The site chest goes; what is left in it is carried into a store of the country, the rest drops on the spot. */
    private void removeStorage(final ConstructionSite site) {
        if (site.storage == null) {
            return;
        }
        final World w = Bukkit.getWorld(site.storage.world());
        if (w != null) w.getChunkAt(site.storage.x() >> 4, site.storage.z() >> 4);
        if (w != null && w.isChunkLoaded(site.storage.x() >> 4, site.storage.z() >> 4)) {
            final Block b = w.getBlockAt(site.storage.x(), site.storage.y(), site.storage.z());
            if (b.getType() == Material.CHEST && b.getState(false) instanceof Chest chest) {
                if (this.plugin.logistics() != null) {
                    this.plugin.logistics().returnLeftovers(site.countryId, chest.getBlockInventory(), b.getLocation());
                }
                b.breakNaturally(); // whatever no store could take drops
            }
        }
        site.storage = null;
    }

    // ------------------------------------------------------------------ night

    private void night(final ConstructionSite site, final SiteRuntime rt, final World w, final Location front) {
        if (!rt.night) {
            rt.night = true;
            if (this.cfg().nightCampfire && site.campfire == null) {
                final Block b = front.getBlock();
                if (b.isEmpty() || b.isReplaceable()) {
                    b.setBlockData(Bukkit.createBlockData("minecraft:campfire[lit=true,signal_fire=false]"), false);
                    site.campfire = new BlockPos(site.world, b.getX(), b.getY(), b.getZ());
                    this.data().markDirty();
                }
            }
            int i = 0;
            for (final BuilderCrew.Body body : rt.crew.bodies) {
                final double angle = (Math.PI * 2 * i++) / Math.max(1, rt.crew.bodies.size());
                body.target = front.clone().add(Math.cos(angle) * 1.8, 0, Math.sin(angle) * 1.8);
                body.target.setY(WorldOps.topSolid(w, body.target.getBlockX(), body.target.getBlockZ(), front.getBlockY() + 3,
                    front.getBlockY() - 4) + 1);
                body.look = front.clone().add(0.5, 0.3, 0.5);
            }
        }
    }

    private void morning(final ConstructionSite site, final SiteRuntime rt, final World w) {
        rt.night = false;
        this.removeCampfire(site, w);
    }

    private void removeCampfire(final ConstructionSite site, final World w) {
        if (site.campfire != null && w.isChunkLoaded(site.campfire.x() >> 4, site.campfire.z() >> 4)) {
            final Block b = w.getBlockAt(site.campfire.x(), site.campfire.y(), site.campfire.z());
            if (b.getType() == Material.CAMPFIRE) {
                b.setType(Material.AIR, false);
            }
        }
        site.campfire = null;
    }

    // ------------------------------------------------------------------ helpers (spot, hologram, entities)

    /** Column in front of the plot (3 blocks out along the front vector); inside {@link #NEAR_MARGIN} of the box. */
    static int[] frontXZ(final BlockBox box, final int[] v) {
        final int dist = (v[0] != 0 ? box.sizeX() : box.sizeZ()) / 2 + 3;
        return new int[] {box.centerX() + v[0] * dist, box.centerZ() + v[1] * dist};
    }

    private Location frontSpot(final ConstructionSite site, final SiteRuntime rt, final World w, final BlockBox box) {
        final int[] v = rt.tpl == null ? new int[] {0, 1} : BlockStates.vector(rt.tpl.template().front());
        final int[] xz = frontXZ(box, v);
        final int x = xz[0];
        final int z = xz[1];
        final int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        final Location loc = new Location(w, x + 0.5, y, z + 0.5);
        loc.setDirection(new Location(w, box.centerX() + 0.5, y, box.centerZ() + 0.5).toVector().subtract(loc.toVector()));
        return loc;
    }

    private void pushOutLiving(final World w, final BlockBox box, final Location front) {
        for (final Entity e : w.getNearbyEntities(BoundingBox.of(new Location(w, box.minX(), box.minY(), box.minZ()),
            new Location(w, box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1)))) {
            if (e instanceof LivingEntity && !(e instanceof Player) && !this.isHelper(e)) {
                final Block feet = e.getLocation().getBlock();
                if (feet.getType().isOccluding() || feet.getRelative(0, 1, 0).getType().isOccluding()) {
                    e.teleport(front);
                }
            }
        }
    }

    private void updateHologram(final ConstructionSite site, final SiteRuntime rt, final World w, final BlockBox box,
                                final String state) {
        if (!this.cfg().hologram) {
            return;
        }
        Entity h = rt.crew.hologram == null ? null : Bukkit.getEntity(rt.crew.hologram);
        if (h == null || !h.isValid()) {
            final Location loc = new Location(w, box.centerX() + 0.5, box.maxY() + 1.5, box.centerZ() + 0.5);
            h = w.spawn(loc, TextDisplay.class, td -> {
                td.setPersistent(false);
                td.setBillboard(Display.Billboard.CENTER);
                td.setShadowed(true);
                Keys.setUuid(td, this.plugin.keys().hologram, site.id);
            });
            rt.crew.hologram = h.getUniqueId();
            rt.crew.lastHologram = null;
        }
        final int percent = this.percent(site);
        final String key = "night".equals(state) ? "site-hologram-night" : "material".equals(state) ? "site-hologram-material"
            : "site-hologram";
        final String text = key + "|" + percent + "|" + rt.crew.bodies.size() + "|" + rt.waitingMaterial;
        if (!text.equals(rt.crew.lastHologram) && h instanceof TextDisplay td) {
            td.text(this.plugin.messages().get(key, Placeholder.parsed("name", this.displayName(site.type)),
                Placeholder.unparsed("percent", Integer.toString(percent)),
                Placeholder.unparsed("builders", Integer.toString(rt.crew.bodies.size())),
                Placeholder.unparsed("material", rt.waitingMaterial == null ? "" : rt.waitingMaterial)));
            rt.crew.lastHologram = text;
        }
    }

    public boolean isHelper(final Entity e) {
        return Keys.getUuid(e, this.plugin.keys().builder) != null || Keys.getUuid(e, this.plugin.keys().hologram) != null;
    }

    /** A helper entity loaded from disk (they are non-persistent) that no site tracks → removed. */
    public void checkLoadedHelper(final Entity e) {
        UUID site = Keys.getUuid(e, this.plugin.keys().builder);
        if (site == null) {
            site = Keys.getUuid(e, this.plugin.keys().hologram);
        }
        if (site == null) {
            return;
        }
        final SiteRuntime rt = this.runtimes.get(site);
        if (rt == null || !rt.crew.contains(e.getUniqueId())) {
            e.remove();
        }
    }

    public void shutdown() {
        for (final SiteRuntime rt : this.runtimes.values()) {
            rt.crew.remove();
        }
        this.runtimes.clear();
        this.releaseTickets();
    }

    /** Drops cached runtimes (templates reloaded); helpers are respawned on the next tick. */
    public void reload() {
        for (final SiteRuntime rt : this.runtimes.values()) {
            rt.crew.remove();
        }
        this.runtimes.clear();
    }

    // ------------------------------------------------------------------ completion / cancel

    private void finish(final ConstructionSite site, final SiteRuntime rt, final World w) {
        this.removeScaffolding(site, w);
        this.removeCampfire(site, w);
        this.removeStorage(site);
        final BlockBox box = this.workBox(site, rt);
        if (box != null && chunksLoaded(w, box)) {
            WorldOps.connectionPass(w, box);
        }
        rt.crew.remove();
        this.runtimes.remove(site.id);
        this.data().removeSite(site.id);
        this.materialWarned.remove(site.id);
        if (site.is(ConstructionSite.LEVEL)) {
            this.graded(site, w);
            return;
        }
        if (site.is(ConstructionSite.RESTORE)) {
            if (rt.placement != null && box != null && chunksLoaded(w, TerrainFootprint.expand(box, NEAR_MARGIN))) {
                this.sweepPlot(w, box, rt.placement.groundY(), false);
            }
            return;
        }
        if (site.is(ConstructionSite.ROAD)) {
            if (!WALL_TYPE.equals(site.type) && !WALL_REMOVE_TYPE.equals(site.type)) {
                this.addRoads(site);
            } else if (!site.road().isEmpty()) {
                final BlockPos a = site.road().getFirst();
                final BlockPos b = site.road().getLast();
                this.plugin.getLogger().info((WALL_TYPE.equals(site.type) ? "Ukończono odcinek muru miejskiego" : "Rozebrano odcinek starego muru")
                    + " (" + site.road().size() + " bl., " + a.x() + " " + a.z() + " → " + b.x() + " " + b.z() + ") kraju "
                    + this.plugin.countryName(site.countryId));
            }
            return;
        }
        if (site.is(ConstructionSite.DEMOLISH)) {
            final Building old = site.repairOf == null ? null : this.data().building(site.repairOf);
            if (old != null) {
                this.plugin.buildings().remove(old);
            }
            // no pit and no floating remains: the plot goes back to the ground level until something is built there
            if (rt.placement != null && box != null && chunksLoaded(w, TerrainFootprint.expand(box, NEAR_MARGIN))) {
                // a bridge or harbour stands in water: its remains go, the river is not filled
                this.sweepPlot(w, box, rt.placement.groundY(), !waterBuilding(rt));
            }
            this.plugin.terrain().refresh(w.getName(), box);
            this.plugin.notifyCountry(site.countryId, "demolish-done", Placeholder.parsed("name", this.displayName(site.type)));
            if (site.then != null && this.plugin.templates().get(site.then) != null) {
                this.plugin.settlement().buildInPlace(site.countryId, site.then, w, site.originX, site.originY, site.originZ, site.rotation);
            }
            return;
        }
        final Placement p = rt.placement;
        final BuildingTemplate t = rt.tpl.template();
        int beds = BuildingService.countBeds(w, box);
        if (beds < 0) {
            beds = t.bedCount();
        }
        if (site.is(ConstructionSite.UPGRADE) && site.repairOf != null && this.data().building(site.repairOf) != null) {
            final Building b = this.data().building(site.repairOf);
            this.data().putBuilding(b.withBeds(beds).withLevel(site.level));
            this.plugin.repairs().repaired(b.id());
            this.plugin.underground().restoreHatch(b);
            this.plugin.notifyCountry(site.countryId, "upgrade-done", Placeholder.parsed("name", t.meta().displayName()),
                Placeholder.unparsed("level", this.plugin.settlement().styleName(MaterialPalette.decode(site.level))));
            return;
        }
        if (site.repairOf != null && this.data().building(site.repairOf) != null) {
            final Building b = this.data().building(site.repairOf);
            this.data().putBuilding(b.withBeds(beds).withLevel(Math.max(b.level(), site.level)));
            this.plugin.repairs().repaired(b.id());
            this.plugin.notifyCountry(site.countryId, "repair-done", Placeholder.parsed("name", t.meta().displayName()));
            return;
        }
        final int[] sc = t.spawnCell();
        final BlockPos spawn = sc == null ? null : p.toWorld(sc);
        final Building built = this.plugin.buildings().add(site.countryId, t.id(), t.meta().function(), box, beds, spawn,
            site.rotation, false, site.dye, site.level);
        if (built != null) {
            this.plugin.underground().onBuilt(built);
            this.data().state(site.countryId).origins().put(built.id().toString(), site.orderedBy == null ? "" : site.orderedBy);
            this.data().markDirty();
        }
        if (t.meta().function() == BuildingFunction.MINE && built != null && this.plugin.templates().get(t.id()) != null) {
            this.plugin.mines().register(built, this.plugin.templates().get(t.id()), site.rotation, p.minX(), p.minY(), p.minZ());
        }
        if (t.meta().function() == BuildingFunction.GRANARY) {
            final List<BlockPos> containers = new ArrayList<>();
            for (final int[] c : t.containers()) {
                containers.add(p.toWorld(c));
            }
            this.plugin.granary().addAll(site.countryId, containers);
        }
        this.addRoads(site);
        if (t.meta().function().housing()) {
            this.greenery(w, box);
        }
        final Location center = new Location(w, box.centerX() + 0.5, p.groundY() + 1.5, box.centerZ() + 0.5);
        if (this.cfg().effects) {
            w.playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            w.spawnParticle(Particle.HAPPY_VILLAGER, center, 40, box.sizeX() / 3.0, 2, box.sizeZ() / 3.0, 0);
        }
        this.plugin.notifyCountry(site.countryId, "site-finished", Placeholder.parsed("name", t.meta().displayName()),
            Placeholder.unparsed("pos", center.getBlockX() + " " + center.getBlockY() + " " + center.getBlockZ()));
        this.plugin.getLogger().info("Zakończono budowę " + t.id() + " (" + box + ") kraju " + this.plugin.countryName(site.countryId));
    }

    /**
     * After the town grading of a quarter: the old street cells it changed are laid again on the new ground (the
     * redesign orders them with its streets), cells inside the quarter are dropped; the terrain cache is refreshed.
     */
    private void graded(final ConstructionSite site, final World w) {
        final CountryState st = this.data().state(site.countryId);
        if (site.terrain != null && !site.terrain.isEmpty()) {
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            final Set<Long> roads = st.roads();
            for (final int[] c : site.terrain) {
                minX = Math.min(minX, c[0]);
                minZ = Math.min(minZ, c[1]);
                maxX = Math.max(maxX, c[0]);
                maxZ = Math.max(maxZ, c[1]);
                final long key = RoadPlanner.key(c[0], c[1]);
                if (c[2] == c[3] || !roads.remove(key)) {
                    continue;
                }
                if (c[6] == 0 && st.planActive) {
                    if (st.planRoads == null) {
                        st.planRoads = new ArrayList<>();
                    }
                    if (!st.planRoads.contains(key)) {
                        st.planRoads.add(key);
                    }
                }
            }
            this.plugin.terrain().refresh(w.getName(), new BlockBox(w.getName(), minX, 0, minZ, maxX, 0, maxZ));
            this.data().markDirty();
        }
    }

    /**
     * A plot left by a demolition / a cancelled build: every non-natural block above the ground level in the box goes
     * (remains of an older template, lamps, scaffolding), entrance steps around it too; {@code fill}: the dug parts
     * (cellars, foundations, a well shaft) are filled with earth up to the ground level, grass on top.
     */
    void sweepPlot(final World w, final BlockBox box, final int groundY, final boolean fill) {
        for (int x = box.minX() - 3; x <= box.maxX() + 3; x++) {
            for (int z = box.minZ() - 3; z <= box.maxZ() + 3; z++) {
                final boolean inside = x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
                if (this.plugin.data().buildingAt(w.getName(), x, groundY + 1, z) != null || this.reservedAt(w.getName(), x, z)) {
                    continue;
                }
                if (!inside) {
                    // entrance steps and their supports in front of the old doors
                    for (int y = groundY - 4; y <= groundY + 2; y++) {
                        final Block b = w.getBlockAt(x, y, z);
                        if (Tag.STAIRS.isTagged(b.getType()) && b.getRelative(0, -1, 0).isEmpty()) {
                            b.setType(Material.AIR, false);
                        }
                    }
                    continue;
                }
                for (int y = box.maxY() + 2; y > groundY; y--) {
                    final Block b = w.getBlockAt(x, y, z);
                    if (!b.isEmpty() && (WorldOps.leftover(b) || WorldOps.soft(b) && !Tag.LEAVES.isTagged(b.getType()))) {
                        b.setType(Material.AIR, false);
                    }
                }
                if (!fill) {
                    continue;
                }
                for (int y = Math.max(w.getMinHeight(), box.minY() - 1); y <= groundY; y++) {
                    final Block b = w.getBlockAt(x, y, z);
                    if (b.isEmpty() || b.isLiquid() || WorldOps.soft(b) || WorldOps.townDecor(b.getType())) {
                        b.setType(WorldOps.fill(Material.GRASS_BLOCK, groundY - y), false);
                    }
                }
                final Block top = w.getBlockAt(x, groundY, z);
                if (top.getType() == Material.DIRT && top.getRelative(0, 1, 0).isEmpty()) {
                    top.setType(Material.GRASS_BLOCK, false);
                }
            }
        }
    }

    /** A construction site or queued order reserves the column. */
    private boolean reservedAt(final String world, final int x, final int z) {
        for (final BlockBox b : this.reservations(null)) {
            if (b != null && b.world().equals(world) && x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private void addRoads(final ConstructionSite site) {
        if (site.road().isEmpty()) {
            return;
        }
        final CountryState st = this.data().state(site.countryId);
        for (final BlockPos p : site.road()) {
            st.roads().add(RoadPlanner.key(p.x(), p.z()));
        }
        this.data().markDirty();
    }

    /** A couple of saplings and flowers on grass around a finished house. */
    private void greenery(final World w, final BlockBox box) {
        final Material[] saplings = {Material.OAK_SAPLING, Material.BIRCH_SAPLING};
        final Material[] flowers = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.OXEYE_DAISY, Material.ALLIUM};
        int trees = 0;
        int flowerCount = 0;
        for (int i = 0; i < 40 && (trees < 2 || flowerCount < 4); i++) {
            final int side = this.random.nextInt(4);
            final int x = side < 2 ? box.minX() - 3 + this.random.nextInt(box.sizeX() + 6) : side == 2 ? box.minX() - 3 : box.maxX() + 3;
            final int z = side >= 2 ? box.minZ() - 3 + this.random.nextInt(box.sizeZ() + 6) : side == 0 ? box.minZ() - 3 : box.maxZ() + 3;
            if (!w.isChunkLoaded(x >> 4, z >> 4) || this.data().buildingAt(w.getName(), x, box.minY(), z) != null) {
                continue;
            }
            final int y = WorldOps.topSolid(w, x, z, box.minY() + 6, box.minY() - 6);
            if (y == TerrainView.UNKNOWN || w.getBlockAt(x, y, z).getType() != Material.GRASS_BLOCK) {
                continue;
            }
            final Block above = w.getBlockAt(x, y + 1, z);
            if (!above.isEmpty()) {
                continue;
            }
            if (trees < 2 && (x - box.minX() + z) % 3 == 0) {
                above.setType(saplings[this.random.nextInt(saplings.length)], false);
                trees++;
            } else if (flowerCount < 4) {
                above.setType(flowers[this.random.nextInt(flowers.length)], false);
                flowerCount++;
            }
        }
    }

    /**
     * Cancels a site, refunding {@code fraction} of its cost. A build that already changed the world is cleaned up
     * afterwards (blocks removed, terrain restored) by a free "restore" site.
     */
    public long cancel(final ConstructionSite site, final double fraction, final String messageKey) {
        if (this.data().site(site.id) != site) {
            return 0; // already cancelled or finished (stale object): never a second refund
        }
        final SiteRuntime rt = this.runtime(site);
        this.runtimes.remove(site.id);
        final World w = Bukkit.getWorld(site.world);
        if (rt != null) {
            rt.crew.remove();
        }
        if (w != null) {
            this.removeScaffolding(site, w);
            this.removeCampfire(site, w);
        }
        this.removeStorage(site);
        pl.medivalrpg.ludnosc.logic.CancellationRules.returnBuffer(site, this.data().state(site.countryId));
        this.data().removeSite(site.id);
        long refund = QueueRules.refund(site.cost, false, fraction);
        refund = this.refund(site.countryId, refund, "Ludnosc: zwrot za budowę " + site.type);
        final boolean started = site.phase > Phase.TERRAIN.ordinal() || site.cursor > 0;
        if (site.is(ConstructionSite.BUILD) && site.repairOf == null && started && rt != null && rt.tpl != null) {
            final ConstructionSite restore = this.newSite(site.countryId, ConstructionSite.RESTORE, site.type, site.world,
                site.originX, site.originY, site.originZ, site.rotation, 0, "porządki", site.dye);
            restore.terrain = site.terrain;
            restore.phase = Phase.RESTORE_CELLS.ordinal();
            restore.validated = true;
            this.data().putSite(restore);
        }
        if (messageKey != null) {
            this.plugin.notifyCountry(site.countryId, messageKey, Placeholder.parsed("name", this.displayName(site.type)),
                Placeholder.unparsed("refund", this.plugin.formatMoney(refund)));
        }
        return refund;
    }

    /** Country disbanded: sites and queue are dropped without refund (there is no treasury any more). */
    public void countryRemoved(final UUID country) {
        this.data().clearQueue(country);
        for (final ConstructionSite s : this.data().sitesOf(country)) {
            final SiteRuntime rt = this.runtimes.remove(s.id);
            if (rt != null) {
                rt.crew.remove();
            }
            final World w = Bukkit.getWorld(s.world);
            if (w != null) {
                this.removeScaffolding(s, w);
                this.removeCampfire(s, w);
            }
            this.data().removeSite(s.id);
        }
    }

    public void chunkChanged(final String world, final int cx, final int cz, final UUID oldOwner) {
        if (oldOwner == null) {
            return;
        }
        final ChunkRef ref = new ChunkRef(world, cx, cz);
        for (final QueuedOrder o : this.data().queue(oldOwner)) {
            final BlockBox box = this.box(o);
            if (box != null && box.chunks().contains(ref)) {
                this.cancelQueued(o, "queue-lost");
            }
        }
        for (final ConstructionSite s : this.data().sitesOf(oldOwner)) {
            final BlockBox box = this.box(s);
            final boolean lost = s.anchorChunk().equals(ref) || box != null && box.chunks().contains(ref);
            if (lost && !s.is(ConstructionSite.RESTORE)) {
                this.cancel(s, this.cfg().refundOnCancel, "site-cancelled");
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public String displayName(final String type) {
        if ("droga".equals(type)) {
            return "Droga";
        }
        if (WALL_TYPE.equals(type)) {
            return "Mur miejski";
        }
        if (WALL_REMOVE_TYPE.equals(type)) {
            return "Rozbiórka starego muru";
        }
        if ("niwelacja".equals(type)) {
            return "Wyrównanie terenu";
        }
        if (VillageService.VILLAGE_HOUSE.equals(type)) {
            return "Dom wiejski";
        }
        if (pl.medivalrpg.ludnosc.logic.PlayerBuildingRules.isPlayerType(type) && this.plugin.playerBuildings() != null) {
            return this.plugin.playerBuildings().typeName(type);
        }
        final BuildingTemplate t = this.plugin.templates().get(type);
        return t == null ? type : t.meta().displayName();
    }

    private String displayPlain(final String type) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(this.plugin.messages().parse(this.displayName(type)));
    }

    public int percent(final ConstructionSite site) {
        final SiteRuntime rt = this.runtime(site);
        if (rt == null) {
            return 0;
        }
        final double[] weights = {15, 5, 70, 3, 7};
        double done = 0;
        double total = 0;
        for (int p = 0; p < weights.length; p++) {
            total += weights[p];
            if (site.phase > p) {
                done += weights[p];
            } else if (site.phase == p) {
                final int size = switch (Phase.values()[p]) {
                    case TERRAIN -> site.terrain == null ? 1 : Math.max(1, site.terrain.size());
                    case FOUNDATION -> rt.tpl == null ? 1 : Math.max(1, rt.tpl.foundation().size());
                    case CELLS -> rt.tpl == null ? 1 : Math.max(1, rt.tpl.order().length);
                    case ENTRANCE -> rt.entrance == null ? 1 : Math.max(1, rt.entrance.size());
                    case ROAD -> rt.road == null ? Math.max(1, site.road().size()) : Math.max(1, rt.road.size());
                    default -> 1;
                };
                done += weights[p] * Math.min(1.0, site.cursor / (double) size);
            }
        }
        if (site.is(ConstructionSite.REPAIR) || site.is(ConstructionSite.UPGRADE) || site.is(ConstructionSite.DEMOLISH)) {
            final int size = rt.tpl == null ? 1 : Math.max(1, rt.tpl.order().length);
            return site.phase > Phase.CELLS.ordinal() ? 100 : (int) Math.min(100, site.cursor * 100L / size);
        }
        if (site.phase >= Phase.FINISH.ordinal()) {
            return 100;
        }
        return (int) Math.min(100, Math.round(done * 100 / total));
    }

    /** What players see about a site in {@code /ludnosc budowy}. */
    public record SiteView(ConstructionSite site, String name, String stage, int percent, int builders, int carrying,
                           String status, Map<String, Long> needs, Map<String, Integer> cut) {
    }

    /** Sites of a country with their stage, crew and what they wait for (main thread). */
    public List<SiteView> views(final UUID country) {
        final List<SiteView> out = new ArrayList<>();
        for (final ConstructionSite site : this.data().sitesOf(country)) {
            final SiteRuntime rt = this.runtime(site);
            if (rt == null) {
                continue;
            }
            int carrying = 0;
            for (final BuilderCrew.Body b : rt.crew.bodies) {
                if (b.fetching) {
                    carrying++;
                }
            }
            if (rt.needs.isEmpty() && this.needsMaterials(site, rt)) {
                rt.needs = this.remainingNeeds(site, rt);
                rt.needsAt = System.currentTimeMillis();
            }
            out.add(new SiteView(site, this.displayPlain(site.type), this.stage(site, rt), this.percent(site),
                rt.crew.bodies.size(), carrying, rt.status, Map.copyOf(rt.needs), Map.copyOf(site.materials())));
        }
        return out;
    }

    /** Polish name of the current stage: earthworks, foundation, then what the next template cell is (walls, roof...). */
    private String stage(final ConstructionSite site, final SiteRuntime rt) {
        if (site.is(ConstructionSite.ROAD)) {
            return "układanie drogi";
        }
        if (site.is(ConstructionSite.LEVEL)) {
            return "wyrównywanie terenu";
        }
        if (site.is(ConstructionSite.RESTORE)) {
            return "sprzątanie po anulowanej budowie";
        }
        if (site.is(ConstructionSite.DEMOLISH)) {
            return "rozbiórka od dachu";
        }
        return switch (Phase.values()[Math.min(site.phase, Phase.values().length - 1)]) {
            case TERRAIN -> "niwelacja terenu i wykop";
            case FOUNDATION -> "fundament";
            case CELLS -> {
                if (rt.phases == null || rt.tpl == null || site.cursor >= rt.tpl.order().length) {
                    yield "wykończenie";
                }
                yield switch (rt.phases[rt.tpl.order()[site.cursor]]) {
                    case EXCAVATE -> "wybieranie ziemi z obrysu";
                    case FOUNDATION -> "parter i piwnica";
                    case FRAME -> "konstrukcja: słupy i belki";
                    case WALL -> "ściany";
                    case FLOOR -> "stropy i podłogi";
                    case ROOF -> "dach";
                    case OPENING -> "okna, drzwi i okiennice";
                    case INTERIOR -> "wyposażenie wnętrza";
                    case DETAIL -> "detale i oświetlenie";
                };
            }
            case ENTRANCE -> "schody wejściowe";
            case ROAD -> "dojście i droga";
            default -> "kończenie";
        };
    }

    public BlockBox box(final ConstructionSite site) {
        final SiteRuntime rt = this.runtime(site);
        return rt == null ? null : rt.box();
    }

    /** Site whose box contains the location, if any. */
    public ConstructionSite siteAt(final Location loc) {
        for (final ConstructionSite s : this.data().allSites()) {
            final BlockBox box = this.box(s);
            if (box != null && box.contains(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                return s;
            }
        }
        return null;
    }
}
