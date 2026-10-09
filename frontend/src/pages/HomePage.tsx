import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import { publicApi } from '../api/endpoints';
import type { ChampionshipSummary } from '../api/types';
import { UFS, dayBlock } from '../lib/format';
import { brandLogoFor } from '../lib/theme';
import { useAuth } from '../auth/AuthContext';
import { Empty, ErrorState, Loading } from '../components/States';

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
  const [term, setTerm] = useState(q.toUpperCase());
  const [state, setState] = useState(uf);

  const query = useQuery({
    queryKey: ['search', q, uf],
    queryFn: () => publicApi.search(q, uf),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    const next = new URLSearchParams();
    if (term.trim()) next.set('q', term.trim());
    if (state) next.set('uf', state);
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
        <h2 id="results-title" className="section-title">
          {q || uf ? 'Resultados da busca' : 'Próximos campeonatos'}
        </h2>
        {query.isLoading && <Loading />}
        {query.error && <ErrorState error={query.error} />}
        {query.data && query.data.length === 0 && (
          <Empty title="Nenhum campeonato encontrado">
            <p>Tente buscar só pela cidade ou trocar o estado.</p>
          </Empty>
        )}
        {query.data && query.data.length > 0 && (
          <ul className="champ-list">
            {query.data.map((c) => {
              const block = dayBlock(c.startDate, c.endDate);
              return (
                <li key={c.id}>
                  <Link to={`/c/${c.slug}`} className="champ-row">
                    <span className="champ-row__date" aria-hidden>
                      <span className="champ-row__days">{block.days}</span>
                      <span className="champ-row__month">{block.month}</span>
                    </span>
                    <span className="champ-row__main">
                      <span className="champ-row__name">{c.name}</span>
                      <span className="champ-row__place">
                        {c.venueName}, {c.city}/{c.state}
                      </span>
                    </span>
                    <span className="champ-row__status">{statusTag(c)}</span>
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
