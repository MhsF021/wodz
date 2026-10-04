package br.com.wodz.payment;

/**
 * Porta de saída para o provedor de pagamentos. A implementação ativa é
 * escolhida por app.payment.provider (sandbox | mercadopago).
 */
public interface PaymentGateway {

    /** Nome gravado em payment.provider. */
    String providerName();

    GatewayPayment create(PaymentCommand command);

    /** Consulta o status atual direto no provedor */
    default boolean supportsRemoteFetch() {
        return false;
    }

    default GatewayPayment fetch(String providerPaymentId) {
        throw new UnsupportedOperationException("Este provedor não consulta pagamentos remotamente");
    }

    /** Só o sandbox permite simular a aprovação de um pagamento pendente. */
    default boolean supportsSimulation() {
        return false;
    }

    /** Chave pública usada pelo navegador para tokenizar cartões. */
    default String publicKey() {
        return null;
    }
}
