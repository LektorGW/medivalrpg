// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Atrapa metod world.stats.* (kontrakt: „Statystyki Skarbu Świata”) do testów E2E i integracyjnych.
// Deterministyczne dane: pula 100 000 000 md, ok. 40 kont, kilka tysięcy transakcji z 14 dni, spis monet
// z jedną celowo widoczną rozbieżnością (−120 md), ruchy stosów monet i rynek kupca NPC.
// Każda metoda sprawdza osobistego zarządcę (W.railwayManager) — admin i inni gracze dostają odmowę.

import { parseMoney } from "../public/js/money.js";

const DAY = 24 * 3600 * 1000;
const HOUR = 3600 * 1000;
export const DENIED = "Tylko WorldSteward ma dostęp do statystyk Skarbu Świata.";
export const CAP = 100_000_000;
export const MANAGE_DENIED = "Tylko WorldSteward zarządza Skarbem Świata.";

export const TYPE_LABELS = {
  reserve: "Rezerwa Skarbu Świata", cash: "Pokrycie monet fizycznych", payments: "Budżet operacyjny", merchant: "Kupcy",
  households: "Obywatele NPC", citizens: "Oszczędności obywateli NPC", players: "Konta graczy", countries: "Skarbce krajów", firms: "Firmy", railBudgets: "Kolej",
  escrow: "Depozyty i zabezpieczenia", server: "Konta serwera", other: "Inne",
};
const TYPE_ORDER = Object.keys(TYPE_LABELS);
const WORLD_TYPES = new Set(["reserve", "payments", "merchant", "households"]);
export const CATEGORY_LABELS = {
  tax: "Podatki", npcIncome: "Dochód państw NPC", wages: "Żołd", army: "Wojsko i rekrutacja", construction: "Budowy i naprawy",
  land: "Ziemia i granice", research: "Badania", trade: "Handel i sklepy", merchant: "Kupiec", transfer: "Przelewy graczy",
  treasury: "Skarbce krajów — wpłaty/wypłaty", cash: "Monety: wpłaty i wypłaty", railway: "Kolej", caravans: "Szlaki i karawany",
  quests: "Zlecenia władców", tournaments: "Turnieje", diplomacy: "Trybuty i wojny", world: "Skarb Świata — przydziały",
  labor: "Robocizna obywateli NPC (udział z budów)", citizens: "Dochody i wydatki obywateli NPC", admin: "Korekty administracyjne", other: "Inne",
};
const COIN_KINDS = {
  PLAYER_ONLINE: "Ekwipunki graczy online", ENDER_ONLINE: "Ender chesty graczy online", PLAYER_OFFLINE: "Ekwipunki graczy offline",
  ENDER_OFFLINE: "Ender chesty graczy offline", CONTAINER: "Skrzynie i kontenery", GROUND: "Przedmioty na ziemi",
  ENTITY: "Byty: ramki, wózki, zwierzęta", OTHER: "Inne",
};
const TRAIL_KINDS = {
  PICKUP: "Podniesienie z ziemi", DROP: "Upuszczenie", DEATH: "Wypadnięcie po śmierci", CONTAINER_IN: "Włożenie do pojemnika",
  CONTAINER_OUT: "Wyjęcie z pojemnika", HOPPER: "Lejek / przenoszenie automatyczne", HOPPER_PICKUP: "Lejek zebrał z ziemi",
  DEPOSIT: "Wpłata monet do banku", WITHDRAW: "Wypłata monet z banku", EVENT: "Zdarzenie Skarbca: handel, sklep, kupiec",
};

/** Deterministyczny generator (mulberry32) — te same dane przy każdym uruchomieniu. */
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

const pad = (n) => String(n).padStart(2, "0");
const dayKey = (t) => { const d = new Date(t); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
const midnight = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };

export function buildWorldStats(W, now = Date.now()) {
  const R = rng(20261028);
  const pick = (a) => a[Math.floor(R() * a.length)];
  const between = (lo, hi) => lo + Math.floor(R() * (hi - lo + 1));

  const acc = new Map();
  const addAcc = (account, type, owner, balance, ownerId = null) => acc.set(account, { account, type, owner, ownerId, balance });
  addAcc("world:cash", "cash", "Pokrycie monet", 31_085);
  addAcc("world:payments", "payments", "Budżet operacyjny", 1_200_000);
  addAcc("world:merchant", "merchant", "Kupcy", 2_000_000);
  addAcc("world:households", "households", "Obywatele NPC — podatki", 3_000_000);
  for (const u of Object.values(W.U)) addAcc("player:" + u.uuid, "players", u.name, u.balance, u.uuid);
  const extraPlayers = ["Mieszko", "Przemysł", "Jadwiga", "Kazimierz", "Wanda", "Bogumił", "Racibor", "Świętosława", "Dobromir", "Ludmiła", "Zbyszko", "Jaśko", "Radosław"];
  extraPlayers.forEach((n, i) => addAcc("player:fake-" + i, "players", n, between(500, 90_000), "fake-" + i));
  for (const c of Object.values(W.C)) addAcc("country:" + c.id, "countries", c.name, typeof c.treasury === "number" ? c.treasury : 20_000);
  [["firm:kuznia-gniezno", "Kuźnia Gnieźnieńska"], ["firm:mlyn-warta", "Młyn nad Wartą"], ["firm:browar", "Browar Książęcy"], ["firm:karczma", "Karczma Pod Złotym Kłosem"], ["firm:tkalnia", "Tkalnia Dobrawy"]]
    .forEach(([id, n]) => addAcc(id, "firms", n, between(5_000, 120_000)));
  for (const c of Object.values(W.C).filter((x) => x.npc)) addAcc("ludnosc:obywatele_" + c.id, "citizens", "Obywatele: " + c.name, between(15_000, 60_000));
  addAcc("kolej:przewoznik", "railBudgets", "Przewoźnik kolejowy", 12_345);
  addAcc("kolej:budowa", "railBudgets", "Roboty kolejowe (rezerwacja)", 250_000);
  addAcc("escrow:aukcja-17", "escrow", "Aukcja: miecz rodowy", 4_800);
  addAcc("escrow:karawana-3", "escrow", "Zabezpieczenie karawany", 15_000);
  addAcc("server:turnieje", "server", "Pula turniejowa", 60_000);
  addAcc("server:zlecenia", "server", "Nagrody zleceń", 35_000);
  addAcc("other:stare-konto", "other", "Stare konto sprzed reformy", 0);
  const others = [...acc.values()].reduce((s, a) => s + a.balance, 0);
  acc.set("world:reserve", { account: "world:reserve", type: "reserve", owner: "Rezerwa", ownerId: null, balance: CAP - others });

  const ofType = (t) => [...acc.values()].filter((a) => a.type === t).map((a) => a.account);
  const players = ofType("players"), countries = ofType("countries"), firms = ofType("firms"), citizens = ofType("citizens");
  const plugins = { tax: "Kraje", npcIncome: "PanstwaNPC", wages: "Armia", army: "Armia", construction: "Budowy", land: "Kraje", research: "Postep", trade: "Sklepy", merchant: "Kupiec", transfer: "Skarbiec", treasury: "Kraje", cash: "Skarbiec", railway: "Kolej", caravans: "Szlaki", quests: "Zlecenia", tournaments: "Turnieje", diplomacy: "Dyplomacja", world: "Skarbiec", labor: "Budowy", citizens: "Ludnosc", admin: "Panel", other: "Skarbiec" };
  // [kategoria, waga, nadawca(), odbiorca(), kwota(), powód()]
  const kinds = [
    ["tax", 14, () => pick(players), () => pick(countries), () => between(5, 60), () => "Podatek dzienny obywatela"],
    ["npcIncome", 6, () => "world:households", () => pick(countries.filter((c) => c.includes("npc-")).concat(countries)), () => between(80, 400), () => "Dochód z osad NPC"],
    ["wages", 10, () => pick(countries), () => "world:payments", () => between(40, 900), () => `Armia: żołd ${between(2, 20)} żołnierzy`],
    ["army", 3, () => pick(countries), () => "world:payments", () => between(250, 2400), () => "Rekrutacja: piechota"],
    ["construction", 5, () => pick(countries), () => pick(["world:payments", ...firms]), () => between(100, 3000), () => pick(["Budowa: dom", "Naprawa murów", "Budowa: spichlerz"])],
    ["land", 2, () => pick(countries), () => "world:payments", () => between(150, 600), () => "Zajęcie chunku"],
    ["research", 2, () => pick(countries), () => "world:payments", () => between(500, 5000), () => "Badanie: płodozmian"],
    ["trade", 12, () => pick(players), () => pick([...firms, ...players]), () => between(10, 1500), () => pick(["Sklep: chleb ×16", "Sklep: żelazo ×8", "Handel: miecz", "Zakup: wełna"])],
    ["merchant", 10, () => (R() < 0.5 ? "world:merchant" : pick(players)), () => null, () => between(20, 700), () => pick(["Kupiec skupił pszenicę", "Kupiec sprzedał chleb", "Kupiec skupił żelazo"])],
    ["transfer", 8, () => pick(players), () => pick(players), () => between(10, 5000), () => "/przelew"],
    ["treasury", 5, () => pick(players), () => pick(countries), () => between(100, 3000), () => "Wpłata do skarbca"],
    ["cash", 6, () => pick(players), () => "world:cash", () => between(10, 400), () => pick(["Wypłata monet z banku", "Wpłata monet do banku"])],
    ["railway", 3, () => pick(players), () => "kolej:przewoznik", () => between(10, 40), () => "Bilet: Królewska Kolej"],
    ["caravans", 2, () => pick(countries), () => "escrow:karawana-3", () => between(200, 1200), () => "Karawana do Krakowa"],
    ["quests", 2, () => pick(countries), () => pick(players), () => between(100, 2000), () => "Zlecenie: dostawa drewna"],
    ["tournaments", 1, () => "server:turnieje", () => pick(players), () => between(500, 3000), () => "Turniej rycerski — nagroda"],
    ["diplomacy", 1, () => pick(countries), () => pick(countries), () => between(500, 4000), () => "Trybut"],
    ["world", 1, () => "world:reserve", () => pick(["world:payments", "world:merchant", "kolej:przewoznik"]), () => between(10_000, 200_000), () => "Przydział z rezerwy"],
    ["admin", 1, () => "world:payments", () => pick(players), () => between(10, 500), () => "Korekta: zwrot za błąd"],
    ["labor", 4, () => "world:payments", () => "world:households", () => between(20, 300), () => "Robocizna: udział z budowy"],
    ["citizens", 5, () => (R() < 0.6 ? "world:households" : pick(citizens)), () => null, () => between(50, 900), () => pick(["Wypłata dla obywateli", "Zakupy obywateli"])],
  ];
  const totalW = kinds.reduce((s, k) => s + k[1], 0);
  const N = 3600;
  const start = now - 14 * DAY;
  const times = Array.from({ length: N }, () => start + Math.floor(R() * (14 * DAY - 60_000))).sort((a, b) => a - b);
  const plan = [];
  for (const time of times) {
    let r = R() * totalW, k = kinds[0];
    for (const x of kinds) { if ((r -= x[1]) < 0) { k = x; break; } }
    let from = k[2](), to = k[3]();
    if (k[0] === "merchant") to = from === "world:merchant" ? pick(players) : "world:merchant";
    if (k[0] === "citizens") to = from === "world:households" ? pick(citizens) : "world:households";
    if (k[0] === "cash" && R() < 0.5) [from, to] = [to, from];
    if (from === to) to = pick(players.filter((p) => p !== from));
    plan.push({ time, category: k[0], from, to, amount: k[4](), reason: k[5]() });
  }
  // kilka starych operacji sprzed zamkniętej puli (emisja/umorzenie bez konta po jednej stronie)
  plan.unshift({ time: start + 2 * HOUR, category: "admin", from: null, to: "other:stare-konto", amount: 500, reason: "Stara emisja (/eco give)", type: "DEPOSIT" });
  plan.splice(1, 0, { time: start + 3 * HOUR, category: "admin", from: "other:stare-konto", to: null, amount: 500, reason: "Stare umorzenie (/eco take)", type: "WITHDRAW" });

  // Salda po operacjach liczone wstecz od obecnych sald (operacja niemieszcząca się w saldzie jest pomniejszana).
  const bal = new Map([...acc.values()].map((a) => [a.account, a.balance]));
  const rows = [];
  for (let i = plan.length - 1; i >= 0; i--) {
    const p = plan[i];
    let amount = p.amount;
    if (p.to && bal.get(p.to) < amount) amount = Math.floor(bal.get(p.to) / 2);
    if (amount <= 0) continue;
    const row = { time: p.time, type: p.type || "TRANSFER", from: p.from, to: p.to, amount, category: p.category, reason: p.reason, plugin: plugins[p.category],
      fromAfter: p.from ? bal.get(p.from) : null, toAfter: p.to ? bal.get(p.to) : null };
    if (p.to) bal.set(p.to, bal.get(p.to) - amount);
    if (p.from) bal.set(p.from, bal.get(p.from) + amount);
    rows.push(row);
  }
  rows.reverse();
  rows.forEach((r, i) => (r.seq = 15000 + i));
  const label = (a) => (a ? acc.get(a)?.owner || a : null);
  for (const r of rows) {
    Object.assign(r, {
      fromLabel: r.from ? label(r.from) : "(emisja)", fromType: r.from ? acc.get(r.from).type : null,
      toLabel: r.to ? label(r.to) : "(umorzenie)", toType: r.to ? acc.get(r.to).type : null,
      categoryLabel: CATEGORY_LABELS[r.category],
    });
  }
  // znaczniki do testów bezpieczeństwa CSV i wyświetlania tekstu
  const lastTrade = [...rows].reverse().find((r) => r.category === "trade");
  if (lastTrade) lastTrade.reason = "=HYPERLINK(\"http://zly.example\") <b>sklep</b>";

  // ---------------------------------------------------------------- spis monet
  const loc = [];
  const cont = (x, y, z, label, amount, owner = null, source = "DISK") => loc.push({ kind: "CONTAINER", label, owner, ownerId: null, world: "world", x, y, z, amount, stacks: Math.max(1, Math.ceil(amount / 640)), source });
  cont(312, 64, -148, "Skrzynia (CHEST)", 12_000, "Bolko");
  cont(-820, 70, 640, "Beczka (BARREL)", 6_400, "Maciej", "LIVE");
  cont(1450, 66, -300, "Skrzynia (CHEST)", 2_100);
  cont(4842, 110, 1198, "Shulker (SHULKER_BOX)", 1_500, null, "LIVE");
  for (let i = 0; i < 26; i++) cont(between(-2000, 2000), between(40, 120), between(-2000, 2000), pick(["Skrzynia (CHEST)", "Beczka (BARREL)", "Lejek (HOPPER)"]), between(10, 260));
  for (const u of Object.values(W.U)) {
    loc.push({ kind: u.online ? "PLAYER_ONLINE" : "PLAYER_OFFLINE", label: "Ekwipunek: " + u.name, owner: u.name, ownerId: u.uuid, world: u.online ? "world" : null, x: u.online ? between(-500, 500) : null, y: u.online ? 64 : null, z: u.online ? between(-500, 500) : null, amount: between(50, 900), stacks: between(1, 4), source: u.online ? "LIVE" : "DISK" });
    loc.push({ kind: u.online ? "ENDER_ONLINE" : "ENDER_OFFLINE", label: "Ender chest: " + u.name, owner: u.name, ownerId: u.uuid, world: null, x: null, y: null, z: null, amount: between(0, 600), stacks: between(0, 3), source: u.online ? "LIVE" : "DISK" });
  }
  loc.push({ kind: "GROUND", label: "Monety na ziemi", owner: null, ownerId: null, world: "world", x: 270, y: 70, z: -100, amount: 35, stacks: 1, source: "LIVE" });
  loc.push({ kind: "ENTITY", label: "Ramka (ITEM_FRAME)", owner: null, ownerId: null, world: "world", x: 300, y: 68, z: -150, amount: 100, stacks: 1, source: "LIVE" });
  loc.push({ kind: "OTHER", label: "Kieszeń konia <i>Siwek</i>", owner: "Dobrawa", ownerId: W.U.dobrawa.uuid, world: "world", x: 310, y: 64, z: -140, amount: 20, stacks: 1, source: "LIVE" });
  for (const l of loc.filter((l) => !l.stacks)) l.amount = 0;
  const census = loc.filter((l) => l.amount > 0);
  // Celowa rozbieżność: spis = pokrycie − 120 md (ostatnia skrzynia uzupełnia sumę).
  const target = 31_085 - 120;
  const sum = census.reduce((s, l) => s + l.amount, 0);
  census[0].amount += target - sum;
  for (const l of census) {
    l.gold = Math.floor(l.amount / 100);
    l.silver = Math.floor((l.amount % 100) / 10);
    l.copper = l.amount % 10;
  }
  census.sort((a, b) => b.amount - a.amount);

  // ---------------------------------------------------------------- ruchy monet
  const trail = [];
  const tk = Object.keys(TRAIL_KINDS);
  const names = Object.values(W.U).map((u) => [u.name, u.uuid]);
  for (let i = 0; i < 340; i++) {
    const kind = pick(tk);
    const [player, playerId] = kind.startsWith("HOPPER") ? [null, null] : pick(names);
    trail.push({ time: now - Math.floor(R() * 7 * DAY), kind, kindLabel: TRAIL_KINDS[kind], player, playerId, amount: between(1, 640), stacks: between(1, 3), world: "world", x: between(-900, 900), y: between(50, 90), z: between(-900, 900),
      target: kind.startsWith("CONTAINER") || kind === "HOPPER" ? pick(["Skrzynia (CHEST)", "Beczka (BARREL)", "Lejek (HOPPER)"]) : kind === "EVENT" ? "Kupiec" : "", note: kind === "DEATH" ? "Śmierć: upadek" : "" });
  }
  trail.sort((a, b) => b.time - a.time);

  // ---------------------------------------------------------------- kupiec
  const goodsDef = [["WHEAT", 16, 30, 45, 120, 256], ["BREAD", 8, 50, 75, 40, 128], ["IRON_INGOT", 8, 120, 180, 300, 192], ["OAK_LOG", 16, 20, 32, 900, 512], ["WOOL", 16, 25, 40, 0, 128], ["COAL", 16, 18, 28, 260, 256], ["LEATHER", 8, 60, 90, 70, 96], ["COPPER_INGOT", 16, 15, 24, 800, 384]];
  const goods = goodsDef.map(([key, bundle, baseBuy, baseSell, stock, target], i) => {
    const level = stock === 0 ? "NONE" : stock < target * 0.6 ? "LOW" : stock > target * 1.5 ? "HIGH" : "OK";
    const f = level === "NONE" ? 1.3 : level === "LOW" ? 1.13 : level === "HIGH" ? 0.8 : 1;
    const buy = Math.round(baseBuy * f), sell = stock ? Math.round(baseSell * f) : 0;
    const trend = ["UP", "DOWN", "FLAT"][i % 3];
    return { key, bundle, stock, target, stockLevel: level, stockLabel: { NONE: "brak", LOW: "mało", OK: "w normie", HIGH: "dużo" }[level], buyPrice: buy, sellPrice: sell, baseBuy, baseSell,
      buyPercent: Math.round((buy / baseBuy) * 100), sellPercent: sell ? Math.round((sell / baseSell) * 100) : 0, trend, trendPercent: trend === "FLAT" ? 0 : trend === "UP" ? 6 : -4,
      featured: key === "IRON_INGOT", soldToday: between(0, 80), boughtToday: between(0, 60), restockPerDay: key === "BREAD" ? 32 : 0 };
  });
  const history = [];
  for (let i = 167; i >= 0; i--) {
    const t = now - i * HOUR;
    const g = {};
    for (const x of goods) g[x.key] = { stock: Math.max(0, x.stock + Math.round(Math.sin(i / 9 + x.baseBuy) * x.target * 0.2)), buy: Math.max(1, x.buyPrice + Math.round(Math.sin(i / 14) * 3)), sell: x.sellPrice ? x.sellPrice + Math.round(Math.cos(i / 14) * 3) : 0 };
    history.push({ t, budget: 42_000 - i * 40 + Math.round(Math.sin(i / 6) * 1500), goods: g });
  }
  const merchant = { enabled: true, budget: 42_000, budgetTarget: 50_000, budgetPercent: 84, buying: true, budgetFactor: 92, updatedAt: now - 5 * 60_000, periodEndsAt: now + 2 * HOUR, goods, history,
    totals: { stockValue: goods.reduce((s, g) => s + g.stock * g.baseBuy, 0), paidToday: 5_600, receivedToday: 2_300 } };

  const doneCensus = { status: "DONE", startedAt: now - 26 * HOUR, finishedAt: now - 26 * HOUR + 91_234, progress: 1, phase: "Gotowe", durationMs: 91_234 };
  // Zasilenia wdrożeniowe: jedno nowe, jedno o niepewnym stanie (do testu świadomego ponowienia).
  const grants = [
    { key: "obywatele", label: "Pula obywateli NPC", account: "world:households", amount: 312_600, state: "NEW" },
    { key: "kupcy", label: "Budżet kupców", account: "world:merchant", amount: 50_000, state: "UNCERTAIN" },
  ];
  return { now, accounts: acc, rows, census, trail, merchant, grants, censusState: doneCensus, retentionDays: 90, oldest: rows.length ? rows[0].time : now };
}

function num(v, def) {
  if (v === undefined || v === null || v === "") return def;
  const n = Number(v);
  return Number.isFinite(n) ? n : def;
}

export function worldStatsMethods(W, { err, okd }) {
  let S = null;
  const data = () => (S ||= buildWorldStats(W));
  const guard = (u) => u.uuid === W.railwayManager;
  const m = {};
  const typeLabels = () => ({ ...TYPE_LABELS });
  const censusNow = () => {
    const s = data();
    const c = s.censusState;
    if (c.status === "RUNNING") {
      const p = (Date.now() - c.startedAt) / c.runMs;
      if (p >= 1) Object.assign(c, { status: "DONE", progress: 1, phase: "Gotowe", finishedAt: Date.now(), durationMs: Date.now() - c.startedAt });
      else Object.assign(c, { progress: Math.round(p * 100) / 100, phase: p < 0.4 ? "Gracze online i offline" : p < 0.8 ? "Pliki regionów (porcjami)" : "Byty i ziemia" });
    }
    return c;
  };
  const coins = () => {
    const s = data();
    const c = censusNow();
    const cashAccount = s.accounts.get("world:cash").balance;
    if (c.status === "NONE") return { ...c, cashAccount, total: 0, difference: null, stacks: 0, byDenomination: { copper: 0, silver: 0, gold: 0 }, byKind: [], locations: [], truncated: false, filesScanned: 0, filesTotal: 0, chunksLive: 0, playersOnline: 0, playersOffline: 0, errors: [], notes: [] };
    const locs = c.status === "DONE" || c.status === "RUNNING" ? s.census : [];
    const total = locs.reduce((t, l) => t + l.amount, 0);
    const byKind = new Map();
    for (const l of locs) {
      const k = byKind.get(l.kind) || { kind: l.kind, label: COIN_KINDS[l.kind], amount: 0, stacks: 0, places: 0 };
      k.amount += l.amount; k.stacks += l.stacks; k.places++;
      byKind.set(l.kind, k);
    }
    const den = locs.reduce((d, l) => ({ copper: d.copper + l.copper, silver: d.silver + l.silver, gold: d.gold + l.gold }), { copper: 0, silver: 0, gold: 0 });
    const running = c.status === "RUNNING";
    return {
      status: c.status, startedAt: c.startedAt, finishedAt: running ? null : c.finishedAt, progress: c.progress, phase: c.phase, durationMs: running ? null : c.durationMs,
      cashAccount, total: running ? s.lastTotal ?? total : total, difference: (running ? s.lastTotal ?? total : total) - cashAccount, stacks: locs.reduce((t, l) => t + l.stacks, 0),
      byDenomination: den, byKind: [...byKind.values()].sort((a, b) => b.amount - a.amount), locations: locs.map((l) => ({ ...l })), truncated: false,
      filesScanned: running ? Math.round(962 * c.progress) : 962, filesTotal: 962, chunksLive: 2530, playersOnline: 4, playersOffline: 2,
      errors: [{ file: "world/region/r.-3.7.mca", error: "Uszkodzony nagłówek chunku 12,4 — pominięto" }],
      notes: ["Spis na gorąco: monety przenoszone w trakcie spisu mogą zostać policzone dwa razy albo wcale.", "Ender chesty graczy offline odczytano z plików playerdata."],
    };
  };

  m["world.stats.summary"] = (u) => {
    if (!guard(u)) return err(DENIED);
    const s = data();
    const all = [...s.accounts.values()];
    const total = all.reduce((t, a) => t + a.balance, 0);
    const categories = TYPE_ORDER.map((key) => {
      const list = all.filter((a) => a.type === key);
      const amount = list.reduce((t, a) => t + a.balance, 0);
      return { key, label: TYPE_LABELS[key], amount, accounts: list.length, share: amount / CAP };
    });
    const c = coins();
    const checks = [
      { key: "sum", level: total === CAP ? "OK" : "ERROR", text: `Suma wszystkich kont = ${total.toLocaleString("pl-PL")} md (pula ${CAP.toLocaleString("pl-PL")} md)`, expected: CAP, actual: total, difference: total - CAP },
      { key: "negative", level: "OK", text: "Brak kont z ujemnym saldem", expected: 0, actual: 0, difference: 0 },
      c.status === "NONE"
        ? { key: "census", level: "WARN", text: "Nie wykonano jeszcze spisu monet fizycznych", expected: c.cashAccount, actual: null, difference: null }
        : { key: "census", level: c.difference === 0 ? "OK" : "WARN", text: c.difference === 0 ? "Spis monet zgadza się z pokryciem" : `Spis monet różni się od pokrycia o ${Math.abs(c.difference)} md`, expected: c.cashAccount, actual: c.total, difference: c.difference },
    ];
    return okd({ generatedAt: Date.now(), cap: CAP, total, difference: total - CAP, closed: true, seq: s.rows.at(-1)?.seq ?? 0, categories, checks,
      census: { status: c.status, startedAt: c.startedAt ?? null, finishedAt: c.finishedAt ?? null, total: c.status === "NONE" ? null : c.total, cashAccount: c.cashAccount, difference: c.status === "NONE" ? null : c.difference, progress: c.progress ?? 0 },
      typeLabels: typeLabels() });
  };

  m["world.stats.accounts"] = (u) => {
    if (!guard(u)) return err(DENIED);
    const s = data();
    const to = Date.now(), from = to - 7 * DAY;
    const stats = new Map();
    for (const r of s.rows) {
      for (const [a, dir] of [[r.from, "out"], [r.to, "in"]]) {
        if (!a) continue;
        const st = stats.get(a) || { last: null, count: 0, in: 0, out: 0 };
        st.last = Math.max(st.last || 0, r.time);
        if (r.time >= from) { st.count++; st[dir] += r.amount; }
        stats.set(a, st);
      }
    }
    const accounts = [...s.accounts.values()].map((a) => {
      const st = stats.get(a.account) || { last: null, count: 0, in: 0, out: 0 };
      return { account: a.account, type: a.type, typeLabel: TYPE_LABELS[a.type], owner: a.owner, ownerId: a.ownerId, balance: a.balance, share: a.balance / CAP, lastActivity: st.last, count7d: st.count, in7d: st.in, out7d: st.out };
    }).sort((a, b) => b.balance - a.balance);
    return okd({ generatedAt: to, total: CAP, count: accounts.length, window: { from, to, days: 7 }, typeLabels: typeLabels(), accounts });
  };

  m["world.stats.flows"] = (u, p) => {
    if (!guard(u)) return err(DENIED);
    const s = data();
    const now = Date.now();
    let to = Math.min(now, num(p.to, now));
    let from = num(p.from, to - 7 * DAY);
    from = Math.max(from, now - s.retentionDays * DAY);
    if (from > to) return err("Nieprawidłowy zakres dat");
    const q = String(p.query ?? "").trim().toLowerCase();
    if (q.length > 64) return err("Za długie wyszukiwanie (najwyżej 64 znaki)");
    const minAmount = num(p.minAmount, 0);
    const offset = Math.max(0, Math.floor(num(p.offset, 0)));
    const limit = Math.floor(num(p.limit, 200));
    if (limit < 1 || limit > 1000) return err("limit 1..1000");
    const account = p.account ? String(p.account) : null;
    const match = s.rows.filter((r) => r.time >= from && r.time <= to
      && (!account || r.from === account || r.to === account)
      && (!p.category || r.category === p.category)
      && (!p.type || r.fromType === p.type || r.toType === p.type)
      && r.amount >= minAmount
      && (!q || [r.reason, r.from, r.to, r.fromLabel, r.toLabel, r.plugin, r.categoryLabel].some((x) => x && String(x).toLowerCase().includes(q))));
    const daily = new Map();
    for (let t = midnight(from); t <= to; t = midnight(t + 26 * HOUR)) daily.set(dayKey(t), { day: dayKey(t), t, count: 0, volume: 0, in: 0, out: 0, byCategory: {} });
    const cats = new Map(), matrix = new Map(), senders = new Map(), receivers = new Map(), plugins = new Map();
    let sumAmount = 0;
    for (const r of match) {
      sumAmount += r.amount;
      const d = daily.get(dayKey(r.time));
      if (d) {
        d.count++; d.volume += r.amount;
        d.byCategory[r.category] = (d.byCategory[r.category] || 0) + r.amount;
        if (account) { if (r.to === account) d.in += r.amount; if (r.from === account) d.out += r.amount; }
        else {
          const fw = WORLD_TYPES.has(r.fromType), tw = WORLD_TYPES.has(r.toType);
          if (fw && !tw) d.in += r.amount;
          if (!fw && tw) d.out += r.amount;
        }
      }
      const c = cats.get(r.category) || { key: r.category, label: CATEGORY_LABELS[r.category], count: 0, volume: 0 };
      c.count++; c.volume += r.amount; cats.set(r.category, c);
      if (r.fromType && r.toType) {
        const k = r.fromType + ">" + r.toType;
        const mx = matrix.get(k) || { from: r.fromType, to: r.toType, amount: 0, count: 0 };
        mx.amount += r.amount; mx.count++; matrix.set(k, mx);
      }
      for (const [map, a, l, t] of [[senders, r.from, r.fromLabel, r.fromType], [receivers, r.to, r.toLabel, r.toType]]) {
        if (!a) continue;
        const x = map.get(a) || { account: a, label: l, type: t, amount: 0, count: 0 };
        x.amount += r.amount; x.count++; map.set(a, x);
      }
      const pl = plugins.get(r.plugin) || { plugin: r.plugin, count: 0, volume: 0 };
      pl.count++; pl.volume += r.amount; plugins.set(r.plugin, pl);
    }
    const top = (map) => [...map.values()].sort((a, b) => b.amount - a.amount).slice(0, 20);
    const rows = match.slice().reverse().slice(offset, offset + limit).map((r) => ({ ...r }));
    return okd({ from, to, retentionDays: s.retentionDays, oldest: s.oldest, total: match.length, offset, limit, sumAmount, rows,
      daily: [...daily.values()], categories: [...cats.values()].sort((a, b) => b.volume - a.volume), matrix: [...matrix.values()].sort((a, b) => b.amount - a.amount),
      topSenders: top(senders), topReceivers: top(receivers), plugins: [...plugins.values()].sort((a, b) => b.volume - a.volume),
      categoryLabels: { ...CATEGORY_LABELS }, typeLabels: typeLabels() });
  };

  m["world.stats.account"] = (u, p) => {
    if (!guard(u)) return err(DENIED);
    const s = data();
    const a = s.accounts.get(String(p.account || ""));
    if (!a) return err("Nie ma takiego konta.");
    const days = Math.floor(num(p.days, 30));
    if (days < 1 || days > 90) return err("days 1..90");
    const to = Date.now(), from = to - days * DAY;
    const rows = s.rows.filter((r) => r.time >= from && (r.from === a.account || r.to === a.account));
    const inSum = rows.filter((r) => r.to === a.account).reduce((t, r) => t + r.amount, 0);
    const outSum = rows.filter((r) => r.from === a.account).reduce((t, r) => t + r.amount, 0);
    const after = (r) => (r.to === a.account ? r.toAfter : r.fromAfter);
    const startBal = rows.length ? after(rows[0]) - (rows[0].to === a.account ? rows[0].amount : -rows[0].amount) : a.balance;
    let pts = [{ t: from, balance: startBal }, ...rows.map((r) => ({ t: r.time, balance: after(r) })), { t: to, balance: a.balance }];
    if (pts.length > 500) { const step = pts.length / 498; pts = [pts[0], ...Array.from({ length: 498 }, (_, i) => pts[1 + Math.floor(i * step)]).filter(Boolean), pts.at(-1)]; }
    const cats = new Map(), cps = new Map();
    for (const r of rows) {
      const inc = r.to === a.account;
      const c = cats.get(r.category) || { key: r.category, label: CATEGORY_LABELS[r.category], in: 0, out: 0, count: 0 };
      c[inc ? "in" : "out"] += r.amount; c.count++; cats.set(r.category, c);
      const other = inc ? r.from : r.to;
      const cp = cps.get(other || "(brak)") || { account: other, label: inc ? r.fromLabel : r.toLabel, type: inc ? r.fromType : r.toType, in: 0, out: 0, count: 0 };
      cp[inc ? "in" : "out"] += r.amount; cp.count++; cps.set(other || "(brak)", cp);
    }
    return okd({ account: a.account, label: a.owner, type: a.type, typeLabel: TYPE_LABELS[a.type], owner: a.owner, balance: a.balance, days, from, to, in: inSum, out: outSum, count: rows.length,
      points: pts, categories: [...cats.values()].sort((x, y) => y.in + y.out - x.in - x.out), counterparties: [...cps.values()].sort((x, y) => y.in + y.out - x.in - x.out).slice(0, 50),
      rows: rows.slice(-200).reverse().map((r) => ({ ...r })) });
  };

  m["world.stats.coins"] = (u) => (guard(u) ? okd(coins()) : err(DENIED));

  m["world.stats.census"] = (u) => {
    if (!guard(u)) return err(DENIED);
    const s = data();
    const c = censusNow();
    if (c.status === "RUNNING") return okd({ ok: true, started: false, message: "Spis monet już trwa." });
    s.lastTotal = s.census.reduce((t, l) => t + l.amount, 0);
    s.censusState = { status: "RUNNING", startedAt: Date.now(), runMs: W.censusRunMs ?? 5000, progress: 0, phase: "Przygotowanie" };
    return okd({ ok: true, started: true, message: "Rozpoczęto spis monet — trwa porcjami w tle." });
  };

  m["world.stats.trail"] = (u, p) => {
    if (!guard(u)) return err(DENIED);
    const s = data();
    const now = Date.now();
    const to = Math.min(now, num(p.to, now)), from = num(p.from, to - 7 * DAY);
    const limit = Math.floor(num(p.limit, 200)), offset = Math.max(0, Math.floor(num(p.offset, 0)));
    if (limit < 1 || limit > 1000) return err("limit 1..1000");
    const player = String(p.player ?? "").trim().toLowerCase();
    const q = String(p.query ?? "").trim().toLowerCase();
    const match = s.trail.filter((r) => r.time >= from && r.time <= to && (!p.kind || r.kind === p.kind)
      && (!player || (r.player || "").toLowerCase() === player || (r.playerId || "").toLowerCase() === player)
      && (!q || [r.player, r.target, r.note, r.kindLabel, r.world].some((x) => x && x.toLowerCase().includes(q))));
    const byKind = new Map();
    for (const r of match) {
      const k = byKind.get(r.kind) || { kind: r.kind, label: r.kindLabel, count: 0, amount: 0 };
      k.count++; k.amount += r.amount; byKind.set(r.kind, k);
    }
    return okd({ from, to, total: match.length, offset, limit, since: now - 7 * DAY, dropped: 0, rows: match.slice(offset, offset + limit).map((r) => ({ ...r })), byKind: [...byKind.values()].sort((a, b) => b.amount - a.amount) });
  };

  // ---------------------------------------------------------------- przelewy rezerwa ↔ skarbce państw NPC
  // Państwo NPC utworzone po zbudowaniu danych dostaje puste konto (saldo 0 — suma kont bez zmian).
  const npcList = () => {
    const s = data();
    const list = Object.values(W.C).filter((c) => c.npc);
    for (const c of list) if (!s.accounts.has("country:" + c.id)) s.accounts.set("country:" + c.id, { account: "country:" + c.id, type: "countries", owner: c.name, ownerId: null, balance: 0 });
    return list;
  };
  m["world.npcTreasuries"] = (u, p) => {
    if (!guard(u)) return err(MANAGE_DENIED);
    const s = data();
    const q = String(p.query ?? "").trim().toLowerCase();
    if (q.length > 32) return err("Za długie wyszukiwanie (najwyżej 32 znaki).");
    const countries = npcList()
      .filter((c) => !q || c.name.toLowerCase().includes(q))
      .map((c) => ({ id: c.id, name: c.name, color: c.color, npc: true, account: "country:" + c.id, balance: s.accounts.get("country:" + c.id)?.balance ?? 0 }))
      .sort((a, b) => a.name.localeCompare(b.name, "pl"));
    const bulk = (s.bulk || []).slice(-10).reverse().map((op) => ({ key: op.key, state: op.state, time: op.time, perCountry: op.perCountry, count: op.count, total: op.total, reason: op.reason, legs: op.legs.map((l) => ({ country: l.country, name: l.name, account: l.account })) }));
    return okd({ reserve: s.accounts.get("world:reserve").balance, countries, bulk, bulkAvailable: W.npcBulkAvailable !== false });
  };
  // Przelew do WSZYSTKICH państw NPC (direction "toAllNpc"): jedna operacja z kluczem `key` — ten sam klucz ponownie
  // = {replayed:true} bez przelewów; brak środków na całość / zero państw = odmowa bez przelewów; suma kont = pula.
  const bulkTransfer = (u, p) => {
    const s = data();
    s.bulk ||= [];
    const key = String(p.key ?? "");
    if (!/^[a-z0-9-]{8,64}$/.test(key)) return err("Nieprawidłowy klucz operacji.");
    const reserve = s.accounts.get("world:reserve");
    const totalMoney = () => [...s.accounts.values()].reduce((t, a) => t + a.balance, 0);
    const view = (op, replayed, paidNow) => okd({
      ok: true, key: op.key, replayed, state: op.state, perCountry: op.perCountry, count: op.count, total: op.total, paidNow, reserve: reserve.balance, totalMoney: totalMoney(),
      legs: op.legs.map((l) => ({ ...l })), message: replayed ? `Ta operacja została już wykonana (${op.count} państw NPC, razem ${op.total} md) — nic nie przelano ponownie.` : `Przelano po ${op.perCountry} md do ${op.count} państw NPC (razem ${op.total} md).`, durable: true,
    });
    const done = s.bulk.find((op) => op.key === key);
    if (done) return view(done, true, 0);
    const list = npcList().sort((a, b) => a.name.localeCompare(b.name, "pl"));
    if (!list.length) return err("Brak państw NPC — nie ma komu przelać.");
    const amount = typeof p.amount === "number" ? p.amount : parseMoney(String(p.amount ?? ""));
    if (!Number.isSafeInteger(amount) || amount <= 0) return err("Nieprawidłowa kwota.");
    if (Number(p.count) !== list.length) return err("Liczba państw NPC zmieniła się — odśwież podgląd i potwierdź ponownie.");
    const total = amount * list.length;
    if (typeof p.confirm !== "number" || p.confirm !== total) return err("Potwierdzona kwota nie zgadza się z kwotą przelewu.");
    const reason = String(p.reason ?? "").trim();
    if (reason.length < 3 || reason.length > 120) return err("Podaj powód przelewu (3–120 znaków).");
    if (reserve.balance < total) return err("Za mało środków w rezerwie Skarbu Świata.");
    const legs = [];
    for (const c of list) {
      const acc = s.accounts.get("country:" + c.id);
      reserve.balance -= amount;
      acc.balance += amount;
      c.treasury = acc.balance;
      s.rows.push({ seq: (s.rows.at(-1)?.seq ?? 0) + 1, time: Date.now(), type: "TRANSFER", from: reserve.account, to: acc.account, amount, category: "world", categoryLabel: CATEGORY_LABELS.world,
        reason: `WorldSteward: ${reason} [npc-bulk:${key}]`, plugin: "Skarbiec", fromAfter: reserve.balance, toAfter: acc.balance, fromLabel: reserve.owner, fromType: reserve.type, toLabel: acc.owner, toType: acc.type });
      legs.push({ country: c.id, name: c.name, account: acc.account, amount, balance: acc.balance });
    }
    if (totalMoney() !== CAP) throw new Error("Atrapa: suma kont różni się od puli");
    const op = { key, state: "DONE", time: Date.now(), perCountry: amount, count: list.length, total, reason, legs };
    s.bulk.push(op);
    return view(op, false, total);
  };
  m["world.npcTransfer"] = (u, p) => {
    if (!guard(u)) return err(MANAGE_DENIED);
    if (p.direction === "toAllNpc") return bulkTransfer(u, p);
    const s = data();
    const c = npcList().find((x) => x.id === p.country);
    const acc = c && s.accounts.get("country:" + c.id);
    if (!acc) return err("Nie ma takiego państwa NPC.");
    if (p.direction !== "toCountry" && p.direction !== "toReserve") return err("Nieprawidłowy kierunek przelewu.");
    const amount = typeof p.amount === "number" ? p.amount : parseMoney(String(p.amount ?? ""));
    if (!Number.isSafeInteger(amount) || amount <= 0) return err("Nieprawidłowa kwota.");
    if (Number(p.confirm) !== amount || typeof p.confirm !== "number") return err("Potwierdzona kwota nie zgadza się z kwotą przelewu.");
    const reason = String(p.reason ?? "").trim();
    if (reason.length < 3 || reason.length > 120) return err("Podaj powód przelewu (3–120 znaków).");
    const reserve = s.accounts.get("world:reserve");
    const [from, to] = p.direction === "toCountry" ? [reserve, acc] : [acc, reserve];
    if (from.balance < amount) return err(p.direction === "toCountry" ? "Za mało środków w rezerwie Skarbu Świata." : "Za mało środków w skarbcu kraju.");
    from.balance -= amount;
    to.balance += amount;
    c.treasury = acc.balance;
    const seq = (s.rows.at(-1)?.seq ?? 0) + 1;
    s.rows.push({ seq, time: Date.now(), type: "TRANSFER", from: from.account, to: to.account, amount, category: "world", categoryLabel: CATEGORY_LABELS.world, reason: "WorldSteward: " + reason, plugin: "Skarbiec",
      fromAfter: from.balance, toAfter: to.balance, fromLabel: from.owner, fromType: from.type, toLabel: to.owner, toType: to.type });
    const total = [...s.accounts.values()].reduce((t, a) => t + a.balance, 0);
    if (total !== CAP) throw new Error("Atrapa: suma kont różni się od puli");
    return okd({ ok: true, message: `Przelano ${amount} md ${p.direction === "toCountry" ? "do skarbca: " + c.name : "do rezerwy z: " + c.name}.`, amount, reserve: reserve.balance, balance: acc.balance, seq });
  };

  // ---------------------------------------------------------------- gospodarstwa (obieg world:households)
  const HH = "world:households";
  m["world.stats.households"] = (u, p) => {
    if (!guard(u)) return err(DENIED);
    const s = data();
    const days = Math.floor(num(p.days, 30));
    if (days < 7 || days > 90) return err("days 7..90");
    const acc = s.accounts.get(HH);
    const to = Date.now(), from = to - days * DAY;
    const rows = s.rows.filter((r) => r.time >= from && (r.from === HH || r.to === HH));
    const daily = new Map();
    for (let t = midnight(from); t <= to; t = midnight(t + 26 * HOUR)) daily.set(dayKey(t), { day: dayKey(t), t, in: 0, out: 0, net: 0, byCategory: {} });
    const cats = new Map(), cps = new Map();
    let tin = 0, tout = 0;
    for (const r of rows) {
      const inc = r.to === HH;
      const d = daily.get(dayKey(r.time));
      if (d) {
        d[inc ? "in" : "out"] += r.amount;
        d.net += inc ? r.amount : -r.amount;
        d.byCategory[r.category] = (d.byCategory[r.category] || 0) + (inc ? r.amount : -r.amount);
      }
      if (inc) tin += r.amount; else tout += r.amount;
      const c = cats.get(r.category) || { key: r.category, label: CATEGORY_LABELS[r.category], in: 0, out: 0, count: 0 };
      c[inc ? "in" : "out"] += r.amount; c.count++; cats.set(r.category, c);
      const other = inc ? r.from : r.to;
      const cp = cps.get(other || "(brak)") || { account: other, label: inc ? r.fromLabel : r.toLabel, type: inc ? r.fromType : r.toType, in: 0, out: 0, count: 0 };
      cp[inc ? "in" : "out"] += r.amount; cp.count++; cps.set(other || "(brak)", cp);
    }
    const last7 = s.rows.filter((r) => r.time >= to - 7 * DAY && (r.from === HH || r.to === HH));
    const a7in = last7.filter((r) => r.to === HH).reduce((t, r) => t + r.amount, 0) / 7;
    const a7out = last7.filter((r) => r.from === HH).reduce((t, r) => t + r.amount, 0) / 7;
    const net7 = last7.length ? Math.round(a7in - a7out) : null;
    const forecastDays = net7 !== null && net7 < 0 ? Math.floor(acc.balance / -net7) : null;
    const after = (r) => (r.to === HH ? r.toAfter : r.fromAfter);
    const startBal = rows.length ? after(rows[0]) - (rows[0].to === HH ? rows[0].amount : -rows[0].amount) : acc.balance;
    const laborRows = s.rows.filter((r) => r.category === "labor");
    const today = midnight(to);
    return okd({
      account: HH, balance: acc.balance, days, from, to, daily: [...daily.values()],
      totals: { in: tin, out: tout, net: tin - tout },
      avg7: { in: last7.length ? Math.round(a7in) : null, out: last7.length ? Math.round(a7out) : null, net: net7, days: 7 },
      forecastDays, forecastDate: forecastDays === null ? null : dayKey(to + forecastDays * DAY), selfSustaining: net7 !== null && net7 >= 0,
      categories: [...cats.values()].sort((a, b) => b.in + b.out - a.in - a.out),
      counterparties: [...cps.values()].sort((a, b) => b.in + b.out - a.in - a.out).slice(0, 50),
      points: [{ t: from, balance: startBal }, ...rows.map((r) => ({ t: r.time, balance: after(r) })), { t: to, balance: acc.balance }].slice(-500),
      rows: rows.slice(-200).reverse().map((r) => ({ ...r })),
      labor: {
        percent: 15, dailyCap: 20_000, targetBalance: 5_000_000, settleAfterHours: 24,
        today: laborRows.filter((r) => r.time >= today).reduce((t, r) => t + r.amount, 0), pending: 1_234, due: 456,
        settledTotal: laborRows.reduce((t, r) => t + r.amount, 0), cancelledTotal: 300, prefixes: ["Budowa:", "Naprawa"], refundPrefixes: ["Zwrot:"],
      },
      grants: s.grants.filter((g) => g.account === HH).map((g) => ({ key: g.key, label: g.label, account: g.account, amount: g.amount, state: g.state === "UNCERTAIN" ? "PENDING" : g.state })),
      categoryLabels: { ...CATEGORY_LABELS },
    });
  };

  // ---------------------------------------------------------------- zasilenia wdrożeniowe (jednorazowe przydziały z rezerwy)
  const grantsView = () => {
    const s = data();
    return {
      reserve: s.accounts.get("world:reserve").balance,
      autoOnStart: false,
      grants: s.grants.map((g) => {
        const given = s.rows.filter((r) => r.from === "world:reserve" && r.to === g.account);
        return { ...g, balance: s.accounts.get(g.account).balance, fromReserveTotal: given.reduce((t, r) => t + r.amount, 0), fromReserveCount: given.length, lastFromReserve: given.length ? given.at(-1).time : null };
      }),
    };
  };
  m["world.grants"] = (u) => (guard(u) ? okd(grantsView()) : err(MANAGE_DENIED));
  m["world.grantsApply"] = (u, p) => {
    if (!guard(u)) return err(MANAGE_DENIED);
    const s = data();
    const retry = p.retry === "true";
    const all = p.key === "wszystkie";
    const list = all ? s.grants.filter((g) => g.state === "NEW" || (retry && g.state === "UNCERTAIN")) : s.grants.filter((g) => g.key === p.key);
    if (!all && !list.length) return err("Nie ma takiego zasilenia.");
    if (!list.length) return err("To zasilenie zostało już wykonane (wszystkie).");
    for (const g of list) {
      if (g.state === "DONE") return err(`To zasilenie zostało już wykonane (${g.label}).`);
      if (g.state === "UNCERTAIN" && !retry) return err(`Stan zasilenia „${g.label}” jest niepewny — sprawdź saldo konta i ponów świadomie.`);
    }
    const reserve = s.accounts.get("world:reserve");
    if (reserve.balance < list.reduce((t, g) => t + g.amount, 0)) return err("Za mało środków w rezerwie Skarbu Świata.");
    for (const g of list) {
      const to = s.accounts.get(g.account);
      reserve.balance -= g.amount;
      to.balance += g.amount;
      s.rows.push({ seq: (s.rows.at(-1)?.seq ?? 0) + 1, time: Date.now(), type: "TRANSFER", from: reserve.account, to: to.account, amount: g.amount, category: "world", categoryLabel: CATEGORY_LABELS.world,
        reason: "Zasilenie wdrożeniowe: " + g.label, plugin: "Skarbiec", fromAfter: reserve.balance, toAfter: to.balance, fromLabel: reserve.owner, fromType: reserve.type, toLabel: to.owner, toType: to.type });
      g.state = "DONE";
    }
    if ([...s.accounts.values()].reduce((t, a) => t + a.balance, 0) !== CAP) throw new Error("Atrapa: suma kont różni się od puli");
    return okd({ ...grantsView(), ok: true, applied: list.map((g) => g.key), durable: true, message: "Wykonano zasilenie: " + list.map((g) => g.label).join(", ") + "." });
  };

  m["world.stats.merchant"] = (u) => (guard(u) ? okd(structuredClone(data().merchant)) : err(DENIED));
  return m;
}
