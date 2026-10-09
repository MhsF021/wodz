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
                        new AppProperties.MercadoPagoProps("public-key","webhook-secret",
                                "https://local.wodz.test/api/webhooks/mercadopago",true,"client-id","client-secret",
                                BigDecimal.ZERO,"platform",testMode)),
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
}
