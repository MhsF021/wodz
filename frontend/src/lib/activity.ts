let requests = 0;
const listeners = new Set<() => void>();

function notify() {
  listeners.forEach((listener) => listener());
}

export const activityStore = {
  getSnapshot: () => requests > 0,
  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  },
};

export function beginActivity() {
  requests++;
  if (requests === 1) notify();
  let finished = false;
  return () => {
    if (finished) return;
    finished = true;
    requests--;
    if (requests === 0) notify();
  };
}

/** Mostra o preloader antes de revelar a página, inclusive quando há dados em cache. */
export function navigationActivity() {
  const finish = beginActivity();
  setTimeout(finish, 550);
}

/** Filtros, abertura de pop-up e outros ajustes locais preservam a página visível. */
export function pageNavigationKey(pathname: string, search: string) {
  const tab = new URLSearchParams(search).get('aba');
  return tab ? `${pathname}?aba=${encodeURIComponent(tab)}` : pathname;
}
