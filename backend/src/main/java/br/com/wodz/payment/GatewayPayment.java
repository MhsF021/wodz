package br.com.wodz.payment;

import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.dto.PaymentDtos.ThreeDsChallenge;

import java.time.Instant;

/**
 * Resposta normalizada do provedor. paymentTypeId é o tipo que o provedor
 * efetivamente cobrou (credit_card, debit_card...), quando informado.
 */
public record GatewayPayment(
        String providerPaymentId,
        PaymentStatus status,
        String statusDetail,
        String pixQrCode,
        String pixQrCodeBase64,
        Instant expiresAt,
        String cardLastFour,
        String paymentTypeId,
        ThreeDsChallenge threeDsChallenge) {
}
