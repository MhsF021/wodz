package br.com.wodz.payment;
import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.domain.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MercadoPagoGatewayTest {
    private MockRestServiceServer server;
    private MercadoPagoGateway gateway;
    @BeforeEach void fixture() {
        RestClient.Builder builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        gateway=new MercadoPagoGateway(new AppProperties(null,null,null,new AppProperties.PaymentProps("mercadopago",Duration.ofMinutes(30),
                new AppProperties.MercadoPagoProps("secret","https://wodz.test/api/webhooks/mercadopago","client","secret",BigDecimal.ZERO,true)),
                null,null,"dev",null),builder);
    }
    private CheckoutCommand command(String token) {
        return new CheckoutCommand(20L,"reference",new BigDecimal("150.00"),"Inscrição",
                "https://wodz.test/pagamento/ABC?checkout=20",Instant.now().plusSeconds(600),
                new SellerAccount(7L,"123",token),new BigDecimal("7.50"),true);
    }
    private String preference() {
        return """
                {"id":"pref","collector_id":123,"client_id":"client","external_reference":"reference",
                 "init_point":"https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=pref",
                 "sandbox_init_point":"https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=pref",
                 "items":[{"currency_id":"BRL","quantity":1,"unit_price":150.00}],"marketplace_fee":7.50,
                 "expires":true,"expiration_date_to":"%s","preference_expired":false,
                 "back_urls":{"success":"https://wodz.test/pagamento/ABC?checkout=20&retorno=sucesso",
                              "pending":"https://wodz.test/pagamento/ABC?checkout=20&retorno=pendente",
                              "failure":"https://wodz.test/pagamento/ABC?checkout=20&retorno=erro"}}
                """.formatted(OffsetDateTime.now().plusMinutes(10));
    }
    @Test void criaPreferenciaHospedadaSemCartaoCpfPixOuSdk() {
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization","Bearer APP_USR-test"))
            .andExpect(jsonPath("$.items[0].unit_price").value(150.00))
            .andExpect(jsonPath("$.marketplace_fee").value(7.50))
            .andExpect(jsonPath("$.external_reference").value("reference"))
            .andExpect(jsonPath("$.back_urls.success").value("https://wodz.test/pagamento/ABC?checkout=20&retorno=sucesso"))
            .andExpect(jsonPath("$.auto_return").value("approved"))
            .andExpect(jsonPath("$.expiration_date_to").value(org.hamcrest.Matchers.matchesPattern(
                    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}-03:00")))
            .andExpect(jsonPath("$.payer").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist())
            .andExpect(jsonPath("$.payment_method_id").doesNotExist())
            .andRespond(withSuccess(preference(),MediaType.APPLICATION_JSON));
        assertEquals("https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=pref",
                gateway.createCheckout(command("APP_USR-test")).url());server.verify();
    }
    @Test void appUsrDeUsuarioDeTesteUsaInitPointSemTrocarCredenciais() {
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
            .andRespond(withSuccess(preference(),MediaType.APPLICATION_JSON));
        assertTrue(gateway.createCheckout(command("APP_USR-test")).url().startsWith("https://www."));server.verify();
    }
    @Test void rejeitaRedirecionamentoForaDoCheckoutOficial() {
        for(String url:new String[]{"http://www.mercadopago.com.br/checkout/v1", "https://www.mercadopago.com.br.attacker.test/checkout/v1",
                "javascript:alert(1)","https://user@www.mercadopago.com.br/checkout/v1","https://www.mercadopago.com.br:8443/checkout/v1",
                "https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=p",
                "https://www.mercadopago.com.br/other"}) assertFalse(MercadoPagoGateway.safeCheckoutUrl(url),url);
    }
    @Test void tokenTestNaoCriaPreferenciaCheckoutPro() {
        assertThrows(ApiException.class, () -> gateway.createCheckout(command("TEST-test")));
        server.verify();
    }
    @Test void respostaSomenteComSandboxNaoERetornadaAoComprador() {
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
                .andRespond(withSuccess("{\"id\":\"pref\",\"sandbox_init_point\":\"https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=p\"}",MediaType.APPLICATION_JSON));
        assertThrows(ApiException.class, () -> gateway.createCheckout(command("APP_USR-test")));
        server.verify();
    }
    @Test void dadosIncompletosNuncaConfirmamPagamento() throws Exception {
        ObjectMapper json=new ObjectMapper();
        assertThrows(ApiException.class,()->MercadoPagoGateway.parse(json.readTree("{\"id\":300,\"status\":\"approved\"}")));
        var p=MercadoPagoGateway.parse(json.readTree("""
                {"id":300,"status":"approved","transaction_amount":150.00,"live_mode":false,"external_reference":"reference",
                 "collector_id":123,"currency_id":"BRL","payment_type_id":"account_money","date_approved":"2026-10-10T10:00:00-03:00"}
                """));
        assertEquals(PaymentStatus.APPROVED,p.status());assertEquals("123",p.collectorId());assertFalse(p.liveMode());
        assertEquals(0,new BigDecimal("150.00").compareTo(p.amount()));
    }
    @Test void preferenciaDeOutroVendedorAplicacaoOuInscricaoNaoAbreCheckout() throws Exception {
        for (String field : new String[]{"collector_id", "client_id", "external_reference", "id", "init_point"}) {
            fixture();
            var response = new ObjectMapper().readTree(preference());
            ((com.fasterxml.jackson.databind.node.ObjectNode) response).put(field, "other");
            server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
                    .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
            assertThrows(ApiException.class, () -> gateway.createCheckout(command("APP_USR-test")), field);
            server.verify();
        }
    }
    @Test void valorMoedaQuantidadeETaxaDaPreferenciaPrecisamCorresponder() throws Exception {
        for (String wrong : new String[]{preference().replace("150.00", "151.00"),
                preference().replace("BRL", "USD"), preference().replace("\"quantity\":1", "\"quantity\":2"),
                preference().replace("7.50", "8.00"), preference().replace("\"marketplace_fee\":7.50,", "")}) {
            fixture();
            server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
                    .andRespond(withSuccess(wrong, MediaType.APPLICATION_JSON));
            assertThrows(ApiException.class, () -> gateway.createCheckout(command("APP_USR-test")));
            server.verify();
        }
    }
    @Test void retomadaConsultaPreferenciaAtualNoProvedorComTokenDoVendedor() {
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences/pref"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer APP_USR-test"))
                .andRespond(withSuccess(preference(), MediaType.APPLICATION_JSON));
        assertEquals("pref", gateway.resumeCheckout("pref", command("APP_USR-test")).preferenceId());
        server.verify();
    }
    @Test void retomadaAceitaPrecoDecimalTextualDocumentadoPeloProvedor() {
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences/pref"))
                .andRespond(withSuccess(preference().replace("\"unit_price\":150.00", "\"unit_price\":\"150.00\""), MediaType.APPLICATION_JSON));
        assertEquals("pref", gateway.resumeCheckout("pref", command("APP_USR-test")).preferenceId());
        server.verify();
    }
    @Test void preferenciaExpiradaOuRetornoAntigoNaoEReutilizada() {
        for (String wrong : new String[]{preference().replace("\"preference_expired\":false", "\"preference_expired\":true"),
                preference().replace("https://wodz.test", "https://old-wodz.test"),
                preference().replaceFirst("\"expiration_date_to\":\"[^\"]*\"", "\"expiration_date_to\":\"2020-01-01T00:00:00-03:00\"")}) {
            fixture();
            server.expect(requestTo("https://api.mercadopago.com/checkout/preferences/pref"))
                    .andRespond(withSuccess(wrong, MediaType.APPLICATION_JSON));
            assertNull(gateway.resumeCheckout("pref", command("APP_USR-test")));
            server.verify();
        }
    }
    @Test void preferenciaInexistentePermiteRecriarMasFalhaDeAcessoNao() {
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences/pref"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertNull(gateway.resumeCheckout("pref", command("APP_USR-test"))); server.verify();
        fixture();
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences/pref"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThrows(ApiException.class, () -> gateway.resumeCheckout("pref", command("APP_USR-test")));
        server.verify();
    }
    @Test void erroDoProvedorTemDiagnosticoFixoSemExporCorpoOuCredenciais() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MercadoPagoGateway.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"message\":\"invalid_collector_id\",\"private\":\"PRIVATE-CREDENTIAL\"}"));
            assertThrows(ApiException.class, () -> gateway.createCheckout(command("APP_USR-test")));
            assertTrue(logs.list.getFirst().getFormattedMessage().contains("motivo=invalid_collector_id"));
            assertFalse(logs.list.getFirst().getFormattedMessage().contains("PRIVATE"));
            server.verify();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
}
