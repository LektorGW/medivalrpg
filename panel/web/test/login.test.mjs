// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Komunikat błędu z adresu /#/logowanie?blad=… — tylko znane kody, nigdy właściwości prototypu obiektu.
import test from "node:test";
import assert from "node:assert/strict";
import { linkError } from "../public/js/views/login.js";

test("linkError: znane kody dają polski komunikat", () => {
  assert.match(linkError("link"), /\/panel/);
  assert.match(linkError("limit"), /Za dużo prób/);
});

test("linkError: nieznane kody i nazwy z prototypu → brak komunikatu", () => {
  for (const code of [null, "", "x", "constructor", "toString", "__proto__", "hasOwnProperty", "valueOf"]) {
    assert.equal(linkError(code), null, String(code));
  }
});
