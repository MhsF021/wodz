package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.config.AppProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Valida o ID token que o botão "Entrar com Google" entrega ao navegador:
 * assinatura com as chaves públicas do Google, emissor, destinatário (o nosso
 * Client ID), validade e e-mail verificado. Não é um bean JwtDecoder para não
 * concorrer com o decoder dos tokens da própria API.
 */
@Component
public class GoogleTokenVerifier {

    static final String JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final JwtDecoder decoder;

    public GoogleTokenVerifier(AppProperties props) {
        String clientId = props.google() == null ? null : Codes.blankToNull(props.google().clientId());
        if (clientId == null) {
            this.decoder = null;
            return;
        }
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build();
        nimbus.setJwtValidator(validator(clientId));
        this.decoder = nimbus;
    }

    public boolean isEnabled() {
        return decoder != null;
    }

    /** 422 (e não 401) em token inválido: o front trata 401 como sessão expirada. */
    public GoogleProfile verify(String credential) {
        if (decoder == null) {
            throw ApiException.badRequest("O login com Google não está configurado");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(credential);
        } catch (JwtException e) {
            throw ApiException.unprocessable("Não foi possível confirmar o login com o Google. Tente de novo.");
        }
        return profile(jwt);
    }

    static OAuth2TokenValidator<Jwt> validator(String clientId) {
        OAuth2TokenValidator<Jwt> issuer = jwt -> ISSUERS.contains(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Emissor inválido", null));
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token de outro aplicativo", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), issuer, audience);
    }

    static GoogleProfile profile(Jwt jwt) {
        String email = Codes.blankToNull(jwt.getClaimAsString("email"));
        if (email == null || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw ApiException.unprocessable("O e-mail desta conta Google não está verificado");
        }
        email = email.toLowerCase(Locale.ROOT);
        String name = Codes.blankToNull(jwt.getClaimAsString("name"));
        if (name == null) {
            name = email.substring(0, email.indexOf('@'));
        }
        return new GoogleProfile(jwt.getSubject(), email, name.length() > 120 ? name.substring(0, 120) : name);
    }

    public record GoogleProfile(String sub, String email, String name) {
    }
}
