import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

const nativeRequire = createRequire(import.meta.url);
function load(path, imports = {}, context = {}) {
  const exports = {};
  const code = ts.transpileModule(readFileSync(new URL(path, import.meta.url), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  runInNewContext(code, { exports, URL, ...context, require: id => {
    if (id === 'react/jsx-runtime') return nativeRequire(id);
    if (id in imports) return imports[id];
    throw new Error(id);
  } });
  return exports;
}
const format = load('../src/lib/format.ts');
const navigation = load('../src/lib/navigation.ts');
const search = load('../src/lib/teamSearch.ts');
const composition = load('../src/lib/categoryComposition.ts');
const user = { id: 1, name: 'ANA SOUZA', email: 'ana@test.invalid', role: 'ATHLETE', gender: 'F',
  document: '52998224725', phone: '11999990000', googleLinked: true, hasPassword: false };
const states = { Loading: () => null, ErrorState: () => null, InlineError: () => null,
  Empty: p => React.createElement('p', {}, p.title, p.children) };
function nodes(element) {
  if (Array.isArray(element)) return element.flatMap(nodes);
  if (!element?.props) return [];
  if (typeof element.type === 'function') return nodes(element.type(element.props));
  return [element, ...nodes(element.props.children)];
}
function hookState() {
  let index = 0; const values = [];
  return { reset: () => { index = 0; }, values, react: { ...React,
    useMemo: fn => fn(), useEffect: fn => fn(),
    useState: initial => { const key = index++; if (!(key in values)) values[key] = typeof initial === 'function' ? initial() : initial;
      return [values[key], next => { values[key] = typeof next === 'function' ? next(values[key]) : next; }]; },
  } };
}

test('phone and document validators accept formatting and reject missing data or wrong check digits', () => {
  for (const phone of ['', ' ', '99999-0000', 'telefone']) assert.equal(format.isValidPhone(phone), false);
  assert.equal(format.isValidPhone('+55 (11) 99999-0000'), true);
  assert.equal(format.isValidCpf('529.982.247-25'), true);
  assert.equal(format.isValidCpf('52998224724'), false);
  assert.equal(format.isValidCpf('529A98224725'), false);
  assert.equal(format.isValidCnpj('11.222.333/0001-81'), true);
  assert.equal(format.isValidCnpj('00.000.000/E08G-12'), true);
  assert.equal(format.isValidCnpj('11222333000182'), false);
  assert.equal(format.maskDocument('11222333000181'), '11.222.333/0001-81');
  assert.equal(format.maskDocument('00000000e08g12'), '00.000.000/E08G-12');
});

test('incomplete accounts complete the profile before returning to the selected event', () => {
  const next = '/c/evento/inscricao?categoria=1';
  for (const patch of [{ phone: null }, { document: null }, { gender: null }])
    assert.equal(navigation.profileReturnPath({ ...user, ...patch }, next), `/minha-conta?next=${encodeURIComponent(next)}`);
  assert.equal(navigation.profileReturnPath(user, next), next);
  assert.equal(navigation.profileReturnPath({ ...user, role: 'ORGANIZER', phone: null, gender: null }, null), '/organizador');
  assert.equal(navigation.profileReturnPath(user, '//attacker.invalid'), '/minhas-equipes');
  assert.equal(navigation.profileReturnPath({ ...user, document: null }, '/minha-conta'), '/minha-conta');
});

test('team search matches team or captain names without accents and restores every team when cleared', () => {
  const teams = [{ teamName: 'FORÇA TOTAL', captainName: 'João Silva' }, { teamName: 'ARENA', captainName: 'Ana Souza' }];
  for (const term of ['forca', 'JOAO', '  silva joão  ']) assert.equal(search.searchTeams(teams, term)[0], teams[0]);
  assert.equal(search.searchTeams(teams, 'ana')[0], teams[1]);
  assert.equal(search.searchTeams(teams, 'ausente').length, 0);
  assert.equal(search.searchTeams(teams, ' '), teams);
});

function profileHarness(account) {
  const hooks = hookState(); let saved; let destination;
  const page = load('../src/pages/MyProfilePage.tsx', {
    react: hooks.react, 'react-router': { useNavigate: () => to => { destination = to; },
      useSearchParams: () => [new URLSearchParams('next=%2Fc%2Fevento%2Finscricao')] },
    '@tanstack/react-query': { useQuery: () => ({}) },
    '../auth/AuthContext': { useAuth: () => ({ user: account, updateProfile: async input => { saved = input; return { ...account, ...input }; } }) },
    '../api/endpoints': { publicApi: {}, authApi: {} }, '../lib/format': format, '../lib/navigation': navigation,
    '../components/Field': { Field: p => React.createElement('label', {}, p.label, p.children) },
    '../components/States': states, '../components/GoogleButton': { GoogleButton: () => null },
    '../components/PayoutAccountSection': { PayoutAccountSection: () => null },
  }).default;
  return { render: () => { hooks.reset(); return nodes(page()); }, saved: () => saved, destination: () => destination };
}

test('athlete profile requires CPF and phone, saves them and resumes the original registration', async () => {
  const app = profileHarness({ ...user, phone: null, document: null });
  let tree = app.render();
  assert.equal(tree.find(n => n.props.id === 'acc-phone').props.required, true);
  assert.equal(tree.find(n => n.props.id === 'acc-document').props.required, true);
  assert.equal(tree.find(n => n.props.id === 'acc-gender').props.required, true);
  await tree.find(n => n.type === 'form').props.onSubmit({ preventDefault() {} }); assert.equal(app.saved(), undefined);
  tree.find(n => n.props.id === 'acc-phone').props.onChange({ target: { value: '(11) 99999-0000' } });
  tree.find(n => n.props.id === 'acc-document').props.onChange({ target: { value: '52998224725' } });
  tree = app.render(); await tree.find(n => n.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(app.saved().document, '52998224725'); assert.equal(app.saved().phone, '(11) 99999-0000');
  assert.equal(app.destination(), '/c/evento/inscricao');
});

test('organizer profile requires CPF/CNPJ while the separate address fields remain optional', async () => {
  const app = profileHarness({ ...user, role: 'ORGANIZER', phone: null, gender: null, document: '11222333000181' });
  const tree = app.render();
  assert.equal(tree.find(n => n.props.id === 'acc-gender'), undefined);
  assert.equal(tree.find(n => n.props.id === 'acc-document').props.required, true);
  assert.equal(tree.find(n => n.props.id === 'acc-phone').props.required, false);
  for (const id of ['acc-street', 'acc-number', 'acc-city', 'acc-cep']) assert.ok(!tree.find(n => n.props.id === id).props.required);
  await tree.find(n => n.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(app.saved().document, '11222333000181'); assert.equal(app.saved().street, '');
  assert.equal(app.saved().gender, undefined);
});

test('registration prefills captain data and requires phone and an explicit shirt size for every athlete', () => {
  const hooks = hookState(); let submissions = 0;
  const championship = { id: 4, slug: 'evento', name: 'EVENTO', registrationOpen: true,
    categories: [{ id: 3, name: 'FEMININO', teamSize: 2, genderPolicy: 'FEMALE', price: 0 }] };
  const page = load('../src/pages/TeamRegistrationPage.tsx', {
    react: hooks.react, 'react-router': { Link: () => null, useNavigate: () => () => {}, useParams: () => ({ slug: 'evento' }) },
    '@tanstack/react-query': { useQuery: options => ({ data: options.queryKey[0] === 'championship' ? championship : [] }),
      useMutation: () => ({ mutate: () => { submissions++; } }), useQueryClient: () => ({}) },
    '../auth/AuthContext': { useAuth: () => ({ user }) }, '../api/endpoints': { athleteApi: {}, publicApi: {} },
    '../lib/format': format, '../lib/categoryComposition': composition, '../lib/teamSchedule': { existingRegistration: () => null },
    '../components/Field': { Field: p => React.createElement('label', {}, p.children) },
    '../components/Dialog': { Dialog: () => null }, '../components/Plate': { PlateChip: () => null }, '../components/States': states,
  }).default;
  const render = () => { hooks.reset(); return nodes(page()); };
  let tree = render(); tree.find(n => n.type === 'input' && n.props.type === 'radio').props.onChange();
  render(); tree = render();
  assert.equal(tree.find(n => n.props.id === 'm0-cpf').props.value, '529.982.247-25');
  assert.equal(tree.find(n => n.props.id === 'm0-phone').props.value, user.phone);
  assert.equal(tree.find(n => n.props.id === 'm1-phone').props.required, true);
  tree.find(n => n.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(submissions, 0); assert.match(hooks.values[3]['m1.phone'], /telefone válido/);
  for (const id of ['m0-size', 'm1-size']) {
    const select = tree.find(n => n.props.id === id);
    assert.equal(select.props.required, true); assert.equal(select.props.value, '');
    assert.equal(nodes(select).find(n => n.type === 'option' && n.props.value === '').props.children, 'Selecione o tamanho');
  }
  for (const [id, value] of [['team-name', 'Equipe'], ['m0-birth', '1990-01-01'], ['m1-name', 'MARIA LIMA'],
    ['m1-email', 'maria@test.invalid'], ['m1-phone', '21999990000'], ['m1-cpf', '11144477735'], ['m1-birth', '1991-01-01']])
    tree.find(n => n.props.id === id).props.onChange({ target: { value } });
  tree = render(); tree.find(n => n.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(submissions, 0);
  assert.deepEqual(Object.keys(hooks.values[3]), ['m0.shirtSize', 'm1.shirtSize']);
  tree.find(n => n.props.id === 'm0-size').props.onChange({ target: { value: 'M' } });
  tree = render(); tree.find(n => n.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(submissions, 0); assert.match(hooks.values[3]['m1.shirtSize'], /tamanho da camiseta/);
  tree.find(n => n.props.id === 'm1-size').props.onChange({ target: { value: 'P' } });
  tree = render(); tree.find(n => n.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(submissions, 1);
});

test('organizer teams display every athlete shirt size and replace the category selector with search', () => {
  const hooks = hookState();
  const team = { teamId: 1, teamName: 'FORÇA TOTAL', captainName: 'João Silva', captainEmail: 'joao@test.invalid',
    createdAt: '2026-10-13T12:00:00Z', registrationCode: 'REG', categoryId: 3, categoryName: 'RX', status: 'CONFIRMED', memberCount: 2,
    members: [{ id: 1, fullName: 'JOÃO SILVA', shirtSize: 'G', captain: true }, { id: 2, fullName: 'MARIA LIMA', shirtSize: null }] };
  const { TeamsTab } = load('../src/pages/admin/TeamsTab.tsx', {
    react: hooks.react, '@tanstack/react-query': { useQuery: () => ({ data: [team] }), useMutation: () => ({}), useQueryClient: () => ({}) },
    '../../api/endpoints': {}, '../../lib/format': format, '../../lib/teamSearch': search,
    '../../components/Plate': { PlateChip: p => React.createElement('span', {}, p.name) },
    '../../components/CopyButton': { CopyButton: () => null }, '../../components/StatusBadge': { TeamBadge: () => null, PaymentBadge: () => null },
    '../../components/States': states, 'lucide-react': { Search: () => null, Shirt: () => null },
  }, { window: { location: { origin: 'https://wodz.invalid' } } });
  const render = () => { hooks.reset(); return TeamsTab({ championship: { id: 4, categories: [{ id: 3 }] } }); };
  const tree = render(); const html = renderToStaticMarkup(tree);
  assert.match(html, /JOÃO SILVA/); assert.match(html, /MARIA LIMA/); assert.match(html, /Camisa: <\/span>G/);
  assert.match(html, /Não informado/); assert.match(html, /type="search"/); assert.doesNotMatch(html, /<table|CategoriaPicker/);
  nodes(tree).find(n => n.props.id === 'team-search').props.onChange({ target: { value: 'ausente' } });
  assert.match(renderToStaticMarkup(render()), /Nenhuma equipe encontrada/);
});
