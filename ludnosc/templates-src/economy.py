# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Smaller economic and civic buildings: piekarnia, browar, tartak, kopalnia, biblioteka, zielarnia, przystan,
cmentarz, pomnik, magazyn. Timber buildings use material slots (@wall, @frame, @roof_stairs...) so their look
follows the settlement level."""
from lib import B

AIR = '.'
K = '{kolor}'


def stair(mat, facing, half='bottom'):
    return f'{mat}_stairs[facing={facing},half={half}]'


def cottage(W, D, x1, z1, x2, z2, wall_h=3, roof_y=None):
    """Timber cottage shell with material slots; returns (b, tops). Door/windows/interior added by the caller."""
    H = wall_h + 2 + (z2 - z1 + 3) // 2 + 2
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    corners = b.corners(x1, z1, x2, z2)
    b.walls(x1, z1, x2, z2, 1, wall_h, '@wall')
    b.ring(x1, z1, x2, z2, 1, '@base')
    for y in range(1, wall_h + 1):
        for (x, z) in corners:
            b.set(x, y, z, '@frame[axis=y]')
    top = wall_h + 1
    for x in range(x1, x2 + 1):
        b.set(x, top, z1, '@frame[axis=x]')
        b.set(x, top, z2, '@frame[axis=x]')
    for z in range(z1, z2 + 1):
        b.set(x1, top, z, '@frame[axis=z]')
        b.set(x2, top, z, '@frame[axis=z]')
    for (x, z) in corners:
        b.set(x, top, z, '@frame[axis=y]')
    tops = b.gable_roof_x(0, W - 1, z1 - 1, z2 + 1, top, '@roof_stairs[facing=north,half=bottom]',
                          '@roof_stairs[facing=south,half=bottom]', '@roof_block', '@roof_slab[type=bottom]')
    for z in range(z1, z2 + 1):
        for y in range(top + 1, tops[z]):
            b.set(x1, y, z, '@wall')
            b.set(x2, y, z, '@wall')
    return b, tops


# ------------------------------------------------------------------------------------------------ piekarnia (bakery)
def piekarnia():
    b, tops = cottage(9, 8, 1, 1, 7, 6)
    b.door(4, 1, 6, 'north', 'left', 'spruce')
    b.torch(3, 2, 7, 'south')
    b.torch(5, 2, 7, 'south')
    for (x, y, z) in [(1, 2, 3), (1, 2, 4), (7, 2, 3), (2, 2, 6), (6, 2, 6), (3, 2, 1)]:
        b.set(x, y, z, '@window')
    # brick oven with chimney in the back right corner
    for x in (5, 6):
        b.set(x, 1, 2, 'bricks')
        b.set(x, 3, 2, 'bricks')
    b.set(5, 2, 2, 'furnace[facing=south]')
    b.set(6, 2, 2, 'smoker[facing=south]')
    for y in range(4, tops[2] + 3):
        b.set(6, y, 1, 'bricks')
    b.set(6, tops[2] + 3, 1, 'campfire[lit=true,signal_fire=false,facing=south]')
    b.set(4, 1, 2, 'furnace[facing=south]')
    # flour and bread
    b.set(2, 1, 2, 'barrel[facing=up]')
    b.set(2, 2, 2, 'barrel[facing=up]')
    b.set(2, 1, 3, 'hay_block[axis=y]')
    b.set(2, 1, 5, 'crafting_table')
    for x in (4, 5, 6):
        b.set(x, 1, 4, 'spruce_slab[type=top]')
    b.set(6, 1, 5, 'barrel[facing=up]')
    b.set(4, tops[3] - 1, 3, 'lantern[hanging=true]')
    return b


# ------------------------------------------------------------------------------------------------ browar (brewery)
def browar():
    b, tops = cottage(11, 9, 1, 1, 9, 7)
    b.door(5, 1, 7, 'north', 'left', 'spruce')
    b.torch(4, 2, 8, 'south')
    b.torch(6, 2, 8, 'south')
    for (x, y, z) in [(1, 2, 3), (1, 2, 5), (9, 2, 3), (9, 2, 5), (2, 2, 7), (8, 2, 7), (3, 2, 1), (7, 2, 1)]:
        b.set(x, y, z, '@window')
    # barrel racks (lying barrels) along the side walls
    for z in (2, 3, 4, 5):
        b.set(2, 1, z, 'barrel[facing=east]')
        b.set(2, 2, z, 'barrel[facing=east]')
        b.set(8, 1, z, 'barrel[facing=west]')
    b.set(8, 2, 3, 'barrel[facing=west]')
    # brewing corner
    b.set(4, 1, 2, 'water_cauldron[level=3]')
    b.set(5, 1, 2, 'brewing_stand')
    b.set(6, 1, 2, 'water_cauldron[level=3]')
    b.set(5, 1, 4, 'spruce_fence')
    b.set(5, 2, 4, 'spruce_pressure_plate')
    b.set(4, 1, 4, stair('spruce', 'east'))
    b.set(6, 1, 4, stair('spruce', 'west'))
    b.set(3, 1, 6, 'composter')
    b.set(5, tops[4] - 1, 4, 'lantern[hanging=true]')
    b.set(3, 3, 4, 'lantern[hanging=true]') if False else None
    return b


# ------------------------------------------------------------------------------------------------ tartak (sawmill)
def tartak():
    W, H, D = 11, 11, 9
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(1, 0, 1, 9, 0, 7, 'coarse_dirt')
    b.ring(1, 1, 9, 7, 0, '@base')
    # open shed on posts, back wall of planks
    for y in range(1, 4):
        for (x, z) in [(1, 1), (5, 1), (9, 1), (1, 7), (5, 7), (9, 7)]:
            b.set(x, y, z, '@frame[axis=y]')
        for x in range(2, 9):
            if x != 5:
                b.set(x, y, 1, '@wall')
    for x in range(1, 10):
        b.set(x, 4, 1, '@frame[axis=x]')
        b.set(x, 4, 7, '@frame[axis=x]')
    for z in range(1, 8):
        b.set(1, 4, z, '@frame[axis=z]')
        b.set(9, 4, z, '@frame[axis=z]')
    tops = b.gable_roof_x(0, W - 1, 0, D - 1, 4, '@roof_stairs[facing=north,half=bottom]',
                          '@roof_stairs[facing=south,half=bottom]', '@roof_block', '@roof_slab[type=bottom]')
    # saw bench, log piles, planks
    b.set(5, 1, 4, 'stonecutter[facing=south]')
    b.set(4, 1, 4, 'stripped_spruce_log[axis=x]')
    b.set(6, 1, 4, 'stripped_spruce_log[axis=x]')
    for x in range(2, 5):
        b.set(x, 1, 2, 'spruce_log[axis=x]')
        b.set(x, 2, 2, 'spruce_log[axis=x]')
        b.set(x, 1, 3, 'oak_log[axis=x]')
    for x in range(6, 9):
        b.set(x, 1, 2, 'spruce_planks')
        b.set(x, 1, 6, 'birch_log[axis=x]')
    b.set(7, 2, 2, 'spruce_slab[type=bottom]')
    b.set(2, 1, 6, 'grindstone[face=floor,facing=east]')
    b.set(3, 1, 6, 'chest[facing=north]')
    b.set(5, 3, 4, 'lantern[hanging=true]')
    b.set(5, tops[4] - 1, 4, 'lantern[hanging=true]')
    b.torch(5, 2, 8, 'south') if False else None
    b.set(1, 1, 8, 'spruce_fence')
    b.set(1, 2, 8, 'lantern[hanging=false]')
    b.set(9, 1, 8, 'spruce_fence')
    b.set(9, 2, 8, 'lantern[hanging=false]')
    return b


# ------------------------------------------------------------------------------------------------ kopalnia (mine head)
def kopalnia():
    W, D = 9, 9
    g = 8
    H = g + 7
    b = B(W, H, D)
    b.fill(0, g + 1, 0, W - 1, H - 1, D - 1, AIR)
    # shaft 3x3 (x3..5, z3..5) lined with cobblestone, ladder on the north side, floor of stone at the bottom
    for y in range(0, g + 1):
        b.ring(2, 2, 6, 6, y, 'cobblestone' if y < g else 'stone_bricks')
        b.fill(3, y, 3, 5, y, 5, AIR if y > 0 else 'stone')
    for y in range(1, g + 1):
        b.set(4, y, 3, 'ladder[facing=south]')
    for y in (2, 5):
        b.set(5, y, 5, 'wall_torch[facing=west]') if False else None
        b.torch(3, y, 5, 'east') if False else None
    b.set(4, 1, 5, 'lantern[hanging=false]')
    # doorway of the gallery on the east side (the miners dig on from here)
    b.set(6, 1, 4, AIR)
    b.set(6, 2, 4, AIR)
    # ground: plank deck around the opening, railing
    b.fill(1, g, 1, 7, g, 7, 'spruce_planks')
    b.ring(2, 2, 6, 6, g, 'stone_bricks')
    b.fill(3, g, 3, 5, g, 5, AIR)
    b.set(4, g, 3, 'ladder[facing=south]')
    for x in (3, 5):
        b.set(x, g + 1, 2, 'spruce_fence')
    for z in (3, 4, 5):
        b.set(2, g + 1, z, 'spruce_fence')
        b.set(6, g + 1, z, 'spruce_fence')
    b.set(3, g + 1, 6, 'spruce_fence')
    b.set(5, g + 1, 6, 'spruce_fence')
    b.set(4, g + 1, 6, 'spruce_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
    # headframe: four posts, beams, winch with chain over the shaft, small roof
    for (x, z) in [(1, 1), (7, 1), (1, 7), (7, 7)]:
        for y in range(g + 1, g + 5):
            b.set(x, y, z, '@frame[axis=y]')
    for x in range(1, 8):
        b.set(x, g + 5, 1, '@frame[axis=x]')
        b.set(x, g + 5, 7, '@frame[axis=x]')
    for z in range(1, 8):
        b.set(4, g + 5, z, '@frame[axis=z]')
    b.set(4, g + 4, 4, 'iron_chain[axis=y]')
    b.set(4, g + 3, 4, 'iron_chain[axis=y]')
    b.set(4, g + 2, 4, 'lantern[hanging=true]')
    b.gable_roof_x(0, 8, 0, 8, g + 6, '@roof_stairs[facing=north,half=bottom]', '@roof_stairs[facing=south,half=bottom]',
                   '@roof_block', '@roof_slab[type=bottom]') if False else None
    for x in range(0, 9):
        for z in range(0, 9):
            if x in (0, 8) or z in (0, 8):
                continue
    b.fill(1, g + 6, 1, 7, g + 6, 7, '@roof_slab[type=bottom]')
    # ore carts and tools at the side
    b.set(1, g + 1, 4, 'barrel[facing=up]')
    b.set(7, g + 1, 4, 'chest[facing=west]')
    b.set(7, g + 1, 3, 'cobblestone')
    b.set(1, g + 1, 3, 'iron_ore') if False else None
    b.set(7, g + 2, 7, 'lantern[hanging=false]') if False else None
    b.set(1, g + 1, 2, 'rail')
    b.set(1, g + 1, 5, 'rail')
    return b, g


# ------------------------------------------------------------------------------------------------ biblioteka (library)
def biblioteka():
    b, tops = cottage(11, 9, 1, 1, 9, 7, wall_h=4)
    b.door(5, 1, 7, 'north', 'left', 'spruce')
    b.torch(4, 2, 8, 'south')
    b.torch(6, 2, 8, 'south')
    for z in (3, 5):
        for y in (2, 3):
            b.set(1, y, z, '@window')
            b.set(9, y, z, '@window')
    for x in (3, 7):
        for y in (2, 3):
            b.set(x, y, 7, '@window')
    # shelves along the walls
    for x in range(2, 9):
        b.set(x, 1, 2, 'bookshelf')
        b.set(x, 2, 2, 'bookshelf')
        b.set(x, 3, 2, 'bookshelf') if x not in (4, 6) else None
    for z in (3, 4, 5):
        b.set(2, 1, z, 'bookshelf')
        b.set(8, 1, z, 'bookshelf')
    b.set(5, 1, 4, 'lectern[facing=south]')
    b.set(4, 1, 5, 'oak_fence')
    b.set(4, 2, 5, f'{K}_carpet')
    b.set(6, 1, 5, 'oak_fence')
    b.set(6, 2, 5, f'{K}_carpet')
    b.set(3, 1, 6, 'chiseled_bookshelf[facing=south]') if False else None
    b.set(7, 1, 6, 'cartography_table')
    b.set(3, 1, 6, 'enchanting_table')
    b.set(5, 4, 4, 'lantern[hanging=true]') if False else None
    b.set(5, tops[4] - 1, 4, 'lantern[hanging=true]')
    b.set(3, 4, 4, 'lantern[hanging=false]') if False else None
    return b


# ------------------------------------------------------------------------------------------------ zielarnia (herbalist)
def zielarnia():
    W, D = 9, 11
    b, tops = cottage(W, D, 1, 1, 7, 6)
    b.door(4, 1, 6, 'north', 'left', 'spruce')
    b.torch(3, 2, 7, 'south')
    b.torch(5, 2, 7, 'south')
    for (x, y, z) in [(1, 2, 3), (7, 2, 3), (2, 2, 6), (6, 2, 6), (3, 2, 1), (5, 2, 1)]:
        b.set(x, y, z, '@window')
    b.set(2, 1, 2, 'brewing_stand')
    b.set(3, 1, 2, 'water_cauldron[level=3]')
    b.set(5, 1, 2, 'composter')
    b.set(6, 1, 2, 'barrel[facing=up]')
    for (x, z) in [(2, 4), (6, 4)]:
        b.set(x, 1, z, 'spruce_slab[type=top]')
    b.set(2, 2, 4, 'potted_red_tulip')
    b.set(6, 2, 4, 'potted_blue_orchid')
    b.set(6, 1, 5, 'potted_fern')
    b.set(4, tops[3] - 1, 3, 'lantern[hanging=true]')
    # herb garden in front (z8..9), fenced
    for x in range(1, 8):
        for z in (8, 9):
            b.set(x, 0, z, 'grass_block[snowy=false]')
    flowers = ['allium', 'cornflower', 'oxeye_daisy', 'azure_bluet', 'poppy', 'lily_of_the_valley', 'dandelion']
    for i, x in enumerate(range(1, 8)):
        if x == 4:
            b.set(x, 0, 8, 'dirt_path')
            b.set(x, 0, 9, 'dirt_path')
            continue
        b.set(x, 1, 8, flowers[i % len(flowers)])
        b.set(x, 1, 9, flowers[(i + 3) % len(flowers)])
    for x in range(0, 9):
        if x != 4:
            b.set(x, 1, 10, '@fence')
    for z in range(7, 11):
        b.set(0, 1, z, '@fence')
        b.set(8, 1, z, '@fence')
    b.set(4, 1, 10, 'spruce_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
    b.set(0, 2, 10, 'lantern[hanging=false]')
    b.set(8, 2, 10, 'lantern[hanging=false]')
    return b


# ------------------------------------------------------------------------------------------------ przystan (fishing harbour hut)
def przystan():
    W, D = 9, 11
    b, tops = cottage(W, D, 1, 1, 7, 6)
    b.door(4, 1, 6, 'north', 'left', 'spruce')
    for (x, y, z) in [(1, 2, 3), (7, 2, 3), (2, 2, 6), (6, 2, 6)]:
        b.set(x, y, z, '@window')
    b.set(2, 1, 2, 'barrel[facing=up]')
    b.set(3, 1, 2, 'barrel[facing=up]')
    b.set(2, 2, 2, 'barrel[facing=up]')
    b.set(5, 1, 2, 'smoker[facing=south]')
    b.set(6, 1, 2, 'chest[facing=south]')
    b.set(6, 1, 4, 'barrel[facing=west]')
    b.set(4, tops[3] - 1, 3, 'lantern[hanging=true]')
    # deck and jetty in front (z7..10) on posts
    for z in range(7, 11):
        for x in range(2, 7):
            b.set(x, 0, z, 'spruce_planks')
    for z in (8, 10):
        b.set(2, 0, z, 'spruce_log[axis=y]')
        b.set(6, 0, z, 'spruce_log[axis=y]')
    for z in range(7, 11):
        b.set(2, 1, z, 'spruce_fence')
        b.set(6, 1, z, 'spruce_fence')
    b.set(2, 2, 10, 'lantern[hanging=false]')
    b.set(6, 2, 10, 'lantern[hanging=false]')
    b.set(3, 1, 9, 'barrel[facing=up]')
    b.set(5, 1, 8, 'composter') if False else None
    b.torch(3, 2, 7, 'south')
    b.torch(5, 2, 7, 'south')
    return b


# ------------------------------------------------------------------------------------------------ cmentarz (cemetery)
def cmentarz():
    W, H, D = 11, 5, 11
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    for z in range(D):
        for x in range(W):
            b.set(x, 0, z, 'grass_block[snowy=false]')
    for z in range(D):
        b.set(5, 0, z, 'gravel')
    b.ring(0, 0, W - 1, D - 1, 1, 'dark_oak_fence')
    b.set(5, 1, D - 1, 'dark_oak_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
    # graves: headstone + mound + flower
    for gz in (2, 5, 8):
        for gx in (2, 3, 7, 8):
            if gz == 8 and gx in (3, 7):
                continue
            b.set(gx, 1, gz - 1, 'stone_brick_wall')
            b.set(gx, 0, gz, 'coarse_dirt')
            b.set(gx, 1, gz, 'poppy' if (gx + gz) % 2 else 'cornflower')
    # small cross / chapel point at the far end
    b.set(5, 1, 1, 'polished_andesite')
    b.set(5, 2, 1, 'stone_brick_wall')
    b.set(5, 3, 1, 'stone_brick_wall')
    b.set(4, 3, 1, 'stone_brick_wall')
    b.set(6, 3, 1, 'stone_brick_wall')
    b.set(5, 4, 1, 'candle[candles=1,lit=true]') if False else None
    b.set(5, 4, 1, 'lantern[hanging=false]')
    for (x, z) in [(0, 0), (W - 1, 0), (0, D - 1), (W - 1, D - 1)]:
        b.set(x, 2, z, 'lantern[hanging=false]')
    b.set(4, 1, 5, 'oak_sapling[stage=0]') if False else None
    return b


# ------------------------------------------------------------------------------------------------ pomnik (monument of the ruler)
def pomnik():
    W, H, D = 7, 10, 7
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(0, 0, 0, 6, 0, 6, 'polished_andesite')
    # stepped plinth
    b.ring(1, 1, 5, 5, 1, 'polished_andesite')
    for x in range(1, 6):
        b.set(x, 1, 1, 'polished_andesite_stairs[facing=south,half=bottom]')
        b.set(x, 1, 5, 'polished_andesite_stairs[facing=north,half=bottom]')
    for z in range(2, 5):
        b.set(1, 1, z, 'polished_andesite_stairs[facing=east,half=bottom]')
        b.set(5, 1, z, 'polished_andesite_stairs[facing=west,half=bottom]')
    b.fill(2, 1, 2, 4, 1, 4, 'chiseled_stone_bricks')
    b.fill(2, 2, 2, 4, 2, 4, 'stone_bricks')
    # the statue: robe, arms, head and crown
    b.set(3, 3, 3, 'polished_deepslate')
    b.set(3, 4, 3, 'polished_deepslate')
    b.set(3, 5, 3, f'{K}_wool')
    b.set(2, 5, 3, 'polished_deepslate_wall') if False else None
    b.set(3, 6, 3, 'chiseled_quartz_block')
    b.set(3, 7, 3, 'gold_block')
    b.set(2, 4, 3, 'polished_deepslate_wall')
    b.set(4, 4, 3, 'polished_deepslate_wall')
    # banners around the plinth and lanterns on the corners
    b.set(3, 2, 1, f'{K}_wall_banner[facing=north]')
    b.set(3, 2, 5, f'{K}_wall_banner[facing=south]')
    b.set(1, 2, 3, f'{K}_wall_banner[facing=west]')
    b.set(5, 2, 3, f'{K}_wall_banner[facing=east]')
    for (x, z) in [(0, 0), (6, 0), (0, 6), (6, 6)]:
        b.set(x, 1, z, 'stone_brick_wall')
        b.set(x, 2, z, 'lantern[hanging=false]')
    return b


# ------------------------------------------------------------------------------------------------ magazyn (warehouse with cellar)
def magazyn():
    g = 3
    W, D = 11, 11
    shell, tops = cottage(W, D, 1, 1, 9, 9)
    # move the shell up by g layers and add the cellar below
    b = B(W, shell.h + g, D)
    b.pal = dict(shell.pal)
    b.rev = dict(shell.rev)
    for y in range(shell.h):
        for z in range(D):
            for x in range(W):
                b.g[y + g][z][x] = shell.g[y][z][x]
    # cellar: stone room x2..8, z2..8 under the floor, stairs down from the front
    for y in range(0, g):
        b.ring(1, 1, 9, 9, y, 'stone_bricks')
        b.fill(2, y, 2, 8, y, 8, AIR if y > 0 else 'stone')
    for z in range(2, 9):
        for x in (2, 3, 7, 8):
            if z % 2 == 0:
                b.set(x, 1, z, 'barrel[facing=up]')
                b.set(x, 2, z, 'barrel[facing=up]' if x in (2, 8) else AIR)
    b.set(5, 1, 2, 'chest[facing=south]')
    b.set(4, 1, 2, 'chest[facing=south]')
    b.set(5, 2, 5, 'lantern[hanging=true]')
    # stairs from the ground floor down to the cellar, walking north from the door side
    b.set(5, g, 8, 'stone_brick_stairs[facing=south,half=bottom]')
    b.set(5, g, 7, AIR)
    b.set(5, g, 6, AIR)
    b.set(5, 2, 7, 'stone_brick_stairs[facing=south,half=bottom]')
    b.set(5, 1, 6, 'stone_brick_stairs[facing=south,half=bottom]')
    b.set(5, 1, 8, 'stone_bricks')
    b.set(5, 2, 8, 'stone_bricks')
    b.set(5, 1, 7, 'stone_bricks')
    for z in (6, 7):
        b.set(4, g + 1, z, '@fence')
        b.set(6, g + 1, z, '@fence')
    # ground floor
    b.door(5, g + 1, 9, 'north', 'left', 'spruce')
    b.torch(4, g + 2, 10, 'south')
    b.torch(6, g + 2, 10, 'south')
    for (x, z) in [(1, 3), (1, 6), (9, 3), (9, 6)]:
        b.set(x, g + 2, z, '@window')
    for x in (2, 3, 7, 8):
        b.set(x, g + 1, 2, 'barrel[facing=up]')
        b.set(x, g + 1, 3, 'hay_block[axis=y]')
    b.set(5, g + 1, 2, 'chest[facing=south]')
    b.set(5, tops[5] + g - 1, 5, 'lantern[hanging=true]')
    return b, g
