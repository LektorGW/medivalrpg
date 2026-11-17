// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Fałszywy plugin — edytor planu państwa NPC: podkład mapy (PNG generowany proceduralnie: trawa, lasy, wzgórza,
// rzeka i jezioro, pola, ścieżki i dachy budynków), plan (zapis z walidacją jak plugin, podgląd, czyszczenie)
// oraz teren (admin.npc.territory: cudze chunki, obszar chroniony, stolica, spójność, koszt jak w Kraje).
import { deflateSync } from "node:zlib";

const ck = (x, z) => x + "," + z;
const cp = (k) => k.split(",").map(Number);

/** Rozmiary szablonów (jak BuildingCatalog w pluginie). */
export const SIZES = { chata: [9, 8, "housing"], dom: [11, 9, "housing"], studnia: [5, 5, "infrastructure"], spichlerz: [10, 11, "food"], koszary: [13, 9, "military"], kuznia: [9, 9, "economy"], wieza: [7, 7, "defense"], targ: [13, 13, "civic"], kosciol: [9, 20, "religious"], palac: [30, 29, "representative"] };
export const footprint = (type, x, z, rot = 0) => {
  const [w0, d0] = SIZES[type] || [9, 9];
  const swap = rot === 90 || rot === 270;
  const w = swap ? d0 : w0;
  const d = swap ? w0 : d0;
  const minX = x - Math.floor(w / 2);
  const minZ = z - Math.floor(d / 2);
  return { minX, minZ, maxX: minX + w - 1, maxZ: minZ + d - 1 };
};

// ---------------------------------------------------------------- PNG

const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC[(c ^ b) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
export function encodePng(rgba, w, h) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

// ---------------------------------------------------------------- teren proceduralny

function hash(x, z, s) {
  let n = (x * 374761393 + z * 668265263 + s * 2147483647) | 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967295;
}
function noise(x, z, s) {
  const xi = Math.floor(x);
  const zi = Math.floor(z);
  const fx = x - xi;
  const fz = z - zi;
  const u = fx * fx * (3 - 2 * fx);
  const v = fz * fz * (3 - 2 * fz);
  const a = hash(xi, zi, s);
  const b = hash(xi + 1, zi, s);
  const c = hash(xi, zi + 1, s);
  const d = hash(xi + 1, zi + 1, s);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}
const fbm = (x, z, s) => noise(x / 64, z / 64, s) * 0.55 + noise(x / 27, z / 27, s + 1) * 0.3 + noise(x / 11, z / 11, s + 2) * 0.15;
const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];

/** Mapa jak vanilla: kolor bloku × cieniowanie (180/220/255) wg wysokości bloku na północ; woda przezroczysta na dnie. */
export function renderTerrain(originX, originZ, w, h, buildings, inRender) {
  const H = (x, z) => 63 + Math.round(fbm(x, z, 7) * 9 + Math.max(0, fbm(x + 400, z - 200, 3) - 0.6) * 75);
  const riverZ = (x) => -330 + 16 * Math.sin(x / 37) + 7 * Math.sin(x / 13);
  const lake = (x, z) => Math.hypot((x - 1512) / 1.3, z + 262) < 15 + 3 * noise(x / 6, z / 6, 9);
  const water = (x, z) => Math.abs(z - riverZ(x)) < 3.2 + noise(x / 9, 0, 4) * 2.5 || lake(x, z);
  const roofs = new Map();
  for (const b of buildings) for (let x = b.minX; x <= b.maxX; x++) for (let z = b.minZ; z <= b.maxZ; z++) roofs.set(ck(x, z), b);
  const out = Buffer.alloc(w * h * 4);
  const heights = new Float32Array(w * h);
  for (let pz = 0; pz < h; pz++) {
    for (let px = 0; px < w; px++) {
      const x = originX + px;
      const z = originZ + pz;
      const i = (pz * w + px) * 4;
      if (!inRender(Math.floor(x / 16), Math.floor(z / 16))) continue;
      let hgt = H(x, z);
      let col;
      let isWater = false;
      const nearWater = Math.abs(z - riverZ(x)) < 7.5 || Math.hypot((x - 1512) / 1.3, z + 262) < 21;
      const roof = roofs.get(ck(x, z));
      const path = Math.abs(z - (-296 + 0.15 * (x - 1448))) < 1.2 || Math.abs(x - (1448 + 0.1 * (z + 296))) < 1.1;
      if (water(x, z)) {
        isWater = true;
        const edge = lake(x, z) ? Math.max(0, 15 - Math.hypot((x - 1512) / 1.3, z + 262)) / 2 : 3.4 - Math.abs(z - riverZ(x));
        const depth = Math.max(1, Math.min(8, 1 + Math.round(edge * 1.6)));
        col = mix([220, 205, 150], [64, 64, 255], Math.min(0.92, 0.45 + depth * 0.06));
        hgt = 62;
      } else if (roof) {
        col = roof.type === "koszary" ? [112, 2, 0] : roof.type === "studnia" ? [112, 112, 112] : (roof.minX + roof.minZ) % 2 ? [102, 76, 51] : [129, 86, 49];
        hgt += 7;
        if (x === roof.minX || z === roof.minZ || x === roof.maxX || z === roof.maxZ) col = mix(col, [40, 25, 10], 0.35);
      } else if (path) col = [148, 108, 72];
      else if (nearWater) col = [247, 233, 163];
      else if (hgt > 84) col = [255, 252, 245];
      else if (hgt > 77) col = [112, 112, 112];
      else {
        const forest = fbm(x - 900, z + 300, 11) > 0.56 && Math.hypot(x - 1448, z + 296) > 38;
        const field = Math.hypot(x - 1395, z + 250) < 22 && !forest;
        if (field) col = (Math.floor(x / 2) + Math.floor(z / 9)) % 3 === 0 ? [151, 109, 77] : [118, 160, 54];
        else if (forest) col = hash(x, z, 5) > 0.7 ? [31, 75, 25] : [0, 124, 0];
        else col = hash(x, z, 6) > 0.97 ? [190, 180, 60] : [127, 178, 56];
      }
      heights[pz * w + px] = isWater ? -1 : hgt;
      out[i] = col[0];
      out[i + 1] = col[1];
      out[i + 2] = col[2];
      out[i + 3] = 255;
    }
  }
  // cieniowanie wysokości (blok na północ)
  for (let pz = 0; pz < h; pz++) {
    for (let px = 0; px < w; px++) {
      const i = (pz * w + px) * 4;
      if (!out[i + 3]) continue;
      const hh = heights[pz * w + px];
      if (hh < 0) continue;
      const hn = pz > 0 ? heights[(pz - 1) * w + px] : hh;
      const f = hn < 0 || hn === hh ? 220 / 255 : hh > hn ? 1 : 180 / 255;
      out[i] = Math.round(out[i] * f);
      out[i + 1] = Math.round(out[i + 1] * f);
      out[i + 2] = Math.round(out[i + 2] * f);
    }
  }
  return out;
}

// ---------------------------------------------------------------- świat

/** Ziemia Mazowsza (≈58 chunków wokół stolicy), enklawa Polan na wschodzie, obszar chroniony „spawn” na zachodzie. */
export function addPlan(W) {
  const land = new Set();
  for (let cx = 84; cx <= 96; cx++) {
    for (let cz = -25; cz <= -13; cz++) {
      const e = ((cx - 90) / 4.6) ** 2 + ((cz + 19) / 3.7) ** 2;
      if (e <= 1 + (hash(cx, cz, 1) - 0.5) * 0.35) land.add(ck(cx, cz));
    }
  }
  land.add(ck(90, -19));
  const polanie = new Set([ck(96, -20), ck(96, -19), ck(97, -20), ck(97, -19), ck(97, -18), ck(96, -18)]);
  for (const k of polanie) land.delete(k);
  W.claims = { "npc-mazowsze": land, "c-polanie": polanie };
  W.protectedAreas = [{ id: "spawn", label: "Spawn", minCX: 81, minCZ: -23, maxCX: 83, maxCZ: -16 }];
  W.C["npc-mazowsze"].chunks = land.size;
  W.C["npc-mazowsze"].areaBlocks = land.size * 256;
  W.planSupported = true;
  // Zycie: nastrój mieszkańców (country.get.mood)
  W.zycie = true;
  W.mood = {
    "c-polanie": { value: 62, label: "Spokój", factors: [{ key: "jedzenie", label: "Jedzenie", value: 9 }, { key: "bezpieczenstwo", label: "Bezpieczeństwo", value: 6 }, { key: "podatki", label: "Podatki", value: -7 }, { key: "wojna", label: "Wojna", value: -4 }], concerns: ["Wojna z Mazowszem niepokoi kupców", "Brakuje karczmy"], multiplier: 1.04, festival: false, unrest: false },
    "c-wislanie": { value: 83, label: "Zachwyt", factors: [{ key: "pokoj", label: "Pokój", value: 14 }, { key: "jedzenie", label: "Jedzenie", value: 10 }], concerns: [], multiplier: 1.21, festival: true, unrest: false },
    "npc-mazowsze": { value: 31, label: "Niezadowolenie", factors: [{ key: "wojna", label: "Wojna", value: -14 }, { key: "pobor", label: "Pobór do wojska", value: -9 }, { key: "jedzenie", label: "Jedzenie", value: 8 }, { key: "mieszkania", label: "Mieszkania", value: -3 }], concerns: ["Synowie idą na wojnę z Polanami", "Za mało łóżek dla nowych osadników", "Plotki o głodzie na zimę"], multiplier: 0.82, festival: false, unrest: true },
  };
  W.forced = [];
  W.mapRenders = 0;
}

function bounds(set) {
  let a = Infinity;
  let b = Infinity;
  let c = -Infinity;
  let d = -Infinity;
  for (const k of set) {
    const [x, z] = cp(k);
    a = Math.min(a, x);
    b = Math.min(b, z);
    c = Math.max(c, x);
    d = Math.max(d, z);
  }
  return { minCX: a, minCZ: b, maxCX: c, maxCZ: d };
}
const near = (land, x, z, margin) => {
  const cx = Math.floor(x / 16);
  const cz = Math.floor(z / 16);
  if (land.has(ck(cx, cz))) return true;
  const r = Math.ceil(margin / 16);
  for (let dx = -r; dx <= r; dx++)
    for (let dz = -r; dz <= r; dz++) {
      if (!land.has(ck(cx + dx, cz + dz))) continue;
      const mx = (cx + dx) * 16;
      const mz = (cz + dz) * 16;
      if (Math.max(Math.max(0, mx - x, x - mx - 15), Math.max(0, mz - z, z - mz - 15)) <= margin) return true;
    }
  return false;
};

export function planMethods(W, hp) {
  const { canNpc, err, okd, typeInfo } = hp;
  const npcOf = (u, id) => {
    const c = W.C[id];
    if (!c) throw new Error("Nie ma takiego kraju.");
    if (!c.npc && u.planCountry !== id) throw new Error("To nie jest państwo NPC.");
    if (!canNpc(u, id) && u.planCountry !== id) throw new Error("Brak uprawnień");
    if (!W.claims[id]) {
      const [cx, cz] = [Math.floor(c.capital.x / 16), Math.floor(c.capital.z / 16)];
      const s = new Set();
      for (let dx = -2; dx <= 2; dx++) for (let dz = -2; dz <= 1; dz++) s.add(ck(cx + dx, cz + dz));
      W.claims[id] = s;
    }
    return c;
  };
  const capitalKey = (c) => ck(Math.floor(c.capital.x / 16), Math.floor(c.capital.z / 16));
  const foreignOf = (id, k) => {
    for (const [oid, set] of Object.entries(W.claims)) if (oid !== id && set.has(k)) return W.C[oid];
    return null;
  };
  const protectedOf = (k) => {
    const [x, z] = cp(k);
    return W.protectedAreas.find((a) => x >= a.minCX && x <= a.maxCX && z >= a.minCZ && z <= a.maxCZ) || null;
  };
  const existing = (c) =>
    c.population.buildings
      .filter((b) => b.x !== undefined)
      .map((b) => ({ id: `${b.type}@${b.x},${b.z}`, type: b.type, name: b.name, function: b.function, x: b.x, z: b.z, ...footprint(b.type, b.x, b.z, 0), approx: true, demolish: /do rozbiórki/.test(b.function || "") }));
  const mapCache = new Map();

  const validate = (c, plan) => {
    const land = W.claims[c.id];
    const types = new Map(typeInfo({...c,v2:c.v2 || {tier:1}}).map((t) => [t.type, t]));
    const errors = [];
    const warnings = [];
    const E = (ref, t) => errors.push(ref ? `${ref}: ${t}` : t);
    const Wn = (ref, t) => warnings.push({ ref: ref ? { kind: ref.split("[")[0], index: Number(ref.match(/\d+/)[0]) } : null, text: t });
    if (!plan || typeof plan !== "object" || Array.isArray(plan)) return { errors: ["plan musi być obiektem JSON"], warnings };
    if (plan.version !== undefined && plan.version !== 1) E(null, "nieobsługiwana wersja planu (dozwolona: 1)");
    const lim = { zones: 200, roads: 200, walls: 50, buildings: 500 };
    for (const [k, max] of Object.entries(lim)) if (Array.isArray(plan[k]) && plan[k].length > max) E(null, `za dużo elementów „${k}”: ${plan[k].length} (najwyżej ${max})`);
    const pointsOk = (ref, pts, what) => {
      let out = false;
      for (const p of pts || []) {
        if (!Array.isArray(p) || !Number.isInteger(p[0]) || !Number.isInteger(p[1])) return E(ref, "punkt musi mieć postać [x, z] z liczb całkowitych"), false;
        if (!near(land, p[0], p[1], 32)) return E(ref, `punkt (${p[0]}, ${p[1]}) leży poza ziemią kraju i marginesem 32 bl.`), false;
        if (!land.has(ck(Math.floor(p[0] / 16), Math.floor(p[1] / 16)))) out = true;
      }
      if (out) Wn(ref, `${what} wychodzi poza ziemię kraju — ta część zostanie pominięta, dopóki kraj jej nie zajmie`);
      return true;
    };
    (plan.zones || []).forEach((z, i) => {
      if (!["mieszkalna", "centrum", "rzemieslnicza", "rolnicza", "las", "kopalnia", "port", "obronna", "park", "zabytki", "nie_ruszac"].includes(z.type)) return E(`zones[${i}]`, "nieznany typ strefy");
      if (!Array.isArray(z.polygon) || z.polygon.length < 3) return E(`zones[${i}]`, "strefa potrzebuje co najmniej 3 różnych punktów");
      pointsOk(`zones[${i}]`, z.polygon, "strefa");
    });
    (plan.roads || []).forEach((r, i) => {
      if (!["glowna", "boczna", "trakt"].includes(r.kind)) return E(`roads[${i}]`, "nieznany rodzaj drogi");
      if (!Number.isInteger(r.width) || r.width < 1 || r.width > 9) return E(`roads[${i}]`, "szerokość: liczba całkowita 1–9");
      if (!Array.isArray(r.points) || r.points.length < 2) return E(`roads[${i}]`, "droga potrzebuje co najmniej 2 różnych punktów");
      pointsOk(`roads[${i}]`, r.points, "droga");
    });
    (plan.walls || []).forEach((w, i) => {
      const mode = w.mode || "points";
      if (!["points", "follow-border", "follow-town"].includes(mode)) return E(`walls[${i}]`, `nieznany tryb muru „${mode}”`);
      if (mode === "points") {
        if (!Array.isArray(w.points) || w.points.length < (w.closed ? 3 : 2)) return E(`walls[${i}]`, "mur potrzebuje co najmniej 2 różnych punktów");
        pointsOk(`walls[${i}]`, w.points, "mur");
      } else if (!Number.isInteger(w.inset) || w.inset < 0 || w.inset > 7) return E(`walls[${i}]`, "odstęp (inset): liczba całkowita 0–7");
    });
    (plan.buildings || []).forEach((b, i) => {
      const t = types.get(b.type);
      if (!t) return E(`buildings[${i}]`, `nieznany typ budynku „${b.type}”`);
      if (![0, 90, 180, 270].includes(b.rotation)) return E(`buildings[${i}]`, "obrót musi wynosić 0, 90, 180 albo 270");
      if (!Number.isInteger(b.x) || !Number.isInteger(b.z)) return E(`buildings[${i}]`, "x: liczba całkowita");
      if (!near(land, b.x, b.z, 32)) return E(`buildings[${i}]`, `${t.name} (${b.x}, ${b.z}) leży poza ziemią kraju i marginesem 32 bl.`);
      if (!t.available) Wn(`buildings[${i}]`, `${t.name} — teraz niedostępny (brakuje: ${t.missing.join(", ")}); czeka w planie na spełnienie wymagań`);
    });
    // „Ludnosc”: ostrzeżenie o kolizji z istniejącym budynkiem (gdy nie wolno burzyć)
    if (!(plan.options && plan.options.allowDemolish)) {
      const ex = existing(c);
      (plan.buildings || []).forEach((b, i) => {
        const f = footprint(b.type, b.x, b.z, b.rotation);
        const hit = ex.find((e) => e.minX <= f.maxX && f.minX <= e.maxX && e.minZ <= f.maxZ && f.minZ <= e.maxZ);
        if (hit) warnings.push({ ref: { kind: "buildings", index: i }, text: `koliduje z istniejącym budynkiem (${hit.name}) — rozbiórki są wyłączone` });
      });
    }
    return { errors, warnings };
  };

  const methods = {
    "admin.npc.mapRender": (u, p) => {
      const c = npcOf(u, p.id);
      W.mapRenders++;
      const land = W.claims[c.id];
      const margin = Number.isInteger(p.margin) ? Math.max(0, Math.min(128, p.margin)) : 32;
      const r = Math.ceil(margin / 16);
      const render = new Set();
      for (const k of land) {
        const [x, z] = cp(k);
        for (let dx = -r; dx <= r; dx++) for (let dz = -r; dz <= r; dz++) render.add(ck(x + dx, z + dz));
      }
      const bb = bounds(render);
      const originX = bb.minCX * 16;
      const originZ = bb.minCZ * 16;
      const w = (bb.maxCX - bb.minCX + 1) * 16;
      const h = (bb.maxCZ - bb.minCZ + 1) * 16;
      const ex = existing(c);
      const key = `${c.id}:${margin}:${ex.length}:${originX},${originZ},${w},${h}`;
      if (!mapCache.has(key)) mapCache.set(key, encodePng(renderTerrain(originX, originZ, w, h, ex, (cx, cz) => render.has(ck(cx, cz))), w, h).toString("base64"));
      const others = [];
      for (const [oid, set] of Object.entries(W.claims)) {
        if (oid === c.id || !W.C[oid]) continue;
        const chunks = [...set].map(cp).filter(([x, z]) => x >= bb.minCX - 8 && x <= bb.maxCX + 8 && z >= bb.minCZ - 8 && z <= bb.maxCZ + 8);
        if (chunks.length) others.push({ id: oid, name: W.C[oid].name, color: W.C[oid].color, npc: W.C[oid].npc, chunks });
      }
      const [ccx, ccz] = cp(capitalKey(c));
      return okd({
        id: c.id,
        world: "world",
        png: mapCache.get(key),
        originX,
        originZ,
        width: w,
        height: h,
        scale: 1,
        margin,
        complete: true,
        loadedChunks: render.size,
        totalChunks: render.size,
        renderedAt: Date.now(),
        cached: false,
        name: c.name,
        color: c.color,
        chunks: [...land].map(cp),
        capital: { cx: ccx, cz: ccz, x: ccx * 16 + 8, z: ccz * 16 + 8 },
        buildings: ex,
        sites: c.population.sites.filter((s) => s.x !== undefined).map((s) => ({ type: s.type, name: s.name, percent: s.percent, x: s.x, z: s.z, ...footprint(s.type, s.x, s.z, 0), approx: true })),
        roads: [],
        others,
        protected: W.protectedAreas.filter((a) => a.maxCX >= bb.minCX - 8 && a.minCX <= bb.maxCX + 8),
        territory: { chunks: land.size, limit: c.ai?.settings["max-chunki"] || 200, costBase: 50, costPerChunk: 5, nextCost: 50 + 5 * land.size, treasury: c.treasury, treasuryText: "", maxChanges: 512 },
      });
    },
    "admin.npc.getPlan": (u, p) => {
      const c = npcOf(u, p.id);
      return okd({ id: c.id, available: true, supported: W.planSupported, plan: c.plan || null, margin: 32, limits: { zones: 200, roads: 200, walls: 50, buildings: 500, pointsPerElement: 256, points: 20000, gates: 32 } });
    },
    "admin.npc.setPlan": (u, p) => {
      const c = npcOf(u, p.id);
      if (!W.planSupported) return err("Zainstalowana wersja pluginu Ludnosc nie obsługuje planów państwa — zaktualizuj Ludnosc.");
      const v = validate(c, p.plan);
      if (v.errors.length) return err(`Plan ma błędy (${v.errors.length}): ${v.errors.slice(0, 8).join("; ")}`);
      c.plan = JSON.parse(JSON.stringify({ version: 1, ...p.plan }));
      W.lastPlan = c.plan;
      const n = (k) => (c.plan[k] || []).length;
      return okd({ ok: true, message: `Zapisano plan państwa: stref ${n("zones")}, dróg ${n("roads")}, murów ${n("walls")}, budynków ${n("buildings")}${v.warnings.length ? ` — ostrzeżenia: ${v.warnings.length}.` : "."}`, warnings: v.warnings, counts: { zones: n("zones"), roads: n("roads"), walls: n("walls"), buildings: n("buildings") }, plan: c.plan });
    },
    "admin.npc.clearPlan": (u, p) => {
      const c = npcOf(u, p.id);
      const had = !!c.plan;
      c.plan = null;
      return okd({ ok: true, cleared: had, message: had ? "Usunięto plan państwa — miasto rozbudowuje znów automatyczny planer." : "Państwo nie miało zapisanego planu." });
    },
    "admin.npc.planPreview": (u, p) => {
      const c = npcOf(u, p.id);
      if (!c.plan) return okd({ id: c.id, supported: W.planSupported, available: false, preview: null });
      const types = new Map(typeInfo({...c,v2:c.v2 || {tier:1}}).map((t) => [t.type, t]));
      const orders = [...(c.plan.buildings || [])].sort((a, b) => (a.priority ?? 50) - (b.priority ?? 50)).map((b) => ({ type: b.type, name: (types.get(b.type) || {}).name || b.type, x: b.x, z: b.z, rotation: b.rotation, priority: b.priority ?? 50, cost: (types.get(b.type) || {}).cost || 0, status: (types.get(b.type) || {}).available ? "zlecony" : "czeka", source: "plan" }));
      if (c.plan.options && c.plan.options.autoFill) orders.push({ type: "dom", name: "Dom", x: null, z: null, rotation: 0, priority: 100, cost: 800, status: "czeka", source: "auto" });
      for (const order of orders) {
        const feedback=W.planFeedback?.[c.id]?.[order.type+"@"+order.x+","+order.z];
        if (feedback) Object.assign(order,feedback);
      }
      const demolitions = [];
      if (c.plan.options && c.plan.options.allowDemolish) {
        for (const e of existing(c)) {
          const hitB = (c.plan.buildings || []).some((b) => {
            const f = footprint(b.type, b.x, b.z, b.rotation);
            return e.minX <= f.maxX && f.minX <= e.maxX && e.minZ <= f.maxZ && f.minZ <= e.maxZ;
          });
          if (hitB) demolitions.push({ id: e.id, type: e.type });
        }
      }
      const total = orders.reduce((s, o) => s + o.cost, 0);
      return okd({ id: c.id, supported: true, available: true, preview: { orders, demolitions, roadMetres: (c.plan.roads || []).reduce((s, r) => s + r.points.slice(1).reduce((a, p, i) => a + Math.round(Math.hypot(p[0] - r.points[i][0], p[1] - r.points[i][1])), 0), 0), wallMetres: (c.plan.walls || []).filter((w) => w.points && w.points.length).length * 300, totalCost: total, warnings: demolitions.length ? [] : ["Rozbiórki są wyłączone — budynki kolidujące z planem zostaną pominięte."] } });
    },
    "admin.npc.festival": (u, p) => {
      const c = npcOf(u, p.id);
      if (!W.zycie) return err("Plugin Zycie nie jest dostępny.");
      const m = W.mood[c.id];
      if (m && m.festival) return err(`W ${c.name} właśnie trwa festyn.`);
      if (m) {
        Object.assign(m, { festival: true, value: Math.min(100, m.value + 12), unrest: false });
        m.label = m.value >= 50 ? "Spokój" : m.value >= 35 ? "Niepokój" : "Niezadowolenie";
      }
      return okd({ ok: true, festival: true, message: `Ogłoszono festyn w ${c.name}.` });
    },
    "admin.npc.forceRedesign": (u, p) => {
      const c = npcOf(u, p.id);
      W.forced.push({ m: "redesign", free: !!p.free });
      return okd({ ok: true, orders: 14, free: !!p.free, message: `Zlecono przebudowę miasta: 14 zleceń${p.free ? " (darmowo)" : ""}.` });
    },
    "admin.npc.forceRoads": (u, p) => (npcOf(u, p.id), W.forced.push({ m: "roads" }), okd({ ok: true, message: "Zlecono przebudowę dróg w stylu epoki." })),
    "admin.npc.forceModernize": (u, p) => (npcOf(u, p.id), W.forced.push({ m: "modernize" }), okd({ ok: true, orders: 5, message: "Zlecono modernizację: 5 budynków." })),
    "admin.npc.territory": (u, p) => {
      const c = npcOf(u, p.id);
      const land = W.claims[c.id];
      const add = Array.isArray(p.add) ? p.add : [];
      const rem = Array.isArray(p.remove) ? p.remove : [];
      if (add.length + rem.length === 0) return err("Nie wybrano żadnych chunków do dodania ani usunięcia.");
      if (add.length + rem.length > 512) return err("Najwyżej 512 zmian terenu naraz.");
      for (const q of [...add, ...rem]) if (!Array.isArray(q) || q.length !== 2 || !Number.isInteger(q[0]) || !Number.isInteger(q[1])) return err("Nieprawidłowy parametr „add”: chunk musi mieć postać [cx, cz] z liczb całkowitych.");
      const skipped = [];
      const cand = [];
      const remSet = new Set(rem.map(([x, z]) => ck(x, z)));
      for (const [x, z] of add) {
        const k = ck(x, z);
        const f = foreignOf(c.id, k);
        const pa = protectedOf(k);
        if (land.has(k)) skipped.push({ cx: x, cz: z, code: "ALREADY_OWN", reason: "chunk już należy do kraju" });
        else if (f) skipped.push({ cx: x, cz: z, code: "FOREIGN", reason: "należy do kraju " + f.name });
        else if (pa) skipped.push({ cx: x, cz: z, code: "PROTECTED", reason: "obszar chroniony: " + pa.label });
        else cand.push(k);
      }
      const grown = new Set([...land, ...cand]);
      const reach = (set, start) => {
        const seen = new Set([start]);
        const q = [start];
        while (q.length) {
          const [x, z] = cp(q.pop());
          for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
            const n = ck(x + dx, z + dz);
            if (set.has(n) && !seen.has(n)) seen.add(n), q.push(n);
          }
        }
        return seen;
      };
      const cap = capitalKey(c);
      const r1 = reach(grown, cap);
      const claim = cand.filter((k) => (r1.has(k) ? true : (skipped.push({ cx: cp(k)[0], cz: cp(k)[1], code: "NOT_ADJACENT", reason: "nie przylega do terytorium kraju" }), false)));
      const after = new Set([...land, ...claim]);
      const release = [];
      for (const k of remSet) {
        const [x, z] = cp(k);
        if (!land.has(k)) skipped.push({ cx: x, cz: z, code: "NOT_OWN", reason: "chunk nie należy do kraju" });
        else if (k === cap) skipped.push({ cx: x, cz: z, code: "CAPITAL", reason: "nie można usunąć stolicy" });
        else {
          after.delete(k);
          if (reach(after, cap).size === after.size) release.push(k);
          else after.add(k), skipped.push({ cx: x, cz: z, code: "DISCONNECTS", reason: "usunięcie rozdzieliłoby terytorium kraju" });
        }
      }
      let cost = 0;
      for (let i = 0; i < claim.length; i++) cost += 50 + 5 * (land.size + i);
      if (p.free) cost = 0;
      if (cost > c.treasury) return err(`Za mało pieniędzy w skarbcu: zajęcie ${claim.length} chunków kosztuje ${cost} md, a jest ${c.treasury} md. Zaznacz „Darmowo” albo dodaj mniej.`);
      c.treasury -= cost;
      for (const k of claim) land.add(k);
      for (const k of release) land.delete(k);
      c.chunks = land.size;
      c.areaBlocks = land.size * 256;
      return okd({ ok: true, message: `Teren: +${claim.length} / −${release.length} chunków${skipped.length ? ", pominięto " + skipped.length : ""}${cost ? ", koszt " + cost + " md" : ""}.`, added: claim.length, removed: release.length, skipped, cost, costText: "", free: !!p.free, chunks: land.size });
    },
  };
  const authorize = (u, id, write = false) => {
    const c = W.C[id];
    if (!c) throw new Error("Nie ma takiego kraju.");
    const rank = c.members[u.uuid];
    if (!u.admin && !(c.npc && canNpc(u,id)) && !(write ? rank === "RULER" : ["RULER","ADVISOR"].includes(rank))) throw new Error("Brak uprawnień");
    return c;
  };
  for (const [name,legacy] of Object.entries({get:"getPlan",mapRender:"mapRender",preview:"planPreview",set:"setPlan",clear:"clearPlan"})) {
    methods["country.plan."+name]=(u,p)=> {
      authorize(u,p.id,["set","clear"].includes(name));
      return methods["admin.npc."+legacy]({...u,planCountry:p.id},p);
    };
  }
  methods["country.plan.buildings"]=(u,p)=> {
    const c=authorize(u,p.id);
    return okd({id:c.id,buildings:c.population.buildings,sites:c.population.sites,types:typeInfo({...c,v2:c.v2 || {tier:1}})});
  };
  const tokens=new Map();
  methods["country.plan.levelPreview"]=(u,p)=> {
    authorize(u,p.id);
    if (W.levelUnsupported) return err("Unknown method: country.plan.levelPreview");
    if (p.free && !u.admin) return err("Brak uprawnień");
    const columns=(p.maxX-p.minX+1)*(p.maxZ-p.minZ+1);
    const token="level-"+Date.now();
    tokens.set(token,{uuid:u.uuid,id:p.id,params:p});
    return okd({id:p.id,token,expiresAt:Date.now()+120000,free:!!p.free,costText:"120 md",preview:{available:true,world:"world",...p,targetY:p.targetY ?? 68,columns,changedColumns:columns-1,blocks:columns*3,cost:120,warnings:["Budynki w zaznaczeniu zostaną pominięte."]}});
  };
  methods["country.plan.level"]=(u,p)=> {
    authorize(u,p.id);
    const t=tokens.get(p.token);tokens.delete(p.token);
    if (!t || t.uuid!==u.uuid || t.id!==p.id) return err("Podgląd wygasł");
    W.lastLevel=t.params;
    return okd({ok:true,result:"queued",free:!!t.params.free,message:"Dodano wyrównanie do kolejki budowy."});
  };
  return methods;
}

