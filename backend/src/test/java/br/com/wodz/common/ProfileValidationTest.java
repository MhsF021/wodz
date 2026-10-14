package br.com.wodz.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileValidationTest {
    @Test void documentosValidamDigitosEAceitamCnpjAlfanumerico() {
        assertTrue(ProfileValidation.validCpf("529.982.247-25"));
        assertFalse(ProfileValidation.validCpf("52998224724"));
        assertFalse(ProfileValidation.validCpf("11111111111"));
        assertFalse(ProfileValidation.validCpf("529a98224725"));
        assertTrue(ProfileValidation.validCnpj("11.222.333/0001-81"));
        assertTrue(ProfileValidation.validCnpj("00.000.000/E08G-12"));
        assertFalse(ProfileValidation.validCnpj("11.222.333/0001-82"));
        assertFalse(ProfileValidation.validCnpj("00000000000000"));
    }

    @Test void telefoneExigeNumeroEDdd() {
        for (String phone : new String[] {"(11) 99999-0000", "+55 (11) 99999-0000", "1133334444"})
            assertTrue(ProfileValidation.validPhone(phone));
        for (String phone : new String[] {"", "   ", "99999-0000", "telefone", "1111111111111111"})
            assertFalse(ProfileValidation.validPhone(phone));
        assertFalse(ProfileValidation.validPhone(null));
    }
}
