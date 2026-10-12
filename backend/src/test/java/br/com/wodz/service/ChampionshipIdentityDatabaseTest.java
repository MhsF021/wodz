package br.com.wodz.service;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChampionshipIdentityDatabaseTest {
    @Test void migrationPreservesLegacyProfileAndAllowsIndependentReplacementAndRemoval() throws Exception {
        Path working = Path.of("target", "identity-postgres-tests").toAbsolutePath();
        Files.createDirectories(working);
        try (var pg = EmbeddedPostgres.builder().setOverrideWorkingDirectory(working.toFile())
                .setLocaleConfig("locale", "C").start()) {
            Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").target("20").load().migrate();
            var jdbc = new JdbcTemplate(pg.getPostgresDatabase());
            UUID coverKey = UUID.randomUUID();
            String coverUrl = "/api/public/championship-images/" + coverKey;
            jdbc.execute("""
                INSERT INTO app_user(id,name,email,password_hash,role) VALUES (1,'Organizador','identity@test.invalid','x','ORGANIZER');
                INSERT INTO championship(id,owner_id,name,slug,venue_name,city,state,start_date,end_date)
                VALUES (1,1,'Evento','identidade','Arena','Recife','PE',CURRENT_DATE+30,CURRENT_DATE+31);
                """);
            jdbc.update("UPDATE championship SET banner_url = ? WHERE id = 1", coverUrl);
            byte[] pixels = {1, 2, 3};
            jdbc.update("INSERT INTO championship_image(championship_id,image_key,content) VALUES (1,?,?)", coverKey, pixels);
            Flyway.configure().dataSource(pg.getPostgresDatabase()).locations("classpath:db/migration").load().migrate();
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM championship_image WHERE championship_id=1", Integer.class));
            String profileUrl = jdbc.queryForObject("SELECT profile_url FROM championship WHERE id=1", String.class);
            assertNotEquals(coverUrl, profileUrl);
            assertArrayEquals(pixels, jdbc.queryForObject("SELECT content FROM championship_image WHERE kind='PROFILE'", byte[].class));
            UUID nextKey = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO championship_image(championship_id,kind,image_key,content) VALUES (1,'COVER',?,?)
                ON CONFLICT (championship_id,kind) DO UPDATE SET image_key=EXCLUDED.image_key,content=EXCLUDED.content
                """, nextKey, new byte[]{4, 5});
            assertArrayEquals(pixels, jdbc.queryForObject("SELECT content FROM championship_image WHERE kind='PROFILE'", byte[].class));
            jdbc.update("DELETE FROM championship_image WHERE championship_id=1 AND kind='PROFILE'");
            assertEquals(nextKey, jdbc.queryForObject("SELECT image_key FROM championship_image WHERE kind='COVER'", UUID.class));
        }
    }
}
