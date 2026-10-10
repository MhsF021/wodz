package br.com.wodz.repository;

import br.com.wodz.domain.ChampionshipStatus;
import br.com.wodz.domain.Team;
import br.com.wodz.domain.TeamStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Team t where t.id = :id")
    Optional<Team> lockById(Long id);

    Optional<Team> findByRegistrationCode(String registrationCode);

    List<Team> findByCaptainIdOrderByCreatedAtDesc(Long captainId);

    List<Team> findByCaptainIdAndStatus(Long captainId, TeamStatus status);

    boolean existsByCaptainIdAndCategory_Championship_IdAndStatusNot(Long captainId, Long championshipId, TeamStatus status);

    List<Team> findByCategoryIdAndStatusOrderByNameAsc(Long categoryId, TeamStatus status);

    boolean existsByCategoryId(Long categoryId);

    long countByCategoryIdAndStatus(Long categoryId, TeamStatus status);

    boolean existsByCaptainIdAndStatusAndCategory_Championship_StatusNotIn(
            Long captainId, TeamStatus status, Collection<ChampionshipStatus> championshipStatuses);
}
