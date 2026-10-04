package br.com.wodz.payment;

import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.dto.PaymentDtos.CardData;
import br.com.wodz.dto.PaymentDtos.Payer;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentCommand(
        Long localPaymentId,
        String idempotencyKey,
        BigDecimal amount,
        String description,
        PaymentMethod method,
        Payer payer,
        CardData card,
        Instant expiresAt,
        String externalReference) {
}
