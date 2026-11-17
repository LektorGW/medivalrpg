// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Statystyki Skarbu Świata: wspólne elementy widoków (plakietki typów i kategorii, linki do historii kont,
// pobieranie CSV, stronicowanie, wybór zakresu dat, pobieranie wszystkich stron do eksportu z paskiem postępu).
import { h, add, clear, icon, toast } from "../dom.js";
import { bar } from "../components.js";
import { formatMoney } from "../money.js";
import { fmtInt } from "../util.js";
import * as api from "../api.js";
import { typeLabel, categoryLabel, cssToken, RANGE_PRESETS, pageInfo, exportPages, toCsv, csvFileName, parseDay, formatDay } from "../world-stats-model.js";

export const BASE = "#/skarbswiata";
export const accountHref = (id) => BASE + "/konto/" + encodeURIComponent(id);

/** Plakietka typu konta: kolorowy znacznik + nazwa (kolor nigdy nie jest jedyną informacją). */
export function typeBadge(type, labels) {
  return h("span.ws-type.ws-t-" + cssToken(type), h("span.ws-key"), h("span", typeLabel(type, labels)));
}
export function catBadge(key, label, labels) {
  return h("span.ws-cat", { title: key || "" }, label || categoryLabel(key, labels));
}
/** Link do historii konta; null → opis operacji sprzed zamkniętej puli. */
export function accountLink(id, label, type, labels, { showId = false } = {}) {
  if (!id) return h("span.ws-acc.ws-acc-none", label || "(brak konta)");
  return h(
    "a.ws-acc",
    { href: accountHref(id), title: id },
    type ? h("span.ws-key.ws-t-" + cssToken(type), { title: typeLabel(type, labels) }) : null,
    h("span.ws-acc-name", label || id),
    showId ? h("code.ws-acc-id", id) : null,
  );
}
export function moneyText(md) {
  return formatMoney(md);
}
/** Kwota z wyrównanymi cyframi i pełną liczbą miedziaków w podpowiedzi. */
export function amount(md, cls = "") {
  return h("span.money.ws-amt" + (cls ? "." + cls : ""), { title: Number.isFinite(Number(md)) ? fmtInt(md) + " md" : "" }, formatMoney(md));
}
export function signedAmount(md) {
  const v = Number(md) || 0;
  return h("span.money.ws-amt" + (v > 0 ? ".pos-amt" : v < 0 ? ".neg" : ""), { title: fmtInt(v) + " md" }, (v > 0 ? "+" : v < 0 ? "−" : "") + formatMoney(Math.abs(v)));
}

/** Zapis pliku CSV przez Blob + <a download> (działa przy CSP bez blob: w default-src — to pobranie, nie zasób). */
export function downloadCsv(kind, headers, rows) {
  const text = toCsv(headers, rows);
  const name = csvFileName(kind);
  const blob = new Blob([text], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = h("a.ws-download", { href: url, download: name, hidden: true });
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    URL.revokeObjectURL(url);
    a.remove();
  }, 5000);
  toast(`Zapisano ${name} (${fmtInt(rows.length)} wierszy)`, "ok");
  return { name, text };
}

export function csvButton(label, onClick) {
  const b = h("button.btn.btn-sm.ws-csv", { type: "button" }, icon("download"), h("span", label));
  b.addEventListener("click", () => onClick(b));
  return b;
}

/**
 * Eksport wszystkich pasujących wierszy: kolejne strony limit 1000 (najwyżej 50 000), pasek postępu w `slot`.
 * fetchPage({offset, limit}) → {rows, total}. Zwraca wiersze (bez duplikatów według keyOf).
 */
export async function exportAll({ slot, total, fetchPage, keyOf, btn }) {
  const pages = exportPages(total);
  const out = [];
  const seen = new Set();
  const main = document.getElementById("main");
  if (main) main.dataset.keep = "1"; // automatyczne odświeżanie nie przerywa eksportu
  if (btn) btn.disabled = true;
  const label = h("span.ws-progress-label", "Pobieranie 0 z " + fmtInt(Math.min(total, 50000)) + " wierszy…");
  const prog = h("div.ws-progress", label, bar(0, "Postęp eksportu"));
  add(clear(slot), prog);
  try {
    for (const [i, p] of pages.entries()) {
      const d = await fetchPage(p);
      for (const r of d.rows || []) {
        const k = keyOf ? keyOf(r) : null;
        if (k !== null && k !== undefined) {
          if (seen.has(k)) continue;
          seen.add(k);
        }
        out.push(r);
      }
      label.textContent = `Pobieranie ${fmtInt(out.length)} z ${fmtInt(Math.min(total, 50000))} wierszy…`;
      prog.replaceChild(bar((i + 1) / pages.length, "Postęp eksportu"), prog.lastChild);
      if (!d.rows || d.rows.length < p.limit) break;
      if (i < pages.length - 1) await new Promise((r) => setTimeout(r, 120)); // limit RPC na sesję
    }
    clear(slot);
    if (total > 50000) add(slot, h("p.muted.small", `Wyeksportowano pierwsze 50 000 z ${fmtInt(total)} pasujących wierszy — zawęź filtry, aby pobrać resztę.`));
    return out;
  } catch (e) {
    add(clear(slot), h("div.alert.alert-err", icon("warn"), h("span", "Eksport przerwany: " + (e.message || String(e)))));
    throw e;
  } finally {
    if (main) delete main.dataset.keep;
    if (btn) btn.disabled = false;
  }
}

/** Stronicowanie (offset/limit z serwera). */
export function pager({ total, offset, limit, onPage }) {
  const pi = pageInfo(total, offset, limit);
  if (pi.pages <= 1) return h("p.muted.small.ws-pager-info", total ? `Wiersze ${fmtInt(pi.from)}–${fmtInt(pi.to)} z ${fmtInt(total)}` : "");
  const b = (label, page, dis, aria) => {
    const el = h("button.btn.btn-sm", { type: "button", disabled: dis, "aria-label": aria }, label);
    el.addEventListener("click", () => onPage(page));
    return el;
  };
  return h(
    "nav.ws-pager",
    { "aria-label": "Strony" },
    b("«", 1, pi.page === 1, "Pierwsza strona"),
    b("‹ Poprzednia", pi.page - 1, pi.page === 1, "Poprzednia strona"),
    h("span.ws-pager-info", `Strona ${fmtInt(pi.page)} z ${fmtInt(pi.pages)} · wiersze ${fmtInt(pi.from)}–${fmtInt(pi.to)} z ${fmtInt(total)}`),
    b("Następna ›", pi.page + 1, pi.page >= pi.pages, "Następna strona"),
    b("»", pi.pages, pi.page >= pi.pages, "Ostatnia strona"),
  );
}

/** Wybór zakresu: presety + „Własny” z dwiema datami. onChange({range, od, do}). */
export function rangePicker(f, onChange) {
  const od = h("input.input.input-sm", { type: "date", value: f.od || "", "aria-label": "Od dnia", max: formatDay(Date.now()) });
  const doo = h("input.input.input-sm", { type: "date", value: f.do || "", "aria-label": "Do dnia", max: formatDay(Date.now()) });
  const custom = h("div.ws-range-custom", { hidden: f.range !== "wlasny" }, h("label", h("span.muted.small", "od"), od), h("label", h("span.muted.small", "do"), doo));
  const apply = () => {
    if (parseDay(od.value) !== null && parseDay(doo.value) !== null) onChange({ range: "wlasny", od: od.value, do: doo.value });
  };
  od.addEventListener("change", apply);
  doo.addEventListener("change", apply);
  const seg = h(
    "div.seg.ws-range",
    { role: "group", "aria-label": "Zakres dat" },
    RANGE_PRESETS.map((p) => {
      const b = h("button.seg-btn" + (f.range === p.id ? ".on" : ""), { type: "button", "aria-pressed": String(f.range === p.id), dataset: { range: p.id } }, p.label);
      b.addEventListener("click", () => onChange({ range: p.id, od: null, do: null }));
      return b;
    }),
    (() => {
      const b = h("button.seg-btn" + (f.range === "wlasny" ? ".on" : ""), { type: "button", "aria-pressed": String(f.range === "wlasny"), dataset: { range: "wlasny" } }, "Własny");
      b.addEventListener("click", () => {
        custom.hidden = false;
        if (!od.value) od.value = formatDay(f.from);
        if (!doo.value) doo.value = formatDay(f.to);
        od.focus();
      });
      return b;
    })(),
  );
  return h("div.ws-range-wrap", seg, custom);
}

export function debounce(fn, ms = 400) {
  let t = null;
  return (...a) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...a), ms);
  };
}

/** Zapisuje filtry w hashu bez przeładowania widoku (bez zdarzenia hashchange). */
export function setQuery(path, qs) {
  const target = "#" + path + (qs ? "?" + qs : "");
  if (location.hash !== target) history.replaceState(null, "", target);
}

/** Odczyt RPC bez zapasu (world.* nigdy nie są cache'owane). */
export async function readStats(method, params = {}) {
  return (await api.read(method, params)).data;
}

/** Pasek udziału w tabeli. */
export function shareBar(share, label) {
  return h("div.ws-share", bar(Math.max(0, Math.min(1, Number(share) || 0)), ""), h("span.ws-share-text", label));
}

export function kpi(label, value, sub, cls = "") {
  return h("div.stat.ws-kpi" + (cls ? "." + cls : ""), h("div.stat-body", h("div.stat-label", label), h("div.stat-value", value), sub ? h("div.stat-sub", sub) : null));
}
