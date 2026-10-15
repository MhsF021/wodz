import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import type { ChampionshipDetail } from '../../api/types';
import { fromLocalInput, toLocalInput } from '../../lib/format';
import { Field } from '../../components/Field';
import { InlineError } from '../../components/States';

export function HeatSettingsPanel({ championship: c, editing = false }: { championship: ChampionshipDetail; editing?: boolean }) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ lanes: c.heatLanes ? String(c.heatLanes) : '',
    start: c.heatFirstStart ? toLocalInput(c.heatFirstStart) : `${c.startDate}T08:00`,
    interval: String(c.heatIntervalMinutes ?? 15) });
  useEffect(() => setForm({ lanes: c.heatLanes ? String(c.heatLanes) : '',
    start: c.heatFirstStart ? toLocalInput(c.heatFirstStart) : `${c.startDate}T08:00`,
    interval: String(c.heatIntervalMinutes ?? 15) }), [c.heatLanes, c.heatFirstStart, c.heatIntervalMinutes, c.startDate]);
  const save = useMutation({ mutationFn: () => adminApi.saveHeatSettings(c.id, {
    lanes: Number(form.lanes), firstStart: fromLocalInput(form.start)!, intervalMinutes: Number(form.interval),
  }), onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['admin-championship', c.id] });
    queryClient.invalidateQueries({ queryKey: ['admin-heats', c.id] });
  } });
  const locked = c.status === 'FINISHED' || c.status === 'CANCELLED'
    || ((c.started || c.status === 'IN_PROGRESS') && c.heatLanes !== null);
  return <section className="panel" aria-labelledby="heat-settings-title">
    <h2 id="heat-settings-title" className="panel__title">Configuração das baterias</h2>
    <form className="form" onSubmit={e => { e.preventDefault(); save.mutate(); }}>
      <fieldset className="heat-settings-fields" disabled={locked || editing || save.isPending}>
        <div className="heat-filters">
          <Field label="Equipes por bateria *" htmlFor="heat-lanes">
            <input id="heat-lanes" className="input" type="number" min={1} max={8} step={1} required
              placeholder="Informe de 1 a 8" value={form.lanes} onChange={e => setForm({ ...form, lanes: e.target.value })} />
          </Field>
          <Field label="Horário da primeira bateria *" htmlFor="heat-first">
            <input id="heat-first" className="input" type="datetime-local" required value={form.start}
              onChange={e => setForm({ ...form, start: e.target.value })} />
          </Field>
          <Field label="Intervalo após o término (min) *" htmlFor="heat-interval">
            <input id="heat-interval" className="input" type="number" min={0} max={240} step={1} required
              value={form.interval} onChange={e => setForm({ ...form, interval: e.target.value })} />
          </Field>
        </div>
        {!locked && <button className="btn btn--secondary" type="submit">{c.registrationFlag ? 'Salvar configuração' : 'Salvar e gerar baterias'}</button>}
      </fieldset>
      <InlineError error={save.error} />
      {save.isSuccess && <p className="form-ok" role="status">Configuração salva.</p>}
    </form>
  </section>;
}
