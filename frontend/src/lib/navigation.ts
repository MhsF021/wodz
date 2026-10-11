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
