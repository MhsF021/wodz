package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.JdbcSupport;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.ScoreStatus;
import br.com.wodz.dto.LeaderboardDtos.LeaderboardDto;
import br.com.wodz.dto.LeaderboardDtos.LeaderboardRow;
import br.com.wodz.dto.LeaderboardDtos.ResultCell;
import br.com.wodz.dto.LeaderboardDtos.WorkoutRef;
import br.com.wodz.dto.Refs;
import br.com.wodz.repository.CategoryRepository;
import br.com.wodz.repository.WorkoutRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Leaderboard geral (fn_category_leaderboard) + resultado de cada prova (vw_score_board). */
@Service
public class LeaderboardService {

    private final ChampionshipService championshipService;
    private final CategoryRepository categories;
    private final WorkoutRepository workouts;
    private final JdbcTemplate jdbc;

    public LeaderboardService(ChampionshipService championshipService, CategoryRepository categories,
                              WorkoutRepository workouts, JdbcTemplate jdbc) {
        this.championshipService = championshipService;
        this.categories = categories;
        this.workouts = workouts;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public LeaderboardDto forChampionship(String slug, Long categoryId) {
        Championship championship = championshipService.findPublic(slug);
        List<Category> cats = categories.findByChampionshipIdOrderByDisplayOrderAscNameAsc(championship.getId());
        List<Refs.CategoryRef> refs = cats.stream().map(Mappers::categoryRef).toList();
        if (cats.isEmpty()) {
            return new LeaderboardDto(championship.getScoringSystem(), null, refs, List.of(), List.of());
        }
        Category category = categoryId == null
                ? cats.get(0)
                : cats.stream().filter(c -> c.getId().equals(categoryId)).findFirst()
                  .orElseThrow(() -> ApiException.notFound("Categoria não encontrada"));

        List<WorkoutRef> workoutRefs = workouts.findByCategoryIdOrderByNumberAsc(category.getId()).stream()
                .map(w -> new WorkoutRef(w.getId(), w.getNumber(), w.getName(), w.getType()))
                .toList();

        Map<Long, Map<Long, ResultCell>> cells = new HashMap<>();
        jdbc.query("SELECT team_id, workout_id, status, placement, points, result_display"
                        + " FROM vw_score_board WHERE category_id = ?",
                (RowCallbackHandler) rs -> cells
                        .computeIfAbsent(rs.getLong("team_id"), k -> new HashMap<>())
                        .put(rs.getLong("workout_id"), new ResultCell(
                                ScoreStatus.valueOf(rs.getString("status")),
                                JdbcSupport.integer(rs, "placement"),
                                rs.getBigDecimal("points"),
                                rs.getString("result_display"))),
                category.getId());

        Map<Long, List<String>> athleteNames = new HashMap<>();
        jdbc.query("SELECT m.team_id, m.full_name FROM team_member m JOIN team t ON t.id = m.team_id"
                        + " WHERE t.category_id = ? AND t.status = 'CONFIRMED' ORDER BY m.id",
                (RowCallbackHandler) rs -> athleteNames.computeIfAbsent(rs.getLong("team_id"), k -> new ArrayList<>())
                        .add(rs.getString("full_name")), category.getId());

        List<LeaderboardRow> rows = jdbc.query("SELECT * FROM fn_category_leaderboard(?)", (rs, rowNum) -> {
            Long teamId = rs.getLong("team_id");
            return new LeaderboardRow(
                    rs.getLong("overall_rank"),
                    teamId,
                    rs.getString("team_name"),
                    rs.getString("box_name"),
                    rs.getBigDecimal("total_points"),
                    rs.getInt("workouts_completed"),
                    rs.getInt("first_places"),
                    cells.getOrDefault(teamId, Map.of()), athleteNames.getOrDefault(teamId, List.of()));
        }, category.getId());

        return new LeaderboardDto(championship.getScoringSystem(), Mappers.categoryRef(category), refs,
                workoutRefs, rows);
    }
}
