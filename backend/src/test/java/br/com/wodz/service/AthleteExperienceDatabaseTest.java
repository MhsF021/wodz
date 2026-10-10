package br.com.wodz.service;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AthleteExperienceDatabaseTest {
    private static EmbeddedPostgres pg;
    private static JdbcTemplate jdbc;
    @BeforeAll static void database() throws Exception {
        Path working = Path.of("target", "registration-postgres-tests").toAbsolutePath(); Files.createDirectories(working);
        pg = EmbeddedPostgres.builder().setOverrideWorkingDirectory(working.toFile()).setLocaleConfig("locale", "C").start();
        Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(pg.getPostgresDatabase());
    }
    @AfterAll static void close() throws Exception { if (pg != null) pg.close(); }
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE app_user RESTART IDENTITY CASCADE");
        jdbc.execute("""
                INSERT INTO app_user(id,name,email,password_hash,role) VALUES
                (1,'Organizador','org@test.invalid','x','ORGANIZER'),(2,'Atleta','athlete@test.invalid','x','ATHLETE');
                INSERT INTO championship(id,owner_id,name,slug,venue_name,city,state,start_date,end_date,status,registration_open) VALUES
                (1,1,'Evento','evento','Arena','Recife','PE',CURRENT_DATE+30,CURRENT_DATE+31,'PUBLISHED',TRUE),
                (2,1,'Outro','outro','Arena','São Paulo','SP',CURRENT_DATE+30,CURRENT_DATE+31,'PUBLISHED',TRUE);
                INSERT INTO category(id,championship_id,name,team_size,price) VALUES (1,1,'RX',1,150),(2,1,'Scaled',1,150),(3,2,'RX',1,150);
                SELECT setval(pg_get_serial_sequence('championship','id'), (SELECT max(id) FROM championship));
                """);
    }
    private void insert(int category, String name, String status) {
        jdbc.update("INSERT INTO team(category_id,captain_id,name,status) VALUES (?,2,?,?)", category, name, status);
    }
    private void duplicate(Runnable request) {
        DataAccessException error = assertThrows(DataAccessException.class, request::run);
        PSQLException cause = (PSQLException) error.getMostSpecificCause();
        assertEquals("23505", cause.getSQLState());
        assertEquals("uq_team_captain_championship_active", cause.getServerErrorMessage().getConstraint());
        assertEquals("Você já possui uma inscrição para esse campeonato", cause.getServerErrorMessage().getMessage());
    }
    @Test void inscricaoPendenteBloqueiaOutraCategoriaDoMesmoCampeonato() {
        insert(1,"Primeira","PENDING_PAYMENT"); duplicate(() -> insert(2,"Segunda","PENDING_PAYMENT"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM team", Integer.class));
    }
    @Test void inscricaoConfirmadaTambemBloqueiaDuplicidade() {
        insert(1,"Primeira","CONFIRMED"); duplicate(() -> insert(2,"Segunda","PENDING_PAYMENT"));
    }
    @Test void cancelamentoPermiteNovaInscricaoMasNaoReativarAAnterior() {
        insert(1,"Anterior","CANCELLED"); insert(2,"Nova","PENDING_PAYMENT");
        duplicate(() -> jdbc.update("UPDATE team SET status='CONFIRMED' WHERE name='Anterior'"));
    }
    @Test void mesmoAtletaPodeSeInscreverEmCampeonatosDiferentes() {
        insert(1,"Evento 1","PENDING_PAYMENT"); insert(3,"Evento 2","PENDING_PAYMENT");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM team", Integer.class));
    }
    @Test void tentativasSimultaneasEmCategoriasDiferentesGeramSomenteUmaInscricao() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor(); var first = pg.getPostgresDatabase().getConnection()) {
            first.setAutoCommit(false);
            try (var statement = first.createStatement()) {
                statement.executeUpdate("INSERT INTO team(category_id,captain_id,name) VALUES (1,2,'Primeira')");
                Future<DataAccessException> second = pool.submit(() -> {
                    started.countDown();
                    try { insert(2,"Segunda","PENDING_PAYMENT"); return null; }
                    catch (DataAccessException error) { return error; }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
                first.commit();
                assertNotNull(second.get(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM team", Integer.class));
    }
    @Test void filtrosPorSituacaoRespeitamJanelaDeInscricoesEstadoEBuscaSemAcento() {
        jdbc.execute("""
                INSERT INTO championship(id,owner_id,name,slug,venue_name,city,state,start_date,end_date,status,registration_open) VALUES
                (3,1,'Ao vivo','vivo','Arena','Recife','PE',CURRENT_DATE,CURRENT_DATE+1,'IN_PROGRESS',TRUE),
                (4,1,'Torneio Único','passado','Arena','Recife','PE',CURRENT_DATE-2,CURRENT_DATE-1,'FINISHED',TRUE),
                (5,1,'Publicado hoje','hoje','Arena','Recife','PE',CURRENT_DATE,CURRENT_DATE+1,'PUBLISHED',TRUE);
                """);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM fn_search_championships('', '', 'OPEN')", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM fn_search_championships('', '', 'IN_PROGRESS')", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM fn_search_championships('unico', 'PE', 'FINISHED')", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fn_search_championships('unico', 'SP', 'FINISHED')", Integer.class));
    }
    @Test void eventosEncerradosSaoFiltradosAntesDoLimiteDeCinquentaResultados() {
        jdbc.execute("""
                INSERT INTO championship(owner_id,name,slug,venue_name,city,state,start_date,end_date,status,registration_open)
                SELECT 1,'Futuro '||n,'futuro-'||n,'Arena','Recife','PE',CURRENT_DATE+30,CURRENT_DATE+31,'PUBLISHED',TRUE
                FROM generate_series(10,69) n;
                INSERT INTO championship(owner_id,name,slug,venue_name,city,state,start_date,end_date,status,registration_open)
                VALUES (1,'Passado','passado','Arena','Recife','PE',CURRENT_DATE-2,CURRENT_DATE-1,'FINISHED',FALSE);
                """);
        assertEquals(50, jdbc.queryForObject("SELECT count(*) FROM fn_search_championships('', '', 'ALL')", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM fn_search_championships('', '', 'FINISHED')", Integer.class));
    }
}
