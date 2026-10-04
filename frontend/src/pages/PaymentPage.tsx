import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CreditCard, FileText, QrCode } from 'lucide-react';
import { paymentApi, publicApi } from '../api/endpoints';
import type { AddressInput, CardInput, Payment, PaymentMethod, PayerInput } from '../api/types';
import { UFS, dateTime, isValidCpf, maskCpf, methodLabel, money, onlyDigits } from '../lib/format';
import { useAuth } from '../auth/AuthContext';
import { Field } from '../components/Field';
import { CopyButton } from '../components/CopyButton';
import { PaymentBadge } from '../components/StatusBadge';
import { MercadoPagoCardBrick } from '../components/MercadoPagoCardBrick';
import { ErrorState, InlineError, Loading } from '../components/States';

const METHODS: { id: PaymentMethod; icon: typeof QrCode }[] = [
  { id: 'PIX', icon: QrCode },
  { id: 'CREDIT_CARD', icon: CreditCard },
  { id: 'BOLETO', icon: FileText },
];

const EMPTY_ADDRESS: AddressInput = { zipCode: '', street: '', number: '', neighborhood: '', city: '', state: '' };

export default function PaymentPage() {
  const { code = '' } = useParams();
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const summary = useQuery({ queryKey: ['registration', code], queryFn: () => paymentApi.summary(code) });
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity });

  const [method, setMethod] = useState<PaymentMethod>('PIX');
  const [payer, setPayer] = useState<PayerInput>(() => {
    const [first = '', ...rest] = (user?.name ?? '').split(' ');
    return { firstName: first, lastName: rest.join(' '), email: user?.email ?? '', cpf: '' };
  });
  const [address, setAddress] = useState<AddressInput>(EMPTY_ADDRESS);
  const [payment, setPayment] = useState<Payment | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [resumeChecked, setResumeChecked] = useState(false);

  // Retoma uma única vez um Pix/boleto já gerado (ex.: atleta voltou pelo link)
  useEffect(() => {
    if (resumeChecked || !summary.data) return;
    setResumeChecked(true);
    const last = summary.data.lastPayment;
    if (last && last.status === 'PENDING') {
      setPayment(last);
      setMethod(last.method);
    }
  }, [summary.data, resumeChecked]);

  // Polling enquanto aguarda Pix/boleto
  const poll = useQuery({
    queryKey: ['payment', code, payment?.id],
    queryFn: () => paymentApi.status(code, payment!.id),
    enabled: payment?.status === 'PENDING',
    refetchInterval: 5000,
  });
  useEffect(() => {
    if (poll.data && payment && poll.data.id === payment.id && poll.data.status !== payment.status) {
      setPayment(poll.data);
    }
  }, [poll.data, payment]);

  useEffect(() => {
    if (payment?.status === 'APPROVED') {
      queryClient.invalidateQueries({ queryKey: ['registration', code] });
    }
  }, [payment?.status, code, queryClient]);

  const create = useMutation({
    mutationFn: (card?: CardInput) =>
      paymentApi.create(code, {
        method,
        payer: {
          ...payer,
          cpf: onlyDigits(payer.cpf),
          address: method === 'BOLETO' ? { ...address, zipCode: onlyDigits(address.zipCode) } : undefined,
        },
        card,
      }),
    onSuccess: setPayment,
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
  const approved = s.teamStatus === 'CONFIRMED' || payment?.status === 'APPROVED';

  function payerValid(): boolean {
    if (!payer.firstName.trim() || !payer.lastName.trim()) return fail('Informe nome e sobrenome do pagador');
    if (!/^\S+@\S+\.\S+$/.test(payer.email)) return fail('Informe um e-mail válido');
    if (!isValidCpf(payer.cpf)) return fail('Informe um CPF válido');
    if (method === 'BOLETO' && Object.values(address).some((v) => !v.trim())) {
      return fail('Preencha o endereço completo para emitir o boleto');
    }
    setFormError(null);
    return true;
  }
  function fail(message: string) {
    setFormError(message);
    return false;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (payerValid()) create.mutate(undefined);
  }

  const pendingOrResult = payment && payment.status !== 'CANCELLED' && payment.status !== 'EXPIRED';

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
      ) : pendingOrResult && payment.status !== 'REJECTED' ? (
        <PendingPayment
          payment={payment}
          canSimulate={Boolean(config.data?.paymentSimulation)}
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
              <h2 className="result__title">Pagamento recusado</h2>
              <p>{payment.statusDetail ?? 'O emissor recusou a transação.'} Tente outro cartão ou outra forma de pagamento.</p>
            </div>
          )}
          {payment && (payment.status === 'EXPIRED' || payment.status === 'CANCELLED') && (
            <p className="note">A cobrança anterior expirou. Gere uma nova abaixo.</p>
          )}

          <fieldset className="form-section">
            <legend className="form-section__title">Quem está pagando</legend>
            <div className="grid-2">
              <Field label="Nome" htmlFor="payer-first">
                <input id="payer-first" className="input" autoComplete="given-name" value={payer.firstName}
                  onChange={(e) => setPayer({ ...payer, firstName: e.target.value })} />
              </Field>
              <Field label="Sobrenome" htmlFor="payer-last">
                <input id="payer-last" className="input" autoComplete="family-name" value={payer.lastName}
                  onChange={(e) => setPayer({ ...payer, lastName: e.target.value })} />
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
              {METHODS.map(({ id, icon: Icon }) => (
                <label key={id} className="method">
                  <input type="radio" name="method" checked={method === id} onChange={() => setMethod(id)} />
                  <span className="method__body">
                    <Icon size={20} aria-hidden />
                    {methodLabel[id]}
                  </span>
                </label>
              ))}
            </div>

            {method === 'PIX' && (
              <p className="note">Geramos um QR Code com validade de 30 minutos. A confirmação é automática.</p>
            )}

            {method === 'BOLETO' && (
              <>
                <p className="note">O boleto vence em 3 dias e pode levar até 3 dias úteis para compensar.</p>
                <div className="grid-3">
                  <Field label="CEP" htmlFor="addr-zip">
                    <input id="addr-zip" className="input" inputMode="numeric" autoComplete="postal-code"
                      value={address.zipCode} onChange={(e) => setAddress({ ...address, zipCode: e.target.value })} />
                  </Field>
                  <Field label="Rua" htmlFor="addr-street" className="span-2">
                    <input id="addr-street" className="input" autoComplete="address-line1" value={address.street}
                      onChange={(e) => setAddress({ ...address, street: e.target.value })} />
                  </Field>
                  <Field label="Número" htmlFor="addr-number">
                    <input id="addr-number" className="input" value={address.number}
                      onChange={(e) => setAddress({ ...address, number: e.target.value })} />
                  </Field>
                  <Field label="Bairro" htmlFor="addr-hood">
                    <input id="addr-hood" className="input" value={address.neighborhood}
                      onChange={(e) => setAddress({ ...address, neighborhood: e.target.value })} />
                  </Field>
                  <Field label="Cidade" htmlFor="addr-city">
                    <input id="addr-city" className="input" autoComplete="address-level2" value={address.city}
                      onChange={(e) => setAddress({ ...address, city: e.target.value })} />
                  </Field>
                  <Field label="UF" htmlFor="addr-uf">
                    <select id="addr-uf" className="input" value={address.state}
                      onChange={(e) => setAddress({ ...address, state: e.target.value })}>
                      <option value="">Selecione</option>
                      {UFS.map((u) => <option key={u} value={u}>{u}</option>)}
                    </select>
                  </Field>
                </div>
              </>
            )}

            {method === 'CREDIT_CARD' && (
              config.data?.mercadoPagoPublicKey ? (
                <MercadoPagoCardBrick
                  publicKey={config.data.mercadoPagoPublicKey}
                  amount={s.amount}
                  email={payer.email}
                  onToken={(card) => (payerValid() ? create.mutateAsync(card) : Promise.reject(new Error(formError ?? '')))}
                />
              ) : (
                <SandboxCardForm
                  busy={create.isPending}
                  onToken={(card) => {
                    if (payerValid()) create.mutate(card);
                  }}
                />
              )
            )}
          </fieldset>

          {formError && <p className="form-error" role="alert">{formError}</p>}
          <InlineError error={create.error} />

          {method !== 'CREDIT_CARD' && (
            <div className="form-actions">
              <button type="submit" className="btn btn--primary btn--large" disabled={create.isPending}>
                {create.isPending ? 'Gerando…' : method === 'PIX' ? 'Gerar Pix' : 'Gerar boleto'}
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
      <div className="pending__head">
        <h2 className="section-title">{payment.method === 'PIX' ? 'Pague com Pix' : 'Pague o boleto'}</h2>
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

      {payment.method === 'BOLETO' && (
        <>
          <Field label="Linha digitável" htmlFor="boleto-code">
            <input id="boleto-code" className="input input--code" readOnly value={payment.boletoBarcode ?? ''} />
          </Field>
          <div className="row-actions">
            {payment.boletoBarcode && <CopyButton value={payment.boletoBarcode} label="Copiar código" />}
            {payment.boletoUrl && (
              <a className="btn btn--secondary btn--small" href={payment.boletoUrl} target="_blank" rel="noreferrer">
                Abrir boleto em PDF
              </a>
            )}
          </div>
        </>
      )}

      {payment.expiresAt && <p className="note">Válido até {dateTime(payment.expiresAt)}.</p>}
      <p className="note">Esta página atualiza sozinha quando o pagamento for confirmado.</p>

      <div className="row-actions">
        {canSimulate && (
          <button type="button" className="btn btn--primary" onClick={onSimulate} disabled={simulating}>
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

/** Formulário de teste usado quando não há chave do Mercado Pago (provedor sandbox). */
function SandboxCardForm({ busy, onToken }: { busy: boolean; onToken: (card: CardInput) => void }) {
  const [card, setCard] = useState({ number: '', holder: '', expiry: '', cvv: '', installments: '1' });

  function pay() {
    const digits = onlyDigits(card.number);
    onToken({
      token: digits.endsWith('0002') ? 'sandbox-rejected' : 'sandbox-approved',
      paymentMethodId: 'visa',
      installments: Number(card.installments),
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
        <Field label="Nome impresso no cartão" htmlFor="cc-holder">
          <input id="cc-holder" className="input" autoComplete="cc-name" value={card.holder}
            onChange={(e) => setCard({ ...card, holder: e.target.value })} />
        </Field>
        <Field label="Validade (MM/AA)" htmlFor="cc-exp">
          <input id="cc-exp" className="input" autoComplete="cc-exp" placeholder="12/29" value={card.expiry}
            onChange={(e) => setCard({ ...card, expiry: e.target.value })} />
        </Field>
        <Field label="CVV" htmlFor="cc-cvv">
          <input id="cc-cvv" className="input" inputMode="numeric" autoComplete="cc-csc" maxLength={4} value={card.cvv}
            onChange={(e) => setCard({ ...card, cvv: onlyDigits(e.target.value) })} />
        </Field>
        <Field label="Parcelas" htmlFor="cc-inst">
          <select id="cc-inst" className="input" value={card.installments}
            onChange={(e) => setCard({ ...card, installments: e.target.value })}>
            {[1, 2, 3, 4, 5, 6].map((n) => <option key={n} value={n}>{n}x</option>)}
          </select>
        </Field>
      </div>
      <div className="form-actions">
        <button type="button" className="btn btn--primary btn--large" disabled={!ready || busy} onClick={pay}>
          {busy ? 'Processando…' : 'Pagar com cartão'}
        </button>
      </div>
    </div>
  );
}
