// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E: chorągwie krajów i ich moderacja (zrzuty 150-…159-; samo E2E_FLAG_ONLY=1).
// Doradca wgrywa PNG → „Oczekuje”; moderator zatwierdza w #/flagi → kraj czeka na przebudowę paczki; duży obrazek jest
// pomniejszany w przeglądarce, odrzucony z powodem → kraj widzi powód; obcy gracz widzi tylko obecną flagę.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pngBuffer } from "./fake-flag.mjs";

export async function flagSteps(E) {
  const { browser, step, assert, eq, page, linkLogin, go, shot, text, clickButton, toastText, lastCall, world, DESKTOP, MOBILE, sleep } = E;
  const wait = sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "panel-flag-"));
  const small = path.join(dir, "herb.png");
  fs.writeFileSync(small, pngBuffer(128, 80, (x, y) => (Math.hypot(x - 64, y - 40) < 22 ? [226, 191, 116] : y < 40 ? [176, 46, 38] : [245, 240, 230])));
  // Duży plik (> limitu 160 KB): gradient z drobnym szumem — przeglądarka musi go pomniejszyć do ≤ 256×160 i wysłać jako PNG.
  const big = path.join(dir, "duzy.png");
  fs.writeFileSync(big, pngBuffer(1200, 750, (x, y) => [((x / 1200) * 200 + Math.random() * 24) | 0, ((y / 750) * 180 + Math.random() * 24) | 0, (90 + Math.random() * 24) | 0]));
  assert(fs.statSync(big).size > 160 * 1024, "duży plik testowy");
  const FLAG = "#/kraj/c-polanie/flaga";
  const prevAdmin = world.U.wedrowiec.admin;
  world.U.wedrowiec.admin = false; // inne zestawy (Skarb Świata) mogą nadać mu admina

  // Ten sam hash nie wywołuje hashchange — przejście przez pulpit wymusza świeży odczyt.
  const reopen = async (p, hash) => {
    if (await p.evaluate((h) => location.hash === h, hash)) await go(p, "#/");
    await go(p, hash);
  };
  const noOverflow = async (p, label) => {
    const o = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(o <= 1, `${label}: przewijanie w bok o ${o}px`);
  };
  const upload = async (p, file) => {
    const input = await p.$("#flag-file");
    assert(input, "pole pliku");
    await input.uploadFile(file);
    await p.waitForSelector(".flag-upload-out .flag-compare, .flag-upload-out .alert-err", { timeout: 15000 });
    const err = await p.$(".flag-upload-out .alert-err");
    assert(!err, "błąd przygotowania: " + (err ? await err.evaluate((e) => e.textContent) : ""));
  };

  const ctxD = await browser.createBrowserContext();
  const pd = await page(ctxD, DESKTOP, "choragiew-doradca");
  let firstImage = null;

  await step("Chorągiew: Doradca ma pozycję w menu i widzi domyślną flagę z koloru kraju", async () => {
    await linkLogin(pd, world.U.dobrawa);
    assert(await pd.$('a.nav-item[href="#/kraj/c-polanie/flaga"]'), "pozycja „Chorągiew kraju”");
    assert(!(await pd.$('a.nav-item[href="#/flagi"]')), "Doradca nie jest moderatorem");
    await reopen(pd, FLAG);
    const body = await text(pd, "#main");
    for (const s of ["Chorągiew kraju", "Królestwo Polan", "Domyślna (z koloru kraju)", "Wgraj nową chorągiew", "Każdy obrazek sprawdza moderator", "Historia chorągwi"]) assert(body.includes(s), s);
    const img = await pd.$eval(".flag-hero img.flag-img", (e) => ({ w: e.getBoundingClientRect().width, src: e.getAttribute("src").slice(0, 22), render: getComputedStyle(e).imageRendering }));
    assert(img.w >= 300, "duży podgląd: " + img.w);
    eq(img.src, "data:image/png;base64,", "obrazek z serwera");
    assert(/pixelated|crisp-edges/.test(img.render), "pikselowy podgląd: " + img.render);
    assert(await pd.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.trim() === "Wyślij do zatwierdzenia")?.disabled === true), "„Wyślij” zablokowane bez pliku");
    // Atrapa = Kraje 9240f7b2 (produkcja do restartu): brak pól sztandaru → komunikat zamiast podglądu, bez błędów.
    assert(body.includes("Podgląd wzoru sztandaru pojawi się po aktualizacji serwera"), "komunikat o aktualizacji");
    assert(!(await pd.$(".flag-banner-img")), "bez obrazka sztandaru na starym Kraje");
    await shot(pd, "150-choragiew-domyslna");
  });

  await step("Chorągiew: wgranie małego PNG → podgląd 64×40 i „Oczekuje na zatwierdzenie”", async () => {
    await upload(pd, small);
    const out = await text(pd, ".flag-upload-out");
    assert(out.includes("128×80 px") && out.includes("przytnie obrazek do 64×40 px") && out.includes("32 kolorów"), out);
    assert(await pd.$(".flag-upload-out canvas.flag-img"), "przybliżony podgląd w grze");
    await shot(pd, "151-choragiew-podglad-wgrania");
    const n = world.calls.length;
    await clickButton(pd, "Wyślij do zatwierdzenia");
    assert((await toastText(pd)).includes("czeka na zatwierdzenie"), "toast");
    const c = world.calls.slice(n).find((x) => x.method === "country.flag.upload");
    assert(c, "wywołanie upload");
    eq([c.params.id, c.params.filename], ["c-polanie", "herb.png"], "parametry");
    eq(c.params.data, "data:image/png;base64," + fs.readFileSync(small).toString("base64"), "mały PNG wysłany bez zmian");
    await pd.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Oczekuje na zatwierdzenie"), { timeout: 8000 });
    const body = await text(pd, "#main");
    assert(body.includes("Dobrawa") && body.includes("PNG · 128×80 px"), "kto i oryginał");
    assert(await pd.$("#main button") && (await pd.evaluate(() => [...document.querySelectorAll("#main button")].some((b) => b.textContent.trim() === "Wycofaj"))), "„Wycofaj”");
    firstImage = world.flags["c-polanie"].pending.imageId;
    await shot(pd, "152-choragiew-oczekuje");
  });

  const ctxM = await browser.createBrowserContext();
  const pm = await page(ctxM, DESKTOP, "moderator");

  await step("Moderacja: moderator zatwierdza w #/flagi → kraj ma flagę czekającą na paczkę", async () => {
    await linkLogin(pm, world.U.maciej);
    assert(await pm.$('a.nav-item[href="#/flagi"]'), "pozycja „Moderacja chorągwi”");
    await reopen(pm, "#/flagi");
    const body = await text(pm, "#main");
    for (const s of ["Moderacja chorągwi", "W kolejce: 1", "Królestwo Polan", "Obecna", "Proponowana", "Dobrawa", "PNG · 128×80 px", "Paczka zasobów", "Przebuduj paczkę teraz"]) assert(body.includes(s), s);
    eq(await pm.$$eval(".flag-queue-item .flag-img", (es) => es.length), 2, "obecna i proponowana obok siebie");
    await shot(pm, "153-moderacja-kolejka");
    await clickButton(pm, "Zatwierdź");
    await pm.waitForSelector(".modal");
    await clickButton(pm, "Zatwierdź", ".modal");
    assert((await toastText(pm)).includes("Zatwierdzono"), "toast");
    eq(lastCall("admin.flag.approve").params, { id: "c-polanie", imageId: firstImage });
    await pm.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Kolejka jest pusta"), { timeout: 8000 });
    assert((await text(pm, "#main")).includes("Zatwierdzona"), "ostatnie decyzje");
    await reopen(pd, FLAG);
    const d = await text(pd, "#main");
    assert(d.includes("Zatwierdzona — czeka na przebudowę paczki"), "status oczekiwania na paczkę");
    assert(/Pojawi się w grze po przebudowie paczki zasobów ok\. \d{2}:\d{2} \(za ok\. \d+ min\)/.test(d), "termin przebudowy: " + d.slice(0, 400));
    assert(d.includes("Zatwierdził") && d.includes("Maciej"), "kto zatwierdził");
    assert(!d.includes("Oczekuje na zatwierdzenie"), "kolejka pusta");
    await shot(pd, "154-choragiew-czeka-na-paczke");
  });

  await step("Moderacja: duży obrazek pomniejszony w przeglądarce, odrzucony z powodem → kraj widzi powód", async () => {
    await reopen(pd, FLAG);
    await upload(pd, big);
    const out = await text(pd, ".flag-upload-out");
    assert(out.includes("pomniejszę go do 256×160 px"), out);
    const n = world.calls.length;
    await clickButton(pd, "Wyślij do zatwierdzenia");
    assert((await toastText(pd)).includes("czeka na zatwierdzenie"), "toast");
    const c = world.calls.slice(n).find((x) => x.method === "country.flag.upload");
    assert(c.params.data.startsWith("data:image/png;base64,") && c.params.data.length < 240000, "PNG ≤ 240 000 znaków");
    const sent = Buffer.from(c.params.data.split(",")[1], "base64");
    eq([sent.readUInt32BE(16), sent.readUInt32BE(20)], [256, 160], "wymiary po pomniejszeniu");
    eq(world.flags["c-polanie"].pending.original, { width: 256, height: 160, format: "PNG", bytes: sent.length }, "serwer dostał pomniejszony obrazek");
    const second = world.flags["c-polanie"].pending.imageId;

    await reopen(pm, "#/flagi");
    await clickButton(pm, "Odrzuć");
    await pm.waitForSelector(".modal textarea");
    await pm.type(".modal textarea", "ab");
    await clickButton(pm, "Odrzuć", ".modal");
    await pm.waitForSelector(".modal .flag-reason-error .alert-err");
    assert((await text(pm, ".modal")).includes("co najmniej 3"), "walidacja powodu w UI");
    assert(!lastCall("admin.flag.reject"), "za krótki powód nie trafia do serwera");
    await pm.$eval(".modal textarea", (e) => (e.value = ""));
    await pm.type(".modal textarea", "Herb przypomina godło innego kraju — prosimy o własny wzór.");
    await shot(pm, "155-moderacja-odrzuc-modal");
    await clickButton(pm, "Odrzuć", ".modal");
    assert((await toastText(pm)).includes("Odrzucono"), "toast");
    eq(lastCall("admin.flag.reject").params, { id: "c-polanie", imageId: second, reason: "Herb przypomina godło innego kraju — prosimy o własny wzór." });

    await reopen(pd, FLAG);
    const d = await text(pd, "#main");
    assert(d.includes("Ostatnie odrzucenie") && d.includes("Herb przypomina godło innego kraju"), "powód widoczny dla kraju");
    assert(d.includes("Zatwierdzona — czeka na przebudowę paczki"), "obecna chorągiew bez zmian");
    assert(d.includes("Odrzucona") && d.includes("Zatwierdzona"), "historia");
    await shot(pd, "156-choragiew-odrzucona");
  });

  await step("Chorągiew: obcy gracz widzi tylko obecną flagę (bez formularza i historii)", async () => {
    const ctxO = await browser.createBrowserContext();
    const po = await page(ctxO, DESKTOP, "choragiew-obcy");
    await linkLogin(po, world.U.wedrowiec);
    assert(!(await po.$('a.nav-item[href$="/flaga"]')), "bez pozycji w menu");
    await reopen(po, FLAG);
    const body = await text(po, "#main");
    assert(body.includes("Obecna chorągiew") && body.includes("Zatwierdzona — czeka na przebudowę paczki"), "obecna flaga");
    for (const s of ["Wgraj nową chorągiew", "Historia chorągwi", "Ostatnie odrzucenie", "Przywróć domyślną", "Herb przypomina"]) assert(!body.includes(s), "nie widzi: " + s);
    assert(!(await po.$("#flag-file")), "brak pola pliku");
    assert(await po.$(".flag-hero img.flag-img"), "podgląd");
    await shot(po, "157-choragiew-obcy");
    await reopen(po, "#/flagi");
    assert((await text(po, "#main")).includes("Brak dostępu"), "odmowa moderacji");
    await shot(po, "158-moderacja-brak-dostepu");
    await ctxO.close();
  });

  await step("Chorągiew i moderacja: 390 px bez poziomego przewijania; przebudowa paczki → „W grze”", async () => {
    await pd.setViewport(MOBILE);
    await reopen(pd, FLAG);
    await noOverflow(pd, "chorągiew");
    await shot(pd, "159-choragiew-390");
    await pm.setViewport(MOBILE);
    await reopen(pm, "#/flagi");
    await noOverflow(pm, "moderacja");
    await clickButton(pm, "Przebuduj paczkę teraz");
    await pm.waitForSelector(".modal");
    await clickButton(pm, "Przebuduj teraz", ".modal");
    assert((await toastText(pm)).includes("przebudowana"), "toast przebudowy");
    await reopen(pd, FLAG);
    assert((await text(pd, "#main")).includes("W grze"), "status po przebudowie");
    await wait(10);
  });

  await step("Sztandar (wzór A): stary Kraje → komunikat; nowy Kraje → liczony, potem obrazek obok flagi; 390 px", async () => {
    const errors = [];
    pd.on("pageerror", (e) => errors.push(String(e)));
    await pd.setViewport(DESKTOP);
    try {
      world.flagBanners = "old";
      await reopen(pd, FLAG);
      let body = await text(pd, "#main");
      assert(body.includes("Podgląd wzoru sztandaru pojawi się po aktualizacji serwera") && body.includes("W grze"), "stary Kraje: " + body.slice(0, 300));
      assert(!(await pd.$(".flag-banner-img")), "stary Kraje: bez obrazka");
      await shot(pd, "157-sztandar-stary-kraje");

      world.flagBanners = "pending";
      await reopen(pd, FLAG);
      body = await text(pd, "#main");
      assert(body.includes("Wzór sztandaru jest właśnie dobierany"), "wzór liczony");
      assert(!body.includes("po aktualizacji serwera"), "nie myli z brakiem aktualizacji");

      world.flagBanners = "ready";
      await reopen(pd, FLAG);
      await pd.waitForSelector(".flag-hero .flag-banner-img");
      body = await text(pd, "#main");
      for (const s of ["Sztandar w grze", "tło: czerwony; połowa górna: biały; koło: żółty", "ΔE 15,5 — dobra zgodność barw", "/kraj flaga sztandar"]) assert(body.includes(s), s);
      assert(!body.includes("po aktualizacji serwera"), "bez komunikatu na nowym Kraje");
      const g = await pd.evaluate(() => {
        const f = document.querySelector(".flag-hero img.flag-img").getBoundingClientRect();
        const b = document.querySelector(".flag-banner-img");
        const r = b.getBoundingClientRect();
        return { f: [f.left, f.right, f.top, f.bottom], b: [r.left, r.top], card: document.querySelector(".flag-hero").getBoundingClientRect().width, nw: b.naturalWidth, nh: b.naturalHeight, w: r.width, h: r.height, beside: r.left >= f.right - 1 && r.top < f.bottom, src: b.getAttribute("src").slice(0, 22), render: getComputedStyle(b).imageRendering };
      });
      eq([g.nw, g.nh, g.w, g.h], [80, 160, 80, 160], "sztandar 80×160");
      eq(g.src, "data:image/png;base64,", "PNG z serwera");
      assert(g.beside, "sztandar obok zatwierdzonej flagi: " + JSON.stringify(g));
      assert(/pixelated|crisp-edges/.test(g.render), "pikselowy sztandar: " + g.render);
      await shot(pd, "157-sztandar-nowy-kraje");

      await pd.setViewport(MOBILE);
      await reopen(pd, FLAG);
      await pd.waitForSelector(".flag-banner-img");
      await noOverflow(pd, "sztandar 390 px");
      await shot(pd, "159-sztandar-390");

      // obcy gracz też widzi wzór (dane publiczne jak obecna flaga)
      const ctxO = await browser.createBrowserContext();
      const po = await page(ctxO, DESKTOP, "sztandar-obcy");
      await linkLogin(po, world.U.wedrowiec);
      await reopen(po, FLAG);
      await po.waitForSelector(".flag-banner-img");
      await ctxO.close();
      eq(errors, [], "błędy JS na stronie");
    } finally {
      delete world.flagBanners;
      await pd.setViewport(DESKTOP);
    }
  });

  await ctxD.close();
  await ctxM.close();
  world.U.wedrowiec.admin = prevAdmin;
  fs.rmSync(dir, { recursive: true, force: true });
}
