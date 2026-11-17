// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Citizens fell a nearby tree for the wood of their own hut (pure, unit tested): which trees are allowed and whether
 * the real drops fit into the target chest. The felling itself is {@code planner.TreeFeller} (whole tree, sapling back).
 */
public final class HutLumber {

    private HutLumber() {
    }

    /** Why a tree column may not be felled; null = allowed. */
    public static String refuse(final boolean ownLand, final boolean planUntouchable, final boolean protectedChunk,
                                final double distanceToSpawn, final int spawnRadius, final boolean nearBuildingOrRoad) {
        if (!ownLand) {
            return "poza ziemią kraju";
        }
        if (planUntouchable) {
            return "strefa planu gracza";
        }
        if (protectedChunk) {
            return "obszar chroniony";
        }
        if (distanceToSpawn < spawnRadius) {
            return "zbyt blisko spawnu";
        }
        if (nearBuildingOrRoad) {
            return "przy budynku lub drodze";
        }
        return null;
    }

    private static final Set<String> BUILT = Set.of("GLASS", "GLASS_PANE", "TORCH", "WALL_TORCH", "LANTERN", "CHEST", "BARREL",
        "CRAFTING_TABLE", "FURNACE", "LADDER", "BOOKSHELF", "COBBLESTONE", "STONE_BRICKS", "BRICKS", "HAY_BLOCK", "RAIL",
        "SCAFFOLDING", "CAMPFIRE", "BELL", "COMPOSTER", "SMOOTH_STONE", "TERRACOTTA", "MUD_BRICKS", "WHITE_CONCRETE");

    /**
     * A block a player or a builder made (planks, stairs, glass, doors, beds, wool, bricks...): a tree next to such a
     * block is not felled (a log house or garden tree). Natural ground, plants, logs and leaves are not built.
     */
    public static boolean built(final String material) {
        if (material == null) {
            return false;
        }
        final String m = material.toUpperCase(java.util.Locale.ROOT);
        if (BUILT.contains(m)) {
            return true;
        }
        for (final String suffix : new String[] {"_PLANKS", "_STAIRS", "_SLAB", "_FENCE", "_FENCE_GATE", "_DOOR", "_TRAPDOOR", "_BED",
            "_WOOL", "_CARPET", "_SIGN", "_WALL_SIGN", "_HANGING_SIGN", "_STAINED_GLASS", "_STAINED_GLASS_PANE", "_BANNER", "_WALL",
            "_BRICKS", "_CONCRETE", "_TERRACOTTA", "_PRESSURE_PLATE", "_BUTTON", "STRIPPED_"}) {
            if (suffix.endsWith("_") ? m.startsWith(suffix) : m.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** A chest slot: {@code key} = material for plain items, a unique key for items with metadata, null = empty. */
    public record Slot(String key, int amount, int max) {
    }

    /** One drop stack (plain item). */
    public record Item(String key, int amount, int max) {
    }

    /** True when every drop fits (merging into equal plain stacks first, then empty slots) — nothing is dropped. */
    public static boolean fits(final List<Slot> slots, final List<Item> drops) {
        final List<int[]> room = new ArrayList<>();       // {index of key, free}
        final List<String> keys = new ArrayList<>();
        int empty = 0;
        for (final Slot s : slots) {
            if (s == null || s.key() == null || s.amount() <= 0) {
                empty++;
                continue;
            }
            keys.add(s.key());
            room.add(new int[] {keys.size() - 1, Math.max(0, s.max() - s.amount())});
        }
        for (final Item d : drops) {
            int left = d.amount();
            for (int i = 0; i < room.size() && left > 0; i++) {
                if (keys.get(room.get(i)[0]).equals(d.key())) {
                    final int put = Math.min(left, room.get(i)[1]);
                    room.get(i)[1] -= put;
                    left -= put;
                }
            }
            while (left > 0) {
                if (empty == 0) {
                    return false;
                }
                empty--;
                final int put = Math.min(left, Math.max(1, d.max()));
                keys.add(d.key());
                room.add(new int[] {keys.size() - 1, Math.max(1, d.max()) - put});
                left -= put;
            }
        }
        return true;
    }
}
