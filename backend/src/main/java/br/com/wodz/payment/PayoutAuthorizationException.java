package br.com.wodz.payment;

import br.com.wodz.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

/** Motivos públicos fixos; nunca leva mensagens, códigos ou tokens do provedor à URL. */
public final class PayoutAuthorizationException extends ApiException {
    public enum Reason {
        INVALID_STATE, MISSING_CODE, CANCELLED, CONFIGURATION, TOKEN_REJECTED,
        AUTHORIZATION_REJECTED, MODE_MISMATCH, ACCOUNT_UNAVAILABLE, ACCOUNT_CHANGE,
        PROVIDER_UNAVAILABLE, INVALID_RESPONSE, UNEXPECTED;

        public String code() { return name().toLowerCase(Locale.ROOT); }
    }

    private final Reason reason;

    public PayoutAuthorizationException(Reason reason, String message) {
        this(reason, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    public PayoutAuthorizationException(Reason reason, HttpStatus status, String message) {
        super(status, message);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
