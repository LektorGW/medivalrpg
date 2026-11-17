// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// E2E: Skarb Świata → Zarząd → „Przelej wszystkim państwom NPC” (world.npcTransfer {direction:"toAllNpc"}). Zrzut 133-.
// Sprawdza: walidację bez RPC, podgląd (liczba państw, suma, rezerwa po, lista), parametry RPC (confirm = suma w md,
// count, key npcbulk-…), saldo rezerwy i skarbców po przelewie, historię `bulk`, idempotencję klucza (replayed),
// odmowy (zła liczba państw, zła suma, brak środków — także ostrzeżenie w modalu) i brak dostępu innych osób.

export async function worldNpcBulkSteps(E) {
  const { browser, step, assert, eq, page, linkLogin, go, shot, text, world, DESKTOP, sleep } = E;
  const wait = sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));
  const DENIED = "Tylko WorldSteward zarządza Skarbem Świata.";
  const flat = (s) => String(s).replace(/[\s  ]/g, "");
  const callsSince = (n, method) => world.calls.slice(n).filter((c) => c.method === method);
  async function waitCall(n, method, timeout = 8000) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
      const c = callsSince(n, method).at(-1);
      if (c) return c;
      await wait(60);
    }
    throw new Error("brak wywołania " + method);
  }
  const rpc = (pg, method, params = {}) =>
    pg.evaluate((method, params) => fetch("/api/rpc", { method: "POST", headers: { "Content-Type": "application/json", "X-Requested-With": "panel" }, body: JSON.stringify({ method, params }), credentials: "same-origin" }).then((x) => x.json()), method, params);
  const clickText = (pg, sel, label) => pg.evaluate((sel, label) => { const b = [...document.querySelectorAll(sel)].find((x) => x.textContent.trim() === label && !x.disabled); if (b) b.click(); return !!b; }, sel, label);
  const typeIn = async (pg, sel, value) => {
    await pg.$eval(sel, (e) => { e.value = ""; e.dispatchEvent(new Event("input", { bubbles: true })); });
    await pg.type(sel, value);
  };
  const npcCount = () => Object.values(world.C).filter((c) => c.npc).length;

  const ctx = await browser.createBrowserContext();
  const p = await page(ctx, DESKTOP, "skarb-przelew-zbiorczy");
  let sent = null;

  await step("Skarb Świata · Zarząd: przelew do wszystkich państw NPC — walidacja, podgląd (liczba, suma, rezerwa po) i parametry RPC", async () => {
    await linkLogin(p, world.U.maciej);
    await go(p, "#/skarbswiata");
    await p.waitForSelector(".ws-npc-bulk");
    const N = npcCount();
    assert(N >= 2, "co najmniej dwa państwa NPC w atrapie: " + N);
    const sec = await text(p, ".ws-npc-bulk");
    assert(sec.includes("Przelej wszystkim państwom NPC"), "nagłówek sekcji");
    assert(sec.includes(`(${N})`), "liczba państw w opisie: " + sec);
    // walidacja formularza bez wysyłania
    let n = world.calls.length;
    assert(await clickText(p, ".ws-npc-bulk button", "Przelej wszystkim…"), "przycisk Przelej wszystkim…");
    await p.waitForFunction(() => document.querySelector(".ws-npc-bulk .ws-npc-errors")?.textContent.includes("Podaj poprawną kwotę na jedno państwo"));
    assert((await text(p, ".ws-npc-bulk .ws-npc-errors")).includes("Podaj powód przelewu"), "błąd powodu");
    assert(!(await p.$(".modal")), "bez modalu przy błędach");
    eq(callsSince(n, "world.npcTransfer").length, 0, "brak RPC przy błędach formularza");

    const before = (await rpc(p, "world.npcTreasuries")).data;
    eq(before.countries.length, N, "lista państw NPC");
    eq(before.bulkAvailable, true, "rejestr przelewów zbiorczych");
    const per = 1500;
    const total = per * N;
    await typeIn(p, 'input[aria-label="Kwota dla każdego państwa NPC"]', "15 zł");
    await typeIn(p, 'input[aria-label="Powód przelewu zbiorczego"]', "Wsparcie zimowe");
    await clickText(p, ".ws-npc-bulk button", "Przelej wszystkim…");
    await p.waitForSelector(".modal .ws-npc-bulk-list");
    const m = await text(p, ".modal");
    const mf = flat(m);
    eq(await p.$$eval(".modal .ws-npc-bulk-list li", (li) => li.length), N, "lista państw w podglądzie");
    for (const c of before.countries) assert(m.includes(c.name.replace(/_/g, " ")), "państwo w podglądzie: " + c.name);
    assert(mf.includes("każdegoz" + N + "państwNPC"), "liczba państw w nagłówku modalu");
    assert(mf.includes("Liczbapaństw" + N), "liczba państw");
    assert(mf.includes("Suma" + total / 100 + "zł(" + total + "md)"), "suma w zł i md: " + m);
    const after = before.reserve - total;
    assert(mf.includes(flat(String(Math.floor(after / 100)))), "rezerwa po przelewie w podglądzie");
    assert(m.includes("WorldSteward: Wsparcie zimowe"), "powód w księdze");
    assert(m.includes("wszystkie przelewy albo żaden"), "opis jednej operacji");
    eq(await p.$$eval(".modal .modal-foot button", (bs) => bs.map((b) => b.textContent.trim())), ["Anuluj", "Przelej razem " + total / 100 + " zł"], "przyciski modalu");
    await shot(p, "133-skarb-zarzad-przelew-zbiorczy");

    n = world.calls.length;
    await clickText(p, ".modal button", "Przelej razem " + total / 100 + " zł");
    const call = await waitCall(n, "world.npcTransfer");
    sent = { ...call.params };
    assert(/^npcbulk-[0-9a-f]{16}$/.test(sent.key), "klucz operacji: " + sent.key);
    const { key, ...rest } = sent;
    eq(rest, { direction: "toAllNpc", amount: "15 zł", confirm: total, count: N, reason: "Wsparcie zimowe" }, "parametry przelewu zbiorczego");
    eq(callsSince(n, "world.npcTransfer").length, 1, "jedno wywołanie RPC");
    await p.waitForFunction(() => !document.querySelector(".modal"));
    await p.waitForFunction(() => document.querySelector(".ws-npc-bulk-history")?.textContent.includes("Wsparcie zimowe"), { timeout: 8000 });

    const now = (await rpc(p, "world.npcTreasuries")).data;
    eq(now.reserve, before.reserve - total, "rezerwa po przelewie");
    for (const c of before.countries) eq(now.countries.find((x) => x.id === c.id).balance, c.balance + per, "skarbiec po przelewie: " + c.name);
    eq(now.bulk[0].key, key, "historia: klucz");
    eq(now.bulk[0].state, "DONE", "historia: stan");
    eq([now.bulk[0].perCountry, now.bulk[0].count, now.bulk[0].total, now.bulk[0].reason], [per, N, total, "Wsparcie zimowe"], "historia: kwoty i powód");
    eq(now.bulk[0].legs.length, N, "historia: odnogi");
    const h = flat(await text(p, ".ws-npc-bulk-history"));
    assert(h.includes(N + "×15zł=" + total / 100 + "zł"), "wpis historii w widoku: " + h);
    // historia rozwija listę państw
    await p.evaluate(() => document.querySelector(".ws-npc-bulk-op summary").click());
    eq(await p.$$eval(".ws-npc-bulk-op[open] li", (li) => li.length), N, "rozwinięta lista państw w historii");
    eq((await rpc(p, "world.stats.summary")).data.difference, 0, "suma kont = pula");
    const f = (await rpc(p, "world.stats.flows", { query: "npc-bulk:" + key })).data;
    eq(f.rows.length, N, "przelewy w księdze z kluczem operacji");
  });

  await step("Skarb Świata · Zarząd: ten sam klucz → replayed bez przelewu; zła liczba państw, zła suma, brak środków → odmowa", async () => {
    assert(sent, "brak poprzedniego przelewu");
    const before = (await rpc(p, "world.npcTreasuries")).data;
    const again = await rpc(p, "world.npcTransfer", sent);
    eq(again.ok, true, "ponowienie klucza: " + JSON.stringify(again));
    eq([again.data.replayed, again.data.paidNow, again.data.state, again.data.durable, again.data.key], [true, 0, "DONE", true, sent.key], "replayed");
    eq(again.data.reserve, before.reserve, "rezerwa bez zmian po ponowieniu");
    eq((await rpc(p, "world.npcTreasuries")).data.bulk.length, before.bulk.length, "historia bez nowego wpisu");
    const N = sent.count;
    const fresh = (k) => ({ ...sent, key: "npcbulk-" + String(k).padStart(16, "0") });
    eq((await rpc(p, "world.npcTransfer", { ...fresh(1), count: N + 1 })).error, "Liczba państw NPC zmieniła się — odśwież podgląd i potwierdź ponownie.", "zła liczba państw");
    eq((await rpc(p, "world.npcTransfer", { ...fresh(2), confirm: 1500 })).error, "Potwierdzona kwota nie zgadza się z kwotą przelewu.", "zła suma");
    eq((await rpc(p, "world.npcTransfer", { ...fresh(3), reason: "x" })).error, "Podaj powód przelewu (3–120 znaków).", "powód");
    eq((await rpc(p, "world.npcTransfer", { ...fresh(4), key: "zły klucz" })).error, "Nieprawidłowy klucz operacji.", "klucz");
    const huge = 90_000_000;
    eq((await rpc(p, "world.npcTransfer", { ...fresh(5), amount: huge, confirm: huge * N })).error, "Za mało środków w rezerwie Skarbu Świata.", "brak środków");
    const after = (await rpc(p, "world.npcTreasuries")).data;
    eq(after.reserve, before.reserve, "odmowy niczego nie przelały");
    eq(after.bulk.length, before.bulk.length, "odmowy bez wpisu w historii");
    // modal ostrzega o braku środków i nie pozwala potwierdzić
    await go(p, "#/skarbswiata");
    await p.waitForSelector(".ws-npc-bulk");
    await typeIn(p, 'input[aria-label="Kwota dla każdego państwa NPC"]', "900000 zł");
    await typeIn(p, 'input[aria-label="Powód przelewu zbiorczego"]', "Za dużo");
    const n = world.calls.length;
    await clickText(p, ".ws-npc-bulk button", "Przelej wszystkim…");
    await p.waitForSelector(".modal .ws-npc-bulk-list");
    assert((await text(p, ".modal")).includes("Za mało środków w rezerwie Skarbu Świata — nic nie zostanie przelane."), "ostrzeżenie w modalu");
    eq(await p.$$eval(".modal .modal-foot button", (bs) => bs.map((b) => b.textContent.trim())), ["Anuluj"], "bez przycisku potwierdzenia");
    await clickText(p, ".modal button", "Anuluj");
    await p.waitForFunction(() => !document.querySelector(".modal"));
    eq(callsSince(n, "world.npcTransfer").length, 0, "nic nie wysłano");
  });
  await ctx.close();

  const adminBefore = world.U.wedrowiec.admin;
  for (const [who, user, setup] of [["drugi administrator (Wedrowiec)", world.U.wedrowiec, () => (world.U.wedrowiec.admin = true)], ["zwykły gracz (Bolko)", world.U.bolko, () => {}]]) {
    const c2 = await browser.createBrowserContext();
    const q = await page(c2, DESKTOP, "przelew-zbiorczy-odmowa");
    await step(`Skarb Świata · przelew zbiorczy: ${who} — brak sekcji i odmowa bezpośredniego RPC`, async () => {
      setup();
      await linkLogin(q, user);
      await go(q, "#/skarbswiata");
      const body = await text(q, "#main");
      assert(body.includes("Brak dostępu"), "karta odmowy");
      assert(!body.includes("Przelej wszystkim"), "brak sekcji przelewu zbiorczego");
      assert(!(await q.$(".ws-npc-bulk")), "brak sekcji w DOM");
      const r = await rpc(q, "world.npcTransfer", { direction: "toAllNpc", amount: "1 zł", confirm: 100 * npcCount(), count: npcCount(), reason: "Próba", key: "npcbulk-00000000000000aa" });
      eq(r, { ok: false, error: DENIED }, "odmowa toAllNpc");
      const t = await rpc(q, "world.npcTreasuries");
      eq(t, { ok: false, error: DENIED }, "odmowa odczytu historii");
      eq(await q.evaluate(() => Object.keys(sessionStorage).filter((k) => k.includes("world.")).length), 0, "brak danych w sessionStorage");
    });
    await c2.close();
  }
  world.U.wedrowiec.admin = adminBefore;
}
