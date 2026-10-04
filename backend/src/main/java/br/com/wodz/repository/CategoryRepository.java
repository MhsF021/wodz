package br.com.wodz.repository;

import br.com.wodz.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByChampionshipIdOrderByDisplayOrderAscNameAsc(Long championshipId);
}
