package br.com.wodz.dto;
import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.TeamStatus;
import java.math.BigDecimal;
import java.time.Instant;
public final class PaymentDtos {
    private PaymentDtos() { }
    public record PaymentDto(Long id, PaymentMethod method, PaymentStatus status, String statusDetail,
                             BigDecimal amount, Instant paidAt, Instant createdAt) { }
    public record CheckoutDto(Long id, String url, Instant expiresAt) { }
    public record RegistrationSummary(String code, Long teamId, String teamName, String championshipName,
                                      String championshipSlug, String categoryName, BigDecimal amount,
                                      TeamStatus teamStatus, boolean championshipStarted, PaymentDto lastPayment, Long checkoutId) { }
    public record PublicConfig(String paymentProvider, String googleClientId, boolean mercadoPagoTestMode) { }
}
