package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

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
        Map<String, Object> body = base("authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("code_verifier", codeVerifier);
        return token(body, "O Mercado Pago não confirmou a autorização. Tente conectar a conta de novo.");
    }

    public OAuthTokens refresh(String refreshToken) {
        Map<String, Object> body = base("refresh_token");
        body.put("refresh_token", refreshToken);
        return token(body, "Não foi possível renovar a autorização da conta Mercado Pago. Conecte a conta de novo.");
    }

    /** Nome e e-mail da conta conectada, para o organizador conferir para onde vai o dinheiro. */
    public String accountLabel(String accessToken) {
        try {
            JsonNode me = client.get().uri("/users/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve().body(JsonNode.class);
            if (me == null) {
                return null;
            }
            String first = MercadoPagoGateway.text(me.path("first_name"));
            String last = MercadoPagoGateway.text(me.path("last_name"));
            String email = MercadoPagoGateway.text(me.path("email"));
            String nickname = MercadoPagoGateway.text(me.path("nickname"));
            String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            if (name.isEmpty()) {
                name = nickname == null ? "Conta Mercado Pago" : nickname;
            }
            String label = email == null ? name : name + " (" + email + ")";
            return label.length() > 200 ? label.substring(0, 200) : label;
        } catch (RestClientException e) {
            log.warn("Não foi possível ler os dados da conta Mercado Pago conectada", e);
            return null;
        }
    }

    private Map<String, Object> base(String grantType) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", config.clientId());
        body.put("client_secret", config.clientSecret());
        body.put("grant_type", grantType);
        return body;
    }

    private OAuthTokens token(Map<String, Object> body, String errorMessage) {
        try {
            JsonNode n = client.post().uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (n == null || MercadoPagoGateway.text(n.path("access_token")) == null) {
                throw ApiException.badGateway(errorMessage);
            }
            return new OAuthTokens(
                    MercadoPagoGateway.text(n.path("access_token")),
                    MercadoPagoGateway.text(n.path("refresh_token")),
                    MercadoPagoGateway.text(n.path("public_key")),
                    MercadoPagoGateway.text(n.path("user_id")),
                    n.path("expires_in").asLong(0),
                    n.path("live_mode").asBoolean(false));
        } catch (RestClientResponseException e) {
            // o corpo de erro do OAuth não traz segredos (só error/message)
            log.warn("OAuth do Mercado Pago recusou {}: {} {}", body.get("grant_type"), e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw e.getStatusCode().is4xxClientError()
                    ? ApiException.unprocessable(errorMessage)
                    : ApiException.badGateway("O Mercado Pago não respondeu. Tente de novo em instantes.");
        } catch (RestClientException e) {
            log.error("Falha de comunicação com o OAuth do Mercado Pago", e);
            throw ApiException.badGateway("Não foi possível falar com o Mercado Pago. Tente de novo em instantes.");
        }
    }

    private void requireConfigured() {
        if (config.clientId() == null || config.clientId().isBlank()
                || config.clientSecret() == null || config.clientSecret().isBlank()) {
            throw ApiException.unprocessable("O recebimento pelo Mercado Pago ainda não foi configurado neste ambiente.");
        }
    }

    /** Resposta do /oauth/token. toString não expõe tokens. */
    public record OAuthTokens(String accessToken, String refreshToken, String publicKey, String userId,
                              long expiresInSeconds, boolean liveMode) {

        @Override
        public String toString() {
            return "OAuthTokens[userId=" + userId + ", liveMode=" + liveMode + "]";
        }
    }
}
