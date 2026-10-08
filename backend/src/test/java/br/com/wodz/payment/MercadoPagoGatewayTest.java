package br.com.wodz.payment;

import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.dto.PaymentDtos.Payer;
import com.mercadopago.client.preference.PreferenceRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MercadoPagoGatewayTest {

    private static final Payer PAYER = new Payer("Ana", "Souza", "ana@wodz.dev", "52998224725", "81999998888");

    private static PaymentCommand command(String returnUrl) {
        return new PaymentCommand(7L, "wodz-payment-7", new BigDecimal("150.00"), "Inscrição Equipe Alfa",
                PAYER, "team:5:payment:7", returnUrl);
    }

    private static GatewayPayment attempt(String id, PaymentStatus status) {
        return new GatewayPayment(id, status, null, "credit_card", 1, null, null, "team:5:payment:7");
    }

    @Test
    void preferenciaLevaPagadorReferenciaESemBoleto() {
        PreferenceRequest req = MercadoPagoGateway.preferenceRequest(
                command("https://wodz.com.br/pagamento/ABCDEFGHJK"), "https://wodz.com.br/api/webhooks/mercadopago");

        assertEquals("team:5:payment:7", req.getExternalReference());
        assertEquals(new BigDecimal("150.00"), req.getItems().get(0).getUnitPrice());
        assertEquals("CPF", req.getPayer().getIdentification().getType());
        assertEquals("52998224725", req.getPayer().getIdentification().getNumber());
        assertEquals("81", req.getPayer().getPhone().getAreaCode());
        assertEquals("999998888", req.getPayer().getPhone().getNumber());
        assertEquals("ticket", req.getPaymentMethods().getExcludedPaymentTypes().get(0).getId());
        assertEquals(12, req.getPaymentMethods().getInstallments());
        assertEquals("https://wodz.com.br/pagamento/ABCDEFGHJK?checkout=success", req.getBackUrls().getSuccess());
        assertEquals("approved", req.getAutoReturn());
        assertEquals("https://wodz.com.br/api/webhooks/mercadopago", req.getNotificationUrl());
    }

    @Test
    void urlLocalNaoUsaRetornoAutomatico() {
        PreferenceRequest req = MercadoPagoGateway.preferenceRequest(
                command("http://localhost:8081/pagamento/ABCDEFGHJK"), "");

        assertNull(req.getAutoReturn());
        assertNull(req.getNotificationUrl());
        assertEquals("http://localhost:8081/pagamento/ABCDEFGHJK?checkout=failure", req.getBackUrls().getFailure());
    }

    @Test
    void soHttpsPublicoContaComoUrlPublica() {
        assertTrue(MercadoPagoGateway.isPublicHttps("https://wodz.com.br/pagamento/X"));
        assertFalse(MercadoPagoGateway.isPublicHttps("http://wodz.com.br/pagamento/X"));
        assertFalse(MercadoPagoGateway.isPublicHttps("https://localhost:8081/pagamento/X"));
        assertFalse(MercadoPagoGateway.isPublicHttps("https://127.0.0.1/pagamento/X"));
        assertFalse(MercadoPagoGateway.isPublicHttps("nada"));
    }

    @Test
    void credencialDeTesteAbreSandboxEAsDemaisOInitPoint() {
        assertEquals("sbx", MercadoPagoGateway.checkoutUrl("init", "sbx", "TEST-123"));
        assertEquals("init", MercadoPagoGateway.checkoutUrl("init", "sbx", "APP_USR-123"));
    }

    @Test
    void tentativaAprovadaTemPrioridadeSobreAsDemais() {
        List<GatewayPayment> newestFirst = List.of(
                attempt("3", PaymentStatus.REJECTED),
                attempt("2", PaymentStatus.APPROVED),
                attempt("1", PaymentStatus.PENDING));
        assertEquals("2", MercadoPagoGateway.mostRelevant(newestFirst).orElseThrow().providerPaymentId());
    }

    @Test
    void semAprovadaValeAEmAndamentoOuAMaisRecente() {
        assertEquals("1", MercadoPagoGateway.mostRelevant(List.of(
                attempt("2", PaymentStatus.REJECTED), attempt("1", PaymentStatus.PENDING))).orElseThrow().providerPaymentId());
        assertEquals("2", MercadoPagoGateway.mostRelevant(List.of(
                attempt("2", PaymentStatus.REJECTED), attempt("1", PaymentStatus.CANCELLED))).orElseThrow().providerPaymentId());
        assertTrue(MercadoPagoGateway.mostRelevant(List.of()).isEmpty());
    }

    @Test
    void statusDoMercadoPagoViraStatusLocal() {
        assertEquals(PaymentStatus.APPROVED, MercadoPagoGateway.mapStatus("approved"));
        assertEquals(PaymentStatus.PENDING, MercadoPagoGateway.mapStatus("in_process"));
        assertEquals(PaymentStatus.REJECTED, MercadoPagoGateway.mapStatus("rejected"));
        assertEquals(PaymentStatus.REFUNDED, MercadoPagoGateway.mapStatus("charged_back"));
        assertEquals(PaymentStatus.PENDING, MercadoPagoGateway.mapStatus(null));
    }

    @Test
    void detalheLegivelGuardaOCodigoDoMercadoPago() {
        assertEquals("O Mercado Pago recusou o pagamento (cc_rejected_insufficient_amount)",
                MercadoPagoGateway.statusDetail("rejected", "cc_rejected_insufficient_amount"));
        assertEquals("Aguardando o pagamento do Pix (pending_waiting_transfer)",
                MercadoPagoGateway.statusDetail("pending", "pending_waiting_transfer"));
    }
}
