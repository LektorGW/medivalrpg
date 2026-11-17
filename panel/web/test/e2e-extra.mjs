// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E: nastrój mieszkańców (plugin Zycie: kafelek i karta w przeglądzie kraju i państwa NPC, „Ogłoś festyn”)
// oraz wymuszenia budowy w zakładce Budowy (przebudowa miasta z podglądem, drogi pod epokę, modernizacja,
// plakietka „do rozbiórki (plan v2)”). Zrzuty 15-, 73-, 74-.
export async function extraSteps(E) {
  const { browser, step, assert, eq, page, linkLogin, go, shot, text, texts, clickButton, toastText, lastCall, world, DESKTOP } = E;
  const clean = (p) => p.evaluate(() => document.querySelectorAll(".toast").forEach((t) => t.remove()));

  await step("Nastrój: kafelek i karta w przeglądzie kraju gracza (czynniki +/−, zmartwienia, bez festynu)", async () => {
    const ctx = await browser.createBrowserContext();
    const p = await page(ctx, DESKTOP, "nastroj-gracz");
    await linkLogin(p, world.U.bolko);
    await go(p, "#/kraj/c-polanie");
    assert((await text(p, ".stat-mood")).includes("62/100"), "kafelek nastroju");
    const cardText = await p.evaluate(() => [...document.querySelectorAll(".card")].find((c) => c.querySelector(".card-title")?.textContent === "Nastrój mieszkańców")?.textContent || "");
    assert(cardText.includes("Spokój") && cardText.includes("Jedzenie") && cardText.includes("+9") && cardText.includes("−7"), "czynniki: " + cardText);
    assert(cardText.includes("Brakuje karczmy"), "zmartwienia");
    assert(!(await p.$(".mood-badge")), "bez plakietek");
    assert(!cardText.includes("Ogłoś festyn"), "gracz nie ogłasza festynu");
    await clean(p);
    await shot(p, "15-kraj-nastroj-desktop");
    await go(p, "#/kraj/c-wislanie");
    assert((await text(p, ".stat-mood")).includes("Festyn"), "plakietka Festyn");
    await ctx.close();
  });

  const ctxA = await browser.createBrowserContext();
  const a = await page(ctxA, DESKTOP, "nastroj-admin");
  await step("Nastrój państwa NPC: plakietka „Protest”, przycisk „Ogłoś festyn” (admin.npc.festival)", async () => {
    await linkLogin(a, world.U.maciej);
    await go(a, "#/npc/npc-mazowsze");
    assert(await a.$(".mood-unrest"), "plakietka Protest");
    assert((await text(a, ".stat-mood")).includes("31/100"), "kafelek NPC");
    await clean(a);
    await shot(a, "73-npc-nastroj-desktop");
    await clickButton(a, "Ogłoś festyn");
    const t = await toastText(a);
    assert(t.includes("Ogłoszono festyn"), "toast: " + t);
    eq(lastCall("admin.npc.festival").params, { id: "npc-mazowsze" }, "festival params");
    await a.waitForFunction(() => !!document.querySelector(".mood-fest"), { timeout: 6000 });
  });

  await step("Budowy NPC: plakietka „do rozbiórki (plan v2)” i wymuszenia (przebudowa z podglądem, drogi, modernizacja)", async () => {
    await go(a, "#/npc/npc-mazowsze/budowy");
    assert((await text(a, ".building-list")).includes("do rozbiórki (plan v2)"), "plakietka rozbiórki");
    await a.evaluate(() => document.querySelector(".force-item input[type=checkbox]").click());
    await clickButton(a, "Wymuś przebudowę miasta");
    await a.waitForSelector("dialog[open]");
    assert((await text(a, "dialog[open] .modal-body")).includes("plan v2"), "opis przebudowy");
    await clean(a);
    await a.screenshot({ path: E.SHOTS + "/74-npc-wymuszenia-desktop.png" });
    await clickButton(a, "Wymuś przebudowę", "dialog[open]");
    let t = await toastText(a);
    assert(t.includes("14 zleceń"), "toast przebudowy: " + t);
    eq(lastCall("admin.npc.forceRedesign").params, { id: "npc-mazowsze", free: true }, "forceRedesign params");
    await clickButton(a, "Przebuduj drogi pod epokę");
    await a.waitForSelector("dialog[open]");
    await clickButton(a, "Przebuduj drogi", "dialog[open]");
    t = await toastText(a);
    assert(t.includes("dróg"), "toast dróg: " + t);
    await clickButton(a, "Modernizuj wszystkie budynki");
    await a.waitForSelector("dialog[open]");
    await clickButton(a, "Modernizuj", "dialog[open]");
    t = await toastText(a);
    assert(t.includes("5 budynków"), "toast modernizacji: " + t);
    eq(world.forced.map((f) => f.m), ["redesign", "roads", "modernize"], "wywołania Ludnosc");
    await ctxA.close();
  });
}
