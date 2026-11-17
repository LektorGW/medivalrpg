// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Administracja (tylko roles.admin): wszystkie kraje, gracze, dziennik panelu, wydajność serwera.
import { h, icon, modal, confirmDanger, skeleton, add } from "../dom.js";
import * as api from "../api.js";
import { formatMoney } from "../money.js";
import { RANKS, RELATIONS, normRank, fmtInt, fmtDate, fmtAgo, pick, arr, safeColor } from "../util.js";
import { money, card, field, act, btn, table, countryName, tabs, moneyInput } from "../components.js";
import { normalizeHealth, rangeOf } from "../health-model.js";
import { healthTab } from "./health.js";

const TABS = [
  { id: "kraje", label: "Wszystkie kraje", icon: "shield" },
  { id: "gracze", label: "Gracze", icon: "users" },
  { id: "dziennik", label: "Dziennik panelu", icon: "scroll" },
  { id: "serwer", label: "Wydajność serwera", icon: "gauge" },
];

export const adminView = {
  title: (d) => (d && d.tab === "serwer" ? "Wydajność serwera" : "Administracja"),
  skeleton: () => h("div.card", skeleton(8)),
  async load(ctx) {
    if (!ctx.roles.admin) return { denied: true };
    const tab = TABS.some((t) => t.id === ctx.params[0]) ? ctx.params[0] : "kraje";
    if (tab === "gracze") return { tab, players: arr((await api.read("admin.players", {})).data) };
    if (tab === "dziennik") return { tab, audit: arr((await api.read("admin.audit", { limit: 200 })).data) };
    if (tab === "serwer") {
      const r = await api.read("admin.health", { range: rangeOf(ctx.query.get("zakres")).id });
      return { tab, health: normalizeHealth(r.data), stale: r.stale };
    }
    return { tab };
  },
  draw(main, d, ctx) {
    if (d.denied) return add(main, card("Brak dostępu", h("p", "Ta sekcja jest dostępna tylko dla administratorów serwera.")));
    add(main, 
      h("div.page-head", h("div", h("h1", "Administracja"), h("p.muted", "Każda operacja trafia do dziennika panelu na serwerze.")), h("a.btn", { href: "#/npc" }, icon("robot"), h("span", "Państwa NPC"))),
      tabs(TABS.map((t) => ({ ...t, href: "#/admin/" + t.id })), d.tab),
    );
    const body = h("div.tab-body");
    ({ kraje: countriesTab, gracze: playersTab, dziennik: auditTab, serwer: healthTab })[d.tab](body, d, ctx);
    add(main, body);
  },
};

// ---------------------------------------------------------------- kraje

function countriesTab(body, d, ctx) {
  const list = ctx.overview.countries.map((c) => ({ ...c, color: safeColor(c.color) }));
  const reload = ctx.reload;

  const setRuler = async (c, b) => {
    const p = h("input.input", { type: "text", maxlength: "32", placeholder: "nick gracza" });
    const ok = await modal({ title: "Nowy władca: " + c.name, body: field("Gracz", p, "Gracz zostanie władcą (dołączy do kraju, jeśli trzeba)."), actions: [{ label: "Anuluj", value: null }, { label: "Ustaw władcę", kind: "primary", onClick: () => (p.value.trim() ? p.value.trim() : (p.classList.add("invalid"), false)) }] });
    if (ok) act(b, () => api.mutate("admin.country.setRuler", { id: c.id, player: ok }), `${ok} jest władcą państwa ${c.name}`, reload);
  };
  const adjust = async (c, b) => {
    const amt = moneyInput({ signed: true, placeholder: "np. +5 zł albo -2 sr" });
    const v = await modal({ title: "Korekta skarbca: " + c.name, body: field("Kwota (+ dodaje, - zabiera)", amt.el), actions: [{ label: "Anuluj", value: null }, { label: "Zastosuj", kind: "primary", onClick: () => { const x = amt.value(); if (x === null || x === 0) { amt.input.classList.add("invalid"); return false; } return { x }; } }] });
    if (v) act(b, () => api.mutate("admin.treasury.adjust", { id: c.id, amount: v.x }), `Skarbiec ${c.name}: ${v.x > 0 ? "+" : ""}${formatMoney(v.x)}`, reload);
  };
  const del = async (c, b) => {
    if (await confirmDanger({ title: "Usunąć państwo?", text: `Państwo ${c.name} zostanie rozwiązane: ziemie, skarbiec i relacje przepadną. Tego nie da się cofnąć.`, confirmLabel: "Usuń państwo", requireText: c.name })) {
      act(b, () => api.mutate("admin.country.delete", { id: c.id }), `Usunięto państwo ${c.name}`, reload);
    }
  };

  add(body, 
    card(
      `Wszystkie kraje (${list.length})`,
      table({
        columns: [
          { key: "name", label: "Kraj", render: (c) => countryName(c), sort: (c) => c.name },
          { key: "ruler", label: "Władca", render: (c) => c.ruler || "—" },
          { key: "members", label: "Członkowie", num: true, render: (c) => fmtInt(c.members) },
          { key: "chunks", label: "Chunki", num: true, render: (c) => fmtInt(c.chunks) },
          { key: "act", label: "Akcje", sort: false, cls: "col-actions", render: (c) => h("div.row-actions", btn("Władca", { small: true, icon: "crown" }, (_e, b) => setRuler(c, b)), btn("Skarbiec ±", { small: true, icon: "coins" }, (_e, b) => adjust(c, b)), btn("Usuń", { small: true, kind: "danger" }, (_e, b) => del(c, b))) },
        ],
        rows: list,
        sort: { key: "name", dir: "asc" },
        emptyText: "Brak państw",
      }),
    ),
  );

  // Relacja
  const opts = () => list.slice().sort((a, b) => a.name.localeCompare(b.name, "pl")).map((c) => h("option", { value: c.id }, c.name + (c.npc ? " (NPC)" : "")));
  const a = h("select.input", { "aria-label": "Kraj A" }, opts());
  const b2 = h("select.input", { "aria-label": "Kraj B" }, opts());
  if (b2.options.length > 1) b2.selectedIndex = 1;
  const rel = h("select.input", { "aria-label": "Relacja" }, Object.entries(RELATIONS).map(([k, v]) => h("option", { value: k }, v)));
  const submit = btn("Ustaw relację", { kind: "primary", type: "submit" });
  add(body, 
    card(
      "Ustaw relację (bez kosztów i cooldownów)",
      h(
        "form.inline-form",
        { on: { submit: (e) => { e.preventDefault(); if (a.value === b2.value) return b2.classList.add("invalid"); const an = a.selectedOptions[0].textContent; const bn = b2.selectedOptions[0].textContent; act(submit, () => api.mutate("admin.relation.set", { a: a.value, b: b2.value, relation: rel.value }), `${an} ↔ ${bn}: ${RELATIONS[rel.value]}`, reload); } } },
        field("Kraj A", a),
        field("Kraj B", b2),
        field("Relacja", rel),
        submit,
      ),
    ),
  );
}

// ---------------------------------------------------------------- gracze

function playersTab(body, d, ctx) {
  const byId = new Map(ctx.overview.countries.map((c) => [c.id, { ...c, color: safeColor(c.color) }]));
  const byName = new Map(ctx.overview.countries.map((c) => [String(c.name).toLowerCase(), { ...c, color: safeColor(c.color) }]));
  const countryOf = (p) => {
    const v = p.country;
    if (!v) return null;
    if (typeof v === "object") return byId.get(v.id) || { ...v, color: safeColor(v.color) };
    return byId.get(v) || byName.get(String(v).toLowerCase()) || { name: String(v), color: "#8a7350" };
  };
  const players = d.players.map((p) => ({ ...p, c: countryOf(p), rankN: normRank(p.rank) }));

  const search = h("input.input.search", { type: "search", placeholder: "Szukaj gracza po nicku lub kraju…", "aria-label": "Szukaj gracza", autocomplete: "off" });
  const holder = h("div");
  const adjust = async (p, b) => {
    const amt = moneyInput({ signed: true, placeholder: "np. +1 zł albo -5 sr" });
    const v = await modal({ title: "Korekta salda: " + p.name, body: h("div", h("p.muted", "Obecne saldo: ", money(p.balance)), field("Kwota (+ dodaje, - zabiera)", amt.el)), actions: [{ label: "Anuluj", value: null }, { label: "Zastosuj", kind: "primary", onClick: () => { const x = amt.value(); if (x === null || x === 0) { amt.input.classList.add("invalid"); return false; } return { x }; } }] });
    if (v) act(b, () => api.mutate("admin.economy.adjust", { player: p.name, amount: v.x }), `Saldo ${p.name}: ${v.x > 0 ? "+" : ""}${formatMoney(v.x)}`, ctx.reload);
  };
  const draw = () => {
    const q = search.value.trim().toLowerCase();
    const rows = q ? players.filter((p) => String(p.name).toLowerCase().includes(q) || (p.c && String(p.c.name).toLowerCase().includes(q))) : players;
    holder.replaceChildren(
      table({
        columns: [
          { key: "name", label: "Gracz", render: (p) => h("span.member", h("span.dot" + (p.online ? ".dot-on" : ".dot-off")), h("span", p.name)), sort: (p) => String(p.name) },
          { key: "country", label: "Kraj", render: (p) => (p.c ? countryName(p.c, { link: !!p.c.id }) : h("span.muted", "bez kraju")), sort: (p) => (p.c ? p.c.name : "") },
          { key: "rank", label: "Ranga", render: (p) => (p.rankN ? RANKS[p.rankN] : "—"), sort: (p) => p.rankN || "" },
          { key: "balance", label: "Saldo", num: true, render: (p) => money(p.balance), sort: (p) => Number(p.balance) || 0 },
          { key: "lastSeen", label: "Ostatnio", render: (p) => (p.online ? "w grze" : fmtAgo(p.lastSeen)), sort: (p) => (p.online ? Infinity : Number(new Date(p.lastSeen)) || 0) },
          { key: "act", label: "Akcje", sort: false, cls: "col-actions", render: (p) => btn("Saldo ±", { small: true, icon: "coins" }, (_e, b) => adjust(p, b)) },
        ],
        rows,
        sort: { key: "lastSeen", dir: "desc" },
        emptyText: q ? "Nikt nie pasuje do wyszukiwania" : "Brak graczy",
      }),
    );
  };
  search.addEventListener("input", draw);
  draw();
  add(body, card(`Gracze (${players.length})`, h("div.search-row", icon("search"), search), holder));
}

// ---------------------------------------------------------------- dziennik

function auditTab(body, d) {
  const rows = d.audit.map((e) => (typeof e === "string" ? { text: e } : e));
  const detail = (e) => {
    if (e.text) return e.text;
    const p = pick(e, "params", "details", "detail", "data");
    if (p === undefined) return "";
    const s = typeof p === "string" ? p : JSON.stringify(p);
    return h("code.audit-params", s.length > 160 ? s.slice(0, 160) + "…" : s);
  };
  add(body, 
    card(
      "Dziennik działań panelu (ostatnie 200)",
      table({
        columns: [
          { key: "time", label: "Czas", render: (e) => fmtDate(pick(e, "time", "at", "ts", "timestamp", "date")) },
          { key: "who", label: "Kto", render: (e) => pick(e, "user", "player", "who", "name", "actor") || "—" },
          { key: "ip", label: "IP", render: (e) => (e.ip ? h("code", e.ip) : "—") },
          { key: "action", label: "Akcja", render: (e) => (pick(e, "method", "action") ? h("code", pick(e, "method", "action")) : "—") },
          { key: "details", label: "Szczegóły", render: detail },
          { key: "ok", label: "Wynik", render: (e) => (e.ok === false || e.error ? h("span.neg", e.error || "błąd") : e.ok === true ? h("span.pos-amt", "OK") : pick(e, "result") || "—") },
        ],
        rows,
        emptyText: "Dziennik jest pusty",
      }),
    ),
  );
}
