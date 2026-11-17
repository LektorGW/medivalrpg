// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Statystyki Skarbu Świata: zakładki „Monety” (spis monet fizycznych i ich miejsca), „Ruchy monet”
// (log ruchów stosów) oraz „Kupiec” (rynek kupca NPC). Dane: world.stats.coins/census/trail/merchant.
import { h, add, clear, icon, modal, toast } from "../dom.js";
import { card, table, empty, btn, bar } from "../components.js";
import { historyCharts, fmtCompact } from "../chart.js";
import { formatMoney } from "../money.js";
import { fmtInt, fmtDate, fmtAgo } from "../util.js";
import * as api from "../api.js";
import * as M from "../world-stats-model.js";
import { amount, signedAmount, downloadCsv, csvButton, exportAll, pager, rangePicker, debounce, setQuery, readStats, kpi } from "./world-stats-common.js";

// ================================================================ Monety

const POLL_MS = 3000;
let pollTimer = null;

function fmtDuration(ms) {
  const s = Math.round((Number(ms) || 0) / 1000);
  if (!s) return "—";
  const m = Math.floor(s / 60);
  return m ? `${m} min ${s % 60} s` : `${s} s`;
}
function where(l) {
  if (l.x === null || l.x === undefined) return l.world ? l.world : h("span.muted", "—");
  return h("span.ws-coords", (l.world ? l.world + " " : "") + `${l.x} ${l.y} ${l.z}`);
}
function denomText(l) {
  const parts = [];
  if (l.gold) parts.push(fmtInt(l.gold) + " zł");
  if (l.silver) parts.push(fmtInt(l.silver) + " sr");
  if (l.copper) parts.push(fmtInt(l.copper) + " md");
  return parts.length ? parts.join(" · ") : "—";
}

async function startCensus(b, ctx) {
  const ok = await modal({
    title: "Wykonać spis monet?",
    body: h(
      "div",
      h("p", "Spis przeszukuje ekwipunki i ender chesty graczy (online i offline), skrzynie, przedmioty na ziemi oraz byty we wszystkich światach i liczy monety fizyczne."),
      h("p", "Działa porcjami w tle, żeby nie obciążać serwera — może potrwać kilka minut. Postęp zobaczysz na tej stronie (odświeżanie co 3 s)."),
      h("p.muted.small", "Spis jest „na gorąco”: monety przenoszone w trakcie mogą zostać policzone dwa razy albo wcale. Wynik porównasz z kontem pokrycia."),
    ),
    actions: [
      { label: "Anuluj", value: null },
      { label: "Rozpocznij spis", kind: "primary", value: true },
    ],
  });
  if (!ok) return;
  b.disabled = true;
  try {
    const r = await api.mutate("world.stats.census", {});
    toast(r && r.message ? r.message : r && r.started ? "Rozpoczęto spis monet" : "Spis już trwa", r && r.started === false ? "err" : "ok");
  } catch (e) {
    toast(e.message || String(e), "err", 6000);
  } finally {
    b.disabled = false;
  }
  ctx.reload();
}

function drawCoins(body, d, ctx) {
  clear(body);
  clearTimeout(pollTimer);
  const running = d.status === "RUNNING";
  const startBtn = btn(running ? "Spis trwa…" : "Wykonaj spis", { kind: "primary", icon: "coins" }, (_, b) => startCensus(b, ctx));
  startBtn.disabled = running;
  const prog = Math.max(0, Math.min(1, Number(d.progress) || 0));
  add(
    body,
    card(
      null,
      h("div.ws-card-head", h("h3.card-title", "Spis monet fizycznych"), startBtn),
      h(
        "div.ws-census-status.ws-census-" + String(d.status || "NONE").toLowerCase(),
        h("span.ws-status-pill", icon(d.status === "DONE" ? "check" : d.status === "RUNNING" ? "clock" : d.status === "FAILED" ? "warn" : "scroll"), M.CENSUS_STATUS_LABELS[d.status] || d.status),
        d.status === "NONE" ? h("span", "Nie wykonano jeszcze spisu. Uruchom go, aby zobaczyć, gdzie są monety.") : null,
        d.status === "DONE" ? h("span", `Ostatni spis: ${fmtDate(d.finishedAt)} (${fmtAgo(d.finishedAt)}), trwał ${fmtDuration(d.durationMs)}.`) : null,
        d.status === "FAILED" ? h("span", `Spis przerwany${d.phase ? ": " + d.phase : ""}. ${d.finishedAt ? fmtDate(d.finishedAt) : ""}`) : null,
        running ? h("span", `Rozpoczęty ${fmtDate(d.startedAt)} · ${d.phase || "w toku"}`) : null,
      ),
      running
        ? h(
            "div.ws-census-progress",
            bar(prog, `${Math.round(prog * 100)}% — ${d.phase || "w toku"}`),
            h("p.muted.small", `Pliki: ${fmtInt(d.filesScanned)} z ${fmtInt(d.filesTotal)} · odświeżam co 3 s. Poniżej wynik poprzedniego spisu.`),
          )
        : null,
    ),
  );
  if (running) {
    pollTimer = setTimeout(async () => {
      if (!body.isConnected) return;
      try {
        const next = await readStats("world.stats.coins");
        if (body.isConnected) drawCoins(body, next, ctx);
      } catch (e) {
        if (M.isDenied(e)) return ctx.forceReload();
        if (body.isConnected) pollTimer = setTimeout(() => drawCoins(body, d, ctx), POLL_MS);
      }
    }, POLL_MS);
  }
  if (d.status === "NONE") return;

  const diff = d.difference;
  add(
    body,
    h(
      "section.ws-banner.ws-banner-" + (diff === 0 ? "ok" : diff === null || diff === undefined ? "warn" : "err"),
      { role: diff ? "alert" : "status" },
      icon(diff === 0 ? "check" : "warn"),
      h(
        "div",
        h("strong", diff === 0 ? "Spis zgadza się z pokryciem." : diff === null || diff === undefined ? "Ostrzeżenie: brak wyniku do porównania." : `Rozbieżność: w świecie jest o ${formatMoney(Math.abs(diff))} ${diff < 0 ? "mniej" : "więcej"} monet niż pokrycie na koncie.`),
        h("div.ws-compare", h("span", "Spis: ", amount(d.total)), h("span", "Pokrycie (konto): ", amount(d.cashAccount)), h("span", "Różnica: ", signedAmount(diff ?? 0))),
      ),
    ),
  );

  const den = d.byDenomination || {};
  add(
    body,
    h(
      "div.grid-stats.ws-kpis",
      kpi("Monety w świecie", amount(d.total), `${fmtInt(d.stacks)} stosów w ${fmtInt((d.locations || []).length)} miejscach`),
      kpi("Złote korony", fmtInt(den.gold) + " szt.", "= " + formatMoney((Number(den.gold) || 0) * 100)),
      kpi("Srebrniki", fmtInt(den.silver) + " szt.", "= " + formatMoney((Number(den.silver) || 0) * 10)),
      kpi("Miedziaki", fmtInt(den.copper) + " szt.", "= " + formatMoney(Number(den.copper) || 0)),
    ),
  );

  const kinds = Array.isArray(d.byKind) ? d.byKind : [];
  const tot = Number(d.total) || 1;
  add(
    body,
    card(
      "Według rodzaju miejsca",
      h(
        "div.ws-kind-grid",
        kinds.map((k) =>
          h(
            "div.ws-kind",
            h("div.ws-kind-label", M.coinKindLabel(k.kind, k.label)),
            h("div.ws-kind-value", amount(k.amount)),
            bar(k.amount / tot, M.fmtShare(k.amount / tot)),
            h("div.muted.small", `${fmtInt(k.places)} miejsc · ${fmtInt(k.stacks)} stosów`),
          ),
        ),
      ),
    ),
  );

  // miejsca
  const locs = Array.isArray(d.locations) ? d.locations : [];
  const st = { kind: "", q: "" };
  const chips = h("div.filter-chips", { role: "group", "aria-label": "Rodzaj miejsca" });
  const search = h("input.input.log-search", { type: "search", placeholder: "Szukaj: opis, właściciel, świat…", "aria-label": "Szukaj miejsca", autocomplete: "off" });
  const info = h("span.muted.small.log-count");
  const slot = h("div");
  const filtered = () => {
    const q = M.fold(st.q);
    return locs.filter((l) => (!st.kind || l.kind === st.kind) && (!q || [l.label, l.owner, l.world, l.ownerId, M.coinKindLabel(l.kind)].some((x) => x && M.fold(x).includes(q))));
  };
  const drawChips = () => {
    const counts = new Map();
    for (const l of locs) counts.set(l.kind, (counts.get(l.kind) || 0) + 1);
    const chip = (k, label, n) => {
      const b = h("button.chip-btn" + (st.kind === k ? ".on" : ""), { type: "button", "aria-pressed": String(st.kind === k) }, h("span", label), h("span.chip-n", fmtInt(n)));
      b.addEventListener("click", () => {
        st.kind = k;
        drawChips();
        drawTable();
      });
      return b;
    };
    add(clear(chips), chip("", "Wszystkie", locs.length), M.COIN_KIND_ORDER.filter((k) => counts.has(k)).map((k) => chip(k, M.coinKindLabel(k), counts.get(k))));
  };
  const drawTable = () => {
    const rows = filtered();
    info.textContent = `Pokazano ${fmtInt(rows.length)} z ${fmtInt(locs.length)} miejsc · ${formatMoney(rows.reduce((s, l) => s + (Number(l.amount) || 0), 0))}`;
    add(
      clear(slot),
      table({
        columns: [
          { key: "kind", label: "Rodzaj", render: (l) => h("span.ws-cat", M.coinKindLabel(l.kind, l.kindLabel)), sort: (l) => M.COIN_KIND_ORDER.indexOf(l.kind) },
          { key: "label", label: "Opis", render: (l) => l.label || null },
          { key: "owner", label: "Właściciel", render: (l) => l.owner || null },
          { key: "where", label: "Miejsce (świat x y z)", render: where, sort: (l) => (l.world || "") + (l.x ?? "") },
          { key: "amount", label: "Kwota", num: true, render: (l) => amount(l.amount) },
          { key: "stacks", label: "Stosy", num: true, render: (l) => fmtInt(l.stacks) },
          { key: "denom", label: "Nominały", render: denomText, sort: false, cls: "ws-nowrap" },
          { key: "source", label: "Źródło", render: (l) => h("span.ws-src.ws-src-" + M.cssToken(l.source), { title: M.SOURCE_LABELS[l.source] || "" }, l.source || "—") },
        ],
        rows,
        sort: { key: "amount", dir: "desc" },
        emptyText: "Żadne miejsce nie pasuje do filtrów.",
        cls: "ws-locs",
      }),
    );
  };
  search.addEventListener(
    "input",
    debounce(() => {
      st.q = search.value.trim();
      drawTable();
    }, 250),
  );
  drawChips();
  drawTable();
  add(
    body,
    card(
      null,
      h("div.ws-card-head", h("h3.card-title", `Miejsca monet (${fmtInt(locs.length)})`), csvButton("Eksportuj CSV", () => downloadCsv("monety", M.COIN_CSV_HEADERS, filtered().map(M.coinCsvRow)))),
      d.truncated ? h("div.alert.alert-warn", icon("warn"), h("span", "Lista ucięta do 2000 największych miejsc — sumy powyżej obejmują wszystkie monety.")) : null,
      h("p.muted.small", "LIVE — odczyt z pamięci serwera (wczytane chunki, gracze online); DISK — z pliku regionu albo pliku gracza."),
      h("div.log-filters", chips, h("div.search-row.log-search-row", icon("search"), search, info)),
      slot,
    ),
  );

  const notes = Array.isArray(d.notes) ? d.notes : [];
  const errors = Array.isArray(d.errors) ? d.errors : [];
  add(
    body,
    card(
      "Przebieg spisu",
      h(
        "dl.ws-facts",
        h("dt", "Pliki regionów i graczy"),
        h("dd", `${fmtInt(d.filesScanned)} z ${fmtInt(d.filesTotal)}`),
        h("dt", "Chunki wczytane (LIVE)"),
        h("dd", fmtInt(d.chunksLive)),
        h("dt", "Gracze online / offline"),
        h("dd", `${fmtInt(d.playersOnline)} / ${fmtInt(d.playersOffline)}`),
        h("dt", "Początek"),
        h("dd", fmtDate(d.startedAt)),
      ),
      notes.length ? h("ul.ws-notes", notes.map((n) => h("li", n))) : null,
      errors.length
        ? h(
            "details.ws-errors",
            { open: errors.length <= 3 },
            h("summary", h("span.ws-check-word", "ostrzeżenie"), ` — błędy odczytu plików (${errors.length})`),
            h("ul", errors.map((e) => h("li", h("code", e.file || "?"), " — ", e.error || ""))),
          )
        : h("p.muted.small", "Bez błędów odczytu plików."),
    ),
  );
}

export const monetyTab = {
  title: "Monety",
  load: () => readStats("world.stats.coins"),
  draw(body, d, ctx) {
    const box = h("div.tab-body");
    add(body, box);
    drawCoins(box, d, ctx);
  },
};

// ================================================================ Ruchy monet

function trailResults(slot, d, f, refetch) {
  const kinds = Array.isArray(d.byKind) ? d.byKind : [];
  clear(slot);
  add(
    slot,
    h(
      "div.grid-stats.ws-kpis",
      kpi("Zdarzenia", fmtInt(d.total), `${fmtDate(d.from)} – ${fmtDate(d.to)}`),
      kpi("Przeniesione monety", amount(kinds.reduce((s, k) => s + (Number(k.amount) || 0), 0)), "suma kwot stosów"),
      kpi("Log od", d.since ? fmtDate(d.since) : "—", "najstarsze zachowane zdarzenie"),
      kpi("Pominięte zdarzenia", fmtInt(d.dropped || 0), d.dropped ? "bufor logu był pełny" : "brak", d.dropped ? "ws-kpi-warn" : ""),
    ),
    d.dropped ? h("div.alert.alert-warn", icon("warn"), h("span", `Ostrzeżenie: ${fmtInt(d.dropped)} zdarzeń nie zapisano (przepełniony bufor) — log może być niepełny.`)) : null,
    card(
      "Według rodzaju",
      kinds.length
        ? table({
            columns: [
              { key: "label", label: "Rodzaj", render: (k) => h("button.ws-linkbtn", { type: "button", title: "Filtruj ten rodzaj", on: { click: () => refetch({ kind: k.kind }) } }, M.trailKindLabel(k.kind, k.label)) },
              { key: "count", label: "Zdarzenia", num: true, render: (k) => fmtInt(k.count) },
              { key: "amount", label: "Kwota", num: true, render: (k) => amount(k.amount) },
            ],
            rows: kinds,
            sort: { key: "amount", dir: "desc" },
          })
        : empty("Brak zdarzeń."),
    ),
  );
  const exportSlot = h("div.ws-export-slot");
  add(
    slot,
    card(
      null,
      h(
        "div.ws-card-head",
        h("h3.card-title", `Zdarzenia (${fmtInt(d.total)})`),
        csvButton(`Eksportuj CSV (${fmtInt(Math.min(d.total, 50000))})`, async (b) => {
          try {
            const rows = await exportAll({ slot: exportSlot, total: d.total, btn: b, fetchPage: (p) => readStats("world.stats.trail", M.trailParams(f, p)) });
            downloadCsv("ruchy-monet", M.TRAIL_CSV_HEADERS, rows.map(M.trailCsvRow));
          } catch {}
        }),
      ),
      exportSlot,
      table({
        columns: [
          { key: "time", label: "Czas", render: (r) => h("span.ws-time", { title: M.csvTime(r.time) }, fmtDate(r.time)) },
          { key: "kind", label: "Rodzaj", render: (r) => h("span.ws-cat.ws-trail-" + M.cssToken(r.kind), M.trailKindLabel(r.kind, r.kindLabel)) },
          { key: "player", label: "Gracz", render: (r) => (r.player ? h("button.ws-linkbtn", { type: "button", title: "Filtruj tego gracza", on: { click: () => refetch({ player: r.player }) } }, r.player) : null) },
          { key: "amount", label: "Kwota", num: true, render: (r) => amount(r.amount) },
          { key: "stacks", label: "Stosy", num: true, render: (r) => fmtInt(r.stacks) },
          { key: "where", label: "Miejsce", render: where },
          { key: "target", label: "Cel", render: (r) => r.target || null },
          { key: "note", label: "Uwagi", render: (r) => r.note || null },
        ],
        rows: Array.isArray(d.rows) ? d.rows : [],
        sort: null,
        emptyText: "Brak zdarzeń pasujących do filtrów.",
        cls: "ws-trail",
      }),
      pager({ total: d.total, offset: d.offset ?? 0, limit: d.limit ?? 200, onPage: (page) => refetch({ page }, { keepPage: true }) }),
    ),
  );
}

export const ruchyTab = {
  title: "Ruchy monet",
  async load(ctx) {
    const f = M.parseTrailFilters(ctx.query, Date.now());
    return { f, d: await readStats("world.stats.trail", M.trailParams(f)) };
  },
  draw(body, data, ctx) {
    let f = data.f;
    let seq = 0;
    const results = h("div.ws-results", { "aria-live": "polite" });
    const filtersBox = h("div.ws-filters");
    const refetch = async (patch = {}, { keepPage = false } = {}) => {
      const next = { ...f, ...patch };
      if (!keepPage) next.page = 1;
      f = M.parseTrailFilters(M.serializeTrailFilters(next), Date.now());
      setQuery("/skarbswiata/ruchy", M.serializeTrailFilters(f));
      drawFilters();
      const my = ++seq;
      results.classList.add("loading");
      try {
        const d = await readStats("world.stats.trail", M.trailParams(f));
        if (my === seq && results.isConnected) trailResults(results, d, f, refetch);
      } catch (e) {
        if (my !== seq) return;
        if (M.isDenied(e)) return ctx.forceReload();
        add(clear(results), h("div.alert.alert-err", icon("warn"), h("span", e.message || String(e))));
      } finally {
        if (my === seq) results.classList.remove("loading");
      }
    };
    const drawFilters = () => {
      const player = h("input.input.input-sm", { type: "text", value: f.player, placeholder: "nick albo UUID", "aria-label": "Gracz", autocomplete: "off", maxlength: "36" });
      player.addEventListener("change", () => refetch({ player: player.value.trim() }));
      const kind = h("select.input.input-sm", { "aria-label": "Rodzaj zdarzenia" }, h("option", { value: "" }, "Wszystkie rodzaje"), M.TRAIL_KIND_ORDER.map((k) => h("option", { value: k, selected: f.kind === k }, M.trailKindLabel(k))));
      kind.addEventListener("change", () => refetch({ kind: kind.value }));
      const q = h("input.input.input-sm", { type: "search", value: f.query, placeholder: "Szukaj: cel, uwagi…", maxlength: "64", "aria-label": "Szukaj", autocomplete: "off" });
      q.addEventListener("input", debounce(() => refetch({ query: q.value.trim() }), 450));
      const reset = btn("Wyczyść filtry", { small: true, mut: false }, () => refetch({ player: "", kind: "", query: "" }));
      reset.disabled = !(f.player || f.kind || f.query);
      add(
        clear(filtersBox),
        rangePicker(f, (r) => refetch(r)),
        h("div.ws-filter-grid", h("label.ws-f", h("span.field-label", "Gracz"), player), h("label.ws-f", h("span.field-label", "Rodzaj"), kind), h("label.ws-f", h("span.field-label", "Szukaj"), q)),
        h("div.ws-filter-foot", h("span.muted.small", "Ruchy stosów monet fizycznych (bez numerów seryjnych). Wpłaty i wypłaty pochodzą z księgi, zdarzenia — z dziennika Skarbca."), reset),
      );
    };
    drawFilters();
    add(body, card(null, h("div.ws-card-head", h("h3.card-title", "Filtry ruchów monet")), filtersBox), results);
    trailResults(results, data.d, f, refetch);
  },
};

// ================================================================ Kupiec

const LEVEL_CLS = { NONE: "err", LOW: "warn", OK: "ok", HIGH: "info" };

export const kupiecTab = {
  title: "Kupiec",
  load: () => readStats("world.stats.merchant"),
  draw(body, d) {
    const goods = Array.isArray(d.goods) ? d.goods : [];
    const history = Array.isArray(d.history) ? d.history : [];
    let selected = (goods.find((g) => g.featured) || goods[0] || {}).key;
    if (d.enabled === false) add(body, h("div.alert.alert-warn", icon("warn"), h("span", "Rynek dynamiczny jest wyłączony — kupiec używa stałych cen. Poniższe dane mogą być nieaktualne.")));
    add(
      body,
      d.buying === false
        ? h("section.ws-banner.ws-banner-err", { role: "alert" }, icon("warn"), h("div", h("strong", "Kupiec nie skupuje towarów."), " ", h("span", "Budżet kupca jest pusty albo poniżej progu — gracze nie mogą teraz sprzedawać mu towarów. Zasil budżet „Kupcy” w zakładce Zarząd.")))
        : h("section.ws-banner.ws-banner-ok", { role: "status" }, icon("check"), h("div", h("strong", "Kupiec skupuje towary."), " ", h("span", `Mnożnik cen skupu z budżetu: ${fmtInt(d.budgetFactor)}%.`))),
    );
    const pct = Math.max(0, Number(d.budgetPercent) || M.shareOf(d.budget, d.budgetTarget) * 100);
    const t = d.totals || {};
    add(
      body,
      card(
        "Budżet kupca",
        h("div.ws-budget", h("div.ws-hero-value", amount(d.budget)), h("span.muted", " z celu "), amount(d.budgetTarget), h("span.ws-budget-pct", ` (${fmtInt(Math.round(pct))}%)`)),
        bar(Math.min(1, pct / 100), `Budżet: ${fmtInt(Math.round(pct))}% celu`, pct < 25 ? "war" : pct < 60 ? "warn" : "ok"),
        h("div.grid-stats.ws-kpis", kpi("Wartość zapasu", amount(t.stockValue), "po cenach bazowych"), kpi("Zapłacono dziś", amount(t.paidToday, "neg"), "skup od graczy"), kpi("Otrzymano dziś", amount(t.receivedToday, "pos-amt"), "sprzedaż graczom")),
        h("p.muted.small", d.updatedAt ? `Ceny przeliczone ${fmtDate(d.updatedAt)}` : "", d.periodEndsAt ? ` · następne przeliczenie ${fmtDate(d.periodEndsAt)}` : ""),
      ),
    );
    const chartSlot = h("div");
    const drawChart = () => {
      const g = goods.find((x) => x.key === selected);
      clear(chartSlot);
      const from = history.length ? history[0].t : Date.now() - 7 * 864e5;
      const to = history.length ? history[history.length - 1].t : Date.now();
      add(chartSlot, historyCharts(history.map((p) => ({ t: p.t, budget: p.budget })), [{ key: "budget", label: "Budżet kupca", fmt: formatMoney, tick: (v) => fmtCompact(v / 100) + " zł" }], { from, to, window: "ostatnie 7 dni", table: false, empty: "Brak historii budżetu." }));
      if (g) {
        add(
          chartSlot,
          h("h4.ws-sub", "Towar: " + M.goodLabel(g.key)),
          historyCharts(
            history.map((p) => ({ t: p.t, stock: p.goods?.[g.key]?.stock ?? null, buy: p.goods?.[g.key]?.buy ?? null, sell: p.goods?.[g.key]?.sell || null })),
            [
              { key: "stock", label: "Zapas (szt.)", fmt: (v) => fmtInt(v), tick: (v) => fmtCompact(v), int: true },
              { key: "buy", label: "Cena skupu", fmt: formatMoney, tick: (v) => fmtCompact(v) + " md" },
              { key: "sell", label: "Cena sprzedaży", fmt: formatMoney, tick: (v) => fmtCompact(v) + " md" },
            ],
            { from, to, window: "ostatnie 7 dni", empty: "Brak historii towaru." },
          ),
        );
      }
    };
    const tblSlot = h("div");
    const drawTable = () =>
      add(
        clear(tblSlot),
        table({
          columns: [
            { key: "key", label: "Towar", render: (g) => h("span.ws-good" + (g.key === selected ? ".on" : ""), g.featured ? h("span.ws-star", { title: "Polecany", "aria-label": "polecany" }, "★") : null, h("strong", M.goodLabel(g.key)), h("code.ws-acc-id", g.key)), sort: (g) => M.goodLabel(g.key) },
            { key: "bundle", label: "Paczka", num: true, render: (g) => fmtInt(g.bundle) + " szt." },
            { key: "stock", label: "Zapas / cel", num: true, render: (g) => h("span.ws-stock", h("span", `${fmtInt(g.stock)} / ${fmtInt(g.target)}`), h("span.ws-level.ws-level-" + (LEVEL_CLS[g.stockLevel] || "ok"), g.stockLabel || M.STOCK_LEVEL_LABELS[g.stockLevel] || "—")), sort: (g) => (Number(g.stock) || 0) / Math.max(1, Number(g.target) || 1) },
            { key: "buyPrice", label: "Skup", num: true, render: (g) => (g.buyPrice ? h("span", amount(g.buyPrice), h("small.muted", ` ${fmtInt(g.buyPercent)}%`)) : h("span.ws-level.ws-level-err", "nie skupuje")) },
            { key: "sellPrice", label: "Sprzedaż", num: true, render: (g) => (g.sellPrice ? h("span", amount(g.sellPrice), h("small.muted", ` ${fmtInt(g.sellPercent)}%`)) : h("span.ws-level.ws-level-warn", "nie sprzedaje")) },
            { key: "trend", label: "Trend", num: true, render: (g) => { const tr = M.TREND[g.trend] || M.TREND.FLAT; return h("span.ws-trend.ws-trend-" + M.cssToken(g.trend || "FLAT").toLowerCase(), { title: "Cena skupu względem ~24 h temu: " + tr.label }, tr.arrow + " " + (g.trendPercent > 0 ? "+" : "") + fmtInt(g.trendPercent) + "%"); }, sort: (g) => Number(g.trendPercent) || 0 },
            { key: "boughtToday", label: "Dziś skupione", num: true, render: (g) => fmtInt(g.boughtToday) },
            { key: "soldToday", label: "Dziś sprzedane", num: true, render: (g) => fmtInt(g.soldToday) },
          ],
          rows: goods,
          sort: { key: "key", dir: "asc" },
          onRowClick: (g) => {
            selected = g.key;
            drawTable();
            drawChart();
          },
          emptyText: "Kupiec nie ma towarów.",
          cls: "ws-goods",
        }),
      );
    drawTable();
    drawChart();
    add(
      body,
      card(
        null,
        h("div.ws-card-head", h("h3.card-title", `Towary (${fmtInt(goods.length)})`), csvButton("Eksportuj CSV", () => downloadCsv("kupiec", M.MERCHANT_CSV_HEADERS, goods.map(M.merchantCsvRow)))),
        h("p.muted.small", "Procent przy cenie — względem ceny bazowej. Trend: cena skupu względem ok. 24 h temu. ★ — towar polecany. Kliknij wiersz, aby zobaczyć historię ceny i zapasu."),
        tblSlot,
      ),
      card("Historia (co godzinę, 7 dni)", chartSlot),
    );
  },
};
