import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';
const require = createRequire(import.meta.url);
const source = file => ts.transpileModule(readFileSync(new URL(file, import.meta.url), 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText;
function pure(file) { const exports = {}; runInNewContext(source(file), { exports, URLSearchParams }); return exports; }
const scheduleHelpers = pure('../src/lib/teamSchedule.ts');
const filters = pure('../src/lib/championshipFilters.ts');
const format = pure('../src/lib/format.ts');
const team = { id: 2, captainId: 10, name: 'EQUIPE DA ANA', status: 'CONFIRMED', registrationCode: 'REG2',
  category: { id: 3, name: 'RX', price: 150 }, championship: { id: 4, name: 'ARENA', slug: 'arena' }, members: [] };
const heat = { heatId: 7, heatNumber: 2, scheduledAt: '2026-11-14T03:15:00Z', championshipId: 4,
  categoryId: 3, workoutId: 9, workoutNumber: 1, workoutName: 'PROVA DA ARENA', workoutType: 'FOR_TIME',
  status: 'UPCOMING', intervalMinutes: 5, lanes: [{ teamId: 2, teamName: team.name, lane: 3 }] };
test('each team only sees its own heats in chronological order', () => {
  const late = { ...heat, heatId: 8, scheduledAt: '2026-11-14T12:00:00Z' };
  const result = scheduleHelpers.teamSchedule([late, { ...heat, heatId: 11, championshipId: 99 },
    { ...heat, heatId: 12, categoryId: 99 }, { ...heat, heatId: 13, lanes: [{ teamId: 99 }] }, heat], team);
  assert.deepEqual(Array.from(result, item => item.heatId), [7, 8]);
});
test('pending and confirmed registrations block another signup; cancelled registrations do not', () => {
  for (const status of ['PENDING_PAYMENT', 'CONFIRMED']) {
    assert.equal(scheduleHelpers.existingRegistration([{ ...team, status }], 4)?.id, 2);
  }
  assert.equal(scheduleHelpers.existingRegistration([{ ...team, status: 'CANCELLED' }], 4), undefined);
  assert.equal(scheduleHelpers.existingRegistration([team], 99), undefined);
});
test('filters normalize unknown URL values and expose all requested situations', () => {
  assert.equal(filters.championshipFilter('OTHER'), 'ALL'); assert.equal(filters.championshipFilter(null), 'ALL');
  assert.deepEqual(Array.from(filters.CHAMPIONSHIP_FILTERS, item => item.id), ['ALL', 'IN_PROGRESS', 'OPEN', 'FINISHED']);
});
test('event times use Brasilia even across midnight UTC', () => {
  assert.equal(format.clockTime('2026-11-14T01:15:00Z'), '22:15');
  assert.match(format.dayLabel('2026-11-14T01:15:00Z'), /13\/11/);
});
function harness({ selectedTeam = null, status = 'CONFIRMED', data = [heat], teamsError,
  confirmCancellation = true, cancelPending = false, cancelError, userId = 10 } = {}) {
  let selectedHeat = null; let confirming = false; const queries = []; let nextParams; let focus; let closed = false;
  let currentTeam = { ...team, status }; const exports = {};
  let cachedTeams = [currentTeam];
  let mutation; let cancellationRequests = 0; let requestedTeam; const invalidations = []; const confirmations = [];
  let booleanHook = 0;
  runInNewContext(source('../src/pages/MyTeamsPage.tsx'), { exports, URLSearchParams,
    window: { confirm: message => { confirmations.push(message); return confirmCancellation; } },
    requestAnimationFrame: callback => callback(), document: { getElementById: id => ({ focus: () => { focus = id; } }) },
    require: id => {
      if (id === 'react') return { ...React, useState: initial => typeof initial === 'boolean'
        ? (++booleanHook === 1 ? [false, () => {}] : [confirming, next => { confirming = next; }])
        : typeof initial === 'string' ? [initial, () => {}] : [selectedHeat, next => { selectedHeat = next; }], useRef: () => ({ current: null }), useEffect: () => {} };
      if (id === '../auth/AuthContext') return { useAuth: () => ({ user: { id: userId } }) };
      if (id === 'react/jsx-runtime') return require(id);
      if (id === 'react-router') return { Link: props => React.createElement('a', { href: props.to }, props.children),
        useSearchParams: () => [new URLSearchParams(selectedTeam ? `equipe=${selectedTeam}` : ''), value => { nextParams = value; }] };
      if (id === '@tanstack/react-query') return { useQuery: options => { queries.push(options);
        if (options.queryKey[0] === 'my-teams') return { data: cachedTeams, error: teamsError };
        if (options.queryKey[0] === 'my-heats') return { data: { heats: data }, isPending: false };
        return { data: { categories: [{ id: 3, workouts: [{ id: 9, type: 'FOR_TIME', timeCapSeconds: 600,
          description: '40 cal row\n30 thrusters', hasTiebreak: true }] }] }, isPending: false };
      }, useMutation: options => { mutation = options; return { mutate: () => { cancellationRequests++; }, isPending: cancelPending, error: cancelError }; },
        useQueryClient: () => ({
          setQueryData: (key, update) => { cachedTeams = update(cachedTeams); },
          invalidateQueries: options => { invalidations.push(Array.from(options.queryKey)); },
        }) };
      if (id === '../api/endpoints') return { athleteApi: { cancelRegistration: id => { requestedTeam = id; return { ...currentTeam, status: 'CANCELLED' }; } }, publicApi: {} };
      if (id === '../lib/format') return format;
      if (id === '../lib/teamSchedule') return scheduleHelpers;
      if (id === '../lib/categoryComposition') return pure('../src/lib/categoryComposition.ts');
      if (id === '../components/Dialog') return { Dialog: props => React.createElement('aside', { role: 'dialog' }, props.title, props.subtitle, props.children) };
      if (id === '../components/StatusBadge') return { TeamBadge: props => React.createElement('span', {}, props.status), HeatBadge: props => React.createElement('span', {}, props.status) };
      if (id === '../components/HeatTiming') return { HeatTiming: () => null };
      if (id === '../components/States') return { Loading: () => null, ErrorState: () => null, InlineError: props => props.error ? React.createElement('p', { role: 'alert' }, props.error.message) : null, Empty: props => React.createElement('div', {}, props.title, props.children) };
      if (id === 'lucide-react') return { ArrowLeft: () => null, ChevronRight: () => null };
      throw new Error(id);
    },
  });
  return { page: () => exports.default(), details: () => { booleanHook = 0; return exports.TeamDetails({ team: currentTeam, onClose: () => { closed = true; } }); },
    queries, params: () => nextParams, focus: () => focus,
    mutation: () => mutation, cancellations: () => cancellationRequests, requestedTeam: () => requestedTeam,
    closed: () => closed, cachedTeams: () => cachedTeams, invalidations, confirmations };
}
function nodes(element) {
  if (Array.isArray(element)) return element.flatMap(nodes);
  if (!element?.props) return [];
  if (typeof element.type === 'function' && element.type.name === 'TeamHeat') return nodes(element.type(element.props));
  return [element, ...nodes(element.props.children)];
}
test('mini cards only contain championship, team and category and open the selected team', () => {
  const app = harness(); const tree = app.page(); const card = nodes(tree).find(node => node.props.className === 'team-mini-card');
  const html = renderToStaticMarkup(card);
  assert.equal(html.replace(/<[^>]*>/g, ''), 'ARENAEQUIPE DA ANARX'); assert.match(html, /aria-haspopup="dialog"/);
  assert.doesNotMatch(html, /Pagar|CONFIRMED|REG2|Bateria/); card.props.onClick(); assert.equal(app.params().get('equipe'), '2');
});
test('a heat opens its workout in the same popup and Back restores the schedule and focus', () => {
  const app = harness(); const before = app.details();
  assert.match(renderToStaticMarkup(before), /Horários das baterias/);
  nodes(before).find(node => node.props.id === 'team-heat-7').props.onClick();
  const detail = app.details(); const html = renderToStaticMarkup(detail);
  assert.match(html, /PROVA DA ARENA/); assert.match(html, /40 cal row/); assert.match(html, /Raia 3/); assert.match(html, /10:00/);
  assert.equal((html.match(/role="dialog"/g) ?? []).length, 1);
  nodes(detail).find(node => node.props.className === 'team-details__back').props.onClick();
  assert.match(renderToStaticMarkup(app.details()), /Horários das baterias/); assert.equal(app.focus(), 'team-heat-7');
  assert.ok(app.queries.filter(query => query.queryKey[0] !== 'my-teams').every(query => query.meta.activity === false));
});
test('pending payment stays discoverable inside the popup and never fetches a schedule', () => {
  const app = harness({ status: 'PENDING_PAYMENT' }); const html = renderToStaticMarkup(app.details());
  assert.match(html, /Continuar para pagamento/); assert.match(html, /href="\/pagamento\/REG2"/); assert.match(html, /Confirme sua inscrição/);
  assert.equal(app.queries.find(query => query.queryKey[0] === 'my-heats').enabled, false);
});

test('a linked teammate can see payment and schedules but cannot cancel or rename', () => {
  const html = renderToStaticMarkup(harness({ status: 'PENDING_PAYMENT', userId: 20 }).details());
  assert.match(html, /Continuar para pagamento/);
  assert.doesNotMatch(html, /Cancelar a inscrição|Alterar nome da equipe/);
  assert.match(renderToStaticMarkup(harness({ userId: 20 }).details()), /Horários das baterias/);
});

test('only pending registrations offer cancellation and declining confirmation does not send it', () => {
  for (const status of ['CONFIRMED', 'CANCELLED']) {
    assert.doesNotMatch(renderToStaticMarkup(harness({ status }).details()), /Cancelar a inscrição/);
  }
  const app = harness({ status: 'PENDING_PAYMENT', confirmCancellation: false });
  const button = nodes(app.details()).find(node => node.props.children === 'Cancelar a inscrição');
  assert.ok(button); button.props.onClick();
  assert.equal(app.cancellations(), 0);
  assert.match(renderToStaticMarkup(app.details()), /Cancelar inscrição\?.*EQUIPE DA ANA/);
  nodes(app.details()).find(node => node.props.children === 'Manter inscrição').props.onClick();
  assert.equal(app.cancellations(), 0); assert.match(renderToStaticMarkup(app.details()), /Continuar para pagamento/);
});

test('confirmed cancellation targets the selected team and immediately updates the popup and cached registration', async () => {
  const app = harness({ status: 'PENDING_PAYMENT' });
  nodes(app.details()).find(node => node.props.children === 'Cancelar a inscrição').props.onClick();
  assert.equal(app.cancellations(), 0);
  nodes(app.details()).find(node => node.props.children === 'Sim, cancelar inscrição').props.onClick();
  assert.equal(app.cancellations(), 1);
  const updated = await app.mutation().mutationFn(); assert.equal(app.requestedTeam(), 2);
  app.mutation().onSuccess(updated);
  assert.equal(app.closed(), true); assert.equal(app.cachedTeams().length, 0);
  assert.doesNotMatch(renderToStaticMarkup(app.page()), /EQUIPE DA ANA/);
  assert.ok(app.invalidations.some(key => key[0] === 'my-teams'));
  assert.ok(app.invalidations.some(key => key[0] === 'championship' && key[1] === 'arena'));
  assert.ok(app.invalidations.some(key => key[0] === 'registration' && key[1] === 'REG2'));
  assert.ok(app.invalidations.some(key => key[0] === 'checkout-status' && key[1] === 'REG2'));
});

test('cancellation disables repeated submission and errors keep the registration visible with a refreshed status', () => {
  const busy = harness({ status: 'PENDING_PAYMENT', cancelPending: true }); const tree = busy.details();
  assert.equal(nodes(tree).find(node => node.props.children === 'Cancelando…').props.disabled, true);
  assert.doesNotMatch(renderToStaticMarkup(tree), /Continuar para pagamento/);
  const failed = harness({ status: 'PENDING_PAYMENT', cancelError: new Error('A inscrição já foi confirmada') });
  assert.match(renderToStaticMarkup(failed.details()), /role="alert"[^>]*>A inscrição já foi confirmada/);
  failed.mutation().onError(); assert.deepEqual(failed.invalidations, [['my-teams']]);
  assert.match(renderToStaticMarkup(failed.details()), /Continuar para pagamento/);
});

test('the open popup refreshes registration status and keeps existing details during a network failure', () => {
  const closed = harness(); closed.page();
  assert.equal(closed.queries.find(query => query.queryKey[0] === 'my-teams').refetchInterval, false);
  const app = harness({ selectedTeam: 2, teamsError: new Error('Falha temporária') });
  const html = renderToStaticMarkup(app.page());
  assert.match(html, /role="dialog"/); assert.match(html, /Horários das baterias/);
  const query = app.queries.find(query => query.queryKey[0] === 'my-teams');
  assert.equal(query.staleTime, 0); assert.equal(query.refetchInterval, 20_000);
});

test('changing situation preserves city and state and passes the filter to the server', () => {
  let params = new URLSearchParams('q=Recife&uf=PE&status=OPEN'); let options; let requested;
  const exports = {};
  runInNewContext(source('../src/pages/HomePage.tsx'), { exports, URLSearchParams, require: id => {
    if (id === 'react') return { ...React, useState: initial => [initial, () => {}], useEffect: () => {} };
    if (id === 'react/jsx-runtime') return require(id);
    if (id === 'react-router') return { Link: () => null, useSearchParams: () => [params, next => { params = next; }] };
    if (id === '@tanstack/react-query') return { keepPreviousData: previous => previous, useQuery: query => { options = query; return { data: [] }; } };
    if (id === 'lucide-react') return { Search: () => null };
    if (id === '../api/endpoints') return { publicApi: { search: (...args) => { requested = args; } } };
    if (id === '../lib/format') return format;
    if (id === '../lib/theme') return { brandLogoFor: () => '/logo.svg' };
    if (id === '../lib/championshipFilters') return filters;
    if (id === '../components/EventImage') return { EventImage: () => null };
    if (id === '../auth/AuthContext') return { useAuth: () => ({ user: null, loading: false }) };
    if (id === '../components/States') return { Loading: () => null, ErrorState: () => null, Empty: () => null };
    throw new Error(id);
  } });
  const tree = exports.default(); options.queryFn(); assert.deepEqual(requested, ['Recife','PE','OPEN']);
  const buttons = nodes(tree).filter(node => node.props.className === 'champ-filter');
  assert.equal(buttons.find(node => node.props.children === 'Inscrições abertas').props['aria-pressed'], true);
  buttons.find(node => node.props.children === 'Encerrados').props.onClick();
  assert.equal(params.get('q'), 'Recife'); assert.equal(params.get('uf'), 'PE'); assert.equal(params.get('status'), 'FINISHED');
  const updated = exports.default(); nodes(updated).find(node => node.type === 'form').props.onSubmit({ preventDefault() {} });
  assert.equal(params.get('status'), 'FINISHED'); assert.equal(params.get('uf'), 'PE');
  assert.equal(options.meta.activity, false); assert.equal(options.placeholderData('cached'), 'cached');
});

test('an existing unpaid registration replaces the form with a clear warning and payment continuation', () => {
  const exports = {}; let registered = false;
  runInNewContext(source('../src/pages/TeamRegistrationPage.tsx'), { exports, URLSearchParams, require: id => {
    if (id === 'react') return { ...React, useState: initial => [initial, () => {}], useEffect: () => {} };
    if (id === 'react/jsx-runtime') return require(id);
    if (id === 'react-router') return { Link: props => React.createElement('a', { href: props.to }, props.children), useParams: () => ({ slug:'arena' }), useNavigate: () => () => {} };
    if (id === '@tanstack/react-query') return { useQuery: options => ({ data: options.queryKey[0] === 'my-teams' ? [{ ...team, status:'PENDING_PAYMENT' }] : { id:4, slug:'arena', name:'ARENA', categories:[] } }),
      useMutation: () => ({ mutate: () => { registered = true; } }), useQueryClient: () => ({}) };
    if (id === '../api/endpoints') return { athleteApi: {}, publicApi: {} };
    if (id === '../lib/format') return format;
    if (id === '../lib/teamSchedule') return scheduleHelpers;
      if (id === '../lib/categoryComposition') return pure('../src/lib/categoryComposition.ts');
    if (id === '../auth/AuthContext') return { useAuth: () => ({ user: { role:'ATHLETE' } }) };
    if (id === '../components/Field') return { Field: () => null };
    if (id === '../components/Dialog') return { Dialog: () => null };
    if (id === '../components/Plate') return { PlateChip: () => null };
    if (id === '../components/States') return { Loading: () => null, ErrorState: () => null, InlineError: () => null };
    throw new Error(id);
  } });
  const html = renderToStaticMarkup(React.createElement(exports.default));
  assert.match(html, /Você já possui uma inscrição para esse campeonato/); assert.match(html, /Continuar para pagamento/);
  assert.match(html, /href="\/pagamento\/REG2"/); assert.doesNotMatch(html, /<form/); assert.equal(registered, false);
});

test('signup offers the email link, declining opens the form and accepting opens the team', () => {
  for (const accept of [false, true]) {
    const exports = {}; const state = []; let hook = 0; let linkMutation; let requests = 0; let destination;
    const user = { id: 20, role: 'ATHLETE', gender: 'F', email: 'friend@test.invalid', document: '52998224725', phone: '11999990000' };
    const candidate = { teamId: 2, teamName: 'EQUIPE DA ANA', captainName: 'ANA' };
    runInNewContext(source('../src/pages/TeamRegistrationPage.tsx'), { exports, URLSearchParams, require: id => {
      if (id === 'react') return { ...React, useEffect: () => {}, useState: initial => {
        const index = hook++; if (!(index in state)) state[index] = initial;
        return [state[index], next => { state[index] = typeof next === 'function' ? next(state[index]) : next; }];
      } };
      if (id === 'react/jsx-runtime') return require(id);
      if (id === 'react-router') return { Link: props => React.createElement('a', { href: props.to }, props.children),
        useParams: () => ({ slug: 'arena' }), useNavigate: () => next => { destination = next; } };
      if (id === '@tanstack/react-query') return {
        useQuery: options => ({ data: options.queryKey[0] === 'team-links' ? [candidate]
          : options.queryKey[0] === 'my-teams' ? [] : { id: 4, name: 'ARENA', slug: 'arena', categories: [], registrationOpen: true } }),
        useMutation: options => { if (options.mutationFn.length === 1) linkMutation = options;
          return { mutate: id => { requests++; assert.equal(id, 2); }, reset: () => {}, isPending: false }; },
        useQueryClient: () => ({ setQueryData: () => {}, invalidateQueries: () => {} }),
      };
      if (id === '../api/endpoints') return { athleteApi: { linkMember: (slug, id) => { assert.equal(slug, 'arena'); assert.equal(id, 2); return team; } }, publicApi: {} };
      if (id === '../auth/AuthContext') return { useAuth: () => ({ user }) };
      if (id === '../lib/format') return format;
      if (id === '../lib/teamSchedule') return scheduleHelpers;
      if (id === '../lib/categoryComposition') return pure('../src/lib/categoryComposition.ts');
      if (id === '../components/Dialog') return { Dialog: props => React.createElement('aside', { role: 'dialog' }, props.children) };
      if (id === '../components/Field') return { Field: props => React.createElement('label', {}, props.children) };
      if (id === '../components/Plate') return { PlateChip: () => null };
      if (id === '../components/States') return { Loading: () => null, ErrorState: () => null, InlineError: () => null };
      throw new Error(id);
    } });
    const render = () => { hook = 0; return exports.default(); };
    const popup = render(); const html = renderToStaticMarkup(popup);
    assert.match(html, /Esse e-mail foi informado para a equipe/); assert.match(html, /EQUIPE DA ANA/); assert.match(html, /ANA/);
    nodes(popup).find(node => node.type === 'button' && node.props.children === (accept ? 'Sim' : 'Não')).props.onClick();
    if (accept) {
      assert.equal(requests, 1); linkMutation.onSuccess(linkMutation.mutationFn(2));
      assert.equal(destination, '/minhas-equipes?equipe=2');
    } else {
      assert.equal(requests, 0); assert.match(renderToStaticMarkup(render()), /<form/);
    }
  }
});
