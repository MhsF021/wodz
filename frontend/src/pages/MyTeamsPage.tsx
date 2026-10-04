import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { athleteApi } from '../api/endpoints';
import type { HeatView } from '../api/types';
import { clockTime, dateRange, dayLabel, money } from '../lib/format';
import { HeatBadge, TeamBadge } from '../components/StatusBadge';
import { LiveHeat } from '../components/LiveHeat';
import { Empty, ErrorState, Loading } from '../components/States';

export default function MyTeamsPage() {
  const teams = useQuery({ queryKey: ['my-teams'], queryFn: athleteApi.teams });
  const heats = useQuery({ queryKey: ['my-heats'], queryFn: athleteApi.heats, refetchInterval: 20_000 });

  if (teams.isLoading) return <Loading />;
  if (teams.error) return <div className="page"><ErrorState error={teams.error} /></div>;

  const myTeamIds = heats.data?.teamIds ?? [];
  const allHeats = heats.data?.heats ?? [];
  const live = allHeats.filter((h) => h.status === 'IN_PROGRESS');

  return (
    <div className="page">
      <h1 className="page-title">Minhas equipes</h1>

      {live.map((heat) => (
        <LiveHeat key={heat.heatId} heat={heat} highlightTeamIds={myTeamIds} />
      ))}

      <section aria-labelledby="my-heats-title" className="stack">
        <h2 id="my-heats-title" className="section-title">Suas baterias</h2>
        {heats.isLoading && <Loading />}
        {heats.error && <ErrorState error={heats.error} />}
        {heats.data && allHeats.length === 0 && (
          <Empty title="Nenhuma bateria definida ainda">
            <p>Quando a organização montar as baterias, o horário e a raia de cada prova aparecem aqui.</p>
          </Empty>
        )}
        {allHeats.length > 0 && (
          <ol className="my-heats">
            {allHeats.map((heat) => (
              <MyHeatRow key={heat.heatId} heat={heat} teamIds={myTeamIds} />
            ))}
          </ol>
        )}
      </section>

      <section aria-labelledby="my-teams-title" className="stack">
        <h2 id="my-teams-title" className="section-title">Inscrições</h2>
        {teams.data && teams.data.length === 0 && (
          <Empty title="Você ainda não inscreveu nenhuma equipe">
            <Link className="btn btn--primary" to="/">Encontrar um campeonato</Link>
          </Empty>
        )}
        <ul className="team-list">
          {teams.data?.map((team) => (
            <li key={team.id} className="team-card">
              <div className="team-card__head">
                <div>
                  <p className="team-card__name">{team.name}</p>
                  <p className="team-card__meta">
                    <Link to={`/c/${team.championship.slug}`}>{team.championship.name}</Link>
                    {', '}categoria {team.category.name}, {dateRange(team.championship.startDate, team.championship.endDate)}
                  </p>
                </div>
                <TeamBadge status={team.status} />
              </div>
              <p className="team-card__members">{team.members.map((m) => m.fullName).join(', ')}</p>
              {team.status === 'PENDING_PAYMENT' && (
                <div className="team-card__actions">
                  <Link className="btn btn--primary" to={`/pagamento/${team.registrationCode}`}>
                    Pagar inscrição ({money(team.category.price)})
                  </Link>
                </div>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

function MyHeatRow({ heat, teamIds }: { heat: HeatView; teamIds: number[] }) {
  const lane = heat.lanes.find((l) => teamIds.includes(l.teamId));
  return (
    <li className={`my-heat my-heat--${heat.status.toLowerCase()}`}>
      <div className="my-heat__time">
        <span className="my-heat__clock">{clockTime(heat.scheduledAt)}</span>
        <span className="my-heat__day">{dayLabel(heat.scheduledAt)}</span>
      </div>
      <div className="my-heat__main">
        <p className="my-heat__title">
          Prova {heat.workoutNumber}: {heat.workoutName}
        </p>
        <p className="my-heat__meta">
          <Link to={`/c/${heat.championshipSlug}?aba=baterias`}>{heat.championshipName}</Link>, {heat.categoryName},
          bateria {heat.heatNumber}
          {lane ? `, raia ${lane.lane} (${lane.teamName})` : ''}
        </p>
      </div>
      <HeatBadge status={heat.status} />
    </li>
  );
}
