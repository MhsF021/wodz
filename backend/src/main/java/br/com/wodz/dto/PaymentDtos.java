package br.com.wodz.dto;

import br.com.wodz.domain.PaymentMethod;
import br.com.wodz.domain.PaymentStatus;
import br.com.wodz.domain.TeamStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** Quem paga: os dados pré-preenchem o Checkout Pro. CPF e telefone chegam com ou sem máscara. */
    public record Payer(
            @NotBlank @Size(max = 60) String firstName,
            @NotBlank @Size(max = 60) String lastName,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(max = 20) String cpf,
            @NotBlank @Size(max = 20) String phone) {
    }

    /** A forma de pagamento (Pix ou cartão) é escolhida pelo pagador no Mercado Pago. */
    public record CreatePaymentRequest(@NotNull @Valid Payer payer) {
    }

    /** checkoutUrl leva ao Checkout Pro; continua válida para retomar um pagamento pendente. */
    public record PaymentDto(Long id, PaymentMethod method, PaymentStatus status, String statusDetail,
                             BigDecimal amount, String cardLastDigits, Integer installments,
                             Instant expiresAt, Instant paidAt, Instant createdAt, String checkoutUrl) {
    }

    public record RegistrationSummary(String code, Long teamId, String teamName, String championshipName,
                                      String championshipSlug, String categoryName, BigDecimal amount,
                                      TeamStatus teamStatus, boolean championshipStarted, PaymentDto lastPayment) {
    }

    /** Configuração pública do frontend: o Client ID do login com Google (nulo se desligado). */
    public record PublicConfig(String googleClientId) {
    }
}
