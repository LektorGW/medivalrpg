// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Testy jednostkowe: wydajność serwera (public/js/health-model.js) — normalizacja admin.health, progi, opisy.
import { test } from "node:test";
import assert from "node:assert/strict";
import { normalizeHealth, level, warnLabel, fmtDuration, fmtDec, overallLevel, healthAvailability, warnCodesText, activePlugins, rangeOf } from "../public/js/health-model.js";

const sample = {
  enabled: true,
  intervalMs: 10000,
  npcScan: true,
  range: "6h",
  resolutionMs: 60000,
  from: 1000,
  to: 9000,
  points: [
    { t: 5000, tps: 17.5, tpsMin: 16, mspt: 48, msptMax: 61, players: 3, chunks: 900, entities: 3000, npcTotal: 40, npc: { ludnosc: 30, armia: 10 }, helpers: 4, warn: ["TPS", "MSPT"] },
    { t: 2000, tps: 20, mspt: 9.5, players: 2, chunks: 800, entities: 2800, npcTotal: 35, npc: { ludnosc: 30, armia: 5 }, helpers: 4, warn: [] },
    { t: "zły" },
    null,
  ],
  plugins: [
    { id: "ludnosc", name: "Ludność" },
    { id: "armia", name: "Armia" },
    { id: "skarbiec", name: "Skarbiec" },
  ],
  thresholds: { tps: 18, mspt: 45, npc: 2000, npcPlugin: { armia: 1000 } },
  warnings: [{ code: "TPS", level: "WARN", text: "Niski TPS" }, { nope: 1 }],
  episodes: [{ code: "MSPT", level: "CRITICAL", start: 1, end: null }],
};

test("brak pomiaru, wyłączony monitoring i dane archiwalne nie oznaczają poprawnego stanu", () => {
  assert.match(healthAvailability(normalizeHealth(null)), /pierwszy pomiar/);
  assert.match(healthAvailability(normalizeHealth({ enabled: false, current: { t: 1 } })), /wyłączone/);
  assert.match(healthAvailability(normalizeHealth({ current: { t: 1 } }), true), /nieznany/);
  assert.equal(healthAvailability(normalizeHealth({ current: { t: 1 } })), null);
});

test("próg krytyczny działa przy wyłączonym zwykłym progu", () => {
  assert.equal(level(12, 0, 15, true), "crit");
  assert.equal(level(80, 0, 50), "crit");
  assert.equal(level(300, 0, 200), "crit");
  assert.equal(level(6000, 0, 5000), "crit");
  assert.equal(level(50, 0, 50), "ok");
});

test("normalizeHealth: sortuje, odrzuca złe punkty, spłaszcza NPC per plugin", () => {
  const n = normalizeHealth(sample);
  assert.equal(n.points.length, 2);
  assert.deepEqual(n.points.map((p) => p.t), [2000, 5000]);
  assert.equal(n.points[0].tpsMin, 20, "brak tpsMin → tps");
  assert.equal(n.points[1].npc_armia, 10);
  assert.equal(n.points[1].npc_skarbiec, null, "brak wartości → null (przerwa w linii)");
  assert.deepEqual(n.points[1].warn, ["TPS", "MSPT"]);
  assert.equal(n.range.id, "6h");
  assert.equal(n.thresholds.tpsCritical, 15, "domyślny próg krytyczny");
  assert.deepEqual(n.thresholds.npcPlugin, { armia: 1000 });
  assert.equal(n.warnings.length, 1);
  assert.equal(n.episodes.length, 1);
});

test("normalizeHealth: pusta/nieznana odpowiedź i wyłączony skan NPC", () => {
  const e = normalizeHealth(null);
  assert.equal(e.points.length, 0);
  assert.equal(e.plugins.length, 7);
  assert.equal(e.range.id, "1h");
  assert.equal(e.current, null);
  const off = normalizeHealth({ npcScan: false, points: [{ t: 1, tps: 20, npcTotal: 0, npc: { ludnosc: 0 } }] });
  assert.equal(off.npcScan, false);
  assert.equal(off.points[0].npcTotal, null);
  assert.equal(off.points[0].npc_ludnosc, null);
});

test("level: TPS ostrzega poniżej progu, reszta powyżej; próg 0 wyłączony", () => {
  assert.equal(level(19, 18, 15, true), "ok");
  assert.equal(level(17, 18, 15, true), "warn");
  assert.equal(level(12, 18, 15, true), "crit");
  assert.equal(level(46, 45, 50), "warn");
  assert.equal(level(55, 45, 50), "crit");
  assert.equal(level(99999, 0, 0), "ok");
  assert.equal(level(null, 18, 15, true), "ok");
});

test("opisy ostrzeżeń, czasy i liczby po polsku", () => {
  assert.equal(warnLabel("TPS"), "TPS");
  assert.equal(warnLabel("MSPT"), "Czas ticka");
  assert.equal(warnLabel("NPC_ARMIA"), "NPC: Armia");
  assert.equal(warnLabel("NPC_NIEZNANY"), "NPC: nieznany");
  assert.equal(warnCodesText(["TPS", "ENTITIES"]), "TPS, Liczba encji");
  assert.equal(fmtDuration(0, 40_000), "40 s");
  assert.equal(fmtDuration(0, 12 * 60_000), "12 min");
  assert.equal(fmtDuration(0, 65 * 60_000), "1 h 5 min");
  assert.equal(fmtDuration(0, 120 * 60_000), "2 h");
  assert.equal(fmtDuration(1000, null, 61_000), "1 min", "trwa — do teraz");
  assert.equal(fmtDec(19.456, 1), "19,5");
  assert.equal(fmtDec(null), "—");
  assert.equal(overallLevel([]), "ok");
  assert.equal(overallLevel([{ level: "WARN" }, { level: "CRITICAL" }]), "crit");
  assert.equal(rangeOf("24h").window, "ostatnia doba");
});

test("activePlugins: tylko pluginy z NPC w zakresie albo teraz", () => {
  const n = normalizeHealth(sample);
  assert.deepEqual(activePlugins(n.points, n.plugins, null).map((p) => p.id), ["ludnosc", "armia"]);
  assert.deepEqual(activePlugins(n.points, n.plugins, { npc: { skarbiec: 2 } }).map((p) => p.id), ["ludnosc", "armia", "skarbiec"]);
});

test("transport: siedem pluginów, dodatkowe rodzaje i wykresy Kolei/Szlaków bez zmiany kontraktu", () => {
  const h = normalizeHealth({ current: { npc: { kolej: 2, szlaki: 3 }, kinds: { railBuilders: 1, trainDrivers: 1, freightDrivers: 2, freightAnimals: 1 } }, points: [{ t: 1, npc: { kolej: 2, szlaki: 3 }, npcTotal: 5 }] });
  assert.deepEqual(activePlugins(h.points, h.plugins, h.current).map(p => p.id), ["kolej", "szlaki"]);
  assert.equal(h.points[0].npc_kolej, 2);
  assert.equal(h.points[0].npc_szlaki, 3);
  assert.equal(h.current.kinds.freightAnimals, 1);
  assert.equal(warnLabel("NPC_KOLEJ"), "NPC: Kolej");
  assert.equal(warnLabel("NPC_SZLAKI"), "NPC: Szlaki");
});
