// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { read } from "../public/js/api.js";

const source = (await readFile(new URL("../src/hub.js", import.meta.url), "utf8"))
  .replace('import { DurableObject } from "cloudflare:workers";', "class DurableObject {}")
  .replace('from "./http.js"', `from ${JSON.stringify(new URL("../src/http.js", import.meta.url).href)}`);
const { PanelHub } = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));

for (const method of ["railway.request.create", "railway.request.quote", "railway.request.confirm", "railway.request.cancel"]) {
  test(method + " is forwarded with session identity and never cached", async () => {
    const hub = Object.create(PanelHub.prototype);
    hub.env = {}; hub.buckets = new Map();
    hub.getSession = async () => ({ h: "session", uuid: "manager", name: "Manager" });
    hub.putCache = () => assert.fail("construction RPC must not be cached");
    const params = { id: "request", token: "server-token", actor: "spoofed" };
    hub.ask = async (kind, message) => {
      assert.equal(kind, "rpc-result"); assert.equal(message.user.uuid, "manager");
      assert.equal(message.method, method); assert.deepEqual(message.params, params);
      return { kind: "result", m: { ok: true, data: { ok: true } } };
    };
    const response = await hub.rpc(new Request("https://panel.test/api/rpc", { method: "POST", body: JSON.stringify({ method, params }) }), "127.0.0.1");
    assert.equal(response.status, 200); assert.equal((await response.json()).ok, true);
  });
}

for(const method of ["railway.get","world.get"]){
test(method+" private data is not cached by the worker and an offline call cannot bypass its manager check", async () => {
  const hub = Object.create(PanelHub.prototype);
  hub.env = {}; hub.buckets = new Map();
  hub.getSession = async () => ({ h: "session", uuid: "user", name: "Manager" });
  hub.ctx = { storage: { get: () => assert.fail("must not read old private cache") } };
  hub.putCache = () => assert.fail("must not store private data");
  hub.ask = async () => ({ kind: "result", m: { ok: true, data: { balance: 1 } } });
  assert.deepEqual(await hub.callRpc({ uuid: "user", name: "Manager" }, "127.0.0.1", method, {}), { ok: true, data: { balance: 1 } });
  hub.ask = async () => ({ kind: "offline" });
  const response = await hub.rpc(new Request("https://panel.test/api/rpc", {
    method: "POST", body: JSON.stringify({ method }),
  }), "127.0.0.1");
  assert.equal(response.status, 503);
  assert.equal("cached" in await response.json(), false);
});

test(method+" browser data never falls back to a former manager's cache", async () => {
  const fetch = globalThis.fetch, storage = globalThis.sessionStorage;
  globalThis.sessionStorage = { removeItem() {}, setItem: () => assert.fail("no private cache"), getItem: () => assert.fail("no private fallback") };
  try {
    globalThis.fetch = async () => new Response(JSON.stringify({ ok: true, data: { balance: 123 } }));
    assert.equal((await read(method)).stale, false);
    globalThis.fetch = async () => new Response(JSON.stringify({ error: "Brak dostępu", cached: { data: { balance: 123 } } }), { status: 403 });
    await assert.rejects(read(method), /Brak dostępu/);
    globalThis.fetch = async () => { throw new Error("offline"); };
    await assert.rejects(read(method), /Brak połączenia/);
  } finally {
    globalThis.fetch = fetch;
    if (storage === undefined) delete globalThis.sessionStorage;
    else globalThis.sessionStorage = storage;
  }
});
}
