package br.com.wodz.domain;

/** Formas de pagamento. Novas cobranças só aceitam Pix e cartão (crédito ou débito). */
public enum PaymentMethod {
    PIX,
    CREDIT_CARD,
    DEBIT_CARD,
    /** Descontinuado: existe só para ler cobranças antigas. Novos boletos são recusados (API e banco). */
    BOLETO;

    public boolean isCard() {
        return this == CREDIT_CARD || this == DEBIT_CARD;
    }

    /** Se pode ser usado para gerar uma nova cobrança. */
    public boolean isAvailable() {
        return this != BOLETO;
    }
}
