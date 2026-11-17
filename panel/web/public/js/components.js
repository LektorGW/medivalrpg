// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — wspólne komponenty widoków: tabela (karty na telefonie), pole kwoty, karty, paski, zakładki, akcje.
import { h, icon, toast, add } from "./dom.js";
import { formatMoney, parseMoney, parseSignedMoney } from "./money.js";
import { safeColor } from "./util.js";

export function money(md, cls = "") {
  return h("span.money" + (cls ? "." + cls : ""), { title: Number.isFinite(Number(md)) ? Number(md) + " md" : "" }, formatMoney(md));
}

export function shield(color, size = "") {
  const s = icon("shield", "shield" + (size ? " shield-" + size : ""));
  s.style.setProperty("--c", safeColor(color));
  return s;
}

export function countryName(c, { link = true, npc = true } = {}) {
  const inner = [shield(c.color), h("span.cn-name", c.name), npc && c.npc ? h("span.tag.tag-npc", { title: "Państwo sterowane przez AI" }, "NPC") : null];
  return link && c.id ? h("a.cn", { href: "#/kraj/" + encodeURIComponent(c.id) }, inner) : h("span.cn", inner);
}

export function stat(label, value, ic, sub) {
  return h("div.stat", ic ? h("div.stat-ic", icon(ic)) : null, h("div.stat-body", h("div.stat-label", label), h("div.stat-value", value), sub ? h("div.stat-sub", sub) : null));
}

export function card(title, ...kids) {
  return h("section.card", title ? h("h3.card-title", title) : null, kids);
}

export function bar(frac, label, kind = "") {
  const f = Math.max(0, Math.min(1, Number(frac) || 0));
  const fill = h("div.bar-fill");
  fill.style.setProperty("width", (f * 100).toFixed(1) + "%");
  return h("div.bar" + (kind ? ".bar-" + kind : ""), { role: "progressbar", "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": String(Math.round(f * 100)), "aria-label": label || "" }, h("div.bar-track", fill), label ? h("span.bar-label", label) : null);
}

export function empty(text, ic = "scroll") {
  return h("div.empty", icon(ic), h("p", text));
}

export function tabs(items, active) {
  const nav = h(
    "nav.tabs",
    { "aria-label": "Zakładki" },
    items.map((t) => h("a.tab" + (t.id === active ? ".active" : ""), { href: t.href, "aria-current": t.id === active ? "page" : null }, t.icon ? icon(t.icon) : null, h("span", t.label))),
  );
  // Na wąskim ekranie pasek się przewija — pokaż aktywną zakładkę.
  requestAnimationFrame(() => {
    const a = nav.querySelector(".tab.active");
    if (a && nav.scrollWidth > nav.clientWidth) nav.scrollLeft = Math.max(0, a.offsetLeft - (nav.clientWidth - a.offsetWidth) / 2);
  });
  return nav;
}

/** Pole kwoty z podglądem w formacie Skarbca. */
export function moneyInput({ name = "amount", placeholder = "np. 1 zł 5 sr", signed = false, value = "" } = {}) {
  const input = h("input.input", { type: "text", name, placeholder, autocomplete: "off", inputmode: "text", value: value === "" ? "" : String(value) });
  const hint = h("span.money-hint");
  const parse = () => (signed ? parseSignedMoney(input.value) : parseMoney(input.value));
  const update = () => {
    const v = input.value.trim() ? parse() : null;
    hint.textContent = !input.value.trim() ? "" : v === null ? "nieprawidłowa kwota" : "= " + formatMoney(v);
    hint.classList.toggle("bad", !!input.value.trim() && v === null);
  };
  input.addEventListener("input", update);
  update();
  return { el: h("div.money-field", input, hint), input, value: parse, reset: () => ((input.value = ""), update()) };
}

export function field(label, control, help) {
  return h("label.field", h("span.field-label", label), control, help ? h("span.field-help", help) : null);
}

/** Wykonuje akcję z przycisku: blokada, toast sukcesu/błędu, potem przeładowanie widoku. */
export async function act(btn, fn, okMsg, after) {
  if (btn) {
    if (btn.disabled) return;
    btn.disabled = true;
    btn.classList.add("busy");
  }
  try {
    const r = await fn();
    if (okMsg) toast(typeof okMsg === "function" ? okMsg(r) : okMsg, "ok");
    const main = document.getElementById("main");
    if (main) delete main.dataset.dirty;
    if (after) await after(r);
    return r;
  } catch (e) {
    toast(e.message || String(e), "err", 6000);
    return undefined;
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.classList.remove("busy");
    }
  }
}

export function btn(label, opts = {}, onClick) {
  const b = h("button.btn" + (opts.kind ? ".btn-" + opts.kind : "") + (opts.small ? ".btn-sm" : ""), { type: opts.type || "button", "data-mut": opts.mut === false ? null : "1", title: opts.title }, opts.icon ? icon(opts.icon) : null, h("span", label));
  if (onClick) b.addEventListener("click", (e) => onClick(e, b));
  return b;
}

/**
 * Tabela sortowalna; na telefonie wiersze jako karty (data-label).
 * columns: [{key, label, render(row)→node|string, sort(row)→value, num, cls}]
 */
export function table({ columns, rows, sort = null, onRowClick = null, emptyText = "Brak danych", cls = "" }) {
  let st = sort ? { ...sort } : null;
  const wrap = h("div.table-wrap" + (cls ? "." + cls : ""));
  const draw = () => {
    while (wrap.firstChild) wrap.removeChild(wrap.firstChild);
    if (!rows.length) {
      wrap.appendChild(empty(emptyText));
      return;
    }
    let data = rows.slice();
    if (st) {
      const col = columns.find((c) => c.key === st.key);
      const f = col && (col.sort || ((r) => r[col.key]));
      if (f) {
        data.sort((a, b) => {
          const x = f(a);
          const y = f(b);
          const c = typeof x === "number" && typeof y === "number" ? x - y : String(x ?? "").localeCompare(String(y ?? ""), "pl");
          return st.dir === "asc" ? c : -c;
        });
      }
    }
    const thead = h(
      "thead",
      h(
        "tr",
        columns.map((c) => {
          const sortable = c.sort !== false && sort !== null;
          const active = st && st.key === c.key;
          const th = h("th" + (c.num ? ".num" : "") + (c.cls ? "." + c.cls : ""), { scope: "col", "aria-sort": active ? (st.dir === "asc" ? "ascending" : "descending") : null }, sortable ? h("button.th-sort", { type: "button", on: { click: () => toggle(c.key) } }, c.label, h("span.sort-ind", active ? (st.dir === "asc" ? "▲" : "▼") : "")) : c.label);
          return th;
        }),
      ),
    );
    const tbody = h(
      "tbody",
      data.map((r, idx) => {
        const tr = h(
          "tr" + (onRowClick ? ".clickable" : ""),
          columns.map((c) => {
            const v = c.render ? c.render(r, idx) : r[c.key];
            const isEmpty = v === undefined || v === null || v === "";
            return h("td" + (c.num ? ".num" : "") + (c.cls ? "." + c.cls : "") + (isEmpty && c.cls === "col-actions" ? ".td-empty" : ""), { "data-label": c.label }, isEmpty ? (c.cls === "col-actions" ? "" : "—") : v);
          }),
        );
        if (onRowClick) {
          tr.tabIndex = 0;
          tr.addEventListener("click", (e) => {
            if (e.target.closest("button, a, input, select")) return;
            onRowClick(r);
          });
          tr.addEventListener("keydown", (e) => {
            if (e.key === "Enter") onRowClick(r);
          });
        }
        return tr;
      }),
    );
    // Na telefonie: wybór sortowania nad kartami.
    let mobileSort = null;
    if (sort !== null) {
      const sel = h(
        "select.input.input-sm",
        { "aria-label": "Sortuj według", on: { change: (e) => { const [k, d] = e.target.value.split(":"); st = { key: k, dir: d }; draw(); } } },
        columns.filter((c) => c.sort !== false).flatMap((c) => [
          h("option", { value: c.key + ":desc", selected: st && st.key === c.key && st.dir === "desc" }, c.label + " ↓"),
          h("option", { value: c.key + ":asc", selected: st && st.key === c.key && st.dir === "asc" }, c.label + " ↑"),
        ]),
      );
      mobileSort = h("div.mobile-sort", icon("sort"), h("span", "Sortuj:"), sel);
    }
    add(wrap, mobileSort || "", h("table.tbl", thead, tbody));
  };
  const toggle = (key) => {
    st = st && st.key === key ? { key, dir: st.dir === "asc" ? "desc" : "asc" } : { key, dir: "desc" };
    draw();
  };
  draw();
  return wrap;
}
