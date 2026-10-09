let requests = 0;
const listeners = new Set<() => void>();

export const activityStore = {
  getSnapshot: () => requests > 0,
  subscribe: (listener: () => void) => {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  },
};

export function beginActivity() {
  requests++;
  listeners.forEach((listener) => listener());
  let finished = false;
  return () => {
    if (finished) return;
    finished = true;
    requests--;
    listeners.forEach((listener) => listener());
  };
}
