// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.List;

/**
 * Name based classification of block states for templates (pure, no Bukkit): beds, doors, attachable blocks
 * (placed after the solid blocks of a layer) and the families that connect sideways (panes, fences, walls).
 */
public final class BlockKinds {

    /** Substrings of ids that are not full cubes (fences/panes don't connect to them). */
    private static final List<String> PARTIAL = List.of(
        "stairs", "slab", "door", "trapdoor", "torch", "lantern", "_bed", "carpet", "pane", "iron_bars", "fence",
        "sign", "banner", "button", "pressure_plate", "flower", "potted_", "flower_pot", "ladder", "rail", "chain",
        "campfire", "anvil", "cauldron", "grindstone", "lectern", "bell", "candle", "head", "skull", "sapling",
        "short_grass", "tall_grass", "seagrass", "fern", "vine", "lever", "bars", "chest", "enchanting", "brewing", "stonecutter", "composter",
        "end_rod", "scaffolding", "hopper", "cake", "dripleaf", "azalea", "frame", "pot", "rod", "web", "torchflower", "@window");

    /** Substrings of blocks that hang on / stand on another block → placed last in a layer. */
    private static final List<String> ATTACHED = List.of(
        "torch", "lantern", "door", "_bed", "carpet", "pane", "iron_bars", "sign", "banner", "button", "pressure_plate",
        "potted_", "flower_pot", "ladder", "rail", "chain", "lever", "candle", "vine", "trapdoor", "fence", "bell",
        "campfire", "head", "skull", "sapling", "flower", "frame");

    private BlockKinds() {
    }

    public static boolean isBed(final BlockStates.Parsed p) {
        return p.path().endsWith("_bed");
    }

    public static boolean isBedHead(final BlockStates.Parsed p) {
        return isBed(p) && "head".equals(p.property("part"));
    }

    public static boolean isBedFoot(final BlockStates.Parsed p) {
        return isBed(p) && "foot".equals(p.property("part"));
    }

    public static boolean isDoor(final BlockStates.Parsed p) {
        return p.path().endsWith("_door");
    }

    public static boolean isContainer(final BlockStates.Parsed p) {
        final String path = p.path();
        return path.equals("chest") || path.equals("barrel") || path.equals("trapped_chest");
    }

    public static boolean isLiquid(final BlockStates.Parsed p) {
        final String path = p.path();
        return path.equals("water") || path.equals("lava");
    }

    /** Glass panes, stained panes and iron bars. */
    public static boolean isPaneLike(final BlockStates.Parsed p) {
        final String path = p.path();
        return path.endsWith("_pane") || path.equals("iron_bars") || path.equals("@window");
    }

    /** Fences (not gates). */
    public static boolean isFence(final BlockStates.Parsed p) {
        return p.path().endsWith("_fence");
    }

    public static boolean isFenceGate(final BlockStates.Parsed p) {
        return p.path().endsWith("_fence_gate");
    }

    /** Wall blocks ({@code cobblestone_wall}), not wall torches/signs/banners. */
    public static boolean isWall(final BlockStates.Parsed p) {
        return p.path().endsWith("_wall");
    }

    public static boolean isConnectable(final BlockStates.Parsed p) {
        return isPaneLike(p) || isFence(p) || isWall(p);
    }

    /** Heuristic: a full, sturdy cube (stone, planks, logs, glass, terracotta...). */
    public static boolean isFullBlock(final BlockStates.Parsed p) {
        if (BlockStates.isAir(p.id()) || isLiquid(p)) {
            return false;
        }
        final String path = p.path();
        if (path.endsWith("_wall") || path.equals("snow")) {
            return false;
        }
        for (final String s : PARTIAL) {
            if (path.contains(s)) {
                return false;
            }
        }
        return true;
    }

    /** Placement priority inside one layer: 0 = clear to air, 1 = solid, 2 = attached/decoration. */
    public static int placementPriority(final BlockStates.Parsed p) {
        if (BlockStates.isAir(p.id())) {
            return 0;
        }
        final String path = p.path();
        for (final String s : ATTACHED) {
            if (path.contains(s)) {
                return 2;
            }
        }
        return 1;
    }
}
