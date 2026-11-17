// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Pulpit: Twój kraj (albo lista krajów), saldo konta bankowego, skróty.
import { h, icon, add } from "../dom.js";
import { RANKS, fmtInt, safeColor } from "../util.js";
import { money, stat, card, countryName, shield, empty } from "../components.js";

export const dashboardView = {
  title: "Pulpit",
  async load(ctx) {
    return {};
  },
  draw(main, _d, ctx) {
    const me = ctx.me || {};
    const list = ctx.overview.countries.map((c) => ({ ...c, color: safeColor(c.color) }));
    const mine = me.country ? list.find((c) => c.id === me.country.id) || { id: me.country.id, name: me.country.name, color: "#8a7350" } : null;

    const head = h("div.page-head", h("div", h("h1", "Witaj, ", h("span.gold", me.name || "wędrowcze")), h("p.muted", mine ? "Oto stan Twojego państwa." : "Nie należysz jeszcze do żadnego państwa.")));

    const balance = card(null, h("div.balance-card", h("div.balance-ic", icon("coins")), h("div", h("div.stat-label", "Konto bankowe"), h("div.balance-value", money(me.balance)), h("div.stat-sub", "Wpłacaj i wypłacaj u bankiera w grze."))));

    let countryBlock;
    if (mine) {
      const id = encodeURIComponent(mine.id);
      const rank = me.country.rank;
      const shortcuts = [
        ["Przegląd", "eye", ""],
        ["Członkowie", "users", "/czlonkowie"],
        ["Skarbiec", "coins", "/skarbiec"],
        ["Dyplomacja", "scroll", "/dyplomacja"],
        ["Armia", "swords", "/armia"],
        ["Ludność", "house", "/ludnosc"],
      ];
      if (rank === "RULER") shortcuts.push(["Ustawienia", "gear", "/ustawienia"]);
      const hero = h("section.card.country-hero", h("div.hero-banner"), h("div.hero-body", shield(mine.color, "xl"), h("div", h("h2.hero-name", mine.name), h("p.muted", "Twoja ranga: ", h("strong", RANKS[rank] || rank || "Obywatel"), mine.ruler ? h("span", " · Władca: ", h("strong", mine.ruler)) : null))));
      hero.style.setProperty("--c", mine.color);
      countryBlock = [
        hero,
        h("div.grid-stats", stat("Członkowie", fmtInt(mine.members), "users"), stat("Ludność", fmtInt(mine.population), "house"), stat("Żołnierze", fmtInt(mine.soldiers), "swords"), stat("Ziemie", fmtInt(mine.chunks) + " chunków", "map", mine.areaBlocks ? fmtInt(mine.areaBlocks) + " bloków²" : null)),
        card("Skróty", h("div.shortcuts", shortcuts.map(([l, ic, t]) => h("a.shortcut", { href: "#/kraj/" + id + t }, icon(ic), h("span", l))))),
      ];
    } else {
      countryBlock = card(
        "Państwa królestwa",
        h("p.muted", "Załóż własne państwo w grze albo poproś władcę o zaproszenie. Poniżej znajdziesz istniejące kraje."),
        list.length
          ? h(
              "ul.country-list",
              list
                .slice()
                .sort((a, b) => (b.members || 0) - (a.members || 0))
                .map((c) => h("li", countryName(c), h("span.muted", `${fmtInt(c.members)} czł. · ${fmtInt(c.chunks)} chunków`), c.ruler ? h("span.muted.small", "Władca: " + c.ruler) : null)),
            )
          : empty("Na serwerze nie ma jeszcze żadnych państw."),
      );
    }

    const ov = ctx.overview;
    const world = card("Królestwo", h("div.grid-stats.grid-stats-2", stat("Gracze online", typeof ov.online === "number" ? `${ov.online} / ${ov.maxPlayers ?? "?"}` : "—", "users"), stat("Państwa", fmtInt(list.length), "shield", `w tym NPC: ${fmtInt(list.filter((c) => c.npc).length)}`)), h("a.link-more", { href: "#/ranking" }, "Zobacz ranking krajów →"));

    add(main, head, h("div.dash", h("div.dash-main", countryBlock), h("div.dash-side", balance, world)));
  },
};
