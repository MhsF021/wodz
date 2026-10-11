import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { Wallet } from 'lucide-react';
import { adminApi } from '../api/endpoints';
import { payoutFailureMessage } from '../lib/payoutReturn';
import { ActionFeedback } from './ActionFeedback';
import { InlineError, Loading } from './States';

type Feedback = { status: 'success' | 'pending' | 'error'; label: string };
const ATTEMPT = 'wodz.payout-attempt';

export function PayoutAccountSection() {
  const [params, setParams] = useSearchParams();
  const queryClient = useQueryClient();
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const account = useQuery({ queryKey: ['payout-account'], queryFn: adminApi.payoutAccount, staleTime: 0 });
  const data = account.data;
  const result = params.get('recebimentos');
  const reason = params.get('motivo');
  const needsTestAuthorization = data?.testMode && data.connected && data.liveMode;
  const connect = useMutation({
    mutationFn: adminApi.connectPayoutAccount,
    onSuccess: ({ authorizationUrl }) => {
      const url = new URL(authorizationUrl);
      if (url.protocol !== 'https:') throw new Error('Endereço de autorização inválido.');
      try { sessionStorage.setItem(ATTEMPT, JSON.stringify({ version: data?.updatedAt ?? data?.connectedAt ?? null, at: Date.now() })); } catch { /* o retorno ainda confirma pela API */ }
      window.location.assign(url.href);
    },
  });
  const disconnect = useMutation({
    mutationFn: adminApi.disconnectPayoutAccount,
    onSuccess: () => {
      try { sessionStorage.removeItem(ATTEMPT); } catch { /* storage indisponível */ }
      void queryClient.invalidateQueries({ queryKey: ['payout-account'] });
      setFeedback({ status: 'success', label: 'Conta desconectada.' });
      const next = new URLSearchParams(params);
      next.delete('recebimentos');
      next.delete('motivo');
      setParams(next, { replace: true });
    },
  });
  const busy = connect.isPending || disconnect.isPending;

  useEffect(() => {
    const resume = (event: PageTransitionEvent) => {
      if (!event.persisted) return;
      connect.reset();
      void account.refetch();
    };
    window.addEventListener('pageshow', resume);
    return () => window.removeEventListener('pageshow', resume);
  }, [connect.reset, account.refetch]);

  useEffect(() => {
    if (!data || account.isFetching || account.isError) return;
    let saved: string | null = null;
    try { saved = sessionStorage.getItem(ATTEMPT); } catch { /* confirmação vem da API */ }
    if (!saved && !result) return;
    let previous: { version: string | null; at: number } | null = null;
    try { previous = saved ? JSON.parse(saved) : null; } catch { /* confirmação vem da API */ }
    try { sessionStorage.removeItem(ATTEMPT); } catch { /* storage indisponível */ }
    const verified = data.connected && data.liveMode !== data.testMode;
    const version = data.updatedAt ?? data.connectedAt ?? null;
    const renewed = verified && previous && Date.now() - previous.at < 600_000 && version !== previous.version;
    setFeedback(result === 'error'
      ? { status: 'error', label: payoutFailureMessage(reason) }
      : verified && (renewed || result === 'connected' && !previous)
        ? { status: 'success', label: 'Conta autorizada e validada pelo Mercado Pago.' }
        : { status: 'pending', label: 'A autorização ainda não foi confirmada. Confira sua conexão ou tente novamente.' });
    if (result) {
      const next = new URLSearchParams(params);
      next.delete('recebimentos');
      next.delete('motivo');
      setParams(next, { replace: true });
    }
  }, [data, account.isFetching, account.isError, result, reason, params, setParams]);

  function startConnect() {
    setFeedback(null);
    const next = new URLSearchParams(params);
    next.delete('recebimentos');
    next.delete('motivo');
    setParams(next, { replace: true });
    connect.mutate();
  }

  return (
    <section id="recebimentos" className="panel" aria-labelledby="account-payout">
      <h2 id="account-payout" className="panel__title"><Wallet size={20} aria-hidden /> Recebimentos — Mercado Pago</h2>
      <p className="panel__lead">Conecte a conta em que deseja receber as inscrições dos seus campeonatos.</p>
      {data?.testMode && (
        <p className="note" role="status"><strong>Modo de teste do Mercado Pago.</strong> Conecte uma conta
          de vendedor de teste criada no Mercado Pago Developers. Use outra conta de teste como comprador.</p>
      )}
      {account.isLoading && <Loading />}
      <InlineError error={account.error || connect.error || disconnect.error} />
      {feedback && <ActionFeedback status={feedback.status} label={feedback.label} />}
      {!feedback && result && account.isFetching && (
        <ActionFeedback status="pending" label="Conferindo a autorização com o Mercado Pago…" />
      )}
      {needsTestAuthorization && (
        <p className="form-error" role="alert">Esta conta tem uma autorização de produção. Desconecte-a
          e conecte um vendedor de teste do Mercado Pago para testar o checkout.</p>
      )}
      {!feedback && result === 'error' && !account.isFetching && (
        <ActionFeedback status="error" label={payoutFailureMessage(reason)} />
      )}
      {data?.connected && (
        <div className="stack">
          <p><strong>{data.accountLabel}</strong><br />
            <span className="note">Conta {data.providerUserId} · {data.liveMode ? 'Produção' : 'Teste'}</span></p>
          {data.testMode ? (
            <p className="note">No checkout do Mercado Pago, use uma conta de comprador de teste diferente da conta de vendedor conectada.</p>
          ) : (
            <p className="note">A autorização confirma a conta conectada. A aprovação de cada cobrança e a
              disponibilidade dos recebimentos seguem as regras do Mercado Pago. Mantenha a identificação
              e os documentos da sua conta de vendedor completos.</p>
          )}
        </div>
      )}
      {data?.paymentUnavailable && <p className="note">Os pagamentos estão desativados neste ambiente. Conecte o Mercado Pago após a configuração da integração.</p>}
      {data && !data.paymentUnavailable && !data.configured && <p className="note">A integração ainda precisa ser configurada
        pela administração do WODZ. Depois disso você poderá conectar sua conta aqui.</p>}
      {data?.configured && (
        <div className="row-actions">
          <button className="btn btn--primary" type="button" disabled={busy} onClick={startConnect}>
            {connect.isPending ? 'Conectando…' : data.connected ? 'Renovar autorização' : 'Conectar Mercado Pago'}
          </button>
          {data.connected && <button className="btn btn--ghost" type="button" disabled={busy}
            onClick={() => window.confirm('Desconectar esta conta? Novas inscrições não poderão ser pagas até conectar uma conta novamente. Cobranças já criadas continuam sendo acompanhadas.') && disconnect.mutate()}>
            {disconnect.isPending ? 'Desconectando…' : 'Desconectar conta'}
          </button>}
        </div>
      )}
      {data?.configured && <p className="note">Você será levado ao Mercado Pago nesta mesma aba. Ao retornar, o WODZ verificará a autorização.</p>}
    </section>
  );
}
