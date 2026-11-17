// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E: Statystyki Skarbu Świata (tylko WorldSteward). Zrzuty 114-…132-: zakładki na desktopie i 390 px, przelew do państwa NPC,
// gospodarstwa (pula obywateli NPC) i zasilenia wdrożeniowe.
// Sprawdza: dane każdej zakładki, filtry przepływów w zapytaniu RPC i w hashu, eksport CSV (Blob + prawdziwe
// pobranie przy CSP), spis monet RUNNING → DONE, odmowę dla innego admina i zwykłego gracza (także bezpośrednie RPC).
import fs from "node:fs";
import path from "node:path";

const TABS = ["bilans", "konta", "przeplywy", "gospodarstwa", "monety", "ruchy", "kupiec"];

export async function worldStatsSteps(E) {
  const { browser, step, assert, eq, page, linkLogin, go, shot, text, world, DESKTOP, MOBILE, sleep, SHOTS } = E;
  const wait = sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const callsSince = (n, method) => world.calls.slice(n).filter((c) => c.method === method);
  async function waitCall(n, method, pred = () => true, timeout = 8000) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
      const c = callsSince(n, method).filter(pred).at(-1);
      if (c) return c;
      await wait(60);
    }
    throw new Error("brak wywołania " + method + " spełniającego warunek; ostatnie: " + JSON.stringify(callsSince(n, method).at(-1)?.params));
  }
  const noOverflow = async (p, label) => {
    const o = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(o <= 1, `${label}: przewijanie w bok o ${o}px`);
  };
  const captureCsv = (p) =>
    p.evaluate(() => {
      window.__csv = null;
      if (!window.__csvHooked) {
        window.__csvHooked = true;
        const orig = URL.createObjectURL.bind(URL);
        URL.createObjectURL = (b) => {
          if (b instanceof Blob) b.arrayBuffer().then((buf) => (window.__csv = { text: new TextDecoder("utf-8", { ignoreBOM: true }).decode(buf), bom: [...new Uint8Array(buf.slice(0, 3))], type: b.type, name: null })); // Blob.text() usuwa BOM
          return orig(b);
        };
        document.addEventListener("click", (e) => {
          const a = e.target.closest && e.target.closest("a[download]");
          if (a) window.__csvName = a.getAttribute("download");
        }, true);
      }
    });
  const readCsv = async (p) => {
    await p.waitForFunction(() => window.__csv && window.__csv.text, { timeout: 30000 });
    return p.evaluate(() => ({ ...window.__csv, name: window.__csvName }));
  };

  const prevCensusMs = world.censusRunMs;
  world.censusRunMs = 5000;
  const ctx = await browser.createBrowserContext();
  const p = await page(ctx, DESKTOP, "skarb-statystyki");
  const dlDir = path.join(SHOTS || ".", "downloads");
  fs.rmSync(dlDir, { recursive: true, force: true });
  fs.mkdirSync(dlDir, { recursive: true });
  let realDownload = false;
  try {
    const cdp = await p.createCDPSession();
    await cdp.send("Browser.setDownloadBehavior", { behavior: "allow", downloadPath: dlDir, browserContextId: ctx.id, eventsEnabled: true });
    realDownload = true;
  } catch {}

  await step("Skarb Świata · Bilans: suma vs pula, baner rozbieżności, udziały kategorii, spis monet", async () => {
    await linkLogin(p, world.U.maciej);
    await go(p, "#/skarbswiata/bilans");
    const body = await text(p, "#main");
    for (const s of ["Suma wszystkich kont", "Pula serwera", "0 md — zgodne", "Gdzie są pieniądze", "Rezerwa Skarbu Świata", "Konta graczy", "Spis monet fizycznych", "Różnica spis − pokrycie"]) assert(body.includes(s), s);
    assert(/Ostrzeżenia \(1\)/.test(body), "baner ostrzeżeń z liczbą");
    assert(body.includes("Spis monet różni się od pokrycia o 120 md"), "treść rozbieżności");
    assert(await p.$(".ws-banner-warn .ic"), "ikona w banerze");
    assert((await p.$$(".ws-stack-seg")).length >= 10, "pasek udziałów");
    assert(await p.$('.nav-item.active[href="#/skarbswiata"]'), "menu podświetlone na podstronie");
    assert(await p.$('.tab.active[href="#/skarbswiata/bilans"]'), "aktywna zakładka");
    await shot(p, "114-skarb-bilans");
  });

  await step("Skarb Świata · Konta: ok. 40 kont, filtr typu, wyszukiwarka, CSV, przejście do historii", async () => {
    await go(p, "#/skarbswiata/konta");
    const all = await p.$$eval(".ws-accounts tbody tr", (r) => r.length);
    assert(all >= 38, "liczba kont " + all);
    await p.evaluate(() => [...document.querySelectorAll(".chip-btn")].find((b) => b.textContent.startsWith("Konta graczy")).click());
    const players = await p.$$eval(".ws-accounts tbody tr", (r) => r.length);
    assert(players > 5 && players < all, "filtr typu: " + players);
    assert((await p.evaluate(() => location.hash)).includes("typ=players"), "filtr w hashu");
    await p.type('input[aria-label="Szukaj konta lub właściciela"]', "bolko");
    await p.waitForFunction(() => document.querySelectorAll(".ws-accounts tbody tr").length === 1);
    await captureCsv(p);
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.trim() === "Eksportuj CSV").click());
    const csv = await readCsv(p);
    assert(csv.text.charCodeAt(0) === 0xfeff, "BOM");
    eq(csv.bom, [0xef, 0xbb, 0xbf], "BOM UTF-8 w bajtach");
    const lines = csv.text.trim().split("\r\n");
    eq(lines.length, 2, "nagłówek + 1 konto");
    assert(lines[0].includes("Konto;Właściciel;Typ;Saldo (md)"), "nagłówek CSV");
    assert(/^player:[0-9a-f-]+;Bolko;Konta graczy;\d+;\d+,?\d*;/.test(lines[1]), "wiersz Bolko: " + lines[1]);
    assert(/^skarb-konta-\d{4}-\d{2}-\d{2}-\d{4}\.csv$/.test(csv.name), "nazwa pliku " + csv.name);
    await p.evaluate(() => (document.querySelector('input[aria-label="Szukaj konta lub właściciela"]').value = ""));
    await p.evaluate(() => document.querySelector('input[aria-label="Szukaj konta lub właściciela"]').dispatchEvent(new Event("input", { bubbles: true })));
    await p.evaluate(() => [...document.querySelectorAll(".chip-btn")].find((b) => b.textContent.startsWith("Wszystkie")).click());
    await p.waitForFunction((n) => document.querySelectorAll(".ws-accounts tbody tr").length === n, {}, all);
    await shot(p, "115-skarb-konta");
    await p.evaluate(() => [...document.querySelectorAll(".ws-accounts tbody tr")].find((r) => r.textContent.includes("Bolko")).click());
    await p.waitForFunction(() => location.hash.startsWith("#/skarbswiata/konto/player%3A"));
    await p.waitForSelector(".ws-hero .ws-acc-h");
    await wait(300);
  });

  await step("Skarb Świata · Historia konta: saldo, wykres, kategorie, kontrahenci, okno 7/30/90 dni", async () => {
    const body = await text(p, "#main");
    for (const s of ["Historia konta", "Bolko", "Saldo w czasie", "Kategorie", "Kontrahenci", "Ostatnie transakcje", "Wpływy", "Wypływy"]) assert(body.includes(s), s);
    assert(await p.$(".chart-svg"), "wykres salda");
    assert(await p.$('.tab.active[href="#/skarbswiata/konta"]'), "zakładka Konta aktywna");
    const c = world.calls.filter((x) => x.method === "world.stats.account").at(-1);
    eq(c.params, { account: "player:" + world.U.bolko.uuid, days: 30 }, "parametry konta");
    await shot(p, "116-skarb-konto");
    const n = world.calls.length;
    await p.evaluate(() => [...document.querySelectorAll(".ws-window a")].find((a) => a.textContent === "7 dni").click());
    await waitCall(n, "world.stats.account", (x) => x.params.days === 7);
  });

  await step("Skarb Świata · Przepływy: filtry zmieniają zapytanie RPC i hash, wykresy, Sankey, macierz", async () => {
    let n = world.calls.length;
    await go(p, "#/skarbswiata/przeplywy");
    const first = await waitCall(n, "world.stats.flows");
    eq(first.params.offset, 0, "offset");
    eq(first.params.limit, 200, "limit");
    assert(Math.abs(first.params.to - first.params.from - 7 * 864e5) < 5000, "domyślnie 7 dni");
    const body = await text(p, "#main");
    for (const s of ["Transakcje", "Obrót", "Wypłaty ze świata", "Wpływy do świata", "Obrót dzienny według kategorii", "Przepływy między typami kont", "Najwięcej wysłali", "Najwięcej otrzymali", "Pluginy źródłowe", "Macierz przepływów"]) assert(body.includes(s), s);
    assert((await p.$$(".ws-sankey path.ws-link")).length >= 5, "wstęgi Sankeya");
    assert((await p.$$(".ws-bar")).length >= 7, "słupki");
    assert(await p.$(".ws-matrix tbody tr"), "macierz");
    // kategoria
    n = world.calls.length;
    await p.select('select[aria-label="Kategoria"]', "wages");
    const c1 = await waitCall(n, "world.stats.flows", (x) => x.params.category === "wages");
    eq(c1.params.offset, 0);
    await p.waitForFunction(() => location.hash.includes("kat=wages"));
    await p.waitForFunction(() => { const c = [...document.querySelectorAll(".ws-tx tbody tr .ws-cat")].map((e) => e.textContent); return c.length > 0 && c.every((t) => t === "Żołd"); });
    // zakres 30 dni
    n = world.calls.length;
    await p.evaluate(() => document.querySelector('.ws-range [data-range="30d"]').click());
    const c2 = await waitCall(n, "world.stats.flows", (x) => Math.abs(x.params.to - x.params.from - 30 * 864e5) < 5000);
    eq(c2.params.category, "wages", "filtr kategorii zostaje");
    await p.waitForFunction(() => location.hash.includes("zakres=30d") && location.hash.includes("kat=wages"));
    // wyszukiwanie z opóźnieniem + minimalna kwota
    n = world.calls.length;
    await p.type('input[aria-label="Szukaj"]', "żołnierzy");
    const c3 = await waitCall(n, "world.stats.flows", (x) => x.params.query === "żołnierzy");
    eq(callsSince(n, "world.stats.flows").length, 1, "debounce: jedno zapytanie po wpisaniu frazy");
    eq(c3.params.category, "wages");
    n = world.calls.length;
    await p.type('input[aria-label="Minimalna kwota"]', "5 zł");
    await p.keyboard.press("Tab");
    const c4 = await waitCall(n, "world.stats.flows", (x) => x.params.minAmount === 500);
    eq(c4.params.query, "żołnierzy");
    await p.waitForFunction(() => location.hash.includes("min=500"));
    // stronicowanie
    n = world.calls.length;
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.trim() === "Wyczyść filtry").click());
    await waitCall(n, "world.stats.flows", (x) => !x.params.category && !x.params.query && !x.params.minAmount);
    await p.waitForFunction(() => document.querySelector(".ws-pager"));
    n = world.calls.length;
    await p.evaluate(() => document.querySelector('.ws-pager button[aria-label="Następna strona"]').click());
    const c5 = await waitCall(n, "world.stats.flows", (x) => x.params.offset === 200);
    eq(c5.params.limit, 200);
    await p.waitForFunction(() => location.hash.includes("str=2"));
    // stan filtrów przetrwa przeładowanie strony
    n = world.calls.length;
    await p.reload({ waitUntil: "domcontentloaded" });
    await p.waitForSelector(".ws-tx");
    const c6 = await waitCall(n, "world.stats.flows");
    eq(c6.params.offset, 200, "strona z hasha");
    assert(Math.abs(c6.params.to - c6.params.from - 30 * 864e5) < 5000, "zakres z hasha");
    // tekst z serwera nie jest HTML
    assert(!(await p.$("#main .ws-tx b")), "powód wyświetlany jako tekst");
    await shot(p, "117-skarb-przeplywy");
  });

  await step("Skarb Świata · Przepływy: eksport CSV wszystkich pasujących (strony po 1000, BOM, ochrona formuł)", async () => {
    await go(p, "#/skarbswiata/przeplywy?zakres=30d");
    const total = await p.evaluate(() => Number(document.querySelector(".ws-tx")?.closest(".card")?.querySelector(".card-title")?.textContent.replace(/\D/g, "")));
    assert(total > 1000, "więcej niż jedna strona: " + total);
    await captureCsv(p);
    const n = world.calls.length;
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.startsWith("Eksportuj CSV")).click());
    const csv = await readCsv(p);
    const pages = callsSince(n, "world.stats.flows");
    eq(pages.map((c) => c.params.offset), Array.from({ length: Math.ceil(total / 1000) }, (_, i) => i * 1000), "kolejne strony");
    assert(pages.every((c) => c.params.limit <= 1000), "limit ≤ 1000");
    assert(csv.text.charCodeAt(0) === 0xfeff, "BOM");
    assert(csv.type.startsWith("text/csv"), "typ " + csv.type);
    const lines = csv.text.replace(/\r\n$/, "").split("\r\n");
    eq(lines.length, total + 1, "wszystkie wiersze + nagłówek");
    assert(lines[0].startsWith("﻿Nr;Czas;Rodzaj;Od (konto)"), "nagłówek");
    assert(csv.text.includes(";\"'=HYPERLINK(\"\"http://zly.example\"\") <b>sklep</b>\";"), "formuła poprzedzona apostrofem");
    assert(!/;=HYPERLINK/.test(csv.text), "brak surowej formuły");
    assert(/^skarb-przeplywy-/.test(csv.name), csv.name);
    assert(realDownload, "CDP: zezwolenie na pobieranie plików w kontekście testu");
    {
      const t0 = Date.now();
      let file = null;
      while (Date.now() - t0 < 15000 && !file) {
        file = fs.readdirSync(dlDir).find((f) => f.startsWith("skarb-przeplywy-") && f.endsWith(".csv"));
        if (!file) await wait(100);
      }
      assert(file, "plik pobrany mimo CSP");
      const bytes = fs.readFileSync(path.join(dlDir, file));
      assert(bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf, "BOM UTF-8 w pliku");
    }
  });

  await step("Skarb Świata · Monety: rozbieżność spisu, rodzaje, nominały, miejsca, filtr i CSV", async () => {
    await go(p, "#/skarbswiata/monety");
    const body = await text(p, "#main");
    for (const s of ["Spis zakończony", "Rozbieżność: w świecie jest o 1 zł 2 sr mniej monet niż pokrycie", "Złote korony", "Srebrniki", "Miedziaki", "Według rodzaju miejsca", "Skrzynie i kontenery", "Miejsca monet", "Przebieg spisu", "błędy odczytu plików"]) assert(body.includes(s), s);
    const all = await p.$$eval(".ws-locs tbody tr", (r) => r.length);
    assert(all > 30, "miejsca " + all);
    await p.evaluate(() => [...document.querySelectorAll(".chip-btn")].find((b) => b.textContent.startsWith("Przedmioty na ziemi")).click());
    eq(await p.$$eval(".ws-locs tbody tr", (r) => r.length), 1, "filtr rodzaju");
    await p.evaluate(() => [...document.querySelectorAll(".chip-btn")].find((b) => b.textContent.startsWith("Wszystkie")).click());
    await p.type('input[aria-label="Szukaj miejsca"]', "Siwek");
    await p.waitForFunction(() => document.querySelectorAll(".ws-locs tbody tr").length === 1);
    assert(!(await p.$(".ws-locs i")), "opis jako tekst");
    await captureCsv(p);
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.trim() === "Eksportuj CSV").click());
    const csv = await readCsv(p);
    assert(csv.text.split("\r\n")[1].startsWith("Inne;Kieszeń konia <i>Siwek</i>;Dobrawa;world;310;64;-140;20;1;0;2;0;LIVE"), csv.text.split("\r\n")[1]);
    await p.evaluate(() => { const i = document.querySelector('input[aria-label="Szukaj miejsca"]'); i.value = ""; i.dispatchEvent(new Event("input", { bubbles: true })); });
    await p.waitForFunction((n) => document.querySelectorAll(".ws-locs tbody tr").length === n, {}, all);
    await shot(p, "118-skarb-monety");
  });

  await step("Skarb Świata · Monety: „Wykonaj spis” → potwierdzenie → RUNNING z paskiem → DONE (odpytywanie co 3 s)", async () => {
    const n = world.calls.length;
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.trim() === "Wykonaj spis").click());
    await p.waitForSelector(".modal");
    assert((await text(p, ".modal")).includes("porcjami w tle"), "opis w modalu");
    await p.evaluate(() => [...document.querySelectorAll(".modal button")].find((b) => b.textContent === "Rozpocznij spis").click());
    await waitCall(n, "world.stats.census");
    await p.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Spis trwa"), { timeout: 8000 });
    assert(await p.$(".ws-census-progress .bar"), "pasek postępu");
    assert(await p.$eval("#main .btn-primary", (b) => b.disabled), "przycisk zablokowany w trakcie");
    await shot(p, "119-skarb-monety-spis");
    await p.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Spis zakończony"), { timeout: 20000 });
    assert(callsSince(n, "world.stats.coins").length >= 2, "odpytywanie world.stats.coins");
    assert(!(await p.$(".ws-census-progress")), "pasek znika po zakończeniu");
  });

  await step("Skarb Świata · Ruchy monet: podsumowanie, filtr rodzaju i gracza w RPC, stronicowanie", async () => {
    let n = world.calls.length;
    await go(p, "#/skarbswiata/ruchy");
    await waitCall(n, "world.stats.trail");
    const body = await text(p, "#main");
    for (const s of ["Zdarzenia", "Według rodzaju", "Przeniesione monety"]) assert(body.includes(s), s);
    assert((await p.$$(".ws-trail tbody tr")).length > 50, "zdarzenia");
    n = world.calls.length;
    await p.select('select[aria-label="Rodzaj zdarzenia"]', "DROP");
    await waitCall(n, "world.stats.trail", (x) => x.params.kind === "DROP");
    await p.waitForFunction(() => location.hash.includes("rodzaj=DROP"));
    n = world.calls.length;
    await p.type('input[aria-label="Gracz"]', "Bolko");
    await p.keyboard.press("Tab");
    const c = await waitCall(n, "world.stats.trail", (x) => x.params.player === "Bolko");
    eq(c.params.kind, "DROP");
    await p.waitForFunction(() => [...document.querySelectorAll(".ws-trail tbody tr")].every((r) => r.textContent.includes("Bolko") && r.textContent.includes("Upuszczenie")));
    await shot(p, "120-skarb-ruchy");
  });

  await step("Skarb Świata · Kupiec: budżet vs cel, skup, towary z poziomem i trendem, wykres wybranego towaru", async () => {
    await go(p, "#/skarbswiata/kupiec");
    const body = await text(p, "#main");
    for (const s of ["Kupiec skupuje towary.", "Budżet kupca", "Pszenica", "mało", "brak", "dużo", "w normie", "★", "↑", "↓", "Towar: Sztabka żelaza"]) assert(body.includes(s), s);
    await p.evaluate(() => [...document.querySelectorAll(".ws-goods tbody tr")].find((r) => r.textContent.includes("Chleb")).click());
    await p.waitForFunction(() => document.querySelector("#main").textContent.includes("Towar: Chleb"));
    assert((await p.$$(".chart-svg")).length >= 4, "wykresy budżetu i towaru");
    await shot(p, "121-skarb-kupiec");
  });

  const rpc = (pg, method, params = {}) =>
    pg.evaluate((method, params) => fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method, params }), credentials: "same-origin" }).then((x) => x.json()), method, params);
  const clickText = (pg, sel, label) => pg.evaluate((sel, label) => { const b = [...document.querySelectorAll(sel)].find((x) => x.textContent.trim() === label && !x.disabled); if (b) b.click(); return !!b; }, sel, label);

  await step("Skarb Świata · Zarząd: przelew z rezerwy do skarbca państwa NPC z potwierdzeniem kwoty i powodu", async () => {
    await go(p, "#/skarbswiata");
    const body = await text(p, "#main");
    assert(body.includes("Przelew do skarbca państwa NPC"), "karta przelewu");
    assert(body.includes("Księstwo Mazowsze"), "lista państw NPC");
    assert(!body.includes("Królestwo Polan") || !(await p.$$eval(".ws-npc-item", (es) => es.some((e) => e.textContent.includes("Królestwo Polan")))), "tylko państwa NPC");
    // walidacja bez wysyłania
    let n = world.calls.length;
    assert(await clickText(p, "#main button", "Przelej…"), "przycisk Przelej…");
    await p.waitForFunction(() => document.querySelector(".ws-npc-errors")?.textContent.includes("Wybierz państwo NPC."));
    eq(callsSince(n, "world.npcTransfer").length, 0, "brak RPC przy błędach formularza");
    const before = (await rpc(p, "world.npcTreasuries")).data;
    const maz0 = before.countries.find((c) => c.id === "npc-mazowsze");
    await p.type('input[aria-label="Szukaj państwa NPC"]', "mazow");
    eq(await p.$$eval(".ws-npc-item", (es) => es.length), 1, "wyszukiwarka");
    await p.click('.ws-npc-item[data-id="npc-mazowsze"]');
    assert(await p.$('.ws-npc-item.on[aria-checked="true"]'), "wybrany kraj");
    await p.type('input[aria-label="Kwota przelewu"]', "25 zł");
    await p.type('input[aria-label="Powód przelewu"]', "Wsparcie odbudowy");
    await clickText(p, "#main button", "Przelej…");
    await p.waitForSelector(".modal .ws-npc-amount");
    const m = await text(p, ".modal");
    for (const x of ["25 zł", "= 2500 md", "Księstwo Mazowsze", "Rezerwa", "WorldSteward: Wsparcie odbudowy"]) assert(m.includes(x), "modal: " + x);
    await shot(p, "130-skarb-zarzad-przelew-npc");
    n = world.calls.length;
    await clickText(p, ".modal button", "Przelej 25 zł");
    await waitCall(n, "world.npcTransfer");
    eq(callsSince(n, "world.npcTransfer").at(-1).params, { country: "npc-mazowsze", amount: "25 zł", confirm: 2500, reason: "Wsparcie odbudowy", direction: "toCountry" }, "parametry przelewu");
    await p.waitForFunction(() => !document.querySelector(".modal"));
    const after = (await rpc(p, "world.npcTreasuries")).data;
    eq(after.reserve, before.reserve - 2500, "rezerwa po przelewie");
    eq(after.countries.find((c) => c.id === "npc-mazowsze").balance, maz0.balance + 2500, "skarbiec po przelewie");
    const f = (await rpc(p, "world.stats.flows", { query: "Wsparcie odbudowy" })).data;
    eq(f.rows[0].category, "world", "kategoria w statystykach");
    assert(f.rows[0].reason.startsWith("WorldSteward: "), "powód w księdze");
    eq((await rpc(p, "world.stats.summary")).data.difference, 0, "suma = pula");
  });

  await step("Skarb Świata · Zarząd: zwrot ponad saldo kraju jest blokowany, serwer sprawdza potwierdzenie i powód", async () => {
    await go(p, "#/skarbswiata");
    await p.click('.ws-npc-item[data-id="npc-mazowsze"]');
    await p.click('.ws-npc-dir [data-dir="toReserve"]');
    await p.type('input[aria-label="Kwota przelewu"]', "999999 zł");
    await p.type('input[aria-label="Powód przelewu"]', "Zwrot nadwyżki");
    const n = world.calls.length;
    await clickText(p, "#main button", "Przelej…");
    await p.waitForSelector(".modal .ws-npc-amount");
    assert((await text(p, ".modal")).includes("Za mało środków w skarbcu kraju."), "ostrzeżenie w modalu");
    eq(await p.$$eval(".modal .modal-foot button", (bs) => bs.map((b) => b.textContent)), ["Anuluj"], "brak przycisku potwierdzenia");
    await clickText(p, ".modal button", "Anuluj");
    await p.waitForFunction(() => !document.querySelector(".modal"));
    eq(callsSince(n, "world.npcTransfer").length, 0, "nic nie wysłano");
    const base = { country: "npc-mazowsze", amount: "1 zł", confirm: 100, reason: "Test reguł", direction: "toCountry" };
    eq((await rpc(p, "world.npcTransfer", { ...base, confirm: 1000 })).error, "Potwierdzona kwota nie zgadza się z kwotą przelewu.");
    eq((await rpc(p, "world.npcTransfer", { ...base, reason: "x" })).error, "Podaj powód przelewu (3–120 znaków).");
    eq((await rpc(p, "world.npcTransfer", { ...base, country: "c-polanie" })).error, "Nie ma takiego państwa NPC.");
    eq((await rpc(p, "world.npcTransfer", { ...base, direction: "toReserve", amount: "999999 zł", confirm: 99999900 })).error, "Za mało środków w skarbcu kraju.");
    eq((await rpc(p, "world.npcTransfer", { ...base, amount: "2000000 zł", confirm: 200000000 })).error, "Za mało środków w rezerwie Skarbu Świata.");
  });

  await step("Skarb Świata · Gospodarstwa: saldo, prognoza, średnie, wykresy, robocizna z budów, okno 7/30/90, CSV", async () => {
    let n = world.calls.length;
    await go(p, "#/skarbswiata/gospodarstwa");
    eq((await waitCall(n, "world.stats.households")).params, { days: 30 }, "domyślne okno");
    const body = await text(p, "#main");
    for (const x of ["Pula gospodarstw", "Prognoza", "Średnio wpływy / dzień", "Średnio netto / dzień", "Wpływy i wypływy dziennie", "Saldo puli", "Robocizna z budów", "Zaległe (czeka na rozliczenie)", "Anulowane zwrotami", "Kategorie", "Kontrahenci", "Ostatnie operacje", "Zasilenia wdrożeniowe tej puli"]) assert(body.includes(x), x);
    assert(/Wystarczy na \d+ (dni|dzień)|samowystarczalna|Za mało danych/.test(body), "tekst prognozy");
    assert(await p.$(".ws-forecast"), "karta prognozy");
    assert(await p.$(".ws-line.ws-net"), "linia netto");
    assert((await p.$$(".ws-bar.ws-in")).length > 0 && (await p.$$(".ws-bar.ws-out")).length > 0, "słupki wpływów i wypływów");
    assert(await p.$('.tab.active[href="#/skarbswiata/gospodarstwa"]'), "aktywna zakładka");
    await shot(p, "131-skarb-gospodarstwa");
    await captureCsv(p);
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.trim() === "Eksportuj CSV").click());
    const csv = await readCsv(p);
    eq(csv.bom, [0xef, 0xbb, 0xbf], "BOM");
    assert(csv.text.split("\r\n")[0].includes("Nr;Czas;Rodzaj"), "nagłówek CSV");
    assert(/^skarb-gospodarstwa-/.test(csv.name), csv.name);
    n = world.calls.length;
    await p.evaluate(() => [...document.querySelectorAll(".ws-window a")].find((a) => a.textContent === "7 dni").click());
    eq((await waitCall(n, "world.stats.households", (x) => x.params.days === 7)).params, { days: 7 });
  });

  await step("Skarb Świata · Zarząd: zasilenia wdrożeniowe — wykonanie z potwierdzeniem, jednorazowość, świadome ponowienie stanu niepewnego", async () => {
    await go(p, "#/skarbswiata");
    const body = await text(p, "#main");
    for (const x of ["Zasilenia wdrożeniowe", "Pula obywateli NPC", "Budżet kupców", "Nowe", "Niepewne", "Dotychczas z rezerwy", "Ponów świadomie"]) assert(body.includes(x), x);
    const row = (label) => `[...document.querySelectorAll(".ws-grants tbody tr")].find((r) => r.textContent.includes(${JSON.stringify(label)}))`;
    let n = world.calls.length;
    await p.evaluate(`${row("Pula obywateli NPC")}.querySelector("button").click()`);
    await p.waitForSelector(".modal .ws-npc-amount");
    const m = await text(p, ".modal");
    for (const x of ["3126 zł", "world:households", "Rezerwa"]) assert(m.includes(x), "modal: " + x);
    assert(m.replace(/[\s\u00a0\u202f]/g, "").includes("=312600md"), "kwota w md");
    await clickText(p, ".modal button", "Wykonaj 3126 zł");
    await waitCall(n, "world.grantsApply");
    eq(callsSince(n, "world.grantsApply").at(-1).params, { key: "obywatele" }, "parametry zasilenia");
    await p.waitForFunction(new Function(`const r = ${row("Pula obywateli NPC")}; return r && r.textContent.includes("Wykonane") && !r.querySelector("button");`), { timeout: 8000 });
    const again = await rpc(p, "world.grantsApply", { key: "obywatele" });
    eq(again.ok, false, "drugie wykonanie odrzucone");
    assert(again.error.startsWith("To zasilenie zostało już wykonane"), again.error);
    const unsure = await rpc(p, "world.grantsApply", { key: "kupcy" });
    assert(unsure.ok === false && unsure.error.startsWith("Stan zasilenia"), "niepewne bez retry: " + unsure.error);
    n = world.calls.length;
    await p.evaluate(`${row("Budżet kupców")}.querySelector("button").click()`);
    await p.waitForSelector(".modal input");
    await clickText(p, ".modal button", "Ponów zasilenie");
    assert(await p.$(".modal input.invalid"), "bez przepisania klucza nie wysyła");
    eq(callsSince(n, "world.grantsApply").length, 0, "nic nie wysłano");
    await p.type(".modal input", "kupcy");
    await clickText(p, ".modal button", "Ponów zasilenie");
    await waitCall(n, "world.grantsApply");
    eq(callsSince(n, "world.grantsApply").at(-1).params, { key: "kupcy", retry: "true" }, "świadome ponowienie");
    await p.waitForFunction(() => ![...document.querySelectorAll(".ws-grants tbody button")].length, { timeout: 8000 });
    await shot(p, "132-skarb-zarzad-zasilenia");
    eq((await rpc(p, "world.stats.summary")).data.difference, 0, "suma = pula po zasileniach");
    eq((await rpc(p, "world.stats.summary")).data.categories.length, 13, "13 kategorii bilansu");
  });

  await step("Skarb Świata: 390 px — każda zakładka bez poziomego przewijania", async () => {
    await p.setViewport(MOBILE);
    let i = 122;
    for (const t of TABS) {
      await go(p, "#/skarbswiata/" + t + (t === "przeplywy" ? "?ile=50" : ""));
      await wait(250);
      await noOverflow(p, t);
      await shot(p, `${i++}-skarb-${t}-390`);
    }
    await go(p, "#/skarbswiata/konto/" + encodeURIComponent("player:" + world.U.bolko.uuid));
    await wait(250);
    await noOverflow(p, "konto");
    await shot(p, `${i++}-skarb-konto-390`);
    await go(p, "#/skarbswiata");
    await noOverflow(p, "zarząd");
    await p.setViewport(DESKTOP);
  });
  await ctx.close();
  fs.rmSync(dlDir, { recursive: true, force: true });

  const adminBefore = world.U.wedrowiec.admin;
  for (const [who, setup] of [["inny administrator (Wedrowiec)", () => (world.U.wedrowiec.admin = true)], ["zwykły gracz (Bolko)", () => {}]]) {
    const user = who.includes("Bolko") ? world.U.bolko : world.U.wedrowiec;
    const c2 = await browser.createBrowserContext();
    const q = await page(c2, DESKTOP, "skarb-odmowa");
    await step(`Skarb Świata: ${who} — brak menu i „Brak dostępu” na każdej podstronie, RPC odrzucone`, async () => {
      setup();
      await linkLogin(q, user);
      assert(!(await q.$('a[href="#/skarbswiata"]')), "brak pozycji menu");
      const n = world.calls.length;
      for (const t of ["", ...TABS.map((x) => "/" + x), "/konto/" + encodeURIComponent("world:reserve"), "/przeplywy?zakres=30d&kat=wages"]) {
        await go(q, "#/skarbswiata" + t);
        const body = await text(q, "#main");
        assert(body.includes("Brak dostępu"), "odmowa: " + t);
        for (const leak of ["Suma wszystkich kont", "Transakcje", "Rezerwa Skarbu", "Miejsca monet", "Budżet kupca", "Pula gospodarstw", "Zasilenia wdrożeniowe"]) assert(!body.includes(leak), `wyciek „${leak}” na ${t}`);
      }
      eq(callsSince(n, "world.stats.flows").length, 0, "SPA nie pyta bez roli");
      const r = await q.evaluate(() =>
        Promise.all(
          ["world.stats.flows", "world.stats.summary", "world.stats.accounts", "world.stats.coins", "world.stats.census", "world.stats.households"].map((method) =>
            fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method, params: method === "world.stats.flows" ? { limit: 1000 } : {} }), credentials: "same-origin" }).then((x) => x.json()),
          ),
        ),
      );
      for (const j of r) eq(j, { ok: false, error: "Tylko WorldSteward ma dostęp do statystyk Skarbu Świata." }, "bezpośrednie RPC");
      assert(!(await q.$(".ws-npc-item")), "brak karty przelewu do państw NPC");
      assert(!(await q.$(".ws-grants")), "brak karty zasileń");
      for (const [method, params] of [["world.npcTreasuries", {}], ["world.npcTransfer", { country: "npc-mazowsze", amount: "1 zł", confirm: 100, reason: "Próba", direction: "toCountry" }], ["world.grants", {}], ["world.grantsApply", { key: "wszystkie" }]])
        eq(await rpc(q, method, params), { ok: false, error: "Tylko WorldSteward zarządza Skarbem Świata." }, method);
      eq(await q.evaluate(() => Object.keys(sessionStorage).filter((k) => k.includes("world.")).length), 0, "brak danych w sessionStorage");
    });
    await c2.close();
  }
  world.U.wedrowiec.admin = adminBefore;
  world.censusRunMs = prevCensusMs;
}
