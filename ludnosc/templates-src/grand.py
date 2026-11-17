# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Representative buildings: zamek (castle) and palac (palace)."""
from lib import B

AIR = '.'
K = '{kolor}'


def stair(mat, facing, half='bottom'):
    return f'{mat}_stairs[facing={facing},half={half}]'


# ------------------------------------------------------------------------------------------------ zamek (castle)
def _tower(b, x1, z1, x2, z2, top, door, ladder, openings):
    b.fill(x1, 0, z1, x2, 0, z2, 'stone_bricks')
    for y in range(1, top):
        b.ring(x1, z1, x2, z2, y, 'stone_bricks')
        b.fill(x1 + 1, y, z1 + 1, x2 - 1, y, z2 - 1, AIR)
    b.fill(x1, top, z1, x2, top, z2, 'stone_bricks')
    b.crenels(x1, z1, x2, z2, top + 1, 'stone_bricks')
    dx, dz, facing = door
    b.door(dx, 1, dz, facing, 'left', 'spruce')
    lx, lz, lf = ladder
    b.ladder(lx, 1, top, lz, lf)
    for (ox, oz) in openings:
        b.set(ox, 8, oz, AIR)
        b.set(ox, 9, oz, AIR)
    for (x, z) in b.corners(x1, z1, x2, z2):
        b.set(x, top + 2, z, 'lantern[hanging=false]')
    b.set(x1 + 2, 4, z1, AIR) if z1 == 0 else None


def zamek():
    W, H, D = 23, 18, 24
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    # courtyard ground
    for z in range(2, 21):
        for x in range(2, 21):
            b.set(x, 0, z, 'coarse_dirt' if (x * 7 + z * 3) % 5 else 'gravel')
    for z in range(13, 23):
        for x in range(10, 13):
            b.set(x, 0, z, 'cobblestone')
    # curtain walls (2 thick) with walkway at y7 and merlons on the outer line
    for (x1, z1, x2, z2) in [(0, 0, 22, 1), (0, 21, 22, 22), (0, 0, 1, 22), (21, 0, 22, 22)]:
        b.fill(x1, 0, z1, x2, 7, z2, 'stone_bricks')
    for x in range(W):
        if x % 2 == 0:
            b.set(x, 8, 0, 'stone_bricks')
            b.set(x, 8, 22, 'stone_bricks')
    for z in range(D - 1):
        if z % 2 == 0:
            b.set(0, 8, z, 'stone_bricks')
            b.set(22, 8, z, 'stone_bricks')
    for (x, y, z) in [(3, 2, 0), (8, 5, 0), (15, 3, 0), (0, 4, 9), (22, 2, 13), (6, 6, 22), (17, 1, 22), (0, 1, 15)]:
        b.set(x, y, z, 'mossy_stone_bricks')
    for (x, y, z) in [(12, 4, 0), (0, 6, 6), (22, 5, 7), (4, 3, 22)]:
        b.set(x, y, z, 'cracked_stone_bricks')
    # corner towers
    _tower(b, 0, 0, 4, 4, 11, (2, 4, 'north'), (1, 2, 'east'), [(4, 1), (1, 4)])
    _tower(b, 18, 0, 22, 4, 11, (20, 4, 'north'), (21, 2, 'west'), [(18, 1), (21, 4)])
    _tower(b, 0, 18, 4, 22, 11, (2, 18, 'south'), (1, 20, 'east'), [(4, 21), (1, 18)])
    _tower(b, 18, 18, 22, 22, 11, (20, 18, 'south'), (21, 20, 'west'), [(18, 21), (21, 18)])
    # gatehouse (front, south) with portcullis
    b.fill(9, 0, 19, 13, 0, 22, 'stone_bricks')
    for y in range(1, 10):
        b.ring(9, 19, 13, 22, y, 'stone_bricks')
    b.fill(10, 5, 20, 12, 5, 21, 'spruce_planks')
    b.fill(10, 6, 20, 12, 8, 21, AIR)
    b.fill(10, 9, 19, 12, 9, 22, 'stone_bricks')
    b.crenels(9, 19, 13, 22, 10, 'stone_bricks')
    b.fill(10, 1, 19, 12, 4, 22, AIR)
    for z in (19, 22):
        for x in (10, 12):
            b.set(x, 4, z, 'stone_brick_stairs[facing=east,half=top]' if x == 10 else 'stone_brick_stairs[facing=west,half=top]')
    for x in range(10, 13):
        b.set(x, 4, 22, 'iron_bars')
    b.set(9, 6, 23, f'{K}_wall_banner[facing=south]')
    b.set(13, 6, 23, f'{K}_wall_banner[facing=south]')
    b.torch(9, 3, 23, 'south')
    b.torch(13, 3, 23, 'south')
    b.torch(10, 3, 20, 'east') if False else None
    # keep (donjon)
    kx1, kx2, kz1, kz2 = 7, 15, 4, 12
    b.fill(kx1, 0, kz1, kx2, 0, kz2, 'stone_bricks')
    b.fill(kx1 + 1, 0, kz1 + 1, kx2 - 1, 0, kz2 - 1, 'spruce_planks')
    for y in range(1, 14):
        b.ring(kx1, kz1, kx2, kz2, y, 'stone_bricks')
    for y in range(1, 14):
        for (x, z) in b.corners(kx1, kz1, kx2, kz2):
            b.set(x, y, z, 'chiseled_stone_bricks' if y % 4 == 0 else 'stone_bricks')
    for fy in (5, 9, 13):
        b.fill(kx1 + 1, fy, kz1 + 1, kx2 - 1, fy, kz2 - 1, 'spruce_planks' if fy < 13 else 'stone_bricks')
    b.crenels(kx1, kz1, kx2, kz2, 14, 'stone_bricks')
    b.set(11, 14, 8, 'spruce_fence')
    b.set(11, 15, 8, 'spruce_fence')
    b.set(11, 16, 8, f'{K}_banner[rotation=8]')
    b.door(11, 1, kz2, 'north', 'left', 'dark_oak')
    b.set(11, 3, kz2, 'chiseled_stone_bricks')
    b.ladder(8, 1, 13, 5, 'east')
    for (x, y, z) in [(9, 2, kz2), (13, 2, kz2), (kx1, 2, 8), (kx2, 2, 8), (9, 7, kz2), (13, 7, kz2), (11, 7, kz1),
                      (kx1, 7, 8), (kx2, 7, 8), (10, 11, kz2), (12, 11, kz2), (kx1, 11, 8), (kx2, 11, 8), (11, 11, kz1)]:
        b.set(x, y, z, 'glass_pane')
    b.set(11, 3, kz2 + 1, f'{K}_wall_banner[facing=south]') if False else None
    b.torch(10, 3, kz2 + 1, 'south')
    b.torch(12, 3, kz2 + 1, 'south')
    # keep: great hall
    for z in range(7, 11):
        b.set(11, 1, z, 'dark_oak_fence')
        b.set(11, 2, z, f'{K}_carpet')
        b.set(10, 1, z, stair('dark_oak', 'east'))
        b.set(12, 1, z, stair('dark_oak', 'west'))
    b.set(11, 1, 6, 'quartz_stairs[facing=north,half=bottom]')
    b.set(11, 2, 5, f'{K}_wall_banner[facing=south]')
    b.set(9, 3, 5, f'{K}_wall_banner[facing=south]')
    b.set(13, 3, 5, f'{K}_wall_banner[facing=south]')
    b.set(10, 4, 8, 'lantern[hanging=true]')
    b.set(12, 4, 8, 'lantern[hanging=true]')
    b.set(14, 1, 11, 'barrel[facing=up]')
    b.set(14, 1, 10, 'chest[facing=west]')
    # lord's chamber (4 beds)
    for x in (9, 10, 12, 13):
        b.bed(x, 6, 5, 'north', 'red')
    b.set(11, 6, 5, 'chest[facing=south]')
    b.set(11, 7, 5, 'lantern[hanging=false]')
    for x in range(9, 14):
        b.set(x, 6, 9, f'{K}_carpet')
    b.set(14, 6, 11, 'bookshelf')
    b.set(13, 6, 11, 'lectern[facing=north]')
    b.set(11, 8, 9, 'lantern[hanging=true]')
    # armoury
    b.set(9, 10, 5, 'anvil[facing=east]')
    b.set(10, 10, 5, 'smithing_table')
    b.set(12, 10, 5, 'barrel[facing=up]')
    b.set(13, 10, 5, 'barrel[facing=up]')
    b.set(14, 10, 8, 'grindstone[face=floor,facing=west]')
    b.set(11, 12, 8, 'lantern[hanging=true]')
    # courtyard: well, training dummies, hay, lamp posts
    b.set(6, 0, 16, 'water[level=0]')
    for (x, z) in [(5, 15), (6, 15), (7, 15), (5, 16), (7, 16), (5, 17), (6, 17), (7, 17)]:
        b.set(x, 1, z, 'cobblestone_wall')
    b.set(16, 1, 16, 'spruce_fence')
    b.set(16, 2, 16, 'carved_pumpkin[facing=south]')
    b.set(18, 1, 15, 'spruce_fence')
    b.set(18, 2, 15, 'carved_pumpkin[facing=west]')
    for (x, z) in [(17, 7), (18, 7), (17, 8), (19, 9)]:
        b.set(x, 1, z, 'hay_block[axis=y]')
    b.set(18, 2, 7, 'hay_block[axis=x]')
    b.set(4, 1, 9, 'anvil[facing=north]')
    b.set(4, 1, 10, 'barrel[facing=up]')
    for (x, z) in [(9, 17), (13, 17), (5, 6), (17, 12)]:
        b.set(x, 1, z, 'spruce_fence')
        b.set(x, 2, z, 'spruce_fence')
        b.set(x, 3, z, 'lantern[hanging=false]')
    for x in (6, 16):
        b.torch(x, 4, 2, 'south')
        b.torch(x, 4, 20, 'north')
    for z in (8, 13):
        b.torch(2, 4, z, 'east')
        b.torch(20, 4, z, 'west')
    return b


# ------------------------------------------------------------------------------------------------ palac (palace)
FLOWERS = ['poppy', 'cornflower', 'allium', 'azure_bluet', 'oxeye_daisy', 'red_tulip', 'white_tulip',
           'lily_of_the_valley', 'pink_tulip', 'blue_orchid']


def palac():
    W, H, D = 30, 23, 29
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    hx1, hx2, hz1, hz2 = 9, 20, 3, 16
    # ---- floors
    for z in range(hz1, hz2 + 1):
        for x in range(hx1, hx2 + 1):
            b.set(x, 0, z, 'smooth_quartz' if (x + z) % 2 == 0 else 'polished_andesite')
    b.ring(hx1, hz1, hx2, hz2, 0, 'polished_andesite')
    for (wx1, wx2) in ((1, 9), (20, 28)):
        b.fill(wx1, 0, 5, wx2, 0, 14, 'dark_oak_planks')
        b.ring(wx1, 5, wx2, 14, 0, 'polished_andesite')
    b.fill(hx1, 0, 17, hx2, 0, 19, 'smooth_stone')
    # ---- wings (flat roof with balustrade)
    for (wx1, wx2, outer) in ((1, 9, 1), (20, 28, 28)):
        b.walls(wx1, 5, wx2, 14, 1, 6, 'quartz_bricks')
        b.ring(wx1, 5, wx2, 14, 1, 'smooth_stone')
        for (x, z) in b.corners(wx1, 5, wx2, 14):
            for y in range(1, 7):
                b.set(x, y, z, 'quartz_pillar[axis=y]')
        b.fill(wx1, 7, 5, wx2, 7, 14, 'smooth_quartz')
        for x in range(wx1, wx2 + 1):
            b.set(x, 8, 5, 'smooth_quartz_slab[type=bottom]')
            b.set(x, 8, 14, 'smooth_quartz_slab[type=bottom]')
        for z in range(5, 15):
            b.set(outer, 8, z, 'smooth_quartz_slab[type=bottom]')
        for z in (5, 14):
            b.set(outer, 8, z, 'quartz_pillar[axis=y]')
            b.set(outer, 9, z, 'lantern[hanging=false]')
        for z in (7, 9, 11, 13):
            for y in (2, 3, 4):
                b.set(outer, y, z, 'glass_pane')
        for x in ((3, 5, 7) if wx1 == 1 else (22, 24, 26)):
            for y in (2, 3, 4):
                b.set(x, y, 14, 'glass_pane')
    # ---- main hall
    b.walls(hx1, hz1, hx2, hz2, 1, 9, 'quartz_bricks')
    b.ring(hx1, hz1, hx2, hz2, 1, 'smooth_stone')
    b.ring(hx1, hz1, hx2, hz2, 9, 'smooth_quartz')
    for (x, z) in b.corners(hx1, hz1, hx2, hz2) + [(hx1, 7), (hx1, 10), (hx1, 13), (hx2, 7), (hx2, 10), (hx2, 13),
                                                    (12, hz2), (17, hz2), (12, hz1), (17, hz1)]:
        for y in range(1, 9):
            b.set(x, y, z, 'quartz_pillar[axis=y]')
    for z in range(hz1, 20):
        b.set(hx1, 10, z, 'quartz_bricks')
        b.set(hx2, 10, z, 'quartz_bricks')
    # pediment over the portico (ridge along z)
    tops = b.gable_roof_z(17, 20, 8, 21, 10, stair('deepslate_tile', 'west'), stair('deepslate_tile', 'east'))
    b.fill_gable_z(17, hx1, hx2, 10, tops, 'smooth_quartz')
    b.fill_gable_z(19, hx1, hx2, 10, tops, 'smooth_quartz')
    for x in (14, 15):
        b.set(x, 12, 20, f'{K}_wall_banner[facing=south]')
        b.set(x, 15, 19, 'gold_block')
    # flat roof of the hall with a balustrade, drum with windows and a copper dome over the throne room
    b.fill(hx1, 10, hz1, hx2, 10, 16, 'smooth_quartz')
    for x in range(hx1, hx2 + 1):
        b.set(x, 11, hz1, 'smooth_quartz_slab[type=bottom]')
    for z in range(hz1, 17):
        b.set(hx1, 11, z, 'smooth_quartz_slab[type=bottom]')
        b.set(hx2, 11, z, 'smooth_quartz_slab[type=bottom]')
    cx, cz = 14.5, 9.5

    def dist(x, z):
        return ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5

    for z in range(hz1, 17):
        for x in range(hx1, hx2 + 1):
            d = dist(x, z)
            if d <= 2.2:
                b.set(x, 10, z, AIR)  # oculus: the dome is seen from the hall
            if 3.6 <= d <= 4.6:
                for y in range(11, 15):
                    window = y in (12, 13) and (abs(x - cx) < 1 or abs(z - cz) < 1)
                    b.set(x, y, z, 'light_blue_stained_glass_pane' if window else 'quartz_bricks')
                b.set(x, 15, z, 'chiseled_quartz_block' if (x + z) % 2 else 'quartz_bricks')
    for dy in range(0, 5):
        r = (4.6 ** 2 - (dy * 1.1) ** 2) ** 0.5
        for z in range(hz1, 17):
            for x in range(hx1, hx2 + 1):
                d = dist(x, z)
                if (r - 1.2 < d <= r) or (dy == 4 and d <= r):
                    b.set(x, 16 + dy, z, 'waxed_oxidized_cut_copper')
    for (x, z) in ((14, 9), (15, 9), (14, 10), (15, 10)):
        b.set(x, 21, z, 'gold_block')
    b.set(14, 22, 9, 'lightning_rod[facing=up,powered=false]')
    # corner turrets on the front of the wings
    for (tx1, tx2) in ((1, 3), (26, 28)):
        for y in range(8, 12):
            b.ring(tx1, 12, tx2, 14, y, 'quartz_bricks')
            b.set(tx1 + 1, y, 13, AIR)
        for y in (9, 10):
            b.set(tx1 + 1, y, 14, 'glass_pane')
            b.set(tx1 if tx1 == 1 else tx2, y, 13, 'glass_pane')
        b.pyramid(tx1, 12, tx2, 14, 12, 'deepslate_tile', 'deepslate_tiles', 'lantern[hanging=false]')
    b.set(13, 11, 19, 'chiseled_quartz_block')
    b.set(16, 11, 19, 'chiseled_quartz_block')
    # clerestory and front/back windows
    for z in (6, 9, 12):
        b.set(hx1, 8, z, 'glass_pane')
        b.set(hx2, 8, z, 'glass_pane')
    for x in (10, 11, 18, 19):
        for y in (2, 3, 4, 5):
            b.set(x, y, hz2, 'glass_pane')
    for x in (10, 19):
        for y in (3, 4, 5, 6):
            b.set(x, y, hz1, 'glass_pane')
    # doors: main double door, doorways to the wings
    b.door(14, 1, hz2, 'north', 'left', 'dark_oak')
    b.door(15, 1, hz2, 'north', 'right', 'dark_oak')
    b.set(14, 3, hz2, 'chiseled_quartz_block')
    b.set(15, 3, hz2, 'chiseled_quartz_block')
    for z in (9, 10):
        for y in (1, 2):
            b.set(hx1, y, z, AIR)
            b.set(hx2, y, z, AIR)
    # ---- portico: columns, capitals, entablature
    for x in (9, 11, 13, 16, 18, 20):
        for y in range(1, 8):
            b.set(x, y, 19, 'quartz_pillar[axis=y]')
        b.set(x, 8, 19, 'chiseled_quartz_block')
    for x in (9, 20):
        for y in range(1, 8):
            b.set(x, y, 17, 'quartz_pillar[axis=y]')
        b.set(x, 8, 17, 'chiseled_quartz_block')
    b.fill(hx1, 9, 17, hx2, 9, 19, 'smooth_quartz')
    b.set(12, 7, 18, 'lantern[hanging=false]') if False else None
    for x in (12, 17):
        b.set(x, 8, 18, 'iron_chain[axis=y]')
        b.set(x, 7, 18, 'lantern[hanging=true]')
    # ---- throne room
    b.fill(12, 1, 4, 17, 1, 5, 'smooth_quartz')
    for x in range(12, 18):
        b.set(x, 1, 6, 'smooth_quartz_slab[type=bottom]')
    b.set(14, 2, 4, 'quartz_stairs[facing=north,half=bottom]')
    b.set(15, 2, 4, 'quartz_stairs[facing=north,half=bottom]')
    for x in (14, 15):
        for y in (2, 3, 4, 5):
            b.set(x, y, hz1, 'gold_block')
        b.set(x, 6, hz1, 'chiseled_quartz_block')
    for x in (13, 16):
        b.set(x, 2, 4, 'gold_block')
        b.set(x, 3, 4, 'lantern[hanging=false]')
    for x in (12, 13, 16, 17):
        for y in range(2, 8):
            b.set(x, y, hz1, f'{K}_wool')
    for z in range(7, 16):
        b.set(14, 1, z, 'red_carpet')
        b.set(15, 1, z, 'red_carpet')
        b.set(13, 1, z, f'{K}_carpet')
        b.set(16, 1, z, f'{K}_carpet')
    for z in (7, 10, 13):
        for x in (11, 18):
            for y in range(1, 9):
                b.set(x, y, z, 'quartz_pillar[axis=y]')
            b.set(x, 9, z, 'chiseled_quartz_block')
        b.set(hx1 + 1, 5, z + 1, f'{K}_wall_banner[facing=east]')
        b.set(hx2 - 1, 5, z + 1, f'{K}_wall_banner[facing=west]')
    for (x, z) in ((14, 6), (14, 13)):
        b.set(x, 9, z, 'lantern[hanging=true]')
    for y in range(10, 20):
        b.set(15, y, 10, 'iron_chain[axis=y]')
    b.set(15, 9, 10, 'lantern[hanging=true]')
    b.set(10, 1, 4, 'barrel[facing=up]')
    b.set(19, 1, 4, 'barrel[facing=up]')
    b.set(10, 2, 4, 'lantern[hanging=false]')
    b.set(19, 2, 4, 'lantern[hanging=false]')
    # ---- west wing: royal bedchamber + library
    for x in (3, 6):
        b.bed(x, 1, 6, 'north', 'red')
    b.set(4, 1, 6, 'chest[facing=south]')
    b.set(5, 1, 6, 'chest[facing=south]')
    for z in range(8, 13):
        b.set(2, 1, z, 'bookshelf')
        b.set(2, 2, z, 'bookshelf')
    b.set(5, 1, 10, 'enchanting_table')
    b.set(5, 1, 12, 'lectern[facing=south]')
    for z in (9, 10, 11):
        b.set(4, 1, z, f'{K}_carpet')
        b.set(6, 1, z, f'{K}_carpet')
    b.set(5, 6, 9, 'lantern[hanging=true]')
    b.set(5, 6, 12, 'lantern[hanging=true]')
    # ---- east wing: royal bedchamber + study
    for x in (23, 26):
        b.bed(x, 1, 6, 'north', 'red')
    b.set(24, 1, 6, 'chest[facing=south]')
    b.set(25, 1, 6, 'chest[facing=south]')
    b.set(27, 1, 9, 'loom[facing=west]')
    b.set(27, 1, 10, 'cartography_table')
    b.set(27, 1, 11, 'jukebox')
    b.set(27, 1, 12, 'barrel[facing=up]')
    for z in (9, 10, 11):
        b.set(24, 1, z, f'{K}_carpet')
        b.set(25, 1, z, f'{K}_carpet')
    b.set(24, 6, 9, 'lantern[hanging=true]')
    b.set(24, 6, 12, 'lantern[hanging=true]')
    # ---- garden
    for z in range(20, D):
        for x in range(W):
            b.set(x, 0, z, 'grass_block[snowy=false]')
    for z in range(20, D):
        for x in range(13, 17):
            b.set(x, 0, z, 'polished_andesite')
    for x in range(3, 27):
        b.set(x, 0, 24, 'polished_andesite')
    for z in range(20, D):
        b.set(0, 1, z, 'oak_leaves[persistent=true]')
        b.set(W - 1, 1, z, 'oak_leaves[persistent=true]')
    for x in range(W):
        if not 13 <= x <= 16:
            b.set(x, 1, D - 1, 'oak_leaves[persistent=true]')
    i = 0
    for (bx1, bx2, bz1, bz2) in ((3, 10, 21, 23), (19, 26, 21, 23), (3, 10, 25, 27), (19, 26, 25, 27)):
        for z in range(bz1, bz2 + 1):
            for x in range(bx1, bx2 + 1):
                edge = x in (bx1, bx2) or z in (bz1, bz2)
                if edge:
                    b.set(x, 1, z, 'oak_leaves[persistent=true]' if (x + z) % 3 == 0 else FLOWERS[(x * 3 + z) % len(FLOWERS)])
                else:
                    b.set(x, 1, z, FLOWERS[(x + 2 * z + i) % len(FLOWERS)])
        i += 3
    # fountain
    b.ring(12, 22, 17, 26, 0, 'smooth_quartz')
    b.fill(13, 0, 23, 16, 0, 25, 'water[level=0]')
    b.ring(12, 22, 17, 26, 1, 'smooth_quartz_slab[type=bottom]')
    for x in (14, 15):
        b.set(x, 0, 24, 'quartz_pillar[axis=y]')
        b.set(x, 1, 24, 'quartz_pillar[axis=y]')
        b.set(x, 2, 24, 'quartz_pillar[axis=y]')
    b.set(14, 3, 24, 'lantern[hanging=false]')
    b.set(15, 3, 24, 'lantern[hanging=false]')
    for (x, z) in [(12, 20), (17, 20), (12, 28), (17, 28)]:
        b.set(x, 1, z, 'polished_blackstone_wall')
        b.set(x, 2, z, 'polished_blackstone_wall')
        b.set(x, 3, z, 'lantern[hanging=false]')
    return b
