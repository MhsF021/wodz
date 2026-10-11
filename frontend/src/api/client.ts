import { beginActivity } from '../lib/activity';

export const API_BASE: string = (import.meta.env.VITE_API_URL as string | undefined) ?? '';

const TOKEN_KEY = 'wodz.token';
export const LOGOUT_EVENT = 'wodz:logout';

// A sessão dura somente a aba. Não conservamos credenciais no armazenamento
// persistente; instalações anteriores precisam entrar novamente uma única vez.
let sessionToken: string | null = null;
try {
  localStorage.removeItem(TOKEN_KEY);
} catch {
  // Armazenamento persistente pode estar bloqueado pelo navegador.
}
try {
  sessionToken = sessionStorage.getItem(TOKEN_KEY);
} catch {
  // Navegação com armazenamento bloqueado continua com uma sessão em memória.
}
let sessionVersion = 0;
let sessionController = new AbortController();

function replaceToken(token: string | null) {
  sessionController.abort();
  sessionController = new AbortController();
  sessionVersion += 1;
  sessionToken = token;
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // O token nunca migra para localStorage se sessionStorage estiver indisponível.
  }
}

export const tokenStore = {
  get: (): string | null => sessionToken,
  set: (token: string) => replaceToken(token),
  clear: () => replaceToken(null),
};

export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: Record<string, string>;

  constructor(message: string, status: number, fieldErrors: Record<string, string> = {}) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  activity?: boolean;
}

async function sendRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const endpoint = new URL(`${API_BASE}${path}`, window.location.origin);
  // O túnel gratuito pode retornar seu aviso com HTTP 200 e texto, mesmo em /api.
  // Não depende do cookie de visita, pois chamadas da API omitem credenciais.
  if (/\.(?:ngrok-free\.(?:app|dev)|ngrok\.(?:app|dev|io))$/i.test(endpoint.hostname)) {
    headers['ngrok-skip-browser-warning'] = '1';
  }
  const token = tokenStore.get();
  const version = sessionVersion;
  const signal = sessionController.signal;
  const ensureCurrentSession = () => {
    if (version !== sessionVersion) {
      throw new ApiError('A sessão mudou. Entre novamente ou recarregue a página.', 409);
    }
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      cache: 'no-store',
      credentials: 'omit',
      signal,
    });
  } catch {
    ensureCurrentSession();
    throw new ApiError('Sem conexão com o servidor. Verifique sua internet e tente de novo.', 0);
  }

  // Uma resposta atrasada da conta anterior não pode encerrar a nova sessão,
  // nem voltar para o cache depois de sair ou trocar de usuário.
  ensureCurrentSession();
  if (response.status === 401 && token) {
    tokenStore.clear();
    window.dispatchEvent(new Event(LOGOUT_EVENT));
  }

  if (!response.ok) {
    let message = `Erro ${response.status}`;
    let fieldErrors: Record<string, string> = {};
    try {
      const problem = await response.json();
      ensureCurrentSession();
      message = problem.detail || problem.title || message;
      fieldErrors = problem.errors ?? {};
    } catch {
      // resposta sem corpo
    }
    // A própria resposta 401 encerrou a sessão acima; mantém seu erro original.
    if (response.status !== 401 || !token) ensureCurrentSession();
    throw new ApiError(message, response.status, fieldErrors);
  }

  if (response.status === 204) return undefined as T;
  let text: string;
  try {
    text = await response.text();
  } catch {
    ensureCurrentSession();
    throw new ApiError('A conexão foi interrompida. Tente novamente.', response.status);
  }
  ensureCurrentSession();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    // Nunca exibe HTML, mensagens de proxy ou detalhes de JSON.parse ao usuário.
    throw new ApiError('Não foi possível carregar as informações. Tente novamente em instantes.', response.status);
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const finish = options.activity === false ? () => {} : beginActivity();
  try {
    return await sendRequest<T>(path, options);
  } finally {
    finish();
  }
}

export const http = {
  // Consultas e polling são acompanhados pelo React Query. GET manual pode
  // solicitar feedback explicitamente com { activity: true }.
  get: <T>(path: string, options: Pick<RequestOptions, 'activity'> = {}) =>
    request<T>(path, { activity: false, ...options }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  del: <T = void>(path: string, body?: unknown) => request<T>(path, { method: 'DELETE', body }),
};

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const fields = Object.values(error.fieldErrors);
    return fields.length ? `${error.message}: ${fields.join('; ')}` : error.message;
  }
  return error instanceof Error ? error.message : 'Algo deu errado. Tente de novo.';
}
