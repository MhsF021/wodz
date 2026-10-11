import { useTransition, type KeyboardEvent } from 'react';
import { useActivity } from '../lib/useActivity';
import { navigationActivity } from '../lib/activity';

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
  const [pending, startTransition] = useTransition();
  useActivity(pending);

  const activate = (id: T) => {
    if (id === active) return;
    navigationActivity();
    startTransition(() => onChange(id));
  };

  const focusTab = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    let next: number;
    switch (event.key) {
      case 'ArrowLeft': next = (index - 1 + items.length) % items.length; break;
      case 'ArrowRight': next = (index + 1) % items.length; break;
      case 'Home': next = 0; break;
      case 'End': next = items.length - 1; break;
      default: return;
    }
    event.preventDefault();
    const buttons = event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]');
    buttons?.[next]?.focus();
  };

  return (
    <div className="tabs" role="tablist" aria-label={label} aria-busy={pending}>
      {items.map((item, index) => (
        <button
          key={item.id}
          type="button"
          role="tab"
          id={`tab-${item.id}`}
          aria-selected={active === item.id}
          aria-controls={`panel-${item.id}`}
          tabIndex={active === item.id ? 0 : -1}
          className="tabs__tab"
          onClick={() => activate(item.id)}
          onKeyDown={(event) => focusTab(event, index)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
