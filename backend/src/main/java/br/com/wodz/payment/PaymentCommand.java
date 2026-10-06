package br.com.wodz.payment;

import br.com.wodz.dto.PaymentDtos.Payer;

import java.math.BigDecimal;

/**
 * Pedido de checkout. payer já vem validado e só com dígitos em CPF e telefone;
 * returnUrl é a página de pagamento da inscrição (sem query string).
 */
public record PaymentCommand(
        Long localPaymentId,
        String idempotencyKey,
        BigDecimal amount,
        String description,
        Payer payer,
        String externalReference,
        String returnUrl) {
}
