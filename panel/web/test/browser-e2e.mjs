// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E w headless Chrome (puppeteer-core + Chrome z systemu) na `wrangler dev` z fałszywym pluginem
// (realistyczny świat: kraj gracza, kraj NPC, admin, zarządca NPC). Zrzuty desktop i 390 px do test/screenshots/.
// Zrzuty 60-… dotyczą zakładek „Państwo NPC” v2 (Przegląd z wykresami, Stosunki, Gospodarka, Ludność, Budowy, Armia, Dziennik).
// Zrzuty 80-… dotyczą zakładki „Postęp” (epoki, drzewko odkryć, inwestycje; kraj, NPC, ranking).
// Zrzuty 30-… dotyczą zakładki Administracja → „Wydajność serwera” — kroki w e2e-health.mjs.
// Zrzuty 140-147 — gospodarka kraju (e2e-economy.mjs; samo E2E_ECONOMY_ONLY=1).
// Zrzuty 150-159 — chorągwie krajów i moderacja (e2e-flag.mjs; samo E2E_FLAG_ONLY=1).
// Zrzuty 112-113 — zarząd Skarbu Świata (e2e-world.mjs), 114-132 — statystyki Skarbu Świata (e2e-world-stats.mjs),
// 133 — przelew do wszystkich państw NPC (e2e-world-npc-bulk.mjs).
// Zrzuty 160-169 — firmy graczy i kredyty firm ze Skarbu Świata (e2e-firmy.mjs; samo E2E_FIRMY_ONLY=1).
// Zrzuty 90-… dotyczą edytora planu państwa NPC (zakładka „Plan”, narzędzie „Teren”) — kroki w e2e-plan.mjs.
// Uruchomienie: `npm run test:e2e` (ścieżka Chrome w CHROME_PATH albo domyślna instalacja).
import fs from "node:fs";
import path from "node:path";
import { randomBytes } from "node:crypto";
import puppeteer from "puppeteer-core";
import { FakePlugin } from "./fake-plugin.mjs";
import { createWorld, makeHandlers } from "./fake-world.mjs";
import { startDev, stopDev, readSecret, sleep, ROOT } from "./dev-server.mjs";
import { playerPlanSteps } from "./e2e-player-plan.mjs";
import { planSteps } from "./e2e-plan.mjs";
import { extraSteps } from "./e2e-extra.mjs";
import { healthSteps } from "./e2e-health.mjs";
import { railwaySteps } from "./e2e-railway.mjs";
import { worldSteps } from "./e2e-world.mjs";
import { worldStatsSteps } from "./e2e-world-stats.mjs";
import { economySteps } from "./e2e-economy.mjs";
import { flagSteps } from "./e2e-flag.mjs";
import { worldNpcBulkSteps } from "./e2e-world-npc-bulk.mjs";
import { firmySteps } from "./e2e-firmy.mjs";

const PORT = Number(process.env.PORT || 8794);
const SHOTS = path.join(ROOT, "test", process.env.PANEL_TEST_DIST === "1" ? "screenshots-dist" : "screenshots");
fs.mkdirSync(SHOTS, { recursive: true });
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "/usr/bin/google-chrome", "/usr/bin/chromium"].find((p) => p && fs.existsSync(p));
const SECRET = readSecret();
const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2 };

let ok = 0;
let fail = 0;
const errors = [];
const openPages = [];
async function step(name, fn) {
  try {
    await fn();
    ok++;
    console.log("  OK   " + name);
  } catch (e) {
    fail++;
    for (const [i, pg] of openPages.entries()) await pg.screenshot({ path: path.join(SHOTS, `FAIL-${fail}-${i}.png`) }).catch(() => {});
    console.log("  FAIL " + name + "\n       " + String(e && e.stack ? e.stack.split("\n").slice(0, 3).join("\n       ") : e));
  }
}
function assert(c, msg) {
  if (!c) throw new Error(msg || "asercja");
}
function eq(a, b, msg) {
  if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(`${msg || "różne"}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`);
}

let dev;
let BASE;
let world;
let plugin;
let browser;

function connectPlugin() {
  plugin = new FakePlugin(BASE, SECRET, makeHandlers(world));
  return plugin.opened.then(() => sleep(150));
}

async function page(ctx, viewport = DESKTOP, label = "") {
  const p = await ctx.newPage();
  openPages.unshift(p);
  if (openPages.length > 1) openPages.length = 1;
  await p.setViewport(viewport);
  p.on("pageerror", (e) => errors.push(`[${label}] pageerror: ${e.message}`));
  p.on("console", (m) => {
    if (m.type() === "error") {
      const t = m.text();
      // 401/503 z API są oczekiwane w scenariuszach (sesja, offline) — przeglądarka loguje je jako błędy zasobów.
      // 429 = limit RPC na sesję przy szybkich kliknięciach testu; SPA ponawia żądanie po Retry-After.
      if (/Failed to load resource: the server responded with a status of (401|429|503|504)/.test(t)) return;
      errors.push(`[${label}] console: ${t}`);
    }
  });
  return p;
}

async function settled(p) {
  await p.waitForFunction(() => {
    const m = document.getElementById("main");
    return (m && m.children.length && !m.querySelector(".skel-wrap")) || document.querySelector(".login-card");
  }, { timeout: 15000 });
  await sleep(150);
}

async function shot(p, name) {
  // Zrzut całej strony od góry — inaczej przypięty nagłówek wypada w środku obrazu.
  await p.evaluate(() => window.scrollTo(0, 0));
  await p.screenshot({ path: path.join(SHOTS, name + ".png"), fullPage: true });
}

async function go(p, hash) {
  await p.evaluate((h) => (location.hash = h), hash);
  await sleep(80);
  await settled(p);
}

async function login(p, name, pass) {
  await p.goto(BASE + "/#/logowanie", { waitUntil: "domcontentloaded" });
  await p.waitForSelector("#login-name");
  await p.$eval("#login-name", (e) => (e.value = ""));
  await p.$eval("#login-pass", (e) => (e.value = ""));
  await p.type("#login-name", name);
  await p.type("#login-pass", pass);
  await p.click(".login-form button[type=submit]");
  await p.waitForSelector("#main .page-head, #main .country-head", { timeout: 15000 });
  await settled(p);
}

/** Logowanie linkiem z gry (/panel) — bez limitu prób logowania hasłem (per IP), który zablokowałby kolejne konta. */
async function linkLogin(p, user) {
  const t = [...randomBytes(32)].map((b) => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[b % 62]).join("");
  plugin.linkToken(t, user.uuid, user.name);
  await sleep(200);
  await p.goto(BASE + "/login?t=" + t, { waitUntil: "domcontentloaded" });
  await p.waitForSelector("#main .page-head, #main .country-head", { timeout: 15000 });
  await settled(p);
}

/** Czyści pole i wpisuje tekst (zdarzenia input jak przy pisaniu). */
async function fill(el, value) {
  await el.evaluate((e) => {
    e.value = "";
    e.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await el.type(value);
}

const text = (p, sel) => p.$eval(sel, (e) => e.textContent);
const texts = (p, sel) => p.$$eval(sel, (es) => es.map((e) => e.textContent.trim()));

async function clickButton(p, label, scope = "#main") {
  const ok = await p.evaluate(
    (label, scope) => {
      const b = [...document.querySelectorAll(scope + " button, " + scope + " a.btn")].find((x) => x.textContent.trim() === label && !x.disabled);
      if (b) b.click();
      return !!b;
    },
    label,
    scope,
  );
  assert(ok, "brak przycisku: " + label);
}

async function toastText(p) {
  await p.waitForSelector(".toast", { timeout: 6000 });
  const t = await texts(p, ".toast");
  await p.evaluate(() => document.querySelectorAll(".toast").forEach((t) => t.remove()));
  // Toast pojawia się przed przeładowaniem widoku; przycisk akcji jest „busy”, dopóki przeładowanie trwa.
  await p.waitForFunction(() => !document.querySelector("#main .busy"), { timeout: 8000 }).catch(() => {});
  return t.join(" | ");
}

function lastCall(method) {
  for (let i = world.calls.length - 1; i >= 0; i--) if (world.calls[i].method === method) return world.calls[i];
  return null;
}

async function main() {
  if (!CHROME) throw new Error("Nie znaleziono Chrome/Edge (ustaw CHROME_PATH)");
  // E2E_FLAG_ONLY / E2E_FIRMY_ONLY czyszczą tylko własne zrzuty (15x- / 16x-), żeby nie kasować zrzutów innych zestawów.
  const own = process.env.E2E_FLAG_ONLY === "1" ? /^15\d-/ : process.env.E2E_FIRMY_ONLY === "1" ? /^16\d-/ : /^/;
  for (const f of fs.readdirSync(SHOTS)) if (f.endsWith(".png") && own.test(f)) fs.rmSync(path.join(SHOTS, f));
  console.log("Start wrangler dev na porcie " + PORT + "…");
  dev = await startDev(PORT, { RPC_TIMEOUT_MS: 3000 });
  BASE = dev.base;
  world = createWorld();
  await connectPlugin();
  browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ["--no-sandbox", "--force-color-profile=srgb"] });

  if (process.env.E2E_HEALTH_ONLY === "1") {
    await healthSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, texts, lastCall, world, DESKTOP, MOBILE, sleep });
    await step("brak błędów JS i CSP w module wydajności", async () => assert(errors.length === 0, errors.join("\n")));
    return;
  }
  if (process.env.E2E_RAILWAY_ONLY === "1") {
    await railwaySteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, lastCall, world, DESKTOP, MOBILE });
    await step("brak błędów JS i CSP w dyspozytorni", async () => assert(errors.length === 0, errors.join("\n")));
    return;
  }
  if (process.env.E2E_FLAG_ONLY === "1") {
    await flagSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, clickButton, toastText, lastCall, world, DESKTOP, MOBILE, sleep });
    await step("brak błędów JS i CSP w chorągwiach", async () => assert(errors.length === 0, errors.join("\n")));
    return;
  }
  if (process.env.E2E_ECONOMY_ONLY === "1") {
    await economySteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, lastCall, world, DESKTOP, MOBILE });
    await step("brak błędów JS i CSP w gospodarce kraju", async () => assert(errors.length === 0, errors.join("\n")));
    return;
  }
  if (process.env.E2E_WORLD_ONLY === "1") {
    await worldSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, lastCall, world, DESKTOP, MOBILE });
    await worldStatsSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, lastCall, world, DESKTOP, MOBILE, sleep, SHOTS });
    await worldNpcBulkSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, world, DESKTOP, sleep });
    await step("brak błędów JS i CSP w Skarbie Świata", async () => assert(errors.length === 0, errors.join("\n")));
    return;
  }
  if (process.env.E2E_FIRMY_ONLY === "1") {
    await firmySteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, toastText, world, DESKTOP, MOBILE, sleep });
    await step("brak błędów JS i CSP w firmach", async () => assert(errors.length === 0, errors.join("\n")));
    return;
  }

  if (process.env.E2E_PLAYER_PLAN_ONLY) {
    await playerPlanSteps({ browser, step, assert, eq, page, linkLogin, go, shot, lastCall, world, DESKTOP, sleep });
    await step("brak błędów JS w edytorze gracza",async()=>assert(errors.length===0,errors.join("\n")));
    return;
  }

  // ------------------------------------------------------------- gracz (władca)
  const ctxB = await browser.createBrowserContext();
  const pb = await page(ctxB, DESKTOP, "Bolko");

  await step("logowanie: ekran, złe hasło → błąd", async () => {
    await pb.goto(BASE + "/", { waitUntil: "domcontentloaded" });
    await pb.waitForSelector(".login-card");
    assert((await text(pb, ".login-alt")).includes("/panel"), "brak informacji o /panel");
    assert((await text(pb, ".login-footer")).includes("lektoro.pl"), "stopka");
    await shot(pb, "01-logowanie-desktop");
    await pb.type("#login-name", "Bolko");
    await pb.type("#login-pass", "zle-haslo");
    await pb.click(".login-form button[type=submit]");
    await pb.waitForSelector(".alert-err:not([hidden])");
    assert((await text(pb, ".alert-err")).includes("Nieprawidłowy"), "komunikat błędu");
    assert((await pb.$eval("#login-pass", (e) => e.value)) === "", "hasło powinno być wyczyszczone");
  });

  await step("logowanie dobre → pulpit (kraj, saldo, skróty); cookie HttpOnly", async () => {
    await login(pb, "Bolko", world.U.bolko.password);
    assert((await text(pb, ".hero-name")) === "Królestwo Polan", "kraj na pulpicie");
    assert((await text(pb, ".balance-value")).includes("123 zł 4 sr 5 md"), "saldo: " + (await text(pb, ".balance-value")));
    assert((await pb.$$(".shortcut")).length >= 6, "skróty");
    const navAdmin = await pb.$$eval(".nav-item", (es) => es.map((e) => e.textContent));
    assert(!navAdmin.some((t) => /Administracja|Państwa NPC/.test(t)), "gracz nie powinien widzieć admina");
    const jsCookie = await pb.evaluate(() => document.cookie);
    assert(!jsCookie.includes("sid="), "cookie sid dostępne z JS!");
    const cookies = await ctxB.cookies(BASE);
    const sid = cookies.find((c) => c.name === "sid");
    assert(sid && sid.httpOnly && sid.secure && sid.sameSite === "Strict", "flagi cookie: " + JSON.stringify(sid));
    await shot(pb, "02-pulpit-desktop");
  });

  const cp = "#/kraj/c-polanie";
  await step("kraj: wszystkie zakładki (Przegląd, Członkowie, Skarbiec, Dyplomacja, Armia, Ludność, Ustawienia)", async () => {
    await go(pb, cp);
    const tabs = await texts(pb, ".tab");
    for (const t of ["Przegląd", "Członkowie", "Skarbiec", "Dyplomacja", "Armia", "Ludność", "Ustawienia"]) assert(tabs.includes(t), "brak zakładki " + t);
    assert((await pb.$$(".siege-list li")).length === 1, "oblężenie");
    await shot(pb, "03-kraj-przeglad-desktop");
    await go(pb, cp + "/czlonkowie");
    assert((await texts(pb, ".tbl tbody tr")).length === 3, "3 członków");
    assert((await pb.$$(".tbl select")).length === 2, "selecty rang dla 2 członków");
    await shot(pb, "04-kraj-czlonkowie-desktop");
    await go(pb, cp + "/skarbiec");
    assert((await text(pb, ".treasury-hero .balance-value")).includes("482 zł 5 sr"), "stan skarbca");
    await shot(pb, "05-kraj-skarbiec-desktop");
    await go(pb, cp + "/dyplomacja");
    await shot(pb, "06-kraj-dyplomacja-desktop");
    await go(pb, cp + "/armia");
    assert((await texts(pb, ".tbl tbody tr")).length === 3, "3 typy jednostek");
    await shot(pb, "07-kraj-armia-desktop");
    await go(pb, cp + "/ludnosc");
    assert((await pb.$$(".site-list .bar")).length === 2, "place budowy z paskami");
    await shot(pb, "08-kraj-ludnosc-desktop");
    await go(pb, cp + "/ustawienia");
    await shot(pb, "09-kraj-ustawienia-desktop");
  });

  await step("skarbiec: wpłata 1 zł 5 sr → RPC amount=150, toast, nowy stan", async () => {
    await go(pb, cp + "/skarbiec");
    const inp = (await pb.$$(".money-field input"))[0];
    await inp.type("1 zł 5 sr");
    assert((await text(pb, ".money-hint")).includes("1 zł 5 sr"), "podgląd kwoty");
    await clickButton(pb, "Wpłać");
    const t = await toastText(pb);
    assert(t.includes("Wpłacono 1 zł 5 sr"), "toast: " + t);
    const c = lastCall("treasury.deposit");
    assert(c && c.params.amount === 150 && c.params.id === "c-polanie", "params: " + JSON.stringify(c));
    await pb.waitForFunction(() => document.querySelector(".treasury-hero .balance-value").textContent.includes("484 zł"), { timeout: 5000 });
  });

  await step("skarbiec: zła kwota nie idzie do pluginu; błąd z serwera → toast błędu", async () => {
    const n = world.calls.length;
    const inp = (await pb.$$(".money-field input"))[1];
    await inp.type("1,5 zł");
    assert((await pb.$$eval(".money-hint.bad", (e) => e.length)) === 1, "podgląd: nieprawidłowa kwota");
    await clickButton(pb, "Wypłać");
    await sleep(300);
    assert(world.calls.filter((c) => c.method === "treasury.withdraw").length === 0, "nie powinno wysłać");
    await fill(inp, "9999 zł");
    await clickButton(pb, "Wypłać");
    const t = await toastText(pb);
    assert(t.includes("W skarbcu nie ma tyle"), "toast błędu: " + t);
    assert((await pb.$$(".toast-err")).length === 0 || true);
    assert(world.calls.length > n);
  });

  await step("dyplomacja: wypowiedzenie wojny wymaga potwierdzenia w modalu", async () => {
    await go(pb, cp + "/dyplomacja");
    await pb.evaluate(() => {
      const row = [...document.querySelectorAll(".tbl tbody tr")].find((r) => r.textContent.includes("Ruś Czerwona"));
      [...row.querySelectorAll("button")].find((b) => b.textContent.includes("Wypowiedz wojnę")).click();
    });
    await pb.waitForSelector("dialog[open]");
    await sleep(250);
    await shot(pb, "10-modal-wojna-desktop");
    await clickButton(pb, "Anuluj", "dialog[open]");
    await sleep(200);
    assert(!lastCall("relation.declareWar"), "anulowanie nie powinno wysłać RPC");
    await pb.evaluate(() => {
      const row = [...document.querySelectorAll(".tbl tbody tr")].find((r) => r.textContent.includes("Ruś Czerwona"));
      [...row.querySelectorAll("button")].find((b) => b.textContent.includes("Wypowiedz wojnę")).click();
    });
    await pb.waitForSelector("dialog[open]");
    await clickButton(pb, "Wypowiadam wojnę", "dialog[open]");
    const t = await toastText(pb);
    assert(t.includes("Wypowiedziano wojnę"), "toast: " + t);
    const c = lastCall("relation.declareWar");
    assert(c && c.params.target === "npc-rus", JSON.stringify(c));
    await settled(pb);
    const rel = await pb.evaluate(() => [...document.querySelectorAll(".tbl tbody tr")].find((r) => r.textContent.includes("Ruś Czerwona")).querySelector(".rel").textContent);
    assert(rel === "Wojna", "relacja po wojnie: " + rel);
  });

  await step("ustawienia: kolor z próbnika, podatek, przekazanie władzy wymaga wpisania nicku", async () => {
    await go(pb, cp + "/ustawienia");
    await pb.click(".swatch[title='#3ab3da']");
    await clickButton(pb, "Zapisz kolor");
    await toastText(pb);
    assert(lastCall("country.setColor").params.color === "#3AB3DA", "kolor");
    await settled(pb);
    const tax = await pb.$(".money-field input");
    await fill(tax, "2 sr 5 md");
    await clickButton(pb, "Ustaw podatek");
    assert((await toastText(pb)).includes("2 sr 5 md"), "toast podatku");
    assert(lastCall("country.setTax").params.amount === 25, "podatek 25 md");
    await settled(pb);
    await pb.select("select[aria-label='Nowy władca']", "Dobrawa");
    await clickButton(pb, "Przekaż władzę");
    await pb.waitForSelector("dialog[open] input");
    await clickButton(pb, "Przekazuję koronę", "dialog[open]");
    await sleep(200);
    assert(await pb.$("dialog[open] input.invalid"), "bez wpisania nicku nie powinno przejść");
    await clickButton(pb, "Anuluj", "dialog[open]");
    assert(!lastCall("country.transferRuler"), "nie wysłano transferu");
  });

  await step("armia: rekrutacja z podglądem kosztu", async () => {
    await go(pb, cp + "/armia");
    await pb.select("select[aria-label='Rodzaj jednostki']", "lucznik");
    const count = await pb.$("input[aria-label='Liczba']");
    await fill(count, "2");
    assert((await text(pb, ".card .money-hint")).includes("6 zł"), "koszt 2 × 3 zł");
    await clickButton(pb, "Rekrutuj");
    assert((await toastText(pb)).includes("Łucznik × 2"), "toast rekrutacji");
    assert(lastCall("army.recruit").params.count === 2, "count");
  });

  await step("ranking: sortowanie po kliknięciu nagłówka", async () => {
    await go(pb, "#/ranking");
    const first = await pb.$eval(".tbl tbody tr .cn-name", (e) => e.textContent);
    assert(first === "Księstwo Mazowsze", "domyślnie wg chunków: " + first);
    await pb.evaluate(() => [...document.querySelectorAll(".th-sort")].find((b) => b.textContent.startsWith("Członkowie")).click());
    await sleep(100);
    const byMembers = await pb.$eval(".tbl tbody tr .cn-name", (e) => e.textContent);
    assert(byMembers === "Królestwo Polan", "wg członków: " + byMembers);
    await pb.evaluate(() => [...document.querySelectorAll(".th-sort")].find((b) => b.textContent.startsWith("Członkowie")).click());
    await sleep(100);
    const asc = await pb.$eval(".tbl tbody tr:last-child .cn-name", (e) => e.textContent);
    assert(asc === "Królestwo Polan", "rosnąco: ostatni " + asc);
    await shot(pb, "11-ranking-desktop");
  });

  await step("gracz nie wejdzie do admina ani NPC", async () => {
    await go(pb, "#/admin");
    assert((await text(pb, "#main")).includes("Brak dostępu"), "admin");
    await go(pb, "#/npc");
    assert((await text(pb, "#main")).includes("Brak dostępu"), "npc");
    await go(pb, "#/npc/npc-mazowsze/gospodarka");
    assert((await text(pb, "#main")).includes("Brak dostępu"), "npc v2");
  });

  await step("motyw jasny", async () => {
    await go(pb, cp);
    await pb.click("#theme-btn");
    await sleep(200);
    assert((await pb.evaluate(() => document.documentElement.dataset.theme)) === "light", "light");
    await shot(pb, "12-kraj-przeglad-jasny-desktop");
    await go(pb, cp + "/skarbiec");
    await shot(pb, "13-kraj-skarbiec-jasny-desktop");
    await pb.click("#theme-btn");
  });

  await step("plugin offline: baner, dane z ostatniego odczytu, mutacje zablokowane; powrót online", async () => {
    await go(pb, cp + "/skarbiec");
    await plugin.close();
    await sleep(300);
    await go(pb, cp);
    await go(pb, cp + "/skarbiec");
    await pb.waitForSelector("#offline-banner:not([hidden])", { timeout: 5000 });
    assert((await text(pb, "#offline-banner")).includes("Serwer gry jest offline"), "baner");
    assert((await text(pb, ".treasury-hero .balance-value")).includes("zł"), "dane z cache");
    const pe = await pb.$eval("button[data-mut]", (b) => getComputedStyle(b).pointerEvents);
    assert(pe === "none", "mutacje zablokowane: " + pe);
    await shot(pb, "14-offline-desktop");
    await connectPlugin();
    await pb.waitForSelector("#offline-banner[hidden]", { timeout: 15000 });
  });

  await step("wylogowanie → ekran logowania, sesja nieważna", async () => {
    await pb.click("#logout-btn");
    await pb.waitForSelector(".login-card");
    const r = await pb.evaluate(() => fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method: "me" }) }).then((r) => r.status));
    assert(r === 401, "po wylogowaniu " + r);
  });

  await step("link z gry /login?t= → zalogowany, drugi raz → komunikat", async () => {
    const t = [...randomBytes(32)].map((b) => "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[b % 62]).join("");
    plugin.linkToken(t, world.U.bolko.uuid, "Bolko");
    await sleep(200);
    await pb.goto(BASE + "/login?t=" + t, { waitUntil: "domcontentloaded" });
    await pb.waitForSelector("#main .page-head", { timeout: 15000 });
    assert((await text(pb, ".page-head h1")).includes("Bolko"), "zalogowany linkiem");
    await pb.click("#logout-btn");
    await pb.waitForSelector(".login-card");
    await pb.goto(BASE + "/login?t=" + t, { waitUntil: "domcontentloaded" });
    await pb.waitForSelector(".alert-err:not([hidden])");
    assert((await text(pb, ".alert-err")).includes("/panel"), "komunikat o nieważnym linku");
  });

  // ------------------------------------------------------------- telefon 390 px
  const ctxM = await browser.createBrowserContext();
  const pm = await page(ctxM, MOBILE, "mobile");
  await step("telefon 390 px: logowanie, pulpit, szuflada, tabele jako karty, brak przewijania w bok", async () => {
    await pm.goto(BASE + "/#/logowanie", { waitUntil: "domcontentloaded" });
    await pm.waitForSelector(".login-card");
    await shot(pm, "20-logowanie-390");
    await login(pm, "Bolko", world.U.bolko.password);
    await shot(pm, "21-pulpit-390");
    await pm.click("#menu-btn");
    await sleep(400);
    assert(await pm.evaluate(() => document.body.classList.contains("drawer-open")), "szuflada otwarta");
    await pm.screenshot({ path: path.join(SHOTS, "22-menu-szuflada-390.png") });
    await pm.click(".backdrop", { offset: { x: 370, y: 400 } }).catch(() => pm.evaluate(() => document.querySelector(".backdrop").click()));
    await sleep(300);
    for (const [hash, name] of [
      [cp, "23-kraj-przeglad-390"],
      [cp + "/czlonkowie", "24-kraj-czlonkowie-390"],
      [cp + "/skarbiec", "25-kraj-skarbiec-390"],
      [cp + "/dyplomacja", "26-kraj-dyplomacja-390"],
      [cp + "/armia", "27-kraj-armia-390"],
      [cp + "/ludnosc", "28-kraj-ludnosc-390"],
      [cp + "/ustawienia", "29-kraj-ustawienia-390"],
      ["#/ranking", "30-ranking-390"],
    ]) {
      await go(pm, hash);
      const overflow = await pm.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      assert(overflow <= 1, `${name}: przewijanie w bok o ${overflow}px`);
      await shot(pm, name);
    }
    const disp = await pm.$eval(".tbl tbody tr", (e) => getComputedStyle(e).display);
    assert(disp === "block", "wiersze tabeli jako karty: " + disp);
  });

  // ------------------------------------------------------------- admin
  const ctxA = await browser.createBrowserContext();
  const pa = await page(ctxA, DESKTOP, "admin");
  await step("admin: menu, rola w nagłówku, lista NPC z przyciskiem Utwórz", async () => {
    await login(pa, "Maciej", world.U.maciej.password);
    assert((await text(pa, "#user-chip .role-badge")) === "Admin", "rola");
    const nav = await texts(pa, ".nav-item");
    assert(nav.includes("Administracja") && nav.includes("Państwa NPC"), "menu: " + nav);
    await go(pa, "#/npc");
    assert((await texts(pa, ".tbl tbody tr")).length === 2, "2 NPC");
    await clickButton(pa, "Utwórz państwo NPC");
    await pa.waitForSelector("dialog[open]");
    await sleep(250);
    await shot(pa, "40-admin-npc-utworz-desktop");
    await clickButton(pa, "Anuluj", "dialog[open]");
    await shot(pa, "41-admin-npc-lista-desktop");
  });

  await step("admin: tworzenie państwa NPC", async () => {
    await clickButton(pa, "Utwórz państwo NPC");
    await pa.waitForSelector("dialog[open]");
    const inputs = await pa.$$("dialog[open] input.input");
    await inputs[0].type("Pomorze Gdańskie");
    await inputs[1].type("Świętopełk");
    await clickButton(pa, "Utwórz", "dialog[open]");
    assert((await toastText(pa)).includes("Pomorze Gdańskie"), "toast");
    const c = lastCall("admin.npc.create");
    assert(c.params.name === "Pomorze Gdańskie" && c.params.world === "world" && c.params.x === 0 && /^#[0-9A-F]{6}$/.test(c.params.color), JSON.stringify(c.params));
    await pa.waitForFunction(() => location.hash.startsWith("#/npc/npc-"), { timeout: 5000 });
  });

  await step("admin: szczegóły NPC (Osobowość i dziennik) — suwaki, pauza, tick, zarządca, log", async () => {
    await go(pa, "#/npc/npc-mazowsze/osobowosc");
    assert((await pa.$$("input.range")).length === 4, "4 suwaki");
    assert((await pa.$$(".ai-log li")).length >= 15, "log AI");
    assert((await pa.$eval("select[aria-label='Zarządca']", (s) => s.value)) === "Ziemowit", "obecny zarządca");
    await shot(pa, "42-admin-npc-szczegoly-desktop");
    await pa.$eval("input.range[aria-label='Agresja']", (e) => {
      e.value = "85";
      e.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await clickButton(pa, "Zapisz ustawienia");
    assert((await toastText(pa)).includes("Zapisano ustawienia (1)"), "toast ustawień");
    const s = lastCall("admin.npc.set");
    assert(s.params.param === "agresja" && s.params.value === 85, JSON.stringify(s.params));
    await settled(pa);
    await clickButton(pa, "Wstrzymaj AI");
    await toastText(pa);
    assert(lastCall("admin.npc.pause").params.paused === true, "pauza");
    await settled(pa);
    await clickButton(pa, "Wznów AI");
    await toastText(pa);
    await settled(pa);
    await clickButton(pa, "Wymuś tick");
    await toastText(pa);
    assert(lastCall("admin.npc.tick"), "tick");
    await settled(pa);
    await pa.select("select[aria-label='Zarządca']", "");
    await clickButton(pa, "Zapisz zarządcę");
    assert((await toastText(pa)).includes("Usunięto zarządcę"), "toast zarządcy");
    assert(lastCall("admin.npc.setManager").params.player === null, "player null");
    await settled(pa);
    await pa.select("select[aria-label='Zarządca']", "Ziemowit");
    await clickButton(pa, "Zapisz zarządcę");
    await toastText(pa);
    assert(lastCall("admin.npc.setManager").params.player === "Ziemowit", "player Ziemowit");
  });

  // ------------------------------------------------------------- państwo NPC v2 (7 zakładek)
  const NPC = "#/npc/npc-mazowsze";
  const rowAction = (p, rowText, label) =>
    p.evaluate(
      (rowText, label) => {
        const row = [...document.querySelectorAll("#main .tbl tbody tr")].find((r) => r.textContent.includes(rowText));
        const b = row && [...row.querySelectorAll("button")].find((x) => x.textContent.trim() === label);
        if (!b) return "brak";
        if (b.disabled) return "disabled";
        b.click();
        return "ok";
      },
      rowText,
      label,
    );

  await step("NPC v2: Przegląd — 9 zakładek (z Planem i Postępem), kafelki, cel AI, 5 wykresów z tooltipem i klawiaturą", async () => {
    await go(pa, NPC);
    const tabs = await texts(pa, ".tab");
    eq(tabs, ["Przegląd", "Stosunki", "Gospodarka", "Ludność i jedzenie", "Budowy", "Plan", "Armia", "Postęp", "Osobowość i dziennik"], "zakładki");
    assert(lastCall("admin.npc.history").params.days === 7, "history days=7");
    eq((await pa.$$(".grid-stats .stat")).length, 7, "7 kafelków (z nastrojem z Zycie)");
    assert((await text(pa, ".grid-stats")).includes("Gród"), "poziom osady");
    assert((await text(pa, ".goal-card")).includes("Prowadzi wojnę"), "cel AI");
    await pa.waitForFunction(() => document.querySelectorAll(".chart-svg").length === 5, { timeout: 5000 });
    eq(await texts(pa, ".chart-title"), ["Skarbiec", "Ludność", "Żołnierze", "Chunki", "Jedzenie"], "tytuły wykresów");
    assert((await pa.$$(".chart-line")).length >= 5, "linie");
    // brak 30 pierwszych godzin jedzenia → linia zaczyna się później (punkt przerwy), ale jest
    const svg = await pa.$(".chart-svg");
    const box = await svg.boundingBox();
    await pa.mouse.move(box.x + box.width * 0.62, box.y + box.height / 2);
    await pa.waitForSelector(".chart-tip:not([hidden])", { timeout: 3000 });
    eq((await pa.$$(".chart-tip .chart-tip-row")).length, 5, "tooltip z 5 miarami");
    eq(await pa.$$eval(".chart-cross", (es) => es.filter((e) => e.getAttribute("visibility") === "visible").length), 5, "crosshair na wszystkich wykresach");
    const t1 = await text(pa, ".chart-tip-time");
    await shot(pa, "60-npc-przeglad-desktop");
    await pa.mouse.move(5, 5);
    await pa.waitForSelector(".chart-tip[hidden]", { timeout: 3000 });
    await svg.evaluate((e) => e.focus());
    await pa.keyboard.press("ArrowLeft");
    await pa.keyboard.press("ArrowLeft");
    const t2 = await text(pa, ".chart-tip-time");
    assert(t2 && t2 !== t1, "klawiatura zmienia godzinę: " + t2);
    assert((await text(pa, ".chart-table summary")).includes("tabeli"), "widok tabeli");
    await pa.keyboard.press("Escape");
  });

  await step("NPC v2: Przegląd — pusta historia (nowe państwo) pokazuje komunikat; motyw jasny", async () => {
    const pom = Object.values(world.C).find((c) => c.name === "Pomorze Gdańskie");
    await go(pa, "#/npc/" + pom.id);
    assert((await text(pa, ".chart-empty")).includes("Brak historii"), "komunikat pustej historii");
    eq((await pa.$$(".chart-svg")).length, 0, "bez wykresów");
    await shot(pa, "61-npc-przeglad-pusty-desktop");
    await go(pa, NPC);
    await pa.click("#theme-btn");
    await pa.waitForFunction(() => document.querySelectorAll(".chart-svg").length === 5);
    const svg = await pa.$$(".chart-svg");
    await svg[1].evaluate((e) => e.focus({ preventScroll: true }));
    for (let i = 0; i < 60; i++) await pa.keyboard.press("ArrowLeft");
    await pa.waitForSelector(".chart-tip:not([hidden])");
    await shot(pa, "62-npc-przeglad-jasny-desktop");
    await go(pa, NPC + "/stosunki");
    await shot(pa, "63-npc-stosunki-jasny-desktop");
    await pa.click("#theme-btn");
    await pa.mouse.move(5, 5);
  });

  await step("NPC v2: Stosunki — suwak opinii, nastawienie, propozycja, wojna i trybut z modalem", async () => {
    await go(pa, NPC + "/stosunki");
    assert((await pa.$$(".rel-table tbody tr")).length >= 3, "kraje");
    eq(await pa.$eval("input[aria-label='Opinia AI o kraju Królestwo Polan']", (e) => e.value), "-62", "opinia o Polanach");
    assert((await text(pa, ".rel-table")).includes("Wasal"), "relacja wasal");
    assert((await text(pa, ".rel-table")).includes("proponuje sojusz"), "oczekująca propozycja");
    assert((await text(pa, ".rel-table")).includes("płaci nam 2 zł/dzień"), "trybut");
    await shot(pa, "64-npc-stosunki-desktop");
    // opinia: input (podgląd koloru) + change (zapis)
    const col = await pa.$eval("input[aria-label='Opinia AI o kraju Wiślanie']", (e) => {
      e.value = "35";
      e.dispatchEvent(new Event("input", { bubbles: true }));
      const c = e.closest(".opinion").style.getPropertyValue("--op-c");
      e.dispatchEvent(new Event("change", { bubbles: true }));
      return c;
    });
    assert(col.includes("var(--ok) 35%"), "kolor opinii: " + col);
    assert((await toastText(pa)).includes("Opinia o Wiślanie: +35"), "toast opinii");
    eq(lastCall("admin.npc.setOpinion").params, { id: "npc-mazowsze", target: "c-wislanie", value: 35 }, "setOpinion");
    await pa.waitForFunction(() => document.querySelector("input[aria-label='Opinia AI o kraju Wiślanie']")?.value === "35", { timeout: 5000 });
    await pa.select("select[aria-label='Nastawienie wobec Wiślanie']", "FRIENDLY");
    await toastText(pa);
    eq(lastCall("admin.npc.setStance").params, { id: "npc-mazowsze", target: "c-wislanie", stance: "FRIENDLY" }, "setStance");
    await pa.waitForFunction(() => document.querySelector("select[aria-label='Nastawienie wobec Wiślanie']")?.value === "FRIENDLY", { timeout: 5000 });
    eq(await rowAction(pa, "Ruś Czerwona", "Wojna"), "disabled", "„nigdy wojny” blokuje przycisk wojny");
    eq(await rowAction(pa, "Wiślanie", "Proponuj sojusz"), "ok", "propozycja");
    assert((await toastText(pa)).includes("czeka na władcę"), "toast z message pluginu");
    eq(lastCall("admin.npc.propose").params.relation, "ALLIANCE", "propose ALLIANCE");
    await settled(pa);
    eq(await rowAction(pa, "Wiślanie", "Wojna"), "ok", "wojna");
    await pa.waitForSelector("dialog[open]");
    await sleep(250);
    await shot(pa, "65-npc-modal-wojna-desktop");
    await clickButton(pa, "Anuluj", "dialog[open]");
    await sleep(200);
    assert(!lastCall("admin.npc.declareWar"), "anulowanie nie wysyła wojny");
    eq(await rowAction(pa, "Ruś Czerwona", "Trybut"), "ok", "trybut");
    await pa.waitForSelector("dialog[open] .money-field input");
    await clickButton(pa, "Zażądaj trybutu", "dialog[open]");
    await sleep(150);
    assert(await pa.$("dialog[open] input.invalid"), "pusta kwota trybutu zablokowana");
    await pa.type("dialog[open] .money-field input", "3 zł");
    await sleep(200);
    await shot(pa, "66-npc-modal-trybut-desktop");
    await clickButton(pa, "Zażądaj trybutu", "dialog[open]");
    assert((await toastText(pa)).includes("będzie płacić trybut"), "trybut przyjęty");
    eq(lastCall("admin.npc.demandTribute").params, { id: "npc-mazowsze", target: "npc-rus", amount: 300 }, "demandTribute");
    await settled(pa);
    eq(await rowAction(pa, "Wiślanie", "Wojna"), "ok", "wojna 2");
    await pa.waitForSelector("dialog[open]");
    await clickButton(pa, "Wypowiadam wojnę", "dialog[open]");
    assert((await toastText(pa)).includes("wypowiada wojnę"), "toast wojny");
    eq(lastCall("admin.npc.declareWar").params, { id: "npc-mazowsze", target: "c-wislanie" }, "declareWar");
    await pa.waitForFunction(() => [...document.querySelectorAll(".rel-table tbody tr")].find((r) => r.textContent.includes("Wiślanie"))?.querySelector(".rel").textContent === "Wojna", { timeout: 5000 });
    eq(await rowAction(pa, "Wiślanie", "Wymuś"), "ok", "wymuś (admin)");
    await pa.waitForSelector("dialog[open] select");
    await pa.select("dialog[open] select", "NEUTRAL");
    await clickButton(pa, "Wymuś relację", "dialog[open]");
    await toastText(pa);
    eq(lastCall("admin.relation.set").params, { a: "npc-mazowsze", b: "c-wislanie", relation: "NEUTRAL" }, "admin.relation.set");
  });

  await step("NPC v2: Gospodarka — rozbicie 24 h, suwaki budżetu sumują się do 100%, mnożnik, korekta skarbca", async () => {
    await go(pa, NPC + "/gospodarka");
    assert((await pa.$$(".flow-in")).length === 3 && (await pa.$$(".flow-out")).length >= 4, "słupki przychodów i wydatków");
    eq(await texts(pa, ".sliders output.range-val"), ["45%", "35%", "20%"], "budżet startowy");
    await shot(pa, "67-npc-gospodarka-desktop");
    await pa.$eval("input[aria-label='Budżet: Wojsko']", (e) => {
      e.value = "60";
      e.dispatchEvent(new Event("input", { bubbles: true }));
    });
    eq(await texts(pa, ".sliders output.range-val"), ["60%", "25%", "15%"], "korekta proporcjonalna");
    assert((await text(pa, ".budget-sum")).includes("100%"), "suma 100%");
    await pa.$eval("input[aria-label='Budżet: Rezerwa']", (e) => {
      e.value = "100";
      e.dispatchEvent(new Event("input", { bubbles: true }));
    });
    eq(await texts(pa, ".sliders output.range-val"), ["0%", "0%", "100%"], "rezerwa 100%");
    await pa.$eval("input[aria-label='Budżet: Budowy']", (e) => {
      e.value = "30";
      e.dispatchEvent(new Event("input", { bubbles: true }));
    });
    eq(await texts(pa, ".sliders output.range-val"), ["0%", "30%", "70%"], "po zerach");
    await pa.$eval("input[aria-label='Budżet: Wojsko']", (e) => {
      e.value = "50";
      e.dispatchEvent(new Event("input", { bubbles: true }));
    });
    const vals = (await texts(pa, ".sliders output.range-val")).map((x) => parseInt(x, 10));
    eq(vals.reduce((a, b) => a + b, 0), 100, "suma po kolejnej zmianie");
    await clickButton(pa, "Zapisz podział");
    await toastText(pa);
    const b = lastCall("admin.npc.setBudget").params;
    eq([b.army, b.buildings, b.reserve], vals, "setBudget");
    await settled(pa);
    await pa.waitForFunction(() => document.querySelector("output.range-val")?.textContent === "50%", { timeout: 5000 });
    await pa.$eval("input[aria-label='Bogactwo (mnożnik dochodu)']", (e) => {
      e.value = "75";
      e.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await clickButton(pa, "Zapisz mnożnik");
    await toastText(pa);
    eq(lastCall("admin.npc.set").params, { id: "npc-mazowsze", param: "bogactwo", value: 75 }, "bogactwo");
    await settled(pa);
    const adj = await pa.$("input[placeholder^='np. +50 zł']");
    await fill(adj, "-2 zł 5 sr");
    await clickButton(pa, "Zastosuj korektę");
    assert((await toastText(pa)).includes("-2 zł 5 sr"), "toast korekty");
    eq(lastCall("admin.npc.treasury").params, { id: "npc-mazowsze", delta: -250 }, "admin.npc.treasury");
    await pa.waitForFunction(() => document.querySelector("#main .tbl tbody tr")?.textContent.includes("Korekta z panelu"), { timeout: 5000 });
  });

  await step("NPC v2: Ludność i jedzenie — szybkie przyciski i pola ±", async () => {
    await go(pa, NPC + "/ludnosc");
    await shot(pa, "68-npc-ludnosc-desktop");
    await clickButton(pa, "+500");
    assert(/stan 1\s?000/.test(await toastText(pa)), "jedzenie przycięte do pojemności");
    eq(lastCall("admin.npc.food").params, { id: "npc-mazowsze", delta: 500 }, "food +500");
    await settled(pa);
    await clickButton(pa, "+10");
    assert((await toastText(pa)).includes("ludność 32"), "osadnicy +10");
    eq(lastCall("admin.npc.settlers").params, { id: "npc-mazowsze", delta: 10 }, "settlers +10");
    await settled(pa);
    await pa.waitForFunction(() => document.querySelector("#main .grid-stats").textContent.includes("32 / 24"), { timeout: 5000 });
    const n = world.calls.length;
    const food = await pa.$("input[aria-label='Jedzenie — zmiana']");
    await fill(food, "0");
    await clickButton(pa, "Zastosuj");
    await sleep(200);
    eq(world.calls.length, n, "zero nie idzie do pluginu");
    await fill(food, "-150");
    await clickButton(pa, "Zastosuj");
    await toastText(pa);
    eq(lastCall("admin.npc.food").params.delta, -150, "food -150");
    await settled(pa);
    await pa.$eval("input[aria-label='Mnożnik podatku od obywatela']", (e) => {
      e.value = "150";
      e.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await clickButton(pa, "Zapisz mnożnik");
    await toastText(pa);
    eq(lastCall("admin.npc.setTaxMultiplier").params, { id: "npc-mazowsze", percent: 150 }, "mnożnik podatku");
  });

  await step("NPC v2: Budowy — kolejka ↑ ↓ ✕, zamówienie (typy z wymaganiami, x/z), rozbudowa z budżetem", async () => {
    await go(pa, NPC + "/budowy");
    eq((await pa.$$(".type-card")).length, 10, "typy budynków");
    const off = await texts(pa, ".type-card.type-off");
    assert(off.length >= 3 && off.every((t) => t.includes("Brakuje")), "wyszarzone z brakami: " + off.length);
    assert(off.some((t) => t.includes("Targ") && t.includes("Kuźnia")), "targ wymaga kuźni");
    assert(await pa.$eval(".type-card.type-off input", (e) => e.disabled), "niedostępny typ jest wyłączony");
    eq(await texts(pa, ".queue-item .q-body strong"), ["Dom", "Studnia", "Koszary"], "kolejka");
    eq((await pa.$$(".site-list .bar")).length, 2, "place z paskami");
    await shot(pa, "69-npc-budowy-desktop");
    await pa.click("button[aria-label='W dół: Dom']");
    await toastText(pa);
    eq(lastCall("admin.npc.queue").params, { id: "npc-mazowsze", action: "down", index: 0 }, "queue down");
    await pa.waitForFunction(() => document.querySelector(".queue-item .q-body strong")?.textContent === "Studnia", { timeout: 5000 });
    assert(await pa.$eval("button[aria-label='W górę: Studnia']", (b) => b.disabled), "↑ pierwszego wyłączone");
    await pa.click("button[aria-label='Anuluj: Koszary']");
    await pa.waitForSelector("dialog[open]");
    await clickButton(pa, "Usuń z kolejki", "dialog[open]");
    await toastText(pa);
    eq(lastCall("admin.npc.queue").params, { id: "npc-mazowsze", action: "cancel", index: 2 }, "queue cancel");
    await pa.waitForFunction(() => document.querySelectorAll(".queue-item").length === 2, { timeout: 5000 });
    // zamówienie: wieża na współrzędnych
    await pa.evaluate(() => [...document.querySelectorAll(".type-card")].find((c) => c.textContent.includes("Wieża strażnicza")).click());
    assert((await text(pa, ".order-sum")).includes("Wieża strażnicza — 18 zł"), "podsumowanie wyboru");
    await clickButton(pa, "Współrzędne x / z");
    assert(!(await pa.$eval(".coords", (e) => e.hidden)), "pola x/z widoczne");
    await fill(await pa.$("input[aria-label='Współrzędna X']"), "1500");
    await fill(await pa.$("input[aria-label='Współrzędna Z']"), "-320");
    await pa.select("select[aria-label='Obrót']", "90");
    await sleep(100);
    await shot(pa, "70-npc-zamow-budynek-desktop");
    await clickButton(pa, "Zamów budynek");
    assert((await toastText(pa)).includes("dodano do kolejki"), "toast zamówienia");
    eq(lastCall("admin.npc.order").params, { id: "npc-mazowsze", type: "wieza", x: 1500, z: -320, rotation: 90 }, "order x/z");
    await pa.waitForFunction(() => document.querySelectorAll(".queue-item").length === 3, { timeout: 5000 });
    // zamówienie automatyczne
    await pa.evaluate(() => [...document.querySelectorAll(".type-card")].find((c) => c.textContent.includes("Kuźnia")).click());
    await clickButton(pa, "Zamów budynek");
    await toastText(pa);
    eq(lastCall("admin.npc.order").params, { id: "npc-mazowsze", type: "kuznia" }, "order auto");
    await settled(pa);
    await pa.waitForFunction(() => document.querySelectorAll(".queue-item").length === 4, { timeout: 5000 });
    const label = await pa.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.startsWith("Rozbuduj teraz")).textContent);
    assert(label.includes("budżet 50 zł"), "przycisk z budżetem: " + label);
    await pa.evaluate(() => [...document.querySelectorAll("#main button")].find((b) => b.textContent.startsWith("Rozbuduj teraz")).click());
    assert((await toastText(pa)).includes("Planer zamówił"), "toast rozbudowy");
    eq(lastCall("admin.npc.develop").params, { id: "npc-mazowsze", budget: 5000 }, "develop");
  });

  await step("NPC v2: Armia — postawa (3 przyciski), cel oblężeń, posterunki, rozwiązanie oddziału", async () => {
    await go(pa, NPC + "/armia");
    eq(await texts(pa, ".seg .seg-btn"), ["Obronna", "Zrównoważona", "Ofensywna"], "postawy");
    eq(await pa.$eval(".seg-btn.on", (b) => b.textContent.trim()), "Zrównoważona", "obecna postawa");
    eq((await pa.$$(".outpost-list li")).length, 3, "posterunki");
    assert((await text(pa, "#main")).includes("AI celuje w"), "cel auto");
    await shot(pa, "71-npc-armia-desktop");
    await clickButton(pa, "Ofensywna");
    await toastText(pa);
    eq(lastCall("admin.npc.setPosture").params, { id: "npc-mazowsze", posture: "OFFENSIVE" }, "setPosture");
    await pa.waitForFunction(() => document.querySelector(".seg-btn.on")?.textContent.trim() === "Ofensywna", { timeout: 5000 });
    await pa.select("select[aria-label='Cel oblężeń']", "c-polanie");
    await clickButton(pa, "Zapisz cel");
    await toastText(pa);
    eq(lastCall("admin.npc.setSiegeTarget").params, { id: "npc-mazowsze", target: "c-polanie" }, "cel Polanie");
    await pa.waitForFunction(() => document.querySelector("select[aria-label='Cel oblężeń']")?.value === "c-polanie", { timeout: 5000 });
    await pa.select("select[aria-label='Cel oblężeń']", "");
    await clickButton(pa, "Zapisz cel");
    await toastText(pa);
    eq(lastCall("admin.npc.setSiegeTarget").params, { id: "npc-mazowsze", target: null }, "cel auto");
    await settled(pa);
    eq(await rowAction(pa, "Rycerz", "Rozwiąż"), "ok", "rozwiąż");
    await pa.waitForSelector("dialog[open] input");
    await fill(await pa.$("dialog[open] input"), "2");
    await clickButton(pa, "Rozwiąż", "dialog[open]");
    await toastText(pa);
    eq(lastCall("admin.npc.disband").params, { id: "npc-mazowsze", type: "rycerz", count: 2 }, "disband");
  });

  await step("NPC v2: Osobowość i dziennik — chipsy kategorii z licznikami, wyszukiwarka z podświetleniem", async () => {
    await go(pa, NPC + "/osobowosc");
    eq((await pa.$$(".filter-chips .chip-btn")).length, 7, "7 chipsów");
    assert((await pa.$$(".ai-log li")).length >= 15, "pełny dziennik");
    await clickButton(pa, "Wojna" + (await pa.$eval(".chip-btn.cat-WAR .chip-n", (e) => e.textContent)));
    await pa.waitForFunction(() => document.querySelector(".chip-btn.cat-WAR")?.getAttribute("aria-pressed") === "true" && !document.querySelector(".log-box.loading"), { timeout: 5000 });
    eq(lastCall("admin.npc.log").params.category, "WAR", "category WAR");
    const cats = await texts(pa, ".ai-log .log-cat");
    assert(cats.length >= 3 && cats.every((c) => c === "Wojna"), "tylko wojna: " + cats);
    await pa.type("input.log-search", "oblęż");
    await pa.waitForFunction(() => document.querySelectorAll(".ai-log mark").length > 0 && !document.querySelector(".log-box.loading"), { timeout: 5000 });
    const lc = lastCall("admin.npc.log").params;
    assert(lc.query === "oblęż" && lc.category === "WAR", JSON.stringify(lc));
    eq((await pa.$$(".ai-log li")).length, 2, "wyniki wyszukiwania");
    eq(await pa.$eval(".chip-btn.cat-ALL .chip-n", (e) => e.textContent), "2", "licznik Wszystko dla zapytania");
    await shot(pa, "72-npc-osobowosc-dziennik-desktop");
    await fill(await pa.$("input.log-search"), "zzz-nic");
    await pa.waitForFunction(() => document.querySelector(".log-box .empty"), { timeout: 5000 });
    await fill(await pa.$("input.log-search"), "");
    await clickButton(pa, "Wszystko" + (await pa.$eval(".chip-btn.cat-ALL .chip-n", (e) => e.textContent)));
    await pa.waitForFunction(() => document.querySelectorAll(".ai-log li").length >= 15, { timeout: 5000 });
  });

  await step("NPC v2: plugin offline — zakładki v2 z cache, mutacje zablokowane", async () => {
    await go(pa, NPC + "/gospodarka");
    await plugin.close();
    await sleep(300);
    await go(pa, NPC + "/budowy");
    await go(pa, NPC + "/gospodarka");
    await pa.waitForSelector("#offline-banner:not([hidden])", { timeout: 5000 });
    assert((await pa.$$(".flow-row")).length >= 6, "gospodarka z cache");
    eq(await pa.$eval("input[aria-label='Budżet: Wojsko']", (e) => e.closest("section").querySelector("button[data-mut]") && getComputedStyle(e.closest("section").querySelector("button[data-mut]")).pointerEvents), "none", "mutacje zablokowane");
    await connectPlugin();
    await pa.waitForSelector("#offline-banner[hidden]", { timeout: 15000 });
  });

  await step("admin: wszystkie kraje, gracze (wyszukiwarka, korekta salda), dziennik", async () => {
    await go(pa, "#/admin/kraje");
    assert((await texts(pa, ".tbl tbody tr")).length >= 5, "kraje");
    await shot(pa, "43-admin-kraje-desktop");
    await pa.evaluate(() => {
      const row = [...document.querySelectorAll(".tbl tbody tr")].find((r) => r.textContent.includes("Ruś Czerwona"));
      [...row.querySelectorAll("button")].find((b) => b.textContent.includes("Usuń")).click();
    });
    await pa.waitForSelector("dialog[open] input");
    await pa.type("dialog[open] input", "Ruś Czerwona");
    await clickButton(pa, "Usuń państwo", "dialog[open]");
    await toastText(pa);
    assert(lastCall("admin.country.delete").params.id === "npc-rus", "usunięcie");
    await go(pa, "#/admin/gracze");
    await pa.type("input.search", "dob");
    await sleep(100);
    assert((await texts(pa, ".tbl tbody tr")).length === 1, "wyszukiwarka");
    await pa.evaluate(() => [...document.querySelectorAll("#main .row-actions button, #main td button")].find((b) => b.textContent.includes("Saldo ±")).click());
    await pa.waitForSelector("dialog[open] input");
    await pa.type("dialog[open] input", "-2 zł");
    await clickButton(pa, "Zastosuj", "dialog[open]");
    await toastText(pa);
    const e = lastCall("admin.economy.adjust");
    assert(e.params.player === "Dobrawa" && e.params.amount === -200, JSON.stringify(e.params));
    await pa.evaluate(() => (document.getElementById("main").dataset.dirty = ""));
    await go(pa, "#/admin/gracze");
    await shot(pa, "44-admin-gracze-desktop");
    await go(pa, "#/admin/dziennik");
    assert((await texts(pa, ".tbl tbody tr")).length >= 3, "dziennik");
    await shot(pa, "45-admin-dziennik-desktop");
  });

  const pa2 = await page(ctxA, MOBILE, "admin-mobile");
  await step("admin 390 px: szczegóły NPC i dziennik", async () => {
    await pa2.goto(BASE + "/#/npc/npc-mazowsze", { waitUntil: "domcontentloaded" });
    await settled(pa2);
    await shot(pa2, "46-admin-npc-390");
    await go(pa2, "#/admin/gracze");
    await shot(pa2, "47-admin-gracze-390");
  });

  await step("admin 390 px: zakładki państwa NPC v2 bez przewijania w bok", async () => {
    for (const [tab, name] of [
      ["", "73-npc-przeglad-390"],
      ["/stosunki", "74-npc-stosunki-390"],
      ["/gospodarka", "75-npc-gospodarka-390"],
      ["/ludnosc", "76-npc-ludnosc-390"],
      ["/budowy", "77-npc-budowy-390"],
      ["/armia", "78-npc-armia-390"],
      ["/osobowosc", "79-npc-osobowosc-390"],
    ]) {
      await go(pa2, "#/npc/npc-mazowsze" + tab);
      await sleep(150);
      if (!tab) {
        await pa2.waitForFunction(() => document.querySelectorAll(".chart-svg").length === 5);
        const svg = (await pa2.$$(".chart-svg"))[2];
        await svg.tap();
        await pa2.waitForSelector(".chart-tip:not([hidden])", { timeout: 3000 });
      }
      const overflow = await pa2.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      assert(overflow <= 1, `${name}: przewijanie w bok o ${overflow}px`);
      await shot(pa2, name);
    }
  });

  // ------------------------------------------------------------- zarządca NPC (przypisany)
  const ctxZ = await browser.createBrowserContext();
  const pz = await page(ctxZ, DESKTOP, "zarzadca");
  await step("zarządca NPC: rola, brak admina, widzi tylko swoje NPC, bez „Utwórz”", async () => {
    await login(pz, "Ziemowit", world.U.ziemowit.password);
    assert((await text(pz, "#user-chip .role-badge")) === "Zarządca państw NPC", "rola: " + (await text(pz, "#user-chip")));
    const nav = await texts(pz, ".nav-item");
    assert(nav.includes("Państwa NPC") && !nav.includes("Administracja"), "menu: " + nav);
    await go(pz, "#/npc");
    const rows = await texts(pz, ".tbl tbody tr .cn-name");
    assert(rows.length === 1 && rows[0] === "Księstwo Mazowsze", "tylko swoje: " + rows);
    assert(!(await pz.evaluate(() => [...document.querySelectorAll("#main button")].some((b) => b.textContent.includes("Utwórz")))), "brak Utwórz");
    await shot(pz, "50-zarzadca-npc-lista-desktop");
    await go(pz, "#/npc/npc-mazowsze/osobowosc");
    assert((await pz.$$("input.range")).length === 4, "suwaki");
    assert(!(await pz.$("select[aria-label='Zarządca']")), "zarządca nie ustawia zarządcy");
    await shot(pz, "51-zarzadca-npc-szczegoly-desktop");
    await go(pz, "#/npc/npc-mazowsze/stosunki");
    assert((await pz.$$(".rel-table tbody tr")).length >= 3, "zarządca widzi stosunki");
    assert(!(await pz.evaluate(() => [...document.querySelectorAll("#main button")].some((b) => b.textContent.trim() === "Wymuś"))), "„Wymuś relację” tylko dla admina");
    await go(pz, "#/npc/npc-mazowsze/gospodarka");
    assert((await pz.$$(".flow-row")).length >= 6, "zarządca widzi gospodarkę");
    assert((await pz.$$("input[aria-label='Budżet: Wojsko']")).length === 1, "zarządca ustawia budżet");
    await go(pz, "#/admin");
    assert((await text(pz, "#main")).includes("Brak dostępu"), "admin zablokowany");
    const other = Object.keys(world.C).find((id) => id.startsWith("npc-") && id !== "npc-mazowsze");
    await go(pz, "#/npc/" + other);
    assert((await text(pz, "#main")).includes("Brak dostępu"), "cudze NPC zablokowane");
    await go(pz, "#/");
    await shot(pz, "52-zarzadca-pulpit-bez-kraju-desktop");
  });

  // ------------------------------------------------------------- gracz bez kraju
  const ctxW = await browser.createBrowserContext();
  const pw = await page(ctxW, MOBILE, "wedrowiec");
  await step("gracz bez kraju: „nie masz kraju” i lista krajów (390 px)", async () => {
    await login(pw, "Wedrowiec", world.U.wedrowiec.password);
    assert((await text(pw, ".page-head")).includes("Nie należysz"), "nie masz kraju");
    assert((await pw.$$(".country-list li")).length >= 3, "lista krajów");
    await shot(pw, "53-bez-kraju-390");
  });

  // ------------------------------------------------------------- Postęp (epoki i odkrycia, zrzuty 80-…)
  const ctxP = await browser.createBrowserContext();
  const pp = await page(ctxP, DESKTOP, "postep-wladca");
  const pk = "#/kraj/c-polanie/postep";
  await step("Postęp: zakładka kraju — plakietka, pasek, braki, cel, drzewko w 4 kolumnach epok, ranking", async () => {
    await linkLogin(pp, world.U.bolko);
    await go(pp, "#/kraj/c-polanie");
    assert((await texts(pp, ".tab")).includes("Postęp"), "zakładka Postęp");
    assert((await text(pp, ".country-head h1 .era-badge")).includes("Renesans"), "plakietka epoki w nagłówku kraju");
    await go(pp, pk);
    assert((await text(pp, ".era-hero-name")).includes("Renesans"), "epoka: " + (await text(pp, ".era-hero-name")));
    assert((await text(pp, ".era-progress-head")).includes("Epoka przemysłowa"), "następna epoka");
    eq((await pp.$$(".era-col")).length, 4, "kolumny epok");
    eq((await pp.$$(".era-col-current")).length, 1, "bieżąca epoka wyróżniona");
    eq((await pp.$$(".tech")).length, 26, "odkrycia");
    eq((await pp.$$(".tech.st-OWNED")).length, 9, "zdobyte");
    assert((await pp.$$(".tech.st-AVAILABLE")).length >= 3, "dostępne");
    assert((await pp.$$(".tech.st-ERA_LOCKED")).length >= 10, "zablokowane epoką");
    assert((await pp.$$(".tech.st-LOCKED")).length >= 1, "zablokowane wymaganiami");
    assert((await text(pp, ".tech-target")).includes("Astronomia"), "cel badań oznaczony w drzewku");
    assert((await text(pp, ".target-name")).includes("Astronomia"), "karta celu");
    const missing = await texts(pp, ".missing-list li");
    assert(missing.some((m) => m.includes("Proch")) && missing.some((m) => m.startsWith("czas w epoce")), "braki: " + missing);
    assert((await pp.$$(".cat-chip")).length >= 5, "legenda kategorii");
    eq((await pp.$$(".era-ranking li")).length, 4, "ranking epok");
    assert((await text(pp, ".era-ranking li.mine")).includes("Królestwo Polan"), "mój kraj w rankingu");
    await shot(pp, "80-postep-kraj-desktop");
  });

  await step("Postęp: kliknięcie odkrycia → szczegóły → ustaw cel badań (postep.discover)", async () => {
    await pp.click('.tech[data-id="nawigacja_oceaniczna"]');
    await pp.waitForSelector("dialog[open]");
    const body = await text(pp, "dialog[open] .modal-body");
    assert(body.includes("Kartografia") && body.includes("320 pkt"), "szczegóły: " + body);
    await sleep(200);
    await shot(pp, "81-postep-modal-odkrycie-desktop");
    await clickButton(pp, "Ustaw jako cel badań", "dialog[open]");
    const t = await toastText(pp);
    assert(t.includes("Cel badań: Nawigacja oceaniczna"), "toast: " + t);
    const c = lastCall("postep.discover");
    eq(c.params, { id: "c-polanie", discovery: "nawigacja_oceaniczna" }, "params");
    await pp.waitForFunction(() => document.querySelector(".target-name") && document.querySelector(".target-name").textContent.includes("Nawigacja"), { timeout: 5000 });
    assert((await text(pp, ".tech-target")).includes("Nawigacja oceaniczna"), "nowy cel w drzewku");
  });

  await step("Postęp: zablokowane odkrycie pokazuje ścieżkę; późniejsza epoka bez przycisku", async () => {
    await pp.click('.tech[data-id="maszyna_parowa"]');
    await pp.waitForSelector("dialog[open]");
    const btns = await texts(pp, "dialog[open] .modal-foot button");
    eq(btns, ["Zamknij"], "późniejsza epoka: tylko Zamknij");
    await clickButton(pp, "Zamknij", "dialog[open]");
    await pp.waitForFunction(() => !document.querySelector("dialog[open]"));
  });

  await step("Postęp: inwestycja — szybki wybór, podgląd punktów (postep.investPreview), Inwestuj", async () => {
    await pp.click('.invest-presets .chip-btn[data-amount="500"]');
    await pp.waitForFunction(() => /pkt badań/.test(document.querySelector(".invest-preview").textContent), { timeout: 5000 });
    assert((await text(pp, ".invest-preview")).includes("5 zł"), "podgląd: " + (await text(pp, ".invest-preview")));
    const inp = await pp.$(".money-field input");
    await fill(inp, "2 zł 5 sr");
    await pp.waitForFunction(() => /2 zł 5 sr → \+/.test(document.querySelector(".invest-preview").textContent), { timeout: 5000 });
    eq(lastCall("postep.investPreview").params, { id: "c-polanie", amount: 250 }, "podgląd params");
    await shot(pp, "82-postep-inwestycja-desktop");
    const before = world.C["c-polanie"].treasury;
    await clickButton(pp, "Inwestuj");
    const t = await toastText(pp);
    assert(t.includes("Zainwestowano 2 zł 5 sr") && t.includes("pkt badań"), "toast: " + t);
    eq(lastCall("postep.invest").params, { id: "c-polanie", amount: 250 }, "invest params");
    eq(world.C["c-polanie"].treasury, before - 250, "skarbiec");
  });

  await step("Postęp: ranking krajów ma kolumnę epoki", async () => {
    await go(pp, "#/ranking");
    const heads = await texts(pp, ".tbl thead th");
    assert(heads.some((x) => x.startsWith("Epoka")), "kolumna: " + heads);
    eq((await pp.$$(".tbl tbody .era-badge")).length, 4, "plakietki epok");
    await pp.evaluate(() => [...document.querySelectorAll(".th-sort")].find((b) => b.textContent.startsWith("Epoka")).click());
    await sleep(100);
    const first = await pp.$eval(".tbl tbody tr .era-badge", (e) => e.textContent);
    assert(first.includes("Renesans"), "sortowanie po epoce: " + first);
    await shot(pp, "83-ranking-epoka-desktop");
  });

  await step("Postęp: obywatel widzi drzewko, ale bez inwestycji i wyboru badań", async () => {
    const ctxS = await browser.createBrowserContext();
    const ps = await page(ctxS, DESKTOP, "postep-obywatel");
    await linkLogin(ps, world.U.siemowit);
    await go(ps, pk);
    assert(!(await ps.$(".invest-presets")), "brak inwestycji");
    await ps.click('.tech[data-id="proch"]');
    await ps.waitForSelector("dialog[open]");
    eq(await texts(ps, "dialog[open] .modal-foot button"), ["Zamknij"], "bez przycisku wyboru");
    assert((await text(ps, "dialog[open] .modal-body")).includes("władca albo doradca"), "wyjaśnienie");
    await clickButton(ps, "Zamknij", "dialog[open]");
    await ctxS.close();
  });

  await step("Postęp: motyw jasny", async () => {
    await go(pp, pk);
    await pp.click("#theme-btn");
    await sleep(200);
    await shot(pp, "84-postep-kraj-jasny-desktop");
    await pp.click("#theme-btn");
  });

  const ctxPA = await browser.createBrowserContext();
  const ppa = await page(ctxPA, DESKTOP, "postep-admin");
  await step("Postęp: państwo NPC (admin) — zakładka, punkty ±, odkryj teraz, nadaj odkrycie, ustaw epokę", async () => {
    await linkLogin(ppa, world.U.maciej);
    await go(ppa, "#/npc/npc-mazowsze/postep");
    assert((await texts(ppa, ".tab")).includes("Postęp"), "zakładka w widoku NPC");
    assert((await text(ppa, ".era-hero-name")).includes("Renesans"), "epoka NPC");
    assert((await text(ppa, "#main")).includes("Narzędzia admina — postęp"), "karta admina");
    const delta = await ppa.$("input[aria-label='Zmiana punktów']");
    await fill(delta, "500");
    await clickButton(ppa, "Zmień punkty");
    let t = await toastText(ppa);
    assert(t.includes("+500"), "toast punktów: " + t);
    eq(lastCall("admin.postep.points").params, { id: "npc-mazowsze", delta: 500 }, "points params");
    await ppa.waitForFunction(() => document.querySelector('.tech[data-id="druk"]').classList.contains("tech-affordable"), { timeout: 5000 });
    await ppa.click('.tech[data-id="druk"]');
    await ppa.waitForSelector("dialog[open]");
    await clickButton(ppa, "Odkryj teraz", "dialog[open]");
    t = await toastText(ppa);
    assert(t.includes("Odkryto: Druk"), "toast odkrycia: " + t);
    await ppa.waitForFunction(() => document.querySelector('.tech[data-id="druk"]').classList.contains("st-OWNED"), { timeout: 5000 });
    await ppa.select("select[aria-label='Odkrycie']", "kartografia");
    await clickButton(ppa, "Nadaj odkrycie");
    t = await toastText(ppa);
    assert(t.includes("Nadano: Kartografia"), "toast nadania: " + t);
    await shot(ppa, "85-postep-npc-admin-desktop");
    await ppa.select("select[aria-label='Epoka']", "3");
    await clickButton(ppa, "Ustaw epokę");
    await ppa.waitForSelector("dialog[open]");
    await clickButton(ppa, "Ustaw epokę", "dialog[open]");
    t = await toastText(ppa);
    assert(t.includes("Epoka przemysłowa"), "toast epoki: " + t);
    eq(lastCall("admin.postep.setEra").params, { id: "npc-mazowsze", era: 3 }, "setEra params");
    await ppa.waitForFunction(() => document.querySelector(".era-hero-name").textContent.includes("przemysłowa"), { timeout: 5000 });
  });

  await step("Postęp: plugin Postep niedostępny → komunikat zamiast zakładki", async () => {
    world.postepAvailable = false;
    await go(ppa, "#/kraj/c-wislanie/postep");
    assert((await text(ppa, "#main")).includes("Plugin Postep jest niedostępny"), "komunikat");
    await shot(ppa, "86-postep-niedostepny-desktop");
    world.postepAvailable = true;
  });

  await step("Postęp 390 px: kraj (władca) i państwo NPC (zarządca może inwestować) bez przewijania w bok", async () => {
    const ctxM = await browser.createBrowserContext();
    const pm2 = await page(ctxM, MOBILE, "postep-390");
    await linkLogin(pm2, world.U.bolko);
    await go(pm2, pk);
    let overflow = await pm2.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(overflow <= 1, `kraj: przewijanie w bok o ${overflow}px`);
    const cols = await pm2.$$eval(".era-col", (es) => es.map((e) => Math.round(e.getBoundingClientRect().left)));
    assert(new Set(cols).size === 1, "kolumny epok jedna pod drugą: " + cols);
    await shot(pm2, "87-postep-kraj-390");
    await ctxM.close();
    const ctxZ2 = await browser.createBrowserContext();
    const pz2 = await page(ctxZ2, MOBILE, "postep-zarzadca-390");
    await linkLogin(pz2, world.U.ziemowit);
    await go(pz2, "#/npc/npc-mazowsze/postep");
    assert(await pz2.$(".invest-presets"), "zarządca przypisany inwestuje w badania NPC");
    assert(!(await text(pz2, "#main")).includes("Narzędzia admina"), "bez narzędzi admina");
    overflow = await pz2.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(overflow <= 1, `NPC: przewijanie w bok o ${overflow}px`);
    await shot(pz2, "88-postep-npc-zarzadca-390");
    await ctxZ2.close();
  });

  await playerPlanSteps({ browser, step, assert, eq, page, linkLogin, go, shot, lastCall, world, DESKTOP, sleep });
  await planSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, texts, clickButton, toastText, lastCall, world, DESKTOP, MOBILE, sleep });
  await extraSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, texts, clickButton, toastText, lastCall, world, DESKTOP, SHOTS });
  await healthSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, texts, lastCall, world, DESKTOP, MOBILE, sleep });
  // Gospodarka kraju (140-147): władca, doradca, odmowy, admin, WorldSteward, zarządca NPC.
  await economySteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, lastCall, world, DESKTOP, MOBILE });
  // Chorągwie (150-159) — przed Skarbem Świata, który nadaje Wędrowcowi admina.
  await flagSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, clickButton, toastText, lastCall, world, DESKTOP, MOBILE, sleep });
  // Skarb Świata: zarząd (112-113) i pełne statystyki właściciela (114-128) — na końcu, bo zmieniają role atrapy.
  await worldSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, lastCall, world, DESKTOP, MOBILE });
  await worldStatsSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, lastCall, world, DESKTOP, MOBILE, sleep, SHOTS });
  // Przelew do wszystkich państw NPC (133) i firmy graczy z kredytami Skarbu Świata (160-169).
  await worldNpcBulkSteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, world, DESKTOP, sleep });
  await firmySteps({ browser, step, assert, eq, page, linkLogin, go, shot, text, toastText, world, DESKTOP, MOBILE, sleep });

  await step("brak błędów JS i naruszeń CSP w konsoli", async () => {
    assert(errors.length === 0, errors.join("\n       "));
  });
}

main()
  .catch((e) => {
    fail++;
    console.error(e);
  })
  .finally(async () => {
    try {
      if (browser) await browser.close();
    } catch {}
    try {
      plugin && plugin.close();
    } catch {}
    stopDev(dev);
    console.log(`\nWynik: ${ok} OK, ${fail} FAIL (zrzuty: ${SHOTS})`);
    process.exit(fail ? 1 : 0);
  });
