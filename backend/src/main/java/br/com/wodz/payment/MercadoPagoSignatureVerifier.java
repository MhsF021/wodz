package br.com.wodz.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Valida o header x-signature dos webhooks do Mercado Pago.
 * Manifest: "id:{data.id};request-id:{x-request-id};ts:{ts};" assinado com HMAC-SHA256.
 * Mesmo com assinatura válida, o status é sempre reconsultado na API do MP.
 */
public final class MercadoPagoSignatureVerifier {

    private final String secret;

    public MercadoPagoSignatureVerifier(String secret) {
        this.secret = secret;
    }

    public boolean isConfigured() {
        return secret != null && !secret.isBlank();
    }

    public boolean verify(String signatureHeader, String requestId, String dataId) {
        if (!isConfigured()) {
            return true;
        }
        if (signatureHeader == null || dataId == null) {
            return false;
        }
        String ts = null;
        String v1 = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("ts".equals(kv[0].trim())) {
                ts = kv[1].trim();
            } else if ("v1".equals(kv[0].trim())) {
                v1 = kv[1].trim();
            }
        }
        if (ts == null || v1 == null) {
            return false;
        }
        String expected = sign(manifest(dataId, requestId, ts));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                v1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    static String manifest(String dataId, String requestId, String ts) {
        StringBuilder sb = new StringBuilder("id:").append(dataId.toLowerCase(Locale.ROOT)).append(';');
        if (requestId != null && !requestId.isBlank()) {
            sb.append("request-id:").append(requestId).append(';');
        }
        return sb.append("ts:").append(ts).append(';').toString();
    }

    String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC indisponível", e);
        }
    }
}
