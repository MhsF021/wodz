package br.com.wodz.dto;

import br.com.wodz.domain.HeatStatus;
import br.com.wodz.domain.WorkoutType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class HeatDtos {

    private HeatDtos() {
    }

    /** judgeName: informado pelo organizador ao lançar o resultado da raia. */
    public record LaneView(int lane, Long teamId, String teamName, String boxName, String judgeName) {
    }

    /** Linha da view vw_heat_schedule. */
    public record HeatView(Long heatId, int heatNumber, Instant scheduledAt, HeatStatus status, Instant startedAt,
                           Instant finishedAt, Long workoutId, int workoutNumber, String workoutName,
                           WorkoutType workoutType, Integer timeCapSeconds, Long categoryId, String categoryName,
                           Long championshipId, String championshipSlug, String championshipName,
                           List<LaneView> lanes, Integer durationSeconds, int intervalMinutes) {

        @com.fasterxml.jackson.annotation.JsonProperty
        public Instant expectedFinishAt() {
            Instant start = startedAt == null ? scheduledAt : startedAt;
            return durationSeconds == null ? null : start.plusSeconds(durationSeconds);
        }

        @com.fasterxml.jackson.annotation.JsonProperty
        public Long actualDurationSeconds() {
            return startedAt == null || finishedAt == null ? null
                    : Math.max(0, java.time.Duration.between(startedAt, finishedAt).getSeconds());
        }
    }

    public record GenerateHeatsRequest(
            @NotNull @Min(1) @Max(value = 8, message = "Cada bateria tem no máximo 8 equipes") Integer lanes,
            @NotNull Instant firstStart,
            @NotNull @Min(0) @Max(240) Integer intervalMinutes) {
    }

    /** Todas as provas do campeonato de uma vez, até 8 equipes por bateria, em sequência a partir de firstStart. */
    public record GenerateAllHeatsRequest(
            @NotNull(message = "Informe a quantidade de equipes por bateria") @Min(1) @Max(8) Integer lanes,
            @NotNull Instant firstStart,
            @NotNull @Min(0) @Max(240) Integer intervalMinutes) {
    }

    public record HeatPlanEntry(Long heatId, @NotNull Long workoutId, @Min(1) int heatNumber,
                                @NotNull Instant scheduledAt,
                                @NotNull @Size(max = 8) List<@NotNull Long> teamIds) {}

    public record HeatPlanRequest(@NotNull @Size(max = 5000) List<@NotNull @Valid HeatPlanEntry> heats) {}

    /** teamIds na ordem das raias (1, 2, 3...). Null = mantém as raias atuais. */
    public record HeatRequest(@NotNull Instant scheduledAt, List<Long> teamIds,
                              @Min(0) @Max(240) Integer intervalMinutes) {
    }

    public record HeatStatusRequest(@NotNull HeatStatus status) {
    }
}
