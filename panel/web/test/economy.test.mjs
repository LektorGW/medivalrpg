// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Testy jednostkowe modelu „Gospodarka kraju” (public/js/economy-model.js) — bez przeglądarki.
import test from "node:test";
import assert from "node:assert/strict";
import {
  canSeeEconomy, navHref, pickableCountries, isEconomyDenied, fmtDuration, fmtDays, foodLevel, fillLevel, kindClass, plural, countLevels,
  normalizeEconomy, dailyNote, describeValue, humanKey, KNOWN_VERSION, hrefOf,
} from "../public/js/economy-model.js";
import { DENIED } from "./fake-economy.mjs";

const ov = { countries: [{ id: "c-polanie", npc: false, name: "Polanie" }, { id: "c-wislanie", npc: false, name: "Wiślanie" }, { id: "npc-mazowsze", npc: true, name: "Mazowsze" }, { id: "npc-rus", npc: true, name: "Ruś" }] };
const me = (country, rank, roles = {}) => ({ name: "x", country: country ? { id: country, rank } : null, roles });

test("dostęp w interfejsie: władca i doradca swojego kraju, admin, WorldSteward, zarządca NPC", () => {
  const polanie = { id: "c-polanie", npc: false };
  const mazowsze = { id: "npc-mazowsze", npc: true };
  assert.equal(canSeeEconomy(me("c-polanie", "RULER"), polanie, "RULER"), true);
  assert.equal(canSeeEconomy(me("c-polanie", "ADVISOR"), polanie, "ADVISOR"), true);
  assert.equal(canSeeEconomy(me("c-polanie", "CITIZEN"), polanie, "CITIZEN"), false);
  assert.equal(canSeeEconomy(me("c-wislanie", "RULER"), polanie, null), false, "władca obcego kraju");
  assert.equal(canSeeEconomy(me(null, null, { admin: true }), polanie, null), true);
  assert.equal(canSeeEconomy(me(null, null, { worldManager: true }), polanie, null), true);
  assert.equal(canSeeEconomy(me(null, null, { managedNpcCountries: ["npc-mazowsze"] }), mazowsze, null), true);
  assert.equal(canSeeEconomy(me(null, null, { managedNpcCountries: ["npc-mazowsze"] }), polanie, null), false);
  assert.equal(canSeeEconomy(me(null, null, { npcManager: true }), polanie, null), false, "globalny zarządca NPC nie widzi kraju gracza");
  assert.equal(canSeeEconomy(null, polanie, "RULER"), false);
});

test("menu: własny kraj, lista albo nic", () => {
  assert.equal(navHref(me("c-polanie", "RULER")), "#/gospodarka/c-polanie");
  assert.equal(navHref(me("c-polanie", "ADVISOR")), "#/gospodarka/c-polanie");
  assert.equal(navHref(me("c-polanie", "CITIZEN")), null);
  assert.equal(navHref(me(null, null)), null);
  assert.equal(navHref(me(null, null, { admin: true })), "#/gospodarka");
  assert.equal(navHref(me("c-polanie", "CITIZEN", { worldManager: true })), "#/gospodarka");
  assert.equal(navHref(null), null);
  assert.equal(navHref(me("c-wislanie", "RULER", { admin: true })), "#/gospodarka", "admin-władca: lista wszystkich");
  assert.equal(hrefOf("a b/c"), "#/gospodarka/a%20b%2Fc");
});

test("lista krajów do wyboru: tylko te, do których jest dostęp", () => {
  assert.equal(pickableCountries(me(null, null, { admin: true }), ov).length, 4);
  assert.deepEqual(pickableCountries(me(null, null, { managedNpcCountries: ["npc-rus"] }), ov).map((c) => c.id), ["npc-rus"]);
  assert.deepEqual(pickableCountries(me(null, null, { npcManager: true }), ov).map((c) => c.id), ["npc-mazowsze", "npc-rus"]);
  assert.deepEqual(pickableCountries(me("c-polanie", "RULER", { npcManager: true }), ov).map((c) => c.id), ["c-polanie", "npc-mazowsze", "npc-rus"]);
  assert.deepEqual(pickableCountries(me("c-polanie", "CITIZEN"), ov), []);
  assert.deepEqual(pickableCountries(me(null, null, { admin: true }), null), []);
});

test("odmowa rozpoznawana po komunikacie serwera", () => {
  assert.equal(isEconomyDenied({ message: DENIED, status: 200, app: true }), true);
  assert.equal(isEconomyDenied({ message: "Brak uprawnień do tej operacji.", status: 200, app: true }), true);
  assert.equal(isEconomyDenied({ message: "Serwer gry jest offline", status: 503 }), false);
  assert.equal(isEconomyDenied(null), false);
});

test("czasy, dni zapasu, poziomy i odmiana", () => {
  assert.equal(fmtDuration(45_000), "45 s");
  assert.equal(fmtDuration(25 * 60_000), "25 min");
  assert.equal(fmtDuration(125 * 60_000), "2 godz. 5 min");
  assert.equal(fmtDuration(3 * 24 * 3600_000), "3 dni");
  assert.equal(fmtDuration(-1), "—");
  assert.equal(fmtDays(3.9), "3,9 dnia");
  assert.equal(fmtDays(1), "1 dzień");
  assert.equal(fmtDays(5), "5 dni");
  assert.equal(fmtDays(150), "ponad 99 dni");
  assert.equal(fmtDays(null), "—");
  assert.equal(foodLevel(0.5), "err");
  assert.equal(foodLevel(2), "warn");
  assert.equal(foodLevel(7), "ok");
  assert.equal(foodLevel(null), "idle");
  assert.equal(fillLevel(1, true), "err");
  assert.equal(fillLevel(0.93, false), "warn");
  assert.equal(fillLevel(0.7, false), "mid");
  assert.equal(fillLevel(0.1, false), "ok");
  assert.equal(kindClass("wood"), "eco-k-wood");
  assert.equal(kindClass("<script>"), "eco-k-other", "nieznany rodzaj nie trafia do klasy CSS");
  assert.equal(plural(1, "a", "b", "c"), "a");
  assert.equal(plural(3, "a", "b", "c"), "b");
  assert.equal(plural(13, "a", "b", "c"), "c");
  assert.equal(plural(24, "a", "b", "c"), "b");
  assert.deepEqual(countLevels([{ level: "error" }, { level: "warn" }, { level: "warn" }, { level: "info" }, { level: "x" }]), { error: 1, warn: 2, info: 1 });
});

test("normalizacja: puste, stare i nowsze odpowiedzi nie psują widoku", () => {
  const e = normalizeEconomy(null);
  assert.equal(e.production.workplaces.length, 0);
  assert.equal(e.stores.list.length, 0);
  assert.equal(e.granary.growth.possible, false);
  assert.equal(e.haulers.count, 0);
  assert.equal(e.newer, false);
  const n = normalizeEconomy({ version: KNOWN_VERSION + 1, production: { workplaces: "x", daily: { source: "ludnosc", items: [{ key: "a", amount: 1 }] } }, stores: { list: [{ label: "s" }] }, haulers: { walking: [1, 2] }, warnings: [null, { level: "warn" }], extra: { chains: [] } });
  assert.equal(n.newer, true);
  assert.deepEqual(n.production.workplaces, []);
  assert.equal(n.production.daily.items.length, 1);
  assert.equal(n.haulers.count, 2);
  assert.equal(n.warnings.length, 1);
  assert.deepEqual(n.extra, { chains: [] });
});

test("opis wydajności i nowych pól", () => {
  assert.match(dailyNote({ source: "estimate", coveredMs: 90 * 60_000 }), /Szacunek.*2 godz\. 30 min|Szacunek.*90 min|Szacunek/);
  assert.match(dailyNote({ source: "ludnosc" }), /Ludnosc/);
  assert.match(dailyNote({ source: "none" }), /30 min/);
  assert.equal(humanKey("waitingSince"), "waiting since");
  assert.equal(describeValue({ glassNeeded: 24, steps: ["piasek", "szkło"] }), "glass needed: 24; steps: piasek, szkło");
  assert.equal(describeValue(true), "tak");
  assert.equal(describeValue("x".repeat(200)).length, 141);
});
