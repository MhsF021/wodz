import { Fragment, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChevronDown, Search, X } from 'lucide-react';
import { publicApi } from '../api/endpoints';
import type { Leaderboard, LeaderboardRow, ResultCell, WorkoutRef } from '../api/types';
import { ordinal, points, workoutTypeLabel } from '../lib/format';
import { searchLeaderboard } from '../lib/leaderboardSearch';
import { CategoryPicker } from './Plate';
import { Empty, ErrorState, Loading } from './States';

function Cell({ cell }: { cell?: ResultCell }) {
  if (!cell) return <span className="lb__none">–</span>;
  return (
    <span className="lb__cell">
      <span className="lb__result">{cell.display}</span>
      <span className="lb__place">
        {ordinal(cell.placement)} lugar, {points(cell.points)} pts
      </span>
    </span>
  );
}

export function LeaderboardView({ slug, categories }: { slug: string; categories: { id: number; name: string }[] }) {
  const [categoryId, setCategoryId] = useState<number | null>(categories[0]?.id ?? null);
  const [open, setOpen] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const query = useQuery({
    queryKey: ['leaderboard', slug, categoryId],
    queryFn: () => publicApi.leaderboard(slug, categoryId ?? undefined),
  });

  return (
    <div className="leaderboard">
      <CategoryPicker categories={categories} value={categoryId} onChange={(id) => setCategoryId(id)} />
      <div className="lb-search" role="search">
        <label className="sr-only" htmlFor="leaderboard-search">Buscar atleta ou equipe</label>
        <Search size={18} aria-hidden />
        <input id="leaderboard-search" className="input" type="search" placeholder="Buscar atleta ou equipe"
          value={search} onChange={(event) => { setSearch(event.target.value); setOpen(null); }} />
        {search && <button type="button" className="lb-search__clear" aria-label="Limpar busca"
          onClick={() => setSearch('')}><X size={18} aria-hidden /></button>}
      </div>
      {query.isLoading && <Loading />}
      {query.error && <ErrorState error={query.error} />}
      {query.data && <Board board={query.data} search={search} open={open} setOpen={setOpen} />}
    </div>
  );
}

function Board({
  board,
  search,
  open,
  setOpen,
}: {
  board: Leaderboard;
  search: string;
  open: number | null;
  setOpen: (id: number | null) => void;
}) {
  const rows = searchLeaderboard(board.rows, search);
  if (board.rows.length === 0) {
    return (
      <Empty title="Nenhuma equipe confirmada nesta categoria ainda">
        <p>O leaderboard aparece quando houver equipes com inscrição paga.</p>
      </Empty>
    );
  }
  if (rows.length === 0) return <Empty title="Nenhum atleta ou equipe encontrado">
    <p>Tente outro nome ou limpe a busca para ver toda a categoria.</p>
  </Empty>;
  const totalLabel = board.scoringSystem === 'RANK_SUM' ? 'Soma' : 'Pontos';
  return (
    <>
      {search.trim() && <p className="note" role="status">{rows.length} {rows.length === 1 ? 'inscrição encontrada' : 'inscrições encontradas'}</p>}
      <p className="lb__rule">
        {board.scoringSystem === 'RANK_SUM'
          ? 'Soma das colocações: vence a menor soma.'
          : 'Pontos por colocação: vence quem somar mais pontos.'}
      </p>
      <div className="lb__scroll">
        <table className="lb">
          <thead>
            <tr>
              <th scope="col" className="lb__rank-col">Pos.</th>
              <th scope="col">Equipe</th>
              <th scope="col" className="lb__num">{totalLabel}</th>
              {board.workouts.map((w) => (
                <th key={w.id} scope="col" className="lb__wcol" title={workoutTypeLabel[w.type]}>
                  Prova {w.number}
                  <span className="lb__wname">{w.name}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <Row
                key={row.teamId}
                row={row}
                workouts={board.workouts}
                expanded={open === row.teamId}
                onToggle={() => setOpen(open === row.teamId ? null : row.teamId)}
              />
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function Row({
  row,
  workouts,
  expanded,
  onToggle,
}: {
  row: LeaderboardRow;
  workouts: WorkoutRef[];
  expanded: boolean;
  onToggle: () => void;
}) {
  return (
    <Fragment>
      <tr className={row.rank <= 3 ? 'lb__row lb__row--podium' : 'lb__row'}>
        <td className="lb__rank">{row.rank}</td>
        <td>
          <button type="button" className="lb__team" aria-expanded={expanded} onClick={onToggle}>
            <span className="lb__team-name">{row.teamName}</span>
            {row.boxName && <span className="lb__box">{row.boxName}</span>}
            <ChevronDown className="lb__chevron" size={16} aria-hidden />
          </button>
        </td>
        <td className="lb__num lb__total">{points(row.totalPoints)}</td>
        {workouts.map((w) => (
          <td key={w.id} className="lb__wcol">
            <Cell cell={row.results[String(w.id)]} />
          </td>
        ))}
      </tr>
      {expanded && (
        <tr className="lb__details">
          <td colSpan={3 + workouts.length}>
            <dl>
              {workouts.map((w) => (
                <div key={w.id}>
                  <dt>
                    Prova {w.number}: {w.name}
                  </dt>
                  <dd>
                    <Cell cell={row.results[String(w.id)]} />
                  </dd>
                </div>
              ))}
            </dl>
          </td>
        </tr>
      )}
    </Fragment>
  );
}
