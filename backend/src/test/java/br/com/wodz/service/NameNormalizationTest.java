package br.com.wodz.service;

import br.com.wodz.common.Codes;
import br.com.wodz.domain.*;
import br.com.wodz.dto.ChampionshipDtos.CategoryRequest;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipRequest;
import br.com.wodz.dto.ChampionshipDtos.WorkoutRequest;
import br.com.wodz.dto.ScoreDtos.ScoreRequest;
import br.com.wodz.dto.TeamDtos.MemberRequest;
import br.com.wodz.dto.TeamDtos.TeamRegistrationRequest;
import br.com.wodz.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifica os valores enviados para gravação, mesmo com requisições que ignoram o frontend. */
class NameNormalizationTest {

    @Test
    void campeonatoGravaNomesEDescricaoEmMaiusculasPreservandoUrlESlug() {
        ChampionshipRepository championships = mock(ChampionshipRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        WorkoutRepository workouts = mock(WorkoutRepository.class);
        OwnershipGuard guard = mock(OwnershipGuard.class);
        Championship championship = championship();
        championship.setSlug("torneio-acao-2030");
        when(guard.championship(1L, 3L)).thenReturn(championship);
        ChampionshipService service = new ChampionshipService(championships, categories, workouts,
                mock(AppUserRepository.class), guard, mock(JdbcTemplate.class), mock(PayoutAccountService.class));
        LocalDate start = LocalDate.now().plusMonths(2);
        String banner = "https://example.test/Foto.jpg?token=AbCd";

        service.update(1L, 3L, new ChampionshipRequest(" Torneio Ação ", " Check-in às 8h\nKit atleta ",
                " Ginásio São João ", " Rua José, 10 ", " São Paulo ", "sp", start, start.plusDays(1),
                null, null, ScoringSystem.POINTS_TABLE, 100, 5, banner));

        ArgumentCaptor<Championship> saved = ArgumentCaptor.forClass(Championship.class);
        verify(championships).saveAndFlush(saved.capture());
        Championship value = saved.getValue();
        assertEquals("TORNEIO AÇÃO", value.getName());
        assertEquals("CHECK-IN ÀS 8H\nKIT ATLETA", value.getDescription());
        assertEquals("GINÁSIO SÃO JOÃO", value.getVenueName());
        assertEquals("RUA JOSÉ, 10", value.getAddress());
        assertEquals("SÃO PAULO", value.getCity());
        assertEquals("SP", value.getState());
        assertEquals(banner, value.getBannerUrl());
        assertEquals("torneio-acao-2030", value.getSlug());
    }

    @Test
    void categoriaEProvaGravamNomeEDescricaoMantendoNumerosEQuebrasDeLinha() {
        CategoryRepository categories = mock(CategoryRepository.class);
        WorkoutRepository workouts = mock(WorkoutRepository.class);
        OwnershipGuard guard = mock(OwnershipGuard.class);
        Championship championship = championship();
        Category category = category(championship);
        when(guard.championship(1L, 3L)).thenReturn(championship);
        when(guard.category(2L, 3L)).thenReturn(category);
        CatalogService service = new CatalogService(categories, workouts, mock(TeamRepository.class),
                mock(ScoreRepository.class), guard);

        service.createCategory(1L, 3L, new CategoryRequest(" Iniciante ", " Duplas mistas ",
                2, null, BigDecimal.ZERO, 0));
        ArgumentCaptor<Category> categorySaved = ArgumentCaptor.forClass(Category.class);
        verify(categories).saveAndFlush(categorySaved.capture());
        assertEquals("INICIANTE", categorySaved.getValue().getName());
        assertEquals("DUPLAS MISTAS", categorySaved.getValue().getDescription());

        service.createWorkouts(1L, 3L, new WorkoutRequest(List.of(2L), 1, " Abertura ",
                "21-15-9\nThrusters 43/30 kg\nPull-ups", WorkoutType.FOR_TIME, 600, null, false));
        ArgumentCaptor<Workout> workoutSaved = ArgumentCaptor.forClass(Workout.class);
        verify(workouts).saveAndFlush(workoutSaved.capture());
        assertEquals("ABERTURA", workoutSaved.getValue().getName());
        assertEquals("21-15-9\nTHRUSTERS 43/30 KG\nPULL-UPS", workoutSaved.getValue().getDescription());
        assertEquals(600, workoutSaved.getValue().getTimeCapSeconds());
    }

    @Test
    void equipeEAtletasGravamNomesMaiusculosSemAlterarEmailOuCodigo() {
        TeamRepository teams = mock(TeamRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        ChampionshipService championships = mock(ChampionshipService.class);
        Championship championship = championship();
        Category category = category(championship);
        when(championships.findPublic("torneio-2030")).thenReturn(championship);
        when(categories.findById(2L)).thenReturn(Optional.of(category));
        AppUser captain = new AppUser();
        captain.setId(3L);
        when(users.lockActiveById(3L)).thenReturn(Optional.of(captain));
        TeamService service = new TeamService(teams, categories, users, championships,
                mock(ScheduleQueryService.class), mock(OwnershipGuard.class), mock(JdbcTemplate.class));

        var response = service.register("torneio-2030", 3L, new TeamRegistrationRequest(2L, " Família Forte ",
                " Box São José ", " São Paulo ", List.of(new MemberRequest(" João D'Ávila ",
                "JOAO@wodz.dev", "52998224725", LocalDate.of(1990, 1, 1), Gender.M, ShirtSize.M))));

        ArgumentCaptor<Team> saved = ArgumentCaptor.forClass(Team.class);
        verify(teams).saveAndFlush(saved.capture());
        Team value = saved.getValue();
        assertEquals("FAMÍLIA FORTE", value.getName());
        assertEquals("BOX SÃO JOSÉ", value.getBoxName());
        assertEquals("SÃO PAULO", value.getCity());
        assertEquals("JOÃO D'ÁVILA", value.getMembers().getFirst().getFullName());
        assertEquals("joao@wodz.dev", value.getMembers().getFirst().getEmail());
        assertEquals("52998224725", value.getMembers().getFirst().getCpf());
        assertEquals(value.getRegistrationCode(), response.registrationCode());
    }

    @Test
    void judgeGravaNomeMaiusculoComJdbcPreservandoObservacaoLivre() throws Exception {
        WorkoutRepository workouts = mock(WorkoutRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        ScoreRepository scores = mock(ScoreRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Championship championship = championship();
        Category category = category(championship);
        Workout workout = new Workout();
        workout.setCategory(category);
        workout.setId(5L);
        Team team = new Team();
        team.setId(7L);
        team.setCategory(category);
        when(workouts.lockById(5L)).thenReturn(Optional.of(workout));
        when(teams.findById(7L)).thenReturn(Optional.of(team));
        ResultSet lane = mock(ResultSet.class);
        when(lane.getLong("team_id")).thenReturn(7L);
        when(lane.getLong("lane_id")).thenReturn(9L);
        when(lane.getString("heat_status")).thenReturn("FINISHED");
        doAnswer(invocation -> {
            invocation.<RowCallbackHandler>getArgument(1).processRow(lane);
            return null;
        }).when(jdbc).query(contains("FROM heat_lane"), any(RowCallbackHandler.class), eq(5L), eq(7L));
        ScoreService service = new ScoreService(scores, workouts, teams, mock(OwnershipGuard.class),
                jdbc, mock(ApplicationEventPublisher.class));

        service.save(5L, 7L, new ScoreRequest(ScoreStatus.DNS, null, null, null, null,
                "Observação livre", " José da Silva "), 3L);

        verify(jdbc).update("UPDATE heat_lane SET judge_name = ? WHERE id = ?", "JOSÉ DA SILVA", 9L);
        ArgumentCaptor<Score> saved = ArgumentCaptor.forClass(Score.class);
        verify(scores).saveAndFlush(saved.capture());
        assertEquals("Observação livre", saved.getValue().getNotes());
    }

    @Test
    void conversaoPreservaAcentosENullIndependenteDoIdiomaDoServidor() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("INICIAÇÃO SÃO JOÃO", Codes.uppercase("iniciação são joão"));
            assertNull(Codes.uppercase(null));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static Championship championship() {
        Championship championship = new Championship();
        championship.setId(1L);
        championship.setSlug("torneio-2030");
        championship.setStartDate(LocalDate.now().plusMonths(2));
        championship.setEndDate(championship.getStartDate().plusDays(1));
        championship.setStatus(ChampionshipStatus.PUBLISHED);
        championship.setRegistrationOpen(true);
        return championship;
    }

    private static Category category(Championship championship) {
        Category category = new Category();
        category.setId(2L);
        category.setChampionship(championship);
        category.setTeamSize(1);
        return category;
    }
}
