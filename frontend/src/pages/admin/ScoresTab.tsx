import { useState } from 'react';
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
  parseDuration,
  points,
  scoreStatusLabel,
  workoutTypeLabel,
} from '../../lib/format';
import { CategoryPicker } from '../../components/Plate';
import { HeatBadge } from '../../components/StatusBadge';
import { Empty, ErrorState, Loading } from '../../components/States';
import { errorMessage } from '../../api/client';

export function ScoresTab({ championship }: { championship: ChampionshipDetail }) {
  const categories = championship.categories;
  // ?prova= vem do botão "Lançar resultados" de uma bateria concluída.
  const [params] = useSearchParams();
  const requested = Number(params.get('prova')) || null;
  const initial = categories.find((c) => c.workouts.some((w) => w.id === requested)) ?? categories[0];
  const [categoryId, setCategoryId] = useState<number | null>(initial?.id ?? null);
  const [workoutId, setWorkoutId] = useState<number | null>(
    initial?.workouts.some((w) => w.id === requested) ? requested : initial?.workouts[0]?.id ?? null,
  );
  const category = categories.find((c) => c.id === categoryId);

  if (categories.length === 0) return <Empty title="Cadastre categorias e provas antes de lançar resultados" />;

  return (
    <div className="stack">
      <CategoryPicker
        categories={categories}
        value={categoryId}
        onChange={(id) => {
          setCategoryId(id);
          setWorkoutId(categories.find((c) => c.id === id)?.workouts[0]?.id ?? null);
        }}
      />
      {category && category.workouts.length === 0 && <Empty title="Esta categoria ainda não tem provas" />}
      {category && category.workouts.length > 0 && (
        <div className="picker" role="group" aria-label="Prova">
          {category.workouts.map((w) => (
            <button key={w.id} type="button" className="picker__option" aria-pressed={workoutId === w.id}
              onClick={() => setWorkoutId(w.id)}>
              Prova {w.number}: {w.name}
            </button>
          ))}
        </div>
      )}
      {workoutId && <ScoreSheet workoutId={workoutId} championshipSlug={championship.slug} />}
    </div>
  );
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

function ScoreSheet({ workoutId, championshipSlug }: { workoutId: number; championshipSlug: string }) {
  const query = useQuery({ queryKey: ['admin-scores', workoutId], queryFn: () => adminApi.scores(workoutId) });
  if (query.isLoading) return <Loading />;
  if (query.error || !query.data) return <ErrorState error={query.error} />;
  const { workout, entries } = query.data;
  const groups = groupByHeat(entries);
  const withoutHeat = entries.filter((e) => !e.heatId);
  const judgesListId = `judges-${workout.id}`;
  const judges = [...new Set(entries.map((e) => e.judgeName).filter((j): j is string => Boolean(j)))];

  return (
    <section aria-labelledby="sheet-title" className="stack">
      <h2 id="sheet-title" className="section-title">
        Prova {workout.number}: {workout.name}
        <span className="section-title__sub">
          {workoutTypeLabel[workout.type]}
          {capLabel(workout.timeCapSeconds) ? `, cap ${capLabel(workout.timeCapSeconds)}` : ''}
        </span>
      </h2>
      <p className="note">
        Os resultados são lançados por bateria, depois que ela é concluída, com o nome do judge de cada raia. A
        colocação e os pontos são recalculados na hora e aparecem no leaderboard público.
      </p>
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
        groups.map((group) => (
          <HeatSheet key={group.heatId} group={group} workout={workout} championshipSlug={championshipSlug}
            judgesListId={judgesListId} />
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
}: {
  group: HeatGroup;
  workout: Workout;
  championshipSlug: string;
  judgesListId: string;
}) {
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
        <div className="table-scroll">
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
                  championshipSlug={championshipSlug} judgesListId={judgesListId} />
              ))}
            </tbody>
          </table>
        </div>
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
  const rpr = workout.repsPerRound ?? 0;
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
}: {
  entry: ScoreEntry;
  workout: Workout;
  championshipSlug: string;
  judgesListId: string;
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
    if (!form.judge.trim()) return fail('Informe o nome do judge da raia');
    const input: ScoreInput = { status: form.status, judgeName: form.judge.trim() };
    if (form.status === 'DNS' || form.status === 'DQ') return input;
    const num = (v: string) => (v.trim() === '' ? null : Number(v.replace(',', '.')));

    if (workout.type === 'FOR_TIME' && form.status === 'VALID') {
      const ms = parseDuration(form.time);
      if (!ms) return fail('Tempo inválido. Use mm:ss, ex.: 07:42');
      input.timeMs = ms;
    } else if (workout.type === 'MAX_LOAD') {
      const kg = num(form.load);
      if (kg === null || Number.isNaN(kg) || kg < 0) return fail('Informe a carga em kg');
      input.loadKg = kg;
    } else {
      const reps = num(form.reps) ?? 0;
      const rounds = rpr ? num(form.rounds) ?? 0 : 0;
      if (Number.isNaN(reps) || Number.isNaN(rounds) || (form.reps.trim() === '' && form.rounds.trim() === '')) {
        return fail('Informe as repetições');
      }
      input.reps = rounds * rpr + reps;
    }
    if (workout.hasTiebreak && form.tiebreak.trim()) {
      const tb = parseDuration(form.tiebreak);
      if (!tb) return fail('Tempo de desempate inválido');
      input.tiebreakMs = tb;
    }
    return input;
  }
  function fail(message: string): null {
    setError(message);
    return null;
  }

  function submit() {
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
            <ScoreInputBox id={`${id}-load`} label="Carga (kg)" placeholder="kg" inputMode="decimal" value={form.load}
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
            <button type="button" className="btn btn--primary btn--small" disabled={save.isPending} onClick={submit}>
              {save.isPending ? 'Salvando…' : entry.scoreId ? 'Atualizar' : 'Lançar'}
            </button>
            {entry.scoreId && (
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
      <label className="sr-only" htmlFor={id}>{label}</label>
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
