package br.com.wodz.web;

import br.com.wodz.common.CurrentUser;
import br.com.wodz.dto.HeatDtos.GenerateAllHeatsRequest;
import br.com.wodz.dto.HeatDtos.GenerateHeatsRequest;
import br.com.wodz.dto.HeatDtos.HeatRequest;
import br.com.wodz.dto.HeatDtos.HeatStatusRequest;
import br.com.wodz.dto.HeatDtos.HeatView;
import br.com.wodz.service.HeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@SecurityRequirement(name = "bearer")
@Tag(name = "Organizador: baterias")
public class AdminHeatController {

    private final HeatService heats;

    public AdminHeatController(HeatService heats) {
        this.heats = heats;
    }

    @GetMapping("/championships/{id}/heats")
    public List<HeatView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @RequestParam(required = false) Long categoryId) {
        return heats.list(id, categoryId, CurrentUser.id(jwt));
    }

    @PostMapping("/championships/{id}/heats/generate")
    @Operation(summary = "Gera as baterias de todas as provas do campeonato, com até 8 equipes por bateria")
    public List<HeatView> generateAll(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                      @Valid @RequestBody GenerateAllHeatsRequest req) {
        return heats.generateAll(id, CurrentUser.id(jwt), req);
    }

    @PostMapping("/workouts/{id}/heats/generate")
    @Operation(summary = "Gera as baterias da prova com as equipes confirmadas (procedure sp_generate_heats)")
    public List<HeatView> generate(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                   @Valid @RequestBody GenerateHeatsRequest req) {
        return heats.generate(id, CurrentUser.id(jwt), req);
    }

    @PostMapping("/workouts/{id}/heats")
    @ResponseStatus(HttpStatus.CREATED)
    public HeatView create(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                           @Valid @RequestBody HeatRequest req) {
        return heats.create(id, CurrentUser.id(jwt), req);
    }

    @PutMapping("/heats/{id}")
    public HeatView update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                           @Valid @RequestBody HeatRequest req) {
        return heats.update(id, CurrentUser.id(jwt), req);
    }

    @DeleteMapping("/heats/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        heats.delete(id, CurrentUser.id(jwt));
    }

    @PostMapping("/heats/{id}/start")
    @Operation(summary = "Inicia a bateria (procedure sp_start_heat) e conclui a que estava em andamento")
    public HeatView start(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                          @RequestParam(defaultValue = "true") boolean finishRunning) {
        return heats.start(id, CurrentUser.id(jwt), finishRunning);
    }

    @PatchMapping("/heats/{id}/status")
    public HeatView status(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                           @Valid @RequestBody HeatStatusRequest req) {
        return heats.changeStatus(id, CurrentUser.id(jwt), req.status());
    }
}
