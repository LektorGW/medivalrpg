// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E: Firmy graczy (#/firmy, #/firmy/<id>, #/firmy/zarzad) i kredyty firm ze Skarbu Świata (#/skarbswiata).
// Zrzuty 160-…169-; samo E2E_FIRMY_ONLY=1. Atrapa: test/fake-firmy.mjs (właściciel Firm = WorldSteward = W.railwayManager).
// Sprawdza: ranking i sortowanie (parametr RPC + hash), kartę publiczną i członka (saldo, księga ze stronami, sklepy,
// magazyn, kontrakty, kredyty), zarząd właściciela (zamrożenie, spór), kredyty Skarbu Świata (Zatwierdź/Odrzuć/zwrot
// z modalem → parametry RPC), odmowy (zwykły gracz, drugi admin — widok i bezpośrednie RPC), brak kopii w sessionStorage,
// Firmy wyłączone / etap 2 niewdrożony oraz 390 px bez przewijania w bok.

const ORDER = {
  obrot: ["Kompania Bursztynowa", "Kuźnia Gnieźnieńska", "Młyn nad Wartą", "Karczma Pod Złotym Kłosem", "Browar Książęcy"],
  majatek: ["Młyn nad Wartą", "Kuźnia Gnieźnieńska", "Kompania Bursztynowa", "Browar Książęcy", "Karczma Pod Złotym Kłosem"],
  ludzie: ["Kompania Bursztynowa", "Kuźnia Gnieźnieńska", "Browar Książęcy", "Karczma Pod Złotym Kłosem", "Młyn nad Wartą"],
  kontrakty: ["Młyn nad Wartą", "Kuźnia Gnieźnieńska", "Kompania Bursztynowa", "Karczma Pod Złotym Kłosem", "Browar Książęcy"],
};
const KUZNIA = "0a1b2c3d4e";
const KOMPANIA = "2c3d4e5f60";
const LOANS_TITLE = "Kredyty firm ze Skarbu Świata (wymagają zgody)";

export async function firmySteps(E) {
  const { browser, step, assert, eq, page, linkLogin, go, shot, text, toastText, world, DESKTOP, MOBILE, sleep } = E;
  const wait = sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const flat = (s) => String(s).replace(/[\s  ]/g, "");
  const callsSince = (n, method) => world.calls.slice(n).filter((c) => c.method === method);
  async function waitCall(n, method, pred = () => true, timeout = 8000) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
      const c = callsSince(n, method).filter(pred).at(-1);
      if (c) return c;
      await wait(60);
    }
    throw new Error("brak wywołania " + method + "; ostatnie: " + JSON.stringify(callsSince(n, method).at(-1)?.params));
  }
  const rpc = (pg, method, params = {}) =>
    pg.evaluate((method, params) => fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method, params }), credentials: "same-origin" }).then((x) => x.json()), method, params);
  const clickText = (pg, sel, label) => pg.evaluate((sel, label) => { const b = [...document.querySelectorAll(sel)].find((x) => x.textContent.trim() === label && !x.disabled); if (b) b.click(); return !!b; }, sel, label);
  // Ten sam hash nie wywołuje hashchange — przejście przez pulpit wymusza świeży odczyt.
  const reopen = async (p, hash) => {
    if (await p.evaluate((h) => location.hash === h, hash)) await go(p, "#/");
    await go(p, hash);
  };
  const names = (p) => p.$$eval("#main .ranking tbody .rank-name a", (as) => as.map((a) => a.textContent.trim()));
  const headers = (p) => p.$$eval("#main th", (ths) => ths.map((t) => t.textContent.trim()));
  const noOverflow = async (p, label) => {
    const o = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(o <= 1, `${label}: przewijanie w bok o ${o}px`);
  };
  const noCopies = async (p) => {
    const keys = await p.evaluate(() => Object.keys(sessionStorage).filter((k) => k.includes("firmy.") || k.includes("world.")));
    eq(keys, [], "kopie firmy.*/world.* w sessionStorage");
  };
  /** Sekcja-karta o danym tytule (h3.card-title). */
  const cardText = (p, title) => p.evaluate((t) => [...document.querySelectorAll("#main section.card")].find((s) => s.querySelector(":scope > h3.card-title")?.textContent === t)?.textContent ?? null, title);
  const toast = async (p) => (toastText ? toastText(p) : (await p.waitForSelector(".toast", { timeout: 6000 }), (await text(p, ".toast")).trim()));

  const prevWedrowiecAdmin = world.U.wedrowiec.admin;
  world.U.wedrowiec.admin = false;
  world.firmyAvailable = true;
  world.firmyStage2 = true;

  // ---------------------------------------------------------------- zwykły gracz (Bolko — mistrz Kuźni)
  const ctxB = await browser.createBrowserContext();
  const pb = await page(ctxB, DESKTOP, "firmy-gracz");

  await step("Firmy: ranking publiczny — kolejność wg obrotu, herby, stany, „Twoja”, bez salda i bez zarządu", async () => {
    await linkLogin(pb, world.U.bolko);
    assert(await pb.$('a.nav-item[href="#/firmy"]'), "pozycja „Firmy” w menu");
    const n = world.calls.length;
    await reopen(pb, "#/firmy");
    eq((await waitCall(n, "firmy.list")).params, { sort: "obrot" }, "parametry rankingu");
    eq(await names(pb), ORDER.obrot, "kolejność wg obrotu");
    const body = await text(pb, "#main");
    for (const s of ["Firmy", "Ranking firm", "Obrót 30 dni", "Kontrakty", "Królewski przewoźnik", "poza rankingiem", "uśpiona", "niewypłacalna", "czynna", "Kompania kupiecka", "Królestwo Polan"]) assert(body.includes(s), s);
    assert(!body.includes("Tkalnia Dobrawy"), "zlikwidowana firma poza rankingiem");
    assert(!(await headers(pb)).includes("Saldo"), "gracz nie widzi kolumny salda");
    assert(!(await pb.$('a.tab[href="#/firmy/zarzad"]')), "brak zakładki zarządu");
    const mine = await pb.$$eval("#main .ranking tbody tr", (rs) => rs.filter((r) => r.querySelector(".tag-mine")).map((r) => r.querySelector(".rank-name a").textContent.trim()));
    eq(mine, ["Kuźnia Gnieźnieńska"], "oznaczenie własnej firmy");
    eq((await pb.$$('#main .ranking tbody [role="img"][aria-label^="Herb"]')).length, 5, "herby");
    assert(await pb.$('.nav-item.active[href="#/firmy"]'), "menu podświetlone");
    await noCopies(pb);
    await shot(pb, "160-firmy-ranking");
  });

  await step("Firmy: sortowanie — Majątek / Ludzie / Kontrakty zmieniają RPC, hash i kolejność", async () => {
    for (const sort of ["majatek", "ludzie", "kontrakty"]) {
      const n = world.calls.length;
      await pb.click(`#main .seg-btn[data-sort="${sort}"]`);
      eq((await waitCall(n, "firmy.list", (c) => c.params.sort === sort)).params, { sort }, "parametr " + sort);
      await pb.waitForFunction((s) => location.hash === "#/firmy?sort=" + s, {}, sort);
      await pb.waitForFunction((s) => document.querySelector(`#main .seg-btn.on[data-sort="${s}"]`), {}, sort);
      await wait(150);
      eq(await names(pb), ORDER[sort], "kolejność " + sort);
      if (sort === "majatek") await shot(pb, "161-firmy-ranking-majatek");
    }
    const n = world.calls.length;
    await pb.click('#main .seg-btn[data-sort="obrot"]');
    await waitCall(n, "firmy.list", (c) => c.params.sort === "obrot");
    await pb.waitForFunction(() => location.hash === "#/firmy");
  });

  await step("Firmy: karta publiczna obcej firmy — bez salda i księgi; kontrakty publiczne, kredyty tylko dla członków", async () => {
    const n = world.calls.length;
    await pb.evaluate(() => [...document.querySelectorAll("#main .ranking tbody tr")].find((r) => r.textContent.includes("Kompania Bursztynowa")).querySelector("td:nth-child(4)").click());
    await pb.waitForFunction((id) => location.hash === "#/firmy/" + id, {}, KOMPANIA);
    await pb.waitForSelector("#main .page-head h1");
    eq(await text(pb, "#main .page-head h1"), "Kompania Bursztynowa", "nagłówek");
    for (const m of ["firmy.get", "firmy.contracts", "firmy.loans"]) eq((await waitCall(n, m)).params, { id: KOMPANIA }, m);
    const body = await text(pb, "#main");
    for (const s of ["Widok publiczny — saldo i księgę widzą członkowie firmy.", "Kompania kupiecka", "siedziba: Wiślanie", "Wedrowiec", "Mistrz", "Czeladnik", "Karawana do Krakowa", "w realizacji"]) assert(body.includes(s), s);
    for (const leak of ["Księga firmy", "Oczekujące dywidendy", "Sklepy firmy", "Magazyn", "Zaległe pensje", "Wspólny adres IP"]) assert(!body.includes(leak), "wyciek: " + leak);
    assert(!(await pb.$("#main .grid-stats")), "brak statystyk salda");
    const people = await pb.evaluate(() => [...document.querySelectorAll("#main section.card")].find((s) => s.querySelector("h3")?.textContent === "Ludzie").querySelectorAll("th").length);
    eq(people, 2, "publiczna lista ludzi: tylko gracz i rola");
    assert((await cardText(pb, "Kredyty")).includes("Kredyty firmy widzą jej członkowie."), "kredyty — odmowa Firm");
    assert(!(await pb.evaluate(() => [...document.querySelectorAll("#main section.card")].find((s) => s.querySelector("h3")?.textContent === "Kredyty")?.querySelector(".alert-warn"))), "odmowa kredytów jako szary dopisek, nie ostrzeżenie");
    const g = (await rpc(pb, "firmy.get", { id: KOMPANIA })).data;
    for (const k of ["balance", "journal", "payouts", "shops", "warehouse", "sharedAddresses"]) assert(!(k in g), "publiczny odczyt zawiera " + k);
    assert(g.people.every((x) => !("share" in x) && !("uuid" in x)), "publiczni ludzie bez udziałów");
    const c = (await rpc(pb, "firmy.contracts", { id: KOMPANIA })).data;
    assert(c.contracts.every((x) => !("deposit" in x) && !("log" in x)), "publiczne kontrakty bez depozytu");
    await shot(pb, "162-firmy-karta-publiczna");
  });

  await step("Firmy: widok członka (Bolko w Kuźni) — saldo, podatek, dywidendy, wykres, księga ze stronami, sklepy, magazyn, kredyty", async () => {
    await reopen(pb, "#/firmy/" + KUZNIA);
    const body = await text(pb, "#main");
    for (const s of ["Kuźnia Gnieźnieńska", "Saldo", "1250 zł", "Podatek", "10%", "Zaległe pensje", "2 dni z rzędu bez pełnych pensji", "Oczekujące dywidendy", "300 zł", "Przychód dzienny (30 dni)", "Księga firmy", "Sklepy firmy", "sprzedaż", "skup", "IRON_SWORD", "Magazyn", "Kto co wziął", "Siemowit", "Dostawa 64 mieczy", "spór — czeka na administratora", "Limit kredytowy firmy: 2400 zł", "Dobrawa", "czeka na zgodę właściciela", "Udział", "60%", "Pensja dzienna"]) assert(body.includes(s), s);
    assert(!body.includes("Widok publiczny"), "pełny widok");
    assert(!body.includes("Wspólny adres IP"), "wspólne IP widzi tylko właściciel");
    assert(await pb.$("#main .chart-svg, #main svg"), "wykres przychodu");
    eq(await text(pb, "#main .ws-pager-info"), "Strona 1 z 3 · wpisy 1–20 z 45", "pierwsza strona księgi");
    await clickText(pb, "#main .ws-pager button", "Starsze");
    await pb.waitForFunction(() => document.querySelector("#main .ws-pager-info")?.textContent.startsWith("Strona 2 z 3"));
    const journalRows = await pb.evaluate(() => [...document.querySelectorAll("#main section.card")].find((s) => s.querySelector("h3")?.textContent === "Księga firmy").querySelectorAll("tbody tr").length);
    eq(journalRows, 20, "20 wpisów na stronie");
    await clickText(pb, "#main .ws-pager button", "Nowsze");
    await pb.waitForFunction(() => document.querySelector("#main .ws-pager-info")?.textContent.startsWith("Strona 1 z 3"));
    const loans = (await rpc(pb, "firmy.loans", { id: KUZNIA })).data;
    eq([loans.limit, loans.loans.length], [240_000, 2], "kredyty firmy dla członka");
    // Wniosek bez wypłaty (dueAt = 0) nie pokazuje daty 1970 ani „udzielenia”.
    const loanCard = await cardText(pb, "Kredyty");
    assert(!loanCard.includes("1970"), "termin wniosku: " + loanCard);
    await noCopies(pb);
    await shot(pb, "163-firmy-karta-czlonek");
  });
  await ctxB.close();

  // ---------------------------------------------------------------- właściciel (WorldSteward)
  const ctxM = await browser.createBrowserContext();
  const pm = await page(ctxM, DESKTOP, "firmy-wlasciciel");

  await step("Firmy: właściciel — saldo w rankingu, zarząd: wszystkie firmy, wnioski, niepotwierdzone operacje, wspólne IP, spory", async () => {
    await linkLogin(pm, world.U.maciej);
    await reopen(pm, "#/firmy");
    assert((await headers(pm)).includes("Saldo"), "kolumna salda dla właściciela");
    assert(await pm.$('a.tab[href="#/firmy/zarzad"]'), "zakładka zarządu");
    await reopen(pm, "#/firmy/" + KUZNIA);
    const card = await text(pm, "#main");
    assert(card.includes("Wspólny adres IP — bez automatycznych kar.") && card.includes("Dobrawa, Siemowit"), "wspólne IP na karcie");
    const n = world.calls.length;
    await reopen(pm, "#/firmy/zarzad");
    await waitCall(n, "firmy.admin");
    await waitCall(n, "firmy.disputes");
    const body = await text(pm, "#main");
    for (const s of ["Firmy — zarząd", "Saldo wszystkich firm", "5100 zł", "Wnioski", "Niepotwierdzone operacje", "Pensje dzienne — brak potwierdzenia", "Tkalnia Dobrawy", "zlikwidowana", "niewypłacalna przez 14 dni", "zamrożona", "Dobrawa, Siemowit", "Wnioski o założenie firmy", "Cech Złotników", "Spory w kontraktach", "Dostawa 64 mieczy", "Wiślanie ↔ Kuźnia Gnieźnieńska", "Miecze gorszej jakości niż w umowie"]) assert(body.includes(s), s);
    assert(await pm.$('a.tab.active[href="#/firmy/zarzad"]'), "aktywna zakładka zarządu");
    const firstCol = await pm.$$eval("#main section.card-flush tbody tr", (rs) => rs.map((r) => r.querySelector("td a")?.textContent.trim()));
    eq(firstCol[0], "Kompania Bursztynowa", "zarząd sortuje wg obrotu malejąco");
    eq(firstCol.length, 6, "zarząd widzi także zlikwidowane");
    await noCopies(pm);
    await shot(pm, "164-firmy-zarzad");
  });

  await step("Firmy: zamrożenie i odmrożenie z modalem → firmy.admin.freeze {id, frozen, reason}", async () => {
    const row = (name) => `[...document.querySelectorAll("#main section.card-flush tbody tr")].find((r) => r.textContent.includes(${JSON.stringify(name)}))`;
    let n = world.calls.length;
    await pm.evaluate(`${row("Kompania Bursztynowa")}.querySelector(".col-actions button").click()`);
    await pm.waitForSelector(".modal input");
    assert((await text(pm, ".modal")).includes("Zamrozić firmę Kompania Bursztynowa?"), "tytuł modalu");
    await pm.type('.modal input[aria-label="Powód"]', "podejrzenie prania pieniędzy");
    await clickText(pm, ".modal button", "Zamroź");
    eq((await waitCall(n, "firmy.admin.freeze")).params, { id: KOMPANIA, frozen: "true", reason: "podejrzenie prania pieniędzy" }, "parametry zamrożenia");
    assert((await toast(pm)).includes("„Kompania Bursztynowa” zamrożona."), "toast zamrożenia");
    await pm.waitForFunction(new Function(`const r = ${row("Kompania Bursztynowa")}; return r && r.textContent.includes("zamrożona") && r.querySelector(".col-actions button")?.textContent.trim() === "Odmroź";`), { timeout: 8000 });
    n = world.calls.length;
    await pm.evaluate(`${row("Kompania Bursztynowa")}.querySelector(".col-actions button").click()`);
    await pm.waitForSelector(".modal input");
    await clickText(pm, ".modal button", "Odmroź");
    eq((await waitCall(n, "firmy.admin.freeze")).params, { id: KOMPANIA, frozen: "false" }, "odmrożenie bez powodu");
    assert((await toast(pm)).includes("odmrożona"), "toast odmrożenia");
    await pm.waitForFunction(new Function(`const r = ${row("Kompania Bursztynowa")}; return r && r.querySelector(".col-actions button")?.textContent.trim() === "Zamroź";`), { timeout: 8000 });
    // anulowanie niczego nie wysyła
    n = world.calls.length;
    await pm.evaluate(`${row("Młyn nad Wartą")}.querySelector(".col-actions button").click()`);
    await pm.waitForSelector(".modal input");
    await clickText(pm, ".modal button", "Anuluj");
    await pm.waitForFunction(() => !document.querySelector(".modal"));
    eq(callsSince(n, "firmy.admin.freeze").length, 0, "anulowanie bez RPC");
  });

  await step("Firmy: spór — „Na korzyść wykonawcy” z notatką → firmy.dispute.resolve, potem brak otwartych sporów", async () => {
    const n = world.calls.length;
    assert(await clickText(pm, "#main button", "Na korzyść wykonawcy"), "przycisk decyzji");
    await pm.waitForSelector(".modal textarea");
    const m = await text(pm, ".modal");
    for (const s of ["Rozstrzygnąć spór?", "Dostawa 64 mieczy", "Wiślanie ↔ Kuźnia Gnieźnieńska", "Na korzyść wykonawcy", "Tej decyzji nie da się cofnąć."]) assert(m.includes(s), "modal: " + s);
    assert(flat(m).includes("Depozyt:400zł·wadium:50zł"), "depozyt i wadium w modalu");
    await pm.type('.modal textarea[aria-label="Notatka"]', "Oględziny potwierdziły jakość mieczy.");
    await shot(pm, "165-firmy-spor-modal");
    await clickText(pm, ".modal button", "Rozstrzygnij");
    eq((await waitCall(n, "firmy.dispute.resolve")).params, { id: "c0ffee0001", winner: "contractor", note: "Oględziny potwierdziły jakość mieczy." }, "parametry rozstrzygnięcia");
    assert((await toast(pm)).includes("rozstrzygnięty: na korzyść wykonawcy"), "toast");
    await pm.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Brak otwartych sporów."), { timeout: 8000 });
    eq((await rpc(pm, "firmy.dispute.resolve", { id: "c0ffee0001", winner: "client" })).error, "Ten kontrakt nie jest w sporze.", "drugie rozstrzygnięcie odrzucone");
  });

  await step("Kredyty Skarbu Świata: WorldSteward widzi wnioski w #/skarbswiata; Zatwierdź / Odrzuć z modalem → firmy.world.loanDecide", async () => {
    let n = world.calls.length;
    await reopen(pm, "#/skarbswiata");
    await waitCall(n, "firmy.world.loans");
    await pm.waitForFunction((t) => [...document.querySelectorAll("#main section.card")].some((s) => s.querySelector("h3")?.textContent === t && s.querySelector("table")), {}, LOANS_TITLE);
    let card = await cardText(pm, LOANS_TITLE);
    for (const s of ["Wnioski", "Kuźnia Gnieźnieńska", "Kompania Bursztynowa", "Rozbudowa warsztatu: druga kuźnia", "Czynne kredyty", "Młyn nad Wartą", "Spłaty do zwrotu do rezerwy", "nie tworzy nowych monet"]) assert(card.includes(s), s);
    assert(await pm.$(`#main a[href="#/firmy/${KUZNIA}"]`), "odnośnik do karty firmy");
    const loanRow = (firm) => `[...document.querySelectorAll("#main section.card")].find((s) => s.querySelector("h3")?.textContent === ${JSON.stringify(LOANS_TITLE)}).querySelectorAll("tbody tr")`;
    const clickIn = (firm, label) => pm.evaluate(`[...${loanRow(firm)}].find((r) => r.textContent.includes(${JSON.stringify(firm)}) && [...r.querySelectorAll("button")].some((b) => b.textContent.trim() === ${JSON.stringify(label)}))?.querySelectorAll("button").forEach((b) => b.textContent.trim() === ${JSON.stringify(label)} && b.click())`);
    // Zatwierdź (Kuźnia, 2000 zł na 30 dni, 5%)
    n = world.calls.length;
    await clickIn("Kuźnia Gnieźnieńska", "Zatwierdź");
    await pm.waitForSelector(".modal input");
    let m = await text(pm, ".modal");
    for (const s of ["Zatwierdzić kredyt?", "Kuźnia Gnieźnieńska prosi o 2000 zł na 30 dni.", "Odsetki: 100 zł", "do zwrotu razem: 2100 zł", "Cel: Rozbudowa warsztatu: druga kuźnia", "przejdzie z rezerwy Skarbu Świata na konto firmy"]) assert(m.includes(s), "modal: " + s);
    await pm.type('.modal input[aria-label="Notatka"]', "Zgoda na drugą kuźnię");
    await shot(pm, "166-skarb-kredyty-firm");
    assert(await clickText(pm, ".modal button", "Zatwierdź 2000 zł"), "przycisk zatwierdzenia w modalu");
    eq((await waitCall(n, "firmy.world.loanDecide")).params, { id: "aa11bb22cc", approve: "true", note: "Zgoda na drugą kuźnię" }, "parametry zatwierdzenia");
    assert((await toast(pm)).includes("Kredyt 2000 zł dla „Kuźnia Gnieźnieńska” wypłacony."), "toast zatwierdzenia");
    // Odrzuć (Kompania) bez notatki
    await pm.waitForFunction(new Function(`return [...${loanRow()}].filter((r) => r.textContent.includes("Zatwierdź")).length === 1;`), { timeout: 8000 });
    n = world.calls.length;
    await clickIn("Kompania Bursztynowa", "Odrzuć");
    await pm.waitForSelector(".modal input");
    m = await text(pm, ".modal");
    assert(m.includes("Odrzucić wniosek?") && m.includes("Kompania Bursztynowa prosi o 1500 zł na 14 dni."), "modal odrzucenia");
    assert(!m.includes("przejdzie z rezerwy"), "odrzucenie nie przelewa");
    assert(await clickText(pm, ".modal button", "Odrzuć"), "przycisk odrzucenia w modalu");
    eq((await waitCall(n, "firmy.world.loanDecide")).params, { id: "dd33ee44ff", approve: "false", note: "" }, "parametry odrzucenia");
    assert((await toast(pm)).includes("Wniosek odrzucony."), "toast odrzucenia");
    await pm.waitForFunction((t) => [...document.querySelectorAll("#main section.card")].find((s) => s.querySelector("h3")?.textContent === t)?.textContent.includes("Brak wniosków o kredyt."), { timeout: 8000 }, LOANS_TITLE);
    card = await cardText(pm, LOANS_TITLE);
    assert(card.includes("Kuźnia Gnieźnieńska") && card.includes("spłacany"), "zatwierdzony kredyt wśród czynnych");
    // ponowna decyzja o tym samym wniosku jest odrzucana przez Firmy
    eq((await rpc(pm, "firmy.world.loanDecide", { id: "aa11bb22cc", approve: "true" })).error, "Brak takiego wniosku.", "drugie zatwierdzenie");
    // Zwrot spłat do rezerwy (Młyn, 400 zł)
    n = world.calls.length;
    assert(await clickText(pm, "#main button", "Zwróć spłaty do rezerwy"), "przycisk zwrotu");
    await pm.waitForSelector(".modal .modal-foot");
    assert((await text(pm, ".modal")).includes("Młyn nad Wartą") && flat(await text(pm, ".modal")).includes("(400zł)"), "modal zwrotu");
    await clickText(pm, ".modal button", "Zwróć do rezerwy");
    eq((await waitCall(n, "firmy.world.loanSweep")).params, { id: "1122334455" }, "parametry zwrotu");
    assert((await toast(pm)).includes("Zwrócono 400 zł do rezerwy Skarbu Świata."), "toast zwrotu");
    await pm.waitForFunction((t) => [...document.querySelectorAll("#main section.card")].find((s) => s.querySelector("h3")?.textContent === t)?.textContent.includes("Nic do zwrotu."), { timeout: 8000 }, LOANS_TITLE);
    // karta firmy pokazuje kredyt jako spłacany, saldo wzrosło o kwotę kredytu
    await reopen(pm, "#/firmy/" + KUZNIA);
    const body = await text(pm, "#main");
    assert(body.includes("3250 zł"), "saldo Kuźni po kredycie");
    assert(body.includes("Skarb Świata") && body.includes("spłacany"), "kredyt Skarbu Świata na karcie firmy");
    await noCopies(pm);
  });

  await step("Firmy: Firmy wyłączone → karta „Firmy są niedostępne”; etap 2 niewdrożony → dopisek zamiast kontraktów i kredytów", async () => {
    world.firmyAvailable = false;
    await reopen(pm, "#/firmy");
    let body = await text(pm, "#main");
    assert(body.includes("Firmy są niedostępne") && body.includes("Firmy są obecnie niedostępne."), "karta niedostępności");
    world.firmyAvailable = true;
    world.firmyStage2 = false;
    await reopen(pm, "#/firmy/" + KUZNIA);
    assert((await cardText(pm, "Kontrakty")).includes("Dostępne po wdrożeniu etapu 2 Firm."), "kontrakty: dopisek etapu 2");
    assert((await cardText(pm, "Kredyty")).includes("Dostępne po wdrożeniu etapu 2 Firm."), "kredyty: dopisek etapu 2");
    assert((await text(pm, "#main")).includes("Księga firmy"), "reszta karty działa");
    await reopen(pm, "#/skarbswiata");
    await pm.waitForFunction((t) => [...document.querySelectorAll("#main section.card")].find((s) => s.querySelector("h3")?.textContent === t)?.textContent.includes("Dostępne po wdrożeniu etapu 2 Firm."), { timeout: 8000 }, LOANS_TITLE);
    await reopen(pm, "#/firmy/zarzad");
    body = await text(pm, "#main");
    assert(body.includes("Saldo wszystkich firm") && (await cardText(pm, "Spory w kontraktach")).includes("Dostępne po wdrożeniu etapu 2 Firm."), "zarząd bez sporów etapu 2");
    world.firmyStage2 = true;
  });
  await ctxM.close();

  // ---------------------------------------------------------------- odmowy: zwykły gracz i drugi admin
  for (const [who, user, setup, shotName] of [
    ["zwykły gracz (Bolko)", world.U.bolko, () => {}, null],
    ["drugi administrator (Wedrowiec, mistrz Kompanii)", world.U.wedrowiec, () => (world.U.wedrowiec.admin = true), "167-firmy-brak-dostepu"],
  ]) {
    const c2 = await browser.createBrowserContext();
    const q = await page(c2, DESKTOP, "firmy-odmowa");
    await step(`Firmy: ${who} — brak zarządu i kredytów Skarbu Świata (widok i bezpośrednie RPC)`, async () => {
      setup();
      await linkLogin(q, user);
      assert(!(await q.$('a[href="#/skarbswiata"]')), "brak Skarbu Świata w menu");
      await reopen(q, "#/firmy");
      assert(!(await headers(q)).includes("Saldo"), "brak kolumny salda");
      assert(!(await q.$('a.tab[href="#/firmy/zarzad"]')), "brak zakładki zarządu");
      const list = (await rpc(q, "firmy.list", { sort: "obrot" })).data;
      eq(list.owner, false, "owner=false");
      assert(list.firms.every((f) => !("balance" in f)), "ranking bez sald");
      const n = world.calls.length;
      await reopen(q, "#/firmy/zarzad");
      let body = await text(q, "#main");
      assert(body.includes("Brak dostępu") && body.includes("Zarząd firm prowadzi osobiście WorldSteward."), "karta „Brak dostępu” w zarządzie");
      assert(!(await q.$('a.tab[href="#/firmy/zarzad"]')) && !body.includes("Widoczne tylko dla Ciebie"), "odmowa bez zakładki i podtytułu zarządu");
      for (const leak of ["Saldo wszystkich firm", "Wnioski o założenie firmy", "Cech Złotników", "Niepotwierdzone operacje", "Spory w kontraktach"]) assert(!body.includes(leak), "wyciek: " + leak);
      eq(callsSince(n, "firmy.disputes").length, 0, "po odmowie zarządu SPA nie pyta o spory");
      if (shotName) await shot(q, shotName);
      await reopen(q, "#/skarbswiata");
      body = await text(q, "#main");
      assert(body.includes("Brak dostępu"), "Skarb Świata: odmowa");
      assert(!body.includes("Kredyty firm") && !body.includes("Zatwierdź"), "brak karty kredytów Skarbu Świata");
      eq(callsSince(n, "firmy.world.loans").length, 0, "SPA nie pyta o kredyty Skarbu Świata bez roli");
      const foreign = user === world.U.bolko ? KOMPANIA : KUZNIA;
      await reopen(q, "#/firmy/" + foreign);
      assert((await cardText(q, "Kredyty")).includes("Kredyty firmy widzą jej członkowie."), "kredyty obcej firmy");
      for (const [method, params, error] of [
        ["firmy.admin", {}, "Brak dostępu."],
        ["firmy.disputes", {}, "Brak dostępu."],
        ["firmy.world.loans", {}, "Brak dostępu."],
        ["firmy.admin.freeze", { id: KUZNIA, frozen: "true", reason: "próba" }, "Brak dostępu."],
        ["firmy.dispute.resolve", { id: "c0ffee0001", winner: "client", note: "" }, "Brak dostępu."],
        ["firmy.world.loanDecide", { id: "dd33ee44ff", approve: "true", note: "" }, "Kredyty Skarbu Świata zatwierdza tylko właściciel."],
        ["firmy.world.loanSweep", { id: "1122334455" }, "Tylko właściciel."],
        ["firmy.loans", { id: foreign }, "Kredyty firmy widzą jej członkowie."],
      ])
        eq(await rpc(q, method, params), { ok: false, error }, method);
      const g = (await rpc(q, "firmy.get", { id: foreign })).data;
      assert(!("balance" in g) && !("journal" in g) && !("sharedAddresses" in g), "obca karta bez prywatnych danych");
      eq(world.firmy.firms.find((f) => f.id === KUZNIA).frozen, false, "odmowa niczego nie zmieniła");
      await noCopies(q);
    });
    await c2.close();
  }
  world.U.wedrowiec.admin = prevWedrowiecAdmin;

  // ---------------------------------------------------------------- telefon 390 px
  await step("Firmy 390 px: ranking, karta członka, zarząd i kredyty Skarbu Świata bez przewijania w bok", async () => {
    const c3 = await browser.createBrowserContext();
    const pp = await page(c3, MOBILE, "firmy-390");
    await linkLogin(pp, world.U.bolko);
    await reopen(pp, "#/firmy");
    await wait(250);
    await noOverflow(pp, "ranking");
    await shot(pp, "168-firmy-ranking-390");
    await reopen(pp, "#/firmy/" + KUZNIA);
    await wait(250);
    await noOverflow(pp, "karta członka");
    await shot(pp, "169-firmy-karta-390");
    await c3.close();
    const c4 = await browser.createBrowserContext();
    const po = await page(c4, MOBILE, "firmy-wlasciciel-390");
    await linkLogin(po, world.U.maciej);
    await reopen(po, "#/firmy/zarzad");
    await wait(250);
    await noOverflow(po, "zarząd");
    await reopen(po, "#/skarbswiata");
    await po.waitForFunction((t) => [...document.querySelectorAll("#main section.card")].some((s) => s.querySelector("h3")?.textContent === t && !s.querySelector(".skel-wrap, .skel")), { timeout: 8000 }, LOANS_TITLE);
    await wait(250);
    await noOverflow(po, "Skarb Świata z kredytami firm");
    await c4.close();
  });
}
