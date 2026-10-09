package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.Payment;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.PayoutAccount;
import br.com.wodz.domain.PayoutAccountStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.dto.PaymentDtos.CreatePaymentRequest;
import br.com.wodz.dto.PaymentDtos.Payer;
import br.com.wodz.dto.PaymentDtos.PaymentDto;
import br.com.wodz.dto.PaymentDtos.PublicConfig;
import br.com.wodz.dto.PaymentDtos.RegistrationSummary;
import br.com.wodz.dto.PaymentDtos.ThreeDsChallenge;
import br.com.wodz.payment.GatewayPayment;
import br.com.wodz.payment.PaymentCommand;
import br.com.wodz.payment.PaymentGateway;
import br.com.wodz.payment.SellerAccount;
import br.com.wodz.repository.PaymentRepository;
import br.com.wodz.repository.PayoutAccountRepository;
import br.com.wodz.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;

/**
 * Pagamento da inscrição pelo link público /pagamento/{código}: Pix ou cartão
 * (crédito ou débito). Aprovação (cartão na hora ou após o 3DS, Pix via webhook)
 * dispara a trigger que confirma a equipe no banco.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TeamRepository teams;
    private final PaymentRepository payments;
    private final PayoutAccountRepository payoutAccounts;
    private final PaymentGateway gateway;
    private final AppProperties props;
    private final PayoutTokenService payoutTokens;

    public PaymentService(TeamRepository teams, PaymentRepository payments, PayoutAccountRepository payoutAccounts,
                          PaymentGateway gateway, AppProperties props, PayoutTokenService payoutTokens) {
        this.teams = teams;
        this.payments = payments;
        this.payoutAccounts = payoutAccounts;
        this.gateway = gateway;
        this.props = props;
        this.payoutTokens = payoutTokens;
    }

    public PublicConfig publicConfig() {
        String googleClientId = props.google() == null ? null : Codes.blankToNull(props.google().clientId());
        return new PublicConfig(gateway.providerName(), gateway.publicKey(), gateway.supportsSimulation(), googleClientId,
                "mercadopago".equalsIgnoreCase(props.payment().provider()) && props.payment().mercadopago().testMode());
    }

    @Transactional(readOnly = true)
    public RegistrationSummary summary(String code) {
        Team team = findTeam(code);
        Category category = team.getCategory();
        Championship championship = category.getChampionship();
        PaymentDto last = payments.findFirstByTeamIdOrderByIdDesc(team.getId())
                .map(PaymentService::toDto)
                .orElse(null);
        return new RegistrationSummary(team.getRegistrationCode(), team.getId(), team.getName(),
                championship.getName(), championship.getSlug(), category.getName(), category.getPrice(),
                team.getStatus(), championship.hasStarted(Instant.now()), last);
    }

    @Transactional
    public PaymentDto create(String code, CreatePaymentRequest req) {
        Team team = findTeam(code);
        if (team.getStatus() == TeamStatus.CONFIRMED) {
            throw ApiException.conflict("Esta inscrição já está paga e confirmada");
        }
        if (team.getStatus() == TeamStatus.CANCELLED) {
            throw ApiException.unprocessable("Esta inscrição foi cancelada. Fale com a organização do campeonato.");
        }
        // Pagar confirma a inscrição; depois do início isso equivaleria a se inscrever com o campeonato rolando.
        if (team.getCategory().getChampionship().hasStarted(Instant.now())) {
            throw ApiException.unprocessable("O campeonato já começou e não aceita mais pagamentos de inscrição. "
                    + "Fale com a organização do campeonato.");
        }
        validate(req);

        // uma nova tentativa substitui cobranças pendentes anteriores
        for (Payment pending : payments.findByTeamIdAndStatus(team.getId(), PaymentStatus.PENDING)) {
            pending.setStatus(PaymentStatus.CANCELLED);
            pending.setStatusDetail("Substituído por uma nova tentativa de pagamento");
        }

        Category category = team.getCategory();
        BigDecimal amount = category.getPrice();
        Instant now = Instant.now();
        SellerAccount seller = sellerForNewPayment(team);
        BigDecimal applicationFee = applicationFee(amount);

        Payment payment = new Payment();
        payment.setTeam(team);
        payment.setAmount(amount);
        payment.setMethod(req.method());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setProvider(gateway.providerName());
        if (seller != null) {
            payment.setPayoutAccountId(seller.payoutAccountId());
        }
        payment.setApplicationFee(applicationFee);
        payment.setExpiresAt(req.method() == PaymentMethod.PIX ? now.plus(props.payment().pixExpiration()) : null);
        if (req.method().isCard()) {
            // débito é sempre à vista (validate já recusou parcelas > 1)
            payment.setInstallments(req.method() == PaymentMethod.DEBIT_CARD || req.card().installments() == null
                    ? 1 : req.card().installments());
        }
        payments.saveAndFlush(payment);

        String description = "Inscrição " + team.getName() + " - " + category.getChampionship().getName()
                + " (" + category.getName() + ")";
        GatewayPayment result = gateway.create(new PaymentCommand(
                payment.getId(),
                "wodz-payment-" + payment.getId(),
                amount,
                description,
                req.method(),
                new Payer(Codes.uppercase(req.payer().firstName().trim()),
                        Codes.uppercase(req.payer().lastName().trim()), req.payer().email(), req.payer().cpf()),
                req.card(),
                payment.getInstallments(),
                payment.getExpiresAt(),
                "team:" + team.getId() + ":payment:" + payment.getId(),
                seller,
                applicationFee));
        apply(payment, result);
        payments.saveAndFlush(payment);
        // o desafio 3DS só é útil agora (o emissor dá poucos segundos para iniciá-lo); não vai para o banco
        return toDto(payment, payment.getStatus() == PaymentStatus.PENDING ? result.threeDsChallenge() : null);
    }

    /**
     * Consulta usada pelo polling da página de pagamento. Com Mercado Pago,
     * reconsulta o provedor enquanto estiver pendente (útil quando o webhook
     * ainda não chegou ou não está configurado, ex.: ambiente local).
     */
    @Transactional
    public PaymentDto status(String code, Long paymentId) {
        Team team = findTeam(code);
        Payment payment = payments.findByIdAndTeamId(paymentId, team.getId())
                .orElseThrow(() -> ApiException.notFound("Pagamento não encontrado"));
        if (payment.getStatus() == PaymentStatus.PENDING && gateway.supportsRemoteFetch()
                && payment.getProviderPaymentId() != null) {
            apply(payment, gateway.fetch(payment.getProviderPaymentId(), sellerForExistingPayment(payment)));
            payments.saveAndFlush(payment);
        }
        return toDto(payment);
    }

    /** Apenas no sandbox: simula a compensação de um pagamento pendente. */
    @Transactional
    public PaymentDto simulateApproval(String code, Long paymentId) {
        if (!gateway.supportsSimulation()) {
            throw ApiException.notFound("Simulação indisponível neste ambiente");
        }
        Team team = findTeam(code);
        Payment payment = payments.findByIdAndTeamId(paymentId, team.getId())
                .orElseThrow(() -> ApiException.notFound("Pagamento não encontrado"));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw ApiException.unprocessable("Só pagamentos pendentes podem ser aprovados na simulação");
        }
        payment.setStatus(PaymentStatus.APPROVED);
        payment.setStatusDetail("Pagamento aprovado (simulação)");
        payment.setPaidAt(Instant.now());
        payments.saveAndFlush(payment);
        return toDto(payment);
    }

    /** Chamado pelo webhook: o status vem sempre da API do provedor, nunca do corpo do webhook. */
    @Transactional
    public void syncFromProvider(String providerPaymentId) {
        if (!gateway.supportsRemoteFetch()) {
            return;
        }
        payments.findByProviderAndProviderPaymentId(gateway.providerName(), providerPaymentId).ifPresentOrElse(
                payment -> {
                    apply(payment, gateway.fetch(providerPaymentId, sellerForExistingPayment(payment)));
                    payments.saveAndFlush(payment);
                    log.info("Pagamento {} sincronizado: {}", payment.getId(), payment.getStatus());
                },
                () -> log.warn("Webhook para pagamento desconhecido: {}", providerPaymentId));
    }

    // ----------------------------------------------------------------- helpers

    private Team findTeam(String code) {
        return teams.findByRegistrationCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("Inscrição não encontrada. Confira o link de pagamento."));
    }

    /**
     * No Mercado Pago, cada organizador recebe diretamente na conta que conectou
     * por OAuth. O sandbox não precisa dessa conta e continua utilizável no dev.
     */
    private SellerAccount sellerForNewPayment(Team team) {
        if (!gateway.supportsRemoteFetch()) {
            return null;
        }
        Long ownerId = team.getCategory().getChampionship().getOwner().getId();
        PayoutAccount account = payoutAccounts.findFirstByUserIdAndStatus(ownerId, PayoutAccountStatus.ACTIVE)
                .orElseThrow(() -> ApiException.unprocessable(
                        "O organizador ainda não conectou uma conta Mercado Pago para receber pagamentos."));
        return payoutTokens.seller(account.getId(), true);
    }

    /** Mantém as consultas e webhooks vinculados à conta que recebeu a cobrança original. */
    private SellerAccount sellerForExistingPayment(Payment payment) {
        if (!gateway.supportsRemoteFetch()) {
            return null;
        }
        Long payoutAccountId = payment.getPayoutAccountId();
        if (payoutAccountId == null) {
            throw ApiException.unprocessable("Esta cobrança não tem uma conta de recebimento associada.");
        }
        PayoutAccount account = payoutAccounts.findById(payoutAccountId)
                .orElseThrow(() -> ApiException.unprocessable(
                        "A conta de recebimento desta cobrança não foi encontrada."));
        return seller(account);
    }

    private SellerAccount seller(PayoutAccount account) {
        if (account.getAccessToken() == null || account.getAccessToken().isBlank()) {
            throw ApiException.unprocessable("A conta Mercado Pago do organizador não está mais conectada.");
        }
        return payoutTokens.seller(account.getId());
    }

    /** Calcula a parte da plataforma em centavos exatos; nula significa que não há retenção. */
    private BigDecimal applicationFee(BigDecimal amount) {
        AppProperties.MercadoPagoProps mercadoPago = props.payment().mercadopago();
        if (mercadoPago == null || mercadoPago.platformFeePercent() == null
                || mercadoPago.platformFeePercent().signum() == 0) {
            return null;
        }
        return amount.multiply(mercadoPago.platformFeePercent())
                .movePointLeft(2)
                .setScale(2, RoundingMode.HALF_UP);
    }

    static void validate(CreatePaymentRequest req) {
        if (!req.method().isAvailable()) {
            throw ApiException.badRequest("Forma de pagamento indisponível. Pague com Pix ou cartão.");
        }
        if (Codes.digits(req.payer().cpf()).length() != 11) {
            throw ApiException.badRequest("Informe um CPF válido para o pagador");
        }
        if (req.method().isCard() && req.card() == null) {
            throw ApiException.badRequest("Dados do cartão não recebidos. Preencha o cartão novamente.");
        }
        if (!req.method().isCard() && req.card() != null) {
            throw ApiException.badRequest("Dados de cartão só são aceitos no pagamento com cartão");
        }
        if (req.method() == PaymentMethod.DEBIT_CARD && req.card().installments() != null
                && req.card().installments() != 1) {
            throw ApiException.badRequest("Pagamento no débito é sempre à vista (1 parcela)");
        }
    }

    private static void apply(Payment payment, GatewayPayment result) {
        payment.setProviderPaymentId(result.providerPaymentId());
        payment.setStatus(result.status());
        payment.setStatusDetail(result.statusDetail());
        if (result.pixQrCode() != null) {
            payment.setPixQrCode(result.pixQrCode());
        }
        if (result.pixQrCodeBase64() != null) {
            payment.setPixQrCodeBase64(result.pixQrCodeBase64());
        }
        if (result.expiresAt() != null) {
            payment.setExpiresAt(result.expiresAt());
        }
        if (result.cardLastFour() != null) {
            payment.setCardLastDigits(result.cardLastFour());
        }
        if (result.status() == PaymentStatus.APPROVED && payment.getPaidAt() == null) {
            payment.setPaidAt(Instant.now());
        }
    }

    static PaymentDto toDto(Payment p) {
        return toDto(p, null);
    }

    private static PaymentDto toDto(Payment p, ThreeDsChallenge challenge) {
        return new PaymentDto(p.getId(), p.getMethod(), p.getStatus(), p.getStatusDetail(), p.getAmount(),
                p.getPixQrCode(), p.getPixQrCodeBase64(), p.getCardLastDigits(), p.getInstallments(),
                p.getExpiresAt(), p.getPaidAt(), p.getCreatedAt(), challenge);
    }
}
