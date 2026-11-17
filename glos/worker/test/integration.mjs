// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Test integracyjny na `wrangler dev`: udaje plugin (/plugin) i przeglądarki (/ws?t=...).
// Uruchomienie: `npm run test:int` (sam startuje wrangler dev) albo BASE=http://127.0.0.1:8787 node test/integration.mjs
import { spawn, execSync } from "node:child_process";
import { readFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes, randomUUID } from "node:crypto";
import WebSocket from "ws";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TOKEN_TTL_MS = 4000; // skrócony czas ważności nieużytego tokenu (przez --var)
const PORT = Number(process.env.PORT || 8799);

function readSecret() {
  const txt = readFileSync(join(ROOT, ".dev.vars"), "utf8");
  const m = /^PLUGIN_SECRET\s*=\s*"?([^"\r\n]+)"?/m.exec(txt);
  if (!m) throw new Error("Brak PLUGIN_SECRET w .dev.vars");
  return m[1].trim();
}

const SECRET = readSecret();
let BASE = process.env.BASE || `http://127.0.0.1:${PORT}`;
const WSBASE = () => BASE.replace(/^http/, "ws");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const tok = () => {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  return [...randomBytes(32)].map((b) => chars[b % chars.length]).join("");
};

// ------------------------------------------------------------- wrangler dev

let devProc = null;
let persistDir = null;

async function startDev() {
  persistDir = mkdtempSync(join(tmpdir(), "glos-test-"));
  const args = [
    "wrangler", "dev", "--port", String(PORT), "--ip", "127.0.0.1",
    "--var", `TOKEN_TTL_MS:${TOKEN_TTL_MS}`, "--persist-to", persistDir, "--log-level", "warn",
  ];
  devProc = spawn(process.platform === "win32" ? "npx " + args.join(" ") : "npx", process.platform === "win32" ? [] : args, { cwd: ROOT, shell: process.platform === "win32", stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, WRANGLER_SEND_METRICS: "false" } });
  let log = "";
  devProc.stdout.on("data", (d) => (log += d));
  devProc.stderr.on("data", (d) => (log += d));
  const until = Date.now() + 90000;
  while (Date.now() < until) {
    try {
      const r = await fetch(BASE + "/");
      if (r.status === 200) return;
    } catch {}
    if (devProc.exitCode !== null) break;
    await sleep(500);
  }
  console.error(log);
  throw new Error("wrangler dev nie wystartował");
}

function stopDev() {
  if (!devProc) return;
  try {
    if (process.platform === "win32") execSync(`taskkill /pid ${devProc.pid} /T /F`, { stdio: "ignore" });
    else devProc.kill("SIGTERM");
  } catch {}
  try {
    if (persistDir) rmSync(persistDir, { recursive: true, force: true });
  } catch {}
}

// ------------------------------------------------------------- klient testowy

class Conn {
  constructor(url, headers) {
    this.msgs = [];
    this.waiters = [];
    this.closeInfo = null;
    this.ws = new WebSocket(url, { headers });
    this.opened = new Promise((res, rej) => {
      this.ws.on("open", res);
      this.ws.on("unexpected-response", (_req, resp) => rej(Object.assign(new Error("HTTP " + resp.statusCode), { status: resp.statusCode })));
      this.ws.on("error", rej);
    });
    this.opened.catch(() => {});
    this.closed = new Promise((res) => {
      this.ws.on("close", (code, reason) => {
        this.closeInfo = { code, reason: reason.toString() };
        res(this.closeInfo);
      });
    });
    this.ws.on("message", (data, isBinary) => {
      if (isBinary) return;
      let m;
      try {
        m = JSON.parse(data.toString());
      } catch {
        return;
      }
      this.msgs.push(m);
      for (const w of [...this.waiters]) w();
    });
  }
  send(obj) {
    this.ws.send(typeof obj === "string" ? obj : JSON.stringify(obj));
  }
  /** Czeka na wiadomość spełniającą predykat (zużywa ją). */
  next(pred, ms = 3000, what = "wiadomość") {
    const f = typeof pred === "string" ? (m) => m.t === pred : pred;
    return new Promise((res, rej) => {
      const check = () => {
        const i = this.msgs.findIndex(f);
        if (i >= 0) {
          const [m] = this.msgs.splice(i, 1);
          cleanup();
          res(m);
          return true;
        }
        return false;
      };
      const timer = setTimeout(() => {
        cleanup();
        rej(new Error(`timeout: ${typeof pred === "string" ? pred : what}`));
      }, ms);
      const cleanup = () => {
        clearTimeout(timer);
        this.waiters = this.waiters.filter((w) => w !== check);
      };
      if (!check()) this.waiters.push(check);
    });
  }
  /** Sprawdza, że w ciągu ms NIE przyszła wiadomość spełniająca predykat. */
  async none(pred, ms = 400) {
    const f = typeof pred === "string" ? (m) => m.t === pred : pred;
    await sleep(ms);
    const hit = this.msgs.find(f);
    if (hit) throw new Error("nieoczekiwana wiadomość: " + JSON.stringify(hit));
  }
  drain() {
    this.msgs = [];
  }
  close() {
    try {
      this.ws.close();
    } catch {}
  }
}

const plugin = (secret = SECRET) => new Conn(WSBASE() + "/plugin", secret === null ? {} : { Authorization: "Bearer " + secret });
const browser = (t) => new Conn(WSBASE() + "/ws?t=" + encodeURIComponent(t));

// ------------------------------------------------------------- asercje

let passed = 0;
let failed = 0;
function assert(cond, msg) {
  if (!cond) throw new Error("asercja: " + msg);
}
function eq(a, b, msg) {
  const ja = JSON.stringify(a);
  const jb = JSON.stringify(b);
  if (ja !== jb) throw new Error(`${msg}: oczekiwano ${jb}, jest ${ja}`);
}
async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log("  OK   " + name);
  } catch (e) {
    failed++;
    console.log("  FAIL " + name + "\n       " + (e && e.stack ? e.stack.split("\n").slice(0, 3).join("\n       ") : e));
  }
}

const clientsMsg = (ids) => (m) => m.t === "clients" && JSON.stringify(m.ids) === JSON.stringify([...ids].sort());

// ------------------------------------------------------------- scenariusz

async function main() {
  if (!process.env.BASE) {
    console.log(`Start wrangler dev na porcie ${PORT}…`);
    await startDev();
  }
  console.log("Testy na " + BASE);

  const idA = randomUUID();
  const idB = randomUUID();
  const idC = randomUUID();
  let tokA = tok();
  const tokB = tok();
  let P, A, B;

  await test("GET / serwuje stronę klienta", async () => {
    const r = await fetch(BASE + "/?t=" + tok());
    eq(r.status, 200, "status");
    const html = await r.text();
    assert(html.includes("Głos") && html.includes("app.js"), "treść strony");
    assert((r.headers.get("referrer-policy") || "") === "no-referrer", "nagłówek Referrer-Policy z _headers");
    const js = await fetch(BASE + "/app.js");
    eq(js.status, 200, "app.js");
    const h = await fetch(BASE + "/_headers");
    assert(h.status === 404 || !(await h.text()).includes("Content-Security-Policy"), "_headers nie jest serwowany");
  });

  await test("GET /ws i /plugin bez Upgrade → 426 / 401", async () => {
    eq((await fetch(BASE + "/ws?t=x")).status, 426, "/ws");
    eq((await fetch(BASE + "/plugin", { headers: { Authorization: "Bearer " + SECRET } })).status, 426, "/plugin z sekretem");
    eq((await fetch(BASE + "/plugin")).status, 401, "/plugin bez sekretu");
  });

  await test("zły sekret pluginu → 401", async () => {
    for (const s of ["zly-sekret", SECRET + "x", null]) {
      const c = plugin(s);
      const err = await c.opened.then(() => null, (e) => e);
      assert(err && err.status === 401, "oczekiwano 401 dla " + s + ", jest " + (err && err.message));
    }
  });

  await test("plugin: hello → clients []", async () => {
    P = plugin();
    await P.opened;
    P.send({ t: "hello", v: 1 });
    await P.next(clientsMsg([]));
  });

  await test("token + połączenie przeglądarki A → welcome, clients [A]", async () => {
    P.send({ t: "token", token: tokA, id: idA, n: "Alfa" });
    P.send({ t: "token", token: tokB, id: idB, n: "Beta" });
    await sleep(150);
    A = browser(tokA);
    await A.opened;
    const w = await A.next("welcome");
    eq(w.id, idA, "id");
    eq(w.n, "Alfa", "n");
    assert(Array.isArray(w.ice) && w.ice.some((s) => JSON.stringify(s.urls).includes("stun:stun.cloudflare.com:3478")), "ice zawiera STUN Cloudflare");
    await P.next(clientsMsg([idA]));
  });

  await test("przeglądarka B → clients [A,B]", async () => {
    B = browser(tokB);
    await B.opened;
    const w = await B.next("welcome");
    eq(w.id, idB, "id");
    await P.next(clientsMsg([idA, idB]));
  });

  await test("state: routing per klient", async () => {
    const pa = { me: { yaw: 10, pitch: 0, mode: "normal", range: 24, muted: false, uw: false }, peers: [{ id: idB, n: "Beta", x: 1, y: 0, z: 2, r: 24, m: false, mu: false }] };
    const pb = { me: { yaw: 190, pitch: 5, mode: "shout", range: 48, muted: true, uw: true }, peers: [{ id: idA, n: "Alfa", x: -1, y: 0, z: -2, r: 24, m: true, mu: false }] };
    P.send({ t: "state", to: { [idA]: pa, [idB]: pb, [idC]: pa } });
    const sa = await A.next("state");
    const sb = await B.next("state");
    eq(sa.me, pa.me, "A.me");
    eq(sa.peers, pa.peers, "A.peers");
    eq(sb.me, pb.me, "B.me");
    eq(sb.peers, pb.peers, "B.peers");
    await A.none("state", 300);
    await B.none("state", 100);
  });

  await test("signal: A → B (from=A), do niepodłączonego → nic", async () => {
    const data = { sdp: { type: "offer", sdp: "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\n" } };
    A.send({ t: "signal", to: idB, data });
    const s = await B.next("signal");
    eq(s.from, idA, "from");
    eq(s.data, data, "data");
    B.send({ t: "signal", to: idA, data: { candidate: { candidate: "candidate:1 1 udp 1 1.2.3.4 5 typ host", sdpMid: "0", sdpMLineIndex: 0 } } });
    const s2 = await A.next("signal");
    eq(s2.from, idB, "from B");
    A.send({ t: "signal", to: idC, data });
    A.send({ t: "signal", to: idA, data }); // do siebie
    A.send({ t: "signal", to: idB, data: "nie-obiekt" });
    A.send({ t: "signal", to: idB, data: { cokolwiek: 1 } });
    await B.none("signal", 400);
    await A.none("signal", 50);
  });

  await test("speaking: A on → plugin i wszystkie przeglądarki; duplikat ignorowany", async () => {
    A.send({ t: "speaking", on: true });
    const ps = await P.next("speaking");
    eq(ps, { t: "speaking", id: idA, on: true }, "plugin");
    eq(await B.next("speaking"), { t: "speaking", id: idA, on: true }, "B");
    eq(await A.next("speaking"), { t: "speaking", id: idA, on: true }, "A (echo)");
    A.send({ t: "speaking", on: true });
    A.send({ t: "speaking", on: "tak" });
    await P.none("speaking", 400);
    A.send({ t: "speaking", on: false });
    eq(await P.next("speaking"), { t: "speaking", id: idA, on: false }, "off");
    eq(await B.next("speaking"), { t: "speaking", id: idA, on: false }, "B off");
    eq(await A.next("speaking"), { t: "speaking", id: idA, on: false }, "A off");
  });

  await test("mode: B shout → plugin; zły tryb ignorowany", async () => {
    B.send({ t: "mode", mode: "shout" });
    eq(await P.next("mode"), { t: "mode", id: idB, mode: "shout" }, "mode");
    B.send({ t: "mode", mode: "fly" });
    B.send({ t: "mode" });
    await P.none("mode", 400);
  });

  await test("śmieci od przeglądarki są ignorowane", async () => {
    A.send("to nie json");
    A.send("[1,2,3]");
    A.send('{"t":123}');
    A.send({ t: "nieznany" });
    A.ws.send(Buffer.from([1, 2, 3]));
    A.send({ t: "signal", to: idB, data: { sdp: "x".repeat(20000) } }); // > 16 KB
    await B.none("signal", 400);
    // socket nadal działa:
    A.send({ t: "speaking", on: true });
    eq(await B.next("speaking"), { t: "speaking", id: idA, on: true }, "A dalej działa");
    A.send({ t: "speaking", on: false });
    await B.next("speaking");
    A.drain();
    P.drain();
  });

  await test("ping → pong (auto-response)", async () => {
    A.send('{"t":"ping"}');
    await A.next("pong");
  });

  await test("nieważny token → kicked + close", async () => {
    for (const t of [tok(), "krotki", ""]) {
      const X = browser(t);
      await X.opened;
      const k = await X.next("kicked");
      eq(k.reason, "Link jest nieważny. Wpisz /glos w grze, aby dostać nowy.", "powód");
      const c = await X.closed;
      assert(c.code === 4001, "kod zamknięcia " + c.code);
    }
    await P.none("clients", 200);
  });

  await test("nieużyty token wygasa (TTL skrócony do 4 s), użyty działa dalej", async () => {
    const tExp = tok();
    P.send({ t: "token", token: tExp, id: idC, n: "Gamma" });
    await sleep(TOKEN_TTL_MS + 1200);
    const X = browser(tExp);
    await X.opened;
    await X.next("kicked");
    await X.closed;
    // tokA był użyty > 4 s temu — nadal ważny (test podwójnego połączenia poniżej).
  });

  await test("podwójne połączenie: nowa karta A2 wyrzuca A1", async () => {
    const A2 = browser(tokA);
    await A2.opened;
    const k = await A.next("kicked");
    eq(k.reason, "Połączono w innej karcie", "powód");
    const c = await A.closed;
    eq(c.code, 4002, "kod");
    eq((await A2.next("welcome")).id, idA, "A2 welcome");
    // lista klientów się nie zmienia (A dalej jest) → plugin nie dostaje zbędnego `clients`
    await P.none(clientsMsg([idB]), 400);
    // state trafia tylko do A2
    P.send({ t: "state", to: { [idA]: { me: { yaw: 1, pitch: 2, mode: "whisper", range: 6, muted: false, uw: false }, peers: [] } } });
    eq((await A2.next("state")).me.mode, "whisper", "A2 dostaje state");
    A = A2;
  });

  await test("nowa karta wyrzuca mówiącą → speaking off do pluginu i przeglądarek", async () => {
    A.send({ t: "speaking", on: true });
    await P.next((m) => m.t === "speaking" && m.id === idA && m.on === true);
    await B.next((m) => m.t === "speaking" && m.id === idA && m.on === true);
    const A2 = browser(tokA);
    await A2.opened;
    await A.next("kicked");
    eq((await A2.next("welcome")).id, idA, "A2 welcome");
    await P.next((m) => m.t === "speaking" && m.id === idA && m.on === false, 2000, "plugin: speaking off");
    await B.next((m) => m.t === "speaking" && m.id === idA && m.on === false, 2000, "B: speaking off");
    // nowa karta zaczyna od ciszy: `on` od A2 znowu przechodzi
    A2.send({ t: "speaking", on: true });
    await P.next((m) => m.t === "speaking" && m.id === idA && m.on === true);
    A2.send({ t: "speaking", on: false });
    await P.next((m) => m.t === "speaking" && m.id === idA && m.on === false);
    B.drain();
    A = A2;
  });

  await test("nowy token dla tego samego id unieważnia stary", async () => {
    const tokA2 = tok();
    P.send({ t: "token", token: tokA2, id: idA, n: "Alfa" });
    await sleep(150);
    const X = browser(tokA);
    await X.opened;
    eq((await X.next("kicked")).reason, "Link jest nieważny. Wpisz /glos w grze, aby dostać nowy.", "stary token");
    // stare połączenie A nie zostało ruszone przez nieudaną próbę
    await A.none("kicked", 200);
    const A3 = browser(tokA2);
    await A3.opened;
    await A.next("kicked");
    eq((await A3.next("welcome")).id, idA, "nowy token działa");
    A = A3;
    tokA = tokA2;
  });

  await test("kick: B dostaje kicked + 4000, plugin clients [A], token B nieważny", async () => {
    P.send({ t: "kick", id: idB, reason: "Wyszedłeś z serwera" });
    eq((await B.next("kicked")).reason, "Wyszedłeś z serwera", "powód");
    eq((await B.closed).code, 4000, "kod");
    await P.next(clientsMsg([idA]));
    const X = browser(tokB);
    await X.opened;
    await X.next("kicked");
    await P.none(clientsMsg([idA, idB]), 300);
  });

  await test("rozłączenie przeglądarki mówiącej → clients + speaking off", async () => {
    const tC = tok();
    P.send({ t: "token", token: tC, id: idC, n: "Gamma" });
    await sleep(100);
    const C = browser(tC);
    await C.opened;
    await C.next("welcome");
    await P.next(clientsMsg([idA, idC]));
    C.send({ t: "speaking", on: true });
    await P.next((m) => m.t === "speaking" && m.id === idC && m.on === true);
    C.close();
    await P.next((m) => m.t === "speaking" && m.id === idC && m.on === false);
    await P.next(clientsMsg([idA]));
    // po zamknięciu karty token działa dalej (odświeżenie strony)
    const C2 = browser(tC);
    await C2.opened;
    await C2.next("welcome");
    await P.next(clientsMsg([idA, idC]));
    P.send({ t: "kick", id: idC, reason: "koniec" });
    await C2.closed;
    await P.next(clientsMsg([idA]));
  });

  await test("limit wiadomości: zalew > burst → zamknięcie 1008", async () => {
    const tR = tok();
    const idR = randomUUID();
    P.send({ t: "token", token: tR, id: idR, n: "Spamer" });
    await sleep(100);
    const R = browser(tR);
    await R.opened;
    await R.next("welcome");
    await P.next(clientsMsg([idA, idR]));
    for (let i = 0; i < 400; i++) R.send({ t: "signal", to: idA, data: { candidate: { candidate: "x" + i } } });
    const c = await Promise.race([R.closed, sleep(4000).then(() => null)]);
    assert(c && c.code === 1008, "oczekiwano zamknięcia 1008, jest " + JSON.stringify(c));
    await P.next(clientsMsg([idA]));
    const got = A.msgs.filter((m) => m.t === "signal").length;
    assert(got <= 200, "A dostał za dużo sygnałów: " + got);
    A.drain();
  });

  await test("plugin niepodłączony: przeglądarki zostają; nowy plugin dostaje clients po hello", async () => {
    P.close();
    await P.closed;
    await sleep(200);
    A.send({ t: "mode", mode: "normal" }); // nie ma komu przekazać — nie może wywrócić DO
    await A.none("kicked", 300);
    P = plugin();
    await P.opened;
    P.send({ t: "hello", v: 1 });
    await P.next(clientsMsg([idA]));
  });

  await test("nowe połączenie pluginu zamyka poprzednie", async () => {
    const P2 = plugin();
    await P2.opened;
    const c = await Promise.race([P.closed, sleep(3000).then(() => null)]);
    assert(c && c.code === 4003, "stary plugin zamknięty, jest " + JSON.stringify(c));
    P2.send({ t: "hello", v: 1 });
    await P2.next(clientsMsg([idA]));
    A.send({ t: "mode", mode: "whisper" });
    eq(await P2.next("mode"), { t: "mode", id: idA, mode: "whisper" }, "mode trafia do nowego pluginu");
    P = P2;
  });

  await test("śmieci od pluginu są ignorowane", async () => {
    P.send("{zly json");
    P.send({ t: "state", to: [1, 2] });
    P.send({ t: "state", to: { [idA]: "x" } });
    P.send({ t: "token", token: "za-krotki", id: idA, n: "X" });
    P.send({ t: "token", token: tok(), id: "NIE-UUID", n: "X" });
    P.send({ t: "kick", id: "nie-uuid" });
    await A.none((m) => m.t === "state" || m.t === "kicked", 400);
    P.send({ t: "state", to: { [idA]: { me: { yaw: 0, pitch: 0, mode: "normal", range: 24, muted: false, uw: false }, peers: [] } } });
    await A.next("state");
  });

  A.close();
  P.close();
  await sleep(200);
  console.log(`\nWynik: ${passed} OK, ${failed} FAIL`);
  return failed === 0;
}

main()
  .then((ok) => {
    stopDev();
    process.exit(ok ? 0 : 1);
  })
  .catch((e) => {
    console.error(e);
    stopDev();
    process.exit(1);
  });
