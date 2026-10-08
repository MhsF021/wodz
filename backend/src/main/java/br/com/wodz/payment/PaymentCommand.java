package br.com.wodz.payment;

import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.dto.PaymentDtos.CardData;
import br.com.wodz.dto.PaymentDtos.Payer;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * installments já vem validado pelo serviço (débito é sempre 1). seller é quem
 * recebe; applicationFee é a parte da plataforma (zero ou nulo = nada).
 */
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
        String externalReference,
        SellerAccount seller,
        BigDecimal applicationFee) {
}
