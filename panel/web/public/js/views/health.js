// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Administracja → „Wydajność serwera” (admin.health): kafelki stanu, aktywne ostrzeżenia, wykresy TPS / MSPT /
// graczy / NPC / encji / chunków, NPC według pluginów (małe wielokrotności), NPC teraz (rodzaje, światy, kraje)
// i historia ostrzeżeń z ostatniej doby. Odświeżane razem z resztą panelu co 30 s.
import { h, icon, add } from "../dom.js";
import { fmtInt, fmtTime, fmtDate, safeColor } from "../util.js";
import { card, table, countryName, empty } from "../components.js";
import { historyCharts, fmtCompact } from "../chart.js";
import { RANGES, KINDS, fmtDec, level, warnLabel, fmtDuration, overallLevel, healthAvailability, warnCodesText, activePlugins } from "../health-model.js";

const LEVEL_TEXT = { warn: "ostrzeżenie", crit: "krytyczne" };
const nOrNull = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);

/** Kafelek ze stanem progu: kolor + ikona + słowo (nigdy sam kolor). */
function healthStat(label, value, ic, sub, lvl = "ok") {
  return h(
    "div.stat.health-stat" + (lvl !== "ok" ? ".health-" + lvl : ""),
    h("div.stat-ic", icon(lvl === "ok" ? ic : "warn")),
    h(
      "div.stat-body",
      h("div.stat-label", label),
      h("div.stat-value", value),
      lvl !== "ok" ? h("div.health-flag", LEVEL_TEXT[lvl]) : null,
      sub ? h("div.stat-sub", sub) : null,
    ),
  );
}

function rangeSwitch(active) {
  return h(
    "nav.seg.health-range",
    { "aria-label": "Zakres wykresów" },
    RANGES.map((r) => h("a.seg-btn" + (r.id === active ? ".on" : ""), { href: "#/admin/serwer?zakres=" + r.id, "aria-current": r.id === active ? "page" : null }, h("span", r.label))),
  );
}

export function healthTab(body, d) {
  const H = d.health;
  const th = H.thresholds;
  const cur = H.current || {};
  const plugins = H.plugins;

  add(
    body,
    h(
      "div.health-toolbar",
      rangeSwitch(H.range.id),
      h(
        "p.muted.small",
        `Pomiar co ${Math.round(H.intervalMs / 1000)} s`,
        cur.t ? ` · ostatni ${fmtTime(cur.t)}` : "",
        H.resolutionMs && H.range.id !== "1h" ? ` · punkt wykresu = ${Math.round(H.resolutionMs / 60000)} min (średnia, najgorsza wartość w podpowiedzi)` : "",
        d.stale ? " · dane z pamięci (serwer offline)" : "",
      ),
    ),
  );

  if (!H.enabled) {
    add(body, card("Pomiary wyłączone", h("p", "Plugin Panel nie mierzy wydajności (", h("code", "health.enabled: false"), " w ", h("code", "plugins/Panel/config.yml"), ").")));
  }

  // aktywne ostrzeżenia
  const lvl = overallLevel(H.warnings);
  const unavailable = healthAvailability(H, d.stale);
  add(
    body,
    unavailable
      ? h("section.health-banner", { role: "status" }, icon("clock"), h("strong", unavailable))
      : lvl === "ok"
      ? h("section.health-banner.health-ok", { role: "status" }, icon("check"), h("div", h("strong", "Wszystko w normie."), " ", h("span.muted", `Progi: TPS < ${fmtDec(th.tps, 0)}, MSPT > ${fmtDec(th.mspt, 0)} ms` + (th.npc > 0 ? `, NPC > ${fmtInt(th.npc)}` : "") + ".")))
      : h(
          "section.health-banner.health-" + lvl,
          { role: "alert" },
          icon("warn"),
          h(
            "div",
            h("strong", lvl === "crit" ? "Krytyczny spadek wydajności" : "Ostrzeżenia wydajności"),
            h(
              "ul.health-warnings",
              H.warnings.map((w) =>
                h("li", h("span.health-pill.health-" + (w.level === "CRITICAL" ? "crit" : "warn"), w.level === "CRITICAL" ? "krytyczne" : "ostrzeżenie"), h("span", w.text || warnLabel(w.code, plugins)), w.since ? h("span.muted.small", ` od ${fmtTime(w.since)} (${fmtDuration(w.since, null)})`) : null),
              ),
            ),
          ),
        ),
  );

  // kafelki
  const npcPlugins = activePlugins(H.points, plugins, cur);
  const npcSub = !H.npcScan ? "skan NPC wyłączony" : npcPlugins.map((p) => `${p.name} ${fmtInt((cur.npc || {})[p.id] || 0)}`).join(" · ") || "brak NPC";
  add(
    body,
    h(
      "div.grid-stats.grid-stats-6",
      healthStat("TPS", fmtDec(cur.tps, 1), "bolt", nOrNull(cur.tps5) !== null ? `5 min ${fmtDec(cur.tps5, 1)} · 15 min ${fmtDec(cur.tps15, 1)}` : null, level(cur.tps, th.tps, th.tpsCritical, true)),
      healthStat("MSPT", nOrNull(cur.mspt) === null ? "—" : fmtDec(cur.mspt, 1) + " ms", "clock", "średni czas ticka (limit 50 ms)", level(cur.mspt, th.mspt, th.msptCritical)),
      healthStat("Gracze", nOrNull(cur.players) === null ? "—" : fmtInt(cur.players) + (cur.maxPlayers ? " / " + fmtInt(cur.maxPlayers) : ""), "users", "online teraz"),
      healthStat("NPC pluginów", H.npcScan ? fmtInt(cur.npcTotal) : "—", "robot", npcSub, H.npcScan ? level(cur.npcTotal, th.npc, th.npcCritical) : "ok"),
      healthStat("Encje", fmtInt(cur.entities), "swords", nOrNull(cur.helpers) ? `w tym ${fmtInt(cur.helpers)} pomocniczych encji pluginów` : "załadowane, wszystkie światy", level(cur.entities, th.entities, th.entitiesCritical)),
      healthStat("Chunki", fmtInt(cur.chunks), "map", "załadowane, wszystkie światy"),
    ),
  );

  // wykresy główne
  const warnText = (codes) => warnCodesText(codes, plugins);
  const intTick = (v) => fmtCompact(v);
  const main = [
    {
      key: "tps",
      label: "TPS",
      fmt: (v) => fmtDec(v, 1),
      tick: (v) => fmtDec(v, 0),
      domainMax: 20,
      domainMin: Math.max(0, Math.min(th.tpsCritical || th.tps || 15, 15)),
      thresholds: [th.tps > 0 && { value: th.tps, label: "próg " + fmtDec(th.tps, 0) }, th.tpsCritical > 0 && { value: th.tpsCritical, kind: "crit", label: "krytyczny " + fmtDec(th.tpsCritical, 0) }].filter(Boolean),
      warnCodes: ["TPS"],
      tip: (p) => (p.tpsMin !== null && p.tpsMin < p.tps - 0.05 ? "min " + fmtDec(p.tpsMin, 1) : null),
    },
    {
      key: "mspt",
      label: "MSPT (ms)",
      fmt: (v) => fmtDec(v, 1) + " ms",
      tick: (v) => fmtDec(v, 0),
      domainMin: 0,
      domainMax: th.msptCritical > 0 ? th.msptCritical : th.mspt > 0 ? th.mspt : 50,
      thresholds: [th.mspt > 0 && { value: th.mspt, label: "próg " + fmtDec(th.mspt, 0) + " ms" }, th.msptCritical > 0 && { value: th.msptCritical, kind: "crit", label: "krytyczny " + fmtDec(th.msptCritical, 0) + " ms" }].filter(Boolean),
      warnCodes: ["MSPT"],
      lowerIsBetter: true,
      tip: (p) => (p.msptMax !== null && p.msptMax > p.mspt + 0.05 ? "maks. " + fmtDec(p.msptMax, 1) + " ms" : null),
    },
    { key: "players", label: "Gracze online", fmt: (v) => fmtInt(v), tick: intTick, int: true, domainMin: 0 },
    {
      key: "npcTotal",
      label: "NPC pluginów (razem)",
      fmt: (v) => fmtInt(v),
      tick: intTick,
      int: true,
      domainMin: 0,
      thresholds: [th.npc > 0 && { value: th.npc, label: "próg " + fmtInt(th.npc) }, th.npcCritical > 0 && { value: th.npcCritical, kind: "crit", label: "krytyczny " + fmtInt(th.npcCritical) }].filter(Boolean),
      warnCodes: ["NPC"],
      lowerIsBetter: true,
    },
    {
      key: "entities",
      label: "Encje",
      fmt: (v) => fmtInt(v),
      tick: intTick,
      int: true,
      domainMin: 0,
      thresholds: [th.entities > 0 && { value: th.entities, label: "próg " + fmtInt(th.entities) }, th.entitiesCritical > 0 && { value: th.entitiesCritical, kind: "crit", label: "krytyczny " + fmtInt(th.entitiesCritical) }].filter(Boolean),
      warnCodes: ["ENTITIES"],
      lowerIsBetter: true,
    },
    { key: "chunks", label: "Chunki", fmt: (v) => fmtInt(v), tick: intTick, int: true, domainMin: 0, lowerIsBetter: true },
  ];
  const chartOpts = { from: H.from, to: H.to, axis: "time", window: H.range.window, warnText, tableStepMs: H.range.tableStepMs, tableTitle: "Dane w tabeli (ostatni pomiar w każdym przedziale)", empty: "Brak pomiarów w tym zakresie — plugin Panel mierzy co " + Math.round(H.intervalMs / 1000) + " s, pierwsze punkty pojawią się wkrótce." };
  add(
    body,
    card(
      "Wydajność — " + H.range.window,
      h("p.muted.small", "Przerywane linie to progi z configu, pasma w tle — czas aktywnego ostrzeżenia. Najedź na wykres (albo użyj strzałek), żeby zobaczyć wszystkie wartości z danej chwili."),
      historyCharts(H.points, main, chartOpts),
    ),
  );

  // NPC według pluginów
  if (H.npcScan) {
    const idle = plugins.filter((p) => !npcPlugins.includes(p));
    const perPlugin = npcPlugins.map((p) => {
      const limit = Number(th.npcPlugin[p.id]) || 0;
      return {
        key: "npc_" + p.id,
        label: p.name,
        fmt: (v) => fmtInt(v),
        tick: intTick,
        int: true,
        domainMin: 0,
        thresholds: limit > 0 ? [{ value: limit, label: "próg " + fmtInt(limit) }] : [],
        warnCodes: ["NPC_" + p.id.toUpperCase()],
        lowerIsBetter: true,
      };
    });
    add(
      body,
      card(
        "NPC według pluginów",
        h("p.muted.small", "Liczone po znacznikach PDC pluginów w załadowanych chunkach: Ludność — mieszkańcy i budowniczowie, Armia — żołnierze (po jednym „mózgu”) i władcy NPC, Skarbiec — bankierzy i kupcy, Życie — wieśniacy na służbie, Kolej — budowniczowie i maszyniści, Szlaki — woźnice, szyprowie i osły transportowe. Ciała, hitboxy, napisy, siedzenia, łodzie i ładunki liczą się osobno jako encje pomocnicze."),
        perPlugin.length ? historyCharts(H.points, perPlugin, { ...chartOpts, table: false }) : empty("Brak NPC pluginów w załadowanych chunkach.", "robot"),
        idle.length && perPlugin.length ? h("p.muted.small.health-idle", "Bez NPC w tym zakresie: " + idle.map((p) => p.name).join(", ") + ".") : null,
      ),
    );
  }

  // NPC teraz
  if (H.npcScan && H.current) {
    const kinds = cur.kinds || {};
    const worlds = Array.isArray(cur.worlds) ? cur.worlds : [];
    const countries = (Array.isArray(cur.countries) ? cur.countries : []).map((c) => ({ ...c, color: safeColor(c.color), name: c.name || (c.exists === false ? "(usunięty kraj)" : "?") }));
    add(
      body,
      h(
        "div.health-now",
        card(
          "NPC teraz — rodzaje",
          h(
            "dl.health-kinds",
            KINDS.map((k) => [h("dt", k.label), h("dd", fmtInt(kinds[k.key] || 0))]),
          ),
          h("p.muted.small", `Skan NPC: ${fmtDec(cur.scanMs, 1)} ms` + (cur.scannedAt ? `, ${fmtTime(cur.scannedAt)}` : "") + "."),
        ),
        card(
          "Światy",
          table({
            columns: [
              { key: "name", label: "Świat", render: (w) => h("code", w.name) },
              { key: "chunks", label: "Chunki", num: true, render: (w) => fmtInt(w.chunks), sort: (w) => w.chunks },
              { key: "entities", label: "Encje", num: true, render: (w) => fmtInt(w.entities), sort: (w) => w.entities },
              { key: "npc", label: "NPC", num: true, render: (w) => fmtInt(w.npc), sort: (w) => w.npc },
              { key: "players", label: "Gracze", num: true, render: (w) => fmtInt(w.players), sort: (w) => w.players },
            ],
            rows: worlds,
            sort: { key: "entities", dir: "desc" },
            emptyText: "Brak światów",
          }),
        ),
      ),
      card(
        "NPC według krajów" + (cur.countriesTotal > countries.length ? ` (${countries.length} z ${cur.countriesTotal})` : ""),
        h("p.muted.small", "Mieszkańcy (Ludność) oraz żołnierze i władcy (Armia) w załadowanych chunkach. Bankierzy i kupcy Skarbca nie należą do krajów."),
        table({
          columns: [
            { key: "name", label: "Kraj", render: (c) => (c.exists === false ? h("span.muted", c.name) : countryName({ id: c.id, name: c.name, color: c.color, npc: c.npc })), sort: (c) => String(c.name) },
            { key: "citizens", label: "Mieszkańcy", num: true, render: (c) => fmtInt(c.citizens), sort: (c) => c.citizens },
            { key: "soldiers", label: "Żołnierze", num: true, render: (c) => fmtInt(c.soldiers), sort: (c) => c.soldiers },
            { key: "rulers", label: "Władcy", num: true, render: (c) => fmtInt(c.rulers), sort: (c) => c.rulers },
            { key: "total", label: "Razem", num: true, render: (c) => h("strong", fmtInt(c.total)), sort: (c) => c.total },
          ],
          rows: countries,
          sort: { key: "total", dir: "desc" },
          emptyText: "Brak NPC krajów w załadowanych chunkach",
        }),
      ),
    );
  }

  // historia ostrzeżeń
  add(
    body,
    card(
      "Ostrzeżenia — ostatnia doba",
      table({
        columns: [
          { key: "start", label: "Początek", render: (e) => fmtDate(e.start), sort: (e) => e.start },
          { key: "dur", label: "Czas trwania", render: (e) => (e.end == null ? h("span", fmtDuration(e.start, null), " ", h("span.health-pill.health-warn", "trwa")) : fmtDuration(e.start, e.end)), sort: (e) => (e.end ?? Date.now()) - e.start },
          { key: "code", label: "Rodzaj", render: (e) => warnLabel(e.code, plugins), sort: (e) => e.code },
          { key: "level", label: "Poziom", render: (e) => h("span.health-pill.health-" + (e.level === "CRITICAL" ? "crit" : "warn"), e.level === "CRITICAL" ? "krytyczne" : "ostrzeżenie"), sort: (e) => e.level },
          { key: "text", label: "Najgorzej", render: (e) => e.text || "—", sort: false },
        ],
        rows: H.episodes,
        sort: { key: "start", dir: "desc" },
        emptyText: "Brak ostrzeżeń w ostatniej dobie",
      }),
    ),
  );
}
