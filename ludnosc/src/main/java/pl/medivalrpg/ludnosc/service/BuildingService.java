// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Registry of finished buildings: ownership follows the country of the anchor (centre) chunk; bed counts are
 * recounted (debounced, one tick later) only when a bed inside a building is broken, placed, exploded or burnt.
 */
public final class BuildingService {

    public static final String REGISTERED_TYPE = "zgloszony";

    private final LudnoscPlugin plugin;
    private final Set<UUID> pendingRecount = new LinkedHashSet<>();
    private boolean recountScheduled;

    public BuildingService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    public UUID ownerOf(final BlockBox box) {
        final ChunkRef a = box.anchorChunk();
        return this.plugin.kraje().getCountryAt(a.world(), a.cx(), a.cz()).map(KrajInfo::id).orElse(null);
    }

    public boolean overlapsAny(final BlockBox box) {
        for (final ChunkRef c : box.chunks()) {
            for (final Building b : this.data().buildingsIn(c)) {
                if (b.box().intersects(box)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Building add(final UUID country, final String type, final BuildingFunction function, final BlockBox box,
                        final int beds, final BlockPos spawn, final int rotation, final boolean registered) {
        return this.add(country, type, function, box, beds, spawn, rotation, registered, null);
    }

    public Building add(final UUID country, final String type, final BuildingFunction function, final BlockBox box,
                        final int beds, final BlockPos spawn, final int rotation, final boolean registered, final String dye) {
        return this.add(country, type, function, box, beds, spawn, rotation, registered, dye, 0);
    }

    public Building add(final UUID country, final String type, final BuildingFunction function, final BlockBox box,
                        final int beds, final BlockPos spawn, final int rotation, final boolean registered, final String dye,
                        final int level) {
        final Building b = new Building(UUID.randomUUID(), country, type, function, box, beds, spawn, rotation,
            System.currentTimeMillis(), registered, dye, level);
        this.data().putBuilding(b);
        return b;
    }

    public void remove(final Building b) {
        this.data().removeBuilding(b.id());
        this.plugin.granary().buildingRemoved(b);
        if (this.plugin.playerBuildings() != null) {
            this.plugin.playerBuildings().forget(b);
        }
    }

    /** Idempotent registration for the durable, explicitly authorised royal reconstruction journal. */
    public Building addRoyal(final UUID id,final UUID country,final String type,final BuildingFunction function,
                             final BlockBox box,final int beds,final BlockPos spawn,final int rotation,final String dye,final int level) {
        final Building b=new Building(id,country,type,function,box,beds,spawn,rotation,System.currentTimeMillis(),
            this.plugin.templates().get(type)==null,dye,level);
        this.data().putBuilding(b);return b;
    }

    private static boolean loaded(final World w, final BlockBox box) {
        for (final ChunkRef c : box.chunks()) {
            if (!w.isChunkLoaded(c.cx(), c.cz())) {
                return false;
            }
        }
        return true;
    }

    /** Number of beds (heads) inside the box. Requires loaded chunks (-1 otherwise). */
    public static int countBeds(final World w, final BlockBox box) {
        if (!loaded(w, box)) {
            return -1;
        }
        int beds = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    final Block b = w.getBlockAt(x, y, z);
                    if (Tag.BEDS.isTagged(b.getType()) && b.getBlockData() instanceof Bed bed && bed.getPart() == Bed.Part.HEAD) {
                        beds++;
                    }
                }
            }
        }
        return beds;
    }

    /** Free standing spot (solid floor, two air blocks) next to a bed inside the box, or null. */
    public static BlockPos findSpawn(final World w, final BlockBox box) {
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    final Block b = w.getBlockAt(x, y, z);
                    if (!Tag.BEDS.isTagged(b.getType())) {
                        continue;
                    }
                    for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        final Block c = w.getBlockAt(x + d[0], y, z + d[1]);
                        if (box.contains(w.getName(), c.getX(), c.getY(), c.getZ()) && c.isPassable() && !c.isLiquid()
                            && c.getRelative(0, 1, 0).isPassable() && c.getRelative(0, -1, 0).isSolid()) {
                            return new BlockPos(w.getName(), c.getX(), c.getY(), c.getZ());
                        }
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ bed recount

    /** A bed changed at this block: recount the building(s) containing it next tick. */
    public void bedChangedAt(final Block block) {
        final String world = block.getWorld().getName();
        final Building b = this.data().buildingAt(world, block.getX(), block.getY(), block.getZ());
        if (b == null) {
            return;
        }
        if (PlayerBuildingService.isPlayer(b)) {
            // registered by the players: whole beds and key blocks are re-counted by PlayerBuildingService
            if (this.plugin.playerBuildings() != null) {
                this.plugin.playerBuildings().schedule(b.id());
            }
            return;
        }
        this.pendingRecount.add(b.id());
        if (!this.recountScheduled) {
            this.recountScheduled = true;
            Bukkit.getScheduler().runTask(this.plugin, this::flushRecounts);
        }
    }

    private void flushRecounts() {
        this.recountScheduled = false;
        for (final UUID id : List.copyOf(this.pendingRecount)) {
            final Building b = this.data().building(id);
            if (b == null) {
                continue;
            }
            final World w = Bukkit.getWorld(b.box().world());
            if (w == null) {
                continue;
            }
            final int beds = countBeds(w, b.box());
            if (beds >= 0 && beds != b.beds()) {
                Building updated = b.withBeds(beds);
                if (b.spawn() == null || beds > 0) {
                    final BlockPos spawn = b.spawn() == null ? findSpawn(w, b.box()) : b.spawn();
                    updated = updated.withSpawn(spawn);
                }
                this.data().putBuilding(updated);
            }
        }
        this.pendingRecount.clear();
    }

    // ------------------------------------------------------------------ ownership

    /** Buildings anchored in the chunk follow the new owner (null = ownerless wilderness building). */
    public void chunkChanged(final String world, final int cx, final int cz, final UUID newOwner) {
        final ChunkRef ref = new ChunkRef(world, cx, cz);
        for (final Building b : this.data().buildingsIn(ref)) {
            if (b.box().anchorChunk().equals(ref) && !java.util.Objects.equals(b.countryId(), newOwner)) {
                this.plugin.mines().ownerChanged(b, newOwner);
                this.data().putBuilding(b.withCountry(newOwner));
            }
        }
    }

    /** Country disbanded: its buildings stay as ownerless (a later claim of the land takes them over). */
    public void countryRemoved(final UUID country) {
        for (final Building b : List.copyOf(this.data().buildingsOf(country))) {
            this.plugin.mines().ownerChanged(b, null);
            this.data().putBuilding(b.withCountry(null));
        }
    }

    /** On enable: re-reads owners from Kraje (covers changes made while Ludnosc was not running). */
    public void syncOwners() {
        for (final Building b : List.copyOf(this.data().allBuildings())) {
            final UUID owner = this.ownerOf(b.box());
            if (!java.util.Objects.equals(owner, b.countryId())) {
                this.plugin.mines().ownerChanged(b, owner);
                this.data().putBuilding(b.withCountry(owner));
            }
        }
    }
}
