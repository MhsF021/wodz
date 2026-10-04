package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.service.GoogleTokenVerifier.GoogleProfile;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleTokenVerifierTest {

    private static final String CLIENT_ID = "123-abc.apps.googleusercontent.com";
    private final OAuth2TokenValidator<Jwt> validator = GoogleTokenVerifier.validator(CLIENT_ID);

    private static Jwt.Builder token() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("google-123")
                .issuer("https://accounts.google.com")
                .audience(List.of(CLIENT_ID))
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(3600))
                .claim("email", "Ana@Gmail.com")
                .claim("email_verified", true)
                .claim("name", "Ana Lima");
    }

    @Test
    void aceitaTokenDoGoogleParaEsteClientId() {
        assertFalse(validator.validate(token().build()).hasErrors());
        assertFalse(validator.validate(token().issuer("accounts.google.com").build()).hasErrors());
    }

    @Test
    void recusaTokenDeOutroAplicativoOuEmissor() {
        assertTrue(validator.validate(token().audience(List.of("outro.apps.googleusercontent.com")).build()).hasErrors());
        assertTrue(validator.validate(token().issuer("https://evil.example.com").build()).hasErrors());
    }

    @Test
    void recusaTokenExpirado() {
        Instant past = Instant.now().minusSeconds(7200);
        assertTrue(validator.validate(token().issuedAt(past).expiresAt(past.plusSeconds(3600)).build()).hasErrors());
    }

    @Test
    void extraiPerfilComEmailEmMinusculas() {
        GoogleProfile profile = GoogleTokenVerifier.profile(token().build());

        assertEquals("google-123", profile.sub());
        assertEquals("ana@gmail.com", profile.email());
        assertEquals("Ana Lima", profile.name());
    }

    @Test
    void exigeEmailVerificado() {
        ApiException ex = assertThrows(ApiException.class,
                () -> GoogleTokenVerifier.profile(token().claim("email_verified", false).build()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }
}
