package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.Codes;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.Workout;
import br.com.wodz.domain.WorkoutType;
import br.com.wodz.dto.ChampionshipDtos.CategoryDto;
import br.com.wodz.dto.ChampionshipDtos.CategoryRequest;
import br.com.wodz.dto.ChampionshipDtos.WorkoutDto;
import br.com.wodz.dto.ChampionshipDtos.WorkoutRequest;
import br.com.wodz.repository.CategoryRepository;
import br.com.wodz.repository.ScoreRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.repository.WorkoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Categorias e provas do campeonato. */
@Service
public class CatalogService {

    private final CategoryRepository categories;
    private final WorkoutRepository workouts;
    private final TeamRepository teams;
    private final ScoreRepository scores;
    private final OwnershipGuard guard;

    public CatalogService(CategoryRepository categories, WorkoutRepository workouts, TeamRepository teams,
                          ScoreRepository scores, OwnershipGuard guard) {
        this.categories = categories;
        this.workouts = workouts;
        this.teams = teams;
        this.scores = scores;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- categorias

    @Transactional
    public CategoryDto createCategory(Long championshipId, Long userId, CategoryRequest req) {
        Championship championship = guard.championship(championshipId, userId);
        Category category = new Category();
        category.setChampionship(championship);
        apply(category, req);
        categories.saveAndFlush(category);
        return Mappers.category(category, category.getMaxTeams(), List.of());
    }

    @Transactional
    public CategoryDto updateCategory(Long categoryId, Long userId, CategoryRequest req) {
        guard.category(categoryId, userId);
        Category category = categories.lockById(categoryId).orElseThrow(() -> ApiException.notFound("Categoria não encontrada"));
        var allowed = CategoryComposition.allowedCounts(req);
        if (category.getGenderPolicy() != null && teams.existsByCategoryId(categoryId)
                && (req.genderPolicy() != category.getGenderPolicy() || !allowed.equals(category.getAllowedMaleCounts()))) {
            throw ApiException.unprocessable("Não é possível mudar a composição: a categoria já tem inscrições");
        }
        if (req.teamSize() != category.getTeamSize() && teams.existsByCategoryId(categoryId)) {
            throw ApiException.unprocessable("Não é possível mudar o tamanho da equipe: a categoria já tem inscrições");
        }
        apply(category, req);
        categories.saveAndFlush(category);
        List<WorkoutDto> list = workouts.findByCategoryIdOrderByNumberAsc(categoryId).stream()
                .map(Mappers::workout).toList();
        return Mappers.category(category, null, list);
    }

    @Transactional
    public void deleteCategory(Long categoryId, Long userId) {
        Category category = guard.category(categoryId, userId);
        if (teams.existsByCategoryId(categoryId)) {
            throw ApiException.conflict("A categoria tem equipes inscritas e não pode ser excluída");
        }
        categories.delete(category);
    }

    private static void apply(Category c, CategoryRequest req) {
        c.setAllowedMaleCounts(CategoryComposition.allowedCounts(req));
        c.setGenderPolicy(req.genderPolicy());
        c.setName(Codes.uppercase(req.name().trim()));
        c.setDescription(Codes.uppercase(Codes.blankToNull(req.description())));
        c.setTeamSize(req.teamSize());
        c.setMaxTeams(req.maxTeams());
        c.setPrice(req.price());
        c.setDisplayOrder(req.displayOrder() == null ? 0 : req.displayOrder());
        if (req.movementStandards() != null) c.setMovementStandards(req.movementStandards().stream()
                .map(row -> new br.com.wodz.domain.MovementStandard(row.movement().trim(), row.male().trim(), row.female().trim())).toList());
    }

    // -------------------------------------------------------------------- provas

    /** Cria a mesma prova para uma ou mais categorias (Prova 1 - Iniciante, Prova 1 - Scaled...). */
    @Transactional
    public List<WorkoutDto> createWorkouts(Long championshipId, Long userId, WorkoutRequest req) {
        guard.championship(championshipId, userId);
        if (req.categoryIds() == null || req.categoryIds().isEmpty()) {
            throw ApiException.badRequest("Escolha ao menos uma categoria para a prova");
        }
        validate(req);
        List<WorkoutDto> created = new ArrayList<>();
        for (Long categoryId : new LinkedHashSet<>(req.categoryIds())) {
            Category category = guard.category(categoryId, userId);
            if (!category.getChampionship().getId().equals(championshipId)) {
                throw ApiException.badRequest("Categoria não pertence a este campeonato");
            }
            Workout workout = new Workout();
            workout.setCategory(category);
            apply(workout, req);
            workouts.saveAndFlush(workout);
            created.add(Mappers.workout(workout));
        }
        return created;
    }

    @Transactional
    public WorkoutDto updateWorkout(Long workoutId, Long userId, WorkoutRequest req) {
        Workout workout = guard.workout(workoutId, userId);
        validate(req);
        apply(workout, req);
        workouts.saveAndFlush(workout);
        return Mappers.workout(workout);
    }

    @Transactional
    public void deleteWorkout(Long workoutId, Long userId) {
        Workout workout = guard.workout(workoutId, userId);
        if (scores.existsByWorkoutId(workoutId)) {
            throw ApiException.conflict("A prova já tem resultados lançados e não pode ser excluída");
        }
        workouts.delete(workout);
    }

    private static void validate(WorkoutRequest req) {
        if (req.type() == WorkoutType.AMRAP && req.timeCapSeconds() == null) {
            throw ApiException.badRequest("Informe a duração do AMRAP");
        }
    }

    private static void apply(Workout w, WorkoutRequest req) {
        w.setNumber(req.number());
        w.setName(Codes.uppercase(req.name().trim()));
        w.setDescription(Codes.uppercase(Codes.blankToNull(req.description())));
        w.setType(req.type());
        w.setTimeCapSeconds(req.timeCapSeconds());
        w.setRepsPerRound(req.type() == WorkoutType.AMRAP ? req.repsPerRound() : null);
        w.setHasTiebreak(req.hasTiebreak());
    }
}
