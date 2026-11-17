# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Block state registry validated against the server's data report (blocks.json).

Every state used by the generator goes through :func:`Registry.state`, which
fails loudly on an unknown block, property or value and fills in defaults so
that each palette entry is a complete, canonical block state string.
"""
from __future__ import annotations

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_REPORT = os.path.join(HERE, "..", "data", "blocks-26.3.json")


class InvalidBlockState(ValueError):
    pass


class Registry:
    def __init__(self, report_path: str = DEFAULT_REPORT):
        with open(report_path, encoding="utf-8") as f:
            raw = json.load(f)
        self.props: dict[str, dict[str, list[str]]] = {}
        self.defaults: dict[str, dict[str, str]] = {}
        for name, info in raw.items():
            self.props[name] = info.get("properties", {}) or {}
            default = next((s for s in info["states"] if s.get("default")), info["states"][0])
            self.defaults[name] = default.get("properties", {}) or {}
        self._cache: dict = {}

    def has(self, name: str) -> bool:
        return _ns(name) in self.props

    def state(self, name: str, **props) -> str:
        key = (name, tuple(sorted(props.items())))
        hit = self._cache.get(key)
        if hit is not None:
            return hit
        full = _ns(name)
        if full not in self.props:
            raise InvalidBlockState(f"Unknown block '{full}' in MC 26.3 registry")
        allowed = self.props[full]
        values = dict(self.defaults[full])
        for k, v in props.items():
            if isinstance(v, bool):
                v = "true" if v else "false"
            v = str(v)
            if k not in allowed:
                raise InvalidBlockState(f"Block '{full}' has no property '{k}' (allowed: {sorted(allowed)})")
            if v not in allowed[k]:
                raise InvalidBlockState(f"Block '{full}' property '{k}' cannot be '{v}' (allowed: {allowed[k]})")
            values[k] = v
        s = full if not values else full + "[" + ",".join(f"{k}={values[k]}" for k in sorted(values)) + "]"
        self._cache[key] = s
        return s

    def validate(self, state: str) -> None:
        """Re-validate a canonical state string (used by the tests on the palette)."""
        name, props = parse_state(state)
        if name not in self.props:
            raise InvalidBlockState(f"Unknown block '{name}'")
        allowed = self.props[name]
        if set(props) != set(allowed):
            raise InvalidBlockState(f"State '{state}' is not complete; expected properties {sorted(allowed)}")
        for k, v in props.items():
            if v not in allowed[k]:
                raise InvalidBlockState(f"State '{state}': bad value {k}={v}")


def _ns(name: str) -> str:
    return name if ":" in name else "minecraft:" + name


def parse_state(state: str):
    if "[" in state:
        name, rest = state.split("[", 1)
        rest = rest.rstrip("]")
        props = dict(kv.split("=", 1) for kv in rest.split(",")) if rest else {}
    else:
        name, props = state, {}
    return name, props


def base(state: str) -> str:
    """'minecraft:oak_stairs[...]' -> 'oak_stairs'."""
    return state.split("[", 1)[0].split(":", 1)[-1]


# ---- shape classification (used for fence/wall/pane connections) ----------
_PARTIAL_KEYWORDS = (
    "stairs", "slab", "fence", "_wall", "pane", "iron_bars", "door", "trapdoor", "sign", "banner",
    "lantern", "chain", "torch", "carpet", "button", "pressure_plate", "flower_pot", "potted_", "leaves",
    "water", "lava", "air", "anvil", "bell", "campfire", "chest", "cauldron", "grindstone", "lectern",
    "brewing_stand", "rail", "ladder", "vine", "end_rod", "lightning_rod", "skull", "head", "candle", "_bed",
    "decorated_pot", "short_grass", "tall_grass", "fern", "sapling", "berry", "scaffolding", "snow",
    "cake", "enchanting_table", "stonecutter", "dirt_path", "farmland", "composter", "shelf", "hopper",
    "poppy", "dandelion", "tulip", "orchid", "allium", "azure_bluet", "oxeye", "cornflower", "lily",
    "rose_bush", "peony", "lilac", "sunflower", "bush", "flowering_azalea", "azalea", "petals", "wildflowers",
    "torchflower", "pitcher", "mushroom", "sea_pickle", "glow_lichen", "wheat", "carrots", "potatoes",
    "beetroots", "brewing_stand", "iron_chain",
)
_FULL_EXCEPTIONS = ("leaves", "barrier", "carved_pumpkin", "jack_o_lantern", "melon", "pumpkin", "shulker_box")


def is_full_solid(state: str | None) -> bool:
    """Heuristic: full, sturdy cube that fences/walls/panes connect to."""
    if state is None:
        return False
    b = base(state)
    if b in ("moss_block", "hay_block", "barrel", "lodestone", "smithing_table", "bookshelf",
             "chiseled_bookshelf", "crafting_table", "glass", "dried_kelp_block", "target", "loom",
             "cartography_table", "fletching_table", "blast_furnace", "furnace", "smoker"):
        return True
    if any(e in b for e in _FULL_EXCEPTIONS):
        return False
    if b.endswith("_wall") and not any(x in b for x in ("sign", "banner", "torch", "head", "skull", "fan")):
        return False
    if b.endswith("_block") and b not in ("hay_block",):
        return True
    return not any(k in b for k in _PARTIAL_KEYWORDS)


def is_stairs(state: str | None) -> bool:
    return state is not None and base(state).endswith("_stairs")


def is_fence(state: str | None) -> bool:
    return state is not None and base(state).endswith("_fence")


def is_fence_gate(state: str | None) -> bool:
    return state is not None and base(state).endswith("_fence_gate")


def is_wall(state: str | None) -> bool:
    if state is None:
        return False
    b = base(state)
    return b.endswith("_wall") and not any(x in b for x in ("sign", "banner", "torch", "head", "skull", "fan"))


def is_pane_like(state: str | None) -> bool:
    if state is None:
        return False
    b = base(state)
    return b == "iron_bars" or b.endswith("glass_pane")


def is_leaves(state: str | None) -> bool:
    return state is not None and base(state).endswith("_leaves")


def is_log(state: str | None) -> bool:
    if state is None:
        return False
    b = base(state)
    return b.endswith("_log") or b.endswith("_wood")
