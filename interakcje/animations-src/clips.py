# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""All animations of the Interakcje plugin, written as code.

Conventions (see rig.py): the character faces -Z, right = +X, 16 px = 1 block.
  torso / head: X < 0 bows forward, Y > 0 turns left, Z > 0 tilts to the left
  arms:  X > 0 raises forward, right arm Z > 0 / left arm Z < 0 raises sideways
  forearm X > 0 bends the elbow; leg X > 0 lifts the thigh forward; shin X < 0 bends the knee
Most contacts (hands on a partner's back, a palm on a head, hands meeting) are solved with IK
against the partner's actual (animated) body, so tuning a torso key never breaks a contact.

Ground in model space: standing clips y = 0. A player sitting on the plugin's seat (a 0-high
BlockDisplay) is placed 0.6 block below the seat by vanilla (player vehicle attachment), so the
seat surface is at y = +9.6 px (SEAT_Y) in the sitting clips.
"""
import math

from motion import EASE, Clip, Pair, wave, window
from rig import add, lerp3, mirror_x

SEAT_Y = 9.6           # seat surface in model space while riding the seat (0.6 block * 16)
REST = object()        # path marker: the limb's keyed (non-IK) position

ARM_REST_POLE_R = (0.15, -0.1, 1.0)   # elbow behind the arm -> no twist at rest
ARM_REST_POLE_L = mirror_x(ARM_REST_POLE_R)
LEG_REST_POLE = (0.0, 0.0, -1.0)      # knee in front of the leg


# ---------------------------------------------------------------------------- helpers

def mine(bone, point):
    return lambda ctx, t: ctx.mine(bone, point)


def partner(bone, point):
    return lambda ctx, t: ctx.partner(bone, point)


def fixed(point):
    return lambda ctx, t: point


def offset(spec, fn):
    """spec + fn(t) (an additive oscillation of a target)."""
    return lambda ctx, t: add(spec(ctx, t), fn(t))


def joint(limb, extra=(0.0, 0.0, 0.0)):
    """The current elbow / knee of a limb (after earlier IK in the same frame) + extra."""
    return lambda ctx, t: add(ctx.rig.joint(ctx.world, limb), extra)


def path(limb, points):
    """IK target along waypoints [(time, spec, ease)]; spec: REST, a tuple or f(ctx, t)."""
    def resolve(spec, ctx, t):
        if spec is REST:
            return ctx.keyed_end(limb)
        if callable(spec):
            return spec(ctx, t)
        return spec

    def target(t, ctx):
        if t <= points[0][0]:
            return resolve(points[0][1], ctx, t)
        for (t0, s0, _e0), (t1, s1, e1) in zip(points, points[1:]):
            if t <= t1:
                u = 0.0 if t1 - t0 < 1e-9 else (t - t0) / (t1 - t0)
                return lerp3(resolve(s0, ctx, t), resolve(s1, ctx, t), EASE[e1](u))
        return resolve(points[-1][1], ctx, t)
    return target


def vec_path(points):
    """Direction (pole) along waypoints [(time, vec, ease)]."""
    def f(t, ctx):
        if t <= points[0][0]:
            return points[0][1]
        for (t0, v0, _e0), (t1, v1, e1) in zip(points, points[1:]):
            if t <= t1:
                u = 0.0 if t1 - t0 < 1e-9 else (t - t0) / (t1 - t0)
                return lerp3(v0, v1, EASE[e1](u))
        return points[-1][1]
    return f


def ik_window(length, edge=0.05):
    return window(0.0, edge, length - edge, length)


def breathe(c, amplitude=0.7, period=3.6, envelope=None):
    env = envelope or (lambda t: 1.0)
    c.layer("chest", lambda t: (amplitude * env(t) * math.sin(2 * math.pi * t / period), 0.0, 0.0))
    c.layer("head", lambda t: (-0.6 * amplitude * env(t) * math.sin(2 * math.pi * t / period - 0.6), 0.0, 0.0))


def osc(amplitude, period, phase=0.0, axis=0, envelope=None):
    """(x, y, z) sine with the value on one axis."""
    env = envelope or (lambda t: 1.0)

    def f(t):
        v = [0.0, 0.0, 0.0]
        v[axis] = amplitude * env(t) * math.sin(2 * math.pi * (t / period + phase))
        return tuple(v)
    return f


# ---------------------------------------------------------------------------- hug

def hug(role):
    """Face to face 0.6 block apart; right arm high over the partner's left shoulder, left arm low
    around the partner's right side, heads beside each other (each over the partner's right
    shoulder), gentle rocking, a squeeze and - for the initiator - two pats on the back."""
    L = 3.0
    a = role == "a"
    d = 0.0 if a else 0.08  # the receiver reacts a moment later
    c = Clip("hug_" + role, L)
    # step in and to the own left so the heads pass beside each other
    c.keypos(0, root=(0, 0, 0))
    c.keypos(0.55 + d, "cubic", root=(-2.7, -0.25, -1.4))
    c.keypos(2.35, root=(-2.7, -0.25, -1.4))
    c.keypos(2.95, "cubic", root=(0, 0, 0))
    c.key(0, hip=(0, 0, 0), waist=(0, 0, 0), chest=(0, 0, 0), head=(0, 0, 0),
          r_leg=(0, 0, 0), l_leg=(0, 0, 0), r_shin=(0, 0, 0), l_shin=(0, 0, 0))
    c.key(0.5 + d, "cubic", hip=(-3, 0, 1), waist=(-3, 0, 1), chest=(-5, -4, 3), r_leg=(3, 0, 1), l_leg=(1, 0, -1),
          r_shin=(-5, 0, 0), l_shin=(-2, 0, 0))
    c.key(0.65 + d, head=(-7, 22, 9))
    c.key(1.35, head=(-8, 24, 12))      # nuzzle
    c.key(1.7, head=(-7, 22, 9))
    c.key(2.3, hip=(-3, 0, 1), waist=(-3, 0, 1), chest=(-5, -4, 3), head=(-7, 22, 9),
          r_leg=(3, 0, 1), l_leg=(1, 0, -1), r_shin=(-5, 0, 0), l_shin=(-2, 0, 0))
    c.key(2.9, "cubic", hip=(0, 0, 0), waist=(0, 0, 0), chest=(0, 0, 0),
          r_leg=(0, 0, 0), l_leg=(0, 0, 0), r_shin=(0, 0, 0), l_shin=(0, 0, 0))
    c.key(3.0, head=(0, 0, 0))
    # rocking as one unit: both turn the same way (yaw) and lean to the same world side
    sway_env = window(0.6, 1.0, 2.0, 2.4)
    side = 1.0 if a else -1.0
    c.layer("hip", lambda t: (0.0, 3.0 * sway_env(t) * math.sin(2 * math.pi * (t - 0.6) / 1.8),
                              side * 1.6 * sway_env(t) * math.sin(2 * math.pi * (t - 0.6) / 1.8)))
    breathe(c, 0.8, 2.4, window(0.3, 0.8, 2.3, 2.8))

    squeeze = window(1.15, 1.35, 1.55, 1.85)
    # right arm high: hand on the partner's left shoulder blade
    high = partner("chest", (-1.6, 21.2, 2.7))
    high_in = offset(high, lambda t: (0.0, 0.0, -0.5 * squeeze(t)))
    if a:  # two pats on the back
        pats = window(1.9, 1.98, 1.98, 2.08)
        pats2 = window(2.1, 2.18, 2.18, 2.28)
        high_in = offset(high_in, lambda t: (0.0, 0.3 * (pats(t) + pats2(t)), 1.4 * (pats(t) + pats2(t))))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.3 + d, mine("chest", (9.5, 19.5, -5.5)), "inout"),
        (0.7 + d, high_in, "cubic"),
        (2.35, high_in, "linear"),
        (2.65, mine("chest", (9.0, 18.5, -5.0)), "inout"),
        (2.95, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.45 + d, (1.0, -0.25, 0.35), "inout"),
                  (2.45, (1.0, -0.25, 0.35), "linear"), (2.95, ARM_REST_POLE_R, "inout")]),
         ik_window(L))
    # left arm low: around the partner's right flank
    low = partner("waist", (3.0, 16.4, 2.4))
    low_in = offset(low, lambda t: (0.0, 0.0, -0.5 * squeeze(t)))
    c.ik("l_arm", path("l_arm", [
        (0.0, REST, "linear"),
        (0.35 + d, mine("chest", (-9.0, 15.5, -5.5)), "inout"),
        (0.75 + d, low_in, "cubic"),
        (2.3, low_in, "linear"),
        (2.6, mine("chest", (-8.5, 15.0, -5.0)), "inout"),
        (2.95, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_L, "linear"), (0.45 + d, (-1.0, -0.45, 0.3), "inout"),
                  (2.45, (-1.0, -0.45, 0.3), "linear"), (2.95, ARM_REST_POLE_L, "inout")]),
         ik_window(L))
    c.event("impact", 0.75)
    return c


# ---------------------------------------------------------------------------- kiss

def kiss(role):
    """Lean in, heads tilted to opposite sides (each to their own right), a short kiss with a
    tiny second press, then lean back. The initiator holds the partner's waist, the receiver
    rests the hands on the initiator's shoulders."""
    L = 2.5
    a = role == "a"
    d = 0.0 if a else 0.1
    c = Clip("kiss_" + role, L)
    c.keypos(0, root=(0, 0, 0))
    c.keypos(0.7 + d, "cubic", root=(0, -0.2, -0.1))
    c.keypos(1.75, root=(0, -0.2, -0.1))
    c.keypos(2.45, "cubic", root=(0, 0, 0))
    c.key(0, hip=(0, 0, 0), waist=(0, 0, 0), chest=(0, 0, 0), head=(0, 0, 0), r_shin=(0, 0, 0), l_shin=(0, 0, 0),
          r_leg=(0, 0, 0), l_leg=(0, 0, 0))
    lean = (-2, 0, 0) if a else (-1.5, 0, 0)
    c.key(0.65 + d, "cubic", hip=lean, waist=(-2, 0, 0), chest=(-3, 0, 0), l_shin=(-6, 0, 0), l_leg=(2, 0, 0))
    c.key(0.8 + d, "cubic", head=(-3, -7, -15))
    c.key(1.15, head=(-4, -8, -16))
    c.key(1.3, head=(-3, -7, -14))      # tiny second press
    c.key(1.45, head=(-4, -8, -16))
    c.key(1.75, hip=lean, waist=(-2, 0, 0), chest=(-3, 0, 0), head=(-3, -7, -15), l_shin=(-6, 0, 0), l_leg=(2, 0, 0))
    c.key(2.35, "cubic", hip=(0, 0, 0), waist=(0, 0, 0), chest=(0, 0, 0), l_shin=(0, 0, 0), l_leg=(0, 0, 0))
    c.key(2.5, head=(0, 0, 0))
    breathe(c, 0.6, 2.2, window(0.2, 0.6, 1.9, 2.3))
    if a:  # hands on the partner's waist
        r_t = partner("waist", (-4.4, 15.8, -0.3))
        l_t = partner("waist", (4.4, 15.8, -0.3))
        r_pole, l_pole = (0.6, -1.0, 0.5), (-0.6, -1.0, 0.5)
    else:  # hands on the partner's shoulders
        r_t = partner("chest", (-4.2, 22.6, -1.0))
        l_t = partner("chest", (4.2, 22.6, -1.0))
        r_pole, l_pole = (0.7, -1.0, 0.3), (-0.7, -1.0, 0.3)
    for limb, t_spec, pole, rest_pole, mid in (
            ("r_arm", r_t, r_pole, ARM_REST_POLE_R, (8.0, 15.0, -4.0)),
            ("l_arm", l_t, l_pole, ARM_REST_POLE_L, (-8.0, 15.0, -4.0))):
        c.ik(limb, path(limb, [
            (0.0, REST, "linear"),
            (0.35 + d, mine("chest", mid), "inout"),
            (0.75 + d, t_spec, "cubic"),
            (1.8, t_spec, "linear"),
            (2.1, mine("chest", mid), "inout"),
            (2.45, REST, "inout"),
        ]), vec_path([(0.0, rest_pole, "linear"), (0.5 + d, pole, "inout"), (1.9, pole, "linear"),
                      (2.45, rest_pole, "inout")]), ik_window(L))
    c.event("impact", 0.85)
    return c


# ---------------------------------------------------------------------------- holding hands

def hold(side):
    """Loop: standing side by side, inner hands joined and gently swinging, weight shifting."""
    L = 4.0
    right = side == "right"  # partner on my right -> my right hand holds
    c = Clip("hold_" + side, L, loop=True, model="interakcje_look")
    s = 1.0 if right else -1.0
    hand = "r_arm" if right else "l_arm"
    free = "l_arm" if right else "r_arm"
    # weight shift and breathing (all periodic in 4 s)
    c.layer("hip", lambda t: (0.0, 0.0, 1.2 * math.sin(2 * math.pi * t / 4.0)))
    c.layer("chest", lambda t: (0.0, 1.5 * s * math.sin(2 * math.pi * t / 4.0 + 1.0), -0.8 * math.sin(2 * math.pi * t / 4.0)))
    breathe(c, 0.7, 4.0)
    c.layer("r_shin" if right else "l_shin", lambda t: (-3.0 - 3.0 * math.sin(2 * math.pi * t / 4.0), 0.0, 0.0))
    c.layer(free, lambda t: (2.0 * math.sin(2 * math.pi * t / 2.0), 0.0, -s * (3.0 + 0.6 * math.sin(2 * math.pi * t / 4.0))))
    c.layer("l_fore" if right else "r_fore", lambda t: (6.0 + 2.0 * math.sin(2 * math.pi * t / 2.0 - 0.5), 0.0, 0.0))
    # joined hands at the midpoint between the players, slightly in front, swinging together
    mid_x = s * 0.8 * 8.0
    c.ik(hand, lambda t, ctx: (mid_x, 12.4 + 0.25 * math.sin(4 * math.pi * t / 2.0),
                               -2.0 - 0.9 * math.sin(2 * math.pi * t / 2.0)),
         (s * 0.5, -0.2, 1.0))
    return c


# ---------------------------------------------------------------------------- high five

def high_five(role):
    """Wind up beside the head, accelerate into the clap at 0.5 s, rebound, recover."""
    L = 1.6
    a = role == "a"
    c = Clip("highfive_" + role, L)
    contact = 0.5
    c.key(0, chest=(0, 0, 0), waist=(0, 0, 0), hip=(0, 0, 0), head=(0, 0, 0), l_arm=(0, 0, 0), l_fore=(0, 0, 0),
          r_leg=(0, 0, 0), l_leg=(0, 0, 0), l_shin=(0, 0, 0))
    c.key(0.38, chest=(3, -11, 0), waist=(1, -5, 0), head=(5, 12, 0), l_arm=(12, 0, -7), l_fore=(18, 0, 0),
          r_leg=(-4, 0, 0), l_leg=(5, 0, 0), l_shin=(-6, 0, 0))
    c.key(contact, "quad_in", chest=(-4, 9, 0), waist=(-2, 4, 0), hip=(-2, 0, 0), head=(-2, -8, 0),
          l_arm=(-10, 0, -9), l_fore=(10, 0, 0))
    c.key(0.68, "quart_out", chest=(-1, 5, 0), head=(3, -5, 0))
    c.key(L - 0.05, "inout", chest=(0, 0, 0), waist=(0, 0, 0), hip=(0, 0, 0), head=(0, 0, 0), l_arm=(0, 0, 0),
          l_fore=(0, 0, 0), r_leg=(0, 0, 0), l_leg=(0, 0, 0), l_shin=(0, 0, 0))
    c.keypos(0, root=(0, 0, 0))
    c.keypos(contact, "quad_in", root=(0, -0.3, -0.9))
    c.keypos(L - 0.05, root=(0, 0, 0))
    # palms meet on the line between the players (0.9 block apart), each stopping short of it
    d = 0.9 * 8.0
    hit = (0.0, 30.2, -(d - 1.25))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.2, mine("chest", (10.5, 21.0, -1.5)), "inout"),
        (0.38, mine("chest", (7.8, 30.0, 1.8)), "inout"),
        (contact, fixed(hit), "quad_in"),
        (0.68, mine("chest", (3.5, 28.5, -3.5)), "quart_out"),
        (1.15, mine("chest", (8.6, 17.0, -2.0)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.3, (1.0, -1.0, 0.3), "inout"),
                  (1.1, (1.0, -1.0, 0.3), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    if not a:  # the receiver's free hand stays a bit closer to the body
        c.layer("l_arm", lambda t: (0.0, 0.0, 2.0 * window(0.2, 0.4, 0.7, 1.2)(t)))
    c.event("impact", contact)
    return c


# ---------------------------------------------------------------------------- pat

def pat(role):
    """The patter leans in and strokes the partner's head three times; the partner ducks a bit
    and leans into each stroke."""
    L = 2.6
    c = Clip("pat_" + role, L)
    strokes = (0.6, 2.05)
    period = (strokes[1] - strokes[0]) / 3.0
    stroke_env = window(strokes[0], strokes[0] + 0.1, strokes[1] - 0.1, strokes[1])
    if role == "a":
        c.keypos(0, root=(0, 0, 0))
        c.keypos(0.5, "cubic", root=(0, -0.2, -1.2))
        c.keypos(2.1, root=(0, -0.2, -1.2))
        c.keypos(2.55, "cubic", root=(0, 0, 0))
        c.key(0, hip=(0, 0, 0), waist=(0, 0, 0), chest=(0, 0, 0), head=(0, 0, 0), l_arm=(0, 0, 0), l_shin=(0, 0, 0))
        c.key(0.5, "cubic", hip=(-6, 0, 0), waist=(-4, -3, 0), chest=(-5, -4, 2), l_arm=(6, 0, -3), l_shin=(-5, 0, 0))
        c.key(0.6, head=(-14, 3, 7))
        c.key(2.1, hip=(-6, 0, 0), waist=(-4, -3, 0), chest=(-5, -4, 2), head=(-14, 3, 7), l_arm=(6, 0, -3),
              l_shin=(-5, 0, 0))
        c.key(2.55, "cubic", hip=(0, 0, 0), waist=(0, 0, 0), chest=(0, 0, 0), l_arm=(0, 0, 0), l_shin=(0, 0, 0))
        c.key(L, head=(0, 0, 0))
        c.layer("chest", lambda t: (0.0, 0.0, 0.8 * stroke_env(t) * math.sin(2 * math.pi * (t - strokes[0]) / period)))
        # palm on top of the partner's head, stroking front to back
        top = partner("head", (0.4, 31.7, -1.0))
        stroke = offset(top, lambda t: (0.0,
                                        0.45 * stroke_env(t) * max(0.0, -math.sin(2 * math.pi * (t - strokes[0]) / period)),
                                        -1.4 * stroke_env(t) * math.cos(2 * math.pi * (t - strokes[0]) / period)))
        c.ik("r_arm", path("r_arm", [
            (0.0, REST, "linear"),
            (0.32, mine("chest", (8.5, 24.5, -6.0)), "inout"),
            (strokes[0], stroke, "cubic"),
            (strokes[1], stroke, "linear"),
            (2.3, mine("chest", (8.5, 22.0, -5.0)), "inout"),
            (L - 0.05, REST, "inout"),
        ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.4, (0.8, -1.0, 0.4), "inout"),
                      (2.1, (0.8, -1.0, 0.4), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
        c.event("impact", strokes[0])
    else:
        dip = dict(head=(-11, 0, 3), chest=(-4, 0, 0), waist=(-2, 0, 0), r_arm=(0, 0, 3), l_arm=(0, 0, -3),
                   r_leg=(4, 0, 0), l_leg=(4, 0, 0), r_shin=(-8, 0, 0), l_shin=(-8, 0, 0))
        c.key(0, **{k: (0, 0, 0) for k in dip})
        c.key(0.55, "cubic", **dip)
        c.key(2.1, **dip)
        c.key(L - 0.05, "cubic", **{k: (0, 0, 0) for k in dip})
        c.keypos(0, root=(0, 0, 0), r_arm=(0, 0, 0), l_arm=(0, 0, 0))
        c.keypos(0.55, "cubic", r_arm=(0, 0.5, 0), l_arm=(0, 0.5, 0), root=(0, -0.7, 0))
        c.keypos(2.1, r_arm=(0, 0.5, 0), l_arm=(0, 0.5, 0), root=(0, -0.7, 0))
        c.keypos(L - 0.05, "cubic", r_arm=(0, 0, 0), l_arm=(0, 0, 0), root=(0, 0, 0))
        c.layer("head", lambda t: (1.5 * stroke_env(t) * math.sin(2 * math.pi * (t - strokes[0]) / period), 0.0,
                                   3.0 * stroke_env(t) * math.sin(2 * math.pi * (t - strokes[0]) / period + 0.8)))
        breathe(c, 0.6, 2.6, window(0.0, 0.5, 2.1, 2.6))
    return c


# ---------------------------------------------------------------------------- slap

def slap_a():
    """Wind up (torso coils right, arm out and back), whip across the partner's left cheek at
    0.44 s, follow through, recover."""
    L = 1.5
    hit_t = 0.44
    c = Clip("slap_a", L)
    c.key(0, chest=(0, 0, 0), waist=(0, 0, 0), hip=(0, 0, 0), head=(0, 0, 0), l_arm=(0, 0, 0), l_fore=(0, 0, 0),
          r_leg=(0, 0, 0), l_leg=(0, 0, 0), r_shin=(0, 0, 0), l_shin=(0, 0, 0))
    c.key(0.36, chest=(1, -20, -2), waist=(0, -8, 0), hip=(0, -3, 0), head=(0, 22, 2), l_arm=(14, 0, -9),
          l_fore=(20, 0, 0), r_leg=(-5, 0, 2), l_shin=(-8, 0, 0), l_leg=(4, 0, 0))
    c.key(hit_t, "quad_in", chest=(-5, 16, 2), waist=(-3, 8, 0), hip=(-2, 3, 0), head=(-3, -10, 0),
          l_arm=(-12, 0, -12), l_fore=(12, 0, 0), r_leg=(2, 0, 0), r_shin=(-4, 0, 0), l_shin=(-3, 0, 0))
    c.key(0.62, "quart_out", chest=(-6, 22, 3), waist=(-3, 10, 0), head=(-2, -14, 0))
    c.key(L - 0.05, "inout", chest=(0, 0, 0), waist=(0, 0, 0), hip=(0, 0, 0), head=(0, 0, 0), l_arm=(0, 0, 0),
          l_fore=(0, 0, 0), r_leg=(0, 0, 0), l_leg=(0, 0, 0), r_shin=(0, 0, 0), l_shin=(0, 0, 0))
    c.keypos(0, root=(0, 0, 0))
    c.keypos(0.36, root=(0.4, 0, 0.3))
    c.keypos(hit_t, "quad_in", root=(-0.2, -0.2, -1.6))
    c.keypos(L - 0.05, root=(0, 0, 0))
    cheek = partner("head", (-5.6, 25.6, -1.2))  # just outside the partner's left cheek
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.36, mine("chest", (12.0, 24.5, 2.0)), "inout"),
        (hit_t, cheek, "quad_in"),
        (0.62, mine("chest", (-2.5, 22.0, -8.0)), "quart_out"),
        (1.05, mine("chest", (6.5, 16.0, -4.0)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.3, (0.3, -1.0, 0.6), "inout"),
                  (hit_t, (0.0, -1.0, 0.2), "linear"), (0.9, (0.6, -1.0, 0.3), "inout"),
                  (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    c.event("impact", hit_t)
    return c


def slap_b():
    """The head snaps to the right, a half step back, then the left hand goes to the cheek."""
    L = 2.3
    hit_t = 0.44
    snap = hit_t + 0.12
    c = Clip("slap_b", L)
    c.key(0, head=(0, 0, 0), chest=(0, 0, 0), waist=(0, 0, 0), r_arm=(0, 0, 0), r_fore=(0, 0, 0))
    c.key(hit_t, head=(0, 0, 0), chest=(0, 0, 0), waist=(0, 0, 0), r_arm=(0, 0, 0), r_fore=(0, 0, 0))
    c.key(snap, "quart_out", head=(5, -30, -13), chest=(2, -10, -4), waist=(1, -4, -1), r_arm=(0, 0, 14),
          r_fore=(12, 0, 0))
    c.key(0.9, "inout", head=(2, -13, -5), chest=(1, -4, -1), waist=(0, -1, 0), r_arm=(0, 0, 4), r_fore=(6, 0, 0))
    c.key(1.6, head=(-4, -6, -3), chest=(-2, -2, 0), waist=(0, 0, 0), r_arm=(0, 0, 0), r_fore=(0, 0, 0))
    c.key(L - 0.05, "inout", head=(0, 0, 0), chest=(0, 0, 0), waist=(0, 0, 0))
    c.keypos(0, root=(0, 0, 0))
    c.keypos(hit_t, root=(0, 0, 0))
    c.keypos(snap + 0.08, "quart_out", root=(0.9, 0, 1.3))
    c.keypos(L - 0.05, "inout", root=(0.5, 0, 0.7))
    c.keypos(L, root=(0, 0, 0))
    c.key(snap + 0.06, "quart_out", r_leg=(-6, 0, 3), r_shin=(-6, 0, 0), l_leg=(4, 0, 0), l_shin=(-8, 0, 0))
    c.key(0.0, r_leg=(0, 0, 0), r_shin=(0, 0, 0), l_leg=(0, 0, 0), l_shin=(0, 0, 0))
    c.key(hit_t, r_leg=(0, 0, 0), r_shin=(0, 0, 0), l_leg=(0, 0, 0), l_shin=(0, 0, 0))
    c.key(L - 0.05, "inout", r_leg=(0, 0, 0), r_shin=(0, 0, 0), l_leg=(0, 0, 0), l_shin=(0, 0, 0))
    # left hand to the slapped (left) cheek
    cheek = mine("head", (-5.3, 25.0, -1.6))
    c.ik("l_arm", path("l_arm", [
        (0.0, REST, "linear"),
        (snap, REST, "linear"),
        (0.78, mine("chest", (-8.5, 18.0, -4.0)), "inout"),
        (1.05, cheek, "inout"),
        (1.55, cheek, "linear"),
        (1.9, mine("chest", (-7.5, 15.0, -3.0)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_L, "linear"), (0.8, (-0.6, -1.0, 0.2), "inout"),
                  (1.6, (-0.6, -1.0, 0.2), "linear"), (L - 0.05, ARM_REST_POLE_L, "inout")]), ik_window(L))
    return c


# ---------------------------------------------------------------------------- solo greetings

def wave_clip():
    """Raise the right hand beside the head and wave from the elbow four times."""
    L = 2.4
    c = Clip("wave", L)
    up, down = 0.45, 2.05
    c.key(0, chest=(0, 0, 0), head=(0, 0, 0), l_arm=(0, 0, 0), waist=(0, 0, 0))
    c.key(up, chest=(1, 3, 4), head=(2, -4, 5), l_arm=(0, 0, -4), waist=(0, 2, 1))
    c.key(down, chest=(1, 3, 4), head=(2, -4, 5), l_arm=(0, 0, -4), waist=(0, 2, 1))
    c.key(L - 0.05, chest=(0, 0, 0), head=(0, 0, 0), l_arm=(0, 0, 0), waist=(0, 0, 0))
    env = window(up - 0.05, up + 0.1, down - 0.1, down + 0.05)
    period = (down - up) / 4.0
    c.layer("chest", lambda t: (0.0, 0.0, 1.0 * env(t) * math.sin(2 * math.pi * (t - up) / period)))
    raised = mine("chest", (11.0, 28.8, -1.6))
    waving = offset(raised, lambda t: (1.9 * env(t) * math.sin(2 * math.pi * (t - up) / period),
                                       -0.35 * env(t) * abs(math.sin(2 * math.pi * (t - up) / period)), 0.0))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (up, waving, "cubic"),
        (down, waving, "linear"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.3, (0.5, -1.0, 0.25), "inout"),
                  (down, (0.5, -1.0, 0.25), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    return c


def bow_clip():
    """Courtly bow: the right foot slides back, the right hand goes to the heart, the left arm
    sweeps out, the torso bends from the hips (head follows last), hold, rise."""
    L = 3.2
    c = Clip("bow", L)
    zero = (0, 0, 0)
    c.key(0, r_leg=zero, r_shin=zero, l_leg=zero, l_shin=zero, hip=zero, waist=zero, chest=zero, head=zero,
          l_arm=zero, l_fore=zero)
    c.key(0.35, r_leg=(-13, 0, 2), r_shin=(-8, 0, 0), l_shin=(-5, 0, 0), l_leg=(3, 0, 0))
    c.key(0.6, l_arm=(-15, 0, -40), l_fore=(12, 0, 0))
    c.key(0.55, hip=zero, waist=zero, chest=(2, 0, 0), head=(2, 0, 0))
    c.key(1.25, "cubic", hip=(-34, 0, 0), waist=(-9, 0, 0), chest=(-6, 0, 0), r_shin=(-14, 0, 0),
          l_shin=(-11, 0, 0), l_leg=(7, 0, 0))
    c.key(1.35, "cubic", head=(-14, 0, 0), l_arm=(22, 0, -42))
    c.key(1.85, hip=(-34, 0, 0), waist=(-9, 0, 0), chest=(-6, 0, 0), head=(-15, 0, 0), l_arm=(22, 0, -40),
          r_shin=(-14, 0, 0), l_shin=(-11, 0, 0), l_leg=(7, 0, 0))
    c.key(2.55, "cubic", hip=zero, waist=zero, chest=(1, 0, 0), r_shin=(-8, 0, 0), l_shin=(-5, 0, 0),
          l_leg=(3, 0, 0))
    c.key(2.7, head=(1, 0, 0))
    c.key(2.75, l_arm=(-10, 0, -20), l_fore=(8, 0, 0))
    c.key(L - 0.05, r_leg=zero, r_shin=zero, l_leg=zero, l_shin=zero, chest=zero, head=zero, l_arm=zero,
          l_fore=zero)
    c.keypos(0, root=zero)
    c.keypos(0.35, root=(0, -0.2, 0.6))
    c.keypos(1.25, "cubic", root=(0, -0.5, 0.9))
    c.keypos(2.55, "cubic", root=(0, -0.2, 0.6))
    c.keypos(L - 0.05, root=zero)
    breathe(c, 0.5, 3.0, window(0.0, 0.5, 2.7, 3.2))
    heart = mine("chest", (-1.4, 20.6, -2.9))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.2, mine("chest", (7.5, 16.5, -4.0)), "inout"),
        (0.55, heart, "inout"),
        (2.45, heart, "linear"),
        (2.8, mine("chest", (7.0, 15.5, -3.0)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.35, (1.0, -0.6, 0.4), "inout"),
                  (2.5, (1.0, -0.6, 0.4), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    return c


# ---------------------------------------------------------------------------- poses (loops)

def sit_floor():
    """Cross-legged on the ground, hands resting on the knees, relaxed breathing and swaying."""
    L = 4.0
    c = Clip("sit_floor", L, loop=True, model="interakcje_look")
    c.layer("hip", lambda t: (5.0, 1.0 * math.sin(2 * math.pi * t / L), 1.2 * math.sin(2 * math.pi * t / L + 0.7)))
    c.layer("waist", lambda t: (-6.0, 0.0, 0.0))
    c.layer("chest", lambda t: (-4.0, 0.0, -0.6 * math.sin(2 * math.pi * t / L + 0.7)))
    c.layer("head", lambda t: (4.0, 0.0, 0.0))
    breathe(c, 0.9, 4.0)
    sole = SEAT_Y + 1.6
    c.ik("r_leg", lambda t, ctx: (-2.4, sole, -4.4), (1.0, 0.55, -0.35))
    c.ik("l_leg", lambda t, ctx: (2.2, sole + 0.2, -6.3), (-1.0, 0.55, -0.35))
    sway = lambda t: (0.25 * math.sin(2 * math.pi * t / L), 0.0, 0.2 * math.sin(2 * math.pi * t / L + 1.3))
    c.ik("r_arm", lambda t, ctx: add(joint("r_leg", (-0.6, 2.4, -0.6))(ctx, t), sway(t)), (0.6, -0.4, 1.0))
    c.ik("l_arm", lambda t, ctx: add(joint("l_leg", (0.6, 2.4, -0.6))(ctx, t), sway(t)), (-0.6, -0.4, 1.0))
    return c


def sit_chair():
    """Sitting on stairs / a slab / a ledge with the legs down, forearms on the thighs."""
    L = 4.0
    c = Clip("sit_chair", L, loop=True, model="interakcje_look")
    c.layer("hip", lambda t: (-5.0, 0.8 * math.sin(2 * math.pi * t / L), 0.8 * math.sin(2 * math.pi * t / L + 0.5)))
    c.layer("waist", lambda t: (-4.0, 0.0, 0.0))
    c.layer("chest", lambda t: (-3.0, 0.0, -0.5 * math.sin(2 * math.pi * t / L + 0.5)))
    c.layer("head", lambda t: (7.0, 0.0, 0.0))
    breathe(c, 0.9, 4.0)
    # feet hanging down in front of the seat, swinging lazily in turn
    c.ik("r_leg", lambda t, ctx: (2.2, 3.8 + 0.3 * math.sin(2 * math.pi * t / L),
                                  -6.6 - 0.9 * math.sin(2 * math.pi * t / L)), (0.15, 0.3, -1.0))
    c.ik("l_leg", lambda t, ctx: (-2.2, 3.8 + 0.3 * math.sin(2 * math.pi * t / L + math.pi),
                                  -6.6 - 0.9 * math.sin(2 * math.pi * t / L + math.pi)), (-0.15, 0.3, -1.0))

    def on_thigh(limb, side):
        def f(t, ctx):
            hip_j = ctx.rig.limb_points(limb)[0]
            hip_w = ctx.world[limb].apply(hip_j)
            knee = ctx.rig.joint(ctx.world, limb)
            return add(lerp3(hip_w, knee, 0.62), (side * 0.3, 2.3, 0.0))
        return f
    c.ik("r_arm", on_thigh("r_leg", 1.0), (0.7, -0.3, 1.0))
    c.ik("l_arm", on_thigh("l_leg", -1.0), (-0.7, -0.3, 1.0))
    return c


def lay_back():
    """Lying on the back, hands folded behind the head, the right knee up and swaying."""
    L = 4.5
    c = Clip("lay_back", L, loop=True, model="interakcje_look")
    # rotate the whole body onto its back around the root pivot (0, 17, 0) and put the back on
    # the ground: the back surface (z = +1.875) ends up at y = 17 - 1.875, so drop by ~15 px
    c.layer("root", lambda t: (90.0, 0.0, 0.0))
    c.layer("root", lambda t: (0.0, -15.05, 2.0), channel="pos")
    # the upper body curls up a little so the head rests on the folded hands (a pillow)
    c.layer("waist", lambda t: (-8.0, 0.0, 0.0))
    c.layer("chest", lambda t: (-12.0 + 1.4 * math.sin(2 * math.pi * t / L), 0.0, 0.0))
    c.layer("head", lambda t: (-12.0 - 0.8 * math.sin(2 * math.pi * t / L - 0.5), 0.0, 0.0))
    c.layer("head", lambda t: (0.0, 0.0, -1.6), channel="pos")
    c.layer("hip", lambda t: (0.0, 0.0, 0.6 * math.sin(2 * math.pi * t / L)))
    # knee up: the foot stays on the ground, the knee sways sideways
    c.ik("r_leg", lambda t, ctx: (2.6 + 0.5 * math.sin(2 * math.pi * t / L), 1.95, -10.2),
         lambda t, ctx: (0.25 + 0.35 * math.sin(2 * math.pi * t / L), 1.0, 0.0))
    c.layer("l_leg", lambda t: (0.0, 0.0, -4.0))
    c.layer("l_shin", lambda t: (-2.0, 0.0, 0.0))
    # hands behind the head (in the head's own space, so they follow the head)
    c.ik("r_arm", lambda t, ctx: ctx.mine("head", (2.6, 25.0, 4.4)), (1.0, 0.3, 0.4))
    c.ik("l_arm", lambda t, ctx: ctx.mine("head", (-2.6, 25.0, 4.4)), (-1.0, 0.3, 0.4))
    return c


# ---------------------------------------------------------------------------- dances (loops)

def dance_folk():
    """Taniec 1 - ludowy (120 BPM, 8 beats): heel taps with hands on the hips, bouncing on every
    beat, a quarter turn and two claps over the head."""
    beat = 0.5
    L = 8 * beat
    c = Clip("dance_1", L, loop=True)
    # bounce: down on every beat
    c.layer("root", lambda t: (0.0, -0.45 + 0.45 * math.cos(2 * math.pi * t / beat), 0.0), channel="pos")
    c.layer("head", lambda t: (2.0 * math.cos(2 * math.pi * t / beat), 0.0, 0.0))
    zero = (0, 0, 0)
    # heel taps: R (beat 1), together, L (beat 3), together, R, L (faster), turn + claps
    taps = [(0.0, zero, zero, zero, zero),
            (0.25, (30, 0, 2), (-4, 0, 0), (6, 0, 0), (-12, 0, 0)),
            (0.5, zero, zero, zero, zero),
            (0.75, (6, 0, 0), (-12, 0, 0), (30, 0, -2), (-4, 0, 0)),
            (1.0, zero, zero, zero, zero),
            (1.25, (30, 0, 2), (-4, 0, 0), (6, 0, 0), (-12, 0, 0)),
            (1.5, zero, zero, zero, zero),
            (1.75, (6, 0, 0), (-12, 0, 0), (30, 0, -2), (-4, 0, 0)),
            (2.0, zero, zero, zero, zero),
            (2.125, (26, 0, 2), (-4, 0, 0), (5, 0, 0), (-10, 0, 0)),
            (2.25, zero, zero, zero, zero),
            (2.375, (5, 0, 0), (-10, 0, 0), (26, 0, -2), (-4, 0, 0)),
            (2.5, zero, zero, zero, zero),
            (3.0, (8, 0, 3), (-14, 0, 0), (8, 0, -3), (-14, 0, 0)),
            (3.5, zero, zero, zero, zero),
            (4.0, zero, zero, zero, zero)]
    for t, rl, rs, ll, ls in taps:
        c.key(t, "inout", r_leg=rl, r_shin=rs, l_leg=ll, l_shin=ls)
    c.key(0.0, chest=zero, waist=zero, head=zero, root=zero)
    for i, t in enumerate((0.25, 0.75, 1.25, 1.75)):
        s = 1 if i % 2 == 0 else -1
        c.key(t, chest=(-2, s * 9, s * 4), waist=(0, s * 4, 0), head=(0, -s * 6, -s * 7))
        c.key(t + 0.25, chest=zero, waist=zero, head=zero)
    c.key(2.5, root=zero)
    c.key(2.9, "cubic", root=(0, 40, 0), chest=(2, 0, 0))
    c.key(3.6, "cubic", root=(0, 0, 0), chest=(1, 0, 0))
    c.key(4.0, chest=zero)
    hips_r = mine("waist", (5.4, 15.6, 0.6))
    hips_l = mine("waist", (-5.4, 15.6, 0.6))

    def claps(side):
        apart = (side * 2.6, 30.4, -3.2)
        meet = (side * 0.95, 30.8, -3.2)
        return [(2.55, fixed(apart), "inout"), (2.8, fixed(meet), "quad_in"), (3.0, fixed(apart), "out"),
                (3.25, fixed(meet), "quad_in")]
    c.ik("r_arm", path("r_arm", [(0.0, hips_r, "linear"), (2.3, hips_r, "linear")] + claps(1)
                       + [(3.7, hips_r, "inout"), (4.0, hips_r, "linear")]),
         vec_path([(0.0, (1.0, 0.1, 0.4), "linear"), (2.3, (1.0, 0.1, 0.4), "linear"), (2.55, (1.0, -0.3, 0.2), "inout"),
                   (3.3, (1.0, -0.3, 0.2), "linear"), (3.7, (1.0, 0.1, 0.4), "inout"), (4.0, (1.0, 0.1, 0.4), "linear")]))
    c.ik("l_arm", path("l_arm", [(0.0, hips_l, "linear"), (2.3, hips_l, "linear")] + claps(-1)
                       + [(3.7, hips_l, "inout"), (4.0, hips_l, "linear")]),
         vec_path([(0.0, (-1.0, 0.1, 0.4), "linear"), (2.3, (-1.0, 0.1, 0.4), "linear"), (2.55, (-1.0, -0.3, 0.2), "inout"),
                   (3.3, (-1.0, -0.3, 0.2), "linear"), (3.7, (-1.0, 0.1, 0.4), "inout"), (4.0, (-1.0, 0.1, 0.4), "linear")]))
    c.event("impact", 2.8, 3.25)
    return c


def dance_court():
    """Taniec 2 - dworski (slow pavane, 6 s): rise and fall, step-close forward and back, one arm
    offered forward as to a partner while the other rests on the hip, a graceful turn and a small
    reverence; mirrored in the second half."""
    L = 6.0
    c = Clip("dance_2", L, loop=True)
    zero = (0, 0, 0)
    # rise on the toes and settle, twice per half
    c.layer("root", lambda t: (0.0, 0.45 * (1 - math.cos(2 * math.pi * t / 1.5)) / 2 - 0.1, 0.0), channel="pos")
    for half in (0, 1):
        o = half * 3.0
        s = 1 if half == 0 else -1  # which side leads
        lead, lead_s, back, back_s = ("r_leg", "r_shin", "l_leg", "l_shin") if s == 1 else ("l_leg", "l_shin", "r_leg", "r_shin")
        c.key(o + 0.0, **{lead: zero, lead_s: zero, back: zero, back_s: zero})
        c.key(o + 0.6, **{lead: (16, 0, 0), lead_s: (-3, 0, 0), back: (-6, 0, 0), back_s: (-6, 0, 0)})
        c.key(o + 1.2, **{lead: (2, 0, 0), lead_s: (-2, 0, 0), back: (-2, 0, 0), back_s: (-2, 0, 0)})
        c.key(o + 1.8, **{lead: (-12, 0, 0), lead_s: (-6, 0, 0), back: (8, 0, 0), back_s: (-3, 0, 0)})
        c.key(o + 2.4, **{lead: (-4, 0, 0), lead_s: (-14, 0, 0), back: (6, 0, 0), back_s: (-12, 0, 0)})  # reverence
        c.key(o + 3.0, **{lead: zero, lead_s: zero, back: zero, back_s: zero})
        c.key(o + 0.0, root=zero, chest=(1, 0, 0), hip=zero, head=(3, 0, 0))
        c.key(o + 1.5, "inout", root=(0, s * 28, 0), chest=(2, -s * 6, s * 2), head=(4, s * 12, s * 4))
        c.key(o + 2.4, "inout", root=(0, s * 8, 0), hip=(-12, 0, 0), chest=(-2, 0, 0), head=(-6, 0, 0))
        c.key(o + 3.0, "inout", root=zero, hip=zero, chest=(1, 0, 0), head=(3, 0, 0))
    c.key(L, root=zero, hip=zero, chest=(1, 0, 0), head=(3, 0, 0))
    offered_r = mine("chest", (8.6, 21.0, -8.6))
    offered_l = mine("chest", (-8.6, 21.0, -8.6))
    hip_r = mine("waist", (5.4, 15.4, 0.4))
    hip_l = mine("waist", (-5.4, 15.4, 0.4))
    c.ik("r_arm", path("r_arm", [(0.0, offered_r, "linear"), (2.3, offered_r, "linear"), (3.0, hip_r, "inout"),
                                 (5.3, hip_r, "linear"), (6.0, offered_r, "inout")]),
         vec_path([(0.0, (0.6, -1.0, 0.3), "linear"), (2.3, (0.6, -1.0, 0.3), "linear"), (3.0, (1.0, 0.0, 0.4), "inout"),
                   (5.3, (1.0, 0.0, 0.4), "linear"), (6.0, (0.6, -1.0, 0.3), "inout")]))
    c.ik("l_arm", path("l_arm", [(0.0, hip_l, "linear"), (2.3, hip_l, "linear"), (3.0, offered_l, "inout"),
                                 (5.3, offered_l, "linear"), (6.0, hip_l, "inout")]),
         vec_path([(0.0, (-1.0, 0.0, 0.4), "linear"), (2.3, (-1.0, 0.0, 0.4), "linear"), (3.0, (-0.6, -1.0, 0.3), "inout"),
                   (5.3, (-0.6, -1.0, 0.3), "linear"), (6.0, (-1.0, 0.0, 0.4), "inout")]))
    return c


def dance_jig():
    """Taniec 3 - biesiadny / skoczny (150 BPM, 8 beats): hop on every beat with alternating knee
    lifts, arms swinging against the legs, a slight forward lean and a bobbing head."""
    beat = 0.4
    L = 8 * beat
    c = Clip("dance_3", L, loop=True)

    def hop(t):
        u = (t % beat) / beat
        air = math.sin(math.pi * u)            # in the air in the middle of the beat
        land = math.exp(-((u - 0.0) / 0.12) ** 2) + math.exp(-((u - 1.0) / 0.12) ** 2)
        return (0.0, 1.9 * air - 0.6 * land, 0.0)
    c.layer("root", hop, channel="pos")
    c.layer("chest", lambda t: (-6.0, 7.0 * math.sin(2 * math.pi * t / (2 * beat)), 0.0))
    c.layer("waist", lambda t: (-2.0, -3.0 * math.sin(2 * math.pi * t / (2 * beat)), 0.0))
    c.layer("head", lambda t: (5.0 + 3.0 * math.cos(2 * math.pi * t / beat), -5.0 * math.sin(2 * math.pi * t / (2 * beat)),
                               3.0 * math.sin(2 * math.pi * t / (2 * beat))))

    def knee(phase):
        def f(t):
            s = math.sin(2 * math.pi * t / (2 * beat) + phase)
            lift = max(0.0, s) ** 1.5
            return (8.0 + 50.0 * lift, 0.0, 0.0)
        return f

    def shin(phase):
        def f(t):
            s = math.sin(2 * math.pi * t / (2 * beat) + phase)
            lift = max(0.0, s) ** 1.5
            return (-10.0 - 70.0 * lift, 0.0, 0.0)
        return f
    c.layer("r_leg", knee(0.0))
    c.layer("r_shin", shin(0.0))
    c.layer("l_leg", knee(math.pi))
    c.layer("l_shin", shin(math.pi))
    # arms against the legs, elbows bent like holding a mug
    c.layer("r_arm", lambda t: (-30.0 * math.sin(2 * math.pi * t / (2 * beat)) + 10.0, 0.0, 9.0))
    c.layer("l_arm", lambda t: (30.0 * math.sin(2 * math.pi * t / (2 * beat)) + 10.0, 0.0, -9.0))
    c.layer("r_fore", lambda t: (55.0 + 12.0 * math.sin(2 * math.pi * t / (2 * beat) - 0.6), 0.0, 0.0))
    c.layer("l_fore", lambda t: (55.0 - 12.0 * math.sin(2 * math.pi * t / (2 * beat) - 0.6), 0.0, 0.0))
    return c


# ---------------------------------------------------------------------------- emotes (one-shot)

def emote_clap():
    """Klaskanie: hands come up in front of the chest and clap six times, body bouncing lightly."""
    L = 2.7
    c = Clip("emote_klaskanie", L)
    claps = [0.5 + i * 0.32 for i in range(6)]
    env = window(0.35, 0.5, claps[-1], claps[-1] + 0.2)
    c.key(0, chest=(0, 0, 0), head=(0, 0, 0))
    c.key(0.4, chest=(-3, 0, 0), head=(3, 0, 0))
    c.key(claps[-1] + 0.2, chest=(-3, 0, 0), head=(3, 0, 0))
    c.key(L - 0.05, chest=(0, 0, 0), head=(0, 0, 0))
    c.layer("root", lambda t: (0.0, -0.25 * env(t) * (1 - math.cos(2 * math.pi * (t - claps[0]) / 0.32)) / 2, 0.0),
            channel="pos")
    c.layer("head", lambda t: (1.5 * env(t) * math.sin(2 * math.pi * (t - claps[0]) / 0.32), 0.0, 0.0))

    def hands(side):
        def gap(t):
            u = ((t - claps[0]) / 0.32) % 1.0  # 0 at each clap
            return 0.95 + 2.2 * math.sin(math.pi * u) ** 0.8
        meet = lambda ctx, t: ctx.mine("chest", (side * gap(t), 19.6, -6.4))
        return path("r_arm" if side > 0 else "l_arm", [
            (0.0, REST, "linear"),
            (0.45, mine("chest", (side * 3.2, 19.0, -6.0)), "inout"),
            (claps[0], meet, "inout"),
            (claps[-1], meet, "linear"),
            (claps[-1] + 0.3, mine("chest", (side * 6.0, 16.0, -4.5)), "inout"),
            (L - 0.05, REST, "inout"),
        ])
    c.ik("r_arm", hands(1), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.4, (0.8, -1.0, 0.6), "inout"),
                                      (claps[-1], (0.8, -1.0, 0.6), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]),
         ik_window(L))
    c.ik("l_arm", hands(-1), vec_path([(0.0, ARM_REST_POLE_L, "linear"), (0.4, (-0.8, -1.0, 0.6), "inout"),
                                       (claps[-1], (-0.8, -1.0, 0.6), "linear"), (L - 0.05, ARM_REST_POLE_L, "inout")]),
         ik_window(L))
    c.event("impact", *claps)
    return c


def emote_salute():
    """Salut rycerski: stand to attention, right fist to the heart, a slow respectful nod, the left
    hand on the sword hilt at the hip; hold; release."""
    L = 2.9
    c = Clip("emote_salut", L)
    zero = (0, 0, 0)
    c.key(0, chest=zero, head=zero, hip=zero)
    c.key(0.35, "cubic", chest=(3, 0, 0), head=(3, 0, 0), hip=(1, 0, 0))
    c.key(0.75, chest=(1, 0, 0), head=(-20, 0, 0))
    c.key(1.55, chest=(1, 0, 0), head=(-20, 0, 0))
    c.key(1.95, "cubic", chest=(3, 0, 0), head=(3, 0, 0))
    c.key(2.45, hip=(1, 0, 0))
    c.key(L - 0.05, chest=zero, head=zero, hip=zero)
    heart = mine("chest", (-1.2, 20.4, -3.0))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.18, mine("chest", (7.6, 17.0, -4.5)), "inout"),
        (0.42, heart, "cubic"),
        (2.0, heart, "linear"),
        (2.35, mine("chest", (7.6, 16.5, -4.0)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.3, (1.0, -0.4, 0.5), "inout"), (2.1, (1.0, -0.4, 0.5), "linear"),
                  (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    hilt = mine("waist", (-4.9, 13.6, -1.8))
    c.ik("l_arm", path("l_arm", [
        (0.0, REST, "linear"),
        (0.45, hilt, "inout"),
        (2.2, hilt, "linear"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_L, "linear"), (0.4, (-1.0, 0.1, 0.5), "inout"), (2.2, (-1.0, 0.1, 0.5), "linear"),
                  (L - 0.05, ARM_REST_POLE_L, "inout")]), ik_window(L))
    breathe(c, 0.5, 2.8, window(0.0, 0.4, 2.4, 2.9))
    return c


def emote_shrug():
    """Nie wiem: shoulders up, forearms open outward with the palms up, head tilted; then drop."""
    L = 1.9
    c = Clip("emote_nie_wiem", L)
    zero = (0, 0, 0)
    up, down = 0.32, 1.15
    c.key(0, chest=zero, head=zero, waist=zero)
    c.key(up, "back", chest=(4, 0, 0), head=(4, 6, 11), waist=(1, 0, 0))
    c.key(down, chest=(4, 0, 0), head=(3, 4, 9), waist=(1, 0, 0))
    c.key(L - 0.05, chest=zero, head=zero, waist=zero)
    c.keypos(0, r_arm=zero, l_arm=zero)
    c.keypos(up, "back", r_arm=(0, 1.1, 0), l_arm=(0, 1.1, 0))
    c.keypos(down, r_arm=(0, 0.9, 0), l_arm=(0, 0.9, 0))
    c.keypos(L - 0.05, r_arm=zero, l_arm=zero)
    for limb, side, rest_pole in (("r_arm", 1, ARM_REST_POLE_R), ("l_arm", -1, ARM_REST_POLE_L)):
        open_hand = mine("chest", (side * 9.2, 17.6, -4.6))
        c.ik(limb, path(limb, [
            (0.0, REST, "linear"),
            (up, open_hand, "back"),
            (down, offset(open_hand, lambda t: (0.0, -0.4, 0.0)), "inout"),
            (L - 0.05, REST, "inout"),
        ]), vec_path([(0.0, rest_pole, "linear"), (0.2, (side * 0.3, -1.0, 0.6), "inout"),
                      (down, (side * 0.3, -1.0, 0.6), "linear"), (L - 0.05, rest_pole, "inout")]), ik_window(L))
    return c


def emote_facepalm():
    """Facepalm: the right hand rises to the face while the head drops into it, a slow
    disappointed head shake, the left hand on the hip; release."""
    L = 2.7
    c = Clip("emote_facepalm", L)
    zero = (0, 0, 0)
    c.key(0, chest=zero, head=zero, waist=zero)
    c.key(0.42, "cubic", chest=(-6, 0, 0), waist=(-2, 0, 0), head=(-19, 0, 2))
    c.key(0.8, head=(-19, 0, 2))
    c.key(1.05, head=(-19, 7, 2))
    c.key(1.35, head=(-19, -7, 2))
    c.key(1.6, head=(-19, 5, 2))
    c.key(1.85, head=(-18, 0, 2), chest=(-6, 0, 0), waist=(-2, 0, 0))
    c.key(2.4, "cubic", chest=zero, waist=zero, head=(2, 0, 0))
    c.key(L, head=zero)
    face = mine("head", (0.8, 25.6, -5.6))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.2, mine("chest", (7.5, 18.0, -5.0)), "inout"),
        (0.45, face, "cubic"),
        (1.9, face, "linear"),
        (2.25, mine("chest", (7.0, 16.5, -4.0)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.3, (0.7, -1.0, 0.2), "inout"), (1.9, (0.7, -1.0, 0.2), "linear"),
                  (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    hip_l = mine("waist", (-5.3, 15.4, 0.4))
    c.ik("l_arm", path("l_arm", [
        (0.0, REST, "linear"),
        (0.5, hip_l, "inout"),
        (2.0, hip_l, "linear"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_L, "linear"), (0.4, (-1.0, 0.0, 0.4), "inout"), (2.0, (-1.0, 0.0, 0.4), "linear"),
                  (L - 0.05, ARM_REST_POLE_L, "inout")]), ik_window(L))
    breathe(c, 0.6, 2.7, window(0.0, 0.4, 2.2, 2.7))
    return c


# ---------------------------------------------------------------------------- emotes: devotion, court, tavern

def emote_prayer():
    """Modlitwa (loop): standing, palms together at the chest, head bowed, slow deep breathing and
    a barely visible sway; moving / sneaking ends it."""
    L = 5.0
    c = Clip("emote_modlitwa", L, loop=True, blend=0.4)
    c.layer("head", lambda t: (-21.0 - 1.5 * math.sin(2 * math.pi * t / L), 0.0, 1.0 * math.sin(2 * math.pi * t / L + 0.8)))
    c.layer("chest", lambda t: (-5.0, 0.0, -0.4 * math.sin(2 * math.pi * t / L + 0.8)))
    c.layer("waist", lambda t: (-2.0, 0.0, 0.0))
    c.layer("hip", lambda t: (0.0, 0.0, 0.7 * math.sin(2 * math.pi * t / L)))
    breathe(c, 1.2, L)
    for limb, side in (("r_arm", 1.0), ("l_arm", -1.0)):
        c.ik(limb, (lambda s: lambda t, ctx: ctx.mine("chest", (s * 0.75, 20.2 + 0.15 * math.sin(2 * math.pi * t / L), -4.5)))(side),
             (side * 0.7, -1.0, 0.45))
    return c


def emote_kneel():
    """Klęczenie (loop): on the left knee as before a knighting, the right foot flat in front, the
    right hand resting on the raised knee, head bowed."""
    L = 4.5
    drop = 3.75  # hips come down so the kneeling thigh is vertical and the knee touches the ground
    c = Clip("emote_kleczenie", L, loop=True, blend=0.6)
    c.layer("root", lambda t: (0.0, -drop, 0.4), channel="pos")
    c.layer("l_leg", lambda t: (-2.0, 0.0, -3.0))                     # kneeling thigh ~vertical
    c.layer("l_shin", lambda t: (-88.0, 0.0, 0.0))                    # shin flat on the ground behind
    c.layer("r_leg", lambda t: (70.0, 0.0, 4.0))                      # front thigh forward
    c.layer("r_shin", lambda t: (-70.0, 0.0, 0.0))                    # front shin vertical, sole on the ground
    c.layer("hip", lambda t: (-6.0, 0.0, 0.6 * math.sin(2 * math.pi * t / L)))
    c.layer("chest", lambda t: (-7.0, 0.0, -0.3 * math.sin(2 * math.pi * t / L)))
    c.layer("head", lambda t: (-17.0 - 1.0 * math.sin(2 * math.pi * t / L - 0.4), 0.0, 0.0))
    c.layer("l_arm", lambda t: (6.0 + 1.0 * math.sin(2 * math.pi * t / L), 0.0, -4.0))
    c.layer("l_fore", lambda t: (10.0, 0.0, 0.0))
    breathe(c, 1.0, L)
    c.ik("r_arm", lambda t, ctx: add(joint("r_leg", (0.3, 2.1, 0.2))(ctx, t),
                                     (0.0, 0.1 * math.sin(2 * math.pi * t / L), 0.0)), (0.6, -0.6, 1.0))
    return c


def emote_deep_bow():
    """Pokłon (once, 2.8 s): a deep reverence before a ruler - the right foot slides back, the right
    hand goes to the heart, the torso bends far from the hips while the left arm opens wide to the
    side, a held moment, then a slow rise with the head coming up last."""
    L = 2.8
    zero = (0, 0, 0)
    c = Clip("emote_poklon", L)
    c.key(0, r_leg=zero, r_shin=zero, l_leg=zero, l_shin=zero, hip=zero, waist=zero, chest=zero, head=zero,
          l_arm=zero, l_fore=zero)
    c.key(0.4, r_leg=(-15, 0, 2), r_shin=(-10, 0, 0), l_leg=(3, 0, 0), l_shin=(-5, 0, 0),
          l_arm=(-12, 0, -48), l_fore=(10, 0, 0), chest=(2, 0, 0), head=(2, 0, 0))
    c.key(1.2, "cubic", hip=(-58, 0, 0), waist=(-14, 0, 0), chest=(-8, 0, 0), r_shin=(-18, 0, 0),
          l_shin=(-15, 0, 0), l_leg=(10, 0, 0))
    c.key(1.3, "cubic", head=(-10, 0, 0), l_arm=(42, 0, -55), l_fore=(8, 0, 0))
    c.key(1.8, hip=(-58, 0, 0), waist=(-14, 0, 0), chest=(-8, 0, 0), head=(-12, 0, 0), l_arm=(42, 0, -52),
          l_fore=(8, 0, 0), r_shin=(-18, 0, 0), l_shin=(-15, 0, 0), l_leg=(10, 0, 0))
    c.key(2.45, "cubic", hip=zero, waist=zero, chest=(1, 0, 0), r_leg=(-8, 0, 1), r_shin=(-6, 0, 0),
          l_shin=(-3, 0, 0), l_leg=(2, 0, 0), l_arm=(-6, 0, -18), l_fore=(5, 0, 0))
    c.key(2.6, head=(2, 0, 0))
    c.key(L - 0.05, r_leg=zero, r_shin=zero, l_leg=zero, l_shin=zero, chest=zero, head=zero, l_arm=zero,
          l_fore=zero)
    c.keypos(0, root=zero)
    c.keypos(0.4, root=(0, -0.2, 0.6))
    c.keypos(1.2, "cubic", root=(0, -0.8, 1.7))
    c.keypos(1.8, root=(0, -0.8, 1.7))
    c.keypos(2.45, "cubic", root=(0, -0.1, 0.4))
    c.keypos(L - 0.05, root=zero)
    breathe(c, 0.5, 2.8, window(0.0, 0.4, 2.3, 2.8))
    heart = mine("chest", (-1.3, 20.6, -2.9))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.18, mine("chest", (7.5, 16.5, -4.0)), "inout"),
        (0.45, heart, "inout"),
        (2.3, heart, "linear"),
        (2.6, mine("chest", (7.0, 15.5, -3.0)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.3, (1.0, -0.6, 0.4), "inout"),
                  (2.35, (1.0, -0.6, 0.4), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    return c


def emote_sword_salute():
    """Salut mieczem (once, 2.8 s): the right hand comes up so the held item (a sword, if any) stands
    upright before the face, a short nod, then the blade sweeps down and out to the right, a held
    moment, and back. Works with an empty hand too (the fist does the same motion)."""
    L = 2.8
    zero = (0, 0, 0)
    c = Clip("emote_salut_miecz", L)
    up = (28, 14, 4)          # upper arm forward / in: the hilt in front of the chest, the blade up before the face
    up_fore = (62, 0, 0)      # forearm ~horizontal -> a vanilla-held sword points straight up
    low = (32, -12, 18)       # blade lowered forward-down and out to the right
    low_fore = (8, 0, 0)
    c.key(0, r_arm=zero, r_fore=zero, chest=zero, head=zero, l_arm=zero, hip=zero)
    c.key(0.5, "cubic", r_arm=up, r_fore=up_fore, chest=(3, 0, 0), head=(4, 0, 0), l_arm=(-3, 0, -2))
    c.key(0.65, "back", r_arm=(up[0] + 2, up[1], up[2]), r_fore=up_fore)
    c.key(1.15, r_arm=up, r_fore=up_fore, chest=(3, 0, 0), head=(4, 0, 0))
    c.key(1.3, head=(-9, 0, 0))
    c.key(1.6, "cubic", r_arm=low, r_fore=low_fore, chest=(1, -4, 0), head=(-2, 0, 0))
    c.key(2.1, r_arm=low, r_fore=low_fore, chest=(1, -4, 0), head=(0, 0, 0), l_arm=(-3, 0, -2))
    c.key(L - 0.05, "inout", r_arm=zero, r_fore=zero, chest=zero, head=zero, l_arm=zero, hip=zero)
    c.key(0.5, hip=(1, 0, 0))
    c.key(2.1, hip=(1, 0, 0))
    c.keypos(0, r_leg=zero, l_leg=zero)
    c.key(0, r_leg=zero, l_leg=zero)
    c.key(0.35, r_leg=(0, 0, -1.5), l_leg=(0, 0, 1.5))  # heels together, standing to attention
    c.key(2.3, r_leg=(0, 0, -1.5), l_leg=(0, 0, 1.5))
    c.key(L - 0.05, r_leg=zero, l_leg=zero)
    breathe(c, 0.5, 2.8, window(0.0, 0.4, 2.3, 2.8))
    c.event("impact", 0.5)
    return c


def emote_tankard():
    """Kufel (once, 3.2 s): raise the right hand to the mouth, tip the head back and take three gulps,
    lower the tankard to the chest and wipe the mouth with the back of the left hand."""
    L = 3.2
    zero = (0, 0, 0)
    c = Clip("emote_kufel", L)
    gulps = (0.85, 1.18, 1.51)
    c.key(0, head=zero, chest=zero, waist=zero)
    c.key(0.45, head=(-2, 0, 0))
    c.key(0.8, "cubic", head=(22, 0, 0), chest=(5, 0, 0), waist=(2, 0, 0))
    c.key(1.7, head=(22, 0, 0), chest=(5, 0, 0), waist=(2, 0, 0))
    c.key(2.05, "cubic", head=(-3, 0, 0), chest=(-3, 0, 0), waist=(0, 0, 0))  # "ahh" - breathing out, leaning in
    c.key(2.5, head=(-4, -6, 0), chest=(-2, 0, 0))
    c.key(L - 0.05, head=zero, chest=zero, waist=zero)
    gulp_env = window(0.8, 0.9, 1.6, 1.72)
    c.layer("head", lambda t: (1.8 * gulp_env(t) * math.sin(2 * math.pi * (t - gulps[0]) / 0.33), 0.0, 0.0))
    mouth = mine("head", (0.6, 24.3, -5.4))
    c.ik("r_arm", path("r_arm", [
        (0.0, REST, "linear"),
        (0.25, mine("chest", (7.0, 17.0, -5.0)), "inout"),
        (0.6, mouth, "cubic"),
        (1.72, mouth, "linear"),
        (2.05, mine("chest", (5.8, 16.8, -5.4)), "inout"),
        (2.65, mine("chest", (5.8, 16.4, -5.2)), "linear"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_R, "linear"), (0.3, (1.0, -1.0, 0.2), "inout"),
                  (2.6, (1.0, -1.0, 0.2), "linear"), (L - 0.05, ARM_REST_POLE_R, "inout")]), ik_window(L))
    c.ik("l_arm", path("l_arm", [
        (0.0, REST, "linear"),
        (1.95, REST, "linear"),
        (2.15, mine("chest", (-6.5, 18.5, -5.0)), "inout"),
        (2.32, mine("head", (-3.0, 23.5, -5.2)), "inout"),
        (2.52, mine("head", (2.6, 23.2, -5.0)), "cubic"),      # the wipe across the mouth
        (2.7, mine("chest", (-4.0, 17.5, -5.0)), "inout"),
        (L - 0.05, REST, "inout"),
    ]), vec_path([(0.0, ARM_REST_POLE_L, "linear"), (1.95, ARM_REST_POLE_L, "linear"), (2.15, (-1.0, -0.8, 0.2), "inout"),
                  (2.7, (-1.0, -0.8, 0.2), "linear"), (L - 0.05, ARM_REST_POLE_L, "inout")]), ik_window(L))
    breathe(c, 0.6, 3.2, window(0.0, 0.4, 2.7, 3.2))
    c.event("impact", *gulps)
    return c


# ---------------------------------------------------------------------------- catalogue

def build():
    """Every clip, with two-person clips paired."""
    out = []
    a, b = hug("a"), hug("b")
    Pair(a, b, Pair.FACE, 0.6)
    out += [a, b]
    a, b = kiss("a"), kiss("b")
    Pair(a, b, Pair.FACE, 0.6)
    out += [a, b]
    a, b = hold("right"), hold("left")
    Pair(a, b, Pair.SIDE_RIGHT, 0.8)
    out += [a, b]
    a, b = high_five("a"), high_five("b")
    Pair(a, b, Pair.FACE, 0.9)
    out += [a, b]
    a, b = pat("a"), pat("b")
    Pair(a, b, Pair.FACE, 0.7)
    out += [a, b]
    a, b = slap_a(), slap_b()
    Pair(a, b, Pair.FACE, 0.85)
    out += [a, b]
    out += [wave_clip(), bow_clip(), sit_floor(), sit_chair(), lay_back(),
            dance_folk(), dance_court(), dance_jig(),
            emote_clap(), emote_salute(), emote_shrug(), emote_facepalm(),
            emote_prayer(), emote_kneel(), emote_deep_bow(), emote_sword_salute(), emote_tankard()]
    return out


# Plugin-facing catalogue (written to animations.json). Times in seconds; distance in blocks.
#   type: pair | solo | pose;  arrangement: face | side | look | none
#   roles: which clip each participant plays; lock: moving / sneaking ends the animation
ANIMATIONS = {
    "hug": {"type": "pair", "arrangement": "face", "distance": 0.6,
            "roles": {"sender": {"clip": "hug_a", "lock": True}, "target": {"clip": "hug_b", "lock": True}}},
    "kiss": {"type": "pair", "arrangement": "face", "distance": 0.6,
             "roles": {"sender": {"clip": "kiss_a", "lock": True}, "target": {"clip": "kiss_b", "lock": True}}},
    "hold": {"type": "pair", "arrangement": "side", "distance": 0.8,
             "roles": {"sender": {"clip": "hold_right", "lock": True}, "target": {"clip": "hold_left", "lock": True}},
             "mirror": {"sender": "hold_left", "target": "hold_right"}},
    "highfive": {"type": "pair", "arrangement": "face", "distance": 0.9,
                 "roles": {"sender": {"clip": "highfive_a", "lock": True}, "target": {"clip": "highfive_b", "lock": True}}},
    "pat": {"type": "pair", "arrangement": "face", "distance": 0.7,
            "roles": {"sender": {"clip": "pat_a", "lock": True}, "target": {"clip": "pat_b", "lock": True}}},
    "slap": {"type": "pair", "arrangement": "face", "distance": 0.85,
             "roles": {"sender": {"clip": "slap_a", "lock": True}, "target": {"clip": "slap_b", "lock": False}}},
    "wave": {"type": "solo", "arrangement": "look", "roles": {"sender": {"clip": "wave", "lock": True}}},
    "bow": {"type": "solo", "arrangement": "look", "roles": {"sender": {"clip": "bow", "lock": True}}},
    "sit_floor": {"type": "pose", "arrangement": "none", "roles": {"sender": {"clip": "sit_floor", "lock": False}}},
    "sit_chair": {"type": "pose", "arrangement": "none", "roles": {"sender": {"clip": "sit_chair", "lock": False}}},
    "lay": {"type": "pose", "arrangement": "none", "roles": {"sender": {"clip": "lay_back", "lock": False}}},
    "dance_1": {"type": "solo", "arrangement": "none", "roles": {"sender": {"clip": "dance_1", "lock": True}}},
    "dance_2": {"type": "solo", "arrangement": "none", "roles": {"sender": {"clip": "dance_2", "lock": True}}},
    "dance_3": {"type": "solo", "arrangement": "none", "roles": {"sender": {"clip": "dance_3", "lock": True}}},
    "klaskanie": {"type": "solo", "arrangement": "none", "roles": {"sender": {"clip": "emote_klaskanie", "lock": True}}},
    "salut": {"type": "solo", "arrangement": "look", "roles": {"sender": {"clip": "emote_salut", "lock": True}}},
    "nie_wiem": {"type": "solo", "arrangement": "none", "roles": {"sender": {"clip": "emote_nie_wiem", "lock": True}}},
    "facepalm": {"type": "solo", "arrangement": "none", "roles": {"sender": {"clip": "emote_facepalm", "lock": True}}},
    "modlitwa": {"type": "solo", "arrangement": "look", "roles": {"sender": {"clip": "emote_modlitwa", "lock": True}}},
    "kleczenie": {"type": "solo", "arrangement": "look", "roles": {"sender": {"clip": "emote_kleczenie", "lock": True}}},
    "poklon": {"type": "solo", "arrangement": "look", "roles": {"sender": {"clip": "emote_poklon", "lock": True}}},
    "salut_miecz": {"type": "solo", "arrangement": "look", "roles": {"sender": {"clip": "emote_salut_miecz", "lock": True}}},
    "kufel": {"type": "solo", "arrangement": "none", "roles": {"sender": {"clip": "emote_kufel", "lock": True}}},
}


# ---------------------------------------------------------------------------- everything

def build_all(rig):
    """Player interactions, ceremonies and NPC work clips (the generator and the preview use this)."""
    import clips_ceremony
    import clips_npc
    return build() + clips_ceremony.build(rig) + clips_npc.build()


def all_animations():
    import clips_ceremony
    out = dict(ANIMATIONS)
    out.update(clips_ceremony.ANIMATIONS)
    return out


def work_clips():
    """WorkAnimations API names -> clip names (NPC work animations)."""
    import clips_npc
    return dict(clips_npc.WORK)
