// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Realistic building order computed from block categories and heights (pure, no per-template tuning):
 * excavation (top-down) → foundation/floor → walls layer by layer (posts and corners first, then infill) →
 * ceilings → roof from the eaves to the ridge → doors and windows → interior → light and decoration.
 */
public final class BuildPhases {

    public enum Phase {
        /** Clearing to air, from the top down. */
        EXCAVATE,
        /** Ground layer and everything below it (floor, cellar, well shaft). */
        FOUNDATION,
        /** Logs, beams, pillars. */
        FRAME,
        /** Full blocks of walls and gables (vertical continuity). */
        WALL,
        /** Horizontal full sheets without blocks above/below (storey floors, platforms, flat roofs). */
        FLOOR,
        /** Stairs/slabs/blocks of the roof. */
        ROOF,
        /** Doors, panes, bars, trapdoors, fences, gates. */
        OPENING,
        /** Beds, furniture, workstations, carpets, ladders, chairs. */
        INTERIOR,
        /** Torches, lanterns, banners, flowers, chains, bells, liquids above ground. */
        DETAIL
    }

    private static final List<String> DETAIL = List.of("torch", "lantern", "banner", "flower_pot", "potted_", "chain",
        "campfire", "candle", "bell", "lightning_rod", "carved_pumpkin", "_head", "skull", "sign", "poppy", "dandelion",
        "cornflower", "allium", "azure_bluet", "oxeye_daisy", "tulip", "lily_of_the_valley", "blue_orchid", "rose_bush",
        "sunflower", "lilac", "peony", "fern", "short_grass", "tall_grass", "sapling", "end_rod");
    private static final List<String> OPENING = List.of("_door", "pane", "iron_bars", "trapdoor", "fence", "@window", "@door");
    private static final List<String> INTERIOR = List.of("_bed", "chest", "barrel", "crafting_table", "furnace", "smoker",
        "bookshelf", "lectern", "anvil", "grindstone", "smithing_table", "fletching_table", "cartography_table", "loom",
        "stonecutter", "composter", "cauldron", "brewing_stand", "enchanting_table", "jukebox", "note_block", "hay_block",
        "carpet", "pressure_plate", "ladder", "melon", "pumpkin", "beehive", "button", "lever");
    private static final Set<String> FRAME_SUFFIXES = Set.of("_log", "_wood", "_stem", "_hyphae", "_pillar");

    private BuildPhases() {
    }

    private static boolean contains(final String path, final List<String> keys) {
        for (final String k : keys) {
            if (path.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFrame(final String path) {
        if (path.equals("@frame")) {
            return true;
        }
        for (final String s : FRAME_SUFFIXES) {
            if (path.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stairOrSlab(final String path) {
        return path.endsWith("_stairs") || path.endsWith("_slab");
    }

    /** Phase of every cell ({@code null} for "keep terrain" cells). */
    public static Phase[] classify(final BuildingTemplate t) {
        final Map<String, BlockStates.Parsed> cache = new HashMap<>();
        final Phase[] out = new Phase[t.volume()];
        final int ground = t.meta().groundLayer();
        // the roof starts at the lowest overhanging stair/slab above the ground storey
        int roofStart = t.height();
        for (int i = 0; i < t.volume(); i++) {
            final String s = t.state(i);
            if (s == null) {
                continue;
            }
            final BlockStates.Parsed p = cache.computeIfAbsent(s, BlockStates::parse);
            final int y = t.yOf(i);
            if (y > ground + 2 && stairOrSlab(p.path()) && !fullAt(t, cache, t.xOf(i), y - 1, t.zOf(i))) {
                roofStart = Math.min(roofStart, y);
            }
        }
        for (int i = 0; i < t.volume(); i++) {
            final String s = t.state(i);
            if (s == null) {
                continue;
            }
            final BlockStates.Parsed p = cache.computeIfAbsent(s, BlockStates::parse);
            final String path = p.path();
            final int x = t.xOf(i);
            final int y = t.yOf(i);
            final int z = t.zOf(i);
            if (BlockStates.isAir(p.id())) {
                out[i] = Phase.EXCAVATE;
            } else if (y <= ground) {
                out[i] = Phase.FOUNDATION;
            } else if (BlockKinds.isLiquid(p) || contains(path, DETAIL)) {
                out[i] = Phase.DETAIL;
            } else if (contains(path, OPENING) && !path.endsWith("_wall")) {
                out[i] = Phase.OPENING;
            } else if (contains(path, INTERIOR)) {
                out[i] = Phase.INTERIOR;
            } else if (stairOrSlab(path)) {
                final boolean overhang = !fullAt(t, cache, x, y - 1, z);
                out[i] = y >= roofStart || overhang && y > ground + 1 ? Phase.ROOF : Phase.INTERIOR;
            } else if (isFrame(path)) {
                out[i] = Phase.FRAME;
            } else if (path.endsWith("_wall")) {
                out[i] = Phase.WALL;
            } else if (BlockKinds.isFullBlock(p)) {
                final boolean below = fullAt(t, cache, x, y - 1, z);
                final boolean above = fullAt(t, cache, x, y + 1, z);
                if (below) {
                    out[i] = Phase.WALL;
                } else if (y >= roofStart) {
                    out[i] = Phase.ROOF;
                } else if (above) {
                    out[i] = Phase.WALL;
                } else {
                    out[i] = Phase.FLOOR;
                }
            } else {
                out[i] = Phase.INTERIOR;
            }
        }
        return out;
    }

    private static boolean fullAt(final BuildingTemplate t, final Map<String, BlockStates.Parsed> cache, final int x,
                                  final int y, final int z) {
        final String s = t.state(x, y, z);
        return s != null && !BlockStates.isAir(s) && BlockKinds.isFullBlock(cache.computeIfAbsent(s, BlockStates::parse));
    }

    /** Sort rank of a phase; FRAME and WALL share one rank (walls go up layer by layer, posts first). */
    public static int rank(final Phase p) {
        return switch (p) {
            case EXCAVATE -> 0;
            case FOUNDATION -> 1;
            case FRAME, WALL -> 2;
            case FLOOR -> 3;
            case ROOF -> 4;
            case OPENING -> 5;
            case INTERIOR -> 6;
            case DETAIL -> 7;
        };
    }

    /** Cell indices in building order ("keep" cells skipped). */
    public static int[] order(final BuildingTemplate t) {
        final Phase[] phase = classify(t);
        final double cx = (t.width() - 1) / 2.0;
        final double cz = (t.depth() - 1) / 2.0;
        final List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < phase.length; i++) {
            if (phase[i] != null) {
                idx.add(i);
            }
        }
        idx.sort(Comparator.<Integer>comparingInt(i -> rank(phase[i]))
            // excavation top-down, everything else bottom-up
            .thenComparingInt(i -> phase[i] == Phase.EXCAVATE ? -t.yOf(i) : t.yOf(i))
            .thenComparingInt(i -> phase[i] == Phase.WALL ? 1 : 0)
            // roof rows of one height: outer (eaves) first; other phases: north-west → south-east
            .thenComparingDouble(i -> phase[i] == Phase.ROOF
                ? -(Math.abs(t.xOf(i) - cx) + Math.abs(t.zOf(i) - cz)) : 0)
            .thenComparingInt(t::zOf)
            .thenComparingInt(t::xOf));
        return idx.stream().mapToInt(Integer::intValue).toArray();
    }
}
