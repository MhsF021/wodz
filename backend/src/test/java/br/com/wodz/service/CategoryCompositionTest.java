package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.*;
import br.com.wodz.dto.ChampionshipDtos.CategoryRequest;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CategoryCompositionTest {
    private Category category(CategoryGender policy, int size, Integer... counts) {
        Category c = new Category(); c.setGenderPolicy(policy); c.setTeamSize(size); c.setAllowedMaleCounts(List.of(counts)); return c;
    }
    @Test void categoriasFemininasEMasculinasRejeitamComposicaoDiferente() {
        assertDoesNotThrow(() -> CategoryComposition.validateTeam(category(CategoryGender.FEMALE,2), List.of(Gender.F,Gender.F)));
        assertDoesNotThrow(() -> CategoryComposition.validateTeam(category(CategoryGender.MALE,1), List.of(Gender.M)));
        assertThrows(ApiException.class, () -> CategoryComposition.validateTeam(category(CategoryGender.MALE,2), List.of(Gender.M,Gender.F)));
        assertThrows(ApiException.class, () -> CategoryComposition.validateTeam(category(CategoryGender.FEMALE,1), List.of(Gender.M)));
    }
    @Test void equipesMistasRespeitamTodasAsComposicoesPermitidasETamanhoExato() {
        Category c = category(CategoryGender.MIXED,3,1,2);
        assertDoesNotThrow(() -> CategoryComposition.validateTeam(c,List.of(Gender.M,Gender.F,Gender.F)));
        assertDoesNotThrow(() -> CategoryComposition.validateTeam(c,List.of(Gender.M,Gender.M,Gender.F)));
        assertThrows(ApiException.class, () -> CategoryComposition.validateTeam(c,List.of(Gender.M,Gender.M,Gender.M)));
        assertThrows(ApiException.class, () -> CategoryComposition.validateTeam(c,List.of(Gender.M,Gender.F)));
        assertThrows(ApiException.class, () -> CategoryComposition.validateTeam(category(CategoryGender.MIXED,3,1),List.of(Gender.M,Gender.M,Gender.F)));
    }
    @Test void legadoNaoRecebeInscricaoAntesDaDefinicaoPeloOrganizador() {
        assertThrows(ApiException.class, () -> CategoryComposition.validateTeam(category(null,1),List.of(Gender.M)));
    }
    @Test void composicoesInvalidasNaoSaoPersistidas() {
        for (var counts : List.of(List.<Integer>of(),List.of(0),List.of(3))) {
            assertThrows(ApiException.class, () -> CategoryComposition.allowedCounts(new CategoryRequest("RX",null,3,null,BigDecimal.ZERO,0,CategoryGender.MIXED,counts)));
        }
        assertEquals(List.of(1,2), CategoryComposition.allowedCounts(new CategoryRequest("RX",null,3,null,BigDecimal.ZERO,0,CategoryGender.MIXED,List.of(2,1,1))));
    }
}
