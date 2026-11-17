# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Generates the "medival-pory-roku" datapack: seasonal variants of the vanilla overworld biomes.

Reads the vanilla biome definitions straight from the Paper 26.3 server jar (data/minecraft/worldgen/biome/*.json),
writes data/pory/worldgen/biome/<season>/<biome>.json and a deterministic zip (fixed timestamps, sorted entries).

The variants are only ever shown to clients (the Pory plugin rewrites biome ids in chunk packets); the server never
places them in the world, so they carry no features, carvers or mob spawns. Summer = vanilla (no variants).

Usage: python -I generate_datapack.py <paper-26.3.jar> <output-dir>
"""
import json
import sys
import zipfile
from pathlib import Path

PACK_NAME = "medival-pory-roku"
PACK_FORMAT = 121  # Minecraft 26.3 data pack format (version.json: data_major 121)
SEASONS = ("wiosna", "jesien", "zima")

SKIP = {
    # underground, nether, end: never seasonal
    "lush_caves", "dripstone_caves", "deep_dark", "sulfur_caves",
    "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas",
    "the_end", "end_barrens", "end_highlands", "end_midlands", "small_end_islands", "the_void",
    # already wintry all year: unchanged
    "snowy_plains", "ice_spikes", "snowy_taiga", "snowy_slopes", "frozen_peaks", "jagged_peaks", "snowy_beach",
    "frozen_river", "frozen_ocean", "deep_frozen_ocean", "grove",
}

GROUP = {
    "forest": "forest", "flower_forest": "forest", "windswept_forest": "forest",
    "birch_forest": "birch", "old_growth_birch_forest": "birch",
    "dark_forest": "dark",
    "swamp": "swamp", "mangrove_swamp": "swamp",
    "taiga": "conifer", "old_growth_pine_taiga": "conifer", "old_growth_spruce_taiga": "conifer",
    "jungle": "jungle", "sparse_jungle": "jungle", "bamboo_jungle": "jungle",
    "desert": "dry", "savanna": "dry", "savanna_plateau": "dry", "windswept_savanna": "dry",
    "badlands": "dry", "eroded_badlands": "dry", "wooded_badlands": "dry",
    "cherry_grove": "cherry", "pale_garden": "pale", "dappled_forest": "dappled",
}

# Vanilla colours from the grass/foliage colormaps (biomes without an explicit override), dark forest already darkened.
BASE = {
    "plains": ("#91BD59", "#77AB2F"), "sunflower_plains": ("#91BD59", "#77AB2F"), "beach": ("#91BD59", "#77AB2F"),
    "forest": ("#79C05A", "#59AE30"), "flower_forest": ("#79C05A", "#59AE30"), "dark_forest": ("#507A32", "#59AE30"),
    "birch_forest": ("#88BB67", "#6BA941"), "old_growth_birch_forest": ("#88BB67", "#6BA941"),
    "meadow": ("#83BB6D", "#63A948"), "river": ("#8EB971", "#71A74D"),
    "ocean": ("#8EB971", "#71A74D"), "deep_ocean": ("#8EB971", "#71A74D"), "cold_ocean": ("#8EB971", "#71A74D"),
    "deep_cold_ocean": ("#8EB971", "#71A74D"), "lukewarm_ocean": ("#8EB971", "#71A74D"),
    "deep_lukewarm_ocean": ("#8EB971", "#71A74D"), "warm_ocean": ("#8EB971", "#71A74D"),
    "taiga": ("#86B783", "#68A464"), "old_growth_spruce_taiga": ("#86B783", "#68A464"),
    "old_growth_pine_taiga": ("#86B87F", "#68A55F"),
    "windswept_hills": ("#8AB689", "#6DA36B"), "windswept_gravelly_hills": ("#8AB689", "#6DA36B"),
    "windswept_forest": ("#8AB689", "#6DA36B"), "stony_shore": ("#8AB689", "#6DA36B"),
    "stony_peaks": ("#9ABE4B", "#82AC1E"),
    "jungle": ("#59C93C", "#30BB0B"), "bamboo_jungle": ("#59C93C", "#30BB0B"), "sparse_jungle": ("#64C73F", "#3EB80F"),
    "mushroom_fields": ("#55C93F", "#2BBB0F"),
    "swamp": ("#6A7039", "#6A7039"), "mangrove_swamp": ("#6A7039", "#8DB127"),
    "desert": ("#BFB755", "#AEA42A"), "savanna": ("#BFB755", "#AEA42A"), "savanna_plateau": ("#BFB755", "#AEA42A"),
    "windswept_savanna": ("#BFB755", "#AEA42A"),
}
DEFAULT_BASE = ("#91BD59", "#77AB2F")

# (grass target, weight), (foliage target, weight) per season and group; None = no variant for that season.
PALETTE = {
    "wiosna": {
        "default": (("#8ED65A", .45), ("#6CC24A", .45)),
        "forest": (("#8ED65A", .45), ("#6CC24A", .45)),
        "birch": (("#8ED65A", .45), ("#6CC24A", .45)),
        "dark": (("#7CC24E", .35), ("#5FB840", .35)),
        "swamp": (("#8ED65A", .25), ("#6CC24A", .25)),
        "conifer": (("#8ED65A", .15), ("#6CC24A", .15)),
        "jungle": (("#8ED65A", .15), ("#6CC24A", .15)),
        "dry": (("#A8BF5A", .35), ("#8DB443", .35)),
        "cherry": None, "pale": None, "dappled": None,
    },
    "jesien": {
        "default": (("#B5A64E", .70), ("#D98A2B", .85)),
        "forest": (("#AFA04A", .70), ("#D2602A", .85)),
        "birch": (("#C2AE52", .70), ("#E3B33C", .90)),
        "dark": (("#8E8A3E", .50), ("#A8461F", .80)),
        "swamp": (("#8F8640", .55), ("#8A7A3A", .70)),
        "conifer": (("#A39E5A", .40), ("#8A7F3A", .25)),
        "jungle": (("#A7A645", .30), ("#B9A23A", .35)),
        "dry": (("#C4A960", .40), ("#B88A3A", .40)),
        "cherry": (("#C2AE52", .50), ("#E0A040", .60)),
        "pale": None, "dappled": None,
    },
    "zima": {
        "default": (("#D0D8CC", .75), ("#8C7B65", .90)),
        "forest": (("#D0D8CC", .75), ("#8C7B65", .90)),
        "birch": (("#D0D8CC", .75), ("#8C7B65", .90)),
        "dark": (("#B4BDB0", .70), ("#6E5F4E", .75)),
        "swamp": (("#A9B09A", .70), ("#6F6650", .70)),
        "conifer": (("#BCC8C0", .65), ("#4E7058", .40)),
        "jungle": (("#A9B79A", .55), ("#6E7B4E", .50)),
        "dry": (("#AFA67E", .30), ("#8E8355", .30)),
        "cherry": (("#C3CCC0", .70), ("#9A8070", .60)),
        "pale": (("#C0C4BC", .50), ("#9A9C92", .30)),
        "dappled": (("#BDB8A8", .60), ("#8C6A50", .60)),
    },
}
DRY_FOLIAGE = {"jesien": "#A0522D", "zima": "#7A6A55"}  # leaf litter
WINTER_TEMPERATURE = -0.25  # below 0.15 everywhere: rain falls as snow on the client
WINTER_WATER = ("#3D57D6", .35)
WINTER_SKY = ("#B4C2D6", .35)
WINTER_FOG = ("#C8D0DA", .30)
AUTUMN_SKY = ("#A9B7CC", .12)


def rgb(h):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def hexc(c):
    return "#%02x%02x%02x" % tuple(max(0, min(255, round(v))) for v in c)


def blend(base, target, w):
    a, b = rgb(base), rgb(target)
    return hexc(tuple(a[i] * (1 - w) + b[i] * w for i in range(3)))


def variant(name, src, season):
    group = GROUP.get(name, "default")
    pal = PALETTE[season].get(group)
    if pal is None:
        return None
    effects = dict(src.get("effects", {}))
    base_grass, base_foliage = BASE.get(name, DEFAULT_BASE)
    base_grass = effects.get("grass_color", base_grass)
    base_foliage = effects.get("foliage_color", base_foliage)
    (gt, gw), (ft, fw) = pal
    effects["grass_color"] = blend(base_grass, gt, gw)
    effects["foliage_color"] = blend(base_foliage, ft, fw)
    # swamp / dark forest modifiers would override or re-tint the seasonal grass: colours above are final
    effects.pop("grass_color_modifier", None)
    if season in DRY_FOLIAGE:
        effects["dry_foliage_color"] = blend(effects.get("dry_foliage_color", "#7B5334"), DRY_FOLIAGE[season], .7)

    attributes = {k: v for k, v in src.get("attributes", {}).items() if not k.startswith("minecraft:gameplay/")}
    out = {
        "has_precipitation": src["has_precipitation"],
        "temperature": src["temperature"],
        "downfall": src["downfall"],
        "attributes": attributes,
        "effects": effects,
        "carvers": [],
        "features": [],
    }
    if "temperature_modifier" in src:
        out["temperature_modifier"] = src["temperature_modifier"]
    sky = "minecraft:visual/sky_color"
    if season == "zima":
        if src["has_precipitation"]:
            out["temperature"] = min(src["temperature"], WINTER_TEMPERATURE)
            effects["water_color"] = blend(effects.get("water_color", "#3F76E4"), *WINTER_WATER)
            if "minecraft:visual/water_fog_color" in attributes:
                attributes["minecraft:visual/water_fog_color"] = blend(
                    attributes["minecraft:visual/water_fog_color"], *WINTER_WATER)
        if sky in attributes:
            attributes[sky] = blend(attributes[sky], *WINTER_SKY)
        if "minecraft:visual/fog_color" in attributes:
            attributes["minecraft:visual/fog_color"] = blend(attributes["minecraft:visual/fog_color"], *WINTER_FOG)
    elif season == "jesien" and sky in attributes:
        attributes[sky] = blend(attributes[sky], *AUTUMN_SKY)
    return out


def generate(jar_path, out_dir):
    out_dir = Path(out_dir)
    root = out_dir / PACK_NAME
    files = {}
    with zipfile.ZipFile(jar_path) as jar:
        names = sorted(n for n in jar.namelist()
                       if n.startswith("data/minecraft/worldgen/biome/") and n.endswith(".json"))
        for n in names:
            biome = n.rsplit("/", 1)[1][:-5]
            if biome in SKIP:
                continue
            src = json.loads(jar.read(n))
            for season in SEASONS:
                v = variant(biome, src, season)
                if v is not None:
                    files["data/pory/worldgen/biome/%s/%s.json" % (season, biome)] = v
    files["pack.mcmeta"] = {"pack": {
        "description": "MEDIVALRPG — sezonowe warianty biomów dla pluginu Pory. © 2026 Maciej Kaczmarczyk — lektoro.pl",
        "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}}
    if root.exists():
        for p in sorted(root.rglob("*"), reverse=True):
            p.unlink() if p.is_file() else p.rmdir()
    for rel, data in sorted(files.items()):
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    zip_path = out_dir / (PACK_NAME + ".zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in sorted(files):
            info = zipfile.ZipInfo(rel, date_time=(2026, 10, 31, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, (root / rel).read_bytes())
    counts = {s: sum(1 for k in files if k.startswith("data/pory/worldgen/biome/%s/" % s)) for s in SEASONS}
    return zip_path, counts


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    path, counts = generate(sys.argv[1], sys.argv[2])
    print(path, counts)
