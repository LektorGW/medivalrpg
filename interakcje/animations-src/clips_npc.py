# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Work animations of NPC citizens (Mannequins of Ludnosc, Skarbiec and Armia), on the same player
rig and in the same model as the player interactions (``interakcje``: the head is animated by the
clip, there is no camera on an NPC).

Loops close exactly (every channel is periodic in the clip length); one-shots start and end in the
rest pose. Held items come from the Mannequin's equipment (BetterModel shows the main / off hand
item on the hand bones), so the clips are posed for a vanilla grip: with the forearm hanging the
item points forward, with the forearm horizontal it points up.
"""
import math

from clips import (ARM_REST_POLE_L, ARM_REST_POLE_R, REST, breathe, fixed, ik_window, mine, offset, path,
                   vec_path)
from motion import Clip, window

ZERO = (0, 0, 0)


def at(spec):
    """A path-style target spec(ctx, t) as a plain IK target f(t, ctx)."""
    return lambda t, ctx: spec(ctx, t)


def _sin(period, phase=0.0):
    return lambda t: math.sin(2 * math.pi * (t / period + phase))


def _loop_keys(c, times_values, ease="inout"):
    """Keys for a loop: [(t, {bone: rot})]; the first entry is repeated at the clip end."""
    first_t, first = times_values[0]
    for t, bones in times_values:
        c.key(t, ease, **bones)
    c.key(c.length, ease, **first)


# ---------------------------------------------------------------------------- builder: hammer

def npc_hammer():
    """Budowniczy: bent over a plank, the left hand steadies it, the right hand hammers twice per
    loop - raise behind the head, a fast accelerating strike, a short rebound."""
    L = 1.6
    c = Clip("npc_hammer", L, loop=True)
    stance = dict(hip=(-14, 0, 0), waist=(-7, -6, 0), r_leg=(-4, 0, 2), l_leg=(10, 0, -2),
                  r_shin=(-10, 0, 0), l_shin=(-14, 0, 0), l_arm=(42, 6, -6), l_fore=(38, 0, 0))
    for bone, rot in stance.items():
        c.layer(bone, (lambda r: lambda t: r)(rot))
    c.layer("root", lambda t: (0.0, -0.8, 0.0), channel="pos")
    low = dict(r_arm=(48, 0, 6), r_fore=(12, 0, 0), chest=(-10, -6, 0), head=(-14, 4, 0))
    up = dict(r_arm=(128, 0, 14), r_fore=(70, 0, 0), chest=(-4, -10, 0), head=(-10, 4, 0))
    hit = dict(r_arm=(52, 0, 6), r_fore=(8, 0, 0), chest=(-12, -4, 0), head=(-15, 4, 0))
    half = L / 2
    for o in (0.0, half):
        c.key(o + 0.0, **low)
        c.key(o + 0.32, "inout", **up)
        c.key(o + 0.5, "quad_in", **hit)
        c.key(o + 0.62, "quart_out", **low)
    c.key(L, **low)
    c.event("impact", 0.5, half + 0.5)
    breathe(c, 0.4, L / 2)
    return c


# ---------------------------------------------------------------------------- porter

def npc_carry_back():
    """Tragarz z ładunkiem na plecach: bent forward under the load, both hands over the shoulders
    holding it, slow breathing, a hitch of the load in the middle of the loop."""
    L = 4.0
    c = Clip("npc_carry_back", L, loop=True)
    hitch = window(1.8, 2.0, 2.0, 2.4)
    c.layer("hip", lambda t: (-16.0, 0.0, 1.2 * _sin(L)(t)))
    c.layer("waist", lambda t: (-6.0, 0.0, 0.0))
    c.layer("chest", lambda t: (-4.0 + 3.0 * hitch(t), 0.0, -0.8 * _sin(L)(t)))
    c.layer("head", lambda t: (14.0 - 2.0 * hitch(t), 0.0, 0.0))
    c.layer("r_leg", lambda t: (6.0, 0.0, 1.0))
    c.layer("l_leg", lambda t: (6.0, 0.0, -1.0))
    c.layer("r_shin", lambda t: (-9.0 - 2.0 * (1 + _sin(L)(t)), 0.0, 0.0))
    c.layer("l_shin", lambda t: (-9.0 - 2.0 * (1 - _sin(L)(t)), 0.0, 0.0))
    c.layer("root", lambda t: (0.0, -0.6 + 0.35 * hitch(t), 0.0), channel="pos")
    breathe(c, 1.0, 2.0)
    for limb, side in (("r_arm", 1.0), ("l_arm", -1.0)):
        c.ik(limb, (lambda s: lambda t, ctx: ctx.mine("chest", (s * 3.6, 26.0 + 0.4 * hitch(t), 3.2)))(side),
             (side * 0.6, 0.2, -1.0))
    return c


def npc_carry_front():
    """Tragarz ze skrzynią w rękach: the crate held in front of the belly, the torso leaning back
    against the weight, small balancing sways."""
    L = 4.0
    c = Clip("npc_carry_front", L, loop=True)
    c.layer("hip", lambda t: (2.0, 1.5 * _sin(L)(t), 0.8 * _sin(L, 0.25)(t)))
    c.layer("waist", lambda t: (5.0, 0.0, 0.0))
    c.layer("chest", lambda t: (4.0, -1.0 * _sin(L)(t), 0.0))
    c.layer("head", lambda t: (-12.0, 2.0 * _sin(L, 0.1)(t), 0.0))
    c.layer("r_shin", lambda t: (-3.0, 0.0, 0.0))
    c.layer("l_shin", lambda t: (-3.0, 0.0, 0.0))
    breathe(c, 0.9, 2.0)
    for limb, side in (("r_arm", 1.0), ("l_arm", -1.0)):
        c.ik(limb, (lambda s: lambda t, ctx: ctx.mine("waist", (s * 4.6, 13.6 + 0.2 * _sin(L / 2)(t), -6.8)))(side),
             (side * 1.0, -0.3, 0.4))
    return c


# ---------------------------------------------------------------------------- miner

def npc_pickaxe():
    """Górnik: swings the pickaxe overhead into the rock face in front of him (head height),
    leaning into each blow; a short pause to pull it out."""
    L = 1.5
    c = Clip("npc_pickaxe", L, loop=True)
    c.layer("r_leg", lambda t: (-6.0, 0.0, 2.0))
    c.layer("l_leg", lambda t: (12.0, 0.0, -2.0))
    c.layer("r_shin", lambda t: (-6.0, 0.0, 0.0))
    c.layer("l_shin", lambda t: (-10.0, 0.0, 0.0))
    rest = dict(r_arm=(62, 0, 4), r_fore=(18, 0, 0), l_arm=(55, -10, -8), l_fore=(30, 0, 0),
                chest=(-8, -4, 0), waist=(-3, 0, 0), hip=(-4, 0, 0), head=(-4, 2, 0))
    up = dict(r_arm=(168, 0, 12), r_fore=(55, 0, 0), l_arm=(120, -12, -10), l_fore=(50, 0, 0),
              chest=(6, -10, 0), waist=(3, -4, 0), hip=(0, 0, 0), head=(8, 4, 0))
    strike = dict(r_arm=(78, 0, 4), r_fore=(4, 0, 0), l_arm=(70, -10, -8), l_fore=(12, 0, 0),
                  chest=(-14, -2, 0), waist=(-5, 0, 0), hip=(-6, 0, 0), head=(-8, 2, 0))
    c.key(0.0, **rest)
    c.key(0.5, "cubic", **up)
    c.key(0.72, "quad_in", **strike)
    c.key(0.95, "quart_out", **strike)
    c.key(L, **rest)
    c.keypos(0.0, root=ZERO)
    c.keypos(0.72, "quad_in", root=(0, -0.5, -0.4))
    c.keypos(L, root=ZERO)
    c.event("impact", 0.72)
    return c


# ---------------------------------------------------------------------------- farmer

def npc_hoe():
    """Rolnik z motyką: bent over the furrow, both hands on the handle, raise, chop into the soil
    and drag it back, one step of the rhythm per 1.8 s."""
    L = 1.8
    c = Clip("npc_hoe", L, loop=True)
    c.layer("hip", lambda t: (-22.0, 0.0, 0.0))
    c.layer("waist", lambda t: (-8.0, 0.0, 0.0))
    c.layer("r_leg", lambda t: (-12.0, 0.0, 3.0))
    c.layer("l_leg", lambda t: (16.0, 0.0, -3.0))
    c.layer("r_shin", lambda t: (-8.0, 0.0, 0.0))
    c.layer("l_shin", lambda t: (-18.0, 0.0, 0.0))
    c.layer("root", lambda t: (0.0, -0.9, 0.6), channel="pos")
    pull = dict(r_arm=(22, -6, 4), r_fore=(30, 0, 0), l_arm=(30, 8, -4), l_fore=(42, 0, 0),
                chest=(-6, 0, 0), head=(-8, 0, 0))
    lift = dict(r_arm=(88, -10, 6), r_fore=(46, 0, 0), l_arm=(80, 10, -6), l_fore=(56, 0, 0),
                chest=(2, 0, 0), head=(-2, 0, 0))
    chop = dict(r_arm=(40, -8, 4), r_fore=(6, 0, 0), l_arm=(46, 8, -4), l_fore=(16, 0, 0),
                chest=(-12, 0, 0), head=(-12, 0, 0))
    c.key(0.0, **pull)
    c.key(0.7, "cubic", **lift)
    c.key(0.92, "quad_in", **chop)
    c.key(1.15, "quart_out", **chop)
    c.key(L, "inout", **pull)
    c.event("impact", 0.92)
    breathe(c, 0.5, L)
    return c


# ---------------------------------------------------------------------------- woodcutter

def npc_axe():
    """Drwal: a diagonal chop at waist height - the torso coils to the right with the axe over the
    right shoulder, uncoils into the trunk in front, a pause, and again."""
    L = 1.6
    c = Clip("npc_axe", L, loop=True)
    c.layer("r_leg", lambda t: (-8.0, 0.0, 3.0))
    c.layer("l_leg", lambda t: (12.0, 0.0, -3.0))
    c.layer("r_shin", lambda t: (-8.0, 0.0, 0.0))
    c.layer("l_shin", lambda t: (-12.0, 0.0, 0.0))
    c.layer("root", lambda t: (0.0, -0.5, 0.0), channel="pos")
    ready = dict(chest=(-6, 8, 0), waist=(-3, 4, 0), hip=(-4, 0, 0), head=(-6, -8, 0),
                 r_arm=(60, 0, 10), r_fore=(30, 0, 0), l_arm=(52, 14, -4), l_fore=(36, 0, 0))
    coil = dict(chest=(-2, -30, -4), waist=(-2, -12, 0), hip=(-2, -4, 0), head=(-4, 26, 0),
                r_arm=(110, -10, 58), r_fore=(70, 0, 0), l_arm=(90, -30, -10), l_fore=(60, 0, 0))
    chop = dict(chest=(-12, 18, 4), waist=(-5, 8, 0), hip=(-5, 3, 0), head=(-8, -12, 0),
                r_arm=(64, 0, -12), r_fore=(4, 0, 0), l_arm=(58, 24, 4), l_fore=(10, 0, 0))
    c.key(0.0, **ready)
    c.key(0.6, "cubic", **coil)
    c.key(0.8, "quad_in", **chop)
    c.key(1.05, "quart_out", **chop)
    c.key(L, **ready)
    c.event("impact", 0.8)
    return c


# ---------------------------------------------------------------------------- smith

def npc_anvil():
    """Kowal przy kowadle: the left hand holds the workpiece with tongs on the anvil, the right
    hammers three quick blows, then turns the piece and looks at it."""
    L = 2.6
    c = Clip("npc_anvil", L, loop=True)
    c.layer("hip", lambda t: (-8.0, 0.0, 0.0))
    c.layer("waist", lambda t: (-4.0, -4.0, 0.0))
    c.layer("r_leg", lambda t: (-4.0, 0.0, 3.0))
    c.layer("l_leg", lambda t: (6.0, 0.0, -3.0))
    c.layer("r_shin", lambda t: (-6.0, 0.0, 0.0))
    c.layer("l_shin", lambda t: (-8.0, 0.0, 0.0))
    low = dict(r_arm=(54, 0, 8), r_fore=(26, 0, 0))
    up = dict(r_arm=(104, 0, 12), r_fore=(78, 0, 0))
    hit = dict(r_arm=(58, 0, 8), r_fore=(18, 0, 0))
    blows = (0.42, 0.86, 1.3)
    c.key(0.0, **low)
    for b in blows:
        c.key(b - 0.26, "cubic", **up)
        c.key(b, "quad_in", **hit)
        c.key(b + 0.1, "quart_out", **low)
    c.key(L, **low)
    tongs = dict(l_arm=(46, 10, -6), l_fore=(34, 0, 0))
    turn = dict(l_arm=(58, 18, -10), l_fore=(54, 0, 0))
    c.key(0.0, **tongs)
    c.key(1.45, **tongs)
    c.key(1.75, **turn)
    c.key(2.15, **turn)
    c.key(L, **tongs)
    c.key(0.0, chest=(-8, -4, 0), head=(-18, 6, 0))
    c.key(1.45, chest=(-8, -4, 0), head=(-18, 6, 0))
    c.key(1.8, chest=(-6, 4, 2), head=(-14, -10, 8))   # inspecting the piece
    c.key(2.2, chest=(-6, 4, 2), head=(-14, -10, 8))
    c.key(L, chest=(-8, -4, 0), head=(-18, 6, 0))
    c.event("impact", *blows)
    breathe(c, 0.5, L)
    return c


# ---------------------------------------------------------------------------- merchant

def npc_invite():
    """Kupiec zaprasza: hands folded on the belly, then an open-palm sweep of the right hand
    towards the stall with a small bow and a nod; back to waiting."""
    L = 4.0
    c = Clip("npc_invite", L, loop=True)
    c.layer("hip", lambda t: (0.0, 0.0, 0.8 * _sin(L)(t)))
    breathe(c, 0.7, L / 2)
    c.key(0.0, chest=ZERO, head=(2, 0, 0), waist=ZERO)
    c.key(0.5, chest=ZERO, head=(2, 0, 0), waist=ZERO)
    c.key(1.0, "cubic", chest=(-8, 10, 0), head=(-10, 14, 4), waist=(-3, 4, 0))
    c.key(1.8, chest=(-8, 10, 0), head=(-6, 18, 4), waist=(-3, 4, 0))
    c.key(2.4, chest=ZERO, head=(2, 0, 0), waist=ZERO)
    c.key(L, chest=ZERO, head=(2, 0, 0), waist=ZERO)
    folded_r = mine("waist", (1.2, 13.4, -4.4))
    folded_l = mine("waist", (-1.2, 13.0, -4.6))
    open_palm = mine("chest", (11.0, 14.0, -7.5))
    c.ik("r_arm", path("r_arm", [
        (0.0, folded_r, "linear"),
        (0.45, folded_r, "linear"),
        (1.0, open_palm, "cubic"),
        (1.8, offset(open_palm, lambda t: (0.6, 0.4, 0.0)), "inout"),
        (2.45, folded_r, "inout"),
        (L, folded_r, "linear"),
    ]), vec_path([(0.0, (1.0, -0.2, 0.5), "linear"), (0.45, (1.0, -0.2, 0.5), "linear"),
                  (1.0, (0.4, -1.0, 0.2), "inout"), (1.8, (0.4, -1.0, 0.2), "linear"),
                  (2.45, (1.0, -0.2, 0.5), "inout"), (L, (1.0, -0.2, 0.5), "linear")]))
    c.ik("l_arm", at(folded_l), (-1.0, -0.2, 0.5))
    return c


# ---------------------------------------------------------------------------- guard

def npc_guard():
    """Strażnik na posterunku: at attention, the left hand on the hilt, the right hand holds the
    weapon upright before the shoulder (a held sword / spear points up), the head looks left and
    right over the eight seconds of the loop."""
    L = 8.0
    c = Clip("npc_guard", L, loop=True)
    c.layer("hip", lambda t: (0.0, 0.0, 0.6 * _sin(L)(t)))
    c.layer("r_leg", lambda t: (0.0, 0.0, -1.5))
    c.layer("l_leg", lambda t: (0.0, 0.0, 1.5))
    c.layer("chest", lambda t: (2.0, 0.0, -0.4 * _sin(L)(t)))
    breathe(c, 0.8, 4.0)
    c.key(0.0, head=(2, 0, 0))
    c.key(1.8, head=(2, 0, 0))
    c.key(2.5, head=(0, 38, 0))
    c.key(3.6, head=(0, 38, 0))
    c.key(4.3, head=(2, 0, 0))
    c.key(5.0, head=(2, 0, 0))
    c.key(5.7, head=(1, -34, 0))
    c.key(6.8, head=(1, -34, 0))
    c.key(L, head=(2, 0, 0))
    c.layer("r_arm", lambda t: (14.0, 6.0, 8.0))
    c.layer("r_fore", lambda t: (76.0, 0.0, 0.0))
    c.ik("l_arm", at(mine("waist", (-4.9, 13.6, -1.8))), (-1.0, 0.1, 0.5))
    return c


# ---------------------------------------------------------------------------- rest

def npc_yawn():
    """Ziewanie (once, 3.6 s): stretch both arms up, arch back with a wide yawn, the right hand to
    the mouth, back to standing."""
    L = 3.6
    c = Clip("npc_yawn", L)
    c.key(0.0, chest=ZERO, head=ZERO, waist=ZERO, hip=ZERO)
    c.key(0.9, "cubic", chest=(8, 0, 0), head=(26, 0, 0), waist=(4, 0, 0), hip=(2, 0, 0))
    c.key(1.7, chest=(9, 0, 2), head=(28, 4, 3), waist=(4, 0, 0), hip=(2, 0, 0))
    c.key(2.4, "cubic", chest=(-2, 0, 0), head=(-2, 0, 0), waist=ZERO, hip=ZERO)
    c.key(L - 0.05, chest=ZERO, head=ZERO, waist=ZERO, hip=ZERO)
    c.keypos(0.0, root=ZERO)
    c.keypos(1.0, root=(0, 0.4, 0))
    c.keypos(1.8, root=(0, 0.4, 0))
    c.keypos(2.4, root=ZERO)
    c.keypos(L, root=ZERO)
    for limb, side, pole in (("r_arm", 1.0, ARM_REST_POLE_R), ("l_arm", -1.0, ARM_REST_POLE_L)):
        high = mine("chest", (side * 4.0, 33.5, 1.0))
        points = [(0.0, REST, "linear"),
                  (0.35, mine("chest", (side * 8.0, 22.0, -2.0)), "inout"),
                  (0.95, high, "cubic"),
                  (1.7, offset(high, lambda t: (0.0, 0.6, 0.0)), "inout")]
        if side > 0:
            points += [(2.25, mine("head", (0.4, 24.0, -5.4)), "inout"),
                       (2.75, mine("head", (0.4, 24.0, -5.4)), "linear"),
                       (3.15, mine("chest", (7.0, 16.0, -3.0)), "inout"),
                       (L - 0.05, REST, "inout")]
        else:
            points += [(2.35, mine("chest", (-8.0, 17.0, -3.0)), "inout"),
                       (L - 0.05, REST, "inout")]
        c.ik(limb, path(limb, points),
             vec_path([(0.0, pole, "linear"), (0.6, (side * 1.0, 0.2, 0.3), "inout"),
                       (2.2, (side * 0.8, -1.0, 0.2), "inout"), (L - 0.05, pole, "inout")]), ik_window(L))
    return c


def npc_rest():
    """Odpoczynek (loop): arms folded on the chest, weight on one leg, a slow look around and a
    nod off now and then."""
    L = 6.0
    c = Clip("npc_rest", L, loop=True)
    c.layer("hip", lambda t: (0.0, 0.0, 3.0 + 0.6 * _sin(L)(t)))
    c.layer("chest", lambda t: (1.0, 0.0, -2.0))
    c.layer("r_leg", lambda t: (0.0, 0.0, 2.0))
    c.layer("l_leg", lambda t: (4.0, 0.0, -5.0))
    c.layer("l_shin", lambda t: (-8.0, 0.0, 0.0))
    breathe(c, 0.9, 3.0)
    c.key(0.0, head=(2, 0, 3))
    c.key(1.5, head=(2, 18, 3))
    c.key(2.8, head=(2, 18, 3))
    c.key(3.6, head=(-14, 4, 3))     # dozing off
    c.key(4.6, head=(-16, 2, 3))
    c.key(5.0, "back", head=(4, 0, 3))
    c.key(L, head=(2, 0, 3))
    c.ik("r_arm", at(mine("chest", (-3.4, 18.4, -4.9))), (1.0, -0.5, 0.3))
    c.ik("l_arm", at(mine("chest", (3.6, 17.2, -5.1))), (-1.0, -0.5, 0.3))
    return c


# ---------------------------------------------------------------------------- catalogue

def build():
    return [npc_hammer(), npc_carry_back(), npc_carry_front(), npc_pickaxe(), npc_hoe(), npc_axe(),
            npc_anvil(), npc_invite(), npc_guard(), npc_yawn(), npc_rest()]


# API name (WorkAnimations) -> clip. "salut" reuses the knight's salute of the player emotes.
WORK = {
    "budowa": "npc_hammer",
    "noszenie_plecy": "npc_carry_back",
    "noszenie_rece": "npc_carry_front",
    "kilof": "npc_pickaxe",
    "motyka": "npc_hoe",
    "siekiera": "npc_axe",
    "kowadlo": "npc_anvil",
    "zaproszenie": "npc_invite",
    "salut": "emote_salut",
    "straz": "npc_guard",
    "ziewanie": "npc_yawn",
    "odpoczynek": "npc_rest",
}
