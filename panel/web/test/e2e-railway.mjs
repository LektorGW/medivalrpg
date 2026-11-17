// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
export async function railwaySteps(E) {
  const { browser, step, assert, eq, page, linkLogin, go, shot, text, lastCall, world, DESKTOP, MOBILE } = E;
  const context = await browser.createBrowserContext();
  const p = await page(context, DESKTOP, "kolej");
  await step("Kolej: osobisty zarządca widzi roboty, linie, tabor i zgody", async () => {
    await linkLogin(p, world.U.maciej);
    await go(p, "#/kolej");
    assert(await p.$('a[href="#/kolej"]'), "menu kolei");
    const body = await text(p, "#main");
    for (const label of ["Dyspozytornia kolei", "Roboty na sieci", "Linie i rozkład", "Tabor", "Maszyniści NPC", "Zgody państw"]) assert(body.includes(label), label);
    assert(body.includes("<script>bad()</script>"), "przyczyna wyświetlana jako tekst");
    assert(!(await p.$("#main script")), "brak wykonania HTML z powodu blokady");
    await shot(p, "110-kolej");
  });
  await step("Kolej: wznowienie wysyła tylko id i odświeża stan", async () => {
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find(b => b.textContent === "Wznów").click());
    await p.waitForFunction(() => ![...document.querySelectorAll("#main button")].some(b => b.textContent === "Wznów"));
    eq(lastCall("railway.work.resume").params, { id: "tor" });
  });
  await step("Kolej: zmiana biletu nie nadpisuje rozkładu", async () => {
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find(b => b.textContent === "Ustawienia").click());
    await p.waitForSelector('input[placeholder="np. 30 albo 08:00,12:00"]');
    eq(await p.$eval('input[placeholder="np. 30 albo 08:00,12:00"]', el => el.value), "");
    const saved = p.waitForResponse(r => r.url().endsWith("/api/rpc") && r.request().postData()?.includes("railway.line.configure"));
    await p.evaluate(() => [...document.querySelectorAll("button")].find(b => b.textContent === "Zapisz").click());
    await saved;
    await p.waitForFunction(() => !document.querySelector('input[placeholder="np. 30 albo 08:00,12:00"]'));
    eq(lastCall("railway.line.configure").params.interval, "");
  });
  await step("Kolej: mapa pokazuje rzeczywisty łamany odcinek i rozdziela światy", async () => {
    await p.reload({ waitUntil: "networkidle0" });
    await p.waitForSelector("svg.railway-network");
    const map = await p.$('svg[aria-label="Mapa sieci: world"]');
    assert(map, "mapa grafu");
    eq(await map.$$eval("polyline", es => es.map(e => e.getAttribute("points").split(" ").length)), [3]);
    assert(!(await p.$('svg [aria-label^="Nether"]')), "brak węzła obcego świata");
    await p.select('select[aria-label="Świat mapy kolei"]', "world_nether");
    assert(await p.$('svg [aria-label^="Nether"]'), "wybrany świat");
    eq(await p.$$eval("svg.railway-network polyline", es => es.length), 0);
    await p.select('select[aria-label="Świat mapy kolei"]', "world");
  });
  await step("Kolej: wniosek nie buduje; dopiero wycena i osobne potwierdzenie wysyła token", async () => {
    const initial = world.railway.works.length;
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find(b => b.textContent === "Nowa stacja").click());
    await p.waitForSelector('dialog input[placeholder="np. stacja_polnoc"]');
    await p.type('dialog input[placeholder="np. stacja_polnoc"]', "nowa_stacja");
    await p.evaluate(() => [...document.querySelectorAll("dialog button")].find(b => b.textContent === "Zapisz").click());
    await p.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Do wyceny"));
    eq(lastCall("railway.request.create").params, { id: "nowa_stacja", rotation: "0", kind: "station" });
    eq(world.railway.works.length, initial);
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find(b => b.textContent === "Wyceń").click());
    await p.waitForFunction(() => [...document.querySelectorAll("#main button")].some(b => b.textContent === "Potwierdź budowę"));
    eq(world.railway.works.length, initial);
    await p.evaluate(() => [...document.querySelectorAll("#main button")].find(b => b.textContent === "Potwierdź budowę").click());
    await p.waitForSelector("dialog");
    assert((await text(p, "dialog")).includes("1234 md"), "pokazany koszt");
    eq(world.railway.works.length, initial);
    await p.evaluate(() => [...document.querySelectorAll("dialog button")].find(b => b.textContent === "Opłać i zleć budowę").click());
    await p.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Zlecono budowę"));
    eq(lastCall("railway.request.confirm").params, { id: "nowa_stacja", token: "server-quote-token" });
    eq(world.railway.works.length, initial + 1);
    const replay = await p.evaluate(async () => (await fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method: "railway.request.confirm", params: { id: "nowa_stacja", token: "server-quote-token" } }) })).json());
    eq(replay.ok, false);
    eq(world.railway.works.length, initial + 1);
  });
  await step("Kolej: mobilny panel mieści się w 390 px", async () => {
    await p.setViewport(MOBILE);
    await go(p, "#/kolej");
    assert(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), "poziomy overflow");
    await shot(p, "111-kolej-390");
  });
  await step("Kolej: niejednoznaczna płatność blokuje ponowne zlecenie z panelu", async () => {
    world.railway.requests.push({ id: "sporna", kind: "rail", from: "baza", to: "spawn", world: "world", state: "RECONCILE", reason: "Sprawdź dziennik banku; bez ponownej opłaty", job: "held", price: 1234, blocks: 150, expires: 0, token: "" });
    world.railway.works.push({ id: "held", status: "HELD", reason: "Kontrola płatności", done: 0, total: 150, cost: 1234 });
    await p.reload({ waitUntil: "networkidle0" });
    await p.waitForSelector("svg.railway-network");
    const row = await p.evaluate(() => [...document.querySelectorAll("tr")].find(r => r.textContent.includes("sporna"))?.textContent);
    assert(row?.includes("Wymaga kontroli transakcji"), "widoczna blokada");
    assert(!row.includes("Wyceń") && !row.includes("Potwierdź budowę") && !row.includes("Odrzuć wniosek"), "brak ponownego zlecenia");
    assert((await text(p, "#main")).includes("Oczekiwanie na potwierdzenie transakcji"), "opis HELD");
  });
  await context.close();
  const other = await browser.createBrowserContext();
  const q = await page(other, DESKTOP, "kolej-inny-admin");
  await step("Kolej: inny administrator nie dostaje dyspozytorni", async () => {
    const u = world.U.wedrowiec;
    u.admin = true;
    await linkLogin(q, u);
    assert(!(await q.$('a[href="#/kolej"]')), "brak menu mimo admina");
    await go(q, "#/kolej");
    assert((await text(q, "#main")).includes("Brak dostępu"), "odmowa wejścia bez UUID zarządcy");
    for (const method of ["railway.request.create", "railway.request.quote", "railway.request.confirm", "railway.request.cancel"]) {
      const response = await q.evaluate(async method => (await fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method, params: { id: "nowa_stacja", kind: "station", rotation: "0", token: "server-quote-token" } }) })).json(), method);
      eq(response.ok, false, method);
    }
  });
  await other.close();
}
