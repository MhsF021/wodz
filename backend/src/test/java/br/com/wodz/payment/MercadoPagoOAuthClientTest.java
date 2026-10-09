package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.payment.MercadoPagoOAuthClient.OAuthTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MercadoPagoOAuthClientTest {
    private MockRestServiceServer server;
    private MercadoPagoOAuthClient client;
    private final OAuthTokens tokens = new OAuthTokens("access","refresh","pk","123",3600,true);

    @BeforeEach
    void setup() {
        setup(false);
    }

    private void setup(boolean testMode) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AppProperties props = new AppProperties(null,null,null,
                new AppProperties.PaymentProps("mercadopago",null,
                        new AppProperties.MercadoPagoProps("pk","secret",null,true,"id","secret",BigDecimal.ZERO,"platform",testMode)),
                null,null,testMode ? "dev" : "prod",null);
        client = new MercadoPagoOAuthClient(props,builder);
    }

    private void account(String id,String status,String tags) {
        server.expect(requestTo("https://api.mercadopago.com/users/me"))
                .andExpect(header("Authorization","Bearer access"))
                .andRespond(withSuccess("""
                        {"id":%s,"site_id":"MLB","first_name":"Ana","last_name":"Souza","email":"ana@test.invalid",
                         "status":{"site_status":"%s"},"tags":%s}
                        """.formatted(id,status,tags),MediaType.APPLICATION_JSON));
    }

    @Test
    void confereIdentidadeNoMercadoPagoAntesDeExibirContaValidada() {
        account("123","active","[]");
        assertEquals("Ana Souza (ana@test.invalid)",client.validateAccount(tokens));
        server.verify();
    }

    @Test
    void identidadeDiferenteESuspensaoNaoSaoAceitas() {
        account("456","active","[]");
        assertThrows(ApiException.class, () -> client.validateAccount(tokens));
        server.verify();
    }

    @Test
    void contaSuspensaNaoEValidada() {
        account("123","suspended","[]");
        assertThrows(ApiException.class, () -> client.validateAccount(tokens));
        server.verify();
    }

    @Test
    void contaDeTesteMesmoComLiveModeNaoEValidadaEmProducao() {
        account("123","active","[\"test_user\"]");
        assertThrows(ApiException.class, () -> client.validateAccount(tokens));
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
    void autorizacaoLocalSolicitaTokenDeTesteNaApiOficial() {
        setup(true);
        server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                .andExpect(jsonPath("$.client_id").value("id"))
                .andExpect(jsonPath("$.client_secret").value("secret"))
                .andExpect(jsonPath("$.grant_type").value("authorization_code"))
                .andExpect(jsonPath("$.test_token").value(true))
                .andExpect(jsonPath("$.code_verifier").value("verifier"))
                .andRespond(withSuccess("""
                        {"access_token":"TEST-access","refresh_token":"refresh","public_key":"TEST-key",
                         "user_id":123,"expires_in":3600,"live_mode":false}
                        """,MediaType.APPLICATION_JSON));
        OAuthTokens result = client.exchangeCode("code","verifier","https://wodz.test/callback");
        assertFalse(result.liveMode());
        assertEquals("TEST-access",result.accessToken());
        server.verify();
    }

    @Test
    void modoAusenteOuMalformadoNaoEPresumidoComoTeste() {
        setup(true);
        for (String mode : new String[]{"",",\"live_mode\":null",",\"live_mode\":\"false\""}) {
            server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                    .andRespond(withSuccess("""
                            {"access_token":"access","refresh_token":"refresh","public_key":"pk",
                             "user_id":123,"expires_in":3600%s}
                            """.formatted(mode),MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < 3; i++) {
            assertThrows(ApiException.class, () -> client.exchangeCode("code","verifier","https://wodz.test/callback"));
        }
        server.verify();
    }

    @Test
    void renovacaoMantemModoDaContaOriginalMesmoAposAlterarConfiguracao() {
        // Cliente configurado para produção ainda concilia uma cobrança de teste anterior.
        server.expect(requestTo("https://api.mercadopago.com/oauth/token"))
                .andExpect(jsonPath("$.grant_type").value("refresh_token"))
                .andExpect(jsonPath("$.refresh_token").value("old-refresh"))
                .andExpect(jsonPath("$.test_token").value(true))
                .andRespond(withSuccess("""
                        {"access_token":"TEST-access","refresh_token":"new-refresh","public_key":"TEST-key",
                         "user_id":123,"expires_in":3600,"live_mode":false}
                        """,MediaType.APPLICATION_JSON));
        assertFalse(client.refresh("old-refresh",true).liveMode());
        server.verify();
    }
}
