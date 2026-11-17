# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Shared helpers for the Ludnosc building template generator.

Frame: x = west->east (columns), z = north->south (rows), y = layers bottom->top. Front (door) = south.
'.' = air (cleared), '_' = keep terrain. '{kolor}' in a block id = country colour (dye), resolved in game.
"""
import json
import os
import re

POOL = ('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
        '!$%&()*+,-/;<=>?@[]^`{|}~')
HORIZONTAL = ['north', 'east', 'south', 'west']
VEC = {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}


def opposite(d):
    return HORIZONTAL[(HORIZONTAL.index(d) + 2) % 4]


class B:
    def __init__(self, w, h, d):
        self.w, self.h, self.d = w, h, d
        self.g = [[['_' for _ in range(w)] for _ in range(d)] for _ in range(h)]
        self.pal = {}
        self.rev = {}
        self.materials = {}

    # palette ------------------------------------------------------------------------------------
    def p(self, ch, state):
        """Explicit palette char (used by the classic buildings)."""
        assert ch not in '._', ch
        if ch in self.pal:
            assert self.pal[ch] == state, (ch, self.pal[ch], state)
        self.pal[ch] = state
        self.rev.setdefault(state, ch)
        return ch

    def q(self, state):
        """Automatic palette char for a state."""
        if state in self.rev:
            return self.rev[state]
        for ch in POOL:
            if ch not in self.pal:
                self.pal[ch] = state
                self.rev[state] = ch
                return ch
        raise RuntimeError('palette full')

    def c(self, v):
        """Char for a cell value: '.', '_' or a block state."""
        if v in ('.', '_'):
            return v
        if len(v) == 1 and v in self.pal:
            return v
        return self.q(v)

    # cells --------------------------------------------------------------------------------------
    def inside(self, x, y, z):
        return 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.d

    def set(self, x, y, z, v):
        assert self.inside(x, y, z), (x, y, z, self.w, self.h, self.d)
        self.g[y][z][x] = self.c(v)

    def get(self, x, y, z):
        ch = self.g[y][z][x]
        return self.pal.get(ch, ch)

    def fill(self, x1, y1, z1, x2, y2, z2, v):
        ch = self.c(v)
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                for x in range(min(x1, x2), max(x1, x2) + 1):
                    self.set(x, y, z, ch)

    def ring(self, x1, z1, x2, z2, y, v):
        ch = self.c(v)
        for x in range(x1, x2 + 1):
            self.set(x, y, z1, ch)
            self.set(x, y, z2, ch)
        for z in range(z1, z2 + 1):
            self.set(x1, y, z, ch)
            self.set(x2, y, z, ch)

    def walls(self, x1, z1, x2, z2, y1, y2, v):
        for y in range(y1, y2 + 1):
            self.ring(x1, z1, x2, z2, y, v)

    def corners(self, x1, z1, x2, z2):
        return [(x1, z1), (x2, z1), (x1, z2), (x2, z2)]

    def beam_ring(self, x1, z1, x2, z2, y, wood):
        """Horizontal log ring with vertical corner logs."""
        for x in range(x1, x2 + 1):
            self.set(x, y, z1, f'{wood}[axis=x]')
            self.set(x, y, z2, f'{wood}[axis=x]')
        for z in range(z1, z2 + 1):
            self.set(x1, y, z, f'{wood}[axis=z]')
            self.set(x2, y, z, f'{wood}[axis=z]')
        for (x, z) in self.corners(x1, z1, x2, z2):
            self.set(x, y, z, f'{wood}[axis=y]')

    def crenels(self, x1, z1, x2, z2, y, v, start_even=True):
        """Merlons on every other cell of a ring (corners always)."""
        ch = self.c(v)
        i = 0
        cells = []
        for x in range(x1, x2 + 1):
            cells.append((x, z1))
        for z in range(z1 + 1, z2 + 1):
            cells.append((x2, z))
        for x in range(x2 - 1, x1 - 1, -1):
            cells.append((x, z2))
        for z in range(z2 - 1, z1, -1):
            cells.append((x1, z))
        corners = set(self.corners(x1, z1, x2, z2))
        for i, (x, z) in enumerate(cells):
            if (x, z) in corners or (i % 2 == 0) == start_even:
                self.set(x, y, z, ch)

    # furniture ----------------------------------------------------------------------------------
    def bed(self, x, y, z, facing, color='red'):
        """Bed whose HEAD is at (x, y, z); the foot is one block behind (opposite to facing)."""
        dx, dz = VEC[facing]
        self.set(x, y, z, f'{color}_bed[facing={facing},part=head]')
        self.set(x - dx, y, z - dz, f'{color}_bed[facing={facing},part=foot]')

    def door(self, x, y, z, facing='north', hinge='left', wood='spruce'):
        self.set(x, y, z, f'{wood}_door[facing={facing},half=lower,hinge={hinge},open=false]')
        self.set(x, y + 1, z, f'{wood}_door[facing={facing},half=upper,hinge={hinge},open=false]')

    def torch(self, x, y, z, facing):
        self.set(x, y, z, f'wall_torch[facing={facing}]')

    def ladder(self, x, y1, y2, z, facing):
        for y in range(y1, y2 + 1):
            self.set(x, y, z, f'ladder[facing={facing}]')

    # roofs --------------------------------------------------------------------------------------
    def gable_roof_x(self, x1, x2, z1, z2, y0, stair_s, stair_n, ridge_full=None, ridge_slab=None, trim=None):
        """Gable roof with the ridge along x over rows z1..z2 (eaves included), first row at y0.
        stair_s = stairs on the south slope (facing north), stair_n = north slope (facing south)."""
        n = z2 - z1 + 1
        tops = {}
        for i in range((n + 1) // 2):
            zs, zn, y = z2 - i, z1 + i, y0 + i
            if zs == zn:
                for x in range(x1, x2 + 1):
                    self.set(x, y, zs, ridge_full)
                    if ridge_slab:
                        self.set(x, y + 1, zs, ridge_slab)
                tops[zs] = y
            else:
                for x in range(x1, x2 + 1):
                    self.set(x, y, zs, stair_s)
                    self.set(x, y, zn, stair_n)
                tops[zs] = y
                tops[zn] = y
        return tops

    def gable_roof_z(self, z1, z2, x1, x2, y0, stair_e, stair_w, ridge_full=None, ridge_slab=None):
        """Ridge along z over columns x1..x2. stair_e = east slope (facing west), stair_w = west slope (facing east)."""
        n = x2 - x1 + 1
        tops = {}
        for i in range((n + 1) // 2):
            xe, xw, y = x2 - i, x1 + i, y0 + i
            if xe == xw:
                for z in range(z1, z2 + 1):
                    self.set(xe, y, z, ridge_full)
                    if ridge_slab:
                        self.set(xe, y + 1, z, ridge_slab)
                tops[xe] = y
            else:
                for z in range(z1, z2 + 1):
                    self.set(xe, y, z, stair_e)
                    self.set(xw, y, z, stair_w)
                tops[xe] = y
                tops[xw] = y
        return tops

    def roof_x(self, x1, x2, z1, z2, y0, mat, ridge=None):
        """Gable roof along x from a stair material name (e.g. 'spruce')."""
        r = ridge or mat
        return self.gable_roof_x(x1, x2, z1, z2, y0, f'{mat}_stairs[facing=north,half=bottom]',
                                 f'{mat}_stairs[facing=south,half=bottom]', f'{r}_planks' if not r.endswith(('tiles', 'bricks')) else r,
                                 f'{mat}_slab[type=bottom]')

    def roof_z(self, z1, z2, x1, x2, y0, mat, ridge_full, slab):
        return self.gable_roof_z(z1, z2, x1, x2, y0, f'{mat}_stairs[facing=west,half=bottom]',
                                 f'{mat}_stairs[facing=east,half=bottom]', ridge_full, slab)

    def fill_gable_x(self, x, z1, z2, y_from, tops, v):
        for z in range(z1, z2 + 1):
            for y in range(y_from, tops[z]):
                self.set(x, y, z, v)

    def fill_gable_z(self, z, x1, x2, y_from, tops, v):
        for x in range(x1, x2 + 1):
            for y in range(y_from, tops[x]):
                self.set(x, y, z, v)

    def pyramid(self, x1, z1, x2, z2, y, mat, full, top=None):
        """Stepped pyramid cap (stairs facing inward on the edges)."""
        while x1 <= x2 and z1 <= z2:
            for x in range(x1, x2 + 1):
                for z in range(z1, z2 + 1):
                    edge = x in (x1, x2) or z in (z1, z2)
                    if not edge:
                        self.set(x, y, z, full)
                        continue
                    if (x in (x1, x2)) and (z in (z1, z2)) and x1 != x2 and z1 != z2:
                        self.set(x, y, z, f'{mat}_slab[type=bottom]')
                    elif z == z1 and z1 != z2:
                        self.set(x, y, z, f'{mat}_stairs[facing=south,half=bottom]')
                    elif z == z2 and z1 != z2:
                        self.set(x, y, z, f'{mat}_stairs[facing=north,half=bottom]')
                    elif x == x1 and x1 != x2:
                        self.set(x, y, z, f'{mat}_stairs[facing=east,half=bottom]')
                    elif x == x2 and x1 != x2:
                        self.set(x, y, z, f'{mat}_stairs[facing=west,half=bottom]')
                    else:
                        self.set(x, y, z, full)
            x1 += 1
            z1 += 1
            x2 -= 1
            z2 -= 1
            y += 1
        if top:
            self.set((x1 + x2) // 2 if x1 <= x2 else x1 - 1, y, (z1 + z2) // 2 if z1 <= z2 else z1 - 1, top)
        return y

    # output -------------------------------------------------------------------------------------
    def trim_top(self):
        while self.h > 1 and all(c == '.' for row in self.g[-1] for c in row):
            self.g.pop()
            self.h -= 1

    def used(self):
        u = set()
        for layer in self.g:
            for row in layer:
                u.update(row)
        return u - set('._')

    def yaml(self, meta):
        lines = ['# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl',
                 '# Wygenerowane przez templates-src/generate.py — zmiany wprowadzaj w generatorze.',
                 '# Warstwy od dołu do góry; w warstwie wiersze z północy (góra) na południe, kolumny z zachodu na wschód.',
                 '# Front (drzwi) = południe przy obrocie 0. "." = powietrze, "_" = nie ruszaj terenu,',
                 '# {kolor} w nazwie bloku = kolor kraju (barwnik).']
        for k, v in meta.items():
            if isinstance(v, dict):
                lines.append(f'{k}:')
                for k2, v2 in v.items():
                    lines.append(f'  {k2}: {v2}')
            else:
                lines.append(f'{k}: {v}')
        if self.materials:
            lines.append('# Materiały szablonu na danym poziomie osady (nadpisują wspólną paletę; poziom 1 = klasyczny wygląd).')
            lines.append('materials:')
            for lv in sorted(self.materials):
                lines.append(f"  '{lv}':")
                for k2, v2 in sorted(self.materials[lv].items()):
                    lines.append(f'    {k2}: {v2}')
        lines.append('palette:')
        used = self.used()
        for ch in sorted(self.pal):
            if ch in used:
                lines.append(f"  '{ch}': '{self.pal[ch]}'")
        lines.append('layers:')
        for y in range(self.h):
            lines.append(f'  # y={y}')
            lines.append('  - |')
            for z in range(self.d):
                lines.append('    ' + ''.join(self.g[y][z]))
        return '\n'.join(lines) + '\n'


STATES = None
STATE_RE = re.compile(r'^([a-z0-9_{}:@]+)(?:\[(.*)\])?$')

# Material slots per style = (epoch, settlement level); same table as MaterialPalette.DEFAULTS in Java
from palettes import STYLES as LEVELS  # noqa: E402


def resolve_slot(bid, props, level, override=None):
    slot = bid[1:]
    m = dict(LEVELS[level])
    m.update(override or {})
    if slot == 'roof_stairs':
        block = m['roof'] + '_stairs'
    elif slot == 'roof_slab':
        block = m['roof'] + '_slab'
    elif slot == 'door':
        block = m['door'] + '_door'
    else:
        block = m[slot]
    keep = {}
    for k, v in props.items():
        if k == 'axis' and not block.endswith(('_log', '_wood', '_pillar', 'basalt')):
            continue
        if k in ('north', 'east', 'south', 'west') and block.endswith(('_fence', '_pane', 'iron_bars')):
            continue
        keep[k] = v
    return block, keep


def slotify(b, mapping):
    """Replaces fixed blocks by material slots: mapping block id → slot name (props kept). The original blocks become
    the template's level-1 (Wieś) materials, so buildings built before material levels keep their exact look."""
    classic = {}
    for ch, st in list(b.pal.items()):
        bid, _, rest = st.partition('[')
        if bid in mapping:
            slot = mapping[bid]
            b.pal[ch] = '@' + slot + ('[' + rest if rest else '')
            if slot in ('roof_stairs', 'roof_slab'):
                classic['roof'] = bid.rsplit('_', 1)[0]
            else:
                classic[slot] = bid
    b.rev = {v: k for k, v in b.pal.items()}
    b.materials[1] = classic


def load_states(path):
    global STATES
    with open(path, encoding='utf-8') as f:
        STATES = json.load(f)


def validate(name, b):
    """Every state must be a real block with existing properties/values (MC 26.3 registry report)."""
    errors = []
    for ch in b.used():
        st = b.pal[ch]
        m = STATE_RE.match(st)
        if not m:
            errors.append(f'{name}: bad state {st}')
            continue
        raw_props = dict(pair.split('=') for pair in m.group(2).split(',')) if m.group(2) else {}
        variants = []
        bid0 = m.group(1).replace('{kolor}', 'red')
        if bid0.startswith('@'):
            for lv in range(len(LEVELS)):
                variants.append(resolve_slot(bid0, raw_props, lv, b.materials.get(lv)))
        else:
            variants.append((bid0, raw_props))
        for bid, pr in variants:
            if ':' not in bid:
                bid = 'minecraft:' + bid
            if bid not in STATES:
                errors.append(f'{name}: unknown block {bid} ({st})')
                continue
            props = STATES[bid]
            for k, v in pr.items():
                if k not in props:
                    errors.append(f'{name}: {bid} has no property {k}')
                elif v not in props[k]:
                    errors.append(f'{name}: {bid}[{k}={v}] not in {props[k]}')
    return errors


def write(out_dir, name, b, meta):
    b.trim_top()
    errs = validate(name, b)
    if errs:
        raise SystemExit('\n'.join(errs))
    # bed / door sanity
    beds = sum(1 for y in range(b.h) for z in range(b.d) for x in range(b.w)
               if 'part=head' in b.get(x, y, z))
    assert beds == meta['beds'], (name, beds, meta['beds'])
    with open(os.path.join(out_dir, name + '.yml'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(b.yaml(meta))
    return b
