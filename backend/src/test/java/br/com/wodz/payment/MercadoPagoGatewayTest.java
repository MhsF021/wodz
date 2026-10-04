package br.com.wodz.payment;

import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.dto.PaymentDtos.ThreeDsChallenge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MercadoPagoGatewayTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode payment(String statusDetail, String url) throws Exception {
        return json.readTree("""
                {"id": 1, "status": "pending", "status_detail": "%s",
                 "three_ds_info": {"external_resource_url": "%s", "creq": "eyJ0aHJlZURT"}}
                """.formatted(statusDetail, url));
    }

    @Test
    void cadaCartaoTemOTipoDoMercadoPago() {
        assertEquals("credit_card", MercadoPagoGateway.cardPaymentType(PaymentMethod.CREDIT_CARD));
        assertEquals("debit_card", MercadoPagoGateway.cardPaymentType(PaymentMethod.DEBIT_CARD));
        assertNull(MercadoPagoGateway.cardPaymentType(PaymentMethod.PIX));
    }

    @Test
    void repassaDesafio3dsPendente() throws Exception {
        ThreeDsChallenge challenge = MercadoPagoGateway.challenge(
                payment("pending_challenge", "https://acs.banco.com.br/challenge"));
        assertEquals("https://acs.banco.com.br/challenge", challenge.url());
        assertEquals("eyJ0aHJlZURT", challenge.creq());
    }

    @Test
    void ignoraDesafioSemHttpsOuForaDoStatusDeDesafio() throws Exception {
        assertNull(MercadoPagoGateway.challenge(payment("pending_challenge", "http://acs.banco.com.br/challenge")));
        assertNull(MercadoPagoGateway.challenge(payment("pending_challenge", "javascript:alert(1)")));
        assertNull(MercadoPagoGateway.challenge(payment("pending_waiting_payment", "https://acs.banco.com.br/c")));
    }
}
