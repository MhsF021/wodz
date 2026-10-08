import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Pencil, Trash2 } from 'lucide-react';
import { adminApi } from '../../api/endpoints';
import type { ChampionshipDetail, Workout, WorkoutInput, WorkoutType } from '../../api/types';
import { capLabel, formatClock, parseDuration, workoutTypeLabel } from '../../lib/format';
import { Field } from '../../components/Field';
import { PlateChip } from '../../components/Plate';
import { Empty, InlineError } from '../../components/States';

const TYPES: WorkoutType[] = ['FOR_TIME', 'AMRAP', 'MAX_LOAD', 'MAX_REPS'];

const TYPE_HINT: Record<WorkoutType, string> = {
  FOR_TIME: 'Lança o tempo de cada equipe; quem estoura o cap é lançado como CAP + repetições.',
  AMRAP: 'Lança o total de repetições (ou rounds + reps) dentro do tempo.',
  MAX_LOAD: 'Lança a carga em kg (PR).',
  MAX_REPS: 'Lança o máximo de repetições.',
};

const emptyForm = () => ({
  number: '',
  name: '',
  type: 'FOR_TIME' as WorkoutType,
  timeCap: '',
  repsPerRound: '',
  hasTiebreak: false,
  description: '',
});

export function WorkoutsTab({ championship }: { championship: ChampionshipDetail }) {
  const queryClient = useQueryClient();
  const categories = championship.categories;
  const [editing, setEditing] = useState<Workout | null>(null);
  const [selected, setSelected] = useState<number[]>(() => categories.map((c) => c.id));
  const [form, setForm] = useState(emptyForm);
  const [localError, setLocalError] = useState<string | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin-championship', championship.id] });

  const save = useMutation({
    mutationFn: (input: WorkoutInput) =>
      editing
        ? adminApi.updateWorkout(editing.id, input).then((workout) => [workout])
        : adminApi.createWorkouts(championship.id, input),
    onSuccess: () => {
      refresh();
      setEditing(null);
      setForm(emptyForm());
    },
  });
  const remove = useMutation({ mutationFn: (id: number) => adminApi.deleteWorkout(id), onSuccess: refresh });

  if (categories.length === 0) {
    return (
      <Empty title="Cadastre as categorias primeiro">
        <p>Cada prova pertence a uma categoria, como Prova 1 do Iniciante e Prova 1 do Scaled.</p>
      </Empty>
    );
  }

  const nextNumber = () => {
    const nums = categories
      .filter((c) => selected.includes(c.id))
      .flatMap((c) => c.workouts.map((w) => w.number));
    return String((nums.length ? Math.max(...nums) : 0) + 1);
  };

  function startEdit(w: Workout) {
    setEditing(w);
    setForm({
      number: String(w.number),
      name: w.name,
      type: w.type,
      timeCap: w.timeCapSeconds ? formatClock(w.timeCapSeconds * 1000) : '',
      repsPerRound: w.repsPerRound ? String(w.repsPerRound) : '',
      hasTiebreak: w.hasTiebreak,
      description: w.description ?? '',
    });
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const capMs = form.timeCap ? parseDuration(form.timeCap) : null;
    if (!form.name.trim()) return setLocalError('Dê um nome para a prova');
    if (!editing && selected.length === 0) return setLocalError('Escolha ao menos uma categoria');
    if (form.timeCap && capMs === null) return setLocalError('Time cap inválido. Use o formato mm:ss, ex.: 12:00');
    if (form.type === 'AMRAP' && !capMs) return setLocalError('Informe a duração do AMRAP no campo time cap');
    setLocalError(null);
    save.mutate({
      categoryIds: editing ? undefined : selected,
      number: Number(form.number || nextNumber()),
      name: form.name.trim(),
      type: form.type,
      timeCapSeconds: capMs ? Math.round(capMs / 1000) : null,
      repsPerRound: form.type === 'AMRAP' && form.repsPerRound ? Number(form.repsPerRound) : null,
      hasTiebreak: form.hasTiebreak,
      description: form.description.trim() || null,
    });
  }

  const toggle = (id: number) =>
    setSelected((list) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]));

  return (
    <div className="split">
      <section aria-labelledby="wk-list">
        <h2 id="wk-list" className="section-title">Provas por categoria</h2>
        {categories.map((cat, index) => (
          <div key={cat.id} className="group">
            <h3 className="group__title"><PlateChip index={index} name={cat.name} /></h3>
            {cat.workouts.length === 0 ? (
              <p className="note">Nenhuma prova nesta categoria.</p>
            ) : (
              <ul className="rows">
                {cat.workouts.map((w) => (
                  <li key={w.id} className="rows__item">
                    <div>
                      <p className="rows__title">Prova {w.number}: {w.name}</p>
                      <p className="rows__meta">
                        {workoutTypeLabel[w.type]}
                        {capLabel(w.timeCapSeconds) ? `, cap ${capLabel(w.timeCapSeconds)}` : ''}
                        {w.hasTiebreak ? ', com desempate' : ''}
                      </p>
                    </div>
                    <div className="rows__actions">
                      <button type="button" className="btn btn--ghost btn--small" onClick={() => startEdit(w)}>
                        <Pencil size={14} aria-hidden /> Editar
                      </button>
                      <button type="button" className="btn btn--danger btn--small"
                        onClick={() => window.confirm(`Excluir a prova ${w.number} (${cat.name})?`) && remove.mutate(w.id)}>
                        <Trash2 size={14} aria-hidden /> Excluir
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
        <InlineError error={remove.error} />
      </section>

      <section aria-labelledby="wk-form" className="panel">
        <h2 id="wk-form" className="panel__title">
          {editing ? `Editar prova ${editing.number}` : 'Nova prova'}
        </h2>
        <form className="form" onSubmit={submit} noValidate>
          {!editing && (
            <fieldset className="checks">
              <legend className="field__label">Criar para as categorias</legend>
              {categories.map((cat, index) => (
                <label key={cat.id} className="check">
                  <input type="checkbox" checked={selected.includes(cat.id)} onChange={() => toggle(cat.id)} />
                  <PlateChip index={index} name={cat.name} />
                </label>
              ))}
            </fieldset>
          )}
          <div className="grid-2">
            <Field label="Número" htmlFor="wk-number" hint={editing ? undefined : `Sugestão: ${nextNumber()}`}>
              <input id="wk-number" className="input" type="number" min={1} value={form.number}
                onChange={(e) => setForm({ ...form, number: e.target.value })} />
            </Field>
            <Field label="Tipo" htmlFor="wk-type">
              <select id="wk-type" className="input" value={form.type}
                onChange={(e) => setForm({ ...form, type: e.target.value as WorkoutType })}>
                {TYPES.map((t) => <option key={t} value={t}>{workoutTypeLabel[t]}</option>)}
              </select>
            </Field>
          </div>
          <p className="note">{TYPE_HINT[form.type]}</p>
          <Field label="Nome" htmlFor="wk-name">
            <input id="wk-name" className="input" maxLength={120} placeholder="Ex.: Abertura" value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Field>
          <div className="grid-2">
            <Field label={form.type === 'AMRAP' ? 'Duração (mm:ss)' : 'Time cap (mm:ss)'} htmlFor="wk-cap">
              <input id="wk-cap" className="input" placeholder="12:00" value={form.timeCap}
                onChange={(e) => setForm({ ...form, timeCap: e.target.value })} />
            </Field>
            {form.type === 'AMRAP' && (
              <Field label="Repetições por round" htmlFor="wk-rpr" hint="Opcional. Exibe 5 rds + 12.">
                <input id="wk-rpr" className="input" type="number" min={1} value={form.repsPerRound}
                  onChange={(e) => setForm({ ...form, repsPerRound: e.target.value })} />
              </Field>
            )}
          </div>
          <label className="check">
            <input type="checkbox" checked={form.hasTiebreak}
              onChange={(e) => setForm({ ...form, hasTiebreak: e.target.checked })} />
            Prova com tempo de desempate (tiebreak)
          </label>
          <Field label="Descrição do WOD" htmlFor="wk-desc">
            <textarea id="wk-desc" className="input" rows={6} placeholder={'21-15-9\nThrusters 43/30 kg\nPull-ups'}
              value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          </Field>
          {localError && <p className="form-error" role="alert">{localError}</p>}
          <InlineError error={save.error} />
          <div className="row-actions">
            <button type="submit" className="btn btn--primary" disabled={save.isPending}>
              {editing ? 'Salvar prova' : 'Adicionar prova'}
            </button>
            {editing && (
              <button type="button" className="btn btn--ghost" onClick={() => { setEditing(null); setForm(emptyForm()); }}>
                Cancelar edição
              </button>
            )}
          </div>
        </form>
      </section>
    </div>
  );
}
