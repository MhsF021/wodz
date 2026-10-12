package br.com.wodz.service;

import br.com.wodz.domain.PayoutAccount;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PayoutAccountMetadataDatabaseTest {
    @Test
    void migrationLeavesLegacyUnknownAndJpaRefreshReadsValidatedModesIndependently() throws Exception {
        Path working = Path.of("target","payout-postgres-tests").toAbsolutePath();
        Files.createDirectories(working);
        try (var pg = EmbeddedPostgres.builder().setOverrideWorkingDirectory(working.toFile())
                .setLocaleConfig("locale","C").start()) {
            Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").target("23").load().migrate();
            var jdbc = new JdbcTemplate(pg.getPostgresDatabase());
            jdbc.execute("""
                    INSERT INTO app_user(id,name,email,password_hash,role)
                    VALUES (1,'Organizador','payout@test.invalid','x','ORGANIZER');
                    INSERT INTO payout_account(id,user_id,provider,provider_user_id,access_token,refresh_token,live_mode)
                    VALUES (1,1,'MERCADO_PAGO','123','legacy-encrypted-access','legacy-encrypted-refresh',FALSE);
                    """);
            Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").load().migrate();
            assertNull(jdbc.queryForObject("SELECT test_account FROM payout_account WHERE id=1",Boolean.class));
            assertNull(jdbc.queryForObject("SELECT application_id FROM payout_account WHERE id=1",String.class));
            assertFalse(jdbc.queryForObject("SELECT live_mode FROM payout_account WHERE id=1",Boolean.class));

            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(pg.getPostgresDatabase());
            factory.setPackagesToScan("br.com.wodz.domain");
            // A unidade deste teste cobre apenas a entidade alterada; não valida mapeamentos de outros módulos.
            factory.setPersistenceUnitPostProcessors(unit -> {
                unit.getManagedClassNames().removeIf(name -> !PayoutAccount.class.getName().equals(name));
                unit.setExcludeUnlistedClasses(true);
            });
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate",
                    "hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            try {
                factory.afterPropertiesSet();
                assertNotNull(factory.getObject());
                try (var outer = factory.getObject().createEntityManager(); var inner = factory.getObject().createEntityManager()) {
                    outer.getTransaction().begin();
                    PayoutAccount legacy = outer.find(PayoutAccount.class,1L);
                    assertNull(legacy.getTestAccount());
                    assertNull(legacy.getApplicationId());
                    assertFalse(legacy.isLiveMode());

                    // Equivalente à atualização de metadata em REQUIRES_NEW, sem usar OAuth real.
                    inner.getTransaction().begin();
                    PayoutAccount validated = inner.find(PayoutAccount.class,1L);
                    validated.setTestAccount(true);
                    validated.setLiveMode(true);
                    validated.setApplicationId("verified-application");
                    inner.getTransaction().commit();

                    assertFalse(legacy.isLiveMode()); // contexto externo ainda guarda os valores legados
                    outer.refresh(legacy);
                    assertTrue(legacy.getTestAccount());
                    assertTrue(legacy.isLiveMode());
                    assertEquals("verified-application",legacy.getApplicationId());
                    assertEquals(1L,legacy.getId());
                    assertEquals("legacy-encrypted-access",legacy.getAccessToken());
                    outer.getTransaction().rollback();
                }
            } finally { factory.destroy(); }
        }
    }
}
