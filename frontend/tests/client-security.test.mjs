import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';

// Executa o cliente real com as fronteiras do navegador substituídas. Não
// precisa de servidor nem de dados pessoais para reproduzir as corridas.
const source = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
  .replace("import { beginActivity } from '../lib/activity';", 'const beginActivity = globalThis.__beginActivity;')
  .replace("(import.meta.env.VITE_API_URL as string | undefined) ?? ''", "globalThis.__apiBase");
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;

function storage(initial = {}, blocked = false) {
  const values = new Map(Object.entries(initial));
  return {
    get length() { return values.size; },
    key(index) { return [...values.keys()][index] ?? null; },
    getItem(key) { if (blocked) throw new Error('Storage blocked'); return values.get(key) ?? null; },
    setItem(key, value) { if (blocked) throw new Error('Storage blocked'); values.set(key, value); },
    removeItem(key) { if (blocked) throw new Error('Storage blocked'); values.delete(key); },
    values,
  };
}

function harness({ blocked = false, persisted = {}, session = {}, origin = 'https://wodz.test', apiBase = '' } = {}) {
  const pending = [];
  const requests = [];
  const events = [];
  const activity = { started: 0, finished: 0 };
  const localStorage = storage(persisted, blocked);
  const sessionStorage = storage(session, blocked);
  const exports = {};
  runInNewContext(compiled, {
    exports,
    localStorage,
    sessionStorage,
    AbortController,
    AbortSignal,
    Event,
    URL,
    __apiBase: apiBase,
    window: { location: { origin }, dispatchEvent: (event) => events.push(event.type) },
    __beginActivity() {
      activity.started += 1;
      return () => { activity.finished += 1; };
    },
    fetch(url, options) {
      requests.push({ url, options });
      return new Promise((resolve, reject) => pending.push({ resolve, reject }));
    },
  });
  return { ...exports, pending, requests, events, activity, localStorage, sessionStorage };
}

test('removes the legacy persistent token and restores only the tab session', () => {
  const client = harness({ persisted: { 'wodz.token': 'legacy' }, session: { 'wodz.token': 'tab' } });
  assert.equal(client.tokenStore.get(), 'tab');
  assert.equal(client.localStorage.values.has('wodz.token'), false);
  client.tokenStore.set('updated');
  assert.equal(client.sessionStorage.values.get('wodz.token'), 'updated');
  assert.equal(client.localStorage.values.has('wodz.token'), false);
  client.tokenStore.clear();
  assert.equal(client.sessionStorage.values.has('wodz.token'), false);
});

test('blocked storage keeps a working in-memory session without persistent fallback', () => {
  const client = harness({ blocked: true });
  client.tokenStore.set('memory');
  assert.equal(client.tokenStore.get(), 'memory');
  assert.equal(client.localStorage.values.size, 0);
  client.tokenStore.clear();
  assert.equal(client.tokenStore.get(), null);
});

test('logout clears all WODZ session artifacts in both stores and preserves unrelated data', () => {
  const initial = { 'wodz.token': 'secret', 'wodz.google-flow': 'flow', 'wodz.google-result': 'credential',
    'wodz.mp-oauth-attempt': 'attempt', 'another-app': 'keep' };
  const client = harness({ persisted: initial, session: initial });
  client.tokenStore.clear();
  assert.deepEqual([...client.sessionStorage.values], [['another-app', 'keep']]);
  assert.deepEqual([...client.localStorage.values], [['another-app', 'keep']]);
});

test('server revocation survives local logout and reports failed requests accurately', async () => {
  const client = harness({ session: { 'wodz.token': 'old-user' } });
  const revoking = client.revokeRemoteSession();
  client.tokenStore.clear();
  assert.equal(client.requests[0].url, '/api/auth/logout');
  assert.equal(client.requests[0].options.headers.Authorization, 'Bearer old-user');
  assert.equal(client.requests[0].options.signal.aborted, false);
  assert.equal(client.requests[0].options.keepalive, true);
  client.pending[0].resolve(new Response(null, { status: 204 }));
  assert.equal(await revoking, true);
  client.tokenStore.set('another-user');
  const failed = client.revokeRemoteSession();
  client.pending[1].reject(new Error('offline'));
  assert.equal(await failed, false);
});

test('logout aborts an in-flight request and refuses its late personal-data response', async () => {
  const client = harness();
  client.tokenStore.set('first-user');
  const request = client.http.get('/api/athlete/teams');
  const signal = client.requests[0].options.signal;
  client.tokenStore.clear();
  assert.equal(signal.aborted, true);
  client.pending[0].resolve(new Response(JSON.stringify({ private: true })));
  await assert.rejects(request, (error) => error.status === 409);
});

test('a late 401 from the old user cannot terminate a new login', async () => {
  const client = harness();
  client.tokenStore.set('first-user');
  const request = client.http.get('/api/athlete/teams');
  client.tokenStore.set('second-user');
  client.pending[0].resolve(new Response('{}', { status: 401 }));
  await assert.rejects(request, (error) => error.status === 409);
  assert.equal(client.tokenStore.get(), 'second-user');
  assert.deepEqual(client.events, []);
});

test('session changes during body decoding also discard the response', async () => {
  const client = harness();
  client.tokenStore.set('first-user');
  let decode;
  const decoding = new Promise((resolve) => { decode = resolve; });
  const request = client.http.get('/api/auth/me');
  client.pending[0].resolve({ ok: true, status: 200, text: () => decoding });
  await Promise.resolve();
  client.tokenStore.clear();
  decode('{"private":true}');
  await assert.rejects(request, (error) => error.status === 409);
});

test('current unauthorized responses clear the session and dispatch logout', async () => {
  const client = harness();
  client.tokenStore.set('expired');
  const request = client.http.get('/api/auth/me');
  client.pending[0].resolve(new Response('{}', { status: 401 }));
  await assert.rejects(request, (error) => error.status === 401);
  assert.equal(client.tokenStore.get(), null);
  assert.deepEqual(client.events, ['wodz:logout']);
});

test('API uses bearer only and bypasses browser cache; polling stays quiet', async () => {
  const client = harness();
  client.tokenStore.set('session');
  const request = client.http.get('/api/auth/me');
  assert.equal(client.requests[0].options.cache, 'no-store');
  assert.equal(client.requests[0].options.credentials, 'omit');
  assert.equal(client.requests[0].options.headers.Authorization, 'Bearer session');
  assert.equal(client.activity.started, 0);
  client.pending[0].resolve(new Response('{}'));
  await request;
  const foreground = client.http.get('/api/public/config', { activity: true });
  assert.equal(client.activity.started, 1);
  client.pending[1].resolve(new Response('{}'));
  await foreground;
  assert.equal(client.activity.finished, 1);
});

test('mutations always finish the activity indicator, including network errors', async () => {
  const client = harness();
  const request = client.http.post('/api/auth/login', { email: 'test@example.invalid', password: 'test-only' });
  assert.equal(client.activity.started, 1);
  client.pending[0].reject(new Error('Offline'));
  await assert.rejects(request, (error) => error.status === 0);
  assert.equal(client.activity.finished, 1);
});

test('ngrok API calls bypass the warning without using visitor cookies', async () => {
  for (const domain of ['ngrok-free.dev', 'ngrok-free.app', 'ngrok.app', 'ngrok.dev', 'ngrok.io']) {
    const client = harness({ origin: `https://wodz.${domain}` });
    const request = client.http.get('/api/public/championships?q=&state=');
    assert.equal(client.requests[0].options.headers['ngrok-skip-browser-warning'], '1');
    assert.equal(client.requests[0].options.credentials, 'omit');
    client.pending[0].resolve(new Response('[]', { headers: { 'Content-Type': 'application/json' } }));
    assert.equal((await request).length, 0);
  }
});

test('warning bypass follows the API hostname and is absent from other origins and lookalikes', async () => {
  for (const [origin, apiBase, expected] of [
    ['https://wodz.test', 'https://api.ngrok-free.dev', '1'],
    ['https://wodz.ngrok-free.dev', 'https://api.wodz.test', undefined],
    ['https://wodz.test', '', undefined],
    ['https://wodz.ngrok-free.dev.attacker.test', '', undefined],
    ['https://evilngrok-free.dev', '', undefined],
  ]) {
    const client = harness({ origin, apiBase });
    const request = client.http.get('/api/public/config');
    assert.equal(client.requests[0].options.headers['ngrok-skip-browser-warning'], expected);
    client.pending[0].resolve(new Response('{}'));
    await request;
  }
});

test('HTTP 200 text, HTML and malformed JSON produce a safe message and end the preloader', async () => {
  for (const body of [
    'You are about to visit wodz.ngrok-free.dev. ERR_NGROK_6024',
    '<html>PRIVATE proxy diagnostics</html>',
    '{"broken": PRIVATE}',
  ]) {
    const client = harness({ origin: 'https://wodz.ngrok-free.dev' });
    const request = client.http.get('/api/public/championships', { activity: true });
    client.pending[0].resolve(new Response(body, { status: 200 }));
    await assert.rejects(request, error => {
      assert.equal(error instanceof client.ApiError, true);
      assert.match(error.message, /Não foi possível carregar/);
      assert.doesNotMatch(error.message, /Unexpected token|PRIVATE|You are|<html>/);
      return true;
    });
    assert.equal(client.activity.finished, 1);
  }
});

test('empty successful responses and JSON problem details preserve API behavior', async () => {
  const client = harness();
  const empty = client.http.del('/api/auth/me');
  client.pending[0].resolve(new Response(null, { status: 204 }));
  assert.equal(await empty, undefined);
  const rejected = client.http.post('/api/auth/login', {});
  client.pending[1].resolve(new Response(JSON.stringify({ detail: 'Confira os campos.', errors: { email: 'E-mail inválido.' } }), {
    status: 400, headers: { 'Content-Type': 'application/problem+json' },
  }));
  await assert.rejects(rejected, error => error.status === 400 && client.errorMessage(error) === 'Confira os campos.: E-mail inválido.');
});

test('an interrupted response body does not leak transport details or leave activity active', async () => {
  const client = harness();
  const request = client.http.get('/api/public/config', { activity: true });
  client.pending[0].resolve({ ok: true, status: 200, text: () => Promise.reject(new Error('PRIVATE transport error')) });
  await assert.rejects(request, error => error.message === 'A conexão foi interrompida. Tente novamente.');
  assert.equal(client.activity.finished, 1);
});
