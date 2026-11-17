# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""More buildings of later epochs: Renaissance (uniwersytet, drukarnia, port, bank, obserwatorium, palac_renesansowy,
ratusz_renesansowy), Industrial (szkola, szpital, most_stalowy, elektrownia, park), Modern (galeria,
szpital_nowoczesny, szkola_nowoczesna, stadion, parlament, dworzec_nowoczesny, parking, laboratorium, lotnisko).
Ids keep the keys Postep scores research by (szkola, uniwersytet, obserwatorium, laboratorium, bank, szpital)."""
from lib import B

AIR = '.'
K = '{kolor}'
FLOWERS = ['poppy', 'cornflower', 'allium', 'azure_bluet', 'oxeye_daisy', 'red_tulip', 'white_tulip', 'lily_of_the_valley']


def stair(mat, facing, half='bottom'):
    return f'{mat}_stairs[facing={facing},half={half}]'


def lamp(b, x, y, z, post='polished_blackstone_wall', h=3, top='lantern[hanging=false]'):
    for k in range(h):
        b.set(x, y + k, z, post)
    b.set(x, y + h, z, top)


def hall(W, D, x1, z1, x2, z2, height, wall, base, corner, window, door_wood, door_x, roof, roof_mat=None,
         floors=(), lights='lantern[hanging=true]', extra_h=6, win_rows=None, double_door=False):
    """Rectangular building shell. roof: 'gable' (stairs of roof_mat), 'flat' (roof_mat block + parapet).
    floors: y levels of inner floor slabs. Returns (b, top_y, tops)."""
    H = height + extra_h + (z2 - z1 + 3) // 2
    b = B(W, H, D)
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, AIR)
    b.fill(x1, 0, z1, x2, 0, z2, base)
    b.walls(x1, z1, x2, z2, 1, height, wall)
    b.ring(x1, z1, x2, z2, 1, base)
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, height + 1):
            b.set(x, y, z, corner)
    for fy in floors:
        b.fill(x1 + 1, fy, z1 + 1, x2 - 1, fy, z2 - 1, base)
    rows = win_rows if win_rows is not None else [2] + [fy + 2 for fy in floors]
    for wy in rows:
        for x in range(x1 + 2, x2 - 1, 2):
            if not (door_x - 1 <= x <= door_x + 1 and wy <= 3):
                b.set(x, wy, z2, window)
            b.set(x, wy, z1, window)
        for z in range(z1 + 2, z2 - 1, 2):
            b.set(x1, wy, z, window)
            b.set(x2, wy, z, window)
    b.door(door_x, 1, z2, 'north', 'left', door_wood)
    if double_door:
        b.door(door_x + 1, 1, z2, 'north', 'right', door_wood)
    b.torch(door_x - 1, 3, z2 + 1, 'south') if z2 + 1 < D else None
    tops = None
    if roof == 'gable':
        tops = b.gable_roof_x(x1 - 1 if x1 > 0 else x1, x2 + 1 if x2 < W - 1 else x2, z1 - 1 if z1 > 0 else z1,
                              z2 + 1 if z2 < D - 1 else z2, height + 1,
                              stair(roof_mat, 'north'), stair(roof_mat, 'south'), roof_mat + 's' if roof_mat.endswith('brick') else
                              (roof_mat + '_planks' if roof_mat in ('oak', 'spruce', 'dark_oak', 'birch') else roof_mat + 's'),
                              f'{roof_mat}_slab[type=bottom]')
        for z in range(z1, z2 + 1):
            for y in range(height + 1, tops.get(z, height + 1)):
                b.set(x1, y, z, wall)
                b.set(x2, y, z, wall)
    else:
        b.fill(x1, height + 1, z1, x2, height + 1, z2, roof_mat)
        for x in range(x1, x2 + 1):
            b.set(x, height + 2, z1, wall)
            b.set(x, height + 2, z2, wall)
        for z in range(z1, z2 + 1):
            b.set(x1, height + 2, z, wall)
            b.set(x2, height + 2, z, wall)
    # lights under every ceiling
    ceilings = [fy for fy in floors] + [height + 1]
    for cy in ceilings:
        for x in range(x1 + 3, x2 - 1, 4):
            for z in range(z1 + 2, z2 - 1, 4):
                if b.get(x, cy, z) not in ('.', '_') and b.get(x, cy - 1, z) == '.':
                    b.set(x, cy - 1, z, lights)
    return b, height + 1, tops


def ladder_up(b, x, z, y1, y2, facing='north'):
    for y in range(y1, y2 + 1):
        b.set(x, y, z, f'ladder[facing={facing}]')


# ================================================================================================ Renaissance
def uniwersytet():
    b, top, tops = hall(19, 13, 0, 0, 18, 11, 7, 'calcite', 'polished_diorite', 'quartz_pillar[axis=y]', 'glass_pane',
                        'dark_oak', 9, 'gable', 'resin_brick', floors=(4,), double_door=False)
    # tower with a lantern over the entrance
    for y in range(top + 1, top + 9):
        b.ring(8, 10, 10, 11, y, 'calcite') if False else None
    for x in range(2, 17, 3):
        b.set(x, 1, 2, 'bookshelf')
        b.set(x, 2, 2, 'bookshelf')
        b.set(x, 1, 6, 'lectern[facing=south,has_book=false,powered=false]')
    for x in range(3, 16, 3):
        b.set(x, 5, 2, 'bookshelf')
        b.set(x, 5, 7, 'enchanting_table') if x == 9 else b.set(x, 5, 7, 'lectern[facing=south,has_book=false,powered=false]')
    ladder_up(b, 17, 1, 1, 4, 'south')
    for x in (8, 10):
        b.set(x, 1, 12, 'quartz_pillar[axis=y]')
        b.set(x, 2, 12, 'quartz_pillar[axis=y]')
        b.set(x, 3, 12, 'quartz_pillar[axis=y]')
    for x in range(7, 12):
        b.set(x, 4, 12, 'smooth_quartz_slab[type=bottom]')
    b.set(9, 3, 12, AIR) if False else None
    return b


def drukarnia():
    b, top, tops = hall(11, 9, 0, 0, 10, 7, 4, 'white_terracotta', 'stone_bricks', 'stripped_oak_log[axis=y]', 'glass_pane',
                        'oak', 5, 'gable', 'resin_brick')
    for x in (2, 4, 7):
        b.set(x, 1, 2, 'loom[facing=south]')
        b.set(x, 1, 4, 'lectern[facing=south,has_book=false,powered=false]')
    b.set(8, 1, 2, 'barrel[facing=up,open=false]')
    b.set(8, 1, 3, 'barrel[facing=up,open=false]')
    b.set(2, 1, 6, 'bookshelf')
    b.set(8, 1, 6, 'cartography_table')
    return b


def port():
    W, D = 15, 17
    g = 2
    b = B(W, 12, D)
    b.fill(0, g + 1, 0, W - 1, 11, D - 1, AIR)
    # quay on land (south part) and a pier on piles over the water (north part)
    for z in range(9, D):
        for x in range(W):
            b.set(x, g, z, 'stone_bricks')
    for z in range(0, 9):
        for x in range(4, 11):
            b.set(x, g, z, 'spruce_planks')
    for (x, z) in [(4, 0), (10, 0), (4, 4), (10, 4), (4, 8), (10, 8)]:
        for y in range(0, g):
            b.set(x, y, z, 'spruce_log[axis=y]')
    for z in range(0, 9):
        b.set(4, g + 1, z, 'spruce_fence')
        b.set(10, g + 1, z, 'spruce_fence')
    for z in (0, 4, 8):
        b.set(4, g + 2, z, 'lantern[hanging=false]')
        b.set(10, g + 2, z, 'lantern[hanging=false]')
    # crane
    for y in range(g + 1, g + 8):
        b.set(12, y, 9, 'spruce_log[axis=y]')
    for x in range(8, 13):
        b.set(x, g + 8, 9, 'spruce_log[axis=x]')
    b.set(8, g + 7, 9, 'iron_chain[axis=y]')
    b.set(8, g + 6, 9, 'iron_chain[axis=y]')
    b.set(8, g + 5, 9, 'barrel[facing=up,open=false]')
    # warehouse shed on the quay
    for y in range(g + 1, g + 5):
        b.ring(1, 11, 7, 15, y, 'white_terracotta')
        for (x, z) in b.corners(1, 11, 7, 15):
            b.set(x, y, z, 'stripped_oak_log[axis=y]')
    b.fill(2, g + 1, 12, 6, g + 4, 14, AIR)
    b.fill(1, g + 5, 11, 7, g + 5, 15, 'resin_bricks')
    b.door(4, g + 1, 15, 'north', 'left', 'oak')
    b.set(4, g + 1, 11, AIR)
    b.set(4, g + 2, 11, AIR)
    for (x, z) in [(2, 12), (3, 12), (6, 12), (6, 13), (2, 14)]:
        b.set(x, g + 1, z, 'barrel[facing=up,open=false]')
    b.set(5, g + 1, 14, 'chest[facing=west,type=single,waterlogged=false]')
    b.set(4, g + 4, 13, 'lantern[hanging=true]')
    b.set(3, g + 3, 16, 'wall_torch[facing=south]') if False else None
    lamp(b, 13, g + 1, 15, 'spruce_fence', 2)
    lamp(b, 9, g + 1, 15, 'spruce_fence', 2)
    return b, g


def bank():
    b, top, tops = hall(13, 12, 0, 0, 12, 9, 6, 'smooth_sandstone', 'polished_andesite', 'quartz_pillar[axis=y]', 'glass_pane',
                        'dark_oak', 6, 'flat', 'smooth_quartz')
    # portico with columns
    for x in (1, 4, 8, 11):
        for y in range(1, 6):
            b.set(x, y, 11, 'quartz_pillar[axis=y]')
    for x in range(0, 13):
        b.set(x, 6, 11, 'smooth_quartz')
        b.set(x, 6, 10, 'smooth_quartz')
    for x in range(0, 13):
        b.set(x, 0, 10, 'polished_andesite')
        b.set(x, 0, 11, 'polished_andesite')
    # counter behind bars and the vault
    for x in range(2, 11):
        b.set(x, 1, 5, 'smooth_quartz')
        b.set(x, 2, 5, 'iron_bars')
    b.set(6, 1, 5, 'dark_oak_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]')
    b.set(6, 2, 5, AIR)
    for x in (2, 3, 9, 10):
        b.set(x, 1, 1, 'gold_block')
    for x in (4, 8):
        b.set(x, 1, 1, 'chest[facing=south,type=single,waterlogged=false]')
    b.set(6, 1, 1, 'barrel[facing=up,open=false]')
    for x in (3, 9):
        b.set(x, 4, 7, f'{K}_wall_banner[facing=north]') if False else None
    return b


def obserwatorium():
    W = D = 9
    b = B(W, 18, D)
    b.fill(0, 1, 0, W - 1, 17, D - 1, AIR)
    b.fill(0, 0, 0, 8, 0, 8, 'polished_andesite')
    for y in range(1, 11):
        b.ring(1, 1, 7, 7, y, 'calcite')
        for (x, z) in b.corners(1, 1, 7, 7):
            b.set(x, y, z, 'polished_diorite')
    b.fill(2, 1, 2, 6, 10, 6, AIR)
    b.door(4, 1, 7, 'north', 'left', 'birch')
    for y in (4, 7):
        b.set(1, y, 4, 'glass_pane')
        b.set(7, y, 4, 'glass_pane')
        b.set(4, y, 1, 'glass_pane')
    ladder_up(b, 4, 2, 1, 10, 'south')
    b.fill(1, 11, 1, 7, 11, 7, 'polished_diorite')
    b.set(4, 11, 2, 'ladder[facing=south]')
    # glass and copper dome with the telescope (lightning rod + end rod)
    b.ring(1, 1, 7, 7, 12, 'waxed_cut_copper')
    b.ring(2, 2, 6, 6, 13, 'light_blue_stained_glass')
    b.ring(3, 3, 5, 5, 14, 'waxed_cut_copper')
    b.set(4, 15, 4, 'waxed_cut_copper')
    b.set(4, 12, 4, 'lodestone')
    b.set(4, 13, 4, 'end_rod[facing=up]')
    b.set(5, 14, 4, AIR)
    b.set(3, 12, 5, 'lantern[hanging=false]')
    b.set(4, 3, 4, 'lantern[hanging=false]') if False else None
    b.set(3, 1, 5, 'cartography_table')
    b.set(5, 1, 5, 'lectern[facing=north,has_book=false,powered=false]')
    b.set(3, 10, 3, 'lantern[hanging=true]')
    b.set(5, 10, 5, 'lantern[hanging=true]')
    b.torch(3, 3, 8, 'south')
    return b


def ratusz_renesansowy():
    b, top, tops = hall(15, 12, 0, 0, 14, 9, 8, 'white_terracotta', 'stone_bricks', 'smooth_sandstone', 'glass_pane',
                        'dark_oak', 7, 'flat', 'resin_bricks', floors=(4,), extra_h=10)
    # attic and the clock tower
    for x in range(0, 15, 2):
        b.set(x, 11, 9, 'smooth_sandstone_slab[type=bottom]')
    for y in range(10, 18):
        b.ring(6, 4, 8, 6, y, 'smooth_sandstone')
    b.fill(7, 10, 5, 7, 17, 5, AIR)
    b.set(7, 14, 6, 'daylight_detector[inverted=false,power=0]') if False else None
    b.set(7, 15, 6, 'white_concrete') if False else None
    b.set(7, 15, 6, 'quartz_block')
    b.set(7, 15, 4, 'quartz_block')
    b.pyramid(5, 3, 9, 7, 18, 'resin_brick', 'resin_bricks', 'lantern[hanging=false]')
    b.set(7, 17, 5, 'bell[attachment=ceiling,facing=north,powered=false]')
    b.set(7, 10, 5, 'smooth_sandstone')
    # council hall and offices
    for x in range(3, 12, 2):
        b.set(x, 1, 3, stair('dark_oak', 'north'))
    b.set(7, 1, 1, 'lectern[facing=south,has_book=false,powered=false]')
    ladder_up(b, 13, 1, 1, 4, 'south')
    for x in (3, 6, 9, 11):
        b.set(x, 5, 2, 'lectern[facing=south,has_book=false,powered=false]')
    b.set(2, 5, 7, 'bookshelf')
    b.set(12, 5, 7, 'bookshelf')
    for x in range(0, 15):
        b.set(x, 0, 10, 'stone_bricks')
    b.set(3, 3, 10, f'{K}_wall_banner[facing=south]')
    b.set(11, 3, 10, f'{K}_wall_banner[facing=south]')
    return b


def palac_renesansowy():
    W, D = 25, 23
    b = B(W, 16, D)
    b.fill(0, 1, 0, W - 1, 15, D - 1, AIR)
    b.fill(0, 0, 0, W - 1, 0, D - 1, 'smooth_stone')
    # four wings around an arcaded courtyard (courtyard x 6..18, z 6..16)
    wings = [(0, 0, 24, 5), (0, 17, 24, 22), (0, 6, 5, 16), (19, 6, 24, 16)]
    for (x1, z1, x2, z2) in wings:
        for y in range(1, 10):
            b.ring(x1, z1, x2, z2, y, 'white_terracotta' if y > 1 else 'stone_bricks')
        b.fill(x1 + 1, 1, z1 + 1, x2 - 1, 9, z2 - 1, AIR)
        b.fill(x1, 5, z1, x2, 5, z2, 'birch_planks')
        b.fill(x1 + 1, 5, z1 + 1, x2 - 1, 5, z2 - 1, 'birch_planks')
        b.fill(x1, 10, z1, x2, 10, z2, 'resin_bricks')
        for (x, z) in b.corners(x1, z1, x2, z2):
            for y in range(1, 11):
                b.set(x, y, z, 'smooth_sandstone')
    # re-ring the outer walls over the floor slabs
    for (x1, z1, x2, z2) in wings:
        b.ring(x1, z1, x2, z2, 5, 'smooth_sandstone')
    # arcades on the courtyard side (ground floor): pillars and arches
    for x in range(6, 19):
        for z in (5, 17):
            b.set(x, 1, z, 'quartz_pillar[axis=y]' if x % 3 == 0 else AIR)
            b.set(x, 2, z, 'quartz_pillar[axis=y]' if x % 3 == 0 else AIR)
            b.set(x, 3, z, 'quartz_pillar[axis=y]' if x % 3 == 0 else stair('smooth_quartz', 'east' if x % 3 == 1 else 'west', 'top'))
    for z in range(6, 17):
        for x in (5, 19):
            b.set(x, 1, z, 'quartz_pillar[axis=y]' if z % 3 == 0 else AIR)
            b.set(x, 2, z, 'quartz_pillar[axis=y]' if z % 3 == 0 else AIR)
            b.set(x, 3, z, 'quartz_pillar[axis=y]' if z % 3 == 0 else stair('smooth_quartz', 'south' if z % 3 == 1 else 'north', 'top'))
    # courtyard: garden and a fountain
    for x in range(6, 19):
        for z in range(6, 17):
            b.set(x, 0, z, 'grass_block[snowy=false]' if (x + z) % 5 else 'polished_andesite')
    b.ring(10, 9, 14, 13, 1, 'smooth_quartz_slab[type=bottom]')
    b.fill(11, 0, 10, 13, 0, 12, 'water[level=0]')
    b.set(12, 1, 11, 'quartz_pillar[axis=y]')
    b.set(12, 2, 11, 'lantern[hanging=false]')
    for (x, z) in [(7, 7), (17, 7), (7, 15), (17, 15)]:
        b.set(x, 1, z, FLOWERS[(x + z) % len(FLOWERS)])
    # upper floor windows, entrance, rooms
    for (x1, z1, x2, z2) in wings:
        for x in range(x1 + 2, x2 - 1, 3):
            b.set(x, 7, z1, 'glass_pane')
            b.set(x, 7, z2, 'glass_pane')
        for z in range(z1 + 2, z2 - 1, 3):
            b.set(x1, 7, z, 'glass_pane')
            b.set(x2, 7, z, 'glass_pane')
    b.door(12, 1, 22, 'north', 'left', 'dark_oak')
    b.set(12, 1, 17, AIR)
    b.set(12, 2, 17, AIR)
    b.set(12, 3, 17, 'smooth_quartz')
    for x in (10, 14):
        b.set(x, 3, 23 - 1, 'lantern[hanging=false]') if False else None
    b.torch(11, 3, 22 + 1, 'south') if 23 < D else None
    for (x, z, f) in [(3, 2, 'north'), (21, 2, 'north'), (3, 20, 'north'), (21, 20, 'north')]:
        b.bed(x, 6, z, f, 'red') if z == 2 else b.bed(x, 6, z - 1, f, 'red')
    ladder_up(b, 1, 1, 1, 5, 'south')
    ladder_up(b, 23, 1, 1, 5, 'south')
    for (x, z) in [(3, 3), (21, 3), (3, 19), (21, 19), (12, 3), (12, 19)]:
        b.set(x, 4, z, 'lantern[hanging=true]')
        b.set(x, 9, z, 'lantern[hanging=true]')
    b.set(12, 1, 2, 'gold_block')
    b.set(12, 2, 2, stair('quartz', 'north'))
    for x in (10, 14):
        b.set(x, 2, 1, f'{K}_wall_banner[facing=south]')
    # roof lanterns on the corners
    for (x, z) in [(0, 0), (24, 0), (0, 22), (24, 22)]:
        b.set(x, 11, z, 'lantern[hanging=false]')
    return b


# ================================================================================================ Industrial
def szkola():
    b, top, tops = hall(15, 10, 0, 0, 14, 8, 7, 'bricks', 'stone_bricks', 'stone_bricks', 'glass_pane', 'dark_oak', 7,
                        'gable', 'deepslate_tile', floors=(4,))
    for y0 in (1, 5):
        for x in range(2, 13, 2):
            b.set(x, y0, 4, stair('spruce', 'north'))
            b.set(x, y0, 5, 'spruce_slab[type=top]') if False else None
        b.set(7, y0, 1, 'lectern[facing=south,has_book=false,powered=false]')
        b.set(3, y0 + 1, 1, 'black_wool') if False else None
    for x in range(4, 11):
        b.set(x, 2, 1, 'black_concrete') if x % 2 == 0 else None
    ladder_up(b, 13, 1, 1, 4, 'south')
    b.set(13, 1, 7, 'bell[attachment=floor,facing=north,powered=false]')
    return b


def szpital():
    b, top, tops = hall(17, 11, 0, 0, 16, 9, 8, 'white_terracotta', 'stone_bricks', 'bricks', 'glass_pane', 'dark_oak', 8,
                        'flat', 'stone_bricks', floors=(4,))
    beds = 0
    for y0 in (1, 5):
        for x in (2, 5, 11, 14):
            b.bed(x, y0, 1, 'north', 'white')
            beds += 1
        b.set(8, y0, 2, 'brewing_stand') if y0 == 1 else b.set(8, y0, 2, 'cauldron')
    for x in range(6, 11):
        b.set(x, 8, 9, 'red_concrete') if x == 8 else None
    b.set(8, 7, 9, 'red_concrete')
    b.set(7, 8, 9, 'red_concrete')
    b.set(9, 8, 9, 'red_concrete')
    b.set(8, 9, 9, 'red_concrete')
    ladder_up(b, 15, 1, 1, 4, 'south')
    return b, beds


def most_stalowy():
    W, D = 7, 25
    g = 3
    b = B(W, 12, D)
    b.fill(0, g + 1, 0, W - 1, 11, D - 1, AIR)
    for z in range(D):
        for x in range(0, 7):
            b.set(x, g, z, 'iron_block' if x in (0, 6) else 'smooth_stone')
    for pz in (6, 18):
        for x in range(1, 6):
            for y in range(0, g):
                b.set(x, y, pz, 'polished_blackstone_bricks')
    # truss: posts and diagonals of iron bars, top chords
    for z in range(D):
        for x in (0, 6):
            b.set(x, g + 1, z, 'iron_bars')
            if z % 4 == 0:
                for y in range(g + 2, g + 6):
                    b.set(x, y, z, 'iron_bars')
            b.set(x, g + 5, z, 'iron_block' if z % 4 == 0 else 'iron_bars')
    for z in range(2, D - 2, 4):
        for x in (0, 6):
            b.set(x, g + 3, z, 'iron_bars')
    for z in (0, 12, 24):
        b.set(0, g + 6, z, 'lantern[hanging=false]')
        b.set(6, g + 6, z, 'lantern[hanging=false]')
    for z in range(D):
        if z % 8 == 4:
            b.set(3, g + 5, z, 'iron_chain[axis=x]') if False else None
    return b, g


def elektrownia():
    b, top, tops = hall(19, 14, 0, 2, 18, 12, 8, 'bricks', 'stone_bricks', 'stone_bricks', 'glass_pane', 'iron', 9,
                        'flat', 'deepslate_tiles', extra_h=14)
    # the iron door needs a button outside and inside
    b.set(10, 2, 13, 'stone_button[face=wall,facing=south,powered=false]')
    b.set(8, 2, 11, 'stone_button[face=wall,facing=north,powered=false]')
    for x in range(2, 17, 3):
        b.set(x, 1, 5, 'blast_furnace[facing=south,lit=true]')
        b.set(x, 2, 5, 'iron_block')
        b.set(x, 3, 5, 'lightning_rod[facing=up,powered=false,waterlogged=false]')
    for x in range(2, 17, 4):
        b.set(x, 1, 9, 'waxed_copper_block')
        b.set(x, 2, 9, 'waxed_copper_bulb[lit=true,powered=false]') if False else b.set(x, 2, 9, 'iron_block')
    for cx in (3, 14):
        for y in range(1, 24):
            for dx in (0, 1):
                for cz in (0, 1):
                    b.set(cx + dx, y, cz, 'stone_bricks' if y % 6 == 0 else 'bricks')
        for dx in (0, 1):
            for cz in (0, 1):
                b.set(cx + dx, 0, cz, 'stone_bricks')
                b.set(cx + dx, 24, cz, 'campfire[lit=true,signal_fire=true,facing=south,waterlogged=false]')
    return b


def park():
    W = D = 17
    b = B(W, 8, D)
    b.fill(0, 1, 0, W - 1, 7, D - 1, AIR)
    b.fill(0, 0, 0, W - 1, 0, D - 1, 'grass_block[snowy=false]')
    for i in range(W):
        b.set(i, 0, 8, 'gravel')
        b.set(8, 0, i, 'gravel')
    b.ring(1, 1, 15, 15, 0, 'gravel')
    for i in range(W):
        for (x, z) in [(i, 0), (i, D - 1), (0, i), (W - 1, i)]:
            if 7 <= i <= 9:
                continue
            b.set(x, 1, z, 'iron_bars')
    for (x, z) in [(4, 4), (12, 4), (4, 12), (12, 12)]:
        for y in (1, 2, 3):
            b.set(x, y, z, 'oak_log[axis=y]')
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                b.set(x + dx, 4, z + dz, 'oak_leaves[persistent=true]')
        b.set(x, 5, z, 'oak_leaves[persistent=true]')
    b.ring(6, 6, 10, 10, 0, 'smooth_stone')
    b.fill(7, 0, 7, 9, 0, 9, 'water[level=0]')
    b.ring(6, 6, 10, 10, 1, 'smooth_stone_slab[type=bottom]')
    b.set(8, 0, 8, 'smooth_stone')
    b.set(8, 1, 8, 'stone_brick_wall')
    b.set(8, 2, 8, 'lantern[hanging=false]')
    for (x, z, f) in [(2, 7, 'west'), (2, 9, 'west'), (14, 7, 'east'), (14, 9, 'east')]:
        b.set(x, 1, z, stair('dark_oak', f))
    for (x, z) in [(1, 1), (15, 1), (1, 15), (15, 15), (7, 2), (9, 14)]:
        lamp(b, x, 1, z, 'polished_blackstone_wall', 3)
    for (x, z) in [(3, 2), (13, 2), (3, 14), (13, 14), (2, 3), (14, 13)]:
        b.set(x, 1, z, FLOWERS[(x * 3 + z) % len(FLOWERS)])
    return b


# ================================================================================================ Modern
def galeria():
    b, top, tops = hall(23, 17, 0, 0, 22, 15, 10, 'white_concrete', 'smooth_stone', 'light_gray_concrete',
                        'light_blue_stained_glass_pane', 'waxed_copper', 11, 'flat', 'gray_concrete', floors=(5,),
                        lights='sea_lantern', win_rows=[2, 3, 7, 8])
    # glass front and shops
    for x in range(2, 21):
        for y in (2, 3, 4):
            if not (10 <= x <= 12 and y <= 2):
                b.set(x, y, 15, 'light_blue_stained_glass_pane')
    for (x1, x2) in [(2, 6), (8, 10), (13, 15), (17, 20)]:
        for z in (2, 3):
            for x in range(x1, x2 + 1):
                b.set(x, 1, z, 'barrel[facing=up,open=false]' if (x + z) % 3 == 0 else 'smooth_quartz_slab[type=top]')
        b.set(x1, 4, 2, f'{K}_wool') if False else None
    for x in range(3, 20, 4):
        b.set(x, 6, 3, 'barrel[facing=up,open=false]')
        b.set(x, 6, 4, 'smooth_quartz_slab[type=top]')
    ladder_up(b, 21, 1, 1, 5, 'south')
    b.set(11, 11, 8, 'sea_lantern')
    for x in range(1, 22, 5):
        b.set(x, 12, 1, 'lightning_rod[facing=up,powered=false,waterlogged=false]') if x in (1, 21) else None
    return b


def szpital_nowoczesny():
    b, top, tops = hall(17, 13, 0, 0, 16, 11, 11, 'white_concrete', 'smooth_stone', 'light_gray_concrete',
                        'light_blue_stained_glass_pane', 'birch', 8, 'flat', 'light_gray_concrete', floors=(4, 8),
                        lights='sea_lantern')
    beds = 0
    for y0 in (5, 9):
        for x in (2, 5, 11, 14):
            b.bed(x, y0, 1, 'north', 'white')
            beds += 1
    b.set(8, 1, 3, 'brewing_stand')
    b.set(4, 1, 3, 'cauldron')
    b.set(12, 1, 3, 'smooth_quartz_slab[type=top]')
    ladder_up(b, 15, 1, 1, 11, 'south')
    for (x, y) in [(8, 9), (7, 10), (8, 10), (9, 10), (8, 11)]:
        b.set(x, y, 11, 'red_concrete')
    for (x, z) in [(6, 4), (7, 4), (8, 4), (9, 4), (10, 4), (8, 3), (8, 5), (8, 6), (8, 7)]:
        b.set(x, 12, z, 'red_concrete')
    return b, beds


def szkola_nowoczesna():
    b, top, tops = hall(19, 11, 0, 0, 18, 9, 8, 'light_gray_concrete', 'smooth_stone', 'white_concrete',
                        'black_stained_glass_pane', 'birch', 9, 'flat', 'gray_concrete', floors=(4,), lights='sea_lantern')
    for y0 in (1, 5):
        for x in range(2, 17, 2):
            b.set(x, y0, 5, stair('birch', 'north'))
        b.set(9, y0, 1, 'lectern[facing=south,has_book=false,powered=false]')
    ladder_up(b, 17, 1, 1, 4, 'south')
    return b


def stadion():
    W, D = 31, 23
    b = B(W, 10, D)
    b.fill(0, 1, 0, W - 1, 9, D - 1, AIR)
    b.fill(0, 0, 0, W - 1, 0, D - 1, 'light_gray_concrete')
    px1, pz1, px2, pz2 = 5, 5, 25, 17
    b.fill(px1, 0, pz1, px2, 0, pz2, 'grass_block[snowy=false]')
    b.ring(px1 + 1, pz1 + 1, px2 - 1, pz2 - 1, 0, 'white_concrete')
    for z in range(pz1 + 1, pz2):
        b.set(15, 0, z, 'white_concrete')
    for (gx, f) in [(px1 + 1, 'east'), (px2 - 1, 'west')]:
        for z in (10, 12):
            b.set(gx, 1, z, 'iron_bars')
            b.set(gx, 2, z, 'iron_bars')
        b.set(gx, 3, 10, 'white_concrete')
        b.set(gx, 3, 11, 'white_concrete')
        b.set(gx, 3, 12, 'white_concrete')
    for k in range(1, 4):
        x1, z1, x2, z2 = px1 - k, pz1 - k, px2 + k, pz2 + k
        y = k
        for x in range(x1 + 1, x2):
            b.set(x, y, z1, stair('smooth_quartz', 'north'))
            b.set(x, y, z2, stair('smooth_quartz', 'south'))
            for yy in range(1, y):
                b.set(x, yy, z1, 'white_concrete')
                b.set(x, yy, z2, 'white_concrete')
        for z in range(z1 + 1, z2):
            b.set(x1, y, z, stair('smooth_quartz', 'west'))
            b.set(x2, y, z, stair('smooth_quartz', 'east'))
            for yy in range(1, y):
                b.set(x1, yy, z, 'white_concrete')
                b.set(x2, yy, z, 'white_concrete')
        for (x, z) in b.corners(x1, z1, x2, z2):
            for yy in range(1, y + 1):
                b.set(x, yy, z, 'white_concrete')
    b.walls(0, 0, W - 1, D - 1, 1, 5, 'light_gray_concrete')
    for z in range(0, D):
        if 9 <= z <= 13:
            for y in (1, 2, 3):
                b.set(0, y, z, AIR)
                b.set(W - 1, y, z, AIR)
    for z in range(1, 4):
        for y in (1, 2):
            b.set(15, y, z, AIR) if False else None
    for (x, z) in [(0, 0), (W - 1, 0), (0, D - 1), (W - 1, D - 1)]:
        for y in range(6, 9):
            b.set(x, y, z, 'iron_bars')
        b.set(x, 9, z, 'sea_lantern')
    for x in range(2, W - 2, 6):
        b.set(x, 6, 0, f'{K}_wool')
        b.set(x, 6, D - 1, f'{K}_wool')
    return b


def parlament():
    W, D = 25, 17
    b = B(W, 22, D)
    b.fill(0, 1, 0, W - 1, 21, D - 1, AIR)
    b.fill(0, 0, 0, W - 1, 0, D - 1, 'smooth_stone')
    x1, z1, x2, z2 = 1, 1, 23, 13
    b.walls(x1, z1, x2, z2, 1, 9, 'smooth_quartz')
    b.ring(x1, z1, x2, z2, 1, 'polished_andesite')
    for x in range(x1, x2 + 1, 3):
        for y in range(1, 10):
            b.set(x, y, z2, 'quartz_pillar[axis=y]')
            b.set(x, y, z1, 'quartz_pillar[axis=y]')
    for x in range(x1 + 1, x2):
        if x % 3:
            for y in (3, 4, 5, 6, 7):
                b.set(x, y, z2, 'light_blue_stained_glass_pane')
                b.set(x, y, z1, 'light_blue_stained_glass_pane')
    b.fill(x1, 10, z1, x2, 10, z2, 'smooth_quartz')
    b.door(12, 1, z2, 'north', 'left', 'dark_oak')
    b.set(12, 3, z2, 'chiseled_quartz_block')
    for x in range(0, W):
        b.set(x, 0, 14, 'polished_andesite')
        b.set(x, 0, 15, 'polished_andesite')
    # glass dome over the chamber
    cx, cz = 12, 7
    for y in range(11, 18):
        r = max(0.0, (6.5 ** 2 - (y - 11) ** 2)) ** 0.5
        for x in range(x1, x2 + 1):
            for z in range(z1, z2 + 1):
                d = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
                if r - 1.1 < d <= r:
                    b.set(x, y, z, 'light_gray_stained_glass' if (x + y + z) % 3 else 'smooth_quartz')
    b.set(cx, 18, cz, 'smooth_quartz')
    b.set(cx, 19, cz, 'iron_bars')
    b.set(cx, 20, cz, 'iron_bars')
    b.set(cx, 21, cz, f'{K}_wool')
    # chamber: rows of seats in a half circle, the speaker's desk
    for r in (3, 4, 5):
        for x in range(cx - r, cx + r + 1):
            z = cz + r
            if z < z2:
                b.set(x, 1, z, stair('dark_oak', 'south'))
    b.set(cx, 1, cz - 2, 'lectern[facing=south,has_book=false,powered=false]')
    b.set(cx, 1, cz - 3, 'gold_block')
    for (x, z) in [(4, 4), (20, 4), (4, 10), (20, 10), (12, 4)]:
        b.set(x, 9, z, 'sea_lantern')
    for x in (6, 18):
        b.set(x, 5, z2 - 1, f'{K}_wall_banner[facing=north]')
    return b


def dworzec_nowoczesny():
    W, D = 25, 15
    b = B(W, 12, D)
    b.fill(0, 1, 0, W - 1, 11, D - 1, AIR)
    for x in range(W):
        b.set(x, 0, 0, 'gravel')
        b.set(x, 0, 1, 'gravel')
        b.set(x, 1, 1, 'powered_rail[powered=false,shape=east_west,waterlogged=false]' if x % 6 == 0 else 'rail[shape=east_west,waterlogged=false]')
        b.set(x, 0, 2, 'smooth_stone')
        for z in (3, 4):
            b.set(x, 0, z, 'light_gray_concrete')
            b.set(x, 1, z, 'smooth_stone_slab[type=bottom]')
    for x in range(1, W - 1, 4):
        for y in (2, 3, 4, 5):
            b.set(x, y, 4, 'iron_bars')
    for x in range(W):
        for z in (2, 3, 4):
            b.set(x, 6, z, 'light_blue_stained_glass')
    for x in range(3, W - 2, 6):
        b.set(x, 5, 3, 'sea_lantern') if False else None
    x1, z1, x2, z2 = 3, 5, 21, 12
    b.fill(x1, 0, z1, x2, 0, z2, 'smooth_quartz')
    b.walls(x1, z1, x2, z2, 1, 7, 'light_blue_stained_glass')
    for x in range(x1, x2 + 1, 3):
        for y in range(1, 8):
            b.set(x, y, z1, 'light_gray_concrete')
            b.set(x, y, z2, 'light_gray_concrete')
    for z in (z1, z2):
        for x in (x1, x2):
            for y in range(1, 8):
                b.set(x, y, z, 'light_gray_concrete')
    b.fill(x1, 8, z1, x2, 8, z2, 'white_concrete')
    for x in range(x1 + 2, x2 - 1, 4):
        b.set(x, 8, (z1 + z2) // 2, 'sea_lantern')
    b.door(12, 1, z2, 'north', 'left', 'waxed_copper')
    b.door(12, 1, z1, 'south', 'left', 'waxed_copper')
    for x in (6, 7, 17, 18):
        b.set(x, 1, 8, stair('birch', 'south'))
        b.set(x, 1, 10, stair('birch', 'north'))
    b.set(20, 1, 6, 'lectern[facing=west,has_book=false,powered=false]')
    for x in range(0, W):
        b.set(x, 0, 13, 'smooth_stone')
        b.set(x, 0, 14, 'smooth_stone')
    for x in (0, 24):
        lamp(b, x, 1, 13, 'andesite_wall', 3, 'sea_lantern')
    return b


def parking():
    W = D = 15
    b = B(W, 6, D)
    b.fill(0, 1, 0, W - 1, 5, D - 1, AIR)
    b.fill(0, 0, 0, W - 1, 0, D - 1, 'black_concrete')
    for z in (0, 6, 7, 8, 14):
        pass
    for x in range(0, W, 3):
        for z in range(0, 5):
            b.set(x, 0, z, 'white_concrete')
        for z in range(10, D):
            b.set(x, 0, z, 'white_concrete')
    for x in range(1, W, 3):
        b.set(x, 0, 7, 'white_concrete') if x % 2 else None
    for (x, z) in [(0, 7), (14, 7), (7, 0), (7, 14)]:
        lamp(b, x, 1, z, 'andesite_wall', 3, 'sea_lantern')
    for x in range(W):
        if x not in (6, 7, 8):
            b.set(x, 1, D - 1, 'smooth_stone_slab[type=bottom]')
    b.set(7, 1, 7, 'iron_bars') if False else None
    return b


def laboratorium():
    b, top, tops = hall(15, 11, 0, 0, 14, 9, 8, 'white_concrete', 'smooth_stone', 'light_gray_concrete',
                        'light_blue_stained_glass_pane', 'birch', 7, 'flat', 'white_concrete', floors=(4,), lights='sea_lantern')
    for y0 in (1, 5):
        for x in range(2, 13, 2):
            b.set(x, y0, 2, 'brewing_stand' if x % 4 == 0 else 'smooth_quartz_slab[type=top]')
            b.set(x, y0, 6, 'enchanting_table' if x == 6 and y0 == 5 else 'smooth_quartz_slab[type=top]')
        b.set(1, y0, 7, 'barrel[facing=up,open=false]')
    ladder_up(b, 13, 1, 1, 4, 'south')
    b.set(3, 10, 3, 'lightning_rod[facing=up,powered=false,waterlogged=false]')
    b.set(11, 10, 3, 'daylight_detector[inverted=false,power=0]')
    return b


def lotnisko():
    W, D = 41, 17
    b = B(W, 12, D)
    b.fill(0, 1, 0, W - 1, 11, D - 1, AIR)
    # runway: asphalt with a white centre line and threshold stripes, edge lights
    for x in range(W):
        for z in range(0, 7):
            b.set(x, 0, z, 'black_concrete')
        b.set(x, 0, 3, 'white_concrete' if x % 4 < 2 else 'black_concrete')
    for x in (1, 2, W - 3, W - 2):
        for z in (1, 2, 4, 5):
            b.set(x, 0, z, 'white_concrete')
    for x in range(0, W, 5):
        b.set(x, 1, 0, 'end_rod[facing=up]')
        b.set(x, 1, 6, 'end_rod[facing=up]')
    # apron and terminal
    for x in range(W):
        for z in range(7, D):
            b.set(x, 0, z, 'light_gray_concrete')
    x1, z1, x2, z2 = 4, 9, 22, 15
    b.walls(x1, z1, x2, z2, 1, 5, 'light_blue_stained_glass')
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, 6):
            b.set(x, y, z, 'white_concrete')
    for x in range(x1, x2 + 1, 3):
        for y in range(1, 6):
            b.set(x, y, z2, 'white_concrete')
            b.set(x, y, z1, 'white_concrete')
    b.fill(x1, 6, z1, x2, 6, z2, 'white_concrete')
    b.fill(x1 + 1, 0, z1 + 1, x2 - 1, 0, z2 - 1, 'smooth_quartz')
    b.door(13, 1, z2, 'north', 'left', 'waxed_copper')
    b.door(13, 1, z1, 'south', 'left', 'waxed_copper')
    for x in range(x1 + 3, x2 - 1, 4):
        b.set(x, 6, 12, 'sea_lantern')
    for x in (8, 9, 17, 18):
        b.set(x, 1, 12, stair('birch', 'south'))
    # control tower
    for y in range(1, 9):
        b.ring(27, 10, 29, 12, y, 'white_concrete')
    b.fill(28, 1, 11, 28, 8, 11, AIR)
    ladder_up(b, 28, 11, 1, 9, 'north') if False else None
    for y in range(1, 9):
        b.set(28, y, 11, 'ladder[facing=north]')
    b.fill(26, 8, 9, 30, 8, 13, 'white_concrete')
    b.set(28, 8, 11, 'ladder[facing=north]')
    b.ring(26, 9, 30, 13, 9, 'light_blue_stained_glass')
    b.fill(27, 9, 10, 29, 9, 12, AIR)
    b.fill(26, 10, 9, 30, 10, 13, 'white_concrete')
    b.set(28, 11, 11, 'lightning_rod[facing=up,powered=false,waterlogged=false]')
    b.set(27, 9, 10, 'sea_lantern') if False else None
    b.set(29, 10, 12, 'sea_lantern')
    b.door(29, 1, 11, 'west', 'left', 'birch')
    # windsock pole
    for y in range(1, 5):
        b.set(35, y, 10, 'iron_bars')
    b.set(35, 5, 10, 'orange_wool')
    return b
