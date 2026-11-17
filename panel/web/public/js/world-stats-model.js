// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Statystyki Skarbu Świata: czyste funkcje bez DOM (testy: test/world-stats.test.mjs).
// Słowniki etykiet z zapasem, CSV (ochrona przed formułami, BOM, separator „;”), układ diagramu Sankey,
// serie dzienne (skumulowane kategorie) oraz filtry przepływów i ruchów monet zapisane w hashu (#/…?od=…&do=…).

export const DAY_MS = 24 * 3600 * 1000;

// ---------------------------------------------------------------- słowniki (zapas, gdy plugin nie poda etykiety)

export const TYPE_ORDER = ["reserve", "cash", "payments", "merchant", "households", "citizens", "players", "countries", "firms", "railBudgets", "escrow", "server", "other"];
export const TYPE_LABELS = {
  reserve: "Rezerwa Skarbu Świata",
  cash: "Pokrycie monet fizycznych",
  payments: "Budżet operacyjny",
  merchant: "Kupcy",
  households: "Obywatele NPC",
  citizens: "Oszczędności obywateli NPC",
  players: "Konta graczy",
  countries: "Skarbce krajów",
  firms: "Firmy",
  railBudgets: "Kolej",
  escrow: "Depozyty",
  server: "Konta serwera",
  other: "Inne",
};
/** Budżety świata: przepływ z nich do gospodarki to „wypłata ze świata”, w drugą stronę — „wpływ do świata”. */
export const WORLD_TYPES = ["reserve", "payments", "merchant", "households"];

export const CATEGORY_ORDER = ["tax", "npcIncome", "wages", "army", "construction", "land", "research", "trade", "firms", "merchant", "transfer", "treasury", "cash", "railway", "caravans", "quests", "tournaments", "diplomacy", "world", "labor", "citizens", "admin", "other"];
export const CATEGORY_LABELS = {
  tax: "Podatki",
  npcIncome: "Dochód państw NPC",
  wages: "Żołd",
  army: "Wojsko i rekrutacja",
  construction: "Budowy i naprawy",
  land: "Ziemia i granice",
  research: "Badania",
  trade: "Handel i sklepy",
  firms: "Firmy",
  merchant: "Kupiec",
  transfer: "Przelewy graczy",
  treasury: "Skarbce krajów — wpłaty/wypłaty",
  cash: "Monety: wpłaty i wypłaty",
  railway: "Kolej",
  caravans: "Szlaki i karawany",
  quests: "Zlecenia władców",
  tournaments: "Turnieje",
  diplomacy: "Trybuty i wojny",
  world: "Skarb Świata — przydziały",
  labor: "Robocizna obywateli NPC (udział z budów)",
  citizens: "Dochody i wydatki obywateli NPC",
  admin: "Korekty administracyjne",
  other: "Inne",
};
export const FLOW_TYPE_LABELS = { TRANSFER: "Przelew", DEPOSIT: "Emisja (sprzed zamknięcia puli)", WITHDRAW: "Umorzenie (sprzed zamknięcia puli)", SET: "Ustawienie salda (sprzed zamknięcia puli)" };
export const COIN_KIND_ORDER = ["PLAYER_ONLINE", "ENDER_ONLINE", "PLAYER_OFFLINE", "ENDER_OFFLINE", "CONTAINER", "GROUND", "ENTITY", "OTHER"];
export const COIN_KIND_LABELS = {
  PLAYER_ONLINE: "Ekwipunki graczy online",
  ENDER_ONLINE: "Ender chesty graczy online",
  PLAYER_OFFLINE: "Ekwipunki graczy offline",
  ENDER_OFFLINE: "Ender chesty graczy offline",
  CONTAINER: "Skrzynie i kontenery",
  GROUND: "Przedmioty na ziemi",
  ENTITY: "Byty: ramki, wózki, zwierzęta",
  OTHER: "Inne",
};
export const SOURCE_LABELS = { LIVE: "z pamięci serwera", DISK: "z pliku" };
export const TRAIL_KIND_ORDER = ["PICKUP", "DROP", "DEATH", "CONTAINER_IN", "CONTAINER_OUT", "HOPPER", "HOPPER_PICKUP", "DEPOSIT", "WITHDRAW", "EVENT"];
export const TRAIL_KIND_LABELS = {
  PICKUP: "Podniesienie z ziemi",
  DROP: "Upuszczenie",
  DEATH: "Wypadnięcie po śmierci",
  CONTAINER_IN: "Włożenie do pojemnika",
  CONTAINER_OUT: "Wyjęcie z pojemnika",
  HOPPER: "Lejek / przenoszenie automatyczne",
  HOPPER_PICKUP: "Lejek zebrał z ziemi",
  DEPOSIT: "Wpłata monet do banku",
  WITHDRAW: "Wypłata monet z banku",
  EVENT: "Zdarzenie Skarbca: handel, sklep, kupiec",
};
export const CENSUS_STATUS_LABELS = { NONE: "Brak spisu", RUNNING: "Spis trwa", DONE: "Spis zakończony", FAILED: "Spis nieudany" };
export const STOCK_LEVEL_LABELS = { NONE: "brak", LOW: "mało", OK: "w normie", HIGH: "dużo" };
export const TREND = { UP: { arrow: "↑", label: "rośnie" }, DOWN: { arrow: "↓", label: "spada" }, FLAT: { arrow: "→", label: "bez zmian" } };
export const LEVEL_WORDS = { OK: "zgodne", WARN: "ostrzeżenie", ERROR: "rozbieżność" };

/** Etykieta z podanego słownika serwera, potem z zapasowego, na końcu sam klucz. */
export function labelOf(key, serverLabels, fallback) {
  if (key === null || key === undefined || key === "") return "—";
  const s = serverLabels && typeof serverLabels[key] === "string" && serverLabels[key] ? serverLabels[key] : null;
  return s || (fallback && fallback[key]) || String(key);
}
export const typeLabel = (key, labels) => labelOf(key, labels, TYPE_LABELS);
export const categoryLabel = (key, labels) => labelOf(key, labels, CATEGORY_LABELS);
export const coinKindLabel = (key, label) => (label ? String(label) : labelOf(key, null, COIN_KIND_LABELS));
export const trailKindLabel = (key, label) => (label ? String(label) : labelOf(key, null, TRAIL_KIND_LABELS));
/** Bezpieczny token do nazw klas CSS (np. ws-type-players). */
export const cssToken = (key) => String(key ?? "other").replace(/[^A-Za-z0-9_-]/g, "") || "other";

/** Najgorszy poziom z listy kontroli: OK < WARN < ERROR. */
export function worstLevel(checks) {
  let w = "OK";
  for (const c of Array.isArray(checks) ? checks : []) {
    const l = String(c && c.level).toUpperCase();
    if (l === "ERROR") return "ERROR";
    if (l === "WARN") w = "WARN";
    else if (l !== "OK") w = w === "OK" ? "WARN" : w; // nieznany poziom traktujemy jak ostrzeżenie
  }
  return w;
}

// ---------------------------------------------------------------- liczby

const pctNf1 = new Intl.NumberFormat("pl-PL", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const pctNf2 = new Intl.NumberFormat("pl-PL", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
/** Udział 0..1 → „12,3%”; małe niezerowe → „<0,01%”. */
export function fmtShare(share) {
  const v = Number(share);
  if (!Number.isFinite(v)) return "—";
  if (v === 0) return "0%";
  const p = v * 100;
  if (Math.abs(p) < 0.01) return "<0,01%";
  return (Math.abs(p) < 1 ? pctNf2 : pctNf1).format(p) + "%";
}
export function shareOf(part, whole) {
  const a = Number(part), b = Number(whole);
  return Number.isFinite(a) && Number.isFinite(b) && b > 0 ? a / b : 0;
}
/** Kwota ze znakiem do porównań („+120 md” / „−120 md”) — zwraca prefiks znaku. */
export function signOf(n) {
  return n > 0 ? "+" : n < 0 ? "−" : "±";
}

// ---------------------------------------------------------------- CSV

/** Separator „;” — polski Excel (przecinek dziesiętny) otwiera taki plik poprawnie dwuklikiem. */
export const CSV_SEP = ";";
const FORMULA_START = /^[=+\-@\t\r]/;

/**
 * Jedna komórka CSV. Liczby zapisujemy bez zmian (także ujemne — to wartość, nie formuła).
 * Tekst zaczynający się od = + - @ tab albo CR dostaje prefiks ' (ochrona przed wstrzyknięciem formuł),
 * a komórki z separatorem, cudzysłowem albo nową linią są cytowane.
 */
export function csvCell(v, sep = CSV_SEP) {
  if (v === null || v === undefined) return "";
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "";
  if (typeof v === "boolean") return v ? "tak" : "nie";
  let s = String(v);
  if (FORMULA_START.test(s)) s = "'" + s;
  if (s.includes(sep) || /["\n\r]/.test(s)) s = '"' + s.replace(/"/g, '""') + '"';
  return s;
}

/** Cały plik: BOM UTF-8, nagłówek, wiersze, końce linii CRLF. */
export function toCsv(headers, rows, sep = CSV_SEP) {
  const lines = [headers.map((x) => csvCell(String(x), sep)).join(sep)];
  for (const r of rows) lines.push(r.map((x) => csvCell(x, sep)).join(sep));
  return "﻿" + lines.join("\r\n") + "\r\n";
}

const p2 = (n) => String(n).padStart(2, "0");
/** Data i czas lokalny w CSV: „2026-10-28 14:05:09” (Excel rozpoznaje). */
export function csvTime(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return "";
  const d = new Date(n);
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}`;
}
/** Nazwa pliku: skarb-<rodzaj>-RRRR-MM-DD-GGMM.csv (bez znaków spoza [a-z0-9-]). */
export function csvFileName(kind, now = Date.now()) {
  const d = new Date(now);
  const k = String(kind || "dane").toLowerCase().normalize("NFD").replace(/\p{M}/gu, "").replace(/ł/g, "l").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "dane";
  return `skarb-${k}-${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}-${p2(d.getHours())}${p2(d.getMinutes())}.csv`;
}

export const FLOW_CSV_HEADERS = ["Nr", "Czas", "Rodzaj", "Od (konto)", "Od", "Typ nadawcy", "Do (konto)", "Do", "Typ odbiorcy", "Kwota (md)", "Kategoria", "Plugin", "Powód", "Saldo nadawcy po (md)", "Saldo odbiorcy po (md)"];
export function flowCsvRow(r, labels = {}) {
  return [
    r.seq ?? null,
    csvTime(r.time),
    labelOf(r.type, null, FLOW_TYPE_LABELS),
    r.from ?? "",
    r.fromLabel || (r.from ? r.from : "(emisja)"),
    r.fromType ? typeLabel(r.fromType, labels.typeLabels) : "",
    r.to ?? "",
    r.toLabel || (r.to ? r.to : "(umorzenie)"),
    r.toType ? typeLabel(r.toType, labels.typeLabels) : "",
    Number(r.amount) || 0,
    r.categoryLabel || categoryLabel(r.category, labels.categoryLabels),
    r.plugin ?? "",
    r.reason ?? "",
    r.fromAfter ?? null,
    r.toAfter ?? null,
  ];
}
export const ACCOUNT_CSV_HEADERS = ["Konto", "Właściciel", "Typ", "Saldo (md)", "Udział w puli (%)", "Ostatnia aktywność", "Ruchy 7 dni", "Wpływy 7 dni (md)", "Wypływy 7 dni (md)"];
export function accountCsvRow(a, typeLabels) {
  return [a.account, a.owner ?? "", a.typeLabel || typeLabel(a.type, typeLabels), Number(a.balance) || 0, csvPercent(a.share), csvTime(a.lastActivity), a.count7d ?? 0, a.in7d ?? 0, a.out7d ?? 0];
}
/** Procent z przecinkiem dziesiętnym (tekst „12,3456”) — liczba w polskim Excelu. */
export function csvPercent(share) {
  const v = Number(share);
  if (!Number.isFinite(v)) return "";
  return (Math.round(v * 1e6) / 1e4).toString().replace(".", ",");
}
export const COIN_CSV_HEADERS = ["Rodzaj", "Opis", "Właściciel", "Świat", "X", "Y", "Z", "Kwota (md)", "Stosy", "Miedziaki (szt.)", "Srebrniki (szt.)", "Złote (szt.)", "Źródło"];
export function coinCsvRow(l) {
  return [coinKindLabel(l.kind, l.kindLabel), l.label ?? "", l.owner ?? "", l.world ?? "", l.x ?? null, l.y ?? null, l.z ?? null, Number(l.amount) || 0, l.stacks ?? 0, l.copper ?? 0, l.silver ?? 0, l.gold ?? 0, l.source ?? ""];
}
export const TRAIL_CSV_HEADERS = ["Czas", "Rodzaj", "Gracz", "Kwota (md)", "Stosy", "Świat", "X", "Y", "Z", "Cel", "Uwagi"];
export function trailCsvRow(r) {
  return [csvTime(r.time), trailKindLabel(r.kind, r.kindLabel), r.player ?? "", Number(r.amount) || 0, r.stacks ?? 0, r.world ?? "", r.x ?? null, r.y ?? null, r.z ?? null, r.target ?? "", r.note ?? ""];
}
export const MERCHANT_CSV_HEADERS = ["Towar", "Paczka", "Zapas", "Cel zapasu", "Poziom", "Cena skupu (md)", "Skup % bazowej", "Cena sprzedaży (md)", "Sprzedaż % bazowej", "Trend", "Trend %", "Polecany", "Skupione dziś", "Sprzedane dziś"];
export function merchantCsvRow(g) {
  return [g.key, g.bundle ?? null, g.stock ?? null, g.target ?? null, g.stockLabel || STOCK_LEVEL_LABELS[g.stockLevel] || "", g.buyPrice ?? null, g.buyPercent ?? null, g.sellPrice ?? null, g.sellPercent ?? null, (TREND[g.trend] || TREND.FLAT).label, g.trendPercent ?? 0, !!g.featured, g.boughtToday ?? 0, g.soldToday ?? 0];
}

/** Plan pobierania eksportu: kolejne strony po `pageSize` aż do `total` (najwyżej `max` wierszy). */
export function exportPages(total, pageSize = 1000, max = 50000) {
  const n = Math.max(0, Math.min(Number(total) || 0, max));
  const out = [];
  for (let off = 0; off < n; off += pageSize) out.push({ offset: off, limit: Math.min(pageSize, n - off) });
  return out;
}

// ---------------------------------------------------------------- Sankey

/**
 * Prosty układ dwukolumnowy: lewa kolumna = typy nadawców, prawa = typy odbiorców (z macierzy przepływów),
 * wysokości proporcjonalne do kwot, wstęgi uporządkowane tak, by się nie krzyżowały wewnątrz węzła.
 * Zwraca {left, right, links, total, scale}; węzeł: {key, value, y, h}; wstęga: {from, to, amount, count, sy, ty, w}.
 */
export function sankeyLayout(matrix, { height = 320, gap = 10, minLink = 1 } = {}) {
  const items = (Array.isArray(matrix) ? matrix : []).filter((m) => m && m.from && m.to && Number(m.amount) > 0).map((m) => ({ from: String(m.from), to: String(m.to), amount: Number(m.amount), count: Number(m.count) || 0 }));
  const sum = (key, side) => {
    const map = new Map();
    for (const m of items) map.set(m[side], (map.get(m[side]) || 0) + m.amount);
    return [...map.entries()].map(([k, value]) => ({ key: k, value })).sort((a, b) => b.value - a.value || order(a.key) - order(b.key));
  };
  const order = (k) => {
    const i = TYPE_ORDER.indexOf(k);
    return i < 0 ? 99 : i;
  };
  const left = sum("from", "from");
  const right = sum("to", "to");
  const total = items.reduce((s, m) => s + m.amount, 0);
  if (!total) return { left: [], right: [], links: [], total: 0, scale: 0 };
  const maxNodes = Math.max(left.length, right.length);
  const scale = Math.max(0, height - gap * (maxNodes - 1)) / total;
  const place = (nodes) => {
    const used = nodes.reduce((s, n) => s + n.value * scale, 0) + gap * (nodes.length - 1);
    let y = Math.max(0, (height - used) / 2);
    for (const n of nodes) {
      n.y = y;
      n.h = n.value * scale;
      y += n.h + gap;
    }
  };
  place(left);
  place(right);
  const li = new Map(left.map((n, i) => [n.key, i]));
  const ri = new Map(right.map((n, i) => [n.key, i]));
  const lOff = new Map(left.map((n) => [n.key, n.y]));
  const rOff = new Map(right.map((n) => [n.key, n.y]));
  const links = items.map((m) => ({ ...m, w: m.amount * scale }));
  // wyjścia węzła lewego w kolejności odbiorców; wejścia prawego w kolejności nadawców
  for (const l of links.slice().sort((a, b) => li.get(a.from) - li.get(b.from) || ri.get(a.to) - ri.get(b.to))) {
    l.sy = lOff.get(l.from);
    lOff.set(l.from, l.sy + l.w);
  }
  for (const l of links.slice().sort((a, b) => ri.get(a.to) - ri.get(b.to) || li.get(a.from) - li.get(b.from))) {
    l.ty = rOff.get(l.to);
    rOff.set(l.to, l.ty + l.w);
  }
  for (const l of links) l.drawW = Math.max(minLink, l.w);
  links.sort((a, b) => b.amount - a.amount);
  return { left, right, links, total, scale };
}

/** Ścieżka wstęgi (krzywe Béziera) między x0 a x1. */
export function sankeyPath(l, x0, x1) {
  const w = l.drawW ?? l.w;
  const mx = (x0 + x1) / 2;
  const f = (n) => Math.round(n * 10) / 10;
  return `M${f(x0)} ${f(l.sy)}C${f(mx)} ${f(l.sy)} ${f(mx)} ${f(l.ty)} ${f(x1)} ${f(l.ty)}L${f(x1)} ${f(l.ty + w)}C${f(mx)} ${f(l.ty + w)} ${f(mx)} ${f(l.sy + w)} ${f(x0)} ${f(l.sy + w)}Z`;
}

/** Macierz przepływów do tabeli: wiersze = nadawcy, kolumny = odbiorcy, komórki z kwotą i liczbą. */
export function flowMatrixTable(matrix) {
  const items = (Array.isArray(matrix) ? matrix : []).filter((m) => m && m.from && m.to);
  const keys = (side) => [...new Set(items.map((m) => m[side]))].sort((a, b) => TYPE_ORDER.indexOf(a) - TYPE_ORDER.indexOf(b));
  const rows = keys("from");
  const cols = keys("to");
  const cell = new Map(items.map((m) => [m.from + ">" + m.to, { amount: Number(m.amount) || 0, count: Number(m.count) || 0 }]));
  const max = items.reduce((s, m) => Math.max(s, Number(m.amount) || 0), 0);
  const rowTotals = rows.map((r) => cols.reduce((s, c) => s + (cell.get(r + ">" + c)?.amount || 0), 0));
  const colTotals = cols.map((c) => rows.reduce((s, r) => s + (cell.get(r + ">" + c)?.amount || 0), 0));
  return { rows, cols, get: (r, c) => cell.get(r + ">" + c) || null, max, rowTotals, colTotals, total: rowTotals.reduce((s, v) => s + v, 0) };
}
/** Stopień nasycenia komórki mapy ciepła 0..5 (0 = brak), skala pierwiastkowa — małe przepływy też widać. */
export function heatLevel(amount, max) {
  if (!(amount > 0) || !(max > 0)) return 0;
  return Math.max(1, Math.min(5, Math.ceil(Math.sqrt(amount / max) * 5)));
}

// ---------------------------------------------------------------- serie dzienne

const dayKeyOf = (t) => {
  const d = new Date(t);
  return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`;
};
export { dayKeyOf as dayKey };
export function localMidnight(t) {
  const d = new Date(t);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}
/** Kolejne lokalne północe z zakresu [from, to] (doba przy zmianie czasu ma 23 albo 25 h). */
export function daysBetween(from, to) {
  const out = [];
  const d = new Date(localMidnight(from));
  for (let i = 0; d.getTime() <= to && i < 400; i++) {
    out.push(d.getTime());
    d.setDate(d.getDate() + 1);
  }
  return out;
}

/**
 * Uzupełnia brakujące dni zerami i zwraca serię rosnąco: [{day, t, count, volume, in, out, byCategory}].
 * Dni rozpoznajemy po `day` (RRRR-MM-DD); gdy go brak — po `t`.
 */
export function fillDaily(daily, from, to) {
  const byDay = new Map();
  for (const d of Array.isArray(daily) ? daily : []) {
    if (!d) continue;
    const key = typeof d.day === "string" && /^\d{4}-\d{2}-\d{2}$/.test(d.day) ? d.day : Number.isFinite(Number(d.t)) ? dayKeyOf(Number(d.t)) : null;
    if (key) byDay.set(key, d);
  }
  const keys = Number.isFinite(from) && Number.isFinite(to) && to >= from ? daysBetween(from, to) : [];
  const seen = new Set();
  const out = keys.map((t) => {
    const key = dayKeyOf(t);
    seen.add(key);
    return normDay(byDay.get(key), key, t);
  });
  for (const [key, d] of byDay) if (!seen.has(key)) out.push(normDay(d, key, Number(d.t) || Date.parse(key + "T00:00:00")));
  return out.sort((a, b) => a.t - b.t);
}
function normDay(d, key, t) {
  d = d || {};
  return { day: key, t: Number(d.t) || t, count: Number(d.count) || 0, volume: Number(d.volume) || 0, in: Number(d.in) || 0, out: Number(d.out) || 0, byCategory: d.byCategory && typeof d.byCategory === "object" ? d.byCategory : {} };
}

/**
 * Kategorie do wykresu skumulowanego: najwyżej `max` największych (kolejność stała według CATEGORY_ORDER),
 * reszta łączona w „Pozostałe” (klucz `_rest`). Zwraca {keys, days:[{day,t,total,values:{key:n}}]}.
 */
export function stackCategories(days, categories, max = 7) {
  const vol = new Map();
  for (const c of Array.isArray(categories) ? categories : []) if (c && c.key) vol.set(c.key, Number(c.volume) || 0);
  if (!vol.size) for (const d of days) for (const [k, v] of Object.entries(d.byCategory || {})) vol.set(k, (vol.get(k) || 0) + (Number(v) || 0));
  const ranked = [...vol.entries()].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]);
  const top = ranked.slice(0, ranked.length > max ? max - 1 : max).map(([k]) => k);
  const rank = (k) => {
    const i = CATEGORY_ORDER.indexOf(k);
    return i < 0 ? 999 : i;
  };
  top.sort((a, b) => rank(a) - rank(b));
  const hasRest = ranked.length > top.length;
  const keys = hasRest ? [...top, "_rest"] : top;
  const set = new Set(top);
  const out = days.map((d) => {
    const values = {};
    for (const k of keys) values[k] = 0;
    let total = 0;
    for (const [k, v0] of Object.entries(d.byCategory || {})) {
      const v = Number(v0) || 0;
      if (!v) continue;
      total += v;
      if (set.has(k)) values[k] += v;
      else if (hasRest) values._rest += v;
    }
    return { day: d.day, t: d.t, total, values };
  });
  return { keys, days: out };
}

// ---------------------------------------------------------------- filtry w hashu

export const RANGE_PRESETS = [
  { id: "24h", label: "24 h", ms: DAY_MS },
  { id: "7d", label: "7 dni", ms: 7 * DAY_MS },
  { id: "30d", label: "30 dni", ms: 30 * DAY_MS },
  { id: "90d", label: "90 dni", ms: 90 * DAY_MS },
];
export const PAGE_SIZES = [50, 100, 200, 500];
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const KEY_RE = /^[A-Za-z][A-Za-z0-9_]{0,31}$/;

function qget(q, k) {
  if (!q) return null;
  if (typeof q.get === "function") return q.get(k);
  return q[k] ?? null;
}
function toParams(q) {
  if (q instanceof URLSearchParams) return q;
  if (typeof q === "string") return new URLSearchParams(q.replace(/^.*\?/, ""));
  return q || null;
}
/** „RRRR-MM-DD” → lokalna północ (ms) albo null. */
export function parseDay(s) {
  if (typeof s !== "string" || !DATE_RE.test(s)) return null;
  const [y, m, d] = s.split("-").map(Number);
  const dt = new Date(y, m - 1, d);
  return dt.getFullYear() === y && dt.getMonth() === m - 1 && dt.getDate() === d ? dt.getTime() : null;
}
export const formatDay = (ms) => dayKeyOf(ms);
function intOr(v, def, lo = 0, hi = Number.MAX_SAFE_INTEGER) {
  if (v === null || v === undefined || v === "") return def;
  const n = Number(v);
  return Number.isSafeInteger(n) && n >= lo && n <= hi ? n : def;
}
const clip = (s, n) => (typeof s === "string" ? s.trim().slice(0, n) : "");

/** Zakres: preset (24h/7d/30d/90d) albo własny (od/do jako daty lokalne). → {range, od, do, from, to} */
export function resolveRange(q, now = Date.now(), def = "7d") {
  const od = qget(q, "od");
  const doo = qget(q, "do");
  const a = parseDay(od);
  const b = parseDay(doo);
  if (a !== null || b !== null) {
    const from = a ?? localMidnight(now - 7 * DAY_MS);
    let to = b !== null ? new Date(b).setDate(new Date(b).getDate() + 1) - 1 : now;
    to = Math.min(to, now);
    if (from <= to) return { range: "wlasny", od: formatDay(from), do: formatDay(Math.max(from, to)), from, to };
  }
  const id = qget(q, "zakres");
  const p = RANGE_PRESETS.find((x) => x.id === id) || RANGE_PRESETS.find((x) => x.id === def);
  return { range: p.id, od: null, do: null, from: now - p.ms, to: now };
}

/** Filtry przepływów z query hasha (#/skarbswiata/przeplywy?zakres=30d&kat=wages&…). */
export function parseFlowFilters(query, now = Date.now()) {
  const q = toParams(query);
  const r = resolveRange(q, now);
  const kat = qget(q, "kat");
  const typ = qget(q, "typ");
  const size = intOr(qget(q, "ile"), 200);
  return {
    ...r,
    category: kat && KEY_RE.test(kat) ? kat : "",
    type: typ && KEY_RE.test(typ) ? typ : "",
    account: clip(qget(q, "konto"), 128),
    query: clip(qget(q, "q"), 64),
    minAmount: intOr(qget(q, "min"), 0),
    page: intOr(qget(q, "str"), 1, 1, 100000),
    pageSize: PAGE_SIZES.includes(size) ? size : 200,
  };
}
/** Odwrotność parseFlowFilters — pomija wartości domyślne; zwraca tekst bez „?”. */
export function serializeFlowFilters(f) {
  const p = new URLSearchParams();
  if (f.range === "wlasny" && f.od && f.do) {
    p.set("od", f.od);
    p.set("do", f.do);
  } else if (f.range && f.range !== "7d") p.set("zakres", f.range);
  if (f.category) p.set("kat", f.category);
  if (f.type) p.set("typ", f.type);
  if (f.account) p.set("konto", f.account);
  if (f.query) p.set("q", f.query);
  if (f.minAmount > 0) p.set("min", String(f.minAmount));
  if (f.page > 1) p.set("str", String(f.page));
  if (f.pageSize && f.pageSize !== 200) p.set("ile", String(f.pageSize));
  return p.toString();
}
/** Parametry RPC world.stats.flows dla filtrów (bez pustych pól). */
export function flowParams(f, { offset, limit } = {}) {
  const size = f.pageSize || 200;
  const p = { from: Math.round(f.from), to: Math.round(f.to), offset: offset ?? (Math.max(1, f.page || 1) - 1) * size, limit: limit ?? size };
  if (f.category) p.category = f.category;
  if (f.type) p.type = f.type;
  if (f.account) p.account = f.account;
  if (f.query) p.query = f.query;
  if (f.minAmount > 0) p.minAmount = f.minAmount;
  return p;
}

/** Filtry ruchów monet (#/skarbswiata/ruchy?gracz=Bolko&rodzaj=DROP&…). */
export function parseTrailFilters(query, now = Date.now()) {
  const q = toParams(query);
  const r = resolveRange(q, now);
  const kind = qget(q, "rodzaj");
  return { ...r, player: clip(qget(q, "gracz"), 36), kind: kind && TRAIL_KIND_ORDER.includes(kind) ? kind : "", query: clip(qget(q, "q"), 64), page: intOr(qget(q, "str"), 1, 1, 100000), pageSize: 200 };
}
export function serializeTrailFilters(f) {
  const p = new URLSearchParams();
  if (f.range === "wlasny" && f.od && f.do) {
    p.set("od", f.od);
    p.set("do", f.do);
  } else if (f.range && f.range !== "7d") p.set("zakres", f.range);
  if (f.player) p.set("gracz", f.player);
  if (f.kind) p.set("rodzaj", f.kind);
  if (f.query) p.set("q", f.query);
  if (f.page > 1) p.set("str", String(f.page));
  return p.toString();
}
export function trailParams(f, { offset, limit } = {}) {
  const size = f.pageSize || 200;
  const p = { from: Math.round(f.from), to: Math.round(f.to), offset: offset ?? (Math.max(1, f.page || 1) - 1) * size, limit: limit ?? size };
  if (f.player) p.player = f.player;
  if (f.kind) p.kind = f.kind;
  if (f.query) p.query = f.query;
  return p;
}

/** Strony do paginacji: {page, pages, from, to} (numeracja od 1). */
export function pageInfo(total, offset, limit) {
  const t = Math.max(0, Number(total) || 0);
  const l = Math.max(1, Number(limit) || 1);
  const o = Math.max(0, Number(offset) || 0);
  return { page: Math.floor(o / l) + 1, pages: Math.max(1, Math.ceil(t / l)), from: t ? o + 1 : 0, to: Math.min(t, o + l) };
}

// ---------------------------------------------------------------- konta

/** Filtr listy kont: typ + wyszukiwanie (konto, właściciel, etykieta typu) bez wielkości liter i polskich znaków. */
export function fold(s) {
  return String(s ?? "").toLowerCase().replace(/ł/g, "l").normalize("NFD").replace(/\p{M}/gu, "");
}
export function filterAccounts(accounts, { type = "", query = "" } = {}) {
  const q = fold(query.trim());
  return (Array.isArray(accounts) ? accounts : []).filter((a) => (!type || a.type === type) && (!q || fold(a.account).includes(q) || fold(a.owner).includes(q) || fold(a.ownerId).includes(q)));
}
/** Liczba kont według typu (w stałej kolejności, tylko niezerowe) + suma. */
export function typeCounts(accounts) {
  const m = new Map();
  for (const a of Array.isArray(accounts) ? accounts : []) m.set(a.type, (m.get(a.type) || 0) + 1);
  const idx = (k) => (TYPE_ORDER.includes(k) ? TYPE_ORDER.indexOf(k) : TYPE_ORDER.length);
  const keys = [...m.keys()].sort((a, b) => idx(a) - idx(b) || String(a).localeCompare(String(b)));
  return keys.map((k) => ({ key: k, count: m.get(k) }));
}

/** Czy błąd RPC oznacza odmowę dostępu do statystyk (pokazujemy wtedy kartę „Brak dostępu”). */
export function isDenied(e) {
  const msg = String((e && e.message) || "");
  return (e && (e.status === 403 || e.app === true) && /tylko\s+worldsteward|brak dostępu|brak uprawnień/i.test(msg)) || /^Tylko WorldSteward/i.test(msg);
}

// ---------------------------------------------------------------- towary kupca

export const GOOD_LABELS = {
  WHEAT: "Pszenica", BREAD: "Chleb", IRON_INGOT: "Sztabka żelaza", GOLD_INGOT: "Sztabka złota", COPPER_INGOT: "Sztabka miedzi",
  OAK_LOG: "Kłoda dębu", SPRUCE_LOG: "Kłoda świerku", BIRCH_LOG: "Kłoda brzozy", WOOL: "Wełna", WHITE_WOOL: "Biała wełna", COAL: "Węgiel",
  LEATHER: "Skóra", COBBLESTONE: "Bruk", STONE: "Kamień", CARROT: "Marchew", POTATO: "Ziemniak", BEETROOT: "Burak", APPLE: "Jabłko",
  COOKED_BEEF: "Pieczona wołowina", COOKED_PORKCHOP: "Pieczony schab", FISH: "Ryba", COD: "Dorsz", SALMON: "Łosoś", SUGAR_CANE: "Trzcina cukrowa",
  STRING: "Nić", FEATHER: "Pióro", FLINT: "Krzemień", CLAY_BALL: "Glina", BRICK: "Cegła", GLASS: "Szkło", SAND: "Piasek", GRAVEL: "Żwir",
  EMERALD: "Szmaragd", DIAMOND: "Diament", REDSTONE: "Czerwony pył", LAPIS_LAZULI: "Lazuryt", EGG: "Jajko", MILK_BUCKET: "Wiadro mleka", HONEY_BOTTLE: "Butelka miodu",
};
/** Nazwa towaru: słownik albo klucz Minecrafta zamieniony na zwykły tekst („IRON_NUGGET” → „Iron nugget”). */
export function goodLabel(key) {
  const k = String(key ?? "");
  if (GOOD_LABELS[k]) return GOOD_LABELS[k];
  const t = k.replace(/^minecraft:/i, "").replace(/_/g, " ").toLowerCase();
  return t ? t[0].toUpperCase() + t.slice(1) : "—";
}

// ---------------------------------------------------------------- gospodarstwa (pula world:households)

export const GRANT_STATE_LABELS = { NEW: "Nowe", PENDING: "W toku", DONE: "Wykonane", UNCERTAIN: "Niepewne" };
export const HOUSEHOLD_WINDOWS = [7, 30, 90];

/**
 * Prognoza puli gospodarstw z odpowiedzi world.stats.households.
 * → {kind: "days"|"self"|"nodata", days?, date?, text}
 * Bez średniej z 7 dni (avg7.net null) — „za mało danych”; forecastDays null i selfSustaining — pula samowystarczalna.
 */
export function householdsForecast(d) {
  const avg = d && d.avg7 ? d.avg7 : {};
  if (avg.net === null || avg.net === undefined || !Number.isFinite(Number(avg.net))) return { kind: "nodata", text: "Za mało danych do prognozy (potrzebne operacje z ostatnich 7 dni)." };
  const days = d.forecastDays;
  if ((days === null || days === undefined) && d.selfSustaining) return { kind: "self", text: "Pula samowystarczalna — wpływy pokrywają wypływy." };
  if (Number.isFinite(Number(days)) && days !== null) {
    const n = Math.max(0, Math.floor(Number(days)));
    const date = typeof d.forecastDate === "string" && parseDay(d.forecastDate) !== null ? d.forecastDate : null;
    const word = n === 1 ? "dzień" : "dni";
    return { kind: "days", days: n, date, text: `Wystarczy na ${n} ${word}` + (date ? ` — do ${date.split("-").reverse().join(".")}` : "") + "." };
  }
  return { kind: "nodata", text: "Za mało danych do prognozy." };
}
