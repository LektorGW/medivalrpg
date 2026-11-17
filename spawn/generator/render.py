# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Preview renderer: top-down map and isometric view (Pillow), colours from a simple block palette."""
from __future__ import annotations

from PIL import Image, ImageDraw

from .blocks import base, parse_state
from .world import hrand

COLORS = {
    "stone": (125, 125, 125), "andesite": (136, 136, 137), "polished_andesite": (132, 135, 134),
    "stone_bricks": (122, 121, 122), "cracked_stone_bricks": (118, 117, 118), "mossy_stone_bricks": (115, 121, 105),
    "chiseled_stone_bricks": (119, 118, 119), "cobblestone": (127, 127, 127), "mossy_cobblestone": (110, 118, 95),
    "tuff": (108, 109, 102), "tuff_bricks": (98, 102, 94), "polished_tuff": (97, 103, 96), "gravel": (131, 127, 126),
    "smooth_stone": (159, 159, 159), "polished_diorite": (193, 193, 195), "calcite": (223, 224, 220),
    "polished_deepslate": (72, 72, 73), "deepslate_tiles": (54, 54, 55), "deepslate_tile": (54, 54, 55),
    "deepslate_tile_wall": (54, 54, 55), "lodestone": (147, 149, 152),
    "grass_block": (95, 150, 60), "dirt": (134, 96, 67), "coarse_dirt": (119, 85, 59), "podzol": (91, 63, 24),
    "rooted_dirt": (144, 103, 76), "dirt_path": (148, 122, 65), "moss_block": (89, 109, 45), "farmland": (82, 50, 28),
    "water": (54, 92, 190), "lava": (210, 90, 20),
    "oak_log": (109, 85, 50), "dark_oak_log": (60, 46, 26), "spruce_log": (58, 37, 16),
    "stripped_dark_oak_log": (73, 57, 36), "stripped_spruce_log": (115, 89, 52),
    "oak_planks": (162, 130, 78), "dark_oak_planks": (67, 43, 20), "spruce_planks": (115, 85, 49),
    "dark_oak": (67, 43, 20), "spruce": (115, 85, 49),
    "oak_leaves": (60, 120, 35), "dark_oak_leaves": (40, 90, 25), "spruce_leaves": (50, 85, 50),
    "azalea_leaves": (90, 125, 45), "flowering_azalea_leaves": (120, 115, 90),
    "white_terracotta": (210, 178, 161), "red_terracotta": (143, 61, 46), "yellow_terracotta": (186, 133, 35),
    "red_wool": (161, 39, 34), "white_wool": (234, 236, 237), "yellow_wool": (249, 198, 39), "blue_wool": (53, 57, 157),
    "green_wool": (84, 109, 27), "orange_wool": (240, 118, 19),
    "gold_block": (246, 208, 61), "iron_block": (220, 220, 220), "hay_block": (166, 136, 38),
    "glass_pane": (175, 210, 215), "red_stained_glass_pane": (153, 51, 51), "yellow_stained_glass_pane": (229, 229, 51),
    "iron_bars": (110, 110, 110), "iron_chain": (60, 64, 72), "lantern": (240, 190, 90), "end_rod": (240, 235, 225),
    "barrel": (110, 80, 45), "chest": (160, 110, 40), "composter": (110, 75, 40), "bookshelf": (110, 80, 50),
    "pumpkin": (200, 120, 25), "carved_pumpkin": (200, 120, 25), "melon": (110, 145, 30), "target": (230, 170, 160),
    "anvil": (68, 68, 68), "chipped_anvil": (68, 68, 68), "furnace": (110, 110, 110), "blast_furnace": (95, 95, 98),
    "smoker": (90, 80, 70), "campfire": (230, 130, 40), "bell": (240, 200, 70), "lectern": (160, 120, 70),
    "red_bed": (160, 40, 35), "ladder": (130, 100, 60), "smithing_table": (60, 40, 40), "loom": (150, 120, 90),
    "cauldron": (70, 70, 70), "water_cauldron": (60, 90, 170), "lava_cauldron": (210, 90, 20), "lightning_rod": (200, 110, 70),
    "decorated_pot": (150, 80, 60), "flower_pot": (120, 60, 40), "dried_kelp_block": (50, 60, 40),
    "crafting_table": (130, 90, 50), "stonecutter": (120, 120, 120), "grindstone": (140, 140, 140),
    "iron_door": (200, 200, 200), "iron_trapdoor": (200, 200, 200), "brewing_stand": (120, 110, 100),
    "spruce_shelf": (115, 85, 49), "red_carpet": (160, 39, 34), "lime_carpet": (112, 185, 25),
}
PLANTS = {"short_grass": (80, 140, 50), "fern": (70, 120, 45), "poppy": (200, 30, 30), "dandelion": (240, 210, 40),
          "cornflower": (70, 100, 220), "oxeye_daisy": (230, 230, 220), "azure_bluet": (210, 220, 230),
          "allium": (180, 110, 220), "lily_of_the_valley": (235, 235, 235), "wheat": (200, 180, 70),
          "carrots": (80, 150, 40), "potatoes": (90, 150, 50), "beetroots": (110, 140, 50)}

_unknown: set[str] = set()


def color_of(state: str):
    b = base(state)
    if b in COLORS:
        return COLORS[b]
    if b in PLANTS:
        return PLANTS[b]
    for suffix in ("_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door", "_pressure_plate"):
        if b.endswith(suffix):
            root = b[: -len(suffix)]
            for cand in (root, root + "s", root + "_planks", root.replace("brick", "bricks")):
                if cand in COLORS:
                    return COLORS[cand]
    if "banner" in b:
        return COLORS.get(b.split("_")[0] + "_wool", (161, 39, 34))
    if "sign" in b:
        return COLORS.get(b.split("_wall")[0].split("_hanging")[0].replace("_sign", "") + "_planks", (115, 85, 49))
    if b.startswith("potted_"):
        return (120, 60, 40)
    if b.endswith("_candle") or b == "candle":
        return (230, 220, 180)
    _unknown.add(b)
    return (255, 0, 255)


def shape_of(state: str):
    """Rough render shape: 'full', 'slab_b', 'slab_t', 'post', 'flat', 'plant', 'pane', 'water'."""
    b = base(state)
    _, p = parse_state(state)
    if b == "water":
        return "water"
    if b.endswith("_slab"):
        return "slab_t" if p.get("type") == "top" else ("full" if p.get("type") == "double" else "slab_b")
    if b.endswith("_carpet") or b.endswith("pressure_plate"):
        return "flat"
    if b in PLANTS or b.startswith("potted_") or b in ("flower_pot", "candle", "lantern", "campfire", "end_rod",
                                                        "iron_chain", "lightning_rod", "bell"):
        return "plant"
    if b.endswith("_fence") or (b.endswith("_wall") and "sign" not in b and "banner" not in b) or b == "ladder":
        return "post"
    if b.endswith("pane") or b == "iron_bars" or "trapdoor" in b or "door" in b or "sign" in b or "banner" in b:
        return "pane"
    return "full"


def _shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def render_top(canvas, path, scale=10, height=None):
    W, L = canvas.W, canvas.L
    H = height or canvas.H
    img = Image.new("RGB", (W * scale, L * scale), (30, 30, 30))
    d = ImageDraw.Draw(img)
    hmap = {}
    for x in range(W):
        for z in range(L):
            for y in range(H - 1, -1, -1):
                st = canvas.blocks.get((x, y, z))
                if st is not None:
                    hmap[(x, z)] = (y, st)
                    break
    for (x, z), (y, st) in hmap.items():
        col = color_of(st)
        hy = hmap.get((x - 1, z - 1), (y, st))[0]
        f = 0.82 + 0.03 * (y - 4) + (0.08 if y > hy else (-0.08 if y < hy else 0))
        f *= 0.94 + 0.12 * hrand(x, y, z, "px")
        d.rectangle([x * scale, z * scale, x * scale + scale - 1, z * scale + scale - 1], fill=_shade(col, f))
    img.save(path)


def render_iso(canvas, path, a=7, b=8, rotate=0, height=None):
    """Isometric view. rotate = number of 90° turns: 0 viewer in the SE corner, 1 NE, 2 NW, 3 SW."""
    W, L = canvas.W, canvas.L
    H = height or canvas.H

    def rot(x, z):
        w, l = W, L
        for _ in range(rotate % 4):
            x, z, w, l = l - 1 - z, x, l, w
        return x, z
    blocks = {}
    for (x, y, z), st in canvas.blocks.items():
        if y < H:
            rx, rz = rot(x, z)
            blocks[(rx, y, rz)] = st
    Wr = W if rotate % 2 == 0 else L
    Lr = L if rotate % 2 == 0 else W
    img_w = (Wr + Lr) * a + 4 * a
    img_h = (Wr + Lr) * a // 2 + H * b + 6 * a
    img = Image.new("RGB", (img_w, img_h), (236, 232, 222))
    d = ImageDraw.Draw(img)
    ox = Lr * a + 2 * a
    oy = H * b + 2 * a

    def P(x, y, z):
        return ox + (x - z) * a, oy + (x + z) * a / 2 - y * b

    def solid(pos):
        st = blocks.get(pos)
        return st is not None and shape_of(st) == "full"

    order = sorted(blocks.items(), key=lambda kv: (kv[0][0] + kv[0][2], kv[0][1], kv[0][0]))
    for (x, y, z), st in order:
        shp = shape_of(st)
        if shp == "full" and solid((x, y + 1, z)) and solid((x + 1, y, z)) and solid((x, y, z + 1)):
            continue
        col = color_of(st)
        j = 0.95 + 0.1 * hrand(x, y, z, "iso")
        if shp == "water":
            y0, y1, inset = y, y + 0.85, 0
        elif shp == "slab_b":
            y0, y1, inset = y, y + 0.5, 0
        elif shp == "slab_t":
            y0, y1, inset = y + 0.5, y + 1, 0
        elif shp == "flat":
            y0, y1, inset = y, y + 0.08, 0
        elif shp == "post":
            y0, y1, inset = y, y + 1, 0.36
        elif shp == "plant":
            y0, y1, inset = y, y + 0.7, 0.3
        elif shp == "pane":
            y0, y1, inset = y, y + 1, 0.2
        else:
            y0, y1, inset = y, y + 1, 0
        x0, x1, z0, z1 = x + inset, x + 1 - inset, z + inset, z + 1 - inset
        top = [P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)]
        right = [P(x1, y1, z0), P(x1, y1, z1), P(x1, y0, z1), P(x1, y0, z0)]
        left = [P(x0, y1, z1), P(x1, y1, z1), P(x1, y0, z1), P(x0, y0, z1)]
        if shp == "water":
            d.polygon(top, fill=_shade(col, 1.05 * j))
            continue
        d.polygon(left, fill=_shade(col, 0.72 * j))
        d.polygon(right, fill=_shade(col, 0.86 * j))
        d.polygon(top, fill=_shade(col, 1.08 * j))
    img.save(path)


def unknown_colors():
    return sorted(_unknown)
