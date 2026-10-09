import { useEffect, useRef, useState, type MouseEvent } from 'react';
import { Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { athleteApi, paymentApi } from '../api/endpoints';
import type { HeatView } from '../api/types';
import { clockTime, dateRange, dayLabel, money } from '../lib/format';
import { HeatBadge, TeamBadge } from '../components/StatusBadge';
import { LiveHeat } from '../components/LiveHeat';
import { HeatTiming } from '../components/HeatTiming';
import { Empty, ErrorState, Loading } from '../components/States';
import { ActionFeedback } from '../components/ActionFeedback';
import { openExternalWindow, subscribeExternalResult } from '../lib/externalWindow';

export default function MyTeamsPage() {
  const queryClient = useQueryClient();
  const [paymentCode, setPaymentCode] = useState<string | null>(null);
  const paymentWindow = useRef<Window | null>(null);
  const [windowActive, setWindowActive] = useState(false);
  const [popupError, setPopupError] = useState<string | null>(null);
  const teams = useQuery({ queryKey: ['my-teams'], queryFn: athleteApi.teams });
  const heats = useQuery({ queryKey: ['my-heats'], queryFn: athleteApi.heats, refetchInterval: 20_000 });
  const returnedPayment = useQuery({
    queryKey: ['my-team-payment', paymentCode],
    queryFn: async () => {
      const registration = await paymentApi.summary(paymentCode!);
      const payment = registration.lastPayment;
      return payment?.status === 'PENDING' ? paymentApi.status(paymentCode!, payment.id) : payment ?? null;
    },
    enabled: Boolean(paymentCode),
    refetchInterval: (query) => windowActive && !query.state.data || query.state.data?.status === 'PENDING' ? 5000 : false,
    refetchIntervalInBackground: true,
  });

  useEffect(() => subscribeExternalResult('payment', (result) => {
    if (!teams.data?.some((team) => team.registrationCode === result.code)) return;
    setPaymentCode(result.code!);
    queryClient.invalidateQueries({ queryKey: ['my-team-payment', result.code] });
    queryClient.invalidateQueries({ queryKey: ['my-teams'] });
  }), [teams.data, queryClient]);

  useEffect(() => {
    if (returnedPayment.data?.status === 'APPROVED') {
      queryClient.invalidateQueries({ queryKey: ['my-teams'] });
    }
  }, [returnedPayment.data?.status, queryClient]);

  useEffect(() => {
    if (!windowActive) return;
    const timer = window.setInterval(() => {
      if (!paymentWindow.current?.closed) return;
      setWindowActive(false);
      paymentWindow.current = null;
      queryClient.invalidateQueries({ queryKey: ['my-team-payment', paymentCode] });
      queryClient.invalidateQueries({ queryKey: ['my-teams'] });
    }, 750);
    return () => window.clearInterval(timer);
  }, [windowActive, paymentCode, queryClient]);

  function openPayment(event: MouseEvent<HTMLAnchorElement>, registrationCode: string) {
    event.preventDefault();
    if (paymentCode === registrationCode && paymentWindow.current && !paymentWindow.current.closed) {
      paymentWindow.current.focus();
      return;
    }
    const opened = openExternalWindow(event.currentTarget.href, `wodz-payment-${registrationCode}`);
    if (!opened) {
      setPopupError('Permita novas janelas no navegador para abrir o pagamento.');
      return;
    }
    paymentWindow.current = opened;
    setPaymentCode(registrationCode);
    setWindowActive(true);
    setPopupError(null);
  }

  if (teams.isLoading) return <Loading />;
  if (teams.error) return <div className="page"><ErrorState error={teams.error} /></div>;

  const myTeamIds = heats.data?.teamIds ?? [];
  const allHeats = heats.data?.heats ?? [];
  const live = allHeats.filter((h) => h.status === 'IN_PROGRESS');

  return (
    <div className="page">
      <h1 className="page-title">Minhas equipes</h1>
      {popupError && <ActionFeedback status="error" label={popupError} />}
      {paymentCode && (
        <ActionFeedback
          key={`${paymentCode}-${returnedPayment.data?.status ?? 'waiting'}`}
          status={returnedPayment.data?.status === 'APPROVED' ? 'success'
            : returnedPayment.error || returnedPayment.data && returnedPayment.data.status !== 'PENDING' ? 'error' : 'pending'}
          label={returnedPayment.data?.status === 'APPROVED' ? 'Pagamento aprovado. Inscrição confirmada.'
            : returnedPayment.data?.status === 'REJECTED' ? 'Pagamento recusado. Tente outra forma de pagamento.'
              : returnedPayment.data?.status === 'CANCELLED' ? 'Pagamento cancelado.'
                : returnedPayment.data?.status === 'EXPIRED' ? 'A cobrança expirou. Você pode gerar uma nova.'
                  : returnedPayment.data?.status === 'REFUNDED' ? 'O pagamento foi devolvido.'
                    : returnedPayment.error ? 'Não foi possível conferir o pagamento. Tente novamente em instantes.'
                      : windowActive ? 'Aguardando confirmação do pagamento na nova janela.' : 'Pagamento ainda não concluído. Você pode retomar na nova janela.'}
        />
      )}

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
                  <Link className="btn btn--primary" to={`/pagamento/${team.registrationCode}?checkout=1`}
                    target="_blank" rel="noopener noreferrer" onClick={(event) => openPayment(event, team.registrationCode)}>
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
        <HeatTiming heat={heat} />
      </div>
      <HeatBadge status={heat.status} />
    </li>
  );
}
