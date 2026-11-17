// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Atrapa country.economy (kontrakt: PANEL.md „Gospodarka kraju”, kształt jak EconomyReport w pluginie) do E2E.
// Dostęp jak w pluginie (EconomyMethods.access): władca i doradcy kraju, admin, osobisty zarządca (W.railwayManager),
// zarządca kraju NPC. Obywatel, gracz bez kraju i władca obcego kraju dostają odmowę bez danych.

export const DENIED = "Brak dostępu do gospodarki tego kraju — widzą ją tylko władca i doradcy kraju.";
const MIN = 60 * 1000;
const H = 60 * MIN;

const g = (key, label, kind, amount) => ({ key, label, kind, amount });

function polanie(now) {
  const workplaces = [
    { id: "a1b2c3d4-0000-4000-8000-000000000001", short: "a1b2c3d4", function: "kopalnia", status: "Magazyn pełny — praca wstrzymana", state: "FULL", stateLabel: "pełna skrzynia — stoi", x: 340, y: null, z: -170, daily: null, extra: null },
    { id: "b2c3d4e5-0000-4000-8000-000000000002", short: "b2c3d4e5", function: "kamieniołom", status: "Magazyn gotowy", state: "OK", stateLabel: "pracuje", x: 360, y: null, z: -120, daily: null, extra: null },
    { id: "c3d4e5f6-0000-4000-8000-000000000003", short: "c3d4e5f6", function: "pole", status: "Magazyn gotowy", state: "OK", stateLabel: "pracuje", x: 290, y: null, z: -140, daily: null, extra: null },
    { id: "d4e5f6a7-0000-4000-8000-000000000004", short: "d4e5f6a7", function: "pole", status: "Praca wstrzymana — magazyn niezaładowany, zniszczony lub zmienił właściciela", state: "UNLOADED", stateLabel: "teren niewczytany", x: 520, y: null, z: -60, daily: null, extra: null },
    { id: "e5f6a7b8-0000-4000-8000-000000000005", short: "e5f6a7b8", function: "przystań", status: "Magazyn gotowy", state: "OK", stateLabel: "pracuje", x: 250, y: null, z: -200, daily: null, extra: null },
    { id: "f6a7b8c9-0000-4000-8000-000000000006", short: "f6a7b8c9", function: "kopalnia", status: "Brak oświetlenia — brak pochodni w magazynie, praca wstrzymana", state: "TORCHES", stateLabel: "brak pochodni — stoi", x: 380, y: null, z: -190, daily: null, extra: null },
  ];
  const stores = [
    { label: "kopalnia (340 58 -170)", kind: "kopalnia", used: 27, slots: 27, fill: 1, full: true, goods: [g("minecraft:cobblestone", "Bruk", "stone", 1472), g("minecraft:raw_iron", "Surowe żelazo", "ore", 96), g("minecraft:coal", "Węgiel", "ore", 70), g("minecraft:raw_copper", "Surowa miedź", "ore", 40)], goodsCount: 1678, world: "world", x: 340, y: 58, z: -170, extra: null },
    { label: "kamieniołom (360 66 -120)", kind: "kamieniołom", used: 25, slots: 27, fill: 0.926, full: false, goods: [g("minecraft:cobblestone", "Bruk", "stone", 1530), g("minecraft:andesite", "Andezyt", "stone", 64)], goodsCount: 1594, world: "world", x: 360, y: 66, z: -120, extra: null },
    { label: "pole (290 65 -140)", kind: "pole", used: 4, slots: 27, fill: 0.148, full: false, goods: [g("minecraft:wheat", "Pszenica", "food", 140), g("minecraft:carrot", "Marchew", "food", 52), g("minecraft:wheat_seeds", "Nasiona pszenicy", "other", 33)], goodsCount: 225, world: "world", x: 290, y: 65, z: -140, extra: null },
    { label: "przystań (250 62 -200)", kind: "przystań", used: 2, slots: 27, fill: 0.074, full: false, goods: [g("minecraft:cod", "Dorsz", "fish", 41), g("minecraft:salmon", "Łosoś", "fish", 12)], goodsCount: 53, world: "world", x: 250, y: 62, z: -200, extra: null },
  ];
  const totals = [g("minecraft:cobblestone", "Bruk", "stone", 3002), g("minecraft:wheat", "Pszenica", "food", 140), g("minecraft:raw_iron", "Surowe żelazo", "ore", 96), g("minecraft:coal", "Węgiel", "ore", 70), g("minecraft:andesite", "Andezyt", "stone", 64), g("minecraft:carrot", "Marchew", "food", 52), g("minecraft:cod", "Dorsz", "fish", 41), g("minecraft:raw_copper", "Surowa miedź", "ore", 40), g("minecraft:wheat_seeds", "Nasiona pszenicy", "other", 33), g("minecraft:salmon", "Łosoś", "fish", 12)];
  const points = Array.from({ length: 20 }, (_, i) => ({ t: now - (19 - i) * 15 * MIN, food: 160 + i * 3 - (i > 12 ? 40 : 0), population: 14 + (i > 10 ? 1 : 0), goods: 4200 + i * 70, usedSlots: 50 + i % 4, slots: 108, haulers: i % 3, waitingSites: 1 }));
  return {
    version: 1, id: "c-polanie", name: "Królestwo Polan", color: "#B02E26", npc: false, generatedAt: now, available: true, settlement: "Gród",
    sources: { flow: true, life: true, work: true, flowVersion: null, unknown: [], errors: {} },
    summary: { workplaces: 6, working: 3, blocked: 2, unloaded: 1, stores: 4, fullStores: 1, usedSlots: 58, slots: 108, goods: 3550, foodDays: 3.9, haulers: 2, sites: 3, waitingSites: 1, errors: 2, warnings: 4, population: 14, housing: 18 },
    production: {
      workplaces,
      byFunction: [
        { function: "kamieniołom", count: 1, working: 1, blocked: 0, full: 0, unloaded: 0 },
        { function: "kopalnia", count: 2, working: 0, blocked: 2, full: 1, unloaded: 0 },
        { function: "pole", count: 2, working: 1, blocked: 0, full: 0, unloaded: 1 },
        { function: "przystań", count: 1, working: 1, blocked: 0, full: 0, unloaded: 0 },
      ],
      daily: { source: "estimate", items: [g("minecraft:cobblestone", "Bruk", "stone", 2304), g("minecraft:wheat", "Pszenica", "food", 210), g("minecraft:raw_iron", "Surowe żelazo", "ore", 72), g("minecraft:cod", "Dorsz", "fish", 40), g("minecraft:coal", "Węgiel", "ore", 31)], gained: [], coveredMs: 4 * H + 40 * MIN, since: now - 5 * H, windowMs: 24 * H },
      today: { food: 64, foodCap: 120, fish: 18, fishCap: 40, adults: 12, assigned: 9 },
      workers: [{ job: "FARMER", label: "rolnik", workers: 3, slots: 6, places: 2 }, { job: "MINER", label: "górnik", workers: 3, slots: 4, places: 2 }, { job: "QUARRYMAN", label: "kamieniarz", workers: 2, slots: 2, places: 1 }, { job: "FISHER", label: "rybak", workers: 1, slots: 2, places: 1 }],
    },
    stores: { list: stores, totals, usedSlots: 58, slots: 108, fill: 0.537, fullCount: 1 },
    granary: { food: 220, capacity: 500, meal: 56, days: 3.9, fill: 0.44, population: 14, housing: 18, freeBeds: 4, foodBalance: -6, hungryDays: 0, homelessDays: 0, foodPerSettler: 20, growth: { possible: true, text: "możliwy (4 wolne łóżka)" }, growth24h: { delta: 1, from: 13, at: now - 23 * H, source: "history" }, hasGranary: true, storedFood: 245, extra: null },
    haulers: {
      count: 2,
      walking: [
        { text: "16× bruk: kamieniołom (360 66 -120) → Wieża strażnicza (330 70 -120)", amount: 16, item: "bruk", from: "kamieniołom (360 66 -120)", to: "Wieża strażnicza (330 70 -120)" },
        { text: "12× pszenica: pole (290 65 -140) → spichlerz (318 66 -150)", amount: 12, item: "pszenica", from: "pole (290 65 -140)", to: "spichlerz (318 66 -150)" },
      ],
      recent: [{ time: "12:41:07", text: "Spichlerz przyjął 36 pkt jedzenia" }, { time: "12:38:52", text: "16× bruk: kamieniołom (360 66 -120) → Wieża strażnicza (330 70 -120)" }, { time: null, text: "Resztki z placu budowy (5 szt.) odniesione do magazynu" }],
    },
    sites: [
      { id: "s-dom", name: "Dom", stage: "konstrukcja: słupy i belki", percent: 15, builders: 2, status: "czeka na drewno — brak w dostępnych magazynach kraju; włóż drewno do skrzyni budowy albo magazynu", state: "WAITING", waitingFor: "drewno", source: null, waitedMs: 25 * MIN, buyInMs: 35 * MIN, waitedApprox: true, needs: [{ key: "drewno", label: "drewno", units: 180, have: 0, missing: 180 }, { key: "kamien", label: "kamień", units: 40, have: 3066, missing: 0 }], x: 300, y: 68, z: -160, extra: null },
      { id: "s-wieza", name: "Wieża strażnicza", stage: "ściany", percent: 58, builders: 3, status: "", state: "WORKING", waitingFor: null, source: null, waitedMs: null, buyInMs: null, waitedApprox: false, needs: [{ key: "kamien", label: "kamień", units: 220, have: 3066, missing: 0 }], x: 330, y: 70, z: -120, extra: null },
      { id: "s-kosciol", name: "Kościół", stage: "okna i drzwi", percent: 81, builders: 2, status: "kupiono szkło na targu (koszt zamówienia) — magazyny nie dostarczyły w 60 min", state: "BOUGHT", waitingFor: null, source: null, waitedMs: null, buyInMs: null, waitedApprox: false, needs: [], x: 310, y: 69, z: -100, extra: { glassNeeded: 24 } },
    ],
    materials: [
      { key: "drewno", label: "drewno", needed: 180, have: 0, missing: 180, producers: [], produced: false, build: "tartak" },
      { key: "kamien", label: "kamień", needed: 260, have: 3066, missing: 0, producers: ["kamieniołom", "kopalnia"], produced: true, build: "kamieniołom" },
    ],
    warnings: [
      { level: "error", code: "FULL_STORE", title: "Kopalnia stoi — pełna skrzynia", text: "Skrzynia kopalnia (x 340, z -170) jest pełna, więc praca stoi przed następnym urobkiem.", hint: "Opróżnij skrzynię, sprzedaj nadwyżkę kupcowi albo zbuduj magazyn — tragarze odwiozą towar z pełnych skrzyń." },
      { level: "error", code: "MATERIAL", title: "Brakuje drewna", text: "Budowy potrzebują jeszcze 180 jedn. drewna, we wczytanych skrzyniach jest 0.", hint: "Zbuduj tartak albo kup drewno u kupca — do tego czasu budowa kupuje drewno za monety z zamówienia." },
      { level: "warn", code: "TORCHES", title: "Kopalnia bez pochodni", text: "Brak oświetlenia — brak pochodni w magazynie, praca wstrzymana (x 380, z -190).", hint: "Włóż pochodnie do skrzyni kopalni — górnicy stawiają jedną co ok. 7 bloków." },
      { level: "warn", code: "STORE_ALMOST_FULL", title: "Skrzynia prawie pełna: kamieniołom (360 66 -120)", text: "Zajęte 25 z 27 miejsc.", hint: "Opróżnij ją albo zbuduj magazyn, zanim praca stanie." },
      { level: "warn", code: "NO_WAREHOUSE", title: "Brak magazynu kraju", text: "Pełne skrzynie miejsc pracy nie mają dokąd odwieźć towaru.", hint: "Zbuduj magazyn albo wskaż magazyn domyślny (/ludnosc magazyn domyslny)." },
      { level: "warn", code: "HOMELESS", title: "1 bezdomny", text: "Nie wszyscy obywatele mają łóżko.", hint: "Zbuduj domy — obywatele z oszczędnościami stawiają też własne chaty." },
      { level: "info", code: "UNLOADED", title: "1 miejsce pracy w niewczytanym terenie", text: "Skrzynie tych miejsc są w chunkach, których serwer teraz nie trzyma w pamięci; praca i odczyt wracają, gdy w pobliżu jest gracz.", hint: null },
      { level: "info", code: "UNEMPLOYED", title: "3 bezrobotnych", text: "Dorośli bez stałej pracy płacą mniejszy podatek.", hint: "Zbuduj miejsca pracy: pole, tartak, kamieniołom, kopalnię, przystań albo warsztat." },
    ],
    citizens: { available: true, population: 14, adults: 12, children: 2, housed: 13, homeless: 1, employed: 9, unemployed: 3, couples: 4, savings: 8_640, average: 617, unpaid: 0, statsAt: now - 4000, taxDaily: 154, taxSource: "settled", taxDue: 160, wagesDaily: 90, wagesDue: 90, income: 310, meals: 112, market: 20, workers: 9, lastDay: 20390, huts: 1 },
    history: { points, from: now - 24 * H, to: now },
    settings: { materialWaitMinutes: 60, requireMaterials: true, foodPerSettler: 20 },
    extra: null,
  };
}

function emptyCountry(c, now) {
  return {
    version: 1, id: c.id, name: c.name, color: c.color, npc: c.npc, generatedAt: now, available: true, settlement: null,
    sources: { flow: true, life: true, work: true, flowVersion: 2, unknown: ["chains"], errors: {} },
    summary: { workplaces: 0, working: 0, blocked: 0, unloaded: 0, stores: 0, fullStores: 0, usedSlots: 0, slots: 0, goods: 0, foodDays: null, haulers: 0, sites: 0, waitingSites: 0, errors: 0, warnings: 0, population: c.population.population, housing: c.population.housing },
    production: { workplaces: [], byFunction: [], daily: { source: "none", items: [], coveredMs: 0, since: null, windowMs: 24 * H }, today: null, workers: null },
    stores: { list: [], totals: [], usedSlots: 0, slots: 0, fill: null, fullCount: 0 },
    granary: { food: c.population.food, capacity: c.population.foodCapacity, meal: null, days: null, fill: null, population: c.population.population, housing: c.population.housing, freeBeds: Math.max(0, c.population.housing - c.population.population), foodBalance: c.population.foodBalance, hungryDays: 0, homelessDays: 0, foodPerSettler: 20, growth: { possible: true, text: "możliwy" }, growth24h: null, hasGranary: false, storedFood: 0, extra: null },
    haulers: { count: 0, walking: [], recent: [] },
    sites: [], materials: [],
    warnings: [{ level: "info", code: "NO_WORKPLACES", title: "Kraj nic nie wytwarza", text: "Nie ma pól, tartaków, kamieniołomów, kopalń ani przystani.", hint: "Zbuduj pierwsze miejsca pracy — produkcja trafia do skrzyń." }],
    citizens: { available: false, population: c.population.population, taxDaily: c.population.dailyTax, taxSource: "expected" },
    history: { points: [], from: now - 24 * H, to: now },
    settings: { materialWaitMinutes: 60, requireMaterials: true, foodPerSettler: 20 },
    extra: { chains: [{ name: "szkło", steps: ["piasek", "węgiel", "szkło"] }] },
  };
}

/** Metody atrapy: `country.economy {id, fresh?}`. */
export function economyMethods(W, { canNpc, err, okd }) {
  W.economyCalls = W.economyCalls || [];
  return {
    "country.economy": (u, { id, fresh }) => {
      const c = W.C[id];
      if (!c) return err("Nie ma takiego kraju.");
      const rank = c.members[u.uuid];
      const access = rank === "RULER" ? "RULER" : rank === "ADVISOR" ? "ADVISOR" : u.admin ? "ADMIN" : W.railwayManager === u.uuid ? "OWNER" : c.npc && canNpc(u, id) ? "NPC_MANAGER" : null;
      W.economyCalls.push({ user: u.name, id, fresh: !!fresh, access });
      if (!access) return err(DENIED);
      const now = Date.now();
      const data = id === "c-polanie" ? polanie(now) : emptyCountry(c, now);
      return okd({ ...data, access, cached: false, ageMs: 0, refreshSeconds: 15, buildMs: 0.42 });
    },
  };
}
