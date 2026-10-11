export const CHAMPIONSHIP_FILTERS = [
  { id: 'ALL', label: 'Todos' },
  { id: 'IN_PROGRESS', label: 'Em andamento' },
  { id: 'OPEN', label: 'Inscrições abertas' },
  { id: 'FINISHED', label: 'Encerrados' },
] as const;

export type ChampionshipFilter = typeof CHAMPIONSHIP_FILTERS[number]['id'];

export function championshipFilter(value: string | null): ChampionshipFilter {
  return CHAMPIONSHIP_FILTERS.find((filter) => filter.id === value)?.id ?? 'ALL';
}
