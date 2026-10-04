package br.com.wodz.repository;

import br.com.wodz.domain.Heat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HeatRepository extends JpaRepository<Heat, Long> {

    @Query("select coalesce(max(h.number), 0) from Heat h where h.workout.id = :workoutId")
    int maxNumber(@Param("workoutId") Long workoutId);

    long countByWorkoutId(Long workoutId);
}
