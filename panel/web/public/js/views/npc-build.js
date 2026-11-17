// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Państwo NPC (v2): zakładki Budowy (budynki, place, kolejka ↑ ↓ ✕, „Zamów budynek” z wymaganiami,
// „Rozbuduj teraz”) i Armia (oddziały, morale, postawa, cel oblężeń, posterunki, dodaj/rozwiąż).
import { h, icon, modal, confirmDanger, add } from "../dom.js";
import * as api from "../api.js";
import { formatMoney } from "../money.js";
import { fmtInt, fmtDate, pick, arr, fraction, pct, safeColor } from "../util.js";
import { money, stat, card, bar, empty, field, act, btn, table, countryName, moneyInput } from "../components.js";
import { groupBuildings, siegeList } from "./country.js";
import { POSTURES, msg, v2Error, segmented } from "./npc-common.js";

const FUNCTIONS = { HOUSING: "Mieszkanie", WELL: "Woda", GRANARY: "Spichlerz", BARRACKS: "Wojsko", SMITHY: "Rzemiosło", TOWER: "Obrona", MARKET: "Handel", CHURCH: "Wiara", PALACE: "Władza" };

// ---------------------------------------------------------------- Budowy

export function buildingsTab(body, t) {
  const { id, reload, d } = t;
  if (d.bld.error) return add(body, v2Error("Budowy", d.bld.error));
  const b = d.bld.data || {};
  const isDem = (x) => x && typeof x === "object" && (x.demolish === true || /do rozbiórki/.test(String(x.function || "")));
  const buildings = [...groupBuildings(arr(b.buildings).filter((x) => !isDem(x))), ...groupBuildings(arr(b.buildings).filter(isDem)).map((g) => ({ ...g, demolish: true }))];
  const sites = arr(b.sites);
  const queue = arr(b.queue);
  const types = arr(b.types);
  const next = b.nextTier && typeof b.nextTier === "object" ? b.nextTier : null;

  add(
    body,
    h(
      "div.grid-stats",
      h("div.stat.stat-wide", h("div.stat-ic", icon("crown")), h("div.stat-body", h("div.stat-label", "Poziom osady"), h("div.stat-value", b.tierName || (b.tier ? "Poziom " + b.tier : "—")), next ? h("div.stat-sub", `Do poziomu „${next.name}” brakuje: ${arr(next.missing).join(", ") || "nic — awans przy najbliższym ticku"}`) : h("div.stat-sub", "Najwyższy poziom osady."))),
      stat("Budynki", fmtInt(arr(b.buildings).length), "house"),
      stat("Place budowy", fmtInt(sites.length), "gear"),
      stat("W kolejce", fmtInt(queue.length), "scroll"),
    ),
    h(
      "div.grid-cards",
      card("Budynki", buildings.length ? h("ul.building-list", buildings.map((g) => h("li" + (g.demolish ? ".bld-demolish" : ""), icon("house"), h("span", g.name, g.demolish ? [" ", h("span.demolish-badge", icon("trash"), "do rozbiórki (plan v2)")] : null), g.beds ? h("span.muted.small", `${g.beds} łóżek`) : null, h("span.badge", "× " + g.count)))) : empty("Brak budynków.", "house")),
      card(
        "Place budowy",
        sites.length
          ? h(
              "ul.site-list",
              sites.map((s) => {
                const f = fraction(s);
                const placed = pick(s, "placed");
                const total = pick(s, "total");
                return h("li", h("div.site-head", h("strong", pick(s, "name", "type") || "Budynek"), h("span.muted.small", typeof placed === "number" && typeof total === "number" ? `${fmtInt(placed)} / ${fmtInt(total)} bloków · ${pct(f)}` : pct(f))), bar(f, null, f >= 0.75 ? "ok" : ""), s.x !== undefined ? h("span.muted.small", `x ${fmtInt(s.x)}, z ${fmtInt(s.z)}`) : null);
              }),
            )
          : empty("Nic się teraz nie buduje.", "gear"),
      ),
    ),
    queueCard(queue, id, reload),
    h("div.grid-cards.grid-cards-wide", orderCard(types, id, reload), developCard(b.develop, id, reload)),
    forceCard(id, reload),
  );
}

/** Wymuszenia budowy (LudnoscApi.forceRedesign / forceRoads / forceModernize) — z potwierdzeniem. */
function forceCard(id, reload) {
  const free = h("input", { type: "checkbox", "aria-label": "Przebudowa darmowa" });
  const redesign = btn("Wymuś przebudowę miasta", { kind: "danger", icon: "bolt" }, async (_e, b) => {
    let preview = null;
    try {
      const r = await api.rpc("admin.npc.planPreview", { id });
      preview = r && r.preview && typeof r.preview === "object" ? r.preview : null;
    } catch {}
    const orders = preview ? arr(preview.orders || preview.queue) : [];
    const demol = preview ? arr(preview.demolitions || preview.demolish) : [];
    const cost = preview ? preview.totalCost ?? preview.cost ?? null : null;
    const ok = await modal({
      title: "Wymusić przebudowę miasta?",
      wide: true,
      body: h(
        "div",
        h("p", "Planer policzy nowy układ miasta (plan v2) i zleci wszystko od razu: najpierw rozbiórki, potem ulice odcinkami i budynki. Czego nie zmieści kolejka, wejdzie stopniowo."),
        preview
          ? h("div.preview-grid", h("div.stat", h("div.stat-body", h("div.stat-label", "Zlecenia"), h("div.stat-value", fmtInt(orders.length)))), h("div.stat", h("div.stat-body", h("div.stat-label", "Rozbiórki"), h("div.stat-value", fmtInt(demol.length)))), h("div.stat", h("div.stat-body", h("div.stat-label", "Koszt"), h("div.stat-value", cost === null ? "—" : free.checked ? "darmowo" : formatMoney(cost)))))
          : h("p.muted", "Podgląd nie jest dostępny — Ludnosc policzy przebudowę dopiero po zatwierdzeniu."),
        demol.length ? h("ul.preview-list.preview-demol", demol.slice(0, 30).map((o) => h("li", icon("warn"), h("strong", o.name || o.type || "budynek"), o.reason ? h("span.muted", " · " + o.reason) : null))) : null,
        h("p.small.muted", free.checked ? "Skarbiec NPC nie zapłaci za przebudowę." : "Koszty pobiera skarbiec NPC w chwili rozpoczęcia każdej budowy."),
      ),
      actions: [{ label: "Anuluj", value: null }, { label: "Wymuś przebudowę", kind: "danger", value: true }],
    });
    if (ok) act(b, () => api.mutate("admin.npc.forceRedesign", { id, free: free.checked }), msg((r) => `Zlecono przebudowę: ${fmtInt(r && r.orders)} zleceń`), reload);
  });
  const roads = btn("Przebuduj drogi pod epokę", { icon: "map" }, async (_e, b) => {
    if (await confirmDanger({ title: "Przebudować drogi?", text: "Sieć ulic państwa zostanie przebudowana w stylu jego epoki (np. bruk, potem asfalt z pasami).", confirmLabel: "Przebuduj drogi" })) act(b, () => api.mutate("admin.npc.forceRoads", { id }), msg("Zlecono przebudowę dróg"), reload);
  });
  const modern = btn("Modernizuj wszystkie budynki", { icon: "house" }, async (_e, b) => {
    if (await confirmDanger({ title: "Zmodernizować budynki?", text: "Każdy przestarzały budynek zostanie przebudowany do stylu obecnej epoki państwa.", confirmLabel: "Modernizuj" })) act(b, () => api.mutate("admin.npc.forceModernize", { id }), msg((r) => `Zlecono modernizację: ${fmtInt(r && r.orders)} budynków`), reload);
  });
  return card(
    "Wymuszenia budowy",
    h(
      "div.force-list",
      h("div.force-item", h("p", h("strong", "Przebudowa miasta"), h("br"), h("span.muted.small", "Nowy układ ulic i dzielnic według planu v2 (rozbiórki na początku kolejki).")), h("label.check", free, h("span", "Darmowo")), redesign),
      h("div.force-item", h("p", h("strong", "Drogi"), h("br"), h("span.muted.small", "Ulice w stylu obecnej epoki państwa.")), roads),
      h("div.force-item", h("p", h("strong", "Modernizacja"), h("br"), h("span.muted.small", "Przestarzałe budynki przebudowane do stylu epoki.")), modern),
    ),
  );
}

function queueCard(queue, id, reload) {
  if (!queue.length) return card("Kolejka budowy", empty("Kolejka jest pusta — zamów budynek poniżej albo pozwól planerowi AI.", "scroll"));
  const n = queue.length;
  return card(
    "Kolejka budowy",
    h(
      "ol.queue-list",
      queue.map((q, pos) => {
        const index = typeof q.index === "number" ? q.index : pos;
        const name = q.name || q.type;
        const where = q.auto || q.x === null || q.x === undefined ? "miejsce wybierze planer" : `x ${fmtInt(q.x)}, z ${fmtInt(q.z)}` + (q.rotation ? ` · obrót ${q.rotation}°` : "");
        const move = (action, label, glyph, disabled) => {
          const bt = h("button.icon-btn.q-btn" + (action === "cancel" ? ".q-cancel" : ""), { type: "button", "aria-label": `${label}: ${name}`, title: label, "data-mut": "1", disabled }, glyph);
          bt.addEventListener("click", async () => {
            if (action === "cancel" && !(await confirmDanger({ title: "Anulować budowę?", text: `${name} zostanie usunięty z kolejki budowy.`, confirmLabel: "Usuń z kolejki" }))) return;
            act(bt, () => api.mutate("admin.npc.queue", { id, action, index }), action === "cancel" ? `Usunięto z kolejki: ${name}` : `Przesunięto: ${name}`, reload);
          });
          return bt;
        };
        return h(
          "li.queue-item",
          h("span.q-pos", String(pos + 1)),
          h("div.q-body", h("strong", name), h("span.muted.small", where + (q.orderedBy ? " · zamówił: " + q.orderedBy : "") + (q.orderedAt ? " · " + fmtDate(q.orderedAt) : ""))),
          q.cost !== undefined ? money(q.cost, "q-cost") : null,
          h("div.q-actions", move("up", "W górę", "↑", pos === 0), move("down", "W dół", "↓", pos === n - 1), move("cancel", "Anuluj", "✕", false)),
        );
      }),
    ),
  );
}

function orderCard(types, id, reload) {
  if (!types.length) return card("Zamów budynek", empty("Plugin nie podał listy typów budynków.", "house"));
  let picked = (types.find((x) => x.available) || {}).type || null;
  const radios = [];
  const grid = h(
    "div.type-grid",
    { role: "radiogroup", "aria-label": "Typ budynku" },
    types.map((ty) => {
      const ok = ty.available !== false;
      const r = h("input.type-radio", { type: "radio", name: "btype-" + id, value: ty.type, disabled: !ok, checked: ty.type === picked });
      radios.push(r);
      r.addEventListener("change", () => {
        picked = r.value;
        upd();
      });
      const missing = arr(ty.missing);
      return h(
        "label.type-card" + (ok ? "" : ".type-off"),
        r,
        h("span.type-top", h("strong.type-name", ty.name || ty.type), money(ty.cost, "type-cost")),
        h("span.type-meta", [FUNCTIONS[ty.function] || ty.function || null, ty.beds ? `${ty.beds} łóżek` : null, ty.minTier ? `poziom ${ty.minTier}+` : null, ty.max ? `${fmtInt(ty.count || 0)}/${ty.max}` : ty.count ? `masz ${fmtInt(ty.count)}` : null].filter(Boolean).join(" · ")),
        ty.description ? h("span.type-desc", ty.description) : null,
        ok ? h("span.type-ok", icon("check"), "Wymagania spełnione") : h("span.type-missing", icon("warn"), h("span", "Brakuje: " + (missing.join(", ") || "wymagań"))),
      );
    }),
  );

  const mode = { v: "auto" };
  const x = h("input.input", { type: "number", step: "1", placeholder: "x", "aria-label": "Współrzędna X" });
  const z = h("input.input", { type: "number", step: "1", placeholder: "z", "aria-label": "Współrzędna Z" });
  const rot = h("select.input", { "aria-label": "Obrót" }, [0, 90, 180, 270].map((r) => h("option", { value: String(r) }, r + "°")));
  const coords = h("div.coords", { hidden: true }, field("X", x), field("Z", z), field("Obrót", rot));
  const where = segmented(
    [
      { id: "auto", label: "Automatycznie (planer)", icon: "robot" },
      { id: "xz", label: "Współrzędne x / z", icon: "map" },
    ],
    "auto",
    () => {},
    "Lokalizacja",
  );
  const setMode = (v) => {
    mode.v = v;
    for (const b of where.querySelectorAll(".seg-btn")) {
      const on = b.dataset.id === v;
      b.classList.toggle("on", on);
      b.setAttribute("aria-pressed", String(on));
    }
    coords.hidden = v !== "xz";
    document.getElementById("main").dataset.dirty = "1";
  };
  // segmented() wywołuje onPick tylko przy zmianie względem wartości początkowej — tu stan trzymamy sami.
  for (const b of where.querySelectorAll(".seg-btn")) b.addEventListener("click", () => setMode(b.dataset.id));

  const summary = h("span.order-sum");
  const submit = btn("Zamów budynek", { kind: "primary", icon: "house", type: "submit" });
  const upd = () => {
    const ty = types.find((q) => q.type === picked);
    summary.textContent = ty ? `Wybrano: ${ty.name} — ${formatMoney(ty.cost)}` : "Żaden typ nie jest teraz dostępny.";
    submit.disabled = !ty;
  };
  upd();

  const form = h(
    "form.order-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          const ty = types.find((q) => q.type === picked);
          if (!ty) return;
          const params = { id, type: ty.type };
          if (mode.v === "xz") {
            const xi = Number(x.value);
            const zi = Number(z.value);
            let bad = false;
            for (const [el, v] of [[x, xi], [z, zi]]) {
              const ok = el.value.trim() !== "" && Number.isInteger(v) && Math.abs(v) <= 30_000_000;
              el.classList.toggle("invalid", !ok);
              bad = bad || !ok;
            }
            if (bad) return;
            params.x = xi;
            params.z = zi;
            params.rotation = Number(rot.value);
          }
          act(submit, () => api.mutate("admin.npc.order", params), msg(`Zamówiono: ${ty.name}`), reload);
        },
      },
    },
    grid,
    h("div.order-where", h("span.field-label", "Lokalizacja"), where, coords),
    h("div.form-actions.form-actions-split", summary, submit),
  );
  return card("Zamów budynek", h("p.muted.small", "Wyszarzone typy mają niespełnione wymagania — obok widać, czego brakuje. Koszt pobiera skarbiec NPC w chwili rozpoczęcia budowy."), form);
}

function developCard(dev, id, reload) {
  const sug = dev && typeof dev.suggested === "number" ? dev.suggested : null;
  const max = dev && typeof dev.max === "number" ? dev.max : null;
  const amt = moneyInput({ placeholder: "np. 50 zł", value: sug ? formatMoney(sug) : "" });
  const submit = btn("Rozbuduj teraz", { kind: "primary", icon: "bolt", type: "submit" });
  const label = submit.querySelector("span");
  const upd = () => {
    const v = amt.value();
    label.textContent = v ? `Rozbuduj teraz (budżet ${formatMoney(v)})` : "Rozbuduj teraz";
  };
  amt.input.addEventListener("input", upd);
  upd();
  const form = h(
    "form.inline-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          const v = amt.value();
          if (v === null || v <= 0 || (max !== null && v > max)) {
            amt.input.classList.add("invalid");
            amt.input.focus();
            return;
          }
          amt.input.classList.remove("invalid");
          act(submit, () => api.mutate("admin.npc.develop", { id, budget: v }), msg((r) => (r && Array.isArray(r.ordered) ? "Zamówiono: " + r.ordered.map((o) => o.name).join(", ") : "Rozbudowa zlecona")), reload);
        },
      },
    },
    field("Budżet", amt.el),
    submit,
  );
  return card("Rozbuduj teraz", h("p.muted.small", "Planer AI sam wybierze i zamówi najpotrzebniejsze budynki w ramach budżetu (pobieranego ze skarbca NPC)."), form, max !== null ? h("p.muted.small", "Maksymalny budżet teraz: " + formatMoney(max) + (sug ? " · sugerowany: " + formatMoney(sug) : "")) : null);
}

// ---------------------------------------------------------------- Armia

export function armyTab(body, t) {
  const { c, id, ctx, reload, d } = t;
  if (d.army.error) add(body, v2Error("Armia — dane v2", d.army.error));
  const a = d.army.data || c.army || {};
  const types = arr(pick(a, "types")).map((x) => ({ id: pick(x, "id", "type"), name: pick(x, "name") || pick(x, "id"), desc: pick(x, "description"), cost: pick(x, "cost"), wage: pick(x, "wage", "upkeep"), count: Number(pick(x, "count")) || 0 }));
  const total = pick(a, "total") ?? types.reduce((s, x) => s + x.count, 0);
  const max = pick(a, "max");
  let morale = pick(a, "morale");
  if (typeof morale === "number" && morale > 0 && morale <= 1 && !Number.isInteger(morale)) morale *= 100;
  const wages = pick(a, "wages") ?? types.reduce((s, x) => s + x.count * (x.wage || 0), 0);
  const outposts = arr(pick(a, "outposts"));
  const posture = pick(a, "posture");

  // postawa
  const postureHelp = h("p.muted.small.posture-help", (POSTURES.find((p) => p.id === posture) || {}).help || "Postawa nieznana (plugin nie podał).");
  const postureSeg = segmented(
    POSTURES.map((p) => ({ id: p.id, label: p.label, icon: p.id === "DEFENSIVE" ? "shield" : p.id === "OFFENSIVE" ? "swords" : "scroll" })),
    posture,
    (v, b) => act(b, () => api.mutate("admin.npc.setPosture", { id, posture: v }), msg("Postawa armii: " + POSTURES.find((p) => p.id === v).label.toLowerCase()), reload),
    "Postawa armii",
  );

  // cel oblężeń
  const others = ctx.overview.countries.filter((x) => x.id !== id).map((x) => ({ ...x, color: safeColor(x.color) }));
  const curT = pick(a, "siegeTarget");
  const curId = curT && typeof curT === "object" ? curT.id : curT || "";
  const tSel = h("select.input", { "aria-label": "Cel oblężeń" }, h("option", { value: "" }, "Auto — AI wybiera cel"), others.map((o) => h("option", { value: o.id, selected: o.id === curId }, o.name)));
  const tSave = btn("Zapisz cel", { kind: "primary", type: "submit", icon: "swords" });
  const autoT = pick(a, "autoTarget");
  const targetForm = h(
    "form.inline-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          const v = tSel.value || null;
          if ((v || "") === curId) return;
          const name = v ? tSel.selectedOptions[0].textContent : "auto";
          act(tSave, () => api.mutate("admin.npc.setSiegeTarget", { id, target: v }), v ? `Cel oblężeń: ${name}` : "Cel oblężeń: auto (AI wybiera)", reload);
        },
      },
    },
    field("Cel", tSel),
    tSave,
  );

  // dodaj wojsko (bez kosztu)
  const typeSel = h("select.input", { "aria-label": "Rodzaj" }, (types.length ? types : [{ id: "piechota", name: "Piechota" }]).map((x) => h("option", { value: x.id }, x.name)));
  const count = h("input.input", { type: "number", min: "1", max: "100", value: "5", step: "1", "aria-label": "Liczba" });
  const addBtn = btn("Dodaj wojsko", { kind: "primary", icon: "swords", type: "submit" });
  const addForm = h(
    "form.inline-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          const n = Math.floor(Number(count.value));
          if (!(n >= 1 && n <= 100)) return count.classList.add("invalid");
          act(addBtn, () => api.mutate("admin.npc.armyAdd", { id, type: typeSel.value, count: n }), `Dodano ${n} żołnierzy`, reload);
        },
      },
    },
    field("Jednostka", typeSel),
    field("Liczba", count),
    addBtn,
  );

  add(
    body,
    h(
      "div.grid-stats",
      stat("Żołnierze", fmtInt(total) + (max ? " / " + fmtInt(max) : ""), "swords"),
      stat("Żołd dzienny", money(wages), "coins"),
      h("div.stat.stat-wide", h("div.stat-ic", icon("shield")), h("div.stat-body", h("div.stat-label", "Morale"), typeof morale === "number" ? bar(morale / 100, Math.round(morale) + "%", morale < 30 ? "war" : morale < 60 ? "warn" : "ok") : h("div.stat-value", "—"), typeof morale === "number" && morale < 30 ? h("div.stat-sub.neg", "Niskie morale grozi dezercją.") : null)),
      stat("Posterunki", fmtInt(outposts.length), "map"),
    ),
    h(
      "div.grid-cards",
      card("Postawa armii", postureSeg, postureHelp),
      card(
        "Cel oblężeń",
        h("p.muted.small", "Wybrany kraj AI oblega, gdy jest z nim w wojnie. „Auto” zostawia wybór AI."),
        targetForm,
        h("p.small", "Teraz: ", curT && typeof curT === "object" ? countryName(curT) : h("strong", "auto"), !curId && autoT && typeof autoT === "object" ? [h("span.muted", " · AI celuje w: "), countryName(autoT)] : null),
      ),
    ),
    card(
      "Oddziały",
      table({
        columns: [
          { key: "name", label: "Jednostka", render: (x) => h("span.unit", h("strong", x.name), x.desc ? h("span.muted.small", x.desc) : null) },
          { key: "wage", label: "Żołd / dzień", num: true, render: (x) => money(x.wage) },
          { key: "count", label: "Liczba", num: true, render: (x) => fmtInt(x.count) },
          { key: "share", label: "Udział", render: (x) => h("span.mini", bar(total ? x.count / total : 0, null), h("span", total ? pct(x.count / total) : "—")) },
          { key: "act", label: "Akcje", cls: "col-actions", render: (x) => (x.count > 0 ? btn("Rozwiąż", { small: true, kind: "danger" }, (_e, b) => disband(x, b)) : null) },
        ],
        rows: types,
        emptyText: "Brak danych o jednostkach (plugin Armia niedostępny?)",
      }),
    ),
    h(
      "div.grid-cards",
      card("Dodaj wojsko (bez kosztu)", addForm),
      card(
        "Posterunki",
        outposts.length
          ? h(
              "ul.outpost-list",
              outposts.map((o) => h("li", icon("shield"), h("div", h("strong", o.name || "Posterunek"), h("span.muted.small", `${o.world || "świat"} · x ${fmtInt(o.x)}, z ${fmtInt(o.z)}`)), h("span.badge", { title: "Żołnierze na posterunku" }, fmtInt(o.soldiers ?? 0) + " żoł."))),
            )
          : empty("Brak posterunków — AI stawia je przy granicach, gdy ma wolne wojsko.", "map"),
      ),
    ),
    arr(a.sieges).length ? card("Oblężenia", siegeList({ sieges: arr(a.sieges) }, new Map(ctx.overview.countries.map((x) => [x.id, x])))) : null,
  );

  async function disband(x, b) {
    const n = h("input.input", { type: "number", min: "1", max: String(x.count), value: "1", step: "1" });
    const r = await modal({
      title: "Rozwiąż oddział: " + x.name,
      body: h("div", h("p", "Żołnierze NPC zostaną rozwiązani bez zwrotu kosztów."), field("Ilu żołnierzy rozwiązać? (max " + x.count + ")", n)),
      actions: [
        { label: "Anuluj", value: null },
        {
          label: "Rozwiąż",
          kind: "danger",
          onClick: () => {
            const v = Math.floor(Number(n.value));
            if (!(v >= 1 && v <= x.count)) {
              n.classList.add("invalid");
              return false;
            }
            return v;
          },
        },
      ],
    });
    if (r) act(b, () => api.mutate("admin.npc.disband", { id, type: x.id, count: r }), msg(`Rozwiązano: ${x.name} × ${r}`), reload);
  }
}
