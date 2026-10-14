package br.com.wodz.common;

import java.util.Locale;

public final class ProfileValidation {
    private ProfileValidation() {}

    public static boolean validPhone(String value) {
        if (value == null || !value.matches("[+0-9\\s().-]+")) return false;
        int length = Codes.digits(value).length();
        return value.length() <= 20 && length >= 10 && length <= 15;
    }

    public static String document(String value) {
        return value == null ? null : value.replaceAll("[. /\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static boolean validCpf(String value) {
        String d = document(value);
        if (d == null || !d.matches("[0-9]{11}") || d.matches("([0-9])\\1{10}")) return false;
        for (int length = 9; length <= 10; length++) {
            int sum = 0;
            for (int i = 0; i < length; i++) sum += (d.charAt(i) - '0') * (length + 1 - i);
            int digit = (sum * 10) % 11;
            if ((digit == 10 ? 0 : digit) != d.charAt(length) - '0') return false;
        }
        return true;
    }

    /** Também aceita o CNPJ alfanumérico, com os mesmos pesos e valores ASCII - 48. */
    public static boolean validCnpj(String value) {
        String d = document(value);
        if (d == null || !d.matches("[A-Z0-9]{12}[0-9]{2}") || d.matches("([0-9])\\1{13}")) return false;
        for (int length = 12; length <= 13; length++) {
            int sum = 0;
            for (int i = 0; i < length; i++) sum += (d.charAt(i) - '0') * (2 + (length - 1 - i) % 8);
            int remainder = sum % 11;
            if ((remainder < 2 ? 0 : 11 - remainder) != d.charAt(length) - '0') return false;
        }
        return true;
    }
}
