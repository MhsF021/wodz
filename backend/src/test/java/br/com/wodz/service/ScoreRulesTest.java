package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.ScoreStatus;
import br.com.wodz.domain.Workout;
import br.com.wodz.domain.WorkoutType;
import br.com.wodz.dto.ScoreDtos.ScoreRequest;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ScoreRulesTest {
    private Workout workout(WorkoutType type) {
        var workout = new Workout(); workout.setType(type); workout.setTimeCapSeconds(600);
        workout.setMaxReps(90); workout.setRepsPerRound(type == WorkoutType.AMRAP ? 30 : null); return workout;
    }
    private ScoreRequest score(ScoreStatus status, Integer time, Integer reps, BigDecimal kg) {
        return new ScoreRequest(status,time,reps,kg,null,null,"JUDGE");
    }
    @Test void forTimeNoventaRepsAceitasNoventaEUmaBloqueadas() {
        var workout=workout(WorkoutType.FOR_TIME);
        assertDoesNotThrow(() -> ScoreRules.validate(workout,score(ScoreStatus.CAPPED,null,90,null)));
        var error=assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.CAPPED,null,91,null)));
        assertTrue(error.getMessage().contains("90"));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.CAPPED,null,-1,null)));
    }
    @Test void forTimeValidaTempoECamposIncompativeis() {
        var workout=workout(WorkoutType.FOR_TIME);
        assertDoesNotThrow(() -> ScoreRules.validate(workout,score(ScoreStatus.VALID,600000,null,null)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.VALID,600001,null,null)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.VALID,300000,1,null)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.CAPPED,300000,1,null)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.CAPPED,null,1,BigDecimal.TEN)));
    }
    @Test void provaLegadaExigeConfiguracaoAntesDeAceitarReps() {
        var workout=workout(WorkoutType.FOR_TIME);workout.setMaxReps(null);
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.CAPPED,null,1,null)));
        workout.setType(WorkoutType.AMRAP);workout.setRepsPerRound(null);
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.VALID,null,1,null)));
    }
    @Test void amrapRespeitaTetoQuandoDefinidoMasPodeTerRoundsSemTeto() {
        var workout=workout(WorkoutType.AMRAP);
        assertDoesNotThrow(() -> ScoreRules.validate(workout,score(ScoreStatus.VALID,null,90,null)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.VALID,null,91,null)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.CAPPED,null,1,null)));
        workout.setMaxReps(null);
        assertDoesNotThrow(() -> ScoreRules.validate(workout,score(ScoreStatus.VALID,null,300,null)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.VALID,1000,1,null)));
    }
    @Test void prAceitaSomenteKgEAusenciaNaoRecebeResultado() {
        var workout=workout(WorkoutType.MAX_LOAD);
        assertDoesNotThrow(() -> ScoreRules.validate(workout,score(ScoreStatus.VALID,null,null,new BigDecimal("142.5"))));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.VALID,null,1,BigDecimal.TEN)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.VALID,1000,null,BigDecimal.TEN)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.VALID,null,null,BigDecimal.valueOf(-1))));
        assertDoesNotThrow(() -> ScoreRules.validate(workout,score(ScoreStatus.DNS,null,null,null)));
        assertThrows(ApiException.class, () -> ScoreRules.validate(workout,score(ScoreStatus.DQ,null,1,null)));
    }
}
