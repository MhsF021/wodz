package br.com.wodz.service;

import br.com.wodz.repository.CheckoutSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.Duration;

/** Recupera confirmações mesmo se o comprador fechar a aba e o webhook falhar. */
@Component
public class CheckoutReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(CheckoutReconciliationJob.class);
    private final CheckoutSessionRepository sessions;
    private final PaymentService payments;
    public CheckoutReconciliationJob(CheckoutSessionRepository sessions, PaymentService payments) {
        this.sessions = sessions; this.payments = payments;
    }
    @Scheduled(initialDelay = 60_000, fixedDelay = 120_000)
    public void reconcile() {
        for (Long id : sessions.recentIds(Instant.now().minus(Duration.ofDays(3)))) {
            try { payments.reconcileCheckout(id); }
            catch (RuntimeException e) { log.warn("Conciliação do checkout {} adiada", id); }
        }
    }
}
