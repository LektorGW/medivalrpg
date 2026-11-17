# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Podglad kramow (Pillow): rzut z przodu (od klienta, z -Z), z boku i izometryczny.

    python skarbiec/tools/preview_stall_models.py

Zapisuje skarbiec/build/stall-preview/<model>.png oraz zestawienie all.png.
Prosty rasteryzator z buforem glebokosci, teksturowany z osadzonego atlasu.
Rysuje rowniez obrys NPC (0,0,0) 0,8 x 2,0 bloku jako czerwona ramke.
"""
from pathlib import Path
import base64
import io
import json
import math
import sys

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'skarbiec/src/main/resources/models'
OUT = ROOT / 'skarbiec/build/stall-preview'
SCALE = 6
STEP = 0.125


def rot_point(e, p):
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


def face_param(e, face):
    """Zwraca funkcje (s,t)->punkt na scianie (s: wzdluz u, t: wzdluz v) oraz wymiary."""
    f, to = e['from'], e['to']
    if face == 'north':
        return lambda s, t: [to[0] - s * (to[0] - f[0]), to[1] - t * (to[1] - f[1]), f[2]], (to[0] - f[0], to[1] - f[1])
    if face == 'south':
        return lambda s, t: [f[0] + s * (to[0] - f[0]), to[1] - t * (to[1] - f[1]), to[2]], (to[0] - f[0], to[1] - f[1])
    if face == 'east':
        return lambda s, t: [to[0], to[1] - t * (to[1] - f[1]), to[2] - s * (to[2] - f[2])], (to[2] - f[2], to[1] - f[1])
    if face == 'west':
        return lambda s, t: [f[0], to[1] - t * (to[1] - f[1]), f[2] + s * (to[2] - f[2])], (to[2] - f[2], to[1] - f[1])
    if face == 'up':
        return lambda s, t: [f[0] + s * (to[0] - f[0]), to[1], f[2] + t * (to[2] - f[2])], (to[0] - f[0], to[2] - f[2])
    return lambda s, t: [f[0] + s * (to[0] - f[0]), f[1], to[2] - t * (to[2] - f[2])], (to[0] - f[0], to[2] - f[2])


SHADE = {'up': 1.0, 'down': .55, 'north': .85, 'south': .75, 'east': .7, 'west': .8}


def render(d, atlas, yaw, pitch, size=(420, 360)):
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def project(p):
        # kamera patrzy w kierunku +Z (stoi po stronie -Z, przed lada), potem yaw/pitch
        x, y, z = p
        x, z = x * cy - z * sy, x * sy + z * cy
        y, z = y * cp + z * sp, -y * sp + z * cp
        return -x, y, z   # ekran: prawo = -X (widok od frontu)

    w, h = size
    img = Image.new('RGB', size, (232, 228, 216))
    pix = img.load()
    zbuf = [[1e9] * h for _ in range(w)]
    ox, oy = w / 2, h - 30
    for e in d['elements']:
        for face, data in e['faces'].items():
            fn, (fw, fh) = face_param(e, face)
            u0, v0, u1, v1 = data['uv']
            ns, nt = max(1, int(fw / STEP)), max(1, int(fh / STEP))
            for i in range(ns):
                s = (i + .5) / ns
                for j in range(nt):
                    t = (j + .5) / nt
                    p = rot_point(e, fn(s, t))
                    sx, syy, depth = project(p)
                    px_, py_ = int(ox + sx * SCALE), int(oy - syy * SCALE)
                    tu = min(127, max(0, int(u0 + (u1 - u0) * s)))
                    tv = min(127, max(0, int(v0 + (v1 - v0) * t)))
                    col = atlas[tu, tv][:3]
                    col = tuple(int(c * SHADE[face]) for c in col)
                    for dx in (0, 1):
                        for dy in (0, 1):
                            X, Y = px_ + dx, py_ + dy
                            if 0 <= X < w and 0 <= Y < h and depth < zbuf[X][Y]:
                                zbuf[X][Y] = depth
                                pix[X, Y] = col
    # obrys NPC (rzut pudelka 0,8 x 2,0 bloku)
    corners = [project([x, y, z]) for x in (-6.4, 6.4) for y in (0, 32) for z in (-6.4, 6.4)]
    xs = [ox + c[0] * SCALE for c in corners]
    ys = [oy - c[1] * SCALE for c in corners]
    ImageDraw.Draw(img).rectangle([min(xs), min(ys), max(xs), max(ys)], outline=(220, 30, 30))
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rows = []
    for p in sorted(SRC.glob('skarbiec_kram_*.bbmodel')):
        d = json.loads(p.read_text(encoding='utf-8'))
        atlas = Image.open(io.BytesIO(base64.b64decode(d['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA').load()
        views = [render(d, atlas, 0, 8), render(d, atlas, 90, 8), render(d, atlas, 35, 28)]
        sheet = Image.new('RGB', (sum(v.width for v in views), views[0].height + 20), (255, 255, 255))
        x = 0
        for v in views:
            sheet.paste(v, (x, 20))
            x += v.width
        ImageDraw.Draw(sheet).text((6, 4), p.stem + '   [przod (od klienta, -Z) | bok (od -X) | izometria]', fill=(0, 0, 0))
        sheet.save(OUT / (p.stem + '.png'))
        rows.append(sheet)
        print('Podglad:', OUT / (p.stem + '.png'))
    if rows:
        allimg = Image.new('RGB', (rows[0].width, sum(r.height for r in rows)), (255, 255, 255))
        y = 0
        for r in rows:
            allimg.paste(r, (0, y))
            y += r.height
        allimg.save(OUT / 'all.png')
        print('Zestawienie:', OUT / 'all.png')
    return 0


if __name__ == '__main__':
    sys.exit(main())
