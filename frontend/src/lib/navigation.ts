import type { User } from '../api/types';

export function isProfileComplete(user: User): boolean {
  return Boolean(user.document?.trim() && (user.role === 'ORGANIZER' || (user.gender && user.phone?.trim())));
}

export function profileReturnPath(user: User, next: string | null): string {
  const destination = localReturnPath(next) ?? (user.role === 'ORGANIZER' ? '/organizador' : '/minhas-equipes');
  if (isProfileComplete(user) || destination.split(/[?#]/)[0] === '/minha-conta') return destination;
  return `/minha-conta?next=${encodeURIComponent(destination)}`;
}

/** Aceita somente caminhos locais para o retorno após a autenticação. */
export function localReturnPath(value: string | null): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u0020\u007f]/.test(value)) return null;
  try {
    const base = 'https://wodz.invalid';
    const destination = new URL(value, base);
    // A remoção de segmentos '..' também pode produzir '//host'. Revalidamos o
    // caminho normalizado antes de entregá-lo novamente ao navegador/router.
    if (destination.origin !== base || destination.pathname.startsWith('//')) return null;
    return destination.pathname + destination.search + destination.hash;
  } catch {
    return null;
  }
}
