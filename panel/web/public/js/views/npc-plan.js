// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — zakładka „Plan” państwa NPC: edytor planu (strefy, drogi, mury z bramami, budynki) na podkładzie mapy
// z góry oraz narzędzie „Teren” (dodawanie i usuwanie chunków). Zapis przez admin.npc.setPlan (Ludnosc buduje wg planu),
// podgląd skutków (planPreview), eksport/import JSON, szkic w localStorage, cofnij/ponów. Kształty: PANEL.md.
import { h, icon, modal, confirmDanger, toast, add, clear } from "../dom.js";
import * as api from "../api.js";
import { formatMoney } from "../money.js";
import { fmtInt, fmtTime, arr } from "../util.js";
import { card, empty, field, act, btn } from "../components.js";
import { segmented } from "./npc-common.js";
import * as M from "../plan/model.js";
import { planAccess, planMethods, levelSelectionError, planExecution } from "../plan/access.js";
import { PlanEditor, categoryColor } from "../plan/editor.js";

const DRAFT_PREFIX = "panel-plan-draft:";
/** Stan edytora per kraj — przetrwa przeładowanie widoku (np. po zapisie albo zmianie motywu). */
const store = new Map();
let keyHandlerOn = false;

const TOOLS = [
  { id: "select", label: "Zaznacz", icon: "cursor", key: "V", hint: "Kliknij element, by go zaznaczyć; przeciągnij, by przesunąć; przeciągnij biały punkt, by zmienić kształt. Delete usuwa, R obraca budynek. Puste tło przesuwa mapę." },
  { id: "zone", label: "Strefa", icon: "zone", hint: "Wielokąt: klikaj kolejne wierzchołki, zamknij klikiem w pierwszy punkt albo Enterem. Prostokąty: przeciągnij jak pędzlem." },
  { id: "road", label: "Droga", icon: "road", hint: "Klikaj kolejne punkty drogi; Enter albo podwójny klik kończy. Backspace cofa ostatni punkt, Esc anuluje." },
  { id: "wall", label: "Mur", icon: "wall", hint: "Ręczny: klikaj punkty; klik w pierwszy punkt zamyka mur, Enter zostawia go otwartym. „Bramy”: klik na murze stawia bramę, klik w bramę ją usuwa." },
  { id: "building", label: "Budynek", icon: "house", hint: "Wybierz typ z listy obok, potem kliknij na mapie. R obraca (front = trójkąt), priorytet 1 buduje się najpierw." },
  { id: "eraser", label: "Gumka", icon: "eraser", hint: "Kliknij albo przeciągnij po elementach planu, żeby je usunąć (Ctrl+Z przywraca)." },
  { id: "leveling", label: "Wyrównaj teren", icon: "terrain", hint: "Przeciągnij prostokąt na własnej ziemi. Wybierz poziom automatyczny albo Y, obejrzyj koszt i zatwierdź prace budowniczych." },
  { id: "terrain", label: "Teren", icon: "terrain", hint: "Zaznacz chunki do dodania (zielone) albo usunięcia (czerwone); Shift odwraca tryb. Cudze kraje i obszary chronione są zablokowane. Zmiany zatwierdzasz w panelu „Teren”." },
  { id: "hand", label: "Przesuń mapę", icon: "hand", hint: "Przeciągnij, by przesunąć mapę; kółko myszy albo dwa palce przybliżają." },
];

const CAT_ICONS = { housing: "house", economy: "coins", civic: "scroll", military: "swords", defense: "shield", religious: "church", representative: "crown", food: "wheat", farm: "wheat", infrastructure: "road", highrise: "tower", other: "house" };

function readDraft(id) {
  try {
    const v = localStorage.getItem(DRAFT_PREFIX + id);
    return v ? JSON.parse(v) : null;
  } catch {
    return null;
  }
}
function writeDraft(id, plan) {
  try {
    localStorage.setItem(DRAFT_PREFIX + id, JSON.stringify({ at: Date.now(), plan }));
    return true;
  } catch {
    return false;
  }
}
function dropDraft(id) {
  try {
    localStorage.removeItem(DRAFT_PREFIX + id);
  } catch {}
}

/** Odczyty zakładki: plan, typy budynków (z rozmiarem), podkład mapy (z pamięci, jeśli świeży). */
export async function loadPlanData(id, { fresh = false, margin, country = false } = {}) {
  const methods = planMethods(country);
  const st = store.get(id);
  const m = margin ?? (st && st.margin) ?? 32;
  const needMap = fresh || !st || !st.map || st.map.margin !== m || Date.now() - st.mapAt > 10 * 60 * 1000;
  const [plan, bld, map] = await Promise.all([
    api.read(methods.get, { id }).then((r) => ({ data: r.data, stale: r.stale }), (e) => (e.status === 401 ? Promise.reject(e) : { error: e })),
    api.read(methods.buildings, { id }).then((r) => r.data, (e) => (e.status === 401 ? Promise.reject(e) : null)),
    needMap ? api.rpc(methods.map, fresh ? { id, margin: m, fresh: true } : { id, margin: m }).then((d) => ({ data: d }), (e) => (e.status === 401 ? Promise.reject(e) : { error: e })) : { data: st.map, reused: true },
  ]);
  const execution = plan.data?.plan ? await api.rpc(methods.preview, { id }).then(data => ({ data }), error => ({ error })) : null;
  return { plan, bld, map, execution, margin: m };
}

function typesMap(bld) {
  const out = new Map();
  for (const t of arr(bld && bld.types)) {
    out.set(String(t.type).toLowerCase(), { ...t, name: t.name || t.type, size: t.size && Number.isFinite(t.size.w) ? t.size : null, category: t.category || null });
  }
  return out;
}

export function planTab(body, data, t) {
  const { id, c } = t;
  const methods = planMethods(!!t.countryPlan);
  const permissions = t.permissions || planAccess(c, c.myRank, t.ctx.roles);
  if (!data) return add(body, empty("Nie masz dostępu do planu tego państwa."));
  const main = document.getElementById("main");
  if (main) main.dataset.keep = "1";
  if (data.map.error) {
    const e = data.map.error;
    const old = /nieznana metoda|unknown method/i.test(e.message || "");
    return add(body, card("Plan państwa", h("div.empty", icon("map"), h("p", old ? "Edytor planu wymaga nowszej wersji pluginu Panel." : e.offline ? "Mapa jest niedostępna, gdy serwer gry jest offline." : "Nie udało się wczytać mapy: " + (e.message || e)), btn("Spróbuj ponownie", { icon: "bolt" }, () => t.reload()))));
  }
  const types = typesMap(data.bld);
  const planInfo = data.plan.data || {};
  let st = store.get(id);
  const serverPlan = M.fromServer(planInfo.plan);
  const serverJson = JSON.stringify(M.toJson(serverPlan));
  if (!st) {
    st = { margin: data.margin, serverJson, savedWarnings: [], free: false };
    store.set(id, st);
  }
  st.map = data.map.data;
  if (!data.map.reused) st.mapAt = Date.now();
  st.margin = data.margin;
  st.types = types;
  st.execution = data.execution?.data || null;
  st.executionError = data.execution?.error || null;
  if (!permissions.free) { st.free = false; st.levelFree = false; }
  const firstOpen = !st.editor;
  // Plan na serwerze zmienił się poza tym oknem, a tu nie ma niezapisanych zmian → weź nowy.
  const prevEditor = st.editor;
  const localDirty = prevEditor && JSON.stringify(M.toJson(prevEditor.plan)) !== st.serverJson;
  if (!localDirty && data.plan.data) st.serverJson = serverJson;
  if (prevEditor && !localDirty && data.plan.data && JSON.stringify(M.toJson(prevEditor.plan)) !== serverJson) prevEditor.plan = serverPlan;

  // ---------------------------------------------------------------- płótno
  const canvas = h("canvas.plan-canvas", { "aria-label": "Mapa planu państwa", tabindex: "0" });
  const coords = h("div.plan-coords", { "aria-live": "off" }, "Najedź na mapę");
  const hint = h("div.plan-hint", { hidden: true });
  const mapInfo = h("div.plan-mapinfo");
  const draftBar = h("div.plan-draftbar", { hidden: true });
  const stage = h("div.plan-stage", canvas, coords, hint, mapInfo, draftBar);
  const optsBar = h("div.plan-options");
  const side = h("aside.plan-side");
  const ui = {};

  const ed = new PlanEditor(canvas, {
    onChange: () => {
      revalidate();
      drawSide();
      scheduleDraft();
    },
    onSelect: () => drawSide(),
    onTool: () => {
      drawToolbar();
      drawOptions();
      drawSide();
    },
    onTerrain: () => drawTerrain(),
    onLevelSelection: () => { st.levelPreview = null; drawLeveling(); },
    onDraft: () => drawDraftBar(),
    onToast: (m, kind) => toast(m, kind),
    onCursor: (b) => {
      if (!b) return (coords.textContent = "Najedź na mapę");
      const [cx, cz] = M.chunkOf(b.x, b.z);
      const k = M.ckey(cx, cz);
      const own = ed.land && ed.land.has(k);
      const f = ed.foreign && ed.foreign.get(k);
      coords.textContent = `x ${b.x}, z ${b.z} · chunk ${cx}, ${cz}${own ? " · ziemia kraju" : f ? " · " + f.name : ""}`;
    },
  });
  if (prevEditor) {
    for (const k of ["plan", "terr", "history", "view", "tool", "opts", "layers", "touchMode", "sel", "levelSelection"]) ed[k] = prevEditor[k];
    prevEditor.destroy();
  } else {
    ed.plan = serverPlan;
    ed.opts.buildingType = ([...types.values()].find((x) => x.available !== false) || [...types.values()][0] || {}).type || null;
  }
  if (ed.tool === "terrain" && !permissions.territory || ed.tool === "leveling" && !permissions.level) ed.tool = "select";
  ed.canvas.dataset.tool = ed.tool;
  st.editor = ed;
  ed.margin = (planInfo.margin ?? 32) | 0;
  ed.setData(st.map, types);

  // ---------------------------------------------------------------- pasek narzędzi
  const toolbar = h("div.plan-toolbar", { role: "toolbar", "aria-label": "Narzędzia planu" });
  function toolBtn(tool) {
    const b = h("button.tool-btn" + (ed.tool === tool.id ? ".on" : ""), { type: "button", "aria-pressed": String(ed.tool === tool.id), title: tool.label, dataset: { tool: tool.id } }, icon(tool.icon), h("span", tool.label));
    b.addEventListener("click", () => ed.setTool(tool.id));
    return b;
  }
  function iconBtn(ic, label, fn, opts = {}) {
    const b = h("button.tool-btn.tool-icon", { type: "button", title: label, "aria-label": label, disabled: !!opts.disabled, dataset: { act: opts.act || "" } }, icon(ic), opts.text ? h("span", opts.text) : null);
    b.addEventListener("click", fn);
    return b;
  }
  function drawToolbar() {
    clear(toolbar);
    add(
      toolbar,
      h("div.tool-group", TOOLS.filter(tool => tool.id !== "terrain" || permissions.territory).filter(tool => tool.id !== "leveling" || permissions.level).map(toolBtn)),
      h("div.tool-group", iconBtn("undo", "Cofnij (Ctrl+Z)", () => ed.undo(), { disabled: !ed.history.canUndo && !ed.draft, act: "undo" }), iconBtn("redo", "Ponów (Ctrl+Y)", () => ed.redo(), { disabled: !ed.history.canRedo, act: "redo" })),
      h(
        "div.tool-group",
        iconBtn("minus", "Oddal", () => ed.zoomBy(0.8)),
        iconBtn("plus", "Przybliż", () => ed.zoomBy(1.25)),
        iconBtn("fit", "Cały kraj", () => ed.fit()),
        iconBtn("grid", ed.layers.grid ? "Ukryj siatkę chunków" : "Pokaż siatkę chunków", () => {
          ed.layers.grid = !ed.layers.grid;
          ed.requestDraw();
          drawToolbar();
        }, { act: "grid" }),
      ),
      h(
        "div.tool-group.touch-only",
        (() => {
          const b = h("button.tool-btn.touch-mode" + (ed.touchMode === "draw" ? ".on" : ""), { type: "button", "aria-pressed": String(ed.touchMode === "draw"), title: "Jeden palec: przesuwanie albo rysowanie" }, icon(ed.touchMode === "draw" ? "pen" : "hand"), h("span", ed.touchMode === "draw" ? "Tryb: rysowanie" : "Tryb: przesuwanie"));
          b.addEventListener("click", () => {
            ed.touchMode = ed.touchMode === "draw" ? "pan" : "draw";
            drawToolbar();
          });
          return b;
        })(),
      ),
    );
    hint.textContent = (TOOLS.find((x) => x.id === ed.tool) || TOOLS[0]).hint;
  }

  // ---------------------------------------------------------------- opcje narzędzia
  function numInput(label, value, min, max, onSet, aria) {
    const i = h("input.input.input-sm.num-sm", { type: "number", min: String(min), max: String(max), step: "1", value: String(value), "aria-label": aria || label });
    i.addEventListener("change", () => {
      const v = Math.trunc(Number(i.value));
      if (!Number.isFinite(v) || v < min || v > max) {
        i.classList.add("invalid");
        return;
      }
      i.classList.remove("invalid");
      onSet(v);
    });
    return h("label.opt", h("span.opt-label", label), i);
  }
  function seg(options, value, onPick, label) {
    const s = segmented(options, value, () => {}, label);
    for (const b of s.querySelectorAll(".seg-btn")) {
      b.removeAttribute("data-mut");
      b.addEventListener("click", () => onPick(b.dataset.id));
    }
    return s;
  }
  function drawOptions() {
    clear(optsBar);
    const o = ed.opts;
    const tool = ed.tool;
    if (tool === "zone") {
      add(
        optsBar,
        seg([{ id: "poly", label: "Wielokąt", icon: "zone" }, { id: "rect", label: "Prostokąty", icon: "rect" }], o.zoneMode, (v) => ((o.zoneMode = v), ed.cancelDraft(), drawOptions()), "Sposób rysowania strefy"),
        h(
          "div.zone-chips",
          { role: "radiogroup", "aria-label": "Typ strefy" },
          M.ZONE_TYPES.map((z) => {
            const b = h("button.zone-chip" + (o.zoneType === z.id ? ".on" : ""), { type: "button", role: "radio", "aria-checked": String(o.zoneType === z.id), title: z.help, dataset: { zone: z.id } }, h("span.swatch", { style: { "--sw": z.color } }), h("span", z.label));
            b.addEventListener("click", () => ((o.zoneType = z.id), drawOptions()));
            return b;
          }),
        ),
      );
    } else if (tool === "road") {
      add(
        optsBar,
        seg(M.ROAD_KINDS.map((r) => ({ id: r.id, label: r.label })), o.roadKind, (v) => ((o.roadKind = v), (o.roadWidth = M.roadKind(v).width), drawOptions()), "Rodzaj drogi"),
        numInput("Szerokość (bl.)", o.roadWidth, 1, M.LIMITS.roadWidth, (v) => (o.roadWidth = v)),
      );
    } else if (tool === "wall") {
      const mode = M.wallMode(o.wallMode);
      add(
        optsBar,
        seg(M.WALL_MODES.map((w) => ({ id: w.id, label: w.label })), o.wallMode, (v) => ((o.wallMode = v), (o.wallInset = M.wallMode(v).inset ?? o.wallInset), ed.cancelDraft(), drawOptions()), "Tryb muru"),
        seg([{ id: "draw", label: o.wallMode === "points" ? "Rysuj mur" : "Mur", icon: "wall" }, { id: "gate", label: "Bramy", icon: "gate" }], o.wallSub, (v) => ((o.wallSub = v), ed.cancelDraft(), drawOptions()), "Rysowanie albo bramy"),
        o.wallMode !== "points" ? numInput("Odstęp (bl.)", o.wallInset, 0, M.LIMITS.inset, (v) => (o.wallInset = v), "Odstęp muru (inset)") : null,
        o.wallMode !== "points" ? btn(ed.plan.walls.some((w) => w.mode === o.wallMode) ? "Zastosuj odstęp" : "Dodaj mur: " + mode.label.toLowerCase(), { small: true, kind: "primary", icon: "wall", mut: false }, () => ed.addAutoWall(o.wallMode, o.wallInset) && toast(`Dodano mur „${mode.label.toLowerCase()}” — dopasuje się sam do ${o.wallMode === "follow-border" ? "granicy kraju" : "zabudowy"}.`)) : null,
        h("span.opt-help", mode.help),
      );
    } else if (tool === "building") {
      const ty = types.get(o.buildingType);
      const sz = M.typeSize(types, o.buildingType);
      const r = h("button.btn.btn-sm", { type: "button", title: "Obróć (R)" }, icon("rotate"), h("span", `Obrót ${o.rotation}°`));
      r.addEventListener("click", () => ed.rotate());
      add(
        optsBar,
        h("span.opt-current", ty ? [h("strong", ty.name), h("span.muted", ` ${sz.w}×${sz.d} bl.${ty.size ? "" : " (rozmiar domyślny)"}`)] : h("span.muted", "Wybierz typ budynku z listy obok")),
        r,
        numInput("Priorytet", o.priority, 1, M.LIMITS.priority, (v) => (o.priority = v), "Priorytet (1 = najpierw)"),
      );
    } else if (tool === "leveling") {
      add(optsBar, h("span.opt-help", "Prostokąt w blokach, maks. 4096 kolumn. Budynki i miejsca chronione zostaną pominięte przez serwer."));
    } else if (tool === "terrain") {
      add(
        optsBar,
        seg([{ id: "add", label: "Dodaj", icon: "plus" }, { id: "remove", label: "Usuń (gumka)", icon: "eraser" }], o.terrMode, (v) => ((o.terrMode = v), drawOptions()), "Dodawanie albo usuwanie chunków"),
        seg([{ id: "brush", label: "Pędzel", icon: "brush" }, { id: "rect", label: "Prostokąt", icon: "rect" }, { id: "fill", label: "Wypełnij", icon: "fill" }], o.terrShape, (v) => ((o.terrShape = v), drawOptions()), "Kształt zaznaczenia"),
        btn("Poszerz o 1 pierścień", { small: true, icon: "ring", mut: false }, () => ed.growRing()),
      );
    }
    add(optsBar, h("span.opt-help.tool-hint", (TOOLS.find((x) => x.id === tool) || TOOLS[0]).hint));
  }

  /** Przyciski rysowania (ważne na telefonie, gdzie nie ma Entera ani podwójnego kliku). */
  function drawDraftBar() {
    clear(draftBar);
    const d = ed.draft;
    draftBar.hidden = !d;
    if (!d) return;
    const n = d.points.length;
    const need = d.kind === "roads" || d.kind === "walls" ? 2 : 3;
    const fin = btn(d.kind === "walls" ? "Zakończ (mur otwarty)" : "Zakończ", { small: true, kind: "primary", icon: "check", mut: false }, () => ed.finishDraft());
    fin.dataset.act = "finish";
    fin.disabled = n < need;
    const close = d.kind === "walls" ? btn("Zamknij mur", { small: true, icon: "ring", mut: false }, () => ed.finishDraft(true)) : null;
    if (close) close.disabled = n < 3;
    add(draftBar, h("span.draft-info", `Rysujesz: ${d.kind === "zones" ? "strefę" : d.kind === "roads" ? "drogę" : "mur"} · punktów ${n}`), btn("Cofnij punkt", { small: true, icon: "undo", mut: false }, () => ed.popDraftPoint()), btn("Anuluj", { small: true, mut: false }, () => ed.cancelDraft()), close, fin);
  }

  // ---------------------------------------------------------------- walidacja
  let validation = { errors: [], warnings: [] };
  const vctx = () => ({ land: ed.effectiveLand(), margin: ed.margin, types });
  function revalidate() {
    validation = M.validatePlan(M.toJson(ed.plan), vctx());
    ed.clearIssues();
    ed.setIssues(validation.errors, "error");
    ed.setIssues(validation.warnings, "warn");
    if (st.savedWarnings.length && JSON.stringify(M.toJson(ed.plan)) === st.serverJson) ed.setIssues(st.savedWarnings, "warn");
  }
  const issueOf = (kind, index) => ed.issues.get(kind + ":" + index);

  // ---------------------------------------------------------------- szkic
  let draftTimer = null;
  const dirty = () => JSON.stringify(M.toJson(ed.plan)) !== st.serverJson;
  function scheduleDraft() {
    clearTimeout(draftTimer);
    draftTimer = setTimeout(() => {
      if (dirty()) st.draftAt = writeDraft(id, M.toJson(ed.plan)) ? Date.now() : null;
      else dropDraft(id);
      drawStatus();
    }, 400);
  }

  // ---------------------------------------------------------------- panel boczny
  const statusBox = h("section.card.plan-card.plan-status");
  const terrBox = h("section.card.plan-card.plan-terrain");
  const levelBox = h("section.card.plan-card.plan-leveling");
  const selBox = h("section.card.plan-card.plan-selected");
  const typesBox = h("section.card.plan-card.plan-types");
  const listBox = h("section.card.plan-card.plan-list");
  const optsBox = h("section.card.plan-card.plan-optbox");
  const legendBox = h("section.card.plan-card.plan-legend");
  add(side, statusBox, levelBox, terrBox, typesBox, selBox, listBox, optsBox, legendBox);

  function drawSide() {
    drawStatus();
    drawTerrain();
    drawLeveling();
    drawTypes();
    drawSelected();
    drawList();
    drawToolbar();
  }

  function drawStatus() {
    clear(statusBox);
    const isDirty = dirty();
    const e = validation.errors.length;
    const w = validation.warnings.length;
    const counts = `${ed.plan.zones.length} stref · ${ed.plan.roads.length} dróg · ${ed.plan.walls.length} murów · ${ed.plan.buildings.length} budynków`;
    const save = btn("Zapisz plan", { kind: "primary", icon: "check" }, (_e, b) => savePlan(b));
    save.dataset.act = "save";
    const prev = btn("Sprawdź realizację", { icon: "eye", small: true, mut: false }, (_e, b) => showPreview(b));
    prev.dataset.act = "preview";
    const clr = btn("Wyczyść plan", { kind: "danger", icon: "trash" }, (_e, b) => clearPlan(b));
    clr.dataset.act = "clear";
    save.disabled = !permissions.write || planInfo.available === false || planInfo.supported === false || !!data.plan.error;
    clr.disabled = !permissions.write || planInfo.available === false || planInfo.supported === false || !!data.plan.error;
    const exp = btn("Eksport JSON", { small: true, icon: "download", mut: false }, () => exportPlan());
    exp.dataset.act = "export";
    const fileIn = h("input.visually-hidden", { type: "file", accept: "application/json,.json", "aria-label": "Plik planu JSON", dataset: { act: "import-file" } });
    fileIn.addEventListener("change", () => importPlanFile(fileIn));
    const imp = btn("Import JSON", { small: true, icon: "upload", mut: false }, () => fileIn.click());
    imp.dataset.act = "import";
    const draft = readDraft(id);
    const banner =
      firstOpen && draft && draft.plan && JSON.stringify(draft.plan) !== st.serverJson && !isDirty && !st.draftHandled
        ? h(
            "div.alert.alert-warn.plan-draft",
            icon("clock"),
            h("span", `Masz niezapisany szkic planu z ${fmtTime(draft.at)}.`),
            btn("Przywróć", { small: true, kind: "primary", mut: false }, () => {
              st.draftHandled = true;
              ed.setPlan(M.fromServer(draft.plan));
              toast("Przywrócono szkic planu.");
            }),
            btn("Odrzuć", { small: true, mut: false }, () => {
              st.draftHandled = true;
              dropDraft(id);
              drawStatus();
            }),
          )
        : null;
    add(
      statusBox,
      h("h3.card-title", "Plan państwa"),
      !permissions.write ? h("p.small.muted", "Odczyt planu. Zapis i usuwanie należą do władcy lub administratora.") : null,
      planInfo.available === false ? h("div.alert.alert-warn", icon("warn"), h("span", "Plugin Ludnosc jest niedostępny — plan można rysować i eksportować, ale nie zapisać.")) : planInfo.supported === false ? h("div.alert.alert-warn", icon("warn"), h("span", "Zainstalowana wersja Ludnosc nie obsługuje planów. Rysuj i eksportuj plan; zapis będzie dostępny po aktywacji aktualizacji.")) : null,
      data.plan.error ? h("div.alert.alert-warn", icon("warn"), h("span", "Nie udało się wczytać zapisanego planu: " + (data.plan.error.message || ""))) : null,
      banner,
      h("p.plan-counts", counts),
      h("p.small.muted", "Zapisany plan jest przeznaczony do realizacji przez budowniczych. Budowy i prace ziemne zużywają środki kraju; opcja rozbiórki pozwala usuwać budynki systemowe."),
      h(
        "p.plan-state",
        isDirty ? h("span.status.status-off", icon("clock"), "niezapisane zmiany") : h("span.status.status-on", icon("check"), planInfo.plan ? "zapisany" : "brak planu na serwerze"),
        st.draftAt && isDirty ? h("span.muted.small", ` · szkic w przeglądarce ${fmtTime(st.draftAt)}`) : null,
        prev,
      ),
      executionFeedback(),
      e || w ? h("p.plan-issues", e ? h("span.issue-err", icon("warn"), `${e} ${e === 1 ? "błąd" : e < 5 ? "błędy" : "błędów"}`) : null, w ? h("span.issue-warn", icon("warn"), `${w} ${w === 1 ? "ostrzeżenie" : w < 5 ? "ostrzeżenia" : "ostrzeżeń"}`) : null) : null,
      validation.errors.filter((x) => !x.kind).map((x) => h("p.small.neg", x.text)),
      h("div.plan-actions", save),
      h("div.plan-actions.plan-actions-sub", exp, imp, fileIn, clr),
    );
  }

  function executionFeedback() {
    if (!planInfo.plan) return null;
    const execution = planExecution(st.execution?.preview);
    const title = h("h4", "Realizacja zapisanego planu");
    if (st.executionError) return h("div.plan-execution", title, h("p.small.warn-text", "Nie udało się odczytać realizacji: " + (st.executionError.message || st.executionError)));
    if (!st.execution?.preview) return h("div.plan-execution", title, h("p.small.muted", "Zapis przyjęty. Użyj „Sprawdź realizację”, aby odczytać aktualny stan prac."));
    return h("div.plan-execution", title,
      h("p.small", `Zlecone: ${execution.ordered} · czeka: ${execution.waiting} · zablokowane: ${execution.blocked.length}`),
      execution.blocked.length ? h("ul.warn-list", execution.blocked.slice(0, 8).map(o => h("li", icon("warn"), h("span",
        h("strong", `${o.name || (types.get(o.type) || {}).name || o.type || "Budynek"} · x ${fmtInt(o.x)}, z ${fmtInt(o.z)}: `),
        o.reason || "Serwer odrzucił to zlecenie. Sprawdź teren i wymagania budynku.",
        o.retryAt ? h("span.small.muted", " · ponowna próba: " + fmtTime(o.retryAt)) : null)))) : null,
      execution.blocked.length > 8 ? h("p.small.muted", "Pełna lista znajduje się w „Sprawdź realizację”.") : null,
      h("p.small.muted", "Zapisanie planu nie oznacza rozpoczęcia każdej budowy. Realizacja zależy od wolnego terenu, wymagań i środków kraju."));
  }

  async function refreshExecution() {
    try { st.execution = await api.rpc(methods.preview, { id }); st.executionError = null; }
    catch (error) { st.executionError = error; }
    drawStatus();
  }

  function drawTerrain() {
    clear(terrBox);
    const T = st.map.territory || {};
    const nAdd = ed.terr.add.size;
    const nRem = ed.terr.remove.size;
    const show = permissions.territory && (ed.tool === "terrain" || nAdd || nRem);
    terrBox.hidden = !show;
    if (!show) return;
    const count = (T.chunks ?? ed.land.size) + nAdd - nRem;
    const cost = M.claimCost(T.chunks ?? ed.land.size, nAdd, T.costBase || 0, T.costPerChunk || 0);
    const freeBox = h("input", { type: "checkbox", checked: st.free, "aria-label": "Darmowo" });
    freeBox.addEventListener("change", () => ((st.free = freeBox.checked), drawTerrain()));
    const commit = btn(`Zatwierdź teren`, { kind: "primary", icon: "check" }, (_e, b) => commitTerrain(b));
    commit.dataset.act = "terrain-commit";
    commit.disabled = !(nAdd || nRem);
    const drop = btn("Odrzuć zmiany", { small: true, mut: false }, () => ed.clearTerrain());
    drop.disabled = !(nAdd || nRem);
    const over = typeof T.limit === "number" && count > T.limit;
    const poor = !st.free && typeof T.treasury === "number" && cost > T.treasury;
    add(
      terrBox,
      h("h3.card-title", "Teren"),
      h("p.terr-count", h("span.pos", `+${fmtInt(nAdd)}`), " / ", h("span.neg", `−${fmtInt(nRem)}`), " chunków", h("span.muted", " · koszt "), h("strong", st.free ? "darmowo" : formatMoney(cost))),
      h("p.small.muted", `Teren po zmianie: ${fmtInt(count)}${typeof T.limit === "number" ? " / limit " + fmtInt(T.limit) : ""} chunków · skarbiec ${T.treasuryText || formatMoney(T.treasury || 0)}`),
      over ? h("p.small.warn-text", icon("warn"), ` Przekroczysz limit terenu AI (max-chunki ${T.limit}) — AI nie będzie się dalej rozrastać.`) : null,
      poor ? h("p.small.neg", icon("warn"), " W skarbcu nie ma tyle pieniędzy — zaznacz „Darmowo” albo dodaj mniej.") : null,
      permissions.free ? h("label.check", freeBox, h("span", "Darmowo (bez opłaty ze skarbca)")) : null,
      h("p.small.muted", `Najwyżej ${T.maxChanges || 512} zmian naraz. Koszt jak w Kraje: ${formatMoney(T.costBase || 0)} + ${formatMoney(T.costPerChunk || 0)} × liczba chunków.`),
      h("div.plan-actions", drop, commit),
    );
  }

  // Manual earthworks selection is separate from country borders and the saved city plan.
  function drawLeveling() {
    clear(levelBox);
    levelBox.hidden = !permissions.level || (ed.tool !== "leveling" && !ed.levelSelection);
    if (levelBox.hidden) return;
    const rect = ed.levelSelection;
    const error = levelSelectionError(rect, ed.land, ed.protectedAreas);
    const mode = st.levelMode || "auto";
    const reset = () => { st.levelPreview = null; drawLeveling(); };
    const modes = seg([{id:"auto",label:"Automatyczny poziom"},{id:"fixed",label:"Wysokość Y"}], mode,
      value => { st.levelMode=value; reset(); }, "Poziom wyrównania");
    const yInput = h("input.input.input-sm", {type:"number",min:String(st.map.minHeight ?? -64),
      max:String((st.map.maxHeight ?? 320)-1),step:"1",value:st.levelY === null ? "" : String(st.levelY ?? 64),"aria-label":"Docelowa wysokość Y"});
    yInput.addEventListener("change", () => {
      const value = yInput.value.trim() ? Number(yInput.value) : NaN;
      st.levelY = Number.isSafeInteger(value) ? value : null;
      reset();
    });
    const free = h("input",{type:"checkbox",checked:permissions.free && !!st.levelFree,"aria-label":"Wyrównanie darmowo"});
    free.addEventListener("change",()=>{st.levelFree=free.checked;reset();});
    const preview = btn("Podgląd wyrównania",{icon:"eye",mut:false},(_e,b)=>previewLeveling(b));
    preview.dataset.act="level-preview";
    preview.disabled=!!error || (mode === "fixed" && st.levelY === null);
    const drop = btn("Anuluj zaznaczenie",{small:true,mut:false},()=>ed.setLevelSelection(null));
    drop.dataset.act="level-cancel";
    const response=st.levelPreview;
    const report=response?.preview;
    const tokenValid=!!response?.token && (!response.expiresAt || Date.now()<response.expiresAt);
    const submit=btn("Zleć wyrównanie",{kind:"primary",icon:"check"},(_e,b)=>commitLeveling(b));
    submit.dataset.act="level-commit";
    submit.disabled=!tokenValid || report?.available===false;
    add(levelBox,h("h3.card-title","Wyrównaj teren"),
      rect ? h("p.small",`x ${rect.minX}…${rect.maxX}, z ${rect.minZ}…${rect.maxZ} · ${(rect.maxX-rect.minX+1)*(rect.maxZ-rect.minZ+1)} kolumn`) : null,
      error ? h("p.small.muted",error) : null,modes,
      mode === "fixed" ? field("Docelowy poziom Y",yInput,"Poziom gruntu; serwer sprawdzi dopuszczalny wykop i nasyp.") : h("p.small.muted","Poziom zostanie dobrany z wysokości zaznaczonego terenu."),
      permissions.free ? h("label.check",free,h("span","Darmowo (administrator)")) : null,
      h("div.plan-actions",preview,drop),
      report ? h("div.level-report",h("p",h("strong",`Poziom gruntu: Y ${report.targetY ?? "—"}`)),
        h("p.small",`Kolumny do zmiany: ${fmtInt(report.changedColumns)} · roboty ziemne: ${fmtInt(report.blocks)} bloków`),
        h("p","Koszt: ",h("strong",response.free ? "darmowo" : response.costText || formatMoney(report.cost || 0))),
        report.reason ? h("p.small.warn-text",report.reason) : null,
        arr(report.warnings).map(w=>h("p.small.warn-text",typeof w === "string" ? w : w.text || w.reason || "Obszar zostanie pominięty.")),
        !tokenValid && report.available !== false ? h("p.small.warn-text","Podgląd wygasł — oblicz go ponownie.") : null,
        h("p.small.muted","Budowniczowie wykonają prace stopniowo. Chronione budynki i strefy pozostaną nienaruszone."),submit) : null);
  }

  async function previewLeveling(button) {
    if (!permissions.level) return;
    const rect=ed.levelSelection;
    const error=levelSelectionError(rect,ed.land,ed.protectedAreas);
    if (error) return toast(error,"err");
    const targetY=(st.levelMode || "auto") === "auto" ? null : st.levelY === undefined ? 64 : st.levelY;
    if (targetY !== null && !Number.isSafeInteger(targetY) || (st.levelMode === "fixed" && targetY === null)) return toast("Wprowadź całkowitą wysokość Y.","err");
    const params={id,world:st.map.world || c.capital?.world,...rect,targetY,free:permissions.free && !!st.levelFree};
    const fingerprint=JSON.stringify(params);
    const response=await act(button,async()=>{
      try {return await api.rpc("country.plan.levelPreview",params);}
      catch(e) {if (/nieznana metoda|unknown method/i.test(e.message || "")) throw new Error("Wyrównywanie terenu wymaga aktualizacji pluginu Panel i Ludnosc. Pliki muszą zostać aktywowane na serwerze.");throw e;}
    });
    const current={id,world:st.map.world || c.capital?.world,...ed.levelSelection,targetY:(st.levelMode || "auto") === "auto" ? null : st.levelY === undefined ? 64 : st.levelY,free:permissions.free && !!st.levelFree};
    if (response && fingerprint===JSON.stringify(current)) st.levelPreview=response;
    drawLeveling();
  }

  async function commitLeveling(button) {
    const response=st.levelPreview;
    if (!permissions.level || !response?.token) return;
    if (response.expiresAt && Date.now()>=response.expiresAt) {st.levelPreview=null;drawLeveling();return toast("Podgląd wygasł — oblicz go ponownie.","err");}
    const token=response.token;
    const approved=await modal({title:"Zlecić wyrównanie terenu?",body:h("div",
      h("p",`Budowniczowie wyrównają zaznaczony teren państwa ${c.name} do Y ${response.preview?.targetY}.`),
      h("p",response.free ? "Bez opłaty ze skarbca." : "Koszt ze skarbca: "+(response.costText || formatMoney(response.preview?.cost || 0))),
      h("p.muted","Serwer ponownie sprawdzi teren i uprawnienia. To zlecenie robót ziemnych, nie zmiana granic kraju.")),
      actions:[{label:"Anuluj",value:null},{label:"Zleć wyrównanie",kind:"primary",value:true}]});
    if (!approved || st.levelPreview?.token!==token) return;
    const result=await act(button,()=>api.mutate("country.plan.level",{id,token}),r=>r.message || "Dodano wyrównanie do kolejki budowy.");
    st.levelPreview=null;
    if (result !== undefined) ed.setLevelSelection(null);
    drawLeveling();
  }

  function drawTypes() {
    clear(typesBox);
    typesBox.hidden = ed.tool !== "building";
    if (typesBox.hidden) return;
    const search = h("input.input.input-sm.search", { type: "search", placeholder: "Szukaj typu…", "aria-label": "Szukaj typu budynku", value: st.typeQuery || "" });
    const list = h("div.btype-list", { role: "listbox", "aria-label": "Typ budynku" });
    const fill = () => {
      clear(list);
      const q = (search.value || "").toLowerCase();
      const all = [...types.values()].filter((x) => !q || (x.name + " " + x.type).toLowerCase().includes(q));
      if (!all.length) return list.appendChild(empty(types.size ? "Brak typów pasujących do wyszukiwania." : "Plugin nie podał listy typów budynków.", "house"));
      all.sort((a, b) => (a.available === false) - (b.available === false) || String(a.name).localeCompare(String(b.name), "pl"));
      for (const ty of all) {
        const sz = M.typeSize(types, ty.type);
        const on = ed.opts.buildingType === ty.type;
        const row = h(
          "button.btype" + (on ? ".on" : "") + (ty.available === false ? ".off" : ""),
          { type: "button", role: "option", "aria-selected": String(on), dataset: { type: ty.type }, title: ty.available === false ? "Teraz niedostępny — brakuje: " + arr(ty.missing).join(", ") + ". W planie poczeka na spełnienie wymagań." : ty.description || ty.name },
          h("span.btype-ic", { style: { "--cc": categoryColor(ty.category) } }, icon(CAT_ICONS[ty.category] || "house")),
          h("span.btype-body", h("span.btype-name", ty.name), h("span.btype-meta", `${sz.w}×${sz.d} bl.${ty.size ? "" : "*"}` + (ty.available === false ? " · brakuje: " + arr(ty.missing).join(", ") : ""))),
          h("span.btype-cost", formatMoney(ty.cost || 0)),
        );
        row.addEventListener("click", () => {
          ed.opts.buildingType = ty.type;
          drawOptions();
          fill();
          canvas.focus({ preventScroll: true });
        });
        list.appendChild(row);
      }
    };
    search.addEventListener("input", () => ((st.typeQuery = search.value), fill()));
    fill();
    const noSize = [...types.values()].some((x) => !x.size);
    add(typesBox, h("h3.card-title", "Typ budynku"), search, list, noSize ? h("p.small.muted", "* rozmiar domyślny 9×9 — plugin nie podał wymiarów tego typu.") : h("p.small.muted", "Wyszarzone typy mają niespełnione wymagania — w planie czekają, aż państwo je spełni."));
  }

  function elementLabel(kind, el) {
    if (kind === "zones") return "Strefa: " + ((M.zoneType(el.type) || {}).label || el.type);
    if (kind === "roads") return `Droga ${((M.roadKind(el.kind) || {}).label || el.kind).toLowerCase()} · ${el.width} bl.`;
    if (kind === "walls") {
      const m = M.wallMode(el.mode);
      const gates = (el.gates || []).length;
      return (el.mode && el.mode !== "points" ? `Mur ${m.label.toLowerCase()} · odstęp ${el.inset}` : `Mur ${el.closed ? "zamknięty" : "otwarty"}`) + (gates ? ` · bramy: ${gates}` : "");
    }
    const ty = types.get(el.type);
    return `${(ty && ty.name) || el.type} · priorytet ${el.priority ?? M.DEFAULT_PRIORITY}`;
  }
  function elementSwatch(kind, el) {
    const col = kind === "zones" ? (M.zoneType(el.type) || {}).color : kind === "roads" ? (M.roadKind(el.kind) || {}).color : kind === "walls" ? "#A7A196" : categoryColor((types.get(el.type) || {}).category);
    return h("span.swatch" + (kind === "buildings" ? ".sq" : kind === "roads" || kind === "walls" ? ".line" : ""), { style: { "--sw": col || "#999" } });
  }

  function drawList() {
    clear(listBox);
    const groups = ["zones", "roads", "walls", "buildings"];
    const total = M.planCount(ed.plan);
    add(listBox, h("h3.card-title", "Elementy planu", h("span.badge", fmtInt(total))));
    if (!total) return add(listBox, empty("Plan jest pusty — wybierz narzędzie i narysuj strefy, drogi, mury albo postaw budynki.", "map"));
    for (const g of groups) {
      const items = ed.plan[g];
      if (!items.length) continue;
      const ul = h("ul.plan-items", { dataset: { kind: g } });
      items.forEach((el, i) => {
        const is = issueOf(g, i);
        const on = ed.sel && ed.sel.kind === g && ed.sel.index === i;
        const del = h("button.icon-btn.item-del", { type: "button", "aria-label": "Usuń: " + elementLabel(g, el), title: "Usuń" }, icon("close"));
        del.addEventListener("click", (e) => {
          e.stopPropagation();
          ed.edit((p) => p[g].splice(i, 1));
          ed.select(null);
        });
        const li = h(
          "li.plan-item" + (on ? ".on" : "") + (is ? ".has-" + is.level : ""),
          { tabindex: "0", dataset: { kind: g, index: String(i) } },
          elementSwatch(g, el),
          h("span.item-body", h("span.item-label", `${i + 1}. ${elementLabel(g, el)}`), is ? is.texts.map((tx) => h("span.item-issue.issue-" + is.level, tx)) : null),
          del,
        );
        const pick = () => {
          ed.select({ kind: g, index: i });
          const c2 = centerOf(g, el);
          if (c2) ed.centerOn(c2[0], c2[1]);
        };
        li.addEventListener("click", pick);
        li.addEventListener("keydown", (e) => e.key === "Enter" && pick());
        ul.appendChild(li);
      });
      add(listBox, h("h4.plan-group", M.KIND_NAMES[g] === "Mur" ? "Mury" : M.KIND_NAMES[g] === "Droga" ? "Drogi" : M.KIND_NAMES[g] === "Strefa" ? "Strefy" : "Budynki"), ul);
    }
  }

  function centerOf(kind, el) {
    if (kind === "buildings") return [el.x + 0.5, el.z + 0.5];
    const pts = kind === "zones" ? el.polygon : el.points;
    if (pts && pts.length) return [pts.reduce((s, p) => s + p[0], 0) / pts.length, pts.reduce((s, p) => s + p[1], 0) / pts.length];
    if (kind === "walls" && el.gates && el.gates.length) return el.gates[0];
    return null;
  }

  function drawSelected() {
    clear(selBox);
    const sel = ed.sel;
    selBox.hidden = !sel;
    if (!sel) return;
    const el = ed.plan[sel.kind][sel.index];
    const set = (fn) => ed.edit((p) => fn(p[sel.kind][sel.index]));
    const sel2 = (label, options, value, onSet) => {
      const s = h("select.input.input-sm", { "aria-label": label }, options.map((o) => h("option", { value: o.id, selected: o.id === value }, o.label)));
      s.addEventListener("change", () => onSet(s.value));
      return field(label, s);
    };
    const num = (label, value, min, max, onSet) => {
      const i = h("input.input.input-sm", { type: "number", min: String(min), max: String(max), step: "1", value: String(value), "aria-label": label });
      i.addEventListener("change", () => {
        const v = Math.trunc(Number(i.value));
        if (!Number.isFinite(v) || v < min || v > max) return i.classList.add("invalid");
        onSet(v);
      });
      return field(label, i);
    };
    const rows = [];
    if (sel.kind === "zones") {
      rows.push(sel2("Typ strefy", M.ZONE_TYPES, el.type, (v) => set((z) => (z.type = v))));
      rows.push(h("p.small.muted", `${el.polygon.length} wierzchołków · ok. ${fmtInt(Math.abs(M.polygonArea(el.polygon)))} bl² · ${(M.zoneType(el.type) || {}).help || ""}`));
    } else if (sel.kind === "roads") {
      rows.push(sel2("Rodzaj", M.ROAD_KINDS, el.kind, (v) => set((r) => ((r.kind = v), (r.width = M.roadKind(v).width)))));
      rows.push(num("Szerokość (bl.)", el.width, 1, M.LIMITS.roadWidth, (v) => set((r) => (r.width = v))));
      rows.push(h("p.small.muted", `${el.points.length} punktów`));
    } else if (sel.kind === "walls") {
      if (!el.mode || el.mode === "points") {
        const cb = h("input", { type: "checkbox", checked: !!el.closed });
        cb.addEventListener("change", () => set((w) => (w.closed = cb.checked)));
        rows.push(h("label.check", cb, h("span", "Mur zamknięty (pierścień)")));
        rows.push(h("p.small.muted", `${el.points.length} punktów · bram: ${(el.gates || []).length}`));
      } else {
        rows.push(h("p.small", M.wallMode(el.mode).help));
        rows.push(num("Odstęp (bl.)", el.inset ?? 2, 0, M.LIMITS.inset, (v) => set((w) => (w.inset = v))));
      }
      if ((el.gates || []).length) rows.push(btn("Usuń bramy", { small: true, mut: false }, () => set((w) => (w.gates = []))));
      rows.push(h("p.small.muted", "Bramy: narzędzie „Mur” → „Bramy”, klik na murze."));
    } else if (sel.kind === "buildings") {
      const opts = [...types.values()].map((x) => ({ id: x.type, label: x.name + (x.available === false ? " (niedostępny)" : "") }));
      if (!types.has(el.type)) opts.unshift({ id: el.type, label: el.type + " (nieznany)" });
      rows.push(sel2("Typ", opts, el.type, (v) => set((b) => (b.type = v))));
      rows.push(sel2("Obrót", M.ROTATIONS.map((r) => ({ id: String(r), label: r + "°" })), String(el.rotation || 0), (v) => set((b) => (b.rotation = Number(v)))));
      rows.push(num("Priorytet (1 = najpierw)", el.priority ?? M.DEFAULT_PRIORITY, 1, M.LIMITS.priority, (v) => set((b) => (b.priority = v))));
      rows.push(h("div.form-row2", num("X", el.x, -29999000, 29999000, (v) => set((b) => (b.x = v))), num("Z", el.z, -29999000, 29999000, (v) => set((b) => (b.z = v)))));
      const ty = types.get(el.type);
      if (ty) rows.push(h("p.small.muted", `Koszt ${formatMoney(ty.cost || 0)} · ${M.typeSize(types, el.type).w}×${M.typeSize(types, el.type).d} bl.` + (ty.available === false ? ` · brakuje: ${arr(ty.missing).join(", ")}` : "")));
    }
    const is = issueOf(sel.kind, sel.index);
    add(
      selBox,
      h("h3.card-title", "Zaznaczony: " + elementLabel(sel.kind, el)),
      is ? h("ul.sel-issues", is.texts.map((tx) => h("li.issue-" + is.level, icon("warn"), h("span", tx)))) : null,
      h("div.sel-props", rows),
      h("div.plan-actions", btn("Usuń element", { small: true, kind: "danger", icon: "trash", mut: false }, () => ed.deleteSelected())),
    );
  }

  function drawOptsBox() {
    clear(optsBox);
    const o = ed.plan.options;
    const box = (key, label, help) => {
      const cb = h("input", { type: "checkbox", checked: !!o[key], dataset: { opt: key } });
      cb.addEventListener("change", () => ed.edit((p) => (p.options[key] = cb.checked)));
      return h("label.check.check-help", cb, h("span", h("strong", label), h("span.muted.small", help)));
    };
    add(
      optsBox,
      h("h3.card-title", "Opcje planu"),
      box("allowDemolish", "Pozwól na rozbiórki", "planer może wyburzyć budynki, które kolidują z planem"),
      box("keepExisting", "Zachowaj istniejące", "nie przebudowuj tego, co już stoi, jeśli nie koliduje"),
      box("autoFill", "Dopełniaj automatycznie", "poza narysowanym planer sam zabudowuje strefy zgodnie z ich typem"),
    );
  }

  function drawLegend() {
    clear(legendBox);
    add(
      legendBox,
      h("h3.card-title", "Legenda"),
      h("ul.legend", M.ZONE_TYPES.map((z) => h("li", h("span.swatch", { style: { "--sw": z.color } }), h("span", h("strong", z.label), h("span.muted.small", " — " + z.help))))),
      h(
        "ul.legend.legend-misc",
        h("li", h("span.swatch.line", { style: { "--sw": "#E9D8A6" } }), h("span", "Droga (główna / boczna / trakt)")),
        h("li", h("span.swatch.line", { style: { "--sw": "#A7A196" } }), h("span", "Mur — złote kwadraty to bramy; przerywany = automatyczny")),
        h("li", h("span.swatch.outline", { style: { "--sw": "#fff" } }), h("span", "Istniejący budynek (obrys przybliżony)")),
        h("li", h("span.swatch", { style: { "--sw": "rgba(76,200,90,.7)" } }), h("span", "Teren do dodania")),
        h("li", h("span.swatch", { style: { "--sw": "rgba(230,60,50,.7)" } }), h("span", "Teren do usunięcia")),
        h("li", h("span.swatch.hatch", { style: { "--sw": "#888" } }), h("span", "Obszar chroniony (nie do zajęcia)")),
      ),
    );
  }

  // ---------------------------------------------------------------- mapa: informacja i odświeżanie
  function drawMapInfo() {
    clear(mapInfo);
    const m = st.map;
    const marginSel = h("select.input.input-sm", { "aria-label": "Margines mapy" }, [16, 32, 64, 128].map((v) => h("option", { value: String(v), selected: v === st.margin }, `margines ${v} bl.`)));
    marginSel.addEventListener("change", () => refreshMap(false, Number(marginSel.value)));
    const refresh = h("button.btn.btn-sm", { type: "button", dataset: { act: "map-refresh" } }, icon("bolt"), h("span", "Odśwież mapę"));
    refresh.addEventListener("click", () => refreshMap(true));
    add(
      mapInfo,
      !m.complete ? h("span.map-partial", icon("warn"), `Mapa niepełna: ${fmtInt(m.loadedChunks)} z ${fmtInt(m.totalChunks)} chunków — reszta wczytuje się w tle.`) : h("span.muted.small", `Mapa z ${fmtTime(m.renderedAt)}${m.scale > 1 ? ` · skala 1:${m.scale}` : ""}`),
      marginSel,
      refresh,
    );
  }

  async function refreshMap(fresh, margin) {
    mapInfo.classList.add("loading");
    try {
      const d = await loadPlanData(id, { fresh, margin: margin ?? st.margin, country: !!t.countryPlan });
      if (d.map.error) throw d.map.error;
      st.map = d.map.data;
      st.mapAt = Date.now();
      st.margin = d.margin;
      ed.setData(st.map, types);
      revalidate();
      drawMapInfo();
      drawTerrain();
    } catch (e) {
      toast("Nie udało się odświeżyć mapy: " + (e.message || e), "err");
    } finally {
      mapInfo.classList.remove("loading");
    }
  }

  // ---------------------------------------------------------------- akcje
  async function savePlan(b) {
    if (!permissions.write) return toast("Tylko władca lub administrator może zapisać plan.", "err");
    ed.cancelDraft();
    revalidate();
    if (validation.errors.length) {
      drawSide();
      const first = validation.errors.find((x) => x.kind);
      if (first) ed.select({ kind: first.kind, index: first.index });
      return toast(`Plan ma błędy (${validation.errors.length}) — popraw elementy zaznaczone na czerwono.`, "err", 6000);
    }
    if (t.countryPlan && !(await confirmDanger({title:"Zapisać plan do realizacji?",text:`Budowniczowie państwa ${c.name} będą realizować zapisany plan z pieniędzy i zasobów kraju.${ed.plan.options?.allowDemolish ? " Plan dopuszcza rozbiórkę budynków systemowych." : ""}`,confirmLabel:"Zapisz plan"}))) return;
    let r;
    b.disabled = true;
    b.classList.add("busy");
    try {
      r = await api.mutate(methods.set, { id, plan: M.toJson(ed.plan) });
    } catch (e) {
      // błąd walidacji pluginu: zaznacz elementy wymienione w komunikacie („zones[2]: …”)
      const refs = M.parseRefs(e.message);
      if (refs.length) {
        ed.setIssues(refs, "error");
        ed.select({ kind: refs[0].kind, index: refs[0].index });
        drawSide();
      }
      toast(e.message || String(e), "err", 8000);
      return;
    } finally {
      b.disabled = false;
      b.classList.remove("busy");
    }
    st.savedWarnings = arr(r.warnings).map((w) => (w.ref ? { kind: w.ref.kind, index: w.ref.index, text: w.text } : { kind: null, index: -1, text: w.text }));
    if (r.plan) {
      const canon = M.fromServer(r.plan);
      st.serverJson = JSON.stringify(M.toJson(canon));
      ed.plan = canon;
    } else st.serverJson = JSON.stringify(M.toJson(ed.plan));
    planInfo.plan = r.plan || M.toJson(ed.plan);
    st.execution = null;
    st.executionError = null;
    dropDraft(id);
    st.draftAt = null;
    revalidate();
    drawSide();
    await refreshExecution();
    const general = st.savedWarnings.filter((w) => !w.kind);
    toast(r.message || "Zapisano plan państwa.", st.savedWarnings.length ? "warn" : "ok", st.savedWarnings.length ? 7000 : 4200);
    if (st.savedWarnings.length) showWarnings(st.savedWarnings, general);
  }

  function showWarnings(list) {
    modal({
      title: "Plan zapisany — ostrzeżenia",
      wide: true,
      body: h(
        "div.plan-warn-list",
        h("p.muted", "Plan został zapisany. Planer pominie albo odłoży poniższe elementy, dopóki problem nie zniknie:"),
        h(
          "ul.warn-list",
          list.map((w) => {
            const el = w.kind && ed.plan[w.kind] && ed.plan[w.kind][w.index];
            return h("li", icon("warn"), h("span", el ? h("strong", `${w.index + 1}. ${elementLabel(w.kind, el)}: `) : null, w.text));
          }),
        ),
      ),
      actions: [{ label: "Zamknij", value: null }],
    });
  }

  async function clearPlan(b) {
    if (!permissions.write) return;
    if (!(await confirmDanger({ title: "Wyczyścić plan państwa?", text: `Plan ${c.name} zostanie usunięty z serwera, a miasto znów będzie rozbudowywał automatyczny planer. Na tej stronie możesz go jeszcze przywrócić (Ctrl+Z) i zapisać ponownie.`, confirmLabel: "Wyczyść plan" }))) return;
    const r = await act(b, () => api.mutate(methods.clear, { id }), (x) => (x && x.message) || "Usunięto plan państwa.");
    if (r === undefined) return;
    ed.setPlan(M.emptyPlan());
    st.serverJson = JSON.stringify(M.toJson(M.emptyPlan()));
    st.savedWarnings = [];
    planInfo.plan = null;
    st.execution = null;
    st.executionError = null;
    dropDraft(id);
    revalidate();
    drawSide();
  }

  async function showPreview(b) {
    const r = await act(b, () => api.rpc(methods.preview, { id }), null);
    if (r === undefined) return;
    st.execution = r;
    st.executionError = null;
    drawStatus();
    const p = r.preview && typeof r.preview === "object" ? r.preview : null;
    const isDirty = dirty();
    const orders = arr(p && (p.orders || p.queue || p.builds));
    const demol = arr(p && (p.demolitions || p.demolish));
    const warns = arr(p && p.warnings);
    const total = p ? p.totalCost ?? p.cost ?? orders.reduce((s, o) => s + (Number(o.cost) || 0), 0) : null;
    const estimate = ed.plan.buildings.reduce((s, x) => s + (Number((types.get(x.type) || {}).cost) || 0), 0);
    const known = new Set(["orders", "queue", "builds", "demolitions", "demolish", "warnings", "totalCost", "cost"]);
    const LABELS = { roadMetres: "Długość dróg (bl.)", wallMetres: "Długość murów (bl.)", roads: "Drogi", walls: "Mury" };
    const STATUS = { czeka: "czeka", zlecony: "zlecony", odrzucony: "odrzucony" };
    const rest = p ? Object.entries(p).filter(([k, v]) => !known.has(k) && (typeof v !== "object" || v === null)).map(([k, v]) => [LABELS[k] || k, typeof v === "number" ? fmtInt(v) : String(v)]) : [];
    const coord = (o) => (o.x !== undefined && o.x !== null ? `x ${fmtInt(o.x)}, z ${fmtInt(o.z)}` : "miejsce wybierze planer");
    const actions = [{ label: "Zamknij", value: null }];
    if (isDirty) actions.unshift({ label: "Zapisz i odśwież podgląd", kind: "primary", value: "save" });
    const res = await modal({
      title: "Podgląd zmian planu",
      wide: true,
      body: h(
        "div.plan-preview",
        isDirty ? h("div.alert.alert-warn", icon("clock"), h("span", "Podgląd dotyczy planu zapisanego na serwerze — masz niezapisane zmiany.")) : null,
        !r.supported ? h("div.alert.alert-warn", icon("warn"), h("span", "Ludnosc nie obsługuje jeszcze planów, więc nie ma podglądu.")) : !p ? h("p.muted", "Ludnosc nie zwrócił podglądu (brak zapisanego planu albo funkcja nieobsługiwana).") : null,
        p
          ? h(
              "div.preview-grid",
              h("div.stat", h("div.stat-body", h("div.stat-label", "Zlecenia budowy"), h("div.stat-value", fmtInt(orders.length)))),
              h("div.stat", h("div.stat-body", h("div.stat-label", "Rozbiórki"), h("div.stat-value", fmtInt(demol.length)))),
              h("div.stat", h("div.stat-body", h("div.stat-label", "Koszt łącznie"), h("div.stat-value", total === null ? "—" : formatMoney(total)))),
            )
          : null,
        orders.length ? h("div", h("h4", "Zlecenia (w kolejności)"), h("ol.preview-list", orders.map((o) => h("li", h("strong", o.name || o.type || "budynek"), h("span.muted", " · " + coord(o) + (o.rotation ? ` · ${o.rotation}°` : "") + (o.source ? ` · ${o.source === "plan" ? "z planu" : o.source === "auto" ? "dopełnienie" : o.source}` : "") + (o.priority !== undefined ? ` · priorytet ${o.priority}` : "")), o.status ? h("span.preview-status.st-" + String(o.status).replace(/[^a-z]/g, ""), STATUS[o.status] || o.status) : null,
          typeof o.reason === "string" && o.reason ? h("p.small.warn-text", o.reason) : null,
          Number.isSafeInteger(o.retryAt) && o.retryAt > 0 ? h("p.small.muted", "Ponowna próba: " + fmtTime(o.retryAt)) : null,
          o.cost !== undefined ? h("span.preview-cost", formatMoney(o.cost)) : null)))) : null,
        demol.length ? h("div", h("h4", "Rozbiórki"), h("ul.preview-list.preview-demol", demol.map((o) => h("li", icon("warn"), h("strong", o.name || (types.get(o.type) || {}).name || o.type || "budynek"), h("span.muted", (o.x !== undefined ? " · " + coord(o) : "") + (o.reason ? " · " + o.reason : "")))))) : null,
        warns.length ? h("div", h("h4", "Uwagi planera"), h("ul.warn-list", warns.map((w) => h("li", icon("warn"), h("span", typeof w === "string" ? w : w.text || JSON.stringify(w)))))) : null,
        rest.length ? h("dl.preview-rest", rest.flatMap(([k, v]) => [h("dt", k), h("dd", v)])) : null,
        h("p.small.muted", `Szacunek panelu: budynki z planu (${fmtInt(ed.plan.buildings.length)}) kosztują razem ${formatMoney(estimate)} wg cennika Ludnosc.`),
      ),
      actions,
    });
    if (res === "save") {
      await savePlan(statusBox.querySelector("[data-act=save]"));
      if (!dirty()) showPreview(statusBox.querySelector("[data-act=preview]"));
    }
  }

  function exportPlan() {
    const json = JSON.stringify(M.toJson(ed.plan), null, 2);
    const blob = new Blob([json], { type: "application/json" });
    const a = h("a", { href: URL.createObjectURL(blob), download: `plan-${(c.name || id).replace(/[^\p{L}\p{N}_-]+/gu, "-")}.json` });
    document.body.appendChild(a);
    a.click();
    setTimeout(() => {
      URL.revokeObjectURL(a.href);
      a.remove();
    }, 1000);
    st.lastExport = json;
    toast("Wyeksportowano plan do pliku JSON.");
  }

  function importPlanFile(input) {
    const f = input.files && input.files[0];
    input.value = "";
    if (!f) return;
    if (f.size > 1024 * 1024) return toast("Plik jest za duży (najwyżej 1 MB).", "err");
    const rd = new FileReader();
    rd.onload = () => {
      try {
        const { plan, notes } = M.importPlan(String(rd.result || ""));
        ed.setPlan(plan);
        const v = M.validatePlan(M.toJson(plan), vctx());
        toast(`Wczytano plan z pliku: ${M.planCount(plan)} elementów${v.errors.length ? ` — błędy: ${v.errors.length} (zaznaczone na czerwono)` : ""}.${notes.length ? " " + notes.join(" ") : ""}`, v.errors.length ? "err" : "ok", 6000);
      } catch (e) {
        toast(e.message || String(e), "err", 6000);
      }
    };
    rd.onerror = () => toast("Nie udało się odczytać pliku.", "err");
    rd.readAsText(f);
  }

  async function commitTerrain(b) {
    if (!permissions.territory) return;
    const add_ = [...ed.terr.add].map(M.cparse);
    const rem = [...ed.terr.remove].map(M.cparse);
    if (!add_.length && !rem.length) return;
    const T = st.map.territory || {};
    const cost = M.claimCost(T.chunks ?? ed.land.size, add_.length, T.costBase || 0, T.costPerChunk || 0);
    const ok = await modal({
      title: "Zatwierdzić zmiany terenu?",
      body: h("div", h("p", `Dodasz ${fmtInt(add_.length)} i usuniesz ${fmtInt(rem.length)} chunków państwa ${c.name}.`), h("p.muted", st.free ? "Bez opłaty ze skarbca." : `Koszt ze skarbca NPC: ok. ${formatMoney(cost)} (dokładny liczy serwer; za pominięte chunki jest zwrot).`), h("p.small.muted", "Chunki cudzych krajów, obszary chronione, stolica i zmiany rozdzielające kraj zostaną pominięte z podaniem powodu.")),
      actions: [{ label: "Anuluj", value: null }, { label: "Zatwierdź teren", kind: "primary", value: true }],
    });
    if (!ok) return;
    const r = await act(b, () => api.mutate("admin.npc.territory", { id, add: add_, remove: rem, free: permissions.free && !!st.free }), (x) => (x && x.message) || "Zmieniono teren.");
    if (r === undefined) return;
    ed.terr = { add: new Set(), remove: new Set() };
    ed.terrainChanged();
    const skipped = arr(r.skipped);
    await refreshMap(true);
    if (skipped.length) {
      modal({
        title: `Pominięte chunki (${skipped.length})`,
        wide: true,
        body: h("div", h("p.muted", `Dodano ${fmtInt(r.added)}, usunięto ${fmtInt(r.removed)}. Tych zmian serwer nie wykonał:`), h("ul.warn-list.skip-list", skipped.slice(0, 200).map((s) => h("li", icon("warn"), h("span", h("strong", `chunk ${s.cx}, ${s.cz}: `), s.reason))))),
        actions: [{ label: "Zamknij", value: null }],
      });
    }
  }

  // ---------------------------------------------------------------- skład
  const editorWrap = h("section.plan-editor", toolbar, optsBar, h("div.plan-main", stage, side));
  drawDraftBar();
  add(body, editorWrap);
  drawToolbar();
  drawOptions();
  drawOptsBox();
  drawLegend();
  drawMapInfo();
  revalidate();
  drawSide();
  if (!keyHandlerOn) {
    keyHandlerOn = true;
    document.addEventListener("keydown", (e) => {
      const cur = [...store.values()].map((x) => x.editor).find((x) => x && x.canvas.isConnected);
      if (!cur || document.querySelector("dialog[open]")) return;
      const a = document.activeElement;
      if (a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) return;
      if (cur.key(e)) e.preventDefault();
    });
    document.addEventListener("keydown", (e) => {
      if (e.code === "Space") for (const x of store.values()) if (x.editor && x.editor.canvas.isConnected && document.activeElement === x.editor.canvas) (x.editor.space = true), e.preventDefault();
    });
    document.addEventListener("keyup", (e) => {
      if (e.code === "Space") for (const x of store.values()) if (x.editor) x.editor.space = false;
    });
  }
  requestAnimationFrame(() => {
    ed.resize();
    if (firstOpen || !prevEditor) ed.fit();
    ed.draw();
  });
  ui.editor = ed;
  return ui;
}
