package br.com.wodz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendUrl,
        List<String> corsAllowedOrigins,
        JwtProps jwt,
        PaymentProps payment,
        SeedProps seed,
        GoogleProps google) {

    public record JwtProps(String secret, Duration ttl, String issuer) {
    }

    public record PaymentProps(String provider, Duration pixExpiration, int boletoDays, MercadoPagoProps mercadopago) {
    }

    public record MercadoPagoProps(String accessToken, String publicKey, String webhookSecret, String notificationUrl) {
    }

    public record SeedProps(boolean enabled) {
    }

    /** clientId vazio desliga o login com Google. */
    public record GoogleProps(String clientId) {
    }
}
