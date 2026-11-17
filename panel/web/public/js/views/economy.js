// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Gospodarka kraju (#/gospodarka/<id>): produkcja i stan miejsc pracy, wydajność dzienna, skrzynie i ich zapełnienie,
// spichlerz z zapasem w dniach i wzrostem ludności, tragarze, place budowy (czego brakuje, skąd przyjdzie, jak długo czekają),
// braki z podpowiedziami oraz obywatele. Tylko odczyt. Widzą: władca i doradcy kraju, admin, WorldSteward, zarządca kraju NPC —
// serwer sprawdza to przy każdym odczycie; odmowa daje kartę „Brak dostępu” bez danych. Odpowiedź nie trafia do cache
// (ani w Durable Object, ani w sessionStorage). Odświeżanie co 30 s jak cały panel albo przyciskiem „Odśwież”.
// #/gospodarka bez kraju: własny kraj albo lista krajów (admin, WorldSteward, zarządca NPC).
import { h, add, icon, skeleton } from "../dom.js";
import * as api from "../api.js";
import { stat, card, bar, empty, table, countryName, shield, money, btn } from "../components.js";
import { fmtInt, fmtTime, fmtAgo, safeColor } from "../util.js";
import { historyCharts, fmtCompact } from "../chart.js";
import {
  hrefOf, BASE, ACCESS_LABELS, LEVEL_WORDS, STATE_CLASSES, KIND_LABELS, SITE_STATE_LABELS, canSeeEconomy, seesManyCountries,
  pickableCountries, isEconomyDenied, fmtDuration, fmtDays, foodLevel, fillLevel, kindClass, plural, countLevels, humanKey,
  describeValue, normalizeEconomy, dailyNote,
} from "../economy-model.js";

let wantFresh = false;

export const economyView = {
  title: (d) => (d && d.e ? "Gospodarka — " + d.e.name : d && d.denied ? "Brak dostępu" : "Gospodarka kraju"),
  skeleton: () => h("div", h("div.card", skeleton(2)), h("div.grid-stats", h("div.card", skeleton(2)), h("div.card", skeleton(2)), h("div.card", skeleton(2))), h("div.card", skeleton(6))),
  async load(ctx) {
    const [id] = ctx.params;
    const me = ctx.me || {};
    if (!id) {
      const own = me.country && ["RULER", "ADVISOR"].includes(String(me.country.rank).toUpperCase()) ? me.country.id : null;
      if (own && !seesManyCountries(me)) {
        ctx.go("/gospodarka/" + encodeURIComponent(own));
        return { redirect: true };
      }
      return { picker: true, countries: pickableCountries(me, ctx.overview), own };
    }
    const fresh = wantFresh;
    wantFresh = false;
    try {
      const r = await api.read("country.economy", fresh ? { id, fresh: true } : { id });
      return { e: normalizeEconomy(r.data), id };
    } catch (e) {
      if (isEconomyDenied(e)) return { denied: true, id, message: e.message };
      throw e;
    }
  },
  draw(main, d, ctx) {
    if (d.redirect) return;
    if (d.picker) return drawPicker(main, d, ctx);
    if (d.denied) return drawDenied(main, d, ctx);
    drawEconomy(main, d.e, ctx);
  },
};

// ---------------------------------------------------------------- lista i odmowa

function drawPicker(main, d, ctx) {
  add(main, h("div.page-head", h("div", h("h1", "Gospodarka kraju"), h("p.muted", "Produkcja, magazyny, spichlerz, tragarze, budowy i obywatele."))));
  if (!d.countries.length) {
    add(main, card(null, empty("Gospodarkę kraju widzą jego władca i doradcy. Nie masz dostępu do żadnego kraju.", "lock")));
    return;
  }
  const rows = d.countries.map((c) => ({ ...c, color: safeColor(c.color) }));
  add(
    main,
    h(
      "section.card.card-flush",
      table({
        columns: [
          { key: "name", label: "Kraj", render: (c) => h("a.cn", { href: hrefOf(c.id) }, shield(c.color), h("span.cn-name", c.name), c.npc ? h("span.tag.tag-npc", "NPC") : null) },
          { key: "population", label: "Ludność", num: true, render: (c) => fmtInt(c.population) },
          { key: "chunks", label: "Ziemie", num: true, render: (c) => fmtInt(c.chunks) },
          { key: "go", label: "", sort: false, cls: "col-actions", render: (c) => h("a.btn.btn-sm", { href: hrefOf(c.id) }, icon("wheat"), h("span", "Gospodarka")) },
        ],
        rows,
        sort: { key: "name", dir: "asc" },
        onRowClick: (c) => ctx.go("/gospodarka/" + encodeURIComponent(c.id)),
      }),
    ),
  );
}

function drawDenied(main, d, ctx) {
  const c = (ctx.overview.countries || []).find((x) => x.id === d.id);
  add(
    main,
    h("div.page-head", h("div", h("h1", "Gospodarka kraju"), c ? h("p.muted", c.name) : null)),
    h(
      "section.card.eco-denied",
      h("h2", icon("lock"), h("span", "Brak dostępu")),
      h("p", d.message || "Gospodarkę kraju widzą tylko jego władca i doradcy."),
      h("p.muted", "Serwer sprawdza uprawnienia przy każdym odczycie — dane obcego kraju nie są wysyłane do przeglądarki."),
      h("div.actions", c ? h("a.btn", { href: "#/kraj/" + encodeURIComponent(c.id) }, icon("shield"), h("span", "Widok kraju")) : null, ctx.me && ctx.me.country ? h("a.btn", { href: BASE }, icon("wheat"), h("span", "Moja gospodarka")) : null),
    ),
  );
}

// ---------------------------------------------------------------- strona

function drawEconomy(main, e, ctx) {
  const color = safeColor(e.color);
  const refresh = btn("Odśwież", { mut: false, icon: "rotate", small: true }, () => {
    wantFresh = true;
    ctx.reload();
  });
  const head = h(
    "section.country-head.eco-head",
    h("div.ch-crest", shield(color, "xl")),
    h(
      "div.ch-main",
      h("h1", "Gospodarka — ", e.name, e.npc ? h("span.tag.tag-npc", "NPC") : null),
      h(
        "p.muted",
        e.access ? [h("span.eco-role", ACCESS_LABELS[e.access] || e.access), " · "] : null,
        e.settlement ? [e.settlement, " · "] : null,
        "stan z ",
        h("time", { datetime: e.generatedAt ? new Date(e.generatedAt).toISOString() : null }, fmtTime(e.generatedAt ? e.generatedAt : Date.now())),
        e.cached && e.ageMs > 1000 ? " (" + Math.round(e.ageMs / 1000) + " s temu)" : "",
        " · odświeżanie co 30 s",
      ),
    ),
    h("div.eco-head-actions", refresh, h("a.btn.btn-sm", { href: "#/kraj/" + encodeURIComponent(e.id) }, icon("shield"), h("span", "Widok kraju"))),
  );
  head.style.setProperty("--c", color);
  add(main, head);
  if (e.newer) add(main, h("div.ws-banner.ws-banner-warn", icon("warn"), h("div", h("strong", "Nowsza wersja danych. "), "Plugin wysyła pola, których ta strona jeszcze nie zna — pokazujemy je w „Dane techniczne”.")));
  if (!e.available) add(main, h("div.ws-banner.ws-banner-warn", icon("warn"), h("div", h("strong", "Niepełne dane. "), Object.values(e.sources.errors || {})[0] || "Ludnosc nie udostępnia przepływu materiałów.")));

  add(main, kpis(e), warningsCard(e));
  add(main, h("div.grid-cards.eco-grid", productionCard(e), dailyCard(e)));
  add(main, workplacesCard(e));
  add(main, h("div.grid-cards.eco-grid", stockCard(e), granaryCard(e)));
  add(main, storesCard(e));
  add(main, h("div.grid-cards.eco-grid", sitesCard(e), haulersCard(e)));
  add(main, citizensCard(e), historyCard(e), technicalCard(e));
}

function kpis(e) {
  const s = e.summary || {};
  const g = e.granary || {};
  const fl = foodLevel(g.days);
  const tile = (label, value, ic, sub, level) => {
    const t = stat(label, value, ic, sub);
    if (level && level !== "ok") t.classList.add("eco-kpi-" + level);
    return t;
  };
  return h(
    "div.grid-stats.eco-kpis",
    tile("Miejsca pracy", `${fmtInt(s.working ?? 0)} / ${fmtInt(s.workplaces ?? 0)}`, "wheat", s.blocked ? `${fmtInt(s.blocked)} ${plural(s.blocked, "stoi", "stoją", "stoi")}` : "pracują", s.blocked ? "err" : null),
    tile("Skrzynie", e.stores.fill === null ? "—" : Math.round(e.stores.fill * 100) + "%", "house", e.stores.fullCount ? `${fmtInt(e.stores.fullCount)} ${plural(e.stores.fullCount, "pełna", "pełne", "pełnych")}` : `${fmtInt(e.stores.list.length)} wczytanych`, e.stores.fullCount ? "err" : null),
    tile("Spichlerz", fmtDays(g.days), "wheat", g.food !== undefined && g.food !== null ? `${fmtInt(g.food)} / ${fmtInt(g.capacity)} pkt` : null, fl),
    tile("Ludność", `${fmtInt(g.population)} / ${fmtInt(g.housing)}`, "users", g.growth24h ? `doba: ${g.growth24h.delta > 0 ? "+" : ""}${fmtInt(g.growth24h.delta)}` : g.growth.possible ? "wzrost możliwy" : "wzrost wstrzymany", g.growth.possible ? null : "warn"),
    tile("Tragarze w drodze", fmtInt(e.haulers.count), "road", e.haulers.recent.length ? `${fmtInt(e.haulers.recent.length)} ostatnich kursów` : null),
    tile("Place budowy", fmtInt(s.sites ?? e.sites.length), "tower", s.waitingSites ? `${fmtInt(s.waitingSites)} czeka na materiał` : "bez przestojów", s.waitingSites ? "warn" : null),
  );
}

function levelIcon(level) {
  return level === "info" ? "eye" : "warn";
}

function warningsCard(e) {
  const counts = countLevels(e.warnings);
  const head = counts.error + counts.warn === 0 ? h("div.ws-banner.ws-banner-ok", icon("check"), h("div", h("strong", "Wszystko płynie. "), e.warnings.length ? "Są tylko informacje poniżej." : "Brak braków i przestojów."))
    : h("div.ws-banner" + (counts.error ? ".ws-banner-err" : ".ws-banner-warn"), icon("warn"), h("div", h("strong", counts.error ? `${counts.error} ${plural(counts.error, "problem", "problemy", "problemów")}` : `${counts.warn} ${plural(counts.warn, "ostrzeżenie", "ostrzeżenia", "ostrzeżeń")}`), counts.error && counts.warn ? ` i ${counts.warn} ${plural(counts.warn, "ostrzeżenie", "ostrzeżenia", "ostrzeżeń")}` : "", " — podpowiedzi przy każdej pozycji."));
  return card(
    "Braki i ostrzeżenia",
    head,
    e.warnings.length
      ? h(
          "ul.eco-alerts",
          e.warnings.map((w) =>
            h(
              "li.eco-alert.eco-alert-" + (["error", "warn", "info"].includes(w.level) ? w.level : "info"),
              { dataset: { code: String(w.code || "") } },
              icon(levelIcon(w.level)),
              h(
                "div",
                h("div.eco-alert-title", h("span.eco-level", LEVEL_WORDS[w.level] || "informacja"), h("strong", w.title || "")),
                w.text ? h("p", w.text) : null,
                w.hint ? h("p.eco-hint", h("span", "Podpowiedź: "), w.hint) : null,
              ),
            ),
          ),
        )
      : null,
  );
}

// ---------------------------------------------------------------- produkcja

function productionCard(e) {
  const p = e.production;
  const rows = p.byFunction;
  return card(
    "Co kraj produkuje",
    rows.length
      ? table({
          columns: [
            { key: "function", label: "Miejsce pracy", render: (r) => h("span.eco-fn", r.function) },
            { key: "count", label: "Liczba", num: true, render: (r) => fmtInt(r.count) },
            { key: "working", label: "Pracuje", num: true, render: (r) => fmtInt(r.working) },
            { key: "blocked", label: "Stoi", num: true, render: (r) => (r.blocked ? h("span.eco-bad", fmtInt(r.blocked) + (r.full ? ` (${fmtInt(r.full)} pełne)` : "")) : "0") },
            { key: "unloaded", label: "Niewczytane", num: true, render: (r) => fmtInt(r.unloaded) },
          ],
          rows,
          cls: "eco-table",
        })
      : empty("Kraj nie ma jeszcze pól, tartaków, kamieniołomów, kopalń ani przystani.", "wheat"),
    p.today
      ? h(
          "dl.facts.eco-facts",
          h("dt", "Dziś do spichlerza z pól"),
          h("dd", `${fmtInt(p.today.food)} / ${fmtInt(p.today.foodCap)} pkt`),
          h("dt", "Dziś złowione ryby"),
          h("dd", `${fmtInt(p.today.fish)} / ${fmtInt(p.today.fishCap)}`),
          p.today.adults !== null && p.today.adults !== undefined ? [h("dt", "Dorośli z przydziałem pracy"), h("dd", `${fmtInt(p.today.assigned)} / ${fmtInt(p.today.adults)}`)] : null,
        )
      : null,
    p.workers && p.workers.length
      ? h(
          "div.eco-jobs",
          p.workers.map((w) => h("span.chip", h("strong", w.label + ":"), " " + fmtInt(w.workers), w.slots ? h("span.muted", ` / ${fmtInt(w.slots)} miejsc`) : null)),
        )
      : null,
  );
}

/** Poziome słupki towarów (kolor = rodzaj towaru, wartość zawsze tekstem). */
function hbars(items, { max = 12, unit = "", emptyText = "Brak danych" } = {}) {
  const list = items.slice(0, max);
  if (!list.length) return h("p.muted.eco-nodata", emptyText);
  const top = Math.max(1, ...list.map((x) => Number(x.amount) || 0));
  return h(
    "ul.eco-bars",
    { role: "list" },
    list.map((x) => {
      const fill = h("span.eco-bar-fill." + kindClass(x.kind));
      fill.style.setProperty("width", Math.max(1.5, ((Number(x.amount) || 0) / top) * 100).toFixed(1) + "%");
      return h(
        "li.eco-bar-row",
        { title: `${x.label}: ${fmtInt(x.amount)}${unit} (${KIND_LABELS[x.kind] || "inne"})` },
        h("span.eco-bar-label", h("span.eco-dot." + kindClass(x.kind)), h("span", x.label)),
        h("span.eco-bar-track", fill),
        h("span.eco-bar-value", fmtInt(x.amount) + unit),
      );
    }),
    items.length > max ? h("li.eco-bar-more.muted", `…i ${items.length - max} innych`) : null,
  );
}

function kindLegend(items) {
  const kinds = [...new Set(items.map((x) => x.kind))];
  return kinds.length > 1 ? h("ul.ws-legend.eco-legend", kinds.map((k) => h("li", h("span.eco-dot." + kindClass(k)), h("span", KIND_LABELS[k] || "inne")))) : null;
}

function dailyCard(e) {
  const d = e.production.daily;
  const items = Array.isArray(d.items) ? d.items : [];
  return card(
    "Wydajność dzienna",
    h("p.muted.small", dailyNote(d)),
    hbars(items, { unit: "/dobę", emptyText: "Jeszcze nic nie policzono." }),
    kindLegend(items),
  );
}

function workplacesCard(e) {
  const rows = e.production.workplaces;
  if (!rows.length) return null;
  return card(
    "Miejsca pracy i ich stan",
    table({
      columns: [
        { key: "function", label: "Miejsce", render: (r) => h("span", h("span.eco-fn", r.function), " ", h("code.eco-id", r.short || "")) },
        { key: "state", label: "Stan", sort: (r) => (STATE_CLASSES[r.state] === "err" ? 0 : STATE_CLASSES[r.state] === "warn" ? 1 : 2), render: (r) => h("span.eco-state.eco-state-" + (STATE_CLASSES[r.state] || "idle"), { title: r.status || "" }, r.stateLabel || r.state) },
        { key: "status", label: "Komunikat Ludnosci", render: (r) => h("span.muted", r.status || "—") },
        { key: "daily", label: "Na dobę", sort: false, render: (r) => (Array.isArray(r.daily) && r.daily.length ? r.daily.slice(0, 3).map((x) => `${fmtInt(x.amount)}× ${x.label}`).join(", ") : "—") },
        { key: "x", label: "Położenie", sort: false, render: (r) => (r.x !== null && r.x !== undefined ? h("span.eco-coords", `x ${fmtInt(r.x)}, z ${fmtInt(r.z)}`) : "—") },
      ],
      rows,
      sort: { key: "state", dir: "asc" },
      cls: "eco-table eco-workplaces",
    }),
  );
}

// ---------------------------------------------------------------- magazyny

function stockCard(e) {
  const s = e.stores;
  return card(
    "Towar w skrzyniach (razem)",
    h("p.muted.small", `${fmtInt(s.list.length)} ${plural(s.list.length, "wczytana skrzynia", "wczytane skrzynie", "wczytanych skrzyń")} · zajęte ${fmtInt(s.usedSlots)} z ${fmtInt(s.slots)} miejsc. Liczone są tylko skrzynie w chunkach, które serwer ma teraz w pamięci.`),
    hbars(s.totals, { max: 14, emptyText: "Brak towaru we wczytanych skrzyniach." }),
    kindLegend(s.totals.slice(0, 14)),
  );
}

function storesCard(e) {
  const list = e.stores.list;
  return card(
    "Magazyny i skrzynie",
    list.length
      ? h(
          "ul.eco-stores",
          list.map((s) =>
            h(
              "li.eco-store" + (s.full ? ".eco-store-full" : ""),
              h(
                "div.eco-store-head",
                h("strong", s.label),
                s.full ? h("span.eco-state.eco-state-err", icon("warn"), h("span", "pełna — blokuje pracę")) : s.fill >= 0.9 ? h("span.eco-state.eco-state-warn", "prawie pełna") : null,
              ),
              h("div.eco-fill.eco-fill-" + fillLevel(s.fill, s.full), bar(s.fill, `${fmtInt(s.used)} / ${fmtInt(s.slots)} miejsc`)),
              Array.isArray(s.goods) && s.goods.length ? h("p.eco-goods", s.goods.slice(0, 6).map((g, i) => [i ? ", " : "", h("span", fmtInt(g.amount) + "× " + g.label)]), s.goods.length > 6 ? h("span.muted", ` …(+${s.goods.length - 6})`) : null) : h("p.muted.eco-goods", "pusta"),
            ),
          ),
        )
      : empty("Brak wczytanych, powiązanych skrzyń. Doradca: /ludnosc magazyn powiaz ID albo skrzynia w budynku.", "house"),
  );
}

// ---------------------------------------------------------------- spichlerz

function granaryCard(e) {
  const g = e.granary;
  const fl = foodLevel(g.days);
  const frac = g.capacity ? (Number(g.food) || 0) / g.capacity : 0;
  return card(
    "Spichlerz i ludność",
    h("div.eco-food.eco-fill-" + (fl === "err" ? "err" : fl === "warn" ? "warn" : "ok"), bar(frac, `${fmtInt(g.food)} / ${fmtInt(g.capacity)} pkt jedzenia`)),
    h(
      "dl.facts.eco-facts",
      h("dt", "Zapas"),
      h("dd", h("strong.eco-" + fl, fmtDays(g.days)), g.meal ? h("span.muted", ` (posiłek dzienny ${fmtInt(g.meal)} pkt)`) : null),
      g.foodBalance !== null && g.foodBalance !== undefined ? [h("dt", "Bilans dzienny"), h("dd", (g.foodBalance > 0 ? "+" : "") + fmtInt(g.foodBalance) + " pkt")] : null,
      g.storedFood ? [h("dt", "Jedzenie w skrzyniach"), h("dd", fmtInt(g.storedFood) + " szt. (tragarze zaniosą do spichlerza)")] : null,
      h("dt", "Ludność / łóżka"),
      h("dd", `${fmtInt(g.population)} / ${fmtInt(g.housing)}`, g.freeBeds ? h("span.muted", ` · ${fmtInt(g.freeBeds)} ${plural(g.freeBeds, "wolne łóżko", "wolne łóżka", "wolnych łóżek")}`) : null),
      h("dt", "Wzrost ludności"),
      h("dd", h("span.eco-" + (g.growth.possible ? "ok" : "warn"), g.growth.text)),
      g.growth24h ? [h("dt", "Zmiana w ciągu doby"), h("dd", (g.growth24h.delta > 0 ? "+" : "") + fmtInt(g.growth24h.delta) + ` (od ${fmtAgo(g.growth24h.at)})`)] : null,
      g.hungryDays ? [h("dt", "Dni głodu"), h("dd.eco-err", fmtInt(g.hungryDays))] : null,
      g.homelessDays ? [h("dt", "Dni bez dachu"), h("dd.eco-warn", fmtInt(g.homelessDays))] : null,
      g.hasGranary === false ? [h("dt", "Spichlerz"), h("dd.eco-warn", "brak budynku spichlerza")] : null,
    ),
  );
}

// ---------------------------------------------------------------- budowy i tragarze

function sitesCard(e) {
  const sites = e.sites;
  const wait = e.settings.materialWaitMinutes ?? 60;
  return card(
    "Place budowy",
    sites.length
      ? h(
          "ul.eco-sites",
          sites.map((s) =>
            h(
              "li.eco-site.eco-site-" + String(s.state || "OTHER").toLowerCase(),
              h("div.eco-site-head", h("strong", s.name), h("span.muted", s.stage ? " · " + s.stage : ""), h("span.eco-site-pct", fmtInt(s.percent) + "%")),
              bar((Number(s.percent) || 0) / 100, ""),
              h(
                "p.eco-site-status",
                h("span.eco-state.eco-state-" + (s.state === "WAITING" ? "warn" : s.state === "BOUGHT" ? "idle" : "ok"), SITE_STATE_LABELS[s.state] || "stan"),
                " ",
                s.status ? s.status : `budowniczych: ${fmtInt(s.builders)}`,
              ),
              s.state === "WAITING"
                ? h(
                    "p.eco-site-wait",
                    s.waitingFor ? [h("span", "Brakuje: "), h("strong", s.waitingFor)] : null,
                    s.source ? [" · skąd: ", h("strong", s.source)] : null,
                    s.waitedMs !== null && s.waitedMs !== undefined ? [" · czeka ", s.waitedApprox ? "co najmniej " : "", fmtDuration(s.waitedMs)] : null,
                    s.buyInMs !== null && s.buyInMs !== undefined ? [" · ", s.buyInMs > 0 ? `kupi na targu za ok. ${fmtDuration(s.buyInMs)}` : `kupuje na targu (limit ${wait} min minął)`] : null,
                  )
                : null,
              Array.isArray(s.needs) && s.needs.length
                ? h("p.eco-site-needs", "Do końca: ", s.needs.map((n, i) => [i ? ", " : "", h("span" + (n.missing ? ".eco-bad" : ""), `${n.label} ${fmtInt(n.units)} jedn.`)]), h("span.muted", " (w skrzyniach: " + s.needs.map((n) => `${n.label} ${fmtInt(n.have)}`).join(", ") + ")"))
                : null,
            ),
          ),
        )
      : empty("Brak trwających budów.", "tower"),
    e.materials.length
      ? table({
          columns: [
            { key: "label", label: "Materiał" },
            { key: "needed", label: "Potrzeba", num: true, render: (m) => fmtInt(m.needed) + " jedn." },
            { key: "have", label: "W skrzyniach", num: true, render: (m) => fmtInt(m.have) + " jedn." },
            { key: "missing", label: "Brakuje", num: true, render: (m) => (m.missing ? h("span.eco-bad", fmtInt(m.missing) + " jedn.") : "0") },
            { key: "producers", label: "Kto wytwarza", sort: false, render: (m) => (Array.isArray(m.producers) && m.producers.length ? m.producers.join(", ") : h("span.eco-bad", "nikt — zbuduj " + (m.build || "warsztat") + " albo kup u kupca")) },
          ],
          rows: e.materials,
          cls: "eco-table",
        })
      : null,
    e.settings.requireMaterials === false ? h("p.muted.small", "Budowy są opłacane monetami (bez fizycznego materiału).") : h("p.muted.small", `Budowa czeka na tragarzy najwyżej ${wait} min, potem kupuje materiał za monety z zamówienia.`),
  );
}

function haulersCard(e) {
  const H = e.haulers;
  return card(
    "Tragarze i kursy",
    H.walking.length
      ? h(
          "ul.eco-hauls",
          H.walking.map((x) =>
            x.from
              ? h("li", h("strong", `${fmtInt(x.amount)}× ${x.item}`), h("span.eco-route", h("span", x.from), h("span.eco-arrow", { "aria-label": "do" }, "→"), h("span", x.to)))
              : h("li", x.text),
          ),
        )
      : h("p.muted", "Teraz nikt nic nie niesie."),
    H.recent.length ? h("h4.eco-sub", "Ostatnie przeniesienia") : null,
    H.recent.length ? h("ul.eco-recent", H.recent.slice(0, 12).map((r) => h("li", r.time ? h("time.muted", r.time) : null, " ", r.text))) : null,
  );
}

// ---------------------------------------------------------------- obywatele

function citizensCard(e) {
  const c = e.citizens;
  const v = (k) => (c[k] === null || c[k] === undefined ? "—" : fmtInt(c[k]));
  if (!c.available && (c.population === null || c.population === undefined)) {
    return card("Obywatele", empty("Brak danych o obywatelach (Życie obywateli w Ludnosc wyłączone albo starsza wersja).", "users"));
  }
  return card(
    "Obywatele",
    c.available ? null : h("p.muted.small", "Szczegóły (domy, praca, rodziny) pokaże Ludnosc z włączonym „Życiem obywateli”."),
    h(
      "div.grid-stats.eco-citizens",
      stat("Obywatele", v("population"), "users", c.children !== null && c.children !== undefined ? `w tym dzieci: ${v("children")}` : null),
      stat("Z domem", v("housed"), "house", c.homeless ? `${v("homeless")} bez dachu` : null),
      stat("Z pracą", v("employed"), "wheat", c.unemployed !== null && c.unemployed !== undefined ? `bezrobotni: ${v("unemployed")}` : null),
      stat("Rodziny (pary)", v("couples"), "users"),
      stat("Oszczędności łącznie", c.savings === null || c.savings === undefined ? "—" : money(c.savings), "coins", c.average ? h("span", "średnio ", money(c.average)) : null),
      stat("Podatek dziennie", c.taxDaily === null || c.taxDaily === undefined ? "—" : money(c.taxDaily), "coins", c.taxSource === "expected" ? "przewidywany" : c.taxDue ? h("span", "należny ", money(c.taxDue)) : "ostatnia doba"),
      stat("Pensje dziennie", c.wagesDaily === null || c.wagesDaily === undefined ? "—" : money(c.wagesDaily), "coins", c.unpaid ? `${v("unpaid")} bez pensji` : c.wagesDue ? h("span", "należne ", money(c.wagesDue)) : null),
      c.income !== null && c.income !== undefined ? stat("Dochód z pracy", money(c.income), "coins", c.meals ? h("span", "posiłki ", money(c.meals)) : null) : null,
    ),
  );
}

function historyCard(e) {
  const pts = e.history.points;
  if (pts.length < 2) return card("Ostatnia doba", h("p.muted", "Panel zbiera próbki co kilka minut (także gdy nikt nie patrzy) — wykres pojawi się po kilku pomiarach."));
  const intTick = (v) => fmtCompact(v);
  return card(
    "Ostatnia doba",
    h("p.muted.small", "Jedzenie w spichlerzu, ludność i towar we wczytanych skrzyniach. Historia jest w pamięci serwera — restart ją zeruje."),
    historyCharts(
      pts,
      [
        { key: "food", label: "Jedzenie (pkt)", fmt: (v) => fmtInt(v), tick: intTick, int: true, domainMin: 0 },
        { key: "population", label: "Ludność", fmt: (v) => fmtInt(v), tick: intTick, int: true },
        { key: "goods", label: "Towar w skrzyniach", fmt: (v) => fmtInt(v), tick: intTick, int: true, domainMin: 0 },
      ],
      { from: Math.max(e.history.from || 0, Number(pts[0].t) - 15 * 60 * 1000), to: e.history.to, axis: "time", window: "ostatnia doba", table: false, empty: "Brak próbek." },
    ),
  );
}

function technicalCard(e) {
  const src = e.sources;
  const errs = Object.entries(src.errors || {});
  const extra = e.extra ? Object.entries(e.extra) : [];
  return h(
    "details.card.eco-tech",
    h("summary", "Dane techniczne"),
    h(
      "dl.facts.eco-facts",
      h("dt", "Przepływ materiałów (Ludnosc)"),
      h("dd", src.flow ? "tak" + (src.flowVersion ? ` (wersja ${src.flowVersion})` : "") : "nie"),
      h("dt", "Życie obywateli / praca"),
      h("dd", (src.life ? "tak" : "nie") + " / " + (src.work ? "tak" : "nie")),
      h("dt", "Czas zebrania"),
      h("dd", e.buildMs !== null ? e.buildMs.toLocaleString("pl-PL") + " ms" : "—", e.cached ? " · z pamięci serwera" : ""),
      errs.length ? [h("dt", "Uwagi"), h("dd", errs.map(([k, t]) => h("div", h("code", k), " ", t)))] : null,
      extra.length ? [h("dt", "Nowe pola Ludnosci"), h("dd", extra.map(([k, x]) => h("div.eco-extra", h("code", k), " ", describeValue(x))))] : null,
    ),
  );
}

export { canSeeEconomy, hrefOf };
