export type ExternalResult = {
  kind: 'payout' | 'payment';
  status: 'success' | 'pending' | 'error';
  code?: string;
};

type ResultEnvelope = ExternalResult & { id: string; at: number };
const CHANNEL = 'wodz.external-result';
const STORAGE_KEY = 'wodz.external-result';

/** Abra no clique, antes de aguardar a API, para respeitar o bloqueador de popups. */
export function openExternalWindow(url = 'about:blank', name?: string): Window | null {
  const popup = window.open('about:blank', name ?? `wodz-${crypto.randomUUID()}`,
    'popup=yes,width=640,height=780,resizable=yes,scrollbars=yes');
  if (!popup) return null;
  // O retorno usa sinais na mesma origem; a página externa não precisa acessar a janela original.
  popup.opener = null;
  if (url !== 'about:blank') popup.location.replace(url);
  return popup;
}

function validResult(value: unknown): value is ResultEnvelope {
  if (!value || typeof value !== 'object') return false;
  const result = value as Partial<ResultEnvelope>;
  return (result.kind === 'payout' || result.kind === 'payment')
    && (result.status === 'success' || result.status === 'pending' || result.status === 'error')
    && typeof result.id === 'string' && typeof result.at === 'number'
    && Math.abs(Date.now() - result.at) < 60_000
    && (result.code === undefined || typeof result.code === 'string');
}

/** Apenas um aviso de retorno. O estado final sempre deve ser consultado na API. */
export function publishExternalResult(result: ExternalResult) {
  const envelope: ResultEnvelope = { ...result, id: crypto.randomUUID(), at: Date.now() };
  if (typeof BroadcastChannel !== 'undefined') {
    const channel = new BroadcastChannel(CHANNEL);
    channel.postMessage(envelope);
    channel.close();
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(envelope));
  } catch {
    // BroadcastChannel continua disponível quando o armazenamento está bloqueado.
  }
}

export function subscribeExternalResult(kind: ExternalResult['kind'], listener: (result: ExternalResult) => void) {
  const seen = new Set<string>();
  const receive = (value: unknown) => {
    if (!validResult(value) || value.kind !== kind || seen.has(value.id)) return;
    if (seen.size >= 100) seen.clear();
    seen.add(value.id);
    listener({ kind: value.kind, status: value.status, code: value.code });
  };
  const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(CHANNEL) : null;
  if (channel) channel.onmessage = (event: MessageEvent<unknown>) => receive(event.data);
  const onStorage = (event: StorageEvent) => {
    if (event.key !== STORAGE_KEY || !event.newValue) return;
    try { receive(JSON.parse(event.newValue)); } catch { /* ignore entradas inválidas */ }
  };
  window.addEventListener('storage', onStorage);
  return () => {
    channel?.close();
    window.removeEventListener('storage', onStorage);
  };
}
