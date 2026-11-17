// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Gate;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.config.WorkSettings;
import pl.medivalrpg.ludnosc.logic.JobBoard;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Profession/distance job assignment and visible citizen work. Farmers and fishermen use physical output stores;
 * missing/full/unavailable storage stops production before any source changes. ProductionStorageService commits
 * complete drops with exact main-thread revalidation. Legacy carried food PDC is only settled, never minted anew.
 * Citizen tasks are budgeted and run only in loaded places near players; Zycie still owns the broad daily schedule.
 */
public final class WorkService implements Listener {

    /** Ticks between two runs. */
    public static final long PERIOD = 5L;
    private static final long BOARD_MILLIS = 20_000L;
    private static final long FIELD_SCAN_MILLIS = 15_000L;
    private static final long GATE_OPEN_MILLIS = 12_000L;

    /** Runtime state of one working villager. */
    static final class Worker {
        UUID country;
        JobBoard.Job job;
        UUID workplace;
        UUID workingAt;
        String state = "odpoczywa";
        int[] crop;
        int fails;
        long pathTick;
        int carried;
        double carriedFood;
        long tripSince;
        Location tripTarget;
        long nextAction;
        long lastSeen;
        Material held;
        Material catchHeld;
    }

    /** Cache of one field: ripe crops and gates. */
    private static final class Field {
        long scannedAt;
        String world;
        final List<int[]> ripe = new ArrayList<>();
        final List<int[]> gates = new ArrayList<>();
    }

    /** Live counters (for {@code /ludnosc admin praca}). */
    public record Stats(int boards, int workers, int harvestsMinute, int deliveriesMinute, int pathsMinute, int catchesMinute,
                        double lastMs, double maxMs, int overBudget) {
    }

    /** Work summary of a country. */
    public record Summary(Map<JobBoard.Job, Integer> workers, Map<JobBoard.Job, Integer> slots, Map<JobBoard.Job, Integer> places,
                          int adults, int assigned, long foodToday, long foodCap, long fishToday, long fishCap) {
    }

    private final LudnoscPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey foodKey;
    private final NamespacedKey basketKey;
    private final Random random = new Random();
    private final Map<UUID, JobBoard.Assignment> boards = new ConcurrentHashMap<>();
    private final Map<UUID, List<JobBoard.Workplace>> places = new ConcurrentHashMap<>();
    /** Board generation per country: an asynchronous result older than a synchronous recompute is dropped. */
    private final Map<UUID, Long> boardGen = new ConcurrentHashMap<>();
    private final Map<UUID, Long> boardTime = new HashMap<>();
    private final Set<UUID> computing = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<UUID[]> rotation = new ArrayDeque<>();
    private final Map<UUID, Worker> workers = new HashMap<>();
    private final Map<UUID, Field> fields = new HashMap<>();
    private final Map<UUID, Long> activeAt = new HashMap<>();
    private final Map<BlockPos, Long> openGates = new HashMap<>();
    private final Map<World, List<Location>> players = new HashMap<>();
    private long ticks;
    private long refilledAt;
    private int harvests;
    private int deliveries;
    private int paths;
    private int catches;
    private Stats last = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    private double maxMs;
    private int overBudget;

    public WorkService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "work_item");
        this.foodKey = new NamespacedKey(plugin, "work_food");
        this.basketKey = new NamespacedKey(plugin, "work_basket");
    }

    private WorkSettings ws() {
        return this.plugin.settings().work;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    // ------------------------------------------------------------------ job boards

    /** The assignment of a country (empty until computed). */
    public JobBoard.Assignment board(final UUID country) {
        return this.boards.getOrDefault(country, JobBoard.Assignment.EMPTY);
    }

    /** Computes the board of a country now on the main thread (daily tick right after a start). */
    public JobBoard.Assignment ensureBoard(final UUID country) {
        final JobBoard.Assignment a = this.boards.get(country);
        if (a != null) {
            return a;
        }
        final var in = this.inputs(country);
        final JobBoard.Assignment out = JobBoard.assign(in.citizens(), in.places(), this.ws().professions);
        this.boardGen.merge(country, 1L, Long::sum);
        this.boards.put(country, out);
        this.places.put(country, in.places());
        this.boardTime.put(country, System.currentTimeMillis());
        return out;
    }

    /** Drops the cached board and computes it again now (season change, admin, trials). */
    public JobBoard.Assignment recompute(final UUID country) {
        this.boards.remove(country);
        return this.ensureBoard(country);
    }

    /** Workers at a workplace building (e.g. miners of a mine). */
    public int workersAt(final UUID country, final UUID building) {
        return this.board(country).workersAt(building);
    }

    private record Inputs(List<JobBoard.Citizen> citizens, List<JobBoard.Workplace> places) {
    }

    /** Snapshot of the registry (main thread: reads the mine records). */
    private Inputs inputs(final UUID country) {
        final List<JobBoard.Citizen> citizens = new ArrayList<>();
        for (final CitizenRecord r : this.data().citizensOf(country)) {
            if (!r.pending()) {
                citizens.add(new JobBoard.Citizen(r.entityId(), r.profession(), r.adult(), r.x(), r.z(), r.world()));
            }
        }
        final List<JobBoard.Workplace> list = new ArrayList<>();
        final boolean winter = this.winterIndoors();
        for (final Building b : this.data().buildingsOf(country)) {
            final JobBoard.Job job = jobOf(b.function());
            if (job == null || b.registered() || winter && job == JobBoard.Job.FARMER) {
                continue; // in winter the farmers work in a workshop or the granary (citizens' life), not in the fields
            }
            final int slots = job == JobBoard.Job.MINER
                ? this.ws().mineYield.slots(this.plugin.mines().levelOf(country, b.id()))
                : this.ws().slots.getOrDefault(job, 0);
            if (slots > 0) {
                list.add(new JobBoard.Workplace(b.id(), job, slots, b.box().centerX(), b.box().centerZ(), b.box().world()));
            }
        }
        return new Inputs(citizens, list);
    }

    /**
     * Winter (server season {@code zima}) with {@code life.work.winter-farmers-indoors}: no field work. Rain changes
     * nothing. Without seasons or the citizens' life the fields stay open all year.
     */
    public boolean winterIndoors() {
        final var seasons = this.plugin.seasons();
        final var life = this.plugin.life();
        return seasons != null && seasons.enabled() && life != null && life.enabled()
            && !pl.medivalrpg.ludnosc.logic.Workplaces.fieldsOpen(seasons.state().season(), life.settings().winterIndoors());
    }

    /** The job a building gives work in (null = none). */
    public static JobBoard.Job jobOf(final BuildingFunction f) {
        if (f == null) {
            return null;
        }
        return switch (f) {
            case FARM -> JobBoard.Job.FARMER;
            case MINE -> JobBoard.Job.MINER;
            case QUARRY -> JobBoard.Job.QUARRYMAN;
            case SAWMILL -> JobBoard.Job.WOODCUTTER;
            case HARBOR -> JobBoard.Job.FISHER;
            default -> null;
        };
    }

    /** One due country per run: inputs on the main thread, the assignment off it. */
    private void refreshBoards(final long now) {
        UUID due = null;
        long oldest = Long.MAX_VALUE;
        for (final KrajInfo k : this.plugin.kraje().getCountries()) {
            if (this.computing.contains(k.id())) {
                continue;
            }
            final long t = this.boardTime.getOrDefault(k.id(), 0L);
            if (now - t >= BOARD_MILLIS && t < oldest) {
                oldest = t;
                due = k.id();
            }
        }
        // forget countries that no longer exist
        if (this.ticks % 240 == 0) {
            this.boards.keySet().removeIf(id -> this.plugin.kraje().getCountry(id).isEmpty());
            this.places.keySet().retainAll(this.boards.keySet());
            this.boardTime.keySet().retainAll(this.boards.keySet());
            this.boardGen.keySet().removeIf(id -> !this.boards.containsKey(id) && !this.computing.contains(id));
        }
        if (due == null) {
            return;
        }
        final UUID country = due;
        this.boardTime.put(country, now);
        final Inputs in = this.inputs(country);
        if (in.places().isEmpty() || in.citizens().isEmpty()) {
            this.boards.put(country, JobBoard.Assignment.EMPTY);
            this.places.put(country, in.places());
            return;
        }
        final Map<JobBoard.Job, List<String>> professions = this.ws().professions;
        final long gen = this.boardGen.merge(country, 1L, Long::sum);
        this.computing.add(country);
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            try {
                final JobBoard.Assignment a = JobBoard.assign(in.citizens(), in.places(), professions);
                // applied on the main thread, and only if no newer board (season change, admin recompute) replaced
                // it meanwhile and the country still exists
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    this.computing.remove(country);
                    if (this.boardGen.getOrDefault(country, 0L) == gen && this.plugin.kraje().getCountry(country).isPresent()) {
                        this.boards.put(country, a);
                        this.places.put(country, in.places());
                    }
                });
            } catch (final RuntimeException e) {
                this.computing.remove(country); // plugin disabled meanwhile, or the assignment failed
                if (!(e instanceof org.bukkit.plugin.IllegalPluginAccessException)) {
                    this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Tablica pracy: przeliczenie nieudane", e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ tick

    /** Every {@link #PERIOD} ticks. */
    public void tick() {
        this.ticks++;
        final long started = System.nanoTime();
        final long now = System.currentTimeMillis();
        this.players.clear();
        if (this.ticks % 240 == 0) { // every minute
            this.last = new Stats(this.boards.size(), this.workers.size(), this.harvests, this.deliveries, this.paths, this.catches,
                this.last.lastMs(), this.maxMs, this.overBudget);
            this.harvests = 0;
            this.deliveries = 0;
            this.paths = 0;
            this.catches = 0;
            this.maxMs = 0;
            this.overBudget = 0;
            this.workers.values().removeIf(w -> now - w.lastSeen > 120_000L);
            this.activeAt.values().removeIf(t -> now - t > 120_000L);
            this.fields.keySet().removeIf(id -> this.data().building(id) == null);
        }
        this.refreshBoards(now);
        if (!this.ws().enabled) {
            this.closeGates(now);
            return;
        }
        if (now - this.refilledAt > 2000L) {
            this.refill();
            this.refilledAt = now;
        }
        final long budget = (long) (this.ws().budgetMs * 1_000_000L);
        int processed = 0;
        int lookups = 0;
        final int max = this.ws().citizensPerTick;
        while (processed < max && lookups < max * 4 && !this.rotation.isEmpty()) {
            if (System.nanoTime() - started > budget) {
                this.overBudget++;
                break;
            }
            final UUID[] e = this.rotation.pollFirst();
            lookups++;
            final Entity entity = Bukkit.getEntity(e[0]);
            if (!(entity instanceof Villager v) || !v.isValid()) {
                continue; // unloaded / gone: dropped until the next refill
            }
            this.rotation.addLast(e);
            processed++;
            try {
                this.process(v, e[1], now);
            } catch (final RuntimeException ex) {
                this.plugin.getLogger().log(java.util.logging.Level.FINE, "Praca obywatela", ex);
            }
        }
        if (this.ticks % 4 == 0) {
            this.closeGates(now);
        }
        final double ms = (System.nanoTime() - started) / 1_000_000.0;
        this.maxMs = Math.max(this.maxMs, ms);
        this.last = new Stats(this.last.boards(), this.last.workers(), this.last.harvestsMinute(), this.last.deliveriesMinute(),
            this.last.pathsMinute(), this.last.catchesMinute(), ms, this.last.maxMs(), this.last.overBudget());
    }

    private void refill() {
        for (final var it = this.workers.entrySet().iterator(); it.hasNext();) {
            final var entry = it.next();
            final Worker worker = entry.getValue();
            final CitizenRecord record = this.data().citizen(entry.getKey());
            if (record != null && worker.country.equals(record.countryId()) && record.adult()
                && this.board(worker.country).workplaceOf().containsKey(entry.getKey())) {
                continue;
            }
            final Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Villager villager) {
                if (worker.carriedFood > 0 && this.plugin.kraje().getCountry(worker.country).isPresent()) {
                    this.deposit(villager, worker, worker.country, false);
                }
                villager.getPersistentDataContainer().remove(this.foodKey);
                villager.getPersistentDataContainer().remove(this.basketKey);
                this.clearHeld(villager, worker);
            }
            it.remove();
        }
        final Set<UUID> paused = new java.util.HashSet<>();
        this.boards.keySet().forEach(country -> {
            if (this.plugin.royalRebuild() != null && this.plugin.royalRebuild().blocks(country)) {
                paused.add(country);
            }
        });
        pl.medivalrpg.ludnosc.logic.WorkRotation.refresh(this.rotation, this.boards, paused);
    }

    private boolean playerNear(final Location at) {
        final double r2 = (double) this.ws().playerRange * this.ws().playerRange;
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

    // ------------------------------------------------------------------ one villager

    private void process(final Villager v, final UUID country, final long now) {
        final CitizenRecord record = this.data().citizen(v.getUniqueId());
        if (record == null || !country.equals(record.countryId()) || !record.adult()) {
            this.clearHeld(v, this.workers.remove(v.getUniqueId()));
            return;
        }
        final JobBoard.Assignment a = this.board(country);
        final UUID place = a.workplaceOf().get(v.getUniqueId());
        final Worker wk = this.workers.computeIfAbsent(v.getUniqueId(), k -> {
            final Worker restored = new Worker();
            restored.carriedFood = v.getPersistentDataContainer().getOrDefault(this.foodKey, PersistentDataType.DOUBLE, 0.0);
            restored.carried = v.getPersistentDataContainer().getOrDefault(this.basketKey, PersistentDataType.INTEGER, 0);
            return restored;
        });
        wk.lastSeen = now;
        wk.country = country;
        if (place == null) {
            if (wk.carriedFood > 0) {
                this.deposit(v, wk, country, false);
            }
            this.clearHeld(v, wk);
            this.workers.remove(v.getUniqueId());
            return;
        }
        wk.job = a.jobOf().get(v.getUniqueId());
        if (!java.util.Objects.equals(wk.workplace, place)) {
            wk.crop = null;
            wk.tripTarget = null;
            wk.tripSince = 0;
        }
        wk.workplace = place;
        final Location loc = v.getLocation();
        final World w = v.getWorld();
        final boolean near = this.playerNear(loc);
        final long time = w.getTime();
        final boolean work = this.ws().workTime(time);
        if (wk.carriedFood > 0 && (!near || !work && !this.ws().deliveryTime(time))) {
            this.deposit(v, wk, country, false); // nobody watches / night: the harvest reaches the granary anyway
            return;
        }
        if (!near) {
            wk.state = "poza zasięgiem graczy (praca liczona wirtualnie)";
            return;
        }
        if (v.isSleeping() || v.isTrading() || v.isLeashed() || v.isInsideVehicle()) {
            wk.state = "zajęty (sen / handel / smycz)";
            return;
        }
        if (wk.carriedFood > 0 && (wk.carried >= this.ws().basket || !work)) {
            this.deliver(v, wk, country, now);
            return;
        }
        if (!work) {
            this.clearHeld(v, wk);
            wk.state = "po pracy";
            wk.crop = null;
            return;
        }
        final Building b = this.workplaceNear(country, wk, loc);
        if (b == null || !country.equals(b.countryId()) || !b.box().world().equals(w.getName())) {
            wk.state = "brak miejsca pracy";
            return;
        }
        wk.workingAt = b.id();
        if (!this.plugin.production().ready(b)) { wk.state=this.plugin.production().status(b); return; }
        if (outside(b.box(), loc) > 10) {
            wk.state = "idzie do pracy";
            if (!this.plugin.zycie().available() && this.ticks >= wk.pathTick) {
                wk.pathTick = this.ticks + 12;
                this.paths++;
                final Location to = this.standNear(w, b.box());
                if (to != null) {
                    v.getPathfinder().moveTo(to, 0.6);
                }
            }
            return;
        }
        this.activeAt.put(b.id(), now);
        if (wk.job == JobBoard.Job.FARMER) {
            this.farm(v, wk, b, w, country, now);
        } else {
            this.craft(v, wk, b, w, country, now);
        }
    }

    /** The assigned workplace, or another one of the same job the villager already stands at (Zycie walks to the nearest). */
    private Building workplaceNear(final UUID country, final Worker wk, final Location loc) {
        final Building own = this.data().building(wk.workplace);
        if (own != null && outside(own.box(), loc) <= 10) {
            return own;
        }
        for (final JobBoard.Workplace p : this.places.getOrDefault(country, List.of())) {
            if (p.job() != wk.job || p.id().equals(wk.workplace)) {
                continue;
            }
            final Building b = this.data().building(p.id());
            if (b != null && b.box().world().equals(loc.getWorld().getName()) && outside(b.box(), loc) <= 4) {
                return b;
            }
        }
        return own;
    }

    /** Horizontal distance from a location to a box (0 inside); other worlds are far away. */
    static double outside(final BlockBox box, final Location l) {
        if (!box.world().equals(l.getWorld().getName())) {
            return Double.MAX_VALUE;
        }
        final double dx = Math.max(0, Math.max(box.minX() - l.getX(), l.getX() - (box.maxX() + 1)));
        final double dz = Math.max(0, Math.max(box.minZ() - l.getZ(), l.getZ() - (box.maxZ() + 1)));
        return Math.hypot(dx, dz);
    }

    // ------------------------------------------------------------------ farmers

    private void farm(final Villager v, final Worker wk, final Building b, final World w, final UUID country, final long now) {
        if (wk.carried == 0) {
            this.hold(v, wk, Material.IRON_HOE);
        }
        final Field f = this.field(b, w, now);
        if (wk.crop != null && (!w.isChunkLoaded(wk.crop[0] >> 4, wk.crop[2] >> 4)
            || !ripe(w.getBlockAt(wk.crop[0], wk.crop[1], wk.crop[2])))) {
            wk.crop = null;
        }
        if (wk.crop == null) {
            f.ripe.removeIf(c -> !w.isChunkLoaded(c[0] >> 4, c[2] >> 4));
            wk.crop = nearest(f.ripe, v.getLocation());
            wk.fails = 0;
        }
        if (wk.crop == null) {
            wk.state = "dogląda pola";
            if (now >= wk.nextAction) {
                wk.nextAction = now + 4000 + this.random.nextInt(5000);
                v.lookAt(new Location(w, b.box().centerX() + 0.5, b.box().minY() + 1, b.box().centerZ() + 0.5));
                v.swingMainHand();
            }
            return;
        }
        final Location crop = new Location(w, wk.crop[0] + 0.5, wk.crop[1] + 0.2, wk.crop[2] + 0.5);
        final double dx = crop.getX() - v.getLocation().getX();
        final double dz = crop.getZ() - v.getLocation().getZ();
        if (dx * dx + dz * dz > 2.4 * 2.4 || Math.abs(crop.getY() - v.getLocation().getY()) > 2.5) {
            wk.state = "idzie do dojrzałych upraw";
            if (this.ticks >= wk.pathTick) {
                wk.pathTick = this.ticks + 8;
                this.openGates(f, now);
                this.paths++;
                if (!v.getPathfinder().moveTo(crop, 0.55) && ++wk.fails >= 3) {
                    f.ripe.removeIf(c -> c[0] == wk.crop[0] && c[1] == wk.crop[1] && c[2] == wk.crop[2]);
                    wk.crop = null;
                }
            }
            return;
        }
        // harvest and sow again at once
        final Block blk = w.getBlockAt(wk.crop[0], wk.crop[1], wk.crop[2]);
        final int[] cell = wk.crop;
        wk.crop = null;

        if (!(blk.getBlockData() instanceof Ageable crop2) || !ripe(blk)) {
            return;
        }
        final var drops=this.plugin.production().drops(blk,new ItemStack(Material.IRON_HOE),true);
        crop2.setAge(0);
        this.plugin.production().produce(b,List.of(ProductionStorageService.Change.of(blk,crop2.getAsString())),drops,List.of(),() -> {
            this.hold(v,wk,cropItem(blk.getType()));
            wk.state="złożył plony w magazynie";
            this.harvests++;
        });
    }

    static boolean ripe(final Block b) {
        return b.getBlockData() instanceof Ageable a
            && (b.getType()==Material.WHEAT || b.getType()==Material.CARROTS || b.getType()==Material.POTATOES || b.getType()==Material.BEETROOTS)
            && a.getAge() >= a.getMaximumAge();
    }

    static Material cropItem(final Material crop) {
        return switch (crop) {
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT;
            default -> Material.WHEAT;
        };
    }

    private static int[] nearest(final List<int[]> cells, final Location l) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (final int[] c : cells) {
            final double d = (c[0] + 0.5 - l.getX()) * (c[0] + 0.5 - l.getX()) + (c[2] + 0.5 - l.getZ()) * (c[2] + 0.5 - l.getZ());
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** Ripe crops and gates of a field (its box only, at most every 15 s per field). */
    private Field field(final Building b, final World w, final long now) {
        final Field f = this.fields.computeIfAbsent(b.id(), k -> new Field());
        if (now - f.scannedAt < FIELD_SCAN_MILLIS) {
            return f;
        }
        f.scannedAt = now;
        f.world = w.getName();
        f.ripe.clear();
        f.gates.clear();
        final BlockBox box = b.box();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                final boolean edge = x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ();
                for (int y = box.minY(); y <= Math.min(box.maxY(), box.minY() + 3); y++) {
                    final Block blk = w.getBlockAt(x, y, z);
                    if (edge && blk.getBlockData() instanceof Gate) {
                        f.gates.add(new int[] {x, y, z});
                    } else if (ripe(blk)) {
                        f.ripe.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return f;
    }

    /** Opens the gates of a field for a while (only Ludnosc field buildings; closed again when nobody stands in them). */
    private void openGates(final Field f, final long now) {
        if (!this.ws().openFieldGates) {
            return;
        }
        final World w = f.world == null ? null : Bukkit.getWorld(f.world);
        if (w == null) {
            return;
        }
        for (final int[] g : f.gates) {
            if (!w.isChunkLoaded(g[0] >> 4, g[2] >> 4)) {
                continue;
            }
            final Block blk = w.getBlockAt(g[0], g[1], g[2]);
            if (!(blk.getBlockData() instanceof Gate gate)) {
                continue;
            }
            final BlockPos pos = new BlockPos(f.world, g[0], g[1], g[2]);
            if (!gate.isOpen()) {
                if (!this.openGates.containsKey(pos) && this.openGates.size() >= 256) {
                    continue; // bounded
                }
                gate.setOpen(true);
                blk.setBlockData(gate, false);
                w.playSound(blk.getLocation(), Sound.BLOCK_FENCE_GATE_OPEN, 0.5f, 1f);
                this.openGates.put(pos, now + GATE_OPEN_MILLIS);
            } else if (this.openGates.containsKey(pos)) {
                this.openGates.put(pos, now + GATE_OPEN_MILLIS); // opened by us: keep it open a while longer
            }
        }
    }

    private void closeGates(final long now) {
        // only gates Ludnosc opened itself are closed (a gate a player left open stays open)
        for (final Iterator<Map.Entry<BlockPos, Long>> it = this.openGates.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<BlockPos, Long> e = it.next();
            final BlockPos p = e.getKey();
            final World w = p.world() == null ? null : Bukkit.getWorld(p.world());
            if (w == null || !w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                it.remove();
                continue;
            }
            final Block blk = w.getBlockAt(p.x(), p.y(), p.z());
            if (!(blk.getBlockData() instanceof Gate gate)) {
                it.remove();
                continue;
            }
            if (e.getValue() > now) {
                continue;
            }
            if (!w.getNearbyEntities(blk.getLocation().add(0.5, 0.5, 0.5), 1.0, 1.0, 1.0, en -> en instanceof Villager).isEmpty()) {
                e.setValue(now + 2000L);
                continue;
            }
            if (gate.isOpen()) {
                gate.setOpen(false);
                blk.setBlockData(gate, false);
                w.playSound(blk.getLocation(), Sound.BLOCK_FENCE_GATE_CLOSE, 0.5f, 1f);
            }
            it.remove();
        }
    }

    // ------------------------------------------------------------------ delivery

    private void deliver(final Villager v, final Worker wk, final UUID country, final long now) {
        if (wk.tripSince == 0) {
            wk.tripSince = now;
            wk.tripTarget = this.granaryTarget(country, v.getLocation());
            wk.pathTick = 0;
            // leaving a field: open its gate
            final Building at = wk.workingAt == null ? null : this.data().building(wk.workingAt);
            if (at != null && at.function() == BuildingFunction.FARM) {
                final Field f = this.fields.get(at.id());
                if (f != null) {
                    this.openGates(f, now);
                }
            }
        }
        if (wk.tripTarget == null) {
            this.deposit(v, wk, country, true); // no granary in reach: handed over at the field (a cart takes it)
            return;
        }
        if (now - wk.tripSince > this.ws().deliveryTimeoutSeconds * 1000L) {
            this.deposit(v, wk, country, false);
            return;
        }
        final Location l = v.getLocation();
        if (l.getWorld().equals(wk.tripTarget.getWorld()) && l.distanceSquared(wk.tripTarget) <= 3.5 * 3.5) {
            this.deposit(v, wk, country, true);
            return;
        }
        wk.state = "niesie plony do spichlerza";
        if (this.ticks >= wk.pathTick) {
            wk.pathTick = this.ticks + 4;
            this.paths++;
            v.getPathfinder().moveTo(wk.tripTarget, 0.6);
        }
    }

    /** Nearest granary building (or marked granary chest) within {@code work.granary-range}. */
    private Location granaryTarget(final UUID country, final Location from) {
        final double max2 = (double) this.ws().granaryRange * this.ws().granaryRange;
        Location best = null;
        double bestD = max2;
        for (final Building b : this.data().buildingsOf(country)) {
            if (b.function() != BuildingFunction.GRANARY || !b.box().world().equals(from.getWorld().getName())) {
                continue;
            }
            final Location c = this.standNear(from.getWorld(), b.box());
            if (c != null && c.distanceSquared(from) < bestD) {
                bestD = c.distanceSquared(from);
                best = c;
            }
        }
        if (best != null) {
            return best;
        }
        final CountryState st = this.data().stateIfPresent(country);
        if (st != null) {
            for (final BlockPos p : st.chests()) {
                if (!from.getWorld().getName().equals(p.world())) {
                    continue;
                }
                final Location c = new Location(from.getWorld(), p.x() + 0.5, p.y(), p.z() + 0.5);
                if (c.distanceSquared(from) < bestD) {
                    bestD = c.distanceSquared(from);
                    best = c;
                }
            }
        }
        return best;
    }

    /** The carried harvest goes into the granary (daily cap per farmer, granary capacity). */
    private void deposit(final Villager v, final Worker wk, final UUID country, final boolean visible) {
        final long food = (long) Math.floor(wk.carriedFood + 1e-9);
        final long credited = this.creditFood(country, food);
        v.getPersistentDataContainer().remove(this.foodKey);
        v.getPersistentDataContainer().remove(this.basketKey);
        if (visible && this.plugin.settings().effects) {
            final Location l = v.getLocation().add(0, 1, 0);
            v.swingMainHand();
            v.getWorld().playSound(l, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 0.8f, 1f);
            v.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, l, 6, 0.4, 0.4, 0.4, 0);
        }
        if (credited > 0) {
            this.deliveries++;
        }
        this.clearHeld(v, wk);
        wk.carried = 0;
        wk.carriedFood = 0;
        wk.tripSince = 0;
        wk.tripTarget = null;
        wk.state = "oddał plony (" + credited + " jedzenia)";
    }

    /** Adds food from visible work up to today's cap ({@code work.harvest-cap-per-farmer} × farmers). Returns the amount. */
    public long creditFood(final UUID country, final long food) {
        if (food <= 0) {
            return 0;
        }
        final CountryState st = this.data().state(country);
        this.rollDay(st);
        final long cap = (long) Math.max(1, this.board(country).workers(JobBoard.Job.FARMER)) * this.ws().harvestCapPerFarmer;
        final long credited = Math.max(0, Math.min(food, cap - st.workFood));
        if (credited > 0) {
            st.workFood += credited;
            this.plugin.economy().adjustFood(country, credited);
            this.data().markDirty();
        }
        return credited;
    }

    private void rollDay(final CountryState st) {
        final long today = java.time.LocalDate.now(this.plugin.settings().dailyZone).toEpochDay();
        if (st.workDay != today) {
            st.workDay = today;
            st.workFood = 0;
            st.workFish = 0;
        }
    }

    // ------------------------------------------------------------------ other jobs

    private void craft(final Villager v, final Worker wk, final Building b, final World w, final UUID country, final long now) {
        final boolean smithy = this.data().buildingsOf(country).stream().anyMatch(x -> x.function() == BuildingFunction.SMITHY);
        final Material tool = switch (wk.job) {
            case MINER, QUARRYMAN -> smithy || this.plugin.postep().era(country) >= 2 ? Material.IRON_PICKAXE : Material.STONE_PICKAXE;
            case WOODCUTTER -> Material.IRON_AXE;
            case FISHER -> Material.FISHING_ROD;
            default -> Material.AIR;
        };
        if (wk.catchHeld == null) {
            this.hold(v, wk, tool);
        }
        wk.state = switch (wk.job) {
            case MINER -> "pracuje przy szybie kopalni";
            case QUARRYMAN -> "kuje kamień";
            case WOODCUTTER -> "rąbie drewno przy tartaku";
            case FISHER -> "łowi ryby";
            default -> "pracuje";
        };
        if (now < wk.nextAction) {
            return;
        }
        wk.nextAction = now + 3000 + this.random.nextInt(4000);
        if (wk.catchHeld != null) {
            wk.catchHeld = null;
            this.hold(v, wk, tool);
        }
        final BlockBox box = b.box();
        final Location focus = new Location(w, box.centerX() + 0.5, box.minY() + 1, box.centerZ() + 0.5);
        if (this.random.nextInt(4) == 0) {
            final Location to = this.standNear(w, box);
            if (to != null) {
                this.paths++;
                v.getPathfinder().moveTo(to, 0.5);
            }
            return;
        }
        v.lookAt(focus);
        v.swingMainHand();
        if (this.plugin.settings().effects) {
            final Sound s = switch (wk.job) {
                case MINER, QUARRYMAN -> Sound.BLOCK_STONE_HIT;
                case WOODCUTTER -> Sound.BLOCK_WOOD_HIT;
                case FISHER -> Sound.ENTITY_FISHING_BOBBER_SPLASH;
                default -> Sound.BLOCK_GRAVEL_HIT;
            };
            w.playSound(v.getLocation(), s, 0.5f, 0.9f + this.random.nextFloat() * 0.2f);
        }
        if (wk.job == JobBoard.Job.FISHER && this.random.nextDouble() < 0.3) {
            final CountryState st = this.data().state(country);
            this.rollDay(st);
            final long cap = (long) Math.max(1, this.board(country).workers(JobBoard.Job.FISHER)) * this.ws().fishCapPerFisher;
            if (st.workFish < cap) {
                final Material fish=this.random.nextBoolean() ? Material.COD : Material.SALMON;
                this.plugin.production().produce(b,List.of(),List.of(new ItemStack(fish)),List.of(),() -> {
                    st.workFish++; wk.catchHeld=fish; this.hold(v,wk,fish); this.catches++; wk.state="złożył rybę w magazynie";
                    this.data().markDirty();
                });
            }
        }
    }

    /** A standable spot on the ground just outside a box (random side), loaded chunks only. */
    private Location standNear(final World w, final BlockBox box) {
        for (int k = 0; k < 6; k++) {
            final int side = this.random.nextInt(4);
            final int x = switch (side) {
                case 0 -> box.minX() - 1;
                case 1 -> box.maxX() + 1;
                default -> box.minX() + this.random.nextInt(Math.max(1, box.sizeX()));
            };
            final int z = switch (side) {
                case 2 -> box.minZ() - 1;
                case 3 -> box.maxZ() + 1;
                default -> box.minZ() + this.random.nextInt(Math.max(1, box.sizeZ()));
            };
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            final int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y <= box.maxY() + 2 && WorldOps.standable(w.getBlockAt(x, y, z))) {
                return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ held items

    private boolean ours(final ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(this.itemKey, PersistentDataType.BYTE);
    }

    private void hold(final Villager v, final Worker wk, final Material m) {
        final EntityEquipment eq = v.getEquipment();
        if (eq == null || m == null || m.isAir()) {
            return;
        }
        final ItemStack cur = eq.getItemInMainHand();
        final boolean empty = cur == null || cur.getType().isAir();
        if (!empty && !this.ours(cur)) {
            return; // a trade offer, a levy weapon of Zycie...: never replaced
        }
        if (!empty && cur.getType() == m) {
            return;
        }
        final ItemStack item = new ItemStack(m);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(this.itemKey, PersistentDataType.BYTE, (byte) 1));
        eq.setItemInMainHand(item);
        eq.setItemInMainHandDropChance(0f);
        wk.held = m;
    }

    private void clearHeld(final Villager v, final Worker wk) {
        final EntityEquipment eq = v.getEquipment();
        if (eq != null && this.ours(eq.getItemInMainHand())) {
            eq.setItemInMainHand(null);
        }
        if (wk != null) {
            wk.held = null;
            wk.catchHeld = null;
        }
    }

    /** Work items left in the hands of villagers loaded from disk are removed (they get them again at work). */
    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent e) {
        for (final Entity en : e.getEntities()) {
            if (en instanceof Villager v && !this.workers.containsKey(v.getUniqueId())) {
                final EntityEquipment eq = v.getEquipment();
                if (eq != null && this.ours(eq.getItemInMainHand())) {
                    eq.setItemInMainHand(null);
                }
            }
        }
    }
    /** Vanilla farmer AI must not harvest behind the physical inventory transaction. */
    @EventHandler(priority=org.bukkit.event.EventPriority.HIGH,ignoreCancelled=true)
    public void onVanillaHarvest(final org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if(e.getEntity() instanceof Villager && this.data().citizen(e.getEntity().getUniqueId())!=null
            && (Tag.CROPS.isTagged(e.getBlock().getType()) || e.getBlock().getType()==Material.FARMLAND)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ info

    /** A citizen of the country works at this building right now (visible work in the last minute). */
    public boolean citizenWorking(final UUID building) {
        final Long t = this.activeAt.get(building);
        return t != null && System.currentTimeMillis() - t < 60_000L;
    }

    public Stats stats() {
        return this.last;
    }

    public Summary summary(final UUID country) {
        final JobBoard.Assignment a = this.board(country);
        final Map<JobBoard.Job, Integer> workersBy = new EnumMap<>(JobBoard.Job.class);
        final Map<JobBoard.Job, Integer> slotsBy = new EnumMap<>(JobBoard.Job.class);
        final Map<JobBoard.Job, Integer> placesBy = new EnumMap<>(JobBoard.Job.class);
        for (final JobBoard.Job j : JobBoard.Job.values()) {
            workersBy.put(j, a.workers(j));
            slotsBy.put(j, 0);
            placesBy.put(j, 0);
        }
        for (final JobBoard.Workplace p : this.places.getOrDefault(country, List.of())) {
            slotsBy.merge(p.job(), p.slots(), Integer::sum);
            placesBy.merge(p.job(), 1, Integer::sum);
        }
        int adults = 0;
        for (final CitizenRecord r : this.data().citizensOf(country)) {
            if (r.adult()) {
                adults++;
            }
        }
        final CountryState st = this.data().stateIfPresent(country);
        final long today = java.time.LocalDate.now(this.plugin.settings().dailyZone).toEpochDay();
        final boolean current = st != null && st.workDay == today;
        return new Summary(workersBy, slotsBy, placesBy, adults, a.workplaceOf().size(), current ? st.workFood : 0,
            (long) Math.max(1, a.workers(JobBoard.Job.FARMER)) * this.ws().harvestCapPerFarmer, current ? st.workFish : 0,
            (long) Math.max(1, a.workers(JobBoard.Job.FISHER)) * this.ws().fishCapPerFisher);
    }

    /** Debug lines of one villager (Polish, plain values) for {@code /ludnosc admin praca}. */
    public List<String> describe(final Villager v) {
        final List<String> out = new ArrayList<>();
        final CitizenRecord rec = this.data().citizen(v.getUniqueId());
        if (rec == null) {
            out.add("to nie jest obywatel");
            return out;
        }
        final JobBoard.Assignment a = this.board(rec.countryId());
        final JobBoard.Job job = a.jobOf().get(v.getUniqueId());
        final UUID place = a.workplaceOf().get(v.getUniqueId());
        final Building b = place == null ? null : this.data().building(place);
        out.add("kraj: " + this.plugin.countryName(rec.countryId()) + ", zawód: " + rec.profession() + (rec.adult() ? "" : " (dziecko)"));
        out.add("praca: " + (job == null ? "brak przydziału" : job.title()) + (b == null ? ""
            : " • " + pl.medivalrpg.ludnosc.config.Messages.mini().stripTags(this.plugin.construction().displayName(b.type())) + " " + b.id().toString().substring(0, 8) + " • "
            + Math.round(outside(b.box(), v.getLocation())) + " bl."));
        final Worker wk = this.workers.get(v.getUniqueId());
        if (wk == null) {
            out.add("stan: jeszcze nie obsłużony (kolejka, brak gracza w pobliżu albo brak przydziału)");
        } else {
            out.add("stan: " + wk.state + " • niesie: " + wk.carried + " zbiorów (" + String.format(java.util.Locale.ROOT, "%.1f", wk.carriedFood)
                + " jedzenia)" + (wk.crop == null ? "" : " • cel: " + wk.crop[0] + " " + wk.crop[1] + " " + wk.crop[2])
                + (wk.tripTarget == null ? "" : " • spichlerz: " + wk.tripTarget.getBlockX() + " " + wk.tripTarget.getBlockY() + " "
                + wk.tripTarget.getBlockZ()));
            out.add("w ręce: " + (wk.held == null ? "nic" : wk.held.getKey().getKey()) + " • ostatnio "
                + (System.currentTimeMillis() - wk.lastSeen) / 1000 + " s temu");
        }
        final long t = v.getWorld().getTime();
        out.add("czas MC " + t + ": " + (this.ws().workTime(t) ? "godziny pracy" : this.ws().deliveryTime(t) ? "odnoszenie plonów"
            : "po pracy") + " • Zycie: " + (this.plugin.zycie().available() ? "steruje planem dnia" : "brak (Ludnosc prowadzi do pracy)"));
        return out;
    }

    /** Removes work items from the hands of loaded workers (plugin disable). */
    public void shutdown() {
        for (final Map.Entry<UUID, Worker> e : this.workers.entrySet()) {
            final Entity en = Bukkit.getEntity(e.getKey());
            if (en instanceof Villager v && v.isValid()) {
                if (e.getValue().carriedFood > 0) {
                    this.deposit(v, e.getValue(), e.getValue().country, false);
                }
                this.clearHeld(v, e.getValue());
            }
        }
        this.workers.clear();
        // gates opened by the workers are closed again
        for (final BlockPos p : this.openGates.keySet()) {
            final World w = p.world() == null ? null : Bukkit.getWorld(p.world());
            if (w != null && w.isChunkLoaded(p.x() >> 4, p.z() >> 4)
                && w.getBlockAt(p.x(), p.y(), p.z()).getBlockData() instanceof Gate gate && gate.isOpen()) {
                gate.setOpen(false);
                w.getBlockAt(p.x(), p.y(), p.z()).setBlockData(gate, false);
            }
        }
        this.openGates.clear();
    }
}
