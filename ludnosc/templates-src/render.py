# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
# Isometric preview of a Ludnosc template YAML (rough, for eyeballing shapes). Stairs/slabs are drawn as cubes.
import sys, re
from PIL import Image, ImageDraw

def parse(path):
    pal, layers, meta = {}, [], {}
    lines = open(path, encoding='utf-8').read().split('\n')
    i = 0; mode = None; cur = None
    while i < len(lines):
        ln = lines[i]
        if ln.startswith('palette:'): mode = 'pal'
        elif ln.startswith('layers:'): mode = 'lay'
        elif mode == 'pal' and ln.startswith("  '"):
            m = re.match(r"  '(.)': '?(.*?)'?$", ln); pal[m.group(1)] = m.group(2).replace('{kolor}', 'red')
        elif mode == 'lay' and ln.strip() == '- |':
            cur = []; layers.append(cur)
        elif mode == 'lay' and ln.startswith('    ') and cur is not None:
            cur.append(ln[4:])
        elif re.match(r'^[a-z-]+: ', ln):
            k, v = ln.split(': ', 1); meta[k] = v
        i += 1
    return pal, layers, meta

COL = [
 ('white_concrete', (235, 237, 238)), ('light_gray_concrete', (160, 160, 155)), ('gray_concrete', (90, 94, 98)),
 ('black_concrete', (25, 27, 32)), ('yellow_concrete', (240, 190, 30)), ('black_stained_glass', (40, 40, 50)),
 ('gray_stained_glass', (110, 115, 125)), ('light_gray_stained_glass', (165, 170, 175)), ('resin', (200, 90, 40)),
 ('smooth_sandstone', (220, 205, 160)), ('sandstone', (215, 200, 150)), ('smooth_quartz', (236, 230, 223)),
 ('iron_block', (200, 200, 200)), ('sea_lantern', (200, 230, 225)), ('blast_furnace', (100, 100, 105)), ('furnace', (110, 110, 110)),
 ('calcite', (226, 226, 220)), ('oxidized', (82, 162, 132)), ('copper', (192, 112, 82)), ('packed_mud', (140, 104, 78)), ('polished_deepslate', (72, 72, 78)),
 ('cobbled_deepslate', (80, 80, 85)), ('yellow_stained_glass', (240, 220, 120)),
 ('red_wool', (170, 30, 35)), ('red_carpet', (170, 30, 35)), ('red_banner', (170, 30, 35)), ('red_wall_banner', (170, 30, 35)),
 ('yellow_wool', (240, 200, 40)), ('white_wool', (240, 240, 240)), ('light_gray_wool', (160, 160, 160)), ('green_bed', (80, 120, 40)),
 ('blue_bed', (50, 60, 160)), ('yellow_bed', (230, 200, 40)), ('brown', (110, 75, 45)),
 ('stained_glass', (150, 190, 230)), ('gold_block', (250, 210, 60)), ('quartz', (235, 230, 222)), ('smooth_stone', (160, 160, 160)),
 ('deepslate_tile', (55, 55, 60)), ('polished_andesite', (135, 138, 138)), ('polished_blackstone', (45, 40, 50)),
 ('leaves', (60, 120, 40)), ('grass_block', (100, 160, 70)), ('coarse_dirt', (120, 85, 60)), ('gravel', (130, 125, 120)),
 ('poppy', (210, 30, 30)), ('cornflower', (70, 90, 220)), ('allium', (180, 100, 220)), ('azure', (220, 230, 240)), ('daisy', (240, 240, 210)),
 ('tulip', (240, 120, 140)), ('lily', (240, 240, 240)), ('orchid', (60, 160, 220)), ('bell', (230, 190, 40)), ('candle', (240, 230, 190)),
 ('chiseled_stone', (120, 120, 120)), ('pumpkin', (220, 130, 30)), ('melon', (100, 160, 40)), ('white_terracotta', (210, 180, 160)),
 ('water', (60, 110, 220)), ('lava', (255, 120, 20)), ('glass', (190, 220, 240)), ('iron_bars', (150, 150, 150)),
 ('red_bed', (190, 30, 30)), ('white_bed', (235, 235, 235)), ('bed', (200, 60, 60)), ('door', (110, 80, 50)),
 ('dark_oak_stairs', (65, 45, 25)), ('dark_oak', (75, 52, 30)), ('stripped_spruce', (140, 105, 65)), ('spruce_log', (70, 50, 30)),
 ('spruce', (115, 85, 50)), ('oak_planks', (170, 135, 80)), ('white_terracotta', (210, 180, 160)), ('mossy', (100, 120, 80)),
 ('cracked', (110, 110, 110)), ('stone_brick', (125, 125, 125)), ('cobblestone', (110, 110, 110)), ('andesite', (135, 135, 135)),
 ('bricks', (150, 75, 60)), ('hay', (200, 170, 40)), ('torch', (255, 220, 80)), ('lantern', (255, 200, 60)), ('campfire', (230, 120, 40)),
 ('chest', (160, 110, 40)), ('barrel', (120, 85, 45)), ('crafting', (150, 100, 60)), ('banner', (200, 20, 20)), ('carpet', (130, 80, 50)),
 ('ladder', (150, 110, 60)), ('fence', (115, 85, 50)), ('chain', (80, 80, 90)), ('potted', (200, 80, 80)),
 ('rail', (140, 120, 90)), ('oak_log', (105, 84, 50)), ('oak', (168, 132, 80)), ('stone', (128, 128, 128)), ('dirt', (125, 90, 60)),
 # workshops (glassworks, brickworks, sheepfold); appended last so earlier matches keep their colours
 ('mud_brick', (137, 104, 79)), ('brick_', (150, 75, 60)), ('sand', (219, 207, 163)), ('clay', (160, 166, 179)),
 ('composter', (120, 85, 45)), ('loom', (180, 150, 110)), ('cauldron', (60, 60, 65)), ('coal_block', (30, 30, 32)),
]
def color(state):
    for k, c in COL:
        if k in state: return c
    return (180, 60, 180)

THIN = ('pane', 'bars', 'fence', 'torch', 'lantern', 'chain', 'potted', 'carpet', 'pressure', 'banner', 'ladder', 'trapdoor', 'poppy', 'cornflower', 'allium', 'bluet', 'daisy', 'tulip', 'lily', 'orchid', 'rod', '_wall[', 'candle')

def shade(c, f): return tuple(max(0, min(255, int(v * f))) for v in c)

def resolve(pal, level, materials):
    """Material slots (@wall...) resolved like the plugin does for a settlement level (0 = Osada ... 4 = Stolica)."""
    import lib
    out = {}
    for ch, st in pal.items():
        if st.startswith('@'):
            bid, _, rest = st.partition('[')
            props = dict(kv.split('=') for kv in rest.rstrip(']').split(',')) if rest else {}
            block, keep = lib.resolve_slot(bid, props, level, (materials or {}).get(level))
            st = block + ('[' + ','.join(f'{k}={v}' for k, v in keep.items()) + ']' if keep else '')
        out[ch] = st
    return out

def render(path, out, a=14, level=1, materials=None):
    pal, layers, meta = parse(path)
    pal = resolve(pal, level, materials)
    H = len(layers); D = len(layers[0]); W = len(layers[0][0])
    g = int(meta.get('ground-layer', 0))
    imgs = []
    for view in (0, 1):
        cells = []
        for y in range(H):
            for z in range(D):
                for x in range(W):
                    ch = layers[y][z][x]
                    if ch in '._': continue
                    st = pal[ch]
                    if view == 1: xx, zz = W - 1 - x, D - 1 - z
                    else: xx, zz = x, z
                    cells.append((xx, y, zz, st))
        # ground plane
        for z in range(-2, D + 2):
            for x in range(-2, W + 2):
                cells.append((x, g - 0.001 - 1 + 1 - 1, z, '#ground'))
        cells.sort(key=lambda c: (c[0] + c[2], c[1]))
        cw = (W + D + 6) * a + 40; ch_ = (W + D + 6) * a // 2 + (H + 3) * a + 40
        im = Image.new('RGB', (cw, ch_), (235, 240, 250)); dr = ImageDraw.Draw(im)
        ox = (D + 3) * a + 20; oy = (H + 2) * a + 20
        def P(x, y, z): return (ox + (x - z) * a, oy + (x + z) * a / 2 - y * a)
        for (x, y, z, st) in cells:
            if st == '#ground':
                c = (110, 160, 80); y = g - 1
                top = [P(x, y + 1, z), P(x + 1, y + 1, z), P(x + 1, y + 1, z + 1), P(x, y + 1, z + 1)]
                dr.polygon(top, fill=c, outline=shade(c, 0.85)); continue
            c = color(st)
            thin = any(t in st for t in THIN)
            hgt = 1.0
            if 'slab' in st or ('bed' in st) : hgt = 0.55
            if 'carpet' in st or 'pressure' in st: hgt = 0.1
            if thin and hgt == 1.0: 
                # draw a narrow column
                cx, cz = x + 0.35, z + 0.35; s = 0.3
                y0 = y
                top = [P(cx, y0 + 1, cz), P(cx + s, y0 + 1, cz), P(cx + s, y0 + 1, cz + s), P(cx, y0 + 1, cz + s)]
                left = [P(cx, y0 + 1, cz + s), P(cx + s, y0 + 1, cz + s), P(cx + s, y0, cz + s), P(cx, y0, cz + s)]
                right = [P(cx + s, y0 + 1, cz), P(cx + s, y0 + 1, cz + s), P(cx + s, y0, cz + s), P(cx + s, y0, cz)]
                if 'pane' in st or 'bars' in st or 'ladder' in st or 'banner' in st:
                    top = [P(x, y0 + 1, z + .4), P(x + 1, y0 + 1, z + .4), P(x + 1, y0 + 1, z + .6), P(x, y0 + 1, z + .6)]
                    left = [P(x, y0 + 1, z + .6), P(x + 1, y0 + 1, z + .6), P(x + 1, y0, z + .6), P(x, y0, z + .6)]
                    right = [P(x+1, y0 + 1, z+.4), P(x + 1, y0 + 1, z + .6), P(x + 1, y0, z + .6), P(x + 1, y0, z+.4)]
                dr.polygon(left, fill=shade(c, 0.8)); dr.polygon(right, fill=shade(c, 0.65)); dr.polygon(top, fill=c)
                continue
            if 'stairs' in st:
                # bottom half + top half on the facing side (approx: draw full cube darker top edge)
                hgt = 1.0
            top = [P(x, y + hgt, z), P(x + 1, y + hgt, z), P(x + 1, y + hgt, z + 1), P(x, y + hgt, z + 1)]
            left = [P(x, y + hgt, z + 1), P(x + 1, y + hgt, z + 1), P(x + 1, y, z + 1), P(x, y, z + 1)]
            right = [P(x + 1, y + hgt, z), P(x + 1, y + hgt, z + 1), P(x + 1, y, z + 1), P(x + 1, y, z)]
            dr.polygon(left, fill=shade(c, 0.8), outline=shade(c, 0.6))
            dr.polygon(right, fill=shade(c, 0.62), outline=shade(c, 0.5))
            dr.polygon(top, fill=c, outline=shade(c, 0.85))
            if 'stairs' in st:
                f = re.search(r'facing=(\w+)', st).group(1)
                dr.text(P(x + .3, y + 1, z + .3), f[0].upper(), fill=(255, 255, 255))
        imgs.append(im)
    tot = Image.new('RGB', (imgs[0].width * 2, imgs[0].height), (255, 255, 255))
    tot.paste(imgs[0], (0, 0)); tot.paste(imgs[1], (imgs[0].width, 0))
    tot.save(out)

if __name__ == '__main__':
    render(sys.argv[1], sys.argv[2])
