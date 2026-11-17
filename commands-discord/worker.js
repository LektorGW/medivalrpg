// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// No Discord credentials, catalog-supplied URLs or request bodies enter durable state.
export const LIMITS = Object.freeze({ pages: 250, content: 1900, key: 128,
  records: 500, writes: 10, catalogBytes: 1048576, responseBytes: 32768,
  requestMs: 5000, runMs: 20000 });
const STATE_KEY = 'sync-v1';
const OBJECT_NAME = 'commands-v1';
const SNOWFLAKE = /^[0-9]{17,20}$/;
const encoder = new TextEncoder();
const json = (body, status = 200) => Response.json(body, { status,
  headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
const emptyState = () => ({ version: 1, records: [], cursor: 0, cooldownUntil: 0,
  lastRun: null, lastError: null, binding: null, pending: 0 });

export async function hash(value) {
  const bytes = await crypto.subtle.digest('SHA-256', encoder.encode(value));
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, '0')).join('');
}

export function webhookConfig(value) {
  // Exact raw match also rejects URL normalization, queries, ports and encoded paths.
  const match = typeof value === 'string' && value.match(
    /^https:\/\/discord\.com\/api(?:\/v10)?\/webhooks\/([0-9]{17,20})\/([A-Za-z0-9_-]{30,200})$/);
  if (!match || match[0] !== value) throw new Error('configuration');
  return { id: match[1], url: `https://discord.com/api/v10/webhooks/${match[1]}/${match[2]}` };
}

export function catalogConfig(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('configuration'); }
  // Only an operator-configured public HTTPS DNS name; no local/IP targets or redirects.
  if (url.protocol !== 'https:' || url.username || url.password || url.port || url.hash ||
      !/^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$/.test(url.hostname) ||
      /(?:^|\.)(?:localhost|local|internal|test|invalid|example|onion)$/.test(url.hostname)) {
    throw new Error('configuration');
  }
  return url.href;
}

export function validateCatalog(input) {
  if (!input || input.version !== 1 || !Array.isArray(input.pages) || input.pages.length > LIMITS.pages) {
    throw new Error('catalog_invalid');
  }
  const keys = new Set();
  return input.pages.map(page => {
    if (!page || typeof page.key !== 'string' || !page.key.trim() ||
        page.key !== page.key.trim() || page.key.length > LIMITS.key ||
        /[\u0000-\u001f\u007f-\u009f]/u.test(page.key) || keys.has(page.key) ||
        typeof page.content !== 'string' || !page.content.trim() || page.content.length > LIMITS.content) {
      throw new Error('catalog_invalid');
    }
    keys.add(page.key);
    // Even literal everyone/here text is neutralized. All other mentions are disabled in payloads.
    const content = page.content.replace(/@(everyone|here)/gi, '＠$1');
    return { key: page.key, content };
  });
}

async function readJson(response, maxBytes) {
  if (Number(response.headers.get('content-length')) > maxBytes) throw new Error('body_limit');
  if (!response.body) throw new Error('body_invalid');
  const reader = response.body.getReader();
  let length = 0;
  const chunks = [];
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxBytes) throw new Error('body_limit');
      chunks.push(value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
}

function retryDelay(response, body) {
  const candidates = [body?.retry_after, response.headers.get('retry-after'),
    response.headers.get('x-ratelimit-reset-after')].map(Number).filter(n => Number.isFinite(n) && n > 0);
  return Math.ceil(Math.max(1, ...candidates, candidates.length ? 0 : 60) * 1000);
}

function statusOf(state, busy = false) {
  return { service: 'medivalrpg-commands', version: 1, busy,
    records: state.records.length, owned: state.records.filter(r => r.id).length,
    held: state.records.filter(r => r.phase === 'held' || r.phase === 'posting').length,
    pending: state.pending, cursor: state.cursor, cooldownUntil: state.cooldownUntil,
    lastRun: state.lastRun, lastError: state.lastError };
}

export class CommandsSynchronizer {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.running = false;
  }

  async fetch(request) {
    const path = new URL(request.url).pathname;
    if (path === '/health' && request.method === 'GET') {
      try { return json(statusOf(await this.load(), this.running)); }
      catch { return json({ error: 'storage_unavailable' }, 503); }
    }
    if (path !== '/sync') return json({ error: 'not_found' }, 404);
    if (request.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);
    // Coalesce overlapping cron/manual calls; never grow an unbounded work queue.
    if (this.running) return json({ status: 'busy' }, 202);
    this.running = true;
    try { return await this.sync(); }
    catch { return json({ error: 'storage_or_internal_failure' }, 503); }
    finally { this.running = false; }
  }

  async load() { return (await this.ctx.storage.get(STATE_KEY)) ?? emptyState(); }
  async save(state) {
    await this.ctx.storage.put(STATE_KEY, state);
    // Explicit durable flush before external POST (also uses DO's output gate).
    await this.ctx.storage.sync();
  }

  async sync() {
    let webhook, catalogUrl;
    try {
      webhook = webhookConfig(this.env.DISCORD_WEBHOOK_URL);
      catalogUrl = catalogConfig(this.env.CATALOG_URL);
    } catch { return json({ error: 'configuration' }, 503); }
    const state = await this.load();
    const binding = await hash(webhook.url);
    if (state.binding && state.binding !== binding) return json({ error: 'webhook_binding_changed' }, 409);
    if (state.cooldownUntil > Date.now()) return json({ ...statusOf(state), status: 'deferred', writes: 0 });
    const deadline = Date.now() + LIMITS.runMs;
    let pages;
    try {
      const signal = AbortSignal.timeout(LIMITS.requestMs);
      const response = await fetch(catalogUrl, { redirect: 'manual', signal,
        headers: { Accept: 'application/json', 'Cache-Control': 'no-cache' } });
      if (!response.ok) { await response.body?.cancel(); throw new Error('catalog_unavailable'); }
      pages = validateCatalog(await readJson(response, LIMITS.catalogBytes));
    } catch {
      state.lastError = 'catalog_unavailable_or_invalid';
      state.lastRun = Date.now();
      await this.save(state);
      return json({ ...statusOf(state), writes: 0 }, 502);
    }

    const known = new Set(state.records.map(r => r.key));
    if (state.records.length + pages.filter(p => !known.has(p.key)).length > LIMITS.records) {
      return json({ error: 'state_capacity', writes: 0 }, 409);
    }
    const desired = new Map(await Promise.all(pages.map(async p => [p.key,
      { content: p.content, hash: await hash(p.content) }])));
    for (const page of pages) {
      if (!known.has(page.key)) state.records.push({ key: page.key, phase: 'ready', id: null, hash: null });
    }
    for (const record of state.records) {
      // A previous instance died after recording an intent. It may have sent the POST.
      if (record.phase === 'posting') record.phase = 'held';
    }
    state.binding = binding;
    state.lastError = null;
    state.lastRun = Date.now();
    state.cooldownUntil = 0;
    // Work: missing pages (POST), changed own pages (PATCH), own pages no longer in the catalog (DELETE).
    const pending = () => state.records.filter(r => r.phase !== 'held' && (desired.has(r.key)
      ? !r.id || r.hash !== desired.get(r.key).hash : Boolean(r.id))).length;
    state.pending = pending();
    await this.save(state);
    const byKey = new Map(state.records.map(r => [r.key, r]));
    // New messages are created strictly in catalog order, so the channel reads top-down. A HELD page
    // (unknown POST outcome) blocks every later creation until the operator reconciles it.
    const queue = [];
    for (const page of pages) {
      const record = byKey.get(page.key);
      if (record.id) continue;
      if (record.phase === 'held') break;
      queue.push({ record, index: -1 });
    }
    const length = state.records.length;
    for (let visited = 0; visited < length; visited++) {
      const index = (state.cursor + visited) % length;
      const record = state.records[index];
      if (record.phase === 'held' || !record.id) continue;
      if (desired.has(record.key) && record.hash === desired.get(record.key).hash) continue;
      queue.push({ record, index });
    }
    let writes = 0;
    for (const { record, index } of queue) {
      if (writes >= LIMITS.writes || Date.now() >= deadline) break;
      if (index >= 0) state.cursor = (index + 1) % length;
      const target = desired.get(record.key) ?? null;
      const creating = !record.id;
      const deleting = !target;
      if (creating) {
        record.phase = 'posting';
        record.intentHash = target.hash;
        record.intentAt = Date.now();
        await this.save(state);
      }
      const url = creating ? `${webhook.url}?wait=true` : `${webhook.url}/messages/${record.id}`;
      const method = creating ? 'POST' : deleting ? 'DELETE' : 'PATCH';
      // No embeds, attachments, usernames, thread IDs, destinations or IDs from the catalog.
      // DELETE targets only a persisted ID of this webhook's own message; Discord refuses any other.
      const init = { method, redirect: 'manual',
        signal: AbortSignal.timeout(Math.max(1, Math.min(LIMITS.requestMs, deadline - Date.now()))) };
      if (!deleting) {
        init.headers = { 'Content-Type': 'application/json' };
        init.body = JSON.stringify({ content: target.content, allowed_mentions: { parse: [] } });
      }
      let response, body;
      writes++;
      try {
        response = await fetch(url, init);
        // Only successful bodies, 429 and DELETE 404 codes are needed; discard all untrusted errors.
        if ((response.ok && !deleting) || response.status === 429) body = await readJson(response, LIMITS.responseBytes);
        else if (deleting && response.status === 404) body = await readJson(response, LIMITS.responseBytes).catch(() => null);
        else await response.body?.cancel();
      } catch {
        if (creating) record.phase = 'held';
        state.lastError = creating ? 'post_outcome_unknown' : deleting ? 'delete_retry' : 'patch_retry';
        state.cooldownUntil = Date.now() + 60000;
        state.pending = pending();
        await this.save(state);
        break;
      }
      if (response.status === 429) {
        if (creating) record.phase = 'ready'; // Explicit rejection: no message was created.
        state.lastError = 'rate_limited';
        state.cooldownUntil = Date.now() + retryDelay(response, body);
      } else if (deleting) {
        // 2xx, or Discord "Unknown Message" (10008): our own message is gone. The key stays in history
        // without an ID; if it returns to the catalog it is posted again.
        if (response.ok || (response.status === 404 && body?.code === 10008)) {
          record.id = null;
          record.hash = null;
          record.phase = 'ready';
          if (response.headers.get('x-ratelimit-remaining') === '0') {
            state.cooldownUntil = Date.now() + retryDelay(response);
          }
        } else {
          state.lastError = 'delete_retry';
          state.cooldownUntil = Date.now() + 60000;
        }
      } else if (response.ok) {
        if (!body || typeof body.id !== 'string' || !SNOWFLAKE.test(body.id) || body.webhook_id !== webhook.id ||
            (!creating && body.id !== record.id)) {
          record.phase = 'held';
          state.lastError = creating ? 'post_outcome_unknown' : 'message_identity_mismatch';
        } else {
          record.id = body.id;
          record.hash = target.hash;
          record.phase = 'ready';
          delete record.intentHash;
          delete record.intentAt;
        }
        if (response.headers.get('x-ratelimit-remaining') === '0') {
          state.cooldownUntil = Date.now() + retryDelay(response);
        }
      } else {
        const rejected = [400, 401, 403, 404, 405, 413, 415, 422].includes(response.status);
        if (creating) record.phase = rejected ? 'ready' : 'held';
        if (!creating && response.status === 404) record.phase = 'held';
        state.lastError = creating ? (rejected ? 'post_rejected' : 'post_outcome_unknown') :
          (response.status === 404 ? 'owned_message_missing' : 'patch_retry');
        state.cooldownUntil = Date.now() + 60000;
      }
      state.pending = pending();
      await this.save(state);
      if (state.cooldownUntil > Date.now() || state.lastError) break;
    }
    state.pending = pending();
    await this.save(state);
    return json({ ...statusOf(state), writes,
      status: state.records.some(r => r.phase === 'held') ? 'held' : state.pending ? 'pending' : 'current' });
  }
}

async function authorized(request, token) {
  if (typeof token !== 'string' || token.length < 32 || token.length > 256) return false;
  const actual = request.headers.get('authorization') ?? '';
  if (actual.length > 300) return false;
  const [a, b] = await Promise.all([hash(actual), hash(`Bearer ${token}`)]);
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function stub(env) { return env.COMMANDS.get(env.COMMANDS.idFromName(OBJECT_NAME)); }

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path !== '/health' && path !== '/sync') return json({ error: 'not_found' }, 404);
    if (request.method !== (path === '/health' ? 'GET' : 'POST')) return json({ error: 'method_not_allowed' }, 405);
    if (path === '/sync' && !await authorized(request, env.SYNC_TOKEN)) return json({ error: 'unauthorized' }, 401);
    try { return await stub(env).fetch(`https://commands.internal${path}`, { method: request.method }); }
    catch { return json({ error: 'service_unavailable' }, 503); }
  },
  scheduled(_event, env, ctx) {
    ctx.waitUntil((async () => {
      // Cron has no external HTTP authorization or payload: it calls only our private DO.
      const response = await stub(env).fetch('https://commands.internal/sync', { method: 'POST' });
      await response.body?.cancel();
      if (!response.ok) throw new Error('commands_sync_failed');
    })());
  },
};
