// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Skarb Świata (tylko osobisty zarządca WorldSteward): zarząd rezerwą i pełne statystyki pieniędzy.
// Zakładki: Zarząd (#/skarbswiata), Bilans, Konta, Przepływy, Gospodarstwa, Monety, Ruchy monet, Kupiec oraz historia konta
// (#/skarbswiata/konto/<id>). Odmowa (brak roli albo błąd Skarbca) → karta „Brak dostępu” bez żadnych danych.
import { h, add, modal, skeleton } from "../dom.js";
import { card, field, money, btn, act, tabs } from "../components.js";
import * as api from "../api.js";
import { isDenied } from "../world-stats-model.js";
import { bilansTab, kontaTab, przeplywyTab, gospodarstwaTab, kontoView } from "./world-stats.js";
import { monetyTab, ruchyTab, kupiecTab } from "./world-stats-coins.js";
import { npcTransferCard } from "./world-npc-transfer.js";
import { grantsCard } from "./world-grants.js";
import { firmLoansCard } from "./world-firm-loans.js";

const budgets = [
  { account: "world:merchant", name: "Kupcy", key: "merchant" },
  { account: "world:households", name: "Obywatele NPC — podatki", key: "households" },
  { account: "world:payments", name: "Pozostałe wypłaty i wpływy", key: "payments" },
];

const zarzadTab = {
  title: "Zarząd",
  async load() {
    // Rozbieżność w odczycie państw NPC (np. stary plugin) nie blokuje zarządu; odmowa — tak (cała strona).
    const soft = (e) => (isDenied(e) ? Promise.reject(e) : { error: e.message || String(e) });
    const [world, npc, grants] = await Promise.all([
      api.read("world.get", {}).then((r) => r.data),
      api.read("world.npcTreasuries", {}).then((r) => r.data, soft),
      api.read("world.grants", {}).then((r) => r.data, soft),
    ]);
    return { ...world, npc, grants };
  },
  draw(main, d, ctx) {
    add(
      main,
      h(
        "div.grid-cards",
        card("Cała pula", money(d.cap), h("p", "Kapitał zaksięgowany: ", money(d.total)), h("p", "Fizyczne monety z pokryciem: ", money(d.cash))),
        card("Rezerwa pod Twoją kontrolą", money(d.reserve), h("p.muted", "Zasilenie budżetu przenosi istniejące pieniądze. Nie tworzy nowych monet.")),
      ),
    );
    const transfer = async (b, method, account, title) => {
      const amount = h("input.input", { type: "text", placeholder: "np. 100 zł", autocomplete: "off" });
      const value = await modal({ title, body: field("Kwota", amount, "Nie zmienia łącznej puli pieniędzy."), actions: [{ label: "Anuluj", value: null }, { label: "Przelej", kind: "primary", onClick: () => amount.value.trim() || false }] });
      if (value) act(b, () => api.mutate(method, { account, amount: value }), "Przelew zapisany w księdze", ctx.reload);
    };
    const cards = [
      card(
        "Przewoźnik kolejowy",
        d.railway === undefined ? h("p.muted", "Saldo sprawdzisz w dyspozytorni kolei.") : money(d.railway),
        h("p.muted", "Przelew z rezerwy na budowę i obsługę sieci kolejowej."),
        d.railwayConstruction === undefined ? null : h("p", "Zarezerwowane na roboty: ", money(d.railwayConstruction)),
        btn("Zasil przewoźnika", {}, (_, b) => transfer(b, "world.allocate", "kolej:przewoznik", "Zasil konto przewoźnika kolejowego")),
      ),
    ];
    for (const budget of budgets)
      cards.push(
        card(
          budget.name,
          money(d[budget.key]),
          h(
            "div.actions",
            btn("Zasil z rezerwy", {}, (_, b) => transfer(b, "world.allocate", budget.account, "Zasil budżet: " + budget.name)),
            btn("Zwróć do rezerwy", {}, (_, b) => transfer(b, "world.return", budget.account, "Zwrot budżetu: " + budget.name)),
          ),
        ),
      );
    add(main, h("div.grid-cards", cards));
    add(main, grantsCard(d.grants, ctx));
    add(main, npcTransferCard(d.npc, ctx));
    add(main, firmLoansCard(ctx));
  },
};

const TABS = [
  { id: "zarzad", label: "Zarząd", icon: "crown", href: "#/skarbswiata", view: zarzadTab },
  { id: "bilans", label: "Bilans", icon: "scroll", href: "#/skarbswiata/bilans", view: bilansTab },
  { id: "konta", label: "Konta", icon: "users", href: "#/skarbswiata/konta", view: kontaTab },
  { id: "przeplywy", label: "Przepływy", icon: "sort", href: "#/skarbswiata/przeplywy", view: przeplywyTab },
  { id: "gospodarstwa", label: "Gospodarstwa", icon: "house", href: "#/skarbswiata/gospodarstwa", view: gospodarstwaTab },
  { id: "monety", label: "Monety", icon: "coins", href: "#/skarbswiata/monety", view: monetyTab },
  { id: "ruchy", label: "Ruchy monet", icon: "eye", href: "#/skarbswiata/ruchy", view: ruchyTab },
  { id: "kupiec", label: "Kupiec", icon: "wheat", href: "#/skarbswiata/kupiec", view: kupiecTab },
];

function deniedCard(main) {
  add(main, card("Brak dostępu", h("p", "Skarbem Świata i jego statystykami zarządza osobiście WorldSteward."), h("p.muted", "Ta strona nie jest dostępna dla innych graczy ani administratorów.")));
}

/** Wspólny szkielet strony: nagłówek, zakładki, treść zakładki. */
function page(main, active, ctx, drawBody) {
  add(
    main,
    h(
      "div.page-head",
      h("div", h("h1", "Skarb Świata"), h("p.muted", "Stała pula całego serwera — gdzie są pieniądze, skąd i dokąd płyną. Widoczne tylko dla Ciebie.")),
      btn("Odśwież", { mut: false, icon: "rotate" }, () => ctx.forceReload()),
    ),
    tabs(TABS, active),
  );
  const body = h("div.tab-body.ws-body");
  add(main, body);
  drawBody(body);
}

/** Ładowanie z rozpoznaniem odmowy (rola albo błąd Skarbca) — wtedy żadnych danych. */
async function guarded(ctx, fn) {
  if (!ctx.me?.roles?.worldManager) return { denied: true };
  try {
    return { data: await fn() };
  } catch (e) {
    if (isDenied(e)) return { denied: true };
    throw e;
  }
}

function tabOf(ctx) {
  return TABS.find((t) => t.id === (ctx.params[0] || "zarzad")) || TABS[0];
}

export const worldView = {
  title: (d, ctx) => "Skarb Świata" + (ctx && tabOf(ctx).id !== "zarzad" ? " · " + tabOf(ctx).label : ""),
  skeleton: () => h("div.card", skeleton(6)),
  load: (ctx) => guarded(ctx, () => tabOf(ctx).view.load(ctx)),
  draw(main, r, ctx) {
    if (r.denied) return deniedCard(main);
    const t = tabOf(ctx);
    page(main, t.id, ctx, (body) => t.view.draw(body, r.data, ctx));
  },
};

export const worldAccountView = {
  title: (r) => (r && r.data ? kontoView.title(r.data) : "Skarb Świata"),
  skeleton: () => h("div.card", skeleton(6)),
  load: (ctx) => guarded(ctx, () => kontoView.load(ctx)),
  draw(main, r, ctx) {
    if (r.denied) return deniedCard(main);
    page(main, "konta", ctx, (body) => kontoView.draw(body, r.data, ctx));
  },
};
