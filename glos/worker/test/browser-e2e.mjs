// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Test end-to-end w prawdziwym Chrome (headless, sztuczny mikrofon): dwie strony klienta + udawany plugin.
// Sprawdza WebRTC mesh, przepływ dźwięku przez graf Web Audio, VAD → speaking, tryb, wyciszenie, kick.
// Uruchomienie: `npm run test:e2e` (wymaga Chrome; ścieżka w CHROME albo domyślna instalacja).
import { spawn, execSync } from "node:child_process";
import { readFileSync, mkdtempSync, rmSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes, randomUUID } from "node:crypto";
import WebSocket from "ws";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PORT = 8798;
const CDP_PORT = 9333;
const BASE = `http://127.0.0.1:${PORT}`;
const SECRET = /^PLUGIN_SECRET\s*=\s*"?([^"\r\n]+)"?/m.exec(readFileSync(join(ROOT, ".dev.vars"), "utf8"))[1].trim();
const CHROME =
  process.env.CHROME ||
  ["C:/Program Files/Google/Chrome/Application/chrome.exe", "/usr/bin/google-chrome", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"].find(existsSync);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const tok = () => [...randomBytes(32)].map((b) => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[b % 62]).join("");
const procs = [];
const dirs = [];

function killAll() {
  for (const p of procs) {
    try {
      if (process.platform === "win32") execSync(`taskkill /pid ${p.pid} /T /F`, { stdio: "ignore" });
      else p.kill("SIGKILL");
    } catch {}
  }
  for (const d of dirs) {
    try {
      rmSync(d, { recursive: true, force: true });
    } catch {}
  }
}

async function waitHttp(url, ms) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    try {
      const r = await fetch(url);
      if (r.ok) return r;
    } catch {}
    await sleep(400);
  }
  throw new Error("brak odpowiedzi: " + url);
}

// ------------------------------------------------------------- CDP

class Page {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl, { perMessageDeflate: false });
    this.id = 0;
    this.pending = new Map();
    this.logs = [];
    this.ws.on("message", (d) => {
      const m = JSON.parse(d.toString());
      if (m.id && this.pending.has(m.id)) {
        const { res, rej } = this.pending.get(m.id);
        this.pending.delete(m.id);
        if (m.error) rej(new Error(m.error.message));
        else res(m.result);
      } else if (m.method === "Runtime.consoleAPICalled") {
        this.logs.push(m.params.type + ": " + m.params.args.map((a) => a.value ?? a.description).join(" "));
      } else if (m.method === "Runtime.exceptionThrown") {
        this.logs.push("EXCEPTION: " + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
      }
    });
    this.ready = new Promise((r) => this.ws.on("open", r));
  }
  cmd(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((res, rej) => this.pending.set(id, { res, rej }));
  }
  async eval(expr, gesture = false) {
    const r = await this.cmd("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true, userGesture: gesture });
    if (r.exceptionDetails) throw new Error("eval: " + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    return r.result.value;
  }
}

async function openPage(url) {
  const r = await fetch(`http://127.0.0.1:${CDP_PORT}/json/new?${encodeURIComponent(url)}`, { method: "PUT" });
  const t = await r.json();
  const p = new Page(t.webSocketDebuggerUrl);
  await p.ready;
  await p.cmd("Runtime.enable");
  return p;
}

async function until(fn, ms, what) {
  const end = Date.now() + ms;
  let last;
  while (Date.now() < end) {
    try {
      last = await fn();
      if (last) return last;
    } catch (e) {
      last = e.message;
    }
    await sleep(250);
  }
  throw new Error(`timeout: ${what} (ostatnio: ${JSON.stringify(last)})`);
}

// ------------------------------------------------------------- scenariusz

let ok = 0;
let fail = 0;
async function step(name, fn) {
  try {
    await fn();
    ok++;
    console.log("  OK   " + name);
  } catch (e) {
    fail++;
    console.log("  FAIL " + name + "\n       " + e.message);
  }
}

async function main() {
  if (!CHROME) throw new Error("Nie znaleziono Chrome (ustaw CHROME=ścieżka)");
  const persist = mkdtempSync(join(tmpdir(), "glos-e2e-"));
  const profile = mkdtempSync(join(tmpdir(), "glos-chrome-"));
  dirs.push(persist, profile);

  const dev = spawn(`npx wrangler dev --port ${PORT} --ip 127.0.0.1 --persist-to "${persist}" --log-level warn`, { cwd: ROOT, shell: true, stdio: "ignore" });
  procs.push(dev);
  await waitHttp(BASE + "/", 90000);

  const chrome = spawn(CHROME, [
    "--headless=new", `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`,
    "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream", "--autoplay-policy=no-user-gesture-required",
    "--no-first-run", "--no-default-browser-check", "about:blank",
  ], { stdio: "ignore" });
  procs.push(chrome);
  await waitHttp(`http://127.0.0.1:${CDP_PORT}/json/version`, 30000);

  // udawany plugin
  const P = new WebSocket(BASE.replace("http", "ws") + "/plugin", { headers: { Authorization: "Bearer " + SECRET } });
  const pmsgs = [];
  P.on("message", (d) => pmsgs.push(JSON.parse(d.toString())));
  await new Promise((r, j) => (P.on("open", r), P.on("error", j)));
  P.send(JSON.stringify({ t: "hello", v: 1 }));

  // uuid A < B, żeby wiedzieć, kto oferuje
  const [idA, idB] = [randomUUID(), randomUUID()].sort();
  // „intruz” (zmodyfikowany klient): najmniejszy uuid, więc to on oferuje B
  const idZ = "00000000-0000-4000-8000-" + randomBytes(6).toString("hex");
  const tA = tok();
  const tB = tok();
  P.send(JSON.stringify({ t: "token", token: tA, id: idA, n: "Alfa" }));
  P.send(JSON.stringify({ t: "token", token: tB, id: idB, n: "Beta" }));
  await sleep(200);

  // Stan świata wysyłany 5 Hz (jak plugin). Zmienne sterowane w trakcie testu.
  const world = { dist: 4, mutedA: false, uwB: false, modeA: "normal" };
  let connected = [];
  const stateTimer = setInterval(() => {
    const last = [...pmsgs].reverse().find((m) => m.t === "clients");
    connected = last ? last.ids : [];
    if (!connected.length) return;
    const to = {};
    if (connected.includes(idA)) {
      to[idA] = {
        me: { yaw: 0, pitch: 0, mode: world.modeA, range: 24, muted: world.mutedA, uw: false },
        peers: connected.includes(idB) ? [{ id: idB, n: "Beta", x: world.dist, y: 0, z: 0, r: 24, m: false, mu: false }] : [],
      };
    }
    if (connected.includes(idZ)) {
      // Z „widzi” B w zasięgu, ale B nie ma Z w swoich peers (Z jest daleko)
      to[idZ] = {
        me: { yaw: 0, pitch: 0, mode: "normal", range: 24, muted: false, uw: false },
        peers: connected.includes(idB) ? [{ id: idB, n: "Beta", x: 4, y: 0, z: 0, r: 24, m: false, mu: false }] : [],
      };
    }
    if (connected.includes(idB)) {
      to[idB] = {
        me: { yaw: 90, pitch: 0, mode: "normal", range: 24, muted: false, uw: world.uwB },
        peers: connected.includes(idA) ? [{ id: idA, n: "Alfa", x: -world.dist, y: 0, z: 0, r: 24, m: world.uwB, mu: world.mutedA }] : [],
      };
    }
    P.send(JSON.stringify({ t: "state", to }));
  }, 200);

  const A = await openPage(`${BASE}/?t=${tA}&debug`);
  const B = await openPage(`${BASE}/?t=${tB}&debug`);
  await sleep(800);

  await step("strona pokazuje ekran Połącz", async () => {
    for (const p of [A, B]) {
      const vis = await p.eval(`!document.getElementById('screen-connect').hidden && !!document.getElementById('btn-connect')`);
      if (!vis) throw new Error("brak ekranu połączenia");
    }
  });

  await step("Połącz → welcome, mikrofon, status Połączono", async () => {
    for (const p of [A, B]) await p.eval(`document.getElementById('btn-connect').click()`, true);
    for (const [p, id] of [[A, idA], [B, idB]]) {
      await until(() => p.eval(`glosDebug.welcomed && glosDebug.myId === '${id}' && !!glosDebug.sendTrack && glosDebug.ctx.state`), 10000, "welcome");
    }
  });

  await step("WebRTC: oba RTCPeerConnection connected (A oferuje)", async () => {
    for (const [p, other] of [[A, idB], [B, idA]]) {
      await until(() => p.eval(`(() => { const x = glosDebug.peers.get('${other}'); return x && x.pc && x.pc.connectionState === 'connected'; })()`), 20000, "connected");
    }
    const initA = await A.eval(`glosDebug.peers.get('${idB}').initiator`);
    const initB = await B.eval(`glosDebug.peers.get('${idA}').initiator`);
    if (initA !== true || initB !== false) throw new Error(`inicjator: A=${initA} B=${initB}`);
  });

  await step("VAD (sztuczny mikrofon) → plugin dostaje speaking on", async () => {
    await A.eval(`(() => { glosDebug.threshold = -70; })()`);
    await until(() => pmsgs.find((m) => m.t === "speaking" && m.id === idA && m.on === true), 10000, "speaking A");
  });

  // Pomiar sygnału za master gain u B (tam, gdzie idzie dźwięk do głośników).
  await B.eval(`(() => { const S = glosDebug; const an = S.ctx.createAnalyser(); an.fftSize = 2048; S.master.connect(an); window.__an = an;
    window.__lvl = () => { const b = new Float32Array(an.fftSize); let mx = 0; for (let k = 0; k < 10; k++) {} an.getFloatTimeDomainData(b); for (const v of b) mx = Math.max(mx, Math.abs(v)); return mx; };
    window.__peak = (ms) => new Promise(r => { let mx = 0; const t = setInterval(() => mx = Math.max(mx, __lvl()), 20); setTimeout(() => { clearInterval(t); r(mx); }, ms); }); })()`);

  await step("dźwięk A dociera do grafu Web Audio u B (obejście Chrome z <audio muted>)", async () => {
    const peak = await until(async () => {
      const v = await B.eval(`__peak(1500)`);
      return v > 0.001 ? v : 0;
    }, 15000, "sygnał > 0");
    console.log("       szczyt sygnału u B: " + peak.toFixed(4));
    const hasEl = await B.eval(`(() => { const e = document.querySelector('#audio-sink audio'); return !!e && e.muted && !!e.srcObject; })()`);
    if (!hasEl) throw new Error("brak wyciszonego <audio> ze strumieniem");
  });

  await step("panner HRTF/linear z maxDistance = r i pozycją z payloadu", async () => {
    const v = await B.eval(`(() => { const n = glosDebug.peers.get('${idA}').nodes; return { m: n.panner.panningModel, d: n.panner.distanceModel, max: n.panner.maxDistance, x: n.panner.positionX.value, f: n.filter.frequency.value }; })()`);
    if (v.m !== "HRTF" || v.d !== "linear" || v.max !== 24 || Math.abs(v.x + 4) > 0.2 || v.f < 15000) throw new Error(JSON.stringify(v));
  });

  await step("pod wodą (m=true) → lowpass ~700 Hz, baner u B", async () => {
    world.uwB = true;
    await until(() => B.eval(`!document.getElementById('banner-uw').hidden && glosDebug.peers.get('${idA}').nodes.filter.frequency.value < 1000`), 5000, "filtr");
    world.uwB = false;
    await until(() => B.eval(`glosDebug.peers.get('${idA}').nodes.filter.frequency.value > 15000`), 5000, "filtr z powrotem");
  });

  await step("peer poza zasięgiem r → gain 0 i cisza u B", async () => {
    world.dist = 30; // > r=24, ale nadal w `peers`
    await until(() => B.eval(`glosDebug.peers.get('${idA}').nodes.gain.gain.value < 0.01`), 5000, "gain→0");
    const peak = await B.eval(`__peak(800)`);
    if (peak > 0.0005) throw new Error("nadal słychać: " + peak);
    world.dist = 4;
    await until(() => B.eval(`glosDebug.peers.get('${idA}').nodes.gain.gain.value > 0.9`), 5000, "gain→1");
  });

  await step("oferta od gracza spoza peers → odpowiedź bez mikrofonu (brak podsłuchu)", async () => {
    const tZ = tok();
    P.send(JSON.stringify({ t: "token", token: tZ, id: idZ, n: "Zlodziej" }));
    await sleep(200);
    const Z = await openPage(`${BASE}/?t=${tZ}&debug`);
    await sleep(500);
    await Z.eval(`document.getElementById('btn-connect').click()`, true);
    await until(() => Z.eval(`glosDebug.welcomed && glosDebug.myId === '${idZ}' && glosDebug.ctx.state === 'running'`), 10000, "Z welcome");
    const thrB = await B.eval(`glosDebug.threshold`);
    await B.eval(`(() => { glosDebug.threshold = -70; })()`); // B nadaje (sztuczny mikrofon)
    try {
      await until(() => B.eval(`(() => { const x = glosDebug.peers.get('${idZ}'); return !!(x && x.pc && x.pc.connectionState === 'connected'); })()`), 15000, "Z↔B connected");
      const r = await B.eval(`(() => { const S = glosDebug; const z = S.peers.get('${idZ}').pc.getTransceivers()[0].sender.track; const a = S.peers.get('${idA}').pc.getTransceivers()[0].sender.track; return { zNull: z === null, aOk: !!a && a === S.sendTrack, inPeers: S.peers.get('${idZ}').inPeers }; })()`);
      if (!r.zNull || !r.aOk || r.inPeers) throw new Error(JSON.stringify(r));
      await Z.eval(`(() => { const S = glosDebug; const an = S.ctx.createAnalyser(); an.fftSize = 2048; S.master.connect(an);
        window.__peak = (ms) => new Promise(r => { const b = new Float32Array(an.fftSize); let mx = 0; const t = setInterval(() => { an.getFloatTimeDomainData(b); for (const v of b) mx = Math.max(mx, Math.abs(v)); }, 20); setTimeout(() => { clearInterval(t); r(mx); }, ms); }); })()`);
      const peak = await Z.eval(`__peak(1500)`);
      if (peak > 0.0005) throw new Error("Z słyszy B: " + peak);
    } finally {
      await B.eval(`(() => { glosDebug.threshold = ${JSON.stringify(thrB)}; })()`);
      P.send(JSON.stringify({ t: "kick", id: idZ, reason: "koniec testu" }));
    }
  });

  await step("lista graczy: nick i odległość", async () => {
    const txt = await until(() => B.eval(`document.getElementById('peer-list').textContent`).then((t) => (t.includes("Alfa") && t.includes("4 bl.") ? t : "")), 4000, "wiersz");
    if (!txt) throw new Error("brak wiersza");
  });

  await step("głośność per gracz zapamiętana w localStorage", async () => {
    await B.eval(`(() => { const s = document.querySelector('#peer-list input[type=range]'); s.value = '50'; s.dispatchEvent(new Event('input')); })()`);
    const v = await B.eval(`localStorage.getItem('glos.vol.${idA}')`);
    if (v !== "0.5") throw new Error("localStorage: " + v);
    await until(() => B.eval(`Math.abs(glosDebug.peers.get('${idA}').nodes.gain.gain.value - 0.5) < 0.02`), 3000, "gain 0.5");
  });

  await step("przycisk Krzyk → plugin dostaje mode shout; aktywny wg me.mode", async () => {
    await A.eval(`document.querySelector('.mode[data-mode=shout]').click()`, true);
    await until(() => pmsgs.find((m) => m.t === "mode" && m.id === idA && m.mode === "shout"), 4000, "mode");
    world.modeA = "shout";
    await until(() => A.eval(`document.querySelector('.mode[data-mode=shout]').getAttribute('aria-pressed') === 'true'`), 3000, "aria-pressed");
  });

  await step("wyciszenie przez admina → track wyłączony, speaking off, baner", async () => {
    world.mutedA = true;
    await until(() => A.eval(`!document.getElementById('banner-muted').hidden && glosDebug.sendTrack.enabled === false`), 4000, "baner+track");
    await until(() => [...pmsgs].reverse().find((m) => m.t === "speaking" && m.id === idA)?.on === false, 4000, "speaking off");
    await until(() => B.eval(`glosDebug.peers.get('${idA}').nodes.gain.gain.value < 0.01`), 4000, "mu → gain 0 u B");
    world.mutedA = false;
  });

  await step("klawisz M wycisza mikrofon", async () => {
    await A.cmd("Input.dispatchKeyEvent", { type: "keyDown", code: "KeyM", key: "m", windowsVirtualKeyCode: 77 });
    await A.cmd("Input.dispatchKeyEvent", { type: "keyUp", code: "KeyM", key: "m", windowsVirtualKeyCode: 77 });
    const v = await A.eval(`glosDebug.selfMuted && document.getElementById('btn-mute').getAttribute('aria-pressed')`);
    if (v !== "true") throw new Error("M nie zadziałał: " + v);
  });

  await step("zerwany WebSocket → ponowne połączenie z backoffem, WebRTC trwa", async () => {
    await A.eval(`(() => { window.__oldWs = glosDebug.ws; glosDebug.ws.close(); })()`);
    await until(() => A.eval(`glosDebug.ws !== window.__oldWs && glosDebug.welcomed`), 8000, "reconnect");
    const st = await A.eval(`glosDebug.peers.get('${idB}')?.pc?.connectionState`);
    if (st !== "connected") throw new Error("pc: " + st);
  });

  if (process.env.SHOTS) {
    for (const [p, name, w, h, mobile] of [[A, "desktop", 1200, 1100, false], [B, "mobile", 390, 1500, true]]) {
      await p.cmd("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 1, mobile });
      await sleep(600);
      const shot = await p.cmd("Page.captureScreenshot", { format: "png" });
      const { writeFileSync } = await import("node:fs");
      writeFileSync(join(process.env.SHOTS, `glos-${name}.png`), Buffer.from(shot.data, "base64"));
    }
  }

  await step("peer znika z peers na > 5 s → rozłączenie", async () => {
    world.dist = 4;
    const save = connected;
    clearInterval(stateTimer);
    // wysyłaj state bez peerów
    const t2 = setInterval(() => {
      P.send(JSON.stringify({ t: "state", to: { [idA]: { me: { yaw: 0, pitch: 0, mode: "normal", range: 24, muted: false, uw: false }, peers: [] }, [idB]: { me: { yaw: 0, pitch: 0, mode: "normal", range: 24, muted: false, uw: false }, peers: [] } } }));
    }, 200);
    await sleep(3000);
    const still = await B.eval(`glosDebug.peers.has('${idA}')`);
    await until(() => B.eval(`!glosDebug.peers.has('${idA}')`), 6000, "usunięty");
    clearInterval(t2);
    if (!still) throw new Error("usunięty za wcześnie (< 3 s)");
    void save;
  });

  await step("kick → ekran Rozłączono z powodem, bez ponownego łączenia", async () => {
    P.send(JSON.stringify({ t: "kick", id: idB, reason: "Wyszedłeś z serwera" }));
    await until(() => B.eval(`!document.getElementById('screen-kicked').hidden && document.getElementById('kick-reason').textContent`), 4000, "kicked").then((r) => {
      if (r !== "Wyszedłeś z serwera") throw new Error(r);
    });
    await sleep(2500);
    const ws = await B.eval(`glosDebug.ws === null && glosDebug.kicked`);
    if (!ws) throw new Error("klient próbuje się łączyć ponownie");
  });

  await step("brak tokenu → instrukcja /glos", async () => {
    const C = await openPage(`${BASE}/`);
    await sleep(500);
    const t = await C.eval(`!document.getElementById('screen-notoken').hidden && document.getElementById('screen-notoken').textContent.includes('/glos')`);
    if (!t) throw new Error("brak instrukcji");
  });

  const errs = [...A.logs, ...B.logs].filter((l) => /EXCEPTION|error/i.test(l));
  await step("brak wyjątków JS w konsoli", async () => {
    if (errs.length) throw new Error(errs.join("\n       "));
  });

  P.close();
  console.log(`\nWynik e2e: ${ok} OK, ${fail} FAIL`);
  return fail === 0;
}

main()
  .then((r) => {
    killAll();
    process.exit(r ? 0 : 1);
  })
  .catch((e) => {
    console.error(e);
    killAll();
    process.exit(1);
  });
