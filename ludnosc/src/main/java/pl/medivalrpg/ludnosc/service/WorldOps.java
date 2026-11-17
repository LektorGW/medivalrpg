// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.planner.TerrainView;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.Connections;

/** Block-level helpers of the builders (main thread, loaded chunks only). */
final class WorldOps {

    private static final Map<String, BlockFace> FACES = Map.of("north", BlockFace.NORTH, "south", BlockFace.SOUTH,
        "east", BlockFace.EAST, "west", BlockFace.WEST, "up", BlockFace.UP, "down", BlockFace.DOWN);

    private WorldOps() {
    }

    /** Vegetation, snow, air: cleared or replaced freely. */
    static boolean soft(final Block b) {
        final Material m = b.getType();
        return b.isEmpty() || b.isReplaceable() || Tag.LEAVES.isTagged(m) || Tag.FLOWERS.isTagged(m) || Tag.SAPLINGS.isTagged(m)
            || m == Material.SNOW || m == Material.VINE;
    }

    /** Natural ground that may be dug away / filled up. */
    static boolean ground(final Material m) {
        return Tag.DIRT.isTagged(m) || pl.medivalrpg.ludnosc.logic.BuildFixes.soil(m.name()) || Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.SAND.isTagged(m) || Tag.TERRACOTTA.isTagged(m)
            || Tag.SNOW.isTagged(m) || m == Material.GRAVEL || m == Material.CLAY || m == Material.DIRT_PATH
            || m == Material.SANDSTONE || m == Material.RED_SANDSTONE || m == Material.MUD || m == Material.MOSS_BLOCK
            || m.name().endsWith("_ORE");
    }

    /** Blocks the city walls of every epoch are made of (an old stretch is taken down). */
    static boolean wallBlock(final Material m) {
        return m == Material.STONE_BRICKS || m == Material.COBBLESTONE || m == Material.POLISHED_ANDESITE || m == Material.SMOOTH_STONE
            || m == Material.STONE_BRICK_WALL || m == Material.SPRUCE_LOG || m == Material.STRIPPED_SPRUCE_LOG || m == Material.SPRUCE_FENCE
            || m == Material.LANTERN || m == Material.BRICKS || m == Material.IRON_BARS || m == Material.GRAY_CONCRETE
            || m == Material.LIGHT_GRAY_CONCRETE;
    }

    /** Blocks of roads of every epoch (may be resurfaced). */
    static boolean roadSurface(final Material m) {
        return m == Material.COBBLESTONE || m == Material.STONE_BRICKS || m == Material.POLISHED_ANDESITE || m == Material.DIRT_PATH
            || m == Material.BLACK_CONCRETE || m == Material.WHITE_CONCRETE || m == Material.SMOOTH_STONE || m == Material.GRAVEL;
    }

    /**
     * Street furniture and building remains that town grading clears from a column: road surfaces, slabs, stairs,
     * walls, fences, lamps and their lights, chains, bars, scaffolding, campfires, torches.
     */
    static boolean townDecor(final Material m) {
        if (m == Material.DIRT_PATH || m == Material.GRAVEL) {
            return false; // natural ground anyway
        }
        final String n = m.name();
        return roadSurface(m) || Tag.SLABS.isTagged(m) || Tag.STAIRS.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCES.isTagged(m)
            || Tag.FENCE_GATES.isTagged(m) || m == Material.LANTERN || m == Material.SOUL_LANTERN || m == Material.SEA_LANTERN
            || m == Material.IRON_BARS || m == Material.SCAFFOLDING || m == Material.CAMPFIRE || m == Material.TORCH
            || m == Material.WALL_TORCH || m == Material.GLOWSTONE || m == Material.REDSTONE_LAMP || n.endsWith("CHAIN")
            || n.startsWith("POLISHED_BLACKSTONE") || n.endsWith("_CONCRETE");
    }

    /**
     * A block over an empty plot that is not terrain: remains of a demolished building, a lamp, a floating platform.
     * Natural ground, trees, liquids and containers (chests with someone's items) are never leftovers.
     */
    static boolean leftover(final Block b) {
        final Material m = b.getType();
        if (b.isEmpty() || b.isLiquid() || ground(m) || Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || soft(b)
            || m == Material.BEDROCK) {
            return false;
        }
        return !(b.getState(false) instanceof org.bukkit.block.Container);
    }

    /** Top solid (non-soft, non-log) block y at a column between {@code fromY} and {@code toY} (downwards). */
    static int topSolid(final World w, final int x, final int z, final int fromY, final int toY) {
        for (int y = fromY; y >= Math.max(toY, w.getMinHeight()); y--) {
            final Block b = w.getBlockAt(x, y, z);
            if (!soft(b) && !Tag.LOGS.isTagged(b.getType()) && !b.isLiquid()) {
                return y;
            }
        }
        return TerrainView.UNKNOWN;
    }

    /**
     * Ground under a city wall column: from the top of the column down, through air, plants, leaves, trunks and a
     * stack of at most {@code maxWall} wall blocks of any epoch ({@link #wallBlock}) — the wall already standing there.
     * The same column always gives the same ground, before, during and after the wall is built (a site planned again
     * after a restart never stacks a second wall on the first one), and it does not depend on the town centre's height.
     * {@link TerrainView#UNKNOWN} when the column ends in a taller stack of wall-like blocks (someone's structure) or
     * nothing solid is found.
     */
    static int wallGround(final World w, final int x, final int z, final int maxWall) {
        final int top = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return wallGround(top, w.getMinHeight(), maxWall, y -> {
            final Block b = w.getBlockAt(x, y, z);
            if (soft(b) || Tag.LOGS.isTagged(b.getType()) || b.isLiquid()) {
                return GroundScan.PASS;
            }
            return wallBlock(b.getType()) ? GroundScan.WALL : GroundScan.GROUND;
        });
    }

    /** What a block of a column is for {@link #wallGround}. */
    enum GroundScan { PASS, WALL, GROUND }

    /** Pure part of {@link #wallGround(World, int, int, int)}: scan from {@code top} down to {@code bottom}. */
    static int wallGround(final int top, final int bottom, final int maxWall, final java.util.function.IntFunction<GroundScan> at) {
        int walls = 0;
        for (int y = top; y >= Math.max(bottom, top - 96); y--) {
            switch (at.apply(y)) {
                case GROUND -> {
                    return y;
                }
                case WALL -> {
                    if (++walls > maxWall) {
                        return TerrainView.UNKNOWN;
                    }
                }
                default -> {
                }
            }
        }
        return TerrainView.UNKNOWN;
    }

    static boolean standable(final Block feet) {
        final Block head = feet.getRelative(0, 1, 0);
        return feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
            && feet.getRelative(0, -1, 0).isSolid();
    }

    /** A free spot to stand next to {@code target} (within 2 blocks, at most 3 lower or 1 higher), closest to {@code from}. */
    static Location standSpot(final World w, final Block target, final Location from) {
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = 1; dy >= -3; dy--) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx == 0 && dz == 0 && dy >= -1) {
                        continue;
                    }
                    // never load a neighbouring chunk synchronously for a stand spot (targets lie on chunk edges)
                    if (!w.isChunkLoaded((target.getX() + dx) >> 4, (target.getZ() + dz) >> 4)) {
                        continue;
                    }
                    final Block feet = w.getBlockAt(target.getX() + dx, target.getY() + dy, target.getZ() + dz);
                    if (!standable(feet)) {
                        continue;
                    }
                    final Location l = feet.getLocation().add(0.5, 0, 0.5);
                    final double d = from == null ? Math.abs(dx) + Math.abs(dz) : l.distanceSquared(from);
                    if (d < bestD) {
                        bestD = d;
                        best = l;
                    }
                }
            }
        }
        if (best == null) {
            if (!w.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) {
                return target.getLocation().add(0.5, 1, 0.5);
            }
            final int top = w.getHighestBlockYAt(target.getX(), target.getZ());
            best = new Location(w, target.getX() + 0.5, top + 1, target.getZ() + 0.5);
        }
        return best;
    }

    /** Recomputes neighbour-dependent states (panes, fences, gates, walls, stairs) in a box from the real neighbours. */
    static int connectionPass(final World w, final BlockBox box) {
        int changed = 0;
        for (int y = box.minY() - 1; y <= box.maxY() + 1; y++) {
            for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
                for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
                    final Block b = w.getBlockAt(x, y, z);
                    final String name = b.getType().name();
                    if (!(name.endsWith("_PANE") || name.equals("IRON_BARS") || name.endsWith("_FENCE")
                        || name.endsWith("_FENCE_GATE") || name.endsWith("_WALL") || name.endsWith("_STAIRS"))) {
                        continue;
                    }
                    if (fixConnections(b)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    static boolean fixConnections(final Block b) {
        final BlockData data = b.getBlockData();
        final BlockStates.Parsed self = BlockStates.parse(data.getAsString());
        if (!Connections.dependsOnNeighbours(self)) {
            return false;
        }
        final Map<String, BlockData> around = new HashMap<>();
        final Connections.Neighbours n = dir -> {
            final BlockFace f = FACES.get(dir);
            if (f == null) {
                return null;
            }
            final BlockData d = b.getRelative(f).getBlockData();
            around.put(dir, d);
            return BlockStates.parse(d.getAsString());
        };
        final BlockStates.Parsed out = Connections.resolve(self, n, (dir, p) -> {
            final BlockData d = around.get(dir);
            final BlockFace f = FACES.get(dir);
            return d != null && f != null && d.isFaceSturdy(f.getOppositeFace(), BlockSupport.FULL);
        });
        if (out.properties().equals(self.properties())) {
            return false;
        }
        try {
            b.setBlockData(Bukkit.createBlockData(out.format()), false);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /** Fill material for a terrain column of the given surface family at a depth below the new top. */
    static Material fill(final Material surface, final int depth) {
        if (Tag.SAND.isTagged(surface)) {
            return depth < 3 ? Material.SAND : Material.SANDSTONE;
        }
        if (Tag.BASE_STONE_OVERWORLD.isTagged(surface) || surface == Material.GRAVEL) {
            return depth == 0 ? surface : Material.STONE;
        }
        if (depth == 0) {
            return Material.GRASS_BLOCK;
        }
        return depth <= 3 ? Material.DIRT : Material.STONE;
    }
}
