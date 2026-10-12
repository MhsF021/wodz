import { useState, useSyncExternalStore, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { Field } from '../components/Field';
import { InlineError } from '../components/States';
import { GoogleAuth } from '../components/GoogleAuth';
import type { User } from '../api/types';
import { localReturnPath } from '../lib/navigation';
import { getGoogleDisconnectNotice, subscribeGoogleDisconnect } from '../lib/googleDisconnect';
import { Loading } from '../components/States';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = localReturnPath(params.get('next'));
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const disconnectNotice = useSyncExternalStore(subscribeGoogleDisconnect, getGoogleDisconnectNotice);
  const googleDisconnected = params.get('google') === 'desvinculado';
  const accountClosed = params.get('conta') === 'encerrada';
  const notice = googleDisconnected && disconnectNotice?.accountClosed === accountClosed ? disconnectNotice : null;

  const goOn = (user: User) =>
    navigate(next ?? (user.role === 'ORGANIZER' ? '/organizador' : '/minhas-equipes'), { replace: true });

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      goOn(await login(email, password));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page page--narrow">
      <h1 className="page-title">Entrar</h1>
      {params.get('saida') === 'local' && <p className="note" role="status">
        Os dados desta sessão foram removidos deste navegador. Sem conexão com o servidor, não foi possível confirmar a revogação das outras sessões.
      </p>}
      {params.get('senha') === 'alterada' && (
        <p className="form-ok" role="status">Senha salva. As sessões anteriores foram encerradas. Entre novamente.</p>
      )}
      {googleDisconnected && <div className="stack" role="status" aria-live="polite">
        <p className="form-ok">{accountClosed
          ? 'Conta Google desvinculada. Sua conta no WODZ foi encerrada e as sessões foram finalizadas.'
          : 'Conta Google desvinculada. As sessões foram encerradas. Para acessar o WODZ, use seu e-mail e senha.'}</p>
        {notice?.permission === 'pending' && <Loading label="Revogando permissão Google" />}
        {notice?.permission === 'revoked' && <p className="note">Permissão de acesso do WODZ no Google revogada.</p>}
        {notice?.permission === 'unconfirmed' && <p className="note">
          A desvinculação no WODZ foi concluída, mas não foi possível confirmar a revogação da permissão no Google.{' '}
          <a href="https://myaccount.google.com/connections">Remova a conexão do WODZ na sua Conta Google</a>.
        </p>}
      </div>}
      <form className="form" onSubmit={submit} noValidate>
        <Field label="E-mail" htmlFor="login-email">
          <input id="login-email" className="input" type="email" autoComplete="email" required
            value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Senha" htmlFor="login-password">
          <input id="login-password" className="input" type="password" autoComplete="current-password" required
            value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <InlineError error={error} />
        <button type="submit" className="btn btn--primary btn--block" disabled={busy}>
          {busy ? 'Entrando…' : 'Entrar'}
        </button>
      </form>
      {notice?.permission !== 'pending' && <GoogleAuth />}
      <p className="form-foot">
        Ainda não tem conta? <Link to={`/cadastro${next ? `?next=${encodeURIComponent(next)}` : ''}`}>Criar conta</Link>
      </p>
    </div>
  );
}
