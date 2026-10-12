import { Link, useParams, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { CalendarDays, MapPin } from 'lucide-react';
import { publicApi } from '../api/endpoints';
import type { ChampionshipDetail } from '../api/types';
import { dateRange } from '../lib/format';
import { useChampionshipStream } from '../lib/useChampionshipStream';
import { useAuth } from '../auth/AuthContext';
import { Tabs } from '../components/Tabs';
import { ScheduleBoard } from '../components/ScheduleBoard';
import { LeaderboardView } from '../components/LeaderboardView';
import { ErrorState, Loading } from '../components/States';
import { ChampionshipHero } from '../components/ChampionshipHero';
import { CategoryCards } from '../components/CategoryCards';

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
    <div className="championship-page">
      <ChampionshipHero name={c.name} cover={c.bannerUrl} profile={c.profileUrl} actions={<>

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
      </>}>
        <span className={`champ-hero__status${c.registrationOpen ? ' is-open' : ''}`}>{c.registrationOpen ? 'Inscrições abertas' : 'Inscrições fechadas'}</span>
        <p className="champ-head__meta">
          <span>
            <CalendarDays size={18} aria-hidden /> {dateRange(c.startDate, c.endDate)}
          </span>
          <span>
            <MapPin size={18} aria-hidden /> {c.venueName}, {c.city}/{c.state}
          </span>
        </p>
      </ChampionshipHero>

      <div className="page championship-page__body">
      <Tabs
        label="Seções do campeonato"
        items={TABS}
        active={tab}
        onChange={(id) => setParams(id === 'visao-geral' ? {} : { aba: id }, { replace: true })}
      />

      <div key={tab} role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="tab-panel">
        {tab === 'visao-geral' && <CategoryCards championship={c} />}
        {tab === 'baterias' && <ScheduleTab slug={slug} championship={c} />}
        {tab === 'leaderboard' && <LeaderboardView slug={slug} categories={c.categories} />}
      </div>
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
