package br.com.wodz.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MercadoPagoSignatureVerifierTest {

    private final MercadoPagoSignatureVerifier verifier = new MercadoPagoSignatureVerifier("segredo-de-teste");

    @Test
    void montaManifestNoFormatoDoMercadoPago() {
        assertEquals("id:abc123;request-id:req-1;ts:1700000000;",
                MercadoPagoSignatureVerifier.manifest("ABC123", "req-1", "1700000000"));
        assertEquals("id:42;ts:1700000000;", MercadoPagoSignatureVerifier.manifest("42", null, "1700000000"));
    }

    @Test
    void aceitaAssinaturaValida() {
        String v1 = verifier.sign(MercadoPagoSignatureVerifier.manifest("123456", "req-9", "1700000000"));
        assertTrue(verifier.verify("ts=1700000000,v1=" + v1, "req-9", "123456"));
    }

    @Test
    void recusaAssinaturaAdulterada() {
        String v1 = verifier.sign(MercadoPagoSignatureVerifier.manifest("123456", "req-9", "1700000000"));
        assertFalse(verifier.verify("ts=1700000000,v1=" + v1, "req-9", "999999"));
        assertFalse(verifier.verify("ts=1700000001,v1=" + v1, "req-9", "123456"));
        assertFalse(verifier.verify(null, "req-9", "123456"));
    }

    @Test
    void semSegredoConfiguradoNaoBloqueia() {
        assertTrue(new MercadoPagoSignatureVerifier("").verify(null, null, "1"));
    }
}
