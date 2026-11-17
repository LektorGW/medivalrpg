// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Próba wspólnego buildu Panelu na izolowanym Paperze (verify-panel-release-20261029/run.py): lokalny Worker z panel/web/dist
// (wrangler dev, 127.0.0.1) + prawdziwy plugin Panel na kopii testserver/ + headless Chrome.
//   Faza A (przygotowane jary): logowanie HASŁEM przez Strażnika, gospodarka kraju, chorągiew (wgranie → moderacja →
//     przebudowa paczki), firmy (lista, karta, zarząd, kredyt Skarbu Świata do akceptacji), przelew do wszystkich NPC,
//     statystyki właściciela, odmowy dostępu, obrazek poza audit.log.
//   Faza B (jary aktywne na produkcji): link z gry, te same strony — mosty bez nowych metod mają dawać czytelne komunikaty.
// Zmienne: RELEASE_RUNTIME, RELEASE_JAVA (JSON), RELEASE_SHOTS, RELEASE_PHASE (A|B), RELEASE_PASSWORDS (plik JSON), PORT.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, execSync } from "node:child_process";
import puppeteer from "puppeteer-core";
import { startDev, stopDev, sleep } from "./dev-server.mjs";
import { pngBuffer } from "./fake-flag.mjs";

const RT = process.env.RELEASE_RUNTIME;
const JAVA = JSON.parse(process.env.RELEASE_JAVA || "[]");
const SHOTS = process.env.RELEASE_SHOTS || path.join(RT, "screenshots");
const PHASE = process.env.RELEASE_PHASE || "A";
const PW = process.env.RELEASE_PASSWORDS ? JSON.parse(fs.readFileSync(process.env.RELEASE_PASSWORDS, "utf8")) : {};
const PORT = Number(process.env.PORT || 8813);
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].find((p) => p && fs.existsSync(p));
const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2 };
const checks = [];
const errors = [];
fs.mkdirSync(SHOTS, { recursive: true });

async function check(name, fn) {
  try {
    const detail = await fn();
    checks.push({ name, ok: true, detail: detail ?? null });
    console.log("  OK   " + name);
  } catch (e) {
    checks.push({ name, ok: false, detail: String(e && e.message ? e.message : e) });
    console.log("  FAIL " + name + " — " + (e && e.message));
  }
}
const assert = (c, m) => {
  if (!c) throw new Error(m || "asercja");
};

let dev;
let java;
let browser;
const result = { result: "FAIL", phase: PHASE, checks, startedAt: Date.now() };
try {
  dev = await startDev(PORT);
  console.log("Worker lokalnie (dist): " + dev.base);
  const log = fs.openSync(path.join(RT, "console.log"), "w");
  java = spawn(JAVA[0], JAVA.slice(1), { cwd: RT, stdio: ["ignore", log, log] });
  const tokensFile = path.join(RT, "probe-tokens.json");
  const t0 = Date.now();
  while (!fs.existsSync(tokensFile)) {
    if (java.exitCode !== null) throw new Error("Paper zakończył się przed gotowością sondy (kod " + java.exitCode + ")");
    if (Date.now() - t0 > 480000) throw new Error("brak gotowości sondy po 8 min");
    await sleep(1000);
  }
  await sleep(1500);
  const T = JSON.parse(fs.readFileSync(tokensFile, "utf8"));
  const F = T.facts;
  result.paperReadyMs = Date.now() - t0;
  const consoleLog = () => fs.readFileSync(path.join(RT, "console.log"), "utf8");

  browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ["--no-sandbox", "--force-color-profile=srgb"] });
  async function context(name, viewport = DESKTOP) {
    const ctx = await browser.createBrowserContext();
    const p = await ctx.newPage();
    await p.setViewport(viewport);
    p.on("pageerror", (e) => errors.push(`[${name}] ${e.message}`));
    p.on("console", (m) => {
      if (m.type() === "error" && !/status of (401|403|429|503|504)/.test(m.text())) errors.push(`[${name}] ${m.text()}`);
    });
    return { p, ctx };
  }
  const loggedIn = (p) => p.waitForFunction(() => !document.querySelector(".login-card") && document.querySelector("#main")?.children.length && !document.querySelector("#main .skel-wrap"), { timeout: 30000 });
  // Logowanie hasłem (formularz strony → Worker → plugin → Strażnik).
  async function passwordLogin(name, typed = name, viewport = DESKTOP) {
    const c = await context(name, viewport);
    await c.p.goto(dev.base + "/", { waitUntil: "domcontentloaded" });
    await c.p.waitForSelector(".login-card");
    await c.p.type("#login-name", typed);
    await c.p.type("#login-pass", PW[name]);
    await c.p.click(".login-form button[type=submit]");
    try {
      await loggedIn(c.p);
    } catch {
      await c.p.screenshot({ path: path.join(SHOTS, "FAIL-login-" + name + ".png") }).catch(() => {});
      throw new Error("logowanie hasłem " + name + " nieudane: " + (await c.p.evaluate(() => document.body.innerText.slice(0, 300)).catch(() => "?")));
    }
    return c;
  }
  // Link jednorazowy z gry (wydany przez sondę).
  const usedLinks = new Set();
  async function linkLogin(name, viewport = DESKTOP) {
    if (usedLinks.has(name)) throw new Error("drugi link " + name);
    usedLinks.add(name);
    const c = await context(name, viewport);
    await c.p.goto(dev.base + "/login?t=" + T[name].token, { waitUntil: "domcontentloaded" });
    await loggedIn(c.p);
    return c;
  }
  const login = PHASE === "A" ? passwordLogin : linkLogin;
  const goto = async (p, hash) => {
    if (await p.evaluate((h) => location.hash === h, hash)) await p.evaluate(() => (location.hash = "#/"));
    await sleep(200);
    await p.evaluate((h) => (location.hash = h), hash);
    await sleep(300);
    await p.waitForFunction(() => {
      const m = document.getElementById("main");
      return m && m.children.length && !m.querySelector(".skel-wrap");
    }, { timeout: 25000 });
    await sleep(500);
  };
  const rpc = (p, method, params = {}) =>
    p.evaluate(async (method, params) => {
      const r = await fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method, params }) });
      return { status: r.status, body: await r.json() };
    }, method, params);
  const body = (p) => p.$eval("#main", (m) => m.textContent);
  const shot = async (p, n) => {
    await p.evaluate(() => window.scrollTo(0, 0));
    await p.screenshot({ path: path.join(SHOTS, n + ".png"), fullPage: true });
  };
  const clickButton = async (p, label, scope = "#main", prefix = false) => {
    const ok = await p.evaluate((label, scope, prefix) => {
      const b = [...document.querySelectorAll(scope + " button, " + scope + " a.btn")].find((x) => (prefix ? x.textContent.trim().startsWith(label) : x.textContent.trim() === label) && !x.disabled);
      if (b) b.click();
      return !!b;
    }, label, scope, prefix);
    assert(ok, "brak przycisku: " + label);
  };
  const toastText = async (p, timeout = 20000) => {
    await p.waitForSelector(".toast", { timeout });
    const t = await p.$$eval(".toast", (es) => es.map((e) => e.textContent).join(" | "));
    await p.evaluate(() => document.querySelectorAll(".toast").forEach((t) => t.remove()));
    await p.waitForFunction(() => !document.querySelector("#main .busy"), { timeout: 15000 }).catch(() => {});
    return t;
  };
  const denied = async (p, hash) => {
    await goto(p, hash);
    const t = await body(p);
    assert(t.includes("Brak dostępu"), hash + ": brak karty odmowy — " + t.slice(0, 200));
  };
  const refused = (r, label) => assert(r.status === 200 && r.body.ok === false && r.body.data === undefined, label + ": " + JSON.stringify(r).slice(0, 300));
  const noOverflow = async (p, label) => {
    const o = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(o <= 1, `${label}: przewijanie w bok o ${o}px`);
  };

  await check("sonda: Testowia krajem gracza, Obcowia, 2 państwa NPC, skrzynie powiązane przez Ludnosc, firma założona", async () => {
    assert(F.testowia && F.testowiaNpc === false && F.rulerOfTestowia === "RULER" && F.advisorOfTestowia === "ADVISOR", JSON.stringify(F).slice(0, 400));
    assert(F.npcCountries && F.npcCountries.length === 2, "NPC: " + JSON.stringify(F.npcCountries));
    assert(F.chestsBound === true, "skrzynie: " + F.economyError);
    assert(F.firmId && !F.firmyError, "firma: " + (F.firmyError || F.firmFound));
    if (PHASE === "A") assert(F.worldLoanId, "wniosek o kredyt Skarbu Świata: " + F.worldLoan);
    return { firmyStages: F.firmyStages, firmFound: F.firmFound, income: F.firmIncome, loanLimit: F.loanLimit, worldLoan: F.worldLoan, poolStart: F.poolStart };
  });

  if (PHASE === "A") {
    // ------------------------------------------------------------------------------------------------ faza A
    await check("Panel podpiął logowanie hasłem pod Strażnika (log serwera)", async () => {
      const l = consoleLog();
      assert(l.includes("Logowanie hasłem w panelu: Straznik."), "brak wpisu Panelu o Strażniku");
      assert(!/Nie udało się podpiąć pod Straznika/.test(l), "błąd podpięcia");
    });
    await check("logowanie: złe hasło → błąd bez szczegółów; nieznany nick → ten sam błąd", async () => {
      const c = await context("zle-haslo");
      await c.p.goto(dev.base + "/", { waitUntil: "domcontentloaded" });
      await c.p.waitForSelector(".login-card");
      const msgs = [];
      for (const [n, pw] of [["Wladca", "zle-haslo-123"], ["NieMaTakiego", "cokolwiek-123"]]) {
        await c.p.evaluate(() => {
          document.querySelector("#login-name").value = "";
          document.querySelector("#login-pass").value = "";
        });
        await c.p.type("#login-name", n);
        await c.p.type("#login-pass", pw);
        await c.p.click(".login-form button[type=submit]");
        await c.p.waitForSelector(".alert-err:not([hidden])", { timeout: 20000 });
        msgs.push(await c.p.$eval(".alert-err", (e) => e.textContent));
        await c.p.evaluate(() => (document.querySelector(".alert-err").hidden = true));
      }
      assert(msgs[0].includes("Nieprawidłowy") && msgs[0] === msgs[1], JSON.stringify(msgs));
      await shot(c.p, "A00-zle-haslo");
      await c.ctx.close();
      return msgs[0];
    });

    const W = await passwordLogin("Wladca", "wladca"); // nick małymi literami → nazwa kanoniczna ze Strażnika
    await check("Władca: logowanie hasłem (nick wpisany małymi literami) → UUID i nick kanoniczny, kraj Testowia", async () => {
      const r = await rpc(W.p, "me");
      assert(r.body.ok && r.body.data.uuid === T.Wladca.uuid && r.body.data.name === "Wladca", JSON.stringify(r).slice(0, 300));
      assert(r.body.data.country && r.body.data.country.id === F.testowia && r.body.data.country.rank === "RULER", "kraj");
      assert(r.body.data.roles.worldManager === false && r.body.data.roles.flagModerator === false, "role");
      await shot(W.p, "A01-wladca-pulpit");
      return r.body.data.name;
    });
    await check("Władca: gospodarka kraju z prawdziwymi danymi Ludnosci", async () => {
      await goto(W.p, "#/gospodarka/" + F.testowia);
      await W.p.waitForSelector(".eco-kpis", { timeout: 20000 });
      const t = await body(W.p);
      for (const s of ["Gospodarka — Testowia", "Braki i ostrzeżenia", "pełna", "Pszenica", "Spichlerz i ludność", "Obywatele"]) assert(t.includes(s), "brak: " + s);
      const r = await rpc(W.p, "country.economy", { id: F.testowia });
      assert(r.body.ok && r.body.data.access === "RULER", JSON.stringify(r).slice(0, 200));
      assert(!(await W.p.evaluate(() => Object.keys(sessionStorage).some((k) => k.includes("country.economy")))), "sessionStorage");
      await shot(W.p, "A02-gospodarka-wladca");
      return { warnings: r.body.data.warnings.map((w) => w.code), workplaces: r.body.data.summary.workplaces };
    });
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "panel-release-flag-"));
    const herb = path.join(dir, "herb.png");
    fs.writeFileSync(herb, pngBuffer(128, 80, (x, y) => (Math.hypot(x - 64, y - 40) < 22 ? [226, 191, 116] : y < 40 ? [40, 90, 176] : [245, 240, 230])));
    let flagImage = null;
    await check("Władca: chorągiew — wgranie PNG → „Oczekuje na zatwierdzenie” (prawdziwe Kraje)", async () => {
      await goto(W.p, "#/kraj/" + F.testowia + "/flaga");
      const t = await body(W.p);
      assert(t.includes("Chorągiew kraju") && t.includes("Wgraj nową chorągiew"), t.slice(0, 300));
      const input = await W.p.$("#flag-file");
      await input.uploadFile(herb);
      await W.p.waitForSelector(".flag-upload-out .flag-compare, .flag-upload-out .alert-err", { timeout: 15000 });
      assert(!(await W.p.$(".flag-upload-out .alert-err")), "błąd przygotowania obrazka");
      await clickButton(W.p, "Wyślij do zatwierdzenia");
      const toast = await toastText(W.p);
      assert(/zatwierdzenie/i.test(toast), "toast: " + toast);
      await W.p.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Oczekuje na zatwierdzenie"), { timeout: 15000 });
      const r = await rpc(W.p, "country.flag.get", { id: F.testowia });
      assert(r.body.ok && r.body.data.pending, JSON.stringify(r).slice(0, 300));
      flagImage = r.body.data.pending.imageId;
      await shot(W.p, "A03-flaga-oczekuje");
      return { toast, imageId: flagImage };
    });
    await check("audit.log: wgranie chorągwi zapisane bez danych obrazka", async () => {
      const a = fs.readFileSync(path.join(RT, "plugins/Panel/audit.log"), "utf8");
      const line = a.split("\n").find((l) => l.includes("country.flag.upload"));
      assert(line, "brak wpisu country.flag.upload");
      assert(!/base64|iVBORw0KGgo/.test(a), "dane obrazka w audit.log");
      return line.slice(0, 240);
    });
    await check("Władca: nie jest moderatorem — #/flagi „Brak dostępu”, admin.flag.approve odrzucone; kredytów Skarbu Świata nie widzi", async () => {
      assert(!(await W.p.$('a.nav-item[href="#/flagi"]')), "pozycja menu moderacji");
      await denied(W.p, "#/flagi");
      refused(await rpc(W.p, "admin.flag.approve", { id: F.testowia, imageId: flagImage }), "approve");
      refused(await rpc(W.p, "firmy.world.loans", {}), "firmy.world.loans");
      refused(await rpc(W.p, "firmy.world.loanDecide", { id: F.worldLoanId, approve: "true" }), "loanDecide");
      await denied(W.p, "#/skarbswiata");
    });
    await check("Władca: firmy — ranking z „Probna”, karta firmy (mistrz), zarząd firm „Brak dostępu”", async () => {
      await goto(W.p, "#/firmy");
      assert((await body(W.p)).includes("Probna"), "ranking");
      await shot(W.p, "A04-firmy-ranking");
      await goto(W.p, "#/firmy/" + F.firmId);
      const t = await body(W.p);
      assert(t.includes("Probna") && t.includes("Testowia"), t.slice(0, 300));
      assert(t.includes("czeka na zgodę właściciela") && !t.includes("1970"), "wniosek o kredyt bez terminu z 1970");
      const r = await rpc(W.p, "firmy.get", { id: F.firmId });
      assert(r.body.ok, JSON.stringify(r).slice(0, 300));
      await shot(W.p, "A05-firma-karta-mistrz");
      await denied(W.p, "#/firmy/zarzad");
      assert(!(await W.p.evaluate(() => Object.keys(sessionStorage).some((k) => /firmy\.|world\./.test(k)))), "sessionStorage firmy/world");
      return Object.keys(r.body.data || {}).slice(0, 20);
    });
    await W.ctx.close();

    await check("Doradca: gospodarka na telefonie 390 px (podgląd doradcy), bez przewijania w bok", async () => {
      const D = await passwordLogin("Doradca", "Doradca", MOBILE);
      await goto(D.p, "#/gospodarka/" + F.testowia);
      await D.p.waitForSelector(".eco-kpis", { timeout: 20000 });
      assert((await body(D.p)).includes("Doradca"), "rola");
      await noOverflow(D.p, "gospodarka 390");
      await shot(D.p, "A06-doradca-390");
      await D.ctx.close();
    });
    await check("Obywatel: odmowa gospodarki (strona i RPC) i wgrania chorągwi", async () => {
      const U = await passwordLogin("Obywatel");
      await denied(U.p, "#/gospodarka/" + F.testowia);
      refused(await rpc(U.p, "country.economy", { id: F.testowia }), "economy");
      const up = await rpc(U.p, "country.flag.upload", { id: F.testowia, data: "data:image/png;base64," + fs.readFileSync(herb).toString("base64"), filename: "x.png" });
      refused(up, "upload");
      await shot(U.p, "A07-obywatel-odmowa");
      await U.ctx.close();
      return up.body.error;
    });
    await check("ObcyKrol: odmowa gospodarki i chorągwi Testowii; własny kraj dostępny", async () => {
      const U = await passwordLogin("ObcyKrol");
      await denied(U.p, "#/gospodarka/" + F.testowia);
      refused(await rpc(U.p, "country.economy", { id: F.testowia }), "economy");
      refused(await rpc(U.p, "country.flag.withdraw", { id: F.testowia }), "withdraw");
      const own = await rpc(U.p, "country.economy", { id: F.obcowia });
      assert(own.body.ok, "własny kraj: " + JSON.stringify(own).slice(0, 200));
      const g = await rpc(U.p, "country.flag.get", { id: F.testowia });
      assert(g.body.ok && !g.body.data.pending && !g.body.data.history, "obcy widzi tylko obecną flagę: " + JSON.stringify(g).slice(0, 300));
      await U.ctx.close();
    });
    await check("Wedrowiec (bez kraju): Skarb Świata, moderacja, zarząd firm — „Brak dostępu”; RPC właściciela odrzucone", async () => {
      const U = await passwordLogin("Wedrowiec");
      await denied(U.p, "#/skarbswiata");
      await denied(U.p, "#/skarbswiata/bilans");
      await denied(U.p, "#/flagi");
      await denied(U.p, "#/firmy/zarzad");
      for (const [m, p] of [["world.stats.summary", {}], ["world.npcTreasuries", {}], ["world.npcTransfer", { direction: "toAllNpc", amount: "1", confirm: "2", count: "2", reason: "proba", key: "npcbulk-0000000000000000" }],
        ["firmy.world.loans", {}], ["firmy.world.loanDecide", { id: F.worldLoanId, approve: "true" }], ["admin.flag.queue", {}]]) refused(await rpc(U.p, m, p), m);
      await goto(U.p, "#/firmy");
      assert((await body(U.p)).includes("Probna"), "ranking firm jest publiczny");
      await shot(U.p, "A08-wedrowiec-odmowa");
      await U.ctx.close();
    });

    const M = await passwordLogin("WorldSteward");
    await check("WorldSteward: rola właściciela i moderatora (bez admina panelu)", async () => {
      const r = await rpc(M.p, "me");
      assert(r.body.ok && r.body.data.roles.worldManager === true && r.body.data.roles.flagModerator === true && r.body.data.admin === false, JSON.stringify(r.body.data.roles));
      assert(await M.p.$('a.nav-item[href="#/flagi"]'), "menu moderacji");
    });
    await check("WorldSteward: moderacja — zatwierdzenie chorągwi Testowii, przebudowa paczki", async () => {
      await goto(M.p, "#/flagi");
      const t = await body(M.p);
      assert(t.includes("Moderacja chorągwi") && t.includes("Testowia") && t.includes("Wladca"), t.slice(0, 400));
      await shot(M.p, "A09-moderacja-kolejka");
      await clickButton(M.p, "Zatwierdź");
      await M.p.waitForSelector(".modal");
      await clickButton(M.p, "Zatwierdź", ".modal");
      const toast = await toastText(M.p);
      assert(/Zatwierdzono/i.test(toast), "toast: " + toast);
      const g = await rpc(M.p, "country.flag.get", { id: F.testowia });
      assert(g.body.ok && !g.body.data.pending, JSON.stringify(g).slice(0, 300));
      await goto(M.p, "#/flagi");
      let rebuild = null;
      if ((await body(M.p)).includes("Przebuduj paczkę teraz")) {
        await clickButton(M.p, "Przebuduj paczkę teraz");
        await M.p.waitForSelector(".modal");
        await clickButton(M.p, "Przebuduj teraz", ".modal");
        rebuild = await toastText(M.p, 60000);
      }
      await shot(M.p, "A10-moderacja-po");
      return { toast, rebuild, current: JSON.stringify(g.body.data.current || g.body.data.state || {}).slice(0, 200) };
    });
    let reserveBefore = null;
    await check("WorldSteward: Skarb Świata — karta kredytów firm z wnioskiem „Probna”; zatwierdzenie przenosi 20 zł z rezerwy", async () => {
      const before = await rpc(M.p, "world.npcTreasuries", {});
      reserveBefore = before.body.data.reserve;
      await goto(M.p, "#/skarbswiata");
      await M.p.waitForFunction(() => document.querySelector("#main")?.textContent.includes("Probna"), { timeout: 20000 });
      const t = await body(M.p);
      assert(t.includes("Kredyty firm ze Skarbu Świata") && t.includes("Test panelu"), "karta");
      await shot(M.p, "A11-skarb-kredyt-wniosek");
      const clicked = await M.p.evaluate(() => {
        const card = [...document.querySelectorAll("#main .card")].find((c) => c.textContent.includes("Kredyty firm ze Skarbu Świata"));
        const b = card && [...card.querySelectorAll("button")].find((x) => x.textContent.trim() === "Zatwierdź");
        if (b) b.click();
        return !!b;
      });
      assert(clicked, "przycisk Zatwierdź w karcie");
      await M.p.waitForSelector(".modal");
      await M.p.type(".modal input", "test panelu — zgoda");
      await clickButton(M.p, "Zatwierdź", ".modal", true);
      const toast = await toastText(M.p, 30000);
      const loans = await rpc(M.p, "firmy.world.loans", {});
      assert(loans.body.ok && loans.body.data.applications.length === 0 && loans.body.data.active.some((l) => l.firmName === "Probna"), JSON.stringify(loans).slice(0, 400));
      const after = await rpc(M.p, "world.npcTreasuries", {});
      assert(reserveBefore - after.body.data.reserve === 2000, `rezerwa ${reserveBefore} → ${after.body.data.reserve}`);
      await shot(M.p, "A12-skarb-kredyt-zatwierdzony");
      return { toast, reserveBefore, reserveAfter: after.body.data.reserve };
    });
    await check("WorldSteward: przelew do wszystkich państw NPC (UI) → 2 × 2 sr 50 md, historia; ten sam klucz drugi raz nic nie przelewa", async () => {
      const before = (await rpc(M.p, "world.npcTreasuries", {})).body.data;
      assert(before.bulkAvailable !== false && before.countries.length === 2, JSON.stringify(before).slice(0, 300));
      await goto(M.p, "#/skarbswiata");
      await M.p.type('input[name="npc-bulk-amount"]', "250");
      await M.p.type('input[aria-label="Powód przelewu zbiorczego"]', "Test zbiorczy panelu");
      await clickButton(M.p, "Przelej wszystkim…");
      await M.p.waitForSelector(".modal");
      const m = await M.p.$eval(".modal", (e) => e.textContent);
      assert(m.includes("każdego z 2 państw NPC") && m.includes("Polnocja") && m.includes("Poludnica"), m.slice(0, 300));
      await shot(M.p, "A13-przelew-wszystkim-podglad");
      await clickButton(M.p, "Przelej razem", ".modal", true);
      const toast = await toastText(M.p, 30000);
      const after = (await rpc(M.p, "world.npcTreasuries", {})).body.data;
      assert(before.reserve - after.reserve === 500, `rezerwa ${before.reserve} → ${after.reserve}`);
      for (const c of after.countries) assert(c.balance - before.countries.find((x) => x.id === c.id).balance === 250, c.name);
      const op = after.bulk[0];
      assert(op && op.state === "DONE" && op.count === 2 && op.total === 500 && /^npcbulk-[0-9a-f]{16}$/.test(op.key), JSON.stringify(op));
      const replay = await rpc(M.p, "world.npcTransfer", { direction: "toAllNpc", amount: "250", confirm: 500, count: 2, reason: "Test zbiorczy panelu", key: op.key });
      assert(replay.body.ok && replay.body.data.replayed === true, JSON.stringify(replay).slice(0, 300));
      assert((await rpc(M.p, "world.npcTreasuries", {})).body.data.reserve === after.reserve, "powtórka nie zmienia rezerwy");
      await goto(M.p, "#/skarbswiata");
      assert((await body(M.p)).includes("Ostatnie przelewy zbiorcze"), "historia");
      await shot(M.p, "A14-przelew-wszystkim-po");
      return { toast, reserve: [before.reserve, after.reserve] };
    });
    await check("WorldSteward: statystyki właściciela — Bilans, Konta, Przepływy (pula 100 000 000 md)", async () => {
      const s = await rpc(M.p, "world.stats.summary", {});
      assert(s.body.ok, JSON.stringify(s).slice(0, 300));
      assert(JSON.stringify(s.body.data).includes("100000000"), "pula w bilansie");
      for (const [h, label, n] of [["#/skarbswiata/bilans", "Bilans", "A15-bilans"], ["#/skarbswiata/konta", "Konta", "A16-konta"], ["#/skarbswiata/przeplywy", "Przepływy", "A17-przeplywy"]]) {
        await goto(M.p, h);
        const t = await body(M.p);
        assert(t.includes(label) && !t.includes("Brak dostępu"), h + ": " + t.slice(0, 200));
        await shot(M.p, n);
      }
      const acc = await rpc(M.p, "world.stats.accounts", {});
      assert(acc.body.ok && JSON.stringify(acc.body.data).includes("firma:" + F.firmId), "konto firmy w spisie kont");
    });
    await check("WorldSteward: zarząd firm i ranking", async () => {
      await goto(M.p, "#/firmy/zarzad");
      const t = await body(M.p);
      assert(t.includes("Firmy — zarząd") && t.includes("Probna") && !t.includes("Brak dostępu"), t.slice(0, 300));
      await shot(M.p, "A18-firmy-zarzad");
    });
    await check("przebudowa paczki po „Przebuduj teraz”: zatwierdzona flaga Testowii w grze, Interakcje z nowym SHA-1", async () => {
      const until = Date.now() + 180000;
      let line = null;
      while (Date.now() < until && !(line = consoleLog().split("\n").find((l) => /Chorągwie: paczka przebudowana, własnych flag w grze: [1-9]/.test(l)))) await sleep(2000);
      assert(line, "brak przebudowy paczki w 3 min");
      await sleep(5000);
      const packs = consoleLog().split("\n").filter((l) => l.includes("[Interakcje] Paczka zasobów gotowa"));
      const g = await rpc(M.p, "country.flag.get", { id: F.testowia });
      assert(g.body.ok, "flag.get");
      return { line: line.trim(), packs: packs.map((l) => (l.match(/SHA-1 ([0-9a-f]{12})/) || [])[1]), state: JSON.stringify(g.body.data.current ? { ...g.body.data.current, png: undefined } : g.body.data).slice(0, 200) };
    });
    await M.ctx.close();
  } else {
    // ------------------------------------------------------------------------------------------------ faza B
    await check("bez Strażnika i nLogin: logowanie hasłem zwraca czytelny błąd (bez wyjątku)", async () => {
      const c = await context("haslo-bez-weryfikatora");
      await c.p.goto(dev.base + "/", { waitUntil: "domcontentloaded" });
      await c.p.waitForSelector(".login-card");
      await c.p.type("#login-name", "Wladca");
      await c.p.type("#login-pass", "jakies-haslo-1");
      await c.p.click(".login-form button[type=submit]");
      await c.p.waitForSelector(".alert-err:not([hidden])", { timeout: 20000 });
      const msg = await c.p.$eval(".alert-err", (e) => e.textContent);
      await c.ctx.close();
      assert(!consoleLog().includes("Logowanie hasłem w panelu: Straznik."), "Strażnik nie powinien być podpięty");
      return msg;
    });
    const W = await linkLogin("Wladca");
    await check("Władca (link): gospodarka kraju na Ludnosc b58d6f61", async () => {
      await goto(W.p, "#/gospodarka/" + F.testowia);
      await W.p.waitForSelector(".eco-kpis", { timeout: 20000 });
      assert((await body(W.p)).includes("Gospodarka — Testowia"), "nagłówek");
      await shot(W.p, "B01-gospodarka");
    });
    await check("Władca: chorągiew na starym Kraje 8f0224fe → komunikat „nie obsługuje”, bez błędów JS", async () => {
      const r = await rpc(W.p, "country.flag.get", { id: F.testowia });
      assert(r.body.ok === false && /nie obsługuje jeszcze chorągwi/.test(r.body.error), JSON.stringify(r).slice(0, 300));
      await goto(W.p, "#/kraj/" + F.testowia + "/flaga");
      const t = await body(W.p);
      assert(/nie obsługuje jeszcze chorągwi|niedostępn/i.test(t), t.slice(0, 300));
      await shot(W.p, "B02-flaga-stary-kraje");
      return r.body.error;
    });
    await check("Władca: firmy etapu 1 — ranking i karta firmy działają", async () => {
      await goto(W.p, "#/firmy");
      const t = await body(W.p);
      assert(t.includes("Probna"), t.slice(0, 300));
      await goto(W.p, "#/firmy/" + F.firmId);
      assert((await body(W.p)).includes("Probna"), "karta");
      await shot(W.p, "B03-firmy-etap1");
    });
    await W.ctx.close();
    const M = await linkLogin("WorldSteward");
    await check("WorldSteward: role — właściciel tak, moderator chorągwi nie (stary Kraje); #/flagi bez danych", async () => {
      const r = await rpc(M.p, "me");
      assert(r.body.data.roles.worldManager === true && r.body.data.roles.flagModerator === false, JSON.stringify(r.body.data.roles));
      await denied(M.p, "#/flagi");
    });
    await check("WorldSteward: Skarb Świata — karta kredytów firm z dopiskiem o etapie 2, przelew zbiorczy dostępny (Skarbiec c5251b6f)", async () => {
      await goto(M.p, "#/skarbswiata");
      await M.p.waitForFunction(() => /Kredyty firm ze Skarbu Świata/.test(document.querySelector("#main")?.textContent || ""), { timeout: 20000 });
      await sleep(1500);
      const t = await body(M.p);
      assert(t.includes("Przelej wszystkim państwom NPC"), "sekcja przelewu zbiorczego");
      const loans = await rpc(M.p, "firmy.world.loans", {});
      assert(loans.body.ok === false, "etap 1 nie ma kredytów: " + JSON.stringify(loans).slice(0, 200));
      const card = await M.p.evaluate(() => [...document.querySelectorAll("#main .card")].find((c) => c.textContent.includes("Kredyty firm ze Skarbu Świata"))?.textContent || "");
      await shot(M.p, "B04-skarb-swiata");
      return { loansError: loans.body.error, card: card.slice(0, 200) };
    });
    await check("WorldSteward: przelew do wszystkich NPC przez RPC i statystyki właściciela", async () => {
      const before = (await rpc(M.p, "world.npcTreasuries", {})).body.data;
      const r = await rpc(M.p, "world.npcTransfer", { direction: "toAllNpc", amount: "100", confirm: 200, count: 2, reason: "Test B", key: "npcbulk-b000000000000001" });
      assert(r.body.ok && r.body.data.total === 200, JSON.stringify(r).slice(0, 300));
      const after = (await rpc(M.p, "world.npcTreasuries", {})).body.data;
      assert(before.reserve - after.reserve === 200, "rezerwa");
      const s = await rpc(M.p, "world.stats.summary", {});
      assert(s.body.ok, "summary");
    });
    await M.ctx.close();
    await check("Wedrowiec (link): odmowy Skarbu Świata", async () => {
      const U = await linkLogin("Wedrowiec");
      await denied(U.p, "#/skarbswiata");
      refused(await rpc(U.p, "world.stats.summary", {}), "summary");
      refused(await rpc(U.p, "world.npcTransfer", { direction: "toAllNpc", amount: "1", confirm: "2", count: "2", reason: "proba", key: "npcbulk-0000000000000000" }), "transfer");
      await U.ctx.close();
    });
  }
  await check("brak błędów JS i CSP", async () => assert(errors.length === 0, errors.join(" | ")));
  result.result = checks.every((c) => c.ok) ? "PASS" : "FAIL";
} catch (e) {
  checks.push({ name: "harness", ok: false, detail: String(e && e.stack ? e.stack : e) });
  console.error(e);
} finally {
  try {
    if (browser) await browser.close();
  } catch {}
  if (RT) fs.writeFileSync(path.join(RT, "probe-stop"), "stop");
  if (java) {
    const until = Date.now() + 120000;
    while (java.exitCode === null && Date.now() < until) await sleep(500);
    if (java.exitCode === null) {
      try {
        execSync(`taskkill /pid ${java.pid} /T /F`, { stdio: "ignore" });
      } catch {}
      result.killed = true;
    }
    result.javaExit = java.exitCode;
  }
  stopDev(dev);
  result.finishedAt = Date.now();
  if (RT) fs.writeFileSync(path.join(RT, "paper-release-result.json"), JSON.stringify(result, null, 2));
  console.log("Wynik fazy " + PHASE + ": " + result.result + ` (${checks.filter((c) => c.ok).length}/${checks.length})`);
  process.exit(result.result === "PASS" ? 0 : 1);
}
