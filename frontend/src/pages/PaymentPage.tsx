import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery } from '@tanstack/react-query';
import { paymentApi, publicApi } from '../api/endpoints';
import type { Checkout } from '../api/types';
import { isValidCpf, maskCpf, money, onlyDigits } from '../lib/format';
import { checkoutDestination } from '../lib/checkout';
import { Empty, ErrorState, InlineError, Loading } from '../components/States';

export default function PaymentPage() {
  const { code = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const [redirecting, setRedirecting] = useState(false);
  const [method, setMethod] = useState<'PIX' | 'CARD'>('PIX');
  const [cpf, setCpf] = useState('');
  const [accepted, setAccepted] = useState(false);
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState(false);
  const [now, setNow] = useState(Date.now());
  const sessionParam = params.get('checkout');
  const paymentParam = params.get('payment_id') ?? params.get('collection_id');
  const sessionId = sessionParam && /^[1-9]\d*$/.test(sessionParam) ? Number(sessionParam) : null;
  const returnedId = paymentParam && /^\d{1,30}$/.test(paymentParam) ? paymentParam : undefined;
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity });
  const summary = useQuery({ queryKey: ['registration', code], queryFn: () => paymentApi.summary(code) });
  const checkoutId = Number.isSafeInteger(sessionId) ? sessionId : summary.data?.checkoutId;
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
      method: activeMethod, ...(activeMethod === 'PIX' ? { payerCpf: onlyDigits(cpf) } : {}), termsAccepted: accepted,
    }),
    onSuccess: (checkout) => {
      setCopied(false); setCopyError(false);
      setParams({ checkout: String(checkout.id) }, { replace: true });
      if (checkout.method === 'PIX') { void summary.refetch(); return; }
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
  const activeMethod: 'PIX' | 'CARD' = active ? checkout.method : method;
  const pix = active && checkout.method === 'PIX' && !!checkout.qrCode && !!checkout.qrCodeBase64;
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    setCpf(''); setAccepted(false); setCopied(false); setCopyError(false); setMethod('PIX'); create.reset();
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
  const noAttempt = !!checkoutId && !!status.data && !data.lastPayment && !status.error;
  const processing = !approved && !!checkoutId && (status.isPending || details.isPending);
  const remaining = Math.max(0, Math.ceil((expiresAt - now) / 1000));
  const copyPix = async () => {
    try { await navigator.clipboard.writeText(checkout?.qrCode ?? ''); setCopied(true); setCopyError(false); }
    catch { setCopyError(true); }
  };
  return (
    <div className="page page--narrow payment-page">
      <p className="crumb"><Link to={`/c/${data.championshipSlug}`}>{data.championshipName}</Link></p>
      <p className="eyebrow">Checkout WODZ</p>
      <h1 className="page-title">{approved ? 'Inscrição confirmada' : 'Confirmar inscrição'}</h1>
      <p className="lead">{data.teamName} · {data.categoryName}</p>
      {approved ? <Empty title="Pagamento confirmado"><p>A inscrição da sua equipe está confirmada.</p></Empty>
        : data.lastPayment?.status === 'REFUNDED' ? <Empty title="Pagamento estornado"><p>Fale com a organização sobre a situação da inscrição.</p></Empty>
        : closed ? <Empty title={data.teamStatus === 'CANCELLED' ? 'Inscrição cancelada' : 'O campeonato já começou'}><p>Fale com a organização do evento para acompanhar esta inscrição.</p></Empty>
        : pix ? (
        <section className="pix-payment stack" aria-label="Pagamento com Pix">
          <div className="pix-payment__heading"><p className="eyebrow">Inscrição pré-registrada</p><h2>Pague com Pix</h2>
            <p>Escaneie o QR Code ou copie o código Pix no aplicativo do seu banco para confirmar sua inscrição.</p></div>
          <strong className="pix-payment__amount">{money(data.amount)}</strong>
          <div className="pix-payment__qr"><img src={`data:image/png;base64,${checkout.qrCodeBase64}`} alt="QR Code Pix desta inscrição" /></div>
          <label htmlFor="pix-code">Código Copia e Cola Pix</label>
          <div className="pix-payment__copy"><input id="pix-code" className="input" value={checkout.qrCode!} readOnly onFocus={e => e.target.select()} />
            <button className="btn btn--primary" type="button" onClick={copyPix}>{copied ? 'Copiado!' : 'Copiar'}</button></div>
          {copyError && <p role="alert">Não foi possível copiar automaticamente. Selecione o código e copie manualmente.</p>}
          <div className="payment-notice" role="status"><span className="payment-dot" /> Aguardando pagamento Pix…</div>
          <p className="note">Código válido por {Math.floor(remaining / 60)}min {String(remaining % 60).padStart(2, '0')}s. A confirmação será atualizada automaticamente.</p>
          <InlineError error={status.error ?? details.error} />
          {status.error && <button type="button" className="btn btn--secondary" onClick={() => void status.refetch()}>Consultar pagamento novamente</button>}
        </section>
      ) : (
        <div className="stack">
          {processing ? <p role="status">Verificando o retorno do Mercado Pago…</p>
            : pending && !expired ? <p role="status">Aguardando confirmação do Mercado Pago. Esta página será atualizada automaticamente.</p>
            : expired ? <p role="status">O prazo desta cobrança terminou. Você pode gerar um novo pagamento.</p>
            : noAttempt ? <p role="status">O Mercado Pago ainda não registrou um pagamento para esta inscrição. Você pode tentar novamente.</p>
            : terminal ? <p role="status">O pagamento não foi aprovado. Você pode tentar novamente.</p> : null}
          <fieldset className="payment-methods"><legend>Forma de pagamento</legend>
            <div className="payment-methods__choices">
              {(['PIX', 'CARD'] as const).map(option => <label key={option} className={`payment-methods__choice${activeMethod === option ? ' is-selected' : ''}`}>
                <input type="radio" name="payment-method" value={option} checked={activeMethod === option} disabled={active || create.isPending}
                  onChange={() => { setMethod(option); create.reset(); }} />
                <span>{option === 'PIX' ? 'Pix (QR Code)' : 'Cartão / Outros'}</span>
              </label>)}
            </div>
          </fieldset>
          {activeMethod === 'PIX' ? <div className="field"><label htmlFor="payer-cpf">CPF do Responsável Pagador *</label>
            <input id="payer-cpf" className="input" inputMode="numeric" autoComplete="off" placeholder="000.000.000-00" maxLength={14}
              value={cpf} onChange={e => setCpf(maskCpf(e.target.value))} aria-invalid={!!cpf && !isValidCpf(cpf)} />
            {!!cpf && !isValidCpf(cpf) && <p className="note" role="alert">Informe um CPF válido.</p>}
            <p className="note">O e-mail do seu cadastro será usado para emitir a cobrança.</p>
          </div> : <div className="payment-redirect"><strong>Redirecionamento seguro</strong>
            <p>Você será redirecionado para o ambiente seguro do Mercado Pago para pagar com cartão de crédito à vista ou parcelado, ou outro meio disponível.</p>
          </div>}
          <div className="payment-breakdown"><div><span>Inscrição ({data.categoryName})</span><strong>{money(data.amount)}</strong></div>
            <div className="payment-breakdown__total"><strong>Total</strong><strong>{money(data.amount)}</strong></div>
            <p className="note"><strong>Pagamento processado pelo Mercado Pago</strong><br />Sua inscrição será confirmada após a aprovação do pagamento.</p>
          </div>
          <details className="payment-conditions"><summary>Condições de pagamento e uso dos dados</summary>
            <p>Confira a categoria, a equipe e o valor antes de continuar. A inscrição fica pré-registrada até o Mercado Pago confirmar o pagamento. O Pix deve ser pago dentro da validade exibida. Parcelamento e condições de cartão são apresentados pelo Mercado Pago.</p>
            <p>Para gerar o Pix, enviamos o CPF informado e o e-mail do cadastro ao Mercado Pago. Esses dados são armazenados cifrados para permitir retomar a cobrança. Os dados de cartão são preenchidos no Mercado Pago.</p>
            <p>Consulte a organização do evento sobre regras de participação, cancelamento e reembolso.</p>
          </details>
          <label className="payment-consent"><input type="checkbox" checked={accepted} onChange={e => setAccepted(e.target.checked)} />
            <span>Li e concordo com os <Link to="/termos" target="_blank" rel="noopener noreferrer">Termos e Políticas de Compra</Link> e estou ciente da <Link to="/privacidade" target="_blank" rel="noopener noreferrer">Política de Privacidade</Link>.</span></label>
          <InlineError error={create.error ?? status.error ?? details.error} />
          <button type="button" className="btn btn--primary btn--block"
            disabled={!accepted || (activeMethod === 'PIX' && !isValidCpf(cpf)) || create.isPending || redirecting || processing || !!details.error}
            onClick={() => create.mutate()}>
            {create.isPending || redirecting ? 'Processando…' : activeMethod === 'PIX' ? 'Gerar Pix' : 'Pagar no Mercado Pago'}
          </button>
          {!!details.error && <button type="button" className="btn btn--secondary" onClick={() => void details.refetch()}>Retomar cobrança</button>}
          {active && <p className="note">Uma cobrança já foi iniciada. Para escolher outra forma, aguarde o vencimento em {Math.ceil(remaining / 60)} minutos.</p>}
        </div>
      )}
      {config.data?.mercadoPagoTestMode && !approved && <p className="note" role="note"><strong>Modo de teste do Mercado Pago.</strong> Use os dados de teste indicados pelo Mercado Pago. Não pague um QR Code de teste com uma conta bancária real.</p>}
      <div className="row-actions"><Link className="btn btn--secondary" to="/minhas-equipes">Minhas equipes</Link></div>
    </div>
  );
}
