import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';

function load(file, imports = {}) {
  const exports = {};
  runInNewContext(ts.transpileModule(readFileSync(new URL(file, import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS },
  }).outputText, { exports, require: id => imports[id] });
  return exports;
}
const { buildScoreInput } = load('../src/lib/scoreInput.ts', { './format': load('../src/lib/format.ts') });
const workout = { id: 1, type: 'FOR_TIME', timeCapSeconds: 600, maxReps: 90, hasTiebreak: false };
const form = { judge: 'JUDGE', status: 'CAPPED', time: '', reps: '90', rounds: '', load: '', tiebreak: '' };

test('For time accepts exactly 90 reps and rejects 91 against the configured total', () => {
  assert.equal(buildScoreInput(workout, form).input.reps, 90);
  assert.match(buildScoreInput(workout, { ...form, reps: '91' }).error, /máximo 90/);
  assert.match(buildScoreInput({ ...workout, maxReps: null }, form).error, /Editar prova/);
});

test('For time uses completion time below cap and only reps at cap, without mixing hidden fields', () => {
  const result = buildScoreInput(workout, { ...form, status: 'VALID', time: '10:00', load: '500' });
  assert.equal(result.input.timeMs, 600000); assert.equal(result.input.reps, undefined); assert.equal(result.input.loadKg, undefined);
  assert.match(buildScoreInput(workout, { ...form, status: 'VALID', time: '10:01' }).error, /acima do time cap/);
  assert.equal(buildScoreInput(workout, { ...form, time: '08:00' }).input.timeMs, undefined);
});

test('AMRAP validates whole rounds and extra reps, and applies a total ceiling only when defined', () => {
  const amrap = { ...workout, type: 'AMRAP', repsPerRound: 90, maxReps: null };
  assert.equal(buildScoreInput(amrap, { ...form, status: 'VALID', rounds: '3', reps: '12' }).input.reps, 282);
  assert.match(buildScoreInput(amrap, { ...form, status: 'VALID', rounds: '0', reps: '90' }).error, /round completo/);
  assert.match(buildScoreInput({ ...amrap, maxReps: 90 }, { ...form, status: 'VALID', rounds: '1', reps: '1' }).error, /máximo 90/);
  assert.match(buildScoreInput({ ...amrap, repsPerRound: null }, { ...form, status: 'VALID' }).error, /Editar prova/);
});

test('negative, fractional and nonfinite rep inputs never reach the launch payload', () => {
  for (const reps of ['-1', '1.5', 'Infinity', 'NaN', '']) assert.ok(buildScoreInput(workout, { ...form, reps }).error);
  for (const rounds of ['-1', '0.5', 'Infinity']) assert.ok(buildScoreInput({ ...workout, type: 'AMRAP', repsPerRound: 30 }, { ...form, status: 'VALID', reps: '1', rounds }).error);
});

test('PR accepts total kilograms only; DNS and DQ contain no result fields', () => {
  const pr = { ...workout, type: 'MAX_LOAD' };
  const result = buildScoreInput(pr, { ...form, status: 'VALID', load: '142,5', time: '09:00' }).input;
  assert.equal(result.loadKg, 142.5); assert.equal(result.reps, undefined); assert.equal(result.timeMs, undefined);
  assert.ok(buildScoreInput(pr, { ...form, status: 'VALID', load: '-1' }).error);
  assert.ok(buildScoreInput(pr, form).error);
  for (const status of ['DNS', 'DQ']) assert.deepEqual(Object.keys(buildScoreInput(pr, { ...form, status }).input), ['status', 'judgeName']);
});
