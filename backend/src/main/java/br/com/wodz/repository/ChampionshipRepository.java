package br.com.wodz.repository;

import br.com.wodz.domain.Championship;
import br.com.wodz.domain.ChampionshipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChampionshipRepository extends JpaRepository<Championship, Long> {

    interface LockedState {
        String getStatus();
        java.time.LocalDate getStartDate();
        boolean getRegistrationOpen();
    }

    // A projeção escalar lê o estado atual mesmo se a entidade já estiver no cache JPA.
    @Query(value = "SELECT status, start_date AS \"startDate\", registration_open AS \"registrationOpen\" "
            + "FROM championship WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<LockedState> lockStateById(@Param("id") Long id);

    Optional<Championship> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Championship> findByOwnerIdOrderByStartDateDesc(Long ownerId);

    boolean existsByOwnerIdAndStatusIn(Long ownerId, Collection<ChampionshipStatus> statuses);

    /** Usa a function fn_search_championships (busca sem acento + trigram). */
    @Query(value = "SELECT * FROM fn_search_championships(:term, :state)", nativeQuery = true)
    List<Championship> search(@Param("term") String term, @Param("state") String state);

    @Query(value = "SELECT * FROM fn_search_championships(:term, :state, :filter)", nativeQuery = true)
    List<Championship> searchFiltered(@Param("term") String term, @Param("state") String state, @Param("filter") String filter);
}
