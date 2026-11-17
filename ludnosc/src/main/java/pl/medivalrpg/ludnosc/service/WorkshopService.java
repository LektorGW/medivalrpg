// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.config.WorkshopSettings;
import pl.medivalrpg.ludnosc.logic.MaterialNeeds;
import pl.medivalrpg.ludnosc.logic.Workshops;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * The village workshops of the production chains — glassworks, brickworks, sheepfold and the smithy. One visible
 * stand-in worker per workshop (like the stonemason of a quarry) works only in loaded chunks with a player nearby,
 * within a time budget per run:
 * <ul>
 *   <li>the glassworks digs natural sand (beaches, dunes) and the brickworks clay (river banks and beds) on the
 *       country's own land — never in a player's plan zone, a protected area, near the spawn, next to buildings or roads,
 *       never a hole into a cave, never a new stream next to water, the shore lowered layer by layer, limited per day;</li>
 *   <li>the kilns smelt what lies in the workshop's real store with real coal / charcoal (logs become charcoal when
 *       nothing else burns);</li>
 *   <li>the sheepfold keeps a flock of real sheep in its pen (founding pair paid by the order, lambs from two wheat),
 *       shears them and presses hay bales from field wheat;</li>
 *   <li>the smithy smelts the mines' raw iron and makes torches.</li>
 * </ul>
 * Every step is one {@link ProductionStorageService#produce} commit (world change + exact items), so a full or missing
 * store stops the work before anything is dug, and nothing is ever created without its inputs.
 */
public final class WorkshopService implements Listener {

    /** Service period in game ticks. */
    public static final long PERIOD = 10L;
    private static final double DIG_REACH = 3.5;
    /** A stand spot is up to two blocks from the sheep (diagonal ≈ 2.9) and the sheep keeps grazing. */
    private static final double SHEAR_REACH = 3.5;

    /** Runtime of one workshop (not saved; the daily counters and fuel are in {@link CountryState#workshops()}). */
    static final class Runtime {
        final BuilderCrew crew = new BuilderCrew();
        long nextAction;
        String status = "czeka na pierwszą zmianę";
        String last;
        long lastAt;
        int[] target;
        boolean targetWet;
        UUID sheep;
        long breedAt;
        int flock = -1;
        // sand / clay survey
        final List<int[]> candidates = new ArrayList<>();
        final List<int[]> found = new ArrayList<>();
        int scanIndex = -1;
        long scannedAt;
        boolean surveyed;
        // sheep pen
        List<int[]> pen;
        long penAt;
        Location kiln;
        boolean kilnSearched;
        final Map<String, Long> failSince = new HashMap<>();
        long seen;
        /** A kiln (glassworks / brickworks) with raw material but no coal or charcoal: fuel goes here first. */
        boolean hungry;
        long walkSince;
    }

    private final LudnoscPlugin plugin;
    private final NamespacedKey flockKey;
    private final NamespacedKey shearedKey;
    private final Map<UUID, Runtime> runtimes = new HashMap<>();
    private final ArrayDeque<UUID> rotation = new ArrayDeque<>();
    private final Map<UUID, Set<String>> demanded = new HashMap<>();
    private final Random random = new Random();
    private long demandedAt;
    private long ticks;
    private java.lang.reflect.Method protectedAreas;
    private java.lang.reflect.Method isProtected;

    public WorkshopService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        this.flockKey = new NamespacedKey(plugin, "flock");
        this.shearedKey = new NamespacedKey(plugin, "sheared_at");
    }

    private WorkshopSettings cfg() {
        return this.plugin.settings().workshops;
    }

    private Workshops.Params params() {
        final WorkshopSettings c = this.cfg();
        return new Workshops.Params(c.batch, c.smeltsPerFuel, c.brickStock, c.terracottaStock, c.torchStock, c.hayStock, c.smithy);
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** A building whose work this service does (smithy only with {@code workshops.smithy.enabled}). */
    public boolean handles(final Building b) {
        return b != null && b.countryId() != null && !b.registered() && Workshops.workshop(b.function())
            && (b.function() != BuildingFunction.SMITHY || this.cfg().smithy);
    }

    // ------------------------------------------------------------------ ticking

    /** Every {@link #PERIOD} ticks (main thread). */
    public void tick() {
        this.ticks++;
        final long now = System.currentTimeMillis();
        if (!this.cfg().enabled) {
            if (!this.runtimes.isEmpty()) {
                this.shutdown();
            }
            return;
        }
        if (this.ticks % 20 == 1 || this.rotation.isEmpty()) {
            final Set<UUID> live = new HashSet<>();
            for (final Building b : this.data().allBuildings()) {
                if (this.handles(b)) {
                    live.add(b.id());
                    if (!this.rotation.contains(b.id())) {
                        this.rotation.addLast(b.id());
                    }
                }
            }
            this.rotation.removeIf(id -> !live.contains(id));
            for (final var it = this.runtimes.entrySet().iterator(); it.hasNext();) {
                final var e = it.next();
                if (!live.contains(e.getKey())) {
                    e.getValue().crew.remove();
                    it.remove();
                }
            }
        }
        if (now - this.demandedAt > 10_000L) {
            this.demandedAt = now;
            this.demanded.clear();
            for (final ConstructionService.MaterialDemand d : this.plugin.construction().materialDemands()) {
                this.demanded.computeIfAbsent(d.country(), k -> new HashSet<>()).add(d.group());
            }
        }
        final long started = System.nanoTime();
        final long budget = (long) (this.cfg().budgetMs * 1_000_000L);
        for (int n = 0; n < Math.min(this.cfg().perTick, this.rotation.size()); n++) {
            if (System.nanoTime() - started > budget) {
                break;
            }
            final UUID id = this.rotation.pollFirst();
            this.rotation.addLast(id);
            final Building b = this.data().building(id);
            if (b == null || !this.handles(b)) {
                continue;
            }
            final Runtime rt = this.runtimes.computeIfAbsent(id, k -> new Runtime());
            try {
                this.process(b, rt, now);
            } catch (final RuntimeException e) {
                rt.target = null;
                rt.sheep = null;
                this.plugin.getLogger().log(java.util.logging.Level.FINE, "Zakład " + id, e);
            }
        }
        // walking workers check their target every run, cheaply
        for (final Map.Entry<UUID, Runtime> e : this.runtimes.entrySet()) {
            final Runtime rt = e.getValue();
            if (rt.target != null || rt.sheep != null) {
                final Building b = this.data().building(e.getKey());
                if (b != null) {
                    try {
                        this.reach(b, rt, now);
                    } catch (final RuntimeException ex) {
                        rt.target = null;
                        rt.sheep = null;
                    }
                }
            }
        }
    }

    /** Builders' walking also moves the workshop workers (every 2 ticks). */
    public void move(final double step) {
        for (final Runtime rt : this.runtimes.values()) {
            if (!rt.crew.bodies.isEmpty()) {
                rt.crew.move(step * 0.7);
            }
        }
    }

    public boolean isWorker(final Entity e) {
        for (final Runtime rt : this.runtimes.values()) {
            if (rt.crew.contains(e.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    public void shutdown() {
        for (final Runtime rt : this.runtimes.values()) {
            rt.crew.remove();
        }
        this.runtimes.clear();
        this.rotation.clear();
    }

    private boolean playerNear(final World w, final Building b) {
        if (!this.cfg().requirePlayer) {
            return true;
        }
        final double r = this.cfg().playerRange;
        final double cx = b.box().centerX() + 0.5;
        final double cz = b.box().centerZ() + 0.5;
        for (final Player p : w.getPlayers()) {
            if (p.getGameMode() != org.bukkit.GameMode.SPECTATOR && Math.abs(p.getX() - cx) <= r && Math.abs(p.getZ() - cz) <= r) {
                return true;
            }
        }
        return false;
    }

    private int[] state(final Building b) {
        final int[] st = this.data().state(b.countryId()).workshops().computeIfAbsent(b.id().toString(), k -> new int[5]);
        final int today = (int) java.time.LocalDate.now(this.plugin.settings().dailyZone).toEpochDay();
        if (st[0] != today) {
            st[0] = today;
            st[1] = 0;
            this.data().markDirty();
        }
        return st;
    }

    private static String role(final BuildingFunction f) {
        return switch (f) {
            case GLASSWORKS -> "Hutnik";
            case BRICKWORKS -> "Ceglarz";
            case SHEEPFOLD -> "Owczarz";
            default -> "Kowal";
        };
    }

    private static Material tool(final BuildingFunction f) {
        return switch (f) {
            case GLASSWORKS, BRICKWORKS -> Material.IRON_SHOVEL;
            case SHEEPFOLD -> Material.SHEARS;
            default -> Material.IRON_PICKAXE;
        };
    }

    private Location home(final World w, final Building b, final Runtime rt) {
        if (!rt.kilnSearched) {
            rt.kilnSearched = true;
            final BlockBox box = b.box();
            for (int y = box.minY(); y <= Math.min(box.maxY(), box.minY() + 4) && rt.kiln == null; y++) {
                for (int x = box.minX(); x <= box.maxX() && rt.kiln == null; x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        final Material m = w.getBlockAt(x, y, z).getType();
                        if (m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER || m == Material.LOOM) {
                            rt.kiln = new Location(w, x + 0.5, y + 0.5, z + 0.5);
                            break;
                        }
                    }
                }
            }
        }
        final Location at = rt.kiln != null ? WorldOps.standSpot(w, rt.kiln.getBlock(), null)
            : new Location(w, b.box().centerX() + 0.5, b.box().minY() + 1, b.box().centerZ() + 0.5);
        final int y = w.getHighestBlockYAt(at.getBlockX(), at.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        return rt.kiln != null ? at : new Location(w, at.getBlockX() + 0.5, y, at.getBlockZ() + 0.5);
    }

    private void idle(final Runtime rt, final String status) {
        rt.crew.remove();
        rt.target = null;
        rt.sheep = null;
        rt.status = status;
    }

    private void process(final Building b, final Runtime rt, final long now) {
        rt.seen = now;
        final World w = Bukkit.getWorld(b.box().world());
        // the whole footprint (not only its centre): the kiln search reads every column of the building
        if (w == null || !ConstructionService.chunksLoaded(w, b.box())) {
            this.idle(rt, "teren niewczytany — praca tylko przy graczach");
            return;
        }
        if (!this.playerNear(w, b)) {
            this.idle(rt, "brak graczy w pobliżu — praca tylko przy graczach");
            return;
        }
        if (!this.plugin.settings().workTime(w.getTime())) {
            this.idle(rt, "po pracy (noc)");
            return;
        }
        if (this.plugin.royalRebuild() != null && this.plugin.royalRebuild().blocks(b.countryId())
            || this.plugin.construction().repairing(b.id())) {
            this.idle(rt, "wstrzymane (przebudowa lub naprawa budynku)");
            return;
        }
        if (rt.crew.bodies.isEmpty()) {
            rt.crew.ensure(this.plugin, b.id(), this.home(w, b, rt), 1, role(b.function()));
            for (final BuilderCrew.Body body : rt.crew.bodies) {
                if (body.entity() instanceof LivingEntity le) {
                    le.customName(net.kyori.adventure.text.Component.text(role(b.function()), net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .append(net.kyori.adventure.text.Component.text(" " + this.plugin.citizens().nextName(),
                            net.kyori.adventure.text.format.NamedTextColor.WHITE)));
                    if (le.getEquipment() != null) {
                        le.getEquipment().setItemInMainHand(new ItemStack(tool(b.function())));
                    }
                }
            }
        }
        rt.crew.prune();
        if (b.function() == BuildingFunction.GLASSWORKS || b.function() == BuildingFunction.BRICKWORKS) {
            this.survey(b, rt, w, now);
        }
        if (now < rt.nextAction || rt.target != null || rt.sheep != null) {
            return;
        }
        if (this.plugin.production().pending() >= this.cfg().maxPending) {
            rt.nextAction = now + 1000L; // other workplaces' commits first
            return;
        }
        rt.nextAction = now + this.cfg().actionSeconds * 1000L;
        // the store first, silently: a smithy of a country without any store must not alert its rulers every turn
        final ProductionStorageService.StoreView store = this.plugin.production().storeOf(b);
        if (store == null || !this.plugin.production().ready(b)) {
            rt.status = this.plugin.production().status(b);
            return;
        }
        if (this.plugin.production().shared(b.countryId(), store.binding())) {
            // working from the warehouse: a slower pace keeps it free for the haulers
            rt.nextAction = now + Math.max(this.cfg().actionSeconds, this.cfg().sharedStoreSeconds) * 1000L;
        }
        final Map<String, Integer> stock = counts(store.inventory());
        final int[] st = this.state(b);
        final boolean hayFirst = b.function() == BuildingFunction.SHEEPFOLD && Workshops.next(BuildingFunction.SHEEPFOLD, stock, st[2],
            this.demanded.getOrDefault(b.countryId(), Set.of()), this.params()).step() != null;
        if (b.function() == BuildingFunction.SHEEPFOLD && this.flock(b, rt, w, stock, st, now, hayFirst)) {
            return; // the shepherd's step (founding, lamb, shearing) was this turn
        }
        final Workshops.Decision d = Workshops.next(b.function(), stock, st[2], this.demanded.getOrDefault(b.countryId(), Set.of()),
            this.params(), b.function() == BuildingFunction.SMITHY && this.kilnsHungry(b.countryId(), now));
        if (b.function() == BuildingFunction.GLASSWORKS || b.function() == BuildingFunction.BRICKWORKS) {
            rt.hungry = d.step() != null ? d.step().kind() == Workshops.Kind.CHARCOAL
                : d.reason() != null && d.reason().contains("paliwa");
        }
        if (d.step() != null) {
            this.commit(b, rt, w, store.inventory(), d.step(), st);
            return;
        }
        if (b.function() == BuildingFunction.GLASSWORKS || b.function() == BuildingFunction.BRICKWORKS) {
            final boolean sand = b.function() == BuildingFunction.GLASSWORKS;
            final int quota = sand ? this.cfg().sandPerDay : this.cfg().clayPerDay;
            final boolean rawInStock = sand ? stock.getOrDefault("SAND", 0) + stock.getOrDefault("RED_SAND", 0) > 0
                : stock.getOrDefault("CLAY_BALL", 0) > 0;
            if (d.starving() != null && rawInStock) {
                this.fail(rt, d.starving(), now); // raw material lies there but cannot be fired (no fuel)
            }
            if (sand && (rawInStock || !rt.candidates.isEmpty())) {
                this.ok(rt, MaterialNeeds.SAND);
            }
            final String what = sand ? "piasku" : "gliny";
            if (st[1] < quota && this.pickDig(b, rt, w, sand)) {
                rt.status = (rawInStock && d.reason() != null ? d.reason() + "; " : "") + (sand ? "idzie kopać piasek" : "idzie kopać glinę");
                return;
            }
            final String why = st[1] >= quota ? "dzienny limit wydobycia " + what + " osiągnięty (" + quota + ")"
                : rt.surveyed ? "brak dostępnego " + what + " w promieniu " + this.cfg().digRadius + " bloków na ziemi kraju"
                : "szuka " + what + " w okolicy";
            rt.status = d.reason() == null ? why : d.reason() + (rawInStock ? "" : "; " + why);
            if (!rawInStock && (rt.surveyed || st[1] >= quota)) {
                for (final String g : Workshops.groups(b.function())) {
                    this.fail(rt, g, now);
                }
            }
            return;
        }
        rt.status = d.reason();
        if (d.starving() != null) {
            this.fail(rt, d.starving(), now);
        }
        this.stockOk(b, rt, stock);
    }

    /** A material already lying in the store counts as produced (sites may wait for the haulers). */
    private void stockOk(final Building b, final Runtime rt, final Map<String, Integer> stock) {
        if (b.function() == BuildingFunction.SMITHY) {
            if (stock.getOrDefault("IRON_INGOT", 0) > 0 || stock.getOrDefault("RAW_IRON", 0) > 0) {
                this.ok(rt, MaterialNeeds.IRON);
            }
            if (stock.getOrDefault("TORCH", 0) > 0) {
                this.ok(rt, MaterialNeeds.TORCH);
            }
        } else if (b.function() == BuildingFunction.SHEEPFOLD && stock.getOrDefault("HAY_BLOCK", 0) > 0) {
            this.ok(rt, MaterialNeeds.HAY);
        }
    }

    private static Map<String, Integer> counts(final Inventory inv) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final ItemStack i : inv.getStorageContents()) {
            if (i != null && !i.getType().isAir() && !i.hasItemMeta()) {
                out.merge(i.getType().name(), i.getAmount(), Integer::sum);
            }
        }
        return out;
    }

    private static List<ItemStack> items(final Map<String, Integer> m) {
        final List<ItemStack> out = new ArrayList<>();
        m.forEach((k, v) -> {
            final Material mat = Material.matchMaterial(k);
            int left = v;
            while (mat != null && left > 0) {
                final int n = Math.min(left, mat.getMaxStackSize());
                out.add(new ItemStack(mat, n));
                left -= n;
            }
        });
        return out;
    }

    /** One kiln / barn step: exact costs and outputs in one production commit; fuel left is saved on commit. */
    private void commit(final Building b, final Runtime rt, final World w, final Inventory inv, final Workshops.Step step, final int[] st) {
        final Map<String, Integer> have = counts(inv);
        for (final Map.Entry<String, Integer> e : step.costs().entrySet()) {
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) {
                rt.status = "brak surowca w magazynie (" + e.getKey().toLowerCase(java.util.Locale.ROOT) + ")";
                return;
            }
        }
        final int fuelAfter = step.fuelAfter();
        final String line = step.describe();
        final boolean started = this.plugin.production().produce(b, List.of(), items(step.outputs()), items(step.costs()), () -> {
            st[2] = fuelAfter;
            rt.last = line;
            rt.lastAt = System.currentTimeMillis();
            this.ok(rt, step.group());
            this.data().markDirty();
        });
        if (!started) {
            rt.status = this.plugin.production().status(b);
            return;
        }
        rt.status = step.kind().label();
        final BuilderCrew.Body body = rt.crew.bodies.isEmpty() ? null : rt.crew.bodies.getFirst();
        if (body != null) {
            body.target = this.home(w, b, rt);
            body.look = rt.kiln;
            rt.crew.swing(body);
        }
        if (this.plugin.settings().effects) {
            final Location at = rt.kiln != null ? rt.kiln : new Location(w, b.box().centerX() + 0.5, b.box().minY() + 1.5, b.box().centerZ() + 0.5);
            final Sound s = switch (step.kind()) {
                case HAY -> Sound.BLOCK_GRASS_PLACE;
                case TORCHES, IRON -> Sound.BLOCK_ANVIL_USE;
                default -> Sound.BLOCK_FURNACE_FIRE_CRACKLE;
            };
            w.playSound(at, s, 0.6f, 1f);
            if (step.kind() != Workshops.Kind.HAY) {
                w.spawnParticle(Particle.FLAME, at, 6, 0.3, 0.3, 0.3, 0.01);
                w.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1.5, 0), 4, 0.2, 0.4, 0.2, 0.01);
            }
        }
    }

    // ------------------------------------------------------------------ fuel priority

    /** A glassworks or brickworks of the country (seen in the last two minutes) waits for fuel. */
    public boolean kilnsHungry(final UUID country, final long now) {
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.function() != BuildingFunction.GLASSWORKS && b.function() != BuildingFunction.BRICKWORKS) {
                continue;
            }
            final Runtime rt = this.runtimes.get(b.id());
            if (rt != null && rt.hungry && now - rt.seen < 120_000L) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ capability

    private void ok(final Runtime rt, final String group) {
        if (group != null) {
            rt.failSince.remove(group);
        }
    }

    private void fail(final Runtime rt, final String group, final long now) {
        if (group != null) {
            rt.failSince.putIfAbsent(group, now);
        }
    }

    /**
     * The country's workshops can make the group now: true while any workshop of the right kind works or is not yet
     * known (never visited near a player), false when all of them have lacked raw material / fuel / sheep for longer
     * than {@code workshops.stall-grace-minutes} — the building sites then buy the material with the order's coins.
     */
    public boolean capable(final UUID country, final String group) {
        if (!this.cfg().enabled) {
            return false;
        }
        final long now = System.currentTimeMillis();
        final long grace = this.cfg().stallGraceMinutes * 60_000L;
        boolean any = false;
        for (final Building b : this.data().buildingsOf(country)) {
            if (!this.handles(b) || !Workshops.groups(b.function()).contains(group)) {
                continue;
            }
            any = true;
            final Runtime rt = this.runtimes.get(b.id());
            if (rt == null) {
                return true;
            }
            final Long since = rt.failSince.get(group);
            if (since == null || now - since < grace) {
                return true;
            }
        }
        return false; // no such workshop at all (any == false) or all of them starve
    }

    // ------------------------------------------------------------------ digging sand and clay

    /** Bounded survey of the surface around the workshop (a few hundred columns per run, loaded chunks only). */
    private void survey(final Building b, final Runtime rt, final World w, final long now) {
        final int r = this.cfg().digRadius;
        final int side = 2 * r + 1;
        final int total = side * side;
        if (rt.scanIndex < 0) {
            if (rt.scannedAt != 0 && now - rt.scannedAt < this.cfg().rescanSeconds * 1000L) {
                return;
            }
            rt.scanIndex = 0;
            rt.found.clear();
        }
        final boolean sand = b.function() == BuildingFunction.GLASSWORKS;
        final int cx = b.box().centerX();
        final int cz = b.box().centerZ();
        int budget = this.cfg().scanColumns;
        while (budget-- > 0 && rt.scanIndex < total) {
            final int x = cx - r + rt.scanIndex % side;
            final int z = cz - r + rt.scanIndex / side;
            rt.scanIndex++;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            final int y = w.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR);
            final Material m = w.getBlockAt(x, y, z).getType();
            if (sand ? m == Material.SAND || m == Material.RED_SAND : m == Material.CLAY) {
                rt.found.add(new int[] {x, y, z});
            }
        }
        if (rt.scanIndex >= total) {
            rt.found.sort(Comparator.comparingDouble(c -> Math.pow(c[0] - cx, 2) + Math.pow(c[2] - cz, 2)));
            rt.candidates.clear();
            rt.candidates.addAll(rt.found);
            rt.found.clear();
            rt.scanIndex = -1;
            rt.scannedAt = now;
            rt.surveyed = true;
        }
    }

    /** Chunk protected by Kraje (spawn and admin areas); unknown = protected (never dig when unsure). */
    private boolean protectedChunk(final String world, final int cx, final int cz) {
        try {
            final org.bukkit.plugin.Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
            if (kraje == null) {
                return false; // no Kraje areas at all (tests without Kraje are impossible: Ludnosc depends on it)
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

    /** Why a surface block may not be dug for the workshop; null = allowed. */
    String refuse(final Building b, final World w, final int x, final int y, final int z, final boolean sand, final List<BlockBox> near) {
        final UUID country = b.countryId();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return "niewczytany";
        }
        if (!this.plugin.kraje().getCountryAt(w.getName(), x >> 4, z >> 4).map(k -> k.id().equals(country)).orElse(false)) {
            return "poza ziemią kraju";
        }
        if (this.plugin.design().untouchable(country, x, z)) {
            return "strefa planu gracza";
        }
        if (this.protectedChunk(w.getName(), x >> 4, z >> 4)) {
            return "obszar chroniony";
        }
        final Location spawn = w.getSpawnLocation();
        if (Math.hypot(x - spawn.getX(), z - spawn.getZ()) < this.cfg().spawnRadius) {
            return "zbyt blisko spawnu";
        }
        if (inside(near, x, z, this.cfg().buildingMargin)) {
            return "przy budynku";
        }
        final CountryState st = this.data().stateIfPresent(country);
        if (st != null) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (st.roads().contains(RoadPlanner.key(x + dx, z + dz))) {
                        return "przy drodze";
                    }
                }
            }
        }
        final Block blk = w.getBlockAt(x, y, z);
        if (w.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR) != y) {
            return "już wykopane";
        }
        final Block up = blk.getRelative(0, 1, 0);
        final String above = up.getType() == Material.WATER ? "water" : up.isEmpty() ? "air" : "solid"; // a plant on top stays
        boolean wet = false;
        int lowest = Integer.MAX_VALUE;
        for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            final int nx = x + d[0];
            final int nz = z + d[1];
            if (!w.isChunkLoaded(nx >> 4, nz >> 4)) {
                return "skraj wczytanego terenu";
            }
            final Block side = w.getBlockAt(nx, y, nz);
            if (side.isLiquid() || side.getRelative(0, 1, 0).isLiquid() && "air".equals(above)) {
                wet = true;
            }
            lowest = Math.min(lowest, w.getHighestBlockYAt(nx, nz, HeightMap.OCEAN_FLOOR));
        }
        final Block below = blk.getRelative(0, -1, 0);
        final boolean solidBelow = below.getType().isSolid() && !below.isLiquid();
        return Workshops.diggable(sand ? "SAND" : "CLAY", blk.getType().name(), y, solidBelow, above, wet, lowest, w.getSeaLevel())
            ? null : "brzeg / dół / woda obok";
    }

    /** Picks the nearest allowed sand / clay block and sends the worker there. */
    private boolean pickDig(final Building b, final Runtime rt, final World w, final boolean sand) {
        if (rt.candidates.isEmpty()) {
            return false;
        }
        final List<BlockBox> near = this.plugin.construction().takenNear(w.getName(), b.box().centerX(), b.box().centerZ(),
            this.cfg().digRadius + 32, 2048);
        int tries = 0;
        for (final var it = rt.candidates.iterator(); it.hasNext() && tries < 24;) {
            final int[] c = it.next();
            tries++;
            if (this.refuse(b, w, c[0], c[1], c[2], sand, near) != null) {
                it.remove();
                continue;
            }
            rt.target = c;
            rt.targetWet = w.getBlockAt(c[0], c[1] + 1, c[2]).getType() == Material.WATER;
            final BuilderCrew.Body body = rt.crew.bodies.isEmpty() ? null : rt.crew.bodies.getFirst();
            if (body != null) {
                final Block blk = w.getBlockAt(c[0], c[1], c[2]);
                body.target = WorldOps.standSpot(w, blk, body.pos);
                body.look = blk.getLocation().add(0.5, 0.5, 0.5);
            }
            it.remove();
            return true;
        }
        return false;
    }

    /** The walking worker reached his target: dig the block or shear the sheep (one production commit). */
    private void reach(final Building b, final Runtime rt, final long now) {
        final BuilderCrew.Body body = rt.crew.bodies.isEmpty() ? null : rt.crew.bodies.getFirst();
        if (body == null) {
            rt.target = null;
            rt.sheep = null;
            return;
        }
        final World w = body.pos.getWorld();
        if (rt.sheep != null) {
            this.shearReach(b, rt, body, now);
            return;
        }
        final int[] c = rt.target;
        if (body.pos.distanceSquared(new Location(w, c[0] + 0.5, c[1] + 0.5, c[2] + 0.5)) > DIG_REACH * DIG_REACH) {
            if (rt.walkSince == 0) {
                rt.walkSince = now;
            } else if (now - rt.walkSince > 12_000L && body.target != null) {
                body.pos = body.target.clone(); // blocked on the way (fence, water...): steps over, as the builders do
            }
            if (now - rt.walkSince > 40_000L) {
                rt.target = null; // could not get there in time: another block next turn
                rt.walkSince = 0;
            }
            return;
        }
        rt.walkSince = 0;
        rt.target = null;
        final boolean sand = b.function() == BuildingFunction.GLASSWORKS;
        final List<BlockBox> near = this.plugin.construction().takenNear(w.getName(), c[0], c[2], 32, 2048);
        if (this.refuse(b, w, c[0], c[1], c[2], sand, near) != null || this.plugin.production().storeOf(b) == null
            || !this.plugin.production().ready(b)) {
            return;
        }
        final Block blk = w.getBlockAt(c[0], c[1], c[2]);
        final List<ItemStack> drops = this.plugin.production().drops(blk, new ItemStack(Material.IRON_SHOVEL), false);
        if (drops.isEmpty()) {
            return;
        }
        final int[] st = this.state(b);
        final String after = rt.targetWet ? "minecraft:water" : "minecraft:air";
        final String what = sand ? "piasek" : "glinę";
        rt.crew.swing(body);
        if (this.plugin.settings().effects) {
            w.playSound(blk.getLocation(), sand ? Sound.BLOCK_SAND_BREAK : Sound.BLOCK_GRAVEL_BREAK, 0.7f, 1f);
            w.spawnParticle(Particle.BLOCK, blk.getLocation().add(0.5, 0.8, 0.5), 8, 0.3, 0.2, 0.3, 0, blk.getBlockData());
        }
        final boolean started = this.plugin.production().produce(b, List.of(ProductionStorageService.Change.of(blk, after)), drops, List.of(),
            () -> {
                st[1]++;
                rt.last = "wykopał " + what + " (" + c[0] + " " + c[1] + " " + c[2] + ")";
                rt.lastAt = System.currentTimeMillis();
                for (final String g : Workshops.groups(b.function())) {
                    this.ok(rt, g);
                }
                this.data().markDirty();
            });
        rt.status = started ? "kopie " + what : this.plugin.production().status(b);
        rt.nextAction = now + this.cfg().actionSeconds * 1000L;
    }

    // ------------------------------------------------------------------ the sheepfold

    /** Grass of the pen under the open sky inside the building (largest connected patch), cached for a minute. */
    private List<int[]> pen(final Building b, final Runtime rt, final World w, final long now) {
        if (rt.pen != null && now - rt.penAt < 60_000L) {
            return rt.pen;
        }
        final BlockBox box = b.box();
        final Map<Long, int[]> cells = new HashMap<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                final int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (y < box.minY() - 1 || y > box.minY() + 2) {
                    continue;
                }
                final Block top = w.getBlockAt(x, y, z);
                if ((top.getType() == Material.GRASS_BLOCK || top.getType() == Material.DIRT) && top.getRelative(0, 1, 0).isPassable()
                    && top.getRelative(0, 2, 0).isPassable() && !top.getRelative(0, 1, 0).isLiquid()) {
                    cells.put(RoadPlanner.key(x, z), new int[] {x, y, z});
                }
            }
        }
        // largest 4-connected patch = the pen (verges and paths outside the fence are not part of it)
        final Set<Long> seen = new HashSet<>();
        List<int[]> best = List.of();
        for (final Long start : cells.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            final List<int[]> patch = new ArrayList<>();
            final ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                final int[] c = cells.get(queue.poll());
                patch.add(c);
                for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    final long k = RoadPlanner.key(c[0] + d[0], c[2] + d[1]);
                    if (cells.containsKey(k) && seen.add(k)) {
                        queue.add(k);
                    }
                }
            }
            if (patch.size() > best.size()) {
                best = patch;
            }
        }
        rt.pen = best;
        rt.penAt = now;
        return best;
    }

    private boolean ours(final Sheep s, final Building b) {
        return b.id().toString().equals(s.getPersistentDataContainer().get(this.flockKey, PersistentDataType.STRING));
    }

    private Sheep spawnSheep(final Building b, final World w, final List<int[]> pen, final boolean baby) {
        final int[] c = pen.get(this.random.nextInt(pen.size()));
        return w.spawn(new Location(w, c[0] + 0.5, c[1] + 1, c[2] + 0.5), Sheep.class, s -> {
            s.getPersistentDataContainer().set(this.flockKey, PersistentDataType.STRING, b.id().toString());
            s.setPersistent(true);
            s.setRemoveWhenFarAway(false);
            s.setColor(DyeColor.WHITE);
            if (baby) {
                s.setBaby();
            }
        });
    }

    /**
     * Flock upkeep and one shepherd step: strays are brought back, sheared sheep regrow their wool, an empty pen gets
     * its founding pair (paid by the order; again after {@code refound-days}), lambs cost two wheat, an unshorn sheep
     * is approached for shearing. True when this turn was used.
     */
    private boolean flock(final Building b, final Runtime rt, final World w, final Map<String, Integer> stock, final int[] st,
                          final long now, final boolean hayFirst) {
        final List<int[]> pen = this.pen(b, rt, w, now);
        if (pen.size() < 4) {
            rt.status = "brak zagrody z trawą pod gołym niebem w budynku";
            rt.flock = 0;
            this.fail(rt, MaterialNeeds.WOOL, now);
            return true;
        }
        final BlockBox box = b.box();
        final BoundingBox area = new BoundingBox(box.minX() - 8, box.minY() - 4, box.minZ() - 8, box.maxX() + 9, box.maxY() + 4, box.maxZ() + 9);
        final List<Sheep> flock = new ArrayList<>();
        for (final Entity e : w.getNearbyEntities(area, e -> e instanceof Sheep)) {
            final Sheep s = (Sheep) e;
            final String tag = s.getPersistentDataContainer().get(this.flockKey, PersistentDataType.STRING);
            final boolean inPen = box.contains(w.getName(), s.getLocation().getBlockX(), Math.max(box.minY(), Math.min(box.maxY(),
                s.getLocation().getBlockY())), s.getLocation().getBlockZ());
            if (tag == null && inPen && s.isAdult() && !s.isLeashed() && flock.size() < this.cfg().flock) {
                s.getPersistentDataContainer().set(this.flockKey, PersistentDataType.STRING, b.id().toString());
                s.setPersistent(true);
                s.setRemoveWhenFarAway(false);
                flock.add(s);
                rt.last = "do stada dołączyła owca z okolicy";
                rt.lastAt = now;
            } else if (tag != null && this.ours(s, b)) {
                flock.add(s);
                if (!inPen) {
                    final int[] c = pen.get(this.random.nextInt(pen.size()));
                    s.teleport(new Location(w, c[0] + 0.5, c[1] + 1, c[2] + 0.5));
                }
            }
        }
        final long regrow = this.cfg().regrowMinutes * 60_000L;
        int adults = 0;
        for (final Sheep s : flock) {
            if (s.isAdult()) {
                adults++;
            }
            if (s.isSheared()) {
                final long at = s.getPersistentDataContainer().getOrDefault(this.shearedKey, PersistentDataType.LONG, 0L);
                if (now - at >= regrow) {
                    s.setSheared(false);
                    s.getPersistentDataContainer().remove(this.shearedKey);
                }
            }
        }
        rt.flock = flock.size();
        if (flock.isEmpty()) {
            final int today = st[0];
            if (this.cfg().founders > 0 && (st[3] == 0 || this.cfg().refoundDays > 0 && today - st[4] >= this.cfg().refoundDays)) {
                for (int i = 0; i < this.cfg().founders; i++) {
                    this.spawnSheep(b, w, pen, false);
                }
                st[3] = 1;
                st[4] = today;
                this.data().markDirty();
                rt.status = "owczarz przygnał stado (" + this.cfg().founders + " owce)";
                rt.last = rt.status;
                rt.lastAt = now;
                this.ok(rt, MaterialNeeds.WOOL);
                return true;
            }
            rt.status = "stado puste — przyprowadź owce do zagrody (nowa para za "
                + Math.max(0, this.cfg().refoundDays - (today - st[4])) + " dni)";
            this.fail(rt, MaterialNeeds.WOOL, now);
            return false; // the barn may still press hay
        }
        this.ok(rt, MaterialNeeds.WOOL);
        if (hayFirst) {
            return false; // wheat for bales is there: the barn presses this turn, the flock waits a step
        }
        if (flock.size() < this.cfg().flock && adults >= 2 && now >= rt.breedAt && stock.getOrDefault("WHEAT", 0) >= 2) {
            rt.breedAt = now + this.cfg().breedMinutes * 60_000L;
            final boolean started = this.plugin.production().produce(b, List.of(), List.of(), List.of(new ItemStack(Material.WHEAT, 2)), () -> {
                final World ww = Bukkit.getWorld(b.box().world());
                if (ww != null && ww.isChunkLoaded(b.box().centerX() >> 4, b.box().centerZ() >> 4)) {
                    this.spawnSheep(b, ww, pen, true);
                    rt.last = "urodziło się jagnię (2 pszenicy)";
                    rt.lastAt = System.currentTimeMillis();
                }
            });
            if (started) {
                rt.status = "karmi owce pszenicą — będzie jagnię";
                if (this.plugin.settings().effects) {
                    final Sheep s = flock.getFirst();
                    w.spawnParticle(Particle.HEART, s.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0);
                }
                return true;
            }
        }
        Sheep next = null;
        for (final Sheep s : flock) {
            if (s.isAdult() && !s.isSheared() && s.isValid()) {
                next = s;
                break;
            }
        }
        if (next == null) {
            return false; // everyone shorn: the barn presses hay meanwhile
        }
        rt.sheep = next.getUniqueId();
        rt.walkSince = 0;
        rt.status = "idzie strzyc owcę";
        final BuilderCrew.Body body = rt.crew.bodies.isEmpty() ? null : rt.crew.bodies.getFirst();
        if (body != null) {
            body.target = WorldOps.standSpot(w, next.getLocation().getBlock(), body.pos);
            body.look = next.getLocation().add(0, 0.6, 0);
        }
        rt.nextAction = now;
        return true;
    }

    private void shearReach(final Building b, final Runtime rt, final BuilderCrew.Body body, final long now) {
        final Entity e = Bukkit.getEntity(rt.sheep);
        if (!(e instanceof Sheep s) || !s.isValid() || s.isSheared() || !s.isAdult() || !this.ours(s, b)) {
            rt.sheep = null;
            return;
        }
        final World w = s.getWorld();
        if (body.pos.getWorld() != w || body.pos.distanceSquared(s.getLocation()) > SHEAR_REACH * SHEAR_REACH) {
            body.target = WorldOps.standSpot(w, s.getLocation().getBlock(), body.pos);
            body.look = s.getLocation().add(0, 0.6, 0);
            if (rt.walkSince == 0) {
                rt.walkSince = now;
            } else if (now - rt.walkSince > 12_000L && body.pos.getWorld() == w) {
                body.pos = body.target.clone(); // the sheep keeps walking off / a fence is in the way: steps over to it
            }
            if (now - rt.walkSince > 30_000L) {
                rt.sheep = null;
                rt.walkSince = 0;
            }
            return;
        }
        rt.walkSince = 0;
        rt.sheep = null;
        rt.nextAction = now + this.cfg().actionSeconds * 1000L;
        if (this.plugin.production().storeOf(b) == null || !this.plugin.production().ready(b)) {
            rt.status = this.plugin.production().status(b);
            return;
        }
        final Material wool = Material.matchMaterial(s.getColor() == null ? "WHITE_WOOL" : s.getColor().name() + "_WOOL");
        final int n = 1 + this.random.nextInt(3);
        // sheared first: if the commit fails the wool is lost, never doubled
        s.setSheared(true);
        s.getPersistentDataContainer().set(this.shearedKey, PersistentDataType.LONG, now);
        final boolean started = this.plugin.production().produce(b, List.of(), List.of(new ItemStack(wool == null ? Material.WHITE_WOOL : wool, n)),
            List.of(), () -> {
                rt.last = "ostrzygł owcę (" + n + " wełny)";
                rt.lastAt = System.currentTimeMillis();
                this.ok(rt, MaterialNeeds.WOOL);
            });
        if (!started) {
            s.setSheared(false);
            s.getPersistentDataContainer().remove(this.shearedKey);
            rt.status = this.plugin.production().status(b);
            return;
        }
        rt.crew.swing(body);
        rt.status = "strzyże owce";
        if (this.plugin.settings().effects) {
            w.playSound(s.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 0.8f, 1f);
        }
    }

    /** The flock keeps its pen green (a sheep of a sheepfold does not turn the grass into dirt). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGraze(final EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Sheep s && s.getPersistentDataContainer().has(this.flockKey, PersistentDataType.STRING)
            && e.getBlock().getType() == Material.GRASS_BLOCK) {
            e.setCancelled(true);
            if (s.isSheared()) {
                // grazing regrows the wool as in vanilla; the timer of the shepherd is cleared
                s.getPersistentDataContainer().remove(this.shearedKey);
            }
        }
    }

    /** Sheep of a sheepfold that no longer exists become ordinary sheep. */
    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent e) {
        for (final Entity en : e.getEntities()) {
            if (en instanceof Sheep s) {
                final String tag = s.getPersistentDataContainer().get(this.flockKey, PersistentDataType.STRING);
                if (tag == null) {
                    continue;
                }
                Building b = null;
                try {
                    b = this.data().building(UUID.fromString(tag));
                } catch (final IllegalArgumentException ignored) {
                    // broken tag: released below
                }
                if (b == null || b.function() != BuildingFunction.SHEEPFOLD) {
                    s.getPersistentDataContainer().remove(this.flockKey);
                    s.getPersistentDataContainer().remove(this.shearedKey);
                }
            }
        }
    }

    // ------------------------------------------------------------------ haulers and status

    /** What the haulers should bring into the workshop's store now (see {@link Workshops#wants}). */
    public List<Workshops.Want> wants(final Building b, final Inventory store) {
        if (!this.cfg().enabled || !this.handles(b)) {
            return List.of();
        }
        final int[] st = this.data().state(b.countryId()).workshops().getOrDefault(b.id().toString(), new int[5]);
        final Runtime rt = this.runtimes.get(b.id());
        final int lambs = b.function() == BuildingFunction.SHEEPFOLD && rt != null && rt.flock >= 2 ? Math.max(0, this.cfg().flock - rt.flock) : 0;
        return Workshops.wants(b.function(), counts(store), st[2], this.demanded.getOrDefault(b.countryId(), Set.of()), this.params(), lambs,
            b.function() == BuildingFunction.SMITHY && this.kilnsHungry(b.countryId(), System.currentTimeMillis()));
    }

    /** Status line of a workshop (Polish). */
    public String status(final Building b) {
        final Runtime rt = this.runtimes.get(b.id());
        if (!this.cfg().enabled) {
            return "zakłady wyłączone (workshops.enabled: false)";
        }
        return rt == null ? "jeszcze nieodwiedzony — praca tylko przy graczach" : rt.status;
    }

    /** Last finished step of a workshop and how long ago (Polish), or null. */
    public String last(final Building b) {
        final Runtime rt = this.runtimes.get(b.id());
        if (rt == null || rt.last == null) {
            return null;
        }
        final long min = (System.currentTimeMillis() - rt.lastAt) / 60_000L;
        return rt.last + " (" + (min <= 0 ? "przed chwilą" : min + " min temu") + ")";
    }

    /** Sand / clay dug today, fuel smelts left, flock size (-1 = unknown). */
    public int[] counters(final Building b) {
        final int[] st = this.data().state(b.countryId()).workshops().getOrDefault(b.id().toString(), new int[5]);
        final int today = (int) java.time.LocalDate.now(this.plugin.settings().dailyZone).toEpochDay();
        final Runtime rt = this.runtimes.get(b.id());
        return new int[] {st[0] == today ? st[1] : 0, st[2], rt == null ? -1 : rt.flock};
    }

    /** Daily digging limit of the workshop (0 for those that do not dig). */
    public int quota(final Building b) {
        return b.function() == BuildingFunction.GLASSWORKS ? this.cfg().sandPerDay
            : b.function() == BuildingFunction.BRICKWORKS ? this.cfg().clayPerDay : 0;
    }
}
