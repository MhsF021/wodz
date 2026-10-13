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

    @org.springframework.data.jpa.repository.Query("select t from Team t where t.captain.id = :userId or exists (select m.id from TeamMember m where m.team = t and m.user.id = :userId) order by t.createdAt desc")
    List<Team> findAccessibleByUserId(Long userId);

    @org.springframework.data.jpa.repository.Query("select distinct t from Team t join t.members m where t.category.championship.id = :championshipId and t.status <> br.com.wodz.domain.TeamStatus.CANCELLED and m.captain = false and m.user is null and lower(m.email) = lower(:email) order by t.id")
    List<Team> findLinkCandidates(Long championshipId, String email);

    List<Team> findByCaptainIdAndStatus(Long captainId, TeamStatus status);

    boolean existsByCaptainIdAndCategory_Championship_IdAndStatusNot(Long captainId, Long championshipId, TeamStatus status);

    List<Team> findByCategoryIdAndStatusOrderByNameAsc(Long categoryId, TeamStatus status);

    boolean existsByCategoryId(Long categoryId);

    long countByCategoryIdAndStatus(Long categoryId, TeamStatus status);

    boolean existsByCaptainIdAndStatusAndCategory_Championship_StatusNotIn(
            Long captainId, TeamStatus status, Collection<ChampionshipStatus> championshipStatuses);
}
