package br.com.wodz.common;

import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUser {

    private CurrentUser() {
    }

    /** O "sub" do JWT é o id do usuário. */
    public static Long id(Jwt jwt) {
        if (jwt == null) {
            throw ApiException.unauthorized("Entre na sua conta para continuar");
        }
        return Long.valueOf(jwt.getSubject());
    }
}
