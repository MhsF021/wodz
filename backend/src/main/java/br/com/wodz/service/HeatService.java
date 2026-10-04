package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.Heat;
import br.com.wodz.domain.HeatStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.domain.Workout;
import br.com.wodz.dto.HeatDtos.GenerateAllHeatsRequest;
import br.com.wodz.dto.HeatDtos.GenerateHeatsRequest;
import br.com.wodz.dto.HeatDtos.HeatRequest;
import br.com.wodz.dto.HeatDtos.HeatView;
import br.com.wodz.repository.HeatRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.repository.WorkoutRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Baterias: geração automática, ajustes manuais e controle de status. */
@Service
public class HeatService {

    /** Limite de equipes por bateria (também garantido no banco: ck_heat_lane_max). */
    public static final int MAX_LANES = 8;

    private final HeatRepository heats;
    private final TeamRepository teams;
    private final WorkoutRepository workouts;
    private final OwnershipGuard guard;
    private final ScheduleQueryService scheduleQuery;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public HeatService(HeatRepository heats, TeamRepository teams, WorkoutRepository workouts, OwnershipGuard guard,
                       ScheduleQueryService scheduleQuery, JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.heats = heats;
        this.teams = teams;
        this.workouts = workouts;
        this.guard = guard;
        this.scheduleQuery = scheduleQuery;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<HeatView> list(Long championshipId, Long categoryId, Long userId) {
        guard.championship(championshipId, userId);
        return scheduleQuery.forChampionship(championshipId, categoryId);
    }

    /** Usa a procedure sp_generate_heats (substitui as baterias ainda não iniciadas). */
    @Transactional
    public List<HeatView> generate(Long workoutId, Long userId, GenerateHeatsRequest req) {
        Workout workout = guard.workout(workoutId, userId);
        Long championshipId = workout.getCategory().getChampionship().getId();
        jdbc.update("CALL sp_generate_heats(?, ?, ?, ?)",
                workoutId, req.lanes(), OffsetDateTime.ofInstant(req.firstStart(), ZoneOffset.UTC),
                req.intervalMinutes());
        publish(championshipId);
        return scheduleQuery.forChampionship(championshipId, workout.getCategory().getId()).stream()
                .filter(h -> h.workoutId().equals(workoutId))
                .toList();
    }

    /**
     * Gera as baterias de todas as provas do campeonato de uma vez, com até 8
     * equipes por bateria: Prova 1 de cada categoria, depois Prova 2, e assim
     * por diante, uma bateria a cada intervalMinutes a partir de firstStart.
     */
    @Transactional
    public List<HeatView> generateAll(Long championshipId, Long userId, GenerateAllHeatsRequest req) {
        guard.championship(championshipId, userId);
        Integer started = jdbc.queryForObject("""
                SELECT count(*) FROM heat h
                  JOIN workout w ON w.id = h.workout_id
                  JOIN category cat ON cat.id = w.category_id
                 WHERE cat.championship_id = ? AND h.status <> 'UPCOMING'""", Integer.class, championshipId);
        if (started != null && started > 0) {
            throw ApiException.unprocessable("Este campeonato já tem baterias iniciadas ou concluídas. "
                    + "Para refazer, gere as baterias de cada prova que ainda não começou.");
        }
        List<Workout> ordered = workouts.findByCategoryChampionshipIdOrderByCategoryDisplayOrderAscNumberAsc(championshipId)
                .stream()
                .sorted(Comparator.comparingInt(Workout::getNumber)
                        .thenComparingInt(w -> w.getCategory().getDisplayOrder()))
                .toList();
        if (ordered.isEmpty()) {
            throw ApiException.unprocessable("Cadastre as provas antes de gerar as baterias");
        }

        Instant start = req.firstStart();
        long created = 0;
        for (Workout workout : ordered) {
            if (teams.countByCategoryIdAndStatus(workout.getCategory().getId(), TeamStatus.CONFIRMED) == 0) {
                continue;
            }
            jdbc.update("CALL sp_generate_heats(?, ?, ?, ?)", workout.getId(), MAX_LANES,
                    OffsetDateTime.ofInstant(start, ZoneOffset.UTC), req.intervalMinutes());
            long count = heats.countByWorkoutId(workout.getId());
            start = start.plus(Duration.ofMinutes(count * req.intervalMinutes()));
            created += count;
        }
        if (created == 0) {
            throw ApiException.unprocessable("Não há equipes confirmadas para montar baterias");
        }
        publish(championshipId);
        return scheduleQuery.forChampionship(championshipId, null);
    }

    @Transactional
    public HeatView create(Long workoutId, Long userId, HeatRequest req) {
        Workout workout = guard.workout(workoutId, userId);
        Heat heat = new Heat();
        heat.setWorkout(workout);
        heat.setNumber(heats.maxNumber(workoutId) + 1);
        heat.setScheduledAt(req.scheduledAt());
        assignLanes(heat, req.teamIds());
        heats.saveAndFlush(heat);
        publish(workout.getCategory().getChampionship().getId());
        return scheduleQuery.byId(heat.getId());
    }

    @Transactional
    public HeatView update(Long heatId, Long userId, HeatRequest req) {
        Heat heat = guard.heat(heatId, userId);
        heat.setScheduledAt(req.scheduledAt());
        if (req.teamIds() != null) {
            if (heat.getStatus() != HeatStatus.UPCOMING) {
                throw ApiException.unprocessable("Só é possível trocar as equipes de baterias que ainda não começaram");
            }
            // remove as raias antes de inserir as novas (o Hibernate faria INSERT antes de DELETE)
            heat.getLanes().clear();
            heats.flush();
            assignLanes(heat, req.teamIds());
        }
        heats.saveAndFlush(heat);
        publish(heat.getWorkout().getCategory().getChampionship().getId());
        return scheduleQuery.byId(heatId);
    }

    @Transactional
    public void delete(Long heatId, Long userId) {
        Heat heat = guard.heat(heatId, userId);
        if (heat.getStatus() != HeatStatus.UPCOMING) {
            throw ApiException.unprocessable("Só é possível excluir baterias que ainda não começaram");
        }
        Long championshipId = heat.getWorkout().getCategory().getChampionship().getId();
        heats.delete(heat);
        publish(championshipId);
    }

    /** Procedure sp_start_heat: marca "em andamento" e conclui a que estava rodando. */
    @Transactional
    public HeatView start(Long heatId, Long userId, boolean finishRunning) {
        Heat heat = guard.heat(heatId, userId);
        jdbc.update("CALL sp_start_heat(?, ?)", heatId, finishRunning);
        publish(heat.getWorkout().getCategory().getChampionship().getId());
        return scheduleQuery.byId(heatId);
    }

    /** Ajuste manual: Próxima / Em andamento / Concluída. */
    @Transactional
    public HeatView changeStatus(Long heatId, Long userId, HeatStatus status) {
        Heat heat = guard.heat(heatId, userId);
        heat.setStatus(status);
        heats.saveAndFlush(heat);
        publish(heat.getWorkout().getCategory().getChampionship().getId());
        return scheduleQuery.byId(heatId);
    }

    private void assignLanes(Heat heat, List<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return;
        }
        if (teamIds.size() > MAX_LANES) {
            throw ApiException.badRequest("Cada bateria tem no máximo " + MAX_LANES + " equipes");
        }
        Map<Long, Team> byId = teams.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        int lane = 1;
        for (Long teamId : teamIds) {
            Team team = byId.get(teamId);
            if (team == null) {
                throw ApiException.badRequest("Equipe " + teamId + " não encontrada");
            }
            heat.addLane(team, lane++);
        }
    }

    private void publish(Long championshipId) {
        events.publishEvent(new ChampionshipUpdatedEvent(championshipId, ChampionshipUpdatedEvent.HEATS));
    }
}
