import { useMemo, useState } from 'react';
import type { HeatView } from '../api/types';
import { capLabel, clockTime, dayLabel, workoutTypeLabel } from '../lib/format';
import { CategoryPicker, PlateChip } from './Plate';
import { HeatBadge } from './StatusBadge';
import { LiveHeat } from './LiveHeat';
import { Empty } from './States';

interface CategoryOption {
  id: number;
  name: string;
}

interface WorkoutGroup {
  workoutId: number;
  workoutNumber: number;
  workoutName: string;
  workoutType: HeatView['workoutType'];
  timeCapSeconds?: number | null;
  categoryId: number;
  categoryName: string;
  heats: HeatView[];
}

function groupByWorkout(heats: HeatView[]): WorkoutGroup[] {
  const groups = new Map<number, WorkoutGroup>();
  for (const heat of heats) {
    let group = groups.get(heat.workoutId);
    if (!group) {
      group = {
        workoutId: heat.workoutId,
        workoutNumber: heat.workoutNumber,
        workoutName: heat.workoutName,
        workoutType: heat.workoutType,
        timeCapSeconds: heat.timeCapSeconds,
        categoryId: heat.categoryId,
        categoryName: heat.categoryName,
        heats: [],
      };
      groups.set(heat.workoutId, group);
    }
    group.heats.push(heat);
  }
  return [...groups.values()];
}

/** Horário de cada bateria separado por prova, com status e destaque da equipe do atleta. */
export function ScheduleBoard({
  heats,
  categories,
  highlightTeamIds = [],
  showFilter = true,
}: {
  heats: HeatView[];
  categories: CategoryOption[];
  highlightTeamIds?: number[];
  showFilter?: boolean;
}) {
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const plateIndex = (id: number) => Math.max(0, categories.findIndex((c) => c.id === id));

  const filtered = useMemo(
    () => (categoryId ? heats.filter((h) => h.categoryId === categoryId) : heats),
    [heats, categoryId],
  );
  const live = filtered.filter((h) => h.status === 'IN_PROGRESS');
  const upNextId = filtered.find((h) => h.status === 'UPCOMING')?.heatId;
  const groups = useMemo(() => groupByWorkout(filtered), [filtered]);
  const multiDay = new Set(heats.map((h) => new Date(h.scheduledAt).toDateString())).size > 1;

  return (
    <div className="schedule">
      {showFilter && (
        <CategoryPicker categories={categories} value={categoryId} onChange={setCategoryId} allowAll />
      )}

      {live.map((heat) => (
        <LiveHeat key={heat.heatId} heat={heat} highlightTeamIds={highlightTeamIds} />
      ))}

      {groups.length === 0 && (
        <Empty title="As baterias ainda não foram divulgadas">
          <p>Assim que a organização montar a grade, os horários aparecem aqui.</p>
        </Empty>
      )}

      {groups.map((group) => (
        <section key={group.workoutId} className="workout-block" aria-labelledby={`wk-${group.workoutId}`}>
          <header className="workout-block__head">
            <h3 id={`wk-${group.workoutId}`} className="workout-block__title">
              Prova {group.workoutNumber}: {group.workoutName}
            </h3>
            <div className="workout-block__meta">
              <PlateChip index={plateIndex(group.categoryId)} name={group.categoryName} />
              <span>{workoutTypeLabel[group.workoutType]}</span>
              {capLabel(group.timeCapSeconds) && <span>Cap {capLabel(group.timeCapSeconds)}</span>}
            </div>
          </header>

          <ol className="heat-list">
            {group.heats.map((heat) => {
              const mine = heat.lanes.some((l) => highlightTeamIds.includes(l.teamId));
              return (
                <li
                  key={heat.heatId}
                  className={`heat-row heat-row--${heat.status.toLowerCase()}${mine ? ' heat-row--mine' : ''}`}
                >
                  <div className="heat-row__time">
                    <span className="heat-row__clock">{clockTime(heat.scheduledAt)}</span>
                    {multiDay && <span className="heat-row__day">{dayLabel(heat.scheduledAt)}</span>}
                  </div>
                  <div className="heat-row__main">
                    <div className="heat-row__title">
                      <span>Bateria {heat.heatNumber}</span>
                      <HeatBadge status={heat.status} />
                      {heat.heatId === upNextId && <span className="up-next">Chamada a seguir</span>}
                    </div>
                    <ul className="lanes">
                      {heat.lanes.map((lane) => (
                        <li key={lane.lane} className={highlightTeamIds.includes(lane.teamId) ? 'is-mine' : undefined}>
                          <span className="lanes__no">{lane.lane}</span>
                          {lane.teamName}
                        </li>
                      ))}
                    </ul>
                  </div>
                </li>
              );
            })}
          </ol>
        </section>
      ))}
    </div>
  );
}
