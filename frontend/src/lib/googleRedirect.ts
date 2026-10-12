import type { Role } from '../api/types';
import { localReturnPath } from './navigation';

export type GoogleRedirectFlow =
  | { kind: 'login'; role?: Role; next?: string | null }
  | { kind: 'unlink'; userId: number; accountClosed: boolean };
type SavedFlow = GoogleRedirectFlow & { id: string; at: number };
export type GoogleRedirectResult = { flow: SavedFlow; credential: string };
const FLOW_KEY = 'wodz.google-flow';
const RESULT_KEY = 'wodz.google-result';
let consumed: GoogleRedirectResult | null | undefined;

export function prepareGoogleRedirect(flow: GoogleRedirectFlow): string {
  const id = crypto.randomUUID();
  refreshGoogleRedirect(id, flow);
  return id;
}

export function refreshGoogleRedirect(id: string, flow: GoogleRedirectFlow) {
  sessionStorage.removeItem(RESULT_KEY);
  sessionStorage.setItem(FLOW_KEY, JSON.stringify({ ...flow, next: flow.kind === 'login' ? localReturnPath(flow.next ?? null) : undefined, id, at: Date.now() }));
}

/** Consome a resposta uma vez. StrictMode pode ler novamente a mesma resposta em memória. */
export function consumeGoogleRedirect(): GoogleRedirectResult | null {
  if (consumed !== undefined) return consumed;
  consumed = null;
  try {
    const rawFlow = sessionStorage.getItem(FLOW_KEY);
    const rawResult = sessionStorage.getItem(RESULT_KEY);
    sessionStorage.removeItem(FLOW_KEY);
    sessionStorage.removeItem(RESULT_KEY);
    if (!rawFlow || !rawResult) return null;
    const flow = JSON.parse(rawFlow) as SavedFlow;
    const result = JSON.parse(rawResult) as { state?: unknown; credential?: unknown };
    if (typeof flow.id !== 'string' || result.state !== flow.id || typeof flow.at !== 'number'
      || Date.now() - flow.at < 0 || Date.now() - flow.at > 900_000
      || typeof result.credential !== 'string' || result.credential.length > 8192
      || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(result.credential)) return null;
    if (flow.kind === 'login') {
      if (flow.role !== undefined && flow.role !== 'ATHLETE' && flow.role !== 'ORGANIZER') return null;
      flow.next = localReturnPath(flow.next ?? null);
    } else if (flow.kind !== 'unlink' || !Number.isSafeInteger(flow.userId) || flow.userId < 1 || typeof flow.accountClosed !== 'boolean') {
      return null;
    }
    consumed = { flow, credential: result.credential };
  } catch { /* ausência de storage ou resposta inválida exige uma nova confirmação */ }
  return consumed;
}

export function clearGoogleRedirect() {
  consumed = null;
  try { sessionStorage.removeItem(FLOW_KEY); sessionStorage.removeItem(RESULT_KEY); } catch { /* storage bloqueado */ }
}
