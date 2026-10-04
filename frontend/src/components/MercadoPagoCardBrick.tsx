import { useEffect, useRef, useState } from 'react';
import type { CardInput } from '../api/types';

/* eslint-disable @typescript-eslint/no-explicit-any */
declare global {
  interface Window {
    MercadoPago?: any;
  }
}

let sdkPromise: Promise<void> | null = null;

function loadSdk(): Promise<void> {
  if (window.MercadoPago) return Promise.resolve();
  if (!sdkPromise) {
    sdkPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://sdk.mercadopago.com/js/v2';
      script.onload = () => resolve();
      script.onerror = () => {
        sdkPromise = null;
        reject(new Error('Não foi possível carregar o formulário de cartão do Mercado Pago'));
      };
      document.head.appendChild(script);
    });
  }
  return sdkPromise;
}

/**
 * Card Payment Brick: o número do cartão é digitado num iframe do Mercado Pago
 * e tokenizado no navegador. O backend recebe apenas o token.
 */
export function MercadoPagoCardBrick({
  publicKey,
  amount,
  email,
  onToken,
}: {
  publicKey: string;
  amount: number;
  email: string;
  onToken: (card: CardInput) => Promise<unknown>;
}) {
  const containerId = useRef(`mp-card-${Math.random().toString(36).slice(2)}`).current;
  const onTokenRef = useRef(onToken);
  onTokenRef.current = onToken;
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let controller: any;
    let cancelled = false;
    loadSdk()
      .then(async () => {
        if (cancelled) return;
        const mp = new window.MercadoPago(publicKey, { locale: 'pt-BR' });
        controller = await mp.bricks().create('cardPayment', containerId, {
          initialization: { amount: Number(amount), payer: { email } },
          customization: { paymentMethods: { maxInstallments: 6 }, visual: { style: { theme: 'dark' } } },
          callbacks: {
            onReady: () => undefined,
            onSubmit: (data: any) =>
              onTokenRef.current({
                token: data.token,
                paymentMethodId: data.payment_method_id,
                issuerId: data.issuer_id ? String(data.issuer_id) : undefined,
                installments: Number(data.installments) || 1,
              }),
            onError: (e: any) => setError(e?.message ?? 'Erro no formulário de cartão'),
          },
        });
      })
      .catch((e: Error) => setError(e.message));
    return () => {
      cancelled = true;
      controller?.unmount?.();
    };
  }, [publicKey, amount, email, containerId]);

  return (
    <div>
      <div id={containerId} />
      {error && <p className="form-error" role="alert">{error}</p>}
    </div>
  );
}
