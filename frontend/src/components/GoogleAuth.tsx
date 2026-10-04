import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { publicApi } from '../api/endpoints';
import type { PendingGoogleSignup, Role, User } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { firstName } from '../lib/format';
import type { GoogleButtonText } from '../lib/googleIdentity';
import { GoogleButton } from './GoogleButton';
import { RoleChoice } from './RoleChoice';
import { InlineError } from './States';

/**
 * "Entrar com Google". Se a conta ainda não existe e nenhum perfil foi passado,
 * a API devolve "pending" e mostramos a escolha de perfil antes de criar a conta.
 * No cadastro o perfil já vem escolhido (role) e esse passo não aparece.
 */
export function GoogleAuth({
  role,
  text = 'signin_with',
  onSuccess,
}: {
  role?: Role;
  text?: GoogleButtonText;
  onSuccess: (user: User) => void;
}) {
  const { loginWithGoogle } = useAuth();
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity });
  const [pending, setPending] = useState<{ credential: string; profile: PendingGoogleSignup } | null>(null);
  const [choice, setChoice] = useState<Role>('ATHLETE');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const clientId = config.data?.googleClientId;
  if (!clientId) return null;

  async function attempt(credential: string, chosen?: Role) {
    setBusy(true);
    setError(null);
    try {
      const result = await loginWithGoogle(credential, chosen);
      if (result.user) onSuccess(result.user);
      else setPending({ credential, profile: result.pending });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  function confirmRole(event: FormEvent) {
    event.preventDefault();
    if (pending) attempt(pending.credential, choice);
  }

  if (pending) {
    return (
      <form className="panel google-first" onSubmit={confirmRole} noValidate aria-labelledby="google-first-title">
        <h2 id="google-first-title" className="panel__title">Primeiro acesso com o Google</h2>
        <p className="panel__lead">
          Olá, {firstName(pending.profile.name)}! Escolha como você vai usar o WODZ com {pending.profile.email}.
        </p>
        <RoleChoice name="google-role" value={choice} onChange={setChoice} />
        <InlineError error={error} />
        <div className="row-actions">
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? 'Criando conta…' : 'Continuar'}
          </button>
          <button type="button" className="btn btn--ghost" disabled={busy}
            onClick={() => {
              setPending(null);
              setError(null);
            }}>
            Cancelar
          </button>
        </div>
      </form>
    );
  }

  return (
    <div className="google-auth">
      <p className="divider"><span>ou</span></p>
      <GoogleButton clientId={clientId} text={text} onCredential={(credential) => attempt(credential, role)} />
      {busy && <p className="note" role="status">Entrando com o Google…</p>}
      <InlineError error={error} />
    </div>
  );
}
