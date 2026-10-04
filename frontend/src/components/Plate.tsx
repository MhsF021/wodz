import { plateColor } from '../lib/format';

/** Disco na cor de uma anilha olímpica: identifica a categoria em toda a interface. */
export function PlateChip({ index, name }: { index: number; name: string }) {
  return (
    <span className="plate">
      <span className="plate__disc" style={{ backgroundColor: plateColor(index) }} aria-hidden />
      {name}
    </span>
  );
}

interface CategoryOption {
  id: number;
  name: string;
}

export function CategoryPicker({
  categories,
  value,
  onChange,
  allowAll = false,
  label = 'Categoria',
}: {
  categories: CategoryOption[];
  value: number | null;
  onChange: (id: number | null) => void;
  allowAll?: boolean;
  label?: string;
}) {
  if (categories.length === 0) return null;
  return (
    <div className="picker" role="group" aria-label={label}>
      {allowAll && (
        <button type="button" className="picker__option" aria-pressed={value === null} onClick={() => onChange(null)}>
          Todas
        </button>
      )}
      {categories.map((c, i) => (
        <button
          key={c.id}
          type="button"
          className="picker__option"
          aria-pressed={value === c.id}
          onClick={() => onChange(c.id)}
        >
          <PlateChip index={i} name={c.name} />
        </button>
      ))}
    </div>
  );
}
