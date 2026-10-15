package br.com.wodz.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fecha a janela e prepara a grade mesmo sem um navegador conectado. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.heats.lifecycle-enabled", matchIfMissing = true)
public class HeatLifecycleJob {
    private static final Logger log = LoggerFactory.getLogger(HeatLifecycleJob.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;

    public HeatLifecycleJob(JdbcTemplate jdbc, PlatformTransactionManager manager, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.events = events;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 15_000)
    public void prepareClosedRegistrations() {
        for (Long id : jdbc.queryForList("""
                SELECT id FROM championship WHERE status='PUBLISHED' AND heat_lanes IS NOT NULL
                AND (NOT registration_open OR registration_closes_at < now()
                    OR start_date <= (now() AT TIME ZONE 'America/Sao_Paulo')::date)
                """, Long.class)) {
            try {
                transaction.executeWithoutResult(ignored -> {
                    var c = jdbc.queryForMap("SELECT status,registration_open,registration_closes_at, "
                            + "start_date <= (now() AT TIME ZONE 'America/Sao_Paulo')::date AS started, "
                            + "registration_closes_at < now() AS expired FROM championship WHERE id=? FOR UPDATE", id);
                    if (!"PUBLISHED".equals(c.get("status"))) return;
                    boolean fallback = Boolean.TRUE.equals(c.get("started"));
                    boolean expired = Boolean.TRUE.equals(c.get("expired"));
                    if (Boolean.TRUE.equals(c.get("registration_open")) && !fallback && !expired) return;
                    jdbc.update("UPDATE championship SET registration_open=FALSE WHERE id=?", id);
                    boolean generated = Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT fn_prepare_championship_heats(?,?)", Boolean.class, id, fallback));
                    if (generated) events.publishEvent(new ChampionshipUpdatedEvent(id, ChampionshipUpdatedEvent.HEATS));
                });
            } catch (RuntimeException error) {
                log.warn("Geração automática do campeonato {} aguarda configuração válida de equipes e provas", id);
            }
        }
    }
}
