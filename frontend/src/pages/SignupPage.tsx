import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import type { Gender, Role, User } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Field } from '../components/Field';
import { InlineError } from '../components/States';
import { GoogleAuth } from '../components/GoogleAuth';
import { RoleChoice } from '../components/RoleChoice';
import { localReturnPath, profileReturnPath } from '../lib/navigation';
import { isValidPhone } from '../lib/format';

export default function SignupPage() {
  const { signup } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = localReturnPath(params.get('next'));
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '' });
  const [passwordConfirmation, setPasswordConfirmation] = useState('');
  const [role, setRole] = useState<Role>('ATHLETE');
  const [gender, setGender] = useState<Gender | ''>('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const goOn = (user: User) => navigate(profileReturnPath(user, next), { replace: true });

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (role === 'ATHLETE' && !gender) {
      setError(new Error('Informe o sexo para criar sua conta'));
      return;
    }
    if (form.password !== passwordConfirmation) {
      setError(new Error('A senha e a confirmação de senha devem ser iguais'));
      return;
    }
    if (form.password.length < 8) {
      setError(new Error('A senha precisa ter pelo menos 8 caracteres'));
      return;
    }
    if (!isValidPhone(form.phone)) {
      setError(new Error('Informe um telefone válido com DDD'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      goOn(await signup({ ...form, role, gender: role === 'ATHLETE' ? gender || undefined : undefined }));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page page--narrow">
      <h1 className="page-title">Criar conta</h1>
      <form className="form" onSubmit={submit} noValidate>
        <RoleChoice value={role} onChange={setRole} />
        <Field label="Nome completo" htmlFor="su-name" uppercase>
          <input id="su-name" className="input" autoComplete="name" required value={form.name} onChange={set('name')} />
        </Field>
        {role === 'ATHLETE' && <Field label="Sexo" htmlFor="su-gender">
          <select id="su-gender" className="input" required value={gender}
            onChange={(e) => setGender(e.target.value as Gender | '')}>
            <option value="">Selecione</option>
            <option value="F">Feminino</option>
            <option value="M">Masculino</option>
          </select>
        </Field>}
        <Field label="E-mail" htmlFor="su-email">
          <input id="su-email" className="input" type="email" autoComplete="email" required
            value={form.email} onChange={set('email')} />
        </Field>
        <Field label="Telefone (obrigatório)" htmlFor="su-phone" hint="Inclua o DDD.">
          <input id="su-phone" className="input" type="tel" autoComplete="tel" required maxLength={20} value={form.phone} onChange={set('phone')} />
        </Field>
        <Field label="Senha" htmlFor="su-password" hint="Pelo menos 8 caracteres.">
          <input id="su-password" className="input" type="password" autoComplete="new-password" required
            value={form.password} onChange={set('password')} />
        </Field>
        <Field label="Confirmação de senha" htmlFor="su-password-confirmation">
          <input id="su-password-confirmation" className="input" type="password" autoComplete="new-password" required
            value={passwordConfirmation} onChange={(e) => setPasswordConfirmation(e.target.value)} />
        </Field>
        <InlineError error={error} />
        <button type="submit" className="btn btn--primary btn--block" disabled={busy}>
          {busy ? 'Criando conta…' : 'Criar conta'}
        </button>
      </form>
      <GoogleAuth role={role} gender={role === 'ATHLETE' ? gender || undefined : undefined} text="signup_with" />
      <p className="form-foot">
        Já tem conta? <Link to={`/entrar${next ? `?next=${encodeURIComponent(next)}` : ''}`}>Entrar</Link>
      </p>
    </div>
  );
}
