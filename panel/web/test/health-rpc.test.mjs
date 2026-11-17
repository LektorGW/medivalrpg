// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { read } from "../public/js/api.js";

// Replace only the Cloudflare base class; execute the real hub RPC and caching code in Node.
const source = (await readFile(new URL("../src/hub.js", import.meta.url), "utf8"))
  .replace('import { DurableObject } from "cloudflare:workers";', "class DurableObject {}")
  .replace('from "./http.js"', `from ${JSON.stringify(new URL("../src/http.js", import.meta.url).href)}`);
const { PanelHub } = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));

function hub() {
  const h = Object.create(PanelHub.prototype);
  h.env = {};
  h.buckets = new Map();
  h.getSession = async () => ({ h: "session", uuid: "user", name: "Admin" });
  h.ctx = { storage: { get: () => assert.fail("health must not read offline cache") } };
  return h;
}

test("admin.health never caches a successful privileged response", async () => {
  const h = hub();
  h.ask = async () => ({ kind: "result", m: { ok: true, data: { points: [] } } });
  h.putCache = () => assert.fail("health must not persist privileged cache");
  assert.deepEqual(await h.callRpc({ uuid: "user", name: "Admin" }, "127.0.0.1", "admin.health", {}), { ok: true, data: { points: [] } });
});

test("admin.health offline and timeout never return previously cached admin data", async () => {
  for (const kind of ["offline", "timeout"]) {
    const h = hub();
    h.ask = async () => ({ kind });
    const response = await h.rpc(new Request("https://panel.test/api/rpc", {
      method: "POST", body: JSON.stringify({ method: "admin.health", params: { range: "24h" } }),
    }), "127.0.0.1");
    assert.equal(response.status, kind === "offline" ? 503 : 504);
    assert.equal("cached" in await response.json(), false);
  }
});

test("plugin denial is propagated without cached health fallback", async () => {
  const h = hub();
  h.ask = async () => ({ kind: "result", m: { ok: false, error: "Brak uprawnień" } });
  const response = await h.rpc(new Request("https://panel.test/api/rpc", {
    method: "POST", body: JSON.stringify({ method: "admin.health" }),
  }), "127.0.0.1");
  assert.deepEqual(await response.json(), { ok: false, error: "Brak uprawnień" });
});

test("browser never stores or falls back to local admin.health data", async () => {
  const previousFetch = globalThis.fetch;
  const previousStorage = globalThis.sessionStorage;
  const removed = [];
  globalThis.sessionStorage = {
    removeItem: key => removed.push(key),
    setItem: () => assert.fail("health must not be stored in browser"),
    getItem: () => assert.fail("health must not use browser fallback"),
  };
  try {
    globalThis.fetch = async () => new Response(JSON.stringify({ ok: true, data: { points: [] } }));
    assert.equal((await read("admin.health")).stale, false);
    globalThis.fetch = async () => { throw new Error("network unavailable"); };
    await assert.rejects(read("admin.health"), /Brak połączenia/);
    globalThis.fetch = async () => new Response(JSON.stringify({ error: "timeout", cached: { data: { secret: true } } }), { status: 504 });
    await assert.rejects(read("admin.health"), /timeout/);
    assert.equal(removed.length, 3);
  } finally {
    globalThis.fetch = previousFetch;
    if (previousStorage === undefined) delete globalThis.sessionStorage;
    else globalThis.sessionStorage = previousStorage;
  }
});
