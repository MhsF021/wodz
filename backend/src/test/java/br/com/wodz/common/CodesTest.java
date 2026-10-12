package br.com.wodz.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodesTest {
    @Test void linkPublicoUsaCodigoLongoSemCaracteresAmbiguos() {
        String code = Codes.registrationCode();
        assertEquals(26, code.length());
        assertTrue(code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{26}"));
    }
}
