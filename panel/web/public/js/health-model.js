// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — wydajność serwera (admin.health): normalizacja odpowiedzi, poziomy ostrzeżeń, opisy po polsku.
// Czysty moduł (bez DOM) — testy jednostkowe w test/health.test.mjs.

export const RANGES = [
  { id: "1h", label: "1 h", window: "ostatnia godzina", tableStepMs: 5 * 60 * 1000 },
  { id: "6h", label: "6 h", window: "ostatnie 6 godzin", tableStepMs: 30 * 60 * 1000 },
  { id: "24h", label: "24 h", window: "ostatnia doba", tableStepMs: 60 * 60 * 1000 },
];

export const DEFAULT_PLUGINS = [
  { id: "ludnosc", name: "Ludność" },
  { id: "armia", name: "Armia" },
  { id: "skarbiec", name: "Skarbiec" },
  { id: "wladcy", name: "Władcy" },
  { id: "zycie", name: "Życie" },
  { id: "kolej", name: "Kolej" },
  { id: "szlaki", name: "Szlaki" },
];

/** Rodzaje NPC z current.kinds (kolejność kafelków). */
export const KINDS = [
  { key: "citizens", label: "Mieszkańcy (Ludność)" },
  { key: "builders", label: "Budowniczowie" },
  { key: "soldiers", label: "Żołnierze (Armia)" },
  { key: "rulers", label: "Władcy NPC (Armia)" },
  { key: "merchants", label: "Bankierzy i kupcy" },
  { key: "onDuty", label: "Pospolite ruszenie / straż ogniowa" },
  { key: "wladcy", label: "Encje Władców" },
  { key: "railBuilders", label: "Budowniczowie kolei" },
  { key: "trainDrivers", label: "Maszyniści" },
  { key: "freightDrivers", label: "Woźnice i szyprowie (Szlaki)" },
  { key: "freightAnimals", label: "Zwierzęta transportowe (Szlaki)" },
  { key: "helpers", label: "Encje pomocnicze (ciała, hitboxy, napisy)" },
];

const DEFAULT_THRESHOLDS = { tps: 18, tpsCritical: 15, mspt: 45, msptCritical: 50, npc: 0, npcCritical: 0, entities: 0, entitiesCritical: 0, npcPlugin: {}, sustain: 1 };

const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);
const int = (v) => (num(v) === null ? null : Math.round(v));

export function rangeOf(id) {
  return RANGES.find((r) => r.id === id) || RANGES[0];
}

/** Liczba z przecinkiem dziesiętnym (pl-PL). */
export function fmtDec(v, digits = 1) {
  const n = num(v);
  if (n === null) return "—";
  return n.toLocaleString("pl-PL", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/** Poziom wartości względem progów: "ok" | "warn" | "crit". lower = ostrzega poniżej progu (TPS). Próg ≤ 0 = wyłączony. */
export function level(value, warn, crit, lower = false) {
  const v = num(value);
  if (v === null) return "ok";
  const over = (t) => num(t) !== null && t > 0 && (lower ? v < t : v > t);
  if (over(crit)) return "crit";
  if (over(warn)) return "warn";
  return "ok";
}

/** Opis kodu ostrzeżenia. */
export function warnLabel(code, plugins = DEFAULT_PLUGINS) {
  const c = String(code || "");
  if (c === "TPS") return "TPS";
  if (c === "MSPT") return "Czas ticka";
  if (c === "NPC") return "Liczba NPC";
  if (c === "ENTITIES") return "Liczba encji";
  if (c.startsWith("NPC_")) {
    const id = c.slice(4).toLowerCase();
    const p = plugins.find((x) => x.id === id);
    return "NPC: " + (p ? p.name : id);
  }
  return c;
}

/** „12 min”, „1 h 5 min”, „40 s” — czas trwania epizodu (end null = trwa). */
export function fmtDuration(start, end, now = Date.now()) {
  const ms = Math.max(0, (num(end) ?? now) - (num(start) ?? now));
  const s = Math.round(ms / 1000);
  if (s < 60) return s + " s";
  const m = Math.round(s / 60);
  if (m < 60) return m + " min";
  const h = Math.floor(m / 60);
  return h + " h" + (m % 60 ? " " + (m % 60) + " min" : "");
}

/**
 * Normalizuje odpowiedź admin.health: punkty posortowane po czasie, liczby albo null, NPC per plugin spłaszczone
 * do pól `npc_<id>` (wykresy małych wielokrotności), domyślne progi i lista pluginów.
 */
export function normalizeHealth(data) {
  const d = data && typeof data === "object" ? data : {};
  const plugins = Array.isArray(d.plugins) && d.plugins.length ? d.plugins.filter((p) => p && typeof p.id === "string").map((p) => ({ id: p.id, name: String(p.name || p.id) })) : DEFAULT_PLUGINS;
  const points = (Array.isArray(d.points) ? d.points : [])
    .filter((p) => p && num(Number(p.t)) !== null)
    .map((p) => {
      const out = {
        t: Number(p.t),
        tps: num(p.tps),
        tpsMin: num(p.tpsMin) ?? num(p.tps),
        mspt: num(p.mspt),
        msptMax: num(p.msptMax) ?? num(p.mspt),
        players: int(p.players),
        chunks: int(p.chunks),
        entities: int(p.entities),
        npcTotal: d.npcScan === false ? null : int(p.npcTotal),
        helpers: int(p.helpers),
        warn: Array.isArray(p.warn) ? p.warn.map(String) : [],
      };
      const npc = p.npc && typeof p.npc === "object" ? p.npc : {};
      for (const pl of plugins) out["npc_" + pl.id] = d.npcScan === false ? null : int(npc[pl.id]);
      return out;
    })
    .sort((a, b) => a.t - b.t);
  const th = { ...DEFAULT_THRESHOLDS, ...(d.thresholds && typeof d.thresholds === "object" ? d.thresholds : {}) };
  if (!th.npcPlugin || typeof th.npcPlugin !== "object") th.npcPlugin = {};
  const range = rangeOf(d.range);
  return {
    enabled: d.enabled !== false,
    npcScan: d.npcScan !== false,
    intervalMs: num(d.intervalMs) ?? 10000,
    range,
    resolutionMs: num(d.resolutionMs),
    from: num(d.from),
    to: num(d.to),
    points,
    current: d.current && typeof d.current === "object" ? d.current : null,
    plugins,
    thresholds: th,
    warnings: (Array.isArray(d.warnings) ? d.warnings : []).filter((w) => w && w.code),
    episodes: (Array.isArray(d.episodes) ? d.episodes : []).filter((e) => e && e.code),
  };
}

/** Najgorszy poziom aktywnych ostrzeżeń: "ok" | "warn" | "crit". */
export function overallLevel(warnings) {
  if (!Array.isArray(warnings) || !warnings.length) return "ok";
  return warnings.some((w) => w.level === "CRITICAL") ? "crit" : "warn";
}

/** A green status requires a current probe from an enabled, reachable monitor. */
export function healthAvailability(health, stale = false) {
  if (!health.enabled) return "Pomiary wyłączone — brak oceny aktualnego stanu.";
  if (stale) return "Dane archiwalne — aktualny stan serwera jest nieznany.";
  if (!health.current) return "Oczekiwanie na pierwszy pomiar.";
  return null;
}

/** Tekst do tooltipa: kody ostrzeżeń punktu → „TPS, Liczba NPC”. */
export function warnCodesText(codes, plugins = DEFAULT_PLUGINS) {
  return (Array.isArray(codes) ? codes : []).map((c) => warnLabel(c, plugins)).join(", ");
}

/** Pluginy, które mają choć jedno NPC w zakresie (pozostałe pokazujemy zbiorczo jako „brak NPC”). */
export function activePlugins(points, plugins, current) {
  return plugins.filter((p) => points.some((x) => (x["npc_" + p.id] || 0) > 0) || ((current && current.npc && current.npc[p.id]) || 0) > 0);
}
