package br.com.wodz.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expira cobranças pendentes vencidas chamando a procedure sp_expire_pending_payments. */
@Component
public class PaymentExpirationJob {

    private final JdbcTemplate jdbc;

    public PaymentExpirationJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void expirePendingPayments() {
        jdbc.update("CALL sp_expire_pending_payments()");
    }
}
