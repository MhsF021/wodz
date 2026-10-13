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
    @Test void cotacaoMultiplicaPrecoPorAtletaEPreservaTotalAposReajuste() {
        Category category = category(2, CategoryGender.MALE);
        category.setPrice(new BigDecimal("59.99"));
        var members = List.of(
                new MemberRequest("Capitão", "captain@test.invalid", "12345678909", LocalDate.of(1990,1,1), Gender.M, ShirtSize.M, "11999990000"),
                new MemberRequest("Parceiro", "partner@test.invalid", "00000000001", LocalDate.of(1990,1,1), Gender.M, ShirtSize.G, "21999990000"));
        var result = service.register("evento", 2L, new TeamRegistrationRequest(1L, "Dupla", null, null, members));
        assertEquals(new BigDecimal("119.98"), result.registrationAmount());
        var captured = org.mockito.ArgumentCaptor.forClass(Team.class);
        verify(teams).saveAndFlush(captured.capture());
        category.setPrice(new BigDecimal("89.99"));
        assertEquals(new BigDecimal("119.98"), captured.getValue().getRegistrationAmount());
    }

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
        athlete.setGender(Gender.M);
        athlete.setDocument("52998224725"); athlete.setPhone("11999990000");
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
        category.setChampionship(championship); category.setTeamSize(1); category.setGenderPolicy(CategoryGender.MALE); category.setPrice(new BigDecimal("150.00"));
        when(categories.lockById(1L)).thenReturn(Optional.of(category));
        var request = new TeamRegistrationRequest(1L, "Equipe", null, null, List.of(new MemberRequest(
                "Atleta", "athlete@test.invalid", "52998224725", LocalDate.of(1990,1,1), Gender.M, ShirtSize.M, "11999990000")));
        var result = service.register("evento", 2L, request);
        assertEquals(TeamStatus.PENDING_PAYMENT, result.status());
        assertEquals("ATLETA", result.name());
        assertEquals("11999990000", result.members().getFirst().phone());
        verify(teams).saveAndFlush(argThat(team -> team.getCaptain().getId().equals(2L) && team.getConfirmedAt() == null));
    }

    private Category category(int size, CategoryGender policy) {
        Category category = new Category(); category.setId(1L); category.setName("Categoria");
        category.setChampionship(fixture()); category.setTeamSize(size); category.setGenderPolicy(policy);
        when(categories.lockById(1L)).thenReturn(Optional.of(category));
        return category;
    }

    private MemberRequest member(String name, Gender gender) {
        return new MemberRequest(name, "athlete@test.invalid", "52998224725", LocalDate.of(1990,1,1), gender, ShirtSize.M, "11999990000");
    }

    @Test void individualDispensaNomeDaEquipeEPreservaNomeCompletoLongoDoAtleta() {
        category(1, CategoryGender.MALE);
        String name = "João " + "Silva ".repeat(18);
        var result = service.register("evento", 2L, new TeamRegistrationRequest(1L, null, null, null,
                List.of(member(name, Gender.M))));
        assertEquals(name.trim().toUpperCase(java.util.Locale.ROOT), result.name());
        assertEquals(result.members().getFirst().fullName(), result.name());
    }

    @Test void equipeComMaisDeUmAtletaContinuaExigindoNome() {
        category(2, CategoryGender.MALE);
        var request = new TeamRegistrationRequest(1L, " ", null, null,
                List.of(member("João", Gender.M), member("José", Gender.M)));
        assertEquals("Informe o nome da equipe", assertThrows(ApiException.class,
                () -> service.register("evento", 2L, request)).getMessage());
        verify(teams, never()).saveAndFlush(any());
    }

    @Test void homemNaoPodeUsarSexoFemininoParaInscreverSeEmCategoriaFeminina() {
        category(1, CategoryGender.FEMALE);
        var request = new TeamRegistrationRequest(1L, null, null, null, List.of(member("João", Gender.F)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ApiException.class,
                () -> service.register("evento", 2L, request)).getStatus());
        verify(teams, never()).saveAndFlush(any());
    }

    @Test void contaLegadaPrecisaCompletarSexoAntesDeSeInscrever() {
        category(1, CategoryGender.MALE);
        users.lockActiveById(2L).orElseThrow().setGender(null);
        var request = new TeamRegistrationRequest(1L, null, null, null, List.of(member("João", Gender.M)));
        assertEquals("Informe o sexo em Minha conta antes de se inscrever", assertThrows(ApiException.class,
                () -> service.register("evento", 2L, request)).getMessage());
    }

    @Test void contaLegadaPrecisaCompletarCpfETelefoneAntesDeSeInscrever() {
        category(1, CategoryGender.MALE);
        var athlete = users.lockActiveById(2L).orElseThrow();
        var request = new TeamRegistrationRequest(1L, null, null, null, List.of(member("João", Gender.M)));
        athlete.setDocument(null);
        assertThrows(ApiException.class, () -> service.register("evento", 2L, request));
        athlete.setDocument("52998224725"); athlete.setPhone(null);
        assertThrows(ApiException.class, () -> service.register("evento", 2L, request));
        verify(teams, never()).saveAndFlush(any());
    }

    @Test void inscricaoExigeTelefoneDeTodosOsAtletas() {
        category(2, CategoryGender.MALE);
        var request = new TeamRegistrationRequest(1L, "Equipe", null, null, List.of(member("João", Gender.M),
                new MemberRequest("José", "jose@test.invalid", "12345678909", LocalDate.of(1990,1,1), Gender.M, ShirtSize.G, " ")));
        assertEquals("Informe um telefone válido com DDD para cada atleta", assertThrows(ApiException.class,
                () -> service.register("evento", 2L, request)).getMessage());
        verify(teams, never()).saveAndFlush(any());
    }

    @Test void inscricaoExigeCamisetaDeTodosOsAtletas() {
        category(2, CategoryGender.MALE);
        var request = new TeamRegistrationRequest(1L, "Equipe", null, null, List.of(member("João", Gender.M),
                new MemberRequest("José", "jose@test.invalid", "12345678909", LocalDate.of(1990,1,1), Gender.M, null, "11999990000")));
        assertEquals("Selecione o tamanho da camiseta para cada atleta", assertThrows(ApiException.class,
                () -> service.register("evento", 2L, request)).getMessage());
        verify(teams, never()).saveAndFlush(any());
    }
}
