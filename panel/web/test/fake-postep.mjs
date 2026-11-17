// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Fałszywy plugin — Postep (epoki i odkrycia, PANEL.md „Postęp — epoki i odkrycia”): drzewko jak w drzewko.yml,
// punkty badań, cel badań ze ścieżką, inwestycje z malejącymi przychodami, metody admina.

const H = 3600 * 1000;
const DAY = 24 * H;

export const ERAS = [
  { id: 1, name: "Średniowiecze", years: "V–XV w.", color: "#B08D57", icon: "⚔", description: "Grody, zamki, cechy i klasztory.", rate: 4, cap: 20, minPop: 0, minDays: 21 },
  { id: 2, name: "Renesans", years: "XV–XVII w.", color: "#C9A227", icon: "⚜", description: "Druk, proch, wielkie odkrycia geograficzne i bankierzy.", rate: 5, cap: 35, minPop: 25, minDays: 21 },
  { id: 3, name: "Epoka przemysłowa", years: "XIX w.", color: "#8E9AAF", icon: "⚙", description: "Para, stal, kolej i elektryczność.", rate: 6, cap: 55, minPop: 60, minDays: 21 },
  { id: 4, name: "Nowoczesność", years: "2026", color: "#3FA7D6", icon: "⚡", description: "Beton, samochody, lotnictwo, komputery i internet.", rate: 7, cap: 80, minPop: 100, minDays: 0 },
];

const D = (id, name, era, cost, requires, req, category, description, effect) => ({ id, name, era, cost, requires, requiredForEra: req, category, description, effect });

export const TREE = [
  D("kolo_wodne", "Koło wodne", 1, 80, [], true, "gospodarka", "Młyny wodne mielą zboże i napędzają miechy kuźni.", "+5% jedzenia, +3% tempa budowy"),
  D("plug", "Pług ciężki", 1, 80, [], true, "gospodarka", "Żelazny lemiesz i zaprzęg wołów orzą ciężkie gleby.", "+10% jedzenia, +5% wzrostu ludności"),
  D("kusza", "Kusza", 1, 120, [], false, "wojsko", "Bełt przebija kolczugę z kilkudziesięciu kroków.", "+5% siły armii; Armia: kusznicy"),
  D("cechy", "Cechy rzemieślnicze", 1, 120, ["plug"], false, "gospodarka", "Mistrzowie, czeladnicy i terminatorzy.", "+5% podatków, +5% tempa budowy"),
  D("zegar", "Zegar mechaniczny", 1, 140, ["kolo_wodne"], true, "nauka", "Zegar na wieży ratusza odmierza godziny.", "+5% punktów badań"),
  D("gotyk", "Architektura gotycka", 1, 160, ["kolo_wodne"], true, "budownictwo", "Łuki ostre, przypory i witraże.", "+10% tempa budowy; Ludnosc: katedra"),
  D("uniwersytety", "Uniwersytety", 1, 220, ["zegar", "gotyk"], true, "nauka", "Studium generale kształci prawników i astronomów.", "+10% punktów badań"),
  D("druk", "Druk", 2, 300, ["uniwersytety"], true, "nauka", "Ruchoma czcionka Gutenberga.", "+10% punktów badań; Ludnosc: drukarnia"),
  D("proch", "Proch strzelniczy", 2, 280, [], true, "wojsko", "Saletra, siarka i węgiel drzewny.", "+15% siły armii; Armia: muszkieterowie"),
  D("kartografia", "Kartografia", 2, 260, ["zegar"], false, "dyplomacja", "Dokładne mapy wybrzeży i szlaków.", "+3% podatków"),
  D("nawigacja_oceaniczna", "Nawigacja oceaniczna", 2, 320, ["kartografia"], true, "dyplomacja", "Karawele, astrolabium i kompas.", "+5% podatków, +5% jedzenia; Ludnosc: port"),
  D("bankowosc", "Bankowość", 2, 300, ["cechy"], true, "gospodarka", "Weksle, kredyt i księgowość podwójna.", "+10% podatków"),
  D("astronomia", "Astronomia", 2, 340, ["druk", "zegar"], true, "nauka", "Ziemia krąży wokół Słońca.", "+5% punktów badań"),
  D("manufaktury", "Manufaktury", 2, 300, ["bankowosc"], false, "budownictwo", "Podział pracy w wielkich warsztatach.", "+10% tempa budowy, +5% podatków"),
  D("maszyna_parowa", "Maszyna parowa", 3, 450, ["manufaktury"], true, "budownictwo", "Para Watta napędza pompy i przędzalnie.", "+15% tempa budowy"),
  D("stal", "Stal", 3, 450, ["proch"], true, "wojsko", "Konwertor Bessemera.", "+10% siły armii"),
  D("kolej", "Kolej żelazna", 3, 550, ["maszyna_parowa", "stal"], true, "gospodarka", "Parowozy łączą miasta i kopalnie.", "+10% podatków; Ludnosc: dworzec"),
  D("elektrycznosc", "Elektryczność", 3, 600, ["astronomia"], true, "nauka", "Prądnica, żarówka i sieć miejska.", "+10% punktów badań"),
  D("telegraf", "Telegraf", 3, 500, ["elektrycznosc"], true, "dyplomacja", "Alfabet Morse'a.", "+5% punktów badań"),
  D("szczepionki", "Szczepionki", 3, 450, ["druk"], false, "nauka", "Jenner i Pasteur.", "+15% wzrostu ludności"),
  D("beton_zbrojony", "Beton zbrojony", 4, 700, ["stal"], false, "budownictwo", "Stalowe pręty w betonie.", "+20% tempa budowy; wieżowce"),
  D("samochod", "Samochód", 4, 800, ["kolej"], false, "gospodarka", "Silnik spalinowy i taśma produkcyjna.", "+10% podatków; drogi asfaltowe"),
  D("lotnictwo", "Lotnictwo", 4, 900, ["samochod"], false, "wojsko", "Od braci Wright do odrzutowców.", "+20% siły armii"),
  D("radio", "Radio", 4, 700, ["telegraf"], false, "dyplomacja", "Fale radiowe w każdym domu.", "+5% punktów badań"),
  D("komputery", "Komputery", 4, 1000, ["radio", "elektrycznosc"], false, "nauka", "Tranzystor i mikroprocesor.", "+15% punktów badań"),
  D("internet", "Internet", 4, 1200, ["komputery"], false, "dyplomacja", "Sieć łączy cały świat.", "+15% punktów badań, +10% podatków"),
];

const byId = new Map(TREE.map((d) => [d.id, d]));

/** Stan postępu krajów świata testowego. */
export function addPostep(W, now) {
  W.postepAvailable = true;
  const st = (era, days, points, total, daily, discoveries, target, investedToday = 0) => ({ era, eraSince: now - days * DAY, points, total, daily, discoveries: new Set(discoveries), target, investedToday });
  W.postep = {
    "c-polanie": st(2, 9.5, 240, 1420, 46.5, ["kolo_wodne", "plug", "zegar", "gotyk", "uniwersytety", "cechy", "kusza", "druk", "kartografia"], "astronomia", 500),
    "c-wislanie": st(1, 12, 90, 410, 22, ["kolo_wodne", "plug", "zegar"], null),
    "npc-mazowsze": st(2, 3, 130, 1210, 38, ["kolo_wodne", "plug", "zegar", "gotyk", "uniwersytety", "kusza", "cechy", "proch"], "bankowosc"),
    "npc-rus": st(1, 20, 40, 300, 12.5, ["kolo_wodne", "plug"], "zegar"),
  };
}

function ensure(W, id) {
  if (!W.postep[id]) W.postep[id] = { era: 1, eraSince: Date.now(), points: 0, total: 0, daily: 5, discoveries: new Set(), target: null, investedToday: 0 };
  return W.postep[id];
}

const status = (d, s) => (s.discoveries.has(d.id) ? "OWNED" : d.era > s.era ? "ERA_LOCKED" : d.requires.every((r) => s.discoveries.has(r)) ? "AVAILABLE" : "LOCKED");

function path(target, owned) {
  const out = [];
  const visit = (id, seen = new Set()) => {
    const d = byId.get(id);
    if (!d || owned.has(id) || out.includes(d) || seen.has(id)) return;
    seen.add(id);
    d.requires.forEach((r) => visit(r, seen));
    out.push(d);
  };
  visit(target);
  return out;
}

const total = (inv, rate, cap) => (inv <= 0 ? 0 : cap * (1 - Math.exp((-inv * (rate / 100)) / cap)));
const gain = (s, amount) => {
  const e = ERAS[s.era - 1];
  return total(s.investedToday + amount, e.rate, e.cap) - total(s.investedToday, e.rate, e.cap);
};
const r1 = (v) => Math.round(v * 10) / 10;
const fmtMoney = (md) => {
  const z = Math.floor(md / 100);
  const sr = Math.floor((md % 100) / 10);
  const m = md % 10;
  return [z ? z + " zł" : "", sr ? sr + " sr" : "", m ? m + " md" : ""].filter(Boolean).join(" ") || "0 md";
};

function check(s, c) {
  if (s.era >= ERAS.length) return { progress: 1, missing: [], ready: false };
  const req = TREE.filter((d) => d.era === s.era && d.requiredForEra);
  const missing = req.filter((d) => !s.discoveries.has(d.id)).map((d) => "odkrycie: " + d.name);
  const need = ERAS[s.era - 1].minDays * DAY;
  const left = Math.max(0, s.eraSince + need - Date.now());
  if (left > 0) missing.push("czas w epoce: jeszcze " + Math.ceil(left / DAY) + " dni");
  const next = ERAS[s.era];
  const pop = c.population ? c.population.population + Object.keys(c.members).length * 5 : 0;
  if (pop < next.minPop) missing.push(`ludność ≥ ${next.minPop} (jest ${pop})`);
  const totalCost = req.reduce((a, d) => a + d.cost, 0);
  const owned = req.filter((d) => s.discoveries.has(d.id)).reduce((a, d) => a + d.cost, 0);
  const dPart = totalCost ? Math.min(1, (owned + Math.min(s.points, totalCost - owned)) / totalCost) : 1;
  const tPart = need ? Math.min(1, (Date.now() - s.eraSince) / need) : 1;
  const pPart = next.minPop ? Math.min(1, pop / next.minPop) : 1;
  const ready = missing.length === 0;
  return { progress: ready ? 1 : Math.min(0.99, Math.round((0.6 * dPart + 0.25 * tPart + 0.15 * pPart) * 1000) / 1000), missing, ready };
}

const eraRef = (n) => {
  const e = ERAS[n - 1];
  return { id: e.id, name: e.name, years: e.years, color: e.color, icon: e.icon, description: e.description };
};

/** Pola epoki dodawane do wpisów `overview`. */
export function overviewEra(W, c) {
  if (!W.postepAvailable) return { era: null, eraProgress: null };
  const s = ensure(W, c.id);
  return { era: eraRef(s.era), eraProgress: check(s, c).progress };
}

export function postepMethods(W, { canNpc, err, okd }) {
  const W8 = { RULER: 3, ADVISOR: 2, CITIZEN: 1 };
  const canAct = (u, c) => !!u.admin || W8[c.members[u.uuid]] >= 2 || (c.npc && canNpc(u, c.id));
  const get = (u, id) => {
    const c = W.C[id];
    if (!c) throw new Error("Nie ma takiego kraju.");
    if (!W.postepAvailable) return { c, s: null };
    return { c, s: ensure(W, id) };
  };
  const need = (u, c) => {
    if (!canAct(u, c)) throw new Error("Brak uprawnień");
  };
  const unlockTarget = (s) => {
    for (let guard = 0; guard < 30 && s.target; guard++) {
      const p = path(s.target, s.discoveries);
      const next = p[0];
      if (!next) {
        s.target = null;
        break;
      }
      if (s.points < next.cost) break;
      s.points -= next.cost;
      s.discoveries.add(next.id);
      if (next.id === s.target) s.target = null;
    }
  };
  const ranking = () =>
    Object.values(W.C)
      .map((c) => ({ c, s: ensure(W, c.id) }))
      .map(({ c, s }) => ({ c, s, k: check(s, c) }))
      .sort((a, b) => b.s.era - a.s.era || b.k.progress - a.k.progress || b.s.total - a.s.total || a.c.name.localeCompare(b.c.name, "pl"))
      .map(({ c, s, k }, i) => ({ pos: i + 1, id: c.id, name: c.name, color: c.color, npc: c.npc, era: s.era, eraName: ERAS[s.era - 1].name, eraIcon: ERAS[s.era - 1].icon, eraColor: ERAS[s.era - 1].color, progress: k.progress, discoveries: s.discoveries.size, totalPoints: s.total }));

  return {
    "postep.get": (u, { id }) => {
      const { c, s } = get(u, id);
      if (!s) return okd({ id, available: false });
      const k = check(s, c);
      let target = null;
      if (s.target) {
        const p = path(s.target, s.discoveries);
        const cost = p.reduce((a, d) => a + d.cost, 0);
        const missing = Math.ceil(Math.max(0, cost - s.points));
        target = { id: s.target, name: byId.get(s.target).name, cost, missing, etaDays: missing === 0 ? 0 : Math.ceil(missing / s.daily), path: p.map((d) => ({ id: d.id, name: d.name, cost: d.cost })) };
      }
      const act = canAct(u, c);
      const out = {
        id,
        available: true,
        era: eraRef(s.era),
        nextEra: s.era < ERAS.length ? eraRef(s.era + 1) : null,
        maxEra: s.era >= ERAS.length,
        eraSince: s.eraSince,
        progress: k.progress,
        ready: k.ready,
        missing: k.missing,
        points: Math.floor(s.points),
        totalPoints: Math.floor(s.total),
        daily: s.daily,
        discoveryCount: s.discoveries.size,
        discoveryTotal: TREE.length,
        target,
        eras: ERAS.map((e) => eraRef(e.id)),
        tree: TREE.map((d) => {
          const stt = status(d, s);
          return { ...d, status: stt, affordable: stt === "AVAILABLE" && s.points >= d.cost };
        }),
      };
      if (act) {
        out.invest = {
          investedToday: s.investedToday,
          investedTodayText: fmtMoney(s.investedToday),
          treasury: c.treasury,
          treasuryText: fmtMoney(c.treasury),
          presets: [100, 500, 1000, 5000].map((a) => ({ amount: a, amountText: fmtMoney(a), points: r1(gain(s, a)) })),
        };
      }
      out.access = { discover: act, invest: act, admin: !!u.admin };
      out.ranking = ranking();
      return okd(out);
    },
    "postep.investPreview": (u, p) => {
      const { c, s } = get(u, p.id);
      need(u, c);
      if (!s) return err("Plugin Postep nie jest dostępny.");
      if (!(p.amount > 0)) return err("Nieprawidłowy parametr amount");
      return okd({ amount: p.amount, amountText: fmtMoney(p.amount), points: r1(gain(s, p.amount)) });
    },
    "postep.discover": (u, p) => {
      const { c, s } = get(u, p.id);
      need(u, c);
      if (!s) return err("Plugin Postep nie jest dostępny.");
      const d = byId.get(p.discovery);
      if (!d) return err("Nie ma odkrycia: " + p.discovery + ".");
      if (s.discoveries.has(d.id)) return err("Kraj zna już: " + d.name + ".");
      if (d.era > s.era) return err(d.name + " będzie dostępne dopiero w epoce " + ERAS[d.era - 1].name + ".");
      const p0 = path(d.id, s.discoveries);
      if (p0.length === 1 && s.points >= d.cost) {
        s.points -= d.cost;
        s.discoveries.add(d.id);
        if (s.target === d.id) s.target = null;
        return okd({ ok: true, status: "UNLOCKED", discovery: d.id, missingPoints: 0, message: "Odkryto: " + d.name + ".", points: Math.floor(s.points) });
      }
      s.target = d.id;
      unlockTarget(s);
      if (s.discoveries.has(d.id)) return okd({ ok: true, status: "UNLOCKED", discovery: d.id, missingPoints: 0, message: "Odkryto: " + d.name + ".", points: Math.floor(s.points) });
      const missing = Math.ceil(Math.max(0, path(d.id, s.discoveries).reduce((a, x) => a + x.cost, 0) - s.points));
      return okd({ ok: true, status: "TARGET_SET", discovery: d.id, missingPoints: missing, message: `Cel badań: ${d.name} (brakuje ${missing} pkt).`, points: Math.floor(s.points) });
    },
    "postep.invest": (u, p) => {
      const { c, s } = get(u, p.id);
      need(u, c);
      if (!s) return err("Plugin Postep nie jest dostępny.");
      if (!(p.amount >= 10)) return err("Inwestycja nie powiodła się: minimalna kwota to 1 sr");
      const g = gain(s, p.amount);
      if (g < 0.05) return err("Dzisiejszy sufit punktów z inwestycji jest wyczerpany — spróbuj po dziennym naliczeniu");
      if (c.treasury < p.amount) return err("Za mało pieniędzy w skarbcu (jest " + fmtMoney(c.treasury) + ")");
      c.treasury -= p.amount;
      s.investedToday += p.amount;
      s.points += g;
      s.total += g;
      unlockTarget(s);
      return okd({ ok: true, paid: p.amount, paidText: fmtMoney(p.amount), points: r1(g), researchPoints: Math.floor(s.points), investedToday: s.investedToday, treasury: c.treasury, message: `Zainwestowano ${fmtMoney(p.amount)}: +${r1(g)} pkt badań.` });
    },
    "admin.postep.setEra": (u, p) => {
      if (!u.admin) return err("Brak uprawnień");
      const { s } = get(u, p.id);
      if (!s) return err("Plugin Postep nie jest dostępny.");
      if (!(p.era >= 1 && p.era <= ERAS.length)) return err("Nieprawidłowy parametr era: zakres 1–4");
      for (const d of TREE) if (d.era < p.era && d.requiredForEra) path(d.id, s.discoveries).forEach((x) => s.discoveries.add(x.id));
      s.era = p.era;
      s.eraSince = Date.now();
      return okd({ ok: true, era: p.era, eraName: ERAS[p.era - 1].name, message: "Ustawiono epokę: " + ERAS[p.era - 1].name + "." });
    },
    "admin.postep.points": (u, p) => {
      if (!u.admin) return err("Brak uprawnień");
      const { s } = get(u, p.id);
      if (!s) return err("Plugin Postep nie jest dostępny.");
      if (!Number.isInteger(p.delta) || p.delta === 0) return err("Nieprawidłowy parametr delta: liczba różna od zera.");
      s.points = Math.max(0, s.points + p.delta);
      if (p.delta > 0) s.total += p.delta;
      unlockTarget(s);
      return okd({ ok: true, points: Math.floor(s.points) });
    },
    "admin.postep.grant": (u, p) => {
      if (!u.admin) return err("Brak uprawnień");
      const { s } = get(u, p.id);
      if (!s) return err("Plugin Postep nie jest dostępny.");
      if (!byId.has(p.discovery)) return err("Nie ma odkrycia: " + p.discovery + ".");
      const g = path(p.discovery, s.discoveries);
      if (!g.length) return err("Kraj zna już to odkrycie.");
      g.forEach((d) => s.discoveries.add(d.id));
      if (s.target && s.discoveries.has(s.target)) s.target = null;
      return okd({ ok: true, granted: g.map((d) => d.id), message: "Nadano: " + g.map((d) => d.name).join(", ") + "." });
    },
  };
}
