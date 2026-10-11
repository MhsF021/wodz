package br.com.wodz.config;

import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Role;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.repository.ChampionshipRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.dto.AuthDtos.UnlinkGoogleRequest;
import br.com.wodz.service.AccountService;
import br.com.wodz.service.GoogleTokenVerifier;
import br.com.wodz.service.PayoutAccountService;
import br.com.wodz.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtSecurityTest {
    private final SecurityConfig security = new SecurityConfig();
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AppProperties props = new AppProperties("https://wodz.test", List.of("https://wodz.test"),
            new AppProperties.JwtProps("testing-secret-with-at-least-thirty-two-bytes", Duration.ofHours(12), "wodz"),
            null, null, null, "prod", null);
    private final JwtEncoder encoder = security.jwtEncoder(security.jwtSecretKey(props));
    private final JwtDecoder decoder = security.jwtDecoder(security.jwtSecretKey(props), users, props);
    private AppUser user;

    @BeforeEach
    void setup() {
        user = new AppUser();
        user.setId(7L);
        user.setRole(Role.ATHLETE);
        user.setName("Dado pessoal");
        user.setEmail("private@test.invalid");
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    private String signed(String issuer, String subject, String role, Object version, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(issuer).subject(subject)
                .issuedAt(expiresAt != null && expiresAt.isBefore(Instant.now())
                        ? expiresAt.minusSeconds(60) : Instant.now()).claim("role", role);
        if (expiresAt != null) claims.expiresAt(expiresAt);
        if (version != null) claims.claim("token_version", version);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    @Test
    void senhaAlteradaRevogaTokenAnteriorESomenteVersaoNovaAutentica() {
        String previous = new TokenService(encoder, props).issue(user).token();
        assertEquals("7", decoder.decode(previous).getSubject());
        user.setTokenVersion(1);
        assertThrows(JwtException.class, () -> decoder.decode(previous));
        Jwt current = decoder.decode(new TokenService(encoder, props).issue(user).token());
        assertEquals(1L, ((Number) current.getClaim("token_version")).longValue());
        assertFalse(current.hasClaim("name"));
        assertFalse(current.hasClaim("email"));
    }

    @Test
    void legadoSoAutenticaAntesDaPrimeiraRevogacao() {
        String legacy = signed("wodz", "7", "ATHLETE", null, Instant.now().plusSeconds(60));
        assertDoesNotThrow(() -> decoder.decode(legacy));
        user.setTokenVersion(1);
        assertThrows(JwtException.class, () -> decoder.decode(legacy));
    }

    @Test
    void rejeitaContaExcluidaPapelisDivergentesEEmissorIncorreto() {
        Instant valid = Instant.now().plusSeconds(60);
        assertThrows(JwtException.class, () -> decoder.decode(signed("outro", "7", "ATHLETE", 0, valid)));
        assertThrows(JwtException.class, () -> decoder.decode(signed("wodz", "7", "ORGANIZER", 0, valid)));
        user.setDeletedAt(Instant.now());
        assertThrows(JwtException.class, () -> decoder.decode(signed("wodz", "7", "ATHLETE", 0, valid)));
    }

    @Test
    void rejeitaSubMalformadoVersaoMalformadaEValidadeAusente() {
        Instant valid = Instant.now().plusSeconds(60);
        assertThrows(JwtException.class, () -> decoder.decode(signed("wodz", "not-a-number", "ATHLETE", 0, valid)));
        assertThrows(JwtException.class, () -> decoder.decode(signed("wodz", "9999999999999999999", "ATHLETE", 0, valid)));
        assertThrows(JwtException.class, () -> decoder.decode(signed("wodz", "7", "ATHLETE", "invalid", valid)));
        assertThrows(JwtException.class, () -> decoder.decode(signed("wodz", "7", "ATHLETE", 0, null)));
    }

    @Test
    void assinaturaAdulteradaEExpiracaoSaoRecusadas() {
        assertThrows(JwtException.class, () -> decoder.decode(signed("wodz", "7", "ATHLETE", 0, Instant.now().minusSeconds(120))));
        String valid = new TokenService(encoder, props).issue(user).token();
        String altered = valid.substring(0, valid.lastIndexOf('.') + 1) + "x".repeat(43);
        assertThrows(JwtException.class, () -> decoder.decode(altered));
    }

    @Test
    void desvinculacaoGoogleRevogaJwtMesmoQuandoContaLocalPermanece() {
        user.setGoogleSub("google-123");
        user.setPasswordHash("local-password-hash");
        when(users.lockActiveById(7L)).thenReturn(Optional.of(user));
        GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
        when(google.verify("credential")).thenReturn(new GoogleTokenVerifier.GoogleProfile("google-123", "private@test.invalid", "Ana"));
        AccountService account = new AccountService(users, mock(ChampionshipRepository.class), mock(TeamRepository.class),
                security.passwordEncoder(), google, mock(PayoutAccountService.class));
        String before = new TokenService(encoder, props).issue(user).token();
        assertDoesNotThrow(() -> decoder.decode(before));

        account.unlinkGoogle(7L, new UnlinkGoogleRequest("credential"));

        assertFalse(user.isDeleted());
        assertNull(user.getGoogleSub());
        assertThrows(JwtException.class, () -> decoder.decode(before));
        assertDoesNotThrow(() -> decoder.decode(new TokenService(encoder, props).issue(user).token()));
    }
}
