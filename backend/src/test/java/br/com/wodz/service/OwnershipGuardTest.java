package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.*;
import br.com.wodz.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnershipGuardTest {
    @Test
    void outroOrganizadorNaoPodeAcessarCampeonatoOuRecursosVinculados() {
        AppUser owner = new AppUser();
        owner.setId(7L);
        Championship championship = new Championship();
        championship.setOwner(owner);
        Category category = new Category();
        category.setChampionship(championship);
        Workout workout = new Workout();
        workout.setCategory(category);
        Heat heat = new Heat();
        heat.setWorkout(workout);
        Team team = new Team();
        team.setCategory(category);

        ChampionshipRepository championships = mock(ChampionshipRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        WorkoutRepository workouts = mock(WorkoutRepository.class);
        HeatRepository heats = mock(HeatRepository.class);
        TeamRepository teams = mock(TeamRepository.class);
        when(championships.findById(1L)).thenReturn(Optional.of(championship));
        when(categories.findById(2L)).thenReturn(Optional.of(category));
        when(workouts.findById(3L)).thenReturn(Optional.of(workout));
        when(heats.findById(4L)).thenReturn(Optional.of(heat));
        when(teams.findById(5L)).thenReturn(Optional.of(team));
        OwnershipGuard guard = new OwnershipGuard(championships, categories, workouts, heats, teams);

        List<Supplier<?>> foreignRequests = List.of(() -> guard.championship(1L, 99L), () -> guard.category(2L, 99L),
                () -> guard.workout(3L, 99L), () -> guard.heat(4L, 99L), () -> guard.team(5L, 99L));
        for (Supplier<?> request : foreignRequests) {
            ApiException denied = assertThrows(ApiException.class, request::get);
            assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        }
        assertSame(championship, guard.championship(1L, 7L));
        assertSame(category, guard.category(2L, 7L));
        assertSame(workout, guard.workout(3L, 7L));
        assertSame(heat, guard.heat(4L, 7L));
        assertSame(team, guard.team(5L, 7L));
    }
}
