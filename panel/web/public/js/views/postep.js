// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — zakładka „Postęp” (plugin Postep): epoka i plakietka, pasek postępu do następnej epoki, czego brakuje,
// cel badań ze ścieżką, drzewko odkryć w kolumnach epok (kategorie w kolorach, kliknięcie = szczegóły i wybór celu),
// inwestycja ze skarbca z podglądem punktów, ranking epok i narzędzia admina. Używana w widoku kraju i państwa NPC.
import { h, icon, modal, confirmDanger, add } from "../dom.js";
import * as api from "../api.js";
import { formatMoney } from "../money.js";
import { fmtInt, fmtAgo, arr, safeColor, pct } from "../util.js";
import { card, bar, empty, act, btn, field, moneyInput, countryName, money } from "../components.js";

export const CATEGORIES = {
  gospodarka: "Gospodarka",
  nauka: "Nauka",
  wojsko: "Wojsko",
  budownictwo: "Budownictwo",
  dyplomacja: "Dyplomacja",
};
export const STATUSES = { OWNED: "Odkryte", AVAILABLE: "Dostępne", LOCKED: "Wymaga odkryć", ERA_LOCKED: "Późniejsza epoka" };
const STATUS_ICON = { OWNED: "check", AVAILABLE: "bolt", LOCKED: "lock", ERA_LOCKED: "lock" };

/** Odczyt `postep.get`, którego błąd nie psuje strony kraju (401 przechodzi dalej). */
export function loadPostep(id) {
  return api.read("postep.get", { id }).then(
    (r) => ({ data: r.data, stale: r.stale }),
    (e) => (e.status === 401 ? Promise.reject(e) : { error: e }),
  );
}

const pts = (v) => {
  const n = Number(v);
  if (!Number.isFinite(n)) return "—";
  return Number.isInteger(n) ? fmtInt(n) : n.toLocaleString("pl-PL", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
};

/** Okrągła plakietka epoki (ikona w kolorze epoki). */
export function eraBadge(era, size = "") {
  if (!era) return null;
  const b = h("span.era-badge" + (size ? ".era-badge-" + size : ""), { title: era.name + (era.years ? " (" + era.years + ")" : "") }, h("span.era-ic", era.icon || "✦"), size === "sm" ? null : h("span.era-name", era.name));
  b.style.setProperty("--era", safeColor(era.color, "#b08d57"));
  return b;
}

/**
 * Rysuje zakładkę. t = { id, name, ctx, reload }, res = wynik loadPostep.
 */
export function postepTab(body, res, t) {
  if (!res || res.error) {
    return add(body, card("Postęp", empty(res && res.error ? "Nie udało się wczytać postępu: " + res.error.message : "Brak danych o postępie.", "warn")));
  }
  const p = res.data || {};
  if (!p.available) return add(body, card("Postęp", empty("Plugin Postep jest niedostępny — epoki i odkrycia nie są śledzone.", "scroll")));
  const access = p.access || {};
  const tree = arr(p.tree);

  add(body, heroCard(p), h("div.grid-cards", missingCard(p), targetCard(p, tree, t, access)), treeCard(p, tree, t, access));
  if (access.invest && p.invest) add(body, investCard(p, t));
  add(body, h("div.grid-cards", rankingCard(p, t)), access.admin ? adminCard(p, tree, t) : null);
}

// ---------------------------------------------------------------- nagłówek epoki

function heroCard(p) {
  const era = p.era || {};
  const next = p.nextEra;
  const hero = h(
    "section.card.era-hero",
    h("div.era-hero-crest", h("span.era-hero-ic", era.icon || "✦")),
    h(
      "div.era-hero-main",
      h("div.era-hero-kicker", "Epoka"),
      h("h2.era-hero-name", era.name || "—", era.years ? h("span.era-hero-years", era.years) : null),
      era.description ? h("p.muted", era.description) : null,
      p.maxEra
        ? h("div.alert.alert-ok", icon("crown"), h("span", "Ostatnia epoka osiągnięta — kraj żyje w roku 2026. Badaj dalej, by zdobyć wszystkie odkrycia."))
        : h(
            "div.era-progress",
            h("div.era-progress-head", h("span", "Postęp do epoki ", eraBadge(next, "inline")), h("strong.era-pct", pct(p.progress || 0))),
            bar(p.progress || 0, null, p.ready ? "ok" : "era"),
            p.ready ? h("p.ok-text.small", icon("check"), " Wszystkie warunki spełnione — nowa epoka zostanie ogłoszona przy najbliższym przeliczeniu.") : null,
          ),
    ),
    h(
      "dl.era-facts",
      h("div", h("dt", "Punkty badań"), h("dd", pts(p.points))),
      h("div", h("dt", "Dziennie"), h("dd.ok-text", "+" + pts(p.daily))),
      h("div", h("dt", "Odkrycia"), h("dd", fmtInt(p.discoveryCount) + " / " + fmtInt(p.discoveryTotal))),
      h("div", h("dt", "W tej epoce od"), h("dd", p.eraSince ? fmtAgo(p.eraSince) : "—")),
    ),
  );
  hero.style.setProperty("--era", safeColor(era.color, "#b08d57"));
  if (next) hero.style.setProperty("--era-next", safeColor(next.color, "#b08d57"));
  return hero;
}

function missingCard(p) {
  if (p.maxEra) return card("Do następnej epoki", h("p.muted", "Nie ma już kolejnych epok."));
  const list = arr(p.missing);
  return card(
    "Do epoki: " + ((p.nextEra && p.nextEra.name) || "—"),
    list.length
      ? h("ul.missing-list", list.map((m) => h("li", icon(/^odkrycie/.test(m) ? "scroll" : /^czas/.test(m) ? "clock" : "users"), h("span", m))))
      : h("p.ok-text", icon("check"), " Niczego nie brakuje."),
    h("p.muted.small", "Potrzebne: wymagane odkrycia bieżącej epoki (★ w drzewku), minimalny czas w epoce i progi ludności oraz poziomu osady."),
  );
}

function targetCard(p, tree, t, access) {
  const tg = p.target;
  if (!tg) {
    return card("Cel badań", h("p.muted", "Brak celu badań — punkty zbierają się w puli."), access.discover ? h("p.small", "Kliknij odkrycie w drzewku poniżej, aby je wybrać.") : null);
  }
  const path = arr(tg.path);
  return card(
    "Cel badań",
    h("div.target-name", icon("search"), h("strong", tg.name)),
    path.length > 1 ? h("ol.target-path", path.map((d) => h("li", d.name, h("span.muted.small", " " + fmtInt(d.cost) + " pkt")))) : null,
    h(
      "dl.facts",
      h("dt", "Koszt ścieżki"),
      h("dd", fmtInt(tg.cost) + " pkt"),
      h("dt", "Brakuje"),
      h("dd", tg.missing > 0 ? fmtInt(Math.ceil(tg.missing)) + " pkt" : "nic — odkrycie przy najbliższym przeliczeniu"),
      h("dt", "Szacowany czas"),
      h("dd", tg.etaDays === 0 ? "teraz" : typeof tg.etaDays === "number" ? "~" + tg.etaDays + " dni" : "brak przyrostu punktów"),
    ),
    bar(tg.cost ? Math.min(1, (tg.cost - tg.missing) / tg.cost) : 0, "Zebrano " + pct(tg.cost ? Math.min(1, (tg.cost - tg.missing) / tg.cost) : 0), "era"),
  );
}

// ---------------------------------------------------------------- drzewko

function treeCard(p, tree, t, access) {
  const eras = arr(p.eras);
  const current = (p.era && p.era.id) || 1;
  const byId = new Map(tree.map((d) => [d.id, d]));
  const legend = h(
    "div.tree-legend",
    h("div.chips", Object.entries(CATEGORIES).map(([k, v]) => h("span.chip.cat-chip.cat-" + k, h("span.cat-dot"), v))),
    h("div.chips", Object.entries(STATUSES).map(([k, v]) => h("span.chip.st-chip.st-" + k, icon(STATUS_ICON[k]), v)), h("span.chip.st-chip", h("span.req-star", "★"), "wymagane do epoki")),
  );
  const cols = eras.map((e) => {
    const items = tree.filter((d) => d.era === e.id);
    const col = h(
      "section.era-col" + (e.id === current ? ".era-col-current" : e.id < current ? ".era-col-past" : ""),
      h("header.era-col-head", h("span.era-col-ic", e.icon || "✦"), h("div", h("h4", e.name), h("span.muted.small", e.years || "")), e.id === current ? h("span.tag.tag-mine", "Teraz") : null),
      h("div.era-col-body", items.length ? items.map((d) => node(d, p, byId, t, access)) : h("p.muted.small", "Brak odkryć.")),
    );
    col.style.setProperty("--era", safeColor(e.color, "#b08d57"));
    return col;
  });
  return card(
    "Drzewko odkryć",
    h("p.muted.small", access.discover ? "Kliknij odkrycie, aby zobaczyć szczegóły i wybrać je jako cel badań (albo odkryć od razu, gdy starcza punktów)." : "Kliknij odkrycie, aby zobaczyć szczegóły. Badania wybiera władca lub doradca."),
    legend,
    h("div.era-cols", cols),
  );
}

function node(d, p, byId, t, access) {
  const target = p.target && p.target.id === d.id;
  const reqNames = arr(d.requires).map((r) => (byId.get(r) || { name: r }).name);
  const b = h(
    "button.tech.cat-" + (CATEGORIES[d.category] ? d.category : "nauka") + ".st-" + d.status + (target ? ".tech-target" : "") + (d.affordable ? ".tech-affordable" : ""),
    { type: "button", "data-id": d.id, "data-mut": null, "aria-label": `${d.name}: ${STATUSES[d.status] || d.status}, ${d.cost} pkt` },
    h("span.tech-top", h("span.tech-status", icon(STATUS_ICON[d.status] || "scroll")), h("span.tech-name", d.name), d.requiredForEra ? h("span.req-star", { title: "Wymagane do następnej epoki" }, "★") : null),
    h("span.tech-meta", h("span.tech-cost", fmtInt(d.cost) + " pkt"), target ? h("span.tech-flag", "cel badań") : d.affordable ? h("span.tech-flag.tech-flag-ok", "stać cię") : null),
    reqNames.length ? h("span.tech-req", "Wymaga: " + reqNames.join(", ")) : null,
  );
  b.addEventListener("click", () => techModal(d, p, byId, t, access));
  return b;
}

async function techModal(d, p, byId, t, access) {
  const reqs = arr(d.requires).map((r) => byId.get(r) || { id: r, name: r, status: "LOCKED" });
  const canAct = access.discover && (d.status === "AVAILABLE" || d.status === "LOCKED");
  const now = d.status === "AVAILABLE" && d.affordable;
  const body = h(
    "div.tech-detail",
    h("div.tech-detail-head", h("span.chip.cat-chip.cat-" + d.category, h("span.cat-dot"), CATEGORIES[d.category] || d.category), h("span.chip.st-chip.st-" + d.status, icon(STATUS_ICON[d.status]), STATUSES[d.status] || d.status), d.requiredForEra ? h("span.chip.st-chip", h("span.req-star", "★"), "wymagane do następnej epoki") : null),
    d.description ? h("p", d.description) : null,
    d.effect ? h("p.tech-effect", icon("bolt"), h("span", d.effect)) : null,
    h("dl.facts", h("dt", "Koszt"), h("dd", fmtInt(d.cost) + " pkt"), h("dt", "Masz"), h("dd", pts(p.points) + " pkt"), h("dt", "Epoka"), h("dd", (arr(p.eras).find((e) => e.id === d.era) || {}).name || "—")),
    reqs.length ? h("div", h("h4.sub", "Wymagania"), h("ul.req-list", reqs.map((r) => h("li" + (r.status === "OWNED" ? ".ok-text" : ""), icon(r.status === "OWNED" ? "check" : "lock"), h("span", r.name))))) : h("p.muted.small", "Bez wymagań."),
    d.status === "LOCKED" && canAct ? h("p.muted.small", "Ustawienie celu zbada najpierw brakujące wymagania — po kolei, w miarę zbierania punktów.") : null,
    !access.discover && d.status !== "OWNED" ? h("p.muted.small", "Badania wybiera władca albo doradca kraju.") : null,
  );
  const actions = [{ label: "Zamknij", value: null }];
  if (canAct) actions.push({ label: now ? "Odkryj teraz" : "Ustaw jako cel badań", kind: "primary", value: "go" });
  const r = await modal({ title: d.name, body, actions });
  if (r === "go") {
    await act(null, () => api.mutate("postep.discover", { id: t.id, discovery: d.id }), (res) => (res && res.message) || "Wybrano badania: " + d.name, t.reload);
  }
}

// ---------------------------------------------------------------- inwestycja

function investCard(p, t) {
  const inv = p.invest;
  const amt = moneyInput({ placeholder: "np. 5 zł" });
  const preview = h("span.invest-preview", "Wpisz kwotę, aby zobaczyć, ile punktów da inwestycja.");
  let seq = 0;
  let timer = null;
  const refresh = () => {
    clearTimeout(timer);
    const v = amt.value();
    if (!amt.input.value.trim() || v === null || v <= 0) {
      preview.textContent = "Wpisz kwotę, aby zobaczyć, ile punktów da inwestycja.";
      preview.classList.remove("ok-text");
      return;
    }
    const my = ++seq;
    timer = setTimeout(async () => {
      try {
        const r = await api.rpc("postep.investPreview", { id: t.id, amount: v });
        if (my !== seq) return;
        preview.textContent = `${formatMoney(v)} → +${pts(r.points)} pkt badań`;
        preview.classList.add("ok-text");
      } catch (e) {
        if (my === seq) preview.textContent = e.message || "Podgląd niedostępny";
      }
    }, 300);
  };
  amt.input.addEventListener("input", refresh);
  const submit = btn("Inwestuj", { kind: "primary", type: "submit", icon: "coins" });
  const form = h(
    "form.inline-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          const v = amt.value();
          if (v === null || v <= 0) return amt.input.classList.add("invalid");
          amt.input.classList.remove("invalid");
          act(submit, () => api.mutate("postep.invest", { id: t.id, amount: v }), (r) => (r && r.message) || "Zainwestowano " + formatMoney(v), t.reload);
        },
      },
    },
    field("Kwota ze skarbca", amt.el),
    submit,
  );
  const presets = h(
    "div.chips.invest-presets",
    arr(inv.presets).map((x) =>
      h(
        "button.chip-btn",
        {
          type: "button",
          "data-mut": "1",
          "data-amount": String(x.amount),
          on: {
            click: () => {
              amt.input.value = x.amountText || formatMoney(x.amount);
              amt.input.dispatchEvent(new Event("input", { bubbles: true }));
              amt.input.focus();
            },
          },
        },
        h("span", x.amountText || formatMoney(x.amount)),
        h("span.chip-n", "+" + pts(x.points)),
      ),
    ),
  );
  return card(
    "Inwestycja w badania",
    h("p.muted.small", "Pieniądze ze skarbca kraju od razu zamieniają się w punkty badań. Każda kolejna moneta danego dnia daje mniej (malejące przychody); licznik zeruje się przy dziennym naliczeniu."),
    h("dl.facts", h("dt", "Skarbiec"), h("dd", money(inv.treasury)), h("dt", "Zainwestowano dziś"), h("dd", money(inv.investedToday))),
    h("div.sub", h("span.field-label", "Szybki wybór (ile punktów da teraz)"), presets),
    form,
    h("p.small", preview),
  );
}

// ---------------------------------------------------------------- ranking i admin

function rankingCard(p, t) {
  const rows = arr(p.ranking);
  return card(
    "Ranking epok",
    rows.length
      ? h(
          "ol.era-ranking",
          rows.slice(0, 10).map((r) => {
            const li = h("li" + (r.id === t.id ? ".mine" : ""), h("span.pos", String(r.pos)), countryName({ id: r.id, name: r.name, color: safeColor(r.color), npc: r.npc }), eraBadge({ name: r.eraName, icon: r.eraIcon, color: r.eraColor }, "sm"), h("span.era-ranking-bar", bar(r.progress || 0, null, "era")), h("span.muted.small", pct(r.progress || 0)));
            li.style.setProperty("--era", safeColor(r.eraColor, "#b08d57"));
            return li;
          }),
        )
      : h("p.muted", "Brak krajów."),
  );
}

function adminCard(p, tree, t) {
  const eraSel = h("select.input", { "aria-label": "Epoka" }, arr(p.eras).map((e) => h("option", { value: String(e.id), selected: p.era && e.id === p.era.id }, `${e.icon || ""} ${e.name}`)));
  const setEra = btn("Ustaw epokę", { kind: "danger", type: "submit" });
  const eraForm = h(
    "form.inline-form",
    {
      on: {
        submit: async (e) => {
          e.preventDefault();
          const era = Number(eraSel.value);
          const name = eraSel.selectedOptions[0] ? eraSel.selectedOptions[0].textContent.trim() : String(era);
          const ok = await confirmDanger({ title: "Zmienić epokę?", text: `Kraj ${t.name} dostanie epokę: ${name}. Wymagane odkrycia wcześniejszych epok zostaną nadane, a czas w epoce liczy się od nowa. Zmiana zostanie ogłoszona w grze.`, confirmLabel: "Ustaw epokę" });
          if (ok) act(setEra, () => api.mutate("admin.postep.setEra", { id: t.id, era }), (r) => (r && r.message) || "Ustawiono epokę", t.reload);
        },
      },
    },
    field("Epoka", eraSel),
    setEra,
  );
  const delta = h("input.input", { type: "number", step: "1", value: "100", "aria-label": "Zmiana punktów" });
  const ptsBtn = btn("Zmień punkty", { type: "submit" });
  const ptsForm = h(
    "form.inline-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          const v = Math.trunc(Number(delta.value));
          if (!Number.isFinite(v) || v === 0) return delta.classList.add("invalid");
          delta.classList.remove("invalid");
          act(ptsBtn, () => api.mutate("admin.postep.points", { id: t.id, delta: v }), (r) => `Punkty: ${v > 0 ? "+" : ""}${v} → ${pts(r && r.points)}`, t.reload);
        },
      },
    },
    field("Punkty badań (±)", delta),
    ptsBtn,
  );
  const missingTree = tree.filter((d) => d.status !== "OWNED");
  const grantSel = h("select.input", { "aria-label": "Odkrycie" }, missingTree.map((d) => h("option", { value: d.id }, `${d.name} (epoka ${d.era})`)));
  const grantBtn = btn("Nadaj odkrycie", { type: "submit" });
  const grantForm = h(
    "form.inline-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          if (!grantSel.value) return;
          act(grantBtn, () => api.mutate("admin.postep.grant", { id: t.id, discovery: grantSel.value }), (r) => (r && r.message) || "Nadano odkrycie", t.reload);
        },
      },
    },
    field("Odkrycie (z brakującymi wymaganiami)", grantSel),
    grantBtn,
  );
  return card("Narzędzia admina — postęp", h("p.muted.small", "Zmiany działają od razu i są zapisywane w dzienniku panelu."), eraForm, ptsForm, missingTree.length ? grantForm : h("p.muted.small", "Kraj zna wszystkie odkrycia."));
}
