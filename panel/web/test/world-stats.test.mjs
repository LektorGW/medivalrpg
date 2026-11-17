// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Testy czystych funkcji Statystyk Skarbu Świata (public/js/world-stats-model.js).
import test from "node:test";
import assert from "node:assert/strict";
import {
  csvCell, toCsv, csvFileName, csvTime, csvPercent, flowCsvRow, FLOW_CSV_HEADERS, exportPages,
  sankeyLayout, sankeyPath, flowMatrixTable, heatLevel,
  fillDaily, stackCategories, daysBetween, localMidnight,
  parseFlowFilters, serializeFlowFilters, flowParams, resolveRange, parseDay, parseTrailFilters, serializeTrailFilters, trailParams, pageInfo,
  typeLabel, categoryLabel, coinKindLabel, trailKindLabel, labelOf, worstLevel, fmtShare, cssToken,
  filterAccounts, typeCounts, isDenied, DAY_MS,
} from "../public/js/world-stats-model.js";

test("csvCell: formuły dostają apostrof, liczby zostają liczbami", () => {
  for (const s of ["=1+1", "+48 123", "-2", "@SUM(A1)", "\tx", "\rx"]) assert.equal(csvCell(s).replace(/^"|"$/g, "").startsWith("'"), true, s);
  assert.equal(csvCell(-120), "-120");
  assert.equal(csvCell(0), "0");
  assert.equal(csvCell(null), "");
  assert.equal(csvCell(undefined), "");
  assert.equal(csvCell(NaN), "");
  assert.equal(csvCell(true), "tak");
  assert.equal(csvCell("zwykły tekst"), "zwykły tekst");
});

test("csvCell: cytowanie separatora, cudzysłowu i nowych linii", () => {
  assert.equal(csvCell("a;b"), '"a;b"');
  assert.equal(csvCell('rzekł "hej"'), '"rzekł ""hej"""');
  assert.equal(csvCell("linia\nnowa"), '"linia\nnowa"');
  assert.equal(csvCell("a,b"), "a,b", "przecinek nie jest separatorem");
  assert.equal(csvCell("=A1;B1"), "\"'=A1;B1\"");
  assert.equal(csvCell("a,b", ","), '"a,b"');
});

test("toCsv: BOM, nagłówek, CRLF", () => {
  const s = toCsv(["Konto", "Kwota"], [["player:1", 5], ["=zly", -3]]);
  assert.equal(s.charCodeAt(0), 0xfeff);
  assert.equal(s, "﻿Konto;Kwota\r\nplayer:1;5\r\n'=zly;-3\r\n");
});

test("csvFileName i csvTime", () => {
  const t = new Date(2026, 9, 28, 14, 5, 9).getTime();
  assert.equal(csvFileName("Przepływy", t), "skarb-przeplywy-2026-10-28-1405.csv");
  assert.equal(csvFileName("../../zły plik", t), "skarb-zly-plik-2026-10-28-1405.csv");
  assert.equal(csvTime(t), "2026-10-28 14:05:09");
  assert.equal(csvTime(null), "");
  assert.equal(csvPercent(0.123456), "12,3456");
  assert.equal(csvPercent(undefined), "");
});

test("flowCsvRow: emisja bez nadawcy i powód z formułą", () => {
  const row = flowCsvRow({ seq: 7, time: 0, type: "DEPOSIT", from: null, to: "player:1", toLabel: "Bolko", toType: "players", amount: 500, category: "admin", plugin: "Panel", reason: "=HYPERLINK(1)", fromAfter: null, toAfter: 500 });
  assert.equal(row.length, FLOW_CSV_HEADERS.length);
  assert.equal(row[4], "(emisja)");
  assert.equal(row[8], "Konta graczy");
  assert.equal(row[10], "Korekty administracyjne");
  const line = toCsv(FLOW_CSV_HEADERS, [row]).split("\r\n")[1];
  assert.ok(line.includes(";'=HYPERLINK(1);"), line);
});

test("exportPages: strony po 1000, najwyżej 50 000 wierszy", () => {
  assert.deepEqual(exportPages(0), []);
  assert.deepEqual(exportPages(2500), [{ offset: 0, limit: 1000 }, { offset: 1000, limit: 1000 }, { offset: 2000, limit: 500 }]);
  const big = exportPages(123456);
  assert.equal(big.length, 50);
  assert.equal(big.at(-1).offset + big.at(-1).limit, 50000);
});

test("sankeyLayout: węzły proporcjonalne, wstęgi bez nakładania w węźle", () => {
  const m = [
    { from: "countries", to: "payments", amount: 600, count: 3 },
    { from: "players", to: "countries", amount: 300, count: 9 },
    { from: "countries", to: "countries", amount: 100, count: 1 },
    { from: "zero", to: "payments", amount: 0, count: 0 },
  ];
  const L = sankeyLayout(m, { height: 200, gap: 10 });
  assert.equal(L.total, 1000);
  assert.deepEqual(L.left.map((n) => n.key), ["countries", "players"]);
  assert.deepEqual(L.right.map((n) => n.key), ["payments", "countries"]);
  // wysokość wszystkich węzłów + odstępy = wysokość
  const leftH = L.left.reduce((s, n) => s + n.h, 0) + 10 * (L.left.length - 1);
  assert.ok(Math.abs(leftH - 200) < 1e-6);
  assert.ok(Math.abs(L.left[0].h / L.left[1].h - 700 / 300) < 1e-9);
  // wyjścia z „countries” sąsiadują i mieszczą się w węźle
  const outs = L.links.filter((l) => l.from === "countries").sort((a, b) => a.sy - b.sy);
  assert.equal(outs[0].sy, L.left[0].y);
  assert.ok(Math.abs(outs[1].sy - (outs[0].sy + outs[0].w)) < 1e-9);
  assert.ok(outs[1].sy + outs[1].w <= L.left[0].y + L.left[0].h + 1e-9);
  // największa wstęga pierwsza
  assert.equal(L.links[0].amount, 600);
  assert.match(sankeyPath(L.links[0], 10, 90), /^M10 [\d.]+C50 .*Z$/);
  assert.deepEqual(sankeyLayout([], {}).links, []);
});

test("flowMatrixTable i heatLevel", () => {
  const t = flowMatrixTable([{ from: "players", to: "countries", amount: 50, count: 2 }, { from: "reserve", to: "payments", amount: 200, count: 1 }]);
  assert.deepEqual(t.rows, ["reserve", "players"]);
  assert.deepEqual(t.cols, ["payments", "countries"]);
  assert.equal(t.get("players", "countries").amount, 50);
  assert.equal(t.get("players", "payments"), null);
  assert.equal(t.total, 250);
  assert.deepEqual(t.rowTotals, [200, 50]);
  assert.equal(heatLevel(0, 200), 0);
  assert.equal(heatLevel(200, 200), 5);
  assert.equal(heatLevel(1, 200), 1);
});

test("fillDaily: brakujące dni zerami, rosnąco", () => {
  const from = new Date(2026, 9, 20, 15).getTime();
  const to = new Date(2026, 9, 23, 9).getTime();
  const d = fillDaily([{ day: "2026-10-22", t: new Date(2026, 9, 22).getTime(), count: 3, volume: 30, in: 1, out: 2, byCategory: { tax: 30 } }], from, to);
  assert.deepEqual(d.map((x) => x.day), ["2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23"]);
  assert.equal(d[2].count, 3);
  assert.equal(d[0].volume, 0);
  assert.deepEqual(d[0].byCategory, {});
  // zmiana czasu (25 października 2026): kolejne północe nadal po jednej na dzień
  const days = daysBetween(new Date(2026, 9, 24, 12).getTime(), new Date(2026, 9, 27, 1).getTime());
  assert.equal(days.length, 4);
  for (const t of days) assert.equal(localMidnight(t), t);
});

test("stackCategories: najwyżej max kategorii + Pozostałe, suma się zgadza", () => {
  const days = [{ day: "a", t: 1, byCategory: { tax: 10, wages: 5, trade: 1, cash: 1, other: 1 } }, { day: "b", t: 2, byCategory: { tax: 1, trade: 7 } }];
  const cats = [{ key: "tax", volume: 11 }, { key: "trade", volume: 8 }, { key: "wages", volume: 5 }, { key: "cash", volume: 1 }, { key: "other", volume: 1 }];
  const s = stackCategories(days, cats, 3);
  assert.deepEqual(s.keys, ["tax", "trade", "_rest"]);
  assert.deepEqual(s.days[0].values, { tax: 10, trade: 1, _rest: 7 });
  assert.equal(s.days[0].total, 18);
  const all = stackCategories(days, cats, 7);
  assert.deepEqual(all.keys, ["tax", "wages", "trade", "cash", "other"], "kolejność stała według słownika");
});

test("filtry przepływów: parsowanie, serializacja i parametry RPC", () => {
  const now = new Date(2026, 9, 28, 12).getTime();
  const def = parseFlowFilters("", now);
  assert.equal(def.range, "7d");
  assert.equal(def.from, now - 7 * DAY_MS);
  assert.equal(serializeFlowFilters(def), "");
  const f = parseFlowFilters(new URLSearchParams("zakres=30d&kat=wages&typ=countries&konto=player%3Aab&q=%C5%BCo%C5%82d&min=500&str=3&ile=50"), now);
  assert.equal(f.range, "30d");
  assert.equal(f.category, "wages");
  assert.equal(f.account, "player:ab");
  assert.equal(f.query, "żołd");
  assert.equal(f.minAmount, 500);
  assert.equal(f.page, 3);
  assert.equal(f.pageSize, 50);
  assert.deepEqual(parseFlowFilters(serializeFlowFilters(f), now), f);
  assert.deepEqual(flowParams(f), { from: now - 30 * DAY_MS, to: now, offset: 100, limit: 50, category: "wages", type: "countries", account: "player:ab", query: "żołd", minAmount: 500 });
  assert.deepEqual(flowParams(f, { offset: 2000, limit: 1000 }).offset, 2000);
  // złe wartości → domyślne
  const bad = parseFlowFilters("kat=%3Cscript%3E&min=-5&str=0&ile=7&zakres=1y&q=" + "x".repeat(80), now);
  assert.equal(bad.category, "");
  assert.equal(bad.minAmount, 0);
  assert.equal(bad.page, 1);
  assert.equal(bad.pageSize, 200);
  assert.equal(bad.range, "7d");
  assert.equal(bad.query.length, 64);
});

test("zakres własny: od/do jako daty lokalne, do ≤ teraz", () => {
  const now = new Date(2026, 9, 28, 12).getTime();
  const r = resolveRange(new URLSearchParams("od=2026-10-01&do=2026-10-03"), now);
  assert.equal(r.range, "wlasny");
  assert.equal(r.from, new Date(2026, 9, 1).getTime());
  assert.equal(r.to, new Date(2026, 9, 4).getTime() - 1);
  const capped = resolveRange(new URLSearchParams("od=2026-10-27&do=2026-11-30"), now);
  assert.equal(capped.to, now);
  assert.equal(parseDay("2026-02-30"), null);
  assert.equal(parseDay("abc"), null);
  const f = parseFlowFilters("od=2026-10-01&do=2026-10-03&kat=tax", now);
  assert.equal(serializeFlowFilters(f), "od=2026-10-01&do=2026-10-03&kat=tax");
  // odwrócony zakres → preset domyślny
  assert.equal(resolveRange(new URLSearchParams("od=2026-10-10&do=2026-10-01"), now).range, "7d");
});

test("filtry ruchów monet", () => {
  const now = 10 * DAY_MS;
  const f = parseTrailFilters("zakres=24h&gracz=Bolko&rodzaj=DROP&q=skrzynia&str=2", now);
  assert.deepEqual(trailParams(f), { from: now - DAY_MS, to: now, offset: 200, limit: 200, player: "Bolko", kind: "DROP", query: "skrzynia" });
  assert.equal(serializeTrailFilters(f), "zakres=24h&gracz=Bolko&rodzaj=DROP&q=skrzynia&str=2");
  assert.equal(parseTrailFilters("rodzaj=ZLY", now).kind, "");
});

test("pageInfo", () => {
  assert.deepEqual(pageInfo(5230, 200, 200), { page: 2, pages: 27, from: 201, to: 400 });
  assert.deepEqual(pageInfo(0, 0, 200), { page: 1, pages: 1, from: 0, to: 0 });
});

test("etykiety z zapasem i poziom kontroli", () => {
  assert.equal(typeLabel("players", { players: "Gracze (serwer)" }), "Gracze (serwer)");
  assert.equal(typeLabel("players"), "Konta graczy");
  assert.equal(typeLabel("nowyTyp"), "nowyTyp");
  assert.equal(categoryLabel("wages", {}), "Żołd");
  assert.equal(coinKindLabel("GROUND"), "Przedmioty na ziemi");
  assert.equal(coinKindLabel("GROUND", "Ziemia"), "Ziemia");
  assert.equal(trailKindLabel("HOPPER_PICKUP"), "Lejek zebrał z ziemi");
  assert.equal(labelOf(null), "—");
  assert.equal(worstLevel([{ level: "OK" }, { level: "WARN" }]), "WARN");
  assert.equal(worstLevel([{ level: "WARN" }, { level: "ERROR" }]), "ERROR");
  assert.equal(worstLevel([]), "OK");
  assert.equal(fmtShare(0.789), "78,9%");
  assert.equal(fmtShare(0.0025), "0,25%");
  assert.equal(fmtShare(0.0000001), "<0,01%");
  assert.equal(fmtShare(0), "0%");
  assert.equal(cssToken("rail<Budgets>"), "railBudgets");
});

test("konta: filtr, wyszukiwanie bez polskich znaków, liczniki typów", () => {
  const list = [
    { account: "player:1", type: "players", owner: "Bolesław" },
    { account: "country:c-polanie", type: "countries", owner: "Królestwo Polan" },
    { account: "world:reserve", type: "reserve", owner: "Rezerwa" },
    { account: "player:2", type: "players", owner: null },
  ];
  assert.equal(filterAccounts(list, { query: "boles" }).length, 1);
  assert.equal(filterAccounts(list, { query: "KROLESTWO" }).length, 1);
  assert.equal(filterAccounts(list, { type: "players" }).length, 2);
  assert.equal(filterAccounts(list, { type: "players", query: "player:2" }).length, 1);
  assert.deepEqual(typeCounts(list), [{ key: "reserve", count: 1 }, { key: "players", count: 2 }, { key: "countries", count: 1 }]);
});

test("isDenied rozpoznaje odmowę Skarbca", () => {
  assert.equal(!!isDenied({ message: "Tylko WorldSteward ma dostęp do statystyk Skarbu Świata.", app: true, status: 200 }), true);
  assert.equal(!!isDenied({ message: "Serwer gry jest offline", status: 503 }), false);
});

test("nazwy towarów kupca", async () => {
  const { goodLabel } = await import("../public/js/world-stats-model.js");
  assert.equal(goodLabel("WHEAT"), "Pszenica");
  assert.equal(goodLabel("minecraft:IRON_NUGGET"), "Iron nugget");
  assert.equal(goodLabel(null), "—");
});

test("typ citizens i nowe kategorie w słownikach zapasowych", async () => {
  const M = await import("../public/js/world-stats-model.js");
  assert.equal(M.TYPE_ORDER.length, 13);
  assert.equal(M.TYPE_ORDER.indexOf("citizens"), M.TYPE_ORDER.indexOf("households") + 1);
  assert.equal(M.typeLabel("citizens"), "Oszczędności obywateli NPC");
  assert.equal(M.categoryLabel("labor"), "Robocizna obywateli NPC (udział z budów)");
  assert.equal(M.categoryLabel("citizens"), "Dochody i wydatki obywateli NPC");
});

test("prognoza puli gospodarstw", async () => {
  const { householdsForecast } = await import("../public/js/world-stats-model.js");
  assert.equal(householdsForecast({ avg7: { net: null } }).kind, "nodata");
  assert.equal(householdsForecast({}).kind, "nodata");
  const self = householdsForecast({ avg7: { net: 120 }, forecastDays: null, selfSustaining: true });
  assert.equal(self.kind, "self");
  assert.match(self.text, /samowystarczalna/);
  const d = householdsForecast({ avg7: { net: -500 }, forecastDays: 42.7, forecastDate: "2026-12-10", selfSustaining: false });
  assert.deepEqual([d.kind, d.days, d.date], ["days", 42, "2026-12-10"]);
  assert.equal(d.text, "Wystarczy na 42 dni — do 10.12.2026.");
  assert.equal(householdsForecast({ avg7: { net: -5 }, forecastDays: 1, forecastDate: "zła" }).text, "Wystarczy na 1 dzień.");
  assert.equal(householdsForecast({ avg7: { net: 0 }, forecastDays: null, selfSustaining: false }).kind, "nodata");
});
