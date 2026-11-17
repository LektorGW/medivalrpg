# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""MEDIVALRPG spawn — procedural design.

Coordinates are schematic-local: x = east, z = south, y = up.
G is the ground (paving) level; the foundation is y = 0..G-1.
North (z = 0) holds the main gate.
"""
from __future__ import annotations

import json
import math
import os

from . import nbt
from .blocks import Registry, is_full_solid
from .world import Canvas, hrand, DIRS, OPP

N = 69          # width = length (odd -> exact centre block)
H = 32          # total height: foundation 4 + paving 1 + 27 blocks above the ground
G = 4           # paving / ground level
C = N // 2      # centre column (34)
PLAZA_R = 14
PLAZA_CHAMFER = 21

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "..", "data", "banner_patterns-26.3.json"), encoding="utf-8") as _f:
    BANNER_PATTERNS = set(json.load(_f))
DYE_COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
              "cyan", "purple", "blue", "brown", "green", "red", "black"}
TEXT_COLORS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
               "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"}

# approximate glyph advance widths of the vanilla font (incl. 1 px spacing)
_GLYPH = {**{ch: 2 for ch in "i!.,:;|'"}, **{ch: 3 for ch in "l`"}, **{ch: 4 for ch in "tI[] ł"},
          **{ch: 5 for ch in "fk<>{}()\"*"}, **{ch: 7 for ch in "@~"}}
SIGN_WIDTH = 90             # SignBlockEntity max line width
HANGING_SIGN_WIDTH = 60     # HangingSignBlockEntity max line width


def text_width(s: str, bold: bool = False) -> int:
    return sum(_GLYPH.get(ch, 6) + (1 if bold else 0) for ch in s)


def base_name(state):
    if state is None:
        return None
    return state.split("[", 1)[0].split(":", 1)[-1]


def gold(t):
    return {"text": t, "color": "gold", "bold": True}


def white(t):
    return {"text": t, "color": "white"}


def title(t):
    return {"text": t, "color": "dark_red", "bold": True}


class Spawn:
    # heraldry of MEDIVALRPG: red field with gold
    CREST = [("rhombus", "yellow"), ("circle", "red"), ("border", "yellow")]
    CREST_CROSS = [("straight_cross", "yellow"), ("border", "yellow")]
    CREST_CAPE = [("stripe_bottom", "yellow"), ("border", "red")]

    def __init__(self, reg: Registry):
        self.reg = reg
        self.c = Canvas(reg, N, H, N)
        self.S = self.c.S

    # ================================================================ helpers
    def _st(self, name, **props):
        if callable(name):
            return name
        if ":" in name or "[" in name:
            if props:
                raise ValueError("Props given for a full state string")
            return name
        return self.S(name, **props)

    def set(self, x, y, z, name, be=None, **props):
        self.c.set(x, y, z, self._st(name, **props), be)

    def fill(self, x1, y1, z1, x2, y2, z2, name, **props):
        self.c.fill(x1, y1, z1, x2, y2, z2, self._st(name, **props))

    def get(self, x, y, z):
        return self.c.get(x, y, z)

    def empty(self, x, y, z):
        return self.c.get(x, y, z) is None

    def stairs(self, x, y, z, mat, facing, half="bottom"):
        self.set(x, y, z, f"{mat}_stairs", facing=facing, half=half)

    def mix(self, salt: str, choices):
        """Texture: deterministic weighted choice per position. choices = [(name, weight)]."""
        states = [(self.S(s), w) for s, w in choices]
        total = sum(w for _, w in states)

        def f(x, y, z):
            r = hrand(x, y, z, salt) * total
            for st, w in states:
                r -= w
                if r < 0:
                    return st
            return states[-1][0]
        return f

    def blend(self, salt: str, a: dict, b: dict, t: float, x, y, z):
        """Weighted choice interpolated between weight tables a and b (gradient)."""
        names = sorted(set(a) | set(b))
        weights = [(n, (1 - t) * a.get(n, 0) + t * b.get(n, 0)) for n in names]
        total = sum(w for _, w in weights)
        r = hrand(x, y, z, salt) * total
        for n, w in weights:
            r -= w
            if r < 0:
                return self.S(n)
        return self.S(weights[-1][0])

    def trapdoor_around(self, cells, mat, y):
        """Open trapdoors hugging the outside of a set of (x, z) cells at height y (flower-box sides).
        An open trapdoor with facing=d sits on the side of its block opposite to d, i.e. against the box."""
        cells = set(cells)
        for (x, z) in sorted(cells):
            for d, (dx, dz) in DIRS.items():
                n = (x + dx, z + dz)
                if n in cells or not self.empty(n[0], y, n[1]):
                    continue
                self.set(n[0], y, n[1], f"{mat}_trapdoor", facing=d, open=True, half="bottom")

    @staticmethod
    def ring(x1, z1, x2, z2):
        """Ordered perimeter cells of a rectangle."""
        cells = [(x, z1) for x in range(x1, x2 + 1)]
        cells += [(x2, z) for z in range(z1 + 1, z2 + 1)]
        cells += [(x, z2) for x in range(x2 - 1, x1 - 1, -1)]
        cells += [(x1, z) for z in range(z2 - 1, z1, -1)]
        return cells

    # ---------------------------------------------------------- block entities
    @staticmethod
    def _component(line):
        if isinstance(line, str):
            line = {"text": line}
        d = {"text": line["text"]}
        if "color" in line:
            if line["color"] not in TEXT_COLORS:
                raise ValueError(f"Bad text colour {line['color']}")
            d["color"] = line["color"]
        if line.get("bold"):
            d["bold"] = nbt.Byte(1)
        return d

    def sign_be(self, lines, glowing=False, color="black", back=None):
        """Sign block entity for data version 5023 (26.3): messages are NBT text components."""
        if color not in DYE_COLORS:
            raise ValueError(color)

        def side(ls):
            ls = list(ls) + [""] * (4 - len(ls))
            if len(ls) != 4:
                raise ValueError("A sign has exactly 4 lines")
            return {"messages": nbt.List([self._component(l) for l in ls], elem_type=nbt.COMPOUND),
                    "color": color, "has_glowing_text": nbt.Byte(1 if glowing else 0)}
        return {"front_text": side(lines), "back_text": side(back if back is not None else []),
                "is_waxed": nbt.Byte(1)}

    @staticmethod
    def check_sign_width(lines, limit):
        for line in lines:
            t = line if isinstance(line, str) else line["text"]
            b = False if isinstance(line, str) else bool(line.get("bold"))
            w = text_width(t, b)
            if w > limit:
                raise ValueError(f"Sign line too wide ({w}px > {limit}px): {t!r}")

    def wall_sign(self, x, y, z, wood, facing, lines, glowing=False):
        self.check_sign_width(lines, SIGN_WIDTH)
        self.set(x, y, z, f"{wood}_wall_sign", be=("minecraft:sign", self.sign_be(lines, glowing)), facing=facing)

    def hanging_sign(self, x, y, z, wood, rotation, lines):
        """Ceiling hanging sign (both sides carry the text). rotation: 0 = faces south, 4 west, 8 north, 12 east."""
        self.check_sign_width(lines, HANGING_SIGN_WIDTH)
        self.set(x, y, z, f"{wood}_hanging_sign", be=("minecraft:hanging_sign", self.sign_be(lines, back=lines)),
                 rotation=rotation, attached=False)

    def wall_hanging_sign(self, x, y, z, wood, facing, lines):
        """Wall hanging sign: facing north/south attaches to an east/west neighbour and vice versa."""
        self.check_sign_width(lines, HANGING_SIGN_WIDTH)
        self.set(x, y, z, f"{wood}_wall_hanging_sign", be=("minecraft:hanging_sign", self.sign_be(lines, back=lines)),
                 facing=facing)

    @staticmethod
    def banner_be(patterns):
        out = []
        for pat, col in patterns:
            pid = pat if ":" in pat else "minecraft:" + pat
            if pid not in BANNER_PATTERNS:
                raise ValueError(f"Unknown banner pattern {pid}")
            if col not in DYE_COLORS:
                raise ValueError(f"Unknown dye colour {col}")
            out.append({"pattern": pid, "color": col})
        return {"patterns": nbt.List(out, elem_type=nbt.COMPOUND)}

    def wall_banner(self, x, y, z, facing, color="red", patterns=None):
        pats = self.CREST if patterns is None else patterns
        self.set(x, y, z, f"{color}_wall_banner", be=("minecraft:banner", self.banner_be(pats)), facing=facing)

    def banner(self, x, y, z, rotation, color="red", patterns=None):
        pats = self.CREST if patterns is None else patterns
        self.set(x, y, z, f"{color}_banner", be=("minecraft:banner", self.banner_be(pats)), rotation=rotation)

    # ------------------------------------------------------------- primitives
    def gable_roof(self, x1, x2, z1, z2, y0, mat, axis="x", full=None, slab=None):
        """45° gable roof of stairs. axis = ridge direction. Ranges include the overhang."""
        full = full or f"{mat}_planks"
        slab = slab or f"{mat}_slab"
        w = (z2 - z1 + 1) if axis == "x" else (x2 - x1 + 1)
        lo, hi = (z1, z2) if axis == "x" else (x1, x2)
        f_lo, f_hi = ("south", "north") if axis == "x" else ("east", "west")
        along = range(x1, x2 + 1) if axis == "x" else range(z1, z2 + 1)
        for k in range((w + 1) // 2):
            a, b, y = lo + k, hi - k, y0 + k
            for t in along:
                P = (lambda u: (t, u)) if axis == "x" else (lambda u: (u, t))
                if a < b:
                    xa, za = P(a); xb, zb = P(b)
                    self.stairs(xa, y, za, mat, f_lo); self.stairs(xb, y, zb, mat, f_hi)
                    if b - a == 1:
                        self.set(xa, y + 1, za, slab, type="bottom"); self.set(xb, y + 1, zb, slab, type="bottom")
                else:
                    xa, za = P(a)
                    self.set(xa, y - 1, za, full); self.set(xa, y, za, slab, type="bottom")

    def gable_fill(self, plane, a1, a2, roof1, roof2, roof_y0, mat, axis="x", ymin=None):
        """Fill the triangle under a gable_roof in a wall plane (below the roof stairs).
        axis='x': plane is an x coordinate, a = z; axis='z': plane is a z coordinate, a = x."""
        for a in range(a1, a2 + 1):
            k = min(a - roof1, roof2 - a)
            for y in range(max(roof_y0, ymin if ymin is not None else roof_y0), roof_y0 + k):
                if axis == "x":
                    self.set(plane, y, a, mat)
                else:
                    self.set(a, y, plane, mat)

    def _stair_ring(self, x, z, s, y, mat):
        for i in range(s):
            self.stairs(x + i, y, z, mat, "south"); self.stairs(x + i, y, z + s - 1, mat, "north")
        for i in range(1, s - 1):
            self.stairs(x, y, z + i, mat, "east"); self.stairs(x + s - 1, y, z + i, mat, "west")

    def spire(self, x1, z1, size, y0, mat, full, top=None, eave=True, steep=2):
        """Witch-hat spire on a size x size (odd) tower top: optional overhanging eave ring, then rings of
        stairs separated by `steep` vertical layers. Returns the first free y above the tip."""
        y = y0
        if eave:
            self._stair_ring(x1 - 1, z1 - 1, size + 2, y, mat)
            self.fill(x1, y, z1, x1 + size - 1, y, z1 + size - 1, full)
            y += 1
        s, x, z = size, x1, z1
        while s >= 3:
            self._stair_ring(x, z, s, y, mat)
            self.fill(x + 1, y, z + 1, x + s - 2, y, z + s - 2, full)
            y += 1
            for _ in range(steep):
                self.fill(x + 1, y, z + 1, x + s - 2, y, z + s - 2, full)
                y += 1
            s -= 2; x += 1; z += 1
        if top is None:
            top = self.S(f"{mat}_wall") if self.reg.has(f"{mat}_wall") else self.S(full)
        self.set(x, y, z, top)
        return y + 1

    def crenellate(self, cells, y, mat):
        for i, (x, z) in enumerate(cells):
            if i % 2 == 0:
                self.set(x, y, z, mat)

    def bench(self, x, z, sit, length, along="x", wood="spruce"):
        """Bench of bottom stairs (players sit with /usiadz or right click). Sitter faces `sit`,
        so the stairs face the opposite way. Open trapdoors as armrests."""
        cells = []
        for i in range(length):
            cx, cz = (x + i, z) if along == "x" else (x, z + i)
            self.stairs(cx, G + 1, cz, wood, OPP[sit])
            cells.append((cx, cz))
        (ax, az), (bx, bz) = cells[0], cells[-1]
        if along == "x":
            self.set(ax - 1, G + 1, az, f"{wood}_trapdoor", facing="west", open=True)
            self.set(bx + 1, G + 1, bz, f"{wood}_trapdoor", facing="east", open=True)
        else:
            self.set(ax, G + 1, az - 1, f"{wood}_trapdoor", facing="north", open=True)
            self.set(bx, G + 1, bz + 1, f"{wood}_trapdoor", facing="south", open=True)

    def planter_box(self, cells, y, flowers=("poppy", "dandelion", "cornflower", "azure_bluet", "oxeye_daisy", "allium")):
        """Raised flower box: soil at y, flowers at y+1, spruce trapdoor sides."""
        for (x, z) in cells:
            self.set(x, y, z, "podzol" if hrand(x, y, z, "pb") < .5 else "rooted_dirt")
            self.set(x, y + 1, z, flowers[int(hrand(x, y, z, "fl") * len(flowers))])
        self.trapdoor_around(cells, "spruce", y)

    def lamp_post(self, x, z, arm):
        """Wooden street lamp: dark oak post with an arm and a hanging lantern."""
        for y in range(G + 1, G + 5):
            self.set(x, y, z, "dark_oak_fence")
        dx, dz = DIRS[arm]
        self.set(x + dx, G + 4, z + dz, "dark_oak_fence")
        self.set(x + dx, G + 3, z + dz, "lantern", hanging=True)

    # -------------------------------------------------------------- materials
    def wall_tex(self, salt="wall"):
        low = {"stone_bricks": 30, "mossy_stone_bricks": 22, "mossy_cobblestone": 20, "cobblestone": 20,
               "cracked_stone_bricks": 8}
        high = {"stone_bricks": 52, "cracked_stone_bricks": 12, "mossy_stone_bricks": 10, "cobblestone": 9,
                "andesite": 7, "mossy_cobblestone": 4, "tuff_bricks": 6}

        def f(x, y, z):
            t = max(0.0, min(1.0, (y - G - 1) / 3))
            return self.blend(salt, low, high, t, x, y, z)
        return f

    def cobble_tex(self, salt="cob"):
        return self.mix(salt, [("cobblestone", 40), ("stone_bricks", 25), ("mossy_cobblestone", 12),
                               ("andesite", 13), ("mossy_stone_bricks", 10)])

    # ================================================================== LAYOUT
    def build(self) -> Canvas:
        self.ground()
        self.pave_roads()
        self.pave_plaza()
        self.compass_rose()
        self.town_wall()
        self.main_gate()
        for side in ("east", "west", "south"):
            self.small_gate(side)
        for cx, cz in ((2, 2), (66, 2), (2, 66), (66, 66)):
            self.corner_tower(cx, cz)
        self.fountain(C, 25)
        self.notice_board()
        self.bank()
        self.chapel()
        self.graveyard()
        self.tavern()
        self.forge()
        self.stable()
        self.market()
        self.training_yard()
        self.well_and_gardens()
        self.plaza_furniture()
        self.street_lamps()
        self.trees()
        self.yard_props()
        self.decorate_ground()
        self.c.finalize()
        return self.c

    # ------------------------------------------------------------------ ground
    def ground(self):
        stone = self.mix("found", [("stone", 62), ("andesite", 22), ("tuff", 9), ("cobblestone", 7)])   # no gravity blocks
        dirt = self.mix("dirt", [("dirt", 85), ("coarse_dirt", 15)])
        self.fill(0, 0, 0, N - 1, 1, N - 1, stone)
        self.fill(0, 2, 0, N - 1, 3, N - 1, dirt)
        self.fill(0, G, 0, N - 1, G, N - 1, "grass_block")

    @staticmethod
    def in_plaza(x, z):
        dx, dz = abs(x - C), abs(z - C)
        return dx <= PLAZA_R and dz <= PLAZA_R and dx + dz <= PLAZA_CHAMFER

    def _is_road(self, x, z):
        if not (0 <= x < N and 0 <= z < N):
            return False
        return (C - 2 <= x <= C + 2 or C - 2 <= z <= C + 2) and not self.in_plaza(x, z)

    def pave_roads(self):
        centre = {"cobblestone": 28, "stone_bricks": 20, "andesite": 20, "stone": 10, "gravel": 7,
                  "mossy_cobblestone": 8, "polished_andesite": 7}
        edge = {"gravel": 22, "dirt_path": 26, "coarse_dirt": 14, "cobblestone": 16, "andesite": 14,
                "mossy_cobblestone": 8}
        outside = {"dirt_path": 40, "gravel": 25, "coarse_dirt": 20, "cobblestone": 15}
        for x in range(N):
            for z in range(N):
                if not self._is_road(x, z):
                    continue
                if C - 2 <= x <= C + 2:
                    lat, along = x - (C - 2), abs(z - C)
                else:
                    lat, along = z - (C - 2), abs(x - C)
                if along >= C - 1:                      # beyond the wall
                    st = self.blend("rout", outside, outside, 0, x, G, z)
                else:                                   # wear grows towards the gates
                    t = 1.0 if lat in (0, 4) else 0.35 * (along - PLAZA_R) / (C - PLAZA_R)
                    st = self.blend("road", centre, edge, max(0.0, min(1.0, t)), x, G, z)
                self.set(x, G, z, st)
        shoulder = self.mix("sh", [("dirt_path", 2), ("coarse_dirt", 2), ("gravel", 1)])
        for x in range(N):
            for z in range(N):
                if base_name(self.get(x, G, z)) != "grass_block":
                    continue
                if any(self._is_road(x + dx, z + dz) for dx, dz in DIRS.values()) and hrand(x, G, z, "shoulder") < .35:
                    self.set(x, G, z, shoulder)

    def pave_plaza(self):
        inner = {"stone_bricks": 40, "polished_andesite": 22, "andesite": 12, "cracked_stone_bricks": 10,
                 "mossy_stone_bricks": 4, "cobblestone": 6, "stone": 6}
        outer = {"cobblestone": 24, "andesite": 20, "stone_bricks": 22, "gravel": 6, "mossy_cobblestone": 9,
                 "stone": 9, "polished_andesite": 6, "cracked_stone_bricks": 4}
        for x in range(N):
            for z in range(N):
                if not self.in_plaza(x, z):
                    continue
                if any(not self.in_plaza(x + dx, z + dz) for dx, dz in DIRS.values()):
                    mouth = C - 2 <= x <= C + 2 or C - 2 <= z <= C + 2
                    st = "polished_andesite" if mouth else ("chiseled_stone_bricks" if (x + z) % 6 == 0 else "stone_bricks")
                    self.set(x, G, z, st)
                    continue
                t = max(0.0, min(1.0, (math.hypot(x - C, z - C) - 6) / 11))
                self.set(x, G, z, self.blend("plaza", inner, outer, t, x, G, z))

    def compass_rose(self):
        """Wind rose mosaic; its centre (lodestone) is the spawn point and the paste origin."""
        for dx in range(-4, 5):
            for dz in range(-4, 5):
                d = math.hypot(dx, dz)
                if d > 4.6:
                    continue
                st = "polished_deepslate" if d > 3.6 else "polished_andesite"
                if dx == 0 or dz == 0:
                    m = max(abs(dx), abs(dz))
                    if m <= 3:
                        st = "red_terracotta" if (dx == 0 and dz < 0) else "polished_diorite"
                    else:
                        st = "chiseled_stone_bricks"
                elif abs(dx) == abs(dz) and abs(dx) <= 2:
                    st = "polished_tuff" if abs(dx) == 1 else "polished_deepslate"
                self.set(C + dx, G, C + dz, st)
        self.set(C, G, C, "lodestone")
        self.c.markers["spawn"] = (C, G + 1, C)

    # ----------------------------------------------------------- fortification
    def town_wall(self):
        tex = self.wall_tex()
        lo, hi = 2, N - 3            # outer lines 2 and 66, inner lines 3 and 65
        for i in range(lo, hi + 1):
            for (ox, oz, ix, iz) in ((i, lo, i, lo + 1), (i, hi, i, hi - 1), (lo, i, lo + 1, i), (hi, i, hi - 1, i)):
                self.fill(ox, G + 1, oz, ox, G + 4, oz, tex)          # parapet line
                self.fill(ix, G + 1, iz, ix, G + 2, iz, tex)          # wall walk line
                self.set(ix, G + 3, iz, "stone_bricks")
                if i % 2 == 0:
                    self.set(ox, G + 5, oz, "stone_bricks")           # merlons
        for i in range(5, hi - 1):                                     # sloped plinth outside
            self.stairs(i, G + 1, lo - 1, "stone_brick", "south")
            self.stairs(i, G + 1, hi + 1, "stone_brick", "north")
            self.stairs(lo - 1, G + 1, i, "stone_brick", "east")
            self.stairs(hi + 1, G + 1, i, "stone_brick", "west")
        # stairs up to the wall walk, inside next to the north and south gates
        for zrow in (lo + 2, hi - 2):
            for k, x in enumerate((22, 23, 24)):            # climbing east
                if k:
                    self.fill(x, G + 1, zrow, x, G + k, zrow, "stone_bricks")
                self.stairs(x, G + 1 + k, zrow, "stone_brick", "east")
            for k, x in enumerate((46, 45, 44)):            # climbing west
                if k:
                    self.fill(x, G + 1, zrow, x, G + k, zrow, "stone_bricks")
                self.stairs(x, G + 1 + k, zrow, "stone_brick", "west")

    def corner_tower(self, cx, cz):
        x1, z1 = cx - 2, cz - 2
        ox = -1 if cx < C else 1          # outward direction
        oz = -1 if cz < C else 1
        self.fill(x1, G + 1, z1, x1 + 4, G + 8, z1 + 4, self.wall_tex("tower"))
        self.fill(x1 + 1, G + 4, z1 + 1, x1 + 3, G + 7, z1 + 3, "air")
        self.fill(x1, G + 8, z1, x1 + 4, G + 8, z1 + 4, "stone_bricks")
        rim = self.ring(x1, z1, x1 + 4, z1 + 4)
        for (x, z) in rim:
            self.set(x, G + 9, z, "polished_andesite" if (x + z) % 2 else "stone_bricks")
        self.crenellate(rim, G + 10, "stone_bricks")
        for y in (G + 5, G + 6):          # arrow slits on the outward faces
            self.set(cx + 2 * ox, y, cz, "air")
            self.set(cx, y, cz + 2 * oz, "air")
        for y in (G + 4, G + 5):          # doors from both wall walks
            self.set(cx - 2 * ox, y, cz - oz, "air")
            self.set(cx - ox, y, cz - 2 * oz, "air")
        lx, lz = cx + ox, cz + oz         # ladder to the roof through a hole in the platform
        for y in range(G + 4, G + 9):
            self.set(lx, y, lz, "ladder", facing="east" if ox < 0 else "west")
        for y in range(G + 9, G + 12):
            self.set(cx, y, cz, "dark_oak_fence")
        self.banner(cx, G + 12, cz, {(-1, -1): 6, (1, -1): 10, (1, 1): 14, (-1, 1): 2}[(ox, oz)])

    def main_gate(self):
        """Gatehouse on the north wall: two towers with spires, arch, portcullis, banners."""
        tex = self.wall_tex("gate")
        z1, z2 = 1, 5
        for tx in (27, 37):
            self.fill(tx, G + 1, z1, tx + 4, G + 10, z2, tex)
            self.fill(tx + 1, G + 2, z1 + 1, tx + 3, G + 9, z2 - 1, "air")
            for (x, z) in ((tx, z1), (tx + 4, z1), (tx, z2), (tx + 4, z2)):      # quoins
                for y in range(G + 1, G + 11):
                    self.set(x, y, z, "stone_bricks" if y % 2 else "polished_andesite")
            for (x, z) in self.ring(tx, z1, tx + 4, z2):                          # string course
                self.set(x, G + 6, z, "chiseled_stone_bricks" if (x + z) % 2 else "stone_bricks")
            for y in (G + 3, G + 4):
                self.set(tx + 2, y, z1, "air"); self.set(tx + 2, y, z2, "air")
            for zz in (z1 + 1, z2 - 1):
                self.set(tx + (0 if tx < C else 4), G + 8, zz, "air")
            self.spire(tx, z1, 5, G + 11, "dark_oak", "dark_oak_planks", top=self.S("lightning_rod"))
            self.wall_banner(tx + 2, G + 9, z1 - 1, "north")
            self.wall_banner(tx + 2, G + 9, z2 + 1, "south")
        # passage and the bridge between the towers
        self.fill(32, G + 1, z1, 36, G + 4, z2, "air")
        self.fill(32, G + 5, z1, 36, G + 7, z2, tex)
        self.fill(32, G + 5, z1, 36, G + 5, z2, "stone_bricks")
        for z in range(z1, z2 + 1):
            self.stairs(32, G + 4, z, "stone_brick", "west", "top")
            self.stairs(36, G + 4, z, "stone_brick", "east", "top")
        for x in range(32, 37):
            for zz in (z1, z2):
                self.set(x, G + 8, zz, "stone_bricks")
                if x % 2 == 0:
                    self.set(x, G + 9, zz, "stone_bricks")
            self.set(x, G + 7, z1, "chiseled_stone_bricks" if x == 34 else "stone_bricks")
        # portcullis, half raised, on the outer wall line
        for x in range(33, 36):
            self.set(x, G + 4, 2, "iron_bars")
            self.set(x, G + 3, 2, "iron_bars")
        for x in (32, 36):
            self.set(x, G + 3, 2, "iron_chain", axis="y")
        self.set(34, G + 4, 4, "lantern", hanging=True)
        self.wall_banner(34, G + 6, z1 - 1, "north", patterns=self.CREST_CROSS)
        self.wall_banner(34, G + 6, z2 + 1, "south", patterns=self.CREST_CROSS)
        for x in (30, 38):                 # lamps outside the gate
            self.set(x, G + 1, 0, "stone_bricks")
            self.set(x, G + 2, 0, "stone_brick_wall")
            self.set(x, G + 3, 0, "lantern")

    def small_gate(self, side):
        """Arched gate with two little turrets on the E, W or S wall (depth -1 = outside, 0 outer, 1 inner)."""
        tex = self.wall_tex("sgate" + side)

        def P(along, depth):
            if side == "east":
                return 66 - depth, along
            if side == "west":
                return 2 + depth, along
            return along, 66 - depth

        def put(along, y, depth, name, **kw):
            x, z = P(along, depth)
            self.set(x, y, z, name, **kw)

        lo_dir = "west" if side == "south" else "north"   # direction of smaller 'along'
        hi_dir = OPP[lo_dir]
        for t0 in (30, 36):
            for a in range(t0, t0 + 3):
                for dep in (-1, 0, 1):
                    for y in range(G + 1, G + 8):
                        put(a, y, dep, tex)
            for a, dep in ((t0, -1), (t0 + 2, -1), (t0, 1), (t0 + 2, 1)):
                put(a, G + 8, dep, "stone_bricks")
            put(t0 + 1, G + 5, -1, "air")
            bx, bz = P(t0 + 1, -2)
            self.wall_banner(bx, G + 6, bz, side)
        for a in range(33, 36):
            put(a, G + 1, -1, "air")                         # no plinth stair in the gateway
            for dep in (0, 1):
                for y in range(G + 1, G + 4):
                    put(a, y, dep, "air")
                put(a, G + 4, dep, "stone_bricks"); put(a, G + 5, dep, tex)
            put(a, G + 6, 0, "air" if a == 34 else "stone_bricks")
        for dep in (0, 1):
            put(33, G + 3, dep, "stone_brick_stairs", facing=lo_dir, half="top")
            put(35, G + 3, dep, "stone_brick_stairs", facing=hi_dir, half="top")
        put(34, G + 4, 0, "chiseled_stone_bricks")
        put(34, G + 3, 1, "lantern", hanging=True)

    # ---------------------------------------------------------------- fountain
    def fountain(self, fx, fz):
        def inside(dx, dz):
            return abs(dx) <= 4 and abs(dz) <= 4 and abs(dx) + abs(dz) <= 6
        for dx in range(-4, 5):
            for dz in range(-4, 5):
                if not inside(dx, dz):
                    continue
                x, z = fx + dx, fz + dz
                self.set(x, G - 1, z, "stone_bricks")
                if any(not inside(dx + a, dz + b) for a, b in DIRS.values()):          # rim
                    self.set(x, G, z, "stone_bricks")
                    self.set(x, G + 1, z, "chiseled_stone_bricks" if (dx == 0 or dz == 0) else "stone_bricks")
                    self.set(x, G + 2, z, "stone_brick_slab", type="bottom")           # seat-able rim
                else:
                    self.set(x, G, z, "water"); self.set(x, G + 1, z, "water")
        for dx in range(-5, 6):                                                      # apron
            for dz in range(-5, 6):
                x, z = fx + dx, fz + dz
                if inside(dx, dz) or math.hypot(x - C, z - C) <= 4.6:
                    continue
                if any(inside(dx + a, dz + b) for a, b in DIRS.values()):
                    self.set(x, G, z, "polished_andesite")
        self.fill(fx - 1, G - 1, fz - 1, fx + 1, G + 1, fz + 1, "stone_bricks")      # pedestal
        self.fill(fx - 1, G + 2, fz - 1, fx + 1, G + 2, fz + 1, "chiseled_stone_bricks")
        self.fill(fx - 1, G + 3, fz - 1, fx + 1, G + 3, fz + 1, "polished_andesite")
        self.knight(fx, G + 4, fz)
        for a in (-4, 4):                                                            # four small lamps
            for b in (-4, 4):
                self.set(fx + a, G + 1, fz + b, "stone_brick_wall")
                self.set(fx + a, G + 2, fz + b, "stone_brick_wall")
                self.set(fx + a, G + 3, fz + b, "lantern")

    def knight(self, x, y, z):
        """Knight statue facing south: legs, iron torso, visor, shield and cape banners, planted sword."""
        for dx in (-1, 1):
            self.fill(x + dx, y, z, x + dx, y + 1, z, "polished_andesite")
        self.set(x, y + 1, z, "polished_andesite_slab", type="top")
        self.fill(x - 1, y + 2, z, x + 1, y + 3, z, "iron_block")
        self.set(x, y + 2, z, "chiseled_stone_bricks")                  # belt
        self.set(x - 2, y + 2, z, "andesite_wall")                     # right arm
        self.set(x + 2, y + 2, z, "polished_andesite")                 # left arm, holds the shield
        self.stairs(x - 2, y + 3, z, "polished_andesite", "east", "top")  # pauldrons
        self.stairs(x + 2, y + 3, z, "polished_andesite", "west", "top")
        self.set(x, y + 4, z, "iron_block")                            # helmet
        self.set(x, y + 4, z + 1, "iron_trapdoor", facing="south", open=True, half="top")   # visor
        self.set(x, y + 5, z, "red_carpet")                            # plume
        self.set(x, y, z + 1, "end_rod", facing="up")                  # greatsword planted in front
        self.set(x, y + 1, z + 1, "end_rod", facing="up")
        for dx in (-1, 0, 1):
            self.set(x + dx, y + 2, z + 1, "iron_chain", axis="x")
        self.set(x, y + 3, z + 1, "iron_chain", axis="y")
        self.wall_banner(x + 2, y + 2, z + 1, "south")                 # shield
        self.wall_banner(x, y + 3, z - 1, "north", patterns=self.CREST_CAPE)   # cape

    # ------------------------------------------------------------ notice board
    def notice_board(self):
        z = 42
        for x in (32, 36):
            self.fill(x, G + 1, z, x, G + 4, z, "stripped_dark_oak_log", axis="y")
        self.fill(33, G + 1, z, 35, G + 1, z, "dark_oak_slab", type="top")
        self.fill(33, G + 2, z, 35, G + 3, z, "dark_oak_planks")
        self.fill(33, G + 4, z, 35, G + 4, z, "stripped_dark_oak_log", axis="x")
        for x in range(31, 38):
            self.stairs(x, G + 5, z - 1, "dark_oak", "south")
            self.stairs(x, G + 5, z + 1, "dark_oak", "north")
            self.set(x, G + 5, z, "dark_oak_planks")
            self.set(x, G + 6, z, "dark_oak_slab", type="bottom")
        for x in (31, 37):
            self.set(x, G + 4, z - 1, "lantern", hanging=True)
        w, f = "spruce", "north"
        # the reader stands north of the board looking south: x=35 is on the reader's left
        self.wall_sign(34, G + 4, z - 1, w, f, ["", white("Witaj w"), gold("MEDIVALRPG!"), ""], glowing=True)
        self.wall_sign(35, G + 3, z - 1, w, f, [gold("/menu"), white("wszystko w"), white("jednym miejscu"), ""], glowing=True)
        self.wall_sign(34, G + 3, z - 1, w, f, [gold("/kraj pomoc"), white("załóż"), white("państwo"), ""], glowing=True)
        self.wall_sign(33, G + 3, z - 1, w, f, [gold("/mapa"), white("mapa świata"), "", ""], glowing=True)
        self.wall_sign(35, G + 2, z - 1, w, f, [gold("/glos"), white("czat głosowy"), "", ""], glowing=True)
        self.wall_sign(34, G + 2, z - 1, w, f, [white("Kupiec skupuje"), white("surowce,"), white("Bankier strzeże"), white("monet")], glowing=True)
        self.wall_sign(33, G + 2, z - 1, w, f, [gold("/nowosci"), white("co nowego"), "", ""], glowing=True)
        self.wall_banner(34, G + 3, z + 1, "south")

    # -------------------------------------------------------------------- bank
    def bank(self):
        """Stone bank with a columned portico facing east onto the main avenue."""
        x1, x2, z1, z2 = 15, 28, 6, 18
        walls = self.mix("bankw", [("stone_bricks", 70), ("cracked_stone_bricks", 10), ("polished_andesite", 12),
                                   ("mossy_stone_bricks", 8)])
        chequer = lambda x, y, z: self.S("polished_andesite") if (x + z) % 2 else self.S("polished_deepslate")
        # podium (floor at G+1) incl. portico x 29..30 and steps at x 31
        self.fill(x1, G, z1, 30, G + 1, z2, "stone_bricks")
        self.fill(x1 + 1, G + 1, z1 + 1, x2 - 1, G + 1, z2 - 1, chequer)
        self.fill(29, G + 1, z1, 30, G + 1, z2, "polished_andesite")
        for z in range(z1, z2 + 1):
            self.stairs(31, G + 1, z, "stone_brick", "west")
        for x in range(x1, 31):
            self.stairs(x, G + 1, z1 - 1, "stone_brick", "south")
            self.stairs(x, G + 1, z2 + 1, "stone_brick", "north")
        for z in range(z1 - 1, z2 + 2):
            self.stairs(x1 - 1, G + 1, z, "stone_brick", "east")
        # walls G+2..G+7, polished corners, chiseled frieze
        self.c.walls(x1, G + 2, z1, x2, G + 7, z2, walls)
        for (x, z) in ((x1, z1), (x2, z1), (x1, z2), (x2, z2)):
            self.fill(x, G + 2, z, x, G + 7, z, "polished_andesite")
        for (x, z) in self.ring(x1, z1, x2, z2):
            self.set(x, G + 7, z, "chiseled_stone_bricks" if (x + z) % 3 == 0 else "polished_andesite")
        for x in (18, 21, 25):                                   # tall windows with sills
            for zz, out in ((z1, -1), (z2, 1)):
                self.fill(x, G + 3, zz, x, G + 5, zz, "glass_pane")
                self.stairs(x, G + 2, zz + out, "stone_brick", "south" if out < 0 else "north", "top")
                self.set(x, G + 6, zz, "chiseled_stone_bricks")
        for z in (9, 15):
            self.fill(x1, G + 3, z, x1, G + 5, z, "glass_pane")
        self.fill(x2, G + 2, 11, x2, G + 4, 13, "air")           # entrance
        self.stairs(x2, G + 5, 11, "stone_brick", "north", "top")
        self.stairs(x2, G + 5, 13, "stone_brick", "south", "top")
        self.set(x2, G + 5, 12, "chiseled_stone_bricks")
        for z in (8, 16):
            self.fill(x2, G + 3, z, x2, G + 5, z, "glass_pane")
        # portico: columns, entablature
        self.fill(x1 + 1, G + 8, z1 + 1, x2 - 1, G + 8, z2 - 1, "dark_oak_planks")
        self.fill(29, G + 7, z1, 31, G + 7, z2, "polished_andesite")
        for z in range(z1, z2 + 1):
            self.set(31, G + 7, z, "chiseled_stone_bricks" if z % 2 == 0 else "polished_andesite")
        for z in (7, 10, 14, 17):
            self.set(30, G + 2, z, "chiseled_stone_bricks")
            self.fill(30, G + 3, z, 30, G + 5, z, "polished_diorite")
            self.set(30, G + 6, z, "chiseled_stone_bricks")
        self.hanging_sign(30, G + 6, 12, "dark_oak", 12, ["", title("BANK"), "Skarbiec", ""])
        for z in (8, 16):
            self.wall_banner(29, G + 6, z, "east")
        for z in (11, 13):
            self.set(29, G + 6, z, "lantern", hanging=True)
        # low-pitch deepslate roof (alternating slabs and stairs), ridge along x
        rz1, rz2 = z1 - 1, z2 + 1
        mid = (rz1 + rz2) // 2
        for x in range(x1 - 1, 32):
            for z in range(rz1, rz2 + 1):
                i = min(z - rz1, rz2 - z)
                face = "south" if z < mid else "north"
                if z == mid:
                    self.set(x, G + 8 + i // 2, z, "deepslate_tiles")
                elif i % 2 == 0:
                    self.set(x, G + 8 + i // 2, z, "deepslate_tile_slab", type="bottom")
                else:
                    self.stairs(x, G + 8 + i // 2, z, "deepslate_tile", face)
                for plane, mat in ((31, "polished_andesite"), (x1, "stone_bricks")):
                    if x == plane:
                        for yy in range(G + 8, G + 8 + i // 2):
                            self.set(x, yy, z, mat)
        self.set(31, G + 9, mid, "chiseled_stone_bricks")
        self.bank_interior(x1, x2, z1, z2)

    def bank_interior(self, x1, x2, z1, z2):
        cx = 21
        for z in range(z1 + 1, z2):                              # counter with teller windows
            if z == 16:
                self.set(cx, G + 2, z, "dark_oak_fence_gate", facing="east")
                continue
            self.set(cx, G + 2, z, "stripped_dark_oak_log", axis="z")
            if z not in (12, 17):
                self.set(cx, G + 3, z, "iron_bars")
            if z != 17:
                self.set(cx, G + 4, z, "dark_oak_slab", type="bottom")
        # banker marker: gold block under red carpet, right behind the counter window
        bx, bz = cx - 1, 12
        self.set(bx, G + 1, bz, "gold_block")
        self.set(bx, G + 2, bz, "red_carpet")
        self.c.markers["bankier"] = (bx, G + 2, bz)
        # vault behind the banker
        for z in range(z1 + 2, z2 - 1):
            self.set(18, G + 2, z, "iron_bars"); self.set(18, G + 3, z, "iron_bars")
            self.set(18, G + 4, z, "stone_bricks")
        self.set(18, G + 2, 12, "iron_door", facing="west", half="lower", hinge="left", open=False)
        self.set(18, G + 3, 12, "iron_door", facing="west", half="upper", hinge="left", open=False)
        for z in range(z1 + 2, z2 - 1):
            if z in (9, 15):
                self.set(16, G + 2, z, "chest", facing="east")
            elif z == 12:
                self.set(16, G + 2, z, "lectern", facing="east")
            else:
                self.set(16, G + 2, z, "barrel", facing="east")
        self.fill(16, G + 3, z1 + 2, 16, G + 3, z2 - 2, "bookshelf")
        # customer hall: red runner, waiting benches, banners, chandeliers
        for x in range(23, 28):
            self.set(x, G + 2, 12, "red_carpet")
        for x in (23, 24, 25):
            self.stairs(x, G + 2, z1 + 1, "dark_oak", "north")
            self.stairs(x, G + 2, z2 - 1, "dark_oak", "south")
        for zz in (z1 + 1, z2 - 1):
            self.set(22, G + 2, zz, "potted_red_tulip"); self.set(26, G + 2, zz, "potted_azure_bluet")
        for x in (22, 26):
            self.wall_banner(x, G + 6, z1 + 1, "south")
            self.wall_banner(x, G + 6, z2 - 1, "north")
        for (x, z) in ((24, 12), (18, 9), (18, 15)):
            self.set(x, G + 7, z, "iron_chain", axis="y")
            self.set(x, G + 6, z, "lantern", hanging=True)

    # ------------------------------------------------------------------ chapel
    def chapel(self):
        x1, x2, z1, z2 = 42, 55, 8, 16
        walls = self.mix("chap", [("stone_bricks", 62), ("cracked_stone_bricks", 10), ("mossy_stone_bricks", 12),
                                  ("andesite", 6), ("cobblestone", 10)])
        self.fill(x1, G, z1, x2, G, z2, "stone_bricks")
        self.fill(x1 + 1, G, z1 + 1, x2 - 1, G, z2 - 1,
                  lambda x, y, z: self.S("polished_andesite") if (x + z) % 2 else self.S("stone_bricks"))
        self.c.walls(x1, G + 1, z1, x2, G + 7, z2, walls)
        for x in (45, 49, 53):                                       # buttresses
            for zz, face in ((z1 - 1, "south"), (z2 + 1, "north")):
                self.fill(x, G + 1, zz, x, G + 4, zz, "stone_bricks")
                self.stairs(x, G + 5, zz, "stone_brick", face)
        for x, col in ((43, "yellow"), (47, "red"), (51, "yellow")):  # lancet windows
            for zz in (z1, z2):
                self.fill(x, G + 2, zz, x, G + 5, zz, f"{col}_stained_glass_pane")
                self.set(x, G + 6, zz, "chiseled_stone_bricks")
        for z in (11, 12, 13):                                       # east window
            self.fill(x2, G + 3, z, x2, G + 5, z, "yellow_stained_glass_pane" if z == 12 else "red_stained_glass_pane")
        self.set(x2, G + 6, 12, "yellow_stained_glass_pane")
        self.gable_roof(x1, x2 + 1, z1 - 1, z2 + 1, G + 8, "dark_oak", axis="x")
        for plane in (x1, x2):
            self.gable_fill(plane, z1, z2, z1 - 1, z2 + 1, G + 8, "stone_bricks")
        self.fill(x1 + 1, G + 8, z1 + 1, x2 - 1, G + 8, z2 - 1, "dark_oak_planks")
        # interior: aisle, pews facing the altar in the east, altar, lectern
        for x in range(x1 + 1, x2 - 1):
            self.set(x, G + 1, 12, "red_carpet")
        for x in (44, 46, 48, 50):
            for z in (9, 10, 11, 13, 14, 15):
                self.stairs(x, G + 1, z, "spruce", "west")
        self.fill(53, G + 1, 11, 53, G + 1, 13, "chiseled_stone_bricks")
        self.set(53, G + 2, 11, "candle", candles=3, lit=True)
        self.set(53, G + 2, 13, "candle", candles=3, lit=True)
        self.set(52, G + 1, 12, "lectern", facing="west")
        for z in (10, 14):
            self.wall_banner(x2 - 1, G + 5, z, "west", patterns=self.CREST_CROSS)
        for x in (45, 50):
            self.set(x, G + 7, 12, "iron_chain", axis="y")
            self.set(x, G + 6, 12, "lantern", hanging=True)
        # bell tower 5x5 at the front (west): x 38..42, z 10..14
        tx, tz = 38, 10
        tw = self.mix("ctow", [("stone_bricks", 70), ("cracked_stone_bricks", 12), ("mossy_stone_bricks", 10),
                               ("polished_andesite", 8)])
        self.fill(tx, G, tz, tx + 4, G, tz + 4, "stone_bricks")
        self.c.walls(tx, G + 1, tz, tx + 4, G + 15, tz + 4, tw)
        for (x, z) in ((tx, tz), (tx + 4, tz), (tx, tz + 4), (tx + 4, tz + 4)):
            self.fill(x, G + 1, z, x, G + 15, z, "polished_andesite")
        self.fill(tx + 1, G + 1, tz + 1, tx + 3, G + 14, tz + 3, "air")
        self.fill(tx, G + 1, 11, tx, G + 3, 13, "air")                     # west entrance
        self.stairs(tx, G + 3, 11, "stone_brick", "north", "top")
        self.stairs(tx, G + 3, 13, "stone_brick", "south", "top")
        self.fill(x1, G + 1, 11, x1, G + 3, 13, "air")                     # into the nave
        self.fill(tx + 1, G, 11, tx + 3, G, 13, "polished_andesite")
        self.fill(tx + 1, G + 10, tz + 1, tx + 3, G + 10, tz + 3, "dark_oak_planks")   # belfry floor
        for y in range(G + 1, G + 11):
            self.set(tx + 1, y, tz + 1, "ladder", facing="south")
        for y in range(G + 11, G + 14):                                    # belfry openings
            for a in (1, 2, 3):
                self.set(tx, y, tz + a, "air"); self.set(tx + 4, y, tz + a, "air")
                self.set(tx + a, y, tz, "air"); self.set(tx + a, y, tz + 4, "air")
        for a, d in ((1, "north"), (3, "south")):
            self.stairs(tx, G + 13, tz + a, "stone_brick", d, "top")
            self.stairs(tx + 4, G + 13, tz + a, "stone_brick", d, "top")
        for a, d in ((1, "west"), (3, "east")):
            self.stairs(tx + a, G + 13, tz, "stone_brick", d, "top")
            self.stairs(tx + a, G + 13, tz + 4, "stone_brick", d, "top")
        self.fill(tx + 1, G + 15, tz + 1, tx + 3, G + 15, tz + 3, "stone_bricks")
        self.set(tx + 2, G + 14, tz + 2, "bell", attachment="ceiling", facing="west")
        top = self.spire(tx, tz, 5, G + 16, "deepslate_tile", "deepslate_tiles")
        cxp, czp = tx + 2, tz + 2                                            # cross
        for dy in (0, 1, 2):
            self.set(cxp, top + dy, czp, "dark_oak_fence")
        self.set(cxp, top + 1, czp - 1, "dark_oak_fence")
        self.set(cxp, top + 1, czp + 1, "dark_oak_fence")
        self.wall_banner(tx - 1, G + 8, tz + 2, "west", patterns=self.CREST_CROSS)
        self.wall_hanging_sign(tx - 1, G + 4, tz + 2, "dark_oak", "north", ["", title("KAPLICA"), "", ""])

    def graveyard(self):
        wall = lambda x, y, z: self.S("mossy_cobblestone_wall" if hrand(x, 0, z, "gy") < .5 else "cobblestone_wall")
        for x in range(57, 65):
            self.set(x, G + 1, 6, wall); self.set(x, G + 1, 19, wall)
        for z in range(6, 20):
            self.set(57, G + 1, z, wall)
        self.set(57, G + 1, 12, "spruce_fence_gate", facing="east")
        self.set(57, G + 1, 13, "spruce_fence_gate", facing="east")
        soil = self.mix("grave", [("coarse_dirt", 2), ("podzol", 2), ("rooted_dirt", 1)])
        for gx in (59, 62):
            for gz in (8, 12, 16):
                self.set(gx, G + 1, gz, "stone_brick_wall" if (gx + gz) % 2 else "andesite_wall")
                self.set(gx, G, gz + 1, soil); self.set(gx, G, gz + 2, soil)
                if hrand(gx, 1, gz, "gyf") < .6:
                    self.set(gx, G + 1, gz + 2, "lily_of_the_valley" if gz % 2 else "poppy")
        self.set(63, G + 1, 7, "lantern")

    # ------------------------------------------------------------------ tavern
    def tavern(self):
        """'Karczma pod Złotym Dzikiem': stone ground floor, jettied timber upper floor, gable to the street."""
        x1, x2, z1, z2 = 16, 29, 48, 60
        ux2 = x2 + 1                                   # upper floor juts out over the street
        stone = self.mix("tav", [("cobblestone", 32), ("stone_bricks", 34), ("mossy_cobblestone", 10),
                                 ("andesite", 12), ("mossy_stone_bricks", 12)])
        self.fill(x1, G, z1, x2, G, z2, "cobblestone")
        self.fill(x1 + 1, G, z1 + 1, x2 - 1, G, z2 - 1, "spruce_planks")
        self.c.walls(x1, G + 1, z1, x2, G + 4, z2, stone)
        for (x, z) in ((x1, z1), (x2, z1), (x1, z2), (x2, z2)):
            self.fill(x, G + 1, z, x, G + 4, z, "stripped_dark_oak_log", axis="y")
        self.fill(x1, G + 5, z1, ux2, G + 5, z2, "dark_oak_planks")
        for z in range(z1, z2 + 1):
            self.stairs(ux2, G + 4, z, "dark_oak", "west", "top")           # corbels under the jetty
        # timber frame upper floor
        self.c.walls(x1, G + 6, z1, ux2, G + 8, z2, self.S("calcite"))
        for (x, z) in self.ring(x1, z1, ux2, z2):
            axis = "x" if z in (z1, z2) and x not in (x1, ux2) else "z"
            self.set(x, G + 5, z, "stripped_dark_oak_log", axis=axis)
            self.set(x, G + 9, z, "stripped_dark_oak_log", axis=axis)
        for x in range(x1, ux2 + 1, 3):
            for zz in (z1, z2):
                self.fill(x, G + 6, zz, x, G + 8, zz, "stripped_dark_oak_log", axis="y")
        for z in (z1, z1 + 3, 54, z2 - 3, z2):
            for xx in (x1, ux2):
                self.fill(xx, G + 6, z, xx, G + 8, z, "stripped_dark_oak_log", axis="y")
        for z in (52, 56):
            self.fill(ux2, G + 7, z, ux2, G + 8, z, "glass_pane")
        for x in (20, 26):
            for zz in (z1, z2):
                self.set(x, G + 7, zz, "glass_pane")
        # steep spruce roof, ridge along x, gable to the street (east)
        rz1, rz2 = z1 - 1, z2 + 1
        self.gable_roof(x1 - 1, ux2 + 1, rz1, rz2, G + 10, "spruce", axis="x")
        for plane in (x1, ux2):
            self.gable_fill(plane, z1, z2, rz1, rz2, G + 10, "calcite")
        self.fill(ux2, G + 10, 54, ux2, G + 15, 54, "stripped_dark_oak_log", axis="y")
        for z in (51, 57):
            self.fill(ux2, G + 10, z, ux2, G + 12, z, "stripped_dark_oak_log", axis="y")
        self.set(ux2, G + 12, 54, "glass_pane")
        # ground floor front: door, windows, flower boxes, sign, banners
        self.set(x2, G + 1, 54, "dark_oak_door", facing="west", half="lower", hinge="left")
        self.set(x2, G + 2, 54, "dark_oak_door", facing="west", half="upper", hinge="left")
        self.set(x2, G + 3, 54, "chiseled_stone_bricks")
        for z in (50, 51, 57, 58):
            self.fill(x2, G + 2, z, x2, G + 3, z, "glass_pane")
        self.planter_box([(ux2, 50), (ux2, 51)], G + 1)
        self.planter_box([(ux2, 57), (ux2, 58)], G + 1)
        for z in (51, 57):
            self.fill(x1, G + 2, z, x1, G + 3, z, "glass_pane")
        for x in (20, 23, 26):
            self.fill(x, G + 2, z2, x, G + 3, z2, "glass_pane")
        self.wall_hanging_sign(ux2 + 1, G + 6, 54, "spruce", "north", [title("KARCZMA"), "Pod Złotym", "Dzikiem", ""])
        for z in (51, 57):
            self.wall_banner(ux2 + 1, G + 8, z, "east")
        # chimney on the north wall with a lit campfire on top (smoke)
        chim = self.cobble_tex("chim")
        for y in range(G + 1, G + 16):
            for x in (23, 24, 25):
                for z in (z1 - 1, z1):
                    self.set(x, y, z, chim)
        self.set(24, G + 16, z1 - 1, "campfire", lit=True, facing="south")
        self.set(24, G + 1, z1, "campfire", lit=True, facing="south")
        self.set(24, G + 2, z1, "air")
        for x in (23, 25):
            self.fill(x, G + 1, z1 + 1, x, G + 2, z1 + 1, "stone_bricks")
        for x in (23, 24, 25):
            self.set(x, G + 3, z1 + 1, "stone_brick_slab", type="top")
        self.tavern_interior(x1, x2, z1, z2)

    def tavern_interior(self, x1, x2, z1, z2):
        for z in range(z1 + 2, z1 + 10):                                    # bar along the west wall
            self.set(20, G + 1, z, "barrel" if z % 3 == 0 else "spruce_planks", **({"facing": "up"} if z % 3 == 0 else {}))
            self.set(17, G + 1, z, "barrel", facing="east")
            if z % 2:
                self.set(17, G + 2, z, "barrel", facing="east")
            else:
                self.set(17, G + 2, z, "spruce_shelf", facing="east")
            self.set(17, G + 3, z, "spruce_shelf", facing="east")
        self.set(20, G + 2, 52, "brewing_stand")
        self.set(20, G + 2, 55, "candle", candles=2, lit=True)
        self.set(18, G + 1, 50, "cauldron")
        self.set(18, G + 1, 51, "smoker", facing="east")
        for y in range(G + 1, G + 6):                                       # ladder to the upper floor
            self.set(17, y, z2 - 1, "ladder", facing="east")
        for tx in (22, 26):                                                 # tables + benches
            for tz in (51, 57):
                for x in (tx, tx + 1):
                    self.set(x, G + 1, tz, "dark_oak_slab", type="top")
                    self.stairs(x, G + 1, tz - 1, "spruce", "north")         # sit facing south
                    self.stairs(x, G + 1, tz + 1, "spruce", "south")         # sit facing north
                self.set(tx, G + 2, tz, "candle", candles=1, lit=True)
        for (x, z) in ((21, 54), (24, 54), (27, 54), (23, 51), (27, 57), (23, 57), (27, 51)):
            self.set(x, G + 4, z, "lantern", hanging=True)
        self.set(28, G + 1, z1 + 1, "barrel", facing="up")
        self.set(28, G + 1, z2 - 1, "barrel", facing="up")
        for bx in (20, 23, 26):                                             # upper floor rooms
            self.set(bx, G + 6, z2 - 1, "red_bed", facing="south", part="head")
            self.set(bx, G + 6, z2 - 2, "red_bed", facing="south", part="foot")
            if bx != 26:
                self.set(bx + 1, G + 6, z2 - 1, "chest", facing="north")
        self.set(24, G + 6, 51, "dark_oak_slab", type="top")
        self.set(24, G + 7, 51, "lantern")
        self.set(28, G + 6, 50, "barrel", facing="up")

    # ------------------------------------------------------------------- forge
    def forge(self):
        x1, x2, z1, z2 = 39, 50, 47, 57
        self.fill(x1, G, z1, x2, G, z2, self.mix("ffl", [("cobblestone", 3), ("stone", 2), ("gravel", 1), ("andesite", 2)]))
        self.c.walls(45, G + 1, z1, x2, G + 4, z2, self.cobble_tex("forge"))      # enclosed workshop
        self.fill(45, G + 1, 52, 45, G + 2, 52, "air")
        for (x, z) in ((x1, z1), (x1, 52), (x1, z2), (44, z1), (44, z2), (45, z1), (45, z2)):
            self.fill(x, G + 1, z, x, G + 4, z, "stripped_dark_oak_log", axis="y")
        for x in range(x1 + 1, 44):
            for zz in (z1, z2):
                self.set(x, G + 1, zz, "cobblestone_wall")
        for z in range(z1, z2 + 1):
            self.set(x1, G + 5, z, "stripped_dark_oak_log", axis="z")
        for x in range(x1, x2 + 1):
            for zz in (z1, z2):
                self.set(x, G + 5, zz, "stripped_dark_oak_log", axis="x")
        self.gable_roof(x1 - 1, x2 + 1, z1 - 1, z2 + 1, G + 5, "spruce", axis="z")
        for zz in (z1, z2):
            self.gable_fill(zz, x1, x2, x1 - 1, x2 + 1, G + 5, "spruce_planks", axis="z", ymin=G + 6)
        # hearth: furnaces under a stone hood + chimney with smoke
        self.set(44, G + 1, 51, "furnace", facing="west", lit=True)
        self.set(44, G + 1, 52, "blast_furnace", facing="west", lit=True)
        self.set(44, G + 1, 53, "furnace", facing="west", lit=True)
        for y in range(G + 2, G + 14):
            for x in (44, 45, 46):
                for z in (51, 52, 53):
                    if y >= G + 5 or x == 44 or (x, z) != (45, 52):
                        self.set(x, y, z, "stone_bricks" if hrand(x, y, z, "chf") < .7 else "cracked_stone_bricks")
        self.set(45, G + 14, 52, "campfire", lit=True, facing="west")
        self.set(43, G + 1, 52, "lava_cauldron")
        self.set(41, G + 1, 50, "anvil", facing="north")
        self.set(41, G + 1, 54, "chipped_anvil", facing="north")
        self.set(42, G + 1, 48, "smithing_table")
        self.set(40, G + 1, 48, "grindstone", face="floor", facing="west")
        self.set(42, G + 1, 56, "water_cauldron", level=3)
        self.set(40, G + 1, 56, "barrel", facing="up")
        self.set(43, G + 1, 56, "stonecutter", facing="west")
        for z in (48, 49):
            self.set(44, G + 1, z, "barrel", facing="west")
        self.set(44, G + 2, 48, "iron_bars")
        self.fill(41, G + 5, 52, 41, G + 7, 52, "iron_chain", axis="y")      # hangs from the roof
        self.set(41, G + 4, 52, "lantern", hanging=True)
        for z in range(z1 + 1, z2):                                        # back room storage
            if z != 52:
                self.set(49, G + 1, z, "chest" if z % 3 == 0 else "barrel", facing="west")
        self.set(47, G + 1, z1 + 1, "crafting_table")
        self.set(x1 - 1, G + 4, 50, "lantern", hanging=True)
        self.hanging_sign(x1 - 1, G + 4, 54, "spruce", 4, ["", title("KUŹNIA"), "", ""])
        for x in range(40, 45):                                            # woodpile
            for y in (G + 1, G + 2):
                self.set(x, y, z2 + 2, "spruce_log", axis="x")

    # ------------------------------------------------------------------ stable
    def stable(self):
        x1, x2, z1, z2 = 5, 17, 39, 46
        self.fill(x1, G, z1, x2, G, z2, self.mix("stf", [("coarse_dirt", 5), ("podzol", 2), ("dirt_path", 1), ("rooted_dirt", 1)]))
        self.fill(x1, G + 1, z2, x2, G + 4, z2, "spruce_planks")
        for x in (x1, x2):
            self.fill(x, G + 1, z1, x, G + 4, z2, "spruce_planks")
        for x in (5, 9, 13, 17):
            self.fill(x, G + 1, z1, x, G + 4, z1, "stripped_spruce_log", axis="y")
            self.fill(x, G + 1, z2, x, G + 4, z2, "stripped_spruce_log", axis="y")
        for x in range(x1, x2 + 1):
            self.set(x, G + 5, z1, "stripped_spruce_log", axis="x")
            self.set(x, G + 5, z2, "stripped_spruce_log", axis="x")
        for x in (9, 13):                                                  # stall dividers
            for z in range(z1 + 1, z2):
                self.set(x, G + 1, z, "spruce_fence"); self.set(x, G + 2, z, "spruce_fence")
        for sx in (5, 9, 13):
            self.set(sx + 1, G + 1, z1, "spruce_fence"); self.set(sx + 3, G + 1, z1, "spruce_fence")
            self.set(sx + 2, G + 1, z1, "spruce_fence_gate", facing="north")
            self.set(sx + 1, G + 1, z2 - 1, "hay_block", axis="x")
            self.set(sx + 2, G + 1, z2 - 1, "composter", level=6)            # manger (7 would tick to 8)
            self.set(sx + 3, G + 1, z2 - 1, "water_cauldron", level=3)       # water trough
        self.set(10, G + 2, z2 - 1, "hay_block", axis="z")
        self.gable_roof(x1 - 1, x2 + 1, z1 - 1, z2 + 1, G + 5, "spruce", axis="x")
        for xx in (x1, x2):
            self.gable_fill(xx, z1, z2, z1 - 1, z2 + 1, G + 5, "spruce_planks", ymin=G + 6)
        self.fill(x1 + 1, G + 5, z1 + 1, x2 - 1, G + 5, z2 - 1, "spruce_slab", type="top")
        self.set(11, G + 4, z1 - 1, "lantern", hanging=True)
        self.hanging_sign(7, G + 4, z1 - 1, "spruce", 8, ["", title("STAJNIA"), "", ""])
        self.set(18, G + 1, 45, "hay_block", axis="x"); self.set(18, G + 1, 44, "hay_block", axis="z")
        self.set(18, G + 2, 45, "hay_block", axis="y")

    # ------------------------------------------------------------------ market
    def market(self):
        north = [(48, ("red", "white"), "veg"), (54, ("red", "yellow"), "kupiec"), (60, ("blue", "white"), "cloth")]
        south = [(48, ("green", "white"), "fish"), (54, ("orange", "white"), "pots"), (60, ("yellow", "white"), "books")]
        for x0, cols, goods in north:
            self.stall(x0, 27, 30, "south", cols, goods)
        for x0, cols, goods in south:
            self.stall(x0, 41, 38, "north", cols, goods)

    def stall(self, x0, zb, zf, front, cols, goods):
        """Market stall 5 x 4: posts, striped wool awning, banner valance, counter with goods."""
        dz = 1 if front == "south" else -1
        self.fill(x0 + 1, G, min(zb, zf), x0 + 3, G, max(zb, zf), "spruce_planks")
        for x in (x0, x0 + 4):
            self.fill(x, G + 1, zb, x, G + 4, zb, "stripped_spruce_log", axis="y")
            self.fill(x, G + 1, zf, x, G + 3, zf, "stripped_spruce_log", axis="y")
        for x in range(x0, x0 + 5):
            col = f"{cols[(x - x0) % 2]}_wool"
            for z in range(min(zb, zf + dz), max(zb, zf + dz) + 1):
                self.set(x, G + 5 if abs(z - zb) <= 1 else G + 4, z, col)
        self.wall_banner(x0 + 2, G + 4, zf + 2 * dz, front, color=cols[0],
                         patterns=[("triangles_bottom", cols[1]), ("border", cols[1])])
        for x in range(x0 + 1, x0 + 4):
            if x - x0 == 2:
                self.set(x, G + 1, zf, "barrel", facing="up")
            else:
                self.set(x, G + 1, zf, "spruce_planks")
        for x in (x0, x0 + 4):
            self.set(x, G + 1, zb + dz, "spruce_fence")
        back = {"veg": ["composter", "hay_block", "composter"], "kupiec": ["chest", "barrel", "chest"],
                "cloth": ["loom", "chest", "white_wool"], "fish": ["barrel", "smoker", "barrel"],
                "pots": ["barrel", "chest", "composter"], "books": ["bookshelf", "lectern", "bookshelf"]}[goods]
        top = {"veg": ["pumpkin", "melon", "carved_pumpkin"], "kupiec": ["oak_log", None, "cobblestone"],
               "cloth": ["red_wool", "yellow_wool", "white_wool"], "fish": ["dried_kelp_block", None, "barrel"],
               "pots": ["decorated_pot", "flower_pot", "decorated_pot"], "books": ["bookshelf", None, "potted_fern"]}[goods]
        for i, x in enumerate(range(x0 + 1, x0 + 4)):
            b = back[i]
            kw = {"facing": front} if b in ("chest", "barrel", "smoker", "lectern", "loom") else {}
            if b == "composter":
                kw["level"] = 6
            self.set(x, G + 1, zb, b, **kw)
            t = top[i]
            if t:
                kw = {"facing": front} if t in ("carved_pumpkin", "barrel", "decorated_pot") else {}
                if t == "oak_log":
                    kw["axis"] = "x"
                self.set(x, G + 2, zf, t, **kw)
        if goods == "kupiec":
            mx, mz = x0 + 2, zf - dz
            self.set(mx, G, mz, "gold_block")
            self.set(mx, G + 1, mz, "lime_carpet")
            self.c.markers["kupiec"] = (mx, G + 1, mz)
            self.hanging_sign(x0 + 2, G + 3, zf + dz, "spruce", 0 if front == "south" else 8,
                              [title("KUPIEC"), "skup", "surowców", ""])
        else:
            self.set(x0 + 2, G + 3, zf + dz, "lantern", hanging=True)

    # ----------------------------------------------------------- training yard
    def training_yard(self):
        x1, x2, z1, z2 = 54, 63, 46, 62
        self.fill(x1, G, z1, x2, G, z2, self.mix("ty", [("coarse_dirt", 3), ("gravel", 2), ("dirt_path", 2), ("grass_block", 2)]))
        for (x, z) in self.ring(x1, z1, x2, z2):
            self.set(x, G + 1, z, "spruce_fence")
        for z in (53, 54):
            self.set(x1, G + 1, z, "spruce_fence_gate", facing="west")
        for z in (49, 54, 59):                                             # straw dummies
            self.set(57, G + 1, z, "spruce_fence")
            self.set(57, G + 2, z, "hay_block", axis="y")
            self.set(57, G + 2, z - 1, "spruce_fence"); self.set(57, G + 2, z + 1, "spruce_fence")
            self.set(57, G + 3, z, "carved_pumpkin", facing="west")
        for z in (50, 55, 60):                                             # archery targets
            self.set(62, G + 1, z, "hay_block", axis="y")
            self.set(62, G + 2, z, "target")
        self.set(55, G + 1, 61, "barrel", facing="up")
        self.set(56, G + 1, 61, "grindstone", face="floor", facing="north")
        self.set(55, G + 1, 47, "hay_block", axis="x")
        for (x, z) in ((x1, z1), (x2, z1), (x1, z2), (x2, z2)):
            self.set(x, G + 2, z, "lantern")

    # --------------------------------------------------------- well & gardens
    def well_and_gardens(self):
        wx, wz = 10, 26
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == 0 and dz == 0:
                    for y in range(1, G + 1):
                        self.set(wx, y, wz, "water")
                    continue
                self.set(wx + dx, G + 1, wz + dz, "mossy_cobblestone" if hrand(wx + dx, 0, wz + dz, "w") < .4 else "cobblestone")
                for y in range(1, G + 1):
                    self.set(wx + dx, y, wz + dz, "cobblestone")
        for dx in (-1, 1):
            self.set(wx + dx, G + 2, wz, "dark_oak_fence"); self.set(wx + dx, G + 3, wz, "dark_oak_fence")
        self.fill(wx - 1, G + 4, wz, wx + 1, G + 4, wz, "stripped_dark_oak_log", axis="x")
        for x in range(wx - 2, wx + 3):
            self.stairs(x, G + 4, wz - 1, "dark_oak", "south"); self.stairs(x, G + 4, wz + 1, "dark_oak", "north")
            self.set(x, G + 5, wz, "dark_oak_slab", type="bottom")
        self.set(wx - 2, G + 4, wz, "dark_oak_planks"); self.set(wx + 2, G + 4, wz, "dark_oak_planks")
        self.set(wx, G + 3, wz, "iron_chain", axis="y")
        self.set(wx, G + 2, wz, "lantern", hanging=True)
        for (x, z) in ((wx, wz - 2), (wx, wz + 2), (wx - 2, wz), (wx + 2, wz)):
            self.set(x, G, z, "dirt_path")
        # vegetable garden (SW) with a water channel and a scarecrow
        gx1, gx2, gz1, gz2 = 5, 11, 51, 55
        for x in range(gx1, gx2 + 1):
            for z in range(gz1, gz2 + 1):
                if x in (gx1, gx2) or z in (gz1, gz2):
                    self.set(x, G, z, "spruce_log", axis="x" if z in (gz1, gz2) else "z")
                elif z == 53:
                    self.set(x, G, z, "water")
                else:
                    crop = ("wheat", "carrots", "potatoes", "beetroots")[(x + (z > 53)) % 4]
                    self.set(x, G, z, "farmland", moisture=7)
                    self.set(x, G + 1, z, crop, age=3 if crop == "beetroots" else 7)
        self.set(gx2 + 1, G + 1, 53, "spruce_fence"); self.set(gx2 + 1, G + 2, 53, "hay_block", axis="y")
        self.set(gx2 + 1, G + 3, 53, "carved_pumpkin", facing="east")
        # bank garden (NW): flower boxes and a bench
        self.planter_box([(6, 12), (7, 12), (8, 12)], G + 1)
        self.planter_box([(10, 6), (11, 6), (12, 6)], G + 1)
        self.bench(6, 15, "east", 3, along="z")

    # --------------------------------------------------------- plaza furniture
    def plaza_furniture(self):
        self.bench(28, 24, "east", 3, along="z")                        # facing the fountain
        self.bench(40, 24, "west", 3, along="z")
        self.bench(28, 36, "east", 3, along="z", wood="dark_oak")       # facing the wind rose
        self.bench(40, 36, "west", 3, along="z", wood="dark_oak")
        for (x, z) in ((C - 3, C - PLAZA_R), (C + 3, C - PLAZA_R), (C - 3, C + PLAZA_R), (C + 3, C + PLAZA_R),
                       (C - PLAZA_R, C - 3), (C - PLAZA_R, C + 3), (C + PLAZA_R, C - 3), (C + PLAZA_R, C + 3)):
            self.set(x, G + 1, z, "stone_bricks")                        # lamps at the road mouths
            self.set(x, G + 2, z, "dark_oak_fence"); self.set(x, G + 3, z, "dark_oak_fence")
            self.set(x, G + 4, z, "lantern")
        for x in (27, 41):                                               # flag poles
            self.set(x, G, 21, "chiseled_stone_bricks")
            for y in range(G + 1, G + 7):
                self.set(x, y, 21, "dark_oak_fence")
            self.banner(x, G + 7, 21, 0)
        for x in (24, 44):                                               # flower boxes
            self.planter_box([(x, 33), (x, 34), (x, 35)], G + 1)
        self.set(46, G + 1, 30, "barrel", facing="up"); self.set(46, G + 1, 29, "barrel", facing="north")
        self.set(45, G + 1, 30, "hay_block", axis="x")
        self.set(46, G + 1, 38, "composter", level=5); self.set(45, G + 1, 39, "barrel", facing="up")
        self.set(46, G + 1, 39, "chest", facing="west")

    def street_lamps(self):
        self.lamp_post(37, 8, "west"); self.lamp_post(37, 17, "west")      # main avenue (bank steps on the west)
        for x in (7, 15):                                                  # west road
            self.lamp_post(x, C - 3, "south"); self.lamp_post(x, C + 3, "north")
        for x in (53, 59):                                                 # east road, between the stalls
            self.lamp_post(x, C - 3, "south"); self.lamp_post(x, C + 3, "north")
        self.lamp_post(31, 55, "east"); self.lamp_post(31, 62, "east")     # south road
        self.lamp_post(37, 49, "west"); self.lamp_post(37, 60, "west")

    # ------------------------------------------------------------------- trees
    def trees(self):
        spots = [
            (25, 26, "oak", 5), (43, 26, "oak", 5), (25, 42, "dark_oak", 4), (43, 42, "dark_oak", 4),   # plaza
            (7, 8, "oak", 5), (12, 17, "azalea", 4), (5, 22, "spruce", 7), (16, 25, "oak", 4),          # NW
            (60, 9, "spruce", 8), (63, 15, "spruce", 6), (51, 22, "oak", 5), (61, 23, "dark_oak", 4),   # NE
            (7, 60, "oak", 5), (13, 63, "azalea", 4),                                                  # SW
            (61, 43, "oak", 4), (44, 62, "spruce", 6), (50, 62, "oak", 4),                             # SE
        ]
        for x, z, kind, h in spots:
            self.tree(x, z, kind, h)

    def tree(self, x, z, kind, h):
        """Hand-shaped tree with persistent leaves; plaza trees get a planter."""
        log = {"oak": "oak_log", "dark_oak": "dark_oak_log", "spruce": "spruce_log", "azalea": "oak_log"}[kind]
        if self.in_plaza(x, z):
            for a in (-1, 0, 1):
                for b in (-1, 0, 1):
                    if (a, b) != (0, 0):
                        self.set(x + a, G, z + b, "grass_block")
                        self.set(x + a, G + 1, z + b, ("short_grass", "poppy", "fern", "dandelion")[int(hrand(x + a, 0, z + b, "pt") * 4)])
            for a in range(-2, 3):
                for b in range(-2, 3):
                    if max(abs(a), abs(b)) == 2:
                        self.set(x + a, G + 1, z + b, "stone_brick_slab", type="bottom")
        self.set(x, G, z, "dirt")
        for y in range(G + 1, G + 1 + h):
            self.set(x, y, z, log, axis="y")
        top = G + h
        if kind == "spruce":
            for i, y in enumerate(range(top + 1, G + 2, -1)):
                r = min(3.2, (0.6 + 0.55 * i) if i % 2 == 0 else (0.4 + 0.5 * i))
                self._leaf_disc(x, y, z, r, "spruce_leaves", "sp")
            self.set(x, top + 2, z, "spruce_leaves")
            return
        leaves = {"oak": "oak_leaves", "dark_oak": "dark_oak_leaves", "azalea": "azalea_leaves"}[kind]
        alt = "flowering_azalea_leaves" if kind == "azalea" else None
        # branches, then a noisy ellipsoid canopy (plus two smaller lobes for an irregular crown)
        for d, (a, b) in DIRS.items():
            if hrand(x, a, z + b, "branch" + d) < .55:
                self.set(x + a, top - 1, z + b, log, axis="x" if a else "z")
                self.set(x + 2 * a, top, z + 2 * b, log, axis="y")
        rx = 3.0 if kind == "dark_oak" else 2.7
        ry = 1.8 if kind == "dark_oak" else 2.3
        lobes = [(0, 0, 0, 1.0)]
        for i in range(2):
            ang = hrand(x, i, z, "lobe") * 6.283
            lobes.append((round(1.6 * math.cos(ang)), -1 + i, round(1.6 * math.sin(ang)), 0.7))
        for (lx, ly, lz, sc) in lobes:
            R = int(math.ceil(rx * sc)) + 1
            for a in range(-R, R + 1):
                for b in range(-R, R + 1):
                    for dy in range(-3, 4):
                        px, py, pz = x + lx + a, top + 1 + ly + dy, z + lz + b
                        if py <= G + 1 or not (0 <= px < N and 0 <= pz < N):
                            continue
                        v = (a * a + b * b) / (rx * sc) ** 2 + (dy * dy) / (ry * sc) ** 2
                        if v + 0.45 * hrand(px, py, pz, "leafn") < 1.05 and self.empty(px, py, pz):
                            st = alt if (alt and hrand(px, py, pz, "alt") < .4) else leaves
                            self.set(px, py, pz, st)

    def _leaf_disc(self, x, y, z, r, leaves, salt, alt=None):
        R = int(math.ceil(r))
        for a in range(-R, R + 1):
            for b in range(-R, R + 1):
                d = math.hypot(a, b)
                if d > r or (d > r - 0.9 and hrand(x + a, y, z + b, salt) < 0.35):
                    continue
                if 0 <= x + a < N and 0 <= z + b < N and self.empty(x + a, y, z + b):
                    st = alt if (alt and hrand(x + a, y, z + b, "alt") < .4) else leaves
                    self.set(x + a, y, z + b, st)

    def yard_props(self):
        self.set(26, G + 1, 62, "barrel", facing="up"); self.set(27, G + 1, 62, "barrel", facing="north")
        self.set(26, G + 2, 62, "composter", level=3)
        self.set(18, G + 1, 62, "hay_block", axis="x")

    def decorate_ground(self):
        # grass covered by a solid block would turn into dirt on the first random tick: do it now
        for (x, y, z), st in list(self.c.blocks.items()):
            if y == G and base_name(st) in ("grass_block", "dirt_path") and is_full_solid(self.get(x, G + 1, z)):
                self.set(x, G, z, "dirt" if base_name(st) == "grass_block" else "coarse_dirt")
        flowers = ["poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "lily_of_the_valley"]
        for x in range(N):
            for z in range(N):
                if base_name(self.get(x, G, z)) != "grass_block" or not self.empty(x, G + 1, z):
                    continue
                r = hrand(x, G + 1, z, "deco")
                if r < 0.10:
                    self.set(x, G + 1, z, "short_grass")
                elif r < 0.13:
                    self.set(x, G + 1, z, "fern")
                elif r < 0.165:
                    self.set(x, G + 1, z, flowers[int(hrand(x, 7, z, "fsel") * len(flowers))])
                elif r < 0.18:
                    self.set(x, G, z, "coarse_dirt")
                elif r < 0.19:
                    self.set(x, G, z, "podzol")
                elif r < 0.20:
                    self.set(x, G, z, "moss_block")


def build(reg: Registry | None = None) -> Canvas:
    return Spawn(reg or Registry()).build()
