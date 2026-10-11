import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import { randomUUID } from 'node:crypto';
const compiled = file => ts.transpileModule(readFileSync(new URL(file, import.meta.url), 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
const navigation = {}; runInNewContext(compiled('../src/lib/navigation.ts'), { exports: navigation, URL });
const credential = 'header.payload.signature';
function harness() {
  const storage = new Map(); let now = 1000000; const exports = {};
  runInNewContext(compiled('../src/lib/googleRedirect.ts'), { exports, require: () => navigation, crypto: { randomUUID }, Date: { now: () => now }, sessionStorage: { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) } });
  return { api: exports, storage, advance: n => now += n, complete: state => storage.set('wodz.google-result', JSON.stringify({ state, credential })) };
}
test('same-tab Google preserves a safe destination and consumes transient credentials', () => {
  const h = harness(); const id = h.api.prepareGoogleRedirect({ kind: 'login', role: 'ORGANIZER', next: '/organizador?aba=equipes' }); h.complete(id);
  const result = h.api.consumeGoogleRedirect(); assert.equal(result.flow.next, '/organizador?aba=equipes'); assert.equal(result.flow.role, 'ORGANIZER'); assert.equal(result.credential, credential); assert.equal(h.storage.size, 0);
  assert.equal(h.api.consumeGoogleRedirect(), result); h.api.clearGoogleRedirect(); assert.equal(h.api.consumeGoogleRedirect(), null);
});
test('unsolicited or mismatched returns never adopt an identity', () => {
  for (const mode of ['missing', 'mismatch']) { const h = harness(); if (mode === 'mismatch') h.api.prepareGoogleRedirect({ kind: 'login' }); h.complete('foreign'); assert.equal(h.api.consumeGoogleRedirect(), null); assert.equal(h.storage.size, 0); }
});
test('expired and future flows require another Google confirmation', () => {
  for (const age of [900001, -1]) { const h = harness(); const id = h.api.prepareGoogleRedirect({ kind: 'login' }); h.advance(age); h.complete(id); assert.equal(h.api.consumeGoogleRedirect(), null); }
});
test('button click refreshes selected role and deadline without changing the nonce', () => {
  const h = harness(); const id = h.api.prepareGoogleRedirect({ kind: 'login', role: 'ATHLETE' }); h.advance(1000000); h.api.refreshGoogleRedirect(id, { kind: 'login', role: 'ORGANIZER' }); h.complete(id);
  const result = h.api.consumeGoogleRedirect(); assert.equal(result.flow.id, id); assert.equal(result.flow.role, 'ORGANIZER');
});
test('unlink preserves the intended WODZ account without storing a session token', () => {
  const h = harness(); const id = h.api.prepareGoogleRedirect({ kind: 'unlink', userId: 17, accountClosed: true }); h.complete(id); const result = h.api.consumeGoogleRedirect();
  assert.equal(result.flow.userId, 17); assert.equal(result.flow.kind, 'unlink'); assert.equal(result.flow.accountClosed, true); assert.equal(Object.keys(result).sort().join(','), 'credential,flow');
});
test('malformed data, privilege roles and credentials are discarded and erased', () => {
  for (const mutate of [
    h => h.storage.set('wodz.google-flow', 'invalid'),
    h => { const f = JSON.parse(h.storage.get('wodz.google-flow')); f.role = 'ADMIN'; h.storage.set('wodz.google-flow', JSON.stringify(f)); },
    h => { const r = JSON.parse(h.storage.get('wodz.google-result')); r.credential = '<script>'; h.storage.set('wodz.google-result', JSON.stringify(r)); },
    h => { const r = JSON.parse(h.storage.get('wodz.google-result')); r.credential = 'x'.repeat(8193); h.storage.set('wodz.google-result', JSON.stringify(r)); },
  ]) { const h = harness(); const id = h.api.prepareGoogleRedirect({ kind: 'login' }); h.complete(id); mutate(h); assert.equal(h.api.consumeGoogleRedirect(), null); assert.equal(h.storage.size, 0); }
});
test('return URLs cannot escape WODZ after browser normalization', () => {
  for (const next of ['https://attacker.invalid', '//attacker.invalid', '/foo/..//attacker.invalid']) { const h = harness(); const id = h.api.prepareGoogleRedirect({ kind: 'login', next }); h.complete(id); assert.equal(h.api.consumeGoogleRedirect().flow.next, null); }
});
test('cached navigation holds the preloader for 550ms and waits for overlapping requests', () => {
  const exports = {}; const timers = []; runInNewContext(compiled('../src/lib/activity.ts'), { exports, setTimeout: (fn, ms) => timers.push({ fn, ms }) }); let changed = 0; exports.activityStore.subscribe(() => changed++);
  exports.navigationActivity(); assert.equal(exports.activityStore.getSnapshot(), true); assert.equal(timers[0].ms, 550); const finish = exports.beginActivity(); timers[0].fn(); assert.equal(exports.activityStore.getSnapshot(), true); finish(); finish(); assert.equal(exports.activityStore.getSnapshot(), false); assert.equal(changed, 2);
});
