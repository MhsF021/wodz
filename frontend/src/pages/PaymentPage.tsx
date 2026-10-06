import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { paymentApi } from '../api/endpoints';
import type { Payment, PayerInput, PayerPrefill, User } from '../api/types';
import { dateTime, isValidCpf, isValidPhone, maskCpf, maskPhone, methodLabel, money, onlyDigits } from '../lib/format';
import { useAuth } from '../auth/AuthContext';
import { Field } from '../components/Field';
import { PaymentBadge } from '../components/StatusBadge';
import { ErrorState, InlineError, Loading } from '../components/States';

/** Dados vindos da inscrição têm prioridade; o perfil completa o que faltar (inclusive o celular). */
function fillPayer(current: PayerInput, prefill: PayerPrefill | undefined, user: User | null): PayerInput {
  const [first = '', ...rest] = (prefill?.fullName ?? user?.name ?? '').trim().split(/\s+/);
  return {
    firstName: current.firstName || first,
    lastName: current.lastName || rest.join(' '),
    email: current.email || prefill?.email || user?.email || '',
    cpf: current.cpf || (prefill?.cpf ? maskCpf(prefill.cpf) : ''),
    phone: current.phone || (user?.phone ? maskPhone(user.phone) : ''),
  };
}

const EMPTY_PAYER: PayerInput = { firstName: '', lastName: '', email: '', cpf: '', phone: '' };

function payerError(payer: PayerInput): string | null {
  if (!payer.firstName.trim() || !payer.lastName.trim()) return 'Informe nome e sobrenome de quem está pagando';
  if (!/^\S+@\S+\.\S+$/.test(payer.email.trim())) return 'Informe um e-mail válido';
  if (!isValidCpf(payer.cpf)) return 'Informe um CPF válido';
  if (!isValidPhone(payer.phone)) return 'Informe um celular válido com DDD, ex.: (81) 99999-9999';
  return null;
}

export default function PaymentPage() {
  const { code = '' } = useParams();
  const [searchParams] = useSearchParams();
  const prefill = (useLocation().state as { payer?: PayerPrefill } | null)?.payer;
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const summary = useQuery({ queryKey: ['registration', code], queryFn: () => paymentApi.summary(code) });

  const [payer, setPayer] = useState<PayerInput>(() => fillPayer(EMPTY_PAYER, prefill, user));
  const [payment, setPayment] = useState<Payment | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [redirecting, setRedirecting] = useState(false);
  const [resumeChecked, setResumeChecked] = useState(false);

  // o perfil chega depois do primeiro render quando a página é aberta direto pelo link
  useEffect(() => {
    if (user) setPayer((current) => fillPayer(current, prefill, user));
  }, [user, prefill]);

  // Retoma uma única vez o último checkout (ex.: atleta voltou do Mercado Pago ou reabriu o link).
  useEffect(() => {
    if (resumeChecked || !summary.data) return;
    setResumeChecked(true);
    const last = summary.data.lastPayment;
    if (last?.checkoutUrl) setPayment(last);
  }, [summary.data, resumeChecked]);

  // Enquanto pendente, a API consulta o Mercado Pago (funciona mesmo sem webhook)
  const poll = useQuery({
    queryKey: ['payment', code, payment?.id],
    queryFn: () => paymentApi.status(code, payment!.id),
    enabled: payment?.status === 'PENDING',
    refetchInterval: 5000,
  });
  useEffect(() => {
    if (poll.data && payment && poll.data.id === payment.id && poll.data !== payment) {
      setPayment(poll.data);
    }
  }, [poll.data, payment]);

  useEffect(() => {
    if (payment?.status === 'APPROVED') {
      queryClient.invalidateQueries({ queryKey: ['registration', code] });
    }
  }, [payment?.status, code, queryClient]);

  const create = useMutation({
    mutationFn: () =>
      paymentApi.create(code, {
        payer: {
          firstName: payer.firstName.trim(),
          lastName: payer.lastName.trim(),
          email: payer.email.trim(),
          cpf: onlyDigits(payer.cpf),
          phone: onlyDigits(payer.phone),
        },
      }),
    onSuccess: (created) => {
      setPayment(created);
      if (created.checkoutUrl) {
        setRedirecting(true);
        window.location.assign(created.checkoutUrl);
      }
    },
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
  const approved = s.teamStatus === 'CONFIRMED' || payment?.status === 'APPROVED';
  const inCheckout = payment?.status === 'PENDING' && Boolean(payment.checkoutUrl);
  // cobranças anteriores ao Checkout Pro (Pix/cartão/boleto) ainda podem compensar
  const legacyPending = !payment && s.lastPayment?.status === 'PENDING' && !s.lastPayment.checkoutUrl;

  function submit(event: FormEvent) {
    event.preventDefault();
    const error = payerError(payer);
    setFormError(error);
    if (!error) create.mutate();
  }

  const set = (field: keyof PayerInput) => (value: string) => setPayer({ ...payer, [field]: value });

  return (
    <div className="page page--form">
      <h1 className="page-title">Pagamento da inscrição</h1>

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
        <section className="result result--ok" aria-live="polite">
          <h2 className="result__title">Inscrição confirmada</h2>
          <p>O pagamento foi aprovado e a equipe {s.teamName} já está confirmada no campeonato.</p>
          <div className="result__actions">
            <Link className="btn btn--primary" to="/minhas-equipes">Ver minhas equipes</Link>
            <Link className="btn btn--secondary" to={`/c/${s.championshipSlug}`}>Página do campeonato</Link>
          </div>
        </section>
      ) : s.teamStatus === 'CANCELLED' ? (
        <section className="result result--warn">
          <h2 className="result__title">Inscrição cancelada</h2>
          <p>Esta inscrição foi cancelada. Fale com a organização do campeonato.</p>
        </section>
      ) : inCheckout && payment ? (
        <CheckoutInProgress
          payment={payment}
          returned={searchParams.has('checkout')}
          redirecting={redirecting}
          onChangePayer={() => setPayment(null)}
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
              <h2 className="result__title">Pagamento não concluído</h2>
              <p>
                {payment.statusDetail ?? 'O Mercado Pago não aprovou o pagamento.'} Você pode tentar de novo com outro
                cartão ou com Pix.
              </p>
            </div>
          )}
          {payment && (payment.status === 'EXPIRED' || payment.status === 'CANCELLED') && (
            <p className="note">O pagamento anterior não foi concluído. Siga para um novo pagamento abaixo.</p>
          )}
          {legacyPending && (
            <p className="note">
              Há uma cobrança gerada antes aguardando compensação. Se você já pagou, aguarde: a confirmação é
              automática e não é preciso pagar de novo.
            </p>
          )}

          <fieldset className="form-section">
            <legend className="form-section__title">Quem está pagando</legend>
            <div className="grid-2">
              <Field label="Nome" htmlFor="payer-first">
                <input id="payer-first" className="input" autoComplete="given-name" value={payer.firstName}
                  onChange={(e) => set('firstName')(e.target.value)} />
              </Field>
              <Field label="Sobrenome" htmlFor="payer-last">
                <input id="payer-last" className="input" autoComplete="family-name" value={payer.lastName}
                  onChange={(e) => set('lastName')(e.target.value)} />
              </Field>
              <Field label="E-mail" htmlFor="payer-email">
                <input id="payer-email" className="input" type="email" autoComplete="email" value={payer.email}
                  onChange={(e) => set('email')(e.target.value)} />
              </Field>
              <Field label="CPF" htmlFor="payer-cpf">
                <input id="payer-cpf" className="input" inputMode="numeric" placeholder="000.000.000-00"
                  value={payer.cpf} onChange={(e) => set('cpf')(maskCpf(e.target.value))} />
              </Field>
              <Field label="Celular com DDD" htmlFor="payer-phone">
                <input id="payer-phone" className="input" type="tel" autoComplete="tel-national"
                  placeholder="(81) 99999-9999" value={payer.phone}
                  onChange={(e) => set('phone')(maskPhone(e.target.value))} />
              </Field>
            </div>
          </fieldset>

          <p className="note">
            Na próxima etapa você escolhe Pix ou cartão (crédito ou débito) e informa os dados de pagamento no
            ambiente seguro do Mercado Pago.
          </p>

          {formError && <p className="form-error" role="alert">{formError}</p>}
          <InlineError error={create.error} />

          <div className="form-actions">
            <button type="submit" className="btn btn--primary btn--large" disabled={create.isPending}>
              {create.isPending ? 'Abrindo o Mercado Pago…' : 'Seguir para pagamento'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

function CheckoutInProgress({
  payment,
  returned,
  redirecting,
  onChangePayer,
}: {
  payment: Payment;
  returned: boolean;
  redirecting: boolean;
  onChangePayer: () => void;
}) {
  // CHECKOUT_PRO: o pagador ainda não escolheu o meio no Mercado Pago
  const started = payment.method !== 'CHECKOUT_PRO';
  return (
    <section className="pending" aria-live="polite">
      <div className="pending__head">
        <h2 className="section-title">Pagamento no Mercado Pago</h2>
        <PaymentBadge status={payment.status} />
      </div>

      <p className="note">
        {redirecting
          ? 'Abrindo o Mercado Pago…'
          : returned
            ? 'Você voltou do Mercado Pago. Assim que o pagamento for confirmado, esta página atualiza sozinha.'
            : 'Conclua o pagamento na página do Mercado Pago. Esta página atualiza sozinha quando ele for confirmado.'}
      </p>

      {started && (
        <p className="note">
          {methodLabel[payment.method]}
          {payment.cardLastDigits ? ` final ${payment.cardLastDigits}` : ''}
          {payment.installments && payment.installments > 1 ? ` em ${payment.installments}x` : ''}
          {payment.statusDetail ? `: ${payment.statusDetail}` : ''}
        </p>
      )}
      {payment.expiresAt && <p className="note">Válido até {dateTime(payment.expiresAt)}.</p>}

      <div className="row-actions">
        <a className="btn btn--primary" href={payment.checkoutUrl ?? undefined}>Continuar no Mercado Pago</a>
        {!started && (
          <button type="button" className="btn btn--ghost" onClick={onChangePayer}>
            Alterar dados de quem paga
          </button>
        )}
      </div>
    </section>
  );
}
