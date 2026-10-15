import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { adminApi } from '../../api/endpoints';
import type { Category, CategoryInput, ChampionshipDetail, MovementStandard } from '../../api/types';
import { money } from '../../lib/format';
import { categoryComposition, compositionLabel } from '../../lib/categoryComposition';
import { isAvailabilitySymbol, normalizeMovementStandard } from '../../lib/movementStandards';
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
    setStandards((c.movementStandards ?? []).map(row => normalizeMovementStandard(row, c.genderPolicy)));
    setForm({
      name: c.name,
      description: c.description ?? '',
      teamSize: String(c.teamSize),
      maxTeams: c.maxTeams ? String(c.maxTeams) : '',
      price: String(c.price),
      displayOrder: String(c.displayOrder + 1),
    });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!form.name.trim() || form.price === '') {
      setLocalError('Informe o nome e o valor da inscrição');
      return;
    }
    if (form.displayOrder !== '' && (!Number.isInteger(Number(form.displayOrder)) || Number(form.displayOrder) < 1)) {
      setLocalError('Informe uma posição inteira a partir de 1 ou deixe o campo em branco para definir automaticamente');
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
    if (standards.some(row => row.kind === 'LOAD' && loadFields.some(field => isAvailabilitySymbol(row[field.key])))) {
      setLocalError('Use a caixa de seleção para indicar se o item poderá ser pedido. No campo de carga, informe a carga, por exemplo: 45 kg.');
      return;
    }
    save.mutate({
      movementStandards: standards.map(row => ({ movement: row.movement.trim(), kind: row.kind,
        male: row.kind === 'MOVEMENT' ? '—' : row.male.trim() || '—',
        female: row.kind === 'MOVEMENT' ? '—' : row.female.trim() || '—', allowed: row.allowed !== false })),
      genderPolicy,
      allowedMaleCounts: genderPolicy === 'MIXED' ? allowedMaleCounts.filter(n => n > 0 && n < Number(form.teamSize)) : [],
      name: form.name.trim(),
      description: form.description.trim() || null,
      teamSize: Number(form.teamSize),
      maxTeams: form.maxTeams ? Number(form.maxTeams) : null,
      price: Number(form.price.replace(',', '.')),
      displayOrder: form.displayOrder ? Number(form.displayOrder) - 1 : championship.categories.length,
    });
  }

  const set = (key: keyof typeof EMPTY) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [key]: e.target.value }));
  const loadFields: { key: 'male' | 'female'; label: string; placeholder: string }[] = genderPolicy === 'MIXED'
    ? [{ key: 'male', label: 'Carga masculina', placeholder: 'Ex.: 30 kg' }, { key: 'female', label: 'Carga feminina', placeholder: 'Ex.: 15 kg' }]
    : genderPolicy === 'MALE' ? [{ key: 'male', label: 'Carga', placeholder: 'Ex.: 30 kg' }]
    : genderPolicy === 'FEMALE' ? [{ key: 'female', label: 'Carga', placeholder: 'Ex.: 15 kg' }] : [];

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
                  Equipes de {c.teamSize}, {Number(c.price) === 0 ? 'gratuita' : `${money(c.price)} por atleta · ${money(Number(c.price) * c.teamSize)} por equipe`},{' '}
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
            <Field label="Valor por atleta (R$)" htmlFor="cat-price" hint="O total da inscrição é este valor multiplicado pelo número de atletas da equipe.">
              <input id="cat-price" className="input" inputMode="decimal" placeholder="220,00" value={form.price} onChange={set('price')} />
            </Field>
            <Field label="Limite de equipes" htmlFor="cat-max" hint="Vazio = sem limite">
              <input id="cat-max" className="input" type="number" min={1} value={form.maxTeams} onChange={set('maxTeams')} />
            </Field>
            <Field label="Posição da categoria na lista" htmlFor="cat-order"
              hint="Define a ordem das categorias nas listas do campeonato e na geração das baterias: números menores aparecem primeiro. Exemplo: 1 = Iniciante, 2 = Scaled e 3 = RX. Se deixar em branco, a posição será definida automaticamente.">
              <input id="cat-order" className="input" type="number" min={1} step={1} placeholder="Ex.: 1" value={form.displayOrder} onChange={set('displayOrder')} />
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
            <p className="note" id="movement-editor-help">Marque a caixa dos itens que poderão ser pedidos nas provas. Desmarcado = não será pedido.</p>
            <p className="note">Escolha o tipo: <strong>Com carga</strong> para informar o peso (ex.: Clean and jerk) ou <strong>Somente movimento</strong> quando não houver peso a informar (ex.: Toes to bar).</p>
            {!genderPolicy && <p className="note">Selecione o tipo de categoria acima para informar as cargas.</p>}
            {standards.map((row, index) => <div className="movement-editor__row" key={index}>
              <div className="movement-editor__header">
                <label className="movement-editor__allowed"><input type="checkbox" checked={row.allowed !== false}
                  aria-label={`Incluir ${row.movement || `item ${index + 1}`} nas provas`} aria-describedby="movement-editor-help"
                  title="Marque se este item poderá ser pedido nas provas"
                  onChange={e => setStandards(rows => rows.map((item, i) => i === index ? { ...item, allowed: e.target.checked } : item))} /></label>
                <span>Item {index + 1}</span>
                <button type="button" className="btn btn--ghost" aria-label={`Remover movimento ${index + 1}`} onClick={() => setStandards(rows => rows.filter((_, i) => i !== index))}><Trash2 size={15} aria-hidden /></button>
              </div>
              <label className="movement-editor__kind">Tipo<select className="input" aria-label={`Tipo do item ${index + 1}`} value={row.kind}
                onChange={e => setStandards(rows => rows.map((item, i) => i === index ? { ...item, kind: e.target.value as MovementStandard['kind'] } : item))}>
                <option value="LOAD">Com carga</option><option value="MOVEMENT">Somente movimento</option>
              </select></label>
              <label className="movement-editor__name">Movimento {index + 1}<input className="input" maxLength={80} value={row.movement} placeholder={row.kind === 'MOVEMENT' ? 'Ex.: Toes to bar' : 'Ex.: Clean and jerk'}
                onChange={e => setStandards(rows => rows.map((item, i) => i === index ? { ...item, movement: e.target.value } : item))} /></label>
              {row.kind === 'LOAD' && loadFields.map(field => <label key={field.key} className={genderPolicy !== 'MIXED' ? 'movement-editor__load--single' : undefined}>
                {field.label}<input className="input" maxLength={60} disabled={row.allowed === false} value={row[field.key]} placeholder={field.placeholder}
                  onChange={e => setStandards(rows => rows.map((item, i) => i === index ? { ...item, [field.key]: e.target.value } : item))} />
              </label>)}
            </div>)}
            <button type="button" className="btn btn--ghost btn--small" disabled={standards.length >= 30}
              onClick={() => setStandards(rows => [...rows, { movement: '', male: '', female: '', allowed: false, kind: 'LOAD' }])}><Plus size={15} aria-hidden /> Adicionar movimento</button>
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
