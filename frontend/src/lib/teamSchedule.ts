import type { HeatView, Team } from '../api/types';

export function teamSchedule(heats: HeatView[], team: Team) {
  return heats.filter((heat) => heat.championshipId === team.championship.id && heat.categoryId === team.category.id
    && heat.lanes.some((lane) => lane.teamId === team.id))
    .sort((a, b) => Date.parse(a.scheduledAt) - Date.parse(b.scheduledAt) || a.workoutNumber - b.workoutNumber || a.heatNumber - b.heatNumber);
}

export function existingRegistration(teams: Team[], championshipId: number) {
  return teams.find((team) => team.championship.id === championshipId && team.status !== 'CANCELLED');
}
