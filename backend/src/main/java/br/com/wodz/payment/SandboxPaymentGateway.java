package br.com.wodz.payment;

import br.com.wodz.domain.PaymentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Provedor simulado para desenvolvimento e demonstração.
 * Cartão: token "sandbox-rejected" é recusado; qualquer outro é aprovado.
 * Pix e boleto ficam pendentes até a simulação de pagamento.
 */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentGateway implements PaymentGateway {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String providerName() {
        return "SANDBOX";
    }

    @Override
    public GatewayPayment create(PaymentCommand command) {
        String id = "SBX-" + UUID.randomUUID();
        return switch (command.method()) {
            case PIX -> new GatewayPayment(id, PaymentStatus.PENDING, "Aguardando pagamento do Pix",
                    fakePixCode(command), null, null, null, command.expiresAt(), null);
            case BOLETO -> new GatewayPayment(id, PaymentStatus.PENDING, "Aguardando compensação do boleto",
                    null, null, null, fakeBarcode(), command.expiresAt(), null);
            case CREDIT_CARD -> {
                boolean rejected = command.card() != null && "sandbox-rejected".equals(command.card().token());
                String lastFour = command.card() == null ? null : command.card().lastFourDigits();
                yield rejected
                        ? new GatewayPayment(id, PaymentStatus.REJECTED, "Cartão recusado pelo emissor (simulação)",
                        null, null, null, null, null, lastFour)
                        : new GatewayPayment(id, PaymentStatus.APPROVED, "Pagamento aprovado (simulação)",
                        null, null, null, null, null, lastFour);
            }
        };
    }

    @Override
    public boolean supportsSimulation() {
        return true;
    }

    private static String fakePixCode(PaymentCommand command) {
        return "00020126580014BR.GOV.BCB.PIX0136" + UUID.randomUUID()
                + "52040000530398654" + String.format("%02d", command.amount().toPlainString().length())
                + command.amount().toPlainString() + "5802BR5912WODZ SANDBOX6006RECIFE62070503***6304SBX0";
    }

    private static String fakeBarcode() {
        StringBuilder sb = new StringBuilder("23793");
        while (sb.length() < 47) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
