package br.com.wodz.domain;

/** Meio efetivamente usado no Mercado Pago ou nos registros financeiros históricos. */
public enum PaymentMethod {
    PIX,
    CREDIT_CARD,
    DEBIT_CARD,
    /** Permitido apenas no checkout hospedado; também preserva cobranças históricas. */
    BOLETO,
    MERCADO_PAGO;

    public boolean isCard() {
        return this == CREDIT_CARD || this == DEBIT_CARD;
    }

}
