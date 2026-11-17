# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Buildings of later epochs (plugin Postep): Renaissance tenement, Industrial tenement, factory and railway station,
Modern block of flats, office tower and residential tower. Fixed materials (they are born in their epoch)."""
from lib import B

AIR = '.'
K = '{kolor}'


def stair(mat, facing, half='bottom'):
    return f'{mat}_stairs[facing={facing},half={half}]'


def lamp(b, x, y, z, post='polished_blackstone_wall', h=3, top='lantern[hanging=false]'):
    for k in range(h):
        b.set(x, y + k, z, post)
    b.set(x, y + h, z, top)


# ------------------------------------------------------------------------------------------------ Renaissance tenement
def kamienica_renesansowa():
    W, D = 11, 10
    x1, z1, x2, z2 = 0, 1, 10, 8
    floors = [1, 5, 9]          # ground floor (shop under arcades), two floors of flats
    roof = 13
    H = roof + 4
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'polished_andesite')
    b.fill(x1, 0, z2 + 1, x2, 0, D - 1, 'stone_bricks')
    # walls: stone ground floor, plaster above
    b.walls(x1, z1, x2, z2, 1, 3, 'stone_bricks')
    b.walls(x1, z1, x2, z2, 4, roof - 1, 'white_terracotta')
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, roof):
            b.set(x, y, z, 'smooth_sandstone')
    # cornices between the floors
    for y in (4, 8):
        for x in range(x1, x2 + 1):
            b.set(x, y, z2, 'smooth_sandstone')
    # arcade in front of the shop: pillars and arches (upside-down stairs)
    for x in (1, 4, 6, 9):
        b.set(x, 1, z2, 'stone_bricks')
        b.set(x, 2, z2, 'stone_bricks')
    for (x, f) in [(2, 'west'), (3, 'east'), (7, 'west'), (8, 'east')]:
        b.set(x, 1, z2, AIR)
        b.set(x, 2, z2, AIR)
        b.set(x, 3, z2, stair('stone_brick', f, 'top'))
    b.set(5, 3, z2, 'chiseled_stone_bricks')
    b.door(5, 1, z2, 'north', 'left', 'birch')
    # shop counter and goods
    for x in (2, 3, 7, 8):
        b.set(x, 1, z2 - 2, 'birch_slab[type=top]') if False else None
    b.set(2, 1, 3, 'barrel[facing=up]')
    b.set(8, 1, 3, 'barrel[facing=up]')
    b.set(3, 1, 3, 'chest[facing=south]')
    b.set(7, 1, 3, 'crafting_table')
    b.set(5, 3, 4, 'lantern[hanging=true]')
    # floors and stairs (ladder in the back corner)
    for y in (4, 8):
        b.fill(x1 + 1, y, z1 + 1, x2 - 1, y, z2 - 1, 'birch_planks')
    b.ladder(9, 1, roof - 1, 2, 'south')
    b.set(9, 4, 2, 'ladder[facing=south]')
    b.set(9, 8, 2, 'ladder[facing=south]')
    # windows with stone surrounds on the upper floors
    for y0 in (5, 9):
        for x in (2, 5, 8):
            b.set(x, y0 + 1, z2, 'glass_pane')
            b.set(x, y0 + 2, z2, 'glass_pane')
            b.set(x, y0 + 1, z1, 'glass_pane')
        for z in (3, 6):
            b.set(x1, y0 + 1, z, 'glass_pane')
            b.set(x2, y0 + 1, z, 'glass_pane')
        b.set(5, y0 + 2, 4, 'lantern[hanging=true]') if False else None
    # flats: 3 beds per floor
    for y0 in (5, 9):
        b.bed(2, y0, 2, 'north', 'red')
        b.bed(4, y0, 2, 'north', 'red')
        b.bed(7, y0, 2, 'north', 'blue') if y0 == 5 else b.bed(7, y0, 2, 'north', 'green')
        b.set(2, y0, 6, 'chest[facing=east]')
        b.set(8, y0, 6, 'bookshelf')
        b.set(5, y0, 6, 'crafting_table')
        b.set(5, 7 if y0 == 5 else 12, 4, 'lantern[hanging=true]')
    # roof: flat behind the attic, attic with crenels and urns
    b.fill(x1, roof, z1, x2, roof, z2, 'resin_bricks')
    b.set(9, roof, 2, 'birch_trapdoor[facing=south,half=top,open=false,powered=false,waterlogged=false]')
    for x in range(x1, x2 + 1):
        b.set(x, roof + 1, z2, 'smooth_sandstone')
        b.set(x, roof + 1, z1, 'smooth_sandstone_slab[type=bottom]')
        if x % 2 == 0:
            b.set(x, roof + 2, z2, 'smooth_sandstone_slab[type=bottom]')
    for z in range(z1, z2 + 1):
        b.set(x1, roof + 1, z, 'smooth_sandstone_slab[type=bottom]')
        b.set(x2, roof + 1, z, 'smooth_sandstone_slab[type=bottom]')
    for x in (0, 5, 10):
        b.set(x, roof + 2, z2, 'flower_pot') if False else None
        b.set(x, roof + 2, z2, 'sandstone_wall') if x != 5 else b.set(x, roof + 2, z2, 'chiseled_sandstone')
        b.set(x, roof + 3, z2, 'lantern[hanging=false]')
    b.torch(3, 3, z2 + 1, 'south') if False else None
    lamp(b, 0, 1, 9, 'spruce_fence', 2)
    lamp(b, 10, 1, 9, 'spruce_fence', 2)
    return b


# ------------------------------------------------------------------------------------------------ Industrial tenement
def kamienica_czynszowa():
    W, D = 11, 11
    x1, z1, x2, z2 = 0, 1, 10, 9
    roof = 20
    H = roof + 5
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'polished_andesite')
    b.fill(x1, 0, z2 + 1, x2, 0, D - 1, 'stone_bricks')
    b.walls(x1, z1, x2, z2, 1, roof - 1, 'bricks')
    b.ring(x1, z1, x2, z2, 1, 'stone_bricks')
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, roof):
            b.set(x, y, z, 'stone_bricks')
    levels = [1, 5, 9, 13, 17]
    for y in levels[1:]:
        b.fill(x1 + 1, y - 1, z1 + 1, x2 - 1, y - 1, z2 - 1, 'dark_oak_planks')
        for x in range(x1, x2 + 1):
            b.set(x, y - 1, z2, 'stone_bricks')
    b.ladder(9, 1, roof - 1, 2, 'south')
    for y in levels[1:]:
        b.set(9, y - 1, 2, 'ladder[facing=south]')
    b.door(5, 1, z2, 'north', 'left', 'dark_oak')
    b.set(5, 3, z2 + 1, 'iron_chain[axis=y]') if False else None
    b.torch(4, 2, z2 + 1, 'south')
    b.torch(6, 2, z2 + 1, 'south')
    for y0 in levels:
        for x in (2, 4, 6, 8):
            if y0 == 1 and x in (4, 6):
                continue
            b.set(x, y0 + 1, z2, 'glass_pane')
            b.set(x, y0 + 2, z2, 'glass_pane')
            b.set(x, y0 + 1, z1, 'glass_pane')
        for z in (3, 6):
            b.set(x1, y0 + 1, z, 'glass_pane')
            b.set(x2, y0 + 1, z, 'glass_pane')
        b.set(5, y0 + 2, 5, 'lantern[hanging=true]')
    # balconies with iron railings on the upper floors
    for y0 in levels[2:]:
        for x in (3, 4, 5, 6, 7):
            b.set(x, y0 - 1, z2 + 1, 'stone_brick_slab[type=top]')
            b.set(x, y0, z2 + 1, 'iron_bars')
        b.set(5, y0, z2, 'dark_oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]')
        b.set(5, y0 + 1, z2, 'dark_oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]')
    # 8 beds: 2 per upper floor
    for y0 in levels[1:]:
        b.bed(2, y0, 2, 'north', 'white')
        b.bed(5, y0, 2, 'north', 'gray')
        b.set(2, y0, 7, 'chest[facing=east]')
        b.set(8, y0, 7, 'furnace[facing=west]')
    # ground floor: shop / workshop
    b.set(2, 1, 3, 'barrel[facing=up]')
    b.set(7, 1, 3, 'smithing_table')
    b.set(3, 1, 3, 'chest[facing=south]')
    # flat roof with chimneys
    b.fill(x1, roof, z1, x2, roof, z2, 'stone_bricks')
    b.set(9, roof, 2, 'spruce_trapdoor[facing=south,half=top,open=false,powered=false,waterlogged=false]')
    for x in range(x1, x2 + 1):
        b.set(x, roof + 1, z1, 'stone_brick_slab[type=bottom]')
        b.set(x, roof + 1, z2, 'stone_brick_slab[type=bottom]')
    for z in range(z1, z2 + 1):
        b.set(x1, roof + 1, z, 'stone_brick_slab[type=bottom]')
        b.set(x2, roof + 1, z, 'stone_brick_slab[type=bottom]')
    for (x, z) in [(2, 5), (8, 5)]:
        for y in range(roof + 1, roof + 4):
            b.set(x, y, z, 'bricks')
        b.set(x, roof + 4, z, 'campfire[lit=true,signal_fire=false,facing=south,waterlogged=false]')
    lamp(b, 0, 1, 10, 'polished_blackstone_wall', 3)
    lamp(b, 10, 1, 10, 'polished_blackstone_wall', 3)
    return b


# ------------------------------------------------------------------------------------------------ factory
def fabryka():
    W, D = 19, 14
    x1, z1, x2, z2 = 0, 2, 18, 12
    wall = 8
    H = 26
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'polished_andesite')
    b.walls(x1, z1, x2, z2, 1, wall, 'bricks')
    b.ring(x1, z1, x2, z2, 1, 'stone_bricks')
    for x in range(x1, x2 + 1, 3):
        for y in range(1, wall + 1):
            b.set(x, y, z1, 'stone_bricks')
            b.set(x, y, z2, 'stone_bricks')
    # tall factory windows
    for x in range(x1 + 1, x2, 3):
        for dx in (0, 1):
            for y in range(3, 7):
                b.set(x + dx, y, z1, 'glass_pane')
                if not (8 <= x + dx <= 11):
                    b.set(x + dx, y, z2, 'glass_pane')
    for z in (z1 + 3, z1 + 4, z1 + 6, z1 + 7):
        for y in range(3, 7):
            b.set(x1, y, z, 'glass_pane')
            b.set(x2, y, z, 'glass_pane')
    # roof: slate, with saw-tooth ridges facing north (skylights in real factories)
    b.fill(x1, wall + 1, z1, x2, wall + 1, z2, 'deepslate_tiles')
    for z0 in range(z1, z2 + 1, 3):
        for x in range(x1, x2 + 1):
            b.set(x, wall + 2, z0, 'deepslate_tiles')
            if z0 + 1 <= z2:
                b.set(x, wall + 2, z0 + 1, stair('deepslate_tile', 'north'))
    # big double door
    b.door(9, 1, z2, 'north', 'left', 'spruce')
    b.door(10, 1, z2, 'north', 'right', 'spruce')
    b.torch(8, wall, z2 + 1, 'south')
    b.torch(11, wall, z2 + 1, 'south')
    for x in range(x1, x2 + 1):
        b.set(x, 0, z2 + 1, 'stone_bricks')
    # machines: furnaces, blast furnaces, anvils, a line of rails (conveyor)
    for x in range(2, 17, 2):
        b.set(x, 1, z1 + 2, 'blast_furnace[facing=south,lit=true]' if x % 4 == 0 else 'furnace[facing=south,lit=false]')
        b.set(x, 2, z1 + 2, 'iron_block' if x % 4 == 0 else 'smooth_stone')
    for x in range(3, 16):
        b.set(x, 1, z1 + 5, 'powered_rail[powered=false,shape=east_west,waterlogged=false]' if x % 5 == 0
              else 'rail[shape=east_west,waterlogged=false]')
    for x in (2, 6, 12, 16):
        b.set(x, 1, z1 + 7, 'anvil[facing=east]' if x < 9 else 'smithing_table')
        b.set(x + 1, 1, z1 + 7, 'chest[facing=north,type=single,waterlogged=false]')
    b.set(17, 1, z1 + 8, 'barrel[facing=up,open=false]')
    b.set(1, 1, z1 + 8, 'barrel[facing=up,open=false]')
    for x in (4, 9, 14):
        for z in (z1 + 3, z1 + 7):
            b.set(x, wall, z, 'iron_chain[axis=y]')
            b.set(x, wall - 1, z, 'lantern[hanging=true]')
    # two brick chimneys with smoke behind the hall
    for cx in (3, 14):
        for y in range(1, H - 1):
            for dx in (0, 1):
                for cz in (0, 1):
                    b.set(cx + dx, y, cz, 'stone_bricks' if y % 6 == 0 else 'bricks')
        for dx in (0, 1):
            for cz in (0, 1):
                b.set(cx + dx, 0, cz, 'stone_bricks')
                b.set(cx + dx, H - 1, cz, 'campfire[lit=true,signal_fire=true,facing=south,waterlogged=false]')
    return b


# ------------------------------------------------------------------------------------------------ railway station
def dworzec():
    W, D = 23, 15
    H = 14
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    # tracks along the north side (x), on gravel
    for x in range(W):
        b.set(x, 0, 0, 'gravel')
        b.set(x, 0, 1, 'gravel')
        b.set(x, 1, 1, 'rail[shape=east_west,waterlogged=false]')
        b.set(x, 0, 2, 'stone_bricks')
    # platform with a canopy on iron posts
    for z in (3, 4):
        for x in range(W):
            b.set(x, 0, z, 'smooth_stone')
            b.set(x, 1, z, 'smooth_stone_slab[type=bottom]')
    for x in range(1, W - 1, 4):
        for y in (2, 3, 4):
            b.set(x, y, 4, 'iron_bars')
    for x in range(W):
        b.set(x, 5, 3, 'dark_oak_slab[type=bottom]')
        b.set(x, 5, 4, 'dark_oak_slab[type=bottom]')
    for x in range(3, W - 3, 4):
        b.set(x, 4, 3, 'lantern[hanging=true]')
        b.set(x + 1, 2, 3, stair('dark_oak', 'south')) if x + 1 < W else None
    # station hall
    x1, z1, x2, z2 = 4, 5, 18, 12
    b.fill(x1, 0, z1, x2, 0, z2, 'polished_andesite')
    b.walls(x1, z1, x2, z2, 1, 7, 'bricks')
    b.ring(x1, z1, x2, z2, 1, 'stone_bricks')
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, 9):
            b.set(x, y, z, 'stone_bricks')
    for x in range(x1 + 2, x2 - 1, 3):
        for y in (3, 4, 5):
            b.set(x, y, z1, 'glass_pane')
            b.set(x, y, z2, 'glass_pane') if x != 11 else None
    b.door(11, 1, z2, 'north', 'left', 'dark_oak')
    b.door(11, 1, z1, 'south', 'left', 'dark_oak')
    b.torch(10, 6, z2 + 1, 'south')
    b.torch(12, 6, z2 + 1, 'south')
    # clock tower over the entrance
    for y in range(8, 12):
        b.ring(10, z2 - 1, 12, z2 + 1, y, 'bricks') if False else None
    tops = b.gable_roof_x(x1 - 1, x2 + 1, z1, z2, 8, stair('deepslate_tile', 'north'), stair('deepslate_tile', 'south'),
                          'deepslate_tiles', 'deepslate_tile_slab[type=bottom]')
    for z in range(z1, z2 + 1):
        for y in range(8, tops[z]):
            b.set(x1, y, z, 'bricks')
            b.set(x2, y, z, 'bricks')
    b.set(11, 9, z2 + 1, 'daylight_detector[inverted=false,power=0]') if False else None
    b.set(11, 8, z2 + 1, 'white_concrete') if False else None
    # inside: benches, ticket office, lamps
    for x in (6, 7, 15, 16):
        b.set(x, 1, 8, stair('dark_oak', 'south'))
        b.set(x, 1, 10, stair('dark_oak', 'north'))
    b.set(17, 1, 6, 'lectern[facing=west,has_book=false,powered=false]')
    b.set(17, 1, 7, 'barrel[facing=up,open=false]')
    for x in (7, 11, 15):
        b.set(x, tops[8] - 1, 8, 'lantern[hanging=true]')
    for x in (0, 22):
        lamp(b, x, 1, 13)
    for x in range(0, W):
        b.set(x, 0, 13, 'stone_bricks')
        b.set(x, 0, 14, 'stone_bricks')
    return b


# ------------------------------------------------------------------------------------------------ Modern: block of flats
def blok():
    W, D = 15, 11
    x1, z1, x2, z2 = 0, 0, 14, 9
    storey = 3
    floors = 6
    roof = floors * storey + 1
    H = roof + 3
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, 'smooth_stone')
    b.fill(x1, 0, z2 + 1, x2, 0, D - 1, 'light_gray_concrete')
    b.walls(x1, z1, x2, z2, 1, roof - 1, 'white_concrete')
    b.ring(x1, z1, x2, z2, 1, 'gray_concrete')
    beds = 0
    for f in range(floors):
        y0 = 1 + f * storey
        if f > 0:
            b.fill(x1 + 1, y0 - 1, z1 + 1, x2 - 1, y0 - 1, z2 - 1, 'smooth_stone')
            for x in range(x1, x2 + 1):
                b.set(x, y0 - 1, z2, 'light_gray_concrete')
                b.set(x, y0 - 1, z1, 'light_gray_concrete')
        # window band
        for x in range(x1 + 1, x2):
            if x not in (4, 10):
                b.set(x, y0 + 1, z1, 'black_stained_glass_pane')
                if not (f == 0 and 6 <= x <= 8):
                    b.set(x, y0 + 1, z2, 'black_stained_glass_pane')
        for z in range(z1 + 2, z2 - 1):
            b.set(x1, y0 + 1, z, 'black_stained_glass_pane')
            b.set(x2, y0 + 1, z, 'black_stained_glass_pane')
        b.set(7, y0 + storey - 2 + 1, 5, 'sea_lantern') if False else None
        if f > 0:
            # three flats per floor along the back wall
            for x in (2, 6, 11):
                b.bed(x, y0, 1, 'north', 'light_gray' if x != 6 else 'cyan')
                beds += 1
            b.set(3, y0, 7, 'chest[facing=north,type=single,waterlogged=false]')
            b.set(12, y0, 7, 'barrel[facing=up,open=false]')
        b.set(7, y0 + storey - 1, 5, 'sea_lantern')
    # staircase (lift shaft with a ladder) in the middle
    b.ladder(8, 1, roof - 1, 8, 'north')
    for f in range(1, floors):
        b.set(8, f * storey, 8, 'ladder[facing=north]')
    b.set(8, roof, 8, 'iron_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]')
    # entrance with a canopy
    b.door(7, 1, z2, 'north', 'left', 'dark_oak')
    for x in (6, 7, 8):
        b.set(x, 3, z2 + 1, 'smooth_quartz_slab[type=bottom]')
    b.set(6, 2, z2, 'sea_lantern') if False else None
    # flat roof with a parapet and antennas
    b.fill(x1, roof, z1, x2, roof, z2, 'gray_concrete')
    b.set(8, roof, 8, 'iron_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]')
    for x in range(x1, x2 + 1):
        b.set(x, roof + 1, z1, 'light_gray_concrete')
        b.set(x, roof + 1, z2, 'light_gray_concrete')
    for z in range(z1, z2 + 1):
        b.set(x1, roof + 1, z, 'light_gray_concrete')
        b.set(x2, roof + 1, z, 'light_gray_concrete')
    b.set(3, roof + 1, 4, 'lightning_rod[facing=up,powered=false,waterlogged=false]')
    b.set(11, roof + 1, 4, 'lightning_rod[facing=up,powered=false,waterlogged=false]')
    b.set(7, roof + 1, 4, 'sea_lantern')
    lamp(b, 1, 1, 10, 'andesite_wall', 3, 'sea_lantern')
    lamp(b, 13, 1, 10, 'andesite_wall', 3, 'sea_lantern')
    return b, beds


# ------------------------------------------------------------------------------------------------ Modern: towers
def _tower(name_beds, storeys, glass, band, bedded):
    W = D = 13
    x1, z1, x2, z2 = 1, 1, 11, 11
    storey = 4
    roof = storeys * storey + 1
    H = roof + 5
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(0, 0, 0, W - 1, 0, D - 1, 'smooth_stone')
    b.fill(x1, 0, z1, x2, 0, z2, 'polished_andesite')
    beds = 0
    for f in range(storeys):
        y0 = 1 + f * storey
        # curtain wall: glass between concrete columns, floor slab band
        for y in range(y0, y0 + storey - 1):
            b.ring(x1, z1, x2, z2, y, glass)
            for (x, z) in b.corners(x1, z1, x2, z2) + [(6, z1), (6, z2), (x1, 6), (x2, 6)]:
                b.set(x, y, z, band)
        b.ring(x1, z1, x2, z2, y0 + storey - 1, band)
        b.fill(x1 + 1, y0 + storey - 1, z1 + 1, x2 - 1, y0 + storey - 1, z2 - 1, 'smooth_stone')
        # core: lift shaft (ladder) and lights
        b.set(6, y0 + storey - 1, 6, 'ladder[facing=north]')
        for y in range(y0, y0 + storey - 1):
            b.set(6, y, 6, 'ladder[facing=north]')
            b.set(6, y, 7, 'light_gray_concrete')
        for (x, z) in [(3, 3), (9, 3), (3, 9), (9, 9)]:
            b.set(x, y0 + storey - 1, z, 'sea_lantern')
        if f == 0:
            continue
        if bedded:
            for (x, z, fac) in [(3, 3, 'north'), (9, 3, 'north')]:
                b.bed(x, y0, z, fac, 'white')
                beds += 1
            b.set(3, y0, 9, 'chest[facing=north,type=single,waterlogged=false]')
            b.set(9, y0, 9, 'barrel[facing=up,open=false]')
        else:
            for (x, z) in [(3, 3), (9, 3), (3, 9), (9, 9)]:
                b.set(x, y0, z, 'lectern[facing=north,has_book=false,powered=false]')
            b.set(4, y0, 9, 'barrel[facing=up,open=false]')
    # lobby: glass doors and canopy
    for x in (5, 6, 7):
        b.set(x, 1, z2, AIR)
        b.set(x, 2, z2, AIR)
    b.door(6, 1, z2, 'north', 'left', 'waxed_copper')
    b.set(5, 1, z2, glass)
    b.set(7, 1, z2, glass)
    b.set(5, 2, z2, glass)
    b.set(7, 2, z2, glass)
    for x in range(4, 9):
        b.set(x, 3, z2 + 1, 'smooth_quartz_slab[type=bottom]')
    b.set(3, 1, 9, 'lectern[facing=south,has_book=false,powered=false]')
    # roof: parapet, plant room, antennas / helipad
    b.fill(x1, roof, z1, x2, roof, z2, 'gray_concrete')
    b.set(6, roof, 6, 'iron_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]')
    for x in range(x1, x2 + 1):
        b.set(x, roof + 1, z1, band)
        b.set(x, roof + 1, z2, band)
    for z in range(z1, z2 + 1):
        b.set(x1, roof + 1, z, band)
        b.set(x2, roof + 1, z, band)
    if bedded:
        b.fill(8, roof + 1, 3, 10, roof + 2, 5, 'light_gray_concrete')
        b.set(9, roof + 3, 4, 'lightning_rod[facing=up,powered=false,waterlogged=false]')
    else:
        for (x, z) in [(4, 4), (4, 8), (8, 4), (8, 8), (4, 6), (8, 6), (5, 6), (6, 6), (7, 6)]:
            b.set(x, roof, z, 'yellow_concrete')
        b.set(6, roof, 6, 'iron_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]')
        for y in range(roof + 1, roof + 5):
            b.set(x1, y, z1, 'iron_bars')
        b.set(x1, roof + 5 - 1, z1, 'lightning_rod[facing=up,powered=false,waterlogged=false]')
    for (x, z) in [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]:
        b.set(x, roof + 2, z, 'sea_lantern') if (x, z) != (x1, z1) or bedded else None
    b.set(0, 1, 12, 'andesite_wall') if False else None
    lamp(b, 0, 1, 12, 'andesite_wall', 3, 'sea_lantern')
    lamp(b, 12, 1, 12, 'andesite_wall', 3, 'sea_lantern')
    return b, beds


def wiezowiec_biurowy():
    b, _ = _tower('office', 10, 'light_blue_stained_glass_pane', 'light_gray_concrete', False)
    return b


def wiezowiec():
    return _tower('flats', 15, 'gray_stained_glass_pane', 'white_concrete', True)
