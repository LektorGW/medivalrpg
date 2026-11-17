// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Próba na izolowanym Paperze (verify-panel-economy-20261029/run.py): lokalny Worker (wrangler dev, 127.0.0.1) + prawdziwy
// plugin Panel na kopii testserver/ z Ludnoscią flow-release5 + headless Chrome. Logowanie jednorazowymi linkami, które
// wydaje EconomyProbe przez połączenie pluginu. Sprawdza stronę gospodarki z PRAWDZIWYMI danymi Ludnosci (pełna skrzynia
// kopalni, towar pola) oraz odmowy: obywatel, władca obcego kraju (strona i bezpośrednie RPC), gracz bez kraju.
// Zmienne: ECONOMY_RUNTIME (katalog serwera), ECONOMY_JAVA (JSON z poleceniem), ECONOMY_SHOTS, PORT.
import fs from "node:fs";
import path from "node:path";
import { spawn, execSync } from "node:child_process";
import puppeteer from "puppeteer-core";
import { startDev, stopDev, sleep } from "./dev-server.mjs";

const RT = process.env.ECONOMY_RUNTIME;
const JAVA = JSON.parse(process.env.ECONOMY_JAVA || "[]");
const SHOTS = process.env.ECONOMY_SHOTS || path.join(RT, "screenshots");
const PORT = Number(process.env.PORT || 8798);
const CHROME = [process.env.CHROME_PATH, "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"].find((p) => p && fs.existsSync(p));
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
const result = { result: "FAIL", checks, startedAt: Date.now() };
try {
  dev = await startDev(PORT);
  console.log("Worker lokalnie: " + dev.base);
  const log = fs.openSync(path.join(RT, "console.log"), "w");
  java = spawn(JAVA[0], JAVA.slice(1), { cwd: RT, stdio: ["ignore", log, log] });
  const tokensFile = path.join(RT, "probe-tokens.json");
  const t0 = Date.now();
  while (!fs.existsSync(tokensFile)) {
    if (java.exitCode !== null) throw new Error("Paper zakończył się przed wydaniem linków (kod " + java.exitCode + ")");
    if (Date.now() - t0 > 360000) throw new Error("brak linków logowania po 6 min");
    await sleep(1000);
  }
  await sleep(1500);
  const T = JSON.parse(fs.readFileSync(tokensFile, "utf8"));
  const F = T.facts;
  result.paperReadyMs = Date.now() - t0;
  // The Worker keeps only the newest link of a player (a new link revokes the older one): use the last, once per player.
  const used = new Set();
  const token = (name) => {
    if (used.has(name)) throw new Error("drugie logowanie " + name + " — link jest jednorazowy");
    used.add(name);
    return T[name].tokens.at(-1);
  };

  browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ["--no-sandbox", "--force-color-profile=srgb"] });
  async function as(name, viewport = { width: 1440, height: 900 }) {
    const ctx = await browser.createBrowserContext();
    const p = await ctx.newPage();
    await p.setViewport(viewport);
    p.on("pageerror", (e) => errors.push(`[${name}] ${e.message}`));
    p.on("console", (m) => {
      if (m.type() === "error" && !/status of (401|429|503|504)/.test(m.text())) errors.push(`[${name}] ${m.text()}`);
    });
    await p.goto(dev.base + "/login?t=" + token(name), { waitUntil: "domcontentloaded" });
    try {
      await p.waitForSelector("#main .page-head, #main .country-head", { timeout: 30000 });
    } catch (e) {
      await p.screenshot({ path: path.join(SHOTS, "FAIL-login-" + name + ".png") }).catch(() => {});
      const where = await p.evaluate(() => location.href + " | " + document.body.innerText.slice(0, 300)).catch(() => "?");
      throw new Error(`logowanie ${name} nieudane: ${where}`);
    }
    return { p, ctx };
  }
  const goto = async (p, hash) => {
    await p.evaluate((h) => (location.hash = h), hash);
    await sleep(300);
    await p.waitForFunction(() => {
      const m = document.getElementById("main");
      return m && m.children.length && !m.querySelector(".skel-wrap");
    }, { timeout: 20000 });
    await sleep(300);
  };
  const rpc = (p, method, params) =>
    p.evaluate(async (method, params) => {
      const r = await fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method, params }) });
      return { status: r.status, body: await r.json() };
    }, method, params);
  const body = (p) => p.$eval("#main", (m) => m.textContent);
  const shot = async (p, n) => {
    await p.evaluate(() => window.scrollTo(0, 0));
    await p.screenshot({ path: path.join(SHOTS, n + ".png"), fullPage: true });
  };

  await check("Paper: Testowia jest krajem gracza, Wladca władcą, sonda powiązała skrzynie przez Ludnosc", async () => {
    assert(F.testowia && F.testowiaNpc === false, JSON.stringify(F));
    assert(F.rulerOfTestowia === "RULER", "ranga: " + F.rulerOfTestowia);
    assert(F.bound === true && !F.setupError, "powiązanie: " + (F.setupError || ""));
    const flow = JSON.parse(fs.readFileSync(path.join(RT, "probe-flow.json"), "utf8"));
    assert(Array.isArray(flow.workplaces) && flow.workplaces.length >= 2, "materialFlow.workplaces");
    return { materialFlowMsAvg: F.materialFlowMsAvg, workplaces: flow.workplaces.map((w) => w.function + ": " + w.status), stores: flow.stores.map((s) => s.label + " " + s.used + "/" + s.slots) };
  });

  const W = await as("Wladca");
  let raw = null;
  await check("Władca: strona z prawdziwymi danymi Ludnosci — pełna skrzynia kopalni blokuje pracę, towar pola", async () => {
    await goto(W.p, "#/gospodarka/" + F.testowia);
    await W.p.waitForSelector(".eco-kpis", { timeout: 20000 });
    const t = await body(W.p);
    for (const s of ["Gospodarka — Testowia", "Władca", "Braki i ostrzeżenia", "Kopalnia stoi — pełna skrzynia", "Podpowiedź:", "pełna — blokuje pracę", "1728", "Pszenica", "Kłoda dębowa", "Spichlerz i ludność", "Place budowy", "Obywatele"])
      assert(t.includes(s), "brak: " + s);
    const r = await rpc(W.p, "country.economy", { id: F.testowia });
    assert(r.status === 200 && r.body.ok === true && r.body.data.access === "RULER", JSON.stringify(r).slice(0, 300));
    raw = r.body.data;
    fs.writeFileSync(path.join(SHOTS, "country-economy-testowia.json"), JSON.stringify(raw, null, 1));
    await shot(W.p, "150-paper-gospodarka-wladca");
    return { buildMs: raw.buildMs, cached: raw.cached, workplaces: raw.summary.workplaces, fullStores: raw.summary.fullStores, warnings: raw.warnings.map((w) => w.code) };
  });
  await check("Władca: drugi odczyt w ciągu 15 s z pamięci serwera, „fresh” buduje od nowa", async () => {
    const a = await rpc(W.p, "country.economy", { id: F.testowia });
    const b = await rpc(W.p, "country.economy", { id: F.testowia, fresh: true });
    assert(a.body.data.cached === true, "drugi odczyt z pamięci");
    assert(b.body.ok === true, "fresh ok");
    return { secondCached: a.body.data.cached, freshCached: b.body.data.cached, freshAgeMs: b.body.data.ageMs };
  });
  await check("Władca: koszt budowy odpowiedzi na głównym wątku po rozgrzaniu (3 × fresh co 5,5 s)", async () => {
    const ms = [];
    for (let i = 0; i < 3; i++) {
      await sleep(5500);
      const r = await rpc(W.p, "country.economy", { id: F.testowia, fresh: true });
      assert(r.body.ok && r.body.data.cached === false, "świeża odpowiedź " + i);
      ms.push(r.body.data.buildMs);
    }
    return { buildMs: ms };
  });
  await check("Władca: obcy kraj — karta „Brak dostępu” i odmowa bezpośredniego RPC, bez danych", async () => {
    await goto(W.p, "#/gospodarka/" + F.obcowia);
    const t = await body(W.p);
    assert(t.includes("Brak dostępu") && !(await W.p.$(".eco-kpis")), "karta odmowy");
    const r = await rpc(W.p, "country.economy", { id: F.obcowia });
    assert(r.status === 200 && r.body.ok === false && /^Brak dostępu do gospodarki/.test(r.body.error) && r.body.data === undefined, JSON.stringify(r));
    const keys = await W.p.evaluate(() => Object.keys(sessionStorage).filter((k) => k.includes("country.economy")));
    assert(keys.length === 0, "sessionStorage: " + keys);
    await shot(W.p, "151-paper-odmowa-obcy-kraj");
    return r.body.error;
  });
  await check("Władca: link z menu i z widoku kraju", async () => {
    await goto(W.p, "#/kraj/" + F.testowia);
    assert(await W.p.$(`.country-head a.eco-link[href="#/gospodarka/${F.testowia}"]`), "przycisk w widoku kraju");
    assert(await W.p.$(`.nav-item[href="#/gospodarka/${F.testowia}"]`), "pozycja menu");
  });
  await W.ctx.close();

  await check("Doradca: podgląd na telefonie 390 px", async () => {
    const D = await as("Doradca", { width: 390, height: 844, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
    await goto(D.p, "#/gospodarka/" + F.testowia);
    await D.p.waitForSelector(".eco-kpis", { timeout: 20000 });
    assert((await body(D.p)).includes("Doradca — podgląd"), "rola");
    const o = await D.p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    assert(o <= 1, "przewijanie w bok " + o);
    await shot(D.p, "152-paper-doradca-390");
    await D.ctx.close();
  });
  for (const name of ["Obywatel", "ObcyKrol", "Wedrowiec"]) {
    await check(`${name}: odmowa dla Testowii (strona i RPC)`, async () => {
      const U = await as(name);
      try {
        await goto(U.p, "#/gospodarka/" + F.testowia);
        assert((await body(U.p)).includes("Brak dostępu") && !(await U.p.$(".eco-kpis")), "karta");
        const r = await rpc(U.p, "country.economy", { id: F.testowia });
        assert(r.body.ok === false && r.body.data === undefined, JSON.stringify(r));
        if (name === "Obywatel") await shot(U.p, "153-paper-obywatel-odmowa");
        if (name === "ObcyKrol") {
          // the same player sees his own country
          await goto(U.p, "#/gospodarka/" + F.obcowia);
          await U.p.waitForSelector(".eco-kpis", { timeout: 20000 });
          assert((await body(U.p)).includes("Kraj nic nie wytwarza"), "własny pusty kraj");
        }
        return r.body.error;
      } finally {
        await U.ctx.close();
      }
    });
  }
  await check("WorldSteward (zarządca osobisty, bez roli admina): każdy kraj", async () => {
    const U = await as("WorldSteward");
    const r = await rpc(U.p, "country.economy", { id: F.testowia });
    assert(r.body.ok === true && r.body.data.access === "OWNER", JSON.stringify(r).slice(0, 300));
    await U.ctx.close();
    return r.body.data.access;
  });
  await check("brak błędów JS i CSP", async () => assert(errors.length === 0, errors.join(" | ")));
  result.result = checks.every((c) => c.ok) ? "PASS" : "FAIL";
} catch (e) {
  checks.push({ name: "harness", ok: false, detail: String(e && e.stack ? e.stack : e) });
  console.error(e);
} finally {
  try {
    if (browser) await browser.close();
  } catch {}
  // wait (max 90 s) for the probe's second, settled materialFlow measurement before stopping the server
  for (let i = 0; RT && java && java.exitCode === null && i < 180; i++) {
    try {
      if (JSON.parse(fs.readFileSync(path.join(RT, "probe-facts.json"), "utf8")).materialFlowMsWarm !== undefined) break;
    } catch {}
    await sleep(500);
  }
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
  if (RT) fs.writeFileSync(path.join(RT, "paper-economy-result.json"), JSON.stringify(result, null, 2));
  console.log("Wynik: " + result.result + ` (${checks.filter((c) => c.ok).length}/${checks.length})`);
  process.exit(result.result === "PASS" ? 0 : 1);
}
