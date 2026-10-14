import type { TeamOverview } from '../api/types';

const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR');

export function searchTeams(teams: TeamOverview[], search: string): TeamOverview[] {
  const terms = normalize(search).trim().split(/\s+/).filter(Boolean);
  if (!terms.length) return teams;
  return teams.filter(team => {
    const names = normalize(`${team.teamName} ${team.captainName}`);
    return terms.every(term => names.includes(term));
  });
}
