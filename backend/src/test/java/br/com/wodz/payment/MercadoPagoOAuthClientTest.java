package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.payment.MercadoPagoOAuthClient.OAuthTokens;
import br.com.wodz.payment.PayoutAuthorizationException.Reason;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MercadoPagoOAuthClientTest {
    private MockRestServiceServer server;
    private MercadoPagoOAuthClient client;
    private final OAuthTokens tokens = new OAuthTokens("APP_USR-access","refresh","123",3600,true);

    @BeforeEach
    void setup() {
        setup(false);
    }

    private void setup(boolean testMode) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AppProperties props = new AppProperties(null,null,null,
                new AppProperties.PaymentProps("mercadopago",null,
                        new AppProperties.MercadoPagoProps("secret", null, "id", "secret", BigDecimal.ZERO, testMode)),
                null,null,testMode ? "dev" : "prod",null);
        client = new MercadoPagoOAuthClient(props,builder);
    }

    private void account(String id,String status,String tags) {
        server.expect(requestTo("https://api.mercadopago.com/users/me"))
                .andExpect(header("Authorization","Bearer APP_USR-access"))
                .andRespond(withSuccess("""
                        {"id":%s,"site_id":"MLB","first_name":"Ana","last_name":"Souza","email":"ana@test.invalid",
                         "status":{"site_status":"%s"},"tags":%s}
                        """.formatted(id,status,tags),MediaType.APPLICATION_JSON));
    }

    @Test
    void confereIdentidadeNoMercadoPagoAntesDeExibirContaValidada() {
        account("123","active","[]");
        assertEquals("Ana Souza (ana@test.invalid)",client.validateCheckoutAccount(tokens, false).label());
        server.verify();
    }

    @Test
    void identidadeDiferenteESuspensaoNaoSaoAceitas() {
        account("456","active","[]");
        assertThrows(ApiException.class, () -> client.validateCheckoutAccount(tokens, false));
        server.verify();
    }

    @Test
    void contaSuspensaNaoEValidada() {
        account("123","suspended","[]");
        assertThrows(ApiException.class, () -> client.validateCheckoutAccount(tokens, false));
        server.verify();
    }

    @Test
    void contaDeTesteMesmoComLiveModeNaoEValidadaEmProducao() {
        account("123","active","[\"test_user\"]");
        assertThrows(ApiException.class, () -> client.validateCheckoutAccount(tokens, false));
        server.verify();
    }

    @Test
    void tokenSemRenovacaoNaoEArmazenado() {
        server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                .andExpect(jsonPath("$.code_verifier").value("verifier"))
                .andExpect(jsonPath("$.test_token").value(false))
                .andRespond(withSuccess("{\"access_token\":\"access\",\"user_id\":123,\"expires_in\":3600}",MediaType.APPLICATION_JSON));
        assertThrows(ApiException.class, () -> client.exchangeCode("code","verifier","https://wodz.test/callback"));
        server.verify();
    }

    @Test
    void autorizacaoLocalSolicitaAppUsrParaCheckoutProNaApiOficial() {
        setup(true);
        server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                .andExpect(jsonPath("$.client_id").value("id"))
                .andExpect(jsonPath("$.client_secret").value("secret"))
                .andExpect(jsonPath("$.grant_type").value("authorization_code"))
                .andExpect(jsonPath("$.scope").value("read write offline_access"))
                .andExpect(jsonPath("$.test_token").value(false))
                .andExpect(jsonPath("$.code_verifier").value("verifier"))
                .andRespond(withSuccess("""
                        {"access_token":"APP_USR-access","refresh_token":"refresh","public_key":"APP_USR-key",
                         "user_id":123,"expires_in":3600,"live_mode":false}
                        """,MediaType.APPLICATION_JSON));
        OAuthTokens result = client.exchangeCode("code","verifier","https://wodz.test/callback");
        assertFalse(result.liveMode());
        assertEquals("APP_USR-access",result.accessToken());
        server.verify();
    }

    @Test
    void modoAusenteSemCredencialTestOuValorAmbiguoNaoEPresumidoComoTeste() {
        setup(true);
        for (String mode : new String[]{"",",\"live_mode\":null",",\"live_mode\":\"unknown\""}) {
            server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                    .andRespond(withSuccess("""
                            {"access_token":"APP_USR-access","refresh_token":"refresh","public_key":"pk",
                             "user_id":123,"expires_in":3600%s}
                            """.formatted(mode),MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 3; i++) {
            assertThrows(ApiException.class, () -> client.exchangeCode("code","verifier","https://wodz.test/callback"));
        }
        server.verify();
    }

    @Test
    void checkoutUsaTagDaContaDeTesteMesmoQuandoOAuthRetornaLiveModeTrue() {
        setup(true);
        server.expect(requestTo("https://api.mercadopago.com/users/me"))
                .andExpect(header("Authorization", "Bearer APP_USR-test"))
                .andRespond(withSuccess("""
                        {"id":123,"site_id":"MLB","nickname":"Vendedor teste",
                         "status":{"site_status":"active"},"tags":["test_user"]}
                        """, MediaType.APPLICATION_JSON));
        var account = client.validateCheckoutAccount(
                new OAuthTokens("APP_USR-test","refresh","123",3600,true), true);
        assertEquals("Vendedor teste", account.label());
        assertFalse(account.tokens().liveMode());
        assertEquals("APP_USR-test", account.tokens().accessToken());
        server.verify();
    }

    @Test
    void contaRealNaoCriaCobrancasNoModoTesteMesmoComLiveModeFalse() {
        setup(true);
        server.expect(requestTo("https://api.mercadopago.com/users/me"))
                .andRespond(withSuccess("""
                        {"id":123,"site_id":"MLB","status":{"site_status":"active"},"tags":[]}
                        """, MediaType.APPLICATION_JSON));
        var failure = assertThrows(PayoutAuthorizationException.class, () -> client.validateCheckoutAccount(
                new OAuthTokens("APP_USR-real","refresh","123",3600,false), true));
        assertEquals(Reason.MODE_MISMATCH, failure.getReason());
        server.verify();
    }

    @Test
    void contaDeTesteNaoPodeSerVinculadaComoContaReal() {
        server.expect(requestTo("https://api.mercadopago.com/users/me"))
                .andRespond(withSuccess("""
                        {"id":123,"site_id":"MLB","status":{"site_status":"active"},"tags":["test_user"]}
                        """, MediaType.APPLICATION_JSON));
        assertThrows(PayoutAuthorizationException.class, () -> client.validateCheckoutAccount(
                new OAuthTokens("APP_USR-test","refresh","123",3600,true), false));
        server.verify();
    }

    @Test
    void checkoutRecusaTokenTestEContaSemTipoConfirmado() {
        assertThrows(PayoutAuthorizationException.class, () -> client.validateCheckoutAccount(
                new OAuthTokens("TEST-test","refresh","123",3600,false), true));
        server.expect(requestTo("https://api.mercadopago.com/users/me"))
                .andRespond(withSuccess("""
                        {"id":123,"site_id":"MLB","status":{"site_status":"active"}}
                        """, MediaType.APPLICATION_JSON));
        var failure = assertThrows(PayoutAuthorizationException.class, () -> client.validateCheckoutAccount(
                new OAuthTokens("APP_USR-test","refresh","123",3600,true), true));
        assertEquals(Reason.INVALID_RESPONSE, failure.getReason());
        server.verify();
    }

    @Test
    void renovaCheckoutDeTesteComAppUsrNoModoOriginalSemPedirTestToken() {
        server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                .andExpect(jsonPath("$.test_token").value(false))
                .andExpect(jsonPath("$.refresh_token").value("old-refresh"))
                .andRespond(withSuccess("""
                        {"access_token":"APP_USR-new","refresh_token":"new-refresh","user_id":123,
                         "expires_in":3600,"live_mode":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.mercadopago.com/users/me"))
                .andExpect(header("Authorization", "Bearer APP_USR-new"))
                .andRespond(withSuccess("""
                        {"id":123,"site_id":"MLB","status":{"site_status":"active"},"tags":["test_user"]}
                        """, MediaType.APPLICATION_JSON));
        var renewed = client.refreshCheckout("old-refresh", true);
        assertFalse(renewed.liveMode());
        assertEquals("new-refresh", renewed.refreshToken());
        server.verify();
    }

    @Test
    void modoTextualExplicitoENormalizadoSemAceitarValoresAmbiguos() {
        for (String value : new String[]{"true", "false"}) {
            setup(!Boolean.parseBoolean(value));
            server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                    .andRespond(withSuccess("""
                            {"access_token":"APP_USR-access","refresh_token":"refresh","user_id":123,
                             "expires_in":3600,"live_mode":"%s"}
                            """.formatted(value), MediaType.APPLICATION_JSON));
            assertEquals(Boolean.parseBoolean(value),
                    client.exchangeCode("code", "verifier", "https://wodz.test/callback").liveMode());
            server.verify();
        }
    }

    @Test
    void credencialTestSemLiveModeNaoEInferidaEmSolicitacaoDeProducao() {
        server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                .andExpect(jsonPath("$.test_token").value(false))
                .andRespond(withSuccess("""
                        {"access_token":"TEST-access","refresh_token":"refresh","user_id":123,"expires_in":3600}
                        """, MediaType.APPLICATION_JSON));
        var failure = assertThrows(PayoutAuthorizationException.class,
                () -> client.exchangeCode("code", "verifier", "https://wodz.test/callback"));
        assertEquals(Reason.INVALID_RESPONSE, failure.getReason());
        server.verify();
    }

    @Test
    void credencialTestNaoPodeSerApresentadaComoProducaoNemMascararModoInvalido() {
        for (String mode : new String[]{"true", "\"true\"", "\"unknown\"", "0", "{}"}) {
            setup(true);
            server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                    .andRespond(withSuccess("""
                            {"access_token":"TEST-access","refresh_token":"refresh","user_id":123,
                             "expires_in":3600,"live_mode":%s}
                            """.formatted(mode), MediaType.APPLICATION_JSON));
            var failure = assertThrows(PayoutAuthorizationException.class,
                    () -> client.exchangeCode("code", "verifier", "https://wodz.test/callback"));
            assertEquals(Reason.INVALID_RESPONSE, failure.getReason());
            server.verify();
        }
    }

    @Test
    void rejeicoesOAuthTemMotivoFixoSemVazarCorpoOuCredenciaisNosLogs() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MercadoPagoOAuthClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.addAppender(logs);
        try {
            String[] codes = {"invalid_client", "invalid_request", "unauthorized_client", "invalid_scope", "invalid_grant", "PRIVATE-ERROR"};
            Reason[] reasons = {Reason.CONFIGURATION, Reason.CONFIGURATION, Reason.CONFIGURATION, Reason.CONFIGURATION, Reason.TOKEN_REJECTED, Reason.AUTHORIZATION_REJECTED};
            for (int i = 0; i < codes.length; i++) {
                setup(false);
                server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                        .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                                .body("{\"error\":\"" + codes[i] + "\",\"message\":\"PRIVATE-TOKEN\"}"));
                var failure = assertThrows(PayoutAuthorizationException.class,
                        () -> client.exchangeCode("PRIVATE-CODE", "PRIVATE-VERIFIER", "https://wodz.test/callback"));
                assertEquals(reasons[i], failure.getReason());
                server.verify();
            }
            assertFalse(logs.list.isEmpty());
            for (var event : logs.list) {
                assertFalse(event.getFormattedMessage().contains("PRIVATE"));
                assertTrue(event.getFormattedMessage().contains("motivo="));
            }
        } finally {
            logger.detachAppender(logs); logs.stop();
        }
    }

    @Test
    void respostaIncompletaIdentificaCampoSemExporTokensOuDadosDaConta() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MercadoPagoOAuthClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start(); logger.addAppender(logs);
        try {
            String[] fields = {"access_token", "refresh_token", "user_id", "expires_in", "live_mode"};
            ObjectMapper json = new ObjectMapper();
            for (String field : fields) {
                setup(true);
                var response = json.readTree("""
                        {"access_token":"APP_USR-PRIVATE-ACCESS","refresh_token":"PRIVATE-REFRESH",
                         "user_id":"PRIVATE-USER","expires_in":3600,"live_mode":false,
                         "PRIVATE-EXTRA":"PRIVATE-VALUE"}
                        """);
                ((com.fasterxml.jackson.databind.node.ObjectNode) response).remove(field);
                server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                        .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
                var failure = assertThrows(PayoutAuthorizationException.class,
                        () -> client.exchangeCode("PRIVATE-CODE", "PRIVATE-VERIFIER", "https://wodz.test/callback"));
                assertEquals(Reason.INVALID_RESPONSE, failure.getReason());
                assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatus());
                assertEquals(1, logs.list.size());
                String diagnostic = logs.list.getFirst().getFormattedMessage();
                assertTrue(diagnostic.endsWith("campos_invalidos=" + field));
                assertTrue(diagnostic.contains("HTTP=200 teste=false"));
                assertTrue(diagnostic.contains("credencial_tipo=" + (field.equals("access_token") ? "UNKNOWN" : "APP_USR")));
                if (field.equals("live_mode")) assertTrue(diagnostic.contains("live_mode_tipo=MISSING"));
                assertFalse(diagnostic.contains("PRIVATE"));
                logs.list.clear();
                server.verify();
            }
            for (String response : new String[]{"null", "[]", "\"PRIVATE\"", """
                    {"access_token":" ","refresh_token":false,"user_id":{},"expires_in":0,"live_mode":"false"}
                    """}) {
                setup(false);
                server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
                var failure = assertThrows(PayoutAuthorizationException.class,
                        () -> client.exchangeCode("PRIVATE-CODE", "PRIVATE-VERIFIER", "https://wodz.test/callback"));
                assertEquals(Reason.INVALID_RESPONSE, failure.getReason());
                assertFalse(logs.list.getLast().getFormattedMessage().contains("PRIVATE"));
                server.verify();
            }
        } finally {
            logger.detachAppender(logs); logs.stop();
        }
    }

    @Test
    void respostaCompletaDeProducaoPermiteVinculacaoSemLogarCredenciais() {
        server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                .andExpect(jsonPath("$.scope").value("read write offline_access"))
                .andExpect(jsonPath("$.redirect_uri").value("https://wodz.test/callback"))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("""
                        {"access_token":"APP_USR-access","refresh_token":"TG-refresh","public_key":"APP_USR-key",
                         "token_type":"bearer","scope":"read write offline_access",
                         "user_id":123,"expires_in":15552000,"live_mode":true}
                        """, MediaType.APPLICATION_JSON));
        OAuthTokens result = client.exchangeCode("code", "verifier", "https://wodz.test/callback");
        assertEquals("123", result.userId());
        assertEquals("TG-refresh", result.refreshToken());
        assertEquals(15552000, result.expiresInSeconds());
        assertTrue(result.liveMode());
        server.verify();
    }

    @Test
    void falhaHttpOuHtmlDoProvedorNaoEConfundidaComCancelamento() {
        server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_HTML)
                        .body("<html>PRIVATE proxy error</html>"));
        var failure = assertThrows(PayoutAuthorizationException.class,
                () -> client.exchangeCode("code", "verifier", "https://wodz.test/callback"));
        assertEquals(Reason.PROVIDER_UNAVAILABLE, failure.getReason());
        assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatus());
        server.verify();
    }

    @Test
    void contaRecusadaNaValidacaoTemMotivoProprio() {
        server.expect(requestTo("https://api.mercadopago.com/users/me"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"PRIVATE\"}"));
        var failure = assertThrows(PayoutAuthorizationException.class, () -> client.validateCheckoutAccount(tokens, false));
        assertEquals(Reason.ACCOUNT_UNAVAILABLE, failure.getReason());
        server.verify();
    }
}
