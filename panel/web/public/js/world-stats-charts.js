// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — wykresy Statystyk Skarbu Świata (SVG bez bibliotek): dzienne słupki (skumulowane albo dwukierunkowe
// wpływy/wypływy) i diagram Sankey przepływów między typami kont. Rysowane przy rzeczywistej szerokości
// (ResizeObserver), z podpowiedzią (mysz, dotyk, klawiatura), opisem dla czytników ekranu i legendą.
// Kolory wyłącznie przez klasy CSS (css/world-stats.css) — zgodnie z CSP style-src 'self'.
import { h, add, clear } from "./dom.js";
import { fmtCompact } from "./chart.js";
import { sankeyLayout, sankeyPath, cssToken } from "./world-stats-model.js";

const NS = "http://www.w3.org/2000/svg";
function s(tag, attrs = {}, text) {
  const el = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v !== null && v !== undefined) el.setAttribute(k, String(v));
  if (text !== undefined) el.textContent = text;
  return el;
}

/** Rysuje przy każdej zmianie szerokości kontenera (bez skalowania tekstu). */
function responsive(plot, draw) {
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
}

function niceStep(span, count) {
  const raw = Math.max(1, span) / count;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  return [1, 2, 5, 10].map((m) => m * mag).find((x) => x >= raw) || 10 * mag;
}

const dayFmt = new Intl.DateTimeFormat("pl-PL", { day: "2-digit", month: "2-digit" });
const dayLongFmt = new Intl.DateTimeFormat("pl-PL", { weekday: "short", day: "2-digit", month: "2-digit", year: "numeric" });

/** Legenda: kolorowy znacznik + tekst (identyfikacja nigdy samym kolorem — wartości są też w tabeli). */
export function legend(items) {
  return h(
    "ul.ws-legend",
    items.map((it) => h("li", h("span.ws-key." + it.cls), h("span", it.label), it.value ? h("span.muted", it.value) : null)),
  );
}

/**
 * Dzienne słupki. days: [{t, day, values:{key:n}}]; series: [{key, label, cls, sign:+1|-1}].
 * Serie ze znakiem +1 piętrzą się w górę, −1 w dół (np. wpływy/wypływy). fmt(md) → tekst kwoty.
 */
/** line: opcjonalna linia na tej samej osi (np. netto = wpływy − wypływy), {key, label, cls}. */
export function dailyBars({ days, series, fmt, title, desc, height = 220, totalLabel = "Razem", line = null }) {
  const plot = h("div.ws-plot");
  const tip = h("div.ws-tip", { role: "status", "aria-live": "polite", hidden: true });
  const fig = h("figure.ws-figure", title ? h("figcaption.ws-fig-title", title) : null, plot, legend([...series.map((sr) => ({ label: sr.label, cls: sr.cls })), ...(line ? [{ label: line.label, cls: line.cls + ".ws-key-line" }] : [])]));
  if (!days.length || !days.some((d) => series.some((sr) => (Number(d.values[sr.key]) || 0) > 0))) {
    add(plot, h("div.chart-nodata", "Brak operacji w wybranym zakresie."));
    return fig;
  }
  const up = (d) => series.filter((sr) => sr.sign !== -1).reduce((t, sr) => t + (Number(d.values[sr.key]) || 0), 0);
  const down = (d) => series.filter((sr) => sr.sign === -1).reduce((t, sr) => t + (Number(d.values[sr.key]) || 0), 0);
  const lineVal = (d) => (line ? Number(d.values[line.key]) || 0 : 0);
  const maxUp = Math.max(0, ...days.map(up), ...days.map(lineVal));
  const maxDown = Math.max(0, ...days.map(down), ...days.map((d) => -lineVal(d)));
  const step = niceStep(maxUp + maxDown, maxDown > 0 ? 4 : 4);
  const top = Math.ceil(maxUp / step) * step || (maxDown ? 0 : step);
  const bottom = -Math.ceil(maxDown / step) * step;
  let active = -1;
  let api = null;

  const showTip = (i) => {
    active = i;
    if (api) api.mark(i);
    if (i < 0) {
      tip.hidden = true;
      return;
    }
    const d = days[i];
    clear(tip);
    add(
      tip,
      h("div.ws-tip-title", dayLongFmt.format(new Date(d.t))),
      series.map((sr) => h("div.ws-tip-row", h("span.ws-key." + sr.cls), h("span", sr.label), h("strong", fmt(Number(d.values[sr.key]) || 0)))),
      series.length > 1 && maxDown === 0 && !line ? h("div.ws-tip-row.ws-tip-total", h("span"), h("span", totalLabel), h("strong", fmt(up(d)))) : null,
      line ? h("div.ws-tip-row.ws-tip-total", h("span.ws-key.ws-key-line." + line.cls), h("span", line.label), h("strong", (lineVal(d) > 0 ? "+" : "") + fmt(lineVal(d)))) : null,
    );
    tip.hidden = false;
    const x = api ? api.xOf(i) : 0;
    tip.classList.toggle("left", x > plot.clientWidth / 2);
    tip.style.setProperty("--x", Math.round(x) + "px");
  };

  responsive(plot, (W) => {
    clear(plot);
    const H = height;
    const P = { l: 54, r: 8, t: 10, b: 24 };
    const iw = W - P.l - P.r;
    const ih = H - P.t - P.b;
    const n = days.length;
    const bw = iw / n;
    const barW = Math.max(2, Math.min(42, bw * 0.72));
    const yOf = (v) => P.t + ((top - v) / Math.max(1, top - bottom)) * ih;
    const svg = s("svg", { class: "ws-svg", width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: "img", tabindex: "0", "aria-label": (desc || title || "Wykres dzienny") + ". Strzałki w lewo i w prawo pokazują kolejne dni." });
    for (let v = bottom; v <= top + step / 2; v += step) {
      const y = Math.round(yOf(v)) + 0.5;
      svg.appendChild(s("line", { class: v === 0 ? "chart-base" : "chart-grid", x1: P.l, x2: W - P.r, y1: y, y2: y }));
      svg.appendChild(s("text", { class: "chart-tick", x: P.l - 6, y: y + 4, "text-anchor": "end" }, (v < 0 ? "−" : "") + fmtCompact(Math.abs(v) / 100) + " zł"));
    }
    const every = Math.ceil(46 / bw);
    const cols = [];
    days.forEach((d, i) => {
      const cx = P.l + bw * i + bw / 2;
      const g = s("g", { class: "ws-col" });
      let yUp = 0;
      let yDown = 0;
      for (const sr of series) {
        const v = Number(d.values[sr.key]) || 0;
        if (v <= 0) continue;
        let y0;
        let y1;
        if (sr.sign === -1) {
          y0 = yOf(-yDown);
          yDown += v;
          y1 = yOf(-yDown);
        } else {
          y0 = yOf(yUp);
          yUp += v;
          y1 = yOf(yUp);
        }
        const yy = Math.min(y0, y1);
        const hh = Math.max(1, Math.abs(y1 - y0));
        g.appendChild(s("rect", { class: "ws-bar " + sr.cls, x: (cx - barW / 2).toFixed(1), y: yy.toFixed(1), width: barW.toFixed(1), height: hh.toFixed(1), rx: Math.min(2, barW / 4) }));
      }
      svg.appendChild(g);
      cols.push(g);
      if (i % every === 0) svg.appendChild(s("text", { class: "chart-tick", x: cx, y: H - 6, "text-anchor": "middle" }, dayFmt.format(new Date(d.t))));
    });
    const hit = s("rect", { class: "chart-hit", x: P.l, y: 0, width: Math.max(0, iw), height: H });
    if (line) {
      const pts = days.map((d, i) => [P.l + bw * i + bw / 2, yOf(lineVal(d))]);
      svg.appendChild(s("path", { class: "ws-line " + line.cls, d: pts.map(([x, y], i) => (i ? "L" : "M") + x.toFixed(1) + " " + y.toFixed(1)).join("") }));
      for (const [x, y] of pts) svg.appendChild(s("circle", { class: "ws-line-dot " + line.cls, cx: x.toFixed(1), cy: y.toFixed(1), r: 3 }));
    }
    svg.appendChild(hit);
    const idxOf = (clientX) => {
      const r = svg.getBoundingClientRect();
      return Math.max(0, Math.min(n - 1, Math.floor((clientX - r.left - P.l) / bw)));
    };
    svg.addEventListener("pointermove", (e) => showTip(idxOf(e.clientX)));
    svg.addEventListener("pointerdown", (e) => showTip(idxOf(e.clientX)));
    svg.addEventListener("pointerleave", () => showTip(-1));
    svg.addEventListener("focus", () => showTip(active >= 0 ? active : n - 1));
    svg.addEventListener("blur", () => showTip(-1));
    svg.addEventListener("keydown", (e) => {
      if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
        e.preventDefault();
        showTip(Math.max(0, Math.min(n - 1, (active < 0 ? n - 1 : active) + (e.key === "ArrowLeft" ? -1 : 1))));
      } else if (e.key === "Escape") showTip(-1);
    });
    api = {
      xOf: (i) => P.l + bw * i + bw / 2,
      mark: (i) => cols.forEach((g, k) => g.classList.toggle("dim", i >= 0 && k !== i)),
    };
    plot.appendChild(svg);
    plot.appendChild(tip);
    if (active >= 0) showTip(active);
  });

  // widok tabeli (dostępność — wszystkie wartości bez najeżdżania)
  const tbl = h(
    "table.tbl.chart-tbl",
    h("thead", h("tr", h("th", { scope: "col" }, "Dzień"), series.map((sr) => h("th.num", { scope: "col" }, sr.label)), line ? h("th.num", { scope: "col" }, line.label) : null)),
    h("tbody", days.slice().reverse().map((d) => h("tr", h("td", { "data-label": "Dzień" }, dayLongFmt.format(new Date(d.t))), series.map((sr) => h("td.num", { "data-label": sr.label }, fmt(Number(d.values[sr.key]) || 0))), line ? h("td.num", { "data-label": line.label }, fmt(lineVal(d))) : null))),
  );
  add(fig, h("details.chart-table", h("summary", "Dane w tabeli"), h("div.table-wrap", tbl)));
  return fig;
}

/**
 * Diagram Sankey: lewa kolumna nadawcy (typy kont), prawa odbiorcy, wstęgi proporcjonalne do kwot.
 * matrix: [{from, to, amount, count}]; labelOf(type) → tekst; fmt(md) → tekst.
 */
export function sankeyChart({ matrix, labelOf, fmt, title, onPick }) {
  const plot = h("div.ws-plot.ws-sankey-plot");
  const tip = h("div.ws-tip", { role: "status", "aria-live": "polite", hidden: true });
  const fig = h("figure.ws-figure", title ? h("figcaption.ws-fig-title", title) : null, h("div.ws-sankey-cols", h("span", "Nadawcy"), h("span", "Odbiorcy")), plot);
  const probe = sankeyLayout(matrix, { height: 100 });
  if (!probe.total) {
    add(plot, h("div.chart-nodata", "Brak przepływów między kontami w wybranym zakresie."));
    return fig;
  }
  const nodes = Math.max(probe.left.length, probe.right.length);
  const H = Math.max(220, Math.min(620, nodes * 44));
  responsive(plot, (W) => {
    clear(plot);
    const narrow = W < 520;
    const labelW = Math.max(78, Math.min(170, Math.floor(W * (narrow ? 0.27 : 0.22))));
    const nodeW = 10;
    const x0 = labelW;
    const x1 = W - labelW - nodeW;
    const L = sankeyLayout(matrix, { height: H - 8, gap: 8, minLink: 1 });
    const maxChars = Math.max(6, Math.floor((labelW - 20) / (narrow ? 6.2 : 6.8)));
    const cut = (t) => (t.length > maxChars ? t.slice(0, maxChars - 1) + "…" : t);
    const svg = s("svg", { class: "ws-svg ws-sankey", width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: "group", "aria-label": (title || "Przepływy między typami kont") + `: ${L.links.length} połączeń, razem ${fmt(L.total)}. Tabulatorem przejdziesz po wstęgach.` });
    const linkLayer = s("g", { class: "ws-links" });
    const show = (l, el) => {
      clear(tip);
      add(tip, h("div.ws-tip-title", labelOf(l.from) + " → " + labelOf(l.to)), h("div.ws-tip-row", h("span"), h("span", "Kwota"), h("strong", fmt(l.amount))), h("div.ws-tip-row", h("span"), h("span", "Operacje"), h("strong", String(l.count))), h("div.ws-tip-row", h("span"), h("span", "Udział"), h("strong", ((l.amount / L.total) * 100).toFixed(1).replace(".", ",") + "%")));
      tip.hidden = false;
      const r = el.getBoundingClientRect();
      const pr = plot.getBoundingClientRect();
      const x = r.left - pr.left + r.width / 2;
      tip.classList.toggle("left", x > W / 2);
      tip.style.setProperty("--x", Math.round(x) + "px");
      tip.style.setProperty("--y", Math.round(Math.max(0, r.top - pr.top + r.height / 2 - 30)) + "px");
      svg.classList.add("hovering");
      for (const p of linkLayer.children) p.classList.toggle("on", p === el);
    };
    const hide = () => {
      tip.hidden = true;
      svg.classList.remove("hovering");
      for (const p of linkLayer.children) p.classList.remove("on");
    };
    for (const l of L.links.slice().reverse()) {
      const label = `${labelOf(l.from)} → ${labelOf(l.to)}: ${fmt(l.amount)}, ${l.count} operacji`;
      const p = s("path", { class: "ws-link ws-t-" + cssToken(l.from), d: sankeyPath(l, x0 + nodeW, x1), tabindex: "0", role: "img", "aria-label": label });
      p.appendChild(s("title", {}, label));
      p.addEventListener("pointerenter", () => show(l, p));
      p.addEventListener("pointerdown", () => show(l, p));
      p.addEventListener("pointerleave", hide);
      p.addEventListener("focus", () => show(l, p));
      p.addEventListener("blur", hide);
      if (onPick) {
        p.addEventListener("click", () => onPick(l));
        p.addEventListener("keydown", (e) => e.key === "Enter" && onPick(l));
      }
      linkLayer.appendChild(p);
    }
    svg.appendChild(linkLayer);
    // Etykiety małych węzłów nie mogą na siebie nachodzić: rozsuwamy je (najmniej LABEL_H px między środkami)
    // i łączymy z węzłem cienką linią prowadzącą.
    const LABEL_H = 27;
    const spread = (nodes) => {
      const ys = nodes.map((n) => n.y + Math.max(1.5, n.h) / 2);
      for (let i = 1; i < ys.length; i++) ys[i] = Math.max(ys[i], ys[i - 1] + LABEL_H);
      const maxY = H - 14;
      if (ys.length && ys[ys.length - 1] > maxY) {
        ys[ys.length - 1] = maxY;
        for (let i = ys.length - 2; i >= 0; i--) ys[i] = Math.min(ys[i], ys[i + 1] - LABEL_H);
      }
      return ys.map((y) => Math.max(10, y));
    };
    const node = (n, x, side, ly) => {
      const g = s("g", { class: "ws-node ws-t-" + cssToken(n.key) });
      g.appendChild(s("rect", { class: "ws-node-bar", x, y: n.y.toFixed(1), width: nodeW, height: Math.max(1.5, n.h).toFixed(1), rx: 2 }));
      const anchor = side === "l" ? "end" : "start";
      const cy = n.y + Math.max(1.5, n.h) / 2;
      const moved = Math.abs(ly - cy) > 3;
      const lead = moved ? 16 : 6;
      const tx = side === "l" ? x - lead : x + nodeW + lead;
      if (moved) g.appendChild(s("path", { class: "ws-node-lead", d: side === "l" ? `M${x} ${cy.toFixed(1)}L${x - 6} ${cy.toFixed(1)}L${tx + 3} ${(ly - 4).toFixed(1)}` : `M${x + nodeW} ${cy.toFixed(1)}L${x + nodeW + 6} ${cy.toFixed(1)}L${tx - 3} ${(ly - 4).toFixed(1)}` }));
      const name = labelOf(n.key);
      const t1 = s("text", { class: "ws-node-label", x: tx, y: (ly - 1).toFixed(1), "text-anchor": anchor }, cut(name));
      const t2 = s("text", { class: "ws-node-value", x: tx, y: (ly + 11).toFixed(1), "text-anchor": anchor }, fmtCompact(n.value / 100) + " zł");
      g.appendChild(s("title", {}, `${name}: ${fmt(n.value)}`));
      g.appendChild(t1);
      g.appendChild(t2);
      svg.appendChild(g);
    };
    const ly = spread(L.left);
    const ry = spread(L.right);
    L.left.forEach((n, i) => node(n, x0, "l", ly[i]));
    L.right.forEach((n, i) => node(n, x1, "r", ry[i]));
    plot.appendChild(svg);
    plot.appendChild(tip);
  });
  return fig;
}
