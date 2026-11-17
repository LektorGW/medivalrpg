// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Głos — Durable Object "VoiceRoom": głupi router między pluginem Paper a przeglądarkami.
// Hibernation API: cały stan, który musi przetrwać uśpienie, jest w tagach/attachmentach socketów
// albo w ctx.storage. Pamięć instancji (this.*) to wyłącznie cache, który można odtworzyć.
import { DurableObject } from "cloudflare:workers";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TOKEN_RE = /^[A-Za-z0-9]{32}$/;
const MODES = new Set(["whisper", "normal", "shout"]);

const STUN = { urls: "stun:stun.cloudflare.com:3478" };

const MAX_CLIENT_MSG = 16 * 1024; // SDP audio to ~2-6 KB
const MAX_PLUGIN_MSG = 1024 * 1024; // state dla wielu graczy
const RATE_PER_SEC = 50; // średnio maks. 50 wiadomości/s od przeglądarki...
const RATE_BURST = 150; // ...z zapasem na serię kandydatów ICE przy zestawianiu wielu połączeń

const UNUSED_TOKEN_TTL_MS = 15 * 60 * 1000; // 15 min do pierwszego użycia
const USED_TOKEN_IDLE_TTL_MS = 24 * 60 * 60 * 1000; // bezpiecznik: użyty token bez połączenia przez 24 h
const ICE_CACHE_MS = 12 * 60 * 60 * 1000; // dane TURN ważne 24 h, odświeżamy co 12 h
const ICE_FAIL_CACHE_MS = 60 * 1000;

// Kody zamknięcia (4000 wymagany przez protokół dla kick, reszta to doprecyzowanie).
const CLOSE_KICK = 4000;
const CLOSE_INVALID_TOKEN = 4001;
const CLOSE_OTHER_TAB = 4002;
const CLOSE_PLUGIN_REPLACED = 4003;
const CLOSE_RATE_LIMIT = 1008;

const REASON_INVALID = "Link jest nieważny. Wpisz /glos w grze, aby dostać nowy.";
const REASON_OTHER_TAB = "Połączono w innej karcie";
const REASON_KICK_DEFAULT = "Rozłączono przez serwer";

const OPEN = 1; // WebSocket.OPEN

function parseJson(msg, maxLen) {
  if (typeof msg !== "string" || msg.length > maxLen) return null;
  try {
    const v = JSON.parse(msg);
    return v && typeof v === "object" && !Array.isArray(v) && typeof v.t === "string" ? v : null;
  } catch {
    return null;
  }
}

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function safeSend(ws, data) {
  try {
    ws.send(data);
    return true;
  } catch {
    return false;
  }
}

export class VoiceRoom extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    /** @type {WeakMap<WebSocket, any>} cache attachmentów (po hibernacji odtwarzany leniwie) */
    this.attCache = new WeakMap();
    /** @type {WeakMap<WebSocket, {tok:number, ts:number}>} limiter wiadomości per socket */
    this.buckets = new WeakMap();
    this.lastClientsKey = null;
    this.iceMem = null;
    this.icePending = null;
    // Keepalive przeglądarki obsługiwany przez runtime bez budzenia DO (nie kosztuje wywołania).
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}'));
  }

  // ---------------------------------------------------------------- pomocnicze

  att(ws) {
    let a = this.attCache.get(ws);
    if (a === undefined) {
      try {
        a = ws.deserializeAttachment() || null;
      } catch {
        a = null;
      }
      this.attCache.set(ws, a);
    }
    return a;
  }

  setAtt(ws, a) {
    try {
      ws.serializeAttachment(a);
    } catch {}
    this.attCache.set(ws, a);
  }

  unusedTtl() {
    const v = Number(this.env.TOKEN_TTL_MS);
    return Number.isFinite(v) && v > 0 ? v : UNUSED_TOKEN_TTL_MS;
  }

  /** Otwarte, nie-wyrzucone sockety przeglądarek. */
  clientSockets(exclude) {
    const out = [];
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === exclude || ws.readyState !== OPEN) continue;
      const a = this.att(ws);
      if (a && a.r === "c" && !a.k) out.push([ws, a]);
    }
    return out;
  }

  clientSocketsFor(id, exclude) {
    const out = [];
    for (const ws of this.ctx.getWebSockets("c:" + id)) {
      if (ws === exclude || ws.readyState !== OPEN) continue;
      const a = this.att(ws);
      if (a && a.r === "c" && !a.k) out.push(ws);
    }
    return out;
  }

  pluginSocket() {
    for (const ws of this.ctx.getWebSockets("plugin")) {
      if (ws.readyState === OPEN) return ws;
    }
    return null;
  }

  sendPlugin(obj) {
    const p = this.pluginSocket();
    if (p) safeSend(p, JSON.stringify(obj));
  }

  /** Wysyła pluginowi listę podłączonych przeglądarek, jeśli się zmieniła (albo force). */
  sendClients(force = false, exclude = undefined) {
    const p = this.pluginSocket();
    if (!p) {
      this.lastClientsKey = null;
      return;
    }
    const ids = [...new Set(this.clientSockets(exclude).map(([, a]) => a.id))].sort();
    const key = ids.join(",");
    if (!force && key === this.lastClientsKey) return;
    if (safeSend(p, JSON.stringify({ t: "clients", ids }))) this.lastClientsKey = key;
  }

  kickSocket(ws, reason, code) {
    const a = this.att(ws);
    if (a) this.setAtt(ws, { ...a, k: 1 });
    safeSend(ws, JSON.stringify({ t: "kicked", reason }));
    try {
      ws.close(code, "kicked");
    } catch {}
  }

  broadcastSpeaking(id, on, exclude) {
    this.sendPlugin({ t: "speaking", id, on });
    const msg = JSON.stringify({ t: "speaking", id, on });
    for (const [ws] of this.clientSockets(exclude)) safeSend(ws, msg);
  }

  // ---------------------------------------------------------------- HTTP / upgrade

  async fetch(request) {
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }
    const url = new URL(request.url);
    if (url.pathname === "/plugin") return this.acceptPlugin();
    if (url.pathname === "/ws") return this.acceptClient(url.searchParams.get("t") || "");
    return new Response("Not found", { status: 404 });
  }

  acceptPlugin() {
    // Nowe połączenie pluginu zamyka poprzednie.
    for (const old of this.ctx.getWebSockets("plugin")) {
      const a = this.att(old);
      if (a) this.setAtt(old, { ...a, k: 1 });
      try {
        old.close(CLOSE_PLUGIN_REPLACED, "replaced by new plugin connection");
      } catch {}
    }
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server, ["plugin"]);
    this.setAtt(server, { r: "p" });
    this.lastClientsKey = null; // `clients` zostanie wysłane po `hello`
    return new Response(null, { status: 101, webSocket: client });
  }

  async acceptClient(token) {
    const st = this.ctx.storage;
    const now = Date.now();
    let rec = TOKEN_RE.test(token) ? await st.get("tok:" + token) : undefined;
    if (rec && !rec.u && now > rec.c + this.unusedTtl()) {
      await this.deleteToken(token, rec);
      rec = undefined;
    }

    const { 0: client, 1: server } = new WebSocketPair();

    if (!rec) {
      // Akceptujemy (bez hibernacji — socket żyje chwilę), wysyłamy `kicked` i zamykamy.
      server.accept();
      server.send(JSON.stringify({ t: "kicked", reason: REASON_INVALID }));
      server.close(CLOSE_INVALID_TOKEN, "invalid token");
      return new Response(null, { status: 101, webSocket: client });
    }

    if (!rec.u) {
      rec = { ...rec, u: true, fu: now };
      await st.put("tok:" + token, rec);
    }

    const ice = await this.getIce();

    // getIce() może czekać na fetch TURN — w tym czasie plugin mógł wysłać `kick`/nowy token.
    if (!(await st.get("tok:" + token))) {
      server.accept();
      server.send(JSON.stringify({ t: "kicked", reason: REASON_INVALID }));
      server.close(CLOSE_INVALID_TOKEN, "invalid token");
      return new Response(null, { status: 101, webSocket: client });
    }

    // Nowe połączenie z tym samym uuid zamyka stare.
    let wasSpeaking = false;
    for (const old of this.ctx.getWebSockets("c:" + rec.id)) {
      const oa = this.att(old);
      if (oa && oa.s && !oa.k) wasSpeaking = true;
      this.kickSocket(old, REASON_OTHER_TAB, CLOSE_OTHER_TAB);
    }

    this.ctx.acceptWebSocket(server, ["c:" + rec.id]);
    this.setAtt(server, { r: "c", id: rec.id, n: rec.n, s: false });
    server.send(JSON.stringify({ t: "welcome", id: rec.id, n: rec.n, ice }));
    this.sendClients();
    // Stara karta mówiła — nowa zaczyna od ciszy; inaczej wskaźnik „mówi” zostałby włączony na stałe.
    if (wasSpeaking) this.broadcastSpeaking(rec.id, false, server);
    return new Response(null, { status: 101, webSocket: client });
  }

  // ---------------------------------------------------------------- wiadomości

  async webSocketMessage(ws, msg) {
    const a = this.att(ws);
    if (!a || a.k) return;
    if (a.r === "p") return this.onPluginMessage(msg);
    if (a.r === "c") return this.onClientMessage(ws, a, msg);
  }

  async onPluginMessage(raw) {
    const m = parseJson(raw, MAX_PLUGIN_MSG);
    if (!m) return;
    switch (m.t) {
      case "hello":
        this.sendClients(true);
        return;

      case "state": {
        if (!isPlainObject(m.to)) return;
        // Jedno przejście po socketach → mapa id → sockety.
        const byId = new Map();
        for (const [ws, a] of this.clientSockets()) {
          const list = byId.get(a.id);
          if (list) list.push(ws);
          else byId.set(a.id, [ws]);
        }
        for (const id in m.to) {
          const socks = byId.get(id);
          const p = m.to[id];
          if (!socks || !isPlainObject(p)) continue;
          const out = JSON.stringify(Object.assign({ t: "state" }, p, { t: "state" }));
          for (const ws of socks) safeSend(ws, out);
        }
        return;
      }

      case "token": {
        const { token, id, n } = m;
        if (typeof token !== "string" || !TOKEN_RE.test(token)) return;
        if (typeof id !== "string" || !UUID_RE.test(id)) return;
        if (typeof n !== "string" || n.length === 0 || n.length > 64) return;
        await this.registerToken(token, id, n);
        return;
      }

      case "kick": {
        const { id } = m;
        if (typeof id !== "string" || !UUID_RE.test(id)) return;
        const reason = typeof m.reason === "string" && m.reason ? m.reason.slice(0, 300) : REASON_KICK_DEFAULT;
        for (const ws of this.ctx.getWebSockets("c:" + id)) this.kickSocket(ws, reason, CLOSE_KICK);
        await this.invalidateTokens(id);
        this.sendClients();
        return;
      }

      case "forget": {
        // Wspomniane w PROTOCOL.md przy tokenie, ale bez definicji: unieważnia tokeny bez rozłączania.
        if (typeof m.id === "string" && UUID_RE.test(m.id)) await this.invalidateTokens(m.id);
        return;
      }

      default:
        return; // m.in. `speaking` od pluginu (nieużywane) i nieznane typy
    }
  }

  onClientMessage(ws, a, raw) {
    // Limiter: token bucket per socket (stan w pamięci; po hibernacji startuje od pełnego wiadra).
    const now = Date.now();
    let b = this.buckets.get(ws);
    if (!b) {
      b = { tok: RATE_BURST, ts: now };
      this.buckets.set(ws, b);
    }
    b.tok = Math.min(RATE_BURST, b.tok + ((now - b.ts) * RATE_PER_SEC) / 1000);
    b.ts = now;
    if (b.tok < 1) {
      this.setAtt(ws, { ...a, k: 1, s: false });
      try {
        ws.close(CLOSE_RATE_LIMIT, "rate limit");
      } catch {}
      this.sendClients(false, ws);
      if (a.s) this.broadcastSpeaking(a.id, false, ws);
      return;
    }
    b.tok -= 1;

    const m = parseJson(raw, MAX_CLIENT_MSG);
    if (!m) return;

    switch (m.t) {
      case "signal": {
        const { to, data } = m;
        if (typeof to !== "string" || !UUID_RE.test(to) || to === a.id) return;
        if (!isPlainObject(data) || !("sdp" in data || "candidate" in data)) return;
        const targets = this.clientSocketsFor(to);
        if (!targets.length) return;
        const out = JSON.stringify({ t: "signal", from: a.id, data });
        for (const t of targets) safeSend(t, out);
        return;
      }

      case "speaking": {
        if (typeof m.on !== "boolean" || m.on === !!a.s) return;
        this.setAtt(ws, { ...a, s: m.on });
        this.broadcastSpeaking(a.id, m.on);
        return;
      }

      case "mode": {
        if (typeof m.mode !== "string" || !MODES.has(m.mode)) return;
        this.sendPlugin({ t: "mode", id: a.id, mode: m.mode });
        return;
      }

      default:
        return;
    }
  }

  async webSocketClose(ws, code, reason, wasClean) {
    try {
      ws.close(code >= 3000 && code <= 4999 ? code : 1000, "bye");
    } catch {}
    await this.onSocketGone(ws);
  }

  async webSocketError(ws, error) {
    await this.onSocketGone(ws);
  }

  async onSocketGone(ws) {
    const a = this.att(ws);
    if (!a || a.gone) return;
    this.setAtt(ws, { ...a, gone: 1, k: 1 });
    if (a.r === "p") {
      this.lastClientsKey = null;
      return;
    }
    if (a.r !== "c") return;
    this.sendClients(false, ws);
    const stillHere = this.clientSocketsFor(a.id, ws).length > 0;
    if (stillHere) return;
    if (a.s) this.broadcastSpeaking(a.id, false, ws);
    await this.touchToken(a.id);
  }

  // ---------------------------------------------------------------- tokeny

  async registerToken(token, id, n) {
    const st = this.ctx.storage;
    const [oldTok, clash] = await Promise.all([st.get("pid:" + id), st.get("tok:" + token)]);
    const del = [];
    if (oldTok && oldTok !== token) del.push("tok:" + oldTok); // nowy token unieważnia stare
    if (clash && clash.id !== id) {
      const clashPid = await st.get("pid:" + clash.id);
      if (clashPid === token) del.push("pid:" + clash.id);
    }
    if (del.length) await st.delete(del);
    await st.put({ ["tok:" + token]: { id, n, c: Date.now(), u: false }, ["pid:" + id]: token });
    await this.scheduleAlarm(Date.now() + this.unusedTtl());
  }

  async invalidateTokens(id) {
    const st = this.ctx.storage;
    const tok = await st.get("pid:" + id);
    const del = ["pid:" + id];
    if (tok) del.push("tok:" + tok);
    await st.delete(del);
  }

  async deleteToken(token, rec) {
    const st = this.ctx.storage;
    const del = ["tok:" + token];
    if (rec && (await st.get("pid:" + rec.id)) === token) del.push("pid:" + rec.id);
    await st.delete(del);
  }

  /** Zapisuje czas ostatniego rozłączenia użytego tokenu (do bezpiecznika 24 h). */
  async touchToken(id) {
    const st = this.ctx.storage;
    const tok = await st.get("pid:" + id);
    if (!tok) return;
    const rec = await st.get("tok:" + tok);
    if (!rec) return;
    const now = Date.now();
    await st.put("tok:" + tok, { ...rec, ls: now });
    if (rec.u) await this.scheduleAlarm(now + USED_TOKEN_IDLE_TTL_MS);
  }

  async scheduleAlarm(at) {
    const cur = await this.ctx.storage.getAlarm();
    if (cur === null || at < cur) await this.ctx.storage.setAlarm(at);
  }

  /** Sprzątanie wygasłych tokenów — bez pollingu, alarm ustawiany na najbliższe wygaśnięcie. */
  async alarm() {
    const st = this.ctx.storage;
    const now = Date.now();
    const [toks, pids] = await Promise.all([st.list({ prefix: "tok:" }), st.list({ prefix: "pid:" })]);
    const online = new Set(this.clientSockets().map(([, a]) => a.id));
    const del = [];
    let next = Infinity;
    for (const [key, rec] of toks) {
      if (!rec || typeof rec !== "object") {
        del.push(key);
        continue;
      }
      let exp;
      if (!rec.u) exp = rec.c + this.unusedTtl();
      else if (online.has(rec.id)) continue; // aktywny — ważny do kick
      else exp = (rec.ls || rec.fu || rec.c) + USED_TOKEN_IDLE_TTL_MS;
      if (exp <= now) {
        del.push(key);
        const token = key.slice(4);
        if (pids.get("pid:" + rec.id) === token) del.push("pid:" + rec.id);
      } else if (exp < next) next = exp;
    }
    // Osierocone wskazania pid → nieistniejący token.
    for (const [pkey, token] of pids) {
      if (!toks.has("tok:" + token) && !del.includes(pkey)) del.push(pkey);
    }
    for (let i = 0; i < del.length; i += 128) await st.delete(del.slice(i, i + 128));
    if (next !== Infinity) await st.setAlarm(next);
  }

  // ---------------------------------------------------------------- ICE / TURN

  async getIce() {
    const keyId = this.env.TURN_KEY_ID;
    const apiToken = this.env.TURN_KEY_API_TOKEN;
    if (!keyId || !apiToken) return [STUN];
    const now = Date.now();
    if (this.iceMem && now < this.iceMem.exp) return this.iceMem.servers;
    // Po hibernacji pamięć znika — trzymamy kopię w storage, żeby nie generować danych TURN przy każdym wybudzeniu.
    const stored = await this.ctx.storage.get("ice");
    if (stored && now < stored.exp && Array.isArray(stored.servers)) {
      this.iceMem = stored;
      return stored.servers;
    }
    if (!this.icePending) {
      this.icePending = this.fetchIce(keyId, apiToken).finally(() => {
        this.icePending = null;
      });
    }
    return this.icePending;
  }

  async fetchIce(keyId, apiToken) {
    try {
      const res = await fetch(
        `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`,
        {
          method: "POST",
          headers: { Authorization: `Bearer ${apiToken}`, "Content-Type": "application/json" },
          body: JSON.stringify({ ttl: 86400 }),
          signal: AbortSignal.timeout(4000),
        },
      );
      if (!res.ok) throw new Error("TURN HTTP " + res.status);
      const j = await res.json();
      // Aktualnie: { iceServers: [ {urls:[stun]}, {urls:[turn...], username, credential} ] }.
      // Starsza wersja API zwracała pojedynczy obiekt — obsługujemy oba.
      const list = Array.isArray(j?.iceServers) ? j.iceServers : isPlainObject(j?.iceServers) ? [j.iceServers] : [];
      const turn = [];
      for (const s of list) {
        if (!isPlainObject(s)) continue;
        const urls = (Array.isArray(s.urls) ? s.urls : [s.urls]).filter(
          (u) => typeof u === "string" && /^turns?:/.test(u) && !/:53(\?|$)/.test(u), // port 53 bywa blokowany w przeglądarkach
        );
        if (urls.length && typeof s.username === "string" && typeof s.credential === "string") {
          turn.push({ urls, username: s.username, credential: s.credential });
        }
      }
      if (!turn.length) throw new Error("TURN: brak serwerów w odpowiedzi");
      const entry = { servers: [STUN, ...turn], exp: Date.now() + ICE_CACHE_MS };
      this.iceMem = entry;
      await this.ctx.storage.put("ice", entry);
      return entry.servers;
    } catch (e) {
      console.warn("TURN niedostępny, używam samego STUN:", String(e));
      this.iceMem = { servers: [STUN], exp: Date.now() + ICE_FAIL_CACHE_MS };
      return [STUN];
    }
  }
}
