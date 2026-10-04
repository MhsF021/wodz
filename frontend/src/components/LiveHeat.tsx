import { useEffect, useState } from 'react';
import type { HeatView } from '../api/types';
import { formatClock } from '../lib/format';

export function useElapsed(startedAt?: string | null): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!startedAt) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [startedAt]);
  return startedAt ? Math.max(0, now - new Date(startedAt).getTime()) : 0;
}

/** O relógio de prova: bateria em andamento com tempo correndo e raias. */
export function LiveHeat({ heat, highlightTeamIds = [] }: { heat: HeatView; highlightTeamIds?: number[] }) {
  const elapsed = useElapsed(heat.startedAt);
  const capMs = heat.timeCapSeconds ? heat.timeCapSeconds * 1000 : null;
  const capped = capMs !== null && elapsed >= capMs;

  return (
    <section className="live" aria-label={`Bateria ${heat.heatNumber} da prova ${heat.workoutNumber} em andamento`}>
      <div className="live__top">
        <span className="live__tag">
          <span className="pulse" aria-hidden />
          Em andamento
        </span>
        <span className="live__category">{heat.categoryName}</span>
      </div>
      <div className="live__body">
        <div>
          <p className="live__clock" role="timer" aria-live="off">
            {formatClock(capped && capMs ? capMs : elapsed)}
          </p>
          <p className="live__cap">
            {capMs ? (capped ? 'Time cap atingido' : `Time cap ${formatClock(capMs)}`) : 'Sem time cap'}
          </p>
        </div>
        <div className="live__info">
          <h3 className="live__title">
            Prova {heat.workoutNumber}: {heat.workoutName}
          </h3>
          <p className="live__subtitle">Bateria {heat.heatNumber}</p>
          <ol className="live__lanes">
            {heat.lanes.map((lane) => (
              <li key={lane.lane} className={highlightTeamIds.includes(lane.teamId) ? 'is-mine' : undefined}>
                <span className="live__lane">Raia {lane.lane}</span>
                <span>{lane.teamName}</span>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </section>
  );
}
