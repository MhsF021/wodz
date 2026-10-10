package br.com.wodz.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test
    void limitaBytesUTF8AntesDePassarAoBCrypt() {
        assertDoesNotThrow(() -> PasswordPolicy.validateNewPassword("a".repeat(72)));
        assertDoesNotThrow(() -> PasswordPolicy.validateNewPassword("á".repeat(36)));
        assertThrows(ApiException.class, () -> PasswordPolicy.validateNewPassword("á".repeat(37)));
        assertThrows(ApiException.class, () -> PasswordPolicy.validateNewPassword("a".repeat(73)));
        assertThrows(ApiException.class, () -> PasswordPolicy.validateNewPassword("curta"));
    }
}
