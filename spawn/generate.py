# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Generates the MEDIVALRPG spawn as WorldEdit schematics (Sponge Schematic v3) + previews.

  .venv\\Scripts\\python generate.py

Outputs: out/spawn.schem (with air, clears terrain), out/spawn_noair.schem (for //paste -a),
out/markers.json, preview/*.png. Fails on any block state not valid for MC 26.3.
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from generator import nbt                       # noqa: E402
from generator.blocks import Registry          # noqa: E402
from generator.build import build, C, G, N, H  # noqa: E402
from generator import render                   # noqa: E402

OUT = os.path.join(HERE, "out")
PREVIEW = os.path.join(HERE, "preview")
AUTHOR = "Maciej Kaczmarczyk — lektoro.pl"
ORIGIN = (C, G + 1, C)          # player's feet on the wind rose = paste point
OFFSET = (-ORIGIN[0], -ORIGIN[1], -ORIGIN[2])


def data_version() -> int:
    with open(os.path.join(HERE, "data", "version-26.3.json"), encoding="utf-8") as f:
        return int(json.load(f)["world_version"])


def main(previews: bool = True):
    reg = Registry()
    canvas = build(reg)
    states = set(canvas.blocks.values())
    for st in states:
        reg.validate(st)                       # complete + valid for 26.3, else raises
    for pos, (be_id, _) in canvas.block_entities.items():
        if pos not in canvas.blocks:
            raise RuntimeError(f"Block entity {be_id} at {pos} has no block")
    dv = data_version()
    os.makedirs(OUT, exist_ok=True)
    full = canvas.to_schematic(dv, OFFSET, "MEDIVALRPG Spawn", AUTHOR, height=H)
    nbt.write(os.path.join(OUT, "spawn.schem"), full)
    top = canvas.max_y() + 1
    noair = canvas.to_schematic(dv, OFFSET, "MEDIVALRPG Spawn (noair)", AUTHOR, height=top)
    nbt.write(os.path.join(OUT, "spawn_noair.schem"), noair)
    markers = {k: {"local": list(v), "relative_to_paste_point": [v[0] - ORIGIN[0], v[1] - ORIGIN[1], v[2] - ORIGIN[2]]}
               for k, v in canvas.markers.items()}
    with open(os.path.join(OUT, "markers.json"), "w", encoding="utf-8") as f:
        json.dump({"size": [N, H, N], "noair_height": top, "offset": list(OFFSET), "data_version": dv,
                   "markers": markers}, f, indent=2, ensure_ascii=False)
    print(f"DataVersion {dv}; size {N}x{H}x{N} (noair height {top}); palette {len(states) + 1} states; "
          f"{len(canvas.block_entities)} block entities; offset {OFFSET}")
    for k, v in markers.items():
        print(f"  marker {k}: local {v['local']}, from paste point {v['relative_to_paste_point']}")
    if previews:
        os.makedirs(PREVIEW, exist_ok=True)
        render.render_top(canvas, os.path.join(PREVIEW, "top.png"))
        for rot, name in ((0, "se"), (1, "ne"), (2, "nw"), (3, "sw")):     # viewer standing in that corner
            render.render_iso(canvas, os.path.join(PREVIEW, f"iso_{name}.png"), rotate=rot)
        if render.unknown_colors():
            print("preview: no colour for", ", ".join(render.unknown_colors()))
    return canvas


if __name__ == "__main__":
    main(previews="--no-preview" not in sys.argv)
