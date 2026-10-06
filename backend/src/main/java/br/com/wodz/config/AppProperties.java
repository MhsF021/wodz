package br.com.wodz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendUrl,
        List<String> corsAllowedOrigins,
        JwtProps jwt,
        PaymentProps payment,
        SeedProps seed,
        GoogleProps google,
        /* dev | homolog | prod: homolog e prod exigem configuração de produção (EnvironmentGuard) */
        String environment,
        SecurityProps security) {

    public boolean isDev() {
        return environment == null || environment.isBlank() || "dev".equalsIgnoreCase(environment);
    }

    public record JwtProps(String secret, Duration ttl, String issuer) {
    }

    /** Pagamentos sempre pelo Checkout Pro do Mercado Pago. */
    public record PaymentProps(MercadoPagoProps mercadopago) {
    }

    /** Credenciais e endpoint de notificação do Checkout Pro. */
    public record MercadoPagoProps(String accessToken, String webhookSecret, String notificationUrl) {
    }

    public record SeedProps(boolean enabled) {
    }

    /** clientId vazio desliga o login com Google. */
    public record GoogleProps(String clientId) {
    }

    /** encryptionKey: 32 bytes em Base64 (AES-256), cifra os tokens das contas de recebimento. */
    public record SecurityProps(String encryptionKey) {
    }
}
