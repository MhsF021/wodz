package br.com.wodz.dto;

import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.TeamStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record Payer(
            @NotBlank @Size(max = 60) String firstName,
            @NotBlank @Size(max = 60) String lastName,
            @NotBlank @Email String email,
            @NotBlank String cpf) {
    }

    /**
     * Dados do cartão já tokenizados no navegador (Mercado Pago Card Brick).
     * O número do cartão nunca passa pelo backend. Crédito ou débito é definido
     * pelo método do pagamento e conferido no servidor, não por este objeto.
     */
    public record CardData(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String token,
            @Size(max = 30) @Pattern(regexp = "[a-z0-9_]+") String paymentMethodId,
            @Size(max = 20) @Pattern(regexp = "[0-9]+") String issuerId,
            @Min(1) @Max(12) Integer installments,
            @Pattern(regexp = "[0-9]{4}") String lastFourDigits) {
    }

    public record CreatePaymentRequest(@NotNull PaymentMethod method, @NotNull @Valid Payer payer, @Valid CardData card) {
    }

    /**
     * Desafio 3DS do emissor do cartão: o navegador envia creq por POST para url
     * num iframe. Só vem na resposta da criação e não é gravado no banco.
     */
    public record ThreeDsChallenge(String url, String creq) {
    }

    public record PaymentDto(Long id, PaymentMethod method, PaymentStatus status, String statusDetail,
                             BigDecimal amount, String pixQrCode, String pixQrCodeBase64,
                             String cardLastDigits, Integer installments,
                             Instant expiresAt, Instant paidAt, Instant createdAt,
                             ThreeDsChallenge threeDsChallenge) {
    }

    public record RegistrationSummary(String code, Long teamId, String teamName, String championshipName,
                                      String championshipSlug, String categoryName, BigDecimal amount,
                                      TeamStatus teamStatus, boolean championshipStarted, PaymentDto lastPayment) {
    }

    /** Configuração pública do frontend: pagamento e o Client ID do login com Google (nulo se desligado). */
    public record PublicConfig(String paymentProvider, String mercadoPagoPublicKey, boolean paymentSimulation,
                               String googleClientId, boolean mercadoPagoTestMode) {
    }
}
