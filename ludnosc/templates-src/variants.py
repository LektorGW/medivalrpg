# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Look variants of the medieval/renaissance buildings (meta `variant-of: <base id>`).

New ids only: the base templates stay byte-identical, so buildings already standing keep matching their template
(repairs) and running construction sites keep their content hash. A variant has the same function, beds, tier,
category, zone, limits and epoch requirements as its base; the planner picks one template of the family
deterministically (BuildingChooser.variant) and the plugin gives a variant the config/bonuses of its base type.

Every variant uses the material slots (@wall, @base, @frame, @roof_stairs, @roof_slab, @roof_block, @floor, @window,
@door, @fence), so the palette of the settlement level / epoch still applies. Fixed blocks are details only:
shutters (trapdoors), window boxes, chimneys, furniture.
"""
from lib import B

AIR = '.'
OUTWARD = {'south': (0, 1), 'north': (0, -1), 'east': (1, 0), 'west': (-1, 0)}
ALONG = {'south': (1, 0), 'north': (1, 0), 'east': (0, 1), 'west': (0, 1)}
OPP = {'south': 'north', 'north': 'south', 'east': 'west', 'west': 'east'}
SLAB = '@roof_slab[type=bottom]'
CAMPFIRE = 'campfire[lit=true,signal_fire=false,facing=south]'
FLOWERS = ['potted_red_tulip', 'potted_cornflower', 'potted_allium', 'potted_oxeye_daisy', 'potted_poppy',
           'potted_azure_bluet', 'potted_orange_tulip', 'potted_lily_of_the_valley']
# half-timbering needs a light infill against the dark frame: mud bricks in an Osada, lime plaster in a Wieś
# (other levels/epochs keep the shared palette: brick nogging, stone, calcite, renaissance plaster)
TIMBER = {0: {'wall': 'mud_bricks'}, 1: {'wall': 'white_terracotta', 'frame': 'dark_oak_log'}}


def stair(mat, facing, half='bottom'):
    return f'{mat}_stairs[facing={facing},half={half}]'


def rs(facing, half='bottom'):
    return f'@roof_stairs[facing={facing},half={half}]'


def fr(axis='y'):
    return f'@frame[axis={axis}]'


def door(b, x, y, z, facing='north', hinge='left'):
    b.set(x, y, z, f'@door[facing={facing},half=lower,hinge={hinge},open=false]')
    b.set(x, y + 1, z, f'@door[facing={facing},half=upper,hinge={hinge},open=false]')


# ------------------------------------------------------------------------------------------------ helpers
def window(b, x, y, z, side, width=1, height=1, shutters='spruce', box=None, glass='@window'):
    """Window in a wall facing `side` (outward), `width` cells along the wall from (x, z), `height` rows from y.
    Shutters = open trapdoors flat against the wall on both sides; box = window box (sill trapdoor + flower pot)."""
    ox, oz = OUTWARD[side]
    ax, az = ALONG[side]
    for i in range(width):
        for j in range(height):
            b.set(x + ax * i, y + j, z + az * i, glass)
    if shutters:
        for (sx, sz) in ((x - ax, z - az), (x + ax * width, z + az * width)):
            for j in range(height):
                if b.inside(sx + ox, y + j, sz + oz):
                    b.set(sx + ox, y + j, sz + oz, f'{shutters}_trapdoor[facing={side},half=bottom,open=true]')
    if box:
        for i in range(width):
            cx, cz = x + ax * i + ox, z + az * i + oz
            if b.inside(cx, y - 1, cz):
                b.set(cx, y - 1, cz, f'{box}_trapdoor[facing={OPP[side]},half=top,open=false]')
                b.set(cx, y, cz, FLOWERS[(cx * 3 + cz * 5 + y) % len(FLOWERS)])


def posts(b, cols, y1, y2):
    for (x, z) in cols:
        for y in range(y1, y2 + 1):
            b.set(x, y, z, fr('y'))


def beam(b, x1, z1, x2, z2, y):
    """Horizontal frame ring (logs along the walls, vertical at the corners)."""
    b.beam_ring(x1, z1, x2, z2, y, '@frame')


def timber_walls(b, x1, z1, x2, z2, y1, y2, step=2, infill='@wall'):
    """Half-timbered storey (szachulec): infill between posts every `step` cells."""
    b.walls(x1, z1, x2, z2, y1, y2, infill)
    cols = set(b.corners(x1, z1, x2, z2))
    for x in range(x1, x2 + 1, step):
        cols.add((x, z1))
        cols.add((x, z2))
    for z in range(z1, z2 + 1, step):
        cols.add((x1, z))
        cols.add((x2, z))
    posts(b, sorted(cols), y1, y2)


def roof_x(b, x1, x2, z1, z2, y0, ridge=True):
    """Gable roof along x over rows z1..z2 (eaves included) from y0; ridge block + slab on an odd span."""
    return b.gable_roof_x(x1, x2, z1, z2, y0, rs('north'), rs('south'), '@roof_block', SLAB if ridge else None)


def roof_z(b, z1, z2, x1, x2, y0, ridge=True):
    """Gable roof along z (gable to the street) over columns x1..x2."""
    return b.gable_roof_z(z1, z2, x1, x2, y0, rs('west'), rs('east'), '@roof_block', SLAB if ridge else None)


def ridge_cap(b, x1, x2, zs, y):
    for x in range(x1, x2 + 1):
        for z in zs:
            b.set(x, y, z, SLAB)


def hip_roof(b, x1, x2, z1, z2, y0):
    """Hipped roof over x1..x2 × z1..z2 (eaves included): every cell rises with its distance to the nearest edge;
    stairs face the nearer edge, the ridge gets roof blocks and slabs."""
    hmax = 0
    cells = {}
    for x in range(x1, x2 + 1):
        for z in range(z1, z2 + 1):
            dx = min(x - x1, x2 - x)
            dz = min(z - z1, z2 - z)
            cells[(x, z)] = (dx, dz)
            hmax = max(hmax, min(dx, dz))
    for (x, z), (dx, dz) in cells.items():
        h = min(dx, dz)
        y = y0 + h
        if h == hmax:
            b.set(x, y, z, '@roof_block')
            b.set(x, y + 1, z, SLAB)
        elif dz <= dx:
            b.set(x, y, z, rs('north' if z - z1 > z2 - z else 'south'))
        else:
            b.set(x, y, z, rs('west' if x - x1 > x2 - x else 'east'))
    return hmax


def gable_x(b, x, z1, z2, y_from, tops, timber=True):
    """Gable wall at column x under a roof along x (a centre post when timbered)."""
    for z in range(z1, z2 + 1):
        for y in range(y_from, tops[z]):
            b.set(x, y, z, '@wall')
    if timber:
        for zc in sorted({(z1 + z2) // 2, (z1 + z2 + 1) // 2}):
            for y in range(y_from, tops[zc]):
                b.set(x, y, zc, fr('y'))


def gable_z(b, z, x1, x2, y_from, tops):
    for x in range(x1, x2 + 1):
        for y in range(y_from, tops[x]):
            b.set(x, y, z, '@wall')


def chimney(b, x, z, y1, y2, block='bricks', smoke=True):
    for y in range(y1, y2 + 1):
        b.set(x, y, z, block)
    if smoke:
        b.set(x, y2 + 1, z, CAMPFIRE)


def corbels(b, cols, y, facing):
    """Upside-down stairs under an overhang (carved brackets)."""
    for (x, z) in cols:
        b.set(x, y, z, stair('spruce', facing, 'top'))


def table(b, x, y, z, top='spruce_pressure_plate', leg='spruce_fence'):
    b.set(x, y, z, leg)
    b.set(x, y + 1, z, top)


def chair(b, x, y, z, facing, wood='spruce'):
    b.set(x, y, z, stair(wood, facing))


def lantern(b, x, y, z, hanging=True):
    b.set(x, y, z, f'lantern[hanging={"true" if hanging else "false"}]')


def clear(b):
    b.fill(0, 1, 0, b.w - 1, b.h - 1, b.d - 1, AIR)


# ================================================================================ chata_szachulec (2 beds)
def chata_szachulec():
    """Low cottage: stone plinth, half-timbered walls, steep roof with eaves and gable overhangs and a rounded
    ridge, window boxes, shutters, porch bench, stone chimney, storage loft."""
    W, H, D = 9, 10, 9
    b = B(W, H, D)
    b.materials = {k: dict(v) for k, v in TIMBER.items()}
    clear(b)
    x1, x2, z1, z2 = 1, 7, 1, 6
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.fill(x1, 0, z2 + 1, x2, 0, z2 + 1, '@base')           # threshold / porch floor
    b.ring(x1, z1, x2, z2, 1, '@base')
    timber_walls(b, x1, z1, x2, z2, 2, 3, step=3)
    posts(b, b.corners(x1, z1, x2, z2), 1, 3)
    beam(b, x1, z1, x2, z2, 4)
    tops = roof_x(b, 0, W - 1, 0, 7, 4, ridge=False)
    ridge_cap(b, 0, W - 1, (3, 4), 8)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 5, tops)
    b.set(x1, 6, 3, '@window')
    b.set(x2, 6, 4, '@window')
    door(b, 4, 1, z2)
    b.set(4, 3, z2, fr('x'))
    window(b, 2, 2, z2, 'south', box='spruce')
    window(b, 6, 2, z2, 'south', box='spruce')
    window(b, x1, 2, 3, 'west', width=2)
    window(b, 4, 2, z1, 'north')
    b.set(5, 1, 7, stair('spruce', 'north'))               # bench by the door
    b.set(6, 1, 7, stair('spruce', 'north'))
    b.set(3, 2, 7, 'wall_torch[facing=south]')
    # stone chimney through the east gable
    b.set(x2, 1, 4, 'cobblestone')
    chimney(b, x2, 4, 2, 8, 'cobblestone')
    b.set(6, 1, 4, 'campfire[lit=false,signal_fire=false,facing=west]')
    # interior: two beds at the back, hearth corner, table, storage
    b.bed(2, 1, 2, 'north', 'brown')
    b.bed(6, 1, 2, 'north', 'red')
    b.set(3, 1, 2, 'chest[facing=south]')
    b.set(4, 1, 2, 'crafting_table')
    b.set(5, 1, 2, 'barrel[facing=up]')
    b.set(6, 1, 5, 'smoker[facing=west]')
    table(b, 3, 1, 4)
    chair(b, 3, 1, 5, 'north')
    b.set(2, 1, 5, 'composter')
    b.set(4, 1, 4, 'brown_carpet')
    b.set(5, 1, 4, 'brown_carpet')
    lantern(b, 4, 6, 3)
    # storage loft over the west part
    for x in range(2, 4):
        for z in range(2, 6):
            b.set(x, 4, z, 'spruce_slab[type=top]')
    b.set(2, 5, 2, 'hay_block[axis=y]')
    b.set(3, 5, 2, 'barrel[facing=up]')
    b.set(2, 5, 3, 'hay_block[axis=x]')
    return b


# ================================================================================ chata_zrebowa (2 beds)
def chata_zrebowa():
    """Log cabin (zrąb): horizontal log courses with crossing corners, covered porch with a railing, outside stone
    chimney, shutters."""
    W, H, D = 9, 10, 10
    b = B(W, H, D)
    clear(b)
    x1, x2, z1, z2 = 1, 7, 1, 6
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.fill(x1, 0, z2 + 1, x2, 0, z2 + 2, '@floor')         # porch deck
    for x in range(x1, x2 + 1):
        b.set(x, 0, z2 + 2, '@base')
    for y in range(1, 4):
        for x in range(x1, x2 + 1):
            b.set(x, y, z1, fr('x'))
            b.set(x, y, z2, fr('x'))
        for z in range(z1 + 1, z2):
            b.set(x1, y, z, fr('z'))
            b.set(x2, y, z, fr('z'))
        for (x, z) in b.corners(x1, z1, x2, z2):
            b.set(x, y, z, fr('x' if y % 2 else 'z'))
    beam(b, x1, z1, x2, z2, 4)
    # porch: corner posts and a railing, roof continuing the front slope
    for x in (x1, x2):
        for y in range(1, 4):
            b.set(x, y, 8, fr('y'))
    for x in range(x1 + 1, x2):
        if x != 4:
            b.set(x, 1, 8, '@fence')
    tops = roof_x(b, 0, W - 1, 0, 7, 4, ridge=False)
    for x in range(0, W):
        b.set(x, 4, 8, rs('north'))
    ridge_cap(b, 0, W - 1, (3, 4), 8)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 5, tops, timber=False)
    b.set(x1, 6, 3, '@window')
    b.set(x1, 6, 4, '@window')
    b.set(x2, 6, 3, '@window')
    door(b, 4, 1, z2, hinge='right')
    window(b, 2, 2, z2, 'south')
    window(b, 6, 2, z2, 'south')
    window(b, x1, 2, 3, 'west', width=2)
    window(b, 3, 2, z1, 'north', shutters=None)
    window(b, 5, 2, z1, 'north', shutters=None)
    lantern(b, 4, 3, 7)
    b.set(2, 1, 7, 'barrel[facing=up]')
    b.set(6, 1, 7, stair('spruce', 'west'))
    # stone chimney outside the east wall
    for y in range(0, 3):
        b.set(8, y, 3, 'cobblestone')
        b.set(8, y, 4, 'cobblestone')
    b.set(x2, 1, 3, 'cobblestone')
    b.set(x2, 1, 4, 'cobblestone')
    chimney(b, 8, 3, 3, 8, 'cobblestone')
    # interior
    b.set(6, 1, 3, 'campfire[lit=false,signal_fire=false,facing=west]')
    b.set(6, 1, 4, 'barrel[facing=up]')
    b.bed(2, 1, 2, 'north', 'light_gray')
    b.bed(4, 1, 2, 'north', 'light_gray')
    b.set(3, 1, 2, 'chest[facing=south]')
    b.set(6, 1, 2, 'crafting_table')
    table(b, 3, 1, 4)
    chair(b, 2, 1, 4, 'east')
    b.set(5, 1, 5, 'white_carpet')
    lantern(b, 4, 6, 3)
    return b


# ================================================================================ dom_szachulec (4 beds)
def dom_szachulec():
    """Half-timbered house with a jettied upper storey (overhang on carved brackets to the street), a dormer,
    window boxes, shutters, brick chimney; kitchen and parlour below, 4 beds upstairs."""
    W, H, D = 11, 15, 10
    b = B(W, H, D)
    b.materials = {k: dict(v) for k, v in TIMBER.items()}
    clear(b)
    x1, x2, z1 = 1, 9, 1
    zg, zu = 7, 8           # ground-floor front wall, jettied upper front wall
    b.fill(x1, 0, z1, x2, 0, zg, '@floor')
    b.ring(x1, z1, x2, zg, 0, '@base')
    b.ring(x1, z1, x2, zg, 1, '@base')
    timber_walls(b, x1, z1, x2, zg, 2, 3)
    posts(b, b.corners(x1, z1, x2, zg), 1, 3)
    b.fill(x1, 4, z1, x2, 4, zu, '@floor')
    beam(b, x1, z1, x2, zu, 4)
    corbels(b, [(x, zu) for x in (x1, 3, 5, 7, x2)], 3, 'north')
    timber_walls(b, x1, z1, x2, zu, 5, 7)
    beam(b, x1, z1, x2, zu, 8)
    tops = roof_x(b, 0, W - 1, 0, 9, 8, ridge=False)
    ridge_cap(b, 0, W - 1, (4, 5), 13)
    for x in (x1, x2):
        gable_x(b, x, z1, zu, 9, tops)
        b.set(x, 10, 3, '@window')
        b.set(x, 10, 6, '@window')
    # dormer in the front slope (wall plane z = 8): framed window, cheeks, small gable roof joining the main roof
    for y in (9, 10):
        b.set(4, y, 8, fr('y'))
        b.set(6, y, 8, fr('y'))
        b.set(5, y, 8, '@window')
        b.set(5, y, 7, AIR)
    b.set(4, 10, 7, '@wall')
    b.set(6, 10, 7, '@wall')
    for z in (7, 8):
        b.set(4, 11, z, rs('east'))
        b.set(6, 11, z, rs('west'))
        b.set(5, 11, z, '@roof_block')
        b.set(5, 12, z, SLAB)
    # front: door, ground windows with boxes, upper windows with shutters
    door(b, 5, 1, zg)
    b.set(5, 3, zg, fr('x'))
    lantern(b, 5, 3, zu)
    window(b, 2, 2, zg, 'south', width=2, shutters=None, box='spruce')
    window(b, 7, 2, zg, 'south', width=2, shutters=None, box='spruce')
    window(b, 2, 6, zu, 'south')
    window(b, 8, 6, zu, 'south')
    window(b, 3, 2, z1, 'north')
    window(b, 7, 6, z1, 'north')
    window(b, 3, 6, z1, 'north', shutters=None)
    window(b, x1, 2, 4, 'west')
    window(b, x2, 2, 4, 'east')
    window(b, x1, 6, 5, 'west', shutters=None)
    window(b, x2, 6, 3, 'east', shutters=None)
    # brick chimney: hearth in the north-east corner, stack through the roof
    for y in range(1, 4):
        b.set(8, y, 1, 'bricks')
    chimney(b, 8, 1, 4, 13)
    b.set(8, 1, 2, 'campfire[lit=true,signal_fire=false,facing=south]')
    b.set(8, 2, 2, 'bricks')
    b.set(8, 3, 2, 'bricks')
    # ground floor: kitchen (east), parlour (west)
    b.set(7, 1, 2, 'smoker[facing=south]')
    b.set(6, 1, 2, 'barrel[facing=up]')
    b.set(8, 1, 4, 'water_cauldron[level=3]')
    b.set(8, 1, 5, 'barrel[facing=up]')
    table(b, 3, 1, 4, 'white_carpet')
    table(b, 3, 1, 5, 'white_carpet')
    chair(b, 2, 1, 4, 'east')
    chair(b, 4, 1, 5, 'west')
    b.set(2, 1, 2, 'bookshelf')
    b.set(3, 1, 2, 'chest[facing=south]')
    lantern(b, 3, 3, 4)
    lantern(b, 7, 3, 4)
    for y in range(1, 5):
        b.set(6, y, 6, 'ladder[facing=north]')
    b.set(7, 5, 6, '@fence')
    # upper floor: 4 beds, storage, lectern
    for x in (2, 3):
        b.bed(x, 5, 2, 'north', 'cyan')
    for x in (7, 8):
        b.bed(x, 5, 2, 'north', 'orange')
    b.set(5, 5, 2, 'chest[facing=south]')
    b.set(4, 5, 2, 'barrel[facing=up]')
    lantern(b, 5, 6, 2, hanging=False)
    b.set(2, 5, 7, 'lectern[facing=north]')
    b.set(3, 5, 5, 'red_carpet')
    b.set(4, 5, 5, 'red_carpet')
    b.set(8, 5, 7, 'potted_blue_orchid')
    lantern(b, 5, 7, 4)
    return b


# ================================================================================ dom_kamienny (4 beds)
def dom_kamienny():
    """Stone house: masonry walls with timber quoins, steep roof on eave brackets, a large chimney on the gable,
    door canopy with lanterns, shutters and window boxes; two bedrooms with 2 beds each."""
    W, H, D = 11, 14, 9
    b = B(W, H, D)
    clear(b)
    x1, x2, z1, z2 = 1, 9, 1, 7
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.walls(x1, z1, x2, z2, 1, 7, '@base')
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, 8):
            b.set(x, y, z, fr('y'))
    b.fill(x1 + 1, 4, z1 + 1, x2 - 1, 4, z2 - 1, '@floor')
    beam(b, x1, z1, x2, z2, 8)
    tops = roof_x(b, 0, W - 1, 0, 8, 8)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 9, tops, timber=False)
        b.set(x, 10, 4, '@window')
    corbels(b, [(x, 8) for x in (x1, 5, x2)], 7, 'north')
    corbels(b, [(x, 0) for x in (x1, 5, x2)], 7, 'south')
    # entrance with a stone canopy and lanterns
    door(b, 5, 1, z2)
    b.set(4, 3, 8, stair('stone_brick', 'east', 'top'))
    b.set(6, 3, 8, stair('stone_brick', 'west', 'top'))
    b.set(5, 3, 8, 'stone_brick_slab[type=top]')
    b.set(4, 2, 8, 'lantern[hanging=true]')
    b.set(6, 2, 8, 'lantern[hanging=true]')
    for x in (2, 8):
        window(b, x, 2, z2, 'south', shutters='dark_oak', box='dark_oak')
    window(b, 3, 6, z2, 'south', shutters='dark_oak')
    window(b, 7, 6, z2, 'south', shutters='dark_oak')
    b.set(5, 6, z2, '@window')
    for x in (3, 7):
        window(b, x, 2, z1, 'north', shutters=None)
        window(b, x, 6, z1, 'north', shutters=None)
    window(b, x1, 2, 4, 'west', shutters='dark_oak')
    window(b, x1, 6, 3, 'west', shutters=None)
    window(b, x2, 6, 5, 'east', shutters=None)
    # big chimney outside the east gable; fireplace inside
    for y in range(0, 13):
        b.set(10, y, 4, 'cobblestone' if y < 4 else 'stone_bricks')
    b.set(10, 13, 4, CAMPFIRE)
    b.set(10, 0, 3, 'cobblestone')
    b.set(10, 0, 5, 'cobblestone')
    b.set(x2, 1, 4, 'campfire[lit=true,signal_fire=false,facing=west]')
    b.set(x2, 2, 4, 'bricks')
    b.set(x2, 3, 4, 'bricks')
    # ground floor: hall with a dining table, kitchen corner
    b.set(8, 1, 2, 'smoker[facing=south]')
    b.set(7, 1, 2, 'barrel[facing=up]')
    b.set(6, 1, 2, 'barrel[facing=up]')
    b.set(2, 1, 2, 'chest[facing=south]')
    b.set(3, 1, 2, 'crafting_table')
    for x in (3, 4, 5):
        table(b, x, 1, 4, 'potted_red_tulip' if x == 4 else 'spruce_pressure_plate', 'dark_oak_fence')
    for x in (3, 5):
        chair(b, x, 1, 3, 'south', 'dark_oak')
        chair(b, x, 1, 5, 'north', 'dark_oak')
    b.set(7, 1, 5, 'red_carpet')
    b.set(7, 1, 6, 'red_carpet')
    lantern(b, 4, 3, 4)
    for y in range(1, 5):
        b.set(2, y, 6, 'ladder[facing=north]')
    b.set(3, 5, 6, '@fence')
    # upstairs: two bedrooms, partition with an opening
    for z in (2, 3, 5, 6):
        b.set(5, 5, z, '@wall')
        b.set(5, 6, z, '@wall')
    b.bed(2, 5, 2, 'north', 'blue')
    b.bed(3, 5, 2, 'north', 'blue')
    b.bed(7, 5, 2, 'north', 'purple')
    b.bed(8, 5, 2, 'north', 'purple')
    b.set(4, 5, 2, 'chest[facing=south]')
    b.set(6, 5, 2, 'chest[facing=south]')
    lantern(b, 4, 6, 2, hanging=False)
    b.set(8, 5, 6, 'bookshelf')
    b.set(7, 5, 6, 'lectern[facing=north]')
    b.set(4, 5, 6, 'barrel[facing=up]')
    lantern(b, 3, 7, 4)
    lantern(b, 7, 7, 4)
    lantern(b, 5, 12, 4)
    return b


# ================================================================================ kamienica_szczytowa (6 beds)
def kamienica_szczytowa():
    """Gabled town house (gable to the market): crow-stepped gable flush with the facade, shop with a striped stall
    awning in the country colours, timber-framed upper floors with window boxes and shutters, 6 beds."""
    W, H, D = 11, 21, 10
    b = B(W, H, D)
    b.materials = {k: dict(v) for k, v in TIMBER.items()}
    clear(b)
    x1, x2, z1, z2 = 1, 9, 1, 8
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.fill(x1, 0, 9, x2, 0, 9, '@base')
    b.walls(x1, z1, x2, z2, 1, 3, '@base')
    for floor_y in (4, 8):
        b.fill(x1, floor_y, z1, x2, floor_y, z2, '@floor')
        beam(b, x1, z1, x2, z2, floor_y)
    timber_walls(b, x1, z1, x2, z2, 5, 7)
    timber_walls(b, x1, z1, x2, z2, 9, 11)
    beam(b, x1, z1, x2, z2, 12)
    # roof along z between the two gables, eaves on the sides
    tops = roof_z(b, z1, z2, 0, 10, 12)
    for z in (z1, z2):
        gable_z(b, z, x1, x2, 13, tops)
        for x in range(x1, x2 + 1):
            b.set(x, tops[x], z, '@wall')          # the gable rises above the roof surface
        for x in range(x1, x2 + 1, 2):
            b.set(x, tops[x] + 1, z, 'stone_brick_wall')
    b.set(5, tops[5] + 2, z2, 'lantern[hanging=false]')
    for (x, y) in ((5, 14), (5, 15), (4, 14), (6, 14)):
        b.set(x, y, z2, '@window')
    b.set(5, 14, z1, '@window')
    # shop front with an awning (country colour stripes), goods on the sill
    door(b, 5, 1, z2)
    for x in (2, 3, 7, 8):
        b.set(x, 2, z2, '@window')
        b.set(x, 3, 9, '{kolor}_wool' if x % 2 == 0 else 'white_wool')
    b.set(4, 3, 9, 'white_wool')
    b.set(6, 3, 9, 'white_wool')
    b.set(2, 1, 9, 'barrel[facing=up]')
    b.set(8, 1, 9, 'barrel[facing=up]')
    b.set(3, 1, 9, 'spruce_slab[type=top]')
    b.set(7, 1, 9, 'spruce_slab[type=top]')
    b.set(3, 2, 9, 'potted_red_tulip')
    b.set(7, 2, 9, 'potted_cornflower')
    b.set(4, 2, 9, 'wall_torch[facing=south]')
    b.set(6, 2, 9, 'wall_torch[facing=south]')
    for y0 in (6, 10):
        window(b, 2, y0, z2, 'south', width=2, box='spruce')
        window(b, 7, y0, z2, 'south', width=2, box='spruce')
        b.set(5, y0, z2, '@window')
        window(b, 3, y0, z1, 'north', shutters=None)
        window(b, 7, y0, z1, 'north', shutters=None)
        b.set(x1, y0, 4, '@window')
        b.set(x2, y0, 5, '@window')
    b.set(x1, 2, 4, '@window')
    b.set(x2, 2, 4, '@window')
    # chimney on the west side
    for y in range(1, 13):
        b.set(2, y, 3, 'bricks')
    chimney(b, 2, 3, 13, 17)
    # shop
    for x in (3, 4, 6, 7):
        b.set(x, 1, 6, 'spruce_slab[type=top]')
    b.set(3, 1, 2, 'barrel[facing=up]')
    b.set(4, 1, 2, 'chest[facing=south]')
    b.set(5, 1, 2, 'crafting_table')
    b.set(6, 1, 2, 'loom[facing=south]')
    b.set(7, 1, 2, 'barrel[facing=up]')
    lantern(b, 5, 3, 4)
    for y in range(1, 12):
        b.set(8, y, 3, 'ladder[facing=west]')
    for fy in (5, 9):
        b.set(7, fy, 3, '@fence')
        b.bed(3, fy, 2, 'north', 'red' if fy == 5 else 'green')
        b.bed(4, fy, 2, 'north', 'red' if fy == 5 else 'green')
        b.bed(6, fy, 2, 'north', 'white' if fy == 5 else 'yellow')
        b.set(5, fy, 2, 'barrel[facing=up]')
        lantern(b, 5, fy + 1, 2, hanging=False)
        b.set(3, fy, 7, 'chest[facing=north]')
        table(b, 6, fy, 6, 'potted_allium' if fy == 5 else 'potted_oxeye_daisy')
        chair(b, 7, fy, 6, 'west')
        b.set(4, fy, 5, 'brown_carpet')
        lantern(b, 5, fy + 2, 5)
    lantern(b, 5, 16, 4)
    return b


# ================================================================================ kamienica_podcieniowa (6 beds)
def kamienica_podcieniowa():
    """Arcaded town house: arcade (podcienie) on stone pillars along the street, two plastered floors under a hipped
    roof with two dormers, window boxes and shutters, 6 beds."""
    W, H, D = 11, 19, 11
    b = B(W, H, D)
    b.materials = {1: {'wall': 'white_terracotta', 'frame': 'dark_oak_log'}}
    clear(b)
    x1, x2, z1, z2 = 1, 9, 1, 9      # upper walls; ground floor wall at z = 7, arcade z = 8..9
    zg = 7
    b.fill(x1, 0, z1, x2, 0, z2, '@base')
    b.fill(x1 + 1, 0, z1 + 1, x2 - 1, 0, zg - 1, '@floor')
    b.walls(x1, z1, x2, zg, 1, 3, '@base')
    # arcade: pillars and arches at the street line
    for x in (x1, 4, 6, x2):
        for y in range(1, 4):
            b.set(x, y, z2, '@base')
    for (x, f) in ((2, 'west'), (3, 'east'), (7, 'west'), (8, 'east')):
        b.set(x, 3, z2, stair('stone_brick', f, 'top'))
    b.set(5, 3, z2, 'stone_brick_slab[type=top]')
    for x in (x1, x2):
        b.set(x, 3, 8, '@base')
    lantern(b, 3, 3, 8)
    lantern(b, 7, 3, 8)
    for floor_y in (4, 8):
        b.fill(x1, floor_y, z1, x2, floor_y, z2, '@floor')
        beam(b, x1, z1, x2, z2, floor_y)
    for y in list(range(5, 8)) + list(range(9, 12)):
        b.ring(x1, z1, x2, z2, y, '@wall')
    posts(b, b.corners(x1, z1, x2, z2), 5, 11)
    beam(b, x1, z1, x2, z2, 12)
    hip_roof(b, 0, W - 1, 0, D - 1, 12)
    # dormers on the front slope
    for xd in (3, 7):
        b.set(xd, 13, 9, '@window')
        b.set(xd - 1, 13, 9, fr('y'))
        b.set(xd + 1, 13, 9, fr('y'))
        b.set(xd - 1, 14, 9, rs('east'))
        b.set(xd + 1, 14, 9, rs('west'))
        b.set(xd, 14, 9, '@roof_block')
        b.set(xd, 15, 9, SLAB)
        b.set(xd, 13, 8, AIR)
    # facade
    for y0 in (6, 10):
        for x in (2, 5, 8):
            window(b, x, y0, z2, 'south', box='spruce' if y0 == 6 else None)
        window(b, 3, y0, z1, 'north', shutters=None)
        window(b, 7, y0, z1, 'north', shutters=None)
        b.set(x1, y0, 5, '@window')
        b.set(x2, y0, 5, '@window')
    # shop behind the arcade
    door(b, 5, 1, zg)
    for x in (2, 3, 7, 8):
        b.set(x, 2, zg, '@window')
    b.set(2, 1, 8, 'barrel[facing=up]')
    b.set(8, 1, 8, 'barrel[facing=up]')
    b.set(3, 1, 8, stair('spruce', 'north'))
    b.set(7, 1, 8, stair('spruce', 'north'))
    for x in (3, 4, 6, 7):
        b.set(x, 1, 5, 'spruce_slab[type=top]')
    b.set(3, 1, 2, 'chest[facing=south]')
    b.set(4, 1, 2, 'crafting_table')
    b.set(6, 1, 2, 'cartography_table')
    b.set(7, 1, 2, 'barrel[facing=up]')
    lantern(b, 5, 3, 4)
    for y in range(1, 12):
        b.set(8, y, 3, 'ladder[facing=west]')
    # chimney in the north-west corner
    for y in range(1, 13):
        b.set(2, y, 2, 'bricks')
    chimney(b, 2, 2, 13, 16)
    for fy in (5, 9):
        b.set(7, fy, 3, '@fence')
        b.bed(3, fy, 2, 'north', 'blue')
        b.bed(4, fy, 2, 'north', 'blue')
        b.bed(6, fy, 2, 'north', 'lime')
        b.set(5, fy, 2, 'chest[facing=south]')
        lantern(b, 5, fy + 1, 2, hanging=False)
        table(b, 4, fy, 6, 'potted_red_tulip')
        chair(b, 3, fy, 6, 'east')
        b.set(7, fy, 7, 'bookshelf')
        b.set(6, fy, 7, 'barrel[facing=up]')
        lantern(b, 5, fy + 2, 5)
    return b


# ================================================================================ kamienica_renesansowa_loggia (6 beds, era 2)
def kamienica_renesansowa_loggia():
    """Renaissance town house: rusticated ground floor with an arched portal, an arcaded loggia on the first floor,
    cornices, and a parapet attic with urns hiding a flat tiled roof (dach pogrążony); 6 beds."""
    W, D = 11, 11
    x1, z1, x2, z2 = 0, 1, 10, 8
    roof = 13
    H = roof + 4
    b = B(W, H, D)
    clear(b)
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.fill(x1, 0, z2 + 1, x2, 0, D - 1, '@base')
    b.walls(x1, z1, x2, z2, 1, 3, '@base')
    b.walls(x1, z1, x2, z2, 4, roof - 1, '@wall')
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, roof):
            b.set(x, y, z, fr('y'))
    # cornices on the facade
    for x in range(x1, x2 + 1):
        b.set(x, 4, z2 + 1, 'stone_brick_slab[type=top]')
        b.set(x, 8, z2 + 1, stair('stone_brick', 'north', 'top'))
        b.set(x, roof - 1, z2 + 1, stair('stone_brick', 'north', 'top'))
    for y in (4, 8):
        b.fill(x1 + 1, y, z1 + 1, x2 - 1, y, z2 - 1, '@floor')
    # rusticated ground floor: portal with an arch, shop windows
    for x in (3, 4, 6, 7):
        b.set(x, 2, z2, '@window')
    door(b, 5, 1, z2)
    b.set(5, 3, z2, 'chiseled_stone_bricks')
    b.set(4, 3, z2, stair('stone_brick', 'west', 'top'))
    b.set(6, 3, z2, stair('stone_brick', 'east', 'top'))
    b.set(2, 2, z2 + 1, 'wall_torch[facing=south]')
    b.set(8, 2, z2 + 1, 'wall_torch[facing=south]')
    # loggia: the first-floor wall steps back to zl, the facade becomes an arcade with a balustrade
    zl = z2 - 2
    for x in range(x1 + 1, x2):
        for y in (5, 6, 7):
            b.set(x, y, z2, AIR)
            b.set(x, y, z2 - 1, AIR)
            b.set(x, y, zl, '@wall')
    for x in (x1 + 1, x2 - 1):
        for z in range(zl, z2 + 1):
            for y in (5, 6, 7):
                b.set(x, y, z, '@wall')
    for x in range(2, 9):
        b.set(x, 5, z2, 'stone_brick_wall')
    for x in (2, 4, 6, 8):
        b.set(x, 6, z2, fr('y'))
        b.set(x, 7, z2, fr('y'))
    for x in (3, 5, 7):
        b.set(x, 7, z2, stair('stone_brick', 'north', 'top'))
    # an open archway to the loggia (an upper door would count as an exterior door needing outside stairs)
    b.set(5, 5, zl, AIR)
    b.set(5, 6, zl, AIR)
    b.set(5, 7, zl, stair('stone_brick', 'south', 'top'))
    b.set(3, 6, zl, '@window')
    b.set(7, 6, zl, '@window')
    b.set(3, 5, z2 - 1, 'potted_red_tulip')
    b.set(7, 5, z2 - 1, 'potted_orange_tulip')
    lantern(b, 5, 7, z2 - 1)
    # second floor: tall windows with window boxes
    for x in (2, 5, 8):
        window(b, x, 10, z2, 'south', height=2, shutters=None, box='spruce' if x != 5 else None)
    for y0 in (6, 10):
        for z in (3, 5):
            b.set(x1, y0, z, '@window')
            b.set(x2, y0, z, '@window')
        for x in (3, 7):
            b.set(x, y0, z1, '@window')
    # shop
    b.set(2, 1, 3, 'barrel[facing=up]')
    b.set(8, 1, 3, 'barrel[facing=up]')
    b.set(3, 1, 3, 'chest[facing=south]')
    b.set(7, 1, 3, 'crafting_table')
    for x in (3, 4, 6, 7):
        b.set(x, 1, 5, 'birch_slab[type=top]')
    lantern(b, 5, 3, 4)
    b.ladder(9, 1, roof - 1, 2, 'south')
    b.set(9, 4, 2, 'ladder[facing=south]')
    b.set(9, 8, 2, 'ladder[facing=south]')
    # flats: 3 beds per floor
    b.bed(2, 5, 2, 'north', 'red')
    b.bed(4, 5, 2, 'north', 'red')
    b.bed(7, 5, 2, 'north', 'blue')
    b.set(3, 5, 2, 'chest[facing=south]')
    table(b, 5, 5, 4, 'potted_allium', 'birch_fence')
    lantern(b, 5, 7, 3)
    b.bed(2, 9, 2, 'north', 'green')
    b.bed(4, 9, 2, 'north', 'green')
    b.bed(7, 9, 2, 'north', 'yellow')
    b.set(2, 9, 6, 'chest[facing=east]')
    b.set(8, 9, 6, 'bookshelf')
    b.set(5, 9, 6, 'lectern[facing=north]')
    lantern(b, 5, 12, 4)
    # flat tiled roof behind a parapet, roof hatch, two chimneys
    b.fill(x1, roof, z1, x2, roof, z2, '@roof_block')
    b.set(9, roof, 2, 'birch_trapdoor[facing=south,half=top,open=false]')
    for x in range(x1, x2 + 1):
        b.set(x, roof + 1, z1, SLAB)
    for z in range(z1, z2 + 1):
        b.set(x1, roof + 1, z, SLAB)
        b.set(x2, roof + 1, z, SLAB)
    for x in (2, 8):
        chimney(b, x, 3, roof + 1, roof + 1, 'bricks', smoke=False)
    # attic: a solid frieze with balusters and urns on the front
    for x in range(x1, x2 + 1):
        b.set(x, roof, z2 + 1, '@wall')
        b.set(x, roof + 1, z2 + 1, 'stone_brick_wall' if x % 2 == 0 else '@wall')
        b.set(x, roof + 1, z2, '@wall')
    for x in (0, 5, 10):
        b.set(x, roof + 1, z2 + 1, 'chiseled_stone_bricks')
        b.set(x, roof + 2, z2 + 1, 'flower_pot')
    return b


# ================================================================================ karczma_zajazd (4 beds)
def karczma_zajazd():
    """Roadside inn: half-timbered, with an upper gallery on posts under the roof, hitching rail, trough and hay,
    a hall with long tables, bar and fireplace, 4 guest beds upstairs."""
    W, H, D = 13, 15, 10
    b = B(W, H, D)
    b.materials = {k: dict(v) for k, v in TIMBER.items()}
    clear(b)
    x1, x2, z1, z2 = 1, 11, 1, 7
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.fill(x1, 0, 8, x2, 0, 8, '@base')
    b.ring(x1, z1, x2, z2, 1, '@base')
    timber_walls(b, x1, z1, x2, z2, 2, 3)
    posts(b, b.corners(x1, z1, x2, z2), 1, 3)
    b.fill(x1, 4, z1, x2, 4, 8, '@floor')
    beam(b, x1, z1, x2, z2, 4)
    # gallery over the porch on posts, with a railing
    for x in (x1, 4, 8, x2):
        for y in range(1, 5):
            b.set(x, y, 8, fr('y'))
    for x in range(x1, x2 + 1):
        b.set(x, 5, 8, '@fence')
    timber_walls(b, x1, z1, x2, z2, 5, 7)
    beam(b, x1, z1, x2, z2, 8)
    tops = roof_x(b, 0, W - 1, 0, 9, 8, ridge=False)
    ridge_cap(b, 0, W - 1, (4, 5), 13)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 9, tops)
        b.set(x, 10, 3, '@window')
        b.set(x, 10, 5, '@window')
    door(b, 6, 1, z2)
    b.set(6, 3, z2, fr('x'))
    b.set(6, 5, z2, AIR)                     # open passage to the gallery (no upper exterior door)
    b.set(6, 6, z2, AIR)
    lantern(b, 6, 3, 8)
    lantern(b, 3, 7, 8)
    lantern(b, 9, 7, 8)
    window(b, 2, 2, z2, 'south', width=2)
    window(b, 9, 2, z2, 'south', width=2)
    for x in (2, 4, 8, 10):
        b.set(x, 6, z2, '@window')
    for x in (3, 9):
        window(b, x, 2, z1, 'north', shutters=None)
        window(b, x, 6, z1, 'north', shutters=None)
    window(b, x1, 2, 4, 'west')
    window(b, x2, 6, 3, 'east', shutters=None)
    # hitching rail, trough and hay by the road
    b.set(1, 1, 9, '@fence')
    b.set(2, 1, 9, '@fence')
    b.set(3, 1, 9, 'water_cauldron[level=3]')
    b.set(10, 1, 9, 'hay_block[axis=y]')
    b.set(11, 1, 9, '@fence')
    # hall: bar with kegs, fireplace with an outside chimney, long tables
    for x in (2, 3, 4):
        b.set(x, 1, 3, 'stripped_spruce_log[axis=x]')
        b.set(x, 1, 2, 'barrel[facing=south]')
    b.set(2, 2, 2, 'barrel[facing=up]')
    b.set(3, 2, 3, 'brewing_stand')
    b.set(5, 1, 2, 'water_cauldron[level=3]')
    b.set(x2, 1, 3, 'campfire[lit=true,signal_fire=false,facing=west]')
    b.set(x2, 2, 3, 'bricks')
    b.set(x2, 3, 3, 'bricks')
    for y in range(0, 13):
        b.set(12, y, 3, 'bricks')
    b.set(12, 13, 3, CAMPFIRE)
    for x in (7, 8, 9):
        table(b, x, 1, 4)
        chair(b, x, 1, 3, 'south')
        chair(b, x, 1, 5, 'north')
    table(b, 3, 1, 5)
    chair(b, 2, 1, 5, 'east')
    chair(b, 4, 1, 5, 'west')
    b.set(10, 1, 6, 'chest[facing=west]')
    b.set(10, 1, 2, 'smoker[facing=south]')
    lantern(b, 4, 3, 4)
    lantern(b, 8, 3, 4)
    for y in range(1, 5):
        b.set(2, y, 6, 'ladder[facing=north]')
    b.set(3, 5, 6, '@fence')
    for x in (3, 4, 8, 9):
        b.bed(x, 5, 2, 'north', 'green' if x < 6 else 'yellow')
    b.set(6, 5, 2, 'chest[facing=south]')
    lantern(b, 6, 6, 2, hanging=False)
    b.set(10, 5, 4, 'barrel[facing=up]')
    b.set(5, 5, 4, 'brown_carpet')
    b.set(7, 5, 4, 'brown_carpet')
    lantern(b, 6, 11, 4)
    return b


# ================================================================================ kuznia_kamienna
def kuznia_kamienna():
    """Stone smithy: masonry workshop with a brick forge and chimney, a lean-to on posts over the outdoor work area
    with the anvil, quenching trough, coal and a stonecutter."""
    W, H, D = 9, 11, 10
    b = B(W, H, D)
    clear(b)
    x1, x2, z1, z2 = 1, 7, 1, 5
    b.fill(x1, 0, z1, x2, 0, 8, '@base')
    b.fill(x1 + 1, 0, z1 + 1, x2 - 1, 0, z2 - 1, 'andesite')
    b.walls(x1, z1, x2, z2, 1, 3, '@base')
    posts(b, b.corners(x1, z1, x2, z2), 1, 3)
    beam(b, x1, z1, x2, z2, 4)
    tops = roof_x(b, 0, W - 1, 0, 6, 4)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 5, tops, timber=False)
    # lean-to over the work area: corner posts at the front (the way to the door stays free), beams, slope to the south
    for x in (x1, x2):
        for y in range(1, 3):
            b.set(x, y, 8, fr('y'))
    for x in range(x1, x2 + 1):
        b.set(x, 3, 6, fr('x'))
        b.set(x, 3, 7, fr('x'))
    for x in range(0, W):
        b.set(x, 4, 6, rs('north'))
        b.set(x, 4, 7, rs('north'))
        b.set(x, 3, 8, rs('north'))
    # forge in the north-west corner, chimney through the roof
    b.set(2, 1, 2, 'blast_furnace[facing=south]')
    b.set(3, 1, 2, 'lava_cauldron')
    b.set(2, 2, 2, 'bricks')
    b.set(3, 2, 2, 'bricks')
    b.set(2, 3, 2, 'bricks')
    b.set(3, 3, 2, 'brick_stairs[facing=north,half=bottom]')
    for y in range(1, 9):
        b.set(2, y, 1, 'bricks')
    b.set(2, 9, 1, CAMPFIRE)
    # workshop
    b.set(5, 1, 2, 'smithing_table')
    b.set(6, 1, 2, 'grindstone[face=floor,facing=north]')
    b.set(6, 1, 4, 'chest[facing=west]')
    lantern(b, 6, 2, 4, hanging=False)
    b.set(2, 1, 4, 'barrel[facing=up]')
    lantern(b, 4, 3, 3)
    door(b, 4, 1, z2)
    b.set(x2, 2, 3, '@window')
    window(b, 6, 2, z2, 'south')
    # outdoor work area
    b.set(3, 1, 7, 'anvil[facing=east]')
    b.set(2, 1, 7, 'water_cauldron[level=3]')
    b.set(6, 1, 7, 'coal_block')
    b.set(6, 1, 6, 'barrel[facing=up]')
    b.set(5, 1, 6, 'stonecutter[facing=south]')
    lantern(b, 4, 2, 7)
    return b


# ================================================================================ piekarnia_szachulec
def piekarnia_szachulec():
    """Half-timbered bakery: a domed brick bread oven against the east wall with its own chimney, a sales window
    under a striped awning, flour barrels, kneading tables."""
    W, H, D = 10, 10, 9
    b = B(W, H, D)
    b.materials = {k: dict(v) for k, v in TIMBER.items()}
    clear(b)
    x1, x2, z1, z2 = 1, 7, 1, 6
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.fill(x1, 0, 7, x2, 0, 7, '@base')
    b.ring(x1, z1, x2, z2, 1, '@base')
    timber_walls(b, x1, z1, x2, z2, 2, 3)
    posts(b, b.corners(x1, z1, x2, z2), 1, 3)
    beam(b, x1, z1, x2, z2, 4)
    tops = roof_x(b, 0, x2 + 1, 0, 7, 4, ridge=False)
    ridge_cap(b, 0, x2 + 1, (3, 4), 8)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 5, tops)
    b.set(x1, 6, 3, '@window')
    b.set(x2, 6, 4, '@window')
    # domed oven outside the east wall
    for z in (2, 3, 4):
        b.set(8, 0, z, 'bricks')
        b.set(9, 0, z, 'bricks')
        b.set(9, 1, z, 'bricks')
        b.set(9, 2, z, 'brick_slab[type=bottom]')
    b.set(8, 1, 2, 'bricks')
    b.set(8, 1, 4, 'bricks')
    b.set(8, 1, 3, 'furnace[facing=east]')
    b.set(8, 2, 2, 'brick_stairs[facing=west,half=bottom]')
    b.set(8, 2, 4, 'brick_stairs[facing=west,half=bottom]')
    b.set(8, 2, 3, 'bricks')
    chimney(b, 8, 3, 3, 8)
    # front: door, sales window with a counter and an awning in the country colours
    door(b, 5, 1, z2)
    window(b, 2, 2, z2, 'south', width=2)
    b.set(2, 1, 5, 'spruce_slab[type=top]')
    b.set(3, 1, 5, 'spruce_slab[type=top]')
    b.set(1, 3, 7, 'white_wool')
    b.set(2, 3, 7, '{kolor}_wool')
    b.set(3, 3, 7, 'white_wool')
    b.set(4, 3, 7, '{kolor}_wool')
    b.set(2, 1, 7, 'barrel[facing=up]')
    b.set(4, 1, 7, stair('spruce', 'north'))
    b.set(6, 2, 7, 'wall_torch[facing=south]')
    window(b, x1, 2, 4, 'west', box='spruce')
    window(b, 4, 2, z1, 'north', shutters=None)
    # interior: oven mouths, flour, kneading tables, stores
    b.set(x2, 1, 3, 'smoker[facing=west]')
    b.set(x2, 1, 2, 'furnace[facing=west]')
    b.set(x2, 1, 4, 'barrel[facing=up]')
    b.set(2, 1, 2, 'barrel[facing=up]')
    b.set(3, 1, 2, 'barrel[facing=up]')
    b.set(2, 2, 2, 'barrel[facing=up]')
    b.set(2, 1, 3, 'hay_block[axis=y]')
    b.set(4, 1, 2, 'crafting_table')
    b.set(5, 1, 2, 'chest[facing=south]')
    table(b, 4, 1, 4)
    table(b, 5, 1, 4)
    b.set(6, 1, 5, 'composter')
    lantern(b, 4, 6, 3)
    return b


# ================================================================================ tartak_kryty
def tartak_kryty():
    """Covered sawmill: open timber hall on stone footings with knee braces, plank back wall, saw line in the
    middle, log and plank stacks, grindstone and a workbench."""
    W, H, D = 11, 10, 10
    b = B(W, H, D)
    clear(b)
    x1, x2, z1, z2 = 1, 9, 1, 8
    b.fill(x1, 0, z1, x2, 0, z2, 'coarse_dirt')
    b.ring(x1, z1, x2, z2, 0, '@base')
    for x in range(3, 8):
        b.set(x, 0, 4, '@floor')
        b.set(x, 0, 5, '@floor')
    cols = [(x, z) for x in (x1, 5, x2) for z in (z1, 4, z2)]
    for (x, z) in cols:
        b.set(x, 1, z, '@base')
        for y in range(2, 5):
            b.set(x, y, z, fr('y'))
    for (x, z) in cols:                      # knee braces under the eaves beams
        if z in (z1, z2):
            if x > x1:
                b.set(x - 1, 4, z, stair('spruce', 'east', 'top'))
            if x < x2:
                b.set(x + 1, 4, z, stair('spruce', 'west', 'top'))
    for y in range(1, 5):
        for x in range(x1 + 1, x2):
            if x != 5:
                b.set(x, y, z1, '@wall')
    beam(b, x1, z1, x2, z2, 5)
    tops = roof_x(b, 0, W - 1, 0, 9, 5, ridge=False)
    ridge_cap(b, 0, W - 1, (4, 5), 9)
    for x in (x1, x2):
        for z in range(z1, z2 + 1):
            for y in range(6, tops[z]):
                b.set(x, y, z, '@wall')
        b.set(x, 7, 4, '@window')
        b.set(x, 7, 5, '@window')
    # saw line
    b.set(5, 1, 5, 'stonecutter[facing=south]')
    for x in (3, 4, 6, 7):
        b.set(x, 1, 5, 'stripped_spruce_log[axis=x]')
    # log piles and planks
    for x in range(2, 5):
        b.set(x, 1, 2, 'spruce_log[axis=x]')
        b.set(x, 2, 2, 'spruce_log[axis=x]')
        b.set(x, 1, 3, 'oak_log[axis=x]')
    b.set(3, 3, 2, 'spruce_log[axis=x]')
    for x in range(6, 9):
        b.set(x, 1, 2, 'spruce_planks')
        b.set(x, 2, 2, 'spruce_slab[type=bottom]')
        b.set(x, 1, 7, 'birch_log[axis=x]')
    b.set(7, 2, 7, 'birch_log[axis=x]')
    b.set(2, 1, 7, 'grindstone[face=floor,facing=east]')
    b.set(3, 1, 7, 'chest[facing=north]')
    b.set(4, 1, 7, 'barrel[facing=up]')
    b.set(8, 1, 4, 'crafting_table')
    lantern(b, 3, 4, 5)
    lantern(b, 7, 4, 5)
    lantern(b, 5, tops[4] - 1, 4)
    b.set(1, 1, 9, '@fence')
    lantern(b, 1, 2, 9, hanging=False)
    b.set(9, 1, 9, '@fence')
    lantern(b, 9, 2, 9, hanging=False)
    b.set(0, 1, 5, 'spruce_log[axis=z]')
    b.set(0, 1, 6, 'spruce_log[axis=z]')
    b.set(10, 1, 3, 'oak_log[axis=z]')
    return b


# ================================================================================ spichlerz_kamienny (granary)
def spichlerz_kamienny():
    """Masonry granary: thick walls with buttresses and ventilation slits, a loft on joists, double door and an
    upper loading door with a hoist beam and lantern, chests, barrels and hay."""
    W, H, D = 10, 12, 11
    b = B(W, H, D)
    clear(b)
    x1, x2, z1, z2 = 1, 8, 1, 9
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.walls(x1, z1, x2, z2, 1, 6, '@base')
    for (x, z) in b.corners(x1, z1, x2, z2):
        for y in range(1, 7):
            b.set(x, y, z, fr('y'))
    for z in (3, 7):                          # buttresses
        for x in (0, 9):
            b.set(x, 0, z, '@base')
            b.set(x, 1, z, '@base')
            b.set(x, 2, z, stair('cobblestone', 'east' if x == 0 else 'west'))
    beam(b, x1, z1, x2, z2, 7)
    tops = roof_z(b, 0, 10, 0, 9, 7, ridge=False)
    for z in (z1, z2):
        gable_z(b, z, x1, x2, 8, tops)
    for z in range(0, 11):
        b.set(4, 11, z, SLAB)
        b.set(5, 11, z, SLAB)
    for z in (3, 5, 7):                       # ventilation slits
        for y in (3, 5):
            b.set(x1, y, z, 'iron_bars')
            b.set(x2, y, z, 'iron_bars')
    # loft
    b.fill(x1 + 1, 4, z1 + 1, x2 - 1, 4, z2 - 1, '@floor')
    for z in range(z1 + 1, z2):
        b.set(x1 + 1, 4, z, fr('z'))
        b.set(x2 - 1, 4, z, fr('z'))
    for y in range(1, 5):
        b.set(7, y, 8, 'ladder[facing=north]')
    # double door, loading door with a hoist beam
    door(b, 4, 1, z2, hinge='left')
    door(b, 5, 1, z2, hinge='right')
    b.set(4, 3, z2, fr('x'))
    b.set(5, 3, z2, fr('x'))
    b.set(4, 8, z2, '@fence')
    b.set(5, 8, z2, '@fence')
    b.set(4, 9, z2, fr('x'))
    b.set(5, 9, z2, fr('x'))
    b.set(4, 9, 10, fr('z'))
    lantern(b, 4, 8, 10)
    b.set(3, 2, 10, 'wall_torch[facing=south]')
    b.set(6, 2, 10, 'wall_torch[facing=south]')
    # storage
    for z in (2, 3, 5, 6):
        b.set(2, 1, z, 'chest[facing=east]')
        b.set(2, 2, z, 'barrel[facing=east]')
    for z in (2, 3, 5):
        b.set(7, 1, z, 'chest[facing=west]')
        b.set(7, 2, z, 'barrel[facing=west]')
    for x in (3, 5, 6):
        b.set(x, 1, 2, 'hay_block[axis=x]')
    b.set(4, 1, 2, 'barrel[facing=up]')
    for (x, z) in ((3, 3), (3, 5), (6, 3), (6, 5), (4, 3), (5, 5)):
        b.set(x, 5, z, 'hay_block[axis=y]')
    b.set(2, 5, 2, 'chest[facing=south]')
    b.set(7, 5, 2, 'chest[facing=south]')
    b.set(3, 5, 7, 'barrel[facing=up]')
    b.set(4, 5, 7, 'barrel[facing=up]')
    lantern(b, 4, 3, 5)
    lantern(b, 5, 9, 5)
    return b


# ================================================================================ stajnia_kamienna
def stajnia_kamienna():
    """Masonry stable: stone ground floor with an arched gateway, timber-framed hay loft under a gable roof with a
    hoist beam, stalls with dividers, troughs and hay, a tack corner, hitching rails."""
    W, H, D = 13, 12, 10
    b = B(W, H, D)
    b.materials = {k: dict(v) for k, v in TIMBER.items()}
    clear(b)
    x1, x2, z1, z2 = 1, 11, 1, 7
    b.fill(x1, 0, z1, x2, 0, z2, '@base')
    for x in range(x1 + 1, x2):
        for z in range(z1 + 1, z2):
            b.set(x, 0, z, 'coarse_dirt' if z < 5 else '@floor')
    b.walls(x1, z1, x2, z2, 1, 3, '@base')
    posts(b, b.corners(x1, z1, x2, z2), 1, 3)
    b.fill(x1, 4, z1, x2, 4, z2, '@floor')
    beam(b, x1, z1, x2, z2, 4)
    timber_walls(b, x1, z1, x2, z2, 5, 5)
    beam(b, x1, z1, x2, z2, 6)
    tops = roof_x(b, 0, W - 1, 0, 8, 6)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 7, tops)
    # stalls along the back: dividers, troughs, hay
    for x in (3, 5, 7, 9):
        for z in (2, 3):
            b.set(x, 1, z, '@fence')
    for x in (2, 8, 10):
        b.set(x, 1, 2, 'hay_block[axis=y]')
    for x in (4, 6):
        b.set(x, 1, 2, 'water_cauldron[level=3]')
    # arched gateway and a side door
    for x in (5, 6):
        b.set(x, 1, z2, AIR)
        b.set(x, 2, z2, AIR)
    b.set(5, 3, z2, stair('stone_brick', 'west', 'top'))
    b.set(6, 3, z2, stair('stone_brick', 'east', 'top'))
    door(b, 8, 1, z2)
    for x in (2, 3, 10):
        b.set(x, 2, z2, '@window')
    for x in (3, 9):
        window(b, x, 2, z1, 'north')
    for z in (3, 5):
        b.set(x1, 2, z, '@window')
        b.set(x2, 2, z, '@window')
    # hay loft: hay, loading opening with a hoist beam
    b.set(6, 5, z2, AIR)
    for (x, z) in ((3, 3), (4, 3), (8, 3), (9, 3), (3, 4), (9, 4), (8, 4)):
        b.set(x, 5, z, 'hay_block[axis=y]')
    b.set(4, 5, 2, 'hay_block[axis=x]')
    b.set(5, 5, 2, 'barrel[facing=up]')
    b.set(6, 7, 8, fr('z'))
    lantern(b, 6, 6, 8)
    for y in range(1, 5):
        b.set(10, y, 6, 'ladder[facing=north]')
    # tack corner, lights, hitching rails outside
    b.set(2, 1, 6, 'chest[facing=east]')
    b.set(3, 1, 6, 'barrel[facing=up]')
    lantern(b, 2, 2, 6, hanging=False)
    lantern(b, 6, 3, 4)
    lantern(b, 6, tops[4] - 1, 4)
    for x in (1, 2, 10, 11):
        b.set(x, 1, 8, '@fence')
    b.set(4, 2, 8, 'wall_torch[facing=south]')
    b.set(9, 2, 8, 'wall_torch[facing=south]')
    return b


# ================================================================================ kosciol_drewniany
def kosciol_drewniany():
    """Wooden church (Polish timber style): log nave on a stone plinth, steep roof, narrower chancel at the back,
    a square timber bell tower over the entrance with a pyramid cap, stained glass, pews, altar with candles."""
    W, H, D = 11, 21, 20
    b = B(W, H, D)
    clear(b)
    nx1, nx2, nz1, nz2 = 1, 9, 6, 15        # nave
    cx1, cx2, cz1 = 2, 8, 1                 # chancel (z 1..6)
    b.fill(nx1, 0, nz1, nx2, 0, nz2, '@floor')
    b.ring(nx1, nz1, nx2, nz2, 0, '@base')
    b.fill(cx1, 0, cz1, cx2, 0, nz1, '@floor')
    b.ring(cx1, cz1, cx2, nz1, 0, '@base')
    b.ring(nx1, nz1, nx2, nz2, 1, '@base')
    b.ring(cx1, cz1, cx2, nz1, 1, '@base')
    for y in range(2, 6):                   # log walls
        for z in range(nz1, nz2 + 1):
            b.set(nx1, y, z, fr('z'))
            b.set(nx2, y, z, fr('z'))
        for x in range(nx1, nx2 + 1):
            b.set(x, y, nz1, fr('x'))
            b.set(x, y, nz2, fr('x'))
        for z in range(cz1, nz1):
            b.set(cx1, y, z, fr('z'))
            b.set(cx2, y, z, fr('z'))
        for x in range(cx1, cx2 + 1):
            b.set(x, y, cz1, fr('x'))
    for (x, z) in b.corners(nx1, nz1, nx2, nz2) + b.corners(cx1, cz1, cx2, nz1):
        for y in range(1, 6):
            b.set(x, y, z, fr('y'))
    for x in range(cx1 + 1, cx2):           # chancel arch
        for y in range(1, 6):
            b.set(x, y, nz1, AIR)
    b.set(cx1 + 1, 5, nz1, stair('spruce', 'east', 'top'))
    b.set(cx2 - 1, 5, nz1, stair('spruce', 'west', 'top'))
    tops = roof_z(b, nz1 - 1, nz2 + 1, 0, 10, 6)
    for z in (nz1, nz2):
        gable_z(b, z, nx1, nx2, 7, tops)
    ctops = roof_z(b, 0, nz1 - 1, cx1 - 1, cx2 + 1, 6)
    gable_z(b, cz1, cx1, cx2, 7, ctops)
    # bell tower over the entrance
    tx1, tx2, tz1, tz2 = 3, 7, 15, 19
    b.fill(tx1, 0, tz1, tx2, 0, tz2, '@floor')
    b.ring(tx1, tz1, tx2, tz2, 0, '@base')
    for y in range(1, 15):
        for (x, z) in b.corners(tx1, tz1, tx2, tz2):
            b.set(x, y, z, fr('y'))
        for x in range(tx1 + 1, tx2):
            b.set(x, y, tz2, '@wall')
            if y > tops[5]:
                b.set(x, y, tz1, '@wall')
        for z in range(tz1 + 1, tz2):
            b.set(tx1, y, z, '@wall')
            b.set(tx2, y, z, '@wall')
    for x in range(tx1 + 1, tx2):           # tower hall open to the nave
        for z in range(tz1 + 1, tz2):
            for y in range(1, 12):
                b.set(x, y, z, AIR)
        for y in range(1, 6):
            b.set(x, y, tz1, AIR)
    b.fill(tx1 + 1, 12, tz1 + 1, tx2 - 1, 12, tz2 - 1, '@floor')
    for y in range(1, 13):
        b.set(6, y, 18, 'ladder[facing=north]')
    for y in (13, 14):                      # belfry openings
        for x in (4, 5, 6):
            b.set(x, y, tz2, AIR)
            b.set(x, y, tz1, AIR)
        for z in (16, 17, 18):
            b.set(tx1, y, z, AIR)
            b.set(tx2, y, z, AIR)
    b.set(5, 14, 17, 'bell[attachment=ceiling,facing=south]')
    beam(b, tx1, tz1, tx2, tz2, 15)
    top = b.pyramid(tx1, tz1, tx2, tz2, 16, 'dark_oak', 'dark_oak_planks')
    b.set(5, top, 17, 'dark_oak_fence')
    b.set(5, top + 1, 17, 'lightning_rod[facing=up]')
    # entrance
    door(b, 5, 1, tz2)
    b.set(4, 3, tz2, stair('spruce', 'west', 'top'))
    b.set(6, 3, tz2, stair('spruce', 'east', 'top'))
    lantern(b, 5, 3, tz2 - 1)
    # windows: narrow, stained glass; round window over the chancel arch
    for z in (8, 11, 13):
        for x in (nx1, nx2):
            b.set(x, 3, z, 'yellow_stained_glass_pane')
            b.set(x, 4, z, 'yellow_stained_glass_pane')
    b.set(5, 4, cz1, 'red_stained_glass_pane')
    b.set(5, 3, cz1, 'red_stained_glass_pane')
    b.set(4, 3, cz1, 'blue_stained_glass_pane')
    b.set(6, 3, cz1, 'blue_stained_glass_pane')
    b.set(5, 9, nz1, '@window')
    # interior: pews, aisle, altar with candles, pulpit, sacristy chest
    for z in (8, 10, 12):
        for x in (2, 3, 7, 8):
            b.set(x, 1, z, stair('spruce', 'south'))
    for z in range(3, 15):
        b.set(5, 1, z, 'red_carpet')
    b.set(4, 1, 2, stair('stone_brick', 'east', 'top'))
    b.set(5, 1, 2, 'chiseled_stone_bricks')
    b.set(6, 1, 2, stair('stone_brick', 'west', 'top'))
    b.set(5, 2, 2, 'candle[candles=3,lit=true]')
    b.set(4, 2, 2, 'candle[candles=1,lit=true]')
    b.set(6, 2, 2, 'candle[candles=1,lit=true]')
    b.set(3, 1, 7, 'lectern[facing=south]')
    b.set(7, 1, 5, 'chest[facing=west]')
    b.set(3, 1, 4, 'potted_lily_of_the_valley')
    b.set(7, 1, 4, 'potted_white_tulip')
    lantern(b, 5, 9, 9)
    lantern(b, 5, 9, 13)
    lantern(b, 5, 5, 4)
    return b


# ================================================================================ wieza_okragla
def wieza_okragla():
    """Round stone tower: circular shaft with arrow slits and string courses, a timber hoarding gallery on brackets
    at the top under a conical roof, two floors and a ladder inside."""
    W, H, D = 9, 18, 9
    b = B(W, H, D)
    clear(b)
    ring = [(x, z) for x in range(1, 8) for z in range(1, 8) if 5.0 <= (x - 4) ** 2 + (z - 4) ** 2 <= 10.5]
    core = [(x, z) for x in range(1, 8) for z in range(1, 8) if (x - 4) ** 2 + (z - 4) ** 2 < 5.0]
    plat = [(x, z) for x in range(9) for z in range(9) if (x - 4) ** 2 + (z - 4) ** 2 <= 16.5]
    for (x, z) in ring:
        b.set(x, 0, z, '@base')
    for (x, z) in core:
        b.set(x, 0, z, '@floor')
    for y in range(1, 11):
        for (x, z) in ring:
            b.set(x, y, z, 'stone_bricks' if y in (4, 8) else '@base')
    for (x, z) in core:
        b.set(x, 5, z, '@floor')
    for y in range(1, 11):
        b.set(4, y, 3, 'ladder[facing=south]')
    for (x, z) in ((1, 4), (7, 4), (4, 1)):     # arrow slits
        for y in (3, 7):
            b.set(x, y, z, 'iron_bars')
    door(b, 4, 1, 7)
    b.set(3, 2, 8, 'wall_torch[facing=south]')
    b.set(5, 2, 8, 'wall_torch[facing=south]')
    # gallery: brackets, floor, railing, posts
    for (x, z) in plat:
        if (x, z) not in ring and (x, z) not in core:
            if abs(z - 4) >= abs(x - 4):
                b.set(x, 10, z, stair('spruce', 'north' if z > 4 else 'south', 'top'))
            else:
                b.set(x, 10, z, stair('spruce', 'west' if x > 4 else 'east', 'top'))
    for (x, z) in plat:
        b.set(x, 11, z, '@floor')
    b.set(4, 11, 3, 'spruce_trapdoor[facing=south,half=top,open=false]')
    for (x, z) in plat:
        if (x - 4) ** 2 + (z - 4) ** 2 >= 9.0:
            b.set(x, 12, z, '@fence')
    for (x, z) in ((0, 4), (8, 4), (4, 0), (4, 8), (1, 1), (7, 1), (1, 7), (7, 7)):
        for y in (12, 13):
            b.set(x, y, z, fr('y'))
    # conical roof: rings of stairs stepping inwards, roof block and a finial on top
    bounds = (16.5, 10.5, 5.0, 2.0)
    for step, r2 in enumerate(bounds):
        y = 14 + step
        inner = bounds[step + 1] if step + 1 < len(bounds) else -1
        for (x, z) in plat:
            d2 = (x - 4) ** 2 + (z - 4) ** 2
            if inner < d2 <= r2 and (x, z) != (4, 4):
                dx, dz = x - 4, z - 4
                if abs(dz) >= abs(dx):
                    f = 'north' if dz > 0 else 'south'
                else:
                    f = 'west' if dx > 0 else 'east'
                b.set(x, y, z, rs(f))
    b.set(4, 16, 4, '@roof_block')
    b.set(4, 17, 4, '@fence')
    lantern(b, 4, 13, 5)
    b.set(5, 1, 4, 'barrel[facing=up]')
    b.set(3, 1, 4, 'chest[facing=east]')
    b.set(5, 6, 4, 'barrel[facing=up]')
    lantern(b, 3, 9, 4)
    return b


# ------------------------------------------------------------------------------------------------ catalogue
def entries(meta):
    """(id, builder, meta) of every variant; `meta` is generate.meta (variant= adds `variant-of`)."""
    def v(id_, base, name, color, desc, function, beds, cost, tier, category, zone, max_per=0, pop=0, treasury=0,
          fblock='cobblestone', era=1):
        return meta(id_, name, color, desc, function, beds, cost, tier, category, zone, max_per, pop=pop, treasury=treasury,
                    fblock=fblock, era=era, variant=base)
    return [
        ('chata_szachulec', chata_szachulec,
         v('chata_szachulec', 'chata', 'Chata szachulcowa', '#C8A165',
           'Niska chata z muru pruskiego na kamiennej podmurówce, z nawisem dachu, okiennicami, skrzynkami kwiatów i kominem. 2 łóżka.',
           'housing', 2, 1500, 0, 'housing', 'inner')),
        ('chata_zrebowa', chata_zrebowa,
         v('chata_zrebowa', 'chata', 'Chata zrębowa', '#A0784A',
           'Chata z bali z gankiem na słupach, kamiennym kominem i okiennicami. 2 łóżka.',
           'housing', 2, 1500, 0, 'housing', 'inner')),
        ('dom_szachulec', dom_szachulec,
         v('dom_szachulec', 'dom', 'Dom szachulcowy', '#E0D3B8',
           'Dom z muru pruskiego z wysuniętym piętrem na wspornikach, lukarną, skrzynkami kwiatów i kominem. 4 łóżka na piętrze.',
           'housing', 4, 4000, 0, 'housing', 'inner')),
        ('dom_kamienny', dom_kamienny,
         v('dom_kamienny', 'dom', 'Dom kamienny', '#B8B2A8',
           'Murowany dom z okapem na wspornikach, daszkiem nad wejściem, okiennicami i dużym kominem przy szczycie. 4 łóżka.',
           'housing', 4, 4000, 0, 'housing', 'inner')),
        ('kamienica_szczytowa', kamienica_szczytowa,
         v('kamienica_szczytowa', 'kamienica', 'Kamienica szczytowa', '#E5D9C3',
           'Kamienica zwrócona schodkowym szczytem do ulicy, z kramem pod markizą w barwach kraju i skrzynkami kwiatów. 6 łóżek.',
           'housing', 6, 9000, 2, 'housing', 'inner', 0, pop=16, fblock='stone_bricks')),
        ('kamienica_podcieniowa', kamienica_podcieniowa,
         v('kamienica_podcieniowa', 'kamienica', 'Kamienica podcieniowa', '#D9CBB0',
           'Kamienica z podcieniami na kamiennych filarach, dachem czterospadowym i lukarnami. 6 łóżek.',
           'housing', 6, 9000, 2, 'housing', 'inner', 0, pop=16, fblock='stone_bricks')),
        ('kamienica_renesansowa_loggia', kamienica_renesansowa_loggia,
         v('kamienica_renesansowa_loggia', 'kamienica_renesansowa', 'Kamienica z loggią', '#F4E1C1',
           'Renesansowa kamienica z arkadową loggią na piętrze, gzymsami, boniowanym parterem i attyką. 6 łóżek.',
           'housing', 6, 9000, 1, 'housing', 'inner', 0, pop=14, era=2, fblock='stone_bricks')),
        ('karczma_zajazd', karczma_zajazd,
         v('karczma_zajazd', 'karczma', 'Zajazd', '#B5651D',
           'Przydrożny zajazd z galerią na słupach, poidłem i koniowiązem, salą z długimi stołami i kominkiem (4 łóżka dla podróżnych).',
           'tavern', 4, 7000, 1, 'civic', 'inner', 2, pop=12)),
        ('kuznia_kamienna', kuznia_kamienna,
         v('kuznia_kamienna', 'kuznia', 'Kuźnia murowana', '#8C8C8C',
           'Murowana kuźnia z paleniskiem i kominem, zadaszonym placem z kowadłem, korytem do hartowania i węglem.',
           'smithy', 0, 3500, 1, 'economy', 'outer', 2, pop=8)),
        ('piekarnia_szachulec', piekarnia_szachulec,
         v('piekarnia_szachulec', 'piekarnia', 'Piekarnia szachulcowa', '#E3B778',
           'Piekarnia z muru pruskiego z ceglanym piecem chlebowym, okienkiem sprzedaży pod markizą i kominem. Więcej jedzenia.',
           'bakery', 0, 3500, 1, 'economy', 'inner', 2, pop=8)),
        ('tartak_kryty', tartak_kryty,
         v('tartak_kryty', 'tartak', 'Tartak kryty', '#B8860B',
           'Kryta hala tartaku na kamiennych stopach, z zastrzałami, piłą i stosami bali. Najlepiej przy lesie.',
           'sawmill', 0, 3000, 1, 'economy', 'forest', 2, pop=8)),
        ('spichlerz_kamienny', spichlerz_kamienny,
         v('spichlerz_kamienny', 'spichlerz', 'Spichlerz murowany', '#D9B36A',
           'Murowany spichlerz ze szkarpami, otworami wentylacyjnymi, poddaszem i belką wyciągu. Zwiększa pojemność spichlerza kraju.',
           'granary', 0, 3000, 0, 'food', 'outer')),
        ('stajnia_kamienna', stajnia_kamienna,
         v('stajnia_kamienna', 'stajnia', 'Stajnia murowana', '#8B6A4A',
           'Murowana stajnia z bramą, boksami, korytami, stryszkiem na siano i siodlarnią.',
           'stable', 0, 4500, 1, 'economy', 'outer', 2, pop=10)),
        ('kosciol_drewniany', kosciol_drewniany,
         v('kosciol_drewniany', 'kosciol', 'Kościół drewniany', '#C49A6C',
           'Drewniany kościół z bali z wieżą-dzwonnicą nad wejściem, stromym dachem, prezbiterium, ławami i ołtarzem.',
           'church', 0, 20000, 2, 'religious', 'center', 1, pop=24, treasury=25000, fblock='stone_bricks')),
        ('wieza_okragla', wieza_okragla,
         v('wieza_okragla', 'wieza', 'Okrągła baszta', '#7F8C8D',
           'Okrągła kamienna baszta ze strzelnicami, drewnianym gankiem obronnym na wspornikach i stożkowym dachem.',
           'tower', 0, 5000, 1, 'defense', 'border', 0, fblock='stone_bricks')),
    ]
