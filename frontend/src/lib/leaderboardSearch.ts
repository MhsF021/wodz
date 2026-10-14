import type { LeaderboardRow } from '../api/types';

const normalize = (value: string) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR');

export function searchLeaderboard(rows: LeaderboardRow[], search: string): LeaderboardRow[] {
  const terms = normalize(search).trim().split(/\s+/).filter(Boolean);
  if (!terms.length) return rows;
  return rows.filter(row => {
    const names = normalize([row.teamName, ...(row.athleteNames ?? [])].join(' '));
    return terms.every(term => names.includes(term));
  });
}
