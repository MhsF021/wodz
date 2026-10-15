import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import type { ChampionshipDetail, HeatStatus, ScoreEntry, ScoreInput, ScoreStatus, Workout } from '../../api/types';
import {
  capLabel,
  clockTime,
  dayLabel,
  formatDuration,
  ordinal,
  points,
  scoreStatusLabel,
  workoutTypeLabel,
} from '../../lib/format';
import { Field } from '../../components/Field';
import { HeatBadge } from '../../components/StatusBadge';
import { Empty, ErrorState, Loading } from '../../components/States';
import { errorMessage } from '../../api/client';
import { buildScoreInput } from '../../lib/scoreInput';

export function ScoresTab({ championship }: { championship: ChampionshipDetail }) {
  const [params, setParams] = useSearchParams();
  const categories = championship.categories;
  const requested = Number(params.get('prova')) || null;
  const category = categories.find(c => c.id === Number(params.get('categoria')))
    ?? categories.find(c => c.workouts.some(w => w.id === requested)) ?? categories[0];
  const workout = category?.workouts.find(w => w.id === requested) ?? category?.workouts[0];

  function filter(categoryId: number, workoutId?: number) {
    setParams(previous => {
      const next = new URLSearchParams(previous);
      next.set('aba', 'resultados'); next.set('categoria', String(categoryId));
      if (workoutId) next.set('prova', String(workoutId)); else next.delete('prova');
      next.delete('bateria');
      return next;
    }, { replace: true });
  }

  if (!categories.length) return <Empty title="Cadastre categorias e provas antes de lançar resultados" />;
  return <div className="stack results-manager">
    <div className="results-filters" role="group" aria-label="Filtrar resultados">
      <Field label="Categoria" htmlFor="results-category">
        <select id="results-category" className="input" value={category.id} onChange={e => {
          const selected = categories.find(c => c.id === Number(e.target.value))!;
          filter(selected.id, selected.workouts[0]?.id);
        }}>{categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select>
      </Field>
      <Field label="Prova" htmlFor="results-workout">
        <select id="results-workout" className="input" value={workout?.id ?? ''} disabled={!category.workouts.length}
          onChange={e => filter(category.id, Number(e.target.value))}>
          {!category.workouts.length && <option value="">Nenhuma prova cadastrada</option>}
          {category.workouts.map(w => <option key={w.id} value={w.id}>Prova {w.number}: {w.name}</option>)}
        </select>
      </Field>
    </div>
    {!category.workouts.length && <Empty title="Esta categoria ainda não tem provas" />}
    {workout && <ScoreSheet key={workout.id} workoutId={workout.id} championshipSlug={championship.slug}
      readOnly={championship.status === 'FINISHED'} />}
  </div>;
}

interface HeatGroup {
  heatId: number;
  heatNumber: number;
  status: HeatStatus;
  scheduledAt?: string | null;
  entries: ScoreEntry[];
}

function groupByHeat(entries: ScoreEntry[]): HeatGroup[] {
  const groups = new Map<number, HeatGroup>();
  for (const e of entries) {
    if (!e.heatId) continue;
    const group = groups.get(e.heatId) ?? {
      heatId: e.heatId,
      heatNumber: e.heatNumber ?? 0,
      status: e.heatStatus ?? 'UPCOMING',
      scheduledAt: e.heatScheduledAt,
      entries: [],
    };
    group.entries.push(e);
    groups.set(e.heatId, group);
  }
  return [...groups.values()].sort((a, b) => a.heatNumber - b.heatNumber);
}

function ScoreSheet({ workoutId, championshipSlug, readOnly }: { workoutId: number; championshipSlug: string; readOnly: boolean }) {
  const [params, setParams] = useSearchParams();
  const query = useQuery({ queryKey: ['admin-scores', workoutId], queryFn: () => adminApi.scores(workoutId) });
  if (query.isLoading) return <Loading />;
  if (query.error || !query.data) return <ErrorState error={query.error} />;
  const { workout, entries } = query.data;
  const groups = groupByHeat(entries);
  const selectedHeat = groups.some(g => g.heatId === Number(params.get('bateria'))) ? params.get('bateria')! : '';
  const withoutHeat = entries.filter((e) => !e.heatId);
  const judgesListId = `judges-${workout.id}`;
  const judges = [...new Set(entries.map((e) => e.judgeName).filter((j): j is string => Boolean(j)))];

  return (
    <section aria-labelledby="sheet-title" className="stack">
      <div className="results-sheet__header">
        <h2 id="sheet-title" className="section-title">
          Prova {workout.number}: {workout.name}
          <span className="section-title__sub">{workoutTypeLabel[workout.type]}{capLabel(workout.timeCapSeconds) ? ` · Cap ${capLabel(workout.timeCapSeconds)}` : ''}</span>
        </h2>
        {groups.length > 0 && <Field label="Bateria" htmlFor="score-heat-filter">
          <select id="score-heat-filter" className="input" value={selectedHeat} onChange={e => {
            const value = e.target.value;
            setParams(previous => {
              const next = new URLSearchParams(previous);
              next.set('aba', 'resultados'); next.set('categoria', String(workout.categoryId)); next.set('prova', String(workout.id));
              if (value) next.set('bateria', value); else next.delete('bateria');
              return next;
            }, { replace: true });
          }}>
            <option value="">Todas as baterias</option>{groups.map(g => <option key={g.heatId} value={g.heatId}>Bateria {g.heatNumber}</option>)}
          </select>
        </Field>}
      </div>
      <datalist id={judgesListId}>
        {judges.map((j) => <option key={j} value={j} />)}
      </datalist>
      {entries.length === 0 ? (
        <Empty title="Nenhuma equipe confirmada nesta categoria" />
      ) : groups.length === 0 ? (
        <Empty title="As baterias desta prova ainda não foram montadas">
          <p>Gere as baterias na aba Baterias para lançar os resultados.</p>
        </Empty>
      ) : (
        groups.filter(g => !selectedHeat || g.heatId === Number(selectedHeat)).map((group) => (
          <HeatSheet key={group.heatId} group={group} workout={workout} championshipSlug={championshipSlug}
            judgesListId={judgesListId} readOnly={readOnly} />
        ))
      )}
      {groups.length > 0 && withoutHeat.length > 0 && (
        <p className="note">
          Sem bateria nesta prova: {withoutHeat.map((e) => e.teamName).join(', ')}. Inclua essas equipes numa
          bateria para lançar o resultado.
        </p>
      )}
    </section>
  );
}

function HeatSheet({
  group,
  workout,
  championshipSlug,
  judgesListId,
  readOnly,
}: {
  group: HeatGroup;
  workout: Workout;
  championshipSlug: string;
  judgesListId: string;
  readOnly: boolean;
}) {
  const queryClient = useQueryClient();
  const builders = useRef(new Map<number, () => ScoreInput | null>());
  const [batchError, setBatchError] = useState<string | null>(null);
  const batch = useMutation({ mutationFn: (entries: { teamId: number; result: ScoreInput }[]) => adminApi.saveHeatScores(workout.id, group.heatId, entries),
    onSuccess: data => {
      queryClient.setQueryData(['admin-scores', workout.id], data);
      queryClient.invalidateQueries({ queryKey: ['leaderboard', championshipSlug] });
      setBatchError(null);
    }, onError: e => setBatchError(errorMessage(e)),
  });
  function launch() {
    setBatchError(null);
    const entries = group.entries.map(entry => ({ teamId: entry.teamId, result: builders.current.get(entry.teamId)?.() ?? null }));
    if (entries.some(e => e.result === null)) { setBatchError('Preencha e revise os resultados de todas as equipes da bateria.'); return; }
    batch.mutate(entries as { teamId: number; result: ScoreInput }[]);
  }
  const launched = group.entries.filter((e) => e.scoreId).length;
  const titleId = `heat-sheet-${group.heatId}`;
  return (
    <section className="heat-sheet" aria-labelledby={titleId}>
      <header className="heat-sheet__head">
        <div>
          <h3 id={titleId} className="heat-sheet__title">Bateria {group.heatNumber}</h3>
          <p className="heat-sheet__meta">
            {group.scheduledAt && `${dayLabel(group.scheduledAt)}, ${clockTime(group.scheduledAt)} · `}
            {launched} de {group.entries.length} resultados lançados
          </p>
        </div>
        <HeatBadge status={group.status} />
      </header>
      {group.status === 'FINISHED' ? (
        <fieldset className="readonly-fieldset" disabled={readOnly || batch.isPending}><div className="table-scroll">
          <table className="table table--scores table--stack">
            <thead>
              <tr>
                <th scope="col">Raia</th>
                <th scope="col">Equipe</th>
                <th scope="col">Judge</th>
                <th scope="col">Situação</th>
                <th scope="col">Resultado</th>
                {workout.hasTiebreak && <th scope="col">Desempate</th>}
                <th scope="col">Pontos</th>
                <th scope="col"><span className="sr-only">Ações</span></th>
              </tr>
            </thead>
            <tbody>
              {group.entries.map((entry) => (
                <ScoreRow key={`${entry.teamId}-${entry.scoreId ?? 'novo'}`} entry={entry} workout={workout}
                  championshipSlug={championshipSlug} judgesListId={judgesListId} readOnly={readOnly}
                  register={(id, build) => { if (build) builders.current.set(id, build); else builders.current.delete(id); }} onLaunch={launch} />
              ))}
            </tbody>
          </table>
        </div>
          {!readOnly && <div className="form-actions">
            {batchError && <p className="form-error" role="alert">{batchError}</p>}
            {batch.isSuccess && <p className="form-ok" role="status">Resultados da bateria lançados.</p>}
            <button className="btn btn--primary" type="button" disabled={batch.isPending} onClick={launch}>Lançar</button>
          </div>}
        </fieldset>
      ) : (
        <>
          <p className="note">
            {group.status === 'IN_PROGRESS' ? 'Bateria em andamento. ' : 'A bateria ainda não começou. '}
            Os resultados podem ser lançados quando ela for concluída na aba Baterias.
          </p>
          <ol className="lanes">
            {group.entries.map((e) => (
              <li key={e.teamId}>
                <span className="lanes__no">{e.lane}</span>
                {e.teamName}
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  );
}

function initialState(entry: ScoreEntry, workout: Workout) {
  const rpr = workout.type === 'AMRAP' ? workout.repsPerRound ?? 0 : 0;
  const reps = entry.reps ?? null;
  return {
    judge: entry.judgeName?.toUpperCase() ?? '',
    status: (entry.status ?? 'VALID') as ScoreStatus,
    time: entry.timeMs ? formatDuration(entry.timeMs) : '',
    reps: reps === null ? '' : String(rpr ? reps % rpr : reps),
    rounds: reps === null || !rpr ? '' : String(Math.floor(reps / rpr)),
    load: entry.loadKg === null || entry.loadKg === undefined ? '' : String(entry.loadKg),
    tiebreak: entry.tiebreakMs ? formatDuration(entry.tiebreakMs) : '',
  };
}

function ScoreRow({
  entry,
  workout,
  championshipSlug,
  judgesListId,
  readOnly,
  register,
  onLaunch,
}: {
  entry: ScoreEntry;
  workout: Workout;
  championshipSlug: string;
  judgesListId: string;
  readOnly: boolean;
  register: (id: number, build: (() => ScoreInput | null) | null) => void;
  onLaunch: () => void;
}) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState(() => initialState(entry, workout));
  const [error, setError] = useState<string | null>(null);
  const rpr = workout.type === 'AMRAP' ? workout.repsPerRound ?? 0 : 0;

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['admin-scores', workout.id] });
    queryClient.invalidateQueries({ queryKey: ['leaderboard', championshipSlug] });
  };
  const save = useMutation({
    mutationFn: (input: ScoreInput) => adminApi.saveScore(workout.id, entry.teamId, input),
    onSuccess: refresh,
    onError: (e) => setError(errorMessage(e)),
  });
  const remove = useMutation({
    mutationFn: () => adminApi.deleteScore(workout.id, entry.teamId),
    onSuccess: refresh,
    onError: (e) => setError(errorMessage(e)),
  });

  function build(): ScoreInput | null {
    const result = buildScoreInput(workout, form);
    if (result.error) { setError(result.error); return null; }
    return result.input!;
  }

  useEffect(() => { register(entry.teamId, () => { setError(null); return build(); }); return () => register(entry.teamId, null); });

  function submit() {
    if (!entry.scoreId) { onLaunch(); return; }
    setError(null);
    const input = build();
    if (input) save.mutate(input);
  }

  const statuses: ScoreStatus[] = workout.type === 'FOR_TIME' ? ['VALID', 'CAPPED', 'DNS', 'DQ'] : ['VALID', 'DNS', 'DQ'];
  const noResult = form.status === 'DNS' || form.status === 'DQ';
  const id = `s-${workout.id}-${entry.teamId}`;

  return (
    <>
      <tr className={entry.scoreId ? 'is-scored' : undefined}>
        <td className="table__lane">{entry.lane}</td>
        <td>
          <strong>{entry.teamName}</strong>
          {entry.display && (
            <span className="table__sub">
              {ordinal(entry.placement)} lugar · lançado: {entry.display}
            </span>
          )}
        </td>
        <td data-label="Judge">
          <label className="sr-only" htmlFor={`${id}-judge`}>Judge da raia {entry.lane}</label>
          <input id={`${id}-judge`} className="input input--judge" list={judgesListId} autoComplete="off" autoCapitalize="characters"
            placeholder="Nome do judge" value={form.judge} onChange={(e) => setForm({ ...form, judge: e.target.value.toUpperCase() })}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                submit();
              }
            }} />
        </td>
        <td data-label="Situação">
          <label className="sr-only" htmlFor={`${id}-status`}>Situação de {entry.teamName}</label>
          <select id={`${id}-status`} className="input input--inline" value={form.status}
            onChange={(e) => setForm({ ...form, status: e.target.value as ScoreStatus })}>
            {statuses.map((s) => <option key={s} value={s}>{scoreStatusLabel[s]}</option>)}
          </select>
        </td>
        <td data-label="Resultado">
          {noResult ? (
            <span className="table__sub">Sem resultado</span>
          ) : workout.type === 'FOR_TIME' && form.status === 'VALID' ? (
            <ScoreInputBox id={`${id}-time`} label="Tempo" placeholder="mm:ss" value={form.time}
              onChange={(v) => setForm({ ...form, time: v })} onEnter={submit} />
          ) : workout.type === 'MAX_LOAD' ? (
            <ScoreInputBox id={`${id}-load`} label="Carga total (kg)" placeholder="kg" inputMode="decimal" value={form.load}
              onChange={(v) => setForm({ ...form, load: v })} onEnter={submit} />
          ) : (
            <div className="score-inputs">
              {rpr > 0 && (
                <ScoreInputBox id={`${id}-rounds`} label="Rounds" placeholder="rds" inputMode="numeric" value={form.rounds}
                  onChange={(v) => setForm({ ...form, rounds: v })} onEnter={submit} />
              )}
              <ScoreInputBox id={`${id}-reps`} label={rpr > 0 ? 'Reps extras' : 'Repetições'} placeholder="reps"
                inputMode="numeric" value={form.reps} onChange={(v) => setForm({ ...form, reps: v })} onEnter={submit} />
            </div>
          )}
          {!noResult && workout.type === 'FOR_TIME' && form.status === 'CAPPED' && <span className="table__sub">CAP {capLabel(workout.timeCapSeconds)}{workout.maxReps != null ? ` · máximo ${workout.maxReps} reps` : ' · informe o total de reps em Editar prova'}</span>}
          {!noResult && workout.type === 'AMRAP' && <span className="table__sub">{rpr ? `${rpr} reps por round` : 'Informe as reps por round em Editar prova'}{workout.maxReps != null && ` · máximo ${workout.maxReps} reps no total`}</span>}
        </td>
        {workout.hasTiebreak && (
          <td data-label="Desempate">
            {!noResult && (
              <ScoreInputBox id={`${id}-tb`} label="Desempate" placeholder="mm:ss" value={form.tiebreak}
                onChange={(v) => setForm({ ...form, tiebreak: v })} onEnter={submit} />
            )}
          </td>
        )}
        <td className="table__num" data-label="Pontos">{points(entry.points) || '–'}</td>
        <td>
          <div className="table__actions">
            {entry.scoreId && !readOnly && <button type="button" className="btn btn--primary btn--small" disabled={save.isPending} onClick={submit}>
              {save.isPending ? 'Salvando…' : 'Atualizar'}
            </button>}
            {entry.scoreId && !readOnly && (
              <button type="button" className="btn btn--ghost btn--small" disabled={remove.isPending}
                onClick={() => window.confirm(`Apagar o resultado de ${entry.teamName}?`) && remove.mutate()}>
                Apagar
              </button>
            )}
          </div>
        </td>
      </tr>
      {error && (
        <tr className="table__error-row">
          <td colSpan={workout.hasTiebreak ? 8 : 7}>
            <p className="form-error" role="alert">{entry.teamName}: {error}</p>
          </td>
        </tr>
      )}
    </>
  );
}

function ScoreInputBox({
  id,
  label,
  placeholder,
  value,
  onChange,
  onEnter,
  inputMode,
}: {
  id: string;
  label: string;
  placeholder: string;
  value: string;
  onChange: (v: string) => void;
  onEnter: () => void;
  inputMode?: 'numeric' | 'decimal';
}) {
  return (
    <span className="score-input">
      <label className="score-input__label" htmlFor={id}>{label}</label>
      <input id={id} className="input input--score" placeholder={placeholder} inputMode={inputMode} value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            onEnter();
          }
        }} />
    </span>
  );
}
