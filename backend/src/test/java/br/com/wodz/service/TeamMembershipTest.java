package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.*;
import br.com.wodz.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamMembershipTest {
    final TeamRepository teams = mock(TeamRepository.class);
    final AppUserRepository users = mock(AppUserRepository.class);
    final ChampionshipService championships = mock(ChampionshipService.class);
    final ScheduleQueryService schedule = mock(ScheduleQueryService.class);
    final TeamService service = new TeamService(teams, mock(CategoryRepository.class), users,
            championships, schedule, mock(OwnershipGuard.class), mock(JdbcTemplate.class));
    final AppUser user = new AppUser();
    final Team team = new Team();
    final TeamMember member = new TeamMember();

    TeamMembershipTest() {
        user.setId(9L); user.setEmail("friend@test.invalid");
        var captain = new AppUser(); captain.setId(2L); captain.setName("Capitão");
        var championship = new Championship(); championship.setId(10L);
        var category = new Category(); category.setId(3L); category.setTeamSize(2); category.setChampionship(championship);
        team.setId(7L); team.setName("EQUIPE"); team.setCaptain(captain); team.setCategory(category);
        team.setStatus(TeamStatus.CONFIRMED);
        member.setEmail("FRIEND@test.invalid"); team.addMember(member);
        when(championships.findPublic("evento")).thenReturn(championship);
        when(users.findById(9L)).thenReturn(Optional.of(user));
        when(users.lockActiveById(9L)).thenReturn(Optional.of(user));
        when(teams.lockById(7L)).thenReturn(Optional.of(team));
    }

    @Test void candidateOnlyExposesTeamAndCaptainAndDoesNotLink() {
        when(teams.findLinkCandidates(10L, user.getEmail())).thenReturn(List.of(team));
        var candidate = service.linkCandidates("evento", 9L).getFirst();
        assertEquals("EQUIPE", candidate.teamName()); assertEquals("Capitão", candidate.captainName());
        assertNull(member.getUser()); verify(teams, never()).saveAndFlush(any());
    }

    @Test void linksBySessionEmailAndRepeatedAcceptanceIsIdempotent() {
        assertEquals(7L, service.linkMember("evento", 7L, 9L).id());
        assertSame(user, member.getUser());
        service.linkMember("evento", 7L, 9L);
        verify(teams, times(1)).saveAndFlush(team);
    }

    @Test void wrongEmailOrAlreadyClaimedMemberCannotBeLinked() {
        user.setEmail("other@test.invalid");
        assertThrows(ApiException.class, () -> service.linkMember("evento", 7L, 9L));
        user.setEmail("friend@test.invalid"); member.setUser(team.getCaptain());
        assertThrows(ApiException.class, () -> service.linkMember("evento", 7L, 9L));
        verify(teams, never()).saveAndFlush(any());
    }

    @Test void cancelledOrDifferentChampionshipCannotBeLinked() {
        team.setStatus(TeamStatus.CANCELLED);
        assertThrows(ApiException.class, () -> service.linkMember("evento", 7L, 9L));
        team.setStatus(TeamStatus.CONFIRMED); team.getCategory().getChampionship().setId(11L);
        var other = new Championship(); other.setId(10L);
        when(championships.findPublic("evento")).thenReturn(other);
        assertThrows(ApiException.class, () -> service.linkMember("evento", 7L, 9L));
    }

    @Test void existingRegistrationPreventsAnotherLink() {
        when(teams.findAccessibleByUserId(9L)).thenReturn(List.of(team));
        assertTrue(service.linkCandidates("evento", 9L).isEmpty());
        assertThrows(ApiException.class, () -> service.linkMember("evento", 7L, 9L));
        assertNull(member.getUser());
    }

    @Test void linkedMemberSeesTeamsAndHeatsButCannotCancelOrRename() {
        member.setUser(user); when(teams.findAccessibleByUserId(9L)).thenReturn(List.of(team));
        assertEquals(7L, service.myTeams(9L).getFirst().id());
        assertEquals(List.of(7L), service.myHeats(9L).teamIds());
        verify(schedule).forTeams(List.of(7L));
        assertThrows(ApiException.class, () -> service.cancelRegistration(7L, 9L));
        assertThrows(ApiException.class, () -> service.rename(7L, 9L, "Novo nome"));
    }

    @Test void onlyCaptainRenamesTeamWithValidatedUppercaseName() {
        assertEquals("NOVA EQUIPE", service.rename(7L, 2L, " Nova equipe ").name());
        assertThrows(ApiException.class, () -> service.rename(7L, 2L, " "));
        assertThrows(ApiException.class, () -> service.rename(7L, 2L, "x".repeat(81)));
        team.getCategory().setTeamSize(1);
        assertThrows(ApiException.class, () -> service.rename(7L, 2L, "Individual"));
    }
}
