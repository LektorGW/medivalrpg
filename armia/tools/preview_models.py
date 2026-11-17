# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Offline preview: renders the real cubes of every Armia model, with bone rest rotations and sampled
animation poses (Blockbench conventions: ZYX Euler, R = Rz*Ry*Rx, positions in parent space, 16 px/block),
into PNG contact sheets. Standard library only. Colours are the average of each face's atlas tile.

    python preview_models.py <out dir> [model ...]
"""
import base64, itertools, json, math, struct, sys, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / 'src/main/resources/models'


def rx(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return ((1, 0, 0), (0, c, -s), (0, s, c))


def ry(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return ((c, 0, s), (0, 1, 0), (-s, 0, c))


def rz(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return ((c, -s, 0), (s, c, 0), (0, 0, 1))


def mm(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)) for i in range(3))


def mv(m, v):
    return tuple(m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] for i in range(3))


def euler(r):
    return mm(rz(r[2]), mm(ry(r[1]), rx(r[0])))


class Affine:
    """x -> M x + t (M may contain scale)."""
    def __init__(self, m=((1, 0, 0), (0, 1, 0), (0, 0, 1)), t=(0, 0, 0)):
        self.m, self.t = m, t

    def then(self, other):  # other after self
        return Affine(mm(other.m, self.m), tuple(a + b for a, b in zip(mv(other.m, self.t), other.t)))

    def apply(self, p):
        return tuple(a + b for a, b in zip(mv(self.m, p), self.t))


def bone_local(origin, rest, rot, pos, scale):
    """T(origin + pos) * R(rest + rot) * S(scale) * T(-origin)."""
    r = euler([rest[i] + rot[i] for i in range(3)])
    m = tuple(tuple(r[i][j] * scale[j] for j in range(3)) for i in range(3))
    t = tuple(origin[i] + pos[i] - mv(m, origin)[i] for i in range(3))
    return Affine(m, t)


def sample(keys, t, default):
    if not keys:
        return default
    keys = sorted(keys, key=lambda k: k[0])
    if t <= keys[0][0]:
        return keys[0][1]
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            u = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(a + (b - a) * u for a, b in zip(v0, v1))
    return keys[-1][1]


def load(name):
    d = json.loads((ROOT / (name + '.bbmodel')).read_text(encoding='utf-8'))
    png = base64.b64decode(d['textures'][0]['source'].split(',')[1])
    return d, tile_colors(png)


def tile_colors(png):
    pos, data = 8, b''
    while pos < len(png):
        size = struct.unpack('>I', png[pos:pos + 4])[0]
        tag = png[pos + 4:pos + 8]
        if tag == b'IDAT':
            data += png[pos + 8:pos + 8 + size]
        pos += size + 12
    raw = zlib.decompress(data)
    colors = {}
    for k in range(16):
        ox, oy = (k % 4) * 32, (k // 4) * 32
        acc = [0, 0, 0]
        for y in range(2, 30):
            row = raw[(oy + y) * 513 + 1:(oy + y + 1) * 513]
            for x in range(2, 30):
                for c in range(3):
                    acc[c] += row[(ox + x) * 4 + c]
        colors[k] = tuple(v // (28 * 28) for v in acc)
    return colors


def pose(d, anims, t):
    """World transform of every element for animations [(name, time)]."""
    tracks = {}
    for name, at in anims:
        a = next(x for x in d['animations'] if x['name'] == name)
        local = min(at, a['length']) if a['loop'] != 'loop' else at % a['length']
        for uuid_, an in a['animators'].items():
            ch = tracks.setdefault(uuid_, {})
            for c in ('rotation', 'position', 'scale'):
                keys = [(k['time'], tuple(float(k['data_points'][0][q]) for q in 'xyz')) for k in an['keyframes'] if k['channel'] == c]
                if keys:
                    ch[c] = sample(keys, local, None)
    groups = {g['uuid']: g for g in d['groups']}
    out = {}

    def walk(nodes, parent):
        for n in nodes:
            if isinstance(n, str):
                out[n] = parent
                continue
            g = groups[n['uuid']]
            tr = tracks.get(n['uuid'], {})
            local = bone_local(g['origin'], g.get('rotation', [0, 0, 0]), tr.get('rotation') or (0, 0, 0),
                               tr.get('position') or (0, 0, 0), tr.get('scale') or (1, 1, 1))
            walk(n['children'], local.then(parent))
    walk(d['outliner'], Affine())
    return out


def cube_faces(e, world):
    a, b = e['from'], e['to']
    corners = [tuple(b[i] if bits[i] else a[i] for i in range(3)) for bits in itertools.product((0, 1), repeat=3)]
    rot = e['rotation']
    if any(rot):
        r = euler(rot)
        o = e['origin']
        corners = [tuple(o[i] + mv(r, tuple(c[j] - o[j] for j in range(3)))[i] for i in range(3)) for c in corners]
    corners = [world.apply(c) for c in corners]
    quads = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    return [[corners[i] for i in q] for q in quads]


class Canvas:
    def __init__(self, w, h, bg=(232, 228, 216)):
        self.w, self.h = w, h
        self.px = bytearray(bytes(bg) * w * h)

    def polygon(self, pts, col):
        ys = [p[1] for p in pts]
        for y in range(max(0, math.ceil(min(ys))), min(self.h, math.ceil(max(ys)))):
            xs = []
            for p, q in zip(pts, pts[1:] + pts[:1]):
                if min(p[1], q[1]) <= y + .5 < max(p[1], q[1]):
                    xs.append(p[0] + (y + .5 - p[1]) * (q[0] - p[0]) / (q[1] - p[1]))
            xs.sort()
            for l, r in zip(xs[::2], xs[1::2]):
                lo, hi = max(0, math.ceil(l)), min(self.w, math.ceil(r))
                if hi > lo:
                    self.px[(y * self.w + lo) * 3:(y * self.w + hi) * 3] = bytes(col) * (hi - lo)

    def line(self, x0, y0, x1, y1, col):
        n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
        for i in range(n + 1):
            x, y = int(x0 + (x1 - x0) * i / n), int(y0 + (y1 - y0) * i / n)
            if 0 <= x < self.w and 0 <= y < self.h:
                self.px[(y * self.w + x) * 3:(y * self.w + x) * 3 + 3] = bytes(col)

    def save(self, path):
        def chunk(tag, data):
            return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
        raw = b''.join(b'\0' + self.px[y * self.w * 3:(y + 1) * self.w * 3] for y in range(self.h))
        Path(path).write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 2, 0, 0, 0))
                               + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))


LIGHT = (0.35, 0.85, -0.4)


def draw(canvas, d, colors, anims, view, cx, cy, scale, water=None):
    yaw, pitch = view
    cyw, syw = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def project(p):
        x = p[0] * cyw + p[2] * syw
        z = -p[0] * syw + p[2] * cyw
        y = p[1] * cp - z * sp
        depth = p[1] * sp + z * cp
        return cx + x * scale, cy - y * scale, depth
    worlds = pose(d, anims, 0)
    polys = []
    for e in d['elements']:
        world = worlds[e['uuid']]
        uv = e['faces']['north']['uv']
        col = colors[int(uv[1] // 32) * 4 + int(uv[0] // 32)]
        for quad in cube_faces(e, world):
            u = [quad[1][i] - quad[0][i] for i in range(3)]
            v = [quad[2][i] - quad[0][i] for i in range(3)]
            n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            ln = math.sqrt(sum(c * c for c in n)) or 1
            shade = 0.55 + 0.45 * abs(sum(n[i] / ln * LIGHT[i] for i in range(3)))
            pts = [project(p) for p in quad]
            polys.append((sum(p[2] for p in pts) / 4, [(p[0], p[1]) for p in pts], tuple(min(255, int(c * shade)) for c in col)))
    for _, pts, col in sorted(polys, key=lambda q: q[0]):
        canvas.polygon(pts, col)
    if water is not None:  # waterline marker
        a, b = project((-60, water, -100)), project((60, water, 100))
        canvas.line(a[0], a[1], b[0], b[1], (60, 110, 170))


VIEWS = {'iso': (-35, 22), 'bok': (-90, 0), 'gora': (0, 89), 'przod': (0, 8)}


def sheet(name, out, frames):
    d, colors = load(name)
    cols = len(frames)
    cell_w, cell_h = 340, 300
    canvas = Canvas(cell_w * cols, cell_h * 2)
    xs = [p for e in d['elements'] for p in (e['from'][2], e['to'][2])]
    ys = [p for e in d['elements'] for p in (e['from'][1], e['to'][1])]
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    scale = 250 / span
    for col, (label, anims) in enumerate(frames):
        for row, view in enumerate(('iso', 'bok')):
            cx, cy = col * cell_w + cell_w / 2, row * cell_h + cell_h * .78
            draw(canvas, d, colors, anims, VIEWS[view], cx, cy, scale)
    canvas.save(Path(out) / (name + '.png'))
    return Path(out) / (name + '.png')


SHEETS = {
    'armia_koga': [('spoczynek', []), ('rozwiniete', [('zagle_rozwin', 9)]), ('lopot', [('zagle_rozwin', 9), ('lopot', .25), ('flagi', .25)]),
                   ('kotwica', [('kotwica_opusc', 9)]), ('fala', [('fala', 1.0)])],
    'armia_karawela': [('spoczynek', []), ('rozwiniete', [('zagle_rozwin', 9)]), ('zwiniete', [('zagle_zwin', 9)]),
                       ('kotwica', [('kotwica_opusc', 9)])],
    'armia_trebusz': [('napiety', []), ('wyrzut .05', [('strzal', .05)]), ('wyrzut .1', [('strzal', .1)]), ('po strzale', [('strzal', 1)]), ('ladowanie 2s', [('strzal', 1), ('ladowanie', 2)])],
    'armia_katapulta': [('napieta', []), ('wyrzut .04', [('strzal', .04)]), ('uderzenie', [('strzal', .11)]), ('po strzale', [('strzal', .5)]), ('ladowanie', [('ladowanie', 1.5)])],
    'armia_taran': [('spoczynek', []), ('zamach', [('ladowanie', 2)]), ('uderzenie', [('uderzenie', .12)]), ('jazda', [('jazda', .125)])],
    'armia_armata': [('spoczynek', []), ('wystrzal', [('strzal', .04)]), ('dym', [('strzal', .45)]), ('odrzut', [('strzal', 1.2)]), ('ladowanie', [('strzal', 1.4), ('ladowanie', 1.4)])],
}


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else '.')
    out.mkdir(parents=True, exist_ok=True)
    wanted = sys.argv[2:] or list(SHEETS)
    for name in wanted:
        print(sheet(name, out, SHEETS[name]))


if __name__ == '__main__':
    main()
