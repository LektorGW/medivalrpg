// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — normalizacja danych z pluginu (tolerancyjna na warianty nazw pól) i formatowanie.

export const RANKS = { RULER: "Władca", ADVISOR: "Doradca", CITIZEN: "Obywatel" };
export const RANK_WEIGHT = { RULER: 3, ADVISOR: 2, CITIZEN: 1 };
export const RELATIONS = { ALLIANCE: "Sojusz", WAR: "Wojna", NEUTRAL: "Pokój" };

export function normRank(r) {
  if (!r) return null;
  const s = String(typeof r === "object" ? r.id || r.name || "" : r)
    .trim()
    .toUpperCase()
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/Ł/g, "L");
  if (s === "RULER" || s === "WLADCA" || s === "KROL") return "RULER";
  if (s === "ADVISOR" || s === "DORADCA") return "ADVISOR";
  if (s === "CITIZEN" || s === "OBYWATEL" || s === "MEMBER") return "CITIZEN";
  return null;
}

export function rankAtLeast(rank, need) {
  return (RANK_WEIGHT[rank] || 0) >= (RANK_WEIGHT[need] || 99);
}

export function normRelation(r) {
  const s = String(r || "")
    .trim()
    .toUpperCase();
  if (s === "ALLIANCE" || s === "SOJUSZ" || s === "ALLY") return "ALLIANCE";
  if (s === "WAR" || s === "WOJNA" || s === "ENEMY") return "WAR";
  return "NEUTRAL";
}

export function safeColor(c, fallback = "#8a7350") {
  if (typeof c === "number" && Number.isFinite(c)) return "#" + (c & 0xffffff).toString(16).padStart(6, "0");
  if (typeof c === "string") {
    const s = c.trim();
    if (/^#[0-9a-f]{6}$/i.test(s)) return s.toLowerCase();
    if (/^#[0-9a-f]{3}$/i.test(s)) return ("#" + s[1] + s[1] + s[2] + s[2] + s[3] + s[3]).toLowerCase();
    if (/^[0-9a-f]{6}$/i.test(s)) return "#" + s.toLowerCase();
  }
  return fallback;
}

const nf = new Intl.NumberFormat("pl-PL");
export function fmtInt(n) {
  const v = Number(n);
  return Number.isFinite(v) ? nf.format(v) : "—";
}

export function toDate(ts) {
  if (ts === null || ts === undefined || ts === "") return null;
  if (typeof ts === "number") return new Date(ts < 1e12 ? ts * 1000 : ts);
  const d = new Date(ts);
  return isNaN(d) ? null : d;
}

const df = new Intl.DateTimeFormat("pl-PL", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" });
const tf = new Intl.DateTimeFormat("pl-PL", { hour: "2-digit", minute: "2-digit" });
export function fmtDate(ts) {
  const d = toDate(ts);
  return d ? df.format(d) : "—";
}
export function fmtTime(ts) {
  const d = toDate(ts);
  return d ? tf.format(d) : "—";
}

export function fmtAgo(ts) {
  const d = toDate(ts);
  if (!d) return "—";
  const s = Math.round((Date.now() - d.getTime()) / 1000);
  if (s < 60) return "przed chwilą";
  if (s < 3600) return Math.floor(s / 60) + " min temu";
  if (s < 86400) return Math.floor(s / 3600) + " godz. temu";
  const days = Math.floor(s / 86400);
  return days === 1 ? "wczoraj" : days + " dni temu";
}

export function pick(o, ...keys) {
  if (!o || typeof o !== "object") return undefined;
  for (const k of keys) if (o[k] !== undefined && o[k] !== null) return o[k];
  return undefined;
}

export function arr(v) {
  return Array.isArray(v) ? v : [];
}

/** Ułamek 0..1 z progress (0..1 albo 0..100) albo placed/total. */
export function fraction(o) {
  const placed = pick(o, "placed", "done", "blocksPlaced");
  const total = pick(o, "total", "blocksTotal", "blocks");
  if (typeof placed === "number" && typeof total === "number" && total > 0) return Math.max(0, Math.min(1, placed / total));
  if (typeof o.percent === "number") return Math.max(0, Math.min(1, o.percent / 100));
  const p = pick(o, "progress");
  if (typeof p === "number") return Math.max(0, Math.min(1, p > 1 ? p / 100 : p));
  return 0;
}

export function pct(f) {
  return Math.round(f * 100) + "%";
}

/** Normalizuje kraj z country.get (różne możliwe nazwy pól). */
export function normCountry(c, overviewEntry) {
  c = c || {};
  const o = overviewEntry || {};
  const npcObj = c.npc && typeof c.npc === "object" ? c.npc : pick(c, "npcAi", "ai") || null;
  const isNpc = typeof c.npc === "boolean" ? c.npc : npcObj ? true : !!o.npc;
  const capital = pick(c, "capital") || (c.capitalWorld ? { world: c.capitalWorld, x: c.capitalX, z: c.capitalZ } : null);
  return {
    raw: c,
    id: c.id || o.id,
    name: c.name || o.name || "?",
    color: safeColor(pick(c, "color", "hexColor") ?? o.color),
    npc: isNpc,
    npcData: npcObj,
    ruler: pick(c, "ruler", "rulerName") ?? o.ruler,
    description: pick(c, "description", "desc") || "",
    tax: pick(c, "tax", "taxPerCitizen"),
    taxMax: pick(c, "taxMax", "maxTax"),
    capital,
    chunks: pick(c, "chunks", "chunkCount") ?? o.chunks,
    areaBlocks: pick(c, "areaBlocks") ?? o.areaBlocks,
    memberCount: pick(c, "memberCount") ?? (Array.isArray(c.members) ? c.members.length : o.members),
    myRank: normRank(pick(c, "myRank", "viewerRank", "rank")),
    members: Array.isArray(c.members) ? c.members.map(normMember) : null,
    treasury: c.treasury && typeof c.treasury === "object" ? c.treasury : null,
    population: c.population && typeof c.population === "object" ? c.population : null,
    populationCount: typeof c.population === "number" ? c.population : o.population,
    army: c.army && typeof c.army === "object" ? c.army : null,
    soldiers: pick(c, "soldiers") ?? o.soldiers,
    relations: c.relations,
    sieges: arr(c.sieges),
    created: pick(c, "created", "createdAt", "founded"),
  };
}

export function normMember(m) {
  if (typeof m === "string") return { name: m, rank: "CITIZEN", online: false, taxDebt: 0 };
  return {
    uuid: m.uuid,
    name: m.name || m.nick || "?",
    rank: normRank(m.rank) || "CITIZEN",
    online: !!m.online,
    taxDebt: pick(m, "taxDebt", "debt") || 0,
    debtDays: pick(m, "debtDays", "taxDebtDays"),
    lastSeen: pick(m, "lastSeen"),
  };
}

/** Mapa id → relacja dla kraju (z country.get.relations albo z overview allies/wars). */
export function relationMap(country, overview) {
  const map = new Map();
  const list = arr(overview && overview.countries);
  const byName = new Map(list.map((x) => [String(x.name).toLowerCase(), x.id]));
  const resolve = (v) => {
    if (v && typeof v === "object") return v.id || byName.get(String(v.name || "").toLowerCase());
    const s = String(v);
    if (list.some((x) => x.id === s)) return s;
    return byName.get(s.toLowerCase()) || s;
  };
  const rel = country && country.relations;
  if (Array.isArray(rel)) {
    for (const r of rel) {
      const id = resolve(r.id ? r.id : r.target || r.country || r.name);
      if (id) map.set(id, normRelation(r.relation || r.type || r.state));
    }
  } else if (rel && typeof rel === "object") {
    for (const [k, v] of Object.entries(rel)) {
      if (Array.isArray(v)) {
        const kind = normRelation(k === "allies" ? "ALLIANCE" : k === "wars" || k === "enemies" ? "WAR" : k);
        for (const x of v) map.set(resolve(x), kind);
      } else map.set(resolve(k), normRelation(v));
    }
  }
  const me = list.find((x) => x.id === (country && country.id));
  if (me && map.size === 0) {
    for (const a of arr(me.allies)) map.set(resolve(a), "ALLIANCE");
    for (const w of arr(me.wars)) map.set(resolve(w), "WAR");
  }
  return map;
}

export function roles(me) {
  const r = (me && me.roles) || {};
  const admin = !!(r.admin ?? (me && me.admin));
  const npcManager = !!r.npcManager;
  const managed = arr(r.managedNpcCountries).map(String);
  return {
    admin,
    npcManager,
    managed,
    canNpc: admin || npcManager || managed.length > 0,
    canNpcCreate: admin || npcManager,
    canNpcCountry: (id) => admin || npcManager || managed.includes(String(id)),
    label: admin ? "Admin" : npcManager || managed.length ? "Zarządca państw NPC" : null,
  };
}
