// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Skarb Świata → Zarząd: „Zasilenia wdrożeniowe” (world.grants / world.grantsApply). Jednorazowe przydziały
// z rezerwy (pula obywateli NPC, budżet kupców). Przy każdym widać dotychczasowe ręczne przydziały z rezerwy na to
// samo konto, żeby nie zasilić go dwa razy. Stan „Niepewne” wymaga świadomego ponowienia (przepisanie klucza).
import { h, icon, modal, toast, confirmDanger } from "../dom.js";
import { card, table, btn } from "../components.js";
import { formatMoney } from "../money.js";
import { fmtInt, fmtDate, fmtAgo } from "../util.js";
import * as api from "../api.js";
import { GRANT_STATE_LABELS } from "../world-stats-model.js";
import { accountHref } from "./world-stats-common.js";

const STATE_CLS = { NEW: "info", PENDING: "warn", DONE: "ok", UNCERTAIN: "warn" };

function stateBadge(state) {
  return h("span.ws-level.ws-level-" + (STATE_CLS[state] || "warn"), state === "UNCERTAIN" ? [icon("warn"), " "] : null, GRANT_STATE_LABELS[state] || state);
}

async function apply(button, params, ctx) {
  button.disabled = true;
  button.classList.add("busy");
  try {
    const r = await api.mutate("world.grantsApply", params);
    toast((r && r.message) || "Zasilenie zapisane w księdze", "ok");
    ctx.reload();
  } catch (e) {
    toast(e.message || String(e), "err", 8000);
  } finally {
    button.disabled = false;
    button.classList.remove("busy");
  }
}

async function confirmApply(button, grants, reserve, ctx, key) {
  const sum = grants.reduce((t, g) => t + (Number(g.amount) || 0), 0);
  const after = reserve - sum;
  const ok = await modal({
    title: grants.length > 1 ? "Wykonać wszystkie nowe zasilenia?" : "Wykonać zasilenie?",
    body: h(
      "div.ws-npc-confirm",
      h("div.ws-npc-amount", formatMoney(sum)),
      h("p.muted.small", `= ${fmtInt(sum)} md`),
      h("dl.ws-facts", grants.map((g) => [h("dt", g.label), h("dd", formatMoney(g.amount), " → ", h("code", g.account))]), h("dt", "Rezerwa"), h("dd", formatMoney(reserve), " → ", h("strong" + (after < 0 ? ".neg" : ""), formatMoney(after)))),
      after < 0
        ? h("div.alert.alert-err", icon("warn"), h("span", "Za mało środków w rezerwie Skarbu Świata."))
        : h("p.muted.small", "Zasilenie jest jednorazowe: przenosi istniejące pieniądze z rezerwy, łączna pula serwera się nie zmienia."),
    ),
    actions: after < 0 ? [{ label: "Anuluj", value: null }] : [{ label: "Anuluj", value: null }, { label: "Wykonaj " + formatMoney(sum), kind: "primary", value: true }],
  });
  if (ok) await apply(button, { key }, ctx);
}

/** Karta zasileń. data: wynik world.grants albo {error}. */
export function grantsCard(data, ctx) {
  const title = "Zasilenia wdrożeniowe";
  if (!data || data.error) return card(title, h("div.alert.alert-warn", icon("warn"), h("span", "Nie udało się wczytać zasileń: " + ((data && data.error) || "brak danych") + ". Ta funkcja wymaga aktualnego pluginu Skarbca.")));
  const grants = Array.isArray(data.grants) ? data.grants : [];
  const reserve = Number(data.reserve) || 0;
  const fresh = grants.filter((g) => g.state === "NEW");
  const rows = table({
    columns: [
      { key: "label", label: "Zasilenie", render: (g) => h("div.ws-acc-cell", h("strong", g.label), h("code.ws-acc-id", g.key)) },
      { key: "state", label: "Stan", render: (g) => stateBadge(g.state) },
      { key: "amount", label: "Kwota", num: true, render: (g) => h("span.money", { title: fmtInt(g.amount) + " md" }, formatMoney(g.amount)) },
      { key: "account", label: "Konto (saldo teraz)", render: (g) => h("div.ws-acc-cell", h("a", { href: accountHref(g.account) }, h("code", g.account)), h("span.money", formatMoney(g.balance))) },
      {
        key: "given",
        label: "Dotychczas z rezerwy",
        render: (g) =>
          h(
            "div.ws-acc-cell",
            h("span", `${fmtInt(g.fromReserveCount)} przydz. · ${formatMoney(g.fromReserveTotal)}`),
            h("span.muted.small", g.lastFromReserve ? { title: fmtDate(g.lastFromReserve) } : null, g.lastFromReserve ? "ostatni " + fmtAgo(g.lastFromReserve) : "brak przydziałów"),
          ),
        sort: false,
      },
      {
        key: "act",
        label: "",
        cls: "col-actions",
        render: (g) => {
          if (g.state === "NEW") return btn("Wykonaj", { kind: "primary", small: true }, (_, b) => confirmApply(b, [g], reserve, ctx, g.key));
          if (g.state === "UNCERTAIN")
            return btn("Ponów świadomie", { kind: "danger", small: true }, async (_, b) => {
              const ok = await confirmDanger({
                title: "Ponowić zasilenie o niepewnym stanie?",
                text: `Poprzednia próba zasilenia „${g.label}” (${formatMoney(g.amount)} → ${g.account}) mogła zostać już zaksięgowana. Sprawdź saldo konta i dotychczasowe przydziały z rezerwy — ponowienie może zasilić konto drugi raz.`,
                confirmLabel: "Ponów zasilenie",
                requireText: g.key,
              });
              if (ok) await apply(b, { key: g.key, retry: "true" }, ctx);
            });
          return null;
        },
        sort: false,
      },
    ],
    rows: grants,
    sort: null,
    emptyText: "Brak zdefiniowanych zasileń.",
    cls: "ws-grants",
  });
  return card(
    title,
    h(
      "p.muted",
      "Jednorazowe przydziały z rezerwy potrzebne po wdrożeniu nowej gospodarki. Rezerwa: ",
      h("strong.money", { title: fmtInt(reserve) + " md" }, formatMoney(reserve)),
      ". ",
      data.autoOnStart ? "Serwer wykonuje nowe zasilenia automatycznie przy starcie." : "Serwer nie wykonuje ich automatycznie — zdecyduj tutaj.",
    ),
    rows,
    fresh.length > 1 ? h("div.form-actions", btn(`Wykonaj wszystkie nowe (${fresh.length})`, { kind: "primary", icon: "coins" }, (_, b) => confirmApply(b, fresh, reserve, ctx, "wszystkie"))) : null,
  );
}
