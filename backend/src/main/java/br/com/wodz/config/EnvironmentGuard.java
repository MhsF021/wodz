package br.com.wodz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Impede que homologação ou produção subam com configuração de desenvolvimento:
 * contas de teste em produção, dados de demonstração, segredos padrão ou URLs sem HTTPS.
 * A API não inicia e lista tudo o que falta.
 */
@Component
public class EnvironmentGuard {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentGuard.class);
    private static final Set<String> ENVIRONMENTS = Set.of("dev", "homolog", "prod");
    static final String DEV_JWT_SECRET = "dev-only-secret-change-me-0123456789abcdef";

    public EnvironmentGuard(AppProperties props, Environment environment) {
        List<String> problems = problems(props);
        problems.addAll(databaseProblems(props, environment.getProperty("spring.datasource.password")));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuração inválida para APP_ENV=" + props.environment() + ":\n - "
                    + String.join("\n - ", problems));
        }
        log.info("Ambiente: {} | provedor de pagamento: {}", props.isDev() ? "dev" : props.environment(),
                props.payment().provider());
    }

    static List<String> problems(AppProperties props) {
        List<String> problems = new ArrayList<>();
        String env = props.environment() == null ? "dev" : props.environment().trim().toLowerCase(Locale.ROOT);
        if (!ENVIRONMENTS.contains(env)) {
            problems.add("APP_ENV deve ser dev, homolog ou prod");
            return problems;
        }
        AppProperties.MercadoPagoProps mp = props.payment().mercadopago();
        if (mp.testMode() && !"mercadopago".equalsIgnoreCase(props.payment().provider())) {
            problems.add("MP_TEST_MODE=true exige PAYMENT_PROVIDER=mercadopago (APIs oficiais)");
        }
        if (mp.testMode() && env.equals("prod")) {
            problems.add("MP_TEST_MODE=false em produção");
        }
        if (mp.platformFeePercent() != null && (mp.platformFeePercent().signum() < 0
                || mp.platformFeePercent().compareTo(new BigDecimal("50")) > 0)) {
            problems.add("MP_PLATFORM_FEE_PERCENT deve ficar entre 0 e 50");
        }
        if (!Set.of("disabled", "mercadopago").contains(props.payment().provider().toLowerCase(Locale.ROOT))) {
            problems.add("PAYMENT_PROVIDER deve ser disabled ou mercadopago");
        }
        if (props.payment().checkoutExpiration() == null || props.payment().checkoutExpiration().isNegative()
                || props.payment().checkoutExpiration().isZero()) {
            problems.add("CHECKOUT_EXPIRATION deve ser positivo");
        }
        if (props.jwt() == null || blank(props.jwt().issuer())) {
            problems.add("app.jwt.issuer precisa identificar o emissor dos tokens");
        }
        if (props.jwt() == null || props.jwt().ttl() == null || props.jwt().ttl().isNegative()
                || props.jwt().ttl().isZero() || props.jwt().ttl().compareTo(Duration.ofHours(24)) > 0) {
            problems.add("JWT_TTL deve ser positivo e no máximo 24h");
        }
        if (props.corsAllowedOrigins() == null || props.corsAllowedOrigins().isEmpty()
                || props.corsAllowedOrigins().stream().anyMatch(origin -> !validOrigin(origin, !env.equals("dev")))) {
            problems.add("CORS_ORIGINS exige origens exatas sem caminhos, credenciais ou curingas; fora de dev, somente HTTPS");
        }
        if (env.equals("dev")) {
            return problems;
        }

        if (!"mercadopago".equalsIgnoreCase(props.payment().provider())) {
            problems.add("PAYMENT_PROVIDER=mercadopago");
        }
        if (props.seed() != null && props.seed().enabled()) {
            problems.add("SEED_DEMO=false (o seed cria usuários com senha conhecida)");
        }
        String jwt = props.jwt() == null ? null : props.jwt().secret();
        if (jwt == null || jwt.length() < 32 || jwt.equals(DEV_JWT_SECRET) || jwt.startsWith("troque-por")) {
            problems.add("JWT_SECRET próprio com 32+ caracteres (openssl rand -base64 48)");
        }
        if (props.security() == null || blank(props.security().encryptionKey())) {
            problems.add("APP_ENCRYPTION_KEY (openssl rand -base64 32)");
        }
        if (blank(mp.clientId()) || blank(mp.clientSecret())) {
            problems.add("MP_CLIENT_ID e MP_CLIENT_SECRET da aplicação do Mercado Pago deste ambiente");
        }
        if (blank(mp.webhookSecret())) {
            problems.add("MP_WEBHOOK_SECRET (assinatura secreta do webhook)");
        }
        if (!https(mp.notificationUrl())) {
            problems.add("MP_NOTIFICATION_URL com https (ex.: https://seu-dominio/api/webhooks/mercadopago)");
        }
        if (!https(props.frontendUrl())) {
            problems.add("FRONTEND_URL com https (também é a base do redirecionamento do OAuth)");
        }
        return problems;
    }

    static List<String> databaseProblems(AppProperties props, String password) {
        if (props.isDev()) {
            return List.of();
        }
        if (blank(password) || Set.of("wodz", "postgres", "password", "changeme")
                .contains(password.trim().toLowerCase(Locale.ROOT))) {
            return List.of("DB_PASSWORD própria, sem a senha padrão de desenvolvimento");
        }
        return List.of();
    }

    private static boolean validOrigin(String value, boolean requireHttps) {
        try {
            URI origin = URI.create(value);
            return (requireHttps ? "https".equals(origin.getScheme())
                    : Set.of("http", "https").contains(origin.getScheme()))
                    && origin.getHost() != null && origin.getUserInfo() == null
                    && (origin.getPath() == null || origin.getPath().isEmpty())
                    && origin.getQuery() == null && origin.getFragment() == null;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean https(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
    }
}
