// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Worker: /plugin (WebSocket pluginu, Bearer PANEL_SECRET), /api/* i /login do Durable Object,
// reszta to statyczne pliki SPA z public/ (obsługiwane przez assets jeszcze przed Workerem).
import { PanelHub } from "./hub.js";
import { json, secHeaders } from "./http.js";

export { PanelHub };

const MAX_BODY = 16 * 1024;
/** Plan państwa NPC (admin.npc.setPlan: do 200 stref, 200 dróg, 500 budynków) może być większy niż zwykłe RPC. */
const MAX_PLAN_BODY = 256 * 1024;
const BIG_BODY_METHODS = new Set(["admin.npc.setPlan", "country.plan.set", "country.flag.upload"]);

/** Duże ciało jest dozwolone tylko dla metod z BIG_BODY_METHODS. */
function bigBodyAllowed(text) {
  try {
    const j = JSON.parse(text);
    return !!j && typeof j === "object" && BIG_BODY_METHODS.has(j.method);
  } catch {
    return false;
  }
}

function hub(env) {
  return env.PANEL_HUB.get(env.PANEL_HUB.idFromName("main"));
}

function isWebSocketUpgrade(request) {
  return (request.headers.get("Upgrade") || "").toLowerCase() === "websocket";
}

const enc = new TextEncoder();

/** Porównanie w czasie stałym: porównujemy skróty SHA-256 (równa długość) przez timingSafeEqual. */
async function secretMatches(authHeader, secret) {
  if (typeof secret !== "string" || secret.trim() === "" || !authHeader) return false;
  const m = /^Bearer[ \t]+(.+)$/i.exec(authHeader.trim());
  if (!m) return false;
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(m[1].trim())),
    crypto.subtle.digest("SHA-256", enc.encode(secret.trim())),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

/** Ochrona POST: własny nagłówek (wymusza preflight CORS, którego nie obsługujemy) + Origin/Sec-Fetch-Site. */
function postGuard(request, url) {
  if (request.headers.get("X-Requested-With") !== "panel") return "Brak nagłówka X-Requested-With";
  const origin = request.headers.get("Origin");
  if (origin && origin !== url.origin) return "Niedozwolone pochodzenie żądania";
  const site = request.headers.get("Sec-Fetch-Site");
  if (site && site !== "same-origin" && site !== "none") return "Niedozwolone pochodzenie żądania";
  return null;
}

/** Przekazuje żądanie do DO (bez podążania za 302 z /login) z IP klienta ustawionym przez Workera (nagłówek klienta jest nadpisywany). */
function forward(request, env, url, body) {
  const headers = new Headers();
  for (const name of ["Cookie", "Content-Type", "User-Agent"]) {
    const v = request.headers.get(name);
    if (v) headers.set(name, v);
  }
  headers.set("X-Panel-IP", request.headers.get("CF-Connecting-IP") || "127.0.0.1");
  return hub(env).fetch(new Request(url.toString(), { method: request.method, headers, body, redirect: "manual" }));
}

async function readCapped(request, max) {
  const len = Number(request.headers.get("Content-Length") || "0");
  if (len > max) return null;
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      try {
        await reader.cancel();
      } catch {}
      return null;
    }
    chunks.push(value);
  }
  const buf = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    buf.set(c, off);
    off += c.byteLength;
  }
  return new TextDecoder().decode(buf);
}

function withHeaders(resp) {
  const r = new Response(resp.body, resp);
  for (const [k, v] of Object.entries(secHeaders)) r.headers.set(k, v);
  return r;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/plugin") {
      if (!(await secretMatches(request.headers.get("Authorization"), env.PANEL_SECRET))) {
        return new Response("Unauthorized", { status: 401, headers: { "WWW-Authenticate": "Bearer" } });
      }
      if (!isWebSocketUpgrade(request)) return new Response("Expected WebSocket", { status: 426 });
      return hub(env).fetch(request);
    }

    if (url.pathname === "/login") {
      if (request.method !== "GET") return json({ ok: false, error: "Metoda niedozwolona" }, 405);
      return withHeaders(await forward(request, env, url, undefined));
    }

    if (url.pathname.startsWith("/api/")) {
      if (request.method === "POST") {
        const bad = postGuard(request, url);
        if (bad) return json({ ok: false, error: bad }, 403);
        const body = await readCapped(request, url.pathname === "/api/rpc" ? MAX_PLAN_BODY : MAX_BODY);
        if (body === null || (body.length > MAX_BODY && !bigBodyAllowed(body))) return json({ ok: false, error: "Za duże żądanie" }, 413);
        return withHeaders(await forward(request, env, url, body));
      }
      if (request.method === "GET" || request.method === "HEAD") {
        return withHeaders(await forward(request, env, url, undefined));
      }
      return json({ ok: false, error: "Metoda niedozwolona" }, 405);
    }

    return env.ASSETS.fetch(request);
  },
};
