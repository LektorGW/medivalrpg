# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Generates the BetterModel player limb models with all Interakcje animations.

    python generate.py            # writes ../src/main/resources/bettermodel/...
    python generate.py --check    # exits 1 when the committed files are out of date
    python generate.py --report   # prints contact / reach diagnostics

Outputs:
  bettermodel/players/interakcje.bbmodel       head fully animated (no camera head tracking)
  bettermodel/players/interakcje_look.bbmodel  head follows the camera (loops: sitting, lying, holding hands)
  bettermodel/animations.json                  catalogue read by the plugin (clips, lengths, events, pairs)
Only the standard library is needed.
"""
import copy
import json
import math
import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import clips as clip_defs  # noqa: E402
import clips_ceremony as clip_ceremony  # noqa: E402
from motion import evaluate, to_keyframes  # noqa: E402
from rig import BONES, Rig, length, sub  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "src", "main", "resources", "bettermodel"))
MODELS = {"interakcje": False, "interakcje_look": True}  # model -> head follows the camera
NS = uuid.UUID("6f1d3c52-8a51-4c1e-9d8e-2b7a4f6e1c90")
FORMAT = 1


def fmt(v):
    s = "%.3f" % v
    s = s.rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def uid(*parts):
    return str(uuid.uuid5(NS, "/".join(str(p) for p in parts)))


def animation_json(model, clip, keyframes, group_uuid, group_name):
    animators = {}
    for bone, channels in keyframes.items():
        kfs = []
        for channel, points in channels.items():
            for i, (t, v) in enumerate(points):
                kfs.append({
                    "channel": channel,
                    "data_points": [{"x": fmt(v[0]), "y": fmt(v[1]), "z": fmt(v[2])}],
                    "uuid": uid(model, clip.name, bone, channel, i),
                    "time": round(t, 4),
                    "color": -1,
                    "interpolation": "linear",
                })
        animators[group_uuid[bone]] = {
            "name": group_name[bone],
            "type": "bone",
            "rotation_global": False,
            "quaternion_interpolation": False,
            "keyframes": kfs,
        }
    return {
        "uuid": uid(model, clip.name),
        "name": clip.name,
        "loop": "loop" if clip.loop else "once",
        "override": False,
        "length": clip.length,
        "snapping": 20,
        "selected": False,
        "anim_time_update": "",
        "blend_weight": "",
        "start_delay": "",
        "loop_delay": "",
        "animators": animators,
    }


def build_model(rig, model, head_tracking, model_clips):
    m = copy.deepcopy(rig.base)
    m["name"] = model
    m["model_identifier"] = ""
    group_uuid, group_name = {}, {}
    by_name = {g["name"]: g for g in m["groups"]}
    for bone, (group, _parent) in BONES.items():
        g = by_name[group]
        if bone == "head" and not head_tracking:
            g["name"] = "ph_head"  # player head skin, but no 'h_' tag -> the camera does not turn it
        group_uuid[bone] = g["uuid"]
        group_name[bone] = g["name"]
    m["animations"] = [animation_json(model, c, kf, group_uuid, group_name) for c, kf in model_clips]
    return m


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n"


# ---------------------------------------------------------------------------- validation

def validate(clip, keyframes):
    problems = []
    for bone, channels in keyframes.items():
        for channel, points in channels.items():
            tol = 0.6 if channel == "rotation" else 0.06
            for t, v in points:
                if any(math.isnan(x) or math.isinf(x) or abs(x) > 400 for x in v):
                    problems.append("%s %s %s: bad value %s at %.2f" % (clip.name, bone, channel, v, t))
            if points[0][0] != 0 or abs(points[-1][0] - clip.length) > 1e-6:
                problems.append("%s %s %s: keys do not span 0..%.2f" % (clip.name, bone, channel, clip.length))
            first, last = points[0][1], points[-1][1]
            if clip.loop:
                if max(abs(a - b) for a, b in zip(first, last)) > tol:
                    problems.append("%s %s %s: loop does not close %s -> %s" % (clip.name, bone, channel, first, last))
            else:
                for label, v in (("start", first), ("end", last)):
                    if max(abs(x) for x in v) > tol:
                        problems.append("%s %s %s: %s is not the rest pose %s" % (clip.name, bone, channel, label, v))
    return problems


def report(rig, clip, samples):
    """Contact / reach diagnostics (IK errors are collected by evaluate())."""
    lines = []
    for limb, (err, t) in sorted(clip.diag.items()):
        flag = "  <-- out of reach" if err > 0.8 else ""
        lines.append("  %-6s max IK error %.2f px at %.2fs%s" % (limb, err, t, flag))
    if clip.pair is not None:
        pair, role = clip.pair
        partner = pair.b if role == "a" else pair.a
        mapping = pair.partner_to_me(role)
        psamples = evaluate(rig, partner)
        dmin = 1e9
        for (t, pose), (_tp, ppose) in zip(samples, psamples):
            mine_head = rig.fk(pose)["head"].apply(rig.pivot["head"])
            their_head = mapping.then(rig.fk(ppose)["head"]).apply(rig.pivot["head"])
            dmin = min(dmin, length(sub(mine_head, their_head)))
        lines.append("  head-to-head min distance %.2f px (head cube 7.5 px)" % dmin)
    return lines


# ---------------------------------------------------------------------------- main

def generate(with_report=False):
    rig = Rig()
    all_clips = clip_defs.build_all(rig)
    animations = clip_defs.all_animations()
    work = clip_defs.work_clips()
    names = [c.name for c in all_clips]
    if len(set(names)) != len(names):
        raise SystemExit("duplicate clip names")
    per_model = {m: [] for m in MODELS}
    clip_meta = {}
    problems = []
    for clip in all_clips:
        if clip.model not in MODELS:
            raise SystemExit("unknown model " + clip.model)
        samples = evaluate(rig, clip)
        kf = to_keyframes(samples)
        problems += validate(clip, kf)
        if with_report:
            print("%s (%.2fs, %s, %s)" % (clip.name, clip.length, "loop" if clip.loop else "once", clip.model))
            for line in report(rig, clip, samples):
                print(line)
        per_model[clip.model].append((clip, kf))
        clip_meta[clip.name] = {
            "model": clip.model,
            "length": clip.length,
            "loop": clip.loop,
            "events": {k: sorted(v) for k, v in clip.events.items()},
        }
        if clip.blend is not None:
            clip_meta[clip.name]["blend"] = clip.blend
        prop = getattr(clip, "prop", None)
        if prop is not None:
            partner_samples = mapping = None
            if clip.pair is not None:
                pair, role = clip.pair
                partner = pair.b if role == "a" else pair.a
                partner_samples = evaluate(rig, partner)
                mapping = pair.partner_to_me(role)
            track = clip_ceremony.prop_track(rig, clip, samples, partner_samples, mapping)
            clip_meta[clip.name]["props"] = {prop[0]: {"set": prop[2], "points": [list(p) for p in track]}}
        if with_report and getattr(clip, "sword_errors", None):
            for label, err in clip.sword_errors.items():
                print("  sword tip on %s: %.2f px from target" % (label, err))
    for name, clip_name in work.items():
        if clip_name not in clip_meta:
            problems.append("work animation %s references unknown clip %s" % (name, clip_name))
        elif clip_meta[clip_name]["model"] != "interakcje":
            problems.append("work animation %s must use the interakcje model" % name)
    for anim_id, spec in animations.items():
        refs = [r["clip"] for r in spec["roles"].values()] + list(spec.get("mirror", {}).values())
        for ref in refs:
            if ref not in clip_meta:
                problems.append("animation %s references unknown clip %s" % (anim_id, ref))
    if problems:
        for p in problems:
            print("ERROR: " + p)
        raise SystemExit(1)
    files = {}
    for model, head_tracking in MODELS.items():
        files[os.path.join("players", model + ".bbmodel")] = dump(build_model(rig, model, head_tracking, per_model[model]))
    files["animations.json"] = json.dumps({
        "format": FORMAT,
        "generator": "animations-src/generate.py",
        "models": list(MODELS),
        "clips": clip_meta,
        "animations": animations,
        "work": work,
    }, ensure_ascii=False, indent=2) + "\n"
    return files


def main(argv):
    check = "--check" in argv
    files = generate(with_report="--report" in argv)
    stale = []
    for rel, content in files.items():
        path = os.path.join(OUT, rel)
        old = None
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                old = f.read()
        if old != content:
            stale.append(rel)
            if not check:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8", newline="\n") as f:
                    f.write(content)
    if check:
        if stale:
            print("out of date: " + ", ".join(stale))
            return 1
        print("up to date")
        return 0
    for rel, content in files.items():
        print("%-36s %7d B%s" % (rel, len(content.encode("utf-8")), "  (changed)" if rel in stale else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
