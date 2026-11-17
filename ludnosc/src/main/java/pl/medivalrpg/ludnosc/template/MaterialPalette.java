// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Materials that grow with the settlement (pure, unit tested). Templates may use material slots instead of fixed
 * blocks: {@code @wall}, {@code @base}, {@code @frame[axis=y]}, {@code @roof_stairs[facing=north,half=bottom]},
 * {@code @roof_slab[type=bottom]}, {@code @roof_block}, {@code @floor}, {@code @window}, {@code @door[...]},
 * {@code @fence}. Each settlement level (Osada … Stolica) maps the slots to real blocks — one template, five
 * looks: wood and thatch → oak/spruce and cobble → stone, bricks, glass → stone bricks, dark oak → polished stone,
 * calcite, copper roofs, stained glass.
 */
public final class MaterialPalette {

    /** Slot prefix in block ids. */
    public static final char SLOT = '@';

    /** Default slot → block base name per level (index 0 = Osada). */
    /** Settlement levels per epoch (Osada … Metropolia) and epochs (Middle Ages … Modern). */
    public static final int LEVELS_PER_ERA = 6;
    public static final int ERAS = 4;

    /**
     * Default slot → block base name per style = (epoch − 1) × {@value #LEVELS_PER_ERA} + settlement level (style 0 =
     * Middle Ages, Osada). Middle Ages: wood and thatch → stone, copper; Renaissance: plaster, light stone, red tiles
     * (resin bricks); Industrial: brick, dark wood, slate, steel railings; Modern: concrete, quartz, dark glass, steel.
     * The same table is in {@code templates-src/palettes.py}.
     */
    public static final List<Map<String, String>> DEFAULTS = List.of(
        level("oak_planks", "cobblestone", "oak_log", "oak", "hay_block", "packed_mud", "oak_fence", "oak", "oak_fence"),
        level("spruce_planks", "cobblestone", "spruce_log", "spruce", "spruce_planks", "spruce_planks", "glass_pane", "spruce", "spruce_fence"),
        level("bricks", "stone", "spruce_log", "dark_oak", "dark_oak_planks", "spruce_planks", "glass_pane", "spruce", "spruce_fence"),
        level("stone_bricks", "stone_bricks", "dark_oak_log", "deepslate_tile", "deepslate_tiles", "dark_oak_planks", "glass_pane", "dark_oak", "dark_oak_fence"),
        level("calcite", "polished_andesite", "stripped_dark_oak_log", "waxed_cut_copper", "waxed_cut_copper", "polished_deepslate", "yellow_stained_glass_pane", "dark_oak", "dark_oak_fence"),
        level("calcite", "polished_andesite", "stripped_dark_oak_log", "waxed_cut_copper", "waxed_cut_copper", "polished_deepslate", "yellow_stained_glass_pane", "dark_oak", "dark_oak_fence"),
        level("white_terracotta", "cobblestone", "stripped_oak_log", "resin_brick", "resin_bricks", "oak_planks", "glass_pane", "oak", "oak_fence"),
        level("white_terracotta", "stone_bricks", "stripped_oak_log", "resin_brick", "resin_bricks", "oak_planks", "glass_pane", "oak", "oak_fence"),
        level("smooth_sandstone", "stone_bricks", "stripped_birch_log", "resin_brick", "resin_bricks", "birch_planks", "glass_pane", "birch", "birch_fence"),
        level("smooth_sandstone", "polished_andesite", "quartz_pillar", "resin_brick", "resin_bricks", "polished_andesite", "glass_pane", "birch", "birch_fence"),
        level("calcite", "polished_diorite", "quartz_pillar", "resin_brick", "resin_bricks", "polished_diorite", "glass_pane", "dark_oak", "dark_oak_fence"),
        level("quartz_bricks", "polished_diorite", "quartz_pillar", "resin_brick", "resin_bricks", "polished_diorite", "glass_pane", "dark_oak", "dark_oak_fence"),
        level("bricks", "cobblestone", "dark_oak_log", "deepslate_tile", "deepslate_tiles", "spruce_planks", "glass_pane", "spruce", "iron_bars"),
        level("bricks", "stone_bricks", "dark_oak_log", "deepslate_tile", "deepslate_tiles", "spruce_planks", "glass_pane", "spruce", "iron_bars"),
        level("bricks", "stone_bricks", "dark_oak_log", "deepslate_tile", "deepslate_tiles", "dark_oak_planks", "glass_pane", "dark_oak", "iron_bars"),
        level("bricks", "polished_blackstone_bricks", "polished_basalt", "deepslate_tile", "deepslate_tiles", "dark_oak_planks", "glass_pane", "dark_oak", "iron_bars"),
        level("bricks", "polished_blackstone_bricks", "polished_basalt", "deepslate_tile", "deepslate_tiles", "polished_deepslate", "glass_pane", "dark_oak", "iron_bars"),
        level("bricks", "polished_blackstone_bricks", "polished_basalt", "deepslate_tile", "deepslate_tiles", "polished_deepslate", "glass_pane", "dark_oak", "iron_bars"),
        level("light_gray_concrete", "gray_concrete", "gray_concrete", "polished_andesite", "gray_concrete", "smooth_stone", "glass_pane", "spruce", "iron_bars"),
        level("white_concrete", "gray_concrete", "light_gray_concrete", "polished_andesite", "light_gray_concrete", "smooth_stone", "gray_stained_glass_pane", "birch", "iron_bars"),
        level("white_concrete", "light_gray_concrete", "gray_concrete", "smooth_quartz", "white_concrete", "polished_andesite", "light_gray_stained_glass_pane", "dark_oak", "iron_bars"),
        level("smooth_quartz", "gray_concrete", "quartz_pillar", "smooth_quartz", "smooth_quartz", "smooth_quartz", "black_stained_glass_pane", "dark_oak", "iron_bars"),
        level("white_concrete", "polished_deepslate", "quartz_pillar", "smooth_quartz", "white_concrete", "smooth_quartz", "black_stained_glass_pane", "waxed_copper", "iron_bars"),
        level("quartz_block", "polished_deepslate", "quartz_pillar", "smooth_quartz", "quartz_block", "smooth_quartz", "black_stained_glass_pane", "dark_oak", "iron_bars"));

    /** Style index of an epoch (1..4) and settlement level index (0..5). */
    public static int style(final int era, final int level) {
        return (Math.max(1, Math.min(ERAS, era)) - 1) * LEVELS_PER_ERA + Math.max(0, Math.min(LEVELS_PER_ERA - 1, level));
    }

    public static int eraOf(final int style) {
        return Math.max(0, style) / LEVELS_PER_ERA + 1;
    }

    public static int levelOf(final int style) {
        return Math.max(0, style) % LEVELS_PER_ERA;
    }

    private final List<Map<String, String>> levels;

    private static Map<String, String> level(final String wall, final String base, final String frame, final String roof,
                                             final String roofBlock, final String floor, final String window, final String door,
                                             final String fence) {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("wall", wall);
        m.put("base", base);
        m.put("frame", frame);
        m.put("roof", roof);
        m.put("roof_block", roofBlock);
        m.put("floor", floor);
        m.put("window", window);
        m.put("door", door);
        m.put("fence", fence);
        return Map.copyOf(m);
    }

    public MaterialPalette(final List<Map<String, String>> overrides) {
        final List<Map<String, String>> out = new ArrayList<>();
        for (int i = 0; i < DEFAULTS.size(); i++) {
            final Map<String, String> m = new HashMap<>(DEFAULTS.get(i));
            if (overrides != null && i < overrides.size() && overrides.get(i) != null) {
                m.putAll(overrides.get(i));
            }
            out.add(Map.copyOf(m));
        }
        this.levels = List.copyOf(out);
    }

    public static MaterialPalette defaults() {
        return new MaterialPalette(null);
    }

    public int levels() {
        return this.levels.size();
    }

    public static boolean isSlot(final String state) {
        return state != null && state.indexOf(SLOT) >= 0;
    }

    /** Block id for a slot name ({@code wall}, {@code roof_stairs}...) at a level. */
    public String block(final String slot, final int level) {
        return this.block(slot, level, null);
    }

    /** Block for a slot, with template-specific overrides of that level (may be null). */
    public String block(final String slot, final int level, final Map<String, String> override) {
        final Map<String, String> table = new HashMap<>(this.levels.get(Math.max(0, Math.min(level, this.levels.size() - 1))));
        if (override != null) {
            table.putAll(override);
        }
        return switch (slot) {
            case "roof_stairs" -> table.get("roof") + "_stairs";
            case "roof_slab" -> table.get("roof") + "_slab";
            case "door" -> table.get("door") + "_door";
            default -> {
                final String v = table.get(slot);
                if (v == null) {
                    throw new IllegalArgumentException("Nieznany slot materiału @" + slot);
                }
                yield v;
            }
        };
    }

    /** Resolves a state with a slot id ({@code @frame[axis=y]}) at a level; other states unchanged. */
    public String resolve(final String state, final int level) {
        return this.resolve(state, level, null);
    }

    /** Resolves with template-specific overrides of the level (may be null). */
    public String resolve(final String state, final int level, final Map<String, String> override) {
        if (!isSlot(state)) {
            return state;
        }
        final BlockStates.Parsed p = BlockStates.parse(state);
        String slot = p.id();
        final int at = slot.indexOf(SLOT);
        slot = slot.substring(at + 1);
        final String block = this.block(slot, level, override);
        final Map<String, String> props = new LinkedHashMap<>(p.properties());
        // logs keep their axis; non-log frames (no axis) drop it
        if (!block.endsWith("_log") && !block.endsWith("_wood") && !block.endsWith("_pillar") && !block.endsWith("basalt")) {
            props.remove("axis");
        }
        if (block.endsWith("_fence") || block.endsWith("_pane") || block.endsWith("iron_bars")) {
            for (final String d : BlockStates.HORIZONTAL) {
                props.remove(d);
            }
        }
        return new BlockStates.Parsed(BlockStates.normalize(block).replaceAll("\\[.*", ""), props).format();
    }

    /**
     * Stored level → level index: {@code 0} (data written before material levels existed) means the classic look,
     * which every template keeps at level 1 (Wieś); otherwise {@code stored - 1}.
     */
    public static int decode(final int stored) {
        return stored <= 0 ? 1 : stored - 1;
    }

    public static int encode(final int level) {
        return level + 1;
    }

    /** Every slot name a template may use. */
    public static List<String> slots() {
        return List.of("wall", "base", "frame", "roof_stairs", "roof_slab", "roof_block", "floor", "window", "door", "fence");
    }
}
