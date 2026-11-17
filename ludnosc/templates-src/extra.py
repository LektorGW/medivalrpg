# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Buildings of rich towns: laznia, latarnia, rynek, ogrody, koszary2, akademia, skarbiec, katedra, arena, most,
kamieniolom. Front (door) = south, x = west->east, z = north->south."""
from lib import B
from economy import cottage

AIR = '.'
K = '{kolor}'
FLOWERS = ['poppy', 'cornflower', 'allium', 'azure_bluet', 'oxeye_daisy', 'red_tulip', 'white_tulip', 'lily_of_the_valley']


def stair(mat, facing, half='bottom'):
    return f'{mat}_stairs[facing={facing},half={half}]'


def lamp_post(b, x, y, z, h=3, post='spruce_fence'):
    for k in range(h):
        b.set(x, y + k, z, post)
    b.set(x, y + h, z, 'lantern[hanging=false]')


# ------------------------------------------------------------------------------------------------ laznia (bathhouse)
def laznia():
    b, tops = cottage(11, 9, 1, 1, 9, 7)
    b.door(5, 1, 7, 'north', 'left', 'spruce')
    b.torch(4, 2, 8, 'south')
    b.torch(6, 2, 8, 'south')
    for (x, y, z) in [(1, 2, 3), (1, 2, 5), (9, 2, 3), (9, 2, 5), (3, 2, 1), (7, 2, 1)]:
        b.set(x, y, z, '@window')
    # pool in the floor, smooth stone edge
    b.fill(3, 0, 2, 7, 0, 5, 'smooth_stone')
    b.fill(4, 0, 3, 6, 0, 4, 'water[level=0]')
    for x in (3, 7):
        b.set(x, 1, 2, 'water_cauldron[level=3]')
    # benches along the side walls
    for z in (3, 4, 5):
        b.set(2, 1, z, stair('spruce', 'west'))
        b.set(8, 1, z, stair('spruce', 'east'))
    # stove with chimney (steam)
    b.set(5, 1, 2, 'smoker[facing=south]')
    b.set(4, 1, 2, 'barrel[facing=up]')
    b.set(6, 1, 2, 'barrel[facing=up]')
    for y in range(2, tops[1] + 3):
        b.set(5, y, 1, 'bricks')
    b.set(5, tops[1] + 3, 1, 'campfire[lit=true,signal_fire=false,facing=south]')
    b.set(3, 1, 6, 'white_carpet')
    b.set(7, 1, 6, 'white_carpet')
    b.set(5, tops[4] - 1, 4, 'lantern[hanging=true]')
    b.set(3, 3, 5, 'lantern[hanging=true]') if False else None
    return b


# ------------------------------------------------------------------------------------------------ latarnia (lighthouse)
def latarnia():
    W, H, D = 7, 21, 7
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(0, 0, 0, 6, 0, 6, 'stone_bricks')
    for y in range(1, 14):
        band = 'red_terracotta' if (y // 3) % 2 == 1 else 'calcite'
        b.ring(1, 1, 5, 5, y, band)
        for (x, z) in b.corners(1, 1, 5, 5):
            b.set(x, y, z, 'stone_bricks')
    b.door(3, 1, 5, 'north', 'left', 'spruce')
    b.torch(2, 2, 6, 'south')
    b.torch(4, 2, 6, 'south')
    b.ladder(3, 1, 14, 2, 'south')
    for y in (4, 8, 12):
        b.set(1, y, 3, 'glass_pane')
        b.set(5, y, 3, 'glass_pane')
    for y in (3, 7, 11):
        b.set(2, y, 4, 'wall_torch[facing=east]') if False else None
        b.set(4, y, 3, 'lantern[hanging=false]') if False else None
    b.set(2, 5, 4, 'wall_torch[facing=north]')
    b.set(4, 10, 4, 'wall_torch[facing=north]')
    # gallery: platform with railing
    b.fill(0, 14, 0, 6, 14, 6, 'stone_bricks')
    b.set(3, 14, 2, 'ladder[facing=south]')
    for x in range(7):
        b.set(x, 15, 0, 'spruce_fence')
        b.set(x, 15, 6, 'spruce_fence')
    for z in range(1, 6):
        b.set(0, 15, z, 'spruce_fence')
        b.set(6, 15, z, 'spruce_fence')
    # lantern room
    for y in (15, 16):
        b.ring(1, 1, 5, 5, y, 'glass_pane')
        for (x, z) in b.corners(1, 1, 5, 5):
            b.set(x, y, z, 'stone_brick_wall')
    b.set(3, 15, 3, 'sea_lantern')
    b.set(3, 16, 3, 'sea_lantern')
    b.pyramid(1, 1, 5, 5, 17, 'deepslate_tile', 'deepslate_tiles')
    b.set(3, 20, 3, 'lightning_rod[facing=up,powered=false]')
    return b


# ------------------------------------------------------------------------------------------------ rynek (main square)
def rynek():
    W, H, D = 17, 8, 17
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    for z in range(D):
        for x in range(W):
            b.set(x, 0, z, 'polished_andesite' if (x + z) % 4 == 0 else 'stone_bricks' if (x * 7 + z * 3) % 5 else 'cobblestone')
    # fountain: basin with water, central column with a spout bowl
    b.ring(5, 5, 11, 11, 0, 'smooth_stone')
    b.fill(6, 0, 6, 10, 0, 10, 'water[level=0]')
    b.ring(5, 5, 11, 11, 1, 'stone_brick_slab[type=bottom]')
    for y in (0, 1, 2):
        b.set(8, y, 8, 'chiseled_stone_bricks')
    b.set(8, 3, 8, 'water_cauldron[level=3]')
    for (x, z) in [(5, 5), (11, 5), (5, 11), (11, 11)]:
        b.set(x, 1, z, 'stone_bricks')
        b.set(x, 2, z, 'lantern[hanging=false]')
    # benches facing the fountain
    for x in (7, 8, 9):
        b.set(x, 1, 3, stair('spruce', 'north'))
        b.set(x, 1, 13, stair('spruce', 'south'))
    for z in (7, 8, 9):
        b.set(3, 1, z, stair('spruce', 'west'))
        b.set(13, 1, z, stair('spruce', 'east'))
    # lamp posts and trees in the corners
    for (x, z) in [(1, 1), (15, 1), (1, 15), (15, 15)]:
        lamp_post(b, x, 1, z, 3)
    for (x, z) in [(3, 3), (13, 3), (3, 13), (13, 13)]:
        b.set(x, 0, z, 'grass_block[snowy=false]')
        for y in (1, 2, 3):
            b.set(x, y, z, 'oak_log[axis=y]')
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                b.set(x + dx, 4, z + dz, 'oak_leaves[persistent=true]')
        b.set(x, 5, z, 'oak_leaves[persistent=true]')
    # stalls in the country's colours
    for (x0, z0) in [(1, 6), (14, 6)]:
        for z in range(z0, z0 + 5):
            b.set(x0, 1, z, 'barrel[facing=up]' if z % 2 else 'spruce_planks')
            b.set(x0 + 1, 1, z, 'spruce_slab[type=top]') if False else None
        b.set(x0, 2, z0, 'spruce_fence')
        b.set(x0, 2, z0 + 4, 'spruce_fence')
        for z in range(z0, z0 + 5):
            b.set(x0, 3, z, f'{K}_wool')
    return b


# ------------------------------------------------------------------------------------------------ ogrody (palace gardens)
def ogrody():
    W, H, D = 19, 9, 17
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(0, 0, 0, W - 1, 0, D - 1, 'grass_block[snowy=false]')
    # gravel paths: cross and ring
    for x in range(W):
        b.set(x, 0, 8, 'gravel')
    for z in range(D):
        b.set(9, 0, z, 'gravel')
    b.ring(2, 2, 16, 14, 0, 'gravel')
    # clipped hedge around, entrances in the middle of each side
    for y in (1, 2):
        b.ring(0, 0, W - 1, D - 1, y, 'oak_leaves[persistent=true]')
    for y in (1, 2):
        b.set(9, y, 0, AIR)
        b.set(9, y, D - 1, AIR)
        b.set(0, y, 8, AIR)
        b.set(W - 1, y, 8, AIR)
    # flower beds in the four quarters
    i = 0
    for (x1, z1, x2, z2) in [(3, 3, 7, 6), (11, 3, 15, 6), (3, 10, 7, 13), (11, 10, 15, 13)]:
        for z in range(z1, z2 + 1):
            for x in range(x1, x2 + 1):
                edge = x in (x1, x2) or z in (z1, z2)
                b.set(x, 1, z, 'azalea_leaves[persistent=true]' if edge and (x + z) % 2 == 0
                      else FLOWERS[(x + 2 * z + i) % len(FLOWERS)])
        i += 3
    # pond with lily pads in the north-west quarter replaced by water in the middle of the bed
    b.fill(4, 0, 4, 6, 0, 5, 'water[level=0]')
    for (x, z) in [(4, 4), (6, 5)]:
        b.set(x, 1, z, 'lily_pad')
    b.set(5, 1, 4, AIR)
    b.set(5, 1, 5, AIR)
    b.set(4, 1, 5, AIR)
    b.set(6, 1, 4, AIR)
    # gazebo in the centre
    b.fill(7, 0, 6, 11, 0, 10, 'smooth_quartz')
    for (x, z) in [(7, 6), (11, 6), (7, 10), (11, 10)]:
        for y in (1, 2, 3):
            b.set(x, y, z, 'quartz_pillar[axis=y]')
    b.fill(7, 4, 6, 11, 4, 10, 'smooth_quartz_slab[type=bottom]')
    b.pyramid(8, 7, 10, 9, 5, 'deepslate_tile', 'deepslate_tiles')
    b.set(9, 3, 8, 'lantern[hanging=true]')
    b.set(9, 1, 8, 'smooth_quartz_slab[type=bottom]') if False else None
    for x in (8, 10):
        b.set(x, 1, 7, stair('quartz', 'north'))
        b.set(x, 1, 9, stair('quartz', 'south'))
    # lamp posts at the entrances
    for (x, z) in [(8, 1), (10, 1), (8, 15), (10, 15), (1, 7), (1, 9), (17, 7), (17, 9)]:
        lamp_post(b, x, 1, z, 2, 'polished_blackstone_wall')
    # statues: column with gold top
    for (x, z) in [(13, 4), (5, 12), (13, 12)]:
        b.set(x, 1, z, 'chiseled_quartz_block')
        b.set(x, 2, z, 'quartz_pillar[axis=y]')
        b.set(x, 3, z, 'gold_block')
    return b


# ------------------------------------------------------------------------------------------------ koszary2 (large barracks)
def koszary2():
    W, H, D = 15, 12, 11
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    x1, z1, x2, z2 = 1, 1, 13, 9
    b.fill(x1, 0, z1, x2, 0, z2, 'stone_bricks')
    b.walls(x1, z1, x2, z2, 1, 8, 'stone_bricks')
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, 10):
            b.set(x, y, z, 'polished_andesite')
    b.fill(x1 + 1, 4, z1 + 1, x2 - 1, 4, z2 - 1, 'spruce_planks')
    b.fill(x1, 9, z1, x2, 9, z2, 'stone_bricks')
    b.crenels(x1, z1, x2, z2, 10, 'stone_bricks')
    b.door(7, 1, z2, 'north', 'left', 'spruce')
    b.torch(6, 2, z2 + 1, 'south')
    b.torch(8, 2, z2 + 1, 'south')
    b.set(7, 3, z2 + 1, f'{K}_wall_banner[facing=south]')
    for x in (3, 5, 9, 11):
        for y in (2, 6):
            b.set(x, y, z1, 'iron_bars')
            b.set(x, y, z2, 'iron_bars')
    for z in (3, 5, 7):
        for y in (2, 6):
            b.set(x1, y, z, 'iron_bars')
            b.set(x2, y, z, 'iron_bars')
    # ground floor: 6 bunks along the back wall, armoury
    for x in (2, 4, 6, 8, 10, 12):
        b.bed(x, 1, 2, 'north', 'red' if x % 4 else 'brown')
    for x in (2, 12):
        b.set(x, 1, 5, 'smithing_table') if x == 2 else b.set(x, 1, 5, 'grindstone[face=floor,facing=west]')
    b.set(2, 1, 6, 'anvil[facing=east]')
    b.set(12, 1, 6, 'barrel[facing=up]')
    b.set(12, 1, 7, 'barrel[facing=up]')
    b.set(2, 1, 7, 'chest[facing=east]')
    b.set(7, 3, 5, 'lantern[hanging=true]')
    b.set(4, 3, 7, 'lantern[hanging=true]')
    b.set(10, 3, 7, 'lantern[hanging=true]')
    # ladder to the upper floor and the roof
    b.ladder(12, 1, 9, 8, 'north')
    b.set(12, 4, 8, 'ladder[facing=north]')
    b.set(12, 9, 8, 'ladder[facing=north]')
    # upper floor: 6 more bunks, table
    for x in (2, 4, 6, 8, 10, 12):
        b.bed(x, 5, 2, 'north', 'blue' if x % 4 else 'brown')
    b.set(6, 5, 6, 'crafting_table')
    b.set(7, 5, 6, 'spruce_slab[type=top]') if False else None
    b.set(3, 5, 7, 'chest[facing=east]')
    b.set(7, 8, 5, 'lantern[hanging=true]')
    b.set(3, 8, 7, 'lantern[hanging=true]')
    for (x, z) in b.corners(x1, z1, x2, z2):
        b.set(x, 11, z, 'lantern[hanging=false]')
    return b


# ------------------------------------------------------------------------------------------------ akademia (knights' academy)
def akademia():
    W, H, D = 17, 11, 15
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    # hall at the back (north)
    x1, z1, x2, z2 = 3, 0, 13, 6
    b.fill(x1, 0, z1, x2, 0, z2, 'spruce_planks')
    b.walls(x1, z1, x2, z2, 1, 5, 'stone_bricks')
    for (x, z) in b.corners(x1, z1, x2, z2) + [(8, z1), (8, z2)]:
        for y in range(1, 6):
            b.set(x, y, z, 'dark_oak_log[axis=y]')
    tops = b.gable_roof_x(x1 - 1, x2 + 1, z1, z2, 6, stair('dark_oak', 'north'), stair('dark_oak', 'south'),
                          'dark_oak_planks', 'dark_oak_slab[type=bottom]')
    for z in range(z1, z2 + 1):
        for y in range(6, tops[z]):
            b.set(x1, y, z, 'stone_bricks')
            b.set(x2, y, z, 'stone_bricks')
    b.door(8, 1, z2, 'north', 'left', 'dark_oak')
    b.torch(7, 2, z2 + 1, 'south')
    b.torch(9, 2, z2 + 1, 'south')
    for x in (5, 11):
        b.set(x, 2, z2, 'glass_pane')
        b.set(x, 3, z2, 'glass_pane')
    for z in (2, 4):
        b.set(x1, 3, z, 'glass_pane')
        b.set(x2, 3, z, 'glass_pane')
    for x in range(4, 13):
        b.set(x, 1, 1, 'bookshelf')
    for x in (5, 7, 9, 11):
        b.set(x, 1, 3, 'lectern[facing=north,has_book=false,powered=false]')
    b.set(12, 1, 5, 'chest[facing=west]')
    b.set(4, 1, 5, 'barrel[facing=up]')
    b.set(5, 1, 1, f'{K}_banner[rotation=0]') if False else None
    b.set(8, tops[3] - 1, 3, 'lantern[hanging=true]')
    b.set(5, tops[4] - 1, 4, 'lantern[hanging=true]')
    b.set(11, tops[4] - 1, 4, 'lantern[hanging=true]')
    # training yard in front: sand, fence, targets, dummies, jousting lane
    for z in range(7, D):
        for x in range(W):
            b.set(x, 0, z, 'coarse_dirt' if (x + z) % 3 else 'sand')
    for x in range(W):
        b.set(x, 1, D - 1, 'spruce_fence')
    for z in range(7, D):
        b.set(0, 1, z, 'spruce_fence')
        b.set(W - 1, 1, z, 'spruce_fence')
    b.set(8, 1, D - 1, 'spruce_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
    for x in (2, 4):
        b.set(x, 1, 8, 'hay_block[axis=y]')
        b.set(x, 2, 8, 'target[power=0]')
    for x in (12, 14):
        b.set(x, 1, 9, 'spruce_fence')
        b.set(x, 2, 9, 'hay_block[axis=y]')
        b.set(x, 3, 9, 'carved_pumpkin[facing=south]')
    for x in range(3, 14):
        if x not in (7, 8, 9):
            b.set(x, 1, 11, 'spruce_fence')  # jousting barrier, open in the middle
    for (x, z) in [(1, 8), (15, 8), (1, 13), (15, 13)]:
        lamp_post(b, x, 1, z, 2)
    b.set(6, 1, 9, 'grindstone[face=floor,facing=north]')
    b.set(10, 1, 9, 'barrel[facing=up]')
    return b


# ------------------------------------------------------------------------------------------------ skarbiec (royal treasury)
def skarbiec():
    W, H, D = 9, 11, 9
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(0, 0, 0, 8, 0, 8, 'polished_blackstone_bricks')
    b.walls(0, 0, 8, 8, 1, 7, 'deepslate_bricks')
    for (x, z) in b.corners(0, 0, 8, 8):
        for y in range(1, 9):
            b.set(x, y, z, 'polished_blackstone_bricks')
    b.fill(1, 1, 1, 7, 7, 7, AIR)
    b.fill(0, 8, 0, 8, 8, 8, 'deepslate_bricks')
    b.crenels(0, 0, 8, 8, 9, 'polished_blackstone_bricks')
    b.door(4, 1, 8, 'north', 'left', 'dark_oak')
    b.set(4, 3, 8, 'chiseled_polished_blackstone')
    b.torch(3, 2, 9 - 1, 'south') if False else None
    for x in (2, 6):
        for y in (3, 4):
            b.set(x, y, 0, 'iron_bars')
            b.set(x, y, 8, 'iron_bars')
    for z in (2, 6):
        for y in (3, 4):
            b.set(0, y, z, 'iron_bars')
            b.set(8, y, z, 'iron_bars')
    # inner vault behind bars
    for x in range(1, 8):
        b.set(x, 1, 4, 'iron_bars')
        b.set(x, 2, 4, 'iron_bars')
    b.set(4, 1, 4, AIR)
    b.set(4, 2, 4, AIR)
    for x in (1, 2, 6, 7):
        b.set(x, 1, 1, 'gold_block')
    b.set(3, 1, 1, 'chest[facing=south]')
    b.set(5, 1, 1, 'chest[facing=south]')
    b.set(4, 1, 1, 'barrel[facing=up]')
    b.set(1, 1, 2, 'raw_gold_block')
    b.set(7, 1, 2, 'raw_iron_block')
    b.set(4, 7, 2, 'lantern[hanging=true]')
    # clerks' room in front
    b.set(1, 1, 6, 'lectern[facing=east,has_book=false,powered=false]')
    b.set(7, 1, 6, 'cartography_table')
    b.set(7, 1, 7, 'barrel[facing=up]')
    b.set(1, 1, 7, 'chest[facing=east]')
    b.set(4, 7, 6, 'lantern[hanging=true]')
    for x in (3, 5):
        b.set(x, 3, 7, f'{K}_wall_banner[facing=north]')
    b.set(2, 2, 9 - 1, 'iron_bars') if False else None
    for (x, z) in b.corners(0, 0, 8, 8):
        b.set(x, 10, z, 'lantern[hanging=false]')
    return b


# ------------------------------------------------------------------------------------------------ katedra (cathedral)
def katedra():
    W, H, D = 15, 28, 28
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    nx1, nx2, nz1, nz2 = 3, 11, 2, 24
    # floor
    for z in range(nz1, nz2 + 1):
        for x in range(nx1, nx2 + 1):
            b.set(x, 0, z, 'polished_andesite' if (x + z) % 2 else 'smooth_stone')
    # walls
    b.walls(nx1, nz1, nx2, nz2, 1, 11, 'stone_bricks')
    b.ring(nx1, nz1, nx2, nz2, 1, 'chiseled_stone_bricks')
    # buttresses
    for z in range(nz1 + 2, nz2 - 3, 4):
        for (x, f) in ((nx1 - 1, 'east'), (nx2 + 1, 'west')):
            for y in range(1, 8):
                b.set(x, y, z, 'stone_bricks')
            b.set(x, 8, z, stair('stone_brick', f))
            b.set(x, 0, z, 'stone_bricks')
    # tall stained-glass windows
    colours = ['purple', 'blue', 'red', 'yellow', 'light_blue']
    for i, z in enumerate(range(nz1 + 4, nz2 - 3, 4)):
        for y in range(3, 9):
            b.set(nx1, y, z, f'{colours[i % 5]}_stained_glass_pane')
            b.set(nx2, y, z, f'{colours[(i + 2) % 5]}_stained_glass_pane')
    # roof (ridge along z)
    tops = b.gable_roof_z(nz1 - 1, nz2, nx1 - 1, nx2 + 1, 12, stair('deepslate_tile', 'west'), stair('deepslate_tile', 'east'),
                          'deepslate_tiles', 'deepslate_tile_slab[type=bottom]')
    b.fill_gable_z(nz1, nx1, nx2, 12, tops, 'stone_bricks')
    b.fill_gable_z(nz2, nx1, nx2, 12, tops, 'stone_bricks')
    # rose window on the front gable
    for (x, y) in [(7, 12), (6, 13), (8, 13), (7, 14), (7, 13)]:
        b.set(x, y, nz2, 'red_stained_glass_pane' if (x, y) != (7, 13) else 'yellow_stained_glass_pane')
    # front towers with spires
    for (tx1, tx2) in ((0, 3), (11, 14)):
        tz1, tz2 = 22, 25
        b.fill(tx1, 0, tz1, tx2, 0, tz2, 'stone_bricks')
        for y in range(1, 19):
            b.ring(tx1, tz1, tx2, tz2, y, 'stone_bricks')
        for y in (6, 12, 17):
            b.set((tx1 + tx2) // 2, y, tz2, 'glass_pane')
            b.set(tx1 if tx1 == 0 else tx2, y, (tz1 + tz2) // 2, 'glass_pane')
        b.fill(tx1, 19, tz1, tx2, 19, tz2, 'stone_bricks')
        b.crenels(tx1, tz1, tx2, tz2, 20, 'stone_brick_wall')
        for y, (a1, a2) in enumerate([(tx1 + 1, tx2 - 1)] * 1):
            pass
        b.fill(tx1 + 1, 20, tz1 + 1, tx2 - 1, 23, tz2 - 1, 'deepslate_tiles')
        b.set(tx1 + 1, 24, tz1 + 1, 'deepslate_tile_wall')
        b.set(tx1 + 1, 25, tz1 + 1, 'lightning_rod[facing=up,powered=false]')
        b.set(tx1 + 1, 21, tz1, 'lantern[hanging=false]') if False else None
    # bell between the towers above the portal
    b.set(7, 17, nz2 + 1, 'bell[attachment=floor,facing=south,powered=false]') if False else None
    # portal
    b.door(6, 1, nz2, 'north', 'left', 'dark_oak')
    b.door(7, 1, nz2, 'north', 'right', 'dark_oak')
    b.set(8, 1, nz2, 'stone_bricks')
    for x in (6, 7):
        b.set(x, 3, nz2, 'chiseled_stone_bricks')
    for x in (5, 8):
        b.set(x, 2, nz2 + 1, 'lantern[hanging=false]') if False else None
    b.torch(5, 3, nz2 + 1, 'south')
    b.torch(8, 3, nz2 + 1, 'south')
    for z in (nz2 + 1, nz2 + 2):
        for x in range(4, 11):
            b.set(x, 0, z, 'stone_bricks')
    # pews
    for z in range(9, 22, 2):
        for x in (4, 5, 9, 10):
            b.set(x, 1, z, stair('dark_oak', 'south'))
    for z in range(4, nz2):
        b.set(6, 1, z, 'red_carpet')
        b.set(7, 1, z, 'red_carpet')
        b.set(8, 1, z, 'red_carpet')
    # altar and apse
    b.fill(4, 1, 3, 10, 1, 5, 'smooth_quartz')
    b.fill(5, 2, 3, 9, 2, 3, 'quartz_bricks')
    b.set(7, 2, 4, 'chiseled_quartz_block')
    b.set(7, 3, 4, 'gold_block')
    for x in (5, 9):
        b.set(x, 2, 4, 'candle[candles=4,lit=true,waterlogged=false]')
    for x in (4, 10):
        for y in range(2, 9):
            b.set(x, y, 3, 'quartz_pillar[axis=y]')
    b.set(7, 9, nz1, 'yellow_stained_glass_pane')
    b.set(7, 8, nz1, 'yellow_stained_glass_pane')
    b.set(6, 8, nz1, 'light_blue_stained_glass_pane')
    b.set(8, 8, nz1, 'light_blue_stained_glass_pane')
    # chandeliers and banners
    for z in (8, 14, 20):
        for y in range(11, tops[7]):
            b.set(7, y, z, 'iron_chain[axis=y]')
        b.set(7, 10, z, 'lantern[hanging=true]')
    for z in (8, 16):
        b.set(nx1 + 1, 6, z, f'{K}_wall_banner[facing=east]')
        b.set(nx2 - 1, 6, z, f'{K}_wall_banner[facing=west]')
    for z in (8, 12, 16, 20):
        b.set(nx1 + 1, 3, z, 'wall_torch[facing=east]')
        b.set(nx2 - 1, 3, z, 'wall_torch[facing=west]')
    return b


# ------------------------------------------------------------------------------------------------ arena (amphitheatre)
def arena():
    W, H, D = 23, 9, 19
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(0, 0, 0, W - 1, 0, D - 1, 'stone_bricks')
    # sand floor
    ax1, az1, ax2, az2 = 5, 5, 17, 13
    b.fill(ax1, 0, az1, ax2, 0, az2, 'sand')
    b.ring(ax1 - 1, az1 - 1, ax2 + 1, az2 + 1, 1, 'stone_bricks')
    # tiered seats: rows rising away from the sand
    for k in range(1, 4):
        x1, z1, x2, z2 = ax1 - 1 - k, az1 - 1 - k, ax2 + 1 + k, az2 + 1 + k
        y = k + 1
        for x in range(x1 + 1, x2):
            b.set(x, y, z1, stair('stone_brick', 'north'))
            b.set(x, y, z2, stair('stone_brick', 'south'))
            for yy in range(1, y):
                b.set(x, yy, z1, 'stone_bricks')
                b.set(x, yy, z2, 'stone_bricks')
        for z in range(z1 + 1, z2):
            b.set(x1, y, z, stair('stone_brick', 'west'))
            b.set(x2, y, z, stair('stone_brick', 'east'))
            for yy in range(1, y):
                b.set(x1, yy, z, 'stone_bricks')
                b.set(x2, yy, z, 'stone_bricks')
        for (x, z) in b.corners(x1, z1, x2, z2):
            for yy in range(1, y + 1):
                b.set(x, yy, z, 'stone_bricks')
    # outer wall with crenels
    b.walls(0, 0, W - 1, D - 1, 1, 5, 'stone_bricks')
    b.crenels(0, 0, W - 1, D - 1, 6, 'stone_bricks')
    # gates north and south: passage through the stands into the sand
    for z in list(range(0, az1)) + list(range(az2 + 1, D)):
        for y in (1, 2, 3):
            b.set(11, y, z, AIR)
        b.set(11, 0, z, 'stone_bricks')
    b.set(11, 1, az1 - 1, 'spruce_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
    b.set(11, 1, az2 + 1, 'spruce_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
    b.set(11, 4, 0, 'stone_bricks') if False else None
    for (x, z, f) in [(10, D - 1, 'south'), (12, D - 1, 'south'), (10, 0, 'north'), (12, 0, 'north')]:
        b.set(x, 3, z + (1 if f == 'south' else -1) if False else z, f'{K}_wool')
    # lamps on the wall and around the sand
    for (x, z) in [(0, 0), (W - 1, 0), (0, D - 1), (W - 1, D - 1)]:
        b.set(x, 7, z, 'lantern[hanging=false]')
    for (x, z) in [(ax1 - 1, az1 - 1), (ax2 + 1, az1 - 1), (ax1 - 1, az2 + 1), (ax2 + 1, az2 + 1)]:
        b.set(x, 2, z, 'lantern[hanging=false]')
    b.set(8, 1, 9, 'hay_block[axis=y]')
    b.set(14, 1, 9, 'hay_block[axis=y]')
    b.set(14, 2, 9, 'target[power=0]')
    return b


# ------------------------------------------------------------------------------------------------ most (bridge)
def most():
    W, H, D = 7, 9, 19
    g = 3
    b = B(W, H, D)
    b.fill(0, g + 1, 0, W - 1, H - 1, D - 1, AIR)
    # deck at ground level: planks in the middle, stone edges
    for z in range(D):
        for x in range(1, 6):
            b.set(x, g, z, 'spruce_planks' if 2 <= x <= 4 else 'stone_bricks')
    # piers down into the water and arches under the deck
    for pz in (6, 12):
        for x in range(1, 6):
            for y in range(0, g):
                b.set(x, y, pz, 'stone_bricks')
        for x in range(1, 6):
            b.set(x, g - 1, pz - 1, stair('stone_brick', 'south', 'top'))
            b.set(x, g - 1, pz + 1, stair('stone_brick', 'north', 'top'))
    for x in range(1, 6):
        b.set(x, g - 1, 0, 'stone_bricks')
        b.set(x, g - 1, D - 1, 'stone_bricks')
    # parapets with lanterns
    for z in range(D):
        b.set(0, g + 1, z, 'stone_brick_wall')
        b.set(6, g + 1, z, 'stone_brick_wall')
        b.set(0, g, z, 'stone_bricks')
        b.set(6, g, z, 'stone_bricks')
    for z in (0, 6, 12, 18):
        for x in (0, 6):
            b.set(x, g + 2, z, 'lantern[hanging=false]')
    for z in (1, 2, 16, 17):
        b.set(0, g + 1, z, AIR) if False else None
    return b, g


# ------------------------------------------------------------------------------------------------ kamieniolom (quarry)
def kamieniolom():
    W, H, D = 15, 13, 15
    g = 5
    b = B(W, H, D)
    b.fill(0, g + 1, 0, W - 1, H - 1, D - 1, AIR)
    # stepped pit: every level one block narrower, the steps are the terraces (walkable, no fall over 1 block)
    for k in range(1, g + 1):
        y = g - k + 1
        b.fill(1 + k, y, 1 + k, W - 2 - k, y, D - 2 - k, AIR)
    for y in range(1, g + 1):
        pass
    b.fill(1 + g, 0, 1 + g, W - 2 - g, 0, D - 2 - g, 'stone')
    # fence around the pit with a gate, lamps on the corners
    for x in range(1, W - 1):
        b.set(x, g + 1, 1, 'spruce_fence')
        b.set(x, g + 1, D - 2, 'spruce_fence')
    for z in range(1, D - 1):
        b.set(1, g + 1, z, 'spruce_fence')
        b.set(W - 2, g + 1, z, 'spruce_fence')
    b.set(7, g + 1, D - 2, 'spruce_fence_gate[facing=south,open=false,in_wall=false,powered=false]')
    for (x, z) in [(1, 1), (W - 2, 1), (1, D - 2), (W - 2, D - 2)]:
        b.set(x, g + 2, z, 'lantern[hanging=false]')
    # crane on the north edge
    for y in range(g + 1, g + 6):
        b.set(7, y, 0, 'spruce_log[axis=y]')
    for z in range(0, 4):
        b.set(7, g + 6, z, 'spruce_log[axis=z]')
    b.set(7, g + 5, 3, 'iron_chain[axis=y]')
    b.set(7, g + 4, 3, 'iron_chain[axis=y]')
    b.set(7, g + 3, 3, 'lantern[hanging=true]')
    # workshop corner: stonecutter, chest, stone piles
    b.set(1, g, 13, 'stonecutter[facing=north]') if False else None
    b.set(0, g + 1, 13, 'stonecutter[facing=east]')
    b.set(0, g + 1, 12, 'chest[facing=east]')
    b.set(13, g + 1, 0, 'cobblestone') if False else None
    b.set(14, g + 1, 13, 'cobblestone')
    b.set(14, g + 1, 12, 'stone_slab[type=bottom]')
    b.set(14, g + 2, 13, 'cobblestone_slab[type=bottom]')
    b.set(7, 1, 7, 'lantern[hanging=false]')
    b.set(7, 2, 7, AIR)
    return b, g
