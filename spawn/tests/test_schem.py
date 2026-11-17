# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Reads the generated schematics back with an independent NBT parse and checks them.

  .venv\\Scripts\\python -m unittest discover -s tests -v
"""
from __future__ import annotations

import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)

from generator import nbt                               # noqa: E402
from generator.blocks import Registry, parse_state      # noqa: E402
from generator.build import BANNER_PATTERNS, SIGN_WIDTH, HANGING_SIGN_WIDTH, text_width   # noqa: E402

OUT = os.path.join(ROOT, "out")
EXPECTED_SIGNS = [
    "Witaj w MEDIVALRPG!",
    "/menu wszystko w jednym miejscu",
    "/glos czat głosowy",
    "/kraj pomoc załóż państwo",
    "/mapa mapa świata",
    "Kupiec skupuje surowce, Bankier strzeże monet",
    "/nowosci co nowego",
]


def load(name):
    root_name, root = nbt.read(os.path.join(OUT, name))
    assert root_name == ""
    return root["Schematic"]


def decode(s):
    W, H, L = s["Width"].v, s["Height"].v, s["Length"].v
    pal = {v.v: k for k, v in s["Blocks"]["Palette"].items()}
    data = nbt.decode_varints(bytes(s["Blocks"]["Data"]))
    return W, H, L, pal, data


def block_at(W, L, pal, data, x, y, z):
    return pal[data[x + z * W + y * W * L]]


def sign_text(be):
    return " ".join(m["text"] if isinstance(m, dict) else m
                    for m in be["Data"]["front_text"]["messages"]).split()


class SchematicTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.reg = Registry()
        with open(os.path.join(ROOT, "data", "version-26.3.json"), encoding="utf-8") as f:
            cls.dv = json.load(f)["world_version"]
        with open(os.path.join(OUT, "markers.json"), encoding="utf-8") as f:
            cls.meta = json.load(f)
        cls.full = load("spawn.schem")
        cls.noair = load("spawn_noair.schem")

    def test_header(self):
        for s in (self.full, self.noair):
            self.assertEqual(s["Version"].v, 3)
            self.assertEqual(s["DataVersion"].v, self.dv)
            self.assertEqual(self.dv, 5023)
            self.assertEqual(list(s["Offset"]), [-34, -5, -34])
            self.assertEqual(list(s["Metadata"]["WorldEdit"]["Origin"]), [0, 0, 0])

    def test_dimensions(self):
        W, H, L, _, data = decode(self.full)
        self.assertEqual((W, H, L), (69, 32, 69))
        self.assertEqual(len(data), W * H * L)
        W2, H2, L2, _, data2 = decode(self.noair)
        self.assertEqual((W2, L2), (69, 69))
        self.assertLess(H2, H)
        self.assertEqual(len(data2), W2 * H2 * L2)

    def test_palette_states_valid_for_26_3(self):
        for s in (self.full, self.noair):
            pal = s["Blocks"]["Palette"]
            idx = sorted(v.v for v in pal.values())
            self.assertEqual(idx, list(range(len(idx))), "palette indices must be dense")
            for state in pal:
                self.reg.validate(state)
            _, _, _, p, data = decode(s)
            self.assertLessEqual(max(data), len(p) - 1)

    def test_air_above_and_foundation(self):
        W, H, L, pal, data = decode(self.full)
        top = self.meta["noair_height"]
        for y in range(top, H):                       # pure air layers clear trees/terrain on paste
            for z in range(L):
                for x in range(W):
                    self.assertEqual(block_at(W, L, pal, data, x, y, z), "minecraft:air")
        for y in range(0, 4):                         # foundation has no holes
            for z in range(L):
                for x in range(W):
                    self.assertNotEqual(block_at(W, L, pal, data, x, y, z), "minecraft:air", (x, y, z))
        self.assertGreaterEqual(H - 5, 25, "at least ~25 blocks of air above the paving level")

    def test_paste_point_and_markers(self):
        W, H, L, pal, data = decode(self.full)
        ox, oy, oz = (-v for v in self.full["Offset"])
        self.assertEqual(block_at(W, L, pal, data, ox, oy - 1, oz), "minecraft:lodestone")
        self.assertEqual(block_at(W, L, pal, data, ox, oy, oz), "minecraft:air")
        m = self.meta["markers"]
        for name, carpet in (("bankier", "minecraft:red_carpet"), ("kupiec", "minecraft:lime_carpet")):
            x, y, z = m[name]["local"]
            self.assertEqual(block_at(W, L, pal, data, x, y, z), carpet)
            self.assertEqual(block_at(W, L, pal, data, x, y - 1, z), "minecraft:gold_block")
            self.assertEqual(block_at(W, L, pal, data, x, y + 1, z), "minecraft:air", "headroom for the NPC")
            self.assertEqual(block_at(W, L, pal, data, x, y + 2, z), "minecraft:air", "headroom for the NPC")
        gold = sum(1 for i in data if pal[i] == "minecraft:gold_block")
        self.assertEqual(gold, 2, "gold blocks are used only as NPC markers")

    def test_block_entities(self):
        for s in (self.full, self.noair):
            W, H, L, pal, data = decode(s)
            bes = s["Blocks"]["BlockEntities"]
            self.assertGreater(len(bes), 0)
            seen = set()
            for be in bes:
                x, y, z = be["Pos"]
                self.assertTrue(0 <= x < W and 0 <= y < H and 0 <= z < L)
                self.assertNotIn((x, y, z), seen); seen.add((x, y, z))
                st = parse_state(block_at(W, L, pal, data, x, y, z))[0]
                bid = be["Id"]
                self.assertEqual(be["Data"]["id"], bid)
                if bid == "minecraft:sign":
                    self.assertRegex(st, r"_(wall_)?sign$")
                elif bid == "minecraft:hanging_sign":
                    self.assertRegex(st, r"_hanging_sign$")
                elif bid == "minecraft:banner":
                    self.assertRegex(st, r"_banner$")
                    for p in be["Data"]["patterns"]:
                        self.assertIn(p["pattern"], BANNER_PATTERNS)
                else:
                    self.fail(f"unexpected block entity {bid}")
                if "sign" in bid:
                    for side in ("front_text", "back_text"):
                        msgs = be["Data"][side]["messages"]
                        self.assertEqual(len(msgs), 4)
                        self.assertEqual(msgs.elem_type, nbt.COMPOUND)
                        limit = HANGING_SIGN_WIDTH if "hanging" in bid else SIGN_WIDTH
                        for mm in msgs:
                            self.assertIsInstance(mm["text"], str)
                            self.assertLessEqual(text_width(mm["text"], "bold" in mm), limit)
                    self.assertEqual(be["Data"]["is_waxed"].v, 1)

    def test_notice_board_texts(self):
        texts = []
        for be in self.full["Blocks"]["BlockEntities"]:
            if be["Id"] == "minecraft:sign":
                texts.append(" ".join(sign_text(be)))
        for expected in EXPECTED_SIGNS:
            self.assertIn(" ".join(expected.split()), texts)

    def test_registry_rejects_bad_states(self):
        from generator.blocks import InvalidBlockState
        for name, props in (("oak_stairs", {"facing": "up"}), ("chain", {}), ("lantern", {"lit": "true"}),
                            ("stone_brick_wall", {"north": "true"})):
            with self.assertRaises(InvalidBlockState):
                self.reg.state(name, **props)
        with self.assertRaises(InvalidBlockState):
            self.reg.validate("minecraft:oak_stairs[facing=east]")      # incomplete state

    def test_leaves_persistent(self):
        for state in self.full["Blocks"]["Palette"]:
            if state.split("[")[0].endswith("_leaves"):
                self.assertIn("persistent=true", state)


if __name__ == "__main__":
    unittest.main()
