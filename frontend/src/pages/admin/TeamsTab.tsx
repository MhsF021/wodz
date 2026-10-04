import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import type { ChampionshipDetail, TeamStatus } from '../../api/types';
import { dateTime, methodLabel } from '../../lib/format';
import { CategoryPicker, PlateChip } from '../../components/Plate';
import { CopyButton } from '../../components/CopyButton';
import { PaymentBadge, TeamBadge } from '../../components/StatusBadge';
import { Empty, ErrorState, InlineError, Loading } from '../../components/States';

export function TeamsTab({ championship }: { championship: ChampionshipDetail }) {
  const queryClient = useQueryClient();
  const [categoryId, setCategoryId] = useState<number | null>(null);
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
  const teams = useMemo(
    () =>
      (query.data ?? []).filter(
        (t) => (categoryId === null || t.categoryId === categoryId) && (status === '' || t.status === status),
      ),
    [query.data, categoryId, status],
  );
  const counts = useMemo(() => {
    const base = (query.data ?? []).filter((t) => categoryId === null || t.categoryId === categoryId);
    return {
      confirmed: base.filter((t) => t.status === 'CONFIRMED').length,
      pending: base.filter((t) => t.status === 'PENDING_PAYMENT').length,
      cancelled: base.filter((t) => t.status === 'CANCELLED').length,
    };
  }, [query.data, categoryId]);

  if (query.isLoading) return <Loading />;
  if (query.error) return <ErrorState error={query.error} />;

  return (
    <div className="stack">
      <div className="toolbar">
        <CategoryPicker categories={championship.categories} value={categoryId} onChange={setCategoryId} allowAll />
        <label className="sr-only" htmlFor="team-status">Situação</label>
        <select id="team-status" className="input input--inline" value={status}
          onChange={(e) => setStatus(e.target.value as TeamStatus | '')}>
          <option value="">Todas as situações</option>
          <option value="CONFIRMED">Confirmadas</option>
          <option value="PENDING_PAYMENT">Aguardando pagamento</option>
          <option value="CANCELLED">Canceladas</option>
        </select>
      </div>

      <p className="counts">
        <span><strong>{counts.confirmed}</strong> confirmadas</span>
        <span><strong>{counts.pending}</strong> aguardando pagamento</span>
        <span><strong>{counts.cancelled}</strong> canceladas</span>
      </p>

      <InlineError error={change.error} />

      {teams.length === 0 ? (
        <Empty title="Nenhuma equipe nesta seleção">
          <p>Divulgue o link do formulário de inscrição (aba Dados e inscrições).</p>
        </Empty>
      ) : (
        <div className="table-scroll">
          <table className="table table--stack">
            <thead>
              <tr>
                <th scope="col">Equipe</th>
                <th scope="col">Categoria</th>
                <th scope="col">Atletas</th>
                <th scope="col">Capitão</th>
                <th scope="col">Inscrição</th>
                <th scope="col">Pagamento</th>
                <th scope="col"><span className="sr-only">Ações</span></th>
              </tr>
            </thead>
            <tbody>
              {teams.map((t) => {
                const payLink = `${window.location.origin}/pagamento/${t.registrationCode}`;
                return (
                  <tr key={t.teamId}>
                    <td>
                      <strong>{t.teamName}</strong>
                      {t.boxName && <span className="table__sub">{t.boxName}</span>}
                    </td>
                    <td data-label="Categoria"><PlateChip index={plateIndex(t.categoryId)} name={t.categoryName} /></td>
                    <td className="table__wrap" data-label="Atletas">{t.memberNames}</td>
                    <td data-label="Capitão">
                      {t.captainName}
                      <span className="table__sub">{t.captainEmail}</span>
                      {t.captainPhone && <span className="table__sub">{t.captainPhone}</span>}
                    </td>
                    <td data-label="Inscrição">
                      <TeamBadge status={t.status} />
                      <span className="table__sub">{dateTime(t.createdAt)}</span>
                    </td>
                    <td data-label="Pagamento">
                      {t.lastPaymentStatus ? (
                        <>
                          <PaymentBadge status={t.lastPaymentStatus} />
                          {t.lastPaymentMethod && <span className="table__sub">{methodLabel[t.lastPaymentMethod]}</span>}
                        </>
                      ) : (
                        <span className="table__sub">Nenhuma tentativa</span>
                      )}
                    </td>
                    <td>
                      <div className="table__actions">
                        {t.status === 'PENDING_PAYMENT' && <CopyButton value={payLink} label="Link de pagamento" />}
                        {t.status !== 'CONFIRMED' && (
                          <button type="button" className="btn btn--secondary btn--small"
                            onClick={() => window.confirm(`Confirmar ${t.teamName} sem pagamento online (ex.: pago em dinheiro)?`)
                              && change.mutate({ id: t.teamId, next: 'CONFIRMED' })}>
                            Confirmar
                          </button>
                        )}
                        {t.status !== 'CANCELLED' && (
                          <button type="button" className="btn btn--danger btn--small"
                            onClick={() => window.confirm(`Cancelar a inscrição de ${t.teamName}? A equipe sai das baterias que ainda não começaram.`)
                              && change.mutate({ id: t.teamId, next: 'CANCELLED' })}>
                            Cancelar
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
