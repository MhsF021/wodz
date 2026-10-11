package br.com.wodz.service;

import br.com.wodz.WodzApplication;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Executa o seed e as migrations reais num banco temporário, sem tocar o banco do WODZ. */
class DemoDataSeederDatabaseTest {
    private static SpringApplication application() {
        SpringApplication app = new SpringApplication(WodzApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setApplicationContextFactory(type -> {
            var context = new GenericWebApplicationContext();
            context.setServletContext(new MockServletContext());
            return context;
        });
        return app;
    }
    @Test
    void demonstracaoNaoGeraPagamentosNemConfirmaInscricoesPagas() throws Exception {
        Path working = Path.of("target", "demo-postgres-tests").toAbsolutePath();
        Files.createDirectories(working);
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().setOverrideWorkingDirectory(working.toFile())
                .setLocaleConfig("locale", "C").start();
             var context = application().run("--app.environment=dev",
                             "--app.seed.enabled=true", "--app.payment.provider=disabled", "--app.google.client-id=",
                             "--spring.datasource.url=" + pg.getJdbcUrl("postgres", "postgres"),
                             "--spring.datasource.username=postgres", "--spring.datasource.password=")) {
            JdbcTemplate jdbc = new JdbcTemplate(pg.getPostgresDatabase());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment", Integer.class));
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM team t JOIN category c ON c.id=t.category_id WHERE c.price>0", Integer.class) > 0);
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM team t JOIN category c ON c.id=t.category_id
                    WHERE c.price>0 AND (t.status <> 'PENDING_PAYMENT' OR t.confirmed_at IS NOT NULL)
                    """, Integer.class));
            assertTrue(jdbc.queryForObject("""
                    SELECT count(*) FROM team t JOIN category c ON c.id=t.category_id
                    WHERE c.price=0 AND t.status='CONFIRMED' AND t.confirmed_at IS NOT NULL
                    """, Integer.class) > 0);
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM heat", Integer.class) > 0);
            int teams = jdbc.queryForObject("SELECT count(*) FROM team", Integer.class);
            context.getBean(DemoDataSeeder.class).run(new DefaultApplicationArguments());
            assertEquals(teams, jdbc.queryForObject("SELECT count(*) FROM team", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment", Integer.class));
        }
    }
}
