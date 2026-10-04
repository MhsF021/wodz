package br.com.wodz.payment;

import br.com.wodz.domain.PaymentStatus;

import java.time.Instant;

/** Resposta normalizada do provedor. */
public record GatewayPayment(
        String providerPaymentId,
        PaymentStatus status,
        String statusDetail,
        String pixQrCode,
        String pixQrCodeBase64,
        String boletoUrl,
        String boletoBarcode,
        Instant expiresAt,
        String cardLastFour) {
}
