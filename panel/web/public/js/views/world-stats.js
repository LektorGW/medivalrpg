// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Statystyki Skarbu Świata (tylko WorldSteward): zakładki „Bilans”, „Konta”, „Przepływy”, „Gospodarstwa” i historia konta.
// Dane: world.stats.summary/accounts/flows/account (kontrakt w README → „Statystyki Skarbu Świata”).
// Żadne dane nie są cache'owane (ani w Workerze, ani w sessionStorage) — odmowa daje kartę „Brak dostępu”.
import { h, add, clear, icon } from "../dom.js";
import { card, table, empty, btn, bar } from "../components.js";
import { historyCharts, fmtCompact } from "../chart.js";
import { formatMoney, parseMoney } from "../money.js";
import { fmtInt, fmtDate, fmtAgo } from "../util.js";
import * as M from "../world-stats-model.js";
import { dailyBars, sankeyChart, legend } from "../world-stats-charts.js";
import { BASE, accountHref, typeBadge, catBadge, accountLink, amount, signedAmount, downloadCsv, csvButton, exportAll, pager, rangePicker, debounce, setQuery, readStats, shareBar, kpi } from "./world-stats-common.js";

const SLOT_CLASSES = ["ws-c1", "ws-c2", "ws-c3", "ws-c4", "ws-c5", "ws-c6", "ws-c7"];
const LEVEL_ICON = { OK: "check", WARN: "warn", ERROR: "warn" };
const LEVEL_CLS = { OK: "ok", WARN: "warn", ERROR: "err" };

// ================================================================ Bilans

function checkItem(c) {
  const lvl = String(c.level || "WARN").toUpperCase();
  return h(
    "li.ws-check.ws-check-" + (LEVEL_CLS[lvl] || "warn"),
    icon(LEVEL_ICON[lvl] || "warn"),
    h(
      "div",
      h("strong.ws-check-word", M.LEVEL_WORDS[lvl] || "ostrzeżenie"),
      h("span", " — " + (c.text || c.key || "")),
      c.expected !== undefined && c.expected !== null && lvl !== "OK"
        ? h("div.ws-check-detail", "oczekiwano ", amount(c.expected), ", jest ", c.actual === null || c.actual === undefined ? h("span", "—") : amount(c.actual), c.difference !== null && c.difference !== undefined ? h("span", ", różnica ", signedAmount(c.difference)) : null)
        : null,
    ),
  );
}

export function complianceBanner(checks) {
  const list = Array.isArray(checks) ? checks : [];
  const lvl = M.worstLevel(list);
  const bad = list.filter((c) => String(c.level).toUpperCase() !== "OK");
  const good = list.filter((c) => String(c.level).toUpperCase() === "OK");
  if (lvl === "OK")
    return h("section.ws-banner.ws-banner-ok", { role: "status" }, icon("check"), h("div", h("strong", "Księga zgodna."), " ", h("span", `Wszystkie kontrole (${list.length}) przeszły pomyślnie.`), h("ul.ws-checks", good.map(checkItem))));
  return h(
    "section.ws-banner.ws-banner-" + (lvl === "ERROR" ? "err" : "warn"),
    { role: "alert" },
    icon("warn"),
    h(
      "div",
      h("strong", lvl === "ERROR" ? `Rozbieżność w księdze (${bad.length})` : `Ostrzeżenia (${bad.length})`),
      h("ul.ws-checks", bad.map(checkItem)),
      good.length ? h("details.ws-checks-ok", h("summary", `Kontrole zgodne (${good.length})`), h("ul.ws-checks", good.map(checkItem))) : null,
    ),
  );
}

export const bilansTab = {
  title: "Bilans",
  load: () => readStats("world.stats.summary"),
  draw(body, d) {
    const labels = d.typeLabels || {};
    const diff = Number(d.difference) || 0;
    const hero = h(
      "section.card.ws-hero",
      h(
        "div.ws-hero-main",
        h("div.ws-hero-label", "Suma wszystkich kont"),
        h("div.ws-hero-value", amount(d.total)),
        h("div.ws-hero-sub", h("span", "Pula serwera: "), amount(d.cap), " · ", h("span", fmtInt(d.total) + " md")),
      ),
      h(
        "div.ws-hero-side",
        h("div.ws-diff.ws-diff-" + (diff === 0 ? "ok" : "err"), icon(diff === 0 ? "check" : "warn"), h("div", h("div.ws-hero-label", "Różnica suma − pula"), h("strong", diff === 0 ? "0 md — zgodne" : (diff > 0 ? "+" : "−") + formatMoney(Math.abs(diff)) + " — rozbieżność"))),
        h("p.muted.small", d.closed ? "Pula zamknięta: pieniądze tylko przechodzą między kontami, nic nie powstaje ani nie znika." : "Uwaga: pula nie jest oznaczona jako zamknięta.", d.seq ? ` Ostatni wpis księgi nr ${fmtInt(d.seq)}.` : "", d.generatedAt ? ` Stan z ${fmtDate(d.generatedAt)}.` : ""),
      ),
    );
    add(body, hero, complianceBanner(d.checks));

    const cats = (Array.isArray(d.categories) ? d.categories : []).slice().sort((a, b) => M.TYPE_ORDER.indexOf(a.key) - M.TYPE_ORDER.indexOf(b.key));
    const totalAmt = cats.reduce((s, c) => s + (Number(c.amount) || 0), 0) || 1;
    const stack = h(
      "div.ws-stack",
      { role: "img", "aria-label": "Udział kategorii kont w puli: " + cats.filter((c) => c.amount > 0).map((c) => `${c.label || M.typeLabel(c.key, labels)} ${M.fmtShare(c.share ?? c.amount / totalAmt)}`).join(", ") },
      cats
        .filter((c) => c.amount > 0)
        .map((c) => {
          const seg = h("span.ws-stack-seg.ws-t-" + M.cssToken(c.key), { title: `${c.label || M.typeLabel(c.key, labels)}: ${formatMoney(c.amount)} (${M.fmtShare(c.share ?? c.amount / totalAmt)})` });
          seg.style.setProperty("flex-grow", String(Math.max(0.004, c.amount / totalAmt)));
          return seg;
        }),
    );
    add(
      body,
      card(
        "Gdzie są pieniądze — kategorie kont",
        stack,
        legend(cats.filter((c) => c.amount > 0).map((c) => ({ cls: "ws-t-" + M.cssToken(c.key), label: c.label || M.typeLabel(c.key, labels), value: M.fmtShare(c.share ?? c.amount / totalAmt) }))),
        table({
          columns: [
            { key: "key", label: "Kategoria", render: (c) => h("a.ws-plain", { href: BASE + "/konta?typ=" + encodeURIComponent(c.key) }, typeBadge(c.key, { ...labels, [c.key]: c.label })), sort: (c) => M.TYPE_ORDER.indexOf(c.key) },
            { key: "amount", label: "Kwota", num: true, render: (c) => amount(c.amount) },
            { key: "accounts", label: "Konta", num: true, render: (c) => fmtInt(c.accounts) },
            { key: "share", label: "Udział w puli", num: true, render: (c) => shareBar(c.share ?? c.amount / totalAmt, M.fmtShare(c.share ?? c.amount / totalAmt)), sort: (c) => c.amount },
          ],
          rows: cats,
          sort: { key: "amount", dir: "desc" },
        }),
      ),
    );

    const c = d.census || { status: "NONE" };
    const cdiff = c.difference;
    add(
      body,
      card(
        "Spis monet fizycznych",
        h(
          "div.grid-stats",
          kpi("Stan spisu", M.CENSUS_STATUS_LABELS[c.status] || c.status, c.finishedAt ? "zakończony " + fmtDate(c.finishedAt) : c.startedAt ? "rozpoczęty " + fmtDate(c.startedAt) : "brak danych"),
          kpi("Monety policzone w świecie", c.total === null || c.total === undefined ? "—" : amount(c.total)),
          kpi("Pokrycie na koncie", amount(c.cashAccount)),
          kpi("Różnica spis − pokrycie", cdiff === null || cdiff === undefined ? "—" : signedAmount(cdiff), cdiff ? "rozbieżność" : cdiff === 0 ? "zgodne" : "brak spisu", cdiff ? "ws-kpi-warn" : ""),
        ),
        c.status === "RUNNING" ? bar(Number(c.progress) || 0, "Postęp spisu " + Math.round((Number(c.progress) || 0) * 100) + "%") : null,
        h("p", h("a.link-more", { href: BASE + "/monety" }, "Szczegóły spisu i miejsca monet →")),
      ),
    );
  },
};

// ================================================================ Konta

export const kontaTab = {
  title: "Konta",
  load: () => readStats("world.stats.accounts"),
  draw(body, d, ctx) {
    const labels = d.typeLabels || {};
    const all = Array.isArray(d.accounts) ? d.accounts : [];
    const st = { type: (ctx.query && ctx.query.get("typ")) || "", query: (ctx.query && ctx.query.get("q")) || "" };
    if (st.type && !all.some((a) => a.type === st.type)) st.type = "";
    const chipsBox = h("div.filter-chips", { role: "group", "aria-label": "Typ konta" });
    const search = h("input.input.log-search", { type: "search", placeholder: "Szukaj konta lub właściciela…", value: st.query, "aria-label": "Szukaj konta lub właściciela", autocomplete: "off" });
    const info = h("span.muted.small.log-count");
    const slot = h("div.ws-table-slot");
    const sync = () => {
      const p = new URLSearchParams();
      if (st.type) p.set("typ", st.type);
      if (st.query) p.set("q", st.query);
      setQuery("/skarbswiata/konta", p.toString());
    };
    const drawChips = () => {
      clear(chipsBox);
      const chip = (key, label, n) => {
        const b = h("button.chip-btn" + (st.type === key ? ".on" : ""), { type: "button", "aria-pressed": String(st.type === key) }, key ? h("span.ws-key.ws-t-" + M.cssToken(key)) : null, h("span", label), h("span.chip-n", fmtInt(n)));
        b.addEventListener("click", () => {
          st.type = key;
          sync();
          drawChips();
          drawTable();
        });
        return b;
      };
      add(chipsBox, chip("", "Wszystkie", all.length), M.typeCounts(all).map((t) => chip(t.key, M.typeLabel(t.key, labels), t.count)));
    };
    const drawTable = () => {
      const rows = M.filterAccounts(all, st);
      const sum = rows.reduce((s, a) => s + (Number(a.balance) || 0), 0);
      info.textContent = `Pokazano ${fmtInt(rows.length)} z ${fmtInt(all.length)} kont · suma sald ${formatMoney(sum)} (${M.fmtShare(M.shareOf(sum, d.total))} puli)`;
      add(
        clear(slot),
        table({
          columns: [
            { key: "owner", label: "Konto", render: (a) => h("div.ws-acc-cell", h("a.ws-acc-title", { href: accountHref(a.account) }, a.owner || a.account), h("code.ws-acc-id", a.account)), sort: (a) => M.fold(a.owner || a.account) },
            { key: "type", label: "Typ", render: (a) => typeBadge(a.type, { ...labels, [a.type]: a.typeLabel || labels[a.type] }), sort: (a) => M.TYPE_ORDER.indexOf(a.type) },
            { key: "balance", label: "Saldo", num: true, render: (a) => amount(a.balance) },
            { key: "share", label: "Udział", num: true, render: (a) => M.fmtShare(a.share ?? M.shareOf(a.balance, d.total)), sort: (a) => Number(a.balance) || 0 },
            { key: "lastActivity", label: "Ostatnia aktywność", render: (a) => (a.lastActivity ? h("span", { title: fmtDate(a.lastActivity) }, fmtAgo(a.lastActivity)) : h("span.muted", "brak ruchu")), sort: (a) => Number(a.lastActivity) || 0 },
            { key: "count7d", label: "Ruchy 7 dni", num: true, render: (a) => fmtInt(a.count7d ?? 0) },
            { key: "in7d", label: "Wpływy 7 dni", num: true, render: (a) => (a.in7d ? amount(a.in7d, "pos-amt") : "0 md") },
            { key: "out7d", label: "Wypływy 7 dni", num: true, render: (a) => (a.out7d ? amount(a.out7d, "neg") : "0 md") },
          ],
          rows,
          sort: { key: "balance", dir: "desc" },
          onRowClick: (a) => ctx.go("/skarbswiata/konto/" + encodeURIComponent(a.account)),
          emptyText: "Żadne konto nie pasuje do filtrów.",
          cls: "ws-accounts",
        }),
      );
    };
    search.addEventListener(
      "input",
      debounce(() => {
        st.query = search.value.trim().slice(0, 64);
        sync();
        drawTable();
      }, 250),
    );
    drawChips();
    drawTable();
    add(
      body,
      card(
        null,
        h("div.ws-card-head", h("h3.card-title", `Wszystkie konta (${fmtInt(all.length)})`), csvButton("Eksportuj CSV", () => downloadCsv("konta", M.ACCOUNT_CSV_HEADERS, M.filterAccounts(all, st).map((a) => M.accountCsvRow(a, labels))))),
        h("p.muted.small", `Ruchy, wpływy i wypływy liczone z ostatnich ${d.window?.days ?? 7} dni. Kliknij wiersz, aby zobaczyć historię konta.`),
        h("div.log-filters", chipsBox, h("div.search-row.log-search-row", icon("search"), search, info)),
        slot,
      ),
    );
  },
};

// ================================================================ wspólne: tabela transakcji

function txColumns(labels, { perspective = null } = {}) {
  const cols = [
    { key: "time", label: "Czas", render: (r) => h("span.ws-time", { title: M.csvTime(r.time) }, fmtDate(r.time)) },
    {
      key: "route",
      label: "Od → do",
      render: (r) =>
        h(
          "div.ws-route",
          accountLink(r.from, r.from ? r.fromLabel : "(emisja)", r.fromType, labels.typeLabels),
          h("span.ws-arrow", { "aria-label": "do" }, "→"),
          accountLink(r.to, r.to ? r.toLabel : "(umorzenie)", r.toType, labels.typeLabels),
          r.type && r.type !== "TRANSFER" ? h("span.ws-cat.ws-cat-old", M.labelOf(r.type, null, M.FLOW_TYPE_LABELS)) : null,
        ),
    },
    perspective
      ? { key: "amount", label: "Kwota", num: true, render: (r) => signedAmount(r.to === perspective ? r.amount : -r.amount) }
      : { key: "amount", label: "Kwota", num: true, render: (r) => amount(r.amount) },
    { key: "category", label: "Kategoria", render: (r) => catBadge(r.category, r.categoryLabel, labels.categoryLabels) },
    { key: "plugin", label: "Plugin", render: (r) => r.plugin || null },
    { key: "reason", label: "Powód", cls: "ws-reason", render: (r) => r.reason || null },
    {
      key: "after",
      label: "Saldo po",
      num: true,
      render: (r) =>
        perspective
          ? amount(r.to === perspective ? r.toAfter : r.fromAfter)
          : h("div.ws-after", r.from ? h("span", { title: "Nadawca po operacji" }, "od: ", amount(r.fromAfter)) : null, r.to ? h("span", { title: "Odbiorca po operacji" }, "do: ", amount(r.toAfter)) : null),
    },
  ];
  return cols;
}

// ================================================================ Przepływy

function flowsResults(slot, d, f, ctx, refetch, accounts) {
  const labels = { typeLabels: d.typeLabels || {}, categoryLabels: d.categoryLabels || {} };
  const tl = (k) => M.typeLabel(k, labels.typeLabels);
  const days = M.fillDaily(d.daily, Number(d.from) || f.from, Number(d.to) || f.to);
  const sumIn = days.reduce((s, x) => s + x.in, 0);
  const sumOut = days.reduce((s, x) => s + x.out, 0);
  const perAccount = !!f.account;
  const accLabel = perAccount ? (accounts.find((a) => a.account === f.account) || {}).owner || f.account : null;

  clear(slot);
  add(
    slot,
    h(
      "div.grid-stats.ws-kpis",
      kpi("Transakcje", fmtInt(d.total), `${fmtDate(d.from)} – ${fmtDate(d.to)}`),
      kpi("Obrót", amount(d.sumAmount), "suma kwot pasujących operacji"),
      kpi(perAccount ? "Wpływy na konto" : "Wypłaty ze świata", amount(sumIn, "pos-amt"), perAccount ? accLabel : "z budżetów świata do gospodarki"),
      kpi(perAccount ? "Wypływy z konta" : "Wpływy do świata", amount(sumOut, "neg"), perAccount ? accLabel : "z gospodarki do budżetów świata"),
    ),
  );
  if (d.oldest && f.from < d.oldest) add(slot, h("p.muted.small", `Najstarszy zachowany wpis: ${fmtDate(d.oldest)} (retencja ${d.retentionDays ?? "?"} dni).`));

  // wykres dzienny według kategorii
  const stacked = M.stackCategories(days, d.categories, 8);
  const slotOf = new Map(stacked.keys.map((k, i) => [k, k === "_rest" ? "ws-c-rest" : SLOT_CLASSES[i % SLOT_CLASSES.length]]));
  const catSeries = stacked.keys.map((k) => ({ key: k, label: k === "_rest" ? "Pozostałe kategorie" : M.categoryLabel(k, labels.categoryLabels), cls: slotOf.get(k), sign: 1 }));
  const inOutSeries = [
    { key: "in", label: perAccount ? "Wpływy" : "Wypłaty ze świata", cls: "ws-in", sign: 1 },
    { key: "out", label: perAccount ? "Wypływy" : "Wpływy do świata", cls: "ws-out", sign: -1 },
  ];
  add(
    slot,
    h(
      "div.grid-cards.ws-charts",
      card(
        "Obrót dzienny według kategorii",
        dailyBars({ days: stacked.days, series: catSeries, fmt: formatMoney, desc: `Obrót dzienny według kategorii, ${days.length} dni, razem ${formatMoney(d.sumAmount)}`, totalLabel: "Obrót dnia" }),
      ),
      card(
        perAccount ? "Wpływy i wypływy konta" : "Świat ↔ gospodarka",
        dailyBars({ days: days.map((x) => ({ t: x.t, day: x.day, values: { in: x.in, out: x.out } })), series: inOutSeries, fmt: formatMoney, desc: perAccount ? "Dzienne wpływy (w górę) i wypływy (w dół) konta" : "Dzienne wypłaty z budżetów świata (w górę) i wpływy do nich (w dół)" }),
        h("p.muted.small", perAccount ? "W górę: pieniądze wpływające na konto; w dół: wypływające." : "W górę: pieniądze wypłacane z budżetów świata (rezerwa, budżet operacyjny, kupcy, obywatele NPC) do gospodarki; w dół: wracające do nich."),
      ),
    ),
  );

  // Sankey + macierz
  const mt = M.flowMatrixTable(d.matrix);
  const matrixTbl = mt.rows.length
    ? h(
        "div.table-wrap.ws-matrix-wrap",
        h(
          "table.tbl.ws-matrix",
          h("caption.muted.small", "Wiersze: typ nadawcy, kolumny: typ odbiorcy. Intensywność tła rośnie z kwotą."),
          h("thead", h("tr", h("th", { scope: "col" }, "Od \\ Do"), mt.cols.map((c) => h("th.num", { scope: "col" }, tl(c))), h("th.num", { scope: "col" }, "Razem"))),
          h(
            "tbody",
            mt.rows.map((r, i) =>
              h(
                "tr",
                h("th", { scope: "row" }, typeBadge(r, labels.typeLabels)),
                mt.cols.map((c) => {
                  const v = mt.get(r, c);
                  const lvl = M.heatLevel(v?.amount, mt.max);
                  return h("td.num.ws-heat.ws-heat-" + lvl, { "data-label": "→ " + tl(c), title: v ? `${tl(r)} → ${tl(c)}: ${formatMoney(v.amount)}, ${v.count} operacji` : "" }, v ? h("span", formatMoney(v.amount), h("small.muted", ` · ${fmtInt(v.count)}`)) : h("span.muted", "—"));
                }),
                h("td.num", { "data-label": "Razem" }, h("strong", formatMoney(mt.rowTotals[i]))),
              ),
            ),
          ),
          h("tfoot", h("tr", h("th", { scope: "row" }, "Razem"), mt.colTotals.map((v, i) => h("td.num", { "data-label": "→ " + tl(mt.cols[i]) }, formatMoney(v))), h("td.num", { "data-label": "Razem" }, h("strong", formatMoney(mt.total))))),
        ),
      )
    : empty("Brak przepływów między kontami.");
  add(
    slot,
    card(
      "Przepływy między typami kont",
      sankeyChart({ matrix: d.matrix, labelOf: tl, fmt: formatMoney, title: null }),
      h("details.chart-table.ws-matrix-details", { open: true }, h("summary", "Macierz przepływów (tabela)"), matrixTbl),
    ),
  );

  // kategorie, top konta, pluginy
  const catRows = Array.isArray(d.categories) ? d.categories : [];
  const vol = catRows.reduce((s, c) => s + (Number(c.volume) || 0), 0) || 1;
  const topTable = (rows, title) =>
    card(
      title,
      table({
        columns: [
          { key: "label", label: "Konto", render: (r) => accountLink(r.account, r.label, r.type, labels.typeLabels), sort: (r) => M.fold(r.label) },
          { key: "amount", label: "Kwota", num: true, render: (r) => amount(r.amount) },
          { key: "count", label: "Operacje", num: true, render: (r) => fmtInt(r.count) },
        ],
        rows: Array.isArray(rows) ? rows : [],
        sort: { key: "amount", dir: "desc" },
        emptyText: "Brak danych",
      }),
    );
  add(
    slot,
    h(
      "div.grid-cards.ws-three",
      card(
        "Kategorie",
        table({
          columns: [
            { key: "label", label: "Kategoria", render: (c) => h("button.ws-linkbtn", { type: "button", title: "Filtruj tę kategorię", on: { click: () => refetch({ category: c.key }) } }, slotOf.has(c.key) ? h("span.ws-key." + slotOf.get(c.key)) : h("span.ws-key.ws-c-rest"), h("span", c.label || M.categoryLabel(c.key, labels.categoryLabels))), sort: (c) => M.fold(c.label) },
            { key: "volume", label: "Obrót", num: true, render: (c) => amount(c.volume) },
            { key: "count", label: "Operacje", num: true, render: (c) => fmtInt(c.count) },
            { key: "share", label: "Udział", num: true, render: (c) => M.fmtShare(c.volume / vol), sort: (c) => c.volume },
          ],
          rows: catRows,
          sort: { key: "volume", dir: "desc" },
        }),
      ),
      topTable(d.topSenders, "Najwięcej wysłali"),
      topTable(d.topReceivers, "Najwięcej otrzymali"),
      card(
        "Pluginy źródłowe",
        table({
          columns: [
            { key: "plugin", label: "Plugin", render: (p) => p.plugin || "—" },
            { key: "volume", label: "Obrót", num: true, render: (p) => amount(p.volume) },
            { key: "count", label: "Operacje", num: true, render: (p) => fmtInt(p.count) },
          ],
          rows: Array.isArray(d.plugins) ? d.plugins : [],
          sort: { key: "volume", dir: "desc" },
        }),
      ),
    ),
  );

  // transakcje
  const exportSlot = h("div.ws-export-slot");
  const exportBtn = csvButton(`Eksportuj CSV (${fmtInt(Math.min(d.total, 50000))})`, async (b) => {
    try {
      const rows = await exportAll({
        slot: exportSlot,
        total: d.total,
        btn: b,
        keyOf: (r) => r.seq,
        fetchPage: (p) => readStats("world.stats.flows", M.flowParams(f, p)),
      });
      downloadCsv("przeplywy", M.FLOW_CSV_HEADERS, rows.map((r) => M.flowCsvRow(r, labels)));
    } catch {}
  });
  add(
    slot,
    card(
      null,
      h("div.ws-card-head", h("h3.card-title", `Transakcje (${fmtInt(d.total)})`), exportBtn),
      h("p.muted.small", "Od najnowszych. Eksport pobiera wszystkie pasujące wiersze kolejnymi stronami po 1000 (najwyżej 50 000); plik CSV ma separator „;” i kodowanie UTF-8 z BOM, więc polski Excel otwiera go poprawnie dwuklikiem."),
      exportSlot,
      table({ columns: txColumns(labels, { perspective: f.account || null }), rows: Array.isArray(d.rows) ? d.rows : [], sort: null, emptyText: "Brak transakcji pasujących do filtrów.", cls: "ws-tx" }),
      pager({ total: d.total, offset: d.offset ?? 0, limit: d.limit ?? f.pageSize, onPage: (page) => refetch({ page }, { keepPage: true, scroll: true }) }),
    ),
  );
}

export const przeplywyTab = {
  title: "Przepływy",
  async load(ctx) {
    const f = M.parseFlowFilters(ctx.query, Date.now());
    const [flows, accounts] = await Promise.all([readStats("world.stats.flows", M.flowParams(f)), readStats("world.stats.accounts").catch((e) => (M.isDenied(e) ? Promise.reject(e) : { accounts: [] }))]);
    return { flows, accounts: accounts.accounts || [], f };
  },
  draw(body, data, ctx) {
    let f = data.f;
    const accounts = data.accounts;
    const flows0 = data.flows;
    const typeLabels = flows0.typeLabels || {};
    const catLabels = flows0.categoryLabels || {};
    const results = h("div.ws-results", { "aria-live": "polite" });
    let seq = 0;

    const filtersBox = h("div.ws-filters");
    const refetch = async (patch = {}, { keepPage = false, scroll = false } = {}) => {
      const next = { ...f, ...patch };
      if (!keepPage) next.page = 1;
      f = M.parseFlowFilters(M.serializeFlowFilters(next), Date.now());
      setQuery("/skarbswiata/przeplywy", M.serializeFlowFilters(f));
      drawFilters();
      const my = ++seq;
      results.classList.add("loading");
      results.setAttribute("aria-busy", "true");
      try {
        const d = await readStats("world.stats.flows", M.flowParams(f));
        if (my !== seq || !results.isConnected) return;
        flowsResults(results, d, f, ctx, refetch, accounts);
        if (scroll) results.querySelector(".ws-tx")?.scrollIntoView({ block: "start", behavior: "smooth" });
      } catch (e) {
        if (my !== seq) return;
        if (M.isDenied(e)) return ctx.forceReload();
        add(clear(results), h("div.alert.alert-err", icon("warn"), h("span", e.message || String(e))));
      } finally {
        if (my === seq) {
          results.classList.remove("loading");
          results.removeAttribute("aria-busy");
        }
      }
    };

    const drawFilters = () => {
      const cat = h("select.input.input-sm", { "aria-label": "Kategoria" }, h("option", { value: "" }, "Wszystkie kategorie"), M.CATEGORY_ORDER.map((k) => h("option", { value: k, selected: f.category === k }, M.categoryLabel(k, catLabels))));
      cat.addEventListener("change", () => refetch({ category: cat.value }));
      const typ = h("select.input.input-sm", { "aria-label": "Typ konta (nadawca lub odbiorca)" }, h("option", { value: "" }, "Wszystkie typy kont"), M.TYPE_ORDER.map((k) => h("option", { value: k, selected: f.type === k }, M.typeLabel(k, typeLabels))));
      typ.addEventListener("change", () => refetch({ type: typ.value }));
      const listId = "ws-accounts-list";
      const acc = h("input.input.input-sm", { type: "text", list: listId, value: f.account, placeholder: "id konta, np. player:…", "aria-label": "Konto (nadawca lub odbiorca)", autocomplete: "off" });
      const dl = h("datalist#" + listId, accounts.slice(0, 2000).map((a) => h("option", { value: a.account }, (a.owner || "") + " · " + M.typeLabel(a.type, typeLabels))));
      acc.addEventListener("change", () => refetch({ account: acc.value.trim() }));
      const q = h("input.input.input-sm", { type: "search", value: f.query, placeholder: "Szukaj w powodach, kontach…", maxlength: "64", "aria-label": "Szukaj", autocomplete: "off" });
      q.addEventListener("input", debounce(() => refetch({ query: q.value.trim() }), 450));
      const min = h("input.input.input-sm", { type: "text", value: f.minAmount ? formatMoney(f.minAmount) : "", placeholder: "np. 5 zł", "aria-label": "Minimalna kwota", autocomplete: "off" });
      const minHint = h("span.money-hint");
      const applyMin = () => {
        const v = min.value.trim() ? parseMoney(min.value) : 0;
        minHint.textContent = v === null ? "nieprawidłowa kwota" : "";
        minHint.classList.toggle("bad", v === null);
        if (v !== null && v !== f.minAmount) refetch({ minAmount: v });
      };
      min.addEventListener("change", applyMin);
      const size = h("select.input.input-sm", { "aria-label": "Wierszy na stronę" }, M.PAGE_SIZES.map((n) => h("option", { value: String(n), selected: f.pageSize === n }, n + " / str.")));
      size.addEventListener("change", () => refetch({ pageSize: Number(size.value) }));
      const active = [f.category, f.type, f.account, f.query, f.minAmount > 0].filter(Boolean).length;
      const reset = btn("Wyczyść filtry", { small: true, mut: false }, () => refetch({ category: "", type: "", account: "", query: "", minAmount: 0 }));
      reset.disabled = !active;
      add(
        clear(filtersBox),
        rangePicker(f, (r) => refetch(r)),
        h(
          "div.ws-filter-grid",
          h("label.ws-f", h("span.field-label", "Kategoria"), cat),
          h("label.ws-f", h("span.field-label", "Typ konta"), typ),
          h("label.ws-f", h("span.field-label", "Konto"), acc, dl),
          h("label.ws-f", h("span.field-label", "Szukaj"), q),
          h("label.ws-f", h("span.field-label", "Min. kwota"), min, minHint),
          h("label.ws-f", h("span.field-label", "Na stronę"), size),
        ),
        h(
          "div.ws-filter-foot",
          f.account ? h("span.ws-chip-info", "Konto: ", accountLink(f.account, (accounts.find((a) => a.account === f.account) || {}).owner || f.account, (accounts.find((a) => a.account === f.account) || {}).type, typeLabels)) : null,
          h("span.muted.small", active ? `Aktywne filtry: ${active}` : "Bez dodatkowych filtrów"),
          reset,
        ),
      );
    };
    drawFilters();
    add(body, card(null, h("div.ws-card-head", h("h3.card-title", "Filtry przepływów")), filtersBox), results);
    flowsResults(results, flows0, f, ctx, refetch, accounts);
  },
};

// ================================================================ historia konta

const WINDOWS = [7, 30, 90];
export const kontoView = {
  async load(ctx) {
    const days = WINDOWS.includes(Number(ctx.query.get("dni"))) ? Number(ctx.query.get("dni")) : 30;
    return { a: await readStats("world.stats.account", { account: ctx.params[0], days }), days };
  },
  title: (d) => "Konto: " + (d && d.a ? d.a.label || d.a.owner || d.a.account : ""),
  draw(body, data) {
    const d = data.a;
    const labels = { typeLabels: { [d.type]: d.typeLabel } };
    const enc = encodeURIComponent(d.account);
    add(
      body,
      h("p", h("a.link-more", { href: BASE + "/konta" }, "← Wszystkie konta")),
      h(
        "section.card.ws-hero",
        h("div.ws-hero-main", h("div.ws-hero-label", "Historia konta"), h("h2.ws-acc-h", d.label || d.owner || d.account), h("div.ws-hero-sub", typeBadge(d.type, labels.typeLabels), " ", h("code.ws-acc-id", d.account), d.owner && d.owner !== d.label ? h("span", " · właściciel: " + d.owner) : null)),
        h("div.ws-hero-side", h("div.ws-hero-label", "Saldo teraz"), h("div.ws-hero-value", amount(d.balance))),
      ),
      h(
        "div.ws-toolbar",
        h("nav.seg.ws-window", { "aria-label": "Okno czasu" }, WINDOWS.map((n) => h("a.seg-btn" + (n === data.days ? ".on" : ""), { href: `${BASE}/konto/${enc}?dni=${n}`, "aria-current": n === data.days ? "page" : null }, n + " dni"))),
        h("a.btn.btn-sm", { href: `${BASE}/przeplywy?konto=${enc}&zakres=${data.days <= 7 ? "7d" : data.days <= 30 ? "30d" : "90d"}` }, icon("search"), h("span", "Wszystkie transakcje w Przepływach")),
      ),
      h(
        "div.grid-stats.ws-kpis",
        kpi("Wpływy", amount(d.in, "pos-amt"), `ostatnie ${d.days} dni`),
        kpi("Wypływy", amount(d.out, "neg"), `ostatnie ${d.days} dni`),
        kpi("Saldo netto", signedAmount((Number(d.in) || 0) - (Number(d.out) || 0)), "wpływy − wypływy"),
        kpi("Operacje", fmtInt(d.count), d.rows && d.count > d.rows.length ? `w tabeli ${d.rows.length} najnowszych` : ""),
      ),
      card(
        "Saldo w czasie",
        historyCharts(
          (Array.isArray(d.points) ? d.points : []).map((p) => ({ t: p.t, balance: p.balance })),
          [{ key: "balance", label: "Saldo", fmt: formatMoney, tick: (v) => fmtCompact(v / 100) + " zł", noDelta: false }],
          { from: d.from, to: d.to, window: `ostatnie ${d.days} dni`, empty: "Brak operacji w tym oknie.", tableTitle: "Saldo w tabeli (stan na koniec każdego dnia)" },
        ),
      ),
      h(
        "div.grid-cards",
        card(
          "Kategorie",
          table({
            columns: [
              { key: "label", label: "Kategoria", render: (c) => catBadge(c.key, c.label) },
              { key: "in", label: "Wpływy", num: true, render: (c) => amount(c.in, c.in ? "pos-amt" : "") },
              { key: "out", label: "Wypływy", num: true, render: (c) => amount(c.out, c.out ? "neg" : "") },
              { key: "count", label: "Operacje", num: true, render: (c) => fmtInt(c.count) },
            ],
            rows: Array.isArray(d.categories) ? d.categories : [],
            sort: { key: "count", dir: "desc" },
          }),
        ),
        card(
          "Kontrahenci",
          table({
            columns: [
              { key: "label", label: "Konto", render: (c) => accountLink(c.account, c.label, c.type), sort: (c) => M.fold(c.label) },
              { key: "in", label: "Od nich", num: true, render: (c) => amount(c.in, c.in ? "pos-amt" : "") },
              { key: "out", label: "Do nich", num: true, render: (c) => amount(c.out, c.out ? "neg" : "") },
              { key: "count", label: "Operacje", num: true, render: (c) => fmtInt(c.count) },
            ],
            rows: Array.isArray(d.counterparties) ? d.counterparties : [],
            sort: { key: "count", dir: "desc" },
          }),
        ),
      ),
      card(
        null,
        h("div.ws-card-head", h("h3.card-title", `Ostatnie transakcje (${fmtInt((d.rows || []).length)})`), csvButton("Eksportuj CSV", () => downloadCsv("konto-" + d.account, M.FLOW_CSV_HEADERS, (d.rows || []).map((r) => M.flowCsvRow(r, labels))))),
        table({ columns: txColumns(labels, { perspective: d.account }), rows: Array.isArray(d.rows) ? d.rows : [], sort: null, emptyText: "Brak operacji w tym oknie.", cls: "ws-tx" }),
      ),
    );
  },
};

// ================================================================ Gospodarstwa (pula world:households)

export const gospodarstwaTab = {
  title: "Gospodarstwa",
  async load(ctx) {
    const days = M.HOUSEHOLD_WINDOWS.includes(Number(ctx.query.get("dni"))) ? Number(ctx.query.get("dni")) : 30;
    return readStats("world.stats.households", { days });
  },
  draw(body, d) {
    const labels = { typeLabels: {}, categoryLabels: d.categoryLabels || {} };
    const fc = M.householdsForecast(d);
    const avg = d.avg7 || {};
    const totals = d.totals || {};
    const lab = d.labor || {};
    const rawDaily = Array.isArray(d.daily) ? d.daily : [];
    const days = M.fillDaily(rawDaily, Number(d.from), Number(d.to)).map((x) => {
      const raw = rawDaily.find((r) => r.day === x.day) || {};
      const vin = Number(raw.in) || 0;
      const vout = Number(raw.out) || 0;
      return { t: x.t, day: x.day, values: { in: vin, out: vout, net: Number.isFinite(Number(raw.net)) ? Number(raw.net) : vin - vout } };
    });
    const grants = Array.isArray(d.grants) ? d.grants : [];
    const low = fc.kind === "days" && fc.days < 14;
    const acc = d.account || "world:households";
    add(
      body,
      h(
        "section.card.ws-hero",
        h(
          "div.ws-hero-main",
          h("div.ws-hero-label", "Pula gospodarstw (obywatele NPC)"),
          h("div.ws-hero-value", amount(d.balance)),
          h("div.ws-hero-sub", h("code.ws-acc-id", acc), " · ", h("a", { href: accountHref(acc) }, "historia konta")),
        ),
        h(
          "div.ws-hero-side",
          h(
            "div.ws-forecast.ws-forecast-" + fc.kind + (low ? ".ws-forecast-low" : ""),
            icon(fc.kind === "self" ? "check" : fc.kind === "days" ? (low ? "warn" : "clock") : "scroll"),
            h("div", h("div.ws-hero-label", "Prognoza"), h("strong.ws-forecast-text", fc.text), low ? h("div.ws-check-word", "ostrzeżenie: pula wkrótce się wyczerpie") : null),
          ),
          h("p.muted.small", "Na podstawie średniej z ostatnich 7 dni (wpływy − wypływy)."),
        ),
      ),
      h(
        "div.ws-toolbar",
        h("nav.seg.ws-window", { "aria-label": "Okno czasu" }, M.HOUSEHOLD_WINDOWS.map((n) => h("a.seg-btn" + (n === d.days ? ".on" : ""), { href: `${BASE}/gospodarstwa?dni=${n}`, "aria-current": n === d.days ? "page" : null }, n + " dni"))),
        h("span.muted.small", `${fmtDate(d.from)} – ${fmtDate(d.to)}`),
      ),
      h(
        "div.grid-stats.ws-kpis",
        kpi("Średnio wpływy / dzień", avg.in === null || avg.in === undefined ? "—" : amount(avg.in, "pos-amt"), "ostatnie 7 dni"),
        kpi("Średnio wypływy / dzień", avg.out === null || avg.out === undefined ? "—" : amount(avg.out, "neg"), "ostatnie 7 dni"),
        kpi("Średnio netto / dzień", avg.net === null || avg.net === undefined ? "za mało danych" : signedAmount(avg.net), "ostatnie 7 dni", avg.net < 0 ? "ws-kpi-warn" : ""),
        kpi(`Netto w oknie ${d.days} dni`, signedAmount(totals.net), `wpływy ${formatMoney(totals.in)} · wypływy ${formatMoney(totals.out)}`),
      ),
      h(
        "div.grid-cards.ws-charts",
        card(
          "Wpływy i wypływy dziennie",
          dailyBars({
            days,
            series: [
              { key: "in", label: "Wpływy", cls: "ws-in", sign: 1 },
              { key: "out", label: "Wypływy", cls: "ws-out", sign: -1 },
            ],
            line: { key: "net", label: "Netto", cls: "ws-net" },
            fmt: formatMoney,
            desc: `Dzienne wpływy (w górę), wypływy (w dół) i netto puli gospodarstw, ${days.length} dni`,
          }),
        ),
        card(
          "Saldo puli",
          historyCharts((Array.isArray(d.points) ? d.points : []).map((p) => ({ t: p.t, balance: p.balance })), [{ key: "balance", label: "Saldo", fmt: formatMoney, tick: (v) => fmtCompact(v / 100) + " zł" }], {
            from: d.from,
            to: d.to,
            window: `ostatnie ${d.days} dni`,
            empty: "Brak operacji w tym oknie.",
          }),
        ),
      ),
    );
    const L = (v) => amount(Number(v) || 0);
    add(
      body,
      h(
        "div.grid-cards",
        card(
          "Robocizna z budów",
          h("p.muted.small", `Udział ${fmtInt(lab.percent)}% z każdej płatności za budowę przechodzi do obywateli NPC po ${fmtInt(lab.settleAfterHours)} h; zwrot płatności w tym czasie anuluje udział. Dzienny limit i cel puli ograniczają rozliczenia.`),
          h(
            "dl.ws-facts",
            h("dt", "Udział z płatności"),
            h("dd", fmtInt(lab.percent) + "%"),
            h("dt", "Limit dzienny"),
            h("dd", L(lab.dailyCap)),
            h("dt", "Cel puli"),
            h("dd", L(lab.targetBalance), lab.targetBalance ? h("span.muted", ` (teraz ${M.fmtShare(M.shareOf(d.balance, lab.targetBalance))})`) : null),
            h("dt", "Zaległe (czeka na rozliczenie)"),
            h("dd", L(lab.pending)),
            h("dt", "Gotowe do rozliczenia"),
            h("dd", L(lab.due)),
            h("dt", "Dziś rozliczono"),
            h("dd", L(lab.today)),
            h("dt", "Łącznie rozliczono"),
            h("dd", L(lab.settledTotal)),
            h("dt", "Anulowane zwrotami"),
            h("dd", L(lab.cancelledTotal)),
          ),
          Array.isArray(lab.prefixes) && lab.prefixes.length
            ? h("p.muted.small", "Płatności liczone jako budowy: ", lab.prefixes.map((x) => [h("code", x), " "]), Array.isArray(lab.refundPrefixes) && lab.refundPrefixes.length ? h("span", "· zwroty: ", lab.refundPrefixes.map((x) => [h("code", x), " "])) : null)
            : null,
        ),
        card(
          "Kategorie",
          table({
            columns: [
              { key: "label", label: "Kategoria", render: (c) => catBadge(c.key, c.label, labels.categoryLabels) },
              { key: "in", label: "Wpływy", num: true, render: (c) => amount(c.in, c.in ? "pos-amt" : "") },
              { key: "out", label: "Wypływy", num: true, render: (c) => amount(c.out, c.out ? "neg" : "") },
              { key: "count", label: "Operacje", num: true, render: (c) => fmtInt(c.count) },
            ],
            rows: Array.isArray(d.categories) ? d.categories : [],
            sort: { key: "out", dir: "desc" },
          }),
          grants.length
            ? h(
                "div.ws-hh-grants",
                h("h4.ws-sub", "Zasilenia wdrożeniowe tej puli"),
                h("ul.ws-notes", grants.map((g) => h("li", h("strong", g.label), " — ", amount(g.amount), " ", h("span.ws-level.ws-level-" + (g.state === "DONE" ? "ok" : g.state === "NEW" ? "info" : "warn"), M.GRANT_STATE_LABELS[g.state] || g.state)))),
                h("p", h("a.link-more", { href: BASE }, "Zarząd → zasilenia wdrożeniowe")),
              )
            : null,
        ),
      ),
      card(
        "Kontrahenci",
        table({
          columns: [
            { key: "label", label: "Konto", render: (c) => accountLink(c.account, c.label, c.type), sort: (c) => M.fold(c.label) },
            { key: "in", label: "Do puli", num: true, render: (c) => amount(c.in, c.in ? "pos-amt" : "") },
            { key: "out", label: "Z puli", num: true, render: (c) => amount(c.out, c.out ? "neg" : "") },
            { key: "count", label: "Operacje", num: true, render: (c) => fmtInt(c.count) },
          ],
          rows: Array.isArray(d.counterparties) ? d.counterparties : [],
          sort: { key: "count", dir: "desc" },
        }),
      ),
      card(
        null,
        h("div.ws-card-head", h("h3.card-title", `Ostatnie operacje (${fmtInt((d.rows || []).length)})`), csvButton("Eksportuj CSV", () => downloadCsv("gospodarstwa", M.FLOW_CSV_HEADERS, (d.rows || []).map((r) => M.flowCsvRow(r, labels))))),
        table({ columns: txColumns(labels, { perspective: acc }), rows: Array.isArray(d.rows) ? d.rows : [], sort: null, emptyText: "Brak operacji w tym oknie.", cls: "ws-tx" }),
      ),
    );
  },
};
