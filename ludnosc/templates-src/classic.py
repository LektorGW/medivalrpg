# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
# The seven original Ludnosc buildings (chata, dom, spichlerz, koszary, kuznia, studnia, wieza).
from lib import B

# ------------------------------------------------------------------------------------------- chata (2 beds)
def chata():
    W, H, D = 9, 8, 8
    b = B(W, H, D)
    C = b.p('C', 'cobblestone')
    P = b.p('P', 'spruce_planks')
    O = b.p('O', 'oak_planks')
    L = b.p('L', 'spruce_log[axis=y]')
    X = b.p('X', 'spruce_log[axis=x]')
    Z = b.p('Z', 'spruce_log[axis=z]')
    G = b.p('G', 'glass_pane')
    sN = b.p('n', 'dark_oak_stairs[facing=north,half=bottom]')
    sS = b.p('s', 'dark_oak_stairs[facing=south,half=bottom]')
    x1, x2, z1, z2 = 1, 7, 1, 6
    # interior air everywhere inside the bounding box above the floor first
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, '.')
    # floor
    b.fill(x1, 0, z1, x2, 0, z2, P)
    b.ring(x1, z1, x2, z2, 0, C)
    # walls y1..y3
    for y in range(1, 4):
        b.ring(x1, z1, x2, z2, y, O)
        for (x, z) in [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]:
            b.set(x, y, z, L)
    b.ring(x1, z1, x2, z2, 1, C)  # stone base course
    for (x, z) in [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]:
        b.set(x, 1, z, L)
    # top beam ring y4
    for x in range(x1, x2 + 1):
        b.set(x, 4, z1, X); b.set(x, 4, z2, X)
    for z in range(z1, z2 + 1):
        b.set(x1, 4, z, Z); b.set(x2, 4, z, Z)
    for (x, z) in [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]:
        b.set(x, 4, z, L)
    # roof (ridge along x), eaves at z0/z7 on y4
    tops = b.gable_roof_x(0, W - 1, 0, D - 1, 4, sN, sS)
    # gable ends x1/x2: fill planks under the roof
    for z in range(z1, z2 + 1):
        for y in range(5, tops[z]):
            b.set(x1, y, z, P); b.set(x2, y, z, P)
    # windows
    for z in (3, 4):
        b.set(x1, 2, z, G); b.set(x2, 2, z, G)
    for x in (3, 5):
        b.set(x, 2, z1, G)
    for x in (2, 6):
        b.set(x, 2, z2, G)
    # door (front, south wall), opens inwards
    b.set(4, 1, z2, b.p('D', 'spruce_door[facing=north,half=lower,hinge=left,open=false]'))
    b.set(4, 2, z2, b.p('d', 'spruce_door[facing=north,half=upper,hinge=left,open=false]'))
    # outside torches next to the door
    T = b.p('T', 'wall_torch[facing=south]')
    b.set(3, 2, 7, T); b.set(5, 2, 7, T)
    # beds: heads against the back wall, facing north (foot -> head)
    bh = b.p('H', 'red_bed[facing=north,part=head]')
    bf = b.p('F', 'red_bed[facing=north,part=foot]')
    for x in (2, 6):
        b.set(x, 1, 2, bh); b.set(x, 1, 3, bf)
    b.set(3, 1, 2, b.p('K', 'chest[facing=south]'))
    b.set(4, 1, 2, b.p('W', 'crafting_table'))
    b.set(5, 1, 2, b.p('R', 'barrel[facing=up]'))
    b.set(2, 1, 5, b.p('U', 'furnace[facing=east]'))
    b.set(6, 1, 5, b.p('M', 'composter'))
    b.set(5, 2, 2, b.p('f', 'potted_red_tulip'))
    # light
    b.set(4, 6, 3, b.p('l', 'lantern[hanging=true]'))
    b.set(4, 6, 4, b.p('l', 'lantern[hanging=true]'))
    b.set(4, 1, 4, b.p('c', 'brown_carpet'))
    return b

# ------------------------------------------------------------------------------------------- dom (4 beds)
def dom():
    W, H, D = 11, 14, 9
    b = B(W, H, D)
    C = b.p('C', 'cobblestone')
    S = b.p('S', 'stone_bricks')
    P = b.p('P', 'spruce_planks')
    K = b.p('k', 'dark_oak_planks')
    L = b.p('L', 'dark_oak_log[axis=y]')
    X = b.p('X', 'dark_oak_log[axis=x]')
    Z = b.p('Z', 'dark_oak_log[axis=z]')
    T = b.p('T', 'white_terracotta')
    G = b.p('G', 'glass_pane')
    sN = b.p('n', 'dark_oak_stairs[facing=north,half=bottom]')
    sS = b.p('s', 'dark_oak_stairs[facing=south,half=bottom]')
    sl = b.p('h', 'dark_oak_slab[type=bottom]')
    x1, x2, z1, z2 = 1, 9, 1, 7
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, '.')
    b.fill(x1, 0, z1, x2, 0, z2, P)
    b.ring(x1, z1, x2, z2, 0, C)
    corners = [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]
    # ground storey (stone)
    for y in range(1, 4):
        b.ring(x1, z1, x2, z2, y, C)
        for (x, z) in corners:
            b.set(x, y, z, S)
    # upper floor y4 (beam ring + planks)
    b.fill(x1, 4, z1, x2, 4, z2, P)
    for x in range(x1, x2 + 1):
        b.set(x, 4, z1, X); b.set(x, 4, z2, X)
    for z in range(z1, z2 + 1):
        b.set(x1, 4, z, Z); b.set(x2, 4, z, Z)
    for (x, z) in corners:
        b.set(x, 4, z, L)
    # upper storey half-timber y5..y7
    for y in range(5, 8):
        b.ring(x1, z1, x2, z2, y, T)
        for (x, z) in corners + [(5, z1), (5, z2), (x1, 4), (x2, 4)]:
            b.set(x, y, z, L)
    # top beam y8
    for x in range(x1, x2 + 1):
        b.set(x, 8, z1, X); b.set(x, 8, z2, X)
    for z in range(z1, z2 + 1):
        b.set(x1, 8, z, Z); b.set(x2, 8, z, Z)
    for (x, z) in corners:
        b.set(x, 8, z, L)
    # roof, eaves z0/z8 at y8, ridge at z4
    tops = b.gable_roof_x(0, W - 1, 0, D - 1, 8, sN, sS, ridge_full=K, ridge_slab=sl)
    for z in range(z1, z2 + 1):
        for y in range(9, tops[z]):
            b.set(x1, y, z, P); b.set(x2, y, z, P)
    b.set(x1, 10, 4, G); b.set(x2, 10, 4, G)
    # ground floor windows
    for x in (2, 3, 7, 8):
        b.set(x, 2, z2, G)
    for x in (3, 7):
        b.set(x, 2, z1, G)
    for z in (3, 5):
        b.set(x1, 2, z, G); b.set(x2, 2, z, G)
    # upper windows
    for x in (3, 7):
        b.set(x, 6, z1, G); b.set(x, 6, z2, G)
    b.set(x1, 6, 3, G); b.set(x2, 6, 5, G)
    # door
    b.set(5, 1, z2, b.p('D', 'spruce_door[facing=north,half=lower,hinge=left,open=false]'))
    b.set(5, 2, z2, b.p('d', 'spruce_door[facing=north,half=upper,hinge=left,open=false]'))
    b.set(5, 3, z2, S)
    wt = b.p('t', 'wall_torch[facing=south]')
    b.set(4, 2, 8, wt); b.set(6, 2, 8, wt)
    # ground floor furniture
    b.set(2, 1, 2, b.p('U', 'smoker[facing=south]'))
    b.set(3, 1, 2, b.p('W', 'crafting_table'))
    b.set(4, 1, 2, b.p('R', 'barrel[facing=up]'))
    b.set(6, 1, 2, b.p('R', 'barrel[facing=up]'))
    b.set(7, 1, 4, b.p('E', 'spruce_fence'))
    b.set(7, 2, 4, b.p('e', 'spruce_pressure_plate'))
    b.set(6, 1, 4, b.p('w', 'spruce_stairs[facing=west,half=bottom]'))
    b.set(8, 1, 4, b.p('q', 'spruce_stairs[facing=east,half=bottom]'))
    b.set(2, 1, 5, b.p('c', 'red_carpet'))
    b.set(3, 1, 5, b.p('c', 'red_carpet'))
    lh = b.p('l', 'lantern[hanging=true]')
    b.set(4, 3, 4, lh)
    # ladder on the inside of the east wall (x9) at z6, through the floor
    lad = b.p('A', 'ladder[facing=west]')
    for y in range(1, 5):
        b.set(8, y, 6, lad)
    # upper floor: 4 beds against the back wall
    bh = b.p('H', 'white_bed[facing=north,part=head]')
    bf = b.p('F', 'white_bed[facing=north,part=foot]')
    for x in (2, 3, 7, 8):
        b.set(x, 5, 2, bh); b.set(x, 5, 3, bf)
    b.set(5, 5, 2, b.p('Q', 'chest[facing=south]'))
    b.set(5, 6, 2, b.p('j', 'lantern[hanging=false]'))
    b.set(4, 5, 2, b.p('V', 'lectern[facing=south]'))
    b.set(6, 5, 2, b.p('f', 'potted_blue_orchid'))
    b.set(5, 11, 4, lh)  # hangs from the ridge
    # guard rail around the ladder hole
    b.set(7, 5, 6, b.p('E', 'spruce_fence'))
    return b

# ------------------------------------------------------------------------------------------- spichlerz (granary)
def spichlerz():
    W, H, D = 10, 11, 11
    b = B(W, H, D)
    C = b.p('C', 'cobblestone')
    P = b.p('P', 'spruce_planks')
    L = b.p('L', 'spruce_log[axis=y]')
    X = b.p('X', 'spruce_log[axis=x]')
    Z = b.p('Z', 'spruce_log[axis=z]')
    sE = b.p('e', 'spruce_stairs[facing=west,half=bottom]')   # east slope rises to the west
    sW = b.p('w', 'spruce_stairs[facing=east,half=bottom]')   # west slope rises to the east
    x1, x2, z1, z2 = 1, 8, 1, 9
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, '.')
    b.fill(x1, 0, z1, x2, 0, z2, P)
    b.ring(x1, z1, x2, z2, 0, C)
    posts = [(x1, z1), (x2, z1), (x1, z2), (x2, z2), (x1, 5), (x2, 5), (x1, 3), (x2, 3), (x1, 7), (x2, 7)]
    b.ring(x1, z1, x2, z2, 1, C)
    for y in range(2, 5):
        b.ring(x1, z1, x2, z2, y, P)
    for y in range(1, 5):
        for (x, z) in posts:
            b.set(x, y, z, L)
    for x in range(x1, x2 + 1):
        b.set(x, 5, z1, X); b.set(x, 5, z2, X)
    for z in range(z1, z2 + 1):
        b.set(x1, 5, z, Z); b.set(x2, 5, z, Z)
    for (x, z) in posts[:4]:
        b.set(x, 5, z, L)
    tops = b.gable_roof_z(0, D - 1, 0, W - 1, 5, sE, sW)
    for x in range(x1, x2 + 1):
        for y in range(6, tops[x]):
            b.set(x, y, z1, P); b.set(x, y, z2, P)
    # hay loft openings in the gables
    tr = b.p('r', 'spruce_trapdoor[facing=south,half=bottom,open=true]')
    b.set(4, 7, z2, b.p('o', 'spruce_trapdoor[facing=north,half=bottom,open=true]'))
    b.set(5, 7, z2, b.p('o', 'spruce_trapdoor[facing=north,half=bottom,open=true]'))
    # small ventilation windows (trapdoor shutters)
    vent = b.p('E', 'spruce_fence')
    for z in (2, 4, 6, 8):
        b.set(x1, 3, z, vent); b.set(x2, 3, z, vent)
    # double door in the front gable
    b.set(4, 1, z2, b.p('D', 'spruce_door[facing=north,half=lower,hinge=left,open=false]'))
    b.set(4, 2, z2, b.p('d', 'spruce_door[facing=north,half=upper,hinge=left,open=false]'))
    b.set(5, 1, z2, b.p('Y', 'spruce_door[facing=north,half=lower,hinge=right,open=false]'))
    b.set(5, 2, z2, b.p('y', 'spruce_door[facing=north,half=upper,hinge=right,open=false]'))
    b.set(4, 3, z2, b.p('X', 'spruce_log[axis=x]')); b.set(5, 3, z2, b.p('X', 'spruce_log[axis=x]'))
    wt = b.p('t', 'wall_torch[facing=south]')
    b.set(3, 2, 10, wt); b.set(6, 2, 10, wt)
    # interior: granary chests along the back wall, hay and barrels along the sides
    k = b.p('K', 'chest[facing=south]')
    for x in (3, 4, 5, 6):
        b.set(x, 1, 2, k)
    hb = b.p('V', 'hay_block[axis=y]')
    hx = b.p('v', 'hay_block[axis=z]')
    for z in (2, 3, 4):
        b.set(2, 1, z, hb); b.set(7, 1, z, hb)
    b.set(2, 2, 2, hx); b.set(2, 2, 3, hx); b.set(7, 2, 2, hx)
    br = b.p('R', 'barrel[facing=up]')
    for z in (6, 7, 8):
        b.set(2, 1, z, br); b.set(7, 1, z, br)
    b.set(2, 2, 7, br)
    b.set(7, 1, 5, b.p('M', 'composter'))
    lh = b.p('l', 'lantern[hanging=true]')
    b.set(4, 8, 5, lh)
    b.set(5, 8, 5, lh)
    # cross beam under the ridge for the lanterns
    for z in range(z1 + 1, z2):
        pass
    return b

# ------------------------------------------------------------------------------------------- koszary (barracks)
def koszary():
    W, H, D = 13, 11, 9
    b = B(W, H, D)
    C = b.p('C', 'cobblestone')
    S = b.p('S', 'stone_bricks')
    Y = b.p('Y', 'cracked_stone_bricks')
    m = b.p('m', 'mossy_stone_bricks')
    P = b.p('P', 'spruce_planks')
    K = b.p('k', 'dark_oak_planks')
    L = b.p('L', 'stripped_spruce_log[axis=y]')
    X = b.p('X', 'stripped_spruce_log[axis=x]')
    Z = b.p('Z', 'stripped_spruce_log[axis=z]')
    B_ = b.p('I', 'iron_bars')
    sN = b.p('n', 'dark_oak_stairs[facing=north,half=bottom]')
    sS = b.p('s', 'dark_oak_stairs[facing=south,half=bottom]')
    sl = b.p('h', 'dark_oak_slab[type=bottom]')
    x1, x2, z1, z2 = 1, 11, 1, 7
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, '.')
    b.fill(x1, 0, z1, x2, 0, z2, S)
    b.fill(x1 + 1, 0, z1 + 1, x2 - 1, 0, z2 - 1, P)
    corners = [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]
    for y in range(1, 5):
        b.ring(x1, z1, x2, z2, y, S)
    # weathering pattern
    for (x, y, z) in [(2, 1, z2), (9, 2, z2), (x1, 1, 3), (x2, 2, 5), (4, 3, z1), (10, 1, z1), (7, 4, z2)]:
        b.set(x, y, z, Y)
    for (x, y, z) in [(3, 1, z2), (x1, 1, 4), (8, 1, z1), (x2, 1, 2), (6, 1, z1)]:
        b.set(x, y, z, m)
    for y in range(1, 5):
        for (x, z) in corners:
            b.set(x, y, z, C)
    for x in range(x1, x2 + 1):
        b.set(x, 5, z1, X); b.set(x, 5, z2, X)
    for z in range(z1, z2 + 1):
        b.set(x1, 5, z, Z); b.set(x2, 5, z, Z)
    for (x, z) in corners:
        b.set(x, 5, z, L)
    tops = b.gable_roof_x(0, W - 1, 0, D - 1, 5, sN, sS, ridge_full=K, ridge_slab=sl)
    for z in range(z1, z2 + 1):
        for y in range(6, tops[z]):
            b.set(x1, y, z, S); b.set(x2, y, z, S)
    # barred windows
    for x in (3, 9):
        b.set(x, 2, z2, B_); b.set(x, 3, z2, B_)
    for x in (3, 6, 9):
        b.set(x, 3, z1, B_)
    for z in (3, 5):
        b.set(x1, 3, z, B_); b.set(x2, 3, z, B_)
    # door + banner above
    b.set(6, 1, z2, b.p('D', 'dark_oak_door[facing=north,half=lower,hinge=left,open=false]'))
    b.set(6, 2, z2, b.p('d', 'dark_oak_door[facing=north,half=upper,hinge=left,open=false]'))
    b.set(6, 4, 8, b.p('N', 'red_wall_banner[facing=south]'))
    wt = b.p('t', 'wall_torch[facing=south]')
    b.set(5, 3, 8, wt); b.set(7, 3, 8, wt)
    # six beds for soldiers along the back wall (heads north)
    bh = b.p('H', 'red_bed[facing=north,part=head]')
    bf = b.p('F', 'red_bed[facing=north,part=foot]')
    for x in (2, 3, 5, 7, 9, 10):
        b.set(x, 1, 2, bh); b.set(x, 1, 3, bf)
    q = b.p('Q', 'chest[facing=south]')
    b.set(4, 1, 2, q); b.set(8, 1, 2, q)
    b.set(6, 1, 2, b.p('W', 'fletching_table'))
    b.set(2, 1, 6, b.p('G', 'grindstone[face=floor,facing=east]'))
    b.set(10, 1, 6, b.p('A', 'smithing_table'))
    b.set(10, 1, 5, b.p('R', 'barrel[facing=up]'))
    b.set(2, 1, 5, b.p('R', 'barrel[facing=up]'))
    # inner wall torches
    b.set(4, 3, 6, b.p('u', 'wall_torch[facing=north]'))
    b.set(8, 3, 6, b.p('u', 'wall_torch[facing=north]'))
    lh = b.p('l', 'lantern[hanging=true]')
    b.set(3, 8, 4, lh); b.set(9, 8, 4, lh); b.set(6, 8, 4, lh)
    return b

# ------------------------------------------------------------------------------------------- kuznia (smithy)
def kuznia():
    W, H, D = 9, 10, 9
    b = B(W, H, D)
    C = b.p('C', 'cobblestone')
    S = b.p('S', 'stone_bricks')
    P = b.p('P', 'spruce_planks')
    L = b.p('L', 'spruce_log[axis=y]')
    X = b.p('X', 'spruce_log[axis=x]')
    Z = b.p('Z', 'spruce_log[axis=z]')
    R = b.p('B', 'bricks')
    sN = b.p('n', 'spruce_stairs[facing=north,half=bottom]')
    sS = b.p('s', 'spruce_stairs[facing=south,half=bottom]')
    K = b.p('k', 'spruce_planks')
    sl = b.p('h', 'spruce_slab[type=bottom]')
    x1, x2, z1, z2 = 1, 7, 1, 7
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, '.')
    b.fill(x1, 0, z1, x2, 0, z2, C)
    b.fill(x1 + 1, 0, z1 + 1, x2 - 1, 0, z2 - 1, b.p('g', 'andesite'))
    for x in range(2, 7):
        b.set(x, 0, 6, S)
    corners = [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]
    # back wall (north) full stone bricks, side walls low
    for y in range(1, 4):
        for x in range(x1, x2 + 1):
            b.set(x, y, z1, S)
    for z in range(z1, z2 + 1):
        b.set(x1, 1, z, S); b.set(x2, 1, z, S)
    for y in range(1, 4):
        for (x, z) in corners + [(x1, 4), (x2, 4)]:
            b.set(x, y, z, L)
    for x in range(x1, x2 + 1):
        b.set(x, 4, z1, X); b.set(x, 4, z2, X)
    for z in range(z1, z2 + 1):
        b.set(x1, 4, z, Z); b.set(x2, 4, z, Z)
    for (x, z) in corners:
        b.set(x, 4, z, L)
    tops = b.gable_roof_x(0, W - 1, 0, D - 1, 4, sN, sS, ridge_full=K, ridge_slab=sl)
    for z in range(z1, z2 + 1):
        for y in range(5, tops[z]):
            b.set(x1, y, z, P); b.set(x2, y, z, P)
    # forge: brick chimney in the back-left corner through the roof, campfire on top for smoke
    for y in range(1, 9):
        b.set(2, y, 1, R)
    b.set(2, 9, 1, b.p('f', 'campfire[lit=true,signal_fire=false,facing=south]'))
    b.set(3, 1, 1, R); b.set(3, 2, 1, R)
    b.set(2, 1, 2, b.p('U', 'blast_furnace[facing=south]'))
    b.set(3, 1, 2, b.p('V', 'lava_cauldron'))
    b.set(5, 1, 3, b.p('A', 'anvil[facing=east]'))
    b.set(5, 1, 2, b.p('M', 'smithing_table'))
    b.set(6, 1, 2, b.p('G', 'grindstone[face=floor,facing=north]'))
    b.set(6, 1, 5, b.p('w', 'water_cauldron[level=3]'))
    b.set(2, 1, 5, b.p('R', 'barrel[facing=up]'))
    b.set(2, 1, 6, b.p('K', 'chest[facing=east]'))
    lh = b.p('l', 'lantern[hanging=true]')
    b.set(4, 7, 4, lh)
    b.set(2, 2, 4, b.p('t', 'wall_torch[facing=east]'))
    b.set(6, 2, 4, b.p('u', 'wall_torch[facing=west]'))
    return b

# ------------------------------------------------------------------------------------------- studnia (well)
def studnia():
    W, H, D = 5, 12, 5
    g = 4  # ground layer index
    b = B(W, H, D)
    C = b.p('C', 'cobblestone')
    M = b.p('M', 'mossy_cobblestone')
    S = b.p('S', 'stone_bricks')
    WT = b.p('~', 'water[level=0]')
    F = b.p('F', 'spruce_fence')
    sN = b.p('n', 'spruce_stairs[facing=north,half=bottom]')
    sS = b.p('s', 'spruce_stairs[facing=south,half=bottom]')
    K = b.p('k', 'spruce_planks')
    sl = b.p('h', 'spruce_slab[type=bottom]')
    b.fill(0, 0, 0, 4, 0, 4, C)
    for y in range(1, g + 1):
        b.ring(0, 0, 4, 4, y, C)
        b.fill(1, y, 1, 3, y, 3, WT)
    for (x, y, z) in [(0, 2, 1), (4, 3, 3), (2, 1, 0), (1, 4, 4), (3, 3, 0)]:
        b.set(x, y, z, M)
    # parapet
    b.fill(0, g + 1, 0, 4, H - 1, 4, '.')
    b.ring(0, 0, 4, 4, g + 1, S)
    # posts
    for y in (g + 2, g + 3):
        for (x, z) in [(0, 0), (4, 0), (0, 4), (4, 4)]:
            b.set(x, y, z, F)
    tops = b.gable_roof_x(0, 4, 0, 4, g + 4, sN, sS, ridge_full=K, ridge_slab=sl)
    # beams under the roof at the gable ends
    for z in range(1, 4):
        pass
    b.set(2, g + 5, 2, b.p('c', 'iron_chain[axis=y]'))
    b.set(2, g + 4, 2, b.p('l', 'lantern[hanging=true]'))
    b.set(2, g + 3, 2, '.')
    return b, g

# ------------------------------------------------------------------------------------------- wieza (watchtower)
def wieza():
    W, H, D = 7, 14, 7
    b = B(W, H, D)
    C = b.p('C', 'cobblestone')
    S = b.p('S', 'stone_bricks')
    Y = b.p('Y', 'cracked_stone_bricks')
    m = b.p('m', 'mossy_stone_bricks')
    P = b.p('P', 'spruce_planks')
    x1, x2, z1, z2 = 1, 5, 1, 5
    top = 10
    b.fill(0, 1, 0, W - 1, H - 1, D - 1, '.')
    b.fill(x1, 0, z1, x2, 0, z2, S)
    for y in range(1, top):
        b.ring(x1, z1, x2, z2, y, C)
        for (x, z) in [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]:
            b.set(x, y, z, S)
    for (x, y, z) in [(2, 2, z1), (x2, 5, 3), (x1, 7, 2), (4, 8, z2), (2, 4, z2), (x2, 1, 4)]:
        b.set(x, y, z, m)
    for (x, y, z) in [(4, 3, z1), (x1, 5, 4), (3, 9, z1), (x2, 8, 2)]:
        b.set(x, y, z, Y)
    # arrow slits
    for y in (4, 7):
        b.set(3, y, z1, '.'); b.set(x1, y, 3, '.'); b.set(x2, y, 3, '.')
    b.set(3, 7, z2, '.')
    # door
    b.set(3, 1, z2, b.p('D', 'spruce_door[facing=north,half=lower,hinge=left,open=false]'))
    b.set(3, 2, z2, b.p('d', 'spruce_door[facing=north,half=upper,hinge=left,open=false]'))
    b.set(3, 3, 6, b.p('t', 'wall_torch[facing=south]'))
    # ladder on the inside of the back wall
    lad = b.p('A', 'ladder[facing=south]')
    for y in range(1, top + 1):
        b.set(3, y, 2, lad)
    # inside torches
    b.set(2, 3, 4, b.p('e', 'wall_torch[facing=east]'))
    b.set(4, 6, 4, b.p('w', 'wall_torch[facing=west]'))
    b.set(2, 9, 4, b.p('e', 'wall_torch[facing=east]'))
    # corbels under the overhanging platform
    up = {'north': b.p('1', 'stone_brick_stairs[facing=north,half=top]'),
          'south': b.p('2', 'stone_brick_stairs[facing=south,half=top]'),
          'east': b.p('3', 'stone_brick_stairs[facing=east,half=top]'),
          'west': b.p('4', 'stone_brick_stairs[facing=west,half=top]')}
    for x in range(1, 6):
        b.set(x, top - 1, 0, up['south'])
        b.set(x, top - 1, 6, up['north'])
    for z in range(1, 6):
        b.set(0, top - 1, z, up['east'])
        b.set(6, top - 1, z, up['west'])
    # platform
    b.fill(0, top, 0, 6, top, 6, P)
    b.ring(0, 0, 6, 6, top, S)
    b.set(3, top, 2, lad)
    # crenellations
    for x in range(7):
        for z in range(7):
            if x in (0, 6) or z in (0, 6):
                edge_i = x if z in (0, 6) else z
                if edge_i % 2 == 0:
                    b.set(x, top + 1, z, S)
    for (x, z) in [(0, 0), (6, 0), (0, 6), (6, 6)]:
        b.set(x, top + 2, z, b.p('j', 'lantern[hanging=false]'))
    # flag pole with banner
    b.set(3, top + 1, 4, b.p('F', 'spruce_fence'))
    b.set(3, top + 2, 4, b.p('F', 'spruce_fence'))
    b.set(3, top + 3, 4, b.p('N', 'red_banner[rotation=8]'))
    return b

