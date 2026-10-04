package br.com.wodz.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Referências resumidas reutilizadas em várias respostas. */
public final class Refs {

    private Refs() {
    }

    public record ChampionshipRef(Long id, String slug, String name, LocalDate startDate, LocalDate endDate,
                                  String venueName, String city, String state) {
    }

    public record CategoryRef(Long id, String name, BigDecimal price) {
    }
}
