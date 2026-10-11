package br.com.wodz.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentGuardTest {
    private AppProperties props(String environment, String provider, boolean testMode) {
        return new AppProperties("https://local.wodz.test",List.of("https://local.wodz.test"),
                new AppProperties.JwtProps("a-custom-secret-with-more-than-32-characters",Duration.ofHours(12),"wodz"),
                new AppProperties.PaymentProps(provider,Duration.ofMinutes(30),
                        new AppProperties.MercadoPagoProps("webhook-secret", "https://local.wodz.test/api/webhooks/mercadopago", "client-id", "client-secret", BigDecimal.ZERO, testMode)),
                new AppProperties.SeedProps(false),null,environment,
                new AppProperties.SecurityProps("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    }

    @Test
    void permiteApiOficialEmModoDeTesteNoDesenvolvimentoENaHomologacao() {
        assertTrue(EnvironmentGuard.problems(props("dev","mercadopago",true)).isEmpty());
        assertTrue(EnvironmentGuard.problems(props("homolog","mercadopago",true)).isEmpty());
    }

    @Test
    void naoPermiteApresentarSimuladorLocalComoTesteOficial() {
        assertTrue(EnvironmentGuard.problems(props("dev","sandbox",true)).stream()
                .anyMatch(p -> p.contains("PAYMENT_PROVIDER=mercadopago")));
    }

    @Test
    void impedeSubirProducaoComCredenciaisDeTeste() {
        assertTrue(EnvironmentGuard.problems(props("prod","mercadopago",true)).stream()
                .anyMatch(p -> p.contains("MP_TEST_MODE=false")));
        assertTrue(EnvironmentGuard.problems(props("prod","mercadopago",false)).isEmpty());
    }

    @Test
    void recusaCorsCuringsCaminhosCredenciaisEHttpForaDeDev() {
        AppProperties base = props("prod", "mercadopago", false);
        for (String origin : List.of("*", "https://*.wodz.test", "https://wodz.test/private",
                "https://user:password@wodz.test", "https://wodz.test?secret=1", "http://wodz.test")) {
            AppProperties invalid = new AppProperties(base.frontendUrl(), List.of(origin), base.jwt(),
                    base.payment(), base.seed(), base.google(), base.environment(), base.security());
            assertTrue(EnvironmentGuard.problems(invalid).stream().anyMatch(p -> p.contains("CORS_ORIGINS")), origin);
        }
    }

    @Test
    void senhasPadraoDeBancoNaoPassamForaDeDev() {
        assertFalse(EnvironmentGuard.databaseProblems(props("prod", "mercadopago", false), "wodz").isEmpty());
        assertFalse(EnvironmentGuard.databaseProblems(props("homolog", "mercadopago", false), "").isEmpty());
        assertTrue(EnvironmentGuard.databaseProblems(props("prod", "mercadopago", false), "dedicated-random-secret").isEmpty());
        assertTrue(EnvironmentGuard.databaseProblems(props("dev", "sandbox", false), "wodz").isEmpty());
    }
}
