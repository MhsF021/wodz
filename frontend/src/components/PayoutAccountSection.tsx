import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { ExternalLink, Wallet } from 'lucide-react';
import { adminApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import { openExternalWindow, subscribeExternalResult } from '../lib/externalWindow';
import { ActionFeedback } from './ActionFeedback';
import { InlineError, Loading } from './States';

type Feedback = { status: 'success' | 'pending' | 'error'; label: string };
type AuthorizationAttempt = {
  popup: Window;
  previousVersion: string | null;
  wasConnected: boolean;
  expiresAt: number;
  checking: boolean;
  closedHandled: boolean;
  pendingOutcome?: 'success' | 'error' | 'closed' | 'expired';
};

export function PayoutAccountSection() {
  const [params, setParams] = useSearchParams();
  const queryClient = useQueryClient();
  const attemptRef = useRef<AuthorizationAttempt | null>(null);
  const [waiting, setWaiting] = useState(false);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const account = useQuery({ queryKey: ['payout-account'], queryFn: adminApi.payoutAccount, staleTime: 0 });
  const connect = useMutation({
    mutationFn: adminApi.connectPayoutAccount,
  });
  const disconnect = useMutation({
    mutationFn: adminApi.disconnectPayoutAccount,
    onSuccess: () => {
      attemptRef.current = null;
      setWaiting(false);
      queryClient.invalidateQueries({ queryKey: ['payout-account'] });
      setFeedback({ status: 'success', label: 'Conta desconectada.' });
      const next = new URLSearchParams(params);
      next.delete('recebimentos');
      setParams(next, { replace: true });
    },
  });
  const result = params.get('recebimentos');
  const data = account.data;
  const busy = connect.isPending || disconnect.isPending || waiting;
  const needsTestAuthorization = data?.testMode && data.connected && data.liveMode;

  const checkResult = useCallback(async (outcome?: 'success' | 'error' | 'closed' | 'expired') => {
    const attempt = attemptRef.current;
    if (!attempt) return;
    if (attempt.checking) {
      if (outcome && (outcome === 'success' || outcome === 'error' || !attempt.pendingOutcome)) {
        attempt.pendingOutcome = outcome;
      }
      return;
    }
    attempt.checking = true;
    const response = await account.refetch();
    if (attemptRef.current !== attempt) return;
    attempt.checking = false;
    outcome = attempt.pendingOutcome ?? outcome;
    attempt.pendingOutcome = undefined;
    const current = response.data;
    const version = current?.updatedAt ?? current?.connectedAt ?? null;
    const valid = !response.isError && current?.connected && current.liveMode !== current.testMode;
    const renewed = valid && (!attempt.wasConnected || (version !== null && version !== attempt.previousVersion));

    // O sinal entre janelas só solicita a consulta; o estado confirmado vem da API.
    if (outcome !== 'error' && renewed) {
      attemptRef.current = null;
      setWaiting(false);
      setFeedback({ status: 'success', label: 'Conta autorizada e validada pelo Mercado Pago.' });
      return;
    }
    if (!outcome) return;
    setWaiting(false);
    if (outcome === 'closed') {
      // Alguns provedores isolam a janela com COOP. Um retorno posterior ainda pode
      // confirmar a conta pelo canal do mesmo domínio, mesmo sem o handle da janela.
      attempt.closedHandled = true;
    } else {
      attemptRef.current = null;
    }
    setFeedback({
      status: outcome === 'closed' && !response.isError ? 'pending' : 'error',
      label: response.isError
        ? 'Não foi possível confirmar a conta. Confira a conexão e tente novamente.'
        : outcome === 'closed'
          ? 'A autorização ainda não foi confirmada. Confira a janela do Mercado Pago ou tente novamente.'
          : outcome === 'expired'
            ? 'A autorização expirou sem confirmação. Conecte novamente.'
            : 'A conexão não foi concluída. A autorização pode ter sido cancelada ou expirado. Tente novamente.',
    });
  }, [account.refetch]);

  useEffect(() => {
    const unsubscribe = subscribeExternalResult('payout', ({ status }) => {
      if (status !== 'pending') void checkResult(status);
    });
    const onFocus = () => { if (attemptRef.current) void checkResult(); };
    const timer = window.setInterval(() => {
      const attempt = attemptRef.current;
      if (!attempt) return;
      if (Date.now() >= attempt.expiresAt) void checkResult('expired');
      else if (attempt.popup.closed && !attempt.closedHandled) void checkResult('closed');
    }, 1000);
    // O callback pode chegar pelo túnel em outra origem ou perder o handle por COOP.
    // A consulta periódica confirma a conta mesmo sem sinal entre janelas ou foco.
    const confirmationTimer = window.setInterval(() => {
      const attempt = attemptRef.current;
      if (attempt && Date.now() < attempt.expiresAt) void checkResult();
    }, 5000);
    window.addEventListener('focus', onFocus);
    return () => {
      unsubscribe();
      window.clearInterval(timer);
      window.clearInterval(confirmationTimer);
      window.removeEventListener('focus', onFocus);
    };
  }, [checkResult]);

  function startConnect() {
    connect.reset();
    const next = new URLSearchParams(params);
    next.delete('recebimentos');
    setParams(next, { replace: true });
    // Abrir no clique evita que o navegador bloqueie a janela após a chamada à API.
    const popup = openExternalWindow();
    if (!popup) {
      setFeedback({ status: 'error', label: 'Permita novas janelas para este site e clique em Conectar Mercado Pago novamente.' });
      return;
    }
    const attempt: AuthorizationAttempt = {
      popup,
      previousVersion: data?.updatedAt ?? data?.connectedAt ?? null,
      wasConnected: Boolean(data?.connected),
      expiresAt: Date.now() + 10 * 60 * 1000,
      checking: false,
      closedHandled: false,
    };
    attemptRef.current = attempt;
    setWaiting(true);
    setFeedback({ status: 'pending', label: 'Aguardando autorização na nova janela.' });
    connect.mutate(undefined, {
      onSuccess: ({ authorizationUrl }) => {
        if (attemptRef.current !== attempt) return;
        if (popup.closed) {
          void checkResult('closed');
          return;
        }
        try {
          setFeedback({ status: 'pending', label: 'Aguardando autorização da conta no Mercado Pago.' });
          popup.location.replace(authorizationUrl);
          popup.focus();
        } catch {
          attemptRef.current = null;
          setWaiting(false);
          popup.close();
          setFeedback({ status: 'error', label: 'Não foi possível abrir o Mercado Pago. Permita novas janelas e tente novamente.' });
        }
      },
      onError: (error) => {
        if (attemptRef.current !== attempt) return;
        attemptRef.current = null;
        setWaiting(false);
        popup.close();
        setFeedback({ status: 'error', label: errorMessage(error) });
      },
    });
  }

  return (
    <section id="recebimentos" className="panel" aria-labelledby="account-payout">
      <h2 id="account-payout" className="panel__title"><Wallet size={20} aria-hidden /> Recebimentos — Mercado Pago</h2>
      <p className="panel__lead">Conecte a conta em que deseja receber as inscrições dos seus campeonatos.</p>
      {data?.testMode && (
        <p className="note" role="status"><strong>Modo de teste do Mercado Pago.</strong> Conecte sua conta
          real para autorizar os testes, sem cobranças reais.</p>
      )}
      {account.isLoading && <Loading />}
      <InlineError error={account.error || disconnect.error} />
      {feedback && <ActionFeedback status={feedback.status} label={feedback.label} />}
      {!feedback && result === 'connected' && data?.connected && !needsTestAuthorization && (
        <ActionFeedback status="success" label="Conta autorizada e validada pelo Mercado Pago." />
      )}
      {needsTestAuthorization && (
        <p className="form-error" role="alert">Esta conta ainda tem uma autorização de produção. Renove a
          autorização em modo de teste antes de criar novas cobranças.</p>
      )}
      {!feedback && result === 'error' && (
        <ActionFeedback status="error" label="A conexão não foi concluída. A autorização pode ter sido cancelada,
          expirado ou a conta não está habilitada. Tente novamente com uma conta brasileira ativa.
          Para trocar de conta, desconecte a atual primeiro." />
      )}
      {data?.connected && (
        <div className="stack">
          <p><strong>{data.accountLabel}</strong><br />
            <span className="note">Conta {data.providerUserId} · {data.liveMode ? 'Produção' : 'Teste'}</span></p>
          {data.testMode ? (
            <p className="note">Use cartões e dados de teste nos pagamentos das inscrições. O e-mail do pagador
              deve ser diferente do e-mail da conta conectada e não pode pertencer a uma conta fictícia do Mercado Pago.</p>
          ) : (
            <p className="note">A autorização confirma a conta conectada. A aprovação de cada cobrança e a
              disponibilidade dos recebimentos seguem as regras do Mercado Pago. Mantenha a identificação
              e os documentos da sua conta de vendedor completos.</p>
          )}
        </div>
      )}
      {data?.sandbox && <p className="note">Este ambiente usa pagamentos simulados. A conexão de uma conta real
        estará disponível quando a integração com o Mercado Pago for ativada.</p>}
      {data && !data.sandbox && !data.configured && <p className="note">A integração ainda precisa ser configurada
        pela administração do WODZ. Depois disso você poderá conectar sua conta aqui.</p>}
      {data?.configured && (
        <div className="row-actions">
          <button className="btn btn--primary" type="button" disabled={busy} onClick={startConnect}>
            <ExternalLink size={16} aria-hidden />
            {connect.isPending ? 'Abrindo Mercado Pago…' : waiting ? 'Aguardando autorização…' : data.connected ? 'Renovar autorização' : 'Conectar Mercado Pago'}
          </button>
          {waiting && <button className="btn btn--ghost" type="button" onClick={() => attemptRef.current?.popup.focus()}>
            Ver janela de autorização
          </button>}
          {data.connected && <button className="btn btn--ghost" type="button" disabled={busy}
            onClick={() => window.confirm('Desconectar esta conta? Novas inscrições não poderão ser pagas até conectar uma conta novamente. Cobranças já criadas continuam sendo acompanhadas.') && disconnect.mutate()}>
            {disconnect.isPending ? 'Desconectando…' : 'Desconectar conta'}
          </button>}
        </div>
      )}
      {data?.configured && <p className="note">O Mercado Pago abre em uma nova janela para entrar e autorizar o WODZ.
        Esta página confirma o resultado automaticamente após a autorização.</p>}
    </section>
  );
}
