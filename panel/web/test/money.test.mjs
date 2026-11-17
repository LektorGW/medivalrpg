// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Testy parsera i formattera kwot (node --test). Przypadki jak w skarbiec MoneyTest.
import { test } from "node:test";
import assert from "node:assert/strict";
import { formatMoney, parseMoney, parseSignedMoney } from "../public/js/money.js";

test("format: zera pominięte, 0 md, ujemne", () => {
  assert.equal(formatMoney(0), "0 md");
  assert.equal(formatMoney(1), "1 md");
  assert.equal(formatMoney(10), "1 sr");
  assert.equal(formatMoney(100), "1 zł");
  assert.equal(formatMoney(153), "1 zł 5 sr 3 md");
  assert.equal(formatMoney(103), "1 zł 3 md");
  assert.equal(formatMoney(150), "1 zł 5 sr");
  assert.equal(formatMoney(12345), "123 zł 4 sr 5 md");
  assert.equal(formatMoney(-153), "-1 zł 5 sr 3 md");
  assert.equal(formatMoney(-7), "-7 md");
  assert.equal(formatMoney("250"), "2 zł 5 sr");
  assert.equal(formatMoney(null), "—");
  assert.equal(formatMoney(NaN), "—");
});

test("parse: format Skarbca i warianty", () => {
  assert.equal(parseMoney("1 zł 5 sr 3 md"), 153);
  assert.equal(parseMoney("1zł5sr3md"), 153);
  assert.equal(parseMoney("3zl"), 300);
  assert.equal(parseMoney("2 sr 3 md"), 23);
  assert.equal(parseMoney("150"), 150);
  assert.equal(parseMoney("0"), 0);
  assert.equal(parseMoney("  7 MD "), 7);
  assert.equal(parseMoney("1 korona, 2 srebrniki i 5 miedziaków"), 125);
  assert.equal(parseMoney("1 ZŁ + 1 sr"), 110);
  assert.equal(parseMoney("5 sr 2"), 52);
  assert.equal(parseMoney("2 zł."), 200);
  assert.equal(parseMoney("10 zlotych"), 1000);
});

test("parse: błędne wejście", () => {
  for (const bad of ["", "   ", "abc", "1.5 zł", "1,5", "1,5 zł", "1 , 5 sr", "-5", "1 zł 2 zł", "5 7", "3 kg", "zł", "1 zł i", "1e3", null, undefined, 5]) {
    assert.equal(parseMoney(bad), null, "powinno odrzucić: " + JSON.stringify(bad));
  }
  assert.equal(parseMoney("99999999999999999 zł"), null);
});

test("parse ze znakiem (korekty)", () => {
  assert.equal(parseSignedMoney("-1 zł 5 sr"), -150);
  assert.equal(parseSignedMoney("+ 3 md"), 3);
  assert.equal(parseSignedMoney("−2 zł"), -200);
  assert.equal(parseSignedMoney("4 sr"), 40);
  assert.equal(parseSignedMoney("--1"), null);
});

test("format ↔ parse w obie strony", () => {
  for (const v of [0, 1, 9, 10, 11, 99, 100, 101, 110, 999, 1000, 123456789]) {
    assert.equal(parseMoney(formatMoney(v)), v);
  }
});
