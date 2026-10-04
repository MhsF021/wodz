import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { adminApi } from '../../api/endpoints';
import { dateRange, money } from '../../lib/format';
import { ChampionshipBadge } from '../../components/StatusBadge';
import { Empty, ErrorState, Loading } from '../../components/States';

export default function AdminHome() {
  const query = useQuery({ queryKey: ['admin-championships'], queryFn: adminApi.championships });

  return (
    <div className="page">
      <div className="page-head">
        <h1 className="page-title">Seus campeonatos</h1>
        <Link className="btn btn--primary" to="/organizador/campeonatos/novo">
          <Plus size={18} aria-hidden /> Novo campeonato
        </Link>
      </div>
      {query.isLoading && <Loading />}
      {query.error && <ErrorState error={query.error} />}
      {query.data && query.data.length === 0 && (
        <Empty title="Você ainda não cadastrou nenhum campeonato">
          <p>Comece pelo nome, local e data. Categorias, provas e baterias vêm depois.</p>
        </Empty>
      )}
      <ul className="admin-list">
        {query.data?.map((c) => (
          <li key={c.id}>
            <Link to={`/organizador/campeonatos/${c.id}`} className="admin-row">
              <span className="admin-row__main">
                <span className="admin-row__name">{c.name}</span>
                <span className="admin-row__meta">
                  {dateRange(c.startDate, c.endDate)}, {c.venueName}, {c.city}/{c.state}
                </span>
              </span>
              <span className="admin-row__stats">
                <span><strong>{c.teamsConfirmed}</strong> confirmadas</span>
                <span><strong>{c.teamsPending}</strong> aguardando pagamento</span>
                <span><strong>{money(c.revenue)}</strong> recebidos</span>
              </span>
              <span className="admin-row__status">
                <ChampionshipBadge status={c.status} />
                {c.registrationOpen && <span className="tag tag--open">Inscrições abertas</span>}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
