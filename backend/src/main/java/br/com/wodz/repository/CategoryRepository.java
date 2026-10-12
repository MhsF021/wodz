package br.com.wodz.repository;

import br.com.wodz.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Category c where c.id = :id")
    java.util.Optional<Category> lockById(Long id);

    List<Category> findByChampionshipIdOrderByDisplayOrderAscNameAsc(Long championshipId);
}
