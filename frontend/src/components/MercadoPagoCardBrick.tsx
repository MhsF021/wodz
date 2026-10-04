import { useEffect, useRef, useState } from 'react';
import type { CardInput, CardMethod } from '../api/types';

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

/** payment_type_id do Mercado Pago para cada tipo de cartão. */
const MP_CARD_TYPE: Record<CardMethod, string> = { CREDIT_CARD: 'credit_card', DEBIT_CARD: 'debit_card' };

/**
 * Card Payment Brick: o número do cartão é digitado num iframe do Mercado Pago
 * e tokenizado no navegador. O backend recebe apenas o token. O Brick só aceita
 * cartões do tipo escolhido (crédito ou débito); o backend confere de novo.
 */
export function MercadoPagoCardBrick({
  publicKey,
  amount,
  email,
  cardType,
  onToken,
}: {
  publicKey: string;
  amount: number;
  email: string;
  cardType: CardMethod;
  onToken: (card: CardInput) => Promise<unknown>;
}) {
  const containerId = useRef(`mp-card-${Math.random().toString(36).slice(2)}`).current;
  const onTokenRef = useRef(onToken);
  onTokenRef.current = onToken;
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let controller: any;
    let cancelled = false;
    setError(null);
    const debit = cardType === 'DEBIT_CARD';
    loadSdk()
      .then(async () => {
        if (cancelled) return;
        const mp = new window.MercadoPago(publicKey, { locale: 'pt-BR' });
        controller = await mp.bricks().create('cardPayment', containerId, {
          initialization: { amount: Number(amount), payer: { email } },
          customization: {
            paymentMethods: { types: { included: [MP_CARD_TYPE[cardType]] }, maxInstallments: debit ? 1 : 6 },
            visual: { style: { theme: 'dark' } },
          },
          callbacks: {
            onReady: () => undefined,
            onSubmit: (data: any, additional: any) => {
              if (additional?.paymentTypeId && additional.paymentTypeId !== MP_CARD_TYPE[cardType]) {
                const message = debit
                  ? 'Este cartão não pode ser usado na função débito. Escolha "Crédito" ou use outro cartão.'
                  : 'Este cartão não pode ser usado na função crédito. Escolha "Débito" ou use outro cartão.';
                setError(message);
                return Promise.reject(new Error(message));
              }
              setError(null);
              const lastFour = String(additional?.lastFourDigits ?? '');
              return onTokenRef.current({
                token: data.token,
                paymentMethodId: data.payment_method_id,
                issuerId: data.issuer_id ? String(data.issuer_id) : undefined,
                installments: debit ? 1 : Number(data.installments) || 1,
                lastFourDigits: /^\d{4}$/.test(lastFour) ? lastFour : undefined,
              });
            },
            onError: (e: any) => setError(e?.message ?? 'Erro no formulário de cartão'),
          },
        });
      })
      .catch((e: Error) => setError(e.message));
    return () => {
      cancelled = true;
      controller?.unmount?.();
    };
  }, [publicKey, amount, email, cardType, containerId]);

  return (
    <div>
      <div id={containerId} />
      {error && <p className="form-error" role="alert">{error}</p>}
    </div>
  );
}
