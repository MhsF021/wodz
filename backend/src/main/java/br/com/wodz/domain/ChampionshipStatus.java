package br.com.wodz.domain;

/** Ciclo de vida do campeonato. DRAFT não aparece na busca pública. */
public enum ChampionshipStatus {
    DRAFT, PUBLISHED, IN_PROGRESS, FINISHED, CANCELLED
}
