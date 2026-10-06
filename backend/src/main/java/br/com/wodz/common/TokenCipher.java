package br.com.wodz.common;

import br.com.wodz.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra segredos guardados no banco (tokens OAuth das contas de recebimento) com
 * AES-256-GCM: IV aleatório por valor e autenticação, então um valor adulterado
 * não decifra. Formato: "v1:" + Base64(IV || texto cifrado + tag).
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public TokenCipher(AppProperties props) {
        String configured = props.security() == null ? null : props.security().encryptionKey();
        this.key = new SecretKeySpec(keyBytes(configured, props.isDev()), "AES");
    }

    static byte[] keyBytes(String configured, boolean dev) {
        if (configured == null || configured.isBlank()) {
            if (!dev) {
                throw new IllegalStateException("Defina APP_ENCRYPTION_KEY (32 bytes em Base64)");
            }
            log.warn("APP_ENCRYPTION_KEY vazio: usando chave fixa de desenvolvimento. Nunca use assim fora de dev.");
            return sha256("wodz-dev-only-encryption-key");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY precisa estar em Base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY precisa ter 32 bytes (openssl rand -base64 32)");
        }
        return bytes;
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar segredo", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Segredo em formato desconhecido");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // chave trocada ou valor adulterado
            throw new IllegalStateException("Não foi possível decifrar o segredo", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
