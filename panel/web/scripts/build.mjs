// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import { build } from "esbuild";
import { cp, mkdir, readFile, writeFile, readdir, rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { createHash } from "node:crypto";

const root = fileURLToPath(new URL("../", import.meta.url));
const dist = path.join(root, "dist");
if (path.dirname(path.resolve(dist)) !== path.resolve(root) || path.basename(dist) !== "dist") throw new Error("Nieprawidłowy katalog dist");
await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });
await cp(path.join(root, "public"), path.join(dist, "public"), { recursive: true });
const shared = { absWorkingDir: root, bundle: true, format: "esm", target: "es2022", legalComments: "eof", logLevel: "info" };
await build({ ...shared, entryPoints: ["public/js/app.js"], outfile: "dist/public/js/app.js", minify: true });
await build({ ...shared, entryPoints: ["src/index.js"], outfile: "dist/worker.js", external: ["cloudflare:*"], platform: "browser" });

// Preserve the authoritative bindings, migrations, asset routing and compatibility date.
const config = (await readFile(path.join(root, "wrangler.jsonc"), "utf8"))
  .replace('"./node_modules/wrangler/config-schema.json"', '"../node_modules/wrangler/config-schema.json"')
  .replace('"main": "src/index.js"', '"main": "worker.js",\n  "no_bundle": true');
if (!config.includes('"main": "worker.js"')) throw new Error("Nie rozpoznano ścieżki main w wrangler.jsonc");
await writeFile(path.join(dist, "wrangler.jsonc"), config);

const files = {};
async function inventory(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) await inventory(file);
    else if (entry.name !== "manifest.json") {
      const bytes = await readFile(file);
      files[path.relative(dist, file).replaceAll("\\", "/")] = { bytes: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex") };
    }
  }
}
await inventory(dist);
await writeFile(path.join(dist, "manifest.json"), JSON.stringify({ files }, null, 2) + "\n");
console.log(`Paczka dist: ${Object.keys(files).length} plików. Bez sekretów i bez wdrożenia.`);
