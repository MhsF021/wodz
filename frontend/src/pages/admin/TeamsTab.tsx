import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import type { ChampionshipDetail, TeamStatus } from '../../api/types';
import { dateTime, methodLabel } from '../../lib/format';
import { searchTeams } from '../../lib/teamSearch';
import { PlateChip } from '../../components/Plate';
import { Search, Shirt } from 'lucide-react';
import { CopyButton } from '../../components/CopyButton';
import { PaymentBadge, TeamBadge } from '../../components/StatusBadge';
import { Empty, ErrorState, InlineError, Loading } from '../../components/States';

export function TeamsTab({ championship }: { championship: ChampionshipDetail }) {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<TeamStatus | ''>('');
  const query = useQuery({ queryKey: ['admin-teams', championship.id], queryFn: () => adminApi.teams(championship.id) });

  const change = useMutation({
    mutationFn: ({ id, next }: { id: number; next: TeamStatus }) => adminApi.setTeamStatus(id, next),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-teams', championship.id] });
      queryClient.invalidateQueries({ queryKey: ['admin-championship', championship.id] });
      queryClient.invalidateQueries({ queryKey: ['admin-heats', championship.id] });
    },
  });

  const plateIndex = (id: number) => championship.categories.findIndex((c) => c.id === id);
  const matchingTeams = useMemo(() => searchTeams(query.data ?? [], search), [query.data, search]);
  const teams = useMemo(() => matchingTeams.filter(t => status === '' || t.status === status), [matchingTeams, status]);
  const counts = useMemo(() => {
    const base = matchingTeams;
    return {
      confirmed: base.filter((t) => t.status === 'CONFIRMED').length,
      pending: base.filter((t) => t.status === 'PENDING_PAYMENT').length,
      cancelled: base.filter((t) => t.status === 'CANCELLED').length,
    };
  }, [matchingTeams]);

  if (query.isLoading) return <Loading />;
  if (query.error) return <ErrorState error={query.error} />;

  return (
    <div className="stack">
      <div className="toolbar team-toolbar">
        <div className="team-search">
          <Search size={18} aria-hidden="true" />
          <label className="sr-only" htmlFor="team-search">Buscar por equipe ou capitão</label>
          <input id="team-search" className="input" type="search" placeholder="Buscar por equipe ou capitão"
            value={search} onChange={e => setSearch(e.target.value)} />
        </div>
        <label className="sr-only" htmlFor="team-status">Situação</label>
        <select id="team-status" className="input input--inline" value={status}
          onChange={(e) => setStatus(e.target.value as TeamStatus | '')}>
          <option value="">Todas as situações</option>
          <option value="CONFIRMED">Confirmadas</option>
          <option value="PENDING_PAYMENT">Aguardando pagamento</option>
          <option value="CANCELLED">Canceladas</option>
        </select>
      </div>

      <p className="counts" role="status" aria-live="polite">
        <span><strong>{counts.confirmed}</strong> confirmadas</span>
        <span><strong>{counts.pending}</strong> aguardando pagamento</span>
        <span><strong>{counts.cancelled}</strong> canceladas</span>
      </p>

      <InlineError error={change.error} />

      {teams.length === 0 ? (
        <Empty title={search.trim() || status ? 'Nenhuma equipe encontrada' : 'Nenhuma equipe inscrita'}>
          <p>{search.trim() || status ? 'Tente outro nome ou outra situação.' : 'Divulgue o link do formulário de inscrição (aba Dados e inscrições).'}</p>
        </Empty>
      ) : (
        <div className="organizer-teams">
          {teams.map((t) => {
            const payLink = `${window.location.origin}/pagamento/${t.registrationCode}`;
            return (
              <article className="organizer-team" key={t.teamId} aria-labelledby={`team-${t.teamId}`}>
                <header className="organizer-team__head">
                  <div>
                    <h3 id={`team-${t.teamId}`}>{t.teamName}</h3>
                    {(t.boxName || t.city) && <p className="organizer-team__location">{[t.boxName, t.city].filter(Boolean).join(' · ')}</p>}
                    <PlateChip index={plateIndex(t.categoryId)} name={t.categoryName} />
                  </div>
                  <TeamBadge status={t.status} />
                </header>
                <div className="organizer-team__body">
                  <section className="organizer-team__athletes" aria-label="Atletas e tamanhos de camisa">
                    <h4>Atletas <span className="badge badge--muted">{t.memberCount}</span></h4>
                    <ul className="organizer-team__members">
                      {t.members.map(member => <li key={member.id}>
                        <span>{member.fullName}{member.captain && <small>Capitão</small>}</span>
                        <span className="organizer-team__shirt"><Shirt size={16} aria-hidden="true" />
                          <span><span className="sr-only">Camisa: </span>{member.shirtSize ?? 'Não informado'}</span>
                        </span>
                      </li>)}
                    </ul>
                  </section>
                  <div className="organizer-team__details">
                    <section aria-label="Contato do capitão">
                      <h4>Contato do capitão</h4>
                      <p>{t.captainName}</p>
                      <a href={`mailto:${t.captainEmail}`}>{t.captainEmail}</a>
                      {t.captainPhone && <a href={`tel:${t.captainPhone.replace(/[^+\d]/g, '')}`}>{t.captainPhone}</a>}
                    </section>
                    <section aria-label="Pagamento">
                      <h4>Pagamento</h4>
                  {t.lastPaymentStatus ? (
                    <>
                      <PaymentBadge status={t.lastPaymentStatus} />
                      {t.lastPaymentMethod && <span className="table__sub">{methodLabel[t.lastPaymentMethod]}</span>}
                    </>
                  ) : (
                      <span className="table__sub">Nenhuma tentativa</span>
                  )}
                    </section>
                  </div>
                </div>
                <footer className="organizer-team__footer">
                  <span className="organizer-team__date">Inscrita em {dateTime(t.createdAt)}</span>
                  <div className="row-actions">
                    {t.status === 'PENDING_PAYMENT' && <CopyButton value={payLink} label="Link de pagamento" />}
                    {championship.status !== 'FINISHED' && !championship.started && championship.status !== 'IN_PROGRESS' && t.status !== 'CONFIRMED' && (
                      <button type="button" className="btn btn--secondary btn--small"
                        disabled={change.isPending} onClick={() => window.confirm(`Confirmar ${t.teamName} sem pagamento online (ex.: pago em dinheiro)?`)
                          && change.mutate({ id: t.teamId, next: 'CONFIRMED' })}>
                        Confirmar
                      </button>
                    )}
                    {championship.status !== 'FINISHED' && !championship.started && championship.status !== 'IN_PROGRESS' && t.status !== 'CANCELLED' && (
                      <button type="button" className="btn btn--danger btn--small"
                        disabled={change.isPending} onClick={() => window.confirm(`Cancelar a inscrição de ${t.teamName}? A equipe sai das baterias que ainda não começaram.`)
                          && change.mutate({ id: t.teamId, next: 'CANCELLED' })}>
                        Cancelar
                      </button>
                    )}
                  </div>
                </footer>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
