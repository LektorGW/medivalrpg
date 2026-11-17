// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Państwo NPC (v2): zakładki Przegląd (kafelki, cel AI, wykresy 7 dni), Stosunki (opinia, nastawienie,
// propozycje, wojna, trybut, wymuszenie relacji), Gospodarka (rozbicie 24 h, budżet AI, mnożnik, korekta, księga)
// oraz Ludność i jedzenie (korekty ±, osadnicy, mnożnik podatku).
import { h, icon, modal, confirmDanger, add } from "../dom.js";
import * as api from "../api.js";
import { formatMoney } from "../money.js";
import { fmtInt, fmtDate, pick, arr, pct } from "../util.js";
import { money, stat, card, bar, empty, field, act, btn, table, countryName, moneyInput } from "../components.js";
import { hasMood, moodStat, moodCard } from "./mood.js";
import { historyCharts, fmtCompact } from "../chart.js";
import { txType, signedMoney } from "./country.js";
import { REL2, REL2_ORDER, STANCES, GOAL_ICONS, opinionColor, opinionText, msg, v2Error, adjuster, settingValue, paramByKey } from "./npc-common.js";

const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);

// ---------------------------------------------------------------- Przegląd

const SERIES = [
  { key: "treasury", label: "Skarbiec", fmt: (v) => formatMoney(v), tick: (v) => fmtCompact(v / 100) + " zł" },
  { key: "population", label: "Ludność", fmt: (v) => fmtInt(v), tick: (v) => fmtCompact(v), int: true },
  { key: "soldiers", label: "Żołnierze", fmt: (v) => fmtInt(v), tick: (v) => fmtCompact(v), int: true },
  { key: "chunks", label: "Chunki", fmt: (v) => fmtInt(v), tick: (v) => fmtCompact(v), int: true },
  { key: "food", label: "Jedzenie", fmt: (v) => fmtInt(v), tick: (v) => fmtCompact(v), int: true },
];

export function overviewTab(body, t) {
  const { c, settings, d } = t;
  const hist = d.history || {};
  const cur = (hist.data && hist.data.current) || {};
  const pop = c.population || {};
  const val = (k, ...alt) => num(cur[k]) ?? alt.map(num).find((x) => x !== null) ?? null;
  const chunks = val("chunks", c.chunks);
  const maxChunks = val("maxChunks", settingValue(settings, paramByKey("max-chunki")));
  const soldiers = val("soldiers", c.soldiers, pick(c.army || {}, "total"));
  const maxSoldiers = val("maxSoldiers", settingValue(settings, paramByKey("max-zolnierze")));
  const population = val("population", pick(pop, "population"), c.populationCount);
  const housing = val("housing", pick(pop, "housing"));
  const food = val("food", pick(pop, "food"));
  const foodCap = val("foodCapacity", pick(pop, "foodCapacity"));
  const treasury = val("treasury", c.treasury ? pick(c.treasury, "balance") : null);
  const of = (a, b) => fmtInt(a) + (b ? " / " + fmtInt(b) : "");

  add(
    body,
    h(
      "div.grid-stats.grid-stats-6",
      stat("Chunki", of(chunks, maxChunks), "map"),
      stat("Żołnierze", of(soldiers, maxSoldiers), "swords"),
      stat("Ludność", of(population, housing), "users", housing ? "mieszkańcy / łóżka" : null),
      stat("Skarbiec", treasury === null ? "—" : money(treasury), "coins"),
      stat("Jedzenie", of(food, foodCap), "house", foodCap ? pct((food || 0) / foodCap) + " spichlerza" : null),
      stat("Poziom osady", cur.tierName || (cur.tier ? "Poziom " + cur.tier : "—"), "crown", cur.tier ? "poziom " + cur.tier : null),
      hasMood(c) ? moodStat(hasMood(c)) : null,
    ),
  );

  const g = cur.goal;
  if (g && (g.text || g.kind)) {
    const target = g.target && typeof g.target === "object" ? g.target : null;
    add(
      body,
      h(
        "section.card.goal-card",
        h("div.goal-ic", icon(GOAL_ICONS[g.kind] || "robot")),
        h(
          "div.goal-body",
          h("div.stat-label", "Aktualny cel AI"),
          h("div.goal-text", g.text || g.kind),
          target ? h("div.goal-target", h("span.muted", "Cel: "), countryName(target)) : null,
          num(g.progress) !== null ? bar(g.progress, "Postęp " + pct(g.progress)) : null,
        ),
      ),
    );
  }

  if (hist.error) add(body, v2Error("Historia — ostatnie 7 dni", hist.error));
  else {
    const hd = hist.data || {};
    add(
      body,
      card(
        "Historia — ostatnie 7 dni",
        h("p.muted.small", "Próbki co godzinę z pluginu Panel. Najedź na wykres (albo użyj strzałek), żeby zobaczyć wszystkie wartości z danej godziny."),
        historyCharts(arr(hd.points), SERIES, { from: hd.from, to: hd.to, empty: "Brak historii — plugin Panel zapisuje stan co godzinę, pierwsze punkty pojawią się wkrótce." }),
      ),
    );
  }
  // nastrój mieszkańców (plugin Zycie) — pod wykresami
  const mood = hasMood(c);
  if (mood) add(body, moodCard(mood, { festival: { id: c.id, reload: t.reload } }));
}

// ---------------------------------------------------------------- Stosunki

export function relationsTab(body, t) {
  const { c, id, ctx, d, reload } = t;
  if (d.rel.error) return add(body, v2Error("Stosunki", d.rel.error));
  const data = d.rel.data || {};
  const warCost = num(data.warCost);
  const tributeMax = num(data.tributeMax);
  const rows = arr(data.countries).slice().sort((a, b) => (REL2_ORDER[a.relation] ?? 9) - (REL2_ORDER[b.relation] ?? 9) || String(a.name).localeCompare(String(b.name), "pl"));

  const opinionCell = (x) => {
    const v = Math.max(-100, Math.min(100, Math.round(Number(x.opinion) || 0)));
    const out = h("output.op-val", opinionText(v));
    const inp = h("input.range.op-range", { type: "range", min: "-100", max: "100", step: "1", value: String(v), "aria-label": "Opinia AI o kraju " + x.name, "data-mut": "1" });
    const paint = () => {
      const n = Number(inp.value);
      out.textContent = opinionText(n);
      wrap.style.setProperty("--op-c", opinionColor(n));
      wrap.style.setProperty("--op-p", ((n + 100) / 2).toFixed(1) + "%");
    };
    inp.addEventListener("input", paint);
    inp.addEventListener("change", () => {
      const n = Number(inp.value);
      if (n === v) return;
      act(inp, () => api.mutate("admin.npc.setOpinion", { id, target: x.id, value: n }), msg(`Opinia o ${x.name}: ${opinionText(n)}`), reload).then((r) => {
        if (r === undefined) ((inp.value = String(v)), paint());
      });
    });
    const wrap = h("div.opinion", inp, out);
    paint();
    return wrap;
  };

  const stanceCell = (x) => {
    const sel = h("select.input.input-sm", { "aria-label": "Nastawienie wobec " + x.name, "data-mut": "1" }, STANCES.map((s) => h("option", { value: s.id, selected: s.id === (x.stance || "AUTO") }, s.label)));
    sel.addEventListener("change", () => {
      const s = sel.value;
      act(sel, () => api.mutate("admin.npc.setStance", { id, target: x.id, stance: s }), msg(`Nastawienie wobec ${x.name}: ${STANCES.find((q) => q.id === s).label}`), reload).then((r) => {
        if (r === undefined) sel.value = x.stance || "AUTO";
      });
    });
    return sel;
  };

  const actions = (x) => {
    const out = [];
    const rel = x.relation;
    if (rel === "WAR") out.push(btn("Proponuj pokój", { small: true, icon: "scroll", title: `${c.name} proponuje pokój` }, (_e, b) => act(b, () => api.mutate("admin.npc.propose", { id, target: x.id, relation: "NEUTRAL" }), msg(`Propozycja pokoju wysłana do: ${x.name}`), reload)));
    if (rel === "NEUTRAL") out.push(btn("Proponuj sojusz", { small: true, icon: "scroll", title: `${c.name} proponuje sojusz` }, (_e, b) => act(b, () => api.mutate("admin.npc.propose", { id, target: x.id, relation: "ALLIANCE" }), msg(`Propozycja sojuszu wysłana do: ${x.name}`), reload)));
    if (rel === "NEUTRAL" || rel === "VASSAL") {
      const blocked = x.stance === "NEVER_WAR";
      const w = btn("Wojna", { small: true, kind: "danger", icon: "swords", title: blocked ? "Nastawienie „nigdy wojny” blokuje wojnę" : "Wypowiedz wojnę" }, async (_e, b) => {
        const ok = await confirmDanger({
          title: "Wypowiedzieć wojnę?",
          text: `${c.name} (NPC) wypowie wojnę państwu ${x.name}.${warCost !== null ? " Koszt ze skarbca NPC: " + formatMoney(warCost) + "." : ""} Decyzja zostanie ogłoszona w grze, a pokoju nie da się zawrzeć od razu.`,
          confirmLabel: "Wypowiadam wojnę",
        });
        if (ok) act(b, () => api.mutate("admin.npc.declareWar", { id, target: x.id }), msg(`Wypowiedziano wojnę: ${x.name}`), reload);
      });
      if (blocked) w.disabled = true;
      out.push(w);
    }
    if (rel !== "SUZERAIN" && rel !== "ALLIANCE") out.push(btn("Trybut", { small: true, icon: "coins", title: "Zażądaj trybutu" }, (_e, b) => tributeModal(x, b)));
    if (ctx.roles.admin) out.push(btn("Wymuś", { small: true, icon: "key", title: "Wymuś relację (admin)" }, (_e, b) => forceModal(x, b)));
    return h("div.row-actions", out);
  };

  async function tributeModal(x, b) {
    const amt = moneyInput({ placeholder: "np. 2 zł" });
    const r = await modal({
      title: "Zażądać trybutu?",
      body: h(
        "div.confirm",
        h("div.confirm-ic", icon("coins")),
        h("div", h("p", `${c.name} zażąda od państwa ${x.name} dziennego trybutu. Kraj NPC odpowie od razu (zależnie od opinii i siły), władca-gracz dostanie żądanie w grze. Odmowa może pogorszyć stosunki.`), field("Trybut dziennie", amt.el), tributeMax !== null ? h("p.muted.small", "Maksymalnie: " + formatMoney(tributeMax)) : null),
      ),
      actions: [
        { label: "Anuluj", value: null },
        {
          label: "Zażądaj trybutu",
          kind: "danger",
          onClick: () => {
            const v = amt.value();
            if (v === null || v <= 0 || (tributeMax !== null && v > tributeMax)) {
              amt.input.classList.add("invalid");
              amt.input.focus();
              return false;
            }
            return v;
          },
        },
      ],
    });
    if (r) act(b, () => api.mutate("admin.npc.demandTribute", { id, target: x.id, amount: r }), msg(`Zażądano trybutu ${formatMoney(r)} dziennie od: ${x.name}`), reload);
  }

  async function forceModal(x, b) {
    const sel = h("select.input", { "aria-label": "Nowa relacja" }, ["ALLIANCE", "NEUTRAL", "WAR"].map((r) => h("option", { value: r, selected: r === x.relation }, REL2[r])));
    const r = await modal({
      title: "Wymusić relację?",
      body: h("div.confirm", h("div.confirm-ic", icon("warn")), h("div", h("p", `Relacja ${c.name} ↔ ${x.name} zmieni się natychmiast, bez kosztów i bez zgody drugiej strony (narzędzie admina).`), field("Relacja", sel))),
      actions: [
        { label: "Anuluj", value: null },
        { label: "Wymuś relację", kind: "danger", onClick: () => sel.value },
      ],
    });
    if (r) act(b, () => api.mutate("admin.relation.set", { a: id, b: x.id, relation: r }), `${c.name} ↔ ${x.name}: ${REL2[r]}`, reload);
  }

  const relCell = (x) => {
    const tags = [];
    const p = x.pending;
    if (p && typeof p === "object") tags.push(h("span.tag.tag-pending", { title: p.at ? fmtDate(p.at) : "" }, p.direction === "IN" ? (p.relation === "ALLIANCE" ? "proponuje sojusz" : "proponuje pokój") : p.relation === "ALLIANCE" ? "czeka: sojusz" : "czeka: pokój"));
    const tr = x.tribute;
    if (tr && typeof tr === "object") tags.push(h("span.tag.tag-tribute", (tr.direction === "PAYS" ? "płacimy " : "płaci nam ") + formatMoney(tr.amount) + "/dzień"));
    return h("div.rel-cell", h("span.rel.rel-" + (REL2[x.relation] ? x.relation : "NEUTRAL"), REL2[x.relation] || REL2.NEUTRAL), tags);
  };

  add(
    body,
    card(
      "Stosunki z innymi państwami",
      h("p.muted.small", "Opinia (-100…100) i nastawienie sterują decyzjami AI: „nigdy wojny” blokuje wojnę, „preferowany cel” zwiększa wagę ataku. Zmiana suwaka zapisuje się po puszczeniu."),
      table({
        columns: [
          { key: "name", label: "Kraj", render: (x) => h("div.rel-country", countryName(x), h("span.muted.small", `${fmtInt(x.soldiers)} żołnierzy · ${fmtInt(x.chunks)} chunków`)) },
          { key: "relation", label: "Relacja", render: relCell },
          { key: "opinion", label: "Opinia AI", render: opinionCell, cls: "col-opinion" },
          { key: "stance", label: "Nastawienie", render: stanceCell },
          { key: "act", label: "Akcje", render: actions, cls: "col-actions" },
        ],
        rows,
        emptyText: "Nie ma innych państw",
        cls: "rel-table",
      }),
    ),
  );
}

// ---------------------------------------------------------------- Gospodarka

const FLOW_LABELS = { npcTax: "Podatki od ludności", aiIncome: "Dochód AI", tribute: "Trybuty", other: "Inne", wages: "Żołd", land: "Utrzymanie ziemi", buildings: "Budowy", recruit: "Rekrutacja", war: "Wojny i oblężenia" };
const BUDGET = [
  { key: "army", label: "Wojsko", help: "rekrutacja i żołd" },
  { key: "buildings", label: "Budowy", help: "planer osady" },
  { key: "reserve", label: "Rezerwa", help: "oszczędności, cele AI" },
];

/** Zmiana jednego udziału koryguje pozostałe proporcjonalnie, tak by suma zawsze wynosiła 100. */
export function rebalance(vals, key, v) {
  v = Math.max(0, Math.min(100, Math.round(v)));
  const others = BUDGET.map((b) => b.key).filter((k) => k !== key);
  const rest = 100 - v;
  const a = vals[others[0]];
  const b = vals[others[1]];
  let na;
  if (a + b <= 0) na = Math.floor(rest / 2);
  else na = Math.round((rest * a) / (a + b));
  return { ...vals, [key]: v, [others[0]]: na, [others[1]]: rest - na };
}

function flows(eco) {
  const inc = arr(eco.income).filter((x) => Number(x.amount) > 0);
  const exp = arr(eco.expenses).filter((x) => Number(x.amount) > 0);
  const max = Math.max(1, ...inc.map((x) => x.amount), ...exp.map((x) => x.amount));
  const it = num(eco.incomeTotal) ?? inc.reduce((s, x) => s + x.amount, 0);
  const et = num(eco.expensesTotal) ?? exp.reduce((s, x) => s + x.amount, 0);
  const row = (x, kind, total) => {
    const fill = h("div.flow-fill");
    fill.style.setProperty("width", ((x.amount / max) * 100).toFixed(1) + "%");
    const label = x.label || FLOW_LABELS[x.key] || x.key;
    return h("li.flow-row.flow-" + kind, { title: `${label}: ${formatMoney(x.amount)} (${total ? Math.round((x.amount / total) * 100) : 0}% ${kind === "in" ? "przychodów" : "wydatków"})` }, h("span.flow-label", label), h("div.flow-track", fill), h("span.flow-amt", (kind === "in" ? "+" : "−") + formatMoney(x.amount)));
  };
  return h(
    "div.flows",
    h("h4.flow-head", "Przychody", h("span.pos-amt", "+" + formatMoney(it))),
    inc.length ? h("ul.flow-list", inc.map((x) => row(x, "in", it))) : h("p.muted.small", "Brak przychodów w ostatniej dobie."),
    h("h4.flow-head", "Wydatki", h("span.neg", "−" + formatMoney(et))),
    exp.length ? h("ul.flow-list", exp.map((x) => row(x, "out", et))) : h("p.muted.small", "Brak wydatków w ostatniej dobie."),
  );
}

function budgetEditor(t, eco) {
  const { id, reload } = t;
  const start = eco.budget && typeof eco.budget === "object" ? { army: Number(eco.budget.army) || 0, buildings: Number(eco.budget.buildings) || 0, reserve: Number(eco.budget.reserve) || 0 } : { army: 34, buildings: 33, reserve: 33 };
  let vals = { ...start };
  const segs = {};
  const outs = {};
  const inputs = {};
  const sum = h("span.budget-sum");
  const stackBar = h("div.budget-bar", { "aria-hidden": "true" }, BUDGET.map((b) => (segs[b.key] = h("div.budget-seg.budget-" + b.key, { title: b.label }))));
  const paint = () => {
    for (const b of BUDGET) {
      segs[b.key].style.setProperty("flex-grow", String(vals[b.key]));
      segs[b.key].classList.toggle("zero", vals[b.key] === 0);
      outs[b.key].textContent = vals[b.key] + "%";
      inputs[b.key].value = String(vals[b.key]);
    }
    sum.textContent = "Suma: " + (vals.army + vals.buildings + vals.reserve) + "%";
  };
  const rows = BUDGET.map((b) => {
    outs[b.key] = h("output.range-val");
    inputs[b.key] = h("input.range", { type: "range", min: "0", max: "100", step: "1", value: String(vals[b.key]), "aria-label": "Budżet: " + b.label });
    inputs[b.key].addEventListener("input", () => {
      vals = rebalance(vals, b.key, Number(inputs[b.key].value));
      paint();
    });
    return h("div.slider-row", h("div.slider-head", h("span.field-label", h("span.budget-key.budget-" + b.key), b.label), outs[b.key]), inputs[b.key], h("span.field-help", b.help));
  });
  paint();
  const save = btn("Zapisz podział", { kind: "primary" }, (_e, bt) => {
    if (vals.army === start.army && vals.buildings === start.buildings && vals.reserve === start.reserve) return;
    act(bt, () => api.mutate("admin.npc.setBudget", { id, ...vals }), msg(`Budżet AI: wojsko ${vals.army}%, budowy ${vals.buildings}%, rezerwa ${vals.reserve}%`), reload);
  });
  return card("Podział budżetu AI", h("p.muted.small", "Jak AI dzieli dochód. Zmiana jednego suwaka proporcjonalnie koryguje pozostałe — suma zawsze wynosi 100%."), stackBar, h("div.sliders", rows), h("div.form-actions.form-actions-split", sum, save));
}

export function economyTab(body, t) {
  const { id, settings, reload, d } = t;
  if (d.eco.error) return add(body, v2Error("Gospodarka", d.eco.error));
  const eco = d.eco.data || {};
  const it = num(eco.incomeTotal) ?? arr(eco.income).reduce((s, x) => s + (Number(x.amount) || 0), 0);
  const et = num(eco.expensesTotal) ?? arr(eco.expenses).reduce((s, x) => s + (Number(x.amount) || 0), 0);
  const net = num(eco.net) ?? it - et;

  // mnożnik dochodu = bogactwo (admin.npc.set)
  const w0 = num(eco.wealth) ?? settingValue(settings, paramByKey("bogactwo")) ?? 50;
  const wOut = h("output.range-val", String(w0));
  const wIn = h("input.range", { type: "range", min: "0", max: "100", step: "1", value: String(w0), "aria-label": "Bogactwo (mnożnik dochodu)" });
  wIn.addEventListener("input", () => (wOut.textContent = wIn.value));
  const wSave = btn("Zapisz mnożnik", { kind: "primary" }, (_e, b) => {
    const v = Number(wIn.value);
    if (v === w0) return;
    act(b, () => api.mutate("admin.npc.set", { id, param: "bogactwo", value: v }), `Bogactwo (mnożnik dochodu): ${v}`, reload);
  });

  // korekta skarbca
  const adj = moneyInput({ signed: true, placeholder: "np. +50 zł albo -2 zł 5 sr" });
  const adjBtn = btn("Zastosuj korektę", { kind: "primary", type: "submit", icon: "coins" });
  const adjForm = h(
    "form.inline-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          const v = adj.value();
          if (v === null || v === 0) {
            adj.input.classList.add("invalid");
            adj.input.focus();
            return;
          }
          adj.input.classList.remove("invalid");
          act(adjBtn, () => api.mutate("admin.npc.treasury", { id, delta: v }), `Skarbiec: ${v > 0 ? "+" : ""}${formatMoney(v)}`, () => (adj.reset(), reload()));
        },
      },
    },
    field("Kwota ±", adj.el),
    adjBtn,
  );

  const log = arr(eco.log);
  add(
    body,
    h(
      "div.grid-stats",
      stat("Skarbiec", money(eco.balance), "coins"),
      stat("Przychody (24 h)", h("span.pos-amt", "+" + formatMoney(it)), "bolt"),
      stat("Wydatki (24 h)", h("span.neg", "−" + formatMoney(et)), "swords"),
      stat("Bilans doby", h("span" + (net < 0 ? ".neg" : ".pos-amt"), (net > 0 ? "+" : "") + formatMoney(net)), "scroll"),
    ),
    h(
      "div.grid-cards",
      h("div.stack", card("Przychody i wydatki — ostatnia doba", flows(eco)), card("Korekta skarbca", h("p.muted.small", "Dodaj albo odejmij pieniądze bez kosztu dla graczy (zapis w audycie). Użyj „-” dla odjęcia."), adjForm)),
      h(
        "div.stack",
        budgetEditor(t, eco),
        card(
          "Mnożnik dochodu",
          h("div.slider-row", h("div.slider-head", h("span.field-label", "Bogactwo"), wOut), wIn, h("span.field-help", "Im wyżej, tym większy dochód AI i większy nacisk na oszczędzanie." + (num(eco.incomeMultiplier) !== null ? ` Teraz: ×${String(eco.incomeMultiplier).replace(".", ",")}.` : ""))),
          h("div.form-actions", wSave),
        ),
      ),
    ),
    card(
      "Księga skarbca",
      table({
        columns: [
          { key: "time", label: "Data", render: (e) => fmtDate(pick(e, "time", "at", "ts")) },
          { key: "type", label: "Operacja", render: (e) => txType(pick(e, "type", "kind")) },
          { key: "note", label: "Opis", render: (e) => pick(e, "note", "reason", "description") || "" },
          { key: "who", label: "Kto", render: (e) => pick(e, "actor", "player", "by") || "—" },
          { key: "amount", label: "Kwota", num: true, render: (e) => signedMoney(e) },
        ],
        rows: log,
        emptyText: "Księga jest pusta",
      }),
    ),
  );
}

// ---------------------------------------------------------------- Ludność i jedzenie

export function populationTab(body, t) {
  const { c, id, reload, d } = t;
  const p = c.population;
  if (!p) return add(body, card("Ludność i jedzenie", empty("Brak danych o ludności — plugin Ludnosc jest niedostępny.", "house")));
  const pop = Number(pick(p, "population")) || 0;
  const housing = num(pick(p, "housing"));
  const food = Number(pick(p, "food")) || 0;
  const cap = num(pick(p, "foodCapacity"));
  const bal = num(pick(p, "foodBalance"));
  const hungry = Number(pick(p, "hungryDays")) || 0;
  const homeless = Number(pick(p, "homelessDays")) || 0;
  const tax = num(pick(p, "dailyTax"));

  const foodAdj = adjuster({
    label: "Jedzenie",
    unit: "",
    quick: [-100, 100, 500],
    max: 100000,
    apply: (delta, b) => act(b, () => api.mutate("admin.npc.food", { id, delta }), (r) => (r && typeof r.food === "number" ? `Jedzenie: ${delta > 0 ? "+" : "−"}${fmtInt(Math.abs(r.applied ?? delta))} (stan ${fmtInt(r.food)})` : `Jedzenie: ${delta > 0 ? "+" : "−"}${fmtInt(Math.abs(delta))}`), reload),
  });
  const setAdj = adjuster({
    label: "Osadnicy",
    unit: "",
    quick: [-5, 5, 10],
    max: 500,
    apply: (delta, b) => act(b, () => api.mutate("admin.npc.settlers", { id, delta }), (r) => (r && typeof r.population === "number" ? `Osadnicy: ${delta > 0 ? "+" : "−"}${fmtInt(Math.abs(r.applied ?? delta))} (ludność ${fmtInt(r.population)})` : `Osadnicy: ${delta > 0 ? "+" : "−"}${fmtInt(Math.abs(delta))}`), reload),
  });

  let taxCard;
  if (d.eco && d.eco.error) taxCard = v2Error("Podatek od obywatela", d.eco.error);
  else {
    const m0 = num(d.eco && d.eco.data && d.eco.data.taxMultiplier) ?? 100;
    const out = h("output.range-val", m0 + "%");
    const inp = h("input.range", { type: "range", min: "0", max: "300", step: "5", value: String(m0), "aria-label": "Mnożnik podatku od obywatela" });
    const est = h("span.field-help");
    const upd = () => {
      out.textContent = inp.value + "%";
      est.textContent = tax !== null && m0 > 0 ? `Szacunkowo ${formatMoney(Math.round((tax * Number(inp.value)) / m0))} dziennie (teraz ${formatMoney(tax)}). Wyższy podatek spowalnia wzrost ludności.` : "100% = podatek normalny. Wyższy podatek spowalnia wzrost ludności.";
    };
    inp.addEventListener("input", upd);
    upd();
    const save = btn("Zapisz mnożnik", { kind: "primary" }, (_e, b) => {
      const v = Number(inp.value);
      if (v === m0) return;
      act(b, () => api.mutate("admin.npc.setTaxMultiplier", { id, percent: v }), msg(`Podatek od obywatela: ${v}%`), reload);
    });
    taxCard = card("Podatek od obywatela", h("div.slider-row", h("div.slider-head", h("span.field-label", "Mnożnik podatku"), out), inp, est), h("div.form-actions", save));
  }

  add(
    body,
    h(
      "div.grid-stats",
      h("div.stat.stat-wide", h("div.stat-ic", icon("users")), h("div.stat-body", h("div.stat-label", "Ludność / mieszkania"), h("div.stat-value", `${fmtInt(pop)} / ${fmtInt(housing)}`), housing ? bar(pop / housing, pop >= housing ? (pop > housing ? `${pop - housing} bez dachu nad głową` : "Brak wolnych łóżek") : `${housing - pop} wolnych łóżek`, pop >= housing ? "warn" : "ok") : h("div.stat-sub", "Bez mieszkań ludność nie rośnie."))),
      h("div.stat.stat-wide", h("div.stat-ic", icon("house")), h("div.stat-body", h("div.stat-label", "Spichlerz"), h("div.stat-value", fmtInt(food) + (cap ? " / " + fmtInt(cap) : "")), cap ? bar(food / cap, pct(food / cap) + (bal !== null ? ` · bilans ${bal > 0 ? "+" : ""}${fmtInt(bal)} dziennie` : ""), food < cap * 0.15 ? "war" : food < cap * 0.4 ? "warn" : "ok") : null)),
      stat("Podatek dzienny", tax === null ? "—" : money(tax), "coins", "od ludności"),
      stat("Głód / bezdomność", h("span" + (hungry > 0 || homeless > 0 ? ".neg" : ""), `${fmtInt(hungry)} / ${fmtInt(homeless)}`), "warn", "dni z rzędu"),
    ),
    hungry > 0 || homeless > 0 ? h("div.alert.alert-warn", icon("warn"), h("span", [hungry > 0 ? `Ludność głoduje od ${hungry} dni` : null, homeless > 0 ? `brakuje mieszkań od ${homeless} dni` : null].filter(Boolean).join(" · ") + " — osadnicy mogą odchodzić, a podatki maleją.")) : null,
    h(
      "div.grid-cards",
      card("Jedzenie", h("p.muted.small", `Korekta zapasów spichlerza${cap ? " (od 0 do " + fmtInt(cap) + ")" : ""}. Ujemna wartość zabiera jedzenie.`), foodAdj),
      card("Osadnicy", h("p.muted.small", "Dodaj albo usuń mieszkańców. Osadnicy ponad liczbę łóżek będą bezdomni."), setAdj),
      taxCard,
    ),
  );
}
