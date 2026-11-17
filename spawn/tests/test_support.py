# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Physics sanity: nothing in the spawn may pop off, flow or convert after the first block update."""
from __future__ import annotations

import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from generator.blocks import Registry, base, parse_state, is_full_solid   # noqa: E402
from generator.build import build                                        # noqa: E402
from generator.world import DIRS, OPP, CW, CCW                            # noqa: E402

SOIL = {"grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt", "moss_block"}
PLANTS = {"short_grass", "fern", "poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium",
          "lily_of_the_valley"}
CROPS = {"wheat", "carrots", "potatoes", "beetroots"}
NEEDS_FLOOR = ("_carpet", "flower_pot", "potted_", "candle", "pressure_plate")


class SupportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.c = build(Registry())

    def g(self, x, y, z):
        return self.c.blocks.get((x, y, z))

    def test_everything_is_supported(self):
        problems = []
        for (x, y, z), st in self.c.blocks.items():
            b = base(st)
            p = parse_state(st)[1]
            below, above = self.g(x, y - 1, z), self.g(x, y + 1, z)

            def need(cond, why):
                if not cond:
                    problems.append(f"{(x, y, z)} {st}: {why}")
            if b in PLANTS:
                need(base(below or "air") in SOIL, "plant needs soil")
            elif b in CROPS:
                need(base(below or "air") == "farmland", "crop needs farmland")
            elif b == "lantern" or b.endswith("_lantern"):
                need((above if p["hanging"] == "true" else below) is not None, "lantern unsupported")
            elif b.endswith("_wall_banner") or b.endswith("_wall_sign") or b == "ladder":
                dx, dz = DIRS[OPP[p["facing"]]]
                need(self.g(x + dx, y, z + dz) is not None, "nothing behind")
            elif b.endswith("_wall_hanging_sign"):
                a, c = DIRS[CW[p["facing"]]], DIRS[CCW[p["facing"]]]
                need(self.g(x + a[0], y, z + a[1]) is not None or self.g(x + c[0], y, z + c[1]) is not None,
                     "wall hanging sign without a side support")
            elif b.endswith("_hanging_sign"):
                need(above is not None, "hanging sign without a ceiling")
            elif b.endswith("_sign") or b.endswith("_banner"):
                need(below is not None, "standing sign/banner without floor")
            elif b == "bell":
                need((above if p["attachment"] == "ceiling" else below) is not None, "bell unsupported")
            elif any(k in b for k in NEEDS_FLOOR):
                need(below is not None, "needs a floor")
            elif b.endswith("_door"):
                if p["half"] == "lower":
                    need(below is not None, "door without floor")
                    need(above is not None and base(above) == b and parse_state(above)[1]["half"] == "upper", "door top")
            elif b.endswith("_bed"):
                dx, dz = DIRS[p["facing"]]
                sgn = 1 if p["part"] == "foot" else -1
                other = self.g(x + sgn * dx, y, z + sgn * dz)
                need(other is not None and base(other) == b, "bed half missing")
            elif b == "water":
                for dx, dz in DIRS.values():
                    need(self.g(x + dx, y, z + dz) is not None, "water would flow sideways")
                need(below is not None, "water would flow down")
            elif b in ("gravel", "sand", "red_sand") or b.endswith("concrete_powder"):
                need(below is not None, "falling block")
            elif b in ("dirt_path", "farmland", "grass_block"):
                need(not is_full_solid(above), f"{b} under a solid block turns into dirt")
            elif b == "composter":
                need(p["level"] != "7", "composter level 7 ticks to 8")
        self.assertEqual(problems, [], "\n".join(problems[:40]))


if __name__ == "__main__":
    unittest.main()
