package br.com.wodz.domain;

/** Status normalizado do pagamento, independente do gateway. */
public enum PaymentStatus {
    PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED, REFUNDED
}
