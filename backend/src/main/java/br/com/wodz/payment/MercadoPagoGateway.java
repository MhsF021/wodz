package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.dto.PaymentDtos.Payer;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.common.IdentificationRequest;
import com.mercadopago.client.common.PhoneRequest;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferencePayerRequest;
import com.mercadopago.client.preference.PreferencePaymentMethodsRequest;
import com.mercadopago.client.preference.PreferencePaymentTypeRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPResultsResourcesPage;
import com.mercadopago.net.MPSearchRequest;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Checkout Pro pelo SDK oficial: cria a preferência com os dados do pagador e o
 * navegador segue para o init_point, onde o pagador escolhe Pix ou cartão.
 */
@Component
public class MercadoPagoGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoGateway.class);

    /** Limite da constraint ck_payment_installments. */
    static final int MAX_INSTALLMENTS = 12;

    private final AppProperties.MercadoPagoProps config;
    private final PreferenceClient preferences;
    private final PaymentClient paymentClient;

    public MercadoPagoGateway(AppProperties props) {
        this.config = props.payment() == null ? null : props.payment().mercadopago();
        if (config == null || config.accessToken() == null || config.accessToken().isBlank()) {
            throw new IllegalStateException("Defina MP_ACCESS_TOKEN (credencial do Mercado Pago) no .env");
        }
        MercadoPagoConfig.setAccessToken(config.accessToken().trim());
        this.preferences = new PreferenceClient();
        this.paymentClient = new PaymentClient();
    }

    @Override
    public String providerName() {
        return "MERCADO_PAGO";
    }

    @Override
    public String createCheckout(PaymentCommand cmd) {
        MPRequestOptions options = MPRequestOptions.builder()
                .customHeaders(Map.of("X-Idempotency-Key", cmd.idempotencyKey()))
                .build();
        try {
            Preference preference = preferences.create(preferenceRequest(cmd, config.notificationUrl()), options);
            String url = checkoutUrl(preference.getInitPoint(), preference.getSandboxInitPoint(), config.accessToken());
            if (url == null) {
                throw ApiException.badGateway("O Mercado Pago não retornou o link para pagamento.");
            }
            return url;
        } catch (MPApiException e) {
            log.warn("Mercado Pago recusou a preferência do pagamento {}: {} {}", cmd.localPaymentId(),
                    e.getStatusCode(), e.getApiResponse() == null ? null : e.getApiResponse().getContent());
            if (e.getStatusCode() >= 400 && e.getStatusCode() < 500) {
                throw ApiException.unprocessable("O Mercado Pago não conseguiu iniciar o checkout. "
                        + "Confira os dados de quem está pagando e tente novamente.");
            }
            throw ApiException.badGateway("O Mercado Pago não respondeu. Tente de novo em instantes.");
        } catch (MPException e) {
            log.error("Falha de comunicação com o Mercado Pago", e);
            throw ApiException.badGateway("Não foi possível falar com o Mercado Pago. Tente de novo em instantes.");
        }
    }

    @Override
    public GatewayPayment fetch(String providerPaymentId) {
        long id;
        try {
            id = Long.parseLong(providerPaymentId.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Identificador de pagamento inválido");
        }
        try {
            return toGatewayPayment(paymentClient.get(id));
        } catch (MPApiException e) {
            log.warn("Mercado Pago não retornou o pagamento {}: {}", id, e.getStatusCode());
            throw ApiException.badGateway("Não foi possível consultar o pagamento no Mercado Pago");
        } catch (MPException e) {
            log.error("Falha ao consultar o pagamento {} no Mercado Pago", id, e);
            throw ApiException.badGateway("Não foi possível consultar o pagamento no Mercado Pago");
        }
    }

    @Override
    public Optional<GatewayPayment> findByExternalReference(String externalReference) {
        MPSearchRequest request = MPSearchRequest.builder()
                .offset(0)
                .limit(50)
                .filters(Map.<String, Object>of(
                        "external_reference", externalReference,
                        "sort", "date_created",
                        "criteria", "desc"))
                .build();
        try {
            MPResultsResourcesPage<Payment> page = paymentClient.search(request);
            List<GatewayPayment> attempts = page == null || page.getResults() == null ? List.of()
                    : page.getResults().stream().map(MercadoPagoGateway::toGatewayPayment).toList();
            return mostRelevant(attempts);
        } catch (MPApiException e) {
            log.warn("Mercado Pago recusou a busca por {}: {}", externalReference, e.getStatusCode());
            throw ApiException.badGateway("Não foi possível consultar o pagamento no Mercado Pago");
        } catch (MPException e) {
            log.error("Falha ao buscar pagamentos de {} no Mercado Pago", externalReference, e);
            throw ApiException.badGateway("Não foi possível consultar o pagamento no Mercado Pago");
        }
    }

    // ----------------------------------------------------------------- helpers

    static PreferenceRequest preferenceRequest(PaymentCommand cmd, String notificationUrl) {
        PreferenceItemRequest item = PreferenceItemRequest.builder()
                .id("inscricao-" + cmd.localPaymentId())
                .title(cmd.description())
                .quantity(1)
                .currencyId("BRL")
                .unitPrice(cmd.amount())
                .build();

        Payer payer = cmd.payer();
        PreferencePayerRequest payerRequest = PreferencePayerRequest.builder()
                .name(payer.firstName())
                .surname(payer.lastName())
                .email(payer.email())
                .identification(IdentificationRequest.builder().type("CPF").number(payer.cpf()).build())
                // telefone já normalizado: DDD (2 dígitos) + número
                .phone(PhoneRequest.builder()
                        .areaCode(payer.phone().substring(0, 2))
                        .number(payer.phone().substring(2))
                        .build())
                .build();

        PreferenceRequest.PreferenceRequestBuilder builder = PreferenceRequest.builder()
                .items(List.of(item))
                .payer(payerRequest)
                .externalReference(cmd.externalReference())
                // boleto (ticket) não é aceito; Pix e cartão ficam disponíveis no checkout
                .paymentMethods(PreferencePaymentMethodsRequest.builder()
                        .excludedPaymentTypes(List.of(PreferencePaymentTypeRequest.builder().id("ticket").build()))
                        .installments(MAX_INSTALLMENTS)
                        .build())
                .backUrls(PreferenceBackUrlsRequest.builder()
                        .success(cmd.returnUrl() + "?checkout=success")
                        .pending(cmd.returnUrl() + "?checkout=pending")
                        .failure(cmd.returnUrl() + "?checkout=failure")
                        .build());
        // O Mercado Pago recusa auto_return com URL local; nela o pagador volta pelo botão do checkout.
        if (isPublicHttps(cmd.returnUrl())) {
            builder.autoReturn("approved");
        }
        if (notificationUrl != null && !notificationUrl.isBlank()) {
            builder.notificationUrl(notificationUrl.trim());
        }
        return builder.build();
    }

    /** Credenciais TEST- abrem o checkout de sandbox; as de produção (inclusive de usuário de teste), o init_point. */
    static String checkoutUrl(String initPoint, String sandboxInitPoint, String accessToken) {
        if (accessToken.trim().startsWith("TEST-") && sandboxInitPoint != null) {
            return sandboxInitPoint;
        }
        return initPoint;
    }

    static boolean isPublicHttps(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && !host.isEmpty()
                    && !host.equals("localhost") && !host.endsWith(".localhost")
                    && !host.equals("127.0.0.1") && !host.equals("[::1]");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Num mesmo checkout o pagador pode tentar várias vezes (ex.: cartão recusado e
     * depois Pix). Vale a aprovada; senão a que está em andamento; senão a mais recente.
     */
    static Optional<GatewayPayment> mostRelevant(List<GatewayPayment> attemptsNewestFirst) {
        return attemptsNewestFirst.stream().filter(p -> p.status() == PaymentStatus.APPROVED).findFirst()
                .or(() -> attemptsNewestFirst.stream().filter(p -> p.status() == PaymentStatus.PENDING).findFirst())
                .or(() -> attemptsNewestFirst.stream().findFirst());
    }

    private static GatewayPayment toGatewayPayment(Payment p) {
        int installments = p.getInstallments();
        return new GatewayPayment(
                p.getId() == null ? null : String.valueOf(p.getId()),
                mapStatus(p.getStatus()),
                statusDetail(p.getStatus(), p.getStatusDetail()),
                p.getPaymentTypeId(),
                installments >= 1 && installments <= MAX_INSTALLMENTS ? installments : null,
                p.getCard() == null ? null : p.getCard().getLastFourDigits(),
                p.getDateOfExpiration() == null ? null : p.getDateOfExpiration().toInstant(),
                p.getExternalReference());
    }

    static PaymentStatus mapStatus(String status) {
        if (status == null) return PaymentStatus.PENDING;
        return switch (status) {
            case "approved" -> PaymentStatus.APPROVED;
            case "rejected" -> PaymentStatus.REJECTED;
            case "cancelled" -> PaymentStatus.CANCELLED;
            case "refunded", "charged_back" -> PaymentStatus.REFUNDED;
            default -> PaymentStatus.PENDING; // pending, in_process, authorized, in_mediation
        };
    }

    /** Texto exibido ao pagador; o código do Mercado Pago fica entre parênteses para o suporte. */
    static String statusDetail(String status, String detail) {
        String text = switch (status == null ? "" : status) {
            case "approved" -> "Pagamento aprovado no Mercado Pago";
            case "rejected" -> "O Mercado Pago recusou o pagamento";
            case "cancelled" -> "Pagamento cancelado no Mercado Pago";
            case "refunded" -> "Pagamento estornado";
            case "charged_back" -> "Pagamento contestado no cartão";
            case "in_process", "in_mediation" -> "Pagamento em análise no Mercado Pago";
            default -> "pending_waiting_transfer".equals(detail)
                    ? "Aguardando o pagamento do Pix"
                    : "Aguardando pagamento no Mercado Pago";
        };
        String full = detail == null || detail.isBlank() ? text : text + " (" + detail + ")";
        return full.length() <= 160 ? full : full.substring(0, 160); // payment.status_detail é VARCHAR(160)
    }
}
