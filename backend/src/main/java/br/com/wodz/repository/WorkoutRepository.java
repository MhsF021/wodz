package br.com.wodz.repository;

import br.com.wodz.domain.Workout;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkoutRepository extends JpaRepository<Workout, Long> {

    List<Workout> findByCategoryIdOrderByNumberAsc(Long categoryId);

    List<Workout> findByCategoryChampionshipIdOrderByCategoryDisplayOrderAscNumberAsc(Long championshipId);

    /**
     * SELECT ... FOR UPDATE na prova: serializa lançamentos simultâneos de
     * resultado na mesma prova (o recálculo do ranking toca todas as linhas).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workout w where w.id = :id")
    Optional<Workout> lockById(@Param("id") Long id);
}
