package br.com.wodz.service;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercita as procedures e triggers reais, num PostgreSQL temporário sem usar o banco do aplicativo. */
class HeatScheduleDatabaseTest {
    private static EmbeddedPostgres pg;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void database() throws Exception {
        Path working = Path.of("target", "postgres-tests").toAbsolutePath();
        Files.createDirectories(working);
        pg = EmbeddedPostgres.builder().setOverrideWorkingDirectory(working.toFile())
                .setLocaleConfig("locale", "C").start();
        Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(pg.getPostgresDatabase());
    }

    @AfterAll
    static void close() throws Exception { if (pg != null) pg.close(); }

    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE app_user RESTART IDENTITY CASCADE");
        jdbc.execute("""
                INSERT INTO app_user (id,name,email,password_hash,role) VALUES (1,'Organizador','org@test.invalid','x','ORGANIZER');
                INSERT INTO championship (id,owner_id,name,slug,venue_name,city,state,start_date,end_date,status,registration_open)
                VALUES (1,1,'Teste','teste','Arena','Recife','PE',CURRENT_DATE+30,CURRENT_DATE+31,'PUBLISHED',TRUE);
                INSERT INTO category (id,championship_id,name,team_size,price) VALUES (1,1,'RX',1,10),(2,1,'Scaled',1,10);
                INSERT INTO workout (id,category_id,number,name,type,time_cap_seconds)
                VALUES (1,1,1,'WOD','FOR_TIME',780),(2,2,1,'Outro WOD','AMRAP',600);
                INSERT INTO team (category_id,captain_id,name,status)
                SELECT 1,1,'Time '||n,'CONFIRMED' FROM generate_series(1,17) n;
                """);
    }

    private static OffsetDateTime time(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static Instant instant(String sql) { return jdbc.queryForObject(sql, OffsetDateTime.class).toInstant(); }
    private static List<Instant> starts() {
        return jdbc.query("SELECT scheduled_at FROM heat ORDER BY scheduled_at,id", (rs,n) -> rs.getObject(1, OffsetDateTime.class).toInstant());
    }
    private static long firstId() { return jdbc.queryForObject("SELECT id FROM heat ORDER BY scheduled_at,id LIMIT 1", Long.class); }
    private static void generate(Instant first, int interval) {
        jdbc.update("CALL sp_generate_heats(1::bigint,8,?,?)", time(first), interval);
    }

    @Test
    void somaDuracaoEDescansoComRaiasEquilibradas() {
        Instant first = Instant.parse("2030-01-01T07:00:00Z");
        generate(first, 8);
        assertEquals(List.of(first, first.plusSeconds(21*60), first.plusSeconds(42*60)), starts());
        assertEquals(List.of(5,6,6), jdbc.query("SELECT count(*) FROM heat_lane GROUP BY heat_id ORDER BY heat_id", (rs,n) -> rs.getInt(1)));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM vw_heat_schedule WHERE duration_seconds=780 AND interval_minutes=8", Integer.class));
    }

    @Test
    void atrasoReagendaOutrasProvasSemDuplicarAoIniciarNovamente() {
        jdbc.execute("UPDATE workout SET time_cap_seconds=600 WHERE id=1");
        Instant scheduled = instant("SELECT now() - interval '2 minutes'");
        generate(scheduled, 3);
        jdbc.update("INSERT INTO heat(workout_id,number,scheduled_at,duration_seconds,interval_minutes) VALUES (2,1,?,600,3)",
                time(scheduled.plusSeconds(39*60)));
        long id = firstId();
        jdbc.update("CALL sp_start_heat(?::bigint,true)", id);
        Instant actual = instant("SELECT started_at FROM heat WHERE id="+id);
        assertEquals(List.of(scheduled, actual.plusSeconds(13*60), actual.plusSeconds(26*60), actual.plusSeconds(39*60)), starts());
        List<Instant> after = starts();
        jdbc.update("CALL sp_start_heat(?::bigint,true)", id);
        assertEquals(after, starts());
        assertEquals(actual, instant("SELECT started_at FROM heat WHERE id="+id));
    }

    @Test
    void atrasoPreservaPausaAdicionalEHorariosJaIniciados() {
        Instant scheduled = instant("SELECT now() - interval '2 minutes'");
        generate(scheduled, 8);
        jdbc.execute("UPDATE heat SET scheduled_at=scheduled_at+interval '1 hour' WHERE number=3");
        long id = firstId();
        jdbc.update("CALL sp_start_heat(?::bigint,true)", id);
        Instant actual = instant("SELECT started_at FROM heat WHERE id="+id);
        assertEquals(List.of(scheduled, actual.plusSeconds(21*60), actual.plusSeconds(42*60+3600)), starts());
    }

    @Test
    void reabrirEIniciarDeNovoNaoSomaOAtrasoJaAplicado() {
        Instant scheduled = instant("SELECT now() - interval '2 minutes'");
        generate(scheduled, 8);
        long id = firstId();
        jdbc.update("CALL sp_start_heat(?::bigint,true)", id);
        jdbc.update("UPDATE heat SET status='UPCOMING' WHERE id=?", id);
        jdbc.update("CALL sp_start_heat(?::bigint,true)", id);
        Instant actual = instant("SELECT started_at FROM heat WHERE id="+id);
        assertEquals(actual.plusSeconds(21*60), starts().get(1));
        assertEquals(actual.plusSeconds(42*60), starts().get(2));
    }

    @Test
    void terminoTardioContaDescansoAPartirDoFimReal() {
        jdbc.execute("UPDATE workout SET time_cap_seconds=600 WHERE id=1");
        Instant first = instant("SELECT now() - interval '12 minutes'");
        generate(first, 3);
        long id = firstId();
        // Simula uma bateria que iniciou há 12 min sem disparar o reagendamento duas vezes.
        jdbc.update("UPDATE heat SET status='IN_PROGRESS' WHERE id=?", id);
        jdbc.update("UPDATE heat SET started_at=? WHERE id=?", time(first), id);
        jdbc.update("UPDATE heat SET schedule_end_at=? WHERE id=?", time(first.plusSeconds(10*60)), id);
        jdbc.update("UPDATE heat SET scheduled_at=? WHERE number=2", time(first.plusSeconds(13*60)));
        jdbc.update("UPDATE heat SET scheduled_at=? WHERE number=3", time(first.plusSeconds(26*60)));
        jdbc.update("UPDATE heat SET status='FINISHED' WHERE id=?", id);
        Instant finished = instant("SELECT finished_at FROM heat WHERE id="+id);
        assertEquals(finished.plusSeconds(3*60), starts().get(1));
        assertEquals(finished.plusSeconds(16*60), starts().get(2));
    }

    @Test
    void intervaloZeroEDuracaoComSegundosAtravessamMeiaNoite() {
        jdbc.execute("UPDATE workout SET time_cap_seconds=810 WHERE id=1");
        Instant first = Instant.parse("2030-01-01T23:55:00Z");
        generate(first, 0);
        assertEquals(List.of(first, first.plusSeconds(810), first.plusSeconds(1620)), starts());
    }

    @Test
    void provaSemDuracaoNaoApagaGradeAnterior() {
        generate(Instant.parse("2030-01-01T07:00:00Z"), 8);
        List<Instant> before = starts();
        jdbc.execute("UPDATE workout SET time_cap_seconds=NULL WHERE id=1");
        assertThrows(DataAccessException.class, () -> generate(Instant.parse("2030-01-01T08:00:00Z"),8));
        assertEquals(before, starts());
    }
}
