package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.common.JdbcSupport;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.HeatStatus;
import br.com.wodz.domain.Score;
import br.com.wodz.domain.ScoreStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import br.com.wodz.domain.Workout;
import br.com.wodz.dto.ScoreDtos.ScoreEntry;
import br.com.wodz.dto.ScoreDtos.ScoreRequest;
import br.com.wodz.dto.ScoreDtos.WorkoutScores;
import br.com.wodz.repository.ScoreRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.repository.WorkoutRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lançamento de resultados. Só vale para equipes cuja bateria na prova já
 * foi concluída, e grava o judge da raia junto. Colocação e pontos são
 * recalculados pelo banco (trigger trg_score_rerank) e lidos de volta pela
 * view vw_score_board.
 */
@Service
public class ScoreService {

    private final ScoreRepository scores;
    private final WorkoutRepository workouts;
    private final TeamRepository teams;
    private final OwnershipGuard guard;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public ScoreService(ScoreRepository scores, WorkoutRepository workouts, TeamRepository teams,
                        OwnershipGuard guard, JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.scores = scores;
        this.workouts = workouts;
        this.teams = teams;
        this.guard = guard;
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Todas as equipes confirmadas da categoria, com a raia na prova e o resultado (se lançado). */
    @Transactional(readOnly = true)
    public WorkoutScores list(Long workoutId, Long userId) {
        Workout workout = guard.workout(workoutId, userId);
        Category category = workout.getCategory();
        Map<Long, ScoreRow> rows = loadRows(workoutId, null);
        Map<Long, LaneRow> lanes = loadLanes(workoutId, null);
        List<ScoreEntry> entries = teams.findByCategoryIdAndStatusOrderByNameAsc(category.getId(), TeamStatus.CONFIRMED)
                .stream()
                .map(team -> entry(team, rows.get(team.getId()), lanes.get(team.getId())))
                .sorted(Comparator.comparing(ScoreEntry::heatNumber, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                        .thenComparing(ScoreEntry::lane, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                        .thenComparing(ScoreEntry::teamName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new WorkoutScores(Mappers.workout(workout), category.getName(), entries);
    }

    @Transactional
    public ScoreEntry save(Long workoutId, Long teamId, ScoreRequest req, Long userId) {
        guard.workout(workoutId, userId);
        Workout workout = lock(workoutId);
        Team team = teams.findById(teamId).orElseThrow(() -> ApiException.notFound("Equipe não encontrada"));
        if (!team.getCategory().getId().equals(workout.getCategory().getId())) {
            throw ApiException.badRequest("A equipe não pertence à categoria desta prova");
        }
        LaneRow lane = loadLanes(workoutId, teamId).get(teamId);
        if (lane == null) {
            throw ApiException.unprocessable("A equipe " + team.getName()
                    + " não está em nenhuma bateria desta prova. Gere as baterias antes de lançar o resultado.");
        }
        if (lane.heatStatus() != HeatStatus.FINISHED) {
            throw ApiException.unprocessable("O resultado de " + team.getName()
                    + " só pode ser lançado depois que a bateria " + lane.heatNumber() + " for concluída");
        }
        auditAs(userId);

        Score score = scores.findByWorkoutIdAndTeamId(workoutId, teamId).orElseGet(() -> {
            Score created = new Score();
            created.setWorkout(workout);
            created.setTeam(team);
            created.setCreatedBy(userId);
            return created;
        });
        score.setStatus(req.status());
        score.setTimeMs(req.timeMs());
        score.setReps(req.reps());
        score.setLoadKg(req.loadKg());
        score.setTiebreakMs(req.tiebreakMs());
        score.setNotes(Codes.blankToNull(req.notes()));
        scores.saveAndFlush(score);
        jdbc.update("UPDATE heat_lane SET judge_name = ? WHERE id = ?", Codes.uppercase(req.judgeName().trim()), lane.laneId());

        publish(workout);
        return entry(team, loadRows(workoutId, teamId).get(teamId), loadLanes(workoutId, teamId).get(teamId));
    }

    @Transactional
    public void delete(Long workoutId, Long teamId, Long userId) {
        guard.workout(workoutId, userId);
        Workout workout = lock(workoutId);
        auditAs(userId);
        scores.findByWorkoutIdAndTeamId(workoutId, teamId).ifPresent(scores::delete);
        scores.flush();
        publish(workout);
    }

    // ----------------------------------------------------------------- helpers

    /** SELECT ... FOR UPDATE na prova: evita deadlock no recálculo do ranking. */
    private Workout lock(Long workoutId) {
        return workouts.lockById(workoutId).orElseThrow(() -> ApiException.notFound("Prova não encontrada"));
    }

    /** Informa à trigger de auditoria quem está lançando (válido só nesta transação). */
    private void auditAs(Long userId) {
        jdbc.queryForObject("SELECT set_config('app.current_user_id', ?, true)", String.class, String.valueOf(userId));
    }

    private void publish(Workout workout) {
        events.publishEvent(new ChampionshipUpdatedEvent(
                workout.getCategory().getChampionship().getId(), ChampionshipUpdatedEvent.LEADERBOARD));
    }

    private Map<Long, ScoreRow> loadRows(Long workoutId, Long teamId) {
        Map<Long, ScoreRow> rows = new HashMap<>();
        List<Object> args = new ArrayList<>();
        String sql = "SELECT * FROM vw_score_board WHERE workout_id = ?";
        args.add(workoutId);
        if (teamId != null) {
            sql += " AND team_id = ?";
            args.add(teamId);
        }
        jdbc.query(sql, (RowCallbackHandler) rs -> rows.put(rs.getLong("team_id"), new ScoreRow(
                rs.getLong("score_id"),
                ScoreStatus.valueOf(rs.getString("status")),
                JdbcSupport.integer(rs, "time_ms"),
                JdbcSupport.integer(rs, "reps"),
                rs.getBigDecimal("load_kg"),
                JdbcSupport.integer(rs, "tiebreak_ms"),
                JdbcSupport.integer(rs, "placement"),
                rs.getBigDecimal("points"),
                rs.getString("result_display"),
                rs.getString("notes"))), args.toArray());
        return rows;
    }

    /** Raia de cada equipe nesta prova (cada equipe corre uma vez por prova). */
    private Map<Long, LaneRow> loadLanes(Long workoutId, Long teamId) {
        Map<Long, LaneRow> lanes = new HashMap<>();
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT hl.id AS lane_id, hl.team_id, hl.lane, hl.judge_name,
                       h.id AS heat_id, h.number AS heat_number, h.status AS heat_status, h.scheduled_at
                  FROM heat_lane hl
                  JOIN heat h ON h.id = hl.heat_id
                 WHERE h.workout_id = ?""";
        args.add(workoutId);
        if (teamId != null) {
            sql += " AND hl.team_id = ?";
            args.add(teamId);
        }
        jdbc.query(sql, (RowCallbackHandler) rs -> lanes.put(rs.getLong("team_id"), new LaneRow(
                rs.getLong("lane_id"),
                rs.getLong("heat_id"),
                rs.getInt("heat_number"),
                HeatStatus.valueOf(rs.getString("heat_status")),
                JdbcSupport.instant(rs, "scheduled_at"),
                rs.getInt("lane"),
                rs.getString("judge_name"))), args.toArray());
        return lanes;
    }

    private static ScoreEntry entry(Team team, ScoreRow r, LaneRow l) {
        return new ScoreEntry(team.getId(), team.getName(), team.getBoxName(),
                r == null ? null : r.scoreId(), r == null ? null : r.status(), r == null ? null : r.timeMs(),
                r == null ? null : r.reps(), r == null ? null : r.loadKg(), r == null ? null : r.tiebreakMs(),
                r == null ? null : r.placement(), r == null ? null : r.points(), r == null ? null : r.display(),
                r == null ? null : r.notes(),
                l == null ? null : l.heatId(), l == null ? null : l.heatNumber(), l == null ? null : l.heatStatus(),
                l == null ? null : l.scheduledAt(), l == null ? null : l.lane(), l == null ? null : l.judgeName());
    }

    private record ScoreRow(Long scoreId, ScoreStatus status, Integer timeMs, Integer reps, BigDecimal loadKg,
                            Integer tiebreakMs, Integer placement, BigDecimal points, String display, String notes) {
    }

    private record LaneRow(Long laneId, Long heatId, int heatNumber, HeatStatus heatStatus, Instant scheduledAt,
                           int lane, String judgeName) {
    }
}
