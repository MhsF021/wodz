package br.com.wodz.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChampionshipTest {

    /** 23:59:59 de 13/11 e 00:00 de 14/11 no horário de Brasília (UTC-3). */
    private static final Instant VESPERA = Instant.parse("2026-11-14T02:59:59Z");
    private static final Instant INICIO = Instant.parse("2026-11-14T03:00:00Z");

    private Championship championship;

    @BeforeEach
    void setUp() {
        championship = new Championship();
        championship.setStartDate(LocalDate.of(2026, 11, 14));
        championship.setEndDate(LocalDate.of(2026, 11, 15));
        championship.setStatus(ChampionshipStatus.PUBLISHED);
        championship.setRegistrationOpen(true);
    }

    @Test
    void comecaAZeroHoraDoDiaDeInicioNoHorarioDeBrasilia() {
        assertEquals(INICIO, championship.startsAt());
        assertFalse(championship.hasStarted(VESPERA));
        assertTrue(championship.hasStarted(INICIO));
    }

    @Test
    void aceitaInscricoesAteAVesperaDoInicio() {
        // Status continua PUBLISHED: o organizador não precisa lembrar de mudar para "em andamento".
        assertTrue(championship.isAcceptingRegistrations(VESPERA));
        assertFalse(championship.isAcceptingRegistrations(INICIO));
        assertFalse(championship.isAcceptingRegistrations(Instant.parse("2026-11-15T15:00:00Z")));
    }

    @Test
    void fimDoPeriodoDepoisDoInicioNaoMantemInscricoesAbertas() {
        championship.setRegistrationClosesAt(Instant.parse("2026-11-20T00:00:00Z"));

        assertFalse(championship.isAcceptingRegistrations(INICIO));
    }
}
