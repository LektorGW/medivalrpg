# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Keyed tracks with easing, additive procedural layers, IK and sampling into Blockbench keyframes."""
import math

from rig import ANIMATED, LIMBS, Transform, add, euler, length, lerp3, mat_vec, rot_y, sub, unwrap

FPS = 20  # one sample per server tick
ROT_TOLERANCE = 0.2   # degrees - keyframe simplification tolerance
POS_TOLERANCE = 0.03  # pixels
ZERO = (0.0, 0.0, 0.0)


# ---------------------------------------------------------------------------- easing

def _sine_inout(u):
    return 0.5 - 0.5 * math.cos(math.pi * u)


def _cubic_inout(u):
    return 4 * u * u * u if u < 0.5 else 1 - (-2 * u + 2) ** 3 / 2


def _quart_out(u):
    return 1 - (1 - u) ** 4


def _quad_in(u):
    return u * u


def _cubic_in(u):
    return u * u * u


def _back_out(u, s=1.6):
    u -= 1
    return u * u * ((s + 1) * u + s) + 1


def _settle(u):
    """Out with a small overshoot that settles (damped), for follow-through."""
    return 1 - math.exp(-6 * u) * math.cos(9 * u) * (1 - u)


EASE = {
    "linear": lambda u: u,
    "inout": _sine_inout,          # default: soft start and stop
    "in": lambda u: 1 - math.cos(math.pi * u / 2),
    "out": lambda u: math.sin(math.pi * u / 2),
    "cubic": _cubic_inout,         # stronger acceleration / deceleration
    "quart_out": _quart_out,       # fast start, long soft landing (strikes, reactions)
    "quad_in": _quad_in,           # accelerating (wind-up release)
    "cubic_in": _cubic_in,
    "back": _back_out,             # overshoot then settle
    "settle": _settle,
}


class Channel:
    """Vector track: keys (time, value, ease used to arrive at this key)."""

    def __init__(self):
        self.keys = []

    def key(self, t, value, ease="inout"):
        if ease not in EASE:
            raise ValueError("unknown easing " + ease)
        value = tuple(float(v) for v in value)
        self.keys = [k for k in self.keys if abs(k[0] - t) > 1e-9]
        self.keys.append((float(t), value, ease))
        self.keys.sort(key=lambda k: k[0])

    def eval(self, t):
        if not self.keys:
            return ZERO
        if t <= self.keys[0][0]:
            return self.keys[0][1]
        for (t0, v0, _e0), (t1, v1, e1) in zip(self.keys, self.keys[1:]):
            if t <= t1:
                u = 0.0 if t1 - t0 < 1e-9 else (t - t0) / (t1 - t0)
                return lerp3(v0, v1, EASE[e1](u))
        return self.keys[-1][1]


def wave(amplitude, period, phase=0.0, offset=0.0):
    """sin wave helper: returns f(t) -> amplitude * sin(2 pi (t / period + phase)) + offset."""
    return lambda t: amplitude * math.sin(2 * math.pi * (t / period + phase)) + offset


def window(t0, t1, t2, t3):
    """0 before t0, eased up to 1 at t1, 1 until t2, eased down to 0 at t3."""
    def f(t):
        if t <= t0 or t >= t3:
            return 0.0
        if t < t1:
            return _sine_inout((t - t0) / (t1 - t0))
        if t <= t2:
            return 1.0
        return 1.0 - _sine_inout((t - t2) / (t3 - t2))
    return f


# ---------------------------------------------------------------------------- clips

class IKSpec:
    def __init__(self, limb, target, pole, weight):
        self.limb = limb
        self.target = target    # f(t, ctx) -> model-space point
        self.pole = pole        # model-space direction, or f(t, ctx) -> direction
        self.weight = weight    # f(t) -> 0..1 (blend between keyed FK and IK)


class Clip:
    """One animation. Keys are absolute; layers are added on top; IK overrides limbs (blended)."""

    def __init__(self, name, length, loop=False, model="interakcje", blend=None):
        self.name = name
        self.length = float(length)
        self.loop = loop
        self.model = model
        self.blend = blend  # seconds of blending into the clip (loops that start far from standing), None = default
        self.rot = {}
        self.pos = {}
        self.layers = []
        self.iks = []
        self.events = {}
        self.pair = None  # (Pair, role) when part of a two-person animation
        self.diag = {}    # limb -> (worst IK error in px, time), filled by evaluate()

    # -------------------------------------------------------------- authoring

    def key(self, t, ease="inout", **bones):
        """Rotation keys: key(0.5, hip=(-20, 0, 0), head=(10, 0, 0))."""
        for bone, value in bones.items():
            self._check_bone(bone)
            self.rot.setdefault(bone, Channel()).key(t, value, ease)
        return self

    def keypos(self, t, ease="inout", **bones):
        for bone, value in bones.items():
            self._check_bone(bone)
            self.pos.setdefault(bone, Channel()).key(t, value, ease)
        return self

    def rest(self, t, ease="inout", bones=None, pos_bones=None):
        """Key the neutral pose for the given bones (default: everything already keyed)."""
        for bone in (bones if bones is not None else list(self.rot)):
            self.key(t, ease, **{bone: ZERO})
        for bone in (pos_bones if pos_bones is not None else list(self.pos)):
            self.keypos(t, ease, **{bone: ZERO})
        return self

    def layer(self, bone, fn, channel="rot"):
        """Additive procedural motion: fn(t) -> (x, y, z)."""
        self._check_bone(bone)
        self.layers.append((bone, channel, fn))
        return self

    def ik(self, limb, target, pole, weight=None):
        if limb not in LIMBS:
            raise ValueError("unknown limb " + limb)
        self.iks.append(IKSpec(limb, target, pole, weight or (lambda t: 1.0)))
        return self

    def event(self, name, *times):
        self.events.setdefault(name, []).extend(float(x) for x in times)
        return self

    @staticmethod
    def _check_bone(bone):
        if bone not in ANIMATED:
            raise ValueError("bone %s cannot be animated" % bone)

    # -------------------------------------------------------------- evaluation

    def times(self):
        n = int(round(self.length * FPS))
        return [i / FPS for i in range(n + 1)]

    def base_pose(self, t):
        pose = {}
        for bone in ANIMATED:
            r = self.rot[bone].eval(t) if bone in self.rot else ZERO
            p = self.pos[bone].eval(t) if bone in self.pos else ZERO
            pose[bone] = {"rot": r, "pos": p}
        for bone, channel, fn in self.layers:
            pose[bone][channel] = add(pose[bone][channel], tuple(fn(t)))
        return pose


class Pair:
    """Two clips played together. partner_to_me maps the partner's model space into mine."""

    FACE = "face"
    SIDE_RIGHT = "side_right"  # the partner stands on my right, facing the same way

    def __init__(self, a, b, arrangement, distance_blocks):
        self.a = a
        self.b = b
        self.arrangement = arrangement
        self.distance = distance_blocks
        a.pair = (self, "a")
        b.pair = (self, "b")

    def partner_to_me(self, role):
        return Pair.partner_to_me_static(self.arrangement, role, self.distance)

    @staticmethod
    def partner_to_me_static(arrangement, role, distance):
        d = distance * 16.0
        if arrangement == Pair.FACE:
            # both directions are the same transform: turn 180 degrees, step d forward (-Z)
            return Transform(rot_y(180.0), (0.0, 0.0, -d))
        if role == "a":  # b is on a's right (+X)
            return Transform(t=(d, 0.0, 0.0))
        return Transform(t=(-d, 0.0, 0.0))


class Context:
    """What IK target functions can ask for at time t."""

    def __init__(self, rig, clip, world, partner_world):
        self.rig = rig
        self.clip = clip
        self.world = world                  # my FK (keyed pose, before IK)
        self.partner_world = partner_world  # partner FK mapped into my space (or None)

    def mine(self, bone, point):
        return self.world[bone].apply(point)

    def partner(self, bone, point):
        if self.partner_world is None:
            raise ValueError(self.clip.name + ": IK target uses the partner, but the clip is solo")
        return self.partner_world[bone].apply(point)

    def keyed_end(self, limb):
        """Where the hand / foot would be without IK."""
        return self.rig.end_effector(self.world, limb)


def _map_world(world, transform):
    return {b: transform.then(w) for b, w in world.items()}


def evaluate(rig, clip):
    """Samples the clip: returns [(t, pose)] with IK applied, one sample per tick."""
    partner = None
    mapping = None
    if clip.pair is not None:
        pair, role = clip.pair
        partner = pair.b if role == "a" else pair.a
        mapping = pair.partner_to_me(role)
    out = []
    prev_upper = {}
    clip.diag = {}
    for t in clip.times():
        pose = clip.base_pose(t)
        if clip.iks:
            world = rig.fk(pose)
            partner_world = None
            if partner is not None:
                tp = min(t, partner.length)
                partner_world = _map_world(rig.fk(partner.base_pose(tp)), mapping)
            ctx = Context(rig, clip, world, partner_world)
            for spec in clip.iks:
                w = max(0.0, min(1.0, spec.weight(t)))
                if w <= 0.0:
                    continue
                upper, lower, _l, _s = LIMBS[spec.limb]
                target = spec.target(t, ctx)
                pole = spec.pole(t, ctx) if callable(spec.pole) else spec.pole
                prev = prev_upper.get(spec.limb, pose[upper]["rot"])
                up_rot, low_rot, _ok = rig.solve_limb(world, spec.limb, target, pole, prev=prev,
                                                      upper_pos=pose[upper]["pos"])
                prev_upper[spec.limb] = up_rot
                pose[upper]["rot"] = lerp_angles(pose[upper]["rot"], up_rot, w)
                pose[lower]["rot"] = lerp_angles(pose[lower]["rot"], low_rot, w)
                world = rig.fk(pose)
                ctx.world = world
                if w >= 0.999:
                    err = length(sub(rig.end_effector(world, spec.limb), target))
                    worst = clip.diag.get(spec.limb)
                    if worst is None or err > worst[0]:
                        clip.diag[spec.limb] = (err, t)
        out.append((t, pose))
    return out


def lerp_angles(a, b, u):
    b = tuple(unwrap(bv, av) for av, bv in zip(a, b))
    return lerp3(a, b, u)


# ---------------------------------------------------------------------------- keyframes

def _rdp(points, tol):
    """Ramer-Douglas-Peucker on [(t, (x, y, z))] with linear interpolation in time."""
    if len(points) <= 2:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        i0, i1 = stack.pop()
        t0, v0 = points[i0]
        t1, v1 = points[i1]
        worst, worst_i = -1.0, -1
        for i in range(i0 + 1, i1):
            t, v = points[i]
            u = (t - t0) / (t1 - t0)
            interp = lerp3(v0, v1, u)
            err = max(abs(interp[k] - v[k]) for k in range(3))
            if err > worst:
                worst, worst_i = err, i
        if worst > tol:
            keep[worst_i] = True
            stack.append((i0, worst_i))
            stack.append((worst_i, i1))
    return [p for p, k in zip(points, keep) if k]


def to_keyframes(samples):
    """samples [(t, pose)] -> {bone: {'rotation': [(t, v)], 'position': [(t, v)]}} (simplified)."""
    out = {}
    for bone in ANIMATED:
        channels = {}
        for channel, key, tol in (("rotation", "rot", ROT_TOLERANCE), ("position", "pos", POS_TOLERANCE)):
            pts = [(round(t, 4), tuple(round(v, 3) for v in pose[bone][key])) for t, pose in samples]
            if all(max(abs(c) for c in v) < tol for _t, v in pts):
                continue  # never leaves the rest pose
            channels[channel] = _rdp(pts, tol)
        if channels:
            out[bone] = channels
    return out


def hand_path_world(rig, samples, limb):
    """Model-space end effector positions over time (for checks and previews)."""
    return [(t, rig.end_effector(rig.fk(pose), limb)) for t, pose in samples]


__all__ = ["Clip", "Pair", "Channel", "EASE", "FPS", "wave", "window", "evaluate", "to_keyframes",
           "hand_path_world", "euler", "mat_vec"]
