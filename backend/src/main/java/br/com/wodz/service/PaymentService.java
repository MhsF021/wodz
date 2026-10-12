package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.common.TokenCipher;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.*;
import br.com.wodz.dto.PaymentDtos.*;
import br.com.wodz.payment.*;
import br.com.wodz.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Cartão hospedado; Pix criado no servidor com os dados mínimos do pagador. */
@Service
public class PaymentService {
    private final TeamRepository teams;
    private final PaymentRepository payments;
    private final CheckoutSessionRepository checkouts;
    private final PayoutAccountRepository payoutAccounts;
    private final PaymentGateway gateway;
    private final AppProperties props;
    private final PayoutTokenService payoutTokens;
    private final TokenCipher cipher;
    private final PixDraftService pixDrafts;
    @PersistenceContext private EntityManager entityManager;

    public PaymentService(TeamRepository teams, PaymentRepository payments, CheckoutSessionRepository checkouts,
                          PayoutAccountRepository payoutAccounts, PaymentGateway gateway,
                          AppProperties props, PayoutTokenService payoutTokens, TokenCipher cipher, PixDraftService pixDrafts) {
        this.teams = teams; this.payments = payments; this.checkouts = checkouts;
        this.payoutAccounts = payoutAccounts; this.gateway = gateway; this.props = props; this.payoutTokens = payoutTokens;
        this.cipher = cipher;
        this.pixDrafts = pixDrafts;
    }
    public PublicConfig publicConfig() {
        return new PublicConfig(gateway.providerName(), props.google() == null ? null : Codes.blankToNull(props.google().clientId()),
                props.payment().mercadopago() != null && props.payment().mercadopago().testMode());
    }
    @Transactional(readOnly = true)
    public RegistrationSummary summary(String code) { return summaryOf(findTeam(code)); }

    @Transactional(noRollbackFor = ApiException.class)
    public CheckoutDto createCheckout(String code) {
        return createCheckout(code, new CheckoutRequest(CheckoutMethod.CARD, null, true));
    }

    // Preserva o rascunho e sua chave de idempotência mesmo em timeout/erro do provedor.
    @Transactional(noRollbackFor = ApiException.class)
    public CheckoutDto createCheckout(String code, CheckoutRequest request) {
        if (request == null || request.method() == null) throw ApiException.badRequest("Escolha a forma de pagamento.");
        if (!request.termsAccepted()) throw ApiException.badRequest("Leia e aceite os termos para continuar.");
        String cpf = request.method() == CheckoutMethod.PIX ? validCpf(request.payerCpf()) : null;
        Team team = lockTeam(findTeam(code));
        if (team.getStatus() == TeamStatus.CONFIRMED) throw ApiException.conflict("Esta inscrição já está confirmada");
        if (team.getStatus() == TeamStatus.CANCELLED) throw ApiException.unprocessable("Esta inscrição foi cancelada");
        Championship championship = team.getCategory().getChampionship();
        Instant now = Instant.now();
        if (championship.hasStarted(now)) throw ApiException.unprocessable("O campeonato já começou e não aceita pagamentos de inscrição.");
        if (!gateway.supportsRemoteFetch()) throw ApiException.unprocessable("O pagamento pelo Mercado Pago ainda não está disponível.");
        BigDecimal amount = team.getCategory().getPrice();
        if (amount.signum() <= 0) throw ApiException.unprocessable("Esta inscrição não exige pagamento.");
        PayoutAccount account = payoutAccounts.findFirstByUserIdAndStatus(championship.getOwner().getId(), PayoutAccountStatus.ACTIVE)
                .orElseThrow(() -> ApiException.unprocessable("O organizador ainda não conectou a conta Mercado Pago."));
        SellerAccount seller = payoutTokens.seller(account.getId(), true);
        refreshAccount(account);
        CheckoutSession previous = checkouts.findFirstByTeamIdOrderByIdDesc(team.getId()).orElse(null);
        if (previous != null) {
            PayoutAccount original = payoutAccounts.findById(previous.getPayoutAccountId())
                    .orElseThrow(() -> ApiException.notFound("Conta de recebimento não encontrada"));
            SellerAccount originalSeller = payoutTokens.seller(original.getId());
            refreshAccount(original);
            GatewayPayment pendingPix = null;
            for (GatewayPayment remote : gateway.search(previous.getExternalReference(), originalSeller)) {
                verify(remote, previous.getAmount(), previous.getExternalReference(), original);
                if (remote.status() == PaymentStatus.APPROVED)
                    throw ApiException.conflict("O Mercado Pago já confirmou este pagamento. Aguarde a atualização da inscrição.");
                if (previous.getMethod() == CheckoutMethod.PIX && previous.getProviderPaymentId() == null
                        && remote.status() == PaymentStatus.PENDING) {
                    if (remote.method() != PaymentMethod.PIX || (pendingPix != null
                            && !pendingPix.providerPaymentId().equals(remote.providerPaymentId()))) throw invalidPayment();
                    pendingPix = remote;
                }
            }
            if (previous.getExpiresAt().isAfter(now) && previous.getMethod() != request.method())
                throw ApiException.conflict("Já existe um checkout válido para esta inscrição. Conclua-o ou aguarde o vencimento antes de trocar a forma de pagamento.");
            if (previous.getMethod() == CheckoutMethod.PIX && previous.getExpiresAt().isAfter(now)) {
                String paymentId = previous.getProviderPaymentId();
                if (paymentId == null && pendingPix == null) return createPix(previous, originalSeller, original);
                if (paymentId == null) paymentId = pendingPix.providerPaymentId();
                GatewayPix pix = gateway.fetchPix(paymentId, originalSeller);
                validatePix(previous, pix, original);
                if (!paymentId.equals(pix.payment().providerPaymentId())) throw invalidPayment();
                if (previous.getProviderPaymentId() == null) {
                    previous.setProviderPaymentId(paymentId);
                    checkouts.saveAndFlush(previous);
                }
                record(previous, pix.payment());
                if (pix.payment().status() == PaymentStatus.PENDING) return pixDto(previous, pix);
                if (pix.payment().status() == PaymentStatus.APPROVED || pix.payment().status() == PaymentStatus.REFUNDED)
                    throw ApiException.conflict("Este pagamento já foi concluído. Atualize a página para consultar a inscrição.");
            }
        }
        // Cliques repetidos retomam o mesmo checkout enquanto ele está válido.
        if (request.method() == CheckoutMethod.CARD && previous != null && MercadoPagoGateway.safeCheckoutUrl(previous.getCheckoutUrl()) && previous.getExpiresAt().isAfter(now)
                && previous.getPayoutAccountId().equals(account.getId()) && previous.getAmount().compareTo(amount) == 0) {
            GatewayCheckout verified = gateway.resumeCheckout(previous.getProviderPreferenceId(), checkoutCommand(previous, seller, account));
            if (verified != null) {
                previous.setCheckoutUrl(verified.url());
                if (previous.getTermsAcceptedAt() == null) {
                    previous.setTermsAcceptedAt(now); previous.setTermsVersion("checkout-v1");
                }
                checkouts.saveAndFlush(previous);
                return checkoutDto(previous);
            }
        }
        CheckoutSession session = new CheckoutSession();
        session.setTeam(team); session.setPayoutAccountId(account.getId()); session.setAmount(amount);
        session.setApplicationFee(applicationFee(amount)); session.setExternalReference(UUID.randomUUID().toString());
        session.setMethod(request.method()); session.setTermsAcceptedAt(now); session.setTermsVersion("checkout-v1");
        java.time.Duration duration = props.payment().checkoutExpiration();
        if (request.method() == CheckoutMethod.PIX && duration.compareTo(java.time.Duration.ofMinutes(31)) < 0)
            duration = java.time.Duration.ofMinutes(31); // MP exige no mínimo 30 min na chegada da requisição.
        Instant expiry = now.plus(duration);
        session.setExpiresAt(expiry.isBefore(championship.startsAt()) ? expiry : championship.startsAt());
        if (request.method() == CheckoutMethod.PIX) {
            if (session.getExpiresAt().isBefore(now.plusSeconds(31 * 60)))
                throw ApiException.unprocessable("O prazo para gerar um Pix terminou. Escolha cartão para continuar.");
            if (duration.compareTo(java.time.Duration.ofDays(30)) > 0)
                throw ApiException.unprocessable("A validade do Pix deve ser de até 30 dias. Fale com a organização.");
            String email = team.getCaptain().getEmail();
            if (email == null || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                throw ApiException.unprocessable("Atualize o e-mail do seu cadastro antes de pagar com Pix.");
            String notification = props.payment().mercadopago().notificationUrl();
            session.setPixPayer(cipher.encrypt(cpf + "\n" + email + "\n" + (notification == null ? "" : notification)));
            session.setPixDescription(description(team));
            // PostgreSQL usa FOR NO KEY UPDATE no lock da equipe: a FK do rascunho pode ser inserida na transação interna.
            session = pixDrafts.save(session);
            return createPix(session, seller, account);
        }
        checkouts.saveAndFlush(session);
        GatewayCheckout remote = gateway.createCheckout(checkoutCommand(session, seller, account));
        session.setProviderPreferenceId(remote.preferenceId()); session.setCheckoutUrl(remote.url());
        checkouts.saveAndFlush(session);
        return checkoutDto(session);
    }

    @Transactional
    public CheckoutDto checkoutDetails(String code, Long sessionId) {
        Team team = lockTeam(findTeam(code));
        CheckoutSession session = checkouts.findByIdAndTeamId(sessionId, team.getId())
                .orElseThrow(() -> ApiException.notFound("Checkout não encontrado para esta inscrição"));
        if (session.getMethod() != CheckoutMethod.PIX || session.getProviderPaymentId() == null) return checkoutDto(session);
        SellerAccount seller = payoutTokens.seller(session.getPayoutAccountId());
        PayoutAccount account = payoutAccounts.findById(session.getPayoutAccountId()).orElseThrow();
        refreshAccount(account);
        GatewayPix pix = gateway.fetchPix(session.getProviderPaymentId(), seller);
        validatePix(session, pix, account); record(session, pix.payment());
        return pixDto(session, pix);
    }

    private CheckoutDto createPix(CheckoutSession session, SellerAccount seller, PayoutAccount account) {
        // Repetir a chave não permite mudar o prazo original após um timeout.
        // Primeiro buscamos a cobrança existente; uma nova emissão exige 30 min + margem de rede.
        if (!session.getExpiresAt().isAfter(Instant.now().plusSeconds(30 * 60 + 5)))
            throw ApiException.unprocessable("A tentativa Pix ainda está em verificação e seu prazo não permite repetir a emissão. Aguarde o vencimento antes de tentar novamente.");
        String[] payer = cipher.decrypt(session.getPixPayer()).split("\n", 3);
        GatewayPix pix = gateway.createPix(new PixCommand(checkoutCommand(session, seller, account), payer[1], payer[0], payer[2]));
        validatePix(session, pix, account);
        session.setProviderPaymentId(pix.payment().providerPaymentId());
        checkouts.saveAndFlush(session); record(session, pix.payment());
        return pixDto(session, pix);
    }

    private static void validatePix(CheckoutSession session, GatewayPix pix, PayoutAccount account) {
        if (pix == null || pix.payment() == null || pix.payment().method() != PaymentMethod.PIX
                || pix.expiresAt() == null || pix.expiresAt().isAfter(session.getExpiresAt().plusSeconds(1))
                || (session.getProviderPaymentId() != null && !session.getProviderPaymentId().equals(pix.payment().providerPaymentId())))
            throw invalidPayment();
        verify(pix.payment(), session.getAmount(), session.getExternalReference(), account);
    }

    private static CheckoutDto pixDto(CheckoutSession session, GatewayPix pix) {
        boolean visible = pix.payment().status() == PaymentStatus.PENDING && pix.expiresAt().isAfter(Instant.now());
        return new CheckoutDto(session.getId(), null, pix.expiresAt(), CheckoutMethod.PIX,
                visible ? pix.qrCode() : null, visible ? pix.qrCodeBase64() : null);
    }

    static String validCpf(String value) {
        if (value == null || !value.matches("(?:[0-9]{11}|[0-9]{3}\\.[0-9]{3}\\.[0-9]{3}-[0-9]{2})"))
            throw ApiException.badRequest("Informe um CPF válido do responsável pagador.");
        String digits = Codes.digits(value);
        if (digits.chars().distinct().count() == 1) throw ApiException.badRequest("Informe um CPF válido do responsável pagador.");
        for (int size = 9; size <= 10; size++) {
            int sum = 0;
            for (int i = 0; i < size; i++) sum += (digits.charAt(i) - '0') * (size + 1 - i);
            int check = 11 - sum % 11;
            if ((check > 9 ? 0 : check) != digits.charAt(size) - '0')
                throw ApiException.badRequest("Informe um CPF válido do responsável pagador.");
        }
        return digits;
    }

    @Transactional
    public RegistrationSummary checkoutStatus(String code, Long sessionId, String returnedPaymentId) {
        Team team = lockTeam(findTeam(code));
        CheckoutSession session = checkouts.findByIdAndTeamId(sessionId, team.getId())
                .orElseThrow(() -> ApiException.notFound("Checkout não encontrado para esta inscrição"));
        if (gateway.supportsRemoteFetch()) {
            SellerAccount seller = payoutTokens.seller(session.getPayoutAccountId());
            if (returnedPaymentId != null) {
                if (!returnedPaymentId.matches("[0-9]{1,30}")) throw ApiException.badRequest("Pagamento inválido");
                record(session, gateway.fetch(returnedPaymentId, seller));
            }
            for (GatewayPayment remote : gateway.search(session.getExternalReference(), seller)) record(session, remote);
            payments.flush();
            if (entityManager != null) entityManager.refresh(team); // a trigger confirma a equipe
        }
        return summaryOf(team);
    }

    @Transactional
    public PaymentDto status(String code, Long paymentId) {
        Team team = lockTeam(findTeam(code));
        Payment p = payments.findByIdAndTeamId(paymentId, team.getId()).orElseThrow(() -> ApiException.notFound("Pagamento não encontrado"));
        syncExisting(p);
        return toDto(p);
    }

    @Transactional
    public void syncFromProvider(String id) { syncFromProvider(id, null); }

    /** O user_id do webhook é apenas uma dica para localizar credenciais; nunca autoriza a aprovação. */
    @Transactional
    public void syncFromProvider(String id, String sellerHint) {
        if (!gateway.supportsRemoteFetch() || id == null || !id.matches("[0-9]{1,30}")) return;
        Payment existing = payments.findByProviderAndProviderPaymentId(gateway.providerName(), id).orElse(null);
        if (existing != null) {
            lockTeam(existing.getTeam()); syncExisting(existing); return;
        }
        if (sellerHint == null || !sellerHint.matches("[0-9]{1,40}")) return;
        ApiException unavailable = null;
        for (PayoutAccount candidate : payoutAccounts.findByProviderAndProviderUserId(gateway.providerName(), sellerHint)) {
            if (candidate.getAccessToken() == null) continue;
            GatewayPayment remote;
            try { remote = gateway.fetch(id, payoutTokens.seller(candidate.getId())); }
            catch (ApiException e) { unavailable = e; continue; }
            CheckoutSession session = checkouts.findByExternalReference(remote.externalReference()).orElse(null);
            if (session == null) return;
            lockTeam(session.getTeam());
            record(session, remote); payments.flush(); return;
        }
        if (unavailable != null) throw unavailable;
    }

    @Transactional
    public void reconcileCheckout(Long sessionId) {
        CheckoutSession session = checkouts.findById(sessionId).orElse(null);
        if (session == null || !gateway.supportsRemoteFetch()) return;
        lockTeam(session.getTeam());
        SellerAccount seller = payoutTokens.seller(session.getPayoutAccountId());
        for (GatewayPayment remote : gateway.search(session.getExternalReference(), seller)) record(session, remote);
        payments.flush();
    }

    private void record(CheckoutSession session, GatewayPayment remote) {
        PayoutAccount original = payoutAccounts.findById(session.getPayoutAccountId())
                .orElseThrow(() -> ApiException.unprocessable("Conta de recebimento não encontrada"));
        // Um webhook pode ter localizado o pagamento com outro vínculo do mesmo vendedor.
        // Também valida o vínculo original legado antes de comparar seu modo financeiro.
        if (original.getTestAccount() == null) payoutTokens.seller(original.getId());
        refreshAccount(original);
        verify(remote, session.getAmount(), session.getExternalReference(), original);
        Payment p = payments.findByProviderAndProviderPaymentId(gateway.providerName(), remote.providerPaymentId()).orElse(null);
        if (p != null && (!p.getTeam().getId().equals(session.getTeam().getId())
                || !session.getId().equals(p.getCheckoutSessionId()))) throw invalidPayment();
        if (p == null) {
            p = new Payment(); p.setTeam(session.getTeam()); p.setProvider(gateway.providerName());
            p.setAmount(session.getAmount()); p.setPayoutAccountId(session.getPayoutAccountId());
            p.setCheckoutSessionId(session.getId()); p.setApplicationFee(session.getApplicationFee());
            if (session.getMethod() == CheckoutMethod.PIX) p.setExpiresAt(session.getExpiresAt());
        }
        apply(p, remote);
        payments.saveAndFlush(p);
    }

    private void syncExisting(Payment p) {
        if (!gateway.supportsRemoteFetch() || p.getProviderPaymentId() == null) return;
        if (p.getPayoutAccountId() == null) throw ApiException.unprocessable("A cobrança não tem conta de recebimento associada.");
        PayoutAccount account = payoutAccounts.findById(p.getPayoutAccountId()).orElseThrow(() -> ApiException.notFound("Conta de recebimento não encontrada"));
        SellerAccount seller = payoutTokens.seller(account.getId());
        refreshAccount(account);
        GatewayPayment remote = gateway.fetch(p.getProviderPaymentId(), seller);
        if (p.getCheckoutSessionId() != null) {
            record(checkouts.findById(p.getCheckoutSessionId()).orElseThrow(() -> ApiException.notFound("Checkout não encontrado")), remote);
        } else {
            // Compatibilidade de leitura e conciliação das cobranças anteriores à migração.
            verify(remote, p.getAmount(), "team:" + p.getTeam().getId() + ":payment:" + p.getId(), account);
            apply(p, remote); payments.saveAndFlush(p);
        }
    }

    static void verify(GatewayPayment p, BigDecimal amount, String reference, PayoutAccount account) {
        if (p == null || p.providerPaymentId() == null || p.amount() == null || p.amount().compareTo(amount) != 0
                || !"BRL".equals(p.currency()) || !reference.equals(p.externalReference())
                || !account.getProviderUserId().equals(p.collectorId()) || p.liveMode() == null
                || p.liveMode() != account.isLiveMode()) throw invalidPayment();
    }
    private static ApiException invalidPayment() {
        return ApiException.unprocessable("O Mercado Pago não confirmou os dados desta inscrição. Fale com a organização.");
    }
    private Team findTeam(String code) {
        return teams.findByRegistrationCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("Inscrição não encontrada. Confira o link."));
    }
    private Team lockTeam(Team t) { return teams.lockById(t.getId()).orElseThrow(() -> ApiException.notFound("Inscrição não encontrada")); }
    private CheckoutCommand checkoutCommand(CheckoutSession session, SellerAccount seller, PayoutAccount account) {
        Team team = session.getTeam();
        String returnUrl = session.getMethod() == CheckoutMethod.CARD
                ? returnBase() + "/pagamento/" + team.getRegistrationCode() + "?checkout=" + session.getId() : null;
        String description = session.getPixDescription() == null ? description(team) : session.getPixDescription();
        return new CheckoutCommand(session.getId(), session.getExternalReference(), session.getAmount(), description,
                returnUrl, session.getExpiresAt(), seller, session.getApplicationFee(), Boolean.TRUE.equals(account.getTestAccount()));
    }
    private static String description(Team team) {
        return "Inscrição " + team.getName() + " - " + team.getCategory().getChampionship().getName()
                + " (" + team.getCategory().getName() + ")";
    }
    private void refreshAccount(PayoutAccount account) {
        // O refresh OAuth legado persiste em REQUIRES_NEW; atualiza modo e aplicação no contexto externo.
        if ((account.getTestAccount() == null || account.getApplicationId() == null) && entityManager != null)
            entityManager.refresh(account);
    }
    private String returnBase() {
        try {
            java.net.URI u = java.net.URI.create(props.frontendUrl());
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null
                    || !(u.getPath().isEmpty() || u.getPath().equals("/")))
                throw new IllegalArgumentException();
            return props.frontendUrl().replaceAll("/+$", "");
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.unprocessable("O retorno do pagamento precisa de um endereço público HTTPS. Fale com a organização.");
        }
    }
    private RegistrationSummary summaryOf(Team team) {
        Category c = team.getCategory(); Championship championship = c.getChampionship();
        CheckoutSession latest = checkouts.findFirstByTeamIdOrderByIdDesc(team.getId()).orElse(null);
        PaymentDto last = (latest == null ? payments.findFirstByTeamIdOrderByIdDesc(team.getId())
                : payments.findFirstByCheckoutSessionIdOrderByIdDesc(latest.getId())).map(PaymentService::toDto).orElse(null);
        Long checkoutId = latest == null ? null : latest.getId();
        BigDecimal amount = latest == null ? (last == null ? c.getPrice() : last.amount()) : latest.getAmount();
        return new RegistrationSummary(team.getRegistrationCode(), team.getId(), team.getName(), championship.getName(),
                championship.getSlug(), c.getName(), amount, team.getStatus(), championship.hasStarted(Instant.now()), last, checkoutId);
    }
    private BigDecimal applicationFee(BigDecimal amount) {
        BigDecimal percent = props.payment().mercadopago().platformFeePercent();
        return percent == null || percent.signum() == 0 ? null : amount.multiply(percent).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }
    private static CheckoutDto checkoutDto(CheckoutSession c) {
        return new CheckoutDto(c.getId(), c.getCheckoutUrl(), c.getExpiresAt(), c.getMethod(), null, null);
    }
    private static void apply(Payment p, GatewayPayment r) {
        // Webhooks atrasados não desfazem uma aprovação ou um estorno já observado.
        if (p.getStatus() == PaymentStatus.REFUNDED || (p.getStatus() == PaymentStatus.APPROVED
                && r.status() != PaymentStatus.APPROVED && r.status() != PaymentStatus.REFUNDED)) return;
        p.setProviderPaymentId(r.providerPaymentId()); p.setStatus(r.status()); p.setStatusDetail(r.statusDetail());
        p.setMethod(r.method()); p.setInstallments(r.installments());
        if (r.status() == PaymentStatus.APPROVED && p.getPaidAt() == null)
            p.setPaidAt(r.paidAt() == null ? Instant.now() : r.paidAt());
    }
    static PaymentDto toDto(Payment p) {
        return new PaymentDto(p.getId(), p.getMethod(), p.getStatus(), p.getStatusDetail(), p.getAmount(), p.getPaidAt(), p.getCreatedAt());
    }
}
