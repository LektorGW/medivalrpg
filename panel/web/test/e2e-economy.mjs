// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E: Gospodarka kraju (#/gospodarka/<id>). Zrzuty 140-…147-: władca (desktop), odmowa dla obcego kraju, doradca na
// telefonie 390 px, obywatel bez dostępu, admin z listą krajów i krajem NPC z nowymi polami Ludnosci, WorldSteward jako
// zarządca (bez roli admina), zarządca kraju NPC. Sprawdza też, że odpowiedź nie trafia do sessionStorage i że
// bezpośrednie RPC dla obcego kraju zwraca odmowę bez danych.
import { DENIED } from "./fake-economy.mjs";

export async function economySteps(E) {
  const { browser, step, assert, page, linkLogin, go, shot, text, world, DESKTOP, MOBILE } = E;
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const noOverflow = async (p, label) => {
    const o = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(o <= 1, `${label}: przewijanie w bok o ${o}px`);
  };
  const noLocalCopy = async (p) => {
    const keys = await p.evaluate(() => Object.keys(sessionStorage).filter((k) => k.includes("country.economy")));
    assert(keys.length === 0, "kopia gospodarki w sessionStorage: " + keys.join(", "));
  };
  const rpc = (p, method, params) =>
    p.evaluate(async (method, params) => {
      const r = await fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method, params }), credentials: "same-origin" });
      return { status: r.status, body: await r.json() };
    }, method, params);

  const ctxB = await browser.createBrowserContext();
  const pb = await page(ctxB, DESKTOP, "gospodarka-wladca");

  await step("Gospodarka · władca: link w menu i w widoku kraju, wszystkie sekcje z podpowiedziami", async () => {
    await linkLogin(pb, world.U.bolko);
    const nav = await pb.$eval('.nav-item[href="#/gospodarka/c-polanie"]', (a) => a.textContent).catch(() => null);
    assert(nav && nav.includes("Gospodarka kraju"), "pozycja menu");
    await go(pb, "#/kraj/c-polanie");
    const link = await pb.$('.country-head a.eco-link[href="#/gospodarka/c-polanie"]');
    assert(link, "przycisk Gospodarka w widoku kraju");
    await link.click();
    await pb.waitForSelector(".eco-kpis", { timeout: 10000 });
    await wait(200);
    const body = await text(pb, "#main");
    for (const s of [
      "Gospodarka — Królestwo Polan", "Władca", "Miejsca pracy", "3 / 6", "2 stoją", "1 pełna", "Spichlerz", "3,9 dnia", "doba: +1",
      "Braki i ostrzeżenia", "2 problemy", "Kopalnia stoi — pełna skrzynia", "Podpowiedź:", "Zbuduj tartak albo kup drewno u kupca",
      "Co kraj produkuje", "kamieniołom", "Dziś do spichlerza z pól", "rolnik", "Wydajność dzienna", "Szacunek", "2304/dobę",
      "Miejsca pracy i ich stan", "pełna skrzynia — stoi", "a1b2c3d4", "Towar w skrzyniach (razem)", "3002",
      "Magazyny i skrzynie", "pełna — blokuje pracę", "prawie pełna", "Spichlerz i ludność", "możliwy (4 wolne łóżka)", "Jedzenie w skrzyniach",
      "Place budowy", "Brakuje: drewno", "czeka co najmniej 25 min", "kupi na targu za ok. 35 min", "kupuje na targu",
      "Kto wytwarza", "nikt — zbuduj tartak albo kup u kupca", "Tragarze i kursy", "16× bruk", "Ostatnie przeniesienia",
      "Obywatele", "Z domem", "Z pracą", "bezrobotni: 3", "Rodziny (pary)", "Oszczędności łącznie", "Podatek dziennie", "Pensje dziennie",
      "Ostatnia doba", "Dane techniczne",
    ]) assert(body.includes(s), "brak: " + s);
    assert((await pb.$$(".eco-alert-error")).length === 2, "dwa problemy");
    assert((await pb.$$(".eco-store-full")).length === 1, "pełna skrzynia oznaczona");
    assert(await pb.$('.nav-item.active[href="#/gospodarka/c-polanie"]'), "menu podświetlone");
    assert(await pb.$(".eco-alerts .eco-alert-error .ic"), "ikona przy problemie (nie tylko kolor)");
    assert((await pb.$$(".eco-bars .eco-bar-row")).length >= 5, "słupki towarów");
    assert(await pb.$(".charts svg, .eco-tech"), "wykres doby");
    await noLocalCopy(pb);
    await shot(pb, "140-gospodarka-wladca");
  });

  await step("Gospodarka · „Odśwież” prosi serwer o świeże dane (fresh), automatyczne odświeżanie bez fresh", async () => {
    const n = world.economyCalls.length;
    await pb.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.trim() === "Odśwież").click());
    for (let i = 0; i < 60 && world.economyCalls.length === n; i++) await wait(50);
    const c = world.economyCalls.at(-1);
    assert(c && c.fresh === true && c.id === "c-polanie", "fresh: " + JSON.stringify(c));
    await wait(300);
    assert((await text(pb, "#main")).includes("Kopalnia stoi"), "widok po odświeżeniu");
  });

  await step("Gospodarka · władca obcego kraju: karta „Brak dostępu”, bez danych; bezpośrednie RPC też odmawia", async () => {
    await go(pb, "#/gospodarka/c-wislanie");
    const body = await text(pb, "#main");
    assert(body.includes("Brak dostępu") && body.includes("widzą ją tylko władca i doradcy"), "karta odmowy");
    assert(!(await pb.$(".eco-kpis")), "bez kafelków");
    assert(!body.includes("Wiślanie") || !body.includes("Spichlerz"), "bez danych obcego kraju");
    const r = await rpc(pb, "country.economy", { id: "c-wislanie" });
    assert(r.status === 200 && r.body.ok === false && r.body.error === DENIED && r.body.data === undefined, "RPC: " + JSON.stringify(r));
    const last = world.economyCalls.filter((c) => c.id === "c-wislanie").at(-1);
    assert(last && last.access === null, "serwer odmówił");
    await noLocalCopy(pb);
    await shot(pb, "141-gospodarka-odmowa-obcy-kraj");
  });
  await ctxB.close();

  await step("Gospodarka · doradca na telefonie 390 px: tylko podgląd, bez przewijania w bok", async () => {
    const ctx = await browser.createBrowserContext();
    const p = await page(ctx, MOBILE, "gospodarka-doradca-390");
    await linkLogin(p, world.U.dobrawa);
    await go(p, "#/gospodarka/c-polanie");
    await p.waitForSelector(".eco-kpis");
    const body = await text(p, "#main");
    assert(body.includes("Doradca — podgląd"), "rola doradcy");
    assert(body.includes("Kopalnia stoi — pełna skrzynia"), "dane");
    await noOverflow(p, "doradca 390");
    await shot(p, "142-gospodarka-doradca-390");
    await ctx.close();
  });

  await step("Gospodarka · obywatel i gracz bez kraju: brak pozycji menu, brak przycisku, odmowa", async () => {
    for (const u of [world.U.siemowit, world.U.wedrowiec]) {
      const ctx = await browser.createBrowserContext();
      const p = await page(ctx, DESKTOP, "gospodarka-" + u.name);
      await linkLogin(p, u);
      assert(!(await p.$('.nav-item[href^="#/gospodarka"]')), u.name + ": pozycja menu");
      await go(p, "#/kraj/c-polanie");
      assert(!(await p.$(".country-head a.eco-link")), u.name + ": przycisk w widoku kraju");
      await go(p, "#/gospodarka/c-polanie");
      const body = await text(p, "#main");
      assert(body.includes("Brak dostępu") && !(await p.$(".eco-kpis")), u.name + ": odmowa");
      await go(p, "#/gospodarka");
      assert((await text(p, "#main")).includes("Nie masz dostępu do żadnego kraju"), u.name + ": lista pusta");
      if (u === world.U.siemowit) await shot(p, "143-gospodarka-obywatel-odmowa");
      await ctx.close();
    }
  });

  await step("Gospodarka · admin: lista krajów, kraj NPC bez produkcji, nowe pola Ludnosci w danych technicznych", async () => {
    const ctx = await browser.createBrowserContext();
    const p = await page(ctx, DESKTOP, "gospodarka-admin");
    await linkLogin(p, world.U.maciej);
    assert(await p.$('.nav-item[href="#/gospodarka"]'), "admin: menu prowadzi do listy");
    await go(p, "#/gospodarka");
    const list = await text(p, "#main");
    // „Ruś Czerwona” jest usuwana wcześniej w pełnym przebiegu (admin.country.delete) — sprawdzana tylko, gdy istnieje.
    for (const n of ["Królestwo Polan", "Wiślanie", "Księstwo Mazowsze", ...(world.C["npc-rus"] ? ["Ruś Czerwona"] : [])]) assert(list.includes(n), "lista: " + n);
    await shot(p, "144-gospodarka-admin-lista");
    await go(p, "#/gospodarka/npc-mazowsze");
    const body = await text(p, "#main");
    assert(body.includes("Administrator — każdy kraj") && body.includes("Kraj nic nie wytwarza") && body.includes("NPC"), "kraj NPC");
    await p.evaluate(() => (document.querySelector(".eco-tech").open = true));
    const tech = await text(p, ".eco-tech");
    assert(tech.includes("chains") && tech.includes("szkło") && tech.includes("wersja 2"), "nowe pola: " + tech);
    await shot(p, "145-gospodarka-admin-npc");
    await ctx.close();
  });

  await step("Gospodarka · WorldSteward bez roli admina (zarządca osobisty): każdy kraj", async () => {
    const was = world.U.maciej.admin;
    world.U.maciej.admin = false;
    try {
      const ctx = await browser.createBrowserContext();
      const p = await page(ctx, DESKTOP, "gospodarka-wlasciciel");
      await linkLogin(p, world.U.maciej);
      await go(p, "#/gospodarka/c-polanie");
      await p.waitForSelector(".eco-kpis");
      assert((await text(p, "#main")).includes("Zarządca Skarbu Świata — każdy kraj"), "rola właściciela");
      assert(world.economyCalls.at(-1).access === "OWNER", "serwer: OWNER");
      await shot(p, "146-gospodarka-wlasciciel");
      await ctx.close();
    } finally {
      world.U.maciej.admin = was;
    }
  });

  await step("Gospodarka · zarządca kraju NPC: swój kraj NPC tak, kraj gracza nie", async () => {
    const ctx = await browser.createBrowserContext();
    const p = await page(ctx, MOBILE, "gospodarka-zarzadca");
    await linkLogin(p, world.U.ziemowit);
    await go(p, "#/gospodarka/npc-mazowsze");
    assert((await text(p, "#main")).includes("Zarządca państwa NPC"), "zarządca NPC");
    await noOverflow(p, "zarządca 390");
    await shot(p, "147-gospodarka-zarzadca-npc-390");
    await go(p, "#/gospodarka/c-polanie");
    assert((await text(p, "#main")).includes("Brak dostępu"), "kraj gracza: odmowa");
    await ctx.close();
  });
}
