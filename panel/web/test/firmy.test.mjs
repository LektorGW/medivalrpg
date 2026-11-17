// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Firmy graczy (#/firmy): czyste funkcje (public/js/firmy-model.js), rejestracja tras i menu w app.js, wpięcie karty
// kredytów w Skarb Świata, kategoria „Firmy” w statystykach oraz brak kopii firmy.* w sessionStorage i w cache Workera.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import * as F from "../public/js/firmy-model.js";
import { CATEGORY_ORDER, CATEGORY_LABELS, categoryLabel } from "../public/js/world-stats-model.js";

const src = (p) => readFile(new URL(p, import.meta.url), "utf8");
const METHODS = ["firmy.list", "firmy.get", "firmy.admin", "firmy.contracts", "firmy.disputes", "firmy.loans", "firmy.world.loans",
  "firmy.admin.freeze", "firmy.dispute.resolve", "firmy.world.loanDecide", "firmy.world.loanSweep"];

test("stany firm po polsku i odcienie znaczników", () => {
  assert.equal(F.stateLabel("ACTIVE"), "czynna");
  assert.equal(F.stateLabel("DORMANT"), "uśpiona");
  assert.equal(F.stateLabel("INSOLVENT"), "niewypłacalna");
  assert.equal(F.stateLabel("LIQUIDATED"), "zlikwidowana");
  assert.equal(F.stateLabel("active"), "czynna");
  assert.equal(F.stateLabel(null), "—");
  assert.equal(F.stateLabel("NOWY"), "nowy");
  assert.deepEqual(["ACTIVE", "DORMANT", "INSOLVENT", "LIQUIDATED", "X"].map(F.stateTone), ["ok", "info", "warn", "err", "info"]);
});

test("sortowanie rankingu: tylko znane przełączniki", () => {
  assert.deepEqual(F.SORTS.map((s) => s.id), ["obrot", "majatek", "ludzie", "kontrakty"]);
  assert.equal(F.normSort("majatek"), "majatek");
  assert.equal(F.normSort("kontrakty"), "kontrakty");
  assert.equal(F.normSort("<script>"), "obrot");
  assert.equal(F.normSort(null), "obrot");
});

test("herb: kolor tylko #RRGGBB, nazwa koloru sztandaru po polsku", () => {
  assert.equal(F.herbColor("#A1B2C3"), "#a1b2c3");
  assert.equal(F.herbColor("a1b2c3"), "#a1b2c3");
  assert.equal(F.herbColor(0xff0000), "#ff0000");
  assert.equal(F.herbColor("red; background:url(x)"), "#8a7350");
  assert.equal(F.herbColor("url(javascript:alert(1))"), "#8a7350");
  assert.equal(F.bannerName({ base: "red", patterns: [] }), "czerwony");
  assert.equal(F.bannerName({ base: "LIGHT_BLUE" }), "jasnoniebieski");
  assert.equal(F.bannerName("minecraft:black_banner"), "czarny");
  assert.equal(F.bannerName(null), "biały");
  assert.equal(F.bannerName({ base: "rainbow_thing" }), "rainbow thing");
});

test("ranking: kolejność z serwera, znacznik „twoja”, saldo tylko dla właściciela", () => {
  const data = {
    sort: "obrot", owner: false,
    firms: [
      { id: "b", rank: 1, name: "Kuźnia Bolka", type: "warsztat", typeName: "Warsztat", seatName: "Polania", color: "#112233", banner: { base: "red" }, members: 3, income30: 12345, contracts: 2, state: "ACTIVE", mine: true },
      { id: "a", rank: 2, name: "Hanza", typeName: "Dom handlowy", seatName: "Lubeka", color: "zły", members: 9, income30: 500, contracts: 7, state: "INSOLVENT", mine: false },
    ],
  };
  const rows = F.rankingRows(data);
  assert.deepEqual(rows.map((r) => r.id), ["b", "a"]);
  assert.equal(rows[0].mine, true);
  assert.equal(rows[0].stateLabel, "czynna");
  assert.equal(rows[0].bannerName, "czerwony");
  assert.equal(rows[0].balance, null, "bez salda dla zwykłego gracza");
  assert.equal(rows[1].color, "#8a7350");
  assert.equal(rows[1].stateLabel, "niewypłacalna");
  assert.equal(rows[1].bannerName, "biały");
  const owner = F.rankingRows({ owner: true, firms: [{ id: "x", name: "X", balance: 777, balanceText: "7 zł 7 sr 7 md" }] });
  assert.equal(owner[0].balance, 777);
  assert.equal(owner[0].rank, 1);
  assert.deepEqual(F.rankingRows(null), []);
  assert.deepEqual(F.rankingRows({ firms: "nie-lista" }), []);
});

test("trasy #/firmy, #/firmy/zarzad i #/firmy/<id>", () => {
  assert.deepEqual(F.firmyRoute(undefined), { kind: "list" });
  assert.deepEqual(F.firmyRoute("zarzad"), { kind: "admin" });
  assert.deepEqual(F.firmyRoute("kuznia-1"), { kind: "firm", id: "kuznia-1" });
  assert.equal(F.firmHref("a b/c"), "#/firmy/a%20b%2Fc");
});

test("etap 2: rozpoznanie brakującej metody i odmowy", () => {
  assert.equal(F.isStageMissing(new Error("Nieznana metoda firm: firmy.contracts")), true);
  assert.equal(F.isStageMissing(new Error("Nieznana metoda: firmy.loans")), true);
  assert.equal(F.isStageMissing(new Error("Zainstalowane Firmy nie obsługują jeszcze panelu WWW.")), true);
  assert.equal(F.isStageMissing(new Error("Firmy są obecnie niedostępne.")), true);
  assert.equal(F.isStageMissing(new Error("Brak dostępu.")), false);
  assert.equal(F.isDenied(new Error("Brak dostępu.")), true);
  assert.equal(F.isDenied(new Error("Nie ma takiej firmy.")), false);
});

test("księga: stronicowanie po stronie przeglądarki", () => {
  const rows = Array.from({ length: 45 }, (_, i) => ({ time: i }));
  let p = F.pageOf(rows, 1, 20);
  assert.equal(p.pages, 3);
  assert.equal(p.rows.length, 20);
  assert.equal(p.from, 1);
  assert.equal(p.to, 20);
  p = F.pageOf(rows, 3, 20);
  assert.equal(p.rows.length, 5);
  assert.equal(p.to, 45);
  assert.equal(F.pageOf(rows, 99, 20).page, 3, "za duża strona → ostatnia");
  assert.equal(F.pageOf(rows, -1, 20).page, 1);
  assert.deepEqual(F.pageOf(null, 1), { rows: [], page: 1, pages: 1, total: 0, from: 0, to: 0 });
});

test("przychód dzienny: 30 dni do dziś, luki jako zero", () => {
  const now = new Date(2026, 9, 29, 15, 0).getTime();
  const s = F.incomeSeries({ "2026-10-29": 500, "2026-10-01": 70, "2026-09-29": 999, "2026-10-15": -5, "zły": 1 }, 30, now);
  assert.equal(s.length, 30);
  assert.equal(s[29].day, "2026-10-29");
  assert.equal(s[29].values.income, 500);
  assert.equal(s[0].day, "2026-09-30");
  assert.equal(s.find((d) => d.day === "2026-10-01").values.income, 70);
  assert.equal(s.find((d) => d.day === "2026-10-15").values.income, 0, "ujemne nie wchodzą na wykres przychodu");
  assert.equal(s.reduce((a, d) => a + d.values.income, 0), 570);
  assert.ok(s.every((d, i) => i === 0 || d.t > s[i - 1].t));
});

test("formatowanie: udział, sklep, księga, grupy IP i parametry zamrożenia", () => {
  assert.equal(F.fmtPercent(25.5), "25,5%");
  assert.equal(F.fmtPercent(100), "100%");
  assert.equal(F.fmtPercent(null), "—");
  assert.equal(F.shopType("SELL"), "sprzedaż");
  assert.equal(F.shopType("buy"), "skup");
  assert.equal(F.journalKind("pensja"), "Pensja");
  assert.equal(F.journalKind("nowe"), "nowe");
  assert.deepEqual(F.addressGroups([["A", "B"], ["C"], [], "x", ["D", "", "E"]]), [["A", "B"], ["D", "E"]]);
  assert.deepEqual(F.freezeParams("f1", true, "  oszustwo "), { id: "f1", frozen: "true", reason: "oszustwo" });
  assert.deepEqual(F.freezeParams("f1", false, ""), { id: "f1", frozen: "false" });
  assert.equal(F.freezeParams("f1", true, "x".repeat(500)).reason.length, F.REASON_MAX);
  assert.deepEqual(F.DISPUTE_WINNERS.map((w) => w.id), ["client", "contractor", "split"]);
});

test("app.js rejestruje widok, trasy i pozycję menu obok rankingu krajów", async () => {
  const app = await src("../public/js/app.js");
  assert.match(app, /import \{ firmyView \} from "\.\/views\/firmy\.js";/);
  const m = /\[(\/\^\\\/firmy[^,]*\$\/), firmyView\]/.exec(app);
  assert.ok(m, "trasa firmyView w ROUTES");
  const re = new RegExp(m[1].slice(1, -1));
  for (const ok of ["/firmy", "/firmy/zarzad", "/firmy/kuznia-1"]) assert.ok(re.test(ok), ok);
  for (const bad of ["/firmy/a/b", "/firmyx", "/ranking"]) assert.ok(!re.test(bad), bad);
  assert.equal(re.exec("/firmy/abc")[1], "abc");
  assert.match(app, /navItem\("#\/ranking", "Ranking krajów"[^)]*\), navItem\("#\/firmy", "Firmy", "coins"/);
});

test("Skarb Świata: karta kredytów firm wpięta w zarząd", async () => {
  const world = await src("../public/js/views/world.js");
  assert.match(world, /import \{ firmLoansCard \} from "\.\/world-firm-loans\.js";/);
  assert.match(world, /add\(main, firmLoansCard\(ctx\)\);/);
  const card = await src("../public/js/views/world-firm-loans.js");
  for (const m of ["firmy.world.loans", "firmy.world.loanDecide", "firmy.world.loanSweep"]) assert.ok(card.includes(`"${m}"`), m);
  assert.match(card, /Dostępne po wdrożeniu etapu 2 Firm/);
});

test("statystyki: kategoria „Firmy”", () => {
  assert.ok(CATEGORY_ORDER.includes("firms"));
  assert.equal(CATEGORY_ORDER.indexOf("firms"), CATEGORY_ORDER.indexOf("trade") + 1);
  assert.equal(CATEGORY_LABELS.firms, "Firmy");
  assert.equal(categoryLabel("firms"), "Firmy");
  assert.equal(new Set(CATEGORY_ORDER).size, CATEGORY_ORDER.length);
  for (const k of CATEGORY_ORDER) assert.ok(CATEGORY_LABELS[k], k);
});

// ---------------------------------------------------------------- cache: przeglądarka i Worker

async function withBrowser(fn) {
  const fetch0 = globalThis.fetch;
  const storage0 = globalThis.sessionStorage;
  const removed = [];
  globalThis.sessionStorage = { removeItem: (k) => removed.push(k), setItem: () => assert.fail("firmy.* nie trafia do sessionStorage"), getItem: () => assert.fail("firmy.* bez danych zapasowych"), key: () => null, length: 0 };
  try {
    await fn(removed);
  } finally {
    globalThis.fetch = fetch0;
    if (storage0 === undefined) delete globalThis.sessionStorage;
    else globalThis.sessionStorage = storage0;
  }
}

test("api.read: odczyty firmy.* bez kopii w sessionStorage i bez starych danych", async () => {
  const { read } = await import("../public/js/api.js");
  await withBrowser(async (removed) => {
    for (const method of METHODS.slice(0, 7)) {
      globalThis.fetch = async () => new Response(JSON.stringify({ ok: true, data: { firms: [] } }));
      const r = await read(method, { id: "x" });
      assert.equal(r.stale, false);
      assert.ok(removed.some((k) => k.includes(method)), method);
      globalThis.fetch = async () => new Response(JSON.stringify({ ok: false, error: "Serwer gry nie odpowiedział", cached: { at: Date.now(), data: { firms: [1] } } }), { status: 504 });
      await assert.rejects(read(method), /nie odpowiedział/);
    }
  });
});

test("Worker: firmy.* poza cache offline Durable Object", async () => {
  const source = (await src("../src/hub.js"))
    .replace('import { DurableObject } from "cloudflare:workers";', "class DurableObject {}")
    .replace('from "./http.js"', `from ${JSON.stringify(new URL("../src/http.js", import.meta.url).href)}`);
  const { PanelHub } = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));
  for (const method of METHODS) {
    const h = Object.create(PanelHub.prototype);
    h.env = {};
    h.buckets = new Map();
    h.getSession = async () => ({ h: "s", uuid: "u-1", name: "Gracz" });
    h.ctx = { storage: { get: () => assert.fail("bez cache offline: " + method) } };
    h.putCache = () => assert.fail("bez zapisu cache: " + method);
    h.ask = async (kind, message) => {
      assert.equal(message.user.uuid, "u-1");
      assert.equal(message.method, method);
      return { kind: "result", m: { ok: true, data: { ok: true } } };
    };
    const res = await h.rpc(new Request("https://panel.test/api/rpc", { method: "POST", body: JSON.stringify({ method, params: { id: "f" } }) }), "127.0.0.1");
    assert.equal(res.status, 200, method);
    h.ask = async () => ({ kind: "offline" });
    const off = await h.rpc(new Request("https://panel.test/api/rpc", { method: "POST", body: JSON.stringify({ method, params: {} }) }), "127.0.0.1");
    assert.equal(off.status, 503);
    assert.equal("cached" in (await off.json()), false, method);
  }
});
