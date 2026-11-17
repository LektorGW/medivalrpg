// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Głos — Worker: routing /ws i /plugin do Durable Object, reszta to statyczne pliki z public/.
import { VoiceRoom } from "./room.js";

export { VoiceRoom };

function room(env) {
  return env.VOICE_ROOM.get(env.VOICE_ROOM.idFromName("main"));
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

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/plugin") {
      if (!(await secretMatches(request.headers.get("Authorization"), env.PLUGIN_SECRET))) {
        return new Response("Unauthorized", { status: 401, headers: { "WWW-Authenticate": "Bearer" } });
      }
      if (!isWebSocketUpgrade(request)) return new Response("Expected WebSocket", { status: 426 });
      return room(env).fetch(request);
    }

    if (url.pathname === "/ws") {
      if (!isWebSocketUpgrade(request)) return new Response("Expected WebSocket", { status: 426 });
      return room(env).fetch(request);
    }

    // Tu trafiają tylko ścieżki, które nie są plikami w public/ (assety serwowane są przed Workerem).
    return env.ASSETS.fetch(request);
  },
};
