package br.com.wodz.dto;

import br.com.wodz.domain.HeatStatus;
import br.com.wodz.domain.ScoreStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ScoreDtos {

    private ScoreDtos() {
    }

    /**
     * Campos usados conforme o tipo da prova:
     * FOR_TIME -> timeMs (ou reps se CAPPED); AMRAP/MAX_REPS -> reps; MAX_LOAD -> loadKg.
     * judgeName fica gravado na raia da equipe nesta prova.
     */
    public record ScoreRequest(
            @NotNull ScoreStatus status,
            @Positive Integer timeMs,
            @PositiveOrZero Integer reps,
            @DecimalMin("0.0") BigDecimal loadKg,
            @Positive Integer tiebreakMs,
            @Size(max = 255) String notes,
            @NotBlank(message = "Informe o nome do judge da raia") @Size(max = 120) String judgeName) {
    }

    /** Resultado (se lançado) e a raia da equipe na prova (se as baterias já foram montadas). */
    public record ScoreEntry(Long teamId, String teamName, String boxName, Long scoreId, ScoreStatus status,
                             Integer timeMs, Integer reps, BigDecimal loadKg, Integer tiebreakMs, Integer placement,
                             BigDecimal points, String display, String notes,
                             Long heatId, Integer heatNumber, HeatStatus heatStatus, Instant heatScheduledAt,
                             Integer lane, String judgeName) {
    }

    public record WorkoutScores(ChampionshipDtos.WorkoutDto workout, String categoryName, List<ScoreEntry> entries) {
    }
}
