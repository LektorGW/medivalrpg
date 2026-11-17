// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Firmy graczy: czyste funkcje bez DOM (testy: test/firmy.test.mjs). Słowniki stanów, sortowania i kolorów
// sztandaru, normalizacja rankingu, trasy #/firmy, stronicowanie księgi, seria przychodu dziennego i rozpoznawanie
// metod etapu 2, których zainstalowane Firmy jeszcze nie znają.

export const STATE_LABELS = { ACTIVE: "czynna", DORMANT: "uśpiona", INSOLVENT: "niewypłacalna", LIQUIDATED: "zlikwidowana" };
export const stateLabel = (s) => STATE_LABELS[String(s || "").toUpperCase()] || (s ? String(s).toLowerCase() : "—");
/** Odcień znacznika stanu — istniejące klasy CSS `ws-level-ok|info|warn|err` (css/world-stats.css). */
export const stateTone = (s) => ({ ACTIVE: "ok", DORMANT: "info", INSOLVENT: "warn", LIQUIDATED: "err" })[String(s || "").toUpperCase()] || "info";

export const SORTS = [
  { id: "obrot", label: "Obrót" },
  { id: "majatek", label: "Majątek" },
  { id: "ludzie", label: "Ludzie" },
  { id: "kontrakty", label: "Kontrakty" },
];
export const normSort = (s) => (SORTS.some((x) => x.id === s) ? s : "obrot");

/** Kolory barwników Minecrafta (podstawa sztandaru) po polsku. */
export const BANNER_COLORS = {
  white: "biały", orange: "pomarańczowy", magenta: "karmazynowy", light_blue: "jasnoniebieski", yellow: "żółty", lime: "jasnozielony",
  pink: "różowy", gray: "szary", light_gray: "jasnoszary", cyan: "turkusowy", purple: "fioletowy", blue: "niebieski", brown: "brązowy",
  green: "zielony", red: "czerwony", black: "czarny",
};
export function bannerName(banner) {
  const base = String((banner && typeof banner === "object" ? banner.base : banner) || "white").toLowerCase().replace(/^minecraft:/, "").replace(/_banner$/, "");
  return BANNER_COLORS[base] || base.replace(/_/g, " ");
}

/** Kolor herbu: tylko #RRGGBB (albo liczba), inaczej kolor zapasowy — nic z serwera nie trafia do CSS jako tekst. */
export function herbColor(c, fallback = "#8a7350") {
  if (typeof c === "number" && Number.isFinite(c)) return "#" + (c & 0xffffff).toString(16).padStart(6, "0");
  const s = typeof c === "string" ? c.trim() : "";
  if (/^#[0-9a-f]{6}$/i.test(s)) return s.toLowerCase();
  if (/^[0-9a-f]{6}$/i.test(s)) return "#" + s.toLowerCase();
  return fallback;
}

const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
const arr = (v) => (Array.isArray(v) ? v : []);

/** Wiersze rankingu w kolejności z serwera (sortuje plugin); `owner` → kolumna salda. */
export function rankingRows(data) {
  return arr(data && data.firms).map((f, i) => ({
    id: String(f.id ?? ""),
    rank: Number.isFinite(Number(f.rank)) ? Number(f.rank) : i + 1,
    name: String(f.name ?? "—"),
    typeName: f.typeName || f.type || "—",
    seatName: f.seatName || "—",
    members: num(f.members),
    income30: num(f.income30),
    contracts: num(f.contracts),
    state: String(f.state || "ACTIVE").toUpperCase(),
    stateLabel: stateLabel(f.state || "ACTIVE"),
    mine: f.mine === true,
    color: herbColor(f.color),
    bannerName: bannerName(f.banner),
    balance: f.balance === undefined || f.balance === null ? null : num(f.balance),
    balanceText: f.balanceText || null,
  }));
}

/** #/firmy → lista, #/firmy/zarzad → zarząd właściciela, #/firmy/<id> → karta firmy. */
export function firmyRoute(param) {
  if (param === undefined || param === null || param === "") return { kind: "list" };
  if (param === "zarzad") return { kind: "admin" };
  return { kind: "firm", id: String(param) };
}
export const firmHref = (id) => "#/firmy/" + encodeURIComponent(String(id));

/** Metoda nieznana zainstalowanym Firmom albo Panelowi (etap 2 jeszcze niewdrożony) albo Firmy wyłączone. */
export function isStageMissing(e) {
  const msg = String((e && e.message) || e || "");
  return /nieznana metoda/i.test(msg) || /nie obsługują jeszcze panelu/i.test(msg) || /firmy są (obecnie )?niedostępne/i.test(msg);
}
/** Odmowa Firm (np. zarząd dla kogoś innego niż właściciel). */
export const isDenied = (e) => /brak dostępu|brak uprawnień|tylko worldsteward/i.test(String((e && e.message) || ""));

/** Strona księgi (od najnowszych, tak jak przysyła plugin). page liczona od 1. */
export function pageOf(rows, page, size = 20) {
  const list = arr(rows);
  const pages = Math.max(1, Math.ceil(list.length / size));
  const p = Math.min(Math.max(1, Math.floor(Number(page)) || 1), pages);
  return { rows: list.slice((p - 1) * size, p * size), page: p, pages, total: list.length, from: list.length ? (p - 1) * size + 1 : 0, to: Math.min(p * size, list.length) };
}

/** Klucz dnia RRRR-MM-DD w czasie lokalnym. */
export function dayKeyLocal(t) {
  const d = new Date(t);
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
}
/**
 * Seria przychodu dziennego do wykresu: ostatnie `days` dni kalendarzowych (dzisiaj włącznie), luki = 0.
 * incomeByDay: {"RRRR-MM-DD": md}. → [{t, day, values:{income}}]
 */
export function incomeSeries(incomeByDay, days = 30, now = Date.now()) {
  const src = incomeByDay && typeof incomeByDay === "object" ? incomeByDay : {};
  const out = [];
  const end = new Date(now);
  end.setHours(0, 0, 0, 0);
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(end.getFullYear(), end.getMonth(), end.getDate() - i);
    const day = dayKeyLocal(d.getTime());
    out.push({ t: d.getTime(), day, values: { income: Math.max(0, num(src[day])) } });
  }
  return out;
}

/** Udział w zyskach (procent, np. 25.5) → „25,5%”. */
export function fmtPercent(p) {
  if (p === null || p === undefined || p === "" || !Number.isFinite(Number(p))) return "—";
  return new Intl.NumberFormat("pl-PL", { maximumFractionDigits: 2 }).format(Number(p)) + "%";
}

export const SHOP_TYPES = { SELL: "sprzedaż", BUY: "skup" };
export const shopType = (t) => SHOP_TYPES[String(t || "").toUpperCase()] || String(t || "—").toLowerCase();

/** Rodzaje wpisów księgi firmy (plugin: wplata, pensja, dywidenda, podatek, sklep, oplata, likwidacja, ludzie, admin…). */
export const JOURNAL_KINDS = {
  wplata: "Wpłata", wyplata: "Wypłata", pensja: "Pensja", dywidenda: "Dywidenda", podatek: "Podatek", sklep: "Sklep", oplata: "Opłata",
  likwidacja: "Likwidacja", ludzie: "Ludzie", admin: "Właściciel", kontrakt: "Kontrakt", kredyt: "Kredyt", magazyn: "Magazyn",
};
export const journalKind = (k) => JOURNAL_KINDS[String(k || "").toLowerCase()] || (k ? String(k) : "—");

export const DISPUTE_WINNERS = [
  { id: "client", label: "Na korzyść zleceniodawcy" },
  { id: "contractor", label: "Na korzyść wykonawcy" },
  { id: "split", label: "Podział" },
];
export const NOTE_MAX = 200;
export const REASON_MAX = 200;

/** Grupy nicków ze wspólnym adresem IP (lista list) — puste grupy i pojedyncze nicki pomijane. */
export function addressGroups(shared) {
  return arr(shared).map((g) => arr(g).map(String).filter(Boolean)).filter((g) => g.length > 1);
}

/** Parametry przełącznika zamrożenia — wartości jako tekst, jak oczekują Firmy. */
export function freezeParams(id, frozen, reason) {
  const r = String(reason ?? "").trim().slice(0, REASON_MAX);
  const p = { id: String(id), frozen: frozen ? "true" : "false" };
  if (r) p.reason = r;
  return p;
}
