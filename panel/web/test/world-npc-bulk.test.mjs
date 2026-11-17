// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Przelew do wszystkich państw NPC — czyste funkcje widoku (podgląd sumy i klucz operacji).
import { test } from "node:test";
import assert from "node:assert/strict";

const view = await import("../public/js/views/world-npc-transfer.js");

test("podgląd: liczba państw, suma i rezerwa po przelewie", () => {
  const countries = [{ id: "a" }, { id: "b" }, { id: "c" }];
  assert.deepEqual(view.bulkPreview(100_000, countries, 2_500), { count: 3, total: 7_500, reserveAfter: 92_500, short: false, empty: false });
  assert.equal(view.bulkPreview(5_000, countries, 2_500).short, true, "za mało w rezerwie na całość");
  assert.deepEqual(view.bulkPreview(1_000, [], 2_500), { count: 0, total: 0, reserveAfter: 1_000, short: false, empty: true });
});

test("klucz operacji pasuje do walidacji serwera i jest nowy przy każdym podglądzie", () => {
  const a = view.newBulkKey();
  const b = view.newBulkKey();
  assert.match(a, /^[a-z0-9-]{8,64}$/);
  assert.ok(a.startsWith("npcbulk-"));
  assert.notEqual(a, b);
});
