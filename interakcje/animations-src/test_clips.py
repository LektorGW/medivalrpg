# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Unit tests of the clip generator (standard library only):  python -m unittest test_clips  (in animations-src)."""
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import clips  # noqa: E402
import clips_ceremony  # noqa: E402
import clips_npc  # noqa: E402
import generate  # noqa: E402
from motion import evaluate, to_keyframes  # noqa: E402
from rig import Rig, length, sub  # noqa: E402

RIG = Rig()
ALL = {c.name: c for c in clips.build_all(RIG)}


class GeneratorTest(unittest.TestCase):

    def test_every_clip_validates(self):
        for c in ALL.values():
            kf = to_keyframes(evaluate(RIG, c))
            self.assertEqual([], generate.validate(c, kf), c.name)

    def test_committed_files_are_up_to_date(self):
        files = generate.generate()
        for rel, content in files.items():
            with open(os.path.join(generate.OUT, rel), encoding="utf-8") as f:
                self.assertEqual(content, f.read(), rel + " is stale: run python generate.py")

    def test_work_animations(self):
        work = clips.work_clips()
        self.assertEqual(12, len(work))
        for name, clip in work.items():
            self.assertIn(clip, ALL, name)
            self.assertEqual("interakcje", ALL[clip].model, name)
        for name in ("budowa", "kilof", "motyka", "siekiera", "kowadlo", "straz", "odpoczynek", "zaproszenie",
                     "noszenie_plecy", "noszenie_rece"):
            self.assertTrue(ALL[work[name]].loop, name + " loops")
        for name in ("ziewanie", "salut"):
            self.assertFalse(ALL[work[name]].loop, name + " is one-off")

    def test_tool_strikes_have_impacts(self):
        for clip, n in (("npc_hammer", 2), ("npc_pickaxe", 1), ("npc_hoe", 1), ("npc_axe", 1), ("npc_anvil", 3)):
            self.assertEqual(n, len(ALL[clip].events.get("impact", [])), clip)

    def test_loops_are_seamless(self):
        for c in ALL.values():
            if not c.loop:
                continue
            s = evaluate(RIG, c)
            first, last = s[0][1], s[-1][1]
            for bone in first:
                for ch in ("rot", "pos"):
                    d = max(abs(a - b) for a, b in zip(first[bone][ch], last[bone][ch]))
                    self.assertLess(d, 0.7, "%s %s %s jumps at the loop point" % (c.name, bone, ch))

    def test_pairs_are_mutual(self):
        for a, b in (("handshake_a", "handshake_b"), ("toast_a", "toast_b"), ("courtbow_a", "courtbow_b"),
                     ("knight_a", "knight_b"), ("fealty_a", "fealty_b"), ("crown_a", "crown_b")):
            pa, ra = ALL[a].pair
            pb, rb = ALL[b].pair
            self.assertIs(pa, pb)
            self.assertEqual(("a", "b"), (ra, rb))
            self.assertEqual(ALL[a].length, ALL[b].length, a)

    def test_heads_never_collide(self):
        for name in ("handshake_a", "toast_a", "courtbow_a", "knight_a", "fealty_a", "crown_a"):
            c = ALL[name]
            pair, role = c.pair
            partner = pair.b
            mapping = pair.partner_to_me(role)
            dmin = min(length(sub(RIG.fk(p)["head"].apply(RIG.pivot["head"]),
                                  mapping.then(RIG.fk(q)["head"]).apply(RIG.pivot["head"])))
                       for (_t, p), (_u, q) in zip(evaluate(RIG, c), evaluate(RIG, partner)))
            self.assertGreater(dmin, 7.5, name + " heads intersect (head cube 7.5 px)")

    def test_handshake_hands_meet(self):
        a, b = ALL["handshake_a"], ALL["handshake_b"]
        mapping = a.pair[0].partner_to_me("a")
        sa, sb = evaluate(RIG, a), evaluate(RIG, b)
        i = int(1.2 * 20)
        ha = RIG.end_effector(RIG.fk(sa[i][1]), "r_arm")
        hb = mapping.apply(RIG.end_effector(RIG.fk(sb[i][1]), "r_arm"))
        self.assertLess(length(sub(ha, hb)), 1.0, "right hands together while shaking")

    def test_knighting_sword_reaches_both_shoulders(self):
        errs = ALL["knight_a"].sword_errors
        for where, err in errs.items():
            self.assertLess(err, 1.0, "sword tip on " + where)

    def test_fealty_lord_encloses_the_vassal_hands(self):
        a, b = ALL["fealty_a"], ALL["fealty_b"]
        to_b = b.pair[0].partner_to_me("b")
        sa, sb = evaluate(RIG, a), evaluate(RIG, b)
        i = int(3.0 * 20)
        joined = to_b.apply(RIG.end_effector(RIG.fk(sa[i][1]), "r_arm"))
        for limb in ("r_arm", "l_arm"):
            hand = RIG.end_effector(RIG.fk(sb[i][1]), limb)
            self.assertLess(length(sub(hand, joined)), 4.5, limb + " of the lord by the vassal's hands")

    def test_crown_track(self):
        a = ALL["crown_a"]
        sa = evaluate(RIG, a)
        pair, role = a.pair
        track = clips_ceremony.prop_track(RIG, a, sa, evaluate(RIG, pair.b), pair.partner_to_me(role))
        self.assertGreater(track[0][0], 0.0)
        above = [p for p in track if 1.6 <= p[0] <= 2.8]
        on = [p for p in track if p[0] > clips_ceremony.CROWN_SET + 0.1 and p[0] < 5.5]
        self.assertTrue(above and on)
        self.assertGreater(min(p[2] for p in above), max(p[2] for p in on), "held up above, then on the head")
        for p in on:  # on the kneeling ruler's head, in front of the officiant
            self.assertLess(p[3], -6.0)

    def test_no_nan(self):
        for c in ALL.values():
            for _t, pose in evaluate(RIG, c):
                for bone in pose.values():
                    for v in bone["rot"] + bone["pos"]:
                        self.assertFalse(math.isnan(v), c.name)


if __name__ == "__main__":
    unittest.main()
