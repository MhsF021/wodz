package br.com.wodz.service;

import br.com.wodz.common.JdbcSupport;
import br.com.wodz.domain.HeatStatus;
import br.com.wodz.domain.WorkoutType;
import br.com.wodz.dto.HeatDtos.HeatView;
import br.com.wodz.dto.HeatDtos.LaneView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/** Leitura da grade de baterias (view vw_heat_schedule). */
@Service
public class ScheduleQueryService {

    private static final String BASE = "SELECT * FROM vw_heat_schedule ";
    private static final TypeReference<List<LaneView>> LANES = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ScheduleQueryService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<HeatView> forChampionship(Long championshipId, Long categoryId) {
        MapSqlParameterSource params = new MapSqlParameterSource("championshipId", championshipId);
        String sql = BASE + "WHERE championship_id = :championshipId";
        if (categoryId != null) {
            sql += " AND category_id = :categoryId";
            params.addValue("categoryId", categoryId);
        }
        sql += " ORDER BY scheduled_at, category_order, workout_number, heat_number";
        return jdbc.query(sql, params, this::map);
    }

    public List<HeatView> forTeams(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return List.of();
        }
        String sql = BASE + "WHERE heat_id IN (SELECT heat_id FROM heat_lane WHERE team_id IN (:teamIds))"
                + " ORDER BY scheduled_at";
        return jdbc.query(sql, new MapSqlParameterSource("teamIds", teamIds), this::map);
    }

    public HeatView byId(Long heatId) {
        return jdbc.queryForObject(BASE + "WHERE heat_id = :heatId",
                new MapSqlParameterSource("heatId", heatId), this::map);
    }

    private HeatView map(ResultSet rs, int rowNum) throws SQLException {
        return new HeatView(
                rs.getLong("heat_id"),
                rs.getInt("heat_number"),
                JdbcSupport.instant(rs, "scheduled_at"),
                HeatStatus.valueOf(rs.getString("status")),
                JdbcSupport.instant(rs, "started_at"),
                JdbcSupport.instant(rs, "finished_at"),
                rs.getLong("workout_id"),
                rs.getInt("workout_number"),
                rs.getString("workout_name"),
                WorkoutType.valueOf(rs.getString("workout_type")),
                JdbcSupport.integer(rs, "time_cap_seconds"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getLong("championship_id"),
                rs.getString("championship_slug"),
                rs.getString("championship_name"),
                lanes(rs.getString("lanes")),
                JdbcSupport.integer(rs, "duration_seconds"),
                rs.getInt("interval_minutes"));
    }

    private List<LaneView> lanes(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, LANES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON de raias inválido", e);
        }
    }
}
