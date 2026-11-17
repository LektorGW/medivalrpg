// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Test integracyjny na `wrangler dev` z fałszywym pluginem (ws): logowanie dobre/złe, rate-limit, cookie,
// link z gry (jednorazowy, wygasający), RPC z sesją i bez, limity, timeout, plugin offline (503 + cache),
// wylogowanie, revoke, nagłówki bezpieczeństwa, brak haseł w logach; cache odczytów v2 (admin.npc.*) w trybie offline.
// Uruchomienie: `npm run test:int`.
import { randomBytes, randomUUID } from "node:crypto";
import { FakePlugin } from "./fake-plugin.mjs";
import { startDev, stopDev, readSecret, sleep } from "./dev-server.mjs";

const PORT = Number(process.env.PORT || 8797);
const SECRET = readSecret();
const VARS = { RPC_TIMEOUT_MS: 1500, LINK_TTL_MS: 2500, LOGIN_RL_MAX: 4, LOGIN_RL_WINDOW_MS: 60000, RPC_RATE: 5, RPC_BURST: 8 };
const PASSWORD = "Tajne-Haslo-" + randomBytes(6).toString("hex");
const BAD_PASSWORD = "Zle-Haslo-" + randomBytes(6).toString("hex");

let dev;
let BASE;
let ok = 0;
let fail = 0;

async function step(name, fn) {
  try {
    await fn();
    ok++;
    console.log("  OK   " + name);
  } catch (e) {
    fail++;
    console.log("  FAIL " + name + "\n       " + (e && e.stack ? e.stack.split("\n").slice(0, 3).join("\n       ") : e));
  }
}
function assert(c, msg) {
  if (!c) throw new Error(msg || "asercja nie spełniona");
}
function eq(a, b, msg) {
  if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(`${msg || "różne"}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`);
}

const tok32 = () => [...randomBytes(32)].map((b) => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[b % 62]).join("");

async function req(path, { method = "GET", body, cookie, headers = {}, xrw = true, ip } = {}) {
  const h = { ...headers };
  if (method === "POST") {
    h["Content-Type"] = "application/json";
    if (xrw) h["X-Requested-With"] = "panel";
  }
  if (cookie) h.Cookie = "sid=" + cookie;
  if (ip) h["CF-Connecting-IP"] = ip;
  const r = await fetch(BASE + path, { method, headers: h, body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body), redirect: "manual" });
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {}
  const setCookie = r.headers.getSetCookie ? r.headers.getSetCookie() : [];
  const sid = (setCookie.map((c) => /^sid=([^;]*)/.exec(c)).find(Boolean) || [])[1];
  return { status: r.status, headers: r.headers, json, text, setCookie, sid };
}
const rpc = (cookie, method, params = {}, opts = {}) => req("/api/rpc", { method: "POST", cookie, body: { method, params }, ...opts });

// ------------------------------------------------------------- dane fałszywego pluginu

const USERS = {
  bolko: { uuid: randomUUID(), name: "Bolko", password: PASSWORD },
  mieszko: { uuid: randomUUID(), name: "Mieszko", password: PASSWORD },
};
const holds = []; // rpc „hang” trzymane bez odpowiedzi
// Atrapy dwóch wersji Kraje dla country.flag.get: 9240f7b2 (bez pól sztandaru) i 463b65a5 (banner/bannerText/bannerDeltaE).
const KRAJE = { mode: "old" };
const BANNER_PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";
function flagGet(params) {
  const current = { source: "UPLOAD", imageId: "ab12", png: BANNER_PNG, approvedAt: 1, approvedBy: "WorldSteward", status: "LIVE", liveSince: 2 };
  if (KRAJE.mode === "pending") Object.assign(current, { banner: null, bannerText: null, bannerDeltaE: null });
  if (KRAJE.mode === "new") Object.assign(current, { banner: BANNER_PNG, bannerText: "tło: czerwony; połowa górna: biały", bannerDeltaE: 15.5 });
  return { ok: true, data: { id: params.id, name: "Polanie", color: "#AA0000", npc: false, access: { view: true, upload: false, manage: false, moderate: false }, current } };
}

function handlers() {
  return {
    auth: (m) => {
      const u = Object.values(USERS).find((x) => x.name.toLowerCase() === String(m.name).toLowerCase());
      if (u && m.password === u.password) return { ok: true, uuid: u.uuid, name: u.name };
      return { ok: false, error: "Nieprawidłowy nick lub hasło (plugin)" };
    },
    rpc: async (m) => {
      switch (m.method) {
        case "me":
          return { ok: true, data: { uuid: m.user.uuid, name: m.user.name, admin: false, balance: 1234, country: null, roles: { admin: false, npcManager: false, managedNpcCountries: [] } } };
        case "overview":
          return { ok: true, data: { online: 3, maxPlayers: 20, countries: [{ id: "c1", name: "Polanie", color: "#AA0000", npc: false }] } };
        case "admin.health":
          return { ok: true, data: { range: m.params.range, current: { tps: 19.9 }, points: [] } };
        case "admin.npc.history":
          return { ok: true, data: { id: m.params.id, days: 7, intervalMs: 3600000, points: [{ t: 1, treasury: 100, population: 5, soldiers: 2, chunks: 3, food: null }], current: { treasury: 100 } } };
        case "admin.npc.food":
          return { ok: true, data: { ok: true, food: 10, foodCapacity: 100, applied: 10 } };
        case "echo":
          return { ok: true, data: { user: m.user, params: m.params, ip: m.ip } };
        case "country.flag.get":
          return flagGet(m.params);
        case "country.plan.set":
        case "admin.npc.setPlan":
          return { ok: true, data: { ok: true, size: JSON.stringify(m.params.plan).length, warnings: [] } };
        case "admin.npc.mapRender":
          return { ok: true, data: { png: "A".repeat(1_500_000), width: 768, height: 768, complete: true } };
        case "fail":
          return { ok: false, error: "Nie masz uprawnień (plugin)" };
        case "slow":
          return undefined; // brak odpowiedzi → timeout
        case "hang":
          holds.push(m);
          return undefined;
        default:
          return { ok: false, error: "Nieznana metoda" };
      }
    },
  };
}

async function loginOk(user = USERS.bolko, ip) {
  const r = await req("/api/login", { method: "POST", body: { name: user.name, password: user.password }, ip });
  assert(r.status === 200, "login " + r.status + " " + r.text);
  assert(r.sid, "brak cookie sid");
  return r;
}

// ------------------------------------------------------------- scenariusz

async function main() {
  console.log("Start wrangler dev na porcie " + PORT + "…");
  dev = await startDev(PORT, VARS);
  BASE = dev.base;
  let plugin;

  await step("strona SPA + nagłówki bezpieczeństwa (CSP bez unsafe-inline, no-referrer)", async () => {
    const r = await req("/");
    eq(r.status, 200, "status");
    assert(r.text.includes("/js/app.js"), "brak app.js");
    const csp = r.headers.get("content-security-policy") || "";
    assert(csp.includes("script-src 'self'") && !csp.includes("unsafe-inline") && csp.includes("frame-ancestors 'none'"), "CSP: " + csp);
    eq(r.headers.get("referrer-policy"), "no-referrer", "Referrer-Policy");
    const f = await req("/kraj/abc/armia");
    eq(f.status, 200, "fallback SPA");
    const api = await req("/api/status");
    eq(api.headers.get("referrer-policy"), "no-referrer", "Referrer-Policy API");
    eq(api.headers.get("cache-control"), "no-store", "Cache-Control API");
  });

  await step("/plugin bez sekretu i ze złym sekretem → 401", async () => {
    for (const s of [null, "zly-sekret", SECRET + "x"]) {
      const p = new FakePlugin(BASE, s);
      let status = 0;
      await p.opened.catch((e) => (status = e.status));
      eq(status, 401, "status dla " + s);
    }
  });

  await step("plugin offline: status, logowanie → 503", async () => {
    const s = await req("/api/status");
    eq(s.json.data.online, false, "online");
    const r = await req("/api/login", { method: "POST", body: { name: "Bolko", password: PASSWORD } });
    eq(r.status, 503, "status");
    eq(r.json.error, "Serwer gry jest offline", "error");
  });

  plugin = new FakePlugin(BASE, SECRET, handlers());
  await plugin.opened;
  await sleep(150);

  await step("plugin online", async () => {
    const s = await req("/api/status");
    eq(s.json.data.online, true, "online");
  });

  await step("POST bez X-Requested-With / z obcym Origin / za duże body → odrzucone", async () => {
    const a = await req("/api/login", { method: "POST", body: { name: "Bolko", password: PASSWORD }, xrw: false });
    eq(a.status, 403, "bez nagłówka");
    const b = await req("/api/login", { method: "POST", body: { name: "Bolko", password: PASSWORD }, headers: { Origin: "https://zly.example" } });
    eq(b.status, 403, "obcy Origin");
    const c = await req("/api/rpc", { method: "POST", body: { method: "echo", params: { x: "a".repeat(17 * 1024) } } });
    eq(c.status, 413, "za duże");
    const d = await req("/api/rpc", { method: "PUT" });
    eq(d.status, 405, "PUT");
  });

  await step("plan państwa: duże ciało dla zapisu planów NPC i gracza (do 256 KB), duża odpowiedź mapRender przechodzi", async () => {
    const lg = await loginOk(USERS.mieszko, "203.0.113.60");
    const buildings = Array.from({ length: 500 }, (_, i) => ({ type: "kamienica_renesansowa", x: 1000 + i, z: -2000 - i, rotation: 90, priority: 50 }));
    const plan = { version: 1, zones: [], roads: [], walls: [], buildings, options: { allowDemolish: false, keepExisting: true, autoFill: true } };
    const big = await rpc(lg.sid, "admin.npc.setPlan", { id: "c1", plan });
    assert(JSON.stringify(plan).length > 32 * 1024, "plan > 32 KB (ponad dwa razy limit zwykłego RPC)");
    eq([big.status, big.json.ok, big.json.data.size], [200, true, JSON.stringify(plan).length], "setPlan > 32 KB");
    const player = await rpc(lg.sid, "country.plan.set", { id: "c1", plan });
    eq([player.status, player.json.ok, player.json.data.size], [200, true, JSON.stringify(plan).length], "player plan > 32 KB");
    const other = await rpc(lg.sid, "echo", { plan });
    eq(other.status, 413, "inna metoda z dużym ciałem");
    const huge = await rpc(lg.sid, "admin.npc.setPlan", { id: "c1", plan: { pad: "x".repeat(260 * 1024) } });
    eq(huge.status, 413, "ponad 256 KB");
    const map = await rpc(lg.sid, "admin.npc.mapRender", { id: "c1" });
    eq([map.status, map.json.data.png.length], [200, 1_500_000], "duża odpowiedź z PNG");
  });

  let sidBolko;
  await step("dobre logowanie: auth do pluginu z hasłem i IP, cookie z flagami, zwraca me", async () => {
    const r = await loginOk(USERS.bolko, "203.0.113.10");
    const auth = plugin.last("auth");
    eq(auth.name, "Bolko", "name w auth");
    eq(auth.password, PASSWORD, "hasło w auth");
    assert(typeof auth.id === "string" && auth.id.length > 8, "id auth");
    assert(typeof auth.ip === "string" && auth.ip.length > 0, "ip w auth");
    const c = r.setCookie.find((x) => x.startsWith("sid="));
    for (const flag of ["HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Max-Age=604800"]) assert(c.includes(flag), "brak " + flag + " w " + c);
    assert(/^[A-Za-z0-9_-]{43}$/.test(r.sid), "format tokenu");
    eq(r.json.ok, true, "ok");
    eq(r.json.data.uuid, USERS.bolko.uuid, "me.uuid");
    eq(r.json.data.balance, 1234, "me.balance");
    sidBolko = r.sid;
  });

  await step("RPC z sesją: tożsamość z sesji, params, ip; błąd pluginu → ok:false", async () => {
    const r = await rpc(sidBolko, "echo", { id: "c1", amount: 150 });
    eq(r.status, 200, "status");
    eq(r.json.ok, true, "ok");
    eq(r.json.data.user.uuid, USERS.bolko.uuid, "uuid");
    eq(r.json.data.user.name, "Bolko", "name");
    eq(r.json.data.params, { id: "c1", amount: 150 }, "params");
    assert(r.json.data.ip, "ip w rpc");
    const f = await rpc(sidBolko, "fail");
    eq(f.status, 200, "status błędu");
    eq(f.json, { ok: false, error: "Nie masz uprawnień (plugin)" }, "błąd");
    const p = await req("/api/rpc", { method: "POST", cookie: sidBolko, body: { method: "echo", params: [1, 2] } });
    eq(p.status, 400, "params tablica");
    const m = await req("/api/rpc", { method: "POST", cookie: sidBolko, body: { method: "../../x" } });
    eq(m.status, 400, "zła nazwa metody");
  });

  await step("chorągiew: country.flag.get przechodzi bez zmian ze starym (bez pól sztandaru) i nowym Kraje (banner*, także null)", async () => {
    const id = "00000000-0000-4000-8000-000000000001";
    await sleep(1000); // limit RPC na sesję (burst 8) po poprzednim kroku
    try {
      KRAJE.mode = "old";
      let r = await rpc(sidBolko, "country.flag.get", { id });
      eq(r.status, 200, "status (stary)");
      eq(r.json.ok, true, "ok (stary)");
      for (const k of ["banner", "bannerText", "bannerDeltaE"]) assert(!(k in r.json.data.current), "stary Kraje: " + k);
      KRAJE.mode = "pending";
      r = await rpc(sidBolko, "country.flag.get", { id });
      eq([r.json.data.current.banner, r.json.data.current.bannerText, r.json.data.current.bannerDeltaE], [null, null, null], "wzór liczony: null zachowane");
      KRAJE.mode = "new";
      r = await rpc(sidBolko, "country.flag.get", { id });
      eq(r.json.data.current.banner, BANNER_PNG, "banner");
      eq(r.json.data.current.bannerText, "tło: czerwony; połowa górna: biały", "bannerText");
      eq(r.json.data.current.bannerDeltaE, 15.5, "bannerDeltaE");
      eq(r.json.data.current.png, BANNER_PNG, "obecna flaga bez zmian");
    } finally {
      KRAJE.mode = "old";
    }
  });

  await step("RPC bez sesji / ze złym cookie → 401 (plugin nie dostaje nic)", async () => {
    const before = plugin.received.length;
    const a = await rpc(null, "echo");
    eq(a.status, 401, "bez cookie");
    const b = await rpc("x".repeat(43), "echo");
    eq(b.status, 401, "złe cookie");
    const c = await rpc("<script>", "echo");
    eq(c.status, 401, "śmieci");
    eq(plugin.received.length, before, "plugin nie powinien dostać wiadomości");
  });

  await step("złe logowanie → 401 z błędem z pluginu; rate-limit per nick → 429", async () => {
    for (let i = 0; i < 4; i++) {
      const r = await req("/api/login", { method: "POST", body: { name: "Mieszko", password: BAD_PASSWORD }, ip: "198.51.100." + (i + 1) });
      eq(r.status, 401, "próba " + (i + 1));
      eq(r.json.error, "Nieprawidłowy nick lub hasło (plugin)", "błąd z pluginu");
      assert(!r.sid, "nie powinno być cookie");
    }
    const n = plugin.received.filter((m) => m.t === "auth").length;
    const r = await req("/api/login", { method: "POST", body: { name: "mieszko", password: PASSWORD }, ip: "198.51.100.99" });
    eq(r.status, 429, "po limicie (nawet dobre hasło)");
    assert(Number(r.headers.get("retry-after")) > 0, "Retry-After");
    eq(plugin.received.filter((m) => m.t === "auth").length, n, "auth nie powinien trafić do pluginu");
  });

  await step("rate-limit per IP → 429", async () => {
    const ip = "192.0.2.77";
    for (let i = 0; i < 4; i++) {
      const r = await req("/api/login", { method: "POST", body: { name: "Gracz" + i, password: BAD_PASSWORD }, ip });
      eq(r.status, 401, "próba " + (i + 1));
    }
    const r = await req("/api/login", { method: "POST", body: { name: "Bolko", password: PASSWORD }, ip });
    eq(r.status, 429, "IP po limicie");
  });

  let sidLink;
  await step("link z gry: jednorazowy, ustawia sesję, przekierowuje na /", async () => {
    const t = tok32();
    plugin.linkToken(t, USERS.mieszko.uuid, "Mieszko");
    await sleep(200);
    const r = await req("/login?t=" + t);
    eq(r.status, 302, "status");
    eq(r.headers.get("location"), "/", "Location");
    assert(r.sid && r.sid.length === 43, "cookie");
    eq(r.headers.get("referrer-policy"), "no-referrer", "Referrer-Policy");
    sidLink = r.sid;
    const me = await rpc(sidLink, "echo");
    eq(me.json.data.user.uuid, USERS.mieszko.uuid, "tożsamość z linku");
    const again = await req("/login?t=" + t);
    eq(again.status, 302, "drugi raz status");
    assert(again.headers.get("location").includes("blad=link"), "drugi raz → błąd: " + again.headers.get("location"));
    assert(!again.sid, "drugi raz bez sesji");
  });

  await step("link z gry: zły format, nieznany, wygasły, nowy unieważnia stary", async () => {
    for (const bad of ["abc", "<x>", tok32()]) {
      const r = await req("/login?t=" + encodeURIComponent(bad), { ip: "203.0.113.50" });
      assert(r.headers.get("location").includes("blad=link"), "zły: " + bad);
    }
    const t = tok32();
    plugin.linkToken(t, USERS.bolko.uuid, "Bolko");
    await sleep(2800);
    const r = await req("/login?t=" + t, { ip: "203.0.113.51" });
    assert(r.headers.get("location").includes("blad=link") && !r.sid, "wygasły");
    const t1 = tok32();
    const t2 = tok32();
    plugin.linkToken(t1, USERS.bolko.uuid, "Bolko");
    plugin.linkToken(t2, USERS.bolko.uuid, "Bolko");
    await sleep(200);
    const r1 = await req("/login?t=" + t1, { ip: "203.0.113.52" });
    assert(!r1.sid, "stary link po wydaniu nowego powinien być nieważny");
    const r2 = await req("/login?t=" + t2, { ip: "203.0.113.52" });
    assert(r2.sid, "nowy link działa");
    await req("/api/logout", { method: "POST", cookie: r2.sid });
  });

  await step("rate-limit RPC per sesja → 429", async () => {
    const rs = await Promise.all(Array.from({ length: 20 }, () => rpc(sidBolko, "echo")));
    const n429 = rs.filter((r) => r.status === 429).length;
    const n200 = rs.filter((r) => r.status === 200).length;
    assert(n429 >= 8 && n200 >= 6, `200=${n200} 429=${n429}`);
    await sleep(2000);
    const r = await rpc(sidBolko, "echo");
    eq(r.status, 200, "po odczekaniu");
  });

  await step("timeout pluginu → 504", async () => {
    const t0 = Date.now();
    const r = await rpc(sidBolko, "slow");
    const dt = Date.now() - t0;
    eq(r.status, 504, "status");
    assert(dt >= 1300 && dt < 6000, "czas " + dt);
    assert(r.json.error, "komunikat");
  });

  await step("drugie połączenie pluginu zastępuje pierwsze (4003)", async () => {
    const p2 = new FakePlugin(BASE, SECRET, handlers());
    await p2.opened;
    const info = await Promise.race([plugin.closed, sleep(3000).then(() => null)]);
    assert(info && info.code === 4003, "kod zamknięcia: " + JSON.stringify(info));
    plugin = p2;
    const r = await rpc(sidBolko, "echo");
    eq(r.status, 200, "rpc przez nowe połączenie");
  });

  await step("plugin offline: oczekujące RPC → 503 od razu; odczyty z cache, mutacje bez", async () => {
    await sleep(1100);
    const ov = await rpc(sidBolko, "overview");
    eq(ov.status, 200, "overview online");
    const hist = await rpc(sidBolko, "admin.npc.history", { id: "npc-x", days: 7 });
    eq(hist.status, 200, "admin.npc.history online");
    await rpc(sidBolko, "admin.npc.food", { id: "npc-x", delta: 10 });
    const health = await rpc(sidBolko, "admin.health", { range: "1h" });
    assert(health.status === 200 && health.json.ok, "health online");
    const pending = rpc(sidBolko, "hang");
    await sleep(300);
    const t0 = Date.now();
    await plugin.close();
    const r = await pending;
    eq(r.status, 503, "oczekujące rpc");
    assert(Date.now() - t0 < 1300, "503 powinno przyjść przed timeoutem");
    const c = await rpc(sidBolko, "overview");
    eq(c.status, 503, "status");
    eq(c.json.error, "Serwer gry jest offline", "error");
    eq(c.json.cached && c.json.cached.data, ov.json.data, "cache");
    const m = await rpc(sidBolko, "me");
    assert(m.json.cached && m.json.cached.data.uuid === USERS.bolko.uuid, "cache me");
    const h2 = await rpc(sidBolko, "admin.npc.history", { id: "npc-x", days: 7 });
    eq(h2.status, 503, "v2 offline");
    eq(h2.json.cached && h2.json.cached.data, hist.json.data, "odczyt v2 z cache DO");
    const m2 = await rpc(sidBolko, "admin.npc.food", { id: "npc-x", delta: 10 });
    assert(m2.status === 503 && !m2.json.cached, "mutacja v2 bez cache");
    const mut = await rpc(sidBolko, "treasury.deposit", { id: "c1", amount: 5 });
    eq(mut.status, 503, "mutacja");
    assert(!mut.json.cached, "mutacja bez cache");
    await sleep(1100);
    const healthOffline = await rpc(sidBolko, "admin.health", { range: "1h" });
    assert(healthOffline.status === 503 && !healthOffline.json.cached, "health offline bez cache po udanym odczycie");
    const st = await req("/api/status");
    eq(st.json.data.online, false, "status offline");
  });

  plugin = new FakePlugin(BASE, SECRET, handlers());
  await plugin.opened;
  await sleep(150);

  await step("wylogowanie: czyści cookie, sesja przestaje działać", async () => {
    const s = (await loginOk(USERS.bolko, "203.0.113.20")).sid;
    eq((await rpc(s, "echo")).status, 200, "przed");
    const r = await req("/api/logout", { method: "POST", cookie: s });
    eq(r.status, 200, "status");
    const c = r.setCookie.find((x) => x.startsWith("sid="));
    assert(c && c.includes("Max-Age=0"), "cookie czyszczące: " + c);
    eq((await rpc(s, "echo")).status, 401, "po");
    const x = await req("/api/logout", { method: "POST", xrw: false, cookie: sidBolko });
    eq(x.status, 403, "logout bez X-Requested-With");
  });

  await step("revoke: unieważnia wszystkie sesje gracza (hasło + link), innych nie", async () => {
    const s1 = (await loginOk(USERS.bolko, "203.0.113.21")).sid;
    const t = tok32();
    plugin.linkToken(t, USERS.bolko.uuid, "Bolko");
    await sleep(200);
    const s2 = (await req("/login?t=" + t, { ip: "203.0.113.21" })).sid;
    assert(s2, "sesja z linku");
    const pendingTok = tok32();
    plugin.linkToken(pendingTok, USERS.bolko.uuid, "Bolko");
    await sleep(100);
    eq((await rpc(s1, "echo")).status, 200, "s1 przed");
    eq((await rpc(sidLink, "echo")).status, 200, "inny gracz przed");
    plugin.revoke(USERS.bolko.uuid);
    await sleep(300);
    eq((await rpc(s1, "echo")).status, 401, "s1 po");
    eq((await rpc(s2, "echo")).status, 401, "s2 po");
    eq((await rpc(sidBolko, "echo")).status, 401, "pierwsza sesja po");
    eq((await rpc(sidLink, "echo")).status, 200, "inny gracz po");
    const l = await req("/login?t=" + pendingTok, { ip: "203.0.113.21" });
    assert(!l.sid, "oczekujący link po revoke nieważny");
  });

  await step("nieznane /api → 404, GET /login z metodą POST → 405", async () => {
    eq((await req("/api/nic")).status, 404, "404");
    eq((await req("/login", { method: "POST", body: {} })).status, 405, "405");
  });

  await step("hasła nie trafiają do logów wrangler dev", async () => {
    await sleep(300);
    assert(dev.log.length > 0, "brak logów");
    assert(!dev.log.includes(PASSWORD), "hasło w logach!");
    assert(!dev.log.includes(BAD_PASSWORD), "złe hasło w logach!");
  });

  plugin.close();
}

main()
  .catch((e) => {
    fail++;
    console.error(e);
  })
  .finally(() => {
    stopDev(dev);
    console.log(`\nWynik: ${ok} OK, ${fail} FAIL`);
    process.exit(fail ? 1 : 0);
  });
