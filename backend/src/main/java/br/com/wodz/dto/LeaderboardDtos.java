package br.com.wodz.dto;

import br.com.wodz.domain.ScoreStatus;
import br.com.wodz.domain.ScoringSystem;
import br.com.wodz.domain.WorkoutType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class LeaderboardDtos {

    private LeaderboardDtos() {
    }

    public record WorkoutRef(Long id, int number, String name, WorkoutType type) {
    }

    public record ResultCell(ScoreStatus status, Integer placement, BigDecimal points, String display) {
    }

    /** results: workoutId -> resultado da equipe naquela prova. */
    public record LeaderboardRow(long rank, Long teamId, String teamName, String boxName, BigDecimal totalPoints,
                                 int workoutsCompleted, int firstPlaces, Map<Long, ResultCell> results) {
    }

    public record LeaderboardDto(ScoringSystem scoringSystem, Refs.CategoryRef category,
                                 List<Refs.CategoryRef> categories, List<WorkoutRef> workouts,
                                 List<LeaderboardRow> rows) {
    }
}
