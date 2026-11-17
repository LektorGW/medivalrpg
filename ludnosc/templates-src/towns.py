# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Town buildings: karczma, mlyn, targ, ratusz, kosciol, stajnia, kamienica, mur, brama."""
from lib import B

AIR = '.'
K = '{kolor}'


def stair(mat, facing, half='bottom'):
    return f'{mat}_stairs[facing={facing},half={half}]'


def log(wood, axis='y'):
    return f'{wood}[axis={axis}]'


# ------------------------------------------------------------------------------------------------ karczma
def karczma():
    W, H, D = 13, 16, 9
    b = B(W, H, D)
    x1, x2, z1, z2 = 1, 11, 1, 7
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    # floor + covered porch in front (z8)
    b.fill(x1, 0, z1, x2, 0, z2, 'spruce_planks')
    b.ring(x1, z1, x2, z2, 0, 'cobblestone')
    b.fill(x1, 0, 8, x2, 0, 8, 'cobblestone')
    corners = b.corners(x1, z1, x2, z2)
    posts = corners + [(6, z1), (6, z2), (x1, 4), (x2, 4)]
    b.walls(x1, z1, x2, z2, 1, 3, 'stone_bricks')
    b.ring(x1, z1, x2, z2, 1, 'cobblestone')
    for y in range(1, 4):
        for (x, z) in posts:
            b.set(x, y, z, log('dark_oak_log'))
    b.fill(x1, 4, z1, x2, 4, z2, 'spruce_planks')
    b.beam_ring(x1, z1, x2, z2, 4, 'dark_oak_log')
    upper_posts = corners + [(4, z1), (8, z1), (4, z2), (8, z2), (x1, 4), (x2, 4)]
    b.walls(x1, z1, x2, z2, 5, 7, 'oak_planks')
    for y in range(5, 8):
        for (x, z) in upper_posts:
            b.set(x, y, z, log('dark_oak_log'))
    b.beam_ring(x1, z1, x2, z2, 8, 'dark_oak_log')
    tops = b.roof_x(0, W - 1, 0, D - 1, 8, 'spruce')
    b.fill_gable_x(x1, z1, z2, 9, tops, 'oak_planks')
    b.fill_gable_x(x2, z1, z2, 9, tops, 'oak_planks')
    b.set(x1, 10, 4, 'glass_pane')
    b.set(x2, 10, 4, 'glass_pane')
    # windows
    for x in (2, 3, 9, 10):
        b.set(x, 2, z2, 'glass_pane')
    for x in (3, 9):
        b.set(x, 2, z1, 'glass_pane')
    for z in (3, 5):
        b.set(x1, 2, z, 'glass_pane')
    for x in (2, 6, 10):
        b.set(x, 6, z2, 'glass_pane')
        b.set(x, 6, z1, 'glass_pane')
    b.set(x1, 6, 2, 'glass_pane')
    b.set(x2, 6, 6, 'glass_pane')
    # door, porch lights
    b.door(6, 1, z2, 'north', 'left', 'dark_oak')
    b.set(6, 3, z2, 'stone_bricks')
    b.torch(5, 2, 8, 'south')
    b.torch(7, 2, 8, 'south')
    b.set(1, 1, 8, 'barrel[facing=up]')
    b.set(1, 2, 8, 'lantern[hanging=false]')
    b.set(11, 1, 8, 'barrel[facing=up]')
    b.set(11, 2, 8, 'lantern[hanging=false]')
    b.set(3, 1, 8, stair('spruce', 'north'))
    b.set(9, 1, 8, stair('spruce', 'north'))
    # fireplace + chimney (east side)
    b.set(10, 1, 2, 'campfire[lit=true,signal_fire=false,facing=south]')
    for y in range(2, 14):
        b.set(10, y, 2, 'bricks')
    b.set(10, 14, 2, 'campfire[lit=true,signal_fire=false,facing=south]')
    b.set(9, 1, 2, 'bricks')
    b.set(9, 2, 2, 'bricks')
    # bar counter + kegs
    for x in (2, 3, 4):
        b.set(x, 1, 3, log('stripped_spruce_log', 'x'))
        b.set(x, 1, 2, 'barrel[facing=south]')
    b.set(3, 2, 3, 'brewing_stand')
    b.set(5, 1, 2, 'water_cauldron[level=3]')
    b.set(2, 2, 2, 'barrel[facing=up]')
    # tables and stools
    for x in (7, 8):
        b.set(x, 1, 4, 'spruce_fence')
        b.set(x, 2, 4, 'spruce_pressure_plate')
        b.set(x, 1, 3, stair('spruce', 'north'))
        b.set(x, 1, 5, stair('spruce', 'south'))
    b.set(3, 1, 6, 'spruce_fence')
    b.set(3, 2, 6, 'spruce_pressure_plate')
    b.set(4, 1, 6, stair('spruce', 'east'))
    b.set(10, 1, 6, 'crafting_table')
    for x in (5, 6, 7, 8, 9):
        b.set(x, 1, 6, 'red_carpet') if x in (5, 6) else None
    b.set(4, 3, 4, 'lantern[hanging=true]')
    b.set(8, 3, 3, 'lantern[hanging=true]')
    # ladder to the guest rooms
    b.ladder(2, 1, 4, 6, 'east')
    b.set(3, 5, 6, 'spruce_fence')
    # guest rooms: 4 beds
    for x in (3, 4, 8, 9):
        b.bed(x, 5, 2, 'north', 'green')
    b.set(6, 5, 2, 'chest[facing=south]')
    b.set(6, 6, 2, 'lantern[hanging=false]')
    b.set(5, 5, 4, 'brown_carpet')
    b.set(7, 5, 4, 'brown_carpet')
    b.set(10, 5, 4, 'barrel[facing=up]')
    b.set(6, 11, 4, 'lantern[hanging=true]')
    # hanging tavern banner by the door
    b.set(6, 4, 8, 'brown_wall_banner[facing=south]')
    return b


# ------------------------------------------------------------------------------------------------ mlyn (windmill)
def mlyn():
    W, H, D = 9, 17, 10
    b = B(W, H, D)
    x1, x2, z1, z2 = 1, 7, 1, 7
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'spruce_planks')
    b.ring(x1, z1, x2, z2, 0, 'cobblestone')
    corners = b.corners(x1, z1, x2, z2)
    b.walls(x1, z1, x2, z2, 1, 3, 'cobblestone')
    for y in range(1, 4):
        for (x, z) in corners:
            b.set(x, y, z, 'stone_bricks')
    b.beam_ring(x1, z1, x2, z2, 4, 'spruce_log')
    b.fill(x1 + 1, 4, z1 + 1, x2 - 1, 4, z2 - 1, 'spruce_planks')
    b.walls(x1, z1, x2, z2, 5, 10, 'spruce_planks')
    for y in range(5, 11):
        for (x, z) in corners + [(4, z1), (x1, 4), (x2, 4)]:
            b.set(x, y, z, log('spruce_log'))
    b.beam_ring(x1, z1, x2, z2, 11, 'spruce_log')
    tops = b.roof_x(0, W - 1, 0, 8, 11, 'dark_oak')
    b.fill_gable_x(x1, z1, z2, 12, tops, 'spruce_planks')
    b.fill_gable_x(x2, z1, z2, 12, tops, 'spruce_planks')
    for (x, y, z) in [(2, 2, z1), (6, 2, z1), (x1, 2, 4), (x2, 2, 4), (x1, 7, 3), (x2, 7, 5), (2, 7, z2), (6, 7, z2),
                      (2, 7, z1), (6, 7, z1), (x1, 13, 4), (x2, 13, 4)]:
        b.set(x, y, z, 'glass_pane')
    b.door(4, 1, z2, 'north', 'left', 'spruce')
    b.torch(3, 2, 8, 'south')
    b.torch(5, 2, 8, 'south')
    # sails on the front: axle, hub, four fence arms with wool blades (pinwheel)
    b.set(4, 9, 8, log('spruce_log', 'z'))
    b.set(4, 9, 9, log('stripped_spruce_log', 'z'))
    for y in range(10, 15):
        b.set(4, y, 9, 'spruce_fence')
    for y in range(4, 9):
        b.set(4, y, 9, 'spruce_fence')
    for x in range(0, 4):
        b.set(x, 9, 9, 'spruce_fence')
    for x in range(5, 9):
        b.set(x, 9, 9, 'spruce_fence')
    for y in range(11, 15):
        b.set(5, y, 9, 'white_wool')
    for x in range(5, 9):
        b.set(x, 8, 9, 'white_wool')
    for y in range(5, 9):
        b.set(3, y, 9, 'white_wool')
    for x in range(0, 4):
        b.set(x, 10, 9, 'white_wool')
    # interior: millstones, sacks, farmer's composter
    b.set(3, 1, 3, 'grindstone[face=floor,facing=north]')
    b.set(4, 1, 3, 'stonecutter[facing=north]')
    b.set(2, 1, 2, 'hay_block[axis=y]')
    b.set(2, 1, 3, 'hay_block[axis=y]')
    b.set(2, 2, 2, 'hay_block[axis=x]')
    b.set(5, 1, 2, 'barrel[facing=up]')
    b.set(2, 1, 6, 'composter')
    b.set(5, 1, 6, 'chest[facing=west]')
    b.ladder(6, 1, 10, 2, 'west')
    b.set(4, 3, 4, 'lantern[hanging=true]')
    for (x, z) in [(2, 2), (2, 3), (3, 2)]:
        b.set(x, 5, z, 'barrel[facing=up]')
    b.set(4, 14, 4, 'lantern[hanging=true]')
    b.set(5, 6, 3, 'wall_torch[facing=west]') if False else None
    return b


# ------------------------------------------------------------------------------------------------ targ (market)
def _stall(b, x0, z_back, z_front, goods, canopy):
    """3x3 stall: goods on the back row, counter on the front row (facing the plaza centre)."""
    step = 1 if z_front > z_back else -1
    z_mid = z_back + step
    for i, x in enumerate((x0, x0 + 1, x0 + 2)):
        b.set(x, 1, z_back, goods[i])
    b.set(x0 + 1, 1, z_front, 'barrel[facing=up]')
    for (x, z) in [(x0, z_back), (x0 + 2, z_back), (x0, z_front), (x0 + 2, z_front)]:
        if (x, z) in [(x0, z_back), (x0 + 2, z_back)]:
            b.set(x, 2, z, 'spruce_fence')
        else:
            b.set(x, 1, z, 'spruce_fence')
            b.set(x, 2, z, 'spruce_fence')
    for x in range(x0, x0 + 3):
        for k, z in enumerate((z_back, z_mid, z_front)):
            b.set(x, 3, z, canopy if k != 1 else 'white_wool')
    b.set(x0 + 1, 2, z_mid, 'lantern[hanging=true]')


def targ():
    W, H, D = 13, 5, 13
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    for z in range(D):
        for x in range(W):
            b.set(x, 0, z, 'stone_bricks' if (x + z) % 2 == 0 else 'cobblestone')
    for z in range(D):
        b.set(6, 0, z, 'polished_andesite')
    for x in range(W):
        b.set(x, 0, 6, 'polished_andesite')
    # fountain in the middle
    b.set(6, 0, 6, 'water[level=0]')
    for (x, z) in [(5, 5), (6, 5), (7, 5), (5, 6), (7, 6), (5, 7), (6, 7), (7, 7)]:
        b.set(x, 1, z, 'stone_brick_slab[type=bottom]')
    b.set(6, 1, 6, 'water[level=0]')
    b.set(6, 0, 6, 'stone_bricks')
    # four stalls facing the centre
    _stall(b, 1, 1, 3, ['hay_block[axis=y]', 'melon', 'pumpkin'], f'{K}_wool')
    _stall(b, 9, 1, 3, ['white_wool', 'loom[facing=south]', 'light_gray_wool'], 'yellow_wool')
    _stall(b, 1, 11, 9, ['anvil[facing=east]', 'barrel[facing=up]', 'grindstone[face=floor,facing=north]'], 'yellow_wool')
    _stall(b, 9, 11, 9, ['barrel[facing=up]', 'water_cauldron[level=3]', 'composter'], f'{K}_wool')
    # lamp posts
    for (x, z) in [(6, 1), (1, 6), (11, 6), (6, 11)]:
        b.set(x, 1, z, 'spruce_fence')
        b.set(x, 2, z, 'spruce_fence')
        b.set(x, 3, z, 'lantern[hanging=false]')
    return b


# ------------------------------------------------------------------------------------------------ ratusz (town hall)
def ratusz():
    W, H, D = 15, 21, 13
    b = B(W, H, D)
    x1, x2, z1, z2 = 1, 13, 1, 9
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'dark_oak_planks')
    b.ring(x1, z1, x2, z2, 0, 'stone_bricks')
    b.fill(6, 0, 9, 8, 0, 11, 'stone_bricks')
    corners = b.corners(x1, z1, x2, z2)
    b.walls(x1, z1, x2, z2, 1, 4, 'stone_bricks')
    b.ring(x1, z1, x2, z2, 1, 'cobblestone')
    for y in range(1, 5):
        for (x, z) in corners:
            b.set(x, y, z, 'chiseled_stone_bricks' if y == 4 else 'stone_bricks')
    b.fill(x1, 5, z1, x2, 5, z2, 'dark_oak_planks')
    b.beam_ring(x1, z1, x2, z2, 5, 'dark_oak_log')
    b.walls(x1, z1, x2, z2, 6, 8, 'white_terracotta')
    upper_posts = corners + [(4, z1), (7, z1), (10, z1), (4, z2), (10, z2), (x1, 5), (x2, 5)]
    for y in range(6, 9):
        for (x, z) in upper_posts:
            b.set(x, y, z, log('dark_oak_log'))
    b.beam_ring(x1, z1, x2, z2, 9, 'dark_oak_log')
    tops = b.gable_roof_x(0, W - 1, 0, 10, 9, stair('deepslate_tile', 'north'), stair('deepslate_tile', 'south'),
                          'deepslate_tiles', 'deepslate_tile_slab[type=bottom]')
    b.fill_gable_x(x1, z1, 9, 10, tops, 'stone_bricks')
    b.fill_gable_x(x2, z1, 9, 10, tops, 'stone_bricks')
    b.set(x1, 11, 5, 'glass_pane')
    b.set(x2, 11, 5, 'glass_pane')
    # windows
    for x in (3, 4, 10, 11):
        for y in (2, 3):
            b.set(x, y, z2, 'glass_pane')
    for x in (3, 6, 8, 11):
        for y in (2, 3):
            b.set(x, y, z1, 'glass_pane')
    for z in (3, 4, 6, 7):
        for y in (2, 3):
            b.set(x1, y, z, 'glass_pane')
            b.set(x2, y, z, 'glass_pane')
    for x in (2, 3, 5, 6, 8, 9, 11, 12):
        b.set(x, 7, z1, 'glass_pane')
    for x in (2, 3, 11, 12):
        b.set(x, 7, z2, 'glass_pane')
    for z in (3, 7):
        b.set(x1, 7, z, 'glass_pane')
        b.set(x2, 7, z, 'glass_pane')
    # clock tower in front (3x3, protruding), door at its foot
    tx1, tx2, tz1, tz2 = 6, 8, 9, 11
    for y in range(1, 18):
        b.ring(tx1, tz1, tx2, tz2, y, 'stone_bricks')
        for (x, z) in b.corners(tx1, tz1, tx2, tz2):
            b.set(x, y, z, 'chiseled_stone_bricks' if y % 4 == 0 else 'stone_bricks')
        if y >= 1:
            b.set(7, y, 10, AIR)
    b.set(7, 1, 9, AIR)
    b.set(7, 2, 9, AIR)
    b.door(7, 1, tz2, 'north', 'left', 'dark_oak')
    b.ladder(7, 3, 15, 10, 'south')
    for y in (14, 15):
        b.set(tx1, y, 10, AIR)
        b.set(tx2, y, 10, AIR)
    b.set(7, 16, 10, 'bell[attachment=ceiling,facing=north]')
    b.fill(tx1, 17, tz1, tx2, 17, tz2, 'stone_bricks')
    b.pyramid(tx1, tz1, tx2, tz2, 18, 'deepslate_tile', 'deepslate_tiles')
    b.set(7, 20, 10, f'{K}_banner[rotation=8]')
    b.set(7, 12, 12, f'{K}_wall_banner[facing=south]')
    b.torch(6, 2, 12, 'south')
    b.torch(8, 2, 12, 'south')
    # interior: council table, chairs, bookshelves, banners
    for x in range(4, 11):
        b.set(x, 1, 5, 'dark_oak_fence')
        b.set(x, 2, 5, f'{K}_carpet')
        b.set(x, 1, 4, stair('dark_oak', 'north'))
        b.set(x, 1, 6, stair('dark_oak', 'south'))
    for x in range(2, 13):
        if x != 7:
            b.set(x, 1, 2, 'bookshelf')
            b.set(x, 2, 2, 'bookshelf')
    b.set(7, 1, 2, 'lectern[facing=south]')
    for x in (3, 5, 9, 11):
        b.set(x, 3, 2, f'{K}_wall_banner[facing=south]')
    b.set(4, 4, 5, 'lantern[hanging=true]')
    b.set(10, 4, 5, 'lantern[hanging=true]')
    b.set(7, 4, 7, 'lantern[hanging=true]')
    b.ladder(12, 1, 5, 8, 'west')
    # upper floor: offices
    b.set(2, 6, 2, 'cartography_table')
    b.set(3, 6, 2, 'chest[facing=south]')
    b.set(4, 6, 2, 'chest[facing=south]')
    b.set(10, 6, 2, 'lectern[facing=south]')
    b.set(11, 6, 2, 'bookshelf')
    b.set(12, 6, 2, 'bookshelf')
    b.set(6, 6, 4, f'{K}_carpet')
    b.set(7, 6, 4, f'{K}_carpet')
    b.set(8, 6, 4, f'{K}_carpet')
    b.set(4, 6, 6, 'barrel[facing=up]')
    b.set(4, 7, 6, 'lantern[hanging=false]')
    b.set(7, 13, 5, 'lantern[hanging=true]')
    return b


# ------------------------------------------------------------------------------------------------ kosciol (church)
def kosciol():
    W, H, D = 9, 23, 20
    b = B(W, H, D)
    x1, x2, z1, z2 = 1, 7, 1, 13
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'polished_andesite')
    b.ring(x1, z1, x2, z2, 0, 'stone_bricks')
    tx1, tx2, tz1, tz2 = 2, 6, 13, 17
    b.fill(tx1, 0, tz1, tx2, 0, tz2, 'stone_bricks')
    b.walls(x1, z1, x2, z2, 1, 8, 'stone_bricks')
    for (x, y, z) in [(1, 2, 4), (7, 3, 8), (3, 5, 1), (1, 6, 11), (7, 1, 2), (5, 7, 1)]:
        b.set(x, y, z, 'mossy_stone_bricks')
    for y in range(1, 9):
        for (x, z) in b.corners(x1, z1, x2, z2):
            b.set(x, y, z, 'chiseled_stone_bricks' if y in (4, 8) else 'stone_bricks')
    # buttresses along the nave
    for z in (4, 7, 10):
        for x in (0, 8):
            for y in range(1, 5):
                b.set(x, y, z, 'stone_bricks')
            b.set(x, 5, z, stair('stone_brick', 'east' if x == 0 else 'west'))
    tops = b.gable_roof_z(0, 13, 0, 8, 8, stair('deepslate_tile', 'west'), stair('deepslate_tile', 'east'),
                          'deepslate_tiles', 'deepslate_tile_slab[type=bottom]')
    b.fill_gable_z(z1, x1, x2, 9, tops, 'stone_bricks')
    for x in (0, 8):
        for z in (4, 7, 10):
            pass
    # stained glass windows
    for z in (3, 6, 9, 11):
        for y, glass in ((2, 'light_blue'), (3, 'yellow'), (4, 'yellow'), (5, 'light_blue')):
            b.set(x1, y, z, f'{glass}_stained_glass_pane')
            b.set(x2, y, z, f'{glass}_stained_glass_pane')
    for y, glass in ((5, 'red'), (6, 'yellow'), (9, 'red'), (10, 'yellow')):
        b.set(4, y, z1, f'{glass}_stained_glass_pane')
    b.set(3, 6, z1, 'red_stained_glass_pane')
    b.set(5, 6, z1, 'red_stained_glass_pane')
    # bell tower in front with spire
    for y in range(1, 17):
        b.ring(tx1, tz1, tx2, tz2, y, 'stone_bricks')
        for (x, z) in b.corners(tx1, tz1, tx2, tz2):
            b.set(x, y, z, 'chiseled_stone_bricks' if y % 5 == 0 else 'stone_bricks')
    for y in range(1, 16):
        b.fill(tx1 + 1, y, tz1 + 1, tx2 - 1, y, tz2 - 1, AIR)
    b.set(4, 1, z2, AIR)
    b.set(4, 2, z2, AIR)
    b.set(4, 3, z2, AIR)
    b.door(4, 1, tz2, 'north', 'left', 'dark_oak')
    for y in (13, 14):
        b.set(4, y, tz2, AIR)
        b.set(tx1, y, 15, AIR)
        b.set(tx2, y, 15, AIR)
    b.fill(tx1, 16, tz1, tx2, 16, tz2, 'stone_bricks')
    b.set(4, 15, 15, 'bell[attachment=ceiling,facing=south]')
    b.ladder(3, 1, 15, 16, 'east') if False else None
    b.ladder(5, 1, 14, 14, 'west')
    y = b.pyramid(tx1, tz1, tx2, tz2, 17, 'deepslate_tile', 'deepslate_tiles')
    b.set(4, 20, 15, 'gold_block')
    b.set(4, 21, 15, 'lightning_rod[facing=up]')
    b.set(4, 9, 18, f'{K}_wall_banner[facing=south]')
    b.torch(3, 2, 18, 'south')
    b.torch(5, 2, 18, 'south')
    # interior: aisle, pews, altar
    for z in range(2, 13):
        b.set(4, 1, z, 'red_carpet')
    for z in (5, 7, 9, 11):
        for x in (2, 3, 5, 6):
            b.set(x, 1, z, stair('spruce', 'south'))
    b.set(4, 1, 2, 'quartz_block')
    b.set(3, 1, 2, 'quartz_stairs[facing=east,half=bottom]')
    b.set(5, 1, 2, 'quartz_stairs[facing=west,half=bottom]')
    b.set(4, 2, 2, 'candle[candles=3,lit=true]')
    b.set(4, 1, 4, 'lectern[facing=south]')
    b.set(2, 1, 2, 'brewing_stand')
    b.set(6, 1, 2, 'flower_pot')
    for z in (4, 8):
        b.set(2, 5, z, f'{K}_wall_banner[facing=east]')
        b.set(6, 5, z, f'{K}_wall_banner[facing=west]')
    for z in (4, 8, 12):
        b.set(4, 11, z, 'iron_chain[axis=y]')
        b.set(4, 10, z, 'lantern[hanging=true]')
    return b


# ------------------------------------------------------------------------------------------------ stajnia (stable)
def stajnia():
    W, H, D = 13, 10, 9
    b = B(W, H, D)
    x1, x2, z1, z2 = 1, 11, 1, 7
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'coarse_dirt')
    b.ring(x1, z1, x2, z2, 0, 'cobblestone')
    for x in range(2, 11):
        b.set(x, 0, 2, 'spruce_planks')
    b.walls(x1, z1, x2, z2, 1, 3, 'spruce_planks')
    b.ring(x1, z1, x2, z2, 1, 'cobblestone')
    front_posts = [(x, z2) for x in (1, 3, 5, 7, 9, 11)]
    for y in range(1, 4):
        for (x, z) in b.corners(x1, z1, x2, z2) + front_posts + [(x, z1) for x in (3, 5, 7, 9)] + [(x1, 4), (x2, 4)]:
            b.set(x, y, z, log('spruce_log'))
    b.beam_ring(x1, z1, x2, z2, 4, 'spruce_log')
    tops = b.roof_x(0, W - 1, 0, D - 1, 4, 'spruce')
    b.fill_gable_x(x1, z1, z2, 5, tops, 'spruce_planks')
    b.fill_gable_x(x2, z1, z2, 5, tops, 'spruce_planks')
    # stall gates on the front, half-open above
    for x in (2, 4, 8, 10):
        b.set(x, 1, z2, 'spruce_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
        b.set(x, 2, z2, AIR)
    b.door(6, 1, z2, 'north', 'left', 'spruce')
    b.torch(5, 3, 8, 'south')
    b.torch(7, 3, 8, 'south')
    # stall dividers, hay, troughs
    for x in (3, 5, 7, 9):
        for z in (4, 5, 6):
            b.set(x, 1, z, 'spruce_fence')
    for x in (2, 4, 8, 10):
        b.set(x, 1, 2, 'hay_block[axis=y]')
    b.set(4, 2, 2, 'hay_block[axis=x]')
    b.set(6, 1, 2, 'water_cauldron[level=3]')
    b.set(2, 1, 3, 'water_cauldron[level=3]')
    b.set(10, 1, 3, 'composter')
    b.set(6, 1, 3, 'chest[facing=south]')
    for z in (3, 5):
        b.set(x1, 2, z, 'spruce_fence')
        b.set(x2, 2, z, 'spruce_fence')
    b.set(3, 7, 4, 'lantern[hanging=true]')
    b.set(9, 7, 4, 'lantern[hanging=true]')
    return b


# ------------------------------------------------------------------------------------------------ kamienica (townhouse, 6 beds)
def kamienica():
    W, H, D = 11, 19, 9
    b = B(W, H, D)
    x1, x2, z1, z2 = 1, 9, 1, 7
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'oak_planks')
    b.ring(x1, z1, x2, z2, 0, 'stone_bricks')
    b.fill(x1, 0, 8, x2, 0, 8, 'stone_bricks')
    corners = b.corners(x1, z1, x2, z2)
    b.walls(x1, z1, x2, z2, 1, 3, 'stone_bricks')
    b.ring(x1, z1, x2, z2, 1, 'cobblestone')
    # storeys
    for floor_y, top in ((4, 8), (8, 12)):
        b.fill(x1, floor_y, z1, x2, floor_y, z2, 'spruce_planks')
        b.beam_ring(x1, z1, x2, z2, floor_y, 'dark_oak_log')
    for y in range(5, 8):
        b.ring(x1, z1, x2, z2, y, 'white_terracotta')
    for y in range(9, 12):
        b.ring(x1, z1, x2, z2, y, 'white_terracotta')
    posts = corners + [(4, z1), (6, z1), (4, z2), (6, z2), (x1, 4), (x2, 4)]
    for y in list(range(5, 8)) + list(range(9, 12)):
        for (x, z) in posts:
            b.set(x, y, z, log('dark_oak_log'))
    b.beam_ring(x1, z1, x2, z2, 12, 'dark_oak_log')
    # roof with the gable to the street (ridge along z)
    tops = b.roof_z(0, 8, 0, 10, 12, 'dark_oak', 'dark_oak_planks', 'dark_oak_slab[type=bottom]')
    b.fill_gable_z(z1, x1, x2, 13, tops, 'white_terracotta')
    b.fill_gable_z(z2, x1, x2, 13, tops, 'white_terracotta')
    for x in (4, 6):
        for z in (z1, z2):
            for y in (13, 14):
                b.set(x, y, z, log('dark_oak_log'))
    b.set(5, 14, z2, 'glass_pane')
    b.set(5, 15, z2, 'glass_pane')
    b.set(5, 14, z1, 'glass_pane')
    # shop windows and door
    for x in (2, 3, 7, 8):
        b.set(x, 2, z2, 'glass_pane')
    b.door(5, 1, z2, 'north', 'left', 'spruce')
    b.set(5, 3, z2, 'stone_bricks')
    b.torch(4, 2, 8, 'south')
    b.torch(6, 2, 8, 'south')
    b.set(4, 3, 8, f'{K}_wall_banner[facing=south]') if False else None
    for (x, y, z) in [(2, 6, z2), (3, 6, z2), (5, 6, z2), (7, 6, z2), (8, 6, z2), (2, 10, z2), (3, 10, z2), (5, 10, z2),
                      (7, 10, z2), (8, 10, z2), (3, 6, z1), (7, 6, z1), (3, 10, z1), (7, 10, z1), (x1, 6, 3), (x2, 6, 5),
                      (x1, 10, 5), (x2, 10, 3), (3, 2, z1), (7, 2, z1)]:
        b.set(x, y, z, 'glass_pane')
    # flower boxes under the upper front windows
    for x in (2, 3, 7, 8):
        b.set(x, 5, 8, 'spruce_trapdoor[facing=south,half=top,open=false]') if False else None
    # ground floor shop
    for x in (3, 4, 6, 7):
        b.set(x, 1, 4, 'spruce_slab[type=top]')
    b.set(2, 1, 2, 'barrel[facing=up]')
    b.set(3, 1, 2, 'barrel[facing=up]')
    b.set(4, 1, 2, 'crafting_table')
    b.set(6, 1, 2, 'chest[facing=south]')
    b.set(5, 3, 4, 'lantern[hanging=true]')
    b.ladder(8, 1, 8, 2, 'west')
    # bedrooms (3 beds per floor)
    for fy in (5, 9):
        for x in (2, 3, 4):
            b.bed(x, fy, 2, 'north', 'blue' if fy == 5 else 'yellow')
        b.set(6, fy, 2, 'chest[facing=south]')
        b.set(6, fy + 1, 2, 'lantern[hanging=false]')
        b.set(6, fy, 5, 'brown_carpet')
    b.set(5, 7, 5, 'lantern[hanging=true]')
    b.set(5, 16, 4, 'lantern[hanging=true]')
    return b


# ------------------------------------------------------------------------------------------------ mur z baszta (wall module)
def mur():
    W, H, D = 13, 12, 6
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    # curtain wall 2 thick (z1 outer, z2 inner)
    b.fill(0, 0, 1, W - 1, 0, 2, 'stone_bricks')
    for y in range(1, 6):
        b.fill(0, y, 1, W - 1, y, 2, 'stone_bricks')
    for (x, y, z) in [(1, 2, 1), (3, 4, 1), (10, 1, 1), (11, 3, 1), (2, 5, 2)]:
        b.set(x, y, z, 'mossy_stone_bricks')
    for (x, y, z) in [(2, 1, 1), (9, 3, 1), (12, 2, 1)]:
        b.set(x, y, z, 'cracked_stone_bricks')
    for x in range(W):
        if x % 2 == 0:
            b.set(x, 6, 1, 'stone_bricks')
    # baszta 5x5
    bx1, bx2, bz1, bz2 = 4, 8, 0, 4
    b.fill(bx1, 0, bz1, bx2, 0, bz2, 'stone_bricks')
    for y in range(1, 10):
        b.ring(bx1, bz1, bx2, bz2, y, 'stone_bricks')
        b.fill(bx1 + 1, y, bz1 + 1, bx2 - 1, y, bz2 - 1, AIR)
    b.fill(bx1 + 1, 9, bz1 + 1, bx2 - 1, 9, bz2 - 1, 'spruce_planks')
    b.crenels(bx1, bz1, bx2, bz2, 10, 'stone_bricks')
    b.door(6, 1, bz2, 'north', 'left', 'spruce')
    b.ladder(6, 1, 9, 1, 'south')
    for y in (6, 7):
        b.set(bx1, y, 2, AIR)
        b.set(bx2, y, 2, AIR)
    b.set(6, 3, bz1, AIR)
    b.set(6, 6, bz1, AIR)
    b.torch(5, 3, 2, 'east')
    b.torch(7, 7, 3, 'west')
    b.torch(2, 4, 3, 'south')
    b.torch(10, 4, 3, 'south')
    b.torch(5, 2, 5, 'south')
    b.set(4, 11, 0, 'lantern[hanging=false]')
    b.set(8, 11, 0, 'lantern[hanging=false]')
    return b


# ------------------------------------------------------------------------------------------------ brama (town gate)
def brama():
    W, H, D = 13, 12, 7
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    for (tx1, tx2) in ((0, 4), (8, 12)):
        b.fill(tx1, 0, 1, tx2, 0, 5, 'stone_bricks')
        for y in range(1, 10):
            b.ring(tx1, 1, tx2, 5, y, 'stone_bricks')
            b.fill(tx1 + 1, y, 2, tx2 - 1, y, 4, AIR)
        b.fill(tx1 + 1, 9, 2, tx2 - 1, 9, 4, 'spruce_planks')
        b.crenels(tx1, 1, tx2, 5, 10, 'stone_bricks')
        cx = (tx1 + tx2) // 2
        b.door(cx, 1, 5, 'north', 'left', 'spruce')
        b.ladder(cx, 1, 9, 2, 'south')
        b.set(cx, 4, 1, AIR)
        b.set(cx, 7, 1, AIR)
        b.set(tx1, 11, 1, 'lantern[hanging=false]')
        b.set(tx2, 11, 1, 'lantern[hanging=false]')
        b.torch(tx1 + 1 if cx < 6 else tx2 - 1, 3, 3, 'east' if cx < 6 else 'west')
    # passage with portcullis and gatehouse above
    b.fill(5, 0, 0, 7, 0, 6, 'cobblestone')
    for y in range(5, 9):
        b.fill(5, y, 1, 7, y, 5, 'stone_bricks')
    b.fill(5, 6, 2, 7, 7, 4, AIR)
    b.set(6, 7, 1, AIR)
    for x in range(5, 8):
        b.set(x, 4, 1, 'iron_bars')
    b.crenels(5, 1, 7, 5, 9, 'stone_bricks')
    for x in (4, 8):
        for y in (6, 7):
            b.set(x, y, 3, AIR)
    b.set(6, 6, 0, f'{K}_wall_banner[facing=north]')
    b.set(6, 6, 6, f'{K}_wall_banner[facing=south]')
    b.torch(5, 3, 3, 'east')
    b.torch(7, 3, 3, 'west')
    return b


# ------------------------------------------------------------------------------------------------ pole (farm field)
def pole():
    W, H, D = 9, 3, 9
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    for z in range(D):
        for x in range(W):
            border = x in (0, W - 1) or z in (0, D - 1)
            b.set(x, 0, z, 'coarse_dirt' if border else 'farmland[moisture=7]')
    for z in range(1, D - 1):
        b.set(4, 0, z, 'water[level=0]')
    crops = {1: 'wheat[age=7]', 2: 'wheat[age=7]', 3: 'wheat[age=7]', 5: 'carrots[age=7]', 6: 'potatoes[age=7]',
             7: 'beetroots[age=3]'}
    for z in range(1, D - 1):
        for x, crop in crops.items():
            b.set(x, 1, z, crop)
    b.ring(0, 0, W - 1, D - 1, 1, 'spruce_fence')
    b.set(4, 1, D - 1, 'spruce_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
    for (x, z) in [(0, 0), (W - 1, 0), (0, D - 1), (W - 1, D - 1)]:
        b.set(x, 2, z, 'lantern[hanging=false]')
    b.set(4, 1, 0, 'composter')
    return b
