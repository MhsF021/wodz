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
import br.com.wodz.common.ApiException;
import br.com.wodz.domain.ScoringSystem;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipRequest;
import br.com.wodz.web.ChampionshipImageController;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

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
            assertEquals(33, jdbc.queryForObject("SELECT count(*) FROM category", Integer.class));
            assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM category WHERE name LIKE 'RX%' AND team_size=1 AND gender_policy IN ('MALE','FEMALE')", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM category WHERE jsonb_array_length(movement_standards)<12", Integer.class));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT t.id FROM team t JOIN category c ON c.id=t.category_id JOIN team_member m ON m.team_id=t.id
                        GROUP BY t.id,c.team_size,c.gender_policy
                        HAVING count(*)<>c.team_size
                            OR (c.gender_policy='MALE' AND count(*) FILTER(WHERE m.gender='F')>0)
                            OR (c.gender_policy='FEMALE' AND count(*) FILTER(WHERE m.gender='M')>0)
                            OR (c.gender_policy='MIXED' AND count(*) FILTER(WHERE m.gender='M')<>1)
                    ) invalid_teams
                    """, Integer.class));
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
            var championshipService = context.getBean(ChampionshipService.class);
            var tcb = championshipService.publicDetail("tcb-2026");
            var cat = tcb.categories().stream().filter(c -> c.name().equals("Scaled misto")).findFirst().orElseThrow();
            assertEquals("45 kg", cat.movementStandards().getFirst().male());
            Long owner = jdbc.queryForObject("SELECT min(id) FROM app_user WHERE role='ORGANIZER'", Long.class);
            var catalog = context.getBean(CatalogService.class);
            var custom = java.util.List.of(new br.com.wodz.domain.MovementStandard("Movimento QA", "42 kg", "24 kg"));
            catalog.updateCategory(cat.id(), owner, new br.com.wodz.dto.ChampionshipDtos.CategoryRequest(cat.name(),cat.description(),
                    cat.teamSize(),cat.maxTeams(),cat.price(),cat.displayOrder(),cat.genderPolicy(),cat.allowedMaleCounts(),custom));
            assertEquals(custom, championshipService.publicDetail("tcb-2026").categories().stream().filter(c -> c.id().equals(cat.id())).findFirst().orElseThrow().movementStandards());
            // Clientes anteriores que omitem a tabela não apagam os movimentos publicados.
            var preserved = catalog.updateCategory(cat.id(), owner, new br.com.wodz.dto.ChampionshipDtos.CategoryRequest(cat.name(),cat.description(),
                    cat.teamSize(),cat.maxTeams(),cat.price(),cat.displayOrder(),cat.genderPolicy(),cat.allowedMaleCounts()));
            assertEquals(custom, preserved.movementStandards());
            int teams = jdbc.queryForObject("SELECT count(*) FROM team", Integer.class);
            context.getBean(DemoDataSeeder.class).run(new DefaultApplicationArguments());
            assertEquals(teams, jdbc.queryForObject("SELECT count(*) FROM team", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment", Integer.class));
            verificaImagemAutorizada(context.getBean(ChampionshipService.class), context.getBean(ChampionshipImageController.class), jdbc);
        }
    }

    private static void verificaImagemAutorizada(ChampionshipService service, ChampionshipImageController images,
                                                 JdbcTemplate jdbc) throws Exception {
        Long owner = jdbc.queryForObject("SELECT min(id) FROM app_user WHERE role='ORGANIZER'",Long.class);
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB),"png",out);
        String data = "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        LocalDate start = LocalDate.now().plusDays(60);
        ChampionshipRequest request = new ChampionshipRequest("Imagem QA",null,"Arena",null,"Recife","PE",start,start,
                null,null,ScoringSystem.POINTS_TABLE,100,5,null,data);
        var created = service.create(owner,request);
        UUID firstKey = UUID.fromString(created.bannerUrl().substring(created.bannerUrl().lastIndexOf('/')+1));
        var response = images.image(firstKey);
        assertEquals("image/png",response.getHeaders().getContentType().toString());
        assertEquals(80,ImageIO.read(new java.io.ByteArrayInputStream(response.getBody())).getWidth());
        assertEquals(created.bannerUrl(),service.adminDetail(created.id(),owner).bannerUrl());
        assertThrows(ApiException.class, () -> service.update(created.id(),owner+100_000L,request));
        int count = jdbc.queryForObject("SELECT count(*) FROM championship",Integer.class);
        ChampionshipRequest invalid = new ChampionshipRequest("Imagem inválida",null,"Arena",null,"Recife","PE",start,start,
                null,null,ScoringSystem.POINTS_TABLE,100,5,null,"data:image/png;base64,bm90LWltYWdl");
        assertThrows(ApiException.class, () -> service.create(owner,invalid));
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM championship",Integer.class));
        var updated = service.update(created.id(),owner,request);
        assertNotEquals(created.bannerUrl(),updated.bannerUrl());
        assertThrows(ApiException.class, () -> images.image(firstKey));
        service.update(created.id(),owner,new ChampionshipRequest("Imagem QA",null,"Arena",null,"Recife","PE",start,start,
                null,null,ScoringSystem.POINTS_TABLE,100,5,updated.bannerUrl(),""));
        assertNull(service.adminDetail(created.id(),owner).bannerUrl());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM championship_image WHERE championship_id=?",Integer.class,created.id()));
    }
}
