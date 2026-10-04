package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Role;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.dto.AuthDtos.ChangePasswordRequest;
import br.com.wodz.dto.AuthDtos.DeleteAccountRequest;
import br.com.wodz.dto.AuthDtos.UpdateProfileRequest;
import br.com.wodz.dto.AuthDtos.UserDto;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.repository.ChampionshipRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.service.GoogleTokenVerifier.GoogleProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private static final String SENHA = "senha-atual-123";

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ChampionshipRepository championships = mock(ChampionshipRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
    private final AccountService service = new AccountService(users, championships, teams, encoder, google);

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setId(7L);
        user.setName("Ana Souza");
        user.setEmail("ana@wodz.dev");
        user.setPhone("11999990000");
        user.setRole(Role.ATHLETE);
        user.setPasswordHash(encoder.encode(SENHA));
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    @Test
    void atualizaNomeECelularSemPedirSenha() {
        UserDto dto = service.update(7L, new UpdateProfileRequest("  Ana Paula Souza ", "ANA@wodz.dev", " ", null));

        assertEquals("Ana Paula Souza", dto.name());
        assertEquals("ana@wodz.dev", dto.email());
        assertNull(dto.phone());
    }

    @Test
    void trocarEmailExigeSenhaAtual() {
        ApiException semSenha = assertThrows(ApiException.class,
                () -> service.update(7L, new UpdateProfileRequest("Ana", "nova@wodz.dev", null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, semSenha.getStatus());

        ApiException senhaErrada = assertThrows(ApiException.class,
                () -> service.update(7L, new UpdateProfileRequest("Ana", "nova@wodz.dev", null, "errada")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, senhaErrada.getStatus());
        assertEquals("ana@wodz.dev", user.getEmail());
    }

    @Test
    void trocaEmailComSenhaCorreta() {
        UserDto dto = service.update(7L, new UpdateProfileRequest("Ana", " Nova@Wodz.dev ", null, SENHA));

        assertEquals("nova@wodz.dev", dto.email());
    }

    @Test
    void recusaEmailDeOutraConta() {
        when(users.existsByEmailIgnoreCaseAndIdNot("outra@wodz.dev", 7L)).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(7L, new UpdateProfileRequest("Ana", "outra@wodz.dev", null, SENHA)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void trocaSenha() {
        service.changePassword(7L, new ChangePasswordRequest(SENHA, "nova-senha-456"));

        assertTrue(encoder.matches("nova-senha-456", user.getPasswordHash()));
    }

    @Test
    void novaSenhaPrecisaSerDiferente() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.changePassword(7L, new ChangePasswordRequest(SENHA, SENHA)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void excluirAnonimizaContaECancelaInscricoesPendentes() {
        Team pendente = new Team();
        pendente.setStatus(TeamStatus.PENDING_PAYMENT);
        when(teams.findByCaptainIdAndStatus(7L, TeamStatus.PENDING_PAYMENT)).thenReturn(List.of(pendente));

        service.delete(7L, new DeleteAccountRequest(SENHA));

        assertTrue(user.isDeleted());
        assertNotNull(user.getDeletedAt());
        assertEquals("Conta excluída", user.getName());
        assertEquals("excluida-7@wodz.invalid", user.getEmail());
        assertNull(user.getPhone());
        assertFalse(encoder.matches(SENHA, user.getPasswordHash()));
        assertEquals(TeamStatus.CANCELLED, pendente.getStatus());
    }

    @Test
    void excluirExigeSenhaCorreta() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.delete(7L, new DeleteAccountRequest("errada")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertFalse(user.isDeleted());
    }

    @Test
    void naoExcluiOrganizadorComCampeonatoPublicadoOuEmAndamento() {
        when(championships.existsByOwnerIdAndStatusIn(eq(7L), any())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> service.delete(7L, new DeleteAccountRequest(SENHA)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertFalse(user.isDeleted());
    }

    @Test
    void naoExcluiCapitaoDeEquipeConfirmadaEmCampeonatoNaoEncerrado() {
        when(teams.existsByCaptainIdAndStatusAndCategory_Championship_StatusNotIn(anyLong(), eq(TeamStatus.CONFIRMED), any()))
                .thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> service.delete(7L, new DeleteAccountRequest(SENHA)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertFalse(user.isDeleted());
    }

    @Test
    void contaExcluidaNaoPodeSerAlterada() {
        user.setDeletedAt(Instant.now());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(7L, new UpdateProfileRequest("Ana", "ana@wodz.dev", null, null)));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    // ------------------------------------------------- conta criada com o Google (sem senha)

    private void contaSoComGoogle() {
        user.setPasswordHash(null);
        user.setGoogleSub("google-123");
    }

    @Test
    void contaDoGoogleCriaPrimeiraSenhaSemPedirAAtual() {
        contaSoComGoogle();

        service.changePassword(7L, new ChangePasswordRequest(null, "primeira-senha-1"));

        assertTrue(encoder.matches("primeira-senha-1", user.getPasswordHash()));
    }

    @Test
    void contaComSenhaContinuaPedindoASenhaAtual() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.changePassword(7L, new ChangePasswordRequest(" ", "nova-senha-456")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void contaDoGooglePrecisaDeSenhaParaTrocarEmail() {
        contaSoComGoogle();

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(7L, new UpdateProfileRequest("Ana", "nova@wodz.dev", null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("ana@wodz.dev", user.getEmail());
    }

    @Test
    void contaDoGoogleExcluiConfirmandoComAMesmaContaGoogle() {
        contaSoComGoogle();
        when(google.verify("credencial")).thenReturn(new GoogleProfile("google-123", "ana@wodz.dev", "Ana"));

        service.delete(7L, new DeleteAccountRequest(null, "credencial"));

        assertTrue(user.isDeleted());
        assertNull(user.getGoogleSub());
    }

    @Test
    void contaDoGoogleNaoExcluiComOutraContaGoogle() {
        contaSoComGoogle();
        when(google.verify("outra")).thenReturn(new GoogleProfile("google-999", "x@wodz.dev", "X"));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.delete(7L, new DeleteAccountRequest(null, "outra")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertFalse(user.isDeleted());
    }
}
