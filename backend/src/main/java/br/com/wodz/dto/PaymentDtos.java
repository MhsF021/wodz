package br.com.wodz.dto;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.payment.CheckoutMethod;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
public final class PaymentDtos {
    private PaymentDtos() { }
    public record PaymentDto(Long id, PaymentMethod method, PaymentStatus status, String statusDetail,
                             BigDecimal amount, Instant paidAt, Instant createdAt) { }
    public record CheckoutRequest(@NotNull CheckoutMethod method, String payerCpf, boolean termsAccepted) { }
    public record CheckoutDto(Long id, String url, Instant expiresAt, CheckoutMethod method,
                              String qrCode, String qrCodeBase64) { }
    public record RegistrationSummary(String code, Long teamId, String teamName, String championshipName,
                                      String championshipSlug, String categoryName, BigDecimal amount,
                                      TeamStatus teamStatus, boolean championshipStarted, PaymentDto lastPayment, Long checkoutId) { }
    public record PublicConfig(String paymentProvider, String googleClientId, boolean mercadoPagoTestMode) { }
}
