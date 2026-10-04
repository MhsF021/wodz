package br.com.wodz.service;

import br.com.wodz.common.ApiException;
import br.com.wodz.domain.ScoringSystem;
import br.com.wodz.dto.ChampionshipDtos.ChampionshipRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChampionshipValidationTest {

    /** 0h de 14/11/2026 no horário de Brasília. */
    private static final Instant INICIO = Instant.parse("2026-11-14T03:00:00Z");

    private static ChampionshipRequest request(Instant opensAt, Instant closesAt) {
        return new ChampionshipRequest("Arena 911", null, "Arena", null, "Recife", "PE",
                LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15), opensAt, closesAt,
                ScoringSystem.POINTS_TABLE, 100, 5, null);
    }

    @Test
    void aceitaPeriodoQueFechaAteOInicio() {
        assertDoesNotThrow(() -> ChampionshipService.validate(request(null, null)));
        assertDoesNotThrow(() -> ChampionshipService.validate(
                request(Instant.parse("2026-10-01T12:00:00Z"), INICIO)));
    }

    @Test
    void recusaPeriodoQueFechaDepoisDoInicio() {
        assertThrows(ApiException.class, () -> ChampionshipService.validate(
                request(null, INICIO.plusSeconds(60))));
    }

    @Test
    void recusaPeriodoQueAbreNoDiaDoCampeonato() {
        assertThrows(ApiException.class, () -> ChampionshipService.validate(request(INICIO, null)));
    }
}
