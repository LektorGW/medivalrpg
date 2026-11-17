// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Fałszywy plugin Paper „Panel” do testów: łączy się z /plugin (Bearer PANEL_SECRET), wysyła hello,
// odpowiada na `auth` i `rpc` przez podane funkcje; umie wysłać `link-token` i `revoke`.
import WebSocket from "ws";

export class FakePlugin {
  /**
   * @param {string} base  np. http://127.0.0.1:8787
   * @param {string|null} secret
   * @param {{auth?:(m)=>any|Promise<any>, rpc?:(m)=>any|Promise<any>}} handlers
   *   auth → {ok, uuid?, name?, error?}; rpc → {ok, data?, error?} albo undefined (= nie odpowiadaj)
   */
  constructor(base, secret, handlers = {}) {
    this.handlers = handlers;
    this.received = [];
    this.closeInfo = null;
    this.ws = new WebSocket(base.replace(/^http/, "ws") + "/plugin", { headers: secret === null ? {} : { Authorization: "Bearer " + secret } });
    this.opened = new Promise((res, rej) => {
      this.ws.on("open", () => {
        this.ws.send(JSON.stringify({ t: "hello", v: 1 }));
        res();
      });
      this.ws.on("unexpected-response", (_q, resp) => rej(Object.assign(new Error("HTTP " + resp.statusCode), { status: resp.statusCode })));
      this.ws.on("error", rej);
    });
    this.opened.catch(() => {});
    this.closed = new Promise((res) => this.ws.on("close", (code, reason) => res((this.closeInfo = { code, reason: String(reason) }))));
    this.ws.on("message", async (raw) => {
      let m;
      try {
        m = JSON.parse(String(raw));
      } catch {
        return;
      }
      this.received.push(m);
      if (m.t === "auth" && this.handlers.auth) {
        const r = await this.handlers.auth(m);
        if (r) this.send({ t: "auth-result", id: m.id, ...r });
      } else if (m.t === "rpc" && this.handlers.rpc) {
        let r;
        try {
          r = await this.handlers.rpc(m);
        } catch (e) {
          r = { ok: false, error: e.message };
        }
        if (r) this.send({ t: "rpc-result", id: m.id, ...r });
      }
    });
  }
  send(obj) {
    if (this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
  }
  linkToken(token, uuid, name) {
    this.send({ t: "link-token", token, uuid, name });
  }
  revoke(uuid) {
    this.send({ t: "revoke", uuid });
  }
  close() {
    try {
      this.ws.close();
    } catch {}
    return this.closed;
  }
  last(t) {
    for (let i = this.received.length - 1; i >= 0; i--) if (this.received[i].t === t) return this.received[i];
    return null;
  }
}
