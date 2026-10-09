package br.com.wodz.common;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;

public final class Codes {

    private static final SecureRandom RANDOM = new SecureRandom();
    // sem 0/O e 1/I para facilitar a leitura do código no celular
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private Codes() {
    }

    /** Código da inscrição; compõe o link público de pagamento. */
    public static String registrationCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    public static String slugify(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalized.isBlank()) {
            return "campeonato";
        }
        return normalized.length() > 140 ? normalized.substring(0, 140).replaceAll("-+$", "") : normalized;
    }

    public static String digits(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Nomes próprios e descrições: mantém acentos, números e quebras de linha. */
    public static String uppercase(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
