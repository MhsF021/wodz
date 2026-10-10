package br.com.wodz.payment;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
/** Informações obtidas exclusivamente pela API autenticada do provedor. */
public record GatewayPayment(String providerPaymentId, PaymentStatus status, String statusDetail,
                             BigDecimal amount, String currency, String externalReference,
                             String collectorId, Boolean liveMode, PaymentMethod method,
                             Integer installments, Instant paidAt) { }
