interface TabItem<T extends string> {
  id: T;
  label: string;
}

/** Abas que ficam na URL (?aba=...), para o link poder ser compartilhado. */
export function Tabs<T extends string>({
  items,
  active,
  onChange,
  label,
}: {
  items: TabItem<T>[];
  active: T;
  onChange: (id: T) => void;
  label: string;
}) {
  return (
    <div className="tabs" role="tablist" aria-label={label}>
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          role="tab"
          id={`tab-${item.id}`}
          aria-selected={active === item.id}
          aria-controls={`panel-${item.id}`}
          className="tabs__tab"
          onClick={() => onChange(item.id)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
