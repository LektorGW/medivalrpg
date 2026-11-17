// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Test w headless Chrome (puppeteer-core + Chrome z systemu): zrzuty desktop i 390 px,
// brak błędów JS, klik w kraj (przesunięcie mapy), napisy, przełączenie świata, 404.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";
import { startServer } from "./server.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SHOTS = path.join(HERE, "screenshots");
fs.mkdirSync(SHOTS, { recursive: true });

const CHROME = [
    process.env.CHROME_PATH,
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium"
].find((p) => p && fs.existsSync(p));

const results = [];
function check(name, ok, info = "") {
    results.push({ name, ok: !!ok, info });
    console.log(`${ok ? "OK  " : "FAIL"} ${name}${info ? " — " + info : ""}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function watch(page) {
    const log = { errors: [], resource404: [] };
    page.on("pageerror", (e) => log.errors.push("pageerror: " + e.message));
    page.on("console", (m) => {
        if (m.type() !== "error" && m.type() !== "warning") return;
        const t = m.text();
        if (/Failed to load resource/.test(t)) log.resource404.push(t + " " + (m.location()?.url || ""));
        else log.errors.push(m.type() + ": " + t);
    });
    page.on("requestfailed", (r) => {
        const u = r.url();
        if (!/\/tiles\//.test(u)) log.errors.push("requestfailed: " + u + " " + r.failure()?.errorText);
    });
    return log;
}

async function waitMapReady(page) {
    await page.waitForFunction(() => window.KrajePanel && window.KrajePanel.map && window.KrajePanel.world, { timeout: 20000 });
    await sleep(1500); // kafelki
}

async function mapCenterBlocks(page) {
    return page.evaluate(() => {
        const m = window.KrajePanel.map;
        const c = m.getCenter();
        const s = m.options.scale;
        return { x: c.lng / s, z: -c.lat / s, zoom: m.getZoom() };
    });
}

async function visibleLabels(page) {
    return page.evaluate(() =>
        [...document.querySelectorAll(".kp-label")].filter((e) => !e.classList.contains("kp-label-hidden")).map((e) => e.textContent)
    );
}

async function main() {
    if (!CHROME) throw new Error("Nie znaleziono Chrome/Edge (ustaw CHROME_PATH)");
    const { server, state, url } = await startServer({ port: 8099 });
    const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ["--no-sandbox", "--force-device-scale-factor=1"] });
    try {
        // ---------- desktop ----------
        let page = await browser.newPage();
        await page.setViewport({ width: 1440, height: 900 });
        let log = watch(page);
        await page.goto(url + "?x=-60&z=-120&zoom=1", { waitUntil: "domcontentloaded" });
        await waitMapReady(page);
        await page.waitForSelector(".kp-item");

        const info = await page.evaluate(() => ({
            items: document.querySelectorAll(".kp-item").length,
            open: document.documentElement.classList.contains("kp-open"),
            count: document.querySelector(".kp-count").textContent,
            first: document.querySelector(".kp-item .kp-name").textContent,
            area: document.querySelector(".kp-item .kp-area").textContent,
            toggleTop: document.querySelector(".leaflet-top.leaflet-left").firstElementChild.classList.contains("kp-toggle-bar"),
            copy: (() => { const a = document.querySelector(".kp-copy"); return a && [a.textContent, a.href, a.target, a.rel].join("|"); })()
        }));
        check("desktop: panel otwarty domyślnie", info.open);
        check("desktop: 8 krajów na liście", info.items === 8 && info.count === "8", `items=${info.items} count=${info.count}`);
        check("sortowanie po powierzchni (pierwsza Aldoria)", info.first === "Aldoria", info.first);
        check("polskie formatowanie „6 144 bl²”", info.area.includes("6\u00a0144\u00a0bl²") && info.area.includes("24 chunki"), JSON.stringify(info.area));
        check("przycisk ☰ na górze lewych kontrolek", info.toggleTop);
        check("stopka z linkiem lektoro.pl", info.copy === "© 2026 Maciej Kaczmarczyk — lektoro.pl|https://lektoro.pl/|_blank|noopener", info.copy);

        let labels = await visibleLabels(page);
        check("napisy widoczne przy zoomie 1 (tylko większe kraje)", labels.length >= 1 && !labels.includes("Osada Łąka"), labels.join(", "));
        await page.screenshot({ path: path.join(SHOTS, "desktop-1-start.png") });

        // klik w kraj -> przelot do stolicy
        const before = await mapCenterBlocks(page);
        await page.click('.kp-item[data-id$="7c01"] .kp-row');
        await sleep(1700);
        const after = await mapCenterBlocks(page);
        const panelW = await page.evaluate(() => document.querySelector(".kp-panel").getBoundingClientRect().width);
        // stolica Aldorii ma być na środku widocznej części mapy (na prawo od panelu)
        const capScreen = await page.evaluate(() => {
            const m = window.KrajePanel.map;
            const p = m.latLngToContainerPoint(window.KrajePanel.toLatLng(120.5, -339.5));
            return { x: p.x, y: p.y, w: m.getSize().x, h: m.getSize().y };
        });
        const expectX = panelW + (capScreen.w - panelW) / 2;
        check("klik w kraj przesuwa mapę", Math.hypot(after.x - before.x, after.z - before.z) > 100, `z (${before.x | 0},${before.z | 0}) z${before.zoom} na (${after.x | 0},${after.z | 0}) z${after.zoom}`);
        check("stolica na środku widocznej mapy", Math.abs(capScreen.x - expectX) < 6 && Math.abs(capScreen.y - capScreen.h / 2) < 6, `stolica na ekranie ${capScreen.x | 0},${capScreen.y | 0}; oczekiwano ${expectX | 0},${capScreen.h / 2}`);
        const det = await page.evaluate(() => {
            const li = document.querySelector('.kp-item[data-id$="7c01"]');
            return { expanded: li.classList.contains("kp-expanded"), text: li.querySelector(".kp-details").innerText, hl: document.querySelectorAll(".leaflet-krajeHighlight-pane path").length };
        });
        check("szczegóły rozwinięte (opis, sojusze, wojny, data, oblężenia)", det.expanded && /Norvik/.test(det.text) && /Borsk/.test(det.text) && /\d\d\.\d\d\.\d{4}/.test(det.text) && /Oblężenia/.test(det.text), det.text.replace(/\n/g, " | "));
        check("pulsujący obrys granicy (SVG)", det.hl >= 2, "ścieżek: " + det.hl);
        labels = await visibleLabels(page);
        check("napisy przy przybliżeniu", labels.includes("Aldoria"), labels.join(", "));
        await page.screenshot({ path: path.join(SHOTS, "desktop-2-aldoria.png") });

        // zasłanianie: Borsk i Osada Łąka leżą obok siebie — przy zoomie 2 tylko jeden z napisów
        await page.evaluate(() => { const m = window.KrajePanel.map; m.setView(window.KrajePanel.toLatLng(50, -410), 2, { animate: false }); });
        await sleep(400);
        let lz2 = await visibleLabels(page);
        await page.evaluate(() => { const m = window.KrajePanel.map; m.setView(window.KrajePanel.toLatLng(50, -410), 5, { animate: false }); });
        await sleep(400);
        let lz5 = await visibleLabels(page);
        check("collision-avoid: przy oddaleniu ukryty mniejszy z nachodzących napisów", lz2.includes("Borsk") && !lz2.includes("Osada Łąka") && lz5.includes("Osada Łąka"), `z2: ${lz2.join(", ")} | z5: ${lz5.join(", ")}`);
        await page.screenshot({ path: path.join(SHOTS, "desktop-3-zoom5-blisko.png") });

        // przełącznik napisów
        await page.click(".kp-switch");
        await sleep(200);
        const off = await page.evaluate(() => getComputedStyle(document.querySelector(".leaflet-krajeLabels-pane")).display);
        await page.click(".kp-switch");
        check("„Pokaż nazwy” wyłącza napisy", off === "none", off);

        // wyszukiwarka + sortowanie po nazwie
        await page.type(".kp-search input", "pan_alp");
        await sleep(150);
        const found = await page.evaluate(() => [...document.querySelectorAll(".kp-item .kp-name")].map((e) => e.textContent));
        check("wyszukiwarka po władcy", found.length === 2 && found.includes("Borsk") && found.includes("WPK"), found.join(", "));
        await page.focus(".kp-search input");
        await page.keyboard.down("Control"); await page.keyboard.press("A"); await page.keyboard.up("Control");
        await page.keyboard.press("Backspace");
        await sleep(100);
        await page.evaluate(() => [...document.querySelectorAll(".kp-sort-btn")].find((b) => b.textContent === "Nazwa").click());
        const byName = await page.evaluate(() => [...document.querySelectorAll(".kp-item .kp-name")].map((e) => e.textContent));
        check("sortowanie po nazwie", byName[0] === "Aldoria" && byName[1] === "Borsk" && byName[byName.length - 1] === "WPK", byName.join(", "));
        await page.evaluate(() => [...document.querySelectorAll(".kp-sort-btn")].find((b) => b.textContent === "Obywatele").click());
        const byCit = await page.evaluate(() => document.querySelector(".kp-item .kp-name").textContent);
        check("sortowanie po obywatelach", byCit === "Norvik", byCit);
        await page.evaluate(() => [...document.querySelectorAll(".kp-sort-btn")].find((b) => b.textContent === "Powierzchnia").click());

        // kraj w innym świecie -> przełączenie świata
        await page.click('.kp-item[data-id$="7c06"] .kp-row');
        await page.waitForFunction(() => window.KrajePanel.world === "minecraft_the_nether", { timeout: 10000 }).catch(() => {});
        await sleep(1800);
        const nether = await mapCenterBlocks(page);
        const world = await page.evaluate(() => window.KrajePanel.world);
        check("kraj w innym świecie przełącza świat", world === "minecraft_the_nether" && Math.hypot(nether.x - 16.5, nether.z - 16.5) < 400, `świat=${world}, środek ${nether.x | 0},${nether.z | 0}`);
        await page.screenshot({ path: path.join(SHOTS, "desktop-4-nether.png") });

        // zwinięcie panelu + pamięć w localStorage
        await page.click(".kp-close");
        await sleep(400);
        await page.reload({ waitUntil: "domcontentloaded" });
        await waitMapReady(page);
        const stillClosed = await page.evaluate(() => !document.documentElement.classList.contains("kp-open"));
        check("stan zwinięcia pamiętany po przeładowaniu", stillClosed);
        await page.screenshot({ path: path.join(SHOTS, "desktop-5-zwiniety.png") });
        await page.click(".kp-toggle");
        await sleep(400);
        check("☰ otwiera panel", await page.evaluate(() => document.documentElement.classList.contains("kp-open")));

        // odświeżanie (bez przeładowania): zmiana danych na serwerze -> reload()
        state.kraje = false;
        await page.evaluate(() => window.KrajePanel.reload());
        // (kafelki przez proxy mogą zajmować wszystkie 6 połączeń — czekamy na wynik, nie na zegar)
        await page.waitForFunction(() => document.querySelectorAll(".kp-item").length === 0, { timeout: 15000 }).catch(() => {});
        const emptyAfter = await page.evaluate(() => document.querySelector(".kp-empty").textContent);
        state.kraje = true;
        await page.evaluate(() => window.KrajePanel.reload());
        await page.waitForFunction(() => document.querySelectorAll(".kp-item").length === 8, { timeout: 15000 }).catch(() => {});
        const back = await page.evaluate(() => document.querySelectorAll(".kp-item").length);
        check("odświeżanie danych bez przeładowania", emptyAfter === "Brak krajów do wyświetlenia" && back === 8, `${emptyAfter} -> ${back}`);

        check("desktop: brak błędów JS w konsoli", log.errors.length === 0, log.errors.join(" ; "));
        await page.close();

        // ---------- telefon 390 px ----------
        page = await browser.newPage();
        await page.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
        log = watch(page);
        await page.goto(url + "?x=100&z=-300&zoom=2", { waitUntil: "domcontentloaded" });
        await waitMapReady(page);
        // squaremap sam ma element wystający w prawo (#pin w zwiniętym pasku), ale body ma overflow:hidden —
        // sprawdzamy więc faktyczną możliwość przewinięcia strony w poziomie.
        const scrolledX = () => page.evaluate(() => { window.scrollTo(300, 0); document.body.scrollLeft = 300; const v = Math.max(window.scrollX, document.body.scrollLeft, document.documentElement.scrollLeft); window.scrollTo(0, 0); document.body.scrollLeft = 0; return v; });
        const mob = await page.evaluate(() => ({ open: document.documentElement.classList.contains("kp-open"), iw: window.innerWidth }));
        mob.sx = await scrolledX();
        check("telefon: panel domyślnie zwinięty", !mob.open);
        check("telefon: brak poziomego przewijania", mob.sx === 0, `przesunięcie po scrollTo(300,0): ${mob.sx}px`);
        await page.screenshot({ path: path.join(SHOTS, "mobile-1-start.png") });
        await page.tap(".kp-toggle");
        await sleep(450);
        const drawer = await page.evaluate(() => {
            const r = document.querySelector(".kp-panel").getBoundingClientRect();
            return { open: document.documentElement.classList.contains("kp-open"), w: Math.round(r.width), left: r.left, right: Math.round(r.right) };
        });
        drawer.sx = await scrolledX();
        check("telefon: szuflada się wysuwa", drawer.open && drawer.left === 0 && drawer.right <= 390 && drawer.sx === 0, JSON.stringify(drawer));
        await page.screenshot({ path: path.join(SHOTS, "mobile-2-szuflada.png") });
        await page.tap('.kp-item[data-id$="7c03"] .kp-row');
        await sleep(400);
        await page.screenshot({ path: path.join(SHOTS, "mobile-3-szczegoly.png") });
        await page.tap('.kp-item[data-id$="7c03"] .kp-show');
        await sleep(1700);
        const mc = await mapCenterBlocks(page);
        const closed = await page.evaluate(() => !document.documentElement.classList.contains("kp-open"));
        check("telefon: „Pokaż na mapie” zamyka szufladę i centruje", closed && Math.hypot(mc.x - 260.5, mc.z + 179.5) < 4, `${mc.x | 0},${mc.z | 0} z${mc.zoom}`);
        await page.screenshot({ path: path.join(SHOTS, "mobile-4-norvik.png") });
        check("telefon: brak błędów JS w konsoli", log.errors.length === 0, log.errors.join(" ; "));
        await page.close();

        // ---------- brak kraje.json (404) ----------
        state.kraje = false;
        page = await browser.newPage();
        await page.setViewport({ width: 1280, height: 800 });
        log = watch(page);
        await page.goto(url, { waitUntil: "domcontentloaded" });
        await waitMapReady(page);
        await sleep(500);
        const empty = await page.evaluate(() => ({ t: document.querySelector(".kp-empty").textContent, hidden: document.querySelector(".kp-empty").hidden, labels: document.querySelectorAll(".kp-label").length }));
        check("404: komunikat „Brak krajów do wyświetlenia”", empty.t === "Brak krajów do wyświetlenia" && !empty.hidden && empty.labels === 0, JSON.stringify(empty));
        check("404: brak błędów JS (poza wpisem przeglądarki o 404)", log.errors.length === 0, log.errors.join(" ; ") + (log.resource404.length ? ` [wpisy przeglądarki o zasobach: ${log.resource404.length}]` : ""));
        await page.screenshot({ path: path.join(SHOTS, "desktop-6-brak-danych.png") });
        await page.close();
        state.kraje = true;

        // ---------- tryb osadzenia squaremap (?show_controls=false) ----------
        page = await browser.newPage();
        await page.setViewport({ width: 1024, height: 700 });
        log = watch(page);
        await page.goto(url + "?show_controls=false&x=100&z=-300&zoom=3", { waitUntil: "domcontentloaded" });
        await waitMapReady(page);
        await page.waitForFunction(() => document.querySelectorAll(".kp-label").length > 0, { timeout: 15000 }).catch(() => {});
        const emb = await page.evaluate(() => ({ panel: !!document.querySelector(".kp-panel"), labels: document.querySelectorAll(".kp-label").length }));
        check("show_controls=false: bez panelu, z napisami, bez błędów", !emb.panel && emb.labels > 0 && log.errors.length === 0, JSON.stringify(emb) + " " + log.errors.join(" ; "));
        await page.close();
    } finally {
        await browser.close();
        server.close();
    }
    const failed = results.filter((r) => !r.ok);
    console.log(`\n${results.length - failed.length}/${results.length} OK. Zrzuty: ${SHOTS}`);
    process.exitCode = failed.length ? 1 : 0;
}

main().catch((e) => {
    console.error(e);
    process.exitCode = 2;
});
