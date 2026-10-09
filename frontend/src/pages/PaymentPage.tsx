import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CreditCard, ExternalLink, QrCode } from 'lucide-react';
import { paymentApi, publicApi } from '../api/endpoints';
import type { CardInput, CardMethod, Payment, PaymentMethod, PayerInput, ThreeDsChallenge } from '../api/types';
import { dateTime, isValidCpf, maskCpf, methodLabel, money, onlyDigits } from '../lib/format';
import { useAuth } from '../auth/AuthContext';
import { Field } from '../components/Field';
import { CopyButton } from '../components/CopyButton';
import { PaymentBadge } from '../components/StatusBadge';
import { MercadoPagoCardBrick } from '../components/MercadoPagoCardBrick';
import { ErrorState, InlineError, Loading } from '../components/States';
import { ActionFeedback } from '../components/ActionFeedback';
import { openExternalWindow, publishExternalResult, subscribeExternalResult } from '../lib/externalWindow';

type Choice = 'PIX' | 'CARD';
type WindowNotice = { status: 'pending' | 'error'; label: string };

const METHODS: { id: Choice; label: string; icon: typeof QrCode }[] = [
  { id: 'PIX', label: 'Pix', icon: QrCode },
  { id: 'CARD', label: 'Cartão', icon: CreditCard },
];

const CARD_TYPES: { id: CardMethod; label: string; hint: string }[] = [
  { id: 'CREDIT_CARD', label: 'Crédito', hint: 'À vista ou parcelado' },
  { id: 'DEBIT_CARD', label: 'Débito', hint: 'À vista' },
];

export default function PaymentPage() {
  const { code = '' } = useParams();
  const [searchParams] = useSearchParams();
  const checkoutWindow = searchParams.get('checkout') === '1';
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const paymentWindow = useRef<Window | null>(null);
  const [windowActive, setWindowActive] = useState(false);
  const [windowNotice, setWindowNotice] = useState<WindowNotice | null>(null);

  const summary = useQuery({
    queryKey: ['registration', code],
    queryFn: () => paymentApi.summary(code),
    refetchInterval: (query) => !checkoutWindow && (windowActive || query.state.data?.lastPayment?.status === 'PENDING')
      ? 5000 : false,
    refetchIntervalInBackground: true,
  });
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity });

  const [choice, setChoice] = useState<Choice>('PIX');
  // no cartão, crédito ou débito é escolha explícita: o formulário só aparece depois dela
  const [cardType, setCardType] = useState<CardMethod | null>(null);
  const [payer, setPayer] = useState<PayerInput>(() => {
    const [first = '', ...rest] = (user?.name ?? '').toUpperCase().split(' ');
    return { firstName: first, lastName: rest.join(' '), email: user?.email ?? '', cpf: '' };
  });
  const payerInitialized = useRef(Boolean(user));
  const [payment, setPayment] = useState<Payment | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [resumeChecked, setResumeChecked] = useState(false);

  // A nova janela carrega a sessão novamente; preenche sem sobrescrever o que o atleta digitou.
  useEffect(() => {
    if (!user || payerInitialized.current) return;
    payerInitialized.current = true;
    const [first = '', ...rest] = user.name.toUpperCase().split(' ');
    setPayer((current) => ({
      ...current,
      firstName: current.firstName || first,
      lastName: current.lastName || rest.join(' '),
      email: current.email || user.email,
    }));
  }, [user]);

  // Retoma uma única vez um Pix ou cartão pendente (ex.: atleta voltou pelo link). Boletos antigos não.
  useEffect(() => {
    if (!summary.data || (checkoutWindow && resumeChecked)) return;
    setResumeChecked(true);
    const last = summary.data.lastPayment;
    if (last && last.method !== 'BOLETO') {
      setPayment((current) => current?.id === last.id && current.status === last.status ? current : last);
      if (last.method === 'PIX') {
        setChoice('PIX');
      } else {
        setChoice('CARD');
        setCardType(last.method);
      }
    }
  }, [summary.data, resumeChecked, checkoutWindow]);

  // Polling enquanto o pagamento está pendente (Pix, ou cartão em análise/3DS)
  const poll = useQuery({
    queryKey: ['payment', code, payment?.id],
    queryFn: () => paymentApi.status(code, payment!.id),
    enabled: payment?.status === 'PENDING',
    refetchInterval: 5000,
    refetchIntervalInBackground: true,
  });
  useEffect(() => {
    if (poll.data && payment && poll.data.id === payment.id && poll.data.status !== payment.status) {
      setPayment(poll.data);
    }
  }, [poll.data, payment]);

  useEffect(() => {
    if (payment?.status === 'APPROVED') {
      queryClient.invalidateQueries({ queryKey: ['registration', code] });
      queryClient.invalidateQueries({ queryKey: ['my-teams'] });
    }
  }, [payment?.status, code, queryClient]);

  // A mensagem da outra janela é só um sinal: a confirmação continua vindo da API.
  useEffect(() => {
    if (checkoutWindow) return;
    return subscribeExternalResult('payment', (result) => {
      if (result.code !== code) return;
      setWindowNotice(result.status === 'error'
        ? { status: 'error', label: 'O pagamento não foi concluído. Confira os dados na janela de pagamento.' } : null);
      queryClient.invalidateQueries({ queryKey: ['registration', code] });
      queryClient.invalidateQueries({ queryKey: ['payment', code] });
    });
  }, [checkoutWindow, code, queryClient]);

  useEffect(() => {
    if (!checkoutWindow || !payment) return;
    publishExternalResult({
      kind: 'payment',
      code,
      status: payment.status === 'APPROVED' ? 'success' : payment.status === 'PENDING' ? 'pending' : 'error',
    });
  }, [checkoutWindow, code, payment?.id, payment?.status]);

  useEffect(() => {
    if (!checkoutWindow) return;
    const notifyClosed = () => publishExternalResult({
      kind: 'payment',
      code,
      status: payment?.status === 'APPROVED' ? 'success' : payment && payment.status !== 'PENDING' ? 'error' : 'pending',
    });
    window.addEventListener('pagehide', notifyClosed);
    return () => window.removeEventListener('pagehide', notifyClosed);
  }, [checkoutWindow, code, payment]);

  useEffect(() => {
    if (!windowActive) return;
    const timer = window.setInterval(() => {
      if (!paymentWindow.current?.closed) return;
      setWindowActive(false);
      paymentWindow.current = null;
      setWindowNotice({ status: 'pending', label: 'Pagamento ainda não concluído. Você pode retomar na nova janela.' });
      queryClient.invalidateQueries({ queryKey: ['registration', code] });
      queryClient.invalidateQueries({ queryKey: ['payment', code] });
    }, 750);
    return () => window.clearInterval(timer);
  }, [windowActive, code, queryClient]);

  useEffect(() => {
    if (checkoutWindow) return;
    const refresh = () => {
      queryClient.invalidateQueries({ queryKey: ['registration', code] });
      queryClient.invalidateQueries({ queryKey: ['payment', code] });
    };
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [checkoutWindow, code, queryClient]);

  const create = useMutation({
    mutationFn: ({ method, card }: { method: PaymentMethod; card?: CardInput }) =>
      paymentApi.create(code, {
        method,
        payer: { ...payer, firstName: payer.firstName.toUpperCase(), lastName: payer.lastName.toUpperCase(), cpf: onlyDigits(payer.cpf) },
        card,
      }),
    onSuccess: (created) => {
      setPayment(created);
      // a nova cobrança cancela as pendentes anteriores; atualiza o resumo
      queryClient.invalidateQueries({ queryKey: ['registration', code] });
    },
    onError: () => {
      if (checkoutWindow) publishExternalResult({ kind: 'payment', code, status: 'error' });
    },
  });

  const simulate = useMutation({
    mutationFn: () => paymentApi.simulate(code, payment!.id),
    onSuccess: setPayment,
  });

  if (summary.isLoading) return <Loading />;
  if (summary.error || !summary.data) {
    return (
      <div className="page page--narrow">
        <ErrorState error={summary.error} />
      </div>
    );
  }
  const s = summary.data;
  const latestPayment = payment && (!s.lastPayment || payment.id >= s.lastPayment.id) ? payment : s.lastPayment;
  // Para a mesma cobrança, usa a consulta mais recente em vez de uma aprovação antiga no resumo.
  const verifiedPayment = latestPayment && poll.data?.id === latestPayment.id && poll.dataUpdatedAt >= summary.dataUpdatedAt
    ? poll.data
    : latestPayment && s.lastPayment?.id === latestPayment.id && summary.dataUpdatedAt > poll.dataUpdatedAt
      ? s.lastPayment : latestPayment;
  const paymentApproved = verifiedPayment?.status === 'APPROVED';
  const confirmedWithoutPayment = s.teamStatus === 'CONFIRMED' && !verifiedPayment;
  const approved = s.teamStatus !== 'CANCELLED' && (paymentApproved || confirmedWithoutPayment);

  function openPayment() {
    if (paymentWindow.current && !paymentWindow.current.closed) {
      paymentWindow.current.focus();
      return;
    }
    const url = `${window.location.origin}/pagamento/${encodeURIComponent(code)}?checkout=1`;
    const opened = openExternalWindow(url, `wodz-payment-${code}`);
    if (!opened) {
      setWindowNotice({ status: 'error', label: 'Permita novas janelas no navegador para abrir o pagamento.' });
      return;
    }
    paymentWindow.current = opened;
    setWindowNotice(null);
    setWindowActive(true);
  }

  function payerValid(): boolean {
    if (!payer.firstName.trim() || !payer.lastName.trim()) return fail('Informe nome e sobrenome do pagador');
    if (!/^\S+@\S+\.\S+$/.test(payer.email)) return fail('Informe um e-mail válido');
    if (!isValidCpf(payer.cpf)) return fail('Informe um CPF válido');
    setFormError(null);
    return true;
  }
  function fail(message: string) {
    setFormError(message);
    return false;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    // o cartão é enviado pelo próprio formulário do cartão
    if (choice === 'PIX' && payerValid()) create.mutate({ method: 'PIX' });
  }

  function payWithCard(type: CardMethod, card: CardInput) {
    if (!payerValid()) return Promise.reject(new Error('Confira os dados de quem está pagando'));
    return create.mutateAsync({ method: type, card });
  }

  const legacyBoleto = s.lastPayment?.method === 'BOLETO' && s.lastPayment.status === 'PENDING';
  const pendingOrResult = payment && payment.status !== 'CANCELLED' && payment.status !== 'EXPIRED';

  return (
    <div className="page page--form">
      <h1 className="page-title">Pagamento da inscrição</h1>
      {config.data?.mercadoPagoTestMode && (
        <p className="note" role="status"><strong>Modo de teste do Mercado Pago.</strong> Use cartões e dados
          de teste nas novas cobranças. Cobranças anteriores mantêm seu modo original.</p>
      )}

      <section className="pay-summary" aria-label="Resumo da inscrição">
        <div>
          <p className="pay-summary__team">{s.teamName}</p>
          <p className="pay-summary__meta">
            {s.championshipName}, categoria {s.categoryName}
          </p>
        </div>
        <p className="pay-summary__amount">{money(s.amount)}</p>
      </section>

      {approved ? (
        <section className={`result${paymentApproved ? ' result--ok' : ''}`} aria-live="polite">
          {paymentApproved && <ActionFeedback status="success" label="Pagamento aprovado." />}
          <h2 className="result__title">Inscrição confirmada</h2>
          <p>{paymentApproved ? 'O pagamento foi aprovado e a equipe' : 'A equipe'} {s.teamName} já está confirmada no campeonato.</p>
          <div className="result__actions">
            {checkoutWindow && (
              <button type="button" className="btn btn--secondary" onClick={() => window.close()}>Fechar janela de pagamento</button>
            )}
            <Link className="btn btn--primary" to="/minhas-equipes">Ver minhas equipes</Link>
            <Link className="btn btn--secondary" to={`/c/${s.championshipSlug}`}>Página do campeonato</Link>
          </div>
        </section>
      ) : s.teamStatus === 'CANCELLED' ? (
        <section className="result result--warn">
          <ActionFeedback status="error" label="Esta inscrição foi cancelada." />
          <h2 className="result__title">Inscrição cancelada</h2>
          <p>Esta inscrição foi cancelada. Fale com a organização do campeonato.</p>
        </section>
      ) : s.teamStatus === 'CONFIRMED' && verifiedPayment ? (
        <section className="result result--warn" aria-live="polite">
          <ActionFeedback status={verifiedPayment.status === 'PENDING' ? 'pending' : 'error'}
            label={verifiedPayment.status === 'REFUNDED' ? 'O pagamento foi devolvido.'
              : verifiedPayment.status === 'REJECTED' ? 'O pagamento foi recusado.'
                : verifiedPayment.status === 'EXPIRED' ? 'A cobrança expirou.'
                  : verifiedPayment.status === 'CANCELLED' ? 'A cobrança foi cancelada.' : 'O pagamento está pendente.'} />
          <h2 className="result__title">Confira o pagamento da inscrição</h2>
          <p>Fale com a organização para conferir o pagamento desta inscrição antes de realizar uma nova cobrança.</p>
          <div className="result__actions">
            {checkoutWindow && <button type="button" className="btn btn--secondary" onClick={() => window.close()}>Fechar janela de pagamento</button>}
            <Link className="btn btn--secondary" to="/minhas-equipes">Ver minhas equipes</Link>
            <Link className="btn btn--secondary" to={`/c/${s.championshipSlug}`}>Página do campeonato</Link>
          </div>
        </section>
      ) : !checkoutWindow ? (
        <section className="pending" aria-label="Abrir pagamento">
          <h2 className="section-title">Pagamento em uma nova janela</h2>
          {payment && windowNotice?.status !== 'error' && payment.status !== 'REFUNDED' && payment.status !== 'APPROVED' && (
            <ActionFeedback
              key={`${payment.id}-${payment.status}`}
              status={payment.status === 'PENDING' ? 'pending' : 'error'}
              label={payment.status === 'PENDING' ? 'Aguardando confirmação do pagamento.'
                : payment.status === 'REJECTED' ? 'Pagamento recusado. Você pode tentar outra forma de pagamento.'
                  : payment.status === 'EXPIRED' ? 'A cobrança expirou. Gere uma nova na janela de pagamento.'
                    : 'A cobrança foi cancelada. Você pode tentar novamente.'}
            />
          )}
          {payment?.status === 'REFUNDED' && <ActionFeedback status="error" label="O pagamento foi devolvido." />}
          {windowNotice && (!payment || windowNotice.status === 'error') && <ActionFeedback status={windowNotice.status} label={windowNotice.label} />}
          {windowActive && !payment && !windowNotice && <ActionFeedback status="pending" label="Conclua o pagamento na nova janela." />}
          <p className="note">Escolha Pix ou cartão na janela de pagamento. A confirmação da inscrição aparece aqui automaticamente.</p>
          {s.championshipStarted && !payment ? (
            <p className="note">As inscrições estão encerradas. Fale com a organização do campeonato.</p>
          ) : (
            <div className="row-actions">
              <button type="button" className="btn btn--primary" onClick={openPayment}>
                <ExternalLink size={16} aria-hidden />
                {windowActive ? 'Voltar à janela de pagamento' : payment?.status === 'PENDING' ? 'Continuar pagamento' : 'Abrir pagamento'}
              </button>
            </div>
          )}
        </section>
      ) : pendingOrResult && payment.status !== 'REJECTED' && payment.status !== 'REFUNDED' ? (
        <PendingPayment
          payment={payment}
          canSimulate={Boolean(config.data?.paymentSimulation && !config.data.mercadoPagoTestMode)}
          onSimulate={() => simulate.mutate()}
          simulating={simulate.isPending}
          simulateError={simulate.error}
          onRestart={() => setPayment(null)}
        />
      ) : s.championshipStarted ? (
        <section className="result result--warn">
          <h2 className="result__title">Inscrições encerradas</h2>
          <p>O campeonato já começou e não aceita mais pagamentos de inscrição. Fale com a organização do campeonato.</p>
        </section>
      ) : (
        <form className="form form--wide" onSubmit={submit} noValidate>
          {payment?.status === 'REJECTED' && (
            <div className="result result--warn" role="alert">
              <ActionFeedback status="error" label="Pagamento recusado." />
              <h2 className="result__title">Pagamento recusado</h2>
              <p>{payment.statusDetail ?? 'O emissor recusou a transação.'} Tente outro cartão ou outra forma de pagamento.</p>
            </div>
          )}
          {payment && (payment.status === 'EXPIRED' || payment.status === 'CANCELLED') && (
            <ActionFeedback status="error" label={payment.status === 'EXPIRED' ? 'A cobrança anterior expirou. Gere uma nova abaixo.' : 'A cobrança anterior foi cancelada. Gere uma nova abaixo.'} />
          )}
          {payment?.status === 'REFUNDED' && <ActionFeedback status="error" label="O pagamento anterior foi devolvido." />}
          {legacyBoleto && (
            <p className="note">
              Há um boleto gerado antes aguardando compensação. Se você já pagou, aguarde: a confirmação é
              automática e não é preciso pagar de novo.
            </p>
          )}

          <fieldset className="form-section">
            <legend className="form-section__title">Quem está pagando</legend>
            <div className="grid-2">
              <Field label="Nome" htmlFor="payer-first" uppercase>
                <input id="payer-first" className="input" autoComplete="given-name" value={payer.firstName}
                  onChange={(e) => setPayer({ ...payer, firstName: e.target.value.toUpperCase() })} />
              </Field>
              <Field label="Sobrenome" htmlFor="payer-last" uppercase>
                <input id="payer-last" className="input" autoComplete="family-name" value={payer.lastName}
                  onChange={(e) => setPayer({ ...payer, lastName: e.target.value.toUpperCase() })} />
              </Field>
              <Field label="E-mail" htmlFor="payer-email">
                <input id="payer-email" className="input" type="email" autoComplete="email" value={payer.email}
                  onChange={(e) => setPayer({ ...payer, email: e.target.value })} />
              </Field>
              <Field label="CPF" htmlFor="payer-cpf">
                <input id="payer-cpf" className="input" inputMode="numeric" placeholder="000.000.000-00"
                  value={payer.cpf} onChange={(e) => setPayer({ ...payer, cpf: maskCpf(e.target.value) })} />
              </Field>
            </div>
          </fieldset>

          <fieldset className="form-section">
            <legend className="form-section__title">Forma de pagamento</legend>
            <div className="methods" role="radiogroup" aria-label="Forma de pagamento">
              {METHODS.map(({ id, label, icon: Icon }) => (
                <label key={id} className="method">
                  <input type="radio" name="method" checked={choice === id} onChange={() => setChoice(id)} />
                  <span className="method__body">
                    <Icon size={20} aria-hidden />
                    {label}
                  </span>
                </label>
              ))}
            </div>

            {choice === 'PIX' && (
              <p className="note">Geramos um QR Code com validade de 30 minutos. A confirmação é automática.</p>
            )}

            {choice === 'CARD' && (
              <>
                <p className="method-group__title" aria-hidden>Tipo do cartão</p>
                <div className="methods" role="radiogroup" aria-label="Tipo do cartão">
                  {CARD_TYPES.map(({ id, label, hint }) => (
                    <label key={id} className="method">
                      <input type="radio" name="card-type" checked={cardType === id}
                        onChange={() => setCardType(id)} />
                      <span className="method__body">
                        {label}
                        <span className="method__hint">{hint}</span>
                      </span>
                    </label>
                  ))}
                </div>

                {!cardType ? (
                  <p className="note">Escolha crédito ou débito para preencher os dados do cartão.</p>
                ) : config.data?.mercadoPagoPublicKey ? (
                  <MercadoPagoCardBrick
                    key={cardType}
                    publicKey={config.data.mercadoPagoPublicKey}
                    amount={s.amount}
                    email={payer.email}
                    cardType={cardType}
                    onToken={(card) => payWithCard(cardType, card)}
                  />
                ) : config.data?.paymentSimulation && !config.data.mercadoPagoTestMode ? (
                  <SandboxCardForm
                    key={cardType}
                    cardType={cardType}
                    busy={create.isPending}
                    onToken={(card) => {
                      payWithCard(cardType, card).catch(() => undefined);
                    }}
                  />
                ) : config.isLoading ? (
                  <Loading />
                ) : (
                  <p className="form-error" role="alert">Não foi possível carregar o formulário de cartão.
                    Tente novamente em instantes.</p>
                )}
              </>
            )}
          </fieldset>

          {formError && <p className="form-error" role="alert">{formError}</p>}
          {create.error && <ActionFeedback status="error" label="Não foi possível concluir o pagamento. Confira a mensagem abaixo." />}
          <InlineError error={create.error} />

          {choice === 'PIX' && (
            <div className="form-actions">
              <button type="submit" className="btn btn--primary btn--large" disabled={create.isPending}>
                {create.isPending && <span className="spinner spinner--small" aria-hidden />}
                {create.isPending ? 'Gerando…' : 'Gerar Pix'}
              </button>
            </div>
          )}
        </form>
      )}
    </div>
  );
}

function PendingPayment({
  payment,
  canSimulate,
  onSimulate,
  simulating,
  simulateError,
  onRestart,
}: {
  payment: Payment;
  canSimulate: boolean;
  onSimulate: () => void;
  simulating: boolean;
  simulateError: unknown;
  onRestart: () => void;
}) {
  return (
    <section className="pending" aria-live="polite">
      <ActionFeedback status="pending" label="Aguardando confirmação do pagamento." />
      <div className="pending__head">
        <h2 className="section-title">
          {payment.method === 'PIX'
            ? 'Pague com Pix'
            : payment.threeDsChallenge
              ? 'Confirme o pagamento com seu banco'
              : 'Pagamento com cartão em análise'}
        </h2>
        <PaymentBadge status={payment.status} />
      </div>

      {payment.method === 'PIX' && (
        <>
          {payment.pixQrCodeBase64 && (
            <img className="pending__qr" src={`data:image/png;base64,${payment.pixQrCodeBase64}`}
              alt="QR Code do Pix" width={220} height={220} />
          )}
          <Field label="Pix copia e cola" htmlFor="pix-code">
            <textarea id="pix-code" className="input input--code" readOnly rows={3} value={payment.pixQrCode ?? ''} />
          </Field>
          {payment.pixQrCode && <CopyButton value={payment.pixQrCode} label="Copiar código Pix" />}
        </>
      )}

      {payment.method !== 'PIX' && (
        <>
          <p className="note">
            {methodLabel[payment.method]}
            {payment.cardLastDigits ? ` final ${payment.cardLastDigits}` : ''}
            {payment.installments && payment.installments > 1 ? ` em ${payment.installments}x` : ''}.
          </p>
          {payment.threeDsChallenge ? (
            <ThreeDsChallengeFrame challenge={payment.threeDsChallenge} />
          ) : (
            <p className="note">O emissor do cartão está analisando a transação.</p>
          )}
        </>
      )}

      {payment.expiresAt && <p className="note">Válido até {dateTime(payment.expiresAt)}.</p>}
      <p className="note">Esta página atualiza sozinha quando o pagamento for confirmado.</p>

      <div className="row-actions">
        {canSimulate && (
          <button type="button" className="btn btn--primary" onClick={onSimulate} disabled={simulating}>
            {simulating && <span className="spinner spinner--small" aria-hidden />}
            {simulating ? 'Aprovando…' : 'Simular pagamento aprovado (sandbox)'}
          </button>
        )}
        <button type="button" className="btn btn--ghost" onClick={onRestart}>
          Usar outra forma de pagamento
        </button>
      </div>
      <InlineError error={simulateError} />
    </section>
  );
}

function isHttpsUrl(value: string) {
  try {
    return new URL(value).protocol === 'https:';
  } catch {
    return false;
  }
}

/**
 * Desafio 3DS: envia o creq por POST para a página do emissor dentro de um
 * iframe isolado (sem acesso à navegação desta página). O resultado não é lido
 * daqui: o status vem do polling, que o backend confere no Mercado Pago.
 */
function ThreeDsChallengeFrame({ challenge }: { challenge: ThreeDsChallenge }) {
  const formRef = useRef<HTMLFormElement>(null);
  const submitted = useRef(false);
  const frameName = useRef(`threeds-${Math.random().toString(36).slice(2)}`).current;
  const safe = isHttpsUrl(challenge.url);

  useEffect(() => {
    if (!safe || submitted.current) return;
    submitted.current = true;
    formRef.current?.submit();
  }, [safe]);

  if (!safe) {
    return (
      <p className="form-error" role="alert">
        Não foi possível abrir a confirmação do banco. Tente outro cartão ou pague com Pix.
      </p>
    );
  }
  return (
    <div className="threeds">
      <p className="note">Seu banco pediu uma confirmação de segurança. Conclua abaixo em até 5 minutos.</p>
      <iframe
        name={frameName}
        title="Confirmação de segurança do banco"
        className="threeds__frame"
        sandbox="allow-forms allow-scripts allow-same-origin"
        referrerPolicy="no-referrer"
      />
      <form ref={formRef} method="post" action={challenge.url} target={frameName} hidden>
        <input type="hidden" name="creq" value={challenge.creq} />
      </form>
    </div>
  );
}

/** Formulário de teste usado quando não há chave do Mercado Pago (provedor sandbox). */
function SandboxCardForm({
  cardType,
  busy,
  onToken,
}: {
  cardType: CardMethod;
  busy: boolean;
  onToken: (card: CardInput) => void;
}) {
  const debit = cardType === 'DEBIT_CARD';
  const [card, setCard] = useState({ number: '', holder: '', expiry: '', cvv: '', installments: '1' });

  function pay() {
    const digits = onlyDigits(card.number);
    onToken({
      token: digits.endsWith('0002') ? 'sandbox-rejected' : 'sandbox-approved',
      paymentMethodId: debit ? 'debvisa' : 'visa',
      installments: debit ? 1 : Number(card.installments),
      lastFourDigits: digits.slice(-4),
    });
  }

  const ready = onlyDigits(card.number).length >= 13 && card.holder.trim() && card.expiry && card.cvv.length >= 3;

  return (
    <div className="sandbox-card">
      <p className="note">
        Ambiente de teste: nenhum valor é cobrado. Qualquer número é aprovado; cartões terminados em 0002 são recusados.
      </p>
      <div className="grid-2">
        <Field label="Número do cartão" htmlFor="cc-number">
          <input id="cc-number" className="input" inputMode="numeric" autoComplete="cc-number" value={card.number}
            onChange={(e) => setCard({ ...card, number: e.target.value })} />
        </Field>
        <Field label="Nome impresso no cartão" htmlFor="cc-holder" uppercase>
          <input id="cc-holder" className="input" autoComplete="cc-name" value={card.holder}
            onChange={(e) => setCard({ ...card, holder: e.target.value.toUpperCase() })} />
        </Field>
        <Field label="Validade (MM/AA)" htmlFor="cc-exp">
          <input id="cc-exp" className="input" autoComplete="cc-exp" placeholder="12/29" value={card.expiry}
            onChange={(e) => setCard({ ...card, expiry: e.target.value })} />
        </Field>
        <Field label="CVV" htmlFor="cc-cvv">
          <input id="cc-cvv" className="input" inputMode="numeric" autoComplete="cc-csc" maxLength={4} value={card.cvv}
            onChange={(e) => setCard({ ...card, cvv: onlyDigits(e.target.value) })} />
        </Field>
        {!debit && (
          <Field label="Parcelas" htmlFor="cc-inst">
            <select id="cc-inst" className="input" value={card.installments}
              onChange={(e) => setCard({ ...card, installments: e.target.value })}>
              {[1, 2, 3, 4, 5, 6].map((n) => <option key={n} value={n}>{n}x</option>)}
            </select>
          </Field>
        )}
      </div>
      <div className="form-actions">
        <button type="button" className="btn btn--primary btn--large" disabled={!ready || busy} onClick={pay}>
          {busy && <span className="spinner spinner--small" aria-hidden />}
          {busy ? 'Processando…' : debit ? 'Pagar no débito' : 'Pagar no crédito'}
        </button>
      </div>
    </div>
  );
}
