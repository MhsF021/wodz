import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { adminApi } from '../../api/endpoints';
import type { Category, CategoryInput, ChampionshipDetail, MovementStandard } from '../../api/types';
import { money } from '../../lib/format';
import { categoryComposition, compositionLabel } from '../../lib/categoryComposition';
import { Field } from '../../components/Field';
import { PlateChip } from '../../components/Plate';
import { Empty, InlineError } from '../../components/States';

const EMPTY = { name: '', description: '', teamSize: '2', maxTeams: '', price: '', displayOrder: '' };

export function CategoriesTab({ championship }: { championship: ChampionshipDetail }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Category | null>(null);
  const [form, setForm] = useState(EMPTY);
  const [genderPolicy, setGenderPolicy] = useState<CategoryInput['genderPolicy'] | ''>('');
  const [allowedMaleCounts, setAllowedMaleCounts] = useState<number[]>([]);
  const [localError, setLocalError] = useState<string | null>(null);
  const [standards, setStandards] = useState<MovementStandard[]>([]);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin-championship', championship.id] });

  const save = useMutation({
    mutationFn: (input: CategoryInput) =>
      editing ? adminApi.updateCategory(editing.id, input) : adminApi.createCategory(championship.id, input),
    onSuccess: () => {
      refresh();
      setEditing(null);
      setForm(EMPTY);
      setGenderPolicy('');
      setAllowedMaleCounts([]);
      setStandards([]);
    },
  });
  const remove = useMutation({ mutationFn: (id: number) => adminApi.deleteCategory(id), onSuccess: refresh });

  function startEdit(c: Category) {
    setEditing(c);
    setLocalError(null);
    setGenderPolicy(c.genderPolicy ?? '');
    setAllowedMaleCounts(c.allowedMaleCounts ?? []);
    setStandards(c.movementStandards ?? []);
    setForm({
      name: c.name,
      description: c.description ?? '',
      teamSize: String(c.teamSize),
      maxTeams: c.maxTeams ? String(c.maxTeams) : '',
      price: String(c.price),
      displayOrder: String(c.displayOrder),
    });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.name.trim() || form.price === '') {
      setLocalError('Informe o nome e o valor da inscrição');
      return;
    }
    setLocalError(null);
    if (standards.some(row => !row.movement.trim())) {
      setLocalError('Informe o nome de cada movimento ou remova a linha vazia');
      return;
    }
    if (!genderPolicy || (genderPolicy === 'MIXED' && !allowedMaleCounts.some(n => n > 0 && n < Number(form.teamSize)))) {
      setLocalError('Selecione o tipo da categoria e as composições permitidas para equipes mistas');
      return;
    }
    save.mutate({
      movementStandards: standards.map(row => ({ movement: row.movement.trim(), male: row.male.trim() || '—', female: row.female.trim() || '—' })),
      genderPolicy,
      allowedMaleCounts: genderPolicy === 'MIXED' ? allowedMaleCounts.filter(n => n > 0 && n < Number(form.teamSize)) : [],
      name: form.name.trim(),
      description: form.description.trim() || null,
      teamSize: Number(form.teamSize),
      maxTeams: form.maxTeams ? Number(form.maxTeams) : null,
      price: Number(form.price.replace(',', '.')),
      displayOrder: form.displayOrder ? Number(form.displayOrder) : championship.categories.length,
    });
  }

  const set = (key: keyof typeof EMPTY) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [key]: e.target.value }));

  return (
    <div className="split">
      <section aria-labelledby="cat-list">
        <h2 id="cat-list" className="section-title">Categorias</h2>
        {championship.categories.length === 0 && (
          <Empty title="Nenhuma categoria cadastrada">
            <p>Crie as categorias (ex.: Iniciante, Scaled, RX) para depois cadastrar as provas de cada uma.</p>
          </Empty>
        )}
        <ul className="rows">
          {championship.categories.map((c, i) => (
            <li key={c.id} className="rows__item">
              <div>
                <PlateChip index={i} name={c.name} />
                <p className="rows__meta">{categoryComposition(c)}</p>
                <p className="rows__meta">
                  Equipes de {c.teamSize}, {Number(c.price) === 0 ? 'gratuita' : money(c.price)},{' '}
                  {c.workouts.length} prova{c.workouts.length === 1 ? '' : 's'}
                </p>
              </div>
              <div className="rows__actions">
                <button type="button" className="btn btn--ghost btn--small" onClick={() => startEdit(c)}>
                  <Pencil size={14} aria-hidden /> Editar
                </button>
                <button type="button" className="btn btn--danger btn--small"
                  onClick={() => window.confirm(`Excluir a categoria ${c.name}?`) && remove.mutate(c.id)}>
                  <Trash2 size={14} aria-hidden /> Excluir
                </button>
              </div>
            </li>
          ))}
        </ul>
        <InlineError error={remove.error} />
      </section>

      <section aria-labelledby="cat-form" className="panel">
        <h2 id="cat-form" className="panel__title">{editing ? `Editar ${editing.name}` : 'Nova categoria'}</h2>
        <form className="form" onSubmit={submit} noValidate>
          <Field label="Nome" htmlFor="cat-name" uppercase>
            <input id="cat-name" className="input" maxLength={80} placeholder="Ex.: Scaled" value={form.name} onChange={set('name')} />
          </Field>
          <div className="grid-2">
            <Field label="Atletas por equipe" htmlFor="cat-size">
              <input id="cat-size" className="input" type="number" min={1} max={10} value={form.teamSize} onChange={set('teamSize')} />
            </Field>
            <Field label="Valor da inscrição (R$)" htmlFor="cat-price">
              <input id="cat-price" className="input" inputMode="decimal" placeholder="220,00" value={form.price} onChange={set('price')} />
            </Field>
            <Field label="Limite de equipes" htmlFor="cat-max" hint="Vazio = sem limite">
              <input id="cat-max" className="input" type="number" min={1} value={form.maxTeams} onChange={set('maxTeams')} />
            </Field>
            <Field label="Ordem de exibição" htmlFor="cat-order">
              <input id="cat-order" className="input" type="number" min={0} value={form.displayOrder} onChange={set('displayOrder')} />
            </Field>
          </div>
          <Field label="Tipo de categoria" htmlFor="cat-gender" hint="As inscrições devem respeitar a composição escolhida. Após receber inscrições, estas regras não podem ser alteradas.">
            <select id="cat-gender" className="input" value={genderPolicy}
              onChange={e => setGenderPolicy(e.target.value as CategoryInput['genderPolicy'])}>
              <option value="">Selecione</option>
              <option value="FEMALE">Feminina</option>
              <option value="MALE">Masculina</option>
              <option value="MIXED">Mista</option>
            </select>
          </Field>
          {genderPolicy === 'MIXED' && (
            <fieldset className="form-section">
              <legend className="form-section__title">Composições permitidas</legend>
              <p className="note">Selecione uma ou mais combinações. Uma equipe mista precisa de ao menos dois atletas.</p>
              <div className="choice choice--compositions">
              {Array.from({ length: Math.max(0, Math.min(10, Number(form.teamSize) || 0) - 1) }, (_, i) => i + 1).map(men => (
                <label key={men} className="choice__option">
                  <input type="checkbox" checked={allowedMaleCounts.includes(men)}
                    onChange={e => setAllowedMaleCounts(values => e.target.checked ? [...values, men] : values.filter(n => n !== men))} />
                  {compositionLabel(men, Number(form.teamSize))}
                </label>
              ))}
              </div>
            </fieldset>
          )}
          <Field label="Descrição (opcional)" htmlFor="cat-desc" uppercase>
            <textarea id="cat-desc" className="input" rows={3} value={form.description} onChange={set('description')} />
          </Field>
          <fieldset className="movement-editor">
            <legend>Cargas e movimentos</legend>
            <p className="note">Informe a carga (ex.: 30 kg), a variação (ex.: Box step up), ✓ para previsto ou ✕ para não previsto. Campos vazios aparecem como não informados.</p>
            {standards.map((row, index) => <div className="movement-editor__row" key={index}>
              <label>Movimento {index + 1}<input className="input" maxLength={80} value={row.movement} placeholder="Ex.: Clean and jerk"
                onChange={e => setStandards(rows => rows.map((item, i) => i === index ? { ...item, movement: e.target.value } : item))} /></label>
              <label>Masculino<input className="input" maxLength={60} value={row.male} placeholder="Ex.: 30 kg"
                onChange={e => setStandards(rows => rows.map((item, i) => i === index ? { ...item, male: e.target.value } : item))} /></label>
              <label>Feminino<input className="input" maxLength={60} value={row.female} placeholder="Ex.: 15 kg"
                onChange={e => setStandards(rows => rows.map((item, i) => i === index ? { ...item, female: e.target.value } : item))} /></label>
              <button type="button" className="btn btn--ghost" aria-label={`Remover movimento ${index + 1}`} onClick={() => setStandards(rows => rows.filter((_, i) => i !== index))}><Trash2 size={15} aria-hidden /></button>
            </div>)}
            <button type="button" className="btn btn--ghost btn--small" disabled={standards.length >= 30}
              onClick={() => setStandards(rows => [...rows, { movement: '', male: '', female: '' }])}><Plus size={15} aria-hidden /> Adicionar movimento</button>
          </fieldset>
          {localError && <p className="form-error" role="alert">{localError}</p>}
          <InlineError error={save.error} />
          <div className="row-actions">
            <button type="submit" className="btn btn--primary" disabled={save.isPending}>
              {editing ? 'Salvar categoria' : 'Adicionar categoria'}
            </button>
            {editing && (
              <button type="button" className="btn btn--ghost" onClick={() => { setEditing(null); setForm(EMPTY); setGenderPolicy(''); setAllowedMaleCounts([]); setStandards([]); setLocalError(null); }}>
                Cancelar edição
              </button>
            )}
          </div>
        </form>
      </section>
    </div>
  );
}
