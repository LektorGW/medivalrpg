// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Atrapa Firm graczy (Firmy → firmyPanel(UUID, String, Map), etap 2/3) dla fałszywego pluginu. Kształty odpowiedzi jak
// w firmy/src/main/java/.../panel/FirmyPanel.java i stage2/Stage2Module.java#registerPanel. Dostęp rozstrzyga atrapa przy
// KAŻDYM wywołaniu: ranking i karty publiczne (bez sald), członkowie firmy — pełna karta, właściciel (WorldSteward =
// W.railwayManager) — zarząd, spory i kredyty Skarbu Świata. Admin panelu NIE jest właścicielem Firm.
// Parametry przechodzą jak przez most Panelu (rpc/FirmyMethods): tylko dozwolone klucze, prymitywy jako tekst.
// Przełączniki testów: W.firmyAvailable=false (Firmy wyłączone), W.firmyStage2=false (etap 2 niewdrożony).
import { randomUUID } from "node:crypto";

const DAY = 24 * 3600 * 1000;
const HOUR = 3600 * 1000;
const KEYS = ["id", "sort", "frozen", "reason", "approve", "note", "winner", "page", "query", "kind"];
const STAGE2 = new Set(["firmy.contracts", "firmy.disputes", "firmy.dispute.resolve", "firmy.loans", "firmy.world.loans", "firmy.world.loanDecide", "firmy.world.loanSweep"]);
export const FIRMY_DENIED = "Brak dostępu.";

const TYPES = { cech: "Cech", kompania: "Kompania kupiecka", manufaktura: "Manufaktura", dom_handlowy: "Dom handlowy" };
const ROLES = { mistrz: "Mistrz", wspolnik: "Wspólnik", czeladnik: "Czeladnik" };
const LOAN_STATES = { REQUESTED: "czeka na zgodę właściciela", OFFERED: "oferta (pieniądze w depozycie)", ACTIVE: "spłacany", REPAID: "spłacony", DEFAULTED: "niespłacony (likwidacja)", CANCELLED: "wycofany", REJECTED: "odrzucony" };
const CONTRACT_STATES = { OPEN: "czeka na wykonawcę", ACCEPTED: "w realizacji", REPORTED: "zgłoszony jako wykonany", DISPUTED: "spór — czeka na administratora", DONE: "wykonany", FAILED: "niedotrzymany termin", CANCELLED: "odwołany", RESOLVED: "rozstrzygnięty" };

const pad = (n) => String(n).padStart(2, "0");
const dayKey = (t) => { const d = new Date(t); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
/** Kwota w md jak ctx.fmt(): „1234 zł 5 sr 6 md” (bez zer). */
export function fmtMd(md) {
  const n = Math.abs(Math.trunc(md));
  const zl = Math.floor(n / 100), sr = Math.floor((n % 100) / 10), m = n % 10;
  const parts = [zl ? zl + " zł" : "", sr ? sr + " sr" : "", m ? m + " md" : ""].filter(Boolean);
  return (md < 0 ? "-" : "") + (parts.join(" ") || "0 md");
}

export function buildFirmy(W, now = Date.now()) {
  const U = W.U;
  const ghost = (name) => ({ uuid: randomUUID(), name });
  const member = (p, role, shareBp, salary) => ({ uuid: p.uuid, name: p.name, role, roleName: ROLES[role], shareBp, salary });
  const seat = (id) => ({ seat: id, seatName: W.C[id] ? W.C[id].name : id });
  const days = (perDay, n = 30, seed = 1) => {
    const o = {};
    for (let i = 0; i < n; i++) if (perDay && (i * seed) % 7 !== 3) o[dayKey(now - i * DAY)] = Math.round(perDay * (0.6 + ((i * 37 + seed * 11) % 9) / 10));
    return o;
  };
  const KINDS = ["sklep", "pensja", "podatek", "wplata", "kontrakt", "sklep", "magazyn", "dywidenda"];
  const journal = (n, actor) =>
    Array.from({ length: n }, (_, i) => {
      const kind = KINDS[i % KINDS.length];
      const amount = { sklep: 1_250 + i * 10, pensja: -230, podatek: -125, wplata: 5_000, kontrakt: 12_000, magazyn: 0, dywidenda: -3_000 }[kind];
      return { time: now - i * 5 * HOUR, kind, text: { sklep: "Sprzedaż: miecz żelazny ×2", pensja: "Pensje dzienne", podatek: "Podatek do skarbca kraju", wplata: "Wpłata wspólnika", kontrakt: "Zapłata za kontrakt", magazyn: "Nowa skrzynia magazynowa", dywidenda: "Dywidenda dla wspólników" }[kind] + " #" + (n - i), amount, actor: kind === "pensja" || kind === "podatek" ? "" : actor };
    });
  const firm = (o) => ({
    type: "cech", color: "#8a7350", bannerBase: "white", bannerPatterns: [], createdAt: now - 30 * DAY, income7: Math.round((o.income30 || 0) / 4), tax30: Math.round((o.income30 || 0) / 10),
    taxPercent: 10, incomeTotal: (o.income30 || 0) * 3, taxTotal: Math.round(((o.income30 || 0) * 3) / 10), incomeByDay: {}, arrears: {}, unpaidDays: 0, frozen: false, frozenReason: null,
    payouts: [], journal: [], shops: [], warehouse: [], warehouseLog: [], sharedAddresses: [], liquidationReason: null, contractsDone: 0, state: "ACTIVE",
    ...o,
  });
  const mieszko = ghost("Mieszko"), jadwiga = ghost("Jadwiga"), racibor = ghost("Racibor"), kazimierz = ghost("Kazimierz"), wanda = ghost("Wanda"), dobromir = ghost("Dobromir");
  const firms = [
    firm({
      id: "0a1b2c3d4e", name: "Kuźnia Gnieźnieńska", type: "cech", ...seat("c-polanie"), color: "#B02E26", bannerBase: "red", income30: 480_000, income7: 120_000, balance: 125_000, contractsDone: 7,
      members: [member(U.bolko, "mistrz", 6000, 0), member(U.dobrawa, "wspolnik", 4000, 150), member(U.siemowit, "czeladnik", 0, 80)],
      arrears: { [U.siemowit.uuid]: 160 }, unpaidDays: 2, incomeByDay: days(16_000, 30, 1), journal: journal(45, "Bolko"),
      payouts: [{ id: "9f8e7d6c5b", amount: 30_000, createdAt: now - 4 * HOUR, dueAt: now + 20 * HOUR, by: "Bolko" }],
      shops: [
        { type: "SELL", item: "IRON_SWORD", amount: 1, price: 450, stock: 12, world: "world", x: 318, y: 69, z: -142 },
        { type: "BUY", item: "IRON_INGOT", amount: 16, price: 640, stock: 3, world: "world", x: 320, y: 69, z: -142 },
      ],
      warehouse: [{ world: "world", x: 316, y: 68, z: -140, addedAt: now - 20 * DAY, addedBy: "Bolko" }, { world: "world", x: 317, y: 68, z: -140, addedAt: now - 9 * DAY, addedBy: "Dobrawa" }],
      warehouseLog: [
        { time: now - 1 * HOUR, player: "Siemowit", item: "IRON_INGOT", delta: -32, chest: "world 316 68 -140" },
        { time: now - 3 * HOUR, player: "Dobrawa", item: "COAL", delta: 64, chest: "world 317 68 -140" },
        { time: now - 26 * HOUR, player: "Bolko", item: "IRON_SWORD", delta: 8, chest: "world 316 68 -140" },
      ],
      sharedAddresses: [["Dobrawa", "Siemowit"]],
    }),
    firm({ id: "1b2c3d4e5f", name: "Młyn nad Wartą", ...seat("c-polanie"), color: "#F9D71C", bannerBase: "yellow", income30: 120_000, balance: 300_000, contractsDone: 12, members: [member(U.ziemowit, "mistrz", 10000, 0)], incomeByDay: days(4_000, 30, 2), journal: journal(6, "Ziemowit") }),
    firm({
      id: "2c3d4e5f60", name: "Kompania Bursztynowa", type: "kompania", ...seat("c-wislanie"), color: "#F9801D", bannerBase: "orange", income30: 900_000, balance: 60_000, contractsDone: 3,
      members: [member(U.wedrowiec, "mistrz", 5000, 0), member(mieszko, "wspolnik", 5000, 200), member(jadwiga, "czeladnik", 0, 90), member(racibor, "czeladnik", 0, 90)],
      incomeByDay: days(30_000, 30, 3), journal: journal(12, "Wedrowiec"),
    }),
    firm({ id: "3d4e5f6071", name: "Browar Książęcy", type: "manufaktura", ...seat("c-wislanie"), color: "#835432", bannerBase: "brown", state: "DORMANT", income30: 0, balance: 20_000, members: [member(kazimierz, "mistrz", 7000, 0), member(wanda, "wspolnik", 3000, 0)] }),
    firm({
      id: "4e5f607182", name: "Karczma Pod Złotym Kłosem", ...seat("c-polanie"), color: "#5E7C16", bannerBase: "green", state: "INSOLVENT", income30: 30_000, balance: 5_000, contractsDone: 1, members: [member(dobromir, "mistrz", 10000, 120)],
      arrears: { [dobromir.uuid]: 50_000 }, unpaidDays: 9, frozen: true, frozenReason: "audyt: niepotwierdzona operacja „pensja” z 2026-10-27",
    }),
    firm({ id: "5f60718293", name: "Tkalnia Dobrawy", ...seat("c-polanie"), color: "#3C44AA", bannerBase: "blue", state: "LIQUIDATED", income30: 0, balance: 0, members: [], liquidationReason: "niewypłacalna przez 14 dni" }),
  ];
  const contracts = [
    { id: "c0ffee0001", title: "Dostawa 64 mieczy", kind: "DELIVERY", client: "Wiślanie", clientType: "COUNTRY", contractor: "Kuźnia Gnieźnieńska", contractorFirm: "0a1b2c3d4e", status: "DISPUTED", price: 40_000, held: 45_000, bond: 5_000, deadline: now + 3 * DAY, item: "IRON_SWORD", quantity: 64, delivered: 64, log: ["utworzony", "przyjęty", "zgłoszony", "spór"], disputeReason: "Miecze gorszej jakości niż w umowie", disputeAt: now - 5 * HOUR, disputeBy: "Maciej" },
    { id: "c0ffee0002", title: "Podkowy dla jazdy", kind: "DELIVERY", client: "Królestwo Polan", clientType: "COUNTRY", contractor: "Kuźnia Gnieźnieńska", contractorFirm: "0a1b2c3d4e", status: "ACCEPTED", price: 12_000, held: 12_000, bond: 0, deadline: now + 6 * DAY, item: "IRON_INGOT", quantity: 128, delivered: 40, log: ["utworzony", "przyjęty"] },
    { id: "c0ffee0003", title: "Karawana do Krakowa", kind: "SERVICE", client: "Bolko", clientType: "PLAYER", contractor: "Kompania Bursztynowa", contractorFirm: "2c3d4e5f60", status: "ACCEPTED", price: 25_000, held: 27_500, bond: 2_500, deadline: now + 2 * DAY, item: null, quantity: 0, delivered: 0, log: ["utworzony", "przyjęty"] },
    { id: "c0ffee0004", title: "Mąka na zimę", kind: "DELIVERY", client: "Księstwo Mazowsze", clientType: "COUNTRY", contractor: "Młyn nad Wartą", contractorFirm: "1b2c3d4e5f", status: "DONE", price: 8_000, held: 0, bond: 0, deadline: now - 2 * DAY, item: "BREAD", quantity: 256, delivered: 256, log: ["utworzony", "wykonany"] },
  ];
  const loan = (o) => ({ lender: "WORLD", lenderId: "world", lenderName: "Skarb Świata", repaid: 0, held: 0, returnedToReserve: 0, purpose: "", note: "", log: [], createdAt: now - 2 * HOUR, dueAt: 0, ...o });
  const loans = [
    loan({ id: "aa11bb22cc", firm: "0a1b2c3d4e", firmName: "Kuźnia Gnieźnieńska", state: "REQUESTED", principal: 200_000, interestBp: 500, days: 30, purpose: "Rozbudowa warsztatu: druga kuźnia", createdAt: now - 3 * HOUR }),
    loan({ id: "dd33ee44ff", firm: "2c3d4e5f60", firmName: "Kompania Bursztynowa", state: "REQUESTED", principal: 150_000, interestBp: 300, days: 14, purpose: "Karawana bursztynowa do Gdańska", createdAt: now - 1 * HOUR }),
    loan({ id: "1122334455", firm: "1b2c3d4e5f", firmName: "Młyn nad Wartą", state: "ACTIVE", principal: 100_000, interestBp: 600, days: 60, repaid: 40_000, held: 40_000, createdAt: now - 20 * DAY, dueAt: now + 40 * DAY }),
    loan({ id: "6677889900", firm: "0a1b2c3d4e", firmName: "Kuźnia Gnieźnieńska", lender: "PLAYER", lenderId: U.dobrawa.uuid, lenderName: "Dobrawa", state: "ACTIVE", principal: 20_000, interestBp: 500, days: 20, repaid: 5_000, createdAt: now - 6 * DAY, dueAt: now + 14 * DAY }),
  ];
  const applications = [{ id: "ab12cd34ef", name: "Cech Złotników", type: "Cech", country: "Wiślanie", founder: "Dobrawa", held: 50_000, expiresAt: now + 5 * DAY, state: "WAITING" }];
  const intents = [{ id: "fe98dc76ba", firm: "4e5f607182", kind: "pensja", description: "Pensje dzienne — brak potwierdzenia z księgi", createdAt: now - 2 * DAY, legs: ["firma:4e5f607182 → player:Dobromir 120 md"] }];
  return { firms, contracts, loans, applications, intents };
}

export function firmyMethods(W, { err, okd }) {
  const S = () => (W.firmy ||= buildFirmy(W));
  const isOwner = (u) => !!u && u.uuid === W.railwayManager;
  const isMember = (u, f) => !!u && f.members.some((m) => m.uuid === u.uuid);
  const deny = (msg = FIRMY_DENIED) => { throw new Error(msg); };
  const firmOf = (id) => S().firms.find((f) => f.id === String(id ?? "")) || deny("Nie ma takiej firmy.");
  const loanTotal = (l) => l.principal + Math.round((l.principal * l.interestBp) / 10000);
  const limitOf = (f) => ({ limit: Math.max(0, Math.round(f.income30 / 2)), income30: f.income30, capital: f.balance });
  const totalArrears = (f) => Object.values(f.arrears).reduce((s, n) => s + n, 0);

  /** Most Panelu: dozwolone klucze, prymitywy jako tekst; obiekt/tablica = odmowa. */
  function args(params) {
    const a = {};
    for (const k of KEYS) {
      const v = params[k];
      if (v === undefined || v === null) continue;
      if (typeof v === "object") throw new Error("Nieprawidłowy parametr firmy.");
      a[k] = String(v);
    }
    return a;
  }

  const summary = (f, money) => {
    const m = {
      id: f.id, name: f.name, type: f.type, typeName: TYPES[f.type], seat: f.seat, seatName: f.seatName, color: f.color,
      banner: { base: f.bannerBase, patterns: f.bannerPatterns }, members: f.members.length, master: f.members.find((x) => x.role === "mistrz")?.name ?? null,
      income30: f.income30, contracts: f.contractsDone, state: f.state, createdAt: f.createdAt,
    };
    if (money) Object.assign(m, { balance: f.balance, balanceText: fmtMd(f.balance) });
    return m;
  };
  const ranking = (sort) => {
    const key = { majatek: (f) => -f.balance, ludzie: (f) => -f.members.length, kontrakty: (f) => -f.contractsDone }[sort] || ((f) => -f.income30);
    return S().firms.filter((f) => f.state !== "LIQUIDATED").sort((a, b) => key(a) - key(b) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  };
  const contractMap = (c) => ({ id: c.id, title: c.title, kind: c.kind, client: c.client, clientType: c.clientType, contractor: c.contractor, status: c.status, statusName: CONTRACT_STATES[c.status], price: c.price, deposit: c.held, bond: c.bond, deadline: c.deadline, item: c.item, quantity: c.quantity, delivered: c.delivered, log: c.log });
  const loanMap = (l) => ({
    id: l.id, firm: l.firm, firmName: l.firmName, lender: l.lender, lenderName: l.lenderName, principal: l.principal, interest: loanTotal(l) - l.principal, interestPercent: l.interestBp / 100,
    amount: l.principal, days: l.days, due: loanTotal(l), repaid: l.repaid, outstanding: Math.max(0, loanTotal(l) - l.repaid), status: l.state, statusName: LOAN_STATES[l.state],
    createdAt: l.createdAt, requestedAt: l.createdAt, dueAt: l.dueAt, purpose: l.purpose, log: l.log,
  });
  const outcome = (message, extra = {}) => okd({ ok: true, message, ...extra });

  const H = {
    "firmy.list": (u, a) => {
      const sort = a.sort || "obrot";
      const firms = ranking(sort).map((f, i) => ({ ...summary(f, isOwner(u)), rank: i + 1, mine: isMember(u, f) }));
      return okd({ sort, firms, owner: isOwner(u), carrier: { name: "Królewski przewoźnik", note: "Kolej Korony — osobna firma WorldSteward, poza rankingiem" } });
    },
    "firmy.get": (u, a) => {
      const f = firmOf(a.id);
      const member = isMember(u, f), owner = isOwner(u), full = member || owner;
      const m = { ...summary(f, full), access: { member, owner } };
      m.people = f.members.map((x) => (full ? { name: x.name, role: x.role, roleName: x.roleName, uuid: x.uuid, share: x.shareBp / 100, salary: x.salary, arrears: f.arrears[x.uuid] || 0 } : { name: x.name, role: x.role, roleName: x.roleName }));
      if (full)
        Object.assign(m, {
          taxPercent: f.taxPercent, income7: f.income7, tax30: f.tax30, incomeTotal: f.incomeTotal, taxTotal: f.taxTotal, incomeByDay: { ...f.incomeByDay }, arrears: totalArrears(f), unpaidDays: f.unpaidDays,
          frozen: f.frozen, frozenReason: f.frozenReason, payouts: f.payouts.map((p) => ({ ...p })), journal: f.journal.slice(0, 200).map((e) => ({ ...e })), shops: f.shops.map((s) => ({ ...s })),
          warehouse: f.warehouse.map((c) => ({ ...c })), warehouseLog: f.warehouseLog.map((e) => ({ ...e })),
        });
      if (owner) m.sharedAddresses = f.sharedAddresses.map((g) => [...g]);
      return okd(m);
    },
    "firmy.admin": (u) => {
      if (!isOwner(u)) deny();
      const firms = S().firms.map((f) => ({ ...summary(f, true), tax30: f.tax30, arrears: totalArrears(f), frozen: f.frozen, frozenReason: f.frozenReason, sharedAddresses: f.sharedAddresses.map((g) => [...g]), liquidationReason: f.liquidationReason }));
      const total = S().firms.filter((f) => f.state !== "LIQUIDATED").reduce((s, f) => s + f.balance, 0);
      return okd({ firms, applications: S().applications.map((x) => ({ ...x })), uncertain: S().intents.map((x) => ({ ...x, legs: [...x.legs] })), totalBalance: total, totalBalanceText: fmtMd(total) });
    },
    "firmy.admin.freeze": (u, a) => {
      if (!isOwner(u)) deny();
      const f = firmOf(a.id);
      const frozen = String(a.frozen ?? "true").toLowerCase() === "true";
      const reason = a.reason ?? "decyzja właściciela";
      if (reason.length > 200) deny("Za długi powód.");
      if (f.state === "LIQUIDATED") deny("Firma jest zlikwidowana.");
      f.frozen = frozen;
      f.frozenReason = frozen ? "panel: " + reason : null;
      f.journal.unshift({ time: Date.now(), kind: "admin", text: (frozen ? "Zamrożenie: " : "Odmrożenie: ") + "panel: " + reason, amount: 0, actor: "WorldSteward" });
      return outcome(frozen ? `Firma „${f.name}” zamrożona.` : `Firma „${f.name}” odmrożona.`);
    },
    "firmy.contracts": (u, a) => {
      const f = firmOf(a.id);
      const full = isOwner(u) || isMember(u, f);
      return okd({ contracts: S().contracts.filter((c) => c.contractorFirm === f.id).map((c) => { const m = contractMap(c); if (!full) { delete m.deposit; delete m.log; } return m; }) });
    },
    "firmy.disputes": (u) => {
      if (!isOwner(u)) deny();
      return okd({ disputes: S().contracts.filter((c) => c.status === "DISPUTED").map((c) => ({ ...contractMap(c), contract: c.id, amount: c.price, reason: c.disputeReason, openedAt: c.disputeAt, openedBy: c.disputeBy })) });
    },
    "firmy.dispute.resolve": (u, a) => {
      if (!isOwner(u)) deny();
      const note = a.note ?? "";
      if (note.length > 200) deny("Za długa notatka.");
      const c = S().contracts.find((x) => x.id === a.id);
      if (!c || c.status !== "DISPUTED") deny("Ten kontrakt nie jest w sporze.");
      const winner = { client: "na korzyść zleceniodawcy", contractor: "na korzyść wykonawcy", split: "podział" }[a.winner ?? ""];
      if (!winner) deny("Nieprawidłowe rozstrzygnięcie sporu.");
      c.status = "RESOLVED";
      c.held = 0;
      c.log.push("rozstrzygnięty: " + winner + (note ? " — " + note : "") + " (WorldSteward (panel))");
      return outcome(`Spór „${c.title}” rozstrzygnięty: ${winner}.`);
    },
    "firmy.loans": (u, a) => {
      const f = firmOf(a.id);
      if (!(isOwner(u) || isMember(u, f))) deny("Kredyty firmy widzą jej członkowie.");
      const lim = limitOf(f);
      return okd({ loans: S().loans.filter((l) => l.firm === f.id).map(loanMap), limit: lim.limit, limitText: fmtMd(lim.limit) });
    },
    "firmy.world.loans": (u) => {
      if (!isOwner(u)) deny();
      const applications = [], active = [], toReturn = [];
      for (const l of S().loans.filter((x) => x.lender === "WORLD")) {
        const m = loanMap(l);
        const f = S().firms.find((x) => x.id === l.firm);
        if (l.state === "REQUESTED") {
          if (f) Object.assign(m, limitOf(f));
          applications.push(m);
        } else if (l.state === "ACTIVE") active.push(m);
        if (l.held > 0 && l.state !== "REQUESTED") toReturn.push({ id: l.id, firmName: l.firmName, amount: l.held });
      }
      return okd({ applications, active, toReturn });
    },
    "firmy.world.loanDecide": (u, a) => {
      const note = a.note ?? "";
      if (note.length > 200) deny("Za długa notatka.");
      if (!isOwner(u)) deny("Kredyty Skarbu Świata zatwierdza tylko właściciel.");
      const l = S().loans.find((x) => x.id === a.id && x.lender === "WORLD");
      if (!l || l.state !== "REQUESTED") deny("Brak takiego wniosku.");
      l.note = note.trim();
      l.decidedBy = "WorldSteward";
      const f = S().firms.find((x) => x.id === l.firm);
      if (String(a.approve ?? "false").toLowerCase() !== "true") {
        l.state = "REJECTED";
        l.log.push("odrzucony przez właściciela" + (l.note ? ": " + l.note : ""));
        return outcome("Wniosek odrzucony.");
      }
      if (!f) deny("Firma już nie istnieje.");
      if (l.principal > limitOf(f).limit) deny("Warunki nie są już spełnione: kwota przekracza limit kredytowy firmy.");
      l.state = "ACTIVE";
      l.createdAt = Date.now();
      l.dueAt = Date.now() + l.days * DAY;
      f.balance += l.principal;
      f.journal.unshift({ time: Date.now(), kind: "kredyt", text: "Kredyt Skarbu Świata " + l.id, amount: l.principal, actor: "WorldSteward" });
      l.log.push("zatwierdzony przez właściciela");
      return outcome(`Kredyt ${fmtMd(l.principal)} dla „${f.name}” wypłacony.`);
    },
    "firmy.world.loanSweep": (u, a) => {
      if (!isOwner(u)) deny("Tylko właściciel.");
      const l = S().loans.find((x) => x.id === a.id);
      if (!l || l.lender !== "WORLD") deny("To nie jest kredyt Skarbu Świata.");
      if (l.held <= 0 || l.state === "REQUESTED") deny("W depozycie kredytu nie ma spłat do zwrotu.");
      const held = l.held;
      l.returnedToReserve += held;
      l.held = 0;
      l.log.push("zwrot " + fmtMd(held) + " do rezerwy");
      return outcome("Zwrócono " + fmtMd(held) + " do rezerwy Skarbu Świata.");
    },
  };

  const m = {};
  for (const [name, fn] of Object.entries(H))
    m[name] = (u, params) => {
      // Most Panelu (rpc/FirmyMethods): Firmy wyłączone / etap 2 niewdrożony → komunikaty jak na serwerze.
      if (W.firmyAvailable === false) return err("Firmy są obecnie niedostępne.");
      if (W.firmyStage2 === false && STAGE2.has(name)) return err("Nieznana metoda firm: " + name);
      try {
        return fn(u, args(params || {}));
      } catch (e) {
        return err(e.message);
      }
    };
  return m;
}
