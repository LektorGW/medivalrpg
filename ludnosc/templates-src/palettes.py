# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Material palettes: one per (epoch, settlement level) = "style" index (epoch - 1) * 6 + level.
Epochs: 1 Middle Ages, 2 Renaissance, 3 Industrial, 4 Modern. Levels: Osada, Wies, Miasteczko, Miasto, Stolica,
Metropolia. The same table is in MaterialPalette.java (python palettes.py --java prints it)."""
import sys

KEYS = ['wall', 'base', 'frame', 'roof', 'roof_block', 'floor', 'window', 'door', 'fence']


def L(*v):
    return dict(zip(KEYS, v))


MEDIEVAL = [
    L('oak_planks', 'cobblestone', 'oak_log', 'oak', 'hay_block', 'packed_mud', 'oak_fence', 'oak', 'oak_fence'),
    L('spruce_planks', 'cobblestone', 'spruce_log', 'spruce', 'spruce_planks', 'spruce_planks', 'glass_pane', 'spruce', 'spruce_fence'),
    L('bricks', 'stone', 'spruce_log', 'dark_oak', 'dark_oak_planks', 'spruce_planks', 'glass_pane', 'spruce', 'spruce_fence'),
    L('stone_bricks', 'stone_bricks', 'dark_oak_log', 'deepslate_tile', 'deepslate_tiles', 'dark_oak_planks', 'glass_pane', 'dark_oak',
      'dark_oak_fence'),
    L('calcite', 'polished_andesite', 'stripped_dark_oak_log', 'waxed_cut_copper', 'waxed_cut_copper', 'polished_deepslate',
      'yellow_stained_glass_pane', 'dark_oak', 'dark_oak_fence'),
    L('calcite', 'polished_andesite', 'stripped_dark_oak_log', 'waxed_cut_copper', 'waxed_cut_copper', 'polished_deepslate',
      'yellow_stained_glass_pane', 'dark_oak', 'dark_oak_fence'),
]
RENAISSANCE = [
    L('white_terracotta', 'cobblestone', 'stripped_oak_log', 'resin_brick', 'resin_bricks', 'oak_planks', 'glass_pane', 'oak', 'oak_fence'),
    L('white_terracotta', 'stone_bricks', 'stripped_oak_log', 'resin_brick', 'resin_bricks', 'oak_planks', 'glass_pane', 'oak', 'oak_fence'),
    L('smooth_sandstone', 'stone_bricks', 'stripped_birch_log', 'resin_brick', 'resin_bricks', 'birch_planks', 'glass_pane', 'birch',
      'birch_fence'),
    L('smooth_sandstone', 'polished_andesite', 'quartz_pillar', 'resin_brick', 'resin_bricks', 'polished_andesite', 'glass_pane', 'birch',
      'birch_fence'),
    L('calcite', 'polished_diorite', 'quartz_pillar', 'resin_brick', 'resin_bricks', 'polished_diorite', 'glass_pane', 'dark_oak',
      'dark_oak_fence'),
    L('quartz_bricks', 'polished_diorite', 'quartz_pillar', 'resin_brick', 'resin_bricks', 'polished_diorite', 'glass_pane', 'dark_oak',
      'dark_oak_fence'),
]
INDUSTRIAL = [
    L('bricks', 'cobblestone', 'dark_oak_log', 'deepslate_tile', 'deepslate_tiles', 'spruce_planks', 'glass_pane', 'spruce', 'iron_bars'),
    L('bricks', 'stone_bricks', 'dark_oak_log', 'deepslate_tile', 'deepslate_tiles', 'spruce_planks', 'glass_pane', 'spruce', 'iron_bars'),
    L('bricks', 'stone_bricks', 'dark_oak_log', 'deepslate_tile', 'deepslate_tiles', 'dark_oak_planks', 'glass_pane', 'dark_oak',
      'iron_bars'),
    L('bricks', 'polished_blackstone_bricks', 'polished_basalt', 'deepslate_tile', 'deepslate_tiles', 'dark_oak_planks', 'glass_pane',
      'dark_oak', 'iron_bars'),
    L('bricks', 'polished_blackstone_bricks', 'polished_basalt', 'deepslate_tile', 'deepslate_tiles', 'polished_deepslate', 'glass_pane',
      'dark_oak', 'iron_bars'),
    L('bricks', 'polished_blackstone_bricks', 'polished_basalt', 'deepslate_tile', 'deepslate_tiles', 'polished_deepslate', 'glass_pane',
      'dark_oak', 'iron_bars'),
]
MODERN = [
    L('light_gray_concrete', 'gray_concrete', 'gray_concrete', 'polished_andesite', 'gray_concrete', 'smooth_stone', 'glass_pane', 'spruce',
      'iron_bars'),
    L('white_concrete', 'gray_concrete', 'light_gray_concrete', 'polished_andesite', 'light_gray_concrete', 'smooth_stone',
      'gray_stained_glass_pane', 'birch', 'iron_bars'),
    L('white_concrete', 'light_gray_concrete', 'gray_concrete', 'smooth_quartz', 'white_concrete', 'polished_andesite',
      'light_gray_stained_glass_pane', 'dark_oak', 'iron_bars'),
    L('smooth_quartz', 'gray_concrete', 'quartz_pillar', 'smooth_quartz', 'smooth_quartz', 'smooth_quartz', 'black_stained_glass_pane',
      'dark_oak', 'iron_bars'),
    L('white_concrete', 'polished_deepslate', 'quartz_pillar', 'smooth_quartz', 'white_concrete', 'smooth_quartz',
      'black_stained_glass_pane', 'waxed_copper', 'iron_bars'),
    L('quartz_block', 'polished_deepslate', 'quartz_pillar', 'smooth_quartz', 'quartz_block', 'smooth_quartz', 'black_stained_glass_pane',
      'dark_oak', 'iron_bars'),
]
STYLES = MEDIEVAL + RENAISSANCE + INDUSTRIAL + MODERN
PER_ERA = 6


def style(era, level):
    return (max(1, min(4, era)) - 1) * PER_ERA + max(0, min(PER_ERA - 1, level))


if __name__ == '__main__' and '--java' in sys.argv:
    for i, m in enumerate(STYLES):
        args = ', '.join(f'"{m[k]}"' for k in KEYS)
        print(f'        level({args}),' if i < len(STYLES) - 1 else f'        level({args}));')
