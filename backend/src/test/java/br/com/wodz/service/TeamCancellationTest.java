package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.*;
import br.com.wodz.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TeamCancellationTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamService service = new TeamService(teams, mock(CategoryRepository.class), mock(AppUserRepository.class),
            mock(ChampionshipService.class), mock(ScheduleQueryService.class), mock(OwnershipGuard.class), mock(JdbcTemplate.class));

    private Team fixture(TeamStatus status) {
        AppUser captain = new AppUser(); captain.setId(2L);
        Championship championship = new Championship(); championship.setId(10L);
        Category category = new Category(); category.setId(3L); category.setChampionship(championship);
        Team team = new Team(); team.setId(7L); team.setCaptain(captain); team.setCategory(category); team.setStatus(status);
        when(teams.lockById(7L)).thenReturn(Optional.of(team));
        return team;
    }

    @Test void capitaoCancelaInscricaoPendenteComLockDaEquipe() {
        Team team = fixture(TeamStatus.PENDING_PAYMENT);
        assertEquals(TeamStatus.CANCELLED, service.cancelRegistration(7L, 2L).status());
        assertNull(team.getConfirmedAt());
        var order = inOrder(teams);
        order.verify(teams).lockById(7L);
        order.verify(teams).saveAndFlush(team);
    }

    @Test void outroAtletaNaoPodeCancelarNemConsultarInscricaoPeloEndpoint() {
        Team team = fixture(TeamStatus.PENDING_PAYMENT);
        var error = assertThrows(ApiException.class, () -> service.cancelRegistration(7L, 9L));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals(TeamStatus.PENDING_PAYMENT, team.getStatus());
        verify(teams, never()).saveAndFlush(any());
    }

    @Test void equipeInexistenteRetornaNaoEncontrada() {
        when(teams.lockById(7L)).thenReturn(Optional.empty());
        var error = assertThrows(ApiException.class, () -> service.cancelRegistration(7L, 2L));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        verify(teams, never()).saveAndFlush(any());
    }

    @Test void equipeConfirmadaInclusivePorPagamentoSimultaneoNaoPodeSerCanceladaPeloCapitao() {
        Team team = fixture(TeamStatus.CONFIRMED);
        var error = assertThrows(ApiException.class, () -> service.cancelRegistration(7L, 2L));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(TeamStatus.CONFIRMED, team.getStatus());
        verify(teams, never()).saveAndFlush(any());
    }

    @Test void repetirCancelamentoDaPropriaEquipeNaoGeraErroNemNovaAlteracao() {
        fixture(TeamStatus.CANCELLED);
        assertEquals(TeamStatus.CANCELLED, service.cancelRegistration(7L, 2L).status());
        verify(teams, never()).saveAndFlush(any());
    }
}
