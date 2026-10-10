package br.com.wodz.web;

import br.com.wodz.dto.ChampionshipDtos.ChampionshipDetail;
import br.com.wodz.domain.ChampionshipFilter;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipSummary;
import br.com.wodz.dto.HeatDtos.HeatView;
import br.com.wodz.dto.LeaderboardDtos.LeaderboardDto;
import br.com.wodz.dto.PaymentDtos.PublicConfig;
import br.com.wodz.service.ChampionshipService;
import br.com.wodz.service.LeaderboardService;
import br.com.wodz.service.PaymentService;
import br.com.wodz.service.RealtimeService;
import br.com.wodz.service.ScheduleQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** Endpoints abertos: busca, detalhes, baterias, leaderboard e stream ao vivo. */
@RestController
@RequestMapping("/api/public")
@Tag(name = "Público")
public class PublicController {

    private final ChampionshipService championships;
    private final ScheduleQueryService scheduleQuery;
    private final LeaderboardService leaderboards;
    private final RealtimeService realtime;
    private final PaymentService payments;

    public PublicController(ChampionshipService championships, ScheduleQueryService scheduleQuery,
                            LeaderboardService leaderboards, RealtimeService realtime, PaymentService payments) {
        this.championships = championships;
        this.scheduleQuery = scheduleQuery;
        this.leaderboards = leaderboards;
        this.realtime = realtime;
        this.payments = payments;
    }

    @GetMapping("/config")
    @Operation(summary = "Configuração pública do frontend")
    public PublicConfig config() {
        return payments.publicConfig();
    }

    @GetMapping("/championships")
    @Operation(summary = "Busca campeonatos por nome, cidade ou local; filtros por UF e situação")
    public List<ChampionshipSummary> search(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String state,
                                            @RequestParam(defaultValue = "ALL") ChampionshipFilter status) {
        return championships.search(q, state, status);
    }

    @GetMapping("/championships/{slug}")
    public ChampionshipDetail detail(@PathVariable String slug) {
        return championships.publicDetail(slug);
    }

    @GetMapping("/championships/{slug}/schedule")
    @Operation(summary = "Horário e status de todas as baterias, por prova")
    public List<HeatView> schedule(@PathVariable String slug, @RequestParam(required = false) Long categoryId) {
        Long championshipId = championships.findPublic(slug).getId();
        return scheduleQuery.forChampionship(championshipId, categoryId);
    }

    @GetMapping("/championships/{slug}/leaderboard")
    public LeaderboardDto leaderboard(@PathVariable String slug, @RequestParam(required = false) Long categoryId) {
        return leaderboards.forChampionship(slug, categoryId);
    }

    @GetMapping(path = "/championships/{slug}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-Sent Events: eventos 'heats' e 'leaderboard'")
    public SseEmitter stream(@PathVariable String slug) {
        return realtime.subscribe(championships.findPublic(slug).getId());
    }
}
