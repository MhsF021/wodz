package br.com.wodz.payment;

import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.dto.PaymentDtos.CardData;
import br.com.wodz.dto.PaymentDtos.Payer;

import java.math.BigDecimal;
import java.time.Instant;

/** installments já vem validado pelo serviço (débito é sempre 1). */
public record PaymentCommand(
        Long localPaymentId,
        String idempotencyKey,
        BigDecimal amount,
        String description,
        PaymentMethod method,
        Payer payer,
        CardData card,
        Integer installments,
        Instant expiresAt,
        String externalReference) {
}
