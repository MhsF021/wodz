package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.ScoreStatus;
import br.com.wodz.domain.Workout;
import br.com.wodz.domain.WorkoutType;
import br.com.wodz.dto.ScoreDtos.ScoreRequest;

/** Validação compartilhada pelos lançamentos individuais e em lote. */
final class ScoreRules {
    private ScoreRules() {}

    static void validate(Workout workout, ScoreRequest result) {
        if (result.status() == null) fail("Informe a situação do resultado");
        if (result.status() == ScoreStatus.DNS || result.status() == ScoreStatus.DQ) {
            if (result.timeMs() != null || result.reps() != null || result.loadKg() != null || result.tiebreakMs() != null)
                fail("Ausência e desclassificação não recebem tempo, repetições ou carga");
            return;
        }
        if (result.status() == ScoreStatus.CAPPED && workout.getType() != WorkoutType.FOR_TIME)
            fail("CAP só se aplica a provas For time");
        switch (workout.getType()) {
            case FOR_TIME -> {
                if (result.loadKg() != null) fail("For time aceita tempo ou CAP com repetições, sem carga em kg");
                if (result.status() == ScoreStatus.VALID) {
                    if (result.reps() != null) fail("Uma prova concluída recebe apenas o tempo; use CAP para informar repetições");
                    if (result.timeMs() == null || result.timeMs() <= 0) fail("Informe um tempo válido");
                    if (workout.getTimeCapSeconds() != null && result.timeMs() > workout.getTimeCapSeconds() * 1000L)
                        fail("Tempo acima do time cap. Lance como CAP + repetições");
                } else {
                    if (result.timeMs() != null) fail("No CAP, informe as repetições feitas até o limite de tempo");
                    if (workout.getTimeCapSeconds() == null) fail("Configure o time cap da prova antes de lançar um resultado no CAP");
                    if (workout.getMaxReps() == null) fail("Informe o total de repetições em Editar prova antes de lançar resultados no CAP");
                    repetitions(workout, result);
                }
            }
            case AMRAP, MAX_REPS -> {
                if (result.timeMs() != null || result.loadKg() != null) fail("Esta prova aceita repetições, sem tempo de conclusão ou carga em kg");
                if (workout.getType() == WorkoutType.AMRAP && (workout.getRepsPerRound() == null || workout.getRepsPerRound() < 1))
                    fail("Informe as repetições por round em Editar prova antes de lançar resultados do AMRAP");
                repetitions(workout, result);
            }
            case MAX_LOAD -> {
                if (result.timeMs() != null || result.reps() != null || result.tiebreakMs() != null)
                    fail("PR aceita apenas a carga total em kg");
                if (result.loadKg() == null || result.loadKg().signum() < 0) fail("Informe uma carga total válida em kg");
            }
        }
        if (result.tiebreakMs() != null) {
            if (!workout.isHasTiebreak()) fail("Esta prova não tem desempate por tempo");
            if (result.tiebreakMs() <= 0) fail("Informe um tempo de desempate válido");
            long limit = result.timeMs() != null ? result.timeMs() : workout.getTimeCapSeconds() == null ? Long.MAX_VALUE : workout.getTimeCapSeconds() * 1000L;
            if (result.tiebreakMs() > limit) fail("O desempate não pode ultrapassar o tempo da prova");
        }
    }

    private static void repetitions(Workout workout, ScoreRequest result) {
        if (result.reps() == null || result.reps() < 0) fail("Informe uma quantidade válida de repetições");
        if (workout.getMaxReps() != null && result.reps() > workout.getMaxReps())
            fail("Esta prova permite no máximo " + workout.getMaxReps() + " repetições. Revise o resultado.");
    }

    private static void fail(String message) { throw ApiException.unprocessable(message); }
}
