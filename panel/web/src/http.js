// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — wspólne pomocnicze HTTP (nagłówki bezpieczeństwa, JSON, cookie).

export const secHeaders = {
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "X-Frame-Options": "DENY",
  "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
  "Cross-Origin-Resource-Policy": "same-origin",
};

export const SESSION_TTL_S = 7 * 24 * 60 * 60;

export function json(obj, status = 200, extra = {}) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...secHeaders, ...extra },
  });
}

export function sessionCookie(token) {
  return `sid=${token}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=${SESSION_TTL_S}`;
}

export function clearCookie() {
  return "sid=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0";
}

export function readCookie(request, name) {
  const raw = request.headers.get("Cookie") || "";
  for (const part of raw.split(";")) {
    const i = part.indexOf("=");
    if (i < 0) continue;
    if (part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return null;
}
