import { useState, type FormEvent } from 'react';
import type { ChampionshipDetail, ChampionshipInput, ScoringSystem } from '../../api/types';
import { UFS, fromLocalInput, toLocalInput } from '../../lib/format';
import { Field } from '../../components/Field';
import { InlineError } from '../../components/States';
import { ChampionshipImagePicker } from '../../components/ChampionshipImagePicker';
import { ChampionshipHero } from '../../components/ChampionshipHero';

function toForm(c?: ChampionshipDetail) {
  return {
    name: c?.name ?? '',
    description: c?.description ?? '',
    venueName: c?.venueName ?? '',
    address: c?.address ?? '',
    city: c?.city ?? '',
    state: c?.state ?? '',
    startDate: c?.startDate ?? '',
    endDate: c?.endDate ?? '',
    registrationOpensAt: toLocalInput(c?.registrationOpensAt),
    registrationClosesAt: toLocalInput(c?.registrationClosesAt),
    scoringSystem: (c?.scoringSystem ?? 'POINTS_TABLE') as ScoringSystem,
    pointsFirstPlace: String(c?.pointsFirstPlace ?? 100),
    pointsStep: String(c?.pointsStep ?? 5),
    bannerUrl: c?.bannerUrl ?? '',
  };
}

export function ChampionshipForm({
  initial,
  submitLabel,
  onSubmit,
  readOnly = false,
}: {
  initial?: ChampionshipDetail;
  submitLabel: string;
  onSubmit: (input: ChampionshipInput) => Promise<unknown>;
  readOnly?: boolean;
}) {
  const [form, setForm] = useState(() => toForm(initial));
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [imageData, setImageData] = useState<string | undefined>();
  const [profileImageData, setProfileImageData] = useState<string | undefined>();
  const [coverBusy, setCoverBusy] = useState(false);
  const [profileBusy, setProfileBusy] = useState(false);
  const imageBusy = coverBusy || profileBusy;
  const cover = imageData === undefined ? form.bannerUrl : imageData;
  const profile = profileImageData === undefined ? initial?.profileUrl : profileImageData;

  const set = (key: keyof ReturnType<typeof toForm>) =>
    (e: { target: { value: string } }) => {
      setSaved(false);
      setForm((f) => ({ ...f, [key]: e.target.value }));
    };

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (imageBusy || busy) return;
    if (!form.name.trim() || !form.venueName.trim() || !form.city.trim() || !form.state || !form.startDate || !form.endDate) {
      setError(new Error('Preencha nome, local, cidade, estado e as datas'));
      return;
    }
    if (form.endDate < form.startDate) {
      setError(new Error('A data de término não pode ser antes do início'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSubmit({
        name: form.name.trim(),
        description: form.description.trim() || null,
        venueName: form.venueName.trim(),
        address: form.address.trim() || null,
        city: form.city.trim(),
        state: form.state,
        startDate: form.startDate,
        endDate: form.endDate,
        registrationOpensAt: fromLocalInput(form.registrationOpensAt),
        registrationClosesAt: fromLocalInput(form.registrationClosesAt),
        scoringSystem: form.scoringSystem,
        pointsFirstPlace: Number(form.pointsFirstPlace) || 100,
        pointsStep: Number(form.pointsStep) || 0,
        bannerUrl: form.bannerUrl.trim() || null,
        imageData,
        profileImageData,
      });
      setSaved(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form form--wide" onSubmit={submit} noValidate>
      <fieldset className="form-section">
        <legend className="form-section__title">Campeonato</legend>
        <Field label="Nome" htmlFor="ch-name" uppercase>
          <input id="ch-name" className="input" maxLength={150} value={form.name} onChange={set('name')} />
        </Field>
        <Field label="Descrição (opcional)" htmlFor="ch-desc" hint="Regulamento resumido, kit atleta, horários de check-in." uppercase>
          <textarea id="ch-desc" className="input" rows={4} value={form.description} onChange={set('description')} />
        </Field>
      </fieldset>

      <fieldset className="form-section championship-identity">
        <legend className="form-section__title">Identidade visual do campeonato</legend>
        <p className="note">Dê uma identidade ao seu evento. Escolha as imagens e ajuste o enquadramento antes de salvar.</p>
        <div className="championship-identity__pickers">
          <ChampionshipImagePicker kind="profile" value={profile} disabled={busy || readOnly} onBusy={setProfileBusy}
            onChange={data => { setProfileImageData(data); setSaved(false); }} />
          <ChampionshipImagePicker kind="cover" value={cover} disabled={busy || readOnly} onBusy={setCoverBusy}
            onChange={data => { setImageData(data); setSaved(false); }} />
        </div>
        <div className="championship-identity__preview">
          <p className="championship-identity__preview-label">Prévia da página do campeonato</p>
          <ChampionshipHero name={form.name || 'Nome do campeonato'} cover={cover} profile={profile} preview>
            <p className="note">{form.venueName || 'Local do evento'}{form.city ? ` · ${form.city}/${form.state}` : ''}</p>
          </ChampionshipHero>
          <p className="image-picker__hint">A capa pode ter as laterais recortadas em telas menores. Mantenha os elementos principais no centro.</p>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend className="form-section__title">Local e data</legend>
        <div className="grid-3">
          <Field label="Local" htmlFor="ch-venue" hint="Box, arena ou ginásio" className="span-2" uppercase>
            <input id="ch-venue" className="input" maxLength={150} value={form.venueName} onChange={set('venueName')} />
          </Field>
          <Field label="Endereço (opcional)" htmlFor="ch-address" uppercase>
            <input id="ch-address" className="input" maxLength={255} value={form.address} onChange={set('address')} />
          </Field>
          <Field label="Cidade" htmlFor="ch-city" uppercase>
            <input id="ch-city" className="input" maxLength={100} value={form.city} onChange={set('city')} />
          </Field>
          <Field label="Estado" htmlFor="ch-state">
            <select id="ch-state" className="input" value={form.state} onChange={set('state')}>
              <option value="">Selecione</option>
              {UFS.map((u) => <option key={u} value={u}>{u}</option>)}
            </select>
          </Field>
          <span />
          <Field label="Início" htmlFor="ch-start">
            <input id="ch-start" className="input" type="date" value={form.startDate} onChange={set('startDate')}
              disabled={initial?.started || initial?.status === 'IN_PROGRESS'} />
          </Field>
          <Field label="Término" htmlFor="ch-end">
            <input id="ch-end" className="input" type="date" value={form.endDate} onChange={set('endDate')} />
          </Field>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend className="form-section__title">Período de inscrição</legend>
        <p className="note">
          Opcional. Fora deste período o formulário fica fechado mesmo que esteja publicado. As inscrições fecham, no
          máximo, quando o campeonato começa (0h do dia de início, horário de Brasília).
        </p>
        <div className="grid-2">
          <Field label="Abre em" htmlFor="ch-reg-open">
            <input id="ch-reg-open" className="input" type="datetime-local" value={form.registrationOpensAt}
              onChange={set('registrationOpensAt')} />
          </Field>
          <Field label="Fecha em" htmlFor="ch-reg-close">
            <input id="ch-reg-close" className="input" type="datetime-local" value={form.registrationClosesAt}
              onChange={set('registrationClosesAt')} />
          </Field>
        </div>
      </fieldset>

      <fieldset className="form-section">
        <legend className="form-section__title">Pontuação do leaderboard</legend>
        <div className="choice">
          <label className="choice__option">
            <input type="radio" name="scoring" checked={form.scoringSystem === 'POINTS_TABLE'}
              onChange={() => setForm((f) => ({ ...f, scoringSystem: 'POINTS_TABLE' }))} />
            <span>
              <strong>Pontos por colocação</strong>
              <span className="choice__hint">O 1º lugar de cada prova ganha mais pontos; vence quem somar mais.</span>
            </span>
          </label>
          <label className="choice__option">
            <input type="radio" name="scoring" checked={form.scoringSystem === 'RANK_SUM'}
              onChange={() => setForm((f) => ({ ...f, scoringSystem: 'RANK_SUM' }))} />
            <span>
              <strong>Soma das colocações</strong>
              <span className="choice__hint">1º lugar vale 1, 2º vale 2…; vence a menor soma.</span>
            </span>
          </label>
        </div>
        {form.scoringSystem === 'POINTS_TABLE' && (
          <div className="grid-2">
            <Field label="Pontos do 1º lugar" htmlFor="ch-p1">
              <input id="ch-p1" className="input" type="number" min={1} value={form.pointsFirstPlace}
                onChange={set('pointsFirstPlace')} />
            </Field>
            <Field label="Pontos a menos por posição" htmlFor="ch-step"
              hint={`Ex.: 1º = ${form.pointsFirstPlace}, 2º = ${Number(form.pointsFirstPlace) - Number(form.pointsStep)}`}>
              <input id="ch-step" className="input" type="number" min={0} value={form.pointsStep}
                onChange={set('pointsStep')} />
            </Field>
          </div>
        )}
      </fieldset>

      <div className="form-actions">
        <InlineError error={error} />
        {saved && <p className="form-ok" role="status">Alterações salvas.</p>}
        <button type="submit" className="btn btn--primary btn--large" disabled={readOnly || busy || imageBusy}>
          {busy ? 'Salvando…' : submitLabel}
        </button>
      </div>
    </form>
  );
}
