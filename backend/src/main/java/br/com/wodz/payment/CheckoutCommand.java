package br.com.wodz.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckoutCommand(Long sessionId, String externalReference, BigDecimal amount,
                              String description, String returnUrl, Instant expiresAt,
                              SellerAccount seller, BigDecimal marketplaceFee, boolean testMode) { }
