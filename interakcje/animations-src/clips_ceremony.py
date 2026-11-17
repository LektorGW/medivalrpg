# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Courtly and ceremonial interactions: handshake, a shared toast, a mutual courtly bow (reverence
and curtsy), knighting, the oath of fealty and the coronation (by an officiant or self-crowning).

Two-person clips are posed against the partner's actual body (IK on the partner's FK, see
motion.Context). The knighting sword is a held item: its tip is placed on the kneeling partner's
shoulders by a small numeric search over the arm angles (``sword_pose``), using the vanilla grip
(forearm hanging -> blade forward). The crown of the coronation is not a body part: its path (the
point between the officiant's hands, then the ruler's head) is exported as a prop track and moved
by the plugin as an item display.
"""
import math

from clips import (ARM_REST_POLE_L, ARM_REST_POLE_R, REST, breathe, fixed, ik_window, joint, mine, offset,
                   path, vec_path)
from motion import Clip, Pair, window
from rig import add, lerp3, mul, sub

ZERO = (0, 0, 0)
HAND = (5.0, 11.0, -0.4)            # right hand grip in r_fore space at rest (vanilla held item anchor)
HAND_L = (-5.0, 11.0, -0.4)
BLADE = (0.0, 0.0, -1.0)             # held sword with the forearm hanging: the blade points forward
BLADE_LEN = 12.5                     # px from the grip to the tip (sword item at the third-person scale)
HEAD_TOP = (0.0, 30.6, 0.0)          # top of the head in head space


# ---------------------------------------------------------------------------- kneeling (one-shot)

KNEEL = dict(l_leg=(-2, 0, -3), l_shin=(-88, 0, 0), r_leg=(70, 0, 4), r_shin=(-70, 0, 0),
             hip=(-6, 0, 0), chest=(-7, 0, 0), head=(-17, 0, 0))
KNEEL_ROOT = (0.0, -3.75, 0.4)


def kneel(c, down, up, head=(-17, 0, 0), chest=(-7, 0, 0), stay=None):
    """Down on the left knee between down[0]..down[1], up again between up[0]..up[1]
    (seconds). The thigh comes forward before the hips drop, so the knee never sinks into the
    ground."""
    pose = dict(KNEEL, head=head, chest=chest)
    mid = dict(l_leg=(-14, 0, -2), l_shin=(-40, 0, 0), r_leg=(40, 0, 3), r_shin=(-30, 0, 0),
               hip=(-8, 0, 0), chest=(-4, 0, 0), head=(-6, 0, 0))
    rest = {k: ZERO for k in pose}
    c.key(0.0, **rest)
    c.key(down[0], **rest)
    c.key((down[0] + down[1]) / 2, "inout", **mid)
    c.key(down[1], "cubic", **pose)
    for t, extra in (stay or []):
        c.key(t, **dict(pose, **extra))
    c.key(up[0], **pose)
    c.key((up[0] + up[1]) / 2, "inout", **mid)
    c.key(up[1], "cubic", **rest)
    c.key(c.length, **rest)
    c.keypos(0.0, root=ZERO)
    c.keypos(down[0], root=ZERO)
    c.keypos(down[1], "cubic", root=KNEEL_ROOT)
    c.keypos(up[0], root=KNEEL_ROOT)
    c.keypos(up[1], "cubic", root=ZERO)
    c.keypos(c.length, root=ZERO)
    return window(down[0], down[1], up[0], up[1])


def hand_on_knee(c, env):
    """The right hand rests on the raised right knee while kneeling."""
    c.ik("r_arm", lambda t, ctx: add(joint("r_leg", (0.3, 2.1, 0.2))(ctx, t), (0.0, 0.0, 0.0)), (0.6, -0.6, 1.0),
         env)


# ---------------------------------------------------------------------------- handshake

def handshake(role):
    """Step in, right hands meet in the middle and pump three times, a nod, let go."""
    L = 2.8
    d = 0.75 * 16
    a = role == "a"
    late = 0.0 if a else 0.06
    c = Clip("handshake_" + role, L)
    c.key(0.0, chest=ZERO, head=ZERO, hip=ZERO, l_arm=ZERO)
    c.key(0.6 + late, "cubic", chest=(-4, 6, 0), hip=(-2, 0, 0), head=(-2, -4, 0), l_arm=(4, 0, -3))
    c.key(1.35, head=(-14, -4, 0))       # the nod while shaking
    c.key(1.7, head=(-3, -4, 0))
    c.key(2.1, chest=(-4, 6, 0), hip=(-2, 0, 0), l_arm=(4, 0, -3))
    c.key(L - 0.05, "cubic", chest=ZERO, head=ZERO, hip=ZERO, l_arm=ZERO)
    c.keypos(0.0, root=ZERO)
    c.keypos(0.6 + late, "cubic", root=(0, -0.2, -1.2))
    c.keypos(2.1, root=(0, -0.2, -1.2))
    c.keypos(L - 0.05, "cubic", root=ZERO)
    pumps = window(0.9, 1.0, 1.8, 1.9)
    grip = fixed((0.0, 14.6, -d / 2))
    shaking = offset(grip, lambda t: (0.0, 1.1 * pumps(t) * math.sin(2 * math.pi * (t - 0.9) / 0.3), 0.0))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.35 + late, mine("chest", (7.5, 15.5, -6.0)), "inout"),
        (0.85, grip, "cubic"),
        (1.95, shaking, "linear"),
        (2.3, mine("chest", (7.5, 15.0, -4.5)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.5, (1.0, -0.4, 0.4), "inout"),
                  (2.1, (1.0, -0.4, 0.4), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    c.event("impact", 0.85)
    return c


# ---------------------------------------------------------------------------- toast

def toast(role):
    """Raise the drinks, clink them together between the two, then drink (two gulps) and lower."""
    L = 3.8
    d = 0.9 * 16
    a = role == "a"
    late = 0.0 if a else 0.05
    c = Clip("toast_" + role, L)
    clink_t = 0.8
    c.key(0.0, head=ZERO, chest=ZERO, waist=ZERO)
    c.key(clink_t, "cubic", head=(6, 0, 0), chest=(2, 4, 0), waist=(0, 2, 0))
    c.key(1.15, head=(2, 0, 0), chest=(0, 0, 0), waist=ZERO)
    c.key(1.6, "cubic", head=(22, 0, 0), chest=(5, 0, 0), waist=(2, 0, 0))
    c.key(2.45, head=(22, 0, 0), chest=(5, 0, 0), waist=(2, 0, 0))
    c.key(2.85, "cubic", head=(-3, 0, 0), chest=(-2, 0, 0), waist=ZERO)
    c.key(L - 0.05, head=ZERO, chest=ZERO, waist=ZERO)
    gulps = window(1.6, 1.7, 2.35, 2.45)
    c.layer("head", lambda t: (1.6 * gulps(t) * math.sin(2 * math.pi * (t - 1.62) / 0.36), 0.0, 0.0))
    clink = fixed((0.7, 24.5, -d / 2 + 1.3))
    mouth = mine("head", (0.6, 24.3, -5.4))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.4 + late, mine("chest", (8.0, 19.0, -6.0)), "inout"),
        (clink_t, clink, "quad_in"),
        (1.0, offset(clink, lambda t: (0.0, 0.6, 0.6)), "quart_out"),
        (1.5, mouth, "inout"),
        (2.45, mouth, "linear"),
        (2.9, mine("chest", (6.0, 16.8, -5.2)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.4, (1.0, -1.0, 0.2), "inout"),
                  (2.8, (1.0, -1.0, 0.2), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    breathe(c, 0.5, 3.8, window(0.0, 0.4, 3.3, 3.8))
    c.event("impact", clink_t)
    return c


# ---------------------------------------------------------------------------- mutual courtly bow

def courtbow_a():
    """Reverence: the right hand doffs an imagined hat and sweeps it down and out to the side while
    the right foot slides back and the body bows; the left hand rests on the back."""
    L = 3.4
    c = Clip("courtbow_a", L)
    c.key(0.0, r_leg=ZERO, r_shin=ZERO, l_leg=ZERO, l_shin=ZERO, hip=ZERO, waist=ZERO, chest=ZERO, head=ZERO)
    c.key(0.45, r_leg=(-14, 0, 2), r_shin=(-8, 0, 0), l_leg=(4, 0, 0), l_shin=(-6, 0, 0), chest=(2, 0, 0))
    c.key(1.3, "cubic", hip=(-36, 0, 0), waist=(-9, 0, 0), chest=(-6, 0, 0), head=(-12, 0, 0),
          r_shin=(-14, 0, 0), l_shin=(-12, 0, 0), l_leg=(8, 0, 0))
    c.key(2.0, hip=(-36, 0, 0), waist=(-9, 0, 0), chest=(-6, 0, 0), head=(-14, 0, 0),
          r_shin=(-14, 0, 0), l_shin=(-12, 0, 0), l_leg=(8, 0, 0))
    c.key(2.75, "cubic", hip=ZERO, waist=ZERO, chest=(1, 0, 0), head=(1, 0, 0), r_leg=(-6, 0, 1),
          r_shin=(-5, 0, 0), l_shin=(-3, 0, 0), l_leg=(2, 0, 0))
    c.key(L - 0.05, r_leg=ZERO, r_shin=ZERO, l_leg=ZERO, l_shin=ZERO, hip=ZERO, waist=ZERO, chest=ZERO,
          head=ZERO)
    c.keypos(0.0, root=ZERO)
    c.keypos(1.3, "cubic", root=(0, -0.5, 1.0))
    c.keypos(2.0, root=(0, -0.5, 1.0))
    c.keypos(L - 0.05, root=ZERO)
    hat = mine("head", (3.4, 31.2, -1.0))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.45, hat, "cubic"),
        (0.6, offset(hat, lambda t: (0.6, 0.6, 0.0)), "inout"),
        (1.35, mine("chest", (11.6, 12.6, -5.6)), "cubic"),
        (2.0, mine("chest", (12.0, 12.4, -4.6)), "linear"),
        (2.7, mine("chest", (8.0, 15.5, -3.5)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.4, (1.0, -0.8, 0.3), "inout"),
                  (1.3, (0.6, 0.4, 1.0), "inout"), (2.1, (0.6, 0.4, 1.0), "linear"),
                  (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    back = mine("waist", (-2.4, 13.6, 4.4))
    c.ik("l_arm", path("l_arm", [(0.0, REST, "linear"), (0.6, back, "inout"), (2.6, back, "linear"),
                                 (L - 0.05, REST, "inout")]),
         vec_path([(0.0, ARM_REST_POLE_L, "linear"), (0.5, (-1.0, 0.0, -0.3), "inout"),
                   (2.6, (-1.0, 0.0, -0.3), "linear"), (L - 0.05, ARM_REST_POLE_L, "inout")]), ik_window(L))
    c.event("impact", 1.3)
    return c


def courtbow_b():
    """Curtsy (dyg): the right foot behind the left, both knees bend deep with a straight back,
    the hands hold the skirt out at the sides, the head bows; then up."""
    L = 3.4
    c = Clip("courtbow_b", L)
    deep = dict(r_leg=(4, 0, 6), r_shin=(-46, 0, 0), l_leg=(32, 0, -2), l_shin=(-40, 0, 0),
                hip=(-12, 0, 0), chest=(-4, 0, 0), head=(-18, 0, 0))
    c.key(0.0, **{k: ZERO for k in deep})
    c.key(0.5, r_leg=(-10, 0, 6), r_shin=(-10, 0, 0), l_leg=(4, 0, 0), l_shin=(-4, 0, 0), hip=ZERO,
          chest=(2, 0, 0), head=(2, 0, 0))
    c.key(1.35, "cubic", **deep)
    c.key(2.05, **deep)
    c.key(2.8, "cubic", r_leg=(-6, 0, 3), r_shin=(-6, 0, 0), l_leg=(2, 0, 0), l_shin=(-2, 0, 0), hip=ZERO,
          chest=(1, 0, 0), head=(1, 0, 0))
    c.key(L - 0.05, **{k: ZERO for k in deep})
    c.keypos(0.0, root=ZERO)
    c.keypos(1.35, "cubic", root=(0, -2.4, 0.8))
    c.keypos(2.05, root=(0, -2.4, 0.8))
    c.keypos(L - 0.05, "cubic", root=ZERO)
    for limb, side, pole in (("r_arm", 1.0, ARM_REST_POLE_R), ("l_arm", -1.0, ARM_REST_POLE_L)):
        skirt = mine("waist", (side * 7.0, 11.6, -1.6))
        c.ik(limb, path(limb, [(0.0, REST, "linear"), (0.6, skirt, "inout"), (2.4, skirt, "linear"),
                               (L - 0.05, REST, "inout")]),
             vec_path([(0.0, pole, "linear"), (0.6, (side * 1.0, 0.3, 0.6), "inout"),
                       (2.4, (side * 1.0, 0.3, 0.6), "linear"), (L - 0.05, pole, "inout")]), ik_window(L))
    c.event("impact", 1.35)
    return c


# ---------------------------------------------------------------------------- knighting

def _sword_tip(rig, pose):
    w = rig.fk(pose, only={"root", "hip", "waist", "chest", "r_arm", "r_fore"})
    grip = w["r_fore"].apply(HAND)
    return w["r_fore"].apply(add(HAND, mul(BLADE, BLADE_LEN))), grip


def sword_pose(rig, target, base, start):
    """Arm (and chest twist / bow) angles that put the held sword's tip on ``target`` (model
    space). Coordinate descent with step halving; prefers small angles."""
    names = [("r_arm", 0), ("r_arm", 1), ("r_arm", 2), ("r_fore", 0), ("chest", 0), ("chest", 1)]
    bounds = {("r_arm", 0): (-10, 170), ("r_arm", 1): (-70, 70), ("r_arm", 2): (-40, 80),
              ("r_fore", 0): (0, 125), ("chest", 0): (-18, 6), ("chest", 1): (-28, 28)}
    x = [start.get(b, ZERO)[i] for b, i in names]

    def pose_of(v):
        p = {k: {"rot": tuple(r), "pos": ZERO} for k, r in base.items()}
        rots = {}
        for (b, i), val in zip(names, v):
            rots.setdefault(b, list(base.get(b, ZERO)))
            rots[b][i] = val
        for b, r in rots.items():
            p[b] = {"rot": tuple(r), "pos": ZERO}
        return p

    def cost(v):
        tip, _grip = _sword_tip(rig, pose_of(v))
        e = sub(tip, target)
        reg = sum((val / 90.0) ** 2 for val in v)
        return e[0] ** 2 + e[1] ** 2 + e[2] ** 2 + 0.6 * reg

    best = cost(x)
    step = 24.0
    while step > 0.05:
        improved = False
        for i, key in enumerate(names):
            lo, hi = bounds[key]
            for s in (step, -step):
                y = list(x)
                y[i] = max(lo, min(hi, y[i] + s))
                cy = cost(y)
                if cy < best - 1e-9:
                    x, best, improved = y, cy, True
        if not improved:
            step /= 2
    tip, _ = _sword_tip(rig, pose_of(x))
    err = math.sqrt(sum(v * v for v in sub(tip, target)))
    out = {}
    for (b, i), val in zip(names, x):
        out.setdefault(b, list(base.get(b, ZERO)))
        out[b][i] = round(val, 2)
    return {b: tuple(v) for b, v in out.items()}, err


def knight_b():
    """The knighted kneels on the left knee with the head bowed through the whole dubbing and
    rises when it is over."""
    L = 7.0
    c = Clip("knight_b", L)
    env = kneel(c, (0.15, 1.0), (5.9, 6.85), head=(-22, 0, 0), chest=(-9, 0, 0))
    hand_on_knee(c, env)
    breathe(c, 0.8, 3.5, env)
    c.event("impact", 1.75, 3.55)
    return c


def knight_a(rig, partner_clip, distance):
    """The knight-maker: the sword comes up before the face, is lowered onto the right shoulder of
    the kneeling one, lifted over the head onto the left shoulder, raised again in a salute and
    lowered to the side."""
    L = 7.0
    c = Clip("knight_a", L)
    d = distance * 16
    # partner's kneeling body in my space (it faces me: its right shoulder is on my left)
    pose_b = partner_clip.base_pose(3.0)
    wb = rig.fk(pose_b)
    to_me = Pair.partner_to_me_static(Pair.FACE, "a", distance)
    shoulder_r = to_me.apply(wb["r_arm"].apply(add(rig.pivot["r_arm"], (0.0, 2.2, 0.6))))
    shoulder_l = to_me.apply(wb["l_arm"].apply(add(rig.pivot["l_arm"], (0.0, 2.2, 0.6))))
    head_top = to_me.apply(wb["head"].apply(HEAD_TOP))
    base = dict(hip=ZERO, waist=ZERO)
    up = {"r_arm": (28, 14, 4), "r_fore": (62, 0, 0), "chest": (3, 0, 0)}
    touch_r, err_r = sword_pose(rig, add(shoulder_r, (0.0, 0.4, 0.0)), base,
                                {"r_arm": (60, -20, 0), "r_fore": (20, 0, 0)})
    touch_l, err_l = sword_pose(rig, add(shoulder_l, (0.0, 0.4, 0.0)), base,
                                {"r_arm": (60, 20, 10), "r_fore": (20, 0, 0)})
    over, err_o = sword_pose(rig, add(head_top, (0.0, 4.0, 0.0)), base,
                             {"r_arm": (90, 0, 4), "r_fore": (30, 0, 0)})
    c.sword_errors = {"right shoulder": err_r, "over head": err_o, "left shoulder": err_l}
    zero_arm = {"r_arm": ZERO, "r_fore": ZERO, "chest": ZERO}
    seq = [(0.0, zero_arm, "inout"), (0.7, up, "cubic"), (1.1, up, "inout"),
           (1.75, touch_r, "cubic"), (2.25, touch_r, "linear"),
           (2.75, over, "inout"),
           (3.55, touch_l, "inout"), (4.05, touch_l, "linear"),
           (4.6, up, "cubic"), (5.2, up, "linear"),
           (5.9, {"r_arm": (24, -10, 16), "r_fore": (10, 0, 0), "chest": (1, -4, 0)}, "cubic"),
           (6.4, {"r_arm": (24, -10, 16), "r_fore": (10, 0, 0), "chest": (1, -4, 0)}, "linear"),
           (L - 0.05, zero_arm, "inout")]
    for t, pose, ease in seq:
        c.key(t, ease, **{b: pose.get(b, ZERO) for b in ("r_arm", "r_fore", "chest")})
    c.key(0.0, head=ZERO, hip=ZERO, l_arm=ZERO)
    c.key(1.1, head=(-8, 0, 0), hip=(-2, 0, 0))
    c.key(4.6, head=(-8, 0, 0), hip=(-2, 0, 0))
    c.key(5.2, head=(2, 0, 0), hip=ZERO)
    c.key(L - 0.05, head=ZERO, hip=ZERO, l_arm=ZERO)
    c.key(0.0, r_leg=ZERO, l_leg=ZERO)
    c.key(0.5, r_leg=(0, 0, -1.5), l_leg=(0, 0, 1.5))
    c.key(6.2, r_leg=(0, 0, -1.5), l_leg=(0, 0, 1.5))
    c.key(L - 0.05, r_leg=ZERO, l_leg=ZERO)
    hilt = mine("waist", (-4.9, 13.6, -1.8))
    c.ik("l_arm", path("l_arm", [(0.0, REST, "linear"), (0.6, hilt, "inout"), (6.3, hilt, "linear"),
                                 (L - 0.05, REST, "inout")]),
         vec_path([(0.0, ARM_REST_POLE_L, "linear"), (0.5, (-1.0, 0.1, 0.5), "inout"),
                   (6.3, (-1.0, 0.1, 0.5), "linear"), (L - 0.05, ARM_REST_POLE_L, "inout")]), ik_window(L))
    breathe(c, 0.5, 3.5, window(0.0, 0.5, 6.4, 7.0))
    c.event("impact", 1.75, 3.55)
    return c


# ---------------------------------------------------------------------------- oath of fealty

def fealty_a():
    """The vassal kneels and raises the joined hands; the lord encloses them, the oath is spoken,
    the hands are released and the vassal rises."""
    L = 6.4
    c = Clip("fealty_a", L)
    env = kneel(c, (0.1, 0.95), (5.3, 6.25), head=(-8, 0, 0), chest=(-4, 0, 0),
                stay=[(2.2, {"head": (-4, 0, 0)}), (4.4, {"head": (-4, 0, 0)}), (4.9, {"head": (-12, 0, 0)})])
    hands = window(0.9, 1.6, 4.8, 5.35)
    for limb, side, pole in (("r_arm", 1.0, ARM_REST_POLE_R), ("l_arm", -1.0, ARM_REST_POLE_L)):
        joined = mine("chest", (side * 0.7, 18.6, -8.2))
        c.ik(limb, path(limb, [(0.0, REST, "linear"), (0.9, REST, "linear"),
                               (1.6, joined, "cubic"), (4.8, joined, "linear"), (5.35, REST, "inout"),
                               (L, REST, "linear")]),
             vec_path([(0.0, pole, "linear"), (0.9, pole, "linear"), (1.5, (side * 0.7, -1.0, 0.5), "inout"),
                       (4.8, (side * 0.7, -1.0, 0.5), "linear"), (5.35, pole, "inout"), (L, pole, "linear")]),
             hands)
    breathe(c, 0.8, 3.2, env)
    c.event("impact", 2.1)
    return c


def fealty_b():
    """The lord stands, leans a little forward and encloses the vassal's joined hands with both of
    his own; nods at the end of the oath."""
    L = 6.4
    c = Clip("fealty_b", L)
    c.key(0.0, chest=ZERO, head=ZERO, hip=ZERO)
    c.key(1.2, chest=ZERO, head=(-10, 0, 0), hip=ZERO)
    c.key(1.9, "cubic", chest=(-8, 0, 0), head=(-22, 0, 0), hip=(-4, 0, 0))
    c.key(4.3, chest=(-8, 0, 0), head=(-22, 0, 0), hip=(-4, 0, 0))
    c.key(4.6, head=(-30, 0, 0))
    c.key(4.9, chest=(-6, 0, 0), head=(-20, 0, 0), hip=(-3, 0, 0))
    c.key(5.6, "cubic", chest=ZERO, head=(-6, 0, 0), hip=ZERO)
    c.key(L - 0.05, chest=ZERO, head=ZERO, hip=ZERO)
    for limb, side, pole in (("r_arm", 1.0, ARM_REST_POLE_R), ("l_arm", -1.0, ARM_REST_POLE_L)):
        def cupped(ctx, t, s=side):
            p = ctx.partner("chest", (0.0, 18.4, -8.2))
            return add(p, (s * 2.1, 0.3, 0.6))
        c.ik(limb, path(limb, [(0.0, REST, "linear"), (1.3, REST, "linear"),
                               (1.75, mine("chest", (side * 6.0, 16.0, -6.0)), "inout"),
                               (2.1, cupped, "cubic"), (4.75, cupped, "linear"),
                               (5.3, mine("chest", (side * 6.5, 15.5, -4.5)), "inout"),
                               (L - 0.05, REST, "inout")]),
             vec_path([(0.0, pole, "linear"), (1.3, pole, "linear"), (1.8, (side * 1.0, -0.6, 0.4), "inout"),
                       (4.8, (side * 1.0, -0.6, 0.4), "linear"), (L - 0.05, pole, "inout")]), ik_window(L))
    breathe(c, 0.6, 3.2, window(0.0, 0.5, 5.9, 6.4))
    c.event("impact", 2.1)
    return c


# ---------------------------------------------------------------------------- coronation

def crown_b():
    """The ruler kneels, receives the crown on the bowed head and rises crowned."""
    L = 7.2
    c = Clip("crown_b", L)
    env = kneel(c, (0.1, 0.95), (5.75, 6.75), head=(-12, 0, 0), chest=(-5, 0, 0),
                stay=[(3.4, {"head": (-14, 0, 0)}), (4.2, {"head": (-6, 0, 0)})])
    for limb, side, pole in (("r_arm", 1.0, ARM_REST_POLE_R), ("l_arm", -1.0, ARM_REST_POLE_L)):
        joined = mine("chest", (side * 0.75, 19.6, -5.0))
        c.ik(limb, path(limb, [(0.0, REST, "linear"), (0.6, REST, "linear"), (1.3, joined, "inout"),
                               (4.6, joined, "linear"), (5.4, REST, "inout"), (L, REST, "linear")]),
             vec_path([(0.0, pole, "linear"), (1.2, (side * 0.7, -1.0, 0.45), "inout"),
                       (4.6, (side * 0.7, -1.0, 0.45), "linear"), (5.4, pole, "inout"), (L, pole, "linear")]),
             window(0.6, 1.3, 4.6, 5.4))
    c.key(6.75, head=ZERO)
    c.key(7.0, head=(4, 0, 0))     # proud look after rising
    c.key(L - 0.05, head=ZERO)
    breathe(c, 0.8, 3.6, env)
    c.event("impact", 3.8)
    return c


CROWN_GRAB = 0.35
CROWN_SET = 3.8


def crown_a():
    """The officiant takes the crown in both hands, lifts it high over the kneeling ruler, holds it
    up, lowers it slowly onto the head, lets go and bows."""
    L = 7.2
    c = Clip("crown_a", L)
    c.key(0.0, chest=ZERO, head=ZERO, hip=ZERO, waist=ZERO)
    c.key(1.6, "cubic", chest=(6, 0, 0), head=(10, 0, 0), hip=ZERO, waist=(2, 0, 0))
    c.key(2.8, chest=(6, 0, 0), head=(10, 0, 0), hip=ZERO, waist=(2, 0, 0))
    c.key(CROWN_SET, "inout", chest=(-8, 0, 0), head=(-12, 0, 0), hip=(-4, 0, 0), waist=(-2, 0, 0))
    c.key(4.4, chest=(-4, 0, 0), head=(-6, 0, 0), hip=ZERO, waist=ZERO)
    # a reverence to the crowned ruler while the ruler still kneels (the heads never meet)
    c.key(5.0, "cubic", chest=(-4, 0, 0), head=(-14, 0, 0), hip=(-20, 0, 0), waist=(-4, 0, 0))
    c.key(5.35, chest=(-4, 0, 0), head=(-14, 0, 0), hip=(-20, 0, 0), waist=(-4, 0, 0))
    c.key(5.95, "cubic", chest=ZERO, head=ZERO, hip=ZERO, waist=ZERO)
    c.key(L, chest=ZERO, head=ZERO, hip=ZERO, waist=ZERO)
    c.keypos(0.0, root=ZERO)
    c.keypos(4.4, root=ZERO)
    c.keypos(5.0, "cubic", root=(0, -0.3, 1.2))
    c.keypos(5.35, root=(0, -0.3, 1.2))
    c.keypos(5.95, "cubic", root=ZERO)
    c.keypos(L, root=ZERO)
    c.key(0.0, r_leg=ZERO, r_shin=ZERO, l_shin=ZERO)
    c.key(4.4, r_leg=ZERO, r_shin=ZERO, l_shin=ZERO)
    c.key(5.0, r_leg=(-10, 0, 2), r_shin=(-6, 0, 0), l_shin=(-6, 0, 0))
    c.key(5.35, r_leg=(-10, 0, 2), r_shin=(-6, 0, 0), l_shin=(-6, 0, 0))
    c.key(5.95, r_leg=ZERO, r_shin=ZERO, l_shin=ZERO)
    c.key(L, r_leg=ZERO, r_shin=ZERO, l_shin=ZERO)

    def crown_point(lift):
        return lambda ctx, t: add(ctx.partner("head", HEAD_TOP), (0.0, lift, 0.0))

    for limb, side, pole in (("r_arm", 1.0, ARM_REST_POLE_R), ("l_arm", -1.0, ARM_REST_POLE_L)):
        hold = mine("chest", (side * 3.0, 18.5, -7.0))

        def at(spec, s=side):
            return lambda ctx, t: add(spec(ctx, t), (s * 3.3, 0.0, -0.6))
        c.ik(limb, path(limb, [
            (0.0, REST, "linear"),
            (CROWN_GRAB, hold, "inout"),
            (0.7, hold, "linear"),
            (1.6, at(crown_point(5.0)), "cubic"),
            (2.8, at(crown_point(5.5)), "linear"),
            (CROWN_SET, at(crown_point(0.6)), "inout"),
            (4.3, mine("chest", (side * 5.5, 18.0, -6.0)), "inout"),
            (4.9, REST, "inout"),
            (L, REST, "linear"),
        ]), vec_path([(0.0, pole, "linear"), (0.4, (side * 1.0, -0.5, 0.3), "inout"),
                      (4.3, (side * 1.0, -0.5, 0.3), "linear"), (4.9, pole, "inout"), (L, pole, "linear")]),
            window(0.0, 0.05, 4.85, 4.95))
    c.event("impact", CROWN_SET)
    c.prop = ("crown", CROWN_GRAB, CROWN_SET)   # crown between the hands, then on the partner's head
    return c


def crown_self():
    """Self-coronation: raise the crown high, show it, lower it onto the own head, then stand proud
    with the hands on the hips."""
    L = 6.0
    set_t = 3.3
    c = Clip("crown_self", L)
    c.key(0.0, chest=ZERO, head=ZERO, waist=ZERO)
    c.key(1.4, "cubic", chest=(8, 0, 0), head=(18, 0, 0), waist=(3, 0, 0))
    c.key(2.4, chest=(8, 0, 0), head=(18, 0, 0), waist=(3, 0, 0))
    c.key(set_t, "inout", chest=(-2, 0, 0), head=(-6, 0, 0), waist=ZERO)
    c.key(4.2, "cubic", chest=(4, 0, 0), head=(6, 0, 0), waist=ZERO)
    c.key(5.2, chest=(4, 0, 0), head=(6, 0, 0), waist=ZERO)
    c.key(L - 0.05, chest=ZERO, head=ZERO, waist=ZERO)
    c.key(0.0, r_leg=ZERO, l_leg=ZERO)
    c.key(4.2, r_leg=(0, 0, 4), l_leg=(0, 0, -4))
    c.key(5.2, r_leg=(0, 0, 4), l_leg=(0, 0, -4))
    c.key(L - 0.05, r_leg=ZERO, l_leg=ZERO)
    for limb, side, pole in (("r_arm", 1.0, ARM_REST_POLE_R), ("l_arm", -1.0, ARM_REST_POLE_L)):
        hold = mine("chest", (side * 3.0, 18.5, -7.0))
        high = mine("head", (side * 3.4, 35.5, -3.6))
        on_head = mine("head", (side * 3.6, 31.0, 0.0))
        hips = mine("waist", (side * 5.4, 15.4, 0.4))
        c.ik(limb, path(limb, [
            (0.0, REST, "linear"),
            (CROWN_GRAB, hold, "inout"),
            (0.6, hold, "linear"),
            (1.4, high, "cubic"),
            (2.4, high, "linear"),
            (set_t, on_head, "inout"),
            (3.7, mine("chest", (side * 7.0, 20.0, -4.0)), "inout"),
            (4.2, hips, "inout"),
            (5.2, hips, "linear"),
            (L - 0.05, REST, "inout"),
        ]), vec_path([(0.0, pole, "linear"), (0.4, (side * 1.0, -0.5, 0.3), "inout"),
                      (3.6, (side * 1.0, -0.5, 0.3), "linear"), (4.2, (side * 1.0, 0.1, 0.4), "inout"),
                      (5.2, (side * 1.0, 0.1, 0.4), "linear"), (L - 0.05, pole, "inout")]), ik_window(L))
    c.event("impact", set_t)
    c.prop = ("crown", CROWN_GRAB, set_t)
    return c


# ---------------------------------------------------------------------------- props

def prop_track(rig, clip, samples, partner_samples=None, mapping=None, every=2):
    """Crown path in the clip owner's model space (px): between the hands from ``grab`` to ``set``,
    then on the head of the crowned one (the partner, or the owner for a self-coronation).
    Returns [(t, x, y, z)] every ``every`` ticks."""
    _name, grab, set_t = clip.prop
    out = []
    for i, (t, pose) in enumerate(samples):
        if i % every and i != len(samples) - 1:
            continue
        if t < grab:
            continue
        if t <= set_t:
            w = rig.fk(pose)
            r = rig.end_effector(w, "r_arm")
            l = rig.end_effector(w, "l_arm")
            p = lerp3(r, l, 0.5)
            p = add(p, (0.0, 1.2, 0.0))
        elif partner_samples is not None:
            pw = rig.fk(partner_samples[min(i, len(partner_samples) - 1)][1])
            p = mapping.apply(pw["head"].apply(add(HEAD_TOP, (0.0, 0.4, 0.0))))
        else:
            p = rig.fk(pose)["head"].apply(add(HEAD_TOP, (0.0, 0.4, 0.0)))
        out.append((round(t, 3), round(p[0], 2), round(p[1], 2), round(p[2], 2)))
    return out


# ---------------------------------------------------------------------------- catalogue

def build(rig):
    out = []
    a, b = handshake("a"), handshake("b")
    Pair(a, b, Pair.FACE, 0.75)
    out += [a, b]
    a, b = toast("a"), toast("b")
    Pair(a, b, Pair.FACE, 0.9)
    out += [a, b]
    a, b = courtbow_a(), courtbow_b()
    Pair(a, b, Pair.FACE, 1.6)
    out += [a, b]
    b = knight_b()
    a = knight_a(rig, b, 1.15)
    Pair(a, b, Pair.FACE, 1.15)
    out += [a, b]
    a, b = fealty_a(), fealty_b()
    Pair(a, b, Pair.FACE, 0.62)
    out += [a, b]
    a, b = crown_a(), crown_b()
    Pair(a, b, Pair.FACE, 0.85)
    out += [a, b, crown_self()]
    return out


ANIMATIONS = {
    "handshake": {"type": "pair", "arrangement": "face", "distance": 0.75,
                  "roles": {"sender": {"clip": "handshake_a", "lock": True},
                            "target": {"clip": "handshake_b", "lock": True}}},
    "toast": {"type": "pair", "arrangement": "face", "distance": 0.9,
              "roles": {"sender": {"clip": "toast_a", "lock": True}, "target": {"clip": "toast_b", "lock": True}}},
    "courtbow": {"type": "pair", "arrangement": "face", "distance": 1.6,
                 "roles": {"sender": {"clip": "courtbow_a", "lock": True},
                           "target": {"clip": "courtbow_b", "lock": True}}},
    "knight": {"type": "pair", "arrangement": "face", "distance": 1.15,
               "roles": {"sender": {"clip": "knight_a", "lock": True}, "target": {"clip": "knight_b", "lock": True}}},
    "fealty": {"type": "pair", "arrangement": "face", "distance": 0.62,
               "roles": {"sender": {"clip": "fealty_a", "lock": True}, "target": {"clip": "fealty_b", "lock": True}}},
    "crown": {"type": "pair", "arrangement": "face", "distance": 0.85,
              "roles": {"sender": {"clip": "crown_a", "lock": True}, "target": {"clip": "crown_b", "lock": True}}},
    "crown_self": {"type": "solo", "arrangement": "none", "roles": {"sender": {"clip": "crown_self", "lock": True}}},
}
