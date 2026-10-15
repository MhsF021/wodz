import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

const nativeRequire = createRequire(import.meta.url);
function load(file, imports = {}) {
  const exports = {};
  const code = ts.transpileModule(readFileSync(new URL(file, import.meta.url), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  runInNewContext(code, { exports, URLSearchParams, require: id => id in imports ? imports[id] : nativeRequire(id) });
  return exports;
}
const format = load('../src/lib/format.ts');
const scoreInput = load('../src/lib/scoreInput.ts', { './format': format });
const fields = load('../src/components/Field.tsx');
const workout = { id: 11, categoryId: 1, number: 1, name: 'Largada', type: 'FOR_TIME', timeCapSeconds: 600, maxReps: 90, hasTiebreak: false, description: '30 cleans' };
const championship = { id: 1, name: 'Arena', slug: 'arena', city: 'Recife', state: 'PE', venueName: 'Arena', startDate: '2030-01-01', endDate: '2030-01-02',
  status: 'PUBLISHED', started: false, registrationFlag: false, heatLanes: 2, heatFirstStart: '2030-01-01T11:00:00Z', heatIntervalMinutes: 2,
  categories: [{ id: 1, name: 'Scaled misto', displayOrder: 0, workouts: [workout, { ...workout, id: 12, number: 2, name: 'Chipper' }] },
    { id: 2, name: 'RX masculino', displayOrder: 1, workouts: [{ ...workout, id: 21, categoryId: 2 }] }] };
const states = { Loading: () => null, ErrorState: () => null, InlineError: () => null,
  Empty: p => React.createElement('p', {}, p.title, p.children) };
function nodes(node) {
  if (Array.isArray(node)) return node.flatMap(nodes);
  if (!node?.props) return [];
  if (typeof node.type === 'function') return nodes(node.type(node.props));
  return [node, ...nodes(node.props.children)];
}
function text(node) { return renderToStaticMarkup(node).replace(/<[^>]+>/g, ''); }
function harness(query = '') {
  let index = 0; const values = []; let params = new URLSearchParams(query); const calls = []; const invalidations = [];
  const heats = [1, 2].map(n => ({ heatId: n, heatNumber: n, status: 'UPCOMING', scheduledAt: `2030-01-01T11:${n === 1 ? '00' : '12'}:00Z`,
    workoutId: 11, workoutNumber: 1, workoutName: 'Largada', categoryId: 1, categoryName: 'Scaled misto', durationSeconds: 600, intervalMinutes: 2,
    lanes: [{ teamId: n, teamName: `Equipe ${n}`, lane: 1 }] }));
  const react = { ...React, useId: () => 'qa-filter', useEffect: () => {}, useRef: value => ({ current: value }),
    useState: initial => { const key = index++; if (!(key in values)) values[key] = typeof initial === 'function' ? initial() : initial;
      return [values[key], next => { values[key] = typeof next === 'function' ? next(values[key]) : next; }]; } };
  const router = { useParams: () => ({ id: '1' }), useSearchParams: () => [params, next => { params = typeof next === 'function' ? next(params) : new URLSearchParams(next); }],
    Link: p => React.createElement('a', { href: p.to }, p.children) };
  const api = { updateWorkout: async (id, input) => { calls.push({ kind: 'update', id, input }); return { ...workout, ...input }; },
    createWorkouts: async (id, input) => { calls.push({ kind: 'create', id, input }); return []; }, deleteWorkout: async id => { calls.push({ kind: 'delete', id }); },
    startHeat: async id => { calls.push({ kind: 'start', id }); const h = heats.find(h => h.heatId === id); h.status = 'IN_PROGRESS'; h.startedAt = '2026-10-14T16:00:00Z'; return h; },
    setHeatStatus: async (id, status) => { const h = heats.find(h => h.heatId === id); h.status = status; return h; } };
  const queryClient = { invalidateQueries: ({ queryKey }) => { invalidations.push(Array.from(queryKey)); return Promise.resolve(); } };
  const queries = { useQueryClient: () => queryClient,
    useMutation: options => ({ isPending: false, isSuccess: false, error: null, reset: () => {},
      mutate: input => Promise.resolve(options.mutationFn(input)).then(result => options.onSuccess?.(result)) }),
    useQuery: ({ queryKey }) => ({ isLoading: false, data: queryKey[0] === 'admin-heats' ? heats : queryKey[0] === 'admin-teams'
      ? heats.map(h => ({ teamId: h.heatId, teamName: `Equipe ${h.heatId}`, categoryId: 1, status: 'CONFIRMED' }))
      : queryKey[0] === 'admin-scores' ? { workout: championship.categories.flatMap(c => c.workouts).find(w => w.id === queryKey[1]), entries: [] } : championship }) };
  return { react, router, queries, api, heats, calls, invalidations, params: () => params,
    render: (Component, props) => { index = 0; return Component(props); } };
}
function workoutsModule(h) {
  return load('../src/pages/admin/WorkoutsTab.tsx', { react: h.react, '@tanstack/react-query': h.queries,
    '../../api/endpoints': { adminApi: h.api }, '../../lib/format': format, '../../components/Field': fields,
    '../../components/States': states, '../../components/Dialog': { Dialog: p => React.createElement('aside', {}, p.children) },
    '../../components/CategoryCards': { CategoryCards: p => React.createElement('div', {}, React.createElement('button', { 'aria-haspopup': 'dialog' }, 'Abrir categoria'), p.filterActions) } });
}
function categoriesModule(h, api = h.api) {
  return load('../src/pages/admin/CategoriesTab.tsx', {
    react: h.react, '@tanstack/react-query': h.queries, '../../api/endpoints': { adminApi: api },
    '../../lib/format': format, '../../lib/categoryComposition': load('../src/lib/categoryComposition.ts'),
    '../../lib/movementStandards': load('../src/lib/movementStandards.ts'),
    '../../components/Field': fields, '../../components/Plate': { PlateChip: () => null }, '../../components/States': states,
  });
}
function heatsModule(h) {
  return load('../src/pages/admin/HeatsTab.tsx', { react: h.react, 'react-router': h.router, '@tanstack/react-query': h.queries,
    '../../api/endpoints': { adminApi: h.api }, '../../api/client': { errorMessage: String }, '../../lib/format': format,
    '../../components/Field': fields, '../../components/States': states, '../../components/StatusBadge': { HeatBadge: () => null },
    '../../components/LiveHeat': { LiveHeat: p => React.createElement('section', { className: 'live' }, `Cronômetro: ${p.heat.startedAt}`, p.actions) },
    '../../components/HeatTiming': { HeatTiming: () => null }, '../../components/Dialog': { Dialog: p => React.createElement('aside', {}, p.children) },
    './HeatSettingsPanel': { HeatSettingsPanel: () => React.createElement('section', { 'aria-label': 'Configurações das baterias' }) } });
}

test('Nova prova is absent in ongoing, finished and cancelled events while category popups remain accessible', () => {
  for (const status of ['PUBLISHED', 'IN_PROGRESS', 'FINISHED', 'CANCELLED']) {
    const h = harness(); const { WorkoutsTab } = workoutsModule(h);
    const tree = h.render(WorkoutsTab, { championship: { ...championship, status } });
    const html = renderToStaticMarkup(tree);
    assert.match(html, /Abrir categoria/);
    assert.equal(html.includes('Nova Prova'), status === 'PUBLISHED');
    const categoryCards = tree.props.children[0];
    const actions = categoryCards.props.renderWorkoutActions(workout);
    assert.equal(Boolean(actions), status === 'PUBLISHED' || status === 'IN_PROGRESS');
    if (status === 'IN_PROGRESS') assert.doesNotMatch(renderToStaticMarkup(actions), /Excluir/);
  }
});

test('ongoing workout editor allows text corrections and sends the original scoring format', () => {
  const h = harness(); const { WorkoutForm } = workoutsModule(h);
  const props = { championship: { ...championship, status: 'IN_PROGRESS' }, workout, onSaved: async () => {} };
  let tree = h.render(WorkoutForm, props);
  nodes(tree).find(n => n.type === 'input' && n.props.id.endsWith('-name')).props.onChange({ target: { value: 'Nome corrigido', selectionStart: null, selectionEnd: null } });
  tree = h.render(WorkoutForm, props);
  const all = nodes(tree);
  assert.equal(all.find(n => n.type === 'fieldset' && n.props.className.includes('workout-specs')).props.disabled, true);
  assert.equal(all.find(n => n.type === 'fieldset').props.disabled, false);
  tree.props.onSubmit({ preventDefault() {} });
  assert.equal(h.calls[0].input.name, 'NOME CORRIGIDO');
  assert.equal(h.calls[0].input.timeCapSeconds, 600);
  assert.equal(h.calls[0].input.type, 'FOR_TIME');
  assert.equal(h.calls[0].input.number, 1);
});

test('result filters clear the previous heat when switching category or workout and keep URL selections in sync', () => {
  const h = harness('aba=resultados&prova=12&bateria=99');
  const { ScoresTab } = load('../src/pages/admin/ScoresTab.tsx', { react: h.react, 'react-router': h.router, '@tanstack/react-query': h.queries,
    '../../api/endpoints': { adminApi: h.api }, '../../lib/format': format, '../../lib/scoreInput': scoreInput, '../../components/Field': fields,
    '../../components/States': states, '../../components/StatusBadge': { HeatBadge: () => null }, '../../api/client': { errorMessage: String } });
  let tree = h.render(ScoresTab, { championship });
  let controls = nodes(tree);
  assert.equal(controls.find(n => n.props.id === 'results-workout').props.value, 12);
  controls.find(n => n.props.id === 'results-category').props.onChange({ target: { value: '2' } });
  assert.equal(h.params().get('prova'), '21'); assert.equal(h.params().get('categoria'), '2'); assert.equal(h.params().has('bateria'), false);
  tree = h.render(ScoresTab, { championship }); controls = nodes(tree);
  assert.equal(controls.find(n => n.props.id === 'results-workout').props.value, 21);
  controls.find(n => n.props.id === 'results-category').props.onChange({ target: { value: '1' } });
  tree = h.render(ScoresTab, { championship });
  nodes(tree).find(n => n.props.id === 'results-workout').props.onChange({ target: { value: '12' } });
  assert.equal(h.params().get('prova'), '12'); assert.equal(h.params().get('aba'), 'resultados');
});

test('Resultados is immediately after Equipes and old pontuacao links still select it', () => {
  const h = harness('aba=pontuacao'); let tabs;
  const dummy = () => null;
  const page = load('../src/pages/admin/AdminChampionshipPage.tsx', { react: h.react, 'react-router': h.router, '@tanstack/react-query': h.queries,
    '../../api/endpoints': { adminApi: h.api }, '../../lib/format': format, '../../components/States': states,
    '../../components/Tabs': { Tabs: p => { tabs = p; return null; } }, '../../components/CopyButton': { CopyButton: dummy },
    '../../components/StatusBadge': { ChampionshipBadge: dummy }, '../../components/LeaderboardView': { LeaderboardView: dummy },
    '../../components/Dialog': { Dialog: dummy }, './ChampionshipForm': { ChampionshipForm: dummy }, './CategoriesTab': { CategoriesTab: dummy },
    './WorkoutsTab': { WorkoutsTab: dummy }, './TeamsTab': { TeamsTab: dummy }, './HeatsTab': { HeatsTab: dummy },
    './ScoresTab': { ScoresTab: () => React.createElement('p', {}, 'Painel de resultados') }, './HeatSettingsPanel': { HeatSettingsPanel: dummy } });
  const html = renderToStaticMarkup(h.render(page.default, {}));
  assert.match(html, /Painel de resultados/);
  const labels = tabs.items.map(t => t.label); assert.equal(labels[labels.indexOf('Equipes') + 1], 'Resultados');
  assert.equal(tabs.active, 'resultados');
});

test('next heat action starts the selected heat and renders its running clock', async () => {
  const h = harness(); const { HeatsTab } = heatsModule(h); const props = { championship: { ...championship, status: 'IN_PROGRESS' } };
  const tree = h.render(HeatsTab, props); const next = nodes(tree).find(n => n.props.className === 'next-heat');
  const start = nodes(next).find(n => n.type === 'button' && text(n).includes('Iniciar bateria'));
  assert.equal(start.props.disabled, false); await start.props.onClick();
  assert.equal(h.calls[0].id, 1); assert.equal(h.calls[0].kind, 'start');
  assert.ok(h.invalidations.some(key => key[0] === 'admin-heats'));
  const html = renderToStaticMarkup(h.render(HeatsTab, props));
  assert.match(html, /Cronômetro: 2026-10-14T16:00:00Z/);
});

test('next heat waits for completion and Concluir e Lançar opens the current heat in Resultados', async () => {
  const h = harness(); h.heats[0].status = 'IN_PROGRESS'; h.heats[0].startedAt = '2026-10-14T16:00:00Z';
  const { HeatsTab } = heatsModule(h); const props = { championship: { ...championship, status: 'IN_PROGRESS' } };
  let tree = h.render(HeatsTab, props); let next = nodes(tree).find(n => n.props.className === 'next-heat');
  assert.equal(nodes(next).find(n => n.type === 'button').props.disabled, true);
  await nodes(tree).find(n => n.type === 'button' && text(n) === 'Concluir e Lançar').props.onClick();
  assert.equal(h.params().get('aba'), 'resultados'); assert.equal(h.params().get('bateria'), '1');
  tree = h.render(HeatsTab, props); next = nodes(tree).find(n => n.props.className === 'next-heat');
  assert.equal(nodes(next).find(n => n.type === 'button').props.disabled, false);
});

test('Nova Prova opens a dialog from the filter actions and no longer renders a bottom panel', () => {
  const h = harness(); const { WorkoutsTab } = workoutsModule(h);
  let tree=h.render(WorkoutsTab,{ championship });
  const actions=tree.props.children[0].props.filterActions;
  assert.equal(text(actions),'Nova Prova');
  assert.doesNotMatch(renderToStaticMarkup(tree),/new-workout/);
  actions.props.onClick();
  tree=h.render(WorkoutsTab,{ championship });
  assert.equal(tree.props.children[1].props.title,'Nova Prova');
});

test('heat settings are first and disappear once started, cancelled or finished', () => {
  for(const status of ['PUBLISHED','IN_PROGRESS','CANCELLED','FINISHED']) {
    const h=harness(); const { HeatsTab }=heatsModule(h);
    const tree=h.render(HeatsTab,{championship:{...championship,status}});
    assert.equal(Boolean(tree.props.children[0]),status==='PUBLISHED');
    assert.equal(renderToStaticMarkup(tree).includes('Configurações das baterias'),status==='PUBLISHED');
  }
});

test('movement checkbox disables loads, preserves their values and persists the availability flag', () => {
  const h=harness(); let saved;
  const { CategoriesTab }=categoriesModule(h,{createCategory:async(id,input)=>{saved=input;}});
  const props={championship:{...championship,categories:[]}};
  let tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.props.id==='cat-gender').props.onChange({target:{value:'MALE'}});
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.type==='button' && text(n).includes('Adicionar movimento')).props.onClick();
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.type==='input' && n.props.placeholder==='Ex.: Clean and jerk').props.onChange({target:{value:'Snatch'}});
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.type==='input' && n.props.placeholder==='Ex.: 30 kg').props.onChange({target:{value:'45 kg'}});
  tree=h.render(CategoriesTab,props);
  const checkbox = nodes(tree).find(n=>n.props['aria-label']==='Incluir Snatch nas provas');
  assert.equal(checkbox.props.checked, false);
  assert.doesNotMatch(renderToStaticMarkup(tree), />Pode ser pedido</);
  checkbox.props.onChange({target:{checked:true}});
  tree=h.render(CategoriesTab,props);
  assert.equal(nodes(tree).find(n=>n.type==='input' && n.props.placeholder==='Ex.: 30 kg').props.disabled,false);
  nodes(tree).find(n=>n.props['aria-label']==='Incluir Snatch nas provas').props.onChange({target:{checked:false}});
  tree=h.render(CategoriesTab,props);
  const male=nodes(tree).find(n=>n.type==='input' && n.props.placeholder==='Ex.: 30 kg');
  assert.equal(male.props.disabled,true); assert.equal(male.props.value,'45 kg');
  nodes(tree).find(n=>n.props.id==='cat-name').props.onChange({target:{value:'RX',selectionStart:null,selectionEnd:null}});
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.props.id==='cat-price').props.onChange({target:{value:'0'}});
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.props.id==='cat-gender').props.onChange({target:{value:'MALE'}});
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.type==='form').props.onSubmit({preventDefault(){}});
  assert.equal(saved.movementStandards[0].allowed,false); assert.equal(saved.movementStandards[0].male,'45 kg');
  assert.equal(saved.movementStandards[0].kind,'LOAD');
});

test('movement type hides all load fields and saves checkbox availability without symbols', () => {
  for (const genderPolicy of ['MALE', 'FEMALE', 'MIXED']) for (const allowed of [false, true]) {
    const h=harness(); let saved;
    const { CategoriesTab }=categoriesModule(h,{updateCategory:async(id,input)=>{saved=input;}});
    const category={...championship.categories[0], teamSize:2, price:0, genderPolicy, allowedMaleCounts:[1],
      movementStandards:[{movement:'Toes to bar',male:'✓',female:'✓'}]};
    const props={championship:{...championship,categories:[category]}};
    let tree=h.render(CategoriesTab,props);
    nodes(tree).find(n=>n.type==='button' && text(n).trim()==='Editar').props.onClick();
    tree=h.render(CategoriesTab,props);
    let row=nodes(tree).find(n=>n.props.className==='movement-editor__row');
    assert.equal(nodes(row).find(n=>n.type==='select').props.value,'MOVEMENT');
    assert.equal(nodes(row).filter(n=>n.type==='input' && n.props.type!=='checkbox').length,1);
    assert.doesNotMatch(renderToStaticMarkup(row),/✓|✕|Pode ser pedido/);
    nodes(row).find(n=>n.type==='input' && n.props.type==='checkbox').props.onChange({target:{checked:allowed}});
    tree=h.render(CategoriesTab,props);
    nodes(tree).find(n=>n.type==='form').props.onSubmit({preventDefault(){}});
    assert.equal(saved.movementStandards[0].kind,'MOVEMENT');
    assert.equal(saved.movementStandards[0].allowed,allowed);
    assert.equal(saved.movementStandards[0].male,'—'); assert.equal(saved.movementStandards[0].female,'—');
  }
});

test('organizer switches between load and movement without losing unsaved loads', () => {
  const h=harness(); const { CategoriesTab }=categoriesModule(h);
  const category={...championship.categories[0],teamSize:2,price:0,genderPolicy:'MIXED',allowedMaleCounts:[1],
    movementStandards:[{movement:'Clean and jerk',male:'45 kg',female:'25 kg',allowed:true,kind:'LOAD'}]};
  const props={championship:{...championship,categories:[category]}};
  let tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.type==='button' && text(n).trim()==='Editar').props.onClick();
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.props['aria-label']==='Tipo do item 1').props.onChange({target:{value:'MOVEMENT'}});
  tree=h.render(CategoriesTab,props);
  assert.equal(nodes(tree).filter(n=>n.type==='input' && n.props.placeholder==='Ex.: 30 kg').length,0);
  nodes(tree).find(n=>n.props['aria-label']==='Tipo do item 1').props.onChange({target:{value:'LOAD'}});
  tree=h.render(CategoriesTab,props);
  assert.equal(nodes(tree).find(n=>n.type==='input' && n.props.placeholder==='Ex.: 30 kg').props.value,'45 kg');
  assert.equal(nodes(tree).find(n=>n.type==='input' && n.props.placeholder==='Ex.: 15 kg').props.value,'25 kg');
});

test('load fields reject availability symbols with a checkbox instruction', () => {
  const h=harness(); let saves=0;
  const { CategoriesTab }=categoriesModule(h,{updateCategory:async()=>{saves++;}});
  const category={...championship.categories[0],teamSize:2,price:0,genderPolicy:'MALE',
    movementStandards:[{movement:'Snatch',male:'45 kg',female:'25 kg',allowed:true,kind:'LOAD'}]};
  const props={championship:{...championship,categories:[category]}};
  let tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.type==='button' && text(n).trim()==='Editar').props.onClick();
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.type==='input' && n.props.placeholder==='Ex.: 30 kg').props.onChange({target:{value:'✓'}});
  tree=h.render(CategoriesTab,props);
  nodes(tree).find(n=>n.type==='form').props.onSubmit({preventDefault(){}});
  tree=h.render(CategoriesTab,props);
  assert.equal(saves,0); assert.match(renderToStaticMarkup(tree),/Use a caixa de seleção/);
});

test('creating and editing categories show only the applicable loads and save the correct gender value', () => {
  for (const genderPolicy of ['MALE', 'FEMALE', 'MIXED']) for (const editing of [false, true]) {
    const h = harness(); let saved;
    const { CategoriesTab } = categoriesModule(h, { updateCategory: async (id, input) => { saved = input; } });
    const category = { ...championship.categories[0], teamSize: 2, price: 0, genderPolicy, allowedMaleCounts: [1],
      movementStandards: [{ movement: 'Snatch', male: '45 kg', female: '25 kg', allowed: true }] };
    const props = { championship: { ...championship, categories: [category] } };
    let tree = h.render(CategoriesTab, props);
    if (editing) nodes(tree).find(n => n.type === 'button' && text(n).trim() === 'Editar').props.onClick();
    else {
      nodes(tree).find(n => n.props.id === 'cat-gender').props.onChange({ target: { value: genderPolicy } });
      tree = h.render(CategoriesTab, props);
      nodes(tree).find(n => n.type === 'button' && text(n).includes('Adicionar movimento')).props.onClick();
    }
    tree = h.render(CategoriesTab, props);
    const row = nodes(tree).find(n => n.props.className === 'movement-editor__row');
    const inputs = nodes(row).filter(n => n.type === 'input' && n.props.type !== 'checkbox');
    assert.equal(inputs.length, genderPolicy === 'MIXED' ? 3 : 2);
    if (genderPolicy === 'MIXED') {
      assert.match(text(row), /Carga masculina/); assert.match(text(row), /Carga feminina/);
    } else {
      assert.match(text(row), /Carga/); assert.doesNotMatch(text(row), /[Mm]asculin|[Ff]eminin/);
    }
    if (editing) {
      assert.equal(inputs[1].props.value, genderPolicy === 'FEMALE' ? '25 kg' : '45 kg');
      inputs[1].props.onChange({ target: { value: '32 kg' } });
      tree = h.render(CategoriesTab, props);
      nodes(tree).find(n => n.type === 'form').props.onSubmit({ preventDefault() {} });
      const stored = saved.movementStandards[0];
      assert.equal(stored[genderPolicy === 'FEMALE' ? 'female' : 'male'], '32 kg');
      assert.equal(stored[genderPolicy === 'FEMALE' ? 'male' : 'female'], genderPolicy === 'FEMALE' ? '45 kg' : '25 kg');
    }
  }
});

test('leaderboard uses a category select and retains the athlete or team search on category changes', () => {
  const h=harness(); const queries={...h.queries,useQuery:()=>({isLoading:false})};
  const { LeaderboardView }=load('../src/components/LeaderboardView.tsx',{
    react:h.react,'@tanstack/react-query':queries,'../api/endpoints':{publicApi:{}},'../lib/format':format,
    '../lib/leaderboardSearch':load('../src/lib/leaderboardSearch.ts'),'./Field':fields,'./States':states,
  });
  const props={slug:'arena',categories:championship.categories};
  let tree=h.render(LeaderboardView,props);
  const controls=nodes(tree); assert.equal(controls.filter(n=>n.type==='select').length,1);
  controls.find(n=>n.type==='input').props.onChange({target:{value:'João'}});
  tree=h.render(LeaderboardView,props);
  nodes(tree).find(n=>n.type==='select').props.onChange({target:{value:'2'}});
  tree=h.render(LeaderboardView,props);
  assert.equal(nodes(tree).find(n=>n.type==='select').props.value,2);
  assert.equal(nodes(tree).find(n=>n.type==='input').props.value,'João');
});
