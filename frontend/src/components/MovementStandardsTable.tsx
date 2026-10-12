import { Check, Minus, X } from 'lucide-react';
import type { Category } from '../api/types';

function StandardValue({ value }: { value: string }) {
  if (value === '✓') return <span className="movement-standard movement-standard--yes"><Check size={16} aria-hidden /><span className="sr-only">Previsto</span></span>;
  if (value === '✕') return <span className="movement-standard movement-standard--no"><X size={16} aria-hidden /><span className="sr-only">Não previsto</span></span>;
  if (value === '—') return <span className="movement-standard"><Minus size={16} aria-hidden /><span className="sr-only">Não informado</span></span>;
  return <span>{value}</span>;
}

export function MovementStandardsTable({ category }: { category: Category }) {
  const rows = category.movementStandards ?? [];
  if (!rows.length) return <p className="note">As cargas e os movimentos serão divulgados pela organização.</p>;
  const male = category.genderPolicy !== 'FEMALE';
  const female = category.genderPolicy !== 'MALE';
  return <div className="movement-table-wrap">
    <table className="movement-table">
      <caption className="sr-only">Cargas e movimentos · {category.name}</caption>
      <thead><tr><th scope="col">Movimento</th>{male && <th scope="col">Masculino</th>}{female && <th scope="col">Feminino</th>}</tr></thead>
      <tbody>{rows.map((row, index) => <tr key={index}><th scope="row">{row.movement}</th>
        {male && <td><StandardValue value={row.male} /></td>}{female && <td><StandardValue value={row.female} /></td>}</tr>)}</tbody>
    </table>
  </div>;
}
