/** Só permite navegar para os endereços de Checkout Pro enviados pela API. */
export function checkoutDestination(value: string): string | null {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && (!url.port || url.port === '443')
      && url.hostname === 'www.mercadopago.com.br'
      && url.pathname.startsWith('/checkout/') ? url.href : null;
  } catch { return null; }
}
