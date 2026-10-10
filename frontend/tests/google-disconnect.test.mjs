import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';

function compile(relativePath) {
  return ts.transpileModule(readFileSync(new URL(relativePath, import.meta.url), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, refuse) => { resolve = accept; reject = refuse; });
  return { promise, resolve, reject };
}

async function flush() {
  for (let turn = 0; turn < 12; turn += 1) await Promise.resolve();
}

function fakeClock() {
  let now = 0;
  let sequence = 0;
  const timers = new Map();
  return {
    setTimeout(callback, delay = 0) {
      const id = ++sequence;
      timers.set(id, { callback, due: now + delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
    advance(milliseconds) {
      const until = now + milliseconds;
      while (true) {
        const next = [...timers].filter(([, timer]) => timer.due <= until)
          .sort((a, b) => a[1].due - b[1].due)[0];
        if (!next) break;
        const [id, timer] = next;
        now = timer.due;
        timers.delete(id);
        timer.callback();
      }
      now = until;
    },
    get count() { return timers.size; },
  };
}

function credential(payload = { sub: 'confirmed-google-sub', email: 'different@example.invalid' }) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  return `${encode({ alg: 'RS256' })}.${encode(payload)}.test-only-signature`;
}

function identityHarness({ sdkPresent = true, throwAt } = {}) {
  const exports = {};
  const clock = fakeClock();
  const calls = [];
  const scripts = [];
  const callbacks = [];
  const logs = [];
  const google = {
    accounts: { id: {
      initialize(config) {
        calls.push({ action: 'initialize', config });
        if (throwAt === 'initialize') throw new Error('Initialize failed');
      },
      disableAutoSelect() {
        calls.push({ action: 'disableAutoSelect' });
        if (throwAt === 'disableAutoSelect') throw new Error('Auto select failed');
      },
      revoke(subject, callback) {
        calls.push({ action: 'revoke', subject });
        if (throwAt === 'revoke') throw new Error('Revoke failed');
        callbacks.push(callback);
      },
    } },
  };
  const window = {
    ...(sdkPresent ? { google } : {}),
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    atob,
  };
  runInNewContext(compile('../src/lib/googleIdentity.ts'), {
    exports,
    window,
    document: {
      createElement: () => ({}),
      head: { appendChild: (script) => scripts.push(script) },
    },
    atob,
    TextDecoder,
    Uint8Array,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    console: Object.fromEntries(['log', 'warn', 'error', 'info'].map((level) => [level, (...args) => logs.push({ level, args })])),
  });
  return { ...exports, clock, calls, scripts, callbacks, logs, window, google };
}

test('GIS revokes the server-confirmed subject, with automatic sign-in disabled', async () => {
  const gis = identityHarness();
  const result = gis.revokeGoogleGrant('configured-client-id', credential());
  await flush();
  assert.deepEqual(gis.calls.map((call) => call.action), ['initialize', 'disableAutoSelect', 'revoke']);
  assert.equal(gis.calls[0].config.client_id, 'configured-client-id');
  assert.equal(gis.calls[0].config.auto_select, false);
  assert.equal(gis.calls[2].subject, 'confirmed-google-sub');
  assert.notEqual(gis.calls[2].subject, 'different@example.invalid');
  gis.callbacks[0]({ successful: true });
  assert.equal(await result, true);
  assert.equal(gis.clock.count, 0);
  assert.deepEqual(gis.logs, []);
});

test('malformed credentials or a missing subject never fall back to an email', async () => {
  for (const value of ['', 'not-a-jwt', 'header.@@@.signature', credential({ email: 'only@example.invalid' }), credential({ sub: '' }), credential({ sub: 123 })]) {
    const gis = identityHarness();
    assert.equal(await gis.revokeGoogleGrant('configured-client-id', value), false);
    assert.equal(gis.calls.some((call) => call.action === 'revoke'), false);
    assert.equal(gis.clock.count, 0);
  }
});

test('GIS exceptions become an unconfirmed revocation without leaving timers running', async () => {
  for (const throwAt of ['initialize', 'disableAutoSelect', 'revoke']) {
    const gis = identityHarness({ throwAt });
    assert.equal(await gis.revokeGoogleGrant('configured-client-id', credential()), false);
    assert.equal(gis.clock.count, 0);
  }
});

test('a rejected grant is reported as unconfirmed', async () => {
  const gis = identityHarness();
  const result = gis.revokeGoogleGrant('configured-client-id', credential());
  await flush();
  gis.callbacks[0]({ successful: false, error: 'invalid_account' });
  assert.equal(await result, false);
  assert.equal(gis.clock.count, 0);
});

test('duplicate GIS callbacks cannot change the first confirmed result', async () => {
  for (const successful of [true, false]) {
    const gis = identityHarness();
    const result = gis.revokeGoogleGrant('configured-client-id', credential());
    await flush();
    gis.callbacks[0]({ successful });
    gis.callbacks[0]({ successful: !successful });
    assert.equal(await result, successful);
    assert.equal(gis.clock.count, 0);
  }
});

test('a failed GIS script load resolves without granting a successful revocation', async () => {
  const gis = identityHarness({ sdkPresent: false });
  const result = gis.revokeGoogleGrant('configured-client-id', credential());
  await flush();
  assert.equal(gis.scripts.length, 1);
  assert.equal(gis.scripts[0].src, 'https://accounts.google.com/gsi/client');
  gis.scripts[0].onerror();
  assert.equal(await result, false);
  assert.equal(gis.calls.length, 0);
  assert.equal(gis.clock.count, 0);
});

test('the seven-second deadline also covers an SDK script that never finishes', async () => {
  const gis = identityHarness({ sdkPresent: false });
  let settled = false;
  const result = gis.revokeGoogleGrant('configured-client-id', credential()).then((value) => {
    settled = true;
    return value;
  });
  await flush();
  gis.clock.advance(6999);
  await flush();
  assert.equal(settled, false);
  gis.clock.advance(1);
  assert.equal(await result, false);
  assert.equal(gis.calls.length, 0);
  assert.equal(gis.clock.count, 0);
});

test('an absent revoke callback times out and a late callback cannot change the result', async () => {
  const gis = identityHarness();
  const result = gis.revokeGoogleGrant('configured-client-id', credential());
  await flush();
  gis.clock.advance(7000);
  assert.equal(await result, false);
  gis.callbacks[0]({ successful: true });
  await flush();
  assert.equal(await result, false);
  assert.equal(gis.clock.count, 0);
});

test('an SDK that loads after the deadline cannot initialize or revoke a later login', async () => {
  const gis = identityHarness({ sdkPresent: false });
  const result = gis.revokeGoogleGrant('configured-client-id', credential());
  await flush();
  gis.clock.advance(7000);
  assert.equal(await result, false);
  gis.window.google = gis.google;
  gis.scripts[0].onload();
  await flush();
  assert.equal(gis.calls.length, 0);
  assert.equal(gis.clock.count, 0);
});

function disconnectHarness(revoke) {
  const exports = {};
  const grantCalls = [];
  runInNewContext(compile('../src/lib/googleDisconnect.ts'), {
    exports,
    require(specifier) {
      assert.equal(specifier, './googleIdentity');
      return {
        revokeGoogleGrant(clientId, confirmedCredential) {
          grantCalls.push({ clientId, credential: confirmedCredential });
          return revoke(clientId, confirmedCredential);
        },
      };
    },
  });
  const notice = () => {
    const current = exports.getGoogleDisconnectNotice();
    return current === null ? null : JSON.parse(JSON.stringify(current));
  };
  return { ...exports, grantCalls, notice };
}

test('neither logout, GIS nor status publication starts before the local API confirms', async () => {
  const api = deferred();
  const grant = deferred();
  const disconnect = disconnectHarness(() => grant.promise);
  let ended = 0;
  let notices = 0;
  const unsubscribe = disconnect.subscribeGoogleDisconnect(() => { notices += 1; });
  const result = disconnect.disconnectGoogleAccount({
    credential: credential(), clientId: 'configured-client-id', accountClosed: false,
    disconnect: () => api.promise,
    endSession: () => { ended += 1; },
  });
  await flush();
  assert.equal(ended, 0);
  assert.equal(disconnect.grantCalls.length, 0);
  assert.equal(disconnect.notice(), null);
  assert.equal(notices, 0);
  api.resolve();
  await flush();
  assert.equal(ended, 1);
  assert.deepEqual(disconnect.notice(), { accountClosed: false, permission: 'pending' });
  grant.resolve(false);
  await result;
  await flush();
  unsubscribe();
});

test('a failed local disconnect preserves the session and never revokes Google', async () => {
  const disconnect = disconnectHarness(() => Promise.resolve(true));
  const failure = new Error('Local identity was not confirmed');
  let ended = 0;
  let notices = 0;
  disconnect.subscribeGoogleDisconnect(() => { notices += 1; });
  await assert.rejects(disconnect.disconnectGoogleAccount({
    credential: credential(), clientId: 'configured-client-id', accountClosed: true,
    disconnect: () => Promise.reject(failure),
    endSession: () => { ended += 1; },
  }), (error) => error === failure);
  assert.equal(ended, 0);
  assert.equal(disconnect.grantCalls.length, 0);
  assert.equal(disconnect.notice(), null);
  assert.equal(notices, 0);
});

test('local success logs out immediately while GIS remains pending, then publishes confirmation', async () => {
  const grant = deferred();
  const order = [];
  const disconnect = disconnectHarness(() => { order.push('revoke'); return grant.promise; });
  const notices = [];
  disconnect.subscribeGoogleDisconnect(() => notices.push(disconnect.notice()));
  const confirmedCredential = credential();
  const result = disconnect.disconnectGoogleAccount({
    credential: confirmedCredential, clientId: 'configured-client-id', accountClosed: false,
    disconnect: async () => { order.push('disconnect'); },
    endSession: () => { order.push('logout'); },
  });
  await flush();
  assert.deepEqual(order, ['disconnect', 'revoke', 'logout']);
  assert.deepEqual(disconnect.notice(), { accountClosed: false, permission: 'pending' });
  assert.equal(disconnect.grantCalls[0].credential, confirmedCredential);
  assert.equal(disconnect.grantCalls[0].clientId, 'configured-client-id');
  grant.resolve(true);
  await result;
  await flush();
  assert.deepEqual(disconnect.notice(), { accountClosed: false, permission: 'revoked' });
  assert.deepEqual(notices, [
    { accountClosed: false, permission: 'pending' },
    { accountClosed: false, permission: 'revoked' },
  ]);
  assert.deepEqual(Object.keys(disconnect.notice()).sort(), ['accountClosed', 'permission']);
});

test('local account closure is retained even when Google cannot confirm revocation', async () => {
  const grant = deferred();
  const disconnect = disconnectHarness(() => grant.promise);
  let ended = 0;
  const result = disconnect.disconnectGoogleAccount({
    credential: credential(), clientId: 'configured-client-id', accountClosed: true,
    disconnect: async () => {}, endSession: () => { ended += 1; },
  });
  await flush();
  assert.equal(ended, 1);
  assert.deepEqual(disconnect.notice(), { accountClosed: true, permission: 'pending' });
  grant.resolve(false);
  await result;
  await flush();
  assert.deepEqual(disconnect.notice(), { accountClosed: true, permission: 'unconfirmed' });
  assert.equal(ended, 1);
});

test('unexpected GIS rejection still publishes uncertainty after the local session has ended', async () => {
  const grant = deferred();
  const disconnect = disconnectHarness(() => grant.promise);
  let ended = 0;
  const result = disconnect.disconnectGoogleAccount({
    credential: credential(), clientId: 'configured-client-id', accountClosed: false,
    disconnect: async () => {}, endSession: () => { ended += 1; },
  });
  await flush();
  grant.reject(new Error('Unexpected SDK failure'));
  await result;
  await flush();
  assert.equal(ended, 1);
  assert.deepEqual(disconnect.notice(), { accountClosed: false, permission: 'unconfirmed' });
});

test('unsubscribed notices stop receiving asynchronous grant results', async () => {
  const grant = deferred();
  const disconnect = disconnectHarness(() => grant.promise);
  let notices = 0;
  const unsubscribe = disconnect.subscribeGoogleDisconnect(() => { notices += 1; });
  const result = disconnect.disconnectGoogleAccount({
    credential: credential(), clientId: 'configured-client-id', accountClosed: false,
    disconnect: async () => {}, endSession: () => {},
  });
  await flush();
  assert.equal(notices, 1);
  unsubscribe();
  grant.resolve(true);
  await result;
  await flush();
  assert.equal(notices, 1);
  assert.deepEqual(disconnect.notice(), { accountClosed: false, permission: 'revoked' });
});

test('an older grant result cannot overwrite the status of a newer disconnect attempt', async () => {
  const grants = [deferred(), deferred()];
  let requests = 0;
  const disconnect = disconnectHarness(() => grants[requests++].promise);
  const options = {
    credential: credential(), clientId: 'configured-client-id',
    disconnect: async () => {}, endSession: () => {},
  };
  const first = disconnect.disconnectGoogleAccount({ ...options, accountClosed: false });
  await flush();
  const second = disconnect.disconnectGoogleAccount({ ...options, accountClosed: true });
  await flush();
  grants[0].resolve(false);
  await first;
  assert.deepEqual(disconnect.notice(), { accountClosed: true, permission: 'pending' });
  grants[1].resolve(true);
  await second;
  assert.deepEqual(disconnect.notice(), { accountClosed: true, permission: 'revoked' });
});
