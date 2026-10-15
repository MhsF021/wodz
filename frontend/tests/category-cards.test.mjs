import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

const nativeRequire = createRequire(import.meta.url);
function module(file, imports) {
  const exports = {};
  const code = ts.transpileModule(readFileSync(new URL(file, import.meta.url), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  runInNewContext(code, { exports, Intl, require: id => id in imports ? imports[id] : nativeRequire(id) });
  return exports;
}
const format = module('../src/lib/format.ts', {});
const composition = module('../src/lib/categoryComposition.ts', {});
const movementStandards = module('../src/lib/movementStandards.ts', {});
const table = module('../src/components/MovementStandardsTable.tsx', { '../lib/movementStandards': movementStandards });
const category = { id: 1, name: 'Scaled misto', teamSize: 2, genderPolicy: 'MIXED', allowedMaleCounts: [1],
  price: 210, maxTeams: 20, slotsLeft: 5, description: 'Duplas mistas.',
  movementStandards: [{ movement: 'Clean and jerk', male: '45 kg', female: '25 kg' },
    { movement: 'Toes to bar', male: '✓', female: '✕' }],
  workouts: [{ id: 4, number: 1, name: 'Largada', type: 'FOR_TIME', timeCapSeconds: 600, description: '30 cleans' }] };
const championship = { name: 'TCB 2026', slug: 'tcb-2026', registrationOpen: true, categories: [category] };

test('Nova Prova is in the organizer composition filter row and never on the public page', () => {
  const action=React.createElement('button',{},'Nova Prova');
  const organizer=renderToStaticMarkup(harness().CategoryCards({championship,organizer:true,filterActions:action}));
  assert.match(organizer,/champ-filters[\s\S]*Misto[\s\S]*champ-filters__actions[\s\S]*Nova Prova/);
  assert.doesNotMatch(renderToStaticMarkup(harness().CategoryCards({championship,filterActions:action})),/Nova Prova/);
});

test('an unchecked movement is published as not requested while stored loads are preserved', () => {
  const cat={...category,movementStandards:[{movement:'Snatch',male:'45 kg',female:'25 kg',allowed:false}]};
  const html=renderToStaticMarkup(React.createElement(table.MovementStandardsTable,{category:cat}));
  assert.match(html,/Snatch/); assert.match(html,/Não será pedido/); assert.doesNotMatch(html,/45 kg|25 kg/);
});
function harness(selected = null, filter = 'ALL') {
  let selection; let chosenFilter; let hook = 0;
  const components = module('../src/components/CategoryCards.tsx', {
    react: { useState: () => hook++ % 2 === 0 ? [selected, value => { selection = value; }] : [filter, value => { chosenFilter = value; }] },
    'react-router': { Link: p => React.createElement('a', { href: p.to }, p.children) },
    '../lib/format': format, '../lib/categoryComposition': composition, './MovementStandardsTable': table,
    './Dialog': { Dialog: p => React.createElement('section', { role: 'dialog', 'aria-label': p.title }, p.children) },
  });
  return { ...components, selection: () => selection, filter: () => chosenFilter };
}
function buttons(node) {
  if (!node || typeof node !== 'object') return [];
  return [...(node.type === 'button' ? [node] : []), ...React.Children.toArray(node.props?.children).flatMap(buttons)];
}

test('standards appear exclusively in the selected category popup', () => {
  const page = harness();
  const tree = page.CategoryCards({ championship });
  const html = renderToStaticMarkup(tree);
  assert.match(html, /class="category-card /);
  assert.doesNotMatch(html, /<table|Clean and jerk|45 kg|25 kg/);
  assert.doesNotMatch(html, /role="dialog"/);
  const open = buttons(tree).find(b => b.props['aria-haspopup'] === 'dialog');
  open.props.onClick(); assert.equal(page.selection(), category.id);
  const selected = harness(category.id);
  const details = renderToStaticMarkup(selected.CategoryCards({ championship }));
  assert.match(details, /role="dialog" aria-label="Scaled misto"/);
  assert.match(details, /Clean and jerk/); assert.match(details, /45 kg/); assert.match(details, /25 kg/);
  assert.match(details, /Largada/); assert.match(details, /30 cleans/);
  assert.doesNotMatch(details, /<dt>Vagas|5 de 20 disponíveis|Limite de 20 equipes/);
});

test('category shows unit price per athlete and the total for its team size', () => {
  const page = harness();
  const html = renderToStaticMarkup(page.CategoryDetails({ category: { ...category, price: 59.99, teamSize: 3 }, championship, onClose() {} }));
  assert.match(html, /Inscrição por atleta/);
  assert.match(html, /Total da equipe/);
  assert.match(html, /179,97/);
});

test('composition filters select actual category rules, include individuals, and restore all categories', () => {
  const items = [category, { ...category, id: 2, name: 'RX individual masculino', genderPolicy: 'MALE', teamSize: 1 },
    { ...category, id: 3, name: 'Iniciante feminino', genderPolicy: 'FEMALE' },
    { ...category, id: 4, name: 'Composição pendente', genderPolicy: null }];
  const event = { ...championship, categories: items };
  for (const [filter, label, id] of [['ALL', 'Todos', null], ['MALE', 'Masculino', 2], ['FEMALE', 'Feminino', 3], ['MIXED', 'Misto', 1]]) {
    const page = harness(null, filter);
    const tree = page.CategoryCards({ championship: event });
    const html = renderToStaticMarkup(tree);
    assert.equal((html.match(/class="category-card /g) ?? []).length, filter === 'ALL' ? 4 : 1);
    assert.equal(buttons(tree).find(button => button.props.children === label).props['aria-pressed'], true);
    if (id) assert.match(html, new RegExp(items.find(item => item.id === id).name));
    buttons(tree).find(button => button.props.children === 'Todos').props.onClick();
    assert.equal(page.filter(), 'ALL');
  }
  const page = harness(null, 'FEMALE');
  const empty = renderToStaticMarkup(page.CategoryCards({ championship }));
  assert.match(empty, /Nenhuma categoria encontrada para este filtro/);
  assert.doesNotMatch(empty, /class="category-card /);
});

test('same-sex tables show the applicable column and mixed tables show both', () => {
  const loadsOnly = { ...category, movementStandards: [category.movementStandards[0]] };
  const female = renderToStaticMarkup(React.createElement(table.MovementStandardsTable, { category: { ...loadsOnly, genderPolicy: 'FEMALE' } }));
  assert.match(female, /<th scope="col">Carga<\/th>/); assert.match(female, /25 kg/); assert.doesNotMatch(female, /[Mm]asculin|[Ff]eminin|45 kg/);
  const male = renderToStaticMarkup(React.createElement(table.MovementStandardsTable, { category: { ...loadsOnly, genderPolicy: 'MALE' } }));
  assert.match(male, /<th scope="col">Carga<\/th>/); assert.match(male, /45 kg/); assert.doesNotMatch(male, /[Mm]asculin|[Ff]eminin|25 kg/);
  const mixed = renderToStaticMarkup(React.createElement(table.MovementStandardsTable, { category: loadsOnly }));
  assert.match(mixed, /Carga masculina/); assert.match(mixed, /Carga feminina/);
  assert.equal((female.match(/scope="col"/g) ?? []).length, 2);
  assert.equal((male.match(/scope="col"/g) ?? []).length, 2);
  assert.equal((mixed.match(/scope="col"/g) ?? []).length, 3);
});

test('movement-only items publish checkbox availability separately from loads, including legacy symbols', () => {
  const cat = { ...category, movementStandards: [...category.movementStandards,
    { movement: 'Handstand walk', male: '—', female: '—', kind: 'MOVEMENT', allowed: false }] };
  const html = renderToStaticMarkup(React.createElement(table.MovementStandardsTable, { category: cat }));
  assert.match(html, /Com carga[\s\S]*45 kg[\s\S]*25 kg/);
  assert.match(html, /Somente movimento[\s\S]*Situação[\s\S]*Toes to bar[\s\S]*Poderá ser pedido[\s\S]*Handstand walk[\s\S]*Não será pedido/);
  assert.doesNotMatch(html, /✓|✕|Carga[^<]*[\s\S]*>✓</);
});

test('legacy movement flags respect the category gender when converted into a checkbox', () => {
  const row = { movement: 'Toes to bar', male: '✓', female: '✕' };
  assert.equal(movementStandards.normalizeMovementStandard(row, 'MALE').allowed, true);
  assert.equal(movementStandards.normalizeMovementStandard(row, 'FEMALE').allowed, false);
  assert.equal(movementStandards.normalizeMovementStandard({ ...row, allowed: false }, 'MALE').allowed, false);
});

test('category details refresh with current data and handle absent standards and workouts without displaying capacity', () => {
  const page = harness(1);
  const updated = { ...category, price: 0, teamSize: 1, genderPolicy: 'FEMALE', name: 'RX individual feminino', maxTeams: null, movementStandards: [], workouts: [] };
  const html = renderToStaticMarkup(page.CategoryCards({ championship: { ...championship, categories: [updated] } }));
  assert.match(html, /RX individual feminino/); assert.match(html, /Individual/); assert.match(html, /Gratuita/);
  assert.doesNotMatch(html, /[Vv]agas|Sem limite|disponíveis/);
  assert.match(html, /cargas e os movimentos serão divulgados/); assert.match(html, /provas desta categoria serão divulgadas/);
  assert.doesNotMatch(html, /Largada/);
});

test('organizer category cards omit prices and keep standards and workouts inside the popup', () => {
  const page = harness();
  const html = renderToStaticMarkup(page.CategoryCards({ championship, organizer: true }));
  assert.doesNotMatch(html, /R\$|Gratuita|\/ atleta|30 cleans|45 kg/);
  const selected = harness(category.id);
  const popup = renderToStaticMarkup(selected.CategoryCards({ championship, organizer: true,
    renderWorkoutActions: () => React.createElement('button', {}, 'Editar prova') }));
  assert.match(popup, /45 kg|30 cleans/); assert.match(popup, /Editar prova/);
  assert.doesNotMatch(popup, /Inscrição por atleta|Total da equipe|Ir para inscrição|R\$/);
});

test('public category popups never expose organizer editing even when callbacks are passed', () => {
  const page = harness(category.id);
  const html = renderToStaticMarkup(page.CategoryDetails({ category, championship,
    renderWorkoutActions: () => React.createElement('button', {}, 'Editar prova'),
    renderWorkoutEditor: () => React.createElement('form', {}, 'Editor exclusivo') }));
  assert.doesNotMatch(html, /Editar prova|Editor exclusivo/);
  assert.match(html, /Inscrição por atleta|30 cleans/);
});
