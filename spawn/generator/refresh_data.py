# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Refresh spawn/data/ from a Paper server jar.

  python -m generator.refresh_data <paper.jar> [java.exe]

* version.json      -> data/version-26.3.json   (DataVersion = world_version)
* banner patterns   -> data/banner_patterns-26.3.json (from the bundled vanilla server jar)
* blocks.json       -> data/blocks-26.3.json   (data generator: --reports, run in a temp dir)
"""
from __future__ import annotations

import glob
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")


def main(jar: str, java: str | None = None):
    java = java or glob.glob(os.path.expanduser(r"~\.gradle\jdks\eclipse_adoptium-25*\bin\java.exe"))[0]
    z = zipfile.ZipFile(jar)
    version = z.read("version.json")
    with open(os.path.join(DATA, "version-26.3.json"), "wb") as f:
        f.write(version)
    print("DataVersion:", json.loads(version)["world_version"])
    tmp = tempfile.mkdtemp(prefix="mcreports-")
    try:
        srv = os.path.dirname(os.path.abspath(jar))
        for sub in ("cache", "libraries", "versions"):           # avoid re-downloading the vanilla jar
            if os.path.isdir(os.path.join(srv, sub)):
                shutil.copytree(os.path.join(srv, sub), os.path.join(tmp, sub))
        subprocess.run([java, "-DbundlerMainClass=net.minecraft.data.Main", "-jar", os.path.abspath(jar), "--reports"],
                       cwd=tmp, check=True)
        shutil.copy(os.path.join(tmp, "generated", "reports", "blocks.json"), os.path.join(DATA, "blocks-26.3.json"))
        inner = glob.glob(os.path.join(tmp, "versions", "*", "*.jar"))[0]
        with zipfile.ZipFile(inner) as vz:
            pats = sorted({"minecraft:" + n.rsplit("/", 1)[-1][:-5] for n in vz.namelist()
                           if n.startswith("data/minecraft/banner_pattern/") and n.endswith(".json")})
        with open(os.path.join(DATA, "banner_patterns-26.3.json"), "w", encoding="utf-8") as f:
            json.dump(pats, f, indent=1)
        print("blocks.json and", len(pats), "banner patterns written")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main(*sys.argv[1:])
