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
                 "payment_methods":{"excluded_payment_methods":[{"id":"pix"}]},
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
            .andExpect(jsonPath("$.payment_methods.excluded_payment_methods[0].id").value("pix"))
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
    @Test void sucessoCorrelacionaCriacaoERetomadaSemExporDadosPrivados() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MercadoPagoGateway.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            CheckoutCommand original = command("APP_USR-PRIVATE-ACCESS");
            CheckoutCommand c = new CheckoutCommand(original.sessionId(), "PRIVATE-REFERENCE", original.amount(),
                    "PRIVATE-DESCRIPTION", original.returnUrl(), original.expiresAt(), original.seller(),
                    original.marketplaceFee(), original.testMode());
            var response = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(preference());
            response.put("external_reference", c.externalReference());
            response.put("private", "PRIVATE-RESPONSE");
            response.putObject("payer").put("email", "PRIVATE-BUYER@example.invalid");
            String expiresAt = OffsetDateTime.parse(response.path("expiration_date_to").asText()).toInstant().toString();
            String[] requestIds = {"855a6fc2-864f-4f47-9423-b5ef5e0af4d6", "07b61040-9532-439f-9e15-8f7e656f7035"};
            for (int i = 0; i < requestIds.length; i++) {
                server.expect(requestTo("https://api.mercadopago.com/checkout/preferences" + (i == 0 ? "" : "/pref")))
                        .andExpect(method(i == 0 ? HttpMethod.POST : HttpMethod.GET))
                        .andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON)
                                .header("X-Request-ID", requestIds[i]));
            }
            for (int i = 0; i < requestIds.length; i++) {
                assertEquals("pref", (i == 0 ? gateway.createCheckout(c) : gateway.resumeCheckout("pref", c)).preferenceId());
            }
            assertEquals(2, logs.list.size());
            for (int i = 0; i < logs.list.size(); i++) {
                var entry = logs.list.get(i);
                assertEquals(ch.qos.logback.classic.Level.INFO, entry.getLevel());
                String message = entry.getFormattedMessage();
                assertTrue(message.contains("action=" + (i == 0 ? "created" : "resumed")));
                assertTrue(message.contains("checkout=20 preference=pref collector=123 client=client"));
                assertTrue(message.contains("expires_at=" + expiresAt));
                assertTrue(message.contains("request_id=" + requestIds[i]));
                assertFalse(message.contains("PRIVATE"));
                assertFalse(message.contains("https://"));
                assertFalse(message.contains("ABC"));
                assertFalse(message.contains("external_reference"));
            }
            server.verify();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    @Test void headerAusenteOuArbitrarioNaoVazaNemImpedeCriacao() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MercadoPagoGateway.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
                    .andRespond(withSuccess(preference(), MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
                    .andRespond(withSuccess(preference(), MediaType.APPLICATION_JSON).header("X-Request-ID", "PRIVATE-HEADER"));
            for (int i = 0; i < 2; i++) assertNotNull(gateway.createCheckout(command("APP_USR-test")));
            assertEquals(2, logs.list.size());
            for (var entry : logs.list) {
                assertTrue(entry.getFormattedMessage().contains("request_id=unavailable"));
                assertFalse(entry.getFormattedMessage().contains("PRIVATE"));
            }
            server.verify();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    @Test void preferenciaInvalidaOuExpiradaNaoProduzLogDeSucesso() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MercadoPagoGateway.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            server.expect(requestTo("https://api.mercadopago.com/checkout/preferences"))
                    .andRespond(withSuccess(preference().replace("\"client_id\":\"client\"", "\"client_id\":\"other\""), MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.mercadopago.com/checkout/preferences/pref"))
                    .andRespond(withSuccess(preference().replace("\"preference_expired\":false", "\"preference_expired\":true"), MediaType.APPLICATION_JSON));
            assertThrows(ApiException.class, () -> gateway.createCheckout(command("APP_USR-test")));
            assertNull(gateway.resumeCheckout("pref", command("APP_USR-test")));
            assertFalse(logs.list.stream().anyMatch(entry -> entry.getLevel().equals(ch.qos.logback.classic.Level.INFO)));
            server.verify();
        } finally { logger.detachAppender(logs); logs.stop(); }
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
    private String pixResponse(String base64) {
        return """
                {"id":900,"transaction_amount":150.00,"currency_id":"BRL","external_reference":"reference",
                 "collector_id":123,"live_mode":false,"status":"pending","payment_method_id":"pix",
                 "payment_type_id":"bank_transfer","date_of_expiration":"%s",
                 "point_of_interaction":{"transaction_data":{"qr_code":"000201-test","qr_code_base64":"%s"}}}
                """.formatted(OffsetDateTime.now().plusMinutes(31),base64);
    }






    @Test void recusa401NasConsultasNaoExpoeCredenciais() {
        var seller = command("APP_USR-test").seller();
        String[] urls = {
                "https://api.mercadopago.com/v1/payments/900",
                "https://api.mercadopago.com/v1/payments/search?external_reference=reference&sort=date_created&criteria=asc&limit=50&offset=0",
                "https://api.mercadopago.com/checkout/preferences/pref"
        };
        org.junit.jupiter.api.function.Executable[] operations = {
                () -> gateway.fetch("900", seller),
                () -> gateway.search("reference", seller),
                () -> gateway.resumeCheckout("pref", command("APP_USR-test"))
        };
        for (int i = 0; i < operations.length; i++) {
            server.reset();
            server.expect(requestTo(urls[i])).andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"message\":\"Unauthorized use of live credentials\"}"));
            ApiException error = assertThrows(ApiException.class, operations[i]);
            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
            server.verify();
        }
    }


    @Test void falhaNaBuscaLogaOperacaoReferenciaERequestIdSemDadosPrivadosOuLogDuplicado() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MercadoPagoGateway.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            String reference = "187558cf-63b5-4671-9384-3f8b3cc1eb5d";
            String requestId = "f4efb40d-7c98-4aa7-bca3-c35cb18d0aa8";
            String[] references = {reference, "PRIVATE-REFERENCE"};
            for (int i = 0; i < references.length; i++) {
                String searchReference = references[i];
                server.reset();
                server.expect(request -> assertEquals("/v1/payments/search", request.getURI().getPath()))
                        .andExpect(method(HttpMethod.GET))
                        .andRespond(withStatus(i == 0 ? HttpStatus.UNAUTHORIZED : HttpStatus.INTERNAL_SERVER_ERROR)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Request-ID", i == 0 ? requestId : "PRIVATE-HEADER")
                                .body(i == 0
                                        ? "{\"message\":\"Unauthorized use of live credentials\",\"private\":\"PRIVATE-BODY\"}"
                                        : "{\"message\":\"PRIVATE-BODY\"}"));
                ApiException error = assertThrows(ApiException.class, () -> gateway.search(
                        searchReference, command("APP_USR-PRIVATE-ACCESS").seller()));
                assertEquals(i + 1, logs.list.size());
                String message = logs.list.getLast().getFormattedMessage();
                assertTrue(message.contains("operation=search action=failed"));
                assertTrue(message.contains("reference=" + (i == 0 ? reference : "unknown")));
                assertTrue(message.contains(i == 0 ? "HTTP=401 motivo=unauthorized_live_credentials" : "HTTP=500 motivo=provider_error"));
                assertTrue(message.contains("request_id=" + (i == 0 ? requestId : "unavailable")));
                assertFalse(message.contains("PRIVATE"));
                assertFalse(message.contains("https://"));
                assertFalse(java.util.Arrays.toString(logs.list.getLast().getArgumentArray()).contains("PRIVATE"));
                server.verify();
            }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void atualizarPreferenciaLegadaExcluiPixSemCriarOutroCheckout() {
        String legacy=preference().replace("\"excluded_payment_methods\":[{\"id\":\"pix\"}]","\"excluded_payment_methods\":[]");
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences/pref"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(legacy,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.mercadopago.com/checkout/preferences/pref"))
                .andExpect(method(HttpMethod.PUT)).andExpect(jsonPath("$.payment_methods.excluded_payment_methods[0].id").value("pix"))
                .andRespond(withSuccess(preference(),MediaType.APPLICATION_JSON));
        assertEquals("pref",gateway.resumeCheckout("pref",command("APP_USR-test")).preferenceId()); server.verify();
    }
}
