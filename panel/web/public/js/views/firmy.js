// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Firmy graczy (plugin Firmy, RPC firmy.*): ranking (#/firmy?sort=…), karta firmy (#/firmy/<id>) i zarząd
// właściciela (#/firmy/zarzad). Dostęp rozstrzygają Firmy przy KAŻDYM wywołaniu (publiczne / członek firmy / WorldSteward);
// widok tylko pokazuje to, co przyszło. Odczyty firmy.* nie trafiają do sessionStorage ani do cache Workera.
// Sekcje etapu 2 (kontrakty, kredyty, spory) chowają się z dopiskiem, gdy zainstalowane Firmy jeszcze ich nie znają.
import { h, add, clear, icon, modal, skeleton } from "../dom.js";
import { card, money, btn, act, table, tabs, stat, empty, field } from "../components.js";
import * as api from "../api.js";
import { formatMoney } from "../money.js";
import { fmtInt, fmtDate } from "../util.js";
import { dailyBars } from "../world-stats-charts.js";
import * as F from "../firmy-model.js";

const STAGE2_NOTE = "Dostępne po wdrożeniu etapu 2 Firm.";
const JOURNAL_PAGE = 20;
/** Strona księgi per firma — automatyczne odświeżanie co 30 s nie wraca na pierwszą stronę. */
const journalPages = new Map();

// ---------------------------------------------------------------- drobne elementy

/** Herb: prostokąt w kolorze firmy (bez obrazków) i nazwa koloru podstawy sztandaru. */
export function herb(color, name) {
  const rect = h("span", {
    role: "img",
    "aria-label": "Herb" + (name ? ": " + name : ""),
    style: { display: "inline-block", width: "28px", height: "18px", "border-radius": "3px", border: "1px solid rgba(0, 0, 0, 0.45)", "box-shadow": "inset 0 0 0 1px rgba(255, 255, 255, 0.18)", background: F.herbColor(color), flex: "none" },
  });
  return h("span", { style: { display: "inline-flex", "align-items": "center", gap: "6px" } }, rect, name ? h("span.muted.small", name) : null);
}

const stateBadge = (s) => h("span.ws-level.ws-level-" + F.stateTone(s), F.stateLabel(s));

function signedMoney(amount) {
  const n = Number(amount) || 0;
  return h("span.money" + (n > 0 ? ".pos-amt" : n < 0 ? ".neg" : ""), { title: n + " md" }, (n > 0 ? "+" : "") + formatMoney(n));
}

const coords = (o) => (o && [o.x, o.y, o.z].every((v) => Number.isFinite(Number(v))) ? `${o.world ? o.world + " " : ""}${o.x}, ${o.y}, ${o.z}` : "—");

function alertBox(kind, text) {
  return h("div.alert.alert-" + kind, { role: kind === "err" ? "alert" : null }, icon(kind === "ok" ? "check" : "warn"), h("span", text));
}

/** Wynik odczytu, który może nie istnieć (etap 2): {data} | {missing} | {error}. */
async function soft(method, params) {
  try {
    return { data: (await api.read(method, params)).data };
  } catch (e) {
    if (e.status === 401) throw e;
    if (F.isStageMissing(e)) return { missing: true };
    const msg = e.message || String(e);
    return F.isDenied(e) || /widzą jej członkowie/i.test(msg) ? { denied: true, error: msg } : { error: msg };
  }
}

/** Sekcja etapu 2: brak metody → dopisek, błąd → ostrzeżenie, inaczej treść. */
function stageSection(title, res, draw) {
  if (!res || res.missing) return card(title, h("p.muted", STAGE2_NOTE));
  // Odmowa (np. kredyty widzą tylko członkowie) to zwykła sytuacja na karcie publicznej — szary dopisek, nie ostrzeżenie.
  if (res.denied) return card(title, h("p.muted", res.error));
  if (res.error) return card(title, alertBox("warn", res.error));
  return card(title, draw(res.data || {}));
}

function pageHead(title, sub, ctx, extra) {
  return h("div.page-head", h("div", h("h1", title), sub ? h("p.muted", sub) : null), h("div.actions", extra || null, btn("Odśwież", { mut: false, icon: "rotate" }, () => ctx.forceReload())));
}

function firmTabs(owner, active) {
  const items = [{ id: "ranking", label: "Ranking firm", icon: "crown", href: "#/firmy" }];
  if (owner) items.push({ id: "zarzad", label: "Zarząd (właściciel)", icon: "key", href: "#/firmy/zarzad" });
  return tabs(items, active);
}

function unavailableCard(main, ctx, message) {
  add(main, pageHead("Firmy", null, ctx), card("Firmy są niedostępne", h("p", message || "Firmy są obecnie niedostępne."), h("p.muted", "Strona zadziała, gdy plugin Firmy będzie aktywny na serwerze.")));
}

// ---------------------------------------------------------------- ranking

function sortSwitch(active, ctx) {
  return h(
    "div.seg",
    { role: "group", "aria-label": "Sortuj ranking według" },
    F.SORTS.map((s) => h("button.seg-btn" + (s.id === active ? ".on" : ""), { type: "button", "aria-pressed": String(s.id === active), dataset: { sort: s.id }, on: { click: () => s.id !== active && ctx.go("/firmy" + (s.id === "obrot" ? "" : "?sort=" + s.id)) } }, h("span", s.label))),
  );
}

function rankingPage(main, d, ctx) {
  const owner = d.owner === true;
  const rows = F.rankingRows(d);
  const columns = [
    { key: "rank", label: "#", cls: "col-pos", render: (r) => h("span.pos", String(r.rank)) },
    { key: "herb", label: "Herb", render: (r) => herb(r.color, r.bannerName) },
    { key: "name", label: "Nazwa", render: (r) => h("span.rank-name", h("a", { href: F.firmHref(r.id) }, r.name), r.mine ? h("span.tag.tag-mine", "Twoja") : null) },
    { key: "typeName", label: "Typ" },
    { key: "seatName", label: "Siedziba" },
    { key: "members", label: "Ludzi", num: true, render: (r) => fmtInt(r.members) },
    { key: "income30", label: "Obrót 30 dni", num: true, render: (r) => money(r.income30) },
    { key: "contracts", label: "Kontrakty", num: true, render: (r) => fmtInt(r.contracts) },
    { key: "state", label: "Stan", render: (r) => stateBadge(r.state) },
  ];
  if (owner) columns.push({ key: "balance", label: "Saldo", num: true, render: (r) => (r.balance === null ? "—" : money(r.balance)) });
  const carrier = d.carrier && typeof d.carrier === "object" ? d.carrier : null;
  add(
    main,
    pageHead("Firmy", "Kompanie, warsztaty i domy handlowe graczy. Kliknij firmę, aby zobaczyć jej kartę.", ctx),
    firmTabs(owner, "ranking"),
    h("div.stack", { style: { "margin-top": "16px" } },
      h("div", sortSwitch(d.sort ? F.normSort(d.sort) : "obrot", ctx)),
      h("section.card.card-flush", table({ columns, rows, sort: null, onRowClick: (r) => ctx.go("/firmy/" + encodeURIComponent(r.id)), emptyText: "Na serwerze nie ma jeszcze żadnej firmy.", cls: "ranking" })),
      carrier ? card(carrier.name || "Królewski przewoźnik", h("p", carrier.note || "Kolej Korony — poza rankingiem."), h("p.muted.small", "Przewoźnik nie bierze udziału w rankingu firm.")) : null,
    ),
  );
}

// ---------------------------------------------------------------- karta firmy

function journalSection(f) {
  const box = h("div");
  const list = Array.isArray(f.journal) ? f.journal : [];
  const draw = () => {
    const pg = F.pageOf(list, journalPages.get(f.id) || 1, JOURNAL_PAGE);
    journalPages.set(f.id, pg.page);
    clear(box);
    add(
      box,
      table({
        columns: [
          { key: "time", label: "Czas", render: (e) => fmtDate(e.time) },
          { key: "kind", label: "Rodzaj", render: (e) => F.journalKind(e.kind) },
          { key: "text", label: "Opis", render: (e) => e.text || "—" },
          { key: "amount", label: "Kwota", num: true, render: (e) => (Number(e.amount) ? signedMoney(e.amount) : "—") },
          { key: "actor", label: "Kto", render: (e) => e.actor || "—" },
        ],
        rows: pg.rows,
        sort: null,
        emptyText: "Księga jest pusta.",
      }),
      pg.pages > 1
        ? h(
            "nav.ws-pager",
            { "aria-label": "Strony księgi" },
            btn("Nowsze", { mut: false, small: true }, () => (journalPages.set(f.id, pg.page - 1), draw())),
            h("span.ws-pager-info", `Strona ${pg.page} z ${pg.pages} · wpisy ${pg.from}–${pg.to} z ${pg.total}`),
            btn("Starsze", { mut: false, small: true }, () => (journalPages.set(f.id, pg.page + 1), draw())),
          )
        : null,
    );
    const [prev, next] = box.querySelectorAll(".ws-pager .btn");
    if (prev) prev.disabled = pg.page <= 1;
    if (next) next.disabled = pg.page >= pg.pages;
  };
  draw();
  return card("Księga firmy", box);
}

function incomeChart(f) {
  const days = F.incomeSeries(f.incomeByDay, 30);
  const total = days.reduce((s, d) => s + d.values.income, 0);
  return card(
    "Przychód dzienny (30 dni)",
    dailyBars({ days, series: [{ key: "income", label: "Przychód", cls: "ws-in", sign: 1 }], fmt: formatMoney, desc: `Przychód firmy dzień po dniu, 30 dni, razem ${formatMoney(total)}`, totalLabel: "Przychód" }),
    h("p.muted.small", "Przychód brutto (przed podatkiem) ze sklepów, kontraktów i wpłat z zewnątrz."),
  );
}

function contractsTable(d) {
  const rows = Array.isArray(d.contracts) ? d.contracts : [];
  return table({
    columns: [
      { key: "title", label: "Kontrakt", render: (c) => c.title || "#" + c.id },
      { key: "client", label: "Zleceniodawca" },
      { key: "contractor", label: "Wykonawca" },
      { key: "status", label: "Stan", render: (c) => c.statusName || c.status || "—" },
      { key: "price", label: "Zapłata", num: true, render: (c) => money(c.price) },
      { key: "deposit", label: "Depozyt", num: true, render: (c) => money(c.deposit) },
      { key: "bond", label: "Wadium", num: true, render: (c) => (Number(c.bond) ? money(c.bond) : "—") },
      { key: "goods", label: "Towar", render: (c) => (c.item ? `${fmtInt(c.delivered ?? 0)} / ${fmtInt(c.quantity ?? 0)} ${c.item}` : "—") },
      { key: "deadline", label: "Termin", render: (c) => fmtDate(c.deadline) },
    ],
    rows,
    sort: null,
    emptyText: "Firma nie ma kontraktów.",
  });
}

function loansTable(d) {
  const rows = Array.isArray(d.loans) ? d.loans : [];
  return h(
    "div",
    d.limit !== undefined || d.limitText ? h("p", "Limit kredytowy firmy: ", d.limitText ? h("strong", d.limitText) : money(d.limit)) : null,
    table({
      columns: [
        { key: "lender", label: "Pożyczkodawca", render: (l) => l.lenderName || l.lender || "—" },
        { key: "principal", label: "Kwota", num: true, render: (l) => money(l.principal) },
        { key: "interest", label: "Odsetki", num: true, render: (l) => money(l.interest) },
        { key: "repaid", label: "Spłacono", num: true, render: (l) => money(l.repaid) },
        { key: "outstanding", label: "Do spłaty", num: true, render: (l) => money(l.outstanding) },
        { key: "status", label: "Stan", render: (l) => l.statusName || l.status || "—" },
        // Wniosek (REQUESTED) nie jest jeszcze udzielony, a termin Firmy ustawiają dopiero przy wypłacie (dueAt = 0).
        { key: "createdAt", label: "Udzielony", render: (l) => (l.status === "REQUESTED" ? "—" : fmtDate(l.createdAt)) },
        { key: "dueAt", label: "Termin", render: (l) => (Number(l.dueAt) > 0 ? fmtDate(l.dueAt) : "—") },
      ],
      rows,
      sort: null,
      emptyText: "Firma nie ma kredytów.",
    }),
  );
}

function firmPage(main, r, ctx) {
  const f = r.firm;
  const access = f.access || {};
  const full = access.member === true || access.owner === true;
  const color = F.herbColor(f.color);
  const people = Array.isArray(f.people) ? f.people : [];
  add(
    main,
    pageHead(f.name || "Firma", [f.typeName || f.type, f.seatName ? "siedziba: " + f.seatName : null].filter(Boolean).join(" · "), ctx, h("a.btn", { href: "#/firmy" }, icon("crown"), h("span", "Ranking firm"))),
    firmTabs(access.owner === true, "ranking"),
  );
  const stack = h("div.stack", { style: { "margin-top": "16px" } });
  add(main, stack);

  if (f.frozen && full) add(stack, alertBox("warn", "Firma jest zamrożona" + (f.frozenReason ? ": " + f.frozenReason : ".")));
  const groups = F.addressGroups(f.sharedAddresses);
  if (access.owner === true && groups.length)
    add(stack, h("div.alert.alert-warn", icon("warn"), h("div", h("strong", "Wspólny adres IP — bez automatycznych kar."), h("ul", groups.map((g) => h("li", g.join(", ")))))));

  add(
    stack,
    card(
      null,
      h("div", { style: { display: "flex", "align-items": "center", gap: "12px", "flex-wrap": "wrap" } }, herb(color, F.bannerName(f.banner)), stateBadge(f.state), full ? null : h("span.muted.small", "Widok publiczny — saldo i księgę widzą członkowie firmy.")),
      h(
        "dl.facts",
        h("dt", "Typ"), h("dd", f.typeName || f.type || "—"),
        h("dt", "Siedziba"), h("dd", f.seatName || "—"),
        h("dt", "Mistrz"), h("dd", f.master || "—"),
        h("dt", "Ludzie"), h("dd", fmtInt(people.length || f.members)),
        h("dt", "Obrót 30 dni"), h("dd", money(f.income30)),
        h("dt", "Wykonane kontrakty"), h("dd", fmtInt(f.contracts)),
        f.createdAt ? [h("dt", "Założona"), h("dd", fmtDate(f.createdAt))] : null,
      ),
    ),
  );

  if (full) {
    add(
      stack,
      h(
        "div.grid-stats",
        stat("Saldo", f.balanceText || money(f.balance), "coins"),
        stat("Podatek", f.taxPercent === null || f.taxPercent === undefined ? "—" : F.fmtPercent(f.taxPercent), "scroll", "Podatek 30 dni: " + formatMoney(f.tax30)),
        stat("Przychód 7 dni", money(f.income7), "sort"),
        stat("Przychód 30 dni", money(f.income30), "sort"),
        stat("Zaległe pensje", money(f.arrears), "warn", f.unpaidDays ? `${fmtInt(f.unpaidDays)} dni z rzędu bez pełnych pensji` : null),
      ),
    );
  }

  const peopleCols = [
    { key: "name", label: "Gracz" },
    { key: "roleName", label: "Rola", render: (p) => p.roleName || p.role || "—" },
  ];
  if (full)
    peopleCols.push(
      { key: "share", label: "Udział", num: true, render: (p) => F.fmtPercent(p.share) },
      { key: "salary", label: "Pensja dzienna", num: true, render: (p) => (Number(p.salary) ? money(p.salary) : "—") },
      { key: "arrears", label: "Zaległość", num: true, render: (p) => (Number(p.arrears) ? h("span.neg", formatMoney(p.arrears)) : "—") },
    );
  add(stack, card("Ludzie", table({ columns: peopleCols, rows: people, sort: null, emptyText: "Firma nie ma członków." })));

  if (full) {
    const payouts = Array.isArray(f.payouts) ? f.payouts : [];
    add(
      stack,
      card(
        "Oczekujące dywidendy",
        payouts.length
          ? table({ columns: [{ key: "amount", label: "Kwota", num: true, render: (p) => money(p.amount) }, { key: "dueAt", label: "Wypłata", render: (p) => fmtDate(p.dueAt) }, { key: "by", label: "Zlecił", render: (p) => p.by || "—" }], rows: payouts, sort: null })
          : h("p.muted", "Brak oczekujących wypłat."),
        h("p.muted.small", "Duże wypłaty czekają 24 godziny, zanim trafią do wspólników."),
      ),
      incomeChart(f),
      journalSection(f),
    );
    const shops = Array.isArray(f.shops) ? f.shops : [];
    add(
      stack,
      card(
        "Sklepy firmy",
        table({
          columns: [
            { key: "type", label: "Rodzaj", render: (s) => F.shopType(s.type) },
            { key: "item", label: "Towar", render: (s) => `${fmtInt(s.amount)} × ${s.item || "?"}` },
            { key: "price", label: "Cena", num: true, render: (s) => money(s.price) },
            { key: "stock", label: "Zapas", num: true, render: (s) => (s.stock === undefined || s.stock === null ? "—" : fmtInt(s.stock)) },
            { key: "pos", label: "Miejsce", render: (s) => coords(s) },
          ],
          rows: shops,
          sort: null,
          emptyText: "Firma nie ma sklepów.",
        }),
      ),
    );
    const chests = Array.isArray(f.warehouse) ? f.warehouse : [];
    const log = Array.isArray(f.warehouseLog) ? f.warehouseLog : [];
    add(
      stack,
      card(
        "Magazyn",
        chests.length ? h("ul", chests.map((c) => h("li", "Skrzynia: ", coords(c), c.addedAt ? h("span.muted.small", " · od " + fmtDate(c.addedAt)) : null))) : h("p.muted", "Firma nie ma skrzyń magazynowych."),
        h("h4", "Kto co wziął"),
        table({
          columns: [
            { key: "time", label: "Czas", render: (e) => fmtDate(e.time) },
            { key: "player", label: "Gracz" },
            { key: "item", label: "Przedmiot" },
            { key: "delta", label: "Zmiana", num: true, render: (e) => { const n = Number(e.delta) || 0; return h("span" + (n > 0 ? ".pos-amt" : n < 0 ? ".neg" : ""), (n > 0 ? "+" : "") + fmtInt(n)); } },
            { key: "chest", label: "Skrzynia", render: (e) => e.chest || "—" },
          ],
          rows: log,
          sort: null,
          emptyText: "Brak ruchów w magazynie.",
        }),
      ),
    );
  }

  add(stack, stageSection("Kontrakty", r.contracts, contractsTable), stageSection("Kredyty", r.loans, loansTable));
}

// ---------------------------------------------------------------- zarząd właściciela

async function freezeDialog(f, b, ctx) {
  const to = !f.frozen;
  const reason = h("input.input", { type: "text", maxlength: String(F.REASON_MAX), placeholder: to ? "np. podejrzenie oszustwa" : "np. sprawa wyjaśniona", autocomplete: "off", "aria-label": "Powód" });
  const ok = await modal({
    title: (to ? "Zamrozić firmę " : "Odmrozić firmę ") + (f.name || "") + "?",
    body: h("div", h("p", to ? "Zamrożenie wstrzymuje wypłaty i operacje pieniężne firmy, dopóki jej nie odmrozisz." : "Firma odzyska możliwość wypłat i operacji pieniężnych."), field("Powód (trafi do księgi firmy)", reason, `Najwyżej ${F.REASON_MAX} znaków.`)),
    actions: [{ label: "Anuluj", value: null }, { label: to ? "Zamroź" : "Odmroź", kind: to ? "danger" : "primary", onClick: () => true }],
  });
  if (ok) await act(b, () => api.mutate("firmy.admin.freeze", F.freezeParams(f.id, to, reason.value)), (res) => (res && res.message) || (to ? "Firma zamrożona" : "Firma odmrożona"), ctx.reload);
}

async function disputeDialog(d, winner, b, ctx) {
  const w = F.DISPUTE_WINNERS.find((x) => x.id === winner);
  const note = h("textarea.input", { rows: "3", maxlength: String(F.NOTE_MAX), placeholder: "Uzasadnienie decyzji (widzą je obie strony)", "aria-label": "Notatka" });
  const ok = await modal({
    title: "Rozstrzygnąć spór?",
    body: h(
      "div",
      h("p", h("strong", d.title || "Kontrakt #" + (d.contract ?? d.id)), " — ", d.client || "?", " ↔ ", d.contractor || "?"),
      h("p", "Depozyt: ", money(d.amount), Number(d.bond) ? [" · wadium: ", money(d.bond)] : null),
      h("p", "Decyzja: ", h("strong", w ? w.label : winner), ". Tej decyzji nie da się cofnąć."),
      field("Notatka", note, `Najwyżej ${F.NOTE_MAX} znaków.`),
    ),
    actions: [{ label: "Anuluj", value: null }, { label: "Rozstrzygnij", kind: "danger", onClick: () => true }],
  });
  if (ok) await act(b, () => api.mutate("firmy.dispute.resolve", { id: String(d.id), winner, note: note.value.trim().slice(0, F.NOTE_MAX) }), (res) => (res && res.message) || "Spór rozstrzygnięty", ctx.reload);
}

function disputesTable(d, ctx) {
  const rows = Array.isArray(d.disputes) ? d.disputes : [];
  if (!rows.length) return empty("Brak otwartych sporów.", "check");
  return table({
    columns: [
      { key: "title", label: "Kontrakt", render: (x) => x.title || "#" + (x.contract ?? x.id) },
      { key: "parties", label: "Strony", render: (x) => `${x.client || "?"} ↔ ${x.contractor || "?"}` },
      { key: "amount", label: "Depozyt", num: true, render: (x) => money(x.amount) },
      { key: "bond", label: "Wadium", num: true, render: (x) => (Number(x.bond) ? money(x.bond) : "—") },
      { key: "reason", label: "Powód sporu", render: (x) => x.reason || "—" },
      { key: "openedAt", label: "Otwarty", render: (x) => [fmtDate(x.openedAt), x.openedBy ? h("span.muted.small", " przez " + x.openedBy) : null] },
      { key: "act", label: "Decyzja", cls: "col-actions", render: (x) => h("div.row-actions", F.DISPUTE_WINNERS.map((w) => btn(w.label, { small: true }, (_, b) => disputeDialog(x, w.id, b, ctx)))) },
    ],
    rows,
    sort: null,
  });
}

function adminPage(main, r, ctx) {
  if (r.denied) {
    // Bez zakładki właściciela i bez „Widoczne tylko dla Ciebie” — odmowa nie udaje strony zarządu.
    add(main, pageHead("Firmy", null, ctx), firmTabs(false, "ranking"), card("Brak dostępu", h("p", "Zarząd firm prowadzi osobiście WorldSteward."), h("p.muted", r.message || "")));
    return;
  }
  add(main, pageHead("Firmy — zarząd", "Wszystkie firmy, wnioski i niepotwierdzone operacje. Widoczne tylko dla Ciebie.", ctx), firmTabs(true, "zarzad"));
  const d = r.data || {};
  const firms = Array.isArray(d.firms) ? d.firms : [];
  const apps = Array.isArray(d.applications) ? d.applications : [];
  const uncertain = Array.isArray(d.uncertain) ? d.uncertain : [];
  const stack = h("div.stack", { style: { "margin-top": "16px" } });
  add(main, stack);
  add(
    stack,
    h(
      "div.grid-stats",
      stat("Saldo wszystkich firm", d.totalBalanceText || money(d.totalBalance), "coins"),
      stat("Firmy", fmtInt(firms.length), "users", `czynne: ${fmtInt(firms.filter((f) => String(f.state).toUpperCase() === "ACTIVE").length)}`),
      stat("Wnioski", fmtInt(apps.length), "scroll"),
      stat("Niepotwierdzone operacje", fmtInt(uncertain.length), "warn"),
    ),
  );
  if (uncertain.length)
    add(
      stack,
      card(
        "Niepotwierdzone operacje",
        alertBox("warn", "Te operacje nie zostały potwierdzone w księdze Skarbca. Firmy rozstrzygną je przy starcie albo wymagają sprawdzenia."),
        table({
          columns: [
            { key: "id", label: "Id" },
            { key: "firm", label: "Firma", render: (x) => (x.firm ? h("a", { href: F.firmHref(x.firm) }, x.firm) : "—") },
            { key: "kind", label: "Rodzaj" },
            { key: "description", label: "Opis" },
            { key: "createdAt", label: "Kiedy", render: (x) => fmtDate(x.createdAt) },
            { key: "legs", label: "Przelewy", render: (x) => (Array.isArray(x.legs) && x.legs.length ? h("ul", x.legs.map((l) => h("li.small", typeof l === "string" ? l : JSON.stringify(l)))) : "—") },
          ],
          rows: uncertain,
          sort: null,
        }),
      ),
    );

  add(
    stack,
    h(
      "section.card.card-flush",
      table({
        columns: [
          { key: "name", label: "Firma", render: (f) => h("span", { style: { display: "inline-flex", "align-items": "center", gap: "8px" } }, herb(f.color), h("a", { href: F.firmHref(f.id) }, f.name || f.id)), sort: (f) => String(f.name || "") },
          { key: "typeName", label: "Typ", render: (f) => f.typeName || f.type || "—" },
          { key: "seatName", label: "Siedziba", render: (f) => f.seatName || "—" },
          { key: "members", label: "Ludzi", num: true, render: (f) => fmtInt(f.members), sort: (f) => Number(f.members) || 0 },
          { key: "income30", label: "Obrót 30 dni", num: true, render: (f) => money(f.income30), sort: (f) => Number(f.income30) || 0 },
          { key: "balance", label: "Saldo", num: true, render: (f) => money(f.balance), sort: (f) => Number(f.balance) || 0 },
          { key: "tax30", label: "Podatek 30 dni", num: true, render: (f) => money(f.tax30), sort: (f) => Number(f.tax30) || 0 },
          { key: "arrears", label: "Zaległości", num: true, render: (f) => (Number(f.arrears) ? h("span.neg", formatMoney(f.arrears)) : "—"), sort: (f) => Number(f.arrears) || 0 },
          { key: "state", label: "Stan", render: (f) => [stateBadge(f.state), f.frozen ? h("span.ws-level.ws-level-warn", { title: f.frozenReason || "" }, "zamrożona") : null, f.liquidationReason ? h("div.muted.small", f.liquidationReason) : null] },
          { key: "ips", label: "Wspólne IP", sort: (f) => F.addressGroups(f.sharedAddresses).length, render: (f) => { const g = F.addressGroups(f.sharedAddresses); return g.length ? h("span.ws-level.ws-level-warn", { title: g.map((x) => x.join(", ")).join(" | ") }, g.map((x) => x.join(", ")).join("; ")) : "—"; } },
          { key: "act", label: "Akcje", cls: "col-actions", sort: false, render: (f) => (String(f.state).toUpperCase() === "LIQUIDATED" ? null : btn(f.frozen ? "Odmroź" : "Zamroź", { small: true, kind: f.frozen ? null : "danger" }, (_, b) => freezeDialog(f, b, ctx))) },
        ],
        rows: firms,
        sort: { key: "income30", dir: "desc" },
        emptyText: "Brak firm.",
      }),
    ),
    card(
      "Wnioski o założenie firmy",
      table({
        columns: [
          { key: "name", label: "Nazwa" },
          { key: "type", label: "Typ" },
          { key: "country", label: "Kraj" },
          { key: "founder", label: "Założyciel" },
          { key: "held", label: "Wpisowe w depozycie", num: true, render: (a) => money(a.held) },
          { key: "expiresAt", label: "Wygasa", render: (a) => fmtDate(a.expiresAt) },
          { key: "state", label: "Stan" },
        ],
        rows: apps,
        sort: null,
        emptyText: "Brak wniosków.",
      }),
    ),
    stageSection("Spory w kontraktach", r.disputes, (x) => disputesTable(x, ctx)),
  );
}

// ---------------------------------------------------------------- widok (trasa #/firmy i #/firmy/<id>)

export const firmyView = {
  title: (r) => (r && r.kind === "firm" && r.firm ? (r.firm.name || "Firma") + " · Firmy" : r && r.kind === "admin" ? "Firmy · Zarząd" : "Firmy"),
  skeleton: () => h("div.card", skeleton(6)),
  async load(ctx) {
    const route = F.firmyRoute(ctx.params[0]);
    try {
      if (route.kind === "list") {
        const sort = F.normSort(ctx.query.get("sort"));
        const res = await api.read("firmy.list", { sort });
        return { kind: "list", data: { sort, ...(res.data || {}) } };
      }
      if (route.kind === "admin") {
        let data;
        try {
          data = (await api.read("firmy.admin", {})).data;
        } catch (e) {
          if (F.isDenied(e)) return { kind: "admin", denied: true, message: e.message };
          throw e;
        }
        return { kind: "admin", data, disputes: await soft("firmy.disputes", {}) };
      }
      const [firm, contracts, loans] = await Promise.all([
        api.read("firmy.get", { id: route.id }).then((x) => x.data),
        soft("firmy.contracts", { id: route.id }),
        soft("firmy.loans", { id: route.id }),
      ]);
      return { kind: "firm", firm: firm || {}, contracts, loans };
    } catch (e) {
      if (e.status !== 401 && F.isStageMissing(e)) return { kind: "unavailable", message: e.message };
      throw e;
    }
  },
  draw(main, r, ctx) {
    if (r.kind === "unavailable") return unavailableCard(main, ctx, r.message);
    if (r.kind === "admin") return adminPage(main, r, ctx);
    if (r.kind === "firm") return firmPage(main, r, ctx);
    return rankingPage(main, r.data || {}, ctx);
  },
};
