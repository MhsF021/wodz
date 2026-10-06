package br.com.wodz.domain;

/**
 * Meio de pagamento registrado. Toda cobrança nasce CHECKOUT_PRO e passa a PIX ou
 * cartão quando o Mercado Pago informa o meio que o pagador escolheu no checkout.
 */
public enum PaymentMethod {
    PIX,
    CREDIT_CARD,
    DEBIT_CARD,
    /** Checkout criado; o meio definitivo chega na consulta/notificação do Mercado Pago. */
    CHECKOUT_PRO,
    /** Descontinuado: existe só para ler cobranças antigas. Novos boletos são recusados (API e banco). */
    BOLETO
}
