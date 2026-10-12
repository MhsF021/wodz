package br.com.wodz.service;

import br.com.wodz.domain.CheckoutSession;
import br.com.wodz.domain.Team;
import br.com.wodz.payment.CheckoutMethod;
import br.com.wodz.repository.CheckoutSessionRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.LockModeType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PixDraftDatabaseTest {
    @Test void draftCommitsBeforeProviderCallAndSurvivesOuterFailureWithTeamLock() throws Exception {
        Path working=Path.of("target","pix-postgres-tests").toAbsolutePath(); Files.createDirectories(working);
        try(var pg=EmbeddedPostgres.builder().setOverrideWorkingDirectory(working.toFile()).setLocaleConfig("locale","C").start()) {
            Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").load().migrate();
            var jdbc=new JdbcTemplate(pg.getPostgresDatabase());
            jdbc.execute("""
                    INSERT INTO app_user(id,name,email,password_hash,role) VALUES
                    (1,'Organizador','org@test.invalid','x','ORGANIZER'),(2,'Atleta','athlete@test.invalid','x','ATHLETE');
                    INSERT INTO championship(id,owner_id,name,slug,venue_name,city,state,start_date,end_date,status,registration_open)
                    VALUES (1,1,'Evento','evento','Arena','Recife','PE',CURRENT_DATE+30,CURRENT_DATE+31,'PUBLISHED',TRUE);
                    INSERT INTO category(id,championship_id,name,team_size,price) VALUES (1,1,'RX',1,150);
                    INSERT INTO team(id,category_id,captain_id,name) VALUES (1,1,2,'Equipe');
                    INSERT INTO payout_account(id,user_id,provider,provider_user_id,access_token,refresh_token,live_mode)
                    VALUES (1,1,'MERCADO_PAGO','123','encrypted','encrypted',FALSE);
                    """);
            var factory=new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(pg.getPostgresDatabase()); factory.setPackagesToScan("br.com.wodz.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none",
                    "hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            try {
                factory.afterPropertiesSet();
                var manager=new JpaTransactionManager(factory.getObject());
                var em=SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
                var sessions=new JpaRepositoryFactory(em).getRepository(CheckoutSessionRepository.class);
                var proxy=new ProxyFactory(new PixDraftService(sessions)); proxy.setProxyTargetClass(true);
                proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
                var drafts=(PixDraftService)proxy.getProxy();
                String reference=UUID.randomUUID().toString();
                assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(status -> {
                    Team locked=em.find(Team.class,1L,LockModeType.PESSIMISTIC_WRITE);
                    CheckoutSession draft=new CheckoutSession(); draft.setTeam(locked); draft.setPayoutAccountId(1L);
                    draft.setMethod(CheckoutMethod.PIX); draft.setAmount(new BigDecimal("150.00"));
                    draft.setExternalReference(reference); draft.setExpiresAt(Instant.now().plusSeconds(1860));
                    draft.setPixPayer("encrypted-payer"); draft.setPixDescription("Inscrição");
                    draft.setTermsAcceptedAt(Instant.now()); draft.setTermsVersion("checkout-v1");
                    drafts.save(draft);
                    // Outra conexão já enxerga a chave antes de chamar o provedor.
                    assertEquals(reference,jdbc.queryForObject("SELECT external_reference FROM checkout_session",String.class));
                    throw new IllegalStateException("Processo interrompido após emissão externa");
                }));
                assertEquals(reference,jdbc.queryForObject("SELECT external_reference FROM checkout_session",String.class));
                assertEquals("checkout-v1",jdbc.queryForObject("SELECT terms_version FROM checkout_session",String.class));
                assertNull(jdbc.queryForObject("SELECT provider_payment_id FROM checkout_session",String.class));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM payment",Integer.class));
            } finally { factory.destroy(); }
        }
    }
}
