package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.Payment;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.dto.PaymentDtos.CreatePaymentRequest;
import br.com.wodz.dto.PaymentDtos.Payer;
import br.com.wodz.dto.PaymentDtos.PaymentDto;
import br.com.wodz.dto.PaymentDtos.PublicConfig;
import br.com.wodz.dto.PaymentDtos.RegistrationSummary;
import br.com.wodz.payment.GatewayPayment;
import br.com.wodz.payment.PaymentCommand;
import br.com.wodz.payment.PaymentGateway;
import br.com.wodz.repository.PaymentRepository;
import br.com.wodz.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * Pagamento da inscrição pelo link público /pagamento/{código}. Com os dados de
 * quem paga, cria o Checkout Pro e o pagador escolhe Pix ou cartão no Mercado
 * Pago. A aprovação (webhook ou consulta da página) dispara a trigger que
 * confirma a equipe no banco.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TeamRepository teams;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final AppProperties props;

    public PaymentService(TeamRepository teams, PaymentRepository payments, PaymentGateway gateway,
                          AppProperties props) {
        this.teams = teams;
        this.payments = payments;
        this.gateway = gateway;
        this.props = props;
    }

    public PublicConfig publicConfig() {
        return new PublicConfig(props.google() == null ? null : Codes.blankToNull(props.google().clientId()));
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

    /** Cria o checkout no Mercado Pago; o frontend redireciona o pagador para checkoutUrl. */
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
        Payer payer = normalize(req.payer());

        // um novo checkout substitui cobranças pendentes anteriores
        for (Payment pending : payments.findByTeamIdAndStatus(team.getId(), PaymentStatus.PENDING)) {
            pending.setStatus(PaymentStatus.CANCELLED);
            pending.setStatusDetail("Substituído por uma nova tentativa de pagamento");
        }

        Category category = team.getCategory();
        BigDecimal amount = category.getPrice();

        Payment payment = new Payment();
        payment.setTeam(team);
        payment.setAmount(amount);
        payment.setMethod(PaymentMethod.CHECKOUT_PRO);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setStatusDetail("Aguardando pagamento no Mercado Pago");
        payment.setProvider(gateway.providerName());
        payments.saveAndFlush(payment);

        String description = "Inscrição " + team.getName() + " - " + category.getChampionship().getName()
                + " (" + category.getName() + ")";
        String checkoutUrl = gateway.createCheckout(new PaymentCommand(
                payment.getId(),
                "wodz-payment-" + payment.getId(),
                amount,
                description,
                payer,
                externalReference(payment),
                frontendUrl() + "/pagamento/" + team.getRegistrationCode()));
        payment.setCheckoutUrl(checkoutUrl);
        payments.saveAndFlush(payment);
        return toDto(payment);
    }

    /**
     * Consulta usada pelo polling da página. Enquanto pendente, busca no Mercado
     * Pago pela referência externa (funciona mesmo sem webhook, ex.: ambiente local).
     */
    @Transactional
    public PaymentDto status(String code, Long paymentId) {
        Team team = findTeam(code);
        Payment payment = payments.findByIdAndTeamId(paymentId, team.getId())
                .orElseThrow(() -> ApiException.notFound("Pagamento não encontrado"));
        if (payment.getStatus() == PaymentStatus.PENDING && gateway.providerName().equals(payment.getProvider())) {
            gateway.findByExternalReference(externalReference(payment))
                    .ifPresent(result -> sync(payment, result));
        }
        return toDto(payment);
    }

    /** Chamado pelo webhook: o status vem sempre da API do provedor, nunca do corpo do webhook. */
    @Transactional
    public void syncFromProvider(String providerPaymentId) {
        GatewayPayment result = gateway.fetch(providerPaymentId);
        Payment payment = payments.findByProviderAndProviderPaymentId(gateway.providerName(), result.providerPaymentId())
                .orElseGet(() -> paymentFromExternalReference(result.externalReference()));
        if (payment == null || !gateway.providerName().equals(payment.getProvider())) {
            log.warn("Webhook para pagamento desconhecido: {}", providerPaymentId);
            return;
        }
        sync(payment, result);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Aplica o resultado do provedor. No mesmo checkout o pagador pode fazer várias
     * tentativas; uma cobrança já aprovada (ou estornada) só aceita novidades da
     * própria tentativa, e uma segunda aprovação para a equipe não é gravada.
     */
    private void sync(Payment payment, GatewayPayment result) {
        boolean otherAttempt = payment.getProviderPaymentId() != null
                && !payment.getProviderPaymentId().equals(result.providerPaymentId());
        boolean settled = payment.getStatus() == PaymentStatus.APPROVED || payment.getStatus() == PaymentStatus.REFUNDED;
        if (otherAttempt && settled) {
            log.info("Pagamento {} já liquidado por {}; ignorando a tentativa {} ({})", payment.getId(),
                    payment.getProviderPaymentId(), result.providerPaymentId(), result.status());
            return;
        }
        if (result.status() == PaymentStatus.APPROVED && payment.getStatus() != PaymentStatus.APPROVED
                && payments.existsByTeamIdAndStatusAndIdNot(payment.getTeam().getId(), PaymentStatus.APPROVED,
                payment.getId())) {
            log.warn("Pagamento em duplicidade: equipe {} já tem pagamento aprovado; estorne {} no Mercado Pago",
                    payment.getTeam().getId(), result.providerPaymentId());
            return;
        }
        apply(payment, result);
        payments.saveAndFlush(payment);
        log.info("Pagamento {} sincronizado: {}", payment.getId(), payment.getStatus());
    }

    private Team findTeam(String code) {
        return teams.findByRegistrationCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.notFound("Inscrição não encontrada. Confira o link de pagamento."));
    }

    private String frontendUrl() {
        String configured = props.frontendUrl();
        return (configured == null || configured.isBlank() ? "http://localhost:5173" : configured.trim())
                .replaceAll("/+$", "");
    }

    static String externalReference(Payment payment) {
        return "team:" + payment.getTeam().getId() + ":payment:" + payment.getId();
    }

    private Payment paymentFromExternalReference(String externalReference) {
        if (externalReference == null || !externalReference.startsWith("team:")) return null;
        int marker = externalReference.lastIndexOf(":payment:");
        if (marker < 6) return null;
        try {
            Long teamId = Long.valueOf(externalReference.substring(5, marker));
            Long paymentId = Long.valueOf(externalReference.substring(marker + 9));
            return payments.findById(paymentId)
                    .filter(p -> p.getTeam().getId().equals(teamId))
                    .orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Valida e devolve o pagador com CPF e telefone só com dígitos (telefone: DDD + número). */
    static Payer normalize(Payer payer) {
        String cpf = Codes.digits(payer.cpf());
        if (!isValidCpf(cpf)) {
            throw ApiException.badRequest("Informe um CPF válido para quem está pagando");
        }
        String phone = normalizePhone(payer.phone());
        if (phone == null) {
            throw ApiException.badRequest("Informe um telefone válido com DDD, ex.: (81) 99999-9999");
        }
        return new Payer(payer.firstName().trim(), payer.lastName().trim(), payer.email().trim(), cpf, phone);
    }

    /** "(81) 99999-9999" ou "+55 81 99999-9999" -> "81999999999"; null se não for um telefone brasileiro. */
    static String normalizePhone(String value) {
        String digits = Codes.digits(value);
        if (digits == null) return null;
        if (digits.startsWith("55") && (digits.length() == 12 || digits.length() == 13)) {
            digits = digits.substring(2);
        }
        boolean validDdd = digits.length() >= 2 && digits.charAt(0) != '0' && digits.charAt(1) != '0';
        return validDdd && (digits.length() == 10 || digits.length() == 11) ? digits : null;
    }

    static boolean isValidCpf(String cpf) {
        if (cpf == null || cpf.length() != 11 || cpf.chars().distinct().count() == 1) return false;
        for (int length = 9; length <= 10; length++) {
            int sum = 0;
            for (int i = 0; i < length; i++) {
                sum += (cpf.charAt(i) - '0') * (length + 1 - i);
            }
            int check = (sum * 10) % 11 % 10;
            if (check != cpf.charAt(length) - '0') return false;
        }
        return true;
    }

    private static void apply(Payment payment, GatewayPayment result) {
        if (result.providerPaymentId() != null) payment.setProviderPaymentId(result.providerPaymentId());
        PaymentMethod method = methodOf(result.paymentTypeId());
        if (method != null) {
            payment.setMethod(method);
            // débito é sempre à vista (ck_payment_debit_installments)
            payment.setInstallments(switch (method) {
                case CREDIT_CARD -> result.installments();
                case DEBIT_CARD -> 1;
                default -> null;
            });
        }
        payment.setStatus(result.status());
        payment.setStatusDetail(result.statusDetail());
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

    /** Meio escolhido no checkout; outros tipos (ex.: saldo em conta) continuam como CHECKOUT_PRO. */
    static PaymentMethod methodOf(String paymentTypeId) {
        if (paymentTypeId == null) return null;
        return switch (paymentTypeId) {
            case "bank_transfer" -> PaymentMethod.PIX;
            case "credit_card" -> PaymentMethod.CREDIT_CARD;
            case "debit_card" -> PaymentMethod.DEBIT_CARD;
            default -> null;
        };
    }

    static PaymentDto toDto(Payment p) {
        return new PaymentDto(p.getId(), p.getMethod(), p.getStatus(), p.getStatusDetail(), p.getAmount(),
                p.getCardLastDigits(), p.getInstallments(), p.getExpiresAt(), p.getPaidAt(), p.getCreatedAt(),
                p.getCheckoutUrl());
    }
}
