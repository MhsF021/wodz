import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery } from '@tanstack/react-query';
import { paymentApi } from '../api/endpoints';
import { money } from '../lib/format';
import { checkoutDestination } from '../lib/checkout';
import { Empty, ErrorState, InlineError, Loading } from '../components/States';

export default function PaymentPage() {
  const { code = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const [redirecting, setRedirecting] = useState(false);
  const sessionParam = params.get('checkout');
  const paymentParam = params.get('payment_id') ?? params.get('collection_id');
  const sessionId = sessionParam && /^[1-9]\d*$/.test(sessionParam) ? Number(sessionParam) : null;
  const returnedId = paymentParam && /^\d{1,30}$/.test(paymentParam) ? paymentParam : undefined;
  const summary = useQuery({
    queryKey: ['registration', code],
    queryFn: () => paymentApi.summary(code),
  });
  const checkoutId = Number.isSafeInteger(sessionId) ? sessionId : summary.data?.checkoutId;
  const status = useQuery({
    queryKey: ['checkout-status', code, checkoutId, returnedId],
    queryFn: () => paymentApi.checkoutStatus(code, checkoutId!, returnedId),
    enabled: !!checkoutId,
    refetchInterval: (query) => query.state.data?.teamStatus === 'CONFIRMED'
      || query.state.data?.lastPayment?.status === 'REFUNDED' ? false : 5000,
    refetchIntervalInBackground: false,
  });
  const data = status.data ?? summary.data;
  const create = useMutation({
    mutationFn: () => paymentApi.checkout(code),
    onSuccess: (checkout) => {
      const url = checkoutDestination(checkout.url);
      if (!url) throw new Error('O endereço do checkout não pôde ser validado. Tente novamente.');
      setRedirecting(true);
      // Guarda o checkout na URL antes de sair. Ao voltar pelo navegador, retoma a consulta.
      setParams({ checkout: String(checkout.id) }, { replace: true });
      window.location.assign(url);
    },
  });
  useEffect(() => {
    const resume = () => { setRedirecting(false); void status.refetch(); void summary.refetch(); };
    window.addEventListener('pageshow', resume);
    return () => window.removeEventListener('pageshow', resume);
  }, [status.refetch, summary.refetch]);

  if (summary.isPending) return <Loading />;
  if (!data || summary.error) return <div className="page"><ErrorState error={summary.error} /></div>;
  const approved = data.teamStatus === 'CONFIRMED';
  const closed = data.teamStatus === 'CANCELLED' || data.championshipStarted;
  const payment = data.lastPayment;
  const pending = !!checkoutId && !approved && (!payment || payment.status === 'PENDING');
  const rejected = payment?.status === 'REJECTED' || payment?.status === 'CANCELLED' || payment?.status === 'EXPIRED';
  const refunded = payment?.status === 'REFUNDED';
  const processing = !approved && !!checkoutId && status.isPending;
  return (
    <div className="page page--narrow">
      <p className="crumb"><Link to={`/c/${data.championshipSlug}`}>{data.championshipName}</Link></p>
      <h1 className="page-title">{approved ? 'Inscrição confirmada' : 'Pagamento da inscrição'}</h1>
      <div className="checkout-summary">
        <h2>{data.teamName}</h2>
        <p>{data.categoryName}</p>
        <p className="checkout-summary__amount">{money(data.amount)}</p>
      </div>
      {approved ? (
        <Empty title="Pagamento confirmado"><p>A inscrição da sua equipe está confirmada.</p></Empty>
      ) : refunded ? (
        <Empty title="Pagamento estornado"><p>Fale com a organização sobre a situação da inscrição.</p></Empty>
      ) : closed ? (
        <Empty title={data.teamStatus === 'CANCELLED' ? 'Inscrição cancelada' : 'O campeonato já começou'}>
          <p>Fale com a organização do evento para acompanhar esta inscrição.</p>
        </Empty>
      ) : (
        <div className="stack">
          {processing ? <p role="status">Verificando o retorno do Mercado Pago…</p>
            : pending ? <p role="status">Aguardando confirmação do Mercado Pago. Esta página será atualizada automaticamente.</p>
            : rejected ? <p role="status">O pagamento não foi aprovado. Você pode tentar novamente no Mercado Pago.</p>
            : <p>Você será levado ao Mercado Pago para escolher a forma de pagamento e concluir a inscrição.</p>}
          <InlineError error={status.error ?? create.error} />
          <button type="button" className="btn btn--primary btn--block" disabled={create.isPending || redirecting || processing}
            onClick={() => create.mutate()}>
            {create.isPending || redirecting ? 'Abrindo Mercado Pago…' : pending ? 'Retomar no Mercado Pago' : 'Pagar no Mercado Pago'}
          </button>
          <p className="note">O pagamento será feito no Mercado Pago, nesta mesma aba. Ao retornar, acompanharemos a confirmação.</p>
        </div>
      )}
      <div className="row-actions"><Link className="btn btn--secondary" to="/minhas-equipes">Minhas equipes</Link></div>
    </div>
  );
}
