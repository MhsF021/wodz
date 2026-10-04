package br.com.wodz.web;

import br.com.wodz.common.CurrentUser;
import br.com.wodz.dto.ScoreDtos.ScoreEntry;
import br.com.wodz.dto.ScoreDtos.ScoreRequest;
import br.com.wodz.dto.ScoreDtos.WorkoutScores;
import br.com.wodz.service.ScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/workouts/{workoutId}/scores")
@SecurityRequirement(name = "bearer")
@Tag(name = "Organizador: pontuação")
public class AdminScoreController {

    private final ScoreService scores;

    public AdminScoreController(ScoreService scores) {
        this.scores = scores;
    }

    @GetMapping
    public WorkoutScores list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long workoutId) {
        return scores.list(workoutId, CurrentUser.id(jwt));
    }

    @PutMapping("/{teamId}")
    @Operation(summary = "Lança ou corrige o resultado (tempo, repetições ou carga conforme o tipo da prova)")
    public ScoreEntry save(@AuthenticationPrincipal Jwt jwt, @PathVariable Long workoutId,
                           @PathVariable Long teamId, @Valid @RequestBody ScoreRequest req) {
        return scores.save(workoutId, teamId, req, CurrentUser.id(jwt));
    }

    @DeleteMapping("/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long workoutId, @PathVariable Long teamId) {
        scores.delete(workoutId, teamId, CurrentUser.id(jwt));
    }
}
