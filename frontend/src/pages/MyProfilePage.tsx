import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { authApi, publicApi } from '../api/endpoints';
import type { Role, User } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Field } from '../components/Field';
import { InlineError } from '../components/States';
import { GoogleButton } from '../components/GoogleButton';

const ROLE_LABEL: Record<Role, string> = { ATHLETE: 'Atleta', ORGANIZER: 'Organizador' };

const EMPTY_PASSWORDS = { currentPassword: '', newPassword: '', confirmation: '' };

export default function MyProfilePage() {
  const { user } = useAuth();
  // O RequireRole já garante o usuário logado.
  if (!user) return null;

  return (
    <div className="page page--form">
      <div className="page-head">
        <h1 className="page-title">Minha conta</h1>
        <div className="row-actions">
          <span className="badge badge--muted">{ROLE_LABEL[user.role]}</span>
          {user.googleLinked && <span className="badge badge--muted">Google conectado</span>}
        </div>
      </div>
      <div className="stack">
        <ProfileSection user={user} />
        <PasswordSection user={user} />
        <DeleteAccountSection user={user} />
      </div>
    </div>
  );
}

function ProfileSection({ user }: { user: User }) {
  const { updateProfile } = useAuth();
  const [form, setForm] = useState({ name: user.name, email: user.email, phone: user.phone ?? '' });
  const [currentPassword, setCurrentPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  const emailChanged = form.email.trim().toLowerCase() !== user.email.toLowerCase();

  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => {
    setSaved(false);
    setForm((f) => ({ ...f, [key]: e.target.value }));
  };

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.name.trim() || !form.email.trim()) {
      setError(new Error('Preencha nome e e-mail'));
      return;
    }
    if (emailChanged && !user.hasPassword) {
      setError(new Error('Crie uma senha antes de trocar o e-mail'));
      return;
    }
    if (emailChanged && !currentPassword) {
      setError(new Error('Informe sua senha atual para trocar o e-mail'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const updated = await updateProfile({
        name: form.name.trim(),
        email: form.email.trim(),
        phone: form.phone.trim(),
        currentPassword: emailChanged ? currentPassword : undefined,
      });
      setForm({ name: updated.name, email: updated.email, phone: updated.phone ?? '' });
      setCurrentPassword('');
      setSaved(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="account-profile">
      <h2 id="account-profile" className="panel__title">Dados de cadastro</h2>
      {user.role === 'ATHLETE' && (
        <p className="panel__lead">O organizador usa esses dados para falar com você sobre as inscrições.</p>
      )}
      <form className="form" onSubmit={submit} noValidate>
        <Field label="Nome completo" htmlFor="acc-name">
          <input id="acc-name" className="input" autoComplete="name" required maxLength={120}
            value={form.name} onChange={set('name')} />
        </Field>
        <div className="grid-2">
          <Field label="E-mail" htmlFor="acc-email" hint="É com ele que você entra no WODZ.">
            <input id="acc-email" className="input" type="email" autoComplete="email" required maxLength={160}
              value={form.email} onChange={set('email')} />
          </Field>
          <Field label="Celular (opcional)" htmlFor="acc-phone">
            <input id="acc-phone" className="input" type="tel" autoComplete="tel" maxLength={20}
              value={form.phone} onChange={set('phone')} />
          </Field>
        </div>
        {emailChanged && !user.hasPassword && (
          <p className="note">Você entra com o Google. Para trocar o e-mail, crie uma senha na seção abaixo.</p>
        )}
        {emailChanged && user.hasPassword && (
          <Field label="Senha atual" htmlFor="acc-email-password" hint="Para trocar o e-mail, confirme sua senha.">
            <input id="acc-email-password" className="input" type="password" autoComplete="current-password" required
              value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} />
          </Field>
        )}
        <div className="form-actions">
          <InlineError error={error} />
          {saved && <p className="form-ok" role="status">Dados atualizados.</p>}
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? 'Salvando…' : 'Salvar alterações'}
          </button>
        </div>
      </form>
    </section>
  );
}

/** Conta criada com o Google ainda não tem senha: a seção vira "Criar senha", sem pedir a atual. */
function PasswordSection({ user }: { user: User }) {
  const { refreshUser } = useAuth();
  const [form, setForm] = useState(EMPTY_PASSWORDS);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState<string | null>(null);
  const firstPassword = !user.hasPassword;

  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => {
    setSaved(null);
    setForm((f) => ({ ...f, [key]: e.target.value }));
  };

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!firstPassword && !form.currentPassword) {
      setError(new Error('Informe sua senha atual'));
      return;
    }
    if (form.newPassword.length < 8) {
      setError(new Error('A nova senha precisa ter pelo menos 8 caracteres'));
      return;
    }
    if (form.newPassword !== form.confirmation) {
      setError(new Error('A confirmação não é igual à nova senha'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authApi.changePassword({
        currentPassword: firstPassword ? undefined : form.currentPassword,
        newPassword: form.newPassword,
      });
      setForm(EMPTY_PASSWORDS);
      if (firstPassword) await refreshUser();
      setSaved(firstPassword ? 'Senha criada. Agora você também pode entrar com e-mail e senha.' : 'Senha alterada.');
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="account-password">
      <h2 id="account-password" className="panel__title">{firstPassword ? 'Criar senha' : 'Senha'}</h2>
      {firstPassword && (
        <p className="panel__lead">
          Você entra com o Google. Crie uma senha para também poder entrar com e-mail e senha.
        </p>
      )}
      <form className="form" onSubmit={submit} noValidate>
        {/* Ajuda o gerenciador de senhas a saber de qual conta é a senha nova. */}
        <input type="email" autoComplete="username" value={user.email} readOnly hidden />
        {!firstPassword && (
          <Field label="Senha atual" htmlFor="acc-current-password">
            <input id="acc-current-password" className="input" type="password" autoComplete="current-password" required
              value={form.currentPassword} onChange={set('currentPassword')} />
          </Field>
        )}
        <div className="grid-2">
          <Field label="Nova senha" htmlFor="acc-new-password" hint="Pelo menos 8 caracteres.">
            <input id="acc-new-password" className="input" type="password" autoComplete="new-password" required
              maxLength={72} value={form.newPassword} onChange={set('newPassword')} />
          </Field>
          <Field label="Confirme a nova senha" htmlFor="acc-confirm-password">
            <input id="acc-confirm-password" className="input" type="password" autoComplete="new-password" required
              maxLength={72} value={form.confirmation} onChange={set('confirmation')} />
          </Field>
        </div>
        <div className="form-actions">
          <InlineError error={error} />
          {saved && <p className="form-ok" role="status">{saved}</p>}
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? 'Salvando…' : firstPassword ? 'Criar senha' : 'Alterar senha'}
          </button>
        </div>
      </form>
    </section>
  );
}

function DeleteAccountSection({ user }: { user: User }) {
  const { logout } = useAuth();
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity });
  const [confirming, setConfirming] = useState(false);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  function cancel() {
    setConfirming(false);
    setPassword('');
    setError(null);
  }

  async function remove(confirmation: { password?: string; googleCredential?: string }) {
    setBusy(true);
    setError(null);
    try {
      await authApi.deleteMe(confirmation);
      logout();
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!password) {
      setError(new Error('Digite sua senha para confirmar'));
      return;
    }
    remove({ password });
  }

  return (
    <section className="panel panel--danger" aria-labelledby="account-delete">
      <h2 id="account-delete" className="panel__title">Excluir conta</h2>
      <p className="panel__lead">
        Seu nome, e-mail e celular são apagados e você não consegue mais entrar com esta conta. Não é possível desfazer.
      </p>
      {user.role === 'ATHLETE' ? (
        <p className="note">
          Inscrições aguardando pagamento são canceladas. Equipes que já competiram continuam nos resultados dos
          campeonatos. Se sua equipe está confirmada em um campeonato que ainda não terminou, peça ao organizador para
          cancelar a inscrição antes.
        </p>
      ) : (
        <p className="note">
          Campeonatos encerrados continuam publicados com os resultados; rascunhos deixam de ser acessíveis.
          Campeonatos publicados ou em andamento precisam ser encerrados ou cancelados antes.
        </p>
      )}
      {confirming && !user.hasPassword ? (
        // Conta só com Google: confirma com um novo login na mesma conta Google.
        <div className="panel__confirm stack">
          <p className="note">Para confirmar, entre de novo com a sua conta Google.</p>
          {config.data?.googleClientId && (
            <GoogleButton clientId={config.data.googleClientId} text="continue_with"
              onCredential={(googleCredential) => remove({ googleCredential })} />
          )}
          {busy && <p className="note" role="status">Excluindo…</p>}
          <InlineError error={error} />
          <div className="row-actions">
            <button type="button" className="btn btn--ghost" onClick={cancel} disabled={busy}>
              Cancelar
            </button>
          </div>
        </div>
      ) : confirming ? (
        <form className="form panel__confirm" onSubmit={submit} noValidate>
          <Field label="Digite sua senha para confirmar" htmlFor="acc-delete-password">
            <input id="acc-delete-password" className="input" type="password" autoComplete="current-password" required
              autoFocus value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
          <div className="form-actions">
            <InlineError error={error} />
            <button type="button" className="btn btn--ghost" onClick={cancel} disabled={busy}>
              Cancelar
            </button>
            <button type="submit" className="btn btn--danger" disabled={busy}>
              {busy ? 'Excluindo…' : 'Excluir minha conta definitivamente'}
            </button>
          </div>
        </form>
      ) : (
        <button type="button" className="btn btn--danger panel__confirm" onClick={() => setConfirming(true)}>
          Excluir minha conta
        </button>
      )}
    </section>
  );
}
