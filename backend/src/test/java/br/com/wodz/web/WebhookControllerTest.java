package br.com.wodz.web;

import br.com.wodz.config.AppProperties;
import br.com.wodz.service.PaymentService;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookControllerTest {
    private AppProperties props(String card) {
        return new AppProperties(null,null,null,new AppProperties.PaymentProps("mercadopago",Duration.ofMinutes(31),
                new AppProperties.MercadoPagoProps(card,null,"client","secret",BigDecimal.ZERO,true)),null,null,"dev",null);
    }
    private String signature(String secret) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "ts=123,v1="+HexFormat.of().formatHex(mac.doFinal("id:900;request-id:req;ts:123;".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void validaSomenteSegredoDoCheckoutSemPermitirAssinaturaVazia() throws Exception {
        var payments=mock(PaymentService.class); var controller=new WebhookController(payments,props("card-secret"));
        assertEquals(401,controller.mercadoPago("900","payment",null,"req",null).getStatusCode().value());
        assertEquals(401,controller.mercadoPago("900","payment",signature("other"),"req",null).getStatusCode().value());
        verifyNoInteractions(payments);
        assertEquals(200,controller.mercadoPago("900","payment",signature("card-secret"),"req",null).getStatusCode().value());
        assertEquals(401,controller.mercadoPago("900","payment",signature("removed-secret"),"req",null).getStatusCode().value());
        verify(payments,times(1)).syncFromProvider("900",null);
        var single=new WebhookController(payments,props("card-secret"));
        assertEquals(401,single.mercadoPago("900","payment",null,"req",null).getStatusCode().value());
    }
}
