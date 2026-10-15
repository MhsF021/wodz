import type { Category } from '../api/types';
import { normalizeMovementStandard } from '../lib/movementStandards';

function Availability({ allowed }: { allowed?: boolean }) {
  return <span className={`movement-standard movement-standard--${allowed ? 'yes' : 'no'}`}>{allowed ? 'Poderá ser pedido' : 'Não será pedido'}</span>;
}

export function MovementStandardsTable({ category }: { category: Category }) {
  const rows = (category.movementStandards ?? []).map(row => normalizeMovementStandard(row, category.genderPolicy));
  if (!rows.length) return <p className="note">As cargas e os movimentos serão divulgados pela organização.</p>;
  const loads = rows.filter(row => row.kind === 'LOAD');
  const movements = rows.filter(row => row.kind === 'MOVEMENT');
  const columns: { key: 'male' | 'female' | null; label: string }[] = category.genderPolicy === 'MIXED'
    ? [{ key: 'male', label: 'Carga masculina' }, { key: 'female', label: 'Carga feminina' }]
    : [{ key: category.genderPolicy === 'MALE' ? 'male' : category.genderPolicy === 'FEMALE' ? 'female' : null, label: 'Carga' }];
  return <div className="movement-standards">
    {loads.length > 0 && <div>
      <h4 className="movement-standards__title">Com carga</h4>
      <div className="movement-table-wrap">
    <table className="movement-table">
      <caption className="sr-only">Cargas · {category.name}</caption>
      <thead><tr><th scope="col">Movimento</th>{columns.map(column => <th key={column.key ?? 'load'} scope="col">{column.label}</th>)}</tr></thead>
      <tbody>{loads.map((row, index) => <tr key={index}><th scope="row">{row.movement}</th>
        {row.allowed === false ? <td colSpan={columns.length}><Availability allowed={false} /></td> : columns.map(column => {
          const value = column.key ? row[column.key] : row.male === row.female ? row.male : '—';
          return <td key={column.key ?? 'load'}>{value && value !== '—' ? value : <span className="movement-standard">Não informada</span>}</td>;
        })}</tr>)}</tbody>
    </table>
      </div>
    </div>}
    {movements.length > 0 && <div>
      <h4 className="movement-standards__title">Somente movimento</h4>
      <div className="movement-table-wrap"><table className="movement-table">
        <caption className="sr-only">Movimentos · {category.name}</caption>
        <thead><tr><th scope="col">Movimento</th><th scope="col">Situação</th></tr></thead>
        <tbody>{movements.map((row, index) => <tr key={index}><th scope="row">{row.movement}</th><td><Availability allowed={row.allowed} /></td></tr>)}</tbody>
      </table></div>
    </div>}
  </div>;
}
