// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — model edytora planu państwa NPC (bez DOM, testowany w Node): słowniki stref, dróg i murów,
// walidacja planu (te same reguły i komunikaty co plugin — PlanValidator.java), geometria, chunki, historia cofnij/ponów.

export const VERSION = 1;
export const LIMITS = { zones: 200, roads: 200, walls: 50, buildings: 500, pointsPerElement: 256, points: 20000, gates: 32, roadWidth: 9, inset: 7, priority: 100 };
export const DEFAULT_PRIORITY = 50;
export const DEFAULT_MARGIN = 32;
export const GATE_TOLERANCE = 2.5;
const COORD_MAX = 29_999_000;

/** Typy stref: kolor wypełnienia (czytelny na mapie w obu motywach). */
export const ZONE_TYPES = [
  { id: "mieszkalna", label: "Mieszkalna", color: "#E8A33D", help: "tylko domy, kamienice i bloki" },
  { id: "centrum", label: "Centrum", color: "#F4D35E", help: "rynek, ratusz, kościół, pałac" },
  { id: "rzemieslnicza", label: "Rzemieślnicza", color: "#B5651D", help: "kuźnie, warsztaty, młyny" },
  { id: "rolnicza", label: "Rolnicza", color: "#C5D86D", help: "pola i spichlerze" },
  { id: "las", label: "Las", color: "#2F8F46", help: "gospodarka leśna, zakaz zabudowy" },
  { id: "kopalnia", label: "Kopalnia", color: "#8D99AE", help: "kopalnie i kamieniołomy" },
  { id: "port", label: "Port", color: "#3A86FF", help: "port, przystań, latarnia" },
  { id: "obronna", label: "Obronna", color: "#D62828", help: "wieże, koszary, mury" },
  { id: "park", label: "Park", color: "#6FD08C", help: "zieleń i ogrody" },
  { id: "zabytki", label: "Zabytki", color: "#B565D8", help: "nic nie jest wyburzane" },
  { id: "nie_ruszac", label: "Nie ruszać", color: "#9AA0A6", help: "planer nic tu nie robi" },
];
export const ROAD_KINDS = [
  { id: "glowna", label: "Główna", width: 5, color: "#E9D8A6" },
  { id: "boczna", label: "Boczna", width: 3, color: "#CBB88A" },
  { id: "trakt", label: "Trakt", width: 2, color: "#A98B5D" },
];
export const WALL_MODES = [
  { id: "points", label: "Ręczny", help: "Linia klik po kliku; zamknij ją, klikając pierwszy punkt." },
  { id: "follow-border", label: "Wzdłuż granicy", inset: 2, help: "Mur biegnie po granicy kraju (z odstępem) i przesuwa się razem z nią." },
  { id: "follow-town", label: "Wokół miasta", inset: 2, help: "Mur otacza zabudowę i rośnie razem z miastem." },
];
export const ROTATIONS = [0, 90, 180, 270];
export const zoneType = (id) => ZONE_TYPES.find((z) => z.id === id) || null;
export const roadKind = (id) => ROAD_KINDS.find((r) => r.id === id) || null;
export const wallMode = (id) => WALL_MODES.find((w) => w.id === id) || WALL_MODES[0];

/** Rozmiar domyślny, gdy plugin nie poda `size` typu (Ludnosc nie udostępnia wymiarów szablonu). */
export const DEFAULT_SIZE = { w: 9, d: 9 };
export const KIND_NAMES = { zones: "Strefa", roads: "Droga", walls: "Mur", buildings: "Budynek" };

export function emptyPlan() {
  return { version: VERSION, zones: [], roads: [], walls: [], buildings: [], options: { allowDemolish: false, keepExisting: true, autoFill: true } };
}

export const clone = (o) => JSON.parse(JSON.stringify(o));

// ---------------------------------------------------------------- chunki

export const ckey = (cx, cz) => cx + "," + cz;
export const cparse = (k) => k.split(",").map(Number);
export const chunkOf = (x, z) => [Math.floor(x / 16), Math.floor(z / 16)];

/** Czy blok leży na chunkach albo najwyżej `margin` bloków (Chebyshev) od nich — jak ChunkGeometry.nearChunks. */
export function nearChunks(set, x, z, margin) {
  const [cx, cz] = chunkOf(x, z);
  if (set.has(ckey(cx, cz))) return true;
  const r = Math.ceil(margin / 16);
  for (let dx = -r; dx <= r; dx++) {
    for (let dz = -r; dz <= r; dz++) {
      if (!set.has(ckey(cx + dx, cz + dz))) continue;
      const minX = (cx + dx) * 16;
      const minZ = (cz + dz) * 16;
      const ddx = Math.max(0, minX - x, x - (minX + 15));
      const ddz = Math.max(0, minZ - z, z - (minZ + 15));
      if (Math.max(ddx, ddz) <= margin) return true;
    }
  }
  return false;
}

const N4 = [[1, 0], [-1, 0], [0, 1], [0, -1]];

/** Chunki zbioru osiągalne od `start` przez wspólne krawędzie. */
export function reachable(set, start) {
  const seen = new Set();
  if (!set.has(start)) return seen;
  const q = [start];
  seen.add(start);
  while (q.length) {
    const [x, z] = cparse(q.pop());
    for (const [dx, dz] of N4) {
      const k = ckey(x + dx, z + dz);
      if (set.has(k) && !seen.has(k)) {
        seen.add(k);
        q.push(k);
      }
    }
  }
  return seen;
}

/**
 * Wypełnianie obszaru od chunka `start` do granicy (`isWall(k)` = ściana). Zwraca zbiór albo null, gdy obszar
 * „wycieka” poza `bounds` {minCX,minCZ,maxCX,maxCZ} lub przekracza `limit` (czyli nie jest zamknięty).
 */
export function floodFill(start, isWall, bounds, limit = 512) {
  if (isWall(start)) return new Set();
  const out = new Set([start]);
  const q = [start];
  while (q.length) {
    const [x, z] = cparse(q.pop());
    for (const [dx, dz] of N4) {
      const nx = x + dx;
      const nz = z + dz;
      const k = ckey(nx, nz);
      if (out.has(k) || isWall(k)) continue;
      if (nx < bounds.minCX || nx > bounds.maxCX || nz < bounds.minCZ || nz > bounds.maxCZ) return null;
      out.add(k);
      if (out.size > limit) return null;
      q.push(k);
    }
  }
  return out;
}

/** Pierścień chunków (8-sąsiedztwo) wokół zbioru, spełniających `ok(k)`. */
export function ring(set, ok) {
  const out = new Set();
  for (const k of set) {
    const [x, z] = cparse(k);
    for (let dx = -1; dx <= 1; dx++) {
      for (let dz = -1; dz <= 1; dz++) {
        const n = ckey(x + dx, z + dz);
        if (!set.has(n) && ok(n)) out.add(n);
      }
    }
  }
  return out;
}

/** Koszt zajęcia `n` chunków przy `count` posiadanych: base + perChunk × liczba (jak Kraje), chunk po chunku. */
export function claimCost(count, n, base, perChunk) {
  let s = 0;
  for (let i = 0; i < n; i++) s += base + perChunk * (count + i);
  return s;
}

/**
 * Granica zbioru komórek (siatka `cell` bloków) jako odcinki [x1,z1,x2,z2] w blokach, scalone w ciągi.
 * `has(cx, cz)` mówi, czy komórka należy do zbioru; `cells` to lista [cx, cz].
 */
export function outline(cells, has, cell = 16) {
  const h = new Map(); // poziome: z → [x...]
  const v = new Map(); // pionowe: x → [z...]
  const push = (m, a, b) => (m.has(a) ? m.get(a).push(b) : m.set(a, [b]));
  for (const [cx, cz] of cells) {
    if (!has(cx, cz - 1)) push(h, "n" + cz, cx);
    if (!has(cx, cz + 1)) push(h, "s" + (cz + 1), cx);
    if (!has(cx - 1, cz)) push(v, "w" + cx, cz);
    if (!has(cx + 1, cz)) push(v, "e" + (cx + 1), cz);
  }
  const segs = [];
  for (const [k, xs] of h) {
    const z = Number(k.slice(1)) * cell;
    xs.sort((a, b) => a - b);
    let s = xs[0];
    let p = xs[0];
    for (let i = 1; i <= xs.length; i++) {
      if (i < xs.length && xs[i] === p + 1) {
        p = xs[i];
        continue;
      }
      segs.push([s * cell, z, (p + 1) * cell, z]);
      if (i < xs.length) s = p = xs[i];
    }
  }
  for (const [k, zs] of v) {
    const x = Number(k.slice(1)) * cell;
    zs.sort((a, b) => a - b);
    let s = zs[0];
    let p = zs[0];
    for (let i = 1; i <= zs.length; i++) {
      if (i < zs.length && zs[i] === p + 1) {
        p = zs[i];
        continue;
      }
      segs.push([x, s * cell, x, (p + 1) * cell]);
      if (i < zs.length) s = p = zs[i];
    }
  }
  return segs;
}

/** Granica ziemi kraju przesunięta do środka o `inset` bloków (podgląd muru „wzdłuż granicy”). */
export function insetOutline(chunkSet, inset) {
  const cells = [...chunkSet].map(cparse);
  if (!cells.length) return [];
  if (inset <= 0) return outline(cells, (x, z) => chunkSet.has(ckey(x, z)));
  // Siatka blokowa obszaru: blok jest w środku, gdy wszystkie bloki w promieniu `inset` należą do kraju.
  let minCX = Infinity;
  let minCZ = Infinity;
  let maxCX = -Infinity;
  let maxCZ = -Infinity;
  for (const [x, z] of cells) {
    minCX = Math.min(minCX, x);
    minCZ = Math.min(minCZ, z);
    maxCX = Math.max(maxCX, x);
    maxCZ = Math.max(maxCZ, z);
  }
  const ox = minCX * 16;
  const oz = minCZ * 16;
  const w = (maxCX - minCX + 1) * 16;
  const hgt = (maxCZ - minCZ + 1) * 16;
  if (w * hgt > 4_000_000) return outline(cells, (x, z) => chunkSet.has(ckey(x, z)));
  // odległość (Chebyshev) do najbliższego bloku poza krajem — dwa przejścia (wiersze, kolumny) z oknem `inset`
  const inLand = new Uint8Array(w * hgt);
  for (const [cx, cz] of cells) {
    const bx = (cx - minCX) * 16;
    const bz = (cz - minCZ) * 16;
    for (let z = 0; z < 16; z++) inLand.fill(1, (bz + z) * w + bx, (bz + z) * w + bx + 16);
  }
  const erodeLine = (get, set, n) => {
    // komórka zostaje, gdy w oknie ±inset wszystkie są 1
    let run = 0;
    const tmp = new Uint8Array(n);
    const left = new Int32Array(n);
    for (let i = 0; i < n; i++) {
      run = get(i) ? run + 1 : 0;
      left[i] = run;
    }
    run = 0;
    for (let i = n - 1; i >= 0; i--) {
      run = get(i) ? run + 1 : 0;
      tmp[i] = get(i) && left[i] > inset && run > inset ? 1 : 0;
    }
    for (let i = 0; i < n; i++) set(i, tmp[i]);
  };
  for (let z = 0; z < hgt; z++) erodeLine((i) => inLand[z * w + i], (i, val) => (inLand[z * w + i] = val), w);
  for (let x = 0; x < w; x++) erodeLine((i) => inLand[i * w + x], (i, val) => (inLand[i * w + x] = val), hgt);
  const blocks = [];
  for (let z = 0; z < hgt; z++) for (let x = 0; x < w; x++) if (inLand[z * w + x]) blocks.push([x, z]);
  const segs = outline(blocks, (x, z) => x >= 0 && z >= 0 && x < w && z < hgt && inLand[z * w + x] === 1, 1);
  return segs.map(([a, b, c, d]) => [a + ox, b + oz, c + ox, d + oz]);
}

/** Otoczka wypukła punktów (monotone chain). */
export function convexHull(points) {
  const p = [...points].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  if (p.length < 3) return p;
  const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lower = [];
  for (const q of p) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], q) <= 0) lower.pop();
    lower.push(q);
  }
  const upper = [];
  for (let i = p.length - 1; i >= 0; i--) {
    const q = p[i];
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], q) <= 0) upper.pop();
    upper.push(q);
  }
  upper.pop();
  lower.pop();
  return lower.concat(upper);
}

/** Obrys „wokół miasta”: otoczka wypukła prostokątów budynków poszerzonych o `inset`. */
export function townOutline(rects, inset) {
  const pts = [];
  for (const r of rects) {
    pts.push([r.minX - inset, r.minZ - inset], [r.maxX + 1 + inset, r.minZ - inset], [r.maxX + 1 + inset, r.maxZ + 1 + inset], [r.minX - inset, r.maxZ + 1 + inset]);
  }
  return convexHull(pts);
}

// ---------------------------------------------------------------- geometria planu

export function polygonArea(p) {
  let s = 0;
  for (let i = 0; i < p.length; i++) {
    const a = p[i];
    const b = p[(i + 1) % p.length];
    s += a[0] * b[1] - b[0] * a[1];
  }
  return s / 2;
}

export function pointInPolygon(p, x, z) {
  let inside = false;
  for (let i = 0, j = p.length - 1; i < p.length; j = i++) {
    const [xi, zi] = p[i];
    const [xj, zj] = p[j];
    if (zi > z !== zj > z && x < ((xj - xi) * (z - zi)) / (zj - zi) + xi) inside = !inside;
  }
  return inside;
}

export function distToSegment(px, pz, ax, az, bx, bz) {
  const dx = bx - ax;
  const dz = bz - az;
  const len = dx * dx + dz * dz;
  let t = len === 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / len;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(ax + t * dx - px, az + t * dz - pz);
}

/** Najbliższy punkt łamanej: {x, z, d, seg}. */
export function nearestOnPolyline(p, closed, x, z) {
  let best = { x: NaN, z: NaN, d: Infinity, seg: -1 };
  const n = closed ? p.length : p.length - 1;
  for (let i = 0; i < n; i++) {
    const [ax, az] = p[i];
    const [bx, bz] = p[(i + 1) % p.length];
    const dx = bx - ax;
    const dz = bz - az;
    const len = dx * dx + dz * dz;
    let t = len === 0 ? 0 : ((x - ax) * dx + (z - az) * dz) / len;
    t = Math.max(0, Math.min(1, t));
    const qx = ax + t * dx;
    const qz = az + t * dz;
    const d = Math.hypot(qx - x, qz - z);
    if (d < best.d) best = { x: qx, z: qz, d, seg: i };
  }
  if (p.length === 1) best = { x: p[0][0], z: p[0][1], d: Math.hypot(p[0][0] - x, p[0][1] - z), seg: 0 };
  return best;
}

export const distToPolyline = (p, closed, x, z) => nearestOnPolyline(p, closed, x, z).d;

function orient(a, b, c) {
  return Math.sign((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]));
}
export function selfIntersects(p) {
  const n = p.length;
  for (let i = 0; i < n; i++) {
    for (let j = i + 2; j < n; j++) {
      if (i === 0 && j === n - 1) continue;
      const a = p[i];
      const b = p[(i + 1) % n];
      const c = p[j];
      const d = p[(j + 1) % n];
      if (orient(a, b, c) * orient(a, b, d) < 0 && orient(c, d, a) * orient(c, d, b) < 0) return true;
    }
  }
  return false;
}

/** Rozmiar typu: z danych pluginu (`size {w,d}`), inaczej domyślny 9×9. */
export function typeSize(types, type) {
  const t = types && types.get ? types.get(type) : null;
  const s = t && t.size && Number.isFinite(t.size.w) && Number.isFinite(t.size.d) ? t.size : DEFAULT_SIZE;
  return { w: Math.max(1, Math.round(s.w)), d: Math.max(1, Math.round(s.d)) };
}

/** Obrys budynku o środku (x, z) — jak BuildingCatalog.footprint: szerokość wzdłuż X przy obrocie 0/180. */
export function footprint(size, x, z, rotation) {
  const swap = rotation === 90 || rotation === 270;
  const w = swap ? size.d : size.w;
  const d = swap ? size.w : size.d;
  const minX = x - Math.floor(w / 2);
  const minZ = z - Math.floor(d / 2);
  return { minX, minZ, maxX: minX + w - 1, maxZ: minZ + d - 1 };
}

// ---------------------------------------------------------------- walidacja (lustro PlanValidator.java)

const isInt = (v) => typeof v === "number" && Number.isInteger(v) && Math.abs(v) <= 2147483647;

/**
 * Sprawdza plan jak plugin. ctx: {land:Set<ckey>, margin, types:Map<type,{name,available,missing,count,max,size}>}.
 * → {errors:[{kind,index,text}], warnings:[...]} (kind null = cały plan).
 */
export function validatePlan(plan, ctx) {
  const errors = [];
  const warnings = [];
  const E = (kind, index, text) => errors.push({ kind, index, text });
  const W = (kind, index, text) => warnings.push({ kind, index, text });
  const land = ctx.land || new Set();
  const margin = ctx.margin ?? DEFAULT_MARGIN;
  const types = ctx.types || new Map();
  if (!plan || typeof plan !== "object" || Array.isArray(plan)) {
    E(null, -1, "plan musi być obiektem JSON");
    return { errors, warnings };
  }
  if (plan.version !== undefined && plan.version !== null && plan.version !== VERSION) E(null, -1, "nieobsługiwana wersja planu (dozwolona: 1)");
  let total = 0;
  const list = (name, max) => {
    const a = plan[name];
    if (a === undefined || a === null) return [];
    if (!Array.isArray(a)) {
      E(null, -1, `„${name}” musi być listą`);
      return [];
    }
    if (a.length > max) {
      E(null, -1, `za dużo elementów „${name}”: ${a.length} (najwyżej ${max})`);
      return a.slice(0, max);
    }
    return a;
  };
  const points = (arr, min, max, kind, i, what, field) => {
    if (arr === undefined || arr === null) {
      if (min > 0) E(kind, i, `brak pola „${field}”`);
      return min > 0 ? null : [];
    }
    if (!Array.isArray(arr)) return E(kind, i, `„${field}” musi być listą punktów [[x,z],…]`), null;
    if (arr.length > max) return E(kind, i, `za dużo punktów (${arr.length}, najwyżej ${max})`), null;
    const out = [];
    for (const p of arr) {
      if (!Array.isArray(p) || p.length !== 2 || !isInt(p[0]) || !isInt(p[1])) return E(kind, i, "punkt musi mieć postać [x, z] z liczb całkowitych"), null;
      if (Math.abs(p[0]) > COORD_MAX || Math.abs(p[1]) > COORD_MAX) return E(kind, i, "współrzędne poza światem"), null;
      const l = out[out.length - 1];
      if (l && l[0] === p[0] && l[1] === p[1]) continue;
      out.push(p);
    }
    if (out.length < min) return E(kind, i, `${what} potrzebuje co najmniej ${min} różnych punktów`), null;
    total += out.length;
    return out;
  };
  const onLand = (pts, kind, i, what) => {
    let outside = false;
    for (const [x, z] of pts) {
      if (!nearChunks(land, x, z, margin)) return E(kind, i, `punkt (${x}, ${z}) leży poza ziemią kraju i marginesem ${margin} bl.`), false;
      if (!land.has(ckey(...chunkOf(x, z)))) outside = true;
    }
    if (outside) W(kind, i, `${what} wychodzi poza ziemię kraju — ta część zostanie pominięta, dopóki kraj jej nie zajmie`);
    return true;
  };
  const obj = (e, kind, i) => (e && typeof e === "object" && !Array.isArray(e) ? e : (E(kind, i, "element musi być obiektem JSON"), null));
  const str = (v) => (typeof v === "string" ? v.trim().toLowerCase() : null);
  const intField = (o, name, min, max, def, kind, i, label) => {
    const v = o[name];
    if (v === undefined || v === null) {
      if (def === null) E(kind, i, `brak pola „${name}”`);
      return def;
    }
    if (!isInt(v) || v < min || v > max) return E(kind, i, `${label}: liczba całkowita ${min}–${max}`), null;
    return v;
  };

  const zones = [];
  list("zones", LIMITS.zones).forEach((e, i) => {
    const o = obj(e, "zones", i);
    if (!o) return;
    const type = str(o.type);
    if (!type || !zoneType(type)) return E("zones", i, "nieznany typ strefy" + (type ? ` „${type}”` : ""));
    let pts = points(o.polygon, 3, LIMITS.pointsPerElement, "zones", i, "strefa", "polygon");
    if (!pts) return;
    if (pts.length > 3 && pts[0][0] === pts[pts.length - 1][0] && pts[0][1] === pts[pts.length - 1][1]) pts = pts.slice(0, -1);
    if (Math.abs(polygonArea(pts)) < 1) return E("zones", i, "strefa ma zerową powierzchnię");
    if (!onLand(pts, "zones", i, "strefa")) return;
    if (selfIntersects(pts)) W("zones", i, "brzeg strefy przecina sam siebie — planer może ją odczytać inaczej, niż wygląda");
    zones.push({ type, pts });
  });
  list("roads", LIMITS.roads).forEach((e, i) => {
    const o = obj(e, "roads", i);
    if (!o) return;
    const kind = str(o.kind);
    const rk = kind && roadKind(kind);
    if (!rk) return E("roads", i, "nieznany rodzaj drogi (dozwolone: glowna, boczna, trakt)");
    const width = intField(o, "width", 1, LIMITS.roadWidth, rk.width, "roads", i, "szerokość");
    const pts = points(o.points, 2, LIMITS.pointsPerElement, "roads", i, "droga", "points");
    if (width === null || !pts) return;
    onLand(pts, "roads", i, "droga");
  });
  const auto = {};
  list("walls", LIMITS.walls).forEach((e, i) => {
    const o = obj(e, "walls", i);
    if (!o) return;
    const mode = str(o.mode) || "points";
    if (!WALL_MODES.some((m) => m.id === mode)) return E("walls", i, `nieznany tryb muru „${mode}”`);
    const gates = points(o.gates, 0, LIMITS.gates, "walls", i, "mur", "gates");
    if (!gates) return;
    if (mode === "points") {
      if (o.closed !== undefined && o.closed !== null && typeof o.closed !== "boolean") return E("walls", i, "„closed” musi być true albo false");
      const closed = !!o.closed;
      const pts = points(o.points, closed ? 3 : 2, LIMITS.pointsPerElement, "walls", i, closed ? "zamknięty mur" : "mur", "points");
      if (!pts || !onLand(pts, "walls", i, "mur")) return;
      for (const [gx, gz] of gates) if (distToPolyline(pts, closed, gx, gz) > GATE_TOLERANCE) return E("walls", i, `brama (${gx}, ${gz}) nie leży na murze`);
    } else {
      if (auto[mode] !== undefined) return E("walls", i, `plan może mieć tylko jeden mur „${mode === "follow-border" ? "wzdłuż granicy" : "wokół miasta"}” (jest już walls[${auto[mode]}])`);
      auto[mode] = i;
      if (Array.isArray(o.points) && o.points.length) W("walls", i, "mur automatyczny nie używa punktów — pominięto je");
      if (intField(o, "inset", 0, LIMITS.inset, wallMode(mode).inset, "walls", i, "odstęp (inset)") === null) return;
      for (const [gx, gz] of gates) if (!nearChunks(land, gx, gz, margin)) return E("walls", i, `brama (${gx}, ${gz}) leży poza ziemią kraju i marginesem ${margin} bl.`);
    }
  });
  const fps = [];
  const perType = {};
  list("buildings", LIMITS.buildings).forEach((e, i) => {
    fps.push(null);
    const o = obj(e, "buildings", i);
    if (!o) return;
    const type = str(o.type);
    const info = type ? types.get(type) : null;
    if (!type || !/^[a-z0-9_-]{1,32}$/.test(type) || (types.size && !info)) return E("buildings", i, "nieznany typ budynku" + (type ? ` „${type}”` : ""));
    const x = intField(o, "x", -COORD_MAX, COORD_MAX, null, "buildings", i, "x");
    const z = intField(o, "z", -COORD_MAX, COORD_MAX, null, "buildings", i, "z");
    const rot = intField(o, "rotation", 0, 270, 0, "buildings", i, "obrót");
    const pr = intField(o, "priority", 1, LIMITS.priority, DEFAULT_PRIORITY, "buildings", i, "priorytet");
    if (x === null || z === null || rot === null || pr === null) return;
    if (!ROTATIONS.includes(rot)) return E("buildings", i, "obrót musi wynosić 0, 90, 180 albo 270");
    const name = (info && info.name) || type;
    if (!nearChunks(land, x, z, margin)) return E("buildings", i, `${name} (${x}, ${z}) leży poza ziemią kraju i marginesem ${margin} bl.`);
    total++;
    const fp = footprint(typeSize(types, type), x, z, rot);
    let onAll = true;
    for (let cx = Math.floor(fp.minX / 16); cx <= Math.floor(fp.maxX / 16); cx++) for (let cz = Math.floor(fp.minZ / 16); cz <= Math.floor(fp.maxZ / 16); cz++) if (!land.has(ckey(cx, cz))) onAll = false;
    if (!onAll) W("buildings", i, `${name} wychodzi poza ziemię kraju — powstanie, gdy kraj zajmie ten teren`);
    if (info && info.available === false) W("buildings", i, `${name} — teraz niedostępny${info.missing && info.missing.length ? ` (brakuje: ${info.missing.join(", ")})` : ""}; czeka w planie na spełnienie wymagań`);
    perType[type] = (perType[type] || 0) + 1;
    if (info && info.max > 0 && (info.count || 0) + perType[type] > info.max) W("buildings", i, `${name} — przekroczony limit ${info.max} na kraj (zbudowane: ${info.count || 0})`);
    const j = fps.findIndex((o2) => o2 && o2.minX <= fp.maxX && fp.minX <= o2.maxX && o2.minZ <= fp.maxZ && fp.minZ <= o2.maxZ);
    if (j >= 0) W("buildings", i, `${name} nachodzi na budynek buildings[${j}]`);
    fps[i] = fp;
    for (let k = zones.length - 1; k >= 0; k--) {
      if (pointInPolygon(zones[k].pts, x + 0.5, z + 0.5)) {
        const zt = zones[k].type;
        if (zt === "nie_ruszac" || zt === "las" || zt === "zabytki") W("buildings", i, `${name} stoi w strefie „${zt.replace("_", " ")}” — planer może go nie postawić`);
        break;
      }
    }
  });
  if (total > LIMITS.points) E(null, -1, `za dużo punktów w planie (${total}, najwyżej ${LIMITS.points})`);
  const op = plan.options;
  if (op !== undefined && op !== null) {
    if (typeof op !== "object" || Array.isArray(op)) E(null, -1, "„options” musi być obiektem");
    else for (const k of ["allowDemolish", "keepExisting", "autoFill"]) if (op[k] !== undefined && op[k] !== null && typeof op[k] !== "boolean") E(null, -1, `„${k}” musi być true albo false`);
  }
  return { errors, warnings };
}

/** Odwołania do elementów w komunikacie błędu pluginu („Plan ma błędy (2): buildings[4]: …; zones[0]: …”). */
export function parseRefs(message) {
  const out = [];
  const re = /(zones|roads|walls|buildings)\[(\d+)\]:\s*([^;]*)/g;
  let m;
  while ((m = re.exec(String(message || "")))) out.push({ kind: m[1], index: Number(m[2]), text: m[3].trim() });
  return out;
}

/**
 * Import pliku JSON: plan v1 (albo obiekt {plan:…} z eksportu). Liczby zaokrąglane, nieznane pola pomijane.
 * → {plan, notes:[...]} albo wyjątek z polskim komunikatem.
 */
export function importPlan(text) {
  let j;
  try {
    j = JSON.parse(text);
  } catch {
    throw new Error("To nie jest poprawny plik JSON.");
  }
  if (j && typeof j === "object" && j.plan && typeof j.plan === "object") j = j.plan;
  if (!j || typeof j !== "object" || Array.isArray(j)) throw new Error("Plik nie zawiera planu (oczekiwano obiektu JSON).");
  if (j.version !== undefined && j.version !== VERSION) throw new Error(`Nieobsługiwana wersja planu: ${j.version} (edytor zna wersję ${VERSION}).`);
  const notes = [];
  const num = (v) => (typeof v === "number" && Number.isFinite(v) ? Math.round(v) : typeof v === "string" && /^-?\d+(\.\d+)?$/.test(v.trim()) ? Math.round(Number(v)) : v);
  const pts = (a) => (Array.isArray(a) ? a.filter((p) => Array.isArray(p) && p.length >= 2).map((p) => [num(p[0]), num(p[1])]) : []);
  const arr = (a, name) => {
    if (a === undefined || a === null) return [];
    if (!Array.isArray(a)) throw new Error(`Pole „${name}” musi być listą.`);
    return a.filter((x) => x && typeof x === "object");
  };
  const plan = emptyPlan();
  plan.zones = arr(j.zones, "zones").map((z) => ({ type: String(z.type || "").toLowerCase(), polygon: pts(z.polygon) }));
  plan.roads = arr(j.roads, "roads").map((r) => {
    const kind = String(r.kind || "boczna").toLowerCase();
    return { kind, points: pts(r.points), width: r.width === undefined ? (roadKind(kind) || ROAD_KINDS[1]).width : num(r.width) };
  });
  plan.walls = arr(j.walls, "walls").map((w) => {
    const mode = String(w.mode || "points").toLowerCase();
    const o = { mode, points: mode === "points" ? pts(w.points) : [], closed: mode === "points" ? !!w.closed : true, gates: pts(w.gates) };
    if (mode !== "points") o.inset = w.inset === undefined ? wallMode(mode).inset ?? 2 : num(w.inset);
    return o;
  });
  plan.buildings = arr(j.buildings, "buildings").map((b) => ({ type: String(b.type || "").toLowerCase(), x: num(b.x), z: num(b.z), rotation: b.rotation === undefined ? 0 : num(b.rotation), priority: b.priority === undefined ? DEFAULT_PRIORITY : num(b.priority) }));
  const o = j.options && typeof j.options === "object" ? j.options : {};
  plan.options = { allowDemolish: o.allowDemolish === true, keepExisting: o.keepExisting !== false, autoFill: o.autoFill !== false };
  const n = plan.zones.length + plan.roads.length + plan.walls.length + plan.buildings.length;
  if (!n) notes.push("Plan jest pusty.");
  return { plan, notes };
}

/** Plan z serwera (getPlan) albo pusty — uzupełnia brakujące pola, żeby edytor nie musiał tego sprawdzać. */
export function fromServer(p) {
  if (!p || typeof p !== "object") return emptyPlan();
  try {
    return importPlan(JSON.stringify(p)).plan;
  } catch {
    return emptyPlan();
  }
}

/** Plan do wysłania / eksportu (kanoniczny, bez pól edytora). */
export function toJson(plan) {
  const p = clone(plan);
  p.version = VERSION;
  p.walls = p.walls.map((w) => {
    const o = { mode: w.mode || "points", points: w.mode && w.mode !== "points" ? [] : w.points, closed: w.mode && w.mode !== "points" ? true : !!w.closed, gates: w.gates || [] };
    if (o.mode !== "points") o.inset = w.inset;
    return o;
  });
  return p;
}

export const planCount = (p) => p.zones.length + p.roads.length + p.walls.length + p.buildings.length;

// ---------------------------------------------------------------- historia

/** Cofnij / ponów na migawkach stanu (JSON), najwyżej `max` kroków. */
export class History {
  constructor(max = 100) {
    this.max = max;
    this.past = [];
    this.future = [];
  }
  /** Zapamiętuje stan PRZED zmianą. */
  push(state) {
    const s = JSON.stringify(state);
    if (this.past[this.past.length - 1] === s) return;
    this.past.push(s);
    if (this.past.length > this.max) this.past.shift();
    this.future = [];
  }
  undo(current) {
    if (!this.past.length) return null;
    this.future.push(JSON.stringify(current));
    return JSON.parse(this.past.pop());
  }
  redo(current) {
    if (!this.future.length) return null;
    this.past.push(JSON.stringify(current));
    return JSON.parse(this.future.pop());
  }
  get canUndo() {
    return this.past.length > 0;
  }
  get canRedo() {
    return this.future.length > 0;
  }
}
