import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { authApi, publicApi } from '../api/endpoints';
import type { PendingGoogleSignup, Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { InlineError, Loading } from '../components/States';
import { RoleChoice } from '../components/RoleChoice';
import { clearGoogleRedirect, consumeGoogleRedirect } from '../lib/googleRedirect';
import { disconnectGoogleAccount } from '../lib/googleDisconnect';

export default function GoogleReturnPage() {
  const { loginWithGoogle, logout, user, loading } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [result] = useState(consumeGoogleRedirect);
  const [pending, setPending] = useState<PendingGoogleSignup | null>(null);
  const [choice, setChoice] = useState<Role>('ATHLETE');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(true);
  const started = useRef(false);
  const mounted = useRef(false);
  const sending = useRef(false);
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity,
    enabled: result?.flow.kind === 'unlink' });

  async function finish(role?: Role) {
    if (!result || sending.current) return;
    sending.current = true;
    setBusy(true); setError(null);
    try {
      if (result.flow.kind === 'unlink') {
        if (!user || user.id !== result.flow.userId) throw new Error('Entre novamente no WODZ para confirmar a desvinculação.');
        const accountClosed = !user.hasPassword;
        await disconnectGoogleAccount({
          credential: result.credential, clientId: config.data?.googleClientId ?? '', accountClosed,
          disconnect: () => authApi.disconnectGoogle({ googleCredential: result.credential }),
          endSession: () => logout(`/entrar?google=desvinculado&conta=${accountClosed ? 'encerrada' : 'mantida'}`),
        });
      } else {
        const response = await loginWithGoogle(result.credential, role ?? result.flow.role);
        if (!mounted.current) return;
        if (response.user) {
          clearGoogleRedirect();
          navigate(result.flow.next ?? (response.user.role === 'ORGANIZER' ? '/organizador' : '/minhas-equipes'), { replace: true });
        } else {
          setPending(response.pending);
        }
      }
    } catch (e) {
      clearGoogleRedirect();
      if (mounted.current) setError(e);
    } finally {
      sending.current = false;
      if (mounted.current) setBusy(false);
      if (result.flow.kind === 'unlink') clearGoogleRedirect();
    }
  }

  useEffect(() => {
    mounted.current = true;
    if (!started.current && !loading && (result?.flow.kind !== 'unlink' || !config.isPending)) {
      started.current = true;
      if (!result || params.has('erro')) {
        clearGoogleRedirect();
        setError(new Error('A confirmação Google foi cancelada, expirou ou não pôde ser validada. Tente novamente.'));
        setBusy(false);
      } else { void finish(); }
    }
    return () => { mounted.current = false; };
  }, [loading, config.isPending, result, params, user]);

  function confirm(event: FormEvent) {
    event.preventDefault();
    void finish(choice);
  }

  return <div className="page page--narrow">
    <h1 className="page-title">{result?.flow.kind === 'unlink' ? 'Desvincular conta Google' : 'Entrar com Google'}</h1>
    {busy && <Loading />}
    <InlineError error={error} />
    {!busy && pending && !error && <form className="form" onSubmit={confirm}>
      <p className="lead">Olá, {pending.name}. Escolha seu perfil para criar uma nova conta no WODZ.</p>
      <RoleChoice value={choice} onChange={setChoice} />
      <button type="submit" className="btn btn--primary">Criar conta e continuar</button>
      <Link className="btn btn--ghost" to="/entrar" onClick={clearGoogleRedirect}>Cancelar</Link>
    </form>}
    {!busy && Boolean(error) && <Link className="btn btn--ghost" to={result?.flow.kind === 'unlink' || user ? '/minha-conta' : '/entrar'}>
      Tentar novamente
    </Link>}
  </div>;
}
