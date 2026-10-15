package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.Category;
import br.com.wodz.domain.Championship;
import br.com.wodz.domain.Heat;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.Workout;
import br.com.wodz.repository.CategoryRepository;
import br.com.wodz.repository.ChampionshipRepository;
import br.com.wodz.repository.HeatRepository;
import br.com.wodz.repository.TeamRepository;
import br.com.wodz.repository.WorkoutRepository;
import org.springframework.stereotype.Component;

/**
 * Garante que o organizador só mexa nos próprios campeonatos.
 * Usar dentro de métodos @Transactional (navega associações LAZY).
 */
@Component
public class OwnershipGuard {

    private final ChampionshipRepository championships;
    private final CategoryRepository categories;
    private final WorkoutRepository workouts;
    private final HeatRepository heats;
    private final TeamRepository teams;

    public OwnershipGuard(ChampionshipRepository championships, CategoryRepository categories,
                          WorkoutRepository workouts, HeatRepository heats, TeamRepository teams) {
        this.championships = championships;
        this.categories = categories;
        this.workouts = workouts;
        this.heats = heats;
        this.teams = teams;
    }

    public Championship championship(Long id, Long userId) {
        Championship c = championships.findById(id)
                .orElseThrow(() -> ApiException.notFound("Campeonato não encontrado"));
        check(c, userId);
        return c;
    }

    public Category category(Long id, Long userId) {
        Category category = categories.findById(id)
                .orElseThrow(() -> ApiException.notFound("Categoria não encontrada"));
        check(category.getChampionship(), userId);
        return category;
    }

    public Workout workout(Long id, Long userId) {
        Workout workout = workouts.findById(id)
                .orElseThrow(() -> ApiException.notFound("Prova não encontrada"));
        check(workout.getCategory().getChampionship(), userId);
        return workout;
    }

    public Heat heat(Long id, Long userId) {
        Heat heat = heats.findById(id)
                .orElseThrow(() -> ApiException.notFound("Bateria não encontrada"));
        check(heat.getWorkout().getCategory().getChampionship(), userId);
        return heat;
    }

    public Team team(Long id, Long userId) {
        Team team = teams.findById(id)
                .orElseThrow(() -> ApiException.notFound("Equipe não encontrada"));
        check(team.getCategory().getChampionship(), userId);
        return team;
    }

    private static void check(Championship championship, Long userId) {
        if (!championship.getOwner().getId().equals(userId)) {
            throw ApiException.forbidden("Você não é o organizador deste campeonato");
        }
    }

    public void requireEditable(Championship championship) {
        var locked = championships.lockStateById(championship.getId())
                .orElseThrow(() -> ApiException.notFound("Campeonato não encontrado"));
        if (locked.getStatus().equals("FINISHED")) {
            throw ApiException.unprocessable("Este campeonato foi encerrado e está disponível apenas para consulta");
        }
        championship.setStatus(br.com.wodz.domain.ChampionshipStatus.valueOf(locked.getStatus()));
        championship.setStartDate(locked.getStartDate());
        championship.setRegistrationOpen(locked.getRegistrationOpen());
    }
}
