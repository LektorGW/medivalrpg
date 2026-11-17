// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Durable Object "PanelHub": sesje przeglądarek + przekazywanie logowania i RPC do pluginu.
// Plugin jest autorytatywny (sam liczy uprawnienia przy każdym RPC); DO przekazuje tylko tożsamość z sesji.
// Hibernation API: socket pluginu przetrwa uśpienie; trwały stan (sesje, tokeny, limity, cache) jest w ctx.storage.
// Pamięć instancji (this.*) to wyłącznie rzeczy chwilowe: oczekujące odpowiedzi i limiter RPC.
// Hasła nigdy nie są logowane ani zapisywane — przechodzą tylko przez wiadomość `auth` do pluginu.
import { DurableObject } from "cloudflare:workers";
import { json, sessionCookie, clearCookie, readCookie, SESSION_TTL_S } from "./http.js";

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const LINK_TOKEN_RE = /^[A-Za-z0-9]{32}$/;
const SID_RE = /^[A-Za-z0-9_-]{43}$/;
const NAME_RE = /^[A-Za-z0-9_.-]{1,32}$/;
const METHOD_RE = /^[a-z][A-Za-z]*(\.[a-z][A-Za-z]*){0,3}$/;

const MAX_PLUGIN_MSG = 2 * 1024 * 1024;
const SESSION_TTL_MS = SESSION_TTL_S * 1000;
const MAX_SESSIONS_PER_USER = 10;
const CLEANUP_EVERY_MS = 6 * 60 * 60 * 1000;
const CACHE_MAX_CHARS = 120 * 1024;
const CACHE_TTL_MS = SESSION_TTL_MS;

/** Metody tylko-do-odczytu, których ostatni wynik (per użytkownik) pokazujemy, gdy plugin jest offline. */
const CACHEABLE = new Set([
  "me",
  "overview",
  "country.get",
  "admin.players",
  "admin.audit",
  // admin.health requires a fresh role check in the plugin; never serve an offline cache after demotion.
  // v2: pełne zarządzanie państwem NPC (PANEL.md, „Kształty odpowiedzi v2”)
  "admin.npc.history",
  "admin.npc.relations",
  "admin.npc.economy",
  "admin.npc.buildings",
  "admin.npc.army",
  "admin.npc.log",
  // Postep: epoki i odkrycia
  "postep.get",
  // Edytor planu państwa NPC (podkład mapy jest za duży na cache — tylko plan)
  "admin.npc.getPlan",
  "country.plan.get",
  "country.plan.buildings",
]);

const CLOSE_PLUGIN_REPLACED = 4003;
const OPEN = 1;

const ERR_OFFLINE = "Serwer gry jest offline";
const ERR_TIMEOUT = "Serwer gry nie odpowiada — spróbuj ponownie";
const ERR_NO_SESSION = "Nie jesteś zalogowany";

const enc = new TextEncoder();

async function sha256hex(s) {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function newSessionToken() {
  const b = crypto.getRandomValues(new Uint8Array(32));
  let s = btoa(String.fromCharCode(...b));
  return s.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function safeSend(ws, data) {
  try {
    ws.send(data);
    return true;
  } catch {
    return false;
  }
}

function num(v, def) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : def;
}

export class PanelHub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    /** @type {Map<string, {resolve:Function, timer:any, ws:WebSocket}>} */
    this.pending = new Map();
    /** @type {Map<string, {tok:number, ts:number}>} limiter RPC per sesja (w pamięci) */
    this.buckets = new Map();
    /** @type {Map<string, string>} ostatnio zapisany cache (żeby nie zapisywać tego samego) */
    this.cacheMem = new Map();
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}'));
  }

  // ---------------------------------------------------------------- konfiguracja

  get rpcTimeout() {
    return num(this.env.RPC_TIMEOUT_MS, 10000);
  }
  get linkTtl() {
    return num(this.env.LINK_TTL_MS, 10 * 60 * 1000);
  }
  get loginMax() {
    return num(this.env.LOGIN_RL_MAX, 10);
  }
  get loginWindow() {
    return num(this.env.LOGIN_RL_WINDOW_MS, 15 * 60 * 1000);
  }
  get rpcRate() {
    return num(this.env.RPC_RATE, 10);
  }
  get rpcBurst() {
    return num(this.env.RPC_BURST, 20);
  }

  // ---------------------------------------------------------------- plugin

  pluginSocket() {
    for (const ws of this.ctx.getWebSockets("plugin")) {
      if (ws.readyState === OPEN) {
        let a = null;
        try {
          a = ws.deserializeAttachment();
        } catch {}
        if (!a || !a.k) return ws;
      }
    }
    return null;
  }

  acceptPlugin() {
    for (const old of this.ctx.getWebSockets("plugin")) {
      try {
        old.serializeAttachment({ r: "p", k: 1 });
      } catch {}
      this.failPendingFor(old);
      try {
        old.close(CLOSE_PLUGIN_REPLACED, "replaced by new plugin connection");
      } catch {}
    }
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server, ["plugin"]);
    server.serializeAttachment({ r: "p", at: Date.now() });
    console.log("Panel: plugin połączony");
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, raw) {
    if (typeof raw !== "string" || raw.length > MAX_PLUGIN_MSG) return;
    const m = parseJson(raw);
    if (!isPlainObject(m) || typeof m.t !== "string") return;
    switch (m.t) {
      case "hello":
        console.log("Panel: hello od pluginu, v=" + String(m.v).slice(0, 8));
        return;
      case "auth-result":
      case "rpc-result": {
        if (typeof m.id !== "string") return;
        const p = this.pending.get(m.id);
        if (!p || p.kind !== m.t) return;
        this.pending.delete(m.id);
        clearTimeout(p.timer);
        p.resolve({ kind: "result", m });
        return;
      }
      case "link-token":
        await this.onLinkToken(m);
        return;
      case "revoke":
        if (typeof m.uuid === "string" && UUID_RE.test(m.uuid)) await this.revokeUser(m.uuid.toLowerCase());
        return;
      default:
        return;
    }
  }

  async webSocketClose(ws, code) {
    try {
      ws.close(code >= 3000 && code <= 4999 ? code : 1000, "bye");
    } catch {}
    this.failPendingFor(ws);
  }

  async webSocketError(ws) {
    this.failPendingFor(ws);
  }

  failPendingFor(ws) {
    for (const [id, p] of this.pending) {
      if (p.ws === ws) {
        this.pending.delete(id);
        clearTimeout(p.timer);
        p.resolve({ kind: "offline" });
      }
    }
  }

  /** Wysyła wiadomość do pluginu i czeka na odpowiedź o tym samym id. */
  ask(kind, msg) {
    const ws = this.pluginSocket();
    if (!ws) return Promise.resolve({ kind: "offline" });
    const id = crypto.randomUUID();
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve({ kind: "timeout" });
      }, this.rpcTimeout);
      this.pending.set(id, { kind, resolve, timer, ws });
      if (!safeSend(ws, JSON.stringify({ ...msg, id }))) {
        this.pending.delete(id);
        clearTimeout(timer);
        resolve({ kind: "offline" });
      }
    });
  }

  // ---------------------------------------------------------------- HTTP

  async fetch(request) {
    const url = new URL(request.url);
    if ((request.headers.get("Upgrade") || "").toLowerCase() === "websocket") {
      if (url.pathname === "/plugin") return this.acceptPlugin();
      return new Response("Not found", { status: 404 });
    }
    const ip = request.headers.get("X-Panel-IP") || "0.0.0.0";
    const key = request.method + " " + url.pathname;
    switch (key) {
      case "GET /api/status":
      case "HEAD /api/status":
        return json({ ok: true, data: { online: !!this.pluginSocket() } });
      case "POST /api/login":
        return this.login(request, ip);
      case "POST /api/logout":
        return this.logout(request);
      case "POST /api/rpc":
        return this.rpc(request, ip);
      case "GET /login":
        return this.linkLogin(url, ip);
      default:
        return json({ ok: false, error: "Nie znaleziono" }, 404);
    }
  }

  async login(request, ip) {
    const body = parseJson(await request.text());
    if (!isPlainObject(body)) return json({ ok: false, error: "Nieprawidłowe żądanie" }, 400);
    const { name, password } = body;
    if (typeof name !== "string" || !NAME_RE.test(name.trim())) return json({ ok: false, error: "Nieprawidłowy nick" }, 400);
    if (typeof password !== "string" || password.length < 1 || password.length > 256) {
      return json({ ok: false, error: "Podaj hasło" }, 400);
    }
    const nick = name.trim();
    const ipKey = "rl:ip:" + ip;
    const nameKey = "rl:n:" + nick.toLowerCase();
    const limited = (await this.rlCheck(ipKey)) || (await this.rlCheck(nameKey));
    if (limited) return this.tooMany(limited);
    await this.rlHit(ipKey);

    const res = await this.ask("auth-result", { t: "auth", name: nick, password, ip });
    if (res.kind === "offline") return json({ ok: false, error: ERR_OFFLINE, offline: true }, 503);
    if (res.kind === "timeout") return json({ ok: false, error: ERR_TIMEOUT }, 504);
    const m = res.m;
    if (m.ok !== true || typeof m.uuid !== "string" || !UUID_RE.test(m.uuid)) {
      await this.rlHit(nameKey);
      const err = typeof m.error === "string" && m.error ? m.error.slice(0, 300) : "Nieprawidłowy nick lub hasło";
      return json({ ok: false, error: err }, 401);
    }
    const uuid = m.uuid.toLowerCase();
    const realName = typeof m.name === "string" && NAME_RE.test(m.name) ? m.name : nick;
    const token = await this.createSession(uuid, realName);
    const me = await this.callRpc({ uuid, name: realName }, ip, "me", {});
    const data = me.ok ? me.data : { uuid, name: realName, admin: false, country: null };
    return json({ ok: true, data }, 200, { "Set-Cookie": sessionCookie(token) });
  }

  async logout(request) {
    const s = await this.getSession(request);
    if (s) {
      await this.ctx.storage.delete(["s:" + s.h, "us:" + s.uuid + ":" + s.h]);
      this.buckets.delete(s.h);
      const left = await this.ctx.storage.list({ prefix: "us:" + s.uuid + ":", limit: 1 });
      if (left.size === 0) await this.deletePrefix("c:" + s.uuid + ":");
    }
    return json({ ok: true }, 200, { "Set-Cookie": clearCookie() });
  }

  async rpc(request, ip) {
    const s = await this.getSession(request);
    if (!s) return json({ ok: false, error: ERR_NO_SESSION, auth: false }, 401, { "Set-Cookie": clearCookie() });

    const body = parseJson(await request.text());
    if (!isPlainObject(body)) return json({ ok: false, error: "Nieprawidłowe żądanie" }, 400);
    const { method } = body;
    const params = body.params === undefined || body.params === null ? {} : body.params;
    if (typeof method !== "string" || method.length > 64 || !METHOD_RE.test(method)) {
      return json({ ok: false, error: "Nieznana metoda" }, 400);
    }
    if (!isPlainObject(params)) return json({ ok: false, error: "Nieprawidłowe parametry" }, 400);

    // Token bucket per sesja.
    const now = Date.now();
    let b = this.buckets.get(s.h);
    if (!b) {
      b = { tok: this.rpcBurst, ts: now };
      this.buckets.set(s.h, b);
    }
    b.tok = Math.min(this.rpcBurst, b.tok + ((now - b.ts) * this.rpcRate) / 1000);
    b.ts = now;
    if (b.tok < 1) return json({ ok: false, error: "Za dużo żądań — zwolnij trochę" }, 429, { "Retry-After": "1" });
    b.tok -= 1;
    if (this.buckets.size > 5000) this.buckets.clear();

    const r = await this.callRpc({ uuid: s.uuid, name: s.name }, ip, method, params);
    if (r.offline || r.timeout) {
      const out = { ok: false, error: r.offline ? ERR_OFFLINE : ERR_TIMEOUT, offline: !!r.offline };
      if (CACHEABLE.has(method)) {
        const c = await this.ctx.storage.get(this.cacheKey(s.uuid, method, params));
        if (c && typeof c.s === "string" && now - c.at < CACHE_TTL_MS) out.cached = { at: c.at, data: JSON.parse(c.s) };
      }
      return json(out, r.offline ? 503 : 504);
    }
    return json(r.ok ? { ok: true, data: r.data } : { ok: false, error: r.error });
  }

  /** Jedno wywołanie RPC w pluginie; przy sukcesie metod odczytu zapisuje ostatni wynik do cache. */
  async callRpc(user, ip, method, params) {
    const res = await this.ask("rpc-result", { t: "rpc", user: { uuid: user.uuid, name: user.name, ip }, ip, method, params });
    if (res.kind === "offline") return { offline: true };
    if (res.kind === "timeout") return { timeout: true };
    const m = res.m;
    if (m.ok === true) {
      const data = m.data === undefined ? null : m.data;
      if (CACHEABLE.has(method)) await this.putCache(user.uuid, method, params, data);
      return { ok: true, data };
    }
    return { ok: false, error: typeof m.error === "string" && m.error ? m.error.slice(0, 500) : "Operacja nie powiodła się" };
  }

  cacheKey(uuid, method, params) {
    let p = JSON.stringify(params);
    if (p.length > 200) p = p.slice(0, 200);
    return "c:" + uuid + ":" + method + ":" + p;
  }

  async putCache(uuid, method, params, data) {
    const s = JSON.stringify(data);
    if (s.length > CACHE_MAX_CHARS) return;
    const key = this.cacheKey(uuid, method, params);
    if (this.cacheMem.get(key) === s) return;
    this.cacheMem.set(key, s);
    if (this.cacheMem.size > 2000) this.cacheMem.clear();
    await this.ctx.storage.put(key, { at: Date.now(), s });
  }

  async linkLogin(url, ip) {
    const fail = (why) =>
      new Response(null, { status: 302, headers: { Location: "/#/logowanie?blad=" + why, "Set-Cookie": clearCookie() } });
    const ipKey = "rl:ip:" + ip;
    if (await this.rlCheck(ipKey)) return fail("limit");
    const t = url.searchParams.get("t") || "";
    if (!LINK_TOKEN_RE.test(t)) {
      await this.rlHit(ipKey);
      return fail("link");
    }
    const h = await sha256hex(t);
    const rec = await this.ctx.storage.get("lt:" + h);
    if (!rec || rec.exp <= Date.now()) {
      if (rec) await this.ctx.storage.delete(["lt:" + h, "ltu:" + rec.uuid]);
      await this.rlHit(ipKey);
      return fail("link");
    }
    // Jednorazowy: kasujemy przed utworzeniem sesji.
    const del = ["lt:" + h];
    if ((await this.ctx.storage.get("ltu:" + rec.uuid)) === h) del.push("ltu:" + rec.uuid);
    await this.ctx.storage.delete(del);
    const token = await this.createSession(rec.uuid, rec.name);
    return new Response(null, { status: 302, headers: { Location: "/", "Set-Cookie": sessionCookie(token) } });
  }

  async onLinkToken(m) {
    const { token, uuid, name } = m;
    if (typeof token !== "string" || !LINK_TOKEN_RE.test(token)) return;
    if (typeof uuid !== "string" || !UUID_RE.test(uuid)) return;
    if (typeof name !== "string" || !NAME_RE.test(name)) return;
    const u = uuid.toLowerCase();
    const h = await sha256hex(token);
    const st = this.ctx.storage;
    const old = await st.get("ltu:" + u);
    if (old && old !== h) await st.delete("lt:" + old); // nowy link unieważnia poprzedni
    await st.put({ ["lt:" + h]: { uuid: u, name, exp: Date.now() + this.linkTtl }, ["ltu:" + u]: h });
    await this.scheduleAlarm();
  }

  // ---------------------------------------------------------------- sesje

  async createSession(uuid, name) {
    const st = this.ctx.storage;
    const token = newSessionToken();
    const h = await sha256hex(token);
    const now = Date.now();
    const mine = await st.list({ prefix: "us:" + uuid + ":" });
    if (mine.size >= MAX_SESSIONS_PER_USER) {
      const sorted = [...mine.entries()].sort((a, b) => a[1] - b[1]);
      const drop = sorted.slice(0, mine.size - MAX_SESSIONS_PER_USER + 1);
      const del = [];
      for (const [k] of drop) {
        del.push(k, "s:" + k.slice(k.lastIndexOf(":") + 1));
      }
      await st.delete(del);
    }
    await st.put({ ["s:" + h]: { uuid, name, c: now, exp: now + SESSION_TTL_MS }, ["us:" + uuid + ":" + h]: now });
    await this.scheduleAlarm();
    return token;
  }

  async getSession(request) {
    const sid = readCookie(request, "sid");
    if (!sid || !SID_RE.test(sid)) return null;
    const h = await sha256hex(sid);
    const rec = await this.ctx.storage.get("s:" + h);
    if (!rec) return null;
    if (rec.exp <= Date.now()) {
      await this.ctx.storage.delete(["s:" + h, "us:" + rec.uuid + ":" + h]);
      return null;
    }
    return { h, uuid: rec.uuid, name: rec.name };
  }

  async revokeUser(uuid) {
    const st = this.ctx.storage;
    const mine = await st.list({ prefix: "us:" + uuid + ":" });
    const del = [];
    for (const k of mine.keys()) {
      const h = k.slice(k.lastIndexOf(":") + 1);
      del.push(k, "s:" + h);
      this.buckets.delete(h);
    }
    const lt = await st.get("ltu:" + uuid);
    if (lt) del.push("lt:" + lt, "ltu:" + uuid);
    for (let i = 0; i < del.length; i += 128) await st.delete(del.slice(i, i + 128));
    await this.deletePrefix("c:" + uuid + ":");
    for (const k of [...this.cacheMem.keys()]) if (k.startsWith("c:" + uuid + ":")) this.cacheMem.delete(k);
    console.log("Panel: unieważniono sesje gracza " + uuid + " (" + (del.length >> 1) + ")");
  }

  async deletePrefix(prefix) {
    const st = this.ctx.storage;
    const keys = [...(await st.list({ prefix })).keys()];
    for (let i = 0; i < keys.length; i += 128) await st.delete(keys.slice(i, i + 128));
  }

  // ---------------------------------------------------------------- limity logowania (trwałe)

  async rlCheck(key) {
    const rec = await this.ctx.storage.get(key);
    const now = Date.now();
    if (!rec || rec.reset <= now) return 0;
    return rec.n >= this.loginMax ? rec.reset - now : 0;
  }

  async rlHit(key) {
    const st = this.ctx.storage;
    const now = Date.now();
    const rec = await st.get(key);
    const next = !rec || rec.reset <= now ? { n: 1, reset: now + this.loginWindow } : { n: rec.n + 1, reset: rec.reset };
    await st.put(key, next);
    await this.scheduleAlarm();
  }

  tooMany(ms) {
    const sec = Math.max(1, Math.ceil(ms / 1000));
    const min = Math.ceil(sec / 60);
    return json({ ok: false, error: `Za dużo prób logowania. Spróbuj ponownie za ${min} min.` }, 429, { "Retry-After": String(sec) });
  }

  // ---------------------------------------------------------------- sprzątanie

  async scheduleAlarm() {
    const cur = await this.ctx.storage.getAlarm();
    if (cur === null) await this.ctx.storage.setAlarm(Date.now() + CLEANUP_EVERY_MS);
  }

  async alarm() {
    const st = this.ctx.storage;
    const now = Date.now();
    const all = await st.list();
    const del = [];
    let left = 0;
    for (const [k, v] of all) {
      let dead = false;
      if (k.startsWith("s:")) dead = !v || v.exp <= now;
      else if (k.startsWith("us:")) {
        // Indeks sesji umiera razem z sesją — także gdy ta wygasa w tym samym przebiegu.
        const s = all.get("s:" + k.slice(k.lastIndexOf(":") + 1));
        dead = !s || s.exp <= now;
      }
      else if (k.startsWith("lt:")) dead = !v || v.exp <= now;
      else if (k.startsWith("ltu:")) dead = !all.has("lt:" + v) || all.get("lt:" + v).exp <= now;
      else if (k.startsWith("rl:")) dead = !v || v.reset <= now;
      else if (k.startsWith("c:")) dead = !v || now - v.at > CACHE_TTL_MS;
      if (dead) del.push(k);
      else left++;
    }
    for (let i = 0; i < del.length; i += 128) await st.delete(del.slice(i, i + 128));
    if (left > 0) await st.setAlarm(now + CLEANUP_EVERY_MS);
  }
}
