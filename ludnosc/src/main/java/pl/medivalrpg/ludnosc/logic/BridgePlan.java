// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.List;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.planner.RoadDecor;

/** Three-wide deck with railings; no blocks below the deck, preserving river water and navigation underneath. */
public final class BridgePlan {
    private BridgePlan() { }

    /** Full reservation and ticket footprint, including the side railings at a chunk boundary. */
    public static BlockBox bounds(final List<BlockPos> path) {
        final List<RoadDecor.Block> blocks = blocks(path, 1);
        if (blocks.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final RoadDecor.Block b : blocks) {
            minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
            minY = Math.min(minY, b.y()); maxY = Math.max(maxY, b.y());
            minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
        }
        return new BlockBox(path.getFirst().world(), minX, minY, minZ, maxX, maxY + 1, maxZ);
    }

    public static List<RoadDecor.Block> blocks(final List<BlockPos> path, final int era) {
        if (path.size() < 3 || path.size() > BridgeRoute.MAX_SPAN + 2) {
            return List.of();
        }
        final BlockPos a = path.getFirst();
        final BlockPos b = path.getLast();
        final int dx = Integer.signum(b.x() - a.x());
        final int dz = Integer.signum(b.z() - a.z());
        if ((dx == 0) == (dz == 0)) {
            return List.of();
        }
        final List<RoadDecor.Block> deck = new ArrayList<>();
        final List<RoadDecor.Block> rails = new ArrayList<>();
        final String surface = era <= 1 ? "minecraft:spruce_planks" : "minecraft:stone_bricks";
        final String rail = era <= 1 ? "minecraft:spruce_fence" : "minecraft:stone_brick_wall";
        final int y = Math.max(a.y(), b.y());
        for (int i = 0; i < path.size(); i++) {
            final BlockPos p = path.get(i);
            if (p.x() != a.x() + i * dx || p.z() != a.z() + i * dz) {
                return List.of();
            }
            for (int side = -1; side <= 1; side++) {
                final int x = p.x() + side * dz;
                final int z = p.z() + side * dx;
                deck.add(new RoadDecor.Block(x, y, z, surface));
                if (side != 0 && i > 0 && i < path.size() - 1) {
                    rails.add(new RoadDecor.Block(x, y + 1, z, rail));
                }
            }
        }
        deck.addAll(rails); // traversable floor first, then railings; survives reconstruction after restart
        return List.copyOf(deck);
    }
}
