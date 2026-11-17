// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Ranking krajów: tabela z sortowaniem (na telefonie karty).
import { h, add } from "../dom.js";
import { fmtInt, safeColor, arr } from "../util.js";
import { table, countryName } from "../components.js";
import { eraBadge } from "./postep.js";

export const rankingView = {
  title: "Ranking krajów",
  async load() {
    return {};
  },
  draw(main, _d, ctx) {
    const rows = ctx.overview.countries.map((c) => ({ ...c, color: safeColor(c.color), alliesN: arr(c.allies).length, warsN: arr(c.wars).length }));
    const myId = ctx.me && ctx.me.country && ctx.me.country.id;
    const t = table({
      columns: [
        { key: "pos", label: "#", sort: false, render: (r, i) => h("span.pos", String(i + 1)), cls: "col-pos" },
        { key: "name", label: "Kraj", render: (r) => h("span.rank-name", countryName(r), r.id === myId ? h("span.tag.tag-mine", "Twój") : null), sort: (r) => r.name },
        { key: "era", label: "Epoka", render: (r) => (r.era ? h("span.rank-era", eraBadge(r.era), typeof r.eraProgress === "number" && r.eraProgress < 1 ? h("span.muted.small", " " + Math.floor(r.eraProgress * 100) + "%") : null) : "—"), sort: (r) => (r.era ? r.era.id * 1000 + Math.floor((r.eraProgress || 0) * 999) : -1) },
        { key: "ruler", label: "Władca", render: (r) => r.ruler || "—" },
        { key: "members", label: "Członkowie", num: true, render: (r) => fmtInt(r.members) },
        { key: "population", label: "Ludność", num: true, render: (r) => fmtInt(r.population) },
        { key: "soldiers", label: "Żołnierze", num: true, render: (r) => fmtInt(r.soldiers) },
        { key: "chunks", label: "Chunki", num: true, render: (r) => fmtInt(r.chunks) },
        { key: "areaBlocks", label: "Obszar (bl²)", num: true, render: (r) => fmtInt(r.areaBlocks) },
        { key: "alliesN", label: "Sojusze", num: true, render: (r) => (r.alliesN ? h("span.rel.rel-ALLIANCE", String(r.alliesN)) : "0") },
        { key: "warsN", label: "Wojny", num: true, render: (r) => (r.warsN ? h("span.rel.rel-WAR", String(r.warsN)) : "0") },
      ],
      rows,
      sort: { key: "chunks", dir: "desc" },
      onRowClick: (r) => ctx.go("/kraj/" + encodeURIComponent(r.id)),
      emptyText: "Na serwerze nie ma jeszcze żadnych państw.",
      cls: "ranking",
    });
    add(main, h("div.page-head", h("div", h("h1", "Ranking krajów"), h("p.muted", "Kliknij nagłówek kolumny, aby zmienić sortowanie. Kliknij kraj, aby zobaczyć szczegóły."))), h("section.card.card-flush", t));
  },
};
