import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Check, Lock } from 'lucide-react';
import { paymentApi, publicApi } from '../api/endpoints';
import type { Checkout } from '../api/types';
import { money } from '../lib/format';
import { checkoutDestination } from '../lib/checkout';
import { Empty, ErrorState, InlineError, Loading } from '../components/States';

export default function PaymentPage() {
  const { code = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const [redirecting, setRedirecting] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const [now, setNow] = useState(Date.now());
  const sessionParam = params.get('checkout');
  const paymentParam = params.get('payment_id') ?? params.get('collection_id');
  const sessionId = sessionParam && /^[1-9]\d*$/.test(sessionParam) ? Number(sessionParam) : null;
  const returnedId = paymentParam && /^\d{1,30}$/.test(paymentParam) ? paymentParam : undefined;
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity });
  const summary = useQuery({
    queryKey: ['registration', code], queryFn: () => paymentApi.summary(code),
    refetchInterval: query => !query.state.data?.checkoutId && query.state.data?.teamStatus === 'PENDING_PAYMENT'
      && query.state.data?.lastPayment?.status === 'PENDING' ? 5000 : false,
    refetchIntervalInBackground: false,
  });
  const checkoutId = summary.data ? summary.data.checkoutId : Number.isSafeInteger(sessionId) ? sessionId : null;
  const status = useQuery({
    queryKey: ['checkout-status', code, checkoutId, returnedId],
    queryFn: () => paymentApi.checkoutStatus(code, checkoutId!, returnedId), enabled: !!checkoutId,
    refetchInterval: (query) => query.state.data?.teamStatus === 'CONFIRMED'
      || query.state.data?.teamStatus === 'CANCELLED'
      || ['REFUNDED', 'REJECTED', 'CANCELLED'].includes(query.state.data?.lastPayment?.status ?? '') ? false : 5000,
    refetchIntervalInBackground: false,
  });
  const details = useQuery({
    queryKey: ['checkout-details', code, checkoutId],
    queryFn: () => paymentApi.checkoutDetails(code, checkoutId!), enabled: !!checkoutId, staleTime: 60_000,
  });
  const data = status.data ?? summary.data;
  const create = useMutation<Checkout, Error, void>({
    mutationFn: (): Promise<Checkout> => paymentApi.checkout(code, {
      termsAccepted: accepted,
    }),
    onSuccess: (checkout) => {
      setParams({ checkout: String(checkout.id) }, { replace: true });
      const url = checkoutDestination(checkout.url ?? '');
      if (!url) throw new Error('O endereço do checkout não pôde ser validado. Tente novamente.');
      setRedirecting(true); window.location.assign(url);
    },
    // O servidor pode ter preservado uma tentativa mesmo sem receber a resposta do provedor.
    onError: () => { void summary.refetch(); },
  });
  const checkout: Checkout | undefined = create.data?.id === checkoutId ? create.data : details.data;
  const expiresAt = checkout ? Date.parse(checkout.expiresAt) : 0;
  const expired = !!expiresAt && now >= expiresAt;
  const terminal = ['REJECTED', 'CANCELLED', 'EXPIRED', 'REFUNDED'].includes(data?.lastPayment?.status ?? '');
  const active = !!checkout && !expired && !terminal;
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    setAccepted(false); create.reset();
  }, [code]);
  useEffect(() => {
    const resume = () => {
      setRedirecting(false); void summary.refetch();
      if (checkoutId) { void status.refetch(); void details.refetch(); }
    };
    window.addEventListener('pageshow', resume);
    return () => window.removeEventListener('pageshow', resume);
  }, [checkoutId, status.refetch, summary.refetch, details.refetch]);

  if (summary.isPending) return <Loading />;
  if (!data || summary.error) return <div className="page"><ErrorState error={summary.error} /></div>;
  const approved = data.teamStatus === 'CONFIRMED';
  const closed = data.teamStatus === 'CANCELLED' || data.championshipStarted;
  const pending = !approved && data.lastPayment?.status === 'PENDING';
  const pendingPix = pending && data.lastPayment?.method === 'PIX';
  const noAttempt = !!checkoutId && !!status.data && !data.lastPayment && !status.error;
  const processing = !approved && !!checkoutId && (status.isPending || details.isPending);
  const remaining = Math.max(0, Math.ceil((expiresAt - now) / 1000));
  return (
    <div className="page page--narrow payment-page">
      <p className="crumb"><Link to={`/c/${data.championshipSlug}`}>{data.championshipName}</Link></p>
      {!approved && <><p className="eyebrow">Checkout WODZ</p>
        <h1 className="page-title">Confirmar inscrição</h1>
        <p className="lead">{data.teamName} · {data.categoryName}</p></>}
      {approved ? <section className="payment-success" aria-labelledby="payment-success-title" role="status">
        <div className="payment-success__icon"><Check size={24} strokeWidth={1.8} aria-hidden /></div>
        <p className="eyebrow">Tudo certo</p>
        <h1 id="payment-success-title">{data.lastPayment?.status === 'APPROVED' ? 'Pagamento aprovado' : 'Inscrição confirmada'}</h1>
        <p className="payment-success__message">Sua inscrição está confirmada. Nos vemos na arena!</p>
        <dl className="payment-success__details">
          <div><dt>Atleta / equipe</dt><dd>{data.teamName}</dd></div>
          <div><dt>Categoria</dt><dd>{data.categoryName}</dd></div>
          <div><dt>Campeonato</dt><dd>{data.championshipName}</dd></div>
          <div><dt>Valor da inscrição</dt><dd>{money(data.amount)}</dd></div>
        </dl>
        <Link className="btn btn--primary" to="/minhas-equipes">Ver minha inscrição</Link>
        <p className="note">Acompanhe os horários e as provas em Minhas equipes.</p>
      </section>
        : data.lastPayment?.status === 'REFUNDED' ? <Empty title="Pagamento estornado"><p>Fale com a organização sobre a situação da inscrição.</p></Empty>
        : closed ? <Empty title={data.teamStatus === 'CANCELLED' ? 'Inscrição cancelada' : 'O campeonato já começou'}><p>Fale com a organização do evento para acompanhar esta inscrição.</p></Empty>
        : (
        <div className="stack">
          {processing ? <p role="status">Verificando o pagamento…</p>
            : pending && (!expired || pendingPix) ? <p role="status">Aguardando confirmação do pagamento. Esta página será atualizada automaticamente.</p>
            : expired ? <p role="status">O prazo desta cobrança terminou. Você pode gerar um novo pagamento.</p>
            : noAttempt ? <p role="status">Ainda não há um pagamento registrado para esta inscrição. Você pode tentar novamente.</p>
            : terminal ? <p role="status">O pagamento não foi aprovado. Você pode tentar novamente.</p> : null}
          <div className="payment-redirect">
            <strong className="payment-redirect__title"><Lock size={18} strokeWidth={1.8} aria-hidden /> Redirecionamento seguro</strong>
            <p>Você será redirecionado para um ambiente seguro de pagamento. Lá, escolha como pagar: Pix, cartão ou outro meio disponível para sua compra.</p>
          </div>
          <div className="payment-breakdown"><div><span>Inscrição ({data.categoryName})</span><strong>{money(data.amount)}</strong></div>
            <div className="payment-breakdown__total"><strong>Total</strong><strong>{money(data.amount)}</strong></div>
            <p className="note">Sua inscrição será confirmada após a aprovação do pagamento.</p>
          </div>
          <details className="payment-conditions"><summary>Condições de pagamento e uso dos dados</summary>
            <p>Confira a categoria, a equipe e o valor antes de continuar. A inscrição fica pré-registrada até a confirmação do pagamento. Parcelamento e condições de cartão são apresentados no ambiente de pagamento.</p>
            <p>Os dados de cartão são preenchidos no ambiente seguro de pagamento.</p>
            <p>Consulte a organização do evento sobre regras de participação, cancelamento e reembolso.</p>
          </details>
          <label className="payment-consent"><input type="checkbox" checked={accepted} onChange={e => setAccepted(e.target.checked)} />
            <span>Li e concordo com os <Link to="/termos" target="_blank" rel="noopener noreferrer">Termos e Políticas de Compra</Link> e estou ciente da <Link to="/privacidade" target="_blank" rel="noopener noreferrer">Política de Privacidade</Link>.</span></label>
          <InlineError error={create.error ?? status.error ?? details.error} />
          <button type="button" className="btn btn--primary btn--block"
            disabled={!accepted || create.isPending || redirecting || processing || !!details.error || (pendingPix && expired)}
            onClick={() => create.mutate()}>
            {create.isPending || redirecting ? 'Processando…' : 'Continuar para pagamento'}
          </button>
          {!!details.error && <button type="button" className="btn btn--secondary" onClick={() => void details.refetch()}>Retomar cobrança</button>}
          {active && <p className="note">Uma cobrança já foi iniciada. Você pode retomá-la até o vencimento em {Math.ceil(remaining / 60)} minutos.</p>}
        </div>
      )}
      {config.data?.mercadoPagoTestMode && !approved && <p className="note" role="note"><strong>Modo de teste de pagamento.</strong> Use os dados de teste indicados pelo provedor de pagamento.</p>}
      {!approved && <div className="row-actions"><Link className="btn btn--secondary" to="/minhas-equipes">Minhas equipes</Link></div>}
    </div>
  );
}
