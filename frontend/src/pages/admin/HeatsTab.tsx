import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardList, Play, RotateCcw, Square, Trash2 } from 'lucide-react';
import { adminApi } from '../../api/endpoints';
import type { ChampionshipDetail, HeatStatus, HeatView, TeamOverview, Workout } from '../../api/types';
import { capLabel, clockTime, dayLabel, fromLocalInput, toLocalInput, workoutTypeLabel } from '../../lib/format';
import { CategoryPicker } from '../../components/Plate';
import { Field } from '../../components/Field';
import { HeatBadge } from '../../components/StatusBadge';
import { LiveHeat } from '../../components/LiveHeat';
import { HeatTiming } from '../../components/HeatTiming';
import { Empty, ErrorState, InlineError, Loading } from '../../components/States';

/** Limite de equipes por bateria (o mesmo da API e do banco). */
const MAX_LANES = 8;

export function HeatsTab({ championship }: { championship: ChampionshipDetail }) {
  const categories = championship.categories;
  const [categoryId, setCategoryId] = useState<number | null>(categories[0]?.id ?? null);
  const category = categories.find((c) => c.id === categoryId);
  const [workoutId, setWorkoutId] = useState<number | null>(category?.workouts[0]?.id ?? null);

  useEffect(() => {
    setWorkoutId(category?.workouts[0]?.id ?? null);
  }, [category?.id]);

  const heats = useQuery({
    queryKey: ['admin-heats', championship.id],
    queryFn: () => adminApi.heats(championship.id),
    refetchInterval: 15_000,
  });
  const teams = useQuery({ queryKey: ['admin-teams', championship.id], queryFn: () => adminApi.teams(championship.id) });

  if (categories.length === 0) return <Empty title="Cadastre categorias e provas antes de montar as baterias" />;
  if (heats.isLoading) return <Loading />;
  if (heats.error) return <ErrorState error={heats.error} />;

  const all = heats.data ?? [];
  const live = all.filter((h) => h.status === 'IN_PROGRESS');
  const workout = category?.workouts.find((w) => w.id === workoutId);

  return (
    <div className="stack">
      {live.map((h) => <LiveHeat key={h.heatId} heat={h} />)}

      <GenerateAllPanel championship={championship} heats={all} />

      <CategoryPicker categories={categories} value={categoryId} onChange={setCategoryId} />
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

      {workout && (
        <WorkoutHeats
          key={workout.id}
          championship={championship}
          workout={workout}
          heats={all.filter((h) => h.workoutId === workout.id)}
          teams={(teams.data ?? []).filter((t) => t.categoryId === workout.categoryId && t.status === 'CONFIRMED')}
        />
      )}
    </div>
  );
}

/** Monta a grade do campeonato inteiro de uma vez, sem gerar prova por prova. */
function GenerateAllPanel({ championship, heats }: { championship: ChampionshipDetail; heats: HeatView[] }) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ start: `${championship.startDate}T08:00`, interval: '15' });
  const generate = useMutation({
    mutationFn: () =>
      adminApi.generateAllHeats(championship.id, {
        firstStart: fromLocalInput(form.start)!,
        intervalMinutes: Number(form.interval),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-heats', championship.id] }),
  });
  const started = heats.some((h) => h.status !== 'UPCOMING');

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.start) return;
    if (heats.length && !window.confirm('A grade atual será substituída por uma nova. Continuar?')) return;
    generate.mutate();
  }

  return (
    <section className="panel" aria-labelledby="gen-all-title">
      <h2 id="gen-all-title" className="panel__title">Gerar baterias de todas as provas</h2>
      <p className="panel__lead">
        Monta a grade inteira com as equipes confirmadas, até {MAX_LANES} equipes por bateria, divididas de forma
        equilibrada. A Prova 1 de cada categoria vem primeiro, depois a Prova 2, e assim por diante. Em cada prova,
        as equipes com pior colocação correm primeiro. Cada horário soma a duração / time cap da prova e o intervalo
        após seu término. Ao iniciar uma bateria, atrasos ajustam automaticamente as seguintes.
      </p>
      {started ? (
        <p className="note">
          O campeonato já tem baterias iniciadas ou concluídas. Para ajustar, gere as baterias de cada prova que ainda
          não começou, logo abaixo.
        </p>
      ) : (
        <form className="form" onSubmit={submit} noValidate>
          <div className="grid-2">
            <Field label="Horário da primeira bateria" htmlFor="gen-all-start">
              <input id="gen-all-start" className="input" type="datetime-local" value={form.start}
                onChange={(e) => setForm({ ...form, start: e.target.value })} />
            </Field>
            <Field label="Intervalo após o término (min)" htmlFor="gen-all-interval" hint="Ex.: 13 min de prova + 8 min de intervalo = próxima bateria em 21 min.">
              <input id="gen-all-interval" className="input" type="number" min={0} max={240} value={form.interval}
                onChange={(e) => setForm({ ...form, interval: e.target.value })} />
            </Field>
          </div>
          <div className="form-actions">
            <InlineError error={generate.error} />
            {generate.isSuccess && (
              <p className="form-ok" role="status">{generate.data.length} baterias geradas.</p>
            )}
            <button type="submit" className="btn btn--primary" disabled={generate.isPending || !form.start}>
              {generate.isPending ? 'Gerando…' : heats.length ? 'Gerar todas de novo' : 'Gerar todas as baterias'}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

function WorkoutHeats({
  championship,
  workout,
  heats,
  teams,
}: {
  championship: ChampionshipDetail;
  workout: Workout;
  heats: HeatView[];
  teams: TeamOverview[];
}) {
  const queryClient = useQueryClient();
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin-heats', championship.id] });
  const defaultStart = heats[0]?.scheduledAt
    ? toLocalInput(heats[0].scheduledAt)
    : `${championship.startDate}T08:00`;
  const [gen, setGen] = useState({ lanes: String(MAX_LANES), start: defaultStart, interval: '15' });
  const [manual, setManual] = useState<{ start: string; teamIds: number[] }>({ start: defaultStart, teamIds: [] });

  const allocated = new Set(heats.flatMap((h) => h.lanes.map((l) => l.teamId)));
  const free = teams.filter((t) => !allocated.has(t.teamId));

  const generate = useMutation({
    mutationFn: () =>
      adminApi.generateHeats(workout.id, {
        lanes: Number(gen.lanes),
        firstStart: fromLocalInput(gen.start)!,
        intervalMinutes: Number(gen.interval),
      }),
    onSuccess: refresh,
  });
  const create = useMutation({
    mutationFn: () => adminApi.createHeat(workout.id, { scheduledAt: fromLocalInput(manual.start)!, teamIds: manual.teamIds,
      intervalMinutes: Number(gen.interval) }),
    onSuccess: () => {
      refresh();
      setManual((m) => ({ ...m, teamIds: [] }));
    },
  });

  function submitGenerate(event: FormEvent) {
    event.preventDefault();
    if (!gen.start) return;
    if (heats.length && !window.confirm('As baterias desta prova serão substituídas. Continuar?')) return;
    generate.mutate();
  }

  return (
    <div className="split">
      <section aria-labelledby="heat-list" className="stack">
        <h2 id="heat-list" className="section-title">
          Prova {workout.number}: {workout.name}
          <span className="section-title__sub">
            {workoutTypeLabel[workout.type]}{capLabel(workout.timeCapSeconds) ? `, cap ${capLabel(workout.timeCapSeconds)}` : ''}
          </span>
        </h2>
        {heats.length === 0 && (
          <Empty title="Nenhuma bateria nesta prova">
            <p>Gere automaticamente com as equipes confirmadas ou crie uma bateria manualmente.</p>
          </Empty>
        )}
        {heats.map((heat) => (
          <HeatCard key={heat.heatId} heat={heat} onChanged={refresh} />
        ))}
      </section>

      <div className="stack">
        <section className="panel" aria-labelledby="gen-title">
          <h2 id="gen-title" className="panel__title">Gerar só esta prova</h2>
          <p className="note">
            Distribui as {teams.length} equipes confirmadas. Equipes com pior colocação no leaderboard correm primeiro.
            O próximo início considera a duração da prova mais o intervalo após o término.
          </p>
          <form className="form" onSubmit={submitGenerate} noValidate>
            <div className="grid-2">
              <Field label={`Equipes por bateria (máx. ${MAX_LANES})`} htmlFor="gen-lanes">
                <input id="gen-lanes" className="input" type="number" min={1} max={MAX_LANES} value={gen.lanes}
                  onChange={(e) => setGen({ ...gen, lanes: e.target.value })} />
              </Field>
              <Field label="Intervalo após o término (min)" htmlFor="gen-interval">
                <input id="gen-interval" className="input" type="number" min={0} max={240} value={gen.interval}
                  onChange={(e) => setGen({ ...gen, interval: e.target.value })} />
              </Field>
            </div>
            <Field label="Horário da primeira bateria" htmlFor="gen-start">
              <input id="gen-start" className="input" type="datetime-local" value={gen.start}
                onChange={(e) => setGen({ ...gen, start: e.target.value })} />
            </Field>
            <InlineError error={generate.error} />
            <button type="submit" className="btn btn--primary" disabled={generate.isPending || teams.length === 0}>
              {heats.length ? 'Gerar de novo' : 'Gerar baterias'}
            </button>
          </form>
        </section>

        <section className="panel" aria-labelledby="manual-title">
          <h2 id="manual-title" className="panel__title">Nova bateria manual</h2>
          <form className="form" onSubmit={(e) => { e.preventDefault(); create.mutate(); }} noValidate>
            <Field label="Horário" htmlFor="manual-start">
              <input id="manual-start" className="input" type="datetime-local" value={manual.start}
                onChange={(e) => setManual({ ...manual, start: e.target.value })} />
            </Field>
            <p className="note">Duração: {capLabel(workout.timeCapSeconds) ?? 'defina a duração na aba Provas'}.
              Intervalo após o término: {gen.interval} min (configurado acima).</p>
            {free.length === 0 ? (
              <p className="note">Todas as equipes confirmadas já estão em alguma bateria desta prova.</p>
            ) : (
              <fieldset className="checks">
                <legend className="field__label">Equipes (na ordem das raias, até {MAX_LANES})</legend>
                {free.map((t) => (
                  <label key={t.teamId} className="check">
                    <input
                      type="checkbox"
                      checked={manual.teamIds.includes(t.teamId)}
                      disabled={!manual.teamIds.includes(t.teamId) && manual.teamIds.length >= MAX_LANES}
                      onChange={() =>
                        setManual((m) => ({
                          ...m,
                          teamIds: m.teamIds.includes(t.teamId)
                            ? m.teamIds.filter((id) => id !== t.teamId)
                            : [...m.teamIds, t.teamId],
                        }))
                      }
                    />
                    {t.teamName}
                    {manual.teamIds.includes(t.teamId) && (
                      <span className="check__lane">raia {manual.teamIds.indexOf(t.teamId) + 1}</span>
                    )}
                  </label>
                ))}
              </fieldset>
            )}
            <InlineError error={create.error} />
            <button type="submit" className="btn btn--secondary" disabled={create.isPending || !manual.start}>
              Criar bateria
            </button>
          </form>
        </section>
      </div>
    </div>
  );
}

function HeatCard({ heat, onChanged }: { heat: HeatView; onChanged: () => void }) {
  const [, setParams] = useSearchParams();
  const [time, setTime] = useState(toLocalInput(heat.scheduledAt));
  useEffect(() => setTime(toLocalInput(heat.scheduledAt)), [heat.scheduledAt]);

  const start = useMutation({ mutationFn: () => adminApi.startHeat(heat.heatId), onSuccess: onChanged });
  const status = useMutation({
    mutationFn: (next: HeatStatus) => adminApi.setHeatStatus(heat.heatId, next),
    onSuccess: onChanged,
  });
  const reschedule = useMutation({
    mutationFn: () => adminApi.updateHeat(heat.heatId, { scheduledAt: fromLocalInput(time)! }),
    onSuccess: onChanged,
  });
  const remove = useMutation({ mutationFn: () => adminApi.deleteHeat(heat.heatId), onSuccess: onChanged });
  const error = start.error || status.error || reschedule.error || remove.error;
  const changedTime = time !== toLocalInput(heat.scheduledAt);

  return (
    <article className={`heat-card heat-card--${heat.status.toLowerCase()}`}>
      <header className="heat-card__head">
        <div>
          <h3 className="heat-card__title">Bateria {heat.heatNumber}</h3>
          <p className="heat-card__time">{dayLabel(heat.scheduledAt)}, {clockTime(heat.scheduledAt)}</p>
        </div>
        <HeatBadge status={heat.status} />
      </header>

      <HeatTiming heat={heat} />

      <ol className="lanes lanes--admin">
        {heat.lanes.map((l) => (
          <li key={l.lane}>
            <span className="lanes__no">{l.lane}</span>
            {l.teamName}
            {l.judgeName && <span className="lanes__judge">judge {l.judgeName}</span>}
          </li>
        ))}
        {heat.lanes.length === 0 && <li className="note">Sem equipes</li>}
      </ol>

      <div className="heat-card__actions">
        {heat.status === 'UPCOMING' && (
          <button type="button" className="btn btn--primary btn--small" disabled={start.isPending}
            onClick={() => start.mutate()}>
            <Play size={14} aria-hidden /> Iniciar bateria
          </button>
        )}
        {heat.status === 'IN_PROGRESS' && (
          <button type="button" className="btn btn--primary btn--small" disabled={status.isPending}
            onClick={() => status.mutate('FINISHED')}>
            <Square size={14} aria-hidden /> Concluir
          </button>
        )}
        {heat.status === 'FINISHED' && (
          <button type="button" className="btn btn--primary btn--small"
            onClick={() => setParams({ aba: 'pontuacao', prova: String(heat.workoutId) }, { replace: true })}>
            <ClipboardList size={14} aria-hidden /> Lançar resultados
          </button>
        )}
        {heat.status !== 'UPCOMING' && (
          <button type="button" className="btn btn--ghost btn--small" disabled={status.isPending}
            onClick={() => window.confirm('Voltar esta bateria para "Próxima"?') && status.mutate('UPCOMING')}>
            <RotateCcw size={14} aria-hidden /> Marcar como próxima
          </button>
        )}
        {heat.status === 'UPCOMING' && (
          <>
            <label className="sr-only" htmlFor={`heat-time-${heat.heatId}`}>Novo horário</label>
            <input id={`heat-time-${heat.heatId}`} className="input input--inline" type="datetime-local" value={time}
              onChange={(e) => setTime(e.target.value)} />
            {changedTime && (
              <button type="button" className="btn btn--secondary btn--small" disabled={reschedule.isPending}
                onClick={() => reschedule.mutate()}>
                Salvar horário
              </button>
            )}
            <button type="button" className="btn btn--danger btn--small" disabled={remove.isPending}
              onClick={() => window.confirm(`Excluir a bateria ${heat.heatNumber}?`) && remove.mutate()}>
              <Trash2 size={14} aria-hidden /> Excluir
            </button>
          </>
        )}
      </div>
      <InlineError error={error} />
    </article>
  );
}
