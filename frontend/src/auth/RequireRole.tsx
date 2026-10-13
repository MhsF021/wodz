import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router';
import type { Role } from '../api/types';
import { useAuth } from './AuthContext';
import { Loading } from '../components/States';
import { isProfileComplete, profileReturnPath } from '../lib/navigation';

/** Sem `role`, basta estar logado. */
export function RequireRole({ role, children }: { role?: Role; children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <Loading />;
  if (!user) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/entrar?next=${next}`} replace />;
  }
  if (role && user.role !== role) {
    return (
      <div className="page page--narrow">
        <h1 className="page-title">Área restrita</h1>
        <p className="lead">
          {role === 'ORGANIZER'
            ? 'Esta área é do organizador do campeonato. Entre com uma conta de organizador.'
            : 'A inscrição de equipes é feita com uma conta de atleta.'}
        </p>
        <Link className="btn btn--secondary" to="/">Voltar para os campeonatos</Link>
      </div>
    );
  }
  if (location.pathname !== '/minha-conta' && !isProfileComplete(user)) {
    return <Navigate to={profileReturnPath(user, location.pathname + location.search)} replace />;
  }
  return <>{children}</>;
}
