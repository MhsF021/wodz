package br.com.wodz.web;

import br.com.wodz.common.CurrentUser;
import br.com.wodz.dto.TeamDtos.TeamDto;
import br.com.wodz.dto.TeamDtos.TeamOverview;
import br.com.wodz.dto.TeamDtos.TeamStatusRequest;
import br.com.wodz.service.TeamService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@SecurityRequirement(name = "bearer")
@Tag(name = "Organizador: equipes")
public class AdminTeamController {

    private final TeamService teams;

    public AdminTeamController(TeamService teams) {
        this.teams = teams;
    }

    @GetMapping("/championships/{id}/teams")
    public List<TeamOverview> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                   @RequestParam(required = false) Long categoryId) {
        return teams.adminList(id, categoryId, CurrentUser.id(jwt));
    }

    @GetMapping("/teams/{id}")
    public TeamDto detail(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return teams.adminDetail(id, CurrentUser.id(jwt));
    }

    @PatchMapping("/teams/{id}/status")
    public TeamDto status(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                          @Valid @RequestBody TeamStatusRequest req) {
        return teams.changeStatus(id, CurrentUser.id(jwt), req.status());
    }
}
