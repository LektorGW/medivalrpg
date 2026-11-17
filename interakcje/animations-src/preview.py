# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Optional preview: renders contact sheets (front / side / top) of every clip into PNG files.

Needs Pillow (pip install pillow). Usage: python preview.py <output dir> [clip name ...]
Partner clips of two-person animations are drawn in grey next to the main character.
"""
import os
import sys

from PIL import Image, ImageDraw

import clips as clip_defs
import clips_ceremony
from motion import evaluate
from rig import BONES, Rig, Transform, add, cross, dot, norm, sub  # noqa: F401

COLORS = {
    "head": (230, 190, 150), "chest": (70, 110, 200), "waist": (60, 95, 180), "hip": (50, 80, 160),
    "r_arm": (200, 70, 70), "r_fore": (240, 120, 110), "l_arm": (70, 170, 80), "l_fore": (120, 210, 120),
    "r_leg": (120, 80, 50), "r_shin": (160, 110, 70), "l_leg": (90, 70, 110), "l_shin": (130, 100, 150),
}
SCALE = 4
CELL = 200


def element_bones(rig):
    group_by_uuid = {g["uuid"]: g["name"] for g in rig.base["groups"]}
    bone_by_group = {group: bone for bone, (group, _p) in BONES.items()}
    out = {}

    def walk(nodes, bone):
        for n in nodes:
            if isinstance(n, dict):
                walk(n["children"], bone_by_group.get(group_by_uuid[n["uuid"]], bone))
            else:
                out[n] = bone
    walk(rig.base["outliner"], None)
    elements = []
    for e in rig.base["elements"]:
        b = out.get(e["uuid"])
        if b in COLORS and e["name"] == "skin":
            elements.append((b, tuple(e["from"]), tuple(e["to"])))
    return elements


def faces(lo, hi):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    c = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    return [[c[i] for i in f] for f in ((0, 1, 2, 3), (5, 4, 7, 6), (4, 0, 3, 7), (1, 5, 6, 2), (3, 2, 6, 7), (4, 5, 1, 0))]


def _camera(yaw, pitch):
    """Orthographic camera looking at the character's front from yaw (deg, + = from its right)
    and pitch (deg, + = from above). Returns (screen x, screen y, depth: larger = nearer)."""
    import math
    cy, sy_ = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(p):
        # camera sits at -Z (in front) rotated around Y by yaw towards +X
        x = p[0] * cy + p[2] * sy_
        z = -p[0] * sy_ + p[2] * cy
        y = p[1]
        y2 = y * cp + z * sp
        z2 = -y * sp + z * cp
        return -x, y2, -z2
    return (lambda p: view(p)[0], lambda p: view(p)[1], lambda p: view(p)[2])


VIEWS = {
    "iso": _camera(35, 20),
    "side": _camera(90, 0),
    "back": _camera(180 + 35, 20),
    "top": _camera(0, 90),
}


def draw_figure(draw, rig, elements, world, view, origin, tint=None):
    sx, sy, depth = VIEWS[view]
    polys = []
    for bone, lo, hi in elements:
        w = world[bone]
        for quad in faces(lo, hi):
            pts = [w.apply(p) for p in quad]
            n = norm(cross(sub(pts[1], pts[0]), sub(pts[2], pts[0])))
            shade = 0.55 + 0.45 * abs(dot(n, norm((0.3, 0.8, -0.5))))
            col = tint or COLORS[bone]
            col = tuple(int(c * shade) for c in col)
            screen = [(origin[0] + sx(p) * SCALE, origin[1] - sy(p) * SCALE) for p in pts]
            polys.append((sum(depth(p) for p in pts) / 4, screen, col))
    polys.sort(key=lambda x: x[0])
    for _d, screen, col in polys:
        draw.polygon(screen, fill=col, outline=(20, 20, 20))


def render(rig, clip, out_dir, frames=10):
    elements = element_bones(rig)
    samples = evaluate(rig, clip)
    partner_samples = None
    mapping = None
    if clip.pair is not None:
        pair, role = clip.pair
        partner = pair.b if role == "a" else pair.a
        partner_samples = evaluate(rig, partner)
        mapping = pair.partner_to_me(role)
    track = None
    if getattr(clip, "prop", None):
        track = clips_ceremony.prop_track(rig, clip, samples, partner_samples, mapping, every=1)
    idx = [round(i * (len(samples) - 1) / (frames - 1)) for i in range(frames)]
    views = list(VIEWS)
    img = Image.new("RGB", (CELL * frames, CELL * len(views)), (245, 245, 240))
    draw = ImageDraw.Draw(img)
    for col, i in enumerate(idx):
        t, pose = samples[i]
        world = rig.fk(pose)
        for row, view in enumerate(views):
            origin = (col * CELL + CELL // 2, row * CELL + int(CELL * 0.8)) if view != "top" else \
                (col * CELL + CELL // 2, row * CELL + CELL // 2)
            if view != "top":
                draw.line([(col * CELL, origin[1]), ((col + 1) * CELL, origin[1])], fill=(150, 150, 150))
            figs = [(world, None)]
            if partner_samples is not None:
                pw = rig.fk(partner_samples[min(i, len(partner_samples) - 1)][1])
                pw = {b: mapping.then(w) for b, w in pw.items()}
                figs.append((pw, (170, 170, 170)))
            sxf, _sy, depth = VIEWS[view]
            # draw the farther figure first
            figs.sort(key=lambda f: depth(f[0]["chest"].apply((0, 20, 0))))
            for w, tint in figs:
                draw_figure(draw, rig, elements, w, view, origin, tint)
            sx, sy = VIEWS[view][0], VIEWS[view][1]

            def scr(p, o=origin):
                return (o[0] + sx(p) * SCALE, o[1] - sy(p) * SCALE)
            if getattr(clip, "sword_errors", None):  # the held sword of the knighting
                fore = world["r_fore"]
                grip = fore.apply(clips_ceremony.HAND)
                tip = fore.apply(add(clips_ceremony.HAND, tuple(v * clips_ceremony.BLADE_LEN for v in clips_ceremony.BLADE)))
                draw.line([scr(grip), scr(tip)], fill=(90, 90, 110), width=4)
            if track:
                near = min(track, key=lambda q: abs(q[0] - t))
                if abs(near[0] - t) < 0.11:
                    cx, cy = scr(near[1:])
                    draw.rectangle([cx - 9, cy - 5, cx + 9, cy + 5], fill=(235, 190, 40), outline=(120, 80, 0))
            draw.text((col * CELL + 4, row * CELL + 4), "%s %.2fs" % (view, t), fill=(0, 0, 0))
    path = os.path.join(out_dir, clip.name + ".png")
    img.save(path)
    return path


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    wanted = set(sys.argv[2:])
    rig = Rig()
    for clip in clip_defs.build_all(rig):
        if not wanted or clip.name in wanted:
            print(render(rig, clip, out_dir))


if __name__ == "__main__":
    main()
