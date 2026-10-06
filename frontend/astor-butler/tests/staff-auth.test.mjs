import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';

const source = readFileSync(new URL('../js/staff-auth.js', import.meta.url), 'utf8');
const issuer = 'https://identity.example.test/realms/astor';
function harness(options = {}) {
  const storage = new Map(), requests = [], redirects = [], history = [];
  const location = { origin: 'https://astor.example.test', pathname: '/astor/staff/', search: '',
    assign: value => redirects.push(value) };
  const config = { enabled: true, issuer, clientId: 'astor-staff-ui', ...options.config };
  const window = { location, AstorStaffConfig: options.apiConfig };
  const context = vm.createContext({ window, URL, URLSearchParams, TextEncoder, crypto: webcrypto, btoa,
    AbortController, setTimeout: options.setTimeout || setTimeout, clearTimeout,
    history: { replaceState: (...args) => history.push(args) },
    sessionStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    fetch: async (url, init) => {
      requests.push({ url: String(url), init });
      if (options.fetch) return options.fetch(url, init);
      if (String(url).includes('/login-config')) return new Response(JSON.stringify(config));
      if (String(url).endsWith('/token')) return new Response(JSON.stringify({ access_token: 'fixture-only-access', refresh_token: 'fixture-only-refresh' }));
      return new Response(JSON.stringify({ tenant: 'AERIS', tasks: [] }));
    } });
  vm.runInContext(source, context);
  return { api: window.AstorStaffApi, location, storage, requests, redirects, history };
}

test('disabled API fails clearly and never falls back to demo JSON', async () => {
  const h = harness({ config: { enabled: false } });
  await assert.rejects(h.api.initialize(), /ещё не включён/);
  assert.equal(h.requests.length, 1);
  assert.match(h.requests[0].url, /\/api\/staff\/login-config$/);
  assert.equal(h.requests[0].init.credentials, 'omit');
  assert.equal(h.requests[0].init.cache, 'no-store');
});
test('code + PKCE validates state, removes callback and keeps tokens only in memory', async () => {
  const h = harness();
  assert.equal(await h.api.initialize(), false);
  await h.api.login();
  const login = new URL(h.redirects[0]);
  const pending = JSON.parse(h.storage.get('astor-staff-pkce'));
  assert.equal(login.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(login.searchParams.get('code_challenge'), Buffer.from(await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(pending.verifier))).toString('base64url'));
  assert.equal(login.searchParams.get('redirect_uri'), 'https://astor.example.test/astor/staff/');
  h.location.search = '?code=fixture-code&state=' + pending.state;
  assert.equal(await h.api.initialize(), true);
  assert.equal(h.storage.size, 0);
  assert.equal(h.history.at(-1)[2], 'https://astor.example.test/astor/staff/');
  const exchange = h.requests.find(r => r.url.endsWith('/token'));
  assert.equal(exchange.init.body.get('code_verifier'), pending.verifier);
  await h.api.request('/api/admin/staff-tasks/dashboard');
  assert.equal(h.requests.at(-1).init.headers.Authorization, 'Bearer fixture-only-access');
  assert.ok(h.requests.every(r => !r.url.includes('fixture-only-access') && !r.url.includes('fixture-only-refresh')));
  h.api.logout();
  assert.equal(h.redirects.at(-1), issuer + '/protocol/openid-connect/logout');
  await h.api.request('/api/staff/login-config');
  assert.equal(h.requests.at(-1).init.headers.Authorization, undefined);
});
test('wrong state and expired verifier fail before token exchange', async () => {
  for (const expired of [false, true]) {
    const h = harness(); await h.api.initialize(); await h.api.login();
    const pending = JSON.parse(h.storage.get('astor-staff-pkce'));
    if (expired) { pending.at = Date.now() - 300001; h.storage.set('astor-staff-pkce', JSON.stringify(pending)); }
    h.location.search = '?code=fixture&state=' + (expired ? pending.state : 'wrong');
    await assert.rejects(h.api.initialize(), /Проверка входа/);
    assert.equal(h.storage.size, 0);
    assert.ok(!h.requests.some(r => r.url.endsWith('/token')));
  }
});
test('cross-origin API and non-HTTPS identity are rejected', async () => {
  assert.throws(() => harness({ apiConfig: { apiBase: 'https://other.example.test/' } }), /same-origin/);
  await assert.rejects(harness({ config: { issuer: 'http://identity.example.test' } }).api.initialize(), /Неверная настройка/);
  await assert.rejects(harness().api.request('https://other.example.test/api/staff'), /Invalid staff API origin/);
});
test('denial exposes status/code without raw server diagnostics', async () => {
  const h = harness({ fetch: () => new Response(JSON.stringify({ error: { code: 'STAFF_INACTIVE', message: 'internal details' } }), { status: 403 }) });
  await assert.rejects(h.api.request('/api/staff/tasks'), error => error.status === 403 && error.code === 'STAFF_INACTIVE' && !error.message.includes('internal details'));
});
test('15 second deadline aborts a hung request and reports no acknowledgement', async () => {
  const h = harness({
    setTimeout: (callback, milliseconds) => { assert.equal(milliseconds, 15000); return setTimeout(callback, 0); },
    fetch: (url, options) => new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('timeout', 'AbortError'))))
  });
  await assert.rejects(h.api.request('/api/staff/tasks'), /Запрос не подтверждён/);
});

const workerSource = readFileSync(new URL('../server/index.js', import.meta.url), 'utf8');
const worker = (await import('data:text/javascript;base64,' + Buffer.from(workerSource).toString('base64'))).default;
test('static worker serves live staff assets without caching or demo-data route', async () => {
  const assets = [];
  const env = { ASSETS: { fetch: async url => { assets.push(url.pathname); return new Response('fixture asset'); } } };
  for (const path of ['/staff/', '/staff/index.html', '/js/staff.js', '/js/staff-auth.js']) {
    const response = await worker.fetch(new Request('https://astor.example.test' + path), env);
    assert.equal(response.status, 200); assert.equal(response.headers.get('Cache-Control'), 'no-store');
  }
  const redirect = await worker.fetch(new Request('https://astor.example.test/staff'), env);
  assert.equal(redirect.status, 303); assert.equal(redirect.headers.get('Location'), 'https://astor.example.test/staff/');
  assert.equal((await worker.fetch(new Request('https://astor.example.test/data/staff-demo.json'), env)).status, 404);
  assert.equal((await worker.fetch(new Request('https://astor.example.test/api/staff/tasks'), env)).status, 404);
  assert.deepEqual(assets, ['/staff/index.html', '/staff/index.html', '/js/staff.js', '/js/staff-auth.js']);
});
