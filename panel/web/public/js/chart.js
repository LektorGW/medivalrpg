// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — lekkie wykresy liniowe SVG (bez bibliotek): małe wielokrotności ze wspólną osią czasu,
// zsynchronizowany crosshair i jeden tooltip ze wszystkimi miarami, obsługa klawiatury, przerwy w danych,
// widok tabeli (dziennie). Rysowane przy rzeczywistej szerokości (ResizeObserver), więc tekst się nie skaluje.
// Opcje dla krótkich okien (wydajność serwera): oś godzin (axis: "time"), progi ostrzeżeń jako przerywane linie
// (series.thresholds) i pasma ostrzeżeń w tle (series.warnCodes + point.warn), tabela w krokach opts.tableStepMs.
import { h, add, clear } from "./dom.js";

const NS = "http://www.w3.org/2000/svg";
const DAY = 24 * 3600 * 1000;
const HEIGHT = 150;
const PAD = { l: 50, r: 12, t: 10, b: 24 };

function s(tag, attrs = {}) {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v !== null && v !== undefined) el.setAttribute(k, String(v));
  return el;
}

const compactNf = new Intl.NumberFormat("pl-PL", { notation: "compact", maximumFractionDigits: 1 });
const intNf = new Intl.NumberFormat("pl-PL");
export const fmtCompact = (n) => (Math.abs(n) < 10000 ? intNf.format(Math.round(n * 10) / 10) : compactNf.format(n));

const dayFmt = new Intl.DateTimeFormat("pl-PL", { day: "2-digit", month: "2-digit" });
const wdFmt = new Intl.DateTimeFormat("pl-PL", { weekday: "short" });
const tipFmt = new Intl.DateTimeFormat("pl-PL", { weekday: "short", day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
const hmFmt = new Intl.DateTimeFormat("pl-PL", { hour: "2-digit", minute: "2-digit" });
const tipSecFmt = new Intl.DateTimeFormat("pl-PL", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const MIN = 60 * 1000;
/** Kroki osi czasu (axis: "time"): pełne minuty/godziny czasu lokalnego. */
const TIME_STEPS = [5, 10, 15, 30, 60, 120, 180, 360, 720].map((m) => m * MIN);

/** Znaczniki osi czasu między from a to: najwyżej co ok. 70 px, wyrównane do pełnych kroków czasu lokalnego. */
export function timeTicks(from, to, widthPx) {
  const span = Math.max(1, to - from);
  const want = Math.max(2, Math.floor(widthPx / 70));
  const step = TIME_STEPS.find((st) => span / st <= want) || TIME_STEPS[TIME_STEPS.length - 1];
  const tz = new Date(from).getTimezoneOffset() * MIN;
  const out = [];
  for (let t = Math.ceil((from - tz) / step) * step + tz; t <= to; t += step) out.push(t);
  return out;
}

/** „Ładne” wartości osi: krok 1/2/5 × 10^n. */
function niceTicks(lo, hi, count = 4, minStep = 0) {
  if (!(hi > lo)) hi = lo + 1;
  const raw = (hi - lo) / count;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const steps = minStep ? [1, 2, 5, 10] : [1, 2, 2.5, 5, 10]; // całkowite miary: bez kroku 2,5
  const step = Math.max(minStep, steps.map((m) => m * mag).find((x) => x >= raw) || 10 * mag);
  const start = Math.floor(lo / step) * step;
  const end = Math.ceil(hi / step) * step;
  const out = [];
  for (let v = start; v <= end + step / 1e6; v += step) out.push(Math.round(v * 1e6) / 1e6);
  return out;
}

function num(v) {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

/**
 * Grupa wykresów historii.
 * points: [{t, <key>: number|null, …}] (rosnąco po t), series: [{key, label, fmt(v)→string, tick(v)→string, int?}]
 * opts: {from, to, empty} — zakres osi czasu (ms), komunikat przy braku danych.
 */
export function historyCharts(points, series, opts = {}) {
  const pts = (Array.isArray(points) ? points : []).filter((p) => p && Number.isFinite(Number(p.t))).map((p) => ({ ...p, t: Number(p.t) })).sort((a, b) => a.t - b.t);
  const hasAny = pts.some((p) => series.some((sr) => num(p[sr.key]) !== null));
  if (!hasAny) return h("div.chart-empty", h("p", opts.empty || "Brak danych historycznych."));

  const to = num(opts.to) ?? pts[pts.length - 1].t;
  const from = num(opts.from) ?? Math.min(pts[0].t, to - 7 * DAY);
  const timeAxis = opts.axis === "time";
  const charts = [];
  let active = -1;

  const tip = h("div.chart-tip", { role: "status", "aria-live": "polite", hidden: true });

  const setIndex = (i, owner) => {
    active = i;
    for (const c of charts) c.mark(i);
    if (i < 0) {
      tip.hidden = true;
      return;
    }
    const p = pts[i];
    clear(tip);
    const warn = Array.isArray(p.warn) && p.warn.length && opts.warnText ? opts.warnText(p.warn) : null;
    add(
      tip,
      h("div.chart-tip-time", (timeAxis ? tipSecFmt : tipFmt).format(new Date(p.t))),
      series.map((sr) => {
        const v = num(p[sr.key]);
        const extra = v !== null && sr.tip ? sr.tip(p) : null;
        return h("div.chart-tip-row" + (owner && owner.key === sr.key ? ".on" : ""), h("span.chart-tip-key"), h("strong", v === null ? "—" : sr.fmt(v)), h("span", sr.label), extra ? h("span.chart-tip-extra", extra) : null);
      }),
      warn ? h("div.chart-tip-warn", "⚠ " + warn) : null,
    );
    if (owner) {
      owner.plot.appendChild(tip);
      tip.hidden = false;
      const x = owner.xOf(p.t);
      const w = owner.width;
      tip.classList.toggle("left", x > w / 2);
      tip.style.setProperty("--x", Math.round(x) + "px");
    }
  };

  const grid = h("div.charts");
  for (const sr of series) {
    const c = lineChart(pts, sr, { from, to, timeAxis, windowText: opts.window || "ostatnie 7 dni", onIndex: (i, owner) => setIndex(i, owner), getActive: () => active });
    charts.push(c);
    grid.appendChild(c.el);
  }

  return h("div.chart-group", grid, opts.table === false ? null : dataTable(pts, series, opts));
}

function lineChart(pts, sr, { from, to, timeAxis, windowText, onIndex, getActive }) {
  const values = pts.map((p) => num(p[sr.key]));
  const valid = values.filter((v) => v !== null);
  const last = [...pts.keys()].reverse().find((i) => values[i] !== null);
  const first = pts.findIndex((_, i) => values[i] !== null);
  const cur = last !== undefined ? values[last] : null;
  const delta = cur !== null && first >= 0 && first !== last ? cur - values[first] : null;

  const plot = h("div.chart-plot");
  const warnOf = (p) => !!(sr.warnCodes && Array.isArray(p.warn) && p.warn.some((c) => sr.warnCodes.includes(c)));
  const warnNow = last !== undefined && warnOf(pts[last]);
  // kolor zmiany: dla TPS spadek jest zły, dla MSPT/NPC zły jest wzrost (sr.lowerIsBetter)
  const deltaCls = delta === null ? "" : (delta < 0) !== !!sr.lowerIsBetter ? ".down" : ".up";
  const head = h(
    "div.chart-head",
    h("h4.chart-title", sr.label),
    h(
      "div.chart-now",
      warnNow ? h("span.chart-warn-badge", { title: "Próg ostrzeżenia przekroczony" }, "⚠ próg") : null,
      cur === null ? h("span.muted", "brak danych") : h("strong", sr.fmt(cur)),
      delta !== null && delta !== 0 && !sr.noDelta ? h("span.chart-delta" + deltaCls, (delta > 0 ? "▲ +" : "▼ ") + sr.fmt(delta)) : null,
    ),
  );
  const el = h("figure.chart" + (warnNow ? ".chart-warn" : ""), head, plot);
  const self = { key: sr.key, el, plot, width: 0, xOf: () => 0, mark: () => {} };

  if (!valid.length) {
    add(plot, h("div.chart-nodata", "Brak danych dla tej miary (" + windowText + ")"));
    return self;
  }

  let lo = Math.min(...valid);
  let hi = Math.max(...valid);
  // podpowiedzi zakresu (np. TPS do 20, MSPT do progu 50 ms) — poszerzają oś, nigdy nie obcinają danych
  if (num(sr.domainMin) !== null) lo = Math.min(lo, sr.domainMin);
  if (num(sr.domainMax) !== null) hi = Math.max(hi, sr.domainMax);
  if (lo >= 0 && lo <= hi * 0.4) lo = 0;
  if (hi === lo) {
    hi = hi + Math.max(1, Math.abs(hi) * 0.1);
    lo = Math.max(0, lo - Math.max(1, Math.abs(lo) * 0.1));
  }
  const ticks = niceTicks(lo, hi, 3, sr.int ? 1 : 0);
  const y0 = ticks[0];
  const y1 = ticks[ticks.length - 1];

  const draw = (width) => {
    self.width = width;
    clear(plot);
    const W = width;
    const H = HEIGHT;
    const iw = W - PAD.l - PAD.r;
    const ih = H - PAD.t - PAD.b;
    const xOf = (t) => PAD.l + ((t - from) / Math.max(1, to - from)) * iw;
    const yOf = (v) => PAD.t + ih - ((v - y0) / Math.max(1e-9, y1 - y0)) * ih;
    self.xOf = xOf;

    const svg = s("svg", { class: "chart-svg", width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: "img", tabindex: "0", "aria-label": `${sr.label}: ${windowText}, teraz ${cur === null ? "brak danych" : sr.fmt(cur)}${warnNow ? ", próg ostrzeżenia przekroczony" : ""}. Strzałki w lewo i w prawo pokazują kolejne ${timeAxis ? "pomiary" : "godziny"}.` });

    // pasma ostrzeżeń (w tle): sąsiednie punkty z ostrzeżeniem łączą się w jedno pasmo
    if (sr.warnCodes) {
      const halfStep = pts.length > 1 ? Math.max(1, (xOf(pts[pts.length - 1].t) - xOf(pts[0].t)) / (pts.length - 1) / 2) : 2;
      let start = -1;
      const flush = (endIdx) => {
        const x1 = Math.max(PAD.l, xOf(pts[start].t) - halfStep);
        const x2 = Math.min(W - PAD.r, xOf(pts[endIdx].t) + halfStep);
        svg.appendChild(s("rect", { class: "chart-warn-band", x: x1.toFixed(1), y: PAD.t, width: Math.max(2, x2 - x1).toFixed(1), height: ih }));
      };
      pts.forEach((p, i) => {
        const w = warnOf(p);
        if (w && start < 0) start = i;
        if (!w && start >= 0) {
          flush(i - 1);
          start = -1;
        }
      });
      if (start >= 0) flush(pts.length - 1);
    }

    // siatka i oś Y
    for (const tv of ticks) {
      const y = Math.round(yOf(tv)) + 0.5;
      svg.appendChild(s("line", { class: tv === y0 ? "chart-base" : "chart-grid", x1: PAD.l, x2: W - PAD.r, y1: y, y2: y }));
      const tx = s("text", { class: "chart-tick", x: PAD.l - 8, y: y + 4, "text-anchor": "end" });
      tx.textContent = sr.tick(tv);
      svg.appendChild(tx);
    }
    // progi ostrzeżeń: przerywane linie z etykietą (tylko gdy mieszczą się na osi)
    let lastLabelY = null;
    for (const th of Array.isArray(sr.thresholds) ? sr.thresholds : []) {
      if (num(th.value) === null || th.value < y0 || th.value > y1) continue;
      const y = Math.round(yOf(th.value)) + 0.5;
      svg.appendChild(s("line", { class: "chart-threshold" + (th.kind === "crit" ? " crit" : ""), x1: PAD.l, x2: W - PAD.r, y1: y, y2: y }));
      // dwa bliskie progi (np. 45 i 50 ms): drugą etykietę pod linią, żeby się nie nakładały
      let ly = y - 3;
      if (lastLabelY !== null && Math.abs(ly - lastLabelY) < 11) ly = y + 11;
      lastLabelY = ly;
      const tl = s("text", { class: "chart-threshold-label", x: W - PAD.r - 2, y: ly, "text-anchor": "end" });
      tl.textContent = th.label || sr.fmt(th.value);
      svg.appendChild(tl);
    }
    if (timeAxis) {
      for (const t of timeTicks(from, to, iw)) {
        const x = Math.round(xOf(t)) + 0.5;
        svg.appendChild(s("line", { class: "chart-day", x1: x, x2: x, y1: PAD.t + ih, y2: PAD.t + ih + 4 }));
        const tx = s("text", { class: "chart-tick", x, y: H - 6, "text-anchor": "middle" });
        tx.textContent = hmFmt.format(new Date(t));
        svg.appendChild(tx);
      }
    }
    // oś X: granice dób (lokalna północ; kolejne dni przez setDate, bo doba przy zmianie czasu ma 23 albo 25 h)
    const days = [];
    const d0 = new Date(from);
    d0.setHours(0, 0, 0, 0);
    d0.setDate(d0.getDate() + 1);
    for (const d = d0; !timeAxis && d.getTime() <= to; d.setDate(d.getDate() + 1)) days.push(d.getTime());
    const every = Math.max(1, Math.ceil(54 / Math.max(1, iw / Math.max(1, days.length)))); // dłuższe okna: co n-ty dzień
    days.forEach((t, i) => {
      const x = Math.round(xOf(t)) + 0.5;
      svg.appendChild(s("line", { class: "chart-day", x1: x, x2: x, y1: PAD.t + ih, y2: PAD.t + ih + 4 }));
      if (i % every) return;
      const tx = s("text", { class: "chart-tick", x, y: H - 6, "text-anchor": "middle" });
      tx.textContent = (iw > 420 ? wdFmt.format(new Date(t)) + " " : "") + dayFmt.format(new Date(t));
      svg.appendChild(tx);
    });

    // linia i wypełnienie — przerwy w danych (null) dzielą linię na odcinki
    const segs = [];
    let curSeg = null;
    pts.forEach((p, i) => {
      const v = values[i];
      if (v === null) return (curSeg = null);
      if (!curSeg) segs.push((curSeg = []));
      curSeg.push([xOf(p.t), yOf(v)]);
    });
    const base = yOf(y0);
    for (const seg of segs) {
      const line = seg.map(([x, y], i) => (i ? "L" : "M") + x.toFixed(1) + " " + y.toFixed(1)).join("");
      if (seg.length > 1) svg.appendChild(s("path", { class: "chart-area", d: line + `L${seg[seg.length - 1][0].toFixed(1)} ${base}L${seg[0][0].toFixed(1)} ${base}Z` }));
      svg.appendChild(s("path", { class: "chart-line", d: seg.length > 1 ? line : `M${seg[0][0] - 1} ${seg[0][1]}h2` }));
    }
    // punkt końcowy
    if (last !== undefined) svg.appendChild(s("circle", { class: "chart-end", cx: xOf(pts[last].t), cy: yOf(values[last]), r: 4 }));

    // warstwa najazdu
    const cross = s("line", { class: "chart-cross", y1: PAD.t, y2: PAD.t + ih, visibility: "hidden" });
    const dot = s("circle", { class: "chart-dot", r: 4.5, visibility: "hidden" });
    svg.appendChild(cross);
    svg.appendChild(dot);
    const hit = s("rect", { class: "chart-hit", x: PAD.l, y: 0, width: Math.max(0, iw), height: H });
    svg.appendChild(hit);

    self.mark = (i) => {
      if (i < 0 || !pts[i]) {
        cross.setAttribute("visibility", "hidden");
        dot.setAttribute("visibility", "hidden");
        return;
      }
      const x = xOf(pts[i].t);
      cross.setAttribute("x1", x);
      cross.setAttribute("x2", x);
      cross.setAttribute("visibility", "visible");
      const v = values[i];
      if (v === null) dot.setAttribute("visibility", "hidden");
      else {
        dot.setAttribute("cx", x);
        dot.setAttribute("cy", yOf(v));
        dot.setAttribute("visibility", "visible");
      }
    };

    const nearest = (clientX) => {
      const r = svg.getBoundingClientRect();
      const t = from + ((clientX - r.left - PAD.l) / Math.max(1, iw)) * (to - from);
      let lo2 = 0;
      let hi2 = pts.length - 1;
      while (hi2 - lo2 > 1) {
        const mid = (lo2 + hi2) >> 1;
        if (pts[mid].t < t) lo2 = mid;
        else hi2 = mid;
      }
      return Math.abs(pts[lo2].t - t) <= Math.abs(pts[hi2].t - t) ? lo2 : hi2;
    };
    svg.addEventListener("pointermove", (e) => onIndex(nearest(e.clientX), self));
    svg.addEventListener("pointerdown", (e) => onIndex(nearest(e.clientX), self));
    svg.addEventListener("pointerleave", () => onIndex(-1, null));
    svg.addEventListener("focus", () => onIndex(getActive() >= 0 ? getActive() : last ?? pts.length - 1, self));
    svg.addEventListener("blur", () => onIndex(-1, null));
    svg.addEventListener("keydown", (e) => {
      const a = getActive();
      if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
        e.preventDefault();
        const n = Math.max(0, Math.min(pts.length - 1, (a < 0 ? pts.length - 1 : a) + (e.key === "ArrowLeft" ? -1 : 1)));
        onIndex(n, self);
      } else if (e.key === "Escape") onIndex(-1, null);
    });
    plot.appendChild(svg);
    if (getActive() >= 0) self.mark(getActive());
  };

  let lastW = 0;
  const ro = new ResizeObserver(() => {
    if (!plot.isConnected) return ro.disconnect();
    const w = Math.floor(plot.clientWidth);
    if (w > 40 && w !== lastW) {
      lastW = w;
      draw(w);
    }
  });
  ro.observe(plot);
  return self;
}

/**
 * Widok tabeli (dostępność — wartości bez najeżdżania): ostatnia próbka każdej doby albo, z opts.tableStepMs,
 * każdego kroku czasu (np. 10 min).
 */
function dataTable(pts, series, opts = {}) {
  const step = num(opts.tableStepMs);
  const byDay = new Map();
  for (const p of pts) {
    const d = new Date(p.t);
    if (step) d.setTime(Math.floor(p.t / step) * step);
    else d.setHours(0, 0, 0, 0);
    const prev = byDay.get(d.getTime()) || {};
    const merged = { ...prev, t: d.getTime() };
    for (const sr of series) if (num(p[sr.key]) !== null) merged[sr.key] = p[sr.key];
    byDay.set(d.getTime(), merged);
  }
  const rows = [...byDay.values()].sort((a, b) => b.t - a.t);
  const tbl = h(
    "table.tbl.chart-tbl",
    h("thead", h("tr", h("th", { scope: "col" }, step ? "Czas" : "Dzień"), series.map((sr) => h("th.num", { scope: "col" }, sr.label)))),
    h(
      "tbody",
      rows.map((r) => {
        const when = step ? (step < 3600 * 1000 ? hmFmt : tipFmt).format(new Date(r.t)) : wdFmt.format(new Date(r.t)) + " " + dayFmt.format(new Date(r.t));
        return h("tr", h("td", { "data-label": step ? "Czas" : "Dzień" }, when), series.map((sr) => h("td.num", { "data-label": sr.label }, num(r[sr.key]) === null ? "—" : sr.fmt(r[sr.key]))));
      }),
    ),
  );
  return h("details.chart-table", h("summary", opts.tableTitle || "Dane w tabeli (stan na koniec każdego dnia)"), h("div.table-wrap", tbl));
}
