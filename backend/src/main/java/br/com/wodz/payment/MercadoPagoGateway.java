package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.dto.PaymentDtos.CardData;
import br.com.wodz.dto.PaymentDtos.Payer;
import br.com.wodz.dto.PaymentDtos.ThreeDsChallenge;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integração com a API de pagamentos do Mercado Pago (Checkout Transparente) no
 * modelo split 1:1: cada cobrança usa o token OAuth do organizador, então o
 * dinheiro cai direto na conta dele. Pix e cartão de crédito ou débito tokenizado
 * no navegador, com 3DS opcional e application_fee para a plataforma.
 * Docs: https://www.mercadopago.com.br/developers/pt/reference/payments/_payments/post
 */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "mercadopago")
public class MercadoPagoGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoGateway.class);
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter MP_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final Duration CATALOG_TTL = Duration.ofHours(6);
    // bandeira desconhecida força recarga do catálogo no máximo uma vez nesse intervalo
    private static final Duration CATALOG_MIN_RELOAD = Duration.ofMinutes(10);

    private final RestClient client;
    private final AppProperties.MercadoPagoProps config;

    /** payment_method_id -> payment_type_id (ex.: master -> credit_card). */
    private volatile Map<String, String> paymentTypes = Map.of();
    private volatile Instant paymentTypesLoadedAt = Instant.EPOCH;

    public MercadoPagoGateway(AppProperties props, RestClient.Builder builder) {
        this.config = props.payment().mercadopago();
        this.client = builder.baseUrl("https://api.mercadopago.com").build();
    }

    @Override
    public String providerName() {
        return "MERCADO_PAGO";
    }

    @Override
    public boolean supportsRemoteFetch() {
        return true;
    }

    @Override
    public String publicKey() {
        return config.publicKey();
    }

    @Override
    public GatewayPayment create(PaymentCommand cmd) {
        SellerAccount seller = cmd.seller();
        if (seller == null || seller.accessToken() == null) {
            throw new IllegalArgumentException("Cobrança sem conta de recebimento");
        }
        Payer payer = cmd.payer();
        Map<String, Object> payerBody = new LinkedHashMap<>();
        payerBody.put("email", payer.email());
        payerBody.put("first_name", payer.firstName());
        payerBody.put("last_name", payer.lastName());
        payerBody.put("identification", Map.of("type", "CPF", "number", Codes.digits(payer.cpf())));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_amount", cmd.amount());
        body.put("description", cmd.description());
        body.put("external_reference", cmd.externalReference());
        if (config.notificationUrl() != null && !config.notificationUrl().isBlank()) {
            body.put("notification_url", config.notificationUrl());
        }
        if (cmd.applicationFee() != null && cmd.applicationFee().signum() > 0) {
            body.put("application_fee", cmd.applicationFee());
        }

        switch (cmd.method()) {
            case PIX -> {
                body.put("payment_method_id", "pix");
                body.put("date_of_expiration", MP_DATE.format(cmd.expiresAt().atZone(BRASILIA)));
            }
            case CREDIT_CARD, DEBIT_CARD -> {
                CardData card = cmd.card();
                // O navegador só limita o formulário; quem garante crédito x débito é o catálogo do Mercado Pago.
                ensureCardType(card.paymentMethodId(), cmd.method(), seller);
                body.put("token", card.token());
                body.put("installments", cmd.installments());
                body.put("payment_method_id", card.paymentMethodId());
                if (card.issuerId() != null && !card.issuerId().isBlank()) {
                    body.put("issuer_id", card.issuerId());
                }
                if (config.threeDSecure()) {
                    body.put("three_d_secure_mode", "optional");
                }
            }
            case BOLETO -> throw new IllegalArgumentException("Boleto não é mais aceito");
        }
        body.put("payer", payerBody);

        GatewayPayment result;
        try {
            JsonNode response = client.post()
                    .uri("/v1/payments")
                    .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                    .header("X-Idempotency-Key", cmd.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            result = toGatewayPayment(response);
        } catch (RestClientResponseException e) {
            log.warn("Mercado Pago recusou o pagamento {} ({}): {} {}", cmd.localPaymentId(), seller,
                    e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw ApiException.unprocessable("A conta Mercado Pago do organizador não está autorizando cobranças. "
                        + "Fale com a organização do campeonato.");
            }
            if (e.getStatusCode().is4xxClientError()) {
                throw ApiException.unprocessable("O Mercado Pago recusou os dados do pagamento. Confira as informações e tente de novo.");
            }
            throw ApiException.badGateway("O Mercado Pago não respondeu. Tente de novo em instantes.");
        } catch (RestClientException e) {
            log.error("Falha de comunicação com o Mercado Pago", e);
            throw ApiException.badGateway("Não foi possível falar com o Mercado Pago. Tente de novo em instantes.");
        }

        String expectedType = cardPaymentType(cmd.method());
        if (expectedType != null && result.paymentTypeId() != null && !expectedType.equals(result.paymentTypeId())) {
            // não deveria acontecer depois de ensureCardType; o pagamento já existe no provedor, então só registra
            log.error("Pagamento {} ({}) foi processado pelo Mercado Pago como {}", cmd.localPaymentId(),
                    cmd.method(), result.paymentTypeId());
        }
        return result;
    }

    @Override
    public GatewayPayment fetch(String providerPaymentId, SellerAccount seller) {
        try {
            JsonNode response = client.get()
                    .uri("/v1/payments/{id}", providerPaymentId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                    .retrieve()
                    .body(JsonNode.class);
            return toGatewayPayment(response);
        } catch (RestClientException e) {
            log.error("Falha ao consultar pagamento {} no Mercado Pago", providerPaymentId, e);
            throw ApiException.badGateway("Não foi possível consultar o pagamento no Mercado Pago");
        }
    }

    /** payment_type_id do Mercado Pago correspondente à forma de pagamento com cartão. */
    static String cardPaymentType(PaymentMethod method) {
        return switch (method) {
            case CREDIT_CARD -> "credit_card";
            case DEBIT_CARD -> "debit_card";
            default -> null;
        };
    }

    /** Recusa a cobrança se a bandeira do cartão não for do tipo escolhido (crédito ou débito). */
    private void ensureCardType(String paymentMethodId, PaymentMethod method, SellerAccount seller) {
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            throw ApiException.badRequest("Dados do cartão incompletos. Preencha o cartão novamente.");
        }
        String type = paymentTypes(false, seller).get(paymentMethodId);
        if (type == null) {
            type = paymentTypes(true, seller).get(paymentMethodId);
        }
        if (!cardPaymentType(method).equals(type)) {
            throw ApiException.unprocessable(method == PaymentMethod.DEBIT_CARD
                    ? "Este cartão não pode ser usado na função débito. Escolha \"Crédito\" ou use outro cartão."
                    : "Este cartão não pode ser usado na função crédito. Escolha \"Débito\" ou use outro cartão.");
        }
    }

    /** Catálogo de meios de pagamento do Brasil, em cache. Falha fechada: sem catálogo, não cobra. */
    private Map<String, String> paymentTypes(boolean reloadIfStale, SellerAccount seller) {
        Instant now = Instant.now();
        Duration maxAge = reloadIfStale ? CATALOG_MIN_RELOAD : CATALOG_TTL;
        if (paymentTypesLoadedAt.plus(maxAge).isAfter(now)) {
            return paymentTypes;
        }
        synchronized (this) {
            if (paymentTypesLoadedAt.plus(maxAge).isAfter(Instant.now())) {
                return paymentTypes;
            }
            try {
                JsonNode response = client.get().uri("/v1/payment_methods")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .retrieve().body(JsonNode.class);
                Map<String, String> types = new HashMap<>();
                if (response != null) {
                    for (JsonNode m : response) {
                        String id = text(m.path("id"));
                        String type = text(m.path("payment_type_id"));
                        if (id != null && type != null) {
                            types.put(id, type);
                        }
                    }
                }
                paymentTypes = Map.copyOf(types);
                paymentTypesLoadedAt = Instant.now();
                return paymentTypes;
            } catch (RestClientException e) {
                log.error("Falha ao carregar os meios de pagamento do Mercado Pago", e);
                throw ApiException.badGateway("Não foi possível validar o cartão no Mercado Pago. Tente de novo em instantes.");
            }
        }
    }

    private static GatewayPayment toGatewayPayment(JsonNode n) {
        JsonNode tx = n.path("point_of_interaction").path("transaction_data");
        String expiration = text(n.path("date_of_expiration"));
        return new GatewayPayment(
                text(n.path("id")),
                mapStatus(text(n.path("status"))),
                text(n.path("status_detail")),
                text(tx.path("qr_code")),
                text(tx.path("qr_code_base64")),
                expiration == null ? null : OffsetDateTime.parse(expiration).toInstant(),
                text(n.path("card").path("last_four_digits")),
                text(n.path("payment_type_id")),
                challenge(n));
    }

    /** Desafio 3DS pendente; só aceita URL https para o navegador não postar o creq em outro lugar. */
    static ThreeDsChallenge challenge(JsonNode n) {
        if (!"pending_challenge".equals(text(n.path("status_detail")))) {
            return null;
        }
        JsonNode info = n.path("three_ds_info");
        String url = text(info.path("external_resource_url"));
        String creq = text(info.path("creq"));
        if (url == null || creq == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                log.warn("Desafio 3DS ignorado: URL não é https ({})", uri.getHost());
                return null;
            }
        } catch (IllegalArgumentException e) {
            log.warn("Desafio 3DS ignorado: URL inválida");
            return null;
        }
        return new ThreeDsChallenge(url, creq);
    }

    private static String bearer(SellerAccount seller) {
        return "Bearer " + seller.accessToken();
    }

    static PaymentStatus mapStatus(String status) {
        if (status == null) {
            return PaymentStatus.PENDING;
        }
        return switch (status) {
            case "approved" -> PaymentStatus.APPROVED;
            case "rejected" -> PaymentStatus.REJECTED;
            case "cancelled" -> PaymentStatus.CANCELLED;
            case "refunded", "charged_back" -> PaymentStatus.REFUNDED;
            default -> PaymentStatus.PENDING; // pending, in_process, authorized, in_mediation
        };
    }

    public static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
