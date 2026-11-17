// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Testy jednostkowe modelu edytora planu (public/js/plan/model.js): walidacja jak w pluginie, import/eksport,
// geometria (obrysy, granica z odstępem, otoczka), chunki (wypełnianie, pierścień, koszt), historia cofnij/ponów.
import { test } from "node:test";
import assert from "node:assert/strict";
import * as M from "../public/js/plan/model.js";

const LAND = new Set();
for (let x = 0; x < 4; x++) for (let z = 0; z < 4; z++) LAND.add(M.ckey(x, z));
const TYPES = new Map([
  ["dom", { type: "dom", name: "Dom", available: true, missing: [], count: 3, max: null, size: { w: 11, d: 9 } }],
  ["palac", { type: "palac", name: "Pałac", available: false, missing: ["Poziom osady: Miasto"], count: 0, max: 1, size: { w: 30, d: 29 } }],
]);
const ctx = { land: LAND, margin: 32, types: TYPES };
const has = (list, kind, index, frag) => list.some((i) => i.kind === kind && i.index === index && i.text.includes(frag));

test("poprawny plan przechodzi; ostrzeżenia jak w pluginie", () => {
  const p = M.emptyPlan();
  p.zones.push({ type: "mieszkalna", polygon: [[2, 2], [30, 2], [30, 30], [2, 30]] });
  p.roads.push({ kind: "glowna", points: [[0, 10], [95, 10]], width: 5 });
  p.walls.push({ mode: "points", points: [[1, 1], [62, 1], [62, 62]], closed: true, gates: [[30, 1]] }, { mode: "follow-border", points: [], closed: true, gates: [], inset: 2 });
  p.buildings.push({ type: "palac", x: 20, z: 20, rotation: 0, priority: 1 }, { type: "dom", x: 24, z: 24, rotation: 90, priority: 50 });
  const r = M.validatePlan(M.toJson(p), ctx);
  assert.deepEqual(r.errors, []);
  assert.ok(has(r.warnings, "roads", 0, "poza ziemię kraju"));
  assert.ok(has(r.warnings, "buildings", 0, "niedostępny (brakuje: Poziom osady: Miasto)"));
  assert.ok(has(r.warnings, "buildings", 1, "nachodzi na budynek buildings[0]"));
});

test("błędy wskazują element (typy, punkty, margines, bramy, tryby murów)", () => {
  const r = M.validatePlan(
    {
      version: 1,
      zones: [{ type: "bagno", polygon: [[0, 0], [5, 0], [5, 5]] }, { type: "park", polygon: [[0, 0], [5, 5], [10, 10]] }],
      roads: [{ kind: "trakt", points: [[0, 0], [96, 0]] }],
      walls: [{ points: [[0, 0], [40, 0]], gates: [[20, 9]] }, { mode: "follow-town" }, { mode: "follow-town" }],
      buildings: [{ type: "wieza_x", x: 5, z: 5 }, { type: "dom", x: 5, z: 5, rotation: 45 }, { type: "dom", x: -40, z: 5 }],
    },
    ctx,
  );
  assert.ok(has(r.errors, "zones", 0, "nieznany typ strefy"));
  assert.ok(has(r.errors, "zones", 1, "zerową powierzchnię"));
  assert.ok(has(r.errors, "roads", 0, "poza ziemią kraju i marginesem 32"));
  assert.ok(has(r.errors, "walls", 0, "nie leży na murze"));
  assert.ok(has(r.errors, "walls", 2, "tylko jeden mur"));
  assert.ok(has(r.errors, "buildings", 0, "nieznany typ budynku"));
  assert.ok(has(r.errors, "buildings", 1, "obrót"));
  assert.ok(has(r.errors, "buildings", 2, "poza ziemią kraju"));
  assert.equal(r.errors.filter((e) => e.kind === "walls" && e.index === 1).length, 0, "domyślny odstęp muru wokół miasta");
});

test("limity liczby elementów", () => {
  const p = M.emptyPlan();
  for (let i = 0; i <= M.LIMITS.buildings; i++) p.buildings.push({ type: "dom", x: i % 60, z: 10, rotation: 0, priority: 50 });
  assert.ok(M.validatePlan(p, ctx).errors.some((e) => e.text.includes("za dużo elementów „buildings”")));
  const q = M.emptyPlan();
  q.roads.push({ kind: "trakt", width: 2, points: Array.from({ length: M.LIMITS.pointsPerElement + 1 }, (_, i) => [i % 60, Math.floor(i / 60)]) });
  assert.ok(has(M.validatePlan(q, ctx).errors, "roads", 0, "za dużo punktów"));
});

test("odwołania z komunikatu pluginu", () => {
  const refs = M.parseRefs("Plan ma błędy (2): buildings[4]: nieznany typ budynku „x”; zones[0]: strefa ma zerową powierzchnię");
  assert.deepEqual(refs.map((r) => [r.kind, r.index]), [["buildings", 4], ["zones", 0]]);
});

test("import: plan, obiekt {plan}, liczby zaokrąglane, złe pliki", () => {
  const { plan } = M.importPlan(JSON.stringify({ plan: { version: 1, buildings: [{ type: "DOM", x: "12", z: 5.4 }], walls: [{ mode: "follow-border" }] } }));
  assert.deepEqual(plan.buildings[0], { type: "dom", x: 12, z: 5, rotation: 0, priority: M.DEFAULT_PRIORITY });
  assert.equal(plan.walls[0].inset, 2);
  assert.equal(plan.options.keepExisting, true);
  assert.throws(() => M.importPlan("{zly json"), /poprawny plik JSON/);
  assert.throws(() => M.importPlan('{"version":2}'), /wersja/);
  assert.throws(() => M.importPlan("[1,2]"), /nie zawiera planu/);
  const round = M.importPlan(JSON.stringify(M.toJson(plan))).plan;
  assert.deepEqual(M.toJson(round), M.toJson(plan), "eksport → import bez zmian");
});

test("obrys budynku: środek i obrót jak BuildingCatalog.footprint", () => {
  assert.deepEqual(M.footprint({ w: 11, d: 9 }, 100, 200, 0), { minX: 95, minZ: 196, maxX: 105, maxZ: 204 });
  const r = M.footprint({ w: 11, d: 9 }, 100, 200, 90);
  assert.equal(r.maxX - r.minX + 1, 9);
  assert.equal(r.maxZ - r.minZ + 1, 11);
  assert.deepEqual(M.typeSize(new Map(), "nieznany"), M.DEFAULT_SIZE);
});

test("chunki: margines, wypełnianie, pierścień, koszt jak Kraje", () => {
  const one = new Set([M.ckey(0, 0)]);
  assert.ok(M.nearChunks(one, 47, 0, 32));
  assert.ok(!M.nearChunks(one, 48, 0, 32));
  assert.ok(M.nearChunks(one, -32, -32, 32));
  assert.ok(!M.nearChunks(one, -33, 0, 32));
  // pierścień 3×3 z dziurą w środku
  const ring = new Set();
  for (let x = 0; x < 3; x++) for (let z = 0; z < 3; z++) if (x !== 1 || z !== 1) ring.add(M.ckey(x, z));
  const filled = M.floodFill(M.ckey(1, 1), (k) => ring.has(k), { minCX: 0, minCZ: 0, maxCX: 2, maxCZ: 2 });
  assert.deepEqual([...filled], ["1,1"]);
  assert.equal(M.floodFill(M.ckey(5, 5), (k) => ring.has(k), { minCX: 0, minCZ: 0, maxCX: 2, maxCZ: 2 }), null, "obszar otwarty");
  assert.equal(M.ring(one, () => true).size, 8);
  assert.equal(M.ring(one, (k) => k !== "1,0").size, 7);
  assert.equal(M.claimCost(3, 2, 50, 5), 65 + 70);
  assert.equal(M.reachable(ring, "0,0").size, 8);
});

test("granica: odcinki scalone, odstęp do środka, obrys miasta", () => {
  const sq = new Set([M.ckey(0, 0), M.ckey(1, 0)]);
  const segs = M.outline([...sq].map(M.cparse), (x, z) => sq.has(M.ckey(x, z)));
  assert.equal(segs.length, 4, "prostokąt 2×1 chunk = 4 odcinki");
  assert.ok(segs.some((s) => s[0] === 0 && s[2] === 32 && s[1] === 0));
  const inset = M.insetOutline(sq, 2);
  const xs = inset.flatMap((s) => [s[0], s[2]]);
  assert.equal(Math.min(...xs), 2);
  assert.equal(Math.max(...xs), 30);
  const hull = M.townOutline([{ minX: 0, minZ: 0, maxX: 9, maxZ: 9 }, { minX: 20, minZ: 20, maxX: 29, maxZ: 29 }], 4);
  assert.ok(hull.length >= 4);
  assert.ok(hull.some(([x, z]) => x === -4 && z === -4));
  assert.ok(hull.some(([x, z]) => x === 34 && z === 34));
});

test("geometria: punkt w wielokącie, najbliższy punkt łamanej, samoprzecięcie", () => {
  const sq = [[0, 0], [10, 0], [10, 10], [0, 10]];
  assert.ok(M.pointInPolygon(sq, 5, 5));
  assert.ok(!M.pointInPolygon(sq, 15, 5));
  assert.equal(M.polygonArea(sq), 100);
  const n = M.nearestOnPolyline(sq, true, 5, 12);
  assert.deepEqual([n.x, n.z, n.d], [5, 10, 2]);
  assert.ok(M.selfIntersects([[0, 0], [10, 10], [10, 0], [0, 10]]));
  assert.ok(!M.selfIntersects(sq));
});

test("historia: cofnij, ponów, nowa zmiana czyści ponów", () => {
  const h = new M.History(3);
  h.push({ v: 1 });
  h.push({ v: 2 });
  assert.deepEqual(h.undo({ v: 3 }), { v: 2 });
  assert.deepEqual(h.redo({ v: 2 }), { v: 3 });
  h.undo({ v: 3 });
  h.push({ v: 2 });
  assert.ok(!h.canRedo);
  for (let i = 0; i < 10; i++) h.push({ v: 10 + i });
  assert.equal(h.past.length, 3, "limit kroków");
});
