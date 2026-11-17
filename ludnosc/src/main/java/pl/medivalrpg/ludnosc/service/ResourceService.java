// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.logic.QuarryPlan;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.TerrainView;
import pl.medivalrpg.ludnosc.planner.TreeFeller;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Physical sawmill/quarry/farm workers and legacy material compatibility. New output is committed through
 * ProductionStorageService; only historical resources and demolition salvage use CountryState.resources.
 * Main-thread consumers debit real inventories first. API readers use immutable published snapshots.
 */
public final class ResourceService {

    public static final String WOOD = "drewno";
    public static final String STONE = "kamien";
    public static final String ORE = "rudy";
    public static final String FISH = "ryby";
    /** Precious metal from mines (gold, diamonds, emeralds). */
    public static final String PRECIOUS = "kruszce";

    /** Ticks between two steps of the workers. */
    public static final long PERIOD = 10L;

    /** One lumberjack (per sawmill), runtime only. */
    private static final class Lumberjack {
        final BuilderCrew crew = new BuilderCrew();
        TreeFeller.Plan job;
        int cursor;
        long idleUntil;
        String log;
    }

    /** A farmer, stonemason or fisherman (per building), runtime only. */
    private static final class Worker {
        final BuilderCrew crew = new BuilderCrew();
        int[] target;
        long nextMove;
        long nextCut;
    }

    private final LudnoscPlugin plugin;
    private final Map<UUID, Lumberjack> jacks = new HashMap<>();
    private final Map<UUID, Worker> workers = new HashMap<>();
    private final Random random = new Random();
    private final pl.medivalrpg.ludnosc.logic.ResourceSnapshots snapshots=new pl.medivalrpg.ludnosc.logic.ResourceSnapshots();

    public ResourceService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        plugin.data().states().forEach((country,state) -> this.snapshots.publish(country,state.resources(),Map.of()));
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** Stock of a country (copy). */
    public Map<String, Long> stock(final UUID country) {
        if(!Bukkit.isPrimaryThread()) return this.stockSnapshot(country);
        final CountryState st = this.data().stateIfPresent(country);
        final Map<String,Long> result = new HashMap<>(st == null ? Map.of() : st.resources());
        this.plugin.production().stock(country).forEach((k,v) -> result.merge(k,v,Long::sum));
        return Map.copyOf(result);
    }
    public Map<String,Long> stockSnapshot(UUID country) { return this.snapshots.read(country); }
    public void publishStock(UUID country,Map<String,Long> physical) {
        if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Snapshot publication requires main thread");
        final var state=this.data().stateIfPresent(country);
        this.snapshots.publish(country,state==null ? Map.of() : state.resources(),physical);
    }

    public void add(final UUID country, final String resource, final long amount) {
        if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Resource mutation requires main thread");
        if (country == null || amount == 0) {
            return;
        }
        this.data().state(country).resources().merge(resource, amount, (a, b) -> Math.max(0, a + b));
        this.data().markDirty();
    }

    /** Half of the materials of a demolished building go back to the store. */
    public void salvage(final UUID country, final Material m) {
        if (this.random.nextBoolean()) {
            return;
        }
        if (Tag.LOGS.isTagged(m) || Tag.PLANKS.isTagged(m) || m.name().contains("WOOD") || m.name().endsWith("_SLAB")
            && m.name().contains("OAK")) {
            this.add(country, WOOD, 1);
        } else if (m.name().contains("STONE") || m.name().contains("BRICK") || m.name().contains("COBBLE") || m.name().contains("CONCRETE")
            || m.name().contains("ANDESITE") || m.name().contains("QUARTZ")) {
            this.add(country, STONE, 1);
        }
    }

    // ------------------------------------------------------------------ lumberjacks

    /** Every {@link #PERIOD} ticks. */
    public void tick() {
        final long now = System.currentTimeMillis();
        if (!this.plugin.settings().lumberjacks) {
            this.tickWorkers(now);
            return;
        }
        final java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (final Building b : this.data().allBuildings()) {
            if (b.function() != BuildingFunction.SAWMILL || b.countryId() == null) {
                continue;
            }
            seen.add(b.id());
            final World w = Bukkit.getWorld(b.box().world());
            final Lumberjack j = this.jacks.computeIfAbsent(b.id(), k -> new Lumberjack());
            // whole footprint, not only its centre: work scans / walks every column of it (no synchronous chunk load)
            if (w == null || !ConstructionService.chunksLoaded(w, b.box())
                || !this.plugin.settings().workTime(w.getTime())) {
                j.crew.remove();
                continue;
            }
            try {
                this.work(b, j, w, now);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().warning("Drwal " + b.id() + ": " + e);
                j.job = null;
            }
        }
        for (final var it = this.jacks.entrySet().iterator(); it.hasNext();) {
            final var e = it.next();
            if (!seen.contains(e.getKey())) {
                e.getValue().crew.remove();
                it.remove();
            }
        }
        this.tickWorkers(now);
    }

    /** Farmers harvest and replant ripe crops of the fields; stonemasons and fishermen work their quarry / pier. */
    private void tickWorkers(final long now) {
        final java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (final Building b : this.data().allBuildings()) {
            final BuildingFunction f = b.function();
            if (b.countryId() == null || f != BuildingFunction.FARM && f != BuildingFunction.QUARRY && f != BuildingFunction.HARBOR) {
                continue;
            }
            seen.add(b.id());
            final World w = Bukkit.getWorld(b.box().world());
            final Worker wk = this.workers.computeIfAbsent(b.id(), k -> new Worker());
            if (f == BuildingFunction.FARM && (!this.plugin.settings().work.standInWorkers || this.plugin.work().citizenWorking(b.id()))) {
                wk.crew.remove(); // a citizen farmer works this field himself: no stand-in
                continue;
            }
            if (w == null || !ConstructionService.chunksLoaded(w, b.box())
                || !this.plugin.settings().workTime(w.getTime()) || !this.plugin.settings().fieldWorkers) {
                wk.crew.remove();
                continue;
            }
            try {
                this.workOn(b, wk, w, now);
            } catch (final RuntimeException e) {
                wk.target = null;
            }
        }
        for (final var it = this.workers.entrySet().iterator(); it.hasNext();) {
            final var e = it.next();
            if (!seen.contains(e.getKey())) {
                e.getValue().crew.remove();
                it.remove();
            }
        }
    }

    private void workOn(final Building b, final Worker wk, final World w, final long now) {
        if (!this.plugin.production().ready(b)) return;
        final BlockBox box = b.box();
        if (wk.crew.bodies.isEmpty()) {
            final Location at = this.standOn(w, new Location(w, box.centerX(), box.minY(), box.centerZ()));
            final String role = switch (b.function()) {
                case FARM -> "Rolnik";
                case QUARRY -> "Kamieniarz";
                default -> "Rybak";
            };
            wk.crew.ensure(this.plugin, b.id(), at, 1, role);
            final Material tool = switch (b.function()) {
                case FARM -> Material.IRON_HOE;
                case QUARRY -> Material.IRON_PICKAXE;
                default -> Material.FISHING_ROD;
            };
            for (final BuilderCrew.Body body : wk.crew.bodies) {
                if (body.entity() instanceof org.bukkit.entity.LivingEntity le && le.getEquipment() != null) {
                    le.getEquipment().setItemInMainHand(new ItemStack(tool));
                }
            }
        }
        final BuilderCrew.Body body = wk.crew.bodies.isEmpty() ? null : wk.crew.bodies.get(0);
        if (body == null) {
            return;
        }
        if (b.function() == BuildingFunction.QUARRY) {
            this.quarry(b, wk, body, w, now);
            return;
        }
        if (wk.target == null || now >= wk.nextMove) {
            wk.target = b.function() == BuildingFunction.FARM ? this.ripeCrop(w, box) : null;
            if (wk.target == null) {
                // walk to a random free spot of the building
                for (int k = 0; k < 6 && wk.target == null; k++) {
                    final int x = box.minX() + this.random.nextInt(Math.max(1, box.sizeX()));
                    final int z = box.minZ() + this.random.nextInt(Math.max(1, box.sizeZ()));
                    final int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                    if (y <= box.maxY() + 1 && WorldOps.standable(w.getBlockAt(x, y, z))) {
                        wk.target = new int[] {x, y, z};
                    }
                }
            }
            wk.nextMove = now + 4000 + this.random.nextInt(4000);
            if (wk.target != null) {
                final Location t = new Location(w, wk.target[0] + 0.5, wk.target[1], wk.target[2] + 0.5);
                final Location stand = WorldOps.standSpot(w, w.getBlockAt(wk.target[0], wk.target[1], wk.target[2]), body.pos);
                body.target = stand == null ? t : stand;
                body.look = t;
            }
        }
        if (wk.target == null || body.pos.distanceSquared(body.target) > 2.5 * 2.5) {
            return;
        }
        wk.crew.swing(body);
        final Block at = w.getBlockAt(wk.target[0], wk.target[1], wk.target[2]);
        switch (b.function()) {
            case FARM -> {
                if (at.getBlockData() instanceof org.bukkit.block.data.Ageable crop && crop.getAge() >= crop.getMaximumAge()) {
                    final var drops=this.plugin.production().drops(at,new ItemStack(Material.IRON_HOE),true);
                    crop.setAge(0);
                    this.plugin.production().produce(b,List.of(ProductionStorageService.Change.of(at,crop.getAsString())),
                        drops,List.of(),() -> wk.target=null);
                    if (this.plugin.settings().effects) {
                        w.playSound(at.getLocation(), Sound.BLOCK_CROP_BREAK, 0.7f, 1f);
                        w.spawnParticle(Particle.HAPPY_VILLAGER, at.getLocation().add(0.5, 0.5, 0.5), 3, 0.3, 0.2, 0.3, 0);
                    }
                }
            }
            default -> {
                if (this.plugin.settings().effects && this.random.nextInt(4) == 0) {
                    w.playSound(at.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.5f, 1f);
                }
            }
        }
    }

    /** A bounded cut in the registered pit only. Resources are awarded for real natural stone, never ore or masonry. */
    private void quarry(final Building b, final Worker worker, final BuilderCrew.Body body, final World w, final long now) {
        if (now < worker.nextCut || b.registered() || this.plugin.construction().repairing(b.id())) {
            return;
        }
        final int today = (int) java.time.LocalDate.now().toEpochDay();
        final int[] state = this.data().state(b.countryId()).quarries().computeIfAbsent(b.id().toString(), k -> new int[] {0, today, 0});
        if (state[1] != today) {
            state[1] = today;
            state[2] = 0;
            this.data().markDirty();
        }
        if (state[2] >= this.plugin.settings().quarryStonePerDay) {
            return;
        }
        final var template = this.plugin.templates().get(b.type());
        final List<QuarryPlan.Cell> plan = QuarryPlan.cells(b.box(), w.getMinHeight(), template == null ? null : template.rotated(b.rotation()));
        for (int scanned = 0; scanned < 16 && state[0] < plan.size(); scanned++) {
            final QuarryPlan.Cell c = plan.get(state[0]);
            if (!w.isChunkLoaded(c.x() >> 4, c.z() >> 4)) {
                return;
            }
            if (!this.plugin.kraje().getCountryAt(w.getName(), c.x() >> 4, c.z() >> 4)
                .map(k -> b.countryId().equals(k.id())).orElse(false)
                || this.plugin.design().untouchable(b.countryId(), c.x(), c.z())) {
                return;
            }
            final Block cut = w.getBlockAt(c.x(), c.y(), c.z());
            final Material material = cut.getType();
            final boolean stone = Tag.BASE_STONE_OVERWORLD.isTagged(material);
            final boolean soil = Tag.DIRT.isTagged(material) || pl.medivalrpg.ludnosc.logic.BuildFixes.soil(material.name())
                || material == Material.GRAVEL || material == Material.CLAY;
            if (!stone && !soil) {
                // A liquid, ore, container or player block is never excavated; air already cut is safely skipped after restart.
                state[0]++;
                this.data().markDirty();
                continue;
            }
            final Building other = this.data().buildingAt(w.getName(), c.x(), c.y(), c.z());
            if (other != null && !other.id().equals(b.id())) {
                return;
            }
            boolean wet = false;
            for (final int[] d : new int[][] {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                final int nx = c.x() + d[0];
                final int nz = c.z() + d[2];
                if (!w.isChunkLoaded(nx >> 4, nz >> 4) || w.getBlockAt(nx, c.y() + d[1], nz).isLiquid()) {
                    wet = true;
                    break;
                }
            }
            if (wet || !cut.getRelative(0, 1, 0).isPassable() || cut.getRelative(0, 1, 0).isLiquid()) {
                state[0]++;
                this.data().markDirty();
                continue;
            }
            body.look = cut.getLocation().add(0.5, 0.5, 0.5);
            body.target = WorldOps.standSpot(w, cut, body.pos);
            if (body.target == null || body.pos.distanceSquared(body.target) > 6.25) {
                return;
            }
            worker.crew.swing(body);
            if (this.plugin.settings().effects) {
                w.playSound(cut.getLocation(), Sound.BLOCK_STONE_BREAK, 0.6f, 0.9f);
                w.spawnParticle(Particle.BLOCK, cut.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.2, 0.3, 0, cut.getBlockData());
            }
            this.plugin.production().produce(b, List.of(ProductionStorageService.Change.of(cut,"minecraft:air")),
                this.plugin.production().drops(cut,new ItemStack(Material.IRON_PICKAXE),false),List.of(), () -> {
                    if(stone) state[2]++;
                    state[0]++; this.data().markDirty(); worker.nextCut=System.currentTimeMillis()+2000;
                });
            return;
        }
    }

    /** A ripe crop in a field (the field's box only, never the world). */
    private int[] ripeCrop(final World w, final BlockBox box) {
        final List<int[]> ripe = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= Math.min(box.maxY(), box.minY() + 3); y++) {
                    final Block blk = w.getBlockAt(x, y, z);
                    if (blk.getBlockData() instanceof org.bukkit.block.data.Ageable a && foodCrop(blk.getType())
                        && a.getAge() >= a.getMaximumAge()) {
                        ripe.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return ripe.isEmpty() ? null : ripe.get(this.random.nextInt(ripe.size()));
    }
    private static boolean foodCrop(Material material) {
        return material==Material.WHEAT || material==Material.CARROTS || material==Material.POTATOES || material==Material.BEETROOTS;
    }

    /**
     * {@code require-materials}: a block the store chest of a site lacks is taken from the country's raw materials
     * (wood for wooden blocks, stone for stone blocks). True when taken.
     */
    public boolean take(final UUID country, final Material need) {
        if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Resource debit requires main thread");
        final String kind = Tag.LOGS.isTagged(need) || Tag.PLANKS.isTagged(need) || Tag.WOODEN_STAIRS.isTagged(need)
            || Tag.WOODEN_SLABS.isTagged(need) || Tag.WOODEN_FENCES.isTagged(need) || Tag.WOODEN_DOORS.isTagged(need) ? WOOD
            : need.name().contains("STONE") || need.name().contains("BRICK") || need.name().contains("COBBLE")
            || need.name().contains("ANDESITE") || need.name().contains("DIORITE") || need.name().contains("GRANITE") ? STONE : null;
        if (this.plugin.production().take(country,need,kind)) return true;
        if (kind == null) {
            return false;
        }
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null || st.resources().getOrDefault(kind, 0L) <= 0) {
            return false;
        }
        st.resources().merge(kind, -1L, Long::sum);
        this.data().markDirty();
        return true;
    }

    /** Builders' walking also moves the lumberjacks (every 2 ticks). */
    public void move(final double step) {
        for (final Lumberjack j : this.jacks.values()) {
            if (!j.crew.bodies.isEmpty()) {
                j.crew.move(step);
            }
        }
        for (final Worker wk : this.workers.values()) {
            if (!wk.crew.bodies.isEmpty()) {
                wk.crew.move(step * 0.7);
            }
        }
    }

    public boolean isWorker(final Entity e) {
        for (final Lumberjack j : this.jacks.values()) {
            if (j.crew.contains(e.getUniqueId())) {
                return true;
            }
        }
        for (final Worker worker : this.workers.values()) {
            if (worker.crew.contains(e.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    public void shutdown() {
        for (final Lumberjack j : this.jacks.values()) {
            j.crew.remove();
        }
        this.jacks.clear();
        for (final Worker wk : this.workers.values()) {
            wk.crew.remove();
        }
        this.workers.clear();
    }

    private void work(final Building b, final Lumberjack j, final World w, final long now) {
        final Location home = new Location(w, b.box().centerX() + 0.5, b.box().minY() + 1, b.box().centerZ() + 0.5);
        if (j.crew.bodies.isEmpty()) {
            j.crew.ensure(this.plugin, b.id(), this.standOn(w, home), 1, "Drwal");
            for (final BuilderCrew.Body body : j.crew.bodies) {
                if (body.entity() instanceof org.bukkit.entity.LivingEntity le && le.getEquipment() != null) {
                    le.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE));
                }
            }
        }
        if (j.job == null) {
            if (now < j.idleUntil) {
                return;
            }
            j.job = this.findTree(b, w);
            j.cursor = 0;
            if (j.job == null) {
                j.idleUntil = now + 60_000; // nothing to fell nearby: look again in a minute
                final BuilderCrew.Body body = j.crew.bodies.isEmpty() ? null : j.crew.bodies.get(0);
                if (body != null) {
                    body.target = this.standOn(w, home);
                }
                return;
            }
            j.log = w.getBlockAt(j.job.sapling()[0], j.job.sapling()[1], j.job.sapling()[2]).getType().getKey().toString();
            // back to the axe after carrying the last logs home
            for (final BuilderCrew.Body b2 : j.crew.bodies) {
                if (b2.entity() instanceof org.bukkit.entity.LivingEntity le && le.getEquipment() != null) {
                    le.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE));
                }
            }
        }
        final BuilderCrew.Body body = j.crew.bodies.isEmpty() ? null : j.crew.bodies.get(0);
        final int[] base = j.job.sapling();
        final Location trunk = new Location(w, base[0] + 0.5, base[1], base[2] + 0.5);
        if (body != null) {
            final Location stand = WorldOps.standSpot(w, w.getBlockAt(base[0], base[1], base[2]), body.pos);
            body.target = stand == null ? trunk.clone().add(1, 0, 0) : stand;
            body.look = trunk.clone().add(0, 1.5, 0);
            if (body.pos.distanceSquared(body.target) > 2.5 * 2.5) {
                return; // still walking
            }
            j.crew.swing(body);
        }
        if (!this.plugin.production().ready(b)) return;
        if (j.cursor < j.job.remove().size()) {
            final int[] c = j.job.remove().get(j.cursor);
            if (!w.isChunkLoaded(c[0] >> 4,c[2] >> 4)
                || !this.plugin.kraje().getCountryAt(w.getName(),c[0] >> 4,c[2] >> 4).map(k -> k.id().equals(b.countryId())).orElse(false)) return;
            final Block blk = w.getBlockAt(c[0],c[1],c[2]);
            if (!Tag.LOGS.isTagged(blk.getType()) && !Tag.LEAVES.isTagged(blk.getType())) { j.cursor++; return; }
            this.plugin.production().produce(b,List.of(ProductionStorageService.Change.of(blk,"minecraft:air")),
                this.plugin.production().drops(blk,new ItemStack(Material.IRON_AXE),false),List.of(),() -> j.cursor++);
            return;
        }
        if (j.cursor >= j.job.remove().size()) {
            // replant: a sapling of the same kind on the base
            final Block ground = w.getBlockAt(base[0], base[1] - 1, base[2]);
            final Block at = w.getBlockAt(base[0], base[1], base[2]);
            if (at.getType().isAir() && (Tag.DIRT.isTagged(ground.getType()) || ground.getType() == Material.GRASS_BLOCK)) {
                at.setBlockData(Bukkit.createBlockData(TreeFeller.sapling(j.log)), false);
                if (this.plugin.settings().effects) {
                    w.playSound(at.getLocation(), Sound.BLOCK_GRASS_PLACE, 0.7f, 1f);
                }
            }
            j.job = null;
            j.idleUntil = System.currentTimeMillis() + this.plugin.settings().lumberjackRestSeconds * 1000L;
            // carries the logs to the sawmill on the shoulder (visible), rests there
            final Material logItem = Material.matchMaterial(j.log == null ? "oak_log" : j.log);
            for (final BuilderCrew.Body b2 : j.crew.bodies) {
                b2.target = this.standOn(w, home);
                b2.look = null;
                if (logItem != null && logItem.isItem() && b2.entity() instanceof org.bukkit.entity.LivingEntity le
                    && le.getEquipment() != null) {
                    le.getEquipment().setItemInMainHand(new ItemStack(logItem));
                }
            }
        }
    }

    private Location standOn(final World w, final Location l) {
        final int y = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        return new Location(w, l.getBlockX() + 0.5, y, l.getBlockZ() + 0.5);
    }

    /** A tree near the sawmill on own land, away from roads and buildings, in a forest that keeps enough trees. */
    private TreeFeller.Plan findTree(final Building mill, final World w) {
        final UUID country = mill.countryId();
        final TerrainView view = this.plugin.terrain().view(w.getName(), country);
        final CountryState st = this.data().state(country);
        final int r = this.plugin.settings().lumberjackRadius;
        final int cx = mill.box().centerX();
        final int cz = mill.box().centerZ();
        this.plugin.terrain().requestAround(country, w.getName(), cx >> 4, cz >> 4, r / 16 + 1);
        final List<int[]> candidates = new ArrayList<>();
        for (int x = cx - r; x <= cx + r; x += 2) {
            for (int z = cz - r; z <= cz + r; z += 2) {
                if (view.tree(x, z) && view.own(x, z) && w.isChunkLoaded(x >> 4, z >> 4)) {
                    candidates.add(new int[] {x, z});
                }
            }
        }
        java.util.Collections.shuffle(candidates, this.random);
        int tries = 0;
        for (final int[] c : candidates) {
            if (++tries > 12) {
                break;
            }
            if (this.nearRoadOrBuilding(st, c[0], c[1]) || this.forestTooThin(view, c[0], c[1])
                || this.plugin.design().untouchable(country, c[0], c[1])) {
                continue;
            }
            // the trunk: first log from the top down
            final int top = w.getHighestBlockYAt(c[0], c[1]);
            int y = top;
            for (int k = 0; k < 24 && y > w.getMinHeight(); k++, y--) {
                if (Tag.LOGS.isTagged(w.getBlockAt(c[0], y, c[1]).getType())) {
                    break;
                }
            }
            if (!Tag.LOGS.isTagged(w.getBlockAt(c[0], y, c[1]).getType())) {
                continue;
            }
            final TreeFeller.Plan plan = TreeFeller.plan((x, yy, z) -> {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    return TreeFeller.OTHER;
                }
                final Block blk = w.getBlockAt(x, yy, z);
                final Material m = blk.getType();
                if (Tag.LOGS.isTagged(m)) {
                    return TreeFeller.LOG;
                }
                if (Tag.LEAVES.isTagged(m) && blk.getBlockData() instanceof Leaves l && !l.isPersistent()) {
                    return TreeFeller.LEAF;
                }
                return TreeFeller.OTHER;
            }, (x, z) -> view.own(x, z) && !this.inBuilding(x, z), c[0], y, c[1],
                this.plugin.settings().lumberjackMaxLogs);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private boolean inBuilding(final int x, final int z) {
        for (final Building b : this.data().allBuildings()) {
            final BlockBox box = b.box();
            if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    private boolean nearRoadOrBuilding(final CountryState st, final int x, final int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (st.roads().contains(RoadPlanner.key(x + dx, z + dz))) {
                    return true;
                }
            }
        }
        for (final Building b : this.data().allBuildings()) {
            final BlockBox box = b.box();
            if (x >= box.minX() - 6 && x <= box.maxX() + 6 && z >= box.minZ() - 6 && z <= box.maxZ() + 6) {
                return true; // the building and its garden
            }
        }
        return st.center != null && Math.hypot(x - st.center.x(), z - st.center.z()) < 12;
    }

    /** At least {@code keep} other tree columns within 6 blocks must remain (part of the forest always stands). */
    private boolean forestTooThin(final TerrainView view, final int x, final int z) {
        int trees = 0;
        for (int dx = -6; dx <= 6; dx += 2) {
            for (int dz = -6; dz <= 6; dz += 2) {
                if ((dx != 0 || dz != 0) && view.tree(x + dx, z + dz)) {
                    trees++;
                }
            }
        }
        return trees < this.plugin.settings().lumberjackKeepTrees;
    }

    // ------------------------------------------------------------------ daily production and sales

    /** Daily bookkeeping only: no synthetic output or automatic sale of physical production. */
    public void daily(final UUID country) {
        // Physical workplaces produce only through real world work. Never mint daily ore/fish,
        // never auto-sell newly deposited items and never migrate the legacy virtual map.
        final CountryState state=this.data().state(country);
        state.lastMineOre=0; state.lastMineStone=0; state.lastMinePrecious=0; state.lastMineSold=0;
        this.data().markDirty();
    }

    /** Sale price (copper) of one unit; precious metal falls back to {@code mines.precious-price}. */
    public long price(final String resource) {
        final Long p = this.plugin.settings().resourcePrices.get(resource);
        if (p == null && PRECIOUS.equals(resource)) {
            return this.plugin.settings().work.preciousPrice;
        }
        return p == null ? 0 : p;
    }

    /** Polish name of a resource. */
    public static String name(final String id) {
        return switch (id) {
            case WOOD -> "drewno";
            case STONE -> "kamień";
            case ORE -> "rudy";
            case FISH -> "ryby";
            case PRECIOUS -> "kruszce";
            default -> id;
        };
    }
}
