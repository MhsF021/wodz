import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';

const exports = {};
runInNewContext(ts.transpileModule(readFileSync(new URL('../src/lib/leaderboardSearch.ts', import.meta.url), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports });
const { searchLeaderboard } = exports;
const rows = [
  { teamId: 1, rank: 1, teamName: 'FORÇA TOTAL', athleteNames: ['João Silva', 'Maria Lima'] },
  { teamId: 2, rank: 8, teamName: 'ANA SOUZA', athleteNames: ['Ana Souza'] },
];

test('search finds teams and individual athletes regardless of case and accents', () => {
  for (const search of ['forca', 'JOAO', '  silva joão  ', 'maria']) {
    assert.deepEqual(Array.from(searchLeaderboard(rows, search), row => row.teamId), [1]);
  }
  assert.deepEqual(Array.from(searchLeaderboard(rows, 'ana'), row => row.teamId), [2]);
  assert.equal(searchLeaderboard(rows, 'ausente').length, 0);
});

test('filtering preserves official ranking and clearing restores the complete board', () => {
  assert.equal(searchLeaderboard(rows, 'ana')[0], rows[1]);
  assert.equal(searchLeaderboard(rows, 'ana')[0].rank, 8);
  assert.equal(searchLeaderboard(rows, '  '), rows);
  assert.equal(searchLeaderboard([{ teamName: 'LEGADO' }], 'legado').length, 1);
});
