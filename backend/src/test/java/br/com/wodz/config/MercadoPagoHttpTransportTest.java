package br.com.wodz.config;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.payment.CheckoutCommand;
import br.com.wodz.payment.MercadoPagoGateway;
import br.com.wodz.payment.MercadoPagoOAuthClient;
import br.com.wodz.payment.SellerAccount;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real HTTP transport: MockRestServiceServer cannot expose lost 401 response bodies. */
class MercadoPagoHttpTransportTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String localUrl;
    private volatile int responseStatus = 401;
    private volatile String responseJson = """
            {"message":"Unauthorized use of live credentials","error":"unauthorized","status":401,"cause":[]}
            """;
    private volatile String requestMethod;
    private volatile String requestPath;
    private volatile String requestQuery;
    private volatile String requestAuthorization;
    private volatile String requestIdempotencyKey;
    private volatile byte[] requestBody;

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            requestQuery = exchange.getRequestURI().getQuery();
            requestAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            requestIdempotencyKey = exchange.getRequestHeaders().getFirst("X-Idempotency-Key");
            requestBody = exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Request-ID", "427b2339-c76a-435f-90f6-c5e8fa6fb0ca");
            if (responseStatus == 307) exchange.getResponseHeaders().set("Location", localUrl + "/redirected");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        localUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stopServer() {
        if (server != null) server.stop(0);
    }



    @Test void productionTransportSearchesPaymentsWithSellerTokenAndDecodesResults() {
        CheckoutCommand c = command();
        responseStatus = 200;
        responseJson = """
                {"results":[{"id":901,"status":"approved","transaction_amount":1,"currency_id":"BRL",
                 "collector_id":123,"live_mode":true,"external_reference":"%s","payment_type_id":"account_money"}]}
                """.formatted(c.externalReference());
        var result = gateway(productionBuilder()).search(c.externalReference(), c.seller());
        assertEquals(1, result.size());
        assertEquals("901", result.getFirst().providerPaymentId());
        assertEquals(PaymentStatus.APPROVED, result.getFirst().status());
        assertEquals(1, requests.get());
        assertEquals("GET", requestMethod);
        assertEquals("/v1/payments/search", requestPath);
        assertTrue(requestQuery.contains("external_reference=" + c.externalReference()));
        assertTrue(requestQuery.contains("offset=0"));
        assertEquals("Bearer APP_USR-local-test", requestAuthorization);
        assertNull(requestIdempotencyKey);
        assertEquals(0, requestBody.length);
    }

    @Test void productionTransportExchangesOauthCodeWithoutChangingTestModeOrPkce() throws Exception {
        responseStatus = 200;
        responseJson = """
                {"access_token":"APP_USR-local-oauth","refresh_token":"local-refresh","user_id":123,
                 "expires_in":3600,"live_mode":true,"scope":"offline_access payments write"}
                """;
        var client = new MercadoPagoOAuthClient(properties(), productionBuilder());
        var result = client.exchangeCode("local-code", "local-verifier", "https://wodz.test/callback");
        assertEquals("APP_USR-local-oauth", result.accessToken());
        assertEquals("local-refresh", result.refreshToken());
        assertEquals("123", result.userId());
        assertTrue(result.liveMode());
        assertEquals(1, requests.get());
        assertEquals("POST", requestMethod);
        assertEquals("/oauth/token", requestPath);
        assertNull(requestAuthorization);
        assertNull(requestIdempotencyKey);
        JsonNode sent = new ObjectMapper().readTree(requestBody);
        assertEquals("client", sent.path("client_id").asText());
        assertEquals("unused", sent.path("client_secret").asText());
        assertEquals("authorization_code", sent.path("grant_type").asText());
        assertEquals("local-code", sent.path("code").asText());
        assertEquals("local-verifier", sent.path("code_verifier").asText());
        assertEquals("https://wodz.test/callback", sent.path("redirect_uri").asText());
        assertTrue(sent.path("test_token").isBoolean());
        assertFalse(sent.path("test_token").booleanValue());
        assertFalse(sent.has("scope"));
    }

    @Test void productionTransportDoesNotFollowRedirectAndReplayPaymentOrToken() {
        responseStatus = 307;
        responseJson = "{}";
        assertThrows(ApiException.class, () -> gateway(productionBuilder()).createCheckout(command()));
        assertEquals(1, requests.get());
        assertEquals("/checkout/preferences", requestPath);
    }

    private RestClient.Builder localBuilder() {
        // The explicit URI factory keeps all gateway calls on this loopback server, despite its production base URL.
        return RestClient.builder().uriBuilderFactory(new DefaultUriBuilderFactory(localUrl));
    }

    private MercadoPagoGateway gateway(RestClient.Builder builder) {
        return new MercadoPagoGateway(properties(), builder);
    }

    private RestClient.Builder productionBuilder() {
        RestClient.Builder builder = localBuilder();
        new ResourceLimitsConfig().externalTimeouts().customize(builder);
        return builder;
    }

    private AppProperties properties() {
        return new AppProperties(null, null, null,
                new AppProperties.PaymentProps("mercadopago", Duration.ofMinutes(30),
                        new AppProperties.MercadoPagoProps("unused", null, "client", "unused", BigDecimal.ZERO, true)),
                null, null, "dev", null);
    }

    private CheckoutCommand command() {
        return new CheckoutCommand(7L, "4f7da0f0-c884-4477-97ca-b5cdafa90f26", BigDecimal.ONE,
                "Local transport test", "https://wodz.test/pagamento/ABC?checkout=7", Instant.now().plusSeconds(30 * 60),
                new SellerAccount(1L, "123", "APP_USR-local-test"), null, true);
    }
}
