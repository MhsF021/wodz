import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, Pencil, Trash2 } from 'lucide-react';
import { adminApi } from '../../api/endpoints';
import type { ChampionshipDetail, Workout, WorkoutInput, WorkoutType } from '../../api/types';
import { formatClock, parseDuration, workoutTypeLabel } from '../../lib/format';
import { Field } from '../../components/Field';
import { CategoryCards } from '../../components/CategoryCards';
import { Dialog } from '../../components/Dialog';
import { Empty, InlineError } from '../../components/States';

const TYPES: WorkoutType[] = ['FOR_TIME', 'AMRAP', 'MAX_LOAD', 'MAX_REPS'];
const closed = (c: ChampionshipDetail) => c.status === 'FINISHED' || c.status === 'CANCELLED';
const started = (c: ChampionshipDetail) => c.started || c.status === 'IN_PROGRESS';

export function WorkoutsTab({ championship: c }: { championship: ChampionshipDetail }) {
  const queryClient = useQueryClient();
  const [editingId, setEditingId] = useState<number | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const refresh = () => Promise.all([
    queryClient.invalidateQueries({ queryKey: ['admin-championship', c.id] }),
    queryClient.invalidateQueries({ queryKey: ['admin-heats', c.id] }),
  ]);
  const remove = useMutation({ mutationFn: (id: number) => adminApi.deleteWorkout(id),
    onSuccess: async () => { await refresh(); setDeletingId(null); } });
  const canEdit = !closed(c);
  const canCreate = canEdit && !started(c);

  if (!c.categories.length) return <Empty title="Cadastre as categorias primeiro">
    <p>As provas serão organizadas dentro de cada categoria.</p>
  </Empty>;

  function actions(w: Workout) {
    if (!canEdit) return null;
    if (deletingId === w.id && canCreate) return <div className="workout-delete-confirm">
      <p>Excluir a prova {w.number}: {w.name}?</p>
      <InlineError error={remove.error} />
      <div className="row-actions">
        <button className="btn btn--ghost btn--small" disabled={remove.isPending} onClick={() => setDeletingId(null)}>Cancelar</button>
        <button className="btn btn--danger btn--small" disabled={remove.isPending} onClick={() => remove.mutate(w.id)}>Confirmar exclusão</button>
      </div>
    </div>;
    return <div className="workout-detail-actions">
      <button className="btn btn--secondary btn--small" disabled={remove.isPending} onClick={() => { setDeletingId(null); setEditingId(w.id); }}>
        <Pencil size={14} aria-hidden />Editar prova
      </button>
      {canCreate && <button className="btn btn--ghost btn--small" disabled={remove.isPending} onClick={() => { remove.reset(); setDeletingId(w.id); }}>
        <Trash2 size={14} aria-hidden />Excluir
      </button>}
    </div>;
  }

  return <div className="stack organizer-workouts">
    <CategoryCards championship={c} organizer renderWorkoutActions={actions}
      filterActions={canCreate && <button type="button" className="btn btn--primary" aria-haspopup="dialog" onClick={() => setCreating(true)}><Plus size={16} aria-hidden />Nova Prova</button>}
      onCategoryClose={() => { setEditingId(null); setDeletingId(null); }}
      renderWorkoutEditor={w => canEdit && editingId === w.id ? <div className="workout-editor">
        <h4>Editar prova {w.number}</h4>
        <WorkoutForm key={w.id} championship={c} workout={w} onSaved={refresh} onCancel={() => setEditingId(null)} />
      </div> : null} />
    {canCreate && creating && <Dialog title="Nova Prova" closeLabel="Fechar nova prova" onClose={() => setCreating(false)}>
      <WorkoutForm championship={c} onSaved={async () => { await refresh(); setCreating(false); }} onCancel={() => setCreating(false)} />
    </Dialog>}
  </div>;
}

function initialForm(workout?: Workout) {
  return { number: workout ? String(workout.number) : '', name: workout?.name ?? '', type: workout?.type ?? 'FOR_TIME' as WorkoutType,
    timeCap: workout?.timeCapSeconds ? formatClock(workout.timeCapSeconds * 1000) : '',
    repsPerRound: workout?.repsPerRound ? String(workout.repsPerRound) : '',
    maxReps: workout?.maxReps ? String(workout.maxReps) : '',
    hasTiebreak: workout?.hasTiebreak ?? false, description: workout?.description ?? '' };
}

export function WorkoutForm({ championship: c, workout, onSaved, onCancel }: {
  championship: ChampionshipDetail; workout?: Workout; onSaved: () => Promise<unknown>; onCancel?: () => void;
}) {
  const [form, setForm] = useState(() => initialForm(workout));
  const [selected, setSelected] = useState(() => c.categories.map(cat => cat.id));
  const [error, setError] = useState<string | null>(null);
  const limited = Boolean(workout) && started(c);
  const blocked = closed(c);
  const id = `wk-${workout?.id ?? 'new'}`;
  const numbers = c.categories.filter(cat => selected.includes(cat.id)).flatMap(cat => cat.workouts.map(w => w.number));
  const suggested = Math.max(0, ...numbers) + 1;
  const save = useMutation({ mutationFn: (input: WorkoutInput) => workout
    ? adminApi.updateWorkout(workout.id, input).then(w => [w]) : adminApi.createWorkouts(c.id, input),
    onSuccess: async () => { await onSaved(); if (workout) onCancel?.(); else setForm(initialForm()); } });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || save.isPending) return;
    const cap = form.timeCap ? parseDuration(form.timeCap) : null;
    const number = Number(form.number || suggested);
    if (!form.name.trim()) return setError('Dê um nome para a prova');
    if (!workout && !selected.length) return setError('Escolha ao menos uma categoria');
    if (!Number.isInteger(number) || number < 1 || number > 99) return setError('Informe um número de prova entre 1 e 99');
    if (form.timeCap && cap === null) return setError('Duração inválida. Use mm:ss, como 12:00');
    if (form.type === 'AMRAP' && !cap) return setError('Informe a duração do AMRAP');
    if (form.type === 'AMRAP' && (!Number.isInteger(Number(form.repsPerRound)) || Number(form.repsPerRound) < 1)) return setError('Informe quantas repetições formam um round completo');
    if (form.type === 'FOR_TIME' && !form.maxReps) return setError('Informe o total de repetições da prova para validar os resultados no CAP');
    if (form.maxReps && (!Number.isInteger(Number(form.maxReps)) || Number(form.maxReps) < 1)) return setError('O limite de repetições deve ser um número inteiro maior que zero');
    setError(null);
    save.mutate({ categoryIds: workout ? undefined : selected, number, name: form.name.trim(), type: form.type,
      timeCapSeconds: cap ? Math.round(cap / 1000) : null,
      repsPerRound: form.type === 'AMRAP' && form.repsPerRound ? Number(form.repsPerRound) : null,
      maxReps: ['FOR_TIME', 'AMRAP'].includes(form.type) && form.maxReps ? Number(form.maxReps) : null,
      hasTiebreak: limited ? workout!.hasTiebreak : form.type !== 'MAX_LOAD' && form.hasTiebreak, description: form.description.trim() || null });
  }

  return <form className="form workout-form" onSubmit={submit}>
    <fieldset className="readonly-fieldset" disabled={blocked || save.isPending}>
      {!workout && <fieldset className="checks"><legend className="field__label">Categorias que receberão a prova</legend>
        {c.categories.map(cat => <label className="check" key={cat.id}>
          <input type="checkbox" checked={selected.includes(cat.id)} onChange={() => setSelected(ids => ids.includes(cat.id) ? ids.filter(id => id !== cat.id) : [...ids, cat.id])} />{cat.name}
        </label>)}
      </fieldset>}
      <Field label="Nome da prova" htmlFor={`${id}-name`} uppercase>
        <input id={`${id}-name`} className="input" maxLength={120} required autoFocus={Boolean(workout)} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />
      </Field>
      {limited && <p className="note workout-editor__note">O formato e a duração estão bloqueados. Você pode corrigir nome e descrição e preencher os limites de repetições que ainda não foram informados.</p>}
      <fieldset className="readonly-fieldset workout-specs" disabled={limited}>
        <div className="grid-2">
          <Field label="Número" htmlFor={`${id}-number`} hint={!workout ? `Sugestão: ${suggested}` : undefined}>
            <input id={`${id}-number`} className="input" type="number" min={1} max={99} required={Boolean(workout)} placeholder={String(suggested)} value={form.number} onChange={e => setForm({ ...form, number: e.target.value })} />
          </Field>
          <Field label="Tipo" htmlFor={`${id}-type`}>
            <select id={`${id}-type`} className="input" value={form.type} onChange={e => setForm({ ...form, type: e.target.value as WorkoutType })}>
              {TYPES.map(type => <option key={type} value={type}>{workoutTypeLabel[type]}</option>)}
            </select>
          </Field>
          <Field label="Duração / time cap (mm:ss)" htmlFor={`${id}-cap`} hint="Define os horários das baterias.">
            <input id={`${id}-cap`} className="input" placeholder="12:00" value={form.timeCap} onChange={e => setForm({ ...form, timeCap: e.target.value })} />
          </Field>
        </div>
        {form.type !== 'MAX_LOAD' && <label className="check"><input type="checkbox" checked={form.hasTiebreak} onChange={e => setForm({ ...form, hasTiebreak: e.target.checked })} />Com tempo de desempate (tiebreak)</label>}
      </fieldset>
      {form.type === 'AMRAP' && <Field label="Repetições por round" htmlFor={`${id}-rpr`} hint="Some todas as repetições de um round. As reps extras do resultado devem ser menores que esse número.">
        <input id={`${id}-rpr`} className="input" type="number" min={1} step={1} required disabled={limited && workout?.repsPerRound != null}
          value={form.repsPerRound} onChange={e => setForm({ ...form, repsPerRound: e.target.value })} />
      </Field>}
      {['FOR_TIME', 'AMRAP'].includes(form.type) && <Field label={form.type === 'FOR_TIME' ? 'Total de repetições da prova' : 'Limite total de repetições (opcional)'} htmlFor={`${id}-max-reps`}
        hint={form.type === 'FOR_TIME' ? 'Some as repetições de todos os movimentos. Ex.: 21 + 21 + 15 + 15 + 9 + 9 = 90 reps. O resultado no CAP não pode ultrapassar esse total.' : 'Preencha apenas se a prova tiver um teto total. Sem teto, o AMRAP aceita quantos rounds completos couberem no tempo.'}>
        <input id={`${id}-max-reps`} className="input" type="number" min={1} step={1} required={form.type === 'FOR_TIME'} placeholder="Ex.: 90"
          disabled={limited && workout?.maxReps != null} value={form.maxReps} onChange={e => setForm({ ...form, maxReps: e.target.value })} />
      </Field>}
      <Field label="Descrição do WOD" htmlFor={`${id}-description`} uppercase>
        <textarea id={`${id}-description`} className="input" rows={5} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} />
      </Field>
      {error && <p className="form-error" role="alert">{error}</p>}
      <InlineError error={save.error} />
      {save.isSuccess && !workout && <p className="form-ok" role="status">Prova adicionada.</p>}
      <div className="row-actions">
        <button className="btn btn--primary" type="submit">{workout ? 'Salvar prova' : 'Adicionar prova'}</button>
        {onCancel && <button className="btn btn--ghost" type="button" onClick={onCancel}>{workout ? 'Cancelar edição' : 'Cancelar'}</button>}
      </div>
    </fieldset>
  </form>;
}
