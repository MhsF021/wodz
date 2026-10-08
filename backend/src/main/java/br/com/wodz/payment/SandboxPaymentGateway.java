package br.com.wodz.payment;

import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Provedor simulado para desenvolvimento e demonstração.
 * Cartão (crédito ou débito): token "sandbox-rejected" é recusado; qualquer outro é aprovado.
 * Pix fica pendente até a simulação de pagamento.
 */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentGateway implements PaymentGateway {

    @Override
    public String providerName() {
        return "SANDBOX";
    }

    @Override
    public GatewayPayment create(PaymentCommand command) {
        String id = "SBX-" + UUID.randomUUID();
        return switch (command.method()) {
            case PIX -> new GatewayPayment(id, PaymentStatus.PENDING, "Aguardando pagamento do Pix",
                    fakePixCode(command), null, command.expiresAt(), null, null, null);
            case CREDIT_CARD, DEBIT_CARD -> {
                boolean rejected = command.card() != null && "sandbox-rejected".equals(command.card().token());
                String lastFour = command.card() == null ? null : command.card().lastFourDigits();
                String type = command.method() == PaymentMethod.DEBIT_CARD ? "debit_card" : "credit_card";
                yield rejected
                        ? new GatewayPayment(id, PaymentStatus.REJECTED, "Cartão recusado pelo emissor (simulação)",
                        null, null, null, lastFour, type, null)
                        : new GatewayPayment(id, PaymentStatus.APPROVED, "Pagamento aprovado (simulação)",
                        null, null, null, lastFour, type, null);
            }
            case BOLETO -> throw new IllegalArgumentException("Boleto não é mais aceito");
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
}
