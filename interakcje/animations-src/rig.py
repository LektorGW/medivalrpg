# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Player rig of the BetterModel limb model: bone hierarchy, forward kinematics and 2-bone IK.

Coordinates are Blockbench model space in pixels (16 px = 1 block): the character faces -Z,
its right side is +X, up is +Y, the feet stand at y = 0.

Rotation convention (Blockbench 5 "free" format, which BetterModel reads as BLOCKBENCH_5):
keyframe (x, y, z) in degrees is the three.js Euler with order ZYX, i.e. R = Rz(z) * Ry(y) * Rx(x)
applied to column vectors (X first). Consequences used everywhere in the clips:
  * X > 0 swings a hanging limb forward; X < 0 bows an upright bone (torso, head) forward.
  * Y > 0 turns a bone to the character's left (counter-clockwise seen from above).
  * Z > 0 tilts an upright bone to the character's left; it raises the RIGHT arm sideways
    (and lowers the left one), so a left arm is raised sideways with Z < 0.
  * Position keyframes translate the bone (and its children) in its parent's space, in pixels.
"""
import json
import math
import os

# short bone id -> (group name in the .bbmodel, parent id)
BONES = {
    "root": ("player_root", None),
    "shadow": ("shadow", "root"),
    "hip": ("phip_hip", "root"),
    "waist": ("pw_waist", "hip"),
    "chest": ("pc_chest", "waist"),
    "cape": ("cape_cape", "chest"),
    "head": ("h_ph_head", "chest"),
    "r_arm": ("pra_right_arm", "chest"),
    "r_fore": ("prfa_right_forearm", "r_arm"),
    "r_item": ("pri_right_item", "r_fore"),
    "l_arm": ("pla_left_arm", "chest"),
    "l_fore": ("plfa_left_forearm", "l_arm"),
    "l_item": ("pli_left_item", "l_fore"),
    "r_leg": ("prl_right_leg", "root"),
    "r_shin": ("prfl_right_foreleg", "r_leg"),
    "l_leg": ("pll_left_leg", "root"),
    "l_shin": ("plfl_left_foreleg", "l_leg"),
    "tag": ("tag_name", "root"),
}

# bones that clips animate (the rest only follows its parent)
ANIMATED = ["root", "hip", "waist", "chest", "head", "cape",
            "r_arm", "r_fore", "l_arm", "l_fore", "r_leg", "r_shin", "l_leg", "l_shin"]

# limb chains for IK: upper bone, lower bone, length of the lower segment to the end effector,
# hinge sign (+1: the lower bone bends forward like an elbow, -1: backwards like a knee)
LIMBS = {
    "r_arm": ("r_arm", "r_fore", 5.0, +1),
    "l_arm": ("l_arm", "l_fore", 5.0, +1),
    "r_leg": ("r_leg", "r_shin", 5.2, -1),
    "l_leg": ("l_leg", "l_shin", 5.2, -1),
}

BASE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "base", "steve_base.bbmodel")


# ---------------------------------------------------------------------------- vector math

def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def mul(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def length(a):
    return math.sqrt(dot(a, a))


def norm(a):
    n = length(a)
    if n < 1e-9:
        return (0.0, 0.0, 0.0)
    return (a[0] / n, a[1] / n, a[2] / n)


def lerp3(a, b, u):
    return (a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u)


def mat_mul(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)) for i in range(3))


def mat_vec(m, v):
    return (m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2])


def transpose(m):
    return tuple(tuple(m[j][i] for j in range(3)) for i in range(3))


def rot_x(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return ((1, 0, 0), (0, c, -s), (0, s, c))


def rot_y(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return ((c, 0, s), (0, 1, 0), (-s, 0, c))


def rot_z(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return ((c, -s, 0), (s, c, 0), (0, 0, 1))


def euler(rot):
    """Blockbench Euler (degrees, order ZYX) -> rotation matrix."""
    return mat_mul(rot_z(rot[2]), mat_mul(rot_y(rot[1]), rot_x(rot[0])))


def to_euler(m):
    """Rotation matrix -> Blockbench Euler (degrees) with R = Rz * Ry * Rx."""
    sy = max(-1.0, min(1.0, -m[2][0]))
    y = math.asin(sy)
    if abs(sy) < 0.99999:
        x = math.atan2(m[2][1], m[2][2])
        z = math.atan2(m[1][0], m[0][0])
    else:  # gimbal lock: put everything into x
        x = math.atan2(-m[1][2], m[1][1])
        z = 0.0
    return (math.degrees(x), math.degrees(y), math.degrees(z))


class Transform:
    """p -> R p + t (bone space -> model space)."""

    __slots__ = ("r", "t")

    def __init__(self, r=((1, 0, 0), (0, 1, 0), (0, 0, 1)), t=(0.0, 0.0, 0.0)):
        self.r = r
        self.t = t

    def apply(self, p):
        return add(mat_vec(self.r, p), self.t)

    def apply_dir(self, d):
        return mat_vec(self.r, d)

    def inverse_apply(self, p):
        return mat_vec(transpose(self.r), sub(p, self.t))

    def inverse_dir(self, d):
        return mat_vec(transpose(self.r), d)

    def then(self, child):
        """self * child."""
        return Transform(mat_mul(self.r, child.r), add(mat_vec(self.r, child.t), self.t))


def mirror_x(p):
    return (-p[0], p[1], p[2])


# ---------------------------------------------------------------------------- rig

class Rig:
    def __init__(self, base_file=BASE_FILE):
        with open(base_file, encoding="utf-8") as f:
            self.base = json.load(f)
        origins = {g["name"]: tuple(float(v) for v in g["origin"]) for g in self.base["groups"]}
        self.pivot = {}
        for bone, (group, _parent) in BONES.items():
            if group not in origins:
                raise ValueError("base model has no group " + group)
            self.pivot[bone] = origins[group]
        self.order = []  # parents before children
        pending = list(BONES)
        while pending:
            for b in list(pending):
                p = BONES[b][1]
                if p is None or p in self.order:
                    self.order.append(b)
                    pending.remove(b)

    def local(self, bone, rot, pos):
        """T(pivot + pos) * R * T(-pivot)."""
        piv = self.pivot[bone]
        r = euler(rot)
        return Transform(r, sub(add(piv, pos), mat_vec(r, piv)))

    def fk(self, pose, only=None):
        """pose: bone -> {'rot': (x, y, z), 'pos': (x, y, z)}; returns bone -> world Transform."""
        world = {}
        for b in self.order:
            if only is not None and b not in only:
                continue
            p = pose.get(b, {})
            loc = self.local(b, p.get("rot", (0.0, 0.0, 0.0)), p.get("pos", (0.0, 0.0, 0.0)))
            parent = BONES[b][1]
            world[b] = loc if parent is None else world[parent].then(loc)
        return world

    # -------------------------------------------------------------- limb geometry (rest pose)

    def limb_points(self, limb):
        upper, lower, end_len, _sign = LIMBS[limb]
        s0 = self.pivot[upper]
        e0 = self.pivot[lower]
        h0 = (e0[0], e0[1] - end_len, e0[2])
        return s0, e0, h0

    def end_effector(self, world, limb):
        """Model-space position of the hand / foot."""
        _upper, lower, _l, _s = LIMBS[limb]
        return world[lower].apply(self.limb_points(limb)[2])

    def joint(self, world, limb):
        """Model-space position of the elbow / knee."""
        _upper, lower, _l, _s = LIMBS[limb]
        return world[lower].apply(self.pivot[lower])

    def solve_limb(self, world, limb, target, pole, prev=None, upper_pos=(0.0, 0.0, 0.0)):
        """Analytic 2-bone IK.

        world:  FK of the limb's parent chain (the parent of the upper bone must be present)
        target: model-space point for the hand / foot
        pole:   model-space direction in which the elbow / knee should point
        prev:   previous upper Euler (for angle continuity)
        upper_pos: position keyframe of the upper bone (e.g. a shrug lifts the shoulder)
        returns (upper_rot, lower_rot, reached) - lower_rot only bends around its hinge (X)
        """
        upper, lower, _end_len, sign = LIMBS[limb]
        parent = world[BONES[upper][1]]
        s0, e0, h0 = self.limb_points(limb)
        l1 = length(sub(e0, s0))
        l2 = length(sub(h0, e0))
        s0 = add(s0, upper_pos)
        t = parent.inverse_apply(target)
        q = parent.inverse_dir(pole)
        d_vec = sub(t, s0)
        dist = length(d_vec)
        reached = dist <= l1 + l2 - 0.05
        dist_c = max(abs(l1 - l2) + 0.05, min(l1 + l2 - 0.05, dist))
        a = norm(d_vec) if dist > 1e-6 else (0.0, -1.0, 0.0)
        q = sub(q, mul(a, dot(q, a)))
        if length(q) < 1e-6:
            q = sub((0.0, 0.0, -1.0 * sign), mul(a, dot((0.0, 0.0, -1.0 * sign), a)))
        q = norm(q)
        cos_a = (l1 * l1 + dist_c * dist_c - l2 * l2) / (2 * l1 * dist_c)
        cos_a = max(-1.0, min(1.0, cos_a))
        sin_a = math.sqrt(1 - cos_a * cos_a)
        elbow = add(s0, mul(add(mul(a, cos_a), mul(q, sin_a)), l1))
        hand = add(s0, mul(a, dist_c))
        u = norm(sub(elbow, s0))
        w = norm(sub(hand, elbow))
        phi = math.degrees(math.acos(max(-1.0, min(1.0, dot(u, w)))))
        b = sub(w, mul(u, dot(w, u)))
        if length(b) < 1e-6:
            b = sub(q, mul(u, dot(q, u)))
        b = norm(b)
        # the elbow (sign +1) bends towards -Z of the bone, the knee (sign -1) towards +Z
        u0 = (0.0, -1.0, 0.0)
        b0 = (0.0, 0.0, -1.0 * sign)
        x0 = cross(u0, b0)
        x1 = cross(u, b)
        f1 = ((x1[0], u[0], b[0]), (x1[1], u[1], b[1]), (x1[2], u[2], b[2]))
        f0 = ((x0[0], u0[0], b0[0]), (x0[1], u0[1], b0[1]), (x0[2], u0[2], b0[2]))
        r1 = mat_mul(f1, transpose(f0))
        up = to_euler(r1)
        if prev is not None:
            up = closest_euler(up, prev)
        return up, (sign * phi, 0.0, 0.0), reached


def closest_euler(rot, previous):
    """Of the two equivalent ZYX Euler triples (and their 360-degree wraps) pick the one nearest
    to the previous frame, so interpolating between frames never flips through a long way round."""
    x, y, z = rot
    candidates = [(x, y, z), (x + 180.0, 180.0 - y, z + 180.0)]
    best = None
    for c in candidates:
        c = tuple(unwrap(v, p) for v, p in zip(c, previous))
        d = sum((a - b) ** 2 for a, b in zip(c, previous))
        if best is None or d < best[0]:
            best = (d, c)
    return best[1]


def unwrap(value, previous):
    while value - previous > 180:
        value -= 360
    while value - previous < -180:
        value += 360
    return value
