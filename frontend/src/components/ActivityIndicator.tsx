import { useEffect, useState, useSyncExternalStore } from 'react';
import { activityStore } from '../lib/activity';

export function ActivityIndicator({ initializing = false }: { initializing?: boolean }) {
  const requesting = useSyncExternalStore(activityStore.subscribe, activityStore.getSnapshot);
  const active = requesting || initializing;
  const [visible, setVisible] = useState(initializing);
  useEffect(() => {
    if (!active) { setVisible(false); return; }
    if (initializing) { setVisible(true); return; }
    // Evita piscar em respostas rápidas, sem prolongar a requisição ou impedir o uso da página.
    const timer = window.setTimeout(() => setVisible(true), 200);
    return () => window.clearTimeout(timer);
  }, [active, initializing]);
  const showing = active && visible;
  return (
    <div className="activity-indicator" role="status" aria-live="polite" aria-atomic="true">
      <span className="sr-only">{showing ? 'Carregando…' : ''}</span>
      {showing && <span className="activity-indicator__line" aria-hidden />}
    </div>
  );
}
