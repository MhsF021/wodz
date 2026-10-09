import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { adminApi } from '../api/endpoints';
import { tokenStore } from '../api/client';
import { ActionFeedback } from '../components/ActionFeedback';
import { publishExternalResult } from '../lib/externalWindow';

type Feedback = { status: 'success' | 'pending' | 'error'; label: string };

/** O callback do servidor remove código/state OAuth antes de chegar a esta página. */
export default function MercadoPagoReturnPage() {
  const [params] = useSearchParams();
  const result = params.get('recebimentos');
  const [feedback, setFeedback] = useState<Feedback>({ status: 'pending', label: 'Confirmando autorização…' });

  useEffect(() => {
    let active = true;
    let closeTimer: number | undefined;
    async function complete() {
      let next: Feedback;
      if (result !== 'connected') {
        next = { status: 'error', label: 'A conexão não foi concluída. Você pode tentar novamente na Minha Conta.' };
      } else if (!tokenStore.get()) {
        // localhost e o túnel podem ter sessões de origens diferentes.
        next = { status: 'pending', label: 'Autorização recebida. Confira a confirmação na janela original.' };
      } else {
        try {
          const account = await adminApi.payoutAccount();
          next = account.connected && account.liveMode !== account.testMode
            ? { status: 'success', label: 'Conta autorizada e validada pelo Mercado Pago.' }
            : { status: 'pending', label: 'Autorização recebida. Confira os recebimentos na Minha Conta.' };
        } catch {
          next = { status: 'pending', label: 'Autorização recebida. Volte à Minha Conta para verificar.' };
        }
      }
      if (!active) return;
      setFeedback(next);
      // Somente status genérico circula entre janelas; tokens e código OAuth ficam no servidor.
      publishExternalResult({ kind: 'payout', status: result === 'connected' ? 'success' : 'error' });
      closeTimer = window.setTimeout(() => window.close(), 900);
    }
    void complete();
    return () => {
      active = false;
      if (closeTimer !== undefined) window.clearTimeout(closeTimer);
    };
  }, [result]);

  return (
    <div className="page page--narrow">
      <h1 className="page-title">Mercado Pago</h1>
      <ActionFeedback status={feedback.status} label={feedback.label} />
      <p className="note">Você pode fechar esta janela. O resultado aparece na página em que iniciou a conexão.</p>
      <Link className="btn btn--ghost" to="/minha-conta#recebimentos">Voltar à Minha Conta</Link>
    </div>
  );
}
