# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Deterministyczny generator kramow targowych kupcow NPC (BetterModel 3.6.0).

    python skarbiec/tools/generate_stall_models.py          # zapisuje 4 pliki .bbmodel
    python skarbiec/tools/generate_stall_models.py --check  # tylko sprawdza (kod wyjscia 1 przy bledzie)

Wyjscie: skarbiec/src/main/resources/models/skarbiec_kram_<typ>.bbmodel
Tylko biblioteka standardowa. Plik niczego nie wdraza i nie dotyka serwera.

Uklad (jednostki Blockbench, 16 = 1 blok):
  NPC stoi w (0,0,0) za lada. Przod kramu (strona klienta) to -Z ("north").
  Lada: x -21..21, z -21.5..-10, blat y=15. Daszek: z -28..16, spod 34..37 (schodkowo
  opada ku przodowi), szyld nad przednia krawedzia. Wolne: |x|<10, |z|<10, 0<=y<34.
Kosci: kram (korzen) > lada, slupki, towar, daszek > falbana, szyld.
Animacje: wiatr (loop 4 s), dostawa (once 2 s).
"""
from pathlib import Path
import base64
import json
import math
import random
import struct
import sys
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'skarbiec/src/main/resources/models'
COPYRIGHT = 'Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl'
TYPES = ('ogolny', 'kowal', 'spozywczy', 'budowlany')
FACES = ('north', 'east', 'south', 'west', 'up', 'down')
REQUIRED_GROUPS = ('kram', 'lada', 'slupki', 'daszek', 'szyld', 'towar')
ALLOWED_ROT = (0, 22.5, -22.5, 45, -45)
FREE_ZONE = ((-10, 10), (0, 34), (-10, 10))      # obrys NPC: nic nie moze tu wchodzic
MODEL_BOUNDS = ((-25, 25), (0, 45), (-30, 17))   # granice calego kramu
MAX_CUBE = 48                                    # = szerokosc pokladu/wagonu w modelach Kolei
MAX_GROUP_SPAN = 48

# ---------------------------------------------------------------- atlas 128x128
# y 0..63   : 32 kafle materialow 16x16 (8 x 4)
# y 64..79  : 4 kafle pasow plotna 32x16 (7 pasow po 4 tekstele, symetryczne)
# y 80..95  : 4 szyldy 32x12 (kremowa deska, rama, symbol typu)
MATS = [
    ('dark_wood', (74, 49, 31)), ('plank', (160, 114, 68)), ('post', (98, 66, 40)), ('iron', (58, 61, 66)),
    ('steel', (150, 156, 162)), ('stone', (124, 122, 116)), ('brick', (152, 72, 50)), ('rope', (196, 170, 112)),
    ('burlap', (168, 138, 94)), ('grain', (222, 186, 92)), ('crust', (150, 88, 38)), ('bread', (204, 146, 74)),
    ('apple', (178, 32, 30)), ('cheese', (234, 192, 74)), ('pumpkin', (214, 116, 28)), ('leaf', (72, 112, 42)),
    ('glass', (176, 214, 224)), ('wicker', (152, 112, 60)), ('black_iron', (36, 36, 40)), ('rust', (138, 62, 36)),
    ('brass', (190, 150, 62)), ('rind', (198, 148, 52)), ('bark', (104, 76, 48)), ('flour', (216, 206, 182)),
    ('ogolny_a', (58, 118, 58)), ('ogolny_b', (226, 222, 208)), ('kowal_a', (68, 70, 75)), ('kowal_b', (150, 56, 40)),
    ('spozywczy_a', (228, 190, 58)), ('spozywczy_b', (214, 118, 38)), ('budowlany_a', (52, 84, 156)), ('budowlany_b', (226, 222, 208)),
]
MAT_INDEX = {n: i for i, (n, _) in enumerate(MATS)}
SIGN_BG = (226, 210, 164)

SYMBOLS = {
    # kłos zboża (dwa kłosy w V)
    'ogolny': ([
        '..g.......g..',
        '.gGg.....gGg.',
        '.Gg.g...g.gG.',
        '..gGg...gGg..',
        '..Gg.g.g.gG..',
        '...gGg.gGg...',
        '....g.s.g....',
        '.....sss.....',
        '......s......',
        '......s......',
    ], {'g': (214, 168, 52), 'G': (160, 112, 26), 's': (98, 110, 40)}),
    # młot nad kowadłem
    'kowal': ([
        '....HHHH.....',
        '....HHHHw....',
        '.........w...',
        '..........w..',
        '...........w.',
        '.AAAAAAAAA...',
        'AAAAAAAAAa...',
        '...AAAAA.....',
        '...AAAAA.....',
        '..AAAAAAA....',
    ], {'H': (70, 72, 78), 'w': (122, 80, 44), 'A': (46, 46, 52), 'a': (96, 98, 106)}),
    # bochenek chleba z nacięciami
    'spozywczy': ([
        '.............',
        '....bbbbb....',
        '..bbBbbBbbb..',
        '.bbbBbbBbBbb.',
        '.bbBbbBbbBbb.',
        'bbbbbbbbbbbbb',
        'cbbbbbbbbbbbc',
        '.ccccccccccc.',
        '..ccccccccc..',
        '.............',
    ], {'b': (196, 134, 62), 'B': (238, 196, 120), 'c': (132, 74, 30)}),
    # cegła z kielnią
    'budowlany': ([
        '..........kk.',
        '.........kkk.',
        '........kkkk.',
        '.......kkkkk.',
        '......w......',
        '.....w.......',
        'RRRRmRRRRR...',
        'mmmmmmmmmm...',
        'RRmRRRRmRR...',
        'RRmRRRRmRR...',
    ], {'R': (160, 64, 44), 'm': (196, 186, 166), 'k': (162, 168, 176), 'w': (122, 80, 44)}),
}


def clamp(v):
    return max(0, min(255, v))


def build_atlas():
    rng = random.Random(29102026)
    px = [[(0, 0, 0)] * 128 for _ in range(128)]
    # materialy
    for i, (name, base) in enumerate(MATS):
        ox, oy = (i % 8) * 16, (i // 8) * 16
        for v in range(16):
            for u in range(16):
                n = rng.randrange(-7, 8)
                if name in ('dark_wood', 'post', 'bark'):
                    n += -16 if (u + (v // 5)) % 5 == 0 else 0
                if name == 'plank':
                    n += -24 if v % 5 == 0 else (6 if (u * 3 + v) % 11 == 0 else 0)
                if name in ('iron', 'steel', 'black_iron', 'brass') and (u + v) % 9 == 0:
                    n += 18
                if name == 'stone' and (v % 6 == 0 or (u + (v // 6 % 2) * 4) % 8 == 0):
                    n -= 22
                if name == 'brick' and (v % 4 == 0 or (u + (v // 4 % 2) * 4) % 8 == 0):
                    px[oy + v][ox + u] = (190, 180, 160)
                    continue
                if name in ('burlap', 'wicker', 'rope') and (u + v) % 2 == 0:
                    n -= 12
                if name == 'wicker' and (u // 2 + v // 2) % 2 == 0:
                    n -= 14
                if name == 'grain' and rng.random() < .25:
                    n += 22
                if name == 'bread' and (u + v) % 6 == 0:
                    n += 24
                if name == 'apple' and (u, v) in ((4, 4), (5, 4), (4, 5), (10, 9)):
                    n += 60
                if name == 'cheese' and (u * 7 + v * 5) % 23 == 0:
                    n -= 40
                if name == 'pumpkin' and u % 4 == 0:
                    n -= 30
                if name == 'glass' and (u - v) % 7 == 0:
                    n += 30
                if name.endswith('_a') or name.endswith('_b'):
                    n = rng.randrange(-4, 5) + (-6 if v % 8 == 7 else 0)
                px[oy + v][ox + u] = tuple(clamp(c + n) for c in base)
    # pasy plotna: 7 pasow x 4 tekstele, symetryczne (lustrzane UV daje ten sam obraz)
    for t, typ in enumerate(TYPES):
        a, b = MATS[MAT_INDEX[typ + '_a']][1], MATS[MAT_INDEX[typ + '_b']][1]
        for v in range(16):
            for u in range(32):
                stripe = min(6, max(0, (u - 2) // 4))
                col = a if stripe % 2 == 0 else b
                n = rng.randrange(-4, 5) + (-8 if v in (0, 15) else 0)
                px[64 + v][t * 32 + u] = tuple(clamp(c + n) for c in col)
    # szyldy 32x12 + pas 32x4 ciemnego drewna pod spodem
    for t, typ in enumerate(TYPES):
        ox, oy = t * 32, 80
        rows, colors = SYMBOLS[typ]
        for v in range(16):
            for u in range(32):
                n = rng.randrange(-5, 6)
                if v >= 12 or u in (0, 31) or v in (0, 11):
                    col = MATS[MAT_INDEX['dark_wood']][1]
                elif u in (1, 30) or v in (1, 10):
                    col = (176, 132, 80)
                else:
                    col = SIGN_BG
                    sx, sy = u - 9, v - 1
                    if 0 <= sy < len(rows) and 0 <= sx < len(rows[sy]) and rows[sy][sx] != '.':
                        col = colors[rows[sy][sx]]
                        n //= 2
                px[oy + v][ox + u] = tuple(clamp(c + n) for c in col)
    # reszta: neutralne tlo
    for y in range(96, 128):
        for x in range(128):
            n = rng.randrange(-6, 7)
            px[y][x] = (clamp(120 + n), clamp(110 + n), clamp(96 + n))
    raw = b''.join(b'\0' + bytes(c for p in row for c in (p[0], p[1], p[2], 255)) for row in px)

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', 128, 128, 8, 6, 0, 0, 0))
            + chunk(b'tEXt', b'Copyright\0Copyright (c) 2026 Maciej Kaczmarczyk - lektoro.pl')
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


def stripe_uv(typ, flip=False):
    x = TYPES.index(typ) * 32
    return [x + 2, 65, x + 30, 79] if not flip else [x + 30, 65, x + 2, 79]


def sign_uv(typ):
    x = TYPES.index(typ) * 32
    return [x, 80, x + 32, 92]


# ---------------------------------------------------------------- geometria

def num(v):
    v = round(float(v), 4)
    if v == 0:
        return 0
    return int(v) if v == int(v) else v


def uid(*parts):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'medivalrpg/skarbiec/kram/' + '/'.join(str(p) for p in parts)))


class Model:
    def __init__(self, typ):
        self.typ = typ
        self.name = 'skarbiec_kram_' + typ
        self.elements = []
        # nazwa -> (rodzic, pivot)
        self.bones = {}
        self.order = []

    def bone(self, name, parent, pivot):
        self.bones[name] = (parent, [num(v) for v in pivot])
        self.order.append(name)

    def box(self, name, a, b, mat, group, faces=None, rotation=None, pivot=None):
        a = [num(v) for v in a]
        b = [num(v) for v in b]
        assert all(a[i] < b[i] for i in range(3)), (name, a, b)
        idx = len(self.elements)
        dims = [b[i] - a[i] for i in range(3)]
        face_dims = {'north': (dims[0], dims[1]), 'south': (dims[0], dims[1]),
                     'east': (dims[2], dims[1]), 'west': (dims[2], dims[1]),
                     'up': (dims[0], dims[2]), 'down': (dims[0], dims[2])}
        fd = {}
        for k, f in enumerate(FACES):
            if faces and f in faces:
                fd[f] = dict(uv=[num(v) for v in faces[f]], texture=0)
                continue
            m = faces.get('*', mat) if faces else mat
            i = MAT_INDEX[m]
            ox, oy = (i % 8) * 16 + 1, (i // 8) * 16 + 1
            w, h = (min(14, max(1, round(d))) for d in face_dims[f])
            seed = (idx * 7 + k * 3) % 97
            du, dv = seed % (15 - w), (seed // 3) % (15 - h)
            fd[f] = dict(uv=[ox + du, oy + dv, ox + du + w, oy + dv + h], texture=0)
        rot = [0, 0, 0]
        if rotation:
            axis, deg = rotation
            rot['xyz'.index(axis)] = num(deg)
        self.elements.append(dict(
            name=name, type='cube', uuid=uid(self.name, 'e', idx),
            **{'from': a, 'to': b},
            origin=[num(v) for v in (pivot or [0, 0, 0])], rotation=rot,
            box_uv=False, autouv=0, rescale=False, export=True, visibility=True,
            faces=fd))
        self.elements[-1]['_group'] = group

    def cylinder(self, name, center, radius, length, mat, group, axis='y', sides=6, top=None):
        ai = 'xyz'.index(axis)
        other = [i for i in range(3) if i != ai]
        for i in range(sides):
            low = -radius + i * 2 * radius / sides
            high = -radius + (i + 1) * 2 * radius / sides
            half = math.sqrt(max(0.0, radius * radius - ((low + high) / 2) ** 2))
            a, b = list(center), list(center)
            a[ai] -= length / 2
            b[ai] += length / 2
            a[other[0]] += low
            b[other[0]] += high
            a[other[1]] -= half
            b[other[1]] += half
            faces = {'up': top, 'down': top} if top and axis == 'y' else None
            if faces:
                faces = {'*': mat, 'up': self._tile(top), 'down': self._tile(top)}
            self.box(name, a, b, mat, group, faces=faces)

    @staticmethod
    def _tile(mat):
        i = MAT_INDEX[mat]
        ox, oy = (i % 8) * 16, (i // 8) * 16
        return [ox + 2, oy + 2, ox + 14, oy + 14]

    def document(self, png):
        groups = []
        for name in self.order:
            parent, pivot = self.bones[name]
            groups.append(dict(name=name, origin=pivot, rotation=[0, 0, 0], uuid=uid(self.name, 'g', name),
                               export=True, visibility=True, isOpen=True))
        by_name = {g['name']: g for g in groups}

        def node(name):
            g = by_name[name]
            children = [node(c) for c in self.order if self.bones[c][0] == name]
            children += [e['uuid'] for e in self.elements if e['_group'] == name]
            return dict(g, children=children)
        roots = [n for n in self.order if self.bones[n][0] is None]
        elements = [{k: v for k, v in e.items() if k != '_group'} for e in self.elements]
        return dict(
            meta=dict(format_version='5.0', model_format='free', box_uv=False),
            name=self.name, model_identifier=self.name, copyright=COPYRIGHT,
            resolution=dict(width=128, height=128),
            elements=elements, groups=groups, outliner=[node(r) for r in roots],
            textures=[dict(name=self.name + '.png', id='0', uuid=uid(self.name, 'texture'),
                           width=128, height=128, uv_width=128, uv_height=128,
                           source='data:image/png;base64,' + base64.b64encode(png).decode('ascii'),
                           internal=True, mode='bitmap', visible=True, render_mode='default')],
            animations=animations(self))


# ---------------------------------------------------------------- animacje

def fmt(v):
    s = '%.3f' % v
    s = s.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


def animator(model, anim, bone, channels):
    kfs = []
    for channel, points in channels.items():
        for i, (t, v) in enumerate(points):
            kfs.append(dict(channel=channel,
                            data_points=[dict(x=fmt(v[0]), y=fmt(v[1]), z=fmt(v[2]))],
                            uuid=uid(model.name, 'a', anim, bone, channel, i),
                            time=round(t, 4), color=-1, interpolation='linear'))
    return uid(model.name, 'g', bone), dict(name=bone, type='bone', rotation_global=False,
                                             quaternion_interpolation=False, keyframes=kfs)


def animation(model, name, loop, length, bones):
    return dict(uuid=uid(model.name, 'a', name), name=name, loop=loop, override=False, length=length,
                snapping=20, selected=False, anim_time_update='', blend_weight='', start_delay='',
                loop_delay='', animators=dict(animator(model, name, b, ch) for b, ch in bones.items()))


def wave(amplitude, phase, length=4.0, steps=16, axis=0, second=None):
    pts = []
    for i in range(steps + 1):
        t = length * i / steps
        a = 2 * math.pi * i / steps
        val = amplitude * math.sin(a + phase)
        if second:
            val += second * math.sin(2 * a + phase * 1.7)
        if i in (0, steps):
            val = amplitude * math.sin(phase) + (second * math.sin(phase * 1.7) if second else 0)
        v = [0.0, 0.0, 0.0]
        v[axis] = val
        pts.append((t, v))
    return pts


def animations(model):
    # wiatr: daszek kolysze sie wokol przedniej krawedzi (pivot kosci daszek), falbana mocniej,
    # szyld lekko w przeciwnej fazie. Petla domyka sie (t=0 == t=4).
    wiatr = animation(model, 'wiatr', 'loop', 4, {
        'daszek': {'rotation': wave(1.5, 0.0, second=0.4)},
        'falbana': {'rotation': wave(7.0, 0.6, second=2.0)},
        'szyld': {'rotation': [(t, [v[0], 0, v[0] * 0.4]) for t, v in wave(3.0, 2.2)]},
    })
    # dostawa: towar podskakuje, przysiada, przesuwa sie i wraca do pozycji wyjsciowej.
    pos = [(0, [0, 0, 0]), (0.2, [0, 4, 0]), (0.4, [0, 0, 0]), (0.6, [0.8, 2, 0]), (0.8, [0.8, 0, 0]),
           (1.1, [-0.6, 1, 0]), (1.4, [0, 0, 0]), (1.7, [0, 0.5, 0]), (2.0, [0, 0, 0])]
    scl = [(0, [1, 1, 1]), (0.2, [0.95, 1.08, 0.95]), (0.4, [1.08, 0.86, 1.08]), (0.6, [0.97, 1.04, 0.97]),
           (0.8, [1.04, 0.93, 1.04]), (1.1, [1, 1, 1]), (1.4, [1.02, 0.97, 1.02]), (2.0, [1, 1, 1])]
    rot = [(0, [0, 0, 0]), (0.6, [0, 4, 0]), (1.1, [0, -3, 0]), (1.4, [0, 0, 0]), (2.0, [0, 0, 0])]
    dostawa = animation(model, 'dostawa', 'once', 2, {
        'towar': {'position': pos, 'scale': scl, 'rotation': rot},
    })
    return [wiatr, dostawa]


# ---------------------------------------------------------------- konstrukcja kramu
ROOF_STEPS = [(-28, -17, 34), (-17, -6, 35), (-6, 5, 36), (5, 16, 37)]  # z0, z1, spod
ROOF_X = 24


def stall(typ):
    m = Model(typ)
    a_mat, b_mat = typ + '_a', typ + '_b'
    m.bone('kram', None, [0, 0, 0])
    m.bone('lada', 'kram', [0, 0, -15.5])
    m.bone('slupki', 'kram', [0, 0, 0])
    m.bone('towar', 'kram', [0, 15, -15.5])
    m.bone('daszek', 'kram', [0, 34, -28])
    m.bone('falbana', 'daszek', [0, 34, -28])
    m.bone('szyld', 'daszek', [0, 34, -28.9])

    # --- slupki: 4 cienkie slupy 2x2, belki pod daszkiem, tylna plachta
    for x in (-23, 21):
        m.box('slup_przod', [x, 0, -23], [x + 2, 35, -21], 'post', 'slupki')
        m.box('slup_tyl', [x, 0, 12], [x + 2, 38, 14], 'post', 'slupki')
        m.box('zwienczenie_przod', [x - .5, 35, -23.5], [x + 2.5, 36.5, -20.5], 'dark_wood', 'slupki')
        m.box('zwienczenie_tyl', [x - .5, 38, 11.5], [x + 2.5, 39.5, 14.5], 'dark_wood', 'slupki')
        m.box('stopa', [x - .5, 0, -23.5], [x + 2.5, 1, -20.5], 'stone', 'slupki')
        m.box('stopa', [x - .5, 0, 11.5], [x + 2.5, 1, 14.5], 'stone', 'slupki')
    m.box('belka_przod', [-21, 32.5, -23], [21, 34, -21], 'dark_wood', 'slupki')
    m.box('belka_tyl', [-21, 35.5, 12], [21, 37, 14], 'dark_wood', 'slupki')
    m.box('belka_dol_tyl', [-21, 5, 12.5], [21, 6.5, 13.5], 'dark_wood', 'slupki')
    m.box('plachta_tyl', [-21, 6.5, 12.6], [21, 35.5, 13.4], a_mat, 'slupki',
          faces={'north': stripe_uv(typ, True), 'south': stripe_uv(typ)})

    # --- daszek: 4 schodki opadajace ku przodowi, w pasy; boczne lambrekiny
    for i, (z0, z1, y) in enumerate(ROOF_STEPS):
        m.box('plotno_%d' % i, [-ROOF_X, y, z0], [ROOF_X, y + 1, z1], a_mat, 'daszek',
              faces={'up': stripe_uv(typ), 'down': stripe_uv(typ), 'north': stripe_uv(typ, True),
                     'south': stripe_uv(typ)})
        for x in (-ROOF_X, ROOF_X - .8):
            m.box('lambrekin_bok', [x, y - 2.5, z0], [x + .8, y, z1], a_mat, 'daszek')
    m.box('listwa_przod', [-ROOF_X, 34, -28.3], [ROOF_X, 35, -28], 'dark_wood', 'daszek')
    # falbana z przodu: 7 proporcow zgodnych z pasami (A,B,A,...)
    w = 2 * ROOF_X / 7
    for i in range(7):
        x0 = -ROOF_X + i * w
        mat = a_mat if i % 2 == 0 else b_mat
        m.box('falbana_%d' % i, [x0, 30, -28.4], [x0 + w, 34, -27.6], mat, 'falbana')
        m.box('falbana_szpic_%d' % i, [x0 + w / 2 - 1.2, 28.6, -28.3], [x0 + w / 2 + 1.2, 30, -27.7], mat, 'falbana')

    # --- szyld: deska z symbolem na przedniej krawedzi daszka
    m.box('szyld_deska', [-12, 34, -29.4], [12, 43, -28.4], 'dark_wood', 'szyld',
          faces={'north': sign_uv(typ)})
    for x in (-9, 8):
        m.box('szyld_wspornik', [x, 35, -28.4], [x + 1, 38, -26], 'iron', 'szyld')

    # --- lada (przed NPC, po stronie -Z)
    for x in (-20, 18):
        for z in (-20, -13):
            m.box('noga_lady', [x, 0, z], [x + 2, 14, z + 2], 'dark_wood', 'lada')
    m.box('front_lady', [-18, 1, -20], [18, 14, -19], 'plank', 'lada')
    for x in (-20, 19):
        m.box('bok_lady', [x, 1, -18], [x + 1, 14, -13], 'plank', 'lada')
    m.box('polka_lady', [-18, 2, -19], [18, 3, -12], 'plank', 'lada')
    m.box('blat', [-21, 14, -21.5], [21, 15, -10], 'plank', 'lada')
    m.box('listwa_blatu', [-21, 13, -21.6], [21, 14, -20.6], 'dark_wood', 'lada')
    m.box('plotno_lady', [-14, 8, -20.4], [14, 13, -20], a_mat, 'lada',
          faces={'north': stripe_uv(typ, True), 'south': stripe_uv(typ)})
    side_extras(m, typ)
    goods(m, typ)
    return m


def side_extras(m, typ):
    """Zaplecze z boku NPC (x 12..20) - statyczne, w kosci lada."""
    g = 'lada'
    if typ == 'ogolny':
        m.box('skrzynia_zaplecze', [12, 0, -2], [20, 8, 6], 'plank', g)
        m.box('skrzynia_zaplecze_okucie', [11.8, 3, -2.2], [20.2, 4.5, 6.2], 'iron', g)
        m.box('skrzynia_zaplecze', [13, 8, -1], [19, 13, 5], 'dark_wood', g)
        m.box('worek_zaplecze', [-20, 0, -2], [-13, 9, 5], 'burlap', g)
        m.box('worek_zaplecze_wiazanie', [-18, 9, 0], [-15, 11, 3], 'rope', g)
    elif typ == 'kowal':
        m.cylinder('beczka_hartownicza', [16, 6, 2], 4.5, 12, 'dark_wood', g, top='rust')
        for y in (2, 10):
            m.cylinder('obrecz', [16, y, 2], 4.8, 1, 'iron', g)
        m.box('woda', [12.6, 11.6, -1.4], [19.4, 12.2, 5.4], 'glass', g)
        m.box('wegiel_skrzynia', [-20, 0, -3], [-12, 5, 5], 'dark_wood', g)
        m.box('wegiel', [-19.5, 5, -2.5], [-12.5, 6.5, 4.5], 'black_iron', g)
    elif typ == 'spozywczy':
        for k, (x, z) in enumerate(((12, -2), (12, 4))):
            m.box('worek_maki', [x, 0, z], [x + 7, 8, z + 5.5], 'flour', g)
            m.box('worek_maki_wiazanie', [x + 2, 8, z + 1.5], [x + 5, 10, z + 4], 'rope', g)
        m.box('skrzynka_warzyw', [-20, 0, -3], [-12, 6, 5], 'plank', g)
        m.box('warzywa', [-19.5, 6, -2.5], [-12.5, 7.5, 4.5], 'pumpkin', g)
    else:
        for k in range(3):
            m.cylinder('kloda', [16 + (k % 2) * .4, 2 + k * 3.5, 2], 1.9, 14, 'bark', g, axis='z', sides=4)
        m.box('wiadro_zaprawy', [-19, 0, -2], [-13, 6, 4], 'dark_wood', g)
        m.box('zaprawa', [-18.5, 6, -1.5], [-13.5, 6.5, 3.5], 'flour', g)


def goods(m, typ):
    """Towar na ladzie (blat y=15, z -21..-10). Srodek (|x|<8) niski, zeby nie zaslaniac NPC."""
    g = 'towar'
    y = 15
    if typ == 'ogolny':
        for k, (x, z) in enumerate(((-19, -19.5), (-13, -18.5))):
            m.box('worek_zboza', [x, y, z], [x + 6, y + 7, z + 6], 'burlap', g)
            m.box('worek_zboza_gardlo', [x + 1.5, y + 7, z + 1.5], [x + 4.5, y + 8.5, z + 4.5], 'burlap', g)
            m.box('worek_zboza_wiazanie', [x + 1.3, y + 6.8, z + 1.3], [x + 4.7, y + 7.4, z + 4.7], 'rope', g)
        m.box('ziarno', [-12, y + 7, -17], [-9, y + 8, -14], 'grain', g)
        m.box('ziarno_kupka', [-6, y, -19], [0, y + 1.5, -14], 'grain', g)
        m.box('ziarno_kupka', [-5, y + 1.5, -18], [-1, y + 2.5, -15], 'grain', g)
        m.box('miarka', [1, y, -19], [5, y + 3, -15], 'dark_wood', g)
        m.box('miarka_zawartosc', [1.5, y + 3, -18.5], [4.5, y + 3.3, -15.5], 'grain', g)
        m.box('skrzynka', [8, y, -20], [14, y + 5, -14], 'plank', g)
        m.box('skrzynka_okucie', [7.8, y + 2, -20.2], [14.2, y + 3, -13.8], 'iron', g)
        m.cylinder('beczulka', [17.5, y + 3.2, -16.5], 3.2, 5.5, 'dark_wood', g, axis='x', sides=6)
        for x in (15.4, 19.6):
            m.cylinder('beczulka_obrecz', [x, y + 3.2, -16.5], 3.4, .6, 'iron', g, axis='x', sides=6)
        m.box('beczulka_kran', [20.3, y + 2.6, -17], [21, y + 3.6, -16], 'brass', g)
    elif typ == 'kowal':
        # kowadlo
        m.box('kowadlo_podstawa', [-17, y, -18], [-11, y + 1.5, -14], 'black_iron', g)
        m.box('kowadlo_talia', [-16, y + 1.5, -17.5], [-12, y + 4, -14.5], 'iron', g)
        m.box('kowadlo_blat', [-18, y + 4, -18], [-10, y + 6, -14], 'iron', g)
        m.box('kowadlo_rog', [-10, y + 4.5, -17], [-7.5, y + 5.7, -15], 'iron', g)
        m.box('kowadlo_lico', [-18, y + 6, -18], [-10, y + 6.2, -14], 'steel', g)
        m.box('mlot_trzonek', [-6, y, -20], [-5, y + 1, -13], 'post', g, rotation=('y', 22.5), pivot=[-5.5, y, -16.5])
        m.box('mlot_glowa', [-7.2, y, -14.5], [-3.8, y + 2, -12.8], 'black_iron', g, rotation=('y', 22.5), pivot=[-5.5, y, -16.5])
        # sztaby zelaza w stosie
        for k, (x, z, yy) in enumerate(((-1, -19, 0), (3, -19, 0), (1, -19, 1.5))):
            m.box('sztaba', [x, y + yy, z], [x + 3.6, y + yy + 1.5, z + 6], 'steel', g)
        # stojak z narzedziami
        m.box('stojak_podstawa', [10, y, -19], [20, y + 1, -13], 'dark_wood', g)
        for x in (10.5, 18.5):
            m.box('stojak_slup', [x, y + 1, -16.5], [x + 1, y + 13, -15.5], 'dark_wood', g)
        m.box('stojak_belka', [10.5, y + 11, -16.5], [19.5, y + 12, -15.5], 'dark_wood', g)
        m.box('stojak_belka', [10.5, y + 5, -16.5], [19.5, y + 6, -15.5], 'dark_wood', g)
        # kilof
        m.box('kilof_trzonek', [12.5, y + 1, -17.2], [13.5, y + 14, -16.6], 'post', g)
        m.box('kilof_grot', [10.8, y + 12.5, -17.4], [15.2, y + 14, -16.4], 'steel', g)
        m.box('kilof_szpic', [10.2, y + 11.8, -17.3], [11, y + 13, -16.5], 'steel', g)
        m.box('kilof_szpic', [15, y + 11.8, -17.3], [15.8, y + 13, -16.5], 'steel', g)
        # miecz ostrzem w gore
        m.box('miecz_glownia', [16.3, y + 4.5, -17.1], [17.3, y + 16, -16.7], 'steel', g)
        m.box('miecz_jelec', [15, y + 3.8, -17.3], [18.6, y + 4.6, -16.5], 'brass', g)
        m.box('miecz_rekojesc', [16.4, y + 1.6, -17.1], [17.2, y + 3.8, -16.7], 'dark_wood', g)
        m.box('miecz_glowica', [16.2, y + 1, -17.2], [17.4, y + 1.6, -16.6], 'brass', g)
        m.box('podkowa', [4, y, -14], [8, y + .6, -11], 'iron', g)
    elif typ == 'spozywczy':
        # bochenki
        for k, (x, z) in enumerate(((-20, -20), (-20, -15.5), (-14, -18))):
            m.box('bochenek', [x, y, z], [x + 6, y + 2.5, z + 3.5], 'crust', g)
            m.box('bochenek_wierzch', [x + .5, y + 2.5, z + .5], [x + 5.5, y + 3.5, z + 3], 'bread', g)
        # kosz jablek
        m.box('kosz_dno', [-6, y, -19], [2, y + 1, -13], 'wicker', g)
        m.box('kosz_bok', [-6, y + 1, -19], [2, y + 4, -18], 'wicker', g)
        m.box('kosz_bok', [-6, y + 1, -14], [2, y + 4, -13], 'wicker', g)
        m.box('kosz_bok', [-6, y + 1, -18], [-5, y + 4, -14], 'wicker', g)
        m.box('kosz_bok', [1, y + 1, -18], [2, y + 4, -14], 'wicker', g)
        rng = random.Random(1508)
        for k, (x, z, yy) in enumerate(((-5, -18, 3), (-3, -18, 3), (-1, -18, 3), (-5, -16, 3), (-3, -16, 3.4),
                                        (-1, -16, 3), (-4, -17, 4.6), (-2, -17, 4.8))):
            s = 2 + rng.choice((0, .2))
            m.box('jablko', [x, y + yy, z], [x + s, y + yy + s, z + s], 'apple' if k != 5 else 'leaf', g)
        m.box('jablko_luzem', [3, y, -14], [5, y + 2, -12], 'apple', g)
        # kolo sera z wycietym klinem
        m.cylinder('ser', [10, y + 1.75, -16.5], 4, 3.5, 'rind', g, sides=6, top='cheese')
        m.box('ser_klin', [15, y, -20], [18, y + 2.5, -18], 'cheese', g)
        # dynia
        m.cylinder('dynia', [17, y + 2.8, -14], 3.5, 5.6, 'pumpkin', g, sides=6)
        m.box('dynia_ogonek', [16.5, y + 5.6, -14.5], [17.5, y + 7, -13.5], 'leaf', g)
    else:
        # stos cegiel w wiazaniu
        for layer in range(4):
            off = 0 if layer % 2 == 0 else 2
            for k in range(2 if off else 3):
                x = -20 + off + k * 4
                m.box('cegla', [x, y + layer * 2, -19], [x + 4, y + layer * 2 + 2, -15], 'brick', g)
        # deski w stosie
        for k in range(3):
            m.box('deska', [-7, y + k * 1.2, -20 + k * .4], [7, y + k * 1.2 + 1.1, -16.5 + k * .4], 'plank', g)
        m.box('kielnia_ostrze', [-3, y + 3.6, -15], [1, y + 3.9, -12.5], 'steel', g)
        m.box('kielnia_raczka', [1, y + 3.6, -14.2], [4, y + 4.4, -13.4], 'post', g)
        # tafle szkla w stojaku
        m.box('stojak_szkla', [8, y, -18], [14, y + 1, -13], 'dark_wood', g)
        for k in range(3):
            z = -17 + k * 1.4
            m.box('tafla_szkla', [8.5, y + 1, z], [13.5, y + 8, z + .5], 'glass', g)
        # kamien ciosany
        m.box('kamien', [15, y, -20], [20.5, y + 5, -14.5], 'stone', g)
        m.box('kamien', [16, y + 5, -19], [19.5, y + 7.5, -15.5], 'stone', g)
    return m


# ---------------------------------------------------------------- zapis i kontrola

def dump(doc):
    return (json.dumps(doc, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def generate():
    png = build_atlas()
    return {OUT / ('skarbiec_kram_%s.bbmodel' % t): dump(stall(t).document(png)) for t in TYPES}


def rotate_point(e, p):
    p = [p[i] - e['origin'][i] for i in range(3)]
    for axis in range(3):
        deg = e['rotation'][axis]
        if not deg:
            continue
        t = math.radians(deg)
        c, s = math.cos(t), math.sin(t)
        i, j = ((1, 2), (2, 0), (0, 1))[axis]
        p[i], p[j] = p[i] * c - p[j] * s, p[i] * s + p[j] * c
    return [p[i] + e['origin'][i] for i in range(3)]


def world_aabb(e):
    pts = [rotate_point(e, [e['to' if mask >> i & 1 else 'from'][i] for i in range(3)]) for mask in range(8)]
    return [min(p[i] for p in pts) for i in range(3)], [max(p[i] for p in pts) for i in range(3)]


def check_png(data):
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos, image, tags = 8, b'', []
    while pos < len(data):
        size = struct.unpack('>I', data[pos:pos + 4])[0]
        tag, payload = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + size]
        assert zlib.crc32(tag + payload) == struct.unpack('>I', data[pos + 8 + size:pos + 12 + size])[0]
        if tag == b'IHDR':
            assert struct.unpack('>IIBBBBB', payload) == (128, 128, 8, 6, 0, 0, 0)
        if tag == b'IDAT':
            image += payload
        tags.append(tag)
        pos += 12 + size
    assert pos == len(data) and tags[0] == b'IHDR' and tags[-1] == b'IEND'
    raw = zlib.decompress(image)
    assert len(raw) == 128 * 513 and all(raw[y * 513] == 0 for y in range(128))
    assert all(raw[y * 513 + 1 + x * 4 + 3] == 255 for y in range(128) for x in range(128))


def validate(path, d):
    """Zwraca statystyki; AssertionError przy naruszeniu reguly."""
    assert path.stem == d['name'] == d['model_identifier'], path
    assert d['meta'] == dict(format_version='5.0', model_format='free', box_uv=False)
    assert d['resolution'] == dict(width=128, height=128)
    es = {e['uuid']: e for e in d['elements']}
    gs = {g['uuid']: g for g in d['groups']}
    all_ids = list(es) + list(gs) + [t['uuid'] for t in d['textures']] + [a['uuid'] for a in d['animations']]
    all_ids += [k['uuid'] for a in d['animations'] for an in a['animators'].values() for k in an['keyframes']]
    assert len(all_ids) == len(set(all_ids)), 'zduplikowane uuid'
    assert len(es) == len(d['elements']) and len(gs) == len(d['groups'])
    names = [g['name'] for g in d['groups']]
    assert len(names) == len(set(names)), 'zduplikowane nazwy grup'
    for req in REQUIRED_GROUPS:
        assert req in names, 'brak grupy ' + req
    # hierarchia: jedyny korzen 'kram', kazdy element w dokladnie jednej grupie
    assert len(d['outliner']) == 1 and d['outliner'][0]['name'] == 'kram', 'korzeniem musi byc kram'
    refs, bones, member = [], [], {}

    def visit(nodes, group):
        for n in nodes:
            if isinstance(n, str):
                assert n in es and group is not None
                refs.append(n)
                member[n] = group
            else:
                assert n['uuid'] in gs and n['origin'] == gs[n['uuid']]['origin'] and n['name'] == gs[n['uuid']]['name']
                bones.append(n['uuid'])
                visit(n['children'], n['name'])
    visit(d['outliner'], None)
    assert sorted(refs) == sorted(es) and sorted(bones) == sorted(gs)
    # geometria
    max_cube = [0, 0, 0]
    spans = {}
    for e in es.values():
        assert e['type'] == 'cube'
        for key in ('from', 'to', 'origin', 'rotation'):
            assert len(e[key]) == 3 and all(math.isfinite(v) for v in e[key])
        assert all(a < b for a, b in zip(e['from'], e['to'])), e['name']
        rot = [r for r in e['rotation'] if r]
        assert len(rot) <= 1 and all(r in ALLOWED_ROT for r in rot), ('rotacja', e['name'], e['rotation'])
        for i in range(3):
            max_cube[i] = max(max_cube[i], e['to'][i] - e['from'][i])
            assert e['to'][i] - e['from'][i] <= MAX_CUBE, ('za duza kostka', e['name'])
        lo, hi = world_aabb(e)
        for i in range(3):
            assert MODEL_BOUNDS[i][0] - 1e-6 <= lo[i] and hi[i] <= MODEL_BOUNDS[i][1] + 1e-6, ('poza obrysem', e['name'], lo, hi)
        inside = all(lo[i] < FREE_ZONE[i][1] - 1e-6 and hi[i] > FREE_ZONE[i][0] + 1e-6 for i in range(3))
        assert not inside, ('element w obrysie NPC', e['name'], lo, hi)
        g = member[e['uuid']]
        s = spans.setdefault(g, [[1e9] * 3, [-1e9] * 3])
        for i in range(3):
            s[0][i] = min(s[0][i], lo[i])
            s[1][i] = max(s[1][i], hi[i])
        assert set(e['faces']) == set(FACES), e['name']
        for f in e['faces'].values():
            u = f['uv']
            assert f['texture'] == 0 and len(u) == 4 and all(0 <= v <= 128 for v in u), ('uv', e['name'], u)
            assert u[0] != u[2] and u[1] != u[3], ('pusta uv', e['name'], u)
    group_span = {g: [round(s[1][i] - s[0][i], 3) for i in range(3)] for g, s in spans.items()}
    for g, sp in group_span.items():
        assert max(sp) <= MAX_GROUP_SPAN, ('za duza rozpietosc grupy', g, sp)
    # blat lady 14..15 i przestrzen przed lada
    lada = [e for e in es.values() if e['name'] == 'blat']
    assert len(lada) == 1 and 14 <= lada[0]['to'][1] <= 15 and lada[0]['from'][2] < -10
    for e in es.values():
        lo, hi = world_aabb(e)
        if hi[2] <= -21.7 + 1e-6 and lo[1] < 28:
            raise AssertionError(('przed lada musi byc wolne miejsce', e['name']))
    # tekstura
    assert len(d['textures']) == 1
    png = base64.b64decode(d['textures'][0]['source'].split(',', 1)[1], validate=True)
    check_png(png)
    # animacje
    anims = {a['name']: a for a in d['animations']}
    assert set(anims) == {'wiatr', 'dostawa'}, set(anims)
    assert anims['wiatr']['loop'] == 'loop' and 3 <= anims['wiatr']['length'] <= 5
    assert anims['dostawa']['loop'] == 'once' and 1.5 <= anims['dostawa']['length'] <= 2.5
    gid = {g['name']: g['uuid'] for g in d['groups']}
    assert gid['daszek'] in anims['wiatr']['animators'] and gid['szyld'] in anims['wiatr']['animators']
    assert set(anims['dostawa']['animators']) == {gid['towar']}
    for a in anims.values():
        for k, an in a['animators'].items():
            assert k in gs and an['name'] == gs[k]['name']
            by_ch = {}
            for kf in an['keyframes']:
                assert kf['channel'] in ('rotation', 'position', 'scale') and kf['interpolation'] == 'linear'
                assert 0 <= kf['time'] <= a['length']
                by_ch.setdefault(kf['channel'], []).append(kf)
            for ch, kfs in by_ch.items():
                times = [k['time'] for k in kfs]
                assert times == sorted(times) and times[0] == 0 and times[-1] == a['length'], (a['name'], ch)
                vals = [[float(k['data_points'][0][c]) for c in 'xyz'] for k in kfs]
                rest = [1, 1, 1] if ch == 'scale' else [0, 0, 0]
                if a['loop'] == 'loop':
                    assert vals[0] == vals[-1], ('petla nie domyka sie', a['name'], ch)
                    if ch == 'rotation':
                        assert all(abs(v) <= 10 for vv in vals for v in vv)
                else:
                    assert vals[0] == rest and vals[-1] == rest, ('once nie wraca do pozycji', a['name'], ch)
    return dict(cubes=len(es), groups=len(gs), max_cube=max_cube, group_span=group_span,
                bounds=[[round(min(world_aabb(e)[0][i] for e in es.values()), 3) for i in range(3)],
                        [round(max(world_aabb(e)[1][i] for e in es.values()), 3) for i in range(3)]])


def main(argv):
    check = '--check' in argv
    files = generate()
    problems = []
    existing = sorted(p.name for p in OUT.glob('skarbiec_kram_*.bbmodel')) if OUT.exists() else []
    expected = sorted(p.name for p in files)
    if check and existing != expected:
        problems.append('zestaw plikow %s != %s' % (existing, expected))
    for path, content in files.items():
        if check:
            if not path.exists():
                problems.append('brak ' + path.name)
                continue
            if path.read_bytes() != content:
                problems.append('nieaktualny (rozni sie bajtowo): ' + path.name)
            content = path.read_bytes()
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        try:
            st = validate(path, json.loads(content.decode('utf-8')))
            print('PASS %s: %d kostek, %d kosci, max kostka %s, obrys %s' % (
                path.stem, st['cubes'], st['groups'], [round(v, 2) for v in st['max_cube']], st['bounds']))
            print('     rozpietosc grup: ' + ', '.join('%s=%s' % kv for kv in sorted(st['group_span'].items())))
        except AssertionError as exc:
            problems.append('%s: %s' % (path.name, exc))
    # testy negatywne walidatora
    sample_path = next(iter(files))
    base = json.loads(files[sample_path].decode('utf-8'))
    for mutation in ('npc', 'uv', 'rotation', 'group', 'anim', 'uuid'):
        d = json.loads(json.dumps(base))
        if mutation == 'npc':
            d['elements'][0]['from'] = [-1, 0, -1]
            d['elements'][0]['to'] = [1, 5, 1]
        if mutation == 'uv':
            d['elements'][0]['faces']['up']['uv'][2] = 130
        if mutation == 'rotation':
            d['elements'][0]['rotation'] = [10, 0, 0]
        if mutation == 'group':
            d['groups'][1]['name'] = 'stol'
        if mutation == 'anim':
            d['animations'] = d['animations'][:1]
        if mutation == 'uuid':
            d['elements'][1]['uuid'] = d['elements'][0]['uuid']
        try:
            validate(sample_path, d)
        except (AssertionError, KeyError, StopIteration):
            continue
        problems.append('walidator przepuscil uszkodzenie: ' + mutation)
    if problems:
        for p in problems:
            print('ERROR: ' + p)
        return 1
    print('OK: %d modeli %s' % (len(files), '(zgodne bajt w bajt)' if check else '(zapisane)'))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
