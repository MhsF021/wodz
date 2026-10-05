package br.com.wodz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Impede que homologação ou produção subam com configuração de desenvolvimento:
 * sandbox de pagamento, dados de demonstração, segredos padrão ou URLs sem HTTPS.
 * A API não inicia e lista tudo o que falta.
 */
@Component
public class EnvironmentGuard {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentGuard.class);
    private static final Set<String> ENVIRONMENTS = Set.of("dev", "homolog", "prod");
    static final String DEV_JWT_SECRET = "dev-only-secret-change-me-0123456789abcdef";

    public EnvironmentGuard(AppProperties props) {
        List<String> problems = problems(props);
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
        if (mp.platformFeePercent() != null && (mp.platformFeePercent().signum() < 0
                || mp.platformFeePercent().compareTo(new BigDecimal("50")) > 0)) {
            problems.add("MP_PLATFORM_FEE_PERCENT deve ficar entre 0 e 50");
        }
        if (mp.cardPublicKeySource() != null && !Set.of("platform", "seller")
                .contains(mp.cardPublicKeySource().toLowerCase(Locale.ROOT))) {
            problems.add("MP_CARD_PUBLIC_KEY_SOURCE deve ser platform ou seller");
        }
        if (env.equals("dev")) {
            return problems;
        }

        if (!"mercadopago".equalsIgnoreCase(props.payment().provider())) {
            problems.add("PAYMENT_PROVIDER=mercadopago (o sandbox gera cobranças fictícias)");
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
        if (blank(mp.publicKey())) {
            problems.add("MP_PUBLIC_KEY (credencial de produção da aplicação)");
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean https(String url) {
        return url != null && url.startsWith("https://") && url.length() > "https://".length();
    }
}
