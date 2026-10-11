import { revokeGoogleGrant } from './googleIdentity';

export interface GoogleDisconnectNotice {
  accountClosed: boolean;
  permission: 'pending' | 'revoked' | 'unconfirmed';
}

let notice: GoogleDisconnectNotice | null = null;
let attempt = 0;
const listeners = new Set<() => void>();

export const getGoogleDisconnectNotice = () => notice;
export function subscribeGoogleDisconnect(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

function publish(next: GoogleDisconnectNotice) {
  notice = next;
  listeners.forEach((listener) => listener());
}

/** A remoção local é definitiva mesmo se a revogação externa não responder. */
export async function disconnectGoogleAccount({
  credential, clientId, accountClosed, disconnect, endSession,
}: {
  credential: string;
  clientId: string;
  accountClosed: boolean;
  disconnect: () => Promise<void>;
  endSession: () => void;
}): Promise<void> {
  await disconnect();
  const currentAttempt = ++attempt;
  publish({ accountClosed, permission: 'pending' });
  const permission = revokeGoogleGrant(clientId, credential).catch(() => false);
  // O JWT já foi revogado pelo servidor. Limpa sessão e caches imediatamente.
  endSession();
  const revoked = await permission;
  if (currentAttempt === attempt) publish({ accountClosed, permission: revoked ? 'revoked' : 'unconfirmed' });
}
