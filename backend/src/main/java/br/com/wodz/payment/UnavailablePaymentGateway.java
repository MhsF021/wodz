package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "disabled", matchIfMissing = true)
public class UnavailablePaymentGateway implements PaymentGateway {
    public String providerName() { return "DISABLED"; }
    public GatewayCheckout createCheckout(CheckoutCommand command) {
        throw ApiException.unprocessable("O pagamento pelo Mercado Pago ainda não está disponível. Fale com a organização.");
    }
}
