// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Państwa NPC: lista (admin i zarządca globalny widzą wszystkie, zarządca przypisany tylko swoje),
// tworzenie (admin, zarządca globalny) i strona państwa NPC z zakładkami (v2): Przegląd, Stosunki, Gospodarka,
// Ludność i jedzenie, Budowy, Armia, Osobowość i dziennik AI. Plugin i tak sprawdza uprawnienia przy każdym RPC.
import { h, icon, modal, skeleton, add, clear } from "../dom.js";
import * as api from "../api.js";
import { normCountry, fmtInt, fmtDate, fmtTime, pick, arr, safeColor } from "../util.js";
import { card, empty, field, act, btn, table, countryName, shield, bar, tabs } from "../components.js";
import { NPC_PARAMS, settingValue, isPaused, LOG_CATS } from "./npc-common.js";
import { overviewTab, relationsTab, economyTab, populationTab } from "./npc-panels.js";
import { buildingsTab, armyTab } from "./npc-build.js";
import { loadPostep, postepTab } from "./postep.js";
import { loadPlanData, planTab } from "./npc-plan.js";

export { NPC_PARAMS };

function npcCountries(ctx) {
  return ctx.overview.countries.filter((c) => c.npc && ctx.roles.canNpcCountry(c.id)).map((c) => ({ ...c, color: safeColor(c.color) }));
}

// ---------------------------------------------------------------- lista

export const npcListView = {
  title: "Państwa NPC",
  async load(ctx) {
    if (!ctx.roles.canNpc) return { denied: true };
    const list = npcCountries(ctx);
    const details = await Promise.all(list.slice(0, 12).map((c) => api.read("country.get", { id: c.id }).then((r) => r.data, () => null)));
    return { list, details: new Map(details.filter(Boolean).map((d) => [d.id, normCountry(d)])) };
  },
  draw(main, d, ctx) {
    if (d.denied) return add(main, card("Brak dostępu", h("p", "Nie zarządzasz żadnym państwem NPC.")));
    const r = ctx.roles;
    const head = h(
      "div.page-head",
      h("div", h("h1", "Państwa NPC"), h("p.muted", r.admin ? "Wszystkie państwa sterowane przez AI." : r.npcManager ? "Jesteś zarządcą wszystkich państw NPC." : "Państwa NPC powierzone Twojej opiece.")),
      r.canNpcCreate ? btn("Utwórz państwo NPC", { kind: "primary", icon: "crown" }, () => createNpc(ctx)) : null,
    );
    const rows = d.list.map((c) => {
      const det = d.details.get(c.id);
      const npc = det && det.npcData;
      return { ...c, paused: isPaused(npc), settings: npc && npc.settings };
    });
    const t = table({
      columns: [
        { key: "name", label: "Państwo", render: (c) => countryName({ ...c, npc: false }, { link: false }), sort: (c) => c.name },
        { key: "ruler", label: "Władca", render: (c) => c.ruler || "—" },
        { key: "paused", label: "AI", render: (c) => (c.paused === null ? "—" : c.paused ? h("span.status.status-off", icon("pause"), "wstrzymana") : h("span.status.status-on", icon("play"), "aktywna")), sort: (c) => (c.paused ? 1 : 0) },
        { key: "aggr", label: "Agresja", num: true, render: (c) => miniBar(settingValue(c.settings, NPC_PARAMS[0])), sort: (c) => settingValue(c.settings, NPC_PARAMS[0]) ?? -1 },
        { key: "chunks", label: "Chunki", num: true, render: (c) => fmtInt(c.chunks) },
        { key: "soldiers", label: "Żołnierze", num: true, render: (c) => fmtInt(c.soldiers) },
        { key: "population", label: "Ludność", num: true, render: (c) => fmtInt(c.population) },
      ],
      rows,
      sort: { key: "name", dir: "asc" },
      onRowClick: (c) => ctx.go("/npc/" + encodeURIComponent(c.id)),
      emptyText: r.canNpcCreate ? "Nie ma jeszcze państw NPC — utwórz pierwsze." : "Nie masz przypisanych państw NPC.",
    });
    add(main, head, h("section.card.card-flush", t));
  },
};

function miniBar(v) {
  if (typeof v !== "number" || !Number.isFinite(v)) return "—";
  return h("span.mini", bar(v / 100, null, v >= 70 ? "war" : v >= 40 ? "warn" : "ok"), h("span", String(v)));
}

async function createNpc(ctx) {
  const name = h("input.input", { type: "text", maxlength: "32", required: true, placeholder: "np. Księstwo Mazowieckie" });
  const color = h("input.color-input", { type: "color", value: "#8a2be2" });
  const ruler = h("input.input", { type: "text", maxlength: "32", placeholder: "np. Bolesław" });
  const world = h("input.input", { type: "text", maxlength: "64", value: "world" });
  const x = h("input.input", { type: "number", step: "1", value: "0" });
  const z = h("input.input", { type: "number", step: "1", value: "0" });
  const res = await modal({
    title: "Nowe państwo NPC",
    wide: true,
    body: h("div.form-grid", field("Nazwa", name), field("Kolor", color), field("Władca (imię NPC)", ruler), field("Świat", world), field("Stolica X", x), field("Stolica Z", z), h("p.muted.small.span-2", "Stolica zostanie założona w chunku zawierającym podane współrzędne. AI zacznie działać od następnego ticku.")),
    actions: [
      { label: "Anuluj", value: null },
      {
        label: "Utwórz",
        kind: "primary",
        onClick: async (dlg) => {
          const n = name.value.trim();
          const xi = Math.trunc(Number(x.value));
          const zi = Math.trunc(Number(z.value));
          if (!n) return name.classList.add("invalid"), name.focus(), false;
          if (!world.value.trim()) return world.classList.add("invalid"), false;
          if (!Number.isFinite(xi) || !Number.isFinite(zi)) return false;
          const b = dlg.querySelector(".btn-primary");
          const r = await act(b, () => api.mutate("admin.npc.create", { name: n, color: color.value.toUpperCase(), ruler: ruler.value.trim() || n, world: world.value.trim(), x: xi, z: zi }), `Utworzono państwo NPC: ${n}`);
          return r === undefined ? false : r || true;
        },
      },
    ],
  });
  if (res) {
    const id = res && typeof res === "object" ? res.id : typeof res === "string" ? res : null;
    if (id) ctx.go("/npc/" + encodeURIComponent(id));
    else ctx.forceReload();
  }
}

// ---------------------------------------------------------------- szczegóły (zakładki v2)

/** Lista graczy do wyboru zarządcy — odświeżana co minutę, żeby nie mnożyć RPC przy każdej akcji. */
let playersMem = { at: 0, data: null };
async function playersCached() {
  if (playersMem.data && Date.now() - playersMem.at < 60000) return playersMem.data;
  const data = await api.read("admin.players", {}).then((x) => x.data, () => null);
  if (data) playersMem = { at: Date.now(), data };
  return data;
}

const NPC_TABS = [
  { id: "przeglad", label: "Przegląd", icon: "eye" },
  { id: "stosunki", label: "Stosunki", icon: "scroll" },
  { id: "gospodarka", label: "Gospodarka", icon: "coins" },
  { id: "ludnosc", label: "Ludność i jedzenie", icon: "users" },
  { id: "budowy", label: "Budowy", icon: "house" },
  { id: "plan", label: "Plan", icon: "map" },
  { id: "armia", label: "Armia", icon: "swords" },
  { id: "postep", label: "Postęp", icon: "flask" },
  { id: "osobowosc", label: "Osobowość i dziennik", icon: "robot" },
];

/** Filtry dziennika AI per kraj — przetrwają automatyczne odświeżanie widoku. */
const logFilters = new Map();
const logParams = (id) => {
  const f = logFilters.get(id) || { cat: "ALL", q: "" };
  const p = { id, limit: 200 };
  if (f.cat !== "ALL") p.category = f.cat;
  if (f.q) p.query = f.q;
  return p;
};

/** Odczyty potrzebne zakładce: [klucz, metoda, parametry]. */
const TAB_READS = {
  przeglad: (id) => [["history", "admin.npc.history", { id, days: 7 }]],
  stosunki: (id) => [["rel", "admin.npc.relations", { id }]],
  gospodarka: (id) => [["eco", "admin.npc.economy", { id }]],
  ludnosc: (id) => [["eco", "admin.npc.economy", { id }]],
  budowy: (id) => [["bld", "admin.npc.buildings", { id }]],
  armia: (id) => [["army", "admin.npc.army", { id }]],
  postep: () => [],
  plan: () => [],
  osobowosc: (id) => [["log", "admin.npc.log", logParams(id)]],
};

/** Odczyt v2, którego błąd nie psuje całej strony (np. starszy plugin) — 401 przechodzi dalej. */
function optRead(method, params) {
  return api.read(method, params).then(
    (r) => ({ data: r.data, stale: r.stale }),
    (e) => (e.status === 401 ? Promise.reject(e) : { error: e }),
  );
}

export const npcDetailView = {
  title: (d) => (d && d.c ? d.c.name + " (NPC)" : "Państwo NPC"),
  skeleton: () => h("div", h("div.card", skeleton(3)), h("div.grid-cards", h("div.card", skeleton(6)), h("div.card", skeleton(6)))),
  async load(ctx) {
    const [id, tabParam] = ctx.params;
    if (!ctx.roles.canNpcCountry(id)) return { denied: true };
    const tab = NPC_TABS.some((t) => t.id === tabParam) ? tabParam : "przeglad";
    const reads = TAB_READS[tab](id);
    const [r, players, postep, plan, ...parts] = await Promise.all([api.read("country.get", { id }), ctx.roles.admin && tab === "osobowosc" ? playersCached() : null, tab === "postep" ? loadPostep(id) : null, tab === "plan" ? loadPlanData(id) : null, ...reads.map(([, m, p]) => optRead(m, p))]);
    const ov = ctx.overview.countries.find((x) => x.id === id);
    const d = { c: normCountry(r.data, ov), players: Array.isArray(players) ? players : null, tab, postep, plan };
    reads.forEach(([k], i) => (d[k] = parts[i]));
    return d;
  },
  draw(main, d, ctx) {
    if (d.denied) return add(main, card("Brak dostępu", h("p", "Nie zarządzasz tym państwem NPC."), h("a.btn", { href: "#/npc" }, "Wróć do listy")));
    const { c } = d;
    const npc = c.npcData || {};
    const settings = npc.settings || {};
    const paused = isPaused(npc);
    const id = c.id;
    const reload = ctx.reload;

    const head = h(
      "section.country-head",
      h("div.ch-crest", shield(c.color, "xl")),
      h(
        "div.ch-main",
        h("h1", c.name, h("span.tag.tag-npc", "NPC")),
        h("p.muted", "Władca: ", h("strong", c.ruler || "—"), " · AI: ", paused === null ? "—" : paused ? h("span.status.status-off", "wstrzymana") : h("span.status.status-on", "aktywna"), npc.nextTickAt && !paused ? " · następny tick o " + fmtTime(npc.nextTickAt) : null),
      ),
      h(
        "div.ch-actions",
        paused ? btn("Wznów AI", { kind: "primary", icon: "play" }, (_e, b) => act(b, () => api.mutate("admin.npc.pause", { id, paused: false }), "AI wznowiona", reload)) : btn("Wstrzymaj AI", { icon: "pause" }, (_e, b) => act(b, () => api.mutate("admin.npc.pause", { id, paused: true }), "AI wstrzymana", reload)),
        btn("Wymuś tick", { icon: "bolt" }, (_e, b) => act(b, () => api.mutate("admin.npc.tick", { id }), "Tick AI wykonany", reload)),
        h("a.btn", { href: "#/kraj/" + encodeURIComponent(id) }, icon("eye"), h("span", "Widok kraju")),
      ),
    );
    head.style.setProperty("--c", c.color);

    const warnings = [
      npc.available === false ? "Plugin Armia jest niedostępny — AI tego państwa nie działa, a ustawień nie da się zmienić." : null,
      npc.globalPaused ? "AI wszystkich państw NPC jest wstrzymana globalnie (/npckraj pauza wszystkie)." : null,
    ].filter(Boolean);

    const base = "#/npc/" + encodeURIComponent(id);
    const body = h("div.tab-body");
    const t = { c, npc, settings, id, ctx, d, reload };
    const draw = { przeglad: overviewTab, stosunki: relationsTab, gospodarka: economyTab, ludnosc: populationTab, budowy: buildingsTab, armia: armyTab, postep: (b) => postepTab(b, d.postep, { id, name: c.name, ctx, reload }), plan: (b) => planTab(b, d.plan, { id, c, ctx, reload }), osobowosc: personalityTab }[d.tab];
    draw(body, t);
    add(main, head, warnings.length ? h("div.alert.alert-warn", icon("warn"), h("span", warnings.join(" "))) : null, tabs(NPC_TABS.map((x) => ({ ...x, href: base + (x.id === "przeglad" ? "" : "/" + x.id) })), d.tab), body);
  },
};

// ---------------------------------------------------------------- Osobowość i dziennik AI

function personalityTab(body, t) {
  const { c, npc, settings, id, ctx, d, reload } = t;

  const inputs = new Map();
  const rows = NPC_PARAMS.map((p) => {
    const v = settingValue(settings, p);
    if (p.range) {
      const out = h("output.range-val", v === undefined ? "—" : String(v));
      const inp = h("input.range", { type: "range", min: "0", max: "100", step: "1", value: String(v ?? 50), "aria-label": p.label });
      inp.addEventListener("input", () => (out.textContent = inp.value));
      inputs.set(p.key, { inp, orig: v });
      return h("div.slider-row", h("div.slider-head", h("span.field-label", p.label), out), inp, h("span.field-help", p.help));
    }
    const inp = h("input.input", { type: "number", min: "0", step: "1", value: v === undefined ? "" : String(v), "aria-label": p.label });
    inputs.set(p.key, { inp, orig: v });
    return field(p.label, inp, p.help);
  });
  const savePers = btn("Zapisz ustawienia", { kind: "primary" }, (_e, b) =>
    act(
      b,
      async () => {
        let n = 0;
        for (const [key, { inp, orig }] of inputs) {
          if (inp.value === "") continue;
          const val = Math.trunc(Number(inp.value));
          if (!Number.isFinite(val) || val < 0) throw new Error("Nieprawidłowa wartość: " + key);
          if (val === orig) continue;
          await api.mutate("admin.npc.set", { id, param: key, value: val });
          n++;
        }
        return n;
      },
      (n) => (n ? `Zapisano ustawienia (${n})` : "Brak zmian do zapisania"),
      reload,
    ),
  );

  // Władca i skin
  const rName = h("input.input", { type: "text", maxlength: "32", value: c.ruler || "" });
  const rSkin = h("input.input", { type: "text", maxlength: "32", placeholder: "nick Mojang ze skinem", value: pick(npc, "skin", "rulerSkin") || "" });
  const saveRuler = btn("Zmień władcę", { kind: "primary", icon: "crown", type: "submit" });
  const rulerForm = h("form.inline-form", { on: { submit: (e) => { e.preventDefault(); const n = rName.value.trim(); if (!n) return rName.classList.add("invalid"); act(saveRuler, () => api.mutate("admin.npc.ruler", { id, name: n, skin: rSkin.value.trim() }), "Zmieniono władcę NPC", reload); } } }, field("Imię", rName), field("Skin", rSkin), saveRuler);

  // Zarządca (tylko admin)
  let managerCard = null;
  if (ctx.roles.admin) {
    const cur = npc.manager;
    const curName = cur && typeof cur === "object" ? cur.name : typeof cur === "string" ? cur : pick(npc, "managerName") || null;
    let control;
    if (d.players) {
      const names = [...new Set(d.players.map((p) => p.name).filter(Boolean))].sort((a, b) => a.localeCompare(b, "pl"));
      if (curName && !names.includes(curName)) names.unshift(curName);
      control = h("select.input", { "aria-label": "Zarządca" }, h("option", { value: "" }, "— brak —"), names.map((n) => h("option", { value: n, selected: n === curName }, n)));
    } else control = h("input.input", { type: "text", maxlength: "32", placeholder: "nick (puste = brak)", value: curName || "" });
    const saveMgr = btn("Zapisz zarządcę", { kind: "primary", type: "submit" });
    managerCard = card(
      "Zarządca",
      h("p.muted.small", "Wskazany gracz może zarządzać tylko tym państwem NPC (wszystkie zakładki tej strony)."),
      h("form.inline-form", { on: { submit: (e) => { e.preventDefault(); const v = control.value.trim(); act(saveMgr, () => api.mutate("admin.npc.setManager", { id, player: v || null }), v ? `Zarządcą jest teraz ${v}` : "Usunięto zarządcę", reload); } } }, field("Zarządca", control), saveMgr),
      h("p.small", "Obecnie: ", h("strong", curName || "brak")),
    );
  }

  add(body, h("div.grid-cards", card("Osobowość władcy", h("div.sliders", rows), h("div.form-actions", savePers)), h("div.stack", card("Władca NPC", rulerForm), managerCard)), logCard(t));
}

const fold = (s) => String(s).toLowerCase().normalize("NFD").replace(/\p{M}/gu, "").replace(/ł/g, "l");

/** Podświetla trafienia wyszukiwania (węzły tekstowe + <mark>, bez innerHTML). Porównanie po znakach bez ogonków. */
function highlight(text, q) {
  const src = [...String(text)];
  const needle = fold(q || "");
  if (!needle) return String(text);
  const folded = src.map((ch) => fold(ch));
  const out = [];
  let i = 0;
  let last = 0;
  while (i < src.length) {
    let j = i;
    let acc = "";
    while (j < src.length && acc.length < needle.length) acc += folded[j++];
    if (acc === needle) {
      if (i > last) out.push(src.slice(last, i).join(""));
      out.push(h("mark", src.slice(i, j).join("")));
      last = i = j;
    } else i++;
  }
  if (!out.length) return String(text);
  if (last < src.length) out.push(src.slice(last).join(""));
  return out;
}

function logCard(t) {
  const { npc, id, d } = t;
  const f = logFilters.get(id) || { cat: "ALL", q: "" };
  logFilters.set(id, f);
  const chipsBox = h("div.filter-chips", { role: "group", "aria-label": "Kategorie dziennika" });
  const listBox = h("div.log-box");
  const count = h("span.muted.small.log-count");
  const search = h("input.input.search.log-search", { type: "search", placeholder: "Szukaj w dzienniku…", "aria-label": "Szukaj w dzienniku AI", maxlength: "64", value: f.q });

  // Starszy plugin bez admin.npc.log: dziennik z country.get (bez kategorii), wyszukiwanie lokalne.
  const legacy = d.log && d.log.error ? arr(pick(npc, "log", "decisions")).map((e) => (typeof e === "string" ? { text: e } : { time: pick(e, "time", "at", "ts"), text: pick(e, "text", "message", "decision") || "" })) : null;
  let data = legacy ? null : (d.log && d.log.data) || { entries: [], counts: {}, total: 0 };

  const drawChips = () => {
    clear(chipsBox);
    if (legacy) return;
    const counts = (data && data.counts) || {};
    for (const cat of LOG_CATS) {
      const on = f.cat === cat.id;
      const n = counts[cat.id];
      const b = h("button.chip-btn.cat-" + cat.id + (on ? ".on" : ""), { type: "button", "aria-pressed": String(on) }, h("span", cat.label), typeof n === "number" ? h("span.chip-n", fmtInt(n)) : null);
      b.addEventListener("click", () => {
        if (f.cat === cat.id) return;
        f.cat = cat.id;
        refresh();
      });
      chipsBox.appendChild(b);
    }
  };
  const drawList = () => {
    clear(listBox);
    let entries;
    if (legacy) {
      entries = f.q ? legacy.filter((e) => fold(e.text).includes(fold(f.q))) : legacy;
      count.textContent = `${fmtInt(entries.length)} wpisów`;
    } else {
      entries = arr(data.entries);
      const total = typeof data.total === "number" ? data.total : entries.length;
      count.textContent = total > entries.length ? `Pokazano ${fmtInt(entries.length)} z ${fmtInt(total)} wpisów` : `${fmtInt(total)} wpisów`;
    }
    if (!entries.length) {
      listBox.appendChild(empty(f.q || f.cat !== "ALL" ? "Brak wpisów pasujących do filtrów." : "AI nie podjęła jeszcze żadnych decyzji.", "robot"));
      return;
    }
    listBox.appendChild(
      h(
        "ol.ai-log",
        entries.slice(0, 200).map((e) => {
          const cat = LOG_CATS.find((x) => x.id === e.category && x.id !== "ALL");
          return h("li", e.time ? h("time", fmtDate(e.time)) : null, h("span.log-text", cat ? h("span.log-cat.cat-" + cat.id, cat.label) : null, h("span", highlight(e.text, f.q))));
        }),
      ),
    );
  };
  let seq = 0;
  const refresh = async () => {
    const my = ++seq;
    drawChips();
    if (legacy) return drawList();
    listBox.classList.add("loading");
    try {
      const r = await api.read("admin.npc.log", logParams(id));
      if (my !== seq) return;
      data = r.data || data;
    } catch (e) {
      if (my !== seq) return;
      count.textContent = "Nie udało się wczytać dziennika: " + (e.message || e);
    } finally {
      if (my === seq) listBox.classList.remove("loading");
    }
    if (my !== seq) return;
    drawChips();
    drawList();
  };
  let timer = null;
  search.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      f.q = search.value.trim().slice(0, 64);
      refresh();
    }, 250);
  });
  drawChips();
  drawList();
  return card(
    "Dziennik decyzji AI",
    legacy ? h("p.muted.small", "Starsza wersja pluginu — dziennik bez kategorii, wyszukiwanie działa lokalnie.") : null,
    h("div.log-filters", chipsBox, h("div.search-row.log-search-row", icon("search"), search, count)),
    listBox,
  );
}
