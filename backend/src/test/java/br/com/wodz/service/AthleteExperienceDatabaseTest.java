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
        Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").target("19").load().migrate();
        jdbc = new JdbcTemplate(pg.getPostgresDatabase());
        // Exercita também a atualização de um banco com link antigo já distribuído.
        jdbc.execute("""
                INSERT INTO app_user(id,name,email,password_hash,role) VALUES
                (1,'Organizador','org@test.invalid','x','ORGANIZER'),(2,'Atleta','athlete@test.invalid','x','ATHLETE');
                INSERT INTO championship(id,owner_id,name,slug,venue_name,city,state,start_date,end_date,status,registration_open)
                VALUES (1,1,'Legado','legado','Arena','Recife','PE',CURRENT_DATE+30,CURRENT_DATE+31,'PUBLISHED',TRUE);
                INSERT INTO category(id,championship_id,name,team_size,price) VALUES (1,1,'RX',1,150);
                INSERT INTO team(category_id,captain_id,name,registration_code) VALUES (1,2,'Legado','LEGADO1234');
                """);
        Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").load().migrate();
        assertEquals("LEGADO1234",jdbc.queryForObject("SELECT registration_code FROM team",String.class));
        assertEquals("LEGADO1234",jdbc.queryForObject("SELECT registration_code FROM vw_team_overview",String.class));
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

    @Test void codigoGeradoNoBancoEImprevisivelEPreservaLinksAntigos() {
        insert(1,"Gerado no banco","PENDING_PAYMENT");
        String code = jdbc.queryForObject("SELECT registration_code FROM team",String.class);
        assertTrue(code.matches("[A-F0-9]{32}"));
        jdbc.update("UPDATE team SET registration_code='LEGADO1234' WHERE category_id=1");
        assertEquals("LEGADO1234",jdbc.queryForObject("SELECT registration_code FROM team",String.class));
        jdbc.update("INSERT INTO team(category_id,captain_id,name,registration_code) VALUES (3,2,'Aplicação',?)",br.com.wodz.common.Codes.registrationCode());
        assertEquals(26,jdbc.queryForObject("SELECT length(registration_code) FROM team WHERE category_id=3",Integer.class));
    }

    private void teamWithGenders(String genders) throws Exception {
        try (var connection = pg.getPostgresDatabase().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO team(id,category_id,captain_id,name,status) VALUES (100,1,2,'Composição','PENDING_PAYMENT')");
                try (var member = connection.prepareStatement("INSERT INTO team_member(team_id,full_name,email,cpf,birth_date,gender) VALUES (100,'Atleta','qa@test.invalid',?,'1990-01-01',?)")) {
                    for (int i=0;i<genders.length();i++) {
                        member.setString(1, i==0 ? "52998224725" : i==1 ? "11144477735" : "12345678909");
                        member.setString(2, genders.substring(i,i+1)); member.executeUpdate();
                    }
                }
                connection.commit();
            } finally { connection.rollback(); }
        }
    }
    @Test void bancoRejeitaEquipeIncompativelMesmoSemPassarPeloServico() throws Exception {
        jdbc.execute("UPDATE category SET team_size=2,gender_policy='MIXED',allowed_male_counts=ARRAY[1] WHERE id=1");
        assertThrows(java.sql.SQLException.class, () -> teamWithGenders("MM"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM team",Integer.class));
        teamWithGenders("MF");
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE team_member SET gender='M' WHERE gender='F'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE category SET gender_policy='MALE',allowed_male_counts='{}' WHERE id=1"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM team_member WHERE gender='F'",Integer.class));
    }
    @Test void regraFemininaERegraMistaComDuasOpcoesSaoValidadasNoCommit() throws Exception {
        jdbc.execute("UPDATE category SET team_size=2,gender_policy='FEMALE' WHERE id=1");
        assertThrows(java.sql.SQLException.class, () -> teamWithGenders("MF"));
        teamWithGenders("FF");
        jdbc.execute("DELETE FROM team WHERE id=100; UPDATE category SET team_size=3,gender_policy='MIXED',allowed_male_counts=ARRAY[1,2] WHERE id=1");
        teamWithGenders("MMF");
        jdbc.execute("DELETE FROM team WHERE id=100");
        teamWithGenders("MFF");
    }
    @Test void definirRegraEmCategoriaLegadaNaoPodeInvalidarEquipeExistente() throws Exception {
        jdbc.execute("UPDATE category SET team_size=2 WHERE id=1");
        teamWithGenders("MF");
        assertThrows(DataAccessException.class, () -> jdbc.execute("UPDATE category SET gender_policy='FEMALE' WHERE id=1"));
        jdbc.execute("UPDATE category SET gender_policy='MIXED',allowed_male_counts=ARRAY[1] WHERE id=1");
        assertEquals("MIXED",jdbc.queryForObject("SELECT gender_policy FROM category WHERE id=1",String.class));
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
    @Test void cancelamentoLiberaVagaCancelaCobrancaPendenteEPermiteNovaInscricao() {
        jdbc.update("UPDATE category SET max_teams=1 WHERE id=1");
        insert(1,"Anterior","PENDING_PAYMENT");
        jdbc.execute("INSERT INTO payment(team_id,amount,method,provider,status) SELECT id,150,'PIX','mercadopago','PENDING' FROM team WHERE name='Anterior'");
        assertEquals(0,jdbc.queryForObject("SELECT fn_category_slots_left(1)",Integer.class));
        jdbc.update("UPDATE team SET status='CANCELLED' WHERE name='Anterior'");
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM payment",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT fn_category_slots_left(1)",Integer.class));
        insert(1,"Nova","PENDING_PAYMENT");
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM team",Integer.class));
    }
    @Test void aprovacaoAtrasadaDaCobrancaCanceladaPreservaHistoricoSemReativarEquipe() {
        insert(1,"Anterior","PENDING_PAYMENT");
        jdbc.execute("INSERT INTO payment(team_id,amount,method,provider,status) SELECT id,150,'PIX','mercadopago','PENDING' FROM team WHERE name='Anterior'");
        jdbc.update("UPDATE team SET status='CANCELLED' WHERE name='Anterior'");
        insert(2,"Nova","PENDING_PAYMENT");
        jdbc.update("UPDATE payment SET status='APPROVED'");
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM payment",String.class));
        assertNotNull(jdbc.queryForObject("SELECT paid_at FROM payment",java.sql.Timestamp.class));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM team WHERE name='Anterior'",String.class));
        assertEquals("PENDING_PAYMENT",jdbc.queryForObject("SELECT status FROM team WHERE name='Nova'",String.class));
    }
    @Test void primeiraNotificacaoAprovadaAposCancelamentoTambemNaoReativaEquipe() {
        insert(1,"Anterior","PENDING_PAYMENT");
        jdbc.update("UPDATE team SET status='CANCELLED' WHERE name='Anterior'");
        jdbc.execute("INSERT INTO payment(team_id,amount,method,provider,status) SELECT id,150,'PIX','mercadopago','APPROVED' FROM team WHERE name='Anterior'");
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM team",String.class));
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM payment",String.class));
    }
    @Test void aprovacaoNormalConfirmaEquipeEEstornoContinuaCancelandoInscricao() {
        insert(1,"Equipe","PENDING_PAYMENT");
        jdbc.execute("INSERT INTO payment(team_id,amount,method,provider,status) SELECT id,150,'PIX','mercadopago','APPROVED' FROM team");
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status FROM team",String.class));
        jdbc.update("UPDATE payment SET status='REFUNDED'");
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM team",String.class));
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
