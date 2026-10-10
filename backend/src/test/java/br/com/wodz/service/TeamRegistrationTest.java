package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.*;
import br.com.wodz.dto.TeamDtos.*;
import br.com.wodz.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamRegistrationTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ChampionshipService championships = mock(ChampionshipService.class);
    private final TeamService service = new TeamService(teams, categories, users, championships,
            mock(ScheduleQueryService.class), mock(OwnershipGuard.class), mock(JdbcTemplate.class));

    private Championship fixture() {
        Championship championship = new Championship();
        championship.setId(10L); championship.setSlug("evento"); championship.setName("EVENTO");
        championship.setStatus(ChampionshipStatus.PUBLISHED); championship.setRegistrationOpen(true);
        championship.setStartDate(LocalDate.now().plusDays(30)); championship.setEndDate(LocalDate.now().plusDays(31));
        when(championships.findPublic("evento")).thenReturn(championship);
        AppUser athlete = new AppUser(); athlete.setId(2L); athlete.setRole(Role.ATHLETE);
        when(users.lockActiveById(2L)).thenReturn(Optional.of(athlete));
        return championship;
    }

    @Test void contaComInscricaoAtivaRecebeConflitoAntesDeCriarOutraEquipe() {
        fixture();
        when(teams.existsByCaptainIdAndCategory_Championship_IdAndStatusNot(2L, 10L, TeamStatus.CANCELLED)).thenReturn(true);
        var error = assertThrows(ApiException.class, () -> service.register("evento", 2L,
                new TeamRegistrationRequest(99L, "Outra equipe", null, null, List.of())));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("Você já possui uma inscrição para esse campeonato", error.getMessage());
        var order = inOrder(users, teams);
        order.verify(users).lockActiveById(2L);
        order.verify(teams).existsByCaptainIdAndCategory_Championship_IdAndStatusNot(2L, 10L, TeamStatus.CANCELLED);
        verify(teams, never()).saveAndFlush(any()); verifyNoInteractions(categories);
    }

    @Test void primeiraInscricaoPagaPermanecePendenteEUsaContaBloqueadaComoCapitao() {
        Championship championship = fixture();
        Category category = new Category(); category.setId(1L); category.setName("RX");
        category.setChampionship(championship); category.setTeamSize(1); category.setPrice(new BigDecimal("150.00"));
        when(categories.findById(1L)).thenReturn(Optional.of(category));
        var request = new TeamRegistrationRequest(1L, "Equipe", null, null, List.of(new MemberRequest(
                "Atleta", "athlete@test.invalid", "52998224725", LocalDate.of(1990,1,1), Gender.M, ShirtSize.M)));
        var result = service.register("evento", 2L, request);
        assertEquals(TeamStatus.PENDING_PAYMENT, result.status());
        verify(teams).saveAndFlush(argThat(team -> team.getCaptain().getId().equals(2L) && team.getConfirmedAt() == null));
    }
}
