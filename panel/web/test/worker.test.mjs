// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Testy jednostkowe Workera (src/index.js: sekret pluginu, ochrona POST, limity ciała, trasy) i trwałego stanu
// Durable Object (src/hub.js: sesje, limit sesji, wygasanie, revoke, sprzątanie w alarmie) — bez wrangler dev.
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { timingSafeEqual } from "node:crypto";

// crypto.subtle.timingSafeEqual to rozszerzenie Cloudflare — w Node dokładamy odpowiednik (rzuca przy różnej długości).
if (typeof crypto.subtle.timingSafeEqual !== "function") {
  Object.defineProperty(crypto.subtle, "timingSafeEqual", {
    value: (a, b) => {
      if (a.byteLength !== b.byteLength) throw new TypeError("różne długości");
      return timingSafeEqual(Buffer.from(a), Buffer.from(b));
    },
  });
}

const httpUrl = JSON.stringify(new URL("../src/http.js", import.meta.url).href);
const load = async (file, replace) => {
  let src = await readFile(new URL(file, import.meta.url), "utf8");
  for (const [a, b] of replace) {
    assert.ok(src.includes(a), "brak fragmentu do podmiany: " + a);
    src = src.replace(a, b);
  }
  return import("data:text/javascript;base64," + Buffer.from(src).toString("base64"));
};
const { default: worker } = await load("../src/index.js", [
  ['import { PanelHub } from "./hub.js";', "class PanelHub {}"],
  ['from "./http.js"', "from " + httpUrl],
]);
const { PanelHub } = await load("../src/hub.js", [
  ['import { DurableObject } from "cloudflare:workers";', "class DurableObject {}"],
  ['from "./http.js"', "from " + httpUrl],
]);

const SECRET = "sekret-testowy-" + "x".repeat(40);
const ORIGIN = "https://panel.test";

function env() {
  const seen = [];
  return {
    seen,
    PANEL_SECRET: SECRET,
    PANEL_HUB: {
      idFromName: (n) => n,
      get: () => ({
        fetch: async (req) => {
          seen.push({ url: req.url, method: req.method, headers: Object.fromEntries(req.headers), body: req.body ? await req.text() : null });
          return new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "Content-Type": "application/json" } });
        },
      }),
    },
    ASSETS: { fetch: async () => new Response("asset", { status: 200 }) },
  };
}

const post = (path, body, headers = {}) =>
  new Request(ORIGIN + path, { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel", ...headers }, body: typeof body === "string" ? body : JSON.stringify(body) });

// ------------------------------------------------------------------ Worker: /plugin

test("/plugin: brak, zły albo pusty sekret → 401 i nic nie trafia do DO", async () => {
  for (const auth of [null, "Bearer zly", "Basic " + SECRET, "Bearer", SECRET]) {
    const e = env();
    const headers = { Upgrade: "websocket" };
    if (auth !== null) headers.Authorization = auth;
    const r = await worker.fetch(new Request(ORIGIN + "/plugin", { headers }), e);
    assert.equal(r.status, 401, "auth=" + auth);
    assert.equal(e.seen.length, 0);
  }
  const e = env();
  e.PANEL_SECRET = "   ";
  const r = await worker.fetch(new Request(ORIGIN + "/plugin", { headers: { Upgrade: "websocket", Authorization: "Bearer    " } }), e);
  assert.equal(r.status, 401, "pusty sekret w środowisku nigdy nie przepuszcza");
});

test("/plugin: dobry sekret bez Upgrade → 426, z Upgrade → DO", async () => {
  const e = env();
  const r1 = await worker.fetch(new Request(ORIGIN + "/plugin", { headers: { Authorization: "Bearer " + SECRET } }), e);
  assert.equal(r1.status, 426);
  const r2 = await worker.fetch(new Request(ORIGIN + "/plugin", { headers: { Authorization: "bearer  " + SECRET + " ", Upgrade: "WebSocket" } }), e);
  assert.equal(r2.status, 200);
  assert.equal(e.seen.length, 1);
});

// ------------------------------------------------------------------ Worker: ochrona POST

test("POST /api/*: bez X-Requested-With, z obcym Origin albo Sec-Fetch-Site cross-site → 403", async () => {
  const cases = [
    new Request(ORIGIN + "/api/rpc", { method: "POST", body: "{}" }),
    post("/api/rpc", { method: "me" }, { Origin: "https://zly.example" }),
    post("/api/rpc", { method: "me" }, { "Sec-Fetch-Site": "cross-site" }),
    post("/api/logout", {}, { "Sec-Fetch-Site": "same-site" }),
  ];
  for (const req of cases) {
    const e = env();
    const r = await worker.fetch(req, e);
    assert.equal(r.status, 403);
    assert.equal(e.seen.length, 0);
    assert.equal(r.headers.get("X-Frame-Options"), "DENY");
  }
});

test("POST /api/rpc: IP klienta zawsze z CF-Connecting-IP (podrobiony X-Panel-IP nadpisany), nagłówki bezpieczeństwa", async () => {
  const e = env();
  const r = await worker.fetch(post("/api/rpc", { method: "me" }, { Origin: ORIGIN, "Sec-Fetch-Site": "same-origin", "CF-Connecting-IP": "203.0.113.7", "X-Panel-IP": "1.2.3.4", Cookie: "sid=abc" }), e);
  assert.equal(r.status, 200);
  assert.equal(e.seen[0].headers["x-panel-ip"], "203.0.113.7");
  assert.equal(e.seen[0].headers.cookie, "sid=abc");
  assert.equal(r.headers.get("Cache-Control"), "no-store");
  assert.match(r.headers.get("Content-Security-Policy"), /default-src 'none'/);
});

// ------------------------------------------------------------------ Worker: limity ciała

test("limity ciała: 16 KB dla zwykłych metod i logowania, 256 KB tylko dla planów i chorągwi", async () => {
  const big = (method, n) => JSON.stringify({ method, params: { pad: "a".repeat(n) } });
  const cases = [
    ["/api/rpc", big("country.get", 17 * 1024), 413],
    ["/api/login", JSON.stringify({ name: "Bolko", password: "a".repeat(17 * 1024) }), 413],
    ["/api/rpc", big("admin.npc.setPlan", 100 * 1024), 200],
    ["/api/rpc", big("country.plan.set", 100 * 1024), 200],
    ["/api/rpc", big("country.flag.upload", 200 * 1024), 200],
    ["/api/rpc", big("country.flag.upload", 257 * 1024), 413],
    ["/api/rpc", "x".repeat(17 * 1024), 413], // nie-JSON nie dostaje dużego limitu
  ];
  for (const [path, body, status] of cases) {
    const e = env();
    const r = await worker.fetch(post(path, body), e);
    assert.equal(r.status, status, path + " " + body.slice(0, 30));
    assert.equal(e.seen.length, status === 200 ? 1 : 0);
  }
});

// ------------------------------------------------------------------ Worker: trasy

test("trasy: metody spoza listy → 405, /login tylko GET, reszta z ASSETS", async () => {
  const e = env();
  assert.equal((await worker.fetch(new Request(ORIGIN + "/api/rpc", { method: "PUT", body: "{}" }), e)).status, 405);
  assert.equal((await worker.fetch(new Request(ORIGIN + "/login", { method: "POST", body: "{}" }), e)).status, 405);
  assert.equal(e.seen.length, 0);
  const asset = await worker.fetch(new Request(ORIGIN + "/index.html"), e);
  assert.equal(await asset.text(), "asset");
  const link = await worker.fetch(new Request(ORIGIN + "/login?t=abc"), e);
  assert.equal(link.status, 200);
  assert.equal(e.seen.length, 1);
  assert.equal(e.seen[0].body, null);
  assert.equal(link.headers.get("Referrer-Policy"), "no-referrer", "token w adresie nie wycieka w Referer");
});

// ------------------------------------------------------------------ DO: sesje i sprzątanie

function fakeStorage() {
  const m = new Map();
  let alarm = null;
  const sorted = () => new Map([...m.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)));
  return {
    m,
    get: async (k) => (Array.isArray(k) ? new Map(k.filter((x) => m.has(x)).map((x) => [x, m.get(x)])) : m.get(k)),
    put: async (k, v) => {
      if (typeof k === "string") m.set(k, v);
      else for (const [a, b] of Object.entries(k)) m.set(a, b);
    },
    delete: async (k) => {
      for (const x of Array.isArray(k) ? k : [k]) m.delete(x);
    },
    list: async ({ prefix = "", limit = Infinity } = {}) => {
      const out = new Map();
      for (const [k, v] of sorted()) {
        if (!k.startsWith(prefix)) continue;
        if (out.size >= limit) break;
        out.set(k, v);
      }
      return out;
    },
    getAlarm: async () => alarm,
    setAlarm: async (t) => void (alarm = t),
    get alarm() {
      return alarm;
    },
  };
}

function hub() {
  const h = Object.create(PanelHub.prototype);
  h.env = {};
  h.buckets = new Map();
  h.cacheMem = new Map();
  h.pending = new Map();
  h.ctx = { storage: fakeStorage() };
  return h;
}
const UUID = "0a1b2c3d-0000-4000-8000-00000000000a";
const OTHER = "0a1b2c3d-0000-4000-8000-00000000000b";
const withSid = (token) => new Request(ORIGIN + "/api/rpc", { headers: { Cookie: "sid=" + token } });

test("sesja: w storage tylko skrót tokenu, odczyt po cookie, zły format cookie → brak sesji", async () => {
  const h = hub();
  const token = await h.createSession(UUID, "Bolko");
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  for (const [k, v] of h.ctx.storage.m) assert.ok(!k.includes(token) && !JSON.stringify(v).includes(token), "surowy token w storage: " + k);
  const s = await h.getSession(withSid(token));
  assert.equal(s.uuid, UUID);
  assert.equal(s.name, "Bolko");
  assert.equal(await h.getSession(withSid(token.slice(1))), null);
  assert.equal(await h.getSession(withSid(token + "A")), null);
  assert.ok(h.ctx.storage.alarm > Date.now(), "zaplanowane sprzątanie");
});

test("sesja: najwyżej 10 na gracza (najstarsza wypada), wygasła sesja jest usuwana", async () => {
  const h = hub();
  const tokens = [];
  for (let i = 0; i < 11; i++) {
    tokens.push(await h.createSession(UUID, "Bolko"));
    await new Promise((r) => setTimeout(r, 2)); // różne znaczniki czasu → przewidywalna kolejność
  }
  const mine = await h.ctx.storage.list({ prefix: "us:" + UUID + ":" });
  assert.equal(mine.size, 10);
  assert.equal(await h.getSession(withSid(tokens[0])), null, "najstarsza sesja usunięta");
  assert.ok(await h.getSession(withSid(tokens[10])));

  const st = h.ctx.storage;
  const key = [...st.m.keys()].find((k) => k.startsWith("s:") && st.m.get(k) && st.m.get(k).c === Math.max(...[...st.m.values()].filter((v) => v && v.c).map((v) => v.c)));
  st.m.get(key).exp = Date.now() - 1;
  assert.equal(await h.getSession(withSid(tokens[10])), null, "wygasła");
  assert.equal(st.m.has(key), false);
  assert.equal(st.m.has("us:" + UUID + ":" + key.slice(2)), false);
});

test("revoke: usuwa sesje, link i cache tylko tego gracza", async () => {
  const h = hub();
  const st = h.ctx.storage;
  const mine = await h.createSession(UUID, "Bolko");
  const other = await h.createSession(OTHER, "Mieszko");
  await h.onLinkToken({ token: "A".repeat(32), uuid: UUID, name: "Bolko" });
  await h.putCache(UUID, "me", {}, { name: "Bolko" });
  await h.putCache(OTHER, "me", {}, { name: "Mieszko" });
  await h.revokeUser(UUID);
  assert.equal(await h.getSession(withSid(mine)), null);
  assert.ok(await h.getSession(withSid(other)));
  const keys = [...st.m.keys()];
  assert.ok(!keys.some((k) => k.includes(UUID)), "zostały klucze: " + keys.filter((k) => k.includes(UUID)));
  assert.ok(!keys.some((k) => k.startsWith("lt:")), "link z gry unieważniony");
  assert.ok(keys.includes("c:" + OTHER + ":me:{}"));
});

test("alarm: kasuje wygasłe sesje, osierocone indeksy, stare limity, linki i cache; zostawia świeże", async () => {
  const h = hub();
  const st = h.ctx.storage;
  const now = Date.now();
  const day = 86400000;
  await st.put({
    "s:live": { uuid: UUID, name: "Bolko", c: now, exp: now + day },
    ["us:" + UUID + ":live"]: now,
    "s:dead": { uuid: UUID, name: "Bolko", c: now - 8 * day, exp: now - 1 },
    ["us:" + UUID + ":dead"]: now - 8 * day,
    ["us:" + UUID + ":orphan"]: now,
    "lt:old": { uuid: UUID, name: "Bolko", exp: now - 1 },
    ["ltu:" + UUID]: "old",
    "lt:new": { uuid: OTHER, name: "Mieszko", exp: now + 60000 },
    ["ltu:" + OTHER]: "new",
    "rl:ip:1.2.3.4": { n: 3, reset: now - 1 },
    "rl:n:bolko": { n: 3, reset: now + 60000 },
    ["c:" + UUID + ":me:{}"]: { at: now - 8 * day, s: "{}" },
    ["c:" + OTHER + ":me:{}"]: { at: now, s: "{}" },
  });
  await h.alarm();
  assert.deepEqual([...st.m.keys()].sort(), ["c:" + OTHER + ":me:{}", "lt:new", "ltu:" + OTHER, "rl:n:bolko", "s:live", "us:" + UUID + ":live"].sort());
  assert.ok(st.alarm > now, "kolejne sprzątanie zaplanowane, bo coś zostało");

  const empty = hub();
  await empty.ctx.storage.put("s:x", { exp: now - 1 });
  await empty.alarm();
  assert.equal(empty.ctx.storage.m.size, 0);
  assert.equal(empty.ctx.storage.alarm, null, "pusty magazyn → bez kolejnego alarmu");
});

test("putCache: ten sam wynik nie jest zapisywany ponownie, za duży wcale", async () => {
  const h = hub();
  const st = h.ctx.storage;
  let puts = 0;
  const orig = st.put;
  st.put = async (...a) => (puts++, orig(...a));
  await h.putCache(UUID, "overview", {}, { a: 1 });
  await h.putCache(UUID, "overview", {}, { a: 1 });
  assert.equal(puts, 1);
  await h.putCache(UUID, "overview", {}, { a: 2 });
  assert.equal(puts, 2);
  await h.putCache(UUID, "country.get", { id: "x" }, { big: "a".repeat(130 * 1024) });
  assert.equal(puts, 2);
});
