import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ChevronRight } from 'lucide-react';
import { athleteApi, publicApi } from '../api/endpoints';
import type { HeatView, Team } from '../api/types';
import { capLabel, clockTime, dayLabel, money, workoutTypeLabel } from '../lib/format';
import { teamSchedule } from '../lib/teamSchedule';
import { Dialog } from '../components/Dialog';
import { useAuth } from '../auth/AuthContext';
import { HeatBadge, TeamBadge } from '../components/StatusBadge';
import { HeatTiming } from '../components/HeatTiming';
import { Empty, ErrorState, InlineError, Loading } from '../components/States';

export default function MyTeamsPage() {
  const [params, setParams] = useSearchParams();
  const teams = useQuery({ queryKey: ['my-teams'], queryFn: athleteApi.teams,
    staleTime: 0, refetchInterval: params.has('equipe') ? 20_000 : false });
  const activeTeams = teams.data?.filter((team) => team.status === 'PENDING_PAYMENT' || team.status === 'CONFIRMED');
  const selectedTeam = activeTeams?.find((team) => String(team.id) === params.get('equipe'));

  function selectTeam(id?: number) {
    const next = new URLSearchParams(params);
    if (id === undefined) next.delete('equipe'); else next.set('equipe', String(id));
    setParams(next, { replace: true });
  }

  if (teams.isLoading) return <Loading />;
  if (teams.error && !teams.data) return <div className="page"><ErrorState error={teams.error}
    action={<button className="btn btn--secondary" onClick={() => teams.refetch()}>Tentar novamente</button>} /></div>;

  return (
    <div className="page">
      <h1 className="page-title">Minhas equipes</h1>
      <p className="my-teams__intro">Selecione uma equipe para ver sua inscrição, os horários das baterias e as provas.</p>
      {teams.error && <ErrorState error={teams.error}
        action={<button className="btn btn--secondary" onClick={() => teams.refetch()}>Tentar novamente</button>} />}
      {activeTeams?.length === 0 && <Empty title="Você ainda não inscreveu nenhuma equipe">
        <Link className="btn btn--primary" to="/">Encontrar um campeonato</Link>
      </Empty>}
      <ul className="team-mini-cards" aria-label="Suas equipes">
        {activeTeams?.map((team) => (
          <li key={team.id}>
            <button type="button" className="team-mini-card" aria-haspopup="dialog"
              onClick={() => selectTeam(team.id)}>
              <span className="team-mini-card__championship">{team.championship.name}</span>
              <span className="team-mini-card__name">{team.name}</span>
              <span className="team-mini-card__category">{team.category.name}</span>
            </button>
          </li>
        ))}
      </ul>
      {selectedTeam && <TeamDetails key={selectedTeam.id} team={selectedTeam} onClose={() => selectTeam()} />}
    </div>
  );
}

function LocalLoading({ label }: { label: string }) {
  return <div className="details-dialog__loading" role="status" aria-label={label}><span className="spinner" aria-hidden /></div>;
}

export function TeamDetails({ team, onClose }: { team: Team; onClose: () => void }) {
  const cache = useQueryClient();
  const { user } = useAuth();
  const isCaptain = user?.id === team.captainId;
  const [editingName, setEditingName] = useState(false);
  const [teamName, setTeamName] = useState(team.name);
  const rename = useMutation({
    mutationFn: () => athleteApi.renameTeam(team.id, teamName.trim()),
    onSuccess: (updated) => {
      cache.setQueryData<Team[]>(['my-teams'], (previous = []) => previous.map(item => item.id === updated.id ? updated : item));
      void cache.invalidateQueries({ queryKey: ['my-heats'] });
      void cache.invalidateQueries({ queryKey: ['registration', team.registrationCode] });
      setEditingName(false);
    },
  });
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const cancel = useMutation({
    mutationFn: () => athleteApi.cancelRegistration(team.id),
    onSuccess: () => {
      cache.setQueryData<Team[]>(['my-teams'], (previous = []) => previous.filter((item) => item.id !== team.id));
      void cache.invalidateQueries({ queryKey: ['my-teams'] });
      void cache.invalidateQueries({ queryKey: ['my-heats'] });
      void cache.invalidateQueries({ queryKey: ['championship', team.championship.slug] });
      void cache.invalidateQueries({ queryKey: ['registration', team.registrationCode] });
      void cache.invalidateQueries({ queryKey: ['checkout-status', team.registrationCode] });
      onClose();
    },
    onError: () => { void cache.invalidateQueries({ queryKey: ['my-teams'] }); },
  });
  const [heatId, setHeatId] = useState<number | null>(null);
  const heats = useQuery({ queryKey: ['my-heats'], queryFn: athleteApi.heats,
    enabled: team.status === 'CONFIRMED', refetchInterval: 20_000, meta: { activity: false } });
  const schedule = teamSchedule(heats.data?.heats ?? [], team);
  const selectedHeat = schedule.find((heat) => heat.heatId === heatId);
  const workoutHeading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { workoutHeading.current?.focus(); }, [selectedHeat?.heatId]);
  const championship = useQuery({ queryKey: ['championship', team.championship.slug],
    queryFn: () => publicApi.championship(team.championship.slug), enabled: !!selectedHeat, meta: { activity: false } });
  const workout = championship.data?.categories.find((category) => category.id === team.category.id)
    ?.workouts.find((item) => item.id === selectedHeat?.workoutId);

  function backToSchedule() {
    setHeatId(null);
    requestAnimationFrame(() => document.getElementById(`team-heat-${heatId}`)?.focus());
  }

  if (editingName && isCaptain) return <Dialog title="Alterar nome da equipe" onClose={() => { if (!rename.isPending) setEditingName(false); }}>
    <form onSubmit={event => { event.preventDefault(); rename.mutate(); }}>
      <label htmlFor="edit-team-name">Nome da equipe</label>
      <input id="edit-team-name" className="input" autoFocus required maxLength={80} value={teamName}
        onChange={event => setTeamName(event.target.value)} />
      <InlineError error={rename.error} />
      <div className="row-actions">
        <button type="button" className="btn btn--secondary" disabled={rename.isPending} onClick={() => setEditingName(false)}>Voltar</button>
        <button type="submit" className="btn btn--primary" disabled={rename.isPending || !teamName.trim()}>{rename.isPending ? 'Salvando…' : 'Salvar nome'}</button>
      </div>
    </form>
  </Dialog>;

  if (confirmingCancel && isCaptain) return (
    <Dialog title="Cancelar inscrição?" subtitle={`${team.name} · ${team.category.name}`}
      closeLabel="Voltar aos detalhes da inscrição" onClose={() => { if (!cancel.isPending) setConfirmingCancel(false); }}>
      <p>Sua inscrição no {team.championship.name} será cancelada, a vaga será liberada e a equipe sairá de Minhas equipes.</p>
      <p className="note">Você poderá se inscrever novamente enquanto as inscrições estiverem abertas e houver vagas.</p>
      <InlineError error={cancel.error} />
      <div className="row-actions">
        <button type="button" className="btn btn--secondary" autoFocus disabled={cancel.isPending}
          onClick={() => setConfirmingCancel(false)}>Manter inscrição</button>
        <button type="button" className="btn btn--danger" disabled={cancel.isPending} onClick={() => cancel.mutate()}>
          {cancel.isPending ? 'Cancelando…' : 'Sim, cancelar inscrição'}
        </button>
      </div>
    </Dialog>
  );

  return (
    <Dialog title={team.championship.name} subtitle={`${team.name} · ${team.category.name}`} onClose={onClose}>
      <div className="team-details__registration">
        <TeamBadge status={team.status} />
        {team.status === 'PENDING_PAYMENT' && <div className="row-actions">
          {!cancel.isPending && <Link className="btn btn--primary" to={`/pagamento/${team.registrationCode}`}>
            Pagar inscrição · {money(team.registrationAmount ?? team.category.price)}
          </Link>}
          {isCaptain && <button type="button" className="btn btn--danger" disabled={cancel.isPending}
            onClick={() => setConfirmingCancel(true)}>
            {cancel.isPending ? 'Cancelando…' : 'Cancelar a inscrição'}
          </button>}
        </div>}
        {isCaptain && team.members.length > 1 && team.status !== 'CANCELLED' && <button type="button" className="btn btn--secondary"
          onClick={() => { setTeamName(team.name); rename.reset(); setEditingName(true); }}>Alterar nome da equipe</button>}
      </div>
      <InlineError error={cancel.error} />
      {team.status === 'PENDING_PAYMENT' ? (
        <div className="team-details__empty"><h3>Confirme sua inscrição</h3><p>Sua equipe já está inscrita. Após a confirmação do pagamento, a organização poderá incluí-la nas baterias.</p></div>
      ) : team.status === 'CANCELLED' ? (
        <div className="team-details__empty"><h3>Inscrição cancelada</h3><p>Fale com a organização se precisar de informações sobre esta inscrição.</p></div>
      ) : selectedHeat ? (
        <section className="team-workout" aria-label={`Prova ${selectedHeat.workoutNumber}`}>
          <button className="team-details__back" type="button" onClick={backToSchedule}><ArrowLeft size={16} aria-hidden /> Voltar aos horários</button>
          <div className="team-workout__heading"><div><p className="team-workout__eyebrow">Prova {selectedHeat.workoutNumber} · Bateria {selectedHeat.heatNumber}</p>
            <h3 ref={workoutHeading} tabIndex={-1}>{selectedHeat.workoutName}</h3></div><HeatBadge status={selectedHeat.status} /></div>
          <p className="team-workout__schedule">{dayLabel(selectedHeat.scheduledAt)} · {clockTime(selectedHeat.scheduledAt)} · Raia {selectedHeat.lanes.find((lane) => lane.teamId === team.id)?.lane}</p>
          <HeatTiming heat={selectedHeat} />
          {championship.isPending && <LocalLoading label="Carregando prova" />}
          {championship.error && <ErrorState error={championship.error}
            action={<button className="btn btn--secondary" onClick={() => championship.refetch()}>Tentar novamente</button>} />}
          {workout && <div className="team-workout__content">
            <dl className="team-workout__facts">
              <div><dt>Formato</dt><dd>{workoutTypeLabel[workout.type]}</dd></div>
              {workout.timeCapSeconds != null && <div><dt>Time cap</dt><dd>{capLabel(workout.timeCapSeconds)}</dd></div>}
              {workout.repsPerRound != null && <div><dt>Repetições por round</dt><dd>{workout.repsPerRound}</dd></div>}
              {workout.hasTiebreak && <div><dt>Desempate</dt><dd>Com tiebreak</dd></div>}
            </dl>
            <h4>Descrição da prova</h4><p className="team-workout__description">{workout.description?.trim() || 'A organização ainda não publicou a descrição desta prova.'}</p>
          </div>}
          {championship.data && !workout && !championship.error && <p className="note">Esta prova foi atualizada pela organização. Volte aos horários para consultar a grade atual.</p>}
        </section>
      ) : (
        <section aria-labelledby="team-schedule-title" className="team-schedule">
          <div className="team-schedule__heading"><h3 id="team-schedule-title">Horários das baterias</h3><p>Horário de Brasília · Selecione uma bateria para ver a prova.</p></div>
          {heats.isPending && <LocalLoading label="Carregando baterias" />}
          {heats.error && <ErrorState error={heats.error}
            action={<button className="btn btn--secondary" onClick={() => heats.refetch()}>Tentar novamente</button>} />}
          {heats.data && schedule.length === 0 && <div className="team-details__empty"><p>A organização ainda não definiu as baterias desta equipe.</p></div>}
          <ol className="team-schedule__list">{schedule.map((heat) => <TeamHeat key={heat.heatId} heat={heat} teamId={team.id} onSelect={() => setHeatId(heat.heatId)} />)}</ol>
        </section>
      )}
    </Dialog>
  );
}

function TeamHeat({ heat, teamId, onSelect }: { heat: HeatView; teamId: number; onSelect: () => void }) {
  const lane = heat.lanes.find((item) => item.teamId === teamId);
  return <li><button id={`team-heat-${heat.heatId}`} type="button" className="team-schedule__heat" onClick={onSelect}>
    <span className="team-schedule__time"><strong>{clockTime(heat.scheduledAt)}</strong><span>{dayLabel(heat.scheduledAt)}</span></span>
    <span className="team-schedule__label"><strong>Bateria {heat.heatNumber}</strong><span>Prova {heat.workoutNumber}{lane ? ` · Raia ${lane.lane}` : ''}</span></span>
    <HeatBadge status={heat.status} /><ChevronRight size={18} className="team-schedule__arrow" aria-hidden />
  </button></li>;
}
