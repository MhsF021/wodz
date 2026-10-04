import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink } from 'lucide-react';
import { adminApi } from '../../api/endpoints';
import type { ChampionshipDetail, ChampionshipStatus } from '../../api/types';
import { championshipStatusLabel, dateRange } from '../../lib/format';
import { Tabs } from '../../components/Tabs';
import { CopyButton } from '../../components/CopyButton';
import { ChampionshipBadge } from '../../components/StatusBadge';
import { Empty, ErrorState, InlineError, Loading } from '../../components/States';
import { LeaderboardView } from '../../components/LeaderboardView';
import { ChampionshipForm } from './ChampionshipForm';
import { CategoriesTab } from './CategoriesTab';
import { WorkoutsTab } from './WorkoutsTab';
import { TeamsTab } from './TeamsTab';
import { HeatsTab } from './HeatsTab';
import { ScoresTab } from './ScoresTab';

type Tab = 'dados' | 'categorias' | 'provas' | 'equipes' | 'baterias' | 'pontuacao' | 'leaderboard';
const TABS: { id: Tab; label: string }[] = [
  { id: 'dados', label: 'Dados e inscrições' },
  { id: 'categorias', label: 'Categorias' },
  { id: 'provas', label: 'Provas' },
  { id: 'equipes', label: 'Equipes' },
  { id: 'baterias', label: 'Baterias' },
  { id: 'pontuacao', label: 'Pontuação' },
  { id: 'leaderboard', label: 'Leaderboard' },
];

export default function AdminChampionshipPage() {
  const id = Number(useParams().id);
  const [params, setParams] = useSearchParams();
  const tab = (TABS.some((t) => t.id === params.get('aba')) ? params.get('aba') : 'dados') as Tab;
  const query = useQuery({ queryKey: ['admin-championship', id], queryFn: () => adminApi.championship(id) });

  if (query.isLoading) return <Loading />;
  if (query.error || !query.data) return <div className="page"><ErrorState error={query.error} /></div>;
  const c = query.data;

  return (
    <div className="page">
      <p className="crumb"><Link to="/organizador">Seus campeonatos</Link></p>
      <header className="admin-head">
        <div>
          <h1 className="page-title">{c.name}</h1>
          <p className="admin-head__meta">
            {dateRange(c.startDate, c.endDate)}, {c.venueName}, {c.city}/{c.state}
          </p>
        </div>
        <div className="admin-head__status">
          <ChampionshipBadge status={c.status} />
          {c.status !== 'DRAFT' && (
            <Link className="btn btn--ghost btn--small" to={`/c/${c.slug}`} target="_blank">
              Página pública <ExternalLink size={14} aria-hidden />
            </Link>
          )}
        </div>
      </header>

      <Tabs
        label="Gestão do campeonato"
        items={TABS}
        active={tab}
        onChange={(t) => setParams(t === 'dados' ? {} : { aba: t }, { replace: true })}
      />

      <div key={tab} role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="tab-panel">
        {tab === 'dados' && <DataTab championship={c} />}
        {tab === 'categorias' && <CategoriesTab championship={c} />}
        {tab === 'provas' && <WorkoutsTab championship={c} />}
        {tab === 'equipes' && <TeamsTab championship={c} />}
        {tab === 'baterias' && <HeatsTab championship={c} />}
        {tab === 'pontuacao' && <ScoresTab championship={c} />}
        {tab === 'leaderboard' &&
          (c.status === 'DRAFT' ? (
            <Empty title="O leaderboard aparece depois que o campeonato for publicado" />
          ) : (
            <LeaderboardView slug={c.slug} categories={c.categories} />
          ))}
      </div>
    </div>
  );
}

const STATUSES: ChampionshipStatus[] = ['DRAFT', 'PUBLISHED', 'IN_PROGRESS', 'FINISHED', 'CANCELLED'];

function DataTab({ championship: c }: { championship: ChampionshipDetail }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<ChampionshipStatus>(c.status);
  const origin = window.location.origin;
  const formLink = `${origin}/c/${c.slug}/inscricao`;
  const publicLink = `${origin}/c/${c.slug}`;

  const refresh = (data: ChampionshipDetail) => {
    queryClient.setQueryData(['admin-championship', c.id], data);
    queryClient.invalidateQueries({ queryKey: ['admin-championships'] });
    setStatus(data.status);
  };

  const registration = useMutation({ mutationFn: (open: boolean) => adminApi.setRegistration(c.id, open), onSuccess: refresh });
  const statusMutation = useMutation({ mutationFn: () => adminApi.setStatus(c.id, status), onSuccess: refresh });

  return (
    <div className="stack">
      <section className="panel" aria-labelledby="pub-title">
        <h2 id="pub-title" className="panel__title">Formulário de inscrição</h2>
        <p className="panel__lead">
          {c.registrationOpen
            ? 'As inscrições estão abertas: atletas já conseguem inscrever equipes e pagar online.'
            : c.started
              ? 'O campeonato já começou: as inscrições ficam fechadas a partir do dia de início.'
              : c.registrationFlag
                ? 'O formulário está publicado, mas fora do período de inscrição definido.'
                : 'O formulário está fechado. Ao abrir, o campeonato também fica visível na busca.'}
        </p>
        <div className="row-actions">
          <button
            type="button"
            className={c.registrationFlag ? 'btn btn--secondary' : 'btn btn--primary'}
            disabled={registration.isPending || (c.started && !c.registrationFlag)}
            onClick={() => registration.mutate(!c.registrationFlag)}
          >
            {c.registrationFlag ? 'Fechar inscrições' : 'Abrir inscrições'}
          </button>
        </div>
        <InlineError error={registration.error} />

        <dl className="links">
          <div>
            <dt>Link do formulário de inscrição</dt>
            <dd>
              <code>{formLink}</code>
              <CopyButton value={formLink} label="Copiar link" />
            </dd>
          </div>
          <div>
            <dt>Página pública (baterias e leaderboard)</dt>
            <dd>
              <code>{publicLink}</code>
              <CopyButton value={publicLink} label="Copiar link" />
            </dd>
          </div>
        </dl>
        <p className="note">
          Cada equipe recebe um link de pagamento próprio ao se inscrever. Você encontra e pode reenviar esses links na aba Equipes.
        </p>
      </section>

      <section className="panel" aria-labelledby="status-title">
        <h2 id="status-title" className="panel__title">Situação do campeonato</h2>
        <div className="row-actions">
          <label className="sr-only" htmlFor="ch-status">Situação</label>
          <select id="ch-status" className="input input--inline" value={status}
            onChange={(e) => setStatus(e.target.value as ChampionshipStatus)}>
            {STATUSES.map((s) => <option key={s} value={s}>{championshipStatusLabel[s]}</option>)}
          </select>
          <button type="button" className="btn btn--secondary" disabled={status === c.status || statusMutation.isPending}
            onClick={() => statusMutation.mutate()}>
            Atualizar situação
          </button>
        </div>
        <InlineError error={statusMutation.error} />
      </section>

      <section aria-labelledby="edit-title">
        <h2 id="edit-title" className="section-title">Editar dados</h2>
        <ChampionshipForm
          key={c.id}
          initial={c}
          submitLabel="Salvar alterações"
          onSubmit={async (input) => refresh(await adminApi.update(c.id, input))}
        />
      </section>
    </div>
  );
}
