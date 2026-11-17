// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import worker, { CommandsSynchronizer, validateCatalog, webhookConfig, catalogConfig,
  LIMITS, hash } from '../worker.js';

const WEBHOOK_ID = '123456789012345678';
const WEBHOOK = `https://discord.com/api/webhooks/${WEBHOOK_ID}/${'a'.repeat(68)}`;
const CANONICAL = WEBHOOK.replace('/api/', '/api/v10/');
const CATALOG = 'https://map.example.org/commands-catalog.json';
const ENV = { DISCORD_WEBHOOK_URL: WEBHOOK, CATALOG_URL: CATALOG, SYNC_TOKEN: 's'.repeat(48) };
const realNow = Date.now;
globalThis.fetch = async () => { throw new Error('No actual network is allowed in this suite'); };
afterEach(() => {
  Date.now = realNow;
  globalThis.fetch = async () => { throw new Error('No actual network is allowed in this suite'); };
});
const response = (value, status = 200, headers = {}) => Response.json(value, { status, headers });
const catalog = (count = 1) => ({ version: 1,
  pages: Array.from({ length: count }, (_, i) => ({ key: `page-${i}`, content: `/komenda${i} opis` })) });
const request = (path = '/sync', method = 'POST') => new Request(`https://internal${path}`, { method });

class Storage {
  constructor() { this.data = new Map(); this.puts = 0; this.flushes = 0; this.failPut = null; this.failSync = null; }
  async get(key) { return structuredClone(this.data.get(key)); }
  async put(key, value) {
    this.puts++;
    if (this.failPut?.(value)) throw new Error('storage secret must not leak');
    this.data.set(key, structuredClone(value));
  }
  async sync() {
    this.flushes++;
    if (this.failSync?.()) throw new Error('flush failure secret');
  }
  state() { return structuredClone(this.data.get('sync-v1')); }
  seed(value) { this.data.set('sync-v1', structuredClone(value)); }
}

function fixture(count = 1, env = {}) {
  const storage = new Storage();
  const f = { storage, env: { ...ENV, ...env }, catalog: catalog(count), calls: [], writes: [], nextId: 0n };
  f.restart = () => { f.object = new CommandsSynchronizer({ storage }, f.env); };
  f.restart();
  f.sync = () => f.object.fetch(request());
  f.health = async () => (await f.object.fetch(request('/health', 'GET'))).json();
  f.respond = null;
  f.catalogResponse = null;
  globalThis.fetch = async (url, options = {}) => {
    const call = { url: String(url), options, before: storage.state(), flushes: storage.flushes };
    f.calls.push(call);
    if (String(url) === CATALOG && !options.method) {
      return f.catalogResponse ? f.catalogResponse() : response(f.catalog);
    }
    assert.match(String(url), /^https:\/\/discord\.com\/api\/v10\/webhooks\//);
    assert.equal(options.redirect, 'manual');
    assert.ok(options.signal instanceof AbortSignal);
    assert.ok(['POST', 'PATCH', 'DELETE'].includes(options.method));
    f.writes.push(call);
    if (f.respond) return f.respond(call);
    if (options.method === 'DELETE') {
      assert.equal(options.body, undefined);
      return new Response(null, { status: 204 });
    }
    return response({ id: options.method === 'POST' ? String(200000000000000000n + f.nextId++) :
      String(url).split('/').at(-1), webhook_id: WEBHOOK_ID });
  };
  return f;
}

test('contract v1: optional metadata and arbitrary URL/ID fields are ignored', async () => {
  const f = fixture();
  f.catalog.metadata = { url: 'https://evil.invalid' };
  Object.assign(f.catalog.pages[0], { webhook_url: 'https://evil.invalid', message_id: '999999999999999999',
    content_url: 'https://evil.invalid', embeds: [{ url: 'https://evil.invalid' }], allowed_mentions: { parse: ['everyone'] } });
  assert.equal((await f.sync()).status, 200);
  assert.equal(f.calls.length, 2);
  assert.equal(f.writes[0].url, `${CANONICAL}?wait=true`);
  assert.deepEqual(JSON.parse(f.writes[0].options.body), { content: '/komenda0 opis', allowed_mentions: { parse: [] } });
  assert.equal(f.storage.state().records[0].id, '200000000000000000');
});

test('full 250-page boundary publishes in 25 bounded rounds; repeat and restart do not duplicate', async () => {
  assert.equal(LIMITS.pages, 250);
  assert.equal(LIMITS.records, 500);
  assert.equal(validateCatalog(catalog(250)).length, 250);
  const f = fixture(250);
  for (let round = 0; round < 25; round++) {
    const result = await (await f.sync()).json();
    assert.equal(result.writes, 10);
    assert.equal(result.pending, 240 - 10 * round);
    f.restart();
  }
  assert.equal(f.writes.length, 250);
  assert.equal(new Set(f.storage.state().records.map(r => r.id)).size, 250);
  assert.equal((await (await f.sync()).json()).writes, 0);
});

test('251-page boundary is rejected atomically without creating or editing Discord messages', async () => {
  assert.throws(() => validateCatalog(catalog(251)), /catalog_invalid/);
  const f = fixture();
  await f.sync();
  const records = f.storage.state().records;
  const writes = f.writes.length;
  f.catalog = catalog(251);
  assert.equal((await f.sync()).status, 502);
  assert.equal(f.writes.length, writes);
  assert.deepEqual(f.storage.state().records, records);
});

test('only changed content is PATCHed on its persisted own ID after restart', async () => {
  const f = fixture(3);
  await f.sync();
  const ids = f.storage.state().records.map(r => r.id);
  f.catalog.pages.reverse();
  f.catalog.pages[1].content = '/komenda1 nowy opis';
  f.restart();
  assert.equal((await (await f.sync()).json()).writes, 1);
  assert.equal(f.writes.at(-1).options.method, 'PATCH');
  assert.equal(f.writes.at(-1).url, `${CANONICAL}/messages/${ids[1]}`);
  assert.equal((await (await f.sync()).json()).writes, 0);
});

test('removed pages DELETE only their persisted own IDs; reappearance posts once again', async () => {
  const f = fixture(2);
  await f.sync();
  const ids = f.storage.state().records.map(r => r.id);
  const original = structuredClone(f.catalog);
  f.catalog.pages = [];
  const removed = await (await f.sync()).json();
  assert.equal(removed.writes, 2);
  assert.equal(removed.status, 'current');
  assert.deepEqual(f.writes.slice(2).map(c => [c.options.method, c.url]),
    ids.map(id => ['DELETE', `${CANONICAL}/messages/${id}`]));
  assert.ok(f.storage.state().records.every(r => r.id === null && r.hash === null && r.phase === 'ready'));
  assert.equal((await (await f.sync()).json()).writes, 0);
  f.catalog = original;
  assert.equal((await (await f.sync()).json()).writes, 2);
  assert.equal(f.writes.filter(c => c.options.method === 'POST').length, 4);
  assert.equal((await (await f.sync()).json()).writes, 0);
});

test('DELETE: Unknown Message 404 counts as removed; other 404, 5xx and network errors retry later', async () => {
  const f = fixture(1);
  let now = realNow(); Date.now = () => now;
  await f.sync();
  const id = f.storage.state().records[0].id;
  f.catalog.pages = [];
  for (const make of [() => response({ code: 10015, message: WEBHOOK }, 404),
    () => new Response('secret', { status: 503 }), () => { throw new Error('timeout'); }]) {
    f.respond = make;
    const result = await (await f.sync()).json();
    assert.equal(result.lastError, 'delete_retry');
    assert.equal(result.held, 0);
    assert.equal(f.storage.state().records[0].id, id);
    now += 60001;
  }
  f.respond = () => response({ code: 10008, message: 'Unknown Message' }, 404);
  assert.equal((await (await f.sync()).json()).status, 'current');
  assert.equal(f.storage.state().records[0].id, null);
  assert.ok(f.writes.slice(1).every(c => c.options.method === 'DELETE' && c.url === `${CANONICAL}/messages/${id}`));
});

test('new pages are POSTed in catalog order regardless of cursor or old history positions', async () => {
  const f = fixture(6);
  await f.sync();
  const state = f.storage.state();
  state.cursor = 4;
  f.storage.seed(state);
  // Old keys 0..5 are retired; new keys reuse history slot 'page-3' (no ID after deletion) and append others.
  f.catalog = { version: 1, pages: ['n-a', 'page-1', 'n-b', 'n-c'].map(key => ({ key, content: `/${key}` })) };
  f.catalog.pages[1].content = '/page-1';
  const posted = [];
  for (let round = 0; round < 5; round++) {
    f.restart();
    await f.sync();
  }
  for (const call of f.writes.slice(6)) {
    if (call.options.method === 'POST') posted.push(JSON.parse(call.options.body).content);
  }
  assert.deepEqual(posted, ['/n-a', '/n-b', '/n-c']);
  assert.equal(f.writes.filter(c => c.options.method === 'DELETE').length, 5);
  assert.equal((await (await f.sync()).json()).status, 'current');
});

test('a HELD creation blocks later creations so the channel order is never broken', async () => {
  const f = fixture(3);
  f.respond = call => {
    if (JSON.parse(call.options.body).content === '/komenda0 opis') throw new Error('lost');
    return response({ id: '234567890123456789', webhook_id: WEBHOOK_ID });
  };
  assert.equal((await (await f.sync()).json()).held, 1);
  f.restart();
  await f.sync();
  assert.equal(f.writes.length, 1);
});

test('all mention parsing disabled on create/edit and everyone/here literals neutralized', async () => {
  const f = fixture();
  f.catalog.pages[0].content = '@everyone @here @EVERYONE <@123456789012345678> <@&123456789012345678>';
  await f.sync();
  f.catalog.pages[0].content += ' zmiana';
  await f.sync();
  for (const call of f.writes) {
    const body = JSON.parse(call.options.body);
    assert.deepEqual(body.allowed_mentions, { parse: [] });
    assert.doesNotMatch(body.content, /@(everyone|here)/i);
  }
});

test('invalid catalogs are rejected completely before any message edit', async () => {
  const f = fixture();
  await f.sync();
  const originalId = f.storage.state().records[0].id;
  const bad = [null, { version: 2, pages: [] }, { version: '1', pages: [] }, { version: 1 },
    catalog(251), { version: 1, pages: [{ key: 'x', content: 'x'.repeat(1901) }] },
    { version: 1, pages: [{ key: 'x'.repeat(129), content: 'ok' }] },
    { version: 1, pages: [{ key: 'x\n', content: 'ok' }] },
    { version: 1, pages: [{ key: ' x', content: 'ok' }] },
    { version: 1, pages: [{ key: '', content: 'ok' }] },
    { version: 1, pages: [{ key: 'x', content: ' ' }] },
    { version: 1, pages: [{ key: 'x', content: 'ok' }, { key: 'x', content: 'other' }] }];
  for (const value of bad) {
    f.catalog = value;
    assert.equal((await f.sync()).status, 502);
  }
  assert.equal(f.writes.length, 1);
  assert.equal(f.storage.state().records[0].id, originalId);
});

test('contract accepts boundary sizes, namespace/unicode keys and __proto__ safely', () => {
  const pages = [{ key: 'x'.repeat(128), content: 'x'.repeat(1900) },
    { key: '__proto__', content: 'ok' }, { key: 'kraje:/polska/ę', content: 'ok' }];
  assert.deepEqual(validateCatalog({ version: 1, pages }), pages);
  assert.equal(validateCatalog({ version: 1, pages: [{ key: 'mentions', content: '@here'.repeat(380) }] })[0].content.length, 1900);
});

test('strict webhook allowlist rejects all destination/path/query normalization tricks', () => {
  assert.equal(webhookConfig(WEBHOOK).url, CANONICAL);
  assert.equal(webhookConfig(CANONICAL).url, CANONICAL);
  for (const url of [WEBHOOK.replace('https:', 'http:'), WEBHOOK.replace('discord.com', 'discordapp.com'),
    WEBHOOK.replace('discord.com', 'discord.com.evil.pl'), WEBHOOK.replace('discord.com', 'user@discord.com'),
    WEBHOOK.replace('discord.com', 'discord.com:443'), WEBHOOK + '?wait=false', WEBHOOK + '#x',
    WEBHOOK + '/messages/123456789012345678', WEBHOOK + '/', WEBHOOK + '\n',
    WEBHOOK.replace('/api/', '/api/v9/'), WEBHOOK.replace('/api/', '/x/../api/'), WEBHOOK.replace('webhooks', '%77ebhooks'),
    WEBHOOK.replace('https://', 'HTTPS://'), null]) {
    assert.throws(() => webhookConfig(url), /configuration/);
  }
});

test('catalog URL must be configured public HTTPS without credentials/local targets', () => {
  assert.equal(catalogConfig(CATALOG), CATALOG);
  const panelCatalog = 'https://panel.example.workers.dev/commands/catalog.json';
  assert.equal(catalogConfig(panelCatalog), panelCatalog);
  for (const url of ['http://map.example.org/c.json', 'https://localhost/a', 'https://127.0.0.1/a',
    'https://[::1]/a', 'https://192.168.1.1/a', 'https://a.internal/a', 'https://a.local/a',
    'https://u:p@map.example.org/a', 'https://map.example.org:1234/a', CATALOG + '#secret']) {
    assert.throws(() => catalogConfig(url), /configuration/);
  }
});

test('catalog redirects, invalid JSON, oversized declared and streamed bodies never reach Discord', async () => {
  const f = fixture();
  const cases = [() => new Response(null, { status: 302, headers: { Location: WEBHOOK } }),
    () => new Response('{'),
    () => new Response('{}', { headers: { 'Content-Length': String(LIMITS.catalogBytes + 1) } }),
    () => new Response(new ReadableStream({ start(c) { c.enqueue(new Uint8Array(LIMITS.catalogBytes + 1)); c.close(); } }))];
  for (const make of cases) {
    f.catalogResponse = make;
    assert.equal((await f.sync()).status, 502);
  }
  assert.equal(f.writes.length, 0);
  assert.ok(f.calls.every(c => c.options.redirect === 'manual'));
});

test('durable POST intent and flush exist before the network call', async () => {
  const f = fixture();
  f.respond = call => {
    const record = call.before.records[0];
    assert.equal(record.phase, 'posting');
    assert.equal(record.id, null);
    assert.equal(typeof record.intentHash, 'string');
    assert.ok(call.flushes >= 2);
    return response({ id: '234567890123456789', webhook_id: WEBHOOK_ID });
  };
  await f.sync();
  assert.equal(f.storage.state().records[0].phase, 'ready');
});

test('failed intent persistence prevents any POST', async () => {
  const f = fixture();
  f.storage.failPut = s => s.records.some(r => r.phase === 'posting');
  assert.equal((await f.sync()).status, 503);
  assert.equal(f.writes.length, 0);
});

test('failed explicit flush prevents any POST and crash recovery holds the intent', async () => {
  const f = fixture();
  f.storage.failSync = () => f.storage.state()?.records.some(r => r.phase === 'posting');
  assert.equal((await f.sync()).status, 503);
  assert.equal(f.writes.length, 0);
  f.storage.failSync = null;
  f.restart();
  assert.equal((await (await f.sync()).json()).held, 1);
  assert.equal(f.writes.length, 0);
});

test('unknown POST network outcome is HELD permanently across restarts and catalog changes', async () => {
  const f = fixture();
  let now = realNow(); Date.now = () => now;
  f.respond = () => { throw new Error(WEBHOOK); };
  const result = await (await f.sync()).json();
  assert.equal(result.held, 1);
  assert.equal(result.lastError, 'post_outcome_unknown');
  now += 120000;
  f.restart();
  f.catalog.pages[0].content = 'zmiana';
  await f.sync();
  assert.equal(f.writes.length, 1);
  assert.doesNotMatch(JSON.stringify(await f.health()), /aaaa|discord.com|ssss/);
});

test('success followed by failed ID persistence recovers as HELD, never a duplicate POST', async () => {
  const f = fixture();
  f.storage.failPut = s => s.records.some(r => r.id);
  assert.equal((await f.sync()).status, 503);
  assert.equal(f.storage.state().records[0].phase, 'posting');
  f.storage.failPut = null;
  f.restart();
  assert.equal((await (await f.sync()).json()).held, 1);
  assert.equal(f.writes.length, 1);
});

test('POST 5xx, redirect, HTTP 408, missing/wrong identity and empty success are HELD', async () => {
  const cases = [() => new Response('secret', { status: 503 }),
    () => new Response(null, { status: 302, headers: { Location: 'https://evil.pl' } }),
    () => new Response(null, { status: 408 }), () => response({}),
    () => response({ id: '234567890123456789', webhook_id: '999999999999999999' }),
    () => response({ id: 234567890123456780, webhook_id: WEBHOOK_ID }),
    () => new Response(null, { status: 204 })];
  for (const make of cases) {
    const f = fixture();
    f.respond = make;
    assert.equal((await (await f.sync()).json()).held, 1);
    f.restart();
    await f.sync();
    assert.equal(f.writes.length, 1);
  }
});

test('429 POST rejection retries later; persisted cooldown prevents immediate work after restart', async () => {
  const f = fixture();
  let now = realNow(); Date.now = () => now;
  f.respond = () => response({ retry_after: 120 }, 429, { 'Retry-After': '180' });
  const first = await (await f.sync()).json();
  assert.equal(first.held, 0);
  assert.equal(first.cooldownUntil, now + 180000);
  assert.equal(f.storage.state().records[0].phase, 'ready');
  f.restart();
  assert.equal((await (await f.sync()).json()).status, 'deferred');
  assert.equal(f.calls.length, 2);
  now += 180001;
  f.respond = null;
  assert.equal((await (await f.sync()).json()).owned, 1);
});

test('successful rate-limit remaining=0 ends run immediately and preserves cursor', async () => {
  const f = fixture(15);
  f.respond = () => response({ id: '234567890123456789', webhook_id: WEBHOOK_ID }, 200,
    { 'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset-After': '2.5' });
  const result = await (await f.sync()).json();
  assert.equal(result.writes, 1);
  assert.equal(result.pending, 14);
  assert.ok(result.cooldownUntil > realNow());
});

test('rate-limit remaining=0 on PATCH ends run and persists the continuation cursor', async () => {
  const f = fixture(3);
  await f.sync();
  f.catalog.pages.forEach(p => { p.content += ' zmiana'; });
  f.respond = call => response({ id: call.url.split('/').at(-1), webhook_id: WEBHOOK_ID }, 200,
    { 'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset-After': '2.5' });
  const result = await (await f.sync()).json();
  assert.equal(result.writes, 1);
  assert.equal(result.cursor, 1);
});

test('PATCH lost response is safe to retry on same ID after cooldown and restart', async () => {
  const f = fixture();
  let now = realNow(); Date.now = () => now;
  await f.sync();
  const id = f.storage.state().records[0].id;
  f.catalog.pages[0].content += ' zmiana';
  f.respond = () => { throw new Error('timeout'); };
  assert.equal((await (await f.sync()).json()).lastError, 'patch_retry');
  now += 60001;
  f.respond = null;
  f.restart();
  assert.equal((await (await f.sync()).json()).status, 'current');
  assert.ok(f.writes.slice(1).every(c => c.options.method === 'PATCH' && c.url.endsWith(`/messages/${id}`)));
  assert.equal(f.writes.filter(c => c.options.method === 'POST').length, 1);
});

test('PATCH 5xx and 429 preserve old hash, retry later, and never create', async () => {
  for (const code of [500, 429]) {
    const f = fixture();
    let now = realNow(); Date.now = () => now;
    await f.sync();
    const oldHash = f.storage.state().records[0].hash;
    f.catalog.pages[0].content += ' update';
    f.respond = () => response({ retry_after: 3 }, code);
    await f.sync();
    assert.equal(f.storage.state().records[0].hash, oldHash);
    now += 60001;
    f.respond = null;
    await f.sync();
    assert.equal(f.writes.at(-1).options.method, 'PATCH');
    assert.notEqual(f.storage.state().records[0].hash, oldHash);
    Date.now = realNow;
  }
});

test('missing own message is HELD and never recreated', async () => {
  const f = fixture();
  let now = realNow(); Date.now = () => now;
  await f.sync();
  f.catalog.pages[0].content += ' update';
  f.respond = () => response({ message: WEBHOOK }, 404);
  assert.equal((await (await f.sync()).json()).lastError, 'owned_message_missing');
  now += 60001;
  f.restart();
  await f.sync();
  assert.equal(f.writes.length, 2);
  assert.equal((await f.health()).held, 1);
});

test('explicit POST 400 rejection may retry after fixing catalog without unknown-outcome hold', async () => {
  const f = fixture();
  let now = realNow(); Date.now = () => now;
  f.respond = () => response({ error: WEBHOOK }, 400);
  assert.equal((await (await f.sync()).json()).lastError, 'post_rejected');
  assert.equal(f.storage.state().records[0].phase, 'ready');
  now += 60001;
  f.respond = null;
  assert.equal((await (await f.sync()).json()).owned, 1);
});

test('overlapping syncs coalesce and health remains read-only during network wait', async () => {
  const f = fixture();
  let release, entered;
  const ready = new Promise(resolve => { entered = resolve; });
  f.respond = () => new Promise(resolve => {
    release = () => resolve(response({ id: '234567890123456789', webhook_id: WEBHOOK_ID })); entered();
  });
  const first = f.sync();
  await ready;
  const puts = f.storage.puts;
  assert.equal((await f.sync()).status, 202);
  assert.equal((await f.health()).busy, true);
  assert.equal(f.storage.puts, puts);
  release();
  await first;
  assert.equal(f.writes.length, 1);
});

test('webhook binding changes fail closed before fetching catalog or editing old IDs', async () => {
  const f = fixture();
  await f.sync();
  const calls = f.calls.length;
  f.env.DISCORD_WEBHOOK_URL = WEBHOOK.replace('aaaaa', 'bbbbb');
  f.restart();
  assert.equal((await f.sync()).status, 409);
  assert.equal(f.calls.length, calls);
  assert.doesNotMatch(JSON.stringify(f.storage.state()), /discord.com|aaaaa|sssss|commands-catalog/);
});

test('historical ID storage is bounded and reaching capacity causes no partial Discord edits', async () => {
  const f = fixture(0);
  await f.sync();
  const state = f.storage.state();
  state.records = Array.from({ length: LIMITS.records }, (_, i) => ({ key: `old-${i}`, phase: 'ready',
    id: String(200000000000000000n + BigInt(i)), hash: 'old' }));
  f.storage.seed(state);
  f.catalog = catalog(1);
  assert.equal((await f.sync()).status, 409);
  assert.equal(f.writes.length, 0);
});

test('wall-time budget stops before further Discord writes and stores continuation cursor', async () => {
  const f = fixture(20);
  let now = realNow(); Date.now = () => now;
  f.respond = () => {
    now += LIMITS.runMs + 1;
    return response({ id: '234567890123456789', webhook_id: WEBHOOK_ID });
  };
  assert.equal((await (await f.sync()).json()).writes, 1);
  assert.equal(f.storage.state().records.filter(r => r.id).length, 1);
  f.respond = null;
  await f.sync();
  assert.deepEqual(f.writes.slice(1, 3).map(c => JSON.parse(c.options.body).content), ['/komenda1 opis', '/komenda2 opis']);
});

test('health does not initialize storage, fetch catalogs, or return credentials/IDs', async () => {
  const f = fixture();
  const result = await f.health();
  assert.equal(result.owned, 0);
  assert.equal(f.storage.puts, 0);
  assert.equal(f.calls.length, 0);
  assert.doesNotMatch(JSON.stringify(result), /discord.com|aaaaa|sssss|123456789012345678/);
});

test('public routes authenticate before DO access, discard input URLs and reject other methods', async () => {
  const calls = [];
  const env = { ...ENV, COMMANDS: { idFromName: name => name, get: name => ({ fetch: (url, options) => {
    calls.push({ name, url, options }); return response({ safe: true });
  } }) } };
  assert.equal((await worker.fetch(request('/sync'), env)).status, 401);
  assert.equal((await worker.fetch(request('/sync', 'GET'), env)).status, 405);
  assert.equal((await worker.fetch(request('/health', 'POST'), env)).status, 405);
  assert.equal((await worker.fetch(request('/else', 'GET'), env)).status, 404);
  assert.equal(calls.length, 0);
  for (const token of ['short', 'x'.repeat(48), ENV.SYNC_TOKEN + 'x']) {
    assert.equal((await worker.fetch(new Request('https://public/sync', { method: 'POST',
      headers: { Authorization: `Bearer ${token}` } }), env)).status, 401);
  }
  const ok = await worker.fetch(new Request('https://public/sync?webhook=https://evil.pl', { method: 'POST',
    headers: { Authorization: `Bearer ${ENV.SYNC_TOKEN}` }, body: JSON.stringify({ url: 'https://evil.pl' }) }), env);
  assert.equal(ok.status, 200);
  assert.deepEqual(calls[0], { name: 'commands-v1', url: 'https://commands.internal/sync', options: { method: 'POST' } });
  assert.equal((await worker.fetch(request('/health', 'GET'), env)).status, 200);
});

test('cron calls only the same private DO and awaits completion through waitUntil', async () => {
  const calls = []; let pending;
  const env = { COMMANDS: { idFromName: name => name, get: name => ({ fetch: async (url, options) => {
    calls.push({ name, url, options }); return response({ status: 'current' });
  } }) } };
  worker.scheduled({}, env, { waitUntil: promise => { pending = promise; } });
  await pending;
  assert.deepEqual(calls, [{ name: 'commands-v1', url: 'https://commands.internal/sync', options: { method: 'POST' } }]);
});

test('invalid configuration and storage errors return fixed safe errors and release the lock', async () => {
  const f = fixture(1, { DISCORD_WEBHOOK_URL: 'https://evil.pl/SECRET' });
  assert.deepEqual(await (await f.sync()).json(), { error: 'configuration' });
  assert.equal(f.calls.length, 0);
  f.env.DISCORD_WEBHOOK_URL = WEBHOOK;
  f.storage.failPut = () => true;
  assert.deepEqual(await (await f.sync()).json(), { error: 'storage_or_internal_failure' });
  assert.equal(f.object.running, false);
  f.storage.failPut = null;
  assert.equal((await f.sync()).status, 200);
});

test('hash is deterministic and never stored as raw content', async () => {
  assert.equal(await hash('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  const f = fixture();
  await f.sync();
  assert.doesNotMatch(JSON.stringify(f.storage.state()), /komenda0/);
});
