// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import test from "node:test";
import assert from "node:assert/strict";
import { mapModel, normalizeRailway, placeText } from "../public/js/railway-map.js";

test("railway map keeps bends, shared coordinate scale and separate worlds", () => {
  const network = { nodes: [{ id: "a", world: "world", x: 0, y: 70, z: 0 }, { id: "b", world: "world", x: 100, y: 71, z: 100 }, { id: "c", world: "nether", x: 0, y: 20, z: 0 }],
    segments: [{ id: "ab", from: "a", to: "b", world: "world", points: [[0, 70, 0], [0, 70, 100], [100, 71, 100]] }, { id: "bad", from: "a", to: "c", world: "world", points: [] }] };
  const m = mapModel(network, "world");
  assert.deepEqual(m.nodes.map(n => n.id), ["a", "b"]);
  assert.deepEqual(m.segments.map(s => s.id), ["ab"]);
  assert.equal(m.segments[0].points.length, 3);
  const origin = m.project(0, 0), east = m.project(100, 0), south = m.project(0, 100);
  assert.equal(east[0] - origin[0], south[1] - origin[1]);
  assert.deepEqual(mapModel(network, "nether").nodes.map(n => n.id), ["c"]);
});

test("empty, single station and invalid points never produce nonfinite SVG coordinates or invented rails", () => {
  for (const network of [{}, { nodes: [{ id: "a", world: "w", x: -12, y: 70, z: 5 }] }]) {
    assert.ok(mapModel(network, "w").project(0, 0).every(Number.isFinite));
  }
  const n = [{ id: "a", world: "w", x: 0, y: 70, z: 0 }, { id: "b", world: "w", x: 10, y: 70, z: 0 }];
  const m = mapModel({ nodes: n, segments: [{ from: "a", to: "b", world: "w", points: [[NaN, 1, 2], [0, 70, 0], null] }, { from: "a", to: "b", world: "w" }] }, "w");
  assert.deepEqual(m.segments.map(s => s.points.length), [1, 0]);
});

test("normalizeRailway: brakujące albo błędne listy z wtyczki kolei nie wysypują widoku", () => {
  const d = normalizeRailway({ company: "Kolej", balance: 5, stations: null, requests: "x", works: [{ id: "w" }], lines: [{ id: "l", stops: null }, null], trains: undefined });
  for (const k of ["stations", "requests", "works", "lines", "trains", "crews", "permits"]) assert.ok(Array.isArray(d[k]), k);
  assert.deepEqual(d.works, [{ id: "w" }]);
  assert.deepEqual(d.lines, [{ id: "l", stops: [] }], "puste wpisy pominięte, stops zawsze tablicą");
  assert.equal(d.company, "Kolej");
  assert.equal(d.balance, 5);
  assert.deepEqual(normalizeRailway(null).stations, []);
  assert.equal(placeText({ kind: "station", world: "w", position: [1, 2, 3] }), "w: 1, 2, 3");
  assert.equal(placeText({ kind: "station", world: "w" }), "w: —");
  assert.equal(placeText({ kind: "rail", world: "w", from: "a", to: "b" }), "w: a → b");
});
