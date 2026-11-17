// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E edytora planu państwa NPC (zakładka „Plan”): podkład mapy, strefa (wielokąt i prostokąt), droga, mur z bramą
// i mur automatyczny, budynek z obrotem, cofnij/ponów, przesuwanie i gumka, zapis z ostrzeżeniami, błędy walidacji,
// podgląd, eksport/import JSON, szkic w localStorage, wyczyszczenie, narzędzie „Teren” (pędzel, usuwanie,
// wypełnianie, pierścień, odrzucony cudzy chunk), motyw jasny, telefon (tryb rysowania), uprawnienia.
// Zrzuty ekranu z prefiksem 90-.
import fs from "node:fs";
import path from "node:path";
import os from "node:os";

export async function planSteps(E) {
  const { browser, step, assert, eq, page, linkLogin, go, shot, text, texts, clickButton, toastText, lastCall, world, DESKTOP, MOBILE, sleep } = E;
  const ctx = await browser.createBrowserContext();
  const p = await page(ctx, DESKTOP, "plan-admin");
  const URL = "#/npc/npc-mazowsze/plan";

  const ed = (fn, arg) => p.evaluate((src, a) => new Function("e", "a", "return (" + src + ")(e, a)")(document.querySelector("canvas.plan-canvas").planEditor, a), fn.toString(), arg);
  const plan = () => ed((e) => JSON.parse(JSON.stringify(e.plan)));
  const tool = (id) => p.evaluate((id) => document.querySelector(`.plan-toolbar [data-tool="${id}"]`).click(), id);
  const optBtn = (id) => p.evaluate((id) => document.querySelector(`.plan-options [data-id="${id}"]`).click(), id);
  const act = (a, scope = "#main") => p.evaluate((a, s) => document.querySelector(`${s} [data-act="${a}"]`).click(), a, scope);
  const view = () => p.evaluate(() => document.querySelector("canvas.plan-canvas").scrollIntoView({ block: "center" }));
  const pt = (x, z) => ed((e, a) => e.clientPoint(a[0], a[1]), [x, z]);
  async function wclick(x, z, mods = []) {
    await view();
    const q = await pt(x, z);
    for (const m of mods) await p.keyboard.down(m);
    await p.mouse.click(q.x, q.y);
    for (const m of mods) await p.keyboard.up(m);
    await sleep(30);
  }
  async function wdrag(a, b) {
    await view();
    const s = await pt(a[0], a[1]);
    const t = await pt(b[0], b[1]);
    await p.mouse.move(s.x, s.y);
    await p.mouse.down();
    await p.mouse.move(t.x, t.y, { steps: 8 });
    await p.mouse.up();
    await sleep(40);
  }
  const keys = async (...combo) => {
    for (const k of combo.slice(0, -1)) await p.keyboard.down(k);
    await p.keyboard.press(combo[combo.length - 1]);
    for (const k of combo.slice(0, -1).reverse()) await p.keyboard.up(k);
    await sleep(40);
  };
  /** Zrzut bez zaległych toastów z poprzednich kroków. */
  async function snap(pg, name) {
    await pg.evaluate(() => document.querySelectorAll(".toast").forEach((t) => t.remove()));
    await shot(pg, name);
  }
  async function openPlan() {
    await go(p, URL);
    await p.waitForSelector("canvas.plan-canvas", { timeout: 15000 });
    await p.waitForFunction(() => document.querySelector("canvas.plan-canvas").planEditor.img, { timeout: 8000 });
    await view();
    await sleep(150);
  }

  await step("Plan: zakładka, podkład mapy z góry, granica, cudze kraje, obszar chroniony, budynki", async () => {
    await linkLogin(p, world.U.maciej);
    try {
      await p.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith("panel-plan-draft:")).forEach((k) => localStorage.removeItem(k)));
    } catch {}
    await openPlan();
    assert((await texts(p, ".tab")).includes("Plan"), "zakładka Plan");
    const info = await ed((e) => ({ land: e.land.size, foreign: e.foreign.size, prot: e.protectedAreas.length, existing: e.existing.length, img: e.img.naturalWidth, types: e.types.size, sized: [...e.types.values()].every((t) => t.size) }));
    assert(info.land >= 40, "ziemia kraju: " + info.land);
    assert(info.foreign > 0 && info.prot === 1, "cudze kraje i spawn");
    eq(info.existing, 9, "istniejące budynki z obrysami");
    assert(info.img > 100, "PNG z mapRender");
    assert(info.sized, "typy budynków mają rozmiar");
    eq(lastCall("admin.npc.mapRender").params, { id: "npc-mazowsze", margin: 32 }, "mapRender params");
    assert((await text(p, ".plan-coords")).length > 0, "współrzędne");
    const q = await pt(1450, -300);
    await p.mouse.move(q.x, q.y);
    await sleep(60);
    assert((await text(p, ".plan-coords")).includes("x 1450, z -300"), "współrzędne pod kursorem: " + (await text(p, ".plan-coords")));
    await snap(p, "90-plan-podklad-desktop");
  });

  await step("Plan: strefa (wielokąt klik po kliku i prostokąt pędzlem) z wyborem typu", async () => {
    await tool("zone");
    await p.evaluate(() => document.querySelector('.zone-chip[data-zone="mieszkalna"]').click());
    for (const [x, z] of [[1400, -322], [1442, -322], [1444, -286], [1404, -284]]) await wclick(x, z);
    assert(await p.$(".plan-draftbar:not([hidden])"), "pasek rysowania");
    await wclick(1400, -322); // klik w pierwszy punkt zamyka
    let pl = await plan();
    eq(pl.zones.length, 1, "strefa");
    eq(pl.zones[0].type, "mieszkalna", "typ strefy");
    eq(pl.zones[0].polygon.length, 4, "wierzchołki");
    await optBtn("rect");
    await p.evaluate(() => document.querySelector('.zone-chip[data-zone="las"]').click());
    await wdrag([1360, -280], [1385, -250]);
    pl = await plan();
    eq(pl.zones.length, 2, "prostokąt");
    eq(pl.zones[1].type, "las", "las");
    eq(pl.zones[1].polygon, [[1360, -280], [1385, -280], [1385, -250], [1360, -250]], "prostokąt w blokach");
  });

  await step("Plan: droga — linia łamana, rodzaj i szerokość, Enter kończy", async () => {
    await tool("road");
    await optBtn("boczna");
    for (const [x, z] of [[1395, -298], [1450, -297], [1515, -292]]) await wclick(x, z);
    await keys("Enter");
    const r = (await plan()).roads;
    eq(r.length, 1, "droga");
    eq([r[0].kind, r[0].width, r[0].points.length], ["boczna", 3, 3], "rodzaj, szerokość, punkty");
  });

  await step("Plan: mur zamknięty z bramą klikniętą na murze i mur „wzdłuż granicy”", async () => {
    await tool("wall");
    for (const [x, z] of [[1396, -334], [1500, -334], [1500, -262], [1396, -262]]) await wclick(x, z);
    await wclick(1396, -334);
    let w = (await plan()).walls;
    eq(w.length, 1, "mur");
    assert(w[0].closed, "zamknięty");
    await optBtn("gate");
    await wclick(1450, -333);
    w = (await plan()).walls;
    eq(w[0].gates, [[1450, -334]], "brama przyciągnięta do muru");
    await optBtn("follow-border");
    const inset = await p.$("input[aria-label='Odstęp muru (inset)']");
    await inset.evaluate((e) => ((e.value = "3"), e.dispatchEvent(new Event("change", { bubbles: true }))));
    await clickButton(p, "Dodaj mur: wzdłuż granicy");
    w = (await plan()).walls;
    eq(w.length, 2, "mur automatyczny");
    eq([w[1].mode, w[1].inset, w[1].points.length], ["follow-border", 3, 0], "tryb i odstęp");
    const segs = await ed((e) => e.wallSegments(e.plan.walls[1]).length);
    assert(segs > 4, "podgląd muru po granicy");
    await optBtn("points");
    await optBtn("draw");
  });

  await step("Plan: budynek z listy (ikona, koszt), obrót klawiszem R, priorytet", async () => {
    await tool("building");
    assert(await p.$(".btype-list .btype.off"), "niedostępne typy wyszarzone");
    const meta = await text(p, '.btype[data-type="kuznia"]');
    assert(meta.includes("9×9") && meta.includes("zł"), "rozmiar i koszt: " + meta);
    await p.evaluate(() => document.querySelector('.btype[data-type="kuznia"]').click());
    const prio = await p.$("input[aria-label='Priorytet (1 = najpierw)']");
    await prio.evaluate((e) => ((e.value = "1"), e.dispatchEvent(new Event("change", { bubbles: true }))));
    await wclick(1462, -302);
    await p.evaluate(() => document.querySelector('.btype[data-type="targ"]').click());
    await p.focus("canvas.plan-canvas");
    await keys("r");
    await wclick(1480, -312);
    const b = (await plan()).buildings;
    eq(b.map((x) => [x.type, x.x, x.z, x.rotation, x.priority]), [["kuznia", 1462, -302, 0, 1], ["targ", 1480, -312, 90, 1]], "budynki");
    assert((await text(p, ".plan-list")).includes("Targ"), "lista elementów");
  });

  await step("Plan: cofnij (Ctrl+Z) i ponów (Ctrl+Y), też przyciskami", async () => {
    await p.focus("canvas.plan-canvas");
    await keys("Control", "z");
    eq((await plan()).buildings.length, 1, "cofnięto budynek");
    await keys("Control", "y");
    eq((await plan()).buildings.length, 2, "ponowiono");
    await act("undo");
    eq((await plan()).buildings.length, 1, "przycisk cofnij");
    await act("redo");
    eq((await plan()).buildings.length, 2, "przycisk ponów");
  });

  await step("Plan: zaznacz i przesuń element, gumka usuwa, lista podświetla", async () => {
    await tool("select");
    await wdrag([1462, -302], [1466, -306]);
    let b = (await plan()).buildings[0];
    eq([b.x, b.z], [1466, -306], "przesunięty budynek");
    await p.evaluate(() => document.querySelector('.plan-item[data-kind="roads"]').click());
    const sel = await ed((e) => e.sel);
    eq(sel, { kind: "roads", index: 0 }, "klik na liście zaznacza");
    assert((await text(p, ".plan-selected")).includes("Droga boczna"), "panel zaznaczenia");
    // gumka: dodatkowa studnia i jej usunięcie
    await tool("building");
    await p.evaluate(() => document.querySelector('.btype[data-type="studnia"]').click());
    await wclick(1420, -296);
    eq((await plan()).buildings.length, 3, "studnia");
    await tool("eraser");
    await wclick(1420, -296);
    eq((await plan()).buildings.length, 2, "gumka usunęła studnię");
    await tool("select");
    await ed((e) => e.select(null));
    await snap(p, "91-plan-narysowany-desktop");
  });

  await step("Plan: błędy walidacji blokują zapis i są zaznaczone przy elemencie", async () => {
    const before = world.calls.filter((c) => c.method === "admin.npc.setPlan").length;
    await ed((e) => e.edit((pl) => pl.buildings.push({ type: "dom", x: 9000, z: 9000, rotation: 0, priority: 50 })));
    await act("save");
    const t = await toastText(p);
    assert(t.includes("Plan ma błędy"), "toast: " + t);
    eq(world.calls.filter((c) => c.method === "admin.npc.setPlan").length, before, "bez RPC");
    assert(await p.$(".plan-item.has-error"), "element z błędem na liście");
    assert((await text(p, ".plan-item.has-error")).includes("poza ziemią kraju"), "opis błędu");
    await snap(p, "92-plan-bledy-desktop");
    await p.focus("canvas.plan-canvas");
    await keys("Control", "z");
    eq((await plan()).buildings.length, 2, "cofnięto zły budynek");
  });

  await step("Plan: zapis (setPlan) z ostrzeżeniami per element", async () => {
    await act("save");
    await p.waitForSelector("dialog[open]", { timeout: 6000 });
    const body = await text(p, "dialog[open] .modal-body");
    assert(body.includes("Targ") && body.includes("niedostępny"), "ostrzeżenie o targu: " + body);
    await snap(p, "93-plan-zapis-ostrzezenia-desktop");
    await clickButton(p, "Zamknij", "dialog[open]");
    const call = lastCall("admin.npc.setPlan");
    eq(call.params.id, "npc-mazowsze", "id");
    const sent = call.params.plan;
    eq([sent.version, sent.zones.length, sent.roads.length, sent.walls.length, sent.buildings.length], [1, 2, 1, 2, 2], "wysłany plan");
    eq(sent.walls[1], { mode: "follow-border", points: [], closed: true, gates: [], inset: 3 }, "mur automatyczny w formacie v1");
    assert(world.C["npc-mazowsze"].plan, "plan zapisany w „Ludnosc”");
    assert((await text(p, ".plan-status")).includes("zapisany"), "status");
    assert(await p.$('.plan-item.has-warn[data-kind="buildings"][data-index="1"]'), "ostrzeżenie przy budynku");
    assert((await p.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith("panel-plan-draft:")).length)) === 0, "szkic usunięty po zapisie");
  });

  await step("Plan: podgląd zmian (planPreview) — zlecenia, rozbiórki, koszt", async () => {
    await act("preview");
    await p.waitForSelector("dialog[open] .plan-preview", { timeout: 6000 });
    const body = await text(p, "dialog[open] .modal-body");
    assert(body.includes("Zlecenia (w kolejności)") && body.includes("Kuźnia") && body.includes("Koszt łącznie"), "podgląd: " + body.slice(0, 200));
    await snap(p, "94-plan-podglad-desktop");
    await clickButton(p, "Zamknij", "dialog[open]");
    eq(lastCall("admin.npc.planPreview").params, { id: "npc-mazowsze" }, "planPreview params");
  });

  await step("Plan: eksport do pliku JSON i import z pliku", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "plan-dl-"));
    // plik z eksportu: przechwytujemy Bloba podanego do URL.createObjectURL i nazwę z atrybutu download
    await p.evaluate(() => {
      window.__dl = [];
      const orig = URL.createObjectURL;
      URL.createObjectURL = (b) => (window.__dl.push(b), orig.call(URL, b));
      const click = HTMLAnchorElement.prototype.click;
      HTMLAnchorElement.prototype.click = function () {
        if (this.download) window.__dlName = this.download;
        return this.download ? undefined : click.call(this);
      };
    });
    await act("export");
    const dl = await p.evaluate(async () => ({ name: window.__dlName, type: window.__dl[0] && window.__dl[0].type, text: window.__dl[0] ? await window.__dl[0].text() : null }));
    assert(dl.name && dl.name.startsWith("plan-") && dl.name.endsWith(".json"), "nazwa pliku: " + dl.name);
    eq(dl.type, "application/json", "typ pliku");
    const exported = JSON.parse(dl.text);
    eq([exported.version, exported.buildings.length, exported.walls[0].gates.length], [1, 2, 1], "eksport");
    const t0 = await toastText(p);
    assert(t0.includes("Wyeksportowano"), "toast eksportu: " + t0);
    const imp = { version: 1, zones: [{ type: "centrum", polygon: [[1430, -310], [1466, -310], [1466, -284], [1430, -284]] }], buildings: [{ type: "studnia", x: 1448, z: -297 }, { type: "dom", x: 1410, z: -270, rotation: 180, priority: 5 }] };
    const f2 = path.join(dir, "import.json");
    fs.writeFileSync(f2, JSON.stringify(imp));
    const input = await p.$('[data-act="import-file"]');
    await input.uploadFile(f2);
    await p.waitForFunction(() => document.querySelector("canvas.plan-canvas").planEditor.plan.zones.length === 1, { timeout: 5000 });
    const pl = await plan();
    eq([pl.zones[0].type, pl.buildings.length, pl.buildings[1].rotation, pl.roads.length], ["centrum", 2, 180, 0], "zaimportowany plan");
    const t = await toastText(p);
    assert(t.includes("Wczytano plan z pliku"), "toast importu: " + t);
    fs.rmSync(dir, { recursive: true, force: true });
  });

  await step("Plan: szkic w localStorage przetrwa przeładowanie strony", async () => {
    await sleep(600);
    const saved = await p.evaluate(() => localStorage.getItem("panel-plan-draft:npc-mazowsze"));
    assert(saved && JSON.parse(saved).plan.zones[0].type === "centrum", "szkic zapisany");
    await p.reload({ waitUntil: "domcontentloaded" });
    await p.waitForSelector("canvas.plan-canvas", { timeout: 15000 });
    await p.waitForSelector(".plan-draft", { timeout: 5000 });
    assert((await text(p, ".plan-draft")).includes("niezapisany szkic"), "baner szkicu");
    await clickButton(p, "Przywróć", ".plan-draft");
    const pl = await plan();
    eq([pl.zones.length, pl.zones[0].type], [1, "centrum"], "przywrócony szkic");
    assert((await toastText(p)).includes("Przywrócono szkic"), "toast przywrócenia");
    await view();
  });

  await step("Plan: wyczyść plan (clearPlan) z potwierdzeniem", async () => {
    await act("clear");
    await p.waitForSelector("dialog[open]");
    await clickButton(p, "Wyczyść plan", "dialog[open]");
    const t = await toastText(p);
    assert(t.includes("Usunięto plan"), "toast: " + t);
    eq(world.C["npc-mazowsze"].plan, null, "plan usunięty");
    eq((await plan()).buildings.length, 0, "pusty plan");
  });

  await step("Teren: pędzel dodaje, Shift usuwa, cudzy kraj i stolica zablokowane, wypełnianie i pierścień", async () => {
    await view();
    await tool("terrain");
    assert(!(await p.$eval(".plan-terrain", (e) => e.hidden)), "panel terenu");
    // wolny chunk F przylegający do ziemi od wschodu; jego 3 pozostałych sąsiadów dodajemy pędzlem → F zamknięty
    const geo = await ed((e) => {
      const has = (x, z) => e.land.has(x + "," + z);
      for (const k of e.land) {
        const [x, z] = k.split(",").map(Number);
        const f = [x - 1, z];
        const n = [[f[0] - 1, f[1]], [f[0], f[1] - 1], [f[0], f[1] + 1]];
        if (!has(...f) && !e.blockedBy(f.join(",")) && n.every(([a, b]) => !e.blockedBy(a + "," + b) && Math.abs(a - 90) < 9)) return { f, n, own: k, cap: e.capital };
      }
      return null;
    });
    assert(geo, "znaleziono miejsce testu");
    for (const [a, b] of geo.n) await wclick(a * 16 + 8, b * 16 + 8);
    eq(await ed((e) => e.terr.add.size), geo.n.filter(([a, b]) => true).length - (await ed((e, n) => n.filter(([a, b]) => e.land.has(a + "," + b)).length, geo.n)), "dodane pędzlem");
    // cudzy chunk (Królestwo Polan) — zablokowany
    await wclick(96 * 16 + 8, -19 * 16 + 8);
    assert(!(await ed((e) => e.terr.add.has("96,-19"))), "cudzy chunk nie jest dodany");
    // stolica i usuwanie Shiftem
    const [cx, cz] = geo.cap.split(",").map(Number);
    await wclick(cx * 16 + 8, cz * 16 + 8, ["Shift"]);
    assert(!(await ed((e) => e.terr.remove.size)), "stolicy nie można usunąć");
    const edge = await ed((e) => [...e.land].find((k) => {
      const [x, z] = k.split(",").map(Number);
      return k !== e.capital && ["1,0", "-1,0", "0,1", "0,-1"].filter((d) => { const [dx, dz] = d.split(",").map(Number); return e.land.has(x + dx + "," + (z + dz)); }).length === 1;
    }));
    const [ex, ez] = edge.split(",").map(Number);
    await wclick(ex * 16 + 8, ez * 16 + 8, ["Shift"]);
    assert(await ed((e, k) => e.terr.remove.has(k), edge), "Shift usuwa chunk z brzegu");
    // wypełnianie obszaru zamkniętego granicą
    await optBtn("fill");
    await wclick(geo.f[0] * 16 + 8, geo.f[1] * 16 + 8);
    assert(await ed((e, k) => e.terr.add.has(k), geo.f.join(",")), "wypełniono zamknięty chunk");
    const beforeRing = await ed((e) => e.terr.add.size);
    await clickButton(p, "Poszerz o 1 pierścień");
    const afterRing = await ed((e) => e.terr.add.size);
    assert(afterRing > beforeRing + 10, `pierścień: ${beforeRing} → ${afterRing}`);
    assert(!(await ed((e) => [...e.terr.add].some((k) => e.blockedBy(k)))), "pierścień omija cudze i chronione");
    const counter = await text(p, ".terr-count");
    assert(counter.includes("+" + afterRing) && counter.includes("−1"), "licznik: " + counter);
    await optBtn("brush");
    await snap(p, "95-teren-zaznaczenie-desktop");
  });

  await step("Teren: zatwierdzenie (admin.npc.territory), odrzucony cudzy chunk z powodem, odświeżony podkład", async () => {
    const renders = world.mapRenders;
    const before = world.claims["npc-mazowsze"].size;
    // chunk Polan dopisany z pominięciem blokady w przeglądarce — serwer musi go odrzucić
    await ed((e) => (e.terr.add.add("96,-20"), e.terrainChanged()));
    const nAdd = await ed((e) => e.terr.add.size);
    await p.evaluate(() => document.querySelector(".plan-terrain input[type=checkbox]").click());
    await act("terrain-commit");
    await p.waitForSelector("dialog[open]");
    await clickButton(p, "Zatwierdź teren", "dialog[open]");
    await p.waitForFunction(() => [...document.querySelectorAll("dialog[open] h2")].some((h) => h.textContent.startsWith("Pominięte chunki")), { timeout: 8000 });
    const body = await text(p, "dialog[open] .modal-body");
    assert(body.includes("chunk 96, -20") && body.includes("Królestwo Polan"), "powód odrzucenia: " + body);
    await snap(p, "96-teren-pominiete-desktop");
    await clickButton(p, "Zamknij", "dialog[open]");
    const call = lastCall("admin.npc.territory");
    eq([call.params.free, call.params.add.length, call.params.remove.length], [true, nAdd, 1], "params territory");
    eq(world.claims["npc-mazowsze"].size, before + nAdd - 1 - 1, "teren po zmianie (bez chunka Polan)");
    assert(world.mapRenders > renders, "podkład odświeżony");
    eq(lastCall("admin.npc.mapRender").params.fresh, true, "mapRender fresh");
    eq(await ed((e) => e.terr.add.size + e.terr.remove.size), 0, "brak oczekujących zmian");
    eq(await ed((e) => e.land.size), before + nAdd - 2, "nowa ziemia na płótnie");
  });

  await step("Plan: motyw jasny", async () => {
    await tool("select");
    await ed((e) => e.setPlan({ version: 1, zones: [{ type: "centrum", polygon: [[1430, -310], [1466, -310], [1466, -284], [1430, -284]] }, { type: "nie_ruszac", polygon: [[1500, -280], [1530, -280], [1530, -250], [1500, -250]] }], roads: [{ kind: "glowna", points: [[1380, -297], [1520, -297]], width: 5 }], walls: [{ mode: "points", points: [[1396, -334], [1500, -334], [1500, -262], [1396, -262]], closed: true, gates: [[1450, -334], [1396, -297]] }], buildings: [{ type: "kosciol", x: 1452, z: -318, rotation: 90, priority: 2 }, { type: "spichlerz", x: 1415, z: -255, rotation: 0, priority: 10 }], options: { allowDemolish: false, keepExisting: true, autoFill: true } }));
    await p.click("#theme-btn");
    await sleep(250);
    assert(await p.evaluate(() => document.documentElement.dataset.theme === "light"), "jasny");
    await snap(p, "97-plan-jasny-desktop");
    await p.click("#theme-btn");
    await sleep(150);
  });

  await step("Plan 390 px: tryb rysowania dotykiem, przycisk „Zakończ”, bez przewijania w bok", async () => {
    const ctxM = await browser.createBrowserContext();
    const m = await page(ctxM, MOBILE, "plan-390");
    await linkLogin(m, world.U.maciej);
    await go(m, URL);
    await m.waitForSelector("canvas.plan-canvas", { timeout: 15000 });
    await m.evaluate(() => document.querySelector("canvas.plan-canvas").scrollIntoView({ block: "center" }));
    await sleep(200);
    assert(await m.$eval(".touch-mode", (e) => getComputedStyle(e.parentElement).display !== "none"), "przełącznik trybu na telefonie");
    await m.evaluate(() => document.querySelector('.plan-toolbar [data-tool="road"]').click());
    await m.evaluate(() => document.querySelector(".touch-mode").click());
    assert((await m.$eval(".touch-mode", (e) => e.textContent)).includes("rysowanie"), "tryb rysowania");
    const tp = (x, z) => m.evaluate((x, z) => document.querySelector("canvas.plan-canvas").planEditor.clientPoint(x, z), x, z);
    for (const [x, z] of [[1420, -300], [1470, -296]]) {
      const q = await tp(x, z);
      await m.touchscreen.tap(q.x, q.y);
      await sleep(60);
    }
    await m.evaluate(() => document.querySelector('.plan-draftbar [data-act="finish"]').click());
    const roads = await m.evaluate(() => document.querySelector("canvas.plan-canvas").planEditor.plan.roads.length);
    assert(roads >= 1, "droga narysowana dotykiem");
    const overflow = await m.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(overflow <= 1, `przewijanie w bok o ${overflow}px`);
    await snap(m, "98-plan-390");
    await ctxM.close();
  });

  await step("Plan: uprawnienia — zarządca przypisany tylko swoje państwo, władca gracza bez dostępu", async () => {
    const ctxZ = await browser.createBrowserContext();
    const z = await page(ctxZ, DESKTOP, "plan-zarzadca");
    await linkLogin(z, world.U.ziemowit);
    await go(z, URL);
    await z.waitForSelector("canvas.plan-canvas", { timeout: 15000 });
    await go(z, "#/npc/npc-rus/plan");
    assert((await text(z, "#main")).includes("Brak dostępu"), "cudze państwo NPC");
    await ctxZ.close();
    const ctxB = await browser.createBrowserContext();
    const b = await page(ctxB, DESKTOP, "plan-gracz");
    await linkLogin(b, world.U.bolko);
    await go(b, URL);
    assert((await text(b, "#main")).includes("Brak dostępu"), "gracz");
    await ctxB.close();
    await ctx.close();
  });
}
