# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Workplaces of the village production chains: glassworks (huta szkła), brickworks (cegielnia) and sheepfold
(owczarnia), each with one look variant (meta `variant-of`).

Rules the plugin relies on (WorkshopService):
- exactly ONE chest per template and no barrels: the chest is the workshop store, found automatically; it stands
  inside the outline next to a walkway;
- glassworks / brickworks: furnaces as the fire mouths of a brick kiln with a chimney, a bin of sand / clay on solid
  ground, no water and no lava;
- sheepfold: an open-air paddock on grass at ground level (layer 0), at least 5x4 cells with two air blocks above
  the grass and nothing over it, a spruce fence with exactly one closed gate on the south side, a trough (composter)
  and hay bales against the fence; next to it a shed with the chest, a loom and hay.

Medieval look (epoch 1), front (door / gate) to the south at rotation 0; walls, roofs, floors and windows use the
material slots, so the palette of the settlement level / epoch applies.
"""
from lib import B
from variants import (AIR, TIMBER, beam, chimney, clear, door, fr, gable_x, gable_z, lantern, posts, roof_x, roof_z,
                      stair, table, timber_walls, window)

FENCE = 'spruce_fence'
GATE = 'spruce_fence_gate[facing=south,in_wall=false,open=false,powered=false]'
GRASS = 'grass_block[snowy=false]'


def brick_stairs(facing, half='bottom'):
    return f'brick_stairs[facing={facing},half={half}]'


def paddock(b, x1, z1, x2, z2, gate_x, curb=None):
    """Open-air sheep paddock: grass on layer 0 (the fence ring too, or a stone curb under the fence), spruce fence
    ring on layer 1 with one closed gate in the south side at gate_x; everything above the grass stays air."""
    b.fill(x1, 0, z1, x2, 0, z2, GRASS)
    if curb:
        b.ring(x1, z1, x2, z2, 0, curb)
    b.fill(x1 + 1, 1, z1 + 1, x2 - 1, b.h - 1, z2 - 1, AIR)
    b.ring(x1, z1, x2, z2, 1, FENCE)
    b.set(gate_x, 1, z2, GATE)


# ================================================================================ huta_szkla (glassworks)
def huta_szkla():
    """Glassworks: half-timbered hall on a stone plinth (the glassmaker's workshop with the store chest), a domed
    brick glass furnace (beehive) beside it with two fire mouths and a chimney, a plank bin of sand in front."""
    W, H, D = 13, 10, 11
    b = B(W, H, D)
    clear(b)
    # hall
    x1, x2, z1, z2 = 1, 6, 1, 7
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.ring(x1, z1, x2, z2, 1, '@base')
    timber_walls(b, x1, z1, x2, z2, 2, 3)
    posts(b, b.corners(x1, z1, x2, z2), 1, 3)
    beam(b, x1, z1, x2, z2, 4)
    tops = roof_x(b, 0, 7, 0, 8, 4)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 5, tops)
    door(b, 4, 1, z2)
    window(b, 2, 2, z2, 'south', box='spruce')
    window(b, x1, 2, 4, 'west')
    window(b, 4, 2, z1, 'north', shutters=None)
    window(b, x2, 2, 4, 'east', shutters=None)
    b.set(5, 3, 8, '{kolor}_wall_banner[facing=south]')
    # workshop: store chest by the door, bench with finished glass, quench cauldron, grindstone
    b.set(2, 1, 6, 'chest[facing=east]')
    b.set(2, 1, 2, 'crafting_table')
    b.set(3, 1, 2, 'glass')
    b.set(4, 1, 2, 'glass')
    b.set(3, 2, 2, 'glass')
    b.set(5, 1, 2, 'cauldron')
    b.set(5, 1, 4, 'grindstone[face=floor,facing=east]')
    table(b, 2, 1, 4)
    b.set(2, 3, 3, 'wall_torch[facing=east]')
    lantern(b, 4, 7, 4)
    # yard and walkway under the east eave
    b.fill(7, 0, 1, 12, 0, 7, '@base')
    b.fill(8, 0, 1, 12, 0, 5, 'bricks')
    # beehive furnace x8..12, z1..5
    fx1, fz1, fx2, fz2 = 8, 1, 12, 5
    corners = set(b.corners(fx1, fz1, fx2, fz2))
    for y in (1, 2):
        for x in range(fx1, fx2 + 1):
            for z in (fz1, fz2):
                if (x, z) not in corners:
                    b.set(x, y, z, 'bricks')
        for z in range(fz1, fz2 + 1):
            for x in (fx1, fx2):
                if (x, z) not in corners:
                    b.set(x, y, z, 'bricks')
    for x in range(fx1 + 1, fx2):
        b.set(x, 3, fz1, brick_stairs('south'))
        b.set(x, 3, fz2, brick_stairs('north'))
    for z in range(fz1 + 1, fz2):
        b.set(fx1, 3, z, brick_stairs('east'))
        b.set(fx2, 3, z, brick_stairs('west'))
    b.fill(fx1 + 1, 3, fz1 + 1, fx2 - 1, 3, fz2 - 1, 'bricks')
    b.set(9, 4, 2, 'brick_slab[type=bottom]')
    b.set(11, 4, 2, 'brick_slab[type=bottom]')
    b.set(9, 4, 4, 'brick_slab[type=bottom]')
    b.set(11, 4, 4, 'brick_slab[type=bottom]')
    b.set(10, 4, 2, brick_stairs('south'))
    b.set(10, 4, 4, brick_stairs('north'))
    b.set(9, 4, 3, brick_stairs('east'))
    b.set(11, 4, 3, brick_stairs('west'))
    chimney(b, 10, 3, 4, 8)
    b.set(10, 1, 5, 'blast_furnace[facing=south]')
    b.set(8, 1, 3, 'furnace[facing=west]')
    b.set(12, 1, 5, 'coal_block')
    b.set(12, 1, 1, 'coal_block')
    b.set(7, 3, 5, 'wall_torch[facing=east]')
    # sand bin in front of the furnace (open to the yard, low plank walls)
    b.fill(8, 0, 8, 12, 0, 10, '@base')
    for z in range(8, 11):
        b.set(8, 1, z, '@wall')
        b.set(12, 1, z, '@wall')
    for x in range(9, 12):
        b.set(x, 1, 10, '@wall')
        b.set(x, 1, 8, 'sand')
        b.set(x, 1, 9, 'sand')
    b.set(10, 2, 9, 'sand')
    b.set(11, 2, 9, 'sand')
    b.set(12, 2, 10, '@fence')
    lantern(b, 12, 3, 10, hanging=False)
    return b


# ================================================================================ huta_szkla_murowana
def huta_szkla_murowana():
    """Masonry glassworks: a stone hall with buttresses; inside against the back wall a brick glass furnace with three
    working holes and a chimney through the roof, a sand bin behind a plank partition, coal, the store chest."""
    W, H, D = 13, 10, 11
    b = B(W, H, D)
    clear(b)
    x1, x2, z1, z2 = 1, 11, 1, 7
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.walls(x1, z1, x2, z2, 1, 3, '@base')
    posts(b, b.corners(x1, z1, x2, z2), 1, 3)
    beam(b, x1, z1, x2, z2, 4)
    tops = roof_x(b, 0, W - 1, 0, 8, 4)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 5, tops, timber=False)
        b.set(x, 6, 4, '@window')
    for x in (4, 8):                         # buttresses on the long walls
        for (z, facing) in ((0, 'south'), (8, 'north')):
            b.set(x, 0, z, '@base')
            b.set(x, 1, z, '@base')
            b.set(x, 2, z, stair('cobblestone', facing))
    door(b, 6, 1, z2)
    for x in (2, 10):
        window(b, x, 2, z2, 'south')
        window(b, x, 2, z1, 'north', shutters=None)
    window(b, x1, 2, 4, 'west')
    window(b, x2, 2, 4, 'east')
    b.set(5, 3, 8, 'wall_torch[facing=south]')
    b.set(7, 3, 8, 'wall_torch[facing=south]')
    # glass furnace x5..7, z2..4 with three working holes, chimney through the roof
    b.fill(5, 0, 2, 7, 0, 4, 'bricks')
    b.fill(5, 1, 2, 7, 2, 4, 'bricks')
    b.set(6, 1, 4, 'blast_furnace[facing=south]')
    b.set(5, 1, 3, 'furnace[facing=west]')
    b.set(7, 1, 3, 'furnace[facing=east]')
    b.set(5, 3, 3, brick_stairs('east'))
    b.set(7, 3, 3, brick_stairs('west'))
    b.set(6, 3, 4, brick_stairs('north'))
    b.set(5, 3, 4, 'brick_slab[type=bottom]')
    b.set(7, 3, 4, 'brick_slab[type=bottom]')
    b.set(5, 3, 2, 'bricks')
    b.set(7, 3, 2, 'bricks')
    b.set(6, 3, 3, 'bricks')
    chimney(b, 6, 2, 3, 8)
    # sand bin in the west end with a fence post and a plank lip (open towards the west fire mouth)
    for (x, z) in ((2, 2), (3, 2), (2, 3), (3, 3)):
        b.set(x, 1, z, 'sand')
    b.set(2, 2, 2, 'sand')
    b.set(4, 1, 2, '@fence')
    b.set(2, 1, 4, 'spruce_slab[type=bottom]')
    b.set(3, 1, 4, 'spruce_slab[type=bottom]')
    b.set(2, 1, 6, 'coal_block')
    b.set(3, 1, 6, 'coal_block')
    b.set(2, 2, 6, 'coal_block')
    # east end: bench, quench cauldron, the store chest, finished glass
    b.set(8, 1, 2, 'cauldron')
    b.set(9, 1, 2, 'crafting_table')
    b.set(10, 1, 2, 'chest[facing=south]')
    b.set(10, 1, 5, 'glass')
    b.set(10, 1, 6, 'glass')
    b.set(10, 2, 6, 'glass')
    b.set(9, 1, 6, 'grindstone[face=floor,facing=west]')
    table(b, 8, 1, 6)
    lantern(b, 3, 7, 4)
    lantern(b, 9, 7, 4)
    return b


# ================================================================================ cegielnia (brickworks)
def cegielnia():
    """Brickworks: a long brick chamber kiln with a barrel vault, two fire mouths to the front and a chimney; an
    open drying shed on posts with hacks of green bricks and the store chest; a plank bin of clay in front."""
    W, H, D = 13, 10, 11
    b = B(W, H, D)
    clear(b)
    # kiln x1..5, z1..6
    kx1, kz1, kx2, kz2 = 1, 1, 5, 6
    b.fill(0, 0, 0, 5, 0, 8, '@base')
    b.fill(kx1, 0, kz1, kx2, 0, kz2, 'bricks')
    b.walls(kx1, kz1, kx2, kz2, 1, 3, 'bricks')
    for z in range(kz1, kz2 + 1):
        b.set(1, 4, z, brick_stairs('east'))
        b.set(5, 4, z, brick_stairs('west'))
        for x in (2, 3, 4):
            b.set(x, 4, z, 'bricks')
        b.set(2, 5, z, brick_stairs('east'))
        b.set(4, 5, z, brick_stairs('west'))
        b.set(3, 5, z, 'bricks')
        b.set(3, 6, z, 'brick_slab[type=bottom]')
    for z in (2, 5):                         # buttresses along the sides
        b.set(0, 1, z, 'bricks')
        b.set(0, 2, z, brick_stairs('east'))
    b.set(2, 1, kz2, 'furnace[facing=south]')
    b.set(4, 1, kz2, 'furnace[facing=south]')
    b.set(2, 2, kz2, brick_stairs('west', 'top'))
    b.set(4, 2, kz2, brick_stairs('east', 'top'))
    chimney(b, 3, 2, 6, 8)
    b.set(0, 1, 7, 'coal_block')
    b.set(1, 1, 7, 'coal_block')
    b.set(0, 2, 7, 'coal_block')
    b.set(3, 3, 7, 'wall_torch[facing=south]')
    # drying shed x7..11, z1..7: posts on stone footings, closed back, open sides
    sx1, sz1, sx2, sz2 = 7, 1, 11, 7
    b.fill(6, 0, 0, 12, 0, 8, 'coarse_dirt')
    b.fill(sx1 + 1, 0, sz1 + 1, sx2 - 1, 0, sz2 - 1, '@floor')
    cols = [(x, z) for x in (sx1, sx2) for z in (sz1, 4, sz2)] + [(9, sz1)]
    for (x, z) in cols:
        b.set(x, 0, z, '@base')
        posts(b, [(x, z)], 1, 3)
    for x in (8, 10):
        for y in range(1, 4):
            b.set(x, y, sz1, '@wall')
    beam(b, sx1, sz1, sx2, sz2, 4)
    for x in (sx1, sx2):                     # knee braces under the side beams
        for z in (sz1, 4, sz2):
            if z < sz2:
                b.set(x, 3, z + 1, stair('spruce', 'north', 'top'))
            if z > sz1:
                b.set(x, 3, z - 1, stair('spruce', 'south', 'top'))
    tops = roof_x(b, 6, 12, 0, 8, 4)
    for x in (sx1, sx2):
        gable_x(b, x, sz1, sz2, 5, tops)
    # hacks of green bricks drying, workbench, the store chest
    for x in (8, 9, 10):
        b.set(x, 1, 3, 'mud_bricks')
        b.set(x, 2, 3, 'mud_brick_slab[type=bottom]')
        b.set(x, 1, 5, 'mud_brick_slab[type=bottom]')
    b.set(8, 1, 2, 'crafting_table')
    b.set(10, 1, 2, 'brick_slab[type=bottom]')
    b.set(8, 1, 6, 'chest[facing=east]')
    lantern(b, 9, 7, 4)
    b.set(10, 1, 6, '@fence')
    lantern(b, 10, 2, 6, hanging=False)
    # clay bin in front of the shed
    b.fill(8, 0, 9, 12, 0, 10, '@base')
    for z in (9, 10):
        b.set(8, 1, z, '@wall')
        b.set(12, 1, z, '@wall')
    for x in range(9, 12):
        b.set(x, 1, 10, '@wall')
        b.set(x, 1, 9, 'clay')
    b.set(10, 2, 9, 'clay')
    b.set(12, 2, 10, '@fence')
    lantern(b, 12, 3, 10, hanging=False)
    return b


# ================================================================================ cegielnia_kryta
def cegielnia_kryta():
    """Covered brickworks: a closed half-timbered drying barn with a door, racks of green bricks and a clay bin
    inside; beside it a square brick kiln with a stepped top, two fire mouths and a chimney."""
    W, H, D = 13, 10, 11
    b = B(W, H, D)
    b.materials = {k: dict(v) for k, v in TIMBER.items()}
    clear(b)
    # barn x1..6, z1..7
    x1, x2, z1, z2 = 1, 6, 1, 7
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.ring(x1, z1, x2, z2, 1, '@base')
    timber_walls(b, x1, z1, x2, z2, 2, 3)
    posts(b, b.corners(x1, z1, x2, z2), 1, 3)
    beam(b, x1, z1, x2, z2, 4)
    tops = roof_x(b, 0, 7, 0, 8, 4)
    for x in (x1, x2):
        gable_x(b, x, z1, z2, 5, tops)
    door(b, 4, 1, z2)
    window(b, 2, 2, z2, 'south')
    for z in (2, 4, 6):                      # ventilation slats of the drying barn
        b.set(x1, 2, z, 'spruce_trapdoor[facing=west,half=bottom,open=true]')
        b.set(x2, 2, z, 'spruce_trapdoor[facing=east,half=bottom,open=true]')
    window(b, 4, 2, z1, 'north', shutters=None)
    b.set(5, 3, 8, '{kolor}_wall_banner[facing=south]')
    # inside: racks of green bricks, clay bin, workbench, the store chest by the door
    for z in (2, 3, 4):
        b.set(2, 1, z, 'spruce_slab[type=top]')
        b.set(2, 2, z, 'mud_brick_slab[type=bottom]')
    for (x, z) in ((4, 2), (5, 2), (5, 3)):
        b.set(x, 1, z, 'clay')
    b.set(5, 2, 2, 'clay')
    b.set(4, 1, 3, 'spruce_slab[type=bottom]')
    b.set(5, 1, 4, 'spruce_slab[type=bottom]')
    b.set(2, 1, 6, 'chest[facing=east]')
    b.set(5, 1, 6, 'crafting_table')
    lantern(b, 3, 7, 4)
    b.set(3, 3, 2, 'wall_torch[facing=south]')
    # kiln x8..12, z1..5 (walkway under the barn eave at x7)
    b.fill(7, 0, 1, 12, 0, 7, '@base')
    kx1, kz1, kx2, kz2 = 8, 1, 12, 5
    b.fill(kx1, 0, kz1, kx2, 0, kz2, 'bricks')
    b.walls(kx1, kz1, kx2, kz2, 1, 4, 'bricks')
    b.fill(kx1 + 1, 4, kz1 + 1, kx2 - 1, 4, kz2 - 1, 'bricks')
    for (x, z) in b.corners(kx1, kz1, kx2, kz2):
        for y in range(1, 5):
            b.set(x, y, z, '@base')
    b.pyramid(kx1, kz1, kx2, kz2, 5, 'brick', 'bricks')
    chimney(b, 10, 3, 6, 8)
    b.set(10, 1, kz2, 'blast_furnace[facing=south]')
    b.set(kx1, 1, 3, 'furnace[facing=west]')
    b.set(10, 2, kz2, brick_stairs('north', 'top'))
    b.set(12, 1, 6, 'coal_block')
    b.set(11, 1, 6, 'coal_block')
    b.set(12, 2, 6, 'coal_block')
    b.set(7, 3, 3, 'wall_torch[facing=east]')
    # finished bricks stacked in the yard
    b.fill(8, 0, 8, 11, 0, 9, '@base')
    for x in (8, 9):
        b.set(x, 1, 9, 'bricks')
    b.set(8, 2, 9, 'brick_slab[type=bottom]')
    b.set(11, 1, 9, '@fence')
    lantern(b, 11, 2, 9, hanging=False)
    return b


# ================================================================================ owczarnia (sheepfold)
def owczarnia():
    """Sheepfold: a timber shed with a gable to the street (store chest, loom, hay, wool) and beside it an open-air
    paddock on grass with a spruce fence, a gate to the front, a trough and hay bales."""
    W, H, D = 13, 9, 11
    b = B(W, H, D)
    clear(b)
    # shed x1..5, z1..7, gable roof along z
    x1, x2, z1, z2 = 1, 5, 1, 7
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.walls(x1, z1, x2, z2, 1, 3, '@wall')
    posts(b, b.corners(x1, z1, x2, z2) + [(x1, 4), (x2, 4)], 1, 3)
    beam(b, x1, z1, x2, z2, 4)
    tops = roof_z(b, 0, 8, 0, 6, 4)
    for z in (z1, z2):
        gable_z(b, z, x1, x2, 5, tops)
    b.set(3, 5, z2, '@window')
    door(b, 3, 1, z2)
    window(b, x1, 2, 2, 'west')
    window(b, x1, 2, 6, 'west')
    window(b, x2, 2, 2, 'east', shutters=None)
    b.set(2, 3, 8, 'wall_torch[facing=south]')
    b.set(4, 3, 8, '{kolor}_wall_banner[facing=south]')
    # inside: hay, loom, wool, the store chest by the door
    b.set(2, 1, 2, 'hay_block[axis=y]')
    b.set(2, 2, 2, 'hay_block[axis=y]')
    b.set(3, 1, 2, 'hay_block[axis=x]')
    b.set(4, 1, 2, 'hay_block[axis=y]')
    b.set(4, 1, 4, 'loom[facing=west]')
    b.set(2, 1, 4, 'white_wool')
    b.set(2, 1, 5, 'white_carpet')
    b.set(2, 1, 6, 'chest[facing=east]')
    b.set(4, 1, 6, 'composter[level=0]')
    lantern(b, 3, 6, 4)
    # paddock x6..12, z1..8 (grass x7..11, z2..7), gate at x9 to the front
    paddock(b, 6, 1, 12, 8, 9)
    b.set(7, 1, 2, 'hay_block[axis=y]')
    b.set(8, 1, 2, 'hay_block[axis=x]')
    b.set(10, 1, 2, 'composter[level=0]')
    b.set(11, 1, 2, 'composter[level=0]')
    for (x, z) in ((12, 1), (6, 8), (12, 8)):
        lantern(b, x, 2, z, hanging=False)
    return b


# ================================================================================ owczarnia_kamienna
def owczarnia_kamienna():
    """Stone sheepfold: a masonry shed with a timber hay loft, a loading door and a hoist beam under a gable to the
    street (store chest, loom, hay, wool), and a paddock on grass with a stone curb under the spruce fence."""
    W, H, D = 13, 10, 11
    b = B(W, H, D)
    b.materials = {k: dict(v) for k, v in TIMBER.items()}
    clear(b)
    # shed x7..11, z1..7: stone ground floor, timber loft, gable roof along z
    x1, x2, z1, z2 = 7, 11, 1, 7
    b.fill(x1, 0, z1, x2, 0, z2, '@floor')
    b.ring(x1, z1, x2, z2, 0, '@base')
    b.walls(x1, z1, x2, z2, 1, 3, '@base')
    b.fill(x1, 4, z1, x2, 4, z2, '@floor')
    beam(b, x1, z1, x2, z2, 4)
    timber_walls(b, x1, z1, x2, z2, 5, 5)
    posts(b, b.corners(x1, z1, x2, z2), 1, 5)
    beam(b, x1, z1, x2, z2, 6)
    tops = roof_z(b, 0, 8, 6, 12, 6, ridge=False)
    for z in (z1, z2):
        gable_z(b, z, x1, x2, 7, tops)
    door(b, 9, 1, z2)
    b.set(9, 3, z2, fr('x'))
    window(b, 8, 2, z1, 'north', shutters=None)
    window(b, 10, 2, z1, 'north', shutters=None)
    window(b, x2, 2, 4, 'east')
    b.set(x1, 2, 4, '@window')
    b.set(10, 2, z2, '@window')
    b.set(8, 2, z2, '@window')
    # loft: loading door with a hoist beam, hay, ladder
    b.set(9, 5, z2, AIR)
    b.set(9, 7, 8, fr('z'))
    lantern(b, 9, 6, 8)
    for (x, z) in ((8, 2), (10, 2), (8, 3), (10, 3), (8, 5)):
        b.set(x, 5, z, 'hay_block[axis=y]')
    for y in range(1, 5):
        b.set(10, y, 6, 'ladder[facing=north]')
    # ground floor: hay, loom, wool, the store chest by the door
    b.set(8, 1, 2, 'hay_block[axis=y]')
    b.set(9, 1, 2, 'hay_block[axis=x]')
    b.set(8, 2, 2, 'hay_block[axis=y]')
    b.set(10, 1, 2, 'loom[facing=south]')
    b.set(10, 1, 3, 'white_wool')
    b.set(10, 2, 3, 'white_carpet')
    b.set(8, 1, 6, 'chest[facing=east]')
    b.set(8, 1, 4, 'composter[level=0]')
    lantern(b, 9, 3, 4)
    b.set(12, 1, 8, '@fence')
    lantern(b, 12, 2, 8, hanging=False)
    # paddock x0..6, z1..8 (grass x1..5, z2..7) with a stone curb, gate at x3 to the front
    paddock(b, 0, 1, 6, 8, 3, curb='@base')
    b.set(5, 1, 2, 'hay_block[axis=y]')
    b.set(5, 1, 3, 'hay_block[axis=z]')
    b.set(1, 1, 2, 'composter[level=0]')
    b.set(2, 1, 2, 'composter[level=0]')
    for (x, z) in ((0, 1), (0, 8), (6, 8)):
        lantern(b, x, 2, z, hanging=False)
    return b


# ------------------------------------------------------------------------------------------------ catalogue
def entries(meta):
    """(id, builder, meta) of the workshop templates; `meta` is generate.meta (variant= adds `variant-of`)."""
    def v(id_, base, name, color, desc, function, beds, cost, tier, category, zone, max_per=0, pop=0, treasury=0,
          fblock='cobblestone', era=1):
        return meta(id_, name, color, desc, function, beds, cost, tier, category, zone, max_per, pop=pop, treasury=treasury,
                    fblock=fblock, era=era, variant=base)
    return [
        ('huta_szkla', huta_szkla,
         meta('huta_szkla', 'Huta szkła', '#7FB8C8',
              'Huta z kopułowym piecem szklarskim i zasiekiem piasku. Hutnik kopie piasek z brzegów na ziemi kraju i wytapia '
              'szkło (paliwo: węgiel albo węgiel drzewny).',
              'glassworks', 0, 4000, 1, 'economy', 'shore', 2, pop=8)),
        ('huta_szkla_murowana', huta_szkla_murowana,
         v('huta_szkla_murowana', 'huta_szkla', 'Huta szkła murowana', '#6FA3B3',
           'Murowana huta ze szkarpami, piecem szklarskim o trzech otworach roboczych, kominem i zasiekiem piasku. Hutnik '
           'kopie piasek z brzegów na ziemi kraju i wytapia szkło (paliwo: węgiel albo węgiel drzewny).',
           'glassworks', 0, 4000, 1, 'economy', 'shore', 2, pop=8)),
        ('cegielnia', cegielnia,
         meta('cegielnia', 'Cegielnia', '#B5523B',
              'Cegielnia z ceglanym piecem komorowym, wiatą suszarni i zasiekiem gliny. Ceglarz kopie glinę z rzek i '
              'brzegów, wypala cegły i terakotę.',
              'brickworks', 0, 3500, 1, 'economy', 'shore', 2, pop=8)),
        ('cegielnia_kryta', cegielnia_kryta,
         v('cegielnia_kryta', 'cegielnia', 'Cegielnia kryta', '#A0462F',
           'Kryta suszarnia z muru pruskiego z regałami surówki i zasiekiem gliny oraz ceglany piec ze schodkowym '
           'sklepieniem i kominem. Ceglarz kopie glinę z rzek i brzegów, wypala cegły i terakotę.',
           'brickworks', 0, 3500, 1, 'economy', 'shore', 2, pop=8)),
        ('owczarnia', owczarnia,
         meta('owczarnia', 'Owczarnia', '#E8E0C8',
              'Szopa z krosnem i sianem oraz zagroda ze stadem owiec. Pasterz strzyże wełnę i wiąże bele siana z pszenicy '
              'z pól; z wełny i drewna powstają łóżka.',
              'sheepfold', 0, 2500, 0, 'economy', 'farm', 2, pop=6)),
        ('owczarnia_kamienna', owczarnia_kamienna,
         v('owczarnia_kamienna', 'owczarnia', 'Owczarnia kamienna', '#CFC6AE',
           'Kamienna owczarnia ze stryszkiem na siano, krosnem i zagrodą na kamiennym krawężniku. Pasterz strzyże wełnę i '
           'wiąże bele siana z pszenicy z pól; z wełny i drewna powstają łóżka.',
           'sheepfold', 0, 2500, 0, 'economy', 'farm', 2, pop=6)),
    ]
