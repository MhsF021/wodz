import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';

const source = readFileSync(new URL('../src/lib/navigation.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
const exports = {};
runInNewContext(compiled, { exports, URL });
const { localReturnPath } = exports;

test('keeps local return paths, including queries and fragments', () => {
  const cases = [
    '/',
    '/minhas-equipes',
    '/organizador/campeonatos/123?aba=baterias#horarios',
    '/pagamento/inscricao?checkout=1#cartao',
    '/c/competicao?next=https%3A%2F%2Fexample.invalid#resultados',
  ];
  for (const value of cases) assert.equal(localReturnPath(value), value);
});

test('missing destinations do not create a return redirect', () => {
  assert.equal(localReturnPath(null), null);
  assert.equal(localReturnPath(''), null);
});

test('refuses external URLs, schemes and browser-normalized host syntax', () => {
  const cases = [
    '//example.invalid',
    '///example.invalid',
    'http://example.invalid',
    'https://example.invalid',
    'javascript:alert(1)',
    'data:text/html,test',
    '/\\example.invalid',
    '/safe\\..\\example.invalid',
    'minhas-equipes',
  ];
  for (const value of cases) assert.equal(localReturnPath(value), null, JSON.stringify(value));
});

test('refuses spaces and control characters before URL parsing can discard them', () => {
  const controls = ['\u0000', '\u0009', '\u000a', '\u000d', '\u001f', '\u0020', '\u007f'];
  for (const control of controls) {
    for (const value of [`${control}/minhas-equipes`, `/minhas${control}-equipes`, `/minhas-equipes?q=${control}`]) {
      assert.equal(localReturnPath(value), null, JSON.stringify(value));
    }
  }
});

test('normalizes local dot segments while keeping the destination on the same origin', () => {
  const value = localReturnPath('/c/../minhas-equipes?aba=baterias#horarios');
  assert.equal(value, '/minhas-equipes?aba=baterias#horarios');
  assert.equal(new URL(value, 'https://wodz.invalid').origin, 'https://wodz.invalid');
});

test('refuses paths that become scheme-relative after dot-segment normalization', () => {
  const cases = [
    '/foo/..//example.invalid',
    '/foo/../..//example.invalid',
    '/%2e%2e//example.invalid',
    '/.%2e//example.invalid',
    '/foo/%2E%2E//example.invalid?query=1#fragment',
  ];
  for (const value of cases) assert.equal(localReturnPath(value), null, value);
});
