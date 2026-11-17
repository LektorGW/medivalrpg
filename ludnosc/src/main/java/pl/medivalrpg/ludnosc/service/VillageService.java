// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import io.papermc.paper.entity.poi.PoiSearchResult;
import io.papermc.paper.entity.poi.PoiType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.planner.RoadPlanner;
import pl.medivalrpg.ludnosc.planner.VillageScan;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Taking over vanilla villages ({@code LudnoscApi#findVillage} / {@code #adoptVillage}). Detection uses the
 * server's points of interest (Paper {@code World#locateAllPoiInRange}: bell = meeting, beds = home, job sites);
 * fields are counted from chunk snapshots (captured a few per tick, analysed off the main thread). Adoption
 * registers the houses with beds as {@code dom_wiejski} buildings (no template: no template repair/modernisation;
 * beds are recounted like for houses registered with WorldEdit), counts farmland as fields for food, adopts every
 * villager with its profession, puts the town centre on the bell and the dirt paths into the town plan as roads.
 */
public final class VillageService {

    /** Building type of adopted village houses. */
    public static final String VILLAGE_HOUSE = "dom_wiejski";

    private static final Set<String> JOBS = Set.of("armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman",
        "fletcher", "leatherworker", "librarian", "mason", "shepherd", "toolsmith", "weaponsmith");
    private static final int SNAPSHOTS_PER_TICK = 4;

    private final LudnoscPlugin plugin;

    public VillageService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    static VillageScan.Kind kind(final PoiType type) {
        final String k = type.getKey().value();
        if (k.equals("meeting")) {
            return VillageScan.Kind.MEETING;
        }
        if (k.equals("home")) {
            return VillageScan.Kind.HOME;
        }
        return JOBS.contains(k) ? VillageScan.Kind.JOB : null;
    }

    private static List<VillageScan.Poi> pois(final World w, final Location at, final int radius) {
        final List<VillageScan.Poi> out = new ArrayList<>();
        for (final PoiSearchResult r : w.locateAllPoiInRange(at, t -> kind(t) != null, radius, PoiType.Occupancy.ANY)) {
            final VillageScan.Kind k = kind(r.poiType());
            if (k != null) {
                final Location l = r.location();
                out.add(new VillageScan.Poi(k, l.getBlockX(), l.getBlockY(), l.getBlockZ()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ detection

    /** Detects a village near a location (any thread; the work runs on the main thread and asynchronously). */
    public CompletableFuture<Optional<LudnoscApi.VillageInfo>> find(final Location near, final int radius) {
        final CompletableFuture<Optional<LudnoscApi.VillageInfo>> result = new CompletableFuture<>();
        if (near == null || near.getWorld() == null) {
            result.complete(Optional.empty());
            return result;
        }
        final Runnable start = () -> {
            try {
                this.start(near, Math.max(16, Math.min(radius, 160)), result);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().warning("Wykrywanie wioski nie powiodło się: " + e.getMessage());
                result.complete(Optional.empty());
            }
        };
        if (Bukkit.isPrimaryThread()) {
            start.run();
        } else {
            Bukkit.getScheduler().runTask(this.plugin, start);
        }
        return result;
    }

    private void start(final Location near, final int radius, final CompletableFuture<Optional<LudnoscApi.VillageInfo>> result) {
        final World w = near.getWorld();
        final VillageScan.Poi bell = VillageScan.bell(pois(w, near, radius), near.getBlockX(), near.getBlockZ());
        if (bell == null) {
            result.complete(Optional.empty());
            return;
        }
        final Location bellLoc = new Location(w, bell.x(), bell.y(), bell.z());
        final List<VillageScan.Poi> around = pois(w, bellLoc, radius);
        // chunks to look for fields: chunks of the POIs and their neighbours, within the radius
        final Set<Long> scan = new LinkedHashSet<>();
        final int rc = (radius >> 4) + 1;
        for (final VillageScan.Poi p : around) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    final int cx = (p.x() >> 4) + dx;
                    final int cz = (p.z() >> 4) + dz;
                    if (Math.abs(cx - (bell.x() >> 4)) <= rc && Math.abs(cz - (bell.z() >> 4)) <= rc) {
                        scan.add(VillageScan.key(cx, cz));
                    }
                }
            }
        }
        final List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (final long k : scan) {
            loads.add(w.getChunkAtAsync(VillageScan.cx(k), VillageScan.cz(k), false));
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ok, error) ->
            Bukkit.getScheduler().runTask(this.plugin, () -> this.snapshots(w, bell, around, scan, radius, result)));
    }

    /** Captures the snapshots a few per tick, then analyses them asynchronously. */
    private void snapshots(final World w, final VillageScan.Poi bell, final List<VillageScan.Poi> pois, final Set<Long> chunks,
                           final int radius, final CompletableFuture<Optional<LudnoscApi.VillageInfo>> result) {
        final ArrayDeque<Long> todo = new ArrayDeque<>(chunks);
        final List<ChunkSnapshot> snaps = new ArrayList<>();
        final int villagers = this.countVillagers(w, bell, radius);
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            for (int i = 0; i < SNAPSHOTS_PER_TICK && !todo.isEmpty(); i++) {
                final long k = todo.poll();
                if (w.isChunkLoaded(VillageScan.cx(k), VillageScan.cz(k))) {
                    snaps.add(w.getChunkAt(VillageScan.cx(k), VillageScan.cz(k)).getChunkSnapshot(true, false, false));
                }
            }
            if (!todo.isEmpty()) {
                return;
            }
            task[0].cancel();
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                final Map<Long, Integer> farmland = new HashMap<>();
                for (final ChunkSnapshot s : snaps) {
                    final int n = countFarmland(s, w.getMinHeight());
                    if (n > 0) {
                        farmland.put(VillageScan.key(s.getX(), s.getZ()), n);
                    }
                }
                final VillageScan.Result r = VillageScan.scan(bell, pois, farmland, radius, this.plugin.settings().villageMinFarmland);
                result.complete(Optional.of(new LudnoscApi.VillageInfo(w.getName(), bell.x(), bell.y(), bell.z(), r.chunks(),
                    r.beds(), villagers, r.jobSites())));
            });
        }, 1L, 1L);
    }

    /** Farmland blocks at the surface of a snapshot (under crops or bare). Any thread. */
    static int countFarmland(final ChunkSnapshot s, final int minY) {
        int n = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int y = s.getHighestBlockYAt(x, z);
                for (int dy = 0; dy <= 1 && y - dy >= minY; dy++) {
                    if (s.getBlockType(x, y - dy, z) == Material.FARMLAND) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }

    private int countVillagers(final World w, final VillageScan.Poi bell, final int radius) {
        int n = 0;
        for (final Villager v : w.getNearbyEntitiesByType(Villager.class, new Location(w, bell.x(), bell.y(), bell.z()), radius, 48,
            radius)) {
            if (!CitizenService.foreignNpc(v) && !this.plugin.construction().isHelper(v)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ adoption

    /** Main thread. See {@code LudnoscApi#adoptVillage}. */
    public int adopt(final UUID country, final LudnoscApi.VillageInfo v) {
        if (country == null || v == null || this.plugin.kraje().getCountry(country).isEmpty()) {
            return -1;
        }
        final World w = Bukkit.getWorld(v.world());
        if (w == null) {
            return -1;
        }
        final Set<Long> chunks = v.chunkKeys() == null || v.chunkKeys().isEmpty()
            ? Set.of(VillageScan.key(v.bellX() >> 4, v.bellZ() >> 4)) : v.chunkKeys();
        int radius = 16;
        for (final long k : chunks) {
            radius = Math.max(radius, Math.max(Math.abs(VillageScan.cx(k) * 16 + 8 - v.bellX()),
                Math.abs(VillageScan.cz(k) * 16 + 8 - v.bellZ())) + 12);
        }
        // houses around the beds (in loaded chunks; the rest are found when a player registers them or never)
        final List<int[]> beds = new ArrayList<>();
        for (final VillageScan.Poi p : pois(w, new Location(w, v.bellX(), v.bellY(), v.bellZ()), radius)) {
            if (p.kind() == VillageScan.Kind.HOME && chunks.contains(p.chunk()) && w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                beds.add(new int[] {p.x(), p.y(), p.z()});
            }
        }
        int houses = 0;
        for (final VillageScan.House h : VillageScan.houses(this.blocks(w), beds, this.plugin.settings().villageHouseReach)) {
            final BlockBox box = new BlockBox(w.getName(), h.minX(), h.minY(), h.minZ(), h.maxX(), h.maxY(), h.maxZ());
            if (this.plugin.construction().overlaps(box, null)) {
                continue;
            }
            int bedCount = BuildingService.countBeds(w, box);
            if (bedCount < 0) {
                bedCount = h.beds();
            }
            this.plugin.buildings().add(country, VILLAGE_HOUSE, BuildingFunction.HOUSING, box, bedCount,
                BuildingService.findSpawn(w, box), 0, true);
            houses++;
        }
        // fields and paths at the surface of the loaded village chunks
        final CountryState st = this.plugin.data().state(country);
        int farmland = 0;
        for (final long k : chunks) {
            final int cx = VillageScan.cx(k);
            final int cz = VillageScan.cz(k);
            if (!w.isChunkLoaded(cx, cz)) {
                continue;
            }
            for (int x = cx * 16; x < cx * 16 + 16; x++) {
                for (int z = cz * 16; z < cz * 16 + 16; z++) {
                    final Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    final Material m = top.getType();
                    if (m == Material.FARMLAND) {
                        farmland++;
                    } else if (m == Material.DIRT_PATH) {
                        st.roads().add(RoadPlanner.key(x, z));
                    }
                }
            }
        }
        st.villageFarmland += farmland;
        st.center = new BlockPos(w.getName(), v.bellX(), v.bellY(), v.bellZ());
        // every villager of the village, professions kept (also when the country has no free beds)
        int adopted = 0;
        for (final long k : chunks) {
            if (!w.isChunkLoaded(VillageScan.cx(k), VillageScan.cz(k))) {
                continue; // adopted when the chunk loads (the land belongs to the country)
            }
            for (final Entity e : w.getChunkAt(VillageScan.cx(k), VillageScan.cz(k)).getEntities()) {
                if (e instanceof Villager vil && vil.isValid() && this.plugin.data().citizen(vil.getUniqueId()) == null
                    && this.plugin.citizens().taggedCountry(vil) == null && !this.plugin.construction().isHelper(vil)
                    && !CitizenService.foreignNpc(vil)) {
                    this.plugin.citizens().adopt(vil, country);
                    adopted++;
                }
            }
        }
        this.plugin.data().markDirty();
        this.plugin.getLogger().info("Wioska przy " + v.bellX() + " " + v.bellY() + " " + v.bellZ() + " przyłączona: domów " + houses
            + ", obywateli " + adopted + ", pól " + farmland + " bl., ścieżek " + st.roads().size());
        return adopted;
    }

    /** Block classifier for the house finder (unloaded chunks = unknown). */
    private VillageScan.Blocks blocks(final World w) {
        return (x, y, z) -> {
            if (!w.isChunkLoaded(x >> 4, z >> 4) || y < w.getMinHeight() || y >= w.getMaxHeight()) {
                return VillageScan.UNKNOWN;
            }
            final Block b = w.getBlockAt(x, y, z);
            return classify(b.getType(), b.isPassable());
        };
    }

    /** Doors, gates, glass and fences close a room; air, beds, carpets, torches and plants are open. */
    static int classify(final Material m, final boolean passable) {
        if (Tag.DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCES.isTagged(m)
            || m.name().endsWith("GLASS_PANE") || m == Material.IRON_BARS) {
            return VillageScan.WALL;
        }
        if (Tag.BEDS.isTagged(m) || passable || Tag.WOOL_CARPETS.isTagged(m)) {
            return VillageScan.OPEN;
        }
        return VillageScan.WALL;
    }

    /** Buildings of the country adopted from villages. */
    public int villageHouses(final UUID country) {
        int n = 0;
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (VILLAGE_HOUSE.equals(b.type())) {
                n++;
            }
        }
        return n;
    }
}
