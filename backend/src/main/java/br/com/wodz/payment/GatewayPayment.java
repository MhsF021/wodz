package br.com.wodz.payment;

import br.com.wodz.domain.PaymentStatus;

import java.time.Instant;

/**
 * Pagamento feito no provedor, normalizado. paymentTypeId é o tipo que o pagador
 * escolheu no checkout (bank_transfer para Pix, credit_card, debit_card...).
 */
public record GatewayPayment(
        String providerPaymentId,
        PaymentStatus status,
        String statusDetail,
        String paymentTypeId,
        Integer installments,
        String cardLastFour,
        Instant expiresAt,
        String externalReference) {
}
