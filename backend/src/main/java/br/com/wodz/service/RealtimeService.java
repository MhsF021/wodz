package br.com.wodz.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atualização ao vivo via Server-Sent Events. O navegador recebe "heats" ou
 * "leaderboard" e recarrega os dados. Para várias instâncias da API, troque o
 * mapa em memória por Redis pub/sub ou LISTEN/NOTIFY do PostgreSQL.
 */
@Service
public class RealtimeService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long championshipId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> group = emitters.computeIfAbsent(championshipId, k -> ConcurrentHashMap.newKeySet());
        group.add(emitter);
        Runnable remove = () -> group.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("championshipId", championshipId)));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /** Só dispara depois do commit, para o navegador nunca ler dado ainda não gravado. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChampionshipUpdated(ChampionshipUpdatedEvent event) {
        Set<SseEmitter> group = emitters.get(event.championshipId());
        if (group == null || group.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of("topic", event.topic(), "at", Instant.now().toString());
        for (SseEmitter emitter : group) {
            try {
                emitter.send(SseEmitter.event().name(event.topic()).data(payload));
            } catch (IOException | IllegalStateException e) {
                group.remove(emitter);
            }
        }
        log.debug("Evento {} enviado para {} conexões do campeonato {}", event.topic(), group.size(),
                event.championshipId());
    }

    /** Mantém a conexão viva atrás de proxies que derrubam conexões ociosas. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emitters.values().forEach(group -> {
            for (SseEmitter emitter : group) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    group.remove(emitter);
                }
            }
        });
    }
}
