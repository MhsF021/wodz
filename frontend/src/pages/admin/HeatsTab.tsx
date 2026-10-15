import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ClipboardList, Pencil, Play, RotateCcw, Trash2 } from 'lucide-react';
import { adminApi } from '../../api/endpoints';
import { errorMessage } from '../../api/client';
import type { ChampionshipDetail, HeatPlanEntry, HeatStatus, HeatView } from '../../api/types';
import { clockTime, dayLabel, fromLocalInput, toLocalInput } from '../../lib/format';
import { Field } from '../../components/Field';
import { HeatBadge } from '../../components/StatusBadge';
import { LiveHeat } from '../../components/LiveHeat';
import { HeatTiming } from '../../components/HeatTiming';
import { Dialog } from '../../components/Dialog';
import { Empty, ErrorState, InlineError, Loading } from '../../components/States';
import { HeatSettingsPanel } from './HeatSettingsPanel';

function asPlan(heats: HeatView[]): HeatPlanEntry[] {
  return heats.map(h => ({ heatId: h.heatId, workoutId: h.workoutId, heatNumber: h.heatNumber,
    scheduledAt: h.scheduledAt, teamIds: h.lanes.map(l => l.teamId) }));
}

/** Mantém o descanso e desloca as próximas quando uma bateria manual é incluída. */
function sequence(plan: HeatPlanEntry[], c: ChampionshipDetail): HeatPlanEntry[] {
  const workouts = c.categories.flatMap(cat => cat.workouts.map(w => ({ ...w, order: cat.displayOrder })));
  const ordered = [...plan].sort((a, b) => {
    const wa = workouts.find(w => w.id === a.workoutId)!;
    const wb = workouts.find(w => w.id === b.workoutId)!;
    return wa.number - wb.number || wa.order - wb.order || wa.categoryId - wb.categoryId || a.heatNumber - b.heatNumber;
  });
  let next = new Date(c.heatFirstStart ?? ordered[0]?.scheduledAt ?? `${c.startDate}T08:00`).getTime();
  return ordered.map(entry => {
    const w = workouts.find(w => w.id === entry.workoutId)!;
    const start = Math.max(next, new Date(entry.scheduledAt).getTime());
    next = start + ((w.timeCapSeconds ?? 0) + (c.heatIntervalMinutes ?? 0) * 60) * 1000;
    return { ...entry, scheduledAt: new Date(start).toISOString() };
  });
}

export function HeatsTab({ championship: c }: { championship: ChampionshipDetail }) {
  const queryClient = useQueryClient();
  const [categoryId, setCategoryId] = useState('');
  const [workoutNumber, setWorkoutNumber] = useState('');
  const [status, setStatus] = useState<HeatStatus | ''>('');
  const [search, setSearch] = useState('');
  const [draft, setDraft] = useState<HeatPlanEntry[] | null>(null);
  const [notice, setNotice] = useState<{ title: string; text?: string; names?: string[] } | null>(null);
  const [regenerate, setRegenerate] = useState(false);
  const [manual, setManual] = useState<{ category: string; workout: string; start: string; teams: number[] }>({
    category: '', workout: '', start: `${c.startDate}T08:00`, teams: [],
  });
  const heats = useQuery({ queryKey: ['admin-heats', c.id], queryFn: () => adminApi.heats(c.id), refetchInterval: 15_000 });
  const teams = useQuery({ queryKey: ['admin-teams', c.id], queryFn: () => adminApi.teams(c.id) });
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['admin-heats', c.id] });
    queryClient.invalidateQueries({ queryKey: ['admin-championship', c.id] });
  };
  const generate = useMutation({ mutationFn: () => adminApi.generateAllHeats(c.id, {
    lanes: c.heatLanes!, firstStart: c.heatFirstStart!, intervalMinutes: c.heatIntervalMinutes!,
  }), onSuccess: () => { refresh(); setRegenerate(false); },
    onError: error => { setRegenerate(false); setNotice({ title: 'Não foi possível gerar', text: errorMessage(error) }); } });
  const save = useMutation({ mutationFn: (plan: HeatPlanEntry[]) => adminApi.saveHeatPlan(c.id, plan),
    onSuccess: data => { queryClient.setQueryData(['admin-heats', c.id], data); setDraft(null); refresh(); },
    onError: error => setNotice({ title: 'Revise a distribuição', text: errorMessage(error) }) });

  if (heats.isLoading || teams.isLoading) return <Loading />;
  if (heats.error || teams.error) return <ErrorState error={heats.error || teams.error} />;
  const all = heats.data ?? [];
  const confirmed = (teams.data ?? []).filter(t => t.status === 'CONFIRMED');
  const mutable = !c.registrationFlag && !c.started && !['IN_PROGRESS', 'FINISHED', 'CANCELLED'].includes(c.status)
    && all.every(h => h.status === 'UPCOMING');
  const live = all.filter(h => h.status === 'IN_PROGRESS');
  const next = [...all].filter(h => h.status === 'UPCOMING')
    .sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt) || a.heatId - b.heatId)[0];
  const numbers = [...new Set(c.categories.flatMap(cat => cat.workouts.map(w => w.number)))].sort((a, b) => a - b);
  const shown = (draft ?? asPlan(all)).filter(entry => {
    const cat = c.categories.find(cat => cat.workouts.some(w => w.id === entry.workoutId));
    const workout = cat?.workouts.find(w => w.id === entry.workoutId);
    const heat = all.find(h => h.heatId === entry.heatId);
    const text = `${cat?.name} ${workout?.name} ${entry.teamIds.map(id => confirmed.find(t => t.teamId === id)?.teamName).join(' ')}`;
    return (!categoryId || cat?.id === Number(categoryId)) && (!workoutNumber || workout?.number === Number(workoutNumber))
      && (!status || (heat?.status ?? 'UPCOMING') === status) && text.toLocaleLowerCase('pt-BR').includes(search.toLocaleLowerCase('pt-BR'));
  });
  const manualCategory = c.categories.find(cat => cat.id === Number(manual.category));

  function updateTeams(entry: HeatPlanEntry, ids: number[]) {
    const cat = c.categories.find(cat => cat.workouts.some(w => w.id === entry.workoutId))!;
    const siblings = new Set(cat.workouts.map(w => w.id));
    setDraft(plan => (plan ?? asPlan(all)).map(e => siblings.has(e.workoutId)
      ? { ...e, teamIds: e.heatNumber === entry.heatNumber ? ids : e.teamIds.filter(id => !ids.includes(id)) } : e));
  }

  function submitPlan() {
    const plan = (draft ?? []).filter(e => e.teamIds.length > 0);
    const missing = new Set<string>();
    for (const cat of c.categories) for (const w of cat.workouts) {
      const assigned = new Set(plan.filter(e => e.workoutId === w.id).flatMap(e => e.teamIds));
      for (const t of confirmed.filter(t => t.categoryId === cat.id))
        if (!assigned.has(t.teamId)) missing.add(`${t.teamName} · ${cat.name}`);
    }
    if (missing.size) { setNotice({ title: 'Equipes sem bateria', names: [...missing] }); return; }
    save.mutate(sequence(plan, c));
  }

  function addManual() {
    const cat = manualCategory!;
    const siblings = new Set(cat.workouts.map(w => w.id));
    let plan = draft ?? asPlan(all);
    const number = Math.max(0, ...plan.filter(e => siblings.has(e.workoutId)).map(e => e.heatNumber)) + 1;
    plan = plan.map(e => siblings.has(e.workoutId) ? { ...e, teamIds: e.teamIds.filter(id => !manual.teams.includes(id)) } : e)
      .filter(e => e.teamIds.length > 0);
    const scheduledAt = fromLocalInput(manual.start)!;
    for (const w of cat.workouts) plan.push({ heatId: null, workoutId: w.id, heatNumber: number,
      scheduledAt: w.id === Number(manual.workout) ? scheduledAt : c.heatFirstStart!, teamIds: [...manual.teams] });
    setDraft(sequence(plan, c));
    setManual({ ...manual, teams: [] });
    setCategoryId(manual.category); setWorkoutNumber('');
  }

  return <div className="stack heat-manager">
    {!c.started && !['IN_PROGRESS', 'FINISHED', 'CANCELLED'].includes(c.status) && <HeatSettingsPanel championship={c} editing={draft !== null} />}
    {live.map(h => <LiveHeat key={h.heatId} heat={h} actions={c.status !== 'FINISHED'
      ? <CompleteAndLaunch heat={h} onChanged={refresh} /> : undefined} />)}
    {next && <div className="next-heat" aria-label="Próxima bateria">
      <span className="next-heat__label">Próxima · {clockTime(next.scheduledAt)}</span>
      <strong>{next.categoryName} · Bateria {next.heatNumber} · {next.workoutName}</strong>
      <span className="next-heat__teams" title={next.lanes.map(l => l.teamName).join(' · ')}>
        {next.lanes.slice(0, 3).map(l => l.teamName).join(' · ') || 'Sem equipes'}
        {next.lanes.length > 3 && ` · +${next.lanes.length - 3} equipes`}
      </span>
      {c.status === 'IN_PROGRESS' && !c.registrationFlag && next.lanes.length > 0 &&
        <div className="next-heat__actions"><HeatActions heat={next} onChanged={refresh} disableStart={live.length > 0} />
          {live.length > 0 && <span className="next-heat__hint">Conclua a bateria em andamento para iniciar a próxima.</span>}
        </div>}
    </div>}
    <div className="heat-manager__head">
      <div><h2 className="section-title">Grade de baterias</h2>
        <p className="note">{all.length} baterias · {confirmed.length} equipes confirmadas</p></div>
      {mutable && c.heatLanes && <div className="row-actions">
        <button className="btn btn--secondary" disabled={generate.isPending || draft !== null || !confirmed.length}
          onClick={() => setRegenerate(true)}><RotateCcw size={16} aria-hidden />Nova Geração</button>
        <button className="btn btn--primary" disabled={draft !== null || !all.length}
          onClick={() => setDraft(asPlan(all))}><Pencil size={16} aria-hidden />Editar grade</button>
      </div>}
    </div>
    {c.registrationFlag && !c.started && <p className="note">A grade será gerada automaticamente ao fechar as inscrições.</p>}
    {(c.started || c.status === 'IN_PROGRESS') && <p className="note">O campeonato iniciou. A distribuição das baterias e raias está bloqueada.</p>}
    {draft && <div className="heat-edit-bar" role="status">
      <span>Distribuição em edição · as raias são mantidas em todas as provas da categoria.</span>
      <div className="row-actions"><button className="btn btn--ghost" disabled={save.isPending} onClick={() => setDraft(null)}>Cancelar edição</button>
        <button className="btn btn--primary" disabled={save.isPending} onClick={submitPlan}>Salvar baterias</button></div>
    </div>}
    <div className="panel heat-filters">
      <Field label="Categoria" htmlFor="heat-category"><select id="heat-category" className="input" value={categoryId} onChange={e => setCategoryId(e.target.value)}>
        <option value="">Todas as categorias</option>{c.categories.map(cat => <option key={cat.id} value={cat.id}>{cat.name}</option>)}
      </select></Field>
      <Field label="Prova" htmlFor="heat-workout"><select id="heat-workout" className="input" value={workoutNumber} onChange={e => setWorkoutNumber(e.target.value)}>
        <option value="">Todas as provas</option>{numbers.map(n => <option key={n} value={n}>Prova {n}</option>)}
      </select></Field>
      <Field label="Situação" htmlFor="heat-status"><select id="heat-status" className="input" value={status} onChange={e => setStatus(e.target.value as HeatStatus | '')}>
        <option value="">Todas</option><option value="UPCOMING">Próximas</option><option value="IN_PROGRESS">Em andamento</option><option value="FINISHED">Concluídas</option>
      </select></Field>
      <Field label="Buscar" htmlFor="heat-search"><input id="heat-search" className="input" type="search" placeholder="Equipe ou prova" value={search} onChange={e => setSearch(e.target.value)} /></Field>
    </div>
    <p className="note" role="status">{shown.length} baterias exibidas</p>
    {shown.length === 0 && <Empty title={all.length ? 'Nenhuma bateria corresponde aos filtros' : 'Nenhuma bateria gerada'} />}
    <div className="heat-grid">{shown.map(entry => {
      const cat = c.categories.find(cat => cat.workouts.some(w => w.id === entry.workoutId))!;
      const w = cat.workouts.find(w => w.id === entry.workoutId)!;
      const heat = all.find(h => h.heatId === entry.heatId);
      return <article className={`heat-card heat-card--${(heat?.status ?? 'UPCOMING').toLowerCase()}`} key={`${entry.workoutId}-${entry.heatNumber}`}>
        <header className="heat-card__head"><div><span className="heat-card__category">{cat.name}</span>
          <h3 className="heat-card__title">Bateria {entry.heatNumber}</h3><p className="heat-card__time">Prova {w.number}: {w.name}</p>
          <p className="heat-card__time">{dayLabel(entry.scheduledAt)}, {clockTime(entry.scheduledAt)}</p></div>
          <HeatBadge status={heat?.status ?? 'UPCOMING'} /></header>
        {draft ? <div className="stack">
          <Field label="Horário" htmlFor={`draft-time-${w.id}-${entry.heatNumber}`}><input id={`draft-time-${w.id}-${entry.heatNumber}`} type="datetime-local" className="input"
            value={toLocalInput(entry.scheduledAt)} onChange={e => { const value = fromLocalInput(e.target.value); if (value) setDraft(plan => plan!.map(p => p === entry ? { ...p, scheduledAt: value } : p)); }} /></Field>
          <ol className="lanes lanes--admin">{entry.teamIds.map((id, index) => <li key={id}><span className="lanes__no">{index + 1}</span>
            <span>{confirmed.find(t => t.teamId === id)?.teamName}</span><button className="btn btn--ghost btn--small" aria-label={`Remover ${confirmed.find(t => t.teamId === id)?.teamName}`}
              onClick={() => updateTeams(entry, entry.teamIds.filter(t => t !== id))}><Trash2 size={14} aria-hidden /></button>
            {index > 0 && <button className="btn btn--ghost btn--small" aria-label="Mover para a raia anterior" onClick={() => {
              const ids = [...entry.teamIds]; [ids[index - 1], ids[index]] = [ids[index], ids[index - 1]]; updateTeams(entry, ids);
            }}>↑</button>}</li>)}</ol>
          <Field label="Adicionar ou mover equipe" htmlFor={`add-team-${w.id}-${entry.heatNumber}`}>
            <select id={`add-team-${w.id}-${entry.heatNumber}`} className="input" value="" disabled={entry.teamIds.length >= (c.heatLanes ?? 8)}
              onChange={e => e.target.value && updateTeams(entry, [...entry.teamIds, Number(e.target.value)])}>
              <option value="">Selecione uma equipe</option>{confirmed.filter(t => t.categoryId === cat.id && !entry.teamIds.includes(t.teamId)).map(t => <option key={t.teamId} value={t.teamId}>{t.teamName}</option>)}
            </select></Field>
          <button className="btn btn--danger btn--small" onClick={() => setDraft(plan => plan!.filter(p => !(cat.workouts.some(w => w.id === p.workoutId) && p.heatNumber === entry.heatNumber)))}>Remover bateria</button>
        </div> : heat && <><HeatTiming heat={heat} /><ol className="lanes lanes--admin">{heat.lanes.map(l => <li key={l.teamId}><span className="lanes__no">{l.lane}</span>{l.teamName}</li>)}</ol>
          {c.status !== 'FINISHED' && c.status !== 'CANCELLED' && !c.registrationFlag && <HeatActions heat={heat} onChanged={refresh} disableStart={live.length > 0} />}</>}
      </article>;
    })}</div>
    {mutable && c.heatLanes && <section className="panel" aria-labelledby="manual-title">
      <h2 id="manual-title" className="panel__title">Nova bateria manual</h2>
      <form className="form" onSubmit={e => { e.preventDefault(); addManual(); }}>
        <div className="heat-filters"><Field label="Categoria" htmlFor="manual-category"><select id="manual-category" className="input" required value={manual.category}
          onChange={e => setManual({ ...manual, category: e.target.value, workout: '', teams: [] })}>
          <option value="">Selecione</option>{c.categories.map(cat => <option key={cat.id} value={cat.id}>{cat.name}</option>)}
        </select></Field><Field label="Prova" htmlFor="manual-workout"><select id="manual-workout" className="input" required value={manual.workout} onChange={e => setManual({ ...manual, workout: e.target.value })}>
          <option value="">Selecione</option>{manualCategory?.workouts.map(w => <option key={w.id} value={w.id}>Prova {w.number}: {w.name}</option>)}
        </select></Field><Field label="Horário" htmlFor="manual-start"><input id="manual-start" className="input" type="datetime-local" required value={manual.start} onChange={e => setManual({ ...manual, start: e.target.value })} /></Field></div>
        <fieldset className="checks"><legend className="field__label">Equipes · até {c.heatLanes}</legend>
          {confirmed.filter(t => t.categoryId === Number(manual.category)).map(t => <label className="check" key={t.teamId}>
            <input type="checkbox" checked={manual.teams.includes(t.teamId)} disabled={!manual.teams.includes(t.teamId) && manual.teams.length >= c.heatLanes!}
              onChange={() => setManual({ ...manual, teams: manual.teams.includes(t.teamId) ? manual.teams.filter(id => id !== t.teamId) : [...manual.teams, t.teamId] })} />{t.teamName}
          </label>)}
        </fieldset>
        <button className="btn btn--secondary" disabled={!manual.teams.length || save.isPending}>Adicionar à grade</button>
      </form>
    </section>}
    {notice && <Dialog title={notice.title} closeLabel="Voltar à edição" onClose={() => setNotice(null)}>
      <p>{notice.text ?? 'As equipes abaixo estão sem bateria. Inclua todas na grade antes de salvar.'}</p>
      {notice.names && <ul>{notice.names.map(name => <li key={name}>{name}</li>)}</ul>}
      <button className="btn btn--primary" onClick={() => setNotice(null)}>Corrigir distribuição</button>
    </Dialog>}
    {regenerate && <Dialog title="Nova Geração" closeLabel="Cancelar nova geração" onClose={() => !generate.isPending && setRegenerate(false)}>
      <p>A grade atual será substituída usando a configuração salva. As mesmas equipes e raias serão mantidas em todas as provas de cada categoria.</p>
      <div className="row-actions"><button className="btn btn--ghost" disabled={generate.isPending} onClick={() => setRegenerate(false)}>Cancelar</button>
        <button className="btn btn--primary" disabled={generate.isPending} onClick={() => generate.mutate()}>Gerar novamente</button></div>
    </Dialog>}
  </div>;
}

function CompleteAndLaunch({ heat, onChanged }: { heat: HeatView; onChanged: () => void }) {
  const [, setParams] = useSearchParams();
  const complete = useMutation({ mutationFn: () => adminApi.setHeatStatus(heat.heatId, 'FINISHED'), onSuccess: () => {
    onChanged(); setParams({ aba: 'resultados', prova: String(heat.workoutId), bateria: String(heat.heatId) }, { replace: true });
  } });
  return <><button className="btn btn--primary" disabled={complete.isPending} onClick={() => complete.mutate()}>Concluir e Lançar</button><InlineError error={complete.error} /></>;
}

function HeatActions({ heat, onChanged, disableStart = false }: { heat: HeatView; onChanged: () => void; disableStart?: boolean }) {
  const [, setParams] = useSearchParams();
  const start = useMutation({ mutationFn: () => adminApi.startHeat(heat.heatId), onSuccess: onChanged });
  return <div className="heat-card__actions">
    {heat.status === 'UPCOMING' && <button className="btn btn--primary btn--small" disabled={disableStart || start.isPending} onClick={() => start.mutate()}><Play size={14} aria-hidden />Iniciar bateria</button>}
    {heat.status === 'IN_PROGRESS' && <CompleteAndLaunch heat={heat} onChanged={onChanged} />}
    {heat.status === 'FINISHED' && <button className="btn btn--primary btn--small" onClick={() => setParams({ aba: 'resultados', prova: String(heat.workoutId), bateria: String(heat.heatId) }, { replace: true })}><ClipboardList size={14} aria-hidden />Lançar resultados</button>}
    <InlineError error={start.error} />
  </div>;
}
