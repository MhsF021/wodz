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

    public record PaymentProps(String provider, Duration pixExpiration, MercadoPagoProps mercadopago) {
    }

    /**
     * Mercado Pago em modelo de split 1:1: cada organizador conecta a própria conta
     * por OAuth (clientId/clientSecret da aplicação) e as cobranças usam o token dele.
     * publicKey é da aplicação da plataforma (tokenização do cartão no navegador).
     * threeDSecure envia three_d_secure_mode=optional. platformFeePercent vira o
     * application_fee de cada pagamento (0 = tudo para o organizador).
     * cardPublicKeySource: "platform" (padrão, como na documentação do split) ou "seller".
     */
    public record MercadoPagoProps(String publicKey, String webhookSecret, String notificationUrl,
                                   boolean threeDSecure, String clientId, String clientSecret,
                                   BigDecimal platformFeePercent, String cardPublicKeySource) {

        public boolean useSellerPublicKey() {
            return "seller".equalsIgnoreCase(cardPublicKeySource);
        }
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
