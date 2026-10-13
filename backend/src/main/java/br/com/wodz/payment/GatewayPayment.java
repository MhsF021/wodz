package br.com.wodz.payment;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
/** Informações obtidas exclusivamente pela API autenticada do provedor. */
public record GatewayPayment(String providerPaymentId, PaymentStatus status, String statusDetail,
                             BigDecimal amount, String currency, String externalReference,
                             String collectorId, Boolean liveMode, PaymentMethod method,
                             Integer installments, Instant paidAt, BigDecimal netReceivedAmount, BigDecimal refundedAmount) {
    public GatewayPayment(String providerPaymentId, PaymentStatus status, String statusDetail,
                          BigDecimal amount, String currency, String externalReference, String collectorId,
                          Boolean liveMode, PaymentMethod method, Integer installments, Instant paidAt, BigDecimal netReceivedAmount) {
        this(providerPaymentId,status,statusDetail,amount,currency,externalReference,collectorId,liveMode,method,
                installments,paidAt,netReceivedAmount,null);
    }
    public GatewayPayment(String providerPaymentId, PaymentStatus status, String statusDetail,
                          BigDecimal amount, String currency, String externalReference, String collectorId,
                          Boolean liveMode, PaymentMethod method, Integer installments, Instant paidAt) {
        this(providerPaymentId, status, statusDetail, amount, currency, externalReference, collectorId,
                liveMode, method, installments, paidAt, null, null);
    }
}
