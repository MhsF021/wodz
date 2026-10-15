package br.com.wodz.service;

import br.com.wodz.WodzApplication;
import br.com.wodz.common.ApiException;
import br.com.wodz.domain.*;
import br.com.wodz.dto.ChampionshipDtos.*;
import br.com.wodz.dto.HeatDtos.*;
import br.com.wodz.dto.ScoreDtos.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.support.GenericWebApplicationContext;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Migrations, serviços e transações reais num PostgreSQL isolado. */
class OrganizerHeatLifecycleDatabaseTest {
    static EmbeddedPostgres pg;
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static ChampionshipService championships;
    static HeatService heats;
    static ScoreService scores;
    static Instant first;

    @BeforeAll static void start() throws Exception {
        Path dir = Path.of("target", "organizer-postgres-tests").toAbsolutePath();
        Files.createDirectories(dir);
        pg = EmbeddedPostgres.builder().setOverrideWorkingDirectory(dir.toFile()).setLocaleConfig("locale", "C").start();
        SpringApplication app = new SpringApplication(WodzApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setApplicationContextFactory(type -> {
            var result = new GenericWebApplicationContext(); result.setServletContext(new MockServletContext()); return result;
        });
        context = app.run("--app.environment=dev", "--app.seed.enabled=false", "--app.payment.provider=disabled",
                "--app.google.client-id=", "--app.heats.lifecycle-enabled=false",
                "--spring.datasource.url=" + pg.getJdbcUrl("postgres", "postgres"),
                "--spring.datasource.username=postgres", "--spring.datasource.password=");
        jdbc = context.getBean(JdbcTemplate.class);
        championships = context.getBean(ChampionshipService.class);
        heats = context.getBean(HeatService.class);
        scores = context.getBean(ScoreService.class);
    }

    @AfterAll static void stop() throws Exception { if (context != null) context.close(); if (pg != null) pg.close(); }

    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE app_user RESTART IDENTITY CASCADE");
        jdbc.execute("""
                INSERT INTO app_user(id,name,email,password_hash,role) VALUES(1,'Organizador','org@test.invalid','x','ORGANIZER');
                INSERT INTO championship(id,owner_id,name,slug,venue_name,city,state,start_date,end_date,status,registration_open)
                VALUES(1,1,'Teste','teste','Arena','Recife','PE',CURRENT_DATE+30,CURRENT_DATE+31,'PUBLISHED',TRUE);
                INSERT INTO category(id,championship_id,name,team_size,price) VALUES(1,1,'RX',2,0);
                INSERT INTO workout(id,category_id,number,name,type,time_cap_seconds)
                VALUES(1,1,1,'Primeira','FOR_TIME',600),(2,1,2,'Segunda','FOR_TIME',780);
                INSERT INTO app_user(id,name,email,password_hash,role)
                SELECT n+1,'Atleta '||n,'atleta'||n||'@test.invalid','x','ATHLETE' FROM generate_series(1,5) n;
                INSERT INTO team(category_id,captain_id,name,status)
                SELECT 1,n+1,'Equipe '||n,'CONFIRMED' FROM generate_series(1,5) n;
                """);
        first = jdbc.queryForObject("SELECT (start_date + time '08:00') AT TIME ZONE 'America/Sao_Paulo' FROM championship WHERE id=1",
                java.time.OffsetDateTime.class).toInstant();
    }

    private void configure() { heats.settings(1L, 1L, new GenerateAllHeatsRequest(2, first, 2)); }
    private void closeRegistration() { configure(); championships.setRegistrationOpen(1L, 1L, false); }
    private List<HeatPlanEntry> plan() {
        return heats.list(1L, null, 1L).stream().map(h -> new HeatPlanEntry(h.heatId(), h.workoutId(), h.heatNumber(),
                h.scheduledAt(), h.lanes().stream().map(LaneView::teamId).toList())).toList();
    }
    private List<String> layout() {
        return jdbc.query("SELECT h.workout_id||':'||h.number||':'||l.lane||':'||l.team_id FROM heat h JOIN heat_lane l ON l.heat_id=h.id ORDER BY h.workout_id,h.number,l.lane", (rs,n) -> rs.getString(1));
    }

    @Test void quantidadeObrigatoriaEInscricoesAbertasBloqueiamGeracao() {
        assertThrows(ApiException.class, () -> championships.setRegistrationOpen(1L, 1L, false));
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(new GenerateAllHeatsRequest(null, first, 2)).isEmpty());
        }
        configure();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM heat", Integer.class));
        assertThrows(ApiException.class, () -> heats.generateAll(1L, 1L, new GenerateAllHeatsRequest(2, first, 2)));
        assertThrows(ApiException.class, () -> heats.savePlan(1L, 1L, new HeatPlanRequest(List.of())));
    }

    @Test void fechamentoGeraGradeEquilibradaComMesmasBateriasERaias() {
        closeRegistration();
        var all = heats.list(1L, null, 1L);
        assertEquals(6, all.size());
        assertEquals(List.of(1,2,2), all.stream().filter(h -> h.workoutId()==1L).map(h -> h.lanes().size()).toList());
        var firstWorkout = all.stream().filter(h -> h.workoutId()==1L).toList();
        var secondWorkout = all.stream().filter(h -> h.workoutId()==2L).toList();
        for (int i=0;i<3;i++) {
            assertEquals(firstWorkout.get(i).lanes().stream().map(LaneView::teamId).toList(), secondWorkout.get(i).lanes().stream().map(LaneView::teamId).toList());
            assertEquals(first.plusSeconds(i*720L), firstWorkout.get(i).scheduledAt());
            assertEquals(first.plusSeconds(2160+i*900L), secondWorkout.get(i).scheduledAt());
        }
        assertTrue(jdbc.queryForObject("SELECT fn_championship_heats_complete(1)", Boolean.class));
        heats.generateAll(1L,1L,new GenerateAllHeatsRequest(3,first,0));
        assertEquals(4, heats.list(1L,null,1L).size());
    }

    @Test void removerEquipeNaoPermiteSalvarEMovimentoValidoPreservaTodasAsProvas() {
        closeRegistration();
        List<String> before = layout();
        var invalid = plan().stream().map(e -> new HeatPlanEntry(e.heatId(),e.workoutId(),e.heatNumber(),e.scheduledAt(),
                e.teamIds().stream().filter(id -> id != 1L).toList())).toList();
        var error = assertThrows(ApiException.class, () -> heats.savePlan(1L,1L,new HeatPlanRequest(invalid)));
        assertTrue(error.getMessage().contains(jdbc.queryForObject("SELECT name FROM team WHERE id=1",String.class)));
        assertEquals(before,layout());
        var valid = plan().stream().map(e -> new HeatPlanEntry(e.heatId(),e.workoutId(),e.heatNumber(),e.scheduledAt(),
                e.teamIds().stream().map(id -> id==1L ? 2L : id==2L ? 1L : id).toList())).toList();
        heats.savePlan(1L,1L,new HeatPlanRequest(valid));
        assertNotEquals(before,layout());
        assertTrue(jdbc.queryForObject("SELECT fn_championship_heats_complete(1)",Boolean.class));
    }

    @Test void bateriaManualPrecisaSerEspelhadaETodaEquipeDevePermanecerAlocada() {
        closeRegistration();
        var edited = new ArrayList<>(plan().stream().filter(e -> e.heatNumber()!=1).toList());
        edited.add(new HeatPlanEntry(null,1L,4,first.plusSeconds(5000),List.of(1L)));
        assertThrows(ApiException.class, () -> heats.savePlan(1L,1L,new HeatPlanRequest(edited)));
        edited.add(new HeatPlanEntry(null,2L,4,first.plusSeconds(6000),List.of(1L)));
        heats.savePlan(1L,1L,new HeatPlanRequest(edited));
        assertTrue(jdbc.queryForObject("SELECT fn_championship_heats_complete(1)",Boolean.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM heat WHERE number=4",Integer.class));
    }

    @Test void inicioRecuperaGradeAusenteEPersisteAvisoEBloqueio() {
        closeRegistration(); jdbc.execute("DELETE FROM heat");
        var c = championships.setStatus(1L,1L,ChampionshipStatus.IN_PROGRESS);
        assertTrue(c.heatsRequireReview()); assertEquals(6,heats.list(1L,null,1L).size());
        var original = layout();
        assertThrows(ApiException.class, () -> heats.generateAll(1L,1L,new GenerateAllHeatsRequest(2,first,2)));
        assertThrows(ApiException.class, () -> heats.savePlan(1L,1L,new HeatPlanRequest(plan())));
        assertThrows(ApiException.class, () -> championships.setStatus(1L,1L,ChampionshipStatus.PUBLISHED));
        assertThrows(ApiException.class, () -> context.getBean(CatalogService.class).deleteWorkout(1L,1L));
        assertThrows(ApiException.class, () -> championships.update(1L,1L,new ChampionshipRequest(c.name(),null,c.venueName(),null,c.city(),c.state(),
                c.startDate().plusDays(1),c.endDate(),null,null,c.scoringSystem(),100,5,null)));
        assertEquals(original,layout());
        heats.acknowledge(1L,1L); assertFalse(championships.adminDetail(1L,1L).heatsRequireReview());
    }

    @Test void fechamentoPorDataEInicioAutomaticoFuncionamSemNavegador() {
        configure(); jdbc.execute("UPDATE championship SET registration_closes_at=now()-interval '1 minute' WHERE id=1");
        var job = new HeatLifecycleJob(jdbc,context.getBean(PlatformTransactionManager.class),context);
        job.prepareClosedRegistrations();
        assertFalse(championships.adminDetail(1L,1L).registrationFlag());
        assertEquals(6,heats.list(1L,null,1L).size());
        jdbc.execute("DELETE FROM heat; UPDATE championship SET start_date=CURRENT_DATE WHERE id=1");
        job.prepareClosedRegistrations();
        assertTrue(championships.adminDetail(1L,1L).heatsRequireReview());
        assertThrows(ApiException.class, () -> heats.savePlan(1L,1L,new HeatPlanRequest(plan())));
    }

    @Test void resultadosDaBateriaSaoAtomicosEPermitemEdicaoPosterior() {
        closeRegistration();
        var heat = heats.list(1L,null,1L).stream().filter(h -> h.workoutId()==1L && h.lanes().size()==2).findFirst().orElseThrow();
        heats.start(heat.heatId(),1L,true); heats.changeStatus(heat.heatId(),1L,HeatStatus.FINISHED);
        var valid = new ScoreRequest(ScoreStatus.VALID,10000,null,null,null,null,"JUDGE");
        var invalid = new ScoreRequest(ScoreStatus.VALID,700000,null,null,null,null,"JUDGE");
        var entries = List.of(new HeatScoreEntry(heat.lanes().get(0).teamId(),valid),new HeatScoreEntry(heat.lanes().get(1).teamId(),invalid));
        assertThrows(RuntimeException.class, () -> scores.saveHeat(1L,new HeatScoresRequest(heat.heatId(),entries),1L));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM score",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM heat_lane WHERE judge_name IS NOT NULL",Integer.class));
        scores.saveHeat(1L,new HeatScoresRequest(heat.heatId(),heat.lanes().stream().map(l -> new HeatScoreEntry(l.teamId(),valid)).toList()),1L);
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM score",Integer.class));
        scores.save(1L,heat.lanes().getFirst().teamId(),new ScoreRequest(ScoreStatus.VALID,12000,null,null,null,null,"JUDGE 2"),1L);
        assertEquals(12000,jdbc.queryForObject("SELECT time_ms FROM score WHERE team_id=?",Integer.class,heat.lanes().getFirst().teamId()));
    }

    @Test void encerramentoBloqueiaTodasAsMutacoesDoOrganizador() {
        closeRegistration(); var plan=plan(); var heat=heats.list(1L,null,1L).getFirst();
        var c=championships.setStatus(1L,1L,ChampionshipStatus.FINISHED);
        var catalog=context.getBean(CatalogService.class); var teams=context.getBean(TeamService.class);
        assertThrows(ApiException.class, () -> championships.setRegistrationOpen(1L,1L,true));
        assertThrows(ApiException.class, () -> championships.setStatus(1L,1L,ChampionshipStatus.PUBLISHED));
        assertThrows(ApiException.class, () -> championships.update(1L,1L,new ChampionshipRequest(c.name(),null,c.venueName(),null,c.city(),c.state(),c.startDate(),c.endDate(),null,null,c.scoringSystem(),100,5,null)));
        assertThrows(ApiException.class, () -> catalog.createCategory(1L,1L,new CategoryRequest("Nova",null,1,null,BigDecimal.ZERO,0,CategoryGender.MALE,List.of())));
        assertThrows(ApiException.class, () -> catalog.deleteCategory(1L,1L));
        assertThrows(ApiException.class, () -> catalog.deleteWorkout(1L,1L));
        assertThrows(ApiException.class, () -> teams.changeStatus(1L,1L,TeamStatus.CANCELLED));
        assertThrows(ApiException.class, () -> heats.settings(1L,1L,new GenerateAllHeatsRequest(2,first,2)));
        assertThrows(ApiException.class, () -> heats.generateAll(1L,1L,new GenerateAllHeatsRequest(2,first,2)));
        assertThrows(ApiException.class, () -> heats.savePlan(1L,1L,new HeatPlanRequest(plan)));
        assertThrows(ApiException.class, () -> heats.start(heat.heatId(),1L,true));
        assertThrows(ApiException.class, () -> heats.changeStatus(heat.heatId(),1L,HeatStatus.FINISHED));
        assertThrows(ApiException.class, () -> scores.save(1L,1L,new ScoreRequest(ScoreStatus.DNS,null,null,null,null,null,"JUDGE"),1L));
        assertThrows(ApiException.class, () -> scores.delete(1L,1L,1L));
        assertEquals(6,heats.list(1L,null,1L).size());
    }

    @Test void encerramentoConcorrenteBloqueiaUmaEntidadeJaCarregada() throws Exception {
        closeRegistration();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var guard = context.getBean(OwnershipGuard.class);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(ignored -> {
                Championship cached = guard.championship(1L,1L);
                assertEquals(ChampionshipStatus.PUBLISHED,cached.getStatus());
                try {
                    executor.submit(() -> championships.setStatus(1L,1L,ChampionshipStatus.FINISHED))
                            .get(10,java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception error) { throw new RuntimeException(error); }
                assertThrows(ApiException.class, () -> guard.requireEditable(cached));
            });
        }
        assertEquals(ChampionshipStatus.FINISHED,championships.adminDetail(1L,1L).status());
    }

    @Test void edicaoDuranteCampeonatoCorrigeTextoEPreservaFormatoERaias() {
        closeRegistration(); championships.setStatus(1L,1L,ChampionshipStatus.IN_PROGRESS);
        var before=layout(); var catalog=context.getBean(CatalogService.class);
        var corrected=catalog.updateWorkout(1L,1L,new WorkoutRequest(null,1,"Nome corrigido","Descrição corrigida",WorkoutType.FOR_TIME,600,null,false));
        assertEquals("NOME CORRIGIDO",corrected.name());
        assertEquals("DESCRIÇÃO CORRIGIDA",corrected.description());
        assertEquals(before,layout());
        assertEquals("NOME CORRIGIDO",heats.list(1L,null,1L).getFirst().workoutName());
        assertThrows(ApiException.class, () -> catalog.updateWorkout(1L,1L,new WorkoutRequest(null,1,"Nome",null,WorkoutType.FOR_TIME,610,null,false)));
        assertThrows(ApiException.class, () -> catalog.updateWorkout(1L,1L,new WorkoutRequest(null,1,"Nome",null,WorkoutType.AMRAP,600,null,false)));
        assertThrows(ApiException.class, () -> catalog.createWorkouts(1L,1L,new WorkoutRequest(List.of(1L),3,"Nova",null,WorkoutType.FOR_TIME,600,null,false)));
        assertEquals(before,layout());
    }

    @Test void campeonatoCanceladoMantemProvasDisponiveisSomenteParaConsulta() {
        championships.setStatus(1L,1L,ChampionshipStatus.CANCELLED);
        var catalog=context.getBean(CatalogService.class);
        assertThrows(ApiException.class, () -> catalog.createWorkouts(1L,1L,new WorkoutRequest(List.of(1L),3,"Nova",null,WorkoutType.FOR_TIME,600,null,false)));
        assertThrows(ApiException.class, () -> catalog.updateWorkout(1L,1L,new WorkoutRequest(null,1,"Nome",null,WorkoutType.FOR_TIME,600,null,false)));
        assertThrows(ApiException.class, () -> catalog.deleteWorkout(1L,1L));
        assertEquals(2,championships.adminDetail(1L,1L).categories().getFirst().workouts().size());
    }

    @Test void limiteDaProvaBloqueiaNoventaEUmaRepsNaApiENoBanco() {
        jdbc.update("UPDATE workout SET max_reps=90 WHERE id=1");
        closeRegistration(); championships.setStatus(1L,1L,ChampionshipStatus.IN_PROGRESS);
        var heat=heats.list(1L,null,1L).getFirst();
        heats.start(heat.heatId(),1L,true); heats.changeStatus(heat.heatId(),1L,HeatStatus.FINISHED);
        Long team=heat.lanes().getFirst().teamId();
        assertThrows(ApiException.class, () -> scores.save(1L,team,new ScoreRequest(ScoreStatus.CAPPED,null,91,null,null,null,"JUDGE"),1L));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM score",Integer.class));
        assertEquals(90,scores.save(1L,team,new ScoreRequest(ScoreStatus.CAPPED,null,90,null,null,null,"JUDGE"),1L).reps());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("UPDATE score SET reps=91 WHERE workout_id=1"));
        assertEquals(90,jdbc.queryForObject("SELECT reps FROM score WHERE workout_id=1",Integer.class));
    }

    @Test void movimentoNaoPedidoMantemCargaEPersisteMarcacao() {
        jdbc.update("DELETE FROM team WHERE category_id=1");
        var catalog=context.getBean(CatalogService.class);
        var movement=new br.com.wodz.domain.MovementStandard("Snatch","45 kg","25 kg",false);
        var bodyweight=new br.com.wodz.domain.MovementStandard("Toes to bar","—","—",true,br.com.wodz.domain.MovementStandard.Kind.MOVEMENT);
        catalog.updateCategory(1L,1L,new CategoryRequest("RX",null,2,null,BigDecimal.ZERO,0,CategoryGender.MIXED,List.of(1),List.of(movement,bodyweight)));
        var items=championships.adminDetail(1L,1L).categories().getFirst().movementStandards();
        var stored=items.getFirst();
        assertFalse(stored.allowed()); assertEquals("45 kg",stored.male());
        assertEquals(br.com.wodz.domain.MovementStandard.Kind.LOAD,stored.kind());
        assertEquals(bodyweight,items.get(1));
    }

    @Test void provaLegadaPermitePreencherLimiteSemAlterarResultadosNemGrade() {
        closeRegistration(); championships.setStatus(1L,1L,ChampionshipStatus.IN_PROGRESS);
        var heat=heats.list(1L,null,1L).getFirst();
        heats.start(heat.heatId(),1L,true); heats.changeStatus(heat.heatId(),1L,HeatStatus.FINISHED);
        scores.save(1L,heat.lanes().getFirst().teamId(),new ScoreRequest(ScoreStatus.VALID,300000,null,null,null,null,"JUDGE"),1L);
        var before=layout();var catalog=context.getBean(CatalogService.class);
        var updated=catalog.updateWorkout(1L,1L,new WorkoutRequest(null,1,"Nome",null,WorkoutType.FOR_TIME,600,null,false,90));
        assertEquals(90,updated.maxReps()); assertEquals(before,layout());
        assertThrows(ApiException.class, () -> catalog.updateWorkout(1L,1L,new WorkoutRequest(null,1,"Nome",null,WorkoutType.FOR_TIME,600,null,false,91)));
    }

    @Test void concluirEIniciarProximaMantemUmaBateriaEmAndamentoComCronometro() {
        closeRegistration(); championships.setStatus(1L,1L,ChampionshipStatus.IN_PROGRESS);
        var before=layout(); var first=heats.list(1L,null,1L).getFirst();
        assertNotNull(heats.start(first.heatId(),1L,true).startedAt());
        heats.changeStatus(first.heatId(),1L,HeatStatus.FINISHED);
        var next=heats.list(1L,null,1L).stream().filter(h -> h.status()==HeatStatus.UPCOMING)
                .min(java.util.Comparator.comparing(HeatView::scheduledAt)).orElseThrow();
        var running=heats.start(next.heatId(),1L,true);
        assertEquals(HeatStatus.IN_PROGRESS,running.status()); assertNotNull(running.startedAt());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM heat WHERE status='IN_PROGRESS'",Integer.class));
        assertEquals("FINISHED",jdbc.queryForObject("SELECT status FROM heat WHERE id=?",String.class,first.heatId()));
        assertEquals(before,layout());
    }
}
