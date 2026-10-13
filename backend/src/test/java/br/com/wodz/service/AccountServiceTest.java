package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Role;
import br.com.wodz.domain.Gender;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.dto.AuthDtos.ChangePasswordRequest;
import br.com.wodz.dto.AuthDtos.DeleteAccountRequest;
import br.com.wodz.dto.AuthDtos.UpdateProfileRequest;
import br.com.wodz.dto.AuthDtos.UnlinkGoogleRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AccountServiceTest {

    private static final String SENHA = "senha-atual-123";

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ChampionshipRepository championships = mock(ChampionshipRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
    private final PayoutAccountService payoutAccounts = mock(PayoutAccountService.class);
    private final AccountService service = new AccountService(users, championships, teams, encoder, google, payoutAccounts);

    private AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setId(7L);
        user.setName("Ana Souza");
        user.setEmail("ana@wodz.dev");
        user.setPhone("11999990000");
        user.setDocument("52998224725");
        user.setRole(Role.ATHLETE);
        user.setGender(Gender.F);
        user.setPasswordHash(encoder.encode(SENHA));
        when(users.lockActiveById(7L)).thenReturn(Optional.of(user));
    }

    @Test
    void atualizaNomeECelularSemPedirSenha() {
        UserDto dto = service.update(7L, new UpdateProfileRequest("  Ana Paula Souza ", "ANA@wodz.dev", "11999990000", null, Gender.F,
                "529.982.247-25", null, null, null, null));

        assertEquals("ANA PAULA SOUZA", dto.name());
        assertEquals("ANA PAULA SOUZA", user.getName());
        assertEquals("ana@wodz.dev", dto.email());
        assertEquals("11999990000", dto.phone());
        assertEquals("52998224725", dto.document());
        assertEquals(Gender.F, dto.gender());
    }

    @Test void sairRevogaCredenciaisEAutorizacoesPendentesSemApagarPerfil() {
        service.logout(7L);
        assertEquals(1, user.getTokenVersion());
        assertFalse(user.isDeleted());
        verify(users).lockActiveById(7L);
        verify(payoutAccounts).cancelPendingAuthorizations(7L);
    }

    @Test void perfilAtletaExigeCpfETelefone() {
        for (String phone : new String[] {null, "", " ", "99999-0000"}) {
            assertThrows(ApiException.class, () -> service.update(7L, new UpdateProfileRequest(
                    "Ana", user.getEmail(), phone, null, Gender.F, "52998224725", null, null, null, null)));
        }
        for (String document : new String[] {null, "", "52998224724", "11.222.333/0001-81"}) {
            assertThrows(ApiException.class, () -> service.update(7L, new UpdateProfileRequest(
                    "Ana", user.getEmail(), "11999990000", null, Gender.F, document, null, null, null, null)));
        }
    }

    @Test void organizadorSalvaEnderecoSeparadoEPodeDeixarEnderecoETelefoneVazios() {
        user.setRole(Role.ORGANIZER);
        user.setGender(null);
        var dto = service.update(7L, new UpdateProfileRequest("Ana", user.getEmail(), "", null, null,
                "11.222.333/0001-81", " Rua São João ", "10 A", " São Paulo ", "01310-100"));
        assertEquals("11222333000181", dto.document());
        assertNull(dto.gender());
        assertEquals("RUA SÃO JOÃO", dto.street()); assertEquals("10 A", dto.addressNumber());
        assertEquals("SÃO PAULO", dto.city()); assertEquals("01310100", dto.postalCode()); assertNull(dto.phone());
        dto = service.update(7L, new UpdateProfileRequest("Ana", user.getEmail(), null, null, Gender.F,
                "00.000.000/E08G-12", null, null, null, null));
        assertEquals("00000000E08G12", dto.document());
        assertNull(dto.street()); assertNull(dto.addressNumber()); assertNull(dto.city()); assertNull(dto.postalCode());
        assertThrows(ApiException.class, () -> service.update(7L, new UpdateProfileRequest("Ana", user.getEmail(), null,
                null, Gender.F, "", null, null, null, null)));
    }

    @Test
    void trocarEmailExigeSenhaAtual() {
        ApiException semSenha = assertThrows(ApiException.class,
                () -> service.update(7L, new UpdateProfileRequest("Ana", "nova@wodz.dev", "11999990000", null, Gender.F,
                        "52998224725", null, null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, semSenha.getStatus());

        ApiException senhaErrada = assertThrows(ApiException.class,
                () -> service.update(7L, new UpdateProfileRequest("Ana", "nova@wodz.dev", "11999990000", "errada", Gender.F,
                        "52998224725", null, null, null, null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, senhaErrada.getStatus());
        assertEquals("ana@wodz.dev", user.getEmail());
    }

    @Test
    void trocaEmailComSenhaCorreta() {
        UserDto dto = service.update(7L, new UpdateProfileRequest("Ana", " Nova@Wodz.dev ", "11999990000", SENHA, Gender.F,
                "52998224725", null, null, null, null));

        assertEquals("nova@wodz.dev", dto.email());
    }

    @Test
    void recusaEmailDeOutraConta() {
        when(users.existsByEmailIgnoreCaseAndIdNot("outra@wodz.dev", 7L)).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(7L, new UpdateProfileRequest("Ana", "outra@wodz.dev", "11999990000", SENHA, Gender.F,
                        "52998224725", null, null, null, null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void trocaSenha() {
        service.changePassword(7L, new ChangePasswordRequest(SENHA, "nova-senha-456"));

        assertTrue(encoder.matches("nova-senha-456", user.getPasswordHash()));
        assertEquals(1L, user.getTokenVersion());
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
        assertNull(user.getGender());
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
                () -> service.update(7L, new UpdateProfileRequest("Ana", "ana@wodz.dev", "11999990000", null, Gender.F,
                        "52998224725", null, null, null, null)));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    // ------------------------------------------------- conta criada com o Google (sem senha)

    private void contaSoComGoogle() {
        user.setPasswordHash(null);
        user.setGoogleSub("google-123");
    }

    @Test
    void contaDoGoogleCriaPrimeiraSenhaConfirmandoIdentidadeGoogle() {
        contaSoComGoogle();
        when(google.verify("credencial")).thenReturn(new GoogleProfile("google-123", "ana@wodz.dev", "Ana"));

        service.changePassword(7L, new ChangePasswordRequest(null, "primeira-senha-1", "credencial"));

        assertTrue(encoder.matches("primeira-senha-1", user.getPasswordHash()));
        assertEquals(1L, user.getTokenVersion());
    }

    @Test
    void primeiraSenhaNaoPodeSerCriadaSomenteComSessaoOuOutraContaGoogle() {
        contaSoComGoogle();
        ApiException missing = assertThrows(ApiException.class,
                () -> service.changePassword(7L, new ChangePasswordRequest(null, "primeira-senha-1")));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatus());
        when(google.verify("outra")).thenReturn(new GoogleProfile("google-999", "x@wodz.dev", "X"));
        ApiException other = assertThrows(ApiException.class,
                () -> service.changePassword(7L, new ChangePasswordRequest(null, "primeira-senha-1", "outra")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, other.getStatus());
        assertNull(user.getPasswordHash());
        assertEquals(0L, user.getTokenVersion());
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
                () -> service.update(7L, new UpdateProfileRequest("Ana", "nova@wodz.dev", "11999990000", null, Gender.F,
                        "52998224725", null, null, null, null)));
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
        verify(payoutAccounts).eraseUserData(7L);
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

    private void vincularGoogle() {
        user.setGoogleSub("google-123");
        when(google.verify("credencial")).thenReturn(new GoogleProfile("google-123", "ana@wodz.dev", "Ana"));
    }

    @Test
    void contaComGoogleESenhaPodeExcluirConfirmandoSoComGoogle() {
        vincularGoogle();

        service.delete(7L, new DeleteAccountRequest(null, "credencial"));

        assertTrue(user.isDeleted());
        assertNull(user.getGoogleSub());
        assertTrue(user.isGoogleLinkDisabled());
        assertEquals(1L, user.getTokenVersion());
        verify(payoutAccounts).eraseUserData(7L);
    }

    @Test
    void contaComGoogleESenhaPodeExcluirComASenhaAtualComoAlternativa() {
        vincularGoogle();

        service.delete(7L, new DeleteAccountRequest(SENHA));

        assertTrue(user.isDeleted());
        assertNull(user.getGoogleSub());
        verifyNoInteractions(google);
    }

    @Test
    void desvincularGoogleComSenhaPreservaContaLocalERevogaSessoes() {
        vincularGoogle();
        String passwordHash = user.getPasswordHash();

        service.unlinkGoogle(7L, new UnlinkGoogleRequest("credencial"));

        assertFalse(user.isDeleted());
        assertNull(user.getGoogleSub());
        assertTrue(user.isGoogleLinkDisabled());
        assertEquals(1L, user.getTokenVersion());
        assertEquals(passwordHash, user.getPasswordHash());
        assertTrue(encoder.matches(SENHA, user.getPasswordHash()));
        assertEquals("ana@wodz.dev", user.getEmail());
        assertEquals("Ana Souza", user.getName());
        assertEquals("11999990000", user.getPhone());
        verifyNoInteractions(championships, teams, payoutAccounts);
    }

    @Test
    void desvincularGoogleSemSenhaEncerraContaECancelaInscricoesPendentes() {
        contaSoComGoogle();
        vincularGoogle();
        Team pending = new Team();
        pending.setStatus(TeamStatus.PENDING_PAYMENT);
        when(teams.findByCaptainIdAndStatus(7L, TeamStatus.PENDING_PAYMENT)).thenReturn(List.of(pending));

        service.unlinkGoogle(7L, new UnlinkGoogleRequest("credencial"));

        assertTrue(user.isDeleted());
        assertEquals("Conta excluída", user.getName());
        assertEquals("excluida-7@wodz.invalid", user.getEmail());
        assertNull(user.getPhone());
        assertNull(user.getGoogleSub());
        assertTrue(user.isGoogleLinkDisabled());
        assertEquals(1L, user.getTokenVersion());
        assertEquals(TeamStatus.CANCELLED, pending.getStatus());
        verify(payoutAccounts).eraseUserData(7L);
    }

    @Test
    void desvincularNaoAceitaOutraContaGoogleComOuSemSenhaLocal() {
        vincularGoogle();
        when(google.verify("outra")).thenReturn(new GoogleProfile("google-999", "x@wodz.dev", "X"));
        for (boolean hasPassword : List.of(true, false)) {
            if (!hasPassword) user.setPasswordHash(null);
            ApiException ex = assertThrows(ApiException.class,
                    () -> service.unlinkGoogle(7L, new UnlinkGoogleRequest("outra")));
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
            assertFalse(user.isDeleted());
            assertEquals("google-123", user.getGoogleSub());
            assertFalse(user.isGoogleLinkDisabled());
            assertEquals(0L, user.getTokenVersion());
        }
        verifyNoInteractions(payoutAccounts);
    }

    @Test
    void desvincularExigeCredencialENaoAceitaContaSemVinculo() {
        vincularGoogle();
        ApiException missing = assertThrows(ApiException.class,
                () -> service.unlinkGoogle(7L, new UnlinkGoogleRequest(" ")));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatus());
        user.setGoogleSub(null);
        ApiException unlinked = assertThrows(ApiException.class,
                () -> service.unlinkGoogle(7L, new UnlinkGoogleRequest("credencial")));
        assertEquals(HttpStatus.BAD_REQUEST, unlinked.getStatus());
        verifyNoInteractions(google, payoutAccounts);
    }

    @Test
    void naoDesvinculaContaSemSenhaQuandoOrganizadorTemCampeonatoAtivo() {
        contaSoComGoogle();
        vincularGoogle();
        when(championships.existsByOwnerIdAndStatusIn(eq(7L), any())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.unlinkGoogle(7L, new UnlinkGoogleRequest("credencial")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertFalse(user.isDeleted());
        assertEquals("google-123", user.getGoogleSub());
        assertFalse(user.isGoogleLinkDisabled());
        assertEquals(0L, user.getTokenVersion());
        verifyNoInteractions(payoutAccounts);
    }

    @Test
    void naoDesvinculaContaSemSenhaDeCapitaoEmEquipeConfirmadaDeEventoAtivo() {
        contaSoComGoogle();
        vincularGoogle();
        when(teams.existsByCaptainIdAndStatusAndCategory_Championship_StatusNotIn(anyLong(), eq(TeamStatus.CONFIRMED), any()))
                .thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.unlinkGoogle(7L, new UnlinkGoogleRequest("credencial")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertFalse(user.isDeleted());
        assertEquals("google-123", user.getGoogleSub());
        assertFalse(user.isGoogleLinkDisabled());
        verifyNoInteractions(payoutAccounts);
    }

    @Test
    void confirmacaoGoogleParaExcluirNaoIgnoraPoliticaDeEventoAtivo() {
        vincularGoogle();
        when(championships.existsByOwnerIdAndStatusIn(eq(7L), any())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.delete(7L, new DeleteAccountRequest(null, "credencial")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertFalse(user.isDeleted());
        verifyNoInteractions(payoutAccounts);
    }
}
