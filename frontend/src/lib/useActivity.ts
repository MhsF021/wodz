import { useEffect, useSyncExternalStore } from 'react';
import { useIsFetching, useIsMutating } from '@tanstack/react-query';
import { activityStore, beginActivity } from './activity';

/** Atualizações em segundo plano mantêm os dados visíveis sem acionar o preloader. */
export function useForegroundActivity() {
  const requesting = useSyncExternalStore(activityStore.subscribe, activityStore.getSnapshot, () => false);
  const fetching = useIsFetching({
    type: 'active',
    predicate: (query) => query.state.data === undefined && query.meta?.activity !== false,
  });
  const mutating = useIsMutating({
    predicate: (mutation) => !mutation.state.isPaused && mutation.meta?.activity !== false,
  });
  return requesting || fetching > 0 || mutating > 0;
}

/** Inclui trabalho de interface, como uma transição React, sem inventar tempo de carga. */
export function useActivity(active: boolean) {
  useEffect(() => {
    if (!active) return;
    return beginActivity();
  }, [active]);
}
