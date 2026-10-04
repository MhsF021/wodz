import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { Field } from '../components/Field';
import { InlineError } from '../components/States';
import { GoogleAuth } from '../components/GoogleAuth';
import type { User } from '../api/types';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get('next');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

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
      <GoogleAuth onSuccess={goOn} />
      <p className="form-foot">
        Ainda não tem conta? <Link to={`/cadastro${next ? `?next=${encodeURIComponent(next)}` : ''}`}>Criar conta</Link>
      </p>
    </div>
  );
}
