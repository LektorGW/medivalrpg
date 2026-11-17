// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — komunikacja z Workerem: logowanie, RPC, stan offline (503) i ostatnie dane do trybu tylko-do-odczytu.

const HEADERS = { "Content-Type": "application/json", "X-Requested-With": "panel" };
const CACHE_PREFIX = "panel-cache:";

export class ApiError extends Error {
  constructor(message, status, extra = {}) {
    super(message);
    this.status = status;
    Object.assign(this, extra);
  }
}

const listeners = new Set();
export const state = { offline: false, staleAt: null, authLost: false };

export function onState(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
function emit() {
  for (const fn of listeners) {
    try {
      fn(state);
    } catch (e) {
      console.error(e);
    }
  }
}

function setOffline(v, staleAt = null) {
  const changed = state.offline !== v || (v && staleAt && staleAt !== state.staleAt);
  state.offline = v;
  state.staleAt = v ? staleAt || state.staleAt : null;
  if (v) startStatusPoll();
  if (changed) emit();
}

let pollTimer = null;
function startStatusPoll() {
  if (pollTimer) return;
  pollTimer = setInterval(async () => {
    try {
      const r = await fetch("/api/status", { cache: "no-store" });
      const j = await r.json();
      if (j && j.data && j.data.online) {
        clearInterval(pollTimer);
        pollTimer = null;
        setOffline(false);
      }
    } catch {}
  }, 8000);
}

async function post(path, body) {
  let r;
  try {
    r = await fetch(path, { method: "POST", headers: HEADERS, body: JSON.stringify(body), credentials: "same-origin", cache: "no-store" });
  } catch {
    throw new ApiError("Brak połączenia z panelem — sprawdź internet", 0, { network: true });
  }
  let j = null;
  try {
    j = await r.json();
  } catch {}
  return { r, j: j || {} };
}

function cacheKey(method, params) {
  return CACHE_PREFIX + method + ":" + JSON.stringify(params || {});
}
function saveLocal(method, params, data) {
  try {
    sessionStorage.setItem(cacheKey(method, params), JSON.stringify({ at: Date.now(), data }));
  } catch {}
}
function loadLocal(method, params) {
  try {
    const v = sessionStorage.getItem(cacheKey(method, params));
    return v ? JSON.parse(v) : null;
  } catch {
    return null;
  }
}
export function clearLocal() {
  try {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const k = sessionStorage.key(i);
      if (k && k.startsWith(CACHE_PREFIX)) sessionStorage.removeItem(k);
    }
  } catch {}
}

function authLost() {
  if (!state.authLost) {
    state.authLost = true;
    clearLocal();
    emit();
  }
}

/** Surowe RPC. Rzuca ApiError (status 401/429/503/504/0 albo 200 z ok:false). */
export async function rpc(method, params = {}) {
  let { r, j } = await post("/api/rpc", { method, params });
  if (r.status === 429) {
    // Limit żądań na sesję (np. kilka akcji w sekundę) — jedno ponowienie po chwili.
    const wait = Math.min(5, Number(r.headers.get("Retry-After")) || 1) * 1000;
    await new Promise((res) => setTimeout(res, wait));
    ({ r, j } = await post("/api/rpc", { method, params }));
  }
  if (r.status === 401) {
    authLost();
    throw new ApiError(j.error || "Sesja wygasła — zaloguj się ponownie", 401);
  }
  if (r.status === 503 || r.status === 504) {
    if (r.status === 503) setOffline(true, j.cached ? j.cached.at : null);
    throw new ApiError(j.error || "Serwer gry jest offline", r.status, { offline: r.status === 503, cached: j.cached || null });
  }
  if (!r.ok) throw new ApiError(j.error || "Błąd " + r.status, r.status);
  if (state.offline) setOffline(false);
  if (j.ok !== true) throw new ApiError(j.error || "Operacja nie powiodła się", 200, { app: true });
  return j.data;
}

/** Odczyt z zapasem: gdy serwer gry offline — ostatnie dane (z DO albo z tej karty). → {data, stale, at} */
export async function read(method, params = {}) {
  // country.economy: prywatne dane kraju — bez kopii w sessionStorage (serwer sprawdza dostęp przy każdym odczycie).
  const cacheable = method !== "admin.health" && method !== "country.economy" && !method.startsWith("railway.") && !method.startsWith("world.") && !method.startsWith("firmy.");
  if (!cacheable) {
    try { sessionStorage.removeItem(cacheKey(method, params)); } catch {}
  }
  try {
    const data = await rpc(method, params);
    if (cacheable) saveLocal(method, params, data);
    return { data, stale: false, at: Date.now() };
  } catch (e) {
    if (cacheable && (e.status === 503 || e.status === 504 || e.network)) {
      const local = loadLocal(method, params);
      const best = e.cached && (!local || e.cached.at >= local.at) ? e.cached : local;
      if (best) {
        if (e.status === 503) setOffline(true, best.at);
        return { data: best.data, stale: true, at: best.at };
      }
    }
    throw e;
  }
}

/** Mutacja — w trybie offline od razu błąd (tylko do odczytu). */
export async function mutate(method, params = {}) {
  if (state.offline) throw new ApiError("Serwer gry jest offline — panel działa w trybie tylko do odczytu", 503, { offline: true });
  return rpc(method, params);
}

export async function login(name, password) {
  const { r, j } = await post("/api/login", { name, password });
  if (r.status === 503) setOffline(true);
  if (!r.ok || j.ok !== true) throw new ApiError(j.error || "Nie udało się zalogować", r.status);
  state.authLost = false;
  if (state.offline) setOffline(false);
  saveLocal("me", {}, j.data);
  return j.data;
}

export async function logout() {
  try {
    await post("/api/logout", {});
  } catch {}
  clearLocal();
}

export async function status() {
  try {
    const r = await fetch("/api/status", { cache: "no-store" });
    const j = await r.json();
    const online = !!(j && j.data && j.data.online);
    if (!online) setOffline(true);
    else if (state.offline) setOffline(false);
    return online;
  } catch {
    return false;
  }
}
