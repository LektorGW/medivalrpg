// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — gospodarka kraju: czyste funkcje (bez DOM) — kto widzi stronę, odmowa, czasy, dni zapasu, poziomy ostrzeżeń,
// rodzaje towarów, normalizacja odpowiedzi country.economy (odporna na brakujące i nowe pola z nowszej Ludnosci).

/** Wersja odpowiedzi, którą ten widok zna (EconomyReport.VERSION w pluginie). */
export const KNOWN_VERSION = 1;
export const BASE = "#/gospodarka";
export const hrefOf = (id) => BASE + "/" + encodeURIComponent(id);

export const ACCESS_LABELS = {
  RULER: "Władca",
  ADVISOR: "Doradca — podgląd",
  ADMIN: "Administrator — każdy kraj",
  OWNER: "Zarządca Skarbu Świata — każdy kraj",
  NPC_MANAGER: "Zarządca państwa NPC",
};
export const LEVEL_WORDS = { error: "problem", warn: "ostrzeżenie", info: "informacja" };
export const STATE_CLASSES = { OK: "ok", WORKING: "ok", FULL: "err", TORCHES: "warn", NO_STORE: "warn", AUDIT: "err", BLOCKED: "warn", UNLOADED: "idle", OTHER: "idle", UNKNOWN: "idle" };
export const KINDS = ["wood", "stone", "ore", "food", "fish", "textile", "ceramics", "tools", "other"];
export const KIND_LABELS = { wood: "drewno", stone: "kamień", ore: "rudy i kopaliny", food: "jedzenie", fish: "ryby", textile: "tkaniny", ceramics: "szkło i cegła", tools: "narzędzia", other: "inne" };
export const SITE_STATE_LABELS = { WAITING: "czeka na materiał", BOUGHT: "kupuje na targu", WORKING: "pracują", OTHER: "stan" };

/**
 * Czy zalogowany użytkownik może otworzyć gospodarkę kraju (sterowanie interfejsem — serwer i tak sprawdza każde wywołanie).
 * me: wynik `me`; country: {id, npc}; rank: ranga użytkownika w tym kraju (RULER/ADVISOR/CITIZEN/null).
 */
export function canSeeEconomy(me, country, rank) {
  if (!me || !country) return false;
  const r = (me && me.roles) || {};
  if (rank === "RULER" || rank === "ADVISOR") return true;
  if (r.admin || me.admin || r.worldManager) return true;
  if (country.npc && (r.npcManager || (Array.isArray(r.managedNpcCountries) && r.managedNpcCountries.map(String).includes(String(country.id))))) return true;
  return false;
}

/** Czy użytkownik może wybierać spośród wielu krajów (admin, WorldSteward, zarządca NPC). */
export function seesManyCountries(me) {
  const r = (me && me.roles) || {};
  return !!(r.admin || me?.admin || r.worldManager || r.npcManager || (Array.isArray(r.managedNpcCountries) && r.managedNpcCountries.length));
}

/** Link w menu: lista krajów (admin, WorldSteward, zarządca NPC), własny kraj (władca/doradca) albo null = brak pozycji. */
export function navHref(me) {
  if (!me) return null;
  if (seesManyCountries(me)) return BASE;
  const c = me.country;
  const rank = c && String(c.rank || "").toUpperCase();
  return c && (rank === "RULER" || rank === "ADVISOR") ? hrefOf(c.id) : null;
}

/** Kraje, które użytkownik może wybrać na liście (bez ujawniania danych — tylko nazwy z `overview`). */
export function pickableCountries(me, overview) {
  const list = Array.isArray(overview && overview.countries) ? overview.countries : [];
  const r = (me && me.roles) || {};
  if (r.admin || me?.admin || r.worldManager) return list.slice();
  const managed = new Set((r.managedNpcCountries || []).map(String));
  return list.filter((c) => (me.country && me.country.id === c.id && ["RULER", "ADVISOR"].includes(String(me.country.rank).toUpperCase())) || (c.npc && (r.npcManager || managed.has(String(c.id)))));
}

/** Błąd RPC oznaczający odmowę (pokazujemy wtedy kartę „Brak dostępu” bez żadnych danych). */
export function isEconomyDenied(e) {
  const msg = String((e && e.message) || "");
  return /^Brak dostępu do gospodarki/i.test(msg) || !!(e && e.app === true && /brak uprawnień/i.test(msg));
}

/** „3 godz. 5 min”, „12 min”, „45 s”. */
export function fmtDuration(ms) {
  const v = Number(ms);
  if (!Number.isFinite(v) || v < 0) return "—";
  const s = Math.round(v / 1000);
  if (s < 60) return s + " s";
  const m = Math.round(s / 60);
  if (m < 60) return m + " min";
  const hrs = Math.floor(m / 60);
  const rest = m % 60;
  if (hrs < 48) return hrs + " godz." + (rest ? " " + rest + " min" : "");
  return Math.round(hrs / 24) + " dni";
}

/** Dni zapasu jedzenia jako tekst: „2,3 dnia”, „1 dzień”, „ponad 99 dni”. */
export function fmtDays(days) {
  const v = Number(days);
  if (days === null || days === undefined || !Number.isFinite(v)) return "—";
  if (v > 99) return "ponad 99 dni";
  const txt = (Math.round(v * 10) / 10).toLocaleString("pl-PL");
  if (v === 1) return "1 dzień";
  return txt + (Number.isInteger(Math.round(v * 10) / 10) ? " dni" : " dnia");
}

/** Poziom zapasu jedzenia: err (< 1 dzień), warn (< 3), ok. */
export function foodLevel(days) {
  const v = Number(days);
  if (days === null || days === undefined || !Number.isFinite(v)) return "idle";
  return v < 1 ? "err" : v < 3 ? "warn" : "ok";
}

export function fillLevel(fill, full) {
  if (full) return "err";
  const v = Number(fill) || 0;
  return v >= 0.9 ? "warn" : v >= 0.6 ? "mid" : "ok";
}

export const kindClass = (kind) => "eco-k-" + (KINDS.includes(kind) ? kind : "other");

export function plural(n, one, few, many) {
  const a = Math.abs(Number(n) || 0);
  if (a === 1) return one;
  const d = a % 10;
  const t = a % 100;
  return d >= 2 && d <= 4 && (t < 12 || t > 14) ? few : many;
}

/** Liczba ostrzeżeń wg poziomu. */
export function countLevels(warnings) {
  const out = { error: 0, warn: 0, info: 0 };
  for (const w of Array.isArray(warnings) ? warnings : []) if (out[w.level] !== undefined) out[w.level]++;
  return out;
}

/** Nazwa pola / klucza z nowej wersji Ludnosci jako zwykły tekst: „waitingSince” → „waiting since”. */
export function humanKey(k) {
  return String(k ?? "")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .toLowerCase();
}

/** Krótki opis dowolnej wartości „extra” (nowe pola Ludnosci) — bez HTML, ograniczony. */
export function describeValue(v, depth = 0) {
  if (v === null || v === undefined) return "—";
  if (typeof v === "number") return v.toLocaleString("pl-PL");
  if (typeof v === "boolean") return v ? "tak" : "nie";
  if (typeof v === "string") return v.length > 140 ? v.slice(0, 140) + "…" : v;
  if (depth >= 2) return "…";
  if (Array.isArray(v)) return v.slice(0, 8).map((x) => describeValue(x, depth + 1)).join(", ") + (v.length > 8 ? ` (+${v.length - 8})` : "");
  return Object.entries(v)
    .slice(0, 8)
    .map(([k, x]) => humanKey(k) + ": " + describeValue(x, depth + 1))
    .join("; ");
}

const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);
const arr = (v) => (Array.isArray(v) ? v : []);
const obj = (v) => (v && typeof v === "object" && !Array.isArray(v) ? v : {});

/** Normalizuje odpowiedź country.economy: każda część istnieje, typy są poprawne (stara/nowa wersja pluginu). */
export function normalizeEconomy(d) {
  const x = obj(d);
  const production = obj(x.production);
  const stores = obj(x.stores);
  const granary = obj(x.granary);
  const haulers = obj(x.haulers);
  return {
    version: num(x.version) ?? 0,
    newer: (num(x.version) ?? 0) > KNOWN_VERSION,
    id: x.id,
    name: x.name || "?",
    color: x.color,
    npc: !!x.npc,
    access: x.access || null,
    generatedAt: num(x.generatedAt),
    ageMs: num(x.ageMs) ?? 0,
    cached: !!x.cached,
    buildMs: num(x.buildMs),
    refreshSeconds: num(x.refreshSeconds) ?? 30,
    available: x.available !== false,
    settlement: x.settlement || null,
    sources: { flow: false, life: false, work: false, unknown: [], errors: {}, ...obj(x.sources) },
    summary: obj(x.summary),
    production: {
      workplaces: arr(production.workplaces),
      byFunction: arr(production.byFunction),
      daily: { source: "none", items: [], ...obj(production.daily) },
      today: production.today && typeof production.today === "object" ? production.today : null,
      workers: Array.isArray(production.workers) ? production.workers : null,
    },
    stores: { list: arr(stores.list), totals: arr(stores.totals), usedSlots: num(stores.usedSlots) ?? 0, slots: num(stores.slots) ?? 0, fill: num(stores.fill), fullCount: num(stores.fullCount) ?? 0 },
    granary: { growth: { possible: false, text: "brak danych" }, ...granary, growth: { possible: false, text: "brak danych", ...obj(granary.growth) } },
    haulers: { count: num(haulers.count) ?? arr(haulers.walking).length, walking: arr(haulers.walking), recent: arr(haulers.recent) },
    sites: arr(x.sites),
    materials: arr(x.materials),
    warnings: arr(x.warnings).filter((w) => w && typeof w === "object"),
    citizens: obj(x.citizens),
    history: { points: arr(obj(x.history).points), from: num(obj(x.history).from), to: num(obj(x.history).to) },
    settings: obj(x.settings),
    extra: x.extra && typeof x.extra === "object" ? x.extra : null,
  };
}

/** Opis źródła dziennej wydajności. */
export function dailyNote(daily) {
  const d = obj(daily);
  if (d.source === "ludnosc") return "Wydajność z ostatniej doby policzona przez Ludnosc.";
  if (d.source === "estimate") {
    return `Szacunek: przyrost towaru w skrzyniach z ${fmtDuration(d.coveredMs)} obserwacji przeliczony na dobę. To dolna granica — towar zużyty po drodze (budowy, posiłki) nie jest widoczny.`;
  }
  return d.since
    ? `Panel zbiera próbki od ${new Date(d.since).toLocaleTimeString("pl-PL", { hour: "2-digit", minute: "2-digit" })} — szacunek pojawi się po ok. 30 min obserwacji.`
    : "Brak danych o wydajności — panel zbiera próbki co kilka minut; szacunek pojawi się po ok. 30 min.";
}
