// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Statystyki Skarbu Świata (world.stats.*): prywatne dane właściciela nigdy nie trafiają do cache Workera (DO)
// ani do sessionStorage przeglądarki, a odmowa Skarbca daje błąd bez danych zapasowych.
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { read, mutate } from "../public/js/api.js";

const source = (await readFile(new URL("../src/hub.js", import.meta.url), "utf8"))
  .replace('import { DurableObject } from "cloudflare:workers";', "class DurableObject {}")
  .replace('from "./http.js"', `from ${JSON.stringify(new URL("../src/http.js", import.meta.url).href)}`);
const { PanelHub } = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));

const READS = ["world.stats.summary", "world.stats.accounts", "world.stats.flows", "world.stats.account", "world.stats.coins", "world.stats.trail", "world.stats.merchant", "world.stats.households", "world.npcTreasuries", "world.grants"];
const DENIED = "Tylko WorldSteward ma dostęp do statystyk Skarbu Świata.";

function hub(uuid = "owner") {
  const h = Object.create(PanelHub.prototype);
  h.env = {};
  h.buckets = new Map();
  h.getSession = async () => ({ h: "session-" + uuid, uuid, name: "Gracz" });
  h.ctx = { storage: { get: () => assert.fail("world.stats must not read an offline cache") } };
  h.putCache = () => assert.fail("world.stats must not be cached");
  return h;
}
const rpcRequest = (method, params = {}) => new Request("https://panel.test/api/rpc", { method: "POST", body: JSON.stringify({ method, params }) });

for (const method of [...READS, "world.stats.census", "world.npcTransfer", "world.grantsApply"]) {
  test(method + ": Worker nie zapisuje odpowiedzi i przekazuje tożsamość sesji", async () => {
    const h = hub();
    const params = method === "world.stats.flows" ? { from: 1, to: 2, query: "żołd", limit: 1000 } : {};
    h.ask = async (kind, message) => {
      assert.equal(kind, "rpc-result");
      assert.equal(message.user.uuid, "owner");
      assert.equal(message.method, method);
      assert.deepEqual(message.params, params);
      return { kind: "result", m: { ok: true, data: { total: 100000000 } } };
    };
    const res = await h.rpc(rpcRequest(method, params), "127.0.0.1");
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true, data: { total: 100000000 } });
  });

  test(method + ": offline i przekroczony czas → 503/504 bez pola cached", async () => {
    for (const kind of ["offline", "timeout"]) {
      const h = hub();
      h.ask = async () => ({ kind });
      const res = await h.rpc(rpcRequest(method), "127.0.0.1");
      assert.equal(res.status, kind === "offline" ? 503 : 504);
      const j = await res.json();
      assert.equal("cached" in j, false);
      assert.equal(j.ok, false);
    }
  });

  test(method + ": odmowa Skarbca (inny admin) wraca jako błąd bez danych", async () => {
    const h = hub("other-admin");
    h.ask = async () => ({ kind: "result", m: { ok: false, error: DENIED } });
    const res = await h.rpc(rpcRequest(method), "127.0.0.1");
    const j = await res.json();
    assert.deepEqual(j, { ok: false, error: DENIED });
  });
}

async function withBrowser(fn) {
  const fetch0 = globalThis.fetch;
  const storage0 = globalThis.sessionStorage;
  const removed = [];
  globalThis.sessionStorage = { removeItem: (k) => removed.push(k), setItem: () => assert.fail("no sessionStorage for world.stats"), getItem: () => assert.fail("no sessionStorage fallback for world.stats"), key: () => null, length: 0 };
  try {
    await fn(removed);
  } finally {
    globalThis.fetch = fetch0;
    if (storage0 === undefined) delete globalThis.sessionStorage;
    else globalThis.sessionStorage = storage0;
  }
}

for (const method of READS) {
  test(method + ": SPA nie zapisuje w sessionStorage i nie sięga po stare dane", async () => {
    await withBrowser(async (removed) => {
      globalThis.fetch = async () => new Response(JSON.stringify({ ok: true, data: { total: 1 } }));
      const r = await read(method, { days: 30 });
      assert.equal(r.stale, false);
      assert.deepEqual(r.data, { total: 1 });
      assert.ok(removed.some((k) => k.includes(method)), "stary wpis jest usuwany");
      // odmowa: ok:false z komunikatem — wyjątek, żadnych danych
      globalThis.fetch = async () => new Response(JSON.stringify({ ok: false, error: DENIED }));
      await assert.rejects(read(method), (e) => e.message === DENIED && e.app === true);
      // przekroczony czas z (podrobionym) cache w odpowiedzi — nadal błąd, nie dane zapasowe
      // (503 uruchamia w api.js odpytywanie /api/status, które w Node trzymałoby proces przy życiu)
      globalThis.fetch = async () => new Response(JSON.stringify({ ok: false, error: "Serwer gry nie odpowiedział", cached: { at: Date.now(), data: { total: 99 } } }), { status: 504 });
      await assert.rejects(read(method), /nie odpowiedział/);
      globalThis.fetch = async () => { throw new Error("sieć"); };
      await assert.rejects(read(method), /Brak połączenia/);
    });
  });
}

test("world.stats.census: mutacja przekazuje odmowę jako błąd", async () => {
  await withBrowser(async () => {
    globalThis.fetch = async () => new Response(JSON.stringify({ ok: false, error: DENIED }));
    await assert.rejects(mutate("world.stats.census"), (e) => e.message === DENIED);
    globalThis.fetch = async () => new Response(JSON.stringify({ ok: true, data: { ok: true, started: true, message: "Rozpoczęto spis" } }));
    assert.equal((await mutate("world.stats.census")).started, true);
  });
});

test("world.npcTransfer: odmowa i potwierdzenie kwoty przechodzą jako błąd mutacji", async () => {
  await withBrowser(async () => {
    globalThis.fetch = async () => new Response(JSON.stringify({ ok: false, error: "Tylko WorldSteward zarządza Skarbem Świata." }));
    await assert.rejects(mutate("world.npcTransfer", { country: "x", amount: "1 zł", confirm: 100, reason: "test", direction: "toCountry" }), /Tylko WorldSteward zarządza/);
  });
});
