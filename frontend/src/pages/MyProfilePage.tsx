import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useSearchParams } from 'react-router';
import { authApi, publicApi } from '../api/endpoints';
import type { Gender, Role, User } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Field } from '../components/Field';
import { InlineError, Loading } from '../components/States';
import { GoogleButton } from '../components/GoogleButton';
import { PayoutAccountSection } from '../components/PayoutAccountSection';
import { isValidCpf, isValidCnpj, isValidPhone, maskCpf, maskDocument, normalizeDocument, onlyDigits } from '../lib/format';
import { isProfileComplete, localReturnPath } from '../lib/navigation';

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
        {user.role === 'ORGANIZER' && <PayoutAccountSection />}
        {user.googleLinked ? <GoogleAccountSection user={user} /> : (
          <>
            <PasswordSection user={user} />
            <DeleteAccountSection user={user} />
          </>
        )}
      </div>
    </div>
  );
}

function ProfileSection({ user }: { user: User }) {
  const { updateProfile } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = localReturnPath(params.get('next'));
  const athlete = user.role === 'ATHLETE';
  const fields = (u: User) => ({ name: u.name, email: u.email, phone: u.phone ?? '',
    document: athlete ? maskCpf(u.document ?? '') : maskDocument(u.document ?? ''),
    street: u.street ?? '', addressNumber: u.addressNumber ?? '', city: u.city ?? '',
    postalCode: u.postalCode?.replace(/^(\d{5})(\d{3})$/, '$1-$2') ?? '' });
  const [form, setForm] = useState(() => fields(user));
  const [gender, setGender] = useState<Gender | ''>(user.gender ?? '');
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
    if (athlete && !gender) { setError(new Error('Informe o sexo no seu cadastro')); return; }
    if (!form.name.trim() || !form.email.trim()) {
      setError(new Error('Preencha nome e e-mail'));
      return;
    }
    if (!isValidCpf(form.document) && (athlete || !isValidCnpj(form.document))) {
      setError(new Error(athlete ? 'Informe um CPF válido' : 'Informe um CPF ou CNPJ válido'));
      return;
    }
    if ((athlete || form.phone.trim()) && !isValidPhone(form.phone)) {
      setError(new Error('Informe um telefone válido com DDD'));
      return;
    }
    if (!athlete && form.postalCode && !/^\d{5}-?\d{3}$/.test(form.postalCode)) {
      setError(new Error('Informe um CEP válido'));
      return;
    }
    if (emailChanged && user.googleLinked) {
      setError(new Error('O e-mail é gerenciado pela sua conta Google'));
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
        gender: athlete ? gender || undefined : undefined,
        document: normalizeDocument(form.document),
        ...(!athlete ? { street: form.street.trim(), addressNumber: form.addressNumber.trim(),
          city: form.city.trim(), postalCode: onlyDigits(form.postalCode) } : {}),
        currentPassword: emailChanged ? currentPassword : undefined,
      });
      setForm(fields(updated));
      setGender(updated.gender ?? '');
      setCurrentPassword('');
      setSaved(true);
      if (next && isProfileComplete(updated)) navigate(next, { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="account-profile">
      <h2 id="account-profile" className="panel__title">Dados de cadastro</h2>
      {!isProfileComplete(user) && <p className="note" role="status">Complete os campos obrigatórios para continuar seu cadastro.</p>}
      {user.role === 'ATHLETE' && (
        <p className="panel__lead">O organizador usa esses dados para falar com você sobre as inscrições.</p>
      )}
      <form className="form" onSubmit={submit} noValidate>
        <Field label="Nome completo" htmlFor="acc-name" uppercase>
          <input id="acc-name" className="input" autoComplete="name" required maxLength={120}
            value={form.name} onChange={set('name')} />
        </Field>
        {athlete && <Field label="Sexo" htmlFor="acc-gender">
          <select id="acc-gender" className="input" required value={gender}
            onChange={(e) => { setGender(e.target.value as Gender | ''); setSaved(false); }}>
            <option value="">Selecione</option><option value="F">Feminino</option><option value="M">Masculino</option>
          </select>
        </Field>}
        <Field label={athlete ? 'CPF (obrigatório)' : 'CPF/CNPJ (obrigatório)'} htmlFor="acc-document"
          hint={athlete ? 'Seu CPF será preenchido automaticamente nas inscrições.' : undefined}>
          <input id="acc-document" className="input" inputMode={athlete ? 'numeric' : 'text'} required maxLength={athlete ? 14 : 18}
            placeholder={athlete ? '000.000.000-00' : 'CPF ou CNPJ'} value={form.document}
            onChange={(e) => { setSaved(false); setForm(f => ({ ...f, document: athlete ? maskCpf(e.target.value) : maskDocument(e.target.value) })); }} />
        </Field>
        <div className="grid-2">
          <Field label="E-mail" htmlFor="acc-email" hint={user.googleLinked ? "E-mail da sua conta Google." : "É com ele que você entra no WODZ."}>
            <input id="acc-email" className="input" type="email" autoComplete="email" required maxLength={160}
              readOnly={user.googleLinked} value={form.email} onChange={set('email')} />
          </Field>
          <Field label={athlete ? 'Telefone (obrigatório)' : 'Telefone (opcional)'} htmlFor="acc-phone" hint="Inclua o DDD.">
            <input id="acc-phone" className="input" type="tel" autoComplete="tel" required={athlete} maxLength={20}
              value={form.phone} onChange={set('phone')} />
          </Field>
        </div>
        {!athlete && <fieldset className="form-section">
          <legend className="form-section__title">Endereço (opcional)</legend>
          <div className="grid-2">
            <Field label="Rua" htmlFor="acc-street" uppercase>
              <input id="acc-street" className="input" autoComplete="address-line1" maxLength={160} value={form.street} onChange={set('street')} />
            </Field>
            <Field label="Número" htmlFor="acc-number">
              <input id="acc-number" className="input" maxLength={20} value={form.addressNumber} onChange={set('addressNumber')} />
            </Field>
            <Field label="Cidade" htmlFor="acc-city" uppercase>
              <input id="acc-city" className="input" autoComplete="address-level2" maxLength={100} value={form.city} onChange={set('city')} />
            </Field>
            <Field label="CEP" htmlFor="acc-cep">
              <input id="acc-cep" className="input" inputMode="numeric" autoComplete="postal-code" maxLength={9} placeholder="00000-000"
                value={form.postalCode} onChange={(e) => { setSaved(false); setForm(f => ({ ...f, postalCode: onlyDigits(e.target.value).slice(0, 8).replace(/^(\d{5})(\d)/, '$1-$2') })); }} />
            </Field>
          </div>
        </fieldset>}
        {emailChanged && !user.googleLinked && user.hasPassword && (
          <Field label="Senha atual" htmlFor="acc-email-password" hint="Para trocar o e-mail, confirme sua senha.">
            <input id="acc-email-password" className="input" type="password" autoComplete="current-password" required
              value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} />
          </Field>
        )}
        <div className="form-actions">
          <InlineError error={error} />
          {saved && <p className="form-ok" role="status">Dados atualizados.</p>}
          <button type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? 'Salvando…' : next ? 'Salvar e continuar' : 'Salvar alterações'}
          </button>
        </div>
      </form>
    </section>
  );
}

function PasswordSection({ user }: { user: User }) {
  const { logout } = useAuth();
  const [form, setForm] = useState(EMPTY_PASSWORDS);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => setForm(f => ({ ...f, [key]: e.target.value }));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (!form.currentPassword) return setError(new Error('Informe sua senha atual'));
    if (form.newPassword.length < 8) return setError(new Error('A nova senha precisa ter pelo menos 8 caracteres'));
    if (form.newPassword !== form.confirmation) return setError(new Error('A confirmação não é igual à nova senha'));
    setBusy(true); setError(null);
    try {
      await authApi.changePassword({ currentPassword: form.currentPassword, newPassword: form.newPassword });
      logout('/entrar?senha=alterada');
    } catch(e) { setError(e); setBusy(false); }
  }
  return <section className="panel" aria-labelledby="account-password">
    <h2 id="account-password" className="panel__title">Senha</h2>
    <form className="form" onSubmit={submit} noValidate>
      <input type="email" autoComplete="username" value={user.email} readOnly hidden />
      <Field label="Senha atual" htmlFor="acc-current-password">
        <input id="acc-current-password" className="input" type="password" autoComplete="current-password" required
          disabled={busy} value={form.currentPassword} onChange={set('currentPassword')} />
      </Field>
      <div className="grid-2">
        <Field label="Nova senha" htmlFor="acc-new-password" hint="Pelo menos 8 caracteres.">
          <input id="acc-new-password" className="input" type="password" autoComplete="new-password" required
            disabled={busy} maxLength={72} value={form.newPassword} onChange={set('newPassword')} />
        </Field>
        <Field label="Confirme a nova senha" htmlFor="acc-confirm-password">
          <input id="acc-confirm-password" className="input" type="password" autoComplete="new-password" required
            disabled={busy} maxLength={72} value={form.confirmation} onChange={set('confirmation')} />
        </Field>
      </div>
      <InlineError error={error} />
      <button type="submit" className="btn btn--primary" disabled={busy}>{busy ? <span className="spinner" aria-hidden /> : 'Alterar senha'}</button>
    </form>
  </section>;
}

function GoogleAccountSection({ user }: { user: User }) {
  const [confirming, setConfirming] = useState(false);
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity, enabled: confirming });
  const accountClosed = !user.hasPassword;
  return <section className="panel panel--danger" aria-labelledby="account-google">
    <h2 id="account-google" className="panel__title">Desvincular conta Google</h2>
    <p className="panel__lead">{accountClosed
      ? 'Ao desvincular o Google, sua conta no WODZ será encerrada e os dados do seu perfil de acesso serão removidos. Não é necessário criar uma senha. Não é possível desfazer.'
      : 'O Google deixará de dar acesso à sua conta no WODZ. Seu acesso por e-mail e senha continuará disponível.'}</p>
    <p className="note">Todas as sessões serão encerradas. Sua conta Google não será excluída.</p>
    {accountClosed && <AccountClosureNotes role={user.role} />}
    {confirming ? <div className="panel__confirm stack">
      <p className="note">Confirme com a mesma conta Google. A confirmação acontece nesta aba.</p>
      {config.isPending ? <Loading /> : config.data?.googleClientId
        ? <GoogleButton clientId={config.data.googleClientId} text="continue_with"
            flow={{ kind: 'unlink', userId: user.id, accountClosed }} />
        : <p className="note">A confirmação Google não está disponível. Tente novamente mais tarde.</p>}
      <InlineError error={config.error} />
      <button type="button" className="btn btn--ghost" onClick={() => setConfirming(false)}>Cancelar</button>
    </div> : <button type="button" className="btn btn--danger panel__confirm" onClick={() => setConfirming(true)}>Desvincular conta Google</button>}
  </section>;
}

function AccountClosureNotes({ role }: { role: Role }) {
  return <>
    <p className="note">{role === 'ATHLETE'
      ? 'Inscrições aguardando pagamento são canceladas. Se você é capitão de uma equipe confirmada em um campeonato que ainda não terminou, peça ao organizador para cancelar a inscrição antes.'
      : 'Campeonatos publicados ou em andamento precisam ser encerrados ou cancelados antes. Rascunhos deixam de ser acessíveis.'}</p>
    <p className="note">Os dados dos atletas nas inscrições e o histórico dos campeonatos permanecem registrados.
      Para solicitar o tratamento desses dados, entre em contato com o organizador do campeonato.</p>
  </>;
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
        Os dados do seu perfil de acesso são removidos e você não consegue mais entrar com esta conta.
        Não é possível desfazer.
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
      <p className="note">
        Os dados dos atletas nas inscrições e o histórico dos campeonatos permanecem registrados.
        Para solicitar o tratamento desses dados, entre em contato com o organizador do campeonato.
      </p>
      {confirming ? (
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
        <button type="button" className="btn btn--danger panel__confirm"
          onClick={() => setConfirming(true)}>
          Excluir minha conta
        </button>
      )}
    </section>
  );
}
