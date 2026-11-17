// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — nastrój mieszkańców (plugin Zycie, `country.get.mood`): kafelek, karta z paskiem 0–100, czynnikami +/−,
// zmartwieniami i plakietkami „Festyn” / „Protest”; dla państw NPC przycisk „Ogłoś festyn” (admin.npc.festival).
import { h, icon } from "../dom.js";
import * as api from "../api.js";
import { arr } from "../util.js";
import { card, bar, act, btn } from "../components.js";

const kindOf = (v) => (v < 35 ? "war" : v < 50 ? "warn" : "ok");

export function hasMood(c) {
  const m = c && c.raw ? c.raw.mood : c && c.mood;
  return m && typeof m === "object" && Number.isFinite(Number(m.value)) ? m : null;
}

function badges(m) {
  return [m.festival ? h("span.mood-badge.mood-fest", icon("crown"), "Festyn") : null, m.unrest ? h("span.mood-badge.mood-unrest", icon("warn"), "Protest") : null];
}

/** Kafelek do siatki statystyk. */
export function moodStat(m) {
  const v = Math.round(Number(m.value));
  return h(
    "div.stat.stat-mood",
    h("div.stat-ic", icon("users")),
    h("div.stat-body", h("div.stat-label", "Nastrój"), h("div.stat-value", `${v}/100`), h("div.stat-sub", h("span.mood-label", m.label || ""), badges(m))),
  );
}

/**
 * Karta „Nastrój mieszkańców”. opts.festival: {id, reload} — przycisk „Ogłoś festyn” (państwa NPC, uprawnieni).
 */
export function moodCard(m, opts = {}) {
  const v = Math.max(0, Math.min(100, Math.round(Number(m.value))));
  const factors = arr(m.factors).filter((f) => f && Number(f.value));
  const concerns = arr(m.concerns).filter(Boolean);
  const fest = opts.festival
    ? btn("Ogłoś festyn", { small: true, kind: "primary", icon: "crown" }, (_e, b) => act(b, () => api.mutate("admin.npc.festival", { id: opts.festival.id }), (r) => (r && r.message) || "Ogłoszono festyn.", opts.festival.reload))
    : null;
  if (fest && m.festival) fest.disabled = true;
  return card(
    "Nastrój mieszkańców",
    h("div.mood-head", h("strong.mood-value", `${v}`), h("span.muted", "/100 · "), h("span.mood-label-big", m.label || ""), h("span.mood-badges", badges(m))),
    bar(v / 100, null, kindOf(v)),
    h("p.small.muted.mood-scale", h("span", "bunt"), h("span", "spokój"), h("span", "zachwyt")),
    factors.length
      ? h(
          "ul.mood-factors",
          factors.map((f) => {
            const n = Math.round(Number(f.value));
            return h("li" + (n > 0 ? ".mf-pos" : ".mf-neg"), h("span.mf-sign", n > 0 ? "+" : "−"), h("span.mf-label", f.label || f.key), h("span.mf-val", (n > 0 ? "+" : "−") + Math.abs(n)));
          }),
        )
      : h("p.muted.small", "Brak danych o czynnikach nastroju."),
    concerns.length ? h("div.mood-concerns", h("h4", "Zmartwienia mieszkańców"), h("ul", concerns.map((c) => h("li", c)))) : null,
    typeof m.multiplier === "number" ? h("p.small.muted", `Nastrój mnoży podatki, przyrost i pracę: ×${m.multiplier.toFixed(2)}.`) : null,
    fest ? h("div.form-actions", fest) : null,
  );
}
