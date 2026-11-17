# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Voxel canvas + post-processing passes + Sponge Schematic v3 writer."""
from __future__ import annotations

import calendar
import hashlib
import struct
from collections import deque

from . import nbt
from .blocks import (Registry, base, parse_state, is_full_solid, is_stairs, is_fence, is_fence_gate,
                     is_wall, is_pane_like, is_leaves, is_log)

DIRS = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
OPP = {"north": "south", "south": "north", "west": "east", "east": "west"}
CCW = {"north": "west", "west": "south", "south": "east", "east": "north"}
CW = {v: k for k, v in CCW.items()}


def hrand(x: int, y: int, z: int, salt: str = "") -> float:
    """Deterministic pseudo-random float in [0,1) for a position (order independent)."""
    h = hashlib.blake2b(f"{salt}:{x}:{y}:{z}".encode(), digest_size=8).digest()
    return struct.unpack(">Q", h)[0] / 2 ** 64


class Canvas:
    def __init__(self, reg: Registry, width: int, height: int, length: int):
        self.reg = reg
        self.W, self.H, self.L = width, height, length
        self.blocks: dict[tuple[int, int, int], str] = {}
        self.block_entities: dict[tuple[int, int, int], tuple[str, dict]] = {}
        self.markers: dict[str, tuple[int, int, int]] = {}

    # -- basic access -------------------------------------------------------
    def S(self, name: str, **props) -> str:
        return self.reg.state(name, **props)

    def inside(self, x, y, z) -> bool:
        return 0 <= x < self.W and 0 <= y < self.H and 0 <= z < self.L

    def set(self, x, y, z, state, be: tuple[str, dict] | None = None):
        if not self.inside(x, y, z):
            raise IndexError(f"Block outside schematic: {(x, y, z)} -> {state}")
        if callable(state):
            state = state(x, y, z)
        if state is None:
            return
        if base(state) in ("air",):
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = state
        self.block_entities.pop((x, y, z), None)
        if be is not None:
            self.block_entities[(x, y, z)] = be

    def air(self, x, y, z):
        self.set(x, y, z, self.S("air"))

    def get(self, x, y, z) -> str | None:
        return self.blocks.get((x, y, z))

    def fill(self, x1, y1, z1, x2, y2, z2, state):
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                for z in range(min(z1, z2), max(z1, z2) + 1):
                    self.set(x, y, z, state)

    def clear(self, x1, y1, z1, x2, y2, z2):
        self.fill(x1, y1, z1, x2, y2, z2, self.S("air"))

    def walls(self, x1, y1, z1, x2, y2, z2, state):
        """Hollow rectangle of walls (no floor/ceiling)."""
        for y in range(y1, y2 + 1):
            for x in range(x1, x2 + 1):
                self.set(x, y, z1, state); self.set(x, y, z2, state)
            for z in range(z1, z2 + 1):
                self.set(x1, y, z, state); self.set(x2, y, z, state)

    # -- post-processing ----------------------------------------------------
    def finalize(self):
        self._fix_stairs()
        self._fix_connections()
        self._fix_leaves()

    def _fix_stairs(self):
        updates = {}
        for pos, st in self.blocks.items():
            if not is_stairs(st):
                continue
            name, p = parse_state(st)
            facing, half = p["facing"], p["half"]
            x, y, z = pos
            shape = "straight"

            def stair_at(d, _x=x, _y=y, _z=z):
                dx, dz = DIRS[d]
                s = self.get(_x + dx, _y, _z + dz)
                return parse_state(s)[1] if is_stairs(s) else None

            def can_take(d):
                q = stair_at(d)
                return q is None or q["facing"] != facing or q["half"] != half

            front = stair_at(facing)
            if front and front["half"] == half and _axis(front["facing"]) != _axis(facing) and can_take(OPP[front["facing"]]):
                shape = "outer_left" if front["facing"] == CCW[facing] else "outer_right"
            else:
                back = stair_at(OPP[facing])
                if back and back["half"] == half and _axis(back["facing"]) != _axis(facing) and can_take(back["facing"]):
                    shape = "inner_left" if back["facing"] == CCW[facing] else "inner_right"
            if p["shape"] != shape:
                p = dict(p); p["shape"] = shape
                updates[pos] = self.reg.state(name, **p)
        self.blocks.update(updates)

    def _fix_connections(self):
        updates = {}
        for pos, st in self.blocks.items():
            x, y, z = pos
            if is_fence(st):
                name, p = parse_state(st)
                nether = "nether" in name
                p = dict(p)
                for d, (dx, dz) in DIRS.items():
                    n = self.get(x + dx, y, z + dz)
                    ok = False
                    if is_fence(n):
                        ok = ("nether" in n) == nether
                    elif is_fence_gate(n):
                        ok = _axis(parse_state(n)[1]["facing"]) != _axis(d)
                    elif is_full_solid(n):
                        ok = True
                    p[d] = "true" if ok else "false"
                updates[pos] = self.reg.state(name, **p)
            elif is_pane_like(st):
                name, p = parse_state(st)
                p = dict(p)
                for d, (dx, dz) in DIRS.items():
                    n = self.get(x + dx, y, z + dz)
                    p[d] = "true" if (is_pane_like(n) or is_wall(n) or is_full_solid(n)) else "false"
                updates[pos] = self.reg.state(name, **p)
            elif is_wall(st):
                name, p = parse_state(st)
                p = dict(p)
                above = self.get(x, y + 1, z)
                conn = {}
                for d, (dx, dz) in DIRS.items():
                    n = self.get(x + dx, y, z + dz)
                    if is_fence_gate(n):
                        c = _axis(parse_state(n)[1]["facing"]) != _axis(d)
                    else:
                        c = is_wall(n) or is_pane_like(n) or is_full_solid(n)
                    conn[d] = c
                for d in DIRS:
                    if not conn[d]:
                        p[d] = "none"
                    else:
                        tall = is_full_solid(above) or (is_wall(above) and parse_state(above)[1][d] != "none")
                        p[d] = "tall" if tall else "low"
                ns = conn["north"] and conn["south"] and not conn["east"] and not conn["west"]
                ew = conn["east"] and conn["west"] and not conn["north"] and not conn["south"]
                above_post = is_wall(above) and parse_state(above)[1]["up"] == "true"
                needs_post = above is not None and not is_full_solid(above) and not is_wall(above)
                p["up"] = "true" if (above_post or needs_post or not (ns or ew)) else "false"
                updates[pos] = self.reg.state(name, **p)
        self.blocks.update(updates)

    def _fix_leaves(self):
        dist = {}
        q = deque()
        for pos, st in self.blocks.items():
            if is_log(st):
                dist[pos] = 0; q.append(pos)
        while q:
            x, y, z = q.popleft()
            d = dist[(x, y, z)]
            if d >= 7:
                continue
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                n = (x + dx, y + dy, z + dz)
                if n in dist:
                    continue
                if is_leaves(self.blocks.get(n)):
                    dist[n] = d + 1; q.append(n)
        updates = {}
        for pos, st in self.blocks.items():
            if is_leaves(st):
                name, p = parse_state(st)
                p = dict(p); p["distance"] = str(min(7, dist.get(pos, 7))); p["persistent"] = "true"
                updates[pos] = self.reg.state(name, **p)
        self.blocks.update(updates)

    # -- schematic output ---------------------------------------------------
    def max_y(self) -> int:
        return max(y for (_, y, _) in self.blocks)

    def to_schematic(self, data_version: int, offset: tuple[int, int, int], name: str, author: str,
                     height: int | None = None, include_air: bool = True) -> dict:
        H = height if height is not None else self.H
        W, L = self.W, self.L
        air = self.reg.state("air")
        palette: dict[str, int] = {}
        if include_air:
            palette[air] = 0
        data = []
        for y in range(H):
            for z in range(L):
                for x in range(W):
                    st = self.blocks.get((x, y, z), air)
                    idx = palette.get(st)
                    if idx is None:
                        idx = palette[st] = len(palette)
                    data.append(idx)
        bes = nbt.List([], elem_type=nbt.COMPOUND)
        for (x, y, z), (be_id, be_data) in sorted(self.block_entities.items()):
            if y >= H:
                continue
            d = dict(be_data); d["id"] = be_id
            bes.append({"Pos": nbt.IntArray([x, y, z]), "Id": be_id, "Data": d})
        schematic = {
            "Version": nbt.Int(3),
            "DataVersion": nbt.Int(data_version),
            "Metadata": {
                "Name": name,
                "Author": author,
                "Date": nbt.Long(calendar.timegm((2026, 10, 25, 12, 0, 0)) * 1000),
                "WorldEdit": {"Origin": nbt.IntArray([0, 0, 0])},
            },
            "Width": nbt.Short(W),
            "Height": nbt.Short(H),
            "Length": nbt.Short(L),
            "Offset": nbt.IntArray(list(offset)),
            "Blocks": {
                "Palette": {k: nbt.Int(v) for k, v in palette.items()},
                "Data": nbt.ByteArray(nbt.encode_varints(data)),
                "BlockEntities": bes,
            },
        }
        return {"Schematic": schematic}


def _axis(d: str) -> str:
    return "x" if d in ("east", "west") else "z"
