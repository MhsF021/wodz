package br.com.wodz.payment;

import br.com.wodz.config.AppProperties;
import br.com.wodz.payment.PayoutAuthorizationException.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OAuth do Mercado Pago para o organizador conectar a conta que recebe as
 * inscrições (split 1:1). Authorization Code com PKCE (S256) e state.
 * Docs: https://www.mercadopago.com.br/developers/pt/docs/security/oauth/creation
 */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "mercadopago")
public class MercadoPagoOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoOAuthClient.class);
    private static final String AUTHORIZATION_URL = "https://auth.mercadopago.com/authorization";
    private static final Set<String> LOGGABLE_SCOPES = Set.of("read", "write", "offline_access", "payment", "payments");

    private final RestClient client;
    private final AppProperties.MercadoPagoProps config;

    public MercadoPagoOAuthClient(AppProperties props, RestClient.Builder builder) {
        this.config = props.payment().mercadopago();
        this.client = builder.baseUrl("https://api.mercadopago.com").build();
    }

    public String authorizationUrl(String state, String codeChallenge, String redirectUri) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_URL)
                .queryParam("client_id", config.clientId())
                .queryParam("response_type", "code")
                .queryParam("platform_id", "mp")
                .queryParam("state", state)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUriString();
    }

    public OAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
        // Checkout Pro requer APP_USR também para vendedor de teste. O tipo da
        // conta é comprovado pelos dados da conta conectada, separado de live_mode.
        Map<String, Object> body = base("authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("code_verifier", codeVerifier);
        // O fluxo oficial Marketplace não sobrescreve os escopos da aplicação.
        // O refresh_token retornado permite renovar o vínculo sem novo login.
        return token(body, "O Mercado Pago não confirmou a autorização. Tente conectar a conta de novo.");
    }

    public CheckoutAccount refreshCheckout(String refreshToken, Boolean expectedTestMode) {
        Map<String, Object> body = base("refresh_token");
        body.put("refresh_token", refreshToken);
        OAuthTokens renewed = token(body, "Não foi possível renovar a autorização da conta Mercado Pago. Conecte a conta de novo.");
        return validateCheckoutAccount(renewed, expectedTestMode);
    }

    /** APP_USR não distingue conta real de teste: confira a identidade no provedor. */
    public CheckoutAccount validateCheckoutAccount(OAuthTokens tokens, Boolean expectedTestMode) {
        if (!tokens.accessToken().startsWith("APP_USR-")) {
            throw new PayoutAuthorizationException(Reason.MODE_MISMATCH,
                    "Reconecte a conta Mercado Pago para usar Checkout Pro.");
        }
        JsonNode me = account(tokens);
        if (!me.path("tags").isArray()) {
            throw new PayoutAuthorizationException(Reason.INVALID_RESPONSE, HttpStatus.BAD_GATEWAY,
                    "O Mercado Pago não confirmou o tipo da conta. Tente conectar novamente.");
        }
        boolean testAccount = false;
        for (JsonNode tag : me.path("tags")) testAccount |= "test_user".equals(tag.asText());
        if (expectedTestMode != null && testAccount != expectedTestMode) {
            throw new PayoutAuthorizationException(Reason.MODE_MISMATCH, expectedTestMode
                    ? "Conecte uma conta de vendedor de teste do Mercado Pago neste ambiente."
                    : "Conecte uma conta real de vendedor do Mercado Pago neste ambiente.");
        }
        // Contas test_user também podem gerar pagamentos live_mode=true no Checkout Pro.
        // O tipo da conta não substitui o modo explicitamente retornado pelo OAuth.
        return new CheckoutAccount(accountLabel(me), tokens, testAccount);
    }

    private JsonNode account(OAuthTokens tokens) {
        try {
            JsonNode me = client.get().uri("/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                    .retrieve().body(JsonNode.class);
            if (me == null || !tokens.userId().equals(MercadoPagoGateway.text(me.path("id")))
                    || !"MLB".equals(me.path("site_id").asText())
                    || !"active".equals(me.path("status").path("site_status").asText())) {
                throw new PayoutAuthorizationException(Reason.ACCOUNT_UNAVAILABLE,
                        "Use uma conta Mercado Pago brasileira ativa para receber inscrições.");
            }
            return me;
        } catch (RestClientResponseException e) {
            Reason reason = e.getStatusCode().is4xxClientError() ? Reason.ACCOUNT_UNAVAILABLE : Reason.PROVIDER_UNAVAILABLE;
            log.warn("Validação da conta Mercado Pago recusada: HTTP {} motivo={}", e.getStatusCode().value(), reason.code());
            throw new PayoutAuthorizationException(reason, HttpStatus.BAD_GATEWAY,
                    "Não foi possível validar a conta no Mercado Pago. Tente conectar novamente.");
        } catch (RestClientException e) {
            log.warn("Falha de comunicação na validação da conta Mercado Pago: {}", e.getClass().getSimpleName());
            throw new PayoutAuthorizationException(Reason.PROVIDER_UNAVAILABLE, HttpStatus.BAD_GATEWAY,
                    "Não foi possível validar a conta no Mercado Pago. Tente conectar novamente.");
        }
    }

    private static String accountLabel(JsonNode me) {
        String first = MercadoPagoGateway.text(me.path("first_name"));
        String last = MercadoPagoGateway.text(me.path("last_name"));
        String email = MercadoPagoGateway.text(me.path("email"));
        String nickname = MercadoPagoGateway.text(me.path("nickname"));
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (name.isEmpty()) name = nickname == null ? "Conta Mercado Pago" : nickname;
        String label = email == null ? name : name + " (" + email + ")";
        return label.length() > 200 ? label.substring(0, 200) : label;
    }

    public record CheckoutAccount(String label, OAuthTokens tokens, boolean testAccount) { }

    private Map<String, Object> base(String grantType) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", config.clientId());
        body.put("client_secret", config.clientSecret());
        body.put("grant_type", grantType);
        body.put("test_token", false);
        return body;
    }

    private OAuthTokens token(Map<String, Object> body, String errorMessage) {
        try {
            var response = client.post().uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(JsonNode.class);
            JsonNode n = response.getBody();
            Boolean liveMode = resolveLiveMode(n);
            List<String> invalidFields = invalidTokenFields(n, liveMode);
            if (!invalidFields.isEmpty()) {
                // Apenas nomes fixos de campos, status e modo solicitado; nunca valores da resposta.
                log.warn("OAuth do Mercado Pago retornou resposta incompleta: grant={} HTTP={} teste={} live_mode_tipo={} credencial_tipo={} campos_invalidos={}",
                        body.get("grant_type"), response.getStatusCode().value(), body.get("test_token"),
                        n == null ? "MISSING" : n.path("live_mode").getNodeType().name(), credentialType(n),
                        String.join(",", invalidFields));
                throw new PayoutAuthorizationException(Reason.INVALID_RESPONSE, HttpStatus.BAD_GATEWAY, errorMessage);
            }
            ScopeDiagnostic scopes = scopeDiagnostic(n);
            log.info("OAuth do Mercado Pago confirmado: grant={} HTTP={} credential_type={} live_mode={} scopes={} unknown_scopes={}",
                    body.get("grant_type"), response.getStatusCode().value(), credentialType(n), liveMode,
                    scopes.known(), scopes.unknownCount());
            return new OAuthTokens(
                    MercadoPagoGateway.text(n.path("access_token")),
                    MercadoPagoGateway.text(n.path("refresh_token")),
                    MercadoPagoGateway.text(n.path("user_id")),
                    n.path("expires_in").asLong(0),
                    liveMode);
        } catch (RestClientResponseException e) {
            Reason reason = rejectionReason(e);
            log.warn("OAuth do Mercado Pago recusou {}: HTTP {} motivo={}",
                    body.get("grant_type"), e.getStatusCode().value(), reason.code());
            throw new PayoutAuthorizationException(reason,
                    e.getStatusCode().is4xxClientError() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY,
                    errorMessage);
        } catch (RestClientException e) {
            log.error("Falha de comunicação com o OAuth do Mercado Pago: {}", e.getClass().getSimpleName());
            throw new PayoutAuthorizationException(Reason.PROVIDER_UNAVAILABLE, HttpStatus.BAD_GATEWAY,
                    "Não foi possível falar com o Mercado Pago. Tente de novo em instantes.");
        }
    }

    private record ScopeDiagnostic(String known, int unknownCount) { }

    private static ScopeDiagnostic scopeDiagnostic(JsonNode response) {
        JsonNode scope = response.path("scope");
        if (scope.isMissingNode() || scope.isNull()) return new ScopeDiagnostic("NONE", 0);
        if (!scope.isTextual()) return new ScopeDiagnostic("NONE", 1);
        Set<String> known = new LinkedHashSet<>();
        int unknownCount = 0;
        for (String value : scope.textValue().trim().split("\\s+")) {
            if (value.isEmpty()) continue;
            if (LOGGABLE_SCOPES.contains(value)) known.add(value);
            else unknownCount++;
        }
        return new ScopeDiagnostic(known.isEmpty() ? "NONE" : String.join(",", known), unknownCount);
    }

    private static String credentialType(JsonNode response) {
        if (response == null || !response.path("access_token").isTextual()) return "UNKNOWN";
        String token = response.path("access_token").textValue();
        if (token.startsWith("APP_USR-") && token.length() > 8) return "APP_USR";
        return "UNKNOWN";
    }

    private static Boolean resolveLiveMode(JsonNode response) {
        if (response == null || !response.isObject()) return null;
        JsonNode mode = response.path("live_mode");
        Boolean explicit = null;
        if (mode.isBoolean()) explicit = mode.booleanValue();
        else if (mode.isTextual()) explicit = switch (mode.textValue()) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
        return explicit;
    }

    private static List<String> invalidTokenFields(JsonNode response, Boolean liveMode) {
        if (response == null || !response.isObject()) return List.of("response");
        List<String> invalid = new ArrayList<>();
        for (String name : List.of("access_token", "refresh_token")) {
            JsonNode value = response.path(name);
            if (!value.isTextual() || value.asText().isBlank()
                    || (name.equals("access_token") && !"APP_USR".equals(credentialType(response)))) invalid.add(name);
        }
        JsonNode userId = response.path("user_id");
        if ((!userId.isTextual() && !userId.isIntegralNumber()) || userId.asText().isBlank()) invalid.add("user_id");
        if (response.path("expires_in").asLong(0) <= 0) invalid.add("expires_in");
        if (liveMode == null) invalid.add("live_mode");
        return invalid;
    }

    private void requireConfigured() {
        if (config.clientId() == null || config.clientId().isBlank()
                || config.clientSecret() == null || config.clientSecret().isBlank()) {
            throw new PayoutAuthorizationException(Reason.CONFIGURATION,
                    "O recebimento pelo Mercado Pago ainda não foi configurado neste ambiente.");
        }
    }

    private static Reason rejectionReason(RestClientResponseException response) {
        if (!response.getStatusCode().is4xxClientError()) return Reason.PROVIDER_UNAVAILABLE;
        try {
            JsonNode body = response.getResponseBodyAs(JsonNode.class);
            if (body != null) return switch (body.path("error").asText()) {
                case "invalid_client", "unauthorized_client", "invalid_request", "invalid_scope", "unsupported_grant_type" -> Reason.CONFIGURATION;
                case "invalid_grant" -> Reason.TOKEN_REJECTED;
                case "access_denied" -> Reason.AUTHORIZATION_REJECTED;
                default -> Reason.AUTHORIZATION_REJECTED;
            };
        } catch (RuntimeException ignored) {
            // Respostas de proxy/HTML também são rejeitadas, sem registrar o corpo.
        }
        return Reason.AUTHORIZATION_REJECTED;
    }

    /** Resposta do /oauth/token. toString não expõe tokens. */
    public record OAuthTokens(String accessToken, String refreshToken, String userId,
                              long expiresInSeconds, boolean liveMode) {

        @Override
        public String toString() {
            return "OAuthTokens[userId=" + userId + ", liveMode=" + liveMode + "]";
        }
    }
}
