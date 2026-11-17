# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Deterministic Blockbench 5 models for Armia: ships (koga, karawela) and siege engines
(trebusz, katapulta, taran, armata). Standard library only; no server access.

    python generate_models.py           # writes ../src/main/resources/models/*.bbmodel (+ manifest/atlas here)
    python generate_models.py --check   # exit 1 when committed files differ from a fresh build

Contract (shared with pl.medivalrpg.armia.visual.ModelCatalog and its tests):
  16 units = 1 block. Ships: origin (0,0,0) = the physical boat entity, bow +Z, deck top exactly at
  DeckRules.deckHeight*16, the flat deck covers the walkable area (DeckRules.walkableHalfLength) and
  nothing on the walkway is taller than a step (0.6 block) except thin masts and overhead castles (>= 1.9).
  Siege engines: origin = feet of the crew soldier, engine body in front (+Z).
"""
import base64, json, math, random, struct, sys, uuid, zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent / 'src/main/resources/models'
U = 16
NS = uuid.UUID('3c2f4f1e-6a1d-4f63-9b8e-6f7a2d1c0b55')

# 4x4 atlas of 32 px tiles (128x128). Materials named in Polish where the players see them.
MATERIALS = [
    ('kadlub', (92, 60, 36)), ('poklad', (156, 116, 72)), ('belka', (66, 45, 28)), ('plotno', (226, 214, 186)),
    ('barwa', (236, 236, 236)), ('lina', (170, 134, 86)), ('zelazo', (58, 60, 64)), ('braz', (170, 116, 50)),
    ('kamien', (128, 126, 120)), ('smola', (36, 31, 29)), ('zloto', (205, 162, 58)), ('skora', (108, 70, 42)),
    ('dym', (196, 196, 194)), ('drewno', (188, 146, 92)), ('czerwien', (146, 40, 32)), ('ogien', (255, 196, 92)),
]
MAT = {name: i for i, (name, _) in enumerate(MATERIALS)}


def uid(*parts):
    return str(uuid.uuid5(NS, '/'.join(str(p) for p in parts)))


def fmt(v):
    s = '%.4f' % v
    s = s.rstrip('0').rstrip('.')
    return '0' if s in ('-0', '') else s


# ----------------------------------------------------------------------------------------------- texture
def atlas():
    px = bytearray(128 * 128 * 4)
    rng = random.Random(29102026)
    for k, (name, col) in enumerate(MATERIALS):
        ox, oy = (k % 4) * 32, (k // 4) * 32
        for y in range(32):
            for x in range(32):
                n = rng.randint(-6, 6)
                if name in ('kadlub', 'smola'):  # clinker strakes: dark seam every 6 px, nail heads
                    n += -26 if y % 6 == 0 else (8 if y % 6 == 1 else 0)
                    if (x + (y // 6) * 7) % 11 == 0 and y % 6 == 3:
                        n -= 18
                if name == 'poklad':  # planks along the length, staggered butt joints
                    n += -24 if x % 5 == 0 else 0
                    if y == ((x // 5) * 9) % 32:
                        n -= 20
                if name in ('belka', 'drewno'):
                    n += int(9 * math.sin((x + rng.random()) * 0.9)) - (14 if x % 9 == 0 else 0)
                if name in ('plotno', 'barwa'):  # weave and a faint seam
                    n += (3 if (x + y) % 2 else -3) - (10 if x % 8 == 0 else 0) // (2 if name == 'barwa' else 1)
                if name == 'lina':
                    n += 14 if (x + y) % 4 < 2 else -10
                if name in ('zelazo', 'braz') and (x % 10 == 4 and y % 10 == 4):
                    n += 30
                if name == 'braz':
                    n += int(10 * math.sin(y * 0.4))
                if name == 'kamien':
                    n += rng.choice((-18, -6, 0, 0, 6, 14))
                if name == 'zloto':
                    n += 18 if (x + y) % 7 == 0 else 0
                if name == 'dym':
                    n = int(8 * math.sin(x * 0.3) * math.cos(y * 0.25))
                if name == 'ogien':
                    n = -abs(16 - y) * 3
                off = ((oy + y) * 128 + ox + x) * 4
                px[off:off + 4] = bytes([max(0, min(255, c + n)) for c in col] + [255])

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
    raw = b''.join(b'\0' + px[y * 512:(y + 1) * 512] for y in range(128))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 128, 128, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


# ----------------------------------------------------------------------------------------------- model
class Model:
    def __init__(self, name):
        self.name = name
        self.elements, self.groups, self.out, self.anims = [], [], [], []
        self.by_name = {}
        self.meta = {}

    def group(self, name, origin, parent=None, rotation=(0, 0, 0)):
        assert name not in self.by_name, name
        g = {'name': name, 'origin': [float(v) for v in origin], 'rotation': [float(v) for v in rotation],
             'uuid': uid(self.name, 'group', name), 'export': True, 'visibility': True, 'isOpen': True,
             'children': []}
        self.groups.append(g)
        self.by_name[name] = g
        (parent['children'] if parent else self.out).append(g)
        return g

    def box(self, name, a, b, mat, group, rot=None, pivot=None):
        """rot = (axis, degrees) with degrees in -45..45 step 22.5 (vanilla item-model limit)."""
        a, b = [float(v) for v in a], [float(v) for v in b]
        for i in range(3):
            if a[i] > b[i]:
                a[i], b[i] = b[i], a[i]
            if b[i] - a[i] < 0.05:
                b[i] = a[i] + 0.05
        rotation = [0.0, 0.0, 0.0]
        if rot:
            axis, deg = rot
            assert deg in (-45, -22.5, 22.5, 45), deg
            rotation['xyz'.index(axis)] = float(deg)
        origin = [float(v) for v in (pivot or [(a[i] + b[i]) / 2 for i in range(3)])]
        k = MAT[mat]
        tx, ty = (k % 4) * 32, (k // 4) * 32
        size = [b[i] - a[i] for i in range(3)]

        def uv(w, h):
            w, h = min(30.0, max(1.0, w)), min(30.0, max(1.0, h))
            return [tx + 1, ty + 1, tx + 1 + w, ty + 1 + h]
        faces = {'north': uv(size[0], size[1]), 'south': uv(size[0], size[1]), 'east': uv(size[2], size[1]),
                 'west': uv(size[2], size[1]), 'up': uv(size[0], size[2]), 'down': uv(size[0], size[2])}
        e = {'name': name, 'type': 'cube', 'uuid': uid(self.name, 'cube', len(self.elements)), 'from': a, 'to': b,
             'origin': origin, 'rotation': rotation, 'box_uv': False, 'autouv': 0, 'rescale': False,
             'export': True, 'visibility': True, 'faces': {f: {'uv': v, 'texture': 0} for f, v in faces.items()},
             'material': mat}
        self.elements.append(e)
        group['children'].append(e['uuid'])
        return e

    def octagon(self, name, center, r, a0, a1, axis, mat, group):
        """Faceted cylinder (two boxes, 0 and 45 degrees) along an axis between a0 and a1."""
        x, y, z = center
        s = r * 0.9239
        if axis == 'x':
            for deg in (None, ('x', 45)):
                self.box(name, [a0, y - s, z - s], [a1, y + s, z + s], mat, group, deg, [x, y, z] if deg else None)
        elif axis == 'z':
            for deg in (None, ('z', 45)):
                self.box(name, [x - s, y - s, a0], [x + s, y + s, a1], mat, group, deg, [x, y, z] if deg else None)
        else:
            for deg in (None, ('y', 45)):
                self.box(name, [x - s, a0, z - s], [x + s, a1, z + s], mat, group, deg, [x, y, z] if deg else None)

    def wheel(self, name, center, r, width, parent, mat='belka', hub='zelazo'):
        g = self.group(name, center, parent)
        x, y, z = center
        self.octagon('obrecz', center, r, x - width / 2, x + width / 2, 'x', mat, g)
        self.box('opona', [x - width / 2 - .2, y - r - .2, z - 1.2], [x + width / 2 + .2, y - r + 1.2, z + 1.2], 'zelazo', g)
        for deg in (None, ('x', 45), ('x', -45)):
            self.box('szprycha', [x - .6, y - r + 1, z - .7], [x + .6, y + r - 1, z + .7], 'drewno', g, deg, [x, y, z] if deg else None)
        self.box('szprycha', [x - .6, y - .7, z - r + 1], [x + .6, y + .7, z + r - 1], 'drewno', g)
        self.box('piasta', [x - width / 2 - 1, y - 1.6, z - 1.6], [x + width / 2 + 1, y + 1.6, z + 1.6], hub, g)
        return g

    # ---------------------------------------------------------------------------------- animations
    def anim(self, name, length, loop, tracks, priority_hint=0):
        """tracks: {bone: {channel: [(time, (x, y, z)), ...]}}; loop: loop|once|hold."""
        animators = {}
        for bone, channels in tracks.items():
            g = self.by_name[bone]
            kfs = []
            for channel, points in channels.items():
                for i, (t, v) in enumerate(points):
                    assert 0 <= t <= length + 1e-9, (name, bone, t)
                    kfs.append({'channel': channel, 'data_points': [{'x': fmt(v[0]), 'y': fmt(v[1]), 'z': fmt(v[2])}],
                                'uuid': uid(self.name, name, bone, channel, i), 'time': round(t, 4), 'color': -1,
                                'interpolation': 'linear'})
            animators[g['uuid']] = {'name': bone, 'type': 'bone', 'rotation_global': False, 'keyframes': kfs}
        self.anims.append({'uuid': uid(self.name, 'anim', name), 'name': name, 'loop': loop, 'override': True,
                           'length': length, 'snapping': 20, 'selected': False, 'anim_time_update': '',
                           'blend_weight': '', 'start_delay': '', 'loop_delay': '', 'animators': animators})

    def save(self, png):
        elements = [{k: v for k, v in e.items() if k != 'material'} for e in self.elements]

        def strip(node):
            return {k: v for k, v in node.items() if k != 'children'}

        def outline(node):
            return dict(strip(node), children=[c if isinstance(c, str) else outline(c) for c in node['children']])
        d = {'meta': {'format_version': '5.0', 'model_format': 'free', 'box_uv': False}, 'name': self.name,
             'model_identifier': self.name, 'resolution': {'width': 128, 'height': 128}, 'elements': elements,
             'groups': [strip(g) for g in self.groups], 'outliner': [outline(g) for g in self.out],
             'textures': [{'name': self.name + '.png', 'id': '0', 'uuid': uid(self.name, 'texture'), 'width': 128,
                           'height': 128, 'uv_width': 128, 'uv_height': 128,
                           'source': 'data:image/png;base64,' + base64.b64encode(png).decode(), 'internal': True,
                           'mode': 'bitmap', 'visible': True, 'render_mode': 'default'}],
             'animations': self.anims}
        text = json.dumps(d, ensure_ascii=False, separators=(',', ':'))
        info = {'name': self.name, 'cubes': len(self.elements), 'bones': [g['name'] for g in self.groups],
                'animations': {a['name']: {'length': a['length'], 'loop': a['loop'],
                                           'bones': sorted(x['name'] for x in a['animators'].values())} for a in self.anims}}
        info.update(self.meta)
        return text, info


# ----------------------------------------------------------------------------------------------- ships
SHIPS = {
    # id: NavalRules half width / half length (blocks); deck = 1 + halfWidth (DeckRules.deckHeight)
    'koga': {'type': 'transportowiec', 'hw': 1.5, 'hl': 4.5},
    'karawela': {'type': 'karawela', 'hw': 2.0, 'hl': 5.5},
}


def walkable_half_length(hw, hl):
    scale = hw
    end = hl - .7 - scale / 2
    lateral = max(scale * .45, hw - .12 - scale * .45)
    radius = scale / 2 + .3 - .005
    return min(hl - .55, end + math.sqrt(max(0, radius * radius - lateral * lateral)))


def ship(kind):
    spec = SHIPS[kind]
    hw, hl = spec['hw'] * U, spec['hl'] * U
    deck = (1 + spec['hw']) * U
    walk = walkable_half_length(spec['hw'], spec['hl']) * U
    m = Model('armia_' + kind)
    m.meta.update({'shipType': spec['type'], 'deckTop': deck, 'walkHalfLength': walk, 'walkHalfWidth': hw - .12 * U,
                   'hullHalfWidth': hw, 'hullHalfLength': hl})
    caravel = kind == 'karawela'
    root = m.group('statek', [0, deck, 0])
    hull = m.group('kadlub', [0, deck, 0], root)
    keel_y = -14.0
    top = deck - 2.0  # under the deck planks
    # Clinker hull: horizontal strakes, each a little wider than the one below; ends taper in steps.
    levels = 9
    for i in range(levels):
        y0 = keel_y + (top - keel_y) * i / levels
        y1 = keel_y + (top - keel_y) * (i + 1) / levels
        f = (i + 1) / levels
        w = hw * (0.34 + 0.66 * math.sqrt(f)) - (0 if i == levels - 1 else 0.6)
        length = hl * (0.78 + 0.22 * f)
        straight = min(walk, length) * (0.72 + 0.28 * f)
        mat = 'smola' if y1 <= 2 else 'kadlub'
        m.box('poszycie', [-w, y0, -straight], [w, y1 + .3, straight], mat, hull)
        steps = 4
        for s in range(steps):
            z0 = straight + (length - straight) * s / steps
            z1 = straight + (length - straight) * (s + 1) / steps
            ws = w * (1 - ((s + .5) / steps) ** 1.7)
            for sign in (1, -1):
                lo, hi = sorted((sign * z0, sign * z1))
                m.box('dziob' if sign > 0 else 'rufa', [-ws, y0, lo], [ws, y1 + .3, hi], mat, hull)
    # keel, stem and stern posts
    m.box('stepka', [-1.2, keel_y - 2, -hl * .8], [1.2, keel_y + 1, hl * .8], 'belka', hull)
    m.box('dziobnica', [-1.2, keel_y, hl - 2], [1.2, deck + 14, hl + 1.5], 'belka', hull)
    m.box('tylnica', [-1.4, keel_y, -hl - 1.5], [1.4, deck + 10, -hl + 2], 'belka', hull)
    # wale: dark band along the hull just above the waterline and a red strake under the gunwale
    m.box('listwa', [-hw - .4, 9, -walk], [hw + .4, 12, walk], 'belka', hull)
    m.box('pas', [-hw - .2, deck - 6, -walk], [hw + .2, deck - 3, walk], 'czerwien', hull)
    m.box('listwa_gorna', [-hw - .3, deck - 15, -walk + 4], [hw + .3, deck - 13, walk - 4], 'drewno', hull)
    # flat deck: exactly the walkable rectangle, top at deck
    m.box('poklad', [-(hw - 2), deck - 2, -walk - 1], [hw - 2, deck, walk + 1], 'poklad', hull)
    # bulwarks: low (7 units < step height 9.6), walk-over; they converge into the pointed ends
    rail = 7.0
    for sx in (1, -1):
        m.box('burta', [sx * (hw - 2), deck - 3, -walk - 1], [sx * hw, deck + rail - 1.5, walk + 1], 'kadlub', hull)
        m.box('reling', [sx * (hw - 2.4), deck + rail - 1.5, -walk - 1], [sx * (hw + .4), deck + rail, walk + 1], 'belka', hull)
    for sz in (1, -1):
        for s in range(4):
            z0, z1 = walk + 1 + (hl - walk - 1) * s / 4, walk + 1 + (hl - walk - 1) * (s + 1) / 4
            ws = hw * (1 - ((s + .5) / 4) ** 1.6)
            lo, hi = sorted((sz * z0, sz * z1))
            m.box('nadburcie', [-ws, deck - 2, lo], [ws, deck + rail + 2 * s, hi], 'kadlub', hull)
            m.box('nadburcie_lis', [-ws - .3, deck + rail + 2 * s, lo], [ws + .3, deck + rail + 2 * s + 1.4, hi], 'belka', hull)
    # flush hatches and low deck details (all below the step height)
    m.box('luk', [-6, deck, -10], [6, deck + 1, 2], 'belka', hull)
    m.box('kratownica', [-5, deck + 1, -9], [5, deck + 1.4, 1], 'drewno', hull)
    for sx in (1, -1):
        m.box('zwoj_liny', [sx * (hw - 8), deck, walk - 16], [sx * (hw - 3), deck + 2, walk - 11], 'lina', hull)
        m.box('knaga', [sx * (hw - 4), deck, 8], [sx * (hw - 2.5), deck + 3, 11], 'belka', hull)
    # overhead castles: platforms >= 1.9 block above the deck, posts on the deck edge (x = +-(hw-2))
    castle_y = deck + 33  # underside at deck+31 = 1.94 block: free headroom for a standing player
    shields = m.group('tarcze', [0, castle_y, 0], root)

    def castle(z0, z1, front):
        lo, hi = sorted((z0, z1))
        m.box('kasztel', [-hw - 1, castle_y, lo], [hw + 1, castle_y + 3, hi], 'poklad', hull)
        m.box('kasztel_spod', [-hw - 1, castle_y - 2, lo], [hw + 1, castle_y, hi], 'belka', hull)
        m.box('blanka_czolo', [-hw - 1, castle_y + 3, front - 1], [hw + 1, castle_y + 7, front + 1], 'kadlub', hull)
        m.box('nadproze', [-hw + 1, castle_y - 3, front - 1.2], [hw - 1, castle_y - 2, front + 1.2], 'belka', hull)
        for sx in (1, -1):
            inner, outer = sx * (hw - 1), sx * (hw + 1)
            m.box('slup', [sx * (hw - 2) - 1, deck, front - 1], [sx * (hw - 2) + 1, castle_y - 2, front + 1], 'belka', hull)
            back = hi if front == lo else lo
            m.box('slup_tylny', [sx * (hw - 2) - 1, deck + rail, back - 1], [sx * (hw - 2) + 1, castle_y - 2, back + 1], 'belka', hull)
            m.box('podciag', [sx * (hw - 1) - 1.2, castle_y - 3, lo], [sx * (hw - 1) + 1.2, castle_y - 2, hi], 'belka', hull)
            m.box('miecz', [sx * (hw - 2) - .8, castle_y - 9, front - 1], [sx * (hw - 2) + .8, castle_y - 2, front + 1], 'belka', hull,
                  ('x', 45 if front == lo else -45), [sx * (hw - 2), castle_y - 5.5, front])
            m.box('blanka_bok', [inner, castle_y + 3, lo], [outer, castle_y + 7, hi], 'kadlub', hull)
            for k in range(int((hi - lo - 4) // 10)):
                zz = lo + 4 + k * 10
                m.box('blanka', [inner, castle_y + 7, zz], [outer, castle_y + 11, zz + 5], 'kadlub', hull)
                m.box('tarcza', [outer, castle_y - 1, zz - 1], [sx * (hw + 1.8), castle_y + 6, zz + 6], 'barwa', shields)
    helm_z = -hl + 1.5 * U
    if caravel:
        castle(-hl - 8, helm_z + 12, helm_z + 12)
    else:
        castle(-hl - 6, helm_z + 4, helm_z + 4)
        castle(hl + 8, walk - 14, walk - 14)
    # stern lantern (emissive bone via the BetterModel glow_ tag)
    lantern = m.group('glow_latarnia', [0, castle_y + 12, -hl - 4], root)
    m.box('latarnia', [-2.5, castle_y + 8, -hl - 6.5], [2.5, castle_y + 15, -hl - 1.5], 'ogien', lantern)
    m.box('latarnia_dach', [-3, castle_y + 15, -hl - 7], [3, castle_y + 16.5, -hl - 1], 'zelazo', lantern)
    # rudder + tiller: one bone pivoting on the sternpost; the helmsman sits at helm_z
    rudder = m.group('ster', [0, deck, -hl + .5], root)
    m.box('pletwa', [-1, keel_y + 2, -hl - 9], [1, deck - 4, -hl - .5], 'belka', rudder)
    m.box('okucie', [-1.4, 4, -hl - 9.5], [1.4, 6, -hl - .2], 'zelazo', rudder)
    m.box('trzon', [-1.5, deck - 6, -hl - 1.5], [1.5, deck + 14, -hl + 1.5], 'belka', rudder)
    m.box('rumpel', [-1, deck + 12, -hl], [1, deck + 14, helm_z - 6], 'drewno', rudder)
    m.box('rumpel_uchwyt', [-1.3, deck + 11.5, helm_z - 9], [1.3, deck + 14.5, helm_z - 6], 'skora', rudder)
    tip = m.group('ster_koniec', [0, keel_y + 3.5, -hl - 8.5], rudder)  # marker bone for orientation tests
    m.box('znacznik', [-.3, keel_y + 3, -hl - 9], [.3, keel_y + 4, -hl - 8], 'belka', tip)
    # anchor at the bow cathead
    anchor = m.group('kotwica', [hw + 3, deck - 2, walk - 4], root)
    m.box('lina_kotwicy', [hw + 2.6, deck - 14, walk - 4.4], [hw + 3.4, deck - 2, walk - 3.6], 'lina', anchor)
    m.box('trzon_kotwicy', [hw + 2.4, deck - 26, walk - 4.6], [hw + 3.6, deck - 14, walk - 3.4], 'zelazo', anchor)
    m.box('ramie_kotwicy', [hw + 2.4, deck - 27, walk - 9], [hw + 3.6, deck - 25.6, walk + 1], 'zelazo', anchor)
    for sz in (-1, 1):
        m.box('lapa', [hw + 2.4, deck - 25.6, walk - 4 + sz * 5 - 1], [hw + 3.6, deck - 22, walk - 4 + sz * 5 + 1], 'zelazo', anchor)
    m.box('kluza', [hw - 1, deck - 3, walk - 6], [hw + 4.5, deck - 1, walk - 2], 'belka', hull)
    # masts and sails
    masts = [('gl', 18 if caravel else 6, 150 if caravel else 118, 'kwadrat')]
    if caravel:
        masts.append(('bez', -34, 96, 'lacina'))
        # bowsprit
        m.box('bukszpryt', [-1.5, deck + 4, hl - 8], [1.5, deck + 7, hl + 30], 'belka', hull, ('x', -22.5), [0, deck + 5, hl - 8])
    flags = []
    for mid, mz, height, rig in masts:
        mast_top = deck + height
        m.box('maszt_' + mid, [-2.5, deck - 2, mz - 2.5], [2.5, mast_top, mz + 2.5], 'belka', hull)
        m.box('opaska_' + mid, [-3, deck, mz - 3], [3, deck + 6, mz + 3], 'zelazo', hull)
        if rig == 'kwadrat':
            yard_y = mast_top - 14
            half = hw + (14 if caravel else 10)
            m.box('bocianie_gniazdo', [-6, mast_top - 30, mz - 6], [6, mast_top - 24, mz + 6], 'kadlub', hull)
            m.box('bocianie_gniazdo_dno', [-5, mast_top - 31, mz - 5], [5, mast_top - 30, mz + 5], 'belka', hull)
            sail = m.group('zagiel_' + mid, [0, yard_y, mz + 3], root)
            m.box('reja_' + mid, [-half - 4, yard_y - 1.5, mz + 2.5], [half + 4, yard_y + 1.5, mz + 5.5], 'belka', hull)
            cloth = m.group('plotno_' + mid, [0, yard_y, mz + 4], sail)
            depth = height - 52
            for k in range(4):  # billowing square sail: middle panels bulge forward
                y_hi = yard_y - depth * k / 4
                y_lo = yard_y - depth * (k + 1) / 4
                bulge = [1, 3, 3.5, 2][k]
                m.box('zagiel', [-half + k, y_lo, mz + 4 + bulge], [half - k, y_hi, mz + 5.2 + bulge], 'plotno', cloth)
            m.box('lik', [-half + 3, yard_y - depth - 1, mz + 5], [half - 3, yard_y - depth, mz + 6.5], 'lina', cloth)
            emblem = m.group('herb_' + mid, [0, yard_y - depth / 2, mz + 9], cloth)
            cy = yard_y - depth / 2
            m.box('herb_pion', [-3, cy - depth * .36, mz + 8.5], [3, cy + depth * .36, mz + 9.3], 'barwa', emblem)
            m.box('herb_poziom', [-half * .62, cy + depth * .05, mz + 8.5], [half * .62, cy + depth * .05 + 6, mz + 9.3], 'barwa', emblem)
            m.meta.setdefault('sails', []).append({'bone': 'zagiel_' + mid, 'cloth': 'plotno_' + mid, 'emblem': 'herb_' + mid,
                                                   'yardY': yard_y, 'depth': depth})
        else:  # lateen: a long slanted yard, triangular sail
            pivot_y = deck + height * .78
            sail = m.group('zagiel_' + mid, [0, pivot_y, mz + 3], root, rotation=(35, 0, 0))
            yard_len = 104
            m.box('reja_' + mid, [-1.5, pivot_y - 1.5, mz + 3 - yard_len * .6], [1.5, pivot_y + 1.5, mz + 3 + yard_len * .4], 'belka', sail)
            cloth = m.group('plotno_' + mid, [0, pivot_y, mz + 3], sail)
            for k in range(5):
                drop = 7.0
                z_lo = mz + 3 - yard_len * .55 + k * 11
                z_hi = mz + 3 + yard_len * .35 - k * 4
                m.box('zagiel', [.5 + (k % 2) * .4, pivot_y - drop * (k + 1), z_lo], [1.6 + (k % 2) * .4, pivot_y - drop * k, z_hi], 'plotno', cloth)
            m.meta.setdefault('sails', []).append({'bone': 'zagiel_' + mid, 'cloth': 'plotno_' + mid, 'emblem': None,
                                                   'yardY': pivot_y, 'depth': 35})
        # masthead pennant in the country colour
        pen = m.group('proporzec_' + mid, [0, mast_top + 2, mz], root)
        for k in range(4):  # streams aft in the apparent wind
            m.box('proporzec', [-.4, mast_top - 2 + k * .6, mz - 8 - k * 6], [.4, mast_top + 3 - k * .6, mz - 2 - k * 6], 'barwa', pen)
        flags.append('proporzec_' + mid)
        # shrouds: ropes from the hull side up to the mast, leaning 22.5 degrees inwards (outside the walkway)
        dx = hw - 2.5
        rise = dx / math.tan(math.radians(22.5))
        length = math.hypot(dx, rise)
        for sx in (1, -1):
            cx, cy = sx * (hw + 2.5 - dx / 2), deck + rail + rise / 2
            for dz in (-9, 5):
                m.box('want', [cx - .3, cy - length / 2, mz + dz - .3], [cx + .3, cy + length / 2, mz + dz + .3], 'lina', hull,
                      ('z', 22.5 if sx > 0 else -22.5), [cx, cy, mz + dz])
    # ensign on the stern staff: two hinged halves wave in the wind
    staff_z = -hl - 6
    m.box('drzewce', [-.8, castle_y, staff_z - .8], [.8, castle_y + 46, staff_z + .8], 'belka', hull)
    ensign = m.group('bandera', [0, castle_y + 44, staff_z], root)
    m.box('bandera_a', [-.4, castle_y + 32, staff_z - 12], [.4, castle_y + 44, staff_z - 1], 'barwa', ensign)
    ensign_tip = m.group('bandera_koniec', [0, castle_y + 44, staff_z - 12], ensign)
    m.box('bandera_b', [-.4, castle_y + 32.5, staff_z - 22], [.4, castle_y + 43.5, staff_z - 12], 'barwa', ensign_tip)
    flags += ['bandera', 'bandera_koniec']
    if caravel:  # bronze guns through the hull below the deck: never on the walkway
        for sx in (1, -1):
            for z in (-30, 0, 30):
                m.box('furta', [sx * (hw - .5), deck - 16, z - 4], [sx * (hw + .3), deck - 8, z + 4], 'smola', hull)
                m.box('dzialo', [sx * (hw - 1), deck - 13.5, z - 1.8], [sx * (hw + 9), deck - 10, z + 1.8], 'braz', hull)
    m.meta.update({'flags': flags, 'tinted': ['tarcze'] + flags + [s['emblem'] for s in m.meta.get('sails', []) if s['emblem']],
                   'helmZ': helm_z, 'castleY': castle_y, 'railHeight': rail})
    ship_animations(m, deck)
    return m


def ship_animations(m, deck):
    sails = m.meta['sails']
    # gentle swell on the root; amplitudes keep the deck within 0.06 block of the physical collider
    wave = []
    for i in range(9):
        t = i * 0.5
        a = 2 * math.pi * t / 4
        wave.append((t, (0.6 * math.sin(a), 0, 1.0 * math.sin(a + 1.1))))
    heave = [(i * 0.5, (0, 0.45 * math.sin(2 * math.pi * i * 0.5 / 4 + .6), 0)) for i in range(9)]
    m.anim('fala', 4.0, 'loop', {'statek': {'rotation': wave, 'position': heave}})
    furled = 0.12
    m.anim('zagle_rozwin', 1.6, 'hold', {s['bone']: {'scale': [(0, (1, furled, 1)), (0.5, (1, 0.45, 1)), (1.2, (1, 1.04, 1)), (1.6, (1, 1, 1))]} for s in sails})
    m.anim('kotwica_opuszczona', 0.05, 'hold', {'kotwica': {'position': [(0, (0, -32, 0)), (0.05, (0, -32, 0))]}})
    m.anim('zagle_zwiniete', 0.05, 'hold', {s['bone']: {'scale': [(0, (1, furled, 1)), (0.05, (1, furled, 1))]} for s in sails})
    m.anim('zagle_zwin', 1.6, 'hold', {s['bone']: {'scale': [(0, (1, 1, 1)), (0.6, (1, 0.6, 1)), (1.6, (1, furled, 1))]} for s in sails})
    flutter = {}
    for n, s in enumerate(sails):
        flutter[s['cloth']] = {'rotation': [(i * 0.25, (2.2 * math.sin(i * math.pi / 2 + n), 0, 0.6 * math.sin(i * math.pi / 2 + n + 1))) for i in range(5)],
                               'scale': [(i * 0.25, (1, 1, 1 + 0.18 * math.sin(i * math.pi / 2 + n))) for i in range(5)]}
    m.anim('lopot', 1.0, 'loop', flutter)
    waving = {}
    for k, f in enumerate(m.meta['flags']):
        amp = 14 if f.startswith('bandera') else 10
        waving[f] = {'rotation': [(i * 0.25, (0, amp * math.sin(i * math.pi / 2 + k * .9), 0)) for i in range(5)]}
    m.anim('flagi', 1.0, 'loop', waving)
    m.anim('kotwica_opusc', 2.0, 'hold', {'kotwica': {'position': [(0, (0, 0, 0)), (1.6, (0, -34, 0)), (2.0, (0, -32, 0))],
                                                       'rotation': [(0, (0, 0, 0)), (1.0, (12, 0, 0)), (2.0, (0, 0, 0))]}})
    m.anim('kotwica_podnies', 2.5, 'hold', {'kotwica': {'position': [(0, (0, -32, 0)), (2.5, (0, 0, 0))],
                                                         'rotation': [(0, (0, 0, 0)), (1.2, (-8, 0, 0)), (2.5, (0, 0, 0))]}})


# ----------------------------------------------------------------------------------------------- siege
def trebusz():
    m = Model('armia_trebusz')
    root = m.group('machina', [0, 0, 48])
    frame = m.group('rama', [0, 0, 48], root)
    for sx in (1, -1):
        m.box('podwalina', [sx * 14 - 2, 6, 22], [sx * 14 + 2, 11, 94], 'belka', frame)
        m.box('slup', [sx * 14 - 2, 11, 57], [sx * 14 + 2, 60, 61], 'belka', frame)
        # A-frame braces: tops lean into the axle (back brace +22.5, front brace -22.5 about X)
        m.box('zastrzal_tyl', [sx * 14 - 1.6, 8, 47.8], [sx * 14 + 1.6, 58, 51], 'belka', frame, ('x', 22.5), [sx * 14, 33, 49.4])
        m.box('zastrzal_przod', [sx * 14 - 1.6, 8, 67], [sx * 14 + 1.6, 58, 70.2], 'belka', frame, ('x', -22.5), [sx * 14, 33, 68.6])
        m.box('lozysko', [sx * 14 - 2.5, 57, 56], [sx * 14 + 2.5, 62, 62], 'zelazo', frame)
    for z in (24, 59, 92):
        m.box('poprzeczka', [-16, 6, z - 2], [16, 10, z + 2], 'belka', frame)
    m.box('rynna', [-5, 10, 10], [5, 12, 46], 'drewno', frame)
    m.box('os', [-17, 57.5, 57.5], [17, 60.5, 60.5], 'zelazo', frame)
    m.box('wyzwalacz', [-1.5, 10, 18], [1.5, 16, 21], 'zelazo', frame)
    for sx in (1, -1):
        for z in (30, 86):
            m.wheel('kolo_%s_%d' % ('p' if sx > 0 else 'l', z), [sx * 18.5, 5, z], 5, 3, root)
    pivot = [0, 59, 59]
    arm = m.group('ramie', pivot, root, rotation=(-45, 0, 0))
    m.box('ramie', [-2.2, 57, -6], [2.2, 61, 77], 'drewno', arm)
    for z in (6, 30, 54, 70):
        m.box('opaska', [-2.6, 56.6, z], [2.6, 61.4, z + 2], 'zelazo', arm)
    cw = m.group('przeciwwaga', [0, 59, 75], arm, rotation=(45, 0, 0))
    m.box('wieszak', [-1, 45, 74], [1, 59, 76], 'zelazo', cw)
    m.box('skrzynia', [-9, 30, 68], [9, 46, 82], 'belka', cw)
    m.box('kamienie', [-8, 46, 69], [8, 47.5, 81], 'kamien', cw)
    sling = m.group('proca', [0, 59, -5], arm, rotation=(-45, 0, 0))  # rests forward along the trough
    m.box('sznur', [-.5, 37, -5.5], [.5, 59, -4.5], 'lina', sling)
    m.box('sakwa', [-3, 33, -8], [3, 37, -2], 'skora', sling)
    stone = m.group('kamien', [0, 35, -5], sling)
    m.box('pocisk', [-2.5, 34, -7.5], [2.5, 39, -2.5], 'kamien', stone)
    # shot: arm swings ~145 degrees, the counterweight keeps hanging, the sling whips and releases
    arm_k = [(0, 0), (0.05, 55), (0.1, 105), (0.2, 140), (0.35, 152), (0.6, 140), (1.0, 145)]
    sling_k = [(0, 0), (0.05, 40), (0.1, 125), (0.2, 170), (0.4, 120), (0.7, 80), (1.0, 95)]
    m.anim('strzal', 1.0, 'hold', {
        'ramie': {'rotation': [(t, (a, 0, 0)) for t, a in arm_k]},
        'przeciwwaga': {'rotation': [(t, (-a, 0, 0)) for t, a in arm_k]},
        'proca': {'rotation': [(t, (a, 0, 0)) for t, a in sling_k]},
        'kamien': {'scale': [(0, (1, 1, 1)), (0.09, (1, 1, 1)), (0.1, (0.01, 0.01, 0.01)), (1.0, (0.01, 0.01, 0.01))]},
        'rama': {'rotation': [(0, (0, 0, 0)), (0.1, (-1.5, 0, 0)), (0.35, (1, 0, 0)), (0.6, (0, 0, 0))]}})
    load = [(0, 145), (0.5, 120), (1.5, 70), (2.5, 25), (3.4, 4), (4.0, 0)]
    m.anim('ladowanie', 4.0, 'hold', {
        'ramie': {'rotation': [(t, (a, 0, 0)) for t, a in load]},
        'przeciwwaga': {'rotation': [(t, (-a, 0, 0)) for t, a in load]},
        'proca': {'rotation': [(0, (95, 0, 0)), (1.0, (60, 0, 0)), (3.0, (10, 0, 0)), (4.0, (0, 0, 0))]},
        'kamien': {'scale': [(0, (0.01, 0.01, 0.01)), (3.7, (0.01, 0.01, 0.01)), (4.0, (1, 1, 1))]}})
    wheel_anim(m)
    m.meta.update({'engine': 'trebusz', 'release': 0.1, 'wheelRadius': 5, 'footprint': [-21, 0, 22, 21, 0, 94]})
    return m


def katapulta():
    m = Model('armia_katapulta')
    root = m.group('machina', [0, 0, 40])
    frame = m.group('rama', [0, 0, 40], root)
    for sx in (1, -1):
        m.box('podluznica', [sx * 12 - 2, 9, 0], [sx * 12 + 2, 15, 80], 'belka', frame)
        m.box('slup', [sx * 12 - 2, 15, 56], [sx * 12 + 2, 64, 60], 'belka', frame)
        m.box('zastrzal', [sx * 12 - 1.5, 14, 66], [sx * 12 + 1.5, 52, 69], 'belka', frame, ('x', -22.5), [sx * 12, 33, 67.5])
        m.box('rama_skretna', [sx * 12 - 2.5, 13, 46], [sx * 12 + 2.5, 27, 54], 'belka', frame)
    for z in (2, 30, 78):
        m.box('poprzeczka', [-14, 9, z - 2], [14, 13, z + 2], 'belka', frame)
    m.box('belka_oporowa', [-14, 58, 56], [14, 65, 60], 'belka', frame)
    m.box('poduszka', [-6, 57, 54.5], [6, 64, 56], 'skora', frame)
    m.octagon('wiazka', [0, 20, 50], 4, -10, 10, 'x', 'lina', frame)
    m.box('kolowrot', [-12, 15, 2], [12, 18, 5], 'drewno', frame)
    m.box('lina_kolowrotu', [-.4, 17, 5], [.4, 18, 14], 'lina', frame)
    for sx in (1, -1):
        for z in (12, 70):
            m.wheel('kolo_%s_%d' % ('p' if sx > 0 else 'l', z), [sx * 17, 7, z], 7, 3, root)
    arm = m.group('ramie', [0, 20, 50], root)
    # resting (cocked) arm lies back towards the crew; the shot swings it up against the padded crossbar
    m.box('ramie', [-2, 18, 8], [2, 22, 52], 'drewno', arm)
    m.box('opaska', [-2.4, 17.6, 30], [2.4, 22.4, 32], 'zelazo', arm)
    m.box('lyzka', [-5, 17, 4], [5, 20, 14], 'drewno', arm)
    for sx in (1, -1):
        m.box('lyzka_bok', [sx * 4, 20, 4], [sx * 5, 24, 14], 'drewno', arm)
    m.box('lyzka_tyl', [-5, 20, 4], [5, 24, 5], 'drewno', arm)
    stone = m.group('kamien', [0, 24, 9], arm)
    m.box('pocisk', [-3.5, 20, 5.5], [3.5, 27, 12.5], 'kamien', stone)
    shot = [(0, 0), (0.04, 50), (0.08, 92), (0.11, 99), (0.2, 92), (0.32, 97), (0.5, 96)]
    m.anim('strzal', 0.5, 'hold', {
        'ramie': {'rotation': [(t, (a, 0, 0)) for t, a in shot]},
        'kamien': {'scale': [(0, (1, 1, 1)), (0.07, (1, 1, 1)), (0.08, (0.01, 0.01, 0.01)), (0.5, (0.01, 0.01, 0.01))]},
        'rama': {'rotation': [(0, (0, 0, 0)), (0.11, (-2, 0, 0)), (0.3, (1, 0, 0)), (0.5, (0, 0, 0))]}})
    load = [(0, 96), (0.6, 80), (1.8, 40), (2.6, 8), (3.0, 0)]
    m.anim('ladowanie', 3.0, 'hold', {
        'ramie': {'rotation': [(t, (a, 0, 0)) for t, a in load]},
        'kamien': {'scale': [(0, (0.01, 0.01, 0.01)), (2.8, (0.01, 0.01, 0.01)), (3.0, (1, 1, 1))]}})
    wheel_anim(m)
    m.meta.update({'engine': 'katapulta', 'release': 0.08, 'wheelRadius': 7, 'footprint': [-20, 0, 0, 20, 0, 80]})
    return m


def taran():
    m = Model('armia_taran')
    root = m.group('machina', [0, 0, 48])
    frame = m.group('rama', [0, 0, 48], root)
    for sx in (1, -1):
        m.box('podluznica', [sx * 16 - 2, 9, 8], [sx * 16 + 2, 14, 88], 'belka', frame)
        for z in (12, 48, 84):
            m.box('slup', [sx * 16 - 2, 14, z - 2], [sx * 16 + 2, 50, z + 2], 'belka', frame)
        m.box('oczep', [sx * 16 - 2.5, 50, 8], [sx * 16 + 2.5, 54, 88], 'belka', frame)
        # sloping hide roof: three steps per side
        for k in range(3):
            x0 = sx * (19 - k * 6)
            m.box('dach', [x0, 54 + k * 5, 6], [x0 - sx * 7, 59 + k * 5, 90], 'skora', frame)
    m.box('kalenica', [-2.5, 68, 4], [2.5, 71, 92], 'belka', frame)
    for z in (12, 84):
        m.box('poprzeczka', [-18, 9, z - 2], [18, 13, z + 2], 'belka', frame)
    for sx in (1, -1):
        for z in (20, 76):
            m.wheel('kolo_%s_%d' % ('p' if sx > 0 else 'l', z), [sx * 21, 8, z], 8, 3.5, root)
    ropes = m.group('liny', [0, 66, 48], root)
    for z in (24, 72):
        m.box('lina', [-.6, 35, z - .6], [.6, 66, z + .6], 'lina', ropes)
    ram = m.group('belka', [0, 35, 48], ropes)
    m.octagon('taran', [0, 30, 48], 5, 2, 96, 'z', 'belka', ram)
    m.box('glowica', [-6, 24, 96], [6, 36, 104], 'zelazo', ram)
    m.box('glowica_szpic', [-3.5, 26.5, 104], [3.5, 33.5, 108], 'zelazo', ram)
    for z in (24, 72):
        m.box('obejma', [-5.6, 24.4, z - 2], [5.6, 35.6, z + 2], 'zelazo', ram)
    # the swing back is the reload ("ladowanie": pulled back and held); the strike hits at 0.12 s
    hit = [(0, 16), (0.08, -4), (0.12, -9), (0.22, -7), (0.5, 3), (1.0, 0)]
    m.anim('uderzenie', 1.0, 'hold', {'liny': {'rotation': [(t, (a, 0, 0)) for t, a in hit]},
                                       'belka': {'rotation': [(t, (-a, 0, 0)) for t, a in hit]},
                                       'rama': {'rotation': [(0, (0, 0, 0)), (0.12, (0, 0, 0)), (0.16, (-1.2, 0, 0)), (0.4, (0, 0, 0))]}})
    back = [(0, 0), (0.8, 13), (1.4, 16), (2.0, 16)]
    m.anim('ladowanie', 2.0, 'hold', {'liny': {'rotation': [(t, (a, 0, 0)) for t, a in back]},
                                       'belka': {'rotation': [(t, (-a, 0, 0)) for t, a in back]}})
    wheel_anim(m)
    m.meta.update({'engine': 'taran', 'release': 0.12, 'wheelRadius': 8, 'footprint': [-25, 0, 4, 25, 0, 108]})
    return m


def armata():
    m = Model('armia_armata')
    root = m.group('machina', [0, 0, 40])
    carriage = m.group('laweta', [0, 0, 40], root)
    for sx in (1, -1):
        m.box('policzek', [sx * 7 - 2, 8, 20], [sx * 7 + 2, 17, 54], 'belka', carriage, ('x', -22.5), [sx * 7, 13, 50])
        m.box('policzek_okucie', [sx * 7 - 2.3, 17, 44], [sx * 7 + 2.3, 19, 54], 'zelazo', carriage)
    m.box('spag', [-6, 0, 14], [6, 4, 24], 'belka', carriage)
    m.box('os', [-17, 11.5, 46.5], [17, 14.5, 49.5], 'zelazo', carriage)
    m.box('klin', [-3, 15, 30], [3, 18, 40], 'drewno', carriage)
    for sx in (1, -1):
        m.wheel('kolo_%s' % ('p' if sx > 0 else 'l'), [sx * 18.5, 13, 48], 13, 3, carriage)
    barrel = m.group('lufa', [0, 21, 48], carriage, rotation=(-6, 0, 0))
    m.octagon('lufa', [0, 21, 48], 4.6, 28, 84, 'z', 'braz', barrel)
    m.octagon('zamek', [0, 21, 48], 5.6, 24, 34, 'z', 'braz', barrel)
    m.octagon('wylot', [0, 21, 48], 5.4, 80, 86, 'z', 'braz', barrel)
    for z in (46, 62):
        m.octagon('pierscien', [0, 21, 48], 5.3, z, z + 2, 'z', 'zloto', barrel)
    m.box('czop', [-8, 19.5, 46.5], [8, 22.5, 49.5], 'braz', barrel)
    m.box('kaskabel', [-1.5, 19.5, 20], [1.5, 22.5, 24], 'braz', barrel)
    m.box('wylot_otwor', [-2, 19, 85.9], [2, 23, 86.3], 'smola', barrel)
    smoke = m.group('dym', [0, 21, 82], barrel)
    for k, (dx, dy, dz) in enumerate(((0, 0, 0), (-1.2, .6, -1), (1.1, -.4, -1.6), (0, 1.2, -2.2))):
        m.box('klab', [dx - 1, 20 + dy, 80 + dz], [dx + 1, 22 + dy, 82 + dz], 'dym', smoke)
    flash = m.group('glow_plomien', [0, 21, 84], barrel)
    m.box('plomien', [-.5, 20.5, 82], [.5, 21.5, 83], 'ogien', flash)
    rammer = m.group('wycior', [10, 1, 30], root)
    m.box('stempel', [8.6, -.4, 2], [11.4, 2.4, 6], 'skora', rammer)
    m.box('drzewce', [9.4, .4, 6], [10.6, 1.6, 60], 'drewno', rammer)
    # shot: barrel recoil, carriage jumps back, smoke and flash at the muzzle
    m.anim('strzal', 1.4, 'hold', {
        'lufa': {'position': [(0, (0, 0, 0)), (0.05, (0, 0, -7)), (0.2, (0, 0, -6)), (1.0, (0, 0, 0))],
                 'rotation': [(0, (0, 0, 0)), (0.05, (-4, 0, 0)), (0.3, (0, 0, 0))]},
        'laweta': {'position': [(0, (0, 0, 0)), (0.08, (0, 1.2, -6)), (0.25, (0, 0, -8)), (1.2, (0, 0, -8)), (1.4, (0, 0, -8))]},
        'dym': {'scale': [(0, (1, 1, 1)), (0.05, (5, 5, 5)), (0.45, (11, 9, 11)), (1.0, (14, 12, 14)), (1.3, (0.3, 0.3, 0.3)), (1.4, (1, 1, 1))],
                'position': [(0, (0, 0, 0)), (0.05, (0, 1, 10)), (0.45, (0, 8, 26)), (1.0, (0, 18, 36)), (1.3, (0, 20, 40)), (1.4, (0, 0, 0))]},
        'glow_plomien': {'scale': [(0, (1, 1, 1)), (0.03, (9, 9, 14)), (0.12, (4, 4, 6)), (0.2, (1, 1, 1))],
                         'position': [(0, (0, 0, 0)), (0.03, (0, 0, 6)), (0.2, (0, 0, 0))]}})
    m.anim('ladowanie', 3.0, 'hold', {
        'laweta': {'position': [(0, (0, 0, -8)), (0.8, (0, 0, -8)), (1.6, (0, 0, 0)), (3.0, (0, 0, 0))]},
        'wycior': {'position': [(0, (0, 0, 0)), (0.4, (-10, 22, 40)), (0.8, (-10, 23, 86)), (1.4, (-10, 22, 54)), (1.9, (-10, 23, 86)),
                                (2.4, (0, 4, 30)), (3.0, (0, 0, 0))]}})
    wheel_anim(m)
    m.meta.update({'engine': 'armata', 'release': 0.0, 'wheelRadius': 13, 'footprint': [-22, 0, 2, 22, 0, 88]})
    return m


def wheel_anim(m):
    wheels = [g['name'] for g in m.groups if g['name'].startswith('kolo_')]
    m.anim('jazda', 1.0, 'loop', {w: {'rotation': [(i / 4, (i * 90, 0, 0)) for i in range(5)]} for w in wheels})
    m.meta['wheels'] = wheels


BUILDERS = [lambda: ship('koga'), lambda: ship('karawela'), trebusz, katapulta, taran, armata]


def build():
    png = atlas()
    files, manifest = {}, []
    for make in BUILDERS:
        model = make()
        text, info = model.save(png)
        files[model.name + '.bbmodel'] = text.encode('utf-8')
        manifest.append(info)
    # reference copies next to this script (not shipped in the jar; every model embeds the atlas)
    files[HERE / 'armia_atlas.png'] = png
    files[HERE / 'model_manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=1).encode('utf-8')
    return files


def main():
    files = build()
    check = '--check' in sys.argv
    changed = []
    for rel, data in files.items():
        path = rel if isinstance(rel, Path) else ROOT / rel
        if not path.exists() or path.read_bytes() != data:
            changed.append(rel)
            if not check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if check:
        print('OUT OF DATE: ' + ', '.join(map(str, changed)) if changed else 'UP TO DATE')
        sys.exit(1 if changed else 0)
    total = sum(len(v) for k, v in files.items() if str(k).endswith('.bbmodel'))
    print('Generated %d models (%d bytes), changed: %s' % (len(BUILDERS), total, changed or 'none'))


if __name__ == '__main__':
    main()
