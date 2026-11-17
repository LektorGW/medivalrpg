// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — widok kraju: Przegląd, Członkowie, Skarbiec, Dyplomacja, Armia, Ludność, Ustawienia.
// Przyciski pokazujemy zgodnie z rangą, ale plugin i tak sprawdza uprawnienia — błąd z serwera trafia do toastu.
import { h, icon, modal, confirmDanger, skeleton, add } from "../dom.js";
import * as api from "../api.js";
import { formatMoney } from "../money.js";
import { RANKS, RELATIONS, RANK_WEIGHT, rankAtLeast, normCountry, relationMap, fmtInt, fmtDate, fmtAgo, pick, arr, fraction, pct, safeColor, normRank } from "../util.js";
import { money, stat, card, bar, empty, tabs, moneyInput, field, act, btn, table, countryName, shield } from "../components.js";
import { hasMood, moodStat, moodCard } from "./mood.js";
import { loadPostep, postepTab, eraBadge } from "./postep.js";
import { loadPlanData, planTab } from "./npc-plan.js";
import { planAccess } from "../plan/access.js";
import { canSeeEconomy, hrefOf as economyHref } from "../economy-model.js";

const TABS = [
  { id: "przeglad", label: "Przegląd", icon: "eye" },
  { id: "postep", label: "Postęp", icon: "flask" },
  { id: "czlonkowie", label: "Członkowie", icon: "users" },
  { id: "skarbiec", label: "Skarbiec", icon: "coins" },
  { id: "dyplomacja", label: "Dyplomacja", icon: "scroll" },
  { id: "armia", label: "Armia", icon: "swords" },
  { id: "ludnosc", label: "Ludność", icon: "house" },
  { id: "plan", label: "Plan", icon: "map" },
  { id: "ustawienia", label: "Ustawienia", icon: "gear" },
];

const SWATCHES = ["#b02e26", "#f9801d", "#fed83d", "#80c71f", "#5e7c16", "#169c9c", "#3ab3da", "#3c44aa", "#8932b8", "#c74ebd", "#f38baa", "#835432", "#474f52", "#9d9d97", "#f9fffe", "#1d1d21"];

export const countryView = {
  title: (d) => (d && d.c ? d.c.name : "Kraj"),
  skeleton: () => h("div", h("div.card", skeleton(3)), h("div.grid-cards", h("div.card", skeleton(5)), h("div.card", skeleton(5)))),
  async load(ctx) {
    const [id, tab] = ctx.params;
    const [r, postep] = await Promise.all([api.read("country.get", { id }), tab === "postep" ? loadPostep(id) : null]);
    const ov = ctx.overview.countries.find((x) => x.id === id);
    const c = normCountry(r.data, ov);
    if (!c.id) c.id = id;
    const me = ctx.me || {};
    const myRank = c.myRank || (me.country && me.country.id === c.id ? normRank(me.country.rank) : null);
    const permissions = planAccess(c, myRank, ctx.roles);
    const plan = tab === "plan" && permissions.read ? await loadPlanData(id, { country: true }) : null;
    return { c, myRank, permissions, plan, stale: r.stale, postep, era: ov && ov.era };
  },
  draw(main, d, ctx) {
    const { c, myRank } = d;
    const tab = ctx.params[1] || "przeglad";
    const visible = TABS.filter((t) => tabVisible(t.id, c, myRank, ctx));
    const active = visible.some((t) => t.id === tab) ? tab : "przeglad";
    const base = "#/kraj/" + encodeURIComponent(c.id);

    const header = h(
      "section.country-head",
      h("div.ch-crest", shield(c.color, "xl")),
      h(
        "div.ch-main",
        h("h1", c.name, c.npc ? h("span.tag.tag-npc", "NPC") : null, d.era ? h("a.era-link", { href: base + "/postep", title: "Postęp kraju" }, eraBadge(d.era)) : null),
        h("p.muted", c.ruler ? ["Władca: ", h("strong", c.ruler)] : "Bez władcy", myRank ? [" · Twoja ranga: ", h("strong", RANKS[myRank])] : null),
      ),
      canSeeEconomy(ctx.me, c, myRank) ? h("a.btn.btn-sm.eco-link", { href: economyHref(c.id) }, icon("wheat"), h("span", "Gospodarka")) : null,
      c.npc && ctx.roles.canNpcCountry(c.id) ? h("a.btn.btn-sm", { href: "#/npc/" + encodeURIComponent(c.id) }, icon("robot"), h("span", "Zarządzaj AI")) : null,
    );
    header.style.setProperty("--c", c.color);

    const body = h("div.tab-body");
    const draw = { plan: (b) => planTab(b, d.plan, { id:c.id, c, ctx, reload:ctx.reload, countryPlan:true, permissions:d.permissions }), przeglad: overviewTab, postep: (b) => postepTab(b, d.postep, { id: c.id, name: c.name, ctx, reload: ctx.reload }), czlonkowie: membersTab, skarbiec: treasuryTab, dyplomacja: diplomacyTab, armia: armyTab, ludnosc: populationTab, ustawienia: settingsTab }[active];
    draw(body, c, myRank, ctx);
    add(main, header, tabs(visible.map((t) => ({ ...t, href: base + (t.id === "przeglad" ? "" : "/" + t.id) })), active), body);
  },
};

function tabVisible(id, c, myRank, ctx) {
  switch (id) {
    case "plan":
      return planAccess(c, myRank, ctx.roles).read;
    case "czlonkowie":
      return !!c.members;
    case "skarbiec":
      return !!c.treasury || !!myRank;
    case "ustawienia":
      return myRank === "RULER";
    default:
      return true;
  }
}

// ---------------------------------------------------------------- Przegląd

function overviewTab(body, c, myRank, ctx) {
  const rel = relationMap(c.raw, ctx.overview);
  const byId = new Map(ctx.overview.countries.map((x) => [x.id, { ...x, color: safeColor(x.color) }]));
  const allies = [...rel].filter(([, r]) => r === "ALLIANCE").map(([id]) => byId.get(id)).filter(Boolean);
  const wars = [...rel].filter(([, r]) => r === "WAR").map(([id]) => byId.get(id)).filter(Boolean);
  const popInfo = c.population || {};
  const pop = pick(popInfo, "population", "count", "citizens") ?? c.populationCount;
  const cap = c.capital;

  add(body, 
    h(
      "div.grid-stats",
      stat("Członkowie", fmtInt(c.memberCount), "users"),
      stat("Ludność", fmtInt(typeof pop === "number" ? pop : null), "house"),
      stat("Żołnierze", fmtInt(armyTotal(c)), "swords"),
      stat("Ziemie", fmtInt(c.chunks) + " chunków", "map", c.areaBlocks ? fmtInt(c.areaBlocks) + " bloków²" : null),
      hasMood(c) ? moodStat(hasMood(c)) : null,
    ),
    h(
      "div.grid-cards",
      card(
        "Kronika",
        c.description ? h("p.description", c.description) : h("p.muted", "Ten kraj nie ma jeszcze opisu."),
        h(
          "dl.facts",
          h("dt", "Podatek dzienny"),
          h("dd", c.tax !== undefined ? money(c.tax) : "—"),
          h("dt", "Stolica"),
          h("dd", cap ? `${cap.world || "świat"} · x ${fmtInt(cap.blockX ?? cap.x)}, z ${fmtInt(cap.blockZ ?? cap.z)}` : "—"),
          c.created ? [h("dt", "Założony"), h("dd", fmtDate(c.created))] : null,
        ),
      ),
      card(
        "Stosunki",
        h("div.rel-group", h("h4", "Sojusznicy"), allies.length ? h("div.chips", allies.map((a) => h("span.chip.chip-ally", countryName(a)))) : h("p.muted", "Brak sojuszy")),
        h("div.rel-group", h("h4", "Wojny"), wars.length ? h("div.chips", wars.map((a) => h("span.chip.chip-war", countryName(a)))) : h("p.muted", "Kraj żyje w pokoju")),
      ),
      hasMood(c) ? moodCard(hasMood(c)) : null,
    ),
    c.sieges.length ? card("Oblężenia", siegeList(c, byId)) : null,
  );
}

function armyTotal(c) {
  if (typeof c.soldiers === "number") return c.soldiers;
  const types = armyTypes(c);
  return types.length ? types.reduce((s, t) => s + (t.count || 0), 0) : null;
}

export function siegeList(c, byId) {
  return h(
    "ul.siege-list",
    c.sieges.map((s) => {
      const side = (v, alt) => (v && typeof v === "object" ? byId.get(v.id) || v : byId.get(v) || { name: alt || v || "?" });
      const att = side(s.attacker, s.attackerName);
      const def = side(s.defender, s.defenderName);
      const f = fraction(s);
      const where = s.cx !== undefined ? `chunk ${s.cx}, ${s.cz}` : s.chunk ? `chunk ${s.chunk.cx ?? s.chunk.x}, ${s.chunk.cz ?? s.chunk.z}` : "";
      return h("li", h("div.siege-title", icon("swords"), h("strong", att.name), h("span", " oblega "), h("strong", def.name), where ? h("span.muted", " · " + where) : null), bar(f, "Postęp " + pct(f), "war"), s.endsAt ? h("span.muted.small", "Koniec najpóźniej: " + fmtDate(s.endsAt)) : null);
    }),
  );
}

// ---------------------------------------------------------------- Członkowie

function membersTab(body, c, myRank, ctx) {
  const me = ctx.me || {};
  const myW = RANK_WEIGHT[myRank] || 0;
  const members = c.members.slice().sort((a, b) => (RANK_WEIGHT[b.rank] || 0) - (RANK_WEIGHT[a.rank] || 0) || a.name.localeCompare(b.name, "pl"));
  const isMe = (m) => (m.uuid && me.uuid ? m.uuid === me.uuid : m.name.toLowerCase() === String(me.name || "").toLowerCase());

  const actions = (m) => {
    const out = [];
    if (myRank === "RULER" && m.rank !== "RULER" && !isMe(m)) {
      const sel = h("select.input.input-sm", { "aria-label": "Ranga gracza " + m.name, "data-mut": "1" }, h("option", { value: "CITIZEN", selected: m.rank === "CITIZEN" }, "Obywatel"), h("option", { value: "ADVISOR", selected: m.rank === "ADVISOR" }, "Doradca"));
      sel.addEventListener("change", () => {
        const rank = sel.value;
        act(sel, () => api.mutate("member.setRank", { id: c.id, player: m.name, rank }), `${m.name} ma teraz rangę: ${RANKS[rank]}`, ctx.reload).then((r) => {
          if (r === undefined) sel.value = m.rank;
        });
      });
      out.push(sel);
    }
    if (rankAtLeast(myRank, "ADVISOR") && m.rank !== "RULER" && !isMe(m) && (RANK_WEIGHT[m.rank] || 0) <= myW) {
      out.push(
        btn("Wyrzuć", { kind: "danger", small: true }, async (_e, b) => {
          if (!(await confirmDanger({ title: "Wyrzucić gracza?", text: `${m.name} zostanie usunięty z państwa ${c.name}.`, confirmLabel: "Wyrzuć" }))) return;
          act(b, () => api.mutate("member.kick", { id: c.id, player: m.name }), `Wyrzucono gracza ${m.name}`, ctx.reload);
        }),
      );
    }
    return out.length ? h("div.row-actions", out) : null;
  };

  const t = table({
    columns: [
      { key: "name", label: "Gracz", render: (m) => h("span.member", h("span.dot" + (m.online ? ".dot-on" : ".dot-off"), { title: m.online ? "Online" : "Offline" }), h("span", m.name), isMe(m) ? h("span.tag.tag-mine", "Ty") : null) },
      { key: "rank", label: "Ranga", render: (m) => h("span.rank.rank-" + m.rank, RANKS[m.rank]) },
      { key: "online", label: "Status", render: (m) => (m.online ? "w grze" : m.lastSeen ? fmtAgo(m.lastSeen) : "offline") },
      { key: "taxDebt", label: "Dług podatkowy", num: true, render: (m) => (m.taxDebt > 0 ? h("span.neg", formatMoney(m.taxDebt), m.debtDays ? h("span.muted.small", ` (${m.debtDays} dni)`) : null) : h("span.muted", "brak")) },
      { key: "act", label: "Akcje", render: actions, cls: "col-actions" },
    ],
    rows: members,
    emptyText: "Brak członków",
  });

  const blocks = [card(`Członkowie (${members.length})`, t)];
  if (rankAtLeast(myRank, "ADVISOR")) {
    const name = h("input.input", { type: "text", placeholder: "Nick gracza", maxlength: "32", autocomplete: "off", required: true });
    const submit = btn("Wyślij zaproszenie", { kind: "primary", type: "submit", icon: "scroll" });
    blocks.push(
      card(
        "Zaproś do państwa",
        h(
          "form.inline-form",
          {
            on: {
              submit: (e) => {
                e.preventDefault();
                const n = name.value.trim();
                if (!n) return name.focus();
                act(submit, () => api.mutate("member.invite", { id: c.id, name: n }), `Zaproszenie dla ${n} wysłane`, () => ((name.value = ""), ctx.reload()));
              },
            },
          },
          field("Nick", name),
          submit,
        ),
        h("p.muted.small", "Gracz musi być teraz w grze — dostanie zaproszenie na czacie i przyjmie je komendą, tak jak zaproszenie wysłane w grze."),
      ),
    );
  }
  add(body, ...blocks);
}

// ---------------------------------------------------------------- Skarbiec

function treasuryTab(body, c, myRank, ctx) {
  const tr = c.treasury;
  const me = ctx.me || {};
  const blocks = [];
  if (tr) {
    blocks.push(h("section.card.treasury-hero", h("div.balance-ic", icon("coins")), h("div", h("div.stat-label", "Stan skarbca"), h("div.balance-value", money(pick(tr, "balance", "amount"))))));
  } else {
    blocks.push(card(null, h("p.muted", "Stan i historię skarbca widzą tylko doradcy i władca. Możesz jednak wpłacić datek na rzecz państwa.")));
  }

  const forms = [];
  if (myRank) {
    const amt = moneyInput({ placeholder: "np. 2 zł 5 sr" });
    const submit = btn("Wpłać", { kind: "primary", type: "submit", icon: "coins" });
    forms.push(
      card(
        "Wpłata do skarbca",
        h("p.muted.small", "Z Twojego konta bankowego (saldo: ", money(me.balance), ")."),
        h("form.inline-form", { on: { submit: (e) => (e.preventDefault(), moneyAction(amt, submit, "treasury.deposit", c, ctx, "Wpłacono")) } }, field("Kwota", amt.el), submit),
      ),
    );
  }
  if (myRank === "RULER") {
    const amt = moneyInput({ placeholder: "np. 10 zł" });
    const submit = btn("Wypłać", { type: "submit", icon: "coins" });
    forms.push(card("Wypłata ze skarbca", h("p.muted.small", "Na konto bankowe władcy."), h("form.inline-form", { on: { submit: (e) => (e.preventDefault(), moneyAction(amt, submit, "treasury.withdraw", c, ctx, "Wypłacono")) } }, field("Kwota", amt.el), submit)));
  }
  if (forms.length) blocks.push(h("div.grid-cards", forms));

  if (tr) {
    const log = arr(pick(tr, "log", "history", "entries"));
    blocks.push(
      card(
        "Księga skarbca",
        table({
          columns: [
            { key: "time", label: "Data", render: (e) => fmtDate(pick(e, "time", "at", "ts", "date", "timestamp")) },
            { key: "who", label: "Kto", render: (e) => pick(e, "player", "by", "who", "actor", "name") || "—" },
            { key: "type", label: "Operacja", render: (e) => txType(pick(e, "type", "kind", "action")) },
            { key: "amount", label: "Kwota", num: true, render: (e) => signedMoney(e) },
            log.some((e) => pick(e, "note", "reason", "description", "desc", "comment")) ? { key: "note", label: "Opis", render: (e) => pick(e, "note", "reason", "description", "desc", "comment") || "" } : null,
          ].filter(Boolean),
          rows: log,
          emptyText: "Księga jest pusta",
        }),
      ),
    );
  }
  add(body, ...blocks);
}

const TX = { DEPOSIT: "Wpłata", WITHDRAW: "Wypłata", TAX: "Podatek", UPKEEP: "Utrzymanie", WAGES: "Żołd", WAGE: "Żołd", RECRUIT: "Rekrutacja", BUILD: "Budowa", CLAIM: "Zajęcie ziemi", WAR: "Wojna", ADMIN: "Korekta admina", INCOME: "Dochód", NPC_INCOME: "Dochód", SIEGE: "Oblężenie", CREATE: "Założenie kraju", REFUND: "Zwrot" };
export function txType(t) {
  if (!t) return "—";
  return TX[String(t).toUpperCase()] || String(t);
}
/** Kwota z księgi: dodatnia = wpływ, ujemna = wydatek (TreasuryLogEntry). */
export function signedMoney(e) {
  const a = Number(pick(e, "amount", "value", "delta"));
  if (!Number.isFinite(a)) return "—";
  return h("span" + (a < 0 ? ".neg" : ".pos-amt"), (a > 0 ? "+" : "") + formatMoney(a));
}

function moneyAction(amt, submit, method, c, ctx, verb) {
  const v = amt.value();
  if (v === null || v <= 0) {
    amt.input.classList.add("invalid");
    amt.input.focus();
    return;
  }
  amt.input.classList.remove("invalid");
  act(submit, () => api.mutate(method, { id: c.id, amount: v }), `${verb} ${formatMoney(v)}`, () => (amt.reset(), ctx.reload()));
}

// ---------------------------------------------------------------- Dyplomacja

function diplomacyTab(body, c, myRank, ctx) {
  const rel = relationMap(c.raw, ctx.overview);
  const others = ctx.overview.countries.filter((x) => x.id !== c.id).map((x) => ({ ...x, color: safeColor(x.color), relation: rel.get(x.id) || "NEUTRAL" }));
  const ruler = myRank === "RULER";
  const order = { WAR: 0, ALLIANCE: 1, NEUTRAL: 2 };

  const actions = (x) => {
    if (!ruler) return null;
    const out = [];
    if (x.relation === "NEUTRAL") {
      out.push(btn("Zaproponuj sojusz", { small: true, icon: "scroll" }, (_e, b) => act(b, () => api.mutate("relation.propose", { id: c.id, target: x.id, relation: "ALLIANCE" }), `Wysłano propozycję sojuszu do: ${x.name}`, ctx.reload)));
      out.push(
        btn("Wypowiedz wojnę", { small: true, kind: "danger", icon: "swords" }, async (_e, b) => {
          const ok = await confirmDanger({
            title: "Wypowiedzieć wojnę?",
            text: `Państwo ${c.name} wypowie wojnę państwu ${x.name}. Wypowiedzenie kosztuje (wg zasad serwera), a pokoju nie można zawrzeć od razu. Ta decyzja zostanie ogłoszona w grze.`,
            confirmLabel: "Wypowiadam wojnę",
          });
          if (ok) act(b, () => api.mutate("relation.declareWar", { id: c.id, target: x.id }), `Wypowiedziano wojnę: ${x.name}`, ctx.reload);
        }),
      );
    } else if (x.relation === "WAR") {
      out.push(btn("Zaproponuj pokój", { small: true, icon: "scroll" }, (_e, b) => act(b, () => api.mutate("relation.propose", { id: c.id, target: x.id, relation: "NEUTRAL" }), `Wysłano propozycję pokoju do: ${x.name}`, ctx.reload)));
    } else if (x.relation === "ALLIANCE") {
      out.push(
        btn("Zerwij sojusz", { small: true, kind: "danger" }, async (_e, b) => {
          const ok = await confirmDanger({ title: "Zerwać sojusz?", text: `Sojusz z państwem ${x.name} zostanie zerwany.`, confirmLabel: "Zerwij sojusz" });
          if (ok) act(b, () => api.mutate("relation.breakAlliance", { id: c.id, target: x.id }), `Zerwano sojusz z: ${x.name}`, ctx.reload);
        }),
      );
    }
    return h("div.row-actions", out);
  };

  const columns = [
    { key: "name", label: "Kraj", render: (x) => countryName(x), sort: (x) => x.name },
    { key: "relation", label: "Relacja", render: (x) => h("span.rel.rel-" + x.relation, RELATIONS[x.relation]), sort: (x) => order[x.relation] },
    { key: "ruler", label: "Władca", render: (x) => x.ruler || "—" },
    { key: "soldiers", label: "Żołnierze", num: true, render: (x) => fmtInt(x.soldiers) },
    { key: "chunks", label: "Chunki", num: true, render: (x) => fmtInt(x.chunks) },
  ];
  if (ruler) columns.push({ key: "act", label: "Akcje", render: actions, sort: false, cls: "col-actions" });
  others.sort((a, b) => order[a.relation] - order[b.relation] || a.name.localeCompare(b.name, "pl"));
  add(body, card("Stosunki z innymi państwami", ruler ? null : h("p.muted.small", "Decyzje dyplomatyczne podejmuje władca."), table({ columns, rows: others, emptyText: "Nie ma innych państw" })));
  if (c.sieges.length) add(body, card("Oblężenia", siegeList(c, new Map(ctx.overview.countries.map((x) => [x.id, x])))));
}

// ---------------------------------------------------------------- Armia

function armyTypes(c) {
  const a = c.army || {};
  const byType = a.soldiers && typeof a.soldiers === "object" ? a.soldiers : pick(a, "byType", "soldiersByType") || {};
  return arr(pick(a, "types", "units", "unitTypes")).map((t) => {
    const id = pick(t, "id", "type", "key");
    return {
      id,
      name: pick(t, "name", "displayName", "label") || id,
      cost: pick(t, "cost", "recruitCost", "price"),
      upkeep: pick(t, "upkeep", "wage", "pay", "dailyPay", "zold"),
      count: pick(t, "count", "soldiers", "amount") ?? byType[id] ?? 0,
      desc: pick(t, "description", "desc"),
    };
  });
}

function armyTab(body, c, myRank, ctx) {
  if (!c.army) return add(body, card("Armia", empty("Brak danych o wojsku — plugin Armia jest niedostępny.", "swords")));
  const a = c.army;
  const types = armyTypes(c);
  let morale = pick(a, "morale");
  if (typeof morale === "number" && morale <= 1 && morale > 0 && !Number.isInteger(morale)) morale *= 100;
  const total = pick(a, "total") ?? (types.reduce((s, t) => s + (t.count || 0), 0) || armyTotal(c) || 0);
  const upkeep = pick(a, "wages", "upkeepTotal", "dailyUpkeep") ?? types.reduce((s, t) => s + (t.count || 0) * (t.upkeep || 0), 0);
  const max = pick(a, "max", "limit", "maxSoldiers");

  add(body, 
    h(
      "div.grid-stats",
      stat("Żołnierze", fmtInt(total) + (max ? " / " + fmtInt(max) : ""), "swords"),
      stat("Żołd dzienny", money(upkeep), "coins"),
      h("div.stat.stat-wide", h("div.stat-ic", icon("shield")), h("div.stat-body", h("div.stat-label", "Morale"), typeof morale === "number" ? bar(morale / 100, Math.round(morale) + "%", morale < 30 ? "war" : morale < 60 ? "warn" : "ok") : h("div.stat-value", "—"), typeof morale === "number" && morale < 30 ? h("div.stat-sub.neg", "Niskie morale grozi dezercją — zadbaj o żołd.") : null)),
    ),
  );

  const canRecruit = rankAtLeast(myRank, "ADVISOR");
  const canDisband = myRank === "RULER";
  const columns = [
    { key: "name", label: "Jednostka", render: (t) => h("span.unit", h("strong", t.name), t.desc ? h("span.muted.small", t.desc) : null) },
    { key: "cost", label: "Koszt rekrutacji", num: true, render: (t) => money(t.cost) },
    { key: "upkeep", label: "Żołd / dzień", num: true, render: (t) => money(t.upkeep) },
    { key: "count", label: "Liczba", num: true, render: (t) => fmtInt(t.count) },
  ];
  if (canDisband)
    columns.push({
      key: "act",
      label: "Akcje",
      cls: "col-actions",
      render: (t) =>
        t.count > 0
          ? btn("Rozwiąż", { small: true, kind: "danger" }, async (_e, b) => {
              const n = h("input.input", { type: "number", min: "1", max: String(t.count), value: "1", step: "1" });
              const r = await modal({
                title: "Rozwiąż oddział: " + t.name,
                body: h("div", h("p", "Żołnierze wrócą do ludności kraju (jeśli to możliwe)."), field("Ilu żołnierzy rozwiązać? (max " + t.count + ")", n)),
                actions: [
                  { label: "Anuluj", value: null },
                  { label: "Rozwiąż", kind: "danger", onClick: () => { const v = Math.floor(Number(n.value)); if (!(v >= 1 && v <= t.count)) { n.classList.add("invalid"); return false; } return v; } },
                ],
              });
              if (r) act(b, () => api.mutate("army.disband", { id: c.id, type: t.id, count: r }), `Rozwiązano: ${t.name} × ${r}`, ctx.reload);
            })
          : null,
    });
  add(body, card("Rodzaje wojsk", table({ columns, rows: types, emptyText: "Brak danych o jednostkach (plugin Armia niedostępny?)" })));

  if (canRecruit && types.length) {
    const sel = h("select.input", { "aria-label": "Rodzaj jednostki" }, types.map((t) => h("option", { value: t.id }, `${t.name} — ${formatMoney(t.cost)}`)));
    const count = h("input.input", { type: "number", min: "1", max: "50", value: "1", step: "1", "aria-label": "Liczba" });
    const cost = h("span.money-hint");
    const upd = () => {
      const t = types.find((x) => x.id === sel.value);
      const n = Math.max(0, Math.floor(Number(count.value) || 0));
      cost.textContent = t ? `Koszt: ${formatMoney((t.cost || 0) * n)} · żołd: ${formatMoney((t.upkeep || 0) * n)}/dzień` : "";
    };
    sel.addEventListener("change", upd);
    count.addEventListener("input", upd);
    upd();
    const submit = btn("Rekrutuj", { kind: "primary", type: "submit", icon: "swords" });
    add(body, 
      card(
        "Rekrutacja",
        h("p.muted.small", "Koszt pobierany jest ze skarbca. Żołnierze pojawią się przy koszarach albo w stolicy."),
        h(
          "form.inline-form",
          {
            on: {
              submit: (e) => {
                e.preventDefault();
                const n = Math.floor(Number(count.value));
                if (!(n >= 1)) return count.classList.add("invalid");
                const t = types.find((x) => x.id === sel.value);
                act(submit, () => api.mutate("army.recruit", { id: c.id, type: sel.value, count: n }), `Zrekrutowano: ${t ? t.name : sel.value} × ${n}`, ctx.reload);
              },
            },
          },
          field("Jednostka", sel),
          field("Liczba", count),
          submit,
        ),
        cost,
      ),
    );
  }
}

// ---------------------------------------------------------------- Ludność

/** Budynki przychodzą pojedynczo (z pozycją) — grupujemy po nazwie, sumując łóżka. */
export function groupBuildings(list) {
  const map = new Map();
  for (const b of list) {
    const name = typeof b === "string" ? b : pick(b, "name", "displayName", "type") || "?";
    const g = map.get(name) || { name, count: 0, beds: 0 };
    g.count += typeof b === "object" && typeof b.count === "number" ? b.count : 1;
    g.beds += (typeof b === "object" && Number(pick(b, "beds", "capacity"))) || 0;
    map.set(name, g);
  }
  return [...map.values()].sort((a, b) => b.count - a.count || a.name.localeCompare(b.name, "pl"));
}

function populationTab(body, c, myRank, ctx) {
  if (!c.population && typeof c.populationCount !== "number") return add(body, card("Ludność", empty("Brak danych o ludności — plugin Ludnosc jest niedostępny.", "house")));
  const p = c.population || {};
  const pop = pick(p, "population", "count", "citizens") ?? c.populationCount;
  const housing = pick(p, "housing", "beds", "homes");
  const food = pick(p, "food", "granary", "foodStored", "spichlerz");
  const foodCap = pick(p, "foodCapacity", "granaryCapacity", "foodMax");
  const income = pick(p, "dailyTax", "taxIncome", "dailyTaxIncome", "income");
  const buildings = groupBuildings(arr(pick(p, "buildings")));
  const hungry = pick(p, "hungryDays");
  const homeless = pick(p, "homelessDays");
  const foodBal = pick(p, "foodBalance");
  const sites = arr(pick(p, "construction", "constructions", "sites", "buildSites"));
  const popN = typeof pop === "number" ? pop : Array.isArray(pop) ? pop.length : null;

  add(body, 
    h(
      "div.grid-stats",
      h("div.stat.stat-wide", h("div.stat-ic", icon("users")), h("div.stat-body", h("div.stat-label", "Ludność / mieszkania"), h("div.stat-value", `${fmtInt(popN)} / ${fmtInt(housing)}`), typeof housing === "number" && housing > 0 ? bar((popN || 0) / housing, popN >= housing ? "Brak wolnych łóżek" : `${housing - (popN || 0)} wolnych łóżek`, popN >= housing ? "warn" : "ok") : h("div.stat-sub", "Bez mieszkań ludność nie rośnie.")) ),
      h("div.stat.stat-wide", h("div.stat-ic", icon("house")), h("div.stat-body", h("div.stat-label", "Spichlerz"), h("div.stat-value", fmtInt(typeof food === "object" && food ? food.amount ?? food.food : food) + (foodCap ? " / " + fmtInt(foodCap) : "") + " jedzenia"), typeof foodBal === "number" && foodBal !== 0 ? h("div.stat-sub" + (foodBal < 0 ? ".neg" : ""), (foodBal > 0 ? "+" : "") + fmtInt(foodBal) + " dziennie") : null, foodCap ? bar((Number(food) || 0) / foodCap, pct((Number(food) || 0) / foodCap), Number(food) < foodCap * 0.15 ? "war" : "ok") : null)),
      income !== undefined ? stat("Podatek od ludności", money(income), "coins", "dziennie") : null,
    ),
    hungry > 0 || homeless > 0
      ? h("div.alert.alert-warn", icon("warn"), h("span", [hungry > 0 ? `Ludność głoduje od ${hungry} dni` : null, homeless > 0 ? `brakuje mieszkań od ${homeless} dni` : null].filter(Boolean).join(" · ") + " — osadnicy mogą odchodzić, a podatki maleją."))
      : null,
    h(
      "div.grid-cards",
      card(
        "Budynki",
        buildings.length
          ? h(
              "ul.building-list",
              buildings.map((b) => h("li", icon("house"), h("span", b.name), b.beds ? h("span.muted.small", `${b.beds} łóżek`) : null, h("span.badge", "× " + b.count))),
            )
          : empty("Brak budynków. Zamów budowę w grze: /ludnosc buduj", "house"),
      ),
      card(
        "Place budowy",
        sites.length
          ? h(
              "ul.site-list",
              sites.map((s) => {
                const f = fraction(s);
                const name = pick(s, "name", "displayName", "type") || "Budynek";
                const placed = pick(s, "placed", "done", "blocksPlaced");
                const total = pick(s, "total", "blocksTotal", "blocks");
                return h("li", h("div.site-head", h("strong", name), h("span.muted.small", typeof placed === "number" && typeof total === "number" ? `${fmtInt(placed)} / ${fmtInt(total)} bloków · ${pct(f)}` : pct(f))), bar(f, null));
              }),
            )
          : empty("Nic się teraz nie buduje.", "gear"),
      ),
    ),
  );
}

// ---------------------------------------------------------------- Ustawienia (Władca)

function settingsTab(body, c, myRank, ctx) {
  // Kolor
  const color = h("input.color-input", { type: "color", value: c.color, "aria-label": "Kolor kraju" });
  const preview = h("div.color-preview", shield(c.color, "lg"), h("span.cp-name", c.name));
  const hex = h("code.hex", c.color.toUpperCase());
  const setColor = (v) => {
    color.value = v;
    preview.querySelector("svg").style.setProperty("--c", v);
    preview.style.setProperty("--c", v);
    hex.textContent = v.toUpperCase();
    document.getElementById("main").dataset.dirty = "1";
  };
  preview.style.setProperty("--c", c.color);
  color.addEventListener("input", () => setColor(color.value));
  const swatches = h(
    "div.swatches",
    SWATCHES.map((s) => {
      const b = h("button.swatch", { type: "button", title: s, "aria-label": "Kolor " + s, on: { click: () => setColor(s) } });
      b.style.setProperty("--c", s);
      return b;
    }),
  );
  const saveColor = btn("Zapisz kolor", { kind: "primary" }, (_e, b) => act(b, () => api.mutate("country.setColor", { id: c.id, color: color.value.toUpperCase() }), "Zapisano kolor kraju", ctx.reload));

  // Opis
  const desc = h("textarea.input", { rows: "4", maxlength: "300", placeholder: "Kilka słów o historii i obyczajach państwa…" });
  desc.value = c.description || "";
  const counter = h("span.field-help", `${desc.value.length}/300`);
  desc.addEventListener("input", () => (counter.textContent = `${desc.value.length}/300`));
  const saveDesc = btn("Zapisz opis", { kind: "primary" }, (_e, b) => act(b, () => api.mutate("country.setDescription", { id: c.id, text: desc.value.trim() }), "Zapisano opis", ctx.reload));

  // Podatek
  const tax = moneyInput({ value: c.tax !== undefined ? formatMoney(c.tax) : "" });
  const saveTax = btn("Ustaw podatek", { kind: "primary" }, (_e, b) => {
    const v = tax.value();
    if (v === null) return tax.input.classList.add("invalid");
    if (typeof c.taxMax === "number" && v > c.taxMax) {
      tax.input.classList.add("invalid");
      return;
    }
    act(b, () => api.mutate("country.setTax", { id: c.id, amount: v }), `Podatek ustawiony na ${formatMoney(v)} dziennie`, ctx.reload);
  });

  // Przekazanie władzy
  const me = ctx.me || {};
  const candidates = (c.members || []).filter((m) => m.rank !== "RULER" && m.name.toLowerCase() !== String(me.name || "").toLowerCase());
  const who = h("select.input", { "aria-label": "Nowy władca" }, h("option", { value: "" }, "— wybierz członka —"), candidates.map((m) => h("option", { value: m.name }, `${m.name} (${RANKS[m.rank]})`)));
  const transfer = btn("Przekaż władzę", { kind: "danger", icon: "crown" }, async (_e, b) => {
    const p = who.value;
    if (!p) return who.focus();
    const ok = await confirmDanger({ title: "Przekazać władzę?", text: `${p} zostanie nowym władcą państwa ${c.name}, a Ty stracisz rangę władcy. Tej decyzji nie cofniesz samodzielnie.`, confirmLabel: "Przekazuję koronę", requireText: p });
    if (ok) act(b, () => api.mutate("country.transferRuler", { id: c.id, player: p }), `${p} jest nowym władcą`, () => ctx.go("/kraj/" + encodeURIComponent(c.id)));
  });

  add(body, 
    h(
      "div.grid-cards",
      card("Barwy państwa", h("div.color-row", preview, h("div.color-pick", color, hex)), swatches, h("div.form-actions", saveColor)),
      card("Opis", field("Opis kraju", desc), counter, h("div.form-actions", saveDesc)),
      card("Podatek", h("p.muted.small", "Pobierany raz dziennie od każdego obywatela-gracza z konta bankowego.", typeof c.taxMax === "number" ? " Maksymalnie: " + formatMoney(c.taxMax) + "." : ""), field("Podatek dzienny", tax.el), h("div.form-actions", saveTax)),
      card("Przekazanie władzy", h("p.muted.small", "Nowy władca przejmuje pełnię uprawnień."), candidates.length ? [field("Nowy władca", who), h("div.form-actions", transfer)] : h("p.muted", "Brak innych członków, którym można przekazać władzę.")),
    ),
  );
}
