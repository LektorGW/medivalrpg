// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — płótno edytora planu państwa NPC: podkład mapy (PNG z admin.npc.mapRender), zoom kółkiem / szczypaniem,
// przesuwanie, siatka chunków, granica kraju, cudze kraje i obszary chronione, istniejące budynki, oraz narzędzia:
// zaznacz/przesuń, strefa (wielokąt albo prostokąty), droga, mur (ręczny / wzdłuż granicy / wokół miasta, bramy),
// budynek (obrót R), gumka i teren (pędzel chunków, prostokąt, wypełnianie, pierścień). Stan planu: model.js.
import * as M from "./model.js";
import { levelRectangle } from "./access.js";

const HIT_PX = 8;
const CLOSE_PX = 11;
const MIN_S = 0.25;
const MAX_S = 24;

/** Kolory motywu z CSS (odświeżane przy zmianie motywu). */
function themeColors() {
  const cs = getComputedStyle(document.documentElement);
  const v = (n, d) => (cs.getPropertyValue(n) || "").trim() || d;
  return {
    bg: v("--surface", "#1d150d"),
    card: v("--card", "#251b11"),
    text: v("--text", "#f1e6cf"),
    muted: v("--muted", "#bcab8b"),
    gold: v("--gold", "#dcb86c"),
    danger: v("--danger", "#ef8571"),
    ok: v("--ok", "#a6d891"),
    warn: v("--warn", "#ecbd5a"),
    line: v("--line-strong", "rgba(217,181,107,.42)"),
    dark: document.documentElement.dataset.theme !== "light",
  };
}

const hexA = (hex, a) => {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex || ""));
  if (!m) return `rgba(128,128,128,${a})`;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};
const C = (v) => v + 0.5; // środek bloku

export class PlanEditor {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {{onChange?:Function, onSelect?:Function, onCursor?:Function, onTool?:Function, onToast?:Function, onTerrain?:Function}} cb
   */
  constructor(canvas, cb = {}) {
    this.canvas = canvas;
    this.cx = canvas.getContext("2d");
    this.cb = cb;
    canvas.planEditor = this; // dostęp dla testów E2E (bez wpływu na bezpieczeństwo — to stan strony)
    this.plan = M.emptyPlan();
    this.terr = { add: new Set(), remove: new Set() };
    this.history = new M.History(150);
    this.levelSelection = null;
    this.sel = null; // {kind, index}
    this.hover = null;
    this.tool = "select";
    this.opts = { zoneType: "mieszkalna", zoneMode: "poly", roadKind: "glowna", roadWidth: 5, wallMode: "points", wallInset: 2, wallSub: "draw", buildingType: null, rotation: 0, priority: M.DEFAULT_PRIORITY, terrMode: "add", terrShape: "brush" };
    this.draft = null; // rysowany element: {kind, points}
    this.drag = null;
    this.pointers = new Map();
    this.view = { s: 2, ox: 0, oz: 0 };
    this.touchMode = "pan"; // telefon: "pan" | "draw"
    this.layers = { grid: true, buildings: true, map: true };
    this.issues = new Map(); // "kind:index" → {level, texts}
    this.data = null;
    this.img = null;
    this.colors = themeColors();
    this.cursor = null;
    this.space = false;
    this.flash = null;
    this.raf = 0;
    this.dpr = 1;
    this.bind();
  }

  // ------------------------------------------------------------------ dane

  /** Dane z mapRender + typy budynków (Map type → {name, size, available, …}). */
  setData(map, types) {
    this.data = map;
    this.types = types || new Map();
    this.land = new Set((map.chunks || []).map(([x, z]) => M.ckey(x, z)));
    this.capital = map.capital ? M.ckey(map.capital.cx, map.capital.cz) : null;
    this.foreign = new Map();
    for (const o of map.others || []) for (const [x, z] of o.chunks || []) this.foreign.set(M.ckey(x, z), o);
    this.protectedAreas = map.protected || [];
    this.existing = map.buildings || [];
    this.sites = map.sites || [];
    this.margin = 32;
    this.outlineCache = null;
    this.landSegs = M.outline([...this.land].map(M.cparse), (x, z) => this.land.has(M.ckey(x, z)));
    this.foreignSegs = (map.others || []).map((o) => {
      const set = new Set((o.chunks || []).map(([x, z]) => M.ckey(x, z)));
      return { o, segs: M.outline(o.chunks || [], (x, z) => set.has(M.ckey(x, z))) };
    });
    this.img = null;
    if (map.png) {
      const img = new Image();
      img.onload = () => {
        this.img = img;
        this.requestDraw();
      };
      img.src = "data:image/png;base64," + map.png;
    }
    this.requestDraw();
  }

  /** Ziemia kraju po zatwierdzonych i oczekujących zmianach terenu. */
  effectiveLand() {
    const s = new Set(this.land);
    for (const k of this.terr.add) s.add(k);
    for (const k of this.terr.remove) s.delete(k);
    return s;
  }

  blockedBy(k) {
    const f = this.foreign.get(k);
    if (f) return "należy do kraju " + f.name;
    const [cx, cz] = M.cparse(k);
    const p = this.protectedAreas.find((a) => cx >= a.minCX && cx <= a.maxCX && cz >= a.minCZ && cz <= a.maxCZ);
    return p ? "obszar chroniony: " + p.label : null;
  }

  setPlan(plan, { record = true, keepSel = false } = {}) {
    if (record) this.history.push(this.snapshot());
    this.plan = plan;
    if (!keepSel) this.sel = null;
    this.changed();
  }

  snapshot() {
    return { plan: this.plan, add: [...this.terr.add], remove: [...this.terr.remove] };
  }

  restore(s) {
    this.plan = s.plan;
    this.terr = { add: new Set(s.add), remove: new Set(s.remove) };
    this.sel = null;
    this.draft = null;
    this.changed();
    this.terrainChanged();
  }

  /** Zmiana planu przez narzędzie: migawka do historii, potem mutacja. */
  edit(fn) {
    this.history.push(this.snapshot());
    this.plan = M.clone(this.plan);
    fn(this.plan);
    this.changed();
  }

  changed() {
    this.outlineCache = null;
    this.requestDraw();
    if (this.cb.onChange) this.cb.onChange();
  }

  setLevelSelection(rect) {
    this.levelSelection = rect;
    this.requestDraw();
    if (this.cb.onLevelSelection) this.cb.onLevelSelection(rect);
  }

  terrainChanged() {
    this.outlineCache = null;
    this.requestDraw();
    if (this.cb.onTerrain) this.cb.onTerrain();
  }

  undo() {
    if (this.draft) return this.cancelDraft();
    const s = this.history.undo(this.snapshot());
    if (s) this.restore(s);
  }

  redo() {
    const s = this.history.redo(this.snapshot());
    if (s) this.restore(s);
  }

  select(sel) {
    this.sel = sel && this.plan[sel.kind] && this.plan[sel.kind][sel.index] ? sel : null;
    this.requestDraw();
    if (this.cb.onSelect) this.cb.onSelect(this.sel);
  }

  deleteSelected() {
    if (!this.sel) return;
    const { kind, index } = this.sel;
    this.edit((p) => p[kind].splice(index, 1));
    this.select(null);
  }

  rotate() {
    if (this.tool === "building") {
      this.opts.rotation = (this.opts.rotation + 90) % 360;
      this.requestDraw();
      if (this.cb.onTool) this.cb.onTool();
      return;
    }
    if (this.sel && this.sel.kind === "buildings") {
      const i = this.sel.index;
      this.edit((p) => (p.buildings[i].rotation = ((p.buildings[i].rotation || 0) + 90) % 360));
      if (this.cb.onSelect) this.cb.onSelect(this.sel);
    }
  }

  setTool(t) {
    this.cancelDraft();
    this.tool = t;
    this.canvas.dataset.tool = t;
    this.requestDraw();
    if (this.cb.onTool) this.cb.onTool();
  }

  cancelDraft() {
    if (this.draft) {
      this.draft = null;
      this.requestDraw();
      this.draftChanged();
    }
  }

  draftChanged() {
    if (this.cb.onDraft) this.cb.onDraft(this.draft);
  }

  /** Cofa ostatni punkt rysowanego elementu. */
  popDraftPoint() {
    if (!this.draft) return;
    this.draft.points.pop();
    if (!this.draft.points.length) this.draft = null;
    this.requestDraw();
    this.draftChanged();
  }

  setIssues(list, level) {
    for (const i of list) {
      if (!i.kind) continue;
      const k = i.kind + ":" + i.index;
      const cur = this.issues.get(k) || { level, texts: [] };
      if (level === "error") cur.level = "error";
      cur.texts.push(i.text);
      this.issues.set(k, cur);
    }
    this.requestDraw();
  }

  clearIssues() {
    this.issues.clear();
    this.requestDraw();
  }

  // ------------------------------------------------------------------ widok

  toWorld(sx, sy) {
    return { x: sx / this.view.s + this.view.ox, z: sy / this.view.s + this.view.oz };
  }

  toScreen(x, z) {
    return { x: (x - this.view.ox) * this.view.s, y: (z - this.view.oz) * this.view.s };
  }

  /** Pozycja na stronie (clientX/Y) dla bloku — używana przez testy E2E. */
  clientPoint(x, z) {
    const r = this.canvas.getBoundingClientRect();
    const p = this.toScreen(C(x), C(z));
    return { x: r.left + p.x, y: r.top + p.y };
  }

  bounds() {
    const d = this.data;
    if (!d) return { minX: 0, minZ: 0, maxX: 256, maxZ: 256 };
    if (d.width && d.height) return { minX: d.originX, minZ: d.originZ, maxX: d.originX + d.width * (d.scale || 1), maxZ: d.originZ + d.height * (d.scale || 1) };
    const xs = (d.chunks || []).map((c) => c[0]);
    const zs = (d.chunks || []).map((c) => c[1]);
    return { minX: Math.min(...xs) * 16, minZ: Math.min(...zs) * 16, maxX: (Math.max(...xs) + 1) * 16, maxZ: (Math.max(...zs) + 1) * 16 };
  }

  fit() {
    const b = this.bounds();
    const w = this.canvas.clientWidth || 600;
    const h = this.canvas.clientHeight || 400;
    const s = Math.max(MIN_S, Math.min(MAX_S, Math.min(w / (b.maxX - b.minX), h / (b.maxZ - b.minZ)) * 0.96));
    this.view.s = s;
    this.view.ox = (b.minX + b.maxX) / 2 - w / 2 / s;
    this.view.oz = (b.minZ + b.maxZ) / 2 - h / 2 / s;
    this.requestDraw();
  }

  zoomAt(f, sx, sy) {
    const before = this.toWorld(sx, sy);
    this.view.s = Math.max(MIN_S, Math.min(MAX_S, this.view.s * f));
    this.view.ox = before.x - sx / this.view.s;
    this.view.oz = before.z - sy / this.view.s;
    this.requestDraw();
  }

  zoomBy(f) {
    this.zoomAt(f, (this.canvas.clientWidth || 600) / 2, (this.canvas.clientHeight || 400) / 2);
  }

  centerOn(x, z) {
    this.view.ox = x - (this.canvas.clientWidth || 600) / 2 / this.view.s;
    this.view.oz = z - (this.canvas.clientHeight || 400) / 2 / this.view.s;
    this.requestDraw();
  }

  resize() {
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    const w = Math.max(1, Math.round(this.canvas.clientWidth * dpr));
    const h = Math.max(1, Math.round(this.canvas.clientHeight * dpr));
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    this.dpr = dpr;
    this.requestDraw();
  }

  refreshTheme() {
    this.colors = themeColors();
    this.requestDraw();
  }

  // ------------------------------------------------------------------ zdarzenia

  bind() {
    const c = this.canvas;
    c.addEventListener("pointerdown", (e) => this.onDown(e));
    c.addEventListener("pointermove", (e) => this.onMove(e));
    c.addEventListener("pointerup", (e) => this.onUp(e));
    c.addEventListener("pointercancel", (e) => this.onUp(e, true));
    c.addEventListener("pointerleave", () => {
      this.cursor = null;
      if (this.cb.onCursor) this.cb.onCursor(null);
      this.requestDraw();
    });
    c.addEventListener("dblclick", (e) => {
      e.preventDefault();
      this.finishDraft();
    });
    c.addEventListener("contextmenu", (e) => e.preventDefault());
    c.addEventListener(
      "wheel",
      (e) => {
        e.preventDefault();
        const r = c.getBoundingClientRect();
        this.zoomAt(Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : 0.0018)), e.clientX - r.left, e.clientY - r.top);
      },
      { passive: false },
    );
    this.ro = new ResizeObserver(() => this.resize());
    this.ro.observe(c);
    this.themeObs = new MutationObserver(() => this.refreshTheme());
    this.themeObs.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  }

  destroy() {
    this.ro.disconnect();
    this.themeObs.disconnect();
    cancelAnimationFrame(this.raf);
  }

  local(e) {
    const r = this.canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  block(p) {
    const w = this.toWorld(p.x, p.y);
    return { x: Math.floor(w.x), z: Math.floor(w.z), fx: w.x, fz: w.z };
  }

  wantsPan(e) {
    if (e.button === 1 || e.button === 2 || this.space || this.tool === "hand") return true;
    return e.pointerType === "touch" && this.touchMode === "pan";
  }

  onDown(e) {
    this.canvas.setPointerCapture(e.pointerId);
    const p = this.local(e);
    this.pointers.set(e.pointerId, p);
    if (this.pointers.size === 2) {
      const [a, b] = [...this.pointers.values()];
      this.drag = { type: "pinch", d: Math.hypot(a.x - b.x, a.y - b.y), m: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } };
      return;
    }
    if (this.pointers.size > 2) return;
    const shift = e.shiftKey;
    const start = { p, b: this.block(p), moved: false, shift };
    if (this.wantsPan(e)) {
      this.drag = { type: "pan", ...start, ox: this.view.ox, oz: this.view.oz };
      return;
    }
    this.drag = { type: "tool", ...start };
    this.toolDown(this.drag, e);
  }

  onMove(e) {
    const p = this.local(e);
    if (this.pointers.has(e.pointerId)) this.pointers.set(e.pointerId, p);
    const b = this.block(p);
    this.cursor = b;
    if (this.cb.onCursor) this.cb.onCursor(b);
    const d = this.drag;
    if (d && d.type === "pinch" && this.pointers.size >= 2) {
      const [a, q] = [...this.pointers.values()];
      const dist = Math.hypot(a.x - q.x, a.y - q.y);
      const m = { x: (a.x + q.x) / 2, y: (a.y + q.y) / 2 };
      if (d.d > 0) this.zoomAt(dist / d.d, m.x, m.y);
      this.view.ox -= (m.x - d.m.x) / this.view.s;
      this.view.oz -= (m.y - d.m.y) / this.view.s;
      d.d = dist;
      d.m = m;
      this.requestDraw();
      return;
    }
    if (d && Math.hypot(p.x - d.p.x, p.y - d.p.y) > 4) d.moved = true;
    if (d && d.type === "pan") {
      this.view.ox = d.ox - (p.x - d.p.x) / this.view.s;
      this.view.oz = d.oz - (p.y - d.p.y) / this.view.s;
      this.requestDraw();
      return;
    }
    if (d && d.type === "tool") this.toolMove(d, b, p, e);
    else this.hover = this.tool === "select" || this.tool === "eraser" ? this.hitTest(p) : null;
    this.requestDraw();
  }

  onUp(e, cancel = false) {
    this.pointers.delete(e.pointerId);
    const d = this.drag;
    if (!d) return;
    if (d.type === "pinch") {
      if (this.pointers.size === 0) this.drag = null;
      return;
    }
    this.drag = null;
    if (cancel) return this.requestDraw();
    if (d.type === "pan") {
      if (!d.moved && this.tool === "select") this.select(this.hitTest(d.p));
      return;
    }
    this.toolUp(d, this.block(this.local(e)), e);
    this.requestDraw();
  }

  /** Klawiatura (wywoływana przez widok, gdy fokus nie jest w polu formularza). Zwraca true, gdy obsłużono. */
  key(e) {
    const k = e.key;
    const mod = e.ctrlKey || e.metaKey;
    if (mod && (k === "z" || k === "Z") && !e.shiftKey) return this.undo(), true;
    if (mod && (k === "y" || k === "Y" || ((k === "z" || k === "Z") && e.shiftKey))) return this.redo(), true;
    if (mod) return false;
    if (k === "r" || k === "R") return this.rotate(), true;
    if (k === "Escape") {
      if (this.draft) this.cancelDraft();
      else if (this.tool === "leveling" && this.levelSelection) this.setLevelSelection(null);
      else this.select(null);
      return true;
    }
    if (k === "Enter") return this.finishDraft(), true;
    if (k === "Backspace" && this.draft) return this.popDraftPoint(), true;
    if ((k === "Delete" || k === "Backspace") && this.sel) return this.deleteSelected(), true;
    if (k === "+" || k === "=") return this.zoomBy(1.25), true;
    if (k === "-") return this.zoomBy(0.8), true;
    return false;
  }

  // ------------------------------------------------------------------ narzędzia

  toolDown(d, e) {
    const b = d.b;
    switch (this.tool) {
      case "select": {
        const hit = this.handleAt(d.p) || this.hitTest(d.p);
        if (!hit) {
          // puste miejsce: przesuwanie widoku
          Object.assign(d, { type: "pan", ox: this.view.ox, oz: this.view.oz });
          return;
        }
        if (hit.vertex !== undefined) {
          d.vertex = hit;
          return;
        }
        this.select(hit);
        d.move = { sel: hit, orig: M.clone(this.plan[hit.kind][hit.index]), recorded: false };
        return;
      }
      case "leveling":
        d.rect = { x0: b.x, z0: b.z, x1: b.x, z1: b.z };
        return;
      case "zone":
        if (this.opts.zoneMode === "rect") d.rect = { x0: b.x, z0: b.z, x1: b.x, z1: b.z };
        return;
      case "terrain":
        d.terrMode = this.terrModeFor(e);
        if (this.opts.terrShape === "brush") {
          this.history.push(this.snapshot());
          d.painted = new Set();
          this.paintChunk(M.ckey(...M.chunkOf(b.x, b.z)), d.terrMode, d.painted);
        } else if (this.opts.terrShape === "rect") d.rect = { x0: b.x, z0: b.z, x1: b.x, z1: b.z };
        return;
      case "eraser":
        d.erased = 0;
        this.eraseAt(d.p, d);
        return;
      default:
    }
  }

  toolMove(d, b, p) {
    if (d.vertex) {
      const { kind, index, vertex, list } = d.vertex;
      if (!d.vertexRecorded) {
        this.history.push(this.snapshot());
        this.plan = M.clone(this.plan);
        d.vertexRecorded = true;
      }
      this.plan[kind][index][list][vertex] = [b.x, b.z];
      this.changed();
      return;
    }
    if (d.move && d.moved) {
      const dx = b.x - d.b.x;
      const dz = b.z - d.b.z;
      if (!d.move.recorded) {
        this.history.push(this.snapshot());
        this.plan = M.clone(this.plan);
        d.move.recorded = true;
      }
      this.plan[d.move.sel.kind][d.move.sel.index] = translate(d.move.orig, d.move.sel.kind, dx, dz);
      this.changed();
      return;
    }
    if (d.rect) {
      d.rect.x1 = b.x;
      d.rect.z1 = b.z;
      return;
    }
    if (d.painted) {
      this.paintChunk(M.ckey(...M.chunkOf(b.x, b.z)), d.terrMode, d.painted);
      return;
    }
    if (d.erased !== undefined && d.moved) this.eraseAt(p, d);
  }

  toolUp(d, b, e) {
    const click = !d.moved;
    switch (this.tool) {
      case "select":
        if (d.vertex && d.vertexRecorded) this.select({ kind: d.vertex.kind, index: d.vertex.index });
        return;
      case "leveling":
        try { this.setLevelSelection(levelRectangle({x:d.b.x,z:d.b.z}, b)); }
        catch (err) { this.toast(err.message, "err"); }
        return;
      case "zone":
        if (this.opts.zoneMode === "rect") {
          const r = norm(d.rect);
          if (r.maxX - r.minX >= 1 && r.maxZ - r.minZ >= 1) {
            this.edit((p) => p.zones.push({ type: this.opts.zoneType, polygon: [[r.minX, r.minZ], [r.maxX, r.minZ], [r.maxX, r.maxZ], [r.minX, r.maxZ]] }));
            this.select({ kind: "zones", index: this.plan.zones.length - 1 });
          }
          return;
        }
        if (click) this.addDraftPoint("zones", b, d.p);
        return;
      case "road":
        if (click) this.addDraftPoint("roads", b, d.p);
        return;
      case "wall":
        if (!click) return;
        if (this.opts.wallSub === "gate") return this.toggleGate(d.p);
        if (this.opts.wallMode === "points") this.addDraftPoint("walls", b, d.p);
        return;
      case "building":
        if (click) this.placeBuilding(b);
        return;
      case "terrain": {
        if (this.opts.terrShape === "rect" && d.rect) {
          const r = norm(d.rect);
          const countX = Math.floor(r.maxX / 16) - Math.floor(r.minX / 16) + 1;
          const countZ = Math.floor(r.maxZ / 16) - Math.floor(r.minZ / 16) + 1;
          if (!Number.isSafeInteger(countX * countZ) || countX * countZ > 4096) return this.toast("Za duży prostokąt — zaznacz mniej niż 4096 chunków naraz.", "err");
          const keys = [];
          for (let cx = Math.floor(r.minX / 16); cx <= Math.floor(r.maxX / 16); cx++) for (let cz = Math.floor(r.minZ / 16); cz <= Math.floor(r.maxZ / 16); cz++) keys.push(M.ckey(cx, cz));
          if (keys.length > 4096) return this.toast("Za duży prostokąt — zaznacz mniej niż 4096 chunków naraz.", "err");
          this.history.push(this.snapshot());
          const painted = new Set();
          for (const k of keys) this.paintChunk(k, d.terrMode, painted, true);
          this.reportBlocked(painted);
        } else if (this.opts.terrShape === "fill" && click) this.fillAt(M.ckey(...M.chunkOf(b.x, b.z)), d.terrMode);
        else if (d.painted) this.reportBlocked(d.painted);
        return;
      }
      default:
    }
  }

  terrModeFor(e) {
    const base = this.opts.terrMode;
    return e && e.shiftKey ? (base === "add" ? "remove" : "add") : base;
  }

  toast(msg, kind = "ok") {
    if (this.cb.onToast) this.cb.onToast(msg, kind);
  }

  // ---- rysowanie łamanych / wielokątów

  addDraftPoint(kind, b, sp) {
    if (!this.draft || this.draft.kind !== kind) this.draft = { kind, points: [] };
    const pts = this.draft.points;
    if (pts.length >= 3 && (kind === "zones" || kind === "walls")) {
      const f = this.toScreen(C(pts[0][0]), C(pts[0][1]));
      if (Math.hypot(f.x - sp.x, f.y - sp.y) <= CLOSE_PX) return this.finishDraft(true);
    }
    const last = pts[pts.length - 1];
    if (last && last[0] === b.x && last[1] === b.z) return;
    if (pts.length >= M.LIMITS.pointsPerElement) return this.toast(`Najwyżej ${M.LIMITS.pointsPerElement} punktów w jednym elemencie.`, "err");
    pts.push([b.x, b.z]);
    this.requestDraw();
    this.draftChanged();
  }

  /** Kończy rysowany element (Enter / podwójny klik / klik w pierwszy punkt). */
  finishDraft(closed = false) {
    const d = this.draft;
    if (!d) return;
    const pts = d.points;
    if (d.kind === "zones") {
      if (pts.length < 3) return this.toast("Strefa potrzebuje co najmniej 3 punktów.", "err");
      this.edit((p) => p.zones.push({ type: this.opts.zoneType, polygon: pts }));
    } else if (d.kind === "roads") {
      if (pts.length < 2) return this.toast("Droga potrzebuje co najmniej 2 punktów.", "err");
      this.edit((p) => p.roads.push({ kind: this.opts.roadKind, points: pts, width: this.opts.roadWidth }));
    } else if (d.kind === "walls") {
      if (pts.length < (closed ? 3 : 2)) return this.toast("Mur potrzebuje co najmniej 2 punktów.", "err");
      this.edit((p) => p.walls.push({ mode: "points", points: pts, closed, gates: [] }));
    }
    this.draft = null;
    this.draftChanged();
    this.select({ kind: d.kind, index: this.plan[d.kind].length - 1 });
  }

  /** Mur automatyczny (wzdłuż granicy / wokół miasta): jeden na tryb. */
  addAutoWall(mode, inset) {
    const i = this.plan.walls.findIndex((w) => w.mode === mode);
    if (i >= 0) {
      this.edit((p) => (p.walls[i].inset = inset));
      this.select({ kind: "walls", index: i });
      return false;
    }
    this.edit((p) => p.walls.push({ mode, points: [], closed: true, gates: [], inset }));
    this.select({ kind: "walls", index: this.plan.walls.length - 1 });
    return true;
  }

  /** Odcinki muru w blokach (dla trybów automatycznych — podgląd). */
  wallSegments(w) {
    if (!w.mode || w.mode === "points") {
      const s = [];
      const pts = w.points || [];
      const n = w.closed ? pts.length : pts.length - 1;
      for (let i = 0; i < n; i++) {
        const a = pts[i];
        const b = pts[(i + 1) % pts.length];
        s.push([C(a[0]), C(a[1]), C(b[0]), C(b[1])]);
      }
      return s;
    }
    const key = w.mode + ":" + w.inset;
    if (!this.outlineCache) this.outlineCache = new Map();
    if (this.outlineCache.has(key)) return this.outlineCache.get(key);
    let segs = [];
    if (w.mode === "follow-border") segs = M.insetOutline(this.effectiveLand(), w.inset || 0);
    else {
      const rects = [...this.existing.filter((b) => b.minX !== undefined), ...this.plan.buildings.map((b) => M.footprint(M.typeSize(this.types, b.type), b.x, b.z, b.rotation || 0))];
      // jak Ludnosc (WallRoute.ring): otoczka zabudowy z odstępem max(2, inset + 4)
      const hull = rects.length ? M.townOutline(rects, Math.max(2, (w.inset || 0) + 4)) : [];
      for (let i = 0; i < hull.length; i++) {
        const a = hull[i];
        const b = hull[(i + 1) % hull.length];
        segs.push([a[0], a[1], b[0], b[1]]);
      }
    }
    this.outlineCache.set(key, segs);
    return segs;
  }

  /** Klik w trybie „Bramy”: dodaje bramę na najbliższym murze albo usuwa istniejącą. */
  toggleGate(sp) {
    const w = this.toWorld(sp.x, sp.y);
    let best = null;
    this.plan.walls.forEach((wall, i) => {
      for (const [gi, g] of (wall.gates || []).entries()) {
        const s = this.toScreen(C(g[0]), C(g[1]));
        const d = Math.hypot(s.x - sp.x, s.y - sp.y);
        if (d <= HIT_PX + 2 && (!best || d < best.d)) best = { d, i, gate: gi };
      }
    });
    if (best) {
      this.edit((p) => p.walls[best.i].gates.splice(best.gate, 1));
      return this.toast("Usunięto bramę.");
    }
    this.plan.walls.forEach((wall, i) => {
      for (const s of this.wallSegments(wall)) {
        const q = nearestSeg(w.x, w.z, s);
        const d = q.d * this.view.s;
        if (d <= HIT_PX + 6 && (!best || d < best.d)) best = { d, i, x: Math.floor(q.x), z: Math.floor(q.z) };
      }
    });
    if (!best) return this.toast("Kliknij na linii muru, żeby postawić bramę.", "err");
    if ((this.plan.walls[best.i].gates || []).length >= M.LIMITS.gates) return this.toast(`Najwyżej ${M.LIMITS.gates} bram na mur.`, "err");
    this.edit((p) => (p.walls[best.i].gates = [...(p.walls[best.i].gates || []), [best.x, best.z]]));
    this.select({ kind: "walls", index: best.i });
    this.toast("Dodano bramę.");
  }

  // ---- budynki

  ghost() {
    if (this.tool !== "building" || !this.cursor || !this.opts.buildingType) return null;
    const size = M.typeSize(this.types, this.opts.buildingType);
    return { type: this.opts.buildingType, x: this.cursor.x, z: this.cursor.z, rotation: this.opts.rotation, fp: M.footprint(size, this.cursor.x, this.cursor.z, this.opts.rotation) };
  }

  placeBuilding(b) {
    if (!this.opts.buildingType) return this.toast("Wybierz typ budynku z listy.", "err");
    if (!M.nearChunks(this.effectiveLand(), b.x, b.z, this.margin)) return this.toast("Budynek musi stać na ziemi kraju (albo najwyżej " + this.margin + " bl. od niej).", "err");
    if (this.plan.buildings.length >= M.LIMITS.buildings) return this.toast(`Najwyżej ${M.LIMITS.buildings} budynków w planie.`, "err");
    this.edit((p) => p.buildings.push({ type: this.opts.buildingType, x: b.x, z: b.z, rotation: this.opts.rotation, priority: this.opts.priority }));
    this.select({ kind: "buildings", index: this.plan.buildings.length - 1 });
  }

  // ---- gumka

  eraseAt(sp, d) {
    const hit = this.hitTest(sp);
    if (!hit) return;
    if (!d.erased) this.history.push(this.snapshot());
    d.erased++;
    this.plan = M.clone(this.plan);
    this.plan[hit.kind].splice(hit.index, 1);
    this.sel = null;
    this.changed();
    if (this.cb.onSelect) this.cb.onSelect(null);
  }

  // ---- teren

  paintChunk(k, mode, painted, silent = false) {
    if (painted.has(k)) return;
    painted.add(k);
    if (mode === "add") {
      if (this.land.has(k)) {
        if (this.terr.remove.delete(k)) this.terrainChanged();
        return;
      }
      const why = this.blockedBy(k);
      if (why) {
        painted.blocked = (painted.blocked || 0) + 1;
        painted.why = why;
        if (!silent) this.flashChunk(k);
        return;
      }
      if (this.terr.add.size + this.terr.remove.size >= 512 && !this.terr.add.has(k)) {
        painted.limit = true;
        return;
      }
      this.terr.add.add(k);
    } else {
      if (this.terr.add.delete(k)) return this.terrainChanged();
      if (!this.land.has(k)) return;
      if (k === this.capital) {
        painted.capital = true;
        if (!silent) this.flashChunk(k);
        return;
      }
      if (this.terr.add.size + this.terr.remove.size >= 512) {
        painted.limit = true;
        return;
      }
      this.terr.remove.add(k);
    }
    this.terrainChanged();
  }

  reportBlocked(p) {
    if (p.blocked) this.toast(`Pominięto ${p.blocked} chunków: ${p.why}.`, "err");
    else if (p.capital) this.toast("Stolicy nie można usunąć.", "err");
    else if (p.limit) this.toast("Najwyżej 512 zmian terenu naraz — zatwierdź te, które są.", "err");
  }

  flashChunk(k) {
    this.flash = { k, until: performance.now() + 450 };
    this.requestDraw();
    setTimeout(() => this.requestDraw(), 480);
  }

  /** Wypełnianie obszaru zamkniętego granicą (ziemia kraju + zaznaczone chunki). */
  fillAt(k, mode) {
    if (mode !== "add") return this.toast("Wypełnianie działa przy dodawaniu terenu.", "err");
    const terr = this.effectiveLand();
    if (terr.has(k)) return this.toast("Kliknij wolny chunk wewnątrz granicy, którą chcesz wypełnić.", "err");
    let minCX = Infinity;
    let minCZ = Infinity;
    let maxCX = -Infinity;
    let maxCZ = -Infinity;
    for (const t of terr) {
      const [x, z] = M.cparse(t);
      minCX = Math.min(minCX, x);
      minCZ = Math.min(minCZ, z);
      maxCX = Math.max(maxCX, x);
      maxCZ = Math.max(maxCZ, z);
    }
    const area = M.floodFill(k, (q) => terr.has(q), { minCX, minCZ, maxCX, maxCZ }, 512);
    if (!area) return this.toast("Ten obszar nie jest zamknięty granicą kraju — domknij ją pędzlem albo użyj prostokąta.", "err");
    this.history.push(this.snapshot());
    const painted = new Set();
    for (const q of area) this.paintChunk(q, "add", painted, true);
    this.reportBlocked(painted);
    this.toast(`Wypełniono obszar: ${area.size - (painted.blocked || 0)} chunków.`);
  }

  /** Poszerza teren o pierścień chunków wokół obecnej granicy. */
  growRing() {
    const terr = this.effectiveLand();
    // pierścień rośnie na zewnątrz: nie cofa chunków zaznaczonych do usunięcia
    const ringSet = M.ring(terr, (k) => !this.blockedBy(k) && !this.terr.remove.has(k));
    if (!ringSet.size) return this.toast("Wokół granicy nie ma wolnych chunków.", "err");
    this.history.push(this.snapshot());
    const painted = new Set();
    for (const k of ringSet) this.paintChunk(k, "add", painted, true);
    this.reportBlocked(painted);
    this.toast(`Dodano pierścień: ${[...ringSet].filter((k) => this.terr.add.has(k)).length} chunków.`);
  }

  clearTerrain() {
    if (!this.terr.add.size && !this.terr.remove.size) return;
    this.history.push(this.snapshot());
    this.terr = { add: new Set(), remove: new Set() };
    this.terrainChanged();
  }

  // ------------------------------------------------------------------ trafienia

  /** Uchwyt wierzchołka zaznaczonego elementu pod kursorem. */
  handleAt(sp) {
    if (!this.sel) return null;
    const { kind, index } = this.sel;
    const el = this.plan[kind][index];
    if (!el) return null;
    const list = kind === "zones" ? "polygon" : kind === "roads" || (kind === "walls" && (!el.mode || el.mode === "points")) ? "points" : null;
    if (!list) return null;
    const pts = el[list] || [];
    for (let i = pts.length - 1; i >= 0; i--) {
      const s = this.toScreen(C(pts[i][0]), C(pts[i][1]));
      if (Math.hypot(s.x - sp.x, s.y - sp.y) <= HIT_PX) return { kind, index, vertex: i, list };
    }
    return null;
  }

  hitTest(sp) {
    const w = this.toWorld(sp.x, sp.y);
    const tol = HIT_PX / this.view.s;
    const P = this.plan;
    for (let i = P.buildings.length - 1; i >= 0; i--) {
      const b = P.buildings[i];
      const f = M.footprint(M.typeSize(this.types, b.type), b.x, b.z, b.rotation || 0);
      if (w.x >= f.minX - tol / 2 && w.x <= f.maxX + 1 + tol / 2 && w.z >= f.minZ - tol / 2 && w.z <= f.maxZ + 1 + tol / 2) return { kind: "buildings", index: i };
    }
    for (let i = P.walls.length - 1; i >= 0; i--) {
      for (const s of this.wallSegments(P.walls[i])) if (nearestSeg(w.x, w.z, s).d <= tol + 1.5) return { kind: "walls", index: i };
    }
    for (let i = P.roads.length - 1; i >= 0; i--) {
      const r = P.roads[i];
      const pts = r.points.map(([x, z]) => [C(x), C(z)]);
      if (pts.length && M.distToPolyline(pts, false, w.x, w.z) <= tol + (r.width || 3) / 2) return { kind: "roads", index: i };
    }
    let best = null;
    P.zones.forEach((z, i) => {
      const pts = z.polygon.map(([x, q]) => [C(x), C(q)]);
      if (pts.length >= 3 && M.pointInPolygon(pts, w.x, w.z)) {
        const a = Math.abs(M.polygonArea(pts));
        if (!best || a < best.a) best = { a, i };
      }
    });
    return best ? { kind: "zones", index: best.i } : null;
  }

  // ------------------------------------------------------------------ rysowanie

  requestDraw() {
    if (this.raf) return;
    this.raf = requestAnimationFrame(() => {
      this.raf = 0;
      this.draw();
    });
  }

  draw() {
    const g = this.cx;
    const T = this.colors;
    const { s } = this.view;
    const W = this.canvas.width;
    const H = this.canvas.height;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.fillStyle = T.dark ? "#14100b" : "#d9c9a5";
    g.fillRect(0, 0, W, H);
    g.setTransform(this.dpr * s, 0, 0, this.dpr * s, -this.view.ox * s * this.dpr, -this.view.oz * s * this.dpr);
    const px = 1 / s; // 1 piksel ekranu w blokach
    const vis = this.visibleBlocks();
    const d = this.data;
    if (!d) return;

    // podkład
    if (this.img && this.layers.map) {
      g.imageSmoothingEnabled = s * (d.scale || 1) < 1.5;
      g.drawImage(this.img, d.originX, d.originZ, d.width * (d.scale || 1), d.height * (d.scale || 1));
    }
    // cudze kraje i obszary chronione
    for (const [k, o] of this.foreign) {
      const [cx, cz] = M.cparse(k);
      if (!inView(vis, cx, cz)) continue;
      g.fillStyle = hexA(o.color, 0.33);
      g.fillRect(cx * 16, cz * 16, 16, 16);
    }
    for (const { o, segs } of this.foreignSegs) {
      this.strokeSegs(segs, o.color, 2 * px);
      if (s > 0.6) this.labelAt(o.name, o.chunks, T, px, "#fff");
    }
    for (const a of this.protectedAreas) {
      const x = a.minCX * 16;
      const z = a.minCZ * 16;
      const w = (a.maxCX - a.minCX + 1) * 16;
      const h = (a.maxCZ - a.minCZ + 1) * 16;
      g.fillStyle = "rgba(120,120,120,0.42)";
      g.fillRect(x, z, w, h);
      g.save();
      g.beginPath();
      g.rect(x, z, w, h);
      g.clip();
      g.strokeStyle = "rgba(230,230,230,0.35)";
      g.lineWidth = 2 * px;
      for (let t = -h; t < w; t += 10 * px * 2) {
        g.beginPath();
        g.moveTo(x + t, z);
        g.lineTo(x + t + h, z + h);
        g.stroke();
      }
      g.restore();
      g.strokeStyle = "rgba(200,200,200,0.85)";
      g.lineWidth = 1.5 * px;
      g.strokeRect(x, z, w, h);
      this.text(a.label + " — obszar chroniony", x + w / 2, z + h / 2, T, px, "#eee");
    }
    // siatka chunków
    if (this.layers.grid && s >= 0.9) {
      g.strokeStyle = T.dark ? "rgba(255,240,210,0.16)" : "rgba(40,25,5,0.16)";
      g.lineWidth = px;
      g.beginPath();
      for (let x = Math.floor(vis.minX / 16) * 16; x <= vis.maxX; x += 16) {
        g.moveTo(x, vis.minZ);
        g.lineTo(x, vis.maxZ);
      }
      for (let z = Math.floor(vis.minZ / 16) * 16; z <= vis.maxZ; z += 16) {
        g.moveTo(vis.minX, z);
        g.lineTo(vis.maxX, z);
      }
      g.stroke();
    }
    // teren: oczekujące zmiany
    for (const k of this.terr.add) {
      const [cx, cz] = M.cparse(k);
      g.fillStyle = "rgba(76,200,90,0.42)";
      g.fillRect(cx * 16, cz * 16, 16, 16);
    }
    for (const k of this.terr.remove) {
      const [cx, cz] = M.cparse(k);
      g.fillStyle = "rgba(230,60,50,0.45)";
      g.fillRect(cx * 16, cz * 16, 16, 16);
      g.strokeStyle = "rgba(255,255,255,0.7)";
      g.lineWidth = 1.5 * px;
      g.beginPath();
      g.moveTo(cx * 16 + 4, cz * 16 + 4);
      g.lineTo(cx * 16 + 12, cz * 16 + 12);
      g.moveTo(cx * 16 + 12, cz * 16 + 4);
      g.lineTo(cx * 16 + 4, cz * 16 + 12);
      g.stroke();
    }
    if (this.flash && performance.now() < this.flash.until) {
      const [cx, cz] = M.cparse(this.flash.k);
      g.fillStyle = "rgba(255,40,40,0.6)";
      g.fillRect(cx * 16, cz * 16, 16, 16);
    }
    // granica kraju
    this.strokeSegs(this.landSegs, "rgba(0,0,0,0.6)", 6 * px);
    this.strokeSegs(this.landSegs, "rgba(255,246,222,0.92)", 4 * px);
    this.strokeSegs(this.landSegs, d.color || T.gold, 2.2 * px);
    if (this.terr.add.size || this.terr.remove.size) {
      if (!this.outlineCache) this.outlineCache = new Map();
      if (!this.outlineCache.has("eff")) {
        const eff = this.effectiveLand();
        this.outlineCache.set("eff", M.outline([...eff].map(M.cparse), (x, z) => eff.has(M.ckey(x, z))));
      }
      g.setLineDash([6 * px, 4 * px]);
      this.strokeSegs(this.outlineCache.get("eff"), T.ok, 2 * px);
      g.setLineDash([]);
    }
    // strefy
    this.plan.zones.forEach((z, i) => {
      const zt = M.zoneType(z.type) || { color: "#999" };
      if (z.polygon.length < 2) return;
      g.beginPath();
      z.polygon.forEach(([x, q], j) => (j ? g.lineTo(C(x), C(q)) : g.moveTo(C(x), C(q))));
      g.closePath();
      g.fillStyle = hexA(zt.color, z.type === "nie_ruszac" ? 0.4 : 0.3);
      g.fill();
      if (z.type === "nie_ruszac") this.hatch(z.polygon, px);
      g.strokeStyle = zt.color;
      g.lineWidth = 2 * px;
      g.stroke();
      this.issueStroke("zones", i, px, () => {
        g.beginPath();
        z.polygon.forEach(([x, q], j) => (j ? g.lineTo(C(x), C(q)) : g.moveTo(C(x), C(q))));
        g.closePath();
      });
      if (s >= 0.7) {
        const c = centroid(z.polygon);
        this.text((zt.label || z.type) + (s >= 1.5 ? " #" + (i + 1) : ""), c[0], c[1], T, px, "#fff", 11);
      }
    });
    // drogi
    this.plan.roads.forEach((r, i) => {
      const rk = M.roadKind(r.kind) || M.ROAD_KINDS[1];
      const wdt = Math.max(2.5 * px, r.width || rk.width);
      const path = () => {
        g.beginPath();
        r.points.forEach(([x, z], j) => (j ? g.lineTo(C(x), C(z)) : g.moveTo(C(x), C(z))));
      };
      g.lineCap = "round";
      g.lineJoin = "round";
      path();
      g.strokeStyle = "rgba(40,28,12,0.75)";
      g.lineWidth = wdt + 2.5 * px;
      g.stroke();
      path();
      g.strokeStyle = rk.color;
      g.lineWidth = wdt;
      g.stroke();
      if (r.kind === "trakt") {
        path();
        g.setLineDash([3 * px, 4 * px]);
        g.strokeStyle = "rgba(60,40,20,0.6)";
        g.lineWidth = Math.max(px, wdt / 4);
        g.stroke();
        g.setLineDash([]);
      }
      this.issueStroke("roads", i, px, path, wdt);
    });
    // mury
    this.plan.walls.forEach((w, i) => {
      const segsW = this.wallSegments(w);
      const auto = w.mode && w.mode !== "points";
      g.lineCap = auto ? "butt" : "square";
      if (auto) {
        // podgląd muru automatycznego: przerywana linia (dopasuje się sama)
        this.strokeSegs(segsW, "rgba(20,20,20,0.75)", 4.5 * px);
        g.setLineDash([10 * px, 6 * px]);
        this.strokeSegs(segsW, "#F3E9D2", 2.5 * px);
        g.setLineDash([]);
      } else {
        this.strokeSegs(segsW, "rgba(20,20,20,0.8)", Math.max(4 * px, 2.6));
        this.strokeSegs(segsW, "#A7A196", Math.max(2.5 * px, 1.6));
      }
      if (!auto && s >= 1.4) {
        // blanki
        g.fillStyle = "#6E685E";
        for (const sg of segsW) {
          const len = Math.hypot(sg[2] - sg[0], sg[3] - sg[1]);
          for (let t = 2; t < len; t += 4) {
            const x = sg[0] + ((sg[2] - sg[0]) * t) / len;
            const z = sg[1] + ((sg[3] - sg[1]) * t) / len;
            g.fillRect(x - 0.6, z - 0.6, 1.2, 1.2);
          }
        }
      }
      for (const [gx, gz] of w.gates || []) {
        const r = Math.max(3, 6 * px); // brama: złoty kwadrat co najmniej 12 px
        g.fillStyle = "#2a1c0c";
        g.fillRect(C(gx) - r - 1.5 * px, C(gz) - r - 1.5 * px, 2 * r + 3 * px, 2 * r + 3 * px);
        g.fillStyle = "#F2C14E";
        g.fillRect(C(gx) - r, C(gz) - r, 2 * r, 2 * r);
        g.fillStyle = "#2a1c0c";
        g.fillRect(C(gx) - r * 0.35, C(gz) - r * 0.2, r * 0.7, r * 1.2);
        if (s >= 2) this.text("brama", C(gx), C(gz) - r - 8 * px, T, px, "#ffe9b0", 10);
      }
      if (auto && segsW.length && s >= 0.5) {
        const sg = segsW[0];
        this.text(w.mode === "follow-border" ? "mur wzdłuż granicy (dopasuje się sam)" : "mur wokół miasta (rośnie z miastem)", (sg[0] + sg[2]) / 2, (sg[1] + sg[3]) / 2 - 6 * px, T, px, "#f5efe0", 11);
      }
      this.issueStroke("walls", i, px, () => {
        g.beginPath();
        for (const sg of segsW) {
          g.moveTo(sg[0], sg[1]);
          g.lineTo(sg[2], sg[3]);
        }
      }, 4);
    });
    // istniejące budynki i place budowy
    if (this.layers.buildings) {
      for (const b of this.existing) {
        if (b.minX === undefined) continue;
        g.fillStyle = b.demolish ? "rgba(230,70,50,0.28)" : T.dark ? "rgba(255,250,235,0.18)" : "rgba(255,255,255,0.32)";
        g.fillRect(b.minX, b.minZ, b.maxX - b.minX + 1, b.maxZ - b.minZ + 1);
        g.strokeStyle = b.demolish ? "#ff7a5c" : "rgba(255,250,235,0.85)";
        g.lineWidth = 1.2 * px;
        g.setLineDash(b.demolish ? [3 * px, 2 * px] : []);
        g.strokeRect(b.minX, b.minZ, b.maxX - b.minX + 1, b.maxZ - b.minZ + 1);
        g.setLineDash([]);
        if (s >= 1.6) this.text(b.name + (b.demolish ? " (do rozbiórki)" : ""), (b.minX + b.maxX + 1) / 2, b.minZ - 4 * px, T, px, "#fff", 10);
      }
      for (const b of this.sites) {
        if (b.minX === undefined) continue;
        g.strokeStyle = T.warn;
        g.setLineDash([4 * px, 3 * px]);
        g.lineWidth = 1.5 * px;
        g.strokeRect(b.minX, b.minZ, b.maxX - b.minX + 1, b.maxZ - b.minZ + 1);
        g.setLineDash([]);
        if (s >= 1.6) this.text(`${b.name} ${b.percent ?? 0}%`, (b.minX + b.maxX + 1) / 2, b.minZ - 4 * px, T, px, "#ffe9b0", 10);
      }
    }
    // stolica
    if (d.capital) {
      g.beginPath();
      g.arc(d.capital.x, d.capital.z, Math.max(3, 6 * px), 0, Math.PI * 2);
      g.fillStyle = d.color || T.gold;
      g.fill();
      g.lineWidth = 2 * px;
      g.strokeStyle = "#fff";
      g.stroke();
      if (s >= 0.5) this.text("★ stolica", d.capital.x, d.capital.z - 10 * px, T, px, "#fff", 11);
    }
    // budynki planu
    this.plan.buildings.forEach((b, i) => this.drawBuilding(b, i, px, T));
    // szkic, duch budynku, prostokąty
    this.drawDraft(px, T);
    // zaznaczenie i najechanie
    if (this.hover && (!this.sel || this.hover.kind !== this.sel.kind || this.hover.index !== this.sel.index)) this.outlineSel(this.hover, px, "rgba(255,255,255,0.75)", false);
    if (this.sel) this.outlineSel(this.sel, px, T.gold, true);
  }

  visibleBlocks() {
    const a = this.toWorld(0, 0);
    const b = this.toWorld(this.canvas.clientWidth || 600, this.canvas.clientHeight || 400);
    return { minX: a.x, minZ: a.z, maxX: b.x, maxZ: b.z };
  }

  strokeSegs(segs, color, width) {
    const g = this.cx;
    g.strokeStyle = color;
    g.lineWidth = width;
    g.beginPath();
    for (const s of segs) {
      g.moveTo(s[0], s[1]);
      g.lineTo(s[2], s[3]);
    }
    g.stroke();
  }

  hatch(poly, px) {
    const g = this.cx;
    const xs = poly.map((p) => p[0]);
    const zs = poly.map((p) => p[1]);
    const x0 = Math.min(...xs);
    const z0 = Math.min(...zs);
    const x1 = Math.max(...xs) + 1;
    const z1 = Math.max(...zs) + 1;
    g.save();
    g.beginPath();
    poly.forEach(([x, z], j) => (j ? g.lineTo(C(x), C(z)) : g.moveTo(C(x), C(z))));
    g.closePath();
    g.clip();
    g.strokeStyle = "rgba(40,40,40,0.55)";
    g.lineWidth = 1.5 * px;
    const step = Math.max(4, 9 * px);
    g.beginPath();
    for (let t = x0 - (z1 - z0); t < x1; t += step) {
      g.moveTo(t, z0);
      g.lineTo(t + (z1 - z0), z1);
    }
    g.stroke();
    g.restore();
  }

  issueStroke(kind, index, px, path, extra = 0) {
    const is = this.issues.get(kind + ":" + index);
    if (!is) return;
    const g = this.cx;
    path();
    g.setLineDash([5 * px, 3 * px]);
    g.strokeStyle = is.level === "error" ? this.colors.danger : this.colors.warn;
    g.lineWidth = 3 * px + extra;
    g.stroke();
    g.setLineDash([]);
  }

  text(t, x, z, T, px, color = "#fff", size = 12) {
    const g = this.cx;
    g.save();
    g.translate(x, z);
    g.scale(px, px);
    g.font = `600 ${size}px system-ui, "Segoe UI", sans-serif`;
    g.textAlign = "center";
    g.textBaseline = "middle";
    g.lineJoin = "round";
    g.lineWidth = 3.5;
    g.strokeStyle = "rgba(0,0,0,0.78)";
    g.strokeText(t, 0, 0);
    g.fillStyle = color;
    g.fillText(t, 0, 0);
    g.restore();
  }

  labelAt(name, chunks, T, px, color) {
    if (!chunks.length) return;
    let sx = 0;
    let sz = 0;
    for (const [x, z] of chunks) {
      sx += x;
      sz += z;
    }
    this.text(name, (sx / chunks.length) * 16 + 8, (sz / chunks.length) * 16 + 8, T, px, color, 12);
  }

  drawBuilding(b, i, px, T, ghost = false, bad = false) {
    const g = this.cx;
    const info = this.types.get(b.type);
    const f = b.fp || M.footprint(M.typeSize(this.types, b.type), b.x, b.z, b.rotation || 0);
    const w = f.maxX - f.minX + 1;
    const h = f.maxZ - f.minZ + 1;
    const col = categoryColor(info && info.category);
    g.fillStyle = ghost ? (bad ? "rgba(230,60,50,0.45)" : hexA(col, 0.5)) : hexA(col, info && info.available === false ? 0.45 : 0.78);
    g.fillRect(f.minX, f.minZ, w, h);
    g.strokeStyle = ghost ? (bad ? "#ff6b5b" : "#fff") : "rgba(30,20,8,0.9)";
    g.lineWidth = (ghost ? 2 : 1.5) * px;
    g.setLineDash(ghost || (info && info.available === false) ? [4 * px, 3 * px] : []);
    g.strokeRect(f.minX, f.minZ, w, h);
    g.setLineDash([]);
    // front (drzwi): południe przy obrocie 0, potem zgodnie z ruchem wskazówek zegara
    const rot = b.rotation || 0;
    const cxm = f.minX + w / 2;
    const czm = f.minZ + h / 2;
    const fr = { 0: [cxm, f.maxZ + 1, 0, 1], 90: [f.minX, czm, -1, 0], 180: [cxm, f.minZ, 0, -1], 270: [f.maxX + 1, czm, 1, 0] }[rot] || [cxm, f.maxZ + 1, 0, 1];
    const tri = Math.max(1.2, Math.min(3, Math.min(w, h) / 3));
    const [fx, fz, nx, nz] = fr;
    g.beginPath();
    g.moveTo(fx + nx * tri, fz + nz * tri);
    g.lineTo(fx - nz * tri, fz + nx * tri);
    g.lineTo(fx + nz * tri, fz - nx * tri);
    g.closePath();
    g.fillStyle = ghost ? "#fff" : T.gold;
    g.fill();
    if (!ghost) this.issueStroke("buildings", i, px, () => {
      g.beginPath();
      g.rect(f.minX, f.minZ, w, h);
    });
    const s = this.view.s;
    if (s >= 1.1 || ghost) {
      const name = (info && info.name) || b.type;
      this.text(name, cxm, czm - (s >= 2.2 ? 5 * px : 0), T, px, "#fff", 11);
      if (s >= 2.2 && !ghost) this.text(`priorytet ${b.priority ?? M.DEFAULT_PRIORITY}`, cxm, czm + 8 * px, T, px, "#ffe9b0", 9);
      if (ghost) this.text(`${w}×${h} · obrót ${rot}° (R)`, cxm, f.maxZ + 1 + 10 * px, T, px, "#fff", 10);
    }
  }

  drawDraft(px, T) {
    const g = this.cx;
    const d = this.draft;
    if (d && d.points.length) {
      const pts = d.points.map(([x, z]) => [C(x), C(z)]);
      const cur = this.cursor ? [C(this.cursor.x), C(this.cursor.z)] : null;
      const col = d.kind === "zones" ? (M.zoneType(this.opts.zoneType) || {}).color : d.kind === "roads" ? (M.roadKind(this.opts.roadKind) || {}).color : "#C9C2B2";
      g.beginPath();
      pts.forEach(([x, z], j) => (j ? g.lineTo(x, z) : g.moveTo(x, z)));
      if (cur) g.lineTo(cur[0], cur[1]);
      if (d.kind === "zones" && cur) g.lineTo(pts[0][0], pts[0][1]);
      if (d.kind === "zones") {
        g.fillStyle = hexA(col, 0.22);
        g.fill();
      }
      g.setLineDash([6 * px, 4 * px]);
      g.strokeStyle = col;
      g.lineWidth = (d.kind === "roads" ? Math.max(2, this.opts.roadWidth) : d.kind === "walls" ? 2.5 : 2) * (d.kind === "roads" ? 1 : px);
      g.stroke();
      g.setLineDash([]);
      for (const [j, [x, z]] of pts.entries()) {
        g.beginPath();
        g.arc(x, z, (j === 0 ? 5 : 3.5) * px, 0, Math.PI * 2);
        g.fillStyle = j === 0 ? T.gold : "#fff";
        g.fill();
        g.strokeStyle = "#000";
        g.lineWidth = px;
        g.stroke();
      }
    }
    const level = this.levelSelection;
    if (level) {
      g.fillStyle = "rgba(72,180,210,.2)";
      g.strokeStyle = "#48b4d2"; g.lineWidth = 2 * px;
      g.fillRect(level.minX, level.minZ, level.maxX-level.minX+1, level.maxZ-level.minZ+1);
      g.strokeRect(level.minX, level.minZ, level.maxX-level.minX+1, level.maxZ-level.minZ+1);
    }
    const dr = this.drag;
    if (dr && dr.rect) {
      const r = norm(dr.rect);
      g.setLineDash([5 * px, 4 * px]);
      if (this.tool === "terrain") {
        const x0 = Math.floor(r.minX / 16) * 16;
        const z0 = Math.floor(r.minZ / 16) * 16;
        const x1 = (Math.floor(r.maxX / 16) + 1) * 16;
        const z1 = (Math.floor(r.maxZ / 16) + 1) * 16;
        g.fillStyle = dr.terrMode === "remove" ? "rgba(230,60,50,0.25)" : "rgba(76,200,90,0.25)";
        g.fillRect(x0, z0, x1 - x0, z1 - z0);
        g.strokeStyle = dr.terrMode === "remove" ? T.danger : T.ok;
        g.lineWidth = 2 * px;
        g.strokeRect(x0, z0, x1 - x0, z1 - z0);
      } else {
        const col = this.tool === "leveling" ? "#48b4d2" : (M.zoneType(this.opts.zoneType) || {}).color || "#fff";
        g.fillStyle = hexA(col, 0.25);
        g.fillRect(C(r.minX), C(r.minZ), r.maxX - r.minX, r.maxZ - r.minZ);
        g.strokeStyle = col;
        g.lineWidth = 2 * px;
        g.strokeRect(C(r.minX), C(r.minZ), r.maxX - r.minX, r.maxZ - r.minZ);
      }
      g.setLineDash([]);
    }
    const gh = this.ghost();
    if (gh) {
      const bad = !M.nearChunks(this.effectiveLand(), gh.x, gh.z, this.margin);
      this.drawBuilding(gh, -1, px, T, true, bad);
    }
    if (this.tool === "terrain" && this.cursor && !(dr && dr.rect)) {
      const [cx, cz] = M.chunkOf(this.cursor.x, this.cursor.z);
      const k = M.ckey(cx, cz);
      const blocked = this.blockedBy(k);
      const mode = this.space ? null : this.opts.terrMode;
      g.strokeStyle = blocked ? T.danger : mode === "remove" ? T.danger : T.ok;
      g.lineWidth = 2.5 * px;
      g.setLineDash(blocked ? [3 * px, 3 * px] : []);
      g.strokeRect(cx * 16, cz * 16, 16, 16);
      g.setLineDash([]);
    }
  }

  outlineSel(sel, px, color, handles) {
    const g = this.cx;
    const el = this.plan[sel.kind] && this.plan[sel.kind][sel.index];
    if (!el) return;
    g.strokeStyle = color;
    g.lineWidth = 2.5 * px;
    g.setLineDash(handles ? [] : [4 * px, 3 * px]);
    let pts = [];
    if (sel.kind === "buildings") {
      const f = M.footprint(M.typeSize(this.types, el.type), el.x, el.z, el.rotation || 0);
      g.strokeRect(f.minX - 1.5 * px, f.minZ - 1.5 * px, f.maxX - f.minX + 1 + 3 * px, f.maxZ - f.minZ + 1 + 3 * px);
    } else if (sel.kind === "zones") {
      pts = el.polygon;
      g.beginPath();
      pts.forEach(([x, z], j) => (j ? g.lineTo(C(x), C(z)) : g.moveTo(C(x), C(z))));
      g.closePath();
      g.stroke();
    } else if (sel.kind === "roads") {
      pts = el.points;
      g.beginPath();
      pts.forEach(([x, z], j) => (j ? g.lineTo(C(x), C(z)) : g.moveTo(C(x), C(z))));
      g.stroke();
    } else if (sel.kind === "walls") {
      if (!el.mode || el.mode === "points") pts = el.points;
      this.strokeSegs(this.wallSegments(el), color, 2.5 * px);
    }
    g.setLineDash([]);
    if (handles) {
      for (const [x, z] of pts) {
        g.fillStyle = "#fff";
        g.fillRect(C(x) - 4 * px, C(z) - 4 * px, 8 * px, 8 * px);
        g.strokeStyle = "#000";
        g.lineWidth = px;
        g.strokeRect(C(x) - 4 * px, C(z) - 4 * px, 8 * px, 8 * px);
      }
    }
  }
}

// ---------------------------------------------------------------- pomocnicze

function norm(r) {
  return { minX: Math.min(r.x0, r.x1), minZ: Math.min(r.z0, r.z1), maxX: Math.max(r.x0, r.x1), maxZ: Math.max(r.z0, r.z1) };
}
function inView(v, cx, cz) {
  return cx * 16 + 16 >= v.minX && cx * 16 <= v.maxX && cz * 16 + 16 >= v.minZ && cz * 16 <= v.maxZ;
}
function nearestSeg(x, z, s) {
  const [ax, az, bx, bz] = s;
  const dx = bx - ax;
  const dz = bz - az;
  const len = dx * dx + dz * dz;
  let t = len === 0 ? 0 : ((x - ax) * dx + (z - az) * dz) / len;
  t = Math.max(0, Math.min(1, t));
  const qx = ax + t * dx;
  const qz = az + t * dz;
  return { x: qx, z: qz, d: Math.hypot(qx - x, qz - z) };
}
function centroid(p) {
  let x = 0;
  let z = 0;
  for (const q of p) {
    x += q[0];
    z += q[1];
  }
  return [x / p.length + 0.5, z / p.length + 0.5];
}
function translate(el, kind, dx, dz) {
  const o = M.clone(el);
  const mv = (pts) => (pts || []).map(([x, z]) => [x + dx, z + dz]);
  if (kind === "buildings") {
    o.x += dx;
    o.z += dz;
  } else if (kind === "zones") o.polygon = mv(o.polygon);
  else if (kind === "roads") o.points = mv(o.points);
  else if (kind === "walls") {
    if (!o.mode || o.mode === "points") o.points = mv(o.points);
    o.gates = mv(o.gates);
  }
  return o;
}

/** Kolor kategorii budynku (Ludnosc: housing, economy, civic, …). */
export const CATEGORY_COLORS = {
  housing: "#D9A066",
  economy: "#C98B2B",
  civic: "#5B8DD6",
  military: "#C0392B",
  defense: "#8E8E8E",
  religious: "#9B6BD3",
  representative: "#E5C04A",
  food: "#B7C95A",
  farm: "#9CCB5A",
  infrastructure: "#7FA3A8",
  highrise: "#6C7A89",
  other: "#B0A48A",
};
export const categoryColor = (c) => CATEGORY_COLORS[c] || CATEGORY_COLORS.other;
