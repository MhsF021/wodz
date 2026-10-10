import { useEffect, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import { publicApi } from '../api/endpoints';
import type { ChampionshipSummary } from '../api/types';
import { UFS, dayBlock } from '../lib/format';
import { brandLogoFor } from '../lib/theme';
import { useAuth } from '../auth/AuthContext';
import { Empty, ErrorState, Loading } from '../components/States';
import { CHAMPIONSHIP_FILTERS, championshipFilter, type ChampionshipFilter } from '../lib/championshipFilters';

function statusTag(c: ChampionshipSummary) {
  if (c.status === 'IN_PROGRESS') return <span className="tag tag--live">Acontecendo agora</span>;
  if (c.status === 'FINISHED') return <span className="tag tag--muted">Encerrado</span>;
  if (c.registrationOpen) return <span className="tag tag--open">Inscrições abertas</span>;
  return <span className="tag tag--muted">Inscrições fechadas</span>;
}

export default function HomePage() {
  const { user, loading } = useAuth();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const uf = params.get('uf') ?? '';
  const filter = championshipFilter(params.get('status'));
  const [term, setTerm] = useState(q.toUpperCase());
  const [state, setState] = useState(uf);
  useEffect(() => { setTerm(q.toUpperCase()); setState(uf); }, [q, uf]);

  const query = useQuery({
    queryKey: ['search', q, uf, filter],
    queryFn: () => publicApi.search(q, uf, filter),
    placeholderData: keepPreviousData,
    meta: { activity: false },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const next = new URLSearchParams(params);
    next.delete('q'); next.delete('uf');
    if (term.trim()) next.set('q', term.trim());
    if (state) next.set('uf', state);
    setParams(next);
  }

  function selectFilter(nextFilter: ChampionshipFilter) {
    if (nextFilter === filter) return;
    const next = new URLSearchParams(params);
    if (nextFilter === 'ALL') next.delete('status');
    else next.set('status', nextFilter);
    setParams(next);
  }

  return (
    <div className="page">
      <section className="hero">
        <h1 className="hero__title">
          {/* Enquanto a sessão carrega o logo fica invisível: evita trocar de verde para laranja na frente do organizador. */}
          <img className={`hero__logo${loading ? ' is-pending' : ''}`} src={brandLogoFor(user?.role)} alt="WODZ"
            width={1152} height={300} fetchPriority="high" />
        </h1>
        <p className="hero__lead">
          A sua plataforma para campeonatos de cross.
        </p>
        <form className="search" role="search" onSubmit={submit}>
          <label className="sr-only" htmlFor="search-q">
            Nome do campeonato, cidade ou local
          </label>
          <input
            id="search-q"
            className="input search__input"
            placeholder="Nome do campeonato, cidade ou local"
            value={term}
            onChange={(e) => setTerm(e.target.value.toUpperCase())}
            autoComplete="off"
            autoCapitalize="characters"
          />
          <label className="sr-only" htmlFor="search-uf">
            Estado
          </label>
          <select id="search-uf" className="input search__select" value={state} onChange={(e) => setState(e.target.value)}>
            <option value="">Todo o Brasil</option>
            {UFS.map((u) => (
              <option key={u} value={u}>
                {u}
              </option>
            ))}
          </select>
          <button type="submit" className="btn btn--primary">
            <Search size={18} aria-hidden /> Buscar
          </button>
        </form>
      </section>

      <section aria-labelledby="results-title">
        <div className="champ-results__head">
          <h2 id="results-title" className="section-title">{q || uf ? 'Resultados da busca' : 'Campeonatos'}</h2>
          {query.isFetching && !query.isLoading && <span className="filter-loading" role="status" aria-label="Atualizando campeonatos"><span className="spinner" aria-hidden /></span>}
        </div>
        <div className="champ-filters" role="group" aria-label="Filtrar campeonatos por situação">
          {CHAMPIONSHIP_FILTERS.map((item) => (
            <button key={item.id} type="button" className="champ-filter" aria-pressed={filter === item.id}
              onClick={() => selectFilter(item.id)}>{item.label}</button>
          ))}
        </div>
        {query.isLoading && <Loading />}
        {query.error && <ErrorState error={query.error} action={<button className="btn btn--secondary" onClick={() => query.refetch()}>Tentar novamente</button>} />}
        {query.data && query.data.length === 0 && (
          <Empty title="Nenhum campeonato encontrado">
            <p>Tente outra situação, cidade ou estado.</p>
            {(q || uf || filter !== 'ALL') && <button className="btn btn--secondary" onClick={() => setParams({})}>Limpar filtros</button>}
          </Empty>
        )}
        {query.data && query.data.length > 0 && (
          <ul className="champ-cards" aria-busy={query.isPlaceholderData} inert={query.isPlaceholderData}>
            {query.data.map((c) => {
              const block = dayBlock(c.startDate, c.endDate);
              return (
                <li key={c.id}>
                  <article className="champ-card">
                    <div className="champ-card__header">
                      <span className="champ-card__date" aria-hidden>
                        <span className="champ-card__days">{block.days}</span>
                        <span className="champ-card__month">{block.month}</span>
                      </span>
                      {statusTag(c)}
                    </div>
                    <h3 className="champ-card__name">{c.name}</h3>
                    <p className="champ-card__place">{c.venueName}, {c.city}/{c.state}</p>
                    <div className="champ-card__actions">
                      <Link to={`/c/${c.slug}`} className="btn btn--secondary">Detalhes do evento</Link>
                      {c.registrationOpen && user?.role !== 'ORGANIZER' ? (
                        <Link to={`/c/${c.slug}/inscricao`} className="btn btn--primary">Inscrever-se</Link>
                      ) : (
                        <button className="btn btn--primary" disabled title={user?.role === 'ORGANIZER'
                          ? 'Entre com uma conta de atleta para se inscrever' : 'As inscrições deste evento estão fechadas'}>
                          Inscrever-se
                        </button>
                      )}
                    </div>
                    {c.registrationOpen && user?.role === 'ORGANIZER' && (
                      <p className="champ-card__note">Use uma conta de atleta para se inscrever.</p>
                    )}
                  </article>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
