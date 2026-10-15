package br.com.wodz.repository;

import br.com.wodz.domain.Score;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScoreRepository extends JpaRepository<Score, Long> {

    Optional<Score> findByWorkoutIdAndTeamId(Long workoutId, Long teamId);

    boolean existsByWorkoutId(Long workoutId);
    boolean existsByWorkoutIdAndRepsGreaterThan(Long workoutId, Integer reps);
}
