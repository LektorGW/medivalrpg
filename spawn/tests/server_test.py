# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Loads and pastes the schematics with the real WorldEdit on the local TEST server (never production).

  .venv\\Scripts\\python tests\\server_test.py <path-to-mcharness.py> [server-dir]

server-dir defaults to ../testserver. Use a copy (Paper jar + WorldEdit) when other people/agents share the
test server; the harness then has to take the directory from the MC_SERVER_DIR environment variable.

Copies out/*.schem to <server>/plugins/WorldEdit/schematics/, drives the console:
//world, //schem load, //pos1 + //placement pos1, //paste (and //paste -a for noair), then checks
sampled blocks with `execute if block` (full block states) and reads sign/banner NBT with `data get block`,
finally //undo both pastes. Results are parsed from testserver/harness.log.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)

from generator import nbt                                   # noqa: E402
from generator.blocks import base                           # noqa: E402

SERVER = os.path.join(os.path.dirname(ROOT), "testserver")
P1 = (1000, 150, 1000)      # paste point for spawn.schem
P2 = (1100, 150, 1000)      # paste point for spawn_noair.schem


def load(name):
    s = nbt.read(os.path.join(ROOT, "out", name))[1]["Schematic"]
    W, H, L = s["Width"].v, s["Height"].v, s["Length"].v
    pal = {v.v: k for k, v in s["Blocks"]["Palette"].items()}
    data = nbt.decode_varints(bytes(s["Blocks"]["Data"]))
    off = list(s["Offset"])
    blocks = {}
    for i, idx in enumerate(data):
        x, z, y = i % W, (i // W) % L, i // (W * L)
        blocks[(x, y, z)] = pal[idx]
    return s, blocks, off


def world(p, off, local):
    return p[0] + off[0] + local[0], p[1] + off[1] + local[1], p[2] + off[2] + local[2]


def samples(blocks, s):
    """A deterministic, diverse sample: every block entity, one of each block type, plus the air above."""
    picks = []
    for be in s["Blocks"]["BlockEntities"]:
        picks.append(tuple(be["Pos"]))
    by_type = {}
    for pos in sorted(blocks):
        st = blocks[pos]
        by_type.setdefault(base(st), pos)
    picks += [p for p in by_type.values() if p not in picks]
    picks += [(10, 30, 10), (34, 31, 34), (60, 25, 60)]       # air layer (clears trees)
    return picks


def main(harness, server=SERVER):
    server = os.path.abspath(server)
    SCHEM_DIR = os.path.join(server, "plugins", "WorldEdit", "schematics")
    os.makedirs(SCHEM_DIR, exist_ok=True)
    for f in ("spawn.schem", "spawn_noair.schem"):
        shutil.copy(os.path.join(ROOT, "out", f), os.path.join(SCHEM_DIR, f))
    s, blocks, off = load("spawn.schem")
    s2, blocks2, off2 = load("spawn_noair.schem")
    checks = []
    for i, local in enumerate(samples(blocks, s)):
        checks.append((f"A{i}", world(P1, off, local), blocks[local]))
    for i, local in enumerate([(34, 4, 34), (20, 5, 12), (56, 4, 29), (34, 8, 41), (40, 24, 12)]):
        checks.append((f"B{i}", world(P2, off2, local), blocks2[local]))
    board = [(x, y, 41) for x in (33, 34, 35) for y in (6, 7)] + [(34, 8, 41)]
    cmds = ["#sleep 3", "forceload add 960 960 1140 1040", "#sleep 8",
            "//world world", "/schem list",
            "//schem load spawn.schem", f"//pos1 {P1[0]},{P1[1]},{P1[2]}", "//placement pos1", "//paste", "#sleep 3"]
    for cid, (x, y, z), st in checks:
        if cid.startswith("B") and cid == "B0":
            cmds += ["//schem load spawn_noair.schem", f"//pos1 {P2[0]},{P2[1]},{P2[2]}", "//placement pos1",
                     "//paste -a", "#sleep 3"]
        cmds.append(f"execute if block {x} {y} {z} {st} run say OK {cid}")
    for local in sorted(set(board)):
        x, y, z = world(P1, off, local)
        cmds.append(f"data get block {x} {y} {z} front_text.messages")
    for local in ((34, 11, 24), (36, 10, 26)):                # knight's cape and shield banners
        x, y, z = world(P1, off, local)
        cmds.append(f"data get block {x} {y} {z} patterns")
    cmds += ["//undo", "//undo", "#sleep 2"]
    x, y, z = world(P1, off, (34, 4, 34))
    cmds.append(f"execute unless block {x} {y} {z} minecraft:lodestone run say OK UNDO1")
    x, y, z = world(P2, off2, (34, 4, 34))
    cmds.append(f"execute unless block {x} {y} {z} minecraft:lodestone run say OK UNDO2")
    cmds += ["forceload remove all", "#sleep 2"]
    cmd_file = os.path.join(HERE, "last_server_test_commands.txt")
    with open(cmd_file, "w", encoding="utf-8") as f:
        f.write("\n".join(cmds) + "\n")
    print(f"{len(checks)} block checks, {len(cmds)} console lines -> {cmd_file}")
    env = dict(os.environ, MC_SERVER_DIR=server)
    subprocess.run([sys.executable, harness, cmd_file], cwd=server, check=True, timeout=1800, env=env)
    with open(os.path.join(server, "harness.log"), encoding="utf-8", errors="replace") as f:
        log = re.sub(r"\x1b\[[0-9;]*m", "", f.read())
    ok = set(re.findall(r"\[Server\] OK (\w+)", log)) | set(re.findall(r"\[Rcon\] OK (\w+)", log))
    failed = [(cid, pos, st) for cid, pos, st in checks if cid not in ok]
    print("WorldEdit lines:")
    for line in log.splitlines():
        if re.search(r"schematics|\.schem|loaded\.|Wklejono|pasted|Obj.to|affected|Cofni|undo|Invalid value|Sk.adnia|Unknown or", line) \
                and not re.search(r"squaremap|Paper|java|Loading|Enabling", line):
            print("  ", line.strip()[:240])
    print("Sign/banner NBT:")
    for line in log.splitlines():
        if "has the following block data" in line:
            print("  ", line.strip()[:300])
    print(f"block checks passed: {len(checks) - len(failed)}/{len(checks)}")
    for cid, pos, st in failed:
        print("   FAIL", cid, pos, st)
    for u in ("UNDO1", "UNDO2"):
        print(f"   {u}: {'OK' if u in ok else 'FAIL'}")
    loaded = log.count("loaded. Paste it with //paste")
    print(f"   schematics loaded by WorldEdit: {loaded}/2")
    return 0 if not failed and {"UNDO1", "UNDO2"} <= ok and loaded == 2 else 1


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
