import { Link, useParams, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { CalendarDays, MapPin } from 'lucide-react';
import { publicApi } from '../api/endpoints';
import type { ChampionshipDetail } from '../api/types';
import { capLabel, dateRange, money, workoutTypeLabel } from '../lib/format';
import { useChampionshipStream } from '../lib/useChampionshipStream';
import { useAuth } from '../auth/AuthContext';
import { Tabs } from '../components/Tabs';
import { PlateChip } from '../components/Plate';
import { ScheduleBoard } from '../components/ScheduleBoard';
import { LeaderboardView } from '../components/LeaderboardView';
import { ErrorState, Loading } from '../components/States';

type Tab = 'visao-geral' | 'baterias' | 'leaderboard';
const TABS: { id: Tab; label: string }[] = [
  { id: 'visao-geral', label: 'Categorias e provas' },
  { id: 'baterias', label: 'Baterias' },
  { id: 'leaderboard', label: 'Leaderboard' },
];

export default function ChampionshipPage() {
  const { slug = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const tab = (TABS.some((t) => t.id === params.get('aba')) ? params.get('aba') : 'visao-geral') as Tab;
  const { user } = useAuth();
  useChampionshipStream(slug);

  const query = useQuery({ queryKey: ['championship', slug], queryFn: () => publicApi.championship(slug) });

  if (query.isLoading) return <Loading />;
  if (query.error || !query.data) return <div className="page"><ErrorState error={query.error} /></div>;
  const c = query.data;

  return (
    <div className="page">
      <header className="champ-head">
        <h1 className="champ-head__title">{c.name}</h1>
        <p className="champ-head__meta">
          <span>
            <CalendarDays size={18} aria-hidden /> {dateRange(c.startDate, c.endDate)}
          </span>
          <span>
            <MapPin size={18} aria-hidden /> {c.venueName}, {c.city}/{c.state}
          </span>
        </p>
        <div className="champ-head__actions">
          {c.registrationOpen ? (
            user?.role === 'ORGANIZER' ? (
              <p className="note">Inscrições abertas. Entre com uma conta de atleta para inscrever uma equipe.</p>
            ) : (
              <Link className="btn btn--primary btn--large" to={`/c/${c.slug}/inscricao`}>
                Inscrever equipe
              </Link>
            )
          ) : (
            <p className="note">Inscrições fechadas.</p>
          )}
        </div>
      </header>

      <Tabs
        label="Seções do campeonato"
        items={TABS}
        active={tab}
        onChange={(id) => setParams(id === 'visao-geral' ? {} : { aba: id }, { replace: true })}
      />

      <div key={tab} role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="tab-panel">
        {tab === 'visao-geral' && <Overview championship={c} />}
        {tab === 'baterias' && <ScheduleTab slug={slug} championship={c} />}
        {tab === 'leaderboard' && <LeaderboardView slug={slug} categories={c.categories} />}
      </div>
    </div>
  );
}

function ScheduleTab({ slug, championship }: { slug: string; championship: ChampionshipDetail }) {
  const query = useQuery({ queryKey: ['schedule', slug], queryFn: () => publicApi.schedule(slug) });
  if (query.isLoading) return <Loading />;
  if (query.error) return <ErrorState error={query.error} />;
  return <ScheduleBoard heats={query.data ?? []} categories={championship.categories} />;
}

function Overview({ championship }: { championship: ChampionshipDetail }) {
  return (
    <div className="overview">
      {championship.description && <p className="overview__description">{championship.description}</p>}
      {championship.categories.map((category, index) => (
        <section key={category.id} className="category-block" aria-labelledby={`cat-${category.id}`}>
          <header className="category-block__head">
            <h2 id={`cat-${category.id}`} className="category-block__title">
              <PlateChip index={index} name={category.name} />
            </h2>
            <dl className="facts">
              <div>
                <dt>Equipe</dt>
                <dd>{category.teamSize} atleta{category.teamSize > 1 ? 's' : ''}</dd>
              </div>
              <div>
                <dt>Inscrição</dt>
                <dd>{Number(category.price) === 0 ? 'Gratuita' : money(category.price)}</dd>
              </div>
              <div>
                <dt>Vagas</dt>
                <dd>
                  {category.slotsLeft === null || category.slotsLeft === undefined
                    ? 'Sem limite'
                    : category.slotsLeft > 0
                      ? `${category.slotsLeft} restantes`
                      : 'Esgotada'}
                </dd>
              </div>
            </dl>
          </header>
          {category.description && <p className="category-block__desc">{category.description}</p>}
          {category.workouts.length === 0 ? (
            <p className="note">As provas desta categoria serão divulgadas em breve.</p>
          ) : (
            <ol className="wod-list">
              {category.workouts.map((w) => (
                <li key={w.id} className="wod">
                  <p className="wod__number">Prova {w.number}</p>
                  <h3 className="wod__name">{w.name}</h3>
                  <p className="wod__meta">
                    {workoutTypeLabel[w.type]}
                    {capLabel(w.timeCapSeconds) ? `, cap ${capLabel(w.timeCapSeconds)}` : ''}
                  </p>
                  {w.description && <p className="wod__text">{w.description}</p>}
                </li>
              ))}
            </ol>
          )}
        </section>
      ))}
    </div>
  );
}
