package br.com.wodz.payment;

import java.util.Optional;

/**
 * Porta de saída para o provedor de pagamentos (Checkout Pro do Mercado Pago):
 * o pagador escolhe o meio e informa os dados na página do provedor.
 */
public interface PaymentGateway {

    /** Nome gravado em payment.provider. */
    String providerName();

    /** Cria o checkout e devolve a URL para onde o pagador é redirecionado. */
    String createCheckout(PaymentCommand command);

    /** Consulta um pagamento pelo id do provedor (usado pelo webhook). */
    GatewayPayment fetch(String providerPaymentId);

    /**
     * Pagamento mais relevante feito no checkout com essa referência externa;
     * vazio enquanto o pagador não concluiu nenhuma tentativa.
     */
    Optional<GatewayPayment> findByExternalReference(String externalReference);
}
