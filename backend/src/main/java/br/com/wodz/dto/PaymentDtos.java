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
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** Endereço do pagador: exigido pelo Mercado Pago para boleto registrado. */
    public record Address(
            @NotBlank String zipCode,
            @NotBlank String street,
            @NotBlank String number,
            @NotBlank String neighborhood,
            @NotBlank String city,
            @NotBlank @Size(min = 2, max = 2) String state) {
    }

    public record Payer(
            @NotBlank @Size(max = 60) String firstName,
            @NotBlank @Size(max = 60) String lastName,
            @NotBlank @Email String email,
            @NotBlank String cpf,
            @Valid Address address) {
    }

    /**
     * Dados do cartão já tokenizados no navegador (Mercado Pago Card Brick).
     * O número do cartão nunca passa pelo backend.
     */
    public record CardData(
            @NotBlank String token,
            String paymentMethodId,
            String issuerId,
            @Min(1) @Max(12) Integer installments,
            @Size(max = 4) String lastFourDigits) {
    }

    public record CreatePaymentRequest(@NotNull PaymentMethod method, @NotNull @Valid Payer payer, @Valid CardData card) {
    }

    public record PaymentDto(Long id, PaymentMethod method, PaymentStatus status, String statusDetail,
                             BigDecimal amount, String pixQrCode, String pixQrCodeBase64, String boletoUrl,
                             String boletoBarcode, String cardLastDigits, Integer installments,
                             Instant expiresAt, Instant paidAt, Instant createdAt) {
    }

    public record RegistrationSummary(String code, Long teamId, String teamName, String championshipName,
                                      String championshipSlug, String categoryName, BigDecimal amount,
                                      TeamStatus teamStatus, boolean championshipStarted, PaymentDto lastPayment) {
    }

    /** Configuração pública do frontend: pagamento e o Client ID do login com Google (nulo se desligado). */
    public record PublicConfig(String paymentProvider, String mercadoPagoPublicKey, boolean paymentSimulation,
                               String googleClientId) {
    }
}
