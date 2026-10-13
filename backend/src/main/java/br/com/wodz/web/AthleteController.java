package br.com.wodz.web;

import br.com.wodz.common.CurrentUser;
import br.com.wodz.dto.TeamDtos.MyHeats;
import br.com.wodz.dto.TeamDtos.LinkCandidate;
import br.com.wodz.dto.TeamDtos.TeamNameRequest;
import br.com.wodz.dto.TeamDtos.TeamDto;
import br.com.wodz.dto.TeamDtos.TeamRegistrationRequest;
import br.com.wodz.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/athlete")
@SecurityRequirement(name = "bearer")
@Tag(name = "Atleta")
public class AthleteController {

    private final TeamService teams;

    public AthleteController(TeamService teams) {
        this.teams = teams;
    }

    @PostMapping("/championships/{slug}/teams")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Inscreve uma equipe; o primeiro atleta da lista é o capitão")
    public TeamDto register(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug,
                            @Valid @RequestBody TeamRegistrationRequest req) {
        return teams.register(slug, CurrentUser.id(jwt), req);
    }

    @GetMapping("/teams")
    public List<TeamDto> myTeams(@AuthenticationPrincipal Jwt jwt) {
        return teams.myTeams(CurrentUser.id(jwt));
    }

    @GetMapping("/championships/{slug}/team-links")
    public List<LinkCandidate> linkCandidates(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug) {
        return teams.linkCandidates(slug, CurrentUser.id(jwt));
    }

    @PostMapping("/championships/{slug}/teams/{teamId}/link")
    public TeamDto linkMember(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug, @PathVariable Long teamId) {
        return teams.linkMember(slug, teamId, CurrentUser.id(jwt));
    }

    @PutMapping("/teams/{teamId}/name")
    public TeamDto rename(@AuthenticationPrincipal Jwt jwt, @PathVariable Long teamId,
                          @Valid @RequestBody TeamNameRequest req) {
        return teams.rename(teamId, CurrentUser.id(jwt), req.name());
    }

    @PostMapping("/teams/{teamId}/cancel")
    @Operation(summary = "Cancela a inscrição pendente de pagamento de uma equipe do capitão")
    public TeamDto cancelRegistration(@AuthenticationPrincipal Jwt jwt, @PathVariable Long teamId) {
        return teams.cancelRegistration(teamId, CurrentUser.id(jwt));
    }

    @GetMapping("/heats")
    @Operation(summary = "Baterias das equipes do atleta, com horário e status")
    public MyHeats myHeats(@AuthenticationPrincipal Jwt jwt) {
        return teams.myHeats(CurrentUser.id(jwt));
    }
}
