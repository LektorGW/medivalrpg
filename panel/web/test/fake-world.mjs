// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Realistyczny świat fałszywego pluginu do E2E: kraj gracza, sojusznik, dwa państwa NPC, admin, zarządca NPC.
// Dane i metody v2 (pełne zarządzanie państwem NPC) są w fake-npc-v2.mjs.
// Uprawnienia liczone jak w PANEL.md (R/A/M/*/ADM + role zarządców NPC); mutacje zmieniają stan.
import { randomUUID } from "node:crypto";
import { addNpcV2, emptyNpcV2, npcV2Methods } from "./fake-npc-v2.mjs";
import { addPostep, overviewEra, postepMethods } from "./fake-postep.mjs";
import { addPlan, planMethods } from "./fake-plan.mjs";
import { healthMethods } from "./fake-health.mjs";
import { railwayMethods } from "./fake-railway.mjs";
import { worldStatsMethods } from "./fake-world-stats.mjs";
import { economyMethods } from "./fake-economy.mjs";
import { flagMethods } from "./fake-flag.mjs";
import { firmyMethods } from "./fake-firmy.mjs";

const H = 3600 * 1000;
const now = Date.now();
const bld = (type, name, fn, beds, i) => ({ type, name, function: fn, beds, world: "world", x: 300 + i * 9, y: 68, z: -150 + (i % 3) * 9 });

export function createWorld() {
  const U = {
    bolko: { uuid: randomUUID(), name: "Bolko", password: "Grunwald1410", balance: 12_345, online: true },
    dobrawa: { uuid: randomUUID(), name: "Dobrawa", password: "x", balance: 4_210, online: true },
    siemowit: { uuid: randomUUID(), name: "Siemowit", password: "x", balance: 85, online: false },
    maciej: { uuid: randomUUID(), name: "Maciej", password: "Korona2026", balance: 250_000, online: true, admin: true },
    ziemowit: { uuid: randomUUID(), name: "Ziemowit", password: "Mazowsze1", balance: 3_150, online: false },
    wedrowiec: { uuid: randomUUID(), name: "Wedrowiec", password: "Szlak123", balance: 42, online: true },
  };
  const C = {
    "c-polanie": {
      id: "c-polanie",
      name: "Królestwo Polan",
      color: "#B02E26",
      npc: false,
      ruler: "Bolko",
      description: "Kraina nad Wartą, gdzie gród Gniezno strzeże szlaku bursztynowego. Władca Bolko zaprasza rzemieślników i rycerzy.",
      tax: 15,
      taxMax: 500,
      capital: { world: "world", x: 312, z: -148 },
      chunks: 46,
      areaBlocks: 46 * 256,
      created: now - 38 * 24 * H,
      members: { [U.bolko.uuid]: "RULER", [U.dobrawa.uuid]: "ADVISOR", [U.siemowit.uuid]: "CITIZEN" },
      debts: { [U.siemowit.uuid]: 45 },
      treasury: 48_250,
      log: [
        { time: now - 0.3 * H, type: "deposit", amount: 500, actor: "Dobrawa" },
        { time: now - 5 * H, type: "tax", amount: 930, actor: "" },
        { time: now - 6 * H, type: "upkeep", amount: -92, actor: "" },
        { time: now - 26 * H, type: "recruit", amount: -1200, actor: "Bolko" },
        { time: now - 30 * H, type: "withdraw", amount: -2000, actor: "Bolko" },
        { time: now - 50 * H, type: "claim", amount: -210, actor: "Bolko" },
      ],
      population: {
        population: 14,
        housing: 18,
        food: 220,
        foodCapacity: 500,
        dailyTax: 140,
        foodBalance: -6,
        hungryDays: 0,
        homelessDays: 0,
        buildings: [
          ...Array.from({ length: 3 }, (_, i) => bld("chata", "Chata", "HOUSING", 2, i)),
          ...Array.from({ length: 3 }, (_, i) => bld("dom", "Dom", "HOUSING", 4, i + 3)),
          bld("spichlerz", "Spichlerz", "GRANARY", 0, 6),
          bld("koszary", "Koszary", "BARRACKS", 0, 7),
          bld("kuznia", "Kuźnia", "SMITHY", 0, 8),
          bld("studnia", "Studnia", "WELL", 0, 9),
          bld("studnia", "Studnia", "WELL", 0, 10),
        ],
        sites: [
          { type: "wieza", name: "Wieża strażnicza", percent: 58, world: "world", x: 330, y: 70, z: -120 },
          { type: "dom", name: "Dom", percent: 15, world: "world", x: 300, y: 68, z: -160 },
        ],
      },
      army: { piechota: 6, lucznik: 4, rycerz: 1 },
      morale: 72,
    },
    "c-wislanie": {
      id: "c-wislanie",
      name: "Wiślanie",
      color: "#3C44AA",
      npc: false,
      ruler: "Maciej",
      description: "Gród Kraków nad Wisłą.",
      tax: 20,
      capital: { world: "world", x: -820, z: 640 },
      chunks: 31,
      areaBlocks: 31 * 256,
      members: { [U.maciej.uuid]: "RULER" },
      debts: {},
      treasury: 15_500,
      log: [],
      population: { population: 9, housing: 10, food: 120, foodCapacity: 300, foodBalance: 4, dailyTax: 90, hungryDays: 0, homelessDays: 0, buildings: [bld("dom", "Dom", "HOUSING", 4, 0), bld("dom", "Dom", "HOUSING", 4, 1)], sites: [] },
      army: { piechota: 3, lucznik: 2, rycerz: 0 },
      morale: 88,
    },
    "npc-mazowsze": {
      id: "npc-mazowsze",
      name: "Księstwo Mazowsze",
      color: "#5E7C16",
      npc: true,
      ruler: "Konrad I",
      description: "Księstwo NPC rządzone przez Konrada.",
      tax: 0,
      capital: { world: "world", x: 1450, z: -300 },
      chunks: 58,
      areaBlocks: 58 * 256,
      members: {},
      debts: {},
      treasury: 71_400,
      log: [],
      population: { population: 22, housing: 24, food: 900, foodCapacity: 1000, foodBalance: 12, dailyTax: 220, hungryDays: 0, homelessDays: 0, buildings: [[1420, -312], [1434, -312], [1420, -280], [1434, -280], [1468, -282], [1478, -266]].map(([x, z], i) => ({ ...bld("dom", "Dom", i === 5 ? "mieszkanie • do rozbiórki (plan v2)" : "HOUSING", 4, 0), x, z })).concat([{ ...bld("koszary", "Koszary", "BARRACKS", 0, 7), x: 1470, z: -318 }, { ...bld("koszary", "Koszary", "BARRACKS", 0, 8), x: 1488, z: -300 }]), sites: [{ type: "spichlerz", name: "Spichlerz", percent: 30 }] },
      army: { piechota: 12, lucznik: 8, rycerz: 3 },
      morale: 64,
      ai: { settings: { agresja: 70, ekspansja: 55, dyplomacja: 30, bogactwo: 60, "max-chunki": 120, "max-zolnierze": 40 }, paused: false, skin: "Notch", manager: "Ziemowit" },
      aiLog: [
        { time: now - 0.2 * H, text: "Wypowiedziano wojnę: Królestwo Polan (przewaga 23 vs 11, agresja 70)" },
        { time: now - 1.4 * H, text: "Zrekrutowano 2× piechota (koszt 5 zł)" },
        { time: now - 2.1 * H, text: "Zajęto chunk 92, -20 (koszt 2 zł 1 sr)" },
        { time: now - 3.5 * H, text: "Odrzucono propozycję sojuszu od: Wiślanie (dyplomacja 30)" },
      ],
    },
    "npc-rus": {
      id: "npc-rus",
      name: "Ruś Czerwona",
      color: "#F9801D",
      npc: true,
      ruler: "Daniel",
      description: "",
      tax: 0,
      capital: { world: "world", x: 2100, z: 900 },
      chunks: 24,
      areaBlocks: 24 * 256,
      members: {},
      debts: {},
      treasury: 9_800,
      log: [],
      population: { population: 8, housing: 12, food: 300, foodCapacity: 400, foodBalance: 0, dailyTax: 60, hungryDays: 2, homelessDays: 0, buildings: Array.from({ length: 4 }, (_, i) => bld("chata", "Chata", "HOUSING", 2, i)), sites: [] },
      army: { piechota: 4, lucznik: 2, rycerz: 0 },
      morale: 50,
      ai: { settings: { agresja: 25, ekspansja: 40, dyplomacja: 80, bogactwo: 45, "max-chunki": 60, "max-zolnierze": 20 }, paused: true, skin: "", manager: null },
      aiLog: [{ time: now - 10 * H, text: "AI wstrzymana przez admina" }],
    },
  };
  const relations = { "c-polanie|c-wislanie": "ALLIANCE", "c-polanie|npc-mazowsze": "WAR", "c-wislanie|npc-rus": "ALLIANCE" };
  const sieges = [{ id: "s1", attacker: "npc-mazowsze", defender: "c-polanie", world: "world", cx: 12, cz: -4, progress: 0.35, startedAt: now - 0.1 * H, endsAt: now + 0.4 * H }];
  const UNITS = [
    { id: "piechota", name: "Piechota", cost: 250, upkeep: 12, description: "Miecz, tarcza i kolczuga" },
    { id: "lucznik", name: "Łucznik", cost: 300, upkeep: 14, description: "Łuk i skórzana zbroja" },
    { id: "rycerz", name: "Rycerz", cost: 1200, upkeep: 45, description: "Zbroja żelazna, na koniu" },
  ];
  const audit = [
    { time: now - 0.5 * H, uuid: U.bolko.uuid, name: "Bolko", ip: "203.0.113.7", action: "country.setTax", params: '{"id":"c-polanie","amount":15}', ok: true, error: null },
    { time: now - 2 * H, uuid: U.maciej.uuid, name: "Maciej", ip: "198.51.100.4", action: "admin.npc.set", params: '{"id":"npc-mazowsze","param":"agresja","value":70}', ok: true, error: null },
    { time: now - 3 * H, uuid: U.dobrawa.uuid, name: "Dobrawa", ip: "203.0.113.9", action: "member.kick", params: '{"id":"c-polanie","player":"Bolko"}', ok: false, error: "Nie możesz wyrzucić władcy." },
  ];
  const managers = { "npc-mazowsze": "Ziemowit" }; // przypisani zarządcy
  const globalNpcManagers = new Set(); // nick → zarządca wszystkich NPC
  const calls = [];
  const W = { U, C, relations, sieges, UNITS, audit, managers, globalNpcManagers, calls };
  addNpcV2(W, now);
  addPostep(W, now);
  addPlan(W);
  return W;
}

const relKey = (a, b) => [a, b].sort().join("|");

export function makeHandlers(W) {
  const byUuid = (uuid) => Object.values(W.U).find((u) => u.uuid === uuid);
  const byName = (n) => Object.values(W.U).find((u) => u.name.toLowerCase() === String(n || "").toLowerCase());
  const countryOf = (uuid) => Object.values(W.C).find((c) => c.members[uuid]);
  const rel = (a, b) => W.relations[relKey(a, b)] || "NEUTRAL";
  const W8 = { RULER: 3, ADVISOR: 2, CITIZEN: 1 };
  const managedOf = (u) => Object.entries(W.managers).filter(([, n]) => n && n.toLowerCase() === u.name.toLowerCase()).map(([id]) => id);
  const allNpc = () => Object.values(W.C).filter((c) => c.npc).map((c) => c.id).sort();
  const rolesOf = (u) => ({ admin: !!u.admin, worldManager: W.railwayManager === u.uuid, railwayManager: W.railwayManager === u.uuid, flagModerator: !!W.isFlagModerator?.(u), npcManager: W.globalNpcManagers.has(u.name), managedNpcCountries: u.admin || W.globalNpcManagers.has(u.name) ? allNpc() : managedOf(u) });
  const canNpc = (u, id) => u.admin || W.globalNpcManagers.has(u.name) || managedOf(u).includes(id);
  const soldiers = (c) => Object.values(c.army).reduce((s, n) => s + n, 0);
  const overviewEntry = (c) => ({
    id: c.id,
    name: c.name,
    color: c.color,
    npc: c.npc,
    ruler: c.ruler,
    members: Object.keys(c.members).length,
    population: c.population.population,
    soldiers: soldiers(c),
    chunks: c.chunks,
    areaBlocks: c.areaBlocks,
    allies: Object.keys(W.C).filter((o) => o !== c.id && rel(c.id, o) === "ALLIANCE"),
    wars: Object.keys(W.C).filter((o) => o !== c.id && rel(c.id, o) === "WAR"),
    ...overviewEra(W, c),
  });
  const err = (e) => ({ ok: false, error: e });
  const okd = (data = { ok: true }) => ({ ok: true, data });

  function need(u, c, rank) {
    if (!c) throw new Error("Nie ma takiego kraju");
    const r = c.members[u.uuid];
    if (!r || W8[r] < W8[rank]) throw new Error(rank === "RULER" ? "Tylko władca może to zrobić" : rank === "ADVISOR" ? "Wymagana ranga: Doradca" : "Nie jesteś członkiem tego kraju");
    return r;
  }
  function audit(u, m, ok, error) {
    W.audit.unshift({ time: Date.now(), uuid: u.uuid, name: u.name, ip: (m.user && m.user.ip) || m.ip || "?", action: m.method, params: JSON.stringify(m.params || {}), ok, error: error || null });
  }

  const methods = {
    me: (u) => {
      const c = countryOf(u.uuid);
      const RN = { RULER: "Władca", ADVISOR: "Doradca", CITIZEN: "Obywatel" };
      return okd({ uuid: u.uuid, name: u.name, admin: !!u.admin, balance: u.balance, balanceText: "", country: c ? { id: c.id, name: c.name, color: c.color, rank: c.members[u.uuid], rankName: RN[c.members[u.uuid]] } : null, roles: rolesOf(u) });
    },
    overview: () => okd({ online: Object.values(W.U).filter((x) => x.online).length + 5, maxPlayers: 40, countries: Object.values(W.C).map(overviewEntry) }),
    "country.get": (u, { id }) => {
      const c = W.C[id];
      if (!c) return err("Nie ma takiego kraju.");
      const myRank = c.members[u.uuid] || null;
      const RN = { RULER: "Władca", ADVISOR: "Doradca", CITIZEN: "Obywatel" };
      const ref = (cid) => ({ id: cid, name: W.C[cid] ? W.C[cid].name : "?", color: W.C[cid] ? W.C[cid].color : "#808080", npc: !!(W.C[cid] && W.C[cid].npc) });
      const others = Object.keys(W.C).filter((o) => o !== id);
      const canMembers = !!myRank || !!u.admin;
      const canTreasury = (myRank && W8[myRank] >= 2) || !!u.admin;
      const npcManage = c.npc && canNpc(u, id);
      const cx = Math.floor(c.capital.x / 16);
      const cz = Math.floor(c.capital.z / 16);
      const out = {
        id: c.id,
        name: c.name,
        color: c.color,
        npc: c.npc,
        description: c.description,
        ruler: c.ruler,
        rulerUuid: c.npc ? null : (byName(c.ruler) || {}).uuid || null,
        capital: { world: c.capital.world, x: cx, z: cz, blockX: (cx << 4) + 8, blockZ: (cz << 4) + 8 },
        chunks: c.chunks,
        areaBlocks: c.chunks * 256,
        memberCount: Object.keys(c.members).length,
        tax: c.tax,
        taxText: "",
        myRank,
        access: { member: canMembers, advisor: canTreasury, ruler: myRank === "RULER", admin: !!u.admin, npcManage, planRead: !!u.admin || npcManage || ["RULER","ADVISOR"].includes(myRank), planWrite: !!u.admin || npcManage || myRank === "RULER", planLevel: !!u.admin || npcManage || ["RULER","ADVISOR"].includes(myRank), planTerritory: npcManage },
      };
      if (canMembers) {
        out.members = Object.entries(c.members)
          .sort((a, b) => W8[b[1]] - W8[a[1]])
          .map(([uuid, rank]) => {
            const p = byUuid(uuid);
            return { uuid, name: p.name, rank, rankName: RN[rank], online: p.online, taxDebt: c.debts[uuid] || 0, debtDays: c.debts[uuid] ? 3 : 0, joinedAt: now - 20 * 24 * H, lastSeen: p.online ? Date.now() : now - 30 * H };
          });
      }
      if (canTreasury) out.treasury = { balance: c.treasury, balanceText: "", log: c.log.map((e) => ({ ...e, amountText: "" })) };
      if (npcManage) {
        out.npc = {
          available: true,
          settings: { ...c.ai.settings, aktywna: !c.ai.paused },
          paused: c.ai.paused,
          globalPaused: false,
          nextTickAt: c.ai.paused ? null : Date.now() + 4 * 60 * 1000,
          log: c.aiLog.map((e) => ({ time: e.time, text: e.text })),
          manager: W.managers[id] ? { uuid: (byName(W.managers[id]) || {}).uuid || "", name: W.managers[id] } : null,
        };
      }
      out.population = { ...c.population, dailyTaxText: "" };
      out.mood = W.zycie && W.mood[id] ? { ...W.mood[id] } : null;
      const byType = { ...c.army };
      out.army = {
        total: soldiers(c),
        byType,
        types: W.UNITS.map((t) => ({ id: t.id, name: t.name, description: t.description, cost: t.cost, costText: "", wage: t.upkeep, wageText: "", siegeStrength: 1, count: byType[t.id] || 0 })),
        morale: c.morale,
        wages: W.UNITS.reduce((sum, t) => sum + (byType[t.id] || 0) * t.upkeep, 0),
        wagesText: "",
      };
      out.relations = { allies: others.filter((o) => rel(id, o) === "ALLIANCE").map(ref), wars: others.filter((o) => rel(id, o) === "WAR").map(ref) };
      out.sieges = W.sieges.filter((x) => x.attacker === id || x.defender === id).map((x) => ({ ...x, attacker: ref(x.attacker), defender: ref(x.defender) }));
      return okd(out);
    },
    "country.setColor": (u, p) => (need(u, W.C[p.id], "RULER"), /^#[0-9A-F]{6}$/i.test(p.color) ? ((W.C[p.id].color = p.color), okd()) : err("Nieprawidłowy kolor")),
    "country.setDescription": (u, p) => (need(u, W.C[p.id], "RULER"), (W.C[p.id].description = String(p.text).slice(0, 300)), okd()),
    "country.setTax": (u, p) => (need(u, W.C[p.id], "RULER"), p.amount > 500 ? err("Maksymalny podatek to 5 zł") : ((W.C[p.id].tax = p.amount), okd())),
    "country.transferRuler": (u, p) => {
      const c = W.C[p.id];
      need(u, c, "RULER");
      const t = byName(p.player);
      if (!t || !c.members[t.uuid]) return err("Ten gracz nie jest członkiem kraju");
      c.members[u.uuid] = "ADVISOR";
      c.members[t.uuid] = "RULER";
      c.ruler = t.name;
      return okd();
    },
    "member.setRank": (u, p) => {
      const c = W.C[p.id];
      need(u, c, "RULER");
      const t = byName(p.player);
      if (!t || !c.members[t.uuid]) return err("Ten gracz nie jest członkiem kraju");
      c.members[t.uuid] = p.rank;
      return okd();
    },
    "member.kick": (u, p) => {
      const c = W.C[p.id];
      const r = need(u, c, "ADVISOR");
      const t = byName(p.player);
      if (!t || !c.members[t.uuid]) return err("Ten gracz nie jest członkiem kraju");
      if (c.members[t.uuid] === "RULER" || W8[c.members[t.uuid]] > W8[r]) return err("Nie możesz wyrzucić tego gracza");
      delete c.members[t.uuid];
      return okd();
    },
    "member.invite": (u, p) => (need(u, W.C[p.id], "ADVISOR"), byName(p.name) ? (byName(p.name).online ? okd() : err("Gracz musi być online, aby dostać zaproszenie")) : err("Nie znaleziono gracza " + p.name)),
    "relation.propose": (u, p) => (need(u, W.C[p.id], "RULER"), okd()),
    "relation.declareWar": (u, p) => {
      const c = W.C[p.id];
      need(u, c, "RULER");
      if (c.treasury < 1000) return err("Za mało pieniędzy w skarbcu (wojna kosztuje 10 zł)");
      c.treasury -= 1000;
      W.relations[relKey(p.id, p.target)] = "WAR";
      return okd();
    },
    "relation.breakAlliance": (u, p) => (need(u, W.C[p.id], "RULER"), (W.relations[relKey(p.id, p.target)] = "NEUTRAL"), okd()),
    "treasury.deposit": (u, p) => {
      const c = W.C[p.id];
      need(u, c, "CITIZEN");
      if (!(p.amount > 0)) return err("Nieprawidłowa kwota");
      if (u.balance < p.amount) return err("Nie masz tyle pieniędzy na koncie");
      u.balance -= p.amount;
      c.treasury += p.amount;
      c.log.unshift({ time: Date.now(), type: "deposit", amount: p.amount, actor: u.name });
      return okd({ ok: true, balance: c.treasury, playerBalance: u.balance });
    },
    "treasury.withdraw": (u, p) => {
      const c = W.C[p.id];
      need(u, c, "RULER");
      if (c.treasury < p.amount) return err("W skarbcu nie ma tyle pieniędzy");
      c.treasury -= p.amount;
      u.balance += p.amount;
      c.log.unshift({ time: Date.now(), type: "withdraw", amount: -p.amount, actor: u.name });
      return okd();
    },
    "army.recruit": (u, p) => {
      const c = W.C[p.id];
      need(u, c, "ADVISOR");
      const t = W.UNITS.find((x) => x.id === p.type);
      if (!t) return err("Nieznany typ jednostki");
      if (c.treasury < t.cost * p.count) return err("Za mało pieniędzy w skarbcu");
      c.treasury -= t.cost * p.count;
      c.army[t.id] += p.count;
      return okd();
    },
    "army.disband": (u, p) => (need(u, W.C[p.id], "RULER"), (W.C[p.id].army[p.type] = Math.max(0, W.C[p.id].army[p.type] - p.count)), okd()),
    "admin.npc.create": (u, p) => {
      if (!u.admin && !W.globalNpcManagers.has(u.name)) return err("Brak uprawnień");
      const id = "npc-" + randomUUID().slice(0, 8);
      W.C[id] = { id, name: p.name, color: p.color, npc: true, ruler: p.ruler, description: "", tax: 0, capital: { world: p.world, x: p.x, z: p.z }, chunks: 1, areaBlocks: 256, members: {}, debts: {}, treasury: 0, log: [], population: { population: 0, housing: 0, food: 0, foodCapacity: 0, foodBalance: 0, dailyTax: 0, hungryDays: 0, homelessDays: 0, buildings: [], sites: [] }, army: { piechota: 0, lucznik: 0, rycerz: 0 }, morale: 100, ai: { settings: { agresja: 50, ekspansja: 50, dyplomacja: 50, bogactwo: 50, "max-chunki": 80, "max-zolnierze": 30 }, paused: false, skin: "", manager: null }, aiLog: [], v2: emptyNpcV2() };
      return okd({ ok: true, id, aiAvailable: true });
    },
    "admin.npc.set": (u, p) => (canNpc(u, p.id) ? ((W.C[p.id].ai.settings[p.param] = p.value), okd()) : err("Brak uprawnień")),
    "admin.npc.pause": (u, p) => (canNpc(u, p.id) ? ((W.C[p.id].ai.paused = !!p.paused), okd()) : err("Brak uprawnień")),
    "admin.npc.tick": (u, p) => (canNpc(u, p.id) ? (W.C[p.id].aiLog.unshift({ time: Date.now(), category: "OTHER", text: "Wymuszony tick: brak nowych decyzji" }), okd()) : err("Brak uprawnień")),
    "admin.npc.armyAdd": (u, p) => (canNpc(u, p.id) ? ((W.C[p.id].army[p.type] = (W.C[p.id].army[p.type] || 0) + p.count), okd()) : err("Brak uprawnień")),
    "admin.npc.ruler": (u, p) => (canNpc(u, p.id) ? ((W.C[p.id].ruler = p.name), (W.C[p.id].ai.skin = p.skin || ""), okd()) : err("Brak uprawnień")),
    "admin.npc.setManager": (u, p) => (u.admin ? ((W.managers[p.id] = p.player || null), okd({ ok: true, manager: p.player ? { uuid: (byName(p.player) || {}).uuid, name: p.player } : null })) : err("Brak uprawnień")),
    "admin.country.delete": (u, p) => (u.admin ? (delete W.C[p.id], okd()) : err("Brak uprawnień")),
    "admin.country.setRuler": (u, p) => (u.admin ? ((W.C[p.id].ruler = p.player), okd()) : err("Brak uprawnień")),
    "admin.relation.set": (u, p) => (u.admin ? ((W.relations[relKey(p.a, p.b)] = p.relation), okd()) : err("Brak uprawnień")),
    "admin.treasury.adjust": (u, p) => (u.admin ? ((W.C[p.id].treasury += p.amount), okd()) : err("Brak uprawnień")),
    "admin.economy.adjust": (u, p) => (u.admin ? (byName(p.player) ? ((byName(p.player).balance += p.amount), okd()) : err("Nie ma takiego gracza")) : err("Brak uprawnień")),
    "admin.players": (u) =>
      u.admin
        ? okd(
            Object.values(W.U).map((p) => {
              const c = countryOf(p.uuid);
              return { uuid: p.uuid, name: p.name, online: p.online, country: c ? { id: c.id, name: c.name } : null, rank: c ? c.members[p.uuid] : null, balance: p.balance, lastSeen: p.online ? Date.now() : now - 30 * H };
            }),
          )
        : err("Brak uprawnień"),
    "admin.audit": (u, p) => (u.admin ? okd(W.audit.slice(0, p.limit || 100)) : err("Brak uprawnień")),
  };
  const ref = (cid) => ({ id: cid, name: W.C[cid] ? W.C[cid].name : "?", color: W.C[cid] ? W.C[cid].color : "#808080" });
  const v2 = npcV2Methods(W, { canNpc, rel, relKey, soldiers, ref, err, okd });
  const typeInfo = v2._typeInfo;
  delete v2._typeInfo;
  Object.assign(methods, v2);
  Object.assign(methods, planMethods(W, { canNpc, err, okd, typeInfo }));
  Object.assign(methods, postepMethods(W, { canNpc, err, okd }));
  Object.assign(methods, healthMethods(W, { err, okd }));
  Object.assign(methods, railwayMethods(W, { err, okd }));
  Object.assign(methods, worldStatsMethods(W, { err, okd }));
  Object.assign(methods, economyMethods(W, { canNpc, err, okd }));
  Object.assign(methods, flagMethods(W, { err, okd }));
  Object.assign(methods, firmyMethods(W, { err, okd }));

  return {
    auth: (m) => {
      const u = byName(m.name);
      if (u && u.password === m.password) return { ok: true, uuid: u.uuid, name: u.name };
      return { ok: false, error: "Nieprawidłowy nick lub hasło" };
    },
    rpc: (m) => {
      W.calls.push({ method: m.method, params: m.params, user: m.user.name });
      const u = byUuid(m.user.uuid);
      if (!u) return err("Nieznany gracz");
      const f = methods[m.method];
      if (!f) return err("Nieznana metoda: " + m.method);
      if (m.method === "admin.npc.ruler" && m.params && m.params.skin !== undefined && m.params.skin !== "" && !/^[A-Za-z0-9_]{1,16}$/.test(m.params.skin)) return err("Nieprawidłowy parametr skin");
      try {
        const r = f(u, m.params || {});
        if (!/^(me|overview|country\.get|country\.economy|admin\.players|admin\.audit|admin\.health|admin\.npc\.(history|relations|economy|buildings|army|log|mapRender|getPlan|planPreview)|postep\.(get|investPreview)|world\.stats\.(summary|accounts|flows|account|coins|trail|merchant|households)|world\.npcTreasuries|world\.grants|firmy\.(list|get|admin|contracts|disputes|loans|world\.loans))$/.test(m.method)) audit(u, m, r.ok, r.error);
        return r;
      } catch (e) {
        audit(u, m, false, e.message);
        return err(e.message);
      }
    },
  };
}
