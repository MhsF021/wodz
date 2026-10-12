/** Identificação textual da categoria. */
export function PlateChip({ name }: { index: number; name: string }) {
  return (
    <span className="plate">
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
