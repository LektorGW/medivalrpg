// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E: Administracja → „Wydajność serwera” (admin.health): kafelki z progami, baner ostrzeżeń, wykresy z osią
// godzin, progami i pasmami ostrzeżeń, NPC według pluginów i krajów, przełącznik zakresu, dostęp tylko dla admina,
// telefon 390 px. Zrzuty 30-…
export async function healthSteps(E) {
  const { browser, step, assert, eq, page, linkLogin, go, shot, text, texts, lastCall, world, DESKTOP, MOBILE, sleep } = E;

  const ctxA = await browser.createBrowserContext();
  const a = await page(ctxA, DESKTOP, "wydajnosc-admin");

  await step("Wydajność: menu admina, kafelki z progami, baner ostrzeżenia, 6 wykresów z progami i pasmami", async () => {
    await linkLogin(a, world.U.maciej);
    assert(await a.$('#sidebar a.nav-item[href="#/admin/serwer"]'), "pozycja menu „Wydajność serwera”");
    await go(a, "#/admin/serwer");
    eq(lastCall("admin.health").params, { range: "1h" }, "domyślny zakres");
    assert((await text(a, "#main .tabs .tab.active")).includes("Wydajność serwera"), "aktywna zakładka");
    const labels = await texts(a, ".health-stat .stat-label");
    eq(labels, ["TPS", "MSPT", "Gracze", "NPC pluginów", "Encje", "Chunki"], "kafelki");
    const banner = await text(a, ".health-banner");
    assert(banner.includes("Ostrzeżenia wydajności") && banner.includes("Dużo NPC (Armia)"), "baner: " + banner);
    const titles = await texts(a, ".card .chart-title");
    for (const t of ["TPS", "MSPT (ms)", "Gracze online", "NPC pluginów (razem)", "Encje", "Chunki", "Ludność", "Armia", "Skarbiec", "Życie"]) assert(titles.includes(t), "wykres " + t + ": " + titles.join("|"));
    assert(!titles.includes("Władcy"), "plugin bez NPC nie ma wykresu");
    for (const transport of ["Kolej", "Szlaki"]) assert(titles.includes(transport), "wykres transportu " + transport);
    const kindsText = await text(a, ".health-kinds");
    for (const label of ["Budowniczowie kolei", "Maszyniści", "Woźnice i szyprowie", "Zwierzęta transportowe"]) assert(kindsText.includes(label), "rodzaj transportu " + label);
    assert((await text(a, ".health-idle")).includes("Władcy"), "informacja o pluginach bez NPC");
    assert((await a.$$(".chart-threshold")).length >= 5, "linie progów");
    assert((await a.$$(".chart-warn-band")).length >= 2, "pasma ostrzeżeń (spadek TPS/MSPT)");
    assert(await a.$(".chart-warn .chart-warn-badge"), "wykres Armii z plakietką progu");
    const ticks = await a.$$eval(".chart-tick", (es) => es.map((e) => e.textContent).filter((s) => /^\d\d:\d\d$/.test(s)));
    assert(ticks.length >= 4, "oś godzin: " + ticks.join(","));
    const countries = await text(a, "#main");
    assert(countries.includes("Księstwo Mazowsze") && countries.includes("(usunięty kraj)"), "NPC według krajów");
    assert(countries.includes("Ostrzeżenia — ostatnia doba") && countries.includes("trwa"), "historia ostrzeżeń");
    await shot(a, "30-wydajnosc-desktop");
  });

  await step("Wydajność: podpowiedź z ostrzeżeniem (klawiatura), zakres 24 h i 6 h", async () => {
    await a.evaluate(() => document.querySelector(".chart-svg").focus());
    for (let i = 0; i < 230; i++) await a.keyboard.press("ArrowLeft"); // ~38 min temu, w środku spadku
    const tip = await text(a, ".chart-tip");
    assert(tip.includes("TPS") && tip.includes("⚠"), "podpowiedź: " + tip);
    await a.keyboard.press("Escape");
    await a.click('.health-range a[href="#/admin/serwer?zakres=24h"]');
    await a.waitForFunction(() => document.querySelector(".health-range .seg-btn.on")?.textContent === "24 h", { timeout: 8000 });
    await sleep(200);
    eq(lastCall("admin.health").params, { range: "24h" }, "zakres 24 h");
    assert((await text(a, "#main")).includes("ostatnia doba"), "opis okna");
    assert((await text(a, ".health-toolbar")).includes("punkt wykresu = 2 min"), "rozdzielczość");
    await shot(a, "31-wydajnosc-24h-desktop");
    await go(a, "#/admin/serwer?zakres=6h");
    eq(lastCall("admin.health").params, { range: "6h" }, "zakres 6 h");
    await a.evaluate(() => (document.documentElement.dataset.theme = "light"));
    await shot(a, "33-wydajnosc-6h-jasny-desktop");
    await a.evaluate(() => (document.documentElement.dataset.theme = "dark"));
  });

  await step("Wydajność: tylko admin (gracz i zarządca NPC → brak dostępu, bez pozycji menu)", async () => {
    for (const u of [world.U.bolko, world.U.ziemowit]) {
      const ctx = await browser.createBrowserContext();
      const p = await page(ctx, DESKTOP, "wydajnosc-" + u.name);
      await linkLogin(p, u);
      assert(!(await p.$('#sidebar a.nav-item[href="#/admin/serwer"]')), "brak pozycji menu dla " + u.name);
      const calls = world.calls.length;
      await go(p, "#/admin/serwer");
      assert((await text(p, "#main")).includes("Brak dostępu"), "brak dostępu dla " + u.name);
      assert(!world.calls.slice(calls).some((c) => c.method === "admin.health"), "SPA nie pyta pluginu bez roli admina");
      await ctx.close();
    }
  });

  await step("Wydajność 390 px: bez przewijania w bok, kafelki i wykresy", async () => {
    const ctx = await browser.createBrowserContext();
    const m = await page(ctx, MOBILE, "wydajnosc-390");
    await linkLogin(m, world.U.maciej);
    await go(m, "#/admin/serwer");
    const overflow = await m.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(overflow <= 1, `przewijanie w bok o ${overflow}px`);
    assert((await m.$$(".chart-svg")).length >= 8, "wykresy narysowane");
    await shot(m, "32-wydajnosc-390");
    await ctx.close();
  });

  await ctxA.close();
}
