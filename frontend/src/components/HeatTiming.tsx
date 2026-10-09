import type { HeatView } from '../api/types';
import { clockTime, dayLabel, formatClock } from '../lib/format';

const exactTime = (iso: string) => new Date(iso).toLocaleTimeString('pt-BR', {
  hour: '2-digit', minute: '2-digit', second: '2-digit',
});

function endLabel(start: string, end: string) {
  return new Date(start).toDateString() === new Date(end).toDateString()
    ? clockTime(end) : `${dayLabel(end)}, ${clockTime(end)}`;
}

export function HeatTiming({ heat }: { heat: HeatView }) {
  const start = heat.startedAt ?? heat.scheduledAt;
  const end = heat.finishedAt ?? heat.expectedFinishAt;
  return (
    <div className="heat-timing">
      {heat.startedAt && <span>Início real: <strong>{exactTime(heat.startedAt)}</strong></span>}
      {end && <span>{heat.finishedAt ? 'Término' : 'Término previsto'}: <strong>{endLabel(start, end)}</strong></span>}
      {heat.actualDurationSeconds != null ? (
        <span>Tempo total: {formatClock(heat.actualDurationSeconds * 1000)}</span>
      ) : heat.durationSeconds != null ? <span>Duração: {formatClock(heat.durationSeconds * 1000)}</span> : null}
      <span>Intervalo após a prova: {heat.intervalMinutes} min</span>
    </div>
  );
}
