import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { athleteApi, publicApi } from '../api/endpoints';
import type { Gender, MemberInput, ShirtSize, Team, User } from '../api/types';
import { isValidCpf, maskCpf, money, onlyDigits } from '../lib/format';
import { useAuth } from '../auth/AuthContext';
import { existingRegistration } from '../lib/teamSchedule';
import { Field } from '../components/Field';
import { PlateChip } from '../components/Plate';
import { ErrorState, InlineError, Loading } from '../components/States';

const SIZES: ShirtSize[] = ['PP', 'P', 'M', 'G', 'GG', 'XGG'];

const emptyMember = (user: User | null): MemberInput => ({
  fullName: user?.name ?? '',
  email: user?.email ?? '',
  cpf: '',
  birthDate: '',
  gender: '',
  shirtSize: '',
});

export default function TeamRegistrationPage() {
  const { slug = '' } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const query = useQuery({ queryKey: ['championship', slug], queryFn: () => publicApi.championship(slug) });
  const registrations = useQuery({ queryKey: ['my-teams'], queryFn: athleteApi.teams,
    enabled: user?.role === 'ATHLETE', staleTime: 0 });

  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [team, setTeam] = useState({ name: '', boxName: '', city: '' });
  const [members, setMembers] = useState<MemberInput[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const category = query.data?.categories.find((c) => c.id === categoryId);
  const teamSize = category?.teamSize ?? 0;

  useEffect(() => {
    if (!teamSize) return;
    setMembers((prev) => Array.from({ length: teamSize }, (_, i) => prev[i] ?? emptyMember(i === 0 ? user : null)));
  }, [teamSize, user]);

  const mutation = useMutation({
    mutationFn: () =>
      athleteApi.register(slug, {
        categoryId: categoryId!,
        name: team.name.trim(),
        boxName: team.boxName.trim() || undefined,
        city: team.city.trim() || undefined,
        members: members.map((m) => ({
          fullName: m.fullName.trim(),
          email: m.email.trim(),
          cpf: onlyDigits(m.cpf),
          birthDate: m.birthDate,
          gender: m.gender as Gender,
          shirtSize: m.shirtSize === '' ? null : m.shirtSize,
        })),
      }),
    onSuccess: (created) => {
      cache.setQueryData<Team[]>(['my-teams'], (previous = []) => [created, ...previous.filter((item) => item.id !== created.id)]);
      navigate(created.status === 'CONFIRMED' ? `/minhas-equipes?equipe=${created.id}` : `/pagamento/${created.registrationCode}`);
    },
    onError: () => { void cache.invalidateQueries({ queryKey: ['my-teams'] }); },
  });

  if (query.isLoading || registrations.isLoading) return <Loading />;
  if (query.error || !query.data) return <div className="page"><ErrorState error={query.error} /></div>;
  const c = query.data;
  if (registrations.error) return <div className="page"><ErrorState error={registrations.error}
    action={<button className="btn btn--secondary" onClick={() => registrations.refetch()}>Tentar novamente</button>} /></div>;
  const existing = existingRegistration(registrations.data ?? [], c.id);
  if (existing) return (
    <div className="page page--narrow">
      <p className="crumb"><Link to={`/c/${c.slug}`}>{c.name}</Link></p>
      <h1 className="page-title">Você já possui uma inscrição para esse campeonato</h1>
      <p className="lead">{existing.name} · {existing.category.name}</p>
      <div className="form-actions">
        {existing.status === 'PENDING_PAYMENT' && <Link className="btn btn--primary" to={`/pagamento/${existing.registrationCode}`}>Continuar pagamento</Link>}
        <Link className="btn btn--secondary" to={`/minhas-equipes?equipe=${existing.id}`}>Ver minha equipe</Link>
      </div>
    </div>
  );

  if (!c.registrationOpen) {
    return (
      <div className="page page--narrow">
        <h1 className="page-title">Inscrições fechadas</h1>
        <p className="lead">O formulário de inscrição do {c.name} não está aberto no momento.</p>
        <Link className="btn btn--secondary" to={`/c/${c.slug}`}>Voltar para o campeonato</Link>
      </div>
    );
  }

  const updateMember = (index: number, patch: Partial<MemberInput>) =>
    setMembers((list) => list.map((m, i) => (i === index ? { ...m, ...patch } : m)));

  function validate(): boolean {
    const next: Record<string, string> = {};
    if (!categoryId) next.category = 'Escolha a categoria da equipe';
    if (!team.name.trim()) next.teamName = 'Dê um nome para a equipe';
    const cpfs = new Set<string>();
    members.forEach((m, i) => {
      if (!m.fullName.trim()) next[`m${i}.fullName`] = 'Informe o nome';
      if (!/^\S+@\S+\.\S+$/.test(m.email)) next[`m${i}.email`] = 'E-mail inválido';
      if (!isValidCpf(m.cpf)) next[`m${i}.cpf`] = 'CPF inválido';
      else if (cpfs.has(onlyDigits(m.cpf))) next[`m${i}.cpf`] = 'CPF repetido na equipe';
      cpfs.add(onlyDigits(m.cpf));
      if (!m.birthDate) next[`m${i}.birthDate`] = 'Informe a data de nascimento';
      if (!m.gender) next[`m${i}.gender`] = 'Escolha uma opção';
    });
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (validate()) mutation.mutate();
  }

  const free = category && Number(category.price) === 0;

  return (
    <div className="page page--form">
      <p className="crumb"><Link to={`/c/${c.slug}`}>{c.name}</Link></p>
      <h1 className="page-title">Inscrever equipe</h1>

      <form className="form form--wide" onSubmit={submit} noValidate>
        <fieldset className="form-section">
          <legend className="form-section__title">Categoria</legend>
          {errors.category && <p className="field__error" role="alert">{errors.category}</p>}
          <div className="category-options">
            {c.categories.map((cat, index) => {
              const full = cat.slotsLeft !== null && cat.slotsLeft !== undefined && cat.slotsLeft <= 0;
              return (
                <label key={cat.id} className={`category-option${full ? ' is-disabled' : ''}`}>
                  <input type="radio" name="category" disabled={full} checked={categoryId === cat.id}
                    onChange={() => setCategoryId(cat.id)} />
                  <span className="category-option__body">
                    <PlateChip index={index} name={cat.name} />
                    <span className="category-option__facts">
                      {cat.teamSize} atleta{cat.teamSize > 1 ? 's' : ''},{' '}
                      {Number(cat.price) === 0 ? 'gratuita' : money(cat.price)}
                      {full ? ', esgotada' : cat.slotsLeft ? `, ${cat.slotsLeft} vagas` : ''}
                    </span>
                  </span>
                </label>
              );
            })}
          </div>
        </fieldset>

        <fieldset className="form-section">
          <legend className="form-section__title">Equipe</legend>
          <div className="grid-3">
            <Field label="Nome da equipe" htmlFor="team-name" error={errors.teamName} uppercase>
              <input id="team-name" className="input" maxLength={80} value={team.name}
                onChange={(e) => setTeam({ ...team, name: e.target.value })} />
            </Field>
            <Field label="Box (opcional)" htmlFor="team-box" uppercase>
              <input id="team-box" className="input" maxLength={120} value={team.boxName}
                onChange={(e) => setTeam({ ...team, boxName: e.target.value })} />
            </Field>
            <Field label="Cidade (opcional)" htmlFor="team-city" uppercase>
              <input id="team-city" className="input" maxLength={100} value={team.city}
                onChange={(e) => setTeam({ ...team, city: e.target.value })} />
            </Field>
          </div>
        </fieldset>

        {members.map((m, i) => (
          <fieldset key={i} className="form-section">
            <legend className="form-section__title">
              Atleta {i + 1}{i === 0 ? ' (capitão, você)' : ''}
            </legend>
            <div className="grid-2">
              <Field label="Nome completo" htmlFor={`m${i}-name`} error={errors[`m${i}.fullName`]} uppercase>
                <input id={`m${i}-name`} className="input" value={m.fullName}
                  onChange={(e) => updateMember(i, { fullName: e.target.value })} />
              </Field>
              <Field label="E-mail" htmlFor={`m${i}-email`} error={errors[`m${i}.email`]}>
                <input id={`m${i}-email`} className="input" type="email" value={m.email}
                  onChange={(e) => updateMember(i, { email: e.target.value })} />
              </Field>
              <Field label="CPF" htmlFor={`m${i}-cpf`} error={errors[`m${i}.cpf`]}>
                <input id={`m${i}-cpf`} className="input" inputMode="numeric" placeholder="000.000.000-00"
                  value={m.cpf} onChange={(e) => updateMember(i, { cpf: maskCpf(e.target.value) })} />
              </Field>
              <Field label="Data de nascimento" htmlFor={`m${i}-birth`} error={errors[`m${i}.birthDate`]}>
                <input id={`m${i}-birth`} className="input" type="date" value={m.birthDate}
                  onChange={(e) => updateMember(i, { birthDate: e.target.value })} />
              </Field>
              <Field label="Sexo" htmlFor={`m${i}-gender`} error={errors[`m${i}.gender`]}>
                <select id={`m${i}-gender`} className="input" value={m.gender}
                  onChange={(e) => updateMember(i, { gender: e.target.value as Gender | '' })}>
                  <option value="">Selecione</option>
                  <option value="F">Feminino</option>
                  <option value="M">Masculino</option>
                </select>
              </Field>
              <Field label="Camiseta (opcional)" htmlFor={`m${i}-size`}>
                <select id={`m${i}-size`} className="input" value={m.shirtSize}
                  onChange={(e) => updateMember(i, { shirtSize: e.target.value as ShirtSize | '' })}>
                  <option value="">Não informar</option>
                  {SIZES.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </Field>
            </div>
          </fieldset>
        ))}

        <div className="form-actions">
          <InlineError error={mutation.error} />
          {category && (
            <p className="form-actions__total">
              Total: <strong>{free ? 'gratuito' : money(category.price)}</strong>
            </p>
          )}
          <button type="submit" className="btn btn--primary btn--large" disabled={mutation.isPending || !category}>
            {mutation.isPending ? 'Enviando inscrição…' : free ? 'Confirmar inscrição' : 'Inscrever e ir para o pagamento'}
          </button>
        </div>
      </form>
    </div>
  );
}
