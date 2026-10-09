import { beginActivity } from '../lib/activity';

export const API_BASE: string = (import.meta.env.VITE_API_URL as string | undefined) ?? '';

const TOKEN_KEY = 'wodz.token';
export const LOGOUT_EVENT = 'wodz:logout';

export const tokenStore = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
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
}

async function sendRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
  } catch {
    throw new ApiError('Sem conexão com o servidor. Verifique sua internet e tente de novo.', 0);
  }

  if (response.status === 401 && token) {
    tokenStore.clear();
    window.dispatchEvent(new Event(LOGOUT_EVENT));
  }

  if (!response.ok) {
    let message = `Erro ${response.status}`;
    let fieldErrors: Record<string, string> = {};
    try {
      const problem = await response.json();
      message = problem.detail || problem.title || message;
      fieldErrors = problem.errors ?? {};
    } catch {
      // resposta sem corpo
    }
    throw new ApiError(message, response.status, fieldErrors);
  }

  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const finish = beginActivity();
  try {
    return await sendRequest<T>(path, options);
  } finally {
    finish();
  }
}

export const http = {
  get: <T>(path: string) => request<T>(path),
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
