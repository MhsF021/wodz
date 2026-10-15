package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.ChampionshipStatus;
import br.com.wodz.domain.Heat;
import br.com.wodz.dto.HeatDtos.HeatPlanEntry;
import br.com.wodz.dto.HeatDtos.HeatPlanRequest;
import br.com.wodz.dto.HeatDtos.LaneView;
import br.com.wodz.domain.HeatStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.domain.Workout;
import br.com.wodz.dto.HeatDtos.GenerateAllHeatsRequest;
import br.com.wodz.dto.HeatDtos.HeatRequest;
import br.com.wodz.dto.HeatDtos.HeatView;
import br.com.wodz.repository.HeatRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.repository.WorkoutRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Transactional
    public void settings(Long id, Long userId, GenerateAllHeatsRequest req) {
        Championship c = guard.championship(id, userId);
        guard.requireEditable(c);
        boolean recovery = c.getHeatLanes() == null && scheduleQuery.forChampionship(id, null).isEmpty();
        if (!recovery) requireNotStarted(c);
        if (c.getStatus() == ChampionshipStatus.CANCELLED) throw ApiException.unprocessable("O campeonato está cancelado");
        storeSettings(c, req);
        if (!c.isRegistrationOpen() || c.hasStarted(Instant.now())) {
            if (c.hasStarted(Instant.now())) {
                c.setRegistrationOpen(false);
                jdbc.update("UPDATE championship SET registration_open=FALSE WHERE id=?", id);
            } else if (!scheduleQuery.forChampionship(id, null).isEmpty()) {
                jdbc.update("CALL sp_generate_championship_heats(?, ?, ?, ?)", id, req.lanes(),
                        OffsetDateTime.ofInstant(req.firstStart(), ZoneOffset.UTC), req.intervalMinutes());
            }
            jdbc.queryForObject("SELECT fn_prepare_championship_heats(?, ?)", Boolean.class, id,
                    c.hasStarted(Instant.now()) || c.getStatus() == ChampionshipStatus.IN_PROGRESS);
        }
        publish(id);
    }

    @Transactional
    public void acknowledge(Long id, Long userId) {
        Championship c = guard.championship(id, userId);
        guard.requireEditable(c);
        c.setHeatsRequireReview(false);
        jdbc.update("UPDATE championship SET heats_require_review=FALSE WHERE id=?", id);
    }

    @Transactional
    public List<HeatView> generateAll(Long id, Long userId, GenerateAllHeatsRequest req) {
        Championship c = guard.championship(id, userId);
        requirePlanEditable(c);
        storeSettings(c, req);
        jdbc.update("CALL sp_generate_championship_heats(?, ?, ?, ?)", id, req.lanes(),
                OffsetDateTime.ofInstant(req.firstStart(), ZoneOffset.UTC), req.intervalMinutes());
        publish(id);
        return scheduleQuery.forChampionship(id, null);
    }

    /** Valida a grade inteira antes de gravar; mover equipes é uma operação atômica. */
    @Transactional
    public List<HeatView> savePlan(Long id, Long userId, HeatPlanRequest req) {
        Championship c = guard.championship(id, userId);
        requirePlanEditable(c);
        ChampionshipService.requireHeatSettings(c);
        List<Workout> allWorkouts = workouts.findByCategoryChampionshipIdOrderByCategoryDisplayOrderAscNumberAsc(id);
        Map<Long, Workout> byWorkout = allWorkouts.stream().collect(Collectors.toMap(Workout::getId, w -> w));
        Map<Long, HeatView> current = scheduleQuery.forChampionship(id, null).stream()
                .collect(Collectors.toMap(HeatView::heatId, h -> h));
        Map<Long, Map<Long, String>> assignments = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (HeatPlanEntry entry : req.heats()) {
            Workout w = byWorkout.get(entry.workoutId());
            if (w == null) throw ApiException.badRequest("Prova não pertence a este campeonato");
            if (!numbers.add(w.getId() + ":" + entry.heatNumber())) throw ApiException.badRequest("Número de bateria duplicado");
            if (entry.heatId() != null && (!ids.add(entry.heatId()) || !current.containsKey(entry.heatId())
                    || !current.get(entry.heatId()).workoutId().equals(w.getId())
                    || current.get(entry.heatId()).heatNumber() != entry.heatNumber())) {
                throw ApiException.badRequest("Bateria não pertence a esta prova ou está duplicada");
            }
            if (entry.teamIds().size() > c.getHeatLanes()) throw ApiException.unprocessable(
                    "Cada bateria pode ter até " + c.getHeatLanes() + " equipes, conforme a configuração");
            Set<Long> allowed = teams.findByCategoryIdAndStatusOrderByNameAsc(w.getCategory().getId(), TeamStatus.CONFIRMED)
                    .stream().map(Team::getId).collect(Collectors.toSet());
            Map<Long, String> assigned = assignments.computeIfAbsent(w.getId(), key -> new HashMap<>());
            for (int lane = 0; lane < entry.teamIds().size(); lane++) {
                Long team = entry.teamIds().get(lane);
                if (!allowed.contains(team)) throw ApiException.badRequest("Equipe não confirmada ou de outra categoria");
                if (assigned.put(team, entry.heatNumber() + ":" + (lane + 1)) != null)
                    throw ApiException.unprocessable("Uma equipe não pode estar em duas baterias da mesma prova");
            }
            durationSeconds(w);
        }
        List<String> missing = new ArrayList<>();
        for (Workout w : allWorkouts) {
            Map<Long, String> assigned = assignments.getOrDefault(w.getId(), Map.of());
            for (Team t : teams.findByCategoryIdAndStatusOrderByNameAsc(w.getCategory().getId(), TeamStatus.CONFIRMED))
                if (!assigned.containsKey(t.getId())) missing.add(t.getName() + " (" + w.getCategory().getName() + ", prova " + w.getNumber() + ")");
        }
        if (!missing.isEmpty()) throw ApiException.unprocessable("Equipes sem bateria: " + String.join(", ", missing)
                + ". Corrija a distribuição antes de salvar.");
        if (req.heats().stream().anyMatch(e -> e.teamIds().isEmpty())) {
            throw ApiException.unprocessable("Remova as baterias vazias antes de salvar");
        }
        Map<Long, Map<Long, String>> canonical = new HashMap<>();
        for (Workout w : allWorkouts) {
            Map<Long, String> assigned = assignments.getOrDefault(w.getId(), Map.of());
            Map<Long, String> first = canonical.putIfAbsent(w.getCategory().getId(), assigned);
            if (first != null && !first.equals(assigned)) throw ApiException.unprocessable(
                    "As equipes, baterias e raias devem ser iguais em todas as provas da categoria");
        }
        jdbc.update("DELETE FROM heat_lane WHERE heat_id IN (SELECT h.id FROM heat h JOIN workout w ON w.id=h.workout_id JOIN category c ON c.id=w.category_id WHERE c.championship_id=?)", id);
        for (Long oldId : current.keySet()) if (!ids.contains(oldId)) jdbc.update("DELETE FROM heat WHERE id=?", oldId);
        for (HeatPlanEntry entry : req.heats()) {
            Workout w = byWorkout.get(entry.workoutId());
            Long heatId = entry.heatId();
            if (heatId == null) heatId = jdbc.queryForObject(
                    "INSERT INTO heat(workout_id,number,scheduled_at,duration_seconds,interval_minutes) VALUES (?,?,?,?,?) RETURNING id",
                    Long.class, w.getId(), entry.heatNumber(), OffsetDateTime.ofInstant(entry.scheduledAt(), ZoneOffset.UTC), durationSeconds(w), c.getHeatIntervalMinutes());
            else jdbc.update("UPDATE heat SET scheduled_at=?,duration_seconds=?,interval_minutes=? WHERE id=?",
                    OffsetDateTime.ofInstant(entry.scheduledAt(), ZoneOffset.UTC), durationSeconds(w), c.getHeatIntervalMinutes(), heatId);
            for (int lane = 0; lane < entry.teamIds().size(); lane++) jdbc.update(
                    "INSERT INTO heat_lane(heat_id,team_id,lane) VALUES (?,?,?)", heatId, entry.teamIds().get(lane), lane + 1);
        }
        publish(id);
        return scheduleQuery.forChampionship(id, null);
    }

    @Transactional
    public HeatView create(Long workoutId, Long userId, HeatRequest req) {
        requirePlanEditable(guard.workout(workoutId, userId).getCategory().getChampionship());
        throw ApiException.unprocessable("Crie a bateria no editor da grade e salve todas as provas de uma vez");
    }

    @Transactional
    public HeatView update(Long heatId, Long userId, HeatRequest req) {
        Heat heat = guard.heat(heatId, userId);
        Long id = heat.getWorkout().getCategory().getChampionship().getId();
        List<HeatPlanEntry> plan = scheduleQuery.forChampionship(id, null).stream().map(h -> new HeatPlanEntry(
                h.heatId(), h.workoutId(), h.heatNumber(), h.heatId().equals(heatId) ? req.scheduledAt() : h.scheduledAt(),
                req.teamIds() != null && h.categoryId().equals(heat.getWorkout().getCategory().getId()) && h.heatNumber() == heat.getNumber()
                        ? req.teamIds() : h.lanes().stream().map(LaneView::teamId).toList())).toList();
        return savePlan(id, userId, new HeatPlanRequest(plan)).stream().filter(h -> h.heatId().equals(heatId)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(Long heatId, Long userId) {
        Heat heat = guard.heat(heatId, userId);
        Long id = heat.getWorkout().getCategory().getChampionship().getId();
        List<HeatPlanEntry> plan = scheduleQuery.forChampionship(id, null).stream()
                .filter(h -> !(h.categoryId().equals(heat.getWorkout().getCategory().getId()) && h.heatNumber() == heat.getNumber()))
                .map(h -> new HeatPlanEntry(h.heatId(), h.workoutId(), h.heatNumber(), h.scheduledAt(), h.lanes().stream().map(LaneView::teamId).toList())).toList();
        savePlan(id, userId, new HeatPlanRequest(plan));
    }

    /** Procedure sp_start_heat: marca "em andamento" e conclui a que estava rodando. */
    @Transactional
    public HeatView start(Long heatId, Long userId, boolean finishRunning) {
        Heat heat = guard.heat(heatId, userId);
        Championship c = heat.getWorkout().getCategory().getChampionship();
        guard.requireEditable(c);
        if (c.isRegistrationOpen() && !c.hasStarted(Instant.now())) throw ApiException.unprocessable("Feche as inscrições antes de iniciar o campeonato");
        if (c.getStatus() == ChampionshipStatus.CANCELLED) throw ApiException.unprocessable("O campeonato está cancelado");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT fn_championship_heats_complete(?)", Boolean.class, c.getId())))
            throw ApiException.unprocessable("A grade está incompleta. Verifique a geração antes de iniciar a bateria");
        if (heat.getLanes().isEmpty()) throw ApiException.unprocessable("Esta bateria está sem equipes");
        c.setStatus(ChampionshipStatus.IN_PROGRESS);
        c.setRegistrationOpen(false);
        jdbc.update("UPDATE championship SET status='IN_PROGRESS',registration_open=FALSE WHERE id=?", c.getId());
        jdbc.update("CALL sp_start_heat(?, ?)", heatId, finishRunning);
        publish(heat.getWorkout().getCategory().getChampionship().getId());
        return scheduleQuery.byId(heatId);
    }

    /** Ajuste manual: Próxima / Em andamento / Concluída. */
    @Transactional
    public HeatView changeStatus(Long heatId, Long userId, HeatStatus status) {
        if (status == HeatStatus.IN_PROGRESS) return start(heatId, userId, true);
        Heat heat = guard.heat(heatId, userId);
        guard.requireEditable(heat.getWorkout().getCategory().getChampionship());
        if (status == HeatStatus.FINISHED && heat.getStatus() == HeatStatus.UPCOMING)
            throw ApiException.unprocessable("Inicie a bateria antes de concluí-la");
        heat.setStatus(status);
        heats.saveAndFlush(heat);
        publish(heat.getWorkout().getCategory().getChampionship().getId());
        return scheduleQuery.byId(heatId);
    }

    private void requirePlanEditable(Championship c) {
        guard.requireEditable(c);
        requireNotStarted(c);
        if (c.isRegistrationOpen()) throw ApiException.unprocessable("As baterias só podem ser geradas ou editadas após fechar as inscrições");
        if (c.getStatus() == ChampionshipStatus.CANCELLED) throw ApiException.unprocessable("O campeonato está cancelado");
    }

    private void requireNotStarted(Championship c) {
        Integer started = jdbc.queryForObject("SELECT count(*) FROM heat h JOIN workout w ON w.id=h.workout_id JOIN category cat ON cat.id=w.category_id WHERE cat.championship_id=? AND h.status<>'UPCOMING'", Integer.class, c.getId());
        if (c.hasStarted(Instant.now()) || c.getStatus() == ChampionshipStatus.IN_PROGRESS || (started != null && started > 0))
            throw ApiException.unprocessable("As baterias não podem ser alteradas depois do início do campeonato");
    }

    private void storeSettings(Championship c, GenerateAllHeatsRequest req) {
        c.setHeatLanes(req.lanes()); c.setHeatFirstStart(req.firstStart()); c.setHeatIntervalMinutes(req.intervalMinutes());
        jdbc.update("UPDATE championship SET heat_lanes=?,heat_first_start=?,heat_interval_minutes=? WHERE id=?",
                req.lanes(), OffsetDateTime.ofInstant(req.firstStart(), ZoneOffset.UTC), req.intervalMinutes(), c.getId());
    }

    private void publish(Long championshipId) {
        events.publishEvent(new ChampionshipUpdatedEvent(championshipId, ChampionshipUpdatedEvent.HEATS));
    }

    static int durationSeconds(Workout workout) {
        if (workout.getTimeCapSeconds() == null || workout.getTimeCapSeconds() <= 0) {
            throw ApiException.unprocessable("Informe a duração / time cap da prova " + workout.getNumber()
                    + " (" + workout.getName() + ") antes de montar as baterias.");
        }
        return workout.getTimeCapSeconds();
    }
}
