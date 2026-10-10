package br.com.wodz.common;

import java.nio.charset.StandardCharsets;

/** BCrypt limita a entrada a 72 bytes; contar caracteres permite truncamento/erros com Unicode. */
public final class PasswordPolicy {
    private PasswordPolicy() { }

    public static boolean fitsBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    public static void validateNewPassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8 || !fitsBcrypt(password)) {
            throw ApiException.badRequest("A senha precisa ter ao menos 8 caracteres e no máximo 72 bytes (UTF-8)");
        }
    }
}
