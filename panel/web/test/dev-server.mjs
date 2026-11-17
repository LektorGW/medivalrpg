// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Uruchamianie `wrangler dev` dla testów (osobny katalog persist, zbieranie logów, sprzątanie całego drzewa procesów).
import { spawn, execSync } from "node:child_process";
import { readFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const WRANGLER = join(ROOT, "node_modules", "wrangler", "bin", "wrangler.js");

export function readSecret() {
  const txt = readFileSync(join(ROOT, ".dev.vars"), "utf8");
  const m = /^PANEL_SECRET\s*=\s*"?([^"\r\n]+)"?/m.exec(txt);
  if (!m) throw new Error("Brak PANEL_SECRET w .dev.vars");
  return m[1].trim();
}

async function portBusy(port) {
  try {
    await fetch(`http://127.0.0.1:${port}/`, { signal: AbortSignal.timeout(1500) });
    return true;
  } catch {
    return false;
  }
}

export async function startDev(port, vars = {}) {
  if (await portBusy(port)) throw new Error(`Port ${port} jest zajęty — zamknij poprzedni wrangler dev albo ustaw PORT`);
  const persistDir = mkdtempSync(join(tmpdir(), "panel-test-"));
  const args = [WRANGLER, "dev", "--port", String(port), "--ip", "127.0.0.1", "--persist-to", persistDir, "--log-level", "info"];
  if (process.env.PANEL_TEST_DIST === "1") args.push("--config", join(ROOT, "dist", "wrangler.jsonc"), "--env-file", join(ROOT, ".dev.vars"));
  for (const [k, v] of Object.entries(vars)) args.push("--var", `${k}:${v}`);
  // Bez powłoki i npx: pid to sam wrangler, więc `taskkill /T` zabija całe drzewo (wrangler → cli → workerd).
  const proc = spawn(process.execPath, args, { cwd: ROOT, stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, WRANGLER_SEND_METRICS: "false", NO_COLOR: "1" } });
  const dev = { proc, persistDir, log: "", base: `http://127.0.0.1:${port}` };
  proc.stdout.on("data", (d) => (dev.log += d));
  proc.stderr.on("data", (d) => (dev.log += d));
  const until = Date.now() + 120000;
  while (Date.now() < until) {
    try {
      const r = await fetch(dev.base + "/api/status");
      if (r.status === 200) return dev;
    } catch {}
    if (proc.exitCode !== null) break;
    await sleep(500);
  }
  console.error(dev.log);
  stopDev(dev);
  throw new Error("wrangler dev nie wystartował");
}

export function stopDev(dev) {
  if (!dev) return;
  try {
    if (process.platform === "win32") execSync(`taskkill /pid ${dev.proc.pid} /T /F`, { stdio: "ignore" });
    else dev.proc.kill("SIGTERM");
  } catch {}
  setTimeout(() => {
    try {
      rmSync(dev.persistDir, { recursive: true, force: true });
    } catch {}
  }, 500).unref?.();
}
