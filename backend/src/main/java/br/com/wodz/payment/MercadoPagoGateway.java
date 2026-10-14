package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Pagamento hospedado no Checkout Pro. */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "mercadopago")
public class MercadoPagoGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(MercadoPagoGateway.class);
    private static final DateTimeFormatter PREFERENCE_DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.of("America/Sao_Paulo"));
    private final RestClient client;
    private final AppProperties.MercadoPagoProps config;
    public MercadoPagoGateway(AppProperties props, RestClient.Builder builder) {
        config = props.payment().mercadopago();
        client = builder.baseUrl("https://api.mercadopago.com").build();
    }
    public String providerName() { return "MERCADO_PAGO"; }
    public boolean supportsRemoteFetch() { return true; }

    public GatewayCheckout createCheckout(CheckoutCommand c) {
        if (c.seller() != null && c.seller().platformDirect()) throw ApiException.unprocessable("O checkout exige a conta OAuth do organizador.");
        requireCheckoutToken(c.seller());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of("id", c.externalReference(), "title", c.description(),
                "currency_id", "BRL", "quantity", 1, "unit_price", c.amount())));
        body.put("external_reference", c.externalReference());
        // Sem seleção ou exclusões: o comprador escolhe entre os meios disponíveis no Checkout Pro.
        body.put("back_urls", Map.of("success", c.returnUrl() + "&retorno=sucesso",
                "pending", c.returnUrl() + "&retorno=pendente", "failure", c.returnUrl() + "&retorno=erro"));
        body.put("auto_return", "approved");
        body.put("expires", true);
        body.put("expiration_date_from", PREFERENCE_DATE.format(Instant.now()));
        body.put("expiration_date_to", PREFERENCE_DATE.format(c.expiresAt()));
        if (config.notificationUrl() != null && !config.notificationUrl().isBlank())
            body.put("notification_url", config.notificationUrl());
        if (c.marketplaceFee() != null) body.put("marketplace_fee", c.marketplaceFee());
        try {
            var response = client.post().uri("/checkout/preferences")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + c.seller().accessToken())
                    .header("X-Idempotency-Key", c.externalReference())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(JsonNode.class);
            GatewayCheckout checkout = preference(response.getBody(), c, null);
            if (checkout == null) throw invalidPreference(c, "expired_or_return_url");
            if (hasPaymentExclusions(response.getBody())) throw invalidPreference(c, "payment_methods");
            logCheckout("created", c, checkout, response.getHeaders(), response.getBody());
            return checkout;
        } catch (RestClientException e) { throw unavailable(e); }
    }

    /** Só retoma uma preferência que o provedor ainda associa à aplicação, vendedor e inscrição atuais. */
    public GatewayCheckout resumeCheckout(String preferenceId, CheckoutCommand c) {
        if (c.seller() != null && c.seller().platformDirect()) throw ApiException.unprocessable("O checkout exige a conta OAuth do organizador.");
        requireCheckoutToken(c.seller());
        if (preferenceId == null || preferenceId.isBlank()) return null;
        try {
            var response = client.get().uri("/checkout/preferences/{id}", preferenceId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + c.seller().accessToken())
                    .retrieve().toEntity(JsonNode.class);
            GatewayCheckout checkout = preference(response.getBody(), c, preferenceId);
            if (checkout != null && hasPaymentExclusions(response.getBody())) {
                // Remove restrições legadas na mesma preferência, sem duplicar o checkout.
                response = client.put().uri("/checkout/preferences/{id}", preferenceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + c.seller().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        // No PUT, [] é ignorado; [{"id":""}] limpa a exclusão existente.
                        .body(Map.of("payment_methods", Map.of("excluded_payment_methods", List.of(Map.of("id", "")),
                                "excluded_payment_types", List.of(Map.of("id", "")))))
                        .retrieve().toEntity(JsonNode.class);
                checkout = preference(response.getBody(), c, preferenceId);
                if (checkout != null && hasPaymentExclusions(response.getBody())) throw invalidPreference(c, "payment_methods");
            }
            if (checkout != null) logCheckout("resumed", c, checkout, response.getHeaders(), response.getBody());
            return checkout;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            throw unavailable(e);
        } catch (RestClientException e) { throw unavailable(e); }
    }

    private static boolean hasPaymentExclusions(JsonNode preference) {
        for (String field : List.of("excluded_payment_methods", "excluded_payment_types")) {
            for (JsonNode method : preference.path("payment_methods").path(field)) {
                String id = text(method.path("id"));
                // O provedor pode representar listas vazias como [{"id":""}].
                if (id != null && !id.isBlank()) return true;
            }
        }
        return false;
    }

    private void logCheckout(String action, CheckoutCommand c, GatewayCheckout checkout,
                             HttpHeaders headers, JsonNode response) {
        String requestId = requestId(headers);
        String expiration = text(response.path("expiration_date_to"));
        // A preferência já passou pela validação; normaliza a data e limita IDs a caracteres seguros.
        String expiresAt = expiration == null ? "unavailable" : OffsetDateTime.parse(expiration).toInstant().toString();
        log.info("Checkout Pro preferência validada: action={} checkout={} preference={} collector={} client={} expires_at={} request_id={}",
                action, c.sessionId(), logIdentifier(checkout.preferenceId()), logIdentifier(c.seller().providerUserId()),
                logIdentifier(config.clientId()), expiresAt, requestId);
    }

    private static String logIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9-]{1,120}") ? value : "unavailable";
    }

    private static String logUuid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                ? value : "unavailable";
    }

    private static String requestId(HttpHeaders headers) {
        // Nunca registra valores arbitrários de headers, mesmo numa resposta de erro.
        return headers == null ? "unavailable" : logUuid(headers.getFirst("x-request-id"));
    }

    private GatewayCheckout preference(JsonNode n, CheckoutCommand c, String expectedId) {
        if (n == null || !n.isObject()) throw invalidPreference(c, "missing_response");
        String id = text(n.path("id"));
        String url = text(n.path("init_point"));
        if (id == null || id.isBlank() || (expectedId != null && !expectedId.equals(id))
                || !safeCheckoutUrl(url) || !preferenceInUrl(url, id)) throw invalidPreference(c, "preference_url");
        if (!c.seller().providerUserId().equals(text(n.path("collector_id"))))
            throw invalidPreference(c, "collector_id");
        if (config.clientId() == null || !config.clientId().equals(text(n.path("client_id")))) {
            log.warn("Checkout Pro recusado: checkout={} motivo=client_id", c.sessionId());
            throw ApiException.unprocessable("A autorização do organizador pertence a outra aplicação Mercado Pago. Reconecte a conta em Recebimentos.");
        }
        if (!c.externalReference().equals(text(n.path("external_reference"))))
            throw invalidPreference(c, "external_reference");
        JsonNode items = n.path("items");
        if (!items.isArray() || items.size() != 1) throw invalidPreference(c, "items");
        JsonNode item = items.get(0);
        BigDecimal price = decimal(item.path("unit_price"));
        if (!"BRL".equals(text(item.path("currency_id"))) || !item.path("quantity").isIntegralNumber()
                || item.path("quantity").intValue() != 1 || price == null || c.amount().compareTo(price) != 0)
            throw invalidPreference(c, "items_amount");
        BigDecimal expectedFee = c.marketplaceFee() == null ? BigDecimal.ZERO : c.marketplaceFee();
        BigDecimal fee = decimal(n.path("marketplace_fee"));
        if (fee == null || expectedFee.compareTo(fee) != 0)
            throw invalidPreference(c, "marketplace_fee");
        boolean expiredFlag = n.path("preference_expired").asBoolean(false);
        String expiration = text(n.path("expiration_date_to"));
        if (expiration != null || n.path("expires").asBoolean(false)) {
            if (expiration == null) throw invalidPreference(c, "expiration_date_to");
            try {
                Instant now = Instant.now();
                Instant end = OffsetDateTime.parse(expiration).toInstant();
                if (!end.isAfter(now)) return null;
                String from = text(n.path("expiration_date_from"));
                Instant start = from == null ? null : OffsetDateTime.parse(from).toInstant();
                if (start != null && start.isAfter(now)) return null;
                if (expiredFlag) {
                    // A API de teste pode indicar expirada uma preferência que o checkout ainda abre.
                    // Só tolera essa divergência em conta test_user validada e com a vigência exata.
                    if (!c.testMode() || !n.path("expires").asBoolean(false) || start == null
                            || !start.isBefore(end)
                            || !end.equals(c.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))) return null;
                    log.warn("Checkout Pro de teste com indicador de expiração divergente: checkout={}", c.sessionId());
                }
            }
            catch (java.time.format.DateTimeParseException e) { throw invalidPreference(c, "expiration_date_to"); }
        } else if (expiredFlag) return null;
        JsonNode back = n.path("back_urls");
        if (!(c.returnUrl() + "&retorno=sucesso").equals(text(back.path("success")))
                || !(c.returnUrl() + "&retorno=pendente").equals(text(back.path("pending")))
                || !(c.returnUrl() + "&retorno=erro").equals(text(back.path("failure")))) return null;
        // Checkout Pro usa init_point também com as contas de teste oficiais.
        return new GatewayCheckout(id, url);
    }

    // A API de preferências também documenta unit_price como texto decimal na resposta GET.
    private static BigDecimal decimal(JsonNode n) {
        if (n.isNumber()) return n.decimalValue();
        if (!n.isTextual() || !n.textValue().matches("[0-9]+(?:\\.[0-9]+)?")) return null;
        try { return new BigDecimal(n.textValue()); }
        catch (NumberFormatException e) { return null; }
    }

    private static boolean preferenceInUrl(String url, String id) {
        String query = URI.create(url).getRawQuery();
        if (query == null) return false;
        int matches = 0;
        try {
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if ("pref_id".equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                    if (pair.length != 2 || !id.equals(URLDecoder.decode(pair[1], StandardCharsets.UTF_8))) return false;
                    matches++;
                }
            }
        } catch (IllegalArgumentException e) { return false; }
        return matches == 1;
    }

    private static void requireCheckoutToken(SellerAccount seller) {
        if (seller == null || seller.accessToken() == null || !seller.accessToken().startsWith("APP_USR-"))
            throw ApiException.unprocessable("O organizador precisa reconectar uma conta Mercado Pago compatível com Checkout Pro.");
    }

    private ApiException invalidPreference(CheckoutCommand c, String reason) {
        log.warn("Checkout Pro recusado: checkout={} motivo={}", c.sessionId(), reason);
        return new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "O Mercado Pago não confirmou a preferência desta inscrição. Tente novamente; se persistir, fale com a organização.");
    }

    public GatewayPayment fetch(String id, SellerAccount seller) {
        if (id == null || !id.matches("[0-9]{1,30}")) throw ApiException.badRequest("Pagamento inválido");
        try {
            return parse(client.get().uri("/v1/payments/{id}", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + seller.accessToken()).retrieve().body(JsonNode.class));
        } catch (RestClientException e) { throw unavailable(e); }
    }

    public List<GatewayPayment> search(String reference, SellerAccount seller) {
        try {
            List<GatewayPayment> found = new ArrayList<>();
            // Uma preferência pode gerar recusas seguidas de aprovação. Não perde tentativas na paginação.
            for (int offset = 0; offset < 10000; offset += 50) {
                int pageOffset = offset;
                JsonNode response = client.get().uri(b -> b.path("/v1/payments/search")
                        .queryParam("external_reference", reference).queryParam("sort", "date_created")
                        .queryParam("criteria", "asc").queryParam("limit", 50).queryParam("offset", pageOffset).build())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + seller.accessToken())
                        .retrieve().body(JsonNode.class);
                if (response == null || !response.path("results").isArray()) throw incomplete();
                for (JsonNode node : response.path("results")) found.add(parse(node));
                if (response.path("results").size() < 50) break;
            }
            return found;
        } catch (RestClientException e) { throw searchUnavailable(e, reference); }
    }

    static GatewayPayment parse(JsonNode n) {
        if (n == null || text(n.path("id")) == null || !n.path("transaction_amount").isNumber()
                || !n.path("live_mode").isBoolean() || text(n.path("status")) == null) throw incomplete();
        PaymentStatus status = switch (n.path("status").asText()) {
            case "approved" -> PaymentStatus.APPROVED;
            case "rejected" -> PaymentStatus.REJECTED;
            case "cancelled" -> PaymentStatus.CANCELLED;
            case "refunded", "charged_back" -> PaymentStatus.REFUNDED;
            default -> PaymentStatus.PENDING;
        };
        PaymentMethod method = switch (n.path("payment_type_id").asText()) {
            case "credit_card" -> PaymentMethod.CREDIT_CARD;
            case "debit_card" -> PaymentMethod.DEBIT_CARD;
            case "ticket" -> PaymentMethod.BOLETO;
            default -> "pix".equals(n.path("payment_method_id").asText()) ? PaymentMethod.PIX : PaymentMethod.MERCADO_PAGO;
        };
        Instant paidAt = null;
        String paid = text(n.path("date_approved"));
        if (paid != null) {
            try { paidAt = OffsetDateTime.parse(paid).toInstant(); }
            catch (java.time.format.DateTimeParseException e) { throw incomplete(); }
        }
        Integer installments = n.path("installments").isInt() ? Integer.valueOf(n.path("installments").intValue()) : null;
        if (method == PaymentMethod.DEBIT_CARD) installments = 1;
        return new GatewayPayment(text(n.path("id")), status, text(n.path("status_detail")),
                n.path("transaction_amount").decimalValue(), text(n.path("currency_id")),
                text(n.path("external_reference")), text(n.path("collector_id")), n.path("live_mode").booleanValue(),
                method, installments, paidAt, decimal(n.path("transaction_details").path("net_received_amount")),
                decimal(n.path("transaction_amount_refunded")));
    }
    public static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || !n.isValueNode() ? null : n.asText();
    }
    public static boolean safeCheckoutUrl(String value) {
        try {
            URI u = URI.create(value);
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443)
                    && "www.mercadopago.com.br".equals(u.getHost())
                    && u.getPath().startsWith("/checkout/");
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
    private static ApiException incomplete() {
        return new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "O Mercado Pago retornou informações incompletas. Tente novamente.");
    }
    private ApiException unavailable(RestClientException e) {
        int status = e instanceof RestClientResponseException r ? r.getStatusCode().value() : 0;
        String reason = providerReason(e);
        log.warn("Mercado Pago indisponível: HTTP={} motivo={}", status, reason);
        return providerFailure();
    }

    private ApiException searchUnavailable(RestClientException e, String reference) {
        int status = e instanceof RestClientResponseException response ? response.getStatusCode().value() : 0;
        String reason = providerReason(e);
        String safeReference = logUuid(reference);
        log.warn("Mercado Pago: operation=search action=failed reference={} HTTP={} motivo={} request_id={}",
                "unavailable".equals(safeReference) ? "unknown" : safeReference, status, reason,
                requestId(e instanceof RestClientResponseException response ? response.getResponseHeaders() : null));
        return providerFailure();
    }

    private static ApiException providerFailure() {
        return new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "O Mercado Pago está indisponível. Tente novamente em instantes.");
    }

    private static String providerReason(RestClientException e) {
        if (!(e instanceof RestClientResponseException response)) return "connection";
        Set<String> known = Set.of("invalid_access_token", "invalid_collector_id", "invalid_client_id",
                "collector_does_not_comply_with_current_regulation", "invalid_marketplace_fee",
                "invalid_items", "invalid_back_urls", "invalid_expiration_date_from", "invalid_expiration_date_to",
                "invalid_date", "invalid_id", "invalid_operation_type", "invalid_sponsor_id", "invalid_collector_email");
        try {
            JsonNode body = new ObjectMapper().readTree(response.getResponseBodyAsString());
            for (String field : List.of("error", "message")) {
                String reason = text(body.path(field));
                if (response.getStatusCode().value() == 401 && "Unauthorized use of live credentials".equals(reason))
                    return "unauthorized_live_credentials";
                // A Payments API também envolve esse código numa mensagem de erro interno.
                if (reason != null && reason.contains("user_allowed_only_in_test")) return "user_allowed_only_in_test";
                if (known.contains(reason == null ? "" : reason)) return reason;
            }
            for (JsonNode cause : body.path("cause")) {
                String description = text(cause.path("description"));
                if (response.getStatusCode().value() == 401 && "Unauthorized use of live credentials".equals(description))
                    return "unauthorized_live_credentials";
                if (description != null && description.contains("user_allowed_only_in_test")) return "user_allowed_only_in_test";
            }
        } catch (Exception ignored) { /* Não registra o corpo, a URL ou credenciais do provedor. */ }
        return "provider_error";
    }
}
