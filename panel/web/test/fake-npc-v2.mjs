// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Fałszywy plugin — rozszerzenie v2 „pełne zarządzanie państwem NPC” (PANEL.md, „Kształty odpowiedzi v2”):
// historia 7 dni (co godzinę), stosunki z opinią i nastawieniem, rozbicie gospodarki 24 h, budżet AI,
// budynki/kolejka/typy z wymaganiami, armia z postawą i posterunkami, dziennik AI z kategoriami.

import { SIZES } from "./fake-plan.mjs";
const H = 3600 * 1000;
const DAY = 24 * H;

/** Deterministyczny generator (mulberry32) — te same dane przy każdym uruchomieniu testu. */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Historia: 7 dni co godzinę; ostatnia próbka = stan bieżący (podmieniana przy odczycie). */
function makeHistory(now, seed, spec) {
  const r = rng(seed);
  const end = Math.floor(now / H) * H;
  const n = spec.hours ?? 7 * 24;
  const pts = [];
  const ph1 = r() * 6.28;
  const ph2 = r() * 6.28;
  for (let i = n; i >= 1; i--) {
    const t = end - i * H;
    const k = (n - i) / n; // 0 → 1
    const p = { t };
    for (const [key, s] of Object.entries(spec.series)) {
      if (s.missingHours && n - i < s.missingHours) {
        p[key] = null;
        continue;
      }
      const base = s.from + (s.to - s.from) * Math.pow(k, s.curve || 1);
      const wave = s.daily ? Math.sin((t / DAY) * Math.PI * 2) * s.daily : 0;
      // szum o niskiej częstotliwości (fale z losową fazą) + odrobina białego — bez „piły” przy liczbach całkowitych
      const nz = s.noise || 0;
      const noise = nz * (0.45 * Math.sin(k * 23 + ph1) + 0.25 * Math.sin(k * 57 + ph2)) + (s.int ? 0 : (r() - 0.5) * nz * 0.6);
      const v = base + wave + noise;
      p[key] = s.int ? Math.max(0, Math.round(s.step ? Math.round(v / s.step) * s.step : v)) : v;
    }
    pts.push(p);
  }
  return pts;
}

const BUILDING_TYPES = [
  { type: "chata", name: "Chata", function: "HOUSING", description: "Skromne mieszkanie dla 2 osadników", cost: 300, beds: 2, minTier: 1, max: null },
  { type: "dom", name: "Dom", function: "HOUSING", description: "Murowany dom dla 4 osadników", cost: 800, beds: 4, minTier: 1, max: null },
  { type: "studnia", name: "Studnia", function: "WELL", description: "Woda dla osady, szybszy wzrost", cost: 150, beds: 0, minTier: 1, max: 4 },
  { type: "spichlerz", name: "Spichlerz", function: "GRANARY", description: "+500 pojemności jedzenia", cost: 1000, beds: 0, minTier: 1, max: 3 },
  { type: "koszary", name: "Koszary", function: "BARRACKS", description: "Rekrutacja i +10 limitu wojska", cost: 2000, beds: 0, minTier: 1, max: 3 },
  { type: "kuznia", name: "Kuźnia", function: "SMITHY", description: "Lepszy sprzęt, tańszy rycerz", cost: 1200, beds: 0, minTier: 1, max: 1, needs: ["studnia"] },
  { type: "wieza", name: "Wieża strażnicza", function: "TOWER", description: "Obrona przed oblężeniem", cost: 1800, beds: 0, minTier: 1, max: null },
  { type: "targ", name: "Targ", function: "MARKET", description: "Handel: +15% dochodu AI", cost: 2500, beds: 0, minTier: 2, max: 1, needs: ["kuznia"] },
  { type: "kosciol", name: "Kościół", function: "CHURCH", description: "Morale i wzrost ludności", cost: 5000, beds: 0, minTier: 2, max: 1, needs: ["targ"], minPop: 30 },
  { type: "palac", name: "Pałac", function: "PALACE", description: "Siedziba władcy, prestiż", cost: 15000, beds: 6, minTier: 3, max: 1, needs: ["kosciol"] },
];
const TIERS = { 1: "Osada", 2: "Gród", 3: "Miasto", 4: "Stolica" };
const CATS = ["WAR", "DIPLOMACY", "BUILD", "ARMY", "ECONOMY", "OTHER"];

/** Dodatkowy stan v2 dla państw NPC (wywoływane z createWorld). */
export function addNpcV2(W, now) {
  const m = W.C["npc-mazowsze"];
  m.v2 = {
    history: makeHistory(now, 7, {
      series: {
        treasury: { from: 52_000, to: 71_400, noise: 1600, curve: 1.2, int: true },
        population: { from: 15, to: 22, noise: 0.8, int: true },
        soldiers: { from: 14, to: 23, noise: 1.2, int: true, curve: 0.8 },
        chunks: { from: 44, to: 58, noise: 0.6, int: true },
        food: { from: 610, to: 900, noise: 30, daily: 45, int: true, missingHours: 30 },
      },
    }),
    tier: 2,
    goal: { kind: "WAR", text: "Prowadzi wojnę z Królestwem Polan i odkłada na pałac", target: { id: "c-polanie", name: "Królestwo Polan", color: "#B02E26" }, progress: 0.47 },
    opinions: { "c-polanie": -62, "c-wislanie": 15, "npc-rus": 40 },
    stances: { "c-polanie": "PREFERRED_TARGET", "c-wislanie": "AUTO", "npc-rus": "NEVER_WAR" },
    pending: { "c-wislanie": { relation: "ALLIANCE", direction: "IN", at: now - 3.5 * H } },
    tributes: { "npc-rus": { amount: 200, direction: "RECEIVES", until: null } },
    vassals: { "npc-rus": "VASSAL" },
    budget: { army: 45, buildings: 35, reserve: 20 },
    taxMultiplier: 100,
    posture: "BALANCED",
    siegeTarget: null,
    outposts: [
      { name: "Strażnica nad Narwią", world: "world", x: 1520, z: -410, soldiers: 4 },
      { name: "Gródek Czersk", world: "world", x: 1390, z: -180, soldiers: 3 },
      { name: "Posterunek graniczny", world: "world", x: 1610, z: -250, soldiers: 2 },
    ],
    queue: [
      { type: "dom", auto: true, x: null, z: null, rotation: 0, orderedBy: "AI", orderedAt: now - 2 * H },
      { type: "studnia", auto: false, x: 1480, z: -270, rotation: 90, orderedBy: "Maciej", orderedAt: now - 5 * H },
      { type: "koszary", auto: true, x: null, z: null, rotation: 0, orderedBy: "AI", orderedAt: now - 9 * H },
    ],
    income: { npcTax: 2200, aiIncome: 1450, tribute: 200 },
    expenses: { land: 290, buildings: 900, recruit: 500, war: 1000 },
    tlog: [
      { time: now - 0.2 * H, type: "war", amount: -1000, actor: "AI", note: "Wypowiedzenie wojny: Królestwo Polan" },
      { time: now - 1.4 * H, type: "recruit", amount: -500, actor: "AI", note: "2× piechota" },
      { time: now - 4 * H, type: "tax", amount: 2200, actor: "", note: "Podatki od ludności" },
      { time: now - 4 * H, type: "income", amount: 1450, actor: "AI", note: "Dochód AI (bogactwo 60)" },
      { time: now - 6 * H, type: "wages", amount: -457, actor: "", note: "Żołd 23 żołnierzy" },
      { time: now - 8 * H, type: "build", amount: -900, actor: "AI", note: "Spichlerz" },
      { time: now - 12 * H, type: "upkeep", amount: -290, actor: "", note: "Utrzymanie 58 chunków" },
    ],
  };
  m.population.sites = [{ type: "spichlerz", name: "Spichlerz", percent: 30, placed: 120, total: 400, world: "world", x: 1470, y: 68, z: -280, startedAt: now - 7 * H }, { type: "wieza", name: "Wieża strażnicza", percent: 82, placed: 410, total: 500, world: "world", x: 1435, y: 70, z: -330, startedAt: now - 20 * H }];
  m.population.buildings.push({ type: "studnia", name: "Studnia", function: "WELL", beds: 0, world: "world", x: 1460, y: 68, z: -310 });
  m.aiLog = [
    { time: now - 0.2 * H, category: "WAR", text: "Wypowiedziano wojnę: Królestwo Polan (przewaga 23 vs 11, agresja 70)" },
    { time: now - 0.6 * H, category: "WAR", text: "Rozpoczęto oblężenie chunku 12, -4 (Królestwo Polan)" },
    { time: now - 1.4 * H, category: "ARMY", text: "Zrekrutowano 2× piechota (koszt 5 zł)" },
    { time: now - 2.1 * H, category: "ECONOMY", text: "Zajęto chunk 92, -20 (koszt 2 zł 1 sr)" },
    { time: now - 3.5 * H, category: "DIPLOMACY", text: "Odrzucono propozycję sojuszu od: Wiślanie (dyplomacja 30)" },
    { time: now - 5 * H, category: "BUILD", text: "Planer: zamówiono Dom (brak 2 łóżek)" },
    { time: now - 7 * H, category: "BUILD", text: "Rozpoczęto budowę: Spichlerz (pojemność 1000 → 1500)" },
    { time: now - 9 * H, category: "ARMY", text: "Wysłano 3 żołnierzy na posterunek Gródek Czersk" },
    { time: now - 11 * H, category: "ECONOMY", text: "Podział budżetu: wojsko 45%, budowy 35%, rezerwa 20%" },
    { time: now - 14 * H, category: "DIPLOMACY", text: "Ruś Czerwona przyjęła zwierzchnictwo — trybut 2 zł dziennie" },
    { time: now - 20 * H, category: "BUILD", text: "Ukończono: Wieża strażnicza 80%, prace trwają" },
    { time: now - 26 * H, category: "WAR", text: "Odparto oblężenie chunku 88, -15 (Królestwo Polan)" },
    { time: now - 30 * H, category: "ARMY", text: "Morale spadło do 64% (zaległy żołd 1 dzień)" },
    { time: now - 40 * H, category: "DIPLOMACY", text: "Opinia o Wiślanach wzrosła do 15 (wspólny wróg)" },
    { time: now - 52 * H, category: "BUILD", text: "Ukończono: Koszary (limit wojska 30 → 40)" },
    { time: now - 70 * H, category: "WAR", text: "Cel ataku: Królestwo Polan (preferowany cel, słaba obrona)" },
    { time: now - 96 * H, category: "OTHER", text: "Nowy władca: Konrad I (skin: Notch)" },
  ];

  const rus = W.C["npc-rus"];
  rus.v2 = {
    history: makeHistory(now, 11, {
      hours: 60,
      series: {
        treasury: { from: 7_200, to: 9_800, noise: 400, int: true },
        population: { from: 6, to: 8, noise: 0.4, int: true },
        soldiers: { from: 6, to: 6, noise: 0.6, int: true },
        chunks: { from: 22, to: 24, noise: 0.3, int: true },
        food: { from: 360, to: 300, noise: 12, daily: 15, int: true },
      },
    }),
    tier: 1,
    goal: { kind: "IDLE", text: "AI wstrzymana — czeka na decyzję zarządcy", target: null, progress: null },
    opinions: { "c-polanie": -10, "c-wislanie": 55, "npc-mazowsze": -30 },
    stances: {},
    pending: {},
    tributes: { "npc-mazowsze": { amount: 200, direction: "PAYS", until: null } },
    vassals: { "npc-mazowsze": "SUZERAIN" },
    budget: { army: 20, buildings: 50, reserve: 30 },
    taxMultiplier: 120,
    posture: "DEFENSIVE",
    siegeTarget: null,
    outposts: [],
    queue: [],
    income: { npcTax: 600, aiIncome: 300 },
    expenses: { land: 120, tribute: 200 },
    tlog: [{ time: now - 10 * H, type: "tribute", amount: -200, actor: "AI", note: "Trybut dla: Księstwo Mazowsze" }],
  };
  rus.aiLog = rus.aiLog.map((e) => ({ ...e, category: e.category || "OTHER" }));
}

/** Stan v2 dla nowo utworzonego państwa NPC (pusta historia). */
export function emptyNpcV2() {
  return { history: [], tier: 1, goal: null, opinions: {}, stances: {}, pending: {}, tributes: {}, vassals: {}, budget: { army: 34, buildings: 33, reserve: 33 }, taxMultiplier: 100, posture: "BALANCED", siegeTarget: null, outposts: [], queue: [], income: {}, expenses: {}, tlog: [] };
}

const norm = (s) => String(s || "").toLowerCase().normalize("NFD").replace(/\p{M}/gu, "").replace(/ł/g, "l");

/**
 * Metody v2. h = pomocniki z makeHandlers: {canNpc, rel, relKey, soldiers, ref, err, okd}.
 */
export function npcV2Methods(W, hp) {
  const { canNpc, rel, relKey, soldiers, ref, err, okd } = hp;
  const npcOf = (u, id) => {
    const c = W.C[id];
    if (!c) throw new Error("Nie ma takiego kraju");
    if (!c.npc) throw new Error("To nie jest państwo NPC");
    if (!canNpc(u, id)) throw new Error("Brak uprawnień");
    if (!c.v2) c.v2 = emptyNpcV2();
    return c;
  };
  const other = (c, target) => {
    if (!W.C[target] || target === c.id) throw new Error("Nieprawidłowy kraj docelowy");
    return W.C[target];
  };
  const log = (c, category, text) => c.aiLog.unshift({ time: Date.now(), category, text });
  const typeOf = (t) => BUILDING_TYPES.find((x) => x.type === t);
  const count = (c, t) => c.population.buildings.filter((b) => b.type === t).length;
  const typeInfo = (c) =>
    BUILDING_TYPES.map((t) => {
      const missing = [];
      if ((c.v2.tier || 1) < t.minTier) missing.push("Poziom osady: " + TIERS[t.minTier]);
      for (const n of t.needs || []) if (!count(c, n)) missing.push(typeOf(n).name);
      if (t.minPop && c.population.population < t.minPop) missing.push(`${t.minPop} mieszkańców (jest ${c.population.population})`);
      if (t.max !== null && count(c, t.type) >= t.max) missing.push(`Limit ${t.max} na osadę`);
      if (c.treasury < t.cost) missing.push("Pieniądze w skarbcu");
      const sz = SIZES[t.type] || [9, 9, "other"];
      return { type: t.type, name: t.name, function: t.function, size: { w: sz[0], d: sz[1], source: "catalog" }, category: sz[2], description: t.description, cost: t.cost, costText: "", beds: t.beds, minTier: t.minTier, count: count(c, t.type), max: t.max, available: missing.length === 0, missing };
    });
  const queueOut = (c) => c.v2.queue.map((q, index) => ({ index, type: q.type, name: typeOf(q.type).name, cost: typeOf(q.type).cost, auto: q.auto, x: q.x, z: q.z, rotation: q.rotation, orderedBy: q.orderedBy, orderedAt: q.orderedAt }));
  const wages = (c) => W.UNITS.reduce((s, t) => s + (c.army[t.id] || 0) * t.upkeep, 0);
  const relationOf = (c, o) => c.v2.vassals[o] || rel(c.id, o);
  const intIn = (v, lo, hi) => Number.isInteger(v) && v >= lo && v <= hi;

  return {
    _typeInfo: typeInfo,
    "admin.npc.history": (u, p) => {
      const c = npcOf(u, p.id);
      const days = Math.max(1, Math.min(7, Math.trunc(Number(p.days) || 7)));
      const now = Date.now();
      const from = now - days * DAY;
      const pts = c.v2.history.filter((x) => x.t >= from);
      const points = c.v2.history.length ? [...pts, { t: now, treasury: c.treasury, population: c.population.population, soldiers: soldiers(c), chunks: c.chunks, food: c.population.food }] : [];
      return okd({
        id: c.id,
        days,
        intervalMs: H,
        from,
        to: now,
        points,
        current: { treasury: c.treasury, population: c.population.population, housing: c.population.housing, soldiers: soldiers(c), maxSoldiers: c.ai.settings["max-zolnierze"], chunks: c.chunks, maxChunks: c.ai.settings["max-chunki"], food: c.population.food, foodCapacity: c.population.foodCapacity, tier: c.v2.tier, tierName: TIERS[c.v2.tier], goal: c.v2.goal },
      });
    },
    "admin.npc.relations": (u, p) => {
      const c = npcOf(u, p.id);
      return okd({
        id: c.id,
        warCost: 1000,
        tributeMax: 100_000,
        countries: Object.values(W.C)
          .filter((o) => o.id !== c.id)
          .map((o) => ({ id: o.id, name: o.name, color: o.color, npc: o.npc, ruler: o.ruler, soldiers: soldiers(o), chunks: o.chunks, relation: relationOf(c, o.id), opinion: c.v2.opinions[o.id] ?? 0, stance: c.v2.stances[o.id] || "AUTO", pending: c.v2.pending[o.id] || null, tribute: c.v2.tributes[o.id] || null })),
      });
    },
    "admin.npc.setOpinion": (u, p) => {
      const c = npcOf(u, p.id);
      other(c, p.target);
      if (!intIn(p.value, -100, 100)) return err("Opinia musi być liczbą od -100 do 100");
      c.v2.opinions[p.target] = p.value;
      log(c, "DIPLOMACY", `Zarządca ustawił opinię o ${W.C[p.target].name}: ${p.value}`);
      return okd({ ok: true, opinion: p.value });
    },
    "admin.npc.setStance": (u, p) => {
      const c = npcOf(u, p.id);
      other(c, p.target);
      if (!["AUTO", "FRIENDLY", "HOSTILE", "NEVER_WAR", "PREFERRED_TARGET"].includes(p.stance)) return err("Nieznane nastawienie");
      c.v2.stances[p.target] = p.stance;
      return okd({ ok: true, stance: p.stance });
    },
    "admin.npc.propose": (u, p) => {
      const c = npcOf(u, p.id);
      const o = other(c, p.target);
      if (!["ALLIANCE", "NEUTRAL"].includes(p.relation)) return err("Nieznana relacja");
      if (o.npc) {
        const accepted = (o.v2 && (o.v2.opinions[c.id] ?? 0) >= 0) || p.relation === "NEUTRAL";
        if (accepted) W.relations[relKey(c.id, o.id)] = p.relation;
        log(c, "DIPLOMACY", `${p.relation === "ALLIANCE" ? "Propozycja sojuszu" : "Propozycja pokoju"} dla: ${o.name} — ${accepted ? "przyjęta" : "odrzucona"}`);
        return okd({ ok: true, message: `${o.name} ${accepted ? "przyjmuje" : "odrzuca"} propozycję`, accepted });
      }
      c.v2.pending[o.id] = { relation: p.relation, direction: "OUT", at: Date.now() };
      log(c, "DIPLOMACY", `Wysłano propozycję ${p.relation === "ALLIANCE" ? "sojuszu" : "pokoju"} do: ${o.name}`);
      return okd({ ok: true, message: `Wysłano propozycję do: ${o.name} (czeka na władcę)`, accepted: null });
    },
    "admin.npc.declareWar": (u, p) => {
      const c = npcOf(u, p.id);
      const o = other(c, p.target);
      if (c.v2.stances[o.id] === "NEVER_WAR") return err("Nastawienie „nigdy wojny” blokuje wojnę z tym krajem");
      if (rel(c.id, o.id) === "WAR") return err("Te kraje już są w stanie wojny");
      if (c.treasury < 1000) return err("Za mało pieniędzy w skarbcu (wojna kosztuje 10 zł)");
      c.treasury -= 1000;
      W.relations[relKey(c.id, o.id)] = "WAR";
      c.v2.tlog.unshift({ time: Date.now(), type: "war", amount: -1000, actor: u.name, note: "Wypowiedzenie wojny: " + o.name });
      log(c, "WAR", `Wypowiedziano wojnę: ${o.name} (rozkaz zarządcy ${u.name})`);
      return okd({ ok: true, message: `${c.name} wypowiada wojnę: ${o.name}`, cost: 1000 });
    },
    "admin.npc.demandTribute": (u, p) => {
      const c = npcOf(u, p.id);
      const o = other(c, p.target);
      if (!intIn(p.amount, 1, 100_000)) return err("Nieprawidłowa kwota trybutu");
      if (o.npc) {
        const accepted = soldiers(c) > soldiers(o) * 1.5;
        if (accepted) c.v2.tributes[o.id] = { amount: p.amount, direction: "RECEIVES", until: null };
        log(c, "DIPLOMACY", `Żądanie trybutu od ${o.name}: ${accepted ? "przyjęte" : "odrzucone"}`);
        return okd({ ok: true, message: accepted ? `${o.name} będzie płacić trybut` : `${o.name} odrzuca żądanie trybutu`, accepted });
      }
      log(c, "DIPLOMACY", `Zażądano trybutu od: ${o.name}`);
      return okd({ ok: true, message: `Wysłano żądanie trybutu do: ${o.name}`, accepted: null });
    },
    "admin.npc.economy": (u, p) => {
      const c = npcOf(u, p.id);
      const L = { npcTax: "Podatki od ludności", aiIncome: "Dochód AI", tribute: "Trybuty", other: "Inne", wages: "Żołd", land: "Utrzymanie ziemi", buildings: "Budowy", recruit: "Rekrutacja", war: "Wojny i oblężenia" };
      const inc = Object.entries(c.v2.income).map(([key, amount]) => ({ key, label: L[key], amount }));
      const exp = [["wages", wages(c)], ...Object.entries(c.v2.expenses)].filter(([, a]) => a > 0).map(([key, amount]) => ({ key, label: L[key], amount }));
      const it = inc.reduce((s, x) => s + x.amount, 0);
      const et = exp.reduce((s, x) => s + x.amount, 0);
      const wealth = c.ai.settings.bogactwo;
      return okd({ id: c.id, balance: c.treasury, from: Date.now() - DAY, to: Date.now(), income: inc, expenses: exp, incomeTotal: it, expensesTotal: et, net: it - et, budget: { ...c.v2.budget }, wealth, incomeMultiplier: Math.round((0.5 + wealth / 100) * 100) / 100, taxMultiplier: c.v2.taxMultiplier, log: c.v2.tlog });
    },
    "admin.npc.setBudget": (u, p) => {
      const c = npcOf(u, p.id);
      const v = [p.army, p.buildings, p.reserve];
      if (!v.every((x) => intIn(x, 0, 100)) || v[0] + v[1] + v[2] !== 100) return err("Podział budżetu musi sumować się do 100%");
      c.v2.budget = { army: p.army, buildings: p.buildings, reserve: p.reserve };
      log(c, "ECONOMY", `Podział budżetu: wojsko ${p.army}%, budowy ${p.buildings}%, rezerwa ${p.reserve}%`);
      return okd({ ok: true, budget: c.v2.budget });
    },
    "admin.npc.treasury": (u, p) => {
      const c = npcOf(u, p.id);
      if (!Number.isInteger(p.delta) || p.delta === 0) return err("Nieprawidłowa kwota");
      if (c.treasury + p.delta < 0) return err("Skarbiec nie może zejść poniżej zera");
      c.treasury += p.delta;
      c.v2.tlog.unshift({ time: Date.now(), type: "admin", amount: p.delta, actor: u.name, note: "Korekta z panelu" });
      return okd({ ok: true, balance: c.treasury });
    },
    "admin.npc.setTaxMultiplier": (u, p) => {
      const c = npcOf(u, p.id);
      if (!intIn(p.percent, 0, 300)) return err("Mnożnik podatku: 0–300%");
      c.v2.taxMultiplier = p.percent;
      return okd({ ok: true, taxMultiplier: p.percent });
    },
    "admin.npc.food": (u, p) => {
      const c = npcOf(u, p.id);
      if (!Number.isInteger(p.delta) || p.delta === 0 || Math.abs(p.delta) > 100_000) return err("Nieprawidłowa zmiana jedzenia");
      const pop = c.population;
      const before = pop.food;
      pop.food = Math.max(0, Math.min(pop.foodCapacity, pop.food + p.delta));
      return okd({ ok: true, food: pop.food, foodCapacity: pop.foodCapacity, applied: pop.food - before });
    },
    "admin.npc.settlers": (u, p) => {
      const c = npcOf(u, p.id);
      if (!Number.isInteger(p.delta) || p.delta === 0 || Math.abs(p.delta) > 500) return err("Nieprawidłowa liczba osadników");
      const pop = c.population;
      const before = pop.population;
      pop.population = Math.max(0, pop.population + p.delta);
      return okd({ ok: true, population: pop.population, housing: pop.housing, applied: pop.population - before });
    },
    "admin.npc.buildings": (u, p) => {
      const c = npcOf(u, p.id);
      const tier = c.v2.tier || 1;
      const next = TIERS[tier + 1] ? { tier: tier + 1, name: TIERS[tier + 1], missing: tier === 1 ? ["Studnia", "15 mieszkańców"] : ["Kościół", `30 mieszkańców (jest ${c.population.population})`] } : null;
      return okd({ id: c.id, tier, tierName: TIERS[tier], nextTier: next, buildings: c.population.buildings, sites: c.population.sites, queue: queueOut(c), types: typeInfo(c), develop: { suggested: Math.min(5000, c.treasury), max: Math.min(20_000, c.treasury) } });
    },
    "admin.npc.order": (u, p) => {
      const c = npcOf(u, p.id);
      const info = typeInfo(c).find((t) => t.type === p.type);
      if (!info) return err("Nieznany typ budynku");
      if (!info.available) return err("Nie można zamówić: brakuje — " + info.missing.join(", "));
      const hasXZ = p.x !== undefined && p.x !== null;
      if (hasXZ !== (p.z !== undefined && p.z !== null)) return err("Podaj obie współrzędne x i z albo żadnej");
      if (hasXZ && (!Number.isInteger(p.x) || !Number.isInteger(p.z))) return err("Współrzędne muszą być liczbami całkowitymi");
      const rot = p.rotation ?? 0;
      if (![0, 90, 180, 270].includes(rot)) return err("Obrót: 0, 90, 180 albo 270");
      c.v2.queue.push({ type: p.type, auto: !hasXZ, x: hasXZ ? p.x : null, z: hasXZ ? p.z : null, rotation: rot, orderedBy: u.name, orderedAt: Date.now() });
      log(c, "BUILD", `Zamówiono: ${info.name} (${hasXZ ? `x ${p.x}, z ${p.z}` : "miejsce wybierze planer"})`);
      const index = c.v2.queue.length - 1;
      return okd({ ok: true, message: `${info.name} dodano do kolejki (pozycja ${index + 1})`, queued: { index, type: p.type, name: info.name, auto: !hasXZ, x: hasXZ ? p.x : null, z: hasXZ ? p.z : null } });
    },
    "admin.npc.develop": (u, p) => {
      const c = npcOf(u, p.id);
      const max = Math.min(20_000, c.treasury);
      if (!Number.isInteger(p.budget) || p.budget < 1 || p.budget > max) return err("Budżet rozbudowy: od 1 md do " + max + " md");
      let left = p.budget;
      const ordered = [];
      for (const t of typeInfo(c).filter((x) => x.available).sort((a, b) => a.cost - b.cost)) {
        if (t.cost > left || ordered.length >= 3) continue;
        left -= t.cost;
        ordered.push({ type: t.type, name: t.name, cost: t.cost });
        c.v2.queue.push({ type: t.type, auto: true, x: null, z: null, rotation: 0, orderedBy: u.name, orderedAt: Date.now() });
      }
      if (!ordered.length) return err("Budżet za mały na jakikolwiek dostępny budynek");
      const spent = p.budget - left;
      c.treasury -= spent;
      c.v2.tlog.unshift({ time: Date.now(), type: "build", amount: -spent, actor: u.name, note: "Rozbudowa: " + ordered.map((o) => o.name).join(", ") });
      log(c, "BUILD", "Rozbudowa osady: " + ordered.map((o) => o.name).join(", "));
      return okd({ ok: true, message: `Planer zamówił: ${ordered.map((o) => o.name).join(", ")}`, ordered, spent });
    },
    "admin.npc.queue": (u, p) => {
      const c = npcOf(u, p.id);
      const q = c.v2.queue;
      const i = p.index;
      if (!Number.isInteger(i) || i < 0 || i >= q.length) return err("Nie ma takiej pozycji w kolejce");
      if (p.action === "cancel") q.splice(i, 1);
      else if (p.action === "up" || p.action === "down") {
        const j = p.action === "up" ? i - 1 : i + 1;
        if (j < 0 || j >= q.length) return err("Nie można przesunąć dalej");
        [q[i], q[j]] = [q[j], q[i]];
      } else return err("Nieznana akcja kolejki");
      return okd({ ok: true, queue: queueOut(c) });
    },
    "admin.npc.army": (u, p) => {
      const c = npcOf(u, p.id);
      const at = Object.keys(W.C).find((o) => o !== c.id && rel(c.id, o) === "WAR");
      return okd({
        id: c.id,
        total: soldiers(c),
        max: c.ai.settings["max-zolnierze"],
        morale: c.morale,
        wages: wages(c),
        types: W.UNITS.map((t) => ({ id: t.id, name: t.name, description: t.description, cost: t.cost, wage: t.upkeep, count: c.army[t.id] || 0 })),
        posture: c.v2.posture,
        siegeTarget: c.v2.siegeTarget ? ref(c.v2.siegeTarget) : null,
        autoTarget: at ? ref(at) : null,
        outposts: c.v2.outposts,
        sieges: W.sieges.filter((x) => x.attacker === c.id || x.defender === c.id).map((x) => ({ ...x, attacker: ref(x.attacker), defender: ref(x.defender) })),
      });
    },
    "admin.npc.setPosture": (u, p) => {
      const c = npcOf(u, p.id);
      if (!["DEFENSIVE", "BALANCED", "OFFENSIVE"].includes(p.posture)) return err("Nieznana postawa");
      c.v2.posture = p.posture;
      log(c, "ARMY", "Postawa armii: " + { DEFENSIVE: "obronna", BALANCED: "zrównoważona", OFFENSIVE: "ofensywna" }[p.posture]);
      return okd({ ok: true, posture: p.posture });
    },
    "admin.npc.setSiegeTarget": (u, p) => {
      const c = npcOf(u, p.id);
      if (p.target === null || p.target === undefined || p.target === "") c.v2.siegeTarget = null;
      else c.v2.siegeTarget = other(c, p.target).id;
      return okd({ ok: true, siegeTarget: c.v2.siegeTarget ? ref(c.v2.siegeTarget) : null });
    },
    "admin.npc.disband": (u, p) => {
      const c = npcOf(u, p.id);
      const have = c.army[p.type];
      if (have === undefined) return err("Nieznany typ jednostki");
      if (!intIn(p.count, 1, have)) return err("Można rozwiązać od 1 do " + have);
      c.army[p.type] -= p.count;
      log(c, "ARMY", `Rozwiązano ${p.count}× ${p.type} (rozkaz zarządcy)`);
      return okd({ ok: true, message: `Rozwiązano ${p.count} żołnierzy`, count: p.count });
    },
    "admin.npc.log": (u, p) => {
      const c = npcOf(u, p.id);
      const limit = Math.max(1, Math.min(500, Math.trunc(Number(p.limit) || 100)));
      const q = norm(String(p.query || "").slice(0, 64)).trim();
      const cat = p.category && p.category !== "ALL" ? String(p.category) : null;
      const byQuery = c.aiLog.filter((e) => !q || norm(e.text).includes(q));
      const counts = { ALL: byQuery.length };
      for (const k of CATS) counts[k] = byQuery.filter((e) => (e.category || "OTHER") === k).length;
      const matched = byQuery.filter((e) => !cat || (e.category || "OTHER") === cat);
      return okd({ id: c.id, total: matched.length, counts, entries: matched.slice(0, limit).map((e) => ({ time: e.time, category: e.category || "OTHER", text: e.text })) });
    },
  };
}
