package br.com.wodz.web;

import br.com.wodz.common.CurrentUser;
import br.com.wodz.dto.ChampionshipDtos.AdminChampionshipSummary;
import br.com.wodz.dto.ChampionshipDtos.CategoryDto;
import br.com.wodz.dto.ChampionshipDtos.CategoryRequest;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipDetail;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipRequest;
import br.com.wodz.dto.ChampionshipDtos.RegistrationToggleRequest;
import br.com.wodz.dto.ChampionshipDtos.StatusRequest;
import br.com.wodz.dto.ChampionshipDtos.WorkoutDto;
import br.com.wodz.dto.ChampionshipDtos.WorkoutRequest;
import br.com.wodz.service.CatalogService;
import br.com.wodz.service.ChampionshipService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@SecurityRequirement(name = "bearer")
@Tag(name = "Organizador: campeonatos, categorias e provas")
public class AdminChampionshipController {

    private final ChampionshipService championships;
    private final CatalogService catalog;

    public AdminChampionshipController(ChampionshipService championships, CatalogService catalog) {
        this.championships = championships;
        this.catalog = catalog;
    }

    @GetMapping("/championships")
    public List<AdminChampionshipSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return championships.listMine(CurrentUser.id(jwt));
    }

    @PostMapping("/championships")
    @ResponseStatus(HttpStatus.CREATED)
    public ChampionshipDetail create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChampionshipRequest req) {
        return championships.create(CurrentUser.id(jwt), req);
    }

    @GetMapping("/championships/{id}")
    public ChampionshipDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return championships.adminDetail(id, CurrentUser.id(jwt));
    }

    @PutMapping("/championships/{id}")
    public ChampionshipDetail update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @Valid @RequestBody ChampionshipRequest req) {
        return championships.update(id, CurrentUser.id(jwt), req);
    }

    @PatchMapping("/championships/{id}/registration")
    @Operation(summary = "Publica ou fecha o formulário de inscrição")
    public ChampionshipDetail registration(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                           @RequestBody RegistrationToggleRequest req) {
        return championships.setRegistrationOpen(id, CurrentUser.id(jwt), req.open());
    }

    @PatchMapping("/championships/{id}/status")
    public ChampionshipDetail status(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @Valid @RequestBody StatusRequest req) {
        return championships.setStatus(id, CurrentUser.id(jwt), req.status());
    }

    @PostMapping("/championships/{id}/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto createCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                      @Valid @RequestBody CategoryRequest req) {
        return catalog.createCategory(id, CurrentUser.id(jwt), req);
    }

    @PutMapping("/categories/{id}")
    public CategoryDto updateCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                      @Valid @RequestBody CategoryRequest req) {
        return catalog.updateCategory(id, CurrentUser.id(jwt), req);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        catalog.deleteCategory(id, CurrentUser.id(jwt));
    }

    @PostMapping("/championships/{id}/workouts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria a prova para uma ou mais categorias (ex.: Prova 1 - Iniciante e Prova 1 - Scaled)")
    public List<WorkoutDto> createWorkouts(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                           @Valid @RequestBody WorkoutRequest req) {
        return catalog.createWorkouts(id, CurrentUser.id(jwt), req);
    }

    @PutMapping("/workouts/{id}")
    public WorkoutDto updateWorkout(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @Valid @RequestBody WorkoutRequest req) {
        return catalog.updateWorkout(id, CurrentUser.id(jwt), req);
    }

    @DeleteMapping("/workouts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkout(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        catalog.deleteWorkout(id, CurrentUser.id(jwt));
    }
}
