package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.dto.PaymentDtos.Address;
import br.com.wodz.dto.PaymentDtos.CardData;
import br.com.wodz.dto.PaymentDtos.Payer;
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

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integração com a API de pagamentos do Mercado Pago (Checkout Transparente):
 * Pix, boleto (bolbradesco) e cartão de crédito tokenizado no navegador.
 * Docs: https://www.mercadopago.com.br/developers/pt/reference/payments/_payments/post
 */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "mercadopago")
public class MercadoPagoGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoGateway.class);
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter MP_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final RestClient client;
    private final AppProperties.MercadoPagoProps config;

    public MercadoPagoGateway(AppProperties props, RestClient.Builder builder) {
        this.config = props.payment().mercadopago();
        if (config.accessToken() == null || config.accessToken().isBlank()) {
            throw new IllegalStateException("Defina MP_ACCESS_TOKEN para usar o Mercado Pago");
        }
        this.client = builder
                .baseUrl("https://api.mercadopago.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.accessToken())
                .build();
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

        switch (cmd.method()) {
            case PIX -> {
                body.put("payment_method_id", "pix");
                body.put("date_of_expiration", MP_DATE.format(cmd.expiresAt().atZone(BRASILIA)));
            }
            case BOLETO -> {
                body.put("payment_method_id", "bolbradesco");
                body.put("date_of_expiration", MP_DATE.format(cmd.expiresAt().atZone(BRASILIA)));
                payerBody.put("address", address(payer.address()));
            }
            case CREDIT_CARD -> {
                CardData card = cmd.card();
                body.put("token", card.token());
                body.put("installments", card.installments() == null ? 1 : card.installments());
                if (card.paymentMethodId() != null) {
                    body.put("payment_method_id", card.paymentMethodId());
                }
                if (card.issuerId() != null && !card.issuerId().isBlank()) {
                    body.put("issuer_id", card.issuerId());
                }
            }
        }
        body.put("payer", payerBody);

        try {
            JsonNode response = client.post()
                    .uri("/v1/payments")
                    .header("X-Idempotency-Key", cmd.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return toGatewayPayment(response);
        } catch (RestClientResponseException e) {
            log.warn("Mercado Pago recusou o pagamento {}: {} {}", cmd.localPaymentId(), e.getStatusCode(),
                    e.getResponseBodyAsString());
            if (e.getStatusCode().is4xxClientError()) {
                throw ApiException.unprocessable("O Mercado Pago recusou os dados do pagamento. Confira as informações e tente de novo.");
            }
            throw ApiException.badGateway("O Mercado Pago não respondeu. Tente de novo em instantes.");
        } catch (RestClientException e) {
            log.error("Falha de comunicação com o Mercado Pago", e);
            throw ApiException.badGateway("Não foi possível falar com o Mercado Pago. Tente de novo em instantes.");
        }
    }

    @Override
    public GatewayPayment fetch(String providerPaymentId) {
        try {
            JsonNode response = client.get()
                    .uri("/v1/payments/{id}", providerPaymentId)
                    .retrieve()
                    .body(JsonNode.class);
            return toGatewayPayment(response);
        } catch (RestClientException e) {
            log.error("Falha ao consultar pagamento {} no Mercado Pago", providerPaymentId, e);
            throw ApiException.badGateway("Não foi possível consultar o pagamento no Mercado Pago");
        }
    }

    private static Map<String, Object> address(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zip_code", Codes.digits(a.zipCode()));
        m.put("street_name", a.street());
        m.put("street_number", a.number());
        m.put("neighborhood", a.neighborhood());
        m.put("city", a.city());
        m.put("federal_unit", a.state().toUpperCase());
        return m;
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
                text(n.path("transaction_details").path("external_resource_url")),
                text(n.path("barcode").path("content")),
                expiration == null ? null : OffsetDateTime.parse(expiration).toInstant(),
                text(n.path("card").path("last_four_digits")));
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
