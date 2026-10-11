package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Role;
import br.com.wodz.dto.AuthDtos.AuthResponse;
import br.com.wodz.dto.AuthDtos.GoogleLoginRequest;
import br.com.wodz.dto.AuthDtos.GoogleLoginResult;
import br.com.wodz.dto.AuthDtos.LoginRequest;
import br.com.wodz.dto.AuthDtos.RegisterRequest;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.service.GoogleTokenVerifier.GoogleProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceGoogleTest {

    private static final GoogleProfile ANA = new GoogleProfile("google-123", "ana@gmail.com", "Ana Lima");

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final TokenService tokens = mock(TokenService.class);
    private final GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
    private final AuthService service = new AuthService(users, encoder, tokens, google);
    private final AuthResponse issued = new AuthResponse("jwt", Instant.now(), null);

    @BeforeEach
    void setUp() {
        when(google.verify("credencial")).thenReturn(ANA);
        when(users.findByGoogleSub(any())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(users.lockActiveByEmail(any())).thenReturn(Optional.empty());
        when(tokens.issue(any())).thenReturn(issued);
    }

    @Test
    void emailGoogleDeTerceiroNaoVinculaCadastroPorSenhaSemConfirmacao() {
        AppUser existing = new AppUser();
        existing.setId(5L);
        existing.setEmail("ana@outro.test");
        existing.setPasswordHash(encoder.encode("senha-atual"));
        when(google.verify("third-party")).thenReturn(new GoogleProfile("google-3", "ana@outro.test", "Ana"));
        when(users.lockActiveByEmail("ana@outro.test")).thenReturn(Optional.of(existing));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.google(new GoogleLoginRequest("third-party", null)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertNull(existing.getGoogleSub());
        verify(tokens, never()).issue(any());
    }

    @Test
    void dominioWorkspaceAtestadoPeloGooglePodeVincularCadastro() {
        AppUser existing = new AppUser();
        existing.setId(5L);
        existing.setEmail("ana@workspace.test");
        when(google.verify("workspace")).thenReturn(new GoogleProfile("google-w", "ana@workspace.test", "Ana", "workspace.test"));
        when(users.lockActiveByEmail("ana@workspace.test")).thenReturn(Optional.of(existing));

        GoogleLoginResult result = service.google(new GoogleLoginRequest("workspace", null));

        assertSame(issued, result.auth());
        assertEquals("google-w", existing.getGoogleSub());
    }

    @Test
    void primeiroAcessoSemPerfilPedeEscolhaENaoCriaConta() {
        GoogleLoginResult result = service.google(new GoogleLoginRequest("credencial", null));

        assertNull(result.auth());
        assertEquals("ana@gmail.com", result.pending().email());
        assertEquals("ANA LIMA", result.pending().name());
        verify(users, never()).save(any());
    }

    @Test
    void primeiroAcessoComPerfilCriaContaSemSenha() {
        GoogleLoginResult result = service.google(new GoogleLoginRequest("credencial", Role.ORGANIZER));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertSame(issued, result.auth());
        assertNull(result.pending());
        assertEquals("ana@gmail.com", saved.getValue().getEmail());
        assertEquals("ANA LIMA", saved.getValue().getName());
        assertEquals("google-123", saved.getValue().getGoogleSub());
        assertEquals(Role.ORGANIZER, saved.getValue().getRole());
        assertFalse(saved.getValue().hasPassword());
    }

    @Test
    void cadastroNormalizaNomeComAcentosSemAlterarSenha() {
        String password = "sEnHa-CaseSensitive";
        service.register(new RegisterRequest("  João D'Ávila  ", "JOAO@wodz.dev", password, Role.ATHLETE, null));

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertEquals("JOÃO D'ÁVILA", saved.getValue().getName());
        assertEquals("joao@wodz.dev", saved.getValue().getEmail());
        assertTrue(encoder.matches(password, saved.getValue().getPasswordHash()));
        assertFalse(encoder.matches(password.toUpperCase(java.util.Locale.ROOT), saved.getValue().getPasswordHash()));
    }

    @Test
    void contaJaVinculadaEntraDireto() {
        AppUser linked = user("google-123", "outro-email@gmail.com");
        when(users.findByGoogleSub("google-123")).thenReturn(Optional.of(linked));

        GoogleLoginResult result = service.google(new GoogleLoginRequest("credencial", null));

        assertSame(issued, result.auth());
        verify(tokens).issue(linked);
    }

    @Test
    void mesmoEmailCadastradoComSenhaEhVinculadoAoGoogle() {
        AppUser existing = user(null, "ana@gmail.com");
        existing.setPasswordHash(encoder.encode("senha-123"));
        when(users.lockActiveByEmail("ana@gmail.com")).thenReturn(Optional.of(existing));

        GoogleLoginResult result = service.google(new GoogleLoginRequest("credencial", null));

        assertNotNull(result.auth());
        assertEquals("google-123", existing.getGoogleSub());
    }

    @Test
    void emailVinculadoAOutraContaGoogleEhRecusado() {
        AppUser existing = user("google-999", "ana@gmail.com");
        when(users.lockActiveByEmail("ana@gmail.com")).thenReturn(Optional.of(existing));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.google(new GoogleLoginRequest("credencial", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void contaDesvinculadaNaoEhVinculadaAutomaticamenteMesmoEmEmailGmail() {
        AppUser existing = user(null, "ana@gmail.com");
        existing.setGoogleLinkDisabled(true);
        existing.setPasswordHash(encoder.encode("senha-local"));
        when(users.findByEmailIgnoreCase("ana@gmail.com")).thenReturn(Optional.of(existing));
        when(users.lockActiveByEmail("ana@gmail.com")).thenReturn(Optional.of(existing));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.google(new GoogleLoginRequest("credencial", Role.ATHLETE)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertNull(existing.getGoogleSub());
        verify(tokens, never()).issue(any());
        verify(users, never()).save(any());
        assertSame(issued, service.login(new LoginRequest("ana@gmail.com", "senha-local")));
    }

    @Test
    void preferenciaDeDesvinculacaoEhVerificadaNaPrimeiraLeituraPorEmailSobLock() {
        AppUser current = user(null, "ana@gmail.com");
        current.setGoogleLinkDisabled(true);
        when(users.lockActiveByEmail("ana@gmail.com")).thenReturn(Optional.of(current));

        assertThrows(ApiException.class, () -> service.google(new GoogleLoginRequest("credencial", null)));

        assertNull(current.getGoogleSub());
        verify(users).lockActiveByEmail("ana@gmail.com");
        verify(users, never()).findByEmailIgnoreCase(any());
        verify(tokens, never()).issue(any());
    }

    @Test
    void loginPorSenhaNaoRevelaSeEmailPertenceAContaGoogle() {
        when(users.findByEmailIgnoreCase("ana@gmail.com")).thenReturn(Optional.of(user("google-123", "ana@gmail.com")));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("ana@gmail.com", "qualquer")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("E-mail ou senha incorretos", ex.getMessage());
    }

    private static AppUser user(String googleSub, String email) {
        AppUser u = new AppUser();
        u.setId(5L);
        u.setName("Ana Lima");
        u.setEmail(email);
        u.setRole(Role.ATHLETE);
        u.setGoogleSub(googleSub);
        return u;
    }
}
